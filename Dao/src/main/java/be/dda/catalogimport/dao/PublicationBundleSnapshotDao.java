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
     *
     * <h3>{@code before_percentage} (snapshot spec v2, P4b)</h3>
     * De verwachte Prodis-waarde voor CONFLICT-detectie (P-V4, design publicatie-direct par. 2.5): het percentage
     * van dezelfde component in de <b>aanvaarde bronstaat</b> waartegen de mutatie gescreend is —
     * {@code catalog_source_state_price} van {@code import_mutation.source_state_id}, exact dezelfde bronstaatrij
     * waaruit {@code import_mutation.before_base_price} komt ({@code MutationDao}, "voor"-waarde, R-PRI-09). Dat die
     * rij sinds de screening niet verschoven is, bewaakt het bevriezen zelf vóór deze insert
     * ({@code SOURCE_STATE_CHANGED} op de gecombineerde vingerafdruk, die de componentverhoudingen dekt).
     * <ul>
     *   <li>{@code CREATE} → {@code null}: er is geen voor-toestand (ook expliciet in de join-voorwaarde);</li>
     *   <li>{@code UPDATE} zonder die component in de bronstaat (component erbij gekomen) → {@code null};</li>
     *   <li>anders de bronstaatwaarde, letterlijk van kolom naar kolom ({@code numeric(24,12)} → {@code numeric(24,12)}).</li>
     * </ul>
     * {@code null} betekent "niet beschikbaar", nooit 0; de projectie maakt er KEEP + {@code EXPECTED_NOT_AVAILABLE} van.
     * Elke component krijgt zijn voor-waarde (ook {@code AKP}/{@code VKP_GROSS}): de kolom is "de bronstaat vóór deze
     * levering", geen Prodis-specifieke selectie; welke codes naar Prodis gaan, beslist de projectie.
     */
    private static final String INSERT_SNAPSHOT_PRICES = "insert into publication_bundle_snapshot_price ("
            + "snapshot_id, component_code, source_amount, percentage, currency, status, before_percentage) "
            + "select sn.id, p.component_code, p.source_amount, p.percentage, p.currency, p.status, "
            + "       accepted.percentage "
            + "from publication_bundle_snapshot sn "
            + "join import_candidate_price p "
            + "  on p.batch_id = sn.batch_id and p.row_number = sn.source_row_number "
            + "join import_mutation m on m.id = sn.mutation_id "
            + "left join catalog_source_state_price accepted "
            + "  on m.action_type = 'UPDATE' and accepted.source_state_id = m.source_state_id "
            + " and accepted.component_code = p.component_code "
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

    /** Snapshot spec versie 1 (5P-3): zonder {@code before_percentage}. Bundels bevroren vóór P4b dragen deze versie. */
    public static final String SNAPSHOT_SPEC_VERSION_1 = "1";

    /** Snapshot spec versie 2 (P4b): elke prijscomponent draagt ook {@code before_percentage}. */
    public static final String SNAPSHOT_SPEC_VERSION_2 = "2";

    /**
     * De huidige specificatieversie: die waarmee het bevriezen nu de snapshot vult en hasht; bewaard in
     * {@code snapshot_spec_version}. Sinds P4b {@value}.
     */
    public static final String SNAPSHOT_SPEC_VERSION = SNAPSHOT_SPEC_VERSION_2;

    /**
     * De snapshothash volgens de <b>huidige</b> specificatie ({@link #SNAPSHOT_SPEC_VERSION}). Voor een bestaande
     * bundel: {@link #computeSnapshotHash(long, String)} met haar eigen {@code snapshot_spec_version}.
     */
    public byte[] computeSnapshotHash(long bundleId) {
        return computeSnapshotHash(bundleId, SNAPSHOT_SPEC_VERSION);
    }

    /**
     * De snapshothash (ontwerp 5-PUB par. 1, bouwstap 5P-3; P4b): één SHA-256 over de snapshottabellen van
     * deze bundel. Zelfde stijl als {@code PublicationBundleDao.computeContentHash}, maar over de
     * snapshot in plaats van over {@code import_mutation}. {@code content_hash} zelf blijft voor elke bundel
     * byte-identiek (beslissing 2026-09-26); {@code before_percentage} zit uitsluitend in deze hash.
     *
     * <h2>Snapshot spec versie 1 (vastgelegd, ongewijzigd)</h2>
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
     * Versie 1 leest {@code before_percentage} niet: een v1-bundel levert byte-identiek dezelfde hash als vóór P4b.
     *
     * <h2>Snapshot spec versie 2 (P4b)</h2>
     * Identiek aan versie 1, met één veld extra <b>achteraan elke componentgroep</b>:
     * <pre>mutation_id ␟ description_state ␟ description ␟ { component_code ␟ source_amount ␟ percentage ␟ currency ␟ status ␟ before_percentage ␟ }* ␞</pre>
     * {@code before_percentage} als {@code stripTrailingZeros().toPlainString()}, {@code null} als {@code "null"}. Een
     * rij zonder componenten serialiseert in beide versies gelijk (er is dan geen {@code before_percentage} om op te
     * nemen); de versie zelf staat in {@code snapshot_spec_version} naast de hash, niet in de gehashte tekst.
     * <p>
     * De basisprijs zit niet in de snapshot en dus ook niet in deze hash; hij is gedekt door
     * {@code content_hash} (via {@code import_mutation.after_base_price}).
     * <p>
     * Gestreamd: één query geordend op {@code mutation_id}; enkel de componenten van de huidige rij staan
     * in het geheugen.
     *
     * @param specVersion {@link #SNAPSHOT_SPEC_VERSION_1} of {@link #SNAPSHOT_SPEC_VERSION_2}
     * @return de hash, of {@code null} wanneer de bundel geen enkele snapshotrij heeft (dan wordt er
     *         ook geen {@code snapshot_hash} bewaard)
     * @throws IllegalArgumentException een onbekende versie: nooit stil op een andere versie terugvallen
     */
    public byte[] computeSnapshotHash(long bundleId, String specVersion) {
        SnapshotHasher hasher = new SnapshotHasher(specVersion);
        jdbc.query("select sn.mutation_id, sn.description_state, sn.description, "
                        + "       p.component_code, p.source_amount, p.percentage, p.currency, p.status, "
                        + "       p.before_percentage "
                        + "from publication_bundle_snapshot sn "
                        + "left join publication_bundle_snapshot_price p on p.snapshot_id = sn.id "
                        + "where sn.bundle_id = ? "
                        + "order by sn.mutation_id, p.component_code",
                rs -> {
                    hasher.row(rs.getLong(1), rs.getString(2), rs.getString(3));
                    String code = rs.getString(4);
                    if (code != null) {
                        hasher.component(code, rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getString(7),
                                rs.getString(8), rs.getBigDecimal(9));
                    }
                }, bundleId);
        return hasher.finish();
    }

    /**
     * De canonieke serialisatie van {@link #computeSnapshotHash(long, String)}, los van de database: rijen in
     * {@code mutation_id}-volgorde aanbieden ({@link #row} mag voor dezelfde mutatie herhaald worden, zoals een
     * {@code left join} dat doet), componenten in willekeurige volgorde ({@link #component}; gesorteerd op code bij het
     * afsluiten van de rij). Pakketzichtbaar zodat de spec met letterlijke hashes getest kan worden.
     */
    static final class SnapshotHasher {
        private final boolean withBeforePercentage;
        private final MessageDigest digest = newSha256();
        private final java.util.TreeMap<String, String> components = new java.util.TreeMap<>();
        private boolean open;
        private long mutationId;
        private StringBuilder head;
        private long rows;

        SnapshotHasher(String specVersion) {
            if (SNAPSHOT_SPEC_VERSION_1.equals(specVersion)) {
                withBeforePercentage = false;
            } else if (SNAPSHOT_SPEC_VERSION_2.equals(specVersion)) {
                withBeforePercentage = true;
            } else {
                throw new IllegalArgumentException("Unknown snapshot spec version: " + specVersion);
            }
        }

        /** Begint een nieuwe snapshotrij, tenzij dit dezelfde mutatie is als de huidige. */
        void row(long rowMutationId, String descriptionState, String description) {
            if (open && mutationId == rowMutationId) {
                return;
            }
            flush();
            open = true;
            mutationId = rowMutationId;
            head = new StringBuilder(96);
            hashField(head, Long.toString(rowMutationId));
            hashField(head, descriptionState);
            hashField(head, description);
        }

        /** Eén prijscomponent van de huidige rij; {@code beforePercentage} telt enkel mee vanaf spec versie 2. */
        void component(String code, BigDecimal sourceAmount, BigDecimal percentage, String currency, String status,
                       BigDecimal beforePercentage) {
            StringBuilder component = new StringBuilder(80);
            hashField(component, code);
            hashField(component, plain(sourceAmount));
            hashField(component, plain(percentage));
            hashField(component, currency);
            hashField(component, status);
            if (withBeforePercentage) {
                hashField(component, plain(beforePercentage));
            }
            components.put(code, component.toString());
        }

        /** @return de hash, of {@code null} zonder enige rij */
        byte[] finish() {
            flush();
            return rows == 0 ? null : digest.digest();
        }

        private void flush() {
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
