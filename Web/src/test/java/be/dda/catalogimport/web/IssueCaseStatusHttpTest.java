package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static be.dda.catalogimport.testsupport.TestActors.withoutPermissions;
import static org.assertj.core.api.Assertions.assertThat;
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
import be.dda.catalogimport.service.DeliveryIntakeService;
import be.dda.catalogimport.service.DeliveryScreeningService;
import be.dda.catalogimport.service.DeliveryView;
import be.dda.catalogimport.service.IssueCaseSyncService;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Bouwstap S2-B2 (docs/design/issue-case-design.md par. 4): de menselijke statuswijziging op een
 * behandelgeval, via {@code POST /api/catalog-import/issue-cases/{caseId}/status}.
 * <p>
 * Elke test bouwt zijn eigen behandelgeval via de echte intake-/screeningsdienst en
 * {@link IssueCaseSyncService} (patroon {@code IssueCaseSyncTest}, S2-B1b) en spreekt daarna alleen
 * het HTTP-endpoint aan.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=25",
        "catalogimport.screening.mutation-chunk-size=25",
        "catalogimport.screening.recovery-on-startup=false"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class IssueCaseStatusHttpTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    /** Boven {@code IssueAggregationService.GROUP_MIN_OCCURRENCES}: anders bestaat er geen groep/geval. */
    private static final int REPEATS = 12;
    private static final String API = "/api/catalog-import/issue-cases";
    private static final String USER = "an.janssens@example.test";

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DeliveryIntakeService intake;
    @Autowired
    private DeliveryScreeningService screening;
    @Autowired
    private IssueCaseSyncService issueCaseSync;
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
    private JdbcTemplate jdbc;

    // --- Succesvolle overgangen -------------------------------------------------------------------

    @Test
    void correctsAnAwaitingCaseAndRecordsReasonActorTimestampAndDecisionRevision() throws Exception {
        Fixture fixture = fixture("CORR");
        long caseId = createCase(fixture);

        json(caseId, "CORRECTED", "AWAITING_REVIEW", "Prijs manueel gecorrigeerd", USER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CORRECTED"))
                .andExpect(jsonPath("$.statusReason").value("Prijs manueel gecorrigeerd"))
                .andExpect(jsonPath("$.statusChangedBy").value(USER))
                .andExpect(jsonPath("$.decisionRevisionId").value(fixture.revisionId()));

        Map<String, Object> row = caseRow(caseId);
        assertThat(row.get("status")).isEqualTo("CORRECTED");
        assertThat(row.get("status_reason")).isEqualTo("Prijs manueel gecorrigeerd");
        assertThat(row.get("status_changed_by")).isEqualTo(USER);
        assertThat(row.get("status_changed_at")).isNotNull();
        assertThat(number(row, "decision_revision_id")).isEqualTo(fixture.revisionId());
        List<Map<String, Object>> history = events(caseId);
        assertThat(history).hasSize(2);
        assertThat(history.get(1).get("event_kind")).isEqualTo("STATUS_CHANGE");
        assertThat(history.get(1).get("source")).isEqualTo("HUMAN");
        assertThat(history.get(1).get("changed_by")).isEqualTo(USER);
        assertThat(history.get(1).get("previous_status")).isEqualTo("AWAITING_REVIEW");
        assertThat(history.get(1).get("new_status")).isEqualTo("CORRECTED");
        assertThat(history.get(1).get("observation_batch_id")).isNull();
    }

    @Test
    void rejectsAnAwaitingCase() throws Exception {
        Fixture fixture = fixture("REJ");
        long caseId = createCase(fixture);

        json(caseId, "REJECTED", "AWAITING_REVIEW", "Bewust zo geleverd", USER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(caseRow(caseId).get("status")).isEqualTo("REJECTED");
    }

    // --- Handmatig heropenen -----------------------------------------------------------------------

    @Test
    void manuallyReopensACorrectedCase() throws Exception {
        Fixture fixture = fixture("REOPC");
        long caseId = createCase(fixture);
        json(caseId, "CORRECTED", "AWAITING_REVIEW", "Eerste keer", USER).andExpect(status().isOk());

        json(caseId, "AWAITING_REVIEW", "CORRECTED", "Toch niet juist, heropend", USER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_REVIEW"));
    }

    @Test
    void manuallyReopensARejectedCase() throws Exception {
        Fixture fixture = fixture("REOPR");
        long caseId = createCase(fixture);
        json(caseId, "REJECTED", "AWAITING_REVIEW", "Eerste keer", USER).andExpect(status().isOk());

        json(caseId, "AWAITING_REVIEW", "REJECTED", "Toch heropend", USER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_REVIEW"));
    }

    /** {@code AUTO_RESOLVED} wordt door geen enkel codepad gezet (A1); rechtstreeks via JDBC klaargezet. */
    @Test
    void manuallyReopensAnAutoResolvedCase() throws Exception {
        Fixture fixture = fixture("REOPA");
        long caseId = createCase(fixture);
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("update issue_case set status = 'AUTO_RESOLVED', status_reason = 'test', "
                + "status_changed_at = ? where id = ?", now, caseId);

        json(caseId, "AWAITING_REVIEW", "AUTO_RESOLVED", "Heropend vanuit auto-resolved", USER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_REVIEW"));
    }

    // --- Niet-toegestane overgangen -----------------------------------------------------------------

    @Test
    void refusesADirectRevisionFromCorrectedToRejected() throws Exception {
        Fixture fixture = fixture("DIRECT");
        long caseId = createCase(fixture);
        json(caseId, "CORRECTED", "AWAITING_REVIEW", "Eerst gecorrigeerd", USER).andExpect(status().isOk());

        json(caseId, "REJECTED", "CORRECTED", "Rechtstreeks omgekeerd", USER)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ISSUE_CASE_TRANSITION_NOT_ALLOWED"));
        assertThat(caseRow(caseId).get("status")).isEqualTo("CORRECTED");
    }

    @Test
    void refusesTheSameStatusToItself() throws Exception {
        Fixture fixture = fixture("SAME");
        long caseId = createCase(fixture);

        json(caseId, "AWAITING_REVIEW", "AWAITING_REVIEW", "Niets veranderd", USER)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ISSUE_CASE_TRANSITION_NOT_ALLOWED"));
    }

    @Test
    void refusesAnyTransitionToAutoResolved() throws Exception {
        Fixture fixture = fixture("TOAUTO");
        long caseId = createCase(fixture);

        json(caseId, "AUTO_RESOLVED", "AWAITING_REVIEW", "Poging", USER)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ISSUE_CASE_TRANSITION_NOT_ALLOWED"));
    }

    // --- Validatie -----------------------------------------------------------------------------------

    @Test
    void refusesAMissingReason() throws Exception {
        Fixture fixture = fixture("NOREASON");
        long caseId = createCase(fixture);

        json(caseId, "CORRECTED", "AWAITING_REVIEW", "  ", USER)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ISSUE_CASE_REASON_REQUIRED"));
        assertThat(caseRow(caseId).get("status")).isEqualTo("AWAITING_REVIEW");
    }

    @Test
    void refusesAMissingNewStatus() throws Exception {
        Fixture fixture = fixture("NOSTATUS");
        long caseId = createCase(fixture);

        mockMvc.perform(post(API + "/{id}/status", caseId).with(as(USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedStatus\":\"AWAITING_REVIEW\",\"reason\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ISSUE_CASE_STATUS_REQUIRED"));
    }

    @Test
    void refusesAnUnknownNewStatus() throws Exception {
        Fixture fixture = fixture("UNKSTATUS");
        long caseId = createCase(fixture);

        json(caseId, "NOT_A_REAL_STATUS", "AWAITING_REVIEW", "x", USER)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ISSUE_CASE_STATUS_UNKNOWN"));
    }

    @Test
    void refusesAWrongExpectedStatus() throws Exception {
        Fixture fixture = fixture("EXP");
        long caseId = createCase(fixture);

        json(caseId, "CORRECTED", "REJECTED", "x", USER)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ISSUE_CASE_STATUS_CHANGED"));
        assertThat(caseRow(caseId).get("status")).isEqualTo("AWAITING_REVIEW");
    }

    @Test
    void refusesAnUnknownCase() throws Exception {
        json(999_999_999L, "CORRECTED", "AWAITING_REVIEW", "x", USER)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ISSUE_CASE_NOT_FOUND"));
    }

    @Test
    void refusesWithoutManagePermission() throws Exception {
        Fixture fixture = fixture("PERM");
        long caseId = createCase(fixture);

        mockMvc.perform(post(API + "/{id}/status", caseId).with(withoutPermissions(USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("CORRECTED", "AWAITING_REVIEW", "x", USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        assertThat(caseRow(caseId).get("status")).isEqualTo("AWAITING_REVIEW");
    }

    // --- D3: geen enkel effect op de screening ---------------------------------------------------

    /**
     * D3 (ontwerp par. 4, laatste punt): de statuswijziging is administratief en raakt
     * {@code import_batch} (status, {@code validation_result}, élke teller) en élke
     * {@code import_mutation.status} niet aan — bewezen voor zowel {@code CORRECTED} als
     * {@code REJECTED} (verplichte testcase design par. 4).
     */
    @Test
    void leavesTheBatchAndEveryMutationStatusUntouchedAfterCorrectedAndRejected() throws Exception {
        Fixture fixture = fixture("D3");
        long batchId = screen(fixture, "REF-1", unreadablePrices(1));
        issueCaseSync.sync(batchId, fixture.linkId(), fixture.revisionId());
        long caseId = number(singleCase(fixture), "id");
        List<Map<String, String>> batchBefore = batchRow(batchId);
        List<Map<String, String>> mutationsBefore = mutationRows(batchId);
        assertThat(mutationsBefore).isNotEmpty();

        json(caseId, "CORRECTED", "AWAITING_REVIEW", "D3-corrected", USER).andExpect(status().isOk());
        assertThat(batchRow(batchId)).isEqualTo(batchBefore);
        assertThat(mutationRows(batchId)).isEqualTo(mutationsBefore);

        json(caseId, "AWAITING_REVIEW", "CORRECTED", "heropend voor rejected-test", USER)
                .andExpect(status().isOk());
        json(caseId, "REJECTED", "AWAITING_REVIEW", "D3-rejected", USER).andExpect(status().isOk());
        assertThat(batchRow(batchId)).isEqualTo(batchBefore);
        assertThat(mutationRows(batchId)).isEqualTo(mutationsBefore);
    }

    // --- Helpers ---------------------------------------------------------------------------------

    private ResultActions json(long caseId, String newStatus, String expectedStatus, String reason,
                               String changedBy) throws Exception {
        return mockMvc.perform(post(API + "/{id}/status", caseId).with(as(USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(newStatus, expectedStatus, reason, changedBy)));
    }

    private static String body(String newStatus, String expectedStatus, String reason, String changedBy) {
        return "{\"newStatus\":" + quote(newStatus) + ",\"expectedStatus\":" + quote(expectedStatus)
                + ",\"reason\":" + quote(reason) + ",\"changedBy\":" + quote(changedBy) + "}";
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value.replace("\"", "\\\"") + "\"";
    }

    /** Screent een minimale levering en synchroniseert haar meteen naar één behandelgeval. */
    private long createCase(Fixture fixture) {
        long batchId = screen(fixture, "REF-1", unreadablePrices(1));
        issueCaseSync.sync(batchId, fixture.linkId(), fixture.revisionId());
        return number(singleCase(fixture), "id");
    }

    private long screen(Fixture fixture, String reference, String csv) {
        DeliveryView view = intake.intake(fixture.taskId(), reference, "tester@example.test", null, null,
                "levering.csv", new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))).delivery();
        return screening.screen(view.batch().batchId()).batchId();
    }

    private static String unreadablePrices(int delivery) {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= REPEATS; i++) {
            csv.append("ACME;G1;R").append(i).append(";onleesbaar;Boormachine-").append(delivery)
                    .append('\n');
        }
        for (int i = REPEATS + 1; i <= REPEATS + 5; i++) {
            csv.append("ACME;G1;R").append(i).append(";1,50;Hamer-").append(delivery).append('\n');
        }
        return csv.toString();
    }

    private Map<String, Object> caseRow(long caseId) {
        return jdbc.queryForMap("select status, status_reason, status_changed_at, status_changed_by, "
                + "decision_revision_id from issue_case where id = ?", caseId);
    }

    private Map<String, Object> singleCase(Fixture fixture) {
        List<Map<String, Object>> found = jdbc.queryForList(
                "select id from issue_case where import_link_id = ?", fixture.linkId());
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    private List<Map<String, Object>> events(long caseId) {
        return jdbc.queryForList("select event_kind, previous_status, new_status, source, changed_by, "
                + "observation_batch_id from issue_case_event where issue_case_id = ? order by id", caseId);
    }

    private List<Map<String, String>> batchRow(long batchId) {
        return snapshot("select status, validation_result, blocked_code, blocked_reason, "
                + "raw_record_count, valid_record_count, rejected_record_count, duplicate_identity_count, "
                + "new_count, changed_count, unchanged_count, content_mutation_count, staged_row_count, "
                + "mutation_progress_row_number, bulk_incident_count, critical_issue_count, warning_count, "
                + "critical_line_count, identity_incident_count, awaiting_approval_count, "
                + "creation_candidate_count, creation_scope_count, creation_outcome, filtered_out_count, "
                + "error_before_filter_count from import_batch where id = ?", batchId);
    }

    private List<Map<String, String>> mutationRows(long batchId) {
        return snapshot("select id, status, status_reason, action_type, target_domain, issue_group_id "
                + "from import_mutation where batch_id = ? order by id", batchId);
    }

    private List<Map<String, String>> snapshot(String sql, Object... arguments) {
        return jdbc.queryForList(sql, arguments).stream().map(row -> {
            Map<String, String> copy = new LinkedHashMap<String, String>();
            row.forEach((column, value) ->
                    copy.put(column.toLowerCase(Locale.ROOT), String.valueOf(value)));
            return copy;
        }).toList();
    }

    private static long number(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    private static ImportDefinitionRevision newRevision(ImportDefinition definition, int revisionNumber) {
        ImportDefinitionRevision revision = new ImportDefinitionRevision(definition, revisionNumber,
                IdentityProfileKind.THREE_PART, "beheerder@example.test");
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
        // Deze test gaat niet over de leveringsdrempel (patroon IssueGroupingTest).
        revision.setMaxCriticalSharePercent(new BigDecimal("100"));
        return revision;
    }

    private Fixture fixture(String prefix) {
        String unique = "ICS" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
        SourceOrganisation organisation = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + "-ORG BV", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(new ImportDefinition(organisation,
                unique + "-DEF", unique + " catalogus", "beheerder@example.test"));
        ImportDefinitionRevision revision = newRevision(definition, 1);
        revision.setStatus(RevisionStatus.ACTIVE);
        ImportDefinitionRevision stored = revisions.saveAndFlush(revision);
        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        String libraryCode = unique.substring(0, Math.min(unique.length(), 20));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, libraryCode));
        CatalogImportTask task = tasks.saveAndFlush(
                new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL));
        return new Fixture(task.getId(), link.getId(), stored.getId(), definition.getId());
    }

    private record Fixture(long taskId, long linkId, long revisionId, long definitionId) {
    }
}
