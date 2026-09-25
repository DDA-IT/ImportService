package be.dda.catalogimport.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.access.RequestMatcherDelegatingAccessDeniedHandler;
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.StringUtils;

/**
 * Login en sessiebeveiliging van CatalogImport (Fase 5-AUTH, bindend ontwerp
 * {@code docs/design/fase5-auth-design.md} par. 2.2): een Backend-for-Frontend met {@code oauth2Login}
 * tegen Keycloak en een sessiecookie. Eén filterketen; geen bearer-tokens (geen resource-server), geen
 * rechten per actie (dat is 5-PERM).
 * <ul>
 *   <li>{@code /api/**} vereist een login en antwoordt zonder login <b>401 JSON</b>
 *       {@code AUTHENTICATION_REQUIRED}, nooit een redirect (een fetch kan geen cross-origin redirect naar
 *       Keycloak volgen). Andere paden worden naar {@value #LOGIN_ENTRY_POINT} gestuurd.</li>
 *   <li>403 op {@code /api/**} is JSON: {@code CSRF_TOKEN_INVALID} bij een CSRF-fout, anders
 *       {@code ACCESS_DENIED}. Filterfouten bereiken de {@code ApiExceptionHandler} niet; de vorm
 *       {@code {error, code}} is daarom hier nagebouwd.</li>
 *   <li>CSRF via cookie {@code XSRF-TOKEN} + header {@code X-XSRF-TOKEN}; het token wordt <b>alleen uit de
 *       header</b> gelezen (C3, zie {@link HeaderOnlySpaCsrfTokenRequestHandler}).</li>
 *   <li>Logout {@code POST} {@value #LOGOUT_URL} antwoordt 200 {@code {"logoutUrl": ...}} (de
 *       end-session-URL van Keycloak) in plaats van een 302.</li>
 *   <li>Geen request cache (C2): na login altijd naar {@code /}; de SPA herstelt zelf het pad.</li>
 *   <li>Tokens leven in de sessie ({@link HttpSessionOAuth2AuthorizedClientRepository}, C11).</li>
 *   <li>Weigert op te starten zonder registratie {@value #REGISTRATION_ID} of met een leeg/onopgelost
 *       client-secret.</li>
 * </ul>
 */
@Configuration
public class SecurityConfiguration {

    static final String REGISTRATION_ID = "keycloak";
    static final String LOGIN_ENTRY_POINT = "/oauth2/authorization/" + REGISTRATION_ID;
    static final String LOGOUT_URL = "/api/catalog-import/logout";
    static final String API_PATTERN = "/api/**";

    static final String AUTHENTICATION_REQUIRED = "AUTHENTICATION_REQUIRED";
    static final String CSRF_TOKEN_INVALID = "CSRF_TOKEN_INVALID";
    static final String ACCESS_DENIED = "ACCESS_DENIED";

    /**
     * Eén repository voor geautoriseerde clients, zowel voor {@code oauth2Login} als voor elke latere
     * gebruiker (token relay in 5-PERM): de tokens leven en sterven met de sessie, niet in een
     * geheugenmap op principalnaam die een logout overleeft (C11).
     */
    @Bean
    OAuth2AuthorizedClientRepository oauth2AuthorizedClientRepository() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            ObjectProvider<ClientRegistrationRepository> clientRegistrationRepository,
                                            OAuth2AuthorizedClientRepository authorizedClients,
                                            ObjectMapper objectMapper) throws Exception {
        // Zonder enige registratie in de properties maakt Boot geen repository aan: ook dan een duidelijke fout.
        ClientRegistrationRepository clientRegistrations = clientRegistrationRepository.getIfAvailable();
        requireUsableRegistration(clientRegistrations);
        RequestMatcher api = AntPathRequestMatcher.antMatcher(API_PATTERN);
        JsonResponses json = new JsonResponses(objectMapper);

        http
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/actuator/health"),
                                AntPathRequestMatcher.antMatcher(HttpMethod.GET, "/actuator/info")).permitAll()
                        .requestMatchers(api).authenticated()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint(api, json))
                        .accessDeniedHandler(accessDeniedHandler(api, json)))
                .oauth2Login(login -> login
                        .clientRegistrationRepository(clientRegistrations)
                        .authorizedClientRepository(authorizedClients)
                        .defaultSuccessUrl("/", true))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new HeaderOnlySpaCsrfTokenRequestHandler()))
                .logout(logout -> logout
                        .logoutUrl(LOGOUT_URL)
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler(new JsonOidcLogoutSuccessHandler(clientRegistrations, json)))
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .cors(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);
        return http.build();
    }

    /**
     * Fail-fast bij het opstarten: zonder registratie {@value #REGISTRATION_ID} of met een leeg of
     * onopgelost ({@code ${...}}) client-secret start de applicatie niet. De foutboodschap noemt nooit
     * de waarde van het secret.
     */
    static void requireUsableRegistration(ClientRegistrationRepository clientRegistrations) {
        ClientRegistration registration = (clientRegistrations == null)
                ? null : clientRegistrations.findByRegistrationId(REGISTRATION_ID);
        if (registration == null) {
            throw new IllegalStateException("OIDC client registration '" + REGISTRATION_ID + "' is missing "
                    + "(spring.security.oauth2.client.registration." + REGISTRATION_ID + ")");
        }
        String secret = registration.getClientSecret();
        if (!StringUtils.hasText(secret) || secret.startsWith("${")) {
            throw new IllegalStateException("OIDC client secret of registration '" + REGISTRATION_ID
                    + "' is empty or unresolved; set CATALOG_OIDC_CLIENT_SECRET");
        }
    }

    private static AuthenticationEntryPoint authenticationEntryPoint(RequestMatcher api, JsonResponses json) {
        LinkedHashMap<RequestMatcher, AuthenticationEntryPoint> byPath = new LinkedHashMap<>();
        byPath.put(api, new ApiAuthenticationEntryPoint(json));
        DelegatingAuthenticationEntryPoint entryPoint = new DelegatingAuthenticationEntryPoint(byPath);
        entryPoint.setDefaultEntryPoint(new LoginUrlAuthenticationEntryPoint(LOGIN_ENTRY_POINT));
        return entryPoint;
    }

    private static AccessDeniedHandler accessDeniedHandler(RequestMatcher api, JsonResponses json) {
        // Expliciet gezet (niet enkel als default per pad): ook de CsrfFilter gebruikt deze handler.
        LinkedHashMap<RequestMatcher, AccessDeniedHandler> byPath = new LinkedHashMap<>();
        byPath.put(api, new ApiAccessDeniedHandler(json));
        return new RequestMatcherDelegatingAccessDeniedHandler(byPath, new AccessDeniedHandlerImpl());
    }

    /** Schrijft een JSON-antwoord in exact de vorm van {@code ApiExceptionHandler}: {@code {error, code}}. */
    static final class JsonResponses {

        private final ObjectMapper objectMapper;

        JsonResponses(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        void error(HttpServletResponse response, HttpStatus status, String error, String code) throws IOException {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("error", error);
            body.put("code", code);
            write(response, status, body);
        }

        void write(HttpServletResponse response, HttpStatus status, Map<String, String> body) throws IOException {
            response.setStatus(status.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(objectMapper.writeValueAsString(body));
        }
    }

    /** 401 JSON voor {@code /api/**}, zonder {@code Location}-header. */
    static final class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

        private final JsonResponses json;

        ApiAuthenticationEntryPoint(JsonResponses json) {
            this.json = json;
        }

        @Override
        public void commence(HttpServletRequest request, HttpServletResponse response,
                             AuthenticationException authException) throws IOException {
            json.error(response, HttpStatus.UNAUTHORIZED, "Authentication required", AUTHENTICATION_REQUIRED);
        }
    }

    /** 403 JSON voor {@code /api/**}: {@code CSRF_TOKEN_INVALID} bij een CSRF-fout, anders {@code ACCESS_DENIED}. */
    static final class ApiAccessDeniedHandler implements AccessDeniedHandler {

        private final JsonResponses json;

        ApiAccessDeniedHandler(JsonResponses json) {
            this.json = json;
        }

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response,
                           AccessDeniedException accessDeniedException) throws IOException {
            if (accessDeniedException instanceof CsrfException) {
                json.error(response, HttpStatus.FORBIDDEN, "Invalid or missing CSRF token", CSRF_TOKEN_INVALID);
            } else {
                json.error(response, HttpStatus.FORBIDDEN, "Access denied", ACCESS_DENIED);
            }
        }
    }

    /**
     * Het Prodis-{@code SpaCsrfTokenRequestHandler}-patroon, met één bewuste afwijking (ontwerp par. 2.2,
     * C3): het token wordt <b>alleen uit de header</b> {@code X-XSRF-TOKEN} gelezen, nooit uit een
     * requestparameter. De {@code CsrfFilter} loopt vóór de autorisatie; een {@code getParameter} zou
     * Tomcat een multipart-body (tot 1 GB) volledig laten inlezen, ook bij een anoniem verzoek.
     * <p>
     * De header mag de ruwe cookiewaarde bevatten (zo stuurt de SPA hem) of de gemaskeerde
     * (BREACH-bestendige) waarde die {@link XorCsrfTokenRequestAttributeHandler} als requestattribuut
     * aanbiedt. Eerst wordt ontmaskeren geprobeerd; lukt dat niet, dan geldt de headerwaarde zelf. De
     * eigenlijke (constant-time) vergelijking met het token doet de {@code CsrfFilter}.
     */
    static final class HeaderOnlySpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

        private final XorCsrfTokenRequestAttributeHandler xor = new XorCsrfTokenRequestAttributeHandler();

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
            // BREACH-bescherming voor het token als requestattribuut.
            this.xor.handle(request, response, csrfToken);
            // Laadt het uitgestelde token, zodat de XSRF-TOKEN-cookie gezet wordt als die nog ontbreekt.
            csrfToken.get();
        }

        @Override
        public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
            String header = request.getHeader(csrfToken.getHeaderName());
            if (!StringUtils.hasText(header)) {
                return null;
            }
            String unmasked = this.xor.resolveCsrfTokenValue(new ParameterlessRequest(request), csrfToken);
            return (unmasked != null) ? unmasked : header;
        }
    }

    /** Verbergt elke requestparameter, zodat geen enkele delegate op een parameter (of body) kan terugvallen. */
    static final class ParameterlessRequest extends HttpServletRequestWrapper {

        ParameterlessRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getParameter(String name) {
            return null;
        }

        @Override
        public String[] getParameterValues(String name) {
            return null;
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            return Collections.emptyMap();
        }

        @Override
        public Enumeration<String> getParameterNames() {
            return Collections.emptyEnumeration();
        }
    }

    /**
     * Keycloak-logout (RP-initiated) als 200 JSON {@code {"logoutUrl": "<end_session_endpoint>?id_token_hint=
     * ...&post_logout_redirect_uri=..."}} in plaats van een 302: de SPA doet de logout met een fetch en
     * navigeert daarna zelf. Zonder OIDC-login of end-session-endpoint is {@code logoutUrl} {@code "/"}.
     */
    static final class JsonOidcLogoutSuccessHandler extends OidcClientInitiatedLogoutSuccessHandler {

        private final JsonResponses json;

        JsonOidcLogoutSuccessHandler(ClientRegistrationRepository clientRegistrations, JsonResponses json) {
            super(clientRegistrations);
            this.json = json;
            setPostLogoutRedirectUri("{baseUrl}/");
        }

        @Override
        public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response,
                                    Authentication authentication) throws IOException {
            String logoutUrl = determineTargetUrl(request, response, authentication);
            json.write(response, HttpStatus.OK, Map.of("logoutUrl", logoutUrl));
        }
    }
}
