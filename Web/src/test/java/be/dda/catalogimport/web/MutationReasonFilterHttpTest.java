package be.dda.catalogimport.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.ImportMutationRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.ImportMutation;
import be.dda.catalogimport.domain.MutationActionType;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Het {@code statusReason}-filter (en het nieuwe {@code status}-filter op de batchlijst) op
 * {@code GET /bundles/{id}/mutations} en {@code GET /batches/{id}/mutations}: exacte,
 * hoofdlettergevoelige gelijkheid; afwezig of blanco = geen filter; een onbekende reden = lege pagina.
 * De fixture is een echte upload met drie CREATE's plus de IMPORT_MARKER; twee CREATE's krijgen een
 * gekende reden, de rest houdt de reden van de screening.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=50",
        "catalogimport.screening.mutation-chunk-size=50",
        "catalogimport.screening.recovery-on-startup=false"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class MutationReasonFilterHttpTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    private static final String CREATOR = "jan.peeters@example.test";
    private static final String DECIDER = "piet.willems@example.test";

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SourceOrganisationRepository sourceOrganisations;
    @Autowired
    private ImportDefinitionRepository definitions;
    @Autowired
    private ImportDefinitionRevisionRepository revisions;
    @Autowired
    private ImportLinkRepository links;
    @Autowired
    private CatalogImportTaskRepository tasks;
    @Autowired
    private ImportMutationRepository mutations;

    private record Scenario(long batchId, long bundleId, String reason) {
    }

    @Test
    void reasonFilterIsExactAndBlankOrAbsentMeansNoFilterOnBothEndpoints() throws Exception {
        Scenario s = scenario("RSN");
        String bundleUrl = "/api/catalog-import/bundles/{id}/mutations";
        String batchUrl = "/api/catalog-import/batches/{id}/mutations";

        // Zonder filter: 3 CREATE's + marker; blanco en afwezig zijn identiek.
        String none = body(get(bundleUrl, s.bundleId()));
        assertThat(JsonPath.<Integer>read(none, "$.totalElements")).isEqualTo(4);
        assertThat(body(get(bundleUrl, s.bundleId()).param("statusReason", ""))).isEqualTo(none);
        assertThat(body(get(bundleUrl, s.bundleId()).param("statusReason", "   "))).isEqualTo(none);
        String noneBatch = body(get(batchUrl, s.batchId()));
        assertThat(JsonPath.<Integer>read(noneBatch, "$.totalElements")).isEqualTo(4);
        assertThat(body(get(batchUrl, s.batchId()).param("statusReason", ""))).isEqualTo(noneBatch);

        // Exacte reden: precies de twee gemarkeerde mutaties, oplopend op id.
        for (String url : List.of(bundleUrl, batchUrl)) {
            long id = url.equals(bundleUrl) ? s.bundleId() : s.batchId();
            String hit = body(get(url, id).param("statusReason", s.reason()));
            assertThat(JsonPath.<Integer>read(hit, "$.totalElements")).isEqualTo(2);
            List<Integer> ids = JsonPath.read(hit, "$.content[*].id");
            assertThat(ids).isSorted();
            assertThat(JsonPath.<List<String>>read(hit, "$.content[*].statusReason"))
                    .containsOnly(s.reason());

            // Onbekende reden en andere hoofdletters: lege pagina, geen fout.
            mockMvc.perform(get(url, id).param("statusReason", "BESTAAT-NIET"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(0))
                    .andExpect(jsonPath("$.content.length()").value(0));
            mockMvc.perform(get(url, id).param("statusReason", s.reason().toLowerCase()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(0));
        }
    }

    @Test
    void statusReasonAndActionTypeCombineAndTheBatchListNowAcceptsStatus() throws Exception {
        Scenario s = scenario("CMB");
        String bundleUrl = "/api/catalog-import/bundles/{id}/mutations";
        String batchUrl = "/api/catalog-import/batches/{id}/mutations";

        mockMvc.perform(get(bundleUrl, s.bundleId()).param("status", "AWAITING_APPROVAL")
                        .param("statusReason", s.reason()).param("actionType", "CREATE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get(bundleUrl, s.bundleId()).param("status", "AWAITING_APPROVAL")
                        .param("statusReason", s.reason()).param("actionType", "IMPORT_MARKER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // Batchlijst: nieuw status-filter, gecombineerd met reden en actionType.
        mockMvc.perform(get(batchUrl, s.batchId()).param("status", "AWAITING_APPROVAL")
                        .param("statusReason", s.reason()).param("actionType", "CREATE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get(batchUrl, s.batchId()).param("status", "READY_FOR_PUBLICATION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get(batchUrl, s.batchId()).param("actionType", "CREATE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    /**
     * De teller van het filter is een bovengrens voor de groepsactie: die past bovenop dezelfde filter
     * nog enkel CREATE/UPDATE zonder eerdere beslissing toe.
     */
    @Test
    void totalElementsUnderAFilterIsAtLeastTheAffectedCountOfTheGroupDecisionWithTheSameFilter()
            throws Exception {
        Scenario s = scenario("BND");
        // Brede filter (zonder reden): de lijst toont ook de al beslisten mutaties.
        String reasonlessFilter = "{\"batchId\":" + s.batchId() + ",\"status\":\"AWAITING_APPROVAL\"}";
        int listed = JsonPath.<Integer>read(body(get("/api/catalog-import/bundles/{id}/mutations", s.bundleId())
                .param("batchId", String.valueOf(s.batchId())).param("status", "AWAITING_APPROVAL")),
                "$.totalElements");
        assertThat(listed).isEqualTo(3);

        String reasonFilter = "{\"batchId\":" + s.batchId() + ",\"statusReason\":\"" + s.reason() + "\"}";
        int listedByReason = JsonPath.<Integer>read(body(get("/api/catalog-import/bundles/{id}/mutations",
                s.bundleId()).param("statusReason", s.reason())), "$.totalElements");

        int affected = decide(s.bundleId(), reasonFilter);
        assertThat(affected).isEqualTo(2);
        assertThat(listedByReason).isGreaterThanOrEqualTo(affected);

        // Tweede groepsactie met de brede filter: enkel de nog onbeslisde CREATE; de
        // reeds beslisten vallen af, dus de lijst (3) is strikt groter dan de geraakte set (1).
        int affectedBroad = decide(s.bundleId(), reasonlessFilter);
        assertThat(affectedBroad).isEqualTo(1);
        assertThat(listed).isGreaterThan(affectedBroad);
    }

    // --- Helpers ----------------------------------------------------------------------------------------

    private int decide(long bundleId, String filterJson) throws Exception {
        String body = "{\"decisionKind\":\"APPROVE\",\"decidedBy\":\"" + DECIDER + "\","
                + "\"reason\":\"Filtertest\",\"filter\":" + filterJson + "}";
        String response = mockMvc.perform(post("/api/catalog-import/bundles/{id}/decisions", bundleId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.affectedCount")).intValue();
    }

    private String body(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    /** Upload + screening, bundel met die batch, en twee van de drie CREATE's krijgen een gekende reden. */
    private Scenario scenario(String prefix) throws Exception {
        String unique = "MR" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
        CatalogImportTask task = fixture(unique);
        StringBuilder csv = new StringBuilder(HEADER);
        for (int index = 1; index <= 3; index++) {
            csv.append("ACME;G1;R").append(index).append(";1").append(index).append(",00;Artikel ")
                    .append(index).append('\n');
        }
        String upload = mockMvc.perform(multipart("/api/catalog-import/tasks/{id}/deliveries", task.getId())
                        .file(new MockMultipartFile("file", "levering.csv", "text/csv",
                                csv.toString().getBytes(StandardCharsets.UTF_8)))
                        .param("deliveryReference", unique + "-LEV")
                        .param("uploadedBy", "tester@example.test"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long batchId = ((Number) JsonPath.read(upload, "$.batchId")).longValue();

        String reason = "TESTREDEN-" + unique;
        List<ImportMutation> creates = mutations.findByBatchIdAndActionType(batchId, MutationActionType.CREATE,
                PageRequest.of(0, 10)).getContent();
        assertThat(creates).hasSize(3);
        for (int index = 0; index < 2; index++) {
            creates.get(index).setStatusReason(reason);
        }
        mutations.saveAllAndFlush(creates);

        String created = mockMvc.perform(post("/api/catalog-import/bundles").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bundleReference\":\"BND-" + unique + "\",\"targetMode\":\"SIMULATION\","
                                + "\"createdBy\":\"" + CREATOR + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long bundleId = ((Number) JsonPath.read(created, "$.id")).longValue();
        mockMvc.perform(post("/api/catalog-import/bundles/{id}/batches", bundleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchIds\":[" + batchId + "],\"addedBy\":\"" + CREATOR + "\"}"))
                .andExpect(status().isOk());
        return new Scenario(batchId, bundleId, reason);
    }

    private CatalogImportTask fixture(String unique) {
        SourceOrganisation organisation = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + "-ORG BV", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(
                new ImportDefinition(organisation, unique + "-DEF", unique + " catalogus", "beheerder@example.test"));
        ImportDefinitionRevision revision = new ImportDefinitionRevision(definition, 1, IdentityProfileKind.THREE_PART,
                "beheerder@example.test");
        revision.setAccessConfigHash("1".repeat(64));
        revision.setStructureConfigHash("2".repeat(64));
        revision.setRecordRulesConfigHash("3".repeat(64));
        revision.setCompositeConfigHash("4".repeat(64));
        revision.setIdentitySupplierField("LEVERANCIER");
        revision.setIdentitySupplierGroupField("GROEP");
        revision.setIdentitySupplierReferenceField("REFERENTIE");
        revision.setStructureDelimiter(";");
        revision.setRecordBasePriceField("PRIJS");
        revision.setRecordDescriptionField("OMSCHRIJVING");
        revision.setMaxCriticalSharePercent(new BigDecimal("100"));
        revision.setStatus(RevisionStatus.ACTIVE);
        revisions.saveAndFlush(revision);
        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, "PSARF050"));
        return tasks.saveAndFlush(new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL));
    }
}
