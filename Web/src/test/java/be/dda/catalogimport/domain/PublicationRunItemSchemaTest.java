package be.dda.catalogimport.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.DeliveryRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.ImportMutationRepository;
import be.dda.catalogimport.dao.PublicationBundleRepository;
import be.dda.catalogimport.dao.PublicationBundleSnapshotPriceRepository;
import be.dda.catalogimport.dao.PublicationBundleSnapshotRepository;
import be.dda.catalogimport.dao.PublicationRunItemRepository;
import be.dda.catalogimport.dao.PublicationRunRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * P4a (docs/design/publicatie-prodis-db-direct-design.md par. 2.4, 2.5, 5 en 6; changeset 027-1..027-3): bewijst dat
 * {@code publication_run_item} migreert, dat Hibernate de entiteiten met {@code ddl-auto: validate} aanvaardt, dat elke
 * check en uniciteit afdwingt wat ze belooft, dat {@code PUBLISHING}, de lease en {@code legacy_master_at_start} op de run
 * werken, dat de partiele index "hoogstens een actieve PRODUCTION-run" ook onder gelijktijdigheid standhoudt, en dat
 * {@code before_percentage} bestaat zonder bestaande rijen te raken.
 *
 * <h2>Business rule vs. implementatie</h2>
 * <ul>
 *   <li><b>Regel:</b> een item is nooit tegelijk twee dingen (verstuurd en nooit verstuurd, beantwoord zonder uitkomst).
 *       <b>Implementatie:</b> checks op status/sent_at/send_count/answered_at/outcome.</li>
 *   <li><b>Regel:</b> hoogstens een actieve PRODUCTION-run. <b>Implementatie:</b> partiele unieke index op
 *       {@code active_marker is not null}; terminale runs (marker NULL) en andere modi vallen erbuiten.</li>
 * </ul>
 * De database is gedeeld en blijvend: de actieve PRODUCTION-runs die een test aanmaakt worden in {@link #cleanUp()}
 * terminaal gezet, anders blokkeren ze elke volgende run (en test).
 */
@SpringBootTest
@ActiveProfiles("local")
class PublicationRunItemSchemaTest {

    private static final String USER = "tester@example.test";

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
    private DeliveryRepository deliveries;
    @Autowired
    private ImportBatchRepository batches;
    @Autowired
    private ImportMutationRepository mutations;
    @Autowired
    private PublicationBundleRepository bundles;
    @Autowired
    private PublicationBundleSnapshotRepository snapshots;
    @Autowired
    private PublicationBundleSnapshotPriceRepository snapshotPrices;
    @Autowired
    private PublicationRunItemRepository items;
    @Autowired
    private PublicationRunRepository runRepository;
    @Autowired
    private JdbcTemplate jdbc;

    /** Ids van de actieve PRODUCTION-runs die deze test aanmaakte; worden na elke test terminaal gezet. */
    private final List<Long> activeProductionRuns = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long id : activeProductionRuns) {
            jdbc.update("update publication_run set status = 'FAILED', failure_code = 'TEST_CLEANUP', "
                    + "finished_at = now(), active_marker = null, lease_owner = null, lease_until = null "
                    + "where id = ?", id);
        }
        activeProductionRuns.clear();
    }

    // --- Schema ----------------------------------------------------------------------------------

    @Test
    void createsThePublicationRunItemTableWithTheDocumentedColumnsAndTypes() {
        assertThat(columnNames("publication_run_item")).containsExactlyInAnyOrder(
                "ID", "RUN_ID", "MUTATION_ID", "PUBLICATION_ATTEMPT", "IDEMPOTENCY_KEY", "REQUEST_HASH", "REQUEST_JSON",
                "STATUS", "OUTCOME", "REASON_CODE", "REASON_DETAIL", "PRIOR_JSON", "RESULT_JSON", "WARNINGS_JSON",
                "LEGACY_MASTER", "LAST_ERROR_CODE", "SENT_AT", "SENT_BY", "SENT_BY_SUBJECT", "ANSWERED_AT",
                "SEND_COUNT");

        assertThat(length("idempotency_key")).isEqualTo(200);
        assertThat(length("request_hash")).isEqualTo(64);
        assertThat(length("status")).isEqualTo(30);
        assertThat(length("outcome")).isEqualTo(30);
        assertThat(length("reason_code")).isEqualTo(60);
        assertThat(length("reason_detail")).isEqualTo(1000);
        assertThat(length("legacy_master")).isEqualTo(30);
        assertThat(length("last_error_code")).isEqualTo(60);
        assertThat(length("sent_by")).isEqualTo(100);
        assertThat(length("sent_by_subject")).isEqualTo(255);
        for (String text : List.of("request_json", "prior_json", "result_json", "warnings_json")) {
            assertThat(dataType(text)).as(text).isEqualTo("text");
        }
        assertThat(nullable("request_json")).isEqualTo("NO");
        assertThat(nullable("request_hash")).isEqualTo("NO");
        assertThat(nullable("sent_by_subject")).isEqualTo("YES");
        assertThat(nullable("prior_json")).isEqualTo("YES");
        assertThat(defaultOf("status")).contains("PENDING");
        assertThat(defaultOf("send_count")).isEqualTo("0");
        assertThat(defaultOf("publication_attempt")).isEqualTo("1");
    }

    @Test
    void addsPublishingLeaseAndLegacyMasterToTheRunAndBeforePercentageToTheSnapshotPrice() {
        Map<String, Object> master = columnInfo("publication_run", "legacy_master_at_start");
        assertThat(((Number) master.get("character_maximum_length")).intValue()).isEqualTo(30);
        assertThat(master.get("is_nullable")).isEqualTo("YES");
        assertThat(master.get("column_default")).isNull();
        assertThat(((Number) columnInfo("publication_run", "lease_owner").get("character_maximum_length")).intValue())
                .isEqualTo(100);
        assertThat(columnInfo("publication_run", "lease_until").get("data_type"))
                .isEqualTo("timestamp with time zone");

        Map<String, Object> before = columnInfo("publication_bundle_snapshot_price", "before_percentage");
        assertThat(((Number) before.get("numeric_precision")).intValue()).isEqualTo(24);
        assertThat(((Number) before.get("numeric_scale")).intValue()).isEqualTo(12);
        assertThat(before.get("is_nullable")).isEqualTo("YES");
        assertThat(before.get("column_default")).isNull();
        // Zelfde schaal als de bestaande percentagekolom.
        Map<String, Object> percentage = columnInfo("publication_bundle_snapshot_price", "percentage");
        assertThat(before.get("numeric_precision")).isEqualTo(percentage.get("numeric_precision"));
        assertThat(before.get("numeric_scale")).isEqualTo(percentage.get("numeric_scale"));
    }

    // --- Itemchecks --------------------------------------------------------------------------------

    @Test
    void refusesAnInvalidStatusAndAcceptsEveryEnumValueInItsConsistentShape() {
        Fixture f = fixture("ISTAT");
        assertThatThrownBy(() -> rawItem(f.run, f.mutation(1), 1, "BOGUS", null, null, null, 0, false))
                .isInstanceOf(DataIntegrityViolationException.class);

        int n = 0;
        for (PublicationRunItemStatus status : PublicationRunItemStatus.values()) {
            ImportMutation mutation = f.mutation(++n);
            boolean answered = status.isAnswered();
            boolean sent = answered || status == PublicationRunItemStatus.SENT
                    || status == PublicationRunItemStatus.RESULT_UNKNOWN;
            assertThatCode(() -> rawItem(f.run, mutation, 1, status.name(), answered ? status.name() : null,
                    status == PublicationRunItemStatus.REJECTED || status == PublicationRunItemStatus.CONFLICT
                            ? "REASON" : null,
                    answered ? Instant.now() : null, sent ? 1 : 0, sent)).as(status.name()).doesNotThrowAnyException();
        }
    }

    @Test
    void enforcesTheSentNotSentAnsweredAndReasonRules() {
        Fixture f = fixture("ICHK");

        // Verstuurd (SENT) zonder sent_at of zonder verzending.
        assertThatThrownBy(() -> rawItem(f.run, f.mutation(1), 1, "SENT", null, null, null, 1, false))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> rawItem(f.run, f.mutation(2), 1, "SENT", null, null, null, 0, true))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> rawItem(f.run, f.mutation(3), 1, "SENT", null, null, null, 1, true))
                .doesNotThrowAnyException();

        // NOT_SENT mag geen verzending of antwoord dragen.
        assertThatThrownBy(() -> rawItem(f.run, f.mutation(4), 1, "NOT_SENT", null, null, null, 1, false))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> rawItem(f.run, f.mutation(5), 1, "NOT_SENT", null, null, null, 0, true))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Een definitief antwoord draagt answered_at en een uitkomst gelijk aan de status.
        assertThatThrownBy(() -> rawItem(f.run, f.mutation(6), 1, "APPLIED", "APPLIED", null, null, 1, true))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> rawItem(f.run, f.mutation(7), 1, "APPLIED", null, null, Instant.now(), 1, true))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> rawItem(f.run, f.mutation(8), 1, "APPLIED", "UNCHANGED", null, Instant.now(), 1, true))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Een niet-definitieve status heeft geen uitkomst en geen antwoordtijdstip.
        assertThatThrownBy(() -> rawItem(f.run, f.mutation(9), 1, "PENDING", "APPLIED", null, null, 0, false))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> rawItem(f.run, f.mutation(10), 1, "RESULT_UNKNOWN", null, null, Instant.now(), 1, true))
                .isInstanceOf(DataIntegrityViolationException.class);

        // REJECTED/CONFLICT zijn nooit zonder reason_code.
        assertThatThrownBy(() -> rawItem(f.run, f.mutation(11), 1, "REJECTED", "REJECTED", null, Instant.now(), 1, true))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> rawItem(f.run, f.mutation(12), 1, "CONFLICT", "CONFLICT", null, Instant.now(), 1, true))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Een onbekende uitkomstwaarde.
        assertThatThrownBy(() -> rawItem(f.run, f.mutation(13), 1, "PENDING", "BOGUS", null, null, 0, false))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Een PENDING-item na een terugval (fout vooraf) mag zijn eerdere verzending houden, met last_error_code.
        assertThatCode(() -> jdbc.update("insert into publication_run_item (run_id, mutation_id, publication_attempt, "
                        + "idempotency_key, request_hash, request_json, status, sent_at, send_count, last_error_code) "
                        + "values (?, ?, 1, ?, ?, ?, 'PENDING', ?, 1, '40P01')", f.run.getId(), f.mutation(14).getId(),
                key("FALLBACK"), "a".repeat(64), "{}", Timestamp.from(Instant.now())))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesInvalidCountersAndASubjectWithoutASender() {
        Fixture f = fixture("ICNT");

        assertThatThrownBy(() -> rawItem(f.run, f.mutation(1), 0, "PENDING", null, null, null, 0, false))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into publication_run_item (run_id, mutation_id, "
                        + "idempotency_key, request_hash, request_json, send_count) values (?, ?, ?, ?, ?, -1)",
                f.run.getId(), f.mutation(2).getId(), key("NEG"), "a".repeat(64), "{}"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Koppelcheck zoals 007-4: een subject zonder naam is een handtekening zonder ondertekenaar.
        assertThatThrownBy(() -> jdbc.update("insert into publication_run_item (run_id, mutation_id, idempotency_key, "
                        + "request_hash, request_json, sent_by_subject) values (?, ?, ?, ?, ?, 'sub-x')",
                f.run.getId(), f.mutation(3).getId(), key("SUB1"), "a".repeat(64), "{}"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> jdbc.update("insert into publication_run_item (run_id, mutation_id, idempotency_key, "
                        + "request_hash, request_json, sent_by, sent_by_subject) values (?, ?, ?, ?, ?, ?, 'sub-x')",
                f.run.getId(), f.mutation(4).getId(), key("SUB2"), "a".repeat(64), "{}", USER))
                .doesNotThrowAnyException();
        // Naam zonder subject (geen login) blijft geldig.
        assertThatCode(() -> jdbc.update("insert into publication_run_item (run_id, mutation_id, idempotency_key, "
                        + "request_hash, request_json, sent_by) values (?, ?, ?, ?, ?, ?)",
                f.run.getId(), f.mutation(5).getId(), key("SUB3"), "a".repeat(64), "{}", USER))
                .doesNotThrowAnyException();
    }

    @Test
    void enforcesTheThreeUniquenessRulesAndTheForeignKeys() {
        Fixture f = fixture("IUNQ");
        Fixture other = fixture("IUNQ2");
        ImportMutation m1 = f.mutation(1);
        String key = key("DUP");
        insert(f.run, m1, 1, key);

        // idempotency_key globaal uniek.
        assertThatThrownBy(() -> insert(other.run, other.mutation(1), 1, key))
                .isInstanceOf(DataIntegrityViolationException.class);
        // (run_id, mutation_id): een item per mutatie per run.
        assertThatThrownBy(() -> insert(f.run, m1, 2, key("DUP-2")))
                .isInstanceOf(DataIntegrityViolationException.class);
        // (mutation_id, publication_attempt): hetzelfde attempt van dezelfde mutatie in een andere run botst, een volgend niet.
        assertThatThrownBy(() -> insert(other.run, m1, 1, key("DUP-3")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> insert(other.run, m1, 2, key("DUP-4"))).doesNotThrowAnyException();

        assertThat(constraintExists("fk_publication_run_item_run")).isTrue();
        assertThat(constraintExists("fk_publication_run_item_mutation")).isTrue();
        assertThatThrownBy(() -> insert(-1L, m1.getId(), 3, key("FK-1")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(f.run.getId(), -1L, 3, key("FK-2")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void savesAnItemThroughTheRepositoryAndReadsItBack() {
        Fixture f = fixture("IRT");
        String key = key("RT");
        PublicationRunItem saved = items.saveAndFlush(new PublicationRunItem(f.run, f.mutation(1), 1, key,
                "b".repeat(64), "{\"identity\":{}}"));

        PublicationRunItem found = items.findById(saved.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(PublicationRunItemStatus.PENDING);
        assertThat(found.getSendCount()).isZero();
        assertThat(found.getPublicationAttempt()).isEqualTo(1);
        assertThat(found.getRequestJson()).isEqualTo("{\"identity\":{}}");
        assertThat(found.getRequestHash()).isEqualTo("b".repeat(64));
        assertThat(found.getSentAt()).isNull();
        assertThat(found.getOutcome()).isNull();
        assertThat(items.findByIdempotencyKey(key)).isPresent();
        assertThat(items.findByRunIdOrderByIdAsc(f.run.getId())).extracting(PublicationRunItem::getId)
                .containsExactly(saved.getId());
        assertThat(items.countByRunIdAndStatus(f.run.getId(), PublicationRunItemStatus.PENDING)).isEqualTo(1L);
    }

    // --- Run: PUBLISHING, lease, modus ---------------------------------------------------------------

    @Test
    void acceptsPublishingAndStillAcceptsTheElevenExistingStatuses() {
        for (PublicationRunStatus status : PublicationRunStatus.values()) {
            PublicationBundle bundle = bundle("RSTAT-" + status);
            Boolean marker = status.isTerminal() ? null : Boolean.TRUE;
            assertThatCode(() -> rawRun(bundle, "SIMULATION", status.name(), marker, "CODE", key("RS-" + status)))
                    .as(status.name()).doesNotThrowAnyException();
        }
        assertThatThrownBy(() -> rawRun(bundle("RSTAT-X"), "SIMULATION", "BOGUS", true, null, key("RS-X")))
                .isInstanceOf(DataIntegrityViolationException.class);
        // PUBLISHING valt onder de bestaande markerregel: een PUBLISHING-run zonder marker is ongeldig.
        assertThatThrownBy(() -> rawRun(bundle("RSTAT-NM"), "SIMULATION", "PUBLISHING", null, null, key("RS-NM")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void leaseOwnerAndLeaseUntilAreBothSetOrBothEmptyAndLegacyMasterIsStored() {
        PublicationBundle bundle = bundle("LEASE");
        PublicationRun run = runs(bundle, "SIMULATION", key("LEASE"));

        // Entiteit leest de nieuwe kolommen: verse run = geen lease, geen modus (nooit stil een default).
        assertThat(run.getLeaseOwner()).isNull();
        assertThat(run.getLeaseUntil()).isNull();
        assertThat(run.getLegacyMasterAtStart()).isNull();

        assertThatThrownBy(() -> jdbc.update("update publication_run set lease_owner = 'node-1' where id = ?",
                run.getId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update publication_run set lease_until = now() where id = ?",
                run.getId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> jdbc.update("update publication_run set lease_owner = 'node-1', "
                        + "lease_until = now() + interval '1 minute', legacy_master_at_start = 'PRODIS' where id = ?",
                run.getId())).doesNotThrowAnyException();
        assertThatCode(() -> jdbc.update("update publication_run set lease_owner = null, lease_until = null "
                + "where id = ?", run.getId())).doesNotThrowAnyException();

        jdbc.update("update publication_run set lease_owner = 'node-1', lease_until = now() where id = ?",
                run.getId());
        Map<String, Object> row = jdbc.queryForMap("select lease_owner, legacy_master_at_start "
                + "from publication_run where id = ?", run.getId());
        assertThat(row).containsEntry("lease_owner", "node-1").containsEntry("legacy_master_at_start", "PRODIS");
    }

    // --- Partiele index: hoogstens een actieve PRODUCTION-run -----------------------------------------

    @Test
    void allowsOneActiveProductionRunButNotASecondAndIgnoresOtherModesAndTerminalRuns() {
        PublicationBundle a = bundle("PROD-A");
        PublicationBundle b = bundle("PROD-B");
        activeProduction(a, "PREPARING", key("PA"));

        // Tweede actieve PRODUCTION-run, ook voor een andere bundel: uk_publication_run_active_production.
        assertThatThrownBy(() -> activeProduction(b, "REQUESTED", key("PB")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(constraintOrIndexExists("uk_publication_run_active_production")).isTrue();

        // Een actieve SIMULATION- of TRIAL_LIBRARY-run staat er los van.
        assertThatCode(() -> rawRun(b, "SIMULATION", "REQUESTED", true, null, key("PSIM"))).doesNotThrowAnyException();
        assertThatCode(() -> rawRun(bundle("PROD-C"), "TRIAL_LIBRARY", "REQUESTED", true, null, key("PTRIAL")))
                .doesNotThrowAnyException();

        // Terminale PRODUCTION-runs (marker NULL): onbeperkt veel.
        assertThatCode(() -> {
            rawRun(b, "PRODUCTION", "FAILED", null, "CODE", key("PT1"));
            rawRun(b, "PRODUCTION", "FAILED", null, "CODE", key("PT2"));
        }).doesNotThrowAnyException();
    }

    @Test
    void publishingCountsAsActiveAndTheSlotIsFreeAgainOnceTheRunIsTerminal() {
        PublicationBundle a = bundle("SLOT-A");
        PublicationBundle b = bundle("SLOT-B");
        long first = activeProduction(a, "PUBLISHING", key("SLOT1"));
        assertThatThrownBy(() -> activeProduction(b, "PREPARING", key("SLOT2")))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("update publication_run set status = 'FAILED', failure_code = 'TEST', finished_at = now(), "
                + "active_marker = null where id = ?", first);
        assertThatCode(() -> activeProduction(b, "PREPARING", key("SLOT3"))).doesNotThrowAnyException();
    }

    @Test
    void underConcurrentInsertsExactlyOneActiveProductionRunSucceeds() throws Exception {
        int threads = 6;
        List<PublicationBundle> own = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            own.add(bundle("CONC-" + i));
        }
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                PublicationBundle bundle = own.get(i);
                String key = key("CONC-" + i);
                Callable<Void> task = () -> {
                    ready.countDown();
                    go.await();
                    try {
                        activeProduction(bundle, "REQUESTED", key);
                        succeeded.incrementAndGet();
                    } catch (DataIntegrityViolationException expected) {
                        refused.incrementAndGet();
                    }
                    return null;
                };
                futures.add(pool.submit(task));
            }
            ready.await();
            go.countDown();
            for (Future<Void> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(refused.get()).isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject("select count(*) from publication_run where target_mode = 'PRODUCTION' "
                + "and active_marker is not null and idempotency_key like 'item-test:CONC-%'", Long.class)).isEqualTo(1L);
    }

    // --- before_percentage ----------------------------------------------------------------------------

    @Test
    void beforePercentageIsNullByDefaultAcceptsTheSamePrecisionAsPercentageAndLeavesExistingRowsUntouched() {
        Fixture f = fixture("BEF");
        PublicationBundleSnapshot snapshot = snapshots.saveAndFlush(new PublicationBundleSnapshot(f.bundle, f.mutation(1),
                f.batchId, f.linkId, 7L, "Omschrijving", SnapshotDescriptionState.VALUE));
        snapshotPrices.saveAndFlush(new PublicationBundleSnapshotPrice(snapshot.getId(), "VKP1", new BigDecimal("10.5"),
                new BigDecimal("25.5"), "EUR", "VALUE"));

        // Bestaand pad (zonder before_percentage): NULL, nooit stil 0.
        PublicationBundleSnapshotPrice found = snapshotPrices.findAll().stream()
                .filter(p -> p.getSnapshotId().equals(snapshot.getId())).findFirst().orElseThrow();
        assertThat(found.getBeforePercentage()).isNull();
        assertThat(found.getPercentage()).isEqualByComparingTo("25.5");

        try {
            jdbc.update("update publication_bundle_snapshot_price set before_percentage = ? where snapshot_id = ?",
                    new BigDecimal("12.123456789012"), snapshot.getId());
            assertThat(jdbc.queryForObject("select before_percentage from publication_bundle_snapshot_price "
                    + "where snapshot_id = ?", BigDecimal.class, snapshot.getId()))
                    .isEqualByComparingTo("12.123456789012");
            // Geen backfill: elke snapshotprijs van een bundel zonder snapshot spec 2 (bevroren vóór P4b, of zonder
            // snapshot) heeft nog NULL. Sinds P4b vult het bevriezen before_percentage voor spec-2-bundels; die tellen
            // hier dus bewust niet mee.
            assertThat(jdbc.queryForObject("select count(*) from publication_bundle_snapshot_price p "
                    + "join publication_bundle_snapshot sn on sn.id = p.snapshot_id "
                    + "join publication_bundle b on b.id = sn.bundle_id "
                    + "where p.before_percentage is not null and p.snapshot_id <> ? "
                    + "and (b.snapshot_spec_version is null or b.snapshot_spec_version <> '2')", Long.class,
                    snapshot.getId())).isZero();
        } finally {
            // Herhaalbaarheid: de eigen testrij terug op NULL zodat een volgende run dezelfde assert haalt.
            jdbc.update("update publication_bundle_snapshot_price set before_percentage = null where snapshot_id = ?",
                    snapshot.getId());
        }
    }

    // --- Helpers ---------------------------------------------------------------------------------------

    private static String key(String prefix) {
        return "item-test:" + prefix + ":" + Long.toString(System.nanoTime(), 36);
    }

    private void insert(PublicationRun run, ImportMutation mutation, int attempt, String key) {
        insert(run.getId(), mutation.getId(), attempt, key);
    }

    private void insert(long runId, long mutationId, int attempt, String key) {
        jdbc.update("insert into publication_run_item (run_id, mutation_id, publication_attempt, idempotency_key, "
                + "request_hash, request_json) values (?, ?, ?, ?, ?, ?)", runId, mutationId, attempt, key,
                "a".repeat(64), "{}");
    }

    private void rawItem(PublicationRun run, ImportMutation mutation, int attempt, String status, String outcome,
                         String reasonCode, Instant answeredAt, int sendCount, boolean sent) {
        jdbc.update("insert into publication_run_item (run_id, mutation_id, publication_attempt, idempotency_key, "
                        + "request_hash, request_json, status, outcome, reason_code, sent_at, answered_at, send_count) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", run.getId(), mutation.getId(), attempt,
                key("RAW-" + status), "a".repeat(64), "{}", status, outcome, reasonCode,
                sent ? Timestamp.from(Instant.now()) : null,
                answeredAt == null ? null : Timestamp.from(answeredAt), sendCount);
    }

    private void rawRun(PublicationBundle bundle, String mode, String status, Boolean marker, String failureCode,
                        String key) {
        jdbc.update("insert into publication_run (bundle_id, target_mode, status, requested_by, requested_at, "
                        + "finished_at, bundle_content_hash, failure_code, idempotency_key, active_marker) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                bundle.getId(), mode, status, USER, Timestamp.from(Instant.now()),
                marker == null ? Timestamp.from(Instant.now()) : null, new byte[32], failureCode, key, marker);
    }

    /** Een actieve (marker TRUE) PRODUCTION-run; wordt na de test terminaal gezet (zie {@link #cleanUp()}). */
    private long activeProduction(PublicationBundle bundle, String status, String key) {
        rawRun(bundle, "PRODUCTION", status, true, null, key);
        Long id = jdbc.queryForObject("select id from publication_run where idempotency_key = ?", Long.class, key);
        activeProductionRuns.add(id);
        return id;
    }

    private PublicationRun runs(PublicationBundle bundle, String mode, String key) {
        rawRun(bundle, mode, "REQUESTED", true, null, key);
        return runRepository.findByIdempotencyKey(key).orElseThrow();
    }

    private PublicationBundle bundle(String prefix) {
        return bundles.saveAndFlush(new PublicationBundle("BND-ITEM-" + prefix + "-" + Long.toString(System.nanoTime(), 36),
                PublicationTargetMode.PRODUCTION, USER));
    }

    private List<String> columnNames(String table) {
        return jdbc.queryForList("select upper(column_name) from information_schema.columns "
                + "where table_schema = current_schema() and lower(table_name) = ?", String.class, table);
    }

    private Map<String, Object> columnInfo(String table, String column) {
        return jdbc.queryForMap("select data_type, character_maximum_length, numeric_precision, numeric_scale, "
                + "is_nullable, column_default from information_schema.columns where table_schema = current_schema() "
                + "and lower(table_name) = ? and lower(column_name) = ?", table, column);
    }

    private Integer length(String column) {
        return ((Number) columnInfo("publication_run_item", column).get("character_maximum_length")).intValue();
    }

    private String dataType(String column) {
        return (String) columnInfo("publication_run_item", column).get("data_type");
    }

    private String nullable(String column) {
        return (String) columnInfo("publication_run_item", column).get("is_nullable");
    }

    private String defaultOf(String column) {
        return (String) columnInfo("publication_run_item", column).get("column_default");
    }

    private boolean constraintExists(String name) {
        Integer count = jdbc.queryForObject("select count(*) from information_schema.table_constraints "
                + "where constraint_schema = current_schema() and upper(constraint_name) = upper(?)", Integer.class, name);
        return count != null && count > 0;
    }

    private boolean constraintOrIndexExists(String name) {
        Integer count = jdbc.queryForObject("select count(*) from pg_indexes where schemaname = current_schema() "
                + "and indexname = ?", Integer.class, name);
        return count != null && count > 0;
    }

    /** Bundel + run (SIMULATION, actief) + een keten om meerdere mutaties te maken. */
    private Fixture fixture(String prefix) {
        String unique = prefix + "-" + Long.toString(System.nanoTime(), 36);
        SourceOrganisation organisation = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + "-ORG BV", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(
                new ImportDefinition(organisation, unique + "-DEF", unique + " catalogus", USER));
        ImportDefinitionRevision revision = new ImportDefinitionRevision(definition, 1,
                IdentityProfileKind.THREE_PART, USER);
        revision.setAccessConfigHash("1".repeat(64));
        revision.setStructureConfigHash("2".repeat(64));
        revision.setRecordRulesConfigHash("3".repeat(64));
        revision.setCompositeConfigHash("4".repeat(64));
        revision.setIdentitySupplierField("LEVERANCIER");
        revision.setIdentitySupplierGroupField("LEV_GROEP");
        revision.setIdentitySupplierReferenceField("LEV_REFERENTIE");
        revision.setStructureDelimiter(";");
        revision = revisions.saveAndFlush(revision);
        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + "-LINK koppeling", definition, supplier, "PSARF050"));
        CatalogImportTask task = tasks.saveAndFlush(new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL));
        Delivery delivery = deliveries.saveAndFlush(new Delivery(task, "manual:" + unique, Instant.now()));
        ImportBatch batch = batches.saveAndFlush(new ImportBatch(delivery, link, revision, 1, USER));
        PublicationBundle bundle = bundle(prefix);
        PublicationRun run = runs(bundle, "SIMULATION", key(prefix + "-RUN"));
        return new Fixture(batch, bundle, run, unique, link.getId(), batch.getId());
    }

    private final class Fixture {
        private final ImportBatch batch;
        private final PublicationBundle bundle;
        private final PublicationRun run;
        private final String unique;
        private final long linkId;
        private final long batchId;
        private final List<ImportMutation> created = new ArrayList<>();

        Fixture(ImportBatch batch, PublicationBundle bundle, PublicationRun run, String unique, long linkId,
                long batchId) {
            this.batch = batch;
            this.bundle = bundle;
            this.run = run;
            this.unique = unique;
            this.linkId = linkId;
            this.batchId = batchId;
        }

        /** De n-de mutatie van de keten (aangemaakt bij de eerste aanvraag, daarna hergebruikt). */
        ImportMutation mutation(int n) {
            while (created.size() < n) {
                String key = unique + ":" + (created.size() + 1) + ":H1:OFFER";
                ImportMutation mutation = new ImportMutation(batch, MutationActionType.UPDATE,
                        MutationTargetDomain.OFFER, MutationStatus.PLANNED, key);
                mutation.setIdentitySupplier("LEV");
                mutation.setIdentitySupplierGroup("GRP");
                mutation.setIdentitySupplierReference("REF-" + key);
                created.add(mutations.saveAndFlush(mutation));
            }
            return created.get(n - 1);
        }
    }
}
