package be.dda.catalogimport.web;

import be.dda.catalogimport.service.ActorIdentity;
import be.dda.catalogimport.service.SetupService;
import be.dda.catalogimport.service.SetupService.CreateDefinitionCommand;
import be.dda.catalogimport.service.SetupService.CreateFieldCriticalityCommand;
import be.dda.catalogimport.service.SetupService.CreateFilterCommand;
import be.dda.catalogimport.service.SetupService.CreateLinkCommand;
import be.dda.catalogimport.service.SetupService.CreateMappingCommand;
import be.dda.catalogimport.service.SetupService.CreateRevisionCommand;
import be.dda.catalogimport.service.SetupService.CreateSourceOrganisationCommand;
import be.dda.catalogimport.service.SetupService.CreateTaskCommand;
import be.dda.catalogimport.service.SetupService.DefinitionView;
import be.dda.catalogimport.service.SetupService.FieldCriticalityView;
import be.dda.catalogimport.service.SetupService.FilterView;
import be.dda.catalogimport.service.SetupService.LinkView;
import be.dda.catalogimport.service.SetupService.MappingView;
import be.dda.catalogimport.service.SetupService.Overview;
import be.dda.catalogimport.service.SetupService.RevisionView;
import be.dda.catalogimport.service.SetupService.SourceOrganisationView;
import be.dda.catalogimport.service.SetupService.TaskView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ontwikkelhulp: configureert een importketen (bronorganisatie → definitie → revisie → koppeling →
 * taak) zodat een mens de applicatie lokaal met de hand kan uitproberen.
 *
 * <h2>WAARSCHUWING — dit endpoint staat standaard uit en mag nooit in productie aan</h2>
 * Deze controller bestaat alleen wanneer {@code catalogimport.setup-api.enabled=true} staat; de
 * default is {@code false} en {@code application.yml} zet de vlag bewust niet. Sinds Fase 5-AUTH
 * (5A-1) vereist elk pad hieronder een login, maar er is nog geen rechtencontrole per actie (5-PERM):
 * iedereen die zich kan aanmelden én deze endpoints kan bereiken, kan een importdefinitie aanmaken en
 * haar drempels bepalen, en daarmee de controle op een leverancierscatalogus uitschakelen. De vlag
 * blijft dus een extra bescherming bovenop de login (A12): zet ze uitsluitend aan op een
 * ontwikkelmachine met wegwerpgegevens (het {@code demo}-profiel doet dat). Met 5-PERM hoort dit achter
 * {@code catalogImport.manage} te komen of volledig te verdwijnen ten gunste van een beheerscherm.
 *
 * <h2>Wie tekent (5A-6)</h2>
 * Elke schrijfhandler roept {@link CurrentActor#signer} aan <b>vóór</b> de service: 400
 * {@code ACTOR_FIELD_MISMATCH} wanneer een meegegeven {@code createdBy}/{@code approvedBy} een andere
 * persoon aanwijst dan de aangemelde gebruiker, 403 {@code SYSTEM_ACTOR_FORBIDDEN} voor {@code system}.
 * Die controles gaan dus vóór 404/409, en er wordt in dat geval niets geschreven. De actorvelden zijn
 * daardoor <b>optioneel</b> geworden; bewaard wordt altijd de naam uit het token plus het OIDC-subject
 * (changeset 007-4). De drie endpoints zonder {@code *_by}-kolom (bronorganisatie, koppeling, taak)
 * ondertekenen niets en vragen enkel een bruikbare, niet-{@code system} login.
 *
 * <h2>Statuscodes</h2>
 * 201 bij een aangemaakte rij, 200 bij {@code activate} en {@code overview}; 404 met een stabiele
 * {@code code} ({@code SOURCE_ORGANISATION_NOT_FOUND}, {@code DEFINITION_NOT_FOUND},
 * {@code REVISION_NOT_FOUND}, {@code FIELD_NOT_FOUND}, {@code LINK_NOT_FOUND}); 409 met een stabiele
 * {@code code} ({@code SOURCE_ORGANISATION_CODE_IN_USE}, {@code DEFINITION_CODE_IN_USE},
 * {@code LINK_CODE_IN_USE}, {@code LINK_SCOPE_IN_USE}, {@code TASK_NAME_IN_USE},
 * {@code REVISION_NOT_ACTIVATABLE}, {@code REVISION_NOT_EDITABLE}); 400 bij een ongeldige waarde of
 * een configuratiefout — die laatste draagt de {@code CONFIG_*}-code van de bestaande
 * screeningvalidatie in {@code error}.
 * <p>
 * Alle antwoorden komen uit {@link SetupService} als records; er gaat geen JPA-entiteit naar buiten.
 * Er wordt hier nooit een geheim of bestandsinhoud bewaard of gelogd.
 */
@RestController
@RequestMapping("/api/catalog-import/setup")
@ConditionalOnProperty(prefix = "catalogimport.setup-api", name = "enabled", havingValue = "true")
public class CatalogImportSetupController {

    /**
     * Body van {@code activate}; {@code approvedBy} is optioneel en sinds 5A-6 enkel nog een controle
     * tegen de aangemelde gebruiker.
     */
    public record ActivateRevisionRequest(String approvedBy) {
    }

    private final SetupService setup;
    private final CurrentActor currentActor;

    public CatalogImportSetupController(SetupService setup, CurrentActor currentActor) {
        this.setup = setup;
        this.currentActor = currentActor;
    }

    @RequiresPermission(Permission.MANAGE)
    @PostMapping("/source-organisations")
    @ResponseStatus(HttpStatus.CREATED)
    SourceOrganisationView createSourceOrganisation(@RequestBody CreateSourceOrganisationCommand request) {
        requireSigningUser();
        return setup.createSourceOrganisation(request);
    }

    @RequiresPermission(Permission.MANAGE)
    @PostMapping("/definitions")
    @ResponseStatus(HttpStatus.CREATED)
    DefinitionView createDefinition(@RequestBody CreateDefinitionCommand request) {
        // Het verzoek draagt hier geen actorveld; de service zette tot nu toe "setup-api". Met een
        // login komt de aangemelde gebruiker in created_by en zijn subject in created_by_subject.
        return setup.createDefinition(request, currentActor.signer(null, "createdBy"));
    }

    /** Maakt een {@code DRAFT}-revisie; pas {@code activate} maakt ze bruikbaar voor een levering. */
    @RequiresPermission(Permission.MANAGE)
    @PostMapping("/definitions/{definitionId}/revisions")
    @ResponseStatus(HttpStatus.CREATED)
    RevisionView createRevision(@PathVariable("definitionId") long definitionId,
                                @RequestBody CreateRevisionCommand request) {
        ActorIdentity actor = currentActor.signer(request == null ? null : request.createdBy(), "createdBy");
        return setup.createRevision(definitionId, request, actor);
    }

    /**
     * Zet de revisie op {@code ACTIVE} en de vorige actieve revisie van dezelfde definitie op
     * {@code SUPERSEDED}. De volledige configuratie wordt eerst gevalideerd met dezelfde fabrieken als
     * de screening.
     */
    @RequiresPermission(Permission.MANAGE)
    @PostMapping("/revisions/{revisionId}/activate")
    RevisionView activateRevision(@PathVariable("revisionId") long revisionId,
                                  @RequestBody(required = false) ActivateRevisionRequest request) {
        ActorIdentity actor =
                currentActor.signer(request == null ? null : request.approvedBy(), "approvedBy");
        return setup.activateRevision(revisionId, actor);
    }

    @RequiresPermission(Permission.MANAGE)
    @PostMapping("/revisions/{revisionId}/mappings")
    @ResponseStatus(HttpStatus.CREATED)
    MappingView addMapping(@PathVariable("revisionId") long revisionId,
                           @RequestBody CreateMappingCommand request) {
        ActorIdentity actor = currentActor.signer(request == null ? null : request.createdBy(), "createdBy");
        return setup.addMapping(revisionId, request, actor);
    }

    @RequiresPermission(Permission.MANAGE)
    @PostMapping("/revisions/{revisionId}/filters")
    @ResponseStatus(HttpStatus.CREATED)
    FilterView addFilter(@PathVariable("revisionId") long revisionId,
                         @RequestBody CreateFilterCommand request) {
        ActorIdentity actor = currentActor.signer(request == null ? null : request.createdBy(), "createdBy");
        return setup.addFilter(revisionId, request, actor);
    }

    @RequiresPermission(Permission.MANAGE)
    @PostMapping("/revisions/{revisionId}/field-criticality")
    @ResponseStatus(HttpStatus.CREATED)
    FieldCriticalityView addFieldCriticality(@PathVariable("revisionId") long revisionId,
                                             @RequestBody CreateFieldCriticalityCommand request) {
        ActorIdentity actor = currentActor.signer(request == null ? null : request.createdBy(), "createdBy");
        return setup.addFieldCriticality(revisionId, request, actor);
    }

    @RequiresPermission(Permission.MANAGE)
    @PostMapping("/links")
    @ResponseStatus(HttpStatus.CREATED)
    LinkView createLink(@RequestBody CreateLinkCommand request) {
        requireSigningUser();
        return setup.createLink(request);
    }

    @RequiresPermission(Permission.MANAGE)
    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    TaskView createTask(@RequestBody CreateTaskCommand request) {
        requireSigningUser();
        return setup.createTask(request);
    }

    /**
     * De schrijfhandlers zonder actorveld en zonder {@code *_by}-kolom (bronorganisatie, koppeling,
     * taak): er valt niets te vergelijken en niets te bewaren, maar {@code system} mag ook hier geen
     * configuratie aanmaken en een onbruikbare login blijft 403 {@code ACTOR_IDENTITY_INVALID}
     * (ontwerp par. 3: de controle gebeurt in élke schrijfhandler van par. 1.1).
     */
    private void requireSigningUser() {
        currentActor.signer(null, "createdBy");
    }

    /** Wat er geconfigureerd staat, inclusief de {@code taskId} die een upload nodig heeft. */
    @RequiresPermission(Permission.READ)
    @GetMapping("/overview")
    Overview overview() {
        return setup.overview();
    }
}
