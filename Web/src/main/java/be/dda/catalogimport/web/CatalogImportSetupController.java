package be.dda.catalogimport.web;

import be.dda.catalogimport.service.ActorIdentity;
import be.dda.catalogimport.service.RevisionSuccessorService;
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
import be.dda.catalogimport.service.SetupService.RevisionView;
import be.dda.catalogimport.service.SetupService.SourceOrganisationView;
import be.dda.catalogimport.service.SetupService.TaskView;
import be.dda.catalogimport.service.SetupService.UpdateRevisionCommand;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * De schrijfpaden waarmee een gebruiker een importketen inricht (bronorganisatie → definitie → revisie →
 * koppeling → taak): de inrichting van een nieuwe leverancier en taak in de UI.
 *
 * <h2>Rechten — niet (meer) achter de setup-vlag</h2>
 * Sinds NT-3 (beslissingslog 2026-09-30 "Nieuwe leverancier + taak (NT-spoor)", V2 = a) bestaat deze
 * controller <b>altijd</b>, ongeacht {@code catalogimport.setup-api.enabled}: een gebruiker met het recht
 * {@code catalogImport.manage} moet zelf een leverancier en een taak kunnen aanmaken. De bescherming is
 * uitsluitend het recht per actie: elk pad hieronder vraagt een login en {@code MANAGE}
 * ({@link RequiresPermission}); zonder recht 403 {@code PERMISSION_DENIED} vóór er iets gelezen of
 * geschreven wordt (fase5-perm-design.md §3, "recht eerst"). Paden, bodies en statuscodes zijn
 * ongewijzigd ten opzichte van de periode achter de vlag.
 * <p>
 * Enkel {@code GET /setup/overview} blijft achter de vlag, in {@link CatalogImportSetupOverviewController}.
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
 * 201 bij een aangemaakte rij, 200 bij {@code activate} en de {@code PATCH} op een
 * revisie, 204 bij het verwijderen van een mapping of filter; 404 met een stabiele
 * {@code code} ({@code SOURCE_ORGANISATION_NOT_FOUND}, {@code DEFINITION_NOT_FOUND},
 * {@code REVISION_NOT_FOUND}, {@code FIELD_NOT_FOUND}, {@code LINK_NOT_FOUND}, en sinds bouwstap S1-X-4
 * {@code MAPPING_NOT_FOUND} en {@code FILTER_NOT_FOUND}); 409 met een stabiele
 * {@code code} ({@code SOURCE_ORGANISATION_CODE_IN_USE}, {@code DEFINITION_CODE_IN_USE},
 * {@code LINK_CODE_IN_USE}, {@code LINK_SCOPE_IN_USE}, {@code TASK_NAME_IN_USE},
 * {@code REVISION_NOT_ACTIVATABLE}, {@code REVISION_NOT_EDITABLE}, sinds bouwstap S1-X-2
 * {@code REVISION_NOT_CLONEABLE}, {@code REVISION_DRAFT_ALREADY_EXISTS} en
 * {@code REVISION_ACTIVATION_CONFLICT}, en sinds S1-X-4
 * {@code REVISION_CANONICALISATION_CHANGE_BLOCKED}, {@code IDENTITY_CHANGE_NOT_ACKNOWLEDGED} plus de
 * bestaande {@code CONFIG_BOOKMARK_*}-familie na een verwijdering); 400 bij een ongeldige waarde of een
 * configuratiefout ({@code CHANGE_REASON_REQUIRED} op de opvolgrevisie) — een configuratiefout draagt de
 * {@code CONFIG_*}-code van de bestaande screeningvalidatie in {@code error} én sinds NT-3 in {@code code};
 * een veldfout van {@link SetupService} draagt sinds NT-3 {@code <VELD>_REQUIRED}, {@code <VELD>_TOO_LONG}
 * of {@code <VELD>_INVALID} in {@code code} (additief; de tekst in {@code error} is ongewijzigd). Twee
 * gelijktijdige verzoeken die dezelfde bronorganisatie, definitie, koppeling of taak aanmaken, geven sinds
 * NT-3 één 201 en één 409 met dezelfde {@code *_IN_USE}-code als de gewone controle vooraf, nooit een 500.
 * <p>
 * Alle antwoorden komen uit {@link SetupService} als records; er gaat geen JPA-entiteit naar buiten.
 * Er wordt hier nooit een geheim of bestandsinhoud bewaard of gelogd.
 */
@RestController
@RequestMapping("/api/catalog-import/setup")
public class CatalogImportSetupController {

    /**
     * Body van {@code activate}; {@code approvedBy} is optioneel en sinds 5A-6 enkel nog een controle
     * tegen de aangemelde gebruiker.
     */
    public record ActivateRevisionRequest(String approvedBy) {
    }

    /**
     * Body van {@code successor} (endpoint E2, {@code docs/design/revision-successor-design.md} §6).
     *
     * @param changeReason verplicht: waarom komt er een opvolgrevisie? Zonder reden 400
     *                     {@code CHANGE_REASON_REQUIRED} — de kolom is nullable, maar een opvolger
     *                     zonder reden laat niets na over de bedoeling (§14.20, ontdekking §9)
     * @param createdBy    optioneel en sinds 5A-6 enkel nog een controle tegen de aangemelde gebruiker
     */
    public record CreateSuccessorRequest(String changeReason, String createdBy) {
    }

    private final SetupService setup;
    private final RevisionSuccessorService successors;
    private final CurrentActor currentActor;

    public CatalogImportSetupController(SetupService setup, RevisionSuccessorService successors,
                                        CurrentActor currentActor) {
        this.setup = setup;
        this.successors = successors;
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
     * Maakt de opvolgrevisie van een {@code ACTIVE} of {@code SUPERSEDED} revisie: een nieuwe
     * {@code DRAFT} met dezelfde configuratie en dezelfde kindrijen, binnen dezelfde definitie
     * (endpoint E2, {@code docs/design/revision-successor-design.md} §1, §2, §6). Activeert niets.
     * <p>
     * 201 met dezelfde revisieweergave als {@code createRevision}; 404 {@code REVISION_NOT_FOUND};
     * 409 {@code REVISION_NOT_CLONEABLE} (een DRAFT of andere status als bron) of
     * {@code REVISION_DRAFT_ALREADY_EXISTS} (er staat al een DRAFT open op deze definitie);
     * 400 {@code CHANGE_REASON_REQUIRED}.
     */
    @RequiresPermission(Permission.MANAGE)
    @PostMapping("/revisions/{revisionId}/successor")
    @ResponseStatus(HttpStatus.CREATED)
    RevisionView createSuccessor(@PathVariable("revisionId") long revisionId,
                                 @RequestBody(required = false) CreateSuccessorRequest request) {
        ActorIdentity actor = currentActor.signer(request == null ? null : request.createdBy(), "createdBy");
        return successors.createSuccessor(revisionId, request == null ? null : request.changeReason(), actor);
    }

    /**
     * Wijzigt de scalaire velden van een {@code DRAFT}-revisie (endpoint E3,
     * {@code docs/design/revision-successor-design.md} §5, §6). Elk veld dat het verzoek niet noemt (of op
     * {@code null} zet) blijft ongewijzigd; de vier configuratiehashes worden daarna herberekend.
     * <p>
     * 200 met dezelfde revisieweergave als {@code createRevision}/{@code successor}/{@code activate}; 404
     * {@code REVISION_NOT_FOUND}; 409 {@code REVISION_NOT_EDITABLE} (alleen een DRAFT is bewerkbaar),
     * {@code REVISION_CANONICALISATION_CHANGE_BLOCKED} (R-REV-X2: er bestaat al aanvaarde bronstaat voor een
     * koppeling van deze definitie — deze blokkade is onvoorwaardelijk) of
     * {@code IDENTITY_CHANGE_NOT_ACKNOWLEDGED} (R-REV-X3: zonder
     * {@code acknowledgeIdentityChange: true}); 400 bij een lege verplichte of ongeldige waarde. In elk
     * foutgeval is er niets opgeslagen.
     */
    @RequiresPermission(Permission.MANAGE)
    @PatchMapping("/revisions/{revisionId}")
    RevisionView updateRevision(@PathVariable("revisionId") long revisionId,
                                @RequestBody(required = false) UpdateRevisionCommand request) {
        // Dezelfde ondertekeningscontrole als elke andere schrijfhandler (5A-6). Er is geen
        // updated_by-kolom, dus er wordt geen naam bewaard; een meegegeven createdBy die een andere
        // persoon aanwijst is nog steeds 400 ACTOR_FIELD_MISMATCH in plaats van stil genegeerd.
        currentActor.signer(request == null ? null : request.createdBy(), "createdBy");
        return setup.updateRevision(revisionId, request);
    }

    /**
     * Verwijdert één (meestal geërfde) veldmapping van een {@code DRAFT}-revisie (endpoint E4,
     * {@code docs/design/revision-successor-design.md} §6).
     * <p>
     * 204 zonder inhoud; 404 {@code REVISION_NOT_FOUND} of {@code MAPPING_NOT_FOUND} (ook wanneer de
     * mapping bij een andere revisie hoort); 409 {@code REVISION_NOT_EDITABLE} of een
     * {@code CONFIG_BOOKMARK_*}-code wanneer een bookmarkdeclaratie van deze revisie op deze mapping
     * steunde — dan is er niets verwijderd.
     */
    @RequiresPermission(Permission.MANAGE)
    @DeleteMapping("/revisions/{revisionId}/mappings/{mappingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteMapping(@PathVariable("revisionId") long revisionId,
                       @PathVariable("mappingId") long mappingId) {
        requireSigningUser();
        setup.deleteMapping(revisionId, mappingId);
    }

    /**
     * Verwijdert één recordfilter van een {@code DRAFT}-revisie (endpoint E4); zelfde statuscodes als
     * {@link #deleteMapping}, met {@code FILTER_NOT_FOUND} in plaats van {@code MAPPING_NOT_FOUND}.
     */
    @RequiresPermission(Permission.MANAGE)
    @DeleteMapping("/revisions/{revisionId}/filters/{filterId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteFilter(@PathVariable("revisionId") long revisionId,
                      @PathVariable("filterId") long filterId) {
        requireSigningUser();
        setup.deleteFilter(revisionId, filterId);
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
}
