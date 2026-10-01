package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.IssueGroupDao.Counter;
import be.dda.catalogimport.domain.CurrencyOrigin;
import be.dda.catalogimport.domain.FieldValueKind;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.RowIssueSeverity;
import be.dda.catalogimport.service.DeliveryScreeningService.LoadedConfiguration;
import be.dda.catalogimport.service.support.CandidateNormaliser;
import be.dda.catalogimport.service.support.ConfigCheckReport;
import be.dda.catalogimport.service.support.ConfigFinding;
import be.dda.catalogimport.service.support.CandidateNormaliser.NormalisedCandidate;
import be.dda.catalogimport.service.support.CsvRecordStreamer;
import be.dda.catalogimport.service.support.CsvRecordStreamer.LineIssue;
import be.dda.catalogimport.service.support.CsvRecordStreamer.ParsedRow;
import be.dda.catalogimport.service.support.CsvRecordStreamer.ReadSummary;
import be.dda.catalogimport.service.support.FieldValueMapper;
import be.dda.catalogimport.service.support.HeaderExpectations;
import be.dda.catalogimport.service.support.ImportIssueCatalog;
import be.dda.catalogimport.service.support.ImportMappingConfig;
import be.dda.catalogimport.service.support.IssueSignature;
import be.dda.catalogimport.service.support.PriceRules;
import be.dda.catalogimport.service.support.RecordFilterEvaluator;
import be.dda.catalogimport.service.support.RecordScreeningCore;
import be.dda.catalogimport.service.support.ScreeningBlockedException;
import be.dda.catalogimport.service.support.SourceStructureConfig;
import be.dda.catalogimport.service.support.SourceStructureConfig.FieldReferenceKind;
import be.dda.catalogimport.service.support.ThresholdEvaluator;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proefinlezing (trial read) van een bestand tegen een revisie, <b>zonder iets te bewaren</b> (NT-9; bindend contract
 * {@code docs/design/proefinlezing-design.md}, beslissingslog 2026-09-30 "NT-9a" en NT-spoor V1 = A).
 *
 * <h2>Businessgedrag</h2>
 * <ul>
 *   <li><b>Zelfde oordeel als de screening, zover dat zonder bronstaat kan.</b> Configuratie, lezen, beslisboom per
 *       regel en tellerregels zijn dezelfde implementatie als de echte screening ({@link DeliveryScreeningService
 *       #loadConfiguration}, {@link CsvRecordStreamer}, {@link RecordScreeningCore}); de bulkregel en de voorrang van
 *       de drempels ook ({@link IssueAggregationService#isBulk}, {@link ThresholdEvaluator#leading}).</li>
 *   <li><b>Het verdict is de eerste blokkade in de screeningvolgorde</b>: configuratie → lezen → geen datarecords →
 *       dubbele identiteit → drempels (kritiek wint). Een blokkade is een resultaat (HTTP 200), geen fout.</li>
 *   <li><b>Wat niet te beoordelen is, staat er als INFO bij</b> ({@code notEvaluated}): creatiebeleid,
 *       hashcollisie tegen de bronstaat, referentiecontrole, prijsafwijking, manifesttellingen, ... De kritieke
 *       drempel telt enkel kritieke lijnen en is dus een <b>ondergrens</b>: {@code EXCEEDED} blokkeert zeker,
 *       {@code WITHIN} garandeert niets.</li>
 *   <li><b>Tellers zijn {@code null} wanneer ze niet vastgesteld zijn</b>, nooit stil 0. Een prijs wordt getoond
 *       zoals gelezen én zoals geïnterpreteerd, nooit gecorrigeerd.</li>
 * </ul>
 *
 * <h2>Technisch</h2>
 * <ul>
 *   <li>Eén korte leestransactie voor revisie, koppeling en configuratie; het lezen gebeurt zonder open verbinding.
 *       Er wordt geen entiteit gewijzigd, niets gearchiveerd en niets in de database geschreven.</li>
 *   <li>Streaming: het bestand staat nooit volledig in het geheugen. Enkel de identiteiten worden gevolgd voor de
 *       duplicaatcontrole, begrensd door {@code catalogimport.trial-read.max-tracked-identities}.</li>
 *   <li>Pure functie: hetzelfde bestand tegen dezelfde revisie (en koppelingsvaluta) geeft een byte-gelijk antwoord;
 *       er staat geen tijdstip en geen gegenereerd id in.</li>
 *   <li>Precies één INFO-logregel per voltooide proef, zonder bestandsnaam en zonder inhoud.</li>
 * </ul>
 */
@Service
public class TrialReadService {

    public static final String CODE_REVISION_NOT_FOUND = "REVISION_NOT_FOUND";
    public static final String CODE_LINK_NOT_FOUND = "LINK_NOT_FOUND";
    public static final String CODE_LINK_NOT_OF_REVISION_DEFINITION = "LINK_NOT_OF_REVISION_DEFINITION";
    /** Het multipartdeel {@code file} ontbreekt (het wordt in de Web-laag vastgesteld). */
    public static final String CODE_FILE_REQUIRED = "FILE_REQUIRED";

    public static final int DEFAULT_SAMPLE_ROWS = 20;
    public static final int DEFAULT_MAX_ISSUE_EXAMPLES = 5;
    public static final int DEFAULT_MAX_TRACKED_IDENTITIES = 2_000_000;
    /** Een celwaarde in {@code rawValues} wordt op zoveel tekens gekapt. */
    public static final int RAW_VALUE_MAX_LENGTH = 200;

    // --- Redenen in notEvaluated -----------------------------------------------------------------------------------
    public static final String REASON_NO_SOURCE_STATE = "NO_SOURCE_STATE";
    public static final String REASON_NOT_IMPLEMENTED_V1 = "NOT_IMPLEMENTED_V1";
    public static final String REASON_NO_PRICE_HISTORY = "NO_PRICE_HISTORY";
    public static final String REASON_NO_MANIFEST = "NO_MANIFEST";
    public static final String REASON_NOT_TRACKED = "NOT_TRACKED";
    public static final String REASON_TRACKING_LIMIT_REACHED = "TRACKING_LIMIT_REACHED";

    private static final Logger LOG = LoggerFactory.getLogger(TrialReadService.class);
    private static final char REPLACEMENT_CHARACTER = '�';

    // --- Antwoord ---------------------------------------------------------------------------------------------------

    public enum VerdictResult { WOULD_BLOCK, NO_BLOCKER_FOUND }

    public enum Stage { CONFIGURATION, READING, FILE_LEVEL, IDENTITY, THRESHOLD }

    public enum SampleStatus { VALID, REJECTED, FILTERED_OUT, UNREADABLE }

    public enum ColumnRole { IDENTITY, PRICE, CURRENCY, DESCRIPTION, DISCOUNT, MAPPING, FILTER }

    /** Het volledige antwoord; zie contract par. 2 voor de betekenis van elk veld. */
    public record TrialReadResult(long revisionId, int revisionNumber, RevisionStatus revisionStatus, Long linkId,
                                  FileInfo file, CurrencyDefault currencyDefault, Verdict verdict,
                                  Counters counters, Header header, List<SampleRow> sampleRows,
                                  boolean sampleRowsTruncated, List<IssueGroup> issueGroups, Thresholds thresholds,
                                  List<ConfigProblem> configProblems, List<String> configChecksSkippedBecause,
                                  List<NotEvaluated> notEvaluated) {
    }

    public record FileInfo(long byteSize, String sha256, String fileName) {
    }

    /** De vaste valuta die geldt voor een regel zonder muntveld: die van de koppeling, of EUR (systeemstandaard). */
    public record CurrencyDefault(String value, CurrencyOrigin origin) {
    }

    /**
     * @param blockedReason technische (Engelse) toelichting voor "Technische details"; de Nederlandse tekst komt uit
     *                      de woordenlijst van de frontend (V7)
     */
    public record Verdict(VerdictResult result, String blockedCode, String blockedReason, String fieldName,
                          String sourceValue, String expectedValue, Stage stage) {
    }

    /** Tellers 1-op-1 met de screening; {@code null} = niet vastgesteld. */
    public record Counters(Long rawRecordCount, Long validRecordCount, Long rejectedRecordCount,
                           Long filteredOutCount, Long errorBeforeFilterCount, Long criticalLineCount,
                           Long duplicateIdentityCount, Long scopeRecordCount, Long physicalLineCount,
                           Long prefixLineCount, Long skippedBlankLineCount, Long columnCount,
                           Long linesWithReplacementCharacter, Map<String, Long> issueOccurrencesBySeverity) {
    }

    public record Header(FieldReferenceKind referenceKind, boolean hasHeader, Integer headerLineNumber,
                         Integer expectedColumnCount, Integer foundColumnCount, List<String> foundColumns,
                         List<ExpectedColumn> expectedColumns, List<String> missingRequired,
                         List<String> missingOptionalFilterColumns, List<ExtraColumn> extraColumns,
                         List<ShiftedColumn> shifted) {
    }

    public record ExpectedColumn(String reference, ColumnRole role, boolean required, Integer foundAtPosition) {
    }

    public record ExtraColumn(String name, int position) {
    }

    public record ShiftedColumn(String reference, int expectedPosition, int foundPosition) {
    }

    public record SampleRow(long lineNumber, SampleStatus status, List<String> rawValues, String sourceValue,
                            Interpreted interpreted, SampleFilter filter, List<SampleIssue> issues) {
    }

    /** Enkel voor een geldige regel: de waarden zoals de screening ze zou stagen. Bedragen als tekst, ongewijzigd. */
    public record Interpreted(String supplier, String supplierGroup, String supplierReference, String discountCode,
                              String discountState, String identityHash, String basePriceRaw, String basePrice,
                              String currency, CurrencyOrigin currencyOrigin, String description,
                              Map<String, String> mappedFields, List<PriceComponentView> priceComponents,
                              List<ReferenceView> references) {
    }

    public record PriceComponentView(String componentCode, String sourceAmount, String percentage, String currency,
                                     String status) {
    }

    public record ReferenceView(String referenceType, String valueRaw, String valueNormalised) {
    }

    public record SampleFilter(RecordFilterEvaluator.Decision.Kind kind, Integer decidingSequenceNumber) {
    }

    public record SampleIssue(String code, RowIssueSeverity severity, String fieldName, String sourceValue,
                              String message) {
    }

    public record IssueGroup(String code, String fieldName, RowIssueSeverity severity, String domain,
                             String controlLevel, String deliveryEffect, long occurrenceCount, boolean grouped,
                             boolean bulkIncident, String sharePercent, List<IssueExample> examples,
                             boolean examplesTruncated) {
    }

    public record IssueExample(Long lineNumber, String fieldName, String sourceValue, String message) {
    }

    public record Thresholds(Long scopeRecordCount, String bulkIncidentSharePercent, CriticalThreshold critical,
                             RejectedThreshold rejected) {
    }

    public record CriticalThreshold(Long count, boolean identityIncidentsEvaluated, String thresholdPercent,
                                    String sharePercent, ThresholdEvaluator.Outcome outcome,
                                    boolean countIsLowerBound) {
    }

    public record RejectedThreshold(Long count, String thresholdPercent, String sharePercent,
                                    ThresholdEvaluator.Outcome outcome) {
    }

    /** @param revisionField sleutel uit het woordenlijstdomein {@code revisionField}, of {@code null} (NT-14-3) */
    public record ConfigProblem(String code, String message, String fieldName, String revisionField) {
    }

    public record NotEvaluated(String check, String status, String reason) {
    }

    // --- Afhankelijkheden --------------------------------------------------------------------------------------------

    private final CsvRecordStreamer streamer = new CsvRecordStreamer();
    private final ImportDefinitionRevisionRepository revisions;
    private final ImportLinkRepository links;
    private final DeliveryScreeningService screening;
    private final ChainConfigurationChecks chainChecks;
    private final TransactionTemplate readOnly;
    private final int sampleRows;
    private final int maxIssueExamples;
    private final int maxTrackedIdentities;
    private final int maxLineLength;

    public TrialReadService(ImportDefinitionRevisionRepository revisions, ImportLinkRepository links,
                            DeliveryScreeningService screening, ChainConfigurationChecks chainChecks,
                            PlatformTransactionManager transactionManager,
                            @Value("${catalogimport.trial-read.sample-rows:" + DEFAULT_SAMPLE_ROWS + "}")
                            int sampleRows,
                            @Value("${catalogimport.trial-read.max-issue-examples-per-code:"
                                    + DEFAULT_MAX_ISSUE_EXAMPLES + "}") int maxIssueExamples,
                            @Value("${catalogimport.trial-read.max-tracked-identities:"
                                    + DEFAULT_MAX_TRACKED_IDENTITIES + "}") int maxTrackedIdentities,
                            @Value("${catalogimport.screening.max-line-length:100000}") int maxLineLength) {
        this.revisions = revisions;
        this.links = links;
        this.screening = screening;
        this.chainChecks = chainChecks;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.sampleRows = sampleRows > 0 ? sampleRows : DEFAULT_SAMPLE_ROWS;
        this.maxIssueExamples = maxIssueExamples > 0 ? maxIssueExamples : DEFAULT_MAX_ISSUE_EXAMPLES;
        this.maxTrackedIdentities = maxTrackedIdentities > 0 ? maxTrackedIdentities : DEFAULT_MAX_TRACKED_IDENTITIES;
        this.maxLineLength = maxLineLength > 0 ? maxLineLength : CsvRecordStreamer.DEFAULT_MAX_LINE_LENGTH;
    }

    /**
     * Leest het bestand op proef tegen de revisie. De stream wordt volledig gelezen (ook na een blokkade, voor de
     * bestandsgrootte en de hash) maar niet gesloten: dat blijft de zaak van de aanroeper.
     *
     * @param linkId   optioneel; enkel voor de vaste valuta van de koppeling
     * @param fileName enkel om terug te geven; nooit gelogd
     * @throws NotFoundException    {@code REVISION_NOT_FOUND}, {@code LINK_NOT_FOUND}
     * @throws BadRequestException  {@code LINK_NOT_OF_REVISION_DEFINITION}
     * @throws UncheckedIOException bij een technische leesfout
     */
    public TrialReadResult trialRead(long revisionId, Long linkId, String fileName, InputStream content) {
        Setup setup = readOnly.execute(status -> setup(revisionId, linkId));
        LoadedConfiguration loaded = setup.loaded();
        DigestingInputStream in = new DigestingInputStream(content);

        TrialSink sink = null;
        ReadSummary summary = null;
        ScreeningBlockedException blocked = loaded.failure();
        Stage blockedStage = blocked == null ? null : Stage.CONFIGURATION;
        if (blocked == null) {
            sink = new TrialSink(loaded.config(), loaded.mappingConfig());
            try {
                summary = streamer.read(in, loaded.config(), loaded.mappingConfig().headerExpectations(),
                        maxLineLength, sink);
            } catch (ScreeningBlockedException readBlockage) {
                blocked = readBlockage;
                blockedStage = Stage.READING;
            }
        }
        in.drain();
        FileInfo file = new FileInfo(in.count(), in.sha256Hex(), fileName);

        TrialReadResult result = blockedStage == Stage.CONFIGURATION
                ? configurationFailure(setup, file, blocked)
                : evaluate(setup, file, sink, summary, blocked);
        LOG.info("Trial read: revisionId={} linkId={} byteSize={} raw={} verdict={} blockedCode={}",
                result.revisionId(), result.linkId(), file.byteSize(), result.counters().rawRecordCount(),
                result.verdict().result(), result.verdict().blockedCode());
        return result;
    }

    // --- Stap 1: revisie, koppeling en configuratie in één leestransactie ------------------------------------------

    private record Setup(long revisionId, int revisionNumber, RevisionStatus revisionStatus, Long linkId,
                         String linkCurrency, LoadedConfiguration loaded, ConfigCheckReport configReport,
                         BigDecimal bulkSharePercent,
                         BigDecimal maxCriticalSharePercent, BigDecimal maxRejectedSharePercent) {
    }

    private Setup setup(long revisionId, Long linkId) {        ImportDefinitionRevision revision = revisions.findById(revisionId)
                .orElseThrow(() -> new NotFoundException(CODE_REVISION_NOT_FOUND,
                        "Revision " + revisionId + " does not exist"));
        String linkCurrency = null;
        if (linkId != null) {
            ImportLink link = links.findById(linkId)
                    .orElseThrow(() -> new NotFoundException(CODE_LINK_NOT_FOUND,
                            "Import link " + linkId + " does not exist"));
            if (!link.getImportDefinition().getId().equals(revision.getImportDefinition().getId())) {
                throw new BadRequestException(CODE_LINK_NOT_OF_REVISION_DEFINITION, "Import link " + linkId
                        + " does not belong to the import definition of revision " + revisionId);
            }
            linkCurrency = link.getDefaultCurrency();
        }
        LoadedConfiguration loaded = screening.loadConfiguration(revision, linkCurrency);
        // NT-14-3: bij een configuratiefout vóór het lezen alle onafhankelijke fouten, in dezelfde (lees)transactie.
        ConfigCheckReport report = loaded.failure() == null ? null
                : chainChecks.configurationReport(revision, linkCurrency);
        return new Setup(revision.getId(), revision.getRevisionNumber(), revision.getStatus(), linkId, linkCurrency,
                loaded, report, revision.getBulkIncidentSharePercent(), revision.getMaxCriticalSharePercent(),
                revision.getMaxRejectedSharePercent());
    }

    // --- Stap 2a: configuratiefout — alles null, secties leeg -------------------------------------------------------

    private TrialReadResult configurationFailure(Setup setup, FileInfo file, ScreeningBlockedException failure) {
        Verdict verdict = blockedVerdict(failure, Stage.CONFIGURATION);
        Counters counters = new Counters(null, null, null, null, null, null, null, null, null, null, null, null,
                null, null);
        return new TrialReadResult(setup.revisionId(), setup.revisionNumber(), setup.revisionStatus(),
                setup.linkId(), file, currencyDefault(setup), verdict, counters, null, List.of(), false, List.of(),
                null, allConfigProblems(setup.configReport(), verdict),
                setup.configReport() == null ? List.of() : setup.configReport().skippedBecause(), List.of());
    }

    /**
     * Alle onafhankelijke configuratiefouten (NT-14a). Het verdict blijft de eerste fout van de screening; de eerste
     * bevinding is dat per invariant ook. Wijkt ze af, dan staat het verdict vooraan en wordt één WARN gelogd
     * (enkel codes, nooit inhoud).
     */
    private static List<ConfigProblem> allConfigProblems(ConfigCheckReport report, Verdict verdict) {
        ConfigProblem leading = new ConfigProblem(verdict.blockedCode(), verdict.blockedReason(), verdict.fieldName(),
                null);
        List<ConfigProblem> problems = new ArrayList<>();
        if (report != null) {
            for (ConfigFinding finding : report.findings()) {
                problems.add(new ConfigProblem(finding.code(), finding.message(), finding.fieldName(),
                        finding.revisionField()));
            }
        }
        if (problems.isEmpty()) {
            return List.of(leading);
        }
        if (!java.util.Objects.equals(problems.get(0).code(), verdict.blockedCode())) {
            LOG.warn("Trial read: first config finding {} differs from verdict {}", problems.get(0).code(),
                    verdict.blockedCode());
            problems.add(0, leading);
        }
        return List.copyOf(problems);
    }

    // --- Stap 2b: gelezen (volledig of tot een leesblokkade) --------------------------------------------------------

    private TrialReadResult evaluate(Setup setup, FileInfo file, TrialSink sink, ReadSummary summary,
                                     ScreeningBlockedException readBlockage) {
        RecordScreeningCore core = sink.core;
        boolean completed = readBlockage == null && summary != null;

        // Tellers: enkel na een volledige lezing (feit 3: bij een leesblokkade null, nooit 0).
        Long raw = completed ? summary.rawRecordCount() : null;
        Long filteredOut = completed ? core.filteredOutCount() : null;
        Long scope = raw == null ? null : Math.max(0L, raw - filteredOut);
        Long duplicates = completed && !sink.identities.limitReached ? sink.identities.duplicateRows : null;

        // Verdict: eerste treffer in de screeningvolgorde (feit 4).
        Verdict verdict;
        ThresholdEvaluator.Judgement critical = ThresholdEvaluator.evaluateCritical(
                completed ? core.criticalLineCount() : null, scope, setup.maxCriticalSharePercent());
        ThresholdEvaluator.Judgement rejected = ThresholdEvaluator.evaluateRejected(
                completed ? core.rejectedCount() : null, scope, setup.maxRejectedSharePercent());
        if (readBlockage != null) {
            verdict = blockedVerdict(readBlockage, Stage.READING);
        } else if (raw == 0) {
            verdict = new Verdict(VerdictResult.WOULD_BLOCK, ImportIssueCatalog.SOURCE_NO_DATA_RECORDS,
                    "The delivery file contains no data records", null, null, null, Stage.FILE_LEVEL);
        } else if (sink.identities.duplicateRows > 0) {
            verdict = new Verdict(VerdictResult.WOULD_BLOCK, ImportIssueCatalog.DUPLICATE_IDENTITY_IN_DELIVERY,
                    duplicates == null
                            ? "At least one line repeats an offer identity that already occurs in this file; the "
                            + "delivery would be blocked because a repeated identity is never resolved by keeping "
                            + "the last line"
                            : duplicates + " lines repeat an offer identity that already occurs in this file; the "
                            + "delivery would be blocked because a repeated identity is never resolved by keeping "
                            + "the last line",
                    null, null, null, Stage.IDENTITY);
        } else {
            ThresholdEvaluator.Judgement leading = ThresholdEvaluator.leading(critical, rejected);
            verdict = leading == null
                    ? new Verdict(VerdictResult.NO_BLOCKER_FOUND, null, null, null, null, null, null)
                    : new Verdict(VerdictResult.WOULD_BLOCK, leading.issueCode(),
                            leading == critical ? criticalReason(critical) : rejectedReason(rejected),
                            null, String.valueOf(leading.count()), percent(leading.thresholdPercent()),
                            Stage.THRESHOLD);
        }

        List<IssueGroup> groups = issueGroups(sink, scope, duplicates,
                IssueAggregationService.effectiveBulkSharePercent(setup.bulkSharePercent()));
        Counters counters = new Counters(raw, completed ? core.validCount() : null,
                completed ? core.rejectedCount() : null, filteredOut,
                completed ? core.errorBeforeFilterCount() : null, completed ? core.criticalLineCount() : null,
                duplicates, scope,
                completed ? summary.physicalLineCount() : null, completed ? summary.prefixLineCount() : null,
                completed ? summary.skippedBlankLineCount() : null,
                completed && summary.columnCount() >= 0 ? Long.valueOf(summary.columnCount()) : null,
                completed ? sink.linesWithReplacementCharacter : null, occurrencesBySeverity(groups));

        List<SampleRow> samples = new ArrayList<>(sink.samples.size());
        for (SampleBuilder builder : sink.samples) {
            samples.add(builder.build(sink.config, sink.mappingConfig));
        }
        Thresholds thresholds = new Thresholds(scope,
                percent(IssueAggregationService.effectiveBulkSharePercent(setup.bulkSharePercent())),
                new CriticalThreshold(critical.count(), false, percent(critical.thresholdPercent()),
                        percent(critical.sharePercent()), critical.outcome(), true),
                new RejectedThreshold(rejected.count(), percent(rejected.thresholdPercent()),
                        percent(rejected.sharePercent()), rejected.outcome()));

        return new TrialReadResult(setup.revisionId(), setup.revisionNumber(), setup.revisionStatus(),
                setup.linkId(), file, currencyDefault(setup), verdict, counters,
                header(sink, completed ? summary : null), List.copyOf(samples),
                sink.dataRecords > samples.size(), groups, thresholds, configProblems(verdict), List.of(),
                notEvaluated(sink.mappingConfig, sink.identities.limitReached));
    }

    private static Verdict blockedVerdict(ScreeningBlockedException blocked, Stage stage) {
        return new Verdict(VerdictResult.WOULD_BLOCK, blocked.getCode(), blocked.getMessage(), blocked.getFieldName(),
                blocked.getSourceValue(), blocked.getExpectedValue(), stage);
    }

    /** Eigen tekst: de melding van de screening spreekt over identiteitsincidenten en bewaarde staging. */
    private static String criticalReason(ThresholdEvaluator.Judgement judgement) {
        return "criticalRecords: at least '" + judgement.count() + "' records of this file need review (critical "
                + "lines only; held identity incidents are not evaluated in a trial read), against "
                + judgement.scope() + " records in scope" + shareText(judgement)
                + "; that is above the review threshold of " + percent(judgement.thresholdPercent())
                + "% of the scope, so the delivery would be blocked";
    }

    private static String rejectedReason(ThresholdEvaluator.Judgement judgement) {
        return "rejectedRecords: '" + judgement.count() + "' source lines of this file would be rejected, against "
                + judgement.scope() + " records in scope" + shareText(judgement)
                + "; that is above the rejection threshold of " + percent(judgement.thresholdPercent())
                + "% of the scope, so the delivery would be blocked";
    }

    private static String shareText(ThresholdEvaluator.Judgement judgement) {
        return judgement.sharePercent() == null ? ""
                : " (" + percent(judgement.sharePercent()) + "% of the scope)";
    }

    /** Hoogstens één {@code CONFIG_*} (NT-8-beperking): de eerste configuratiefout die de screening zou geven. */
    private static List<ConfigProblem> configProblems(Verdict verdict) {
        if (verdict.blockedCode() == null || !verdict.blockedCode().startsWith("CONFIG_")) {
            return List.of();
        }
        return List.of(new ConfigProblem(verdict.blockedCode(), verdict.blockedReason(), verdict.fieldName(), null));
    }

    private static CurrencyDefault currencyDefault(Setup setup) {
        String link = setup.linkCurrency() == null || setup.linkCurrency().isBlank()
                ? null : setup.linkCurrency().trim();
        return link == null
                ? new CurrencyDefault(CandidateNormaliser.SYSTEM_DEFAULT_CURRENCY, CurrencyOrigin.SYSTEM_DEFAULT)
                : new CurrencyDefault(link, CurrencyOrigin.LINK_DEFAULT);
    }

    // --- Issuegroepen --------------------------------------------------------------------------------------------

    /**
     * Per (code, logisch veld) via {@link RecordScreeningCore#tally()}, met de classificatie uit
     * {@link ImportIssueCatalog}; groepen onder {@link IssueAggregationService#GROUP_MIN_OCCURRENCES} blijven
     * zichtbaar ({@code grouped=false}) en krijgen, zoals in pass E4, geen aandeel en geen bulkoordeel.
     */
    private List<IssueGroup> issueGroups(TrialSink sink, Long scope, Long duplicates, BigDecimal bulkSharePercent) {
        List<IssueGroup> groups = new ArrayList<>();
        for (Counter counter : sink.core.tally().drain()) {
            GroupExamples examples = sink.groupExamples.get(counter.issueCode() + ' ' + counter.signature());
            groups.add(group(counter.issueCode(), examples == null ? null : examples.fieldName, counter.severity(),
                    counter.occurrences(), scope, bulkSharePercent,
                    examples == null ? List.of() : examples.examples));
        }
        if (duplicates != null && duplicates > 0) {
            ImportIssueCatalog.IssueClassification classification =
                    ImportIssueCatalog.classify(ImportIssueCatalog.DUPLICATE_IDENTITY_IN_DELIVERY);
            IssueGroup duplicateGroup = group(ImportIssueCatalog.DUPLICATE_IDENTITY_IN_DELIVERY, null,
                    classification.severity(), duplicates, scope, bulkSharePercent, sink.identities.examples);
            // De voorbeelden zijn de latere voorvallen; afgekapt betekent: er zijn meer latere voorvallen dan
            // voorbeelden (het aantal van de groep telt, zoals countDuplicateRows, ook de eerste voorkomsten).
            groups.add(new IssueGroup(duplicateGroup.code(), duplicateGroup.fieldName(), duplicateGroup.severity(),
                    duplicateGroup.domain(), duplicateGroup.controlLevel(), duplicateGroup.deliveryEffect(),
                    duplicateGroup.occurrenceCount(), duplicateGroup.grouped(), duplicateGroup.bulkIncident(),
                    duplicateGroup.sharePercent(), duplicateGroup.examples(),
                    sink.identities.repeats > sink.identities.examples.size()));
        }
        return List.copyOf(groups);
    }

    private static IssueGroup group(String code, String fieldName, RowIssueSeverity severity, long occurrences,
                                    Long scope, BigDecimal bulkSharePercent, List<IssueExample> examples) {
        ImportIssueCatalog.IssueClassification classification = ImportIssueCatalog.classify(code);
        boolean grouped = occurrences >= IssueAggregationService.GROUP_MIN_OCCURRENCES;
        boolean bulk = grouped && IssueAggregationService.isBulk(occurrences, scope, bulkSharePercent);
        String share = grouped ? percent(IssueAggregationService.share(occurrences, scope)) : null;
        return new IssueGroup(code, fieldName, severity, classification.domain().name(),
                classification.controlLevel().name(), classification.deliveryEffect().name(), occurrences, grouped,
                bulk, share, List.copyOf(examples), occurrences > examples.size());
    }

    /** CRITICAL/ERROR/WARNING/INFO, altijd alle vier (0 = gemeten); het werkelijke aantal, niet het aantal voorbeelden. */
    private static Map<String, Long> occurrencesBySeverity(List<IssueGroup> groups) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (RowIssueSeverity severity : new RowIssueSeverity[] {RowIssueSeverity.CRITICAL, RowIssueSeverity.ERROR,
                RowIssueSeverity.WARNING, RowIssueSeverity.INFO}) {
            counts.put(severity.name(), 0L);
        }
        for (IssueGroup group : groups) {
            counts.computeIfPresent(group.severity().name(), (key, value) -> value + group.occurrenceCount());
        }
        return counts;
    }

    // --- Header -------------------------------------------------------------------------------------------------

    private record Expected(String reference, ColumnRole role, boolean required) {
    }

    /**
     * Wat de revisie verwacht naast wat het bestand bevat. {@code missingRequired} toont álle ontbrekende verplichte
     * kolommen (via de sinkhook), ook wanneer het verdict enkel de eerste noemt.
     */
    private static Header header(TrialSink sink, ReadSummary summary) {
        SourceStructureConfig config = sink.config;
        ImportMappingConfig mapping = sink.mappingConfig;
        boolean byName = config.fieldReferenceKind() == FieldReferenceKind.HEADER_NAME;
        List<String> headerColumns = sink.headerColumns;

        Integer foundColumnCount = headerColumns != null ? Integer.valueOf(headerColumns.size())
                : (summary != null && summary.columnCount() >= 0 ? Integer.valueOf(summary.columnCount()) : null);
        List<String> trimmedHeader = headerColumns == null ? null
                : headerColumns.stream().map(String::trim).toList();
        // Zonder header of zonder bekend kolomaantal is er niets vast te stellen: dan geen "ontbreekt"-oordeel.
        boolean determinable = byName ? trimmedHeader != null : foundColumnCount != null;

        List<Expected> expected = expectedColumns(config, mapping);
        List<ExpectedColumn> expectedColumns = new ArrayList<>(expected.size());
        Set<String> missingRequired = new LinkedHashSet<>();
        Set<String> missingFilters = new LinkedHashSet<>();
        Set<Integer> referencedPositions = new LinkedHashSet<>();
        for (Expected column : expected) {
            Integer position = determinable
                    ? position(column.reference(), byName, trimmedHeader, foundColumnCount) : null;
            expectedColumns.add(new ExpectedColumn(column.reference(), column.role(), column.required(), position));
            if (position != null) {
                referencedPositions.add(position);
            } else if (determinable && column.required()) {
                missingRequired.add(column.reference());
            } else if (determinable) {
                missingFilters.add(column.reference());
            }
        }

        List<ExtraColumn> extra = new ArrayList<>();
        if (determinable && foundColumnCount != null) {
            for (int position = 1; position <= foundColumnCount; position++) {
                if (!referencedPositions.contains(position)) {
                    extra.add(new ExtraColumn(trimmedHeader == null ? null : trimmedHeader.get(position - 1),
                            position));
                }
            }
        }

        List<ShiftedColumn> shifted = new ArrayList<>();
        if (byName && trimmedHeader != null) {
            for (HeaderExpectations.ExpectedField field : mapping.headerExpectations().fields()) {
                Integer found = position(field.reference(), true, trimmedHeader, foundColumnCount);
                if (field.expectedPosition() != null && found != null && !found.equals(field.expectedPosition())) {
                    shifted.add(new ShiftedColumn(field.reference(), field.expectedPosition(), found));
                }
            }
        }

        return new Header(config.fieldReferenceKind(), config.hasHeader(),
                config.hasHeader() ? Integer.valueOf(config.headerLineNumber()) : null, config.expectedColumnCount(),
                foundColumnCount, byName ? trimmedHeader : null, List.copyOf(expectedColumns),
                List.copyOf(missingRequired), List.copyOf(missingFilters), List.copyOf(extra),
                List.copyOf(shifted));
    }

    /** De verwachte kolommen in vaste volgorde: de revisievelden (als {@code declaredFields}), mappings, filters. */
    private static List<Expected> expectedColumns(SourceStructureConfig config, ImportMappingConfig mapping) {
        List<Expected> expected = new ArrayList<>();
        expected.add(new Expected(config.supplierField(), ColumnRole.IDENTITY, true));
        expected.add(new Expected(config.supplierGroupField(), ColumnRole.IDENTITY, true));
        expected.add(new Expected(config.supplierReferenceField(), ColumnRole.IDENTITY, true));
        if (config.identityProfileKind() == IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE
                && config.discountCodeField() != null) {
            expected.add(new Expected(config.discountCodeField(), ColumnRole.DISCOUNT, true));
        }
        expected.add(new Expected(config.basePriceField(), ColumnRole.PRICE, true));
        if (config.descriptionField() != null) {
            expected.add(new Expected(config.descriptionField(), ColumnRole.DESCRIPTION, true));
        }
        if (config.currencyField() != null) {
            expected.add(new Expected(config.currencyField(), ColumnRole.CURRENCY, true));
        }
        for (ImportMappingConfig.FieldMapping field : mapping.fields()) {
            if (field.valueKind() == FieldValueKind.SOURCE_FIELD && field.sourceReference() != null) {
                expected.add(new Expected(field.sourceReference(), ColumnRole.MAPPING, true));
            }
        }
        for (ImportMappingConfig.RecordFilter filter : mapping.filters()) {
            expected.add(new Expected(filter.sourceReference(), ColumnRole.FILTER, false));
        }
        return expected;
    }

    /** 1-gebaseerde positie: op naam (getrimd, hoofdletterongevoelig, zoals de streamer) of op kolomindex. */
    private static Integer position(String reference, boolean byName, List<String> trimmedHeader,
                                    Integer columnCount) {
        if (reference == null) {
            return null;
        }
        if (byName) {
            String wanted = reference.trim().toLowerCase(Locale.ROOT);
            for (int i = 0; i < trimmedHeader.size(); i++) {
                if (trimmedHeader.get(i).toLowerCase(Locale.ROOT).equals(wanted)) {
                    return i + 1;
                }
            }
            return null;
        }
        try {
            int index = Integer.parseInt(reference.trim());
            return index >= 1 && columnCount != null && index <= columnCount ? index : null;
        } catch (NumberFormatException notAnIndex) {
            return null;
        }
    }

    // --- Niet geëvalueerd ----------------------------------------------------------------------------------------

    private static List<NotEvaluated> notEvaluated(ImportMappingConfig mapping, boolean trackingLimitReached) {
        List<NotEvaluated> items = new ArrayList<>();
        items.add(info("CREATION_POLICY", REASON_NO_SOURCE_STATE));
        items.add(info("IDENTITY_HASH_COLLISION_AGAINST_SOURCE_STATE", REASON_NO_SOURCE_STATE));
        if (mapping.hasReferences()) {
            items.add(info("REFERENCE_CONTROL", REASON_NO_SOURCE_STATE));
        }
        items.add(info(ImportIssueCatalog.DUPLICATE_REFERENCE_IN_DELIVERY, REASON_NOT_IMPLEMENTED_V1));
        items.add(info("PRICE_DEVIATION", REASON_NO_PRICE_HISTORY));
        items.add(info("MANIFEST_COUNTS", REASON_NO_MANIFEST));
        items.add(info("IDENTITY_HASH_COLLISION_IN_FILE", REASON_NOT_TRACKED));
        items.add(info("IDENTITY_INCIDENTS_IN_CRITICAL_THRESHOLD", REASON_NO_SOURCE_STATE));
        if (trackingLimitReached) {
            items.add(info(ImportIssueCatalog.DUPLICATE_IDENTITY_IN_DELIVERY, REASON_TRACKING_LIMIT_REACHED));
        }
        return List.copyOf(items);
    }

    private static NotEvaluated info(String check, String reason) {
        return new NotEvaluated(check, "INFO", reason);
    }

    /** Een percentage als tekst zonder nullen achteraan ({@code 1}, niet {@code 1.000000000000}); nooit afgerond. */
    private static String percent(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static String cap(String value) {
        return value == null || value.length() <= RAW_VALUE_MAX_LENGTH ? value
                : value.substring(0, RAW_VALUE_MAX_LENGTH);
    }

    // --- Het lezen -----------------------------------------------------------------------------------------------

    /** Voorbeelden per issuegroep (code + logisch veld), in leesvolgorde. */
    private static final class GroupExamples {
        private final String fieldName;
        private final List<IssueExample> examples = new ArrayList<>();

        private GroupExamples(String fieldName) {
            this.fieldName = fieldName;
        }
    }

    /**
     * Ontvangt de leesresultaten: stuurt elke regel door de gedeelde kern en houdt bij wat de proef toont
     * (voorbeeldrijen, voorbeelden per groep, header, identiteiten). Bewaart nooit een volledig bestand.
     */
    private final class TrialSink implements CsvRecordStreamer.Sink, RecordScreeningCore.Callbacks {

        private final SourceStructureConfig config;
        private final ImportMappingConfig mappingConfig;
        private final RecordScreeningCore core;
        private final IdentityTracker identities = new IdentityTracker(maxTrackedIdentities, maxIssueExamples);
        private final List<SampleBuilder> samples = new ArrayList<>();
        private final Map<String, GroupExamples> groupExamples = new HashMap<>();
        private List<String> headerColumns;
        private long linesWithReplacementCharacter;
        private long dataRecords;
        /** De voorbeeldrij in opbouw, of {@code null} wanneer de huidige regel geen voorbeeld wordt. */
        private SampleBuilder current;

        private TrialSink(SourceStructureConfig config, ImportMappingConfig mappingConfig) {
            this.config = config;
            this.mappingConfig = mappingConfig;
            this.core = new RecordScreeningCore(config, mappingConfig, this);
        }

        @Override
        public void header(long lineNumber, List<String> columns) {
            headerColumns = columns;
        }

        @Override
        public void physicalLine(long lineNumber, String line) {
            if (line.indexOf(REPLACEMENT_CHARACTER) >= 0) {
                linesWithReplacementCharacter++;
            }
        }

        @Override
        public void record(ParsedRow row) {
            dataRecords++;
            current = samples.size() < sampleRows ? SampleBuilder.parsed(row) : null;
            SampleBuilder building = current;
            core.record(row);
            current = null;
            // Pas ná een voltooide beslissing: een regel die de levering blokkeert, is geen voorbeeldrij.
            if (building != null) {
                samples.add(building);
            }
        }

        @Override
        public void issue(LineIssue issue) {
            if (issue.warning()) {
                // Header- of bestandswaarschuwing (BOM, verschoven of onbekende kolom): geen datarecord.
                core.lineIssue(issue);
                return;
            }
            // Een datalijn die niet te parsen is (kolomaantal, aanhalingsteken, te lang): wél een datarecord.
            dataRecords++;
            current = samples.size() < sampleRows ? SampleBuilder.unreadable(issue) : null;
            SampleBuilder building = current;
            core.lineIssue(issue);
            current = null;
            if (building != null) {
                samples.add(building);
            }
        }

        @Override
        public void onDecision(ParsedRow row, RecordFilterEvaluator.Decision decision) {
            if (current != null) {
                current.decision = decision;
            }
        }

        @Override
        public void onCandidate(ParsedRow row, NormalisedCandidate candidate) {
            if (current != null) {
                current.candidate = candidate;
            }
            long firstLine = identities.observe(candidate.identityHash(), row.lineNumber());
            if (firstLine >= 0 && current != null) {
                ImportIssueCatalog.IssueClassification classification =
                        ImportIssueCatalog.classify(ImportIssueCatalog.DUPLICATE_IDENTITY_IN_DELIVERY);
                current.issues.add(new SampleIssue(ImportIssueCatalog.DUPLICATE_IDENTITY_IN_DELIVERY,
                        classification.severity(), null, null, IdentityTracker.message(firstLine)));
            }
        }

        @Override
        public void onIssue(long rowNumber, String code, String fieldName, String sourceValue, String message,
                            RowIssueSeverity severity) {
            GroupExamples group = groupExamples.computeIfAbsent(
                    code + ' ' + IssueSignature.generic(fieldName).value(), key -> new GroupExamples(fieldName));
            if (group.examples.size() < maxIssueExamples) {
                group.examples.add(new IssueExample(rowNumber, fieldName, sourceValue, message));
            }
            if (current != null) {
                current.issues.add(new SampleIssue(code, severity, fieldName, sourceValue, message));
            }
        }
    }

    /** Eén voorbeeldrij in opbouw; de kern vult beslissing, kandidaat en meldingen aan. */
    private static final class SampleBuilder {
        private final long lineNumber;
        private final ParsedRow row;
        private final LineIssue unreadable;
        private RecordFilterEvaluator.Decision decision;
        private NormalisedCandidate candidate;
        private final List<SampleIssue> issues = new ArrayList<>();

        private SampleBuilder(long lineNumber, ParsedRow row, LineIssue unreadable) {
            this.lineNumber = lineNumber;
            this.row = row;
            this.unreadable = unreadable;
        }

        private static SampleBuilder parsed(ParsedRow row) {
            return new SampleBuilder(row.lineNumber(), row, null);
        }

        private static SampleBuilder unreadable(LineIssue issue) {
            return new SampleBuilder(issue.lineNumber(), null, issue);
        }

        private SampleRow build(SourceStructureConfig config, ImportMappingConfig mappingConfig) {
            if (row == null) {
                return new SampleRow(lineNumber, SampleStatus.UNREADABLE, null, unreadable.sourceValue(), null, null,
                        List.copyOf(issues));
            }
            List<String> rawValues = row.values().stream().map(TrialReadService::cap).toList();
            SampleFilter filter = decision == null ? null
                    : new SampleFilter(decision.kind(), decision.decidingSequenceNumber());
            SampleStatus status;
            Interpreted interpreted = null;
            if (decision != null && decision.kind() == RecordFilterEvaluator.Decision.Kind.FILTERED_OUT) {
                status = SampleStatus.FILTERED_OUT;
            } else if (candidate != null) {
                status = SampleStatus.VALID;
                interpreted = interpreted(config, mappingConfig);
            } else {
                status = SampleStatus.REJECTED;
            }
            return new SampleRow(lineNumber, status, rawValues, null, interpreted, filter, List.copyOf(issues));
        }

        private Interpreted interpreted(SourceStructureConfig config, ImportMappingConfig mappingConfig) {
            Integer pricePosition = row.positions().position(config.basePriceField());
            String basePriceRaw = pricePosition == null ? null : row.value(pricePosition);
            // Dezelfde mapping als de normaliser zonet deed (puur en deterministisch); enkel voor de voorbeeldrijen.
            Map<String, String> mapped = new LinkedHashMap<>(new FieldValueMapper().map(row, mappingConfig).values());
            List<PriceComponentView> components = new ArrayList<>();
            for (PriceRules.PriceComponent component : candidate.priceComponents()) {
                components.add(new PriceComponentView(component.componentCode(),
                        component.sourceAmount() == null ? null : component.sourceAmount().toPlainString(),
                        component.percentage() == null ? null : component.percentage().toPlainString(),
                        component.currency(), component.status() == null ? null : component.status().name()));
            }
            List<ReferenceView> references = new ArrayList<>();
            for (CandidateNormaliser.ReferenceValue reference : candidate.references()) {
                references.add(new ReferenceView(reference.referenceType(), reference.valueRaw(),
                        reference.valueNormalised()));
            }
            return new Interpreted(candidate.supplier(), candidate.supplierGroup(), candidate.supplierReference(),
                    candidate.discountCode(),
                    candidate.discountState() == null ? null : candidate.discountState().name(),
                    HexFormat.of().formatHex(candidate.identityHash()), basePriceRaw,
                    candidate.basePrice().toPlainString(), candidate.basePriceCurrency(),
                    candidate.basePriceCurrencyOrigin(), candidate.description(),
                    java.util.Collections.unmodifiableMap(mapped), List.copyOf(components), List.copyOf(references));
        }
    }

    /**
     * Dubbele aanbiedingsidentiteiten in het bestand, met hetzelfde telvoorschrift als
     * {@code CandidateStageDao.countDuplicateRows}: over de geldige kandidaten binnen de scope, inclusief de eerste
     * voorkomst van elke herhaalde identiteit. Hoogstens {@code limit} identiteiten worden gevolgd; daarboven worden
     * nieuwe identiteiten niet meer onthouden (bestaande blijven herkend) en is het aantal niet meer exact.
     */
    private static final class IdentityTracker {

        /** Een SHA-256-identiteitshash als vier longs: compacter dan een {@code byte[]}-sleutel. */
        private record Key(long a, long b, long c, long d) {
            private static Key of(byte[] hash) {
                ByteBuffer buffer = ByteBuffer.wrap(hash.length >= 32 ? hash : java.util.Arrays.copyOf(hash, 32));
                return new Key(buffer.getLong(), buffer.getLong(), buffer.getLong(), buffer.getLong());
            }
        }

        private final int limit;
        private final int maxExamples;
        /** Per identiteit: {eerste regel, aantal voorkomsten}. */
        private final Map<Key, long[]> seen = new HashMap<>();
        private final List<IssueExample> examples = new ArrayList<>();
        private long duplicateRows;
        /** Het aantal latere voorkomsten (voorkomsten min de eerste van elke herhaalde identiteit). */
        private long repeats;
        private boolean limitReached;

        private IdentityTracker(int limit, int maxExamples) {
            this.limit = limit;
            this.maxExamples = maxExamples;
        }

        /** @return de regel van de eerste voorkomst als dit een herhaling is, anders {@code -1} */
        private long observe(byte[] identityHash, long lineNumber) {
            Key key = Key.of(identityHash);
            long[] entry = seen.get(key);
            if (entry != null) {
                entry[1]++;
                duplicateRows += entry[1] == 2 ? 2 : 1;
                repeats++;
                if (examples.size() < maxExamples) {
                    examples.add(new IssueExample(lineNumber, null, null, message(entry[0])));
                }
                return entry[0];
            }
            if (seen.size() >= limit) {
                limitReached = true;
                return -1;
            }
            seen.put(key, new long[] {lineNumber, 1});
            return -1;
        }

        private static String message(long firstLine) {
            return "Offer identity occurs more than once in this delivery; first occurrence on line " + firstLine;
        }
    }

    /** Telt de bytes en berekent de SHA-256 terwijl de streamer leest; buffert niets. */
    private static final class DigestingInputStream extends FilterInputStream {

        private final MessageDigest digest;
        private long count;

        private DigestingInputStream(InputStream delegate) {
            super(delegate);
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException impossible) {
                throw new UncheckedIOException(new IOException("SHA-256 is not available", impossible));
            }
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                digest.update((byte) value);
                count++;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                digest.update(buffer, offset, read);
                count += read;
            }
            return read;
        }

        /** Overslaan zou bytes buiten de hash houden; er wordt dus gelezen in plaats van overgeslagen. */
        @Override
        public long skip(long requested) throws IOException {
            byte[] buffer = new byte[(int) Math.min(Math.max(requested, 0), 64 * 1024)];
            long skipped = 0;
            while (skipped < requested) {
                int read = read(buffer, 0, (int) Math.min(buffer.length, requested - skipped));
                if (read < 0) {
                    break;
                }
                skipped += read;
            }
            return skipped;
        }

        @Override
        public void close() {
            // Nooit de onderliggende stream sluiten: dat is de zaak van de aanroeper.
        }

        /** Leest de rest van het bestand door de hash (ook na een blokkade), zodat grootte en hash volledig zijn. */
        private void drain() {
            byte[] buffer = new byte[64 * 1024];
            try {
                while (read(buffer, 0, buffer.length) >= 0) {
                    // enkel tellen en hashen
                }
            } catch (IOException failure) {
                throw new UncheckedIOException("Cannot read the uploaded file", failure);
            }
        }

        private long count() {
            return count;
        }

        private String sha256Hex() {
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
