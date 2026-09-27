package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.ImportDefinitionBookmarkRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.BookmarkDataType;
import be.dda.catalogimport.domain.BookmarkValueScope;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionBookmark;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import com.jayway.jsonpath.JsonPath;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Bouwstap 5A-6 (docs/design/fase5-auth-design.md par. 1.1, par. 3, par. 4 en par. 7): de setup-,
 * sjabloon-/materialisatie-, bookmark- en koppelingendpoints ondertekenen op de <b>geverifieerde
 * identiteit</b>, net als freeze (5A-2), de overige bundelacties (5A-4) en upload/accept-baseline
 * (5A-5).
 *
 * <h2>Business rule vs. implementatie</h2>
 * <ul>
 *   <li><b>Regel:</b> wie configuratie aanmaakt, activeert, materialiseert of een bookmarkwaarde
 *       wijzigt, tekent onder zijn eigen naam. <b>Implementatie:</b>
 *       {@code CurrentActor.signer(requestValue, veld)} in de controller, vóór de service; de
 *       actorvelden ({@code createdBy}, {@code approvedBy}, {@code materialisedBy}, {@code updatedBy})
 *       zijn optioneel geworden en zijn enkel nog een controle.</li>
 *   <li><b>Regel:</b> een handtekening onder de verkeerde naam gebeurt nooit ongemerkt.
 *       <b>Implementatie:</b> afwijkende naam = 400 {@code ACTOR_FIELD_MISMATCH} zonder
 *       nevenschrijfactie.</li>
 *   <li><b>Regel:</b> {@code system} tekent nooit — ook geen configuratie. <b>Implementatie:</b> 403
 *       {@code SYSTEM_ACTOR_FORBIDDEN}, ook op de drie endpoints zonder {@code *_by}-kolom.</li>
 *   <li><b>Data:</b> naast de naam komt het OIDC-subject in de {@code *_by_subject}-kolommen van
 *       changeset 007-4 (G1); {@code NULL} = geen geverifieerde identiteit. Het subject staat in geen
 *       enkel antwoord (A6).</li>
 *   <li><b>Uitzondering:</b> waar de service tot nu toe {@code "setup-api"} als naam zette, staat nu de
 *       aangemelde gebruiker — de default geldt alleen nog voor rechtstreekse Service-aanroepen.</li>
 * </ul>
 * Controlevolgorde: de actorcontrole gaat vóór de service, dus 400/403 komen vóór 404/409.
 */
// Zelfde kleine verbindingspool als de andere setup-/sjabloontests: de lokale PostgreSQL heeft een
// beperkt aantal verbindingen en elke afwijkende testconfiguratie is een aparte context met een eigen
// pool.
@SpringBootTest(properties = {"catalogimport.setup-api.enabled=true",
        "spring.datasource.hikari.maximum-pool-size=4"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class SetupTemplateLinkActorHttpTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    /** Per-run achtervoegsel: het {@code local}-profiel draait tegen een blijvende PostgreSQL. */
    private static final String RUN = Long.toString(System.nanoTime() % 1_000_000_000L, 36).toUpperCase();
    private static final String SETUP = "/api/catalog-import/setup";
    private static final String TEMPLATES = "/api/catalog-import/templates";
    private static final String LINKS = "/api/catalog-import/links";
    private static final String ADMIN = "an.janssens@example.test";
    private static final String OTHER = "piet.willems@example.test";

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SourceOrganisationRepository sourceOrganisations;
    @Autowired
    private ImportDefinitionRepository definitions;
    @Autowired
    private ImportDefinitionRevisionRepository revisions;
    @Autowired
    private ImportLinkRepository links;
    @Autowired
    private ImportDefinitionBookmarkRepository bookmarks;

    // --- POST /setup/definitions (geen actorveld; de service zette "setup-api") ----------------------

    @Test
    void definitionCreateStoresTheSignedInUserAndSubjectInsteadOfSetupApi() throws Exception {
        String unique = unique("DEF");
        createOrganisation(unique);

        String body = postAs(ADMIN, definitionJson(unique, "OWN_DEFINITION"), SETUP + "/definitions")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(signature("import_definition", "created_by", "code", unique + "-DEF"))
                .containsExactly(ADMIN, subjectOf(ADMIN));
        // A6: het subject staat in geen enkel antwoord.
        assertThat(body).doesNotContain(subjectOf(ADMIN)).doesNotContain("setup-api");
    }

    @Test
    void definitionCreateAsSystemIs403AndWritesNothing() throws Exception {
        String unique = unique("DEFSYS");
        createOrganisation(unique);

        postAs("system", definitionJson(unique, "OWN_DEFINITION"), SETUP + "/definitions")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));

        assertThat(count("select count(*) from import_definition where code = ?", unique + "-DEF")).isZero();
    }

    // --- POST /setup/definitions/{id}/revisions (createdBy) ------------------------------------------

    @Test
    void revisionCreateAcceptsTheSameNameOrNoNameAndStoresTheSubject() throws Exception {
        String unique = unique("REV");
        createOrganisation(unique);
        long definitionId = createDefinition(unique, "OWN_DEFINITION");

        // Gelijke naam (andere hoofdletters) én geen veld: allebei aanvaard, allebei de token-spelling.
        long withName = id(postAs(ADMIN, revisionJson("\"createdBy\":\"" + ADMIN.toUpperCase(Locale.ROOT) + "\""),
                SETUP + "/definitions/{id}/revisions", definitionId)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long withoutName = id(postAs(ADMIN, revisionJson(null),
                SETUP + "/definitions/{id}/revisions", definitionId)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        assertThat(signature("import_definition_revision", "created_by", "id", withName))
                .containsExactly(ADMIN, subjectOf(ADMIN));
        assertThat(signature("import_definition_revision", "created_by", "id", withoutName))
                .containsExactly(ADMIN, subjectOf(ADMIN));
    }

    @Test
    void revisionCreateWithAnotherNameIs400AndCreatesNoRevision() throws Exception {
        String unique = unique("REVMIS");
        createOrganisation(unique);
        long definitionId = createDefinition(unique, "OWN_DEFINITION");

        String body = postAs(ADMIN, revisionJson("\"createdBy\":\"" + OTHER + "\""),
                SETUP + "/definitions/{id}/revisions", definitionId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACTOR_FIELD_MISMATCH"))
                .andReturn().getResponse().getContentAsString();

        // De boodschap noemt enkel de veldnaam, nooit een van beide gebruikers.
        assertThat(body).contains("createdBy").doesNotContain(ADMIN).doesNotContain(OTHER);
        assertThat(revisionCount(definitionId)).isZero();

        postAs("system", revisionJson(null), SETUP + "/definitions/{id}/revisions", definitionId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));
        assertThat(revisionCount(definitionId)).isZero();
    }

    // --- POST /setup/revisions/{id}/activate (approvedBy) --------------------------------------------

    @Test
    void activateStoresTheApproverAndSubjectAndRefusesAMismatchWithoutActivating() throws Exception {
        String unique = unique("ACT");
        createOrganisation(unique);
        long definitionId = createDefinition(unique, "OWN_DEFINITION");
        long revisionId = createRevision(definitionId);

        postAs(ADMIN, "{\"approvedBy\":\"" + OTHER + "\"}", SETUP + "/revisions/{id}/activate", revisionId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACTOR_FIELD_MISMATCH"));
        assertThat(revisionStatus(revisionId)).isEqualTo("DRAFT");
        assertThat(signature("import_definition_revision", "approved_by", "id", revisionId))
                .containsExactly(null, null);

        postAs("system", "{}", SETUP + "/revisions/{id}/activate", revisionId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));
        assertThat(revisionStatus(revisionId)).isEqualTo("DRAFT");

        // Zonder body en zonder veld: de aangemelde gebruiker tekent.
        mockMvc.perform(post(SETUP + "/revisions/{id}/activate", revisionId).with(as(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(signature("import_definition_revision", "approved_by", "id", revisionId))
                .containsExactly(ADMIN, subjectOf(ADMIN));
    }

    // --- Mappings, filters en kritiekheid (createdBy) -------------------------------------------------

    @Test
    void mappingFilterAndCriticalityStoreTheSignedInUserAndSubject() throws Exception {
        long revisionId = draftRevision("CFG");

        postAs(ADMIN, "{\"targetFieldCode\":\"EAN\",\"sourceReference\":\"ean\",\"sequenceNumber\":1}",
                SETUP + "/revisions/{id}/mappings", revisionId).andExpect(status().isCreated());
        postAs(ADMIN, "{\"sourceReference\":\"groep\",\"operator\":\"EQUALS\",\"compareValue\":\"MEET\","
                + "\"outcome\":\"EXCLUDE\"}", SETUP + "/revisions/{id}/filters", revisionId)
                .andExpect(status().isCreated());
        postAs(ADMIN, "{\"fieldKey\":\"DESCRIPTION\",\"criticality\":\"CRITICAL\"}",
                SETUP + "/revisions/{id}/field-criticality", revisionId).andExpect(status().isCreated());

        assertThat(signature("import_field_mapping", "created_by", "definition_revision_id", revisionId))
                .containsExactly(ADMIN, subjectOf(ADMIN));
        assertThat(signature("import_record_filter", "created_by", "definition_revision_id", revisionId))
                .containsExactly(ADMIN, subjectOf(ADMIN));
        assertThat(signature("import_revision_field_criticality", "created_by", "definition_revision_id",
                revisionId)).containsExactly(ADMIN, subjectOf(ADMIN));
    }

    @Test
    void mappingFilterAndCriticalityRefuseAMismatchAndSystemWithoutWritingARow() throws Exception {
        long revisionId = draftRevision("CFGBAD");

        postAs(ADMIN, "{\"targetFieldCode\":\"EAN\",\"sourceReference\":\"ean\",\"createdBy\":\"" + OTHER + "\"}",
                SETUP + "/revisions/{id}/mappings", revisionId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACTOR_FIELD_MISMATCH"));
        postAs("system", "{\"targetFieldCode\":\"EAN\",\"sourceReference\":\"ean\"}",
                SETUP + "/revisions/{id}/mappings", revisionId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));

        postAs(ADMIN, "{\"sourceReference\":\"groep\",\"operator\":\"EQUALS\",\"compareValue\":\"MEET\","
                + "\"outcome\":\"EXCLUDE\",\"createdBy\":\"" + OTHER + "\"}",
                SETUP + "/revisions/{id}/filters", revisionId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACTOR_FIELD_MISMATCH"));
        postAs(ADMIN, "{\"fieldKey\":\"DESCRIPTION\",\"criticality\":\"CRITICAL\",\"createdBy\":\"" + OTHER
                + "\"}", SETUP + "/revisions/{id}/field-criticality", revisionId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACTOR_FIELD_MISMATCH"));

        assertThat(count("select count(*) from import_field_mapping where definition_revision_id = ?",
                revisionId)).isZero();
        assertThat(count("select count(*) from import_record_filter where definition_revision_id = ?",
                revisionId)).isZero();
        assertThat(count("select count(*) from import_revision_field_criticality "
                + "where definition_revision_id = ?", revisionId)).isZero();
    }

    // --- De endpoints zonder *_by-kolom ----------------------------------------------------------------

    @Test
    void theEndpointsWithoutAnActorFieldStillRefuseSystemAndWriteNothing() throws Exception {
        String unique = unique("NOCOL");
        createOrganisation(unique);
        long definitionId = createDefinition(unique, "OWN_DEFINITION");

        postAs("system", organisationJson(unique + "X"), SETUP + "/source-organisations")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));
        postAs("system", linkJson(unique, definitionId), SETUP + "/links")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));

        assertThat(count("select count(*) from source_organisation where code = ?", unique + "X")).isZero();
        assertThat(count("select count(*) from import_link where code = ?", unique + "-LINK")).isZero();

        // Met een gewone login werken ze onveranderd; er wordt geen naam bewaard (geen *_by-kolom).
        long linkId = id(postAs(ADMIN, linkJson(unique, definitionId), SETUP + "/links")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        postAs("system", "{\"linkId\":" + linkId + ",\"name\":\"" + unique + "-taak\"}", SETUP + "/tasks")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));
        postAs(ADMIN, "{\"linkId\":" + linkId + ",\"name\":\"" + unique + "-taak\"}", SETUP + "/tasks")
                .andExpect(status().isCreated());
    }

    // --- POST /templates/{d}/revisions/{r}/bookmarks (createdBy) + usages -------------------------------

    @Test
    void bookmarkDeclarationStoresTheSignedInUserAndSubjectAndRefusesAMismatch() throws Exception {
        String unique = unique("BMK");
        createOrganisation(unique);
        long definitionId = createDefinition(unique, "REUSABLE_TEMPLATE");
        long revisionId = createRevision(definitionId);

        postAs(ADMIN, bookmarkJson("CULTUUR", "LINK", 1, "\"createdBy\":\"" + OTHER + "\""),
                TEMPLATES + "/{d}/revisions/{r}/bookmarks", definitionId, revisionId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACTOR_FIELD_MISMATCH"));
        postAs("system", bookmarkJson("CULTUUR", "LINK", 1, null),
                TEMPLATES + "/{d}/revisions/{r}/bookmarks", definitionId, revisionId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));
        assertThat(count("select count(*) from import_definition_bookmark where definition_revision_id = ?",
                revisionId)).isZero();

        String body = postAs(ADMIN, bookmarkJson("CULTUUR", "LINK", 1, null),
                TEMPLATES + "/{d}/revisions/{r}/bookmarks", definitionId, revisionId)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        assertThat(signature("import_definition_bookmark", "created_by", "definition_revision_id", revisionId))
                .containsExactly(ADMIN, subjectOf(ADMIN));
        assertThat(body).doesNotContain(subjectOf(ADMIN));

        // De usage-rij heeft geen *_by-kolom: enkel de system-weigering geldt daar.
        postAs("system", "{\"placeKind\":\"LINK_LIBRARY_CODE\"}",
                TEMPLATES + "/{d}/revisions/{r}/bookmarks/{n}/usages", definitionId, revisionId, "CULTUUR")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));
        postAs(ADMIN, "{\"placeKind\":\"LINK_LIBRARY_CODE\"}",
                TEMPLATES + "/{d}/revisions/{r}/bookmarks/{n}/usages", definitionId, revisionId, "CULTUUR")
                .andExpect(status().isCreated());
    }

    // --- POST /templates/{d}/materialisations (materialisedBy, nu optioneel) ----------------------------

    @Test
    void materialisationSignsEveryRowItCreatesWithTheSignedInUserAndSubject() throws Exception {
        Template template = template("MAT");

        String body = postAs(ADMIN, materialiseJson(template, null), TEMPLATES + "/{d}/materialisations",
                template.definitionId())
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long derivedRevisionId = ((Number) JsonPath.read(body, "$.definitionRevisionId")).longValue();
        long derivedDefinitionId = ((Number) JsonPath.read(body, "$.definitionId")).longValue();
        long derivedLinkId = ((Number) JsonPath.read(body, "$.importLinkId")).longValue();

        // Elke rij die deze materialisatie aanmaakt draagt dezelfde handtekening (ontwerp par. 1.2, G1).
        assertThat(signature("import_definition", "created_by", "id", derivedDefinitionId))
                .containsExactly(ADMIN, subjectOf(ADMIN));
        assertThat(signature("import_definition_revision", "created_by", "id", derivedRevisionId))
                .containsExactly(ADMIN, subjectOf(ADMIN));
        assertThat(signature("import_field_mapping", "created_by", "definition_revision_id", derivedRevisionId))
                .containsExactly(ADMIN, subjectOf(ADMIN));
        assertThat(signature("import_record_filter", "created_by", "definition_revision_id", derivedRevisionId))
                .containsExactly(ADMIN, subjectOf(ADMIN));
        assertThat(signature("import_revision_field_criticality", "created_by", "definition_revision_id",
                derivedRevisionId)).containsExactly(ADMIN, subjectOf(ADMIN));
        // De meegekopieerde LINK-declaratie en de ingevulde waarden.
        assertThat(signature("import_definition_bookmark", "created_by", "definition_revision_id",
                derivedRevisionId)).containsExactly(ADMIN, subjectOf(ADMIN));
        assertThat(signature("import_link_bookmark_value", "filled_by", "import_link_id", derivedLinkId))
                .containsExactly(ADMIN, subjectOf(ADMIN));
        assertThat(body).doesNotContain(subjectOf(ADMIN));
    }

    @Test
    void materialisationWithAnotherNameIs400AndCreatesNothing() throws Exception {
        Template template = template("MATMIS");

        String body = postAs(ADMIN, materialiseJson(template, OTHER), TEMPLATES + "/{d}/materialisations",
                template.definitionId())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACTOR_FIELD_MISMATCH"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("materialisedBy").doesNotContain(ADMIN).doesNotContain(OTHER);
        assertNothingMaterialised(template);

        postAs("system", materialiseJson(template, null), TEMPLATES + "/{d}/materialisations",
                template.definitionId())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));
        assertNothingMaterialised(template);
    }

    // --- PUT /links/{id}/bookmark-values/{name} (updatedBy) ---------------------------------------------

    @Test
    void bookmarkValueKeepsTheFirstSignerAndRecordsTheChangingSignerWithTheirSubjects() throws Exception {
        long linkId = linkWithLinkScopeBookmark("LBV");

        String first = putAs(ADMIN, "{\"value\":\"NL\",\"updatedBy\":\"" + ADMIN + "\"}",
                LINKS + "/{id}/bookmark-values/{name}", linkId, "CULTUUR")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filledBy").value(ADMIN))
                .andExpect(jsonPath("$.updatedBy").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(first).doesNotContain(subjectOf(ADMIN));
        assertThat(signature("import_link_bookmark_value", "filled_by", "import_link_id", linkId))
                .containsExactly(ADMIN, subjectOf(ADMIN));

        // Een wijziging zonder veld door iemand anders: de eerste invuller en diens subject blijven.
        putAs(OTHER, "{\"value\":\"FR\"}", LINKS + "/{id}/bookmark-values/{name}", linkId, "CULTUUR")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedBy").value(OTHER));

        Map<String, Object> row = jdbc.queryForMap("select filled_by, filled_by_subject, updated_by, "
                + "updated_by_subject from import_link_bookmark_value where import_link_id = ?", linkId);
        assertThat(row).containsEntry("filled_by", ADMIN)
                .containsEntry("filled_by_subject", subjectOf(ADMIN))
                .containsEntry("updated_by", OTHER)
                .containsEntry("updated_by_subject", subjectOf(OTHER));
    }

    @Test
    void bookmarkValueRefusesAMismatchAndSystemWithoutChangingTheValue() throws Exception {
        long linkId = linkWithLinkScopeBookmark("LBVBAD");

        putAs(ADMIN, "{\"value\":\"NL\",\"updatedBy\":\"" + OTHER + "\"}",
                LINKS + "/{id}/bookmark-values/{name}", linkId, "CULTUUR")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACTOR_FIELD_MISMATCH"));
        putAs("system", "{\"value\":\"NL\"}", LINKS + "/{id}/bookmark-values/{name}", linkId, "CULTUUR")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));

        assertThat(count("select count(*) from import_link_bookmark_value where import_link_id = ?", linkId))
                .isZero();
    }

    // --- Controlevolgorde --------------------------------------------------------------------------------

    @Test
    void theActorCheckRunsBeforeTheLookupsAnd404() throws Exception {
        long unknown = 999_999_999L;

        postAs(ADMIN, revisionJson("\"createdBy\":\"" + OTHER + "\""),
                SETUP + "/definitions/{id}/revisions", unknown)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACTOR_FIELD_MISMATCH"));
        postAs("system", "{}", SETUP + "/revisions/{id}/activate", unknown)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));
        putAs("system", "{\"value\":\"NL\"}", LINKS + "/{id}/bookmark-values/{name}", unknown, "CULTUUR")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));

        // Met een geldige, passende identiteit blijft het gewoon een 404.
        postAs(ADMIN, revisionJson(null), SETUP + "/definitions/{id}/revisions", unknown)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEFINITION_NOT_FOUND"));
        postAs(ADMIN, "{}", SETUP + "/revisions/{id}/activate", unknown)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_FOUND"));
        putAs(ADMIN, "{\"value\":\"NL\"}", LINKS + "/{id}/bookmark-values/{name}", unknown, "CULTUUR")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"));
    }

    /** Een onbruikbare identiteit blijft 403 {@code ACTOR_IDENTITY_INVALID}, ook hier. */
    @Test
    void anUnusableLoginIsRefusedOnAConfigurationWrite() throws Exception {
        postAs("x".repeat(101), "{}", SETUP + "/revisions/{id}/activate", 999_999_999L)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTOR_IDENTITY_INVALID"));
    }

    // --- Helpers ------------------------------------------------------------------------------------------

    private record Template(String unique, long definitionId, long revisionId, String supplierCode) {
    }

    private ResultActions postAs(String signedInAs, String json, String urlTemplate, Object... uriVars)
            throws Exception {
        return mockMvc.perform(post(urlTemplate, uriVars).with(as(signedInAs))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions putAs(String signedInAs, String json, String urlTemplate, Object... uriVars)
            throws Exception {
        return mockMvc.perform(put(urlTemplate, uriVars).with(as(signedInAs))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /** {@code <naam>_by} en {@code <naam>_by_subject} van precies één rij. */
    private List<String> signature(String table, String nameColumn, String whereColumn, Object value) {
        Map<String, Object> row = jdbc.queryForMap("select " + nameColumn + " as actor_name, " + nameColumn
                + "_subject as actor_subject from " + table + " where " + whereColumn + " = ?", value);
        return Arrays.asList((String) row.get("actor_name"), (String) row.get("actor_subject"));
    }

    private long count(String sql, Object argument) {
        Long count = jdbc.queryForObject(sql, Long.class, argument);
        return count == null ? 0L : count;
    }

    private long revisionCount(long definitionId) {
        return count("select count(*) from import_definition_revision where import_definition_id = ?",
                definitionId);
    }

    private String revisionStatus(long revisionId) {
        return jdbc.queryForObject("select status from import_definition_revision where id = ?", String.class,
                revisionId);
    }

    private void assertNothingMaterialised(Template template) {
        assertThat(count("select count(*) from import_definition where code = ?",
                template.unique() + "-DERIVED")).isZero();
        assertThat(count("select count(*) from import_link where code = ?",
                template.unique() + "-DLINK")).isZero();
    }

    private static String subjectOf(String username) {
        return "test-sub-" + username;
    }

    private static String unique(String prefix) {
        return "AC" + SEQUENCE.incrementAndGet() + prefix + RUN;
    }

    /** {@code import_link.library_code} is varchar(20). */
    private static String library(String unique) {
        return unique.length() <= 20 ? unique : unique.substring(0, 20);
    }

    private static long id(String body) {
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private static String organisationJson(String code) {
        return "{\"code\":\"" + code + "\",\"name\":\"" + code + " BV\",\"type\":\"SUPPLIER\"}";
    }

    private static String definitionJson(String unique, String usageType) {
        return "{\"sourceOrganisationCode\":\"" + unique + "\",\"code\":\"" + unique + "-DEF\",\"name\":\""
                + unique + " catalogus\",\"usageType\":\"" + usageType + "\"}";
    }

    private static String linkJson(String unique, long definitionId) {
        return "{\"definitionId\":" + definitionId + ",\"code\":\"" + unique + "-LINK\",\"name\":\"" + unique
                + " koppeling\",\"supplierCode\":\"" + unique + "\",\"libraryCode\":\"" + library(unique) + "\"}";
    }

    private static String bookmarkJson(String name, String valueScope, int sortOrder, String extraField) {
        return "{\"name\":\"" + name + "\",\"label\":\"" + name + " label\",\"dataType\":\"TEXT\","
                + "\"valueScope\":\"" + valueScope + "\",\"ownerRole\":\"catalogImport.manage\","
                + "\"sortOrder\":" + sortOrder + (extraField == null ? "" : "," + extraField) + "}";
    }

    /** Dezelfde configuratie als {@code SetupApiFlowTest.revisionJson()}, met een optioneel extra veld. */
    private static String revisionJson(String extraField) {
        String base = """
                {"delimiter":";","quoteChar":"\\"","charset":"UTF-8","hasHeader":true,
                 "headerLineNumber":1,"fieldReferenceKind":"HEADER_NAME",
                 "identityProfileKind":"THREE_PART","supplierField":"leverancier",
                 "supplierGroupField":"groep","supplierReferenceField":"referentie",
                 "discountCodeField":null,"basePriceField":"prijs","descriptionField":"omschrijving",
                 "currencyField":"valuta","canonicalisationVersion":2,
                 "creationThresholdSharePercent":10,"maxCriticalSharePercent":25""";
        return base + (extraField == null ? "" : "," + extraField) + "}";
    }

    private String materialiseJson(Template template, String materialisedBy) {
        return "{\"mode\":\"NEW_DEFINITION\",\"definitionCode\":\"" + template.unique() + "-DERIVED\","
                + "\"definitionName\":\"Afgeleide definitie\",\"linkCode\":\"" + template.unique() + "-DLINK\","
                + "\"linkName\":\"Afgeleide koppeling\",\"supplierOrganisationCode\":\""
                + template.supplierCode() + "\",\"bookmarkValues\":[{\"name\":\"DOELBIBLIOTHEEK\","
                + "\"value\":\"PSARF701\"}]"
                + (materialisedBy == null ? "" : ",\"materialisedBy\":\"" + materialisedBy + "\"") + "}";
    }

    private void createOrganisation(String unique) throws Exception {
        postAs(ADMIN, organisationJson(unique), SETUP + "/source-organisations")
                .andExpect(status().isCreated());
    }

    private long createDefinition(String unique, String usageType) throws Exception {
        return id(postAs(ADMIN, definitionJson(unique, usageType), SETUP + "/definitions")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private long createRevision(long definitionId) throws Exception {
        return id(postAs(ADMIN, revisionJson(null), SETUP + "/definitions/{id}/revisions", definitionId)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    /** Organisatie + definitie + één {@code DRAFT}-revisie, klaar om mappings aan toe te voegen. */
    private long draftRevision(String prefix) throws Exception {
        String unique = unique(prefix);
        createOrganisation(unique);
        return createRevision(createDefinition(unique, "OWN_DEFINITION"));
    }

    /**
     * Een sjabloon ({@code REUSABLE_TEMPLATE}) met een mapping, een filter, een kritiek-overrule en één
     * verplichte LINK-bookmark op {@code LINK_LIBRARY_CODE}, geactiveerd en dus materialiseerbaar.
     */
    private Template template(String prefix) throws Exception {
        String unique = unique(prefix);
        createOrganisation(unique);
        long definitionId = createDefinition(unique, "REUSABLE_TEMPLATE");
        long revisionId = createRevision(definitionId);
        postAs(ADMIN, "{\"targetFieldCode\":\"EAN\",\"sourceReference\":\"ean\",\"sequenceNumber\":1}",
                SETUP + "/revisions/{id}/mappings", revisionId).andExpect(status().isCreated());
        postAs(ADMIN, "{\"sourceReference\":\"groep\",\"operator\":\"EQUALS\",\"compareValue\":\"MEET\","
                + "\"outcome\":\"EXCLUDE\"}", SETUP + "/revisions/{id}/filters", revisionId)
                .andExpect(status().isCreated());
        postAs(ADMIN, "{\"fieldKey\":\"DESCRIPTION\",\"criticality\":\"CRITICAL\"}",
                SETUP + "/revisions/{id}/field-criticality", revisionId).andExpect(status().isCreated());
        postAs(ADMIN, bookmarkJson("DOELBIBLIOTHEEK", "LINK", 1, null),
                TEMPLATES + "/{d}/revisions/{r}/bookmarks", definitionId, revisionId)
                .andExpect(status().isCreated());
        postAs(ADMIN, "{\"placeKind\":\"LINK_LIBRARY_CODE\"}",
                TEMPLATES + "/{d}/revisions/{r}/bookmarks/{n}/usages", definitionId, revisionId,
                "DOELBIBLIOTHEEK").andExpect(status().isCreated());
        mockMvc.perform(post(SETUP + "/revisions/{id}/activate", revisionId).with(as(ADMIN)))
                .andExpect(status().isOk());

        String supplierCode = unique + "-SUP";
        postAs(ADMIN, organisationJson(supplierCode), SETUP + "/source-organisations")
                .andExpect(status().isCreated());
        return new Template(unique, definitionId, revisionId, supplierCode);
    }

    /**
     * Een koppeling op een definitie met een <b>actieve</b> revisie die één LINK-scope bookmark
     * declareert — de kortste weg naar {@code PUT /links/{id}/bookmark-values/{name}} (zelfde
     * fixturepatroon als {@code LinkBookmarkValueTest}).
     */
    private long linkWithLinkScopeBookmark(String prefix) {
        String unique = unique(prefix);
        SourceOrganisation organisation = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique, unique + " BV", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(
                new ImportDefinition(organisation, unique + "-DEF", unique + " catalogus", ADMIN));
        ImportDefinitionRevision revision = new ImportDefinitionRevision(definition, 1,
                IdentityProfileKind.THREE_PART, ADMIN);
        revision.setAccessConfigHash("1".repeat(64));
        revision.setStructureConfigHash("2".repeat(64));
        revision.setRecordRulesConfigHash("3".repeat(64));
        revision.setCompositeConfigHash("4".repeat(64));
        revision.setIdentitySupplierField("LEVERANCIER");
        revision.setIdentitySupplierGroupField("GROEP");
        revision.setIdentitySupplierReferenceField("REFERENTIE");
        revision.setStructureDelimiter(";");
        revision.setRecordBasePriceField("PRIJS");
        revision.setStatus(RevisionStatus.ACTIVE);
        revision = revisions.saveAndFlush(revision);
        ImportDefinitionBookmark bookmark = new ImportDefinitionBookmark(revision, "CULTUUR", "cultuur",
                BookmarkDataType.TEXT, BookmarkValueScope.LINK, "catalogImport.manage", 1);
        bookmark.setRequired(true);
        bookmarks.saveAndFlush(bookmark);
        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + " leverancier", SourceOrganisationType.SUPPLIER));
        return links.saveAndFlush(new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier,
                library(unique))).getId();
    }
}
