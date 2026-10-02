package be.dda.catalogimport.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.DeliveryRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.PriceDeviationDao;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportBatchStatus;
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
import be.dda.catalogimport.service.SourceStateBaselineService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * S4-f (beslissingslog 2026-10-01 "Stap 4 en 5 uitgewerkt"): de verwerkingsclaim per batch onder echte
 * gelijktijdigheid, via HTTP ({@code POST /batches/{id}/continue}) tegen de echte database.
 * <p>
 * <b>Businessregel.</b> Een batch wordt nooit door twee workers tegelijk verwerkt; een levende claim van een
 * andere worker blijft ongemoeid, een verlopen claim wordt overgenomen.
 * <p>
 * <b>Hoe de race betrouwbaar is.</b> De onderbroken batch (MUTATING, 4 van 12 regels beoordeeld) heeft bij het
 * hervatten nog vier chunks van de prijscontrole te doen. De spy op {@link PriceDeviationDao} vertraagt elke chunk
 * met {@value #CHUNK_DELAY_MILLIS} ms, zodat de verwerking van de winnaar minstens een halve seconde duurt en de
 * verliezer ruim binnen die tijd zijn aanvraag doet. Beide threads starten op dezelfde {@link CountDownLatch}. De
 * spy telt bovendien het aantal gelijktijdige aanroepen: een tweede verwerking die toch meedraait zou dat maximum
 * boven 1 duwen.
 * <p>
 * De verliezer krijgt 409 {@code BATCH_BEING_PROCESSED} (de claim van de winnaar leeft) of, als zijn aanvraag pas
 * na afloop aan de beurt komt, {@code BATCH_NOT_RESUMABLE}; beide zijn correct, "nooit twee verwerkingen tegelijk"
 * is de te bewijzen eigenschap.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=4",
        "catalogimport.screening.mutation-chunk-size=4",
        "catalogimport.screening.price-control-chunk-size=2",
        "catalogimport.screening.max-sample-rows-per-code=3",
        "catalogimport.screening.recovery-on-startup=false"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class BatchProcessingClaimConcurrencyTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final long CHUNK_DELAY_MILLIS = 200;
    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @MockitoSpyBean
    private PriceDeviationDao deviations;
    @Autowired
    private DeliveryIntakeService intake;
    @Autowired
    private DeliveryScreeningService screening;
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
    private DeliveryRepository deliveries;
    @Autowired
    private ImportBatchRepository batches;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private Clock clock;

    @AfterEach
    void resetSpy() {
        Mockito.reset(deviations);
    }

    // --- 1. Twee gelijktijdige hervattingen ---------------------------------------------------------

    @Test
    void twoSimultaneousResumesNeverProcessTheBatchTwice() throws Exception {
        // Referentierun: dezelfde situatie, één hervatting zonder concurrent.
        long reference = interruptedBatch("REFSEQ");
        AtomicInteger referenceCalls = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
            referenceCalls.incrementAndGet();
            return invocation.callRealMethod();
        }).when(deviations).findCandidates(anyLong(), anyLong(), anyInt(), anyInt(), anyLong(), anyLong());
        continueBatch(reference).andExpect(status().isOk());
        Mockito.reset(deviations);
        assertThat(referenceCalls.get()).isPositive();
        Snapshot expected = snapshot(reference);
        assertThat(expected.groupOccurrences()).isEqualTo(12L);

        long batchId = interruptedBatch("RACE");
        assertThat(occurrenceCount(batchId)).isEqualTo(4L);

        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            calls.incrementAndGet();
            try {
                Thread.sleep(CHUNK_DELAY_MILLIS);
                return invocation.callRealMethod();
            } finally {
                inFlight.decrementAndGet();
            }
        }).when(deviations).findCandidates(anyLong(), anyLong(), anyInt(), anyInt(), anyLong(), anyLong());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Outcome> resume = () -> {
                ready.countDown();
                go.await();
                MvcResult result = mockMvc.perform(post("/api/catalog-import/batches/{id}/continue", batchId))
                        .andReturn();
                String body = result.getResponse().getContentAsString();
                return new Outcome(result.getResponse().getStatus(), body);
            };
            Future<Outcome> first = pool.submit(resume);
            Future<Outcome> second = pool.submit(resume);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Outcome> outcomes = new ArrayList<>(List.of(first.get(60, TimeUnit.SECONDS),
                    second.get(60, TimeUnit.SECONDS)));

            // Precies één slaagt; de andere wordt geweigerd met een 409 die geen tweede verwerking toelaat.
            assertThat(outcomes).filteredOn(o -> o.status() == 200).hasSize(1);
            Outcome loser = outcomes.stream().filter(o -> o.status() != 200).findFirst().orElseThrow();
            assertThat(loser.status()).isEqualTo(409);
            assertThat(loser.body()).containsAnyOf("\"BATCH_BEING_PROCESSED\"", "\"BATCH_NOT_RESUMABLE\"");
        } finally {
            pool.shutdownNow();
        }

        // Nooit twee verwerkingen tegelijk, en de overgebleven chunks zijn exact één keer doorlopen.
        assertThat(maxInFlight.get()).as("max concurrent price control chunks").isEqualTo(1);
        assertThat(calls.get()).as("price control chunks run by both resumes together")
                .isEqualTo(referenceCalls.get());

        // Eindtoestand identiek aan de referentierun.
        assertThat(batches.findById(batchId).orElseThrow().getStatus()).isEqualTo(ImportBatchStatus.SCREENED);
        Snapshot actual = snapshot(batchId);
        assertThat(actual).isEqualTo(expected);
        assertThat(markerCount(batchId)).isEqualTo(1L);
        assertNoClaim(batchId);
    }

    // --- 2. Levende vreemde claim ----------------------------------------------------------------------

    @Test
    void aLiveClaimOfAnotherWorkerRefusesTheResumeAndChangesNothing() throws Exception {
        long batchId = interruptedBatch("LIVE");
        Snapshot before = snapshot(batchId);
        UUID foreign = UUID.randomUUID();
        setForeignClaim(batchId, foreign, Duration.ZERO);

        continueBatch(batchId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_BEING_PROCESSED"));

        assertThat(batches.findById(batchId).orElseThrow().getStatus()).isEqualTo(ImportBatchStatus.MUTATING);
        assertThat(snapshot(batchId)).isEqualTo(before);
        assertThat(markerCount(batchId)).isZero();
        assertThat(jdbc.queryForObject("select processing_claim_token from import_batch where id = ?",
                UUID.class, batchId)).isEqualTo(foreign);
        assertThat(jdbc.queryForObject("select processing_claimed_by from import_batch where id = ?",
                String.class, batchId)).startsWith("other-host-");
        mockMvc.perform(get("/api/catalog-import/batches/{id}", batchId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MUTATING"))
                .andExpect(jsonPath("$.processingActive").value(true));
    }

    // --- 3. Verlopen vreemde claim ---------------------------------------------------------------------

    @Test
    void anExpiredClaimOfAnotherWorkerIsTakenOverAndTheBatchIsFinished() throws Exception {
        long reference = interruptedBatch("EXPREF");
        continueBatch(reference).andExpect(status().isOk());
        Snapshot expected = snapshot(reference);

        long batchId = interruptedBatch("EXPIRED");
        // Default lease is 60 minuten: twee uur oud is dus zeker verlopen.
        setForeignClaim(batchId, UUID.randomUUID(), Duration.ofHours(2));
        mockMvc.perform(get("/api/catalog-import/batches/{id}", batchId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.processingActive").value(false));

        continueBatch(batchId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SCREENED"));

        assertThat(batches.findById(batchId).orElseThrow().getStatus()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(snapshot(batchId)).isEqualTo(expected);
        assertThat(markerCount(batchId)).isEqualTo(1L);
        assertNoClaim(batchId);
    }

    // --- Helpers ---------------------------------------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions continueBatch(long batchId) throws Exception {
        return mockMvc.perform(post("/api/catalog-import/batches/{id}/continue", batchId));
    }

    /** Een batch die echt op MUTATING staat: de prijscontrole valt na twee chunks (4 regels) weg. */
    private long interruptedBatch(String prefix) {
        Fixture fixture = fixture(prefix);
        long first = deliver(fixture, "REF-1", rows("100,00"));
        screening.screen(first);
        baseline.acceptBaseline(first, "tester@example.test", "nulmeting");

        long batchId = deliver(fixture, "REF-2", rows("200,00"));
        AtomicInteger chunk = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
            if (chunk.incrementAndGet() == 3) {
                throw new UncheckedIOException(new IOException("simulated crash halfway"));
            }
            return invocation.callRealMethod();
        }).when(deviations).findCandidates(anyLong(), anyLong(), anyInt(), anyInt(), anyLong(), anyLong());
        try {
            screening.screen(batchId);
            throw new AssertionError("the simulated crash did not propagate");
        } catch (UncheckedIOException expected) {
            assertThat(expected).hasRootCauseInstanceOf(IOException.class);
        }
        Mockito.reset(deviations);
        assertThat(batches.findById(batchId).orElseThrow().getStatus()).isEqualTo(ImportBatchStatus.MUTATING);
        // De onderbreking geeft de claim meteen vrij: de batch is direct hervatbaar.
        assertNoClaim(batchId);
        return batchId;
    }

    /** Zet rechtstreeks een claim van een andere instantie; {@code age} is de ouderdom van de heartbeat. */
    private void setForeignClaim(long batchId, UUID token, Duration age) {
        OffsetDateTime beat = OffsetDateTime.ofInstant(clock.instant().minus(age), ZoneOffset.UTC);
        int updated = jdbc.update("update import_batch set processing_claim_token = ?, processing_claimed_at = ?, "
                        + "processing_heartbeat_at = ?, processing_claimed_by = ? where id = ?",
                token, beat, beat, "other-host-" + token + "/" + UUID.randomUUID(), batchId);
        assertThat(updated).isEqualTo(1);
    }

    private void assertNoClaim(long batchId) {
        Map<String, Object> row = jdbc.queryForMap("select processing_claim_token, processing_claimed_at, "
                + "processing_heartbeat_at, processing_claimed_by from import_batch where id = ?", batchId);
        assertThat(row.values()).containsOnlyNulls();
    }

    /** Alles wat een dubbele verwerking zou verraden, vergelijkbaar tussen een referentierun en de race. */
    private Snapshot snapshot(long batchId) {
        List<String> groups = jdbc.queryForList("select signature || '=' || occurrence_count || '/' "
                + "|| recorded_sample_count || '/' || is_bulk_incident from import_issue_group "
                + "where batch_id = ? order by signature", String.class, batchId);
        Long occurrences = jdbc.queryForObject("select coalesce(sum(occurrence_count), 0) from import_issue_group "
                + "where batch_id = ?", Long.class, batchId);
        Map<String, Long> issuesByCode = new TreeMap<>();
        jdbc.query("select issue_code, count(*) from import_row_issue where batch_id = ? group by issue_code",
                rs -> {
                    issuesByCode.put(rs.getString(1), rs.getLong(2));
                }, batchId);
        Long mutationRows = jdbc.queryForObject("select count(*) from import_mutation where batch_id = ?",
                Long.class, batchId);
        return new Snapshot(groups, occurrences == null ? 0L : occurrences, issuesByCode, mutationRows);
    }

    private long occurrenceCount(long batchId) {
        return snapshot(batchId).groupOccurrences();
    }

    private long markerCount(long batchId) {
        return jdbc.queryForObject("select count(*) from import_mutation where batch_id = ? "
                + "and action_type = 'IMPORT_MARKER'", Long.class, batchId);
    }

    private static String rows(String price) {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 12; i++) {
            csv.append("ACME;G1;R").append(i).append(';').append(price).append(";Boormachine\n");
        }
        return csv.toString();
    }

    private long deliver(Fixture fixture, String reference, String csv) {
        DeliveryView view = intake.intake(fixture.taskId(), reference, "tester@example.test", null, null,
                "levering.csv", new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))).delivery();
        deliveries.findById(view.deliveryId()).orElseThrow();
        return view.batch().batchId();
    }

    private Fixture fixture(String prefix) {
        String unique = "BPC" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-"
                + UUID.randomUUID().toString().substring(0, 8) + "-" + prefix;
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
        revision.setStatus(RevisionStatus.ACTIVE);
        ImportDefinitionRevision stored = revisions.saveAndFlush(revision);
        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(new ImportLink(unique + "-LINK", unique + " koppeling", definition,
                supplier, unique.substring(0, Math.min(unique.length(), 20))));
        CatalogImportTask task = tasks.saveAndFlush(
                new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL));
        return new Fixture(task.getId(), link.getId(), stored.getId());
    }

    private record Fixture(long taskId, long linkId, long revisionId) {
    }

    private record Outcome(int status, String body) {
    }

    private record Snapshot(List<String> groups, long groupOccurrences, Map<String, Long> issuesByCode,
                            long mutationRows) {
    }
}
