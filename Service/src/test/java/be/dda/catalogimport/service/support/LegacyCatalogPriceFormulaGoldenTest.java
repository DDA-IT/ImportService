package be.dda.catalogimport.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.service.support.LegacyCatalogPriceFormula.ExistingDetail;
import be.dda.catalogimport.service.support.LegacyCatalogPriceFormula.Input;
import be.dda.catalogimport.service.support.LegacyCatalogPriceFormula.Notice;
import be.dda.catalogimport.service.support.LegacyCatalogPriceFormula.Outcome;
import be.dda.catalogimport.service.support.LegacyCatalogPriceFormula.PortInput;
import be.dda.catalogimport.service.support.LegacyCatalogPriceFormula.PortResult;
import be.dda.catalogimport.service.support.LegacyCatalogPriceFormula.Result;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * P2a (design {@code publicatie-prodis-db-direct-design.md} §3): {@link LegacyCatalogPriceFormula} moet bit-identiek rekenen
 * aan Prodis {@code LegacyPriceCalculator}. De verwachte waarden in {@code legacy-price-golden.csv} zijn niet zelf uitgerekend
 * maar gegenereerd met de Prodis-klasse zelf (commithash in de kop van het bestand).
 * <p>
 * Per golden-rij: (1) de letterlijke port met exact de Prodis-invoer; (2) bij catalogusrijen ook de publieke API, met de
 * percentages als bestaande Prodis-waarden (KEEP) en — bij prijscode 0 — als importwaarden die de bestaande overschrijven.
 */
class LegacyCatalogPriceFormulaGoldenTest {

    private static final String GOLDEN = "/legacy-price-golden.csv";
    private static final int COLUMNS = 34;
    private static final double OTHER_PERCENTAGE = 999.25d;

    // --- golden ---------------------------------------------------------------------------------------------------

    static Stream<Arguments> goldenRows() throws IOException {
        return readGolden().rows().stream().map(row -> Arguments.of(row.get("case_id"), row));
    }

    @Test
    void goldenFileIsTraceableAndCoversTheRequiredCases() throws IOException {
        Golden golden = readGolden();

        assertThat(golden.comments()).anySatisfy(line -> assertThat(line).matches("# prodis-commit: [0-9a-f]{40}"));
        assertThat(golden.comments()).anySatisfy(line -> assertThat(line).contains("LegacyPriceCalculator#calculate"));
        List<String> ids = golden.rows().stream().map(row -> row.get("case_id")).toList();
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(ids).filteredOn(id -> id.startsWith("T")).hasSize(9);
        assertThat(ids).filteredOn(id -> id.startsWith("C")).hasSize(1);
        assertThat(ids).filteredOn(id -> id.startsWith("B")).hasSizeGreaterThanOrEqualTo(12);
        assertThat(golden.rows()).filteredOn(row -> "Y".equals(row.get("catalog"))).hasSizeGreaterThanOrEqualTo(20);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("goldenRows")
    void literalPortIsBitIdenticalToProdis(String caseId, Map<String, String> row) {
        PortResult result = LegacyCatalogPriceFormula.port(new PortInput(
                text(row.get("price_code")),
                text(row.get("brut_code")),
                row.get("decimals").isEmpty() ? null : Integer.valueOf(row.get("decimals")),
                number(row.get("coefficient")),
                number(row.get("base_price")),
                number(row.get("sales_base_price")),
                cells(row, "pct"),
                number(row.get("brut_pct")),
                cells(row, "price"),
                number(row.get("brut_price"))));

        assertBitIdentical(caseId + " percentages", result.salesPercentages(), cells(row, "exp_pct"));
        assertBitIdentical(caseId + " prices", result.salesPrices(), cells(row, "exp_price"));
        assertBitIdentical(caseId + " brut_price_percentage", result.brutPercentage(), number(row.get("exp_brut_pct")));
        assertBitIdentical(caseId + " brut_price", result.brutPrice(), number(row.get("exp_brut_price")));
        assertBitIdentical(caseId + " salesBasePrice", result.salesBasePrice(), number(row.get("exp_sales_base_price")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("goldenRows")
    void catalogApiWithProdisPercentagesIsBitIdenticalToProdis(String caseId, Map<String, String> row) {
        if (!"Y".equals(row.get("catalog"))) {
            return;
        }
        String priceCode = assertCatalogParameters(caseId, row);
        Map<Integer, ExistingDetail> existing = new TreeMap<>();
        cells(row, "pct").forEach((code, percentage) -> existing.put(Integer.valueOf(code),
                new ExistingDetail(percentage, cells(row, "price").get(code))));

        Result result = LegacyCatalogPriceFormula.calculate(new Input(text(row.get("price_code")), text(row.get("brut_code")),
                new BigDecimal(row.get("base_price")), Map.of(), existing, number(row.get("brut_price")), number(row.get("brut_pct"))));

        assertThat(result.outcome()).isEqualTo(Outcome.CALCULATED);
        assertBitIdentical(caseId + " prices", stringKeys(result.prices()), cells(row, "exp_price"));
        assertBitIdentical(caseId + " brut_price", result.brutPrice(), number(row.get("exp_brut_price")));
        assertBitIdentical(caseId + " brut_price_percentage", result.brutPricePercentage(), number(row.get("exp_brut_pct")));
        if ("1".equals(priceCode)) {
            // Prg_1185 zet de VKP% op 0; §3: CatalogImport schrijft bij prijscode 1 geen percentages -> bestaande waarden
            assertThat(cells(row, "exp_pct").values()).allSatisfy(value -> assertThat(value).isEqualTo(0d));
            assertBitIdentical(caseId + " percentages", stringKeys(result.percentages()), cells(row, "pct"));
        } else {
            assertBitIdentical(caseId + " percentages", stringKeys(result.percentages()), cells(row, "exp_pct"));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("goldenRows")
    void catalogApiWithImportPercentagesIsBitIdenticalToProdis(String caseId, Map<String, String> row) {
        if (!"Y".equals(row.get("catalog")) || !"0".equals(assertCatalogParameters(caseId, row))) {
            return;
        }
        Map<Integer, ExistingDetail> existing = new TreeMap<>();
        Map<Integer, BigDecimal> imported = new HashMap<>();
        cells(row, "pct").forEach((code, percentage) -> {
            // bestaande Prodis-waarde bewust anders: de importwaarde moet ze overschrijven; 'null' blijft null (KEEP)
            existing.put(Integer.valueOf(code), new ExistingDetail(percentage == null ? null : OTHER_PERCENTAGE,
                    cells(row, "price").get(code)));
            if (percentage != null) {
                imported.put(Integer.valueOf(code), new BigDecimal(raw(row, "pct", code)));
            }
        });

        Result result = LegacyCatalogPriceFormula.calculate(new Input(text(row.get("price_code")), text(row.get("brut_code")),
                new BigDecimal(row.get("base_price")), imported, existing, number(row.get("brut_price")), number(row.get("brut_pct"))));

        assertBitIdentical(caseId + " prices", stringKeys(result.prices()), cells(row, "exp_price"));
        assertBitIdentical(caseId + " percentages", stringKeys(result.percentages()), cells(row, "exp_pct"));
        assertBitIdentical(caseId + " brut_price", result.brutPrice(), number(row.get("exp_brut_price")));
        assertBitIdentical(caseId + " brut_price_percentage", result.brutPricePercentage(), number(row.get("exp_brut_pct")));
        assertThat(result.notices()).extracting(Notice::code).doesNotContain(LegacyCatalogPriceFormula.CODE_PERCENTAGES_NOT_APPLICABLE);
    }

    // --- prijscode 0 ----------------------------------------------------------------------------------------------

    @Test
    void priceCodeZeroUsesImportPercentagesAndKeepsProdisPercentagesWhereTheImportIsEmpty() {
        Result result = calculate("0", "0", "80", imported(1, "60", 2, null),
                details(1, 50d, 1d, 2, 40d, 1d), 1d, 120d);

        assertThat(result.outcome()).isEqualTo(Outcome.CALCULATED);
        assertThat(result.percentages()).containsExactly(Map.entry(1, 60d), Map.entry(2, 40d));
        assertThat(result.prices()).containsExactly(Map.entry(1, 48d), Map.entry(2, 32d));
        assertThat(result.brutPrice()).isEqualTo(96d);
        assertThat(result.brutPricePercentage()).isEqualTo(120d);
        assertThat(result.notices()).isEmpty();
    }

    @Test
    void anExplicitZeroPercentageIsAValueHereBecauseQ2BelongsToThePlanner() {
        Result result = calculate("0", "0", "80", imported(1, "0"), details(1, 50d, 40d), null, null);

        assertThat(result.percentages()).containsEntry(1, 0d);
        assertThat(result.prices()).containsEntry(1, 0d);
        assertThat(result.notices()).isEmpty();
    }

    @Test
    void anImportPercentageWithoutProdisDetailGetsAPriceLikeProdisCreatesTheDetail() {
        Result result = calculate("0", "0", "80", imported(3, "10"), details(1, 50d, 40d), null, null);

        assertThat(result.prices()).containsExactly(Map.entry(1, 40d), Map.entry(3, 8d));
        assertThat(result.percentages()).containsExactly(Map.entry(1, 50d), Map.entry(3, 10d));
    }

    @Test
    void aMissingPercentageGivesPriceZeroLikeProdisButIsReported() {
        Result result = calculate("0", "0", "100", Map.of(), details(1, null, 12d, 2, 50d, 1d), null, null);

        assertThat(result.prices()).containsExactly(Map.entry(1, 0d), Map.entry(2, 50d));
        assertThat(result.notices()).singleElement().satisfies(notice -> {
            assertThat(notice.code()).isEqualTo(LegacyCatalogPriceFormula.CODE_PERCENTAGE_MISSING);
            assertThat(notice.detailCode()).isEqualTo(1);
        });
    }

    @Test
    void negativeBasePriceRoundsHalfUpAwayFromZero() {
        Result result = calculate("0", "0", "-12.345", imported(1, "100", 2, "50"), details(1, null, 0d, 2, null, 0d), null, null);

        assertThat(result.prices()).containsExactly(Map.entry(1, -12.35d), Map.entry(2, -6.17d));
    }

    // --- prijscode 2 ----------------------------------------------------------------------------------------------

    @Test
    void priceCodeTwoRecalculatesWithTheExistingPercentagesAndReportsTheIgnoredImportPercentages() {
        Result result = calculate("2", "0", "64.99", imported(1, "200", 2, null), details(1, 140d, 1d, 2, 130d, 1d), 1d, 150d);

        assertThat(result.percentages()).containsExactly(Map.entry(1, 140d), Map.entry(2, 130d));
        assertThat(result.prices()).containsExactly(Map.entry(1, 90.99d), Map.entry(2, 84.49d));
        assertThat(result.brutPrice()).isEqualTo(97.49d);
        assertThat(result.notices()).singleElement().satisfies(notice -> {
            assertThat(notice.code()).isEqualTo(LegacyCatalogPriceFormula.CODE_PERCENTAGES_NOT_APPLICABLE);
            assertThat(notice.detailCode()).isEqualTo(1);
            assertThat(notice.message()).contains("200").contains("price_code 2");
        });
    }

    // --- prijscode 1 ----------------------------------------------------------------------------------------------

    @Test
    void priceCodeOneKeepsSalesPricesAndPercentagesAndRecalculatesOnlyAnOwnBrutPercentage() {
        Result result = calculate("1", "0", "100", imported(1, "70"), details(1, 55d, 61.5d, 2, 50d, 52d), 99d, 125d);

        assertThat(result.prices()).containsExactly(Map.entry(1, 61.5d), Map.entry(2, 52d));
        assertThat(result.percentages()).containsExactly(Map.entry(1, 55d), Map.entry(2, 50d));
        assertThat(result.brutPrice()).isEqualTo(125d);
        assertThat(result.brutPricePercentage()).isEqualTo(125d);
        assertThat(result.notices()).extracting(Notice::code).containsExactly(LegacyCatalogPriceFormula.CODE_PERCENTAGES_NOT_APPLICABLE);
    }

    @Test
    void priceCodeOneWithBrutPoliticsNCopiesTheExistingSalesPriceAndClearsTheBrutPercentage() {
        Result result = calculate("1", "2", "100", Map.of(), details(1, 55d, 61.5d, 2, 50d, 52d), 99d, 125d);

        assertThat(result.brutPrice()).isEqualTo(52d);
        assertThat(result.brutPricePercentage()).isEqualTo(0d);
        assertThat(result.prices()).containsExactly(Map.entry(1, 61.5d), Map.entry(2, 52d));
        assertThat(result.notices()).isEmpty();
    }

    @Test
    void priceCodeOneDoesNotReportMissingPercentagesBecauseNothingIsRecalculated() {
        Result result = calculate("1", "0", "100", Map.of(), details(1, null, 61.5d), null, null);

        assertThat(result.prices()).containsEntry(1, 61.5d);
        assertThat(result.notices()).isEmpty();
    }

    // --- brut_price_politics --------------------------------------------------------------------------------------

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", "6", "A"})
    void brutPoliticsWithoutMatchingSalesPriceKeepsTheBrutPriceAndClearsItsPercentage(String politics) {
        Result result = calculate("0", politics, "100", Map.of(), details(1, 55d, 1d), 99d, 125d);

        assertThat(result.brutPrice()).isEqualTo(99d);
        assertThat(result.brutPricePercentage()).isEqualTo(0d);
    }

    @Test
    void brutPoliticsNCopiesTheRecalculatedSalesPrice() {
        Result result = calculate("0", " 3 ", "88.88", Map.of(), details(1, 150d, 1d, 2, 140d, 1d, 3, 130.555d, 1d), 99d, 125d);

        assertThat(result.prices()).containsEntry(3, 116.04d);
        assertThat(result.brutPrice()).isEqualTo(116.04d);
        assertThat(result.brutPricePercentage()).isEqualTo(0d);
    }

    // --- prijscode niet ondersteund -------------------------------------------------------------------------------

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "3", "9", "A", "01", "0 0"})
    void unsupportedPriceCodeIsRejectedWithoutCalculation(String priceCode) {
        Result result = calculate(priceCode, "0", "100", imported(1, "60"), details(1, 55d, 1d), 99d, 125d);

        assertThat(result.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(result.rejectionCode()).isEqualTo(LegacyCatalogPriceFormula.CODE_PRICE_CODE_UNSUPPORTED);
        assertThat(result.prices()).isEmpty();
        assertThat(result.percentages()).isEmpty();
        assertThat(result.brutPrice()).isNull();
        assertThat(result.brutPricePercentage()).isNull();
        assertThat(result.notices()).extracting(Notice::code).containsExactly(LegacyCatalogPriceFormula.CODE_PRICE_CODE_UNSUPPORTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", " 0", "1 ", " 2 "})
    void priceCodeIsTrimmedLikeProdis(String priceCode) {
        assertThat(calculate(priceCode, "0", "100", Map.of(), details(1, 55d, 1d), null, null).outcome()).isEqualTo(Outcome.CALCULATED);
    }

    // --- invoer, determinisme, idempotentie -----------------------------------------------------------------------

    @Test
    void basePriceIsRequiredBecauseThePlannerDecidesKeepOrZero() {
        assertThatThrownBy(() -> new Input("0", "0", null, Map.of(), Map.of(), null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 6, -1})
    void codesOutsideOneToFiveAreRefused(int code) {
        assertThatThrownBy(() -> calculate("0", "0", "1", imported(code, "10"), Map.of(), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculate("0", "0", "1", Map.of(), details(code, 10d, 1d), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNullExistingDetailIsRefused() {
        Map<Integer, ExistingDetail> existing = new HashMap<>();
        existing.put(1, null);

        assertThatThrownBy(() -> calculate("0", "0", "1", Map.of(), existing, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sameInputGivesTheSameResultAndApplyingTheResultAgainChangesNothing() {
        Map<Integer, BigDecimal> imported = imported(1, "135", 2, "128.5", 3, "122", 4, "117.25", 5, "110");
        Map<Integer, ExistingDetail> existing = details(1, 1d, 60d, 2, 1d, 60d, 3, 1d, 58d, 4, 1d, 56d, 5, 1d, 52d);

        Result first = calculate("0", "0", "47.83", imported, existing, 67d, 140.5d);
        Result second = calculate("0", "0", "47.83", imported, existing, 67d, 140.5d);
        assertThat(second).isEqualTo(first);

        Map<Integer, ExistingDetail> applied = new TreeMap<>();
        first.prices().forEach((code, price) -> applied.put(code, new ExistingDetail(first.percentages().get(code), price)));
        Result again = calculate("0", "0", "47.83", imported, applied, first.brutPrice(), first.brutPricePercentage());
        assertThat(again.prices()).isEqualTo(first.prices());
        assertThat(again.percentages()).isEqualTo(first.percentages());
        assertThat(again.brutPrice()).isEqualTo(first.brutPrice());
        assertThat(again.brutPricePercentage()).isEqualTo(first.brutPricePercentage());
    }

    @Test
    void inputAndResultAreImmutableCopies() {
        Map<Integer, BigDecimal> imported = new HashMap<>(imported(1, "60"));
        Map<Integer, ExistingDetail> existing = new HashMap<>(details(1, 50d, 40d));
        Input input = new Input("0", "0", new BigDecimal("80"), imported, existing, null, null);
        imported.put(1, new BigDecimal("1"));
        existing.clear();

        Result result = LegacyCatalogPriceFormula.calculate(input);

        assertThat(result.prices()).containsExactly(Map.entry(1, 48d));
        assertThatThrownBy(() -> result.prices().put(2, 1d)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.notices().add(null)).isInstanceOf(UnsupportedOperationException.class);
    }

    // --- hulp -----------------------------------------------------------------------------------------------------

    private static Result calculate(String priceCode, String politics, String basePrice, Map<Integer, BigDecimal> imported,
                                    Map<Integer, ExistingDetail> existing, Double brutPrice, Double brutPercentage) {
        return LegacyCatalogPriceFormula.calculate(new Input(priceCode, politics, new BigDecimal(basePrice), imported, existing,
                brutPrice, brutPercentage));
    }

    /** Paren code, waarde (tekst of null). */
    private static Map<Integer, BigDecimal> imported(Object... pairs) {
        Map<Integer, BigDecimal> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((Integer) pairs[i], pairs[i + 1] == null ? null : new BigDecimal((String) pairs[i + 1]));
        }
        return map;
    }

    /** Drietallen code, percentage, prijs. */
    private static Map<Integer, ExistingDetail> details(Object... triples) {
        Map<Integer, ExistingDetail> map = new TreeMap<>();
        for (int i = 0; i < triples.length; i += 3) {
            map.put((Integer) triples[i], new ExistingDetail((Double) triples[i + 1], (Double) triples[i + 2]));
        }
        return map;
    }

    private static String assertCatalogParameters(String caseId, Map<String, String> row) {
        String priceCode = text(row.get("price_code")) == null ? "" : text(row.get("price_code")).trim();
        assertThat(row.get("coefficient")).as(caseId).isEqualTo("1");
        assertThat(row.get("decimals")).as(caseId).isEqualTo("2");
        assertThat(row.get("sales_base_price")).as(caseId).isEmpty();
        assertThat(priceCode).as(caseId).isIn("0", "1", "2");
        assertThat(cells(row, "pct").keySet()).as(caseId).isEqualTo(cells(row, "price").keySet());
        return priceCode;
    }

    private static void assertBitIdentical(String what, Map<String, Double> actual, Map<String, Double> expected) {
        assertThat(actual.keySet()).as(what).isEqualTo(expected.keySet());
        expected.forEach((code, value) -> assertBitIdentical(what + " code " + code, actual.get(code), value));
    }

    private static void assertBitIdentical(String what, Double actual, Double expected) {
        if (expected == null) {
            assertThat(actual).as(what).isNull();
            return;
        }
        assertThat(actual).as(what).isNotNull();
        assertThat(Double.compare(actual, expected)).as(what + ": " + actual + " <> " + expected).isZero();
        assertThat(Double.doubleToRawLongBits(actual)).as(what).isEqualTo(Double.doubleToRawLongBits(expected));
    }

    private static Map<String, Double> stringKeys(Map<Integer, Double> source) {
        Map<String, Double> result = new TreeMap<>();
        source.forEach((code, value) -> result.put(String.valueOf(code), value));
        return result;
    }

    /** Leeg = afwezig, "null" = aanwezig met null. */
    private static Map<String, Double> cells(Map<String, String> row, String prefix) {
        Map<String, Double> map = new TreeMap<>();
        for (int code = 1; code <= 5; code++) {
            String cell = row.get(prefix + code);
            if (cell.isEmpty()) {
                continue;
            }
            map.put(String.valueOf(code), "null".equals(cell) ? null : Double.valueOf(cell));
        }
        return map;
    }

    private static String raw(Map<String, String> row, String prefix, String code) {
        return row.get(prefix + code);
    }

    private static Double number(String cell) {
        return cell.isEmpty() ? null : Double.valueOf(cell);
    }

    /** Tekstcel ongewijzigd (spaties tellen); {@code <null>} = null. */
    private static String text(String cell) {
        return "<null>".equals(cell) ? null : cell;
    }

    private record Golden(List<String> comments, List<Map<String, String>> rows) {
    }

    private static Golden readGolden() throws IOException {
        List<String> comments = new ArrayList<>();
        List<Map<String, String>> rows = new ArrayList<>();
        try (InputStream stream = LegacyCatalogPriceFormulaGoldenTest.class.getResourceAsStream(GOLDEN)) {
            assertThat(stream).as(GOLDEN).isNotNull();
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            String[] header = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#")) {
                    comments.add(line);
                    continue;
                }
                String[] cells = line.split(";", -1);
                assertThat(cells).as(line).hasSize(COLUMNS);
                if (header == null) {
                    header = cells;
                    assertThat(Set.of(header)).hasSize(COLUMNS);
                    continue;
                }
                Map<String, String> row = new HashMap<>();
                for (int i = 0; i < COLUMNS; i++) {
                    row.put(header[i], cells[i]);
                }
                rows.add(row);
            }
        }
        assertThat(rows).isNotEmpty();
        return new Golden(comments, rows);
    }
}
