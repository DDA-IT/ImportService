package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.ExternalCredentialRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.dao.TaskRunRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.ExternalCredential;
import be.dda.catalogimport.domain.ExternalCredentialSecretKind;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskRun;
import be.dda.catalogimport.domain.TaskRunStatus;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.service.ActorIdentity;
import be.dda.catalogimport.service.ConflictException;
import be.dda.catalogimport.service.ConnectionProfileService;
import be.dda.catalogimport.service.ConnectionProfileService.NewConnectionProfile;
import be.dda.catalogimport.service.DeliveryConfigurationService;
import be.dda.catalogimport.service.DeliveryConfigurationService.NewDeliveryConfiguration;
import be.dda.catalogimport.service.DeliveryIntakeService;
import be.dda.catalogimport.service.TaskDeliveryConfigurationService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Bouwstap LC-2: taakkoppeling aan een Leveringsconfiguratie-versie ({@code docs/design/leveringsconfiguratie-design.md}
 * par. 3.2, 4.4, 6, 10; beslissingslog 2026-09-29 L2, L3, A10, A11).
 *
 * <ul>
 *   <li><b>Regel:</b> (ont)koppelen is geauditeerd. <b>Data:</b> {@code catalog_import_task.delivery_configuration_version_id}
 *       en append-only {@code acquisition_config_event} ({@code TASK_BOUND}/{@code TASK_UNBOUND}, HUMAN, actor en
 *       subject uit het token, verplichte reden). Herkoppelen = UNBOUND(oud) + BOUND(nieuw); dezelfde versie opnieuw is
 *       idempotent (geen event).</li>
 *   <li><b>Regel A11:</b> hoogstens één taak met een Leveringsconfiguratie per koppeling: 409.</li>
 *   <li><b>Regel:</b> niet (ont)koppelen met een lopende run: 409 {@code TASK_RUN_IN_PROGRESS}.</li>
 *   <li><b>Regel A10:</b> upload en servermap op een taak met DC: 409 {@code TASK_HAS_DELIVERY_CONFIGURATION}, niets
 *       geregistreerd; zonder DC ongewijzigd.</li>
 * </ul>
 * Elke test bouwt een eigen keten met unieke codes: de database is gedeeld.
 */
@SpringBootTest(properties = {"catalogimport.screening.recovery-on-startup=false"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class TaskBindingHttpTest {

    private static final String API = "/api/catalog-import";
    private static final String BINDING = API + "/tasks/{id}/delivery-configuration";
    private static final String USER = "an.janssens@example.test";
    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @TempDir
    static Path archiveRoot;
    @TempDir
    static Path localSourceRoot;

    @DynamicPropertySource
    static void directories(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
        registry.add("catalogimport.local-source.directory", () -> localSourceRoot.toString());
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
    @Autowired
    private TaskRunRepository runs;
    @Autowired
    private ExternalCredentialRepository credentials;
    @Autowired
    private ConnectionProfileService profiles;
    @Autowired
    private DeliveryConfigurationService configurations;
    @Autowired
    private TaskDeliveryConfigurationService bindings;

    // --- Koppelen, herkoppelen, ontkoppelen ----------------------------------------------------------------------------

    @Test
    void bindingRebindingAndUnbindingWriteTheColumnAndAppendOnlyEvents() throws Exception {
        CatalogImportTask task = task(link(unique("BIND")));
        Dc first = dc();
        Dc second = dc();

        bind(task.getId(), first.versionId(), "Automatisch ophalen")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(task.getId()))
                .andExpect(jsonPath("$.deliveryConfigurationVersionId").value(first.versionId()))
                .andExpect(jsonPath("$.deliveryConfigurationCode").value(first.code()))
                .andExpect(jsonPath("$.deliveryConfigurationVersionNumber").value(1));
        assertThat(boundVersion(task.getId())).isEqualTo(first.versionId());
        List<Map<String, Object>> trail = events(task.getId());
        assertThat(trail).hasSize(1);
        assertThat(trail.get(0)).containsEntry("event_kind", "TASK_BOUND").containsEntry("source", "HUMAN")
                .containsEntry("changed_by", USER).containsEntry("changed_by_subject", "test-sub-" + USER)
                .containsEntry("reason", "Automatisch ophalen").containsEntry("dc_version_id", first.versionId())
                .containsEntry("profile_version_id", first.profileVersionId());
        assertThat(trail.get(0).get("changed_at")).isNotNull();

        // Dubbele invoer: dezelfde versie opnieuw = 200, geen wijziging en geen tweede event.
        bind(task.getId(), first.versionId(), "Nog eens").andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryConfigurationVersionId").value(first.versionId()));
        assertThat(events(task.getId())).hasSize(1);

        // Herkoppelen: UNBOUND voor de oude, BOUND voor de nieuwe versie.
        bind(task.getId(), second.versionId(), "Nieuwe configuratie").andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryConfigurationVersionId").value(second.versionId()));
        assertThat(boundVersion(task.getId())).isEqualTo(second.versionId());
        trail = events(task.getId());
        assertThat(trail).extracting(e -> e.get("event_kind"))
                .containsExactly("TASK_BOUND", "TASK_UNBOUND", "TASK_BOUND");
        assertThat(trail.get(1)).containsEntry("dc_version_id", first.versionId())
                .containsEntry("reason", "Nieuwe configuratie");
        assertThat(trail.get(2)).containsEntry("dc_version_id", second.versionId());

        // Ontkoppelen, dan nogmaals ontkoppelen = 409 zonder event.
        unbind(task.getId(), "Leverancier levert weer via upload").andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(task.getId()))
                .andExpect(jsonPath("$.deliveryConfigurationVersionId").doesNotExist());
        assertThat(boundVersion(task.getId())).isNull();
        trail = events(task.getId());
        assertThat(trail).hasSize(4);
        assertThat(trail.get(3)).containsEntry("event_kind", "TASK_UNBOUND")
                .containsEntry("dc_version_id", second.versionId())
                .containsEntry("reason", "Leverancier levert weer via upload");
        unbind(task.getId(), "Nog eens").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(TaskDeliveryConfigurationService.CODE_NOT_BOUND));
        assertThat(events(task.getId())).hasSize(4);
    }

    // --- A11 ---------------------------------------------------------------------------------------------------------

    @Test
    void atMostOneTaskPerImportLinkHasADeliveryConfiguration() throws Exception {
        ImportLink link = link(unique("A11"));
        CatalogImportTask one = task(link);
        CatalogImportTask two = task(link);
        CatalogImportTask elsewhere = task(link(unique("A11B")));
        Dc dc = dc();
        Dc other = dc();

        bind(one.getId(), dc.versionId(), "Eerste").andExpect(status().isOk());
        bind(two.getId(), dc.versionId(), "Tweede").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(TaskDeliveryConfigurationService.CODE_LINK_ALREADY_HAS_DC_TASK));
        assertThat(boundVersion(two.getId())).isNull();
        assertThat(events(two.getId())).isEmpty();

        // De eigen taak telt niet mee: herkoppelen blijft mogen; een andere koppeling is onafhankelijk.
        bind(one.getId(), other.versionId(), "Herkoppelen").andExpect(status().isOk());
        bind(elsewhere.getId(), dc.versionId(), "Andere koppeling").andExpect(status().isOk());

        unbind(one.getId(), "Vrijmaken").andExpect(status().isOk());
        bind(two.getId(), dc.versionId(), "Nu wel").andExpect(status().isOk());
        assertThat(boundVersion(two.getId())).isEqualTo(dc.versionId());
    }

    /**
     * A11 onder gelijktijdigheid: twee taken van dezelfde koppeling tegelijk koppelen. Het rijslot op alle taken van
     * de koppeling laat er exact één slagen; de andere krijgt 409 en er is exact één {@code TASK_BOUND}-event.
     */
    @Test
    void twoConcurrentBindingsOnTheSameLinkLetExactlyOneSucceed() throws Exception {
        ImportLink link = link(unique("RACE"));
        CatalogImportTask one = task(link);
        CatalogImportTask two = task(link);
        Dc dc = dc();
        ActorIdentity actor = new ActorIdentity(USER, "test-sub-" + USER);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> outcomes = new ArrayList<>();
            for (CatalogImportTask task : List.of(one, two)) {
                outcomes.add(pool.submit(() -> {
                    go.await();
                    try {
                        return bindings.bind(task.getId(), dc.versionId(), "Gelijktijdig", actor)
                                .deliveryConfigurationVersionId() == null ? "NONE" : "BOUND";
                    } catch (ConflictException conflict) {
                        return conflict.getCode();
                    }
                }));
            }
            go.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> outcome : outcomes) {
                results.add(outcome.get(60, TimeUnit.SECONDS));
            }
            assertThat(results).containsExactlyInAnyOrder("BOUND",
                    TaskDeliveryConfigurationService.CODE_LINK_ALREADY_HAS_DC_TASK);
        } finally {
            pool.shutdownNow();
        }
        long bound = jdbc.queryForObject("select count(*) from catalog_import_task where import_link_id = ? "
                + "and delivery_configuration_version_id is not null", Long.class, link.getId());
        assertThat(bound).isEqualTo(1L);
        assertThat(events(one.getId()).size() + events(two.getId()).size()).isEqualTo(1);
    }

    // --- Lopende run -------------------------------------------------------------------------------------------------

    @Test
    void aTaskWithARunInProgressCanBeNeitherBoundNorUnbound() throws Exception {
        CatalogImportTask task = task(link(unique("RUN")));
        Dc dc = dc();

        TaskRun run = running(task);
        bind(task.getId(), dc.versionId(), "Tijdens run").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_RUN_IN_PROGRESS"));
        assertThat(boundVersion(task.getId())).isNull();
        finish(run);

        bind(task.getId(), dc.versionId(), "Na de run").andExpect(status().isOk());
        TaskRun second = running(task);
        unbind(task.getId(), "Tijdens run").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_RUN_IN_PROGRESS"));
        assertThat(boundVersion(task.getId())).isEqualTo(dc.versionId());
        assertThat(events(task.getId())).hasSize(1);
        finish(second);
    }

    /** Ook zonder concurrency-token (preventConcurrentRuns = false) telt een RUNNING-run als lopend. */
    @Test
    void aRunningRunWithoutConcurrencyTokenAlsoBlocks() throws Exception {
        CatalogImportTask task = task(link(unique("RUNF")));
        task.setPreventConcurrentRuns(false);
        task = tasks.saveAndFlush(task);
        Dc dc = dc();

        TaskRun run = running(task);
        assertThat(run.getConcurrencyToken()).isNull();
        bind(task.getId(), dc.versionId(), "Tijdens run").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_RUN_IN_PROGRESS"));
        finish(run);
    }

    // --- Onbekend en ongeldig -----------------------------------------------------------------------------------------

    @Test
    void unknownTaskOrVersionIs404AndMissingInputIs400() throws Exception {
        CatalogImportTask task = task(link(unique("UNK")));
        Dc dc = dc();
        long unknown = 999_999_999L;

        bind(unknown, dc.versionId(), "Reden").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
        unbind(unknown, "Reden").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
        bind(task.getId(), unknown, "Reden").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(TaskDeliveryConfigurationService.CODE_VERSION_NOT_FOUND));
        json(put(BINDING, task.getId()), "{\"reason\":\"Reden\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(TaskDeliveryConfigurationService.CODE_VERSION_REQUIRED));
        bind(task.getId(), dc.versionId(), " ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(TaskDeliveryConfigurationService.CODE_REASON_REQUIRED));
        json(put(BINDING, task.getId()), "").andExpect(status().isBadRequest());
        json(delete(BINDING, task.getId()), "").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(TaskDeliveryConfigurationService.CODE_REASON_REQUIRED));

        assertThat(boundVersion(task.getId())).isNull();
        assertThat(events(task.getId())).isEmpty();
    }

    // --- A10: upload en servermap op een taak met DC ----------------------------------------------------------------

    @Test
    void uploadAndServerDirectoryAreRefusedOnATaskWithADeliveryConfigurationAndUnchangedWithout() throws Exception {
        CatalogImportTask task = task(link(unique("A10")));
        Dc dc = dc();
        String fileName = "a10-" + UUID.randomUUID() + ".csv";
        Files.writeString(localSourceRoot.resolve(fileName), HEADER + "ACME;G1;R1;10,00;Artikel 1\n",
                StandardCharsets.UTF_8);
        bind(task.getId(), dc.versionId(), "Automatisch ophalen").andExpect(status().isOk());

        upload(task.getId(), unique("REF")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(DeliveryIntakeService.CODE_TASK_HAS_DELIVERY_CONFIGURATION));
        json(post(API + "/tasks/{id}/deliveries/local-source", task.getId()), "{\"fileName\":\"" + fileName + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(DeliveryIntakeService.CODE_TASK_HAS_DELIVERY_CONFIGURATION));
        assertThat(deliveryCount(task.getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from task_run where task_id = ?", Long.class, task.getId()))
                .isZero();

        // Zonder DC: exact het bestaande gedrag (201, levering aangemaakt).
        unbind(task.getId(), "Terug naar upload").andExpect(status().isOk());
        upload(task.getId(), unique("REF2")).andExpect(status().isCreated());
        assertThat(deliveryCount(task.getId())).isEqualTo(1L);
    }

    // --- Rechten -----------------------------------------------------------------------------------------------------

    @Test
    void aReadOnlyUserMayNotBindOrUnbindAndNothingIsWritten() throws Exception {
        CatalogImportTask task = task(link(unique("READ")));
        Dc dc = dc();
        RequestPostProcessor reader = as(USER, Permission.READ);

        mockMvc.perform(put(BINDING, task.getId()).with(reader).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versionId\":" + dc.versionId() + ",\"reason\":\"R\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        assertThat(boundVersion(task.getId())).isNull();

        bind(task.getId(), dc.versionId(), "Beheerder").andExpect(status().isOk());
        mockMvc.perform(delete(BINDING, task.getId()).with(reader).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"R\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        assertThat(boundVersion(task.getId())).isEqualTo(dc.versionId());
        assertThat(events(task.getId())).hasSize(1);
    }

    // --- Helpers -------------------------------------------------------------------------------------------------------

    private record Dc(String code, long versionId, long profileVersionId) {
    }

    private Dc dc() {
        ActorIdentity actor = new ActorIdentity(USER, "test-sub-" + USER);
        String host = "sftp.bind" + SEQUENCE.incrementAndGet() + ".test";
        ExternalCredential credential = credentials.saveAndFlush(new ExternalCredential(UUID.randomUUID(),
                "LC-2 bind " + host, ExternalCredentialSecretKind.SFTP_PASSWORD, host, "v1:lc2-test:Nep", "lc2-test",
                USER, null, Instant.now()));
        String profileCode = unique("PRF");
        long profileVersionId = profiles.create(new NewConnectionProfile(profileCode, "Profiel", host, null, "lev",
                "PASSWORD", credential.getCredentialRef().toString(), "ssh-ed25519",
                ConnectionProfileHttpTest.fingerprint(), "Test"), actor).versions().get(0).id();
        String code = unique("DC");
        long versionId = configurations.create(new NewDeliveryConfiguration(code, "Levering", profileVersionId, "/out",
                "ALL_FILES", null, null, null, "Test"), actor).versions().get(0).id();
        return new Dc(code, versionId, profileVersionId);
    }

    private ResultActions bind(long taskId, long versionId, String reason) throws Exception {
        return json(put(BINDING, taskId), "{\"versionId\":" + versionId + ",\"reason\":\"" + reason + "\"}");
    }

    private ResultActions unbind(long taskId, String reason) throws Exception {
        return json(delete(BINDING, taskId), "{\"reason\":\"" + reason + "\"}");
    }

    private ResultActions json(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mockMvc.perform(request.with(as(USER, Permission.MANAGE)).contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions upload(long taskId, String reference) throws Exception {
        return mockMvc.perform(multipart(API + "/tasks/{id}/deliveries", taskId)
                .file(new MockMultipartFile("file", "levering.csv", "text/csv",
                        (HEADER + "ACME;G1;R1;10,00;Artikel 1\n").getBytes(StandardCharsets.UTF_8)))
                .param("deliveryReference", reference).with(as(USER, Permission.MANAGE)));
    }

    private TaskRun running(CatalogImportTask task) {
        TaskRun run = new TaskRun(task, Instant.now(), USER);
        run.setStatus(TaskRunStatus.RUNNING);
        return runs.saveAndFlush(run);
    }

    private void finish(TaskRun run) {
        run.setStatus(TaskRunStatus.COMPLETED);
        run.setFinishedAt(Instant.now());
        runs.saveAndFlush(run);
    }

    private Long boundVersion(long taskId) {
        return jdbc.queryForObject("select delivery_configuration_version_id from catalog_import_task where id = ?",
                Long.class, taskId);
    }

    private List<Map<String, Object>> events(long taskId) {
        return jdbc.queryForList("select * from acquisition_config_event where task_id = ? order by id", taskId);
    }

    private long deliveryCount(long taskId) {
        return jdbc.queryForObject("select count(*) from delivery where task_id = ?", Long.class, taskId);
    }

    private static String unique(String prefix) {
        return "TB" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
    }

    private ImportLink link(String unique) {
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
        return links.saveAndFlush(new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier,
                "PSARF050"));
    }

    private CatalogImportTask task(ImportLink link) {
        return tasks.saveAndFlush(new CatalogImportTask(link, unique("taak"), TaskTriggerType.MANUAL));
    }
}
