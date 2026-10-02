package be.dda.catalogimport.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Fail-fast bij het opstarten (docs/decisions.md 2026-10-02, stap 7, S7-b): {@code application.yml} heeft geen
 * default meer voor het databasewachtwoord ({@code ${CATALOG_DB_PASSWORD}}). Is die variabele niet gezet, dan blijft
 * {@code spring.datasource.password} een onopgelost placeholder ({@code ${...}}) en zou de verbinding falen met een
 * misleidende "password authentication failed". Dit is een {@link EnvironmentPostProcessor} (geregistreerd in
 * {@code META-INF/spring.factories}) en draait dus vóór de context, vóór de DataSource en vóór Liquibase, nadat
 * alle configuratiebestanden en profielen geladen zijn. Zelfde patroon als de OIDC-secretcontrole in
 * {@link SecurityConfiguration#requireUsableRegistration}. Een expliciet leeg wachtwoord blijft toegelaten
 * (bv. peer/trust-authenticatie); een niet-gezette property ook (andere datasource-configuratie). De boodschap
 * noemt nooit de waarde van het wachtwoord.
 */
public class DatasourcePasswordCheck implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY = "spring.datasource.password";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String password;
        try {
            password = environment.getProperty(PROPERTY);
        } catch (IllegalArgumentException unresolvable) {
            // Strikte placeholderresolutie: een onopgeloste ${...} geeft hier een exception i.p.v. de letterlijke tekst.
            throw unresolved();
        }
        requireResolved(password);
    }

    /** Laatste: na het laden van alle configuratiebestanden en profielen. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    static void requireResolved(String password) {
        if (password != null && password.startsWith("${")) {
            throw unresolved();
        }
    }

    private static IllegalStateException unresolved() {
        return new IllegalStateException("Database password (" + PROPERTY + ") is unresolved; "
                + "set the environment variable CATALOG_DB_PASSWORD (an explicitly empty value is allowed)");
    }
}
