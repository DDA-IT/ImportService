package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.dao.ImportDefinitionBookmarkRepository;
import be.dda.catalogimport.dao.ImportDefinitionBookmarkUsageRepository;
import be.dda.catalogimport.dao.ImportDefinitionBookmarkValueRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportFieldMappingRepository;
import be.dda.catalogimport.dao.ImportLinkBookmarkValueRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.ImportRecordFilterRepository;
import be.dda.catalogimport.dao.ImportRevisionFieldCriticalityRepository;
import be.dda.catalogimport.domain.BookmarkDataType;
import be.dda.catalogimport.domain.BookmarkUsagePlace;
import be.dda.catalogimport.domain.BookmarkValueScope;
import be.dda.catalogimport.domain.FilterOperator;
import be.dda.catalogimport.service.TemplateMaterialisationService.BookmarkValue;
import be.dda.catalogimport.service.TemplateMaterialisationService.MaterialisationMode;
import be.dda.catalogimport.service.TemplateMaterialisationService.MaterialisationView;
import be.dda.catalogimport.service.TemplateMaterialisationService.MaterialiseRequest;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * Bouwstap 5c: de transactiegrens van §3 en de bewuste niet-idempotentie van A35
 * (sjabloon-materialisatie-design.md §10, {@code TemplateMaterialisationAtomicityTest}).
 *
 * <h2>Wat hier bewezen wordt</h2>
 * <ul>
 *   <li><b>Fase F laat niets achter.</b> Een configuratiefout die pas ná het schrijven blijkt, rolt de
 *       hele transactie terug: definitie, revisie, mappings, filters, koppeling, gekopieerde
 *       declaraties en beide soorten waarderijen zijn allemaal weg. Een half gematerialiseerde
 *       definitie zou een definitie zonder eigenaar achterlaten die er geldig uitziet.</li>
 *   <li><b>Twee keer materialiseren geeft nooit een duplicaat.</b> De tweede poging botst op de
 *       bestaande unieke sleutels en krijgt 409; er komt geen tweede definitie en geen tweede
 *       koppeling bij. Dat is de duplicaatpreventie van A35 — er is bewust geen idempotentiesleutel.</li>
 * </ul>
 * Het bewijs gebeurt met een telling van <b>alle</b> betrokken tabellen vóór en na de mislukte poging,
 * niet met een zoekopdracht op één code: zo valt ook een rij op die met een andere sleutel zou
 * achterblijven.
 */
// Dezelfde annotaties als TemplateMaterialisationTest, bewust letterlijk gelijk: zo delen de vier
// 5c-testklassen één applicatiecontext en dus één (kleine) verbindingspool. Zie de klasse-javadoc
// van TemplateMaterialisationTest.
@SpringBootTest(properties = {"catalogimport.setup-api.enabled=true",
        "spring.datasource.hikari.maximum-pool-size=4"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class TemplateMaterialisationAtomicityTest {

    private static final String USER = MaterialisationFixtures.USER;

    @Autowired
    private ApplicationContext context;
    @Autowired
    private TemplateMaterialisationService materialisation;
    @Autowired
    private ImportDefinitionRepository definitions;
    @Autowired
    private ImportDefinitionRevisionRepository revisions;
    @Autowired
    private ImportFieldMappingRepository fieldMappings;
    @Autowired
    private ImportRecordFilterRepository recordFilters;
    @Autowired
    private ImportRevisionFieldCriticalityRepository fieldCriticalities;
    @Autowired
    private ImportLinkRepository links;
    @Autowired
    private ImportDefinitionBookmarkRepository bookmarks;
    @Autowired
    private ImportDefinitionBookmarkUsageRepository usages;
    @Autowired
    private ImportDefinitionBookmarkValueRepository definitionValues;
    @Autowired
    private ImportLinkBookmarkValueRepository linkValues;

    private MaterialisationFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = MaterialisationFixtures.of(context);
    }

    // --- Fase F laat niets achter ---------------------------------------------------------------------

    /**
     * Een optionele bookmark met een uitdrukkelijk lege waarde op een recordfilter dat met
     * {@code BEGINS_WITH} vergelijkt: de waarde is geldig als bookmarkwaarde (fase D laat {@code ""}
     * toe op een optionele bookmark), maar de afgeleide configuratie zou elk record matchen. De
     * bestaande configuratievalidatie vangt dat in fase F met {@code CONFIG_FILTER_INVALID}.
     */
    @Test
    void leavesNoRowBehindWhenTheDerivedConfigurationIsRefusedInPhaseF() {
        MaterialisationFixtures.Template template = fixtures.template("FASEF");
        fixtures.replaceFilter(template.revision(), FilterOperator.BEGINS_WITH);
        fixtures.declareOn(template.revision(), "CULTUUR", BookmarkValueScope.DEFINITION, false,
                BookmarkDataType.TEXT, 1, BookmarkUsagePlace.RECORD_FILTER_COMPARE_VALUE,
                String.valueOf(MaterialisationFixtures.FILTER_SEQUENCE));
        fixtures.declareOn(template.revision(), "DOELBIBLIOTHEEK", BookmarkValueScope.LINK, true,
                BookmarkDataType.TEXT, 2, BookmarkUsagePlace.LINK_LIBRARY_CODE, "");
        Map<String, Long> before = counts();

        assertThatThrownBy(() -> materialisation.materialise(template.definition().getId(),
                request(template, List.of(new BookmarkValue("CULTUUR", ""),
                        new BookmarkValue("DOELBIBLIOTHEEK", "PSARF020")))))
                .isInstanceOf(BadRequestException.class)
                .extracting(error -> ((BadRequestException) error).getCode())
                .isEqualTo("CONFIG_FILTER_INVALID");

        assertThat(counts()).isEqualTo(before);
        assertThat(definitions.findBySourceOrganisationIdAndCode(template.source().getId(),
                template.definitionCode())).isEmpty();
        assertThat(links.findByCode(template.linkCode())).isEmpty();
    }

    // --- Nooit een duplicaat ---------------------------------------------------------------------------

    @Test
    void refusesASecondMaterialisationWithTheSameDefinitionCodeWithoutCreatingADuplicate() {
        MaterialisationFixtures.Template template = fixtures.template("TWICE");
        fixtures.declareOn(template.revision(), "DOELBIBLIOTHEEK", BookmarkValueScope.LINK, true,
                BookmarkDataType.TEXT, 1, BookmarkUsagePlace.LINK_LIBRARY_CODE, "");

        MaterialisationView first = materialisation.materialise(template.definition().getId(),
                request(template, List.of(new BookmarkValue("DOELBIBLIOTHEEK", "PSARF021"))));
        assertThat(first.definitionCreated()).isTrue();
        Map<String, Long> afterFirst = counts();

        assertThatThrownBy(() -> materialisation.materialise(template.definition().getId(),
                request(template, List.of(new BookmarkValue("DOELBIBLIOTHEEK", "PSARF021")))))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo("DEFINITION_CODE_IN_USE");

        assertThat(counts()).isEqualTo(afterFirst);
        assertThat(definitions.findBySourceOrganisationIdAndCode(template.source().getId(),
                template.definitionCode()).orElseThrow().getId()).isEqualTo(first.definitionId());
    }

    @Test
    void refusesASecondMaterialisationWithTheSameLinkCodeWithoutCreatingADefinition() {
        MaterialisationFixtures.Template template = fixtures.template("LINK2");
        fixtures.declareOn(template.revision(), "DOELBIBLIOTHEEK", BookmarkValueScope.LINK, true,
                BookmarkDataType.TEXT, 1, BookmarkUsagePlace.LINK_LIBRARY_CODE, "");

        materialisation.materialise(template.definition().getId(),
                request(template, List.of(new BookmarkValue("DOELBIBLIOTHEEK", "PSARF022"))));
        Map<String, Long> afterFirst = counts();

        // Andere definitiecode, dezelfde koppelingscode: de koppelingscode is systeembreed uniek.
        assertThatThrownBy(() -> materialisation.materialise(template.definition().getId(),
                new MaterialiseRequest(null, MaterialisationMode.NEW_DEFINITION, null,
                        template.definitionCode() + "-2", "Tweede definitie", null, template.linkCode(),
                        "Tweede koppeling", template.supplier().getCode(), null, null,
                        List.of(new BookmarkValue("DOELBIBLIOTHEEK", "PSARF023")), USER)))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo("LINK_CODE_IN_USE");

        assertThat(counts()).isEqualTo(afterFirst);
        assertThat(definitions.findBySourceOrganisationIdAndCode(template.source().getId(),
                template.definitionCode() + "-2")).isEmpty();
    }

    // --- Helpers ---------------------------------------------------------------------------------------

    /** Elke tabel die een materialisatie aanraakt, zodat een achtergebleven rij zichtbaar wordt. */
    private Map<String, Long> counts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        put(counts, "import_definition", definitions::count);
        put(counts, "import_definition_revision", revisions::count);
        put(counts, "import_field_mapping", fieldMappings::count);
        put(counts, "import_record_filter", recordFilters::count);
        put(counts, "import_revision_field_criticality", fieldCriticalities::count);
        put(counts, "import_link", links::count);
        put(counts, "import_definition_bookmark", bookmarks::count);
        put(counts, "import_definition_bookmark_usage", usages::count);
        put(counts, "import_definition_bookmark_value", definitionValues::count);
        put(counts, "import_link_bookmark_value", linkValues::count);
        return counts;
    }

    private static void put(Map<String, Long> counts, String table, Supplier<Long> count) {
        counts.put(table, count.get());
    }

    private static MaterialiseRequest request(MaterialisationFixtures.Template template,
                                              List<BookmarkValue> values) {
        return new MaterialiseRequest(null, MaterialisationMode.NEW_DEFINITION, null,
                template.definitionCode(), "Afgeleide definitie", null, template.linkCode(),
                "Afgeleide koppeling", template.supplier().getCode(), null, null, values, USER);
    }
}
