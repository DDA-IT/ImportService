package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.DeliveryRepository;
import be.dda.catalogimport.dao.TaskRunRepository;
import be.dda.catalogimport.domain.TaskRun;
import be.dda.catalogimport.domain.TaskRunStatus;
import be.dda.catalogimport.domain.TaskRunTriggerSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Herstel van een vastgelopen ophaalrun (bouwstap K-4c; {@code docs/design/leveringsconfiguratie-design.md} par. 4, 10
 * en 11). Volgt het precedent van de publicatierun ({@link PublicationRunService#abortRun}, beslissingslog 2026-09-27)
 * en van {@link ScreeningRecoveryService} (opstartherstel achter een vlag).
 *
 * <h2>Wat "vastgelopen" is</h2>
 * Een {@code task_run} met {@code trigger_source} {@code MANUAL_FETCH} of {@code SCHEDULED_FETCH}, in {@code RUNNING},
 * <b>zonder</b> gekoppelde levering (en dus zonder batch: levering, bestand en batch ontstaan in één transactie), die
 * langer dan {@code catalogimport.fetch.stuck-after} (default {@code PT60M}, zoals
 * {@code catalogimport.publication-run.stuck-after}) geleden startte. Er bestaat geen heartbeat (ook niet bij het
 * publicatieprecedent), dus {@code started_at} is de enige tijdsbasis. Een run mét batch is screeningwerk en valt onder
 * {@link ScreeningRecoveryService}. Een ongeldige of niet-positieve drempel laat de applicatie niet opstarten.
 *
 * <h2>Handmatig afbreken ({@link #abort})</h2>
 * Zoals bij de publicatierun is er <b>geen tijdsvoorwaarde</b>: wie {@code MANAGE} heeft, mag een {@code RUNNING}
 * ophaalrun zonder levering op elk moment afbreken. Een run die niet ({@code RUNNING} + ophaalrun + zonder levering) is,
 * geeft 409 {@link #CODE_RUN_NOT_STUCK}. Het resultaat is {@code FAILED}/{@link FetchOutcomeCodes#FETCH_MANUALLY_ABORTED}
 * met {@code finished_at}; de concurrency-token komt vrij via {@link TaskRun#setStatus} (de token volgt de status), zodat
 * de taak weer vrij is.
 *
 * <h2>Opstartherstel ({@link #recoverStuck})</h2>
 * Achter {@code catalogimport.fetch.recovery-on-startup} (default aan, zoals bij de screening): elke vastgelopen run
 * wordt {@code FAILED}/{@link FetchOutcomeCodes#FETCH_TIMED_OUT}. Een mislukt herstel houdt het opstarten nooit tegen.
 * Veronderstelt één applicatie-instantie zolang er geen heartbeat is (zelfde beperking als de screening).
 *
 * <h2>Race met een run die toch nog afrondt</h2>
 * Afbreken, herstel, {@link DeliveryIntakeService#registerInRun} en {@code FetchRunService.close} nemen allemaal het
 * rijslot op de taak en herlezen daarna de status. Wie na een afbreking komt, ziet {@code FAILED}: registratie weigert
 * ({@code TASK_RUN_NOT_RUNNING}, het archiefobject wordt door de ophaalrun opgeruimd) en {@code close} laat de
 * {@code FAILED}-toestand ongemoeid. Nooit twee eindtoestanden, nooit een levering bij een afgebroken run.
 *
 * <h2>Bekende beperking: wees-archiefobject</h2>
 * {@link DeliveryArchiveStore} kiest een willekeurig uuid-pad en legt geen verband met een run; een object dat
 * gedownload maar niet geregistreerd werd (procescrash) is daarom niet betrouwbaar aan zijn run te koppelen. Er wordt
 * <b>niets op basis van een heuristiek verwijderd</b>; het wees-object blijft onschuldig achter (zelfde aanvaarde
 * situatie als bij de upload).
 */
@Service
public class FetchRunRecoveryService {

    /** 409: de run is niet af te breken (geen lopende ophaalrun zonder levering). */
    public static final String CODE_RUN_NOT_STUCK = "FETCH_RUN_NOT_STUCK";
    /** 400: de reden ontbreekt of is leeg. */
    public static final String CODE_ABORT_REASON_REQUIRED = "FETCH_ABORT_REASON_REQUIRED";
    /** 404: de run bestaat niet. */
    public static final String CODE_TASK_RUN_NOT_FOUND = TaskRunQueryService.CODE_TASK_RUN_NOT_FOUND;

    /** Hoogstens 300, zodat "Manually aborted by &lt;100&gt;: &lt;reden&gt;" binnen {@code outcome_message} (500) past. */
    static final int MAX_REASON_LENGTH = 300;
    static final int MAX_ACTOR_LENGTH = 100;
    static final String DEFAULT_STUCK_AFTER = "PT60M";

    private static final Set<TaskRunTriggerSource> FETCH_SOURCES = EnumSet.of(TaskRunTriggerSource.MANUAL_FETCH,
            TaskRunTriggerSource.SCHEDULED_FETCH);
    private static final Logger LOG = LoggerFactory.getLogger(FetchRunRecoveryService.class);

    private final TaskRunRepository runs;
    private final DeliveryRepository deliveries;
    private final CatalogImportTaskRepository tasks;
    private final TaskRunQueryService runQueries;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Duration stuckAfter;
    private final boolean recoveryOnStartup;

    public FetchRunRecoveryService(TaskRunRepository runs, DeliveryRepository deliveries,
                                   CatalogImportTaskRepository tasks, TaskRunQueryService runQueries,
                                   PlatformTransactionManager transactionManager, Clock clock,
                                   @Value("${catalogimport.fetch.stuck-after:" + DEFAULT_STUCK_AFTER + "}")
                                   String stuckAfter,
                                   @Value("${catalogimport.fetch.recovery-on-startup:true}") boolean recoveryOnStartup) {
        this.runs = runs;
        this.deliveries = deliveries;
        this.tasks = tasks;
        this.runQueries = runQueries;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.stuckAfter = parseStuckAfter(stuckAfter);
        this.recoveryOnStartup = recoveryOnStartup;
    }

    /** Fail-fast: geen stille terugval op de default bij een fout in de configuratie. */
    static Duration parseStuckAfter(String value) {
        Duration parsed;
        try {
            parsed = Duration.parse(value == null ? "" : value.trim());
        } catch (DateTimeParseException invalid) {
            throw new IllegalStateException("catalogimport.fetch.stuck-after must be an ISO-8601 duration such as "
                    + DEFAULT_STUCK_AFTER + " (was '" + value + "')", invalid);
        }
        if (parsed.isZero() || parsed.isNegative()) {
            throw new IllegalStateException("catalogimport.fetch.stuck-after must be positive (was '" + value + "')");
        }
        return parsed;
    }

    public Duration stuckAfter() {
        return stuckAfter;
    }

    /** Herstel bij het opstarten; een mislukt herstel mag het opstarten nooit tegenhouden. */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!recoveryOnStartup) {
            LOG.info("Fetch run recovery on startup is disabled");
            return;
        }
        try {
            recoverStuck();
        } catch (RuntimeException failure) {
            LOG.error("Fetch run recovery on startup failed", failure);
        }
    }

    /**
     * Sluit elke vastgelopen ophaalrun af als {@code FAILED}/{@code FETCH_TIMED_OUT}, ook als het opstartherstel
     * uitgeschakeld is. Elke run in haar eigen transactie: een fout bij één run laat de andere niet liggen.
     *
     * @return de ids van de afgesloten runs
     */
    public List<Long> recoverStuck() {
        List<Long> candidates = runs.findByStatusAndTriggerSourceIn(TaskRunStatus.RUNNING, FETCH_SOURCES).stream()
                .map(TaskRun::getId).toList();
        List<Long> timedOut = new ArrayList<>();
        for (Long runId : candidates) {
            try {
                Boolean done = transaction.execute(status -> timeOut(runId));
                if (Boolean.TRUE.equals(done)) {
                    timedOut.add(runId);
                }
            } catch (RuntimeException failure) {
                LOG.error("Cannot recover stuck fetch run {}", runId, failure);
            }
        }
        return List.copyOf(timedOut);
    }

    /** @return {@code false} als de run intussen niet (meer) vastgelopen is */
    private boolean timeOut(long runId) {
        TaskRun run = lockedRun(runId);
        if (run == null || !isStuck(run, true)) {
            return false;
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        finish(run, now, FetchOutcomeCodes.FETCH_TIMED_OUT, "Automatically timed out: still RUNNING without a delivery "
                + "after more than " + stuckAfter + " (started at " + run.getStartedAt() + ")");
        LOG.warn("Fetch run {} of task {} timed out (RUNNING since {}, threshold {}): marked FAILED ({})", runId,
                run.getTask().getId(), run.getStartedAt(), stuckAfter, FetchOutcomeCodes.FETCH_TIMED_OUT);
        return true;
    }

    /**
     * Breekt een lopende ophaalrun zonder levering af (zie klassedocumentatie).
     *
     * @param reason verplicht, hoogstens {@value #MAX_REASON_LENGTH} tekens; komt (naast de naam) in
     *               {@code outcome_message}, dus nooit een secret invullen
     * @return de run zoals {@code GET /task-runs/{id}} ze toont
     * @throws BadRequestException      {@link #CODE_ABORT_REASON_REQUIRED}
     * @throws NotFoundException        {@link #CODE_TASK_RUN_NOT_FOUND}
     * @throws ConflictException        {@link #CODE_RUN_NOT_STUCK}
     * @throws IllegalArgumentException ontbrekende of ongeldige actor, of een te lange reden
     */
    public TaskRunView abort(long runId, ActorIdentity actor, String reason) {
        if (actor == null) {
            throw new IllegalArgumentException("Missing actor");
        }
        String by = ActorNames.requireActorName(actor.username(), "actor", MAX_ACTOR_LENGTH);
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException(CODE_ABORT_REASON_REQUIRED, "A reason is required to abort a fetch run");
        }
        String motivation = ActorNames.requireText(reason, "reason", MAX_REASON_LENGTH);
        transaction.executeWithoutResult(status -> {
            TaskRun run = lockedRun(runId);
            if (run == null) {
                throw new NotFoundException(CODE_TASK_RUN_NOT_FOUND, "Task run " + runId + " not found");
            }
            if (!isStuck(run, false)) {
                throw new ConflictException(CODE_RUN_NOT_STUCK, "Task run " + runId + " is not a running fetch run "
                        + "without a delivery (status " + run.getStatus() + "); only such a run can be aborted");
            }
            finish(run, clock.instant().truncatedTo(ChronoUnit.MICROS), FetchOutcomeCodes.FETCH_MANUALLY_ABORTED,
                    "Manually aborted by " + by + ": " + motivation);
            LOG.info("Fetch run {} of task {} manually aborted by {} (subject {})", runId, run.getTask().getId(), by,
                    actor.subject());
        });
        return runQueries.getRun(runId);
    }

    /**
     * De run onder het rijslot van haar taak (zelfde slot als koppeling, registratie en afsluiten), met een verse
     * lezing <b>na</b> het slot; {@code null} als de run niet bestaat.
     */
    private TaskRun lockedRun(long runId) {
        Long taskId = runs.findTaskIdByRunId(runId).orElse(null);
        if (taskId == null) {
            return null;
        }
        tasks.findByIdForUpdate(taskId);
        return runs.findById(runId).orElse(null);
    }

    /**
     * Een lopende ophaalrun zonder levering; met {@code requireAge} bovendien ouder dan de drempel.
     */
    private boolean isStuck(TaskRun run, boolean requireAge) {
        if (run.getStatus() != TaskRunStatus.RUNNING || !FETCH_SOURCES.contains(run.getTriggerSource())
                || !deliveries.findByTaskRunId(run.getId()).isEmpty()) {
            return false;
        }
        return !requireAge
                || Duration.between(run.getStartedAt(), clock.instant()).compareTo(stuckAfter) > 0;
    }

    private void finish(TaskRun run, Instant now, String code, String message) {
        run.setOutcome(code, message);
        run.setStatus(TaskRunStatus.FAILED);
        run.setFinishedAt(now);
        runs.saveAndFlush(run);
    }
}
