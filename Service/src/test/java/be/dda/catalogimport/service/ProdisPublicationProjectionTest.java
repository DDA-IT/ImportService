package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.dao.ProdisPublicationSourceDao.SourcePrice;
import be.dda.catalogimport.dao.ProdisPublicationSourceDao.SourceRow;
import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticleIdentity;
import be.dda.catalogimport.service.ProdisPublicationProjection.Action;
import be.dda.catalogimport.service.ProdisPublicationProjection.AmountField;
import be.dda.catalogimport.service.ProdisPublicationProjection.FieldState;
import be.dda.catalogimport.service.ProdisPublicationProjection.Item;
import be.dda.catalogimport.service.ProdisPublicationProjection.Notice;
import be.dda.catalogimport.service.ProdisPublicationProjection.Severity;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * P2b (design {@code publicatie-prodis-db-direct-design.md} par. 2.5, 2.6, 6): {@link ProdisPublicationProjection} — één
 * test per regel: identiteit, actie, munt, leeg/0/expected per veld, precisie, omschrijving, deterministische
 * {@code requestHash}.
 */
class ProdisPublicationProjectionTest {

    // --- normaal scenario ---------------------------------------------------------------------------------------------

    @Test
    void projectsAnUpdateWithIdentityFieldsAndExpectedBasePrice() {
        Item item = ProdisPublicationProjection.project(row(r -> {
        }));

        assertThat(item.payloadSpecVersion()).isEqualTo(1);
        assertThat(item.bundleId()).isEqualTo(7L);
        assertThat(item.mutationId()).isEqualTo(42L);
        assertThat(item.action()).isEqualTo(Action.UPDATE);
        assertThat(item.identity().supplierNumber()).isEqualTo("0601");
        assertThat(item.identity().groupLegacyCode()).isEqualTo("0601|GRP|K1");
        assertThat(item.identity().supplierReference()).isEqualTo("REF-1");
        assertThat(item.identity().scopeKey()).isEqualTo("050");
        assertThat(item.identity().toLookupIdentity())
                .isEqualTo(new CatalogArticleIdentity("0601", "0601|GRP|K1", "050", "REF-1"));
        assertThat(item.currency()).isEqualTo("EUR");
        assertThat(item.basePrice()).isEqualTo(new AmountField(FieldState.VALUE, new BigDecimal("120.000000")));
        assertThat(item.expected().basePrice()).isEqualByComparingTo("100");
        assertThat(item.description().state()).isEqualTo(FieldState.VALUE);
        assertThat(item.description().value()).isEqualTo("Nieuwe omschrijving");
        assertThat(item.notices()).isEmpty();
        assertThat(item.rejected()).isFalse();
        assertThat(item.rejection()).isNull();
        assertThat(item.requestHash()).matches("[0-9a-f]{64}");
    }

    @Test
    void discountStateEmptyAndNotUsedGiveAnEmptyThirdComponentLikeTheProdisDatasync() {
        Item empty = ProdisPublicationProjection.project(row(r -> {
            r.discountCode = "";
            r.discountState = "EMPTY";
        }));
        Item notUsed = ProdisPublicationProjection.project(row(r -> {
            r.discountCode = null;
            r.discountState = "NOT_USED";
        }));

        assertThat(empty.identity().groupLegacyCode()).isEqualTo("0601|GRP|");
        assertThat(notUsed.identity().groupLegacyCode()).isEqualTo("0601|GRP|");
        assertThat(empty.rejected()).isFalse();
        assertThat(notUsed.rejected()).isFalse();
    }

    // --- identiteit en actie -----------------------------------------------------------------------------------------

    @Test
    void anIncompleteIdentityBlocksTheItem() {
        Item noSupplier = ProdisPublicationProjection.project(row(r -> r.supplier = null));
        Item noLibrary = ProdisPublicationProjection.project(row(r -> r.libraryCode = null));
        Item unknownDiscount = ProdisPublicationProjection.project(row(r -> r.discountState = null));
        Item valueWithoutCode = ProdisPublicationProjection.project(row(r -> r.discountCode = null));

        for (Item item : List.of(noSupplier, noLibrary, unknownDiscount, valueWithoutCode)) {
            assertThat(item.rejected()).isTrue();
            assertThat(item.rejection().code()).isEqualTo("IDENTITY_INCOMPLETE");
            assertThat(item.identity().complete()).isFalse();
            assertThatThrownBy(() -> item.identity().toLookupIdentity()).isInstanceOf(IllegalStateException.class);
        }
        assertThat(noSupplier.rejection().message()).contains("supplier");
        assertThat(noLibrary.rejection().message()).contains("library code");
        assertThat(unknownDiscount.rejection().message()).contains("discount code");
    }

    @ParameterizedTest
    @ValueSource(strings = {"IMPORT_MARKER", "IDENTITY_REFERENCE_INCIDENT", "create", ""})
    void anActionOtherThanCreateOrUpdateBlocksTheItem(String actionType) {
        Item item = ProdisPublicationProjection.project(row(r -> r.actionType = actionType));

        assertThat(item.action()).isNull();
        assertThat(item.rejection().code()).isEqualTo("ACTION_UNSUPPORTED");
    }

    @Test
    void aCreateCarriesValuesWithoutExpectedAndWithoutExpectedNotAvailable() {
        Item item = ProdisPublicationProjection.project(row(r -> {
            r.actionType = "CREATE";
            r.before = null;
            r.prices.add(new SourcePrice("VKP1", new BigDecimal("12.000000"), new BigDecimal("10.000000000000"), "EUR",
                    "OK", null));
        }));

        assertThat(item.action()).isEqualTo(Action.CREATE);
        assertThat(item.basePrice().state()).isEqualTo(FieldState.VALUE);
        assertThat(item.salesPercentages().get(1).state()).isEqualTo(FieldState.VALUE);
        assertThat(item.expected().basePrice()).isNull();
        assertThat(item.expected().percentages()).isEmpty();
        assertThat(item.notices()).isEmpty();
    }

    // --- munt ----------------------------------------------------------------------------------------------------------

    @Test
    void aCurrencyOtherThanEurIsRejectedAndEurIsNeverAssumed() {
        Item usd = ProdisPublicationProjection.project(row(r -> r.currency = "USD"));
        Item missing = ProdisPublicationProjection.project(row(r -> r.currency = null));
        Item lowercase = ProdisPublicationProjection.project(row(r -> r.currency = "eur"));

        assertThat(usd.rejection().code()).isEqualTo("CURRENCY_NOT_SUPPORTED_BY_PRODIS");
        assertThat(usd.rejection().field()).isEqualTo("BASE_PRICE_CURRENCY");
        assertThat(missing.rejection().code()).isEqualTo("CURRENCY_NOT_SUPPORTED_BY_PRODIS");
        assertThat(missing.rejection().message()).contains("unknown");
        assertThat(lowercase.rejection().code()).isEqualTo("CURRENCY_NOT_SUPPORTED_BY_PRODIS");
    }

    @Test
    void aSalesPriceComponentInAnotherCurrencyIsRejected() {
        Item item = ProdisPublicationProjection.project(row(r -> r.prices.add(
                new SourcePrice("VKP2", new BigDecimal("5"), new BigDecimal("10"), "USD", "OK", null))));

        assertThat(item.rejection().code()).isEqualTo("CURRENCY_NOT_SUPPORTED_BY_PRODIS");
        assertThat(item.rejection().field()).isEqualTo("VKP2_PCT");
    }

    // --- basisprijs ---------------------------------------------------------------------------------------------------

    @Test
    void anEmptyBasePriceKeepsTheProdisValue() {
        Item item = ProdisPublicationProjection.project(row(r -> r.after = null));

        assertThat(item.basePrice().state()).isEqualTo(FieldState.KEEP);
        assertThat(item.basePrice().value()).isNull();
        assertThat(item.expected().basePrice()).isNull();
        assertThat(item.notices()).extracting(Notice::code, Notice::severity, Notice::field)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("EMPTY_KEEPS_EXISTING", Severity.WARNING, "BASE_PRICE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.000000", "-0.00"})
    void anExplicitZeroBasePriceKeepsTheProdisValue(String zero) {
        Item item = ProdisPublicationProjection.project(row(r -> r.after = new BigDecimal(zero)));

        assertThat(item.basePrice().state()).isEqualTo(FieldState.KEEP);
        assertThat(codes(item)).containsExactly("ZERO_KEEPS_EXISTING");
        assertThat(item.rejected()).isFalse();
    }

    @Test
    void anUpdateWithoutPreviousBasePriceKeepsTheProdisValue() {
        Item item = ProdisPublicationProjection.project(row(r -> r.before = null));

        assertThat(item.basePrice().state()).isEqualTo(FieldState.KEEP);
        assertThat(item.expected().basePrice()).isNull();
        assertThat(codes(item)).containsExactly("EXPECTED_NOT_AVAILABLE");
    }

    @Test
    void aNegativeBasePriceIsAValueAndNeverCorrected() {
        Item item = ProdisPublicationProjection.project(row(r -> r.after = new BigDecimal("-5.50")));

        assertThat(item.basePrice().state()).isEqualTo(FieldState.VALUE);
        assertThat(item.basePrice().value()).isEqualByComparingTo("-5.50");
        assertThat(item.notices()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.1234567", "1234567890123456", "1234567890123456.5"})
    void aBasePriceThatDoesNotFitNumeric21x6IsRejectedForPrecisionLoss(String amount) {
        Item item = ProdisPublicationProjection.project(row(r -> r.after = new BigDecimal(amount)));

        assertThat(item.rejection().code()).isEqualTo("PRECISION_LOSS");
        assertThat(item.rejection().field()).isEqualTo("BASE_PRICE");
        assertThat(codes(item)).doesNotContain("EXCEEDS_LEGACY_PRECISION");
    }

    @Test
    void aBasePriceAtTheNumeric21x6LimitsIsAccepted() {
        Item item = ProdisPublicationProjection.project(row(r -> r.after = new BigDecimal("999999999999999.000000")));

        assertThat(item.rejected()).isFalse();
        assertThat(item.notices()).isEmpty();
    }

    @Test
    void moreThanThreeDecimalsOnTheBasePriceIsALegacyPrecisionWarning() {
        Item four = ProdisPublicationProjection.project(row(r -> r.after = new BigDecimal("12.345600")));
        Item three = ProdisPublicationProjection.project(row(r -> r.after = new BigDecimal("12.345000")));
        Item six = ProdisPublicationProjection.project(row(r -> r.after = new BigDecimal("12.123456")));

        assertThat(codes(four)).containsExactly("EXCEEDS_LEGACY_PRECISION");
        assertThat(four.rejected()).isFalse();
        assertThat(codes(three)).isEmpty();
        assertThat(codes(six)).containsExactly("EXCEEDS_LEGACY_PRECISION");
    }

    // --- percentages --------------------------------------------------------------------------------------------------

    @Test
    void aSpecVersion1SnapshotNeverWritesPercentages() {
        Item item = ProdisPublicationProjection.project(row(r -> {
            r.specVersion = "1";
            r.prices.add(vkp(1, "12", "10", "10"));
        }));

        assertThat(item.salesPercentages().get(1).state()).isEqualTo(FieldState.KEEP);
        assertThat(item.expected().percentages()).isEmpty();
        assertThat(item.notices()).extracting(Notice::code, Notice::field)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("EXPECTED_NOT_AVAILABLE", "VKP1_PCT"));
    }

    @Test
    void aSpecVersion2SnapshotWritesAPercentageWithItsBeforeValueAsExpected() {
        Item item = ProdisPublicationProjection.project(row(r -> {
            r.specVersion = "2";
            r.prices.add(vkp(2, "30", "25.000000000000", "20"));
        }));

        assertThat(item.salesPercentages().get(2)).isEqualTo(new AmountField(FieldState.VALUE,
                new BigDecimal("25.000000000000")));
        assertThat(item.expected().percentages().get(2)).isEqualByComparingTo("20");
        assertThat(item.sourceAmounts().get(2)).isEqualByComparingTo("30");
        assertThat(item.notices()).isEmpty();
    }

    @Test
    void aSpecVersion2PercentageWithoutBeforeValueKeepsTheProdisValue() {
        Item item = ProdisPublicationProjection.project(row(r -> {
            r.specVersion = "2";
            r.prices.add(vkp(3, "30", "25", null));
        }));

        assertThat(item.salesPercentages().get(3).state()).isEqualTo(FieldState.KEEP);
        assertThat(codes(item)).containsExactly("EXPECTED_NOT_AVAILABLE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "x", "v2"})
    void anUnreadableSpecVersionIsTreatedAsWithoutBeforePercentages(String version) {
        Item item = ProdisPublicationProjection.project(row(r -> {
            r.specVersion = version;
            r.prices.add(vkp(1, "12", "10", "10"));
        }));

        assertThat(item.salesPercentages().get(1).state()).isEqualTo(FieldState.KEEP);
        assertThat(codes(item)).containsExactly("EXPECTED_NOT_AVAILABLE");
    }

    @Test
    void anEmptyAndAZeroPercentageKeepTheProdisValue() {
        Item item = ProdisPublicationProjection.project(row(r -> {
            r.specVersion = "2";
            r.prices.add(vkp(1, "0", null, "10"));
            r.prices.add(vkp(2, "0", "0.000000000000", "20"));
        }));

        assertThat(item.salesPercentages().get(1).state()).isEqualTo(FieldState.KEEP);
        assertThat(item.salesPercentages().get(2).state()).isEqualTo(FieldState.KEEP);
        assertThat(item.expected().percentages()).isEmpty();
        assertThat(item.notices()).extracting(Notice::code, Notice::field).containsExactly(
                org.assertj.core.groups.Tuple.tuple("EMPTY_KEEPS_EXISTING", "VKP1_PCT"),
                org.assertj.core.groups.Tuple.tuple("ZERO_KEEPS_EXISTING", "VKP2_PCT"));
    }

    @Test
    void unmappedAndNonSalesComponentsAreOmitted() {
        Item item = ProdisPublicationProjection.project(row(r -> {
            r.specVersion = "2";
            r.prices.add(new SourcePrice("AKP", new BigDecimal("50"), new BigDecimal("50"), "EUR", "OK", null));
            r.prices.add(new SourcePrice("VKP_GROSS", new BigDecimal("150"), new BigDecimal("150"), "EUR", "OK", null));
            r.prices.add(new SourcePrice("VKP6", new BigDecimal("1"), new BigDecimal("1"), "EUR", "OK", null));
            r.prices.add(vkp(4, "48", "40", "40"));
        }));

        assertThat(item.salesPercentages()).containsOnlyKeys(4);
        assertThat(item.sourceAmounts()).containsOnlyKeys(4);
    }

    @Test
    void aPercentageWithMoreThan15SignificantDigitsIsRejected() {
        Item item = ProdisPublicationProjection.project(row(r -> {
            r.specVersion = "2";
            r.prices.add(vkp(1, "1", "1234.333333333333", "10"));
        }));

        assertThat(item.rejection().code()).isEqualTo("PRECISION_LOSS");
        assertThat(item.rejection().field()).isEqualTo("VKP1_PCT");
    }

    @Test
    void moreThanTwoDecimalsOnAPercentageIsALegacyPrecisionWarning() {
        Item item = ProdisPublicationProjection.project(row(r -> {
            r.specVersion = "2";
            r.prices.add(vkp(1, "33.33", "33.333333333333", "10"));
            r.prices.add(vkp(2, "12.5", "12.500000000000", "10"));
        }));

        assertThat(item.rejected()).isFalse();
        assertThat(item.notices()).extracting(Notice::code, Notice::field)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("EXCEEDS_LEGACY_PRECISION", "VKP1_PCT"));
    }

    // --- omschrijving -------------------------------------------------------------------------------------------------

    @Test
    void descriptionStatesMapToValueKeepOrOmitted() {
        Item empty = ProdisPublicationProjection.project(row(r -> {
            r.description = "";
            r.descriptionState = "EMPTY";
        }));
        Item notMapped = ProdisPublicationProjection.project(row(r -> {
            r.description = null;
            r.descriptionState = "NOT_MAPPED";
        }));

        assertThat(empty.description().state()).isEqualTo(FieldState.KEEP);
        assertThat(codes(empty)).containsExactly("EMPTY_KEEPS_EXISTING");
        assertThat(notMapped.description()).isNull();
        assertThat(notMapped.notices()).isEmpty();
    }

    @Test
    void anInconsistentSnapshotDescriptionBlocksTheItem() {
        Item valueWithoutText = ProdisPublicationProjection.project(row(r -> r.description = null));
        Item blankValue = ProdisPublicationProjection.project(row(r -> r.description = "  "));
        Item unknownState = ProdisPublicationProjection.project(row(r -> r.descriptionState = "BOGUS"));
        Item noState = ProdisPublicationProjection.project(row(r -> r.descriptionState = null));

        for (Item item : List.of(valueWithoutText, blankValue, unknownState, noState)) {
            assertThat(item.rejection().code()).isEqualTo("SNAPSHOT_INCOMPLETE");
            assertThat(item.rejection().field()).isEqualTo("DESCRIPTION");
        }
    }

    // --- meldingen: volgorde en combinatie -----------------------------------------------------------------------------

    @Test
    void noticesComeInAFixedOrderAndAllBlockingReasonsAreKept() {
        Item item = ProdisPublicationProjection.project(row(r -> {
            r.supplier = null;
            r.currency = "USD";
            r.after = BigDecimal.ZERO;
            r.descriptionState = "EMPTY";
            r.description = "";
        }));

        assertThat(codes(item)).containsExactly("IDENTITY_INCOMPLETE", "CURRENCY_NOT_SUPPORTED_BY_PRODIS",
                "ZERO_KEEPS_EXISTING", "EMPTY_KEEPS_EXISTING");
        assertThat(item.rejection().code()).isEqualTo("IDENTITY_INCOMPLETE");
    }

    // --- requestHash: deterministisch en idempotent ---------------------------------------------------------------------

    @Test
    void theSameRowTwiceGivesTheSameItemAndHash() {
        Item first = ProdisPublicationProjection.project(row(r -> r.prices.add(vkp(1, "12", "10", "10"))));
        Item second = ProdisPublicationProjection.project(row(r -> r.prices.add(vkp(1, "12", "10", "10"))));

        assertThat(second).isEqualTo(first);
        assertThat(second.requestHash()).isEqualTo(first.requestHash());
    }

    @Test
    void theHashIgnoresTrailingZerosAndComponentOrderButNotContent() {
        String base = ProdisPublicationProjection.project(row(r -> {
            r.specVersion = "2";
            r.prices.add(vkp(1, "12", "10", "10"));
            r.prices.add(vkp(2, "24", "20", "20"));
        })).requestHash();
        String scaled = ProdisPublicationProjection.project(row(r -> {
            r.specVersion = "2";
            r.after = new BigDecimal("120");
            r.prices.add(vkp(2, "24.000000", "20.000000000000", "20.0"));
            r.prices.add(vkp(1, "12.000000", "10.000000000000", "10.0"));
        })).requestHash();

        assertThat(scaled).isEqualTo(base);
        assertThat(hashWith(r -> r.after = new BigDecimal("120.01"))).isNotEqualTo(hashWith(r -> {
        }));
        assertThat(hashWith(r -> r.before = new BigDecimal("100.01"))).isNotEqualTo(hashWith(r -> {
        }));
        assertThat(hashWith(r -> r.mutationId = 43L)).isNotEqualTo(hashWith(r -> {
        }));
        assertThat(hashWith(r -> r.reference = "REF-2")).isNotEqualTo(hashWith(r -> {
        }));
        assertThat(hashWith(r -> r.libraryCode = "051")).isNotEqualTo(hashWith(r -> {
        }));
        assertThat(hashWith(r -> r.discountCode = "K2")).isNotEqualTo(hashWith(r -> {
        }));
        assertThat(hashWith(r -> r.description = "Andere")).isNotEqualTo(hashWith(r -> {
        }));
        assertThat(hashWith(r -> r.actionType = "CREATE")).isNotEqualTo(hashWith(r -> {
        }));
        // Bundel en snapshot-id zitten er bewust niet in: dezelfde mutatie met dezelfde inhoud = hetzelfde verzoek.
        assertThat(hashWith(r -> r.bundleId = 8L)).isEqualTo(hashWith(r -> {
        }));
    }

    @Test
    void theCanonicalTextIsDocumentedFieldByField() {
        Item item = ProdisPublicationProjection.project(row(r -> {
            r.specVersion = "2";
            r.prices.add(vkp(1, "12", "10.50", "10"));
        }));

        String us = "\u001F";
        assertThat(ProdisPublicationProjection.canonical(item)).isEqualTo(String.join(us, "prodis-publication", "1", "42",
                "UPDATE", "0601", "0601|GRP|K1", "REF-1", "050", "EUR", "BASE_PRICE", "VALUE", "120", "VKP1_PCT", "VALUE",
                "10.5", "DESCRIPTION", "VALUE", "Nieuwe omschrijving", "EXPECTED_BASE_PRICE", "100", "EXPECTED_VKP1_PCT",
                "10") + us + "\u001E");
    }

    @Test
    void recordsRefuseAnInconsistentFieldState() {
        assertThatThrownBy(() -> new AmountField(FieldState.VALUE, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AmountField(FieldState.KEEP, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProdisPublicationProjection.DescriptionField(FieldState.VALUE, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- hulp -----------------------------------------------------------------------------------------------------------

    private static String hashWith(Consumer<RowBuilder> change) {
        return ProdisPublicationProjection.project(row(change)).requestHash();
    }

    private static List<String> codes(Item item) {
        return item.notices().stream().map(Notice::code).toList();
    }

    static SourcePrice vkp(int code, String amount, String percentage, String before) {
        return new SourcePrice("VKP" + code, amount == null ? null : new BigDecimal(amount),
                percentage == null ? null : new BigDecimal(percentage), "EUR", "OK",
                before == null ? null : new BigDecimal(before));
    }

    static SourceRow row(Consumer<RowBuilder> change) {
        RowBuilder builder = new RowBuilder();
        change.accept(builder);
        return builder.build();
    }

    /** Een UPDATE 100 → 120 EUR met omschrijving en kortingscode; snapshot spec 1, zonder prijscomponenten. */
    static final class RowBuilder {
        long bundleId = 7L;
        long mutationId = 42L;
        String actionType = "UPDATE";
        String supplier = "0601";
        String group = "GRP";
        String reference = "REF-1";
        String discountCode = "K1";
        String discountState = "VALUE";
        String libraryCode = "050";
        BigDecimal before = new BigDecimal("100.000000");
        BigDecimal after = new BigDecimal("120.000000");
        String currency = "EUR";
        String description = "Nieuwe omschrijving";
        String descriptionState = "VALUE";
        String specVersion = "1";
        List<SourcePrice> prices = new ArrayList<>();

        SourceRow build() {
            return new SourceRow(bundleId, 900L + mutationId, mutationId, actionType, supplier, group, reference,
                    discountCode, discountState, libraryCode, before, after, currency, description, descriptionState,
                    specVersion, prices);
        }
    }
}
