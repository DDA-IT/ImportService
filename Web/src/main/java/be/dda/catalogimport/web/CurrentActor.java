package be.dda.catalogimport.web;

import be.dda.catalogimport.service.ActorIdentity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * De geverifieerde identiteit van de aangemelde gebruiker (Fase 5-AUTH, ontwerp par. 3) — de <b>enige</b>
 * klasse buiten {@code config} die de {@link SecurityContextHolder} leest. De Service krijgt de identiteit
 * als {@link ActorIdentity} en kent zelf geen Spring Security.
 * <p>
 * Bouwstap 5A-1 levert enkel {@link #current()}; de ondertekeningscontrole ({@code signer}) volgt in 5A-2.
 */
@Component
public class CurrentActor {

    /** 403: de login levert geen bruikbare identiteit op. */
    public static final String ACTOR_IDENTITY_INVALID = "ACTOR_IDENTITY_INVALID";

    /** Lengte van de {@code *_by}-kolommen ({@code varchar(100)}): een langere naam wordt geweigerd, nooit afgekapt. */
    static final int MAX_USERNAME_LENGTH = 100;

    /**
     * De identiteit van de aangemelde gebruiker: {@code username} = claim {@code preferred_username}
     * (getrimd), {@code subject} = {@code sub} (ongewijzigd).
     *
     * @throws ActorNotAllowedException 403 {@code ACTOR_IDENTITY_INVALID} als de login geen OIDC-login is,
     *         of als username of subject ontbreekt, blanco is of te lang is (username &gt;
     *         {@value #MAX_USERNAME_LENGTH}, subject &gt; {@value ActorIdentity#MAX_SUBJECT_LENGTH} tekens)
     * @throws AuthenticationCredentialsNotFoundException zonder login; de filterketen maakt daar een 401
     *         van (defensief: de keten laat een anoniem verzoek op {@code /api/**} niet eens door)
     */
    public ActorIdentity current() {
        OidcUser user = oidcUser();
        String subject = user.getSubject();
        if (subject == null || subject.isBlank()) {
            throw invalid("The login has no subject");
        }
        if (subject.length() > ActorIdentity.MAX_SUBJECT_LENGTH) {
            throw invalid("The login subject exceeds " + ActorIdentity.MAX_SUBJECT_LENGTH + " characters");
        }
        String username = user.getPreferredUsername();
        if (username == null || username.isBlank()) {
            throw invalid("The login has no preferred_username");
        }
        username = username.trim();
        if (username.length() > MAX_USERNAME_LENGTH) {
            throw invalid("The login preferred_username exceeds " + MAX_USERNAME_LENGTH + " characters");
        }
        return new ActorIdentity(username, subject);
    }

    /**
     * De weergavenaam (claim {@code name}), getrimd; {@code null} als die ontbreekt of blanco is. Enkel
     * voor {@code GET /me}; roep eerst {@link #current()} aan, dat de login valideert.
     */
    String displayName() {
        String name = oidcUser().getFullName();
        return (name == null || name.isBlank()) ? null : name.trim();
    }

    private static OidcUser oidcUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken
                || !authentication.isAuthenticated()) {
            throw new AuthenticationCredentialsNotFoundException("Authentication required");
        }
        if (authentication instanceof OAuth2AuthenticationToken
                && authentication.getPrincipal() instanceof OidcUser user) {
            return user;
        }
        throw invalid("The login is not an OpenID Connect login");
    }

    private static ActorNotAllowedException invalid(String message) {
        return new ActorNotAllowedException(ACTOR_IDENTITY_INVALID, message);
    }
}
