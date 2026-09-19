package be.dda.catalogimport.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * De klok van de applicatie.
 * <p>
 * Wie een <b>kalenderdag</b> vastlegt, mag niet van {@code Instant.now()} afhangen: de prijshistoriek
 * ({@code catalog_price_observation}) bewaart hoogstens één goedgekeurde waarde per dag per identiteit
 * en component (R-PRI-13), en die dag moet in een test stuurbaar en in productie reproduceerbaar zijn.
 * Daarom loopt dat pad via een injecteerbare {@link Clock}.
 * <p>
 * De klok staat op <b>UTC</b>, niet op de tijdzone van de server: elke tijdstempel in dit schema staat
 * al in UTC, en één vaste regel houdt "één waarde per dag" onafhankelijk van waar de applicatie
 * draait. Zie ook {@code PriceObservationDao.OBSERVATION_ZONE}.
 */
@Configuration
public class TimeConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
