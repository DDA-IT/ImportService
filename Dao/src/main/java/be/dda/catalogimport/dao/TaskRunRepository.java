package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.TaskRun;
import be.dda.catalogimport.domain.TaskRunStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRunRepository extends JpaRepository<TaskRun, Long> {

    /**
     * De lopende run van een taak, indien die de concurrency-token bezet. De databaseconstraint
     * {@code uk_task_run_concurrency} garandeert dat dit er hoogstens één is.
     */
    Optional<TaskRun> findByTaskIdAndConcurrencyTokenIsNotNull(Long taskId);

    List<TaskRun> findByTaskIdOrderByStartedAtDesc(Long taskId);

    List<TaskRun> findByTaskIdAndStatus(Long taskId, TaskRunStatus status);
}
