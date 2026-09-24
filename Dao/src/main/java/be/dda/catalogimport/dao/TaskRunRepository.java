package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.TaskRun;
import be.dda.catalogimport.domain.TaskRunStatus;
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

    /**
     * De meest recente run (hoogste {@code startedAt}, bij gelijkstand hoogste id) van elke opgegeven taak,
     * in één query voor een hele pagina taken (geen N+1). Taken zonder run komen niet voor in het resultaat.
     */
    @Query("select r from TaskRun r join fetch r.task where r.task.id in :taskIds "
            + "and not exists (select 1 from TaskRun r2 where r2.task = r.task "
            + "and (r2.startedAt > r.startedAt or (r2.startedAt = r.startedAt and r2.id > r.id)))")
    List<TaskRun> findLatestRunsByTaskIds(@Param("taskIds") Collection<Long> taskIds);
}
