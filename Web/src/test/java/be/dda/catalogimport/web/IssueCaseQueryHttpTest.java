package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import be.dda.catalogimport.service.support.CandidateNormaliser;
import be.dda.catalogimport.service.support.ImportValueRules;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
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

/**
 * Bouwstap S2-B3 (docs/design/issue-case-design.md §6): de vijf leesendpoints van het behandelgeval.
 * Tegen de echte services, DAO's, het archief en de echte database; het geval zelf ontstaat via de
 * echte intake-/screeningsdienst (het S2-B1b-hookpunt is al in {@code DeliveryScreeningService}
 * verweven — geen handmatige synchronisatie nodig, patroon {@code IssueCaseSyncTest}/
 * {@code IssueCaseStatusHttpTest}).
 * <p>
 * <b>Ontwerpkeuze (gemotiveerd, geen §6-architectuurvraag):</b> {@code hasUnreviewedRecurrence} staat
 * op {@code true} zolang een geval nog nooit beoordeeld is ({@code statusChangedAt == null}) — zie de
 * javadoc van {@code IssueCaseQueryService.IssueCaseRow}. De ontwerptekst zelf legt enkel de formule
 * {@code last_seen_at > status_changed_at} vast voor een geval dat al een beslissing draagt.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=25",
        "catalogimport.screening.mutation-chunk-size=25",
        "catalogimport.screening.recovery-on-startup=false"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class IssueCaseQueryHttpTest {

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

    // --- Lijst: zonder filter -----------------------------------------------------------------------

    @Test
    void listsAllCasesOfTheLinkWithoutFilter() throws Exception {
        Fixture fixture = fixture("NOFILTER");
        screen(fixture, "REF-1", twoIssueCodes());

        mockMvc.perform(get(API).param("importLinkId", String.valueOf(fixture.linkId())).with(as(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    // --- Lijst: elk filter apart ---------------------------------------------------------------------

    @Test
    void filtersByImportLinkId() throws Exception {
        Fixture fixtureA = fixture("LINKA");
        Fixture fixtureB = fixture("LINKB");
        screen(fixtureA, "REF-1", unreadablePrices(1));
        screen(fixtureB, "REF-1", unreadablePrices(1));

        mockMvc.perform(get(API).param("importLinkId", String.valueOf(fixtureA.linkId())).with(as(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].importLinkId").value(fixtureA.linkId()));
    }

    @Test
    void filtersByStatus() throws Exception {
        Fixture fixture = fixture("STATUS");
        screen(fixture, "REF-1", twoIssueCodes());
        long priceCaseId = caseIdOf(fixture, ImportValueRules.CODE_PRICE_UNREADABLE);
        decide(priceCaseId, "CORRECTED", "AWAITING_REVIEW", "gecorrigeerd");

        mockMvc.perform(get(API).param("importLinkId", String.valueOf(fixture.linkId()))
                        .param("status", "AWAITING_REVIEW").with(as(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].issueCode")
                        .value(CandidateNormaliser.CODE_IDENTITY_COMPONENT_EMPTY));

        mockMvc.perform(get(API).param("importLinkId", String.valueOf(fixture.linkId()))
                        .param("status", "CORRECTED").with(as(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].issueCode").value(ImportValueRules.CODE_PRICE_UNREADABLE));
    }

    @Test
    void filtersBySeverity() throws Exception {
        Fixture fixture = fixture("SEVERITY");
        screen(fixture, "REF-1", twoIssueCodes());

        mockMvc.perform(get(API).param("importLinkId", String.valueOf(fixture.linkId()))
                        .param("severity", "ERROR").with(as(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get(API).param("importLinkId", String.valueOf(fixture.linkId()))
                        .param("severity", "CRITICAL").with(as(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void filtersByIssueCode() throws Exception {
        Fixture fixture = fixture("ISSUECODE");
        screen(fixture, "REF-1", twoIssueCodes());

        mockMvc.perform(get(API).param("importLinkId", String.valueOf(fixture.linkId()))
                        .param("issueCode", ImportValueRules.CODE_PRICE_UNREADABLE).with(as(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].issueCode").value(ImportValueRules.CODE_PRICE_UNREADABLE));
    }

    /**
     * De opgeslagen tijden zijn Java-kant {@code Instant.now()} (via {@code IssueCaseSyncService}, niet
     * databaseklok), dus vergelijken met ruime marges rond {@link Instant#now()} hier is veilig: geen
     * afhankelijkheid van klokverschil tussen testproces en database.
     */
    @Test
    void filtersByLastSeenRange() throws Exception {
        Fixture fixture = fixture("LASTSEEN");
        Instant farPast = Instant.now().minusSeconds(300);
        Instant farFuture = Instant.now().plusSeconds(300);
        screen(fixture, "REF-1", unreadablePrices(1));

        mockMvc.perform(get(API).param("importLinkId", String.valueOf(fixture.linkId()))
                        .param("lastSeenFrom", farPast.toString()).with(as(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get(API).param("importLinkId", String.valueOf(fixture.linkId()))
                        .param("lastSeenFrom", farFuture.toString()).with(as(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get(API).param("importLinkId", String.valueOf(fixture.linkId()))
                        .param("lastSeenTo", farPast.toString()).with(as(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get(API).param("importLinkId", String.valueOf(fixture.linkId()))
                        .param("lastSeenTo", farFuture.toString()).with(as(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void sortsByLastSeenAtDescThenIdDesc() throws Exception {
        Fixture fixture = fixture("SORT");
        screen(fixture, "REF-1", unreadablePrices(1));
        Thread.sleep(50);
        screen(fixture, "REF-2", identityIssues());

        mockMvc.perform(get(API).param("importLinkId", String.valueOf(fixture.linkId())).with(as(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].issueCode")
                        .value(CandidateNormaliser.CODE_IDENTITY_COMPONENT_EMPTY))
                .andExpect(jsonPath("$.content[1].issueCode").value(ImportValueRules.CODE_PRICE_UNREADABLE));
    }

    // --- hasUnreviewedRecurrence ----------------------------------------------------------------------

    @Test
    void hasUnreviewedRecurrenceIsTrueForACaseWithoutAnyDecisionYet() throws Exception {
        Fixture fixture = fixture("NODECISION");
        long caseId = createSingleCase(fixture, "REF-1");

        mockMvc.perform(get(API + "/{id}", caseId).with(as(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_REVIEW"))
                .andExpect(jsonPath("$.hasUnreviewedRecurrence").value(true));
    }

    @Test
    void hasUnreviewedRecurrenceIsTrueForARejectedCaseWithALaterObservation() throws Exception {
        Fixture fixture = fixture("REJTRUE");
        long caseId = createSingleCase(fixture, "REF-1");
        decide(caseId, "REJECTED", "AWAITING_REVIEW", "Bewust zo geleverd");

        // Zelfde revisie: R-CASE-02 houdt het geval REJECTED, maar last_seen_at schuift op.
        screen(fixture, "REF-2", unreadablePrices(2));

        mockMvc.perform(get(API + "/{id}", caseId).with(as(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.hasUnreviewedRecurrence").value(true));
    }

    @Test
    void hasUnreviewedRecurrenceIsFalseForARejectedCaseWithoutALaterObservation() throws Exception {
        Fixture fixture = fixture("REJFALSE");
        long caseId = createSingleCase(fixture, "REF-1");
        decide(caseId, "REJECTED", "AWAITING_REVIEW", "Bewust zo geleverd");

        mockMvc.perform(get(API + "/{id}", caseId).with(as(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.hasUnreviewedRecurrence").value(false));
    }

    // --- Detail: onbekend geval ------------------------------------------------------------------------

    @Test
    void getCaseIsNotFoundForAnUnknownCase() throws Exception {
        mockMvc.perform(get(API + "/{id}", 999_999_999L).with(as(USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ISSUE_CASE_NOT_FOUND"));
    }

    // --- Waarnemingen ------------------------------------------------------------------------------------

    @Test
    void observationsShowTheLinkedGroupsWithBatchAndRevisionInfo() throws Exception {
        Fixture fixture = fixture("OBS");
        DeliveryView first = screen(fixture, "REF-1", unreadablePrices(1));
        DeliveryView second = screen(fixture, "REF-2", unreadablePrices(2));
        long caseId = caseIdOf(fixture, ImportValueRules.CODE_PRICE_UNREADABLE);

        mockMvc.perform(get(API + "/{id}/observations", caseId).with(as(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].batchId").value(first.batch().batchId()))
                .andExpect(jsonPath("$[0].deliveryId").value(first.deliveryId()))
                .andExpect(jsonPath("$[0].definitionRevisionId").value(fixture.revisionId()))
                .andExpect(jsonPath("$[0].occurrenceCount").value(REPEATS))
                .andExpect(jsonPath("$[1].batchId").value(second.batch().batchId()))
                .andExpect(jsonPath("$[1].deliveryId").value(second.deliveryId()));
    }

    @Test
    void observationsIsNotFoundForAnUnknownCase() throws Exception {
        mockMvc.perform(get(API + "/{id}/observations", 999_999_999L).with(as(USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ISSUE_CASE_NOT_FOUND"));
    }

    // --- Geschiedenis -----------------------------------------------------------------------------------

    @Test
    void eventsShowTheFullHistoryInChronologicalOrder() throws Exception {
        Fixture fixture = fixture("EVENTS");
        long caseId = createSingleCase(fixture, "REF-1");
        decide(caseId, "CORRECTED", "AWAITING_REVIEW", "Eerste keer");
        decide(caseId, "AWAITING_REVIEW", "CORRECTED", "Toch heropend");

        mockMvc.perform(get(API + "/{id}/events", caseId).with(as(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].eventKind").value("CREATED"))
                .andExpect(jsonPath("$[0].previousStatus").doesNotExist())
                .andExpect(jsonPath("$[1].eventKind").value("STATUS_CHANGE"))
                .andExpect(jsonPath("$[1].newStatus").value("CORRECTED"))
                .andExpect(jsonPath("$[2].eventKind").value("STATUS_CHANGE"))
                .andExpect(jsonPath("$[2].previousStatus").value("CORRECTED"))
                .andExpect(jsonPath("$[2].newStatus").value("AWAITING_REVIEW"));
    }

    @Test
    void eventsIsNotFoundForAnUnknownCase() throws Exception {
        mockMvc.perform(get(API + "/{id}/events", 999_999_999L).with(as(USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ISSUE_CASE_NOT_FOUND"));
    }

    // --- Samenvatting -------------------------------------------------------------------------------------

    @Test
    void summaryCountsCasesPerStatus() throws Exception {
        Fixture fixture = fixture("SUMMARY");
        screen(fixture, "REF-1", twoIssueCodes());
        long priceCaseId = caseIdOf(fixture, ImportValueRules.CODE_PRICE_UNREADABLE);
        decide(priceCaseId, "REJECTED", "AWAITING_REVIEW", "afgewezen");

        mockMvc.perform(get(API + "/summary").param("importLinkId", String.valueOf(fixture.linkId()))
                        .with(as(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.byStatus[?(@.status=='AWAITING_REVIEW')].count").value(1))
                .andExpect(jsonPath("$.byStatus[?(@.status=='REJECTED')].count").value(1));
    }

    // --- GET /batches/{id}/issue-groups toont issueCaseId ---------------------------------------------------

    @Test
    void issueGroupsListExposesTheLinkedIssueCaseId() throws Exception {
        Fixture fixture = fixture("GROUPCASE");
        DeliveryView delivery = screen(fixture, "REF-1", unreadablePrices(1));
        long caseId = caseIdOf(fixture, ImportValueRules.CODE_PRICE_UNREADABLE);

        mockMvc.perform(get("/api/catalog-import/batches/{id}/issue-groups", delivery.batch().batchId())
                        .with(as(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].issueCaseId").value(caseId));
    }

    // --- Helpers -----------------------------------------------------------------------------------------------

    private void decide(long caseId, String newStatus, String expectedStatus, String reason) throws Exception {
        mockMvc.perform(post("/api/catalog-import/issue-cases/{id}/status", caseId).with(as(USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newStatus\":\"" + newStatus + "\",\"expectedStatus\":\"" + expectedStatus
                        + "\",\"reason\":\"" + reason + "\",\"changedBy\":\"" + USER + "\"}"))
                .andExpect(status().isOk());
    }

    private long createSingleCase(Fixture fixture, String reference) {
        screen(fixture, reference, unreadablePrices(1));
        return caseIdOf(fixture, ImportValueRules.CODE_PRICE_UNREADABLE);
    }

    private long caseIdOf(Fixture fixture, String issueCode) {
        List<Map<String, Object>> found = jdbc.queryForList(
                "select id from issue_case where import_link_id = ? and issue_code = ?",
                fixture.linkId(), issueCode);
        assertThat(found).as("issue case for code " + issueCode).hasSize(1);
        return ((Number) found.get(0).get("id")).longValue();
    }

    /** Twaalf onleesbare prijzen (één groep/geval) en twaalf lege identiteitscomponenten (een tweede). */
    private static String twoIssueCodes() {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= REPEATS; i++) {
            csv.append("ACME;G1;R").append(i).append(";onleesbaar;Boormachine\n");
        }
        for (int i = REPEATS + 1; i <= 2 * REPEATS; i++) {
            csv.append("ACME;;R").append(i).append(";1,50;Boormachine\n");
        }
        return csv.toString();
    }

    private static String identityIssues() {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= REPEATS; i++) {
            csv.append("ACME;;R").append(i).append(";1,50;Boormachine\n");
        }
        return csv.toString();
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

    private DeliveryView screen(Fixture fixture, String reference, String csv) {
        DeliveryView view = intake.intake(fixture.taskId(), reference, "tester@example.test", null, null,
                "levering.csv", new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))).delivery();
        screening.screen(view.batch().batchId());
        return view;
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
        String unique = "ICQ" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
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
