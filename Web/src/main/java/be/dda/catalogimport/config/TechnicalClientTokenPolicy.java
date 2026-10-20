package be.dda.catalogimport.config;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Beslist of een (al op handtekening, issuer en geldigheid gecontroleerd) access token van de technische client de
 * legacy-config-push mag doen (beslissingslog 2026-10-20 M2; {@code legacy-config-bundle-design.md} par. 3):
 * <ol>
 *   <li>{@code aud} bevat de geconfigureerde audience;</li>
 *   <li>{@code azp} is exact de geconfigureerde client-id;</li>
 *   <li>{@code resource_access.<audience>.roles} bevat de geconfigureerde client role.</li>
 * </ol>
 * Een token dat hier faalt is wel <i>geauthenticeerd</i> maar niet <i>bevoegd</i>: 403, niet 401.
 */
final class TechnicalClientTokenPolicy {

    /** Welke controle faalde (voor de auditregel; nooit een tokenwaarde). */
    enum Verdict { ALLOWED, AUDIENCE, AUTHORIZED_PARTY, ROLE }

    private final String audience;
    private final String clientId;
    private final String requiredRole;

    TechnicalClientTokenPolicy(String audience, String clientId, String requiredRole) {
        this.audience = audience;
        this.clientId = clientId;
        this.requiredRole = requiredRole;
    }

    Verdict evaluate(Jwt jwt) {
        List<String> audiences = jwt.getAudience();
        if (audiences == null || !audiences.contains(audience)) {
            return Verdict.AUDIENCE;
        }
        Object azp = jwt.getClaims().get("azp");
        if (!(azp instanceof String party) || !party.equals(clientId)) {
            return Verdict.AUTHORIZED_PARTY;
        }
        Object resourceAccess = jwt.getClaims().get("resource_access");
        if (!(resourceAccess instanceof Map<?, ?> clients)
                || !(clients.get(audience) instanceof Map<?, ?> client)
                || !(client.get("roles") instanceof Collection<?> roles)
                || !roles.contains(requiredRole)) {
            return Verdict.ROLE;
        }
        return Verdict.ALLOWED;
    }
}
