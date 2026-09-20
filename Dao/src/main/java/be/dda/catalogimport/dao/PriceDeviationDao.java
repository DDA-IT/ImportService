package be.dda.catalogimport.dao;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * De leeskant van de prijsafwijkingscontrole (ontwerp fase 3, par. 3.1 stap E3 en par. 3.2,
 * R-PRI-10/R-PRI-11): per gestagede regel en per prijscomponent het kandidaatbedrag naast zijn
 * <b>drie</b> referenties.
 *
 * <h2>Waarom set-based</h2>
 * Een levering kan een miljoen regels met tot zeven componenten bevatten; een query per regel zou
 * zeven miljoen round trips kosten. Daarom levert één query per chunk alle vergelijkingen: de
 * vorige aanvaarde waarde komt uit een join, de twee gemiddelden uit één venstersubquery
 * ({@code row_number() over (partition by import_link_id, identity_hash, component_code order by
 * observation_date desc)}) over {@code catalog_price_observation}.
 *
 * <h2>Financieel</h2>
 * <ul>
 *   <li>De gemiddelden worden berekend als {@code cast(avg(amount) as numeric(24,6))} — een
 *       numerieke aggregatie, <b>nooit</b> een {@code float}/{@code double}-aggregatie.</li>
 *   <li>Deze DAO <b>rekent geen afwijking uit</b>. Ze levert uitsluitend bedragen; de formule van
 *       R-PRI-11 staat exact één keer in {@code PriceDeviationEvaluator} (Service). Twee
 *       implementaties van één financiële formule is één te veel.</li>
 *   <li>Er wordt hier niets geschreven, niets gecorrigeerd en niets afgerond op een prijs: de
 *       controle is read-only op de prijsgegevens (R-PRI-12).</li>
 * </ul>
 *
 * <h2>Welke rijen meedoen</h2>
 * Alleen regels waarvan de identiteit al in {@code catalog_source_state} van <b>deze</b> koppeling
 * bestaat (een nieuwe aanbieding heeft per definitie geen vorige waarde), en daarbinnen alleen de
 * componenten waarvan het bedrag <b>verschilt</b> van de laatste aanvaarde waarde, of waarvoor die
 * laatste waarde ontbreekt. Een ongewijzigde prijs is al eerder goedgekeurd en levert dus geen
 * melding op — anders zou elke levering dezelfde afwijking opnieuw melden.
 * <p>
 * De venstersubquery veronderstelt dat een identiteit hoogstens één keer in de staging voorkomt.
 * Dat is gegarandeerd: een dubbele identiteit blokkeert de levering in stap D, vóór deze pass draait.
 * <p>
 * <b>Transactiegrens.</b> Deze DAO opent zelf geen transactie; de screeningservice bepaalt de
 * chunkgrens en werkt {@code import_batch.price_progress_row_number} in dezelfde transactie bij.
 */
@Repository
public class PriceDeviationDao {

    /** Standaard chunkgrootte van de prijscontrolepass; zelfde grootteorde als de mutatiegeneratie. */
    public static final int DEFAULT_CHUNK_SIZE = MutationDao.DEFAULT_CHUNK_SIZE;

    /** De componentcode van de basisprijs; zie {@link CandidatePriceDao#BASE_COMPONENT_CODE}. */
    public static final String BASE_COMPONENT_CODE = CandidatePriceDao.BASE_COMPONENT_CODE;

    /**
     * Elke te beoordelen combinatie van bronregel en prijscomponent, met haar drie referenties.
     * Een {@code null}-referentie betekent "niet beschikbaar" en nooit 0: het verschil tussen "geen
     * vorige waarde" en "vorige waarde 0" mag niet verdwijnen (R-PRI-11).
     *
     * @param newAmount      het kandidaatbedrag ({@code import_candidate_price.source_amount}, of
     *                       {@code import_candidate_stage.base_price} voor de basisprijs)
     * @param previousAmount de laatste aanvaarde waarde
     * @param averageShort   het gemiddelde van de laatste {@code price_avg_short_window} vastgelegde
     *                       goedgekeurde dagwaarden
     * @param averageLong    idem voor {@code price_avg_long_window}
     */
    public record DeviationRow(long rowNumber, String componentCode, BigDecimal newAmount,
                               BigDecimal previousAmount, BigDecimal averageShort,
                               BigDecimal averageLong) {
    }

    /**
     * Hoeveel vergelijkingen een referentie misten, over de <b>volledige</b> batch. Uit de database
     * geteld en niet in het geheugen bijgehouden: zo klopt het aantal ook wanneer de pass hervat is
     * (R-PRI-11, samenvattende {@code PRICE_REFERENCE_NOT_AVAILABLE}-melding).
     */
    public record MissingReferenceCounts(long evaluated, long previousMissing, long shortAverageMissing,
                                         long longAverageMissing) {

        public long total() {
            return previousMissing + shortAverageMissing + longAverageMissing;
        }
    }

    /**
     * Kandidaatbedrag + drie referenties. De parametervolgorde is die van de SQL-tekst zelf; zie
     * {@link #bind}.
     */
    private static final String CANDIDATES = "select candidate.source_row_number, "
            + "candidate.component_code, candidate.new_amount, candidate.previous_amount, "
            + "candidate.avg_short, candidate.avg_long from ("
            + "select c.source_row_number as source_row_number, c.component_code as component_code, "
            + "c.amount as new_amount, "
            // De vorige geldige waarde: voor de basisprijs de bronstaat zelf, voor een afgeleide
            // component haar aanvaarde bedrag (R-PRI-11, referentie 1).
            + "case when c.component_code = '" + BASE_COMPONENT_CODE + "' then state.base_price "
            + "     else accepted.amount end as previous_amount, "
            + "averages.avg_short as avg_short, averages.avg_long as avg_long "
            + "from ("
            + "  select stage.row_number as source_row_number, stage.identity_hash as identity_hash, "
            + "         cast('" + BASE_COMPONENT_CODE + "' as varchar(20)) as component_code, "
            + "         stage.base_price as amount "
            + "    from import_candidate_stage stage "
            + "   where stage.batch_id = ? and stage.row_number > ? and stage.row_number <= ? "
            + "  union all "
            + "  select stage.row_number, stage.identity_hash, price.component_code, price.source_amount "
            + "    from import_candidate_stage stage "
            + "    join import_candidate_price price "
            + "      on price.batch_id = stage.batch_id and price.row_number = stage.row_number "
            + "   where stage.batch_id = ? and stage.row_number > ? and stage.row_number <= ? "
            + "     and price.component_code <> '" + BASE_COMPONENT_CODE + "' and price.status = 'OK'"
            + ") c "
            + "join catalog_source_state state "
            + "  on state.import_link_id = ? and state.identity_hash = c.identity_hash "
            + "left join catalog_source_state_price accepted "
            + "  on accepted.source_state_id = state.id and accepted.component_code = c.component_code "
            + "left join ("
            + "  select ranked.identity_hash as identity_hash, ranked.component_code as component_code, "
            // avg() negeert NULL: zo leveren twee vensters over dezelfde geordende reeks precies het
            // gemiddelde van de laatste N waarden, in één scan en zonder float.
            + "         cast(avg(case when ranked.rn <= ? then ranked.amount end) as numeric(24,6)) as avg_short, "
            + "         cast(avg(case when ranked.rn <= ? then ranked.amount end) as numeric(24,6)) as avg_long "
            + "    from (select observation.identity_hash as identity_hash, "
            + "                 observation.component_code as component_code, "
            + "                 observation.amount as amount, "
            + "                 row_number() over (partition by observation.import_link_id, "
            + "                     observation.identity_hash, observation.component_code "
            + "                     order by observation.observation_date desc) as rn "
            + "            from catalog_price_observation observation "
            + "            join import_candidate_stage stage "
            + "              on stage.identity_hash = observation.identity_hash "
            + "           where observation.import_link_id = ? and stage.batch_id = ? "
            + "             and stage.row_number > ? and stage.row_number <= ?) ranked "
            + "   where ranked.rn <= ? "
            + "   group by ranked.identity_hash, ranked.component_code"
            + ") averages "
            + "  on averages.identity_hash = c.identity_hash "
            + " and averages.component_code = c.component_code"
            + ") candidate "
            // Alleen een werkelijk gewijzigd (of nooit eerder aanvaard) bedrag wordt beoordeeld.
            + "where candidate.previous_amount is null or candidate.previous_amount <> candidate.new_amount";

    private static final String SELECT_CANDIDATES = CANDIDATES
            + " order by candidate.source_row_number, candidate.component_code";

    private static final String COUNT_MISSING = "select count(*) as evaluated, "
            + "sum(case when missing.previous_amount is null or missing.previous_amount = 0 "
            + "    then 1 else 0 end) as previous_missing, "
            + "sum(case when missing.avg_short is null or missing.avg_short = 0 "
            + "    then 1 else 0 end) as short_missing, "
            + "sum(case when missing.avg_long is null or missing.avg_long = 0 "
            + "    then 1 else 0 end) as long_missing "
            + "from (" + CANDIDATES + ") missing";

    private final JdbcTemplate jdbc;
    private final int chunkSize;

    public PriceDeviationDao(JdbcTemplate jdbc,
                             @Value("${catalogimport.screening.price-control-chunk-size:"
                                     + DEFAULT_CHUNK_SIZE + "}") int chunkSize) {
        this.jdbc = jdbc;
        this.chunkSize = chunkSize > 0 ? chunkSize : DEFAULT_CHUNK_SIZE;
    }

    /** De geconfigureerde chunkgrootte van deze pass. */
    public int chunkSize() {
        return chunkSize;
    }

    /**
     * Het hoogste regelnummer van de volgende chunk gestagede regels ná {@code afterRowNumber}, of
     * {@code null} wanneer er geen regels meer zijn. Eigen chunkgrens, want deze pass heeft haar
     * eigen hervatpunt ({@code import_batch.price_progress_row_number}).
     */
    public Long nextChunkBoundary(long batchId, long afterRowNumber) {
        List<Long> boundary = jdbc.queryForList("select max(chunk.row_number) from ("
                + "select row_number from import_candidate_stage where batch_id = ? and row_number > ? "
                + "order by row_number limit ?) chunk", Long.class, batchId, afterRowNumber, chunkSize);
        return boundary.isEmpty() ? null : boundary.get(0);
    }

    /**
     * Bestaat er al een aanvaarde bronstaat voor deze koppeling? Zo niet, dan is er geen enkele
     * vorige waarde en geen enkele observatie, en heeft de volledige pass niets te doen: een eerste
     * levering kost zo geen extra query's en levert geen enkele melding op.
     */
    public long countSourceStateRows(long importLinkId) {
        Long count = jdbc.queryForObject(
                "select count(*) from catalog_source_state where import_link_id = ?",
                Long.class, importLinkId);
        return count == null ? 0L : count;
    }

    /**
     * De logische veldnaam per prijscomponent uit {@code import_field_catalog} (meldingsstijl
     * par. 15.12). Exact één query per batch — nooit per regel.
     */
    public Map<String, String> fieldNameByComponent() {
        Map<String, String> names = new LinkedHashMap<>();
        jdbc.query("select price_component_code, name from import_field_catalog "
                        + "where price_component_code is not null order by sort_order",
                resultSet -> {
                    names.putIfAbsent(resultSet.getString(1), resultSet.getString(2));
                });
        return names;
    }

    /** Zie {@link #CANDIDATES}. */
    public List<DeviationRow> findCandidates(long batchId, long importLinkId, int shortWindow,
                                             int longWindow, long fromExclusive, long toInclusive) {
        return jdbc.query(SELECT_CANDIDATES,
                statement -> bind(statement, batchId, importLinkId, shortWindow, longWindow,
                        fromExclusive, toInclusive),
                (resultSet, index) -> new DeviationRow(resultSet.getLong(1), resultSet.getString(2),
                        resultSet.getBigDecimal(3), resultSet.getBigDecimal(4),
                        resultSet.getBigDecimal(5), resultSet.getBigDecimal(6)));
    }

    /**
     * Het aantal werkelijk uitgevoerde prijsvergelijkingen over de <b>volledige</b> batch: de scope
     * waartegen een bulkprijsincident gemeten wordt (R-PRI-14/R-THR-04). Dezelfde selectie als
     * {@link #findCandidates}, dus exact de vergelijkingen die de controle ook beoordeeld heeft —
     * een nieuwe aanbieding zonder referentie en een ongewijzigd bedrag horen niet in de noemer.
     * <p>
     * Uit de database geteld en niet in het geheugen bijgehouden: zo klopt de scope ook wanneer de
     * prijscontrolepass in twee doorlopen afgewerkt is.
     */
    public long countComparisons(long batchId, long importLinkId, int shortWindow, int longWindow) {
        Long count = jdbc.query("select count(*) from (" + CANDIDATES + ") scope",
                statement -> bind(statement, batchId, importLinkId, shortWindow, longWindow,
                        0L, Long.MAX_VALUE),
                resultSet -> resultSet.next() ? resultSet.getLong(1) : 0L);
        return count == null ? 0L : count;
    }

    /** Zie {@link MissingReferenceCounts}; telt over de volledige batch, niet over één chunk. */
    public MissingReferenceCounts countMissingReferences(long batchId, long importLinkId,
                                                         int shortWindow, int longWindow) {
        return jdbc.query(COUNT_MISSING,
                statement -> bind(statement, batchId, importLinkId, shortWindow, longWindow,
                        0L, Long.MAX_VALUE),
                resultSet -> {
                    if (!resultSet.next()) {
                        return new MissingReferenceCounts(0L, 0L, 0L, 0L);
                    }
                    return new MissingReferenceCounts(resultSet.getLong(1), resultSet.getLong(2),
                            resultSet.getLong(3), resultSet.getLong(4));
                });
    }

    /**
     * De parameters in exact de volgorde waarin de vraagtekens in {@link #CANDIDATES} staan. Ze
     * wijken bewust af van de logische volgorde: de venstersubquery staat tekstueel ná de join op de
     * bronstaat.
     */
    private static void bind(java.sql.PreparedStatement statement, long batchId, long importLinkId,
                             int shortWindow, int longWindow, long fromExclusive, long toInclusive)
            throws java.sql.SQLException {
        statement.setLong(1, batchId);          // basisprijsdeel van de union
        statement.setLong(2, fromExclusive);
        statement.setLong(3, toInclusive);
        statement.setLong(4, batchId);          // componentdeel van de union
        statement.setLong(5, fromExclusive);
        statement.setLong(6, toInclusive);
        statement.setLong(7, importLinkId);     // join op de bronstaat
        statement.setInt(8, shortWindow);       // venster van het korte gemiddelde
        statement.setInt(9, longWindow);        // venster van het lange gemiddelde
        statement.setLong(10, importLinkId);    // observaties van deze koppeling
        statement.setLong(11, batchId);
        statement.setLong(12, fromExclusive);
        statement.setLong(13, toInclusive);
        statement.setInt(14, Math.max(shortWindow, longWindow));
    }
}
