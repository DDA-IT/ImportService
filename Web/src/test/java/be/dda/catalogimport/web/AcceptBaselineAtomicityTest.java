package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportFieldCatalogRepository;
import be.dda.catalogimport.dao.ImportFieldMappingRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.MutationDao;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.dao.SourceStateDao;
import be.dda.catalogimport.dao.SourceStateDao.AcceptanceContext;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.FieldValueKind;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportFieldCatalogEntry;
import be.dda.catalogimport.domain.ImportFieldMapping;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.PublicationTargetMode;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.service.ConflictException;
import be.dda.catalogimport.service.DeliveryIntakeService;
import be.dda.catalogimport.service.DeliveryScreeningService;
import be.dda.catalogimport.service.PublicationBundleService;
import be.dda.catalogimport.service.SourceStateBaselineService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S5-c (beslissingslog 2026-10-01 "Stap 4 en 5 uitgewerkt"): accept-baseline is één transactie, alles of niets,
 * tegen de echte PostgreSQL.
 * <p>
 * <b>Businessregel.</b> Een batch die niet aanvaard wordt, laat geen bronstaat, geen prijsobservatie en geen
 * kritieke referentie achter (AGENT.md 2.8: financiële data nooit stil fout). Twee aanvaardingen op dezelfde
 * koppeling lopen nooit tegelijk: de tweede krijgt meteen 409 {@code BASELINE_ACCEPTANCE_IN_PROGRESS}. Een batch is
 * nooit tegelijk aanvaard én lid van een Publicatiebundel (R-BAS-02).
 * <p>
 * Chunkgrootte 2 en vijf regels: drie chunks. De fixture mapt de EAN-kolom als kritieke referentie, zodat ook
 * {@code catalog_reference_state} meegeschreven (en teruggedraaid) wordt. Elke test krijgt een eigen bibliotheek.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=50",
        "catalogimport.screening.mutation-chunk-size=2",
        "catalogimport.screening.recovery-on-startup=false"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AcceptBaselineAtomicityTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING;EAN\n";
    private static final String USER = "jan.peeters@example.test";
    private static final String REASON = "Eerste nulmeting van de leverancierscatalogus";
    private static final long MUST_ANSWER_WITHIN_MILLIS = 5_000;

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @MockitoSpyBean
    private SourceStateDao sourceState;
    @MockitoSpyBean
    private MutationDao mutationDao;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DeliveryIntakeService intake;
    @Autowired
    private DeliveryScreeningService screening;
    @Autowired
    private SourceStateBaselineService baseline;
    @Autowired
    private PublicationBundleService bundles;
    @Autowired
    private SourceOrganisationRepository sourceOrganisations;
    @Autowired
    private ImportDefinitionRepository definitions;
    @Autowired
    private ImportDefinitionRevisionRepository revisions;
    @Autowired
    private ImportFieldCatalogRepository fieldCatalog;
    @Autowired
    private ImportFieldMappingRepository fieldMappings;
    @Autowired
    private ImportLinkRepository links;
    @Autowired
    private CatalogImportTaskRepository tasks;
    @Autowired
    private ImportBatchRepository batches;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void resetSpies() {
        Mockito.reset(sourceState, mutationDao);
    }

    // --- 1. Alles of niets --------------------------------------------------------------------------------------

    @Test
    void aFailureInTheSecondChunkLeavesNoSourceStateObservationOrReferenceAndAPlainRetrySucceeds()
            throws Exception {
        Fixture f = fixture("CHUNK2");
        long batchId = screened(f, "REF-1", prices("1,00"));
        AtomicInteger chunk = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
            if (chunk.incrementAndGet() == 2) {
                throw new UncheckedIOException(new IOException("simulated failure in chunk 2"));
            }
            return invocation.callRealMethod();
        }).when(sourceState).insertNewFromStage(any(), anyLong(), anyLong());

        assertThatThrownBy(() -> baseline.acceptBaseline(batchId, USER, REASON))
                .hasRootCauseInstanceOf(IOException.class);

        assertThat(chunk.get()).as("chunk 1 ran and chunk 2 failed").isEqualTo(2);
        assertNothingWritten(f, batchId);

        Mockito.reset(sourceState);
        accept(batchId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("BASELINE_ACCEPTED"));
        assertThat(count("catalog_source_state", f.linkId())).isEqualTo(5L);
        assertThat(observations(f.linkId(), batchId)).isEqualTo(5L);
        assertThat(count("catalog_reference_state", f.linkId())).isEqualTo(5L);
        assertThat(skippedMutations(batchId)).isEqualTo(5L);
    }

    /** Ook een fout ná alle chunks (in de eindtransitie) draait de geschreven chunks terug. */
    @Test
    void aFailureInTheFinalTransitionAlsoRollsBackEveryChunk() {
        Fixture f = fixture("FINISH");
        long batchId = screened(f, "REF-1", prices("1,00"));
        Mockito.doThrow(new UncheckedIOException(new IOException("simulated failure in the final transition")))
                .when(mutationDao).skipOpenContentMutations(anyLong(), anyString());

        assertThatThrownBy(() -> baseline.acceptBaseline(batchId, USER, REASON))
                .hasRootCauseInstanceOf(IOException.class);

        Mockito.verify(sourceState, Mockito.times(3)).insertNewFromStage(any(), anyLong(), anyLong());
        assertNothingWritten(f, batchId);
    }

    // --- 2. Bezette sloten --------------------------------------------------------------------------------------

    @Test
    void aBusyLinkGivesBaselineAcceptanceInProgressAndABusyBatchGivesBatchBeingProcessed() throws Exception {
        Fixture f = fixture("BUSY");
        long batchId = screened(f, "REF-1", prices("1,00"));

        holdingRowLock("select id from import_link where id = ? for update", f.linkId(), () ->
                assertRefusedQuickly(batchId, "BASELINE_ACCEPTANCE_IN_PROGRESS"));
        assertNothingWritten(f, batchId);

        holdingRowLock("select id from import_batch where id = ? for update", batchId, () ->
                assertRefusedQuickly(batchId, "BATCH_BEING_PROCESSED"));
        assertNothingWritten(f, batchId);

        accept(batchId).andExpect(status().isOk());
        assertThat(count("catalog_source_state", f.linkId())).isEqualTo(5L);
    }

    // --- 3. Twee aanvaardingen op dezelfde koppeling -----------------------------------------------------------

    /**
     * Deterministisch: de aanvaarding van A staat stil in haar eerste chunk (houdt de sloten vast); B op dezelfde
     * koppeling krijgt meteen 409 {@code BASELINE_ACCEPTANCE_IN_PROGRESS}. Na A is B verouderd (zelfde identiteiten,
     * andere prijs): 409 {@code SOURCE_STATE_CHANGED_SINCE_SCREENING}. Nooit beide aanvaard.
     */
    @Test
    void aSecondAcceptanceOnTheSameLinkIsRefusedWhileTheFirstIsRunning() throws Exception {
        Fixture f = fixture("SAMELINK");
        long batchA = screened(f, "REF-A", prices("1,00"));
        long batchB = screened(f, "REF-B", prices("2,00"));

        CountDownLatch inChunk = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        pauseFirstChunkOf(batchA, inChunk, release);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<SourceStateBaselineService.BaselineAcceptance> a =
                    pool.submit(() -> baseline.acceptBaseline(batchA, USER, REASON));
            assertThat(inChunk.await(20, TimeUnit.SECONDS)).as("acceptance A is inside its first chunk").isTrue();

            assertRefusedQuickly(batchB, "BASELINE_ACCEPTANCE_IN_PROGRESS");
            assertThat(batches.findById(batchB).orElseThrow().getStatus()).isEqualTo(ImportBatchStatus.SCREENED);

            release.countDown();
            assertThat(a.get(30, TimeUnit.SECONDS).status()).isEqualTo("BASELINE_ACCEPTED");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        accept(batchB).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_STATE_CHANGED_SINCE_SCREENING"));
        assertThat(batches.findById(batchB).orElseThrow().getStatus()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(observations(f.linkId(), batchB)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from catalog_source_state where import_link_id = ? "
                + "and last_change_batch_id = ?", Long.class, f.linkId(), batchA)).isEqualTo(5L);
    }

    /** Vrije race: beide starten tegelijk; precies één wordt aanvaard, de andere krijgt een van de twee 409's. */
    @Test
    void twoSimultaneousAcceptancesOnTheSameLinkNeverBothSucceed() throws Exception {
        Fixture f = fixture("RACE");
        long batchA = screened(f, "REF-A", prices("1,00"));
        long batchB = screened(f, "REF-B", prices("2,00"));
        slowDownChunks(150);

        List<Outcome> outcomes = race(() -> acceptOutcome(batchA), () -> acceptOutcome(batchB));

        assertThat(outcomes).filteredOn(o -> o.status() == 200).hasSize(1);
        Outcome loser = outcomes.stream().filter(o -> o.status() != 200).findFirst().orElseThrow();
        assertThat(loser.status()).isEqualTo(409);
        assertThat(loser.body()).containsAnyOf("\"BASELINE_ACCEPTANCE_IN_PROGRESS\"",
                "\"SOURCE_STATE_CHANGED_SINCE_SCREENING\"");
        long accepted = jdbc.queryForObject("select count(*) from import_batch where id in (?, ?) "
                + "and status = 'BASELINE_ACCEPTED'", Long.class, batchA, batchB);
        assertThat(accepted).isEqualTo(1L);
        // De bronstaat komt volledig van de winnaar, de verliezer heeft niets achtergelaten.
        long winner = outcomes.get(0).status() == 200 ? batchA : batchB;
        long loserBatch = winner == batchA ? batchB : batchA;
        assertThat(jdbc.queryForObject("select count(*) from catalog_source_state where import_link_id = ? "
                + "and last_change_batch_id = ?", Long.class, f.linkId(), winner)).isEqualTo(5L);
        assertThat(observations(f.linkId(), loserBatch)).isZero();
    }

    // --- 4. Bundelopname tijdens een aanvaarding (R-BAS-02) -----------------------------------------------------

    @Test
    void anAddToBundleDuringAnAcceptanceIsRefusedAndTheBatchNeverEndsUpInBoth() throws Exception {
        Fixture f = fixture("BUNDLE");
        long batchId = screened(f, "REF-1", prices("1,00"));
        long bundleId = bundles.createBundle(bundleRef("ACC"), null, PublicationTargetMode.SIMULATION, null, null,
                USER).id();

        CountDownLatch inChunk = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        pauseFirstChunkOf(batchId, inChunk, release);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<SourceStateBaselineService.BaselineAcceptance> acceptance =
                    pool.submit(() -> baseline.acceptBaseline(batchId, USER, REASON));
            assertThat(inChunk.await(20, TimeUnit.SECONDS)).isTrue();

            long start = System.nanoTime();
            assertThatThrownBy(() -> bundles.addBatches(bundleId, List.of(batchId), USER))
                    .isInstanceOfSatisfying(ConflictException.class,
                            e -> assertThat(e.getCode()).isEqualTo("BATCH_BEING_PROCESSED"));
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(MUST_ANSWER_WITHIN_MILLIS);

            release.countDown();
            assertThat(acceptance.get(30, TimeUnit.SECONDS).status()).isEqualTo("BASELINE_ACCEPTED");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertThat(activeMemberships(batchId)).isZero();
        // Na de aanvaarding weigert de bundel de batch op haar status.
        assertThatThrownBy(() -> bundles.addBatches(bundleId, List.of(batchId), USER))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo(PublicationBundleService.CODE_BATCH_NOT_BUNDLEABLE));
        assertThat(activeMemberships(batchId)).isZero();
    }

    /** Vrije race tussen opname en aanvaarding, enkele keren: nooit lidmaatschap én BASELINE_ACCEPTED. */
    @Test
    void aSimultaneousAddToBundleAndAcceptanceNeverLeaveBoth() throws Exception {
        slowDownChunks(50);
        for (int round = 0; round < 3; round++) {
            Fixture f = fixture("BRACE" + round);
            long batchId = screened(f, "REF-1", prices("1,00"));
            long bundleId = bundles.createBundle(bundleRef("RACE" + round), null, PublicationTargetMode.SIMULATION,
                    null, null, USER).id();

            List<Outcome> outcomes = race(() -> acceptOutcome(batchId), () -> {
                try {
                    bundles.addBatches(bundleId, List.of(batchId), USER);
                    return new Outcome(200, "added");
                } catch (ConflictException e) {
                    return new Outcome(409, e.getCode());
                }
            });

            ImportBatch batch = batches.findById(batchId).orElseThrow();
            boolean accepted = batch.getStatus() == ImportBatchStatus.BASELINE_ACCEPTED;
            boolean member = activeMemberships(batchId) > 0;
            assertThat(accepted && member).as("round %d: accepted and member at the same time (%s)", round, outcomes)
                    .isFalse();
            assertThat(accepted || member).as("round %d: one of both succeeded (%s)", round, outcomes).isTrue();
            if (!accepted) {
                assertNothingWritten(f, batchId);
            }
        }
    }

    // --- Helpers --------------------------------------------------------------------------------------------------

    private record Outcome(int status, String body) {
    }

    private List<Outcome> race(Callable<Outcome> first, Callable<Outcome> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Outcome> a = () -> {
                ready.countDown();
                go.await();
                return first.call();
            };
            Callable<Outcome> b = () -> {
                ready.countDown();
                go.await();
                return second.call();
            };
            Future<Outcome> fa = pool.submit(a);
            Future<Outcome> fb = pool.submit(b);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            return new ArrayList<>(List.of(fa.get(60, TimeUnit.SECONDS), fb.get(60, TimeUnit.SECONDS)));
        } finally {
            pool.shutdownNow();
        }
    }

    private Outcome acceptOutcome(long batchId) throws Exception {
        MvcResult result = accept(batchId).andReturn();
        return new Outcome(result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    /** De eerste chunk van {@code batchId} meldt zich en wacht op {@code release}; alle andere lopen gewoon. */
    private void pauseFirstChunkOf(long batchId, CountDownLatch inChunk, CountDownLatch release) {
        AtomicInteger calls = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
            AcceptanceContext context = invocation.getArgument(0);
            if (context.batchId() == batchId && calls.incrementAndGet() == 1) {
                inChunk.countDown();
                assertThat(release.await(30, TimeUnit.SECONDS)).as("released").isTrue();
            }
            return invocation.callRealMethod();
        }).when(sourceState).insertNewFromStage(any(), anyLong(), anyLong());
    }

    private void slowDownChunks(long millis) {
        Mockito.doAnswer(invocation -> {
            Thread.sleep(millis);
            return invocation.callRealMethod();
        }).when(sourceState).insertNewFromStage(any(), anyLong(), anyLong());
    }

    private void assertRefusedQuickly(long batchId, String code) {
        try {
            long start = System.nanoTime();
            accept(batchId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code));
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
                    .as("refused without waiting for the lock").isLessThan(MUST_ANSWER_WITHIN_MILLIS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** Thread A houdt het rijslot in een open transactie terwijl {@code whileHeld} draait. */
    private void holdingRowLock(String lockSql, long id, Runnable whileHeld) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        try {
            Future<?> a = holder.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                jdbc.queryForList(lockSql, id);
                locked.countDown();
                try {
                    release.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).as("thread A holds the row lock").isTrue();
            try {
                whileHeld.run();
            } finally {
                release.countDown();
            }
            a.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            holder.shutdownNow();
        }
    }

    private ResultActions accept(long batchId) throws Exception {
        return mockMvc.perform(post("/api/catalog-import/batches/{id}/accept-baseline", batchId).with(as(USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"acceptedBy\":\"" + USER + "\",\"reason\":\"" + REASON + "\"}"));
    }

    private void assertNothingWritten(Fixture f, long batchId) {
        assertThat(count("catalog_source_state", f.linkId())).as("catalog_source_state").isZero();
        assertThat(count("catalog_price_observation", f.linkId())).as("catalog_price_observation").isZero();
        assertThat(observations(f.linkId(), batchId)).isZero();
        assertThat(count("catalog_reference_state", f.linkId())).as("catalog_reference_state").isZero();
        assertThat(jdbc.queryForObject("select count(*) from catalog_source_state_price price "
                + "join catalog_source_state state on state.id = price.source_state_id "
                + "where state.import_link_id = ?", Long.class, f.linkId())).isZero();
        assertThat(skippedMutations(batchId)).isZero();
        ImportBatch batch = batches.findById(batchId).orElseThrow();
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(batch.getBaselineAcceptedBy()).isNull();
        assertThat(batch.getBaselineAcceptedAt()).isNull();
    }

    private long count(String table, long linkId) {
        return jdbc.queryForObject("select count(*) from " + table + " where import_link_id = ?", Long.class, linkId);
    }

    private long observations(long linkId, long batchId) {
        return jdbc.queryForObject("select count(*) from catalog_price_observation where import_link_id = ? "
                + "and batch_id = ?", Long.class, linkId, batchId);
    }

    private long skippedMutations(long batchId) {
        return jdbc.queryForObject("select count(*) from import_mutation where batch_id = ? and status = 'SKIPPED'",
                Long.class, batchId);
    }

    private long activeMemberships(long batchId) {
        return jdbc.queryForObject("select count(*) from publication_bundle_batch where batch_id = ? "
                + "and active_marker is not null", Long.class, batchId);
    }

    private static String bundleRef(String suffix) {
        return "BND-ATOM-" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + suffix;
    }

    /** Vijf regels R1..R5 met dezelfde basisprijs en een eigen EAN per regel (per test een eigen bibliotheek). */
    private static byte[] prices(String price) {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 5; i++) {
            csv.append("ACME;G1;R").append(i).append(';').append(price).append(";Artikel ").append(i)
                    .append(";EAN-").append(i).append('\n');
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private long screened(Fixture f, String reference, byte[] content) {
        long batchId = intake.intake(f.taskId(), reference, USER, null, null, "levering.csv",
                new ByteArrayInputStream(content)).delivery().batch().batchId();
        screening.screen(batchId);
        assertThat(batches.findById(batchId).orElseThrow().getStatus()).isEqualTo(ImportBatchStatus.SCREENED);
        return batchId;
    }

    private Fixture fixture(String prefix) {
        String unique = "AB" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
        SourceOrganisation organisation = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + "-ORG BV", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(new ImportDefinition(organisation,
                unique + "-DEF", unique + " catalogus", "beheerder@example.test"));
        ImportDefinitionRevision revision = new ImportDefinitionRevision(definition, 1,
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
        revision.setMaxCriticalSharePercent(new BigDecimal("100"));
        revision.setStatus(RevisionStatus.ACTIVE);
        revision.setRecordCanonicalisationVersion(2);
        ImportDefinitionRevision stored = revisions.saveAndFlush(revision);
        fieldMappings.saveAndFlush(eanMapping(stored));

        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        // Een eigen bibliotheek per test: een kritieke referentie is uniek per bibliotheek (par. 14.23.3).
        String libraryCode = unique.substring(0, Math.min(unique.length(), 20));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, libraryCode));
        CatalogImportTask task = tasks.saveAndFlush(
                new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL));
        return new Fixture(task.getId(), link.getId());
    }

    private ImportFieldMapping eanMapping(ImportDefinitionRevision revision) {
        ImportFieldCatalogEntry target = fieldCatalog.findById("EAN").orElseThrow();
        ImportFieldMapping mapping = new ImportFieldMapping(revision, 1, target, FieldValueKind.SOURCE_FIELD,
                target.getDataType(), target.getDefaultOwner(), target.getIdentityClass());
        mapping.setSourceReference("EAN");
        mapping.setReferenceType(target.getReferenceType());
        return mapping;
    }

    private record Fixture(long taskId, long linkId) {
    }
}
