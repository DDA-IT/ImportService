package be.dda.catalogimport.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.dao.TaskRunRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskRun;
import be.dda.catalogimport.domain.TaskRunStatus;
import be.dda.catalogimport.domain.TaskTriggerType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-contract van {@code GET /api/catalog-import/tasks} (bouwstap B-B1, beslissingslog 23/09): alleen
 * lezen, altijd bereikbaar (in tests staat {@code catalogimport.setup-api.enabled} niet aan), en zonder
 * botsing met {@code POST /tasks/{taskId}/deliveries}. Fixtures rechtstreeks via de repositories.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class CatalogImportTaskHttpTest {

    private static final String TASKS = "/api/catalog-import/tasks";

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
    private ImportLinkRepository links;
    @Autowired
    private CatalogImportTaskRepository tasks;
    @Autowired
    private TaskRunRepository runs;

    @Test
    void tasksAreReachableWithoutTheSetupApiFlagAndShowLinkLabelsTriggerTypeAndLastRun() throws Exception {
        String unique = unique();
        ImportLink link = link(unique);
        CatalogImportTask manual = tasks.saveAndFlush(new CatalogImportTask(link, "b-manual", TaskTriggerType.MANUAL));
        CatalogImportTask scheduled = new CatalogImportTask(link, "a-scheduled", TaskTriggerType.SCHEDULED);
        scheduled.setTriggerExpression("0 0 3 * * *");
        scheduled.setPreventConcurrentRuns(false);
        scheduled = tasks.saveAndFlush(scheduled);

        Instant older = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        Instant newer = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        TaskRun oldRun = new TaskRun(scheduled, older, "tester");
        oldRun.setStatus(TaskRunStatus.COMPLETED);
        oldRun.setFinishedAt(older.plusSeconds(5));
        runs.saveAndFlush(oldRun);
        TaskRun newRun = new TaskRun(scheduled, newer, "tester");
        newRun.setStatus(TaskRunStatus.COMPLETED);
        newRun.setFinishedAt(newer.plusSeconds(7));
        runs.saveAndFlush(newRun);

        // Sortering: taaknaam oplopend binnen de koppeling, dus a-scheduled voor b-manual.
        mockMvc.perform(get(TASKS).param("importLinkId", String.valueOf(link.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(scheduled.getId()))
                .andExpect(jsonPath("$.content[0].name").value("a-scheduled"))
                .andExpect(jsonPath("$.content[0].triggerType").value("SCHEDULED"))
                .andExpect(jsonPath("$.content[0].preventConcurrentRuns").value(false))
                .andExpect(jsonPath("$.content[0].importLinkId").value(link.getId()))
                .andExpect(jsonPath("$.content[0].importLinkCode").value(link.getCode()))
                .andExpect(jsonPath("$.content[0].supplierCode").value(unique + "-SUP"))
                .andExpect(jsonPath("$.content[0].libraryCode").value("PSARF050"))
                .andExpect(jsonPath("$.content[0].active").value(true))
                // lastRun* = de meest recente run, niet de oudste.
                .andExpect(jsonPath("$.content[0].lastRunStartedAt").value(newer.toString()))
                .andExpect(jsonPath("$.content[0].lastRunFinishedAt").value(newer.plusSeconds(7).toString()))
                .andExpect(jsonPath("$.content[1].id").value(manual.getId()))
                .andExpect(jsonPath("$.content[1].triggerType").value("MANUAL"))
                .andExpect(jsonPath("$.content[1].preventConcurrentRuns").value(true))
                .andExpect(jsonPath("$.content[1].lastRunStartedAt").isEmpty())
                .andExpect(jsonPath("$.content[1].lastRunFinishedAt").isEmpty());
    }

    @Test
    void tasksAreOrderedByLinkCodeThenNameAndFilterableByActiveAndImportLink() throws Exception {
        String unique = unique();
        ImportLink linkA = link(unique + "A");
        ImportLink linkB = link(unique + "B");
        // Bewust in omgekeerde volgorde aangemaakt: de sortering hangt niet af van het id.
        CatalogImportTask b2 = tasks.saveAndFlush(new CatalogImportTask(linkB, "x", TaskTriggerType.MANUAL));
        CatalogImportTask inactive = new CatalogImportTask(linkA, "z-inactive", TaskTriggerType.MANUAL);
        inactive.setActive(false);
        inactive = tasks.saveAndFlush(inactive);
        CatalogImportTask a1 = tasks.saveAndFlush(new CatalogImportTask(linkA, "m-active", TaskTriggerType.MANUAL));

        // Beide koppelingen dragen dezelfde uniekheidsprefix: filter via de hele pagina met grote size
        // is onbetrouwbaar in een gedeelde database, dus per koppeling en gecombineerd nagaan.
        mockMvc.perform(get(TASKS).param("importLinkId", String.valueOf(linkA.getId())))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(a1.getId()))
                .andExpect(jsonPath("$.content[1].id").value(inactive.getId()));
        mockMvc.perform(get(TASKS).param("importLinkId", String.valueOf(linkB.getId())))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(b2.getId()));
        mockMvc.perform(get(TASKS).param("importLinkId", String.valueOf(linkA.getId())).param("active", "true"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(a1.getId()));
        mockMvc.perform(get(TASKS).param("importLinkId", String.valueOf(linkA.getId())).param("active", "false"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(inactive.getId()));
        mockMvc.perform(get(TASKS).param("importLinkId", "999999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content.length()").value(0));

        // Over koppelingen heen: link A (code ...A-LINK) komt voor link B (code ...B-LINK).
        // De database is gedeeld, dus alle pagina's doorlopen en de globale volgorde van de ids nagaan.
        java.util.List<Long> order = new java.util.ArrayList<>();
        int page = 0;
        int totalPages = 1;
        while (page < totalPages) {
            String body = mockMvc.perform(get(TASKS).param("size", "200").param("active", "true")
                            .param("page", String.valueOf(page)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            totalPages = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.totalPages")).intValue();
            java.util.List<Number> ids = com.jayway.jsonpath.JsonPath.read(body, "$.content[*].id");
            ids.forEach(id -> order.add(id.longValue()));
            page++;
        }
        assertThat(order).contains(a1.getId(), b2.getId());
        assertThat(order.indexOf(a1.getId())).isLessThan(order.indexOf(b2.getId()));
        assertThat(order).doesNotContain(inactive.getId());
    }

    @Test
    void tasksArePaginatedWithTheSharedDefaultAndMaximumAndRejectInvalidPaging() throws Exception {
        mockMvc.perform(get(TASKS)).andExpect(status().isOk()).andExpect(jsonPath("$.size").value(50));
        mockMvc.perform(get(TASKS).param("size", "5000")).andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(200));
        mockMvc.perform(get(TASKS).param("size", "0")).andExpect(status().isBadRequest());
        mockMvc.perform(get(TASKS).param("page", "-1")).andExpect(status().isBadRequest());

        ImportLink link = link(unique());
        tasks.saveAndFlush(new CatalogImportTask(link, "t1", TaskTriggerType.MANUAL));
        tasks.saveAndFlush(new CatalogImportTask(link, "t2", TaskTriggerType.MANUAL));
        mockMvc.perform(get(TASKS).param("importLinkId", String.valueOf(link.getId()))
                        .param("size", "1").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].name").value("t2"));
    }

    @Test
    void getTasksDoesNotCollideWithThePostDeliveriesEndpoint() throws Exception {
        ImportLink link = link(unique());
        CatalogImportTask task = tasks.saveAndFlush(new CatalogImportTask(link, "t", TaskTriggerType.MANUAL));

        mockMvc.perform(get(TASKS)).andExpect(status().isOk());
        // De POST-route bestaat nog en wordt bereikt: een onbekende taak geeft de eigen 404, geen 405.
        mockMvc.perform(multipart(TASKS + "/{id}/deliveries", 999_999_999L)
                        .file(new MockMultipartFile("file", "a.csv", "text/csv", "x".getBytes(StandardCharsets.UTF_8)))
                        .param("deliveryReference", "REF-" + System.nanoTime())
                        .param("uploadedBy", "tester@example.test"))
                .andExpect(status().isNotFound());
        assertThat(task.getId()).isNotNull();
    }

    private static String unique() {
        return "TSK" + Long.toString(System.nanoTime(), 36);
    }

    private ImportLink link(String unique) {
        SourceOrganisation organisation = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + "-ORG BV", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(
                new ImportDefinition(organisation, unique + "-DEF", unique + " catalogus", "beheerder@example.test"));
        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        return links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, "PSARF050"));
    }
}
