package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.CandidateStageDao;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.RowIssueDao;
import be.dda.catalogimport.dao.TaskRunRepository;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.TaskRun;
import be.dda.catalogimport.domain.TaskRunStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Herstel bij het opstarten van de applicatie (design par. 9): een screening die halverwege wegviel,
 * mag niet eeuwig open blijven staan en de {@code TaskRun}-concurrency-token vasthouden.
 * <ul>
 *   <li>{@code SCREENING} ⇒ {@code FAILED} met {@code blocked_code=SCREENING_INTERRUPTED}. Staging en
 *       regelproblemen van die poging worden verwijderd, de {@code TaskRun} gaat naar {@code FAILED}
 *       (token vrij). Er wordt <b>nooit</b> een marker of mutatie geschreven: de stagingfase was niet
 *       af, dus er valt niets over de levering vast te stellen.</li>
 *   <li>{@code MUTATING} ⇒ ongewijzigd. De staging is volledig en de reeds geschreven mutaties zijn
 *       gecommit; die batch blijft hervatbaar via {@code POST /batches/{id}/continue}. Ze wordt enkel
 *       gelogd, zodat een operator ziet welke batches wachten.</li>
 * </ul>
 * Uitschakelbaar met {@code catalogimport.screening.recovery-on-startup=false} (standaard aan); tests
 * gebruiken dat zodat gedeelde testdata elkaar niet beïnvloeden.
 * <p>
 * <b>Beperking.</b> Dit veronderstelt precies één applicatie-instantie: zonder lease of heartbeat
 * (design par. 9) is een {@code SCREENING}-batch die een andere instantie nog bezig is te verwerken niet
 * te onderscheiden van een verweesde. Pas op te lossen in Fase 5.
 */
@Service
public class ScreeningRecoveryService {

    /** Blokkeercode van een screening die door een herstart onderbroken werd. */
    public static final String CODE_SCREENING_INTERRUPTED = "SCREENING_INTERRUPTED";

    private static final int MAX_BLOCKED_REASON_LENGTH = 500;
    private static final Logger LOG = LoggerFactory.getLogger(ScreeningRecoveryService.class);

    /**
     * Wat het herstel gedaan heeft: {@code failedBatchIds} zijn de onderbroken screenings die op
     * {@code FAILED} gezet zijn, {@code resumableBatchIds} de {@code MUTATING}-batches die hervatbaar
     * bleven.
     */
    public record RecoveryReport(List<Long> failedBatchIds, List<Long> resumableBatchIds) {
    }

    private final ImportBatchRepository batches;
    private final TaskRunRepository runs;
    private final CandidateStageDao stage;
    private final RowIssueDao rowIssues;
    private final TransactionTemplate transaction;
    private final boolean enabled;

    public ScreeningRecoveryService(ImportBatchRepository batches, TaskRunRepository runs,
                                    CandidateStageDao stage, RowIssueDao rowIssues,
                                    PlatformTransactionManager transactionManager,
                                    @Value("${catalogimport.screening.recovery-on-startup:true}")
                                    boolean enabled) {
        this.batches = batches;
        this.runs = runs;
        this.stage = stage;
        this.rowIssues = rowIssues;
        this.transaction = new TransactionTemplate(transactionManager);
        this.enabled = enabled;
    }

    /** Herstel bij het opstarten; een mislukt herstel mag het opstarten nooit tegenhouden. */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!enabled) {
            LOG.info("Screening recovery on startup is disabled");
            return;
        }
        try {
            recover();
        } catch (RuntimeException failure) {
            LOG.error("Screening recovery on startup failed", failure);
        }
    }

    /**
     * Voert het herstel uit, ook wanneer het opstartherstel uitgeschakeld is. Elke batch wordt in haar
     * eigen transactie afgehandeld: een fout bij één batch laat de andere niet liggen.
     */
    public RecoveryReport recover() {
        List<Long> failed = new ArrayList<>();
        List<Long> interrupted = batches.findByStatus(ImportBatchStatus.SCREENING).stream()
                .map(ImportBatch::getId).toList();
        for (Long batchId : interrupted) {
            try {
                Boolean done = transaction.execute(status -> failInterrupted(batchId));
                if (Boolean.TRUE.equals(done)) {
                    failed.add(batchId);
                }
            } catch (RuntimeException failure) {
                LOG.error("Cannot recover interrupted screening of batch {}", batchId, failure);
            }
        }
        List<Long> resumable = batches.findByStatus(ImportBatchStatus.MUTATING).stream()
                .map(ImportBatch::getId).toList();
        resumable.forEach(batchId -> LOG.warn("Batch {} is in MUTATING and can be resumed with "
                + "POST /api/catalog-import/batches/{}/continue", batchId, batchId));
        return new RecoveryReport(List.copyOf(failed), resumable);
    }

    /** @return {@code false} als de batch intussen al niet meer in {@code SCREENING} stond */
    private boolean failInterrupted(long batchId) {
        ImportBatch batch = batches.findById(batchId).orElse(null);
        if (batch == null || batch.getStatus() != ImportBatchStatus.SCREENING) {
            return false;
        }
        stage.deleteByBatchId(batchId);
        rowIssues.deleteByBatchId(batchId);
        batch.setStagedRowCount(0);
        batch.setBlockedCode(CODE_SCREENING_INTERRUPTED);
        batch.setBlockedReason(truncate(CODE_SCREENING_INTERRUPTED
                + ": the application stopped while this batch was being screened; staging and row issues "
                + "were removed, upload the delivery again"));
        batch.setStatus(ImportBatchStatus.FAILED);
        batch.setFinishedAt(Instant.now());
        batches.saveAndFlush(batch);
        TaskRun run = batch.getTaskRun() == null ? null : runs.findById(batch.getTaskRun().getId()).orElse(null);
        if (run != null && run.getStatus() != TaskRunStatus.FAILED) {
            run.setStatus(TaskRunStatus.FAILED);
            run.setFinishedAt(Instant.now());
            runs.saveAndFlush(run);
        }
        LOG.warn("Batch {} was interrupted while screening: marked FAILED ({})", batchId,
                CODE_SCREENING_INTERRUPTED);
        return true;
    }

    private static String truncate(String value) {
        return value.length() <= MAX_BLOCKED_REASON_LENGTH ? value : value.substring(0, MAX_BLOCKED_REASON_LENGTH);
    }
}
