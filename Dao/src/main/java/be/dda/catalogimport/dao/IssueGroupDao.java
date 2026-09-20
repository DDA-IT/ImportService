package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.ControlLevel;
import be.dda.catalogimport.domain.DeviationDirection;
import be.dda.catalogimport.domain.ImpactScope;
import be.dda.catalogimport.domain.IssueDomain;
import be.dda.catalogimport.domain.IssueIncidentKind;
import be.dda.catalogimport.domain.RowIssueSeverity;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * De samenvatting per foutsignatuur binnen één batch: {@code import_issue_group} (ontwerp fase 3,
 * changeset 004-4, par. 3.1 pass E4, R-THR-04, R-ISS-03).
 *
 * <h2>Waarom het aantal hier staat en niet uit de issuerijen komt</h2>
 * {@code import_row_issue} bevat per foutcode hoogstens {@code max-sample-rows-per-code}
 * voorbeeldrijen (R-ISS-03). Een telling over die tabel zou dus bij 1.000.000 identieke fouten
 * "200" opleveren — een getal dat er precies uitziet als een echte meting en dat volledig fout is.
 * {@code occurrence_count} is daarom het <b>werkelijke</b> aantal, aangeleverd door de pass die het
 * probleem vaststelt (die telt élk voorval, ook het voorval waarvan geen voorbeeld bewaard wordt),
 * en {@code recorded_sample_count} is het aantal wél bewaarde voorbeeldrijen. Die twee lopen bewust
 * uiteen.
 *
 * <h2>Idempotent en hervatbaar</h2>
 * Elke pass verhoogt haar tellers in <b>dezelfde transactie</b> als haar issuerijen en haar
 * voortgangskolom. Valt de verwerking weg tussen twee chunks, dan is ofwel alles van die chunk
 * vastgelegd ofwel niets: een hervatting begint na de laatste vastgelegde chunk en telt dus nooit
 * een voorval dubbel. {@link #accumulate(long, List)} is een {@code insert ... where not exists}
 * gevolgd door een {@code update ... set occurrence_count = occurrence_count + ?}; de unieke
 * sleutel {@code (batch_id, issue_code, signature)} blijft de harde garantie.
 *
 * <h2>Set-based, nooit per regel</h2>
 * Het koppelen van de issuerijen aan hun groep, het hertellen van de voorbeelden en het toepassen
 * van de drempels gebeuren met één {@code update}/{@code select} over de hele batch — nooit met een
 * query per regel en nooit met een groepering in het geheugen over miljoenen rijen.
 *
 * <h2>Transactiegrens</h2>
 * Deze DAO opent zelf geen transactie. Binnen één transactie wordt hier geschreven en nooit via JPA
 * teruggelezen.
 */
@Repository
public class IssueGroupDao {

    /**
     * Eén op te tellen vaststelling. Alles wat de groep gedenormaliseerd bewaart, staat erin: de
     * classificatie mag later niet meer van de (wijzigbare) foutcodecatalogus afhangen (R-ISS-02).
     *
     * @param occurrences        het <b>werkelijke</b> aantal voorvallen dat hierbij komt, niet het
     *                           aantal bewaarde voorbeeldrijen
     * @param firstRowNumber     het laagste bronregelnummer van deze groep, of {@code null}
     * @param priceComponentCode enkel bij {@link IssueIncidentKind#PRICE}
     * @param direction          enkel bij {@link IssueIncidentKind#PRICE}
     * @param referenceType      enkel bij {@link IssueIncidentKind#IDENTITY}
     */
    public record Counter(String issueCode, String signature, RowIssueSeverity severity,
                          IssueDomain issueDomain, ControlLevel controlLevel, ImpactScope impactScope,
                          IssueIncidentKind incidentKind, String priceComponentCode,
                          DeviationDirection direction, String referenceType, long occurrences,
                          Long firstRowNumber, Instant detectedAt) {
    }

    /** Eén groep zoals ze in de database staat; ook het leesmodel van de groepenlijst. */
    public record GroupRow(long id, long batchId, String issueCode, String signature, String severity,
                           String issueDomain, String controlLevel, String impactScope,
                           String incidentKind, long occurrenceCount, int recordedSampleCount,
                           Long scopeRecordCount, BigDecimal sharePercent, boolean bulkIncident,
                           String priceComponentCode, String deviationDirection,
                           BigDecimal dominantFactor, String referenceType, String patternDescription,
                           Long firstRowNumber, Instant firstDetectedAt, Instant lastDetectedAt,
                           String handlingStatus) {
    }

    /** Het werkelijke aantal voorvallen náást het aantal bewaarde voorbeelden, per foutcode. */
    public record CodeTotals(String issueCode, long occurrenceCount, long recordedSampleCount) {
    }

    private static final String COLUMNS = "id, batch_id, issue_code, signature, severity, issue_domain, "
            + "control_level, impact_scope, incident_kind, occurrence_count, recorded_sample_count, "
            + "scope_record_count, share_percent, is_bulk_incident, price_component_code, "
            + "deviation_direction, dominant_factor, reference_type, pattern_description, "
            + "first_row_number, first_detected_at, last_detected_at, handling_status";

    // dominant_factor en pattern_description blijven leeg: patroonherkenning van bulktransformaties
    // hoort niet in fase 3 (aanname A20) en wordt nooit met een geraden waarde ingevuld.
    private static final String INSERT_IF_ABSENT = "insert into import_issue_group ("
            + "batch_id, issue_code, signature, severity, issue_domain, control_level, impact_scope, "
            + "incident_kind, occurrence_count, recorded_sample_count, price_component_code, "
            + "deviation_direction, reference_type, first_row_number, first_detected_at, "
            + "last_detected_at) "
            + "select cast(? as bigint), cast(? as varchar(60)), cast(? as varchar(300)), "
            + "cast(? as varchar(20)), cast(? as varchar(40)), cast(? as varchar(20)), "
            + "cast(? as varchar(20)), cast(? as varchar(30)), 0, 0, cast(? as varchar(20)), "
            + "cast(? as varchar(10)), cast(? as varchar(30)), cast(? as bigint), "
            + "cast(? as timestamp with time zone), cast(? as timestamp with time zone) "
            + "where not exists (select 1 from import_issue_group existing "
            + "  where existing.batch_id = ? and existing.issue_code = ? and existing.signature = ?)";

    // Het laagste regelnummer wint: de voorbeeldrijen zijn ook de laagste regelnummers, dus de groep
    // en haar voorbeelden wijzen naar dezelfde eerste voorkomst.
    private static final String ADD_OCCURRENCES = "update import_issue_group "
            + "set occurrence_count = occurrence_count + ?, "
            + "    last_detected_at = ?, "
            + "    first_row_number = case when first_row_number is null then ? "
            + "        when ? is null then first_row_number "
            + "        when ? < first_row_number then ? else first_row_number end "
            + "where batch_id = ? and issue_code = ? and signature = ?";

    private final JdbcTemplate jdbc;

    public IssueGroupDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Telt de opgegeven vaststellingen bij hun groep op; onbestaande groepen worden eerst
     * aangemaakt. Beide stappen zijn set-based batches en samen idempotent binnen één transactie:
     * dezelfde chunk wordt nooit twee keer opgeteld, want het hervatpunt van de pass wordt in
     * dezelfde transactie bijgewerkt.
     *
     * @return het aantal opgetelde tellers
     */
    public int accumulate(long batchId, List<Counter> counters) {
        if (counters.isEmpty()) {
            return 0;
        }
        jdbc.batchUpdate(INSERT_IF_ABSENT, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                Counter counter = counters.get(index);
                OffsetDateTime detectedAt = OffsetDateTime.ofInstant(counter.detectedAt(), ZoneOffset.UTC);
                statement.setLong(1, batchId);
                statement.setString(2, counter.issueCode());
                statement.setString(3, counter.signature());
                statement.setString(4, counter.severity().name());
                statement.setString(5, counter.issueDomain().name());
                statement.setString(6, counter.controlLevel().name());
                statement.setString(7, counter.impactScope().name());
                statement.setString(8, counter.incidentKind().name());
                setNullable(statement, 9, counter.priceComponentCode());
                setNullable(statement, 10,
                        counter.direction() == null ? null : counter.direction().name());
                setNullable(statement, 11, counter.referenceType());
                setNullableLong(statement, 12, counter.firstRowNumber());
                statement.setObject(13, detectedAt);
                statement.setObject(14, detectedAt);
                statement.setLong(15, batchId);
                statement.setString(16, counter.issueCode());
                statement.setString(17, counter.signature());
            }

            @Override
            public int getBatchSize() {
                return counters.size();
            }
        });
        int[] updated = jdbc.batchUpdate(ADD_OCCURRENCES, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                Counter counter = counters.get(index);
                statement.setLong(1, counter.occurrences());
                statement.setObject(2, OffsetDateTime.ofInstant(counter.detectedAt(), ZoneOffset.UTC));
                for (int position : new int[] {3, 4, 5, 6}) {
                    setNullableLong(statement, position, counter.firstRowNumber());
                }
                statement.setLong(7, batchId);
                statement.setString(8, counter.issueCode());
                statement.setString(9, counter.signature());
            }

            @Override
            public int getBatchSize() {
                return counters.size();
            }
        });
        int total = 0;
        for (int count : updated) {
            total += Math.max(count, 0);
        }
        return total;
    }

    /**
     * Koppelt elke issuerij met een signatuur aan haar groep (pass E4). Eén {@code update} over de
     * hele batch; rijen zonder signatuur (een blokkade die per definitie hoogstens één keer
     * voorkomt) blijven bewust los.
     *
     * @return het aantal gekoppelde rijen
     */
    public int linkIssueRows(long batchId) {
        return jdbc.update("update import_row_issue set issue_group_id = ("
                + "select g.id from import_issue_group g "
                + " where g.batch_id = import_row_issue.batch_id "
                + "   and g.issue_code = import_row_issue.issue_code "
                + "   and g.signature = import_row_issue.signature) "
                + "where batch_id = ? and signature is not null and issue_group_id is null "
                + "  and exists (select 1 from import_issue_group g "
                + "    where g.batch_id = import_row_issue.batch_id "
                + "      and g.issue_code = import_row_issue.issue_code "
                + "      and g.signature = import_row_issue.signature)", batchId);
    }

    /**
     * Koppelt een samenvattende melding (een bulkincident) aan de groep die ze beschrijft. De
     * foutcode van zo'n melding verschilt van die van de groep; ze telt daarom niet mee als
     * voorbeeldrij.
     */
    public int linkIssueRowsToGroup(long batchId, long issueGroupId, String issueCode,
                                    String signature) {
        return jdbc.update("update import_row_issue set issue_group_id = ? "
                        + "where batch_id = ? and issue_code = ? and signature = ? "
                        + "and issue_group_id is null",
                issueGroupId, batchId, issueCode, signature);
    }

    /**
     * Hertelt per groep het aantal bewaarde voorbeeldrijen. Bewust hertellen en niet optellen: dan
     * levert een tweede doorloop van pass E4 exact hetzelfde getal op. Alleen rijen met <b>dezelfde
     * foutcode</b> tellen mee, zodat een samenvattende bulkmelding geen voorbeeldrij wordt.
     */
    public int refreshSampleCounts(long batchId) {
        return jdbc.update("update import_issue_group set recorded_sample_count = ("
                + "select count(*) from import_row_issue ri "
                + " where ri.issue_group_id = import_issue_group.id "
                + "   and ri.issue_code = import_issue_group.issue_code) "
                + "where batch_id = ?", batchId);
    }

    /**
     * Legt de gecontroleerde scope, het aandeel en het bulkoordeel van één groep vast. Het aandeel
     * wordt in de Service-laag in decimale rekenkunde bepaald (R-THR-03/R-THR-04): hier wordt niets
     * berekend en niets afgerond.
     */
    public int applyThreshold(long groupId, Long scopeRecordCount, BigDecimal sharePercent,
                              boolean bulkIncident) {
        return jdbc.update("update import_issue_group set scope_record_count = ?, share_percent = ?, "
                        + "is_bulk_incident = ? where id = ?",
                statement -> {
                    setNullableLong(statement, 1, scopeRecordCount);
                    if (sharePercent == null) {
                        statement.setNull(2, Types.NUMERIC);
                    } else {
                        statement.setBigDecimal(2, sharePercent);
                    }
                    statement.setBoolean(3, bulkIncident);
                    statement.setLong(4, groupId);
                });
    }

    /**
     * Verwijdert de groepen die de groeperingsdrempel niet halen (R-THR-04: groeperen vanaf
     * {@code minOccurrences} gelijke signaturen), maar <b>alleen</b> wanneer hun aantal ook zonder
     * groep bewaard blijft — dat wil zeggen: wanneer élk voorval nog een eigen voorbeeldrij heeft.
     * Is er boven de voorbeeldcap iets weggelaten, dan blijft de groep staan: anders zou het
     * werkelijke aantal nergens meer te vinden zijn.
     *
     * @return het aantal verwijderde groepen
     */
    public int deleteBelowThreshold(long batchId, int minOccurrences) {
        // Eerst de verwijzingen losmaken: de foreign key van import_row_issue mag nooit een wees
        // krijgen, en de rijen zelf blijven onverkort bestaan.
        jdbc.update("update import_row_issue set issue_group_id = null "
                + "where batch_id = ? and issue_group_id in ("
                + "select id from import_issue_group where batch_id = ? and occurrence_count < ? "
                + "  and occurrence_count <= recorded_sample_count)", batchId, batchId, minOccurrences);
        jdbc.update("update import_mutation set issue_group_id = null "
                + "where batch_id = ? and issue_group_id in ("
                + "select id from import_issue_group where batch_id = ? and occurrence_count < ? "
                + "  and occurrence_count <= recorded_sample_count)", batchId, batchId, minOccurrences);
        return jdbc.update("delete from import_issue_group where batch_id = ? and occurrence_count < ? "
                + "and occurrence_count <= recorded_sample_count", batchId, minOccurrences);
    }

    /** Alle groepen van deze batch, oplopend op foutcode en signatuur; voor pass E4 zelf. */
    public List<GroupRow> findByBatchId(long batchId) {
        return jdbc.query("select " + COLUMNS + " from import_issue_group where batch_id = ? "
                + "order by issue_code, signature", (resultSet, index) -> map(resultSet), batchId);
    }

    /** Eén pagina van de groepenlijst, oplopend op id (= volgorde van ontstaan). */
    public List<GroupRow> findPage(long batchId, int offset, int limit) {
        return jdbc.query("select " + COLUMNS + " from import_issue_group where batch_id = ? "
                        + "order by id limit ? offset ?", (resultSet, index) -> map(resultSet),
                batchId, limit, offset);
    }

    public long countByBatchId(long batchId) {
        Long count = jdbc.queryForObject("select count(*) from import_issue_group where batch_id = ?",
                Long.class, batchId);
        return count == null ? 0L : count;
    }

    /** Het aantal vastgestelde bulkincidenten; komt op {@code import_batch.bulk_incident_count}. */
    public long countBulkIncidents(long batchId) {
        Long count = jdbc.queryForObject("select count(*) from import_issue_group "
                + "where batch_id = ? and is_bulk_incident = true", Long.class, batchId);
        return count == null ? 0L : count;
    }

    /**
     * Het werkelijke aantal voorvallen náást het aantal bewaarde voorbeelden, per foutcode. De basis
     * voor één {@code ROW_ISSUE_RECORDING_CAPPED}-melding per batch en foutcode, in plaats van één
     * deelmelding per pass.
     */
    public List<CodeTotals> totalsByCode(long batchId) {
        return jdbc.query("select issue_code, sum(occurrence_count), sum(recorded_sample_count) "
                        + "from import_issue_group where batch_id = ? group by issue_code "
                        + "order by issue_code",
                (resultSet, index) -> new CodeTotals(resultSet.getString(1), resultSet.getLong(2),
                        resultSet.getLong(3)), batchId);
    }

    /**
     * Verwijdert de groepen van één batch. Uitsluitend voor een technisch mislukte of onderbroken
     * poging, samen met de issuerijen zelf; een geblokkeerde batch behoudt haar samenvatting als
     * bewijsmateriaal.
     */
    public int deleteByBatchId(long batchId) {
        jdbc.update("update import_row_issue set issue_group_id = null where batch_id = ?", batchId);
        jdbc.update("update import_mutation set issue_group_id = null where batch_id = ?", batchId);
        return jdbc.update("delete from import_issue_group where batch_id = ?", batchId);
    }

    private static GroupRow map(ResultSet resultSet) throws SQLException {
        return new GroupRow(resultSet.getLong(1), resultSet.getLong(2), resultSet.getString(3),
                resultSet.getString(4), resultSet.getString(5), resultSet.getString(6),
                resultSet.getString(7), resultSet.getString(8), resultSet.getString(9),
                resultSet.getLong(10), resultSet.getInt(11), nullableLong(resultSet, 12),
                resultSet.getBigDecimal(13), resultSet.getBoolean(14), resultSet.getString(15),
                resultSet.getString(16), resultSet.getBigDecimal(17), resultSet.getString(18),
                resultSet.getString(19), nullableLong(resultSet, 20), instant(resultSet, 21),
                instant(resultSet, 22), resultSet.getString(23));
    }

    private static Instant instant(ResultSet resultSet, int index) throws SQLException {
        OffsetDateTime value = resultSet.getObject(index, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(ResultSet resultSet, int index) throws SQLException {
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

    private static void setNullableLong(PreparedStatement statement, int index, Long value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }
}
