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
 *   <li><b>Regel (par. 5):</b> de setup-vlag blijft de buitenste beveiliging. Hier staat ze aan: zonder
 *       {@code READ} 403. Vlag uit = 404 blijft in {@code SetupApiDisabledTest}.</li>
 * </ul>
 * Enkel lezen: er wordt niets geschreven, de database wordt niet gedeeld-vervuild.
 */
@SpringBootTest(properties = {"catalogimport.setup-api.enabled=true",
        "catalogimport.screening.recovery-on-startup=false"})
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
                API + "/import-links", API + "/tasks", API + "/setup/overview", API + "/templates",
                API + "/source-organisations", API + "/definitions");
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
                API + "/definitions/" + UNKNOWN + "/revisions");
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
