package be.dda.catalogimport.web;

import be.dda.catalogimport.service.ActorIdentity;
import be.dda.catalogimport.service.BadRequestException;
import java.util.Set;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * De geverifieerde identiteit van de aangemelde gebruiker (Fase 5-AUTH, ontwerp par. 3) — de <b>enige</b>
 * klasse buiten {@code config} die de {@link SecurityContextHolder} leest. De Service krijgt de identiteit
 * als {@link ActorIdentity} en kent zelf geen Spring Security.
 * <p>
 * Bouwstap 5A-1 levert {@link #current()}; 5A-2 voegt de ondertekeningscontrole {@link #signer} toe, die
 * elke schrijfhandler als eerste Service-gerelateerde stap aanroept. Bouwstap 5B-1 (Fase 5-PERM, ontwerp
 * {@code docs/design/fase5-perm-design.md} par. 1 en 3) voegt {@link #require(Permission)} toe: dezelfde
 * klasse is ook de enige plek waar de <b>hiërarchie</b> van rechten wordt afgeleid en waar de opgehaalde
 * rechten per verzoek gecachet worden.
 */
@Component
public class CurrentActor {

    /** 403: de login levert geen bruikbare identiteit op. */
    public static final String ACTOR_IDENTITY_INVALID = "ACTOR_IDENTITY_INVALID";

    /** 403: {@code system} mag lezen maar nooit ondertekenen — een handtekening is altijd van een mens. */
    public static final String SYSTEM_ACTOR_FORBIDDEN = "SYSTEM_ACTOR_FORBIDDEN";

    /** 400: het actorveld in het request wijst een andere persoon aan dan de aangemelde gebruiker. */
    public static final String ACTOR_FIELD_MISMATCH = "ACTOR_FIELD_MISMATCH";

    /** 403: de aangemelde gebruiker mist het recht dat dit endpoint vraagt (5-PERM). */
    public static final String PERMISSION_DENIED = "PERMISSION_DENIED";

    /** Lengte van de {@code *_by}-kolommen ({@code varchar(100)}): een langere naam wordt geweigerd, nooit afgekapt. */
    static final int MAX_USERNAME_LENGTH = 100;

    /** Requestattribuut met de effectieve rechten van dit ene verzoek (ontwerp 5-PERM par. 3). */
    static final String PERMISSIONS_ATTRIBUTE = CurrentActor.class.getName() + ".EFFECTIVE_PERMISSIONS";

    /** Nooit een ondertekenaar, ook niet bij een geldige login (beslissingslog 2026-09-25). */
    private static final String SYSTEM_USER = "system";

    private final PermissionSource permissionSource;

    public CurrentActor(PermissionSource permissionSource) {
        this.permissionSource = permissionSource;
    }

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
     * De identiteit waaronder de aangemelde gebruiker een geauditeerde actie <b>ondertekent</b> (Fase
     * 5-AUTH, ontwerp par. 3), en de controle op het optionele actorveld uit het request.
     * <p>
     * Drie stappen, in deze volgorde:
     * <ol>
     *   <li>{@link #current()} — zonder bruikbare identiteit is er geen handtekening mogelijk.</li>
     *   <li>Is de aangemelde gebruiker {@code system} (hoofdletterongevoelig), dan 403
     *       {@link #SYSTEM_ACTOR_FORBIDDEN}. Lezen mag {@code system} wel; tekenen nooit.</li>
     *   <li>Draagt het request een actorveld dat niet blanco is en dat — getrimd en
     *       hoofdletterongevoelig — een <b>andere</b> naam aanwijst, dan 400
     *       {@link #ACTOR_FIELD_MISMATCH}. Er wordt niets opgeslagen: wie in een ander tabblad intussen
     *       als iemand anders aangemeld raakte, mag nooit ongemerkt op de verkeerde naam tekenen.</li>
     * </ol>
     * <b>Bewaard wordt altijd de naam uit het token, nooit de spelling uit het request.</b> Een
     * afwijkende hoofdlettering in het request is dus aanvaard maar niet bepalend. De foutboodschap
     * noemt enkel de veldnaam en nooit een van beide waarden — dat zou de naam van de andere
     * aangemelde gebruiker lekken.
     *
     * @param requestValue het actorveld uit het request; {@code null} of blanco = afwezig (A3)
     * @param fieldName    de naam van dat veld, bv. {@code frozenBy}, enkel voor de foutboodschap
     * @throws ActorNotAllowedException 403 {@link #ACTOR_IDENTITY_INVALID} of
     *                                  {@link #SYSTEM_ACTOR_FORBIDDEN}
     * @throws BadRequestException      400 {@link #ACTOR_FIELD_MISMATCH}
     */
    public ActorIdentity signer(String requestValue, String fieldName) {
        ActorIdentity identity = current();
        if (SYSTEM_USER.equalsIgnoreCase(identity.username())) {
            throw new ActorNotAllowedException(SYSTEM_ACTOR_FORBIDDEN,
                    "'" + SYSTEM_USER + "' may read but never sign; sign in as a person");
        }
        if (requestValue != null && !requestValue.isBlank()
                && !requestValue.trim().equalsIgnoreCase(identity.username())) {
            throw new BadRequestException(ACTOR_FIELD_MISMATCH, "The field '" + fieldName + "' does not match the "
                    + "signed-in user; nothing was saved. Reload the page and try again");
        }
        return identity;
    }

    /**
     * Eist dat de aangemelde gebruiker {@code permission} heeft (Fase 5-PERM, ontwerp par. 3).
     * <p>
     * <b>De volledige foutvolgorde, in deze volgorde</b> (keuze mens 2026-09-26, V1 "recht eerst"; 401
     * en {@code CSRF_TOKEN_INVALID} zijn dan al door de filterketen afgehandeld):
     * <ol>
     *   <li>403 {@link #ACTOR_IDENTITY_INVALID} — zonder bruikbare identiteit valt er niets te toetsen.</li>
     *   <li>403 {@link #SYSTEM_ACTOR_FORBIDDEN} — <b>alleen</b> bij {@code MANAGE}/{@code APPROVE}, en
     *       bewust <b>vóór</b> de rechtencheck: dat {@code system} niet mag beheren of goedkeuren is een
     *       eigenschap van {@code system} zelf, niet van zijn toekenningen. Lezen mag {@code system} wel.</li>
     *   <li>503 {@link PermissionSourceUnavailableException} — de bron weet het niet; nooit stil "geen
     *       rechten".</li>
     *   <li>403 {@link #PERMISSION_DENIED} — de boodschap noemt <b>enkel de ontbrekende rechtcode</b>,
     *       nooit de bron, het profiel of wat deze of een andere gebruiker wél heeft.</li>
     * </ol>
     * Pas daarna komt de handler aan bod, en dus ook 400 {@code ACTOR_FIELD_MISMATCH} en 404/409.
     * <p>
     * De hiërarchie wordt hier toegepast: wie {@code APPROVE} heeft, voldoet ook aan {@code MANAGE} en
     * {@code READ}.
     *
     * @return de identiteit van de aangemelde gebruiker, zodat een aanroeper ze niet opnieuw hoeft op te halen
     * @throws ActorNotAllowedException            403 met een van de codes hierboven
     * @throws PermissionSourceUnavailableException 503, de bron is onbereikbaar
     */
    public ActorIdentity require(Permission permission) {
        ActorIdentity identity = current();
        if (permission != Permission.READ && SYSTEM_USER.equalsIgnoreCase(identity.username())) {
            throw new ActorNotAllowedException(SYSTEM_ACTOR_FORBIDDEN,
                    "'" + SYSTEM_USER + "' may read but never manage or approve; sign in as a person");
        }
        if (!effectivePermissions(identity).contains(permission)) {
            throw new ActorNotAllowedException(PERMISSION_DENIED,
                    "You do not have the required permission '" + permission.code() + "'");
        }
        return identity;
    }

    /**
     * De <b>effectieve</b> rechten van deze gebruiker: de ruwe codes van de {@link PermissionSource},
     * uitgebreid met de hiërarchie ({@link Permission#effective}). Dit is de enige plek waar die
     * afleiding gebeurt, zodat {@code GET /me.permissions} (5B-4) en de rechtencheck nooit uit elkaar
     * kunnen lopen.
     * <p>
     * <b>Per verzoek gecachet</b> in een requestattribuut: één HTTP-verzoek bevraagt de bron hoogstens
     * één keer, maar een volgend verzoek bevraagt ze opnieuw — intrekken van een recht werkt zo
     * onmiddellijk (beslissingslog 2026-09-26). Buiten een verzoek (directe aanroep) is er geen cache.
     *
     * @throws PermissionSourceUnavailableException als de bron niet bevraagd kan worden; een storing
     *                                              wordt nooit als een lege set gecachet
     */
    public Set<Permission> effectivePermissions(ActorIdentity identity) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            Object cached = attributes.getAttribute(PERMISSIONS_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
            if (cached instanceof Set<?> set) {
                @SuppressWarnings("unchecked")
                Set<Permission> permissions = (Set<Permission>) set;
                return permissions;
            }
        }
        Set<Permission> effective = Permission.effective(this.permissionSource.permissionsOf(identity));
        if (attributes != null) {
            attributes.setAttribute(PERMISSIONS_ATTRIBUTE, effective, RequestAttributes.SCOPE_REQUEST);
        }
        return effective;
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
