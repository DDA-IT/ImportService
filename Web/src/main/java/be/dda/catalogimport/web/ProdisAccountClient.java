package be.dda.catalogimport.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;

/**
 * Transport naar Prodis' {@code GET /api/account} (uitgetrokken uit {@link ProdisAccountRelay}, slice 5B-7a van
 * docs/design/fase5-perm-5b7-design.md): het access token van de BFF-sessie (registratie
 * {@value #REGISTRATION_ID}, zo nodig vernieuwd met het refresh token) als {@code Authorization: Bearer}, geen
 * redirects, maximale bodygrootte, korte timeouts, en de body geparsed naar een JSON-object.
 * <p>
 * Geeft de JSON-root terug of gooit een {@link ProdisAccountException} met een veilige reden (nooit token, body of
 * gebruikersgegevens) en een {@link ProdisAccountException.Kind}: {@code TOKEN_LOST} (geweigerde refresh, geen
 * geautoriseerde client) tegenover {@code OTHER} (al het overige). Interpretatie van de inhoud en het beleid
 * (fail-soft of fail-closed) zijn aan de aanroeper.
 */
final class ProdisAccountClient {

    /** Zelfde registratie als {@code SecurityConfiguration.REGISTRATION_ID} (oauth2Login). */
    static final String REGISTRATION_ID = "keycloak";

    /** Bovengrens voor het antwoord van {@code /api/account}; een normaal antwoord is enkele kB. */
    static final int MAX_BODY_BYTES = 256 * 1024;

    private final Supplier<String> accountUrl;
    private final ObjectMapper objectMapper;
    private final OAuth2AuthorizedClientManager clientManager;
    private final RestClient restClient;

    /** Voor de productwisselaar: URL en timeouts uit {@code catalogimport.app-switcher}. */
    ProdisAccountClient(AppSwitcherProperties properties,
                        ObjectProvider<ClientRegistrationRepository> clientRegistrations,
                        OAuth2AuthorizedClientRepository authorizedClients,
                        ObjectMapper objectMapper) {
        this(properties::getProdisAccountUrl, properties.getConnectTimeout(), properties.getReadTimeout(),
                clientRegistrations, authorizedClients, objectMapper);
    }

    /**
     * Met eigen URL en timeouts (5B-7b: de rechtenbron heeft haar eigen {@code catalogimport.permissions.prodis.*}).
     * De URL wordt bij elke call opnieuw gelezen.
     */
    ProdisAccountClient(Supplier<String> accountUrl, Duration connectTimeout, Duration readTimeout,
                        ObjectProvider<ClientRegistrationRepository> clientRegistrations,
                        OAuth2AuthorizedClientRepository authorizedClients,
                        ObjectMapper objectMapper) {
        this.accountUrl = accountUrl;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = requestFactory(connectTimeout, readTimeout);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
        ClientRegistrationRepository registrations = clientRegistrations.getIfAvailable();
        // Zonder registratie start de applicatie sowieso niet (SecurityConfiguration); hier enkel defensief.
        this.clientManager = (registrations == null)
                ? null : clientManager(registrations, authorizedClients, requestFactory);
    }

    /** De geparste JSON-root (altijd een object) van Prodis' accountantwoord; gooit {@link ProdisAccountException}. */
    JsonNode fetchAccount(Authentication principal, HttpServletRequest request, HttpServletResponse response) {
        return fetch((principal == null) ? null : authorizeRequest().principal(principal), request, response);
    }

    /**
     * Zoals {@link #fetchAccount(Authentication, HttpServletRequest, HttpServletResponse)}, maar met enkel de
     * principalnaam ({@code OAuth2AuthorizeRequest.principal(String)}): de rechtenbron leest zo de SecurityContext niet
     * (5B-7 design par. 2). De geautoriseerde client komt uit de sessie, die de naam niet als sleutel gebruikt.
     */
    JsonNode fetchAccount(String principalName, HttpServletRequest request, HttpServletResponse response) {
        return fetch((principalName == null || principalName.isBlank()) ? null
                : authorizeRequest().principal(principalName), request, response);
    }

    private static OAuth2AuthorizeRequest.Builder authorizeRequest() {
        return OAuth2AuthorizeRequest.withClientRegistrationId(REGISTRATION_ID);
    }

    private JsonNode fetch(OAuth2AuthorizeRequest.Builder authorizeRequest, HttpServletRequest request,
                           HttpServletResponse response) {
        try {
            String accessToken = accessToken(authorizeRequest, request, response);
            if (accessToken == null) {
                throw ProdisAccountException.tokenLost("no authorized client in the session");
            }
            return parse(call(accessToken));
        } catch (ProdisAccountException e) {
            throw e;
        } catch (OAuth2AuthorizationException e) {
            // Refresh geweigerd (bv. invalid_grant): enkel de foutcode, nooit de beschrijving of het token.
            throw ProdisAccountException.tokenLost("token refresh failed (" + e.getError().getErrorCode() + ")");
        } catch (RuntimeException e) {
            throw ProdisAccountException.other(describe(e));
        }
    }

    private String accessToken(OAuth2AuthorizeRequest.Builder authorizeRequest, HttpServletRequest request,
                               HttpServletResponse response) {
        if (this.clientManager == null || authorizeRequest == null) {
            return null;
        }
        OAuth2AuthorizedClient client = this.clientManager.authorize(authorizeRequest
                .attribute(HttpServletRequest.class.getName(), request)
                .attribute(HttpServletResponse.class.getName(), response)
                .build());
        return (client == null || client.getAccessToken() == null) ? null : client.getAccessToken().getTokenValue();
    }

    private byte[] call(String accessToken) {
        return this.restClient.get()
                .uri(URI.create(this.accountUrl.get()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .accept(MediaType.APPLICATION_JSON)
                .exchange((clientRequest, clientResponse) -> {
                    int status = clientResponse.getStatusCode().value();
                    if (!clientResponse.getStatusCode().is2xxSuccessful()) {
                        throw ProdisAccountException.other("HTTP " + status);
                    }
                    byte[] bytes = clientResponse.getBody().readNBytes(MAX_BODY_BYTES + 1);
                    if (bytes.length > MAX_BODY_BYTES) {
                        throw ProdisAccountException.other("response larger than " + MAX_BODY_BYTES + " bytes");
                    }
                    return bytes;
                }, true);
    }

    private JsonNode parse(byte[] body) {
        JsonNode root;
        try {
            root = this.objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw ProdisAccountException.other("unexpected response: not JSON");
        } catch (IOException e) {
            throw ProdisAccountException.other("unexpected response: unreadable");
        }
        if (root == null || !root.isObject()) {
            throw ProdisAccountException.other("unexpected JSON: not an object");
        }
        return root;
    }

    /** Klassenamen van de fout en haar oorzaak; nooit de boodschap (die kan URL-parameters of serverteksten bevatten). */
    private static String describe(RuntimeException e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return (root == e) ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + " caused by " + root.getClass().getSimpleName();
    }

    /**
     * Blokkerende {@link HttpURLConnection} (geen NIO-{@code Selector}: die kan op sommige Windows-machines
     * niet geopend worden, en de bean wordt bij het opstarten gemaakt). Korte connect- en read-timeout; een redirect
     * wordt nooit gevolgd — met een bearer token is een 3xx gewoon een mislukte relay.
     */
    private static SimpleClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    /**
     * Eigen manager (geen bean, zodat de rest van de applicatie niet verandert) die enkel een bestaand
     * geautoriseerde client uit de sessie haalt en die zo nodig met het refresh token vernieuwt — met dezelfde
     * korte timeouts. Zonder geautoriseerde client geeft hij {@code null} (geen nieuwe login vanuit {@code /me}).
     * Een geweigerde refresh verwijdert de client uit de sessie (standaardgedrag van Spring Security).
     */
    private static OAuth2AuthorizedClientManager clientManager(ClientRegistrationRepository registrations,
                                                               OAuth2AuthorizedClientRepository authorizedClients,
                                                               SimpleClientHttpRequestFactory requestFactory) {
        RestClient tokenClient = RestClient.builder()
                .requestFactory(requestFactory)
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(new FormHttpMessageConverter());
                    converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();
        DefaultOAuth2AuthorizedClientManager manager =
                new DefaultOAuth2AuthorizedClientManager(registrations, authorizedClients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .refreshToken(refresh -> refresh.accessTokenResponseClient(new LazyRefreshTokenResponseClient(tokenClient)))
                .build());
        return manager;
    }

    /**
     * Maakt de {@link RestClientRefreshTokenTokenResponseClient} pas bij de eerste echte refresh. Zijn constructor
     * bouwt eerst een standaard-{@code RestClient} met een JDK-{@code HttpClient} (eigen selectorthread), die wij
     * meteen vervangen: zo kost een nooit gebruikte refresh niets bij het opstarten, en kan een HTTP-stack die niet
     * start nooit de applicatie tegenhouden — een fout hier valt binnen de foutafhandeling van {@link #fetchAccount}.
     */
    private static final class LazyRefreshTokenResponseClient
            implements OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> {

        private final RestClient tokenClient;
        private volatile RestClientRefreshTokenTokenResponseClient delegate;

        LazyRefreshTokenResponseClient(RestClient tokenClient) {
            this.tokenClient = tokenClient;
        }

        @Override
        public OAuth2AccessTokenResponse getTokenResponse(OAuth2RefreshTokenGrantRequest grantRequest) {
            RestClientRefreshTokenTokenResponseClient client = this.delegate;
            if (client == null) {
                synchronized (this) {
                    client = this.delegate;
                    if (client == null) {
                        client = new RestClientRefreshTokenTokenResponseClient();
                        client.setRestClient(this.tokenClient);
                        this.delegate = client;
                    }
                }
            }
            return client.getTokenResponse(grantRequest);
        }
    }
}
