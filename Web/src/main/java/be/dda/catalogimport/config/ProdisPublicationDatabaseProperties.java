package be.dda.catalogimport.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * De schrijfverbinding naar de Prodis-database voor de publicatie ({@code docs/design/publicatie-prodis-db-direct-design.md}
 * par. 2.1, slice P5a; beslissingslog 2026-10-22 "Publicatiefase: P-V1..P-V5", P-V2).
 *
 * <pre>
 * catalogimport:
 *   prodis-db-write:
 *     url: jdbc:postgresql://prodis-db:5432/Prodis   # CATALOG_PRODIS_DB_WRITE_URL
 *     username: catalogimport_pub                    # CATALOG_PRODIS_DB_WRITE_USERNAME (docs/ops/prodis-catalogimport-pub-role.sql)
 *     password: ...                                  # CATALOG_PRODIS_DB_WRITE_PASSWORD (nooit in de repo)
 *     connect-timeout: PT5S                          # wachten op een verbinding (min. 250 ms)
 *     query-timeout: PT30S                           # per opdracht (min. 1 s, hele seconden)
 *     maximum-pool-size: 2                           # 1..10 (de rol heeft CONNECTION LIMIT 4)
 * </pre>
 *
 * Geen default, in geen enkel profiel. Alle drie leeg = functie uit: de applicatie start, publiceren naar Prodis geeft 409
 * {@code PRODIS_WRITE_NOT_CONFIGURED}. Half ingesteld of ongeldig = de applicatie start niet. Onbereikbaar = de applicatie
 * start wel (de pool verbindt pas bij het eerste gebruik). Validatie: {@link ProdisDatabaseProperties}.
 */
@ConfigurationProperties(prefix = ProdisPublicationDatabaseProperties.PREFIX, ignoreUnknownFields = false)
public class ProdisPublicationDatabaseProperties extends ProdisDatabaseProperties {

    static final String PREFIX = "catalogimport.prodis-db-write";

    static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration DEFAULT_QUERY_TIMEOUT = Duration.ofSeconds(30);
    static final int DEFAULT_MAXIMUM_POOL_SIZE = 2;

    public ProdisPublicationDatabaseProperties() {
        super(DEFAULT_CONNECT_TIMEOUT, DEFAULT_QUERY_TIMEOUT, DEFAULT_MAXIMUM_POOL_SIZE);
    }

    @Override
    String prefix() {
        return PREFIX;
    }

    @Override
    String description() {
        return "Prodis publication database";
    }
}
