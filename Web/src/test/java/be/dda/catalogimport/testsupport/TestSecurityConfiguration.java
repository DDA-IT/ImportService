package be.dda.catalogimport.testsupport;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.Map;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/**
 * Laat elke {@code @SpringBootTest} draaien zonder Keycloak en zonder netwerk (Fase 5-AUTH, ontwerp par. 7).
 * <p>
 * <b>Bewust een gewone {@code @Configuration}, geen {@code @TestConfiguration}</b> (C1): de component-scan
 * van {@code CatalogImportApplication} (pakket {@code be.dda.catalogimport}) pikt hem op de
 * test-classpath op, zodat de bestaande testklassen ongewijzigd blijven. Hij zit enkel in
 * {@code src/test} en dus nooit in het artefact.
 * <ol>
 *   <li>Een nep-{@link ClientRegistrationRepository} (Prodis-patroon, met {@code end_session_endpoint}): de
 *       Boot-autoconfiguratie trekt zich terug, er gebeurt geen OIDC-discovery tegen de issuer.</li>
 *   <li>Een {@link MockMvcBuilderCustomizer}: elk MockMvc-verzoek is standaard aangemeld als
 *       {@value #DEFAULT_USERNAME} (subject {@value #DEFAULT_SUBJECT}) en draagt een geldig CSRF-token
 *       <b>als header</b> — de enige plek waar de applicatie het leest (C3). Een expliciete
 *       {@code .with(TestActors.as(X))} in een test wint: zijn postprocessor loopt na die van dit
 *       standaardverzoek.</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
public class TestSecurityConfiguration {

    public static final String REGISTRATION_ID = "keycloak";
    public static final String ISSUER = "https://keycloak.test.invalid/realms/catalog-import-test";
    public static final String END_SESSION_ENDPOINT = ISSUER + "/protocol/openid-connect/logout";
    public static final String AUTHORIZATION_ENDPOINT = ISSUER + "/protocol/openid-connect/auth";
    public static final String DEFAULT_USERNAME = "test.user";
    public static final String DEFAULT_SUBJECT = "test-sub-default";

    @Bean
    ClientRegistrationRepository clientRegistrationRepository() {
        return new InMemoryClientRegistrationRepository(keycloakRegistration());
    }

    @Bean
    MockMvcBuilderCustomizer authenticatedWithCsrfByDefault() {
        return builder -> builder.defaultRequest(get("/")
                .with(oidcLogin()
                        .clientRegistration(keycloakRegistration())
                        .idToken(token -> token
                                .subject(DEFAULT_SUBJECT)
                                .claim("preferred_username", DEFAULT_USERNAME)))
                .with(csrf().asHeader()));
    }

    /** De nep-registratie {@value #REGISTRATION_ID}; enkel onbereikbare {@code .invalid}-URL's. */
    public static ClientRegistration keycloakRegistration() {
        return ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId("catalog-import-test")
                .clientSecret("test-secret")
                .clientName("Keycloak (test)")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "profile", "email")
                .issuerUri(ISSUER)
                .authorizationUri(AUTHORIZATION_ENDPOINT)
                .tokenUri(ISSUER + "/protocol/openid-connect/token")
                .jwkSetUri(ISSUER + "/protocol/openid-connect/certs")
                .userInfoUri(ISSUER + "/protocol/openid-connect/userinfo")
                .userNameAttributeName("preferred_username")
                .providerConfigurationMetadata(Map.of("end_session_endpoint", END_SESSION_ENDPOINT))
                .build();
    }
}
