package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.ImportMutationRepository;
import be.dda.catalogimport.dao.ProdisPublicationJdbc;
import be.dda.catalogimport.dao.ProdisPublicationSourceDao;
import be.dda.catalogimport.dao.ProdisPublicationWriter;
import be.dda.catalogimport.dao.ProdisReferenceDao;
import be.dda.catalogimport.dao.ProdisReferenceJdbc;
import be.dda.catalogimport.dao.PsimportPreviewDao;
import be.dda.catalogimport.dao.PublicationBundleDao;
import be.dda.catalogimport.dao.PublicationBundleRepository;
import be.dda.catalogimport.dao.PublicationBundleSnapshotDao;
import be.dda.catalogimport.dao.PublicationRunItemDao;
import be.dda.catalogimport.dao.PublicationRunItemRepository;
import be.dda.catalogimport.dao.PublicationRunRepository;
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
import be.dda.catalogimport.service.BundleDecisionService.DecisionFilter;
import be.dda.catalogimport.service.ProdisPublicationRunService.ProductionRunView;
import be.dda.catalogimport.service.ProdisPublicationRunService.Settings;
import be.dda.catalogimport.service.PublicationBundleService.BundleReference;
import be.dda.catalogimport.testsupport.ProdisPublicationTestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * P5c ({@code docs/design/publicatie-prodis-db-direct-design.md} par. 2.4, 5, 6 rij P5c; {@code publicatie-prodis-db-design.md}
 * par. 2; beslissingen 2026-10-22 Q3, P4a, P5b): de orchestratie van een PRODUCTION-run end-to-end — echte levering,
 * screening, nulmeting, bundel, bevriezen en snapshot in de lokale CI-database; schrijven als de testrol in
 * {@code prodis_pubtest} (guard: {@link ProdisPublicationTestDatabase}; de echte {@code Prodis} wordt nooit beschreven).
 *
 * <h2>Business rule → wat hier bewezen wordt</h2>
 * <ul>
 *   <li>Normaal pad: start (items, PUBLISHING, modus) → APPLIED/UNCHANGED → run APPLIED, bundel PUBLISHED, slot vrij.</li>
 *   <li>REJECTED/CONFLICT/NOT_SENT → bundel PARTIALLY_PUBLISHED; mutaties terug READY_FOR_PUBLICATION; niet annuleerbaar.</li>
 *   <li>Q3: bronstaat (state_origin PUBLISHED, prijs, dagwaarde) enkel bij APPLIED/UNCHANGED; de volgende identieke levering
 *       stelt de gepubliceerde mutatie niet opnieuw voor, de geweigerde wel. Gewijzigde bronstaat → niets overschreven.</li>
 *   <li>Geen dubbele toepassing: tweede start/continue, crash na SENT → overname na verlopen lease → één toepassing.</li>
 *   <li>Tweede gelijktijdige continue → 409 PUBLICATION_RUN_BUSY; vlag uit / niet geconfigureerd / startcontroles → 409.</li>
 *   <li>Begrenzing: herhaalde PRODIS_NOT_APPLIED → REJECTED PRODIS_NOT_APPLIED_REPEATEDLY, mutatie terug.</li>
 *   <li>Afbreken van een PUBLISHING-run → 409 PUBLICATION_RUN_HAS_SENT_ITEMS.</li>
 * </ul>
 * Hoogstens één actieve PRODUCTION-run per installatie geldt ook voor de gedeelde testdatabase: elke test sluit zijn runs
 * af ({@link #closeActiveRuns()}).
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=50",
        "catalogimport.screening.mutation-chunk-size=50",
        "catalogimport.screening.recovery-on-startup=false"})
@ActiveProfiles("local")
class PublicationRunProductionTest {

    private static final String SOURCE_ID = "ci-p5c-test";
    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    private static final String CREATOR = "jan.peeters@example.test";
    private static final String FREEZER = "an.janssens@example.test";
    private static final String DECIDER = "piet.willems@example.test";
    private static final ActorIdentity PUBLISHER = new ActorIdentity("els.verhoeven@example.test", "kc-subject-p5c");
    private static final String LIBRARY = "PSARF050";
    private static final String BUNDLE_PREFIX = "BND-P5C-";
    private static final List<String> PRODIS_TABLES = List.of("catalog_article_description", "catalog_article_price_detail",
            "catalog_article_price", "catalog_article", "system_settings", "language", "supplier_catalog", "supplier_group",
            "supplier");
    private static final List<String> WRITABLE = List.of("catalog_article_price", "catalog_article_price_detail",
            "catalog_article_description");
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final AtomicLong PRODIS_ID = new AtomicLong(5000);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static ProdisPublicationTestDatabase database;
    private static ProdisPublicationJdbc prodis;
    private static ProdisReferenceJdbc prodisReader;

    @TempDir
    static java.nio.file.Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

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
    private BundleCancellationService cancellation;
    @Autowired
    private PublicationRunService simulationRuns;
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
    private PublicationRunRepository runs;
    @Autowired
    private PublicationRunItemRepository items;
    @Autowired
    private PublicationRunItemDao itemDao;
    @Autowired
    private PublicationBundleRepository bundles;
    @Autowired
    private PublicationBundleDao bundleDao;
    @Autowired
    private PublicationBundleSnapshotDao snapshotDao;
    @Autowired
    private PsimportPreviewDao previewDao;
    @Autowired
    private ProdisPublicationSourceDao sourceDao;
    @Autowired
    private ImportMutationRepository mutations;
    @Autowired
    private PublishedSourceStateRecorder sourceStateRecorder;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate ci;

    private final MutableClock clock = new MutableClock();

    @BeforeAll
    static void prepareProdis() throws SQLException {
        database = ProdisPublicationTestDatabase.prepare();
        prodis = ProdisPublicationTestDatabase.publicationJdbc(Duration.ofSeconds(30), 4);
        PGSimpleDataSource reader = new PGSimpleDataSource();
        reader.setURL(ProdisPublicationTestDatabase.URL);
        reader.setUser(ProdisPublicationTestDatabase.OWNER);
        reader.setPassword(ProdisPublicationTestDatabase.OWNER_PASSWORD);
        prodisReader = ProdisReferenceJdbc.of(reader, 5);
        try (Connection owner = ProdisPublicationTestDatabase.owner(); Statement statement = owner.createStatement()) {
            statement.execute("drop table if exists pubtest_write_log");
            statement.execute("create table pubtest_write_log (id bigserial primary key, tx bigint not null, "
                    + "tbl text not null, row_id bigint not null)");
            statement.execute("create or replace function pubtest_log_update() returns trigger language plpgsql "
                    + "security definer set search_path = public as $$ begin insert into pubtest_write_log (tx, tbl, row_id) "
                    + "values (txid_current(), tg_table_name, new.id); return new; end $$");
            for (String table : WRITABLE) {
                statement.execute("create trigger pubtest_log after update on " + table
                        + " for each row execute function pubtest_log_update()");
            }
        }
    }

    @AfterAll
    static void close() throws Exception {
        try (Connection owner = ProdisPublicationTestDatabase.owner(); Statement statement = owner.createStatement()) {
            for (String table : WRITABLE) {
                statement.execute("drop trigger if exists pubtest_log on " + table);
            }
            statement.execute("drop function if exists pubtest_log_update()");
            statement.execute("drop table if exists pubtest_write_log");
            for (String grant : database.roleScript().grants()) {
                statement.execute(grant);
            }
        }
        if (prodis != null) {
            prodis.close();
        }
        if (prodisReader != null) {
            prodisReader.close();
        }
    }

    @BeforeEach
    void cleanProdis() {
        closeActiveRuns();
        StringBuilder truncate = new StringBuilder("truncate ").append(String.join(", ", PRODIS_TABLES))
                .append(", pubtest_write_log cascade");
        owner(truncate.toString());
        owner("insert into language (id, code, txt) values (4, 'NL', 'Nederlands')");
        owner("insert into system_settings (id, legacy_master) values (1, 'NONE')");
        clock.set(Instant.now());
    }

    /** Sluit elke actieve PRODUCTION-run van deze testklasse af (enkel bundels met {@value #BUNDLE_PREFIX}). */
    @AfterEach
    void closeActiveRuns() {
        ci.update("update publication_run set status = 'FAILED', failure_code = 'TEST_P5C_CLEANUP', finished_at = now(), "
                + "active_marker = null, lease_owner = null, lease_until = null where target_mode = 'PRODUCTION' "
                + "and active_marker is not null and bundle_id in (select id from publication_bundle "
                + "where bundle_reference like '" + BUNDLE_PREFIX + "%')");
    }

    // --- normaal scenario -----------------------------------------------------------------------------------------

    @Test
    void aProductionRunPublishesEveryItemFinishesAppliedAndTheBundleIsPublished() throws Exception {
        Scenario s = scenario("NORMAL", "R1", "R2");
        prodisArticle(s, "R1", "100", "Boormachine");
        prodisArticle(s, "R2", "120", "Schroevendraaier");

        ProductionRunView view = service().startProductionRun(s.bundleId(), PUBLISHER);

        assertThat(view.status()).isEqualTo("APPLIED");
        assertThat(view.finished()).isTrue();
        assertThat(view.bundleStatus()).isEqualTo("PUBLISHED");
        assertThat(view.itemCount()).isEqualTo(2L);
        assertThat(view.itemCounts()).containsExactly(Map.entry("APPLIED", 1L), Map.entry("UNCHANGED", 1L));
        assertThat(view.openItemCount()).isZero();
        assertThat(view.legacyMasterAtStart()).isEqualTo("NONE");
        assertThat(view.legacyMasterWarningCode()).isNull();
        assertThat(view.lastChunk().processed()).isEqualTo(2);
        assertThat(view.lastChunk().legacyMaster()).isEqualTo("NONE");

        // Run: terminaal, marker vrij, geen lease; tellers en modus vastgelegd.
        Map<String, Object> run = ci.queryForMap("select * from publication_run where id = ?", view.runId());
        assertThat(run.get("active_marker")).isNull();
        assertThat(run.get("finished_at")).isNotNull();
        assertThat(run.get("lease_owner")).isNull();
        assertThat(run.get("legacy_master_at_start")).isEqualTo("NONE");
        assertThat(run.get("row_count")).isEqualTo(2L);
        assertThat(run.get("incomplete_row_count")).isEqualTo(0L);
        assertThat(run.get("requested_by")).isEqualTo(PUBLISHER.username());
        assertThat(run.get("requested_by_subject")).isEqualTo(PUBLISHER.subject());
        assertThat(run.get("idempotency_key")).isEqualTo("run:" + s.bundleId() + ":PRODUCTION:1");

        // Items: sleutel <source-id>:mut:<id>:pa:1, verzoek = projectie, verstuurd door de publisher.
        for (Map<String, Object> item : itemRows(view.runId())) {
            long mutationId = ((Number) item.get("mutation_id")).longValue();
            assertThat(item.get("idempotency_key")).isEqualTo(SOURCE_ID + ":mut:" + mutationId + ":pa:1");
            assertThat(item.get("publication_attempt")).isEqualTo(1);
            assertThat(item.get("request_hash"))
                    .isEqualTo(ProdisPublicationRequestJson.sha256Hex((String) item.get("request_json")));
            assertThat(item.get("sent_by")).isEqualTo(PUBLISHER.username());
            assertThat(item.get("legacy_master")).isEqualTo("NONE");
            assertThat(item.get("send_count")).isEqualTo(1);
            assertThat(JSON.readTree((String) item.get("result_json")).path("sourceState").asText()).isEqualTo("UPDATED");
        }

        // Prodis: R1 100 → 120 met VKP herberekend; R2 ongemoeid.
        assertThat(prodisBasePrice(s, "R1")).isEqualByComparingTo("120");
        assertThat(prodisBasePrice(s, "R2")).isEqualByComparingTo("120");
        assertThat(writtenRows()).containsOnlyKeys("catalog_article_price", "catalog_article_price_detail");

        // Mutaties PUBLISHED; bundel niet meer annuleerbaar.
        assertThat(mutationStatuses(s.batchId())).containsOnly(Map.entry("R1", "PUBLISHED"), Map.entry("R2", "PUBLISHED"));
        assertThatThrownBy(() -> cancellation.cancel(s.bundleId(), FREEZER, "te laat"))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).getCode())
                .isEqualTo(BundleCancellationService.CODE_BUNDLE_NOT_CANCELLABLE);
    }

    @Test
    void rejectedConflictAndNotSentItemsLeaveTheBundlePartiallyPublishedAndTheirMutationsReady() throws Exception {
        Scenario s = scenario("PARTIAL", "R1", "R3", "R4", "+R6");
        owner("update system_settings set legacy_master = 'PRODIS'");
        prodisArticle(s, "R1", "100", "Boormachine");
        prodisArticle(s, "R3", "77", "Zaag");
        // R4 bestaat niet in Prodis (REJECTED ARTICLE_NOT_FOUND); R6 is een CREATE (NOT_SENT in v1).
        // Publiceren op een latere kalenderdag dan de nulmeting: anders wint de dagwaarde van de nulmeting (A16).
        clock.set(Instant.now().plus(Duration.ofDays(2)));

        ProductionRunView view = service().startProductionRun(s.bundleId(), PUBLISHER);

        assertThat(view.status()).isEqualTo("APPLIED");
        assertThat(view.bundleStatus()).isEqualTo("PARTIALLY_PUBLISHED");
        assertThat(view.itemCounts()).containsOnly(Map.entry("APPLIED", 1L), Map.entry("CONFLICT", 1L),
                Map.entry("REJECTED", 1L), Map.entry("NOT_SENT", 1L));
        // Modus PRODIS: getoond met de vaste waarschuwing, niets geweigerd (P-V1).
        assertThat(view.legacyMasterAtStart()).isEqualTo("PRODIS");
        assertThat(view.legacyMasterWarningCode()).isEqualTo("LEGACY_MASTER_DATASYNC_REVERTS");
        assertThat(itemsByReference(view.runId())).containsOnly(Map.entry("R1", "APPLIED"), Map.entry("R3", "CONFLICT"),
                Map.entry("R4", "REJECTED"), Map.entry("R6", "NOT_SENT"));

        assertThat(mutationStatuses(s.batchId())).containsOnly(Map.entry("R1", "PUBLISHED"),
                Map.entry("R3", "READY_FOR_PUBLICATION"), Map.entry("R4", "READY_FOR_PUBLICATION"),
                Map.entry("R6", "READY_FOR_PUBLICATION"));
        assertThat(prodisBasePrice(s, "R3")).isEqualByComparingTo("77");

        // Q3: bronstaat enkel voor R1.
        assertThat(sourceState(s, "R1")).containsEntry("state_origin", "PUBLISHED");
        assertThat((BigDecimal) sourceState(s, "R1").get("base_price")).isEqualByComparingTo("120");
        for (String untouched : List.of("R3", "R4")) {
            assertThat(sourceState(s, untouched)).as(untouched).containsEntry("state_origin", "BASELINE_ACCEPTED");
            assertThat((BigDecimal) sourceState(s, untouched).get("base_price")).isEqualByComparingTo("100");
        }
        assertThat(ci.queryForObject("select count(*) from catalog_source_state where import_link_id = ? "
                + "and identity_supplier_reference = 'R6'", Long.class, s.linkId())).isZero();
        assertThat(observationCount(s, "R1")).isEqualTo(2L); // nulmeting + publicatie
        assertThat(observationCount(s, "R3")).isEqualTo(1L);

        assertThatThrownBy(() -> cancellation.cancel(s.bundleId(), FREEZER, "te laat"))
                .isInstanceOf(ConflictException.class);

        // De volgende identieke levering: R1 levert niets meer op, R3/R4 opnieuw een UPDATE, R6 opnieuw een CREATE.
        long next = screenedBatch(s.fixture(), "VOLGENDE", rows(s.supplier(), "120,00", "R1", "R3", "R4", "+R6"));
        assertThat(actionsByReference(next)).containsOnly(Map.entry("R3", "UPDATE"), Map.entry("R4", "UPDATE"),
                Map.entry("R6", "CREATE"));
        assertThat(ci.queryForObject("select classification from import_candidate_stage s "
                + "where s.batch_id = ? and s.identity_supplier_reference = 'R1'", String.class, next))
                .isEqualTo("UNCHANGED");
    }

    @Test
    void theNextIdenticalDeliveryProposesNothingForAPublishedBundle() {
        Scenario s = scenario("NEXT", "R1", "R2");
        prodisArticle(s, "R1", "100", "Boormachine");
        prodisArticle(s, "R2", "100", "Schroevendraaier");
        service().startProductionRun(s.bundleId(), PUBLISHER);

        Map<String, Object> state = sourceState(s, "R1");
        byte[] stagedFingerprint = ci.queryForObject("select combined_fingerprint from import_candidate_stage "
                + "where batch_id = ? and identity_supplier_reference = 'R1'", byte[].class, s.batchId());
        assertThat((byte[]) state.get("combined_fingerprint")).isEqualTo(stagedFingerprint);
        assertThat(state.get("last_change_batch_id")).isEqualTo(s.batchId());
        assertThat(state.get("accepted_by")).isEqualTo(PUBLISHER.username());

        long next = screenedBatch(s.fixture(), "VOLGENDE", rows(s.supplier(), "120,00", "R1", "R2"));
        assertThat(actionsByReference(next)).isEmpty();
    }

    // --- geen dubbele toepassing ------------------------------------------------------------------------------------

    @Test
    void aSecondStartOrContinueOnAFinishedRunAppliesNothingTwice() throws Exception {
        Scenario s = scenario("TWICE", "R1");
        prodisArticle(s, "R1", "100", "Boormachine");
        ProdisPublicationRunService service = service();
        ProductionRunView first = service.startProductionRun(s.bundleId(), PUBLISHER);
        assertThat(first.status()).isEqualTo("APPLIED");
        Object written = prodisValue("select last_modified_date from catalog_article_price where id = "
                + s.prodisPriceId("R1"));
        long writes = writeCount();

        assertConflict(() -> service.startProductionRun(s.bundleId(), PUBLISHER),
                ProdisPublicationRunService.CODE_BUNDLE_NOT_FROZEN);
        ProductionRunView again = service.continueRun(first.runId(), PUBLISHER);
        assertThat(again.status()).isEqualTo("APPLIED");
        assertThat(again.lastChunk()).isNull();
        long itemId = itemIds(first.runId()).get(0);
        assertThat(publication().processItem(itemId, PUBLISHER).prodisContacted()).isFalse();

        assertThat(writeCount()).isEqualTo(writes);
        assertThat(prodisValue("select last_modified_date from catalog_article_price where id = " + s.prodisPriceId("R1")))
                .isEqualTo(written);
        assertThat(ci.queryForObject("select count(*) from publication_run where bundle_id = ?", Long.class,
                s.bundleId())).isEqualTo(1L);
        assertThat(ci.queryForObject("select count(*) from publication_run_item where run_id = ?", Long.class,
                first.runId())).isEqualTo(1L);
    }

    @Test
    void aCrashAfterSentIsTakenOverAfterTheLeaseExpiredAndAppliedExactlyOnce() throws Exception {
        Scenario s = scenario("CRASH", "R1", "R2");
        prodisArticle(s, "R1", "100", "Boormachine");
        prodisArticle(s, "R2", "100", "Schroevendraaier");
        ProdisPublicationRunService service = service(settings(1, 5));

        ProductionRunView started = service.startProductionRun(s.bundleId(), PUBLISHER);
        assertThat(started.status()).isEqualTo("PUBLISHING");
        assertThat(started.itemCounts()).containsOnly(Map.entry("APPLIED", 1L), Map.entry("PENDING", 1L));
        long r2 = itemIdOf(started.runId(), "R2");

        // Een andere aanroep neemt de lease en crasht ná de Prodis-commit, vóór CatalogImport iets vastlegt: item SENT.
        ci.update("update publication_run set lease_owner = 'continue:crashed', lease_until = ? where id = ?",
                java.sql.Timestamp.from(clock.instant().plus(Duration.ofMinutes(5))), started.runId());
        // De tweede klokaanroep is die van CI-transactie 2: ze faalt NA de Prodis-commit.
        assertThatThrownBy(() -> publication(new FailingClock(2)).processItem(r2, PUBLISHER))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("simulated crash");
        assertThat(itemStatus(r2)).isEqualTo("RESULT_UNKNOWN");
        // Zoals een proces dat hard stopte: ook RESULT_UNKNOWN werd nooit vastgelegd.
        ci.update("update publication_run_item set status = 'SENT' where id = ?", r2);
        assertThat(prodisBasePrice(s, "R2")).isEqualByComparingTo("120");
        long writesAfterCrash = writeCount();

        // Zolang de lease geldt: 409 PUBLICATION_RUN_BUSY, niets verwerkt.
        assertConflict(() -> service.continueRun(started.runId(), PUBLISHER),
                ProdisPublicationRunService.CODE_PUBLICATION_RUN_BUSY);
        assertThat(itemStatus(r2)).isEqualTo("SENT");

        // Lease verlopen: overname; SENT → RESULT_UNKNOWN → opnieuw met dezelfde bytes → UNCHANGED, niets dubbel.
        clock.advance(Duration.ofMinutes(6));
        ProductionRunView resumed = service.continueRun(started.runId(), PUBLISHER);
        assertThat(resumed.lastChunk().recoveredSent()).isEqualTo(1);
        assertThat(resumed.status()).isEqualTo("APPLIED");
        assertThat(resumed.bundleStatus()).isEqualTo("PUBLISHED");
        Map<String, Object> item = ci.queryForMap("select * from publication_run_item where id = ?", r2);
        assertThat(item.get("status")).isEqualTo("UNCHANGED");
        assertThat(item.get("last_error_code")).isEqualTo(ProdisPublicationRunService.ERROR_LEASE_EXPIRED_WHILE_SENT);
        assertThat(item.get("send_count")).isEqualTo(2);
        assertThat(writeCount()).isEqualTo(writesAfterCrash);
        assertThat(prodisBasePrice(s, "R2")).isEqualByComparingTo("120");
        // De CI-transactie van de crash was teruggedraaid (ook de bronstaat): de overname legt ze alsnog vast.
        assertThat(sourceState(s, "R2")).containsEntry("state_origin", "PUBLISHED");
        assertThat(mutationStatuses(s.batchId())).containsOnly(Map.entry("R1", "PUBLISHED"), Map.entry("R2", "PUBLISHED"));
    }

    @Test
    void aSecondConcurrentContinueIsBusyAndProcessesNothing() {
        Scenario s = scenario("BUSY", "R1", "R2");
        prodisArticle(s, "R1", "100", "Boormachine");
        prodisArticle(s, "R2", "100", "Schroevendraaier");
        ProdisPublicationRunService service = service(settings(1, 5));
        ProductionRunView started = service.startProductionRun(s.bundleId(), PUBLISHER);
        ci.update("update publication_run set lease_owner = 'continue:other', lease_until = ? where id = ?",
                java.sql.Timestamp.from(clock.instant().plus(Duration.ofMinutes(1))), started.runId());

        assertConflict(() -> service.continueRun(started.runId(), PUBLISHER),
                ProdisPublicationRunService.CODE_PUBLICATION_RUN_BUSY);
        assertThat(itemStatus(itemIdOf(started.runId(), "R2"))).isEqualTo("PENDING");
        assertThat(ci.queryForObject("select lease_owner from publication_run where id = ?", String.class,
                started.runId())).isEqualTo("continue:other");

        // Twee echt gelijktijdige continues: hoogstens één neemt de lease (de andere 409 of de afgeronde run).
        ci.update("update publication_run set lease_owner = null, lease_until = null where id = ?", started.runId());
        List<Object> outcomes = java.util.Collections.synchronizedList(new ArrayList<>());
        Thread[] threads = new Thread[2];
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                try {
                    go.await();
                    outcomes.add(service.continueRun(started.runId(), PUBLISHER));
                } catch (ConflictException conflict) {
                    outcomes.add(conflict.getCode());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            threads[i].start();
        }
        go.countDown();
        for (Thread thread : threads) {
            try {
                thread.join(60_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        assertThat(outcomes).hasSize(2);
        long processed = outcomes.stream().filter(o -> o instanceof ProductionRunView v && v.lastChunk() != null)
                .mapToInt(o -> ((ProductionRunView) o).lastChunk().processed()).sum();
        assertThat(processed).isEqualTo(1);
        assertThat(itemStatus(itemIdOf(started.runId(), "R2"))).isEqualTo("APPLIED");
        assertThat(ci.queryForObject("select status from publication_run where id = ?", String.class, started.runId()))
                .isEqualTo("APPLIED");
    }

    // --- slot, vlag, configuratie, startcontroles ---------------------------------------------------------------------

    @Test
    void aFinishedRunReleasesTheSlotAndAnActiveRunBlocksEveryOtherProductionRun() {
        Scenario first = scenario("SLOT-A", "R1", "R2");
        Scenario second = scenario("SLOT-B", "R1");
        prodisArticle(first, "R1", "100", "Boormachine");
        prodisArticle(first, "R2", "100", "Schroevendraaier");
        prodisArticle(second, "R1", "100", "Boormachine");
        ProdisPublicationRunService service = service(settings(1, 5));

        ProductionRunView active = service.startProductionRun(first.bundleId(), PUBLISHER);
        assertThat(active.status()).isEqualTo("PUBLISHING");
        assertConflict(() -> service.startProductionRun(second.bundleId(), PUBLISHER),
                ProdisPublicationRunService.CODE_PRODUCTION_RUN_IN_PROGRESS);
        assertThat(bundleStatus(second.bundleId())).isEqualTo("FROZEN");
        // Afbreken kan niet: de run heeft items verstuurd.
        assertConflict(() -> simulationRuns.abortRun(active.runId(), ActorIdentity.unverified(FREEZER)),
                PublicationRunService.CODE_RUN_HAS_SENT_ITEMS);

        assertThat(service.continueRun(active.runId(), PUBLISHER).status()).isEqualTo("APPLIED");
        ProductionRunView next = service.startProductionRun(second.bundleId(), PUBLISHER);
        assertThat(next.status()).isEqualTo("APPLIED");
        assertThat(next.bundleStatus()).isEqualTo("PUBLISHED");
    }

    @Test
    void theFlagOffOrAMissingWriteConnectionRefusesWithoutCreatingARun() {
        Scenario s = scenario("FLAG", "R1");
        prodisArticle(s, "R1", "100", "Boormachine");

        ProdisPublicationRunService off = service(new Settings(false, null, 200, Duration.ofMinutes(5), 5), writer(prodis));
        assertConflict(() -> off.startProductionRun(s.bundleId(), PUBLISHER),
                ProdisPublicationRunService.CODE_PUBLICATION_MODE_NOT_ENABLED);
        assertConflict(() -> off.continueRun(1L, PUBLISHER), ProdisPublicationRunService.CODE_PUBLICATION_MODE_NOT_ENABLED);

        ProdisPublicationRunService unconfigured = service(settings(200, 5), writer(null));
        assertConflict(() -> unconfigured.startProductionRun(s.bundleId(), PUBLISHER),
                ProdisPublicationWriteNotConfiguredException.CODE);

        assertThat(ci.queryForObject("select count(*) from publication_run where bundle_id = ?", Long.class,
                s.bundleId())).isZero();
        assertThat(bundleStatus(s.bundleId())).isEqualTo("FROZEN");
        assertThat(writeCount()).isZero();
    }

    @Test
    void theSettingsFailFastAndTheSourceIdIsRequiredWhenEnabled() {
        assertThatThrownBy(() -> new Settings(true, " ", 200, Duration.ofMinutes(5), 5))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("source-id is required");
        assertThatThrownBy(() -> new Settings(true, "bad:id", 200, Duration.ofMinutes(5), 5))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new Settings(true, "x".repeat(101), 200, Duration.ofMinutes(5), 5))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new Settings(true, SOURCE_ID, 0, Duration.ofMinutes(5), 5))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new Settings(true, SOURCE_ID, 1001, Duration.ofMinutes(5), 5))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new Settings(true, SOURCE_ID, 200, Duration.ZERO, 5))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new Settings(true, SOURCE_ID, 200, Duration.ofMinutes(5), 0))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Settings.parseLease("vijf minuten")).isInstanceOf(IllegalStateException.class);
        // Uit en zonder source-id: geldig (de applicatie start, publiceren geeft 409).
        assertThat(new Settings(false, "", 200, Duration.ofMinutes(5), 5).sourceId()).isNull();
    }

    @Test
    void theStartChecksRefuseBeforeAnythingIsWritten() {
        ProdisPublicationRunService service = service();

        Scenario simulation = scenario("MODE", PublicationTargetMode.SIMULATION, "R1");
        assertConflict(() -> service.startProductionRun(simulation.bundleId(), PUBLISHER),
                ProdisPublicationRunService.CODE_BUNDLE_TARGET_MODE_NOT_PRODUCTION);

        Scenario notSnapshotted = scenario("NOSNAP", "R1");
        ci.update("update publication_bundle set snapshot_hash = null where id = ?", notSnapshotted.bundleId());
        assertConflict(() -> service.startProductionRun(notSnapshotted.bundleId(), PUBLISHER),
                ProdisPublicationRunService.CODE_BUNDLE_NOT_SNAPSHOTTED);

        Scenario changed = scenario("CHANGED", "R1");
        ci.update("update publication_bundle_snapshot set description = 'iets anders' where bundle_id = ?",
                changed.bundleId());
        assertConflict(() -> service.startProductionRun(changed.bundleId(), PUBLISHER),
                ProdisPublicationRunService.CODE_BUNDLE_CONTENT_CHANGED_SINCE_FREEZE);

        // Een mutatie zonder munt bevroren: de rij is onvolledig (zelfde definitie als de SIMULATION-run).
        Scenario incomplete = scenario("INCOMPLETE", "R1", "R2");
        ci.update("update import_mutation set base_price_currency = null where batch_id = ? "
                + "and identity_supplier_reference = 'R2'", incomplete.batchId());
        assertConflict(() -> service.startProductionRun(incomplete.bundleId(), PUBLISHER),
                ProdisPublicationRunService.CODE_BUNDLE_HAS_INCOMPLETE_ROWS);

        assertThatThrownBy(() -> service.startProductionRun(-1L, PUBLISHER)).isInstanceOf(NotFoundException.class);
        long assembling = assemblingBundle();
        assertConflict(() -> service.startProductionRun(assembling, PUBLISHER),
                ProdisPublicationRunService.CODE_BUNDLE_NOT_FROZEN);

        for (Scenario scenario : List.of(simulation, notSnapshotted, changed, incomplete)) {
            assertThat(ci.queryForObject("select count(*) from publication_run where bundle_id = ?", Long.class,
                    scenario.bundleId())).isZero();
            assertThat(bundleStatus(scenario.bundleId())).isEqualTo("FROZEN");
        }
        assertThat(writeCount()).isZero();
    }

    // --- bronstaat ------------------------------------------------------------------------------------------------------

    @Test
    void aSourceStateChangedSinceScreeningIsNeverOverwrittenAndTheItemSaysSo() throws Exception {
        Scenario s = scenario("STALE", "R1");
        prodisArticle(s, "R1", "100", "Boormachine");
        // Een andere aanvaarding wijzigde de bronstaat van R1 na de screening.
        ci.update("update catalog_source_state set combined_fingerprint = ? where import_link_id = ? "
                + "and identity_supplier_reference = 'R1'", new byte[32], s.linkId());

        ProductionRunView view = service().startProductionRun(s.bundleId(), PUBLISHER);

        assertThat(view.itemCounts()).containsOnly(Map.entry("APPLIED", 1L));
        assertThat(view.bundleStatus()).isEqualTo("PUBLISHED");
        Map<String, Object> item = itemRows(view.runId()).get(0);
        assertThat(JSON.readTree((String) item.get("result_json")).path("sourceState").asText())
                .isEqualTo("NOT_UPDATED_SOURCE_STATE_CHANGED");
        assertThat(JSON.readTree((String) item.get("warnings_json")).findValuesAsText("code"))
                .contains(PublishedSourceStateRecorder.NOTICE_SOURCE_STATE_NOT_UPDATED);
        Map<String, Object> state = sourceState(s, "R1");
        assertThat(state.get("combined_fingerprint")).isEqualTo(new byte[32]);
        assertThat(state).containsEntry("state_origin", "BASELINE_ACCEPTED");
        assertThat((BigDecimal) state.get("base_price")).isEqualByComparingTo("100");
        assertThat(observationCount(s, "R1")).isEqualTo(1L);
    }

    // --- begrenzing herhaalde fouten ------------------------------------------------------------------------------------

    @Test
    void repeatedNotAppliedAttemptsEndRejectedAndTheMutationIsReadyAgain() throws Exception {
        Scenario s = scenario("REPEAT", "R1", "R2");
        prodisArticle(s, "R1", "100", "Boormachine");
        prodisArticle(s, "R2", "100", "Schroevendraaier");
        ProdisPublicationRunService service = service(settings(200, 2));
        // De testrol mag de prijsrij niet meer vergrendelen: elke poging faalt vóór de commit (42501) → zeker niet toegepast.
        owner("revoke update on catalog_article_price from " + ProdisPublicationTestDatabase.ROLE);
        try {
            ProductionRunView first = service.startProductionRun(s.bundleId(), PUBLISHER);
            assertThat(first.status()).isEqualTo("PUBLISHING");
            assertThat(first.itemCounts()).containsOnly(Map.entry("PENDING", 2L));
            long r1 = itemIdOf(first.runId(), "R1");
            long r2 = itemIdOf(first.runId(), "R2");
            assertThat(ci.queryForObject("select last_error_code from publication_run_item where id = ?", String.class, r1))
                    .isEqualTo("PRODIS_NOT_APPLIED:42501");
            assertThat(mutationStatuses(s.batchId())).containsOnly(Map.entry("R1", "IN_PROGRESS"),
                    Map.entry("R2", "IN_PROGRESS"));
            // R2 had ooit een poging met onbekende uitkomst (send_count > not_applied_count).
            ci.update("update publication_run_item set send_count = send_count + 1 where id = ?", r2);

            ProductionRunView second = service.continueRun(first.runId(), PUBLISHER);

            assertThat(second.lastChunk().rejectedRepeatedly()).isEqualTo(2);
            assertThat(second.status()).isEqualTo("APPLIED");
            assertThat(second.bundleStatus()).isEqualTo("PARTIALLY_PUBLISHED");
            Map<String, Object> rejected = ci.queryForMap("select * from publication_run_item where id = ?", r1);
            assertThat(rejected.get("status")).isEqualTo("REJECTED");
            assertThat(rejected.get("outcome")).isEqualTo("REJECTED");
            assertThat(rejected.get("reason_code")).isEqualTo(ProdisPublicationRunService.REASON_PRODIS_NOT_APPLIED_REPEATEDLY);
            assertThat(rejected.get("not_applied_count")).isEqualTo(2);
            assertThat(rejected.get("answered_at")).isNotNull();
            assertThat((String) rejected.get("reason_detail")).doesNotContain("unknown outcome");
            assertThat((String) ci.queryForObject("select reason_detail from publication_run_item where id = ?",
                    String.class, r2)).contains("unknown outcome");
            assertThat(JSON.readTree((String) ci.queryForObject("select result_json from publication_run_item where id = ?",
                    String.class, r2)).path("possiblyWrittenEarlier").asBoolean()).isTrue();
            assertThat(mutationStatuses(s.batchId())).containsOnly(Map.entry("R1", "READY_FOR_PUBLICATION"),
                    Map.entry("R2", "READY_FOR_PUBLICATION"));
            assertThat(sourceState(s, "R1")).containsEntry("state_origin", "BASELINE_ACCEPTED");
        } finally {
            for (String grant : database.roleScript().grants()) {
                owner(grant);
            }
        }
        assertThat(prodisBasePrice(s, "R1")).isEqualByComparingTo("100");
        assertThat(writeCount()).isZero();
    }

    @Test
    void aBundleWithOnlyCreationsFinishesAtOnceWithoutContactingProdis() {
        Scenario s = scenario("CREATES", "+R7", "+R8");

        ProductionRunView view = service().startProductionRun(s.bundleId(), PUBLISHER);

        assertThat(view.status()).isEqualTo("APPLIED");
        assertThat(view.bundleStatus()).isEqualTo("PARTIALLY_PUBLISHED");
        assertThat(view.itemCounts()).containsOnly(Map.entry("NOT_SENT", 2L));
        assertThat(writeCount()).isZero();
    }

    // --- service --------------------------------------------------------------------------------------------------------

    private static Settings settings(int chunkSize, int maxNotApplied) {
        return new Settings(true, SOURCE_ID, chunkSize, Duration.ofMinutes(5), maxNotApplied);
    }

    private ProdisPublicationRunService service() {
        return service(settings(200, 5));
    }

    private ProdisPublicationRunService service(Settings settings) {
        return service(settings, writer(prodis));
    }

    private ProdisPublicationRunService service(Settings settings, ProdisPublicationWriter writer) {
        return new ProdisPublicationRunService(runs, items, itemDao, bundles, bundleDao, snapshotDao, previewDao, sourceDao,
                mutations, publication(clock, writer), writer, reference(), transactionManager, clock, settings);
    }

    private ProdisPublicationService publication() {
        return publication(clock, writer(prodis));
    }

    private ProdisPublicationService publication(Clock serviceClock) {
        return publication(serviceClock, writer(prodis));
    }

    private ProdisPublicationService publication(Clock serviceClock, ProdisPublicationWriter writer) {
        return new ProdisPublicationService(items, mutations, writer, reference(), sourceStateRecorder,
                transactionManager, serviceClock);
    }

    private static ProdisPublicationWriter writer(ProdisPublicationJdbc jdbc) {
        StaticListableBeanFactory factory = jdbc == null ? new StaticListableBeanFactory()
                : new StaticListableBeanFactory(Map.of("prodisPublicationJdbc", jdbc));
        return new ProdisPublicationWriter(factory.getBeanProvider(ProdisPublicationJdbc.class));
    }

    private static ProdisReferenceService reference() {
        return new ProdisReferenceService(new ProdisReferenceDao(new StaticListableBeanFactory(
                Map.of("prodisReferenceJdbc", prodisReader)).getBeanProvider(ProdisReferenceJdbc.class)));
    }

    // --- scenario -------------------------------------------------------------------------------------------------------

    private record Fixture(long taskId, long linkId, String unique) {
    }

    private record Scenario(long bundleId, long batchId, long linkId, String supplier, Fixture fixture,
                            Map<String, Long> prodisPrices) {
        long prodisPriceId(String reference) {
            return prodisPrices.get(reference);
        }
    }

    private Scenario scenario(String prefix, String... references) {
        return scenario(prefix, PublicationTargetMode.PRODUCTION, references);
    }

    /**
     * Nulmeting van alle referenties zonder "+" aan 100; de tweede levering zet ze op 120 (UPDATE 100 → 120) en voegt de
     * referenties met "+" toe (CREATE). Zoals {@code PublicationParallelRunTest.scenario}, maar met een PRODUCTION-bundel.
     */
    private Scenario scenario(String prefix, PublicationTargetMode mode, String... references) {
        String supplier = ("P" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet()).toUpperCase();
        Fixture fixture = fixture(prefix);
        List<String> existing = new ArrayList<>();
        for (String reference : references) {
            if (!reference.startsWith("+")) {
                existing.add(reference);
            }
        }
        if (!existing.isEmpty()) {
            long first = screenedBatch(fixture, "NULMETING", rows(supplier, "100,00", existing.toArray(String[]::new)));
            baseline.acceptBaseline(first, CREATOR, "Nulmeting");
        }
        long batchId = screenedBatch(fixture, "PRIJSRONDE", rows(supplier, "120,00", references));
        BundleReference bundle = bundleService.createBundle(BUNDLE_PREFIX + fixture.unique(), null, mode, null, null,
                CREATOR);
        bundleService.addBatches(bundle.id(), List.of(batchId), CREATOR);
        decisions.decideGroup(bundle.id(), BundleDecisionKind.APPROVE, DECIDER, "Levering nagekeken",
                new DecisionFilter(null, MutationStatus.AWAITING_APPROVAL, null, null, null));
        freezeService.freeze(bundle.id(), FREEZER, "Prijsronde goedgekeurd");
        return new Scenario(bundle.id(), batchId, fixture.linkId(), supplier, fixture, new LinkedHashMap<>());
    }

    private static String[] rows(String supplier, String price, String... references) {
        List<String> rows = new ArrayList<>();
        for (String reference : references) {
            String bare = reference.startsWith("+") ? reference.substring(1) : reference;
            rows.add(supplier + ";G1;" + bare + ";" + price + ";" + description(bare));
        }
        return rows.toArray(String[]::new);
    }

    private static String description(String reference) {
        return switch (reference) {
            case "R1" -> "Boormachine";
            case "R2" -> "Schroevendraaier";
            case "R3" -> "Zaag";
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
        // Zoals PublicationRunSimulationTest: de fixture kent geen valutakolom; nooit EUR aannemen, dus expliciet zetten.
        ci.update("update import_mutation set base_price_currency = 'EUR' where batch_id = ? "
                + "and action_type in ('CREATE', 'UPDATE')", batchId);
        return batchId;
    }

    private Fixture fixture(String prefix) {
        String unique = "P5C" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
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
        return new Fixture(task.getId(), link.getId(), unique);
    }

    private long assemblingBundle() {
        return bundleService.createBundle(BUNDLE_PREFIX + "ASM-" + SEQUENCE.incrementAndGet() + "-"
                + Long.toString(System.nanoTime(), 36), null, PublicationTargetMode.PRODUCTION, null, null, CREATOR).id();
    }

    // --- Prodis-testdata --------------------------------------------------------------------------------------------------

    /**
     * Eén volledig artikel (prijscode 0, bruto-politiek 1, VKP% 10..50 met consistente prijzen, NL-omschrijving); leverancier,
     * groep en catalogus van het scenario worden bij het eerste artikel aangemaakt.
     */
    private void prodisArticle(Scenario s, String reference, String base, String description) {
        long supplierId = ensure("supplier", "number = '" + s.supplier() + "'",
                id -> "insert into supplier (id, number, name_alfa, name) values (" + id + ", '" + s.supplier()
                        + "', 'P5C', 'P5C supplier')");
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
        s.prodisPrices().put(reference, price);
    }

    private long ensure(String table, String where, java.util.function.LongFunction<String> insert) {
        AtomicReference<Long> found = new AtomicReference<>();
        try (Connection owner = ProdisPublicationTestDatabase.owner(); Statement statement = owner.createStatement();
             ResultSet row = statement.executeQuery("select id from " + table + " where " + where)) {
            if (row.next()) {
                found.set(row.getLong(1));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
        if (found.get() != null) {
            return found.get();
        }
        long id = PRODIS_ID.incrementAndGet();
        owner(insert.apply(id));
        return id;
    }

    private static BigDecimal prodisBasePrice(Scenario s, String reference) {
        return (BigDecimal) prodisValue("select base_price from catalog_article_price where id = "
                + s.prodisPriceId(reference));
    }

    private static Object prodisValue(String sql) {
        return ProdisPublicationTestDatabase.ownerValue(sql);
    }

    private static long writeCount() {
        return (Long) prodisValue("select count(*) from pubtest_write_log");
    }

    /** Tabel → aantal bijgewerkte rijen. */
    private static Map<String, Long> writtenRows() {
        Map<String, Long> rows = new LinkedHashMap<>();
        try (Connection owner = ProdisPublicationTestDatabase.owner(); Statement statement = owner.createStatement();
             ResultSet result = statement.executeQuery("select tbl, count(*) from pubtest_write_log group by tbl order by tbl")) {
            while (result.next()) {
                rows.put(result.getString(1), result.getLong(2));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
        return rows;
    }

    private static void owner(String sql) {
        try (Connection owner = ProdisPublicationTestDatabase.owner(); Statement statement = owner.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    // --- CI-lezen ----------------------------------------------------------------------------------------------------------

    private List<Map<String, Object>> itemRows(long runId) {
        return ci.queryForList("select * from publication_run_item where run_id = ? order by id", runId);
    }

    private List<Long> itemIds(long runId) {
        return ci.queryForList("select id from publication_run_item where run_id = ? order by id", Long.class, runId);
    }

    private long itemIdOf(long runId, String reference) {
        return ci.queryForObject("select i.id from publication_run_item i join import_mutation m on m.id = i.mutation_id "
                + "where i.run_id = ? and m.identity_supplier_reference = ?", Long.class, runId, reference);
    }

    private String itemStatus(long itemId) {
        return ci.queryForObject("select status from publication_run_item where id = ?", String.class, itemId);
    }

    private Map<String, String> itemsByReference(long runId) {
        Map<String, String> result = new LinkedHashMap<>();
        ci.query("select m.identity_supplier_reference, i.status from publication_run_item i join import_mutation m "
                + "on m.id = i.mutation_id where i.run_id = ? order by i.id", rs -> {
                    result.put(rs.getString(1), rs.getString(2));
                }, runId);
        return result;
    }

    private Map<String, String> mutationStatuses(long batchId) {
        Map<String, String> result = new LinkedHashMap<>();
        ci.query("select identity_supplier_reference, status from import_mutation where batch_id = ? "
                + "and action_type in ('CREATE', 'UPDATE') order by id", rs -> {
                    result.put(rs.getString(1), rs.getString(2));
                }, batchId);
        return result;
    }

    private Map<String, String> actionsByReference(long batchId) {
        Map<String, String> result = new LinkedHashMap<>();
        ci.query("select identity_supplier_reference, action_type from import_mutation where batch_id = ? "
                + "and action_type in ('CREATE', 'UPDATE') order by id", rs -> {
                    result.put(rs.getString(1), rs.getString(2));
                }, batchId);
        return result;
    }

    private Map<String, Object> sourceState(Scenario s, String reference) {
        return ci.queryForMap("select * from catalog_source_state where import_link_id = ? "
                + "and identity_supplier_reference = ?", s.linkId(), reference);
    }

    private long observationCount(Scenario s, String reference) {
        return ci.queryForObject("select count(*) from catalog_price_observation o join catalog_source_state st "
                + "on st.id = o.source_state_id where st.import_link_id = ? and st.identity_supplier_reference = ?",
                Long.class, s.linkId(), reference);
    }

    private String bundleStatus(long bundleId) {
        return ci.queryForObject("select status from publication_bundle where id = ?", String.class, bundleId);
    }

    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).getCode()).isEqualTo(code);
    }

    // --- klokken --------------------------------------------------------------------------------------------------------

    /** Een klok die de test kan verzetten (lease verlopen). */
    private static final class MutableClock extends Clock {
        private volatile Instant now = Instant.now();

        void set(Instant instant) {
            now = instant;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Een klok die bij de n-de aanroep faalt: een crash ná de Prodis-commit, vóór CI-transactie 2 vastligt. */
    private static final class FailingClock extends Clock {
        private final AtomicInteger calls = new AtomicInteger();
        private final int failOn;

        FailingClock(int failOn) {
            this.failOn = failOn;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            if (calls.incrementAndGet() == failOn) {
                throw new IllegalStateException("simulated crash after the Prodis commit");
            }
            return Instant.now();
        }
    }
}
