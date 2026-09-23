package be.dda.catalogimport.service.support;

import static org.assertj.core.api.Assertions.assertThat;

import be.dda.catalogimport.domain.BookmarkDataType;
import be.dda.catalogimport.domain.BookmarkUsagePlace;
import be.dda.catalogimport.service.support.BookmarkValueRules.Problem;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Bouwstap 5a: {@link BookmarkValueRules}, checks D5/D6 van sjabloon-materialisatie-design.md §4 fase
 * D. Unittest zonder Spring en zonder database.
 */
class BookmarkValueRulesTest {

    // --- "" is nooit type-/patroongetoetst (R-BMK-03) -------------------------------------------------

    @Test
    void neverTypeChecksAnExplicitlyEmptyValue() {
        assertThat(BookmarkValueRules.checkType(BookmarkDataType.INTEGER, null, null, "")).isEmpty();
        assertThat(BookmarkValueRules.checkType(BookmarkDataType.DATE, null, "^[0-9]+$", "")).isEmpty();
    }

    // --- INTEGER ---------------------------------------------------------------------------------------

    @Test
    void integerMustBeParsable() {
        assertThat(BookmarkValueRules.checkType(BookmarkDataType.INTEGER, null, null, "42")).isEmpty();

        assertThat(BookmarkValueRules.checkType(BookmarkDataType.INTEGER, null, null, "niet-een-getal"))
                .contains(new Problem(BookmarkValueRules.CODE_VALUE_INVALID,
                        "Value 'niet-een-getal' is not a valid INTEGER"));
    }

    // --- DECIMAL ---------------------------------------------------------------------------------------

    @Test
    void decimalMustBeParsable() {
        assertThat(BookmarkValueRules.checkType(BookmarkDataType.DECIMAL, null, null, "12.50")).isEmpty();

        Optional<Problem> problem = BookmarkValueRules.checkType(BookmarkDataType.DECIMAL, null, null,
                "12,50");
        // Een komma is hier geen decimaalteken (bookmarks kennen geen per-bron notatie zoals
        // ImportValueRules.decimal): een niet-parsebare DECIMAL wordt nooit stil 0 of leeg (AGENT.md
        // principe 8), ze blokkeert.
        assertThat(problem).isPresent();
        assertThat(problem.get().code()).isEqualTo(BookmarkValueRules.CODE_VALUE_INVALID);
    }

    // --- DATE (ISO-8601) --------------------------------------------------------------------------------

    @Test
    void dateMustBeIso8601() {
        assertThat(BookmarkValueRules.checkType(BookmarkDataType.DATE, null, null, "2026-09-23")).isEmpty();

        assertThat(BookmarkValueRules.checkType(BookmarkDataType.DATE, null, null, "23/09/2026"))
                .isPresent();
    }

    // --- BOOLEAN ---------------------------------------------------------------------------------------

    @Test
    void booleanMustBeTrueOrFalse() {
        assertThat(BookmarkValueRules.checkType(BookmarkDataType.BOOLEAN, null, null, "true")).isEmpty();
        assertThat(BookmarkValueRules.checkType(BookmarkDataType.BOOLEAN, null, null, "FALSE")).isEmpty();

        assertThat(BookmarkValueRules.checkType(BookmarkDataType.BOOLEAN, null, null, "yes")).isPresent();
    }

    // --- ENUM ------------------------------------------------------------------------------------------

    @Test
    void enumMustBeWithinAllowedValues() {
        assertThat(BookmarkValueRules.checkType(BookmarkDataType.ENUM, "NL, FR, EN", null, "FR")).isEmpty();

        assertThat(BookmarkValueRules.checkType(BookmarkDataType.ENUM, "NL, FR, EN", null, "DE")).isPresent();
    }

    @Test
    void enumWithoutAllowedValuesIsRejectedDefensively() {
        // De databasecheck ck_import_definition_bookmark_enum verbiedt dit al bij declareren; hier
        // defensief dezelfde fout in plaats van een NullPointerException.
        assertThat(BookmarkValueRules.checkType(BookmarkDataType.ENUM, null, null, "FR")).isPresent();
        assertThat(BookmarkValueRules.checkType(BookmarkDataType.ENUM, "  ", null, "FR")).isPresent();
    }

    // --- validation_pattern, ongeacht het type ----------------------------------------------------------

    @Test
    void validationPatternAppliesOnTopOfTheTypeCheck() {
        assertThat(BookmarkValueRules.checkType(BookmarkDataType.TEXT, null, "^[A-Z0-9]{4}$", "ABP4"))
                .isEmpty();

        assertThat(BookmarkValueRules.checkType(BookmarkDataType.TEXT, null, "^[A-Z0-9]{4}$", "abp4"))
                .contains(new Problem(BookmarkValueRules.CODE_VALUE_INVALID,
                        "Value 'abp4' does not match validation_pattern '^[A-Z0-9]{4}$'"));
    }

    @Test
    void aBlankValidationPatternMeansNoExtraCheck() {
        assertThat(BookmarkValueRules.checkType(BookmarkDataType.TEXT, null, "", "anything")).isEmpty();
        assertThat(BookmarkValueRules.checkType(BookmarkDataType.TEXT, null, null, "anything")).isEmpty();
    }

    // --- TEXT en referentietypes: geen ingebouwde syntax, enkel het patroon -----------------------------

    @Test
    void textAndReferenceTypesHaveNoBuiltInSyntaxCheck() {
        assertThat(BookmarkValueRules.checkType(BookmarkDataType.TEXT, null, null, "eender wat")).isEmpty();
        assertThat(BookmarkValueRules.checkType(BookmarkDataType.SUPPLIER_REFERENCE, null, null, "VROOAM"))
                .isEmpty();
    }

    // --- D6: de waarde moet in de doelkolom passen -------------------------------------------------------

    @Test
    void refusesAValueThatDoesNotFitTheLibraryCodeColumn() {
        String tooLong = "A".repeat(21); // import_link.library_code is varchar(20)

        assertThat(BookmarkValueRules.checkLength(BookmarkUsagePlace.LINK_LIBRARY_CODE, tooLong))
                .contains(new Problem(BookmarkValueRules.CODE_VALUE_TOO_LONG,
                        "Value for place 'LINK_LIBRARY_CODE' is 21 characters, the target column allows "
                                + "at most 20"));
    }

    @Test
    void acceptsAValueThatFitsExactlyInTheLibraryCodeColumn() {
        String exactlyTwenty = "A".repeat(20);

        assertThat(BookmarkValueRules.checkLength(BookmarkUsagePlace.LINK_LIBRARY_CODE, exactlyTwenty))
                .isEmpty();
    }

    @Test
    void refusesAValueThatDoesNotFitTheSearchSupplierColumn() {
        String tooLong = "A".repeat(51); // import_link.library_search_supplier_code is varchar(50)

        assertThat(BookmarkValueRules.checkLength(BookmarkUsagePlace.LINK_SEARCH_SUPPLIER, tooLong))
                .isPresent();
    }

    @Test
    void refusesAValueThatDoesNotFitTheRevisionIdentityFieldColumn() {
        String tooLong = "A".repeat(201); // identity_*_field is varchar(200)

        assertThat(BookmarkValueRules.checkLength(BookmarkUsagePlace.REVISION_IDENTITY_FIELD, tooLong))
                .isPresent();
    }

    @Test
    void aPlaceWithoutAKnownTargetColumnLengthIsNotJudgedHere() {
        // REVISION_PRICE_POLICY wordt al elders (BookmarkDeclarations C4) tegengehouden; deze klasse
        // velt daarover geen apart oordeel.
        assertThat(BookmarkValueRules.checkLength(BookmarkUsagePlace.REVISION_PRICE_POLICY,
                "A".repeat(10_000))).isEmpty();
    }
}
