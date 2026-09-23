package be.dda.catalogimport.service;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.domain.BookmarkDataType;
import be.dda.catalogimport.domain.BookmarkUsagePlace;
import be.dda.catalogimport.domain.BookmarkValueScope;
import be.dda.catalogimport.domain.DefinitionUsageType;
import be.dda.catalogimport.domain.RevisionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Bouwstap 5e, de statuscodematrix van §5/§10 over HTTP ({@code TemplateMaterialisationHttpTest}):
 * {@code POST /templates/{id}/materialisations} vertaalt elke uitzonderingsfamilie naar de juiste
 * statuscode met een stabiel {@code code}-veld, via het bestaande {@code ApiExceptionHandler}
 * ({@link NotFoundException} = 404, {@link ConflictException} = 409, {@link BadRequestException} = 400
 * met code). De servicelaag zelf ({@code materialise(...)}) is al uitputtend getest in
 * {@code TemplateMaterialisationTest}/{@code TemplateMaterialisationValidationTest}/
 * {@code TemplateReuseTest}; hier gaat het uitsluitend om de HTTP-vertaling, één representatief geval per
 * familie in plaats van elke rij van §4 nogmaals.
 *
 * <h2>Waarom deze klasse dezelfde annotaties draagt als de andere materialisatietests</h2>
 * Letterlijk gelijk aan {@code TemplateMaterialisationTest}: Spring houdt elke afwijkende
 * testconfiguratie als een <b>aparte</b> applicatiecontext in de cache, elk met een eigen
 * verbindingspool, en de lokale PostgreSQL heeft weinig vrije verbindingen. Zo delen alle
 * materialisatietests één context en één (uitdrukkelijk kleine) pool.
 */
@SpringBootTest(properties = {"catalogimport.setup-api.enabled=true",
        "spring.datasource.hikari.maximum-pool-size=4"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class TemplateMaterialisationHttpTest {

    private static final String USER = MaterialisationFixtures.USER;

    @Autowired
    private ApplicationContext context;
    @Autowired
    private MockMvc mockMvc;

    private MaterialisationFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = MaterialisationFixtures.of(context);
    }

    // --- 404 ---------------------------------------------------------------------------------------

    @Test
    void returns404WithTemplateNotFound() throws Exception {
        mockMvc.perform(post("/api/catalog-import/templates/{id}/materialisations", -1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bareRequest("X-DEF", "X-LINK", "X-SUP")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TEMPLATE_NOT_FOUND"));
    }

    @Test
    void returns404WithSourceOrganisationNotFound() throws Exception {
        MaterialisationFixtures.Template template = fixtures.template("H404B");

        mockMvc.perform(post("/api/catalog-import/templates/{id}/materialisations",
                        template.definition().getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bareRequest(template.definitionCode(), template.linkCode(),
                                "BESTAAT-NIET-" + template.unique())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SOURCE_ORGANISATION_NOT_FOUND"));
    }

    // --- 409 ---------------------------------------------------------------------------------------

    @Test
    void returns409WithDefinitionNotATemplate() throws Exception {
        MaterialisationFixtures.Template own = fixtures.template("H409A", DefinitionUsageType.OWN_DEFINITION,
                RevisionStatus.ACTIVE);

        mockMvc.perform(post("/api/catalog-import/templates/{id}/materialisations",
                        own.definition().getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bareRequest(own.definitionCode(), own.linkCode(), own.supplier().getCode())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEFINITION_NOT_A_TEMPLATE"));
    }

    @Test
    void returns409WithConfigBookmarkPlaceNotSupported() throws Exception {
        MaterialisationFixtures.Template template = fixtures.template("H409B");
        fixtures.declareOn(template.revision(), "TE_VROEG", BookmarkValueScope.LINK, true,
                BookmarkDataType.TEXT, 1, BookmarkUsagePlace.REVISION_PRICE_POLICY, "");

        String body = """
                {"mode":"NEW_DEFINITION","definitionCode":"%s","definitionName":"x",
                 "linkCode":"%s","linkName":"y","supplierOrganisationCode":"%s",
                 "bookmarkValues":[{"name":"TE_VROEG","value":"x"}],"materialisedBy":"%s"}"""
                .formatted(template.definitionCode(), template.linkCode(), template.supplier().getCode(), USER);

        mockMvc.perform(post("/api/catalog-import/templates/{id}/materialisations",
                        template.definition().getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFIG_BOOKMARK_PLACE_NOT_SUPPORTED"));
    }

    @Test
    void returns409WithDefinitionCodeInUse() throws Exception {
        MaterialisationFixtures.Template template = fixtures.template("H409C");
        declareCanonicalBookmarks(template.revision());
        mockMvc.perform(post("/api/catalog-import/templates/{id}/materialisations",
                        template.definition().getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(canonicalBody(template, template.linkCode(), "PSARF900")))
                .andExpect(status().isCreated());

        // Zelfde definitiecode, andere koppeling: botst op uk_import_definition_code.
        mockMvc.perform(post("/api/catalog-import/templates/{id}/materialisations",
                        template.definition().getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(canonicalBody(template, template.linkCode("B"), "PSARF901")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEFINITION_CODE_IN_USE"));
    }

    // --- 400 ---------------------------------------------------------------------------------------

    @Test
    void returns400WithMaterialisationModeRequired() throws Exception {
        MaterialisationFixtures.Template template = fixtures.template("H400A");

        String body = """
                {"definitionCode":"%s","definitionName":"x","linkCode":"%s","linkName":"y",
                 "supplierOrganisationCode":"%s","bookmarkValues":[],"materialisedBy":"%s"}"""
                .formatted(template.definitionCode(), template.linkCode(), template.supplier().getCode(), USER);

        mockMvc.perform(post("/api/catalog-import/templates/{id}/materialisations",
                        template.definition().getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MATERIALISATION_MODE_REQUIRED"));
    }

    @Test
    void returns400WithConfigRequiredBookmarkMissing() throws Exception {
        MaterialisationFixtures.Template template = fixtures.template("H400B");
        fixtures.declareOn(template.revision(), "CULTUUR", BookmarkValueScope.DEFINITION, true,
                BookmarkDataType.TEXT, 1, BookmarkUsagePlace.RECORD_FILTER_COMPARE_VALUE, "1");

        mockMvc.perform(post("/api/catalog-import/templates/{id}/materialisations",
                        template.definition().getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bareRequest(template.definitionCode(), template.linkCode(),
                                template.supplier().getCode())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONFIG_REQUIRED_BOOKMARK_MISSING"));
    }

    @Test
    void returns400WithLinkFieldBothBookmarkAndExplicit() throws Exception {
        MaterialisationFixtures.Template template = fixtures.template("H400C");
        fixtures.declareOn(template.revision(), "DOELBIBLIOTHEEK", BookmarkValueScope.LINK, true,
                BookmarkDataType.TEXT, 1, BookmarkUsagePlace.LINK_LIBRARY_CODE, "");

        String body = """
                {"mode":"NEW_DEFINITION","definitionCode":"%s","definitionName":"x",
                 "linkCode":"%s","linkName":"y","supplierOrganisationCode":"%s","libraryCode":"PSARF001",
                 "bookmarkValues":[{"name":"DOELBIBLIOTHEEK","value":"PSARF002"}],"materialisedBy":"%s"}"""
                .formatted(template.definitionCode(), template.linkCode(), template.supplier().getCode(), USER);

        mockMvc.perform(post("/api/catalog-import/templates/{id}/materialisations",
                        template.definition().getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LINK_FIELD_BOTH_BOOKMARK_AND_EXPLICIT"));
    }

    // --- Helpers -------------------------------------------------------------------------------------

    /** Het canonieke sjabloon van §11 (bouwstap 5c): CULTUUR, DOELBIBLIOTHEEK, DETAILLEVERANCIER. */
    private void declareCanonicalBookmarks(be.dda.catalogimport.domain.ImportDefinitionRevision revision) {
        fixtures.declareOn(revision, "CULTUUR", BookmarkValueScope.DEFINITION, true, BookmarkDataType.ENUM,
                1, BookmarkUsagePlace.RECORD_FILTER_COMPARE_VALUE,
                String.valueOf(MaterialisationFixtures.FILTER_SEQUENCE), "NL,FR,EN", null);
        fixtures.declareOn(revision, "DOELBIBLIOTHEEK", BookmarkValueScope.LINK, true,
                BookmarkDataType.TEXT, 2, BookmarkUsagePlace.LINK_LIBRARY_CODE, "");
        fixtures.declareOn(revision, "DETAILLEVERANCIER", BookmarkValueScope.LINK, true,
                BookmarkDataType.TEXT, 3, BookmarkUsagePlace.FIELD_MAPPING_FIXED_VALUE,
                MaterialisationFixtures.MAPPED_FIELD);
    }

    private String canonicalBody(MaterialisationFixtures.Template template, String linkCode, String libraryCode) {
        return """
                {"mode":"NEW_DEFINITION","definitionCode":"%s","definitionName":"Afgeleide definitie",
                 "linkCode":"%s","linkName":"Afgeleide koppeling","supplierOrganisationCode":"%s",
                 "bookmarkValues":[{"name":"CULTUUR","value":"NL"},
                                   {"name":"DOELBIBLIOTHEEK","value":"%s"},
                                   {"name":"DETAILLEVERANCIER","value":"ACME-001"}],
                 "materialisedBy":"%s"}"""
                .formatted(template.definitionCode(), linkCode, template.supplier().getCode(), libraryCode, USER);
    }

    /** Een minimale, geldig gevormde aanvraag zonder bookmarkwaarden — voor de fase A/B/E-toetsen. */
    private String bareRequest(String definitionCode, String linkCode, String supplierCode) {
        return """
                {"mode":"NEW_DEFINITION","definitionCode":"%s","definitionName":"x",
                 "linkCode":"%s","linkName":"y","supplierOrganisationCode":"%s","libraryCode":"PSARF001",
                 "bookmarkValues":[],"materialisedBy":"%s"}"""
                .formatted(definitionCode, linkCode, supplierCode, USER);
    }
}
