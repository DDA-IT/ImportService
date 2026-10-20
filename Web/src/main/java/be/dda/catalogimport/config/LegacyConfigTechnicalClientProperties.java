package be.dda.catalogimport.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * De technische client van de legacy-config-push (beslissingslog 2026-10-20 M2;
 * {@code docs/design/legacy-config-bundle-design.md} par. 3).
 *
 * <pre>
 * catalogimport:
 *   technical-clients:
 *     legacy-config:
 *       audience: catalog-import                       # moet in "aud" staan; ook de sleutel in resource_access
 *       client-id: prodis-datasync-catalogimport       # moet gelijk zijn aan "azp"
 *       required-role: legacy-config-push              # client role in resource_access.&lt;audience&gt;.roles
 * </pre>
 *
 * <b>Gedrag zoals de bestaande optionele functies</b> (sleutelring, ophalen, productwisselaar): {@code audience} en
 * {@code client-id} beide leeg = functie uit — de applicatie start, en {@code POST /legacy-config/receipts} geeft altijd
 * 401 (de JWT-keten blijft dat pad wel afschermen, zodat de BFF-sessie het nooit bedient). Een van beide gezet, een
 * lege {@code required-role}, witruimte in een waarde of een onbekende sleutel = de applicatie start niet.
 * <p>
 * De issuer is die van de bestaande realm ({@code spring.security.oauth2.client.provider.keycloak.issuer-uri}); er is
 * bewust geen tweede issuer-property.
 */
@Component
@ConfigurationProperties(prefix = "catalogimport.technical-clients.legacy-config", ignoreUnknownFields = false)
public class LegacyConfigTechnicalClientProperties {

    static final String PREFIX = "catalogimport.technical-clients.legacy-config";
    static final String DEFAULT_REQUIRED_ROLE = "legacy-config-push";

    private String audience;
    private String clientId;
    private String requiredRole = DEFAULT_REQUIRED_ROLE;

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = optional(PREFIX + ".audience", audience);
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = optional(PREFIX + ".client-id", clientId);
    }

    public String getRequiredRole() {
        return requiredRole;
    }

    public void setRequiredRole(String requiredRole) {
        String role = optional(PREFIX + ".required-role", requiredRole);
        if (role == null) {
            throw new IllegalArgumentException(PREFIX + ".required-role must not be empty");
        }
        this.requiredRole = role;
    }

    /** {@code true} als zowel audience als client-id ingesteld zijn. */
    boolean enabled() {
        return audience != null && clientId != null;
    }

    /**
     * Fail-fast bij het opstarten: half ingesteld is een tikfout die anders pas bij de eerste push als 401 opvalt.
     *
     * @throws IllegalStateException precies een van audience/client-id is gezet
     */
    void validate() {
        if ((audience == null) != (clientId == null)) {
            throw new IllegalStateException(PREFIX + ".audience and " + PREFIX + ".client-id must both be set "
                    + "(technical client enabled) or both be empty (disabled)");
        }
    }

    /** Blanco = niet ingesteld; anders getrimd en zonder witruimte of stuurtekens. */
    private static String optional(String property, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                throw new IllegalArgumentException(property + " must not contain whitespace or control characters");
            }
        }
        return trimmed;
    }
}
