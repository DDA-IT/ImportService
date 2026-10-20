package be.dda.catalogimport.web;

import be.dda.catalogimport.service.ActorIdentity;
import be.dda.catalogimport.web.AppSwitcher.AppLink;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
 * laadt ook het CSRF-token, zodat de {@code CATALOGIMPORT-XSRF-TOKEN}-cookie bestaat vóór de eerste schrijfactie.
 * <p>
 * {@code apps} (productwisselaar, docs/decisions.md 2026-10-20) is altijd een lijst: de andere apps
 * ({@code prodis}, {@code profin}, {@code prodev}, in die volgorde) als {@code {id, url}}, zie {@link AppSwitcher}.
 * Een Prodis die niet antwoordt verandert de status van dit endpoint nooit (fail-soft); ook een gebruiker
 * zonder CatalogImport-rechten krijgt de lijst.
 */
@RestController
@RequestMapping("/api/catalog-import/me")
public class CatalogImportMeController {

    /**
     * Antwoord van {@code GET /me}; {@code displayName} kan {@code null} zijn (5-AUTH), {@code permissions} (5-PERM)
     * en {@code apps} (productwisselaar) zijn altijd een lijst.
     */
    public record MeView(String username, String subject, String displayName, List<String> permissions,
                         List<AppLink> apps) {
    }

    private final CurrentActor currentActor;
    private final AppSwitcher appSwitcher;

    public CatalogImportMeController(CurrentActor currentActor, AppSwitcher appSwitcher) {
        this.currentActor = currentActor;
        this.appSwitcher = appSwitcher;
    }

    @NoPermissionRequired
    @GetMapping
    MeView me(CsrfToken csrfToken, HttpServletRequest request, HttpServletResponse response) {
        ActorIdentity actor = currentActor.current();
        if (csrfToken != null) {
            // Laadt het (uitgestelde) token: een nieuw token schrijft de CATALOGIMPORT-XSRF-TOKEN-cookie in dit antwoord.
            csrfToken.getToken();
        }
        Set<Permission> effective = currentActor.effectivePermissions(actor);
        List<String> codes = effective.stream()
                .map(Permission::code)
                .sorted()
                .toList();
        List<AppLink> apps = appSwitcher.appsFor(actor, currentActor.authentication(), request, response);
        return new MeView(actor.username(), actor.subject(), currentActor.displayName(), codes, apps);
    }
}
