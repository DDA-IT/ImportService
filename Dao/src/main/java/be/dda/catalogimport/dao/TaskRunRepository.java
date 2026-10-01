package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.TaskRun;
import be.dda.catalogimport.domain.TaskRunStatus;
import be.dda.catalogimport.domain.TaskRunTriggerSource;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRunRepository extends JpaRepository<TaskRun, Long> {

    /**
     * De lopende run van een taak, indien die de concurrency-token bezet. De databaseconstraint
     * {@code uk_task_run_concurrency} garandeert dat dit er hoogstens één is.
     */
    Optional<TaskRun> findByTaskIdAndConcurrencyTokenIsNotNull(Long taskId);

    List<TaskRun> findByTaskIdOrderByStartedAtDesc(Long taskId);

    List<TaskRun> findByTaskIdAndStatus(Long taskId, TaskRunStatus status);

    /** Runs in één status met een van de opgegeven herkomsten (K-4c: kandidaten voor herstel van een vastgelopen ophaalrun). */
    List<TaskRun> findByStatusAndTriggerSourceIn(TaskRunStatus status, Collection<TaskRunTriggerSource> sources);

    /** De runlijst van een taak (K-4b, design par. 6): de laatste 20, nieuwste eerst, bij gelijke start op id. */
    List<TaskRun> findTop20ByTaskIdOrderByStartedAtDescIdDesc(Long taskId);

    /**
     * De taak van een run als scalar, zonder de run of de taak in de persistence context te laden: de intake in een
     * bestaande run vergrendelt daarna de taak en moet haar toestand vers <b>na</b> het slot lezen (zelfde reden als
     * {@code CatalogImportTaskRepository.findImportLinkIdByTaskId}).
     */
    @Query("select r.task.id from TaskRun r where r.id = :runId")
    Optional<Long> findTaskIdByRunId(@Param("runId") Long runId);

    /**
     * De meest recente run (hoogste {@code startedAt}, bij gelijkstand hoogste id) van elke opgegeven taak,
     * in één query voor een hele pagina taken (geen N+1). Taken zonder run komen niet voor in het resultaat.
     */
    @Query("select r from TaskRun r join fetch r.task where r.task.id in :taskIds "
            + "and not exists (select 1 from TaskRun r2 where r2.task = r.task "
            + "and (r2.startedAt > r.startedAt or (r2.startedAt = r.startedAt and r2.id > r.id)))")
    List<TaskRun> findLatestRunsByTaskIds(@Param("taskIds") Collection<Long> taskIds);
}
