package be.dda.catalogimport.dao;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Leest de bevroren bundelsnapshot als bron voor de publicatie naar de Prodis-database (design
 * {@code publicatie-prodis-db-direct-design.md} par. 2.5/6, slice P2b). Read-only JDBC-projectie, zelfde patroon als
 * {@link PsimportPreviewDao}: geen JPA-entiteit, geen schrijfactie, geen enkele waarde aangepast of ingevuld
 * ({@code null} blijft {@code null}; de pure {@code ProdisPublicationProjection} bepaalt de toestand).
 *
 * <h2>Scope: de snapshot, niet de live mutatiestatus</h2>
 * Anders dan de PSIMPORT-preview vertrekt deze lezer van {@code publication_bundle_snapshot}: de snapshot <b>is</b> de
 * bevroren scope van de bundel (5-PUB par. 1), en de mutatiestatus verandert tijdens het publiceren. Per snapshotrij komen
 * mee: de identiteit, basisprijs vóór/na en munt uit {@code import_mutation}, de bibliotheek
 * ({@code import_link.library_code} = Prodis {@code supplier_catalog.scope_key}), de omschrijving en alle
 * prijscomponenten van de snapshot, en {@code publication_bundle.snapshot_spec_version}.
 * <p>
 * Vaste volgorde {@code mutation_id asc} (dezelfde als de {@code snapshot_hash}), met keyset-paginering op
 * {@code mutation_id}: deterministisch, ook als er tussen twee pagina's niets verandert.
 * <p>
 * {@link SourcePrice#beforePercentage()} is {@code publication_bundle_snapshot_price.before_percentage}, letterlijk: gevuld
 * door het bevriezen sinds snapshot spec v2 (P4b), {@code null} voor spec-v1-bundels, voor CREATE en voor een component zonder
 * bronstaatwaarde. Deze lezer kijkt niet naar de specversie; de projectie gebruikt de waarde enkel bij spec &ge; 2.
 */
@Repository
public class ProdisPublicationSourceDao {

    /** Hoogstens zoveel snapshotrijen per pagina. */
    public static final int MAX_PAGE_SIZE = 1000;

    /**
     * Eén prijscomponent van de snapshot, ongewijzigd uit {@code publication_bundle_snapshot_price}.
     *
     * @param beforePercentage het percentage vóór deze levering (snapshot spec v2, P4b); {@code null} = niet beschikbaar
     */
    public record SourcePrice(String componentCode, BigDecimal sourceAmount, BigDecimal percentage, String currency,
                              String status, BigDecimal beforePercentage) {
    }

    /**
     * Eén snapshotrij met de bijhorende mutatie- en koppelingsvelden, letterlijk uit de database.
     *
     * @param snapshotSpecVersion {@code publication_bundle.snapshot_spec_version} ("1" vóór P4b, "2" sindsdien; {@code null}
     *                            zonder snapshot)
     * @param prices              alle prijscomponenten van de snapshotrij, oplopend op {@code component_code}
     */
    public record SourceRow(long bundleId, long snapshotId, long mutationId, String actionType, String identitySupplier,
                            String identitySupplierGroup, String identitySupplierReference, String identityDiscountCode,
                            String identityDiscountState, String libraryCode, BigDecimal beforeBasePrice,
                            BigDecimal afterBasePrice, String basePriceCurrency, String description,
                            String descriptionState, String snapshotSpecVersion, List<SourcePrice> prices) {
        public SourceRow {
            prices = List.copyOf(prices);
        }
    }

    private static final String SELECT_ROWS = "select sn.id, sn.mutation_id, m.action_type, m.identity_supplier, "
            + "m.identity_supplier_group, m.identity_supplier_reference, m.identity_discount_code, "
            + "m.identity_discount_state, l.library_code, m.before_base_price, m.after_base_price, m.base_price_currency, "
            + "sn.description, sn.description_state, b.snapshot_spec_version "
            + "from publication_bundle_snapshot sn "
            + "join import_mutation m on m.id = sn.mutation_id "
            + "join import_link l on l.id = sn.import_link_id "
            + "join publication_bundle b on b.id = sn.bundle_id "
            + "where sn.bundle_id = ? and sn.mutation_id > ? "
            + "order by sn.mutation_id asc limit ?";

    private final JdbcTemplate jdbc;

    public ProdisPublicationSourceDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Het aantal snapshotrijen van de bundel (de volledige publicatiescope). */
    public long count(long bundleId) {
        Long count = jdbc.queryForObject("select count(*) from publication_bundle_snapshot where bundle_id = ?",
                Long.class, bundleId);
        return count == null ? 0L : count;
    }

    /**
     * De volgende pagina snapshotrijen na {@code afterMutationId} (exclusief), oplopend op {@code mutation_id}.
     *
     * @param afterMutationId {@code 0} voor de eerste pagina, daarna de {@code mutationId} van de laatste rij
     * @param limit           1..{@value #MAX_PAGE_SIZE}
     */
    public List<SourceRow> findPage(long bundleId, long afterMutationId, int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE_SIZE + ": " + limit);
        }
        List<SourceRow> base = jdbc.query(SELECT_ROWS, (rs, rowNum) -> new SourceRow(bundleId, rs.getLong(1),
                rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getString(8), rs.getString(9), rs.getBigDecimal(10), rs.getBigDecimal(11), rs.getString(12),
                rs.getString(13), rs.getString(14), rs.getString(15), List.of()), bundleId, afterMutationId, limit);
        if (base.isEmpty()) {
            return base;
        }
        // Tweede query per pagina voor de prijscomponenten: geen rijvermenigvuldiging in de paginering.
        Object[] snapshotIds = base.stream().map(SourceRow::snapshotId).toArray();
        Map<Long, List<SourcePrice>> pricesBySnapshot = new HashMap<>();
        jdbc.query("select snapshot_id, component_code, source_amount, percentage, currency, status, before_percentage "
                        + "from publication_bundle_snapshot_price where snapshot_id in ("
                        + String.join(",", Collections.nCopies(snapshotIds.length, "?")) + ")",
                rs -> {
                    pricesBySnapshot.computeIfAbsent(rs.getLong(1), key -> new ArrayList<>())
                            .add(new SourcePrice(rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                                    rs.getString(5), rs.getString(6), rs.getBigDecimal(7)));
                }, snapshotIds);
        // Sorteren in Java (String.compareTo), niet met de collatie van de database: die verschilt tussen H2 en
        // PostgreSQL (zelfde keuze als de snapshot_hash).
        pricesBySnapshot.values().forEach(list -> list.sort((a, b) -> a.componentCode().compareTo(b.componentCode())));
        return base.stream().map(row -> new SourceRow(row.bundleId(), row.snapshotId(), row.mutationId(),
                row.actionType(), row.identitySupplier(), row.identitySupplierGroup(), row.identitySupplierReference(),
                row.identityDiscountCode(), row.identityDiscountState(), row.libraryCode(), row.beforeBasePrice(),
                row.afterBasePrice(), row.basePriceCurrency(), row.description(), row.descriptionState(),
                row.snapshotSpecVersion(), pricesBySnapshot.getOrDefault(row.snapshotId(), List.of()))).toList();
    }
}
