package be.dda.catalogimport.dao;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalLong;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Delta en mutatiegeneratie: alles wat {@code catalog_source_state} of {@code import_mutation} — de
 * centrale PSIMPORT001-mutatielijst — raakt (design par. 3 en par. 4).
 * <p>
 * <b>Waarom set-based en niet rij per rij.</b> Een levering kan een miljoen regels bevatten. De
 * vergelijking met de bronstaat en het schrijven van de mutaties gebeuren daarom volledig in de
 * database: één {@code update ... case} voor de classificatie en één {@code insert ... select} voor
 * de mutaties, telkens over een venster van regelnummers (de chunk). Er wordt nooit een rij naar
 * Java gehaald om ze meteen terug te schrijven, en de binaire hash-/vingerafdrukkolommen verlaten de
 * database niet: ze worden enkel in SQL vergeleken. De JPA-entiteit {@code ImportMutation} mapt die
 * kolommen bewust niet en is uitsluitend leesmodel.
 * <p>
 * <b>Idempotentie.</b> De sleutel van een inhoudelijke mutatie is
 * {@code <delivery>:<revisie>:<identiteitshash-hex>:OFFER} en wordt gebouwd uit
 * {@code import_candidate_stage.mutation_key_prefix} — met SQL-concatenatie, nooit met een
 * databasespecifieke hexfunctie, zodat PostgreSQL en H2 dezelfde sleutel opleveren. Elke insert
 * slaat rijen over waarvoor die sleutel al bestaat, zodat een hervatte chunk niets verdubbelt; de
 * unieke index {@code uk_import_mutation_idempotency} blijft de harde garantie.
 * <p>
 * <b>Prijscomponenten staan niet in extra mutatiekolommen</b> (ontwerp fase 3, R-PRI-09). De mutatie
 * draagt de voor- en nabasisprijs en een {@code domain_mask} dat zegt <i>welke</i> component wijzigde
 * ({@code PRICE:AKP}); de voor- en nawaarde van die component zijn volledig herleidbaar zonder
 * duplicatie:
 * <ul>
 *   <li><b>voor</b>: {@code catalog_source_state_price} van {@code import_mutation.source_state_id}
 *       — de toestand waartegen gescreend is, zolang de mutatie nog niet uitgevoerd is;</li>
 *   <li><b>na</b>: {@code import_candidate_price} op
 *       {@code (batch_id, source_row_number)} van de mutatie.</li>
 * </ul>
 * Zeven componenten × twee waarden als kolommen zou de mutatielijst per component moeten laten
 * meegroeien; erger nog, het zou een tweede bron van waarheid voor een bedrag zijn. Er is er precies
 * één, en de mutatie verwijst ernaar.
 * <p>
 * <b>Transactiegrens.</b> Deze DAO opent zelf geen transactie; de screeningservice bepaalt de
 * chunkgrens (design par. 9, stap E). Binnen één transactie wordt hier geschreven en nooit via JPA
 * teruggelezen.
 */
@Repository
public class MutationDao {

    /** Standaard chunkgrootte van de mutatiegeneratie, conform design par. 9 stap E. */
    public static final int DEFAULT_CHUNK_SIZE = 5000;

    /** Achtervoegsel van de idempotentiesleutel van een inhoudelijke mutatie op een aanbieding. */
    public static final String OFFER_KEY_SUFFIX = ":OFFER";

    /** Achtervoegsel van de idempotentiesleutel van de {@code IMPORT_MARKER} van een screening. */
    public static final String MARKER_KEY_SUFFIX = ":MARKER";

    /** {@code import_mutation.result_summary} is varchar(1000). */
    public static final int MAX_RESULT_SUMMARY_LENGTH = 1000;

    /** Scheidt het prijsdomein van de componentcode in {@code domain_mask}: {@code PRICE:AKP}. */
    public static final String COMPONENT_MASK_SEPARATOR = ":";

    /**
     * De classificatie van een regel die wegens een kritiek referentie-incident wordt vastgehouden
     * (R-REF-09). Dezelfde waarde staat in {@code CandidateClassification.IDENTITY_INCIDENT} (Domain);
     * ze staat hier letterlijk omdat de SQL ze nodig heeft en de Dao-laag geen enum uit de Domain-laag
     * in haar statementtekst mag weven.
     */
    public static final String IDENTITY_INCIDENT_CLASSIFICATION = "IDENTITY_INCIDENT";

    /**
     * {@code import_mutation.status_reason} van de inhoudelijke mutatie van een vastgehouden regel.
     * Eén uniforme reden voor alle kritieke referentie-incidenten; wélk incident het precies was
     * (een gewijzigde, verwijderde, hergebruikte of dubbelzinnige referentie, of dezelfde
     * referentiewaarde tweemaal in deze levering) staat in {@code import_row_issue} en in de
     * bijhorende {@code IDENTITY_REFERENCE_INCIDENT}-mutatie. Een tweede reden in deze kolom zou een
     * correlatie per regel vragen en op een miljoen regels een scan per regel opleveren.
     */
    public static final String BLOCKED_BY_IDENTITY_REFERENCE_INCIDENT = "IDENTITY_REFERENCE_INCIDENT";

    /** Het artikeldeel van {@code domain_mask}. */
    public static final String ARTICLE_MASK = "ARTICLE";
    /** Het basisprijsdeel van {@code domain_mask}; met een componentcode erachter: {@code PRICE:AKP}. */
    public static final String PRICE_MASK = "PRICE";

    /**
     * {@code import_mutation.domain_mask} is varchar(200) sinds changeset 004-13b (bouwstap 3e). Met
     * de zeven geseede prijscomponenten was het langst mogelijke masker 94 tekens en paste het nog
     * net in de oorspronkelijke varchar(100); de kolom is verbreed zodat een achtste component of een
     * langere componentcode niet halverwege een levering op een databasefout strandt.
     */
    public static final int MAX_DOMAIN_MASK_LENGTH = 200;

    /** Vorm van een prijscomponentcode; zie {@link #verifyComponentCode(String)}. */
    private static final Pattern COMPONENT_CODE = Pattern.compile("[A-Z0-9_]{1,20}");

    private static final String CONTENT_MUTATION_COLUMNS = "batch_id, delivery_id, import_link_id, "
            + "definition_revision_id, task_run_id, action_type, target_domain, status, status_reason, "
            + "identity_supplier, identity_supplier_group, identity_supplier_reference, "
            + "identity_discount_code, identity_discount_state, identity_hash, "
            + "before_combined_fingerprint, after_combined_fingerprint, domain_mask, "
            + "before_base_price, after_base_price, base_price_currency, source_state_id, "
            + "delivery_file_id, source_row_number, idempotency_key, created_at";

    /**
     * Zet de classificatie van één chunk gestagede regels ten opzichte van de bronstaat van
     * <b>deze</b> importkoppeling. Twee koppelingen met dezelfde leverancierssleutel zien elkaars
     * bronstaat dus nooit: {@code import_link_id} staat in elke deeluitdrukking.
     * <p>
     * Geen rij in de bronstaat ⇒ {@code NEW}; zelfde gecombineerde vingerafdruk ⇒ {@code UNCHANGED}
     * (die regel raakt de bronstaat nooit aan); anders {@code CHANGED}. De eerste levering is
     * gewoon een join op een lege tabel, geen apart codepad. Reeds geclassificeerde regels
     * (bv. {@code DUPLICATE_IN_DELIVERY}) worden niet overschreven.
     */
    private static final String CLASSIFY_CHUNK = "update import_candidate_stage stage set classification = case "
            + "when not exists (select 1 from catalog_source_state state "
            + "    where state.import_link_id = ? and state.identity_hash = stage.identity_hash) then 'NEW' "
            + "when exists (select 1 from catalog_source_state state "
            + "    where state.import_link_id = ? and state.identity_hash = stage.identity_hash "
            + "      and state.combined_fingerprint = stage.combined_fingerprint) then 'UNCHANGED' "
            + "else 'CHANGED' end "
            + "where stage.batch_id = ? and stage.classification is null "
            + "  and stage.row_number > ? and stage.row_number <= ?";

    /**
     * Schrijft de inhoudelijke mutaties van één chunk. {@code UNCHANGED} levert bewust geen rij op.
     * <p>
     * Het domeinmasker van een {@code UPDATE} zegt welk deel van de aanbieding verschilt; zie
     * {@link #domainMask(List)}. De voor- en nabasisprijs worden apart bewaard, zodat een
     * prijswijziging zichtbaar is zonder de bronregel te hoeven bewaren.
     * <p>
     * <b>Vastgehouden regels krijgen wél hun mutatie, maar geblokkeerd</b> (R-REF-09). Een regel met
     * classificatie {@code IDENTITY_INCIDENT} levert dezelfde {@code CREATE}- of {@code UPDATE}-rij
     * op, maar met status {@code BLOCKED} en reden
     * {@value #BLOCKED_BY_IDENTITY_REFERENCE_INCIDENT}. Ze helemaal weglaten zou verbergen wát er
     * met die aanbieding zou gebeuren zodra het incident goedgekeurd of verworpen is; ze op
     * {@code PLANNED} zetten zou een kritieke referentiewijziging alsnog als gewone update laten
     * doorgaan. {@code accept-baseline} raakt een {@code BLOCKED}-mutatie nooit aan.
     * <p>
     * Of het een creatie of een wijziging is, volgt uit de <b>bronstaat</b> en niet uitsluitend uit de
     * classificatie: een vastgehouden regel draagt haar oorspronkelijke {@code NEW}/{@code CHANGED}
     * niet meer.
     *
     * @param componentCodes de prijscomponenten van deze batch, gesorteerd; leeg ⇒ exact het
     *                       fase 2-masker
     */
    private static String insertContentMutations(List<String> componentCodes) {
        return "insert into import_mutation ("
                + CONTENT_MUTATION_COLUMNS + ") select "
                + "cast(? as bigint), cast(? as bigint), cast(? as bigint), cast(? as bigint), cast(? as bigint), "
                + "case when stage.classification = 'NEW' or state.id is null then 'CREATE' else 'UPDATE' end, "
                + "'OFFER', "
                + "case when stage.classification = '" + IDENTITY_INCIDENT_CLASSIFICATION + "' "
                + "     then 'BLOCKED' else 'PLANNED' end, "
                + "case when stage.classification = '" + IDENTITY_INCIDENT_CLASSIFICATION + "' "
                + "     then cast('" + BLOCKED_BY_IDENTITY_REFERENCE_INCIDENT + "' as varchar(200)) "
                + "     else cast(null as varchar(200)) end, "
                + "stage.identity_supplier, stage.identity_supplier_group, stage.identity_supplier_reference, "
                + "stage.identity_discount_code, stage.identity_discount_state, stage.identity_hash, "
                + "state.combined_fingerprint, stage.combined_fingerprint, "
                + domainMask(componentCodes) + ", "
                + "state.base_price, stage.base_price, stage.base_price_currency, state.id, "
                + "cast(? as bigint), stage.row_number, stage.mutation_key_prefix || '" + OFFER_KEY_SUFFIX + "', "
                + "cast(? as timestamp with time zone) "
                + "from import_candidate_stage stage "
                + "left join catalog_source_state state "
                + "  on state.import_link_id = ? and state.identity_hash = stage.identity_hash "
                + "where stage.batch_id = ? and stage.row_number > ? and stage.row_number <= ? "
                + "  and stage.classification in ('NEW', 'CHANGED', '"
                + IDENTITY_INCIDENT_CLASSIFICATION + "') "
                + "  and not exists (select 1 from import_mutation existing "
                + "      where existing.idempotency_key = stage.mutation_key_prefix || '" + OFFER_KEY_SUFFIX + "')";
    }

    /**
     * Het domeinmasker van een {@code UPDATE}: welk deel van de aanbieding verschilt van de aanvaarde
     * bronstaat (R-PRI-09).
     * <ul>
     *   <li>{@code ARTICLE} — de artikelvingerafdruk verschilt;</li>
     *   <li>{@code PRICE} — de <b>basisprijs</b> of haar munt verschilt;</li>
     *   <li>{@code PRICE:<COMPONENT>} — de verhouding of de munt van die ene prijscomponent verschilt,
     *       of de component is erbij gekomen of weggevallen.</li>
     * </ul>
     * De onderdelen staan in een vaste, deterministische volgorde (artikel, basisprijs, daarna de
     * componenten op code) en worden met een komma gescheiden: {@code ARTICLE,PRICE:AKP}. Zo kan een
     * gebruiker zien dát enkel de aankoopprijsverhouding wijzigde terwijl de basisprijs gelijk bleef —
     * precies het geval dat R-PRI-09 zichtbaar wil maken.
     * <p>
     * <b>Zonder prijscomponenten blijft dit exact het fase 2-masker</b> ({@code ARTICLE},
     * {@code PRICE}, {@code ARTICLE,PRICE} of {@code null}): de basisprijsvergelijking is dan
     * gelijkwaardig aan de vergelijking van de prijsvingerafdrukken, want die dekt in dat geval enkel
     * de basisprijs en de munt.
     */
    private static String domainMask(List<String> componentCodes) {
        verifyMaskFits(componentCodes);
        StringBuilder parts = new StringBuilder();
        parts.append("case when state.article_fingerprint <> stage.article_fingerprint "
                + "then ',").append(ARTICLE_MASK).append("' else '' end");
        parts.append(" || case when state.base_price <> stage.base_price "
                + "or coalesce(state.base_price_currency, '') <> coalesce(stage.base_price_currency, '') "
                + "then ',").append(PRICE_MASK).append("' else '' end");
        for (String code : componentCodes) {
            parts.append(" || case when ").append(componentDiffers(code))
                    .append(" then ',").append(PRICE_MASK).append(COMPONENT_MASK_SEPARATOR).append(code)
                    .append("' else '' end");
        }
        // Een creatie heeft geen "voor"-toestand en dus geen masker; een CHANGED waarvan enkel het
        // referentiedeel verschilt (bouwstap 3f) krijgt null in plaats van een lege tekst die op
        // "niets gewijzigd" zou lijken.
        return "case when stage.classification = 'NEW' or state.id is null then cast(null as varchar("
                + MAX_DOMAIN_MASK_LENGTH + ")) "
                + "else nullif(substr(" + parts + ", 2), '') end";
    }

    /**
     * Verschilt deze prijscomponent van de aanvaarde bronstaat? Gelijk is: de component bestaat aan
     * beide kanten met exact dezelfde verhouding en munt, óf ze bestaat aan geen van beide kanten.
     * Elke andere toestand — toegevoegd, weggevallen, andere verhouding, andere munt — is een
     * verschil. De vergelijking gebeurt op {@code numeric}, nooit op een afgeronde of tekstuele vorm.
     */
    private static String componentDiffers(String componentCode) {
        String code = "'" + verifyComponentCode(componentCode) + "'";
        return "not (exists (select 1 from import_candidate_price price "
                + "join catalog_source_state_price accepted "
                + "  on accepted.source_state_id = state.id "
                + " and accepted.component_code = price.component_code "
                + "where price.batch_id = stage.batch_id and price.row_number = stage.row_number "
                + "  and price.component_code = " + code
                + "  and price.percentage = accepted.percentage "
                + "  and coalesce(price.currency, '') = coalesce(accepted.currency, '')) "
                + "or (not exists (select 1 from import_candidate_price price "
                + "      where price.batch_id = stage.batch_id and price.row_number = stage.row_number "
                + "        and price.component_code = " + code + ") "
                + "    and not exists (select 1 from catalog_source_state_price accepted "
                + "      where accepted.source_state_id = state.id "
                + "        and accepted.component_code = " + code + ")))";
    }

    /**
     * Het masker moet in {@code import_mutation.domain_mask} passen
     * ({@value #MAX_DOMAIN_MASK_LENGTH} tekens sinds changeset 004-13b). Met de zeven geseede
     * prijscomponenten is het langst mogelijke masker 94 tekens; een definitie met veel méér of met
     * langere componentcodes zou er alsnog buiten vallen. Dat wordt hier <b>vooraf</b> vastgesteld met
     * een duidelijke melding, in plaats van halverwege een levering op een databasefout te stranden —
     * en het masker wordt nooit afgekapt, want dan zou een gewijzigde component onzichtbaar worden.
     */
    private static void verifyMaskFits(List<String> componentCodes) {
        int length = ARTICLE_MASK.length() + 1 + PRICE_MASK.length();
        for (String code : componentCodes) {
            length += 1 + PRICE_MASK.length() + COMPONENT_MASK_SEPARATOR.length() + code.length();
        }
        if (length > MAX_DOMAIN_MASK_LENGTH) {
            throw new IllegalArgumentException("The domain mask of " + componentCodes.size()
                    + " price components would need " + length + " characters while domain_mask holds "
                    + MAX_DOMAIN_MASK_LENGTH + "; widen the column before using these component codes. "
                    + "Truncating the mask would hide which price component changed.");
        }
    }

    /**
     * De componentcode komt in de SQL-tekst zelf terecht (het aantal componenten verschilt per
     * revisie), dus ze moet aantoonbaar onschadelijk zijn. Enkel hoofdletters, cijfers en liggende
     * streepjes; alles anders is een programmeerfout en geen bronwaarde om te "ontsnappen".
     */
    private static String verifyComponentCode(String componentCode) {
        if (componentCode == null || !COMPONENT_CODE.matcher(componentCode).matches()) {
            throw new IllegalArgumentException("Price component code '" + componentCode + "' is not a "
                    + "plain code (A-Z, 0-9, _, at most 20 characters) and cannot be used in the domain "
                    + "mask");
        }
        return componentCode;
    }

    private static final String INSERT_MARKER = "insert into import_mutation ("
            + "batch_id, delivery_id, import_link_id, definition_revision_id, task_run_id, "
            + "action_type, target_domain, status, result_summary, idempotency_key, created_at) "
            + "values (?, ?, ?, ?, ?, 'IMPORT_MARKER', 'IMPORT', 'RECORDED', ?, ?, ?)";

    /**
     * De onveranderlijke gegevens van één screening die op elke mutatie meegaan. Bewust een record
     * en geen JPA-entiteit: deze DAO schrijft met JdbcTemplate en mag geen persistence context
     * aanraken.
     */
    public record MutationContext(long batchId, long deliveryId, long importLinkId,
                                  long definitionRevisionId, Long taskRunId, long deliveryFileId) {
    }

    private final JdbcTemplate jdbc;
    private final int chunkSize;

    public MutationDao(JdbcTemplate jdbc,
                       @Value("${catalogimport.screening.mutation-chunk-size:" + DEFAULT_CHUNK_SIZE + "}")
                       int chunkSize) {
        this.jdbc = jdbc;
        this.chunkSize = chunkSize > 0 ? chunkSize : DEFAULT_CHUNK_SIZE;
    }

    /** De geconfigureerde chunkgrootte, zodat de aanroeper dezelfde grens hanteert. */
    public int chunkSize() {
        return chunkSize;
    }

    /** De idempotentiesleutel van de {@code IMPORT_MARKER} van deze levering onder deze revisie. */
    public static String markerKey(long deliveryId, long definitionRevisionId) {
        return deliveryId + ":" + definitionRevisionId + MARKER_KEY_SUFFIX;
    }

    // --- Collisiecontrole tegen de bronstaat ---------------------------------------------------

    /**
     * Zoekt een gestagede regel waarvan de {@code identity_hash} al in de bronstaat van deze
     * koppeling voorkomt, maar met <b>andere</b> sleutelcomponenten. Dat is een hashcollisie: de
     * delta zou dan de prijs van een andere aanbieding overschrijven. De levering blokkeert.
     *
     * @return het laagste betrokken regelnummer, of leeg
     */
    public OptionalLong findSourceStateCollisionRow(long batchId, long importLinkId) {
        List<Long> rows = jdbc.queryForList("select stage.row_number from import_candidate_stage stage "
                + "join catalog_source_state state "
                + "  on state.import_link_id = ? and state.identity_hash = stage.identity_hash "
                + "where stage.batch_id = ? and ("
                + "   stage.identity_supplier <> state.identity_supplier "
                + "or stage.identity_supplier_group <> state.identity_supplier_group "
                + "or stage.identity_supplier_reference <> state.identity_supplier_reference "
                + "or stage.identity_discount_state <> state.identity_discount_state "
                + "or coalesce(stage.identity_discount_code, '') <> coalesce(state.identity_discount_code, '')) "
                + "order by stage.row_number limit 1", Long.class, importLinkId, batchId);
        return rows.isEmpty() ? OptionalLong.empty() : OptionalLong.of(rows.get(0));
    }

    // --- Delta en mutatiegeneratie in chunks ---------------------------------------------------

    /**
     * Het hoogste regelnummer van de volgende chunk gestagede regels ná {@code afterRowNumber}, of
     * {@code null} wanneer er geen regels meer zijn. Chunkgrenzen worden zo uit de data zelf
     * afgeleid; regelnummers zijn fysieke bronregelnummers en dus niet aaneengesloten.
     */
    public Long nextChunkBoundary(long batchId, long afterRowNumber) {
        List<Long> boundary = jdbc.queryForList("select max(chunk.row_number) from ("
                + "select row_number from import_candidate_stage where batch_id = ? and row_number > ? "
                + "order by row_number limit ?) chunk", Long.class, batchId, afterRowNumber, chunkSize);
        return boundary.isEmpty() ? null : boundary.get(0);
    }

    /** Zie {@link #CLASSIFY_CHUNK}. */
    public int classifyChunk(long batchId, long importLinkId, long fromExclusive, long toInclusive) {
        return jdbc.update(CLASSIFY_CHUNK, importLinkId, importLinkId, batchId, fromExclusive, toInclusive);
    }

    /**
     * Zie {@link #insertContentMutations(List)}.
     *
     * @param componentCodes de prijscomponenten die in deze batch voorkomen, gesorteerd
     *                       ({@link CandidatePriceDao#componentCodes(long)}); een lege lijst levert
     *                       exact het fase 2-gedrag op
     */
    public int insertContentMutations(MutationContext context, List<String> componentCodes,
                                      long fromExclusive, long toInclusive, Instant createdAt) {
        return jdbc.update(insertContentMutations(componentCodes), statement -> {
            statement.setLong(1, context.batchId());
            statement.setLong(2, context.deliveryId());
            statement.setLong(3, context.importLinkId());
            statement.setLong(4, context.definitionRevisionId());
            if (context.taskRunId() == null) {
                statement.setNull(5, Types.BIGINT);
            } else {
                statement.setLong(5, context.taskRunId());
            }
            statement.setLong(6, context.deliveryFileId());
            statement.setObject(7, OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
            statement.setLong(8, context.importLinkId());
            statement.setLong(9, context.batchId());
            statement.setLong(10, fromExclusive);
            statement.setLong(11, toInclusive);
        });
    }

    // --- Marker en tellers ---------------------------------------------------------------------

    /**
     * Bestaat de {@code IMPORT_MARKER} van deze levering onder deze revisie al? Dat is het bewijs
     * dat de screening al afgerond is; een tweede screening van dezelfde levering onder dezelfde
     * revisie is dan een conflict, geen nieuwe verwerking. Een <i>herlevering</i> is een nieuwe
     * {@code Delivery} en krijgt dus een andere sleutel — die blokkeert bewust niet.
     */
    public boolean markerExists(long deliveryId, long definitionRevisionId) {
        Long count = jdbc.queryForObject("select count(*) from import_mutation where idempotency_key = ?",
                Long.class, markerKey(deliveryId, definitionRevisionId));
        return count != null && count > 0;
    }

    /**
     * Schrijft de {@code IMPORT_MARKER}: exact één per afgeronde screening (SCREENED of BLOCKED), in
     * dezelfde transactie als de eindtransitie. Een technisch mislukte screening schrijft er geen.
     * Een bestaande marker wordt niet overschreven — de sleutel is uniek en de eerste vaststelling
     * blijft staan.
     *
     * @return {@code 1} als de marker geschreven is, {@code 0} als ze er al was
     */
    public int insertMarker(MutationContext context, String resultSummary, Instant createdAt) {
        String key = markerKey(context.deliveryId(), context.definitionRevisionId());
        if (markerExists(context.deliveryId(), context.definitionRevisionId())) {
            return 0;
        }
        return jdbc.update(INSERT_MARKER, statement -> {
            statement.setLong(1, context.batchId());
            statement.setLong(2, context.deliveryId());
            statement.setLong(3, context.importLinkId());
            statement.setLong(4, context.definitionRevisionId());
            if (context.taskRunId() == null) {
                statement.setNull(5, Types.BIGINT);
            } else {
                statement.setLong(5, context.taskRunId());
            }
            statement.setString(6, truncate(resultSummary, MAX_RESULT_SUMMARY_LENGTH));
            statement.setString(7, key);
            statement.setObject(8, OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
        });
    }

    /**
     * Zet de nog geplande inhoudelijke mutaties van een batch op {@code SKIPPED} met de opgegeven reden
     * (accept-baseline: de bronstaat is aanvaard zonder publicatie). De {@code IMPORT_MARKER} blijft
     * {@code RECORDED}: die legt vast dat de screening plaatsvond en is geen uitvoerbare mutatie.
     * Mutaties in een andere status blijven ongemoeid, zodat herhalen niets verandert.
     *
     * @return het aantal overgezette mutaties
     */
    public int skipPlannedContentMutations(long batchId, String statusReason) {
        // Enkel CREATE/UPDATE en enkel PLANNED: een geblokkeerde mutatie (kritiek referentie-incident)
        // en een incident dat op goedkeuring wacht, blijven onaangeroerd. Een aanvaarding van de
        // nulmeting mag een vastgehouden identiteitswijziging nooit stilzwijgend afsluiten.
        return jdbc.update("update import_mutation set status = 'SKIPPED', status_reason = ? "
                + "where batch_id = ? and action_type in ('CREATE', 'UPDATE') and status = 'PLANNED'",
                statusReason, batchId);
    }

    /**
     * Het aantal <b>inhoudelijke</b> mutaties van deze batch: {@code CREATE} en {@code UPDATE}. De
     * {@code IMPORT_MARKER} telt bewust niet mee, en sinds bouwstap 3f ook een
     * {@code IDENTITY_REFERENCE_INCIDENT} niet: dat is een vaststelling die wacht op goedkeuring en
     * geen voorgestelde wijziging aan een aanbieding. Ze zou het aantal te publiceren mutaties
     * overschatten. Het aantal incidenten staat in {@code import_batch.identity_incident_count}.
     */
    public long countContentMutations(long batchId) {
        Long count = jdbc.queryForObject("select count(*) from import_mutation "
                + "where batch_id = ? and action_type in ('CREATE', 'UPDATE')", Long.class, batchId);
        return count == null ? 0L : count;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
