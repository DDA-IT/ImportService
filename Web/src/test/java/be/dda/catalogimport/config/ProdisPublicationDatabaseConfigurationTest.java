package be.dda.catalogimport.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.dao.ProdisPublicationJdbc;
import be.dda.catalogimport.dao.ProdisTransactionNotAppliedException;
import be.dda.catalogimport.dao.ProdisReferenceJdbc;
import be.dda.catalogimport.service.ConflictException;
import be.dda.catalogimport.service.ProdisPublicationWriteNotConfiguredException;
import be.dda.catalogimport.testsupport.ProdisReferenceTestDatabase;
import com.zaxxer.hikari.HikariConfig;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * De configuratie van de schrijfverbinding naar Prodis (publicatie P5a; {@code docs/design/publicatie-prodis-db-direct-design.md}
 * par. 2.1-2.2). Zonder volledige applicatiecontext ({@link ApplicationContextRunner}) en zonder databaseverbinding: de
 * pool verbindt pas bij het eerste gebruik (de echte PostgreSQL-proeven staan in {@code ProdisPublicationGrantTest} en
 * {@code ProdisPublicationJdbcTest}).
 *
 * <h2>Business rule → wat hier bewezen wordt</h2>
 * <ul>
 *   <li>Niets gezet = functie uit; de context start; gebruik geeft 409 {@code PRODIS_WRITE_NOT_CONFIGURED}.</li>
 *   <li>Half ingesteld of ongeldig = de context start niet, zonder een waarde in de boodschap.</li>
 *   <li>Het enige bean-type is {@code ProdisPublicationJdbc}: geen {@code DataSource}/{@code JdbcTemplate}-bean, en de
 *       autoconfiguratie van de hoofddatabase blijft (één DataSource, JdbcTemplate, transactiemanager).</li>
 *   <li>Hikari zoals design par. 2.1: readOnly=false, autoCommit=false, minimumIdle=0, initializationFailTimeout=-1,
 *       ApplicationName=catalogimport-publish, logServerErrorDetail=false.</li>
 * </ul>
 */
class ProdisPublicationDatabaseConfigurationTest {

    private static final String P = ProdisPublicationDatabaseProperties.PREFIX;
    private static final String SECRET = "canary-prodis-write-password";
    /** Een PostgreSQL-URL die nooit verbonden wordt (lazy pool); de driver herkent ze wel. */
    private static final String URL = "jdbc:postgresql://prodis-write.example.test:5432/Prodis";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ProdisPublicationDatabaseConfiguration.class);

    @Test
    void withoutConfigurationTheFunctionIsOffAndUseIsAnExplicitConflict() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            ProdisPublicationJdbc jdbc = context.getBean(ProdisPublicationJdbc.class);
            assertThat(jdbc.isConfigured()).isFalse();
            assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
            assertThat(context.getBeansOfType(JdbcTemplate.class)).isEmpty();
            assertThatThrownBy(() -> jdbc.inTransaction(transaction -> 1))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("not configured");
        });
        runner.withPropertyValues(P + ".url=", P + ".username= ", P + ".password=")
                .run(context -> assertThat(context.getBean(ProdisPublicationJdbc.class).isConfigured()).isFalse());

        ProdisPublicationWriteNotConfiguredException conflict = new ProdisPublicationWriteNotConfiguredException();
        assertThat(conflict).isInstanceOf(ConflictException.class);
        assertThat(conflict.getCode()).isEqualTo("PRODIS_WRITE_NOT_CONFIGURED");
        assertThat(conflict.getMessage()).contains(P);
    }

    @Test
    void aHalfConfigurationDoesNotStartAndNamesOnlyTheMissingProperties() {
        assertFails(new String[] {P + ".url=" + URL}, "missing: " + P + ".username " + P + ".password");
        assertFails(new String[] {P + ".url=" + URL, P + ".username=catalogimport_pub"}, "missing: " + P + ".password");
        assertFails(new String[] {P + ".password=" + SECRET}, "missing: " + P + ".url " + P + ".username");
        assertFails(new String[] {P + ".username=catalogimport_pub", P + ".password=" + SECRET},
                "missing: " + P + ".url");
        assertFails(new String[] {P + ".url=" + URL}, "Prodis publication database enabled");
    }

    @Test
    void anInvalidConfigurationDoesNotStart() {
        String user = P + ".username=catalogimport_pub";
        String password = P + ".password=" + SECRET;
        assertFails(new String[] {P + ".url=postgresql://host/Prodis", user, password}, "must be a JDBC URL");
        assertFails(new String[] {P + ".url=jdbc:nosuchdriver://host/Prodis", user, password}, "No JDBC driver accepts");
        assertFails(new String[] {P + ".url=jdbc:postgresql://host/Prodis?password=x", user, password},
                "must not carry a password");
        assertFails(new String[] {P + ".url=" + URL, user, password, P + ".maximum-pool-size=0"}, "maximum-pool-size");
        assertFails(new String[] {P + ".url=" + URL, user, password, P + ".maximum-pool-size=11"}, "maximum-pool-size");
        assertFails(new String[] {P + ".url=" + URL, user, password, P + ".query-timeout=PT0.5S"}, "query-timeout");
        assertFails(new String[] {P + ".url=" + URL, user, password, P + ".connect-timeout=PT0.1S"}, "connect-timeout");
        // Ook zonder url: een ongeldige waarde is een tikfout die anders pas bij het aanzetten opvalt.
        assertFails(new String[] {P + ".query-timeout=PT0S"}, "query-timeout");
        // Een onbekende sleutel (tikfout) laat de context ook niet starten.
        runner.withPropertyValues(P + ".pasword=" + SECRET).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aValidConfigurationGivesALazyPoolWithoutADataSourceBeanThatClosesWithTheContext() {
        ProdisPublicationJdbc[] seen = new ProdisPublicationJdbc[1];
        runner.withPropertyValues(P + ".url=" + URL, P + ".username=catalogimport_pub", P + ".password=" + SECRET,
                P + ".query-timeout=PT7S").run(context -> {
            // Lazy: de host bestaat niet, toch start de context.
            assertThat(context).hasNotFailed();
            ProdisPublicationJdbc jdbc = context.getBean(ProdisPublicationJdbc.class);
            assertThat(jdbc.isConfigured()).isTrue();
            assertThat(jdbc.queryTimeoutSeconds()).isEqualTo(7);
            assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
            assertThat(context.getBeansOfType(JdbcTemplate.class)).isEmpty();
            assertThat(context.getBeansOfType(NamedParameterJdbcTemplate.class)).isEmpty();
            assertThat(context.getBean(ProdisPublicationDatabaseProperties.class).toString())
                    .isEqualTo("ProdisPublicationDatabaseProperties[enabled=true]").doesNotContain(SECRET);
            seen[0] = jdbc;
        });
        // De pool is met de context gesloten (geen verbindingspoging naar de onbestaande host).
        assertThatThrownBy(() -> seen[0].inTransaction(transaction -> 1))
                .isInstanceOf(ProdisTransactionNotAppliedException.class)
                .cause().hasMessageContaining("has been closed");
    }

    /**
     * De hoofddatabase blijft ongemoeid: met de autoconfiguratie van DataSource, JdbcTemplate en transactiemanager erbij
     * is er precies één DataSource (de hoofddatabase), één JdbcTemplate en één transactiemanager - de schrijfbron (en de
     * leesbron) doen de {@code @ConditionalOnMissingBean}/{@code @ConditionalOnSingleCandidate} van Boot niet terugtreden.
     */
    @Test
    void theMainDataSourceAutoConfigurationIsUnaffected() {
        String mainUrl = ProdisReferenceTestDatabase.url("main-" + UUID.randomUUID());
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        JdbcTemplateAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class))
                .withUserConfiguration(ProdisPublicationDatabaseConfiguration.class,
                        ProdisReadOnlyDatabaseConfiguration.class)
                .withPropertyValues("spring.datasource.url=" + mainUrl, "spring.datasource.username=sa",
                        P + ".url=" + URL, P + ".username=catalogimport_pub", P + ".password=" + SECRET)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
                    try (var connection = context.getBean(DataSource.class).getConnection()) {
                        assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:main-");
                    }
                    assertThat(context.getBeansOfType(JdbcTemplate.class)).hasSize(1);
                    assertThat(context.getBeansOfType(NamedParameterJdbcTemplate.class)).hasSize(1);
                    assertThat(context.getBeansOfType(PlatformTransactionManager.class)).hasSize(1);
                    assertThat(context.getBean(ProdisPublicationJdbc.class).isConfigured()).isTrue();
                    assertThat(context.getBean(ProdisReferenceJdbc.class).isConfigured()).isFalse();
                });
    }

    @Test
    void thePoolSettingsFollowTheDesign() {
        HikariConfig config = ProdisPublicationDatabaseConfiguration.hikariConfig(properties(URL));

        assertThat(config.isReadOnly()).isFalse();
        assertThat(config.isAutoCommit()).isFalse();
        assertThat(config.getTransactionIsolation()).isEqualTo("TRANSACTION_READ_COMMITTED");
        assertThat(config.getMaximumPoolSize()).isEqualTo(2);
        assertThat(config.getMinimumIdle()).isZero();
        assertThat(config.getConnectionTimeout()).isEqualTo(5_000L);
        assertThat(config.getInitializationFailTimeout()).isEqualTo(-1L);
        assertThat(config.getPoolName()).isEqualTo("catalogimport-prodis-publish");
        assertThat(config.getDataSourceProperties()).containsEntry("ApplicationName", "catalogimport-publish")
                .containsEntry("logServerErrorDetail", "false").containsEntry("connectTimeout", "5")
                .containsEntry("socketTimeout", "40").doesNotContainKey("readOnly").doesNotContainKey("readOnlyMode");
        // Geen pgjdbc-eigenschappen voor een andere driver (H2 zou ze weigeren).
        assertThat(ProdisPublicationDatabaseConfiguration.hikariConfig(properties(ProdisReferenceTestDatabase.url("x")))
                .getDataSourceProperties()).isEmpty();
    }

    @Test
    void theDefaultsAreTheDesignValues() {
        ProdisPublicationDatabaseProperties defaults = new ProdisPublicationDatabaseProperties();
        assertThat(defaults.getConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(defaults.getQueryTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(defaults.getMaximumPoolSize()).isEqualTo(2);
        assertThat(defaults.getUrl()).isNull();
        // De leesbron houdt haar eigen defaults (gedeelde basisklasse).
        assertThat(new ProdisReferenceDatabaseProperties().getQueryTimeout()).isEqualTo(Duration.ofSeconds(10));
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

    private static ProdisPublicationDatabaseProperties properties(String url) {
        ProdisPublicationDatabaseProperties properties = new ProdisPublicationDatabaseProperties();
        properties.setUrl(url);
        properties.setUsername("catalogimport_pub");
        properties.setPassword(SECRET);
        return properties;
    }
}
