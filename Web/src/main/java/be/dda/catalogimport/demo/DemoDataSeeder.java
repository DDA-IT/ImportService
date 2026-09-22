package be.dda.catalogimport.demo;

import be.dda.catalogimport.domain.DefinitionUsageType;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.service.SetupService;
import be.dda.catalogimport.service.SetupService.CreateDefinitionCommand;
import be.dda.catalogimport.service.SetupService.CreateLinkCommand;
import be.dda.catalogimport.service.SetupService.CreateMappingCommand;
import be.dda.catalogimport.service.SetupService.CreateRevisionCommand;
import be.dda.catalogimport.service.SetupService.CreateSourceOrganisationCommand;
import be.dda.catalogimport.service.SetupService.CreateTaskCommand;
import be.dda.catalogimport.service.SetupService.DefinitionView;
import be.dda.catalogimport.service.SetupService.LinkView;
import be.dda.catalogimport.service.SetupService.OrganisationOverview;
import be.dda.catalogimport.service.SetupService.RevisionView;
import be.dda.catalogimport.service.SetupService.TaskView;
import java.math.BigDecimal;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Maakt bij het opstarten van het {@code demo}-profiel één volledige voorbeeldketen aan, zodat een
 * mens meteen een levering kan uploaden zonder eerst zelf een definitie te configureren.
 * <p>
 * <b>Alleen in het {@code demo}-profiel.</b> Buiten dat profiel bestaat deze bean niet; er worden dus
 * nooit demogegevens in een andere omgeving geschreven.
 * <p>
 * <b>Idempotent.</b> De bronorganisatie {@value #ORGANISATION_CODE} is de sleutel: bestaat die al, dan
 * wordt er niets aangemaakt en wordt de bestaande {@code taskId} gelogd. Twee keer starten levert dus
 * nooit een tweede keten op — ook niet met een persistente database.
 * <p>
 * <b>De gekozen configuratie</b> is de eenvoudigste die de belangrijkste scenario's toont: een CSV met
 * puntkomma en header, een driedelige identiteit ({@code leverancier + groep + referentie}), basisprijs
 * met munt, omschrijving en één kritieke koppelreferentie (EAN). De drempels staan ruim
 * ({@code creation_threshold_share_percent} 10, {@code max_critical_share_percent} 25) omdat de
 * standaard van 1 procent bij een voorbeeldbestand van tien regels elke creatie en elke fout meteen zou
 * tegenhouden — zie {@code README.md}.
 */
@Component
@Profile("demo")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DemoDataSeeder.class);

    /** De sleutel van de demoketen; haar bestaan bepaalt of er nog iets aangemaakt moet worden. */
    public static final String ORGANISATION_CODE = "DEMO";
    public static final String DEFINITION_CODE = "DEMO-CSV";
    public static final String LINK_CODE = "DEMO-LINK";
    public static final String LIBRARY_CODE = "DEMOBIB";
    public static final String TASK_NAME = "Demo manuele levering";
    private static final String CREATED_BY = "demo-seeder";

    private final SetupService setup;
    private final String port;

    public DemoDataSeeder(SetupService setup, @Value("${server.port:8080}") String port) {
        this.setup = setup;
        this.port = port;
    }

    @Override
    public void run(ApplicationArguments args) {
        Optional<Long> existing = existingTaskId();
        if (existing.isPresent()) {
            LOG.info("Demoketen bestaat al; er wordt niets aangemaakt.");
            logHowToUse(existing.get());
            return;
        }
        if (hasOrganisation()) {
            // Halve keten: niet aanvullen en niets weggooien - dat zou onzichtbaar gegevens wijzigen.
            LOG.warn("Bronorganisatie {} bestaat al maar heeft geen koppeling met een taak; de demoketen "
                    + "wordt niet aangevuld. Verwijder de bestaande rijen of gebruik een lege database.",
                    ORGANISATION_CODE);
            return;
        }
        logHowToUse(createChain());
    }

    private long createChain() {
        setup.createSourceOrganisation(new CreateSourceOrganisationCommand(ORGANISATION_CODE,
                "Demo leverancier ACME", SourceOrganisationType.SUPPLIER));
        DefinitionView definition = setup.createDefinition(new CreateDefinitionCommand(ORGANISATION_CODE,
                DEFINITION_CODE, "Demo CSV-catalogus", DefinitionUsageType.OWN_DEFINITION));
        RevisionView revision = setup.createRevision(definition.id(), demoRevision());
        // Eén kritieke koppelreferentie: ze toont de referentiecontrole zonder extra configuratie.
        setup.addMapping(revision.id(), new CreateMappingCommand("EAN", "ean", 1, null, null, null, null,
                false, null, null, null, null, null, null, null, CREATED_BY));
        setup.activateRevision(revision.id(), CREATED_BY);
        LinkView link = setup.createLink(new CreateLinkCommand(definition.id(), LINK_CODE, "Demo koppeling",
                ORGANISATION_CODE, LIBRARY_CODE, null));
        TaskView task = setup.createTask(new CreateTaskCommand(link.id(), TASK_NAME, true));
        LOG.info("Demoketen aangemaakt: organisatie {}, definitie {} (id {}), revisie {} ACTIVE, koppeling {} "
                        + "(bibliotheek {}), taak '{}'.", ORGANISATION_CODE, DEFINITION_CODE, definition.id(),
                revision.id(), LINK_CODE, LIBRARY_CODE, TASK_NAME);
        return task.id();
    }

    /**
     * De demorevisie: puntkomma, header, driedelige identiteit, basisprijs met munt en omschrijving.
     * Canonicalisatieversie 2 is verplicht zodra er een munt gelezen wordt of een referentie gemapt is
     * (ontwerp fase 3, par. 3.5) — anders zou een gewijzigde munt of EAN buiten de vingerafdruk vallen.
     */
    private static CreateRevisionCommand demoRevision() {
        return new CreateRevisionCommand(";", "\"", "UTF-8", true, 1, "HEADER_NAME", null,
                IdentityProfileKind.THREE_PART, "leverancier", "groep", "referentie", null,
                "prijs", "omschrijving", "valuta", 2,
                new BigDecimal("10"), new BigDecimal("25"), null, null,
                null, null, null, null, null, "Demoketen voor handmatig uitproberen", CREATED_BY);
    }

    private boolean hasOrganisation() {
        return organisation().isPresent();
    }

    private Optional<Long> existingTaskId() {
        return organisation().stream()
                .flatMap(organisation -> organisation.definitions().stream())
                .filter(definition -> DEFINITION_CODE.equals(definition.code()))
                .flatMap(definition -> definition.links().stream())
                .flatMap(link -> link.tasks().stream())
                .map(TaskView::id)
                .findFirst();
    }

    private Optional<OrganisationOverview> organisation() {
        return setup.overview().sourceOrganisations().stream()
                .filter(organisation -> ORGANISATION_CODE.equals(organisation.code()))
                .findFirst();
    }

    /**
     * Logt de {@code taskId} en een kant-en-klaar {@code curl}-commando. Er wordt geen bestandsinhoud
     * en geen geheim gelogd; enkel de id's die de gebruiker nodig heeft.
     */
    private void logHowToUse(long taskId) {
        LOG.info("""

                        ===============================================================================
                        Demoketen klaar. taskId = {}
                        Upload de eerste voorbeeldlevering (vanuit de projectmap):

                          curl -F "file=@docs/samples/01-eerste-levering.csv" \\
                               -F "deliveryReference=REF-01" -F "uploadedBy=demo@example.test" \\
                               http://localhost:{}/api/catalog-import/tasks/{}/deliveries

                        Overzicht van de configuratie: http://localhost:{}/api/catalog-import/setup/overview
                        H2-console: http://localhost:{}/h2-console (jdbc:h2:mem:catalogimport, gebruiker sa)
                        LET OP: de setup-API staat in dit profiel AAN en kent geen authenticatie.
                        ===============================================================================""",
                taskId, port, taskId, port, port);
    }
}
