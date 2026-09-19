package be.dda.catalogimport.dao;

import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * De goedgekeurde prijshistoriek in {@code catalog_price_observation} (ontwerp fase 3, par. 2 004-7,
 * R-PRI-13). Uitsluitend <b>append-only</b>: deze DAO kent geen update en geen delete.
 *
 * <h2>Wat hier wél en niet in komt</h2>
 * <ul>
 *   <li><b>Alleen goedgekeurde waarden.</b> In fase 3 schrijft uitsluitend {@code accept-baseline}
 *       hier (later de publicatie). Een kandidaatprijs uit een lopende screening belandt hier
 *       <b>nooit</b>: een nog niet beoordeelde afwijking zou zichzelf anders tot referentie promoveren
 *       en de volgende levering zou er geen afwijking meer in zien.</li>
 *   <li><b>Alleen {@code NEW} en {@code CHANGED}.</b> Een {@code UNCHANGED}-regel raakt de bronstaat
 *       niet aan en levert dus ook geen observatie op. Gevolg, en dat is bewust: de gemiddelden van
 *       R-PRI-10 lopen over de laatste N <i>vastgelegde</i> goedgekeurde dagwaarden, niet over N
 *       kalenderdagen met doorgetrokken waarden. Een prijs die een jaar lang niet wijzigt, levert één
 *       observatie op en geen 365.</li>
 *   <li><b>Eén waarde per kalenderdag.</b> {@code uk_catalog_price_observation_day} dwingt dat af;
 *       de inserts hieronder slaan een bestaande dagrij over ({@code where not exists}), zodat bij
 *       twee aanvaardingen op dezelfde dag de <b>eerste</b> waarde blijft staan (aanname A16). Er
 *       wordt dus nooit financiële historiek overschreven.</li>
 * </ul>
 *
 * <h2>Kalenderdag en tijdzone</h2>
 * De dag van een observatie wordt bepaald in {@link #OBSERVATION_ZONE} (UTC), niet in de tijdzone van
 * de server. Elke tijdstempel in dit schema staat al in UTC; één vaste regel maakt "hoogstens één
 * waarde per dag" reproduceerbaar en onafhankelijk van waar de applicatie draait. De aanroeper geeft
 * de dag expliciet mee, zodat er binnen één aanvaarding nooit twee dagen door elkaar lopen wanneer de
 * verwerking over middernacht heen loopt.
 *
 * <h2>Set-based en hervatbaar</h2>
 * Beide schrijfroutes zijn één {@code insert ... select} per chunk: de bedragen en de binaire
 * identiteitshashes verlaten de database niet. Een onderbroken of herhaalde aanvaarding schrijft
 * niets dubbel. Deze DAO opent zelf geen transactie; de baseline-service bepaalt de chunkgrens.
 */
@Repository
public class PriceObservationDao {

    /** De componentcode van de basisprijs; zie {@link CandidatePriceDao#BASE_COMPONENT_CODE}. */
    public static final String BASE_COMPONENT_CODE = CandidatePriceDao.BASE_COMPONENT_CODE;

    /**
     * De tijdzone waarin de kalenderdag van een observatie bepaald wordt. Bewust UTC en bewust op
     * één plaats: verschuift deze regel, dan verschuift de betekenis van de volledige prijshistoriek.
     */
    public static final ZoneId OBSERVATION_ZONE = ZoneOffset.UTC;

    /**
     * De basisprijs van elke aanvaarde regel, rechtstreeks uit de staging. Ook een revisie zonder
     * gemapte prijscomponenten levert deze observatie op: de basisprijs is de waarde waarop de
     * afwijkingscontrole in de eerste plaats rekent (R-PRI-10).
     */
    private static final String INSERT_BASE_PRICE = "insert into catalog_price_observation ("
            + "import_link_id, identity_hash, component_code, observation_date, amount, percentage, "
            + "currency, source_state_id, batch_id, accepted_by, created_at) "
            + "select cast(? as bigint), stage.identity_hash, cast(? as varchar(20)), cast(? as date), "
            + "stage.base_price, cast(null as numeric(24,12)), stage.base_price_currency, state.id, "
            + "cast(? as bigint), cast(? as varchar(100)), cast(? as timestamp with time zone) "
            + "from import_candidate_stage stage "
            + "join catalog_source_state state "
            + "  on state.import_link_id = ? and state.identity_hash = stage.identity_hash "
            + "where stage.batch_id = ? and stage.classification in ('NEW', 'CHANGED') "
            + "  and stage.row_number > ? and stage.row_number <= ? "
            + "  and not exists (select 1 from catalog_price_observation existing "
            + "      where existing.import_link_id = state.import_link_id "
            + "        and existing.identity_hash = stage.identity_hash "
            + "        and existing.component_code = cast(? as varchar(20)) "
            + "        and existing.observation_date = cast(? as date))";

    /**
     * De afgeleide prijscomponenten van dezelfde aanvaarde regels. Enkel componenten met status
     * {@code OK}: een component zonder berekenbare verhouding is geen goedgekeurde waarde en mag
     * nooit als referentie voor een latere afwijking dienen.
     */
    private static final String INSERT_COMPONENTS = "insert into catalog_price_observation ("
            + "import_link_id, identity_hash, component_code, observation_date, amount, percentage, "
            + "currency, source_state_id, batch_id, accepted_by, created_at) "
            + "select cast(? as bigint), stage.identity_hash, price.component_code, cast(? as date), "
            + "price.source_amount, price.percentage, price.currency, state.id, "
            + "cast(? as bigint), cast(? as varchar(100)), cast(? as timestamp with time zone) "
            + "from import_candidate_stage stage "
            + "join import_candidate_price price "
            + "  on price.batch_id = stage.batch_id and price.row_number = stage.row_number "
            + "join catalog_source_state state "
            + "  on state.import_link_id = ? and state.identity_hash = stage.identity_hash "
            + "where stage.batch_id = ? and stage.classification in ('NEW', 'CHANGED') "
            + "  and stage.row_number > ? and stage.row_number <= ? "
            + "  and price.component_code <> cast(? as varchar(20)) and price.status = 'OK' "
            + "  and not exists (select 1 from catalog_price_observation existing "
            + "      where existing.import_link_id = state.import_link_id "
            + "        and existing.identity_hash = stage.identity_hash "
            + "        and existing.component_code = price.component_code "
            + "        and existing.observation_date = cast(? as date))";

    /**
     * Wat elke observatie van één aanvaarding meekrijgt.
     *
     * @param observationDate de kalenderdag in {@link #OBSERVATION_ZONE}; één keer per aanvaarding
     *                        bepaald, nooit per chunk opnieuw
     * @param acceptedBy      de mens die aanvaardde; nooit {@code system}
     */
    public record ObservationContext(long importLinkId, long batchId, LocalDate observationDate,
                                     String acceptedBy, Instant createdAt) {
    }

    private final JdbcTemplate jdbc;

    public PriceObservationDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Schrijft de basisprijsobservatie van de aanvaarde regels van één chunk.
     *
     * @return het aantal werkelijk weggeschreven observaties (0 wanneer de dag al vastlag)
     */
    public int insertBasePriceObservations(ObservationContext context, long fromExclusive,
                                           long toInclusive) {
        return jdbc.update(INSERT_BASE_PRICE, statement -> {
            statement.setLong(1, context.importLinkId());
            statement.setString(2, BASE_COMPONENT_CODE);
            statement.setObject(3, context.observationDate());
            statement.setLong(4, context.batchId());
            if (context.acceptedBy() == null) {
                statement.setNull(5, Types.VARCHAR);
            } else {
                statement.setString(5, context.acceptedBy());
            }
            statement.setObject(6, utc(context.createdAt()));
            statement.setLong(7, context.importLinkId());
            statement.setLong(8, context.batchId());
            statement.setLong(9, fromExclusive);
            statement.setLong(10, toInclusive);
            statement.setString(11, BASE_COMPONENT_CODE);
            statement.setObject(12, context.observationDate());
        });
    }

    /**
     * Schrijft de observaties van de afgeleide prijscomponenten van dezelfde chunk. Aanroepen ná
     * {@link #insertBasePriceObservations}: beide hebben de bronstaatrij nodig, die door de
     * acceptatie zelf aangemaakt of bijgewerkt is.
     *
     * @return het aantal werkelijk weggeschreven observaties
     */
    public int insertComponentObservations(ObservationContext context, long fromExclusive,
                                           long toInclusive) {
        return jdbc.update(INSERT_COMPONENTS, statement -> {
            statement.setLong(1, context.importLinkId());
            statement.setObject(2, context.observationDate());
            statement.setLong(3, context.batchId());
            if (context.acceptedBy() == null) {
                statement.setNull(4, Types.VARCHAR);
            } else {
                statement.setString(4, context.acceptedBy());
            }
            statement.setObject(5, utc(context.createdAt()));
            statement.setLong(6, context.importLinkId());
            statement.setLong(7, context.batchId());
            statement.setLong(8, fromExclusive);
            statement.setLong(9, toInclusive);
            statement.setString(10, BASE_COMPONENT_CODE);
            statement.setObject(11, context.observationDate());
        });
    }

    /** Het aantal vastgelegde observaties van één koppeling; voor tellingen en tests. */
    public long countByImportLinkId(long importLinkId) {
        Long count = jdbc.queryForObject(
                "select count(*) from catalog_price_observation where import_link_id = ?",
                Long.class, importLinkId);
        return count == null ? 0L : count;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
