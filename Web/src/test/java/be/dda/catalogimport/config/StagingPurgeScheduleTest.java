package be.dda.catalogimport.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import be.dda.catalogimport.service.StagingPurgeService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * Stap 7, S7-P3: de geplande opruimtaak bestaat enkel met {@code catalogimport.staging-retention.enabled=true}; zonder
 * die property is er geen bean en geen scheduler. Zonder database: een minimale context met een gemockte service.
 */
class StagingPurgeScheduleTest {

    private static final String SCHEDULER_PROCESSOR =
            "org.springframework.context.annotation.internalScheduledAnnotationProcessor";

    private final StagingPurgeService service = mock(StagingPurgeService.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(StagingPurgeService.class, () -> service)
            .withUserConfiguration(StagingPurgeSchedule.class);

    @Test
    void withoutThePropertyThereIsNoTaskAndNoScheduler() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(StagingPurgeSchedule.class);
            assertThat(context.containsBean(SCHEDULER_PROCESSOR)).isFalse();
        });
    }

    @Test
    void enabledFalseMeansNoTask() {
        runner.withPropertyValues("catalogimport.staging-retention.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(StagingPurgeSchedule.class);
            assertThat(context.containsBean(SCHEDULER_PROCESSOR)).isFalse();
        });
    }

    @Test
    void enabledTrueRegistersOneCronTaskWithTheDefaultCron() {
        runner.withPropertyValues("catalogimport.staging-retention.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(StagingPurgeSchedule.class);
            assertThat(cronExpressions(context.getBean(ScheduledTaskHolder.class))).containsExactly("0 30 3 * * *");
        });
    }

    @Test
    void theCronIsConfigurable() {
        runner.withPropertyValues("catalogimport.staging-retention.enabled=true",
                "catalogimport.staging-retention.cron=0 0 2 * * SUN").run(context ->
                assertThat(cronExpressions(context.getBean(ScheduledTaskHolder.class)))
                        .containsExactly("0 0 2 * * SUN"));
    }

    @Test
    void aRunIsARealPurgeNotADryRun() {
        new StagingPurgeSchedule(service).run();

        verify(service).purge(false);
    }

    private static List<String> cronExpressions(ScheduledTaskHolder holder) {
        return holder.getScheduledTasks().stream()
                .map(ScheduledTask::getTask)
                .filter(CronTask.class::isInstance)
                .map(task -> ((CronTask) task).getExpression())
                .toList();
    }
}
