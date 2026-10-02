package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** {@link SetupInput}: de invoercontroles en de stabiele veldcodes (NT-3). */
class SetupInputTest {

    private static void assertBadRequest(Runnable call, String code, String message) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BadRequestException.class, e -> {
            assertThat(e.getCode()).isEqualTo(code);
            assertThat(e.getMessage()).isEqualTo(message);
        });
    }

    @Test
    void fieldCodeTurnsCamelCaseIntoUpperSnakeCase() {
        assertThat(SetupInput.fieldCode("delimiter", "REQUIRED")).isEqualTo("DELIMITER_REQUIRED");
        assertThat(SetupInput.fieldCode("sourceOrganisationCode", "TOO_LONG"))
                .isEqualTo("SOURCE_ORGANISATION_CODE_TOO_LONG");
        assertThat(SetupInput.fieldCode("creationThresholdSharePercent", "INVALID"))
                .isEqualTo("CREATION_THRESHOLD_SHARE_PERCENT_INVALID");
    }

    @Test
    void requireTextTrimsAndReturnsTheValue() {
        assertThat(SetupInput.requireText("  abc  ", "code", 5)).isEqualTo("abc");
        assertThat(SetupInput.requireText("abcde", "code", 5)).isEqualTo("abcde");
    }

    @Test
    void requireTextRefusesBlankAndNull() {
        assertBadRequest(() -> SetupInput.requireText(null, "supplierField", 10), "SUPPLIER_FIELD_REQUIRED",
                "supplierField must not be blank");
        assertBadRequest(() -> SetupInput.requireText("   ", "code", 10), "CODE_REQUIRED",
                "code must not be blank");
    }

    @Test
    void requireTextRefusesATooLongValueAfterTrimming() {
        assertBadRequest(() -> SetupInput.requireText("abcdef", "code", 5), "CODE_TOO_LONG",
                "code must be at most 5 characters");
    }

    @Test
    void optionalTextTurnsNullAndBlankIntoNullAndOtherwiseValidates() {
        assertThat(SetupInput.optionalText(null, "fixedValue", 5)).isNull();
        assertThat(SetupInput.optionalText("  ", "fixedValue", 5)).isNull();
        assertThat(SetupInput.optionalText(" ab ", "fixedValue", 5)).isEqualTo("ab");
        assertBadRequest(() -> SetupInput.optionalText("abcdef", "fixedValue", 5), "FIXED_VALUE_TOO_LONG",
                "fixedValue must be at most 5 characters");
    }

    @Test
    void requireReturnsTheValueOrRefusesNull() {
        assertThat(SetupInput.require("x", "operator")).isEqualTo("x");
        assertBadRequest(() -> SetupInput.require(null, "operator"), "OPERATOR_REQUIRED",
                "operator must not be null");
    }

    @Test
    void requireNotNegativeAcceptsZeroAndRefusesNegative() {
        assertThat(SetupInput.requireNotNegative(BigDecimal.ZERO, "priceDeviationPercent"))
                .isEqualByComparingTo("0");
        assertThat(SetupInput.requireNotNegative(new BigDecimal("12.5"), "priceDeviationPercent"))
                .isEqualByComparingTo("12.5");
        assertBadRequest(() -> SetupInput.requireNotNegative(new BigDecimal("-0.01"), "priceDeviationPercent"),
                "PRICE_DEVIATION_PERCENT_INVALID", "priceDeviationPercent must not be negative");
    }

    @Test
    void orDefaultFallsBackOnlyOnNull() {
        assertThat(SetupInput.orDefault(null, "setup-api")).isEqualTo("setup-api");
        assertThat(SetupInput.orDefault("jan", "setup-api")).isEqualTo("jan");
        assertThat(SetupInput.orDefault("", "setup-api")).isEmpty();
    }
}
