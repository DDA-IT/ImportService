package be.dda.catalogimport.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import be.dda.catalogimport.testsupport.FakeProdisServer;
import be.dda.catalogimport.testsupport.FakeProdisServer.Response;
import be.dda.catalogimport.testsupport.TestSecurityConfiguration;
import be.dda.catalogimport.web.ProdisAccountException.Kind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.channels.Selector;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
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
 * 5B-7a: het Prodis-accounttransport onderscheidt "token verloren" ({@code TOKEN_LOST}: geen geautoriseerde
 * client, geweigerde refresh) van "andere fout" ({@code OTHER}) — de basis voor V4 in 5B-7b.
 */
class ProdisAccountClientTest {

    private static final String ACCESS_TOKEN = "client-access-token-1";
    private static final String REFRESH_TOKEN = "client-refresh-token-1";

    private static FakeProdisServer prodis;

    private final HttpSessionOAuth2AuthorizedClientRepository authorizedClients = new HttpSessionOAuth2AuthorizedClientRepository();
    private final Authentication principal = new TestingAuthenticationToken("client.user", null, "ROLE_USER");

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

    @Test
    void returnsTheJsonRootOnSuccess() {
        storeClient(accessToken(300), null);
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(200, "{\"systemAdmin\":true}"));

        JsonNode root = client().fetchAccount(principal, request, response);

        assertThat(root.get("systemAdmin").booleanValue()).isTrue();
    }

    @Test
    void noAuthorizedClientIsTokenLost() {
        assertThatThrownBy(() -> client().fetchAccount(principal, request, response))
                .isInstanceOfSatisfying(ProdisAccountException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.TOKEN_LOST);
                    assertThat(e.getMessage()).isEqualTo("no authorized client in the session");
                });
        assertThat(prodis.requests(FakeProdisServer.ACCOUNT_PATH)).isEmpty();
    }

    @Test
    void aRejectedRefreshIsTokenLostWithoutTokenOrDescription() {
        assumeJdkHttpClientCanStart();
        storeClient(accessToken(-300), new OAuth2RefreshToken(REFRESH_TOKEN, Instant.now().minusSeconds(600)));
        prodis.respond(FakeProdisServer.TOKEN_PATH, Response.json(400,
                "{\"error\":\"invalid_grant\",\"error_description\":\"Token is not active\"}"));

        assertThatThrownBy(() -> client().fetchAccount(principal, request, response))
                .isInstanceOfSatisfying(ProdisAccountException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.TOKEN_LOST);
                    assertThat(e.getMessage()).isEqualTo("token refresh failed (invalid_grant)");
                });
    }

    @Test
    void aNonSuccessStatusIsOther() {
        storeClient(accessToken(300), null);
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(401, "{}"));

        assertOther("HTTP 401");
    }

    @Test
    void aRedirectIsOther() {
        storeClient(accessToken(300), null);
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(302, "")
                .withHeader("Location", prodis.url(FakeProdisServer.REDIRECT_TARGET_PATH)));

        assertOther("HTTP 302");
        assertThat(prodis.requests(FakeProdisServer.REDIRECT_TARGET_PATH)).isEmpty();
    }

    @Test
    void notJsonAndNonObjectAreOther() {
        storeClient(accessToken(300), null);
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.of(200, "application/json", "<html></html>"));
        assertOther("unexpected response: not JSON");

        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.of(200, "application/json", "[]"));
        assertOther("unexpected JSON: not an object");
    }

    @Test
    void anOversizedResponseIsOther() {
        storeClient(accessToken(300), null);
        String huge = "{\"pad\":\"" + "x".repeat(ProdisAccountClient.MAX_BODY_BYTES) + "\"}";
        prodis.respond(FakeProdisServer.ACCOUNT_PATH, Response.json(200, huge));

        assertThatThrownBy(() -> client().fetchAccount(principal, request, response))
                .isInstanceOfSatisfying(ProdisAccountException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.OTHER);
                    assertThat(e.getMessage()).startsWith("response larger than");
                });
    }

    @Test
    void anUnreachableProdisIsOther() {
        storeClient(accessToken(300), null);
        AppSwitcherProperties properties = properties();
        properties.setProdisAccountUrl("http://127.0.0.1:1/api/account");

        assertThatThrownBy(() -> client(properties).fetchAccount(principal, request, response))
                .isInstanceOfSatisfying(ProdisAccountException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.OTHER);
                    assertThat(e.getMessage()).doesNotContain(ACCESS_TOKEN);
                });
    }

    private void assertOther(String reason) {
        assertThatThrownBy(() -> client().fetchAccount(principal, request, response))
                .isInstanceOfSatisfying(ProdisAccountException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.OTHER);
                    assertThat(e.getMessage()).isEqualTo(reason);
                });
    }

    private AppSwitcherProperties properties() {
        AppSwitcherProperties properties = new AppSwitcherProperties();
        properties.setProdisUrl("http://localhost:9060");
        properties.setProdisAccountUrl(prodis.accountUrl());
        return properties;
    }

    private ProdisAccountClient client() {
        return client(properties());
    }

    private ProdisAccountClient client(AppSwitcherProperties properties) {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("clientRegistrationRepository", new InMemoryClientRegistrationRepository(registration()));
        ObjectProvider<ClientRegistrationRepository> provider = beanFactory.getBeanProvider(ClientRegistrationRepository.class);
        return new ProdisAccountClient(properties, provider, authorizedClients, new ObjectMapper());
    }

    private static ClientRegistration registration() {
        return ClientRegistration.withClientRegistration(TestSecurityConfiguration.keycloakRegistration())
                .tokenUri(prodis.tokenUrl())
                .build();
    }

    private void storeClient(OAuth2AccessToken accessToken, OAuth2RefreshToken refreshToken) {
        OAuth2AuthorizedClient authorized = new OAuth2AuthorizedClient(registration(), principal.getName(), accessToken, refreshToken);
        authorizedClients.saveAuthorizedClient(authorized, principal, request, response);
    }

    /** Verloopt over {@code secondsFromNow} seconden (negatief = al verlopen). */
    private static OAuth2AccessToken accessToken(long secondsFromNow) {
        Instant expires = Instant.now().plusSeconds(secondsFromNow);
        return new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, ACCESS_TOKEN, expires.minusSeconds(300), expires,
                Set.of("openid"));
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
}
