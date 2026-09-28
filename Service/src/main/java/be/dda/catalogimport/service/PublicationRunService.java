package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.PsimportPreviewDao;
import be.dda.catalogimport.dao.PsimportPreviewDao.SourceRow;
import be.dda.catalogimport.dao.PublicationBundleDao;
import be.dda.catalogimport.dao.PublicationBundleRepository;
import be.dda.catalogimport.dao.PublicationRunRepository;
import be.dda.catalogimport.domain.PublicationBundle;
import be.dda.catalogimport.domain.PublicationBundleStatus;
import be.dda.catalogimport.domain.PublicationRun;
import be.dda.catalogimport.domain.PublicationRunStatus;
import be.dda.catalogimport.domain.PublicationTargetMode;
import be.dda.catalogimport.service.PsimportPreviewMapper.Row;
import be.dda.catalogimport.service.PsimportPreviewService.PsimportPreview;
import be.dda.catalogimport.service.PublicationArtifactStore.StoredArtifact;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * De publicatierun in modus {@code SIMULATION} (ontwerp fase 5-PUB par. 3 en 4, bouwstap 5P-7;
 * docs/decisions.md 2026-09-26 "5-PUB (deel a): ontwerp bindend").
 *
 * <h2>Wat een SIMULATION-run is — en vooral: wat niet</h2>
 * Een run leest de bevroren bundel, bouwt het PSIMPORT-CSV-artefact en bewaart daarvan de SHA-256, de
 * grootte en een verwijzing naar het bestand. <b>Status {@code SIMULATED} betekent: artefact geschreven,
 * GEEN enkel operationeel effect.</b> Nooit te verwarren met {@code APPLIED}. Er wordt niets naar
 * ProDisWebbase, PSIMPORT of Pervasive geschreven, er is geen {@code ARIMP_Verwerken}, geen
 * {@code ARIMP_DELETE}, geen OUT02-resultaat en geen scheduler (ontwerp par. 5). De bundel blijft
 * {@code FROZEN}; mutatiestatussen, batches en {@code catalog_source_state} worden niet aangeraakt, ook
 * niet bij een mislukte run.
 * <p>
 * Een geslaagde run <b>blokkeert het annuleren van de bundel niet</b> (keuze mens 2026-09-26): de runs
 * blijven als auditspoor staan en {@link BundleCancellationService} is hierdoor niet gewijzigd.
 *
 * <h2>Foutvolgorde (ontwerp par. 4)</h2>
 * <ol>
 *   <li>400 {@link #CODE_PUBLICATION_MODE_REQUIRED} — geen modus meegegeven;</li>
 *   <li>400 {@link #CODE_PUBLICATION_MODE_UNKNOWN} — geen geldige {@link PublicationTargetMode};</li>
 *   <li>409 {@link #CODE_PUBLICATION_MODE_NOT_ENABLED} — {@code TRIAL_LIBRARY} en {@code PRODUCTION}
 *       <b>altijd</b>, en bewust vóór de bundel wordt opgezocht: dat die modi dicht staan, hangt niet van
 *       een bundel af, en een bestaande bundel zou anders méér prijsgeven dan een onbestaande;</li>
 *   <li>404 {@link #CODE_BUNDLE_NOT_FOUND};</li>
 *   <li>409 {@link #CODE_BUNDLE_NOT_FROZEN};</li>
 *   <li>409 {@link #CODE_PUBLICATION_RUN_IN_PROGRESS};</li>
 *   <li>409 {@link #CODE_BUNDLE_CONTENT_CHANGED_SINCE_FREEZE} — de herberekende bundelhash wijkt af van
 *       de bewaarde. Er wordt dan <b>niets</b> geschreven: publiceren wat niet meer is wat iemand
 *       bevroor, is erger dan niet publiceren.</li>
 * </ol>
 *
 * <h2>Transacties: drie stappen, bewust niet één</h2>
 * <ol>
 *   <li><b>Eigen transactie</b>: de runrij wordt aangemaakt ({@code REQUESTED}, marker {@code TRUE}) en
 *       meteen op {@code PREPARING} gezet, achter het schrijfslot op de bundel
 *       ({@link PublicationBundleRepository#findByIdForUpdate}). Vanaf de commit is voor iedereen
 *       zichtbaar dát er een run loopt, en houdt {@code uk_publication_run_active} een tweede aanvraag
 *       tegen.</li>
 *   <li><b>Zonder transactie</b>: het artefact wordt paginagewijs naar het bestandssysteem gestreamd. Een
 *       databasetransactie openhouden zolang er een bestand van willekeurige grootte geschreven wordt,
 *       zou een verbinding en een slot op de bundel vasthouden voor werk dat de database niet raakt.</li>
 *   <li><b>Tweede transactie</b>: {@code SIMULATED} met de artefactvelden en de tellers, of {@code FAILED}
 *       met een foutcode — in beide gevallen met {@code finished_at} en marker {@code null}.</li>
 * </ol>
 * Een run mag na een uitzondering <b>nooit</b> in een actieve toestand blijven hangen: elke fout in stap 2
 * loopt langs {@link #failRun}, dat de run afsluit en het (hoogstens tijdelijke) artefactbestand opruimt.
 * De aanroeper krijgt dan de runview met status {@code FAILED} terug in plaats van een uitzondering: de
 * run bestaat echt, met een leesbare reden, en die reden verbergen zou de aanvrager doen denken dat er
 * niets gebeurd is.
 *
 * <h2>Onvolledigheid wordt getoond, nooit ingevuld</h2>
 * {@code incompleteRowCount} telt de rijen met minstens één {@code UNKNOWN}- of
 * {@code NOT_SNAPSHOTTED}-veld. Voor een bundel die bevroren werd vóór de bundelsnapshot bestond, zijn
 * dat <b>alle</b> rijen (keuze mens 2026-09-26: zo'n bundel mag een run krijgen, met zichtbare
 * onvolledigheid). Er wordt nooit een waarde stil ingevuld, leeggemaakt of op 0 gezet.
 *
 * <h2>Herstel van een vastgelopen {@code PREPARING}-run</h2>
 * Een run die tussen het aanmaken en het afronden door een proces- of serverfout blijft staan, houdt
 * {@code PREPARING} met de marker vast en blokkeert daarmee elke volgende run op die bundel (ontwerp par. 7;
 * er is bewust geen scheduler, par. 5). {@code docs/decisions.md} 2026-09-27 ("herstel van een vastgelopen
 * PREPARING-publicatierun") kiest <b>beide</b> herstelpaden: {@link #requestRun} ruimt zo'n run zelf op zodra
 * ze langer dan {@link #stuckAfter} (config {@code catalogimport.publication-run.stuck-after}, default 60
 * minuten) op {@code PREPARING} staat ({@link #FAILURE_TIMED_OUT}), en {@link #abortRun} laat wie
 * {@code APPROVE} heeft een {@code PREPARING}-run op elk moment handmatig afbreken, zonder tijdsvoorwaarde
 * ({@link #FAILURE_MANUALLY_ABORTED}). Beide hergebruiken {@link PublicationRun#recordFailed}.
 */
@Service
public class PublicationRunService {

    /** Er is geen doelmodus meegegeven (400). */
    public static final String CODE_PUBLICATION_MODE_REQUIRED = "PUBLICATION_MODE_REQUIRED";
    /** De meegegeven doelmodus is geen {@link PublicationTargetMode} (400). */
    public static final String CODE_PUBLICATION_MODE_UNKNOWN = "PUBLICATION_MODE_UNKNOWN";
    /**
     * De modus bestaat maar staat dicht (409): {@code TRIAL_LIBRARY} en {@code PRODUCTION} blijven dicht
     * tot het verwerkingscontract (252 IMPORT/1179, ARIMP_DELETE-codes, OUT02) bewezen is — 5-PUB-b/c.
     */
    public static final String CODE_PUBLICATION_MODE_NOT_ENABLED = "PUBLICATION_MODE_NOT_ENABLED";
    /** De bundel bestaat niet (404). */
    public static final String CODE_BUNDLE_NOT_FOUND = PublicationBundleService.CODE_BUNDLE_NOT_FOUND;
    /**
     * De bundel is niet {@code FROZEN} (409). Bewust dezelfde code als de PSIMPORT-preview: het is
     * dezelfde vaststelling met dezelfde oplossing.
     */
    public static final String CODE_BUNDLE_NOT_FROZEN = PsimportPreviewService.CODE_BUNDLE_NOT_FROZEN;
    /** Er loopt al een niet-terminale run op deze bundel (409). */
    public static final String CODE_PUBLICATION_RUN_IN_PROGRESS = "PUBLICATION_RUN_IN_PROGRESS";
    /**
     * De herberekende bundelhash wijkt af van de hash die bij het bevriezen bewaard is (409): de inhoud
     * van de bundel is sinds het bevriezen gewijzigd. Er wordt niets geschreven en er blijft geen runrij
     * achter.
     */
    public static final String CODE_BUNDLE_CONTENT_CHANGED_SINCE_FREEZE = "BUNDLE_CONTENT_CHANGED_SINCE_FREEZE";
    /** De run bestaat niet (404). */
    public static final String CODE_RUN_NOT_FOUND = "PUBLICATION_RUN_NOT_FOUND";
    /** De run bestaat wel, maar draagt (nog) geen artefact (409): enkel een {@code SIMULATED}-run heeft er een. */
    public static final String CODE_ARTIFACT_NOT_AVAILABLE = "PUBLICATION_RUN_ARTIFACT_NOT_AVAILABLE";
    /**
     * De run bestaat, maar staat niet op {@code PREPARING} (409): enkel een vastgelopen {@code PREPARING}-run
     * kan afgebroken worden ({@code docs/decisions.md} 2026-09-27 "herstel van een vastgelopen
     * PREPARING-publicatierun", optie A). Geen tijdsvoorwaarde: elke {@code PREPARING}-run mag op elk moment
     * afgebroken worden door wie {@code APPROVE} heeft.
     */
    public static final String CODE_RUN_NOT_STUCK = "PUBLICATION_RUN_NOT_STUCK";

    /** {@code failure_code}: het artefact kon niet naar het bestandssysteem geschreven worden. */
    public static final String FAILURE_ARTIFACT_WRITE_FAILED = "ARTIFACT_WRITE_FAILED";
    /** {@code failure_code}: het lezen of afbeelden van de bundelrijen liep mis. */
    public static final String FAILURE_PROJECTION_FAILED = "PROJECTION_FAILED";
    /**
     * {@code failure_code}: een eerdere aanvraag bleef op {@code PREPARING} hangen (server-/procescrash
     * tijdens het bouwen van het artefact) en werd bij de eerstvolgende {@link #requestRun} voor die bundel
     * automatisch als mislukt afgesloten (optie B, geen scheduler: de controle gebeurt enkel op aanvraag).
     */
    public static final String FAILURE_TIMED_OUT = "FAILURE_TIMED_OUT";
    /** {@code failure_code}: een {@code PREPARING}-run werd expliciet afgebroken via {@link #abortRun} (optie A). */
    public static final String FAILURE_MANUALLY_ABORTED = "FAILURE_MANUALLY_ABORTED";

    /** {@code publication_run.requested_by}: varchar(100). */
    static final int MAX_ACTOR_LENGTH = 100;
    /** {@code publication_run.failure_message}: varchar(1000); nooit stil overschrijden. */
    static final int MAX_FAILURE_MESSAGE_LENGTH = 1000;

    /**
     * Paginagrootte waarmee het artefact de bundelrijen leest: dezelfde bovengrens als de preview-API
     * ({@link BundleQueryService#MAX_PAGE_SIZE}), zodat er geen tweede, afwijkend paginabegrip ontstaat.
     */
    static final int ARTIFACT_PAGE_SIZE = BundleQueryService.MAX_PAGE_SIZE;

    private static final Logger LOG = LoggerFactory.getLogger(PublicationRunService.class);

    /**
     * Eén publicatierun zoals de API ze toont. Bewust <b>zonder</b> {@code requested_by_subject} (audit,
     * komt nooit in een domeinantwoord — zelfde regel als {@code frozen_by_subject}) en <b>zonder</b>
     * {@code artifact_reference}: het bestandspad van de server hoort niet in een antwoord; het artefact
     * wordt in 5P-8 via een eigen endpoint aangeboden.
     * <p>
     * {@code simulationOnly}, {@code writesToProdis} en {@code contractStatus} staan er altijd en vast op:
     * ze zijn geen contractuele toezegging over de inhoud, maar een waarschuwing dat deze run niets
     * uitgevoerd heeft.
     *
     * @param bundleContentHash hex, of {@code null} wanneer de bundel er geen draagt
     * @param snapshotHash      hex, of {@code null} voor een bundel zonder bundelsnapshot
     * @param payloadHash       hex van de artefactbytes; {@code null} zolang er geen artefact is
     */
    public record PublicationRunView(long id, long bundleId, String targetMode, int attempt, String status,
                                     String requestedBy, Instant requestedAt, Instant startedAt,
                                     Instant finishedAt, String bundleContentHash, String snapshotHash,
                                     String payloadHash, String artifactSha256, Long artifactByteSize,
                                     Long rowCount, Long incompleteRowCount, String failureCode,
                                     String failureMessage, boolean simulationOnly, boolean writesToProdis,
                                     String contractStatus, String previewSpecVersion,
                                     String snapshotSpecVersion) {

        private static PublicationRunView of(PublicationRun run, String snapshotSpecVersion) {
            HexFormat hex = HexFormat.of();
            return new PublicationRunView(run.getId(), run.getBundle().getId(), run.getTargetMode().name(),
                    run.getAttempt(), run.getStatus().name(), run.getRequestedBy(), run.getRequestedAt(),
                    run.getStartedAt(), run.getFinishedAt(),
                    run.getBundleContentHash() == null ? null : hex.formatHex(run.getBundleContentHash()),
                    run.getSnapshotHash() == null ? null : hex.formatHex(run.getSnapshotHash()),
                    run.getPayloadHash() == null ? null : hex.formatHex(run.getPayloadHash()),
                    run.getArtifactSha256(), run.getArtifactByteSize(), run.getRowCount(),
                    run.getIncompleteRowCount(), run.getFailureCode(), run.getFailureMessage(),
                    true, false, PsimportPreviewService.CONTRACT_STATUS,
                    PsimportPreviewService.PREVIEW_SPEC_VERSION, snapshotSpecVersion);
        }
    }

    /** Wat stap 1 vastlegt en stap 2 nodig heeft; de bundelrij zelf wordt daarna niet meer aangeraakt. */
    private record RunStart(long runId, long bundleId, String bundleContentHash, String snapshotHash,
                            String snapshotSpecVersion) {
    }

    /** De uitkomst van stap 2, klaar om in stap 3 op de runrij te zetten. */
    private record Artifact(StoredArtifact stored, byte[] payloadHash, long rowCount, long incompleteRowCount) {
    }

    private final PublicationRunRepository runs;
    private final PublicationBundleRepository bundles;
    private final PublicationBundleDao bundleDao;
    private final PsimportPreviewDao previewDao;
    private final PublicationArtifactStore artifacts;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Duration stuckAfter;

    public PublicationRunService(PublicationRunRepository runs, PublicationBundleRepository bundles,
                                 PublicationBundleDao bundleDao, PsimportPreviewDao previewDao,
                                 PublicationArtifactStore artifacts,
                                 PlatformTransactionManager transactionManager, Clock clock,
                                 @Value("${catalogimport.publication-run.stuck-after:PT60M}") Duration stuckAfter) {
        this.runs = runs;
        this.bundles = bundles;
        this.bundleDao = bundleDao;
        this.previewDao = previewDao;
        this.artifacts = artifacts;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.stuckAfter = stuckAfter;
    }

    /**
     * Vraagt een publicatierun aan <b>zonder geverifieerde identiteit</b>
     * ({@code requested_by_subject} blijft {@code null}): enkel voor tests en directe Service-aanroepen.
     * De Web-laag gebruikt de {@link ActorIdentity}-overload.
     */
    public PublicationRunView requestRun(long bundleId, String targetMode, String requestedBy) {
        return requestRun(bundleId, targetMode, ActorIdentity.unverified(requestedBy));
    }

    /**
     * Zoals hierboven, met de aanvrager als {@link ActorIdentity}. De modus komt als tekst binnen, zodat
     * een onbekende waarde de gedocumenteerde 400 {@link #CODE_PUBLICATION_MODE_UNKNOWN} geeft in plaats
     * van een naamloze conversiefout. De vergelijking is exact (na {@code trim}), net als de
     * enum-conversie die de Web-laag elders doet.
     */
    public PublicationRunView requestRun(long bundleId, String targetMode, ActorIdentity actor) {
        return requestRun(bundleId, parseMode(targetMode), actor);
    }

    /**
     * Vraagt een publicatierun aan. Zie de klassedocumentatie voor de foutvolgorde, de drie transacties en
     * de betekenis van {@code SIMULATED}.
     *
     * @param actor verplicht; {@code actor.username()} volgt de gewone naamregels, {@code actor.subject()}
     *              mag {@code null} zijn ("geen geverifieerde identiteit") en wordt nooit uit de naam
     *              afgeleid
     * @return de runview; bij een technische fout in stap 2 met status {@code FAILED} en een foutcode
     * @throws BadRequestException      {@link #CODE_PUBLICATION_MODE_REQUIRED},
     *                                  {@link #CODE_PUBLICATION_MODE_UNKNOWN}
     * @throws NotFoundException        {@link #CODE_BUNDLE_NOT_FOUND}
     * @throws ConflictException        {@link #CODE_PUBLICATION_MODE_NOT_ENABLED},
     *                                  {@link #CODE_BUNDLE_NOT_FROZEN},
     *                                  {@link #CODE_PUBLICATION_RUN_IN_PROGRESS},
     *                                  {@link #CODE_BUNDLE_CONTENT_CHANGED_SINCE_FREEZE}
     * @throws IllegalArgumentException ontbrekende of ongeldige aanvrager
     */
    public PublicationRunView requestRun(long bundleId, PublicationTargetMode targetMode, ActorIdentity actor) {
        PublicationTargetMode mode = requireMode(targetMode);
        requireModeEnabled(mode);
        if (actor == null) {
            throw new IllegalArgumentException("Missing requestedBy");
        }
        String requester = ActorNames.requireActorName(actor.username(), "requestedBy", MAX_ACTOR_LENGTH);
        String requesterSubject = actor.subject();

        RunStart start = createRun(bundleId, mode, requester, requesterSubject);
        Artifact artifact;
        try {
            artifact = buildArtifact(start);
        } catch (RuntimeException failure) {
            return failRun(start, failure);
        }
        return completeRun(start, artifact);
    }

    /** Alle runs van één bundel, oudste eerst. */
    @Transactional(readOnly = true)
    public List<PublicationRunView> listRuns(long bundleId) {
        PublicationBundle bundle = bundles.findById(bundleId).orElseThrow(() -> new NotFoundException(
                CODE_BUNDLE_NOT_FOUND, "Bundle " + bundleId + " not found"));
        String snapshotSpecVersion = bundle.getSnapshotSpecVersion();
        return runs.findByBundleIdOrderByIdAsc(bundleId).stream()
                .map(run -> PublicationRunView.of(run, snapshotSpecVersion)).toList();
    }

    /**
     * Eén run.
     *
     * @throws NotFoundException {@link #CODE_RUN_NOT_FOUND}
     */
    @Transactional(readOnly = true)
    public PublicationRunView getRun(long runId) {
        PublicationRun run = requireRun(runId);
        return PublicationRunView.of(run, run.getBundle().getSnapshotSpecVersion());
    }

    /**
     * Opent het artefact van een geslaagde run, voor het download-endpoint van 5P-8. De aanroeper sluit de
     * stroom. De bewaarde referentie wordt door {@link PublicationArtifactStore#open} opnieuw
     * gevalideerd; ze kan dus nooit buiten de artefactmap wijzen.
     *
     * @throws NotFoundException {@link #CODE_RUN_NOT_FOUND}
     * @throws ConflictException {@link #CODE_ARTIFACT_NOT_AVAILABLE} zolang de run geen artefact draagt
     */
    @Transactional(readOnly = true)
    public InputStream openArtifact(long runId) {
        PublicationRun run = requireRun(runId);
        String reference = run.getArtifactReference();
        if (run.getStatus() != PublicationRunStatus.SIMULATED || reference == null) {
            throw new ConflictException(CODE_ARTIFACT_NOT_AVAILABLE, "Run " + runId + " is " + run.getStatus()
                    + " and carries no artifact; only a SIMULATED run has one");
        }
        return artifacts.open(reference);
    }

    /**
     * Breekt een vastgelopen run handmatig af (optie A, {@code docs/decisions.md} 2026-09-27): wie
     * {@code APPROVE} heeft, mag een {@code PREPARING}-run op elk moment afbreken, op eigen oordeel — er is
     * <b>bewust geen tijdsvoorwaarde</b> (afwijking van de aanbeveling van de denker, expliciete menskeuze).
     * De run wordt {@code FAILED} met {@link #FAILURE_MANUALLY_ABORTED}, waarna de marker vrijkomt en een
     * volgende aanvraag voor deze bundel gewoon weer kan slagen.
     * <p>
     * Er is geen kolom om wie dit deed vast te leggen (geen nieuwe migratie nodig, zie het beslissingsblok);
     * de aanvrager wordt daarom net als bij een technische mislukking in {@code failure_message} bewaard, en
     * in de logregel met het volledige subject.
     *
     * @param actor verplicht; dezelfde naamregels als een handtekening elders in dit domein
     * @return de runview met status {@code FAILED}
     * @throws NotFoundException        {@link #CODE_RUN_NOT_FOUND}
     * @throws ConflictException        {@link #CODE_RUN_NOT_STUCK} — de run is niet (meer) {@code PREPARING}
     * @throws IllegalArgumentException ontbrekende of ongeldige aanvrager
     */
    @Transactional
    public PublicationRunView abortRun(long runId, ActorIdentity actor) {
        if (actor == null) {
            throw new IllegalArgumentException("Missing abortedBy");
        }
        String aborter = ActorNames.requireActorName(actor.username(), "abortedBy", MAX_ACTOR_LENGTH);
        PublicationRun run = requireRun(runId);
        if (run.getStatus() != PublicationRunStatus.PREPARING) {
            throw new ConflictException(CODE_RUN_NOT_STUCK, "Publication run " + runId + " is " + run.getStatus()
                    + "; only a PREPARING run can be aborted");
        }
        run.recordFailed(clock.instant(), FAILURE_MANUALLY_ABORTED, "Manually aborted by " + aborter);
        runs.saveAndFlush(run);
        LOG.info("Publication run {} for bundle {} manually aborted by {} (subject {})", runId,
                run.getBundle().getId(), aborter, actor.subject());
        return PublicationRunView.of(run, run.getBundle().getSnapshotSpecVersion());
    }

    // --- Stap 1: de runrij ---------------------------------------------------------------------------

    /**
     * Legt de run vast achter het schrijfslot op de bundel: alle voorwaarden opnieuw gecontroleerd,
     * {@code attempt} bepaald, de rij ingevoegd als {@code REQUESTED} en meteen op {@code PREPARING}
     * gezet.
     * <p>
     * De voorafgaande telling van actieve runs dient enkel voor de <b>nette</b> foutmelding; de echte
     * garantie komt van {@code uk_publication_run_active}. Een tweede gelijktijdige aanvraag die het slot
     * net te laat krijgt, botst op die constraint en wordt hier eveneens 409
     * {@link #CODE_PUBLICATION_RUN_IN_PROGRESS} — nooit een naamloze 500.
     * <p>
     * <b>Automatische timeout (optie B, {@code docs/decisions.md} 2026-09-27):</b> vóór die weigering wordt
     * gecontroleerd of de bestaande actieve run {@code PREPARING} is én haar {@code started_at} ouder is dan
     * {@link #stuckAfter}. Zo ja, dan is er geen scheduler die dit ooit zou opmerken (ontwerp par. 5 verbiedt
     * er één): deze aanvraag ruimt de vastgelopen run zelf op ({@code FAILED}/{@link #FAILURE_TIMED_OUT}, met
     * een expliciete logregel — een volgende aanvrager "erft" dit nooit stilzwijgend) en gaat daarna gewoon
     * door, zonder 409. Is de actieve run niet {@code PREPARING} of nog binnen de drempel, dan blijft het
     * bestaande gedrag ongewijzigd.
     */
    private RunStart createRun(long bundleId, PublicationTargetMode mode, String requester,
                               String requesterSubject) {
        return transaction.execute(status -> {
            PublicationBundle bundle = bundles.findByIdForUpdate(bundleId).orElseThrow(
                    () -> new NotFoundException(CODE_BUNDLE_NOT_FOUND, "Bundle " + bundleId + " not found"));
            if (bundle.getStatus() != PublicationBundleStatus.FROZEN) {
                throw new ConflictException(CODE_BUNDLE_NOT_FROZEN, "Bundle " + bundleId + " is "
                        + bundle.getStatus() + "; only a FROZEN bundle can be published");
            }
            Optional<PublicationRun> active = runs.findByBundleIdAndActiveMarkerIsNotNull(bundleId);
            if (active.isPresent() && isStuck(active.get())) {
                timeOut(active.get());
            } else if (active.isPresent()) {
                throw new ConflictException(CODE_PUBLICATION_RUN_IN_PROGRESS, "Bundle " + bundleId
                        + " already has a publication run in progress; wait for it to finish");
            }
            requireUnchangedContent(bundle);

            int attempt = nextAttempt(bundleId, mode);
            Instant requestedAt = clock.instant();
            PublicationRun run = new PublicationRun(bundle, mode, attempt, requester, requesterSubject,
                    requestedAt, bundle.getContentHash(), bundle.getSnapshotHash(),
                    idempotencyKey(bundleId, mode, attempt));
            try {
                runs.saveAndFlush(run);
            } catch (DataIntegrityViolationException collision) {
                // uk_publication_run_active of uk_publication_run_idempotency: een tweede aanvraag was ons
                // net voor. Geen stille tweede run, geen naamloze 500.
                throw new ConflictException(CODE_PUBLICATION_RUN_IN_PROGRESS, "Bundle " + bundleId
                        + " already has a publication run in progress; wait for it to finish");
            }
            run.markPreparing(clock.instant());
            runs.saveAndFlush(run);

            HexFormat hex = HexFormat.of();
            LOG.info("Publication run {} requested for bundle {} by {} (mode {}, attempt {})", run.getId(),
                    bundleId, requester, mode, attempt);
            return new RunStart(run.getId(), bundleId,
                    bundle.getContentHash() == null ? null : hex.formatHex(bundle.getContentHash()),
                    bundle.getSnapshotHash() == null ? null : hex.formatHex(bundle.getSnapshotHash()),
                    bundle.getSnapshotSpecVersion());
        });
    }

    /**
     * Is deze actieve run vastgelopen: status {@code PREPARING} en {@code started_at} langer dan
     * {@link #stuckAfter} geleden? Een {@code REQUESTED}-run (het korte moment vóór {@code markPreparing}
     * binnen dezelfde transactie) telt hier bewust niet mee — dat venster is te kort om ooit van buiten deze
     * transactie zichtbaar te zijn, en de mens koos expliciet voor enkel {@code PREPARING}.
     */
    private boolean isStuck(PublicationRun run) {
        return run.getStatus() == PublicationRunStatus.PREPARING && run.getStartedAt() != null
                && Duration.between(run.getStartedAt(), clock.instant()).compareTo(stuckAfter) > 0;
    }

    /**
     * Sluit een vastgelopen {@code PREPARING}-run automatisch af ({@code FAILED}/{@link #FAILURE_TIMED_OUT}),
     * zodat de marker vrijkomt en de aanroepende aanvraag meteen kan doorgaan. Een expliciete logregel: een
     * volgende aanvrager "erft" het opruimen van andermans vastgelopen run nooit stilzwijgend.
     */
    private void timeOut(PublicationRun run) {
        Instant now = clock.instant();
        run.recordFailed(now, FAILURE_TIMED_OUT, "Automatically timed out: still PREPARING after more than "
                + stuckAfter + " (started at " + run.getStartedAt() + ")");
        runs.saveAndFlush(run);
        LOG.info("Publication run {} for bundle {} automatically timed out (PREPARING since {}, threshold {}); "
                        + "a new request may now proceed", run.getId(), run.getBundle().getId(),
                run.getStartedAt(), stuckAfter);
    }

    /**
     * De bundelhash wordt op het runmoment opnieuw berekend en vergeleken met wat bij het bevriezen
     * bewaard is. Wijken ze af, dan is de inhoud van de bundel sinds het bevriezen verschoven en wordt er
     * <b>niets</b> geschreven: geen runrij, geen artefact. Een bundel zonder bewaarde hash valt hier ook
     * onder — zonder hash valt niet te bewijzen dat dit nog is wat iemand bevroor.
     */
    private void requireUnchangedContent(PublicationBundle bundle) {
        byte[] recomputed = bundleDao.computeContentHash(bundle.getId());
        if (!Arrays.equals(recomputed, bundle.getContentHash())) {
            throw new ConflictException(CODE_BUNDLE_CONTENT_CHANGED_SINCE_FREEZE, "The content of bundle "
                    + bundle.getId() + " changed after it was frozen (the recomputed content hash differs "
                    + "from the stored one); nothing was published. Investigate the change before "
                    + "publishing this bundle");
        }
    }

    /** {@code attempt} telt per bundel en per modus; de eerste poging is 1. */
    private int nextAttempt(long bundleId, PublicationTargetMode mode) {
        Integer highest = runs.findMaxAttempt(bundleId, mode);
        return (highest == null ? 0 : highest) + 1;
    }

    /** {@code run:<bundleId>:<mode>:<attempt>} (ontwerp par. 3). */
    static String idempotencyKey(long bundleId, PublicationTargetMode mode, int attempt) {
        return "run:" + bundleId + ":" + mode.name() + ":" + attempt;
    }

    // --- Stap 2: het artefact ------------------------------------------------------------------------

    /**
     * Bouwt het PSIMPORT-CSV-artefact: banner, vaste header, één regel per te publiceren mutatie. De
     * rijen worden <b>paginagewijs</b> gelezen ({@link PsimportPreviewDao#findPage}, sortering
     * {@code batch_id asc, m.id asc}) en meteen naar de stroom geschreven, zodat een grote bundel niet
     * eerst volledig in het geheugen komt. De kolommen van de header komen uit de eerste rij, precies
     * zoals {@code PsimportPreviewCsvSerializer.toCsv} ze bepaalt; een bundel zonder publiceerbare
     * mutaties levert banner + header en geen enkele datarij op.
     * <p>
     * Deze stap loopt <b>buiten</b> een databasetransactie (zie de klassedocumentatie). Dat is veilig
     * omdat de bundel {@code FROZEN} is en haar inhoud vlak ervoor tegen de bewaarde hash gecontroleerd
     * werd.
     */
    private Artifact buildArtifact(RunStart start) {
        long total = previewDao.count(start.bundleId());
        PsimportPreview envelope = new PsimportPreview(true, PsimportPreviewService.CONTRACT_STATUS,
                PsimportPreviewService.PREVIEW_SPEC_VERSION, start.bundleId(), start.bundleContentHash(),
                start.snapshotSpecVersion(), start.snapshotHash(), clock.instant(), List.of(), 0,
                ARTIFACT_PAGE_SIZE, total, (int) ((total + ARTIFACT_PAGE_SIZE - 1) / ARTIFACT_PAGE_SIZE));
        long[] counters = new long[2];

        StoredArtifact stored = artifacts.write(start.runId(), out -> writeArtifact(start.bundleId(), envelope,
                out, counters));
        try {
            byte[] payloadHash = HexFormat.of().parseHex(stored.sha256Hex());
            return new Artifact(stored, payloadHash, counters[0], counters[1]);
        } catch (RuntimeException failure) {
            artifacts.deleteQuietly(stored.reference());
            throw failure;
        }
    }

    /** @param counters {@code [0]} het aantal geschreven rijen, {@code [1]} daarvan het aantal onvolledige */
    private void writeArtifact(long bundleId, PsimportPreview envelope, Writer out, long[] counters)
            throws IOException {
        PsimportPreviewCsvSerializer.writeArtifactBanner(out, envelope);
        boolean headerWritten = false;
        long offset = 0;
        while (true) {
            List<SourceRow> page = previewDao.findPage(bundleId, ARTIFACT_PAGE_SIZE, offset);
            if (page.isEmpty()) {
                break;
            }
            for (SourceRow source : page) {
                Row row = PsimportPreviewMapper.map(source);
                if (!headerWritten) {
                    PsimportPreviewCsvSerializer.writeHeader(out, row.fields());
                    headerWritten = true;
                }
                PsimportPreviewCsvSerializer.writeRow(out, row);
                counters[0]++;
                if (!row.complete()) {
                    counters[1]++;
                }
            }
            offset += page.size();
            if (page.size() < ARTIFACT_PAGE_SIZE) {
                break;
            }
        }
        if (!headerWritten) {
            PsimportPreviewCsvSerializer.writeHeader(out, List.of());
        }
    }

    // --- Stap 3: afsluiten ---------------------------------------------------------------------------

    /**
     * Zet de run op {@code SIMULATED} met de artefactvelden, de tellers en {@code finished_at}, en geeft
     * de marker vrij. {@code payload_hash} is dezelfde waarde als {@code artifact_sha256} — de SHA-256
     * over exact de geschreven bytes — maar binair; zodra 5-PUB-b/c een ander doelformaat krijgt, blijft
     * {@code payload_hash} "de hash van wat er verzonden is" en {@code artifact_sha256} "de hash van het
     * bewaarde bestand".
     */
    private PublicationRunView completeRun(RunStart start, Artifact artifact) {
        return transaction.execute(status -> {
            PublicationRun run = requireRun(start.runId());
            run.recordSimulated(clock.instant(), artifact.stored().reference(), artifact.stored().sha256Hex(),
                    artifact.stored().byteSize(), artifact.payloadHash(), artifact.rowCount(),
                    artifact.incompleteRowCount());
            runs.saveAndFlush(run);
            LOG.info("Publication run {} simulated for bundle {}: {} row(s), {} incomplete, {} byte(s), "
                            + "sha256 {}. SIMULATED means the artifact was written and NOTHING was published.",
                    run.getId(), start.bundleId(), artifact.rowCount(), artifact.incompleteRowCount(),
                    artifact.stored().byteSize(), artifact.stored().sha256Hex());
            return PublicationRunView.of(run, start.snapshotSpecVersion());
        });
    }

    /**
     * Sluit een mislukte run af: {@code FAILED}, foutcode, ingekorte boodschap, {@code finished_at} en
     * marker {@code null}. De volledige uitzondering gaat naar het logboek, niet naar de database:
     * {@code failure_message} mag geen stacktrace en geen serverpad dragen.
     * <p>
     * Er blijft geen artefactbestand achter: {@link PublicationArtifactStore#write} ruimt het tijdelijke
     * bestand op en hernoemt pas als laatste stap, dus het definitieve pad bestaat na een mislukking niet.
     */
    private PublicationRunView failRun(RunStart start, RuntimeException failure) {
        String code = failure instanceof UncheckedIOException ? FAILURE_ARTIFACT_WRITE_FAILED
                : FAILURE_PROJECTION_FAILED;
        LOG.warn("Publication run {} for bundle {} failed with {}", start.runId(), start.bundleId(), code,
                failure);
        return transaction.execute(status -> {
            PublicationRun run = requireRun(start.runId());
            run.recordFailed(clock.instant(), code, failureMessage(failure));
            runs.saveAndFlush(run);
            return PublicationRunView.of(run, start.snapshotSpecVersion());
        });
    }

    /**
     * Enkel het type en de eigen boodschap van de uitzondering, nooit de oorzaakketen: die draagt bij een
     * IO-fout het volledige bestandspad van de server. Te lang wordt zichtbaar afgekapt met {@code ...},
     * nooit stil.
     */
    private static String failureMessage(RuntimeException failure) {
        String message = failure.getClass().getSimpleName()
                + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
        return message.length() <= MAX_FAILURE_MESSAGE_LENGTH ? message
                : message.substring(0, MAX_FAILURE_MESSAGE_LENGTH - 3) + "...";
    }

    // --- Gedeeld ---------------------------------------------------------------------------------------

    private PublicationRun requireRun(long runId) {
        return runs.findById(runId).orElseThrow(
                () -> new NotFoundException(CODE_RUN_NOT_FOUND, "Publication run " + runId + " not found"));
    }

    /** Tekst naar modus: leeg is niet hetzelfde als onbekend, en geen van beide wordt een aanname. */
    private static PublicationTargetMode parseMode(String targetMode) {
        if (targetMode == null || targetMode.isBlank()) {
            throw new BadRequestException(CODE_PUBLICATION_MODE_REQUIRED,
                    "targetMode is required; a publication run never assumes a target");
        }
        String candidate = targetMode.trim();
        for (PublicationTargetMode mode : PublicationTargetMode.values()) {
            if (mode.name().equals(candidate)) {
                return mode;
            }
        }
        throw new BadRequestException(CODE_PUBLICATION_MODE_UNKNOWN, "Unknown targetMode '" + candidate
                + "'; expected one of " + Arrays.toString(PublicationTargetMode.values()));
    }

    private static PublicationTargetMode requireMode(PublicationTargetMode targetMode) {
        if (targetMode == null) {
            throw new BadRequestException(CODE_PUBLICATION_MODE_REQUIRED,
                    "targetMode is required; a publication run never assumes a target");
        }
        return targetMode;
    }

    /**
     * {@code TRIAL_LIBRARY} en {@code PRODUCTION} geven <b>altijd</b> 409, ook op een bundel die niet
     * bestaat: 5-PUB-a schrijft naar geen enkel doelsysteem, en een halfopen modus zou de indruk wekken
     * dat er ooit iets doorgestuurd wordt.
     */
    private static void requireModeEnabled(PublicationTargetMode mode) {
        if (mode != PublicationTargetMode.SIMULATION) {
            throw new ConflictException(CODE_PUBLICATION_MODE_NOT_ENABLED, "Target mode " + mode
                    + " is not enabled: phase 5-PUB-a only supports SIMULATION and writes nothing to "
                    + "ProDisWebbase, PSIMPORT or Pervasive");
        }
    }
}
