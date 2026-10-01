package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.DeliveryRepository;
import be.dda.catalogimport.dao.FetchFileObservationRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.TaskRunRepository;
import be.dda.catalogimport.domain.Delivery;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.TaskRun;
import be.dda.catalogimport.service.TaskRunView.ObservationView;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leest runs van een taak (bouwstap K-4b; {@code docs/design/leveringsconfiguratie-design.md} par. 6: runlijst van de
 * laatste 20 en rundetail, recht READ). Alle soorten runs: upload, servermap en ophaalrun.
 */
@Service
@Transactional(readOnly = true)
public class TaskRunQueryService {

    /** Hoeveel runs de runlijst hoogstens toont (design par. 6, 7). */
    public static final int RUN_LIST_SIZE = 20;
    public static final String CODE_TASK_NOT_FOUND = "TASK_NOT_FOUND";
    public static final String CODE_TASK_RUN_NOT_FOUND = DeliveryIntakeService.CODE_TASK_RUN_NOT_FOUND;

    private final CatalogImportTaskRepository tasks;
    private final TaskRunRepository runs;
    private final DeliveryRepository deliveries;
    private final ImportBatchRepository batches;
    private final FetchFileObservationRepository observations;

    public TaskRunQueryService(CatalogImportTaskRepository tasks, TaskRunRepository runs, DeliveryRepository deliveries,
                               ImportBatchRepository batches, FetchFileObservationRepository observations) {
        this.tasks = tasks;
        this.runs = runs;
        this.deliveries = deliveries;
        this.batches = batches;
        this.observations = observations;
    }

    /**
     * De laatste {@value #RUN_LIST_SIZE} runs van een taak, nieuwste eerst, zonder waarnemingen.
     *
     * @throws NotFoundException 404 {@code TASK_NOT_FOUND}
     */
    public List<TaskRunView> recentRuns(long taskId) {
        if (!tasks.existsById(taskId)) {
            throw new NotFoundException(CODE_TASK_NOT_FOUND, "Task " + taskId + " not found");
        }
        return runs.findTop20ByTaskIdOrderByStartedAtDescIdDesc(taskId).stream()
                .map(run -> view(run, false))
                .toList();
    }

    /**
     * Eén run met haar waarnemingen.
     *
     * @throws NotFoundException 404 {@code TASK_RUN_NOT_FOUND}
     */
    public TaskRunView getRun(long runId) {
        TaskRun run = runs.findById(runId).orElseThrow(() -> new NotFoundException(CODE_TASK_RUN_NOT_FOUND,
                "Task run " + runId + " not found"));
        return view(run, true);
    }

    private TaskRunView view(TaskRun run, boolean withObservations) {
        List<Delivery> runDeliveries = deliveries.findByTaskRunId(run.getId());
        Delivery delivery = runDeliveries.isEmpty() ? null : runDeliveries.get(0);
        ImportBatch batch = null;
        if (delivery != null) {
            List<ImportBatch> attempts = batches.findByDeliveryIdOrderByAttemptNoAsc(delivery.getId());
            batch = attempts.isEmpty() ? null : attempts.get(attempts.size() - 1);
        }
        List<ObservationView> seen = !withObservations ? null
                : observations.findByTaskRunIdOrderByIdAsc(run.getId()).stream()
                        .map(o -> new ObservationView(o.getRemoteFileName(), o.getByteSize(), o.getRemoteModifiedAt(),
                                o.getDecision().name(), o.getDelivery() == null ? null : o.getDelivery().getId()))
                        .toList();
        return new TaskRunView(run.getId(), run.getTask().getId(), run.getStatus().name(),
                run.getTriggerSource() == null ? null : run.getTriggerSource().name(), run.getTriggeredBy(),
                run.getStartedAt(), run.getFinishedAt(),
                run.getDeliveryConfigurationVersion() == null ? null : run.getDeliveryConfigurationVersion().getId(),
                run.getOutcomeCode(), run.getOutcomeMessage(), run.getPendingFileCount(),
                delivery == null ? null : delivery.getId(), batch == null ? null : batch.getId(),
                batch == null ? null : batch.getStatus().name(), seen);
    }
}
