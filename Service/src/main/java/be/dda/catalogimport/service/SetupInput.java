package be.dda.catalogimport.service;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Invoercontroles van {@link SetupService} (stap 9, S9-a; geen gedragswijziging): verplichte en optionele
 * tekst, verplichte waarden, niet-negatieve bedragen en de stabiele foutcode per veld.
 */
final class SetupInput {

    private SetupInput() {
    }

    // NT-3: elke veldfout hieronder draagt een stabiele code naast de ongewijzigde tekst. Vorm
    // <VELD>_REQUIRED / <VELD>_TOO_LONG / <VELD>_INVALID, met <VELD> de veldnaam uit het verzoek in
    // hoofdletters met underscores (fieldCode) — dezelfde vorm als CHANGE_REASON_REQUIRED en
    // CREDENTIAL_LABEL_REQUIRED.

    static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(fieldCode(field, "REQUIRED"), field + " must not be blank");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new BadRequestException(fieldCode(field, "TOO_LONG"),
                    field + " must be at most " + maxLength + " characters");
        }
        return trimmed;
    }

    static String optionalText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireText(value, field, maxLength);
    }

    static <T> T require(T value, String field) {
        if (value == null) {
            throw new BadRequestException(fieldCode(field, "REQUIRED"), field + " must not be null");
        }
        return value;
    }

    static BigDecimal requireNotNegative(BigDecimal value, String field) {
        if (value.signum() < 0) {
            throw new BadRequestException(fieldCode(field, "INVALID"), field + " must not be negative");
        }
        return value;
    }

    /**
     * {@code delimiter} + {@code REQUIRED} → {@code DELIMITER_REQUIRED};
     * {@code sourceOrganisationCode} + {@code TOO_LONG} → {@code SOURCE_ORGANISATION_CODE_TOO_LONG}.
     */
    static String fieldCode(String field, String suffix) {
        return field.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT) + "_" + suffix;
    }

    static <T> T orDefault(T value, T fallback) {
        return value == null ? fallback : value;
    }
}
