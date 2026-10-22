package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.ImportMutationRepository;
import be.dda.catalogimport.dao.ProdisPublicationSourceDao;
import be.dda.catalogimport.dao.ProdisPublicationSourceDao.SourceRow;
import be.dda.catalogimport.dao.ProdisPublicationWriter;
import be.dda.catalogimport.dao.PsimportPreviewDao;
import be.dda.catalogimport.dao.PublicationBundleDao;
import be.dda.catalogimport.dao.PublicationBundleRepository;
import be.dda.catalogimport.dao.PublicationBundleSnapshotDao;
import be.dda.catalogimport.dao.PublicationRunItemDao;
import be.dda.catalogimport.dao.PublicationRunItemDao.NewItem;
import be.dda.catalogimport.dao.PublicationRunItemDao.PriorItem;
import be.dda.catalogimport.dao.PublicationRunItemRepository;
import be.dda.catalogimport.dao.PublicationRunRepository;
import be.dda.catalogimport.domain.MutationStatus;
import be.dda.catalogimport.domain.PublicationBundle;
import be.dda.catalogimport.domain.PublicationBundleStatus;
import be.dda.catalogimport.domain.PublicationRun;
import be.dda.catalogimport.domain.PublicationRunItem;
import be.dda.catalogimport.domain.PublicationRunItemStatus;
import be.dda.catalogimport.domain.PublicationRunStatus;
import be.dda.catalogimport.domain.PublicationTargetMode;
import be.dda.catalogimport.service.ProdisPublicationProjection.Item;
import be.dda.catalogimport.service.ProdisPublicationService.ItemResult;
import be.dda.catalogimport.service.ProdisReferenceService.LegacyMasterMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * De orchestratie van een PRODUCTION-run naar de Prodis-database (slice P5c; {@code docs/design/publicatie-prodis-db-direct-
 * design.md} par. 2.4 "Gelijktijdigheid", par. 5, par. 6 rij P5c; {@code publicatie-prodis-db-design.md} par. 2 "Kant
 * CatalogImport"; beslissingen 2026-10-22 P-V1..P-V5, Q1..Q3, P4a, P5b). Geen scheduler: {@link #startProductionRun} legt de
 * run vast en verwerkt de eerste chunk, {@link #continueRun} de volgende (de frontend roept het in een lus, P7). Endpoints
 * en rechten volgen in P6.
 *
 * <h2>Start ({@link #startProductionRun})</h2>
 * Controles in deze volgorde, alles vóór er iets geschreven wordt: 409 {@value #CODE_PUBLICATION_MODE_NOT_ENABLED} (vlag
 * {@code catalogimport.publication.prodis.enabled}, noodrem, default uit); 409
 * {@value ProdisPublicationWriteNotConfiguredException#CODE}; dan in <b>één CI-transactie</b> achter het schrijfslot op de
 * bundel: 404 bundel; 409 {@value #CODE_BUNDLE_NOT_FROZEN}; 409 {@value #CODE_BUNDLE_TARGET_MODE_NOT_PRODUCTION}; 409
 * {@value #CODE_BUNDLE_NOT_SNAPSHOTTED}; 409 {@value #CODE_PUBLICATION_RUN_IN_PROGRESS} (deze bundel); 409
 * {@value #CODE_PRODUCTION_RUN_IN_PROGRESS} (hoogstens één actieve PRODUCTION-run per installatie, A1, hard afgedwongen door
 * {@code uk_publication_run_active_production}); 409 {@value #CODE_BUNDLE_CONTENT_CHANGED_SINCE_FREEZE} (bundelhash én
 * snapshothash opnieuw berekend); 409 {@value #CODE_BUNDLE_HAS_INCOMPLETE_ROWS} (dezelfde onvolledigheid als de
 * SIMULATION-run: een veld {@code UNKNOWN} of {@code NOT_SNAPSHOTTED}). Daarna, in dezelfde transactie: de run
 * ({@code REQUESTED → PREPARING}), één {@code PENDING}-item per snapshotrij (projectie → {@link ProdisPublicationRequestJson},
 * sleutel {@code <catalogimport.publication.source-id>:mut:<mutationId>:pa:<n>}), de run {@code PUBLISHING} met
 * {@code legacy_master_at_start} en de bundel {@code FROZEN → PUBLISHING}. Een bundel in {@code PUBLISHING} is niet meer
 * annuleerbaar ({@link BundleCancellationService} kent enkel ASSEMBLING/FROZEN).
 *
 * <h2>Continue ({@link #continueRun})</h2>
 * <ol>
 *   <li>Chunk-lease op {@code publication_run} (voorwaardelijke update): een geldige lease van een ander → 409
 *       {@value #CODE_PUBLICATION_RUN_BUSY}. Wie de lease neemt, zet elk {@code SENT}-item (van een houder wiens lease
 *       verliep) op {@code RESULT_UNKNOWN} ({@value #ERROR_LEASE_EXPIRED_WHILE_SENT}) en weigert de {@code PENDING}-items
 *       die de grens van zeker niet-toegepaste pogingen bereikten.</li>
 *   <li>Eerst de {@code RESULT_UNKNOWN}-items opnieuw (zelfde sleutel en bytes; reconcile-by-state schrijft niets dubbel),
 *       daarna {@code PENDING}-items, samen hoogstens {@code chunk-size} (default 200). De modus van Prodis wordt per chunk
 *       één keer gelezen en per item meegegeven ({@link ProdisPublicationService#processItem}); er wordt niets op geweigerd
 *       (P-V1). Voor elk item wordt de lease verlengd; is ze overgenomen, dan stopt deze aanroep meteen.</li>
 *   <li>Is daarna elk item terminaal ({@code APPLIED|UNCHANGED|REJECTED|CONFLICT|NOT_SENT}), dan wordt de run
 *       {@code APPLIED} (marker {@code null}: het slot komt vrij, changeset 028-1) en de bundel {@code PUBLISHED} als elk
 *       item {@code APPLIED|UNCHANGED} is, anders {@code PARTIALLY_PUBLISHED}. Anders wordt de lease vrijgegeven.</li>
 * </ol>
 *
 * <h2>Begrenzing van herhaalde pogingen (open punt P5b; aanbeveling gevolgd)</h2>
 * Een item dat {@code max-not-applied-attempts} keer (default 5) zeker niets toepaste ({@code PRODIS_NOT_APPLIED:*} of een
 * teruggedraaide onverwachte fout; {@code not_applied_count}, changeset 028-2) wordt {@code REJECTED}
 * {@value #REASON_PRODIS_NOT_APPLIED_REPEATEDLY} zonder nieuw Prodis-contact, en de mutatie gaat terug naar
 * {@code READY_FOR_PUBLICATION}. Had een eerdere poging een onbekende uitkomst ({@code send_count > not_applied_count}), dan
 * zegt {@code reason_detail} dat Prodis de wijziging misschien al bevat: een volgende poging vindt dan {@code UNCHANGED}.
 * Een {@code RESULT_UNKNOWN}-item wordt nooit zo begrensd (de uitkomst is onbekend, dus nooit "geweigerd").
 *
 * <h2>Herstel</h2>
 * Een PRODUCTION-run staat na de start meteen op {@code PUBLISHING} (nooit {@code PREPARING} na een commit); afbreken of de
 * automatische timeout gelden enkel voor {@code PREPARING} ({@link PublicationRunService#abortRun} geeft voor een
 * {@code PUBLISHING}-run 409 {@code PUBLICATION_RUN_HAS_SENT_ITEMS}). Een run die nooit meer verder gezet wordt, houdt zijn
 * {@code IN_PROGRESS}-mutaties en het slot: er is in v1 bewust geen automatische actie; de toestand is zichtbaar in de
 * run (tellers per itemstatus) en een volgende {@link #continueRun} hervat ze.
 */
@Service
public class ProdisPublicationRunService {

    /** 409: de PRODUCTION-publicatie staat uit ({@code catalogimport.publication.prodis.enabled}). */
    public static final String CODE_PUBLICATION_MODE_NOT_ENABLED = PublicationRunService.CODE_PUBLICATION_MODE_NOT_ENABLED;
    public static final String CODE_BUNDLE_NOT_FOUND = PublicationRunService.CODE_BUNDLE_NOT_FOUND;
    public static final String CODE_BUNDLE_NOT_FROZEN = PublicationRunService.CODE_BUNDLE_NOT_FROZEN;
    public static final String CODE_PUBLICATION_RUN_IN_PROGRESS = PublicationRunService.CODE_PUBLICATION_RUN_IN_PROGRESS;
    public static final String CODE_BUNDLE_CONTENT_CHANGED_SINCE_FREEZE =
            PublicationRunService.CODE_BUNDLE_CONTENT_CHANGED_SINCE_FREEZE;
    public static final String CODE_RUN_NOT_FOUND = PublicationRunService.CODE_RUN_NOT_FOUND;
    /** 409: de bundel heeft een ander publicatiedoel dan PRODUCTION. */
    public static final String CODE_BUNDLE_TARGET_MODE_NOT_PRODUCTION = "BUNDLE_TARGET_MODE_NOT_PRODUCTION";
    /** 409: de bundel werd bevroren vóór de bundelsnapshot bestond ({@code snapshot_hash} leeg). */
    public static final String CODE_BUNDLE_NOT_SNAPSHOTTED = "BUNDLE_NOT_SNAPSHOTTED";
    /** 409: minstens één te publiceren rij heeft een onbekend of niet-gesnapshot veld. */
    public static final String CODE_BUNDLE_HAS_INCOMPLETE_ROWS = "BUNDLE_HAS_INCOMPLETE_ROWS";
    /** 409: er loopt al een PRODUCTION-run (op een andere bundel); hoogstens één per installatie. */
    public static final String CODE_PRODUCTION_RUN_IN_PROGRESS = "PRODUCTION_RUN_IN_PROGRESS";
    /** 409: een andere aanroep houdt de chunk-lease van deze run. */
    public static final String CODE_PUBLICATION_RUN_BUSY = "PUBLICATION_RUN_BUSY";
    /** 409: de run is geen PRODUCTION-run. */
    public static final String CODE_PUBLICATION_RUN_NOT_PRODUCTION = "PUBLICATION_RUN_NOT_PRODUCTION";
    /** 409: de run staat niet op PUBLISHING (en is ook niet afgerond). */
    public static final String CODE_PUBLICATION_RUN_NOT_PUBLISHING = "PUBLICATION_RUN_NOT_PUBLISHING";

    /** {@code reason_code} van een item na te veel zeker niet-toegepaste pogingen. */
    public static final String REASON_PRODIS_NOT_APPLIED_REPEATEDLY = "PRODIS_NOT_APPLIED_REPEATEDLY";
    /** {@code last_error_code}: het item stond {@code SENT} onder een verlopen lease (de vorige houder is weg). */
    public static final String ERROR_LEASE_EXPIRED_WHILE_SENT = "LEASE_EXPIRED_WHILE_SENT";

    /** Snapshotrijen per pagina bij het materialiseren (de bovengrens van de lezer). */
    static final int MATERIALISE_PAGE_SIZE = ProdisPublicationSourceDao.MAX_PAGE_SIZE;
    /** Bundelrijen per pagina bij de onvolledigheidscontrole (zoals het SIMULATION-artefact). */
    static final int INCOMPLETE_PAGE_SIZE = PublicationRunService.ARTIFACT_PAGE_SIZE;
    static final int MAX_ACTOR_LENGTH = PublicationRunService.MAX_ACTOR_LENGTH;

    private static final Set<PublicationRunItemStatus> OPEN = EnumSet.of(PublicationRunItemStatus.PENDING,
            PublicationRunItemStatus.SENT, PublicationRunItemStatus.RESULT_UNKNOWN);
    private static final Set<PublicationRunItemStatus> PUBLISHED = EnumSet.of(PublicationRunItemStatus.APPLIED,
            PublicationRunItemStatus.UNCHANGED);
    private static final Logger LOG = LoggerFactory.getLogger(ProdisPublicationRunService.class);

    /**
     * De configuratie {@code catalogimport.publication.*}. Fail-fast: een ongeldige waarde laat de applicatie niet starten;
     * staat de functie aan, dan is {@code source-id} verplicht.
     *
     * @param sourceId             het eerste deel van elke itemsleutel; uniek per installatie (bv. {@code catalogimport-prod}),
     *                             nooit wijzigen zolang er items bestaan
     * @param chunkSize            items per {@code continue} (1..1000)
     * @param lease                geldigheid van de chunk-lease, verlengd per item
     * @param maxNotAppliedAttempts zeker niet-toegepaste pogingen per item vóór {@code REJECTED} (1..100)
     */
    public record Settings(boolean enabled, String sourceId, int chunkSize, Duration lease, int maxNotAppliedAttempts) {

        static final Pattern SOURCE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

        public Settings {
            if (sourceId != null && sourceId.isBlank()) {
                sourceId = null;
            }
            if (enabled && sourceId == null) {
                throw new IllegalStateException("catalogimport.publication.source-id is required when "
                        + "catalogimport.publication.prodis.enabled is true");
            }
            if (sourceId != null && !SOURCE_ID.matcher(sourceId).matches()) {
                throw new IllegalStateException("catalogimport.publication.source-id must be 1..100 characters [A-Za-z0-9._-] "
                        + "starting with a letter or digit (was '" + sourceId + "')");
            }
            if (chunkSize < 1 || chunkSize > 1000) {
                throw new IllegalStateException("catalogimport.publication.chunk-size must be 1..1000 (was " + chunkSize + ")");
            }
            if (lease == null || lease.isZero() || lease.isNegative()) {
                throw new IllegalStateException("catalogimport.publication.lease must be positive (was " + lease + ")");
            }
            if (maxNotAppliedAttempts < 1 || maxNotAppliedAttempts > 100) {
                throw new IllegalStateException("catalogimport.publication.max-not-applied-attempts must be 1..100 (was "
                        + maxNotAppliedAttempts + ")");
            }
        }

        static Duration parseLease(String value) {
            try {
                return Duration.parse(value == null ? "" : value.trim());
            } catch (DateTimeParseException invalid) {
                throw new IllegalStateException("catalogimport.publication.lease must be an ISO-8601 duration such as PT5M "
                        + "(was '" + value + "')", invalid);
            }
        }
    }

    /**
     * Wat één aanroep van {@link #continueRun} deed.
     *
     * @param legacyMaster         de modus van Prodis waaronder deze chunk verstuurd werd
     * @param processed            items die {@link ProdisPublicationService#processItem} doorliepen
     * @param recoveredSent        {@code SENT}-items van een verlopen lease die {@code RESULT_UNKNOWN} werden
     * @param rejectedRepeatedly   items die {@value #REASON_PRODIS_NOT_APPLIED_REPEATEDLY} werden
     * @param statusAfterProcessing aantal verwerkte items per status na de verwerking
     * @param leaseLost            de lease werd tijdens de chunk overgenomen; de rest van de chunk is niet verwerkt
     */
    public record ChunkResult(String legacyMaster, int processed, int recoveredSent, int rejectedRepeatedly,
                              Map<String, Integer> statusAfterProcessing, boolean leaseLost) {
    }

    /**
     * Een PRODUCTION-run zoals de service ze toont (P6 maakt er het API-contract van). Zonder Prodis-id's en zonder subject.
     *
     * @param itemCounts         aantal items per status (enkel statussen die voorkomen)
     * @param openItemCount      items die nog niet terminaal zijn ({@code PENDING|SENT|RESULT_UNKNOWN})
     * @param legacyMasterWarningCode {@code LEGACY_MASTER_DATASYNC_REVERTS} bij PRODIS/PROFAB, {@code LEGACY_MASTER_UNKNOWN}
     *                           bij een onbekende modus, anders {@code null} (design par. 5)
     * @param lastChunk          wat deze aanroep deed; {@code null} als er niets verwerkt werd
     */
    public record ProductionRunView(long runId, long bundleId, int attempt, String status, String bundleStatus,
                                    String requestedBy, Instant requestedAt, Instant startedAt, Instant finishedAt,
                                    String legacyMasterAtStart, String legacyMasterWarningCode, long itemCount,
                                    Map<String, Long> itemCounts, long openItemCount, boolean finished,
                                    ChunkResult lastChunk) {
    }

    private record Lease(boolean acquired, int recoveredSent, int rejectedRepeatedly) {
    }

    private final PublicationRunRepository runs;
    private final PublicationRunItemRepository items;
    private final PublicationRunItemDao itemDao;
    private final PublicationBundleRepository bundles;
    private final PublicationBundleDao bundleDao;
    private final PublicationBundleSnapshotDao snapshotDao;
    private final PsimportPreviewDao previewDao;
    private final ProdisPublicationSourceDao sourceDao;
    private final ImportMutationRepository mutations;
    private final ProdisPublicationService publication;
    private final ProdisPublicationWriter writer;
    private final ProdisReferenceService reference;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Settings settings;

    @Autowired
    public ProdisPublicationRunService(PublicationRunRepository runs, PublicationRunItemRepository items,
                                       PublicationRunItemDao itemDao, PublicationBundleRepository bundles,
                                       PublicationBundleDao bundleDao, PublicationBundleSnapshotDao snapshotDao,
                                       PsimportPreviewDao previewDao, ProdisPublicationSourceDao sourceDao,
                                       ImportMutationRepository mutations, ProdisPublicationService publication,
                                       ProdisPublicationWriter writer, ProdisReferenceService reference,
                                       PlatformTransactionManager transactionManager, Clock clock,
                                       @Value("${catalogimport.publication.prodis.enabled:false}") boolean enabled,
                                       @Value("${catalogimport.publication.source-id:}") String sourceId,
                                       @Value("${catalogimport.publication.chunk-size:200}") int chunkSize,
                                       @Value("${catalogimport.publication.lease:PT5M}") String lease,
                                       @Value("${catalogimport.publication.max-not-applied-attempts:5}")
                                       int maxNotAppliedAttempts) {
        this(runs, items, itemDao, bundles, bundleDao, snapshotDao, previewDao, sourceDao, mutations, publication, writer,
                reference, transactionManager, clock,
                new Settings(enabled, sourceId, chunkSize, Settings.parseLease(lease), maxNotAppliedAttempts));
    }

    /** Met expliciete instellingen (tests). */
    public ProdisPublicationRunService(PublicationRunRepository runs, PublicationRunItemRepository items,
                                       PublicationRunItemDao itemDao, PublicationBundleRepository bundles,
                                       PublicationBundleDao bundleDao, PublicationBundleSnapshotDao snapshotDao,
                                       PsimportPreviewDao previewDao, ProdisPublicationSourceDao sourceDao,
                                       ImportMutationRepository mutations, ProdisPublicationService publication,
                                       ProdisPublicationWriter writer, ProdisReferenceService reference,
                                       PlatformTransactionManager transactionManager, Clock clock, Settings settings) {
        this.runs = runs;
        this.items = items;
        this.itemDao = itemDao;
        this.bundles = bundles;
        this.bundleDao = bundleDao;
        this.snapshotDao = snapshotDao;
        this.previewDao = previewDao;
        this.sourceDao = sourceDao;
        this.mutations = mutations;
        this.publication = publication;
        this.writer = writer;
        this.reference = reference;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.settings = settings;
        if (settings.enabled()) {
            LOG.info("Prodis publication (PRODUCTION) enabled: source-id '{}', chunk size {}, lease {}, at most {} "
                    + "not-applied attempts per item", settings.sourceId(), settings.chunkSize(), settings.lease(),
                    settings.maxNotAppliedAttempts());
        }
    }

    public Settings settings() {
        return settings;
    }

    // --- Start ---------------------------------------------------------------------------------------------------

    /**
     * Start een PRODUCTION-run op een bevroren PRODUCTION-bundel en verwerkt de eerste chunk (zie de klassebeschrijving).
     *
     * @param actor de aanvrager ({@code requested_by(_subject)}) en verzender van de eerste chunk
     * @return de run na de eerste chunk; was de lease meteen bezet (een gelijktijdige {@code continue}), dan de run zonder
     *     chunk
     * @throws ConflictException  zie de klassebeschrijving; er wordt dan niets geschreven
     * @throws NotFoundException  {@value #CODE_BUNDLE_NOT_FOUND}
     */
    public ProductionRunView startProductionRun(long bundleId, ActorIdentity actor) {
        requireEnabledAndConfigured();
        if (actor == null) {
            throw new IllegalArgumentException("Missing requestedBy");
        }
        String requester = ActorNames.requireActorName(actor.username(), "requestedBy", MAX_ACTOR_LENGTH);
        LegacyMasterMode mode = readLegacyMaster();
        long runId = transaction.execute(status -> createRun(bundleId, requester, actor.subject(), mode));
        try {
            return continueRun(runId, actor);
        } catch (ConflictException busy) {
            if (CODE_PUBLICATION_RUN_BUSY.equals(busy.getCode())) {
                // De run bestaat en loopt al (een gelijktijdige continue nam de lease): geen fout voor de aanvrager.
                return view(runId, null);
            }
            throw busy;
        }
    }

    private long createRun(long bundleId, String requester, String requesterSubject, LegacyMasterMode mode) {
        PublicationBundle bundle = bundles.findByIdForUpdate(bundleId).orElseThrow(
                () -> new NotFoundException(CODE_BUNDLE_NOT_FOUND, "Bundle " + bundleId + " not found"));
        if (bundle.getStatus() != PublicationBundleStatus.FROZEN) {
            throw new ConflictException(CODE_BUNDLE_NOT_FROZEN, "Bundle " + bundleId + " is " + bundle.getStatus()
                    + "; only a FROZEN bundle can be published");
        }
        if (bundle.getTargetMode() != PublicationTargetMode.PRODUCTION) {
            throw new ConflictException(CODE_BUNDLE_TARGET_MODE_NOT_PRODUCTION, "Bundle " + bundleId + " targets "
                    + bundle.getTargetMode() + "; only a PRODUCTION bundle is published to Prodis");
        }
        if (bundle.getSnapshotHash() == null) {
            // Ook een bundel zonder enige publiceerbare mutatie heeft geen snapshothash (BundleFreezeService, 5P-3).
            throw new ConflictException(CODE_BUNDLE_NOT_SNAPSHOTTED, "Bundle " + bundleId + " has no bundle snapshot "
                    + "(frozen before the snapshot existed, or without any publishable mutation); it cannot be published "
                    + "to Prodis");
        }
        if (runs.findByBundleIdAndActiveMarkerIsNotNull(bundleId).isPresent()) {
            throw new ConflictException(CODE_PUBLICATION_RUN_IN_PROGRESS, "Bundle " + bundleId
                    + " already has a publication run in progress; wait for it to finish");
        }
        runs.findFirstByTargetModeAndActiveMarkerIsNotNull(PublicationTargetMode.PRODUCTION).ifPresent(active -> {
            throw new ConflictException(CODE_PRODUCTION_RUN_IN_PROGRESS, "Production run " + active.getId()
                    + " is still in progress; only one production run at a time");
        });
        requireUnchangedContent(bundle);
        long incomplete = countIncompleteRows(bundleId);
        if (incomplete > 0) {
            throw new ConflictException(CODE_BUNDLE_HAS_INCOMPLETE_ROWS, "Bundle " + bundleId + " has " + incomplete
                    + " row(s) with an unknown or not snapshotted field; nothing was published");
        }

        Integer highest = runs.findMaxAttempt(bundleId, PublicationTargetMode.PRODUCTION);
        int attempt = (highest == null ? 0 : highest) + 1;
        Instant now = clock.instant();
        PublicationRun run = new PublicationRun(bundle, PublicationTargetMode.PRODUCTION, attempt, requester,
                requesterSubject, now, bundle.getContentHash(), bundle.getSnapshotHash(),
                PublicationRunService.idempotencyKey(bundleId, PublicationTargetMode.PRODUCTION, attempt));
        try {
            runs.saveAndFlush(run);
        } catch (DataIntegrityViolationException collision) {
            // uk_publication_run_active_production: een gelijktijdige start op een andere bundel was ons net voor (deze
            // bundel is vergrendeld, dus het is nooit dezelfde bundel). Geen naamloze 500.
            throw new ConflictException(CODE_PRODUCTION_RUN_IN_PROGRESS, "Another production run started at the same "
                    + "time; only one production run at a time");
        }
        run.markPreparing(now);
        runs.saveAndFlush(run);

        long itemCount = materialise(run.getId(), bundleId);
        run.markPublishing(mode.name(), itemCount);
        runs.saveAndFlush(run);
        bundle.setStatus(PublicationBundleStatus.PUBLISHING);
        bundles.saveAndFlush(bundle);
        LOG.info("Production run {} started for bundle {} by {} (attempt {}, {} item(s), legacy_master {})", run.getId(),
                bundleId, requester, attempt, itemCount, mode);
        return run.getId();
    }

    /**
     * Herberekent bundelhash en snapshothash: wat gepubliceerd wordt, is exact wat bevroren werd (zelfde regel en code als
     * de SIMULATION-run).
     */
    private void requireUnchangedContent(PublicationBundle bundle) {
        long bundleId = bundle.getId();
        if (!Arrays.equals(bundleDao.computeContentHash(bundleId), bundle.getContentHash())
                || !Arrays.equals(snapshotDao.computeSnapshotHash(bundleId, bundle.getSnapshotSpecVersion()),
                bundle.getSnapshotHash())) {
            throw new ConflictException(CODE_BUNDLE_CONTENT_CHANGED_SINCE_FREEZE, "The content or snapshot of bundle "
                    + bundleId + " changed after it was frozen (a recomputed hash differs from the stored one); nothing "
                    + "was published. Investigate the change before publishing this bundle");
        }
    }

    /** Zelfde definitie als {@code incomplete_row_count} van de SIMULATION-run ({@link PsimportPreviewMapper}). */
    private long countIncompleteRows(long bundleId) {
        long incomplete = 0;
        long offset = 0;
        while (true) {
            List<PsimportPreviewDao.SourceRow> page = previewDao.findPage(bundleId, INCOMPLETE_PAGE_SIZE, offset);
            for (PsimportPreviewDao.SourceRow row : page) {
                if (!PsimportPreviewMapper.map(row).complete()) {
                    incomplete++;
                }
            }
            offset += page.size();
            if (page.size() < INCOMPLETE_PAGE_SIZE) {
                return incomplete;
            }
        }
    }

    /**
     * Eén {@code PENDING}-item per snapshotrij, met het bevroren verzoek. {@code n} = hoogste eerdere poging van die
     * mutatie + 1; een eerder item dat niet definitief niet-toegepast is ({@code REJECTED|CONFLICT|NOT_SENT}) zou een
     * dubbele publicatie betekenen en stopt de start.
     */
    private long materialise(long runId, long bundleId) {
        long after = 0;
        long total = 0;
        while (true) {
            List<SourceRow> page = sourceDao.findPage(bundleId, after, MATERIALISE_PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            Map<Long, Integer> highest = new HashMap<>();
            for (PriorItem prior : itemDao.findPriorItems(page.stream().map(SourceRow::mutationId).toList())) {
                PublicationRunItemStatus status = PublicationRunItemStatus.valueOf(prior.status());
                if (status != PublicationRunItemStatus.REJECTED && status != PublicationRunItemStatus.CONFLICT
                        && status != PublicationRunItemStatus.NOT_SENT) {
                    throw new IllegalStateException("Mutation " + prior.mutationId() + " already has publication item "
                            + "attempt " + prior.publicationAttempt() + " in status " + status + "; refusing to publish it "
                            + "twice");
                }
                highest.merge(prior.mutationId(), prior.publicationAttempt(), Math::max);
            }
            List<NewItem> news = new ArrayList<>(page.size());
            for (SourceRow row : page) {
                int attempt = highest.getOrDefault(row.mutationId(), 0) + 1;
                Item request = ProdisPublicationProjection.project(row);
                String json = ProdisPublicationRequestJson.write(request);
                news.add(new NewItem(row.mutationId(), attempt, idempotencyKey(row.mutationId(), attempt),
                        ProdisPublicationRequestJson.sha256Hex(json), json));
            }
            int inserted = itemDao.insertPending(runId, news);
            if (inserted != news.size()) {
                throw new IllegalStateException("Run " + runId + ": " + inserted + " of " + news.size()
                        + " items inserted");
            }
            total += inserted;
            after = page.get(page.size() - 1).mutationId();
            if (page.size() < MATERIALISE_PAGE_SIZE) {
                break;
            }
        }
        long expected = sourceDao.count(bundleId);
        if (total != expected) {
            throw new IllegalStateException("Run " + runId + ": " + total + " items for " + expected + " snapshot rows");
        }
        return total;
    }

    /** {@code <source-id>:mut:<mutationId>:pa:<n>} (design par. 2, "Idempotentie"). */
    String idempotencyKey(long mutationId, int attempt) {
        return settings.sourceId() + ":mut:" + mutationId + ":pa:" + attempt;
    }

    // --- Continue ------------------------------------------------------------------------------------------------

    /**
     * Verwerkt de volgende chunk van een PRODUCTION-run (zie de klassebeschrijving). Idempotent op een afgeronde run: die
     * wordt ongewijzigd teruggegeven.
     *
     * @param actor de verzender van deze chunk ({@code sent_by(_subject)}, Prodis-audit)
     * @throws NotFoundException {@value #CODE_RUN_NOT_FOUND}
     * @throws ConflictException {@value #CODE_PUBLICATION_MODE_NOT_ENABLED}, {@code PRODIS_WRITE_NOT_CONFIGURED},
     *                           {@value #CODE_PUBLICATION_RUN_NOT_PRODUCTION}, {@value #CODE_PUBLICATION_RUN_NOT_PUBLISHING},
     *                           {@value #CODE_PUBLICATION_RUN_BUSY}; of een itemfout van
     *                           {@link ProdisPublicationService#processItem} (de lease wordt dan vrijgegeven)
     */
    public ProductionRunView continueRun(long runId, ActorIdentity actor) {
        requireEnabledAndConfigured();
        if (actor == null) {
            throw new IllegalArgumentException("Missing sentBy");
        }
        ActorNames.requireActorName(actor.username(), "sentBy", ProdisPublicationService.MAX_SENT_BY_LENGTH);
        String owner = "continue:" + UUID.randomUUID();
        Lease lease = transaction.execute(status -> acquire(runId, owner));
        if (!lease.acquired()) {
            return view(runId, null);
        }

        LegacyMasterMode mode = readLegacyMaster();
        Map<String, Integer> after = new LinkedHashMap<>();
        int processed = 0;
        int rejected = lease.rejectedRepeatedly();
        boolean leaseLost = false;
        boolean completed = false;
        try {
            for (Long itemId : selectChunk(runId)) {
                if (!renew(runId, owner)) {
                    leaseLost = true;
                    LOG.warn("Production run {}: the lease of {} was taken over; this chunk stops after {} item(s)", runId,
                            owner, processed);
                    break;
                }
                ItemResult result = publication.processItem(itemId, actor, mode);
                processed++;
                PublicationRunItemStatus status = result.status();
                if (status == PublicationRunItemStatus.PENDING && rejectIfExhausted(itemId)) {
                    rejected++;
                    status = PublicationRunItemStatus.REJECTED;
                }
                after.merge(status.name(), 1, Integer::sum);
            }
            completed = true;
        } finally {
            if (!completed && !leaseLost) {
                releaseQuietly(runId, owner);
            }
        }
        if (!leaseLost) {
            transaction.executeWithoutResult(status -> finishOrRelease(runId, owner));
        }
        ChunkResult chunk = new ChunkResult(mode.name(), processed, lease.recoveredSent(), rejected,
                Collections.unmodifiableMap(after), leaseLost);
        LOG.info("Production run {}: chunk of {} item(s) under legacy_master {} ({}; {} recovered from SENT, {} rejected "
                + "after repeated failures)", runId, processed, mode, after, lease.recoveredSent(), rejected);
        return view(runId, chunk);
    }

    private Lease acquire(long runId, String owner) {
        PublicationRun run = runs.findById(runId).orElseThrow(
                () -> new NotFoundException(CODE_RUN_NOT_FOUND, "Publication run " + runId + " not found"));
        if (run.getTargetMode() != PublicationTargetMode.PRODUCTION) {
            throw new ConflictException(CODE_PUBLICATION_RUN_NOT_PRODUCTION, "Publication run " + runId + " is a "
                    + run.getTargetMode() + " run; only a PRODUCTION run publishes to Prodis");
        }
        if (run.getStatus() == PublicationRunStatus.APPLIED) {
            return new Lease(false, 0, 0);
        }
        if (run.getStatus() != PublicationRunStatus.PUBLISHING) {
            throw new ConflictException(CODE_PUBLICATION_RUN_NOT_PUBLISHING, "Publication run " + runId + " is "
                    + run.getStatus() + "; only a PUBLISHING run can continue");
        }
        Instant now = clock.instant();
        Instant heldUntil = run.getLeaseUntil();
        if (runs.acquireLease(runId, owner, now.plus(settings.lease()), now, PublicationRunStatus.PUBLISHING) != 1) {
            PublicationRun fresh = runs.findById(runId).orElseThrow();
            if (fresh.getStatus() == PublicationRunStatus.APPLIED) {
                return new Lease(false, 0, 0);
            }
            throw new ConflictException(CODE_PUBLICATION_RUN_BUSY, "Publication run " + runId + " is being processed "
                    + "by another request (lease until " + (fresh.getLeaseUntil() != null ? fresh.getLeaseUntil()
                    : heldUntil) + "); try again later");
        }
        // Wie de lease heeft, is de enige die verstuurt: een SENT-item is van een vorige houder wiens lease verliep.
        int recovered = 0;
        for (PublicationRunItem sent : items.findByRunIdAndStatusOrderByIdAsc(runId, PublicationRunItemStatus.SENT)) {
            sent.recordResultUnknown(ERROR_LEASE_EXPIRED_WHILE_SENT);
            items.saveAndFlush(sent);
            recovered++;
            LOG.warn("Production run {}: item {} was SENT under an expired lease; now RESULT_UNKNOWN, retried first", runId,
                    sent.getId());
        }
        int rejected = 0;
        for (PublicationRunItem exhausted : items.findByRunIdAndStatusAndNotAppliedCountAtLeast(runId,
                PublicationRunItemStatus.PENDING, settings.maxNotAppliedAttempts())) {
            reject(exhausted);
            rejected++;
        }
        return new Lease(true, recovered, rejected);
    }

    /** Eerst {@code RESULT_UNKNOWN}, dan {@code PENDING}; samen hoogstens {@code chunk-size}. */
    private List<Long> selectChunk(long runId) {
        int size = settings.chunkSize();
        List<Long> chunk = new ArrayList<>(items.findIdsByRunIdAndStatus(runId, PublicationRunItemStatus.RESULT_UNKNOWN,
                Pageable.ofSize(size)));
        if (chunk.size() < size) {
            chunk.addAll(items.findIdsByRunIdAndStatus(runId, PublicationRunItemStatus.PENDING,
                    Pageable.ofSize(size - chunk.size())));
        }
        return chunk;
    }

    private boolean renew(long runId, String owner) {
        Integer renewed = transaction.execute(status -> runs.renewLease(runId, owner,
                clock.instant().plus(settings.lease()), PublicationRunStatus.PUBLISHING));
        return renewed != null && renewed == 1;
    }

    private void releaseQuietly(long runId, String owner) {
        try {
            transaction.executeWithoutResult(status -> runs.releaseLease(runId, owner));
        } catch (RuntimeException failure) {
            // De lease verloopt vanzelf; de oorspronkelijke fout gaat naar boven.
            LOG.warn("Production run {}: could not release lease {} (it expires on its own)", runId, owner, failure);
        }
    }

    /** Een {@code PENDING}-item dat de grens bereikte: {@code REJECTED} in een eigen transactie. */
    private boolean rejectIfExhausted(long itemId) {
        Boolean rejected = transaction.execute(status -> {
            PublicationRunItem item = items.findByIdForUpdate(itemId).orElseThrow();
            if (item.getStatus() != PublicationRunItemStatus.PENDING
                    || item.getNotAppliedCount() < settings.maxNotAppliedAttempts()) {
                return false;
            }
            reject(item);
            return true;
        });
        return Boolean.TRUE.equals(rejected);
    }

    private void reject(PublicationRunItem item) {
        int max = settings.maxNotAppliedAttempts();
        boolean possiblyWritten = item.getSendCount() > item.getNotAppliedCount();
        String detail = "Prodis applied nothing in " + item.getNotAppliedCount() + " attempt(s) (last error "
                + item.getLastErrorCode() + "); the mutation is ready for a new publication attempt"
                + (possiblyWritten ? ". An earlier attempt had an unknown outcome: Prodis may already contain this change; "
                + "a new attempt reconciles it (UNCHANGED)" : "");
        String resultJson = "{\"rejectedBy\":\"" + REASON_PRODIS_NOT_APPLIED_REPEATEDLY + "\",\"notAppliedCount\":"
                + item.getNotAppliedCount() + ",\"sendCount\":" + item.getSendCount() + ",\"maxNotAppliedAttempts\":" + max
                + ",\"possiblyWrittenEarlier\":" + possiblyWritten + "}";
        item.rejectAfterRepeatedNotApplied(clock.instant(), max, REASON_PRODIS_NOT_APPLIED_REPEATEDLY,
                detail.length() <= ProdisPublicationService.MAX_REASON_DETAIL_LENGTH ? detail
                        : detail.substring(0, ProdisPublicationService.MAX_REASON_DETAIL_LENGTH - 3) + "...",
                resultJson);
        items.saveAndFlush(item);
        long mutationId = item.getMutation().getId();
        if (mutations.transitionStatus(mutationId, MutationStatus.IN_PROGRESS, MutationStatus.READY_FOR_PUBLICATION) != 1) {
            LOG.warn("Publication item {}: mutation {} was not IN_PROGRESS; its status was left unchanged", item.getId(),
                    mutationId);
        }
        LOG.warn("Publication item {} (mutation {}): REJECTED {} after {} not-applied attempt(s){}", item.getId(),
                mutationId, REASON_PRODIS_NOT_APPLIED_REPEATEDLY, item.getNotAppliedCount(),
                possiblyWritten ? " (an earlier attempt had an unknown outcome)" : "");
    }

    /**
     * Afronden als elk item terminaal is, anders de lease vrijgeven. Achter het schrijfslot op de bundel (slotvolgorde
     * bundel, run) en enkel door de leasehouder.
     */
    private void finishOrRelease(long runId, String owner) {
        Long bundleId = runs.findBundleIdByRunId(runId).orElseThrow();
        PublicationBundle bundle = bundles.findByIdForUpdate(bundleId).orElseThrow();
        PublicationRun run = runs.findById(runId).orElseThrow();
        if (run.getStatus() != PublicationRunStatus.PUBLISHING || !owner.equals(run.getLeaseOwner())) {
            return;
        }
        Map<PublicationRunItemStatus, Long> counts = counts(runId);
        long open = OPEN.stream().mapToLong(status -> counts.getOrDefault(status, 0L)).sum();
        if (open > 0) {
            runs.releaseLease(runId, owner);
            return;
        }
        long notPublished = counts.entrySet().stream().filter(entry -> !PUBLISHED.contains(entry.getKey()))
                .mapToLong(Map.Entry::getValue).sum();
        if (bundle.getStatus() != PublicationBundleStatus.PUBLISHING) {
            throw new IllegalStateException("Bundle " + bundleId + " of production run " + runId + " is "
                    + bundle.getStatus() + ", not PUBLISHING");
        }
        PublicationBundleStatus outcome = notPublished == 0 ? PublicationBundleStatus.PUBLISHED
                : PublicationBundleStatus.PARTIALLY_PUBLISHED;
        run.recordApplied(clock.instant());
        runs.saveAndFlush(run);
        bundle.setStatus(outcome);
        bundles.saveAndFlush(bundle);
        LOG.info("Production run {} finished: run APPLIED, bundle {} {} (items {})", runId, bundleId, outcome, counts);
    }

    // --- Lezen ---------------------------------------------------------------------------------------------------

    /**
     * Eén PRODUCTION-run met de tellers per itemstatus.
     *
     * @throws NotFoundException {@value #CODE_RUN_NOT_FOUND}
     * @throws ConflictException {@value #CODE_PUBLICATION_RUN_NOT_PRODUCTION}
     */
    public ProductionRunView getProductionRun(long runId) {
        return view(runId, null);
    }

    private ProductionRunView view(long runId, ChunkResult chunk) {
        return transaction.execute(status -> {
            PublicationRun run = runs.findById(runId).orElseThrow(
                    () -> new NotFoundException(CODE_RUN_NOT_FOUND, "Publication run " + runId + " not found"));
            if (run.getTargetMode() != PublicationTargetMode.PRODUCTION) {
                throw new ConflictException(CODE_PUBLICATION_RUN_NOT_PRODUCTION, "Publication run " + runId + " is a "
                        + run.getTargetMode() + " run");
            }
            Map<PublicationRunItemStatus, Long> counts = counts(runId);
            Map<String, Long> byName = new LinkedHashMap<>();
            counts.forEach((key, value) -> byName.put(key.name(), value));
            long open = OPEN.stream().mapToLong(s -> counts.getOrDefault(s, 0L)).sum();
            long total = counts.values().stream().mapToLong(Long::longValue).sum();
            return new ProductionRunView(run.getId(), run.getBundle().getId(), run.getAttempt(), run.getStatus().name(),
                    run.getBundle().getStatus().name(), run.getRequestedBy(), run.getRequestedAt(), run.getStartedAt(),
                    run.getFinishedAt(), run.getLegacyMasterAtStart(), warningCode(run.getLegacyMasterAtStart()), total,
                    Collections.unmodifiableMap(byName), open, run.getStatus().isTerminal(), chunk);
        });
    }

    private Map<PublicationRunItemStatus, Long> counts(long runId) {
        Map<PublicationRunItemStatus, Long> counts = new EnumMap<>(PublicationRunItemStatus.class);
        for (Object[] row : items.countByStatus(runId)) {
            counts.put((PublicationRunItemStatus) row[0], (Long) row[1]);
        }
        return counts;
    }

    private static String warningCode(String legacyMaster) {
        if (legacyMaster == null) {
            return null;
        }
        return switch (legacyMaster) {
            case "NONE" -> null;
            case "PRODIS", "PROFAB" -> PublicationParallelRunService.WARNING_LEGACY_MASTER_DATASYNC_REVERTS;
            default -> PublicationParallelRunService.WARNING_LEGACY_MASTER_UNKNOWN;
        };
    }

    // --- Gedeeld -------------------------------------------------------------------------------------------------

    private void requireEnabledAndConfigured() {
        if (!settings.enabled()) {
            throw new ConflictException(CODE_PUBLICATION_MODE_NOT_ENABLED, "Publishing to Prodis (PRODUCTION) is not "
                    + "enabled on this environment (catalogimport.publication.prodis.enabled is false)");
        }
        if (!writer.isConfigured()) {
            throw new ProdisPublicationWriteNotConfiguredException();
        }
    }

    /** De modus van Prodis via de leesbron; niet geconfigureerd of onleesbaar = UNKNOWN, nooit een weigering (P-V1). */
    private LegacyMasterMode readLegacyMaster() {
        return reference.isConfigured() ? reference.legacyMaster().mode() : LegacyMasterMode.UNKNOWN;
    }
}
