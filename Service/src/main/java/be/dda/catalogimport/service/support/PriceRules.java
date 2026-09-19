package be.dda.catalogimport.service.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * De prijsregels van één bronregel: basisprijs, afgeleide prijscomponenten en hun percentage
 * (ontwerp fase 3, R-PRI-02..R-PRI-08 en R-PRI-15). Pure klasse: geen Spring, geen database, geen
 * tijd- of omgevingsafhankelijkheid — dezelfde bronregel levert altijd exact dezelfde bedragen,
 * percentages en vingerafdruk op.
 *
 * <h2>Financiële grondregels (par. 3.4)</h2>
 * <ul>
 *   <li><b>Nooit {@code float} of {@code double}.</b> Alles is {@link BigDecimal}. Bedragen rekenen op
 *       schaal {@value #AMOUNT_SCALE}, percentages op schaal {@value #PERCENTAGE_SCALE}.</li>
 *   <li><b>Nooit een stille nul.</b> Een onleesbaar, ontbrekend of verdacht bedrag verwerpt de
 *       bronregel met een expliciete code; het wordt nooit 0, leeg of "ongewijzigd".</li>
 *   <li><b>Nooit een stille afronding of correctie.</b> Er wordt uitsluitend op de doelgrens afgerond
 *       ({@value #TARGET_SCALE} decimalen, {@code HALF_UP}, R-PRI-15). Een reconstructie die buiten de
 *       ingestelde tolerantie valt, wordt getoond ({@link #CODE_PRICE_DERIVATION_MISMATCH}) en nooit
 *       "rechtgezet".</li>
 *   <li><b>Nul, ontbrekend en leeg zijn drie verschillende toestanden</b> (R-PRI-02). Ontbrekend en
 *       leeg worden al vóór deze klasse afgevangen ({@code PRICE_MISSING} / {@code VALUE_MISSING} in
 *       {@link ImportValueRules} en {@link FieldValueMapper}); hier gaat het uitsluitend over een
 *       werkelijk geleverde waarde 0.</li>
 * </ul>
 *
 * <h2>Wat deze klasse verwerpt en wat ze vaststelt</h2>
 * Elke schending van een prijsregel verwerpt <b>uitsluitend de betrokken bronregel</b>
 * ({@link ImportValueException}); de levering loopt door. Eén geval is bewust géén exception maar een
 * <i>toestand</i>: een basisprijs van nul waarbij de revisie dat toelaat, maakt elk percentage
 * onberekenbaar. De component krijgt dan status {@link ComponentStatus#NO_BASE_PRICE} en géén
 * percentage; de aanroeper beslist met {@link #requireComputable(List, String)} wat daarmee gebeurt.
 * Zo blijft de vaststelling ("er is geen basis") gescheiden van het oordeel ("deze regel is
 * onbruikbaar"), en blijft R-PRI-05 afzonderlijk bewijsbaar.
 *
 * <h2>Grens van bouwstap 3d</h2>
 * De afwijkingscontrole tegen de historiek (R-PRI-10..R-PRI-14) zit hier niet: die vergelijkt met
 * eerder goedgekeurde waarden en is bouwstap 3e. De muntcontrole is <b>syntactisch</b> (ISO-4217-vorm)
 * en <b>intern consistent</b> (aanname A22); de vergelijking met de stamdata van Prodis is fase 5.
 */
public final class PriceRules {

    /** De basisprijs is 0 terwijl de revisie dat niet toelaat (R-PRI-02). */
    public static final String CODE_PRICE_ZERO_NOT_ALLOWED = "PRICE_ZERO_NOT_ALLOWED";
    /** Het bedrag is negatief terwijl de revisie of de mapping dat niet toelaat (R-PRI-03). */
    public static final String CODE_PRICE_NEGATIVE_NOT_ALLOWED = "PRICE_NEGATIVE_NOT_ALLOWED";
    /** De munt is geen ISO-4217-vorm of verschilt van die van de basisprijs (R-PRI-06). */
    public static final String CODE_PRICE_CURRENCY_MISMATCH = "PRICE_CURRENCY_MISMATCH";
    /** Zonder bruikbare basisprijs bestaat er geen verhouding om te bewaren (R-PRI-05). */
    public static final String CODE_PRICE_PERCENTAGE_NOT_COMPUTABLE = "PRICE_PERCENTAGE_NOT_COMPUTABLE";
    /** {@code basis × pct / 100} levert het geleverde bedrag niet op binnen de tolerantie (R-PRI-07). */
    public static final String CODE_PRICE_DERIVATION_MISMATCH = "PRICE_DERIVATION_MISMATCH";
    /** De verhouding overschrijdt de voor deze component ingestelde grens (R-PRI-08). */
    public static final String CODE_PRICE_PERCENTAGE_OUT_OF_RANGE = "PRICE_PERCENTAGE_OUT_OF_RANGE";

    /** De componentcode van de basisprijs zelf; draagt een bedrag en nooit een percentage. */
    public static final String BASE_COMPONENT_CODE = "BASE_PRICE";

    /** Schaal van elk bedrag ({@code numeric(24,6)}). */
    public static final int AMOUNT_SCALE = 6;
    /** Schaal van elk percentage ({@code numeric(24,12)}). */
    public static final int PERCENTAGE_SCALE = 12;
    /** De enige grens waarop afgerond wordt: de doelgrens van 2 decimalen (R-PRI-15). */
    public static final int TARGET_SCALE = 2;

    /** Normatieve default van {@code price_derivation_tolerance} (changeset 004-10). */
    public static final BigDecimal DEFAULT_DERIVATION_TOLERANCE = new BigDecimal("0.01");

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    /** Syntactische ISO-4217-vorm: exact drie hoofdletters. Nooit stil omgezet (aanname A22). */
    private static final Pattern ISO_4217 = Pattern.compile("[A-Z]{3}");

    /** De toestand van één prijscomponent van één kandidaat ({@code import_candidate_price.status}). */
    public enum ComponentStatus {
        /** Bedrag gelezen en, voor een afgeleide component, percentage berekend. */
        OK,
        /** Er is geen bruikbare basisprijs, dus geen verhouding; nooit stil 0 of 100 (R-PRI-05). */
        NO_BASE_PRICE
    }

    /**
     * Eén prijscomponent van één bronregel, klaar om in {@code import_candidate_price} te gaan.
     *
     * @param percentage {@code null} voor {@link #BASE_COMPONENT_CODE} (de basisprijs is haar eigen
     *                   basis) en voor een component met status {@link ComponentStatus#NO_BASE_PRICE}
     * @param currency   {@code null} wanneer de revisie geen muntveld leest; dat is "onbekend" en
     *                   nooit "EUR"
     */
    public record PriceComponent(String componentCode, BigDecimal sourceAmount, BigDecimal percentage,
                                 String currency, ComponentStatus status) {

        public boolean isBase() {
            return BASE_COMPONENT_CODE.equals(componentCode);
        }
    }

    /**
     * Eén gelezen prijscomponent vóór de prijsregels erop losgelaten zijn.
     *
     * @param fieldName     de logische veldnaam uit de veldcatalogus; die staat in elke melding
     *                      (meldingsstijl par. 15.12)
     * @param sourceValue   de bronwaarde zoals ze in het bestand stond; enkel voor de melding
     * @param amount        het gelezen bedrag; nooit {@code null} (een lege component wordt niet
     *                      aangeboden)
     * @param currency      een eigen munt van deze component, of {@code null} om die van de basisprijs
     *                      over te nemen. In bouwstap 3d bestaat er geen bronveld per component; deze
     *                      parameter maakt R-PRI-06 wel afdwingbaar zodra er één komt
     * @param maxPercentage de <b>geconfigureerde</b> semantische bovengrens van deze component
     *                      ({@code maxPercentage=} in {@code transform_config}), of {@code null}. Er is
     *                      bewust geen kunstmatige algemene bovengrens (R-PRI-08)
     */
    public record ComponentInput(String componentCode, String fieldName, String sourceValue,
                                 BigDecimal amount, String currency, boolean zeroAllowed,
                                 boolean negativeAllowed, BigDecimal maxPercentage) {
    }

    private PriceRules() {
    }

    // --- Basisprijs (R-PRI-02, R-PRI-03) -------------------------------------------------------

    /**
     * Toetst de gelezen basisprijs aan het beleid van de revisie en geeft haar op de bedragschaal
     * terug. Ontbrekend, leeg en onleesbaar zijn hier al afgevangen door
     * {@link ImportValueRules#decimal(String, String)}; hier blijven 0 en negatief over — twee
     * werkelijk geleverde, betekenisvolle waarden die alleen doorgelaten worden wanneer de revisie ze
     * uitdrukkelijk toelaat.
     *
     * @throws ImportValueException de bronregel wordt verworpen; het bedrag wordt nooit gecorrigeerd
     */
    public static BigDecimal basePrice(BigDecimal amount, String fieldName, String sourceValue,
                                       PricePolicy policy) {
        if (amount == null) {
            // Onbereikbaar via de normale route; nooit stil 0 maken als het toch gebeurt.
            throw new ImportValueException(ImportValueRules.CODE_PRICE_MISSING, fieldName, sourceValue,
                    message(fieldName, sourceValue, "is missing; a base price is never assumed to be 0"));
        }
        if (amount.signum() == 0 && !policy.zeroAllowed()) {
            throw new ImportValueException(CODE_PRICE_ZERO_NOT_ALLOWED, fieldName, sourceValue,
                    message(fieldName, sourceValue, "is a base price of 0, which this revision does not "
                            + "allow (base_price_zero_allowed); 0 is a meaningful value and is never "
                            + "treated as 'no price'"));
        }
        if (amount.signum() < 0 && !policy.negativeAllowed()) {
            throw new ImportValueException(CODE_PRICE_NEGATIVE_NOT_ALLOWED, fieldName, sourceValue,
                    message(fieldName, sourceValue, "is a negative base price, which this revision does "
                            + "not allow (base_price_negative_allowed)"));
        }
        return scaled(amount, fieldName, sourceValue);
    }

    /**
     * Zet een bedrag op de bedragschaal zonder ooit af te ronden. Méér decimalen dan
     * {@code numeric(24,6)} kan bewaren is een fout en geen afronding: {@code 1,2345678} wordt nooit
     * stil {@code 1,234568}.
     */
    private static BigDecimal scaled(BigDecimal amount, String fieldName, String sourceValue) {
        if (amount.scale() > AMOUNT_SCALE) {
            throw new ImportValueException(ImportValueRules.CODE_PRICE_SCALE_EXCEEDED, fieldName,
                    sourceValue, message(fieldName, sourceValue, "has more than " + AMOUNT_SCALE
                    + " decimals; an amount is never rounded to fit the target column"));
        }
        return amount.setScale(AMOUNT_SCALE, RoundingMode.UNNECESSARY);
    }

    // --- Munt (R-PRI-06, aanname A22) ----------------------------------------------------------

    /**
     * De genormaliseerde munt van één bronregel: getrimd en in ISO-4217-<b>vorm</b> (drie
     * hoofdletters). Er wordt niets omgezet — {@code eur} wordt geen {@code EUR} — want een
     * hoofdletterwijziging op een bedrag is een stille datawijziging (R-REC-09). De vergelijking met
     * de muntstamdata van Prodis is fase 5.
     *
     * @param rawValue de bronwaarde van {@code record_currency_field}; leeg is een fout, want de
     *                 revisie verklaarde dat deze bron een munt levert
     * @throws ImportValueException de bronregel wordt verworpen
     */
    public static String currency(String rawValue, String fieldName) {
        String value = rawValue == null ? "" : rawValue.trim();
        if (!ISO_4217.matcher(value).matches()) {
            throw new ImportValueException(CODE_PRICE_CURRENCY_MISMATCH, fieldName, rawValue,
                    message(fieldName, rawValue, "is not an ISO 4217 currency code (exactly three capital "
                            + "letters); an amount is never stored with a guessed or corrected currency"));
        }
        return value;
    }

    // --- Componenten en percentages (R-PRI-04..R-PRI-08) ---------------------------------------

    /**
     * Zet de gelezen componenten van één bronregel om in de rijen van {@code import_candidate_price}.
     * De eerste rij is altijd de basisprijs zelf; de overige staan gesorteerd op componentcode, zodat
     * de volgorde nooit van de mappingvolgorde afhangt.
     *
     * @param basePrice de al getoetste basisprijs ({@link #basePrice})
     * @param currency  de munt van de bronregel, of {@code null} wanneer de revisie er geen leest
     * @throws ImportValueException de bronregel wordt verworpen (0, negatief, munt, reconstructie of
     *                              een overschreden geconfigureerde grens)
     */
    public static List<PriceComponent> components(BigDecimal basePrice, String currency,
                                                  List<ComponentInput> inputs, PricePolicy policy) {
        List<PriceComponent> components = new ArrayList<>(inputs.size() + 1);
        components.add(new PriceComponent(BASE_COMPONENT_CODE, basePrice, null, currency,
                ComponentStatus.OK));
        List<ComponentInput> sorted = new ArrayList<>(inputs);
        sorted.sort(Comparator.comparing(ComponentInput::componentCode));
        for (ComponentInput input : sorted) {
            components.add(component(basePrice, currency, input, policy));
        }
        return List.copyOf(components);
    }

    private static PriceComponent component(BigDecimal basePrice, String baseCurrency,
                                            ComponentInput input, PricePolicy policy) {
        BigDecimal amount = scaled(input.amount(), input.fieldName(), input.sourceValue());
        if (amount.signum() == 0 && !input.zeroAllowed()) {
            throw new ImportValueException(CODE_PRICE_ZERO_NOT_ALLOWED, input.fieldName(),
                    input.sourceValue(), message(input.fieldName(), input.sourceValue(),
                    "is 0, which the mapping of price component '" + input.componentCode()
                            + "' does not allow (zero_allowed)"));
        }
        if (amount.signum() < 0 && !input.negativeAllowed()) {
            throw new ImportValueException(CODE_PRICE_NEGATIVE_NOT_ALLOWED, input.fieldName(),
                    input.sourceValue(), message(input.fieldName(), input.sourceValue(),
                    "is negative, which the mapping of price component '" + input.componentCode()
                            + "' does not allow (negative_allowed)"));
        }
        String currency = componentCurrency(input, baseCurrency);

        // R-PRI-05: geen bruikbare basis => geen percentage en geen automatische prijsupdate. De
        // component wordt vastgesteld, niet stilzwijgend op 0 of 100 gezet.
        if (basePrice.signum() == 0) {
            return new PriceComponent(input.componentCode(), amount, null, currency,
                    ComponentStatus.NO_BASE_PRICE);
        }

        BigDecimal percentage = percentage(amount, basePrice);
        verifyWithinConfiguredRange(percentage, input);
        verifyDerivation(basePrice, percentage, amount, policy.derivationTolerance(), input.fieldName(),
                input.componentCode());
        return new PriceComponent(input.componentCode(), amount, percentage, currency,
                ComponentStatus.OK);
    }

    /** R-PRI-06: een component erft de munt van de bronregel en mag er nooit stil van afwijken. */
    private static String componentCurrency(ComponentInput input, String baseCurrency) {
        String own = input.currency();
        if (own == null) {
            return baseCurrency;
        }
        String normalised = currency(own, input.fieldName());
        if (!normalised.equals(baseCurrency)) {
            throw new ImportValueException(CODE_PRICE_CURRENCY_MISMATCH, input.fieldName(),
                    input.sourceValue(), message(input.fieldName(), input.sourceValue(),
                    "is expressed in '" + normalised + "' while the base price of this record is in '"
                            + (baseCurrency == null ? "" : baseCurrency)
                            + "'; amounts in different currencies are never compared or converted"));
        }
        return normalised;
    }

    /**
     * R-PRI-04: {@code pct = ander_bedrag × 100 / basisprijs}, op schaal {@value #PERCENTAGE_SCALE}
     * met {@code HALF_UP}. Uitsluitend {@link BigDecimal}; de deling wordt nooit aan {@code double}
     * overgelaten.
     */
    public static BigDecimal percentage(BigDecimal amount, BigDecimal basePrice) {
        if (basePrice == null || basePrice.signum() == 0) {
            throw new IllegalArgumentException("A percentage of a zero or absent base price is never "
                    + "computed; use the NO_BASE_PRICE state instead");
        }
        return amount.multiply(HUNDRED).divide(basePrice, PERCENTAGE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * R-PRI-07: {@code afronden(basis × pct / 100, 2, HALF_UP)} moet het geleverde bedrag op twee
     * decimalen opleveren, binnen {@code tolerance}. Een grotere afwijking wordt <b>getoond</b> met de
     * gereconstrueerde waarde, het bronbedrag en het verschil; er wordt nooit stil gecorrigeerd en het
     * percentage wordt nooit "bijgedraaid" om te doen kloppen.
     *
     * @throws ImportValueException de bronregel wordt verworpen
     */
    public static void verifyDerivation(BigDecimal basePrice, BigDecimal percentage,
                                        BigDecimal sourceAmount, BigDecimal tolerance, String fieldName,
                                        String componentCode) {
        BigDecimal reconstructed = reconstruct(basePrice, percentage);
        BigDecimal delivered = sourceAmount.setScale(TARGET_SCALE, RoundingMode.HALF_UP);
        BigDecimal difference = reconstructed.subtract(delivered).abs();
        if (difference.compareTo(tolerance) <= 0) {
            return;
        }
        throw new ImportValueException(CODE_PRICE_DERIVATION_MISMATCH, fieldName, plain(sourceAmount),
                message(fieldName, plain(sourceAmount), "cannot be reconstructed from price component '"
                        + componentCode + "': " + plain(basePrice) + " x " + plain(percentage)
                        + "% / 100 = " + plain(reconstructed) + " instead of " + plain(delivered)
                        + " (difference " + plain(difference) + ", tolerance " + plain(tolerance)
                        + "); the amount is shown as delivered and never corrected"));
    }

    /**
     * De doelwaarde van een component uit de basisprijs en haar verhouding: {@code basis × pct / 100},
     * afgerond op de doelgrens van {@value #TARGET_SCALE} decimalen met {@code HALF_UP} (R-PRI-15).
     * Dit is de <b>enige</b> plaats waar een prijs afgerond wordt.
     */
    public static BigDecimal reconstruct(BigDecimal basePrice, BigDecimal percentage) {
        return basePrice.multiply(percentage).divide(HUNDRED, TARGET_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * R-PRI-08: er is géén algemene kunstmatige bovengrens op een verhouding — een aankoopprijs van
     * 900% van de basisprijs kan legitiem zijn. Alleen een <b>uitdrukkelijk geconfigureerde</b>
     * semantische grens per component telt.
     */
    private static void verifyWithinConfiguredRange(BigDecimal percentage, ComponentInput input) {
        BigDecimal max = input.maxPercentage();
        if (max == null || percentage.compareTo(max) <= 0) {
            return;
        }
        throw new ImportValueException(CODE_PRICE_PERCENTAGE_OUT_OF_RANGE, input.fieldName(),
                input.sourceValue(), message(input.fieldName(), input.sourceValue(), "is "
                + plain(percentage) + "% of the base price, above the configured maximum of "
                + plain(max) + "% for price component '" + input.componentCode() + "'"));
    }

    /**
     * R-PRI-05: een component zonder berekenbare verhouding maakt de bronregel onbruikbaar voor een
     * automatische prijsupdate. De aanroeper roept dit aan ná {@link #components}; de vaststelling
     * (status {@link ComponentStatus#NO_BASE_PRICE}) en het oordeel (regel verwerpen) blijven zo
     * gescheiden.
     *
     * @throws ImportValueException de bronregel wordt verworpen
     */
    public static void requireComputable(List<PriceComponent> components, String basePriceFieldName) {
        for (PriceComponent component : components) {
            if (component.status() != ComponentStatus.NO_BASE_PRICE) {
                continue;
            }
            throw new ImportValueException(CODE_PRICE_PERCENTAGE_NOT_COMPUTABLE, basePriceFieldName,
                    plain(component.sourceAmount()), message(basePriceFieldName, "0",
                    "is a base price without a usable value, so the share of price component '"
                            + component.componentCode() + "' (" + plain(component.sourceAmount())
                            + ") cannot be computed; no percentage is stored and no price is updated"));
        }
    }

    private static String message(String fieldName, String sourceValue, String problem) {
        // Meldingsstijl par. 15.12: <logische veldnaam>: '<bronwaarde>' <wat er mis is>.
        return fieldName + ": '" + (sourceValue == null ? "" : sourceValue) + "' " + problem;
    }

    private static String plain(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }
}
