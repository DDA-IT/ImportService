package be.dda.catalogimport.config;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * De alleen-lezen Prodis-database ({@code docs/design/legacy-config-bundle-design.md} par. 6, slice S5; beslissingslog
 * 2026-10-20 "datasync-aanpak, Prodis-DB lezen, sjablonen").
 *
 * <pre>
 * catalogimport:
 *   prodis-db:
 *     url: jdbc:postgresql://prodis-db:5432/Prodis   # CATALOG_PRODIS_DB_URL
 *     username: catalogimport_ro                     # CATALOG_PRODIS_DB_USERNAME
 *     password: ...                                  # CATALOG_PRODIS_DB_PASSWORD (nooit in de repo)
 *     connect-timeout: PT5S                          # wachten op een verbinding (min. 250 ms)
 *     query-timeout: PT10S                           # per query (min. 1 s, hele seconden)
 *     maximum-pool-size: 2                           # 1..10
 * </pre>
 *
 * <b>Gedrag zoals de andere optionele functies</b> (sleutelring, technische client): {@code url}, {@code username} en
 * {@code password} alle drie leeg = functie uit (de applicatie start; keuzelijsten 409, PREVIEW zoals S2). Een of twee
 * van de drie gezet, een URL die geen JDBC-driver herkent of die zelf een wachtwoord draagt, of een ongeldige
 * time-out/poolgrootte = de applicatie start niet. Een onbereikbare database laat de applicatie wél starten (de pool
 * verbindt pas bij de eerste query; 503 / "unverified"). Geen enkele boodschap noemt URL, gebruiker of wachtwoord.
 */
@ConfigurationProperties(prefix = ProdisReferenceDatabaseProperties.PREFIX, ignoreUnknownFields = false)
public class ProdisReferenceDatabaseProperties {

    static final String PREFIX = "catalogimport.prodis-db";

    static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration DEFAULT_QUERY_TIMEOUT = Duration.ofSeconds(10);
    static final int DEFAULT_MAXIMUM_POOL_SIZE = 2;
    static final int MAX_POOL_SIZE = 10;
    /** Kleinste {@code connectionTimeout} die HikariCP aanvaardt. */
    static final Duration MIN_CONNECT_TIMEOUT = Duration.ofMillis(250);

    private String url;
    private String username;
    private String password;
    private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
    private Duration queryTimeout = DEFAULT_QUERY_TIMEOUT;
    private int maximumPoolSize = DEFAULT_MAXIMUM_POOL_SIZE;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = blankToNull(url);
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = blankToNull(username);
    }

    public String getPassword() {
        return password;
    }

    /** Niet getrimd: een wachtwoord mag met witruimte beginnen of eindigen; enkel helemaal leeg = niet gezet. */
    public void setPassword(String password) {
        this.password = password == null || password.isEmpty() ? null : password;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getQueryTimeout() {
        return queryTimeout;
    }

    public void setQueryTimeout(Duration queryTimeout) {
        this.queryTimeout = queryTimeout;
    }

    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    public void setMaximumPoolSize(int maximumPoolSize) {
        this.maximumPoolSize = maximumPoolSize;
    }

    /** {@code true} als url, gebruiker en wachtwoord gezet zijn (na {@link #validate()}). */
    boolean enabled() {
        return url != null;
    }

    /**
     * Fail-fast bij het opstarten.
     *
     * @throws IllegalStateException half ingesteld of ongeldig; de boodschap noemt nooit een waarde
     */
    void validate() {
        int set = (url == null ? 0 : 1) + (username == null ? 0 : 1) + (password == null ? 0 : 1);
        if (set != 0 && set != 3) {
            throw new IllegalStateException(PREFIX + ".url, " + PREFIX + ".username and " + PREFIX + ".password must "
                    + "all be set (Prodis reference database enabled) or all be empty (disabled); missing: "
                    + missing());
        }
        if (connectTimeout == null || connectTimeout.compareTo(MIN_CONNECT_TIMEOUT) < 0) {
            throw new IllegalStateException(PREFIX + ".connect-timeout must be at least " + MIN_CONNECT_TIMEOUT);
        }
        if (queryTimeout == null || queryTimeout.toSeconds() < 1 || queryTimeout.toSeconds() > Integer.MAX_VALUE) {
            throw new IllegalStateException(PREFIX + ".query-timeout must be at least 1 second");
        }
        if (maximumPoolSize < 1 || maximumPoolSize > MAX_POOL_SIZE) {
            throw new IllegalStateException(PREFIX + ".maximum-pool-size must be between 1 and " + MAX_POOL_SIZE);
        }
        if (set == 0) {
            return;
        }
        if (!url.startsWith("jdbc:")) {
            throw new IllegalStateException(PREFIX + ".url must be a JDBC URL");
        }
        if (url.toLowerCase(Locale.ROOT).contains("password")) {
            throw new IllegalStateException(PREFIX + ".url must not carry a password; use " + PREFIX + ".password");
        }
        try {
            DriverManager.getDriver(url);
        } catch (SQLException noDriver) {
            throw new IllegalStateException("No JDBC driver accepts " + PREFIX + ".url");
        }
    }

    /** {@code true} voor een PostgreSQL-URL: dan gelden de pgjdbc-eigenschappen (alleen lezen, time-outs). */
    boolean postgres() {
        return url != null && url.startsWith("jdbc:postgresql:");
    }

    private String missing() {
        StringBuilder missing = new StringBuilder();
        if (url == null) {
            missing.append(PREFIX).append(".url ");
        }
        if (username == null) {
            missing.append(PREFIX).append(".username ");
        }
        if (password == null) {
            missing.append(PREFIX).append(".password");
        }
        return missing.toString().trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Nooit het wachtwoord (en ook niet de URL of gebruiker). */
    @Override
    public String toString() {
        return "ProdisReferenceDatabaseProperties[enabled=" + enabled() + "]";
    }
}
