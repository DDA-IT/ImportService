package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;

import be.dda.catalogimport.dao.PsimportPreviewDao.SnapshotPrice;
import be.dda.catalogimport.dao.PsimportPreviewDao.SourceRow;
import be.dda.catalogimport.service.PsimportPreviewMapper.Field;
import be.dda.catalogimport.service.PsimportPreviewMapper.Row;
import be.dda.catalogimport.service.PsimportPreviewMapper.State;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure unit-tests (geen database) van de state-bepaling en bedragformattering van de PSIMPORT-preview. */
class PsimportPreviewMapperTest {

    private static SourceRow source(BigDecimal price, String currency, String discountCode, String discountState,
                                    String refType, String refValue) {
        return snapshotted(price, currency, discountCode, discountState, refType, refValue, "VALUE", "Boormachine",
                List.of());
    }

    private static SourceRow snapshotted(BigDecimal price, String currency, String discountCode,
                                         String discountState, String refType, String refValue,
                                         String descriptionState, String description, List<SnapshotPrice> prices) {
        return new SourceRow(7L, 42L, "CREATE", "ACME", "G1", "R1", discountCode, discountState, price, currency,
                refType, refValue, 99L, description, descriptionState, prices);
    }

    private static SourceRow notSnapshotted() {
        return new SourceRow(7L, 42L, "CREATE", "ACME", "G1", "R1", null, "NOT_USED", new BigDecimal("1"), "EUR",
                null, null, null, null, null, List.of());
    }

    private static Field field(Row row, String code) {
        return row.fields().stream().filter(f -> f.code().equals(code)).findFirst().orElseThrow();
    }

    @Test
    void aCompleteRowMapsIdentityPriceAndCurrency() {
        Row row = PsimportPreviewMapper.map(source(new BigDecimal("12.500000"), "EUR", null, "NOT_USED", null, null));

        assertThat(row.complete()).isTrue();
        assertThat(row.batchId()).isEqualTo(7L);
        assertThat(row.mutationId()).isEqualTo(42L);
        assertThat(field(row, "SUPPLIER")).isEqualTo(new Field("SUPPLIER", "Leverancier Nummer", "ACME", State.VALUE));
        assertThat(field(row, "BASE_PRICE").value()).isEqualTo("12.500000");
        assertThat(field(row, "BASE_PRICE_CURRENCY").value()).isEqualTo("EUR");
    }

    @Test
    void amountsAreFormattedWithToPlainStringNeverScientific() {
        assertThat(PsimportPreviewMapper.basePrice(new BigDecimal("1E+3")).value()).isEqualTo("1000");
        assertThat(PsimportPreviewMapper.basePrice(new BigDecimal("0.000001")).value()).isEqualTo("0.000001");
        assertThat(PsimportPreviewMapper.basePrice(BigDecimal.ZERO).value()).isEqualTo("0");
    }

    @Test
    void aMissingPriceIsUnknownAndNeverZero() {
        Row row = PsimportPreviewMapper.map(source(null, "EUR", null, "NOT_USED", null, null));

        assertThat(field(row, "BASE_PRICE")).isEqualTo(new Field("BASE_PRICE", "Basisprijs", null, State.UNKNOWN));
        assertThat(row.complete()).isFalse();
    }

    @Test
    void aNullCurrencyIsUnknownAndMakesTheRowIncomplete() {
        Row row = PsimportPreviewMapper.map(source(new BigDecimal("1.00"), null, null, "NOT_USED", null, null));

        assertThat(field(row, "BASE_PRICE_CURRENCY").state()).isEqualTo(State.UNKNOWN);
        assertThat(field(row, "BASE_PRICE_CURRENCY").value()).isNull();
        assertThat(row.complete()).isFalse();
    }

    @Test
    void discountCodeStatesAreNeverCollapsedIntoOneAnother() {
        Field notUsed = PsimportPreviewMapper.discount(null, "NOT_USED");
        Field empty = PsimportPreviewMapper.discount("", "EMPTY");
        Field value = PsimportPreviewMapper.discount("K5", "VALUE");

        assertThat(notUsed.state()).isEqualTo(State.NOT_MAPPED);
        assertThat(notUsed.value()).isNull();
        assertThat(empty.state()).isEqualTo(State.VALUE);
        assertThat(empty.value()).isEmpty();
        assertThat(value).isEqualTo(new Field("DISCOUNT_CODE", "Korting Code", "K5", State.VALUE));
        assertThat(PsimportPreviewMapper.discount(null, null).state()).isEqualTo(State.UNKNOWN);
        assertThat(PsimportPreviewMapper.discount(null, "VALUE").state()).isEqualTo(State.UNKNOWN);
    }

    @Test
    void referenceTypeSelectsBarcodeOrExternalPimIdAndUnknownTypeIsUnknown() {
        List<Field> ean = PsimportPreviewMapper.references("EAN", "5449000000997");
        assertThat(ean.get(0)).isEqualTo(new Field("EAN", "Barcode", "5449000000997", State.VALUE));
        assertThat(ean.get(1).state()).isEqualTo(State.NOT_AVAILABLE_IN_MUTATION);

        List<Field> pim = PsimportPreviewMapper.references("PIM_ID", "P-1");
        assertThat(pim.get(1)).isEqualTo(new Field("PIM_ID", "EXTERNAL_PIM_ID", "P-1", State.VALUE));

        List<Field> unknown = PsimportPreviewMapper.references("CAB_ID", "C-1");
        assertThat(unknown).anyMatch(f -> f.state() == State.UNKNOWN && f.value() == null);
        assertThat(unknown).noneMatch(f -> "C-1".equals(f.value()));

        assertThat(PsimportPreviewMapper.references(null, null))
                .allMatch(f -> f.state() == State.NOT_AVAILABLE_IN_MUTATION && f.value() == null);
        assertThat(PsimportPreviewMapper.references("EAN", null).get(0).state()).isEqualTo(State.UNKNOWN);
    }

    @Test
    void contractFieldsNeverCarryAValue() {
        Row row = PsimportPreviewMapper.map(source(new BigDecimal("1"), "EUR", null, "NOT_USED", null, null));

        for (String code : List.of("PROCESS", "DELETE", "RECORD", "NUMBER")) {
            assertThat(field(row, code).state()).isEqualTo(State.NOT_CONTRACTED);
            assertThat(field(row, code).value()).isNull();
        }
    }

    @Test
    void descriptionFollowsTheSnapshotState() {
        assertThat(PsimportPreviewMapper.description(true, "Boor, \"XL\"", "VALUE"))
                .isEqualTo(new Field("DESCRIPTION", "Omschrijving NED", "Boor, \"XL\"", State.VALUE));
        Field empty = PsimportPreviewMapper.description(true, "", "EMPTY");
        assertThat(empty.state()).isEqualTo(State.VALUE);
        assertThat(empty.value()).isEmpty();
        Field notMapped = PsimportPreviewMapper.description(true, null, "NOT_MAPPED");
        assertThat(notMapped.state()).isEqualTo(State.NOT_MAPPED);
        assertThat(notMapped.value()).isNull();
        assertThat(PsimportPreviewMapper.description(true, null, "VALUE").state()).isEqualTo(State.UNKNOWN);
        assertThat(PsimportPreviewMapper.description(true, "x", "BOGUS").state()).isEqualTo(State.UNKNOWN);
    }

    @Test
    void percentagesAreStringsFromTheSnapshotComponentsAndAMissingOneIsNotMappedNeverZero() {
        Row row = PsimportPreviewMapper.map(snapshotted(new BigDecimal("1"), "EUR", null, "NOT_USED", null, null,
                "VALUE", "Boor", List.of(
                        new SnapshotPrice("VKP1", new BigDecimal("125.500000000000"), "OK"),
                        new SnapshotPrice("VKP2", new BigDecimal("1E+2"), "OK"),
                        new SnapshotPrice("VKP3", null, "NO_BASE_PRICE"),
                        new SnapshotPrice("AKP", new BigDecimal("50"), "OK"))));

        assertThat(field(row, "VKP1_PCT")).isEqualTo(new Field("VKP1_PCT", "Prijs 1 %", "125.500000000000", State.VALUE));
        assertThat(field(row, "VKP2_PCT").value()).isEqualTo("100");
        assertThat(field(row, "VKP3_PCT").state()).isEqualTo(State.UNKNOWN);
        assertThat(field(row, "VKP3_PCT").value()).isNull();
        assertThat(field(row, "VKP4_PCT").state()).isEqualTo(State.NOT_MAPPED);
        assertThat(field(row, "VKP4_PCT").value()).isNull();
        assertThat(field(row, "VKP5_PCT").state()).isEqualTo(State.NOT_MAPPED);
        assertThat(row.complete()).isFalse(); // VKP3 is UNKNOWN
    }

    @Test
    void aSnapshottedRowWithoutPercentagesIsCompleteWhenNothingIsUnknown() {
        Row row = PsimportPreviewMapper.map(source(new BigDecimal("1"), "EUR", null, "NOT_USED", null, null));

        assertThat(field(row, "DESCRIPTION").state()).isEqualTo(State.VALUE);
        assertThat(field(row, "VKP1_PCT").state()).isEqualTo(State.NOT_MAPPED);
        assertThat(row.complete()).isTrue();
    }

    @Test
    void aRowWithoutSnapshotIsNotSnapshottedAndIncomplete() {
        Row row = PsimportPreviewMapper.map(notSnapshotted());

        for (String code : List.of("DESCRIPTION", "VKP1_PCT", "VKP2_PCT", "VKP3_PCT", "VKP4_PCT", "VKP5_PCT")) {
            assertThat(field(row, code).state()).isEqualTo(State.NOT_SNAPSHOTTED);
            assertThat(field(row, code).value()).isNull();
        }
        assertThat(row.complete()).isFalse();
    }
}
