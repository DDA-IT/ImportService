package be.dda.catalogimport.web;

import be.dda.catalogimport.service.ActorIdentity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * De rechtenbron Prodis (5B-7, {@code docs/design/fase5-perm-5b7-design.md} par. 2; beslissingslog 2026-10-22 V1-V4):
 * de rechten van de aangemelde gebruiker uit Prodis' {@code GET /api/account}, met het access token van de BFF-sessie.
 * Actief bij {@code catalogimport.permissions.source=PRODIS}; geen {@code @Component}: de bronkeuze
 * ({@code PermissionSourceConfiguration}) maakt precies één {@link PermissionSource}.
 *
 * <h2>Business rule vs. implementatie</h2>
 * <ul>
 *   <li><b>Regel:</b> enkel {@code catalogImport.read/.manage/.approve} tellen. <b>Implementatie:</b> exacte, volledige
 *       code na trimmen ({@link Permission#code()}), niet {@link Permission#parse} (die korte namen en andere
 *       hoofdlettering aanvaardt — voor een externe bron te ruim). Al het overige wordt genegeerd. De hiërarchie blijft
 *       in {@link Permission#effective}. {@code systemAdmin} wordt niet gelezen: Prodis geeft een System Admin zelf al
 *       alle codes (V3).</li>
 *   <li><b>Regel:</b> fail-closed — "we weten het niet" is nooit "geen rechten". <b>Implementatie:</b> {@code permissions}
 *       moet een array van strings zijn; ontbreekt het, is het {@code null} of van een ander type, dan 503. Een geldige
 *       lege lijst is een lege set (de gebruiker heeft niets).</li>
 *   <li><b>Regel (V4 = B):</b> verloren token (geweigerde refresh, geen geautoriseerde client) = 401
 *       {@code AUTHENTICATION_REQUIRED} ({@link PermissionSourceAuthenticationLostException}); elke andere fout (timeout,
 *       verbinding, 3xx/4xx/5xx, te grote body, geen of onverwachte JSON, geen lopend verzoek) = 503
 *       {@code PERMISSION_SOURCE_UNAVAILABLE}.</li>
 *   <li><b>Regel (V1 = B):</b> enkel een geslaagd antwoord wordt {@code cache-ttl} (default PT30S) per sessie bewaard,
 *       gekoppeld aan subject + username: een andere login in dezelfde sessie haalt opnieuw. Fouten worden nooit
 *       gecachet. Gevolg: een intrekking in Prodis geldt hoogstens {@code cache-ttl} later. Eigen sessie-item, nooit de
 *       cache van {@link AppSwitcher} (die is fail-soft en cachet ook fouten).</li>
 * </ul>
 * Logging: per fout één WARN {@code Permission source (Prodis) unavailable (<reden>)} met enkel een veilige reden
 * (klassenaam, OAuth2-foutcode, HTTP-status) — nooit token, body of username.
 * <p>
 * Het verzoek en antwoord komen uit {@link RequestContextHolder} (de poort {@link PermissionSource} heeft ze niet); de
 * principal is {@code OAuth2AuthorizeRequest.principal(username)}, zodat deze klasse de SecurityContext niet leest en
 * er geen cyclus met {@link CurrentActor} ontstaat.
 */
public class ProdisPermissionSource implements PermissionSource {

    static final String CACHE_ATTRIBUTE = ProdisPermissionSource.class.getName() + ".PERMISSIONS";

    private static final Logger LOG = LoggerFactory.getLogger(ProdisPermissionSource.class);

    /** Sessie-item: enkel na een geslaagd antwoord, voor precies deze login. */
    record CachedPermissions(String subject, String username, Instant fetchedAt, Set<Permission> permissions)
            implements Serializable {
    }

    private final ProdisAccountClient client;
    private final Duration cacheTtl;
    private final Clock clock;

    /**
     * @throws IllegalStateException zonder {@code catalogimport.permissions.prodis.account-url}
     */
    public ProdisPermissionSource(PermissionProperties.Prodis properties,
                                  ObjectProvider<ClientRegistrationRepository> clientRegistrations,
                                  OAuth2AuthorizedClientRepository authorizedClients,
                                  ObjectMapper objectMapper,
                                  Clock clock) {
        String accountUrl = properties.getAccountUrl();
        if (accountUrl == null) {
            throw new IllegalStateException(PermissionProperties.Prodis.PREFIX + ".account-url is required when "
                    + PermissionProperties.PREFIX + ".source=PRODIS (set CATALOG_PRODIS_ACCOUNT_URL)");
        }
        this.client = new ProdisAccountClient(() -> accountUrl, properties.getConnectTimeout(),
                properties.getReadTimeout(), clientRegistrations, authorizedClients, objectMapper);
        this.cacheTtl = properties.getCacheTtl();
        this.clock = clock;
    }

    @Override
    public Set<Permission> permissionsOf(ActorIdentity actor) {
        if (actor == null || actor.username() == null || actor.username().isBlank()) {
            throw unavailable("no usable actor");
        }
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)
                || attributes.getResponse() == null) {
            throw unavailable("no current HTTP request");
        }
        HttpServletRequest request = attributes.getRequest();
        HttpServletResponse response = attributes.getResponse();
        HttpSession session = request.getSession(false);
        Instant now = this.clock.instant();

        Set<Permission> cached = cached(session, actor, now);
        if (cached != null) {
            return cached;
        }
        Set<Permission> permissions;
        try {
            permissions = permissions(this.client.fetchAccount(actor.username(), request, response));
        } catch (ProdisAccountException e) {
            LOG.warn("Permission source (Prodis) unavailable ({})", e.getMessage());
            if (e.kind() == ProdisAccountException.Kind.TOKEN_LOST) {
                throw new PermissionSourceAuthenticationLostException();
            }
            throw new PermissionSourceUnavailableException(message(e.getMessage()));
        }
        store(session, actor, now, permissions);
        return permissions;
    }

    /**
     * Strikt: {@code permissions} moet een array van strings zijn. Enkel de exacte, volledige codes tellen.
     *
     * @throws ProdisAccountException {@code OTHER} bij een ontbrekend veld of een verkeerd type
     */
    static Set<Permission> permissions(JsonNode root) {
        JsonNode node = root.get("permissions");
        if (node == null || node.isNull()) {
            throw ProdisAccountException.other("unexpected JSON: permissions is missing");
        }
        if (!node.isArray()) {
            throw ProdisAccountException.other("unexpected JSON: permissions is not an array");
        }
        EnumSet<Permission> granted = EnumSet.noneOf(Permission.class);
        for (JsonNode element : node) {
            if (!element.isTextual()) {
                throw ProdisAccountException.other("unexpected JSON: permissions contains a non-string");
            }
            String value = element.textValue().trim();
            for (Permission permission : Permission.values()) {
                if (permission.code().equals(value)) {
                    granted.add(permission);
                }
            }
        }
        return Collections.unmodifiableSet(granted);
    }

    private Set<Permission> cached(HttpSession session, ActorIdentity actor, Instant now) {
        if (session == null) {
            return null;
        }
        try {
            if (session.getAttribute(CACHE_ATTRIBUTE) instanceof CachedPermissions entry
                    && Objects.equals(entry.subject(), actor.subject())
                    && Objects.equals(entry.username(), actor.username())
                    && now.isBefore(entry.fetchedAt().plus(this.cacheTtl))
                    && !now.isBefore(entry.fetchedAt())) {
                return entry.permissions();
            }
        } catch (IllegalStateException sessionInvalidated) {
            // De sessie is intussen ongeldig gemaakt (bv. gelijktijdige logout): geen cache, gewoon opnieuw vragen.
        }
        return null;
    }

    private static void store(HttpSession session, ActorIdentity actor, Instant now, Set<Permission> permissions) {
        if (session == null) {
            return;
        }
        try {
            session.setAttribute(CACHE_ATTRIBUTE, new CachedPermissions(actor.subject(), actor.username(), now, permissions));
        } catch (IllegalStateException sessionInvalidated) {
            // De sessie is intussen ongeldig gemaakt: niets te cachen.
        }
    }

    private static PermissionSourceUnavailableException unavailable(String reason) {
        LOG.warn("Permission source (Prodis) unavailable ({})", reason);
        return new PermissionSourceUnavailableException(message(reason));
    }

    private static String message(String reason) {
        return "Permission source (Prodis) unavailable (" + reason + ")";
    }
}
