package be.dda.catalogimport.service.support;

import static org.assertj.core.api.Assertions.assertThat;

import be.dda.catalogimport.domain.BookmarkDataType;
import be.dda.catalogimport.domain.BookmarkUsagePlace;
import be.dda.catalogimport.domain.BookmarkValueScope;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionBookmark;
import be.dda.catalogimport.domain.ImportDefinitionBookmarkUsage;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.service.support.BookmarkDeclarations.Problem;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Bouwstap 5a: {@link BookmarkDeclarations}, checks C1-C4 van sjabloon-materialisatie-design.md §4
 * fase C. Unittest zonder Spring en zonder database, naar het patroon van
 * {@code MappingConfigValidationTest}: de bookmarks en usages worden hier rechtstreeks geconstrueerd
 * (geen gegenereerde id's), en de groepering in {@link BookmarkDeclarations} gebeurt daarom op
 * objectidentiteit, niet op {@code id}.
 */
class BookmarkDeclarationsTest {

    private final ImportDefinitionRevision revision = revision();

    // --- C1: DEFINITION-scope mag nooit een LINK_*-plaats dragen ------------------------------------

    @Test
    void refusesADefinitionScopeBookmarkOnALinkPlace() {
        ImportDefinitionBookmark bookmark = bookmark("DETAILLEVERANCIER", BookmarkValueScope.DEFINITION);
        ImportDefinitionBookmarkUsage usage = new ImportDefinitionBookmarkUsage(bookmark,
                BookmarkUsagePlace.LINK_LIBRARY_CODE, "");

        List<Problem> problems = BookmarkDeclarations.findProblems(List.of(bookmark), List.of(usage),
                Set.of(), Set.of());

        assertThat(problems).extracting(Problem::code)
                .contains(BookmarkDeclarations.CODE_SCOPE_PLACE_CONFLICT);
    }

    @Test
    void acceptsALinkScopeBookmarkOnALinkPlace() {
        ImportDefinitionBookmark bookmark = bookmark("DOELBIBLIOTHEEK", BookmarkValueScope.LINK);
        ImportDefinitionBookmarkUsage usage = new ImportDefinitionBookmarkUsage(bookmark,
                BookmarkUsagePlace.LINK_LIBRARY_CODE, "");

        List<Problem> problems = BookmarkDeclarations.findProblems(List.of(bookmark), List.of(usage),
                Set.of(), Set.of());

        assertThat(problems).extracting(Problem::code)
                .doesNotContain(BookmarkDeclarations.CODE_SCOPE_PLACE_CONFLICT);
    }

    // --- C2: elke bookmark heeft minstens één usage-rij ----------------------------------------------

    @Test
    void refusesABookmarkWithoutAnyUsage() {
        ImportDefinitionBookmark orphan = bookmark("NERGENS_TOEGEPAST", BookmarkValueScope.LINK);

        List<Problem> problems = BookmarkDeclarations.findProblems(List.of(orphan), List.of(), Set.of(),
                Set.of());

        assertThat(problems).extracting(Problem::code, Problem::bookmarkName)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(BookmarkDeclarations.CODE_WITHOUT_PLACE,
                        "NERGENS_TOEGEPAST"));
    }

    @Test
    void acceptsABookmarkWithAtLeastOneUsage() {
        ImportDefinitionBookmark bookmark = bookmark("CULTUUR", BookmarkValueScope.DEFINITION);
        ImportDefinitionBookmarkUsage usage = new ImportDefinitionBookmarkUsage(bookmark,
                BookmarkUsagePlace.RECORD_FILTER_COMPARE_VALUE, "1");

        List<Problem> problems = BookmarkDeclarations.findProblems(List.of(bookmark), List.of(usage),
                Set.of(), Set.of(1));

        assertThat(problems).extracting(Problem::code).doesNotContain(BookmarkDeclarations.CODE_WITHOUT_PLACE);
    }

    // --- C3: de usage-rij moet naar een bestaand doel in déze revisie wijzen ------------------------

    @Test
    void refusesAFieldMappingUsageWhoseTargetFieldCodeDoesNotExist() {
        ImportDefinitionBookmark bookmark = bookmark("DETAILLEVERANCIER", BookmarkValueScope.DEFINITION);
        ImportDefinitionBookmarkUsage usage = new ImportDefinitionBookmarkUsage(bookmark,
                BookmarkUsagePlace.FIELD_MAPPING_FIXED_VALUE, "E_ONBEKEND");

        List<Problem> problems = BookmarkDeclarations.findProblems(List.of(bookmark), List.of(usage),
                Set.of("E_SUPPLIER"), Set.of());

        assertThat(problems).extracting(Problem::code).contains(BookmarkDeclarations.CODE_PLACE_UNRESOLVED);
    }

    @Test
    void acceptsAFieldMappingUsageWhoseTargetFieldCodeExists() {
        ImportDefinitionBookmark bookmark = bookmark("DETAILLEVERANCIER", BookmarkValueScope.DEFINITION);
        ImportDefinitionBookmarkUsage usage = new ImportDefinitionBookmarkUsage(bookmark,
                BookmarkUsagePlace.FIELD_MAPPING_FIXED_VALUE, "E_SUPPLIER");

        List<Problem> problems = BookmarkDeclarations.findProblems(List.of(bookmark), List.of(usage),
                Set.of("E_SUPPLIER"), Set.of());

        assertThat(problems).isEmpty();
    }

    @Test
    void refusesARecordFilterUsageWhoseSequenceNumberDoesNotExist() {
        ImportDefinitionBookmark bookmark = bookmark("CULTUUR", BookmarkValueScope.DEFINITION);
        ImportDefinitionBookmarkUsage usage = new ImportDefinitionBookmarkUsage(bookmark,
                BookmarkUsagePlace.RECORD_FILTER_COMPARE_VALUE, "99");

        List<Problem> problems = BookmarkDeclarations.findProblems(List.of(bookmark), List.of(usage),
                Set.of(), Set.of(1, 2));

        assertThat(problems).extracting(Problem::code).contains(BookmarkDeclarations.CODE_PLACE_UNRESOLVED);
    }

    @Test
    void refusesARecordFilterUsageWithANonNumericTargetHint() {
        ImportDefinitionBookmark bookmark = bookmark("CULTUUR", BookmarkValueScope.DEFINITION);
        ImportDefinitionBookmarkUsage usage = new ImportDefinitionBookmarkUsage(bookmark,
                BookmarkUsagePlace.RECORD_FILTER_COMPARE_VALUE, "niet-een-getal");

        List<Problem> problems = BookmarkDeclarations.findProblems(List.of(bookmark), List.of(usage),
                Set.of(), Set.of(1));

        assertThat(problems).extracting(Problem::code).contains(BookmarkDeclarations.CODE_PLACE_UNRESOLVED);
    }

    @Test
    void refusesARevisionIdentityFieldUsageWithAnUnknownCriticalityKey() {
        ImportDefinitionBookmark bookmark = bookmark("LEV_SLEUTEL", BookmarkValueScope.DEFINITION);
        ImportDefinitionBookmarkUsage usage = new ImportDefinitionBookmarkUsage(bookmark,
                BookmarkUsagePlace.REVISION_IDENTITY_FIELD, "GEEN_BESTAAND_VELD");

        List<Problem> problems = BookmarkDeclarations.findProblems(List.of(bookmark), List.of(usage),
                Set.of(), Set.of());

        assertThat(problems).extracting(Problem::code).contains(BookmarkDeclarations.CODE_PLACE_UNRESOLVED);
    }

    @Test
    void acceptsARevisionIdentityFieldUsageWithAKnownCriticalityKey() {
        ImportDefinitionBookmark bookmark = bookmark("LEV_SLEUTEL", BookmarkValueScope.DEFINITION);
        ImportDefinitionBookmarkUsage usage = new ImportDefinitionBookmarkUsage(bookmark,
                BookmarkUsagePlace.REVISION_IDENTITY_FIELD, "SUPPLIER");

        List<Problem> problems = BookmarkDeclarations.findProblems(List.of(bookmark), List.of(usage),
                Set.of(), Set.of());

        assertThat(problems).isEmpty();
    }

    @Test
    void aLinkPlaceUsageNeverNeedsATargetInTheRevision() {
        ImportDefinitionBookmark bookmark = bookmark("DOELBIBLIOTHEEK", BookmarkValueScope.LINK);
        ImportDefinitionBookmarkUsage usage = new ImportDefinitionBookmarkUsage(bookmark,
                BookmarkUsagePlace.LINK_LIBRARY_CODE, "");

        List<Problem> problems = BookmarkDeclarations.findProblems(List.of(bookmark), List.of(usage),
                Set.of(), Set.of());

        assertThat(problems).isEmpty();
    }

    // --- C4: de plaats moet in deze bouwstap ondersteund zijn -----------------------------------------

    @Test
    void refusesAnUnsupportedPlace() {
        ImportDefinitionBookmark bookmark = bookmark("PRIJSBELEID", BookmarkValueScope.DEFINITION);
        ImportDefinitionBookmarkUsage usage = new ImportDefinitionBookmarkUsage(bookmark,
                BookmarkUsagePlace.REVISION_PRICE_POLICY, "");

        List<Problem> problems = BookmarkDeclarations.findProblems(List.of(bookmark), List.of(usage),
                Set.of(), Set.of());

        assertThat(problems).extracting(Problem::code)
                .containsExactly(BookmarkDeclarations.CODE_PLACE_NOT_SUPPORTED);
    }

    @Test
    void acceptsEverySupportedPlaceExceptRevisionPricePolicy() {
        for (BookmarkUsagePlace place : BookmarkUsagePlace.values()) {
            if (place == BookmarkUsagePlace.REVISION_PRICE_POLICY) {
                continue;
            }
            ImportDefinitionBookmark bookmark = bookmark("BM_" + place.name(), BookmarkValueScope.LINK);
            ImportDefinitionBookmarkUsage usage = new ImportDefinitionBookmarkUsage(bookmark, place, "");

            List<Problem> problems = BookmarkDeclarations.findProblems(List.of(bookmark), List.of(usage),
                    Set.of(), Set.of());

            assertThat(problems).as("place " + place).extracting(Problem::code)
                    .doesNotContain(BookmarkDeclarations.CODE_PLACE_NOT_SUPPORTED);
        }
    }

    // --- Meerdere bookmarks in dezelfde declaratie: geen kruisbesmetting tussen groepen ---------------

    @Test
    void keepsUsagesOfDifferentBookmarksApart() {
        ImportDefinitionBookmark first = bookmark("EEN", BookmarkValueScope.LINK);
        ImportDefinitionBookmark second = bookmark("TWEE", BookmarkValueScope.LINK);
        ImportDefinitionBookmarkUsage firstUsage = new ImportDefinitionBookmarkUsage(first,
                BookmarkUsagePlace.LINK_LIBRARY_CODE, "");
        // "second" heeft geen usage: enkel "second" mag CODE_WITHOUT_PLACE krijgen.

        List<Problem> problems = BookmarkDeclarations.findProblems(List.of(first, second), List.of(firstUsage),
                Set.of(), Set.of());

        assertThat(problems).extracting(Problem::code, Problem::bookmarkName)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(BookmarkDeclarations.CODE_WITHOUT_PLACE,
                        "TWEE"));
    }

    // --- Hulpmiddelen --------------------------------------------------------------------------------

    private ImportDefinitionBookmark bookmark(String name, BookmarkValueScope scope) {
        return new ImportDefinitionBookmark(revision, name, name, BookmarkDataType.TEXT, scope, "DATA_OWNER", 1);
    }

    private static ImportDefinitionRevision revision() {
        SourceOrganisation organisation = new SourceOrganisation("VROOAM", "VROOAM", SourceOrganisationType
                .PURCHASING_ASSOCIATION);
        ImportDefinition definition = new ImportDefinition(organisation, "TEMPLATE-1", "Sjabloon 1",
                "setup-api");
        return new ImportDefinitionRevision(definition, 1, IdentityProfileKind.THREE_PART, "setup-api");
    }
}
