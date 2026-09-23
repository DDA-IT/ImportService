package be.dda.catalogimport.web;

import be.dda.catalogimport.service.PageResult;
import be.dda.catalogimport.service.TemplateBookmarkService;
import be.dda.catalogimport.service.TemplateBookmarkService.AddUsageCommand;
import be.dda.catalogimport.service.TemplateBookmarkService.BookmarkSetView;
import be.dda.catalogimport.service.TemplateBookmarkService.BookmarkView;
import be.dda.catalogimport.service.TemplateBookmarkService.DeclareBookmarkCommand;
import be.dda.catalogimport.service.TemplateBookmarkService.TemplateView;
import be.dda.catalogimport.service.TemplateBookmarkService.UsageView;
import be.dda.catalogimport.service.TemplateMaterialisationService;
import be.dda.catalogimport.service.TemplateMaterialisationService.MaterialisationView;
import be.dda.catalogimport.service.TemplateMaterialisationService.MaterialisedDefinitionView;
import be.dda.catalogimport.service.TemplateMaterialisationService.MaterialiseRequest;
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
 * De materialisatiewizard (sjabloon-materialisatie-design.md §5, bouwstappen 5b, 5c en 5d): sjablonen
 * opzoeken, de bookmarks van één sjabloonrevisie declareren/lezen, opvragen wat er al uit dit sjabloon
 * gematerialiseerd is, en een sjabloon materialiseren tot een leveranciersgebonden definitie + revisie +
 * koppeling — of enkel een koppeling bij een al bestaande, deelbare definitie.
 *
 * <h2>WAARSCHUWING — dit endpoint staat standaard uit en mag nooit in productie aan</h2>
 * Zelfde vlag als {@link CatalogImportSetupController} ({@code catalogimport.setup-api.enabled}, default
 * {@code false}, decisions.md 2026-09-23 Q1): er is nog geen authenticatie (Fase 5), en wie deze
 * endpoints bereikt, kan een sjabloondeclaratie wijzigen die later in leveranciersdefinities
 * gematerialiseerd wordt. Zet de vlag dus uitsluitend aan op een ontwikkelmachine met wegwerpgegevens.
 *
 * <h2>Statuscodes</h2>
 * 201 bij een aangemaakte bookmark/usage-rij en bij een geslaagde materialisatie, 200 bij lezen. 404 met
 * een stabiele {@code code}: {@code TEMPLATE_NOT_FOUND}, {@code TEMPLATE_REVISION_NOT_FOUND},
 * {@code BOOKMARK_NOT_FOUND}, {@code DEFINITION_NOT_FOUND}, {@code SOURCE_ORGANISATION_NOT_FOUND}. 409
 * met een stabiele {@code code}:
 * {@code REVISION_NOT_EDITABLE} (declareren mag alleen op een DRAFT-revisie),
 * {@code DEFINITION_NOT_A_TEMPLATE}, {@code TEMPLATE_REVISION_NOT_MATERIALISABLE},
 * {@code NO_ACTIVE_TEMPLATE_REVISION}, {@code BOOKMARK_NAME_IN_USE}, {@code BOOKMARK_ORDER_IN_USE},
 * {@code BOOKMARK_USAGE_IN_USE}, {@code DEFINITION_CODE_IN_USE}, {@code LINK_CODE_IN_USE},
 * {@code LINK_SCOPE_IN_USE}, {@code DEFINITION_NOT_FROM_TEMPLATE},
 * {@code TEMPLATE_REVISION_MISMATCH_ON_REUSE}, {@code DEFINITION_NOT_SHAREABLE}, of één van de fase
 * C-codes ({@code CONFIG_BOOKMARK_SCOPE_PLACE_CONFLICT},
 * {@code CONFIG_BOOKMARK_WITHOUT_PLACE}, {@code CONFIG_BOOKMARK_PLACE_UNRESOLVED},
 * {@code CONFIG_BOOKMARK_PLACE_NOT_SUPPORTED}). 400 met een stabiele {@code code}:
 * {@code MATERIALISATION_MODE_REQUIRED}, {@code REUSE_DEFINITION_REQUIRED},
 * {@code REUSE_DEFINITION_NOT_ALLOWED}, {@code DEFINITION_SCOPE_VALUE_NOT_ALLOWED_ON_REUSE},
 * {@code BOOKMARK_UNKNOWN},
 * {@code CONFIG_REQUIRED_BOOKMARK_MISSING}, {@code CONFIG_BOOKMARK_VALUE_INVALID},
 * {@code CONFIG_BOOKMARK_VALUE_TOO_LONG}, {@code LINK_FIELD_BOTH_BOOKMARK_AND_EXPLICIT}, of de
 * {@code CONFIG_*}-code van een fase F-blokkade; 400 zonder code bij een ontbrekende of te lange waarde.
 * <p>
 * Alle antwoorden komen uit {@link TemplateBookmarkService} en {@link TemplateMaterialisationService} als
 * records; er gaat geen JPA-entiteit naar buiten. Inline {@code record}-requests/responses, dezelfde
 * stijl als {@code CatalogImportBundleController}.
 */
@RestController
@RequestMapping("/api/catalog-import/templates")
@ConditionalOnProperty(prefix = "catalogimport.setup-api", name = "enabled", havingValue = "true")
public class CatalogImportTemplateController {

    private final TemplateBookmarkService templateBookmarks;
    private final TemplateMaterialisationService materialisations;

    public CatalogImportTemplateController(TemplateBookmarkService templateBookmarks,
                                           TemplateMaterialisationService materialisations) {
        this.templateBookmarks = templateBookmarks;
        this.materialisations = materialisations;
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

    /**
     * De definities die al uit dit sjabloon gematerialiseerd zijn — de keuzelijst waarmee het scherm
     * "nieuw" en "hergebruik" naast elkaar kan zetten (§6). Per definitie: de sjabloonversie waarop ze
     * bevroren is, hoeveel koppelingen ze al delen, en of ze deelbaar is (met de bookmark die het
     * eventueel verhindert). Deterministisch gesorteerd op code.
     */
    @GetMapping("/{definitionId}/materialisations")
    PageResult<MaterialisedDefinitionView> materialisations(
            @PathVariable("definitionId") long definitionId,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        return materialisations.listMaterialisations(definitionId, page, size);
    }

    /**
     * <b>De operatie</b>: materialiseert het sjabloon tot een nieuwe importdefinitie + revisie 1
     * ({@code DRAFT}) + koppeling, in één transactie (201). Het antwoord toont altijd welke
     * sjabloonversie effectief gebruikt is, ook wanneer die {@code SUPERSEDED} is (beslissingslog 23/09
     * Q3) — materialiseren uit een oudere versie mag, maar nooit stilzwijgend.
     * <p>
     * Met {@code mode = REUSE_DEFINITION} + {@code reuseDefinitionId} ontstaat er <b>alleen</b> een
     * koppeling: de bestaande definitie en haar revisie blijven ongewijzigd, en
     * {@code definitionCreated} is {@code false} (bouwstap 5d, §6).
     */
    @PostMapping("/{definitionId}/materialisations")
    @ResponseStatus(HttpStatus.CREATED)
    MaterialisationView materialise(@PathVariable("definitionId") long definitionId,
                                    @RequestBody MaterialiseRequest request) {
        return materialisations.materialise(definitionId, request);
    }
}
