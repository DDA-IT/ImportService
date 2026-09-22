package be.dda.catalogimport.web;

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
 * default is {@code false} en {@code application.yml} zet de vlag bewust niet. Er is nog geen
 * authenticatie of autorisatie (Fase 5): iedereen die deze endpoints kan bereiken, kan een
 * importdefinitie aanmaken en haar drempels bepalen, en daarmee de controle op een leverancierscatalogus
 * uitschakelen. Zet de vlag dus uitsluitend aan op een ontwikkelmachine met wegwerpgegevens (het
 * {@code demo}-profiel doet dat). Met authenticatie in Fase 5 hoort dit achter
 * {@code catalogImport.manage} te komen of volledig te verdwijnen ten gunste van een beheerscherm.
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

    /** Body van {@code activate}; {@code approvedBy} is optioneel zolang er geen authenticatie is. */
    public record ActivateRevisionRequest(String approvedBy) {
    }

    private final SetupService setup;

    public CatalogImportSetupController(SetupService setup) {
        this.setup = setup;
    }

    @PostMapping("/source-organisations")
    @ResponseStatus(HttpStatus.CREATED)
    SourceOrganisationView createSourceOrganisation(@RequestBody CreateSourceOrganisationCommand request) {
        return setup.createSourceOrganisation(request);
    }

    @PostMapping("/definitions")
    @ResponseStatus(HttpStatus.CREATED)
    DefinitionView createDefinition(@RequestBody CreateDefinitionCommand request) {
        return setup.createDefinition(request);
    }

    /** Maakt een {@code DRAFT}-revisie; pas {@code activate} maakt ze bruikbaar voor een levering. */
    @PostMapping("/definitions/{definitionId}/revisions")
    @ResponseStatus(HttpStatus.CREATED)
    RevisionView createRevision(@PathVariable("definitionId") long definitionId,
                                @RequestBody CreateRevisionCommand request) {
        return setup.createRevision(definitionId, request);
    }

    /**
     * Zet de revisie op {@code ACTIVE} en de vorige actieve revisie van dezelfde definitie op
     * {@code SUPERSEDED}. De volledige configuratie wordt eerst gevalideerd met dezelfde fabrieken als
     * de screening.
     */
    @PostMapping("/revisions/{revisionId}/activate")
    RevisionView activateRevision(@PathVariable("revisionId") long revisionId,
                                  @RequestBody(required = false) ActivateRevisionRequest request) {
        return setup.activateRevision(revisionId, request == null ? null : request.approvedBy());
    }

    @PostMapping("/revisions/{revisionId}/mappings")
    @ResponseStatus(HttpStatus.CREATED)
    MappingView addMapping(@PathVariable("revisionId") long revisionId,
                           @RequestBody CreateMappingCommand request) {
        return setup.addMapping(revisionId, request);
    }

    @PostMapping("/revisions/{revisionId}/filters")
    @ResponseStatus(HttpStatus.CREATED)
    FilterView addFilter(@PathVariable("revisionId") long revisionId,
                         @RequestBody CreateFilterCommand request) {
        return setup.addFilter(revisionId, request);
    }

    @PostMapping("/revisions/{revisionId}/field-criticality")
    @ResponseStatus(HttpStatus.CREATED)
    FieldCriticalityView addFieldCriticality(@PathVariable("revisionId") long revisionId,
                                             @RequestBody CreateFieldCriticalityCommand request) {
        return setup.addFieldCriticality(revisionId, request);
    }

    @PostMapping("/links")
    @ResponseStatus(HttpStatus.CREATED)
    LinkView createLink(@RequestBody CreateLinkCommand request) {
        return setup.createLink(request);
    }

    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    TaskView createTask(@RequestBody CreateTaskCommand request) {
        return setup.createTask(request);
    }

    /** Wat er geconfigureerd staat, inclusief de {@code taskId} die een upload nodig heeft. */
    @GetMapping("/overview")
    Overview overview() {
        return setup.overview();
    }
}
