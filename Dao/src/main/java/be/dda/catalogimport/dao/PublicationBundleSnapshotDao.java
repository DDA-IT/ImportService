package be.dda.catalogimport.dao;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Het <b>vullen</b> van de bundelsnapshot bij het bevriezen (ontwerp fase 5-PUB par. 1, bouwstap 5P-2):
 * set-based {@code insert ... select} uit {@code import_candidate_stage} en
 * {@code import_candidate_price} naar {@code publication_bundle_snapshot} en
 * {@code publication_bundle_snapshot_price}.
 *
 * <h2>Waarom set-based JDBC en geen JPA</h2>
 * Zelfde reden als {@link MutationDao} en {@link CandidateStageDao}: een bundel kan honderdduizenden
 * publiceerbare mutaties dragen. Eén entiteit per mutatie zou de persistence context laten vollopen en
 * per rij een round-trip kosten, en de bedragen zouden dan langs Java lopen — precies waar een
 * onbedoelde herschaling of afronding zou kunnen ontstaan. Hier verlaat geen enkel bedrag de database:
 * {@code source_amount} en {@code percentage} worden binnen SQL van kolom naar kolom gekopieerd, in
 * kolommen met exact hetzelfde type ({@code numeric(24,6)} en {@code numeric(24,12)}, changeset 008-2).
 *
 * <h2>Scope: exact die van de PSIMPORT-preview</h2>
 * De snapshot bevat exact de mutaties die {@link PsimportPreviewDao} als te publiceren rijen toont:
 * {@code status = 'READY_FOR_PUBLICATION'}, {@code action_type in ('CREATE','UPDATE')}, binnen de
 * <b>actieve</b> batchlidmaatschappen van de bundel. {@code IMPORT_MARKER},
 * {@code IDENTITY_REFERENCE_INCIDENT}, {@code BLOCKED}, {@code REJECTED} en {@code EXPIRED} vallen er
 * dus nooit in.
 * <p>
 * {@link #SCOPE_FROM} + {@link #SCOPE_WHERE} is woord voor woord de {@code FROM_WHERE}-constante van
 * {@link PsimportPreviewDao}, enkel gesplitst op het woord {@code where} zodat er tussen de
 * {@code from} en de {@code where} een {@code join} op de kandidaatstaging past. Die constante is daar
 * privé en blijft in deze bouwstap ongewijzigd; {@code BundleFreezeSnapshotTest} bewijst daarom dat
 * beide DAO's over dezelfde bundel hetzelfde aantal rijen zien — divergeert de preview ooit, dan valt
 * die test om in plaats van dat de snapshot stilzwijgend iets anders gaat bevatten.
 *
 * <h2>Nooit stil leeg</h2>
 * De {@code join} op {@code (m.batch_id, m.source_row_number) = (s.batch_id, s.row_number)} is een
 * <b>inner</b> join: een mutatie waarvan de kandidaatstaging opgeruimd is, levert geen snapshotrij op.
 * De aanroeper vergelijkt {@link #countInScope} met het aantal geschreven rijen en blokkeert de hele
 * bevriezing bij het minste verschil ({@code SNAPSHOT_SOURCE_MISSING}, ontwerp par. 1). Er wordt nooit
 * een lege omschrijving of een 0-bedrag ingevuld om een gat te dichten.
 *
 * <h2>Transactiegrens</h2>
 * Deze DAO opent zelf geen transactie. Ze draait binnen dezelfde transactie als de rest van de
 * bevriezing ({@code BundleFreezeService}), zodat een blokkade alles terugdraait.
 */
@Repository
public class PublicationBundleSnapshotDao {

    /**
     * De componentcode van de basisprijs zelf. Ze wordt <b>niet</b> mee gesnapshot: ontwerp par. 1,
     * "Niet gekopieerd: basisprijs, valuta, identiteit en referenties (staan al op
     * {@code import_mutation}, dat nooit wordt opgeruimd; dupliceren zou een tweede bron van waarheid
     * voor een bedrag zijn)". {@code import_candidate_price} draagt de basisprijs als gewone
     * componentrij ({@code PriceRules.BASE_COMPONENT_CODE}), met een {@code source_amount} dat
     * letterlijk gelijk is aan {@code import_mutation.after_base_price} — die rij mee kopiëren zou dus
     * exact de duplicatie opleveren die het ontwerp uitsluit.
     * <p>
     * De code staat hier als tekst en niet als verwijzing naar {@code PriceRules}: die klasse leeft in
     * de Service-laag, en de Dao-laag mag daar niet van afhangen (zelfde keuze als
     * {@code MutationDao.IDENTITY_INCIDENT_CLASSIFICATION}).
     */
    public static final String BASE_PRICE_COMPONENT_CODE = "BASE_PRICE";

    /** Het {@code from}-deel van de scope; zie de klassedocumentatie. */
    static final String SCOPE_FROM = "from import_mutation m ";

    /** Het {@code where}-deel van de scope; één bindparameter: de bundel-id. */
    static final String SCOPE_WHERE = "where m.batch_id in (select bb.batch_id from publication_bundle_batch bb "
            + "    where bb.bundle_id = ? and bb.active_marker is not null) "
            + "  and m.status = 'READY_FOR_PUBLICATION' and m.action_type in ('CREATE', 'UPDATE') ";

    /**
     * De omschrijving en haar toestand, afgeleid uit wat {@code import_candidate_stage.description}
     * werkelijk kan bevatten (zie {@code CandidateNormaliser}):
     * <ul>
     *   <li>{@code null} — de revisie mapt geen omschrijvingsveld; de bron doet er geen uitspraak over
     *       ⇒ {@code NOT_MAPPED};</li>
     *   <li>{@code ''} — wél gemapt, maar de bronkolom was leeg of enkel witruimte (de normalisatie
     *       trimt en bewaart dan de lege tekst) ⇒ {@code EMPTY};</li>
     *   <li>elke andere tekst ⇒ {@code VALUE}.</li>
     * </ul>
     * De {@code trim(...)}-vergelijking is defensief: via de screening kan er nooit een uitsluitend uit
     * spaties bestaande omschrijving in de staging staan, maar zou een andere schrijver er ooit één
     * achterlaten, dan is "blanco" hier {@code EMPTY} en nooit {@code VALUE}. De <b>bewaarde</b>
     * omschrijving blijft in elk geval de bronwaarde, letterlijk en ongewijzigd.
     */
    private static final String DESCRIPTION_STATE = "cast(case when s.description is null then 'NOT_MAPPED' "
            + "          when trim(s.description) = '' then 'EMPTY' "
            + "          else 'VALUE' end as varchar(20))";

    /**
     * Eén rij per te publiceren mutatie. {@code batch_id}, {@code import_link_id} en
     * {@code source_row_number} komen van de mutatie zelf (gedenormaliseerde herkomst, changeset
     * 008-1); de omschrijving komt uit de staging.
     */
    private static final String INSERT_SNAPSHOT = "insert into publication_bundle_snapshot ("
            + "bundle_id, mutation_id, batch_id, import_link_id, source_row_number, "
            + "description, description_state, created_at) "
            + "select cast(? as bigint), m.id, m.batch_id, m.import_link_id, m.source_row_number, "
            + "       s.description, " + DESCRIPTION_STATE + ", cast(? as timestamp with time zone) "
            + SCOPE_FROM
            + "join import_candidate_stage s "
            + "  on s.batch_id = m.batch_id and s.row_number = m.source_row_number "
            + SCOPE_WHERE;

    /**
     * De prijscomponenten van de zojuist geschreven snapshotrijen, letterlijk gekopieerd. De
     * {@code join} vertrekt bewust vanuit {@code publication_bundle_snapshot}: die rijen dragen de
     * herkomst ({@code batch_id}, {@code source_row_number}) al, dus de scopebepaling gebeurt maar op
     * één plaats — in {@link #INSERT_SNAPSHOT}.
     * <p>
     * Geen enkele herberekening en geen afronding: {@code source_amount}, {@code percentage},
     * {@code currency} en {@code status} gaan ongewijzigd mee. Een component zonder bedrag of zonder
     * percentage blijft {@code null}; er wordt nooit 0 ingevuld (R-PRI-05).
     */
    private static final String INSERT_SNAPSHOT_PRICES = "insert into publication_bundle_snapshot_price ("
            + "snapshot_id, component_code, source_amount, percentage, currency, status) "
            + "select sn.id, p.component_code, p.source_amount, p.percentage, p.currency, p.status "
            + "from publication_bundle_snapshot sn "
            + "join import_candidate_price p "
            + "  on p.batch_id = sn.batch_id and p.row_number = sn.source_row_number "
            + "where sn.bundle_id = ? and p.component_code <> '" + BASE_PRICE_COMPONENT_CODE + "'";

    private final JdbcTemplate jdbc;

    public PublicationBundleSnapshotDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Het aantal mutaties dat gesnapshot <b>moet</b> worden: de volledige scope, zonder de staging aan
     * te raken. Dit is de teller waarmee de aanroeper het werkelijk geschreven aantal vergelijkt.
     */
    public long countInScope(long bundleId) {
        Long count = jdbc.queryForObject("select count(*) " + SCOPE_FROM + SCOPE_WHERE, Long.class, bundleId);
        return count == null ? 0L : count;
    }

    /**
     * Schrijft de snapshotrijen van deze bundel.
     *
     * @param createdAt het bevriezingsmoment; de snapshot draagt geen eigen actor — die staat op
     *                  {@code publication_bundle.frozen_by(_subject)} en op de FREEZE-beslissingsregel
     * @return het aantal geschreven rijen; de aanroeper vergelijkt dit met {@link #countInScope} en
     *         rolt de hele bevriezing terug wanneer ze verschillen
     */
    public int insertSnapshot(long bundleId, Instant createdAt) {
        return jdbc.update(INSERT_SNAPSHOT, bundleId,
                OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC), bundleId);
    }

    /**
     * Schrijft de prijscomponenten van de snapshotrijen van deze bundel. Moet ná
     * {@link #insertSnapshot} lopen, in dezelfde transactie.
     * <p>
     * Het aantal is bewust <b>geen</b> blokkeercriterium: een mutatie van een revisie zonder gemapte
     * prijscomponenten heeft helemaal geen {@code import_candidate_price}-rijen (zie
     * {@code CandidateNormaliser}), en dat is een geldige toestand — geen ontbrekende bron. De
     * aanwezigheid van de bron wordt op mutatieniveau bewaakt, waar ze onmiskenbaar is.
     *
     * @return het aantal geschreven prijsrijen, enkel voor logging
     */
    public int insertSnapshotPrices(long bundleId) {
        return jdbc.update(INSERT_SNAPSHOT_PRICES, bundleId);
    }

    /** De huidige specificatieversie van {@link #computeSnapshotHash}; bewaard in {@code snapshot_spec_version}. */
    public static final String SNAPSHOT_SPEC_VERSION = "1";

    /**
     * De snapshothash (ontwerp 5-PUB par. 1, bouwstap 5P-3): één SHA-256 over de snapshottabellen van
     * deze bundel. Zelfde stijl als {@code PublicationBundleDao.computeContentHash}, maar over de
     * snapshot in plaats van over {@code import_mutation}.
     *
     * <h2>Snapshot spec versie 1 (vastgelegd)</h2>
     * Per snapshotrij, oplopend op {@code mutation_id}, in exact deze volgorde, elk veld afgesloten met
     * {@code U+001F} (unit separator) en de hele rij afgesloten met {@code U+001E} (record separator):
     * <pre>mutation_id ␟ description_state ␟ description ␟ { component_code ␟ source_amount ␟ percentage ␟ currency ␟ status ␟ }* ␞</pre>
     * <ul>
     *   <li>De prijscomponenten van de rij volgen direct na de omschrijving, geordend op
     *       {@code component_code} (oplopend Java-tekstvolgorde, {@code String.compareTo} — bewust niet
     *       de collatie van de database, die tussen H2 en PostgreSQL verschilt). Een rij zonder
     *       componenten heeft geen componentgroep.</li>
     *   <li>{@code null} wordt de vaste tekst {@code "null"} (ook een niet-gemapte omschrijving, en een
     *       ontbrekend bedrag/percentage/munt); de lege tekst blijft leeg en is dus onderscheidbaar.</li>
     *   <li>{@code source_amount} en {@code percentage} als {@code stripTrailingZeros().toPlainString()}.</li>
     *   <li>Tekst als UTF-8; algoritme SHA-256; type {@code byte[]}, identiek aan {@code content_hash}.</li>
     * </ul>
     * De basisprijs zit niet in de snapshot en dus ook niet in deze hash; hij is gedekt door
     * {@code content_hash} (via {@code import_mutation.after_base_price}).
     * <p>
     * Gestreamd: één query geordend op {@code mutation_id}; enkel de componenten van de huidige rij staan
     * in het geheugen.
     *
     * @return de hash, of {@code null} wanneer de bundel geen enkele snapshotrij heeft (dan wordt er
     *         ook geen {@code snapshot_hash} bewaard)
     */
    public byte[] computeSnapshotHash(long bundleId) {
        MessageDigest digest = newSha256();
        SnapshotHashState state = new SnapshotHashState(digest);
        jdbc.query("select sn.mutation_id, sn.description_state, sn.description, "
                        + "       p.component_code, p.source_amount, p.percentage, p.currency, p.status "
                        + "from publication_bundle_snapshot sn "
                        + "left join publication_bundle_snapshot_price p on p.snapshot_id = sn.id "
                        + "where sn.bundle_id = ? "
                        + "order by sn.mutation_id, p.component_code",
                rs -> {
                    long mutationId = rs.getLong(1);
                    if (!state.open || state.mutationId != mutationId) {
                        state.flush();
                        state.open = true;
                        state.mutationId = mutationId;
                        state.head = new StringBuilder(96);
                        hashField(state.head, Long.toString(mutationId));
                        hashField(state.head, rs.getString(2));
                        hashField(state.head, rs.getString(3));
                    }
                    String code = rs.getString(4);
                    if (code != null) {
                        StringBuilder component = new StringBuilder(64);
                        hashField(component, code);
                        hashField(component, plain(rs.getBigDecimal(5)));
                        hashField(component, plain(rs.getBigDecimal(6)));
                        hashField(component, rs.getString(7));
                        hashField(component, rs.getString(8));
                        state.components.put(code, component.toString());
                    }
                }, bundleId);
        state.flush();
        return state.rows == 0 ? null : digest.digest();
    }

    /** Toestand van de gestreamde berekening: de huidige rij en haar (op code gesorteerde) componenten. */
    private static final class SnapshotHashState {
        private final MessageDigest digest;
        private final java.util.TreeMap<String, String> components = new java.util.TreeMap<>();
        private boolean open;
        private long mutationId;
        private StringBuilder head;
        private long rows;

        SnapshotHashState(MessageDigest digest) {
            this.digest = digest;
        }

        void flush() {
            if (!open) {
                return;
            }
            StringBuilder line = new StringBuilder(head);
            components.values().forEach(line::append);
            line.append('\u001E');
            digest.update(line.toString().getBytes(StandardCharsets.UTF_8));
            components.clear();
            rows++;
            open = false;
        }
    }

    private static void hashField(StringBuilder line, String value) {
        line.append(value == null ? "null" : value).append('\u001F');
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** Het aantal snapshotrijen dat deze bundel draagt; leesmodel voor tests en latere bouwstappen. */
    public long countSnapshotRows(long bundleId) {
        Long count = jdbc.queryForObject("select count(*) from publication_bundle_snapshot where bundle_id = ?",
                Long.class, bundleId);
        return count == null ? 0L : count;
    }
}
