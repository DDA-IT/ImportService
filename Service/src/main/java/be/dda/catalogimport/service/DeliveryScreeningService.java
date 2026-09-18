package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.CandidateStageDao;
import be.dda.catalogimport.dao.CandidateStageDao.DuplicateRow;
import be.dda.catalogimport.dao.CandidateStageDao.StageRow;
import be.dda.catalogimport.dao.DeliveryFileRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.MutationDao;
import be.dda.catalogimport.dao.MutationDao.MutationContext;
import be.dda.catalogimport.dao.RowIssueDao;
import be.dda.catalogimport.dao.RowIssueDao.IssueRow;
import be.dda.catalogimport.dao.TaskRunRepository;
import be.dda.catalogimport.domain.CandidateClassification;
import be.dda.catalogimport.domain.Delivery;
import be.dda.catalogimport.domain.DeliveryFile;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.RowIssueSeverity;
import be.dda.catalogimport.domain.TaskRun;
import be.dda.catalogimport.domain.TaskRunStatus;
import be.dda.catalogimport.service.support.CandidateNormaliser;
import be.dda.catalogimport.service.support.CandidateNormaliser.NormalisedCandidate;
import be.dda.catalogimport.service.support.CsvRecordStreamer;
import be.dda.catalogimport.service.support.CsvRecordStreamer.LineIssue;
import be.dda.catalogimport.service.support.CsvRecordStreamer.ParsedRow;
import be.dda.catalogimport.service.support.CsvRecordStreamer.ReadSummary;
import be.dda.catalogimport.service.support.ScreeningBlockedException;
import be.dda.catalogimport.service.support.SourceStructureConfig;
import be.dda.catalogimport.service.support.SourceStructureConfigFactory;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Screent één {@link ImportBatch} van begin tot eind: het gearchiveerde bronbestand lezen en stagen
 * (design par. 9 stap C), dubbele identiteiten en hashcollisies vaststellen (stap D), de delta tegen
 * de bronstaat bepalen en de mutatielijst genereren (stap E) en de screening afronden met tellers,
 * {@code IMPORT_MARKER} en eindstatus (stap F).
 * <p>
 * <b>Businessregels die hier hard zijn.</b>
 * <ul>
 *   <li>Een inhoudelijk ongeldige regel verwerpt <b>enkel die regel</b> (aanname A4) en levert nooit
 *       een prijs 0 op; de batch eindigt dan gewoon op {@code SCREENED} met
 *       {@code rejected_record_count > 0}.</li>
 *   <li>Een contract- of structuurfout (leeg bestand, header-only, ontbrekend headerveld,
 *       kolomaantal, aantalsmismatch, te veel rijfouten, dubbele identiteit, hashcollisie) blokkeert
 *       de <b>volledige</b> levering: {@code BLOCKED}, nul inhoudelijke mutaties, wel één marker met
 *       {@code outcome=BLOCKED}.</li>
 *   <li>Een dubbele aanbiedingsidentiteit binnen één levering is nooit "laatste wint": élke
 *       betrokken regel krijgt een probleem en de levering blokkeert.</li>
 *   <li>De screening schrijft <b>nooit</b> in {@code catalog_source_state}. Een ongewijzigde regel
 *       raakt de bronstaat niet aan en levert geen mutatie op. Het bijwerken van de bronstaat is een
 *       aparte, geauditeerde actie (beslissingslog 18/09, accept-baseline).</li>
 *   <li>Een technische fout tijdens het stagen levert {@code FAILED} op: geen marker, geen mutaties,
 *       staging en problemen van die poging opgeruimd.</li>
 * </ul>
 * <b>Transactiegrenzen.</b> Deze orchestrator is bewust <b>niet</b> {@code @Transactional}: één
 * transactie over een miljoen regels zou het transactielog en het geheugen laten vollopen en na een
 * crash alles verliezen. In plaats daarvan: één korte transactie voor de overgang naar
 * {@code SCREENING}, één transactie per microbatch tijdens het stagen
 * ({@code catalogimport.screening.stage-batch-size}), één transactie per chunk tijdens de
 * mutatiegeneratie ({@code catalogimport.screening.mutation-chunk-size}, die ook
 * {@code mutation_progress_row_number} bijwerkt) en één afrondende transactie die tellers, marker,
 * eindstatus en {@code TaskRun} samen vastlegt. Er wordt nooit in dezelfde transactie via JPA
 * teruggelezen wat via JdbcTemplate geschreven is.
 * <p>
 * <b>Hervatten.</b> Breekt de mutatiegeneratie halverwege af, dan blijft de batch op
 * {@code MUTATING} staan — met haar staging, haar reeds geschreven mutaties en haar hervatpunt.
 * {@link #continueMutating(long)} pakt die batch opnieuw op. Dubbele mutaties zijn onmogelijk: elke
 * insert slaat bestaande idempotentiesleutels over en {@code uk_import_mutation_idempotency} is de
 * harde garantie. Dat is bewust géén {@code FAILED}: het werk dat al gedaan is (mogelijk honderden
 * chunks) mag niet weggegooid worden, en design par. 9 merkt {@code MUTATING} expliciet als
 * hervatbaar aan. De HTTP-ingang ({@code POST /batches/{id}/continue}) en de opstartrecovery
 * ({@link ScreeningRecoveryService}) zijn bouwstap 2e.
 */
@Service
public class DeliveryScreeningService {

    /** Het verwachte byte-aantal uit het manifest klopt niet met het ontvangen bestand. */
    public static final String CODE_BYTE_SIZE_MISMATCH = "BYTE_SIZE_MISMATCH";
    /** Het verwachte recordaantal uit het manifest klopt niet met het gelezen bestand. */
    public static final String CODE_RECORD_COUNT_MISMATCH = "RECORD_COUNT_MISMATCH";
    /** Het bestand bevat een header maar geen enkele datalijn (aanname A3). */
    public static final String CODE_SOURCE_NO_DATA_RECORDS = "SOURCE_NO_DATA_RECORDS";
    /** Meer verworpen regels dan {@code catalogimport.screening.max-recorded-row-issues}. */
    public static final String CODE_TOO_MANY_ROW_ISSUES = "TOO_MANY_ROW_ISSUES";
    /** Dezelfde aanbiedingsidentiteit komt meermaals voor in één levering; nooit "laatste wint". */
    public static final String CODE_DUPLICATE_IDENTITY_IN_DELIVERY = "DUPLICATE_IDENTITY_IN_DELIVERY";
    /** Zelfde identiteitshash, andere sleutelcomponenten: de identiteit is niet betrouwbaar. */
    public static final String CODE_IDENTITY_HASH_COLLISION = "IDENTITY_HASH_COLLISION";
    /** Technische fout tijdens de screening; batch en run eindigen op FAILED. */
    public static final String CODE_SCREENING_FAILED = "SCREENING_FAILED";
    /** Deze levering is onder deze revisie al gescreend; een tweede screening is een conflict. */
    public static final String CODE_ALREADY_SCREENED = "DELIVERY_ALREADY_SCREENED_WITH_THIS_REVISION";
    /** Alleen een batch in {@code MUTATING} kan hervat worden. */
    public static final String CODE_BATCH_NOT_RESUMABLE = "BATCH_NOT_RESUMABLE";

    /** Fase 2 kent geen volledigheidscontract; {@code completeness_proven} is altijd false (A6). */
    public static final String COMPLETENESS_REASON = "PHASE2_NO_COMPLETENESS_CONTRACT";

    /** {@code import_batch.blocked_code} is varchar(60), {@code blocked_reason} varchar(500). */
    private static final int MAX_BLOCKED_CODE_LENGTH = 60;
    private static final int MAX_BLOCKED_REASON_LENGTH = 500;

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryScreeningService.class);

    /**
     * Wat de screening van deze batch heeft opgeleverd; tellers zijn {@code null} als ze onbekend
     * zijn — nooit stil {@code 0}. Een geblokkeerde batch die de delta nooit bereikt heeft, heeft
     * dus geen {@code newCount}, maar wél {@code contentMutationCount = 0}: dát is wél zeker.
     */
    public record ScreeningOutcome(long batchId, ImportBatchStatus status, Long rawRecordCount,
                                   Long validRecordCount, Long rejectedRecordCount, long stagedRowCount,
                                   Long duplicateIdentityCount, Long newCount, Long changedCount,
                                   Long unchangedCount, Long contentMutationCount,
                                   String blockedCode, String blockedReason) {
    }

    /** Alles wat buiten een transactie nodig is; bewust geen JPA-entiteiten (open-in-view staat uit). */
    private record Context(long batchId, long deliveryId, long deliveryFileId, long importLinkId,
                           long definitionRevisionId, Long taskRunId, String archiveReference,
                           long fileByteSize, String fileSha256, Long expectedRecordCount,
                           Long expectedByteSize, SourceStructureConfig config,
                           ScreeningBlockedException configFailure) {

        private MutationContext mutationContext() {
            return new MutationContext(batchId, deliveryId, importLinkId, definitionRevisionId, taskRunId,
                    deliveryFileId);
        }
    }

    /** Een vastgestelde reden om de volledige levering te blokkeren, met wat eromheen bekend is. */
    private record Blockage(String code, String reason, Long duplicateRowCount) {
    }

    /** Lopende stand van één screening; enkel binnen één {@link #screen(long)}-aanroep gebruikt. */
    private static final class Progress {
        private final List<StageRow> pendingRows = new ArrayList<>();
        private final List<IssueRow> pendingIssues = new ArrayList<>();
        private final Map<String, Long> issueCounts = new TreeMap<>();
        private long validCount;
        private long rejectedCount;
        private long stagedCount;
        private long recordedErrorCount;
        private Long rawRecordCount;
    }

    private final CsvRecordStreamer streamer = new CsvRecordStreamer();
    private final CandidateNormaliser normaliser = new CandidateNormaliser();

    private final DeliveryArchiveStore archive;
    private final SourceStructureConfigFactory configFactory;
    private final ImportBatchRepository batches;
    private final DeliveryFileRepository deliveryFiles;
    private final TaskRunRepository runs;
    private final CandidateStageDao stage;
    private final RowIssueDao rowIssues;
    private final MutationDao mutations;
    private final TransactionTemplate transaction;
    private final int stageBatchSize;
    private final int maxRecordedRowIssues;
    private final int maxLineLength;

    public DeliveryScreeningService(DeliveryArchiveStore archive, SourceStructureConfigFactory configFactory,
                                    ImportBatchRepository batches, DeliveryFileRepository deliveryFiles,
                                    TaskRunRepository runs, CandidateStageDao stage, RowIssueDao rowIssues,
                                    MutationDao mutations, PlatformTransactionManager transactionManager,
                                    @Value("${catalogimport.screening.stage-batch-size:2000}") int stageBatchSize,
                                    @Value("${catalogimport.screening.max-recorded-row-issues:1000}")
                                    int maxRecordedRowIssues,
                                    @Value("${catalogimport.screening.max-line-length:100000}") int maxLineLength) {
        this.archive = archive;
        this.configFactory = configFactory;
        this.batches = batches;
        this.deliveryFiles = deliveryFiles;
        this.runs = runs;
        this.stage = stage;
        this.rowIssues = rowIssues;
        this.mutations = mutations;
        this.transaction = new TransactionTemplate(transactionManager);
        this.stageBatchSize = stageBatchSize > 0 ? stageBatchSize : CandidateStageDao.DEFAULT_BATCH_SIZE;
        this.maxRecordedRowIssues = maxRecordedRowIssues;
        this.maxLineLength = maxLineLength > 0 ? maxLineLength : CsvRecordStreamer.DEFAULT_MAX_LINE_LENGTH;
    }

    /**
     * Screent de batch volledig. Herhaalde aanroep is veilig: enkel een batch in {@code RECEIVED}
     * wordt gescreend en een levering die onder deze revisie al een marker heeft, wordt geweigerd.
     *
     * @throws NotFoundException onbekende batch ({@code BATCH_NOT_FOUND})
     * @throws ConflictException {@code DELIVERY_ALREADY_SCREENED_WITH_THIS_REVISION},
     *                           {@code BATCH_NOT_SCREENABLE}, {@code DELIVERY_FILE_COUNT_UNSUPPORTED}
     * @throws RuntimeException  bij een technische fout tijdens het stagen; de batch staat dan al op
     *                           {@code FAILED} met opgeruimde staging. Breekt de mutatiegeneratie af,
     *                           dan blijft de batch op {@code MUTATING} staan en is
     *                           {@link #continueMutating(long)} de weg terug.
     */
    public ScreeningOutcome screen(long batchId) {
        Context context = transaction.execute(status -> start(batchId));
        Progress progress = new Progress();
        try {
            if (context.configFailure() != null) {
                throw context.configFailure();
            }
            verifyExpectedByteSize(context);
            ReadSummary summary = readAndStage(context, progress);
            progress.rawRecordCount = summary.rawRecordCount();
            flush(context, progress);
            verifyRecordCount(context, progress);
            transaction.executeWithoutResult(status -> toMutating(context, progress));
        } catch (ScreeningBlockedException blocked) {
            flushBeforeBlocking(context, progress);
            return transaction.execute(status ->
                    block(context, new Blockage(blocked.getCode(), blocked.getMessage(), null), progress));
        } catch (RuntimeException | Error technical) {
            fail(context, technical);
            throw technical;
        }
        return mutate(context);
    }

    /**
     * Hervat de mutatiefase van een batch die op {@code MUTATING} is blijven staan (onderbroken
     * verwerking of herstart van de applicatie). Reeds geschreven mutaties worden niet herhaald.
     *
     * @throws NotFoundException onbekende batch ({@code BATCH_NOT_FOUND})
     * @throws ConflictException {@code BATCH_NOT_RESUMABLE} als de batch niet in {@code MUTATING} staat
     */
    public ScreeningOutcome continueMutating(long batchId) {
        return mutate(transaction.execute(status -> resume(batchId)));
    }

    // --- Stap 1: overgang naar SCREENING -----------------------------------------------------

    private Context start(long batchId) {
        ImportBatch batch = batches.findById(batchId)
                .orElseThrow(() -> new NotFoundException("BATCH_NOT_FOUND", "Batch " + batchId + " not found"));
        Delivery delivery = batch.getDelivery();
        long revisionId = batch.getDefinitionRevision().getId();
        // De marker is het bewijs dat deze levering onder deze revisie al afgerond is; de unieke
        // idempotentiesleutel blijft daarnaast de harde garantie tegen dubbele mutaties.
        if (mutations.markerExists(delivery.getId(), revisionId)) {
            throw new ConflictException(CODE_ALREADY_SCREENED, "Delivery " + delivery.getId()
                    + " has already been screened under definition revision " + revisionId);
        }
        if (batch.getStatus() != ImportBatchStatus.RECEIVED) {
            throw new ConflictException("BATCH_NOT_SCREENABLE",
                    "Batch " + batchId + " is in status " + batch.getStatus() + " and cannot be screened again");
        }
        DeliveryFile file = singleFile(delivery);

        // De configuratie wordt hier gelezen omdat de revisie een lazy JPA-entiteit is. Een fout
        // blokkeert de batch en mag deze transactie dus niet terugdraaien: ze reist mee als resultaat.
        SourceStructureConfig config = null;
        ScreeningBlockedException configFailure = null;
        try {
            config = configFactory.from(batch.getDefinitionRevision());
        } catch (ScreeningBlockedException failure) {
            configFailure = failure;
        }

        batch.setStatus(ImportBatchStatus.SCREENING);
        batch.setStartedAt(Instant.now());
        batches.saveAndFlush(batch);

        return context(batch, delivery, file, config, configFailure);
    }

    private Context resume(long batchId) {
        ImportBatch batch = batches.findById(batchId)
                .orElseThrow(() -> new NotFoundException("BATCH_NOT_FOUND", "Batch " + batchId + " not found"));
        if (batch.getStatus() != ImportBatchStatus.MUTATING) {
            throw new ConflictException(CODE_BATCH_NOT_RESUMABLE, "Batch " + batchId + " is in status "
                    + batch.getStatus() + "; only a batch in MUTATING can be resumed");
        }
        Delivery delivery = batch.getDelivery();
        // De bronconfiguratie is hier niet meer nodig: het bestand is al gelezen en gestaged.
        return context(batch, delivery, singleFile(delivery), null, null);
    }

    private DeliveryFile singleFile(Delivery delivery) {
        List<DeliveryFile> files = deliveryFiles.findByDeliveryIdOrderBySequenceNumberAsc(delivery.getId());
        if (files.size() != 1) {
            throw new ConflictException("DELIVERY_FILE_COUNT_UNSUPPORTED",
                    "Delivery " + delivery.getId() + " has " + files.size()
                            + " files; phase 2 screens exactly one file per delivery");
        }
        return files.get(0);
    }

    private static Context context(ImportBatch batch, Delivery delivery, DeliveryFile file,
                                   SourceStructureConfig config, ScreeningBlockedException configFailure) {
        return new Context(batch.getId(), delivery.getId(), file.getId(), batch.getImportLink().getId(),
                batch.getDefinitionRevision().getId(),
                batch.getTaskRun() == null ? null : batch.getTaskRun().getId(), file.getArchiveReference(),
                file.getByteSize(), file.getContentHash(), delivery.getExpectedRecordCount(),
                delivery.getExpectedByteSize(), config, configFailure);
    }

    // --- Stap 2: volledigheidscontroles ------------------------------------------------------

    /** Design par. 6: het byte-aantal wordt vóór het parsen gecontroleerd. */
    private void verifyExpectedByteSize(Context context) {
        Long expected = context.expectedByteSize();
        if (expected != null && expected != context.fileByteSize()) {
            throw new ScreeningBlockedException(CODE_BYTE_SIZE_MISMATCH,
                    "Manifest declares " + expected + " bytes but the delivered file has "
                            + context.fileByteSize());
        }
    }

    private void verifyRecordCount(Context context, Progress progress) {
        long raw = progress.rawRecordCount == null ? 0L : progress.rawRecordCount;
        if (raw == 0) {
            throw new ScreeningBlockedException(CODE_SOURCE_NO_DATA_RECORDS,
                    "The delivery file contains no data records");
        }
        Long expected = context.expectedRecordCount();
        if (expected != null && expected != raw) {
            throw new ScreeningBlockedException(CODE_RECORD_COUNT_MISMATCH,
                    "Manifest declares " + expected + " records but the file contains " + raw);
        }
    }

    // --- Stap 3: lezen en stagen -------------------------------------------------------------

    private ReadSummary readAndStage(Context context, Progress progress) {
        try (InputStream archived = archive.open(context.archiveReference());
             CountingInputStream counting = new CountingInputStream(archived)) {
            ReadSummary summary = streamer.read(counting, context.config(), maxLineLength,
                    new StagingSink(context, progress));
            if (counting.count() != context.fileByteSize()) {
                // Het archief is onveranderlijk: een ander byte-aantal betekent een beschadigd of
                // afgekapt object. Dat is technisch, geen leveringsprobleem.
                throw new IllegalStateException("Archived object " + context.archiveReference() + " returned "
                        + counting.count() + " bytes but " + context.fileByteSize()
                        + " were registered at intake");
            }
            return summary;
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read archived object " + context.archiveReference(), failure);
        }
    }

    /** Vertaalt leesresultaten naar stagingrijen en problemen, en commit per microbatch. */
    private final class StagingSink implements CsvRecordStreamer.Sink {

        private final Context context;
        private final Progress progress;

        private StagingSink(Context context, Progress progress) {
            this.context = context;
            this.progress = progress;
        }

        @Override
        public void record(ParsedRow row) {
            CandidateNormaliser.Result result = normaliser.normalise(row, context.config());
            if (result instanceof NormalisedCandidate candidate) {
                progress.pendingRows.add(stageRow(context, candidate));
                progress.validCount++;
                if (progress.pendingRows.size() >= stageBatchSize) {
                    flush(context, progress);
                }
            } else if (result instanceof CandidateNormaliser.RowIssue rejected) {
                progress.rejectedCount++;
                addIssue(context, progress, rejected.rowNumber(), rejected.code(), rejected.fieldName(),
                        rejected.sourceValue(), rejected.message(), RowIssueSeverity.ERROR);
            }
        }

        @Override
        public void issue(LineIssue issue) {
            RowIssueSeverity severity = issue.warning() ? RowIssueSeverity.WARNING : RowIssueSeverity.ERROR;
            if (severity == RowIssueSeverity.ERROR) {
                progress.rejectedCount++;
            }
            addIssue(context, progress, issue.lineNumber(), issue.code(), issue.fieldName(),
                    issue.sourceValue(), issue.message(), severity);
        }
    }

    private static StageRow stageRow(Context context, NormalisedCandidate candidate) {
        return new StageRow(context.batchId(), candidate.rowNumber(), context.deliveryFileId(),
                candidate.supplier(), candidate.supplierGroup(), candidate.supplierReference(),
                candidate.discountCode(), candidate.discountState(), candidate.identityHash(),
                candidate.basePrice(), candidate.basePriceCurrency(), candidate.description(),
                candidate.articleFingerprint(), candidate.priceFingerprint(), candidate.combinedFingerprint(),
                candidate.mutationKeyPrefix(context.deliveryId(), context.definitionRevisionId()),
                Instant.now());
    }

    private void addIssue(Context context, Progress progress, long rowNumber, String code, String field,
                          String sourceValue, String message, RowIssueSeverity severity) {
        progress.issueCounts.merge(code, 1L, Long::sum);
        if (severity == RowIssueSeverity.ERROR) {
            progress.recordedErrorCount++;
            if (progress.recordedErrorCount > maxRecordedRowIssues) {
                throw new ScreeningBlockedException(CODE_TOO_MANY_ROW_ISSUES,
                        "More than " + maxRecordedRowIssues + " rows were rejected; recorded "
                                + maxRecordedRowIssues + ", issues per code: " + describe(progress.issueCounts));
            }
        }
        progress.pendingIssues.add(new IssueRow(context.batchId(), context.deliveryFileId(), rowNumber, code,
                field, severity, sourceValue, message, Instant.now()));
        if (progress.pendingIssues.size() >= stageBatchSize) {
            flush(context, progress);
        }
    }

    /**
     * Legt één microbatch vast: stagingrijen, regelproblemen en de voortgangsteller in dezelfde
     * transactie. Een crash tussen twee microbatches laat dus nooit rijen zonder hun teller achter.
     */
    private void flush(Context context, Progress progress) {
        if (progress.pendingRows.isEmpty() && progress.pendingIssues.isEmpty()) {
            return;
        }
        List<StageRow> rows = List.copyOf(progress.pendingRows);
        List<IssueRow> issues = List.copyOf(progress.pendingIssues);
        transaction.executeWithoutResult(status -> {
            stage.insertBatch(rows);
            rowIssues.insertBatch(issues);
            ImportBatch batch = batches.findById(context.batchId()).orElseThrow();
            batch.setStagedRowCount(batch.getStagedRowCount() + rows.size());
            batches.saveAndFlush(batch);
        });
        progress.stagedCount += rows.size();
        progress.pendingRows.clear();
        progress.pendingIssues.clear();
    }

    /**
     * Een geblokkeerde batch behoudt haar staging en haar problemen als bewijsmateriaal; de nog niet
     * weggeschreven microbatch wordt dus eerst alsnog vastgelegd. Faalt dat, dan is het alsnog een
     * technische fout.
     */
    private void flushBeforeBlocking(Context context, Progress progress) {
        try {
            flush(context, progress);
        } catch (RuntimeException technical) {
            fail(context, technical);
            throw technical;
        }
    }

    /** Einde van de stagingfase: tellers vast, batch naar MUTATING. De TaskRun blijft open. */
    private void toMutating(Context context, Progress progress) {
        ImportBatch batch = batches.findById(context.batchId()).orElseThrow();
        applyCounts(batch, progress);
        batch.getDelivery().setActualRecordCount(progress.rawRecordCount);
        batch.setStatus(ImportBatchStatus.MUTATING);
        batches.saveAndFlush(batch);
    }

    // --- Stap 4: identiteitscontrole, delta en mutatiegeneratie -------------------------------

    private ScreeningOutcome mutate(Context context) {
        Blockage blockage = detectIdentityProblems(context);
        if (blockage != null) {
            return transaction.execute(status -> block(context, blockage, null));
        }
        generateMutations(context);
        return transaction.execute(status -> complete(context));
    }

    /**
     * Design par. 9 stap D: read-only en herhaalbaar. Volgorde is bewust collisie eerst — twee regels
     * met dezelfde hash maar andere sleutelcomponenten zijn géén dubbele levering maar een kapotte
     * identiteit, en mogen niet als "duplicaat" gerapporteerd worden.
     *
     * @return de reden om te blokkeren, of {@code null} als de identiteiten bruikbaar zijn
     */
    private Blockage detectIdentityProblems(Context context) {
        OptionalLong inDelivery = stage.findIdentityHashCollisionRow(context.batchId());
        if (inDelivery.isPresent()) {
            return new Blockage(CODE_IDENTITY_HASH_COLLISION, "Line " + inDelivery.getAsLong()
                    + " shares its identity hash with another line in this delivery that has different "
                    + "identity components", null);
        }
        OptionalLong againstState = mutations.findSourceStateCollisionRow(context.batchId(),
                context.importLinkId());
        if (againstState.isPresent()) {
            return new Blockage(CODE_IDENTITY_HASH_COLLISION, "Line " + againstState.getAsLong()
                    + " shares its identity hash with a known offer that has different identity components", null);
        }
        long duplicates = stage.countDuplicateRows(context.batchId());
        if (duplicates > 0) {
            return new Blockage(CODE_DUPLICATE_IDENTITY_IN_DELIVERY, duplicates
                    + " lines repeat an offer identity that already occurs in this delivery; the delivery is "
                    + "blocked because a repeated identity is never resolved by keeping the last line",
                    duplicates);
        }
        return null;
    }

    /**
     * Design par. 9 stap E: per chunk één transactie die de classificatie, de mutaties én het
     * hervatpunt samen vastlegt. Valt de verwerking tussen twee chunks weg, dan staat het hervatpunt
     * altijd op een chunkgrens waarvan de mutaties gecommit zijn.
     */
    private void generateMutations(Context context) {
        MutationContext mutationContext = context.mutationContext();
        long from = transaction.execute(status ->
                batches.findById(context.batchId()).orElseThrow().getMutationProgressRowNumber());
        Long boundary;
        while ((boundary = mutations.nextChunkBoundary(context.batchId(), from)) != null) {
            long chunkFrom = from;
            long chunkTo = boundary;
            transaction.executeWithoutResult(status -> {
                mutations.classifyChunk(context.batchId(), context.importLinkId(), chunkFrom, chunkTo);
                mutations.insertContentMutations(mutationContext, chunkFrom, chunkTo, Instant.now());
                ImportBatch batch = batches.findById(context.batchId()).orElseThrow();
                batch.setMutationProgressRowNumber(chunkTo);
                batches.saveAndFlush(batch);
            });
            from = chunkTo;
        }
    }

    // --- Stap 5: afronden (design par. 9 stap F) ----------------------------------------------

    /**
     * Eén transactie: tellers, precies één {@code IMPORT_MARKER}, de overgang naar {@code SCREENED}
     * en het afsluiten van de {@code TaskRun}. Zo bestaat er nooit een afgeronde screening zonder
     * marker of een marker zonder eindstatus.
     */
    private ScreeningOutcome complete(Context context) {
        ImportBatch batch = batches.findById(context.batchId()).orElseThrow();
        Map<String, Long> classified = stage.countByClassification(context.batchId());
        batch.setNewCount(classified.getOrDefault(CandidateClassification.NEW.name(), 0L));
        batch.setChangedCount(classified.getOrDefault(CandidateClassification.CHANGED.name(), 0L));
        batch.setUnchangedCount(classified.getOrDefault(CandidateClassification.UNCHANGED.name(), 0L));
        batch.setDuplicateIdentityCount(
                classified.getOrDefault(CandidateClassification.DUPLICATE_IN_DELIVERY.name(), 0L));
        batch.setContentMutationCount(mutations.countContentMutations(context.batchId()));
        batch.setStatus(ImportBatchStatus.SCREENED);
        batch.setFinishedAt(Instant.now());
        batches.saveAndFlush(batch);
        writeMarker(context, "outcome=SCREENED");
        finishTaskRun(context, TaskRunStatus.COMPLETED);
        return outcome(batch);
    }

    /**
     * Blokkeert de volledige levering: geen enkele inhoudelijke mutatie, wél één marker met
     * {@code outcome=BLOCKED}, in dezelfde transactie als de eindtransitie. Staging en problemen
     * blijven bewaard als bewijsmateriaal; enkel een technische fout ruimt ze op.
     *
     * @param progress de stand van de stagingfase, of {@code null} wanneer die al vastligt op de batch
     */
    private ScreeningOutcome block(Context context, Blockage blockage, Progress progress) {
        ImportBatch batch = batches.findById(context.batchId()).orElseThrow();
        if (progress != null) {
            applyCounts(batch, progress);
            if (progress.rawRecordCount != null) {
                batch.getDelivery().setActualRecordCount(progress.rawRecordCount);
            }
        }
        if (blockage.duplicateRowCount() != null) {
            recordDuplicateIssues(context, blockage.duplicateRowCount());
            batch.setDuplicateIdentityCount(blockage.duplicateRowCount());
        }
        // Nul is hier geen aanname maar een vaststelling: een geblokkeerde levering genereert niets.
        batch.setContentMutationCount(mutations.countContentMutations(context.batchId()));
        batch.setBlockedCode(truncate(blockage.code(), MAX_BLOCKED_CODE_LENGTH));
        batch.setBlockedReason(truncate(blockage.code() + ": " + blockage.reason(), MAX_BLOCKED_REASON_LENGTH));
        batch.setStatus(ImportBatchStatus.BLOCKED);
        batch.setFinishedAt(Instant.now());
        batches.saveAndFlush(batch);
        writeMarker(context, "outcome=BLOCKED;blockedCode=" + blockage.code());
        finishTaskRun(context, TaskRunStatus.COMPLETED);
        LOG.info("Batch {} blocked: {} ({})", context.batchId(), blockage.code(), blockage.reason());
        return outcome(batch);
    }

    /**
     * Elke regel die bij een dubbele identiteit betrokken is, krijgt haar eigen probleem met het
     * regelnummer van de eerste voorkomst — ook de eerste regel zelf, want zonder de rest is ook zij
     * niet te vertrouwen. De issue-cap geldt onverkort: boven de cap worden er geen problemen meer
     * bewaard, maar het volledige aantal staat in de blokkeerreden en in
     * {@code duplicate_identity_count}.
     */
    private void recordDuplicateIssues(Context context, long duplicateRowCount) {
        stage.classifyDuplicates(context.batchId());
        long alreadyRecorded = rowIssues.countBySeverity(context.batchId(), RowIssueSeverity.ERROR);
        int budget = (int) Math.max(0, Math.min(maxRecordedRowIssues - alreadyRecorded, duplicateRowCount));
        List<DuplicateRow> duplicates = stage.findDuplicateRows(context.batchId(), budget);
        Instant now = Instant.now();
        List<IssueRow> issues = duplicates.stream()
                .map(duplicate -> new IssueRow(context.batchId(), context.deliveryFileId(),
                        duplicate.rowNumber(), CODE_DUPLICATE_IDENTITY_IN_DELIVERY, null,
                        RowIssueSeverity.ERROR, null,
                        "Offer identity occurs more than once in this delivery; first occurrence on line "
                                + duplicate.firstRowNumber(), now))
                .toList();
        rowIssues.insertBatch(issues);
    }

    /**
     * Design par. 4: exact één marker per afgeronde screening. De samenvatting legt vast dat de
     * volledigheid in fase 2 niet bewezen is en welk bestand gescreend werd, zodat een latere
     * reconciliatie niet van de (wijzigbare) leveringsrijen hoeft af te hangen.
     */
    private void writeMarker(Context context, String outcome) {
        mutations.insertMarker(context.mutationContext(),
                outcome + ";completenessProven=false;completenessReason=" + COMPLETENESS_REASON
                        + ";fileSha256=" + context.fileSha256(), Instant.now());
    }

    /**
     * Technische fout: batch en run op FAILED, staging en problemen van deze poging weg, geen marker.
     * Faalt ook dat nog, dan wordt die tweede fout gelogd en niet over de oorspronkelijke heen gegooid.
     */
    private void fail(Context context, Throwable cause) {
        try {
            transaction.executeWithoutResult(status -> {
                stage.deleteByBatchId(context.batchId());
                rowIssues.deleteByBatchId(context.batchId());
                ImportBatch batch = batches.findById(context.batchId()).orElseThrow();
                batch.setStagedRowCount(0);
                batch.setBlockedCode(CODE_SCREENING_FAILED);
                batch.setBlockedReason(truncate(CODE_SCREENING_FAILED + ": " + cause, MAX_BLOCKED_REASON_LENGTH));
                batch.setStatus(ImportBatchStatus.FAILED);
                batch.setFinishedAt(Instant.now());
                batches.saveAndFlush(batch);
                finishTaskRun(context, TaskRunStatus.FAILED);
            });
        } catch (RuntimeException secondary) {
            LOG.error("Cannot mark batch {} as FAILED after {}", context.batchId(), cause, secondary);
        }
    }

    /** Alleen een volledig gelezen bestand levert eindtellers op; anders blijven ze onbekend (null). */
    private static void applyCounts(ImportBatch batch, Progress progress) {
        batch.setStagedRowCount(progress.stagedCount);
        if (progress.rawRecordCount != null) {
            batch.setRawRecordCount(progress.rawRecordCount);
            batch.setValidRecordCount(progress.validCount);
            batch.setRejectedRecordCount(progress.rejectedCount);
        }
    }

    private void finishTaskRun(Context context, TaskRunStatus status) {
        if (context.taskRunId() == null) {
            return;
        }
        TaskRun run = runs.findById(context.taskRunId()).orElse(null);
        if (run == null) {
            return;
        }
        run.setStatus(status);
        run.setFinishedAt(Instant.now());
        runs.saveAndFlush(run);
    }

    private static ScreeningOutcome outcome(ImportBatch batch) {
        return new ScreeningOutcome(batch.getId(), batch.getStatus(), batch.getRawRecordCount(),
                batch.getValidRecordCount(), batch.getRejectedRecordCount(), batch.getStagedRowCount(),
                batch.getDuplicateIdentityCount(), batch.getNewCount(), batch.getChangedCount(),
                batch.getUnchangedCount(), batch.getContentMutationCount(), batch.getBlockedCode(),
                batch.getBlockedReason());
    }

    private static String describe(Map<String, Long> issueCounts) {
        return issueCounts.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(", "));
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /** Telt de bytes die werkelijk uit het archief gelezen worden, zonder ze te bufferen. */
    private static final class CountingInputStream extends FilterInputStream {

        private long count;

        private CountingInputStream(InputStream delegate) {
            super(delegate);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                count++;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                count += read;
            }
            return read;
        }

        @Override
        public long skip(long requested) throws IOException {
            long skipped = super.skip(requested);
            count += skipped;
            return skipped;
        }

        private long count() {
            return count;
        }
    }
}
