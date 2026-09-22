package be.dda.catalogimport.dao;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Set-based tellingen over en gerichte schrijfacties op de mutaties van een {@code publication_bundle},
 * over al haar actieve batchlidmaatschappen heen (ontwerp fase 4 par. 2 en 3, R-BND). Bouwstap 4b
 * leverde de tellingen, bouwstap 4c de individuele beslissing ({@link #decideMutation});
 * {@code decideByFilter}, {@code approvePlanned}, {@code expireOpenMutations} en de bundelhash-stream
 * volgen in latere bouwstappen (4d-4f).
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

    /**
     * Legt één individuele beslissing vast op één mutatie (ontwerp fase 4 par. 3,
     * "Goedkeuringsalgoritme (individueel)", R-DEC).
     * <p>
     * <b>Waarom hier en niet via JPA.</b> Deze statement zet <b>uitsluitend</b> de vijf kolommen die
     * een beslissing mag raken: {@code status}, {@code decided_by}, {@code decided_at},
     * {@code decided_from_status} en {@code decision_id}. Een {@code save()} van de volledige
     * {@code ImportMutation}-entiteit zou élke gemapte kolom meeschrijven — ook
     * {@code before_base_price}, {@code after_base_price}, {@code domain_mask} en
     * {@code status_reason}. Die waarden zouden dan uit een in-memory representatie komen in plaats van
     * uit de databank, en een beslissing mag een financieel veld nooit aanraken, ook niet met
     * "dezelfde" waarde (ontwerp par. 1, R-DEC, slotzin). De kolommen die hier niet in de
     * {@code set}-lijst staan, kunnen dus niet per ongeluk overschreven worden.
     * <p>
     * <b>{@code status_reason} blijft staan</b>: die draagt waaróm de mutatie wachtte
     * ({@code BULK_PRICE_INCIDENT}, {@code INITIAL_LOAD_REQUIRES_APPROVAL}, ...) en dus waarvoor
     * iemand tekende. De beslissing zelf en haar reden staan in {@code publication_decision}.
     * <p>
     * De {@code where}-staart bevat naast de id ook de <b>verwachte huidige status</b>: wijzigde die
     * tussen de controle en deze statement (ondanks het slot op de bundel), dan raakt deze statement 0
     * rijen en beslist de aanroeper wat er moet gebeuren — nooit een stille overschrijving.
     *
     * @param expectedStatus de status waarop de aanroeper zijn controles baseerde; wordt ook
     *                       {@code decided_from_status}
     * @return het aantal bijgewerkte rijen: 1 bij succes, 0 wanneer de mutatie intussen verschoof
     */
    public int decideMutation(long mutationId, String expectedStatus, String newStatus, String decidedBy,
                              Instant decidedAt, long decisionId) {
        return jdbc.update("update import_mutation "
                        + "set status = ?, decided_by = ?, decided_at = ?, decided_from_status = ?, "
                        + "    decision_id = ? "
                        + "where id = ? and status = ?",
                newStatus, decidedBy, OffsetDateTime.ofInstant(decidedAt, ZoneOffset.UTC), expectedStatus,
                decisionId, mutationId, expectedStatus);
    }
}
