package be.dda.catalogimport.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * BE-SW: de binding van {@code catalogimport.app-switcher.*} in een minimale context (geen database), zoals
 * {@code ProdevProperties} in Prodis: leeg = niet ingesteld; een ongeldige URL, een niet-positieve duur of een
 * onbekende sleutel laat de context niet starten.
 */
class AppSwitcherPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppSwitcherProperties.class)
    static class Binding {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Binding.class);

    @Test
    void withoutPropertiesNothingIsConfiguredAndTheDefaultsApply() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            AppSwitcherProperties properties = context.getBean(AppSwitcherProperties.class);
            assertThat(properties.getProdisUrl()).isNull();
            assertThat(properties.getProdisAccountUrl()).isNull();
            assertThat(properties.relayConfigured()).isFalse();
            assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.getCacheTtl()).isEqualTo(Duration.ofMinutes(5));
            assertThat(properties.getFailureCacheTtl()).isEqualTo(Duration.ofMinutes(1));
        });
    }

    @Test
    void blankValuesMeanNotConfigured() {
        // Zo komt ${CATALOG_PRODIS_URL:} uit application.yml binnen als de variabele niet gezet is.
        runner.withPropertyValues("catalogimport.app-switcher.prodis-url=",
                        "catalogimport.app-switcher.prodis-account-url=   ")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AppSwitcherProperties properties = context.getBean(AppSwitcherProperties.class);
                    assertThat(properties.getProdisUrl()).isNull();
                    assertThat(properties.relayConfigured()).isFalse();
                });
    }

    @Test
    void validValuesAreTrimmedAndLoseTheirTrailingSlash() {
        runner.withPropertyValues("catalogimport.app-switcher.prodis-url= https://prodis.example.test/ ",
                        "catalogimport.app-switcher.prodis-account-url=http://localhost:8080/api/account",
                        "catalogimport.app-switcher.connect-timeout=500ms",
                        "catalogimport.app-switcher.read-timeout=PT1S",
                        "catalogimport.app-switcher.cache-ttl=PT10M",
                        "catalogimport.app-switcher.failure-cache-ttl=30s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AppSwitcherProperties properties = context.getBean(AppSwitcherProperties.class);
                    assertThat(properties.getProdisUrl()).isEqualTo("https://prodis.example.test");
                    assertThat(properties.getProdisAccountUrl()).isEqualTo("http://localhost:8080/api/account");
                    assertThat(properties.relayConfigured()).isTrue();
                    assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofMillis(500));
                    assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofSeconds(1));
                    assertThat(properties.getCacheTtl()).isEqualTo(Duration.ofMinutes(10));
                    assertThat(properties.getFailureCacheTtl()).isEqualTo(Duration.ofSeconds(30));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost:9060", "ftp://prodis.example.test", "javascript:alert(1)", "/dashboard",
            "http://", "https://exa mple.test", "prodis"})
    void anInvalidProdisUrlFailsTheBoot(String value) {
        runner.withPropertyValues("catalogimport.app-switcher.prodis-url=" + value)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("catalogimport.app-switcher.prodis-url"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost:8080/api/account", "file:///etc/passwd", "api/account"})
    void anInvalidAccountUrlFailsTheBoot(String value) {
        runner.withPropertyValues("catalogimport.app-switcher.prodis-account-url=" + value)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("catalogimport.app-switcher.prodis-account-url"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"connect-timeout=0s", "read-timeout=-1s", "cache-ttl=PT0S", "failure-cache-ttl=-PT1M"})
    void aNonPositiveDurationFailsTheBoot(String setting) {
        runner.withPropertyValues("catalogimport.app-switcher." + setting)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void anUnknownKeyFailsTheBoot() {
        // Een tikfout (prodis-ur) zou anders stil een wisselaar zonder Prodis-link opleveren.
        runner.withPropertyValues("catalogimport.app-switcher.prodis-ur=http://localhost:9060")
                .run(context -> assertThat(context).hasFailed());
    }
}
