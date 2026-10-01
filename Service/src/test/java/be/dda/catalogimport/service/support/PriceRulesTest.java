package be.dda.catalogimport.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.service.support.PriceRules.ComponentInput;
import be.dda.catalogimport.service.support.PriceRules.ComponentStatus;
import be.dda.catalogimport.service.support.PriceRules.PriceComponent;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fase 3d (ontwerp fase 3, R-PRI-02..R-PRI-08 en R-PRI-15): de prijsregels van één bronregel.
 * Unittest zonder Spring — financiële rekenregels mogen nooit van een database of omgeving afhangen.
 * <p>
 * Wat hier hard bewezen wordt: er wordt nooit met {@code double} gerekend, nooit stil op nul gezet,
 * nooit stil afgerond en nooit stil gecorrigeerd. Elke verwachte waarde staat als letterlijke decimale
 * tekst in de test, zodat een wijziging aan de rekenregels zichzelf niet kan goedkeuren.
 */
class PriceRulesTest {

    private static final String BASE_FIELD = "PRIJS";
    private static final String AKP_FIELD = "Aankoopprijs in procent van de basisprijs";
    private static final String AKP = "AKP";

    private static final PricePolicy STRICT = PricePolicy.DEFAULT;
    private static final PricePolicy ZERO_ALLOWED =
            new PricePolicy(null, true, false, PriceRules.DEFAULT_DERIVATION_TOLERANCE);
    private static final PricePolicy NEGATIVE_ALLOWED =
            new PricePolicy(null, false, true, PriceRules.DEFAULT_DERIVATION_TOLERANCE);

    // --- R-PRI-04: het percentage staat op schaal 12 en wordt nooit met double berekend -----------

    @Test
    void computesThePercentageOnScaleTwelveWithHalfUp() {
        // 50,00 van 200,00 is exact 25%.
        assertThat(PriceRules.percentage(amount("50.00"), amount("200.00")).toPlainString())
                .isEqualTo("25.000000000000");
        // 1 van 3 is oneindig repeterend: afgerond op de twaalfde decimaal, HALF_UP.
        assertThat(PriceRules.percentage(amount("1.00"), amount("3.00")).toPlainString())
                .isEqualTo("33.333333333333");
        // 2 van 3 rondt op de laatste decimaal naar boven af (...6666 -> ...667).
        assertThat(PriceRules.percentage(amount("2.00"), amount("3.00")).toPlainString())
                .isEqualTo("66.666666666667");
        // Geen kunstmatige bovengrens (R-PRI-08): 900% is gewoon 900%.
        assertThat(PriceRules.percentage(amount("90.00"), amount("10.00")).toPlainString())
                .isEqualTo("900.000000000000");
    }

    /**
     * Het klassieke bewijs dat er geen binaire drijvendekomma in het spel is: {@code 0,1 + 0,2} is in
     * {@code double} 0,30000000000000004. Met {@link BigDecimal} is de verhouding van 0,30 tot 0,10
     * exact 300% — niet 299,999999999999%.
     */
    @Test
    void neverUsesBinaryFloatingPointArithmetic() {
        BigDecimal sum = amount("0.10").add(amount("0.20"));
        assertThat(sum.toPlainString()).isEqualTo("0.300000");
        assertThat(PriceRules.percentage(sum, amount("0.10")).toPlainString())
                .isEqualTo("300.000000000000");
        // 1,15 van 100 is met double 1.1499999999999999; hier exact.
        assertThat(PriceRules.percentage(amount("1.15"), amount("100.00")).toPlainString())
                .isEqualTo("1.150000000000");
        assertThat(0.1 + 0.2).isNotEqualTo(0.3); // waarom deze regels bestaan
    }

    @Test
    void refusesToComputeAPercentageOfAnAbsentOrZeroBase() {
        assertThatThrownBy(() -> PriceRules.percentage(amount("1.00"), amount("0.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NO_BASE_PRICE");
        assertThatThrownBy(() -> PriceRules.percentage(amount("1.00"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- R-PRI-02: nul, ontbrekend en leeg zijn drie verschillende toestanden ---------------------

    @Test
    void rejectsAZeroBasePriceUnlessTheRevisionAllowsIt() {
        assertThat(codeOf(() -> PriceRules.basePrice(amount("0.00"), BASE_FIELD, "0,00", STRICT)))
                .isEqualTo(PriceRules.CODE_PRICE_ZERO_NOT_ALLOWED);
        assertThat(PriceRules.basePrice(amount("0.00"), BASE_FIELD, "0,00", ZERO_ALLOWED).toPlainString())
                .isEqualTo("0.000000");
    }

    /**
     * Nul is een geleverde waarde, ontbrekend betekent "geen kolomwaarde" en leeg betekent "de
     * leverancier liet het veld leeg". Alle drie leveren ze een andere foutcode op en géén van de drie
     * levert ooit een prijs 0 op in de staging.
     */
    @Test
    void separatesZeroFromAbsentAndFromEmpty() {
        assertThat(codeOf(() -> PriceRules.basePrice(amount("0.00"), BASE_FIELD, "0", STRICT)))
                .isEqualTo(PriceRules.CODE_PRICE_ZERO_NOT_ALLOWED);
        assertThat(codeOf(() -> PriceRules.basePrice(null, BASE_FIELD, null, STRICT)))
                .isEqualTo(ImportValueRules.CODE_PRICE_MISSING);
        // De lege bronwaarde wordt al vóór PriceRules afgevangen en wordt nooit 0.
        assertThat(codeOf(() -> ImportValueRules.decimal("", BASE_FIELD)))
                .isEqualTo(ImportValueRules.CODE_PRICE_MISSING);
        assertThat(codeOf(() -> ImportValueRules.decimal("   ", BASE_FIELD)))
                .isEqualTo(ImportValueRules.CODE_PRICE_MISSING);
        assertThat(codeOf(() -> ImportValueRules.decimal("12,3x", BASE_FIELD)))
                .isEqualTo(ImportValueRules.CODE_PRICE_UNREADABLE);
    }

    @Test
    void appliesZeroAndNegativePerComponentFromItsOwnMapping() {
        // De basisprijs mag geen 0 zijn, de component wel: twee onafhankelijke schakelaars.
        List<PriceComponent> components = PriceRules.components(amount("200.00"), null,
                List.of(component("0.00", true, false, null)), STRICT);

        assertThat(components).hasSize(2);
        assertThat(components.get(1).percentage().toPlainString()).isEqualTo("0.000000000000");
        assertThat(codeOf(() -> PriceRules.components(amount("200.00"), null,
                List.of(component("0.00", false, false, null)), STRICT)))
                .isEqualTo(PriceRules.CODE_PRICE_ZERO_NOT_ALLOWED);
    }

    // --- R-PRI-03: negatief enkel wanneer het uitdrukkelijk toegelaten is -------------------------

    @Test
    void rejectsANegativeAmountUnlessItIsExplicitlyAllowed() {
        assertThat(codeOf(() -> PriceRules.basePrice(amount("-1.50"), BASE_FIELD, "-1,50", STRICT)))
                .isEqualTo(PriceRules.CODE_PRICE_NEGATIVE_NOT_ALLOWED);
        assertThat(PriceRules.basePrice(amount("-1.50"), BASE_FIELD, "-1,50", NEGATIVE_ALLOWED)
                .toPlainString()).isEqualTo("-1.500000");

        assertThat(codeOf(() -> PriceRules.components(amount("200.00"), null,
                List.of(component("-10.00", false, false, null)), STRICT)))
                .isEqualTo(PriceRules.CODE_PRICE_NEGATIVE_NOT_ALLOWED);
        List<PriceComponent> allowed = PriceRules.components(amount("200.00"), null,
                List.of(component("-10.00", false, true, null)), STRICT);
        assertThat(allowed.get(1).percentage().toPlainString()).isEqualTo("-5.000000000000");
    }

    // --- R-PRI-05: geen basis, geen verhouding ----------------------------------------------------

    @Test
    void leavesAComponentWithoutAUsableBasePriceUncomputedAndRejectsTheRow() {
        List<PriceComponent> components = PriceRules.components(amount("0.00"), null,
                List.of(component("10.00", false, false, null)), ZERO_ALLOWED);

        PriceComponent component = components.get(1);
        assertThat(component.status()).isEqualTo(ComponentStatus.NO_BASE_PRICE);
        // Nooit 0 en nooit 100 als plaatsvervangend percentage.
        assertThat(component.percentage()).isNull();
        assertThat(component.sourceAmount().toPlainString()).isEqualTo("10.000000");
        // De basisprijs zelf blijft een gewone rij zonder percentage.
        assertThat(components.get(0).componentCode()).isEqualTo(PriceRules.BASE_COMPONENT_CODE);
        assertThat(components.get(0).percentage()).isNull();
        assertThat(components.get(0).status()).isEqualTo(ComponentStatus.OK);

        assertThat(codeOf(() -> PriceRules.requireComputable(components, BASE_FIELD)))
                .isEqualTo(PriceRules.CODE_PRICE_PERCENTAGE_NOT_COMPUTABLE);
        // Zonder componenten is er niets onberekenbaars: een basisprijs 0 blijft dan gewoon geldig.
        assertThatCode(() -> PriceRules.requireComputable(
                PriceRules.components(amount("0.00"), null, List.of(), ZERO_ALLOWED), BASE_FIELD))
                .doesNotThrowAnyException();
    }

    // --- R-PRI-06: munt ---------------------------------------------------------------------------

    @Test
    void acceptsOnlyAnIsoShapedCurrencyAndNeverCorrectsIt() {
        assertThat(PriceRules.currency(" EUR ", "MUNT")).isEqualTo("EUR");
        // Geen stille omzetting naar hoofdletters: dat zou de waarde wijzigen (R-REC-09).
        assertThat(codeOf(() -> PriceRules.currency("eur", "MUNT")))
                .isEqualTo(PriceRules.CODE_PRICE_CURRENCY_MISMATCH);
        assertThat(codeOf(() -> PriceRules.currency("EURO", "MUNT")))
                .isEqualTo(PriceRules.CODE_PRICE_CURRENCY_MISMATCH);
        // Leeg is niet "onbekend": de revisie verklaarde dat deze bron een munt levert.
        assertThat(codeOf(() -> PriceRules.currency("", "MUNT")))
                .isEqualTo(PriceRules.CODE_PRICE_CURRENCY_MISMATCH);
        assertThat(codeOf(() -> PriceRules.currency(null, "MUNT")))
                .isEqualTo(PriceRules.CODE_PRICE_CURRENCY_MISMATCH);
    }

    @Test
    void rejectsAComponentInAnotherCurrencyThanTheBasePrice() {
        assertThat(codeOf(() -> PriceRules.components(amount("200.00"), "EUR",
                List.of(new ComponentInput(AKP, AKP_FIELD, "50,00", amount("50.00"), "USD", false,
                        false, null)), STRICT)))
                .isEqualTo(PriceRules.CODE_PRICE_CURRENCY_MISMATCH);
        // Zonder muntveld heeft geen enkele component een munt; nooit stil EUR.
        List<PriceComponent> withoutCurrency = PriceRules.components(amount("200.00"), null,
                List.of(component("50.00", false, false, null)), STRICT);
        assertThat(withoutCurrency).allSatisfy(component -> assertThat(component.currency()).isNull());
        // Mét muntveld erft de component de munt van de bronregel.
        List<PriceComponent> withCurrency = PriceRules.components(amount("200.00"), "EUR",
                List.of(component("50.00", false, false, null)), STRICT);
        assertThat(withCurrency).allSatisfy(component ->
                assertThat(component.currency()).isEqualTo("EUR"));
    }

    // --- R-PRI-07: reconstructie, exact op de grens en één eenheid erbuiten -----------------------

    @Test
    void acceptsAReconstructionDifferenceUpToAndIncludingTheConfiguredTolerance() {
        BigDecimal base = amount("100.00");
        // 10,00 is exact 10% van 100,00; het verschil is 0,00.
        assertThatCode(() -> PriceRules.verifyDerivation(base, percentage("10"), amount("10.00"),
                tolerance("0.01"), AKP_FIELD, AKP)).doesNotThrowAnyException();
        // 10,01 gereconstrueerd tegenover 10,00 geleverd: verschil exact 0,01 = de tolerantie.
        assertThatCode(() -> PriceRules.verifyDerivation(base, percentage("10.01"), amount("10.00"),
                tolerance("0.01"), AKP_FIELD, AKP)).doesNotThrowAnyException();
        // 10,02 tegenover 10,00: verschil 0,02, één eenheid boven de tolerantie.
        assertThat(codeOf(() -> PriceRules.verifyDerivation(base, percentage("10.02"), amount("10.00"),
                tolerance("0.01"), AKP_FIELD, AKP)))
                .isEqualTo(PriceRules.CODE_PRICE_DERIVATION_MISMATCH);
        // Met tolerantie 0 telt elk verschil, ook 0,01.
        assertThat(codeOf(() -> PriceRules.verifyDerivation(base, percentage("10.01"), amount("10.00"),
                tolerance("0"), AKP_FIELD, AKP)))
                .isEqualTo(PriceRules.CODE_PRICE_DERIVATION_MISMATCH);
    }

    /** De melding toont beide bedragen en het verschil; er wordt nooit stil gecorrigeerd. */
    @Test
    void showsBothAmountsAndTheDifferenceInsteadOfCorrectingThem() {
        assertThatThrownBy(() -> PriceRules.verifyDerivation(amount("100.00"), percentage("10.05"),
                amount("10.00"), tolerance("0.01"), AKP_FIELD, AKP))
                .isInstanceOf(ImportValueException.class)
                .hasMessageContaining(AKP_FIELD)
                .hasMessageContaining("10.05")
                .hasMessageContaining("10.00")
                .hasMessageContaining("never corrected");
    }

    /** De berekende verhouding reconstrueert altijd binnen de tolerantie; ook bij repeterende delingen. */
    @Test
    void reconstructsEveryComputedPercentageWithinTheTolerance() {
        List<PriceComponent> components = PriceRules.components(amount("3.33"), null,
                List.of(component("1.11", false, false, null)), STRICT);

        assertThat(components.get(1).percentage().toPlainString()).isEqualTo("33.333333333333");
        assertThat(PriceRules.reconstruct(amount("3.33"), components.get(1).percentage())
                .toPlainString()).isEqualTo("1.11");
    }

    // --- R-PRI-08 en R-PRI-15 --------------------------------------------------------------------

    @Test
    void appliesOnlyAnExplicitlyConfiguredUpperLimitOnTheRatio() {
        // Zonder ingestelde grens is 900% gewoon toegelaten: geen kunstmatig plafond.
        List<PriceComponent> unlimited = PriceRules.components(amount("10.00"), null,
                List.of(component("90.00", false, false, null)), STRICT);
        assertThat(unlimited.get(1).percentage().toPlainString()).isEqualTo("900.000000000000");

        // Exact op de ingestelde grens is toegelaten; erboven niet.
        assertThat(PriceRules.components(amount("10.00"), null,
                List.of(component("90.00", false, false, new BigDecimal("900"))), STRICT))
                .hasSize(2);
        assertThat(codeOf(() -> PriceRules.components(amount("10.00"), null,
                List.of(component("90.01", false, false, new BigDecimal("900"))), STRICT)))
                .isEqualTo(PriceRules.CODE_PRICE_PERCENTAGE_OUT_OF_RANGE);
    }

    @Test
    void roundsOnlyOnTheTargetBoundaryOfTwoDecimals() {
        // 0,005 rondt naar boven af (HALF_UP) en 0,004 naar beneden - en enkel op de doelgrens.
        assertThat(PriceRules.reconstruct(amount("1.00"), percentage("0.5")).toPlainString())
                .isEqualTo("0.01");
        assertThat(PriceRules.reconstruct(amount("1.00"), percentage("0.4")).toPlainString())
                .isEqualTo("0.00");
        // Intern blijft alles op schaal 6 respectievelijk 12 staan.
        List<PriceComponent> components = PriceRules.components(amount("12.345678"), null,
                List.of(component("1.234567", false, false, null)), STRICT);
        assertThat(components.get(0).sourceAmount().scale()).isEqualTo(PriceRules.AMOUNT_SCALE);
        assertThat(components.get(1).sourceAmount().toPlainString()).isEqualTo("1.234567");
        assertThat(components.get(1).percentage().scale()).isEqualTo(PriceRules.PERCENTAGE_SCALE);
    }

    @Test
    void refusesAnAmountWithMoreDecimalsThanTheTargetColumnInsteadOfRoundingIt() {
        assertThat(codeOf(() -> PriceRules.components(amount("100.00"), null,
                List.of(new ComponentInput(AKP, AKP_FIELD, "1,2345678", new BigDecimal("1.2345678"),
                        null, false, false, null)), STRICT)))
                .isEqualTo(ImportValueRules.CODE_PRICE_SCALE_EXCEEDED);
    }

    // --- Volgorde en samenstelling ----------------------------------------------------------------

    @Test
    void putsTheBasePriceFirstAndSortsTheComponentsOnTheirCode() {
        List<PriceComponent> components = PriceRules.components(amount("100.00"), null, List.of(
                new ComponentInput("VKP1", "Verkoopprijs 1", "120,00", amount("120.00"), null, false,
                        false, null),
                new ComponentInput(AKP, AKP_FIELD, "80,00", amount("80.00"), null, false, false, null)),
                STRICT);

        assertThat(components).extracting(PriceComponent::componentCode)
                .containsExactly(PriceRules.BASE_COMPONENT_CODE, "AKP", "VKP1");
        assertThat(components.get(1).percentage().toPlainString()).isEqualTo("80.000000000000");
        assertThat(components.get(2).percentage().toPlainString()).isEqualTo("120.000000000000");
        assertThat(components.get(0).sourceAmount().toPlainString()).isEqualTo("100.000000");
    }

    @Test
    void refusesAPricePolicyWithoutAToleranceOrWithANegativeOne() {
        assertThatThrownBy(() -> new PricePolicy(null, false, false, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PricePolicy(null, false, false, new BigDecimal("-0.01")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Helpers -----------------------------------------------------------------------------------

    private static ComponentInput component(String amount, boolean zeroAllowed, boolean negativeAllowed,
                                            BigDecimal maxPercentage) {
        return new ComponentInput(AKP, AKP_FIELD, amount.replace('.', ','), amount(amount), null,
                zeroAllowed, negativeAllowed, maxPercentage);
    }

    /** Zoals {@link ImportValueRules#decimal(String, String)} ze aanlevert: altijd op schaal 6. */
    private static BigDecimal amount(String value) {
        return new BigDecimal(value).setScale(PriceRules.AMOUNT_SCALE, java.math.RoundingMode.UNNECESSARY);
    }

    private static BigDecimal percentage(String value) {
        return new BigDecimal(value).setScale(PriceRules.PERCENTAGE_SCALE,
                java.math.RoundingMode.UNNECESSARY);
    }

    private static BigDecimal tolerance(String value) {
        return new BigDecimal(value);
    }

    private static String codeOf(Runnable rejected) {
        try {
            rejected.run();
            throw new AssertionError("expected the row to be rejected");
        } catch (ImportValueException failure) {
            return failure.getCode();
        }
    }
}
