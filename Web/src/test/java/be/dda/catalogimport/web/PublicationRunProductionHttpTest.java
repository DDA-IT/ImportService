package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static be.dda.catalogimport.testsupport.TestActors.withoutPermissions;
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
import be.dda.catalogimport.domain.BundleDecisionKind;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.MutationStatus;
import be.dda.catalogimport.domain.PublicationTargetMode;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.service.BundleDecisionService;
import be.dda.catalogimport.service.BundleDecisionService.DecisionFilter;
import be.dda.catalogimport.service.BundleFreezeService;
import be.dda.catalogimport.service.DeliveryIntakeService;
import be.dda.catalogimport.service.DeliveryScreeningService;
import be.dda.catalogimport.service.PublicationBundleService;
import be.dda.catalogimport.service.SourceStateBaselineService;
import be.dda.catalogimport.testsupport.ProdisPublicationTestDatabase;
import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Het HTTP-contract van de publicatie naar Prodis (P6, {@code docs/design/publicatie-prodis-db-direct-design.md} par. 6 rij P6),
 * end-to-end via MockMvc, met de JSON-vorm die de frontend (P7: {@code api/publicationRuns.ts}, {@code api/types.ts}) verwacht.
 * De Spring-context schrijft als de testrol in {@code prodis_pubtest} (guard: {@link ProdisPublicationTestDatabase}; de echte
 * {@code Prodis} wordt nooit beschreven) en gebruikt {@code chunk-size=1}, zodat de continue-lus zichtbaar meerdere
 * aanroepen kost.
 *
 * <h2>Business rule → wat hier bewezen wordt</h2>
 * <ul>
 *   <li>POST start (PRODUCTION, APPROVE) → run {@code PUBLISHING}; POST continue (APPROVE) tot {@code done}; run en bundellijst
 *       tonen {@code itemCounts}, {@code done}, {@code legacyMasterAtStart}, {@code simulationOnly=false},
 *       {@code writesToProdis=true}.</li>
 *   <li>GET items (READ): pagina-envelop, statusfilter, itemvorm met identiteit, prior/result ({@code ArticleState}) en meldingen.</li>
 *   <li>403 vóór 404/409 en zonder bijwerking; 409-codes {@code BUNDLE_TARGET_MODE_NOT_PRODUCTION}, {@code BUNDLE_NOT_FROZEN},
 *       {@code PUBLICATION_RUN_NOT_PRODUCTION}, {@code PRODUCTION_RUN_IN_PROGRESS}, {@code PUBLICATION_RUN_HAS_SENT_ITEMS},
 *       {@code PUBLICATION_RUN_BUSY}; 404 en 400 op onbekende run, ongeldige status en paginering.</li>
 * </ul>
 * De codes {@code PUBLICATION_MODE_NOT_ENABLED} (vlag uit) en {@code PRODIS_WRITE_NOT_CONFIGURED} zitten in andere contexten
 * en zijn bewezen in {@code PublicationRunHttpTest} en {@code PublicationRunProductionTest}.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=50",
        "catalogimport.screening.mutation-chunk-size=50",
        "catalogimport.screening.recovery-on-startup=false",
        "catalogimport.publication.prodis.enabled=true",
        "catalogimport.publication.source-id=ci-p6-test",
        "catalogimport.publication.chunk-size=1"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class PublicationRunProductionHttpTest {

    private static final String API = "/api/catalog-import";
    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    private static final String CREATOR = "jan.peeters@example.test";
    private static final String FREEZER = "an.janssens@example.test";
    private static final String DECIDER = "piet.willems@example.test";
    private static final String USER = "els.verhoeven@example.test";
    private static final String LIBRARY = "PSARF050";
    private static final String BUNDLE_PREFIX = "BND-P6H-";
    private static final String PRODUCTION = "{\"targetMode\":\"PRODUCTION\"}";
    private static final String SIMULATION = "{\"targetMode\":\"SIMULATION\"}";
    private static final List<String> PRODIS_TABLES = List.of("catalog_article_description", "catalog_article_price_detail",
            "catalog_article_price", "catalog_article", "system_settings", "language", "supplier_catalog", "supplier_group",
            "supplier");
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final AtomicLong PRODIS_ID = new AtomicLong(7000);

    @TempDir
    static java.nio.file.Path archiveRoot;

    @BeforeAll
    static void prepareProdis() {
        ProdisPublicationTestDatabase.prepare();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
        registry.add("catalogimport.prodis-db-write.url", () -> ProdisPublicationTestDatabase.URL);
        registry.add("catalogimport.prodis-db-write.username", () -> ProdisPublicationTestDatabase.ROLE);
        registry.add("catalogimport.prodis-db-write.password", () -> ProdisPublicationTestDatabase.ROLE_PASSWORD);
        registry.add("catalogimport.prodis-db.url", () -> ProdisPublicationTestDatabase.URL);
        registry.add("catalogimport.prodis-db.username", () -> ProdisPublicationTestDatabase.OWNER);
        registry.add("catalogimport.prodis-db.password", () -> ProdisPublicationTestDatabase.OWNER_PASSWORD);
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate ci;
    @Autowired
    private DeliveryIntakeService intake;
    @Autowired
    private DeliveryScreeningService screening;
    @Autowired
    private SourceStateBaselineService baseline;
    @Autowired
    private PublicationBundleService bundleService;
    @Autowired
    private BundleDecisionService decisions;
    @Autowired
    private BundleFreezeService freezeService;
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

    @BeforeEach
    void cleanProdis() {
        closeActiveRuns();
        owner("truncate " + String.join(", ", PRODIS_TABLES) + " cascade");
        owner("insert into language (id, code, txt) values (4, 'NL', 'Nederlands')");
        owner("insert into system_settings (id, legacy_master) values (1, 'NONE')");
    }

    /** Sluit elke actieve PRODUCTION-run van deze testklasse af (enkel bundels met {@value #BUNDLE_PREFIX}). */
    @AfterEach
    void closeActiveRuns() {
        ci.update("update publication_run set status = 'FAILED', failure_code = 'TEST_P6_CLEANUP', finished_at = now(), "
                + "active_marker = null, lease_owner = null, lease_until = null where target_mode = 'PRODUCTION' "
                + "and active_marker is not null and bundle_id in (select id from publication_bundle "
                + "where bundle_reference like '" + BUNDLE_PREFIX + "%')");
    }

    // --- start, continue, run- en itemcontract -------------------------------------------------------------------------

    @Test
    void startContinueUntilDoneThenTheRunAndItemsCarryTheFrontendContract() throws Exception {
        Scenario s = scenario("FLOW", PublicationTargetMode.PRODUCTION, "R1", "R2", "R4");
        prodisArticle(s, "R1", "100", "Boormachine");   // 100 -> 120: APPLIED
        prodisArticle(s, "R2", "120", "Schroevendraaier"); // al 120: UNCHANGED
        // R4 bestaat niet in Prodis: REJECTED.

        String started = send(API + "/bundles/" + s.bundleId + "/publication-runs", as(USER, Permission.APPROVE), PRODUCTION)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetMode").value("PRODUCTION"))
                .andExpect(jsonPath("$.status").value("PUBLISHING"))
                .andExpect(jsonPath("$.done").value(false))
                .andExpect(jsonPath("$.simulationOnly").value(false))
                .andExpect(jsonPath("$.writesToProdis").value(true))
                .andExpect(jsonPath("$.legacyMasterAtStart").value("NONE"))
                .andExpect(jsonPath("$.requestedBy").value(USER))
                .andReturn().getResponse().getContentAsString();
        long runId = ((Number) JsonPath.read(started, "$.id")).longValue();
        // chunk-size 1: na de start is precies één item verwerkt; de rest wacht (ontbrekende sleutels = 0).
        assertThat(((java.util.Map<?, ?>) JsonPath.read(started, "$.itemCounts")).values().stream()
                .mapToLong(v -> ((Number) v).longValue()).sum()).isEqualTo(3L);
        assertThat((Integer) JsonPath.read(started, "$.itemCounts.PENDING")).isEqualTo(2);
        assertThat(started).doesNotContain("Subject").doesNotContain("subject").doesNotContain("artifactReference");

        // De continue-lus van de frontend: tot done (nooit eindeloos).
        String last = started;
        int calls = 0;
        while (!(Boolean) JsonPath.read(last, "$.done")) {
            assertThat(calls++).isLessThan(10);
            last = send(API + "/publication-runs/" + runId + "/continue", as(USER, Permission.APPROVE), null)
                    .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(runId))
                    .andReturn().getResponse().getContentAsString();
        }
        assertThat(calls).isEqualTo(2);
        // Een run wordt APPLIED; de bundel is PARTIALLY_PUBLISHED omdat één item REJECTED is (geen run-status PARTIALLY_PUBLISHED).
        assertThat((String) JsonPath.read(last, "$.status")).isEqualTo("APPLIED");
        assertThat((Integer) JsonPath.read(last, "$.itemCounts.APPLIED")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(last, "$.itemCounts.UNCHANGED")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(last, "$.itemCounts.REJECTED")).isEqualTo(1);
        assertThat(ci.queryForObject("select status from publication_bundle where id = ?", String.class, s.bundleId))
                .isEqualTo("PARTIALLY_PUBLISHED");

        // Continue op een afgeronde run: idempotent, 200 met dezelfde run.
        send(API + "/publication-runs/" + runId + "/continue", as(USER, Permission.APPROVE), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.done").value(true));

        // GET run (READ) en bundellijst (READ) dragen dezelfde additieve velden.
        mockMvc.perform(get(API + "/publication-runs/" + runId).with(as(USER, Permission.READ)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.done").value(true))
                .andExpect(jsonPath("$.legacyMasterAtStart").value("NONE"))
                .andExpect(jsonPath("$.writesToProdis").value(true))
                .andExpect(jsonPath("$.itemCounts.APPLIED").value(1));
        mockMvc.perform(get(API + "/bundles/" + s.bundleId + "/publication-runs").with(as(USER, Permission.READ)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(runId)).andExpect(jsonPath("$[0].done").value(true))
                .andExpect(jsonPath("$[0].itemCounts.UNCHANGED").value(1));

        // Items: envelop + filter.
        mockMvc.perform(get(API + "/publication-runs/" + runId + "/items").with(as(USER, Permission.READ)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50)).andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(1)).andExpect(jsonPath("$.content.length()").value(3));
        mockMvc.perform(get(API + "/publication-runs/" + runId + "/items").param("size", "2")
                        .with(as(USER, Permission.READ)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalPages").value(2)).andExpect(jsonPath("$.content.length()").value(2));
        mockMvc.perform(get(API + "/publication-runs/" + runId + "/items").param("size", "2").param("page", "1")
                        .with(as(USER, Permission.READ)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1));

        mockMvc.perform(get(API + "/publication-runs/" + runId + "/items").param("status", "APPLIED")
                        .with(as(USER, Permission.READ)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].mutationId").isNumber())
                .andExpect(jsonPath("$.content[0].identity.supplierNumber").value(s.supplier))
                .andExpect(jsonPath("$.content[0].identity.groupLegacyCode").value(s.supplier + "|G1|"))
                .andExpect(jsonPath("$.content[0].identity.supplierReference").value("R1"))
                .andExpect(jsonPath("$.content[0].identity.libraryCode").value(LIBRARY))
                .andExpect(jsonPath("$.content[0].status").value("APPLIED"))
                .andExpect(jsonPath("$.content[0].reasonCode").doesNotExist())
                .andExpect(jsonPath("$.content[0].prior.basePrice").value("100"))
                .andExpect(jsonPath("$.content[0].prior.description").value("Boormachine"))
                .andExpect(jsonPath("$.content[0].result.basePrice").value("120"))
                .andExpect(jsonPath("$.content[0].result.priceCode").value("0"))
                .andExpect(jsonPath("$.content[0].result.percentages.1").value("10"))
                .andExpect(jsonPath("$.content[0].result.prices.1").isString())
                .andExpect(jsonPath("$.content[0].result.brutPrice").isString())
                .andExpect(jsonPath("$.content[0].result.brutPricePercentage").isString())
                .andExpect(jsonPath("$.content[0].warnings").isArray())
                .andExpect(jsonPath("$.content[0].legacyMaster").value("NONE"))
                .andExpect(jsonPath("$.content[0].sendCount").value(1))
                .andExpect(jsonPath("$.content[0].lastErrorCode").doesNotExist());
        mockMvc.perform(get(API + "/publication-runs/" + runId + "/items").param("status", "UNCHANGED")
                        .with(as(USER, Permission.READ)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].identity.supplierReference").value("R2"))
                .andExpect(jsonPath("$.content[0].status").value("UNCHANGED"));
        String rejected = mockMvc.perform(get(API + "/publication-runs/" + runId + "/items").param("status", "REJECTED")
                        .with(as(USER, Permission.READ)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].identity.supplierReference").value("R4"))
                .andExpect(jsonPath("$.content[0].reasonCode").isString())
                .andExpect(jsonPath("$.content[0].reasonDetail").isString())
                .andReturn().getResponse().getContentAsString();
        assertThat(rejected).doesNotContain("requestJson").doesNotContain("idempotencyKey").doesNotContain("sentBy");
        mockMvc.perform(get(API + "/publication-runs/" + runId + "/items").param("status", "PENDING")
                        .with(as(USER, Permission.READ)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    // --- rechten --------------------------------------------------------------------------------------------------------

    @Test
    void withoutApproveThePublishEndpointsAre403BeforeAnythingElseAndNothingChanges() throws Exception {
        Scenario s = scenario("PERM", PublicationTargetMode.PRODUCTION, "R1");
        prodisArticle(s, "R1", "100", "Boormachine");

        for (RequestPostProcessor login : List.of(as(USER, Permission.READ), as(USER, Permission.MANAGE),
                withoutPermissions(USER))) {
            send(API + "/bundles/" + s.bundleId + "/publication-runs", login, PRODUCTION).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
            // 403 vóór 404: ook voor een run die niet bestaat.
            send(API + "/publication-runs/999999999/continue", login, null).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
        assertThat(ci.queryForObject("select count(*) from publication_run where bundle_id = ?", Long.class, s.bundleId))
                .isZero();
        assertThat(ci.queryForObject("select status from publication_bundle where id = ?", String.class, s.bundleId))
                .isEqualTo("FROZEN");

        mockMvc.perform(get(API + "/publication-runs/999999999/items").with(withoutPermissions(USER)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        // READ volstaat voor lezen: onbekende run is dan 404.
        mockMvc.perform(get(API + "/publication-runs/999999999/items").with(as(USER, Permission.READ)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PUBLICATION_RUN_NOT_FOUND"));
    }

    // --- foutcodes ------------------------------------------------------------------------------------------------------

    @Test
    void startAndContinueErrorsKeepTheirErrorAndCodeContract() throws Exception {
        Scenario simulationBundle = scenario("SIMB", PublicationTargetMode.SIMULATION, "R1");
        prodisArticle(simulationBundle, "R1", "100", "Boormachine");
        long assembling = bundleService.createBundle(BUNDLE_PREFIX + "ASM-" + SEQUENCE.incrementAndGet() + "-"
                + Long.toString(System.nanoTime(), 36), null, PublicationTargetMode.PRODUCTION, null, null, CREATOR).id();

        send(API + "/bundles/" + simulationBundle.bundleId + "/publication-runs", as(USER, Permission.APPROVE), PRODUCTION)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BUNDLE_TARGET_MODE_NOT_PRODUCTION"))
                .andExpect(jsonPath("$.error").isString());
        send(API + "/bundles/" + assembling + "/publication-runs", as(USER, Permission.APPROVE), PRODUCTION)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BUNDLE_NOT_FROZEN"));
        send(API + "/bundles/999999999/publication-runs", as(USER, Permission.APPROVE), PRODUCTION)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BUNDLE_NOT_FOUND"));
        send(API + "/bundles/" + assembling + "/publication-runs", as(USER, Permission.APPROVE), "{\"targetMode\":\"BOGUS\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PUBLICATION_MODE_UNKNOWN"));
        // De TRIAL_LIBRARY blijft dicht.
        send(API + "/bundles/" + assembling + "/publication-runs", as(USER, Permission.APPROVE),
                "{\"targetMode\":\"TRIAL_LIBRARY\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PUBLICATION_MODE_NOT_ENABLED"));

        // SIMULATION blijft werken en is geen PRODUCTION-run: continue → 409 PUBLICATION_RUN_NOT_PRODUCTION.
        String simulated = send(API + "/bundles/" + simulationBundle.bundleId + "/publication-runs",
                as(USER, Permission.APPROVE), SIMULATION).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SIMULATED")).andExpect(jsonPath("$.simulationOnly").value(true))
                .andExpect(jsonPath("$.writesToProdis").value(false)).andExpect(jsonPath("$.done").value(true))
                .andExpect(jsonPath("$.legacyMasterAtStart").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        long simulationRun = ((Number) JsonPath.read(simulated, "$.id")).longValue();
        send(API + "/publication-runs/" + simulationRun + "/continue", as(USER, Permission.APPROVE), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PUBLICATION_RUN_NOT_PRODUCTION"));
        mockMvc.perform(get(API + "/publication-runs/" + simulationRun + "/items").with(as(USER, Permission.READ)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));

        send(API + "/publication-runs/999999999/continue", as(USER, Permission.APPROVE), null)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PUBLICATION_RUN_NOT_FOUND"));
        mockMvc.perform(get(API + "/publication-runs/" + simulationRun + "/items").param("status", "BOGUS")
                        .with(as(USER, Permission.READ))).andExpect(status().isBadRequest());
        mockMvc.perform(get(API + "/publication-runs/" + simulationRun + "/items").param("size", "0")
                        .with(as(USER, Permission.READ))).andExpect(status().isBadRequest());
        mockMvc.perform(get(API + "/publication-runs/" + simulationRun + "/items").param("page", "-1")
                        .with(as(USER, Permission.READ))).andExpect(status().isBadRequest());
    }

    @Test
    void aRunningProductionRunBlocksOthersCannotBeAbortedAndReportsBusy() throws Exception {
        Scenario first = scenario("SLOT-A", PublicationTargetMode.PRODUCTION, "R1", "R2");
        prodisArticle(first, "R1", "100", "Boormachine");
        prodisArticle(first, "R2", "100", "Schroevendraaier");
        Scenario second = scenario("SLOT-B", PublicationTargetMode.PRODUCTION, "R1");
        prodisArticle(second, "R1", "100", "Boormachine");

        String started = send(API + "/bundles/" + first.bundleId + "/publication-runs", as(USER, Permission.APPROVE),
                PRODUCTION).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHING"))
                .andExpect(jsonPath("$.done").value(false)).andReturn().getResponse().getContentAsString();
        long runId = ((Number) JsonPath.read(started, "$.id")).longValue();

        // Hoogstens één actieve PRODUCTION-run per installatie.
        send(API + "/bundles/" + second.bundleId + "/publication-runs", as(USER, Permission.APPROVE), PRODUCTION)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRODUCTION_RUN_IN_PROGRESS"));
        // Een PUBLISHING-run met verstuurde items wordt nooit afgebroken.
        send(API + "/publication-runs/" + runId + "/abort", as(USER, Permission.APPROVE), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PUBLICATION_RUN_HAS_SENT_ITEMS"));
        // Een andere aanroep houdt de lease: 409 PUBLICATION_RUN_BUSY, niets verwerkt.
        ci.update("update publication_run set lease_owner = 'continue:other', lease_until = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().plusSeconds(120)), runId);
        send(API + "/publication-runs/" + runId + "/continue", as(USER, Permission.APPROVE), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PUBLICATION_RUN_BUSY"));
        ci.update("update publication_run set lease_owner = null, lease_until = null where id = ?", runId);

        send(API + "/publication-runs/" + runId + "/continue", as(USER, Permission.APPROVE), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.done").value(true)).andExpect(jsonPath("$.itemCounts.APPLIED").value(2));
        assertThat(ci.queryForObject("select status from publication_bundle where id = ?", String.class, first.bundleId))
                .isEqualTo("PUBLISHED");
        // Het slot is vrij: de tweede bundel mag nu wel.
        send(API + "/bundles/" + second.bundleId + "/publication-runs", as(USER, Permission.APPROVE), PRODUCTION)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPLIED"));
    }

    // --- helpers ----------------------------------------------------------------------------------------------------------

    private ResultActions send(String url, RequestPostProcessor login, String body) throws Exception {
        var request = post(url).with(login);
        if (body != null) {
            request = request.contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request);
    }

    private record Fixture(long taskId, String unique) {
    }

    private record Scenario(long bundleId, long batchId, String supplier) {
    }

    /**
     * Nulmeting van de referenties aan 100; de prijsronde zet ze op 120 (UPDATE), bevroren in een bundel met de gevraagde
     * doelmodus (zoals {@code PublicationRunProductionTest.scenario}).
     */
    private Scenario scenario(String prefix, PublicationTargetMode mode, String... references) {
        String supplier = ("H" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet()).toUpperCase();
        Fixture fixture = fixture(prefix);
        long first = screenedBatch(fixture, "NULMETING", rows(supplier, "100,00", references));
        baseline.acceptBaseline(first, CREATOR, "Nulmeting");
        long batchId = screenedBatch(fixture, "PRIJSRONDE", rows(supplier, "120,00", references));
        var bundle = bundleService.createBundle(BUNDLE_PREFIX + fixture.unique(), null, mode, null, null, CREATOR);
        bundleService.addBatches(bundle.id(), List.of(batchId), CREATOR);
        decisions.decideGroup(bundle.id(), BundleDecisionKind.APPROVE, DECIDER, "Levering nagekeken",
                new DecisionFilter(null, MutationStatus.AWAITING_APPROVAL, null, null, null));
        freezeService.freeze(bundle.id(), FREEZER, "Prijsronde goedgekeurd");
        return new Scenario(bundle.id(), batchId, supplier);
    }

    private static String[] rows(String supplier, String price, String... references) {
        List<String> rows = new ArrayList<>();
        for (String reference : references) {
            rows.add(supplier + ";G1;" + reference + ";" + price + ";" + description(reference));
        }
        return rows.toArray(String[]::new);
    }

    private static String description(String reference) {
        return switch (reference) {
            case "R1" -> "Boormachine";
            case "R2" -> "Schroevendraaier";
            case "R4" -> "Tang";
            default -> "Artikel " + reference;
        };
    }

    private long screenedBatch(Fixture fixture, String deliveryReference, String... rows) {
        StringBuilder csv = new StringBuilder(HEADER);
        for (String row : rows) {
            csv.append(row).append('\n');
        }
        var delivery = intake.intake(fixture.taskId(), deliveryReference + "-" + SEQUENCE.incrementAndGet(),
                "tester@example.test", null, null, "levering.csv",
                new ByteArrayInputStream(csv.toString().getBytes(StandardCharsets.UTF_8))).delivery();
        long batchId = delivery.batch().batchId();
        screening.screen(batchId);
        // De fixture kent geen valutakolom; nooit EUR aannemen, dus expliciet zetten (zoals de andere publicatietests).
        ci.update("update import_mutation set base_price_currency = 'EUR' where batch_id = ? "
                + "and action_type in ('CREATE', 'UPDATE')", batchId);
        return batchId;
    }

    private Fixture fixture(String prefix) {
        String unique = "P6H" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
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
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, LIBRARY));
        CatalogImportTask task = tasks.saveAndFlush(new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL));
        return new Fixture(task.getId(), unique);
    }

    /** Eén volledig Prodis-artikel (prijscode 0, VKP% 10..50 met consistente prijzen, NL-omschrijving). */
    private void prodisArticle(Scenario s, String reference, String base, String description) {
        long supplierId = ensure("supplier", "number = '" + s.supplier() + "'",
                id -> "insert into supplier (id, number, name_alfa, name) values (" + id + ", '" + s.supplier()
                        + "', 'P6H', 'P6H supplier')");
        long groupId = ensure("supplier_group", "legacy_code = '" + s.supplier() + "|G1|'",
                id -> "insert into supplier_group (id, supplier_id, group_name, legacy_code) values (" + id + ", "
                        + supplierId + ", 'G1', '" + s.supplier() + "|G1|')");
        long catalogId = ensure("supplier_catalog", "supplier_id = " + supplierId,
                id -> "insert into supplier_catalog (id, catalog_name, scope_key, imported_at, status, supplier_id) values ("
                        + id + ", 'Catalog', '" + LIBRARY + "', timestamp '2026-10-01 00:00:00', 'ACTIVE', " + supplierId + ")");
        long article = PRODIS_ID.incrementAndGet();
        owner("insert into catalog_article (id, supplier_id, supplier_reference, supplier_reference_stripped, "
                + "supplier_catalog_id, supplier_group_id) values (" + article + ", " + supplierId + ", '" + reference + "', '"
                + reference + "', " + catalogId + ", " + groupId + ")");
        double basis = Double.parseDouble(base);
        List<Double> prices = new ArrayList<>();
        for (int code = 1; code <= 5; code++) {
            prices.add(BigDecimal.valueOf(basis * code * 10d / 100d).setScale(2, RoundingMode.HALF_UP).doubleValue());
        }
        long price = PRODIS_ID.incrementAndGet();
        owner("insert into catalog_article_price (id, base_price, catalog_article_id, price_code, brut_price_politics, "
                + "purchase_price, brut_price, brut_price_percentage, sales_price_last_updated) values (" + price + ", " + base
                + ", " + article + ", '0', '1', 55.5, " + prices.get(0) + ", 0, timestamp '2026-02-03 04:05:06')");
        for (int code = 1; code <= 5; code++) {
            owner("insert into catalog_article_price_detail (id, code, percentage, price, catalog_article_price_id) values ("
                    + PRODIS_ID.incrementAndGet() + ", '" + code + "', " + (code * 10) + ", " + prices.get(code - 1) + ", "
                    + price + ")");
        }
        owner("insert into catalog_article_description (id, language_id, description, default_language, "
                + "catalog_article_id) values (" + PRODIS_ID.incrementAndGet() + ", 4, '" + description + "', true, "
                + article + ")");
    }

    private long ensure(String table, String where, java.util.function.LongFunction<String> insert) {
        try (Connection owner = ProdisPublicationTestDatabase.owner(); Statement statement = owner.createStatement();
             ResultSet row = statement.executeQuery("select id from " + table + " where " + where)) {
            if (row.next()) {
                return row.getLong(1);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
        long id = PRODIS_ID.incrementAndGet();
        owner(insert.apply(id));
        return id;
    }

    private static void owner(String sql) {
        try (Connection owner = ProdisPublicationTestDatabase.owner(); Statement statement = owner.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
