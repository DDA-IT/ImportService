package be.dda.catalogimport.dao;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only JDBC-projectie voor de PSIMPORT-preview van een bevroren bundel (beslissing 2026-09-25,
 * slice 1). Zelfde patroon als {@code MutationDao.findIdentityHashes}: {@link JdbcTemplate}, geen
 * JPA-entiteit, en geen enkele schrijfactie.
 * <p>
 * Afbakening in SQL: enkel {@code READY_FOR_PUBLICATION}-mutaties met {@code action_type} CREATE of
 * UPDATE, binnen de <b>actieve</b> batchlidmaatschappen van de bundel
 * ({@code publication_bundle_batch.active_marker is not null}). {@code IMPORT_MARKER},
 * {@code IDENTITY_REFERENCE_INCIDENT} en {@code BLOCKED} vallen er dus nooit in. Vaste sortering:
 * {@code batch_id asc, m.id asc}. Geen waarde wordt hier aangepast of ingevuld: {@code null} blijft
 * {@code null} (de mapper bepaalt de state).
 */
@Repository
public class PsimportPreviewDao {

    /** Eén bronrij; alle velden precies zoals ze in {@code import_mutation} staan. */
    public record SourceRow(long batchId, long mutationId, String actionType, String identitySupplier,
                            String identitySupplierGroup, String identitySupplierReference,
                            String identityDiscountCode, String identityDiscountState,
                            BigDecimal afterBasePrice, String basePriceCurrency,
                            String referenceType, String afterReferenceValue) {
    }

    private static final String FROM_WHERE = "from import_mutation m "
            + "where m.batch_id in (select bb.batch_id from publication_bundle_batch bb "
            + "    where bb.bundle_id = ? and bb.active_marker is not null) "
            + "  and m.status = 'READY_FOR_PUBLICATION' and m.action_type in ('CREATE', 'UPDATE') ";

    private final JdbcTemplate jdbc;

    public PsimportPreviewDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long count(long bundleId) {
        Long count = jdbc.queryForObject("select count(*) " + FROM_WHERE, Long.class, bundleId);
        return count == null ? 0L : count;
    }

    public List<SourceRow> findPage(long bundleId, int limit, long offset) {
        return jdbc.query("select m.batch_id, m.id, m.action_type, m.identity_supplier, "
                + "m.identity_supplier_group, m.identity_supplier_reference, m.identity_discount_code, "
                + "m.identity_discount_state, m.after_base_price, m.base_price_currency, "
                + "m.reference_type, m.after_reference_value " + FROM_WHERE
                + "order by m.batch_id asc, m.id asc limit ? offset ?",
                (rs, rowNum) -> new SourceRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getBigDecimal(9), rs.getString(10), rs.getString(11), rs.getString(12)),
                bundleId, limit, offset);
    }
}
