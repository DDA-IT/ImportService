package be.dda.catalogimport.web;

import be.dda.catalogimport.domain.DefinitionUsageType;
import be.dda.catalogimport.service.PageResult;
import be.dda.catalogimport.service.SetupQueryService;
import be.dda.catalogimport.service.SetupQueryService.DefinitionRow;
import be.dda.catalogimport.service.SetupQueryService.RevisionRow;
import be.dda.catalogimport.service.SetupQueryService.SourceOrganisationRow;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alleen-lezen inrichtingsendpoints voor scherm 1a (S1-B1, beslissingslog 27/09 "Heropening scherm
 * 1a/1b", keuze A1): bronorganisatie → definitie → revisie.
 * <p>
 * <b>Bewust NIET achter {@code catalogimport.setup-api.enabled}</b>, in tegenstelling tot
 * {@link CatalogImportSetupController}: deze endpoints schrijven niets, ze tonen enkel labels — net
 * zoals {@code GET /import-links} en {@code GET /tasks} vandaag ook al zonder die vlag bereikbaar zijn.
 * Scherm 1a is zo altijd bruikbaar als inzagescherm; enkel met de vlag aan ook als beheerscherm (via
 * {@link CatalogImportSetupController}). De schrijfpaden voor sjabloon/koppeling blijven achter de vlag,
 * ongewijzigd.
 */
@RestController
@RequestMapping("/api/catalog-import")
public class CatalogImportSetupQueryController {

    private final SetupQueryService queries;

    public CatalogImportSetupQueryController(SetupQueryService queries) {
        this.queries = queries;
    }

    /** Alle bronorganisaties, oplopend op {@code code}, optioneel gefilterd op {@code active}. */
    @RequiresPermission(Permission.READ)
    @GetMapping("/source-organisations")
    PageResult<SourceOrganisationRow> sourceOrganisations(
            @RequestParam(value = "active", required = false) Boolean active,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        return queries.listSourceOrganisations(active, page, size);
    }

    /**
     * Alle importdefinities, oplopend op {@code code}, optioneel gefilterd op bronorganisatie en
     * gebruikstype.
     */
    @RequiresPermission(Permission.READ)
    @GetMapping("/definitions")
    PageResult<DefinitionRow> definitions(
            @RequestParam(value = "sourceOrganisationId", required = false) Long sourceOrganisationId,
            @RequestParam(value = "usageType", required = false) DefinitionUsageType usageType,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        return queries.listDefinitions(sourceOrganisationId, usageType, page, size);
    }

    /**
     * Alle revisies van één definitie, oplopend op {@code revisionNumber}.
     *
     * @throws be.dda.catalogimport.service.NotFoundException 404 {@code DEFINITION_NOT_FOUND}
     */
    @RequiresPermission(Permission.READ)
    @GetMapping("/definitions/{definitionId}/revisions")
    PageResult<RevisionRow> revisions(@PathVariable("definitionId") long definitionId,
                                      @RequestParam(value = "page", required = false) Integer page,
                                      @RequestParam(value = "size", required = false) Integer size) {
        return queries.listRevisions(definitionId, page, size);
    }
}
