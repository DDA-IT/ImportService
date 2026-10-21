package be.dda.catalogimport.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.dao.ProdisReferenceJdbc;
import be.dda.catalogimport.testsupport.ProdisReferenceTestDatabase;
import com.zaxxer.hikari.HikariConfig;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * De configuratie van de alleen-lezen Prodis-bron (legacy-config S5; {@code docs/design/legacy-config-bundle-design.md}
 * par. 6 en 10). Zonder volledige applicatiecontext ({@link ApplicationContextRunner}), behalve de readOnly-proef tegen de
 * lokale PostgreSQL.
 *
 * <h2>Business rule → wat hier bewezen wordt</h2>
 * <ul>
 *   <li>Niets gezet = functie uit; de context start.</li>
 *   <li>Half ingesteld of ongeldig = de context start niet, zonder een waarde in de boodschap.</li>
 *   <li>Het enige bean-type is {@code ProdisReferenceJdbc}: geen {@code DataSource}/{@code JdbcTemplate}-bean.</li>
 *   <li>Alleen lezen wordt door de pool afgedwongen (PostgreSQL: {@code readOnlyMode=always}, ook met autocommit).</li>
 *   <li>De pool wordt gesloten met de context.</li>
 * </ul>
 */
class ProdisReadOnlyDatabaseConfigurationTest {

    private static final String P = ProdisReferenceDatabaseProperties.PREFIX;
    private static final String SECRET = "canary-prodis-password";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ProdisReadOnlyDatabaseConfiguration.class);

    @Test
    void withoutConfigurationTheFunctionIsOffAndTheContextStarts() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ProdisReferenceJdbc.class).isConfigured()).isFalse();
            assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
            assertThat(context.getBeansOfType(JdbcTemplate.class)).isEmpty();
        });
        runner.withPropertyValues(P + ".url=", P + ".username= ", P + ".password=")
                .run(context -> assertThat(context.getBean(ProdisReferenceJdbc.class).isConfigured()).isFalse());
    }

    @Test
    void aHalfConfigurationDoesNotStartAndNamesOnlyTheMissingProperties() {
        String url = ProdisReferenceTestDatabase.url("prodis-config-" + UUID.randomUUID());
        assertFails(new String[] {P + ".url=" + url}, P + ".username " + P + ".password");
        assertFails(new String[] {P + ".url=" + url, P + ".username=sa"}, "missing: " + P + ".password");
        assertFails(new String[] {P + ".password=" + SECRET}, P + ".url " + P + ".username");
        assertFails(new String[] {P + ".username=sa", P + ".password=" + SECRET}, "missing: " + P + ".url");
    }

    @Test
    void anInvalidConfigurationDoesNotStart() {
        String url = ProdisReferenceTestDatabase.url("prodis-config-" + UUID.randomUUID());
        String user = P + ".username=sa";
        String password = P + ".password=" + SECRET;
        assertFails(new String[] {P + ".url=postgresql://host/Prodis", user, password}, "must be a JDBC URL");
        assertFails(new String[] {P + ".url=jdbc:nosuchdriver://host/Prodis", user, password},
                "No JDBC driver accepts");
        assertFails(new String[] {P + ".url=jdbc:postgresql://host/Prodis?password=x", user, password},
                "must not carry a password");
        assertFails(new String[] {P + ".url=" + url, user, password, P + ".maximum-pool-size=0"}, "maximum-pool-size");
        assertFails(new String[] {P + ".url=" + url, user, password, P + ".maximum-pool-size=11"}, "maximum-pool-size");
        assertFails(new String[] {P + ".url=" + url, user, password, P + ".query-timeout=PT0.5S"}, "query-timeout");
        assertFails(new String[] {P + ".url=" + url, user, password, P + ".connect-timeout=PT0.1S"}, "connect-timeout");
        // Ook zonder url: een ongeldige waarde is een tikfout die anders pas bij het aanzetten opvalt.
        assertFails(new String[] {P + ".maximum-pool-size=0"}, "maximum-pool-size");
        // Een onbekende sleutel (tikfout) laat de context ook niet starten.
        runner.withPropertyValues(P + ".pasword=" + SECRET).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aValidConfigurationGivesAReadOnlyLazyPoolThatClosesWithTheContext() {
        ProdisReferenceTestDatabase prodis = ProdisReferenceTestDatabase.named("prodis-config-" + UUID.randomUUID());
        ProdisReferenceJdbc[] seen = new ProdisReferenceJdbc[1];
        runner.withPropertyValues(P + ".url=" + prodis.url(), P + ".username=" + ProdisReferenceTestDatabase.USERNAME,
                P + ".password=" + ProdisReferenceTestDatabase.PASSWORD, P + ".query-timeout=PT3S").run(context -> {
            assertThat(context).hasNotFailed();
            ProdisReferenceJdbc jdbc = context.getBean(ProdisReferenceJdbc.class);
            assertThat(jdbc.isConfigured()).isTrue();
            assertThat(jdbc.queryTimeoutSeconds()).isEqualTo(3);
            assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
            assertThat(context.getBeansOfType(JdbcTemplate.class)).isEmpty();
            assertThat(jdbc.query("select count(*) from supplier", row -> row.getLong(1))).containsExactly(0L);
            assertThat(context.getBean(ProdisReferenceDatabaseProperties.class).toString())
                    .doesNotContain(ProdisReferenceTestDatabase.PASSWORD).doesNotContain("jdbc:");
            seen[0] = jdbc;
        });
        assertThatThrownBy(() -> seen[0].query("select 1", row -> row.getInt(1)))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void thePoolSettingsAreReadOnlyLazyAndSmall() {
        ProdisReferenceDatabaseProperties properties = properties("jdbc:postgresql://prodis.example.test:5432/Prodis");
        HikariConfig config = ProdisReadOnlyDatabaseConfiguration.hikariConfig(properties);

        assertThat(config.isReadOnly()).isTrue();
        assertThat(config.isAutoCommit()).isTrue();
        assertThat(config.getMaximumPoolSize()).isEqualTo(2);
        assertThat(config.getMinimumIdle()).isZero();
        assertThat(config.getConnectionTimeout()).isEqualTo(5_000L);
        assertThat(config.getInitializationFailTimeout()).isEqualTo(-1L);
        assertThat(config.getPoolName()).isEqualTo(ProdisReadOnlyDatabaseConfiguration.POOL_NAME);
        assertThat(config.getDataSourceProperties()).containsEntry("readOnly", "true")
                .containsEntry("readOnlyMode", "always").containsEntry("connectTimeout", "5")
                .containsEntry("logServerErrorDetail", "false").containsEntry("socketTimeout", "20");
        // Geen pgjdbc-eigenschappen voor een andere driver (H2 zou ze weigeren).
        assertThat(ProdisReadOnlyDatabaseConfiguration.hikariConfig(properties(
                ProdisReferenceTestDatabase.url("x"))).getDataSourceProperties()).isEmpty();
    }

    /**
     * De echte proef: tegen PostgreSQL (de lokale catalog_import-database, enkel om te lezen) weigert de pool elke
     * schrijfopdracht, ook met autocommit. De UPDATE treft bewust geen rij ({@code where false}), zodat er zelfs zonder
     * afdwinging niets zou veranderen.
     */
    @Test
    void writesAreRejectedOnPostgres() throws Exception {
        ProdisReferenceDatabaseProperties properties = properties("jdbc:postgresql://localhost:5432/catalog_import");
        properties.setUsername("catalog_import");
        properties.setPassword("catalog_import");
        properties.setMaximumPoolSize(1);
        try (ProdisReferenceJdbc jdbc = new ProdisReadOnlyDatabaseConfiguration().prodisReferenceJdbc(properties)) {
            assertThat(jdbc.query("show transaction_read_only", row -> row.getString(1))).containsExactly("on");
            assertThatThrownBy(() -> jdbc.query("update legacy_config_origin set interface_code = interface_code "
                    + "where false returning id", row -> row.getLong(1)))
                    .isInstanceOf(DataAccessResourceFailureException.class)
                    .hasMessageContaining("SQLState 25006")
                    .cause().isInstanceOf(SQLException.class);
        }
    }

    private void assertFails(String[] properties, String expectedMessagePart) {
        runner.withPropertyValues(properties).run(context -> {
            assertThat(context).hasFailed();
            Throwable root = context.getStartupFailure();
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertThat(root).isInstanceOf(IllegalStateException.class).hasMessageContaining(expectedMessagePart)
                    .hasMessageNotContaining(SECRET).hasMessageNotContaining("jdbc:")
                    .hasMessageNotContaining("host/Prodis");
        });
    }

    private static ProdisReferenceDatabaseProperties properties(String url) {
        ProdisReferenceDatabaseProperties properties = new ProdisReferenceDatabaseProperties();
        properties.setUrl(url);
        properties.setUsername("catalogimport_ro");
        properties.setPassword(SECRET);
        properties.setConnectTimeout(Duration.ofSeconds(5));
        properties.setQueryTimeout(Duration.ofSeconds(10));
        return properties;
    }
}
