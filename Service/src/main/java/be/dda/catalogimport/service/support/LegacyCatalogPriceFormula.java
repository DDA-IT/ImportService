package be.dda.catalogimport.service.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * De verkoopprijsformule van legacy Prg_1185 "Prijsupdate" voor één catalogusartikel, zoals CatalogImport ze nodig heeft om
 * rechtstreeks in de Prodis-database te publiceren (design {@code publicatie-prodis-db-direct-design.md} §3, slice P2a;
 * beslissingen 2026-10-22 P-V2 en Q1 = b). Pure klasse: geen Spring, geen database, geen tijd.
 *
 * <h2>Letterlijke port, bewust in {@code double}</h2>
 * {@link #port(PortInput)} is een regel-voor-regel port van Prodis
 * {@code be.dda.prodis.service.suppliergroup.pricing.LegacyPriceCalculator#calculate} (r.31-73): dezelfde
 * {@code double}-volgorde {@code bverk * pct / 100d * coef} en dezelfde afronding
 * {@code BigDecimal.valueOf(d).setScale(dec, HALF_UP).doubleValue()}. Anders dan de rest van CatalogImport ("nooit
 * {@code double}", zie {@link PriceDeviationEvaluator}) rekent deze klasse daarom met {@code double}: het doel is
 * <b>bit-identiek</b> aan wat Prodis zelf zou wegschrijven, inclusief de {@code double}-artefacten (1.005 × 100% → 1.01).
 * De golden {@code legacy-price-golden.csv} is gegenereerd met de Prodis-klasse zelf. Weggelaten t.o.v. Prodis: enkel
 * {@code pricePolicy}, {@code purchasePercentage} en {@code purchasePrice} — die bepalen alleen {@code purchase_price}, dat
 * CatalogImport niet schrijft (Q1 = b) en dat de verkoop-/brutoprijzen niet beïnvloedt.
 *
 * <h2>Catalogusparameters (Prg_261-port)</h2>
 * Zoals Prodis {@code SupplierGroupPricePropagationService} r.267-282: coëfficiënt 1, prijsbeleid leeg, 2 decimalen
 * ({@code ARBIB_Aant_dec_prijs} wordt niet naar Prodis gesynchroniseerd), verkoopbasis = basisprijs.
 *
 * <h2>Regels per {@code price_code} (§3)</h2>
 * <ul>
 *   <li>{@code 0}: de VKP% uit de import tellen; een ontbrekend percentage ({@code null} = KEEP) neemt de Prodis-waarde.
 *       VKP1..5 herberekend; bruto volgens {@code brut_price_politics}.</li>
 *   <li>{@code 2}: VKP herberekend met de <b>bestaande</b> Prodis-percentages; import-% worden niet overgenomen →
 *       melding {@link #CODE_PERCENTAGES_NOT_APPLICABLE}.</li>
 *   <li>{@code 1}: VKP-prijzen ongemoeid; import-% worden niet overgenomen → melding. Prg_1185 zou de VKP% op 0 zetten;
 *       volgens §3 schrijft CatalogImport ze niet, dus het resultaat geeft de bestaande percentages terug. Bruto volgens
 *       Prg_1185 ({@code 0}: herberekend uit AVP%; {@code n}: kopie van de bestaande VKPn, AVP% := 0).</li>
 *   <li>elke andere waarde (ook leeg): {@link Outcome#REJECTED} met {@link #CODE_PRICE_CODE_UNSUPPORTED}.</li>
 * </ul>
 * Bruto (Prg_1185): {@code brut_price_politics} {@code 0} → {@code brut_price := Round(basis × AVP% / 100)};
 * elke andere waarde (ook leeg) → {@code brut_price := VKPn} als detail {@code n} bestaat, anders ongemoeid, en
 * {@code brut_price_percentage := 0}.
 *
 * <h2>Wat deze klasse niet doet (planner, P2b)</h2>
 * Een expliciete 0 als basisprijs of VKP% is hier een gewone waarde: Q2 (0 → bestaande waarde behouden, WARNING
 * {@code ZERO_KEEPS_EXISTING}) beslist de planner vóór de aanroep. Ook precisiecontroles, {@code PRICE_ROUNDING_DIFFERENCE},
 * {@code sales_price_last_updated} en het al dan niet schrijven van een veld horen bij de planner. Er wordt niets stil
 * gecorrigeerd: elke afwijking van de import is een melding in het resultaat.
 */
public final class LegacyCatalogPriceFormula {

    /** Prijscode 1 of 2: de VKP% uit de import worden niet overgenomen (legacy Prg_1179). */
    public static final String CODE_PERCENTAGES_NOT_APPLICABLE = "PERCENTAGES_NOT_APPLICABLE";
    /** Prijscode buiten {@code 0}/{@code 1}/{@code 2}: geen berekening, het item wordt geweigerd. */
    public static final String CODE_PRICE_CODE_UNSUPPORTED = "PRICE_CODE_UNSUPPORTED";
    /**
     * Een herberekende VKP heeft geen percentage ({@code null} in Prodis én geen importwaarde): Prg_1185 rekent met 0, dus de
     * prijs wordt 0 — zoals Prodis, maar zichtbaar gemaakt.
     */
    public static final String CODE_PERCENTAGE_MISSING = "PERCENTAGE_MISSING";

    /** {@code ARBIB_Prijscode}/{@code MANUAL_PERCENT} '0': percentage. */
    public static final String PRICE_CODE_PERCENTAGE = "0";
    /** '1': manuele verkoopprijzen. */
    public static final String PRICE_CODE_MANUAL = "1";
    /** '2': percentage, maar import-percentages worden niet overgenomen. */
    public static final String PRICE_CODE_PERCENTAGE_KEEP = "2";

    /** Laagste en hoogste VKP-code ({@code catalog_article_price_detail.code} "1".."5"). */
    public static final int FIRST_CODE = 1;
    public static final int LAST_CODE = 5;

    /** Prg_261-port: {@code CATALOG_DECIMALS}. */
    static final int CATALOG_DECIMALS = 2;
    /** Prg_261-port: {@code coefficient(1d)}. */
    static final double CATALOG_COEFFICIENT = 1d;
    /** {@code LegacyPriceCalculator.DEFAULT_DECIMALS}. */
    static final int DEFAULT_DECIMALS = 2;

    private static final String OWN_BRUT_PERCENTAGE = "0";

    private LegacyCatalogPriceFormula() {
    }

    // ---- publieke catalogus-API ---------------------------------------------------------------------------------

    /** Bestaande Prodis-waarden van één {@code catalog_article_price_detail} (beide nullable). */
    public record ExistingDetail(Double percentage, Double price) {
    }

    /**
     * @param priceCode                    Prodis {@code catalog_article_price.price_code} (wordt getrimd zoals in Prodis)
     * @param brutPricePolitics            Prodis {@code brut_price_politics} (nullable; getrimd)
     * @param basePrice                    de doel-basisprijs (import, of de Prodis-waarde bij KEEP); verplicht — de planner
     *                                     beslist KEEP/0 vóór de aanroep
     * @param importPercentages            VKP% uit de import per code 1..5; afwezig of {@code null} = KEEP
     * @param existingDetails              bestaande Prodis-details per code 1..5
     * @param existingBrutPrice            Prodis {@code brut_price} (nullable)
     * @param existingBrutPricePercentage  Prodis {@code brut_price_percentage} (nullable)
     */
    public record Input(String priceCode, String brutPricePolitics, BigDecimal basePrice, Map<Integer, BigDecimal> importPercentages,
                        Map<Integer, ExistingDetail> existingDetails, Double existingBrutPrice, Double existingBrutPricePercentage) {

        public Input {
            Objects.requireNonNull(basePrice, "basePrice is verplicht: de planner beslist KEEP of 0 vóór de berekening");
            importPercentages = codeMap(importPercentages, "importPercentages");
            existingDetails = codeMap(existingDetails, "existingDetails");
            for (Map.Entry<Integer, ExistingDetail> entry : existingDetails.entrySet()) {
                if (entry.getValue() == null) {
                    throw new IllegalArgumentException("existingDetails: detail " + entry.getKey() + " is null");
                }
            }
        }
    }

    public enum Outcome {
        CALCULATED,
        REJECTED
    }

    /**
     * Een melding bij de berekening; de ernst bepaalt de planner.
     *
     * @param code        meldingscode ({@code CODE_*})
     * @param detailCode  de betrokken VKP-code 1..5, of {@code null} als de melding het hele artikel betreft
     * @param message     leesbare uitleg
     */
    public record Notice(String code, Integer detailCode, String message) {
    }

    /**
     * Doelwaarden na Prg_1185. Bij {@link Outcome#REJECTED} zijn de maps leeg en de bruto-velden {@code null}.
     *
     * @param percentages          doel-VKP% per code (prijscode 0: import of bestaand; 1/2: bestaand)
     * @param prices               doel-VKP-prijs per code
     * @param brutPrice            doel-{@code brut_price} (AVP1)
     * @param brutPricePercentage  doel-{@code brut_price_percentage}
     */
    public record Result(Outcome outcome, String rejectionCode, Map<Integer, Double> percentages, Map<Integer, Double> prices,
                         Double brutPrice, Double brutPricePercentage, List<Notice> notices) {

        public Result {
            percentages = Collections.unmodifiableMap(new TreeMap<>(percentages));
            prices = Collections.unmodifiableMap(new TreeMap<>(prices));
            notices = List.copyOf(notices);
        }
    }

    /** Berekent de doelwaarden voor één catalogusartikel met de catalogusparameters van Prg_261. */
    public static Result calculate(Input input) {
        Objects.requireNonNull(input, "input");
        String priceCode = trim(input.priceCode());
        if (!PRICE_CODE_PERCENTAGE.equals(priceCode) && !PRICE_CODE_MANUAL.equals(priceCode)
                && !PRICE_CODE_PERCENTAGE_KEEP.equals(priceCode)) {
            Notice rejection = new Notice(CODE_PRICE_CODE_UNSUPPORTED, null,
                    "price_code '" + input.priceCode() + "' is not supported (only 0, 1 and 2)");
            return new Result(Outcome.REJECTED, CODE_PRICE_CODE_UNSUPPORTED, Map.of(), Map.of(), null, null, List.of(rejection));
        }
        boolean importPercentagesApply = PRICE_CODE_PERCENTAGE.equals(priceCode);
        boolean recalculates = !PRICE_CODE_MANUAL.equals(priceCode);
        List<Notice> notices = new ArrayList<>();

        if (!importPercentagesApply) {
            for (Map.Entry<Integer, BigDecimal> entry : input.importPercentages().entrySet()) {
                if (entry.getValue() != null) {
                    notices.add(new Notice(CODE_PERCENTAGES_NOT_APPLICABLE, entry.getKey(), "sales percentage " + entry.getKey()
                            + " from the import (" + entry.getValue().toPlainString() + ") is not applied: price_code " + priceCode
                            + (recalculates ? " recalculates with the existing Prodis percentage" : " keeps the sales prices")));
                }
            }
        }

        // Zoals Prg_261: één map met percentages en één met prijzen, per bestaand detail; een importpercentage zonder detail
        // krijgt (zoals catalogDetail(...) in Prodis) een detail met prijs null.
        Map<String, Double> salesPercentages = new TreeMap<>();
        Map<String, Double> salesPrices = new TreeMap<>();
        Map<Integer, Double> existingPercentages = new TreeMap<>();
        input.existingDetails().forEach((code, detail) -> {
            salesPercentages.put(String.valueOf(code), detail.percentage());
            salesPrices.put(String.valueOf(code), detail.price());
            existingPercentages.put(code, detail.percentage());
        });
        if (importPercentagesApply) {
            input.importPercentages().forEach((code, percentage) -> {
                if (percentage != null) {
                    salesPercentages.put(String.valueOf(code), percentage.doubleValue());
                    salesPrices.putIfAbsent(String.valueOf(code), null);
                }
            });
        }
        if (recalculates) {
            salesPercentages.forEach((code, percentage) -> {
                if (percentage == null) {
                    notices.add(new Notice(CODE_PERCENTAGE_MISSING, Integer.valueOf(code), "sales percentage " + code
                            + " is empty in Prodis and not in the import: Prg_1185 calculates sales price " + code + " as 0"));
                }
            });
        }

        PortResult port = port(new PortInput(priceCode, input.brutPricePolitics(), CATALOG_DECIMALS, CATALOG_COEFFICIENT,
                input.basePrice().doubleValue(), null, salesPercentages, input.existingBrutPricePercentage(), salesPrices,
                input.existingBrutPrice()));

        Map<Integer, Double> percentages = recalculates ? toCodeMap(port.salesPercentages()) : existingPercentages;
        return new Result(Outcome.CALCULATED, null, percentages, toCodeMap(port.salesPrices()), port.brutPrice(),
                port.brutPercentage(), notices);
    }

    // ---- letterlijke port van LegacyPriceCalculator.calculate (zonder purchase) ---------------------------------------

    /** De invoer van {@code LegacyPriceInput} zonder {@code pricePolicy}/{@code purchase*}. */
    record PortInput(String priceCode, String brutCode, Integer decimals, Double coefficient, Double basePrice, Double salesBasePrice,
                     Map<String, Double> salesPercentages, Double brutPercentage, Map<String, Double> salesPrices, Double brutPrice) {
    }

    /** De uitvoer van {@code LegacyPriceResult} zonder {@code purchase*}. */
    record PortResult(Map<String, Double> salesPercentages, Map<String, Double> salesPrices, Double brutPercentage, Double brutPrice,
                      Double salesBasePrice) {
    }

    /** Regel-voor-regel {@code LegacyPriceCalculator.calculate} (Prodis r.31-73), zonder de purchase-tak. */
    static PortResult port(PortInput input) {
        double basePrice = value(input.basePrice());
        double salesBasePrice = value(input.salesBasePrice()) == 0d ? basePrice : value(input.salesBasePrice());
        double coefficient = value(input.coefficient()) == 0d ? 1d : value(input.coefficient());
        int decimals = input.decimals() == null || input.decimals() < 0 ? DEFAULT_DECIMALS : input.decimals();

        Map<String, Double> salesPercentages = new TreeMap<>(input.salesPercentages() == null ? Map.of() : input.salesPercentages());
        Map<String, Double> salesPrices = new TreeMap<>(input.salesPrices() == null ? Map.of() : input.salesPrices());
        Double brutPercentage = input.brutPercentage();
        Double brutPrice = input.brutPrice();

        String priceCode = trim(input.priceCode());
        if ("1".equals(priceCode)) {
            salesPercentages.replaceAll((code, percentage) -> 0d);
        } else if ("0".equals(priceCode) || "2".equals(priceCode)) {
            for (Map.Entry<String, Double> entry : salesPercentages.entrySet()) {
                salesPrices.put(entry.getKey(), round(salesBasePrice * value(entry.getValue()) / 100d * coefficient, decimals));
            }
        }

        String brutCode = trim(input.brutCode());
        if (OWN_BRUT_PERCENTAGE.equals(brutCode)) {
            brutPrice = round(salesBasePrice * value(brutPercentage) / 100d * coefficient, decimals);
        } else {
            if (salesPrices.containsKey(brutCode)) {
                brutPrice = salesPrices.get(brutCode);
            }
            brutPercentage = 0d;
        }

        return new PortResult(salesPercentages, salesPrices, brutPercentage, brutPrice, salesBasePrice);
    }

    /** {@code LegacyPriceCalculator.round}: {@code BigDecimal.valueOf(value).setScale(decimals, HALF_UP).doubleValue()}. */
    static double round(double value, int decimals) {
        return BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
    }

    private static double value(Double value) {
        return value == null ? 0d : value;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    // ---- hulp ---------------------------------------------------------------------------------------------------------

    private static <V> Map<Integer, V> codeMap(Map<Integer, V> source, String name) {
        Map<Integer, V> copy = new TreeMap<>();
        if (source == null) {
            return Collections.unmodifiableMap(copy);
        }
        for (Map.Entry<Integer, V> entry : source.entrySet()) {
            Integer code = entry.getKey();
            if (code == null || code < FIRST_CODE || code > LAST_CODE) {
                throw new IllegalArgumentException(name + ": code " + code + " is outside " + FIRST_CODE + ".." + LAST_CODE);
            }
            copy.put(code, entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<Integer, Double> toCodeMap(Map<String, Double> source) {
        Map<Integer, Double> result = new TreeMap<>();
        source.forEach((code, value) -> result.put(Integer.valueOf(code), value));
        return result;
    }
}
