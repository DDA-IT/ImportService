package be.dda.catalogimport.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.ImportDefinitionBookmarkRepository;
import be.dda.catalogimport.dao.ImportDefinitionBookmarkUsageRepository;
import be.dda.catalogimport.dao.ImportDefinitionBookmarkValueRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportFieldCatalogRepository;
import be.dda.catalogimport.dao.ImportFieldMappingRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.ImportRecordFilterRepository;
import be.dda.catalogimport.dao.ImportRevisionFieldCriticalityRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.BookmarkDataType;
import be.dda.catalogimport.domain.BookmarkUsagePlace;
import be.dda.catalogimport.domain.BookmarkValueScope;
import be.dda.catalogimport.domain.Criticality;
import be.dda.catalogimport.domain.DefinitionUsageType;
import be.dda.catalogimport.domain.FieldValueKind;
import be.dda.catalogimport.domain.FilterOperator;
import be.dda.catalogimport.domain.FilterOutcome;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionBookmark;
import be.dda.catalogimport.domain.ImportDefinitionBookmarkUsage;
import be.dda.catalogimport.domain.ImportDefinitionBookmarkValue;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportFieldCatalogEntry;
import be.dda.catalogimport.domain.ImportFieldMapping;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.ImportRecordFilter;
import be.dda.catalogimport.domain.ImportRevisionFieldCriticality;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.service.support.RevisionConfigHashes;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-contract van de alleen-lezen inrichtingsendpoints voor scherm 1a (S1-B1, beslissingslog 27/09
 * "Heropening scherm 1a/1b", keuze A1): {@code GET /source-organisations}, {@code GET /definitions},
 * {@code GET /definitions/{id}/revisions}. Bewust bereikbaar zonder {@code catalogimport.setup-api.enabled}
 * (in tests staat die vlag niet aan) — patroon van {@link CatalogImportTaskHttpTest}. Fixtures
 * rechtstreeks via de repositories, met dezelfde hashopbouw als {@code MaterialisationFixtures}.
 * <p>
 * Dekt ook de additieve uitbreiding van {@code GET /import-links} met {@code importDefinitionId}: de
 * bestaande, ongewijzigde vorm (zonder dat filter) blijft werken, zie
 * {@link #importLinksWithoutTheNewFilterStillWorkUnchanged()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class CatalogImportSetupQueryHttpTest {

    private static final String API = "/api/catalog-import";
    private static final String USER = "beheerder@example.test";

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SourceOrganisationRepository organisations;
    @Autowired
    private ImportDefinitionRepository definitions;
    @Autowired
    private ImportDefinitionRevisionRepository revisions;
    @Autowired
    private ImportLinkRepository links;
    @Autowired
    private ImportFieldCatalogRepository fieldCatalog;
    @Autowired
    private ImportFieldMappingRepository fieldMappings;
    @Autowired
    private ImportRecordFilterRepository recordFilters;
    @Autowired
    private ImportRevisionFieldCriticalityRepository fieldCriticalities;
    @Autowired
    private ImportDefinitionBookmarkRepository bookmarks;
    @Autowired
    private ImportDefinitionBookmarkUsageRepository bookmarkUsages;
    @Autowired
    private ImportDefinitionBookmarkValueRepository bookmarkValues;

    @Test
    void sourceOrganisationsAreReachableWithoutTheSetupApiFlagOrderedByCodeAndFilterableByActive()
            throws Exception {
        // De lijst is oplopend op code en gepagineerd (max. 200); in een gedeeld schema staan er andere rijen. Het
        // voorvoegsel "000" sorteert vóór alle gewone codes, zodat de rijen altijd op de eerste pagina staan.
        String unique = "000" + unique();
        SourceOrganisation active = organisations.saveAndFlush(
                new SourceOrganisation(unique + "-B", unique + " actief", SourceOrganisationType.SUPPLIER));
        SourceOrganisation inactive = new SourceOrganisation(unique + "-A", unique + " inactief",
                SourceOrganisationType.PURCHASING_ASSOCIATION);
        inactive.setActive(false);
        inactive = organisations.saveAndFlush(inactive);

        mockMvc.perform(get(API + "/source-organisations").param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + inactive.getId() + ")].active").value(false))
                .andExpect(jsonPath("$.content[?(@.id == " + active.getId() + ")].active").value(true))
                .andExpect(jsonPath("$.content[?(@.id == " + active.getId() + ")].type").value("SUPPLIER"));

        // Oplopend op code: A vóór B.
        mockMvc.perform(get(API + "/source-organisations").param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    int indexOfA = body.indexOf(unique + "-A");
                    int indexOfB = body.indexOf(unique + "-B");
                    assertThat(indexOfA).isGreaterThan(-1).isLessThan(indexOfB);
                });

        mockMvc.perform(get(API + "/source-organisations").param("active", "true").param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + inactive.getId() + ")]").isEmpty());
        mockMvc.perform(get(API + "/source-organisations").param("active", "false").param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + active.getId() + ")]").isEmpty());
    }

    @Test
    void definitionsAreFilterableBySourceOrganisationAndUsageTypeAndShowTheActiveRevisionId()
            throws Exception {
        String unique = unique();
        SourceOrganisation organisation = organisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + " org", SourceOrganisationType.SUPPLIER));
        SourceOrganisation other = organisations.saveAndFlush(
                new SourceOrganisation(unique + "-OTH", unique + " andere", SourceOrganisationType.SUPPLIER));

        ImportDefinition withActive = definitions.saveAndFlush(
                new ImportDefinition(organisation, unique + "-A", unique + " met actieve revisie", USER));
        ImportDefinitionRevision activeRevision = revisions.saveAndFlush(revision(withActive, RevisionStatus.ACTIVE, 1));

        ImportDefinition withoutActive = definitions.saveAndFlush(
                new ImportDefinition(organisation, unique + "-B", unique + " zonder actieve revisie", USER));
        revisions.saveAndFlush(revision(withoutActive, RevisionStatus.DRAFT, 1));

        ImportDefinition template = definitions.saveAndFlush(
                new ImportDefinition(organisation, unique + "-T", unique + " sjabloon", USER));
        template.setUsageType(DefinitionUsageType.REUSABLE_TEMPLATE);
        template = definitions.saveAndFlush(template);

        ImportDefinition onOther = definitions.saveAndFlush(
                new ImportDefinition(other, unique + "-C", unique + " andere org", USER));

        mockMvc.perform(get(API + "/definitions").param("sourceOrganisationId", String.valueOf(organisation.getId()))
                        .param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + withActive.getId() + ")].activeRevisionId")
                        .value(activeRevision.getId().intValue()))
                .andExpect(jsonPath("$.content[?(@.id == " + withoutActive.getId() + ")].activeRevisionId")
                        .value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath("$.content[?(@.id == " + onOther.getId() + ")]").isEmpty());

        mockMvc.perform(get(API + "/definitions").param("sourceOrganisationId", String.valueOf(organisation.getId()))
                        .param("usageType", "REUSABLE_TEMPLATE").param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(template.getId()))
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get(API + "/definitions").param("sourceOrganisationId", String.valueOf(other.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(onOther.getId()))
                .andExpect(jsonPath("$.content[0].sourceOrganisationCode").value(other.getCode()));
    }

    @Test
    void revisionsAreOrderedByRevisionNumberAndAnUnknownDefinitionGivesTheStableNotFoundCode()
            throws Exception {
        String unique = unique();
        SourceOrganisation organisation = organisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + " org", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(
                new ImportDefinition(organisation, unique + "-DEF", unique + " definitie", USER));
        ImportDefinitionRevision first = revisions.saveAndFlush(revision(definition, RevisionStatus.SUPERSEDED, 1));
        ImportDefinitionRevision second = revisions.saveAndFlush(revision(definition, RevisionStatus.ACTIVE, 2));

        mockMvc.perform(get(API + "/definitions/{id}/revisions", definition.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(first.getId()))
                .andExpect(jsonPath("$.content[0].revisionNumber").value(1))
                .andExpect(jsonPath("$.content[0].status").value("SUPERSEDED"))
                .andExpect(jsonPath("$.content[1].id").value(second.getId()))
                .andExpect(jsonPath("$.content[1].revisionNumber").value(2))
                .andExpect(jsonPath("$.content[1].status").value("ACTIVE"));

        mockMvc.perform(get(API + "/definitions/{id}/revisions", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEFINITION_NOT_FOUND"));
    }

    /**
     * Bouwstap S1-X-3 (revision-successor-design.md §6 endpoint E1): het volledige revisiedetail, met
     * alle vijf configuratie-kindtabellen gevuld — precies wat een gebruiker moet zien vóór hij een
     * opvolger wijzigt.
     */
    @Test
    void revisionDetailShowsAllScalarFieldsAndAllFiveConfigurationChildTables() throws Exception {
        String unique = unique();
        SourceOrganisation organisation = organisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + " org", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(
                new ImportDefinition(organisation, unique + "-DEF", unique + " definitie", USER));
        ImportDefinitionRevision revision = revisions.saveAndFlush(revision(definition, RevisionStatus.ACTIVE, 1));

        ImportFieldCatalogEntry target = fieldCatalog.findById("E_SUPPLIER").orElseThrow();
        ImportFieldMapping mapping = new ImportFieldMapping(revision, 1, target, FieldValueKind.FIXED_VALUE,
                target.getDataType(), target.getDefaultOwner(), target.getIdentityClass());
        mapping.setFixedValue("VASTE-WAARDE");
        mapping.setCriticality(Criticality.NON_CRITICAL);
        mapping.setCreatedBy(USER);
        mapping = fieldMappings.saveAndFlush(mapping);

        ImportRecordFilter filter = new ImportRecordFilter(revision, 1, "CULTUUR", FilterOperator.EQUALS,
                "NL", FilterOutcome.INCLUDE);
        filter.setCreatedBy(USER);
        filter = recordFilters.saveAndFlush(filter);

        ImportRevisionFieldCriticality criticality =
                new ImportRevisionFieldCriticality(revision.getId(), "CURRENCY", Criticality.CRITICAL);
        criticality.setCreatedBy(USER);
        fieldCriticalities.saveAndFlush(criticality);

        ImportDefinitionBookmark bookmark = new ImportDefinitionBookmark(revision, unique + "_BM",
                "label", BookmarkDataType.TEXT, BookmarkValueScope.DEFINITION, "catalogImport.manage", 1);
        bookmark.setCreatedBy(USER);
        bookmark = bookmarks.saveAndFlush(bookmark);
        ImportDefinitionBookmarkUsage usage = new ImportDefinitionBookmarkUsage(bookmark,
                BookmarkUsagePlace.RECORD_FILTER_COMPARE_VALUE, String.valueOf(filter.getSequenceNumber()));
        bookmarkUsages.saveAndFlush(usage);

        ImportDefinitionBookmarkValue value = new ImportDefinitionBookmarkValue(revision, bookmark.getName(),
                BookmarkDataType.TEXT, "ingevulde-waarde", USER);
        bookmarkValues.saveAndFlush(value);

        mockMvc.perform(get(API + "/definitions/{definitionId}/revisions/{revisionId}",
                        definition.getId(), revision.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(revision.getId()))
                .andExpect(jsonPath("$.definitionId").value(definition.getId()))
                .andExpect(jsonPath("$.revisionNumber").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.identityProfileKind").value("THREE_PART"))
                .andExpect(jsonPath("$.identitySupplierField").value("LEVERANCIER"))
                .andExpect(jsonPath("$.recordBasePriceField").value("PRIJS"))
                .andExpect(jsonPath("$.recordCanonicalisationVersion").value(2))
                .andExpect(jsonPath("$.accessConfigHash").isNotEmpty())
                .andExpect(jsonPath("$.compositeConfigHash").isNotEmpty())
                .andExpect(jsonPath("$.createdBy").value(USER))
                .andExpect(jsonPath("$.mappings.length()").value(1))
                .andExpect(jsonPath("$.mappings[0].targetFieldCode").value("E_SUPPLIER"))
                .andExpect(jsonPath("$.mappings[0].valueKind").value("FIXED_VALUE"))
                .andExpect(jsonPath("$.filters.length()").value(1))
                .andExpect(jsonPath("$.filters[0].sourceReference").value("CULTUUR"))
                .andExpect(jsonPath("$.filters[0].compareValue").value("NL"))
                .andExpect(jsonPath("$.fieldCriticalities.length()").value(1))
                .andExpect(jsonPath("$.fieldCriticalities[0].fieldKey").value("CURRENCY"))
                .andExpect(jsonPath("$.fieldCriticalities[0].criticality").value("CRITICAL"))
                .andExpect(jsonPath("$.bookmarks.length()").value(1))
                .andExpect(jsonPath("$.bookmarks[0].name").value(unique + "_BM"))
                .andExpect(jsonPath("$.bookmarks[0].usages.length()").value(1))
                .andExpect(jsonPath("$.bookmarks[0].usages[0].placeKind")
                        .value("RECORD_FILTER_COMPARE_VALUE"))
                .andExpect(jsonPath("$.bookmarkValues.length()").value(1))
                .andExpect(jsonPath("$.bookmarkValues[0].bookmarkName").value(bookmark.getName()))
                .andExpect(jsonPath("$.bookmarkValues[0].valueText").value("ingevulde-waarde"));
    }

    /** Geen statusbeperking op lezen: een DRAFT- en een SUPERSEDED-revisie zijn beide gewoon zichtbaar. */
    @Test
    void revisionDetailWorksForDraftAndSupersededRevisionsWithoutAnyStatusRestriction() throws Exception {
        String unique = unique();
        SourceOrganisation organisation = organisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + " org", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(
                new ImportDefinition(organisation, unique + "-DEF", unique + " definitie", USER));
        ImportDefinitionRevision draft = revisions.saveAndFlush(revision(definition, RevisionStatus.DRAFT, 1));
        ImportDefinitionRevision superseded =
                revisions.saveAndFlush(revision(definition, RevisionStatus.SUPERSEDED, 2));

        mockMvc.perform(get(API + "/definitions/{definitionId}/revisions/{revisionId}",
                        definition.getId(), draft.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.mappings").isArray())
                .andExpect(jsonPath("$.mappings.length()").value(0));

        mockMvc.perform(get(API + "/definitions/{definitionId}/revisions/{revisionId}",
                        definition.getId(), superseded.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUPERSEDED"));
    }

    @Test
    void revisionDetailGivesStableNotFoundCodesForAnUnknownDefinitionOrAnUnrelatedRevision()
            throws Exception {
        String unique = unique();
        SourceOrganisation organisation = organisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + " org", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(
                new ImportDefinition(organisation, unique + "-DEF", unique + " definitie", USER));
        ImportDefinitionRevision revision = revisions.saveAndFlush(revision(definition, RevisionStatus.ACTIVE, 1));

        ImportDefinition otherDefinition = definitions.saveAndFlush(
                new ImportDefinition(organisation, unique + "-OTH", unique + " andere definitie", USER));

        mockMvc.perform(get(API + "/definitions/{definitionId}/revisions/{revisionId}", 999_999_999L,
                        revision.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEFINITION_NOT_FOUND"));

        mockMvc.perform(get(API + "/definitions/{definitionId}/revisions/{revisionId}", definition.getId(),
                        999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_FOUND"));

        // Bestaat wel, maar hoort bij een andere definitie: ook REVISION_NOT_FOUND, geen 500 op een
        // stille mismatch.
        mockMvc.perform(get(API + "/definitions/{definitionId}/revisions/{revisionId}",
                        otherDefinition.getId(), revision.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_FOUND"));
    }

    @Test
    void listsArePaginatedWithTheSharedDefaultAndMaximumAndRejectInvalidPaging() throws Exception {
        mockMvc.perform(get(API + "/source-organisations")).andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(50));
        mockMvc.perform(get(API + "/source-organisations").param("size", "5000")).andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(200));
        mockMvc.perform(get(API + "/source-organisations").param("size", "0")).andExpect(status().isBadRequest());
        mockMvc.perform(get(API + "/definitions").param("page", "-1")).andExpect(status().isBadRequest());

        String unique = unique();
        SourceOrganisation organisation = organisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + " org", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(
                new ImportDefinition(organisation, unique + "-DEF", unique + " definitie", USER));
        revisions.saveAndFlush(revision(definition, RevisionStatus.SUPERSEDED, 1));
        revisions.saveAndFlush(revision(definition, RevisionStatus.ACTIVE, 2));

        mockMvc.perform(get(API + "/definitions/{id}/revisions", definition.getId())
                        .param("size", "1").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].revisionNumber").value(2));
    }

    /** Regressietest voor de additieve uitbreiding: de bestaande vorm zonder het nieuwe filter verandert niet. */
    @Test
    void importLinksWithoutTheNewFilterStillWorkUnchanged() throws Exception {
        // Gedeeld schema + sortering op code + pagina's: "000" sorteert vóór gewone codes (eerste pagina).
        String unique = "000" + unique();
        SourceOrganisation organisation = organisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + " org", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(
                new ImportDefinition(organisation, unique + "-DEF", unique + " definitie", USER));
        SourceOrganisation supplier = organisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + " leverancier", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, "PSARF050"));

        mockMvc.perform(get(API + "/import-links").param("active", "true").param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + link.getId() + ")].importDefinitionId")
                        .value(definition.getId().intValue()));
    }

    /** Nieuw filter: sluit de boom van scherm 1a tot op koppelingenniveau. */
    @Test
    void importLinksAreFilterableByImportDefinitionId() throws Exception {
        String unique = unique();
        SourceOrganisation organisation = organisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + " org", SourceOrganisationType.SUPPLIER));
        ImportDefinition definitionA = definitions.saveAndFlush(
                new ImportDefinition(organisation, unique + "-DEF-A", unique + " definitie A", USER));
        ImportDefinition definitionB = definitions.saveAndFlush(
                new ImportDefinition(organisation, unique + "-DEF-B", unique + " definitie B", USER));
        SourceOrganisation supplier = organisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + " leverancier", SourceOrganisationType.SUPPLIER));
        ImportLink linkA = links.saveAndFlush(
                new ImportLink(unique + "-LINK-A", unique + " koppeling A", definitionA, supplier, "PSARF050"));
        links.saveAndFlush(
                new ImportLink(unique + "-LINK-B", unique + " koppeling B", definitionB, supplier, "PSARF051"));

        mockMvc.perform(get(API + "/import-links").param("importDefinitionId", String.valueOf(definitionA.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(linkA.getId()))
                .andExpect(jsonPath("$.content[0].importDefinitionId").value(definitionA.getId().intValue()));
    }

    private static String unique() {
        return "SQ" + Long.toString(System.nanoTime(), 36).toUpperCase();
    }

    /** Minimale, geldige revisie (hashopbouw als {@code MaterialisationFixtures}), zonder mappings/filters. */
    private static ImportDefinitionRevision revision(ImportDefinition definition, RevisionStatus status,
                                                      int revisionNumber) {
        ImportDefinitionRevision revision = new ImportDefinitionRevision(definition, revisionNumber,
                IdentityProfileKind.THREE_PART, USER);
        revision.setStatus(status);
        revision.setIdentitySupplierField("LEVERANCIER");
        revision.setIdentitySupplierGroupField("GROEP");
        revision.setIdentitySupplierReferenceField("REFERENTIE");
        revision.setRecordBasePriceField("PRIJS");
        revision.setStructureDelimiter(";");
        revision.setRecordCanonicalisationVersion(2);
        RevisionConfigHashes.applyAll(revision);
        return revision;
    }
}
