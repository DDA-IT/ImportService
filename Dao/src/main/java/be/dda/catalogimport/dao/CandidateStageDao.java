package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.DiscountCodeState;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Bulkopslag van gescreende kandidaten in {@code import_candidate_stage} (design par. 2).
 * <p>
 * <b>Waarom JDBC en geen JPA.</b> Een levering kan een miljoen regels bevatten. Eén entiteit per
 * regel zou de persistence context laten vollopen, per rij een insert (en bij een identity-kolom
 * zelfs een extra round-trip voor de gegenereerde sleutel) uitvoeren en het geheugen opeten. Deze
 * tabel heeft daarom bewust geen {@code @Entity}, een samengestelde sleutel
 * {@code (batch_id, row_number)} in plaats van een gegenereerde id, en wordt uitsluitend met
 * {@code batchUpdate} geschreven. Binaire hashes gaan als {@code byte[]} naar de database — geen
 * hex-tekst, geen conversie in SQL.
 * <p>
 * <b>Transactiegrens.</b> Deze DAO opent zelf geen transactie; de screeningservice bepaalt de
 * microbatch-grens (design par. 9, stap C). Binnen één transactie wordt hier geschreven en nooit
 * via JPA teruggelezen.
 */
@Repository
public class CandidateStageDao {

    /** Standaard microbatchgrootte, conform design par. 2. */
    public static final int DEFAULT_BATCH_SIZE = 2000;

    private static final String INSERT = "insert into import_candidate_stage ("
            + "batch_id, row_number, delivery_file_id, identity_supplier, identity_supplier_group, "
            + "identity_supplier_reference, identity_discount_code, identity_discount_state, identity_hash, "
            + "base_price, base_price_currency, description, article_fingerprint, price_fingerprint, "
            + "combined_fingerprint, mutation_key_prefix, created_at) "
            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /**
     * Eén te stagen kandidaat.
     *
     * @param identityDiscountCode {@code null} betekent "niet gemapt", {@code ""} betekent "gemapt
     *                             maar leeg"; die twee mogen nooit door elkaar lopen
     * @param basePrice            nooit {@code null} en nooit 0 bij een parsefout: zo'n regel wordt
     *                             niet gestaged maar verworpen
     */
    public record StageRow(
            long batchId,
            long rowNumber,
            long deliveryFileId,
            String identitySupplier,
            String identitySupplierGroup,
            String identitySupplierReference,
            String identityDiscountCode,
            DiscountCodeState identityDiscountState,
            byte[] identityHash,
            BigDecimal basePrice,
            String basePriceCurrency,
            String description,
            byte[] articleFingerprint,
            byte[] priceFingerprint,
            byte[] combinedFingerprint,
            String mutationKeyPrefix,
            Instant createdAt) {
    }

    private final JdbcTemplate jdbc;
    private final int batchSize;

    public CandidateStageDao(JdbcTemplate jdbc,
                             @Value("${catalogimport.screening.stage-batch-size:" + DEFAULT_BATCH_SIZE + "}")
                             int batchSize) {
        this.jdbc = jdbc;
        this.batchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
    }

    /** De geconfigureerde microbatchgrootte, zodat de aanroeper dezelfde grens hanteert. */
    public int batchSize() {
        return batchSize;
    }

    /**
     * Schrijft de kandidaten in JDBC-batches van {@link #batchSize()}. Een dubbele
     * {@code (batch_id, row_number)} of een ontbrekende prijs faalt hier hard op de
     * databaseconstraint: dat is bedoeld, niet op te vangen.
     *
     * @return het aantal weggeschreven rijen
     */
    public int insertBatch(List<StageRow> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        int written = 0;
        for (int start = 0; start < rows.size(); start += batchSize) {
            List<StageRow> chunk = rows.subList(start, Math.min(start + batchSize, rows.size()));
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
     * Verwijdert de staging van één batch. Uitsluitend bedoeld voor een technisch mislukte poging
     * (design par. 9, stap C): een nieuwe poging start met {@code attempt_no + 1} op schone staging.
     * Een geblokkeerde batch behoudt haar staging als bewijsmateriaal.
     */
    public int deleteByBatchId(long batchId) {
        return jdbc.update("delete from import_candidate_stage where batch_id = ?", batchId);
    }

    public long countByBatchId(long batchId) {
        Long count = jdbc.queryForObject("select count(*) from import_candidate_stage where batch_id = ?",
                Long.class, batchId);
        return count == null ? 0L : count;
    }

    private static void bind(PreparedStatement statement, StageRow row) throws SQLException {
        statement.setLong(1, row.batchId());
        statement.setLong(2, row.rowNumber());
        statement.setLong(3, row.deliveryFileId());
        statement.setString(4, row.identitySupplier());
        statement.setString(5, row.identitySupplierGroup());
        statement.setString(6, row.identitySupplierReference());
        if (row.identityDiscountCode() == null) {
            statement.setNull(7, Types.VARCHAR);
        } else {
            statement.setString(7, row.identityDiscountCode());
        }
        statement.setString(8, row.identityDiscountState().name());
        statement.setBytes(9, row.identityHash());
        statement.setBigDecimal(10, row.basePrice());
        if (row.basePriceCurrency() == null) {
            statement.setNull(11, Types.VARCHAR);
        } else {
            statement.setString(11, row.basePriceCurrency());
        }
        if (row.description() == null) {
            statement.setNull(12, Types.VARCHAR);
        } else {
            statement.setString(12, row.description());
        }
        statement.setBytes(13, row.articleFingerprint());
        statement.setBytes(14, row.priceFingerprint());
        statement.setBytes(15, row.combinedFingerprint());
        statement.setString(16, row.mutationKeyPrefix());
        statement.setObject(17, OffsetDateTime.ofInstant(row.createdAt(), ZoneOffset.UTC));
    }
}
