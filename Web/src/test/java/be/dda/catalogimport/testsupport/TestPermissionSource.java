package be.dda.catalogimport.testsupport;

import be.dda.catalogimport.service.ActorIdentity;
import be.dda.catalogimport.web.Permission;
import be.dda.catalogimport.web.PermissionSource;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * De rechtenbron in tests (Fase 5-PERM, ontwerp par. 7): de rechten staan in de <b>login zelf</b>, in de
 * claim {@value #CLAIM}, zodat een test met één {@code TestActors.as(...)} bepaalt wie wat mag — zonder
 * property-overrides en dus zonder een aparte Spring-context per rechtencombinatie.
 * <p>
 * <b>Bewust een gewone {@code @Component} met {@code @Primary}</b>, net als
 * {@link TestSecurityConfiguration}: de component-scan van {@code CatalogImportApplication} pikt hem op
 * de test-classpath op, zodat bestaande testklassen ongewijzigd blijven. Hij wint van
 * {@code ConfiguredPermissionSource}, die daardoor in tests niets bepaalt maar wel nog steeds bij het
 * opstarten gebouwd en gevalideerd wordt.
 * <p>
 * Levert de <b>ruwe</b> codes, precies als een echte bron; de hiërarchie past {@code CurrentActor} toe.
 * Onbekende waarden in de claim worden genegeerd — dat is wat de Prodis-adapter (5B-7) ook zal doen.
 * Geen claim = lege set = "deze gebruiker heeft niets" (nooit een storing).
 */
@Component
@Primary
public class TestPermissionSource implements PermissionSource {

    /** De claim waarin een testlogin haar rechten draagt. */
    public static final String CLAIM = "catalogimport_permissions";

    @Override
    public Set<Permission> permissionsOf(ActorIdentity actor) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof OidcUser user)) {
            return Set.of();
        }
        return parse(user.getClaims().get(CLAIM));
    }

    private static Set<Permission> parse(Object claim) {
        EnumSet<Permission> permissions = EnumSet.noneOf(Permission.class);
        if (claim instanceof Collection<?> values) {
            values.forEach(value -> Permission.parse(String.valueOf(value)).ifPresent(permissions::add));
        } else if (claim instanceof String single) {
            for (String value : single.split(",")) {
                Permission.parse(value).ifPresent(permissions::add);
            }
        }
        return permissions;
    }
}
