package be.dda.catalogimport.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Legacy-config S1 (beslissingslog 2026-10-20 M2): de configuratie van de technische client en de tokenregels,
 * databasevrij en zonder Spring-context.
 * <ul>
 *   <li><b>Regel:</b> niet ingesteld = functie uit (start); half ingesteld of ongeldig = start niet.</li>
 *   <li><b>Regel:</b> {@code aud}, {@code azp} en de client role moeten alle drie kloppen.</li>
 * </ul>
 */
class LegacyConfigTechnicalClientPropertiesTest {

    @Test
    void emptyMeansDisabledAndBothMeansEnabled() {
        LegacyConfigTechnicalClientProperties properties = new LegacyConfigTechnicalClientProperties();
        properties.setAudience("  ");
        properties.setClientId("");
        properties.validate();
        assertThat(properties.enabled()).isFalse();
        assertThat(properties.getRequiredRole()).isEqualTo("legacy-config-push");

        properties.setAudience(" catalog-import ");
        properties.setClientId("prodis-datasync-catalogimport");
        properties.validate();
        assertThat(properties.enabled()).isTrue();
        assertThat(properties.getAudience()).isEqualTo("catalog-import");
    }

    @Test
    void halfConfiguredOrInvalidValuesStopTheStartup() {
        LegacyConfigTechnicalClientProperties onlyAudience = new LegacyConfigTechnicalClientProperties();
        onlyAudience.setAudience("catalog-import");
        assertThatThrownBy(onlyAudience::validate).isInstanceOf(IllegalStateException.class);

        LegacyConfigTechnicalClientProperties onlyClient = new LegacyConfigTechnicalClientProperties();
        onlyClient.setClientId("prodis-datasync-catalogimport");
        assertThatThrownBy(onlyClient::validate).isInstanceOf(IllegalStateException.class);

        LegacyConfigTechnicalClientProperties properties = new LegacyConfigTechnicalClientProperties();
        assertThatThrownBy(() -> properties.setRequiredRole(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setAudience("catalog import"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theIssuerMustBeAnAbsoluteHttpUrl() {
        assertThat(LegacyConfigPushSecurityConfiguration.isAbsoluteHttpUrl("http://localhost:9080/realms/prodis"))
                .isTrue();
        assertThat(LegacyConfigPushSecurityConfiguration.isAbsoluteHttpUrl("")).isFalse();
        assertThat(LegacyConfigPushSecurityConfiguration.isAbsoluteHttpUrl("${CATALOG_OIDC_ISSUER_URI}")).isFalse();
        assertThat(LegacyConfigPushSecurityConfiguration.isAbsoluteHttpUrl("ftp://host/realm")).isFalse();
    }

    @Test
    void thePolicyNeedsAudienceAuthorizedPartyAndRole() {
        TechnicalClientTokenPolicy policy = new TechnicalClientTokenPolicy("catalog-import",
                "prodis-datasync-catalogimport", "legacy-config-push");
        Map<String, Object> roles = Map.of("catalog-import", Map.of("roles", List.of("legacy-config-push")));

        assertThat(policy.evaluate(jwt(List.of("catalog-import", "account"), "prodis-datasync-catalogimport", roles)))
                .isEqualTo(TechnicalClientTokenPolicy.Verdict.ALLOWED);
        assertThat(policy.evaluate(jwt(List.of("account"), "prodis-datasync-catalogimport", roles)))
                .isEqualTo(TechnicalClientTokenPolicy.Verdict.AUDIENCE);
        assertThat(policy.evaluate(jwt(List.of("catalog-import"), "other-client", roles)))
                .isEqualTo(TechnicalClientTokenPolicy.Verdict.AUTHORIZED_PARTY);
        assertThat(policy.evaluate(jwt(List.of("catalog-import"), "prodis-datasync-catalogimport",
                Map.of("catalog-import", Map.of("roles", List.of("legacy-config-read"))))))
                .isEqualTo(TechnicalClientTokenPolicy.Verdict.ROLE);
        assertThat(policy.evaluate(jwt(List.of("catalog-import"), "prodis-datasync-catalogimport",
                Map.of("account", Map.of("roles", List.of("legacy-config-push"))))))
                .isEqualTo(TechnicalClientTokenPolicy.Verdict.ROLE);
        assertThat(policy.evaluate(jwt(List.of("catalog-import"), "prodis-datasync-catalogimport",
                Map.of("catalog-import", Map.of("roles", "legacy-config-push")))))
                .isEqualTo(TechnicalClientTokenPolicy.Verdict.ROLE);
    }

    private static Jwt jwt(List<String> audience, String azp, Map<String, Object> resourceAccess) {
        return Jwt.withTokenValue("t").header("alg", "RS256").subject("sub").audience(audience).claim("azp", azp)
                .claim("resource_access", resourceAccess).build();
    }
}
