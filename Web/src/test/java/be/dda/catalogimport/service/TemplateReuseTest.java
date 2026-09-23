package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.ImportDefinitionBookmarkValueRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportFieldMappingRepository;
import be.dda.catalogimport.dao.ImportLinkBookmarkValueRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.ImportRecordFilterRepository;
import be.dda.catalogimport.domain.BookmarkDataType;
import be.dda.catalogimport.domain.BookmarkUsagePlace;
import be.dda.catalogimport.domain.BookmarkValueScope;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.ImportLinkBookmarkValue;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.service.TemplateMaterialisationService.BookmarkValue;
import be.dda.catalogimport.service.TemplateMaterialisationService.MaterialisationMode;
import be.dda.catalogimport.service.TemplateMaterialisationService.MaterialisationView;
import be.dda.catalogimport.service.TemplateMaterialisationService.MaterialisedDefinitionView;
import be.dda.catalogimport.service.TemplateMaterialisationService.MaterialiseRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Bouwstap 5d: hergebruik van een al gematerialiseerde definitie
 * (sjabloon-materialisatie-design.md §6 en §10, {@code TemplateReuseTest}).
 *
 * <h2>Wat hier bewezen wordt</h2>
 * <ul>
 *   <li>hergebruik maakt <b>geen</b> tweede definitie en <b>geen</b> tweede revisie: de bestaande
 *       revisie blijft byte-identiek (zelfde hashes, zelfde mapping- en filterwaarden, zelfde
 *       {@code DEFINITION}-snapshot) en alleen koppeling + {@code LINK}-waarden komen erbij;</li>
 *   <li>een {@code DEFINITION}-scope waarde wordt bij hergebruik geweigerd — ze zou de configuratie van
 *       elke andere leverancier op die definitie mee veranderen;</li>
 *   <li>een definitie die niet uit dit sjabloon komt (en een sjabloon zelf) wordt geweigerd;</li>
 *   <li>een definitie die op een andere sjabloonversie bevroren is, wordt geweigerd in plaats van stil
 *       hergebruikt — dat zou de uitgestelde sjabloonversievergelijking half en onzichtbaar doen;</li>
 *   <li><b>de deelbaarheidsregel (vraag Q2).</b> Een {@code LINK}-scope bookmark op een
 *       revisieniveau-plaats mág bij materialisatie, maar maakt de definitie niet deelbaar: 409
 *       {@code DEFINITION_NOT_SHAREABLE} met vermelding van de bookmark. Dat is het
 *       {@code DETAILLEVERANCIER}-geval, waarvan de waarde mee de aanbiedingsidentiteit bepaalt;</li>
 *   <li>{@code GET /templates/{id}/materialisations} toont dezelfde deelbaarheid, met een
 *       <b>deterministische</b> sortering.</li>
 * </ul>
 *
 * <h2>Waarom deze klasse dezelfde annotaties draagt als de 5c-testklassen</h2>
 * Letterlijk gelijk aan {@code TemplateMaterialisationTest}: Spring houdt elke afwijkende
 * testconfiguratie als een <b>aparte</b> applicatiecontext in de cache, elk met een eigen
 * verbindingspool, en de lokale PostgreSQL heeft weinig vrije verbindingen. Zo delen alle
 * materialisatietests één context en één (uitdrukkelijk kleine) pool; zonder die twee maatregelen loopt
 * een gerichte testronde vast op {@code remaining connection slots are reserved}.
 */
@SpringBootTest(properties = {"catalogimport.setup-api.enabled=true",
        "spring.datasource.hikari.maximum-pool-size=4"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class TemplateReuseTest {

    private static final String USER = MaterialisationFixtures.USER;

    @Autowired
    private ApplicationContext context;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TemplateMaterialisationService materialisation;
    @Autowired
    private ImportDefinitionRevisionRepository revisions;
    @Autowired
    private ImportFieldMappingRepository fieldMappings;
    @Autowired
    private ImportRecordFilterRepository recordFilters;
    @Autowired
    private ImportLinkRepository links;
    @Autowired
    private ImportDefinitionBookmarkValueRepository definitionValues;
    @Autowired
    private ImportLinkBookmarkValueRepository linkValues;

    private MaterialisationFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = MaterialisationFixtures.of(context);
    }

    // --- Het hoofdgeval: een tweede leverancier op dezelfde definitie ---------------------------------

    @Test
    void reusesADefinitionWithoutCreatingASecondRevision() {
        MaterialisationFixtures.Template template = fixtures.template("REUSE");
        declareShareableBookmarks(template.revision());
        MaterialisationView first = materialisation.materialise(template.definition().getId(),
                newRequest(template, template.supplier(), template.linkCode(), "PSARF301"));

        // De toestand vóór het hergebruik, om te kunnen bewijzen dat ze niet beweegt.
        ImportDefinitionRevision before = revisions.findById(first.definitionRevisionId()).orElseThrow();
        String compositeHash = before.getCompositeConfigHash();
        String recordRulesHash = before.getRecordRulesConfigHash();
        String structureHash = before.getStructureConfigHash();
        String accessHash = before.getAccessConfigHash();
        String filterValue = recordFilters
                .findByDefinitionRevisionIdOrderBySequenceNumberAsc(before.getId()).get(0).getCompareValue();
        String mappingValue = fieldMappings.findByRevisionIdWithTargetField(before.getId()).get(0)
                .getFixedValue();

        SourceOrganisation second = fixtures.extraSupplier(template, "B");
        MaterialisationView reused = materialisation.materialise(template.definition().getId(),
                reuseRequest(first.definitionId(), second, template.linkCode("B"),
                        List.of(new BookmarkValue("DOELBIBLIOTHEEK", "PSARF302"))));

        // --- geen tweede definitie, geen tweede revisie
        assertThat(reused.definitionCreated()).isFalse();
        assertThat(reused.definitionId()).isEqualTo(first.definitionId());
        assertThat(reused.definitionRevisionId()).isEqualTo(first.definitionRevisionId());
        assertThat(revisions.findByImportDefinitionIdOrderByRevisionNumberDesc(first.definitionId()))
                .hasSize(1);

        // --- de bestaande revisie is byte-identiek gebleven
        ImportDefinitionRevision after = revisions.findById(first.definitionRevisionId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(RevisionStatus.DRAFT);
        assertThat(after.getCompositeConfigHash()).isEqualTo(compositeHash);
        assertThat(after.getRecordRulesConfigHash()).isEqualTo(recordRulesHash);
        assertThat(after.getStructureConfigHash()).isEqualTo(structureHash);
        assertThat(after.getAccessConfigHash()).isEqualTo(accessHash);
        assertThat(recordFilters.findByDefinitionRevisionIdOrderBySequenceNumberAsc(after.getId()))
                .singleElement()
                .satisfies(filter -> assertThat(filter.getCompareValue()).isEqualTo(filterValue));
        assertThat(fieldMappings.findByRevisionIdWithTargetField(after.getId())).singleElement()
                .satisfies(mapping -> assertThat(mapping.getFixedValue()).isEqualTo(mappingValue));
        // De DEFINITION-snapshot van de eerste materialisatie is niet aangeraakt en er is er geen bij.
        assertThat(definitionValues.findByDefinitionRevisionIdAndBookmarkName(after.getId(), "CULTUUR"))
                .hasValueSatisfying(value -> assertThat(value.getValueText()).isEqualTo("NL"));
        assertThat(definitionValues.findByDefinitionRevisionIdAndBookmarkName(after.getId(),
                "DOELBIBLIOTHEEK")).isEmpty();

        // --- wat er wél bij komt: één koppeling met haar LINK-waarden
        assertThat(links.findByImportDefinitionId(first.definitionId()))
                .extracting(ImportLink::getCode)
                .containsExactlyInAnyOrder(template.linkCode(), template.linkCode("B"));
        ImportLink link = links.findById(reused.importLinkId()).orElseThrow();
        assertThat(link.getLibraryCode()).isEqualTo("PSARF302");
        assertThat(link.getSupplierOrganisation().getId()).isEqualTo(second.getId());
        // R-BMK-04: nooit stil afgeleid uit de leverancier van de koppeling.
        assertThat(link.getLibrarySearchSupplierCode()).isNull();
        assertThat(linkValues.findByImportLinkIdOrderByBookmarkNameAsc(link.getId()))
                .extracting(ImportLinkBookmarkValue::getBookmarkName, ImportLinkBookmarkValue::getValueText)
                .containsExactly(tuple("DOELBIBLIOTHEEK", "PSARF302"));

        // --- het antwoord: geen enkele toegepaste DEFINITION-waarde, want er is er geen toegepast
        assertThat(reused.definitionValues()).isEmpty();
        assertThat(reused.linkValues()).extracting(value -> value.name() + '=' + value.value())
                .containsExactly("DOELBIBLIOTHEEK=PSARF302");
        assertThat(reused.warnings()).isEmpty();
        assertThat(reused.templateRevisionId()).isEqualTo(template.revision().getId());
        assertThat(reused.definitionRevisionStatus()).isEqualTo("DRAFT");
    }

    // --- §6 punt 4: geen DEFINITION-waarden ------------------------------------------------------------

    @Test
    void refusesADefinitionScopeValueOnReuse() {
        MaterialisationFixtures.Template template = fixtures.template("REUSEDEF");
        declareShareableBookmarks(template.revision());
        MaterialisationView first = materialisation.materialise(template.definition().getId(),
                newRequest(template, template.supplier(), template.linkCode(), "PSARF303"));
        SourceOrganisation second = fixtures.extraSupplier(template, "B");

        assertThatThrownBy(() -> materialisation.materialise(template.definition().getId(),
                reuseRequest(first.definitionId(), second, template.linkCode("B"),
                        List.of(new BookmarkValue("CULTUUR", "FR"),
                                new BookmarkValue("DOELBIBLIOTHEEK", "PSARF304")))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("CULTUUR")
                .extracting(error -> ((BadRequestException) error).getCode())
                .isEqualTo("DEFINITION_SCOPE_VALUE_NOT_ALLOWED_ON_REUSE");

        // Niets geschreven: de revisie draagt nog altijd de oorspronkelijke waarde.
        assertThat(recordFilters.findByDefinitionRevisionIdOrderBySequenceNumberAsc(
                first.definitionRevisionId())).singleElement()
                .satisfies(filter -> assertThat(filter.getCompareValue()).isEqualTo("NL"));
        assertThat(links.findByCode(template.linkCode("B"))).isEmpty();
    }

    // --- §6 punten 1 en 2: de definitie moet uit dit sjabloon komen ------------------------------------

    @Test
    void refusesADefinitionThatDoesNotComeFromThisTemplate() {
        MaterialisationFixtures.Template source = fixtures.template("FOREIGNA");
        declareShareableBookmarks(source.revision());
        MaterialisationView derived = materialisation.materialise(source.definition().getId(),
                newRequest(source, source.supplier(), source.linkCode(), "PSARF305"));

        MaterialisationFixtures.Template other = fixtures.template("FOREIGNB");
        declareShareableBookmarks(other.revision());

        // Een definitie van een ánder sjabloon: geen sjabloonwerk, daar bestaat POST /setup/links voor.
        assertThatThrownBy(() -> materialisation.materialise(other.definition().getId(),
                reuseRequest(derived.definitionId(), other.supplier(), other.linkCode(),
                        List.of(new BookmarkValue("DOELBIBLIOTHEEK", "PSARF306")))))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo("DEFINITION_NOT_FROM_TEMPLATE");

        // Het sjabloon zelf: een sjabloon krijgt nooit een koppeling (R-SHR-01), en dat wordt hier met
        // een leesbaar antwoord geweigerd in plaats van pas op de databaseguard 006-5 te stranden.
        assertThatThrownBy(() -> materialisation.materialise(other.definition().getId(),
                reuseRequest(other.definition().getId(), other.supplier(), other.linkCode(),
                        List.of(new BookmarkValue("DOELBIBLIOTHEEK", "PSARF307")))))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo("DEFINITION_NOT_FROM_TEMPLATE");

        // Een onbestaande definitie is een 404, geen 409.
        assertThatThrownBy(() -> materialisation.materialise(other.definition().getId(),
                reuseRequest(-1L, other.supplier(), other.linkCode(),
                        List.of(new BookmarkValue("DOELBIBLIOTHEEK", "PSARF308")))))
                .isInstanceOf(NotFoundException.class)
                .extracting(error -> ((NotFoundException) error).getCode())
                .isEqualTo("DEFINITION_NOT_FOUND");
    }

    // --- §6 punt 3: dezelfde sjabloonversie ------------------------------------------------------------

    @Test
    void refusesADefinitionThatIsFrozenOnAnotherTemplateRevision() {
        MaterialisationFixtures.Template template = fixtures.template("MISMATCH");
        declareShareableBookmarks(template.revision());
        MaterialisationView first = materialisation.materialise(template.definition().getId(),
                newRequest(template, template.supplier(), template.linkCode(), "PSARF309"));

        // Het sjabloon krijgt een nieuwere versie; de bestaande definitie blijft op versie 1 bevroren.
        ImportDefinitionRevision secondRevision = fixtures.nextRevision(template);
        declareShareableBookmarks(secondRevision);
        SourceOrganisation second = fixtures.extraSupplier(template, "B");

        // Zonder opgave kiest de aanvraag de ACTIVE sjabloonversie — nu versie 2.
        assertThatThrownBy(() -> materialisation.materialise(template.definition().getId(),
                reuseRequest(first.definitionId(), second, template.linkCode("B"),
                        List.of(new BookmarkValue("DOELBIBLIOTHEEK", "PSARF310")))))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo("TEMPLATE_REVISION_MISMATCH_ON_REUSE");

        // Met de sjabloonversie waarop die definitie wél bevroren is, slaagt hetzelfde verzoek.
        MaterialiseRequest onRevisionOne = new MaterialiseRequest(template.revision().getId(),
                MaterialisationMode.REUSE_DEFINITION, first.definitionId(), null, null, null,
                template.linkCode("B"), "Tweede koppeling", second.getCode(), null, null,
                List.of(new BookmarkValue("DOELBIBLIOTHEEK", "PSARF310")), USER);
        MaterialisationView reused = materialisation.materialise(template.definition().getId(),
                onRevisionOne);
        assertThat(reused.definitionCreated()).isFalse();
        assertThat(reused.templateRevisionStatus()).isEqualTo("SUPERSEDED");
        assertThat(secondRevision.getRevisionNumber()).isEqualTo(2);
    }

    // --- §6 punt 5 (vraag Q2): deelbaarheid ------------------------------------------------------------

    /**
     * Het {@code DETAILLEVERANCIER}-geval. Materialiseren mág (de mens heeft Q2 zo beslist), maar de
     * uitkomst is niet deelbaar: die waarde landt via {@code FIELD_MAPPING_FIXED_VALUE} op een
     * revisieveld en bepaalt mee de aanbiedingsidentiteit. Twee leveranciers op één zo'n definitie zou
     * betekenen dat de aanbiedingen van de een de identiteit van de ander dragen.
     */
    @Test
    void allowsALinkScopeBookmarkOnARevisionPlaceButRefusesToShareTheResult() {
        MaterialisationFixtures.Template template = fixtures.template("SHARE");
        declareShareableBookmarks(template.revision());
        fixtures.declareOn(template.revision(), "DETAILLEVERANCIER", BookmarkValueScope.LINK, true,
                BookmarkDataType.TEXT, 3, BookmarkUsagePlace.FIELD_MAPPING_FIXED_VALUE,
                MaterialisationFixtures.MAPPED_FIELD);

        MaterialisationView first = materialisation.materialise(template.definition().getId(),
                new MaterialiseRequest(null, MaterialisationMode.NEW_DEFINITION, null,
                        template.definitionCode(), "Afgeleide definitie", null, template.linkCode(),
                        "Afgeleide koppeling", template.supplier().getCode(), null, null,
                        List.of(new BookmarkValue("CULTUUR", "NL"),
                                new BookmarkValue("DOELBIBLIOTHEEK", "PSARF311"),
                                new BookmarkValue("DETAILLEVERANCIER", "ACME-001")),
                        USER));
        assertThat(first.definitionCreated()).isTrue();

        SourceOrganisation second = fixtures.extraSupplier(template, "B");
        assertThatThrownBy(() -> materialisation.materialise(template.definition().getId(),
                reuseRequest(first.definitionId(), second, template.linkCode("B"),
                        List.of(new BookmarkValue("DOELBIBLIOTHEEK", "PSARF312"),
                                new BookmarkValue("DETAILLEVERANCIER", "ACME-002")))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("DETAILLEVERANCIER")
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo("DEFINITION_NOT_SHAREABLE");
        assertThat(links.findByCode(template.linkCode("B"))).isEmpty();

        // De keuzelijst meldt dezelfde weigering vooraf, met dezelfde bookmark.
        assertThat(materialisation.listMaterialisations(template.definition().getId(), null, null).content())
                .singleElement().satisfies(row -> {
                    assertThat(row.shareable()).isFalse();
                    assertThat(row.blockingBookmarkName()).isEqualTo("DETAILLEVERANCIER");
                });
    }

    // --- Dubbele invoer: dezelfde leverancier twee keer op dezelfde definitie --------------------------

    @Test
    void refusesADuplicateLinkCodeAndADuplicateSupplierLibraryScope() {
        MaterialisationFixtures.Template template = fixtures.template("DOUBLE");
        declareShareableBookmarks(template.revision());
        MaterialisationView first = materialisation.materialise(template.definition().getId(),
                newRequest(template, template.supplier(), template.linkCode(), "PSARF313"));

        assertThatThrownBy(() -> materialisation.materialise(template.definition().getId(),
                reuseRequest(first.definitionId(), template.supplier(), template.linkCode(),
                        List.of(new BookmarkValue("DOELBIBLIOTHEEK", "PSARF314")))))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo("LINK_CODE_IN_USE");

        // Zelfde leverancier én zelfde bibliotheek: uk_import_link_scope. Dit is een gewone
        // gebruikersfout, geen race, en hoort dus een leesbaar 409 te geven in plaats van een
        // databasefout.
        assertThatThrownBy(() -> materialisation.materialise(template.definition().getId(),
                reuseRequest(first.definitionId(), template.supplier(), template.linkCode("B"),
                        List.of(new BookmarkValue("DOELBIBLIOTHEEK", "PSARF313")))))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo("LINK_SCOPE_IN_USE");

        assertThat(links.findByImportDefinitionId(first.definitionId())).hasSize(1);
    }

    // --- De keuzelijst ----------------------------------------------------------------------------------

    @Test
    void listsWhatCameFromThisTemplateInADeterministicOrder() {
        MaterialisationFixtures.Template template = fixtures.template("LIST");
        declareShareableBookmarks(template.revision());
        // Bewust in omgekeerde alfabetische volgorde aangemaakt, zodat de sortering iets te bewijzen
        // heeft: de lijst mag niet van de invoegvolgorde afhangen.
        MaterialisationView second = materialisation.materialise(template.definition().getId(),
                newRequest(template, template.supplier(), template.linkCode("-B"), "PSARF316",
                        template.definitionCode() + "-B"));
        SourceOrganisation otherSupplier = fixtures.extraSupplier(template, "C");
        MaterialisationView firstAlphabetically = materialisation.materialise(template.definition().getId(),
                newRequest(template, otherSupplier, template.linkCode("-A"), "PSARF315",
                        template.definitionCode() + "-A"));

        List<MaterialisedDefinitionView> rows =
                materialisation.listMaterialisations(template.definition().getId(), null, null).content();

        assertThat(rows).extracting(MaterialisedDefinitionView::definitionId)
                .containsExactly(firstAlphabetically.definitionId(), second.definitionId());
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.templateRevisionId()).isEqualTo(template.revision().getId());
            assertThat(row.templateRevisionNumber()).isEqualTo(1);
            assertThat(row.templateRevisionStatus()).isEqualTo("ACTIVE");
            assertThat(row.definitionRevisionNumber()).isEqualTo(1);
            assertThat(row.definitionRevisionStatus()).isEqualTo("DRAFT");
            assertThat(row.importLinkCount()).isEqualTo(1);
            assertThat(row.shareable()).isTrue();
            assertThat(row.blockingBookmarkName()).isNull();
        });

        // Na een hergebruik telt de gedeelde definitie twee koppelingen — precies wat het scherm moet
        // tonen om "deze definitie wordt al gedeeld" zichtbaar te maken.
        SourceOrganisation third = fixtures.extraSupplier(template, "D");
        materialisation.materialise(template.definition().getId(),
                reuseRequest(firstAlphabetically.definitionId(), third, template.linkCode("-A2"),
                        List.of(new BookmarkValue("DOELBIBLIOTHEEK", "PSARF317"))));
        assertThat(materialisation.listMaterialisations(template.definition().getId(), null, null).content())
                .first().extracting(MaterialisedDefinitionView::importLinkCount).isEqualTo(2L);
    }

    @Test
    void returnsTheChoiceListOverHttp() throws Exception {
        MaterialisationFixtures.Template template = fixtures.template("LISTHTTP");
        declareShareableBookmarks(template.revision());
        materialisation.materialise(template.definition().getId(),
                newRequest(template, template.supplier(), template.linkCode(), "PSARF318"));

        mockMvc.perform(get("/api/catalog-import/templates/{id}/materialisations",
                        template.definition().getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].definitionCode").value(template.definitionCode()))
                .andExpect(jsonPath("$.content[0].templateRevisionNumber").value(1))
                .andExpect(jsonPath("$.content[0].importLinkCount").value(1))
                .andExpect(jsonPath("$.content[0].shareable").value(true))
                .andExpect(jsonPath("$.content[0].blockingBookmarkName").doesNotExist());
    }

    // --- Helpers -----------------------------------------------------------------------------------------

    /**
     * Een sjabloon dat een <b>deelbare</b> definitie oplevert: de enige waarde die per leverancier
     * verschilt ({@code DOELBIBLIOTHEEK}) landt op de koppeling, niet in de revisie.
     */
    private void declareShareableBookmarks(ImportDefinitionRevision revision) {
        fixtures.declareOn(revision, "CULTUUR", BookmarkValueScope.DEFINITION, true, BookmarkDataType.ENUM,
                1, BookmarkUsagePlace.RECORD_FILTER_COMPARE_VALUE,
                String.valueOf(MaterialisationFixtures.FILTER_SEQUENCE), "NL,FR,EN", null);
        fixtures.declareOn(revision, "DOELBIBLIOTHEEK", BookmarkValueScope.LINK, true, BookmarkDataType.TEXT,
                2, BookmarkUsagePlace.LINK_LIBRARY_CODE, "");
    }

    private MaterialiseRequest newRequest(MaterialisationFixtures.Template template,
                                          SourceOrganisation supplier, String linkCode, String libraryCode) {
        return newRequest(template, supplier, linkCode, libraryCode, template.definitionCode());
    }

    private MaterialiseRequest newRequest(MaterialisationFixtures.Template template,
                                          SourceOrganisation supplier, String linkCode, String libraryCode,
                                          String definitionCode) {
        return new MaterialiseRequest(null, MaterialisationMode.NEW_DEFINITION, null, definitionCode,
                "Afgeleide definitie", null, linkCode, "Afgeleide koppeling", supplier.getCode(), null, null,
                List.of(new BookmarkValue("CULTUUR", "NL"),
                        new BookmarkValue("DOELBIBLIOTHEEK", libraryCode)),
                USER);
    }

    /**
     * Hergebruik draagt bewust <b>geen</b> {@code definitionCode}, {@code definitionName} of
     * {@code changeReason}: er ontstaat geen definitie en geen revisie waar die op zouden slaan.
     */
    private MaterialiseRequest reuseRequest(long reuseDefinitionId, SourceOrganisation supplier,
                                            String linkCode, List<BookmarkValue> values) {
        return new MaterialiseRequest(null, MaterialisationMode.REUSE_DEFINITION, reuseDefinitionId, null,
                null, null, linkCode, "Tweede koppeling", supplier.getCode(), null, null, values, USER);
    }
}
