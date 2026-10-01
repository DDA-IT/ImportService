package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static be.dda.catalogimport.testsupport.TestActors.withoutPermissions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Bouwstap 5B-2 (bindend ontwerp {@code docs/design/fase5-perm-design.md} par. 1, 3, 5 en 7): alle overige
 * schrijfendpoints dragen een recht. MANAGE: upload, continue, bundel aanmaken/batches toevoegen/verwijderen,
 * alle {@code POST /setup/**}, {@code POST /templates/**} en {@code PUT /links/.../bookmark-values/...};
 * APPROVE: accept-baseline.
 *
 * <ul>
 *   <li><b>Regel:</b> fail-closed per actie. <b>Implementatie:</b> 403 {@code PERMISSION_DENIED} in de
 *       interceptor vóór de handler; <b>data:</b> een telling over de betrokken tabellen blijft gelijk.</li>
 *   <li><b>Regel:</b> {@code system} beheert/keurt nooit goed: 403 {@code SYSTEM_ACTOR_FORBIDDEN} vóór de
 *       rechtencheck (bewezen met een {@code system} zonder enig recht).</li>
 *   <li><b>Regel:</b> recht eerst: geen recht + verkeerde actornaam of onbekend object = 403.</li>
 *   <li><b>Regel (NT-3, beslissingslog 2026-09-30 V2 = a):</b> de schrijfpaden van de inrichting, het
 *       materialiseren en de link-PUT staan niet meer achter {@code catalogimport.setup-api.enabled}; het recht
 *       is hun enige slot. Deze klasse draait daarom bewust met de vlag <b>uit</b> (de default): elke 403
 *       hieronder bewijst tegelijk dat het pad zonder vlag bestaat. De twee declaratiepaden van sjabloonbeheer
 *       blijven achter de vlag; hun 403 met de vlag aan staat in {@code SetupApiFlagOnlyPermissionHttpTest},
 *       hun 404 zonder vlag in {@code SetupApiDisabledTest}.</li>
 * </ul>
 * Elke test bouwt een eigen keten met unieke codes: de database is gedeeld.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=50",
        "catalogimport.screening.mutation-chunk-size=50",
        "catalogimport.screening.recovery-on-startup=false",
        "spring.datasource.hikari.maximum-pool-size=4",
        // K-3: expliciet GEEN sleutelring (ook niet via een omgevingsvariabele van de ontwikkelaar), zodat de
        // credential-schrijfendpoints met recht deterministisch 409 SECRETS_NOT_CONFIGURED geven.
        "catalogimport.secrets.keys=", "catalogimport.secrets.active-key-id=",
        // K-4a: idem GEEN allowlist, zodat scan/test met recht deterministisch 404 FETCH_NOT_CONFIGURED geven.
        "catalogimport.fetch.allowed-hosts="})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class PermissionWriteEndpointsHttpTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    private static final String API = "/api/catalog-import";
    private static final String USER = "an.janssens@example.test";
    private static final String OTHER = "piet.willems@example.test";
    private static final String[] TABLES = {"source_organisation", "import_definition", "import_definition_revision",
            "import_field_mapping", "import_record_filter", "import_revision_field_criticality", "import_link",
            "catalog_import_task", "delivery", "import_batch", "publication_bundle", "publication_bundle_batch",
            "import_definition_bookmark", "import_definition_bookmark_usage", "import_link_bookmark_value",
            "issue_case", "issue_case_event", "external_credential", "external_credential_event",
            "connection_profile", "connection_profile_version", "delivery_configuration",
            "delivery_configuration_version", "delivery_configuration_file_condition", "acquisition_config_event",
            "task_run", "fetch_file_observation"};
    /** LC-2: een verder geldige body; het verwezen object bestaat niet, want het recht komt vóór elke controle. */
    private static final String PROFILE_BODY = "{\"code\":\"PERM-X\",\"name\":\"X\",\"host\":\"sftp.example.test\","
            + "\"username\":\"u\",\"authMethod\":\"PASSWORD\",\"credentialRef\":\"00000000-0000-0000-0000-000000000000\","
            + "\"hostKeyAlgorithm\":\"ssh-ed25519\",\"hostKeyFingerprintSha256\":\"SHA256:"
            + "A".repeat(43) + "\",\"reason\":\"x\"}";
    private static final String DC_BODY = "{\"code\":\"PERM-X\",\"name\":\"X\",\"connectionProfileVersionId\":1,"
            + "\"remoteDirectory\":\"/in\",\"selectionMode\":\"ALL_FILES\",\"reason\":\"x\"}";
    private static final String CREDENTIAL_BODY = "{\"label\":\"X\",\"secretKind\":\"SFTP_PASSWORD\","
            + "\"boundHost\":\"sftp.example.test\",\"secret\":\"PermTest-Geheim-1\",\"reason\":\"x\"}";
    /** K-4a: een verder geldige scan-body; deze context heeft geen allowlist, er wordt nooit iets gecontacteerd. */
    private static final String SCAN_BODY = "{\"host\":\"sftp.example.test\",\"port\":22}";

    @TempDir
    static Path archiveRoot;
    /** Eigen (lege) bronmap voor de tweede ontvangstweg: de rechtencheck hangt nooit van echte bestanden af. */
    @TempDir
    static Path localSourceRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
        registry.add("catalogimport.local-source.directory", () -> localSourceRoot.toString());
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
    private CatalogImportTaskRepository tasks;

    // --- Zonder recht: 403 PERMISSION_DENIED en niets geschreven -------------------------------------------

    /** Alle MANAGE-schrijfendpoints (upload, continue, bundels, setup, templates, link-PUT) met enkel READ. */
    @Test
    void withOnlyReadEveryManageWriteEndpointIs403AndNothingIsWritten() throws Exception {
        CatalogImportTask task = fixture(unique("DENY"));
        Object before = snapshot();

        deniedUpload(task.getId());
        // Tweede ontvangstweg (beslissingslog 2026-09-27): inlezen uit de servermap is net zo goed een
        // schrijfactie en vraagt MANAGE; zonder recht wordt er geen enkel bestand op de server aangeraakt.
        denied(post(API + "/tasks/{id}/deliveries/local-source", task.getId()),
                "{\"fileName\":\"levering.csv\"}");
        denied(post(API + "/batches/{id}/continue", 1L), "");
        denied(post(API + "/bundles"), "{\"bundleReference\":\"X\",\"targetMode\":\"SIMULATION\"}");
        denied(post(API + "/bundles/{id}/batches", 1L), "{\"batchIds\":[1]}");
        denied(post(API + "/bundles/{id}/batches/{b}/remove", 1L, 1L), "{\"reason\":\"x\"}");
        denied(post(API + "/issue-cases/{id}/status", 1L),
                "{\"newStatus\":\"CORRECTED\",\"expectedStatus\":\"AWAITING_REVIEW\",\"reason\":\"x\"}");

        denied(post(API + "/setup/source-organisations"), "{}");
        denied(post(API + "/setup/definitions"), "{}");
        denied(post(API + "/setup/definitions/{id}/revisions", 1L), "{}");
        denied(post(API + "/setup/revisions/{id}/activate", 1L), "{}");
        // E2 (S1-X-2): de opvolgrevisie kopieert een volledige configuratie; zonder MANAGE komt de
        // handler er niet aan te pas en ontstaat er geen enkele revisie- of kindrij.
        denied(post(API + "/setup/revisions/{id}/successor", 1L), "{\"changeReason\":\"x\"}");
        denied(post(API + "/setup/revisions/{id}/mappings", 1L), "{}");
        denied(post(API + "/setup/revisions/{id}/filters", 1L), "{}");
        denied(post(API + "/setup/revisions/{id}/field-criticality", 1L), "{}");
        // E3/E4 (S1-X-4): wijzigen en verwijderen binnen een DRAFT-revisie raakt drempels, prijsbeleid en
        // de aanbiedingsidentiteit; zonder MANAGE komt de handler er niet aan te pas.
        denied(patch(API + "/setup/revisions/{id}", 1L), "{\"maxCriticalSharePercent\":50}");
        denied(delete(API + "/setup/revisions/{id}/mappings/{m}", 1L, 1L), "");
        denied(delete(API + "/setup/revisions/{id}/filters/{f}", 1L, 1L), "");
        denied(post(API + "/setup/links"), "{}");
        denied(post(API + "/setup/tasks"), "{}");

        // Sjabloonbeheer (bookmarks/usages declareren) blijft achter de vlag en staat hier dus niet (vlag uit = 404);
        // zie SetupApiFlagOnlyPermissionHttpTest. Materialiseren staat sinds NT-3 niet meer achter de vlag.
        denied(post(API + "/templates/{d}/materialisations", 1L), "{}");
        denied(put(API + "/links/{id}/bookmark-values/{name}", 1L, "X"), "{\"value\":\"NL\"}");

        // Credentials (K-3, V2 = MANAGE): zonder recht geen rij en geen event.
        String ref = java.util.UUID.randomUUID().toString();
        denied(post(API + "/credentials"), CREDENTIAL_BODY);
        denied(put(API + "/credentials/{ref}/secret", ref), "{\"secret\":\"PermTest-Geheim-2\",\"reason\":\"x\"}");
        denied(post(API + "/credentials/{ref}/revoke", ref), "{\"reason\":\"x\"}");

        // LC-2 (L7a): profielen, Leveringsconfiguraties en taakkoppeling vragen MANAGE; zonder recht geen rij en geen event.
        denied(post(API + "/connection-profiles"), PROFILE_BODY);
        denied(post(API + "/delivery-configurations"), DC_BODY);
        denied(put(API + "/tasks/{id}/delivery-configuration", task.getId()), "{\"versionId\":1,\"reason\":\"x\"}");
        denied(delete(API + "/tasks/{id}/delivery-configuration", task.getId()), "{\"reason\":\"x\"}");

        // K-4a (L7): scan en verbindingstests vragen MANAGE; zonder recht geen verbinding en geen event.
        denied(post(API + "/connection-profiles/host-key-scan"), SCAN_BODY);
        denied(post(API + "/connection-profile-versions/{id}/test", 1L), "");
        denied(post(API + "/delivery-configuration-versions/{id}/test", 1L), "");

        // K-4b: "Nu ophalen" vraagt MANAGE; zonder recht geen run, geen waarneming, geen levering.
        denied(post(API + "/tasks/{id}/fetch-runs", task.getId()), "");

        // K-4c: een run afbreken vraagt MANAGE; zonder recht blijft de run ongemoeid.
        denied(post(API + "/task-runs/{id}/abort", 1L), "{\"reason\":\"x\"}");

        // NT-9: de proefinlezing vraagt MANAGE; zonder recht wordt het bestand niet eens gelezen (ook een
        // bestaande revisie geeft dan 403, geen 200 en geen 404).
        deniedTrialRead(task);

        assertThat(snapshot()).isEqualTo(before);
    }

    /** accept-baseline vraagt APPROVE: MANAGE en READ zijn 403, de batch blijft ongewijzigd. */
    @Test
    void acceptBaselineNeedsApproveAndManageIsNotEnough() throws Exception {
        Uploaded u = uploaded("ACC");

        json(post(API + "/batches/{id}/accept-baseline", u.batchId()), as(USER, Permission.MANAGE),
                "{\"reason\":\"Nulmeting\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        json(post(API + "/batches/{id}/accept-baseline", u.batchId()), as(USER, Permission.READ),
                "{\"reason\":\"Nulmeting\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        assertThat(batchStatus(u.batchId())).isNotEqualTo("BASELINE_ACCEPTED");

        json(post(API + "/batches/{id}/accept-baseline", u.batchId()), as(USER, Permission.APPROVE),
                "{\"reason\":\"Nulmeting\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("BASELINE_ACCEPTED"));
        assertThat(batchStatus(u.batchId())).isEqualTo("BASELINE_ACCEPTED");
    }

    // --- Met recht en hiërarchie: ongewijzigd gedrag -----------------------------------------------------------

    /** MANAGE volstaat voor upload, bundel aanmaken en batches toevoegen; APPROVE impliceert MANAGE. */
    @Test
    void manageAndApproveMayUploadAndAssembleBundles() throws Exception {
        CatalogImportTask task = fixture(unique("OK"));

        String body = multipartUpload(task.getId(), unique("OKREF"), as(USER, Permission.MANAGE))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long batchId = ((Number) JsonPath.read(body, "$.batchId")).longValue();
        multipartUpload(task.getId(), unique("OKREF2"), as(USER, Permission.APPROVE))
                .andExpect(status().isCreated());

        String created = json(post(API + "/bundles"), as(USER, Permission.MANAGE),
                "{\"bundleReference\":\"BND-" + unique("OKB") + "\",\"targetMode\":\"SIMULATION\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long bundleId = ((Number) JsonPath.read(created, "$.id")).longValue();
        json(post(API + "/bundles/{id}/batches", bundleId), as(USER, Permission.APPROVE),
                "{\"batchIds\":[" + batchId + "]}").andExpect(status().isOk());
        json(post(API + "/bundles/{id}/batches/{b}/remove", bundleId, batchId), as(USER, Permission.MANAGE),
                "{\"reason\":\"Vergissing\"}").andExpect(status().isOk());
    }

    /** Setup, template en link-PUT: met MANAGE ongewijzigd gedrag, ook met de vlag uit (NT-3). */
    @Test
    void withManageTheSetupEndpointsStillWork() throws Exception {
        String code = unique("SETUP");

        json(post(API + "/setup/source-organisations"), as(USER, Permission.MANAGE),
                "{\"code\":\"" + code + "\",\"name\":\"" + code + " BV\",\"type\":\"SUPPLIER\"}")
                .andExpect(status().isCreated());
        // Recht eerst, dan 404: met MANAGE bestaat de revisie nog steeds niet.
        json(post(API + "/setup/revisions/{id}/activate", 999_999_999L), as(USER, Permission.MANAGE), "{}")
                .andExpect(status().isNotFound());
        // Zelfde volgorde voor E2 (S1-X-2): met MANAGE is het antwoord de gewone 404 van een onbekende
        // revisie, niet 403 — en zeker niet de 400 over de ontbrekende wijzigingsreden van een revisie
        // die niet bestaat.
        json(post(API + "/setup/revisions/{id}/successor", 999_999_999L), as(USER, Permission.MANAGE),
                "{\"changeReason\":\"Opvolger van een onbestaande revisie\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_FOUND"));
        // E3/E4 (S1-X-4): ook hier recht eerst, dan 404 — een PATCH of DELETE op een onbestaande revisie
        // geeft met MANAGE de gewone REVISION_NOT_FOUND, nooit 403 en nooit stil 200.
        json(patch(API + "/setup/revisions/{id}", 999_999_999L), as(USER, Permission.MANAGE),
                "{\"maxCriticalSharePercent\":50}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_FOUND"));
        json(delete(API + "/setup/revisions/{id}/mappings/{m}", 999_999_999L, 1L),
                as(USER, Permission.MANAGE), "")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_FOUND"));
        json(delete(API + "/setup/revisions/{id}/filters/{f}", 999_999_999L, 1L),
                as(USER, Permission.MANAGE), "")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_FOUND"));
        // Zelfde volgorde als hierboven voor het link-PUT-endpoint.
        json(put(API + "/links/{id}/bookmark-values/{name}", 999_999_999L, "X"), as(USER, Permission.MANAGE),
                "{\"value\":\"NL\"}").andExpect(status().isNotFound());
    }

    // --- system: vóór de rechtencheck ----------------------------------------------------------------------------

    @Test
    void systemIsRefusedOnManageAndApproveEndpointsBeforeTheRightsCheck() throws Exception {
        CatalogImportTask task = fixture(unique("SYS"));
        Object before = snapshot();

        multipartUpload(task.getId(), unique("SYSREF"), withoutPermissions("system"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));
        forbiddenSystem(post(API + "/tasks/{id}/deliveries/local-source", task.getId()),
                "{\"fileName\":\"levering.csv\"}");
        forbiddenSystem(post(API + "/batches/{id}/continue", 1L), "");
        forbiddenSystem(post(API + "/batches/{id}/accept-baseline", 1L), "{\"reason\":\"x\"}");
        forbiddenSystem(post(API + "/bundles"), "{\"bundleReference\":\"X\",\"targetMode\":\"SIMULATION\"}");
        forbiddenSystem(post(API + "/issue-cases/{id}/status", 1L),
                "{\"newStatus\":\"CORRECTED\",\"expectedStatus\":\"AWAITING_REVIEW\",\"reason\":\"x\"}");
        forbiddenSystem(post(API + "/setup/definitions"), "{}");
        forbiddenSystem(post(API + "/setup/revisions/{id}/successor", 1L), "{\"changeReason\":\"x\"}");
        forbiddenSystem(patch(API + "/setup/revisions/{id}", 1L), "{\"maxCriticalSharePercent\":50}");
        forbiddenSystem(delete(API + "/setup/revisions/{id}/mappings/{m}", 1L, 1L), "");
        forbiddenSystem(delete(API + "/setup/revisions/{id}/filters/{f}", 1L, 1L), "");
        forbiddenSystem(post(API + "/templates/{d}/materialisations", 1L), "{}");
        forbiddenSystem(put(API + "/links/{id}/bookmark-values/{name}", 1L, "X"), "{\"value\":\"NL\"}");
        forbiddenSystem(post(API + "/credentials"), CREDENTIAL_BODY);
        forbiddenSystem(post(API + "/credentials/{ref}/revoke", java.util.UUID.randomUUID().toString()),
                "{\"reason\":\"x\"}");
        forbiddenSystem(post(API + "/connection-profiles"), PROFILE_BODY);
        forbiddenSystem(post(API + "/delivery-configurations"), DC_BODY);
        forbiddenSystem(put(API + "/tasks/{id}/delivery-configuration", task.getId()),
                "{\"versionId\":1,\"reason\":\"x\"}");
        forbiddenSystem(delete(API + "/tasks/{id}/delivery-configuration", task.getId()), "{\"reason\":\"x\"}");
        forbiddenSystem(post(API + "/connection-profiles/host-key-scan"), SCAN_BODY);
        forbiddenSystem(post(API + "/connection-profile-versions/{id}/test", 1L), "");
        forbiddenSystem(post(API + "/delivery-configuration-versions/{id}/test", 1L), "");
        forbiddenSystem(post(API + "/tasks/{id}/fetch-runs", task.getId()), "");
        forbiddenSystem(post(API + "/task-runs/{id}/abort", 1L), "{\"reason\":\"x\"}");
        // NT-9: ook de proefinlezing is een MANAGE-actie en dus nooit voor system.
        this.mockMvc.perform(trialRead(revisionIdOf(task)).with(withoutPermissions("system")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));

        assertThat(snapshot()).isEqualTo(before);
    }

    // --- LC-2: recht eerst, daarna 400/404/409 --------------------------------------------------------------------

    /**
     * Zonder recht 403, ook met een ongeldige body of een onbekend object; met MANAGE de gewone uitkomst van de
     * service (400 invoer, 404 onbekend). Nergens wordt iets geschreven.
     */
    @Test
    void acquisitionConfigWritesAreRightsFirst() throws Exception {
        Object before = snapshot();
        long unknown = 999_999_999L;

        json(post(API + "/connection-profiles"), as(USER, Permission.READ), "{}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        json(post(API + "/connection-profiles"), as(USER, Permission.MANAGE), "{}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONNECTION_PROFILE_CODE_INVALID"));
        json(post(API + "/connection-profiles"), as(USER, Permission.MANAGE), PROFILE_BODY)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CREDENTIAL_NOT_FOUND"));

        json(post(API + "/delivery-configurations"), as(USER, Permission.READ), "{}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        json(post(API + "/delivery-configurations"), as(USER, Permission.MANAGE), "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DELIVERY_CONFIGURATION_CODE_INVALID"));
        json(post(API + "/delivery-configurations"), as(USER, Permission.MANAGE),
                DC_BODY.replace("\"connectionProfileVersionId\":1", "\"connectionProfileVersionId\":" + unknown))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONNECTION_PROFILE_VERSION_NOT_FOUND"));

        json(put(API + "/tasks/{id}/delivery-configuration", unknown), as(USER, Permission.READ),
                "{\"versionId\":1,\"reason\":\"x\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        json(put(API + "/tasks/{id}/delivery-configuration", unknown), as(USER, Permission.MANAGE),
                "{\"versionId\":1,\"reason\":\"x\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
        json(delete(API + "/tasks/{id}/delivery-configuration", unknown), withoutPermissions(USER), "")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        json(delete(API + "/tasks/{id}/delivery-configuration", unknown), as(USER, Permission.MANAGE),
                "{\"reason\":\"x\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));

        // K-4a: zonder recht 403 (ook met een ongeldige body); met MANAGE eerst de invoer (400), dan in deze context
        // zonder allowlist 404 FETCH_NOT_CONFIGURED (bij de tests vóór de bestaanscontrole); nooit een event.
        json(post(API + "/connection-profiles/host-key-scan"), as(USER, Permission.READ), "{}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        json(post(API + "/connection-profiles/host-key-scan"), as(USER, Permission.MANAGE), "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONNECTION_PROFILE_HOST_INVALID"));
        json(post(API + "/connection-profiles/host-key-scan"), as(USER, Permission.MANAGE), SCAN_BODY)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FETCH_NOT_CONFIGURED"));
        json(post(API + "/connection-profile-versions/{id}/test", unknown), as(USER, Permission.READ), "")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        json(post(API + "/connection-profile-versions/{id}/test", unknown), as(USER, Permission.MANAGE), "")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FETCH_NOT_CONFIGURED"));
        json(post(API + "/delivery-configuration-versions/{id}/test", unknown), withoutPermissions(USER), "")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        json(post(API + "/delivery-configuration-versions/{id}/test", unknown), as(USER, Permission.APPROVE), "")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FETCH_NOT_CONFIGURED"));

        // K-4b: "Nu ophalen" - zonder recht 403; met MANAGE in deze context zonder allowlist 404 FETCH_NOT_CONFIGURED,
        // vóór de bestaanscontrole van de taak (de functie bestaat hier niet); er ontstaat geen run.
        json(post(API + "/tasks/{id}/fetch-runs", unknown), as(USER, Permission.READ), "")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        json(post(API + "/tasks/{id}/fetch-runs", unknown), as(USER, Permission.MANAGE), "")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FETCH_NOT_CONFIGURED"));

        // K-4c: afbreken - zonder recht 403; met MANAGE een onbekende run 404 (ook zonder allowlist: herstel hangt er niet van af).
        json(post(API + "/task-runs/{id}/abort", unknown), as(USER, Permission.READ), "{\"reason\":\"x\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        json(post(API + "/task-runs/{id}/abort", unknown), as(USER, Permission.MANAGE), "{\"reason\":\"x\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TASK_RUN_NOT_FOUND"));

        assertThat(snapshot()).isEqualTo(before);
    }

    // --- Credentials (K-3): recht eerst, daarna 400/404/409; zonder sleutelring 409 -------------------------------

    /**
     * Deze context heeft bewust geen sleutelring. Zonder recht 403 (ook met een ongeldige body of onbekende ref);
     * met MANAGE eerst de invoerfout (400), dan de ontbrekende configuratie (409 SECRETS_NOT_CONFIGURED) bij
     * aanmaken/vervangen. Intrekken versleutelt niets en werkt zonder sleutelring (A8): een onbekende ref is dan
     * gewoon 404. Nergens wordt iets geschreven.
     */
    @Test
    void credentialWritesAreRightsFirstAndRefuseWithoutAKeyRing() throws Exception {
        Object before = snapshot();
        String unknown = java.util.UUID.randomUUID().toString();

        json(post(API + "/credentials"), as(USER, Permission.READ), "{}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        json(post(API + "/credentials"), as(USER, Permission.MANAGE), "{}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CREDENTIAL_LABEL_REQUIRED"));
        json(post(API + "/credentials"), as(USER, Permission.MANAGE), CREDENTIAL_BODY)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SECRETS_NOT_CONFIGURED"));
        json(post(API + "/credentials"), as(USER, Permission.APPROVE), CREDENTIAL_BODY)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SECRETS_NOT_CONFIGURED"));

        json(put(API + "/credentials/{ref}/secret", unknown), as(USER, Permission.READ),
                "{\"secret\":\"PermTest-Geheim-2\",\"reason\":\"x\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        json(put(API + "/credentials/{ref}/secret", unknown), as(USER, Permission.MANAGE),
                "{\"secret\":\"PermTest-Geheim-2\",\"reason\":\"x\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SECRETS_NOT_CONFIGURED"));

        json(post(API + "/credentials/{ref}/revoke", unknown), as(USER, Permission.READ), "{\"reason\":\"x\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        json(post(API + "/credentials/{ref}/revoke", unknown), as(USER, Permission.MANAGE), "{\"reason\":\"x\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CREDENTIAL_NOT_FOUND"));

        assertThat(snapshot()).isEqualTo(before);
    }

    // --- Volgorde: recht eerst -------------------------------------------------------------------------------------

    @Test
    void aMissingRightOutweighsAWrongActorNameAndAnUnknownObject() throws Exception {
        long unknown = 999_999_999L;

        // Verkeerde actornaam: zonder recht 403, mét recht 400 ACTOR_FIELD_MISMATCH.
        String wrongName = "{\"bundleReference\":\"X\",\"targetMode\":\"SIMULATION\",\"createdBy\":\"" + OTHER + "\"}";
        json(post(API + "/bundles"), as(USER, Permission.READ), wrongName)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        json(post(API + "/bundles"), as(USER, Permission.MANAGE), wrongName)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ACTOR_FIELD_MISMATCH"));

        // Onbekend object: zonder recht 403, mét recht 404.
        json(post(API + "/batches/{id}/continue", unknown), as(USER, Permission.READ), "")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        json(post(API + "/batches/{id}/continue", unknown), as(USER, Permission.MANAGE), "")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BATCH_NOT_FOUND"));
        json(post(API + "/batches/{id}/accept-baseline", unknown), as(USER, Permission.MANAGE),
                "{\"reason\":\"x\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        json(post(API + "/issue-cases/{id}/status", unknown), as(USER, Permission.READ),
                "{\"newStatus\":\"CORRECTED\",\"expectedStatus\":\"AWAITING_REVIEW\",\"reason\":\"x\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        json(post(API + "/issue-cases/{id}/status", unknown), as(USER, Permission.MANAGE),
                "{\"newStatus\":\"CORRECTED\",\"expectedStatus\":\"AWAITING_REVIEW\",\"reason\":\"x\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ISSUE_CASE_NOT_FOUND"));
        json(post(API + "/bundles/{id}/batches", unknown), withoutPermissions(USER), "{\"batchIds\":[1]}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));

        // Tweede ontvangstweg: zonder recht 403, mét MANAGE de gewone uitkomst van een onbekend bestand
        // (404 LOCAL_SOURCE_FILE_NOT_FOUND) - nooit 403, en de taak-404 komt er niet eens aan te pas.
        String body = "{\"fileName\":\"bestaat-niet.csv\"}";
        json(post(API + "/tasks/{id}/deliveries/local-source", unknown), as(USER, Permission.READ), body)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        json(post(API + "/tasks/{id}/deliveries/local-source", unknown), as(USER, Permission.MANAGE), body)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOCAL_SOURCE_FILE_NOT_FOUND"));
    }

    // --- Helpers -----------------------------------------------------------------------------------------------------

    private void denied(MockHttpServletRequestBuilder request, String body) throws Exception {
        json(request, as(USER, Permission.READ), body)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    private void forbiddenSystem(MockHttpServletRequestBuilder request, String body) throws Exception {
        json(request, withoutPermissions("system"), body)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));
    }

    /** NT-9: met enkel READ 403 PERMISSION_DENIED, ook voor een bestaande revisie met een geldig bestand. */
    private void deniedTrialRead(CatalogImportTask task) throws Exception {
        this.mockMvc.perform(trialRead(revisionIdOf(task)).with(as(USER, Permission.READ)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder trialRead(
            long revisionId) {
        return multipart(API + "/revisions/{id}/trial-reads", revisionId)
                .file(new MockMultipartFile("file", "levering.csv", "text/csv",
                        (HEADER + "ACME;G1;R1;10,00;Artikel 1\n").getBytes(StandardCharsets.UTF_8)));
    }

    private long revisionIdOf(CatalogImportTask task) {
        return this.jdbc.queryForObject("select r.id from import_definition_revision r "
                + "join import_link l on l.import_definition_id = r.import_definition_id "
                + "join catalog_import_task t on t.import_link_id = l.id where t.id = ?", Long.class, task.getId());
    }

    private void deniedUpload(long taskId) throws Exception {
        multipartUpload(taskId, unique("DENYREF"), as(USER, Permission.READ))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    private ResultActions json(MockHttpServletRequestBuilder request, RequestPostProcessor actor, String body)
            throws Exception {
        return this.mockMvc.perform(request.with(actor).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions multipartUpload(long taskId, String reference, RequestPostProcessor actor)
            throws Exception {
        return this.mockMvc.perform(multipart(API + "/tasks/{id}/deliveries", taskId)
                .file(new MockMultipartFile("file", "levering.csv", "text/csv",
                        (HEADER + "ACME;G1;R1;10,00;Artikel 1\n").getBytes(StandardCharsets.UTF_8)))
                .param("deliveryReference", reference).with(actor));
    }

    /** Tellingen over alle tabellen die een schrijfendpoint kan raken; gelijk voor en na = niets geschreven. */
    private List<Long> snapshot() {
        return java.util.Arrays.stream(TABLES)
                .map(table -> this.jdbc.queryForObject("select count(*) from " + table, Long.class)).toList();
    }

    private String batchStatus(long batchId) {
        return this.jdbc.queryForObject("select status from import_batch where id = ?", String.class, batchId);
    }

    private Uploaded uploaded(String prefix) throws Exception {
        CatalogImportTask task = fixture(unique(prefix));
        String body = multipartUpload(task.getId(), unique(prefix + "REF"), as(USER, Permission.MANAGE))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Uploaded(((Number) JsonPath.read(body, "$.batchId")).longValue());
    }

    private static String unique(String prefix) {
        return "PW" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
    }

    private CatalogImportTask fixture(String unique) {
        SourceOrganisation organisation = this.sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + "-ORG BV", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = this.definitions.saveAndFlush(
                new ImportDefinition(organisation, unique + "-DEF", unique + " catalogus", "beheerder@example.test"));
        ImportDefinitionRevision revision = new ImportDefinitionRevision(definition, 1, IdentityProfileKind.THREE_PART,
                "beheerder@example.test");
        revision.setAccessConfigHash("1".repeat(64));
        revision.setStructureConfigHash("2".repeat(64));
        revision.setRecordRulesConfigHash("3".repeat(64));
        revision.setCompositeConfigHash("4".repeat(64));
        revision.setIdentitySupplierField("LEVERANCIER");
        revision.setIdentitySupplierGroupField("GROEP");
        revision.setIdentitySupplierReferenceField("REFERENTIE");
        revision.setStructureDelimiter(";");
        revision.setRecordBasePriceField("PRIJS");
        revision.setRecordDescriptionField("OMSCHRIJVING");
        revision.setMaxCriticalSharePercent(new BigDecimal("100"));
        revision.setStatus(RevisionStatus.ACTIVE);
        this.revisions.saveAndFlush(revision);
        SourceOrganisation supplier = this.sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        ImportLink link = this.links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, "PSARF050"));
        return this.tasks.saveAndFlush(new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL));
    }

    private record Uploaded(long batchId) {
    }
}
