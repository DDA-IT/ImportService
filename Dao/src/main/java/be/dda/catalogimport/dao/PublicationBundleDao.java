package be.dda.catalogimport.dao;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Set-based tellingen over en gerichte schrijfacties op de mutaties van een {@code publication_bundle},
 * over al haar actieve batchlidmaatschappen heen (ontwerp fase 4 par. 2 en 3, R-BND). Bouwstap 4b
 * leverde de tellingen, bouwstap 4c de individuele beslissing ({@link #decideMutation}), bouwstap 4d de
 * groepsactie ({@link #countDecidable} + {@link #decideByFilter}), bouwstap 4e het bevriezen
 * ({@link #countUndecided}, {@link #findInBundleOfferConflicts}, {@link #findCrossBundleOfferConflicts},
 * {@link #approvePlanned}, {@link #computeBatchTotals}, {@link #computeContentHash}), bouwstap 4f het
 * annuleren ({@link #countExpirableMutations}, {@link #expireOpenMutations}).
 */
@Repository
public class PublicationBundleDao {

    /**
     * De statussen waarin een inhoudelijke mutatie nog naar Prodis kán gaan. Basis van de
     * baselinecontrole (R-BND-06) en van beide conflictregels (R-FRZ-03/04): enkel wie nog
     * publiceerbaar is, kan met een ander voorstel op dezelfde aanbieding botsen. {@code REJECTED},
     * {@code EXPIRED}, {@code SKIPPED} en {@code BLOCKED} gaan nooit naar Prodis en botsen dus nooit.
     */
    private static final String PUBLISHABLE_STATUSES = "('PLANNED','AWAITING_APPROVAL','READY_FOR_PUBLICATION')";

    /**
     * De selectie van de bulkgoedkeuring bij bevriezen (R-FRZ, beslissingslog 22/09 keuze 1): alle
     * resterende {@code PLANNED}-mutaties. De harde staart van {@link #GROUP_DECISION_TAIL} doet de
     * rest — {@code action_type in ('CREATE','UPDATE')} en {@code decision_id is null} — zodat
     * {@link #approvePlanned} exact hetzelfde codepad en dezelfde garanties heeft als de groepsactie
     * uit 4d.
     */
    private static final MutationSelection PLANNED_ONLY = new MutationSelection(null, "PLANNED", null, null, null);

    /** De doelstatus van de bulkgoedkeuring bij bevriezen ({@code MutationStatus.READY_FOR_PUBLICATION}). */
    private static final String READY_FOR_PUBLICATION = "READY_FOR_PUBLICATION";

    /** Vaste tekst voor een ontbrekende waarde; zie de serialisatie in {@link #computeContentHash}. */
    private static final String HASH_NULL = "null";
    /** Scheidt twee velden binnen één mutatie in de bundelhash (unit separator). */
    private static final char HASH_FIELD_SEPARATOR = '';
    /** Sluit één mutatie af in de bundelhash (record separator). */
    private static final char HASH_RECORD_SEPARATOR = '';

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
     * Alle waarden gaan als bindparameter mee, nooit als tekst in de statement.
     *
     * @param statusReason exacte vergelijking (bv. {@code BULK_PRICE_INCIDENT}), geen {@code like}: een
     *                     groepsactie mag nooit méér raken dan wat de aanvrager letterlijk aanduidde
     * @param identityHash de wijzigingsgroep van één aanbieding (bouwstap C5), {@code null} = niet
     *                     filteren op de hash; zie {@link IdentityHashFilter}
     */
    public record MutationSelection(Long batchId, String status, String statusReason, String actionType,
                                    IdentityHashFilter identityHash) {

        /** {@code true} zodra geen enkel veld ingevuld is — dan raakt de actie de hele bundel. */
        public boolean isEmpty() {
            return batchId == null && status == null && statusReason == null && actionType == null
                    && identityHash == null;
        }
    }

    /**
     * Het {@code identity_hash}-filter van een groepsactie (bouwstap C5): de bytes waarmee vergeleken
     * wordt, of de vaststelling dat er wél op een hash gefilterd is maar dat die hash onmogelijk kan
     * bestaan.
     * <p>
     * <b>Waarom een eigen type.</b> Er zijn drie toestanden, niet twee: "niet filteren op de hash"
     * ({@code null} in {@link MutationSelection}), "filteren op déze bytes", en "de aanvrager filterde op
     * een hash die geen enkele rij kan dragen" ({@link #UNMATCHABLE}, bv. ongeldige hex). Zou dat derde
     * geval met het eerste versmelten, dan zou een vergissing in de hash de groepsactie stilzwijgend over
     * de <b>hele</b> bundel laten lopen — precies het soort onomkeerbare vergissing dat
     * {@code DECISION_FILTER_REQUIRED} elders al verhindert. {@link #UNMATCHABLE} levert daarom een
     * selectie op die aantoonbaar 0 rijen raakt.
     * <p>
     * De vergelijking gebeurt op de binaire waarde zelf, zonder {@code encode(...,'hex')} — dezelfde
     * keuze en dezelfde reden als bij het lijstfilter van bouwstap C4
     * ({@code ImportMutationRepository.IDENTITY_HASH_FILTER}): draagbaar tussen PostgreSQL en H2, en de
     * indexen op {@code identity_hash} blijven bruikbaar.
     *
     * @param hash de 32 bytes van de SHA-256-identiteitshash; {@code null} betekent {@link #UNMATCHABLE}
     */
    public record IdentityHashFilter(byte[] hash) {

        /** Er is op een hash gefilterd die geen enkele rij kan dragen; de selectie is dus leeg. */
        public static final IdentityHashFilter UNMATCHABLE = new IdentityHashFilter(null);

        /** {@code true} wanneer deze filter per definitie 0 rijen kan opleveren. */
        public boolean matchesNothing() {
            return hash == null;
        }
    }

    /**
     * De leesbare aanbiedingsidentiteit achter een conflict. De conflictregels groeperen op
     * {@code identity_hash} (binair, en niet leesbaar in een foutmelding); deze drie velden zijn de
     * bronwaarden waaruit die hash berekend is en dus per definitie gelijk binnen één groep.
     */
    public record OfferIdentity(long importLinkId, String supplier, String supplierGroup,
                                String supplierReference) {
    }

    /**
     * Eén aanbieding die binnen <b>dezelfde</b> bundel uit meerdere batches publiceerbaar openstaat
     * (R-FRZ-03). {@code firstBatchId}/{@code lastBatchId} zijn de laagste en de hoogste betrokken
     * batch — genoeg om het conflict te kunnen aanwijzen, zonder een niet-draagbare
     * stringaggregatie ({@code listagg}/{@code string_agg} verschillen tussen H2 en PostgreSQL).
     */
    public record InBundleOfferConflict(OfferIdentity offer, long batchCount, long firstBatchId,
                                        long lastBatchId) {
    }

    /**
     * Eén aanbieding van deze bundel die ook in een <b>andere</b>, niet-geannuleerde bundel
     * publiceerbaar openstaat (R-FRZ-04).
     */
    public record CrossBundleOfferConflict(OfferIdentity offer, long batchId, long otherBundleId,
                                           long otherBatchId) {
    }

    /**
     * De drie tellers van {@code publication_bundle} die niet uit {@code import_mutation} komen maar
     * uit de screeningtellers van de leden-batches ({@code import_batch}, changesets 004-10b/004-11b).
     * <p>
     * <b>{@code null} betekent "niet vastgesteld", nooit stil 0.</b> Draagt ook maar één lid-batch
     * geen waarde voor een teller (bv. een screening die die controle niet uitvoerde), dan is de som
     * over de bundel onbekend en blijft de teller leeg. Een som die stilzwijgend de ontbrekende
     * batches als 0 meerekent, zou een bundel rustiger doen lijken dan ze is.
     */
    public record BundleBatchTotals(Long bulkIncidentCount, Long criticalIssueCount, Long warningCount) {
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

    // --- Bouwstap 4e: bevriezen ------------------------------------------------------------------

    /**
     * Het aantal inhoudelijke mutaties van deze bundel dat nog <b>expliciet</b> beoordeeld moet worden
     * (R-FRZ-02): {@code CREATE}/{@code UPDATE} in {@code AWAITING_APPROVAL}.
     * <p>
     * Bewust niet {@code PLANNED}: die worden bij het bevriezen zelf in bulk goedgekeurd op naam van de
     * bevriezer (beslissingslog 22/09, keuze 1, {@link #approvePlanned}). {@code AWAITING_APPROVAL}
     * daarentegen betekent dat een regel, drempel of incident om een mens gevraagd heeft — die vraag
     * mag een bevriezing nooit stilzwijgend beantwoorden. Bewust ook niet {@code BLOCKED} of een
     * {@code IDENTITY_REFERENCE_INCIDENT}: die krijgen in Fase 4 geen beslispad en beletten het
     * bevriezen dus niet (ontwerp par. 3.6, beslissingslog 22/09 keuze 3).
     */
    public long countUndecided(long bundleId) {
        Long count = jdbc.queryForObject("select count(*) from import_mutation m "
                        + "join publication_bundle_batch pbb "
                        + "  on pbb.batch_id = m.batch_id and pbb.bundle_id = ? and pbb.active_marker is not null "
                        + "where m.action_type in ('CREATE','UPDATE') and m.status = 'AWAITING_APPROVAL'",
                Long.class, bundleId);
        return count == null ? 0L : count;
    }

    /**
     * De conflictregel <b>binnen</b> één bundel (R-FRZ-03, ontwerp par. 3.8): twee publiceerbare
     * mutaties op dezelfde {@code (import_link_id, identity_hash)} uit <b>verschillende</b> batches.
     * <p>
     * Zonder deze regel zou "de laatste import wint" ontstaan: twee opeenvolgende leveringen van
     * dezelfde bron die dezelfde aanbieding wijzigen, allebei goedgekeurd, allebei in dezelfde bundel —
     * Prodis zou er dan willekeurig één als laatste verwerken en de andere wijziging zou spoorloos
     * verdwijnen. Dat is expliciet verboden (businessanalyse r.1412). Bevriezen wordt daarom geweigerd
     * tot één van beide kanten afgekeurd is.
     * <p>
     * De sleutel is {@code (import_link_id, identity_hash)} en nooit {@code identity_hash} alleen: twee
     * koppelingen zijn twee verschillende leveranciersbibliotheken, en dezelfde leverancierssleutel bij
     * twee koppelingen is twee verschillende aanbiedingen die elkaar niet in de weg zitten.
     *
     * @param limit hoogstens zoveel voorbeelden; een bundel met duizenden conflicten moet een leesbare
     *              foutmelding opleveren, geen volledige dump
     */
    public List<InBundleOfferConflict> findInBundleOfferConflicts(long bundleId, int limit) {
        return jdbc.query("select m.import_link_id, min(m.identity_supplier), min(m.identity_supplier_group), "
                        + "       min(m.identity_supplier_reference), count(distinct m.batch_id), "
                        + "       min(m.batch_id), max(m.batch_id) "
                        + "from import_mutation m "
                        + "join publication_bundle_batch pbb "
                        + "  on pbb.batch_id = m.batch_id and pbb.bundle_id = ? and pbb.active_marker is not null "
                        + "where m.action_type in ('CREATE','UPDATE') "
                        + "  and m.status in " + PUBLISHABLE_STATUSES + " "
                        + "group by m.import_link_id, m.identity_hash "
                        + "having count(distinct m.batch_id) > 1 "
                        + "order by m.import_link_id, min(m.batch_id) "
                        + "limit ?",
                (rs, rowNum) -> new InBundleOfferConflict(
                        new OfferIdentity(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                        rs.getLong(5), rs.getLong(6), rs.getLong(7)),
                bundleId, limit);
    }

    /**
     * De conflictregel <b>tussen</b> bundels (R-FRZ-04): dezelfde
     * {@code (import_link_id, identity_hash)} staat ook publiceerbaar open in een andere bundel die nog
     * niet {@code CANCELLED} is.
     * <p>
     * Twee bevroren bundels die dezelfde aanbieding dragen, zouden in Fase 5 in onbepaalde volgorde
     * gepubliceerd worden — exact hetzelfde "laatste import wint" als binnen één bundel, alleen
     * verspreid over twee goedkeuringsdossiers. Een {@code CANCELLED}-bundel telt niet mee: haar
     * mutaties worden bij het annuleren {@code EXPIRED} (4f) en haar batches komen weer vrij. De
     * statuscontrole staat er naast het actieve lidmaatschap als tweede zekering: een bundel die haar
     * leden zou behouden maar niets meer publiceert, mag deze bevriezing niet blokkeren.
     *
     * @param limit hoogstens zoveel voorbeelden, zie {@link #findInBundleOfferConflicts}
     */
    public List<CrossBundleOfferConflict> findCrossBundleOfferConflicts(long bundleId, int limit) {
        return jdbc.query("select distinct m.import_link_id, m.identity_supplier, m.identity_supplier_group, "
                        + "       m.identity_supplier_reference, m.batch_id, other.bundle_id, other.batch_id "
                        + "from import_mutation m "
                        + "join publication_bundle_batch pbb "
                        + "  on pbb.batch_id = m.batch_id and pbb.bundle_id = ? and pbb.active_marker is not null "
                        + "join import_mutation om "
                        + "  on om.import_link_id = m.import_link_id and om.identity_hash = m.identity_hash "
                        + " and om.batch_id <> m.batch_id "
                        + " and om.action_type in ('CREATE','UPDATE') "
                        + " and om.status in " + PUBLISHABLE_STATUSES + " "
                        + "join publication_bundle_batch other "
                        + "  on other.batch_id = om.batch_id and other.active_marker is not null "
                        + " and other.bundle_id <> ? "
                        + "join publication_bundle b on b.id = other.bundle_id and b.status <> 'CANCELLED' "
                        + "where m.action_type in ('CREATE','UPDATE') "
                        + "  and m.status in " + PUBLISHABLE_STATUSES + " "
                        + "order by m.import_link_id, m.batch_id, other.bundle_id "
                        + "limit ?",
                (rs, rowNum) -> new CrossBundleOfferConflict(
                        new OfferIdentity(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                        rs.getLong(5), rs.getLong(6), rs.getLong(7)),
                bundleId, bundleId, limit);
    }

    /**
     * Het aantal {@code PLANNED}-mutaties dat {@link #approvePlanned} zou raken — exact dezelfde
     * {@code where}-clausule, want beide gaan via {@link #PLANNED_ONLY} en
     * {@link #countDecidable}/{@link #decideByFilter}. De aanroeper telt eerst en schrijft dan pas,
     * zodat de append-only {@code publication_decision}-regel meteen met haar definitieve
     * {@code affected_count} ingevoegd kan worden (zelfde volgorde als de groepsactie in 4d).
     */
    public long countPlanned(long bundleId) {
        return countDecidable(bundleId, PLANNED_ONLY);
    }

    /**
     * Keurt bij het bevriezen alle resterende {@code PLANNED}-mutaties van deze bundel in bulk goed
     * ({@code READY_FOR_PUBLICATION}) op naam van de bevriezer — beslissingslog 22/09, keuze 1: een
     * geplande, door geen enkele regel tegengehouden mutatie hoeft niet één voor één aangeklikt te
     * worden, maar krijgt wél haar eigen, volledige audit.
     * <p>
     * Bewust <b>geen</b> eigen {@code update}: dit is letterlijk de groepsactie uit 4d met de vaste
     * selectie {@link #PLANNED_ONLY}, en erft daarmee ongewijzigd al haar garanties — de harde staart
     * ({@code action_type in ('CREATE','UPDATE')}, {@code decision_id is null}), het actieve
     * batchlidmaatschap, de per rij vastgelegde {@code decided_from_status}, en de {@code set}-lijst van
     * exact vijf kolommen die geen enkel financieel veld kan raken. Een tweede, eigen statement zou die
     * garanties moeten kopiëren en zou dus stilzwijgend uit elkaar kunnen lopen.
     *
     * @param decisionId de ene {@code AUTO_APPROVE_PLANNED}-regel waarnaar alle geraakte rijen wijzen
     * @return het werkelijke aantal geraakte rijen; de aanroeper vergelijkt dit met
     *         {@link #countPlanned} en rolt de hele bevriezing terug wanneer ze verschillen
     */
    public int approvePlanned(long bundleId, long decisionId, String frozenBy, Instant at) {
        return decideByFilter(bundleId, READY_FOR_PUBLICATION, frozenBy, at, decisionId, PLANNED_ONLY);
    }

    /**
     * De drie bundeltellers die uit de screeningtellers van de leden-batches komen
     * ({@code bulk_incident_count}, {@code critical_issue_count}, {@code warning_count}), gesommeerd
     * over de <b>actieve</b> lidmaatschappen.
     * <p>
     * Per teller wordt naast de som ook geteld hoeveel batches er werkelijk een waarde voor dragen.
     * Ligt dat lager dan het aantal leden, dan is de som over de bundel niet vastgesteld en komt er
     * {@code null} uit in plaats van een te lage som (zie {@link BundleBatchTotals}).
     */
    public BundleBatchTotals computeBatchTotals(long bundleId) {
        return jdbc.queryForObject("select count(*), "
                        + "  count(b.bulk_incident_count), sum(b.bulk_incident_count), "
                        + "  count(b.critical_issue_count), sum(b.critical_issue_count), "
                        + "  count(b.warning_count), sum(b.warning_count) "
                        + "from publication_bundle_batch pbb "
                        + "join import_batch b on b.id = pbb.batch_id "
                        + "where pbb.bundle_id = ? and pbb.active_marker is not null",
                (rs, rowNum) -> {
                    long members = rs.getLong(1);
                    return new BundleBatchTotals(total(rs, members, 2, 3), total(rs, members, 4, 5),
                            total(rs, members, 6, 7));
                }, bundleId);
    }

    /** {@code null} zodra niet élke lid-batch een waarde voor deze teller draagt; anders de som. */
    private static Long total(ResultSet rs, long members, int knownColumn, int sumColumn) throws SQLException {
        long known = rs.getLong(knownColumn);
        if (members == 0 || known < members) {
            return null;
        }
        long sum = rs.getLong(sumColumn);
        return rs.wasNull() ? null : sum;
    }

    /**
     * De volledige bundelhash (R-FRZ): één SHA-256 over <b>alle</b> mutaties van alle actieve
     * lidmaatschappen van deze bundel, op het moment van bevriezen. Ze wordt in
     * {@code publication_bundle.content_hash} bewaard en maakt in Fase 5 aantoonbaar dat wat
     * gepubliceerd wordt exact is wat iemand bevroren heeft.
     *
     * <h2>Welke mutaties</h2>
     * Alle mutaties van de batches met een <b>actief</b> lidmaatschap — dus ook de
     * {@code IMPORT_MARKER}, de {@code BLOCKED}-mutaties en de identiteitsincidenten, die Fase 5 niet
     * publiceert maar die wel deel zijn van wat er bevroren werd. Batches waarvan het lidmaatschap
     * eerder verwijderd is, tellen <b>niet</b> mee: ze zijn geen lid meer, hun mutaties gaan niet naar
     * Prodis, en ze kunnen intussen in een andere bundel zitten. Zou de hash ze toch meenemen, dan zou
     * hij niet meer beschrijven wát er bevroren is maar hóe men daar geraakt is.
     *
     * <h2>Serialisatie (deterministisch, vastgelegd)</h2>
     * Per mutatie, in exact deze volgorde, gescheiden door {@code U+001F} (unit separator) en per
     * mutatie afgesloten met {@code U+001E} (record separator):
     * <pre>batch_id ␟ id ␟ action_type ␟ status ␟ decision_id ␟ identity_hash(hex) ␟ before_base_price ␟ after_base_price ␞</pre>
     * <ul>
     *   <li>Ordening op {@code m.id} oplopend, <b>niet</b> op {@code idempotency_key} (ontwerp par. 9
     *       A28): tekstsortering verschilt tussen H2 en PostgreSQL door collatie, en dan zou dezelfde
     *       inhoud per database een andere hash geven.</li>
     *   <li>{@code null} wordt de vaste tekst {@value #HASH_NULL} — onmogelijk te verwarren met een
     *       bestaande waarde (een id is numeriek, een status is hoofdletters met liggend streepje, een
     *       hash is kleine hex), en onderscheidbaar van de lege tekst.</li>
     *   <li>Prijzen als {@code stripTrailingZeros().toPlainString()}: numeriek identieke bedragen
     *       leveren zo altijd dezelfde tekst, ook als het JDBC-stuurprogramma de schaal anders
     *       teruggeeft. De opgeslagen waarde wordt nooit aangeraakt — dit is enkel de
     *       tekstvoorstelling voor de digest.</li>
     *   <li>De scheidingstekens zijn stuurtekens die in geen enkele van deze velden kunnen voorkomen
     *       (id/decision_id numeriek, status/action_type enums, identity_hash hex, prijzen decimaal),
     *       dus twee verschillende reeksen kunnen nooit dezelfde bytes opleveren.</li>
     * </ul>
     * De mutatie-id maakt deel uit van de hash. Twee bundels met inhoudelijk identieke leveringen
     * krijgen daardoor een verschillende hash: deze hash identificeert <b>deze</b> rijen, niet een
     * abstracte inhoudsgelijkheid. Dat is de bedoeling — Fase 5 moet kunnen vaststellen dat exact deze
     * mutaties nog onveranderd zijn, niet dat er ergens gelijkaardige bestaan.
     */
    public byte[] computeContentHash(long bundleId) {
        MessageDigest digest = newSha256();
        HexFormat hex = HexFormat.of();
        jdbc.query("select m.batch_id, m.id, m.action_type, m.status, m.decision_id, m.identity_hash, "
                        + "       m.before_base_price, m.after_base_price "
                        + "from import_mutation m "
                        + "join publication_bundle_batch pbb "
                        + "  on pbb.batch_id = m.batch_id and pbb.bundle_id = ? and pbb.active_marker is not null "
                        + "order by m.id",
                rs -> {
                    StringBuilder line = new StringBuilder(160);
                    append(line, Long.toString(rs.getLong(1)));
                    append(line, Long.toString(rs.getLong(2)));
                    append(line, rs.getString(3));
                    append(line, rs.getString(4));
                    long decisionId = rs.getLong(5);
                    append(line, rs.wasNull() ? null : Long.toString(decisionId));
                    byte[] identityHash = rs.getBytes(6);
                    append(line, identityHash == null ? null : hex.formatHex(identityHash));
                    append(line, money(rs.getBigDecimal(7)));
                    append(line, money(rs.getBigDecimal(8)));
                    line.append(HASH_RECORD_SEPARATOR);
                    digest.update(line.toString().getBytes(StandardCharsets.UTF_8));
                }, bundleId);
        return digest.digest();
    }

    private static void append(StringBuilder line, String value) {
        line.append(value == null ? HASH_NULL : value).append(HASH_FIELD_SEPARATOR);
    }

    /**
     * Numeriek identieke bedragen krijgen altijd dezelfde tekst, onafhankelijk van de schaal die het
     * stuurprogramma teruggeeft. {@code toPlainString} en niet {@code toString}: die laatste kan een
     * exponent produceren en zou dezelfde waarde twee vormen geven.
     */
    private static String money(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 is verplicht in elke Java-implementatie; hier komen betekent een kapotte JVM.
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * Voegt de optionele versmalling toe, altijd met {@code and} en altijd als bindparameter. Zowel de
     * telling als de {@code update} lopen hierlangs, zodat ze onmogelijk een verschillende selectie
     * kunnen beschrijven — ook niet voor een {@link IdentityHashFilter} die niets kan raken.
     *
     * @param prefix {@code "m."} voor de getelde variant (die een alias heeft), leeg voor de
     *               {@code update} (die er geen kan hebben)
     */
    // --- Bouwstap 4f: annuleren -------------------------------------------------------------------

    /**
     * De harde {@code where}-staart van het annuleren (R-FRZ-10). In tegenstelling tot
     * {@link #GROUP_DECISION_TAIL} telt {@code READY_FOR_PUBLICATION} hier <b>wél</b> mee: een al
     * goedgekeurde maar nog niet gepubliceerde mutatie is bij annulering evengoed nog niets waard
     * geworden, en er is ook geen {@code decision_id is null}-voorwaarde — een eerder goedgekeurde
     * mutatie draagt al een beslissing en moet toch vervallen. {@code REJECTED}, {@code SKIPPED},
     * {@code RECORDED}, {@code BLOCKED} en elke {@code IDENTITY_REFERENCE_INCIDENT}-mutatie (elke
     * andere status/actionType dan hier vermeld) blijven ongemoeid: "REJECTED blijft REJECTED, BLOCKED
     * blijft BLOCKED, marker blijft RECORDED" (R-FRZ-10, ontwerp par. 6 stap 4f).
     */
    private static final String EXPIRABLE_TAIL = " %1$saction_type in ('CREATE','UPDATE') "
            + "  and %1$sstatus in ('PLANNED','AWAITING_APPROVAL','READY_FOR_PUBLICATION') ";

    /**
     * Het aantal mutaties dat {@link #expireOpenMutations} zou raken — exact dezelfde
     * {@code where}-clausule. De aanroeper telt eerst en schrijft dan pas de
     * {@code publication_decision}-regel met haar definitieve {@code affected_count} (zelfde volgorde
     * als bevriezen en de groepsactie).
     */
    public long countExpirableMutations(long bundleId) {
        String sql = "select count(*) from import_mutation m "
                + "join publication_bundle_batch pbb "
                + "  on pbb.batch_id = m.batch_id and pbb.bundle_id = ? and pbb.active_marker is not null "
                + "where" + EXPIRABLE_TAIL.formatted("m.");
        Long count = jdbc.queryForObject(sql, Long.class, bundleId);
        return count == null ? 0L : count;
    }

    /**
     * Zet bij het annuleren van een bundel (R-FRZ-10, ontwerp par. 6 stap 4f) alle nog niet-terminale
     * inhoudelijke mutaties van haar actieve batches op {@code EXPIRED}, met haar eigen
     * {@code decided_by}/{@code decided_at}/{@code decided_from_status} en een verwijzing naar de
     * {@code CANCEL}-beslissingsregel. Zelfde {@code set}-lijst van vijf kolommen als
     * {@link #decideByFilter} — geen enkel financieel veld kan meeschrijven — en dezelfde
     * gecorreleerde-{@code exists} in plaats van een {@code update ... join} (niet draagbaar tussen H2
     * en PostgreSQL).
     *
     * @param decisionId de ene {@code CANCEL}-regel waarnaar alle geraakte rijen wijzen
     * @return het werkelijke aantal geraakte rijen; de aanroeper vergelijkt dit met
     *         {@link #countExpirableMutations} en rolt de hele annulering terug wanneer ze verschillen
     */
    public int expireOpenMutations(long bundleId, long decisionId, String cancelledBy, Instant at) {
        String sql = "update import_mutation "
                + "set decided_from_status = status, "
                + "    status = 'EXPIRED', "
                + "    decided_by = ?, "
                + "    decided_at = ?, "
                + "    decision_id = ? "
                + "where" + EXPIRABLE_TAIL.formatted("")
                + "  and exists (select 1 from publication_bundle_batch pbb "
                + "              where pbb.batch_id = import_mutation.batch_id and pbb.bundle_id = ? "
                + "                and pbb.active_marker is not null) ";
        return jdbc.update(sql, cancelledBy, OffsetDateTime.ofInstant(at, ZoneOffset.UTC), decisionId, bundleId);
    }

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
        IdentityHashFilter identityHash = selection.identityHash();
        if (identityHash != null) {
            if (identityHash.matchesNothing()) {
                // Er is op een hash gefilterd die niet kan bestaan (bouwstap C5). Géén voorwaarde
                // weglaten: dat zou de selectie verbreden in plaats van versmallen. "1 = 0" maakt zowel
                // de telling als de update aantoonbaar leeg, langs exact hetzelfde codepad.
                sql.append("  and 1 = 0 ");
            } else {
                sql.append("  and ").append(prefix).append("identity_hash = ? ");
                parameters.add(identityHash.hash());
            }
        }
    }
}
