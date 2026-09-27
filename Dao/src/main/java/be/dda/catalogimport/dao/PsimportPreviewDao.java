package be.dda.catalogimport.dao;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /** Een prijscomponent van de bundelsnapshot, ongewijzigd ({@code percentage} kan {@code null} zijn). */
    public record SnapshotPrice(String componentCode, BigDecimal percentage, String status) {
    }

    /**
     * Eén bronrij; de mutatievelden precies zoals ze in {@code import_mutation} staan. {@code snapshotId}
     * is {@code null} als er voor deze mutatie geen snapshotrij bestaat (bundel van vóór 5-PUB);
     * {@code description}/{@code descriptionState} en {@code prices} komen dan ook niet uit de snapshot.
     */
    public record SourceRow(long batchId, long mutationId, String actionType, String identitySupplier,
                            String identitySupplierGroup, String identitySupplierReference,
                            String identityDiscountCode, String identityDiscountState,
                            BigDecimal afterBasePrice, String basePriceCurrency,
                            String referenceType, String afterReferenceValue,
                            Long snapshotId, String description, String descriptionState,
                            List<SnapshotPrice> prices) {
    }

    /** Scope (ongewijzigd): het {@code from}-deel, gesplitst zodat het page-pad er een join tussen kan zetten. */
    private static final String FROM = "from import_mutation m ";
    private static final String WHERE = "where m.batch_id in (select bb.batch_id from publication_bundle_batch bb "
            + "    where bb.bundle_id = ? and bb.active_marker is not null) "
            + "  and m.status = 'READY_FOR_PUBLICATION' and m.action_type in ('CREATE', 'UPDATE') ";
    private static final String FROM_WHERE = FROM + WHERE;

    /**
     * Left join op de snapshot: {@code unique (mutation_id)} garandeert hoogstens één snapshotrij per
     * mutatie, dus de join dupliceert of verwijdert nooit een rij en het count-pad (zonder join) blijft
     * identiek. De extra {@code sn.bundle_id = ?} is defensief.
     */
    private static final String SNAPSHOT_JOIN = "left join publication_bundle_snapshot sn "
            + "on sn.mutation_id = m.id and sn.bundle_id = ? ";

    private final JdbcTemplate jdbc;

    public PsimportPreviewDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long count(long bundleId) {
        Long count = jdbc.queryForObject("select count(*) " + FROM_WHERE, Long.class, bundleId);
        return count == null ? 0L : count;
    }

    public List<SourceRow> findPage(long bundleId, int limit, long offset) {
        List<SourceRow> base = jdbc.query("select m.batch_id, m.id, m.action_type, m.identity_supplier, "
                + "m.identity_supplier_group, m.identity_supplier_reference, m.identity_discount_code, "
                + "m.identity_discount_state, m.after_base_price, m.base_price_currency, "
                + "m.reference_type, m.after_reference_value, sn.id, sn.description, sn.description_state "
                + FROM + SNAPSHOT_JOIN + WHERE
                + "order by m.batch_id asc, m.id asc limit ? offset ?",
                (rs, rowNum) -> {
                    long snapshotId = rs.getLong(13);
                    Long id = rs.wasNull() ? null : snapshotId;
                    return new SourceRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                            rs.getBigDecimal(9), rs.getString(10), rs.getString(11), rs.getString(12),
                            id, rs.getString(14), rs.getString(15), List.of());
                },
                bundleId, bundleId, limit, offset);
        // Tweede query per pagina voor de prijscomponenten: geen rijvermenigvuldiging in de paginering.
        List<Long> snapshotIds = base.stream().map(SourceRow::snapshotId).filter(java.util.Objects::nonNull)
                .toList();
        if (snapshotIds.isEmpty()) {
            return base;
        }
        Map<Long, List<SnapshotPrice>> pricesBySnapshot = new HashMap<>();
        jdbc.query("select snapshot_id, component_code, percentage, status "
                        + "from publication_bundle_snapshot_price where snapshot_id in ("
                        + String.join(",", java.util.Collections.nCopies(snapshotIds.size(), "?")) + ") "
                        + "order by snapshot_id, component_code",
                rs -> {
                    pricesBySnapshot.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                            .add(new SnapshotPrice(rs.getString(2), rs.getBigDecimal(3), rs.getString(4)));
                }, snapshotIds.toArray());
        return base.stream().map(r -> r.snapshotId() == null ? r : new SourceRow(r.batchId(), r.mutationId(),
                r.actionType(), r.identitySupplier(), r.identitySupplierGroup(), r.identitySupplierReference(),
                r.identityDiscountCode(), r.identityDiscountState(), r.afterBasePrice(), r.basePriceCurrency(),
                r.referenceType(), r.afterReferenceValue(), r.snapshotId(), r.description(),
                r.descriptionState(), List.copyOf(pricesBySnapshot.getOrDefault(r.snapshotId(), List.of()))))
                .toList();
    }
}
