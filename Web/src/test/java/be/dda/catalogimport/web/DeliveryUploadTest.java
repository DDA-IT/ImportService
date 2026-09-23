package be.dda.catalogimport.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.DeliveryFileRepository;
import be.dda.catalogimport.dao.DeliveryRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.dao.TaskRunRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.Delivery;
import be.dda.catalogimport.domain.DeliveryFile;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskRun;
import be.dda.catalogimport.domain.TaskRunStatus;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.service.DeliveryScreeningService;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * Fase 2b/2d: upload van een manuele levering (archief + registratie + synchrone screening), retry,
 * conflicten en validatie via de echte controller/service/H2-database. Elke test maakt een eigen
 * taak met unieke codes omdat de H2-database gedeeld is.
 * <p>
 * Sinds bouwstap 2d screent de POST synchroon (design par. 10): een geslaagde upload antwoordt met
 * batchstatus {@code SCREENED} en sluit haar {@code TaskRun} af, zodat een volgende upload op
 * dezelfde taak weer toegelaten is. De concurrency-constraint wordt daarom expliciet aangetoond met
 * een screening die kunstmatig wordt opgehouden.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class DeliveryUploadTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    /** Enkel gebruikt om de screening kunstmatig op te houden in de gelijktijdigheidstest. */
    @MockitoSpyBean
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
    private TaskRunRepository runs;
    @Autowired
    private DeliveryRepository deliveries;
    @Autowired
    private DeliveryFileRepository deliveryFiles;
    @Autowired
    private ImportBatchRepository batches;
    @Autowired
    private JdbcTemplate jdbc;

    private static final byte[] CSV = ("LEVERANCIER;GROEP;REFERENTIE;PRIJS\n"
            + "ACME;G1;R1;1,50\n"
            + "ACME;G1;R2;2,25\n").getBytes(StandardCharsets.UTF_8);

    // --- Happy path ----------------------------------------------------------------------------

    @Test
    void registersTheDeliveryFileBatchAndRunAndKeepsTheBytesOutOfTheDatabase() throws Exception {
        Fixture f = fixture("HAPPY");

        String body = upload(f.task(), "REF-1", "tester@example.test", "../evil.csv", CSV)
                .andExpect(status().isCreated())
                // De screening loopt synchroon mee in de POST (design par. 10).
                .andExpect(jsonPath("$.status").value("SCREENED"))
                .andExpect(jsonPath("$.newCount").value(2))
                .andExpect(jsonPath("$.contentMutationCount").value(2))
                .andExpect(jsonPath("$.blockedCode").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        long deliveryId = ((Number) JsonPath.read(body, "$.deliveryId")).longValue();
        long batchId = ((Number) JsonPath.read(body, "$.batchId")).longValue();

        Delivery delivery = deliveries.findByTaskIdAndIdempotencyKey(f.task().getId(), "manual:REF-1").orElseThrow();
        assertThat(delivery.getId()).isEqualTo(deliveryId);
        assertThat(delivery.getExpectedFileCount()).isEqualTo(1);
        assertThat(delivery.getActualFileCount()).isEqualTo(1);
        assertThat(delivery.getExpectedRecordCount()).isNull();
        assertThat(delivery.getExpectedByteSize()).isNull();
        assertThat(delivery.getActualRecordCount()).isEqualTo(2L);
        assertThat(delivery.getActualByteSize()).isEqualTo(CSV.length);
        assertThat(delivery.isCompletenessProven()).isFalse();
        assertThat(delivery.getManifestReference()).isNull();

        List<DeliveryFile> files = deliveryFiles.findByDeliveryIdOrderBySequenceNumberAsc(deliveryId);
        assertThat(files).singleElement().satisfies(file -> {
            assertThat(file.getFileName()).isEqualTo("../evil.csv"); // origineel ongewijzigd
            assertThat(file.getContentHash()).isEqualTo(sha256Hex(CSV));
            assertThat(file.getHashAlgorithm()).isEqualTo("SHA-256");
            assertThat(file.getByteSize()).isEqualTo(CSV.length);
            assertThat(file.getArchiveReference()).endsWith("/evil.csv").doesNotContain("..");
            assertThat(archiveRoot.resolve(file.getArchiveReference())).hasBinaryContent(CSV);
        });

        ImportBatch batch = batches.findById(batchId).orElseThrow();
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(batch.getAttemptNo()).isEqualTo(1);
        assertThat(batch.getCreatedBy()).isEqualTo("tester@example.test");
        assertThat(jdbc.queryForObject("select delivery_id from import_batch where id = ?", Long.class, batchId))
                .isEqualTo(deliveryId);
        assertThat(jdbc.queryForObject("select definition_revision_id from import_batch where id = ?", Long.class,
                batchId)).isEqualTo(f.revision().getId());
        assertThat(jdbc.queryForObject("select import_link_id from import_batch where id = ?", Long.class, batchId))
                .isEqualTo(f.link().getId());

        // De screening sluit de run af en geeft de concurrency-token vrij.
        TaskRun run = runs.findByTaskIdOrderByStartedAtDesc(f.task().getId()).get(0);
        assertThat(run.getStatus()).isEqualTo(TaskRunStatus.COMPLETED);
        assertThat(run.getConcurrencyToken()).isNull();
        assertThat(run.getTriggeredBy()).isEqualTo("tester@example.test");
        assertThat(jdbc.queryForObject("select task_run_id from delivery where id = ?", Long.class, deliveryId))
                .isEqualTo(run.getId());
        assertThat(jdbc.queryForObject("select task_run_id from import_batch where id = ?", Long.class, batchId))
                .isEqualTo(run.getId());

        // De bytes staan enkel in het archief: geen binaire/LOB-kolommen op delivery/delivery_file.
        assertThat(jdbc.queryForObject(
                "select count(*) from information_schema.columns where upper(table_name) in "
                        + "('DELIVERY', 'DELIVERY_FILE') and (upper(data_type) like '%BINARY%' "
                        + "or upper(data_type) like '%LARGE OBJECT%')", Long.class)).isZero();
    }

    @Test
    void persistsTheOptionalExpectedCountsWhenProvided() throws Exception {
        Fixture f = fixture("EXP");

        upload(f.task(), "REF-EXP", "tester@example.test", "levering.csv", CSV,
                "expectedRecordCount", "2", "expectedByteSize", String.valueOf(CSV.length))
                .andExpect(status().isCreated());

        Delivery delivery = deliveries.findByTaskIdAndIdempotencyKey(f.task().getId(), "manual:REF-EXP")
                .orElseThrow();
        assertThat(delivery.getExpectedRecordCount()).isEqualTo(2L);
        assertThat(delivery.getExpectedByteSize()).isEqualTo((long) CSV.length);
        assertThat(delivery.getActualRecordCount()).isEqualTo(2L);
        // Tellen en vergelijken bewijst de volledigheid niet: fase 2 kent geen volledigheidscontract.
        assertThat(delivery.isCompletenessProven()).isFalse();
    }

    @Test
    void exposesTheDeliveryWithFileAndBatchStatusButNotTheArchivePath() throws Exception {
        Fixture f = fixture("GET");
        String body = upload(f.task(), "REF-GET", "tester@example.test", "levering.csv", CSV)
                .andReturn().getResponse().getContentAsString();
        long deliveryId = ((Number) JsonPath.read(body, "$.deliveryId")).longValue();

        mockMvc.perform(get("/api/catalog-import/deliveries/{id}", deliveryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryId").value(deliveryId))
                .andExpect(jsonPath("$.taskId").value(f.task().getId()))
                .andExpect(jsonPath("$.idempotencyKey").value("manual:REF-GET"))
                .andExpect(jsonPath("$.expectedRecordCount").doesNotExist())
                .andExpect(jsonPath("$.actualByteSize").value(CSV.length))
                .andExpect(jsonPath("$.completenessProven").value(false))
                .andExpect(jsonPath("$.files[0].fileName").value("levering.csv"))
                .andExpect(jsonPath("$.files[0].contentHash").value(sha256Hex(CSV)))
                .andExpect(jsonPath("$.files[0].archiveReference").doesNotExist())
                .andExpect(jsonPath("$.batch.status").value("SCREENED"))
                .andExpect(jsonPath("$.batch.attemptNo").value(1))
                .andExpect(jsonPath("$.batch.newCount").value(2))
                .andExpect(jsonPath("$.batch.unchangedCount").value(0))
                .andExpect(jsonPath("$.batch.contentMutationCount").value(2));
    }

    @Test
    void answers404ForAnUnknownDelivery() throws Exception {
        mockMvc.perform(get("/api/catalog-import/deliveries/{id}", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DELIVERY_NOT_FOUND"))
                .andExpect(jsonPath("$.error").exists());
    }

    // --- Retry en conflicten -------------------------------------------------------------------

    @Test
    void anIdenticalRetryReturnsTheExistingDeliveryWithoutASecondBatchRunOrArchiveObject() throws Exception {
        Fixture f = fixture("RETRY");
        String first = upload(f.task(), "REF-R", "tester@example.test", "levering.csv", CSV)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long archivedBefore = archivedFileCount();

        String second = upload(f.task(), "REF-R", "tester@example.test", "levering.csv", CSV)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(((Number) JsonPath.read(second, "$.deliveryId")).longValue())
                .isEqualTo(((Number) JsonPath.read(first, "$.deliveryId")).longValue());
        assertThat(((Number) JsonPath.read(second, "$.batchId")).longValue())
                .isEqualTo(((Number) JsonPath.read(first, "$.batchId")).longValue());
        assertThat(deliveries.findByTaskIdOrderByReceivedAtDesc(f.task().getId())).hasSize(1);
        assertThat(runs.findByTaskIdOrderByStartedAtDesc(f.task().getId())).hasSize(1);
        long deliveryId = ((Number) JsonPath.read(first, "$.deliveryId")).longValue();
        assertThat(batches.findByDeliveryIdOrderByAttemptNoAsc(deliveryId)).hasSize(1);
        assertThat(archivedFileCount()).isEqualTo(archivedBefore); // het retry-object is opgeruimd
    }

    @Test
    void reusingAReferenceWithDifferentContentIsRejectedAndTheNewArchiveObjectIsRemoved() throws Exception {
        Fixture f = fixture("DIFF");
        upload(f.task(), "REF-D", "tester@example.test", "levering.csv", CSV).andExpect(status().isCreated());
        long archivedBefore = archivedFileCount();
        byte[] other = "LEVERANCIER;GROEP;REFERENTIE;PRIJS\nACME;G1;R1;9,99\n".getBytes(StandardCharsets.UTF_8);

        upload(f.task(), "REF-D", "tester@example.test", "levering.csv", other)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELIVERY_REFERENCE_REUSED_WITH_DIFFERENT_CONTENT"))
                .andExpect(jsonPath("$.error").exists());

        List<Delivery> stored = deliveries.findByTaskIdOrderByReceivedAtDesc(f.task().getId());
        assertThat(stored).hasSize(1);
        assertThat(deliveryFiles.findByDeliveryIdOrderBySequenceNumberAsc(stored.get(0).getId()))
                .singleElement().satisfies(file -> assertThat(file.getContentHash()).isEqualTo(sha256Hex(CSV)));
        assertThat(archivedFileCount()).isEqualTo(archivedBefore);
    }

    @Test
    void aSecondUploadWithAnotherReferenceIsAcceptedOnceTheFirstScreeningClosedItsRun() throws Exception {
        Fixture f = fixture("NEXT");
        upload(f.task(), "REF-1", "tester@example.test", "levering.csv", CSV).andExpect(status().isCreated());

        // De eerste screening is afgerond, dus de taak is opnieuw beschikbaar: een tweede levering
        // met een eigen referentie is een nieuwe verwerking met een eigen run en batch.
        upload(f.task(), "REF-2", "tester@example.test", "levering2.csv", CSV)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SCREENED"));

        assertThat(deliveries.findByTaskIdOrderByReceivedAtDesc(f.task().getId())).hasSize(2);
        assertThat(runs.findByTaskIdOrderByStartedAtDesc(f.task().getId())).hasSize(2);
        assertThat(runs.findByTaskIdAndConcurrencyTokenIsNotNull(f.task().getId())).isEmpty();
    }

    @Test
    void aSecondUploadWhileTheFirstIsStillScreeningIsRejectedWithTaskRunInProgress() throws Exception {
        Fixture f = fixture("BUSY");
        CountDownLatch screeningMayFinish = new CountDownLatch(1);
        CountDownLatch screeningStarted = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
            screeningStarted.countDown();
            screeningMayFinish.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(screening).screen(ArgumentMatchers.anyLong());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> first = pool.submit(() -> mockMvc
                    .perform(request(f.task().getId(), "REF-1", "tester@example.test", "levering.csv", CSV))
                    .andReturn().getResponse().getStatus());
            assertThat(screeningStarted.await(10, TimeUnit.SECONDS)).isTrue();
            long archivedBefore = archivedFileCount();

            // De run van de eerste levering is nog open: de tweede upload archiveert niets en botst.
            upload(f.task(), "REF-2", "tester@example.test", "levering2.csv", CSV)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TASK_RUN_IN_PROGRESS"));
            assertThat(archivedFileCount()).isEqualTo(archivedBefore);

            screeningMayFinish.countDown();
            assertThat(first.get()).isEqualTo(201);
        } finally {
            screeningMayFinish.countDown();
            pool.shutdownNow();
        }
        assertThat(deliveries.findByTaskIdOrderByReceivedAtDesc(f.task().getId())).hasSize(1);
        assertThat(runs.findByTaskIdOrderByStartedAtDesc(f.task().getId())).hasSize(1);
    }

    @Test
    void twoSimultaneousUploadsOnTheSameTaskLeaveExactlyOneDeliveryRunAndArchiveObject() throws Exception {
        Fixture f = fixture("RACE");
        CountDownLatch start = new CountDownLatch(1);
        // Houd de winnende screening vast tot de andere poging haar antwoord heeft: alleen de
        // verliezer kan als eerste antwoorden, dus botst die gegarandeerd op een nog open run in
        // plaats van op toeval in de planning.
        CountDownLatch otherAnswered = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
            otherAnswered.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(screening).screen(ArgumentMatchers.anyLong());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> calls = List.of(
                    () -> raceUpload(f.task(), "REF-A", start, otherAnswered),
                    () -> raceUpload(f.task(), "REF-B", start, otherAnswered));
            List<Future<Integer>> futures = calls.stream().map(pool::submit).toList();
            start.countDown();
            List<Integer> statuses = List.of(futures.get(0).get(), futures.get(1).get());

            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(deliveries.findByTaskIdOrderByReceivedAtDesc(f.task().getId())).hasSize(1);
        assertThat(runs.findByTaskIdOrderByStartedAtDesc(f.task().getId())).hasSize(1);
        assertThat(archivedFilesNamed("race.csv")).isEqualTo(1);
    }

    // --- Configuratie en taak ------------------------------------------------------------------

    @Test
    void rejectsAnUploadWithoutAnActiveRevisionAndArchivesNothing() throws Exception {
        Fixture f = fixture("NOREV", revision -> revision.setStatus(RevisionStatus.DRAFT));
        long archivedBefore = archivedFileCount();

        upload(f.task(), "REF-1", "tester@example.test", "levering.csv", CSV)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_ACTIVE_REVISION"));

        assertThat(deliveries.findByTaskIdOrderByReceivedAtDesc(f.task().getId())).isEmpty();
        assertThat(runs.findByTaskIdOrderByStartedAtDesc(f.task().getId())).isEmpty();
        assertThat(archivedFileCount()).isEqualTo(archivedBefore);
    }

    @Test
    void rejectsAnUploadWhenTheActiveRevisionHasNoBasePriceField() throws Exception {
        Fixture f = fixture("NOPRICE", revision -> revision.setRecordBasePriceField(null));
        long archivedBefore = archivedFileCount();

        upload(f.task(), "REF-1", "tester@example.test", "levering.csv", CSV)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFIG_PRICE_FIELD_MISSING"));

        assertThat(deliveries.findByTaskIdOrderByReceivedAtDesc(f.task().getId())).isEmpty();
        assertThat(archivedFileCount()).isEqualTo(archivedBefore);
    }

    @Test
    void rejectsAnUploadOnAnUnknownTaskWith404() throws Exception {
        long archivedBefore = archivedFileCount();

        mockMvc.perform(request(999_999_999L, "REF-1", "tester@example.test", "levering.csv", CSV))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));

        assertThat(archivedFileCount()).isEqualTo(archivedBefore);
    }

    @Test
    void rejectsAnUploadOnATaskThatIsNotManual() throws Exception {
        Fixture f = fixture("SCHED", null, task -> {
            task.setTriggerType(TaskTriggerType.SCHEDULED);
            task.setTriggerExpression("0 0 4 * * *");
        });
        long archivedBefore = archivedFileCount();

        upload(f.task(), "REF-1", "tester@example.test", "levering.csv", CSV)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_NOT_MANUAL"));

        assertThat(deliveries.findByTaskIdOrderByReceivedAtDesc(f.task().getId())).isEmpty();
        assertThat(archivedFileCount()).isEqualTo(archivedBefore);
    }

    // --- Ongeldige aanvraag --------------------------------------------------------------------

    @Test
    void rejectsABlankUploadedByAndCreatesNothing() throws Exception {
        Fixture f = fixture("NOUSER");
        long archivedBefore = archivedFileCount();

        upload(f.task(), "REF-1", "  ", "levering.csv", CSV).andExpect(status().isBadRequest());
        upload(f.task(), "REF-1", "", "levering.csv", CSV).andExpect(status().isBadRequest());

        assertThat(deliveries.findByTaskIdOrderByReceivedAtDesc(f.task().getId())).isEmpty();
        assertThat(runs.findByTaskIdOrderByStartedAtDesc(f.task().getId())).isEmpty();
        assertThat(archivedFileCount()).isEqualTo(archivedBefore);
    }

    @Test
    void rejectsAMissingOrBlankDeliveryReferenceAndNegativeExpectedValues() throws Exception {
        Fixture f = fixture("BADREQ");
        long archivedBefore = archivedFileCount();

        upload(f.task(), " ", "tester@example.test", "levering.csv", CSV).andExpect(status().isBadRequest());
        mockMvc.perform(multipart("/api/catalog-import/tasks/{id}/deliveries", f.task().getId())
                        .file(new MockMultipartFile("file", "levering.csv", "text/csv", CSV))
                        .param("uploadedBy", "tester@example.test"))
                .andExpect(status().isBadRequest());
        upload(f.task(), "REF-1", "tester@example.test", "levering.csv", CSV, "expectedRecordCount", "-1")
                .andExpect(status().isBadRequest());
        upload(f.task(), "REF-1", "tester@example.test", "levering.csv", CSV, "expectedByteSize", "-5")
                .andExpect(status().isBadRequest());
        upload(f.task(), "REF-1", "tester@example.test", "levering.csv", CSV, "expectedRecordCount", "veel")
                .andExpect(status().isBadRequest());

        assertThat(deliveries.findByTaskIdOrderByReceivedAtDesc(f.task().getId())).isEmpty();
        assertThat(archivedFileCount()).isEqualTo(archivedBefore);
    }

    // --- Helpers -------------------------------------------------------------------------------

    private int raceUpload(CatalogImportTask task, String reference, CountDownLatch start,
                           CountDownLatch answered) throws Exception {
        start.await();
        try {
            MockHttpServletResponse response = mockMvc
                    .perform(request(task.getId(), reference, "tester@example.test", "race.csv", CSV))
                    .andReturn().getResponse();
            if (response.getStatus() == 409) {
                // Ofwel de voorcontrole, ofwel de vertaalde uk_task_run_concurrency-fout.
                assertThat(response.getContentAsString()).contains("TASK_RUN_IN_PROGRESS");
            }
            return response.getStatus();
        } finally {
            answered.countDown();
        }
    }

    private ResultActions upload(CatalogImportTask task, String reference, String uploadedBy, String fileName,
                                 byte[] bytes, String... extraParams) throws Exception {
        return mockMvc.perform(request(task.getId(), reference, uploadedBy, fileName, bytes, extraParams));
    }

    private MockMultipartHttpServletRequestBuilder request(long taskId, String reference, String uploadedBy,
                                                           String fileName, byte[] bytes, String... extraParams) {
        MockMultipartHttpServletRequestBuilder builder = multipart("/api/catalog-import/tasks/{id}/deliveries", taskId)
                .file(new MockMultipartFile("file", fileName, "text/csv", bytes));
        builder.param("deliveryReference", reference);
        builder.param("uploadedBy", uploadedBy);
        for (int i = 0; i + 1 < extraParams.length; i += 2) {
            builder.param(extraParams[i], extraParams[i + 1]);
        }
        return builder;
    }

    private long archivedFileCount() throws Exception {
        try (var walk = Files.walk(archiveRoot)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    private long archivedFilesNamed(String fileName) throws Exception {
        try (var walk = Files.walk(archiveRoot)) {
            return walk.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().equals(fileName)).count();
        }
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private Fixture fixture(String prefix) {
        return fixture(prefix, null, null);
    }

    private Fixture fixture(String prefix, Consumer<ImportDefinitionRevision> revisionCustomiser) {
        return fixture(prefix, revisionCustomiser, null);
    }

    /** Volledige keten tot en met een MANUAL-taak met een ACTIEVE revisie met prijsveld. */
    private Fixture fixture(String prefix, Consumer<ImportDefinitionRevision> revisionCustomiser,
                            Consumer<CatalogImportTask> taskCustomiser) {
        String unique = "UP" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
        SourceOrganisation organisation = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + "-ORG BV", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(
                new ImportDefinition(organisation, unique + "-DEF", unique + " catalogus", "beheerder@example.test"));
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
        revision.setStatus(RevisionStatus.ACTIVE);
        if (revisionCustomiser != null) {
            revisionCustomiser.accept(revision);
        }
        revision = revisions.saveAndFlush(revision);
        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, "PSARF050"));
        CatalogImportTask task = new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL);
        if (taskCustomiser != null) {
            taskCustomiser.accept(task);
        }
        task = tasks.saveAndFlush(task);
        return new Fixture(task, link, revision);
    }

    private record Fixture(CatalogImportTask task, ImportLink link, ImportDefinitionRevision revision) {
    }
}
