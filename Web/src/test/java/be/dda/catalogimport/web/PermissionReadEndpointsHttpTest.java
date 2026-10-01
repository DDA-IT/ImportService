package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static be.dda.catalogimport.testsupport.TestActors.withoutPermissions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.List;
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
 * Bouwstap 5B-3 (bindend ontwerp {@code docs/design/fase5-perm-design.md} par. 1, 3, 5 en 7): alle GET's
 * vragen {@code READ}; {@code GET /me} is uitgezonderd.
 *
 * <ul>
 *   <li><b>Regel:</b> lezen vraagt {@code READ} (of meer via de hiërarchie). <b>Implementatie:</b> 403
 *       {@code PERMISSION_DENIED} in de interceptor, per endpointfamilie bewezen.</li>
 *   <li><b>Regel:</b> recht eerst. <b>Implementatie:</b> een onbekend object geeft zonder recht 403, niet
 *       404; met recht wél de gewone service-uitkomst (dus nooit 403).</li>
 *   <li><b>Regel (NT-3, beslissingslog 2026-09-30 V2 = a):</b> sjablonen lezen en de bookmarkwaarden van een
 *       koppeling staan niet meer achter {@code catalogimport.setup-api.enabled}; {@code READ} is hun enige slot.
 *       Deze klasse draait daarom bewust met de vlag <b>uit</b> (de default). {@code GET /setup/overview} blijft
 *       achter de vlag en staat hier dus niet: 403/200 met de vlag aan in
 *       {@code SetupApiFlagOnlyPermissionHttpTest}, 404 zonder vlag in {@code SetupApiDisabledTest}.</li>
 * </ul>
 * Enkel lezen: er wordt niets geschreven, de database wordt niet gedeeld-vervuild.
 */
@SpringBootTest(properties = {"catalogimport.screening.recovery-on-startup=false"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class PermissionReadEndpointsHttpTest {

    private static final String API = "/api/catalog-import";
    private static final String USER = "an.janssens@example.test";
    private static final long UNKNOWN = 999_999_999L;

    @TempDir
    static Path archiveRoot;
    /** Eigen (lege) bronmap: de rechtencheck moet niet van de map van de ontwikkelaar afhangen. */
    @TempDir
    static Path localSourceRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
        registry.add("catalogimport.local-source.directory", () -> localSourceRoot.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    /** Lijsten: zonder recht 403, met READ 200. */
    private static List<String> lists() {
        return List.of(API + "/batches", API + "/batches/summary", API + "/bundles", API + "/bundles/candidates",
                API + "/import-links", API + "/tasks", API + "/templates",
                API + "/source-organisations", API + "/definitions", API + "/issue-cases",
                API + "/issue-cases/summary");
    }

    /** Endpoints op een onbekend object: zonder recht 403 (recht eerst), met recht de service-uitkomst. */
    private static List<String> byUnknownId() {
        return List.of(API + "/batches/" + UNKNOWN, API + "/batches/" + UNKNOWN + "/mutations",
                API + "/batches/" + UNKNOWN + "/issues", API + "/batches/" + UNKNOWN + "/issue-groups",
                API + "/bundles/" + UNKNOWN, API + "/bundles/" + UNKNOWN + "/batches",
                API + "/bundles/" + UNKNOWN + "/mutations", API + "/bundles/" + UNKNOWN + "/decisions",
                API + "/bundles/" + UNKNOWN + "/freeze-check", API + "/bundles/" + UNKNOWN + "/psimport-preview",
                API + "/deliveries/" + UNKNOWN,
                API + "/templates/" + UNKNOWN + "/revisions/" + UNKNOWN + "/bookmarks",
                API + "/templates/" + UNKNOWN + "/materialisations",
                API + "/links/" + UNKNOWN + "/bookmark-values",
                // NT-8: gereedheidscontrole van een koppeling (READ).
                API + "/import-links/" + UNKNOWN + "/readiness",
                API + "/definitions/" + UNKNOWN + "/revisions",
                API + "/definitions/" + UNKNOWN + "/revisions/" + UNKNOWN,
                API + "/issue-cases/" + UNKNOWN, API + "/issue-cases/" + UNKNOWN + "/observations",
                API + "/issue-cases/" + UNKNOWN + "/events",
                // K-4b (design leveringsconfiguratie par. 6, L7b): runlijst en rundetail vragen READ.
                API + "/tasks/" + UNKNOWN + "/runs", API + "/task-runs/" + UNKNOWN);
    }

    /** K-4b: met READ geven runlijst en rundetail van een onbekend object de eigen 404-codes. */
    @Test
    void withReadTheRunReadsOfAnUnknownObjectAre404WithTheirOwnCode() throws Exception {
        mockMvc.perform(get(API + "/tasks/" + UNKNOWN + "/runs").with(as(USER, Permission.READ)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
        mockMvc.perform(get(API + "/task-runs/" + UNKNOWN).with(as(USER, Permission.READ)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TASK_RUN_NOT_FOUND"));
        // NT-8: de gereedheidscontrole van een onbekende koppeling.
        mockMvc.perform(get(API + "/import-links/" + UNKNOWN + "/readiness").with(as(USER, Permission.READ)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"));
    }

    @Test
    void withoutAnyRightEveryGetFamilyIs403PermissionDenied() throws Exception {
        for (String path : concat(lists(), byUnknownId())) {
            mockMvc.perform(get(path).with(withoutPermissions(USER)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
    }

    @Test
    void theDenialNamesOnlyTheReadRightCode() throws Exception {
        String body = mockMvc.perform(get(API + "/batches").with(withoutPermissions(USER)))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("catalogImport.read").doesNotContain("catalogImport.manage")
                .doesNotContain("catalogImport.approve").doesNotContain("test-sub-").doesNotContain(USER);
    }

    @Test
    void withReadTheListsAnswer200() throws Exception {
        for (String path : lists()) {
            mockMvc.perform(get(path).with(as(USER, Permission.READ))).andExpect(status().isOk());
        }
    }

    /** Recht eerst, spiegelbeeld: met recht is een onbekend object nooit 403 maar de gewone 4xx uit de service. */
    @Test
    void withReadAnUnknownObjectIsNeverA403() throws Exception {
        for (String path : byUnknownId()) {
            int status = mockMvc.perform(get(path).with(as(USER, Permission.READ))).andReturn().getResponse()
                    .getStatus();
            assertThat(status).as(path).isNotEqualTo(403).isNotEqualTo(401).isLessThan(500);
        }
    }

    /** Hiërarchie: MANAGE alleen en APPROVE alleen mogen lezen. */
    @Test
    void manageOnlyAndApproveOnlyMayRead() throws Exception {
        for (Permission permission : new Permission[] {Permission.MANAGE, Permission.APPROVE}) {
            for (String path : lists()) {
                mockMvc.perform(get(path).with(as(USER, permission))).andExpect(status().isOk());
            }
        }
    }

    /** {@code system} mag lezen mét READ (geen SYSTEM_ACTOR_FORBIDDEN op een GET) en niet zonder. */
    @Test
    void systemMayReadOnlyWithRead() throws Exception {
        mockMvc.perform(get(API + "/batches").with(as("system", Permission.READ))).andExpect(status().isOk());
        mockMvc.perform(get(API + "/batches").with(withoutPermissions("system")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    /**
     * Tweede ontvangstweg, lijst-endpoint (beslissingslog 2026-09-27, D8): dit is de enige GET die
     * {@code MANAGE} vraagt en niet {@code READ} — hij onthult de inhoud van een serverdirectory. Daarom
     * staat hij bewust <b>niet</b> in {@link #lists()}: met alleen {@code READ} moet hij 403 geven.
     */
    @Test
    void theLocalSourceListingNeedsManageAndReadIsNotEnough() throws Exception {
        String path = API + "/local-source/files";

        mockMvc.perform(get(path).with(withoutPermissions(USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        mockMvc.perform(get(path).with(as(USER, Permission.READ)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));

        mockMvc.perform(get(path).with(as(USER, Permission.MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.files").isArray());
    }

    /**
     * Credentials (K-3; design leveringsconfiguratie par. 6, beslissingslog 2026-09-29 L7b): de drie GET's vragen
     * {@code MANAGE}, niet {@code READ} — elk antwoord toont {@code boundHost}. Daarom staan ze bewust niet in
     * {@link #lists()}/{@link #byUnknownId()}. Lezen werkt ook zonder sleutelring (deze context heeft er geen nodig).
     */
    @Test
    void theCredentialReadsNeedManageAndReadIsNotEnough() throws Exception {
        String unknown = java.util.UUID.randomUUID().toString();
        List<String> paths = List.of(API + "/credentials", API + "/credentials/" + unknown,
                API + "/credentials/" + unknown + "/events");
        for (String path : paths) {
            mockMvc.perform(get(path).with(withoutPermissions(USER)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
            mockMvc.perform(get(path).with(as(USER, Permission.READ)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }

        mockMvc.perform(get(API + "/credentials").with(as(USER, Permission.MANAGE)))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mockMvc.perform(get(API + "/credentials").with(as(USER, Permission.APPROVE))).andExpect(status().isOk());
        // Recht eerst, spiegelbeeld: met MANAGE is een onbekende ref de gewone 404, nooit 403.
        for (String path : paths.subList(1, 3)) {
            mockMvc.perform(get(path).with(as(USER, Permission.MANAGE)))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CREDENTIAL_NOT_FOUND"));
        }
    }

    /**
     * Verbindingsprofielen en Leveringsconfiguraties (LC-2; design leveringsconfiguratie par. 6, L7b): lijst en detail
     * vragen {@code MANAGE}, zoals de credentials - het detail toont host, login en map. Daarom staan ze bewust niet in
     * {@link #lists()}/{@link #byUnknownId()}. Recht eerst: een onbekend id is zonder recht 403, met MANAGE 404.
     */
    @Test
    void theProfileAndDeliveryConfigurationReadsNeedManageAndReadIsNotEnough() throws Exception {
        for (String base : List.of(API + "/connection-profiles", API + "/delivery-configurations")) {
            for (String path : List.of(base, base + "/" + UNKNOWN)) {
                mockMvc.perform(get(path).with(withoutPermissions(USER)))
                        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
                mockMvc.perform(get(path).with(as(USER, Permission.READ)))
                        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
            }
            mockMvc.perform(get(base).with(as(USER, Permission.MANAGE)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
            mockMvc.perform(get(base).with(as(USER, Permission.APPROVE))).andExpect(status().isOk());
            mockMvc.perform(get(base + "/" + UNKNOWN).with(as(USER, Permission.MANAGE)))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(get(API + "/connection-profiles/" + UNKNOWN).with(as(USER, Permission.MANAGE)))
                .andExpect(jsonPath("$.code").value("CONNECTION_PROFILE_NOT_FOUND"));
        mockMvc.perform(get(API + "/delivery-configurations/" + UNKNOWN).with(as(USER, Permission.MANAGE)))
                .andExpect(jsonPath("$.code").value("DELIVERY_CONFIGURATION_NOT_FOUND"));
    }

    /** {@code /me} is uitgezonderd: een rechtenloze gebruiker krijgt 200. De inhoud van {@code permissions} is 5B-4. */
    @Test
    void meIsOpenToAUserWithoutRights() throws Exception {
        mockMvc.perform(get(API + "/me").with(withoutPermissions(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(USER));
    }

    private static List<String> concat(List<String> first, List<String> second) {
        return java.util.stream.Stream.concat(first.stream(), second.stream()).toList();
    }
}
