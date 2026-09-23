package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.ImportDefinitionBookmarkRepository;
import be.dda.catalogimport.dao.ImportDefinitionBookmarkUsageRepository;
import be.dda.catalogimport.dao.ImportDefinitionBookmarkValueRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportFieldMappingRepository;
import be.dda.catalogimport.dao.ImportLinkBookmarkValueRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.ImportRecordFilterRepository;
import be.dda.catalogimport.domain.BookmarkDataType;
import be.dda.catalogimport.domain.BookmarkUsagePlace;
import be.dda.catalogimport.domain.BookmarkValueScope;
import be.dda.catalogimport.domain.DefinitionUsageType;
import be.dda.catalogimport.domain.FieldValueKind;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionBookmark;
import be.dda.catalogimport.domain.ImportDefinitionBookmarkValue;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportFieldMapping;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.ImportLinkBookmarkValue;
import be.dda.catalogimport.domain.ImportRecordFilter;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.service.TemplateMaterialisationService.BookmarkValue;
import be.dda.catalogimport.service.TemplateMaterialisationService.MaterialisationMode;
import be.dda.catalogimport.service.TemplateMaterialisationService.MaterialisationView;
import be.dda.catalogimport.service.TemplateMaterialisationService.MaterialiseRequest;
import be.dda.catalogimport.service.support.RevisionConfigHashes;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Bouwstap 5c, kernbewijzen van de materialisatie (sjabloon-materialisatie-design.md §10,
 * {@code TemplateMaterialisationTest}): uit één sjabloonrevisie plus ingevulde bookmarkwaarden ontstaat
 * aantoonbaar een werkende definitie + revisie + koppeling.
 *
 * <h2>Wat hier bewezen wordt</h2>
 * <ul>
 *   <li>definitie, revisie, mappings, filters, koppeling en beide soorten waarderijen ontstaan;</li>
 *   <li>de afgeleide revisie is {@code DRAFT} en activeert dus nooit vanzelf (R-MAT, A33);</li>
 *   <li>{@code based_on_definition_id}, {@code based_on_revision_id} en
 *       {@code source_template_revision_id} zijn gevuld — de herkomst blijft afleesbaar;</li>
 *   <li>de bookmarkwaarde staat <b>letterlijk</b> in haar doelkolom (R-MAT-02): de mapping draagt
 *       {@code FIXED_VALUE} met de werkelijke waarde, nooit {@code FieldValueKind.BOOKMARK};</li>
 *   <li>alleen de {@code LINK}-scope declaraties gaan mee naar de afgeleide revisie (R-MAT-03, Q5);</li>
 *   <li>de vier hashes zijn <b>herberekend</b> over de afgeleide revisie zelf (§2);</li>
 *   <li>materialiseren uit een {@code SUPERSEDED} sjabloonrevisie mag en wordt zichtbaar gemeld (Q3).</li>
 * </ul>
 * De HTTP-laag wordt hier met één volledige {@code POST} meegenomen (201 en de vorm van §5); de
 * volledige statuscodematrix hoort bij {@code TemplateMaterialisationHttpTest} in bouwstap 5e.
 *
 * <h2>Waarom alle vier de 5c-testklassen dezelfde annotaties dragen</h2>
 * De lokale PostgreSQL heeft een beperkt aantal verbindingen, en Spring houdt elke afwijkende
 * testconfiguratie als een <b>aparte</b> applicatiecontext in de cache — elk met een eigen
 * verbindingspool. {@code TemplateMaterialisationTest},
 * {@code TemplateMaterialisationValidationTest}, {@code TemplateMaterialisationAtomicityTest} en
 * {@code TemplateGuardTest} dragen daarom letterlijk dezelfde {@code @SpringBootTest}-eigenschappen,
 * {@code @AutoConfigureMockMvc} en {@code @ActiveProfiles}: zo delen ze één context en één pool. De
 * pool is bovendien uitdrukkelijk klein gezet; zonder die twee maatregelen loopt een gerichte testronde
 * vast op {@code remaining connection slots are reserved}.
 */
@SpringBootTest(properties = {"catalogimport.setup-api.enabled=true",
        "spring.datasource.hikari.maximum-pool-size=4"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class TemplateMaterialisationTest {

    private static final String USER = MaterialisationFixtures.USER;

    @Autowired
    private ApplicationContext context;
    @Autowired
    private MockMvc mockMvc;
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

    // --- Happy path -----------------------------------------------------------------------------------

    @Test
    void materialisesATemplateIntoADefinitionARevisionAndALink() {
        MaterialisationFixtures.Template template = fixtures.template("HAPPY");
        declareCanonicalBookmarks(template.revision());

        MaterialisationView view = materialisation.materialise(template.definition().getId(),
                request(template, "PSARF012", null));

        // --- de afgeleide definitie
        assertThat(view.definitionCreated()).isTrue();
        ImportDefinition derived = definitions.findById(view.definitionId()).orElseThrow();
        assertThat(derived.getUsageType()).isEqualTo(DefinitionUsageType.OWN_DEFINITION);
        assertThat(derived.getBasedOnDefinition().getId()).isEqualTo(template.definition().getId());
        // A31: de bronorganisatie wordt geërfd, niet gekozen — het verzoek draagt er geen.
        assertThat(derived.getSourceOrganisation().getId()).isEqualTo(template.source().getId());

        // --- de afgeleide revisie
        ImportDefinitionRevision revision = revisions.findById(view.definitionRevisionId()).orElseThrow();
        assertThat(revision.getRevisionNumber()).isEqualTo(1);
        assertThat(revision.getStatus()).isEqualTo(RevisionStatus.DRAFT);
        assertThat(revision.getBasedOnRevision().getId()).isEqualTo(template.revision().getId());
        assertThat(revision.getChangeReason()).contains(template.definition().getCode());

        // --- de gekopieerde regels, met de bookmarkwaarde er letterlijk in
        List<ImportFieldMapping> mappings = fieldMappings.findByRevisionIdWithTargetField(revision.getId());
        assertThat(mappings).singleElement().satisfies(mapping -> {
            assertThat(mapping.getTargetField().getCode()).isEqualTo(MaterialisationFixtures.MAPPED_FIELD);
            assertThat(mapping.getValueKind()).isEqualTo(FieldValueKind.FIXED_VALUE);
            assertThat(mapping.getFixedValue()).isEqualTo("ACME-001");
        });
        List<ImportRecordFilter> filters =
                recordFilters.findByDefinitionRevisionIdOrderBySequenceNumberAsc(revision.getId());
        assertThat(filters).singleElement().satisfies(filter ->
                assertThat(filter.getCompareValue()).isEqualTo("NL"));
        // Het sjabloon zelf is onaangeroerd gebleven: materialisatie is een kopie, geen verplaatsing.
        assertThat(recordFilters.findByDefinitionRevisionIdOrderBySequenceNumberAsc(
                template.revision().getId())).singleElement()
                .satisfies(filter -> assertThat(filter.getCompareValue())
                        .isEqualTo(MaterialisationFixtures.TEMPLATE_PLACEHOLDER));

        // --- de koppeling
        ImportLink link = links.findById(view.importLinkId()).orElseThrow();
        assertThat(link.getLibraryCode()).isEqualTo("PSARF012");
        assertThat(link.getSupplierOrganisation().getId()).isEqualTo(template.supplier().getId());
        // R-BMK-04: nooit stil afgeleid uit de leverancier van de koppeling.
        assertThat(link.getLibrarySearchSupplierCode()).isNull();

        // --- de waarderijen: snapshot met herkomst (R-MAT-02)
        ImportDefinitionBookmarkValue cultuur = definitionValues
                .findByDefinitionRevisionIdAndBookmarkName(revision.getId(), "CULTUUR").orElseThrow();
        assertThat(cultuur.getValueText()).isEqualTo("NL");
        assertThat(cultuur.getSourceTemplateRevision().getId()).isEqualTo(template.revision().getId());
        assertThat(cultuur.getFilledBy()).isEqualTo(USER);
        assertThat(linkValues.findByImportLinkIdOrderByBookmarkNameAsc(link.getId()))
                .extracting(ImportLinkBookmarkValue::getBookmarkName)
                .containsExactly("DETAILLEVERANCIER", "DOELBIBLIOTHEEK");
        // Een DEFINITION-waarde landt nooit op de koppeling en omgekeerd.
        assertThat(definitionValues.findByDefinitionRevisionIdAndBookmarkName(revision.getId(),
                "DOELBIBLIOTHEEK")).isEmpty();

        // --- R-MAT-03: alleen de LINK-declaraties gaan mee
        List<ImportDefinitionBookmark> copied =
                bookmarks.findByDefinitionRevisionIdOrderBySortOrderAsc(revision.getId());
        // Gesorteerd op sort_order: DOELBIBLIOTHEEK (2) vóór DETAILLEVERANCIER (3); CULTUUR (1) is
        // DEFINITION-scope en gaat bewust niet mee.
        assertThat(copied).extracting(ImportDefinitionBookmark::getName)
                .containsExactly("DOELBIBLIOTHEEK", "DETAILLEVERANCIER");
        assertThat(copied).allSatisfy(bookmark ->
                assertThat(bookmark.getValueScope()).isEqualTo(BookmarkValueScope.LINK));
        assertThat(usages.findByBookmarkId(copied.get(1).getId())).singleElement().satisfies(usage -> {
            assertThat(usage.getPlaceKind()).isEqualTo(BookmarkUsagePlace.FIELD_MAPPING_FIXED_VALUE);
            assertThat(usage.getTargetHint()).isEqualTo(MaterialisationFixtures.MAPPED_FIELD);
        });

        // --- het antwoord
        assertThat(view.templateRevisionStatus()).isEqualTo("ACTIVE");
        assertThat(view.templateRevisionNumber()).isEqualTo(1);
        assertThat(view.definitionRevisionStatus()).isEqualTo("DRAFT");
        assertThat(view.definitionValues()).extracting(value -> value.name() + '=' + value.value())
                .containsExactly("CULTUUR=NL");
        assertThat(view.linkValues()).extracting(value -> value.name() + '=' + value.value())
                .containsExactlyInAnyOrder("DETAILLEVERANCIER=ACME-001", "DOELBIBLIOTHEEK=PSARF012");
        // R-BMK-04: geen LINK_SEARCH_SUPPLIER-bookmark en geen requestveld, dus de kolom blijft leeg —
        // en dat wordt getypeerd gemeld, nooit stil afgeleid (bouwstap 5e).
        assertThat(view.warnings()).extracting(TemplateMaterialisationService.Warning::code)
                .containsExactly("LINK_SEARCH_SUPPLIER_NOT_DERIVED");
    }

    // --- Hashes ---------------------------------------------------------------------------------------

    /**
     * §2: de vier hashes van de afgeleide revisie worden <b>herberekend</b> en beschrijven die revisie
     * zelf. Het canonieke sjabloon van deze klasse raakt geen enkel revisieveld (haar vier plaatsen
     * blijven {@code RECORD_FILTER_COMPARE_VALUE}, {@code LINK_LIBRARY_CODE} en
     * {@code FIELD_MAPPING_FIXED_VALUE}), dus de afgeleide revisie is inhoudelijk gelijk aan het sjabloon
     * en draagt terecht dezelfde hashes — de hash zegt "deze configuratie", niet "deze rij". Wat hier
     * bewezen wordt is dat de hashes uit de canonieke berekening over de <i>eigen</i> veldwaarden komen.
     * De kernproef dat een bookmarkwaarde die wél een revisieveld raakt de hash daadwerkelijk verandert,
     * staat hieronder in {@link #materialisesARevisionIdentityFieldAndChangesTheConfigurationHashes()}
     * (§10, bouwstap 5e: geen van de vier 5c-plaatsen kon dit tonen).
     */
    @Test
    void recomputesTheFourHashesFromTheDerivedRevisionItself() {
        MaterialisationFixtures.Template template = fixtures.template("HASH");
        declareCanonicalBookmarks(template.revision());

        MaterialisationView view = materialisation.materialise(template.definition().getId(),
                request(template, "PSARF013", null));

        ImportDefinitionRevision derived = revisions.findById(view.definitionRevisionId()).orElseThrow();
        ImportDefinitionRevision expected = revisions.findById(view.definitionRevisionId()).orElseThrow();
        RevisionConfigHashes.applyAll(expected);
        assertThat(derived.getAccessConfigHash()).isEqualTo(expected.getAccessConfigHash());
        assertThat(derived.getStructureConfigHash()).isEqualTo(expected.getStructureConfigHash());
        assertThat(derived.getRecordRulesConfigHash()).isEqualTo(expected.getRecordRulesConfigHash());
        assertThat(derived.getCompositeConfigHash()).isEqualTo(expected.getCompositeConfigHash());
        assertThat(derived.getCompositeConfigHash()).hasSize(64);

        // Een inhoudelijk afwijkende revisie draagt nooit dezelfde hash: één veld anders volstaat.
        ImportDefinitionRevision other = revisions.findById(view.definitionRevisionId()).orElseThrow();
        other.setIdentitySupplierField("ANDERE_KOLOM");
        RevisionConfigHashes.applyAll(other);
        assertThat(other.getRecordRulesConfigHash()).isNotEqualTo(derived.getRecordRulesConfigHash());
        assertThat(other.getCompositeConfigHash()).isNotEqualTo(derived.getCompositeConfigHash());
    }

    // --- 5e: REVISION_IDENTITY_FIELD -------------------------------------------------------------------

    /**
     * De belangrijkste van de twee 5e-plaatsen (§11): een bookmark op {@code REVISION_IDENTITY_FIELD}
     * verandert een identiteitsveld van de afgeleide revisie, en dus de aanbiedingsidentiteit én de
     * configuratiehashes (§2). Dit is de kernproef uit §10 ("de hashes verschillen van die van het
     * sjabloon zodra een waarde een revisieveld raakt") end-to-end via de echte materialisatie, in plaats
     * van de synthetische mutatie hierboven.
     */
    @Test
    void materialisesARevisionIdentityFieldAndChangesTheConfigurationHashes() {
        MaterialisationFixtures.Template template = fixtures.template("IDENT");
        declareCanonicalBookmarks(template.revision());
        fixtures.declareOn(template.revision(), "LEVERANCIERSVELD", BookmarkValueScope.DEFINITION, true,
                BookmarkDataType.TEXT, 4, BookmarkUsagePlace.REVISION_IDENTITY_FIELD, "SUPPLIER");

        MaterialisationView view = materialisation.materialise(template.definition().getId(),
                new MaterialiseRequest(null, MaterialisationMode.NEW_DEFINITION, null,
                        template.definitionCode(), "Afgeleide definitie", null, template.linkCode(),
                        "Afgeleide koppeling", template.supplier().getCode(), null, null,
                        List.of(new BookmarkValue("CULTUUR", "NL"),
                                new BookmarkValue("DOELBIBLIOTHEEK", "PSARF020"),
                                new BookmarkValue("DETAILLEVERANCIER", "ACME-001"),
                                new BookmarkValue("LEVERANCIERSVELD", "ANDERE_KOLOM")),
                        USER));

        // --- de waarde staat letterlijk in het revisieveld (R-MAT-02); het sjabloon blijft onaangeroerd
        ImportDefinitionRevision derived = revisions.findById(view.definitionRevisionId()).orElseThrow();
        assertThat(derived.getIdentitySupplierField()).isEqualTo("ANDERE_KOLOM");
        ImportDefinitionRevision templateRevision = revisions.findById(template.revision().getId())
                .orElseThrow();
        assertThat(templateRevision.getIdentitySupplierField()).isEqualTo("LEVERANCIER");

        // --- de kernproef: de hashes verschillen van die van het sjabloon
        assertThat(derived.getRecordRulesConfigHash())
                .isNotEqualTo(templateRevision.getRecordRulesConfigHash());
        assertThat(derived.getCompositeConfigHash())
                .isNotEqualTo(templateRevision.getCompositeConfigHash());
        // De andere twee lagen zijn onveranderd: alleen een recordregel-veld verschilde.
        assertThat(derived.getAccessConfigHash()).isEqualTo(templateRevision.getAccessConfigHash());
        assertThat(derived.getStructureConfigHash()).isEqualTo(templateRevision.getStructureConfigHash());

        // --- de waarderij: snapshot met herkomst (R-MAT-02)
        ImportDefinitionBookmarkValue value = definitionValues
                .findByDefinitionRevisionIdAndBookmarkName(derived.getId(), "LEVERANCIERSVELD").orElseThrow();
        assertThat(value.getValueText()).isEqualTo("ANDERE_KOLOM");
        assertThat(value.getSourceTemplateRevision().getId()).isEqualTo(template.revision().getId());

        assertThat(view.definitionValues()).extracting(v -> v.name() + '=' + v.value())
                .contains("LEVERANCIERSVELD=ANDERE_KOLOM");
    }

    // --- Q3: SUPERSEDED sjabloonrevisie ---------------------------------------------------------------

    @Test
    void materialisesFromASupersededTemplateRevisionAndSaysSoInTheAnswer() {
        MaterialisationFixtures.Template template = fixtures.template("SUPER");
        declareCanonicalBookmarks(template.revision());
        fixtures.supersede(template);

        // De gebruiker kiest de sjabloonversie uitdrukkelijk (§14.16); zonder opgave zou er geen ACTIVE
        // revisie zijn en zou de aanvraag met NO_ACTIVE_TEMPLATE_REVISION stoppen.
        MaterialisationView view = materialisation.materialise(template.definition().getId(),
                request(template, "PSARF014", template.revision().getId()));

        assertThat(view.templateRevisionStatus()).isEqualTo("SUPERSEDED");
        assertThat(view.templateRevisionId()).isEqualTo(template.revision().getId());
        assertThat(revisions.findById(view.definitionRevisionId()).orElseThrow().getStatus())
                .isEqualTo(RevisionStatus.DRAFT);
    }

    // --- De default van een optionele bookmark --------------------------------------------------------

    /**
     * D3: een {@code default_value} telt als invulling. Een optionele bookmark zonder waarde en zonder
     * default laat haar plaats ongemoeid — de sjabloonwaarde blijft staan (de getypeerde waarschuwing
     * {@code OPTIONAL_BOOKMARK_NOT_FILLED} hoort bij bouwstap 5e).
     */
    @Test
    void appliesADefaultValueAndLeavesAnUnfilledOptionalBookmarkAlone() {
        MaterialisationFixtures.Template template = fixtures.template("DEFAULT");
        ImportDefinitionBookmark cultuur = fixtures.declareOn(template.revision(), "CULTUUR",
                BookmarkValueScope.DEFINITION, false, BookmarkDataType.TEXT, 1,
                BookmarkUsagePlace.RECORD_FILTER_COMPARE_VALUE, "1");
        fixtures.withDefault(cultuur, "FR");
        fixtures.declareOn(template.revision(), "DETAILLEVERANCIER", BookmarkValueScope.DEFINITION, false,
                BookmarkDataType.TEXT, 2, BookmarkUsagePlace.FIELD_MAPPING_FIXED_VALUE,
                MaterialisationFixtures.MAPPED_FIELD);

        MaterialisationView view = materialisation.materialise(template.definition().getId(),
                new MaterialiseRequest(null, MaterialisationMode.NEW_DEFINITION, null,
                        template.definitionCode(), "Afgeleide definitie", null, template.linkCode(),
                        "Afgeleide koppeling", template.supplier().getCode(), "PSARF015", null, List.of(),
                        USER));

        long revisionId = view.definitionRevisionId();
        assertThat(recordFilters.findByDefinitionRevisionIdOrderBySequenceNumberAsc(revisionId))
                .singleElement().satisfies(filter -> assertThat(filter.getCompareValue()).isEqualTo("FR"));
        assertThat(fieldMappings.findByRevisionIdWithTargetField(revisionId)).singleElement()
                .satisfies(mapping -> assertThat(mapping.getFixedValue())
                        .isEqualTo(MaterialisationFixtures.TEMPLATE_PLACEHOLDER));
        assertThat(definitionValues.findByDefinitionRevisionIdAndBookmarkName(revisionId, "CULTUUR"))
                .isPresent();
        assertThat(definitionValues.findByDefinitionRevisionIdAndBookmarkName(revisionId,
                "DETAILLEVERANCIER")).isEmpty();
    }

    // --- HTTP ------------------------------------------------------------------------------------------

    @Test
    void returns201AndTheContractShapeOverHttp() throws Exception {
        MaterialisationFixtures.Template template = fixtures.template("HTTP");
        declareCanonicalBookmarks(template.revision());

        String body = """
                {"mode":"NEW_DEFINITION","definitionCode":"%s","definitionName":"Afgeleide definitie",
                 "linkCode":"%s","linkName":"Afgeleide koppeling","supplierOrganisationCode":"%s",
                 "bookmarkValues":[{"name":"CULTUUR","value":"NL"},
                                   {"name":"DOELBIBLIOTHEEK","value":"PSARF016"},
                                   {"name":"DETAILLEVERANCIER","value":"ACME-001"}],
                 "materialisedBy":"%s"}""".formatted(template.definitionCode(), template.linkCode(),
                template.supplier().getCode(), USER);

        mockMvc.perform(post("/api/catalog-import/templates/{id}/materialisations",
                        template.definition().getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.definitionCreated").value(true))
                .andExpect(jsonPath("$.definitionCode").value(template.definitionCode()))
                .andExpect(jsonPath("$.definitionRevisionNumber").value(1))
                .andExpect(jsonPath("$.definitionRevisionStatus").value("DRAFT"))
                .andExpect(jsonPath("$.templateRevisionStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.importLinkCode").value(template.linkCode()))
                .andExpect(jsonPath("$.definitionValues[0].name").value("CULTUUR"))
                .andExpect(jsonPath("$.linkValues.length()").value(2))
                .andExpect(jsonPath("$.warnings.length()").value(1))
                .andExpect(jsonPath("$.warnings[0].code").value("LINK_SEARCH_SUPPLIER_NOT_DERIVED"));
    }

    // --- Helpers ---------------------------------------------------------------------------------------

    /**
     * Het canonieke sjabloon van §11 (bouwstap 5c): één verplichte {@code CULTUUR} op een recordfilter,
     * één {@code DOELBIBLIOTHEEK} op de koppeling, plus het {@code DETAILLEVERANCIER}-geval van vraag Q2
     * (een {@code LINK}-scope bookmark op een revisieniveau-plaats, uitdrukkelijk toegestaan).
     */
    private void declareCanonicalBookmarks(ImportDefinitionRevision revision) {
        fixtures.declareOn(revision, "CULTUUR", BookmarkValueScope.DEFINITION, true, BookmarkDataType.ENUM,
                1, BookmarkUsagePlace.RECORD_FILTER_COMPARE_VALUE,
                String.valueOf(MaterialisationFixtures.FILTER_SEQUENCE), "NL,FR,EN", null);
        fixtures.declareOn(revision, "DOELBIBLIOTHEEK", BookmarkValueScope.LINK, true,
                BookmarkDataType.TEXT, 2, BookmarkUsagePlace.LINK_LIBRARY_CODE, "");
        fixtures.declareOn(revision, "DETAILLEVERANCIER", BookmarkValueScope.LINK, true,
                BookmarkDataType.TEXT, 3, BookmarkUsagePlace.FIELD_MAPPING_FIXED_VALUE,
                MaterialisationFixtures.MAPPED_FIELD);
    }

    private static MaterialiseRequest request(MaterialisationFixtures.Template template, String libraryCode,
                                              Long templateRevisionId) {
        return new MaterialiseRequest(templateRevisionId, MaterialisationMode.NEW_DEFINITION, null,
                template.definitionCode(), "Afgeleide definitie", null, template.linkCode(),
                "Afgeleide koppeling", template.supplier().getCode(), null, null,
                List.of(new BookmarkValue("CULTUUR", "NL"),
                        new BookmarkValue("DOELBIBLIOTHEEK", libraryCode),
                        new BookmarkValue("DETAILLEVERANCIER", "ACME-001")),
                USER);
    }
}
