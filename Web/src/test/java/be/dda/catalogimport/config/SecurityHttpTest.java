package be.dda.catalogimport.config;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.testsupport.TestSecurityConfiguration;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.Part;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * De filterketen van Fase 5-AUTH (bouwstap 5A-1, ontwerp {@code docs/design/fase5-auth-design.md} par. 2,
 * 3, 5 en 7): login verplicht, 401/403 als JSON op {@code /api/**}, CSRF enkel uit de header, logout als
 * JSON, {@code GET /me}, en de testinfrastructuur zelf (standaardlogin, expliciete {@code as(X)} wint).
 * <p>
 * Twee MockMvc's: {@link #mockMvc} is de gedeelde, standaard aangemeld als {@code test.user} met een
 * CSRF-header (zoals elke bestaande HTTP-test); {@link #bareMvc} heeft geen enkele standaard — exact wat
 * een browser zonder sessie stuurt.
 */
@SpringBootTest(properties = "catalogimport.screening.recovery-on-startup=false")
@AutoConfigureMockMvc
@ActiveProfiles("local")
class SecurityHttpTest {

    private static final String ME = "/api/catalog-import/me";
    private static final String LOGOUT = "/api/catalog-import/logout";
    /** Sessie-attribuut van de standaard {@code HttpSessionRequestCache}; met {@code NullRequestCache} nooit gezet. */
    private static final String SAVED_REQUEST = "SPRING_SECURITY_SAVED_REQUEST";

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc bareMvc;

    @BeforeEach
    void setUp() {
        bareMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // --- Zonder login ----------------------------------------------------------------------------------

    @Test
    void anAnonymousApiRequestGets401JsonAndNoRedirect() throws Exception {
        MvcResult result = bareMvc.perform(get("/api/catalog-import/batches"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        // Exact de vorm van ApiExceptionHandler: {error, code}, niets meer.
        assertThat(body(result)).containsOnly(
                entry("error", "Authentication required"), entry("code", "AUTHENTICATION_REQUIRED"));
    }

    /** Een schrijfactie met een geldig CSRF-token maar zonder login: 401, geen 403 en niets uitgevoerd. */
    @Test
    void anAnonymousWriteWithAValidCsrfTokenStillGets401() throws Exception {
        bareMvc.perform(post("/api/catalog-import/bundles").with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void anAnonymousPageRequestIsRedirectedToTheKeycloakLogin() throws Exception {
        bareMvc.perform(get("/"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, endsWith("/oauth2/authorization/keycloak")));
    }

    /** De registratie {@code keycloak} hangt aan {@code oauth2Login}: de login-ingang stuurt door naar Keycloak. */
    @Test
    void theLoginEntryPointRedirectsToTheKeycloakAuthorizationEndpoint() throws Exception {
        String location = bareMvc.perform(get("/oauth2/authorization/keycloak"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);

        assertThat(location).startsWith(TestSecurityConfiguration.AUTHORIZATION_ENDPOINT)
                .contains("client_id=catalog-import-test")
                .contains("redirect_uri=");
    }

    /** C2: een geweigerd verzoek wordt nooit bewaard, zodat de browser na login niet op een JSON-URL landt. */
    @Test
    void noRejectedRequestIsSavedForAfterTheLogin() throws Exception {
        MockHttpSession session = new MockHttpSession();

        bareMvc.perform(get(ME).session(session)).andExpect(status().isUnauthorized());
        bareMvc.perform(get("/").session(session)).andExpect(status().isFound());

        assertThat(session.getAttribute(SAVED_REQUEST)).isNull();
    }

    /**
     * {@code /actuator/health} en {@code /actuator/info} staan open. CatalogImport heeft (nog) geen
     * actuator-dependency: het verzoek komt voorbij de beveiliging (geen 401, geen redirect) en eindigt op
     * 404. Wordt 200 zodra actuator toegevoegd is. Een ander actuatorpad blijft wél achter de login.
     */
    @Test
    void healthAndInfoAreOpenButOtherActuatorPathsAreNot() throws Exception {
        bareMvc.perform(get("/actuator/health"))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
        bareMvc.perform(get("/actuator/info"))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
        bareMvc.perform(get("/actuator/env"))
                .andExpect(status().isFound());
    }

    // --- CSRF ------------------------------------------------------------------------------------------

    @Test
    void aWriteWithAnInvalidCsrfTokenGets403CsrfTokenInvalid() throws Exception {
        MvcResult result = bareMvc.perform(post("/api/catalog-import/bundles")
                        .with(as("csrf.user")).with(csrf().asHeader().useInvalidToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        assertThat(body(result)).containsOnly(
                entry("error", "Invalid or missing CSRF token"), entry("code", "CSRF_TOKEN_INVALID"));
    }

    @Test
    void aWriteWithoutCsrfTokenGets403CsrfTokenInvalid() throws Exception {
        bareMvc.perform(post("/api/catalog-import/bundles").with(as("csrf.user"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
    }

    /**
     * C3 op HTTP-niveau: een geldig token als requestparameter ({@code _csrf}) telt niet. Zou het wel tellen,
     * dan kwam de upload bij de controller en antwoordde die met een 404 voor de onbestaande taak.
     */
    @Test
    void aCsrfTokenAsRequestParameterIsIgnoredEvenOnAMultipartUpload() throws Exception {
        bareMvc.perform(multipart("/api/catalog-import/tasks/{id}/deliveries", 999_999_999L)
                        .file(new MockMultipartFile("file", "levering.csv", "text/csv",
                                "A;B\n1;2\n".getBytes(StandardCharsets.UTF_8)))
                        .param("deliveryReference", "REF-CSRF")
                        .param("uploadedBy", "csrf.user")
                        .with(as("csrf.user"))
                        .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
    }

    // --- GET /me ---------------------------------------------------------------------------------------

    @Test
    void meReturnsTheDefaultTestIdentityWithPermissionsNull() throws Exception {
        MvcResult result = mockMvc.perform(get(ME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(TestSecurityConfiguration.DEFAULT_USERNAME))
                .andExpect(jsonPath("$.subject").value(TestSecurityConfiguration.DEFAULT_SUBJECT))
                .andReturn();

        Map<String, Object> body = body(result);
        assertThat(body).containsOnlyKeys("username", "subject", "displayName", "permissions");
        // "Niet vastgesteld" is null, nooit een lege lijst (ontwerp par. 5).
        assertThat(body.get("permissions")).isNull();
        assertThat(body.get("displayName")).isNull();
    }

    /** De merge-volgorde van het standaardverzoek: een expliciete {@code .with(as(X))} wint. */
    @Test
    void anExplicitActorWinsFromTheDefaultLogin() throws Exception {
        mockMvc.perform(get(ME).with(as("an.janssens@example.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("an.janssens@example.test"))
                .andExpect(jsonPath("$.subject").value("test-sub-an.janssens@example.test"));
    }

    @Test
    void meTrimsTheUsernameAndReturnsTheDisplayName() throws Exception {
        mockMvc.perform(get(ME).with(login(token -> token.subject("sub-jan")
                        .claim("preferred_username", "  jan.peeters  ")
                        .claim("name", " Jan Peeters "))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("jan.peeters"))
                .andExpect(jsonPath("$.subject").value("sub-jan"))
                .andExpect(jsonPath("$.displayName").value("Jan Peeters"));
    }

    @Test
    void meWithoutPreferredUsernameGets403ActorIdentityInvalid() throws Exception {
        MvcResult result = mockMvc.perform(get(ME).with(login(token -> token.subject("sub-without-username"))))
                .andExpect(status().isForbidden())
                .andReturn();

        assertThat(body(result)).containsOnlyKeys("error", "code").containsEntry("code", "ACTOR_IDENTITY_INVALID");
    }

    @Test
    void meWithABlankPreferredUsernameGets403() throws Exception {
        mockMvc.perform(get(ME).with(login(token -> token.subject("sub-blank").claim("preferred_username", "   "))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTOR_IDENTITY_INVALID"));
    }

    /** {@code *_by} is {@code varchar(100)}: 100 tekens mag, 101 wordt geweigerd — nooit afgekapt. */
    @Test
    void theUsernameMayBeAtMost100Characters() throws Exception {
        String hundred = "u".repeat(100);
        mockMvc.perform(get(ME).with(login(token -> token.subject("sub-100").claim("preferred_username", hundred))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(hundred));
        mockMvc.perform(get(ME).with(login(token -> token.subject("sub-101")
                        .claim("preferred_username", hundred + "u"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTOR_IDENTITY_INVALID"));
    }

    @Test
    void theSubjectMayBeAtMost255Characters() throws Exception {
        String subject255 = "s".repeat(255);
        mockMvc.perform(get(ME).with(login(token -> token.subject(subject255).claim("preferred_username", "long.sub"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value(subject255));
        mockMvc.perform(get(ME).with(login(token -> token.subject(subject255 + "s")
                        .claim("preferred_username", "long.sub"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTOR_IDENTITY_INVALID"));
    }

    /** Een login die geen OIDC-login is (hier een gewone gebruikersnaam/wachtwoord-token) levert geen identiteit. */
    @Test
    void aNonOidcLoginGets403ActorIdentityInvalid() throws Exception {
        mockMvc.perform(get(ME).with(user("plain.user")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTOR_IDENTITY_INVALID"));
    }

    /** Lezen mag voor iedereen die aangemeld is, ook {@code system}; tekenen wordt in 5A-2 geweigerd. */
    @Test
    void aSystemLoginMayStillReadMe() throws Exception {
        mockMvc.perform(get(ME).with(as("system")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("system"));
    }

    @Test
    void meWithoutLoginGets401() throws Exception {
        bareMvc.perform(get(ME))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    // --- Logout ----------------------------------------------------------------------------------------

    /**
     * Een echte sessielogin (context in de sessie, zoals na de Keycloak-callback): logout geeft 200 met de
     * end-session-URL, maakt de sessie ongeldig, en daarna geeft dezelfde sessie 401.
     */
    @Test
    void logoutReturnsTheKeycloakLogoutUrlAndEndsTheSession() throws Exception {
        MockHttpSession session = sessionLoggedInAs("logout.user");
        bareMvc.perform(get(ME).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("logout.user"));

        MvcResult logout = bareMvc.perform(post(LOGOUT).session(session).with(csrf().asHeader()))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        String logoutUrl = JsonPath.read(logout.getResponse().getContentAsString(), "$.logoutUrl");
        assertThat(logoutUrl).startsWith(TestSecurityConfiguration.END_SESSION_ENDPOINT + "?")
                .contains("id_token_hint=id-token-logout")
                .contains("post_logout_redirect_uri=");
        assertThat(session.isInvalid()).isTrue();

        bareMvc.perform(get(ME).session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void logoutWithoutCsrfTokenIsRefused() throws Exception {
        MockHttpSession session = sessionLoggedInAs("logout.nocsrf");

        bareMvc.perform(post(LOGOUT).session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));

        assertThat(session.isInvalid()).isFalse();
    }

    /** Een expliciete actor laat de standaard-CSRF-header van de gedeelde MockMvc intact. */
    @Test
    void anExplicitActorKeepsTheDefaultCsrfHeader() throws Exception {
        mockMvc.perform(post(LOGOUT).with(as("an.janssens@example.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logoutUrl", startsWith(TestSecurityConfiguration.END_SESSION_ENDPOINT)));
    }

    // --- Eenheidstests op de onderdelen van de keten -----------------------------------------------------

    @Test
    void startupIsRefusedWithoutTheKeycloakRegistration() {
        ClientRegistration other = ClientRegistration
                .withClientRegistration(TestSecurityConfiguration.keycloakRegistration())
                .registrationId("other").build();

        assertThatThrownBy(() -> SecurityConfiguration.requireUsableRegistration(
                new InMemoryClientRegistrationRepository(other)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'keycloak' is missing");
        // Geen enkele registratie in de properties: Boot maakt dan helemaal geen repository aan.
        assertThatThrownBy(() -> SecurityConfiguration.requireUsableRegistration(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'keycloak' is missing");
    }

    @Test
    void startupIsRefusedWithAnEmptyOrUnresolvedClientSecret() {
        for (String secret : new String[] {"", "   ", "${CATALOG_OIDC_CLIENT_SECRET}"}) {
            ClientRegistration registration = ClientRegistration
                    .withClientRegistration(TestSecurityConfiguration.keycloakRegistration())
                    .clientSecret(secret).build();

            assertThatThrownBy(() -> SecurityConfiguration.requireUsableRegistration(
                    new InMemoryClientRegistrationRepository(registration)))
                    .as("secret '%s'", secret)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CATALOG_OIDC_CLIENT_SECRET");
        }
    }

    @Test
    void startupIsAcceptedWithAUsableRegistrationAndTheMessageNeverShowsTheSecret() {
        assertThatCode(() -> SecurityConfiguration.requireUsableRegistration(
                new InMemoryClientRegistrationRepository(TestSecurityConfiguration.keycloakRegistration())))
                .doesNotThrowAnyException();

        ClientRegistration leaky = ClientRegistration
                .withClientRegistration(TestSecurityConfiguration.keycloakRegistration())
                .clientSecret("${oops-secret-fragment").build();
        assertThatThrownBy(() -> SecurityConfiguration.requireUsableRegistration(
                new InMemoryClientRegistrationRepository(leaky)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("oops-secret-fragment");
    }

    @Test
    void theApiAccessDeniedHandlerDistinguishesCsrfFromOtherRefusals() throws Exception {
        SecurityConfiguration.ApiAccessDeniedHandler handler =
                new SecurityConfiguration.ApiAccessDeniedHandler(new SecurityConfiguration.JsonResponses(objectMapper));

        MockHttpServletResponse csrfResponse = new MockHttpServletResponse();
        handler.handle(new MockHttpServletRequest("POST", "/api/x"), csrfResponse, new MissingCsrfTokenException(null));
        assertThat(csrfResponse.getStatus()).isEqualTo(403);
        assertThat(body(csrfResponse.getContentAsString())).containsOnly(
                entry("error", "Invalid or missing CSRF token"), entry("code", "CSRF_TOKEN_INVALID"));

        MockHttpServletResponse deniedResponse = new MockHttpServletResponse();
        handler.handle(new MockHttpServletRequest("GET", "/api/x"), deniedResponse, new AccessDeniedException("no"));
        assertThat(deniedResponse.getStatus()).isEqualTo(403);
        assertThat(body(deniedResponse.getContentAsString())).containsOnly(
                entry("error", "Access denied"), entry("code", "ACCESS_DENIED"));
    }

    /**
     * C3 op filterniveau, met de echte {@link CsrfFilter} en {@link CookieCsrfTokenRepository}: een geldig
     * token als parameter op een multipart-POST wordt geweigerd <b>zonder</b> dat de filter ooit een
     * parameter of de body leest (elke poging daartoe laat deze test falen).
     */
    @Test
    void theCsrfFilterNeverReadsParametersOrTheBody() throws Exception {
        String token = UUID.randomUUID().toString();
        ParameterTrapRequest request = new ParameterTrapRequest("POST", "/api/catalog-import/tasks/1/deliveries");
        request.setContentType("multipart/form-data; boundary=x");
        request.setCookies(new Cookie("XSRF-TOKEN", token));
        request.addParameter("_csrf", token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        csrfFilter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).as("the request must not pass the CSRF filter").isNull();
    }

    @Test
    void theCsrfFilterAcceptsTheRawCookieValueInTheHeader() throws Exception {
        String token = UUID.randomUUID().toString();
        ParameterTrapRequest request = new ParameterTrapRequest("POST", "/api/catalog-import/bundles");
        request.setCookies(new Cookie("XSRF-TOKEN", token));
        request.addHeader("X-XSRF-TOKEN", token);
        MockFilterChain chain = new MockFilterChain();

        csrfFilter().doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    /** De gemaskeerde (BREACH-bestendige) waarde in de header telt ook — zo levert {@code csrf().asHeader()} ze. */
    @Test
    void theCsrfFilterAcceptsTheMaskedTokenInTheHeaderAndRefusesAWrongOne() throws Exception {
        String token = UUID.randomUUID().toString();
        MockHttpServletRequest scratch = new MockHttpServletRequest();
        new XorCsrfTokenRequestAttributeHandler().handle(scratch, new MockHttpServletResponse(),
                () -> new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", token));
        String masked = ((CsrfToken) scratch.getAttribute(CsrfToken.class.getName())).getToken();
        assertThat(masked).isNotEqualTo(token);

        ParameterTrapRequest maskedRequest = new ParameterTrapRequest("POST", "/api/catalog-import/bundles");
        maskedRequest.setCookies(new Cookie("XSRF-TOKEN", token));
        maskedRequest.addHeader("X-XSRF-TOKEN", masked);
        MockFilterChain maskedChain = new MockFilterChain();
        csrfFilter().doFilter(maskedRequest, new MockHttpServletResponse(), maskedChain);
        assertThat(maskedChain.getRequest()).isNotNull();

        ParameterTrapRequest wrongRequest = new ParameterTrapRequest("POST", "/api/catalog-import/bundles");
        wrongRequest.setCookies(new Cookie("XSRF-TOKEN", token));
        wrongRequest.addHeader("X-XSRF-TOKEN", UUID.randomUUID().toString());
        MockHttpServletResponse wrongResponse = new MockHttpServletResponse();
        MockFilterChain wrongChain = new MockFilterChain();
        csrfFilter().doFilter(wrongRequest, wrongResponse, wrongChain);
        assertThat(wrongResponse.getStatus()).isEqualTo(403);
        assertThat(wrongChain.getRequest()).isNull();
    }

    /** Het eerste verzoek zonder cookie (typisch {@code GET /me}) krijgt een door JavaScript leesbare XSRF-TOKEN-cookie. */
    @Test
    void aRequestWithoutCookieReceivesAReadableXsrfTokenCookie() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", ME);
        MockHttpServletResponse response = new MockHttpServletResponse();

        csrfFilter().doFilter(request, response, new MockFilterChain());

        Cookie cookie = response.getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isNotBlank();
        assertThat(cookie.isHttpOnly()).isFalse();
    }

    // --- Helpers ---------------------------------------------------------------------------------------

    private static CsrfFilter csrfFilter() {
        CsrfFilter filter = new CsrfFilter(CookieCsrfTokenRepository.withHttpOnlyFalse());
        filter.setRequestHandler(new SecurityConfiguration.HeaderOnlySpaCsrfTokenRequestHandler());
        filter.setAccessDeniedHandler((request, response, denied) -> response.setStatus(403));
        return filter;
    }

    private static OidcLoginRequestPostProcessor login(Consumer<OidcIdToken.Builder> idToken) {
        return oidcLogin().clientRegistration(TestSecurityConfiguration.keycloakRegistration()).idToken(idToken);
    }

    /** Een sessie zoals ze er na een echte Keycloak-login uitziet: de SecurityContext staat in de sessie. */
    private static MockHttpSession sessionLoggedInAs(String username) {
        OidcIdToken idToken = OidcIdToken.withTokenValue("id-token-logout")
                .subject("test-sub-" + username)
                .claim("preferred_username", username)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        DefaultOidcUser principal = new DefaultOidcUser(AuthorityUtils.createAuthorityList("OIDC_USER"), idToken, "sub");
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new OAuth2AuthenticationToken(principal, principal.getAuthorities(),
                TestSecurityConfiguration.REGISTRATION_ID));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContext);
        return session;
    }

    private Map<String, Object> body(MvcResult result) throws Exception {
        return body(result.getResponse().getContentAsString());
    }

    private Map<String, Object> body(String json) throws Exception {
        return objectMapper.readValue(json, new TypeReference<>() {
        });
    }

    /** Faalt zodra iemand een parameter, part of de body leest (C3: dat zou Tomcat de multipart laten inlezen). */
    private static final class ParameterTrapRequest extends MockHttpServletRequest {

        ParameterTrapRequest(String method, String uri) {
            super(method, uri);
        }

        private static AssertionError trap() {
            return new AssertionError("CSRF resolution must never read request parameters or the body (C3)");
        }

        @Override
        public String getParameter(String name) {
            throw trap();
        }

        @Override
        public String[] getParameterValues(String name) {
            throw trap();
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            throw trap();
        }

        @Override
        public Enumeration<String> getParameterNames() {
            throw trap();
        }

        @Override
        public ServletInputStream getInputStream() {
            throw trap();
        }

        @Override
        public BufferedReader getReader() {
            throw trap();
        }

        @Override
        public Collection<Part> getParts() {
            throw trap();
        }

        @Override
        public Part getPart(String name) {
            throw trap();
        }
    }
}
