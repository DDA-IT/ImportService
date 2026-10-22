package be.dda.catalogimport.config;

import be.dda.catalogimport.dao.ProdisPublicationJdbc;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * De schrijfverbinding naar de Prodis-PostgreSQL voor de publicatie ({@code docs/design/publicatie-prodis-db-direct-design.md}
 * par. 2.1-2.2, slice P5a).
 *
 * <h2>Technische grenzen (bewust, zoals {@link ProdisReadOnlyDatabaseConfiguration})</h2>
 * <ul>
 *   <li>Het <b>enige</b> bean-type is {@link ProdisPublicationJdbc}. Geen bean van type {@code DataSource},
 *       {@code JdbcTemplate} of {@code NamedParameterJdbcTemplate}: de autoconfiguratie van de hoofddatabase (DataSource,
 *       JPA, Liquibase, transactiemanager, health-indicator {@code db}) blijft ongewijzigd.</li>
 *   <li>Geen Liquibase, geen Spring-transactiemanager en geen readiness-probe op deze bron; transacties beheert
 *       {@link ProdisPublicationJdbc#inTransaction} zelf (één verbinding, expliciete commit/rollback).</li>
 *   <li>Pool {@code readOnly=false}, {@code autoCommit=false}, READ COMMITTED, {@code minimumIdle=0},
 *       {@code initializationFailTimeout=-1}: een onbereikbare Prodis-database laat de applicatie starten en houdt geen
 *       verbindingen vast. PostgreSQL: {@code ApplicationName=catalogimport-publish} (zichtbaar in
 *       {@code pg_stat_activity}), {@code logServerErrorDetail=false} (geen rijinhoud in foutboodschappen).</li>
 * </ul>
 * Niet geconfigureerd: {@link ProdisPublicationJdbc#disabled()} (geen pool). Half of ongeldig: de applicatie start niet
 * ({@link ProdisDatabaseProperties#validate()}).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProdisPublicationDatabaseProperties.class)
public class ProdisPublicationDatabaseConfiguration {

    static final String POOL_NAME = "catalogimport-prodis-publish";
    static final String APPLICATION_NAME = "catalogimport-publish";
    /** Hoe lang een ongebruikte verbinding open blijft (HikariCP-minimum 10 s). */
    static final Duration IDLE_TIMEOUT = Duration.ofMinutes(2);

    @Bean(destroyMethod = "close")
    public ProdisPublicationJdbc prodisPublicationJdbc(ProdisPublicationDatabaseProperties properties) {
        properties.validate();
        if (!properties.enabled()) {
            return ProdisPublicationJdbc.disabled();
        }
        return ProdisPublicationJdbc.of(new HikariDataSource(hikariConfig(properties)),
                (int) properties.getQueryTimeout().toSeconds());
    }

    /** De poolinstellingen (los, zodat de tests exact deze configuratie kunnen nagaan). */
    static HikariConfig hikariConfig(ProdisPublicationDatabaseProperties properties) {
        HikariConfig config = new HikariConfig();
        config.setPoolName(POOL_NAME);
        config.setJdbcUrl(properties.getUrl());
        config.setUsername(properties.getUsername());
        config.setPassword(properties.getPassword());
        config.setReadOnly(false);
        config.setAutoCommit(false);
        config.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        config.setMaximumPoolSize(properties.getMaximumPoolSize());
        config.setMinimumIdle(0);
        config.setIdleTimeout(IDLE_TIMEOUT.toMillis());
        config.setConnectionTimeout(properties.getConnectTimeout().toMillis());
        config.setValidationTimeout(Math.min(properties.getConnectTimeout().toMillis(), 5_000L));
        config.setInitializationFailTimeout(-1);
        config.setRegisterMbeans(false);
        if (properties.postgres()) {
            long connectSeconds = Math.max(1, (properties.getConnectTimeout().toMillis() + 999) / 1000);
            long querySeconds = properties.getQueryTimeout().toSeconds();
            config.addDataSourceProperty("connectTimeout", String.valueOf(connectSeconds));
            config.addDataSourceProperty("loginTimeout", String.valueOf(connectSeconds));
            // Vangnet voor een hangende socket: ruim boven de query-time-out (die de server zelf afbreekt).
            config.addDataSourceProperty("socketTimeout", String.valueOf(querySeconds + connectSeconds + 5));
            config.addDataSourceProperty("ApplicationName", APPLICATION_NAME);
            // Zoals de hoofddatabase: geen rijinhoud in foutboodschappen.
            config.addDataSourceProperty("logServerErrorDetail", "false");
        }
        return config;
    }
}
