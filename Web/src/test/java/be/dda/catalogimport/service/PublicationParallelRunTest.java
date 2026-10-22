package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.PublicationBundleRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.BundleDecisionKind;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.MutationStatus;
import be.dda.catalogimport.domain.PublicationBundleStatus;
import be.dda.catalogimport.domain.PublicationTargetMode;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.service.BundleDecisionService.DecisionFilter;
import be.dda.catalogimport.service.PublicationBundleService.BundleReference;
import be.dda.catalogimport.service.PublicationParallelRunService.ParallelRunSummary;
import be.dda.catalogimport.service.PublicationRunService.PublicationRunView;
import be.dda.catalogimport.testsupport.ProdisReferenceTestDatabase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * P2c (design {@code publicatie-prodis-db-direct-design.md} par. 5 en 6; {@code publicatie-prodis-db-design.md} P2;
 * beslissingen 2026-10-22): de parallelrun op een echte {@code SIMULATION}-run — PostgreSQL (CatalogImport) en de
 * H2-Prodis-leesbron van P1 ({@code prodis-reference-test-schema.sql}, alleen lezen via {@code ProdisReferenceJdbc}).
 *
 * <h2>Business rule → wat hier bewezen wordt</h2>
 * <ul>
 *   <li>Elke uitkomst ({@code WOULD_UPDATE|UNCHANGED|CONFLICT|REJECTED|NOT_SENT}) met {@code DERIVED_PRICE_DRIFT}, de
 *       modus bij de start en de tellers; rapport + samenvatting naast het PSIMPORT-artefact; de runview (POST, GET,
 *       lijst) toont de samenvatting.</li>
 *   <li>Niets geschreven: de Prodis-tabellen, de mutaties, de batch, de bronstaat en de bundel zijn erna identiek.</li>
 *   <li>Deterministisch: dezelfde bundel 2× → dezelfde items en tellers (op runId/tijdstempels na).</li>
 *   <li>Onbereikbare leesbron → {@code PARALLEL_RUN_UNAVAILABLE}, geen item, de SIMULATION slaagt zoals voorheen.</li>
 *   <li>Modus PRODIS (ook: geen rij) → vaste waarschuwing, niets geweigerd; onleesbaar → UNKNOWN + waarschuwing.</li>
 * </ul>
 * {@code PRICE_ROUNDING_DIFFERENCE} kan end-to-end nog niet voorkomen (percentages worden pas geschreven met snapshot spec 2,
 * P4b); de telling en rapportering ervan bewijst {@code PublicationParallelRunServiceTest}. Elke test gebruikt een eigen
 * leveranciersnummer: de H2-bron en de database zijn gedeeld.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=50",
        "catalogimport.screening.mutation-chunk-size=50",
        "catalogimport.screening.recovery-on-startup=false",
        "catalogimport.prodis-db.url=" + PublicationParallelRunTest.PRODIS_URL,
        "catalogimport.prodis-db.username=" + ProdisReferenceTestDatabase.USERNAME,
        "catalogimport.prodis-db.password=" + ProdisReferenceTestDatabase.PASSWORD,
        "catalogimport.prodis-db.connect-timeout=PT1S",
        "catalogimport.prodis-db.query-timeout=PT2S"})
@ActiveProfiles("local")
class PublicationParallelRunTest {

    static final String PRODIS_URL =
            "jdbc:h2:mem:prodis-parallel-run;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    private static final ProdisReferenceTestDatabase PRODIS = ProdisReferenceTestDatabase.at(PRODIS_URL);
    private static final long LANGUAGE_NL = PRODIS.insertLanguage("NL");
    private static final List<String> PRODIS_TABLES = List.of("supplier", "supplier_group", "supplier_catalog", "language",
            "system_settings", "catalog_article", "catalog_article_price", "catalog_article_price_detail",
            "catalog_article_description");

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    private static final String CREATOR = "jan.peeters@example.test";
    private static final String FREEZER = "an.janssens@example.test";
    private static final String DECIDER = "piet.willems@example.test";
    private static final String REQUESTER = "els.verhoeven@example.test";
    private static final String LIBRARY = "PSARF050";

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @Autowired
    private DeliveryIntakeService intake;
    @Autowired
    private DeliveryScreeningService screening;
    @Autowired
    private PublicationBundleService bundleService;
    @Autowired
    private BundleDecisionService decisions;
    @Autowired
    private BundleFreezeService freezeService;
    @Autowired
    private PublicationRunService runService;
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
    @Autowired
    private PublicationBundleRepository bundles;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void modeNone() {
        PRODIS.setLegacyMaster("NONE");
    }

    // --- normaal scenario --------------------------------------------------------------------------------------------

    @Test
    void aSimulationRunGetsAParallelRunWithEveryOutcomeTheModeAndTheCounters() throws Exception {
        Scenario scenario = scenario("ALL");

        PublicationRunView view = runService.requestRun(scenario.bundleId(), "SIMULATION", REQUESTER);

        // De SIMULATION zelf is ongewijzigd.
        assertThat(view.status()).isEqualTo("SIMULATED");
        assertThat(view.rowCount()).isEqualTo(6L);
        assertThat(view.simulationOnly()).isTrue();
        assertThat(view.writesToProdis()).isFalse();

        ParallelRunSummary parallel = view.parallelRun();
        assertThat(parallel.status()).isEqualTo(PublicationParallelRunService.STATUS_COMPLETED);
        assertThat(parallel.writesToProdis()).isFalse();
        assertThat(parallel.legacyMaster()).isEqualTo("NONE");
        assertThat(parallel.legacyMasterAvailability()).isEqualTo("VERIFIED");
        assertThat(parallel.legacyMasterWarningCode()).isNull();
        assertThat(parallel.itemCount()).isEqualTo(6L);
        assertThat(parallel.outcomeCounts()).containsExactly(Map.entry("WOULD_UPDATE", 1L),
                Map.entry("UNCHANGED", 2L), Map.entry("CONFLICT", 1L), Map.entry("REJECTED", 1L),
                Map.entry("NOT_SENT", 1L));
        assertThat(parallel.reasonCounts()).containsExactly(Map.entry("ARTICLE_NOT_FOUND", 1L),
                Map.entry("CREATE_NOT_SUPPORTED_IN_V1", 1L), Map.entry("VALUE_CHANGED_IN_PRODIS", 1L));
        assertThat(parallel.noticeCounts()).containsEntry("DERIVED_PRICE_DRIFT", 1L);

        // Per referentie de verwachte uitkomst.
        JsonNode report = report(view.id());
        Map<String, String> outcomes = new LinkedHashMap<>();
        report.path("items").forEach(item -> outcomes.put(item.path("identity").path("supplierReference").asText(),
                item.path("outcome").asText()));
        assertThat(outcomes).containsExactly(Map.entry("R1", "WOULD_UPDATE"), Map.entry("R2", "UNCHANGED"),
                Map.entry("R3", "UNCHANGED"), Map.entry("R4", "CONFLICT"), Map.entry("R5", "REJECTED"),
                Map.entry("R6", "NOT_SENT"));
        JsonNode update = report.path("items").get(0);
        assertThat(update.path("identity").path("groupLegacyCode").asText()).isEqualTo(scenario.supplier() + "|G1|");
        assertThat(update.path("identity").path("scopeKey").asText()).isEqualTo(LIBRARY);
        assertThat(update.path("basePrice").path("expected").asText()).isEqualTo("100");
        assertThat(update.path("target").path("basePrice").asText()).isEqualTo("120");
        assertThat(update.path("target").path("prices").path("5").asText()).isEqualTo("60");
        assertThat(update.path("salesPriceLastUpdated").asBoolean()).isTrue();
        assertThat(report.path("items").get(2).path("notices").findValuesAsText("code")).contains("DERIVED_PRICE_DRIFT");
        assertThat(report.path("items").get(2).path("changes")).isEmpty();
        assertThat(report.path("bundleContentHash").asText()).isEqualTo(view.bundleContentHash());
        assertThat(report.path("snapshotHash").asText()).isEqualTo(view.snapshotHash());

        // Naast het PSIMPORT-artefact, met de bewaarde hash; GET en lijst tonen dezelfde samenvatting.
        String reference = jdbc.queryForObject("select artifact_reference from publication_run where id = ?",
                String.class, view.id());
        Path directory = archiveRoot.resolve(reference).getParent();
        assertThat(Files.exists(directory.resolve(PublicationParallelRunService.REPORT_FILE_NAME))).isTrue();
        assertThat(Files.exists(directory.resolve(PublicationParallelRunService.SUMMARY_FILE_NAME))).isTrue();
        byte[] downloaded;
        try (InputStream in = runService.openParallelRunArtifact(view.id())) {
            downloaded = in.readAllBytes();
        }
        assertThat(sha256(downloaded)).isEqualTo(parallel.reportSha256());
        assertThat((long) downloaded.length).isEqualTo(parallel.reportByteSize());
        assertThat(runService.getRun(view.id()).parallelRun()).isEqualTo(parallel);
        assertThat(runService.listRuns(scenario.bundleId())).extracting(PublicationRunView::parallelRun)
                .containsExactly(parallel);
        // Het PSIMPORT-artefact zelf is onaangeroerd.
        assertThat(sha256(Files.readAllBytes(archiveRoot.resolve(reference)))).isEqualTo(view.artifactSha256());
    }

    @Test
    void nothingIsWrittenToProdisNorToTheMutationsBatchSourceStateOrBundle() throws Exception {
        Scenario scenario = scenario("NOWRITE");
        Map<String, List<Map<String, Object>>> prodisBefore = prodisContent();
        List<Map<String, Object>> mutationsBefore = mutations(scenario.batchId());
        List<Map<String, Object>> batchBefore = comparable(jdbc.queryForList("select * from import_batch where id = ?",
                scenario.batchId()));
        List<Map<String, Object>> sourceStateBefore = jdbc.queryForList(
                "select count(*) as total, coalesce(max(id), 0) as highest from catalog_source_state");

        PublicationRunView view = runService.requestRun(scenario.bundleId(), "SIMULATION", REQUESTER);

        assertThat(view.parallelRun().status()).isEqualTo(PublicationParallelRunService.STATUS_COMPLETED);
        assertThat(view.parallelRun().outcomeCounts()).containsEntry("WOULD_UPDATE", 1L);
        assertThat(prodisContent()).isEqualTo(prodisBefore);
        assertThat(mutations(scenario.batchId())).isEqualTo(mutationsBefore);
        assertThat(comparable(jdbc.queryForList("select * from import_batch where id = ?", scenario.batchId())))
                .isEqualTo(batchBefore);
        assertThat(jdbc.queryForList("select count(*) as total, coalesce(max(id), 0) as highest "
                + "from catalog_source_state")).isEqualTo(sourceStateBefore);
        assertThat(bundles.findById(scenario.bundleId()).orElseThrow().getStatus())
                .isEqualTo(PublicationBundleStatus.FROZEN);
    }

    @Test
    void twoRunsOnTheSameBundleGiveTheSameItemsAndCounters() throws Exception {
        Scenario scenario = scenario("TWICE");

        PublicationRunView first = runService.requestRun(scenario.bundleId(), "SIMULATION", REQUESTER);
        PublicationRunView second = runService.requestRun(scenario.bundleId(), "SIMULATION", REQUESTER);

        assertThat(second.attempt()).isEqualTo(2);
        assertThat(withoutVolatile(report(second.id()))).isEqualTo(withoutVolatile(report(first.id())));
        assertThat(report(first.id()).path("items")).hasSize(6);
        assertThat(second.parallelRun().outcomeCounts()).isEqualTo(first.parallelRun().outcomeCounts());
        assertThat(second.parallelRun().reasonCounts()).isEqualTo(first.parallelRun().reasonCounts());
        assertThat(second.parallelRun().noticeCounts()).isEqualTo(first.parallelRun().noticeCounts());
        assertThat(second.parallelRun().legacyMaster()).isEqualTo(first.parallelRun().legacyMaster());
    }

    // --- leesbron onbereikbaar of modus -------------------------------------------------------------------------------

    @Test
    void anUnreachableSourceSkipsTheParallelRunWhileTheSimulationSucceedsAsBefore() throws Exception {
        Scenario scenario = scenario("DOWN");

        PublicationRunView view;
        PRODIS.breakSource();
        try {
            view = runService.requestRun(scenario.bundleId(), "SIMULATION", REQUESTER);
        } finally {
            PRODIS.restore();
        }

        assertThat(view.status()).isEqualTo("SIMULATED");
        assertThat(view.rowCount()).isEqualTo(6L);
        assertThat(view.failureCode()).isNull();
        ParallelRunSummary parallel = view.parallelRun();
        assertThat(parallel.status()).isEqualTo(PublicationParallelRunService.STATUS_UNAVAILABLE);
        assertThat(parallel.itemCount()).isNull();
        assertThat(parallel.outcomeCounts()).isNull();
        JsonNode report = report(view.id());
        assertThat(report.path("items")).isEmpty();
        assertThat(report.toString()).doesNotContain("ARTICLE_NOT_FOUND");
        assertThat(runService.getRun(view.id()).parallelRun().status())
                .isEqualTo(PublicationParallelRunService.STATUS_UNAVAILABLE);
    }

    @Test
    void modeProdisOrNoSettingsRowIsShownWithTheDatasyncWarningAndNothingIsRefused() {
        Scenario prodis = scenario("MODEP");
        PRODIS.setLegacyMaster("PRODIS");
        ParallelRunSummary inProdis = runService.requestRun(prodis.bundleId(), "SIMULATION", REQUESTER).parallelRun();

        Scenario noRow = scenario("MODEN");
        PRODIS.setLegacyMaster(null);
        ParallelRunSummary withoutRow = runService.requestRun(noRow.bundleId(), "SIMULATION", REQUESTER).parallelRun();

        for (ParallelRunSummary summary : List.of(inProdis, withoutRow)) {
            assertThat(summary.status()).isEqualTo(PublicationParallelRunService.STATUS_COMPLETED);
            assertThat(summary.legacyMaster()).isEqualTo("PRODIS");
            assertThat(summary.legacyMasterWarningCode())
                    .isEqualTo(PublicationParallelRunService.WARNING_LEGACY_MASTER_DATASYNC_REVERTS);
            assertThat(summary.outcomeCounts()).containsEntry("WOULD_UPDATE", 1L).containsEntry("UNCHANGED", 2L);
        }
    }

    @Test
    void anUnreadableModeIsUnknownWithAWarningAndTheParallelRunStillCompletes() {
        Scenario scenario = scenario("MODEU");

        ParallelRunSummary summary;
        PRODIS.breakSystemSettings();
        try {
            summary = runService.requestRun(scenario.bundleId(), "SIMULATION", REQUESTER).parallelRun();
        } finally {
            PRODIS.restoreSystemSettings();
        }

        assertThat(summary.status()).isEqualTo(PublicationParallelRunService.STATUS_COMPLETED);
        assertThat(summary.legacyMaster()).isEqualTo("UNKNOWN");
        assertThat(summary.legacyMasterAvailability()).isEqualTo("UNAVAILABLE");
        assertThat(summary.legacyMasterWarningCode())
                .isEqualTo(PublicationParallelRunService.WARNING_LEGACY_MASTER_UNKNOWN);
        assertThat(summary.itemCount()).isEqualTo(6L);
    }

    // --- scenario ----------------------------------------------------------------------------------------------------

    private record Scenario(long bundleId, long batchId, String supplier) {
    }

    /**
     * Zoals {@code BundleFreezeTest.updateScenario}: een eerste levering (R1..R5 aan 100) wordt als nulmeting aanvaard, de
     * tweede levering (R1..R5 aan 120 → UPDATE 100 → 120, plus R6 → CREATE) komt in de bundel. Prodis:
     * R1 basis 100 (→ WOULD_UPDATE), R2 basis 120 consistent (→ UNCHANGED), R3 basis 120 met scheve VKP-prijzen
     * (→ UNCHANGED + DERIVED_PRICE_DRIFT), R4 basis 77 (→ CONFLICT), R5 bestaat niet (→ REJECTED ARTICLE_NOT_FOUND).
     */
    private Scenario scenario(String prefix) {
        String supplier = ("S" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet()).toUpperCase();
        Fixture fixture = fixture(prefix);
        long first = screenedBatch(fixture, "NULMETING",
                supplier + ";G1;R1;100,00;Boormachine",
                supplier + ";G1;R2;100,00;Schroevendraaier",
                supplier + ";G1;R3;100,00;Hamer",
                supplier + ";G1;R4;100,00;Zaag",
                supplier + ";G1;R5;100,00;Tang");
        baseline.acceptBaseline(first, CREATOR, "Nulmeting");
        long batchId = screenedBatch(fixture, "PRIJSRONDE",
                supplier + ";G1;R1;120,00;Boormachine",
                supplier + ";G1;R2;120,00;Schroevendraaier",
                supplier + ";G1;R3;120,00;Hamer",
                supplier + ";G1;R4;120,00;Zaag",
                supplier + ";G1;R5;120,00;Tang",
                supplier + ";G1;R6;120,00;Beitel");
        BundleReference bundle = bundleService.createBundle("BND-" + fixture.unique(), null,
                PublicationTargetMode.SIMULATION, null, null, CREATOR);
        bundleService.addBatches(bundle.id(), List.of(batchId), CREATOR);
        decisions.decideGroup(bundle.id(), BundleDecisionKind.APPROVE, DECIDER, "Levering nagekeken",
                new DecisionFilter(null, MutationStatus.AWAITING_APPROVAL, null, null, null));
        freezeService.freeze(bundle.id(), FREEZER, "Prijsronde goedgekeurd");

        long prodisSupplier = PRODIS.insertSupplier(supplier, "Leverancier " + supplier);
        long group = PRODIS.insertGroup(prodisSupplier, supplier + "|G1|", "G1", "");
        long catalog = PRODIS.insertCatalog(prodisSupplier, LIBRARY, "50", "CATALOGUS", "ACTIVE");
        prodisArticle(prodisSupplier, group, catalog, "R1", "100", "Boormachine", 1d);
        prodisArticle(prodisSupplier, group, catalog, "R2", "120", "Schroevendraaier", 1d);
        prodisArticle(prodisSupplier, group, catalog, "R3", "120", "Hamer", 1.2d);
        prodisArticle(prodisSupplier, group, catalog, "R4", "77", "Zaag", 1d);
        return new Scenario(bundle.id(), batchId, supplier);
    }

    /** Prijscode 0, bruto-politiek 1 (bruto = VKP1); VKP% 10..50 met prijzen basis × % ÷ {@code skew} (1 = consistent). */
    private static void prodisArticle(long supplierId, long groupId, long catalogId, String reference, String base,
                                      String description, double skew) {
        long article = PRODIS.insertArticle(supplierId, groupId, catalogId, reference, true, false);
        double basis = Double.parseDouble(base);
        List<Double> prices = new ArrayList<>();
        for (int code = 1; code <= 5; code++) {
            prices.add(BigDecimal.valueOf(basis * code * 10d / 100d / skew).setScale(2, RoundingMode.HALF_UP)
                    .doubleValue());
        }
        long price = PRODIS.insertPrice(article, "0", "1", new BigDecimal(base), prices.get(0), 0d);
        for (int code = 1; code <= 5; code++) {
            PRODIS.insertPriceDetail(price, Integer.toString(code), code * 10d, prices.get(code - 1));
        }
        PRODIS.insertDescription(article, LANGUAGE_NL, description);
    }

    private long screenedBatch(Fixture fixture, String deliveryReference, String... rows) {
        StringBuilder csv = new StringBuilder(HEADER);
        for (String row : rows) {
            csv.append(row).append('\n');
        }
        var delivery = intake.intake(fixture.taskId(), deliveryReference, "tester@example.test", null, null,
                "levering.csv",
                new ByteArrayInputStream(csv.toString().getBytes(StandardCharsets.UTF_8))).delivery();
        long batchId = delivery.batch().batchId();
        screening.screen(batchId);
        // Zoals PublicationRunSimulationTest: de fixture kent geen valutakolom; nooit EUR aannemen, dus expliciet zetten.
        jdbc.update("update import_mutation set base_price_currency = 'EUR' where batch_id = ? "
                + "and action_type in ('CREATE', 'UPDATE')", batchId);
        return batchId;
    }

    /** Zelfde fixture als {@code PublicationRunSimulationTest} (omschrijving gemapt, geen prijscomponenten). */
    private Fixture fixture(String prefix) {
        String unique = "PPR" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
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
        CatalogImportTask task = tasks.saveAndFlush(new CatalogImportTask(link, unique + "-taak",
                TaskTriggerType.MANUAL));
        return new Fixture(task.getId(), unique);
    }

    private record Fixture(long taskId, String unique) {
    }

    // --- hulp ----------------------------------------------------------------------------------------------------

    private JsonNode report(long runId) throws IOException {
        try (InputStream in = runService.openParallelRunArtifact(runId)) {
            return JSON.readTree(in);
        }
    }

    private static JsonNode withoutVolatile(JsonNode document) {
        ObjectNode copy = document.deepCopy();
        copy.remove(List.of("runId", "startedAt"));
        ((ObjectNode) copy.path("summary")).remove(List.of("startedAt", "finishedAt"));
        return copy;
    }

    private List<Map<String, Object>> mutations(long batchId) {
        return comparable(jdbc.queryForList("select * from import_mutation where batch_id = ? order by id", batchId));
    }

    /** {@code byte[]}-kolommen (hashes) als hex: {@code Map.equals} vergelijkt arrays anders op referentie. */
    private static List<Map<String, Object>> comparable(List<Map<String, Object>> rows) {
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> values = new LinkedHashMap<>();
            row.forEach((column, value) -> values.put(column,
                    value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : value));
            copy.add(values);
        }
        return copy;
    }

    /** De volledige inhoud van de Prodis-testtabellen, via een eigen verbinding (niet via de alleen-lezen bron). */
    private static Map<String, List<Map<String, Object>>> prodisContent() {
        Map<String, List<Map<String, Object>>> content = new LinkedHashMap<>();
        try (var connection = java.sql.DriverManager.getConnection(PRODIS_URL, ProdisReferenceTestDatabase.USERNAME,
                ProdisReferenceTestDatabase.PASSWORD)) {
            for (String table : PRODIS_TABLES) {
                List<Map<String, Object>> rows = new ArrayList<>();
                try (var statement = connection.createStatement();
                     var result = statement.executeQuery("select * from " + table + " order by id")) {
                    int columns = result.getMetaData().getColumnCount();
                    while (result.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= columns; i++) {
                            row.put(result.getMetaData().getColumnName(i), result.getObject(i));
                        }
                        rows.add(row);
                    }
                }
                content.put(table, rows);
            }
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException(failure);
        }
        return content;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
