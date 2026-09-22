package be.dda.catalogimport.dao;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Set-based tellingen over en gerichte schrijfacties op de mutaties van een {@code publication_bundle},
 * over al haar actieve batchlidmaatschappen heen (ontwerp fase 4 par. 2 en 3, R-BND). Bouwstap 4b
 * leverde de tellingen, bouwstap 4c de individuele beslissing ({@link #decideMutation}), bouwstap 4d de
 * groepsactie ({@link #countDecidable} + {@link #decideByFilter}); {@code approvePlanned},
 * {@code expireOpenMutations} en de bundelhash-stream volgen in 4e-4f.
 */
@Repository
public class PublicationBundleDao {

    /**
     * De <b>harde, niet-onderhandelbare</b> {@code where}-staart van elke groepsactie (ontwerp fase 4
     * par. 3 "Groepsactie"). Ze staat hier als één constante zodat de telling en de {@code update}
     * onmogelijk uit elkaar kunnen lopen, en zodat er maar één plaats is waar ze ooit gelezen moet
     * worden:
     * <ul>
     *   <li>{@code action_type in ('CREATE','UPDATE')} — een groepsactie raakt nooit de
     *       {@code IMPORT_MARKER} en nooit een {@code IDENTITY_REFERENCE_INCIDENT}: dat laatste vraagt
     *       een identiteitsbeslissing die in {@code catalog_reference_state} zou moeten schrijven, wat
     *       Fase 4 niet mag (ontwerp par. 3.6, R-BND-08).</li>
     *   <li>{@code status in ('PLANNED','AWAITING_APPROVAL')} — een groepsactie raakt nooit een
     *       {@code BLOCKED} mutatie (vastgehouden kritiek referentie-incident) en geen enkele terminale
     *       of lopende status.</li>
     *   <li>{@code decision_id is null} — een groepsactie raakt nooit een mutatie die al een beslissing
     *       draagt. Een herziening van een ondertekende beslissing blijft exclusief het individuele pad
     *       ({@link #decideMutation}): wie omkeert wat iemand al tekende, doet dat per stuk en met een
     *       verplichte reden, nooit en masse.</li>
     * </ul>
     * De optionele filtervelden uit {@link MutationSelection} kunnen deze staart alleen <b>verder
     * versmallen</b>, nooit verbreden.
     */
    private static final String GROUP_DECISION_TAIL = " %1$saction_type in ('CREATE','UPDATE') "
            + "  and %1$sstatus in ('PLANNED','AWAITING_APPROVAL') "
            + "  and %1$sdecision_id is null ";

    /** Eén (actionType, status)-combinatie met haar aantal binnen de bundel. */
    public record MutationStatusCount(String actionType, String status, long count) {
    }

    /**
     * De optionele versmalling van een groepsactie binnen één bundel (ontwerp fase 4 par. 3
     * "Groepsactie"). Elk veld is optioneel en wordt met {@code and} toegevoegd aan
     * {@link #GROUP_DECISION_TAIL}; {@code null} betekent "niet filteren op dit veld".
     * <p>
     * Tekstwaarden, geen enums: dit is de databasegrens. De Service-laag valideert en vertaalt
     * ({@code status} enkel {@code PLANNED}/{@code AWAITING_APPROVAL}, {@code actionType} enkel
     * {@code CREATE}/{@code UPDATE}) en weigert een volledig lege selectie vóór de query ooit draait.
     * Alle vier de waarden gaan als bindparameter mee, nooit als tekst in de statement.
     *
     * @param statusReason exacte vergelijking (bv. {@code BULK_PRICE_INCIDENT}), geen {@code like}: een
     *                     groepsactie mag nooit méér raken dan wat de aanvrager letterlijk aanduidde
     */
    public record MutationSelection(Long batchId, String status, String statusReason, String actionType) {

        /** {@code true} zodra geen enkel veld ingevuld is — dan raakt de actie de hele bundel. */
        public boolean isEmpty() {
            return batchId == null && status == null && statusReason == null && actionType == null;
        }
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

    /**
     * Het aantal mutaties dat een groepsactie met deze selectie <b>zou</b> raken: exact dezelfde
     * {@code where}-clausule als {@link #decideByFilter}, inclusief {@link #GROUP_DECISION_TAIL} en het
     * actieve batchlidmaatschap.
     * <p>
     * De aanroeper telt eerst en schrijft dan pas (ontwerp fase 4 par. 3): met dit aantal is de
     * {@code publication_decision}-regel meteen met haar definitieve {@code affected_count} in te
     * voegen, en hoeft een <b>append-only</b> regel nooit achteraf bijgewerkt te worden. Is het aantal
     * 0, dan wordt er helemaal geen regel geschreven — een beslissing die niets raakte, is geen
     * beslissing. Beide statements lopen in dezelfde transactie, achter hetzelfde slot op de bundel.
     */
    public long countDecidable(long bundleId, MutationSelection selection) {
        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder("select count(*) from import_mutation m "
                + "join publication_bundle_batch pbb "
                + "  on pbb.batch_id = m.batch_id and pbb.bundle_id = ? and pbb.active_marker is not null "
                + "where");
        parameters.add(bundleId);
        sql.append(GROUP_DECISION_TAIL.formatted("m."));
        appendSelection(sql, parameters, "m.", selection);
        Long count = jdbc.queryForObject(sql.toString(), Long.class, parameters.toArray());
        return count == null ? 0L : count;
    }

    /**
     * Past één groepsbeslissing toe op alle mutaties van deze bundel die aan {@code selection} voldoen
     * (ontwerp fase 4 par. 1 R-DEC, par. 3 "Groepsactie"), in <b>één</b> {@code update} — geen lus met
     * een statement per mutatie.
     * <p>
     * <b>Elke geraakte mutatie draagt haar eigen audit.</b> R-DEC eist dat ook bij een groepsactie, dus
     * {@code decided_by}, {@code decided_at}, {@code decided_from_status} en {@code decision_id} worden
     * per rij gezet. Voor {@code decided_from_status} is dat
     * {@code decided_from_status = status}: de {@code set}-lijst van een {@code update} leest per rij de
     * <b>oude</b> kolomwaarden (SQL-standaard, zowel H2 als PostgreSQL), dus dit legt per rij de status
     * vast zoals ze vlak vóór deze statement was. Daarom kunnen {@code PLANNED} en
     * {@code AWAITING_APPROVAL} in dezelfde aanroep mee en houdt elke rij toch haar eigen, juiste
     * herkomst. De toekenning staat bewust als <b>eerste</b> in de {@code set}-lijst, vóór
     * {@code status = ?}: ook onder een (niet-standaard) sequentiële evaluatie leest ze dan nog de oude
     * waarde. Een {@code case}-constructie zou hetzelfde doen maar zou de twee toegestane bronstatussen
     * een tweede keer vastleggen — en dus stilzwijgend fout worden zodra de staart ooit verruimt.
     * <p>
     * <b>Financiële onveranderlijkheid</b> (ontwerp par. 1 R-DEC, slotzin): de {@code set}-lijst bevat
     * exact vijf kolommen. {@code before_base_price}, {@code after_base_price}, {@code domain_mask},
     * {@code status_reason}, {@code identity_hash} en de vingerafdrukken staan er niet in en kunnen dus
     * niet meeschrijven — precies dezelfde garantie als bij {@link #decideMutation}, nu voor de
     * groepsroute. {@code status_reason} blijft staan: die draagt waaróm de mutatie wachtte en dus
     * waarvoor iemand tekende.
     *
     * @param decisionId de <b>ene</b> {@code publication_decision}-regel waarnaar alle geraakte rijen
     *                   wijzen
     * @return het werkelijke aantal geraakte rijen; de aanroeper vergelijkt dit met
     *         {@link #countDecidable} en rolt terug wanneer ze verschillen
     */
    public int decideByFilter(long bundleId, String newStatus, String decidedBy, Instant decidedAt,
                              long decisionId, MutationSelection selection) {
        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder("update import_mutation "
                + "set decided_from_status = status, "
                + "    status = ?, "
                + "    decided_by = ?, "
                + "    decided_at = ?, "
                + "    decision_id = ? "
                + "where");
        parameters.add(newStatus);
        parameters.add(decidedBy);
        parameters.add(OffsetDateTime.ofInstant(decidedAt, ZoneOffset.UTC));
        parameters.add(decisionId);
        sql.append(GROUP_DECISION_TAIL.formatted(""));
        // Een gecorreleerde exists in plaats van een join: een update met join is niet draagbaar tussen
        // H2 en PostgreSQL. De voorwaarde is dezelfde als bij de individuele beslissing - alleen
        // mutaties van een batch met een ACTIEF lidmaatschap van déze bundel.
        sql.append("  and exists (select 1 from publication_bundle_batch pbb "
                + "              where pbb.batch_id = import_mutation.batch_id and pbb.bundle_id = ? "
                + "                and pbb.active_marker is not null) ");
        parameters.add(bundleId);
        appendSelection(sql, parameters, "", selection);
        return jdbc.update(sql.toString(), parameters.toArray());
    }

    /**
     * Voegt de optionele versmalling toe, altijd met {@code and} en altijd als bindparameter. Zowel de
     * telling als de {@code update} lopen hierlangs, zodat ze onmogelijk een verschillende selectie
     * kunnen beschrijven.
     *
     * @param prefix {@code "m."} voor de getelde variant (die een alias heeft), leeg voor de
     *               {@code update} (die er geen kan hebben)
     */
    private static void appendSelection(StringBuilder sql, List<Object> parameters, String prefix,
                                        MutationSelection selection) {
        if (selection == null) {
            return;
        }
        if (selection.batchId() != null) {
            sql.append("  and ").append(prefix).append("batch_id = ? ");
            parameters.add(selection.batchId());
        }
        if (selection.status() != null) {
            sql.append("  and ").append(prefix).append("status = ? ");
            parameters.add(selection.status());
        }
        if (selection.statusReason() != null) {
            sql.append("  and ").append(prefix).append("status_reason = ? ");
            parameters.add(selection.statusReason());
        }
        if (selection.actionType() != null) {
            sql.append("  and ").append(prefix).append("action_type = ? ");
            parameters.add(selection.actionType());
        }
    }
}
