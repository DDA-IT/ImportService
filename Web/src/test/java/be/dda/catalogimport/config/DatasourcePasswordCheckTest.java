package be.dda.catalogimport.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockPropertySource;

/** Opstartcontrole op een onopgelost databasewachtwoord (S7-b). Geen database nodig. */
class DatasourcePasswordCheckTest {

    private static void run(StandardEnvironment env) {
        new DatasourcePasswordCheck().postProcessEnvironment(env, new SpringApplication());
    }

    private static StandardEnvironment envWith(String password) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MockPropertySource().withProperty("spring.datasource.password", password));
        return env;
    }

    @Test
    void unresolvedPlaceholderFailsWithClearMessageWithoutRevealingValue() {
        assertThatThrownBy(() -> run(envWith("${CATALOG_DB_PASSWORD}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CATALOG_DB_PASSWORD")
                .hasMessageContaining("spring.datasource.password");
    }

    @Test
    void placeholderWithoutVariableInEnvironmentFailsEvenWhenStrictResolutionThrows() {
        // Echte vorm: de yml-waarde ${CATALOG_DB_PASSWORD} verwijst naar een variabele die nergens bestaat.
        StandardEnvironment env = envWith("${CATALOG_DB_PASSWORD_NOT_SET_ANYWHERE}");
        assertThatThrownBy(() -> run(env)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resolvedPasswordIsAccepted() {
        assertThatCode(() -> run(envWith("geheim"))).doesNotThrowAnyException();
    }

    @Test
    void explicitlyEmptyPasswordIsAccepted() {
        assertThatCode(() -> run(envWith(""))).doesNotThrowAnyException();
    }

    @Test
    void placeholderResolvedFromEnvironmentVariableIsAccepted() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MockPropertySource()
                .withProperty("spring.datasource.password", "${CATALOG_DB_PASSWORD}")
                .withProperty("CATALOG_DB_PASSWORD", "uit-omgeving"));
        assertThatCode(() -> run(env)).doesNotThrowAnyException();
    }

    @Test
    void absentPropertyIsNotThisChecksBusiness() {
        assertThatCode(() -> run(new StandardEnvironment())).doesNotThrowAnyException();
        assertThat(new DatasourcePasswordCheck().getOrder()).isEqualTo(org.springframework.core.Ordered.LOWEST_PRECEDENCE);
    }
}
