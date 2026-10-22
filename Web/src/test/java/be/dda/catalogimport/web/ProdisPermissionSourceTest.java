package be.dda.catalogimport.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import be.dda.catalogimport.service.ActorIdentity;
import be.dda.catalogimport.testsupport.FakeProdisServer;
import be.dda.catalogimport.testsupport.FakeProdisServer.Response;
import be.dda.catalogimport.testsupport.TestSecurityConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.channels.Selector;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 5B-7b: de Prodis-rechtenbron ({@code docs/design/fase5-perm-5b7-design.md} par. 2 en 6; beslissingslog 2026-10-22
 * V1-V4) tegen een echte loopbackserver ({@link FakeProdisServer}) en de echte sessierepository van tokens — zonder
 * Keycloak, zonder Spring-context en zonder netwerk buiten loopback.
 * <ul>
 *   <li><b>Regel:</b> enkel de exacte volledige codes tellen; {@code systemAdmin} wordt niet gelezen; de hiërarchie blijft
 *       in {@link Permission#effective}.</li>
 *   <li><b>Regel (fail-closed):</b> ontbrekend/verkeerd type, timeout, 3xx/4xx/5xx, te groot, geen JSON = 503; nooit een
 *       lege set; één WARN zonder token, body of username.</li>
 *   <li><b>Regel (V4 = B):</b> geen geautoriseerde client of een geweigerde refresh = 401-pad.</li>
 *   <li><b>Regel (V1 = B):</b> enkel een geslaagd antwoord PT30S per sessie, gekoppeld aan subject + username; fouten nooit.</li>
 * </ul>
 */
@ExtendWith(OutputCaptureExtension.class)
class ProdisPermissionSourceTest {

    private static final String USERNAME = "prodis.user";
    private static final String SUBJECT = "4f1c0d9e-subject-1";
    private static final String ACCESS_TOKEN = "perm-access-token-1";
    private static final String REFRESH_TOKEN = "perm-refresh-token-1";
    private static final String REFRESHED_TOKEN = "perm-refreshed-token-2";
    private static final String WARN = "Permission source (Prodis) unavailable (";

    private static FakeProdisServer prodis;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpSessionOAuth2AuthorizedClientRepository authorizedClients = new HttpSessionOAuth2AuthorizedClientRepository();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-22T10:00:00Z"));

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
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    // --- Normaal scenario ------------------------------------------------------------------------------

    @Test
    void sendsTheSessionTokenAsBearerAndGrantsOnlyExactFullCodes() {
        storeClient(validAccessToken(), null);
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(200, """
                {"login":"prodis.user","systemAdmin":false,
                 "permissions":["catalogImport.approve"," catalogImport.read ","approve","read","manage",
                                "CATALOGIMPORT.READ","catalogimport.manage","catalogImport.manage.extra","profin.access"]}"""));

        Set<Permission> permissions = source().permissionsOf(actor());

        assertThat(permissions).containsExactlyInAnyOrder(Permission.APPROVE, Permission.READ);
        assertThat(prodis.requests(FakeProdisServer.ACCOUNT_PATH)).singleElement().satisfies(r -> {
            assertThat(r.method()).isEqualTo("GET");
            assertThat(r.authorization()).isEqualTo("Bearer " + ACCESS_TOKEN);
        });
        assertThat(prodis.requests(FakeProdisServer.TOKEN_PATH)).isEmpty();
    }

    @Test
    void theSourceReturnsRawCodesAndTheHierarchyStaysInPermissionEffective() {
        storeClient(validAccessToken(), null);
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(200, "{\"permissions\":[\"catalogImport.approve\"]}"));

        Set<Permission> raw = source().permissionsOf(actor());

        assertThat(raw).containsExactly(Permission.APPROVE);
        assertThat(Permission.effective(raw)).containsExactlyInAnyOrder(Permission.READ, Permission.MANAGE, Permission.APPROVE);
    }

    @Test
    void systemAdminIsNotReadProdisMustListTheCodesItself() {
        storeClient(validAccessToken(), null);
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(200, "{\"systemAdmin\":true,\"permissions\":[]}"));

        assertThat(source().permissionsOf(actor())).isEmpty();
    }

    @Test
    void aValidEmptyListIsAnEmptySetNotAnError(CapturedOutput output) {
        storeClient(validAccessToken(), null);
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(200, "{\"permissions\":[]}"));

        assertThat(source().permissionsOf(actor())).isEmpty();
        assertThat(output).doesNotContain(WARN);
    }

    @Test
    void theResultIsUnmodifiable() {
        storeClient(validAccessToken(), null);
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(200, "{\"permissions\":[\"catalogImport.read\"]}"));

        Set<Permission> permissions = source().permissionsOf(actor());

        assertThatThrownBy(() -> permissions.add(Permission.APPROVE)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void anExpiredAccessTokenIsRefreshedFirst() {
        assumeJdkHttpClientCanStart();
        storeClient(expiredAccessToken(), new OAuth2RefreshToken(REFRESH_TOKEN, Instant.now().minusSeconds(600)));
        prodis.respond(FakeProdisServer.TOKEN_PATH, Response.json(200, """
                {"access_token":"%s","token_type":"Bearer","expires_in":300,"refresh_token":"perm-refresh-token-2"}"""
                .formatted(REFRESHED_TOKEN)));
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(200, "{\"permissions\":[\"catalogImport.manage\"]}"));

        assertThat(source().permissionsOf(actor())).containsExactly(Permission.MANAGE);

        assertThat(prodis.requests(FakeProdisServer.TOKEN_PATH)).singleElement()
                .satisfies(r -> assertThat(r.body()).contains("grant_type=refresh_token"));
        assertThat(prodis.requests(FakeProdisServer.ACCOUNT_PATH)).singleElement()
                .satisfies(r -> assertThat(r.authorization()).isEqualTo("Bearer " + REFRESHED_TOKEN));
        OAuth2AuthorizedClient stored = authorizedClients.loadAuthorizedClient(ProdisAccountClient.REGISTRATION_ID,
                new TestingAuthenticationToken(USERNAME, null), request);
        assertThat(stored.getAccessToken().getTokenValue()).isEqualTo(REFRESHED_TOKEN);
    }

    // --- Ontbrekende data / ongeldige input: 503, nooit een lege set -------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"login\":\"prodis.user\"}",
            "{\"permissions\":null}",
            "{\"permissions\":\"catalogImport.read\"}",
            "{\"permissions\":{\"catalogImport.read\":true}}",
            "{\"permissions\":[\"catalogImport.read\",1]}",
            "{\"permissions\":[\"catalogImport.read\",null]}",
            "{\"permissions\":[[\"catalogImport.read\"]]}",
            "[\"catalogImport.read\"]",
            "<html>catalogImport.read</html>"})
    void aMissingOrWronglyTypedPermissionsFieldIsUnavailable(String body, CapturedOutput output) {
        storeClient(validAccessToken(), null);
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(200, body));

        assertUnavailable("unexpected ");
        assertSingleSafeWarn(output);
    }

    // --- Verloren token: 401-pad (V4 = B) ----------------------------------------------------------------

    @Test
    void withoutAnAuthorizedClientTheUserMustSignInAgainAndProdisIsNotCalled(CapturedOutput output) {
        assertThatThrownBy(() -> source().permissionsOf(actor()))
                .isInstanceOf(PermissionSourceAuthenticationLostException.class);

        assertThat(prodis.requests(FakeProdisServer.ACCOUNT_PATH)).isEmpty();
        assertThat(output).contains(WARN + "no authorized client in the session)");
        assertSingleSafeWarn(output);
    }

    @Test
    void aRejectedRefreshMeansSignInAgainWithoutTokenOrDescriptionInTheLog(CapturedOutput output) {
        assumeJdkHttpClientCanStart();
        storeClient(expiredAccessToken(), new OAuth2RefreshToken(REFRESH_TOKEN, Instant.now().minusSeconds(600)));
        prodis.respond(FakeProdisServer.TOKEN_PATH, Response.json(400,
                "{\"error\":\"invalid_grant\",\"error_description\":\"Token is not active\"}"));

        assertThatThrownBy(() -> source().permissionsOf(actor()))
                .isInstanceOf(PermissionSourceAuthenticationLostException.class)
                .hasMessageNotContaining(REFRESH_TOKEN);

        assertThat(prodis.requests(FakeProdisServer.ACCOUNT_PATH)).isEmpty();
        assertThat(output).contains(WARN + "token refresh failed (invalid_grant))")
                .doesNotContain(REFRESH_TOKEN).doesNotContain("Token is not active");
        assertSingleSafeWarn(output);
    }

    // --- Onverwacht extern antwoord: 503 zonder token in de log -----------------------------------------

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404, 500, 503})
    void aNonSuccessStatusIsUnavailable(int status, CapturedOutput output) {
        storeClient(validAccessToken(), null);
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(status, "{\"permissions\":[\"catalogImport.read\"]}"));

        assertUnavailable("HTTP " + status);
        assertSingleSafeWarn(output);
    }

    @Test
    void aRedirectIsNeverFollowedAndIsUnavailable(CapturedOutput output) {
        storeClient(validAccessToken(), null);
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(302, "")
                .withHeader("Location", prodis.url(FakeProdisServer.REDIRECT_TARGET_PATH)));
        prodis.respond(FakeProdisServer.REDIRECT_TARGET_PATH, Response.json(200, "{\"permissions\":[\"catalogImport.approve\"]}"));

        assertUnavailable("HTTP 302");
        assertThat(prodis.requests(FakeProdisServer.REDIRECT_TARGET_PATH)).isEmpty();
        assertSingleSafeWarn(output);
    }

    @Test
    void anOversizedResponseIsUnavailable(CapturedOutput output) {
        storeClient(validAccessToken(), null);
        String huge = "{\"permissions\":[\"catalogImport.read\"],\"pad\":\"" + "x".repeat(ProdisAccountClient.MAX_BODY_BYTES) + "\"}";
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(200, huge));

        assertUnavailable("response larger than");
        assertSingleSafeWarn(output);
    }

    @Test
    void aSlowProdisHitsTheReadTimeoutQuicklyAndIsUnavailable(CapturedOutput output) {
        storeClient(validAccessToken(), null);
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(200, "{\"permissions\":[\"catalogImport.read\"]}")
                .delayedBy(Duration.ofSeconds(5)));
        PermissionProperties.Prodis properties = properties();
        properties.setReadTimeout(Duration.ofMillis(300));

        long start = System.nanoTime();
        assertThatThrownBy(() -> source(properties).permissionsOf(actor()))
                .isInstanceOf(PermissionSourceUnavailableException.class);
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(took).isLessThan(Duration.ofSeconds(3));
        assertSingleSafeWarn(output);
    }

    @Test
    void anUnreachableProdisIsUnavailable(CapturedOutput output) throws IOException {
        storeClient(validAccessToken(), null);
        PermissionProperties.Prodis properties = properties();
        properties.setAccountUrl("http://127.0.0.1:" + closedPort() + "/api/account");

        assertThatThrownBy(() -> source(properties).permissionsOf(actor()))
                .isInstanceOf(PermissionSourceUnavailableException.class)
                .hasMessageNotContaining(ACCESS_TOKEN);
        assertSingleSafeWarn(output);
    }

    @Test
    void outsideAnHttpRequestTheSourceIsUnavailableAndProdisIsNotCalled(CapturedOutput output) {
        storeClient(validAccessToken(), null);
        RequestContextHolder.resetRequestAttributes();

        assertThatThrownBy(() -> source().permissionsOf(actor()))
                .isInstanceOf(PermissionSourceUnavailableException.class)
                .hasMessageContaining("no current HTTP request");
        assertThat(prodis.requests(FakeProdisServer.ACCOUNT_PATH)).isEmpty();
        assertSingleSafeWarn(output);
    }

    @Test
    void aRequestWithoutResponseIsUnavailable() {
        storeClient(validAccessToken(), null);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThatThrownBy(() -> source().permissionsOf(actor()))
                .isInstanceOf(PermissionSourceUnavailableException.class);
        assertThat(prodis.requests(FakeProdisServer.ACCOUNT_PATH)).isEmpty();
    }

    @Test
    void anAccountUrlIsRequired() {
        PermissionProperties.Prodis properties = new PermissionProperties().getProdis();

        assertThatThrownBy(() -> source(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("catalogimport.permissions.prodis.account-url is required");
    }

    // --- Sessiecache (V1 = B) -----------------------------------------------------------------------------

    @Test
    void aSecondCallWithinTheTtlSendsNoRequestAndAfterTheTtlFetchesAgain() {
        storeClient(validAccessToken(), null);
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(200, "{\"permissions\":[\"catalogImport.read\"]}"));
        ProdisPermissionSource source = source();

        assertThat(source.permissionsOf(actor())).containsExactly(Permission.READ);
        clock.advance(Duration.ofSeconds(29));
        assertThat(source.permissionsOf(actor())).containsExactly(Permission.READ);
        assertThat(prodis.requests(FakeProdisServer.ACCOUNT_PATH)).hasSize(1);

        // Intrekking in Prodis: na de TTL geldt het nieuwe antwoord.
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(200, "{\"permissions\":[]}"));
        clock.advance(Duration.ofSeconds(1));
        assertThat(source.permissionsOf(actor())).isEmpty();
        assertThat(prodis.requests(FakeProdisServer.ACCOUNT_PATH)).hasSize(2);
    }

    @Test
    void theTtlIsConfigurable() {
        storeClient(validAccessToken(), null);
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(200, "{\"permissions\":[\"catalogImport.read\"]}"));
        PermissionProperties.Prodis properties = properties();
        properties.setCacheTtl(Duration.ofSeconds(5));
        ProdisPermissionSource source = source(properties);

        source.permissionsOf(actor());
        clock.advance(Duration.ofSeconds(5));
        source.permissionsOf(actor());

        assertThat(prodis.requests(FakeProdisServer.ACCOUNT_PATH)).hasSize(2);
    }

    @Test
    void aFailureIsNeverCachedTheNextCallAsksAgain() {
        storeClient(validAccessToken(), null);
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(500, "{}"));
        ProdisPermissionSource source = source();

        assertThatThrownBy(() -> source.permissionsOf(actor())).isInstanceOf(PermissionSourceUnavailableException.class);
        assertThat(request.getSession().getAttribute(ProdisPermissionSource.CACHE_ATTRIBUTE)).isNull();

        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(200, "{\"permissions\":[\"catalogImport.manage\"]}"));
        assertThat(source.permissionsOf(actor())).containsExactly(Permission.MANAGE);
        assertThat(prodis.requests(FakeProdisServer.ACCOUNT_PATH)).hasSize(2);
    }

    @Test
    void aFailureAfterAnExpiredSuccessDoesNotReviveTheOldAnswer() {
        storeClient(validAccessToken(), null);
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(200, "{\"permissions\":[\"catalogImport.approve\"]}"));
        ProdisPermissionSource source = source();
        source.permissionsOf(actor());

        clock.advance(Duration.ofSeconds(31));
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(503, "{}"));

        assertThatThrownBy(() -> source.permissionsOf(actor())).isInstanceOf(PermissionSourceUnavailableException.class);
        assertThatThrownBy(() -> source.permissionsOf(actor())).isInstanceOf(PermissionSourceUnavailableException.class);
        assertThat(prodis.requests(FakeProdisServer.ACCOUNT_PATH)).hasSize(3);
    }

    @Test
    void anotherUsernameOrSubjectInTheSameSessionNeverReusesTheCache() {
        storeClient(validAccessToken(), null);
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(200, "{\"permissions\":[\"catalogImport.approve\"]}"));
        ProdisPermissionSource source = source();
        source.permissionsOf(actor());

        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(200, "{\"permissions\":[\"catalogImport.read\"]}"));
        assertThat(source.permissionsOf(new ActorIdentity("other.user", SUBJECT))).containsExactly(Permission.READ);
        assertThat(source.permissionsOf(new ActorIdentity(USERNAME, "another-subject"))).containsExactly(Permission.READ);

        assertThat(prodis.requests(FakeProdisServer.ACCOUNT_PATH)).hasSize(3);
    }

    @Test
    void aClockThatWentBackDoesNotExtendTheCache() {
        storeClient(validAccessToken(), null);
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(200, "{\"permissions\":[\"catalogImport.read\"]}"));
        ProdisPermissionSource source = source();
        source.permissionsOf(actor());

        clock.advance(Duration.ofSeconds(-5));
        source.permissionsOf(actor());

        assertThat(prodis.requests(FakeProdisServer.ACCOUNT_PATH)).hasSize(2);
    }

    @Test
    void withoutASessionNothingIsCachedAndNoSessionIsCreated() {
        MockHttpServletRequest sessionless = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(sessionless, response));

        // Zonder sessie is er ook geen geautoriseerde client: 401-pad, en er wordt geen sessie aangemaakt.
        assertThatThrownBy(() -> source().permissionsOf(actor()))
                .isInstanceOf(PermissionSourceAuthenticationLostException.class);
        assertThat(sessionless.getSession(false)).isNull();
    }

    @Test
    void theCacheIsItsOwnSessionItemNeverTheAppSwitcherCache() {
        storeClient(validAccessToken(), null);
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(200, "{\"permissions\":[\"catalogImport.read\"]}"));
        // Een (geldig) productwisselaar-item in dezelfde sessie mag nooit als rechtenantwoord gelden.
        request.getSession().setAttribute(AppSwitcher.CACHE_ATTRIBUTE, new AppSwitcher.CachedAccount(SUBJECT, USERNAME,
                clock.instant(), new ProdisAccountRelay.ProdisAccount(true, true, null)));

        assertThat(source().permissionsOf(actor())).containsExactly(Permission.READ);

        assertThat(prodis.requests(FakeProdisServer.ACCOUNT_PATH)).hasSize(1);
        assertThat(request.getSession().getAttribute(ProdisPermissionSource.CACHE_ATTRIBUTE))
                .isInstanceOfSatisfying(ProdisPermissionSource.CachedPermissions.class, cached -> {
                    assertThat(cached.subject()).isEqualTo(SUBJECT);
                    assertThat(cached.username()).isEqualTo(USERNAME);
                    assertThat(cached.permissions()).containsExactly(Permission.READ);
                });
    }

    // --- Hulpmiddelen -------------------------------------------------------------------------------------

    private void assertUnavailable(String reasonPrefix) {
        assertThatThrownBy(() -> source().permissionsOf(actor()))
                .isInstanceOfSatisfying(PermissionSourceUnavailableException.class, e -> {
                    assertThat(e.getMessage()).startsWith(WARN + reasonPrefix);
                    assertThat(e.getMessage()).doesNotContain(ACCESS_TOKEN).doesNotContain(USERNAME);
                });
    }

    /** Precies één WARN-regel van de bron; nooit token, refresh token of username. */
    private static void assertSingleSafeWarn(CapturedOutput output) {
        String all = output.getAll();
        int count = 0;
        for (int at = all.indexOf(WARN); at >= 0; at = all.indexOf(WARN, at + 1)) {
            count++;
        }
        assertThat(count).as("WARN lines of the permission source").isEqualTo(1);
        assertThat(all).doesNotContain(ACCESS_TOKEN).doesNotContain(REFRESH_TOKEN).doesNotContain(USERNAME);
    }

    private static ActorIdentity actor() {
        return new ActorIdentity(USERNAME, SUBJECT);
    }

    private PermissionProperties.Prodis properties() {
        PermissionProperties.Prodis properties = new PermissionProperties().getProdis();
        properties.setAccountUrl(prodis.accountUrl());
        return properties;
    }

    private ProdisPermissionSource source() {
        return source(properties());
    }

    private ProdisPermissionSource source(PermissionProperties.Prodis properties) {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("clientRegistrationRepository", new InMemoryClientRegistrationRepository(registration()));
        ObjectProvider<ClientRegistrationRepository> provider = beanFactory.getBeanProvider(ClientRegistrationRepository.class);
        return new ProdisPermissionSource(properties, provider, authorizedClients, objectMapper, clock);
    }

    /** De testregistratie, maar met het tokenendpoint op de nep-server (voor de refresh). */
    private static ClientRegistration registration() {
        return ClientRegistration.withClientRegistration(TestSecurityConfiguration.keycloakRegistration())
                .tokenUri(prodis.tokenUrl())
                .build();
    }

    private void storeClient(OAuth2AccessToken accessToken, OAuth2RefreshToken refreshToken) {
        TestingAuthenticationToken login = new TestingAuthenticationToken(USERNAME, null, "ROLE_USER");
        OAuth2AuthorizedClient client = new OAuth2AuthorizedClient(registration(), USERNAME, accessToken, refreshToken);
        authorizedClients.saveAuthorizedClient(client, login, request, response);
    }

    private static OAuth2AccessToken validAccessToken() {
        Instant now = Instant.now();
        return new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, ACCESS_TOKEN, now, now.plusSeconds(300),
                Set.of("openid"));
    }

    private static OAuth2AccessToken expiredAccessToken() {
        Instant now = Instant.now();
        return new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, ACCESS_TOKEN, now.minusSeconds(600),
                now.minusSeconds(300), Set.of("openid"));
    }

    /** Zie {@code ProdisAccountRelayTest}: de refresh-client opent een NIO-Selector die niet overal lukt. */
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

    /** Een klok die de test vooruit (of terug) zet. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            this.now = this.now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.now;
        }
    }
}
