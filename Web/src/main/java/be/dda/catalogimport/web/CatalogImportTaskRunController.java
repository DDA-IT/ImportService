package be.dda.catalogimport.web;

import be.dda.catalogimport.service.ActorIdentity;
import be.dda.catalogimport.service.FetchRunRecoveryService;
import be.dda.catalogimport.service.FetchRunService;
import be.dda.catalogimport.service.TaskRunQueryService;
import be.dda.catalogimport.service.TaskRunView;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Nu ophalen" en de runs van een taak (bouwstap K-4b; {@code docs/design/leveringsconfiguratie-design.md} par. 4 en 6;
 * beslissingslog 2026-09-29 L2, L6, L7, A9). Een eigen controller naast {@link CatalogImportTaskController} en
 * {@link CatalogImportTaskBindingController} (zelfde basispad, andere paden), zodat die ongewijzigd blijven.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@code POST /tasks/{taskId}/fetch-runs} (geen body; recht {@code MANAGE}, niet achter de setup-vlag): synchroon
 *       ophalen. <b>201</b> met de run ({@link TaskRunView}, met waarnemingen) - ook als het ophalen remote mislukte
 *       ({@code status = FAILED}, {@code outcomeCode}) of er niets nieuws was ({@code COMPLETED}/{@code NO_NEW_FILE}).
 *       Voorcontroles zonder run: 404 {@code FETCH_NOT_CONFIGURED}, {@code TASK_NOT_FOUND}; 409
 *       {@code TASK_HAS_NO_DELIVERY_CONFIGURATION}, {@code SECRETS_NOT_CONFIGURED}, {@code CREDENTIAL_REVOKED},
 *       {@code NO_ACTIVE_REVISION}, {@code CONFIG_PRICE_FIELD_MISSING}, {@code CONFIG_REQUIRED_BOOKMARK_MISSING},
 *       {@code TASK_RUN_IN_PROGRESS}.</li>
 *   <li>{@code GET /tasks/{taskId}/runs} ({@code READ}): de laatste 20 runs, nieuwste eerst, zonder waarnemingen; 404
 *       {@code TASK_NOT_FOUND}.</li>
 *   <li>{@code GET /task-runs/{runId}} ({@code READ}): één run met waarnemingen; 404 {@code TASK_RUN_NOT_FOUND}.</li>
 *   <li>{@code POST /task-runs/{runId}/abort} (K-4c, body {@code {reason}}, {@code MANAGE}): zie {@link #abort}.</li>
 * </ul>
 * READ ziet bestandsnamen, geen host, login of map (L7b; zoals {@code DeliveryView} al voor READ zichtbaar is). Recht
 * eerst: 403 vóór alles.
 */
@RestController
@RequestMapping("/api/catalog-import")
public class CatalogImportTaskRunController {

    /** Body van {@code POST /task-runs/{runId}/abort}; {@code reason} is verplicht. */
    public record AbortRunRequest(String reason) {
    }

    private final FetchRunService fetchRuns;
    private final FetchRunRecoveryService recovery;
    private final TaskRunQueryService runQueries;
    private final CurrentActor currentActor;

    public CatalogImportTaskRunController(FetchRunService fetchRuns, FetchRunRecoveryService recovery,
                                          TaskRunQueryService runQueries, CurrentActor currentActor) {
        this.fetchRuns = fetchRuns;
        this.recovery = recovery;
        this.runQueries = runQueries;
        this.currentActor = currentActor;
    }

    /**
     * Breekt een vastgelopen ophaalrun af (K-4c; precedent {@code POST /publication-runs/{id}/abort}): 200 met de run
     * ({@code FAILED}/{@code FETCH_MANUALLY_ABORTED}). Geen tijdsvoorwaarde; 409 {@code FETCH_RUN_NOT_STUCK} als de run
     * geen lopende ophaalrun zonder levering is; 400 {@code FETCH_ABORT_REASON_REQUIRED}; 404
     * {@code TASK_RUN_NOT_FOUND}. Recht {@code MANAGE}, net als het ophalen zelf.
     */
    @RequiresPermission(Permission.MANAGE)
    @PostMapping("/task-runs/{runId}/abort")
    TaskRunView abort(@PathVariable("runId") long runId, @RequestBody AbortRunRequest request) {
        ActorIdentity actor = currentActor.signer(null, "actor");
        return recovery.abort(runId, actor, request.reason());
    }

    @RequiresPermission(Permission.MANAGE)
    @PostMapping("/tasks/{taskId}/fetch-runs")
    ResponseEntity<TaskRunView> fetch(@PathVariable("taskId") long taskId) {
        ActorIdentity actor = currentActor.signer(null, "actor");
        return ResponseEntity.status(HttpStatus.CREATED).body(fetchRuns.fetch(taskId, actor));
    }

    @RequiresPermission(Permission.READ)
    @GetMapping("/tasks/{taskId}/runs")
    List<TaskRunView> runs(@PathVariable("taskId") long taskId) {
        return runQueries.recentRuns(taskId);
    }

    @RequiresPermission(Permission.READ)
    @GetMapping("/task-runs/{runId}")
    TaskRunView run(@PathVariable("runId") long runId) {
        return runQueries.getRun(runId);
    }
}
