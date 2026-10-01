package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.ImportDefinitionBookmarkRepository;
import be.dda.catalogimport.dao.ImportDefinitionBookmarkUsageRepository;
import be.dda.catalogimport.dao.ImportDefinitionBookmarkValueRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportFieldMappingRepository;
import be.dda.catalogimport.dao.ImportRecordFilterRepository;
import be.dda.catalogimport.dao.ImportRevisionFieldCriticalityRepository;
import be.dda.catalogimport.domain.BookmarkDataType;
import be.dda.catalogimport.domain.BookmarkUsagePlace;
import be.dda.catalogimport.domain.BookmarkValueScope;
import be.dda.catalogimport.domain.Criticality;
import be.dda.catalogimport.domain.DefinitionUsageType;
import be.dda.catalogimport.domain.ImportDefinitionBookmark;
import be.dda.catalogimport.domain.ImportDefinitionBookmarkValue;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportRevisionFieldCriticality;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.service.SetupService.RevisionView;
import be.dda.catalogimport.service.TemplateMaterialisationService.BookmarkValue;
import be.dda.catalogimport.service.TemplateMaterialisationService.MaterialisationMode;
import be.dda.catalogimport.service.TemplateMaterialisationService.MaterialisationView;
import be.dda.catalogimport.service.TemplateMaterialisationService.MaterialisedDefinitionView;
import be.dda.catalogimport.service.TemplateMaterialisationService.MaterialiseRequest;
import be.dda.catalogimport.service.support.RevisionConfigHashes;
import be.dda.catalogimport.testsupport.TestActors;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Bouwstap S1-X-2: de opvolgrevisie ({@code docs/design/revision-successor-design.md} §1, §2, §3, §6
 * endpoint E2, §10 O1/O2).
 *
 * <h2>Wat hier bewezen wordt</h2>
 * <ul>
 *   <li><b>De kloon is byte-identiek</b> (§1, R-REV-X1): alle vier configuratiehashes, alle
 *       identiteits-, prijs- en structuurvelden gelijk aan de bron; status {@code DRAFT};
 *       {@code approved_*} leeg; {@code based_on_revision_id} naar de bron; revisienummer = bron + 1.</li>
 *   <li><b>Alle vijf configuratie-kindtabellen gaan mee</b> (§2): mappings, filters, kritiek-overrules,
 *       bookmarkdeclaraties (<b>alle scopes</b>, dus ook {@code DEFINITION}) en de
 *       bookmarkwaarden — die laatste met {@code source_template_revision_id} letterlijk mee, en met de
 *       klonende gebruiker als invuller.</li>
 *   <li><b>O2:</b> klonen mag vanaf {@code ACTIVE} én {@code SUPERSEDED}; een {@code DRAFT} of andere
 *       status geeft 409 {@code REVISION_NOT_CLONEABLE} en schrijft niets.</li>
 *   <li><b>O1:</b> een tweede opvolger terwijl er al een DRAFT openstaat geeft 409
 *       {@code REVISION_DRAFT_ALREADY_EXISTS} — en noemt het bestaande revisienummer.</li>
 *   <li><b>{@code changeReason} is verplicht</b>: 400 {@code CHANGE_REASON_REQUIRED} (ontdekking §9).</li>
 *   <li><b>Activeren</b> van de opvolger zet de vorige {@code ACTIVE} op {@code SUPERSEDED}, en bumpt
 *       per laag het versienummer alleen wanneer die laaghash veranderde (3b). Een gematerialiseerde
 *       revisie 1, wiens herkomstrevisie bij een <i>andere</i> definitie hoort, blijft onaangeroerd.</li>
 *   <li><b>3a:</b> een botsing op {@code uk_import_definition_revision_active} wordt 409
 *       {@code REVISION_ACTIVATION_CONFLICT} in plaats van een 500 — met een <i>gesimuleerde</i>
 *       databasetoestand in plaats van een echte race, zie
 *       {@link #translatesTheActiveMarkerCollisionIntoAConflictAndWritesNothing()}.</li>
 *   <li><b>De sjabloonherkomst blijft intact</b> ná twee opvolgrevisies én nadat het sjabloon zelf een
 *       opvolger kreeg: {@code listMaterialisations} en het hergebruikspad vinden nog steeds dezelfde
 *       materialisatierevisie (§1, "Belangrijke verificatie").</li>
 *   <li>HTTP: E2 geeft 201 met dezelfde revisieweergave als {@code createRevision}, en 404/409/400 met
 *       een stabiele {@code code}.</li>
 * </ul>
 * De twee rechtenbewijzen staan bewust elders, in hun eigen bestaande context: 403 zonder
 * {@code MANAGE} in {@code PermissionWriteEndpointsHttpTest}, bereikbaar zonder
 * {@code catalogimport.setup-api.enabled} (sinds NT-3) in {@code SetupApiDisabledTest}.
 *
 * <h2>Waarom deze klasse dezelfde annotaties draagt als de materialisatietests</h2>
 * Letterlijk gelijk aan {@code TemplateMaterialisationTest}/{@code TemplateReuseTest}: Spring houdt elke
 * afwijkende testconfiguratie als een aparte applicatiecontext met een eigen verbindingspool, en de
 * lokale PostgreSQL heeft weinig vrije verbindingen. Zo deelt deze klasse hun context en hun
 * uitdrukkelijk kleine pool; ze gebruikt ook dezelfde {@code MaterialisationFixtures}.
 */
@SpringBootTest(properties = {"catalogimport.setup-api.enabled=true",
        "spring.datasource.hikari.maximum-pool-size=4"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class RevisionSuccessorTest {

    private static final String USER = MaterialisationFixtures.USER;
    /** Hetzelfde subject als {@code TestActors} zet, zodat de HTTP- en servicepaden gelijk tekenen. */
    private static final String SUBJECT = "test-sub-" + MaterialisationFixtures.USER;
    private static final String SETUP = "/api/catalog-import/setup";

    @Autowired
    private ApplicationContext context;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RevisionSuccessorService successors;
    @Autowired
    private SetupService setup;
    @Autowired
    private TemplateMaterialisationService materialisation;
    @Autowired
    private ImportDefinitionRevisionRepository revisions;
    @Autowired
    private ImportFieldMappingRepository fieldMappings;
    @Autowired
    private ImportRecordFilterRepository recordFilters;
    @Autowired
    private ImportRevisionFieldCriticalityRepository fieldCriticalities;
    @Autowired
    private ImportDefinitionBookmarkRepository bookmarks;
    @Autowired
    private ImportDefinitionBookmarkUsageRepository usages;
    @Autowired
    private ImportDefinitionBookmarkValueRepository definitionValues;
    /**
     * Voor twee dingen die niet via JPA gaan: de gesimuleerde activatierace (3a) heeft een
     * databasetoestand nodig die de service zelf niet kan maken, en een herkomst-FK is buiten een
     * transactie alleen als kolomwaarde te lezen (de associatie is lazy).
     */
    @Autowired
    private JdbcTemplate jdbc;

    private MaterialisationFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = MaterialisationFixtures.of(context);
    }

    // --- §1: de kloon zelf ---------------------------------------------------------------------------

    @Test
    void copiesAnActiveRevisionIntoAByteIdenticalDraftSuccessor() {
        MaterialisationFixtures.Template source = ownDefinition("BYTE", RevisionStatus.ACTIVE);

        RevisionView view = successors.createSuccessor(source.revision().getId(),
                "Drempels aanscherpen na het kwartaaloverleg", actor());

        assertThat(view.revisionNumber()).isEqualTo(2);
        assertThat(view.status()).isEqualTo("DRAFT");
        assertThat(view.definitionId()).isEqualTo(source.definition().getId().longValue());

        ImportDefinitionRevision successor = revisions.findById(view.id()).orElseThrow();
        ImportDefinitionRevision origin = revisions.findById(source.revision().getId()).orElseThrow();
        // De herkomstkolom rechtstreeks uit de database: buiten een transactie is de lazy associatie
        // niet te volgen, en de kolomwaarde is precies wat bewezen moet worden.
        assertThat(jdbc.queryForObject("select based_on_revision_id from import_definition_revision "
                + "where id = ?", Long.class, view.id())).isEqualTo(origin.getId());
        assertThat(successor.getChangeReason()).isEqualTo("Drempels aanscherpen na het kwartaaloverleg");
        assertThat(successor.getCreatedBy()).isEqualTo(USER);
        assertThat(successor.getCreatedBySubject()).isEqualTo(SUBJECT);

        // Een DRAFT is niet goedgekeurd: de drie approve-velden blijven leeg (§1).
        assertThat(successor.getApprovedAt()).isNull();
        assertThat(successor.getApprovedBy()).isNull();
        assertThat(successor.getApprovedBySubject()).isNull();

        // De vier hashes zijn herberekend en komen — omdat geen enkel veld wijzigde — uit op exact
        // dezelfde waarde als die van de bron (R-REV-X1: de kloonstap raakt nooit een identiteits- of
        // prijsbepalend veld aan).
        assertThat(successor.getAccessConfigHash()).isEqualTo(origin.getAccessConfigHash());
        assertThat(successor.getStructureConfigHash()).isEqualTo(origin.getStructureConfigHash());
        assertThat(successor.getRecordRulesConfigHash()).isEqualTo(origin.getRecordRulesConfigHash());
        assertThat(successor.getCompositeConfigHash()).isEqualTo(origin.getCompositeConfigHash());

        // De identiteits-, prijs- en structuurvelden letterlijk.
        assertThat(successor.getIdentityProfileKind()).isEqualTo(origin.getIdentityProfileKind());
        assertThat(successor.getIdentitySupplierField()).isEqualTo(origin.getIdentitySupplierField());
        assertThat(successor.getIdentitySupplierGroupField())
                .isEqualTo(origin.getIdentitySupplierGroupField());
        assertThat(successor.getIdentitySupplierReferenceField())
                .isEqualTo(origin.getIdentitySupplierReferenceField());
        assertThat(successor.getRecordBasePriceField()).isEqualTo(origin.getRecordBasePriceField());
        assertThat(successor.getRecordCanonicalisationVersion())
                .isEqualTo(origin.getRecordCanonicalisationVersion());
        assertThat(successor.getStructureDelimiter()).isEqualTo(origin.getStructureDelimiter());

        // De laagversienummers krijgen pas bij activatie betekenis (3b): tot dan blijven ze op hun default.
        assertThat(successor.getAccessVersion()).isEqualTo(1);
        assertThat(successor.getStructureVersion()).isEqualTo(1);
        assertThat(successor.getRecordRulesVersion()).isEqualTo(1);

        // De bron is onaangeroerd: een opvolger is een kopie, geen verplaatsing en geen statuswijziging.
        assertThat(origin.getStatus()).isEqualTo(RevisionStatus.ACTIVE);
        assertThat(origin.getRevisionNumber()).isEqualTo(1);
    }

    /** O2: een {@code SUPERSEDED} revisie klonen is "terugdraaien naar een eerdere configuratie". */
    @Test
    void copiesASupersededRevisionToo() {
        MaterialisationFixtures.Template source = ownDefinition("SUPER", RevisionStatus.ACTIVE);
        fixtures.supersede(source);

        RevisionView view = successors.createSuccessor(source.revision().getId(),
                "Terug naar de configuratie van vóór de wijziging", actor());

        assertThat(view.status()).isEqualTo("DRAFT");
        assertThat(view.revisionNumber()).isEqualTo(2);
        assertThat(revisions.findById(source.revision().getId()).orElseThrow().getStatus())
                .isEqualTo(RevisionStatus.SUPERSEDED);
    }

    // --- §2: alle vijf de kindtabellen ----------------------------------------------------------------

    @Test
    void copiesAllFiveChildTablesIncludingDefinitionScopeBookmarksAndTheirValues() {
        MaterialisationFixtures.Template source = ownDefinition("CHILD", RevisionStatus.ACTIVE);
        ImportDefinitionRevision origin = source.revision();
        criticality(origin, "DESCRIPTION", Criticality.CRITICAL);
        fixtures.declareOn(origin, "CULTUUR", BookmarkValueScope.DEFINITION, true, BookmarkDataType.TEXT, 1,
                BookmarkUsagePlace.RECORD_FILTER_COMPARE_VALUE,
                String.valueOf(MaterialisationFixtures.FILTER_SEQUENCE));
        fixtures.declareOn(origin, "DOELBIBLIOTHEEK", BookmarkValueScope.LINK, true, BookmarkDataType.TEXT, 2,
                BookmarkUsagePlace.LINK_LIBRARY_CODE, "");
        // Een ingevulde DEFINITION-waarde met herkomst. De herkomst wijst hier naar de bronrevisie zelf:
        // voor deze proef hoeft ze alleen een bestaande revisie te zijn, zodat aantoonbaar is dat de
        // kolom létterlijk meegaat in plaats van naar de nieuwe revisie te verspringen.
        ImportDefinitionBookmarkValue value = new ImportDefinitionBookmarkValue(origin, "CULTUUR",
                BookmarkDataType.TEXT, "NL", "iemand.anders@example.test", "test-sub-iemand.anders");
        value.setSourceTemplateRevision(origin);
        definitionValues.saveAndFlush(value);

        RevisionView view = successors.createSuccessor(origin.getId(), "Alle kindrijen mee", actor());
        long successorId = view.id();

        // 1. mappings
        assertThat(fieldMappings.findByRevisionIdWithTargetField(successorId)).singleElement()
                .satisfies(mapping -> {
                    assertThat(mapping.getTargetField().getCode())
                            .isEqualTo(MaterialisationFixtures.MAPPED_FIELD);
                    assertThat(mapping.getFixedValue()).isEqualTo(MaterialisationFixtures.TEMPLATE_PLACEHOLDER);
                    assertThat(mapping.getCreatedBy()).isEqualTo(USER);
                    assertThat(mapping.getCreatedBySubject()).isEqualTo(SUBJECT);
                });
        // 2. filters
        assertThat(recordFilters.findByDefinitionRevisionIdOrderBySequenceNumberAsc(successorId))
                .singleElement().satisfies(filter -> {
                    assertThat(filter.getSequenceNumber())
                            .isEqualTo(MaterialisationFixtures.FILTER_SEQUENCE);
                    assertThat(filter.getCompareValue())
                            .isEqualTo(MaterialisationFixtures.TEMPLATE_PLACEHOLDER);
                    assertThat(filter.getCreatedBy()).isEqualTo(USER);
                });
        // 3. kritiek-overrules
        assertThat(fieldCriticalities.findByDefinitionRevisionId(successorId)).singleElement()
                .satisfies(row -> {
                    assertThat(row.getFieldKey()).isEqualTo("DESCRIPTION");
                    assertThat(row.getCriticality()).isEqualTo(Criticality.CRITICAL);
                    assertThat(row.getCreatedBy()).isEqualTo(USER);
                });
        // 4. bookmarkdeclaraties: ALLE scopes, niet alleen LINK (de bewuste afwijking van materialisatie)
        List<ImportDefinitionBookmark> declarations =
                bookmarks.findByDefinitionRevisionIdOrderBySortOrderAsc(successorId);
        assertThat(declarations).extracting(ImportDefinitionBookmark::getName)
                .containsExactly("CULTUUR", "DOELBIBLIOTHEEK");
        assertThat(declarations).extracting(ImportDefinitionBookmark::getValueScope)
                .containsExactly(BookmarkValueScope.DEFINITION, BookmarkValueScope.LINK);
        assertThat(declarations.get(0).isRequired()).isTrue();
        assertThat(usages.findByBookmarkId(declarations.get(0).getId())).singleElement()
                .satisfies(usage -> {
                    assertThat(usage.getPlaceKind())
                            .isEqualTo(BookmarkUsagePlace.RECORD_FILTER_COMPARE_VALUE);
                    assertThat(usage.getTargetHint())
                            .isEqualTo(String.valueOf(MaterialisationFixtures.FILTER_SEQUENCE));
                });
        assertThat(usages.findByBookmarkId(declarations.get(1).getId())).singleElement()
                .satisfies(usage -> assertThat(usage.getPlaceKind())
                        .isEqualTo(BookmarkUsagePlace.LINK_LIBRARY_CODE));
        // 5. bookmarkwaarden
        assertThat(definitionValues.findByDefinitionRevisionIdAndBookmarkName(successorId, "CULTUUR"))
                .hasValueSatisfying(copy -> {
                    assertThat(copy.getValueText()).isEqualTo("NL");
                    assertThat(copy.getDataType()).isEqualTo(BookmarkDataType.TEXT);
                    // De invultekening is die van de klonende gebruiker, niet van de oorspronkelijke.
                    assertThat(copy.getFilledBy()).isEqualTo(USER);
                    assertThat(copy.getFilledBySubject()).isEqualTo(SUBJECT);
                    assertThat(copy.getFilledAt()).isNotNull();
                });
        // Letterlijk mee: de herkomstkolom wijst nog steeds naar de bronrevisie en niet naar de nieuwe.
        // Rechtstreeks uit de database, want buiten een transactie is de lazy associatie niet te volgen.
        assertThat(jdbc.queryForObject("select source_template_revision_id from "
                        + "import_definition_bookmark_value where definition_revision_id = ? "
                        + "and bookmark_name = ?", Long.class, successorId, "CULTUUR"))
                .isEqualTo(origin.getId());

        // De bron is onaangeroerd: elke kindtabel heeft er nog exact één, met de oorspronkelijke
        // tekening op de bookmarkwaarde.
        assertThat(fieldMappings.findByRevisionIdWithTargetField(origin.getId())).hasSize(1);
        assertThat(recordFilters.findByDefinitionRevisionIdOrderBySequenceNumberAsc(origin.getId()))
                .hasSize(1);
        assertThat(fieldCriticalities.findByDefinitionRevisionId(origin.getId())).hasSize(1);
        assertThat(bookmarks.findByDefinitionRevisionIdOrderBySortOrderAsc(origin.getId())).hasSize(2);
        assertThat(definitionValues.findByDefinitionRevisionIdAndBookmarkName(origin.getId(), "CULTUUR"))
                .hasValueSatisfying(row ->
                        assertThat(row.getFilledBy()).isEqualTo("iemand.anders@example.test"));
    }

    /**
     * Waarom de waardekopie van §2 businessbetekenis heeft: zonder haar zou de opvolger van een revisie
     * met een <b>verplichte</b> DEFINITION-bookmark niet meer te activeren zijn — het blokkeerpunt
     * {@code CONFIG_REQUIRED_BOOKMARK_MISSING} zou aanslaan op een waarde die de bron wél had.
     */
    @Test
    void theCopiedBookmarkValueKeepsARequiredDefinitionBookmarkSatisfiedOnActivation() {
        MaterialisationFixtures.Template source = ownDefinition("REQBMK", RevisionStatus.ACTIVE);
        fixtures.declareOn(source.revision(), "CULTUUR", BookmarkValueScope.DEFINITION, true,
                BookmarkDataType.TEXT, 1, BookmarkUsagePlace.RECORD_FILTER_COMPARE_VALUE,
                String.valueOf(MaterialisationFixtures.FILTER_SEQUENCE));
        ImportDefinitionBookmarkValue value = new ImportDefinitionBookmarkValue(source.revision(),
                "CULTUUR", BookmarkDataType.TEXT, "NL", USER, SUBJECT);
        definitionValues.saveAndFlush(value);

        RevisionView successor = successors.createSuccessor(source.revision().getId(),
                "Opvolger met verplichte bookmark", actor());
        RevisionView activated = setup.activateRevision(successor.id(), actor());

        assertThat(activated.status()).isEqualTo("ACTIVE");
        assertThat(revisions.findById(source.revision().getId()).orElseThrow().getStatus())
                .isEqualTo(RevisionStatus.SUPERSEDED);
    }

    // --- O2 en O1: wat geweigerd wordt ----------------------------------------------------------------

    @Test
    void refusesToCopyADraftOrAnyOtherNonFrozenStatus() {
        MaterialisationFixtures.Template draft = ownDefinition("DRAFTSRC", RevisionStatus.DRAFT);
        assertThatThrownBy(() -> successors.createSuccessor(draft.revision().getId(), "Toch proberen",
                actor()))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo("REVISION_NOT_CLONEABLE");
        // Niets geschreven: de definitie heeft nog steeds haar ene revisie.
        assertThat(revisions.findByImportDefinitionIdOrderByRevisionNumberDesc(
                draft.definition().getId())).hasSize(1);

        MaterialisationFixtures.Template withdrawn = ownDefinition("WDRAWN", RevisionStatus.WITHDRAWN);
        assertThatThrownBy(() -> successors.createSuccessor(withdrawn.revision().getId(), "Toch proberen",
                actor()))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo("REVISION_NOT_CLONEABLE");
    }

    @Test
    void refusesASecondSuccessorWhileADraftIsStillOpenAndNamesTheExistingRevision() {
        MaterialisationFixtures.Template source = ownDefinition("ONEDRAFT", RevisionStatus.ACTIVE);
        RevisionView first = successors.createSuccessor(source.revision().getId(), "Eerste opvolger",
                actor());

        assertThatThrownBy(() -> successors.createSuccessor(source.revision().getId(), "Tweede opvolger",
                actor()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("revision 2")
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo("REVISION_DRAFT_ALREADY_EXISTS");

        // Twee keer dezelfde handeling levert nooit twee opvolgers op (idempotent in gevolg, niet in vorm).
        assertThat(revisions.findByImportDefinitionIdOrderByRevisionNumberDesc(source.definition().getId()))
                .extracting(ImportDefinitionRevision::getId)
                .containsExactly(first.id(), source.revision().getId());
    }

    @Test
    void refusesAMissingOrBlankChangeReasonAndAnUnknownRevision() {
        MaterialisationFixtures.Template source = ownDefinition("REASON", RevisionStatus.ACTIVE);

        assertThatThrownBy(() -> successors.createSuccessor(source.revision().getId(), null, actor()))
                .isInstanceOf(BadRequestException.class)
                .extracting(error -> ((BadRequestException) error).getCode())
                .isEqualTo("CHANGE_REASON_REQUIRED");
        assertThatThrownBy(() -> successors.createSuccessor(source.revision().getId(), "   ", actor()))
                .isInstanceOf(BadRequestException.class)
                .extracting(error -> ((BadRequestException) error).getCode())
                .isEqualTo("CHANGE_REASON_REQUIRED");
        assertThat(revisions.findByImportDefinitionIdOrderByRevisionNumberDesc(source.definition().getId()))
                .hasSize(1);

        assertThatThrownBy(() -> successors.createSuccessor(-1L, "Onbekende bron", actor()))
                .isInstanceOf(NotFoundException.class)
                .extracting(error -> ((NotFoundException) error).getCode())
                .isEqualTo("REVISION_NOT_FOUND");
    }

    // --- 3b: laagversienummers bij activatie ----------------------------------------------------------

    /** Ongewijzigde laaghash = ongewijzigde laagversie; de opvolger vervangt de vorige ACTIVE. */
    @Test
    void activationKeepsTheLayerVersionsWhenNothingChangedInTheDraft() {
        MaterialisationFixtures.Template source = ownDefinition("SAMEHASH", RevisionStatus.ACTIVE);
        RevisionView successor = successors.createSuccessor(source.revision().getId(),
                "Alleen een nieuwe reden, geen veldwijziging", actor());

        setup.activateRevision(successor.id(), actor());

        ImportDefinitionRevision activated = revisions.findById(successor.id()).orElseThrow();
        ImportDefinitionRevision previous = revisions.findById(source.revision().getId()).orElseThrow();
        assertThat(activated.getStatus()).isEqualTo(RevisionStatus.ACTIVE);
        assertThat(activated.getApprovedBy()).isEqualTo(USER);
        assertThat(activated.getApprovedBySubject()).isEqualTo(SUBJECT);
        assertThat(activated.getApprovedAt()).isNotNull();
        assertThat(previous.getStatus()).isEqualTo(RevisionStatus.SUPERSEDED);

        assertThat(activated.getAccessVersion()).isEqualTo(previous.getAccessVersion());
        assertThat(activated.getStructureVersion()).isEqualTo(previous.getStructureVersion());
        assertThat(activated.getRecordRulesVersion()).isEqualTo(previous.getRecordRulesVersion());
    }

    /**
     * Eén gewijzigd recordregel-veld: alleen {@code record_rules_version} gaat één stap omhoog, de twee
     * andere lagen blijven staan. Zo is aan het versienummer af te lezen in wélke laag er echt iets
     * veranderde.
     */
    @Test
    void activationBumpsOnlyTheLayerWhoseHashChanged() {
        MaterialisationFixtures.Template source = ownDefinition("BUMP", RevisionStatus.ACTIVE);
        RevisionView successor = successors.createSuccessor(source.revision().getId(),
                "Ander leverancierskolomhoofd in de bron", actor());

        // De DRAFT wijzigen zoals bouwstap S1-X-4 (endpoint E3) dat later zal doen: veld zetten, hashes
        // herberekenen. Enkel de recordregel-laag verandert daardoor.
        ImportDefinitionRevision draft = revisions.findById(successor.id()).orElseThrow();
        draft.setIdentitySupplierField("ANDERE_KOLOM");
        RevisionConfigHashes.applyAll(draft);
        revisions.saveAndFlush(draft);

        setup.activateRevision(successor.id(), actor());

        ImportDefinitionRevision activated = revisions.findById(successor.id()).orElseThrow();
        ImportDefinitionRevision previous = revisions.findById(source.revision().getId()).orElseThrow();
        assertThat(activated.getRecordRulesConfigHash()).isNotEqualTo(previous.getRecordRulesConfigHash());
        assertThat(activated.getRecordRulesVersion()).isEqualTo(previous.getRecordRulesVersion() + 1);
        assertThat(activated.getAccessVersion()).isEqualTo(previous.getAccessVersion());
        assertThat(activated.getStructureVersion()).isEqualTo(previous.getStructureVersion());
    }

    /**
     * De grens van 3b: een gematerialiseerde revisie 1 draagt een herkomstrevisie uit een <b>andere</b>
     * definitie (het sjabloon). Haar laagversies zijn niet de voortzetting van de versiereeks van dat
     * sjabloon en blijven daarom op 1 staan, ook al verschilt haar recordregel-hash van die van het
     * sjabloon (hier door een {@code REVISION_IDENTITY_FIELD}-bookmark).
     */
    @Test
    void activationLeavesTheLayerVersionsOfAMaterialisedRevisionAlone() {
        MaterialisationFixtures.Template template = fixtures.template("MATVERS");
        declareShareableBookmarks(template.revision());
        fixtures.declareOn(template.revision(), "LEVERANCIERSVELD", BookmarkValueScope.DEFINITION, true,
                BookmarkDataType.TEXT, 3, BookmarkUsagePlace.REVISION_IDENTITY_FIELD, "SUPPLIER");

        MaterialisationView materialised = materialisation.materialise(template.definition().getId(),
                new MaterialiseRequest(null, MaterialisationMode.NEW_DEFINITION, null,
                        template.definitionCode(), "Afgeleide definitie", null, template.linkCode(),
                        "Afgeleide koppeling", template.supplier().getCode(), null, null,
                        List.of(new BookmarkValue("CULTUUR", "NL"),
                                new BookmarkValue("DOELBIBLIOTHEEK", "PSARF901"),
                                new BookmarkValue("LEVERANCIERSVELD", "ANDERE_KOLOM")),
                        USER),
                actor());

        setup.activateRevision(materialised.definitionRevisionId(), actor());

        ImportDefinitionRevision derived =
                revisions.findById(materialised.definitionRevisionId()).orElseThrow();
        ImportDefinitionRevision templateRevision =
                revisions.findById(template.revision().getId()).orElseThrow();
        assertThat(derived.getStatus()).isEqualTo(RevisionStatus.ACTIVE);
        // De hash verschilt wél van die van het sjabloon: zonder de definitiegrens van 3b zou de
        // recordregel-versie hier op 2 staan.
        assertThat(derived.getRecordRulesConfigHash())
                .isNotEqualTo(templateRevision.getRecordRulesConfigHash());
        assertThat(derived.getAccessVersion()).isEqualTo(1);
        assertThat(derived.getStructureVersion()).isEqualTo(1);
        assertThat(derived.getRecordRulesVersion()).isEqualTo(1);
        // Het sjabloon zelf is niet aangeraakt.
        assertThat(templateRevision.getStatus()).isEqualTo(RevisionStatus.ACTIVE);
        assertThat(templateRevision.getRecordRulesVersion()).isEqualTo(1);
    }

    // --- 3a: gelijktijdige activatie ------------------------------------------------------------------

    /**
     * 3a: de botsing op {@code uk_import_definition_revision_active} wordt 409
     * {@code REVISION_ACTIVATION_CONFLICT} in plaats van een 500.
     *
     * <h2>Waarom dit een gesimuleerde race is en geen echte</h2>
     * De echte race vraagt twee transacties die elkaar op het juiste moment kruisen; met threads en een
     * kleine verbindingspool levert dat een test op die soms slaagt en soms hangt — precies het soort
     * test dat later niemand meer vertrouwt. Deze test maakt dezelfde <b>databasetoestand</b>
     * deterministisch na: de vorige revisie houdt haar {@code active_marker} nog vast (zoals in de
     * milliseconde waarin de andere transactie hem net genomen heeft) terwijl haar status al niet meer
     * {@code ACTIVE} is, zodat de statusquery van de service niets vindt en de botsing pas bij het
     * wegschrijven ontstaat. Dat is exact het pad dat 3a moet opvangen: dezelfde constraint, dezelfde
     * vertaling, dezelfde terugrol.
     */
    @Test
    void translatesTheActiveMarkerCollisionIntoAConflictAndWritesNothing() {
        MaterialisationFixtures.Template source = ownDefinition("RACE", RevisionStatus.ACTIVE);
        RevisionView successor = successors.createSuccessor(source.revision().getId(),
                "Opvolger die op een gelijktijdige activatie botst", actor());
        // De marker van de vorige revisie blijft staan, haar status niet: de service ziet dus geen
        // ACTIVE revisie meer en probeert de marker zelf te nemen.
        jdbc.update("update import_definition_revision set status = 'SUPERSEDED' where id = ?",
                source.revision().getId());

        assertThatThrownBy(() -> setup.activateRevision(successor.id(), actor()))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo("REVISION_ACTIVATION_CONFLICT");

        // Niets geactiveerd: de opvolger staat nog op DRAFT en draagt geen goedkeuring.
        ImportDefinitionRevision draft = revisions.findById(successor.id()).orElseThrow();
        assertThat(draft.getStatus()).isEqualTo(RevisionStatus.DRAFT);
        assertThat(draft.getApprovedBy()).isNull();
        assertThat(draft.getApprovedAt()).isNull();
    }

    // --- §1 "Belangrijke verificatie": de sjabloonherkomst blijft vindbaar ----------------------------

    @Test
    void templateOriginStaysIntactAfterTwoSuccessorRevisionsAndAfterTheTemplateGetsItsOwn() {
        MaterialisationFixtures.Template template = fixtures.template("ORIGIN");
        declareShareableBookmarks(template.revision());
        MaterialisationView materialised = materialisation.materialise(template.definition().getId(),
                newRequest(template, template.supplier(), template.linkCode(), "PSARF910"), actor());
        long derivedDefinitionId = materialised.definitionId();
        long materialisationRevisionId = materialised.definitionRevisionId();

        // De afgeleide definitie doorloopt twee volledige opvolgcycli.
        setup.activateRevision(materialisationRevisionId, actor());
        RevisionView second = successors.createSuccessor(materialisationRevisionId, "Eerste opvolger",
                actor());
        setup.activateRevision(second.id(), actor());
        RevisionView third = successors.createSuccessor(second.id(), "Tweede opvolger", actor());
        setup.activateRevision(third.id(), actor());
        assertThat(third.revisionNumber()).isEqualTo(3);

        // En het sjabloon krijgt ook zijn eigen opvolger (DRAFT, dus de ACTIVE sjabloonversie blijft 1).
        RevisionView templateSuccessor = successors.createSuccessor(template.revision().getId(),
                "Opvolger van het sjabloon zelf", actor());
        assertThat(templateSuccessor.status()).isEqualTo("DRAFT");

        // De materialisatierevisie blijft de laagst genummerde revisie met herkomst uit dít sjabloon.
        List<MaterialisedDefinitionView> rows =
                materialisation.listMaterialisations(template.definition().getId(), null, null).content();
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.definitionId()).isEqualTo(derivedDefinitionId);
            assertThat(row.definitionRevisionId()).isEqualTo(materialisationRevisionId);
            assertThat(row.definitionRevisionNumber()).isEqualTo(1);
            assertThat(row.definitionRevisionStatus()).isEqualTo("SUPERSEDED");
            assertThat(row.templateRevisionId()).isEqualTo(template.revision().getId());
            assertThat(row.templateRevisionNumber()).isEqualTo(1);
            assertThat(row.shareable()).isTrue();
            assertThat(row.blockingBookmarkName()).isNull();
        });

        // En het hergebruikspad vindt dezelfde herkomst nog: een tweede leverancier mag nog steeds op
        // deze definitie gekoppeld worden, ook al is haar eigen revisienummer inmiddels 3.
        SourceOrganisation secondSupplier = fixtures.extraSupplier(template, "B");
        MaterialisationView reused = materialisation.materialise(template.definition().getId(),
                new MaterialiseRequest(template.revision().getId(), MaterialisationMode.REUSE_DEFINITION,
                        derivedDefinitionId, null, null, null, template.linkCode("B"), "Tweede koppeling",
                        secondSupplier.getCode(), null, null,
                        List.of(new BookmarkValue("DOELBIBLIOTHEEK", "PSARF911")), USER),
                actor());
        assertThat(reused.definitionCreated()).isFalse();
        assertThat(reused.definitionId()).isEqualTo(derivedDefinitionId);
        assertThat(reused.definitionRevisionId()).isEqualTo(materialisationRevisionId);
    }

    // --- HTTP (endpoint E2) ---------------------------------------------------------------------------

    @Test
    void returns201AndTheRevisionViewOverHttpAndTheStableCodesOnTheErrorPaths() throws Exception {
        MaterialisationFixtures.Template source = ownDefinition("HTTP", RevisionStatus.ACTIVE);

        String body = mockMvc.perform(post(SETUP + "/revisions/{id}/successor", source.revision().getId())
                        .with(TestActors.as(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeReason\":\"Nieuwe drempels\",\"createdBy\":\"" + USER + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.revisionNumber").value(2))
                .andExpect(jsonPath("$.identityProfileKind").value("THREE_PART"))
                .andReturn().getResponse().getContentAsString();
        long successorId = ((Number) JsonPath.read(body, "$.id")).longValue();
        ImportDefinitionRevision successor = revisions.findById(successorId).orElseThrow();
        assertThat(successor.getChangeReason()).isEqualTo("Nieuwe drempels");
        assertThat(successor.getCreatedBySubject()).isEqualTo(SUBJECT);

        // O1 over HTTP: 409 met een stabiele code.
        mockMvc.perform(post(SETUP + "/revisions/{id}/successor", source.revision().getId())
                        .with(TestActors.as(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeReason\":\"Nog eens\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_DRAFT_ALREADY_EXISTS"));

        // Ontbrekende wijzigingsreden: 400 met een stabiele code, op een verse bron.
        MaterialisationFixtures.Template other = ownDefinition("HTTPBAD", RevisionStatus.ACTIVE);
        mockMvc.perform(post(SETUP + "/revisions/{id}/successor", other.revision().getId())
                        .with(TestActors.as(USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHANGE_REASON_REQUIRED"));
        // Een DRAFT als bron: 409 REVISION_NOT_CLONEABLE.
        MaterialisationFixtures.Template draft = ownDefinition("HTTPDRAFT", RevisionStatus.DRAFT);
        mockMvc.perform(post(SETUP + "/revisions/{id}/successor", draft.revision().getId())
                        .with(TestActors.as(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeReason\":\"Toch proberen\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_CLONEABLE"));
        // Onbekende revisie: 404.
        mockMvc.perform(post(SETUP + "/revisions/{id}/successor", 999_999_999L)
                        .with(TestActors.as(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeReason\":\"Onbekend\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_FOUND"));
    }

    // --- Helpers --------------------------------------------------------------------------------------

    /** Dezelfde tekening als de HTTP-tests: naam plus geverifieerd subject. */
    private static ActorIdentity actor() {
        return new ActorIdentity(USER, SUBJECT);
    }

    /**
     * Een gewone (niet-sjabloon) definitie met één revisie in de gevraagde status, met een veldmapping en
     * een recordfilter erop — de achtergrond van {@code MaterialisationFixtures}, maar als
     * {@link DefinitionUsageType#OWN_DEFINITION}: een opvolgrevisie hoort bij live configuratie.
     */
    private MaterialisationFixtures.Template ownDefinition(String prefix, RevisionStatus status) {
        return fixtures.template(prefix, DefinitionUsageType.OWN_DEFINITION, status);
    }

    private void criticality(ImportDefinitionRevision revision, String fieldKey, Criticality criticality) {
        ImportRevisionFieldCriticality row =
                new ImportRevisionFieldCriticality(revision.getId(), fieldKey, criticality);
        row.setCreatedBy(USER);
        fieldCriticalities.saveAndFlush(row);
    }

    /** Zoals {@code TemplateReuseTest}: de enige per-leverancier waarde landt op de koppeling. */
    private void declareShareableBookmarks(ImportDefinitionRevision revision) {
        fixtures.declareOn(revision, "CULTUUR", BookmarkValueScope.DEFINITION, true, BookmarkDataType.ENUM,
                1, BookmarkUsagePlace.RECORD_FILTER_COMPARE_VALUE,
                String.valueOf(MaterialisationFixtures.FILTER_SEQUENCE), "NL,FR,EN", null);
        fixtures.declareOn(revision, "DOELBIBLIOTHEEK", BookmarkValueScope.LINK, true, BookmarkDataType.TEXT,
                2, BookmarkUsagePlace.LINK_LIBRARY_CODE, "");
    }

    private MaterialiseRequest newRequest(MaterialisationFixtures.Template template,
                                          SourceOrganisation supplier, String linkCode, String libraryCode) {
        return new MaterialiseRequest(null, MaterialisationMode.NEW_DEFINITION, null,
                template.definitionCode(), "Afgeleide definitie", null, linkCode, "Afgeleide koppeling",
                supplier.getCode(), null, null,
                List.of(new BookmarkValue("CULTUUR", "NL"), new BookmarkValue("DOELBIBLIOTHEEK", libraryCode)),
                USER);
    }
}
