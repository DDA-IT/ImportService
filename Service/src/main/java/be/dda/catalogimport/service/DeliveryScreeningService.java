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
import be.dda.catalogimport.domain.ValidationResult;
import be.dda.catalogimport.service.support.CandidateNormaliser;
import be.dda.catalogimport.service.support.CandidateNormaliser.NormalisedCandidate;
import be.dda.catalogimport.service.support.CsvRecordStreamer;
import be.dda.catalogimport.service.support.CsvRecordStreamer.LineIssue;
import be.dda.catalogimport.service.support.CsvRecordStreamer.ParsedRow;
import be.dda.catalogimport.service.support.CsvRecordStreamer.ReadSummary;
import be.dda.catalogimport.service.support.ImportIssueCatalog;
import be.dda.catalogimport.service.support.ImportMappingConfig;
import be.dda.catalogimport.service.support.ImportMappingConfigFactory;
import be.dda.catalogimport.service.support.RecordFilterEvaluator;
import be.dda.catalogimport.service.support.ScreeningBlockedException;
import be.dda.catalogimport.service.support.SourceStructureConfig;
import be.dda.catalogimport.service.support.SourceStructureConfigFactory;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
 *       kolomaantal, aantalsmismatch, dubbele identiteit, hashcollisie) blokkeert de <b>volledige</b>
 *       levering: {@code BLOCKED}, nul inhoudelijke mutaties, wel één marker met
 *       {@code outcome=BLOCKED}. Elke blokkade laat sinds fase 3 ook één issuerij achter op niveau
 *       {@code STRUCTURE} of {@code DELIVERY} — de blokkeerreden op de batch blijft daarnaast
 *       ongewijzigd bestaan.</li>
 *   <li><b>Veel identieke regelfouten blokkeren niet.</b> Per foutcode worden hoogstens
 *       {@code catalogimport.screening.max-sample-rows-per-code} voorbeeldrijen bewaard (de laagste
 *       regelnummers, want er wordt in leesvolgorde gestreamd); de volledige aantallen per code
 *       blijven bewaard in één {@code ROW_ISSUE_RECORDING_CAPPED}-melding. Dat vervangt de
 *       fase 2-blokkade {@code TOO_MANY_ROW_ISSUES}, die een technische logginglimiet was en geen
 *       businessoordeel (ontwerp fase 3, afwijking C).</li>
 *   <li><b>Recordfilters bepalen de importscope</b> (fase 3, R-FLT-01..R-FLT-04). Ze draaien
 *       onmiddellijk na het parsen en vóór identiteit, prijs en referenties; een record dat buiten de
 *       scope valt krijgt geen enkele verdere controle en telt in {@code filtered_out_count} — dat is
 *       geen fout. Een record dat al vóór het filter onleesbaar was, kan niet meer aan de scope
 *       toegewezen worden en telt in {@code error_before_filter_count}, niet in
 *       {@code rejected_record_count}. Het bronbestand wordt altijd <b>volledig</b> gelezen: header,
 *       structuur, parsefouten en het ruwe recordaantal gelden over 100% van de levering. Zonder
 *       geconfigureerde filters staan beide tellers op 0 en blijft het gedrag exact dat van fase 2.</li>
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
    public static final String CODE_BYTE_SIZE_MISMATCH = ImportIssueCatalog.BYTE_SIZE_MISMATCH;
    /** Het verwachte recordaantal uit het manifest klopt niet met het gelezen bestand. */
    public static final String CODE_RECORD_COUNT_MISMATCH = ImportIssueCatalog.RECORD_COUNT_MISMATCH;
    /** Het bestand bevat een header maar geen enkele datalijn (aanname A3). */
    public static final String CODE_SOURCE_NO_DATA_RECORDS = ImportIssueCatalog.SOURCE_NO_DATA_RECORDS;
    /** Dezelfde aanbiedingsidentiteit komt meermaals voor in één levering; nooit "laatste wint". */
    public static final String CODE_DUPLICATE_IDENTITY_IN_DELIVERY =
            ImportIssueCatalog.DUPLICATE_IDENTITY_IN_DELIVERY;
    /** Zelfde identiteitshash, andere sleutelcomponenten: de identiteit is niet betrouwbaar. */
    public static final String CODE_IDENTITY_HASH_COLLISION = ImportIssueCatalog.IDENTITY_HASH_COLLISION;
    /** Technische fout tijdens de screening; batch en run eindigen op FAILED. */
    public static final String CODE_SCREENING_FAILED = ImportIssueCatalog.SCREENING_FAILED;
    /**
     * Er zijn meer voorvallen van een foutcode dan er voorbeeldrijen bewaard worden. Informatief:
     * dit blokkeert de levering <b>niet</b> (ontwerp fase 3, afwijking C).
     */
    public static final String CODE_ROW_ISSUE_RECORDING_CAPPED =
            ImportIssueCatalog.ROW_ISSUE_RECORDING_CAPPED;
    /** Deze levering is onder deze revisie al gescreend; een tweede screening is een conflict. */
    public static final String CODE_ALREADY_SCREENED = "DELIVERY_ALREADY_SCREENED_WITH_THIS_REVISION";
    /** Alleen een batch in {@code MUTATING} kan hervat worden. */
    public static final String CODE_BATCH_NOT_RESUMABLE = "BATCH_NOT_RESUMABLE";

    /** Standaardaantal bewaarde voorbeeldrijen per foutcode (ontwerp fase 3, R-ISS-03). */
    public static final int DEFAULT_MAX_SAMPLE_ROWS_PER_CODE = 200;

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
    public record ScreeningOutcome(long batchId, ImportBatchStatus status, ValidationResult validationResult,
                                   Long rawRecordCount, Long validRecordCount, Long rejectedRecordCount,
                                   Long filteredOutCount, Long errorBeforeFilterCount,
                                   long stagedRowCount, Long duplicateIdentityCount, Long newCount,
                                   Long changedCount, Long unchangedCount, Long contentMutationCount,
                                   String blockedCode, String blockedReason) {
    }

    /** Alles wat buiten een transactie nodig is; bewust geen JPA-entiteiten (open-in-view staat uit). */
    private record Context(long batchId, long deliveryId, long deliveryFileId, long importLinkId,
                           long definitionRevisionId, Long taskRunId, String archiveReference,
                           long fileByteSize, String fileSha256, Long expectedRecordCount,
                           Long expectedByteSize, SourceStructureConfig config,
                           ImportMappingConfig mappingConfig, ScreeningBlockedException configFailure) {

        private MutationContext mutationContext() {
            return new MutationContext(batchId, deliveryId, importLinkId, definitionRevisionId, taskRunId,
                    deliveryFileId);
        }

        /** Zonder geconfigureerde recordfilters blijft het gedrag exact dat van fase 2. */
        private boolean hasRecordFilters() {
            return mappingConfig != null && mappingConfig.hasFilters();
        }
    }

    /**
     * Een vastgestelde reden om de volledige levering te blokkeren, met wat eromheen bekend is.
     * {@code rowNumber} is gevuld wanneer de blokkade aan één regel op te hangen is (hashcollisie);
     * bij een leverings- of structuurfout blijft die bewust {@code null} in plaats van 0.
     */
    private record Blockage(String code, String reason, String fieldName, String sourceValue,
                            String expectedValue, Long rowNumber, Long duplicateRowCount) {

        private static Blockage of(ScreeningBlockedException blocked) {
            return new Blockage(blocked.getCode(), blocked.getMessage(), blocked.getFieldName(),
                    blocked.getSourceValue(), blocked.getExpectedValue(), null, null);
        }

        private static Blockage onRow(String code, long rowNumber, String reason) {
            return new Blockage(code, reason, null, null, null, rowNumber, null);
        }

        private static Blockage duplicates(String code, long duplicateRowCount, String reason) {
            return new Blockage(code, reason, null, null, null, null, duplicateRowCount);
        }
    }

    /** Lopende stand van één screening; enkel binnen één {@link #screen(long)}-aanroep gebruikt. */
    private static final class Progress {
        private final List<StageRow> pendingRows = new ArrayList<>();
        private final List<IssueRow> pendingIssues = new ArrayList<>();
        /** Volledige aantallen per foutcode — ook boven de voorbeeldcap (R-ISS-03). */
        private final Map<String, Long> issueCounts = new TreeMap<>();
        /** Aantal reeds bewaarde voorbeeldrijen per foutcode. */
        private final Map<String, Integer> recordedSamples = new HashMap<>();
        private long validCount;
        private long rejectedCount;
        private long filteredOutCount;
        private long errorBeforeFilterCount;
        private long stagedCount;
        private boolean sampleCapReached;
        private boolean capNoticeRecorded;
        private Long rawRecordCount;
    }

    private final CsvRecordStreamer streamer = new CsvRecordStreamer();
    private final CandidateNormaliser normaliser = new CandidateNormaliser();

    private final DeliveryArchiveStore archive;
    private final SourceStructureConfigFactory configFactory;
    private final ImportMappingConfigFactory mappingConfigFactory;
    private final ImportBatchRepository batches;
    private final DeliveryFileRepository deliveryFiles;
    private final TaskRunRepository runs;
    private final CandidateStageDao stage;
    private final RowIssueDao rowIssues;
    private final MutationDao mutations;
    private final TransactionTemplate transaction;
    private final int stageBatchSize;
    private final int maxSampleRowsPerCode;
    private final int maxLineLength;

    public DeliveryScreeningService(DeliveryArchiveStore archive, SourceStructureConfigFactory configFactory,
                                    ImportMappingConfigFactory mappingConfigFactory,
                                    ImportBatchRepository batches, DeliveryFileRepository deliveryFiles,
                                    TaskRunRepository runs, CandidateStageDao stage, RowIssueDao rowIssues,
                                    MutationDao mutations, PlatformTransactionManager transactionManager,
                                    @Value("${catalogimport.screening.stage-batch-size:2000}") int stageBatchSize,
                                    @Value("${catalogimport.screening.max-sample-rows-per-code:"
                                            + DEFAULT_MAX_SAMPLE_ROWS_PER_CODE + "}")
                                    int maxSampleRowsPerCode,
                                    @Value("${catalogimport.screening.max-line-length:100000}") int maxLineLength) {
        this.archive = archive;
        this.configFactory = configFactory;
        this.mappingConfigFactory = mappingConfigFactory;
        this.batches = batches;
        this.deliveryFiles = deliveryFiles;
        this.runs = runs;
        this.stage = stage;
        this.rowIssues = rowIssues;
        this.mutations = mutations;
        this.transaction = new TransactionTemplate(transactionManager);
        this.stageBatchSize = stageBatchSize > 0 ? stageBatchSize : CandidateStageDao.DEFAULT_BATCH_SIZE;
        this.maxSampleRowsPerCode = maxSampleRowsPerCode > 0
                ? maxSampleRowsPerCode : DEFAULT_MAX_SAMPLE_ROWS_PER_CODE;
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
            recordSampleCapNotice(context, progress);
            flush(context, progress);
            verifyRecordCount(context, progress);
            transaction.executeWithoutResult(status -> toMutating(context, progress));
        } catch (ScreeningBlockedException blocked) {
            recordSampleCapNotice(context, progress);
            flushBeforeBlocking(context, progress);
            return transaction.execute(status -> block(context, Blockage.of(blocked), progress));
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
        // Dit is stap B' uit ontwerp fase 3 par. 3.1: structuur, veldmapping, recordfilters en
        // drempels worden één keer per batch geladen en gevalideerd, vóór er één byte gelezen is.
        SourceStructureConfig config = null;
        ImportMappingConfig mappingConfig = null;
        ScreeningBlockedException configFailure = null;
        try {
            config = configFactory.from(batch.getDefinitionRevision());
            mappingConfig = mappingConfigFactory.from(batch.getDefinitionRevision(), config);
        } catch (ScreeningBlockedException failure) {
            configFailure = failure;
        }

        batch.setStatus(ImportBatchStatus.SCREENING);
        batch.setStartedAt(Instant.now());
        batches.saveAndFlush(batch);

        return context(batch, delivery, file, config, mappingConfig, configFailure);
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
        return context(batch, delivery, singleFile(delivery), null, null, null);
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
                                   SourceStructureConfig config, ImportMappingConfig mappingConfig,
                                   ScreeningBlockedException configFailure) {
        return new Context(batch.getId(), delivery.getId(), file.getId(), batch.getImportLink().getId(),
                batch.getDefinitionRevision().getId(),
                batch.getTaskRun() == null ? null : batch.getTaskRun().getId(), file.getArchiveReference(),
                file.getByteSize(), file.getContentHash(), delivery.getExpectedRecordCount(),
                delivery.getExpectedByteSize(), config, mappingConfig, configFailure);
    }

    // --- Stap 2: volledigheidscontroles ------------------------------------------------------

    /** Design par. 6: het byte-aantal wordt vóór het parsen gecontroleerd. */
    private void verifyExpectedByteSize(Context context) {
        Long expected = context.expectedByteSize();
        if (expected != null && expected != context.fileByteSize()) {
            throw new ScreeningBlockedException(CODE_BYTE_SIZE_MISMATCH, "byteSize",
                    String.valueOf(context.fileByteSize()), String.valueOf(expected),
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
            throw new ScreeningBlockedException(CODE_RECORD_COUNT_MISMATCH, "recordCount",
                    String.valueOf(raw), String.valueOf(expected),
                    "Manifest declares " + expected + " records but the file contains " + raw);
        }
    }

    // --- Stap 3: lezen en stagen -------------------------------------------------------------

    private ReadSummary readAndStage(Context context, Progress progress) {
        ImportMappingConfig mappingConfig = context.mappingConfig();
        RecordFilterEvaluator filters = new RecordFilterEvaluator(mappingConfig.filters());
        try (InputStream archived = archive.open(context.archiveReference());
             CountingInputStream counting = new CountingInputStream(archived)) {
            ReadSummary summary = streamer.read(counting, context.config(),
                    mappingConfig.headerExpectations(), maxLineLength,
                    new StagingSink(context, progress, filters));
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

    /**
     * Vertaalt leesresultaten naar stagingrijen en problemen, en commit per microbatch.
     * <p>
     * <b>Volgorde (R-FLT-02).</b> Het recordfilter draait onmiddellijk na het parsen en vóór
     * identiteit, prijs en referenties. Een record dat buiten de importscope valt, krijgt dus géén
     * enkele verdere controle: het kan nooit een identiteits-, prijs- of referentieprobleem
     * veroorzaken en telt in {@code filtered_out_count} in plaats van in
     * {@code rejected_record_count}.
     */
    private final class StagingSink implements CsvRecordStreamer.Sink {

        private final Context context;
        private final Progress progress;
        private final RecordFilterEvaluator filters;

        private StagingSink(Context context, Progress progress, RecordFilterEvaluator filters) {
            this.context = context;
            this.progress = progress;
            this.filters = filters;
        }

        @Override
        public void record(ParsedRow row) {
            RecordFilterEvaluator.Decision decision = filters.evaluate(row);
            switch (decision.kind()) {
                case FILTERED_OUT -> {
                    // Geen probleemrij: buiten de scope vallen is geen fout. Het aantal blijft wel
                    // zichtbaar, zodat de reconciliatie van de tellers klopt.
                    progress.filteredOutCount++;
                    return;
                }
                case REJECTED -> {
                    addIssue(context, progress, row.lineNumber(),
                            RecordFilterEvaluator.CODE_FILTER_RECORD_REJECTED, decision.fieldName(),
                            decision.sourceValue(), decision.message(), false);
                    return;
                }
                case IN_SCOPE -> {
                    // Verder met de gewone recordcontroles.
                }
            }
            CandidateNormaliser.Result result = normaliser.normalise(row, context.config());
            if (result instanceof NormalisedCandidate candidate) {
                progress.pendingRows.add(stageRow(context, candidate));
                progress.validCount++;
                if (progress.pendingRows.size() >= stageBatchSize) {
                    flush(context, progress);
                }
            } else if (result instanceof CandidateNormaliser.RowIssue rejected) {
                addIssue(context, progress, rejected.rowNumber(), rejected.code(), rejected.fieldName(),
                        rejected.sourceValue(), rejected.message(), false);
            }
        }

        /**
         * Een probleem dat bij het lezen zelf ontstaat (kolomaantal, niet-gesloten aanhalingsteken, te
         * lange regel) of een waarschuwing over de header. Zo'n regel is niet parseerbaar en kan dus
         * <b>niet</b> aan de importscope toegewezen worden: met geconfigureerde filters telt ze in
         * {@code error_before_filter_count} en niet in {@code rejected_record_count}.
         */
        @Override
        public void issue(LineIssue issue) {
            addIssue(context, progress, issue.lineNumber(), issue.code(), issue.fieldName(),
                    issue.sourceValue(), issue.message(), true);
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

    /**
     * Legt één vastgesteld probleem vast. De ernst komt uit {@link ImportIssueCatalog} en nooit uit
     * de aanroeper: zo kan geen enkel pad een eigen oordeel wegschrijven (R-ISS-02/R-ISS-06).
     * <p>
     * <b>Alle voorvallen tellen, niet alle voorvallen worden bewaard.</b> De teller per code loopt
     * altijd door — {@code rejected_record_count} blijft dus exact — maar per code worden hoogstens
     * {@code maxSampleRowsPerCode} voorbeeldrijen bewaard. Omdat er in leesvolgorde gestreamd wordt,
     * zijn dat deterministisch de laagste regelnummers (R-ISS-03).
     *
     * @param beforeFilter of dit probleem ontstond vóór het recordfilter kon draaien. Alleen wanneer
     *                     er werkelijk filters geconfigureerd zijn, krijgt zo'n regel een eigen teller
     *                     ({@code error_before_filter_count}); zonder filters is er geen scope om
     *                     buiten te vallen en blijft het fase 2-gedrag gelden (R-FLT-04).
     */
    private void addIssue(Context context, Progress progress, long rowNumber, String code, String field,
                          String sourceValue, String message, boolean beforeFilter) {
        RowIssueSeverity severity = ImportIssueCatalog.classify(code).severity();
        progress.issueCounts.merge(code, 1L, Long::sum);
        if (severity == RowIssueSeverity.ERROR) {
            if (beforeFilter && context.hasRecordFilters()) {
                progress.errorBeforeFilterCount++;
            } else {
                progress.rejectedCount++;
            }
        }
        int recorded = progress.recordedSamples.getOrDefault(code, 0);
        if (recorded >= maxSampleRowsPerCode) {
            progress.sampleCapReached = true;
            return;
        }
        progress.recordedSamples.put(code, recorded + 1);
        progress.pendingIssues.add(ImportIssueCatalog.issue(context.batchId(), context.deliveryFileId(),
                rowNumber, code, field, sourceValue, null, message, Instant.now()));
        if (progress.pendingIssues.size() >= stageBatchSize) {
            flush(context, progress);
        }
    }

    /**
     * Eén informatieve melding wanneer er voorbeeldrijen weggelaten zijn, met de <b>volledige</b>
     * aantallen per foutcode. Zo blijft na een bestand met een miljoen identieke fouten nog steeds
     * zichtbaar hoeveel het er werkelijk waren, zonder een miljoen rijen te bewaren. Dit blokkeert de
     * levering niet (ontwerp fase 3, afwijking C).
     */
    private void recordSampleCapNotice(Context context, Progress progress) {
        if (!progress.sampleCapReached || progress.capNoticeRecorded) {
            return;
        }
        progress.capNoticeRecorded = true;
        progress.pendingIssues.add(ImportIssueCatalog.issue(context.batchId(), context.deliveryFileId(),
                null, CODE_ROW_ISSUE_RECORDING_CAPPED, null, null, null,
                "rowIssueSamples: '" + maxSampleRowsPerCode + "' is the maximum number of example rows kept "
                        + "per issue code; occurrences per code: " + describe(progress.issueCounts),
                Instant.now()));
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
            return Blockage.onRow(CODE_IDENTITY_HASH_COLLISION, inDelivery.getAsLong(),
                    "Line " + inDelivery.getAsLong()
                            + " shares its identity hash with another line in this delivery that has different "
                            + "identity components");
        }
        OptionalLong againstState = mutations.findSourceStateCollisionRow(context.batchId(),
                context.importLinkId());
        if (againstState.isPresent()) {
            return Blockage.onRow(CODE_IDENTITY_HASH_COLLISION, againstState.getAsLong(),
                    "Line " + againstState.getAsLong()
                            + " shares its identity hash with a known offer that has different identity "
                            + "components");
        }
        long duplicates = stage.countDuplicateRows(context.batchId());
        if (duplicates > 0) {
            return Blockage.duplicates(CODE_DUPLICATE_IDENTITY_IN_DELIVERY, duplicates, duplicates
                    + " lines repeat an offer identity that already occurs in this delivery; the delivery is "
                    + "blocked because a repeated identity is never resolved by keeping the last line");
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
        ValidationResult validationResult = determineValidationResult(context.batchId(), false);
        batch.setValidationResult(validationResult);
        batch.setStatus(ImportBatchStatus.SCREENED);
        batch.setFinishedAt(Instant.now());
        batches.saveAndFlush(batch);
        writeMarker(context, "outcome=SCREENED", validationResult);
        finishTaskRun(context, TaskRunStatus.COMPLETED);
        return outcome(batch);
    }

    /**
     * Het inhoudelijke eindoordeel naast de status (R-THR-06), voor zover in deze bouwstap te
     * berekenen: {@code BLOCKING} bij een geblokkeerde levering of minstens één kritiek/blokkerend
     * probleem, anders {@code VALID_WITH_WARNINGS} bij minstens één waarschuwing, anders
     * {@code VALID}. {@code REVIEW_REQUIRED} (bulkincidenten en wachtende creaties) komt met de
     * drempels in bouwstap 3h; tot dan wordt die waarde nooit gezet in plaats van geraden.
     * <p>
     * Leest met JdbcTemplate wat in deze transactie met JdbcTemplate geschreven is — nooit via JPA.
     */
    private ValidationResult determineValidationResult(long batchId, boolean blocked) {
        Map<RowIssueSeverity, Long> counts = rowIssues.countsBySeverity(batchId);
        boolean blockingIssue = counts.entrySet().stream()
                .anyMatch(entry -> entry.getKey().isBlockingForBatch() && entry.getValue() > 0);
        if (blocked || blockingIssue) {
            return ValidationResult.BLOCKING;
        }
        if (counts.getOrDefault(RowIssueSeverity.WARNING, 0L) > 0) {
            return ValidationResult.VALID_WITH_WARNINGS;
        }
        return ValidationResult.VALID;
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
        recordBlockageIssue(context, blockage);
        // Nul is hier geen aanname maar een vaststelling: een geblokkeerde levering genereert niets.
        batch.setContentMutationCount(mutations.countContentMutations(context.batchId()));
        ValidationResult validationResult = determineValidationResult(context.batchId(), true);
        batch.setValidationResult(validationResult);
        batch.setBlockedCode(truncate(blockage.code(), MAX_BLOCKED_CODE_LENGTH));
        batch.setBlockedReason(truncate(blockage.code() + ": " + blockage.reason(), MAX_BLOCKED_REASON_LENGTH));
        batch.setStatus(ImportBatchStatus.BLOCKED);
        batch.setFinishedAt(Instant.now());
        batches.saveAndFlush(batch);
        writeMarker(context, "outcome=BLOCKED;blockedCode=" + blockage.code(), validationResult);
        finishTaskRun(context, TaskRunStatus.COMPLETED);
        LOG.info("Batch {} blocked: {} ({})", context.batchId(), blockage.code(), blockage.reason());
        return outcome(batch);
    }

    /**
     * Elke blokkade laat ook een issuerij achter (ontwerp fase 3, par. 3.3), op controleniveau
     * {@code STRUCTURE} of {@code DELIVERY} met impactscope {@code DELIVERY}. Zonder die rij zou de
     * zwaarste vaststelling over een levering alléén in {@code blocked_code} staan en dus buiten de
     * probleemlijst vallen die de gebruiker leest. {@code blocked_code}/{@code blocked_reason}
     * blijven onveranderd bestaan.
     * <p>
     * <b>Nooit twee keer.</b> Bestaat er al een rij met deze foutcode voor deze batch, dan wordt er
     * geen samenvattende rij meer bijgeschreven: een dubbele identiteit heeft haar regels dan al
     * gemeld, en een hervatte verwerking mag de blokkade niet verdubbelen.
     */
    private void recordBlockageIssue(Context context, Blockage blockage) {
        if (rowIssues.countByBatchIdAndIssueCode(context.batchId(), blockage.code()) > 0) {
            return;
        }
        rowIssues.insertBatch(List.of(ImportIssueCatalog.issue(context.batchId(), context.deliveryFileId(),
                blockage.rowNumber(), blockage.code(), blockage.fieldName(), blockage.sourceValue(),
                blockage.expectedValue(), blockage.reason(), Instant.now())));
    }

    /**
     * Elke regel die bij een dubbele identiteit betrokken is, krijgt haar eigen probleem met het
     * regelnummer van de eerste voorkomst — ook de eerste regel zelf, want zonder de rest is ook zij
     * niet te vertrouwen. De voorbeeldcap per foutcode geldt onverkort: boven de cap worden er geen
     * voorbeelden meer bewaard, maar het volledige aantal staat in de blokkeerreden én in
     * {@code duplicate_identity_count} — het gaat dus nooit verloren.
     */
    private void recordDuplicateIssues(Context context, long duplicateRowCount) {
        stage.classifyDuplicates(context.batchId());
        long alreadyRecorded = rowIssues.countByBatchIdAndIssueCode(context.batchId(),
                CODE_DUPLICATE_IDENTITY_IN_DELIVERY);
        int budget = (int) Math.max(0, Math.min(maxSampleRowsPerCode - alreadyRecorded, duplicateRowCount));
        List<DuplicateRow> duplicates = stage.findDuplicateRows(context.batchId(), budget);
        Instant now = Instant.now();
        List<IssueRow> issues = duplicates.stream()
                .map(duplicate -> ImportIssueCatalog.issue(context.batchId(), context.deliveryFileId(),
                        duplicate.rowNumber(), CODE_DUPLICATE_IDENTITY_IN_DELIVERY, null, null, null,
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
    private void writeMarker(Context context, String outcome, ValidationResult validationResult) {
        mutations.insertMarker(context.mutationContext(),
                outcome + ";completenessProven=false;completenessReason=" + COMPLETENESS_REASON
                        + ";fileSha256=" + context.fileSha256()
                        + ";validationResult=" + validationResult.name(), Instant.now());
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

    /**
     * Alleen een volledig gelezen bestand levert eindtellers op; anders blijven ze onbekend (null).
     * <p>
     * De vijf tellers reconciliëren (R-FLT-04):
     * {@code raw = filtered_out + error_before_filter + rejected + valid}. Zonder geconfigureerde
     * recordfilters staan {@code filtered_out} en {@code error_before_filter} op 0 — dat is geen
     * aanname maar een vaststelling: zonder filters is er niets om buiten te vallen.
     */
    private static void applyCounts(ImportBatch batch, Progress progress) {
        batch.setStagedRowCount(progress.stagedCount);
        if (progress.rawRecordCount != null) {
            batch.setRawRecordCount(progress.rawRecordCount);
            batch.setValidRecordCount(progress.validCount);
            batch.setRejectedRecordCount(progress.rejectedCount);
            batch.setFilteredOutCount(progress.filteredOutCount);
            batch.setErrorBeforeFilterCount(progress.errorBeforeFilterCount);
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
        return new ScreeningOutcome(batch.getId(), batch.getStatus(), batch.getValidationResult(),
                batch.getRawRecordCount(), batch.getValidRecordCount(), batch.getRejectedRecordCount(),
                batch.getFilteredOutCount(), batch.getErrorBeforeFilterCount(),
                batch.getStagedRowCount(), batch.getDuplicateIdentityCount(), batch.getNewCount(),
                batch.getChangedCount(), batch.getUnchangedCount(), batch.getContentMutationCount(),
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
