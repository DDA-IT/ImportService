package be.dda.catalogimport.testsupport;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Access tokens van de technische client in tests, zonder Keycloak en zonder netwerk (legacy-config S1, M2).
 * <p>
 * Zoals {@link TestSecurityConfiguration} een gewone {@code @Configuration} die de component-scan op de
 * test-classpath oppikt: de {@link JwtDecoder}-bean vervangt in elke test de luie issuer-decoder van
 * {@code LegacyConfigPushSecurityConfiguration}. Hij controleert handtekening (een per JVM lokaal gegenereerde
 * RSA-sleutel), {@code exp}/{@code nbf} en de issuer ({@link TestSecurityConfiguration#ISSUER}) — exact zoals de
 * productiedecoder dat met de Keycloak-sleutels doet. {@link #signedWithAnotherKey} levert een token dat op de
 * handtekening faalt.
 */
@Configuration(proxyBeanMethods = false)
public class TestTechnicalClientJwt {

    public static final String ISSUER = TestSecurityConfiguration.ISSUER;
    public static final String AUDIENCE = "web_app";
    public static final String CLIENT_ID = "prodis-datasync-catalogimport";
    public static final String ROLE = "legacy-config-push";
    public static final String SUBJECT = "service-account-sub-datasync";

    private static final RSAKey KEY = generate("test-technical-client");
    private static final RSAKey OTHER_KEY = generate("not-trusted");

    @Bean
    JwtDecoder technicalClientTestJwtDecoder() throws JOSEException {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
        return decoder;
    }

    /** Een geldig token van de datasync-client met de juiste audience, {@code azp} en client role. */
    public static String validToken() {
        return token(claims -> { });
    }

    /** Een token met de standaardclaims, aangepast door {@code customizer}. */
    public static String token(Consumer<JWTClaimsSet.Builder> customizer) {
        return sign(KEY, customizer);
    }

    /** Correcte claims, maar ondertekend met een sleutel die de decoder niet vertrouwt. */
    public static String signedWithAnotherKey() {
        return sign(OTHER_KEY, claims -> { });
    }

    /** De {@code resource_access}-claim met {@code roles} voor één client. */
    public static Map<String, Object> resourceAccess(String client, String... roles) {
        return Map.of(client, Map.of("roles", List.of(roles)));
    }

    private static String sign(RSAKey key, Consumer<JWTClaimsSet.Builder> customizer) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(SUBJECT)
                .audience(List.of(AUDIENCE, "account"))
                .claim("azp", CLIENT_ID)
                .claim("typ", "Bearer")
                .claim("resource_access", resourceAccess(AUDIENCE, ROLE))
                .issueTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plusSeconds(300)));
        customizer.accept(claims);
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                    claims.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign the test token", e);
        }
    }

    private static RSAKey generate(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not generate a test RSA key", e);
        }
    }
}
