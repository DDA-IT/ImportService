package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.domain.BookmarkDataType;
import be.dda.catalogimport.domain.BookmarkUsagePlace;
import be.dda.catalogimport.domain.BookmarkValueScope;
import be.dda.catalogimport.domain.DefinitionUsageType;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.service.TemplateMaterialisationService.BookmarkValue;
import be.dda.catalogimport.service.TemplateMaterialisationService.MaterialisationMode;
import be.dda.catalogimport.service.TemplateMaterialisationService.MaterialiseRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * Bouwstap 5c: elke rij uit de validatietabel van §4 (fasen A t/m E) met haar exacte foutcode
 * (sjabloon-materialisatie-design.md §10, {@code TemplateMaterialisationValidationTest}).
 * <p>
 * De statuscode volgt uit het uitzonderingstype en het bestaande {@code ApiExceptionHandler}:
 * {@link NotFoundException} = 404, {@link ConflictException} = 409, {@link BadRequestException} = 400
 * met een stabiele {@code code}, en een gewone {@link IllegalArgumentException} = 400 zonder code.
 * <p>
 * Kern van deze groep: <b>niets wordt stil genegeerd of stil gecorrigeerd</b>. Een onbekende naam, een
 * niet-parsebare waarde, een te lange waarde of een tweede bron voor dezelfde kolom blokkeert; er wordt
 * nooit een lege of afgekapte waarde in de plaats gezet.
 */
// Dezelfde annotaties als TemplateMaterialisationTest, bewust letterlijk gelijk: zo delen de vier
// 5c-testklassen één applicatiecontext en dus één (kleine) verbindingspool. Zie de klasse-javadoc
// van TemplateMaterialisationTest.
@SpringBootTest(properties = {"catalogimport.setup-api.enabled=true",
        "spring.datasource.hikari.maximum-pool-size=4"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class TemplateMaterialisationValidationTest {

    private static final String USER = MaterialisationFixtures.USER;

    @Autowired
    private ApplicationContext context;
    @Autowired
    private TemplateMaterialisationService materialisation;
    @Autowired
    private ImportDefinitionRepository definitions;

    private MaterialisationFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = MaterialisationFixtures.of(context);
    }

    // --- Fase A ---------------------------------------------------------------------------------------

    @Test
    void refusesAnUnknownDefinitionADefinitionThatIsNoTemplateAndAForeignRevision() {
        assertThatThrownBy(() -> materialisation.materialise(-1L, request(null, List.of(), null)))
                .isInstanceOf(NotFoundException.class)
                .extracting(error -> ((NotFoundException) error).getCode())
                .isEqualTo("TEMPLATE_NOT_FOUND");

        MaterialisationFixtures.Template own = fixtures.template("A2", DefinitionUsageType.OWN_DEFINITION,
                RevisionStatus.ACTIVE);
        assertThatThrownBy(() -> materialisation.materialise(own.definition().getId(),
                request(own, List.of(), null)))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo("DEFINITION_NOT_A_TEMPLATE");

        MaterialisationFixtures.Template template = fixtures.template("A3");
        MaterialisationFixtures.Template other = fixtures.template("A3B");
        assertThatThrownBy(() -> materialisation.materialise(template.definition().getId(),
                request(template, List.of(), other.revision().getId())))
                .isInstanceOf(NotFoundException.class)
                .extracting(error -> ((NotFoundException) error).getCode())
                .isEqualTo("TEMPLATE_REVISION_NOT_FOUND");
    }

    /**
     * A4 met beslissing Q3: {@code SUPERSEDED} mag, {@code DRAFT} niet. Zonder opgegeven revisie en
     * zonder {@code ACTIVE} revisie is er niets om uit te materialiseren.
     */
    @Test
    void refusesADraftTemplateRevisionAndATemplateWithoutAnActiveRevision() {
        MaterialisationFixtures.Template draft = fixtures.template("A4",
                DefinitionUsageType.REUSABLE_TEMPLATE, RevisionStatus.DRAFT);

        assertThatThrownBy(() -> materialisation.materialise(draft.definition().getId(),
                request(draft, List.of(), draft.revision().getId())))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo("TEMPLATE_REVISION_NOT_MATERIALISABLE");

        assertThatThrownBy(() -> materialisation.materialise(draft.definition().getId(),
                request(draft, List.of(), null)))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo("NO_ACTIVE_TEMPLATE_REVISION");
    }

    // --- Fase B ---------------------------------------------------------------------------------------

    @Test
    void refusesAMissingModeAndTheReuseModeThatThisBuildStepDoesNotHaveYet() {
        MaterialisationFixtures.Template template = fixtures.template("B1");

        assertThatThrownBy(() -> materialisation.materialise(template.definition().getId(),
                new MaterialiseRequest(null, null, null, template.definitionCode(), "x", null,
                        template.linkCode(), "y", template.supplier().getCode(), "PSARF001", null,
                        List.of(), USER)))
                .isInstanceOf(BadRequestException.class)
                .extracting(error -> ((BadRequestException) error).getCode())
                .isEqualTo("MATERIALISATION_MODE_REQUIRED");

        assertThatThrownBy(() -> materialisation.materialise(template.definition().getId(),
                new MaterialiseRequest(null, MaterialisationMode.REUSE_DEFINITION, 1L,
                        template.definitionCode(), "x", null, template.linkCode(), "y",
                        template.supplier().getCode(), "PSARF001", null, List.of(), USER)))
                .isInstanceOf(BadRequestException.class)
                .extracting(error -> ((BadRequestException) error).getCode())
                .isEqualTo("REUSE_DEFINITION_NOT_ALLOWED");

        assertThatThrownBy(() -> materialisation.materialise(template.definition().getId(),
                new MaterialiseRequest(null, MaterialisationMode.NEW_DEFINITION, 99L,
                        template.definitionCode(), "x", null, template.linkCode(), "y",
                        template.supplier().getCode(), "PSARF001", null, List.of(), USER)))
                .isInstanceOf(BadRequestException.class)
                .extracting(error -> ((BadRequestException) error).getCode())
                .isEqualTo("REUSE_DEFINITION_NOT_ALLOWED");
    }

    /** B3: {@code materialisedBy} volgt de bestaande {@code ActorNames}-regel — nooit {@code system}. */
    @Test
    void refusesAMissingOrSystemActorName() {
        MaterialisationFixtures.Template template = fixtures.template("B3");

        assertThatThrownBy(() -> materialisation.materialise(template.definition().getId(),
                new MaterialiseRequest(null, MaterialisationMode.NEW_DEFINITION, null,
                        template.definitionCode(), "x", null, template.linkCode(), "y",
                        template.supplier().getCode(), "PSARF001", null, List.of(), "system")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("materialisedBy");
    }

    // --- Fase C ---------------------------------------------------------------------------------------

    @Test
    void refusesABookmarkWithoutAPlaceAndADefinitionScopeBookmarkOnALinkPlace() {
        MaterialisationFixtures.Template noPlace = fixtures.template("C2");
        fixtures.declare(noPlace.revision(), "NERGENS", BookmarkValueScope.DEFINITION, true,
                BookmarkDataType.TEXT, 1);
        assertThatThrownBy(() -> materialisation.materialise(noPlace.definition().getId(),
                request(noPlace, List.of(), null)))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo("CONFIG_BOOKMARK_WITHOUT_PLACE");

        MaterialisationFixtures.Template conflict = fixtures.template("C1");
        fixtures.declareOn(conflict.revision(), "DOELBIBLIOTHEEK", BookmarkValueScope.DEFINITION, true,
                BookmarkDataType.TEXT, 1, BookmarkUsagePlace.LINK_LIBRARY_CODE, "");
        assertThatThrownBy(() -> materialisation.materialise(conflict.definition().getId(),
                request(conflict, List.of(new BookmarkValue("DOELBIBLIOTHEEK", "PSARF001")), null)))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo("CONFIG_BOOKMARK_SCOPE_PLACE_CONFLICT");
    }

    @Test
    void refusesAPlaceThatDoesNotResolveInThisRevision() {
        MaterialisationFixtures.Template template = fixtures.template("C3");
        fixtures.declareOn(template.revision(), "ONBEKEND_DOEL", BookmarkValueScope.DEFINITION, true,
                BookmarkDataType.TEXT, 1, BookmarkUsagePlace.FIELD_MAPPING_FIXED_VALUE, "BRAND");

        assertThatThrownBy(() -> materialisation.materialise(template.definition().getId(),
                request(template, List.of(new BookmarkValue("ONBEKEND_DOEL", "x")), null)))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo("CONFIG_BOOKMARK_PLACE_UNRESOLVED");
    }

    /**
     * C4. {@code REVISION_PRICE_POLICY} blijft geweigerd (§9 punt 4, A37);
     * {@code REVISION_IDENTITY_FIELD} en {@code LINK_SEARCH_SUPPLIER} zijn geldige declaraties maar
     * worden pas in bouwstap 5e gematerialiseerd — tot dan uitdrukkelijk geweigerd in plaats van stil
     * genegeerd.
     */
    @Test
    void refusesEveryPlaceThatThisBuildStepCannotMaterialise() {
        assertPlaceNotSupported("C4A", BookmarkUsagePlace.REVISION_PRICE_POLICY, "");
        assertPlaceNotSupported("C4B", BookmarkUsagePlace.REVISION_IDENTITY_FIELD, "SUPPLIER");
        assertPlaceNotSupported("C4C", BookmarkUsagePlace.LINK_SEARCH_SUPPLIER, "");
    }

    private void assertPlaceNotSupported(String prefix, BookmarkUsagePlace place, String targetHint) {
        MaterialisationFixtures.Template template = fixtures.template(prefix);
        fixtures.declareOn(template.revision(), "TE_VROEG", BookmarkValueScope.LINK, true,
                BookmarkDataType.TEXT, 1, place, targetHint);

        assertThatThrownBy(() -> materialisation.materialise(template.definition().getId(),
                request(template, List.of(new BookmarkValue("TE_VROEG", "x")), null)))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo("CONFIG_BOOKMARK_PLACE_NOT_SUPPORTED");
    }

    // --- Fase D ---------------------------------------------------------------------------------------

    @Test
    void refusesAnUnknownBookmarkNameAndADuplicateEntry() {
        MaterialisationFixtures.Template template = fixtures.template("D1");
        fixtures.declareOn(template.revision(), "CULTUUR", BookmarkValueScope.DEFINITION, true,
                BookmarkDataType.TEXT, 1, BookmarkUsagePlace.RECORD_FILTER_COMPARE_VALUE, "1");

        assertThatThrownBy(() -> materialisation.materialise(template.definition().getId(),
                request(template, List.of(new BookmarkValue("CULTUUR", "NL"),
                        new BookmarkValue("BESTAAT_NIET", "x")), null)))
                .isInstanceOf(BadRequestException.class)
                .extracting(error -> ((BadRequestException) error).getCode())
                .isEqualTo("BOOKMARK_UNKNOWN");

        // Twee waarden voor dezelfde naam: welke zou er toegepast worden? Geen van beide.
        assertThatThrownBy(() -> materialisation.materialise(template.definition().getId(),
                request(template, List.of(new BookmarkValue("CULTUUR", "NL"),
                        new BookmarkValue("CULTUUR", "FR")), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than once");
    }

    /** D3/D4: geen rij is "niet ingevuld", {@code ""} is "uitdrukkelijk leeg"; geen van beide vult in. */
    @Test
    void refusesAMissingRequiredBookmarkAndAnEmptyStringOnARequiredBookmark() {
        MaterialisationFixtures.Template template = fixtures.template("D3");
        fixtures.declareOn(template.revision(), "CULTUUR", BookmarkValueScope.DEFINITION, true,
                BookmarkDataType.TEXT, 1, BookmarkUsagePlace.RECORD_FILTER_COMPARE_VALUE, "1");

        assertThatThrownBy(() -> materialisation.materialise(template.definition().getId(),
                request(template, List.of(), null)))
                .isInstanceOf(BadRequestException.class)
                .extracting(error -> ((BadRequestException) error).getCode())
                .isEqualTo("CONFIG_REQUIRED_BOOKMARK_MISSING");

        assertThatThrownBy(() -> materialisation.materialise(template.definition().getId(),
                request(template, List.of(new BookmarkValue("CULTUUR", "")), null)))
                .isInstanceOf(BadRequestException.class)
                .extracting(error -> ((BadRequestException) error).getCode())
                .isEqualTo("CONFIG_REQUIRED_BOOKMARK_MISSING");
    }

    /** D5: een ENUM buiten de lijst en een waarde die het patroon niet volgt blokkeren allebei. */
    @Test
    void refusesAnEnumOutsideItsListAndAValueThatBreaksThePattern() {
        MaterialisationFixtures.Template enumTemplate = fixtures.template("D5A");
        fixtures.declareOn(enumTemplate.revision(), "CULTUUR", BookmarkValueScope.DEFINITION, true,
                BookmarkDataType.ENUM, 1, BookmarkUsagePlace.RECORD_FILTER_COMPARE_VALUE, "1",
                "NL,FR,EN", null);

        assertThatThrownBy(() -> materialisation.materialise(enumTemplate.definition().getId(),
                request(enumTemplate, List.of(new BookmarkValue("CULTUUR", "DE")), null)))
                .isInstanceOf(BadRequestException.class)
                .extracting(error -> ((BadRequestException) error).getCode())
                .isEqualTo("CONFIG_BOOKMARK_VALUE_INVALID");

        MaterialisationFixtures.Template patternTemplate = fixtures.template("D5B");
        fixtures.declareOn(patternTemplate.revision(), "DETAILLEVERANCIER", BookmarkValueScope.DEFINITION,
                true, BookmarkDataType.TEXT, 1, BookmarkUsagePlace.FIELD_MAPPING_FIXED_VALUE,
                MaterialisationFixtures.MAPPED_FIELD, null, "^[0-9]{4}$");

        assertThatThrownBy(() -> materialisation.materialise(patternTemplate.definition().getId(),
                request(patternTemplate, List.of(new BookmarkValue("DETAILLEVERANCIER", "ACME")), null)))
                .isInstanceOf(BadRequestException.class)
                .extracting(error -> ((BadRequestException) error).getCode())
                .isEqualTo("CONFIG_BOOKMARK_VALUE_INVALID");
    }

    /**
     * D6: {@code value_text} is {@code varchar(500)}, maar {@code import_link.library_code} is
     * {@code varchar(20)}. Zonder deze controle zou de bookmarkrij slagen en de koppeling pas op een
     * databasefout stuklopen.
     */
    @Test
    void refusesAValueThatDoesNotFitTheTargetColumn() {
        MaterialisationFixtures.Template template = fixtures.template("D6");
        fixtures.declareOn(template.revision(), "DOELBIBLIOTHEEK", BookmarkValueScope.LINK, true,
                BookmarkDataType.TEXT, 1, BookmarkUsagePlace.LINK_LIBRARY_CODE, "");

        assertThatThrownBy(() -> materialisation.materialise(template.definition().getId(),
                request(template, List.of(new BookmarkValue("DOELBIBLIOTHEEK", "P".repeat(21))), null)))
                .isInstanceOf(BadRequestException.class)
                .extracting(error -> ((BadRequestException) error).getCode())
                .isEqualTo("CONFIG_BOOKMARK_VALUE_TOO_LONG");
    }

    /** D7: een koppelingskolom heeft precies één bron — de bookmark óf het requestveld, nooit beide. */
    @Test
    void refusesALinkFieldThatIsFilledByBothABookmarkAndAnExplicitRequestField() {
        MaterialisationFixtures.Template template = fixtures.template("D7");
        fixtures.declareOn(template.revision(), "DOELBIBLIOTHEEK", BookmarkValueScope.LINK, true,
                BookmarkDataType.TEXT, 1, BookmarkUsagePlace.LINK_LIBRARY_CODE, "");

        assertThatThrownBy(() -> materialisation.materialise(template.definition().getId(),
                new MaterialiseRequest(null, MaterialisationMode.NEW_DEFINITION, null,
                        template.definitionCode(), "x", null, template.linkCode(), "y",
                        template.supplier().getCode(), "PSARF001", null,
                        List.of(new BookmarkValue("DOELBIBLIOTHEEK", "PSARF002")), USER)))
                .isInstanceOf(BadRequestException.class)
                .extracting(error -> ((BadRequestException) error).getCode())
                .isEqualTo("LINK_FIELD_BOTH_BOOKMARK_AND_EXPLICIT");
    }

    // --- Fase E ---------------------------------------------------------------------------------------

    @Test
    void refusesAnUnknownSupplierOrganisation() {
        MaterialisationFixtures.Template template = fixtures.template("E1");

        assertThatThrownBy(() -> materialisation.materialise(template.definition().getId(),
                new MaterialiseRequest(null, MaterialisationMode.NEW_DEFINITION, null,
                        template.definitionCode(), "x", null, template.linkCode(), "y",
                        "BESTAAT-NIET-" + template.unique(), "PSARF001", null, List.of(), USER)))
                .isInstanceOf(NotFoundException.class)
                .extracting(error -> ((NotFoundException) error).getCode())
                .isEqualTo("SOURCE_ORGANISATION_NOT_FOUND");
    }

    /** Elke afgewezen aanvraag laat de definitie ongemaakt: er is niets half aangelegd. */
    @Test
    void createsNothingWhenAValidationFails() {
        MaterialisationFixtures.Template template = fixtures.template("NONE");
        fixtures.declareOn(template.revision(), "CULTUUR", BookmarkValueScope.DEFINITION, true,
                BookmarkDataType.TEXT, 1, BookmarkUsagePlace.RECORD_FILTER_COMPARE_VALUE, "1");

        assertThatThrownBy(() -> materialisation.materialise(template.definition().getId(),
                request(template, List.of(), null)))
                .isInstanceOf(BadRequestException.class);

        assertThat(definitions.findBySourceOrganisationIdAndCode(template.source().getId(),
                template.definitionCode())).isEmpty();
    }

    // --- Helpers ---------------------------------------------------------------------------------------

    private static MaterialiseRequest request(MaterialisationFixtures.Template template,
                                              List<BookmarkValue> values, Long templateRevisionId) {
        return new MaterialiseRequest(templateRevisionId, MaterialisationMode.NEW_DEFINITION, null,
                template == null ? "X-DEF" : template.definitionCode(), "Afgeleide definitie", null,
                template == null ? "X-LINK" : template.linkCode(), "Afgeleide koppeling",
                template == null ? "X-SUP" : template.supplier().getCode(), "PSARF001", null, values,
                USER);
    }
}
