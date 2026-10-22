package be.dda.catalogimport.web;

import be.dda.catalogimport.domain.PublicationRunItemStatus;
import be.dda.catalogimport.service.PageResult;
import be.dda.catalogimport.service.PublicationRunApiService;
import be.dda.catalogimport.service.PublicationRunApiService.PublicationItemView;
import be.dda.catalogimport.service.PublicationRunService;
import be.dda.catalogimport.service.PublicationRunService.PublicationRunView;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publicatieruns van een bevroren bundel (Fase 5-PUB deel a, bouwstap 5P-8; ontwerp
 * {@code docs/design/fase5-pub-design.md} par. 4). {@code SIMULATION} schrijft een CSV-artefact en niets naar Prodis;
 * sinds P6 ({@code docs/design/publicatie-prodis-db-direct-design.md} par. 6) start {@code PRODUCTION} een run die naar de
 * Prodis-database schrijft, in chunks via {@code POST /publication-runs/{id}/continue}, met de itemresultaten op
 * {@code GET /publication-runs/{id}/items}.
 * <p>
 * <b>Rechten.</b> {@code POST} draagt {@code APPROVE}, de drie {@code GET}'s {@code READ}. De check gebeurt
 * in de interceptor vóór de handler ("recht eerst", fase5-perm-design par. 3): dus 403 vóór 400/404/409,
 * ook bij een niet-ingeschakelde modus.
 * <p>
 * <b>Actor.</b> De aanvrager komt uit de login ({@link CurrentActor#signer}); het request draagt geen
 * actorveld. Bewaard worden de tokennaam ({@code requested_by}) en het OIDC-subject
 * ({@code requested_by_subject}); enkel de naam komt in het antwoord.
 * <p>
 * Statuscodes: 200 (zoals de andere POST-acties op bundels). Foutcodes komen uit de service: 400
 * {@code PUBLICATION_MODE_REQUIRED}/{@code _UNKNOWN}; 404 {@code BUNDLE_NOT_FOUND},
 * {@code PUBLICATION_RUN_NOT_FOUND}; 409 {@code PUBLICATION_MODE_NOT_ENABLED}, {@code BUNDLE_NOT_FROZEN},
 * {@code PUBLICATION_RUN_IN_PROGRESS}, {@code BUNDLE_CONTENT_CHANGED_SINCE_FREEZE},
 * {@code PUBLICATION_RUN_ARTIFACT_NOT_AVAILABLE}; voor PRODUCTION ook {@code PRODIS_WRITE_NOT_CONFIGURED},
 * {@code BUNDLE_TARGET_MODE_NOT_PRODUCTION}, {@code BUNDLE_NOT_SNAPSHOTTED}, {@code BUNDLE_HAS_INCOMPLETE_ROWS},
 * {@code PRODUCTION_RUN_IN_PROGRESS}, {@code PUBLICATION_RUN_BUSY}, {@code PUBLICATION_RUN_NOT_PRODUCTION},
 * {@code PUBLICATION_RUN_NOT_PUBLISHING} en {@code PUBLICATION_RUN_HAS_SENT_ITEMS} (afbreken van een PUBLISHING-run).
 * {@code POST /continue} draagt {@code APPROVE} (publiceren), de itemlijst {@code READ}; beide zonder body/actorveld.
 * <p>
 * <b>Afbreken.</b> {@code POST .../abort} (herstel van een vastgelopen run, {@code docs/decisions.md}
 * 2026-09-27) draagt {@code APPROVE}, net als het aanvragen zelf: dezelfde rechtenconventie voor
 * bundel-levenscyclusacties. Geen tijdsvoorwaarde — elke {@code PREPARING}-run mag afgebroken worden. Geen
 * body en geen actorveld: de aanvrager komt uit de login, net als bij {@code /continue}.
 */
@RestController
@RequestMapping("/api/catalog-import")
public class PublicationRunController {

    /** Body van {@code POST /bundles/{id}/publication-runs}; geen actorveld (de aanvrager is de login). */
    public record RequestRunRequest(String targetMode) {
    }

    private final PublicationRunService service;
    private final PublicationRunApiService api;
    private final CurrentActor currentActor;

    public PublicationRunController(PublicationRunService service, PublicationRunApiService api,
                                    CurrentActor currentActor) {
        this.service = service;
        this.api = api;
        this.currentActor = currentActor;
    }

    /** Vraagt een run aan; een technische mislukking in het artefact geeft 200 met status {@code FAILED}. */
    @RequiresPermission(Permission.APPROVE)
    @PostMapping("/bundles/{bundleId}/publication-runs")
    PublicationRunView request(@PathVariable("bundleId") long bundleId, @RequestBody RequestRunRequest request) {
        return api.request(bundleId, request.targetMode(), currentActor.signer(null, "requestedBy"));
    }

    /**
     * Verwerkt de volgende chunk van een PRODUCTION-run naar Prodis (P6); de frontend roept dit herhaaldelijk aan tot
     * {@code done}. Geen body en geen actorveld: de verzender komt uit de login (Prodis-audit {@code sent_by}).
     * Een gelijktijdige tweede aanroep krijgt 409 {@code PUBLICATION_RUN_BUSY}.
     */
    @RequiresPermission(Permission.APPROVE)
    @PostMapping("/publication-runs/{runId}/continue")
    PublicationRunView continueRun(@PathVariable("runId") long runId) {
        return api.continueRun(runId, currentActor.signer(null, "sentBy"));
    }

    /**
     * Breekt een vastgelopen {@code PREPARING}-run handmatig af. Geen tijdsvoorwaarde (keuze mens
     * 2026-09-27): wie {@code APPROVE} heeft, mag dit op elk moment doen. Een andere status geeft 409
     * {@code PUBLICATION_RUN_NOT_STUCK}.
     */
    @RequiresPermission(Permission.APPROVE)
    @PostMapping("/publication-runs/{runId}/abort")
    PublicationRunView abort(@PathVariable("runId") long runId) {
        return service.abortRun(runId, currentActor.current());
    }

    /** Alle runs van een bundel, oudste eerst. */
    @RequiresPermission(Permission.READ)
    @GetMapping("/bundles/{bundleId}/publication-runs")
    List<PublicationRunView> list(@PathVariable("bundleId") long bundleId) {
        return service.listRuns(bundleId);
    }

    /** De itemresultaten van een PRODUCTION-run, oplopend op id; optioneel gefilterd op status. */
    @RequiresPermission(Permission.READ)
    @GetMapping("/publication-runs/{runId}/items")
    PageResult<PublicationItemView> items(@PathVariable("runId") long runId,
                                          @RequestParam(value = "status", required = false) PublicationRunItemStatus status,
                                          @RequestParam(value = "page", required = false) Integer page,
                                          @RequestParam(value = "size", required = false) Integer size) {
        return api.listItems(runId, status, page, size);
    }

    /** Eén run. */
    @RequiresPermission(Permission.READ)
    @GetMapping("/publication-runs/{runId}")
    PublicationRunView get(@PathVariable("runId") long runId) {
        return service.getRun(runId);
    }

    /**
     * Streamt het CSV-artefact van een {@code SIMULATED} run. De stroom wordt eerst geopend (fouten 404/409
     * komen dus vóór de eerste byte) en na het kopiëren altijd gesloten. De bestandsnaam bevat enkel het
     * run-id, nooit padinformatie.
     */
    @RequiresPermission(Permission.READ)
    @GetMapping("/publication-runs/{runId}/artifact")
    void artifact(@PathVariable("runId") long runId, HttpServletResponse response) throws IOException {
        try (InputStream in = service.openArtifact(runId)) {
            response.setContentType("text/csv; charset=UTF-8");
            response.setHeader("Content-Disposition",
                    "attachment; filename=\"psimport-simulation-run-" + runId + ".csv\"");
            in.transferTo(response.getOutputStream());
        }
    }

    /**
     * Streamt het JSON-rapport van de Prodis-parallelrun (P2c, {@code docs/design/publicatie-prodis-db-direct-design.md}
     * par. 6) van een {@code SIMULATED} run. Zelfde patroon en zelfde recht als {@link #artifact}: eerst openen (404/409
     * vóór de eerste byte), daarna kopiëren; de bestandsnaam bevat enkel het run-id.
     */
    @RequiresPermission(Permission.READ)
    @GetMapping("/publication-runs/{runId}/parallel-run-artifact")
    void parallelRunArtifact(@PathVariable("runId") long runId, HttpServletResponse response) throws IOException {
        try (InputStream in = service.openParallelRunArtifact(runId)) {
            response.setContentType("application/json; charset=UTF-8");
            response.setHeader("Content-Disposition",
                    "attachment; filename=\"prodis-parallel-run-" + runId + ".json\"");
            in.transferTo(response.getOutputStream());
        }
    }
}
