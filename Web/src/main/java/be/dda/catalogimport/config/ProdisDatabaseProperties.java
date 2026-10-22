package be.dda.catalogimport.config;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Locale;

/**
 * Gemeenschappelijke instellingen en fail-fast-validatie van een optionele Prodis-databaseverbinding: de alleen-lezen
 * leesbron ({@link ProdisReferenceDatabaseProperties}, legacy-config S5) en de schrijfverbinding van de publicatie
 * ({@link ProdisPublicationDatabaseProperties}, publicatie P5a). Elke subklasse heeft haar eigen prefix, defaults en
 * omschrijving; het gedrag is identiek:
 * <ul>
 *   <li>{@code url}, {@code username} en {@code password} alle drie leeg = functie uit (de applicatie start);</li>
 *   <li>een of twee van de drie gezet, een URL die geen JDBC-driver herkent of zelf een wachtwoord draagt, of een ongeldige
 *       time-out/poolgrootte = de applicatie start niet;</li>
 *   <li>geen enkele boodschap noemt URL, gebruiker of wachtwoord.</li>
 * </ul>
 */
public abstract class ProdisDatabaseProperties {

    static final int MAX_POOL_SIZE = 10;
    /** Kleinste {@code connectionTimeout} die HikariCP aanvaardt. */
    static final Duration MIN_CONNECT_TIMEOUT = Duration.ofMillis(250);

    private String url;
    private String username;
    private String password;
    private Duration connectTimeout;
    private Duration queryTimeout;
    private int maximumPoolSize;

    protected ProdisDatabaseProperties(Duration connectTimeout, Duration queryTimeout, int maximumPoolSize) {
        this.connectTimeout = connectTimeout;
        this.queryTimeout = queryTimeout;
        this.maximumPoolSize = maximumPoolSize;
    }

    /** De configuratieprefix, bv. {@code catalogimport.prodis-db}. */
    abstract String prefix();

    /** Korte omschrijving voor de foutboodschap, bv. {@code Prodis reference database}. */
    abstract String description();

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
        String prefix = prefix();
        int set = (url == null ? 0 : 1) + (username == null ? 0 : 1) + (password == null ? 0 : 1);
        if (set != 0 && set != 3) {
            throw new IllegalStateException(prefix + ".url, " + prefix + ".username and " + prefix + ".password must "
                    + "all be set (" + description() + " enabled) or all be empty (disabled); missing: " + missing());
        }
        if (connectTimeout == null || connectTimeout.compareTo(MIN_CONNECT_TIMEOUT) < 0) {
            throw new IllegalStateException(prefix + ".connect-timeout must be at least " + MIN_CONNECT_TIMEOUT);
        }
        if (queryTimeout == null || queryTimeout.toSeconds() < 1 || queryTimeout.toSeconds() > Integer.MAX_VALUE) {
            throw new IllegalStateException(prefix + ".query-timeout must be at least 1 second");
        }
        if (maximumPoolSize < 1 || maximumPoolSize > MAX_POOL_SIZE) {
            throw new IllegalStateException(prefix + ".maximum-pool-size must be between 1 and " + MAX_POOL_SIZE);
        }
        if (set == 0) {
            return;
        }
        if (!url.startsWith("jdbc:")) {
            throw new IllegalStateException(prefix + ".url must be a JDBC URL");
        }
        if (url.toLowerCase(Locale.ROOT).contains("password")) {
            throw new IllegalStateException(prefix + ".url must not carry a password; use " + prefix + ".password");
        }
        try {
            DriverManager.getDriver(url);
        } catch (SQLException noDriver) {
            throw new IllegalStateException("No JDBC driver accepts " + prefix + ".url");
        }
    }

    /** {@code true} voor een PostgreSQL-URL: dan gelden de pgjdbc-eigenschappen. */
    boolean postgres() {
        return url != null && url.startsWith("jdbc:postgresql:");
    }

    private String missing() {
        String prefix = prefix();
        StringBuilder missing = new StringBuilder();
        if (url == null) {
            missing.append(prefix).append(".url ");
        }
        if (username == null) {
            missing.append(prefix).append(".username ");
        }
        if (password == null) {
            missing.append(prefix).append(".password");
        }
        return missing.toString().trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Nooit het wachtwoord (en ook niet de URL of gebruiker). */
    @Override
    public String toString() {
        return getClass().getSimpleName() + "[enabled=" + enabled() + "]";
    }
}
