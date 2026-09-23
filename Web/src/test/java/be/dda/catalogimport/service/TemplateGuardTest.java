package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.domain.BookmarkDataType;
import be.dda.catalogimport.domain.BookmarkUsagePlace;
import be.dda.catalogimport.domain.BookmarkValueScope;
import be.dda.catalogimport.domain.DefinitionUsageType;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.service.TemplateMaterialisationService.BookmarkValue;
import be.dda.catalogimport.service.TemplateMaterialisationService.MaterialisationMode;
import be.dda.catalogimport.service.TemplateMaterialisationService.MaterialisationView;
import be.dda.catalogimport.service.TemplateMaterialisationService.MaterialiseRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

/**
 * R-SHR-01: een sjabloon krijgt nooit een {@link ImportLink}, ook niet wanneer de servicecontrole
 * omzeild wordt (sjabloon-materialisatie-design.md §1, §10 {@code TemplateGuardTest}; beslissingslog
 * 23/09 keuze 7, changeset {@code 006-5}).
 * <p>
 * <b>Waarom dit apart bewezen wordt.</b> Een sjabloon is een blauwdruk. Zou er toch een koppeling aan
 * kunnen hangen, dan krijgt dat sjabloon leveringen, batches, mutaties en uiteindelijk een publicatie.
 * De materialisatieservice controleert het zelf ({@code DEFINITION_NOT_A_TEMPLATE} in fase A), maar
 * rekent er niet op: de tests hieronder schrijven <b>rechtstreeks via de repositories</b>, dus zonder
 * enige servicecontrole, en bewijzen dat de database het alsnog weigert.
 * <p>
 * De guard werkt via de samengestelde foreign key {@code fk_import_link_definition_usage} plus de check
 * {@code ck_import_link_definition_usage_type} op de niet-gemapte kolom {@code definition_usage_type}:
 * Hibernate laat die kolom bij het invoegen weg, de databasedefault vult {@code 'OWN_DEFINITION'}, en de
 * FK vindt dan geen definitie met dat gebruikstype.
 */
// Dezelfde annotaties als TemplateMaterialisationTest, bewust letterlijk gelijk: zo delen de vier
// 5c-testklassen één applicatiecontext en dus één (kleine) verbindingspool. Zie de klasse-javadoc
// van TemplateMaterialisationTest.
@SpringBootTest(properties = {"catalogimport.setup-api.enabled=true",
        "spring.datasource.hikari.maximum-pool-size=4"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class TemplateGuardTest {

    private static final String USER = MaterialisationFixtures.USER;

    @Autowired
    private ApplicationContext context;
    @Autowired
    private TemplateMaterialisationService materialisation;
    @Autowired
    private ImportDefinitionRepository definitions;
    @Autowired
    private ImportLinkRepository links;

    private MaterialisationFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = MaterialisationFixtures.of(context);
    }

    @Test
    void theDatabaseRefusesAnImportLinkToATemplateEvenWithoutTheServiceCheck() {
        MaterialisationFixtures.Template template = fixtures.template("GUARD");
        String code = template.unique() + "-SMOKKEL";
        ImportLink smuggled = new ImportLink(code, "Koppeling naar een sjabloon", template.definition(),
                template.supplier(), "PSARF030");

        assertThatThrownBy(() -> links.saveAndFlush(smuggled))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(links.findByCode(code)).isEmpty();
        assertThat(links.findByImportDefinitionId(template.definition().getId())).isEmpty();
    }

    /**
     * De keerzijde van dezelfde FK, uitdrukkelijk gewild (changeset {@code 006-5}): zolang er een
     * koppeling naar een definitie wijst, kan die definitie niet meer tot sjabloon omgebouwd worden.
     * Een live definitie tot blauwdruk maken terwijl ze leveringen ontvangt, was nooit de bedoeling.
     */
    @Test
    void theDatabaseRefusesTurningADefinitionWithALinkIntoATemplate() {
        MaterialisationFixtures.Template own = fixtures.template("GUARD2",
                DefinitionUsageType.OWN_DEFINITION, RevisionStatus.ACTIVE);
        links.saveAndFlush(new ImportLink(own.unique() + "-LNK", "Gewone koppeling", own.definition(),
                own.supplier(), "PSARF031"));

        ImportDefinition definition = definitions.findById(own.definition().getId()).orElseThrow();
        definition.setUsageType(DefinitionUsageType.REUSABLE_TEMPLATE);

        assertThatThrownBy(() -> definitions.saveAndFlush(definition))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(definitions.findById(own.definition().getId()).orElseThrow().getUsageType())
                .isEqualTo(DefinitionUsageType.OWN_DEFINITION);
    }

    /**
     * De tegenproef: de koppeling die de wizard zelf aanlegt, hangt aan de <b>afgeleide</b> definitie
     * ({@code OWN_DEFINITION}) en niet aan het sjabloon. De guard mag de normale weg dus niet hinderen.
     */
    @Test
    void theMaterialisedLinkHangsOnTheDerivedDefinitionAndNotOnTheTemplate() {
        MaterialisationFixtures.Template template = fixtures.template("GUARD3");
        fixtures.declareOn(template.revision(), "DOELBIBLIOTHEEK", BookmarkValueScope.LINK, true,
                BookmarkDataType.TEXT, 1, BookmarkUsagePlace.LINK_LIBRARY_CODE, "");

        MaterialisationView view = materialisation.materialise(template.definition().getId(),
                new MaterialiseRequest(null, MaterialisationMode.NEW_DEFINITION, null,
                        template.definitionCode(), "Afgeleide definitie", null, template.linkCode(),
                        "Afgeleide koppeling", template.supplier().getCode(), null, null,
                        List.of(new BookmarkValue("DOELBIBLIOTHEEK", "PSARF032")), USER));

        ImportLink link = links.findById(view.importLinkId()).orElseThrow();
        assertThat(link.getImportDefinition().getId()).isEqualTo(view.definitionId());
        assertThat(definitions.findById(view.definitionId()).orElseThrow().getUsageType())
                .isEqualTo(DefinitionUsageType.OWN_DEFINITION);
        // Het sjabloon zelf blijft koppelingsvrij.
        assertThat(links.findByImportDefinitionId(template.definition().getId())).isEmpty();
    }
}
