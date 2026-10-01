package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.DeliveryConfigurationFileConditionRepository;
import be.dda.catalogimport.dao.DeliveryRepository;
import be.dda.catalogimport.dao.ExternalCredentialRepository;
import be.dda.catalogimport.dao.FetchFileObservationRepository;
import be.dda.catalogimport.dao.TaskRunRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.ConnectionProfileVersion;
import be.dda.catalogimport.domain.Delivery;
import be.dda.catalogimport.domain.DeliveryConfigurationVersion;
import be.dda.catalogimport.domain.DeliverySourceKind;
import be.dda.catalogimport.domain.ExternalCredential;
import be.dda.catalogimport.domain.ExternalCredentialSecretKind;
import be.dda.catalogimport.domain.ExternalCredentialStatus;
import be.dda.catalogimport.domain.FetchFileDecision;
import be.dda.catalogimport.domain.FetchFileObservation;
import be.dda.catalogimport.domain.TaskRun;
import be.dda.catalogimport.domain.TaskRunStatus;
import be.dda.catalogimport.domain.TaskRunTriggerSource;
import be.dda.catalogimport.service.DeliveryArchiveStore.ArchivedObject;
import be.dda.catalogimport.service.DeliveryIntakeService.IntakeResult;
import be.dda.catalogimport.service.SftpConnector.DownloadPlan;
import be.dda.catalogimport.service.SftpConnector.FetchResult;
import be.dda.catalogimport.service.SftpConnector.Listing;
import be.dda.catalogimport.service.SftpConnector.PasswordSource;
import be.dda.catalogimport.service.SftpConnector.PinnedHostKey;
import be.dda.catalogimport.service.SftpConnector.RemoteFile;
import be.dda.catalogimport.service.support.RemoteFileSelection;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handmatig "Nu ophalen" via de Leveringsconfiguratie van een taak: bouwstap K-4b van
 * {@code docs/design/leveringsconfiguratie-design.md} par. 4; beslissingslog 2026-09-29 L2, L4-L6, A3, A4, A9, A12, A13,
 * A15, A18. Synchroon en zonder automatische retry (A9); geen scheduler (L1).
 *
 * <h2>Flow</h2>
 * <ol>
 *   <li><b>Voorcontroles zonder run</b> (niets vastgelegd, niets gecontacteerd), in deze volgorde: 404
 *       {@code FETCH_NOT_CONFIGURED} (allowlist niet gezet, L4b) → 404 {@code TASK_NOT_FOUND} → 409
 *       {@code TASK_HAS_NO_DELIVERY_CONFIGURATION} → 409 {@code SECRETS_NOT_CONFIGURED} → 409 {@code CREDENTIAL_REVOKED}
 *       → 409 {@code NO_ACTIVE_REVISION}/{@code CONFIG_PRICE_FIELD_MISSING}/{@code CONFIG_REQUIRED_BOOKMARK_MISSING}
 *       (dezelfde inrichtingscontroles als de upload) → 409 {@code TASK_RUN_IN_PROGRESS}. Alles onder het rijslot op de
 *       taak (zelfde slot als de taakkoppeling).</li>
 *   <li>In dezelfde transactie een {@code task_run} {@code RUNNING} ({@code trigger_source = MANUAL_FETCH},
 *       {@code triggered_by} = de mens, de DC-versie). Vanaf hier is elke uitkomst een run.</li>
 *   <li>Buiten elke transactie: allowlist + één DNS-opzoeking, verbinden met de vastgepinde hostsleutel, aanmelden (het
 *       wachtwoord wordt pas na de hostsleutelcontrole ontsleuteld, leeft enkel in het geheugen van die sessie en wordt
 *       nooit gelogd), map lijsten, voorwaarden.</li>
 *   <li>Keuze (L6) in een korte leestransactie: per matchend bestand de sleutel (A4) en één query tegen
 *       {@code delivery(task_id, idempotency_key)}; de watermark is het laatst opgehaalde bestand van deze taak
 *       ({@link FetchFileObservationRepository#findFirstByTaskRunTaskIdAndDeliveryIsNotNullOrderByIdDesc}). Zie
 *       {@link #decide}.</li>
 *   <li>Het gekozen bestand streamen naar {@link DeliveryArchiveStore#store} met byte-guard (min(DC-grens, 1 GB)), dan
 *       her-stat (zie {@link SftpConnector#fetchOne}).</li>
 *   <li>Eén transactie: levering, bestand en batch in de bestaande run ({@link DeliveryIntakeService#registerInRun},
 *       {@code source_kind = SFTP}), de waarnemingen ({@code fetch_file_observation}, de gekozen met
 *       {@code delivery_id}), {@code outcome_code = FETCHED} en {@code pending_file_count}.</li>
 *   <li>Screening zoals bij de upload ({@link DeliveryReceptionService#screenIfCreated}); die sluit de run af.</li>
 * </ol>
 * Niets nieuws (geen match, alles al opgehaald, te jong, te groot, ouder dan de watermark): de run sluit meteen als
 * {@code COMPLETED}/{@code NO_NEW_FILE}, met alle waarnemingen.
 *
 * <h2>Fouten</h2>
 * Een remote fout (verbinding, hostsleutel, aanmelding, map, bestand, grootte, wijziging tijdens de overdracht) is geen
 * HTTP-fout: de run gaat naar {@code FAILED} met de {@code outcome_code} en het antwoord is die run (201). De taak blijft
 * niet geblokkeerd (de run is terminaal). Een onverwachte interne fout (bv. het lokale archief) zet de run op
 * {@code FAILED}/{@code FETCH_INTERNAL_ERROR} en wordt daarna doorgegeven (500). Verandert de inrichting tijdens de
 * download (bv. geen actieve revisie meer), dan is de run {@code FAILED} met die foutcode.
 *
 * <h2>Archief bij falen</h2>
 * Na een mislukte download blijft niets half in het archief: {@link DeliveryArchiveStore#store} verwijdert zijn eigen
 * gedeeltelijke bestand als de stream breekt of de byte-guard ingrijpt; is het bestand volledig geschreven maar faalt
 * daarna de her-stat, de registratie of blijkt het al opgehaald, dan ruimt deze service het object expliciet op
 * ({@link DeliveryArchiveStore#deleteQuietly}). Enkel een crash van het proces tussen archiveren en registreren laat
 * een wees-object achter (zelfde aanvaarde situatie als bij de upload). Een vastgelopen {@code RUNNING}-run herstelt
 * {@link FetchRunRecoveryService} (K-4c): handmatig afbreken of automatisch bij het opstarten; een late afronding van
 * een afgebroken run wordt geweigerd ({@code TASK_RUN_NOT_RUNNING}) of laat de {@code FAILED}-toestand ongemoeid.
 */
@Service
public class FetchRunService {

    /** Globale bovengrens per bestand (A12): 1 GB = 1024³ bytes, zelfde eenheid als de DC-validatie (LC-2). */
    public static final long GLOBAL_MAX_FILE_BYTES = 1024L * 1024L * 1024L;
    /** Voorvoegsel van de idempotentiesleutel van een SFTP-levering (A4). */
    public static final String KEY_PREFIX = "sftp:";

    public static final String CODE_TASK_NOT_FOUND = "TASK_NOT_FOUND";
    public static final String CODE_NO_DELIVERY_CONFIGURATION = TaskDeliveryConfigurationService.CODE_NOT_BOUND;
    public static final String CODE_RUN_IN_PROGRESS = TaskDeliveryConfigurationService.CODE_RUN_IN_PROGRESS;
    public static final String CODE_CREDENTIAL_REVOKED = FetchOutcomeCodes.CREDENTIAL_REVOKED;

    static final int MAX_ACTOR_LENGTH = 100;

    private static final Logger LOG = LoggerFactory.getLogger(FetchRunService.class);

    /** Oudste eerst, bij gelijke tijd op naam; bestanden zonder tijd achteraan. */
    private static final Comparator<RemoteFile> CHRONOLOGICAL = Comparator
            .comparing(RemoteFile::modifiedAt, Comparator.<Instant>nullsLast(Comparator.<Instant>naturalOrder()))
            .thenComparing(RemoteFile::name);

    /** Momentopname bij de start van de run; {@code toString} zonder ciphertext. */
    private record Plan(long runId, long taskId, String host, int port, String username, String hostKeyAlgorithm,
                        String hostKeyFingerprint, UUID credentialRef, ExternalCredentialSecretKind secretKind,
                        String ciphertext, String remoteDirectory, RemoteFileSelection selection,
                        int minFileAgeSeconds, long byteLimit) {
        @Override
        public String toString() {
            return "Plan[runId=" + runId + ", taskId=" + taskId + "]";
        }
    }

    /** Een matchend bestand met zijn beslissing, in chronologische volgorde. */
    private record Decided(RemoteFile file, FetchFileDecision decision) {
    }

    /** De laatst opgehaalde positie (L6): wijzigingstijd in seconden + naam. */
    private record Watermark(long epochSecond, String name) {
    }

    /** Wat tijdens de sessie beslist en gearchiveerd werd. */
    private static final class Progress {
        /** {@code null} = er werd (nog) niet gelijst. */
        private List<Decided> decisions;
        private RemoteFile chosen;
        /** Het archiefobject van de download; {@code null} zodra het bij een levering hoort of opgeruimd is. */
        private ArchivedObject archived;
    }

    private final FetchHostPolicy hostPolicy;
    private final SftpConnector connector;
    private final SecretsService secrets;
    private final DeliveryArchiveStore archive;
    private final DeliveryIntakeService intake;
    private final DeliveryReceptionService reception;
    private final TaskRunQueryService runQueries;
    private final CatalogImportTaskRepository tasks;
    private final TaskRunRepository runs;
    private final DeliveryRepository deliveries;
    private final ExternalCredentialRepository credentials;
    private final DeliveryConfigurationFileConditionRepository conditions;
    private final FetchFileObservationRepository observations;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readTransaction;
    private final Clock clock;

    public FetchRunService(FetchHostPolicy hostPolicy, SftpConnector connector, SecretsService secrets,
                           DeliveryArchiveStore archive, DeliveryIntakeService intake,
                           DeliveryReceptionService reception, TaskRunQueryService runQueries,
                           CatalogImportTaskRepository tasks, TaskRunRepository runs, DeliveryRepository deliveries,
                           ExternalCredentialRepository credentials,
                           DeliveryConfigurationFileConditionRepository conditions,
                           FetchFileObservationRepository observations,
                           PlatformTransactionManager transactionManager, Clock clock) {
        this.hostPolicy = hostPolicy;
        this.connector = connector;
        this.secrets = secrets;
        this.archive = archive;
        this.intake = intake;
        this.reception = reception;
        this.runQueries = runQueries;
        this.tasks = tasks;
        this.runs = runs;
        this.deliveries = deliveries;
        this.credentials = credentials;
        this.conditions = conditions;
        this.observations = observations;
        this.transaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.clock = clock;
    }

    /**
     * "Nu ophalen" voor {@code taskId} (zie klassedocumentatie).
     *
     * @return de run (met waarnemingen), ook bij een remote fout
     * @throws NotFoundException 404 {@code FETCH_NOT_CONFIGURED}, {@code TASK_NOT_FOUND}
     * @throws ConflictException 409 {@code TASK_HAS_NO_DELIVERY_CONFIGURATION}, {@code SECRETS_NOT_CONFIGURED},
     *                           {@code CREDENTIAL_REVOKED}, {@code NO_ACTIVE_REVISION}, {@code CONFIG_PRICE_FIELD_MISSING},
     *                           {@code CONFIG_REQUIRED_BOOKMARK_MISSING}, {@code TASK_RUN_IN_PROGRESS}
     */
    public TaskRunView fetch(long taskId, ActorIdentity actor) {
        String by = requireActor(actor);
        hostPolicy.requireConfigured();
        Plan plan = transaction.execute(status -> start(taskId, by));
        LOG.info("Fetch run {} of task {} started by {}", plan.runId(), plan.taskId(), by);

        Progress progress = new Progress();
        IntakeResult registered;
        try {
            FetchTarget target = hostPolicy.resolve(plan.host(), plan.port());
            // Pas aangeroepen na een geslaagde hostsleutelcontrole; de waarde leeft enkel in deze sessie.
            PasswordSource password = () -> secrets.decrypt(plan.ciphertext(), plan.credentialRef(),
                    plan.secretKind().name());
            FetchResult result = connector.fetchOne(target, plan.username(),
                    new PinnedHostKey(plan.hostKeyAlgorithm(), plan.hostKeyFingerprint()), password,
                    plan.remoteDirectory(), plan.selection(), plan.byteLimit(), new DownloadPlan() {
                        @Override
                        public RemoteFile choose(Listing listing) {
                            progress.decisions = decide(plan, listing.matchedFiles());
                            progress.chosen = progress.decisions.stream()
                                    .filter(d -> d.decision() == FetchFileDecision.SELECTED)
                                    .map(Decided::file).findFirst().orElse(null);
                            return progress.chosen;
                        }

                        @Override
                        public void receive(RemoteFile file, InputStream content) {
                            progress.archived = archive.store(content, file.name());
                        }
                    });
            if (result.downloaded() == null) {
                close(plan, progress, TaskRunStatus.COMPLETED, FetchOutcomeCodes.NO_NEW_FILE,
                        noNewFileMessage(progress.decisions));
                return runQueries.getRun(plan.runId());
            }
            registered = register(plan, progress, actor);
            if (registered == null) {
                return runQueries.getRun(plan.runId());
            }
        } catch (FetchFailureException failed) {
            discard(progress);
            close(plan, progress, TaskRunStatus.FAILED, failed.getCode(), failed.getMessage());
            return runQueries.getRun(plan.runId());
        } catch (RuntimeException | Error unexpected) {
            discard(progress);
            failAfterUnexpected(plan, progress, unexpected);
            throw unexpected;
        }
        // Buiten de try: het archiefobject hoort nu bij de geregistreerde levering en wordt nooit meer opgeruimd.
        reception.screenIfCreated(registered);
        return runQueries.getRun(plan.runId());
    }

    // --- Stap 1-2: voorcontroles en run ------------------------------------------------------------------------

    private Plan start(long taskId, String by) {
        // Eerste lezing = rijslot (zelfde slot als de taakkoppeling en de upload-registratie).
        CatalogImportTask task = tasks.findByIdForUpdate(taskId).orElseThrow(() -> new NotFoundException(
                CODE_TASK_NOT_FOUND, "Task " + taskId + " not found"));
        DeliveryConfigurationVersion version = task.getDeliveryConfigurationVersion();
        if (version == null) {
            throw new ConflictException(CODE_NO_DELIVERY_CONFIGURATION, "Task " + taskId
                    + " has no delivery configuration; bind one before fetching");
        }
        if (!secrets.configured()) {
            throw new SecretsNotConfiguredException();
        }
        ConnectionProfileVersion profile = version.getConnectionProfileVersion();
        ExternalCredential credential = credentials.findById(profile.getCredentialId())
                .orElseThrow(() -> new IllegalStateException("The credential of connection profile version "
                        + profile.getId() + " is missing"));
        if (credential.getStatus() != ExternalCredentialStatus.ACTIVE) {
            throw new ConflictException(CODE_CREDENTIAL_REVOKED, "The credential of the connection profile of this "
                    + "task is revoked; give it a new value first (nothing was contacted, no run was started)");
        }
        intake.requireIntakeConfiguration(task);
        if (!runs.findByTaskIdAndStatus(taskId, TaskRunStatus.RUNNING).isEmpty()
                || !runs.findByTaskIdAndStatus(taskId, TaskRunStatus.PENDING).isEmpty()) {
            throw inProgress(taskId);
        }
        RemoteFileSelection selection = ConnectionTestService.selectionOf(version, conditions);

        TaskRun run = new TaskRun(task, now(), by);
        run.setStatus(TaskRunStatus.RUNNING);
        run.setTriggerSource(TaskRunTriggerSource.MANUAL_FETCH);
        run.setDeliveryConfigurationVersion(version);
        try {
            runs.saveAndFlush(run);
        } catch (DataIntegrityViolationException concurrentRun) {
            // uk_task_run_concurrency: laatste verdediging; het rijslot hierboven maakt dit normaal onmogelijk.
            throw inProgress(taskId);
        }
        return new Plan(run.getId(), taskId, profile.getHost(), profile.getPort(), profile.getUsername(),
                profile.getHostKeyAlgorithm(), profile.getHostKeyFingerprintSha256(), credential.getCredentialRef(),
                credential.getSecretKind(), credential.getCiphertext(), version.getRemoteDirectory(), selection,
                version.getMinFileAgeSeconds(), Math.min(version.getMaxFileBytes(), GLOBAL_MAX_FILE_BYTES));
    }

    // --- Stap 4-5: keuze (L6) ------------------------------------------------------------------------------------

    /**
     * De beslissing per matchend bestand (L6), chronologisch (oudste eerst, bij gelijke tijd op naam):
     * <ul>
     *   <li>zonder wijzigingstijd van de server: {@code TOO_YOUNG} (de minimale ouderdom is niet aantoonbaar);</li>
     *   <li>sleutel (A4) heeft al een levering bij deze taak: {@code ALREADY_FETCHED};</li>
     *   <li>(tijd, naam) kleiner dan de watermark: {@code OLDER_THAN_WATERMARK} - nooit een bestand ouder dan het laatst
     *       opgehaalde. Gelijke positie met een andere grootte is een nieuw remote object en telt als nieuw;</li>
     *   <li><b>eerste run</b> (nog geen watermark): enkel het recentste nieuwe bestand komt in aanmerking, de andere zijn
     *       {@code OLDER_THAN_WATERMARK};</li>
     *   <li>van de overblijvers, oudste eerst: jonger dan de minimale ouderdom = {@code TOO_YOUNG}, groter dan de grens =
     *       {@code TOO_LARGE}, het eerste andere = {@code SELECTED}, de rest {@code DEFERRED} (één bestand per run).</li>
     * </ul>
     * Een {@code TOO_LARGE}-bestand houdt de volgorde niet tegen: het nieuwere bestand wordt gekozen en het te grote
     * valt daarna onder de watermark (zichtbaar in de waarnemingen van deze run).
     */
    private List<Decided> decide(Plan plan, List<RemoteFile> matched) {
        List<RemoteFile> ordered = matched.stream().sorted(CHRONOLOGICAL).toList();
        Map<String, String> keyByName = new HashMap<>();
        for (RemoteFile file : ordered) {
            if (file.modifiedAt() != null) {
                keyByName.put(file.name(), idempotencyKey(plan.host(), plan.port(),
                        SftpConnector.remotePath(plan.remoteDirectory(), file.name()),
                        file.modifiedAt().getEpochSecond(), file.size()));
            }
        }
        Set<String> existing = new HashSet<>();
        Watermark[] watermark = new Watermark[1];
        readTransaction.executeWithoutResult(status -> {
            if (!keyByName.isEmpty()) {
                existing.addAll(deliveries.findExistingIdempotencyKeys(plan.taskId(), keyByName.values()));
            }
            observations.findFirstByTaskRunTaskIdAndDeliveryIsNotNullOrderByIdDesc(plan.taskId())
                    .filter(o -> o.getRemoteModifiedAt() != null)
                    .ifPresent(o -> watermark[0] = new Watermark(o.getRemoteModifiedAt().getEpochSecond(),
                            o.getRemoteFileName()));
        });

        Map<String, FetchFileDecision> decisions = new LinkedHashMap<>();
        List<RemoteFile> candidates = new ArrayList<>();
        for (RemoteFile file : ordered) {
            if (file.modifiedAt() == null) {
                decisions.put(file.name(), FetchFileDecision.TOO_YOUNG);
            } else if (existing.contains(keyByName.get(file.name()))) {
                decisions.put(file.name(), FetchFileDecision.ALREADY_FETCHED);
            } else if (watermark[0] != null && compare(file, watermark[0]) < 0) {
                decisions.put(file.name(), FetchFileDecision.OLDER_THAN_WATERMARK);
            } else {
                candidates.add(file);
            }
        }
        if (watermark[0] == null && candidates.size() > 1) {
            // Eerste run na het koppelen: enkel het recentste (L6); oudere nooit, anders draait de historiek prijzen terug.
            RemoteFile newest = candidates.get(candidates.size() - 1);
            for (RemoteFile older : candidates.subList(0, candidates.size() - 1)) {
                decisions.put(older.name(), FetchFileDecision.OLDER_THAN_WATERMARK);
            }
            candidates = List.of(newest);
        }
        Instant now = clock.instant();
        boolean selected = false;
        for (RemoteFile file : candidates) {
            if (file.modifiedAt().plusSeconds(plan.minFileAgeSeconds()).isAfter(now)) {
                decisions.put(file.name(), FetchFileDecision.TOO_YOUNG);
            } else if (file.size() > plan.byteLimit()) {
                decisions.put(file.name(), FetchFileDecision.TOO_LARGE);
            } else if (!selected) {
                decisions.put(file.name(), FetchFileDecision.SELECTED);
                selected = true;
            } else {
                decisions.put(file.name(), FetchFileDecision.DEFERRED);
            }
        }
        List<Decided> result = new ArrayList<>(ordered.size());
        for (RemoteFile file : ordered) {
            result.add(new Decided(file, decisions.get(file.name())));
        }
        return List.copyOf(result);
    }

    private static int compare(RemoteFile file, Watermark watermark) {
        int byTime = Long.compare(file.modifiedAt().getEpochSecond(), watermark.epochSecond());
        return byTime != 0 ? byTime : file.name().compareTo(watermark.name());
    }

    /**
     * A4: {@code sftp:<eerste 32 hex van sha256(lower(host) ":" port ":" absoluutPad)>:<mtimeEpochSec>:<byteSize>},
     * zonder DC-versie-ID (een nieuwe DC-versie haalt hetzelfde bestand niet opnieuw op).
     */
    static String idempotencyKey(String host, int port, String absolutePath, long modifiedEpochSecond, long byteSize) {
        String location = host.toLowerCase(Locale.ROOT) + ":" + port + ":" + absolutePath;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(location.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + HexFormat.of().formatHex(digest).substring(0, 32) + ":" + modifiedEpochSecond + ":"
                    + byteSize;
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 not available", unavailable);
        }
    }

    // --- Stap 6: registratie -------------------------------------------------------------------------------------

    /** @return het intakeresultaat, of {@code null} als de run door een gewijzigde inrichting al als FAILED gesloten is */
    private IntakeResult register(Plan plan, Progress progress, ActorIdentity actor) {
        RemoteFile file = progress.chosen;
        String key = idempotencyKey(plan.host(), plan.port(),
                SftpConnector.remotePath(plan.remoteDirectory(), file.name()), file.modifiedAt().getEpochSecond(),
                file.size());
        IntakeResult result;
        try {
            result = transaction.execute(status -> {
                IntakeResult intakeResult = intake.registerInRun(plan.runId(), key, actor, file.name(),
                        DeliverySourceKind.SFTP, progress.archived);
                TaskRun run = runs.findById(plan.runId()).orElseThrow();
                if (intakeResult.created()) {
                    Delivery delivery = deliveries.getReferenceById(intakeResult.delivery().deliveryId());
                    writeObservations(run, progress.decisions, delivery);
                    int pending = count(progress.decisions, FetchFileDecision.DEFERRED);
                    run.setOutcome(FetchOutcomeCodes.FETCHED, "Fetched '" + file.name() + "' (" + file.size()
                            + " bytes)" + (pending == 0 ? "" : "; " + pending + " newer file(s) wait for a next run"));
                    run.setPendingFileCount(pending);
                } else {
                    // Laatste verdediging (uk_delivery_idempotency): intussen al als levering geregistreerd.
                    List<Decided> already = progress.decisions.stream()
                            .map(d -> d.file().equals(file) ? new Decided(file, FetchFileDecision.ALREADY_FETCHED) : d)
                            .toList();
                    writeObservations(run, already, null);
                    run.setOutcome(FetchOutcomeCodes.NO_NEW_FILE, "'" + file.name() + "' was already fetched");
                    run.setPendingFileCount(count(already, FetchFileDecision.DEFERRED));
                    run.setStatus(TaskRunStatus.COMPLETED);
                    run.setFinishedAt(now());
                }
                runs.saveAndFlush(run);
                return intakeResult;
            });
        } catch (ConflictException conflict) {
            // De inrichting veranderde tijdens de download (bv. geen actieve revisie meer) of de run liep niet meer.
            discard(progress);
            close(plan, progress, TaskRunStatus.FAILED, conflict.getCode(), conflict.getMessage());
            return null;
        } catch (NotFoundException notFound) {
            // De inrichting veranderde tijdens de download (bv. geen actieve revisie meer) of de run liep niet meer.
            discard(progress);
            close(plan, progress, TaskRunStatus.FAILED, notFound.getCode(), notFound.getMessage());
            return null;
        }
        if (result == null || !result.created()) {
            discard(progress);
        }
        progress.archived = null;
        return result;
    }

    // --- Afsluiten zonder levering -------------------------------------------------------------------------------

    /**
     * Sluit de run (enkel als ze nog loopt) met haar waarnemingen; nooit een levering. Onder het rijslot van de taak
     * en met een verse lezing daarna (K-4c): een gelijktijdige afbreking of herstel ({@link FetchRunRecoveryService})
     * neemt hetzelfde slot, dus een afgebroken run blijft {@code FAILED} met haar eigen uitkomst.
     */
    private void close(Plan plan, Progress progress, TaskRunStatus status, String code, String message) {
        transaction.executeWithoutResult(tx -> {
            tasks.findByIdForUpdate(plan.taskId());
            TaskRun run = runs.findById(plan.runId()).orElseThrow();
            if (run.getStatus() != TaskRunStatus.RUNNING) {
                LOG.warn("Fetch run {} was already closed ({}); outcome {} not recorded", plan.runId(),
                        run.getStatus(), code);
                return;
            }
            if (progress.decisions != null) {
                writeObservations(run, progress.decisions, null);
            }
            run.setOutcome(code, message);
            run.setPendingFileCount(progress.decisions == null ? null
                    : count(progress.decisions, FetchFileDecision.DEFERRED));
            run.setStatus(status);
            run.setFinishedAt(now());
            runs.saveAndFlush(run);
        });
        LOG.info("Fetch run {} of task {}: {} {}", plan.runId(), plan.taskId(), status, code);
    }

    private void failAfterUnexpected(Plan plan, Progress progress, Throwable unexpected) {
        LOG.error("Fetch run {} of task {} failed unexpectedly", plan.runId(), plan.taskId(), unexpected);
        try {
            close(plan, progress, TaskRunStatus.FAILED, FetchOutcomeCodes.FETCH_INTERNAL_ERROR,
                    "An internal error stopped the fetch; nothing was registered. See the application log");
        } catch (RuntimeException secondary) {
            LOG.error("Cannot mark fetch run {} as FAILED", plan.runId(), secondary);
        }
    }

    private void writeObservations(TaskRun run, List<Decided> decided, Delivery delivery) {
        List<FetchFileObservation> rows = new ArrayList<>(decided.size());
        for (Decided d : decided) {
            boolean withDelivery = delivery != null && d.decision() == FetchFileDecision.SELECTED;
            Instant modified = d.file().modifiedAt() == null ? null
                    : d.file().modifiedAt().truncatedTo(ChronoUnit.MICROS);
            rows.add(new FetchFileObservation(run, d.file().name(), d.file().size(), modified, d.decision(),
                    withDelivery ? delivery : null));
        }
        observations.saveAll(rows);
        observations.flush();
    }

    private void discard(Progress progress) {
        if (progress.archived != null) {
            archive.deleteQuietly(progress.archived.archiveReference());
            progress.archived = null;
        }
    }

    private static String noNewFileMessage(List<Decided> decisions) {
        if (decisions == null || decisions.isEmpty()) {
            return "No file in the remote directory matches the delivery configuration";
        }
        Map<FetchFileDecision, Integer> counts = new EnumMap<>(FetchFileDecision.class);
        for (Decided d : decisions) {
            counts.merge(d.decision(), 1, Integer::sum);
        }
        StringBuilder text = new StringBuilder("No new file to fetch: ").append(decisions.size()).append(" matching");
        counts.forEach((decision, n) -> text.append(", ").append(n).append(' ').append(decision));
        return text.toString();
    }

    private static int count(Collection<Decided> decisions, FetchFileDecision decision) {
        int n = 0;
        for (Decided d : decisions) {
            if (d.decision() == decision) {
                n++;
            }
        }
        return n;
    }

    private static ConflictException inProgress(long taskId) {
        return new ConflictException(CODE_RUN_IN_PROGRESS, "Task " + taskId + " already has a run in progress");
    }

    private static String requireActor(ActorIdentity actor) {
        if (actor == null) {
            throw new IllegalArgumentException("Missing actor");
        }
        return ActorNames.requireActorName(actor.username(), "actor", MAX_ACTOR_LENGTH);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
