package be.dda.catalogimport.config;

import be.dda.catalogimport.web.TechnicalClientOnly;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * De tweede, aparte filterketen voor de technische client van de datasync (beslissingslog 2026-10-20 M2;
 * {@code docs/design/legacy-config-bundle-design.md} par. 3). Herroept voor <b>exact een pad</b> de keuze "geen
 * resource-server" uit {@code fase5-auth-design.md} par. 2.1/V1 (A3, "aparte, latere beslissing"): de BFF-keten van
 * {@link SecurityConfiguration} blijft ongewijzigd voor al het andere.
 *
 * <h2>Afbakening</h2>
 * <ul>
 *   <li>{@code @Order(1)}, {@code securityMatcher} = {@code POST /api/catalog-import/legacy-config/receipts}, letterlijk.
 *       Elk ander pad (ook {@code GET} op hetzelfde pad) gaat naar de BFF-keten, die geen bearer-token leest: een token
 *       werkt daar niet (401 zonder sessie).</li>
 *   <li><b>Stateless</b>: de beveiligingscontext komt enkel uit het request (nooit uit de sessie), er wordt geen sessie
 *       aangemaakt, geen request bewaard. Een BFF-sessie, ook met {@code MANAGE}, telt op dit pad niet: 401.</li>
 *   <li>CSRF uit, <b>enkel</b> voor deze matcher (een bearer-token wordt niet automatisch door een browser
 *       meegestuurd).</li>
 *   <li>{@code oauth2ResourceServer(jwt)} met de issuer van de bestaande realm. Na de handtekening- en
 *       geldigheidscontrole kent {@link TechnicalClientTokenPolicy} de bevoegdheid
 *       {@code TECHNICAL_CLIENT:legacy-config-push} toe als {@code aud}, {@code azp} en de client role kloppen;
 *       anders 403 {@code ACCESS_DENIED} (geauthenticeerd maar niet bevoegd).</li>
 *   <li>Geen {@code ConfiguredPermissionSource}, geen Prodis-relay, geen {@code hasDdaProdisApiBypass()}-achtige
 *       uitzondering. De identiteit wordt {@code ActorIdentity("svc:" + azp, sub)} via
 *       {@code CurrentActor#technicalClient}.</li>
 * </ul>
 *
 * <h2>Zonder configuratie</h2>
 * Zijn {@code catalogimport.technical-clients.legacy-config.audience} en {@code .client-id} leeg, dan is de functie uit
 * (patroon van de sleutelring en het ophalen: de applicatie start). Deze keten blijft het pad dan afschermen en
 * weigert elk verzoek met 401 {@code AUTHENTICATION_REQUIRED}; er wordt geen token gelezen en er gebeurt geen
 * discovery. Half ingesteld = de applicatie start niet ({@link LegacyConfigTechnicalClientProperties#validate()}).
 *
 * <h2>JwtDecoder</h2>
 * Bestaat er een bean van type {@link JwtDecoder} (tests; of Boot via {@code spring.security.oauth2.resourceserver.*}),
 * dan wordt die gebruikt. Anders een lui ({@link SupplierJwtDecoder}) uit de issuer van de bestaande realm opgebouwde
 * decoder: de applicatie start ook als Keycloak (nog) niet bereikbaar is; de discovery gebeurt bij het eerste token.
 */
@Configuration
public class LegacyConfigPushSecurityConfiguration {

    /** Het enige pad van deze keten. */
    public static final String PUSH_PATH = "/api/catalog-import/legacy-config/receipts";

    static final String AUTHORITY = TechnicalClientOnly.AUTHORITY_PREFIX + TechnicalClientOnly.LEGACY_CONFIG_PUSH;
    static final String ISSUER_PROPERTY = "spring.security.oauth2.client.provider.keycloak.issuer-uri";

    private static final Logger LOG = LoggerFactory.getLogger(LegacyConfigPushSecurityConfiguration.class);

    @Bean
    @Order(1)
    SecurityFilterChain legacyConfigPushFilterChain(HttpSecurity http, LegacyConfigTechnicalClientProperties client,
                                                    ObjectProvider<JwtDecoder> jwtDecoders, ObjectMapper objectMapper,
                                                    @Value("${" + ISSUER_PROPERTY + ":}") String issuer)
            throws Exception {
        client.validate();
        RequestMatcher push = AntPathRequestMatcher.antMatcher(HttpMethod.POST, PUSH_PATH);
        SecurityConfiguration.JsonResponses json = new SecurityConfiguration.JsonResponses(objectMapper);
        AuthenticationEntryPoint unauthenticated = new BearerJsonAuthenticationEntryPoint(json);
        AccessDeniedHandler denied = new TechnicalClientAccessDeniedHandler(json);

        http
                .securityMatcher(push)
                .authorizeHttpRequests(authorize -> authorize.anyRequest()
                        .access(client.enabled() ? technicalClientOnly() : (authentication, context) ->
                                new AuthorizationDecision(false)))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(denied))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(
                        new RequestAttributeSecurityContextRepository()))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);

        if (client.enabled()) {
            JwtDecoder decoder = jwtDecoders.getIfAvailable(() -> issuerDecoder(issuer));
            TechnicalClientTokenPolicy policy = new TechnicalClientTokenPolicy(client.getAudience(),
                    client.getClientId(), client.getRequiredRole());
            http.oauth2ResourceServer(resourceServer -> resourceServer
                    .authenticationEntryPoint(unauthenticated)
                    .accessDeniedHandler(denied)
                    .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(converter(policy))));
            LOG.info("Legacy-config push enabled for technical client {} (audience {}, role {})",
                    client.getClientId(), client.getAudience(), client.getRequiredRole());
        } else {
            LOG.info("Legacy-config push disabled: {}.audience/.client-id not set; {} {} always answers 401",
                    LegacyConfigTechnicalClientProperties.PREFIX, HttpMethod.POST, PUSH_PATH);
        }
        return http.build();
    }

    /** Toegelaten = een JWT-aanmelding met de bevoegdheid {@value #AUTHORITY}; al het andere geweigerd. */
    static AuthorizationManager<RequestAuthorizationContext> technicalClientOnly() {
        return (Supplier<Authentication> authentication, RequestAuthorizationContext context) -> {
            Authentication current = authentication.get();
            boolean granted = current instanceof JwtAuthenticationToken && current.isAuthenticated()
                    && current.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                    .anyMatch(AUTHORITY::equals);
            return new AuthorizationDecision(granted);
        };
    }

    /**
     * JWT → aanmelding. De naam is {@code azp} (de client), zodat geen gebruikersnaam ooit een technische actie
     * ondertekent. De bevoegdheid komt er enkel bij als {@link TechnicalClientTokenPolicy} alle drie de controles
     * goedkeurt; anders een WARN-auditregel met de gefaalde controle en {@code azp}, nooit het token.
     */
    static Converter<Jwt, AbstractAuthenticationToken> converter(TechnicalClientTokenPolicy policy) {
        return jwt -> {
            TechnicalClientTokenPolicy.Verdict verdict = policy.evaluate(jwt);
            Object azp = jwt.getClaims().get("azp");
            String name = (azp instanceof String party && !party.isBlank()) ? party : jwt.getSubject();
            List<GrantedAuthority> authorities = List.of();
            if (verdict == TechnicalClientTokenPolicy.Verdict.ALLOWED) {
                authorities = List.of(new SimpleGrantedAuthority(AUTHORITY));
            } else {
                LOG.warn("LEGACY_CONFIG_PUSH_DENIED azp={} failed={}", printable(name), verdict);
            }
            return new JwtAuthenticationToken(jwt, authorities, name);
        };
    }

    private static JwtDecoder issuerDecoder(String issuer) {
        if (!isAbsoluteHttpUrl(issuer)) {
            throw new IllegalStateException("The legacy-config technical client is enabled but " + ISSUER_PROPERTY
                    + " is not an absolute http(s) URL");
        }
        String location = issuer.trim();
        return new SupplierJwtDecoder(() -> JwtDecoders.fromIssuerLocation(location));
    }

    static boolean isAbsoluteHttpUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** Enkel zichtbare ASCII in een logregel, hoogstens 100 tekens. */
    private static String printable(String value) {
        if (value == null) {
            return "(none)";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length() && out.length() < 100; i++) {
            char c = value.charAt(i);
            out.append(c > 0x20 && c < 0x7f ? c : '?');
        }
        return out.toString();
    }

    /** 401 JSON {@code AUTHENTICATION_REQUIRED} (zelfde vorm als de BFF-keten) met {@code WWW-Authenticate: Bearer}. */
    static final class BearerJsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

        private final SecurityConfiguration.JsonResponses json;

        BearerJsonAuthenticationEntryPoint(SecurityConfiguration.JsonResponses json) {
            this.json = json;
        }

        @Override
        public void commence(HttpServletRequest request, HttpServletResponse response,
                             AuthenticationException authException) throws IOException {
            // Bewust zonder error_description: de reden (verlopen, verkeerde issuer, ...) staat enkel op DEBUG in de log.
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            json.error(response, HttpStatus.UNAUTHORIZED, "Authentication required",
                    SecurityConfiguration.AUTHENTICATION_REQUIRED);
        }
    }

    /**
     * 403 JSON {@code ACCESS_DENIED} voor een geldig token zonder de bevoegdheid. Is de aanmelding <b>geen</b>
     * JWT-aanmelding (bv. een BFF-aanmelding die toch in de context terechtkwam), dan 401: op dit pad bestaat enkel de
     * technische client, en een sessie geldt als "niet aangemeld" — ook met {@code MANAGE}.
     */
    static final class TechnicalClientAccessDeniedHandler implements AccessDeniedHandler {

        private final SecurityConfiguration.JsonResponses json;

        TechnicalClientAccessDeniedHandler(SecurityConfiguration.JsonResponses json) {
            this.json = json;
        }

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response,
                           AccessDeniedException accessDeniedException) throws IOException {
            Authentication current = SecurityContextHolder.getContext().getAuthentication();
            if (!(current instanceof JwtAuthenticationToken)) {
                response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                json.error(response, HttpStatus.UNAUTHORIZED, "Authentication required",
                        SecurityConfiguration.AUTHENTICATION_REQUIRED);
                return;
            }
            json.error(response, HttpStatus.FORBIDDEN, "Access denied", SecurityConfiguration.ACCESS_DENIED);
        }
    }
}
