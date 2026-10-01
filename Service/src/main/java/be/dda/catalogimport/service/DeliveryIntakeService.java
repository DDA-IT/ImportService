package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.BatchBookmarkHashDao;
import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.DeliveryFileRepository;
import be.dda.catalogimport.dao.DeliveryRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.TaskRunRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.Delivery;
import be.dda.catalogimport.domain.DeliveryFile;
import be.dda.catalogimport.domain.DeliverySourceKind;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.TaskRun;
import be.dda.catalogimport.domain.TaskRunStatus;
import be.dda.catalogimport.domain.TaskRunTriggerSource;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.service.DeliveryArchiveStore.ArchivedObject;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ontvangt een manueel geüploade CSV-levering (design par. 6, par. 9 stap A en B, par. 10).
 * <p>
 * <b>Businessgedrag.</b> Een upload is alleen toegelaten op een taak met {@link TaskTriggerType#MANUAL}
 * die een actieve {@link ImportDefinitionRevision} en een basisprijsveld heeft. De levering krijgt
 * {@code idempotencyKey = "manual:" + deliveryReference}: dezelfde referentie met identieke
 * bestandshash is een retry (bestaande levering terug, geen tweede run of batch); dezelfde referentie
 * met andere inhoud is een conflict. Een tweede upload op een taak met een niet-afgeronde
 * {@link TaskRun} botst op {@code uk_task_run_concurrency}.
 * <p>
 * <b>Technische volgorde.</b> (1) Read-only voorcontrole zodat een afgekeurde aanvraag niets
 * archiveert, (2) archiveren buiten elke transactie, (3) één korte transactie die
 * {@code TaskRun}, {@code Delivery}, {@code DeliveryFile} en {@code ImportBatch} registreert. Faalt
 * (3) of blijkt de upload een retry, dan wordt het zojuist geschreven archiefobject opgeruimd. Het
 * orkestreren gebeurt met {@link TransactionTemplate}; deze klasse is zelf niet {@code @Transactional}.
 * <p>
 * <b>Twee ontvangstwegen, één intake.</b> Sinds de tweede ontvangstweg (beslissingslog 2026-09-27) komt de
 * stream ofwel van de browser-upload, ofwel van een bestand uit de beheerde servermap
 * ({@code LocalSourceDirectory}). Voor deze service is dat hetzelfde werk: dezelfde archivering, dezelfde
 * idempotentiesleutel en dezelfde controles. Het enige verschil is de {@code DeliverySourceKind} die
 * meekomt en permanent op de {@link Delivery} bewaard wordt; de bestaande overloads zonder die parameter
 * blijven bestaan en betekenen {@code UPLOAD}.
 * <p>
 * <b>Racevenster A10 (bouwstap K-4b, LC-2 open punt 2).</b> De registratietransactie (3) neemt als
 * <b>eerste</b> lezing hetzelfde rijslot op de taak als de taakkoppeling ({@code TaskDeliveryConfigurationService}
 * vergrendelt alle taken van de koppeling). Koppelen en registreren sluiten elkaar daardoor uit: komt een koppeling
 * vlak na de voorcontrole (1) maar vóór de registratie, dan wacht de registratie op haar commit en ziet daarna de
 * Leveringsconfiguratie ({@code TASK_HAS_DELIVERY_CONFIGURATION}, het archiefobject wordt opgeruimd); komt de
 * registratie eerst, dan ziet de koppeling na het slot de lopende run ({@code TASK_RUN_IN_PROGRESS}). Er kan dus nooit
 * een upload- of servermaprun ontstaan op een taak met een Leveringsconfiguratie.
 * <p>
 * <b>Intake in een bestaande run (K-4b).</b> Een ophaalrun maakt haar {@link TaskRun} al vóór het verbinden (een
 * aanmeldfout blijft zo zichtbaar zonder levering). {@link #registerInRun} registreert levering, bestand en batch in
 * die bestaande run, met dezelfde revisiecontroles en dezelfde batchopbouw als de upload.
 * <p>
 * <b>Grens van deze service.</b> De intake registreert en archiveert alleen; de batch komt op
 * {@code RECEIVED} te staan en de {@code TaskRun} op {@code RUNNING}. De aanroeper start daarna de screening
 * ({@link DeliveryReceptionService#screenIfCreated}), die de batch naar haar eindstatus brengt en de {@code TaskRun}
 * afsluit. Zolang die run open is, blokkeert de concurrency-constraint een volgende upload op dezelfde taak;
 * dat is het beoogde gedrag. {@code actual_record_count} blijft {@code null} tot de screening geteld
 * heeft.
 */
@Service
public class DeliveryIntakeService {

    static final String KEY_PREFIX = "manual:";
    /** 409: upload/servermap op een taak met een Leveringsconfiguratie (A10, LC-2). */
    public static final String CODE_TASK_HAS_DELIVERY_CONFIGURATION = "TASK_HAS_DELIVERY_CONFIGURATION";
    /** 409: de run waarin geregistreerd moet worden, loopt niet meer ({@link #registerInRun}). */
    public static final String CODE_TASK_RUN_NOT_RUNNING = "TASK_RUN_NOT_RUNNING";
    /** 404: de run waarin geregistreerd moet worden, bestaat niet ({@link #registerInRun}). */
    public static final String CODE_TASK_RUN_NOT_FOUND = "TASK_RUN_NOT_FOUND";
    /** {@code delivery.idempotency_key} is varchar(200). */
    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 200;
    static final int MAX_DELIVERY_REFERENCE_LENGTH = MAX_IDEMPOTENCY_KEY_LENGTH - KEY_PREFIX.length();
    /** {@code task_run.triggered_by} en {@code import_batch.created_by} zijn varchar(100). */
    static final int MAX_UPLOADED_BY_LENGTH = 100;
    /** {@code delivery_file.file_name} is varchar(500). */
    static final int MAX_FILE_NAME_LENGTH = 500;

    /** Resultaat van een upload: {@code created} is {@code false} bij een idempotente retry. */
    public record IntakeResult(boolean created, DeliveryView delivery) {
    }

    private record Resolved(CatalogImportTask task, ImportDefinitionRevision revision, Delivery existing) {
    }

    private final DeliveryArchiveStore archive;
    private final DeliveryQueryService queries;
    private final CatalogImportTaskRepository tasks;
    private final TaskRunRepository runs;
    private final DeliveryRepository deliveries;
    private final DeliveryFileRepository deliveryFiles;
    private final ImportBatchRepository batches;
    private final ChainConfigurationChecks checks;
    private final BatchBookmarkHashDao bookmarkHashes;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnlyTransaction;

    /**
     * Sinds NT-8 komen de revisie-, prijsveld-, bookmark- en taakcontroles uit {@link ChainConfigurationChecks}; de
     * afzonderlijke {@code ImportDefinitionRevisionRepository} en {@code LinkBookmarkValueService} zijn daarom geen
     * parameter meer (enkel Spring bouwt deze service).
     */
    public DeliveryIntakeService(DeliveryArchiveStore archive, DeliveryQueryService queries,
                                 CatalogImportTaskRepository tasks,
                                 TaskRunRepository runs, DeliveryRepository deliveries,
                                 DeliveryFileRepository deliveryFiles, ImportBatchRepository batches,
                                 ChainConfigurationChecks checks,
                                 BatchBookmarkHashDao bookmarkHashes,
                                 PlatformTransactionManager transactionManager) {
        this.archive = archive;
        this.queries = queries;
        this.tasks = tasks;
        this.runs = runs;
        this.deliveries = deliveries;
        this.deliveryFiles = deliveryFiles;
        this.batches = batches;
        this.checks = checks;
        this.bookmarkHashes = bookmarkHashes;
        this.transaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    /**
     * Neemt een levering aan. De stream wordt niet gesloten; dat blijft de verantwoordelijkheid van
     * de aanroeper.
     *
     * @throws NotFoundException  onbekende taak ({@code TASK_NOT_FOUND})
     * @throws ConflictException  {@code TASK_NOT_MANUAL}, {@code TASK_HAS_DELIVERY_CONFIGURATION} (A10),
     *                            {@code NO_ACTIVE_REVISION}, {@code CONFIG_PRICE_FIELD_MISSING},
     *                            {@code CONFIG_REQUIRED_BOOKMARK_MISSING}, {@code TASK_RUN_IN_PROGRESS},
     *                            {@code DELIVERY_REFERENCE_REUSED_WITH_DIFFERENT_CONTENT}
     * @throws IllegalArgumentException ongeldige aanvraagvelden
     */
    public IntakeResult intake(long taskId, String deliveryReference, String uploadedBy,
                               Long expectedRecordCount, Long expectedByteSize,
                               String originalFileName, InputStream content) {
        return intake(taskId, deliveryReference, ActorIdentity.unverified(uploadedBy), expectedRecordCount,
                expectedByteSize, originalFileName, content);
    }

    /**
     * Zoals hierboven, met de geverifieerde identiteit van de uploader (Fase 5-AUTH, 5A-5): de
     * gebruikersnaam komt in {@code import_batch.created_by} (en {@code task_run.triggered_by}), het OIDC-subject
     * in {@code import_batch.created_by_subject}. De Web-laag gebruikt uitsluitend deze overload.
     */
    public IntakeResult intake(long taskId, String deliveryReference, ActorIdentity actor,
                               Long expectedRecordCount, Long expectedByteSize,
                               String originalFileName, InputStream content) {
        return intake(taskId, deliveryReference, actor, expectedRecordCount, expectedByteSize, originalFileName,
                DeliverySourceKind.UPLOAD, content);
    }

    /**
     * Zoals hierboven, met de <b>ontvangstweg</b> waarlangs de bytes binnenkwamen (beslissingslog
     * 2026-09-27, Q2): {@link DeliverySourceKind#UPLOAD} voor de browser-upload,
     * {@link DeliverySourceKind#LOCAL_DIRECTORY} voor een bestand uit de beheerde servermap. Puur additief:
     * de ontvangst zelf, de idempotentie en de archivering zijn voor beide wegen identiek — de weg wordt
     * enkel permanent vastgelegd op de {@link Delivery}, zodat later navertelbaar blijft waar een levering
     * vandaan kwam. Een {@code null} betekent {@code UPLOAD}, nooit "onbekend". Sinds K-4b krijgt de nieuwe
     * {@link TaskRun} ook {@code trigger_source} ({@code UPLOAD} of {@code LOCAL_DIRECTORY}).
     */
    public IntakeResult intake(long taskId, String deliveryReference, ActorIdentity actor,
                               Long expectedRecordCount, Long expectedByteSize,
                               String originalFileName, DeliverySourceKind sourceKind, InputStream content) {
        String reference = requireText(deliveryReference, "deliveryReference", MAX_DELIVERY_REFERENCE_LENGTH);
        String uploader = requireText(actor == null ? null : actor.username(), "uploadedBy",
                MAX_UPLOADED_BY_LENGTH);
        String uploaderSubject = actor.subject();
        String fileName = requireText(originalFileName, "file name", MAX_FILE_NAME_LENGTH);
        if (expectedRecordCount != null && expectedRecordCount < 0) {
            throw new IllegalArgumentException("expectedRecordCount must not be negative");
        }
        if (expectedByteSize != null && expectedByteSize < 0) {
            throw new IllegalArgumentException("expectedByteSize must not be negative");
        }
        String idempotencyKey = KEY_PREFIX + reference;

        // Stap 0: een afgekeurde aanvraag mag niets archiveren.
        readOnlyTransaction.executeWithoutResult(status -> resolve(taskId, idempotencyKey));

        // Stap A: archiveren, buiten elke transactie.
        ArchivedObject archived = archive.store(content, fileName);
        boolean keepArchive = false;
        try {
            // Stap B: registratie in één korte transactie.
            IntakeResult result = transaction.execute(status ->
                    register(taskId, idempotencyKey, uploader, uploaderSubject, expectedRecordCount,
                            expectedByteSize, fileName, sourceKind, archived));
            keepArchive = result != null && result.created();
            return result;
        } finally {
            if (!keepArchive) {
                archive.deleteQuietly(archived.archiveReference());
            }
        }
    }

    /**
     * Registreert een reeds gearchiveerd bestand als levering in een <b>bestaande</b>, lopende run (bouwstap K-4b:
     * de ophaalrun maakt haar run vóór het verbinden). Neemt deel aan een lopende transactie als die er is (de
     * ophaalrun legt haar waarnemingen en uitkomst in dezelfde transactie vast), anders een eigen korte transactie.
     * <p>
     * Volgorde: taak van de run → <b>rijslot op de taak</b> (zelfde slot als koppeling en upload) → run moet
     * {@code RUNNING} zijn → bestaat er al een levering met deze sleutel, dan {@code created = false} en niets nieuws →
     * dezelfde revisiecontroles als de upload ({@link #requireIntakeConfiguration}) → levering, bestand en batch, met
     * de run als {@code task_run_id}. Geen {@code TASK_NOT_MANUAL}- en geen A10-controle: die gelden voor de
     * manuele ontvangstwegen, niet voor de run die de taak zelf uitvoert. Het archiefobject wordt hier nooit
     * opgeruimd; dat blijft bij de aanroeper, die het ook gearchiveerd heeft.
     *
     * @throws NotFoundException  {@link #CODE_TASK_RUN_NOT_FOUND}, {@code TASK_NOT_FOUND}
     * @throws ConflictException  {@link #CODE_TASK_RUN_NOT_RUNNING}, {@code NO_ACTIVE_REVISION},
     *                            {@code CONFIG_PRICE_FIELD_MISSING}, {@code CONFIG_REQUIRED_BOOKMARK_MISSING}
     * @throws IllegalArgumentException ongeldige velden
     */
    public IntakeResult registerInRun(long taskRunId, String idempotencyKey, ActorIdentity actor,
                                      String originalFileName, DeliverySourceKind sourceKind,
                                      ArchivedObject archived) {
        String key = requireText(idempotencyKey, "idempotencyKey", MAX_IDEMPOTENCY_KEY_LENGTH);
        String by = requireText(actor == null ? null : actor.username(), "actor", MAX_UPLOADED_BY_LENGTH);
        String fileName = requireText(originalFileName, "file name", MAX_FILE_NAME_LENGTH);
        Objects.requireNonNull(archived, "archived");
        return transaction.execute(status -> {
            long taskId = runs.findTaskIdByRunId(taskRunId).orElseThrow(() -> new NotFoundException(
                    CODE_TASK_RUN_NOT_FOUND, "Task run " + taskRunId + " not found"));
            CatalogImportTask task = tasks.findByIdForUpdate(taskId).orElseThrow(() -> new NotFoundException(
                    "TASK_NOT_FOUND", "Task " + taskId + " not found"));
            TaskRun run = runs.findById(taskRunId).orElseThrow(() -> new NotFoundException(CODE_TASK_RUN_NOT_FOUND,
                    "Task run " + taskRunId + " not found"));
            if (run.getStatus() != TaskRunStatus.RUNNING) {
                throw new ConflictException(CODE_TASK_RUN_NOT_RUNNING, "Task run " + taskRunId
                        + " is no longer running (" + run.getStatus() + "); nothing was registered");
            }
            Delivery existing = deliveries.findByTaskIdAndIdempotencyKey(taskId, key).orElse(null);
            if (existing != null) {
                return new IntakeResult(false, queries.getDelivery(existing.getId()));
            }
            ImportDefinitionRevision revision = requireIntakeConfiguration(task);
            Delivery delivery = createDelivery(task, run, key, sourceKind, null, null, fileName, archived,
                    revision, by, actor.subject(), Instant.now());
            return new IntakeResult(true, queries.getDelivery(delivery.getId()));
        });
    }

    private IntakeResult register(long taskId, String idempotencyKey, String uploader, String uploaderSubject,
                                  Long expectedRecordCount, Long expectedByteSize,
                                  String fileName, DeliverySourceKind sourceKind, ArchivedObject archived) {
        // Racevenster A10 (K-4b): eerst het rijslot van de taakkoppeling, dan pas lezen (zie klassedocumentatie).
        // Een onbekende taak geeft hieronder dezelfde 404 als vroeger.
        tasks.findByIdForUpdate(taskId);
        Resolved resolved = resolve(taskId, idempotencyKey);

        if (resolved.existing() != null) {
            List<DeliveryFile> files = deliveryFiles
                    .findByDeliveryIdOrderBySequenceNumberAsc(resolved.existing().getId());
            boolean identical = files.size() == 1
                    && files.get(0).getContentHash().equals(archived.sha256Hex());
            if (!identical) {
                throw new ConflictException("DELIVERY_REFERENCE_REUSED_WITH_DIFFERENT_CONTENT",
                        "Delivery reference " + idempotencyKey.substring(KEY_PREFIX.length())
                                + " was already used with different file content");
            }
            return new IntakeResult(false, queries.getDelivery(resolved.existing().getId()));
        }

        CatalogImportTask task = resolved.task();
        Instant now = Instant.now();

        TaskRun run = new TaskRun(task, now, uploader);
        run.setStatus(TaskRunStatus.RUNNING);
        run.setTriggerSource(TaskRunTriggerSource.forIntake(sourceKind));
        try {
            runs.saveAndFlush(run);
        } catch (DataIntegrityViolationException concurrentRun) {
            // uk_task_run_concurrency: een gelijktijdige upload was sneller dan de voorcontrole.
            throw inProgress(task);
        }

        Delivery delivery = createDelivery(task, run, idempotencyKey, sourceKind, expectedRecordCount,
                expectedByteSize, fileName, archived, resolved.revision(), uploader, uploaderSubject, now);
        return new IntakeResult(true, queries.getDelivery(delivery.getId()));
    }

    /** Levering, bestand en batch in de gegeven run; gedeeld door de upload en de intake in een bestaande run. */
    private Delivery createDelivery(CatalogImportTask task, TaskRun run, String idempotencyKey,
                                    DeliverySourceKind sourceKind, Long expectedRecordCount, Long expectedByteSize,
                                    String fileName, ArchivedObject archived, ImportDefinitionRevision revision,
                                    String createdBy, String createdBySubject, Instant receivedAt) {
        Delivery delivery = new Delivery(task, idempotencyKey, receivedAt);
        delivery.setTaskRun(run);
        delivery.setSourceKind(sourceKind);
        delivery.setExpectedFileCount(1);
        delivery.setActualFileCount(1);
        delivery.setExpectedRecordCount(expectedRecordCount);
        delivery.setExpectedByteSize(expectedByteSize);
        delivery.setActualByteSize(archived.byteSize());
        delivery.setCompletenessProven(false);
        delivery.setManifestReference(null);
        deliveries.saveAndFlush(delivery);

        deliveryFiles.saveAndFlush(new DeliveryFile(delivery, 1, fileName, archived.archiveReference(),
                archived.sha256Hex(), archived.byteSize()));

        ImportBatch batch = new ImportBatch(delivery, task.getImportLink(), revision, 1, createdBy);
        batch.setTaskRun(run);
        batch.setCreatedBySubject(createdBySubject);
        batches.saveAndFlush(batch);
        recordBookmarkValuesHash(batch);
        return delivery;
    }

    /**
     * Legt vast met welke LINK-bookmarkwaarden deze batch gedraaid heeft
     * ({@code import_batch.bookmark_values_hash}, ontwerp §7, beslissingslog 23/09 keuze 5).
     * <p>
     * De kolom is bewust niet op {@link ImportBatch} gemapt (changeset 006-6) en wordt daarom met één
     * gerichte JDBC-update gezet, in dezelfde transactie als de batch zelf: een volledige
     * {@code save()} zou andere kolommen meeschrijven. Heeft de koppeling geen enkele bookmarkwaarde,
     * dan blijft de kolom {@code null} — "geen bookmarkwaarden van toepassing", niet de hash van een
     * lege reeks. Vanaf dit punt is het slot op die waarden actief: de batch is open, dus
     * {@code LinkBookmarkValueService} weigert elke wijziging tot ze terminaal is.
     */
    private void recordBookmarkValuesHash(ImportBatch batch) {
        byte[] hash = bookmarkHashes.computeLinkBookmarkValuesHash(batch.getImportLink().getId());
        if (hash != null) {
            bookmarkHashes.setBookmarkValuesHash(batch.getId(), hash);
        }
    }

    /**
     * Gedeelde controle voor de voorcontrole en de registratie. Een bestaande levering met dezelfde
     * referentie wordt teruggegeven zonder revisiecontroles: een retry moet ook slagen wanneer de
     * revisie sindsdien vervangen werd.
     */
    private Resolved resolve(long taskId, String idempotencyKey) {
        CatalogImportTask task = tasks.findById(taskId)
                .orElseThrow(() -> new NotFoundException("TASK_NOT_FOUND", "Task " + taskId + " not found"));
        // TASK_NOT_MANUAL, dan A10 (beslissingslog 2026-09-29, LC-2): een taak met een Leveringsconfiguratie haalt zelf
        // op; upload en servermap (beide via deze intake) worden geweigerd, ook als retry, vóór er iets gearchiveerd
        // wordt. Taken zonder Leveringsconfiguratie (het bestaande gedrag) raken deze regel nooit. Sinds NT-8 één
        // gedeelde implementatie met de gereedheidscontrole; de eerste bevinding wordt geworpen, zoals vroeger.
        ChainConfigurationChecks.throwFirst(checks.manualIntakeProblems(task));
        Delivery existing = deliveries.findByTaskIdAndIdempotencyKey(taskId, idempotencyKey).orElse(null);
        if (existing != null) {
            return new Resolved(task, null, existing);
        }
        ImportDefinitionRevision revision = requireIntakeConfiguration(task);
        if (runs.findByTaskIdAndConcurrencyTokenIsNotNull(taskId).isPresent()) {
            throw inProgress(task);
        }
        return new Resolved(task, revision, null);
    }

    /**
     * De configuratiecontroles die elke intake vóór het registreren doet: een actieve revisie, een basisprijsveld en
     * alle verplichte LINK-bookmarkwaarden. Gedeeld door upload/servermap en de ophaalrun (die ze ook als voorcontrole
     * vóór het verbinden doet, zodat een onvolledig ingerichte taak niets ophaalt). Enkel binnen een transactie
     * aanroepen.
     * <p>
     * Sinds NT-8 staan de controles zelf in {@link ChainConfigurationChecks#intakeConfiguration} (gedeeld met de
     * gereedheidscontrole {@code GET /import-links/{id}/readiness}); deze methode werpt de eerste bevinding met exact
     * dezelfde code, status en tekst als vroeger.
     *
     * @return de actieve revisie
     * @throws ConflictException {@code NO_ACTIVE_REVISION}, {@code CONFIG_PRICE_FIELD_MISSING},
     *                           {@code CONFIG_REQUIRED_BOOKMARK_MISSING}
     */
    public ImportDefinitionRevision requireIntakeConfiguration(CatalogImportTask task) {
        return checks.intakeConfiguration(task.getImportLink(), "Task " + task.getId()).requireActiveRevision();
    }

    private static ConflictException inProgress(CatalogImportTask task) {
        return new ConflictException("TASK_RUN_IN_PROGRESS",
                "Task " + task.getId() + " already has a run in progress");
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + field);
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
        }
        return value;
    }
}
