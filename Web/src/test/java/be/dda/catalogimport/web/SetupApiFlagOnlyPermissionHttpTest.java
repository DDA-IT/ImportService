package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static be.dda.catalogimport.testsupport.TestActors.withoutPermissions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * De drie paden die sinds NT-3 nog als enige achter {@code catalogimport.setup-api.enabled} staan
 * ({@code GET /setup/overview}, {@code POST .../bookmarks}, {@code POST .../bookmarks/{name}/usages} —
 * beslissingslog 2026-09-30, V2 = a): met de vlag <b>aan</b> is het recht de binnenste beveiliging.
 * <ul>
 *   <li>{@code GET /setup/overview}: zonder recht 403 {@code PERMISSION_DENIED}, met {@code READ} 200.</li>
 *   <li>De twee declaratiepaden: met enkel {@code READ} 403 en niets geschreven; {@code system} 403
 *       {@code SYSTEM_ACTOR_FORBIDDEN} vóór de rechtencheck.</li>
 * </ul>
 * Zonder vlag geven dezelfde paden 404 ({@code SetupApiDisabledTest}). Deze controles stonden vóór NT-3 in
 * {@code PermissionReadEndpointsHttpTest}/{@code PermissionWriteEndpointsHttpTest}; die draaien nu met de vlag
 * uit, zodat ze de achter de vlag vandaan gehaalde paden in hun echte (productie)toestand bewijzen.
 * <p>
 * Letterlijk dezelfde annotaties als {@code RevisionSuccessorTest}/{@code RevisionUpdateTest}: Spring deelt zo
 * hun applicatiecontext en hun uitdrukkelijk kleine verbindingspool.
 */
@SpringBootTest(properties = {"catalogimport.setup-api.enabled=true",
        "spring.datasource.hikari.maximum-pool-size=4"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class SetupApiFlagOnlyPermissionHttpTest {

    private static final String API = "/api/catalog-import";
    private static final String USER = "an.janssens@example.test";
    private static final String[] TABLES = {"import_definition_bookmark", "import_definition_bookmark_usage"};

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theOverviewNeedsReadWhenTheFlagIsOn() throws Exception {
        mockMvc.perform(get(API + "/setup/overview").with(withoutPermissions(USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        mockMvc.perform(get(API + "/setup/overview").with(as(USER, Permission.READ)))
                .andExpect(status().isOk());
    }

    @Test
    void theTemplateDeclarationPathsNeedManageAndSystemIsRefusedFirst() throws Exception {
        List<Long> before = snapshot();
        for (MockHttpServletRequestBuilder declaration : declarations()) {
            mockMvc.perform(declaration.with(as(USER, Permission.READ)).contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
        for (MockHttpServletRequestBuilder declaration : declarations()) {
            mockMvc.perform(declaration.with(withoutPermissions("system")).contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    private static List<MockHttpServletRequestBuilder> declarations() {
        return List.of(post(API + "/templates/{d}/revisions/{r}/bookmarks", 1L, 1L),
                post(API + "/templates/{d}/revisions/{r}/bookmarks/{n}/usages", 1L, 1L, "X"));
    }

    private List<Long> snapshot() {
        return java.util.Arrays.stream(TABLES)
                .map(table -> jdbc.queryForObject("select count(*) from " + table, Long.class)).toList();
    }
}
