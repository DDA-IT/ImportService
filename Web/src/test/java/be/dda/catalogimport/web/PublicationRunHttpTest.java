package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static be.dda.catalogimport.testsupport.TestActors.withoutPermissions;
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
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Het HTTP-contract van de publicatieruns (bouwstap 5P-8, {@code docs/design/fase5-pub-design.md} par. 4),
 * end-to-end via MockMvc. Vereist een draaiende database (profiel local).
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=50",
        "catalogimport.screening.mutation-chunk-size=50",
        "catalogimport.screening.recovery-on-startup=false"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class PublicationRunHttpTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String API = "/api/catalog-import";
    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    private static final String CREATOR = "jan.peeters@example.test";
    private static final String DECIDER = "piet.willems@example.test";
    private static final String FREEZER = "an.janssens@example.test";
    private static final String USER = "els.maes@example.test";
    private static final String SIMULATION = "{\"targetMode\":\"SIMULATION\"}";

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

    @Test
    void approveRequestsASimulatedRunWithoutLeakingSubjectOrPath() throws Exception {
        long bundleId = frozenBundle("OK", 2);

        String body = run(bundleId, as(USER, Permission.APPROVE), SIMULATION).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SIMULATED"))
                .andExpect(jsonPath("$.simulationOnly").value(true))
                .andExpect(jsonPath("$.writesToProdis").value(false))
                .andExpect(jsonPath("$.contractStatus").value("UNVERIFIED_FIELD_INVENTORY"))
                .andExpect(jsonPath("$.previewSpecVersion").isString())
                .andExpect(jsonPath("$.requestedBy").value(USER))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("requestedBySubject").doesNotContain("subject")
                .doesNotContain("artifactReference").doesNotContain("artifact_reference")
                .doesNotContain(archiveRoot.toString().replace("\\", "\\\\")).doesNotContain(archiveRoot.toString());
        long runId = ((Number) JsonPath.read(body, "$.id")).longValue();
        assertThat(jdbc.queryForObject("select requested_by from publication_run where id = ?", String.class, runId))
                .isEqualTo(USER);
        assertThat(jdbc.queryForObject("select requested_by_subject from publication_run where id = ?",
                String.class, runId)).isEqualTo("test-sub-" + USER);
    }

    @Test
    void withoutApproveIsA403AndNoRunRowIsWritten() throws Exception {
        long bundleId = frozenBundle("DENY", 1);

        run(bundleId, as(USER, Permission.READ), SIMULATION).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        run(bundleId, as(USER, Permission.MANAGE), SIMULATION).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        run(bundleId, withoutPermissions(USER), SIMULATION).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        assertThat(runCount(bundleId)).isZero();
    }

    @Test
    void systemIsForbiddenToRequestARun() throws Exception {
        long bundleId = frozenBundle("SYS", 1);

        run(bundleId, withoutPermissions("system"), SIMULATION).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));
        assertThat(runCount(bundleId)).isZero();
    }

    @Test
    void missingOrUnknownTargetModeIs400() throws Exception {
        long bundleId = frozenBundle("MODE", 1);

        run(bundleId, as(USER), "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PUBLICATION_MODE_REQUIRED"));
        run(bundleId, as(USER), "{\"targetMode\":\"NOPE\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PUBLICATION_MODE_UNKNOWN"));
        assertThat(runCount(bundleId)).isZero();
    }

    @Test
    void nonSimulationModesAreNotEnabledAndPermissionStillComesFirst() throws Exception {
        long bundleId = frozenBundle("NOTEN", 1);

        for (String mode : new String[] {"TRIAL_LIBRARY", "PRODUCTION"}) {
            String content = "{\"targetMode\":\"" + mode + "\"}";
            run(bundleId, as(USER), content).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PUBLICATION_MODE_NOT_ENABLED"));
            run(999_999_999L, as(USER), content).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PUBLICATION_MODE_NOT_ENABLED"));
            run(bundleId, as(USER, Permission.READ), content).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
        assertThat(runCount(bundleId)).isZero();
    }

    @Test
    void anUnknownBundleIs404AndANotFrozenBundleIs409() throws Exception {
        run(999_999_999L, as(USER), SIMULATION).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BUNDLE_NOT_FOUND"));

        long assembling = createBundle();
        run(assembling, as(USER), SIMULATION).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUNDLE_NOT_FROZEN"));
    }

    @Test
    void listAndGetNeedReadAndUnknownRunIs404() throws Exception {
        long bundleId = frozenBundle("LIST", 1);
        String body = run(bundleId, as(USER), SIMULATION).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long runId = ((Number) JsonPath.read(body, "$.id")).longValue();

        mockMvc.perform(get(API + "/bundles/{id}/publication-runs", bundleId).with(as(USER, Permission.READ)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(runId))
                .andExpect(jsonPath("$[0].simulationOnly").value(true));
        mockMvc.perform(get(API + "/publication-runs/{id}", runId).with(as(USER, Permission.READ)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(runId))
                .andExpect(jsonPath("$.writesToProdis").value(false));

        mockMvc.perform(get(API + "/bundles/{id}/publication-runs", bundleId).with(withoutPermissions(USER)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        mockMvc.perform(get(API + "/publication-runs/{id}", runId).with(withoutPermissions(USER)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        mockMvc.perform(get(API + "/publication-runs/{id}/artifact", runId).with(withoutPermissions(USER)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));

        mockMvc.perform(get(API + "/publication-runs/{id}", 999_999_999L).with(as(USER, Permission.READ)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PUBLICATION_RUN_NOT_FOUND"));
        mockMvc.perform(get(API + "/publication-runs/{id}/artifact", 999_999_999L).with(as(USER, Permission.READ)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PUBLICATION_RUN_NOT_FOUND"));
    }

    @Test
    void theArtifactIsStreamedAsCsvWithTheStoredChecksum() throws Exception {
        long bundleId = frozenBundle("ART", 2);
        String body = run(bundleId, as(USER), SIMULATION).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long runId = ((Number) JsonPath.read(body, "$.id")).longValue();

        MockHttpServletResponse response = mockMvc.perform(get(API + "/publication-runs/{id}/artifact", runId)
                        .with(as(USER, Permission.READ)))
                .andExpect(status().isOk()).andReturn().getResponse();

        assertThat(response.getContentType()).startsWith("text/csv");
        assertThat(response.getHeader("Content-Disposition"))
                .isEqualTo("attachment; filename=\"psimport-simulation-run-" + runId + ".csv\"");
        byte[] bytes = response.getContentAsByteArray();
        String csv = new String(bytes, StandardCharsets.UTF_8);
        assertThat(csv).startsWith("# PREVIEW");
        assertThat(csv.split("\n")[0]).contains("simulationOnly=true").contains("writesToProdis=false");
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        assertThat(sha).isEqualTo(jdbc.queryForObject("select artifact_sha256 from publication_run where id = ?",
                String.class, runId));
    }

    @Test
    void aFailedRunHasNoArtifactAndIsA409() throws Exception {
        long bundleId = frozenBundle("FAIL", 1);
        jdbc.update("insert into publication_run (bundle_id, target_mode, attempt, status, requested_by, "
                + "requested_at, finished_at, bundle_content_hash, failure_code, idempotency_key, active_marker) "
                + "select id, 'SIMULATION', 99, 'FAILED', 'tester', now(), now(), content_hash, 'TEST_FAILURE', ?, null "
                + "from publication_bundle where id = ?", "TEST-FAILED-" + System.nanoTime(), bundleId);
        long runId = jdbc.queryForObject("select id from publication_run where bundle_id = ?", Long.class, bundleId);

        mockMvc.perform(get(API + "/publication-runs/{id}/artifact", runId).with(as(USER, Permission.READ)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PUBLICATION_RUN_ARTIFACT_NOT_AVAILABLE"));
    }

    @Test
    void cancellingTheBundleAfterARunIsStillPossible() throws Exception {
        long bundleId = frozenBundle("CANCEL", 1);
        run(bundleId, as(USER), SIMULATION).andExpect(status().isOk());

        mockMvc.perform(post(API + "/bundles/{id}/cancel", bundleId).with(as(FREEZER, Permission.APPROVE))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Niet meer nodig\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    // --- Helpers ---------------------------------------------------------------------------------------

    private ResultActions run(long bundleId, org.springframework.test.web.servlet.request.RequestPostProcessor login,
                              String content) throws Exception {
        MockHttpServletRequestBuilder request = post(API + "/bundles/{id}/publication-runs", bundleId)
                .with(login).contentType(MediaType.APPLICATION_JSON).content(content);
        return mockMvc.perform(request);
    }

    private int runCount(long bundleId) {
        return jdbc.queryForObject("select count(*) from publication_run where bundle_id = ?", Integer.class,
                bundleId);
    }

    private long createBundle() throws Exception {
        String reference = "BND-RUN-" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet();
        String body = mockMvc.perform(post(API + "/bundles").with(as(CREATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bundleReference\":\"" + reference + "\",\"targetMode\":\"SIMULATION\","
                                + "\"createdBy\":\"" + CREATOR + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    /** Upload met {@code rowCount} regels, bundel, alles goedgekeurd, bevroren; geeft het bundel-id. */
    private long frozenBundle(String prefix, int rowCount) throws Exception {
        Fixture f = fixture(prefix);
        StringBuilder csv = new StringBuilder(HEADER);
        for (int index = 0; index < rowCount; index++) {
            csv.append("ACME;G1;R").append(index + 1).append(";1,").append(String.format("%02d", index))
                    .append(";Artikel ").append(index + 1).append('\n');
        }
        String upload = mockMvc.perform(multipart(API + "/tasks/{id}/deliveries", f.taskId())
                        .file(new MockMultipartFile("file", "levering.csv", "text/csv",
                                csv.toString().getBytes(StandardCharsets.UTF_8)))
                        .param("deliveryReference", "REF-" + f.unique())
                        .param("uploadedBy", "tester@example.test").with(as("tester@example.test")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("SCREENED"))
                .andReturn().getResponse().getContentAsString();
        long batchId = ((Number) JsonPath.read(upload, "$.batchId")).longValue();
        jdbc.update("update import_mutation set base_price_currency = 'EUR' where batch_id = ? "
                + "and action_type in ('CREATE', 'UPDATE')", batchId);
        long bundleId = createBundle();
        mockMvc.perform(post(API + "/bundles/{id}/batches", bundleId).with(as(CREATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchIds\":[" + batchId + "],\"addedBy\":\"" + CREATOR + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post(API + "/bundles/{id}/decisions", bundleId).with(as(DECIDER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisionKind\":\"APPROVE\",\"decidedBy\":\"" + DECIDER + "\","
                                + "\"reason\":\"Nagekeken\",\"filter\":{\"batchId\":" + batchId + "}}"))
                .andExpect(status().isOk());
        mockMvc.perform(post(API + "/bundles/{id}/freeze", bundleId).with(as(FREEZER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"frozenBy\":\"" + FREEZER + "\",\"reason\":\"Run test\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FROZEN"));
        return bundleId;
    }

    private Fixture fixture(String prefix) {
        String unique = "PR" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
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
        CatalogImportTask task = tasks.saveAndFlush(new CatalogImportTask(link, unique + "-taak",
                TaskTriggerType.MANUAL));
        return new Fixture(task.getId(), unique);
    }

    private record Fixture(long taskId, String unique) {
    }
}
