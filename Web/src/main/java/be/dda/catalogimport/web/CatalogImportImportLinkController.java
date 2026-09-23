package be.dda.catalogimport.web;

import be.dda.catalogimport.service.ImportLinkQueryService;
import be.dda.catalogimport.service.ImportLinkQueryService.ImportLinkRow;
import be.dda.catalogimport.service.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
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
 * Verschilt daarom bewust van {@link CatalogImportLinkController} (de bookmarkwaarde-endpoints van de
 * materialisatiewizard), die wél achter die vlag staat omdat híj schrijft.
 */
@RestController
@RequestMapping("/api/catalog-import/import-links")
public class CatalogImportImportLinkController {

    private final ImportLinkQueryService queries;

    public CatalogImportImportLinkController(ImportLinkQueryService queries) {
        this.queries = queries;
    }

    /** Alle koppelingen, oplopend op {@code code}, optioneel gefilterd op {@code active}. */
    @GetMapping
    PageResult<ImportLinkRow> importLinks(@RequestParam(value = "active", required = false) Boolean active,
                                          @RequestParam(value = "page", required = false) Integer page,
                                          @RequestParam(value = "size", required = false) Integer size) {
        return queries.listImportLinks(active, page, size);
    }
}
