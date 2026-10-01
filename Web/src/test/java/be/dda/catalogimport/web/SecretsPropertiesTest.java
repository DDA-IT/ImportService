package be.dda.catalogimport.web;

import static be.dda.catalogimport.web.SecretCipherTest.KEY_A;
import static be.dda.catalogimport.web.SecretCipherTest.KEY_B;
import static org.assertj.core.api.Assertions.assertThat;

import be.dda.catalogimport.service.SecretsService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * K-1: de binding van {@code catalogimport.secrets.*} in een minimale context (geen database): geen config =
 * de context start met de functie uit; geldige config = aan; ongeldige config = de context start niet.
 */
class SecretsPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SecretsService.class);

    @Test
    void withoutPropertiesTheContextStartsWithTheFunctionOff() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SecretsService.class).configured()).isFalse();
        });
    }

    @Test
    void withValidPropertiesTheFunctionIsOn() {
        runner.withPropertyValues("catalogimport.secrets.keys=a:" + KEY_A + ",b:" + KEY_B,
                        "catalogimport.secrets.active-key-id=b")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    SecretsService service = context.getBean(SecretsService.class);
                    UUID ref = UUID.randomUUID();
                    assertThat(service.configured()).isTrue();
                    assertThat(service.activeKeyId()).isEqualTo("b");
                    assertThat(service.decrypt(service.encrypt("x", ref, "API_KEY"), ref, "API_KEY")).isEqualTo("x");
                });
    }

    @Test
    void withInvalidPropertiesTheContextFailsToStart() {
        runner.withPropertyValues("catalogimport.secrets.keys=a:" + KEY_A,
                        "catalogimport.secrets.active-key-id=missing")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("catalogimport.secrets.keys=a:" + KEY_A)
                .run(context -> assertThat(context).hasFailed());
    }
}
