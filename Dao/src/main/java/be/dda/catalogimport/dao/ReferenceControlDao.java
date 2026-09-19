package be.dda.catalogimport.dao;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * De lees- en schrijfkant van de controle op kritieke koppelreferenties (ontwerp fase 3, par. 3.1
 * stap D1 en E2, par. 3.2; R-ID-03/R-ID-04 en R-REF-02..R-REF-05, R-REF-09).
 *
 * <h2>Waarom set-based</h2>
 * Een levering kan een miljoen regels met meerdere referenties bevatten; een query per regel zou
 * miljoenen round trips kosten en de 15-minutennorm (businessanalyse par. 14.23.7) onhaalbaar maken.
 * Daarom levert <b>één</b> query per chunk alle gegevens die de beoordeling nodig heeft:
 * <ul>
 *   <li>de gemapte referenties van de chunk ({@code import_candidate_reference});</li>
 *   <li>de bronstaatrij van dezelfde aanbiedingsidentiteit binnen <i>deze</i> importkoppeling —
 *       {@code null} betekent een nieuwe aanbieding;</li>
 *   <li>de actieve waarde die díe aanbieding vandaag voor dat referentietype draagt;</li>
 *   <li>de aanbieding die diezelfde geleverde waarde vandaag actief draagt binnen <i>dezelfde
 *       bibliotheek</i> ({@code catalog_reference_state}, join op
 *       {@code (library_code, reference_type, value_normalised)} — precies de voorste kolommen van
 *       {@code uk_catalog_reference_state_active}).</li>
 * </ul>
 * De bibliotheekcode komt van de {@code ImportLink} en wordt één keer per batch meegegeven; de
 * uniciteit van een koppelreferentie geldt namelijk per <b>bibliotheek</b> en niet per koppeling
 * (businessanalyse par. 14.23.3).
 *
 * <h2>Deze DAO oordeelt niet</h2>
 * Welke uitkomst een combinatie oplevert, staat exact één keer in de Service-laag
 * ({@code ReferenceControlEvaluator}). Hier staat uitsluitend het ophalen en het wegschrijven: twee
 * implementaties van één identiteitsregel is één te veel.
 *
 * <h2>Alleen wat verandert wordt beoordeeld</h2>
 * Uitsluitend regels met classificatie {@code NEW} of {@code CHANGED} worden opgehaald. Een
 * {@code UNCHANGED}-regel levert byte-identiek dezelfde inhoud als wat eerder aanvaard is: er wordt
 * niets nieuws opgeëist, dus er valt niets te beoordelen. Zonder die grens zou een aanbieding die
 * volgens matchingstap 2 (R-ID-03) bewust naar het artikel van een andere aanbieding verwijst, bij
 * élke volgende levering opnieuw een incident opleveren en nooit meer kunnen doorstromen.
 *
 * <h2>Transactiegrens</h2>
 * Deze DAO opent zelf geen transactie; de screeningservice bepaalt de chunkgrens en werkt
 * {@code import_batch.reference_progress_row_number} in dezelfde transactie bij. Binnen één
 * transactie wordt hier geschreven en nooit via JPA teruggelezen.
 */
@Repository
public class ReferenceControlDao {

    /** Standaard chunkgrootte van de referentiecontrolepass; zelfde grootteorde als de andere passes. */
    public static final int DEFAULT_CHUNK_SIZE = MutationDao.DEFAULT_CHUNK_SIZE;

    /** Achtervoegsel van de idempotentiesleutel van een referentie-incident: {@code ...:REFERENCE:EAN}. */
    public static final String REFERENCE_KEY_SUFFIX = ":REFERENCE:";

    /** {@code import_mutation.result_summary} is varchar(1000). */
    private static final int MAX_RESULT_SUMMARY_LENGTH = MutationDao.MAX_RESULT_SUMMARY_LENGTH;
    /** {@code import_mutation.status_reason} is varchar(200). */
    private static final int MAX_STATUS_REASON_LENGTH = 200;

    /**
     * Eén te beoordelen combinatie van bronregel en referentietype, met alles wat het oordeel nodig
     * heeft. Er kunnen meerdere rijen voor dezelfde {@code (rowNumber, referenceType)} terugkomen
     * wanneer een aanbieding onverhoopt meer dan één actieve waarde voor dat type draagt; dat is zelf
     * een dubbelzinnige koppeling en wordt door de Service-laag als zodanig beoordeeld.
     *
     * @param ownStateId    de bronstaatrij van deze aanbiedingsidentiteit; {@code null} = nieuw
     * @param activeValue   de actieve waarde die deze aanbieding vandaag voor dit type draagt
     * @param holderStateId de aanbieding die de <i>geleverde</i> waarde vandaag actief draagt binnen
     *                      dezelfde bibliotheek; {@code null} = de waarde is nergens actief
     */
    public record ReferenceCandidate(long rowNumber, String referenceType, String valueRaw,
                                     String valueNormalised, boolean empty, String classification,
                                     Long ownStateId, String activeValue, Long holderStateId) {
    }

    /** Een regel die bij een dubbele referentiewaarde binnen dezelfde levering betrokken is. */
    public record DuplicateReferenceRow(long rowNumber, String referenceType, String valueNormalised) {
    }

    /** De vastgestelde uitkomst van één referentie; wordt teruggeschreven als audit. */
    public record MatchUpdate(long rowNumber, String referenceType, String matchResult,
                              Long matchedSourceStateId) {
    }

    /**
     * Eén vast te leggen kritiek referentie-incident.
     *
     * @param statusReason  het soort incident ({@code CHANGED}, {@code REMOVED}, {@code REUSED},
     *                      {@code AMBIGUOUS})
     * @param beforeValue   de genormaliseerde waarde die actief was, of {@code null}
     * @param afterValue    de genormaliseerde waarde uit de levering, of {@code null} bij
     *                      "gemapt maar leeg"
     * @param resultSummary de melding in de stijl van par. 15.12, met veldnaam, oude en nieuwe waarde
     */
    public record IncidentMutation(long rowNumber, String referenceType, String statusReason,
                                   String beforeValue, String afterValue, String resultSummary) {
    }

    /**
     * De te beoordelen referenties van één chunk. De bibliotheekcode en de koppeling staan vooraan in
     * de join-voorwaarden, zodat twee koppelingen met dezelfde leverancierssleutel elkaars bronstaat
     * nooit zien en twee bibliotheken elkaars referenties nooit zien.
     */
    private static final String SELECT_CANDIDATES = "select ref.row_number, ref.reference_type, "
            + "ref.value_raw, ref.value_normalised, ref.is_empty, stage.classification, own.id, "
            + "active.value_normalised, holder.source_state_id "
            + "from import_candidate_reference ref "
            + "join import_candidate_stage stage "
            + "  on stage.batch_id = ref.batch_id and stage.row_number = ref.row_number "
            + "left join catalog_source_state own "
            + "  on own.import_link_id = ? and own.identity_hash = stage.identity_hash "
            + "left join catalog_reference_state active "
            + "  on active.source_state_id = own.id and active.reference_type = ref.reference_type "
            + " and active.active_marker = true "
            + "left join catalog_reference_state holder "
            + "  on holder.library_code = ? and holder.reference_type = ref.reference_type "
            + " and holder.value_normalised = ref.value_normalised and holder.active_marker = true "
            + "where ref.batch_id = ? and ref.row_number > ? and ref.row_number <= ? "
            + "  and stage.classification in ('NEW', 'CHANGED') "
            + "order by ref.row_number, ref.reference_type";

    /**
     * Dezelfde genormaliseerde waarde van hetzelfde referentietype bij twee of meer <b>verschillende</b>
     * aanbiedingsidentiteiten binnen één levering (D1). Dat is nooit op te lossen met "laatste wint":
     * élke betrokken regel is verdacht, want welke aanbieding het artikel werkelijk is, valt niet vast
     * te stellen.
     */
    private static final String DUPLICATE_GROUPS = "select r.reference_type, r.value_normalised "
            + "from import_candidate_reference r "
            + "join import_candidate_stage s on s.batch_id = r.batch_id and s.row_number = r.row_number "
            + "where r.batch_id = ? and r.is_empty = false "
            + "group by r.reference_type, r.value_normalised "
            + "having count(distinct s.identity_hash) > 1";

    private static final String INSERT_INCIDENT = "insert into import_mutation ("
            + "batch_id, delivery_id, import_link_id, definition_revision_id, task_run_id, "
            + "action_type, target_domain, status, status_reason, "
            + "identity_supplier, identity_supplier_group, identity_supplier_reference, "
            + "identity_discount_code, identity_discount_state, identity_hash, "
            + "reference_type, before_reference_value, after_reference_value, "
            + "delivery_file_id, source_row_number, result_summary, idempotency_key, created_at) "
            + "select cast(? as bigint), cast(? as bigint), cast(? as bigint), cast(? as bigint), "
            + "cast(? as bigint), 'IDENTITY_REFERENCE_INCIDENT', 'OFFER', 'AWAITING_APPROVAL', "
            + "cast(? as varchar(200)), "
            + "stage.identity_supplier, stage.identity_supplier_group, stage.identity_supplier_reference, "
            + "stage.identity_discount_code, stage.identity_discount_state, stage.identity_hash, "
            + "cast(? as varchar(30)), cast(? as varchar(200)), cast(? as varchar(200)), "
            + "cast(? as bigint), stage.row_number, cast(? as varchar(1000)), "
            + "stage.mutation_key_prefix || cast(? as varchar(60)), "
            + "cast(? as timestamp with time zone) "
            + "from import_candidate_stage stage "
            + "where stage.batch_id = ? and stage.row_number = ? "
            + "  and not exists (select 1 from import_mutation existing "
            + "      where existing.idempotency_key = stage.mutation_key_prefix "
            + "            || cast(? as varchar(60)))";

    private final JdbcTemplate jdbc;
    private final int chunkSize;

    public ReferenceControlDao(JdbcTemplate jdbc,
                               @Value("${catalogimport.screening.reference-control-chunk-size:"
                                       + DEFAULT_CHUNK_SIZE + "}") int chunkSize) {
        this.jdbc = jdbc;
        this.chunkSize = chunkSize > 0 ? chunkSize : DEFAULT_CHUNK_SIZE;
    }

    /** De geconfigureerde chunkgrootte van deze pass. */
    public int chunkSize() {
        return chunkSize;
    }

    /**
     * Het hoogste regelnummer van de volgende chunk gestagede regels ná {@code afterRowNumber}, of
     * {@code null} wanneer er geen regels meer zijn. Eigen chunkgrens, want deze pass heeft haar eigen
     * hervatpunt ({@code import_batch.reference_progress_row_number}).
     */
    public Long nextChunkBoundary(long batchId, long afterRowNumber) {
        List<Long> boundary = jdbc.queryForList("select max(chunk.row_number) from ("
                + "select row_number from import_candidate_stage where batch_id = ? and row_number > ? "
                + "order by row_number limit ?) chunk", Long.class, batchId, afterRowNumber, chunkSize);
        return boundary.isEmpty() ? null : boundary.get(0);
    }

    /**
     * De logische veldnaam per referentietype uit {@code import_field_catalog} (meldingsstijl
     * par. 15.12). Exact één query per batch — nooit per regel.
     */
    public Map<String, String> fieldNameByReferenceType() {
        Map<String, String> names = new LinkedHashMap<>();
        jdbc.query("select reference_type, name from import_field_catalog "
                        + "where reference_type is not null order by sort_order",
                (org.springframework.jdbc.core.RowCallbackHandler) resultSet ->
                        names.putIfAbsent(resultSet.getString(1), resultSet.getString(2)));
        return names;
    }

    /** Zie {@link #SELECT_CANDIDATES}. */
    public List<ReferenceCandidate> findCandidates(long batchId, long importLinkId, String libraryCode,
                                                   long fromExclusive, long toInclusive) {
        return jdbc.query(SELECT_CANDIDATES,
                statement -> {
                    statement.setLong(1, importLinkId);
                    statement.setString(2, libraryCode);
                    statement.setLong(3, batchId);
                    statement.setLong(4, fromExclusive);
                    statement.setLong(5, toInclusive);
                },
                (resultSet, index) -> new ReferenceCandidate(resultSet.getLong(1), resultSet.getString(2),
                        resultSet.getString(3), resultSet.getString(4), resultSet.getBoolean(5),
                        resultSet.getString(6), nullableLong(resultSet, 7), resultSet.getString(8),
                        nullableLong(resultSet, 9)));
    }

    // --- D1: dubbele referentiewaarde binnen één levering ---------------------------------------

    /** Het aantal <b>regels</b> dat bij een dubbele referentiewaarde binnen deze levering betrokken is. */
    public long countDuplicateReferenceRows(long batchId) {
        Long count = jdbc.queryForObject("select count(*) from import_candidate_reference ref "
                + "join (" + DUPLICATE_GROUPS + ") dup "
                + "  on dup.reference_type = ref.reference_type "
                + " and dup.value_normalised = ref.value_normalised "
                + "where ref.batch_id = ? and ref.is_empty = false", Long.class, batchId, batchId);
        return count == null ? 0L : count;
    }

    /**
     * De betrokken regels, oplopend op regelnummer en begrensd tot {@code limit} (de voorbeeldcap per
     * foutcode): bij duizenden dubbele referenties mogen er geen duizenden issuerijen ontstaan. Het
     * volledige aantal blijft via {@link #countDuplicateReferenceRows(long)} en
     * {@code identity_incident_count} bewaard.
     */
    public List<DuplicateReferenceRow> findDuplicateReferenceRows(long batchId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return jdbc.query("select ref.row_number, ref.reference_type, ref.value_normalised "
                        + "from import_candidate_reference ref "
                        + "join (" + DUPLICATE_GROUPS + ") dup "
                        + "  on dup.reference_type = ref.reference_type "
                        + " and dup.value_normalised = ref.value_normalised "
                        + "where ref.batch_id = ? and ref.is_empty = false "
                        + "order by ref.row_number, ref.reference_type limit ?",
                (resultSet, index) -> new DuplicateReferenceRow(resultSet.getLong(1),
                        resultSet.getString(2), resultSet.getString(3)),
                batchId, batchId, limit);
    }

    /**
     * Houdt élke regel vast die bij een dubbele referentiewaarde betrokken is (R-REF-09): nooit
     * "laatste wint", nooit stil één van beide kiezen. De update is idempotent en overschrijft een
     * reeds gezette classificatie van deze soort niet.
     *
     * @return het aantal vastgehouden regels
     */
    public int classifyDuplicateReferences(long batchId, String identityIncidentClassification) {
        // "classification is null" hoort er uitdrukkelijk bij: D1 draait vóór de classificatiepass,
        // dus op dat moment dragen de regels nog geen classificatie. Zonder die tak zou
        // "classification <> ?" op NULL uitkomen en geen enkele regel vasthouden.
        return jdbc.update("update import_candidate_stage set classification = ? "
                + "where batch_id = ? and (classification is null or classification <> ?) "
                + "and row_number in ("
                + "select ref.row_number from import_candidate_reference ref "
                + "join (" + DUPLICATE_GROUPS + ") dup "
                + "  on dup.reference_type = ref.reference_type "
                + " and dup.value_normalised = ref.value_normalised "
                + "where ref.batch_id = ? and ref.is_empty = false)",
                identityIncidentClassification, batchId, identityIncidentClassification, batchId, batchId);
    }

    // --- E2: wegschrijven van de uitkomst -------------------------------------------------------

    /**
     * Legt de vastgestelde uitkomst per referentie vast als audit. Regels die niet beoordeeld zijn
     * ({@code UNCHANGED}, of al vastgehouden door D1) houden bewust {@code match_result = null}: dat
     * is "niet beoordeeld" en nooit stil "in orde".
     */
    public int markMatchResults(long batchId, List<MatchUpdate> updates) {
        if (updates.isEmpty()) {
            return 0;
        }
        int[] counts = jdbc.batchUpdate("update import_candidate_reference "
                + "set match_result = ?, matched_source_state_id = ? "
                + "where batch_id = ? and row_number = ? and reference_type = ?",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement statement, int index) throws SQLException {
                        MatchUpdate update = updates.get(index);
                        statement.setString(1, update.matchResult());
                        if (update.matchedSourceStateId() == null) {
                            statement.setNull(2, Types.BIGINT);
                        } else {
                            statement.setLong(2, update.matchedSourceStateId());
                        }
                        statement.setLong(3, batchId);
                        statement.setLong(4, update.rowNumber());
                        statement.setString(5, update.referenceType());
                    }

                    @Override
                    public int getBatchSize() {
                        return updates.size();
                    }
                });
        return sum(counts);
    }

    /**
     * Houdt de opgegeven regels vast met classificatie {@code IDENTITY_INCIDENT} (R-REF-09). Zo'n
     * regel wordt door {@code accept-baseline} nooit in de bronstaat aanvaard en haar inhoudelijke
     * mutatie krijgt status {@code BLOCKED}.
     */
    public int classifyIdentityIncidents(long batchId, List<Long> rowNumbers,
                                         String identityIncidentClassification) {
        if (rowNumbers.isEmpty()) {
            return 0;
        }
        int[] counts = jdbc.batchUpdate("update import_candidate_stage set classification = ? "
                + "where batch_id = ? and row_number = ? "
                + "and (classification is null or classification <> ?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement statement, int index) throws SQLException {
                        statement.setString(1, identityIncidentClassification);
                        statement.setLong(2, batchId);
                        statement.setLong(3, rowNumbers.get(index));
                        statement.setString(4, identityIncidentClassification);
                    }

                    @Override
                    public int getBatchSize() {
                        return rowNumbers.size();
                    }
                });
        return sum(counts);
    }

    /**
     * Schrijft per incident één {@code IDENTITY_REFERENCE_INCIDENT}-mutatie met status
     * {@code AWAITING_APPROVAL}. De identiteit, de identiteitshash en het idempotentievoorvoegsel
     * komen rechtstreeks uit de staging — er wordt dus nooit een identiteit in Java opnieuw
     * samengesteld.
     * <p>
     * <b>Idempotent.</b> De sleutel is {@code <delivery>:<revisie>:<identiteitshash-hex>:REFERENCE:<type>}
     * en de insert slaat over wat die sleutel al heeft; {@code uk_import_mutation_idempotency} blijft
     * de harde garantie. Een hervatte pass schrijft dus nooit een tweede incident voor dezelfde
     * aanbieding en hetzelfde referentietype.
     *
     * @return het aantal werkelijk geschreven incidenten
     */
    public int insertIncidentMutations(MutationDao.MutationContext context,
                                       List<IncidentMutation> incidents, Instant createdAt) {
        if (incidents.isEmpty()) {
            return 0;
        }
        int[] counts = jdbc.batchUpdate(INSERT_INCIDENT, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                IncidentMutation incident = incidents.get(index);
                String suffix = REFERENCE_KEY_SUFFIX + incident.referenceType();
                statement.setLong(1, context.batchId());
                statement.setLong(2, context.deliveryId());
                statement.setLong(3, context.importLinkId());
                statement.setLong(4, context.definitionRevisionId());
                if (context.taskRunId() == null) {
                    statement.setNull(5, Types.BIGINT);
                } else {
                    statement.setLong(5, context.taskRunId());
                }
                statement.setString(6, truncate(incident.statusReason(), MAX_STATUS_REASON_LENGTH));
                statement.setString(7, incident.referenceType());
                setNullable(statement, 8, incident.beforeValue());
                setNullable(statement, 9, incident.afterValue());
                statement.setLong(10, context.deliveryFileId());
                statement.setString(11, truncate(incident.resultSummary(), MAX_RESULT_SUMMARY_LENGTH));
                statement.setString(12, suffix);
                statement.setObject(13, OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
                statement.setLong(14, context.batchId());
                statement.setLong(15, incident.rowNumber());
                statement.setString(16, suffix);
            }

            @Override
            public int getBatchSize() {
                return incidents.size();
            }
        });
        return sum(counts);
    }

    /** Het aantal reeds geschreven referentie-incidenten van deze batch; voor tellers en hervatting. */
    public long countIncidentMutations(long batchId) {
        Long count = jdbc.queryForObject("select count(*) from import_mutation "
                + "where batch_id = ? and action_type = 'IDENTITY_REFERENCE_INCIDENT'", Long.class, batchId);
        return count == null ? 0L : count;
    }

    private static Long nullableLong(java.sql.ResultSet resultSet, int index) throws SQLException {
        long value = resultSet.getLong(index);
        return resultSet.wasNull() ? null : value;
    }

    private static void setNullable(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static int sum(int[] counts) {
        int total = 0;
        for (int count : counts) {
            // SUCCESS_NO_INFO (-2) telt als geslaagd zonder aantal; H2 en PostgreSQL geven aantallen.
            total += Math.max(count, 0);
        }
        return total;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
