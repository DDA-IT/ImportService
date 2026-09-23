package be.dda.catalogimport.service.support;

import be.dda.catalogimport.domain.BookmarkDataType;
import be.dda.catalogimport.domain.BookmarkUsagePlace;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Type-, patroon-, keuzelijst- en lengtecontrole van één bookmarkwaarde tegen haar declaratie
 * (sjabloon-materialisatie-design.md §4 fase D, checks D5/D6).
 * <p>
 * <b>{@code ""} wordt hier nooit type- of patroongetoetst.</b> R-BMK-03 (§1 van het ontwerp) maakt
 * expliciet onderscheid tussen "niet ingevuld" (geen rij) en "uitdrukkelijk leeg" ({@code value_text =
 * ""}). Een uitdrukkelijk lege waarde op een optionele bookmark is geen "0" of "01-01-1970" die aan een
 * type moet voldoen; of een verplichte bookmark met {@code ""} toegelaten is, beslist D3/D4 (elders),
 * niet deze klasse.
 * <p>
 * <b>D5 dwingt principe 8 van AGENT.md af in de bookmarklaag</b>: een niet-parsebare {@code DECIMAL}
 * wordt nooit stil 0 of leeg, ze blokkeert met {@link #CODE_VALUE_INVALID}.
 * <p>
 * Nog geen aanroeper in bouwstap 5a: deze klasse wordt in een latere bouwstap aangeroepen bij het
 * invullen van een bookmarkwaarde en defensief bij materialisatie. Package-private: enkel bedoeld voor
 * Service-klassen in dit pakket, geen publiek contract.
 */
final class BookmarkValueRules {

    static final String CODE_VALUE_INVALID = "CONFIG_BOOKMARK_VALUE_INVALID";
    static final String CODE_VALUE_TOO_LONG = "CONFIG_BOOKMARK_VALUE_TOO_LONG";

    /**
     * De doelkolomlengte per plaats (D6). {@code import_field_mapping.fixed_value} en
     * {@code import_record_filter.compare_value} zijn {@code varchar(500)}, gelijk aan
     * {@code import_definition_bookmark_value.value_text} zelf — de controle is voor die twee plaatsen
     * dus nooit strenger dan wat de kolom al toestaat, maar blijft hier voor eenduidigheid. De
     * {@code identity_*_field}-kolommen zijn {@code varchar(200)}; {@code import_link.library_code} is
     * {@code varchar(20)}; {@code library_search_supplier_code} is {@code varchar(50)};
     * {@code source_organisation.code} (het doel van {@code LINK_SUPPLIER_ORGANISATION}) is ook
     * {@code varchar(50)}.
     */
    private static final Map<BookmarkUsagePlace, Integer> TARGET_COLUMN_LENGTHS = new EnumMap<>(
            BookmarkUsagePlace.class);

    static {
        TARGET_COLUMN_LENGTHS.put(BookmarkUsagePlace.FIELD_MAPPING_FIXED_VALUE, 500);
        TARGET_COLUMN_LENGTHS.put(BookmarkUsagePlace.RECORD_FILTER_COMPARE_VALUE, 500);
        TARGET_COLUMN_LENGTHS.put(BookmarkUsagePlace.REVISION_IDENTITY_FIELD, 200);
        TARGET_COLUMN_LENGTHS.put(BookmarkUsagePlace.LINK_LIBRARY_CODE, 20);
        TARGET_COLUMN_LENGTHS.put(BookmarkUsagePlace.LINK_SEARCH_SUPPLIER, 50);
        TARGET_COLUMN_LENGTHS.put(BookmarkUsagePlace.LINK_SUPPLIER_ORGANISATION, 50);
    }

    /** Scheidingsteken tussen toegelaten waarden in {@code allowed_values} (declaratiemetadata). */
    private static final String ALLOWED_VALUES_SEPARATOR = ",";

    private BookmarkValueRules() {
        // Enkel statische helpers.
    }

    record Problem(String code, String message) {
    }

    /**
     * D5: is de waarde parsebaar voor haar declaratie? {@code ""} wordt altijd aanvaard (zie
     * klasse-javadoc). Controleert daarna, ongeacht het type, het optionele {@code validation_pattern}.
     *
     * @param allowedValues     verplicht en niet leeg wanneer {@code dataType == ENUM}
     *                          (databasecheck {@code ck_import_definition_bookmark_enum}), komma-
     *                          gescheiden, elke token getrimd
     * @param validationPattern optioneel Java-regexpatroon; {@code null} of leeg betekent "geen extra
     *                          patrooncontrole"
     */
    static Optional<Problem> checkType(BookmarkDataType dataType, String allowedValues, String validationPattern,
                                       String valueText) {
        if (valueText.isEmpty()) {
            return Optional.empty();
        }
        Optional<Problem> typeProblem = checkOneType(dataType, allowedValues, valueText);
        if (typeProblem.isPresent()) {
            return typeProblem;
        }
        return checkPattern(validationPattern, valueText);
    }

    private static Optional<Problem> checkOneType(BookmarkDataType dataType, String allowedValues,
                                                   String valueText) {
        return switch (dataType) {
            case INTEGER -> parseCheck(valueText, "INTEGER", Long::parseLong);
            case DECIMAL -> parseCheck(valueText, "DECIMAL", BigDecimal::new);
            case DATE -> parseCheck(valueText, "DATE (ISO-8601)", LocalDate::parse);
            case BOOLEAN -> "true".equalsIgnoreCase(valueText) || "false".equalsIgnoreCase(valueText)
                    ? Optional.empty()
                    : Optional.of(new Problem(CODE_VALUE_INVALID,
                            "Value '" + valueText + "' is not a BOOLEAN (true/false)"));
            case ENUM -> checkEnum(allowedValues, valueText);
            // TEXT en de referentietypes hebben geen ingebouwde syntax om op te parsen; hun controle is
            // het optionele validation_pattern hierboven, en referentieresolutie is nog geen onderdeel
            // van deze bouwstap.
            case TEXT, SUPPLIER_REFERENCE, LIBRARY_REFERENCE, POLICY_PROFILE_REFERENCE -> Optional.empty();
        };
    }

    private static Optional<Problem> checkEnum(String allowedValues, String valueText) {
        if (allowedValues == null || allowedValues.isBlank()) {
            // De databasecheck ck_import_definition_bookmark_enum verbiedt dit al bij het declareren;
            // hier defensief dezelfde fout in plaats van een NPE.
            return Optional.of(new Problem(CODE_VALUE_INVALID,
                    "ENUM bookmark has no allowed_values declared"));
        }
        boolean allowed = Arrays.stream(allowedValues.split(ALLOWED_VALUES_SEPARATOR))
                .map(String::trim)
                .anyMatch(candidate -> candidate.equals(valueText));
        return allowed ? Optional.empty()
                : Optional.of(new Problem(CODE_VALUE_INVALID,
                        "Value '" + valueText + "' is not one of the allowed values '" + allowedValues + "'"));
    }

    private static Optional<Problem> checkPattern(String validationPattern, String valueText) {
        if (validationPattern == null || validationPattern.isBlank()) {
            return Optional.empty();
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(validationPattern);
        } catch (PatternSyntaxException invalidPattern) {
            return Optional.of(new Problem(CODE_VALUE_INVALID,
                    "validation_pattern '" + validationPattern + "' is not a valid regular expression"));
        }
        return pattern.matcher(valueText).matches() ? Optional.empty()
                : Optional.of(new Problem(CODE_VALUE_INVALID,
                        "Value '" + valueText + "' does not match validation_pattern '" + validationPattern + "'"));
    }

    private static Optional<Problem> parseCheck(String valueText, String typeLabel, java.util.function.Function<
            String, Object> parser) {
        try {
            parser.apply(valueText);
            return Optional.empty();
        } catch (RuntimeException notParsable) {
            // NumberFormatException, DateTimeParseException: alle verwachte parsefouten.
            return Optional.of(new Problem(CODE_VALUE_INVALID,
                    "Value '" + valueText + "' is not a valid " + typeLabel));
        }
    }

    /**
     * D6: past de waarde in de doelkolom van {@code place}? Een plaats zonder bekende doelkolomlengte
     * (nog niet ondersteund, zie {@code BookmarkDeclarations} C4) levert hier geen oordeel: die plaats
     * is al elders tegengehouden.
     */
    static Optional<Problem> checkLength(BookmarkUsagePlace place, String valueText) {
        Integer maxLength = TARGET_COLUMN_LENGTHS.get(place);
        if (maxLength == null) {
            return Optional.empty();
        }
        if (valueText.length() > maxLength) {
            return Optional.of(new Problem(CODE_VALUE_TOO_LONG,
                    "Value for place '" + place + "' is " + valueText.length() + " characters, "
                            + "the target column allows at most " + maxLength));
        }
        return Optional.empty();
    }
}
