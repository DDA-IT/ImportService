package be.dda.catalogimport.service.support;

import be.dda.catalogimport.domain.DeviationDirection;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * De afwijkingscontrole van één prijscomponent tegen haar drie referenties (ontwerp fase 3,
 * R-PRI-10..R-PRI-12, model 1 {@code DEVIATION}). Pure klasse: geen Spring, geen database, geen tijd —
 * dezelfde bedragen leveren altijd exact dezelfde beoordeling en dezelfde melding op.
 *
 * <h2>Financiële grondregels</h2>
 * <ul>
 *   <li><b>Nooit {@code float} of {@code double}.</b> Alles is {@link BigDecimal};
 *       {@code afwijking% = (nieuw − referentie) × 100 / referentie} met
 *       {@code divide(..., 12, HALF_UP)} (R-PRI-11).</li>
 *   <li><b>Een anomalie wijzigt niets</b> (R-PRI-12). Deze klasse geeft een vaststelling terug: geen
 *       prijs, geen percentage, geen mutatie-inhoud en geen status wordt hier aangeraakt. De
 *       bijbehorende mutatie blijft {@code PLANNED}.</li>
 *   <li><b>Een ontbrekende, nul of onleesbare referentie wordt niet berekend</b> maar benoemd
 *       ({@link ReferenceKind#notAvailableStatus()}). Er wordt nooit een afwijking van 0% of 100%
 *       verzonnen om toch iets te kunnen tonen, en een deling door nul bestaat hier niet.</li>
 *   <li><b>Exact op de grens is niet overschreden.</b> Overschrijding is
 *       {@code |afwijking%| > grens}, niet {@code >=}: bij een grens van 15% is 15,000000000000% nog
 *       toegelaten en 15,000000000001% niet.</li>
 * </ul>
 *
 * <h2>Melding (par. 15.12)</h2>
 * Eén tekst met alles wat een mens nodig heeft om te oordelen: de logische veldnaam, de nieuwe
 * waarde, de ingestelde grens, en per referentie de oude waarde met de afwijking in procent — of de
 * reden waarom die referentie er niet is. De grens staat vooraan in de tekst, zodat ze ook bij een
 * afgekapte melding bewaard blijft.
 */
public final class PriceDeviationEvaluator {

    /** Minstens één referentie wordt met meer dan de ingestelde grens overschreden (R-PRI-10). */
    public static final String CODE_PRICE_DEVIATION_EXCEEDED = "PRICE_DEVIATION_EXCEEDED";
    /**
     * Er kon voor minstens één component niet met alle drie de referenties vergeleken worden
     * (R-PRI-11). Bewust informatief en bewust <b>samenvattend per levering</b>: één melding per
     * record zou bij een eerste historiekopbouw miljoenen rijen opleveren zonder iets toe te voegen.
     */
    public static final String CODE_PRICE_REFERENCE_NOT_AVAILABLE = "PRICE_REFERENCE_NOT_AVAILABLE";

    /** Schaal van een afwijkingspercentage; dezelfde als elk ander percentage (R-PRI-11). */
    public static final int PERCENTAGE_SCALE = PriceRules.PERCENTAGE_SCALE;

    /** Status van een referentie die wél bruikbaar was. */
    public static final String STATUS_OK = "OK";

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * De drie referenties van R-PRI-10. De namen van de statussen
     * ({@code PREVIOUS_NOT_AVAILABLE}, {@code AVG50_NOT_AVAILABLE}, {@code AVG200_NOT_AVAILABLE})
     * zijn normatief en verwijzen naar de <i>standaardvensters</i> 50 en 200; het werkelijke venster
     * is per revisie instelbaar ({@code price_avg_short_window}/{@code price_avg_long_window}) en
     * staat in de melding.
     */
    public enum ReferenceKind {

        /** De laatste aanvaarde waarde ({@code catalog_source_state[_price]}). */
        PREVIOUS("PREVIOUS", "the previous accepted value"),
        /** Het gemiddelde van de laatste {@code price_avg_short_window} goedgekeurde dagwaarden. */
        AVG50("AVG50", "the average of the last %d approved values"),
        /** Het gemiddelde van de laatste {@code price_avg_long_window} goedgekeurde dagwaarden. */
        AVG200("AVG200", "the average of the last %d approved values");

        private final String key;
        private final String label;

        ReferenceKind(String key, String label) {
            this.key = key;
            this.label = label;
        }

        /** Korte sleutel voor {@code expected_value}, bv. {@code PREVIOUS=3.000000}. */
        public String key() {
            return key;
        }

        /** De status wanneer deze referentie ontbreekt, nul of onleesbaar is (R-PRI-11). */
        public String notAvailableStatus() {
            return key + "_NOT_AVAILABLE";
        }

        private String label(int windowSize) {
            return label.contains("%d") ? String.format(label, windowSize) : label;
        }
    }

    /**
     * Eén aangeboden referentie.
     *
     * @param value      de referentiewaarde, of {@code null} wanneer ze niet bestaat
     * @param windowSize het aantal dagwaarden waarover een gemiddelde loopt; 0 voor
     *                   {@link ReferenceKind#PREVIOUS}
     */
    public record Reference(ReferenceKind kind, BigDecimal value, int windowSize) {

        public static Reference previous(BigDecimal value) {
            return new Reference(ReferenceKind.PREVIOUS, value, 0);
        }
    }

    /**
     * Het oordeel over één referentie.
     *
     * @param deviationPercent {@code null} wanneer de referentie niet bruikbaar was; dan is er
     *                         <b>niets</b> berekend
     * @param status           {@link #STATUS_OK} of {@link ReferenceKind#notAvailableStatus()}
     */
    public record ReferenceOutcome(ReferenceKind kind, BigDecimal value, BigDecimal deviationPercent,
                                   String status, boolean exceeded) {

        public boolean available() {
            return deviationPercent != null;
        }
    }

    /**
     * Het oordeel over één prijscomponent van één bronregel.
     *
     * @param exceeded      minstens één referentie overschrijdt de grens ⇒ één issue
     *                      {@link #CODE_PRICE_DEVIATION_EXCEEDED}
     * @param message       de volledige melding (par. 15.12)
     * @param expectedValue de oude waarden en de grens in machineleesbare vorm, voor
     *                      {@code import_row_issue.expected_value}
     */
    public record Result(String componentCode, String fieldName, BigDecimal newAmount,
                         BigDecimal limitPercent, List<ReferenceOutcome> references, boolean exceeded,
                         String message, String expectedValue) {

        /**
         * De richting van de overschrijding: ligt het geleverde bedrag boven of onder de referentie
         * die de grens overschrijdt? Bepaald op de <b>eerste overschreden</b> referentie — precies
         * die referentie die ook vooraan in de melding staat, zodat melding en groepering hetzelfde
         * verschijnsel beschrijven.
         * <p>
         * De richting hoort bij de signatuur van een bulkprijsincident (R-PRI-14): honderd
         * stijgingen zijn een prijsverhoging, honderd dalingen eerder een verkeerd geplaatste
         * decimaal. Ze samen tellen zou juist de aanwijzing wegpoetsen.
         *
         * @return {@code null} wanneer er geen overschrijding is; er valt dan niets te groeperen
         */
        public DeviationDirection direction() {
            return references.stream().filter(ReferenceOutcome::exceeded).findFirst()
                    .map(outcome -> outcome.deviationPercent().signum() < 0
                            ? DeviationDirection.DOWN : DeviationDirection.UP)
                    .orElse(null);
        }
    }

    private PriceDeviationEvaluator() {
    }

    /**
     * Beoordeelt één kandidaatbedrag tegen de drie referenties.
     *
     * @param fieldName    de logische veldnaam uit {@code import_field_catalog}; die staat vooraan in
     *                     elke melding
     * @param newAmount    het geleverde bedrag; nooit {@code null} (een component zonder bedrag komt
     *                     hier niet)
     * @param limitPercent de gezamenlijke grens per revisie ({@code price_deviation_percent})
     */
    public static Result evaluate(String componentCode, String fieldName, BigDecimal newAmount,
                                  Reference previous, Reference shortAverage, Reference longAverage,
                                  BigDecimal limitPercent) {
        if (newAmount == null) {
            throw new IllegalArgumentException("A price deviation is never evaluated without a new "
                    + "amount; an absent amount is a record issue, not a deviation");
        }
        BigDecimal limit = limitPercent == null ? BigDecimal.ZERO : limitPercent;
        List<ReferenceOutcome> outcomes = new ArrayList<>(3);
        boolean exceeded = false;
        for (Reference reference : List.of(previous, shortAverage, longAverage)) {
            ReferenceOutcome outcome = evaluateReference(newAmount, reference, limit);
            outcomes.add(outcome);
            exceeded = exceeded || outcome.exceeded();
        }
        List<ReferenceOutcome> references = List.copyOf(outcomes);
        return new Result(componentCode, fieldName, newAmount, limit, references, exceeded,
                message(fieldName, newAmount, limit, references,
                        List.of(previous, shortAverage, longAverage)),
                expectedValue(references, limit));
    }

    private static ReferenceOutcome evaluateReference(BigDecimal newAmount, Reference reference,
                                                      BigDecimal limit) {
        BigDecimal value = reference.value();
        // R-PRI-11: ontbrekend en nul zijn beide "geen bruikbare referentie". Er wordt dan niets
        // berekend - geen deling door nul, en geen verzonnen 0%.
        if (value == null || value.signum() == 0) {
            return new ReferenceOutcome(reference.kind(), value, null,
                    reference.kind().notAvailableStatus(), false);
        }
        BigDecimal deviation = newAmount.subtract(value).multiply(HUNDRED)
                .divide(value, PERCENTAGE_SCALE, RoundingMode.HALF_UP);
        // Exact op de grens is niet overschreden.
        boolean exceeded = deviation.abs().compareTo(limit) > 0;
        return new ReferenceOutcome(reference.kind(), value, deviation, STATUS_OK, exceeded);
    }

    /**
     * De melding van par. 15.12: veldnaam, nieuwe waarde, de ingestelde grens, en per referentie de
     * oude waarde met de afwijking in procent. De eerste overschreden referentie staat vooraan; is er
     * geen overschrijding, dan begint de tekst met de vorige waarde.
     */
    private static String message(String fieldName, BigDecimal newAmount, BigDecimal limit,
                                  List<ReferenceOutcome> outcomes, List<Reference> references) {
        ReferenceOutcome leading = outcomes.stream().filter(ReferenceOutcome::exceeded).findFirst()
                .orElse(outcomes.get(0));
        StringBuilder message = new StringBuilder(name(fieldName)).append(": '")
                .append(plain(newAmount)).append("' deviates ");
        if (leading.available()) {
            message.append(plain(leading.deviationPercent())).append("% from ")
                    .append(label(leading.kind(), references)).append(' ')
                    .append(plain(leading.value()));
        } else {
            message.append("from its price references, but ").append(label(leading.kind(), references))
                    .append(" is not available (").append(leading.status()).append(')');
        }
        message.append(" (limit ").append(plain(limit)).append("%)");
        for (ReferenceOutcome outcome : outcomes) {
            if (outcome == leading) {
                continue;
            }
            message.append("; ").append(label(outcome.kind(), references)).append(' ');
            if (outcome.available()) {
                message.append(plain(outcome.value())).append(" -> ")
                        .append(plain(outcome.deviationPercent())).append('%');
            } else {
                message.append("not available (").append(outcome.status()).append(')');
            }
        }
        return message.toString();
    }

    /** Machineleesbaar, náást de melding: de oude waarden en de grens (R-PRI-12). */
    private static String expectedValue(List<ReferenceOutcome> outcomes, BigDecimal limit) {
        StringBuilder expected = new StringBuilder();
        for (ReferenceOutcome outcome : outcomes) {
            expected.append(outcome.kind().key()).append('=')
                    .append(outcome.value() == null ? "" : plain(outcome.value())).append(';');
        }
        return expected.append("limit=").append(plain(limit)).toString();
    }

    private static String label(ReferenceKind kind, List<Reference> references) {
        for (Reference reference : references) {
            if (reference.kind() == kind) {
                return kind.label(reference.windowSize());
            }
        }
        return kind.key();
    }

    private static String name(String fieldName) {
        return fieldName == null || fieldName.isBlank() ? "price" : fieldName;
    }

    private static String plain(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }
}
