package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.DiscountCodeState;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
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
            + "reference_fingerprint, combined_fingerprint, mutation_key_prefix, created_at) "
            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /**
     * Eén te stagen kandidaat.
     *
     * @param identityDiscountCode {@code null} betekent "niet gemapt", {@code ""} betekent "gemapt
     *                             maar leeg"; die twee mogen nooit door elkaar lopen
     * @param basePrice            nooit {@code null} en nooit 0 bij een parsefout: zo'n regel wordt
     *                             niet gestaged maar verworpen
     * @param referenceFingerprint {@code null} bij canonicalisatieversie 1, die geen referentiedeel
     *                             kent; dat is iets anders dan een lege referentielijst onder versie 2
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
            byte[] referenceFingerprint,
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

    // --- Duplicaat- en collisiedetectie (design par. 2 en par. 3) ------------------------------

    /**
     * Eén gestagede regel die haar aanbiedingsidentiteit met een andere regel uit dezelfde levering
     * deelt, samen met het regelnummer van de <i>eerste</i> voorkomst van die identiteit.
     */
    public record DuplicateRow(long rowNumber, long firstRowNumber) {
    }

    /**
     * Het aantal gestagede regels dat betrokken is bij een dubbele identiteit — dus inclusief de
     * eerste voorkomst, want "laatste wint" bestaat niet: álle betrokken regels zijn verdacht.
     * <p>
     * Deze teller is bewust het aantal <b>regels</b> en niet het aantal identiteiten, zodat
     * {@code valid_record_count} blijft opgaan als
     * {@code new + changed + unchanged + duplicate_identity_count}.
     */
    public long countDuplicateRows(long batchId) {
        Long count = jdbc.queryForObject(
                "select coalesce(sum(occurrences), 0) from ("
                        + "select count(*) as occurrences from import_candidate_stage where batch_id = ? "
                        + "group by identity_hash having count(*) > 1) duplicates",
                Long.class, batchId);
        return count == null ? 0L : count;
    }

    /**
     * De betrokken regels bij een dubbele identiteit, oplopend op regelnummer en begrensd tot
     * {@code limit} (de issue-cap): bij een miljoen dubbele regels mogen er geen miljoen
     * {@code import_row_issue}-rijen ontstaan. De batch blokkeert hoe dan ook.
     */
    public List<DuplicateRow> findDuplicateRows(long batchId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return jdbc.query("select stage.row_number, duplicates.first_row_number "
                        + "from import_candidate_stage stage "
                        + "join (select identity_hash, min(row_number) as first_row_number "
                        + "      from import_candidate_stage where batch_id = ? "
                        + "      group by identity_hash having count(*) > 1) duplicates "
                        + "  on duplicates.identity_hash = stage.identity_hash "
                        + "where stage.batch_id = ? order by stage.row_number limit ?",
                (resultSet, index) -> new DuplicateRow(resultSet.getLong(1), resultSet.getLong(2)),
                batchId, batchId, limit);
    }

    /** Markeert elke betrokken regel; nooit "laatste wint", nooit stil één van de twee kiezen. */
    public int classifyDuplicates(long batchId) {
        return jdbc.update("update import_candidate_stage set classification = 'DUPLICATE_IN_DELIVERY' "
                + "where batch_id = ? and identity_hash in ("
                + "select identity_hash from import_candidate_stage where batch_id = ? "
                + "group by identity_hash having count(*) > 1)", batchId, batchId);
    }

    /**
     * Zoekt binnen één batch een {@code identity_hash} die door regels met <b>verschillende</b>
     * sleutelcomponenten gedeeld wordt: een echte hashcollisie, geen dubbele levering. Zonder deze
     * controle zouden twee verschillende aanbiedingen als één identiteit behandeld worden en zou de
     * delta stilzwijgend de verkeerde prijs bijwerken.
     *
     * @return het laagste regelnummer van de eerste collisie, of leeg
     */
    public OptionalLong findIdentityHashCollisionRow(long batchId) {
        List<Long> rows = jdbc.queryForList("select min(row_number) from import_candidate_stage "
                + "where batch_id = ? group by identity_hash having "
                + "min(identity_supplier) <> max(identity_supplier) "
                + "or min(identity_supplier_group) <> max(identity_supplier_group) "
                + "or min(identity_supplier_reference) <> max(identity_supplier_reference) "
                + "or min(identity_discount_state) <> max(identity_discount_state) "
                + "or min(coalesce(identity_discount_code, '')) <> max(coalesce(identity_discount_code, '')) "
                + "order by 1 limit 1", Long.class, batchId);
        return rows.isEmpty() ? OptionalLong.empty() : OptionalLong.of(rows.get(0));
    }

    /**
     * Aantal gestagede regels per {@code classification}; regels zonder classificatie tellen niet
     * mee (de delta heeft ze nog niet gezien).
     */
    public Map<String, Long> countByClassification(long batchId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query("select classification, count(*) from import_candidate_stage "
                + "where batch_id = ? and classification is not null group by classification",
                resultSet -> {
                    counts.put(resultSet.getString(1), resultSet.getLong(2));
                }, batchId);
        return counts;
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
        if (row.referenceFingerprint() == null) {
            statement.setNull(15, Types.BINARY);
        } else {
            statement.setBytes(15, row.referenceFingerprint());
        }
        statement.setBytes(16, row.combinedFingerprint());
        statement.setString(17, row.mutationKeyPrefix());
        statement.setObject(18, OffsetDateTime.ofInstant(row.createdAt(), ZoneOffset.UTC));
    }
}
