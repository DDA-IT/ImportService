package be.dda.catalogimport.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.domain.FilterNullBehaviour;
import be.dda.catalogimport.domain.FilterOperator;
import be.dda.catalogimport.domain.FilterOutcome;
import be.dda.catalogimport.domain.MissingColumnBehaviour;
import be.dda.catalogimport.service.support.CsvRecordStreamer.ParsedRow;
import be.dda.catalogimport.service.support.ImportMappingConfig.RecordFilter;
import be.dda.catalogimport.service.support.RecordFilterEvaluator.Decision;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Fase 3b (ontwerp fase 3, R-FLT-01..R-FLT-03): het volledige gedrag van de recordfilters. Unittest
 * zonder Spring en zonder database — het filter bepaalt de importscope en moet dus ook los van een
 * levering hard en herhaalbaar zijn.
 * <p>
 * De importscope is geen schermfilter: wie hier een regel verkeerd beoordeelt, importeert ofwel een
 * deelcatalogus te veel ofwel te weinig, en dat werkt door in de creatiedrempel, de duplicaatcontrole
 * en later het volledigheidsbewijs.
 */
class RecordFilterEvaluatorTest {

    private static final String CULTURE = "CULTURE";
    private static final String STATUS = "STATUS";

    // --- De zes operatoren ----------------------------------------------------------------------

    @Test
    void appliesTheSixOperatorsCaseInsensitivelyByDefault() {
        assertThat(includes(FilterOperator.EQUALS, "BENL", "benl")).isTrue();
        assertThat(includes(FilterOperator.EQUALS, "BENL", "BEFR")).isFalse();
        assertThat(includes(FilterOperator.NOT_EQUALS, "BENL", "BEFR")).isTrue();
        assertThat(includes(FilterOperator.NOT_EQUALS, "BENL", "benl")).isFalse();
        assertThat(includes(FilterOperator.BEGINS_WITH, "BE", "benl")).isTrue();
        assertThat(includes(FilterOperator.BEGINS_WITH, "NL", "benl")).isFalse();
        assertThat(includes(FilterOperator.ENDS_WITH, "NL", "benl")).isTrue();
        assertThat(includes(FilterOperator.ENDS_WITH, "BE", "benl")).isFalse();
        assertThat(includes(FilterOperator.CONTAINS, "EN", "benl")).isTrue();
        assertThat(includes(FilterOperator.CONTAINS, "XX", "benl")).isFalse();
        assertThat(includes(FilterOperator.NOT_CONTAINS, "XX", "benl")).isTrue();
        assertThat(includes(FilterOperator.NOT_CONTAINS, "EN", "benl")).isFalse();
    }

    @Test
    void comparesCaseSensitivelyWhenTheFilterSaysSo() {
        RecordFilter sensitive = filter(1, CULTURE, FilterOperator.EQUALS, "BENL", FilterOutcome.INCLUDE,
                true, true, FilterNullBehaviour.EXCLUDE, MissingColumnBehaviour.BLOCK);

        assertThat(evaluate(row("BENL"), sensitive).inScope()).isTrue();
        assertThat(evaluate(row("benl"), sensitive).inScope()).isFalse();
    }

    @Test
    void trimsTheSourceValueAndTheCompareValueOnlyWhenConfigured() {
        RecordFilter trimming = filter(1, CULTURE, FilterOperator.EQUALS, " BENL ", FilterOutcome.INCLUDE,
                false, true, FilterNullBehaviour.EXCLUDE, MissingColumnBehaviour.BLOCK);
        RecordFilter literal = filter(1, CULTURE, FilterOperator.EQUALS, "BENL", FilterOutcome.INCLUDE,
                false, false, FilterNullBehaviour.EXCLUDE, MissingColumnBehaviour.BLOCK);

        assertThat(evaluate(row("  BENL  "), trimming).inScope()).isTrue();
        // Zonder trimmen is '  BENL  ' letterlijk een andere waarde; er wordt niets stil weggepoetst.
        assertThat(evaluate(row("  BENL  "), literal).inScope()).isFalse();
        assertThat(evaluate(row("BENL"), literal).inScope()).isTrue();
    }

    // --- Lege en ontbrekende waarden -------------------------------------------------------------

    @Test
    void treatsAnEmptyValueAccordingToTheConfiguredNullBehaviour() {
        RecordFilter excluding = filter(1, CULTURE, FilterOperator.EQUALS, "BENL", FilterOutcome.INCLUDE,
                false, true, FilterNullBehaviour.EXCLUDE, MissingColumnBehaviour.BLOCK);
        RecordFilter rejecting = filter(1, CULTURE, FilterOperator.EQUALS, "BENL", FilterOutcome.INCLUDE,
                false, true, FilterNullBehaviour.REJECT, MissingColumnBehaviour.BLOCK);
        RecordFilter comparing = filter(1, STATUS, FilterOperator.NOT_EQUALS, "EOL", FilterOutcome.INCLUDE,
                false, true, FilterNullBehaviour.COMPARE_AS_EMPTY, MissingColumnBehaviour.BLOCK);

        assertThat(evaluate(row(""), excluding).kind()).isEqualTo(Decision.Kind.FILTERED_OUT);
        assertThat(evaluate(row("   "), excluding).kind()).isEqualTo(Decision.Kind.FILTERED_OUT);
        assertThat(evaluate(row(""), rejecting).kind()).isEqualTo(Decision.Kind.REJECTED);
        // COMPARE_AS_EMPTY vergelijkt '' gewoon: een record zonder status is niet EOL en blijft in scope.
        assertThat(evaluate(statusRow(""), comparing).inScope()).isTrue();
        assertThat(evaluate(statusRow("EOL"), comparing).kind()).isEqualTo(Decision.Kind.FILTERED_OUT);
    }

    /** De kolom bestaat, maar deze regel is korter: dat is een ontbrekende waarde, geen ontbrekende kolom. */
    @Test
    void treatsAValueBeyondTheWidthOfThisRowAsAnEmptyValue() {
        RecordFilter excluding = filter(1, CULTURE, FilterOperator.EQUALS, "BENL", FilterOutcome.INCLUDE,
                false, true, FilterNullBehaviour.EXCLUDE, MissingColumnBehaviour.BLOCK);
        ParsedRow shortRow = new ParsedRow(7, List.of("ACME"),
                new SourceFieldPositions(Map.of(CULTURE, 1)));

        assertThat(evaluate(shortRow, excluding).kind()).isEqualTo(Decision.Kind.FILTERED_OUT);
    }

    // --- Ontbrekende kolom -----------------------------------------------------------------------

    @Test
    void blocksTheWholeDeliveryWhenTheFilterColumnIsMissingAndTheRuleSaysBlock() {
        RecordFilter blocking = filter(1, CULTURE, FilterOperator.EQUALS, "BENL", FilterOutcome.INCLUDE,
                false, true, FilterNullBehaviour.EXCLUDE, MissingColumnBehaviour.BLOCK);
        ParsedRow withoutColumn = new ParsedRow(2, List.of("ACME", "G1"),
                new SourceFieldPositions(Map.of("LEVERANCIER", 0)));

        assertThatThrownBy(() -> evaluate(withoutColumn, blocking))
                .isInstanceOf(ScreeningBlockedException.class)
                .extracting(failure -> ((ScreeningBlockedException) failure).getCode())
                .isEqualTo(RecordFilterEvaluator.CODE_FILTER_COLUMN_MISSING);
    }

    @Test
    void appliesTheConfiguredAlternativeWhenTheFilterColumnIsMissing() {
        ParsedRow withoutColumn = new ParsedRow(2, List.of("ACME"),
                new SourceFieldPositions(Map.of("LEVERANCIER", 0)));
        RecordFilter excluding = filter(1, CULTURE, FilterOperator.EQUALS, "BENL", FilterOutcome.INCLUDE,
                false, true, FilterNullBehaviour.EXCLUDE, MissingColumnBehaviour.EXCLUDE);
        RecordFilter rejecting = filter(1, CULTURE, FilterOperator.EQUALS, "BENL", FilterOutcome.INCLUDE,
                false, true, FilterNullBehaviour.EXCLUDE, MissingColumnBehaviour.REJECT);

        assertThat(evaluate(withoutColumn, excluding).kind()).isEqualTo(Decision.Kind.FILTERED_OUT);
        assertThat(evaluate(withoutColumn, rejecting).kind()).isEqualTo(Decision.Kind.REJECTED);
        // Er bestaat geen uitkomst die neerkomt op "het filter matcht dan maar niet" (R-FLT-03).
        assertThat(evaluate(withoutColumn, excluding).inScope()).isFalse();
    }

    // --- Meerdere filterrijen --------------------------------------------------------------------

    @Test
    void keepsARecordInScopeWhenAtLeastOneIncludeRuleMatches() {
        RecordFilter benl = filter(1, CULTURE, FilterOperator.EQUALS, "BENL", FilterOutcome.INCLUDE,
                false, true, FilterNullBehaviour.EXCLUDE, MissingColumnBehaviour.BLOCK);
        RecordFilter befr = filter(2, CULTURE, FilterOperator.EQUALS, "BEFR", FilterOutcome.INCLUDE,
                false, true, FilterNullBehaviour.EXCLUDE, MissingColumnBehaviour.BLOCK);

        assertThat(evaluate(row("BENL"), benl, befr).inScope()).isTrue();
        assertThat(evaluate(row("BEFR"), benl, befr).inScope()).isTrue();
        Decision outside = evaluate(row("NLNL"), benl, befr);
        assertThat(outside.kind()).isEqualTo(Decision.Kind.FILTERED_OUT);
        assertThat(outside.message()).contains("No include filter");
    }

    @Test
    void excludesARecordThatMatchesAnExcludeRuleEvenWhenAnIncludeRuleMatchesToo() {
        RecordFilter include = filter(1, CULTURE, FilterOperator.EQUALS, "BENL", FilterOutcome.INCLUDE,
                false, true, FilterNullBehaviour.EXCLUDE, MissingColumnBehaviour.BLOCK);
        RecordFilter exclude = filter(2, STATUS, FilterOperator.EQUALS, "EOL", FilterOutcome.EXCLUDE,
                false, true, FilterNullBehaviour.COMPARE_AS_EMPTY, MissingColumnBehaviour.BLOCK);

        assertThat(evaluate(row("BENL", "ACTIVE"), include, exclude).inScope()).isTrue();
        Decision excluded = evaluate(row("BENL", "EOL"), include, exclude);
        assertThat(excluded.kind()).isEqualTo(Decision.Kind.FILTERED_OUT);
        assertThat(excluded.decidingSequenceNumber()).isEqualTo(2);
    }

    @Test
    void rejectsARecordThatMatchesARejectRuleSoItStaysVisible() {
        RecordFilter include = filter(1, CULTURE, FilterOperator.EQUALS, "BENL", FilterOutcome.INCLUDE,
                false, true, FilterNullBehaviour.EXCLUDE, MissingColumnBehaviour.BLOCK);
        RecordFilter reject = filter(2, STATUS, FilterOperator.EQUALS, "ONBEKEND", FilterOutcome.REJECT,
                false, true, FilterNullBehaviour.COMPARE_AS_EMPTY, MissingColumnBehaviour.BLOCK);

        Decision rejected = evaluate(row("BENL", "ONBEKEND"), include, reject);
        assertThat(rejected.kind()).isEqualTo(Decision.Kind.REJECTED);
        assertThat(rejected.decidingSequenceNumber()).isEqualTo(2);
        assertThat(rejected.message()).contains(STATUS).contains("ONBEKEND");
    }

    /** De eerste rij met een eindbeslissing wint, zodat de uitkomst altijd herleidbaar is. */
    @Test
    void letsTheFirstDecidingRuleInSequenceOrderWin() {
        RecordFilter excludeFirst = filter(1, STATUS, FilterOperator.EQUALS, "EOL", FilterOutcome.EXCLUDE,
                false, true, FilterNullBehaviour.COMPARE_AS_EMPTY, MissingColumnBehaviour.BLOCK);
        RecordFilter rejectSecond = filter(2, STATUS, FilterOperator.BEGINS_WITH, "EO", FilterOutcome.REJECT,
                false, true, FilterNullBehaviour.COMPARE_AS_EMPTY, MissingColumnBehaviour.BLOCK);

        assertThat(evaluate(row("BENL", "EOL"), excludeFirst, rejectSecond).kind())
                .isEqualTo(Decision.Kind.FILTERED_OUT);
        assertThat(evaluate(row("BENL", "EOL"), rejectSecond, excludeFirst).kind())
                .isEqualTo(Decision.Kind.REJECTED);
    }

    @Test
    void keepsEveryRecordInScopeWhenThereIsNoFilterAtAll() {
        RecordFilterEvaluator evaluator = new RecordFilterEvaluator(List.of());

        assertThat(evaluator.isEmpty()).isTrue();
        assertThat(evaluator.evaluate(row("BENL")).inScope()).isTrue();
        assertThat(evaluator.evaluate(row("")).inScope()).isTrue();
    }

    /** Zonder INCLUDE-rijen is alles in scope wat niet uitgesloten of verworpen wordt. */
    @Test
    void keepsARecordInScopeWhenOnlyExcludeRulesExistAndNoneMatch() {
        RecordFilter exclude = filter(1, STATUS, FilterOperator.EQUALS, "EOL", FilterOutcome.EXCLUDE,
                false, true, FilterNullBehaviour.COMPARE_AS_EMPTY, MissingColumnBehaviour.BLOCK);

        assertThat(evaluate(row("NLNL", "ACTIVE"), exclude).inScope()).isTrue();
        assertThat(evaluate(row("NLNL", "EOL"), exclude).inScope()).isFalse();
    }

    // --- Helpers ----------------------------------------------------------------------------------

    private static boolean includes(FilterOperator operator, String compareValue, String sourceValue) {
        return evaluate(row(sourceValue), filter(1, CULTURE, operator, compareValue, FilterOutcome.INCLUDE,
                false, true, FilterNullBehaviour.EXCLUDE, MissingColumnBehaviour.BLOCK)).inScope();
    }

    private static Decision evaluate(ParsedRow row, RecordFilter... filters) {
        return new RecordFilterEvaluator(List.of(filters)).evaluate(row);
    }

    private static ParsedRow row(String culture) {
        return row(culture, "ACTIVE");
    }

    private static ParsedRow row(String culture, String status) {
        return new ParsedRow(2, List.of("ACME", culture, status),
                new SourceFieldPositions(Map.of("LEVERANCIER", 0, CULTURE, 1, STATUS, 2)));
    }

    private static ParsedRow statusRow(String status) {
        return row("BENL", status);
    }

    private static RecordFilter filter(int sequenceNumber, String reference, FilterOperator operator,
                                       String compareValue, FilterOutcome outcome, boolean caseSensitive,
                                       boolean trim, FilterNullBehaviour nullBehaviour,
                                       MissingColumnBehaviour missingColumnBehaviour) {
        return new RecordFilter(sequenceNumber, reference, operator, compareValue, outcome, caseSensitive,
                trim, nullBehaviour, missingColumnBehaviour);
    }
}
