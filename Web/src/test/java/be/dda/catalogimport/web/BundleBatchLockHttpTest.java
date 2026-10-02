package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S5-b (beslissingslog 2026-10-01 "Stap 4 en 5 uitgewerkt"): een batch waarvan het rijslot door een andere
 * transactie vastgehouden wordt, kan niet aan een bundel toegevoegd worden. {@code POST /bundles/{id}/batches}
 * geeft dan meteen 409 {@code BATCH_BEING_PROCESSED} (geen wachten), en na het vrijgeven van het slot lukt dezelfde
 * aanvraag. Tegen de echte PostgreSQL: thread A houdt {@code select ... for update} op de batchrij in een open
 * transactie.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=50",
        "catalogimport.screening.mutation-chunk-size=50",
        "catalogimport.screening.recovery-on-startup=false"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class BundleBatchLockHttpTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    private static final String CREATOR = "jan.peeters@example.test";
    private static final long MUST_ANSWER_WITHIN_MILLIS = 5_000;

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
    private ImportDefinitionRevisionRepository revisions;
    @Autowired
    private ImportLinkRepository links;
    @Autowired
    private CatalogImportTaskRepository tasks;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void aBatchUnderRowLockIsRefusedImmediatelyAndAddedOnceTheLockIsReleased() throws Exception {
        long taskId = fixtureTaskId("LOCK");
        long batchId = uploadAndScreen(taskId, "REF-LOCK");
        long bundleId = createBundle("BND-LOCK-" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet());
        String body = "{\"batchIds\":[" + batchId + "],\"addedBy\":\"" + CREATOR + "\"}";

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        try {
            Future<?> a = holder.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                jdbc.queryForList("select id from import_batch where id = ? for update", batchId);
                locked.countDown();
                try {
                    release.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).as("thread A holds the batch row lock").isTrue();

            long start = System.nanoTime();
            mockMvc.perform(post("/api/catalog-import/bundles/{id}/batches", bundleId).with(as(CREATOR))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("BATCH_BEING_PROCESSED"));
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
                    .as("refused without waiting for the lock").isLessThan(MUST_ANSWER_WITHIN_MILLIS);

            release.countDown();
            a.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            holder.shutdownNow();
        }

        mockMvc.perform(post("/api/catalog-import/bundles/{id}/batches", bundleId).with(as(CREATOR))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].batchId").value(batchId))
                .andExpect(jsonPath("$[0].active").value(true));
    }

    private long createBundle(String reference) throws Exception {
        String response = mockMvc.perform(post("/api/catalog-import/bundles").with(as(CREATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bundleReference\":\"" + reference + "\",\"targetMode\":\"SIMULATION\","
                                + "\"createdBy\":\"" + CREATOR + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    /** Uploadt en levert de batch-id op; de HTTP-intake screent synchroon. */
    private long uploadAndScreen(long taskId, String reference) throws Exception {
        String csv = HEADER + "ACME;G1;R1;1,00;Artikel 1\nACME;G1;R2;1,25;Artikel 2\n";
        String body = mockMvc.perform(multipart("/api/catalog-import/tasks/{id}/deliveries", taskId)
                        .file(new MockMultipartFile("file", "levering.csv", "text/csv",
                                csv.getBytes(StandardCharsets.UTF_8)))
                        .param("deliveryReference", reference)
                        .param("uploadedBy", "tester@example.test").with(as("tester@example.test")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SCREENED")).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.batchId")).longValue();
    }

    private long fixtureTaskId(String prefix) {
        String unique = "BL" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
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
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, "PSARF050"));
        CatalogImportTask task = tasks.saveAndFlush(new CatalogImportTask(link, unique + "-taak",
                TaskTriggerType.MANUAL));
        return task.getId();
    }
}
