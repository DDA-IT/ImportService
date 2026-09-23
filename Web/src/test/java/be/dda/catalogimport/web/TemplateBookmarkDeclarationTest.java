package be.dda.catalogimport.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * De declaratielaag van de materialisatiewizard (sjabloon-materialisatie-design.md §4 fase C, §5;
 * bouwstap 5b): {@code TemplateBookmarkService} en {@code CatalogImportTemplateController} end-to-end
 * via {@link MockMvc}, zelfde patroon en achtergrond (gedeelde H2, unieke codes per test) als
 * {@link SetupApiFlowTest}.
 * <p>
 * Het uitgeschakelde-vlag-gedrag (deze controller bestaat niet zonder
 * {@code catalogimport.setup-api.enabled=true}) wordt bewezen in {@link SetupApiDisabledTest}, samen
 * met de bestaande setup-API — dezelfde vlag raakt beide controllers.
 */
@SpringBootTest(properties = "catalogimport.setup-api.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("local")
class TemplateBookmarkDeclarationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    /**
     * Per-run achtervoegsel (zelfde patroon als {@code ImportTemplateBookmarkSchemaTest.RUN}): het
     * {@code local}-profiel draait tegen een blijvende PostgreSQL, niet een verse H2 per run. Een louter
     * JVM-statische teller (zoals {@link SetupApiFlowTest} gebruikt) botst dan bij de tweede run op
     * eigen achtergelaten rijen van een vorige run.
     */
    private static final String RUN = Long.toString(System.nanoTime() % 1_000_000_000L, 36).toUpperCase();
    private static final String SETUP = "/api/catalog-import/setup";
    private static final String TEMPLATES = "/api/catalog-import/templates";
    private static final String USER = "beheerder@example.test";

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    // --- Naamformaat ----------------------------------------------------------------------------------

    @Test
    void rejectsANonUppercaseTechnicalNameButAcceptsAValidOne() throws Exception {
        TemplateRevision template = template("NAME");

        mockMvc.perform(declareRequest(template, bookmarkJson("bad-name", "TEXT", "DEFINITION", 1, null)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(declareRequest(template, bookmarkJson("1CULTUUR", "TEXT", "DEFINITION", 1, null)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(declareRequest(template, bookmarkJson("MET SPATIE", "TEXT", "DEFINITION", 1, null)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(declareRequest(template, bookmarkJson("BESTANDS_PREFIX", "TEXT", "DEFINITION", 1, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("BESTANDS_PREFIX"));
    }

    // --- Uniciteit van naam en volgorde per revisie ----------------------------------------------------

    @Test
    void rejectsADuplicateNameAndADuplicateSortOrderWithinTheSameRevision() throws Exception {
        TemplateRevision template = template("UNIQ");

        mockMvc.perform(declareRequest(template, bookmarkJson("CULTUUR", "TEXT", "DEFINITION", 1, null)))
                .andExpect(status().isCreated());

        mockMvc.perform(declareRequest(template, bookmarkJson("CULTUUR", "TEXT", "LINK", 2, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOKMARK_NAME_IN_USE"));

        mockMvc.perform(declareRequest(template, bookmarkJson("ANDERE_NAAM", "TEXT", "DEFINITION", 1, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOKMARK_ORDER_IN_USE"));
    }

    // --- ENUM zonder allowed_values --------------------------------------------------------------------

    @Test
    void rejectsAnEnumBookmarkWithoutAllowedValuesButAcceptsItWithThem() throws Exception {
        TemplateRevision template = template("ENUM");

        mockMvc.perform(declareRequest(template, bookmarkJson("KEUZE_LEEG", "ENUM", "DEFINITION", 1, null)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(declareRequest(template,
                        bookmarkJson("KEUZE_OK", "ENUM", "DEFINITION", 1, "NL,FR,EN")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.allowedValues").value("NL,FR,EN"));
    }

    // --- DEFINITION-scope op een LINK_*-plaats (C1) ------------------------------------------------------

    @Test
    void rejectsADefinitionScopeBookmarkOnALinkPlace() throws Exception {
        TemplateRevision template = template("SCOPE");
        mockMvc.perform(declareRequest(template,
                        bookmarkJson("DETAILLEVERANCIER", "TEXT", "DEFINITION", 1, null)))
                .andExpect(status().isCreated());

        mockMvc.perform(usageRequest(template, "DETAILLEVERANCIER", "{\"placeKind\":\"LINK_LIBRARY_CODE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFIG_BOOKMARK_SCOPE_PLACE_CONFLICT"));

        // Dezelfde plaats op een LINK-scope bookmark is wél toegelaten.
        mockMvc.perform(declareRequest(template, bookmarkJson("DOELBIBLIOTHEEK", "TEXT", "LINK", 2, null)))
                .andExpect(status().isCreated());
        mockMvc.perform(usageRequest(template, "DOELBIBLIOTHEEK", "{\"placeKind\":\"LINK_LIBRARY_CODE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.placeKind").value("LINK_LIBRARY_CODE"));
    }

    // --- Bookmark zonder usage-rij: zichtbaar als probleem, niet als weigering bij declareren -----------

    @Test
    void showsABookmarkWithoutAnyUsageAsAProblemInTheReadModelWithoutThrowing() throws Exception {
        TemplateRevision template = template("ORPHAN");
        mockMvc.perform(declareRequest(template,
                        bookmarkJson("NERGENS_TOEGEPAST", "TEXT", "LINK", 1, null)))
                .andExpect(status().isCreated());

        mockMvc.perform(get(TEMPLATES + "/{definitionId}/revisions/{revisionId}/bookmarks",
                        template.definitionId(), template.revisionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookmarks[0].name").value("NERGENS_TOEGEPAST"))
                .andExpect(jsonPath("$.bookmarks[0].usages").isEmpty())
                .andExpect(jsonPath("$.problems[0].code").value("CONFIG_BOOKMARK_WITHOUT_PLACE"))
                .andExpect(jsonPath("$.problems[0].bookmarkName").value("NERGENS_TOEGEPAST"));
    }

    // --- Declareren op een niet-DRAFT revisie -----------------------------------------------------------

    @Test
    void rejectsDeclaringOnANonDraftRevision() throws Exception {
        TemplateRevision template = template("FROZEN");
        mockMvc.perform(post(SETUP + "/revisions/{id}/mappings", template.revisionId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetFieldCode\":\"EAN\",\"sourceReference\":\"ean\",\"sequenceNumber\":1}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post(SETUP + "/revisions/{id}/activate", template.revisionId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvedBy\":\"" + USER + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(declareRequest(template, bookmarkJson("TE_LAAT", "TEXT", "DEFINITION", 1, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_EDITABLE"));
    }

    // --- GET /templates ------------------------------------------------------------------------------

    @Test
    void listsOnlyReusableTemplateDefinitions() throws Exception {
        TemplateRevision template = template("LIST");

        // Geen ORDER BY op deze lijst (net als BundleQueryService.listBundles): een grote paginagrootte
        // maakt de test onafhankelijk van hoeveel sjablonen eerdere testruns al achterlieten.
        mockMvc.perform(get(TEMPLATES).param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id==" + template.definitionId() + ")]").exists());
    }

    // --- Helpers -------------------------------------------------------------------------------------

    private record TemplateRevision(long definitionId, long revisionId) {
    }

    private org.springframework.test.web.servlet.RequestBuilder declareRequest(TemplateRevision template,
                                                                                String body) {
        return post(TEMPLATES + "/{definitionId}/revisions/{revisionId}/bookmarks", template.definitionId(),
                template.revisionId()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private org.springframework.test.web.servlet.RequestBuilder usageRequest(TemplateRevision template,
                                                                              String bookmarkName, String body) {
        return post(TEMPLATES + "/{definitionId}/revisions/{revisionId}/bookmarks/{name}/usages",
                template.definitionId(), template.revisionId(), bookmarkName)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String bookmarkJson(String name, String dataType, String valueScope, int sortOrder,
                                       String allowedValues) {
        String allowed = allowedValues == null ? "null" : "\"" + allowedValues + "\"";
        return "{\"name\":\"" + name + "\",\"label\":\"" + name + " label\",\"dataType\":\"" + dataType
                + "\",\"valueScope\":\"" + valueScope + "\",\"ownerRole\":\"catalogImport.manage\","
                + "\"sortOrder\":" + sortOrder + ",\"allowedValues\":" + allowed + ",\"createdBy\":\"" + USER
                + "\"}";
    }

    /** Een sjabloondefinitie ({@code REUSABLE_TEMPLATE}) met één {@code DRAFT}-revisie. */
    private TemplateRevision template(String prefix) throws Exception {
        String unique = unique(prefix);
        mockMvc.perform(post(SETUP + "/source-organisations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + unique + "\",\"name\":\"" + unique
                                + " aankoopvereniging\",\"type\":\"PURCHASING_ASSOCIATION\"}"))
                .andExpect(status().isCreated());
        long definitionId = id(mockMvc.perform(post(SETUP + "/definitions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceOrganisationCode\":\"" + unique + "\",\"code\":\"" + unique
                                + "-TPL\",\"name\":\"" + unique + " sjabloon\",\"usageType\":\"REUSABLE_TEMPLATE\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long revisionId = id(mockMvc.perform(post(SETUP + "/definitions/{id}/revisions", definitionId)
                        .contentType(MediaType.APPLICATION_JSON).content(revisionJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString());
        return new TemplateRevision(definitionId, revisionId);
    }

    /** Dezelfde configuratie als {@code SetupApiFlowTest.revisionJson()}. */
    private static String revisionJson() {
        return """
                {"delimiter":";","quoteChar":"\\"","charset":"UTF-8","hasHeader":true,
                 "headerLineNumber":1,"fieldReferenceKind":"HEADER_NAME",
                 "identityProfileKind":"THREE_PART","supplierField":"leverancier",
                 "supplierGroupField":"groep","supplierReferenceField":"referentie",
                 "discountCodeField":null,"basePriceField":"prijs","descriptionField":"omschrijving",
                 "currencyField":"valuta","canonicalisationVersion":2,
                 "creationThresholdSharePercent":10,"maxCriticalSharePercent":25}""";
    }

    private static String unique(String prefix) {
        return "TB" + SEQUENCE.incrementAndGet() + prefix + "-" + RUN;
    }

    private static long id(String body) {
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }
}
