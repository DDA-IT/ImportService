package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;

import be.dda.catalogimport.dao.CandidateStageDao;
import be.dda.catalogimport.dao.CandidateStageDao.StageRow;
import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.MutationDao;
import be.dda.catalogimport.dao.RowIssueDao;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.dao.TaskRunRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.DiscountCodeState;
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
import be.dda.catalogimport.service.DeliveryScreeningService.ScreeningOutcome;
import be.dda.catalogimport.service.ScreeningRecoveryService.RecoveryReport;
import be.dda.catalogimport.service.support.ImportIssueCatalog;
import be.dda.catalogimport.service.support.ImportValueRules;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Fase 2e: het herstel van onderbroken screenings ({@link ScreeningRecoveryService}) tegen de echte
 * services, DAO's en H2. Een process-kill halverwege het stagen laat zich in een test niet
 * nabootsen (een fout zet de batch al op FAILED), dus de toestand "blijven steken op SCREENING" wordt
 * rechtstreeks klaargezet met de echte DAO's.
 * <p>
 * De opstartrecovery staat uit voor de gedeelde applicatiecontext; de tests roepen {@code recover()} zelf
 * aan, of bouwen een tweede instantie met de schakelaar aan om het {@code ApplicationReadyEvent}-pad te
 * bewijzen. Elke test bouwt een eigen keten met unieke codes omdat de H2-database gedeeld is.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=2",
        "catalogimport.screening.mutation-chunk-size=2",
        "catalogimport.screening.recovery-on-startup=false"})
@ActiveProfiles("local")
class ScreeningRecoveryServiceTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String CSV = "LEVERANCIER;GROEP;REFERENTIE;PRIJS\n"
            + "ACME;G1;R1;1,50\nACME;G1;R2;2,25\nACME;G1;R3;3,00\nACME;G1;R4;4,00\nACME;G1;R5;5,00\n";

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @Autowired
    private ScreeningRecoveryService recovery;
    @Autowired
    private DeliveryIntakeService intake;
    @Autowired
    private DeliveryScreeningService screening;
    @MockitoSpyBean
    private MutationDao mutations;
    @Autowired
    private CandidateStageDao stage;
    @Autowired
    private RowIssueDao rowIssues;
    @Autowired
    private PlatformTransactionManager transactionManager;
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
    private ImportBatchRepository batches;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aBatchStuckOnScreeningBecomesFailedWithItsStagingAndIssuesRemovedAndItsTaskFreed() {
        Fixture f = fixture("STUCK");
        Stuck stuck = stuckOnScreening(f, "REF-1");
        assertThat(stage.countByBatchId(stuck.batchId())).isEqualTo(2L);
        assertThat(rowIssues.countByBatchId(stuck.batchId())).isEqualTo(1L);
        // Zolang de run open is, is de taak bezet.
        assertThat(runs.findByTaskIdAndConcurrencyTokenIsNotNull(f.taskId())).isPresent();

        RecoveryReport report = recovery.recover();

        assertThat(report.failedBatchIds()).contains(stuck.batchId());
        ImportBatch batch = batches.findById(stuck.batchId()).orElseThrow();
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.FAILED);
        assertThat(batch.getBlockedCode()).isEqualTo("SCREENING_INTERRUPTED");
        assertThat(batch.getBlockedReason()).startsWith("SCREENING_INTERRUPTED");
        assertThat(batch.getOpenMarker()).isNull();
        assertThat(batch.getFinishedAt()).isNotNull();
        assertThat(batch.getStagedRowCount()).isZero();
        assertThat(stage.countByBatchId(stuck.batchId())).isZero();
        assertThat(rowIssues.countByBatchId(stuck.batchId())).isZero();

        // FAILED schrijft nooit een marker of mutaties.
        assertThat(jdbc.queryForObject("select count(*) from import_mutation where batch_id = ?", Long.class,
                stuck.batchId())).isZero();

        // De run is FAILED en de concurrency-token is vrij: de taak is weer uploadbaar.
        TaskRun run = runs.findById(stuck.taskRunId()).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(TaskRunStatus.FAILED);
        assertThat(run.getConcurrencyToken()).isNull();
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(runs.findByTaskIdAndConcurrencyTokenIsNotNull(f.taskId())).isEmpty();
        assertThat(intake.intake(f.taskId(), "REF-2", "tester@example.test", null, null, "levering.csv",
                new ByteArrayInputStream(CSV.getBytes(StandardCharsets.UTF_8))).created()).isTrue();

        // Een tweede run doet niets meer voor deze batch.
        assertThat(recovery.recover().failedBatchIds()).doesNotContain(stuck.batchId());
    }

    @Test
    void aBatchInMutatingIsLeftUntouchedAndStaysResumable() {
        Fixture f = fixture("MUT");
        Delivered delivered = deliver(f, "REF-1");
        AtomicInteger chunk = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
            if (chunk.incrementAndGet() == 2) {
                throw new UncheckedIOException(new IOException("simulated crash after the first chunk"));
            }
            return invocation.callRealMethod();
        }).when(mutations).insertContentMutations(any(), anyLong(), anyLong(), any());
        try {
            screening.screen(delivered.batchId());
        } catch (UncheckedIOException expected) {
            // de batch staat nu op MUTATING
        }
        Mockito.reset(mutations);
        ImportBatch before = batches.findById(delivered.batchId()).orElseThrow();
        assertThat(before.getStatus()).isEqualTo(ImportBatchStatus.MUTATING);

        RecoveryReport report = recovery.recover();

        assertThat(report.resumableBatchIds()).contains(delivered.batchId());
        assertThat(report.failedBatchIds()).doesNotContain(delivered.batchId());
        ImportBatch after = batches.findById(delivered.batchId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ImportBatchStatus.MUTATING);
        assertThat(after.getBlockedCode()).isNull();
        assertThat(after.getMutationProgressRowNumber()).isEqualTo(before.getMutationProgressRowNumber());
        assertThat(stage.countByBatchId(delivered.batchId())).isEqualTo(5L);
        assertThat(mutations.countContentMutations(delivered.batchId())).isEqualTo(2L);
        assertThat(runs.findById(delivered.taskRunId()).orElseThrow().getStatus()).isEqualTo(TaskRunStatus.RUNNING);

        // En ze is nog steeds hervatbaar.
        ScreeningOutcome outcome = screening.continueMutating(delivered.batchId());
        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(mutations.countContentMutations(delivered.batchId())).isEqualTo(5L);
    }

    @Test
    void theStartupHookOnlyActsWhenRecoveryOnStartupIsEnabled() {
        Fixture f = fixture("HOOK");
        Stuck stuck = stuckOnScreening(f, "REF-1");

        // De gedeelde context heeft de schakelaar uit: het opstartpad doet niets.
        recovery.onApplicationReady();
        assertThat(batches.findById(stuck.batchId()).orElseThrow().getStatus()).isEqualTo(ImportBatchStatus.SCREENING);

        ScreeningRecoveryService disabled = new ScreeningRecoveryService(batches, runs, stage, rowIssues,
                transactionManager, false);
        disabled.onApplicationReady();
        assertThat(batches.findById(stuck.batchId()).orElseThrow().getStatus()).isEqualTo(ImportBatchStatus.SCREENING);

        ScreeningRecoveryService enabled = new ScreeningRecoveryService(batches, runs, stage, rowIssues,
                transactionManager, true);
        enabled.onApplicationReady();
        ImportBatch batch = batches.findById(stuck.batchId()).orElseThrow();
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.FAILED);
        assertThat(batch.getBlockedCode()).isEqualTo("SCREENING_INTERRUPTED");
        assertThat(runs.findById(stuck.taskRunId()).orElseThrow().getConcurrencyToken()).isNull();
    }

    // --- Helpers -------------------------------------------------------------------------------------------

    /** Zet een batch klaar zoals ze achterblijft na een process-kill halverwege het stagen. */
    private Stuck stuckOnScreening(Fixture f, String reference) {
        Delivered delivered = deliver(f, reference);
        ImportBatch batch = batches.findById(delivered.batchId()).orElseThrow();
        batch.setStatus(ImportBatchStatus.SCREENING);
        batch.setStartedAt(Instant.now());
        batch.setStagedRowCount(2);
        batches.saveAndFlush(batch);
        Instant now = Instant.now();
        stage.insertBatch(List.of(stageRow(delivered, 2, "R1", now), stageRow(delivered, 3, "R2", now)));
        // Classificatie loopt altijd via de catalogus; een test mag daar geen uitzondering op zijn.
        rowIssues.insertBatch(List.of(ImportIssueCatalog.issue(delivered.batchId(),
                delivered.deliveryFileId(), 4L, ImportValueRules.CODE_PRICE_UNREADABLE, "PRIJS", "12,3x",
                null, "unreadable price", now)));
        return new Stuck(delivered.batchId(), delivered.taskRunId());
    }

    private static StageRow stageRow(Delivered delivered, long rowNumber, String reference, Instant now) {
        byte[] hash = ImportValueRules.sha256Utf8(ImportValueRules.canonical(1, "ACME", "G1", reference));
        // Canonicalisatieversie 1 kent geen referentiedeelvingerafdruk: die blijft null.
        return new StageRow(delivered.batchId(), rowNumber, delivered.deliveryFileId(), "ACME", "G1", reference,
                null, DiscountCodeState.NOT_USED, hash, new BigDecimal("1.50"), null, null, hash, hash, null,
                hash, delivered.deliveryId() + ":1:" + reference, now);
    }

    private Delivered deliver(Fixture f, String reference) {
        DeliveryView view = intake.intake(f.taskId(), reference, "tester@example.test", null, null,
                "levering.csv", new ByteArrayInputStream(CSV.getBytes(StandardCharsets.UTF_8))).delivery();
        long fileId = jdbc.queryForObject("select id from delivery_file where delivery_id = ?", Long.class,
                view.deliveryId());
        long runId = runs.findByTaskIdAndConcurrencyTokenIsNotNull(f.taskId()).orElseThrow().getId();
        return new Delivered(view.deliveryId(), view.batch().batchId(), fileId, runId);
    }

    private Fixture fixture(String prefix) {
        String unique = "RC" + SEQUENCE.incrementAndGet() + "-" + prefix;
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
        revisions.saveAndFlush(revision);
        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, "PSARF050"));
        CatalogImportTask task = tasks.saveAndFlush(
                new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL));
        return new Fixture(task.getId());
    }

    private record Fixture(long taskId) {
    }

    private record Delivered(long deliveryId, long batchId, long deliveryFileId, long taskRunId) {
    }

    private record Stuck(long batchId, long taskRunId) {
    }
}
