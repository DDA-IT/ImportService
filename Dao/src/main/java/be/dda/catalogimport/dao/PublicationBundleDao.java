package be.dda.catalogimport.dao;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Set-based tellingen over de mutaties van een {@code publication_bundle}, over al haar actieve
 * batchlidmaatschappen heen (ontwerp fase 4 par. 2 en 3, R-BND). Bouwstap 4b beperkt zich tot wat
 * batchbeheer en het leesmodel nodig hebben; {@code decideByFilter}, {@code approvePlanned},
 * {@code expireOpenMutations} en de bundelhash-stream volgen in latere bouwstappen (4c-4f).
 */
@Repository
public class PublicationBundleDao {

    /** Eén (actionType, status)-combinatie met haar aantal binnen de bundel. */
    public record MutationStatusCount(String actionType, String status, long count) {
    }

    private final JdbcTemplate jdbc;

    public PublicationBundleDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Tellers per {@code (action_type, status)} over alle mutaties van de <b>actieve</b> batches van
     * deze bundel. Basis van de live tellers in {@code BundleQueryService.getBundle} zolang de bundel
     * {@code ASSEMBLING} is.
     */
    public List<MutationStatusCount> countByStatus(long bundleId) {
        return jdbc.query("select m.action_type, m.status, count(*) as cnt "
                        + "from import_mutation m "
                        + "join publication_bundle_batch pbb "
                        + "  on pbb.batch_id = m.batch_id and pbb.bundle_id = ? and pbb.active_marker is not null "
                        + "group by m.action_type, m.status",
                (rs, rowNum) -> new MutationStatusCount(rs.getString(1), rs.getString(2), rs.getLong(3)),
                bundleId);
    }

    /**
     * De baselinecontrole (R-BND-06, ontwerp par. 3): het aantal nog niet afgehandelde inhoudelijke
     * mutaties van deze bundel wiens {@code before}-toestand niet meer overeenkomt met de huidige
     * {@code catalog_source_state}. Gebruikt uitsluitend kolommen op {@code import_mutation} zelf, niet
     * de kandidaatstaging (kortere retentie dan een openstaande bundel). In bouwstap 4b enkel
     * informatief (leesmodel); blokkerend pas bij bevriezen (4e).
     */
    public long countStaleMutations(long bundleId) {
        Long count = jdbc.queryForObject("select count(*) from import_mutation m "
                        + "join publication_bundle_batch pbb "
                        + "  on pbb.batch_id = m.batch_id and pbb.bundle_id = ? and pbb.active_marker is not null "
                        + "left join catalog_source_state s "
                        + "  on s.import_link_id = m.import_link_id and s.identity_hash = m.identity_hash "
                        + "where m.action_type in ('CREATE','UPDATE') "
                        + "  and m.status in ('PLANNED','AWAITING_APPROVAL','READY_FOR_PUBLICATION') "
                        + "  and ( (m.action_type = 'CREATE' and s.id is not null) "
                        + "     or (m.action_type = 'UPDATE' and (s.id is null "
                        + "                                    or s.combined_fingerprint <> m.before_combined_fingerprint)) )",
                Long.class, bundleId);
        return count == null ? 0L : count;
    }
}
