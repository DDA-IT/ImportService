package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static be.dda.catalogimport.testsupport.TestActors.withoutPermissions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.testsupport.FakeProdisServer;
import be.dda.catalogimport.testsupport.FakeProdisServer.Response;
import be.dda.catalogimport.testsupport.TestSecurityConfiguration;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import org.springframework.test.web.servlet.MockMvc;

/**
 * BE-SW end-to-end: {@code GET /api/catalog-import/me.apps} door de echte filterketen, controller, wisselaar en
 * relay, met het access token in de echte sessierepository en een nep-Prodis op een loopbackpoort (geen Keycloak).
 * {@code prodis-url} komt uit het profiel {@code local} ({@code http://localhost:9060}, overschrijfbaar met
 * {@code CATALOG_PRODIS_URL} — daarom leest de test de waarde uit de bean).
 */
@SpringBootTest(properties = {
        "catalogimport.screening.recovery-on-startup=false",
        "spring.datasource.hikari.maximum-pool-size=4"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class MeAppSwitcherHttpTest {

    private static final String ME = "/api/catalog-import/me";
    private static final String TOKEN = "me-relay-access-token";

    private static final FakeProdisServer PRODIS = FakeProdisServer.start();

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
        registry.add("catalogimport.app-switcher.prodis-account-url", PRODIS::accountUrl);
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OAuth2AuthorizedClientRepository authorizedClients;
    @Autowired
    private AppSwitcherProperties appSwitcherProperties;

    private String prodisUrl;

    @AfterAll
    static void stopServer() {
        PRODIS.close();
    }

    @BeforeEach
    void setUp() {
        PRODIS.reset();
        prodisUrl = appSwitcherProperties.getProdisUrl();
        assertThat(prodisUrl).as("profile local sets catalogimport.app-switcher.prodis-url").isNotNull();
    }

    @Test
    void aSystemAdminWithProdevSeesProdisProfinAndProdevAndTheTokenIsRelayed() throws Exception {
        PRODIS.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(200, """
                {"systemAdmin":true,"permissions":["sales.read"],"prodevUrl":"https://prodev.example.test"}"""));

        mockMvc.perform(get(ME).session(sessionWithToken()).with(as("switch.admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apps.length()").value(3))
                .andExpect(jsonPath("$.apps[0].id").value("prodis"))
                .andExpect(jsonPath("$.apps[0].url").value(prodisUrl))
                .andExpect(jsonPath("$.apps[1].id").value("profin"))
                .andExpect(jsonPath("$.apps[1].url").value(prodisUrl + "/dashboard/profin"))
                .andExpect(jsonPath("$.apps[2].id").value("prodev"))
                .andExpect(jsonPath("$.apps[2].url").value("https://prodev.example.test"))
                .andExpect(jsonPath("$.apps[0].length()").value(2));

        assertThat(PRODIS.requests(FakeProdisServer.ACCOUNT_PATH)).singleElement()
                .satisfies(r -> assertThat(r.authorization()).isEqualTo("Bearer " + TOKEN));
    }

    @Test
    void aUserWithoutCatalogImportPermissionsStillGetsTheApps() throws Exception {
        PRODIS.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(200, """
                {"systemAdmin":false,"permissions":["profin.access"]}"""));

        mockMvc.perform(get(ME).session(sessionWithToken()).with(withoutPermissions("switch.nobody")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.length()").value(0))
                .andExpect(jsonPath("$.apps.length()").value(2))
                .andExpect(jsonPath("$.apps[0].id").value("prodis"))
                .andExpect(jsonPath("$.apps[1].id").value("profin"));
    }

    @Test
    void aFailingProdisKeepsMeAt200WithOnlyTheProdisLink() throws Exception {
        PRODIS.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(503, "{}"));

        mockMvc.perform(get(ME).session(sessionWithToken()).with(as("switch.down")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apps.length()").value(1))
                .andExpect(jsonPath("$.apps[0].id").value("prodis"))
                .andExpect(jsonPath("$.apps[0].url").value(prodisUrl));
    }

    @Test
    void withoutATokenInTheSessionProdisIsNotCalled() throws Exception {
        mockMvc.perform(get(ME).session(new MockHttpSession()).with(as("switch.notoken")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apps.length()").value(1))
                .andExpect(jsonPath("$.apps[0].id").value("prodis"));

        assertThat(PRODIS.requests(FakeProdisServer.ACCOUNT_PATH)).isEmpty();
    }

    @Test
    void aSecondMeInTheSameSessionUsesTheCache() throws Exception {
        PRODIS.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(200, "{\"systemAdmin\":true}"));
        MockHttpSession session = sessionWithToken();

        mockMvc.perform(get(ME).session(session).with(as("switch.cached"))).andExpect(status().isOk());
        mockMvc.perform(get(ME).session(session).with(as("switch.cached")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apps[1].id").value("profin"));

        assertThat(PRODIS.requests(FakeProdisServer.ACCOUNT_PATH)).hasSize(1);
    }

    @Test
    void aSlowProdisDoesNotHoldMeLongerThanTheTimeouts() throws Exception {
        PRODIS.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(200, "{\"systemAdmin\":true}")
                .delayedBy(Duration.ofSeconds(10)));

        long start = System.nanoTime();
        mockMvc.perform(get(ME).session(sessionWithToken()).with(as("switch.slow")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apps.length()").value(1));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(6));
    }

    /** Een sessie met een geldig access token voor de registratie {@code keycloak}, zoals na een echte login. */
    private MockHttpSession sessionWithToken() {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        TestingAuthenticationToken principal = new TestingAuthenticationToken("switch.user", null, "ROLE_USER");
        Instant now = Instant.now();
        OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, TOKEN, now,
                now.plusSeconds(300), Set.of("openid"));
        authorizedClients.saveAuthorizedClient(new OAuth2AuthorizedClient(TestSecurityConfiguration.keycloakRegistration(),
                principal.getName(), accessToken), principal, request, new MockHttpServletResponse());
        return session;
    }
}
