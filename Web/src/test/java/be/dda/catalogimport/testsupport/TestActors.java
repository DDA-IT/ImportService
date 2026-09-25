package be.dda.catalogimport.testsupport;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;

import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;

/**
 * Aanmelden als een bepaalde gebruiker in een MockMvc-test (Fase 5-AUTH, ontwerp par. 7):
 * {@code mockMvc.perform(post(...).with(as("an.janssens@example.test")))}.
 * <p>
 * Levert een OIDC-login op de nep-registratie {@code keycloak} met {@code preferred_username = username}
 * en {@code sub = "test-sub-" + username}. Wint van de standaardlogin uit
 * {@link TestSecurityConfiguration}.
 */
public final class TestActors {

    private TestActors() {
        // Enkel statische helpers.
    }

    public static OidcLoginRequestPostProcessor as(String username) {
        return oidcLogin()
                .clientRegistration(TestSecurityConfiguration.keycloakRegistration())
                .idToken(token -> token
                        .subject("test-sub-" + username)
                        .claim("preferred_username", username));
    }
}
