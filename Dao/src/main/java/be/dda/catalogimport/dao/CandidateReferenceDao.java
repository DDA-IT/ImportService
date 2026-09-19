package be.dda.catalogimport.dao;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Bulkopslag van de genormaliseerde kritieke koppelreferenties van gestagede kandidaten in
 * {@code import_candidate_reference} (ontwerp fase 3, par. 2 004-9, R-REF-01/R-REF-03).
 * <p>
 * <b>Waarom JDBC en geen JPA.</b> Exact dezelfde reden als bij {@link CandidateStageDao} en
 * {@link CandidatePriceDao}: een levering kan een miljoen regels met meerdere referenties bevatten.
 * De tabel heeft bewust geen entiteit, een samengestelde sleutel
 * {@code (batch_id, row_number, reference_type)} in plaats van een gegenereerde id, en wordt
 * uitsluitend met {@code batchUpdate} geschreven — in dezelfde microbatchtransactie als de
 * stagingrijen zelf, zodat een kandidaat en haar referenties nooit uit elkaar lopen.
 * <p>
 * <b>Byte-neutraal zonder referentiemappings.</b> Een revisie die geen enkele kritieke referentie
 * mapt, levert geen enkele rij: {@link #insertBatch(List)} krijgt dan een lege lijst en doet niets.
 * De referentiecontrole heeft dan ook niets te beoordelen en het gedrag blijft exact dat van
 * bouwstap 3d.
 * <p>
 * <b>Geen rij is geen uitspraak.</b> Er bestaat een rij per <i>gemapte</i> referentie, ook wanneer de
 * bron ze leeg levert ({@code is_empty = true}). Het ontbreken van een rij betekent dat het
 * referentietype voor deze revisie niet gemapt is; daarover doet de screening geen enkele uitspraak
 * (R-REF-03). De databasecheck {@code ck_import_candidate_reference_empty} laat die twee toestanden
 * niet door elkaar lopen.
 * <p>
 * <b>Transactiegrens.</b> Deze DAO opent zelf geen transactie; de screeningservice bepaalt de
 * microbatch-grens. Binnen één transactie wordt hier geschreven en nooit via JPA teruggelezen.
 */
@Repository
public class CandidateReferenceDao {

    private static final String INSERT = "insert into import_candidate_reference ("
            + "batch_id, row_number, reference_type, value_raw, value_normalised, is_empty) "
            + "values (?, ?, ?, ?, ?, ?)";

    /**
     * Eén gemapte kritieke koppelreferentie van één gestagede regel.
     *
     * @param valueRaw        de waarde zoals de leverancier ze stuurde; blijft bewaard naast de
     *                        vergelijkingswaarde (R-REF-01)
     * @param valueNormalised de vergelijkingswaarde, of {@code null} bij "gemapt maar leeg"
     */
    public record ReferenceRow(long batchId, long rowNumber, String referenceType, String valueRaw,
                               String valueNormalised) {

        /** Gemapt maar leeg: een uitspraak van de leverancier, geen ontbrekende mapping. */
        public boolean isEmpty() {
            return valueNormalised == null;
        }
    }

    private final JdbcTemplate jdbc;
    private final int batchSize;

    public CandidateReferenceDao(JdbcTemplate jdbc,
                                 @Value("${catalogimport.screening.stage-batch-size:"
                                         + CandidateStageDao.DEFAULT_BATCH_SIZE + "}")
                                 int batchSize) {
        this.jdbc = jdbc;
        this.batchSize = batchSize > 0 ? batchSize : CandidateStageDao.DEFAULT_BATCH_SIZE;
    }

    /**
     * Schrijft de referenties in JDBC-batches. Een dubbele
     * {@code (batch_id, row_number, reference_type)} of een rij die haar databasecheck schendt, faalt
     * hier hard: dat is bedoeld, niet op te vangen.
     *
     * @return het aantal weggeschreven rijen
     */
    public int insertBatch(List<ReferenceRow> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        int written = 0;
        for (int start = 0; start < rows.size(); start += batchSize) {
            List<ReferenceRow> chunk = rows.subList(start, Math.min(start + batchSize, rows.size()));
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
     * Draagt deze batch kritieke referenties? Zo niet, slaat de volledige referentiecontrole over —
     * dat spaart een join over de bronstaat voor elke bestaande levering. Uit de <b>data</b> en niet
     * uit de configuratie, zodat een hervatte batch dezelfde beslissing neemt als een batch in één
     * keer, ook wanneer de veldmapping niet opnieuw geladen is.
     */
    public boolean hasReferences(long batchId) {
        return countByBatchId(batchId) > 0;
    }

    public long countByBatchId(long batchId) {
        Long count = jdbc.queryForObject(
                "select count(*) from import_candidate_reference where batch_id = ?", Long.class, batchId);
        return count == null ? 0L : count;
    }

    /**
     * Verwijdert de referenties van één batch. Uitsluitend voor een technisch mislukte poging; de
     * databasecascade op {@code import_candidate_stage} ruimt ze ook op wanneer de staging zelf
     * verdwijnt, zodat er nooit een wees kan achterblijven.
     */
    public int deleteByBatchId(long batchId) {
        return jdbc.update("delete from import_candidate_reference where batch_id = ?", batchId);
    }

    private static void bind(PreparedStatement statement, ReferenceRow row) throws SQLException {
        statement.setLong(1, row.batchId());
        statement.setLong(2, row.rowNumber());
        statement.setString(3, row.referenceType());
        if (row.valueRaw() == null) {
            statement.setNull(4, Types.VARCHAR);
        } else {
            statement.setString(4, row.valueRaw());
        }
        if (row.valueNormalised() == null) {
            statement.setNull(5, Types.VARCHAR);
        } else {
            statement.setString(5, row.valueNormalised());
        }
        statement.setBoolean(6, row.isEmpty());
    }
}
