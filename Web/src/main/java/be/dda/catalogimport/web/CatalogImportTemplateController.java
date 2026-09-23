package be.dda.catalogimport.web;

import be.dda.catalogimport.service.PageResult;
import be.dda.catalogimport.service.TemplateBookmarkService;
import be.dda.catalogimport.service.TemplateBookmarkService.AddUsageCommand;
import be.dda.catalogimport.service.TemplateBookmarkService.BookmarkSetView;
import be.dda.catalogimport.service.TemplateBookmarkService.BookmarkView;
import be.dda.catalogimport.service.TemplateBookmarkService.DeclareBookmarkCommand;
import be.dda.catalogimport.service.TemplateBookmarkService.TemplateView;
import be.dda.catalogimport.service.TemplateBookmarkService.UsageView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * De declaratielaag van de materialisatiewizard (sjabloon-materialisatie-design.md §5, bouwstap 5b):
 * sjablonen opzoeken en de bookmarks van één sjabloonrevisie declareren/lezen. De materialisatie zelf
 * ({@code POST .../materialisations}) hoort hier nog niet bij — dat is bouwstap 5c/5d.
 *
 * <h2>WAARSCHUWING — dit endpoint staat standaard uit en mag nooit in productie aan</h2>
 * Zelfde vlag als {@link CatalogImportSetupController} ({@code catalogimport.setup-api.enabled}, default
 * {@code false}, decisions.md 2026-09-23 Q1): er is nog geen authenticatie (Fase 5), en wie deze
 * endpoints bereikt, kan een sjabloondeclaratie wijzigen die later in leveranciersdefinities
 * gematerialiseerd wordt. Zet de vlag dus uitsluitend aan op een ontwikkelmachine met wegwerpgegevens.
 *
 * <h2>Statuscodes</h2>
 * 201 bij een aangemaakte bookmark/usage-rij, 200 bij lezen. 404 met een stabiele {@code code}:
 * {@code TEMPLATE_NOT_FOUND}, {@code TEMPLATE_REVISION_NOT_FOUND}, {@code BOOKMARK_NOT_FOUND}. 409 met
 * een stabiele {@code code}: {@code REVISION_NOT_EDITABLE} (declareren mag alleen op een DRAFT-revisie),
 * {@code DEFINITION_NOT_A_TEMPLATE}, {@code BOOKMARK_NAME_IN_USE}, {@code BOOKMARK_ORDER_IN_USE},
 * {@code BOOKMARK_USAGE_IN_USE}, of één van de fase C-codes ({@code CONFIG_BOOKMARK_SCOPE_PLACE_CONFLICT},
 * {@code CONFIG_BOOKMARK_PLACE_UNRESOLVED}, {@code CONFIG_BOOKMARK_PLACE_NOT_SUPPORTED}). 400 bij een
 * ontbrekende, te lange of ongeldige waarde.
 * <p>
 * Alle antwoorden komen uit {@link TemplateBookmarkService} als records; er gaat geen JPA-entiteit naar
 * buiten. Inline {@code record}-requests/responses, dezelfde stijl als {@code CatalogImportBundleController}.
 */
@RestController
@RequestMapping("/api/catalog-import/templates")
@ConditionalOnProperty(prefix = "catalogimport.setup-api", name = "enabled", havingValue = "true")
public class CatalogImportTemplateController {

    private final TemplateBookmarkService templateBookmarks;

    public CatalogImportTemplateController(TemplateBookmarkService templateBookmarks) {
        this.templateBookmarks = templateBookmarks;
    }

    /** De sjablonen ({@code usage_type = REUSABLE_TEMPLATE}), gepagineerd. */
    @GetMapping
    PageResult<TemplateView> templates(@RequestParam(value = "page", required = false) Integer page,
                                       @RequestParam(value = "size", required = false) Integer size) {
        return templateBookmarks.listTemplates(page, size);
    }

    /**
     * De invulset van één sjabloonrevisie voor het scherm, inclusief een {@code problems}-lijst met de
     * fase C-bevindingen — leesbaar zonder te werpen.
     */
    @GetMapping("/{definitionId}/revisions/{revisionId}/bookmarks")
    BookmarkSetView bookmarks(@PathVariable("definitionId") long definitionId,
                              @PathVariable("revisionId") long revisionId) {
        return templateBookmarks.getBookmarkSet(definitionId, revisionId);
    }

    /** Declareert een nieuwe bookmark; alleen toegelaten op een {@code DRAFT}-sjabloonrevisie. */
    @PostMapping("/{definitionId}/revisions/{revisionId}/bookmarks")
    @ResponseStatus(HttpStatus.CREATED)
    BookmarkView declareBookmark(@PathVariable("definitionId") long definitionId,
                                 @PathVariable("revisionId") long revisionId,
                                 @RequestBody DeclareBookmarkCommand request) {
        return templateBookmarks.declareBookmark(definitionId, revisionId, request);
    }

    /** Voegt een toegelaten configuratieplaats (witte lijst) toe aan een bestaande bookmark. */
    @PostMapping("/{definitionId}/revisions/{revisionId}/bookmarks/{name}/usages")
    @ResponseStatus(HttpStatus.CREATED)
    UsageView addUsage(@PathVariable("definitionId") long definitionId,
                       @PathVariable("revisionId") long revisionId, @PathVariable("name") String name,
                       @RequestBody AddUsageCommand request) {
        return templateBookmarks.addUsage(definitionId, revisionId, name, request);
    }
}
