package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
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
import be.dda.catalogimport.service.ActorIdentity;
import be.dda.catalogimport.service.SourceStateBaselineService;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Bouwstap 5A-5 (docs/design/fase5-auth-design.md par. 3, 4 en 7): de upload en {@code accept-baseline}
 * ondertekenen op de <b>geverifieerde identiteit</b>, net als freeze (5A-2) en de overige bundelacties (5A-4).
 *
 * <h2>Business rule vs. implementatie</h2>
 * <ul>
 *   <li><b>Regel:</b> wie uploadt of een nulmeting aanvaardt, tekent onder zijn eigen naam.
 *       <b>Implementatie:</b> {@code CurrentActor.signer(requestValue, veld)} in de controller, vóór de
 *       service; {@code uploadedBy} en {@code acceptedBy} zijn optioneel en enkel nog een controle.</li>
 *   <li><b>Regel:</b> een handtekening onder de verkeerde naam gebeurt nooit ongemerkt.
 *       <b>Implementatie:</b> afwijkende naam = 400 {@code ACTOR_FIELD_MISMATCH}, zonder nevenschrijfactie
 *       (niets gearchiveerd, geen levering/run/batch, bronstaat en batchstatus ongemoeid).</li>
 *   <li><b>Regel:</b> {@code system} tekent nooit. <b>Implementatie:</b> 403 {@code SYSTEM_ACTOR_FORBIDDEN}.</li>
 *   <li><b>Data:</b> naast {@code import_batch.created_by}/{@code baseline_accepted_by} komt het OIDC-subject in
 *       {@code created_by_subject}/{@code baseline_accepted_by_subject} (changeset 007-3); {@code NULL} = geen
 *       geverifieerde identiteit (directe Service-aanroep). Bulkkopieën en {@code task_run.triggered_by} houden
 *       enkel de naam.</li>
 *   <li><b>Uitzondering:</b> {@code POST /batches/{id}/continue} heeft geen actorveld en bewaart niets; ze vraagt
 *       enkel een bruikbare login (logregel).</li>
 * </ul>
 * Controlevolgorde: de actorcontrole gaat vóór de service, dus 400/403 komen vóór 404/409.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=50",
        "catalogimport.screening.mutation-chunk-size=50",
        "catalogimport.screening.recovery-on-startup=false"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class UploadBaselineActorHttpTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    private static final String UPLOADER = "jan.peeters@example.test";
    private static final String OTHER = "piet.willems@example.test";
    private static final String ACCEPTER = "an.janssens@example.test";
    private static final String TASKS = "/api/catalog-import/tasks";
    private static final String BATCHES = "/api/catalog-import/batches";

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
    private SourceStateBaselineService baseline;
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

    // --- POST /tasks/{id}/deliveries (uploadedBy) -----------------------------------------------------------

    @Test
    void uploadWithoutAnUploadedByStoresTheTokenUsernameAndSubject() throws Exception {
        CatalogImportTask task = fixture("NOFIELD");

        String body = upload(task, "REF-1", UPLOADER, null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdBySubject").doesNotExist())
                .andExpect(jsonPath("$.subject").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(subjectOf(UPLOADER));
        assertThat(createdBy(task)).containsExactly(UPLOADER, subjectOf(UPLOADER));
        // task_run.triggered_by is een kopie van de uploader (enkel de naam, geen subjectkolom).
        assertThat(jdbc.queryForObject("select triggered_by from task_run where task_id = ?", String.class,
                task.getId())).isEqualTo(UPLOADER);
    }

    @Test
    void uploadWithTheSameNameInAnotherCasingIsAcceptedAndTheTokenSpellingIsStored() throws Exception {
        CatalogImportTask task = fixture("CASE");

        upload(task, "REF-1", UPLOADER, "  " + UPLOADER.toUpperCase(Locale.ROOT) + " ")
                .andExpect(status().isCreated());

        assertThat(createdBy(task)).containsExactly(UPLOADER, subjectOf(UPLOADER));
    }

    @Test
    void uploadWithAnotherNameIs400AndNothingIsArchivedOrRegistered() throws Exception {
        CatalogImportTask task = fixture("MISMATCH");
        long archivedBefore = archivedFileCount();

        String body = upload(task, "REF-1", UPLOADER, OTHER)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACTOR_FIELD_MISMATCH"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("uploadedBy").doesNotContain(UPLOADER).doesNotContain(OTHER);
        assertNothingRegistered(task, archivedBefore);
    }

    @Test
    void uploadAsSystemIs403AndNothingIsArchivedOrRegistered() throws Exception {
        CatalogImportTask task = fixture("SYSTEM");
        long archivedBefore = archivedFileCount();

        upload(task, "REF-1", "system", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));
        upload(task, "REF-1", "SYSTEM", "SYSTEM")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));

        assertNothingRegistered(task, archivedBefore);
    }

    /** Een idempotente retry (200) bewaart niets nieuws: de oorspronkelijke uploader en diens subject blijven. */
    @Test
    void anIdempotentRetryByAnotherUserKeepsTheOriginalUploaderAndSubject() throws Exception {
        CatalogImportTask task = fixture("RETRY");
        upload(task, "REF-1", UPLOADER, null).andExpect(status().isCreated());

        upload(task, "REF-1", OTHER, null).andExpect(status().isOk());

        assertThat(createdBy(task)).containsExactly(UPLOADER, subjectOf(UPLOADER));
    }

    @Test
    void theActorCheckOnUploadRunsBeforeTheTaskLookup() throws Exception {
        long unknown = 999_999_999L;

        multipartUpload(unknown, "REF-1", UPLOADER, OTHER)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACTOR_FIELD_MISMATCH"));
        multipartUpload(unknown, "REF-1", "system", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));
        // Met een geldige identiteit blijft het gewoon een 404.
        multipartUpload(unknown, "REF-1", UPLOADER, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
    }

    // --- POST /batches/{id}/accept-baseline (acceptedBy) -------------------------------------------------------

    @Test
    void acceptWithoutAnAcceptedByStoresTheTokenUsernameAndSubject() throws Exception {
        long batchId = screenedBatch("ACC");

        accept(batchId, ACCEPTER, "{\"reason\":\"Nulmeting\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BASELINE_ACCEPTED"))
                .andExpect(jsonPath("$.acceptedBy").value(ACCEPTER))
                .andExpect(jsonPath("$.acceptedBySubject").doesNotExist())
                .andExpect(jsonPath("$.subject").doesNotExist());

        assertThat(acceptance(batchId)).containsExactly(ACCEPTER, subjectOf(ACCEPTER));
        // De bulkkopie draagt enkel de naam (geen subjectkolom): het subject staat op de batchrij.
        assertThat(jdbc.queryForList("select distinct accepted_by from catalog_source_state "
                + "where last_change_batch_id = ?", String.class, batchId)).containsExactly(ACCEPTER);
    }

    @Test
    void acceptWithTheSameNameInAnotherCasingIsAcceptedAndTheTokenSpellingIsStored() throws Exception {
        long batchId = screenedBatch("ACCCASE");

        accept(batchId, ACCEPTER, "{\"acceptedBy\":\"" + ACCEPTER.toUpperCase(Locale.ROOT)
                + "\",\"reason\":\"Nulmeting\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedBy").value(ACCEPTER));

        assertThat(acceptance(batchId)).containsExactly(ACCEPTER, subjectOf(ACCEPTER));
    }

    @Test
    void acceptWithAnotherNameIs400AndTheBatchStaysScreenedWithoutSourceState() throws Exception {
        long batchId = screenedBatch("ACCMIS");

        String body = accept(batchId, ACCEPTER, "{\"acceptedBy\":\"" + OTHER + "\",\"reason\":\"Nulmeting\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACTOR_FIELD_MISMATCH"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("acceptedBy").doesNotContain(ACCEPTER).doesNotContain(OTHER);
        assertBatchUntouched(batchId);
    }

    @Test
    void acceptAsSystemIs403AndTheBatchStaysScreened() throws Exception {
        long batchId = screenedBatch("ACCSYS");

        accept(batchId, "system", "{\"reason\":\"Nulmeting\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));

        assertBatchUntouched(batchId);
    }

    @Test
    void theActorCheckOnAcceptRunsBeforeTheBatchLookup() throws Exception {
        long unknown = 999_999_999L;

        accept(unknown, ACCEPTER, "{\"acceptedBy\":\"" + OTHER + "\",\"reason\":\"x\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACTOR_FIELD_MISMATCH"));
        accept(unknown, "system", "{\"reason\":\"x\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));
        accept(unknown, ACCEPTER, "{\"reason\":\"x\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BATCH_NOT_FOUND"));
    }

    // --- POST /batches/{id}/continue (geen actorveld, niets persistent) -------------------------------------

    @Test
    void continueHasNoActorFieldAndNeedsOnlyAUsableLogin() throws Exception {
        long unknown = 999_999_999L;

        // Sinds 5B-2 is hervatten MANAGE en beheert "system" nooit (ontwerp par. 3, stap 3): 403 vóór 404.
        mockMvc.perform(post(BATCHES + "/{id}/continue", unknown).with(as("system")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));
        mockMvc.perform(post(BATCHES + "/{id}/continue", unknown).with(as(ACCEPTER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BATCH_NOT_FOUND"));
        // Een onbruikbare identiteit (te lange naam) blijft 403 ACTOR_IDENTITY_INVALID.
        mockMvc.perform(post(BATCHES + "/{id}/continue", unknown).with(as("x".repeat(101))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTOR_IDENTITY_INVALID"));
    }

    // --- Directe Service-aanroep: NULL = geen geverifieerde identiteit ---------------------------------------

    @Test
    void aDirectServiceCallStoresTheNameWithoutASubject() throws Exception {
        long batchId = screenedBatch("DIRECT");

        baseline.acceptBaseline(batchId, ACCEPTER, "Nulmeting");

        assertThat(acceptance(batchId)).containsExactly(ACCEPTER, null);
    }

    @Test
    void anExplicitIdentityStoresItsSubjectViaTheServiceOverload() throws Exception {
        long batchId = screenedBatch("IDENT");

        baseline.acceptBaseline(batchId, new ActorIdentity(ACCEPTER, "sub-explicit"), "Nulmeting");

        assertThat(acceptance(batchId)).containsExactly(ACCEPTER, "sub-explicit");
    }

    // --- Helpers ----------------------------------------------------------------------------------------------

    private ResultActions accept(long batchId, String signedInAs, String json) throws Exception {
        return mockMvc.perform(post(BATCHES + "/{id}/accept-baseline", batchId).with(as(signedInAs))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions upload(CatalogImportTask task, String reference, String signedInAs, String uploadedBy)
            throws Exception {
        return multipartUpload(task.getId(), reference, signedInAs, uploadedBy);
    }

    private ResultActions multipartUpload(long taskId, String reference, String signedInAs, String uploadedBy)
            throws Exception {
        MockHttpServletRequestBuilder request = multipart(TASKS + "/{id}/deliveries", taskId)
                .file(new MockMultipartFile("file", "levering.csv", "text/csv", csv()))
                .param("deliveryReference", reference)
                .with(as(signedInAs));
        if (uploadedBy != null) {
            request = request.param("uploadedBy", uploadedBy);
        }
        return mockMvc.perform(request);
    }

    private static byte[] csv() {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int index = 1; index <= 3; index++) {
            csv.append("ACME;G1;R").append(index).append(";1").append(index).append(",00;Artikel ")
                    .append(index).append('\n');
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Een geüploade, gescreende batch (status SCREENED) klaar voor accept-baseline. */
    private long screenedBatch(String prefix) throws Exception {
        CatalogImportTask task = fixture(prefix);
        String body = upload(task, "REF-1", UPLOADER, null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SCREENED"))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.batchId")).longValue();
    }

    private static String subjectOf(String username) {
        return "test-sub-" + username;
    }

    /** created_by, created_by_subject van de enige batch van de taak. */
    private List<String> createdBy(CatalogImportTask task) {
        Map<String, Object> row = jdbc.queryForMap("select created_by, created_by_subject from import_batch "
                + "where import_link_id = ?", task.getImportLink().getId());
        return Arrays.asList((String) row.get("created_by"), (String) row.get("created_by_subject"));
    }

    /** baseline_accepted_by, baseline_accepted_by_subject van de batch. */
    private List<String> acceptance(long batchId) {
        Map<String, Object> row = jdbc.queryForMap("select baseline_accepted_by, baseline_accepted_by_subject "
                + "from import_batch where id = ?", batchId);
        return Arrays.asList((String) row.get("baseline_accepted_by"),
                (String) row.get("baseline_accepted_by_subject"));
    }

    private void assertBatchUntouched(long batchId) {
        Map<String, Object> row = jdbc.queryForMap("select status, baseline_accepted_by, "
                + "baseline_accepted_by_subject, baseline_accepted_at from import_batch where id = ?", batchId);
        assertThat(row.get("status")).isEqualTo("SCREENED");
        assertThat(row.get("baseline_accepted_by")).isNull();
        assertThat(row.get("baseline_accepted_by_subject")).isNull();
        assertThat(row.get("baseline_accepted_at")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from catalog_source_state where last_change_batch_id = ?",
                Long.class, batchId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from import_mutation where batch_id = ? "
                + "and status = 'SKIPPED'", Long.class, batchId)).isZero();
    }

    private void assertNothingRegistered(CatalogImportTask task, long archivedBefore) throws Exception {
        long linkId = task.getImportLink().getId();
        assertThat(jdbc.queryForObject("select count(*) from import_batch where import_link_id = ?", Long.class,
                linkId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from task_run where task_id = ?", Long.class,
                task.getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from delivery where task_id = ?", Long.class,
                task.getId())).isZero();
        assertThat(archivedFileCount()).isEqualTo(archivedBefore);
    }

    private long archivedFileCount() throws Exception {
        try (var walk = Files.walk(archiveRoot)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    private CatalogImportTask fixture(String prefix) {
        String unique = "UA" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
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
