package be.dda.catalogimport.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.DefinitionUsageType;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
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

    @Test
    void sourceOrganisationsAreReachableWithoutTheSetupApiFlagOrderedByCodeAndFilterableByActive()
            throws Exception {
        String unique = unique();
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
        String unique = unique();
        SourceOrganisation organisation = organisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + " org", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(
                new ImportDefinition(organisation, unique + "-DEF", unique + " definitie", USER));
        SourceOrganisation supplier = organisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + " leverancier", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, "PSARF050"));

        mockMvc.perform(get(API + "/import-links").param("active", "true"))
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
