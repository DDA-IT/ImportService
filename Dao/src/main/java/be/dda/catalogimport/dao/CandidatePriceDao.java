package be.dda.catalogimport.dao;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Bulkopslag van de prijscomponenten van gestagede kandidaten in {@code import_candidate_price}
 * (ontwerp fase 3, par. 2 004-5, R-PRI-04/R-PRI-09).
 * <p>
 * <b>Waarom JDBC en geen JPA.</b> Exact dezelfde reden als bij {@link CandidateStageDao}: een levering
 * kan een miljoen regels met tot zeven componenten bevatten. De tabel heeft bewust geen entiteit, een
 * samengestelde sleutel {@code (batch_id, row_number, component_code)} in plaats van een gegenereerde
 * id, en wordt uitsluitend met {@code batchUpdate} geschreven — in dezelfde microbatchtransactie als de
 * stagingrijen zelf, zodat een kandidaat en haar prijzen nooit uit elkaar lopen.
 * <p>
 * <b>Byte-neutraal zonder prijscomponenten.</b> Een revisie die geen enkele prijscomponent mapt, levert
 * geen enkele rij: {@link #insertBatch(List)} krijgt dan een lege lijst en doet niets. De basisprijs
 * blijft in dat geval uitsluitend op {@code import_candidate_stage.base_price} staan, precies zoals in
 * fase 2.
 * <p>
 * <b>Financieel.</b> Bedragen gaan als {@link BigDecimal} naar {@code numeric(24,6)} en percentages
 * naar {@code numeric(24,12)} — nooit via {@code double}. Een component zonder bruikbare basisprijs
 * draagt géén percentage en zegt met haar {@code status} waarom; de databasecheck
 * {@code ck_import_candidate_price_component} laat geen stille "geen verhouding" toe.
 * <p>
 * <b>Transactiegrens.</b> Deze DAO opent zelf geen transactie; de screeningservice bepaalt de
 * microbatch-grens. Binnen één transactie wordt hier geschreven en nooit via JPA teruggelezen.
 */
@Repository
public class CandidatePriceDao {

    /**
     * De componentcode van de basisprijs; draagt een bedrag en nooit een percentage. Dezelfde waarde
     * staat in {@code PriceRules.BASE_COMPONENT_CODE} (Service) en in de databasecheck
     * {@code ck_import_candidate_price_base}: de Dao-laag mag niet van de Service-laag afhangen. Lopen
     * ze ooit uiteen, dan faalt de insert meteen op die check — nooit stil.
     */
    public static final String BASE_COMPONENT_CODE = "BASE_PRICE";

    private static final String INSERT = "insert into import_candidate_price ("
            + "batch_id, row_number, component_code, source_amount, percentage, currency, status) "
            + "values (?, ?, ?, ?, ?, ?, ?)";

    /**
     * Eén prijscomponent van één gestagede regel.
     *
     * @param sourceAmount het geleverde bedrag; nooit {@code null} bij de basisprijs en nooit stil 0
     * @param percentage   de verhouding tot de basisprijs op schaal 12; {@code null} voor de
     *                     basisprijs zelf en voor een component zonder bruikbare basis
     * @param currency     {@code null} wanneer de revisie geen munt leest; dat is "onbekend", niet EUR
     * @param status       {@code OK} of de reden waarom er geen verhouding is (bv. {@code NO_BASE_PRICE})
     */
    public record PriceRow(long batchId, long rowNumber, String componentCode, BigDecimal sourceAmount,
                           BigDecimal percentage, String currency, String status) {
    }

    private final JdbcTemplate jdbc;
    private final int batchSize;

    public CandidatePriceDao(JdbcTemplate jdbc,
                             @Value("${catalogimport.screening.stage-batch-size:"
                                     + CandidateStageDao.DEFAULT_BATCH_SIZE + "}")
                             int batchSize) {
        this.jdbc = jdbc;
        this.batchSize = batchSize > 0 ? batchSize : CandidateStageDao.DEFAULT_BATCH_SIZE;
    }

    /**
     * Schrijft de prijscomponenten in JDBC-batches. Een dubbele
     * {@code (batch_id, row_number, component_code)} of een component die haar databasecheck schendt,
     * faalt hier hard: dat is bedoeld, niet op te vangen.
     *
     * @return het aantal weggeschreven rijen
     */
    public int insertBatch(List<PriceRow> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        int written = 0;
        for (int start = 0; start < rows.size(); start += batchSize) {
            List<PriceRow> chunk = rows.subList(start, Math.min(start + batchSize, rows.size()));
            jdbc.batchUpdate(INSERT, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement statement, int index) throws SQLException {
                    bind(statement, chunk.get(index));
                }

                @Override
                public int getBatchSize() {
                    return chunk.size();
                }
            });
            written += chunk.size();
        }
        return written;
    }

    /**
     * De componentcodes die in deze batch voorkomen, zonder de basisprijs, <b>gesorteerd</b>. Dit is de
     * volgorde waarin ze in het domeinmasker van een mutatie verschijnen (R-PRI-09); ze uit de data
     * halen in plaats van uit de configuratie maakt de mutatiegeneratie ook na een herstart hervatbaar,
     * wanneer de veldmapping niet opnieuw geladen is.
     */
    public List<String> componentCodes(long batchId) {
        return jdbc.queryForList("select distinct component_code from import_candidate_price "
                + "where batch_id = ? and component_code <> ? order by component_code",
                String.class, batchId, BASE_COMPONENT_CODE);
    }

    public long countByBatchId(long batchId) {
        Long count = jdbc.queryForObject("select count(*) from import_candidate_price where batch_id = ?",
                Long.class, batchId);
        return count == null ? 0L : count;
    }

    /**
     * Verwijdert de prijscomponenten van één batch. Uitsluitend voor een technisch mislukte poging; de
     * databasecascade op {@code import_candidate_stage} ruimt ze ook op wanneer de staging zelf
     * verdwijnt, zodat er nooit een wees kan achterblijven.
     */
    public int deleteByBatchId(long batchId) {
        return jdbc.update("delete from import_candidate_price where batch_id = ?", batchId);
    }

    private static void bind(PreparedStatement statement, PriceRow row) throws SQLException {
        statement.setLong(1, row.batchId());
        statement.setLong(2, row.rowNumber());
        statement.setString(3, row.componentCode());
        if (row.sourceAmount() == null) {
            statement.setNull(4, Types.DECIMAL);
        } else {
            statement.setBigDecimal(4, row.sourceAmount());
        }
        if (row.percentage() == null) {
            statement.setNull(5, Types.DECIMAL);
        } else {
            statement.setBigDecimal(5, row.percentage());
        }
        if (row.currency() == null) {
            statement.setNull(6, Types.VARCHAR);
        } else {
            statement.setString(6, row.currency());
        }
        statement.setString(7, row.status());
    }
}
