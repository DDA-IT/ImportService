package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.domain.TaskRun;
import be.dda.catalogimport.domain.TaskRunStatus;
import be.dda.catalogimport.domain.TaskRunTriggerSource;
import be.dda.catalogimport.service.FetchOutcomeCodes;
import be.dda.catalogimport.service.FetchRunRecoveryService;
import be.dda.catalogimport.service.FetchRunService;
import be.dda.catalogimport.service.FetchTarget;
import be.dda.catalogimport.service.SftpConnector;
import be.dda.catalogimport.service.SftpConnector.DownloadPlan;
import be.dda.catalogimport.service.SftpConnector.FetchResult;
import be.dda.catalogimport.service.SftpConnector.Listing;
import be.dda.catalogimport.service.SftpConnector.PasswordSource;
import be.dda.catalogimport.service.SftpConnector.PinnedHostKey;
import be.dda.catalogimport.service.SftpConnector.RemoteFile;
import be.dda.catalogimport.service.TaskRunView;
import be.dda.catalogimport.service.support.RemoteFileSelection;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Bouwstap K-4c: herstel van een vastgelopen ophaalrun ({@code docs/design/leveringsconfiguratie-design.md} par. 4, 10
 * en 11; precedent {@code PublicationRunService.abortRun} en {@code ScreeningRecoveryService}).
 *
 * <ul>
 *   <li><b>Definitie:</b> {@code RUNNING} + ophaalrun + zonder levering + ouder dan {@code stuck-after} (default PT60M).</li>
 *   <li><b>Afbreken:</b> {@code POST /task-runs/{id}/abort} (MANAGE, reden verplicht, geen tijdsvoorwaarde): FAILED,
 *       {@code FETCH_MANUALLY_ABORTED}, taak weer vrij. 409 {@code FETCH_RUN_NOT_STUCK} voor al het andere.</li>
 *   <li><b>Opstartherstel:</b> {@code FETCH_TIMED_OUT}, enkel met de vlag.</li>
 *   <li><b>Race:</b> een late afronding van een afgebroken run overschrijft niets en registreert geen levering.</li>
 * </ul>
 */
class FetchRunRecoveryTest extends FetchTestSupport {

    @org.springframework.beans.factory.annotation.Autowired
    FetchRunRecoveryService recovery;

    // --- Definitie -------------------------------------------------------------------------------------------------

    @Test
    void onlyARunningFetchRunWithoutDeliveryOlderThanTheThresholdIsRecovered() throws Exception {
        Chain old = chain("OLD");
        Chain young = chain("YOUNG");
        Chain withBatch = chain("BATCH");
        long oldRun = runningRun(old.taskId(), TaskRunTriggerSource.MANUAL_FETCH, Instant.now().minus(2, ChronoUnit.HOURS));
        long youngRun = runningRun(young.taskId(), TaskRunTriggerSource.MANUAL_FETCH,
                Instant.now().minus(5, ChronoUnit.MINUTES));
        // Een run mét levering en batch is screeningwerk (ScreeningRecoveryService), nooit een vastgelopen ophaalrun.
        remoteFile(withBatch, "a.csv", hoursAgo(1), csv(1));
        long batchRun = longAt(fetchBody(withBatch.taskId()), "$.id");
        jdbc.update("update task_run set status = 'RUNNING', finished_at = null, started_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minus(3, ChronoUnit.HOURS)), batchRun);

        List<Long> recovered = recovery.recoverStuck();

        assertThat(recovered).contains(oldRun).doesNotContain(youngRun, batchRun);
        assertThat(state(oldRun)).containsEntry("status", "FAILED")
                .containsEntry("outcome_code", FetchOutcomeCodes.FETCH_TIMED_OUT);
        assertThat(state(oldRun).get("finished_at")).isNotNull();
        assertThat(state(oldRun).get("concurrency_token")).isNull();
        assertThat((String) state(oldRun).get("outcome_message")).contains("PT1H"); // Duration.toString van PT60M
        assertThat(state(youngRun)).containsEntry("status", "RUNNING");
        assertThat(state(batchRun)).containsEntry("status", "RUNNING");
        assertThat(recovery.recoverStuck()).as("idempotent").doesNotContain(oldRun);
    }

    @Test
    void scheduledFetchRunsAreCoveredAndUploadRunsAreNot() throws Exception {
        Chain scheduled = chain("SCHED");
        Chain upload = chain("UPL");
        long scheduledRun = runningRun(scheduled.taskId(), TaskRunTriggerSource.SCHEDULED_FETCH,
                Instant.now().minus(2, ChronoUnit.HOURS));
        long uploadRun = runningRun(upload.taskId(), TaskRunTriggerSource.UPLOAD, Instant.now().minus(2, ChronoUnit.HOURS));

        assertThat(recovery.recoverStuck()).contains(scheduledRun).doesNotContain(uploadRun);
        assertThat(state(uploadRun)).containsEntry("status", "RUNNING");
        abort(uploadRun, "x").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FETCH_RUN_NOT_STUCK"));
    }

    // --- Handmatig afbreken ----------------------------------------------------------------------------------------

    @Test
    void abortingAStuckRunFailsItFreesTheTaskAndAllowsANewFetch() throws Exception {
        Chain chain = chain("ABORT");
        remoteFile(chain, "a.csv", hoursAgo(1), csv(1));
        long runId = runningRun(chain.taskId(), TaskRunTriggerSource.MANUAL_FETCH,
                Instant.now().minus(2, ChronoUnit.HOURS));
        // Zolang de run loopt, is de taak bezet.
        fetch(chain.taskId()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_RUN_IN_PROGRESS"));
        assertThat(state(runId).get("concurrency_token")).isNotNull();

        abort(runId, "Server crashte tijdens het ophalen").andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(runId))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.outcomeCode").value(FetchOutcomeCodes.FETCH_MANUALLY_ABORTED))
                .andExpect(jsonPath("$.outcomeMessage").value(
                        "Manually aborted by " + USER + ": Server crashte tijdens het ophalen"))
                .andExpect(jsonPath("$.finishedAt").isNotEmpty());

        assertThat(state(runId).get("concurrency_token")).isNull();
        assertThat(deliveryCount(chain.taskId())).isZero();
        fetch(chain.taskId()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcomeCode").value(FetchOutcomeCodes.FETCHED));
    }

    @Test
    void thereIsNoTimeConditionOnAbortingLikeThePublicationRunPrecedent() throws Exception {
        Chain chain = chain("FRESH");
        long runId = runningRun(chain.taskId(), TaskRunTriggerSource.MANUAL_FETCH, Instant.now());

        abort(runId, "Handmatig gestopt").andExpect(status().isOk())
                .andExpect(jsonPath("$.outcomeCode").value(FetchOutcomeCodes.FETCH_MANUALLY_ABORTED));
    }

    @Test
    void aReasonIsRequiredAndBoundedAndTheRunStaysRunningWithoutIt() throws Exception {
        Chain chain = chain("REASON");
        long runId = runningRun(chain.taskId(), TaskRunTriggerSource.MANUAL_FETCH,
                Instant.now().minus(2, ChronoUnit.HOURS));

        for (String body : new String[] {"{}", "{\"reason\":null}", "{\"reason\":\"   \"}"}) {
            json(runId, body, Permission.MANAGE).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("FETCH_ABORT_REASON_REQUIRED"));
        }
        abort(runId, "x".repeat(301)).andExpect(status().isBadRequest());
        assertThat(state(runId)).containsEntry("status", "RUNNING");

        abort(runId, "x".repeat(300)).andExpect(status().isOk());
    }

    @Test
    void anyOtherRunIs409AnUnknownRunIs404AndReadIsForbidden() throws Exception {
        Chain chain = chain("OTHER");
        remoteFile(chain, "a.csv", hoursAgo(1), csv(1));
        long finished = longAt(fetchBody(chain.taskId()), "$.id");
        long stuck = runningRun(chain("OTH2").taskId(), TaskRunTriggerSource.MANUAL_FETCH,
                Instant.now().minus(2, ChronoUnit.HOURS));

        abort(finished, "te laat").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FETCH_RUN_NOT_STUCK"));
        assertThat(state(finished)).containsEntry("status", "COMPLETED").containsEntry("outcome_code", "FETCHED");
        abort(999_999_999L, "onbekend").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_RUN_NOT_FOUND"));
        json(stuck, "{\"reason\":\"x\"}", Permission.READ).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        assertThat(state(stuck)).containsEntry("status", "RUNNING");

        abort(stuck, "eerste").andExpect(status().isOk());
        abort(stuck, "tweede").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FETCH_RUN_NOT_STUCK"));
        assertThat((String) state(stuck).get("outcome_message")).contains("eerste").doesNotContain("tweede");
    }

    @Test
    void aRunWithADeliveryIsNeverAbortedBecauseItIsScreeningWork() throws Exception {
        Chain chain = chain("SCREEN");
        remoteFile(chain, "a.csv", hoursAgo(1), csv(1));
        long runId = longAt(fetchBody(chain.taskId()), "$.id");
        jdbc.update("update task_run set status = 'RUNNING', finished_at = null where id = ?", runId);

        abort(runId, "screening loopt nog").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FETCH_RUN_NOT_STUCK"));
        assertThat(state(runId)).containsEntry("status", "RUNNING");
    }

    // --- Race: late afronding van een afgebroken run --------------------------------------------------------------

    @Test
    void aLateCompletionAfterAnAbortOverwritesNothingAndRegistersNoDelivery() throws Exception {
        Chain chain = chain("LATE");
        remoteFile(chain, "a.csv", hoursAgo(1), csv(1));
        long archivedBefore = archivedFileCount();
        // Afbreken nadat het bestand gekozen is maar vóór de download/registratie: de run "rondt toch nog af".
        FetchRunService service = serviceWith(abortingAfterChoice(chain.taskId()));

        TaskRunView returned = service.fetch(chain.taskId(), ACTOR);

        assertThat(returned.status()).isEqualTo("FAILED");
        assertThat(returned.outcomeCode()).isEqualTo(FetchOutcomeCodes.FETCH_MANUALLY_ABORTED);
        Map<String, Object> row = state(returned.id());
        assertThat(row).containsEntry("status", "FAILED").containsEntry("outcome_code",
                FetchOutcomeCodes.FETCH_MANUALLY_ABORTED);
        assertThat((String) row.get("outcome_message")).contains("Tijdens de download afgebroken");
        assertThat(row.get("concurrency_token")).isNull();
        assertThat(deliveryCount(chain.taskId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from fetch_file_observation where task_run_id = ?", Long.class,
                returned.id())).isZero();
        assertThat(archivedFileCount()).as("the downloaded object was discarded").isEqualTo(archivedBefore);
        // De taak is vrij en het bestand is nog nooit opgehaald: een nieuwe run haalt het alsnog op.
        fetch(chain.taskId()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcomeCode").value(FetchOutcomeCodes.FETCHED));
    }

    // --- Opstartherstel achter een vlag ---------------------------------------------------------------------------

    @Test
    void startupRecoveryTimesOutStuckRunsOnlyWhenTheFlagIsOn() throws Exception {
        Chain chain = chain("START");
        long runId = runningRun(chain.taskId(), TaskRunTriggerSource.MANUAL_FETCH,
                Instant.now().minus(2, ChronoUnit.HOURS));

        recoveryService("PT60M", false).onApplicationReady();
        assertThat(state(runId)).as("flag off: untouched").containsEntry("status", "RUNNING");

        recoveryService("PT60M", true).onApplicationReady();
        assertThat(state(runId)).containsEntry("status", "FAILED")
                .containsEntry("outcome_code", FetchOutcomeCodes.FETCH_TIMED_OUT);
        assertThat(state(runId).get("concurrency_token")).isNull();
        assertThat((String) state(runId).get("outcome_message")).contains("PT1H");
    }

    @Test
    void theThresholdIsConfigurableAndAnInvalidOneFailsFast() throws Exception {
        Chain chain = chain("THRESH");
        long runId = runningRun(chain.taskId(), TaskRunTriggerSource.MANUAL_FETCH,
                Instant.now().minus(10, ChronoUnit.MINUTES));

        assertThat(recoveryService("PT30M", true).recoverStuck()).doesNotContain(runId);
        assertThat(recoveryService("PT5M", true).recoverStuck()).contains(runId);
        assertThat(recoveryService("PT5M", true).stuckAfter()).isEqualTo(Duration.ofMinutes(5));

        for (String invalid : new String[] {"banana", "60", "", "PT0S", "-PT1M"}) {
            assertThatThrownBy(() -> recoveryService(invalid, true)).as(invalid)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("catalogimport.fetch.stuck-after");
        }
    }

    // --- Helpers ---------------------------------------------------------------------------------------------------

    private FetchRunRecoveryService recoveryService(String stuckAfter, boolean onStartup) {
        return new FetchRunRecoveryService(runs, deliveries, tasks, runQueries, transactionManager, clock, stuckAfter,
                onStartup);
    }

    /** Een run zoals een gecrashte ophaalrun ze achterlaat: {@code RUNNING}, zonder levering en batch. */
    private long runningRun(long taskId, TaskRunTriggerSource source, Instant startedAt) {
        TaskRun run = new TaskRun(tasks.findById(taskId).orElseThrow(), startedAt.truncatedTo(ChronoUnit.MICROS), USER);
        run.setStatus(TaskRunStatus.RUNNING);
        run.setTriggerSource(source);
        return runs.saveAndFlush(run).getId();
    }

    private Map<String, Object> state(long runId) {
        return jdbc.queryForMap("select * from task_run where id = ?", runId);
    }

    private ResultActions abort(long runId, String reason) throws Exception {
        return json(runId, "{\"reason\":\"" + reason + "\"}", Permission.MANAGE);
    }

    private ResultActions json(long runId, String body, Permission permission) throws Exception {
        return mockMvc.perform(post(API + "/task-runs/{id}/abort", runId).with(as(USER, permission))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** Een connector die de run afbreekt zodra de service een bestand gekozen heeft (vóór de download). */
    private SftpConnector abortingAfterChoice(long taskId) {
        return new SftpConnector(Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(10), 10_000) {
            @Override
            public FetchResult fetchOne(FetchTarget target, String username, PinnedHostKey pinned,
                                        PasswordSource password, String remoteDirectory,
                                        RemoteFileSelection selection, long byteLimit, DownloadPlan plan) {
                return super.fetchOne(target, username, pinned, password, remoteDirectory, selection, byteLimit,
                        new DownloadPlan() {
                            @Override
                            public RemoteFile choose(Listing listing) {
                                RemoteFile chosen = plan.choose(listing);
                                long runId = jdbc.queryForObject(
                                        "select id from task_run where task_id = ? and status = 'RUNNING'", Long.class,
                                        taskId);
                                try {
                                    abort(runId, "Tijdens de download afgebroken").andExpect(status().isOk());
                                } catch (Exception failure) {
                                    throw new IllegalStateException(failure);
                                }
                                return chosen;
                            }

                            @Override
                            public void receive(RemoteFile file, InputStream content) {
                                plan.receive(file, content);
                            }
                        });
            }
        };
    }
}
