package be.dda.catalogimport.config;

import be.dda.catalogimport.dao.ProdisReferenceJdbc;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * De alleen-lezen Prodis-leesbron ({@code docs/design/legacy-config-bundle-design.md} par. 6 en 10, slice S5).
 *
 * <h2>Technische grenzen (bewust)</h2>
 * <ul>
 *   <li>Het <b>enige</b> bean-type is {@link ProdisReferenceJdbc}. Geen bean van type {@code DataSource},
 *       {@code JdbcTemplate} of {@code NamedParameterJdbcTemplate}: dat zou de autoconfiguratie van de hoofddatabase
 *       (DataSource, JPA, Liquibase, {@code JdbcTemplate}, health-indicator {@code db}) laten terugtreden.</li>
 *   <li>Geen Liquibase en geen transactiemanager op deze bron; de readiness-probe kijkt er niet naar (een Prodis-storing
 *       haalt CatalogImport niet uit de rotatie).</li>
 *   <li>Pool {@code readOnly=true}; voor PostgreSQL daarbij {@code readOnlyMode=always} (pgjdbc zet anders enkel bij
 *       {@code autoCommit=false} een read-only transactie) - aanvullend op de DB-rol {@code catalogimport_ro} met
 *       {@code default_transaction_read_only=on}.</li>
 *   <li>De pool verbindt pas bij de eerste query ({@code initializationFailTimeout=-1}, {@code minimumIdle=0}): een
 *       onbereikbare Prodis-database laat de applicatie starten en houdt geen verbindingen vast.</li>
 * </ul>
 * Niet geconfigureerd: {@link ProdisReferenceJdbc#disabled()} (geen pool). Half of ongeldig: de applicatie start niet
 * ({@link ProdisReferenceDatabaseProperties#validate()}).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProdisReferenceDatabaseProperties.class)
public class ProdisReadOnlyDatabaseConfiguration {

    static final String POOL_NAME = "catalogimport-prodis-ro";
    /** Hoe lang een ongebruikte verbinding open blijft (HikariCP-minimum 10 s). */
    static final Duration IDLE_TIMEOUT = Duration.ofMinutes(2);

    @Bean(destroyMethod = "close")
    public ProdisReferenceJdbc prodisReferenceJdbc(ProdisReferenceDatabaseProperties properties) {
        properties.validate();
        if (!properties.enabled()) {
            return ProdisReferenceJdbc.disabled();
        }
        return ProdisReferenceJdbc.of(new HikariDataSource(hikariConfig(properties)),
                (int) properties.getQueryTimeout().toSeconds());
    }

    /** De poolinstellingen (los, zodat de tests exact deze configuratie kunnen nagaan). */
    static HikariConfig hikariConfig(ProdisReferenceDatabaseProperties properties) {
        HikariConfig config = new HikariConfig();
        config.setPoolName(POOL_NAME);
        config.setJdbcUrl(properties.getUrl());
        config.setUsername(properties.getUsername());
        config.setPassword(properties.getPassword());
        config.setReadOnly(true);
        config.setAutoCommit(true);
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
            config.addDataSourceProperty("readOnly", "true");
            config.addDataSourceProperty("readOnlyMode", "always");
            config.addDataSourceProperty("connectTimeout", String.valueOf(connectSeconds));
            config.addDataSourceProperty("loginTimeout", String.valueOf(connectSeconds));
            // Vangnet voor een hangende socket: ruim boven de query-time-out (die de server zelf afbreekt).
            config.addDataSourceProperty("socketTimeout", String.valueOf(querySeconds + connectSeconds + 5));
            config.addDataSourceProperty("ApplicationName", "catalog-import (prodis read-only)");
            // Zoals de hoofddatabase: geen rijinhoud in foutboodschappen.
            config.addDataSourceProperty("logServerErrorDetail", "false");
        }
        return config;
    }
}
