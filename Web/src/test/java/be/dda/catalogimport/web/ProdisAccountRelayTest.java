package be.dda.catalogimport.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import be.dda.catalogimport.testsupport.FakeProdisServer;
import be.dda.catalogimport.testsupport.FakeProdisServer.Response;
import be.dda.catalogimport.testsupport.TestSecurityConfiguration;
import be.dda.catalogimport.web.ProdisAccountRelay.ProdisAccount;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.channels.Selector;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;

/**
 * BE-SW: de token relay naar Prodis' {@code GET /api/account} (docs/decisions.md 2026-10-20, productwisselaar),
 * tegen een echte loopbackserver ({@link FakeProdisServer}) en de echte sessierepository van tokens — zonder
 * Keycloak en zonder Spring-context.
 * <ul>
 *   <li><b>Regel:</b> het sessietoken gaat als {@code Bearer} mee en enkel {@code systemAdmin},
 *       {@code permissions} ({@code profin.access}) en {@code prodevUrl} worden gelezen.</li>
 *   <li><b>Regel:</b> een verlopen token wordt eerst met het refresh token vernieuwd.</li>
 *   <li><b>Regel (fail-soft):</b> geen token, refresh geweigerd, timeout, geen verbinding, 3xx/4xx/5xx, te groot of
 *       onverwachte JSON = leeg resultaat en één WARN-regel zonder token; nooit een exception.</li>
 * </ul>
 */
@ExtendWith(OutputCaptureExtension.class)
class ProdisAccountRelayTest {

    private static final String ACCESS_TOKEN = "session-access-token-1";
    private static final String REFRESH_TOKEN = "session-refresh-token-1";
    private static final String REFRESHED_TOKEN = "refreshed-access-token-2";

    private static FakeProdisServer prodis;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpSessionOAuth2AuthorizedClientRepository authorizedClients = new HttpSessionOAuth2AuthorizedClientRepository();
    private final Authentication principal = new TestingAuthenticationToken("relay.user", null, "ROLE_USER");

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeAll
    static void startServer() {
        prodis = FakeProdisServer.start();
    }

    @AfterAll
    static void stopServer() {
        prodis.close();
    }

    @BeforeEach
    void setUp() {
        prodis.reset();
        request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());
        response = new MockHttpServletResponse();
    }

    // --- Normaal scenario ------------------------------------------------------------------------------

    @Test
    void sendsTheSessionAccessTokenAsBearerAndReadsOnlyTheSwitcherFields() {
        storeClient(validAccessToken(), null);
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(200, """
                {"login":"relay.user","email":"relay.user@example.test","systemAdmin":true,
                 "permissions":["sales.read","profin.access"],"features":["prodev"],
                 "prodevUrl":"https://prodev.example.test"}"""));

        Optional<ProdisAccount> account = relay(properties()).fetch(principal, request, response);

        assertThat(account).contains(new ProdisAccount(true, true, "https://prodev.example.test"));
        assertThat(prodis.requests(FakeProdisServer.ACCOUNT_PATH)).singleElement().satisfies(r -> {
            assertThat(r.method()).isEqualTo("GET");
            assertThat(r.authorization()).isEqualTo("Bearer " + ACCESS_TOKEN);
            assertThat(r.accept()).contains("application/json");
        });
        assertThat(prodis.requests(FakeProdisServer.TOKEN_PATH)).isEmpty();
    }

    @Test
    void profinAccessIsAnExactCodeAfterTrimming() {
        storeClient(validAccessToken(), null);
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(200, """
                {"systemAdmin":false,"permissions":["profin.access.extra","PROFIN.ACCESS"," profin.access "]}"""));

        assertThat(relay(properties()).fetch(principal, request, response))
                .contains(new ProdisAccount(false, true, null));
    }

    @Test
    void withoutProfinAccessOrSystemAdminBothAreFalse() {
        storeClient(validAccessToken(), null);
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(200, """
                {"systemAdmin":false,"permissions":["profin.access.extra","PROFIN_ACCESS"]}"""));

        assertThat(relay(properties()).fetch(principal, request, response))
                .contains(new ProdisAccount(false, false, null));
    }

    // --- Ontbrekende data ------------------------------------------------------------------------------

    @Test
    void absentOrNullFieldsMeanNotSet() {
        storeClient(validAccessToken(), null);
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(200, """
                {"login":"relay.user","systemAdmin":null,"permissions":null}"""));

        assertThat(relay(properties()).fetch(principal, request, response))
                .contains(new ProdisAccount(false, false, null));
    }

    @Test
    void aBlankProdevUrlMeansNoProdev() {
        storeClient(validAccessToken(), null);
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(200, """
                {"systemAdmin":true,"permissions":[],"prodevUrl":"   "}"""));

        assertThat(relay(properties()).fetch(principal, request, response))
                .contains(new ProdisAccount(true, false, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "ftp://prodev.example.test", "/relative/prodev", "prodev.example.test"})
    void aProdevUrlThatIsNotAnAbsoluteHttpUrlIsDroppedButTheRestIsKept(String prodevUrl, CapturedOutput output) {
        storeClient(validAccessToken(), null);
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(200,
                "{\"systemAdmin\":true,\"prodevUrl\":\"" + prodevUrl + "\"}"));

        assertThat(relay(properties()).fetch(principal, request, response))
                .contains(new ProdisAccount(true, false, null));
        assertThat(output).contains("prodevUrl that is not an absolute http(s) URL").doesNotContain(prodevUrl);
    }

    @Test
    void withoutAnAuthorizedClientInTheSessionNothingIsSent(CapturedOutput output) {
        assertThat(relay(properties()).fetch(principal, request, response)).isEmpty();

        assertThat(prodis.requests(FakeProdisServer.ACCOUNT_PATH)).isEmpty();
        assertThat(output).contains("Prodis account relay failed (no authorized client in the session)");
    }

    @Test
    void withoutASessionNothingIsSent() {
        MockHttpServletRequest sessionless = new MockHttpServletRequest();

        assertThat(relay(properties()).fetch(principal, sessionless, response)).isEmpty();
        assertThat(prodis.requests(FakeProdisServer.ACCOUNT_PATH)).isEmpty();
    }

    @Test
    void withoutPrincipalNothingIsSent() {
        storeClient(validAccessToken(), null);

        assertThat(relay(properties()).fetch(null, request, response)).isEmpty();
        assertThat(prodis.requests(FakeProdisServer.ACCOUNT_PATH)).isEmpty();
    }

    @Test
    void aRelayThatIsNotConfiguredSendsNothingAndLogsNothing(CapturedOutput output) {
        storeClient(validAccessToken(), null);
        AppSwitcherProperties notConfigured = new AppSwitcherProperties();
        notConfigured.setProdisUrl("http://localhost:9060");

        assertThat(relay(notConfigured).fetch(principal, request, response)).isEmpty();
        assertThat(prodis.requests(FakeProdisServer.ACCOUNT_PATH)).isEmpty();
        assertThat(output).doesNotContain("Prodis account relay failed");
    }

    // --- Refresh ---------------------------------------------------------------------------------------

    @Test
    void anExpiredAccessTokenIsRefreshedFirstAndTheNewTokenIsStoredInTheSession() {
        assumeJdkHttpClientCanStart();
        storeClient(expiredAccessToken(), new OAuth2RefreshToken(REFRESH_TOKEN, Instant.now().minusSeconds(600)));
        prodis.respond(FakeProdisServer.TOKEN_PATH, Response.json(200, """
                {"access_token":"%s","token_type":"Bearer","expires_in":300,"refresh_token":"session-refresh-token-2"}"""
                .formatted(REFRESHED_TOKEN)));
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(200, "{\"systemAdmin\":true}"));

        assertThat(relay(properties()).fetch(principal, request, response))
                .contains(new ProdisAccount(true, false, null));

        assertThat(prodis.requests(FakeProdisServer.TOKEN_PATH)).singleElement().satisfies(r -> {
            assertThat(r.method()).isEqualTo("POST");
            assertThat(r.body()).contains("grant_type=refresh_token").contains("refresh_token=" + REFRESH_TOKEN);
        });
        assertThat(prodis.requests(FakeProdisServer.ACCOUNT_PATH)).singleElement()
                .satisfies(r -> assertThat(r.authorization()).isEqualTo("Bearer " + REFRESHED_TOKEN));
        OAuth2AuthorizedClient stored = authorizedClients.loadAuthorizedClient(
                ProdisAccountRelay.REGISTRATION_ID, principal, request);
        assertThat(stored.getAccessToken().getTokenValue()).isEqualTo(REFRESHED_TOKEN);
    }

    @Test
    void aRejectedRefreshIsFailSoftAndProdisIsNotCalled(CapturedOutput output) {
        assumeJdkHttpClientCanStart();
        storeClient(expiredAccessToken(), new OAuth2RefreshToken(REFRESH_TOKEN, Instant.now().minusSeconds(600)));
        prodis.respond(FakeProdisServer.TOKEN_PATH, Response.json(400,
                "{\"error\":\"invalid_grant\",\"error_description\":\"Token is not active\"}"));

        assertThat(relay(properties()).fetch(principal, request, response)).isEmpty();

        assertThat(prodis.requests(FakeProdisServer.ACCOUNT_PATH)).isEmpty();
        assertThat(output).contains("token refresh failed (invalid_grant)")
                .doesNotContain(REFRESH_TOKEN).doesNotContain("Token is not active");
    }

    // --- Onverwacht extern antwoord (fail-soft) ---------------------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404, 500, 503})
    void aNonSuccessStatusIsFailSoftAndTheTokenIsNeverLogged(int status, CapturedOutput output) {
        storeClient(validAccessToken(), null);
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(status, "{\"systemAdmin\":true}"));

        assertThat(relay(properties()).fetch(principal, request, response)).isEmpty();
        assertThat(output).contains("Prodis account relay failed (HTTP " + status + ")").doesNotContain(ACCESS_TOKEN);
    }

    @Test
    void aRedirectIsNeverFollowed(CapturedOutput output) {
        storeClient(validAccessToken(), null);
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(302, "")
                .withHeader("Location", prodis.url(FakeProdisServer.REDIRECT_TARGET_PATH)));
        prodis.respond(FakeProdisServer.REDIRECT_TARGET_PATH, Response.json(200, "{\"systemAdmin\":true}"));

        assertThat(relay(properties()).fetch(principal, request, response)).isEmpty();
        assertThat(prodis.requests(FakeProdisServer.REDIRECT_TARGET_PATH)).isEmpty();
        assertThat(output).contains("Prodis account relay failed (HTTP 302)");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{",                                         // kapotte JSON
            "<!doctype html><html></html>",              // de SPA in plaats van de API
            "",                                          // leeg antwoord
            "[]",                                        // geen object
            "\"systemAdmin\"",                           // geen object
            "{\"systemAdmin\":\"true\"}",                 // verkeerd type
            "{\"permissions\":\"profin.access\"}",        // geen lijst
            "{\"permissions\":[1]}",                     // geen tekst in de lijst
            "{\"prodevUrl\":5}"                          // verkeerd type
    })
    void unexpectedJsonIsFailSoft(String body, CapturedOutput output) {
        storeClient(validAccessToken(), null);
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.of(200, "application/json", body));

        assertThat(relay(properties()).fetch(principal, request, response)).isEmpty();
        assertThat(output).contains("Prodis account relay failed (unexpected").doesNotContain(ACCESS_TOKEN);
    }

    @Test
    void anOversizedResponseIsFailSoft(CapturedOutput output) {
        storeClient(validAccessToken(), null);
        String huge = "{\"systemAdmin\":true,\"pad\":\"" + "x".repeat(ProdisAccountRelay.MAX_BODY_BYTES) + "\"}";
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(200, huge));

        assertThat(relay(properties()).fetch(principal, request, response)).isEmpty();
        assertThat(output).contains("response larger than");
    }

    @Test
    void aSlowProdisHitsTheReadTimeoutQuickly(CapturedOutput output) {
        storeClient(validAccessToken(), null);
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(200, "{\"systemAdmin\":true}")
                .delayedBy(Duration.ofSeconds(5)));
        AppSwitcherProperties properties = properties();
        properties.setReadTimeout(Duration.ofMillis(300));

        long start = System.nanoTime();
        Optional<ProdisAccount> account = relay(properties).fetch(principal, request, response);
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(account).isEmpty();
        assertThat(took).isLessThan(Duration.ofSeconds(3));
        assertThat(output).contains("Prodis account relay failed (").doesNotContain(ACCESS_TOKEN);
    }

    @Test
    void anUnreachableProdisIsFailSoft(CapturedOutput output) throws IOException {
        storeClient(validAccessToken(), null);
        AppSwitcherProperties properties = properties();
        properties.setProdisAccountUrl("http://127.0.0.1:" + closedPort() + "/api/account");

        assertThat(relay(properties).fetch(principal, request, response)).isEmpty();
        assertThat(output).contains("Prodis account relay failed (").doesNotContain(ACCESS_TOKEN);
    }

    @Test
    void twoCallsAreTwoRequests() {
        // De relay zelf cachet niet; dat doet AppSwitcher per sessie.
        storeClient(validAccessToken(), null);
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(200, "{\"systemAdmin\":false}"));
        ProdisAccountRelay relay = relay(properties());

        relay.fetch(principal, request, response);
        relay.fetch(principal, request, response);

        assertThat(prodis.requests(FakeProdisServer.ACCOUNT_PATH)).hasSize(2);
    }

    // --- Hulpmiddelen ----------------------------------------------------------------------------------

    private AppSwitcherProperties properties() {
        AppSwitcherProperties properties = new AppSwitcherProperties();
        properties.setProdisUrl("http://localhost:9060");
        properties.setProdisAccountUrl(prodis.accountUrl());
        return properties;
    }

    private ProdisAccountRelay relay(AppSwitcherProperties properties) {
        return new ProdisAccountRelay(properties, provider(new InMemoryClientRegistrationRepository(registration())),
                authorizedClients, objectMapper);
    }

    /** De testregistratie, maar met het tokenendpoint op de nep-server (voor de refresh). */
    private static ClientRegistration registration() {
        return ClientRegistration.withClientRegistration(TestSecurityConfiguration.keycloakRegistration())
                .tokenUri(prodis.tokenUrl())
                .build();
    }

    private void storeClient(OAuth2AccessToken accessToken, OAuth2RefreshToken refreshToken) {
        OAuth2AuthorizedClient client = new OAuth2AuthorizedClient(registration(), principal.getName(), accessToken, refreshToken);
        authorizedClients.saveAuthorizedClient(client, principal, request, response);
    }

    private static OAuth2AccessToken validAccessToken() {
        Instant now = Instant.now();
        return new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, ACCESS_TOKEN, now, now.plusSeconds(300),
                Set.of("openid"));
    }

    private static OAuth2AccessToken expiredAccessToken() {
        Instant issued = Instant.now().minusSeconds(600);
        return new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, ACCESS_TOKEN, issued, issued.plusSeconds(300),
                Set.of("openid"));
    }

    private static ObjectProvider<ClientRegistrationRepository> provider(ClientRegistrationRepository repository) {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("clientRegistrationRepository", repository);
        return beanFactory.getBeanProvider(ClientRegistrationRepository.class);
    }

    /**
     * Spring Security's {@code RestClientRefreshTokenTokenResponseClient} maakt in zijn constructor een JDK-
     * {@code HttpClient}, en die opent een NIO-{@code Selector}. Op sommige Windows-omgevingen (bv. een afgeschermde
     * agentshell) lukt dat niet ("Unable to establish loopback connection"); daar is deze test niet uitvoerbaar —
     * zichtbaar als overgeslagen, niet als vals rood. Workaround: {@code -DargLine=-Djdk.net.unixdomain.tmpdir=C:	mp}.
     */
    private static void assumeJdkHttpClientCanStart() {
        boolean available;
        try (Selector selector = Selector.open()) {
            available = selector.isOpen();
        } catch (IOException e) {
            available = false;
        }
        assumeTrue(available, "JDK NIO Selector cannot be opened in this environment; refresh path not testable here");
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
