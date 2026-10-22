package be.dda.catalogimport.web;

import static org.assertj.core.api.Assertions.assertThat;

import be.dda.catalogimport.config.PermissionSourceConfiguration;
import be.dda.catalogimport.service.ActorIdentity;
import be.dda.catalogimport.testsupport.TestPermissionSource;
import be.dda.catalogimport.testsupport.TestSecurityConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

/**
 * 5B-7b: bronkeuze en validatie van {@code catalogimport.permissions.*} ({@code docs/design/fase5-perm-5b7-design.md}
 * par. 3; beslissingslog 2026-10-22) in een minimale context (geen database, geen netwerk):
 * <ul>
 *   <li>{@code source} = {@code CONFIGURED|PRODIS}, precies één {@link PermissionSource}; onbekend of leeg = opstartfout.</li>
 *   <li>{@code PRODIS} zonder {@code prodis.account-url} = opstartfout; ongeldige URL of niet-positieve duur = opstartfout.</li>
 *   <li>{@code PRODIS} met niet-lege {@code grants} = WARN "grants genegeerd", zonder usernames.</li>
 * </ul>
 */
@ExtendWith(OutputCaptureExtension.class)
class PermissionPropertiesTest {

    private static final String ACCOUNT_URL = "catalogimport.permissions.prodis.account-url=http://localhost:8080/api/account";

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PermissionProperties.class)
    static class Binding {

        @Bean
        ClientRegistrationRepository clientRegistrationRepository() {
            return new InMemoryClientRegistrationRepository(TestSecurityConfiguration.keycloakRegistration());
        }

        @Bean
        OAuth2AuthorizedClientRepository authorizedClientRepository() {
            return new HttpSessionOAuth2AuthorizedClientRepository();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Binding.class, PermissionSourceConfiguration.class);

    // --- Normaal scenario ------------------------------------------------------------------------------

    @Test
    void withoutASourceTheCodeDefaultIsConfiguredAndTheProdisDefaultsApply() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PermissionSource.class);
            assertThat(context.getBean(PermissionSource.class)).isInstanceOf(ConfiguredPermissionSource.class);
            PermissionProperties properties = context.getBean(PermissionProperties.class);
            assertThat(properties.source()).isEqualTo(PermissionProperties.Source.CONFIGURED);
            assertThat(properties.getProdis().getAccountUrl()).isNull();
            assertThat(properties.getProdis().getConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.getProdis().getReadTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.getProdis().getCacheTtl()).isEqualTo(Duration.ofSeconds(30));
        });
    }

    @Test
    void configuredGivesExactlyTheYamlSource() {
        runner.withPropertyValues("catalogimport.permissions.source=CONFIGURED",
                        "catalogimport.permissions.grants[0].username=jan.peeters",
                        "catalogimport.permissions.grants[0].rights=read")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PermissionSource.class);
                    assertThat(context.getBean(PermissionSource.class).permissionsOf(
                            new ActorIdentity("jan.peeters", "s-1")))
                            .containsExactly(Permission.READ);
                });
    }

    @Test
    void prodisGivesExactlyTheProdisSourceWithTrimmedUrlAndCustomDurations() {
        runner.withPropertyValues("catalogimport.permissions.source=PRODIS",
                        "catalogimport.permissions.prodis.account-url= https://prodis.example.test/api/account/ ",
                        "catalogimport.permissions.prodis.connect-timeout=500ms",
                        "catalogimport.permissions.prodis.read-timeout=PT1S",
                        "catalogimport.permissions.prodis.cache-ttl=10s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PermissionSource.class);
                    assertThat(context.getBean(PermissionSource.class)).isInstanceOf(ProdisPermissionSource.class);
                    PermissionProperties.Prodis prodis = context.getBean(PermissionProperties.class).getProdis();
                    assertThat(prodis.getAccountUrl()).isEqualTo("https://prodis.example.test/api/account");
                    assertThat(prodis.getConnectTimeout()).isEqualTo(Duration.ofMillis(500));
                    assertThat(prodis.getReadTimeout()).isEqualTo(Duration.ofSeconds(1));
                    assertThat(prodis.getCacheTtl()).isEqualTo(Duration.ofSeconds(10));
                });
    }

    @Test
    void theSourceValueIsBoundLeniently() {
        // Spring Boot bindt enums soepel ("prodis" = PRODIS); enkel een onbekende waarde faalt.
        runner.withPropertyValues("catalogimport.permissions.source=prodis", ACCOUNT_URL)
                .run(context -> assertThat(context.getBean(PermissionSource.class)).isInstanceOf(ProdisPermissionSource.class));
    }

    @Test
    void configuredIgnoresTheProdisSettingsButStillValidatesTheirFormat() {
        runner.withPropertyValues("catalogimport.permissions.source=CONFIGURED", ACCOUNT_URL)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PermissionSource.class)).isInstanceOf(ConfiguredPermissionSource.class);
                });
        runner.withPropertyValues("catalogimport.permissions.source=CONFIGURED",
                        "catalogimport.permissions.prodis.cache-ttl=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    // --- Ongeldige of ontbrekende configuratie: opstartfout --------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"YAML", "BOTH", "CONFIGURED,PRODIS", "none"})
    void anUnknownSourceFailsTheBoot(String value) {
        runner.withPropertyValues("catalogimport.permissions.source=" + value, ACCOUNT_URL)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining(value);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void anEmptySourceFailsTheBootInsteadOfFallingBackToTheDefault(String value) {
        runner.withPropertyValues("catalogimport.permissions.source=" + value)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void prodisWithoutAnAccountUrlFailsTheBoot() {
        runner.withPropertyValues("catalogimport.permissions.source=PRODIS")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("catalogimport.permissions.prodis.account-url is required");
                });
    }

    @Test
    void prodisWithABlankAccountUrlFailsTheBoot() {
        // Zo komt ${CATALOG_PRODIS_ACCOUNT_URL:} binnen als de variabele niet gezet is.
        runner.withPropertyValues("catalogimport.permissions.source=PRODIS",
                        "catalogimport.permissions.prodis.account-url=  ")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("catalogimport.permissions.prodis.account-url is required");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost:8080/api/account", "ftp://prodis.example.test/api/account", "/api/account",
            "javascript:alert(1)", "http://"})
    void anInvalidAccountUrlFailsTheBoot(String value) {
        runner.withPropertyValues("catalogimport.permissions.source=PRODIS",
                        "catalogimport.permissions.prodis.account-url=" + value)
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"connect-timeout", "read-timeout", "cache-ttl"})
    void aNonPositiveDurationFailsTheBoot(String key) {
        runner.withPropertyValues("catalogimport.permissions.source=PRODIS", ACCOUNT_URL,
                        "catalogimport.permissions.prodis." + key + "=0s")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("catalogimport.permissions.source=PRODIS", ACCOUNT_URL,
                        "catalogimport.permissions.prodis." + key + "=-1s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void configuredStillRefusesSystemWithManage() {
        runner.withPropertyValues("catalogimport.permissions.source=CONFIGURED",
                        "catalogimport.permissions.grants[0].username=system",
                        "catalogimport.permissions.grants[0].rights=manage")
                .run(context -> assertThat(context).hasFailed());
    }

    // --- PRODIS met grants: WARN, nooit een unie --------------------------------------------------------

    @Test
    void prodisWithGrantsWarnsThatTheGrantsAreIgnoredWithoutNamingAnyone(CapturedOutput output) {
        runner.withPropertyValues("catalogimport.permissions.source=PRODIS", ACCOUNT_URL,
                        "catalogimport.permissions.grants[0].username=jan.peeters",
                        "catalogimport.permissions.grants[0].rights=approve",
                        "catalogimport.permissions.grants[1].username=an.janssens",
                        "catalogimport.permissions.grants[1].rights=read")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PermissionSource.class);
                    assertThat(context.getBean(PermissionSource.class)).isInstanceOf(ProdisPermissionSource.class);
                });
        assertThat(output).contains("catalogimport.permissions.source=PRODIS: the 2 entries under "
                + "catalogimport.permissions.grants are ignored");
        assertThat(output).doesNotContain("jan.peeters").doesNotContain("an.janssens");
    }

    // --- Tests: TestPermissionSource wint, tenzij uitgeschakeld (voor 5B-7c) ------------------------------

    @Test
    void theTestSourceWinsByDefaultAndTheRealSourceIsStillBuilt() {
        runner.withUserConfiguration(TestPermissionSource.class)
                .withPropertyValues("catalogimport.permissions.source=PRODIS", ACCOUNT_URL)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(PermissionSource.class)).hasSize(2);
                    assertThat(context.getBean(PermissionSource.class)).isInstanceOf(TestPermissionSource.class);
                });
    }

    @Test
    void theTestSourceCanBeSwitchedOffSoTheRealSourceDecides() {
        runner.withUserConfiguration(TestPermissionSource.class)
                .withPropertyValues("catalogimport.permissions.source=PRODIS", ACCOUNT_URL,
                        TestPermissionSource.ENABLED_PROPERTY + "=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PermissionSource.class);
                    assertThat(context.getBean(PermissionSource.class)).isInstanceOf(ProdisPermissionSource.class);
                });
    }

    @Test
    void prodisWithoutGrantsDoesNotWarn(CapturedOutput output) {
        runner.withPropertyValues("catalogimport.permissions.source=PRODIS", ACCOUNT_URL)
                .run(context -> assertThat(context).hasNotFailed());
        assertThat(output).doesNotContain("are ignored");
    }
}
