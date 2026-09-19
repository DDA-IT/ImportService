package be.dda.catalogimport.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.service.support.PriceDeviationEvaluator.Reference;
import be.dda.catalogimport.service.support.PriceDeviationEvaluator.ReferenceKind;
import be.dda.catalogimport.service.support.PriceDeviationEvaluator.ReferenceOutcome;
import be.dda.catalogimport.service.support.PriceDeviationEvaluator.Result;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Fase 3e (ontwerp fase 3, R-PRI-10..R-PRI-12): de rekenkern van de prijsafwijkingscontrole, zonder
 * Spring en zonder database.
 * <p>
 * <b>Financieel wat hier bewezen wordt.</b> Exact op de grens is <i>niet</i> overschreden; één
 * biljoenste erboven wél. Een ontbrekende of nul-referentie levert géén berekening op (geen deling
 * door nul, geen verzonnen 0%). Elke uitkomst is een {@link BigDecimal} op schaal 12 en nooit een
 * afgeronde of drijvendekommawaarde. De melding draagt alle vier de gegevens die par. 15.12 eist:
 * veldnaam, oude en nieuwe waarde, de afwijking per referentie en de ingestelde grens.
 */
class PriceDeviationEvaluatorTest {

    private static final String FIELD = "Basisprijs";
    private static final BigDecimal LIMIT_15 = new BigDecimal("15.000000000000");

    // --- De grens (R-PRI-10, grensgeval) ------------------------------------------------------

    @Test
    void treatsADeviationExactlyOnTheLimitAsNotExceeded() {
        // 115,00 van 100,00 is exact 15,000000000000%.
        Result result = evaluate(amount("115.000000"), amount("100.000000"), null, null);

        assertThat(deviation(result, ReferenceKind.PREVIOUS)).isEqualTo("15.000000000000");
        assertThat(outcome(result, ReferenceKind.PREVIOUS).exceeded()).isFalse();
        assertThat(result.exceeded()).isFalse();
    }

    @Test
    void treatsTheSmallestPossibleStepAboveTheLimitAsExceeded() {
        // 100 x 15,000000000001% => een afwijking van precies één biljoenste boven de grens.
        Result result = evaluate(amount("115.000000000001"), amount("100.000000"), null, null);

        assertThat(deviation(result, ReferenceKind.PREVIOUS)).isEqualTo("15.000000000001");
        assertThat(result.exceeded()).isTrue();
    }

    /** De grens geldt op de absolute afwijking: een prijsdaling telt even zwaar als een stijging. */
    @Test
    void treatsANegativeDeviationBeyondTheLimitAsExceeded() {
        Result result = evaluate(amount("80.000000"), amount("100.000000"), null, null);

        assertThat(deviation(result, ReferenceKind.PREVIOUS)).isEqualTo("-20.000000000000");
        assertThat(result.exceeded()).isTrue();
        assertThat(result.message()).contains("-20.000000000000% from the previous accepted value");
    }

    @Test
    void treatsANegativeDeviationExactlyOnTheLimitAsNotExceeded() {
        Result result = evaluate(amount("85.000000"), amount("100.000000"), null, null);

        assertThat(deviation(result, ReferenceKind.PREVIOUS)).isEqualTo("-15.000000000000");
        assertThat(result.exceeded()).isFalse();
    }

    // --- Ontbrekende, nul- en onbruikbare referenties (R-PRI-11) -------------------------------

    @Test
    void computesNothingForAnAbsentReferenceAndNamesTheStatusInstead() {
        Result result = evaluate(amount("115.000000"), null, null, null);

        for (ReferenceKind kind : ReferenceKind.values()) {
            ReferenceOutcome outcome = outcome(result, kind);
            assertThat(outcome.available()).as("%s", kind).isFalse();
            assertThat(outcome.deviationPercent()).as("%s", kind).isNull();
            assertThat(outcome.exceeded()).as("%s", kind).isFalse();
            assertThat(outcome.status()).as("%s", kind).isEqualTo(kind.name() + "_NOT_AVAILABLE");
        }
        assertThat(result.exceeded()).isFalse();
        assertThat(outcome(result, ReferenceKind.PREVIOUS).status()).isEqualTo("PREVIOUS_NOT_AVAILABLE");
        assertThat(outcome(result, ReferenceKind.AVG50).status()).isEqualTo("AVG50_NOT_AVAILABLE");
        assertThat(outcome(result, ReferenceKind.AVG200).status()).isEqualTo("AVG200_NOT_AVAILABLE");
    }

    /** Een referentie van 0 is geen referentie: delen door nul bestaat hier niet. */
    @Test
    void computesNothingForAZeroReferenceInsteadOfDividingByZero() {
        Result result = evaluate(amount("115.000000"), BigDecimal.ZERO, amount("0.000000"), null);

        assertThat(outcome(result, ReferenceKind.PREVIOUS).status()).isEqualTo("PREVIOUS_NOT_AVAILABLE");
        assertThat(outcome(result, ReferenceKind.PREVIOUS).deviationPercent()).isNull();
        assertThat(outcome(result, ReferenceKind.AVG50).status()).isEqualTo("AVG50_NOT_AVAILABLE");
        assertThat(result.exceeded()).isFalse();
    }

    @Test
    void refusesToEvaluateWithoutANewAmount() {
        assertThatThrownBy(() -> PriceDeviationEvaluator.evaluate("BASE_PRICE", FIELD, null,
                Reference.previous(amount("100.000000")),
                new Reference(ReferenceKind.AVG50, null, 50),
                new Reference(ReferenceKind.AVG200, null, 200), LIMIT_15))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- De drie referenties afzonderlijk (R-PRI-10) -------------------------------------------

    @Test
    void judgesEachOfTheThreeReferencesOnItsOwn() {
        // Vorige waarde binnen de grens, kort gemiddelde erbuiten, lang gemiddelde ontbreekt.
        Result result = evaluate(amount("110.000000"), amount("100.000000"), amount("50.000000"), null);

        assertThat(outcome(result, ReferenceKind.PREVIOUS).exceeded()).isFalse();
        assertThat(deviation(result, ReferenceKind.PREVIOUS)).isEqualTo("10.000000000000");
        assertThat(outcome(result, ReferenceKind.AVG50).exceeded()).isTrue();
        assertThat(deviation(result, ReferenceKind.AVG50)).isEqualTo("120.000000000000");
        assertThat(outcome(result, ReferenceKind.AVG200).available()).isFalse();
        // Eén overschreden referentie volstaat voor een melding.
        assertThat(result.exceeded()).isTrue();
    }

    /**
     * De melding van par. 15.12 moet in één tekst alles dragen wat een mens nodig heeft: veldnaam,
     * nieuwe waarde, de ingestelde grens, en per referentie de oude waarde met de afwijking.
     */
    @Test
    void writesOneMessageThatCarriesFieldNameOldValueNewValueEveryDeviationAndTheLimit() {
        Result result = evaluate(amount("110.000000"), amount("100.000000"), amount("50.000000"),
                amount("40.000000"));

        assertThat(result.message())
                .startsWith("Basisprijs: '110.000000' deviates ")
                // De leidende referentie is de eerste die de grens overschrijdt.
                .contains("120.000000000000% from the average of the last 50 approved values 50.000000")
                .contains("(limit 15.000000000000%)")
                .contains("the previous accepted value 100.000000 -> 10.000000000000%")
                .contains("the average of the last 200 approved values 40.000000 -> 175.000000000000%");
        // Machineleesbaar ernaast: de oude waarden en de grens, zonder de melding te moeten ontleden.
        assertThat(result.expectedValue())
                .isEqualTo("PREVIOUS=100.000000;AVG50=50.000000;AVG200=40.000000;limit=15.000000000000");
    }

    @Test
    void namesTheMissingReferenceInTheMessageWhenNothingCouldBeCompared() {
        Result result = evaluate(amount("110.000000"), null, null, null);

        assertThat(result.message())
                .contains("Basisprijs: '110.000000' deviates from its price references")
                .contains("the previous accepted value is not available (PREVIOUS_NOT_AVAILABLE)")
                .contains("(limit 15.000000000000%)")
                .contains("AVG50_NOT_AVAILABLE")
                .contains("AVG200_NOT_AVAILABLE");
        assertThat(result.expectedValue()).isEqualTo("PREVIOUS=;AVG50=;AVG200=;limit=15.000000000000");
    }

    // --- Schaal en rekenwijze (R-PRI-11, par. 3.4) ---------------------------------------------

    @Test
    void computesEveryDeviationOnScaleTwelveWithHalfUpAndWithoutFloatingPoint() {
        // 1/3 is niet exact: 0,10 van 0,30 => -66,666666666667% (HALF_UP op schaal 12).
        Result result = evaluate(amount("0.100000"), amount("0.300000"), null, null);

        BigDecimal deviation = outcome(result, ReferenceKind.PREVIOUS).deviationPercent();
        assertThat(deviation.scale()).isEqualTo(PriceDeviationEvaluator.PERCENTAGE_SCALE);
        assertThat(deviation.toPlainString()).isEqualTo("-66.666666666667");
        // HALF_UP en geen afkapping: de exacte waarde is -66,666666...(6) en wordt weg van nul afgerond.
        assertThat(deviation).isNotEqualTo(new BigDecimal("-66.666666666666"));
    }

    /** Een grens van 0 laat geen enkele afwijking toe, maar een gelijke prijs blijft in orde. */
    @Test
    void appliesAZeroLimitLiterally() {
        Result exact = PriceDeviationEvaluator.evaluate("AKP", "Aankoopprijs", amount("100.000000"),
                Reference.previous(amount("100.000000")), new Reference(ReferenceKind.AVG50, null, 50),
                new Reference(ReferenceKind.AVG200, null, 200), BigDecimal.ZERO);
        Result different = PriceDeviationEvaluator.evaluate("AKP", "Aankoopprijs",
                amount("100.000001"), Reference.previous(amount("100.000000")),
                new Reference(ReferenceKind.AVG50, null, 50),
                new Reference(ReferenceKind.AVG200, null, 200), BigDecimal.ZERO);

        assertThat(exact.exceeded()).isFalse();
        assertThat(different.exceeded()).isTrue();
    }

    // --- Helpers -------------------------------------------------------------------------------

    private static Result evaluate(BigDecimal newAmount, BigDecimal previous, BigDecimal shortAverage,
                                   BigDecimal longAverage) {
        return PriceDeviationEvaluator.evaluate("BASE_PRICE", FIELD, newAmount,
                Reference.previous(previous), new Reference(ReferenceKind.AVG50, shortAverage, 50),
                new Reference(ReferenceKind.AVG200, longAverage, 200), LIMIT_15);
    }

    private static ReferenceOutcome outcome(Result result, ReferenceKind kind) {
        return result.references().stream().filter(reference -> reference.kind() == kind)
                .findFirst().orElseThrow();
    }

    private static String deviation(Result result, ReferenceKind kind) {
        return outcome(result, kind).deviationPercent().toPlainString();
    }

    private static BigDecimal amount(String value) {
        return new BigDecimal(value);
    }
}
