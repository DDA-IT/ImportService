package be.dda.catalogimport.config;

import java.time.Duration;
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
 * Validatie en velden: {@link ProdisDatabaseProperties} (gedeeld met de schrijfverbinding van de publicatie).
 */
@ConfigurationProperties(prefix = ProdisReferenceDatabaseProperties.PREFIX, ignoreUnknownFields = false)
public class ProdisReferenceDatabaseProperties extends ProdisDatabaseProperties {

    static final String PREFIX = "catalogimport.prodis-db";

    static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration DEFAULT_QUERY_TIMEOUT = Duration.ofSeconds(10);
    static final int DEFAULT_MAXIMUM_POOL_SIZE = 2;

    public ProdisReferenceDatabaseProperties() {
        super(DEFAULT_CONNECT_TIMEOUT, DEFAULT_QUERY_TIMEOUT, DEFAULT_MAXIMUM_POOL_SIZE);
    }

    @Override
    String prefix() {
        return PREFIX;
    }

    @Override
    String description() {
        return "Prodis reference database";
    }
}
