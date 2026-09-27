package be.dda.catalogimport.testsupport;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;

import be.dda.catalogimport.web.Permission;
import java.util.Arrays;
import java.util.List;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;

/**
 * Aanmelden als een bepaalde gebruiker in een MockMvc-test (Fase 5-AUTH, ontwerp par. 7):
 * {@code mockMvc.perform(post(...).with(as("an.janssens@example.test")))}.
 * <p>
 * Levert een OIDC-login op de nep-registratie {@code keycloak} met {@code preferred_username = username}
 * en {@code sub = "test-sub-" + username}. Wint van de standaardlogin uit
 * {@link TestSecurityConfiguration}.
 * <p>
 * <b>Rechten (Fase 5-PERM, keuze mens 2026-09-26, V3).</b> {@link #as(String)} geeft de gebruiker
 * <b>alle drie</b> de rechten, via de claim {@link TestPermissionSource#CLAIM}. Zo blijven de bestaande
 * ~300 aanroepen ongewijzigd geldig: ze gaan over identiteit en businessgedrag, niet over rechten. Een
 * test die juist wél over rechten gaat, gebruikt {@link #as(String, Permission...)} of
 * {@link #withoutPermissions(String)} en zegt daarmee zichtbaar wat ze bewijst.
 */
public final class TestActors {

    /** Wat de standaardtestlogin krijgt: alles. Weigering bewijs je expliciet, niet per ongeluk. */
    public static final List<String> ALL_PERMISSION_CODES =
            Arrays.stream(Permission.values()).map(Permission::code).toList();

    private TestActors() {
        // Enkel statische helpers.
    }

    /** Aangemeld als {@code username}, met alle drie de rechten (V3). */
    public static OidcLoginRequestPostProcessor as(String username) {
        return login(username, ALL_PERMISSION_CODES);
    }

    /** Aangemeld als {@code username}, met exact de opgegeven <b>ruwe</b> rechten (de hiërarchie komt erbij). */
    public static OidcLoginRequestPostProcessor as(String username, Permission... permissions) {
        return login(username, Arrays.stream(permissions).map(Permission::code).toList());
    }

    /** Aangemeld als {@code username} zonder enig recht — leesbaarder dan {@code as(user, new Permission[0])}. */
    public static OidcLoginRequestPostProcessor withoutPermissions(String username) {
        return login(username, List.of());
    }

    private static OidcLoginRequestPostProcessor login(String username, List<String> permissionCodes) {
        return oidcLogin()
                .clientRegistration(TestSecurityConfiguration.keycloakRegistration())
                .idToken(token -> token
                        .subject("test-sub-" + username)
                        .claim("preferred_username", username)
                        .claim(TestPermissionSource.CLAIM, permissionCodes));
    }
}
