package be.dda.catalogimport.web;

import be.dda.catalogimport.service.LinkBookmarkValueService;
import be.dda.catalogimport.service.LinkBookmarkValueService.LinkBookmarkValueRow;
import be.dda.catalogimport.service.LinkBookmarkValueService.LinkBookmarkValues;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * De twee bookmarkwaarde-endpoints van een bestaande importkoppeling
 * (sjabloon-materialisatie-design.md §5, bouwstap 5f).
 *
 * <h2>WAARSCHUWING — dit endpoint staat standaard uit en mag nooit in productie aan</h2>
 * Deze controller bestaat alleen wanneer {@code catalogimport.setup-api.enabled=true} staat, exact
 * dezelfde vlag als {@link CatalogImportSetupController} (beslissingslog 23/09, vraag Q1). De default
 * is {@code false} en {@code application.yml} zet de vlag bewust niet; zonder de vlag antwoordt elk
 * pad hieronder met 404. Er is nog geen authenticatie (Fase 5), en een LINK-bookmarkwaarde bepaalt mee
 * wat er gefilterd, gemapt en uiteindelijk gepubliceerd wordt. Zet de vlag dus uitsluitend aan op een
 * ontwikkelmachine met wegwerpgegevens.
 *
 * <h2>Statuscodes</h2>
 * 200 bij beide endpoints. 404 met code {@code LINK_NOT_FOUND} of {@code SOURCE_ORGANISATION_NOT_FOUND}
 * (een {@code LINK_SUPPLIER_ORGANISATION}-bookmark wijst naar een onbestaande leverancierscode). 409 met
 * code {@code LINK_BOOKMARK_LOCKED_BY_OPEN_BATCH} (de koppeling heeft een open batch, keuze 5) of
 * {@code NO_ACTIVE_REVISION} (niets declareert welke bookmarks deze koppeling heeft). 400 met code
 * {@code BOOKMARK_UNKNOWN}, {@code BOOKMARK_SCOPE_MISMATCH}, {@code CONFIG_BOOKMARK_VALUE_INVALID},
 * {@code CONFIG_BOOKMARK_VALUE_TOO_LONG} of {@code CONFIG_REQUIRED_BOOKMARK_MISSING} (een lege waarde
 * zou {@code library_code} of de leveranciersorganisatie leegmaken, allebei {@code NOT NULL} —
 * 5f-nalevering, beslissingslog 2026-09-23); 400 zonder code bij een ontbrekend of te lang veld.
 * <p>
 * Alle antwoorden komen uit {@link LinkBookmarkValueService} als records; er gaat geen JPA-entiteit
 * naar buiten.
 */
@RestController
@RequestMapping("/api/catalog-import/links")
@ConditionalOnProperty(prefix = "catalogimport.setup-api", name = "enabled", havingValue = "true")
public class CatalogImportLinkController {

    /**
     * Body van {@code PUT /links/{id}/bookmark-values/{name}}.
     *
     * @param value     de nieuwe waarde; {@code ""} is een uitdrukkelijk lege waarde en wordt bewaard,
     *                  een ontbrekend veld wordt geweigerd — die twee zijn nooit hetzelfde (R-BMK-03)
     * @param updatedBy wie de wijziging doet; niet leeg en nooit {@code system} (autorisatie volgt in
     *                  Fase 5)
     */
    public record SetBookmarkValueRequest(String value, String updatedBy) {
    }

    private final LinkBookmarkValueService bookmarkValues;

    public CatalogImportLinkController(LinkBookmarkValueService bookmarkValues) {
        this.bookmarkValues = bookmarkValues;
    }

    /**
     * De ingevulde LINK-bookmarkwaarden van de koppeling. Een rij met {@code declared = false} is een
     * wees: de naam staat in de huidige actieve revisie niet (meer) gedeclareerd, de waarde telt niet
     * als ingevuld en wordt nooit toegepast — ze wordt hier getoond omdat ze auditmateriaal is (§7).
     */
    @GetMapping("/{linkId}/bookmark-values")
    LinkBookmarkValues bookmarkValues(@PathVariable("linkId") long linkId) {
        return bookmarkValues.list(linkId);
    }

    /**
     * Wijzigt (of vult voor het eerst in) één LINK-bookmarkwaarde. Weigert met 409 zolang de koppeling
     * een open batch heeft; de wijziging zelf legt vorige waarde, wie en wanneer samen vast.
     */
    @PutMapping("/{linkId}/bookmark-values/{name}")
    LinkBookmarkValueRow setBookmarkValue(@PathVariable("linkId") long linkId,
                                          @PathVariable("name") String name,
                                          @RequestBody SetBookmarkValueRequest request) {
        return bookmarkValues.setValue(linkId, name, request == null ? null : request.value(),
                request == null ? null : request.updatedBy());
    }
}
