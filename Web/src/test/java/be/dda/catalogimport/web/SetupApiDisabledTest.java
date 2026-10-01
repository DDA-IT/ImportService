package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static be.dda.catalogimport.testsupport.TestActors.withoutPermissions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Wat {@code catalogimport.setup-api.enabled} sinds NT-3 nog afschermt, en wat niet meer (beslissingslog
 * 2026-09-30 "Nieuwe leverancier + taak (NT-spoor)", V2 = a). Deze klasse draait met de vlag <b>uit</b> — de
 * default, zoals in productie.
 *
 * <ul>
 *   <li><b>Regel:</b> een gebruiker met {@code MANAGE} richt zelf een leverancier en een taak in.
 *       <b>Implementatie:</b> de schrijfpaden van de inrichting (bronorganisatie, definitie, revisie, PATCH,
 *       mappings/filters/kritiekheid, activeren, opvolger, koppeling, taak), sjablonen lezen + materialiseren
 *       en de bookmarkwaarden van een koppeling bestaan ook zonder vlag. <b>Bewijs:</b> zonder login 401,
 *       met enkel {@code READ} 403 {@code PERMISSION_DENIED} (de rechtencheck bestaat enkel op een gemapte
 *       handler), met recht de gewone 2xx — of een 4xx mét stabiele {@code code} uit de service, nooit de
 *       404-zonder-code van een onbestaand pad.</li>
 *   <li><b>Regel:</b> sjabloonbeheer en het ontwikkeloverzicht blijven achter de vlag. <b>Bewijs:</b>
 *       {@code GET /setup/overview} en {@code POST .../bookmarks/{name}/usages} geven zonder vlag 404, óók met
 *       alle rechten ("vlag vóór recht" geldt enkel nog voor deze drie); {@code POST .../bookmarks} geeft 405,
 *       omdat {@code GET} op hetzelfde pad sinds NT-3 altijd bestaat.</li>
 * </ul>
 * De bestaande upload- en batchendpoints blijven gewoon bestaan; de vlag raakt enkel die drie paden.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class SetupApiDisabledTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String API = "/api/catalog-import";
    private static final String SETUP = API + "/setup";
    private static final String USER = "an.janssens@example.test";
    private static final long UNKNOWN = 999_999_999L;

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ApplicationContext context;
    @Autowired
    private WebApplicationContext webContext;

    // --- Welke controllers bestaan zonder vlag --------------------------------------------------------

    @Test
    void withoutTheFlagOnlyTheOverviewAndTheTemplateDeclarationControllersAreMissing() {
        assertThat(context.getBeanNamesForType(CatalogImportSetupOverviewController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(CatalogImportTemplateDeclarationController.class)).isEmpty();

        assertThat(context.getBeanNamesForType(CatalogImportSetupController.class)).isNotEmpty();
        assertThat(context.getBeanNamesForType(CatalogImportTemplateController.class)).isNotEmpty();
        assertThat(context.getBeanNamesForType(CatalogImportLinkController.class)).isNotEmpty();
    }

    // --- Blijft achter de vlag: 404, ook met alle rechten -----------------------------------------------

    /**
     * <b>Important technical constraint discovered (NT-3).</b> {@code POST .../bookmarks} deelt zijn pad met
     * {@code GET .../bookmarks}, dat sinds NT-3 altijd bestaat. Spring MVC antwoordt op een pad dat wél
     * gemapt is maar niet voor deze methode met 405 in plaats van 404. Het resultaat is hetzelfde — er is
     * geen handler, dus ook geen rechtencheck en niets geschreven — maar de statuscode verschilt van de
     * twee andere paden.
     */
    @Test
    void thePathsThatStayBehindTheFlagDoNotExistEvenWithEveryRight() throws Exception {
        mockMvc.perform(get(SETUP + "/overview").with(as(USER)))
                .andExpect(status().isNotFound());
        json(post(API + "/templates/{d}/revisions/{r}/bookmarks/{name}/usages", 1L, 1L, "X"),
                "{\"placeKind\":\"LINK_LIBRARY_CODE\"}")
                .andExpect(status().isNotFound());
        json(post(API + "/templates/{d}/revisions/{r}/bookmarks", 1L, 1L),
                "{\"name\":\"X\",\"label\":\"X\",\"dataType\":\"TEXT\",\"valueScope\":\"LINK\","
                        + "\"ownerRole\":\"catalogImport.manage\"}")
                .andExpect(status().isMethodNotAllowed());
    }

    // --- Achter de vlag vandaan: 401 zonder login, 403 zonder recht -------------------------------------

    @Test
    void theMovedPathsAnswer401WithoutALogin() throws Exception {
        MockMvc bareMvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
        bareMvc.perform(post(SETUP + "/source-organisations").with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        bareMvc.perform(get(API + "/templates"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    /**
     * {@code PERMISSION_DENIED} komt uit de interceptor op een <b>gemapte</b> handler: een 403 met die code
     * bewijst dus dat het pad zonder vlag bestaat, en dat het recht het enige slot is.
     */
    @Test
    void everyMovedWritePathIs403WithOnlyReadAndEveryMovedReadPathIs403WithoutRights() throws Exception {
        for (MockHttpServletRequestBuilder write : List.of(
                post(SETUP + "/source-organisations"), post(SETUP + "/definitions"),
                post(SETUP + "/definitions/{id}/revisions", 1L), patch(SETUP + "/revisions/{id}", 1L),
                post(SETUP + "/revisions/{id}/mappings", 1L), delete(SETUP + "/revisions/{id}/mappings/{m}", 1L, 1L),
                post(SETUP + "/revisions/{id}/filters", 1L), delete(SETUP + "/revisions/{id}/filters/{f}", 1L, 1L),
                post(SETUP + "/revisions/{id}/field-criticality", 1L), post(SETUP + "/revisions/{id}/activate", 1L),
                post(SETUP + "/revisions/{id}/successor", 1L), post(SETUP + "/links"), post(SETUP + "/tasks"),
                post(API + "/templates/{d}/materialisations", 1L),
                put(API + "/links/{id}/bookmark-values/{name}", 1L, "X"))) {
            mockMvc.perform(write.with(as(USER, Permission.READ)).contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
        for (String read : List.of(API + "/templates", API + "/templates/1/revisions/1/bookmarks",
                API + "/templates/1/materialisations", API + "/links/1/bookmark-values")) {
            mockMvc.perform(get(read).with(withoutPermissions(USER)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
    }

    // --- Achter de vlag vandaan: met recht het gewone gedrag -------------------------------------------

    /**
     * De volledige inrichtingsketen zonder vlag, met {@code MANAGE}: exact dezelfde statuscodes als vroeger
     * met de vlag aan (201/200/204).
     */
    @Test
    void withManageAWholeChainIsSetUpWithoutTheFlag() throws Exception {
        String unique = unique("CHAIN");
        json(post(SETUP + "/source-organisations"),
                "{\"code\":\"" + unique + "\",\"name\":\"" + unique + " BV\",\"type\":\"SUPPLIER\"}")
                .andExpect(status().isCreated());
        long definitionId = id(json(post(SETUP + "/definitions"),
                "{\"sourceOrganisationCode\":\"" + unique + "\",\"code\":\"" + unique + "-DEF\",\"name\":\""
                        + unique + " catalogus\"}")
                .andExpect(status().isCreated()));
        long revisionId = id(json(post(SETUP + "/definitions/{id}/revisions", definitionId), revisionJson())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT")));

        json(patch(SETUP + "/revisions/{id}", revisionId), "{\"maxCriticalSharePercent\":30}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
        json(post(SETUP + "/revisions/{id}/mappings", revisionId),
                "{\"targetFieldCode\":\"EAN\",\"sourceReference\":\"ean\",\"sequenceNumber\":1}")
                .andExpect(status().isCreated());
        long filterId = id(json(post(SETUP + "/revisions/{id}/filters", revisionId),
                "{\"sourceReference\":\"groep\",\"operator\":\"EQUALS\",\"compareValue\":\"MEET\","
                        + "\"outcome\":\"EXCLUDE\"}")
                .andExpect(status().isCreated()));
        json(post(SETUP + "/revisions/{id}/field-criticality", revisionId),
                "{\"fieldKey\":\"DESCRIPTION\",\"criticality\":\"CRITICAL\"}")
                .andExpect(status().isCreated());
        json(delete(SETUP + "/revisions/{id}/filters/{f}", revisionId, filterId), "")
                .andExpect(status().isNoContent());
        json(post(SETUP + "/revisions/{id}/activate", revisionId), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        json(post(SETUP + "/revisions/{id}/successor", revisionId), "{\"changeReason\":\"NT-3 opvolger\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"));

        long linkId = id(json(post(SETUP + "/links"),
                "{\"definitionId\":" + definitionId + ",\"code\":\"" + unique + "-LINK\",\"name\":\"" + unique
                        + " koppeling\",\"supplierCode\":\"" + unique + "\",\"libraryCode\":\"" + library(unique)
                        + "\"}")
                .andExpect(status().isCreated()));
        json(post(SETUP + "/tasks"), "{\"linkId\":" + linkId + ",\"name\":\"" + unique + " levering\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.triggerType").value("MANUAL"));

        mockMvc.perform(get(API + "/links/{id}/bookmark-values", linkId).with(as(USER)))
                .andExpect(status().isOk());
        mockMvc.perform(get(API + "/templates").with(as(USER)))
                .andExpect(status().isOk());
    }

    /**
     * Met recht en een onbekend object: de gewone 4xx uit de service, altijd mét stabiele {@code code} —
     * nooit de 404-zonder-code van een pad dat niet bestaat.
     */
    @Test
    void withRightsAnUnknownObjectGivesTheServiceAnswerNotAMissingRoute() throws Exception {
        assertHandledByTheService(json(delete(SETUP + "/revisions/{id}/mappings/{m}", UNKNOWN, UNKNOWN), ""));
        assertHandledByTheService(json(post(SETUP + "/revisions/{id}/successor", UNKNOWN),
                "{\"changeReason\":\"x\"}"));
        assertHandledByTheService(json(put(API + "/links/{id}/bookmark-values/{name}", UNKNOWN, "X"),
                "{\"value\":\"NL\"}"));
        assertHandledByTheService(json(post(API + "/templates/{d}/materialisations", UNKNOWN), "{}"));
        assertHandledByTheService(mockMvc.perform(get(API + "/templates/{d}/revisions/{r}/bookmarks",
                UNKNOWN, UNKNOWN).with(as(USER))));
        assertHandledByTheService(mockMvc.perform(get(API + "/templates/{d}/materialisations", UNKNOWN)
                .with(as(USER))));
        assertHandledByTheService(mockMvc.perform(get(API + "/links/{id}/bookmark-values", UNKNOWN)
                .with(as(USER))));
    }

    @Test
    void theExistingEndpointsKeepAnsweringTheirOwnCodes() throws Exception {
        // Geen 404 op het pad zelf maar de gewone, bestaande foutcode: de vlag raakt niets anders.
        mockMvc.perform(get(API + "/deliveries/{id}", UNKNOWN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DELIVERY_NOT_FOUND"));
    }

    // --- Hulpmiddelen ----------------------------------------------------------------------------------

    private ResultActions json(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mockMvc.perform(request.with(as(USER)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static void assertHandledByTheService(ResultActions result) throws Exception {
        MockHttpServletResponse response = result.andReturn().getResponse();
        assertThat(response.getStatus()).isBetween(400, 499).isNotEqualTo(401).isNotEqualTo(403);
        String code = JsonPath.read(response.getContentAsString(), "$.code");
        assertThat(code).isNotBlank();
    }

    private static long id(ResultActions result) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    /** Dezelfde configuratie als {@code SetupApiFlowTest}: puntkomma, header, drie-delige identiteit. */
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
        return "SD" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + prefix;
    }

    /** {@code import_link.library_code} is varchar(20). */
    private static String library(String unique) {
        return unique.length() <= 20 ? unique : unique.substring(0, 20);
    }
}
