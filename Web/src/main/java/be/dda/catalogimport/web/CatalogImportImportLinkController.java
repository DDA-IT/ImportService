package be.dda.catalogimport.web;

import be.dda.catalogimport.service.ImportLinkQueryService;
import be.dda.catalogimport.service.ImportLinkQueryService.ImportLinkRow;
import be.dda.catalogimport.service.ImportLinkReadinessService;
import be.dda.catalogimport.service.ImportLinkReadinessService.LinkReadiness;
import be.dda.catalogimport.service.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alleen-lezen opzoeklijst van importkoppelingen (Scherm 0/3, D14, bouwstap S0-B3): laat de UI een
 * koppelingscode/-naam tonen in plaats van een kaal id.
 * <p>
 * <b>Bewust NIET achter {@code catalogimport.setup-api.enabled}</b> (beslissingslog 23/09, D14-slice,
 * vraag Q1): dit endpoint schrijft geen configuratie — het toont enkel labels — net zoals
 * {@code GET /batches} en {@code GET /bundles} vandaag ook al zonder authenticatie bereikbaar zijn.
 * Staat los van {@link CatalogImportLinkController} (de bookmarkwaarde-endpoints van de
 * materialisatiewizard), die schrijft en daarom {@code MANAGE} vraagt; die stond tot NT-3 achter de vlag
 * (beslissingslog 2026-09-30, V2 = a).
 */
@RestController
@RequestMapping("/api/catalog-import/import-links")
public class CatalogImportImportLinkController {

    private final ImportLinkQueryService queries;
    private final ImportLinkReadinessService readinessService;

    public CatalogImportImportLinkController(ImportLinkQueryService queries,
                                             ImportLinkReadinessService readinessService) {
        this.queries = queries;
        this.readinessService = readinessService;
    }

    /**
     * Gereedheidscontrole van de koppeling en haar keten, zonder bestand (NT-8; beslissingslog 2026-09-30
     * "Nieuwe leverancier + taak (NT-spoor)", V1 = C). Alle bevindingen tegelijk, elk met een stabiele code; een
     * probleem draagt dezelfde code als de upload of de activatie zou geven. Schrijft niets (leestransactie).
     * <p>
     * {@code READ}, en net als de rest van deze controller niet achter {@code catalogimport.setup-api.enabled}.
     * 200, of 404 {@code LINK_NOT_FOUND} voor een onbekende koppeling.
     */
    @RequiresPermission(Permission.READ)
    @GetMapping("/{linkId}/readiness")
    LinkReadiness readiness(@PathVariable("linkId") long linkId) {
        return readinessService.readiness(linkId);
    }

    /**
     * Alle koppelingen, oplopend op {@code code}, optioneel gefilterd op {@code active} en (additief,
     * S1-B1) op {@code importDefinitionId}.
     */
    @RequiresPermission(Permission.READ)
    @GetMapping
    PageResult<ImportLinkRow> importLinks(@RequestParam(value = "active", required = false) Boolean active,
                                          @RequestParam(value = "importDefinitionId", required = false)
                                          Long importDefinitionId,
                                          @RequestParam(value = "page", required = false) Integer page,
                                          @RequestParam(value = "size", required = false) Integer size) {
        return queries.listImportLinks(active, importDefinitionId, page, size);
    }
}
