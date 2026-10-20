package be.dda.catalogimport.web;

import be.dda.catalogimport.service.ActorIdentity;
import be.dda.catalogimport.web.ProdisAccountRelay.ProdisAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * De andere apps die de productwisselaar linksboven aanbiedt (docs/decisions.md 2026-10-20
 * "Frontend-ombouw volgens de Prodis-mock + productwisselaar"), voor {@code GET /me.apps}. Zelfde regels als
 * Prodis' {@code product-switcher.tsx}, in deze vaste volgorde, de huidige app (CatalogImport) nooit erbij:
 * <ol>
 *   <li>{@code prodis} als {@code catalogimport.app-switcher.prodis-url} gezet is;</li>
 *   <li>{@code profin} ({@code prodis-url + }{@value #PROFIN_PATH}, een route binnen de Prodis-SPA) als de relay
 *       lukt, de gebruiker System Admin is of {@code profin.access} heeft, en {@code prodis-url} gezet is;</li>
 *   <li>{@code prodev} als de relay een (geldige) {@code prodevUrl} teruggeeft — Prodis heeft feature, URL en
 *       recht daarvoor al beoordeeld.</li>
 * </ol>
 * <b>Cache per sessie.</b> Het relay-antwoord wordt in de {@link HttpSession} bewaard: een geslaagd antwoord
 * {@code cache-ttl} lang (default 5 min), een mislukte relay {@code failure-cache-ttl} lang (default 1 min), zodat
 * niet elke {@code /me} Prodis aanroept en een onbereikbaar Prodis niet elke {@code /me} met de timeout vertraagt.
 * Het item onthoudt voor wie het gehaald is (subject + username): een andere login in dezelfde sessie haalt opnieuw.
 * Logout vernietigt de sessie en dus de cache. Gevolg: een wijziging van {@code profin.access} of
 * {@code prodevUrl} in Prodis is hoogstens {@code cache-ttl} later zichtbaar — aanvaardbaar, want dit is enkel
 * een link en ProFin/ProDev controleren hun eigen rechten.
 */
@Component
public class AppSwitcher {

    /** Route van ProFin binnen de Prodis-SPA (Prodis {@code paths.dashboard.profin.root}). */
    static final String PROFIN_PATH = "/dashboard/profin";

    static final String CACHE_ATTRIBUTE = AppSwitcher.class.getName() + ".PRODIS_ACCOUNT";

    /** Eén app in de wisselaar; naam en logo bepaalt de client. */
    public record AppLink(String id, String url) {
    }

    /** Sessie-item; {@code account == null} = de relay is mislukt. */
    record CachedAccount(String subject, String username, Instant fetchedAt, ProdisAccount account)
            implements Serializable {
    }

    private final AppSwitcherProperties properties;
    private final ProdisAccountRelay relay;
    private final Clock clock;

    public AppSwitcher(AppSwitcherProperties properties, ProdisAccountRelay relay, Clock clock) {
        this.properties = properties;
        this.relay = relay;
        this.clock = clock;
    }

    /** De apps voor deze gebruiker; nooit {@code null}, gooit nooit (de relay is fail-soft). */
    public List<AppLink> appsFor(ActorIdentity actor, Authentication principal, HttpServletRequest request,
                                 HttpServletResponse response) {
        ProdisAccount account = this.properties.relayConfigured()
                ? account(actor, principal, request, response) : null;
        return apps(this.properties.getProdisUrl(), account);
    }

    /** De zichtbaarheidsregels zelf; {@code account == null} = geen relay of relay mislukt. */
    static List<AppLink> apps(String prodisUrl, ProdisAccount account) {
        List<AppLink> apps = new ArrayList<>(3);
        if (prodisUrl != null) {
            apps.add(new AppLink("prodis", prodisUrl));
            if (account != null && (account.systemAdmin() || account.profinAccess())) {
                apps.add(new AppLink("profin", prodisUrl + PROFIN_PATH));
            }
        }
        if (account != null && account.prodevUrl() != null) {
            apps.add(new AppLink("prodev", account.prodevUrl()));
        }
        return List.copyOf(apps);
    }

    private ProdisAccount account(ActorIdentity actor, Authentication principal, HttpServletRequest request,
                                  HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        Instant now = this.clock.instant();
        if (session != null && session.getAttribute(CACHE_ATTRIBUTE) instanceof CachedAccount cached
                && Objects.equals(cached.subject(), actor.subject())
                && Objects.equals(cached.username(), actor.username())
                && now.isBefore(cached.fetchedAt().plus(ttl(cached)))
                && !now.isBefore(cached.fetchedAt())) {
            return cached.account();
        }
        ProdisAccount account = this.relay.fetch(principal, request, response).orElse(null);
        if (session != null) {
            try {
                session.setAttribute(CACHE_ATTRIBUTE, new CachedAccount(actor.subject(), actor.username(), now, account));
            } catch (IllegalStateException sessionInvalidated) {
                // De sessie is intussen ongeldig gemaakt (bv. gelijktijdige logout): niets te cachen.
            }
        }
        return account;
    }

    private Duration ttl(CachedAccount cached) {
        return (cached.account() == null) ? this.properties.getFailureCacheTtl() : this.properties.getCacheTtl();
    }
}
