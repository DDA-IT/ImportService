package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.testsupport.FakeProdisServer;
import be.dda.catalogimport.testsupport.FakeProdisServer.Response;
import be.dda.catalogimport.testsupport.TestPermissionSource;
import be.dda.catalogimport.testsupport.TestSecurityConfiguration;
import be.dda.catalogimport.testsupport.TestTechnicalClientJwt;
import com.jayway.jsonpath.JsonPath;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Bouwstap 5B-7c ({@code docs/design/fase5-perm-5b7-design.md} par. 6; beslissingslog 2026-10-22 V1-V4): de echte
 * rechtenbron {@link ProdisPermissionSource} end-to-end over HTTP, met {@code source=PRODIS}, de
 * {@code TestPermissionSource} uit, een nep-Prodis ({@link FakeProdisServer}) als {@code /api/account} en een echte
 * BFF-sessie met een geautoriseerde client (access token in de sessierepository, zoals {@code MeAppSwitcherHttpTest}).
 *
 * <h2>Business rule vs. implementatie</h2>
 * <ul>
 *   <li><b>Regel:</b> de rechten komen uit Prodis; enkel de exacte codes tellen, de hiërarchie geldt. <b>Implementatie:</b>
 *       {@code /me} toont de effectieve set; {@code approve} geeft lezen en beheren, enkel {@code read} geeft geen POST.</li>
 *   <li><b>Regel:</b> "we weten het niet" is nooit "geen rechten". <b>Implementatie:</b> Prodis down (5xx, onbruikbare
 *       JSON) = 503 {@code PERMISSION_SOURCE_UNAVAILABLE} op {@code /me} en op een beschermde GET, nooit 403; fouten
 *       worden niet gecachet.</li>
 *   <li><b>Regel (V4 = B):</b> verloren token = 401 {@code AUTHENTICATION_REQUIRED}, zonder Prodis-call.</li>
 *   <li><b>Regel (V1 = B):</b> hoogstens één Prodis-call per verzoek; een tweede verzoek binnen de TTL van dezelfde
 *       sessie roept Prodis niet opnieuw aan.</li>
 *   <li><b>Regel:</b> {@code system} beheert nooit; {@code svc:} gebruikt geen rechtenbron. <b>Implementatie:</b>
 *       {@code SYSTEM_ACTOR_FORBIDDEN} vóór de bron; de legacy-config-push roept Prodis niet aan.</li>
 * </ul>
 * Elke test heeft een eigen sessie en gebruikersnaam; de nep-Prodis wordt per test gereset.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.recovery-on-startup=false",
        "catalogimport.permissions.source=PRODIS",
        "spring.datasource.hikari.maximum-pool-size=4"})
@TestPropertySource(properties = {
        TestPermissionSource.ENABLED_PROPERTY + "=false",
        "catalogimport.technical-clients.legacy-config.audience=" + TestTechnicalClientJwt.AUDIENCE,
        "catalogimport.technical-clients.legacy-config.client-id=" + TestTechnicalClientJwt.CLIENT_ID,
        "catalogimport.technical-clients.legacy-config.required-role=" + TestTechnicalClientJwt.ROLE,
        "catalogimport.secrets.keys=", "catalogimport.secrets.active-key-id="})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ProdisPermissionHttpTest {

    private static final String ME = "/api/catalog-import/me";
    private static final String BUNDLES = "/api/catalog-import/bundles";
    private static final String SOURCE_ORGANISATIONS = "/api/catalog-import/setup/source-organisations";
    private static final String PUSH = "/api/catalog-import/legacy-config/receipts";
    private static final String TOKEN = "prodis-permission-access-token";

    private static final FakeProdisServer PRODIS = FakeProdisServer.start();

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
        registry.add("catalogimport.permissions.prodis.account-url", PRODIS::accountUrl);
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private OAuth2AuthorizedClientRepository authorizedClients;

    @AfterAll
    static void stopServer() {
        PRODIS.close();
    }

    @BeforeEach
    void setUp() {
        PRODIS.reset();
    }

    // --- De set uit Prodis ----------------------------------------------------------------------------------------

    /** Enkel de exacte codes tellen, de hiërarchie is toegepast: approve geeft ook manage en read. */
    @Test
    void meShowsTheEffectiveSetFromProdisWithTheHierarchyApplied() throws Exception {
        prodisPermissions("\"catalogImport.approve\", \"sales.read\", \"CATALOGIMPORT.MANAGE\", \"manage\"");

        String body = mockMvc.perform(get(ME).session(sessionWithToken()).with(as("prodis.approver")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> permissions = JsonPath.read(body, "$.permissions");
        assertThat(permissions).containsExactlyInAnyOrder(Permission.APPROVE.code(), Permission.MANAGE.code(),
                Permission.READ.code());
        assertThat(PRODIS.requests(FakeProdisServer.ACCOUNT_PATH)).singleElement()
                .satisfies(r -> assertThat(r.authorization()).isEqualTo("Bearer " + TOKEN));
    }

    @Test
    void aValidEmptyListFromProdisIsAnEmptySetAndNotAFailure() throws Exception {
        prodisPermissions("");

        mockMvc.perform(get(ME).session(sessionWithToken()).with(as("prodis.nothing")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.length()").value(0));
    }

    @Test
    void onlyReadAllowsAGetAndRefusesAManagePostWith403() throws Exception {
        prodisPermissions("\"catalogImport.read\"");
        MockHttpSession session = sessionWithToken();

        mockMvc.perform(get(BUNDLES).session(session).with(as("prodis.reader")))
                .andExpect(status().isOk());
        postSourceOrganisation(session, "prodis.reader")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    @Test
    void onlyApproveAllowsReadingAndManaging() throws Exception {
        prodisPermissions("\"catalogImport.approve\"");
        MockHttpSession session = sessionWithToken();

        mockMvc.perform(get(BUNDLES).session(session).with(as("prodis.boss")))
                .andExpect(status().isOk());
        // Beheren: de handler wordt bereikt (de lege body geeft 400, geen 403).
        postSourceOrganisation(session, "prodis.boss")
                .andExpect(status().isBadRequest());
    }

    // --- Prodis down: 503, nooit 403 -----------------------------------------------------------------------------

    @Test
    void whenProdisIsDownMeAndAProtectedGetAre503AndNeverCached() throws Exception {
        PRODIS.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(503, "{}"));
        MockHttpSession session = sessionWithToken();

        mockMvc.perform(get(ME).session(session).with(as("prodis.down")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PERMISSION_SOURCE_UNAVAILABLE"));
        mockMvc.perform(get(BUNDLES).session(session).with(as("prodis.down")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PERMISSION_SOURCE_UNAVAILABLE"));
        postSourceOrganisation(session, "prodis.down")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PERMISSION_SOURCE_UNAVAILABLE"));

        // Een fout wordt nooit gecachet: elk verzoek vraagt opnieuw (hoogstens één keer per verzoek).
        assertThat(PRODIS.requests(FakeProdisServer.ACCOUNT_PATH)).hasSize(3);

        // Prodis herstelt: het volgende verzoek in dezelfde sessie slaagt meteen.
        prodisPermissions("\"catalogImport.read\"");
        mockMvc.perform(get(BUNDLES).session(session).with(as("prodis.down")))
                .andExpect(status().isOk());
    }

    @Test
    void aResponseWithoutAPermissionsArrayIs503AndNotAnEmptySet() throws Exception {
        PRODIS.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(200, "{\"systemAdmin\":false}"));

        mockMvc.perform(get(BUNDLES).session(sessionWithToken()).with(as("prodis.nofield")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PERMISSION_SOURCE_UNAVAILABLE"));
    }

    // --- Verloren token: 401 --------------------------------------------------------------------------------------

    @Test
    void withoutAnAuthorizedClientInTheSessionMeAndAProtectedGetAre401AndProdisIsNotCalled() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(get(ME).session(session).with(as("prodis.notoken")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(get(BUNDLES).session(session).with(as("prodis.notoken")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        assertThat(PRODIS.requests(FakeProdisServer.ACCOUNT_PATH)).isEmpty();
    }

    // --- system en aantal calls -----------------------------------------------------------------------------------

    @Test
    void systemIsRefusedAtManageWithoutAnyProdisCall() throws Exception {
        prodisPermissions("\"catalogImport.approve\"");

        postSourceOrganisation(sessionWithToken(), "system")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));

        assertThat(PRODIS.requests(FakeProdisServer.ACCOUNT_PATH)).isEmpty();
    }

    @Test
    void atMostOneProdisCallPerRequestAndASecondRequestWithinTheTtlCallsNone() throws Exception {
        prodisPermissions("\"catalogImport.read\"");
        MockHttpSession session = sessionWithToken();

        mockMvc.perform(get(ME).session(session).with(as("prodis.counter"))).andExpect(status().isOk());
        assertThat(PRODIS.requests(FakeProdisServer.ACCOUNT_PATH)).hasSize(1);

        mockMvc.perform(get(BUNDLES).session(session).with(as("prodis.counter"))).andExpect(status().isOk());
        mockMvc.perform(get(ME).session(session).with(as("prodis.counter"))).andExpect(status().isOk());
        assertThat(PRODIS.requests(FakeProdisServer.ACCOUNT_PATH)).hasSize(1);

        // Een andere login in dezelfde sessie deelt de cache niet.
        mockMvc.perform(get(BUNDLES).session(session).with(as("prodis.counter.other"))).andExpect(status().isOk());
        assertThat(PRODIS.requests(FakeProdisServer.ACCOUNT_PATH)).hasSize(2);
    }

    // --- svc: ongewijzigd -----------------------------------------------------------------------------------------

    @Test
    void theTechnicalClientPushIsUnchangedAndNeverCallsProdis() throws Exception {
        // Prodis staat bewust op "down": als de push de rechtenbron zou raken, ging het fout.
        PRODIS.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(503, "{}"));
        MockMvc bare = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        bare.perform(post(PUSH).header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTechnicalClientJwt.validToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LegacyConfigReceiptHttpTest.bytes(LegacyConfigReceiptHttpTest.bundle())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("RECEIVED"));

        assertThat(PRODIS.requests(FakeProdisServer.ACCOUNT_PATH)).isEmpty();
    }

    // --- Hulp -------------------------------------------------------------------------------------------------------

    private void prodisPermissions(String quotedCodes) {
        PRODIS.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(200,
                "{\"systemAdmin\":false,\"permissions\":[" + quotedCodes + "]}"));
    }

    private ResultActions postSourceOrganisation(MockHttpSession session, String username) throws Exception {
        return mockMvc.perform(post(SOURCE_ORGANISATIONS).session(session).with(as(username))
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    /** Een sessie met een geldig access token voor de registratie {@code keycloak}, zoals na een echte login. */
    private MockHttpSession sessionWithToken() {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        TestingAuthenticationToken principal = new TestingAuthenticationToken("prodis.user", null, "ROLE_USER");
        Instant now = Instant.now();
        OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, TOKEN, now,
                now.plusSeconds(300), Set.of("openid"));
        authorizedClients.saveAuthorizedClient(new OAuth2AuthorizedClient(TestSecurityConfiguration.keycloakRegistration(),
                principal.getName(), accessToken), principal, request, new MockHttpServletResponse());
        return session;
    }
}
