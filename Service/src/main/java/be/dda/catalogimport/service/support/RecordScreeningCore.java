package be.dda.catalogimport.service.support;

import be.dda.catalogimport.domain.RowIssueSeverity;
import be.dda.catalogimport.service.support.CandidateNormaliser.NormalisedCandidate;
import be.dda.catalogimport.service.support.CsvRecordStreamer.LineIssue;
import be.dda.catalogimport.service.support.CsvRecordStreamer.ParsedRow;
import java.time.Instant;

/**
 * De gedeelde beslisboom per bronregel en de tellerregels van de screening (NT-9, contract
 * {@code docs/design/proefinlezing-design.md} par. 7), zodat de echte screening
 * ({@code DeliveryScreeningService}) en de proefinlezing ({@code TrialReadService}) per regel
 * <b>exact hetzelfde</b> beslissen en tellen. Uit {@code DeliveryScreeningService.StagingSink} en
 * {@code addIssue} gehaald zonder gedragswijziging.
 *
 * <h2>Beslisboom per geparste regel (R-FLT-02)</h2>
 * <ol>
 *   <li>recordfilter: {@code FILTERED_OUT} ⇒ enkel {@link #filteredOutCount()}; {@code REJECTED} ⇒ één
 *       {@code FILTER_RECORD_REJECTED}; {@code IN_SCOPE} ⇒ verder;</li>
 *   <li>normaliseren: een kandidaat ⇒ {@link #validCount()} + {@link Callbacks#onCandidate} en daarna de
 *       informatieve vaststellingen van die regel; een verwerping ⇒ één issue.</li>
 * </ol>
 * Een {@link ScreeningBlockedException} (filterkolom ontbreekt, veld niet oplosbaar, ...) wordt niet
 * opgevangen: ze blokkeert de volledige levering en is de zaak van de aanroeper.
 *
 * <h2>Tellerregels ({@link #addIssue})</h2>
 * De ernst komt altijd uit {@link ImportIssueCatalog}. Élk voorval telt in de {@link IssueTally} (ook
 * boven een voorbeeldcap). Een {@code ERROR} telt in {@link #errorBeforeFilterCount()} wanneer ze vóór
 * het filter ontstond én er filters geconfigureerd zijn, anders in {@link #rejectedCount()}. Elke melding
 * gaat langs de {@link CriticalLineCounter}. Wat er daarna met de melding gebeurt (voorbeeldcap, stagen,
 * tonen) beslist de aanroeper in {@link Callbacks#onIssue}.
 * <p>
 * Niet draadveilig; één instantie hoort bij één lezing van één bestand.
 */
public final class RecordScreeningCore {

    /** Wat de aanroeper met de uitkomst van één regel doet. */
    public interface Callbacks {

        /**
         * De recordfilterbeslissing van een geparste regel, vóór iets anders. Standaard zonder effect; de
         * proefinlezing toont ze bij haar voorbeeldrijen.
         */
        default void onDecision(ParsedRow row, RecordFilterEvaluator.Decision decision) {
        }

        /** Een geldige kandidaat binnen de importscope; de tellers zijn al bijgewerkt. */
        void onCandidate(ParsedRow row, NormalisedCandidate candidate);

        /**
         * Eén vastgestelde melding, nadat tally, tellers en kritieke lijnen bijgewerkt zijn.
         *
         * @param severity de ernst uit de catalogus
         */
        void onIssue(long rowNumber, String code, String fieldName, String sourceValue, String message,
                     RowIssueSeverity severity);
    }

    private final SourceStructureConfig config;
    private final ImportMappingConfig mappingConfig;
    private final RecordFilterEvaluator filters;
    private final CandidateNormaliser normaliser = new CandidateNormaliser();
    private final Callbacks callbacks;

    /**
     * Volledige aantallen per foutsignatuur (foutcode + logisch veld); loopt door boven elke voorbeeldcap.
     */
    private final IssueTally tally = new IssueTally();
    /** Ongecapt, ontdubbeld per regelnummer (3h-2, par. 15.1). */
    private final CriticalLineCounter criticalLines = new CriticalLineCounter();
    private long validCount;
    private long rejectedCount;
    private long filteredOutCount;
    private long errorBeforeFilterCount;

    /**
     * @param mappingConfig de gevalideerde veldmapping en recordfilters; nooit {@code null} voor een
     *                      lezing (de screening leest enkel met een geldige configuratie)
     */
    public RecordScreeningCore(SourceStructureConfig config, ImportMappingConfig mappingConfig,
                               Callbacks callbacks) {
        this.config = config;
        this.mappingConfig = mappingConfig;
        this.filters = new RecordFilterEvaluator(mappingConfig.filters());
        this.callbacks = callbacks;
    }

    /**
     * Beslist over één geparste regel: filter, normaliseren, geldig of verworpen.
     *
     * @throws ScreeningBlockedException wanneer de configuratie niet op dit bestand past
     */
    public void record(ParsedRow row) {
        RecordFilterEvaluator.Decision decision = filters.evaluate(row);
        callbacks.onDecision(row, decision);
        switch (decision.kind()) {
            case FILTERED_OUT -> {
                // Geen probleemrij: buiten de scope vallen is geen fout. Het aantal blijft wel
                // zichtbaar, zodat de reconciliatie van de tellers klopt.
                filteredOutCount++;
                return;
            }
            case REJECTED -> {
                addIssue(row.lineNumber(), RecordFilterEvaluator.CODE_FILTER_RECORD_REJECTED,
                        decision.fieldName(), decision.sourceValue(), decision.message(), false);
                return;
            }
            case IN_SCOPE -> {
                // Verder met de gewone recordcontroles.
            }
        }
        CandidateNormaliser.Result result = normaliser.normalise(row, config, mappingConfig);
        if (result instanceof NormalisedCandidate candidate) {
            validCount++;
            callbacks.onCandidate(row, candidate);
            // Informatieve vaststellingen (een toegepaste standaardwaarde) horen bij een geldige regel:
            // ze verwerpen niets, maar ze mogen ook niet onzichtbaar blijven.
            for (CandidateNormaliser.RowIssue notice : candidate.notices()) {
                addIssue(notice.rowNumber(), notice.code(), notice.fieldName(), notice.sourceValue(),
                        notice.message(), false);
            }
        } else if (result instanceof CandidateNormaliser.RowIssue rejected) {
            addIssue(rejected.rowNumber(), rejected.code(), rejected.fieldName(), rejected.sourceValue(),
                    rejected.message(), false);
        }
    }

    /**
     * Een probleem dat bij het lezen zelf ontstaat (kolomaantal, niet-gesloten aanhalingsteken, te lange
     * regel) of een waarschuwing over de header. Zo'n regel is niet parseerbaar en kan dus <b>niet</b>
     * aan de importscope toegewezen worden: met geconfigureerde filters telt ze in
     * {@code error_before_filter_count} en niet in {@code rejected_record_count}.
     */
    public void lineIssue(LineIssue issue) {
        addIssue(issue.lineNumber(), issue.code(), issue.fieldName(), issue.sourceValue(), issue.message(),
                true);
    }

    /**
     * Legt één vastgesteld probleem vast volgens de tellerregels hierboven en geeft het daarna door aan
     * {@link Callbacks#onIssue}.
     *
     * @param beforeFilter of dit probleem ontstond vóór het recordfilter kon draaien (R-FLT-04)
     */
    public void addIssue(long rowNumber, String code, String field, String sourceValue, String message,
                         boolean beforeFilter) {
        RowIssueSeverity severity = ImportIssueCatalog.classify(code).severity();
        // Élk voorval telt, ook het voorval waarvan geen voorbeeldrij bewaard wordt: het aantal in de
        // issuegroep is het werkelijke aantal en nooit het aantal bewaarde voorbeelden (R-ISS-03).
        tally.add(code, IssueSignature.generic(field), null, rowNumber, Instant.now());
        if (severity == RowIssueSeverity.ERROR) {
            if (beforeFilter && hasRecordFilters()) {
                errorBeforeFilterCount++;
            } else {
                rejectedCount++;
            }
        }
        // Kritieke lijn (3h-2, par. 15.1): vóór elke voorbeeldcap, zodat de teller ongecapt blijft.
        criticalLines.record(mappingConfig, rowNumber, severity, code, field);
        callbacks.onIssue(rowNumber, code, field, sourceValue, message, severity);
    }

    /** Zonder geconfigureerde recordfilters blijft het gedrag exact dat van fase 2 (R-FLT-04). */
    public boolean hasRecordFilters() {
        return mappingConfig != null && mappingConfig.hasFilters();
    }

    public long validCount() {
        return validCount;
    }

    public long rejectedCount() {
        return rejectedCount;
    }

    public long filteredOutCount() {
        return filteredOutCount;
    }

    public long errorBeforeFilterCount() {
        return errorBeforeFilterCount;
    }

    /** Het aantal kritieke lijnen tot nu toe; ongecapt. */
    public long criticalLineCount() {
        return criticalLines.count();
    }

    /** De lopende telling per foutsignatuur; de aanroeper legt ze vast of toont ze. */
    public IssueTally tally() {
        return tally;
    }
}
