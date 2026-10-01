package be.dda.catalogimport.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TaskRunTest {

    private static final Instant STARTED = Instant.parse("2026-01-01T00:00:00Z");

    private static CatalogImportTask taskWithId(Long id, boolean preventConcurrent) throws Exception {
        CatalogImportTask task = new CatalogImportTask(null, "taak", TaskTriggerType.MANUAL);
        Field field = CatalogImportTask.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(task, id);
        task.setPreventConcurrentRuns(preventConcurrent);
        return task;
    }

    private static TaskRun run() {
        return new TaskRun(null, STARTED, "tester");
    }

    @Test
    void outcomeCodeOfExactly60CharactersIsAccepted() {
        TaskRun run = run();
        String code = "A".repeat(60);
        run.setOutcome(code, "ok");
        assertThat(run.getOutcomeCode()).isEqualTo(code);
    }

    @Test
    void outcomeCodeLongerThan60IsRejectedAndLeavesPreviousOutcome() {
        TaskRun run = run();
        run.setOutcome("FIRST", "eerste");
        assertThatThrownBy(() -> run.setOutcome("A".repeat(61), "x")).isInstanceOf(IllegalArgumentException.class);
        assertThat(run.getOutcomeCode()).isEqualTo("FIRST");
        assertThat(run.getOutcomeMessage()).isEqualTo("eerste");
    }

    @Test
    void outcomeMessageIsTruncatedAbove500WithEllipsisAndKeptAtOrBelow() {
        TaskRun run = run();
        run.setOutcome(null, "m".repeat(500));
        assertThat(run.getOutcomeMessage()).hasSize(500);
        run.setOutcome(null, "m".repeat(501));
        assertThat(run.getOutcomeMessage()).hasSize(500).endsWith("...");
        assertThat(run.getOutcomeMessage()).startsWith("m".repeat(497));
        run.setOutcome(null, null);
        assertThat(run.getOutcomeCode()).isNull();
        assertThat(run.getOutcomeMessage()).isNull();
    }

    @Test
    void pendingFileCountRejectsNegativeButAcceptsNullAndZero() {
        TaskRun run = run();
        run.setPendingFileCount(0);
        assertThat(run.getPendingFileCount()).isZero();
        run.setPendingFileCount(null);
        assertThat(run.getPendingFileCount()).isNull();
        run.setPendingFileCount(5);
        assertThatThrownBy(() -> run.setPendingFileCount(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(run.getPendingFileCount()).isEqualTo(5);
    }

    @Test
    void constructorLeavesDefaultsAndPersistHookFillsTimestamps() {
        TaskRun run = new TaskRun(null, null, "tester");
        assertThat(run.getStatus()).isEqualTo(TaskRunStatus.PENDING);
        assertThat(run.getCreatedAt()).isNull();
        run.onPersist();
        assertThat(run.getCreatedAt()).isNotNull();
        assertThat(run.getStartedAt()).isNotNull();
        assertThat(run.getTriggeredBy()).isEqualTo("tester");
    }

    @Test
    void concurrencyTokenIsTaskIdWhileNonTerminalAndTaskPreventsConcurrentRuns() throws Exception {
        TaskRun run = new TaskRun(taskWithId(42L, true), STARTED, "tester");
        run.onPersist();
        assertThat(run.getConcurrencyToken()).isEqualTo(42L);
        run.setStatus(TaskRunStatus.RUNNING);
        assertThat(run.getConcurrencyToken()).isEqualTo(42L);
        for (TaskRunStatus terminal : new TaskRunStatus[] {TaskRunStatus.COMPLETED, TaskRunStatus.FAILED,
                TaskRunStatus.CANCELLED}) {
            run.setStatus(terminal);
            assertThat(run.getConcurrencyToken()).as("%s", terminal).isNull();
        }
    }

    @Test
    void concurrencyTokenIsNullWhenTaskAllowsConcurrentRuns() throws Exception {
        TaskRun run = new TaskRun(taskWithId(42L, false), STARTED, "tester");
        run.onPersist();
        assertThat(run.getConcurrencyToken()).isNull();
    }
}
