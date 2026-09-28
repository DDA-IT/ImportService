package be.dda.catalogimport.web;

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
import org.springframework.web.bind.annotation.RestController;

/**
 * Publicatieruns van een bevroren bundel (Fase 5-PUB deel a, bouwstap 5P-8; ontwerp
 * {@code docs/design/fase5-pub-design.md} par. 4). In dit deel bestaat enkel {@code SIMULATION}: een run
 * schrijft een CSV-artefact en niets naar ProDisWebbase.
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
 * {@code PUBLICATION_RUN_ARTIFACT_NOT_AVAILABLE}.
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
    private final CurrentActor currentActor;

    public PublicationRunController(PublicationRunService service, CurrentActor currentActor) {
        this.service = service;
        this.currentActor = currentActor;
    }

    /** Vraagt een run aan; een technische mislukking in het artefact geeft 200 met status {@code FAILED}. */
    @RequiresPermission(Permission.APPROVE)
    @PostMapping("/bundles/{bundleId}/publication-runs")
    PublicationRunView request(@PathVariable("bundleId") long bundleId, @RequestBody RequestRunRequest request) {
        return service.requestRun(bundleId, request.targetMode(), currentActor.signer(null, "requestedBy"));
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
}
