package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.TaskRunRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.TaskRun;
import be.dda.catalogimport.domain.TaskTriggerType;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alleen-lezen takenlijst (Scherm 2, bouwstap B-B1): laat de UI kiezen op welke taak een levering
 * geupload wordt. Schrijft niets; spiegelt {@link ImportLinkQueryService} (zelfde paginering).
 * <p>
 * {@code triggerType} wordt meegegeven omdat {@code DeliveryIntakeService.resolve} een niet-MANUAL taak
 * met {@code TASK_NOT_MANUAL} weigert; de UI toont zo'n taak uitgeschakeld mét reden.
 * {@code lastRunStartedAt}/{@code lastRunFinishedAt} komen van de meest recente {@code TaskRun} van de
 * taak (niet van de denormalisatiekolommen op de taak), en zijn {@code null} zonder run.
 */
@Service
@Transactional(readOnly = true)
public class TaskQueryService {

    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 200;

    public record TaskRow(long id, String name, boolean active, TaskTriggerType triggerType,
                          boolean preventConcurrentRuns, long importLinkId, String importLinkCode,
                          String supplierCode, String libraryCode, Instant lastRunStartedAt,
                          Instant lastRunFinishedAt) {

        private static TaskRow of(CatalogImportTask task, TaskRun lastRun) {
            ImportLink link = task.getImportLink();
            return new TaskRow(task.getId(), task.getName(), task.isActive(), task.getTriggerType(),
                    task.isPreventConcurrentRuns(), link.getId(), link.getCode(),
                    link.getSupplierOrganisation().getCode(), link.getLibraryCode(),
                    lastRun == null ? null : lastRun.getStartedAt(),
                    lastRun == null ? null : lastRun.getFinishedAt());
        }
    }

    private final CatalogImportTaskRepository tasks;
    private final TaskRunRepository runs;

    public TaskQueryService(CatalogImportTaskRepository tasks, TaskRunRepository runs) {
        this.tasks = tasks;
        this.runs = runs;
    }

    /**
     * Alle taken, oplopend op koppelingscode, naam en id, optioneel gefilterd.
     *
     * @throws IllegalArgumentException ongeldige paginering
     */
    public PageResult<TaskRow> listTasks(Long importLinkId, Boolean active, Integer page, Integer size) {
        int number = page == null ? 0 : page;
        int requested = size == null ? DEFAULT_PAGE_SIZE : size;
        if (number < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (requested < 1) {
            throw new IllegalArgumentException("size must be at least 1");
        }
        PageRequest pageRequest = PageRequest.of(number, Math.min(requested, MAX_PAGE_SIZE), Sort.unsorted());
        Page<CatalogImportTask> result = tasks.findTaskRows(importLinkId, active, pageRequest);
        Map<Long, TaskRun> lastRuns = new HashMap<>();
        if (!result.isEmpty()) {
            List<Long> ids = result.getContent().stream().map(CatalogImportTask::getId).toList();
            for (TaskRun run : runs.findLatestRunsByTaskIds(ids)) {
                lastRuns.put(run.getTask().getId(), run);
            }
        }
        return PageResult.of(result, task -> TaskRow.of(task, lastRuns.get(task.getId())));
    }
}
