package be.dda.catalogimport.web;

import be.dda.catalogimport.service.ActorIdentity;
import java.util.List;
import java.util.Set;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wie ben ik? (Fase 5-AUTH, ontwerp par. 5.) De SPA laadt dit bij het opstarten en toont onder welke naam
 * de gebruiker tekent.
 * <p>
 * <b>Statuscodes.</b> 200; 401 {@code AUTHENTICATION_REQUIRED} zonder sessie (filterketen); 403
 * {@code ACTOR_IDENTITY_INVALID} bij een onbruikbare identiteit. Een {@code system}-login krijgt 200:
 * lezen mag, tekenen niet (dat bewaakt 5A-2). 503 {@code PERMISSION_SOURCE_UNAVAILABLE} als de rechtenbron
 * onbereikbaar is (Fase 5-PERM, bouwstap 5B-4).
 * <p>
 * {@code permissions} (Fase 5-PERM, bouwstap 5B-4) is altijd een lijst (nooit {@code null}): de effectieve
 * rechtcodes van de aangemelde gebruiker, gesorteerd alfabetisch; lege lijst als geen rechten. Het endpoint
 * laadt ook het CSRF-token, zodat de {@code XSRF-TOKEN}-cookie bestaat vóór de eerste schrijfactie.
 */
@RestController
@RequestMapping("/api/catalog-import/me")
public class CatalogImportMeController {

    /** Antwoord van {@code GET /me}; {@code displayName} en {@code permissions} kunnen {@code null} zijn (5-AUTH), maar {@code permissions} is altijd een lijst (5-PERM). */
    public record MeView(String username, String subject, String displayName, List<String> permissions) {
    }

    private final CurrentActor currentActor;

    public CatalogImportMeController(CurrentActor currentActor) {
        this.currentActor = currentActor;
    }

    @NoPermissionRequired
    @GetMapping
    MeView me(CsrfToken csrfToken) {
        ActorIdentity actor = currentActor.current();
        if (csrfToken != null) {
            // Laadt het (uitgestelde) token: een nieuw token schrijft de XSRF-TOKEN-cookie in dit antwoord.
            csrfToken.getToken();
        }
        Set<Permission> effective = currentActor.effectivePermissions(actor);
        List<String> codes = effective.stream()
                .map(Permission::code)
                .sorted()
                .toList();
        return new MeView(actor.username(), actor.subject(), currentActor.displayName(), codes);
    }
}
