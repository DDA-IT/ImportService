package be.dda.catalogimport.service;

import java.time.Instant;
import java.util.List;

/**
 * Leesmodel van een {@code task_run} (bouwstap K-4b; {@code docs/design/leveringsconfiguratie-design.md} par. 4.4, 4.5
 * en 6): antwoord van {@code POST /tasks/{id}/fetch-runs}, {@code GET /tasks/{id}/runs} en {@code GET /task-runs/{id}}.
 * <p>
 * {@code triggerSource} is {@code UPLOAD}, {@code LOCAL_DIRECTORY}, {@code MANUAL_FETCH} of {@code null} (runs van vóór
 * K-4b). {@code outcomeCode}/{@code outcomeMessage}/{@code pendingFileCount} zijn enkel bij een ophaalrun gevuld
 * ({@code pendingFileCount} enkel als er gelijst werd). {@code deliveryId}/{@code batchId}/{@code batchStatus} zijn
 * {@code null} zolang de run geen levering heeft. {@code observations} is {@code null} in de runlijst (een listing kan
 * tot 10 000 waarnemingen tellen) en gevuld in het detail en in het antwoord op "Nu ophalen". Nooit een secret, host,
 * login, map of archiefpad: wel bestandsnamen, zoals {@code DeliveryView} die al voor READ toont.
 */
public record TaskRunView(long id, long taskId, String status, String triggerSource, String triggeredBy,
                          Instant startedAt, Instant finishedAt, Long deliveryConfigurationVersionId,
                          String outcomeCode, String outcomeMessage, Integer pendingFileCount,
                          Long deliveryId, Long batchId, String batchStatus,
                          List<ObservationView> observations) {

    /** Eén bekeken bestand ({@code fetch_file_observation}); {@code deliveryId} enkel bij een geregistreerde SELECTED. */
    public record ObservationView(String remoteFileName, long byteSize, Instant remoteModifiedAt, String decision,
                                  Long deliveryId) {
    }
}
