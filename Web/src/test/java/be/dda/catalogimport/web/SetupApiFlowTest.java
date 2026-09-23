package be.dda.catalogimport.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * De setup-API (ontwikkelhulp) end-to-end: een volledige keten configureren en er daarna echte
 * voorbeeldleveringen uit {@code docs/samples} doorheen sturen.
 *
 * <h2>Wat hier bewezen wordt</h2>
 * <ul>
 *   <li>De keten organisatie → definitie → revisie → activate → koppeling → taak levert een taak op
 *       waarop de bestaande upload werkt, zonder testcode of SQL.</li>
 *   <li>De eerste levering is een initialisatie: {@code INITIAL_LOAD}, {@code REVIEW_REQUIRED} en
 *       tien creaties in {@code AWAITING_APPROVAL}.</li>
 *   <li>Na {@code accept-baseline} levert een identieke herlevering 0 inhoudelijke mutaties op en
 *       levert één gewijzigde prijs precies één {@code UPDATE} met {@code PRICE} in de
 *       {@code domain_mask} op.</li>
 *   <li>Een levering met een onleesbare prijs en een lege identiteitscomponent blijft doorgaan met
 *       verworpen regels; een dubbele identiteit blokkeert de hele levering.</li>
 *   <li>De foutpaden van de setup-API zelf: 404, 409 en 400.</li>
 * </ul>
 * Elke test bouwt een eigen keten met unieke codes: de H2-database is gedeeld.
 */
@SpringBootTest(properties = "catalogimport.setup-api.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("local")
class SetupApiFlowTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String SETUP = "/api/catalog-import/setup";
    private static final String USER = "tester@example.test";

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    // --- (b) Volledige keten + echte leveringen ---------------------------------------------------

    @Test
    void aChainBuiltThroughTheSetupApiAcceptsTheSampleDeliveriesAndBehavesAsDocumented() throws Exception {
        Chain chain = chain("FLOW");

        // 1. Eerste levering: alles is nieuw, dus een initialisatie die op goedkeuring wacht.
        long firstBatch = batchId(upload(chain.taskId(), "REF-01", "01-eerste-levering.csv")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SCREENED"))
                .andExpect(jsonPath("$.rawRecordCount").value(10))
                .andExpect(jsonPath("$.validRecordCount").value(10))
                .andExpect(jsonPath("$.rejectedRecordCount").value(0))
                .andExpect(jsonPath("$.newCount").value(10))
                .andExpect(jsonPath("$.contentMutationCount").value(10))
                .andReturn().getResponse().getContentAsString());
        batch(firstBatch)
                .andExpect(jsonPath("$.validationResult").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.creationOutcome").value("INITIAL_LOAD"))
                .andExpect(jsonPath("$.creationScopeCount").value(0))
                .andExpect(jsonPath("$.awaitingApprovalCount").value(10));
        assertThat(contentMutationStatuses(firstBatch)).hasSize(10).containsOnly("AWAITING_APPROVAL");

        // 2. Eén bevoegde persoon aanvaardt de nulmeting.
        mockMvc.perform(post("/api/catalog-import/batches/{id}/accept-baseline", firstBatch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acceptedBy\":\"" + USER + "\",\"reason\":\"nulmeting van de demoketen\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BASELINE_ACCEPTED"))
                .andExpect(jsonPath("$.skippedMutationCount").value(10));

        // 3. Exact hetzelfde bestand opnieuw: niets nieuw, niets gewijzigd, geen inhoudelijke mutatie.
        long secondBatch = batchId(upload(chain.taskId(), "REF-02", "02-zelfde-levering-nogmaals.csv")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SCREENED"))
                .andExpect(jsonPath("$.unchangedCount").value(10))
                .andExpect(jsonPath("$.newCount").value(0))
                .andExpect(jsonPath("$.changedCount").value(0))
                .andExpect(jsonPath("$.contentMutationCount").value(0))
                .andReturn().getResponse().getContentAsString());
        assertThat(contentMutationStatuses(secondBatch)).isEmpty();

        // 4. Eén gewijzigde prijs: precies één UPDATE, met PRICE in de domain_mask.
        long thirdBatch = batchId(upload(chain.taskId(), "REF-03", "03-prijswijziging.csv")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SCREENED"))
                .andExpect(jsonPath("$.changedCount").value(1))
                .andExpect(jsonPath("$.unchangedCount").value(9))
                .andExpect(jsonPath("$.newCount").value(0))
                .andExpect(jsonPath("$.contentMutationCount").value(1))
                .andReturn().getResponse().getContentAsString());
        mockMvc.perform(get("/api/catalog-import/batches/{id}/mutations", thirdBatch)
                        .param("actionType", "UPDATE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].identitySupplierReference").value("A-1001"))
                .andExpect(jsonPath("$.content[0].domainMask").value("PRICE"))
                .andExpect(jsonPath("$.content[0].beforeBasePrice").value(149.50))
                .andExpect(jsonPath("$.content[0].afterBasePrice").value(179.50));
    }

    // --- (c) Verworpen regels en blokkade ----------------------------------------------------------

    @Test
    void aDeliveryWithAnUnreadablePriceAndAnEmptyIdentityComponentRejectsThoseRowsAndAsksForReview()
            throws Exception {
        Chain chain = acceptedBaseline("ERR");

        long batchId = batchId(upload(chain.taskId(), "REF-04", "04-met-fouten.csv")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SCREENED"))
                .andExpect(jsonPath("$.rawRecordCount").value(10))
                .andExpect(jsonPath("$.validRecordCount").value(8))
                .andExpect(jsonPath("$.rejectedRecordCount").value(2))
                .andReturn().getResponse().getContentAsString());

        batch(batchId)
                // Twee kritieke lijnen op tien records is 20%, onder de 25% van deze keten: review,
                // geen blokkade.
                .andExpect(jsonPath("$.criticalLineCount").value(2))
                .andExpect(jsonPath("$.validationResult").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.blockedCode").doesNotExist());
        assertThat(issueCodes(batchId)).contains("PRICE_UNREADABLE", "IDENTITY_COMPONENT_EMPTY");
    }

    @Test
    void aDuplicateIdentityInOneDeliveryBlocksTheWholeDelivery() throws Exception {
        Chain chain = acceptedBaseline("DUP");

        long batchId = batchId(upload(chain.taskId(), "REF-05", "05-dubbele-identiteit.csv")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("BLOCKED"))
                .andExpect(jsonPath("$.blockedCode").value("DUPLICATE_IDENTITY_IN_DELIVERY"))
                .andReturn().getResponse().getContentAsString());

        batch(batchId).andExpect(jsonPath("$.validationResult").value("BLOCKING"));
        assertThat(contentMutationStatuses(batchId)).isEmpty();
    }

    // --- (e) Foutpaden van de setup-API zelf --------------------------------------------------------

    @Test
    void anUnknownDefinitionIsA404() throws Exception {
        mockMvc.perform(post(SETUP + "/definitions/{id}/revisions", 999_999_999L)
                        .contentType(MediaType.APPLICATION_JSON).content(revisionJson()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEFINITION_NOT_FOUND"));

        mockMvc.perform(post(SETUP + "/revisions/{id}/activate", 999_999_999L)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_FOUND"));

        mockMvc.perform(post(SETUP + "/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"linkId\":999999999,\"name\":\"taak\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"));
    }

    @Test
    void aDuplicateCodeIsA409() throws Exception {
        String unique = unique("CONF");
        createOrganisation(unique);

        mockMvc.perform(post(SETUP + "/source-organisations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + unique + "\",\"name\":\"Nog eens\",\"type\":\"SUPPLIER\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_ORGANISATION_CODE_IN_USE"));

        long definitionId = createDefinition(unique);
        mockMvc.perform(post(SETUP + "/definitions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceOrganisationCode\":\"" + unique + "\",\"code\":\"" + unique
                                + "-DEF\",\"name\":\"Nog eens\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEFINITION_CODE_IN_USE"));

        // Een bevroren (ACTIVE) revisie wordt nooit bijgewerkt en nooit een tweede keer geactiveerd.
        long revisionId = createRevision(definitionId);
        activate(revisionId).andExpect(status().isOk());
        activate(revisionId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_ACTIVATABLE"));
        mockMvc.perform(post(SETUP + "/revisions/{id}/mappings", revisionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetFieldCode\":\"EAN\",\"sourceReference\":\"ean\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_EDITABLE"));
    }

    @Test
    void anInvalidValueIsA400AndPersistsNothing() throws Exception {
        String unique = unique("BAD");

        // Lege verplichte waarde.
        mockMvc.perform(post(SETUP + "/source-organisations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"  \",\"name\":\"Zonder code\",\"type\":\"SUPPLIER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
        // Onbekende enumwaarde.
        mockMvc.perform(post(SETUP + "/source-organisations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + unique + "\",\"name\":\"Fout type\",\"type\":\"KLANT\"}"))
                .andExpect(status().isBadRequest());
        assertThat(organisationCount(unique)).isZero();

        createOrganisation(unique);
        long definitionId = createDefinition(unique);
        // Een driedelige identiteit die tóch een kortingscodeveld draagt, spreekt zichzelf tegen.
        mockMvc.perform(post(SETUP + "/definitions/{id}/revisions", definitionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revisionJson().replace("\"discountCodeField\":null",
                                "\"discountCodeField\":\"korting\"")))
                .andExpect(status().isBadRequest());

        // Een configuratiefout draagt de CONFIG_*-code van de bestaande screeningvalidatie.
        long revisionId = createRevision(definitionId);
        mockMvc.perform(post(SETUP + "/revisions/{id}/mappings", revisionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetFieldCode\":\"DESCRIPTION\",\"sourceReference\":\"omschrijving\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("CONFIG_FIELD_MAPPING_DUPLICATES_REVISION")));
        assertThat(mappingCount(revisionId)).isZero();

        mockMvc.perform(post(SETUP + "/revisions/{id}/mappings", revisionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetFieldCode\":\"BESTAAT_NIET\",\"sourceReference\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FIELD_NOT_FOUND"));
    }

    // --- Recordfilters en kritiek-overrules --------------------------------------------------------

    /**
     * Een recordfilter en een kritiek-overrule via de setup-API, met hun werking in de screening: de
     * twee {@code MEET}-regels vallen buiten de importscope en worden geteld, niet stil overgeslagen.
     */
    @Test
    void aRecordFilterAndACriticalityOverruleFromTheSetupApiAreAppliedByTheScreening() throws Exception {
        String unique = unique("FILT");
        createOrganisation(unique);
        long definitionId = createDefinition(unique);
        long revisionId = createRevision(definitionId);

        mockMvc.perform(post(SETUP + "/revisions/{id}/filters", revisionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceReference\":\"groep\",\"operator\":\"EQUALS\","
                                + "\"compareValue\":\"MEET\",\"outcome\":\"EXCLUDE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sequenceNumber").value(1));
        mockMvc.perform(post(SETUP + "/revisions/{id}/field-criticality", revisionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fieldKey\":\"DESCRIPTION\",\"criticality\":\"CRITICAL\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.criticality").value("CRITICAL"));

        // Dubbele input: dezelfde volgorde en hetzelfde veld nog eens.
        mockMvc.perform(post(SETUP + "/revisions/{id}/filters", revisionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sequenceNumber\":1,\"sourceReference\":\"groep\",\"operator\":\"EQUALS\","
                                + "\"compareValue\":\"ZAAG\",\"outcome\":\"EXCLUDE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FILTER_SEQUENCE_IN_USE"));
        mockMvc.perform(post(SETUP + "/revisions/{id}/field-criticality", revisionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fieldKey\":\"DESCRIPTION\",\"criticality\":\"NON_CRITICAL\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FIELD_CRITICALITY_IN_USE"));
        // Een identiteitsveld kan nooit niet-kritiek zijn: zonder identiteit is er niets te beoordelen.
        mockMvc.perform(post(SETUP + "/revisions/{id}/field-criticality", revisionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fieldKey\":\"SUPPLIER\",\"criticality\":\"NON_CRITICAL\"}"))
                .andExpect(status().isBadRequest());
        // Dubbel doelveld in dezelfde revisie.
        mockMvc.perform(post(SETUP + "/revisions/{id}/mappings", revisionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetFieldCode\":\"EAN\",\"sourceReference\":\"ean\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post(SETUP + "/revisions/{id}/mappings", revisionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetFieldCode\":\"EAN\",\"sourceReference\":\"ean\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MAPPING_TARGET_IN_USE"));

        activate(revisionId).andExpect(status().isOk());
        long linkId = id(mockMvc.perform(post(SETUP + "/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definitionId\":" + definitionId + ",\"code\":\"" + unique
                                + "-LINK\",\"name\":\"" + unique + " koppeling\",\"supplierCode\":\"" + unique
                                + "\",\"libraryCode\":\"" + library(unique) + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long taskId = id(mockMvc.perform(post(SETUP + "/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"linkId\":" + linkId + ",\"name\":\"" + unique + " levering\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        long batchId = batchId(upload(taskId, "REF-FILT", "01-eerste-levering.csv")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rawRecordCount").value(10))
                .andExpect(jsonPath("$.validRecordCount").value(8))
                .andExpect(jsonPath("$.newCount").value(8))
                .andReturn().getResponse().getContentAsString());
        batch(batchId).andExpect(jsonPath("$.filteredOutCount").value(2));
    }

    // --- Overzicht ---------------------------------------------------------------------------------

    @Test
    void theOverviewShowsTheTaskIdOfTheChain() throws Exception {
        Chain chain = chain("OVER");

        mockMvc.perform(get(SETUP + "/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceOrganisations[?(@.code=='" + chain.code() + "')]").exists())
                .andExpect(jsonPath("$.sourceOrganisations[?(@.code=='" + chain.code()
                        + "')].definitions[0].links[0].tasks[0].id").value(
                        org.hamcrest.Matchers.hasItem((int) chain.taskId())))
                .andExpect(jsonPath("$.sourceOrganisations[?(@.code=='" + chain.code()
                        + "')].definitions[0].activeRevision.status").value(
                        org.hamcrest.Matchers.hasItem("ACTIVE")));
    }

    // --- Helpers -------------------------------------------------------------------------------------

    private record Chain(String code, long definitionId, long revisionId, long linkId, long taskId) {
    }

    /** De volledige keten via de setup-API, zoals de README ze beschrijft. */
    private Chain chain(String prefix) throws Exception {
        String unique = unique(prefix);
        createOrganisation(unique);
        long definitionId = createDefinition(unique);
        long revisionId = createRevision(definitionId);
        mockMvc.perform(post(SETUP + "/revisions/{id}/mappings", revisionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetFieldCode\":\"EAN\",\"sourceReference\":\"ean\",\"sequenceNumber\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.criticality").value("CRITICAL"));
        activate(revisionId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));

        String linkBody = mockMvc.perform(post(SETUP + "/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definitionId\":" + definitionId + ",\"code\":\"" + unique
                                + "-LINK\",\"name\":\"" + unique + " koppeling\",\"supplierCode\":\"" + unique
                                + "\",\"libraryCode\":\"" + library(unique) + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long linkId = id(linkBody);
        String taskBody = mockMvc.perform(post(SETUP + "/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"linkId\":" + linkId + ",\"name\":\"" + unique + " manuele levering\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.triggerType").value("MANUAL"))
                .andExpect(jsonPath("$.preventConcurrentRuns").value(true))
                .andReturn().getResponse().getContentAsString();
        return new Chain(unique, definitionId, revisionId, linkId, id(taskBody));
    }

    /** Een keten waarvan de eerste levering al als nulmeting aanvaard is. */
    private Chain acceptedBaseline(String prefix) throws Exception {
        Chain chain = chain(prefix);
        long batchId = batchId(upload(chain.taskId(), "REF-BASE", "01-eerste-levering.csv")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mockMvc.perform(post("/api/catalog-import/batches/{id}/accept-baseline", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acceptedBy\":\"" + USER + "\",\"reason\":\"nulmeting\"}"))
                .andExpect(status().isOk());
        return chain;
    }

    private void createOrganisation(String unique) throws Exception {
        mockMvc.perform(post(SETUP + "/source-organisations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + unique + "\",\"name\":\"" + unique
                                + " BV\",\"type\":\"SUPPLIER\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(unique));
    }

    private long createDefinition(String unique) throws Exception {
        return id(mockMvc.perform(post(SETUP + "/definitions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceOrganisationCode\":\"" + unique + "\",\"code\":\"" + unique
                                + "-DEF\",\"name\":\"" + unique + " catalogus\",\"usageType\":\"OWN_DEFINITION\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private long createRevision(long definitionId) throws Exception {
        return id(mockMvc.perform(post(SETUP + "/definitions/{id}/revisions", definitionId)
                        .contentType(MediaType.APPLICATION_JSON).content(revisionJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString());
    }

    private ResultActions activate(long revisionId) throws Exception {
        return mockMvc.perform(post(SETUP + "/revisions/{id}/activate", revisionId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"approvedBy\":\"" + USER + "\"}"));
    }

    /** Dezelfde configuratie als het demoprofiel: puntkomma, header, drie-delige identiteit. */
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

    private ResultActions upload(long taskId, String reference, String sampleName) throws Exception {
        return mockMvc.perform(multipart("/api/catalog-import/tasks/{id}/deliveries", taskId)
                .file(new MockMultipartFile("file", sampleName, "text/csv", read(sampleName)))
                .param("deliveryReference", reference)
                .param("uploadedBy", USER));
    }

    private ResultActions batch(long batchId) throws Exception {
        return mockMvc.perform(get("/api/catalog-import/batches/{id}", batchId)).andExpect(status().isOk());
    }

    private List<String> contentMutationStatuses(long batchId) {
        return jdbc.queryForList("select status from import_mutation where batch_id = ? "
                + "and action_type in ('CREATE', 'UPDATE')", String.class, batchId);
    }

    private List<String> issueCodes(long batchId) {
        return jdbc.queryForList("select issue_code from import_row_issue where batch_id = ?",
                String.class, batchId);
    }

    private long organisationCount(String code) {
        Long count = jdbc.queryForObject("select count(*) from source_organisation where code = ?",
                Long.class, code);
        return count == null ? 0L : count;
    }

    private long mappingCount(long revisionId) {
        Long count = jdbc.queryForObject("select count(*) from import_field_mapping "
                + "where definition_revision_id = ?", Long.class, revisionId);
        return count == null ? 0L : count;
    }

    private static String unique(String prefix) {
        return "SU" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + prefix;
    }

    /** {@code import_link.library_code} is varchar(20). */
    private static String library(String unique) {
        return unique.length() <= 20 ? unique : unique.substring(0, 20);
    }

    private static long id(String body) {
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private static long batchId(String body) {
        return ((Number) JsonPath.read(body, "$.batchId")).longValue();
    }

    /** Leest een voorbeeldbestand uit {@code docs/samples}, ongeacht vanwaar de test start. */
    private static byte[] read(String sampleName) throws IOException {
        Path directory = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && directory != null; depth++, directory = directory.getParent()) {
            Path candidate = directory.resolve("docs").resolve("samples").resolve(sampleName);
            if (Files.isRegularFile(candidate)) {
                return Files.readAllBytes(candidate);
            }
        }
        throw new IllegalStateException("Voorbeeldbestand docs/samples/" + sampleName
                + " niet gevonden vanaf " + Path.of("").toAbsolutePath());
    }
}
