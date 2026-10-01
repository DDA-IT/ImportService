package be.dda.catalogimport.web;

import be.dda.catalogimport.service.ActorIdentity;
import be.dda.catalogimport.service.TemplateBookmarkService;
import be.dda.catalogimport.service.TemplateBookmarkService.AddUsageCommand;
import be.dda.catalogimport.service.TemplateBookmarkService.BookmarkView;
import be.dda.catalogimport.service.TemplateBookmarkService.DeclareBookmarkCommand;
import be.dda.catalogimport.service.TemplateBookmarkService.UsageView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sjabloonbeheer: de bookmarks van een sjabloonrevisie en hun toegelaten configuratieplaatsen declareren
 * (sjabloon-materialisatie-design.md §5, bouwstap 5b).
 *
 * <h2>Blijft achter de setup-vlag</h2>
 * Deze controller bestaat alleen wanneer {@code catalogimport.setup-api.enabled=true} staat; zonder de vlag
 * bestaat geen van beide paden, ongeacht de rechten: {@code .../usages} geeft 404, {@code POST .../bookmarks}
 * geeft 405 omdat {@code GET} op hetzelfde pad ({@link CatalogImportTemplateController}) altijd bestaat. Er is
 * dan geen handler, dus ook niets geschreven. Met de vlag aan vraagt elk pad {@code MANAGE}.
 * Dat is een bewuste uitzondering (beslissingslog 2026-09-30 "Nieuwe leverancier + taak (NT-spoor)",
 * V2 = a): sjablonen lezen en materialiseren kwam in NT-3 achter de vlag vandaan
 * ({@link CatalogImportTemplateController}), sjabloonbeheer niet — wie deze endpoints bereikt, wijzigt een
 * declaratie die later in leveranciersdefinities gematerialiseerd wordt.
 * <p>
 * Paden, bodies, statuscodes en rechten zijn ongewijzigd; de handlers stonden vóór NT-3 in
 * {@link CatalogImportTemplateController}.
 *
 * <h2>Wie tekent (5A-6)</h2>
 * {@code declareBookmark} roept {@link CurrentActor#signer} aan <b>vóór</b> de service: 400
 * {@code ACTOR_FIELD_MISMATCH} bij een afwijkende {@code createdBy}, 403 {@code SYSTEM_ACTOR_FORBIDDEN} voor
 * {@code system} — allebei vóór 404/409 en zonder iets te schrijven. {@code createdBy} is daardoor
 * <b>optioneel</b>; bewaard wordt de token-naam plus het OIDC-subject (changeset 007-4). {@code addUsage}
 * heeft geen {@code *_by}-kolom en vraagt enkel een bruikbare, niet-{@code system} login.
 *
 * <h2>Statuscodes</h2>
 * 201 bij een aangemaakte bookmark/usage-rij. 404 met een stabiele {@code code}: {@code TEMPLATE_NOT_FOUND},
 * {@code TEMPLATE_REVISION_NOT_FOUND}, {@code BOOKMARK_NOT_FOUND}. 409 met een stabiele {@code code}:
 * {@code REVISION_NOT_EDITABLE} (declareren mag alleen op een DRAFT-revisie), {@code DEFINITION_NOT_A_TEMPLATE},
 * {@code BOOKMARK_NAME_IN_USE}, {@code BOOKMARK_ORDER_IN_USE}, {@code BOOKMARK_USAGE_IN_USE}, of één van de
 * fase C-codes ({@code CONFIG_BOOKMARK_*}). 400 zonder code bij een ontbrekende, te lange of ongeldige waarde.
 */
@RestController
@RequestMapping("/api/catalog-import/templates")
@ConditionalOnProperty(prefix = "catalogimport.setup-api", name = "enabled", havingValue = "true")
public class CatalogImportTemplateDeclarationController {

    private final TemplateBookmarkService templateBookmarks;
    private final CurrentActor currentActor;

    public CatalogImportTemplateDeclarationController(TemplateBookmarkService templateBookmarks,
                                                      CurrentActor currentActor) {
        this.templateBookmarks = templateBookmarks;
        this.currentActor = currentActor;
    }

    /** Declareert een nieuwe bookmark; alleen toegelaten op een {@code DRAFT}-sjabloonrevisie. */
    @RequiresPermission(Permission.MANAGE)
    @PostMapping("/{definitionId}/revisions/{revisionId}/bookmarks")
    @ResponseStatus(HttpStatus.CREATED)
    BookmarkView declareBookmark(@PathVariable("definitionId") long definitionId,
                                 @PathVariable("revisionId") long revisionId,
                                 @RequestBody DeclareBookmarkCommand request) {
        ActorIdentity actor = currentActor.signer(request == null ? null : request.createdBy(), "createdBy");
        return templateBookmarks.declareBookmark(definitionId, revisionId, request, actor);
    }

    /** Voegt een toegelaten configuratieplaats (witte lijst) toe aan een bestaande bookmark. */
    @RequiresPermission(Permission.MANAGE)
    @PostMapping("/{definitionId}/revisions/{revisionId}/bookmarks/{name}/usages")
    @ResponseStatus(HttpStatus.CREATED)
    UsageView addUsage(@PathVariable("definitionId") long definitionId,
                       @PathVariable("revisionId") long revisionId, @PathVariable("name") String name,
                       @RequestBody AddUsageCommand request) {
        // Geen actorveld en geen *_by-kolom op import_definition_bookmark_usage: enkel een bruikbare,
        // niet-'system' login is vereist (ontwerp par. 1.1 en par. 3).
        currentActor.signer(null, "createdBy");
        return templateBookmarks.addUsage(definitionId, revisionId, name, request);
    }
}
