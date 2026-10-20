package be.dda.catalogimport.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Token relay naar Prodis' {@code GET /api/account} voor de productwisselaar (docs/decisions.md 2026-10-20
 * "Frontend-ombouw volgens de Prodis-mock + productwisselaar"). Stuurt het access token van de huidige
 * BFF-sessie (registratie {@value #REGISTRATION_ID}, vernieuwd met het refresh token als het verlopen is)
 * als {@code Authorization: Bearer} en leest <b>enkel</b> {@code systemAdmin}, {@code permissions} (alleen om
 * {@value #PROFIN_ACCESS} te zien) en {@code prodevUrl}.
 * <p>
 * <b>Geen rechtenbron.</b> De rechten van CatalogImport zelf blijven bij {@link PermissionSource}
 * ({@code ConfiguredPermissionSource}); dit antwoord bepaalt enkel welke links de wisselaar toont. De
 * Prodis-rechtenadapter (5B-7) is een aparte stap met andere regels (fail-closed, nooit gecachet).
 * <p>
 * <b>Fail-soft.</b> Elke fout — geen token in de sessie, refresh mislukt, timeout, geen verbinding, een
 * niet-2xx-antwoord (ook een redirect: die wordt nooit gevolgd), een te groot antwoord of onverwachte JSON —
 * geeft {@link Optional#empty()} en één WARN-regel zonder token, body of gebruikersgegevens.
 */
@Component
public class ProdisAccountRelay {

    /** Zelfde registratie als {@code SecurityConfiguration.REGISTRATION_ID} (oauth2Login). */
    static final String REGISTRATION_ID = "keycloak";

    /** Het Prodis-recht dat ProFin in de wisselaar zichtbaar maakt (Prodis {@code PROFIN_ACCESS}). */
    static final String PROFIN_ACCESS = "profin.access";

    /** Bovengrens voor het antwoord van {@code /api/account}; een normaal antwoord is enkele kB. */
    static final int MAX_BODY_BYTES = 256 * 1024;

    private static final Logger LOG = LoggerFactory.getLogger(ProdisAccountRelay.class);

    /** Wat de wisselaar uit Prodis' accountantwoord haalt; {@code prodevUrl} is {@code null} of een absolute http(s)-URL. */
    public record ProdisAccount(boolean systemAdmin, boolean profinAccess, String prodevUrl) implements Serializable {
    }

    private final AppSwitcherProperties properties;
    private final ObjectMapper objectMapper;
    private final OAuth2AuthorizedClientManager clientManager;
    private final RestClient restClient;

    public ProdisAccountRelay(AppSwitcherProperties properties,
                              ObjectProvider<ClientRegistrationRepository> clientRegistrations,
                              OAuth2AuthorizedClientRepository authorizedClients,
                              ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = requestFactory(properties);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
        ClientRegistrationRepository registrations = clientRegistrations.getIfAvailable();
        // Zonder registratie start de applicatie sowieso niet (SecurityConfiguration); hier enkel defensief.
        this.clientManager = (registrations == null)
                ? null : clientManager(registrations, authorizedClients, requestFactory);
    }

    /**
     * Prodis' accountgegevens voor de aangemelde gebruiker, of leeg bij elke fout (en als de relay niet
     * ingesteld is — dan zonder log). Gooit nooit.
     */
    public Optional<ProdisAccount> fetch(Authentication principal, HttpServletRequest request,
                                         HttpServletResponse response) {
        if (!this.properties.relayConfigured()) {
            return Optional.empty();
        }
        try {
            String accessToken = accessToken(principal, request, response);
            if (accessToken == null) {
                return failed("no authorized client in the session");
            }
            return Optional.of(call(accessToken));
        } catch (RelayFailure e) {
            return failed(e.getMessage());
        } catch (OAuth2AuthorizationException e) {
            // Refresh geweigerd (bv. invalid_grant): enkel de foutcode, nooit de beschrijving of het token.
            return failed("token refresh failed (" + e.getError().getErrorCode() + ")");
        } catch (RuntimeException e) {
            return failed(describe(e));
        }
    }

    private String accessToken(Authentication principal, HttpServletRequest request, HttpServletResponse response) {
        if (this.clientManager == null || principal == null) {
            return null;
        }
        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest.withClientRegistrationId(REGISTRATION_ID)
                .principal(principal)
                .attribute(HttpServletRequest.class.getName(), request)
                .attribute(HttpServletResponse.class.getName(), response)
                .build();
        OAuth2AuthorizedClient client = this.clientManager.authorize(authorizeRequest);
        return (client == null || client.getAccessToken() == null) ? null : client.getAccessToken().getTokenValue();
    }

    private ProdisAccount call(String accessToken) {
        byte[] body = this.restClient.get()
                .uri(URI.create(this.properties.getProdisAccountUrl()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .accept(MediaType.APPLICATION_JSON)
                .exchange((clientRequest, clientResponse) -> {
                    int status = clientResponse.getStatusCode().value();
                    if (!clientResponse.getStatusCode().is2xxSuccessful()) {
                        throw new RelayFailure("HTTP " + status);
                    }
                    byte[] bytes = clientResponse.getBody().readNBytes(MAX_BODY_BYTES + 1);
                    if (bytes.length > MAX_BODY_BYTES) {
                        throw new RelayFailure("response larger than " + MAX_BODY_BYTES + " bytes");
                    }
                    return bytes;
                }, true);
        return parse(body);
    }

    /** Strikt op type, mild op afwezigheid: een ontbrekend veld is "niet gezet", een veld van het verkeerde type is een fout. */
    private ProdisAccount parse(byte[] body) {
        JsonNode root;
        try {
            root = this.objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new RelayFailure("unexpected response: not JSON");
        } catch (IOException e) {
            throw new RelayFailure("unexpected response: unreadable");
        }
        if (root == null || !root.isObject()) {
            throw new RelayFailure("unexpected JSON: not an object");
        }
        boolean systemAdmin = systemAdmin(root.get("systemAdmin"));
        boolean profinAccess = hasProfinAccess(root.get("permissions"));
        String prodevUrl = prodevUrl(root.get("prodevUrl"));
        return new ProdisAccount(systemAdmin, profinAccess, prodevUrl);
    }

    private static boolean systemAdmin(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (!node.isBoolean()) {
            throw new RelayFailure("unexpected JSON: systemAdmin is not a boolean");
        }
        return node.booleanValue();
    }

    private static boolean hasProfinAccess(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (!node.isArray()) {
            throw new RelayFailure("unexpected JSON: permissions is not an array");
        }
        boolean found = false;
        for (JsonNode permission : node) {
            if (!permission.isTextual()) {
                throw new RelayFailure("unexpected JSON: permissions contains a non-string");
            }
            found |= PROFIN_ACCESS.equals(permission.textValue().trim());
        }
        return found;
    }

    /** Een ongeldige ProDev-URL laat enkel ProDev weg (nooit een niet-http(s)-link in de wisselaar), de rest blijft bruikbaar. */
    private static String prodevUrl(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new RelayFailure("unexpected JSON: prodevUrl is not a string");
        }
        String value = node.textValue().trim();
        if (value.isEmpty()) {
            return null;
        }
        if (!AppSwitcherProperties.isAbsoluteHttpUrl(value)) {
            LOG.warn("App switcher: Prodis returned a prodevUrl that is not an absolute http(s) URL; ProDev is not offered");
            return null;
        }
        return value;
    }

    private static Optional<ProdisAccount> failed(String reason) {
        LOG.warn("App switcher: Prodis account relay failed ({}); offering only the configured Prodis link", reason);
        return Optional.empty();
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
    private static SimpleClientHttpRequestFactory requestFactory(AppSwitcherProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(properties.getConnectTimeout());
        factory.setReadTimeout(properties.getReadTimeout());
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
     * start nooit de applicatie tegenhouden — een fout hier valt binnen de fail-soft van {@link #fetch}.
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

    /** Een verwachte relayfout met een veilige, korte reden. */
    private static final class RelayFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        RelayFailure(String reason) {
            super(reason, null, false, false);
        }
    }
}
