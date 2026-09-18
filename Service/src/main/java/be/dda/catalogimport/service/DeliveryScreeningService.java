package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.CandidateStageDao;
import be.dda.catalogimport.dao.CandidateStageDao.StageRow;
import be.dda.catalogimport.dao.DeliveryFileRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.RowIssueDao;
import be.dda.catalogimport.dao.RowIssueDao.IssueRow;
import be.dda.catalogimport.dao.TaskRunRepository;
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
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Screent één {@link ImportBatch}: leest het gearchiveerde bronbestand, normaliseert elke regel en
 * stageert de kandidaten (design par. 9, stap C).
 * <p>
 * <b>Bewuste tussenstand (bouwstap 2c).</b> Deze stap stopt na het stagen. Een geslaagde batch
 * eindigt op {@link ImportBatchStatus#MUTATING} — <b>niet</b> op {@code SCREENED} — en houdt haar
 * {@code TaskRun} bewust op {@code RUNNING}: duplicaatdetectie, delta, mutatielijst,
 * {@code IMPORT_MARKER} en de eindtransitie horen bij bouwstap 2d, die op deze status verder bouwt.
 * Alleen een batch die terminaal eindigt wordt in 2c al afgesloten:
 * <ul>
 *   <li><b>BLOCKED</b> (contract-/structuurfout) sluit de {@code TaskRun} af als
 *       {@link TaskRunStatus#COMPLETED}: de uitvoering zelf is normaal verlopen en heeft een geldig,
 *       verklaarbaar resultaat opgeleverd; het oordeel over de levering staat in
 *       {@code import_batch.status} / {@code blocked_code}. Design par. 9 stap F noemt dezelfde
 *       eindtransitie voor SCREENED én BLOCKED met {@code TaskRun COMPLETED}.</li>
 *   <li><b>FAILED</b> (technische fout) sluit de {@code TaskRun} af als
 *       {@link TaskRunStatus#FAILED}, verwijdert de staging en de problemen van die poging en
 *       schrijft geen marker; een nieuwe poging start later met {@code attempt_no + 1}.</li>
 * </ul>
 * <b>Transactiegrenzen.</b> Deze orchestrator is bewust <b>niet</b> {@code @Transactional}: één
 * transactie over een miljoen regels zou het transactielog en het geheugen laten vollopen en na een
 * crash alles verliezen. In plaats daarvan: één korte transactie voor de overgang naar
 * {@code SCREENING}, dan per microbatch
 * ({@code catalogimport.screening.stage-batch-size}) één transactie die de stagingrijen, de
 * regelproblemen én {@code staged_row_count} samen vastlegt, en één afrondende transactie. Er wordt
 * nooit in dezelfde transactie via JPA teruggelezen wat via JdbcTemplate geschreven is.
 * <p>
 * <b>Businessregels die hier hard zijn.</b> Een ongeldige regel verwerpt enkel die regel (aanname
 * A4) en levert nooit een prijs 0 op; een contractfout (leeg bestand, header-only, ontbrekend
 * headerveld, kolomaantal, aantalsmismatch, te veel rijfouten) blokkeert de volledige levering;
 * een verwacht record- of byte-aantal dat niet klopt blokkeert eveneens — het byte-aantal
 * <i>vóór</i> het parsen, zodat een afgekapt bestand nooit half verwerkt wordt.
 * <p>
 * De screening wordt in 2c nog niet automatisch vanuit de upload gestart; {@link #screen(long)} is
 * de expliciete ingang. Het koppelen aan {@code POST .../deliveries} gebeurt in 2d.
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
    /** Technische fout tijdens de screening; batch en run eindigen op FAILED. */
    public static final String CODE_SCREENING_FAILED = "SCREENING_FAILED";

    /** {@code import_batch.blocked_code} is varchar(60), {@code blocked_reason} varchar(500). */
    private static final int MAX_BLOCKED_CODE_LENGTH = 60;
    private static final int MAX_BLOCKED_REASON_LENGTH = 500;

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryScreeningService.class);

    /** Wat de screening van deze batch heeft opgeleverd; tellers zijn {@code null} als ze onbekend zijn. */
    public record ScreeningOutcome(long batchId, ImportBatchStatus status, Long rawRecordCount,
                                   Long validRecordCount, Long rejectedRecordCount, long stagedRowCount,
                                   String blockedCode, String blockedReason) {
    }

    /** Alles wat buiten een transactie nodig is; bewust geen JPA-entiteiten (open-in-view staat uit). */
    private record Context(long batchId, long deliveryId, long deliveryFileId, long definitionRevisionId,
                           Long taskRunId, String archiveReference, long fileByteSize, Long expectedRecordCount,
                           Long expectedByteSize, SourceStructureConfig config,
                           ScreeningBlockedException configFailure) {
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
    private final TransactionTemplate transaction;
    private final int stageBatchSize;
    private final int maxRecordedRowIssues;
    private final int maxLineLength;

    public DeliveryScreeningService(DeliveryArchiveStore archive, SourceStructureConfigFactory configFactory,
                                    ImportBatchRepository batches, DeliveryFileRepository deliveryFiles,
                                    TaskRunRepository runs, CandidateStageDao stage, RowIssueDao rowIssues,
                                    PlatformTransactionManager transactionManager,
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
        this.transaction = new TransactionTemplate(transactionManager);
        this.stageBatchSize = stageBatchSize > 0 ? stageBatchSize : CandidateStageDao.DEFAULT_BATCH_SIZE;
        this.maxRecordedRowIssues = maxRecordedRowIssues;
        this.maxLineLength = maxLineLength > 0 ? maxLineLength : CsvRecordStreamer.DEFAULT_MAX_LINE_LENGTH;
    }

    /**
     * Screent de batch tot en met de staging. Herhaalde aanroep is veilig: enkel een batch in
     * {@code RECEIVED} wordt gescreend, elke andere status is een conflict. Zo kan dezelfde levering
     * nooit twee keer gestaged worden.
     *
     * @throws NotFoundException onbekende batch ({@code BATCH_NOT_FOUND})
     * @throws ConflictException {@code BATCH_NOT_SCREENABLE}, {@code DELIVERY_FILE_COUNT_UNSUPPORTED}
     * @throws RuntimeException  bij een technische fout; de batch staat dan al op {@code FAILED} met
     *                           opgeruimde staging en de oorspronkelijke fout wordt doorgegeven
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
            return transaction.execute(status -> complete(context, progress));
        } catch (ScreeningBlockedException blocked) {
            flushBeforeBlocking(context, progress);
            return transaction.execute(status -> block(context, blocked, progress));
        } catch (RuntimeException | Error technical) {
            fail(context, technical);
            throw technical;
        }
    }

    // --- Stap 1: overgang naar SCREENING -----------------------------------------------------

    private Context start(long batchId) {
        ImportBatch batch = batches.findById(batchId)
                .orElseThrow(() -> new NotFoundException("BATCH_NOT_FOUND", "Batch " + batchId + " not found"));
        if (batch.getStatus() != ImportBatchStatus.RECEIVED) {
            throw new ConflictException("BATCH_NOT_SCREENABLE",
                    "Batch " + batchId + " is in status " + batch.getStatus() + " and cannot be screened again");
        }
        Delivery delivery = batch.getDelivery();
        List<DeliveryFile> files = deliveryFiles.findByDeliveryIdOrderBySequenceNumberAsc(delivery.getId());
        if (files.size() != 1) {
            throw new ConflictException("DELIVERY_FILE_COUNT_UNSUPPORTED",
                    "Delivery " + delivery.getId() + " has " + files.size()
                            + " files; phase 2 screens exactly one file per delivery");
        }
        DeliveryFile file = files.get(0);

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

        return new Context(batchId, delivery.getId(), file.getId(), batch.getDefinitionRevision().getId(),
                batch.getTaskRun() == null ? null : batch.getTaskRun().getId(), file.getArchiveReference(),
                file.getByteSize(), delivery.getExpectedRecordCount(), delivery.getExpectedByteSize(),
                config, configFailure);
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

    // --- Stap 4: afronden --------------------------------------------------------------------

    private ScreeningOutcome complete(Context context, Progress progress) {
        ImportBatch batch = batches.findById(context.batchId()).orElseThrow();
        applyCounts(batch, progress);
        batch.getDelivery().setActualRecordCount(progress.rawRecordCount);
        batch.setStatus(ImportBatchStatus.MUTATING);
        batches.saveAndFlush(batch);
        // De TaskRun blijft bewust RUNNING: bouwstap 2d rondt de screening af.
        return outcome(batch, progress);
    }

    private ScreeningOutcome block(Context context, ScreeningBlockedException blocked, Progress progress) {
        ImportBatch batch = batches.findById(context.batchId()).orElseThrow();
        applyCounts(batch, progress);
        if (progress.rawRecordCount != null) {
            batch.getDelivery().setActualRecordCount(progress.rawRecordCount);
        }
        batch.setBlockedCode(truncate(blocked.getCode(), MAX_BLOCKED_CODE_LENGTH));
        batch.setBlockedReason(truncate(blocked.getCode() + ": " + blocked.getMessage(),
                MAX_BLOCKED_REASON_LENGTH));
        batch.setStatus(ImportBatchStatus.BLOCKED);
        batch.setFinishedAt(Instant.now());
        batches.saveAndFlush(batch);
        finishTaskRun(context, TaskRunStatus.COMPLETED);
        LOG.info("Batch {} blocked: {} ({})", context.batchId(), blocked.getCode(), blocked.getMessage());
        return outcome(batch, progress);
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

    private static ScreeningOutcome outcome(ImportBatch batch, Progress progress) {
        return new ScreeningOutcome(batch.getId(), batch.getStatus(), batch.getRawRecordCount(),
                batch.getValidRecordCount(), batch.getRejectedRecordCount(), progress.stagedCount,
                batch.getBlockedCode(), batch.getBlockedReason());
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
