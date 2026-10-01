package be.dda.catalogimport.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.dao.IssueGroupDao.Counter;
import be.dda.catalogimport.domain.Criticality;
import be.dda.catalogimport.domain.FilterNullBehaviour;
import be.dda.catalogimport.domain.FilterOperator;
import be.dda.catalogimport.domain.FilterOutcome;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.MissingColumnBehaviour;
import be.dda.catalogimport.domain.RowIssueSeverity;
import be.dda.catalogimport.service.support.CandidateNormaliser.NormalisedCandidate;
import be.dda.catalogimport.service.support.CsvRecordStreamer.LineIssue;
import be.dda.catalogimport.service.support.CsvRecordStreamer.ParsedRow;
import be.dda.catalogimport.service.support.CsvRecordStreamer.ReadSummary;
import be.dda.catalogimport.service.support.ImportMappingConfig.RecordFilter;
import be.dda.catalogimport.service.support.SourceStructureConfig.FieldReferenceKind;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * NT-9 (contract {@code docs/design/proefinlezing-design.md} par. 4 en 7): de gedeelde beslisboom en tellerregels
 * van de screening, zonder database. Dezelfde klasse draait in {@code DeliveryScreeningService} (adapter
 * {@code StagingSink}) en in {@code TrialReadService}; de bestaande screeningtests bewijzen dat de screening
 * ongewijzigd is, deze test bewijst de regels zelf.
 *
 * <ul>
 *   <li><b>Regel:</b> filter vóór normaliseren; uitgefilterd is geen fout; een REJECT-filter is een ERROR die geen
 *       kritieke lijn is. <b>Implementatie:</b> {@link RecordScreeningCore#record}.</li>
 *   <li><b>Regel:</b> een fout vóór het filter telt enkel met geconfigureerde filters als
 *       {@code error_before_filter}, anders als verworpen (R-FLT-04).</li>
 *   <li><b>Regel:</b> kritieke lijn = ERROR op een kritieke of onbekende kolom, per regel één keer.</li>
 *   <li><b>Data:</b> reconciliatie {@code raw = valid + rejected + errorBeforeFilter + filteredOut}.</li>
 * </ul>
 */
class RecordScreeningCoreTest {

    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING";

    // --- Normaal scenario ---------------------------------------------------------------------------------------

    @Test
    void validRowsAreCandidatesInScopeWithoutAnyIssue() {
        Recorder recorder = new Recorder();
        RecordScreeningCore core = core(mapping(List.of()), recorder);

        read(core, recorder, HEADER + "\nACME;G1;R1;1,50;Hamer\nACME;G1;R2;2,25;Zaag\n");

        assertThat(core.validCount()).isEqualTo(2);
        assertThat(core.rejectedCount()).isZero();
        assertThat(core.filteredOutCount()).isZero();
        assertThat(core.errorBeforeFilterCount()).isZero();
        assertThat(core.criticalLineCount()).isZero();
        assertThat(core.tally().isEmpty()).isTrue();
        assertThat(recorder.candidates).extracting(NormalisedCandidate::supplierReference).containsExactly("R1", "R2");
        assertThat(recorder.candidates.get(0).basePrice()).isEqualByComparingTo("1.50");
        assertThat(recorder.decisions).containsExactly("2:IN_SCOPE", "3:IN_SCOPE");
        assertThat(recorder.issues).isEmpty();
    }

    // --- Ongeldige input -------------------------------------------------------------------------------------------

    @Test
    void anUnreadablePriceRejectsTheLineAndCountsACriticalLineBecauseThePriceColumnIsCritical() {
        Recorder recorder = new Recorder();
        RecordScreeningCore core = core(mapping(List.of()), recorder);

        read(core, recorder, HEADER + "\nACME;G1;R1;abc;Hamer\nACME;G1;R2;2,25;Zaag\n");

        assertThat(core.validCount()).isEqualTo(1);
        assertThat(core.rejectedCount()).isEqualTo(1);
        assertThat(core.criticalLineCount()).isEqualTo(1);
        assertThat(recorder.issues).containsExactly("2:" + ImportValueRules.CODE_PRICE_UNREADABLE + ":PRIJS:ERROR");
        List<Counter> tally = core.tally().drain();
        assertThat(tally).singleElement().satisfies(counter -> {
            assertThat(counter.issueCode()).isEqualTo(ImportValueRules.CODE_PRICE_UNREADABLE);
            assertThat(counter.signature()).isEqualTo("FIELD=PRIJS");
            assertThat(counter.occurrences()).isEqualTo(1);
            assertThat(counter.firstRowNumber()).isEqualTo(2L);
        });
    }

    @Test
    void anErrorOnANonCriticalColumnIsRejectedButNeverACriticalLine() {
        Recorder recorder = new Recorder();
        RecordScreeningCore core = core(mapping(List.of()), recorder);

        read(core, recorder, HEADER + "\nACME;G1;R1;1,50;" + "x".repeat(1001) + "\n");

        assertThat(core.rejectedCount()).isEqualTo(1);
        assertThat(core.criticalLineCount()).isZero();
        assertThat(recorder.issues).containsExactly("2:" + CandidateNormaliser.CODE_VALUE_TOO_LONG + ":OMSCHRIJVING:ERROR");
    }

    // --- Filters ---------------------------------------------------------------------------------------------------

    @Test
    void filtersDecideTheScopeBeforeAnythingElseAndTheCountersReconcile() {
        Recorder recorder = new Recorder();
        RecordScreeningCore core = core(mapping(List.of(
                filter(1, "GROEP", "MEET", FilterOutcome.EXCLUDE),
                filter(2, "GROEP", "WEG", FilterOutcome.REJECT))), recorder);

        ReadSummary summary = read(core, recorder, HEADER + "\n"
                + "ACME;G1;R1;1,50;Hamer\n"      // geldig
                + "ACME;MEET;R2;abc;Meetlint\n"  // uitgefilterd: de onleesbare prijs wordt nooit bekeken
                + "ACME;WEG;R3;1,00;Weg\n"       // REJECT-filter: verworpen, geen kritieke lijn
                + "ACME;G1;R4\n"                 // kolomaantal: fout vóór het filter, fail-safe kritiek
                + "ACME;G1;R5;abc;Zaag\n");      // onleesbare prijs in scope: verworpen en kritiek

        assertThat(summary.rawRecordCount()).isEqualTo(5);
        assertThat(core.validCount()).isEqualTo(1);
        assertThat(core.filteredOutCount()).isEqualTo(1);
        assertThat(core.rejectedCount()).isEqualTo(2);
        assertThat(core.errorBeforeFilterCount()).isEqualTo(1);
        assertThat(core.criticalLineCount()).isEqualTo(2);
        assertThat(summary.rawRecordCount()).isEqualTo(core.validCount() + core.rejectedCount()
                + core.errorBeforeFilterCount() + core.filteredOutCount());
        assertThat(recorder.decisions).containsExactly("2:IN_SCOPE", "3:FILTERED_OUT", "4:REJECTED", "6:IN_SCOPE");
        assertThat(recorder.issues).containsExactly(
                "4:" + RecordFilterEvaluator.CODE_FILTER_RECORD_REJECTED + ":GROEP:ERROR",
                "5:" + CsvRecordStreamer.CODE_ROW_COLUMN_COUNT_MISMATCH + ":null:ERROR",
                "6:" + ImportValueRules.CODE_PRICE_UNREADABLE + ":PRIJS:ERROR");
    }

    @Test
    void withoutFiltersAnUnreadableLineCountsAsRejectedNotAsAnErrorBeforeTheFilter() {
        Recorder recorder = new Recorder();
        RecordScreeningCore core = core(mapping(List.of()), recorder);

        read(core, recorder, HEADER + "\nACME;G1;R4\n");

        assertThat(core.rejectedCount()).isEqualTo(1);
        assertThat(core.errorBeforeFilterCount()).isZero();
        assertThat(core.criticalLineCount()).isEqualTo(1);
    }

    @Test
    void aMissingFilterColumnOnBlockBlocksTheDeliveryAndNothingIsCounted() {
        Recorder recorder = new Recorder();
        RecordScreeningCore core = core(mapping(List.of(new RecordFilter(1, "CULTURE", FilterOperator.EQUALS,
                "BENL", FilterOutcome.INCLUDE, false, true, FilterNullBehaviour.EXCLUDE,
                MissingColumnBehaviour.BLOCK))), recorder);

        assertThatThrownBy(() -> read(core, recorder, HEADER + "\nACME;G1;R1;1,50;Hamer\n"))
                .isInstanceOfSatisfying(ScreeningBlockedException.class, blocked -> assertThat(blocked.getCode())
                        .isEqualTo(RecordFilterEvaluator.CODE_FILTER_COLUMN_MISSING));
        assertThat(core.validCount()).isZero();
        assertThat(recorder.candidates).isEmpty();
    }

    // --- Waarschuwingen, dubbele fouten en volgorde van de callbacks --------------------------------------------------

    @Test
    void aWarningIsTalliedButNeverCountedAsRejectedOrCritical() {
        Recorder recorder = new Recorder();
        RecordScreeningCore core = core(mapping(List.of()), recorder);

        read(core, recorder, "﻿" + HEADER + "\nACME;G1;R1;1,50;Hamer\n");

        assertThat(core.validCount()).isEqualTo(1);
        assertThat(core.rejectedCount()).isZero();
        assertThat(core.criticalLineCount()).isZero();
        assertThat(recorder.issues).containsExactly("1:" + CsvRecordStreamer.CODE_SOURCE_BOM_REMOVED + ":null:WARNING");
        assertThat(core.tally().drain()).extracting(Counter::issueCode)
                .containsExactly(CsvRecordStreamer.CODE_SOURCE_BOM_REMOVED);
    }

    @Test
    void twoCriticalErrorsOnTheSameLineAreOneCriticalLineButTwoTalliedOccurrences() {
        Recorder recorder = new Recorder();
        RecordScreeningCore core = core(mapping(List.of()), recorder);

        core.addIssue(7, CsvRecordStreamer.CODE_ROW_COLUMN_COUNT_MISMATCH, null, "x", "m", false);
        core.addIssue(7, ImportValueRules.CODE_PRICE_UNREADABLE, "PRIJS", "abc", "m", false);

        assertThat(core.criticalLineCount()).isEqualTo(1);
        assertThat(core.rejectedCount()).isEqualTo(2);
        assertThat(core.tally().drain()).hasSize(2);
    }

    @Test
    void theCountersAreAlreadyUpdatedWhenOnIssueIsCalled() {
        List<Long> rejectedSeenInCallback = new ArrayList<>();
        RecordScreeningCore[] holder = new RecordScreeningCore[1];
        RecordScreeningCore core = new RecordScreeningCore(structure(), mapping(List.of()),
                new RecordScreeningCore.Callbacks() {
                    @Override
                    public void onCandidate(ParsedRow row, NormalisedCandidate candidate) {
                    }

                    @Override
                    public void onIssue(long rowNumber, String code, String fieldName, String sourceValue,
                                        String message, RowIssueSeverity severity) {
                        rejectedSeenInCallback.add(holder[0].rejectedCount());
                    }
                });
        holder[0] = core;

        core.addIssue(2, ImportValueRules.CODE_PRICE_UNREADABLE, "PRIJS", "abc", "m", false);

        assertThat(rejectedSeenInCallback).containsExactly(1L);
    }

    @Test
    void anUnknownIssueCodeIsAProgrammingErrorAndNeverAGuessedSeverity() {
        RecordScreeningCore core = core(mapping(List.of()), new Recorder());

        assertThatThrownBy(() -> core.addIssue(2, "NOT_IN_THE_CATALOGUE", null, null, "m", false))
                .isInstanceOf(IllegalStateException.class);
        assertThat(core.rejectedCount()).isZero();
    }

    // --- Helpers -----------------------------------------------------------------------------------------------------

    /** Legt de callbacks vast als leesbare tekst. */
    private static final class Recorder implements RecordScreeningCore.Callbacks, CsvRecordStreamer.Sink {
        private RecordScreeningCore core;
        private final List<NormalisedCandidate> candidates = new ArrayList<>();
        private final List<String> decisions = new ArrayList<>();
        private final List<String> issues = new ArrayList<>();

        @Override
        public void onDecision(ParsedRow row, RecordFilterEvaluator.Decision decision) {
            decisions.add(row.lineNumber() + ":" + decision.kind());
        }

        @Override
        public void onCandidate(ParsedRow row, NormalisedCandidate candidate) {
            candidates.add(candidate);
        }

        @Override
        public void onIssue(long rowNumber, String code, String fieldName, String sourceValue, String message,
                            RowIssueSeverity severity) {
            issues.add(rowNumber + ":" + code + ":" + fieldName + ":" + severity);
        }

        @Override
        public void record(ParsedRow row) {
            core.record(row);
        }

        @Override
        public void issue(LineIssue issue) {
            core.lineIssue(issue);
        }
    }

    private static RecordScreeningCore core(ImportMappingConfig mapping, Recorder recorder) {
        RecordScreeningCore core = new RecordScreeningCore(structure(), mapping, recorder);
        recorder.core = core;
        return core;
    }

    /** Leest via de echte streamer; de recorder stuurt elke regel door naar de kern die aan hem hangt. */
    private static ReadSummary read(RecordScreeningCore core, Recorder recorder, String content) {
        assertThat(recorder.core).isSameAs(core);
        return new CsvRecordStreamer().read(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                structure(), HeaderExpectations.none(), CsvRecordStreamer.DEFAULT_MAX_LINE_LENGTH, recorder);
    }

    private static SourceStructureConfig structure() {
        return new SourceStructureConfig("CSV", StandardCharsets.UTF_8, ';', '"', true, 1,
                FieldReferenceKind.HEADER_NAME, null, IdentityProfileKind.THREE_PART,
                "LEVERANCIER", "GROEP", "REFERENTIE", null, "PRIJS", "OMSCHRIJVING", 1);
    }

    /** De kritiek-vlaggen zoals de fabriek ze voor de revisievelden zet (prijs kritiek, omschrijving niet). */
    private static ImportMappingConfig mapping(List<RecordFilter> filters) {
        return new ImportMappingConfig(1, List.of(), filters, Map.of(
                "LEVERANCIER", Criticality.CRITICAL, "GROEP", Criticality.CRITICAL,
                "REFERENTIE", Criticality.CRITICAL, "PRIJS", Criticality.CRITICAL,
                "OMSCHRIJVING", Criticality.NON_CRITICAL));
    }

    private static RecordFilter filter(int sequence, String column, String value, FilterOutcome outcome) {
        return new RecordFilter(sequence, column, FilterOperator.EQUALS, value, outcome, false, true,
                FilterNullBehaviour.EXCLUDE, MissingColumnBehaviour.BLOCK);
    }
}
