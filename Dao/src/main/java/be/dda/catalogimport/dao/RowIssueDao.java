package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.ControlLevel;
import be.dda.catalogimport.domain.ImpactScope;
import be.dda.catalogimport.domain.IssueDomain;
import be.dda.catalogimport.domain.IssueHandlingStatus;
import be.dda.catalogimport.domain.RowIssueSeverity;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Bulkopslag van vastgestelde problemen in {@code import_row_issue} (fase 2 design par. 5, fase 3
 * changeset 004-12).
 * <p>
 * <b>Niet alleen regelproblemen.</b> Ondanks de tabelnaam draagt deze tabel sinds fase 3 alle drie de
 * controleniveaus uit {@link ControlLevel}: {@code rowNumber} en {@code deliveryFileId} mogen daarom
 * {@code null} zijn voor een probleem dat de hele levering of het bestandscontract raakt.
 * <p>
 * <b>Classificatie komt altijd van buiten.</b> Ernst, domein, niveau en impactscope worden
 * gedenormaliseerd op elke rij bewaard (R-ISS-02) en staan verplicht in {@link IssueRow}. De
 * foutcodecatalogus die ze bepaalt leeft in de Service-laag; deze DAO weigert enkel een rij zonder
 * classificatie, zodat er nooit een ongeclassificeerd probleem in de database belandt.
 * <p>
 * Schrijven gebeurt met {@code batchUpdate} in dezelfde microbatchtransactie als de staging, zodat
 * een gestagede regel en haar problemen nooit uit elkaar lopen. Lezen (paginering) gebeurt via de
 * JPA-entiteit {@code ImportRowIssue}; binnen één transactie wordt hier nooit geschreven en
 * tegelijk via JPA teruggelezen.
 * <p>
 * {@code source_value} is bewust afgekapt tot {@value #MAX_SOURCE_VALUE_LENGTH} tekens: de
 * volledige brontekst blijft uitsluitend in het gearchiveerde bestand staan.
 */
@Repository
public class RowIssueDao {

    /** {@code issue_code} is varchar(60); een samengestelde code wordt afgekapt, niet geweigerd. */
    public static final int MAX_ISSUE_CODE_LENGTH = 60;
    /** {@code field_name} is varchar(200). */
    public static final int MAX_FIELD_NAME_LENGTH = 200;
    /** {@code source_value} is varchar(200) en bevat een fragment, geen volledige bronregel. */
    public static final int MAX_SOURCE_VALUE_LENGTH = 200;
    /** {@code expected_value} is varchar(200). */
    public static final int MAX_EXPECTED_VALUE_LENGTH = 200;
    /** {@code message} is varchar(500). */
    public static final int MAX_MESSAGE_LENGTH = 500;
    /** {@code signature} is varchar(300); zie {@code IssueSignature} in de Service-laag. */
    public static final int MAX_SIGNATURE_LENGTH = 300;

    private static final String INSERT = "insert into import_row_issue ("
            + "batch_id, delivery_file_id, row_number, issue_code, field_name, severity, issue_domain, "
            + "control_level, impact_scope, handling_status, source_value, expected_value, message, "
            + "signature, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /**
     * Eén te bewaren probleem.
     *
     * @param deliveryFileId {@code null} wanneer het probleem niet aan één bronbestand hangt
     * @param rowNumber      {@code null} bij een probleem op leverings- of structuurniveau; nooit 0
     *                       als plaatsvervanger
     * @param expectedValue  wat er verwacht werd, náást {@code sourceValue}; nooit stil toegepast
     * @param signature      de foutsignatuur waarop pass E4 groepeert (bouwstap 3g), of {@code null}
     *                       voor een probleem dat per definitie hoogstens één keer voorkomt. De
     *                       signatuur wordt hier vastgelegd en niet later herleid: ze bevat gegevens
     *                       (prijscomponent, richting, soort referentie-incident) die niet allemaal
     *                       op de rij staan
     */
    public record IssueRow(
            long batchId,
            Long deliveryFileId,
            Long rowNumber,
            String issueCode,
            String fieldName,
            RowIssueSeverity severity,
            IssueDomain issueDomain,
            ControlLevel controlLevel,
            ImpactScope impactScope,
            IssueHandlingStatus handlingStatus,
            String sourceValue,
            String expectedValue,
            String message,
            String signature,
            Instant createdAt) {

        public IssueRow {
            Objects.requireNonNull(issueCode, "issueCode");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(createdAt, "createdAt");
            // Een ongeclassificeerd probleem is een programmeerfout: zonder ernst en domein is later
            // niet meer vast te stellen hoe zwaar deze levering beoordeeld werd (R-ISS-02).
            requireClassification(severity, "severity", issueCode);
            requireClassification(issueDomain, "issueDomain", issueCode);
            requireClassification(controlLevel, "controlLevel", issueCode);
            requireClassification(impactScope, "impactScope", issueCode);
            requireClassification(handlingStatus, "handlingStatus", issueCode);
        }

        private static void requireClassification(Object value, String field, String issueCode) {
            if (value == null) {
                throw new IllegalArgumentException("Issue " + issueCode + " has no " + field
                        + "; every issue must be classified through the issue catalogue before it is stored");
            }
        }
    }

    private final JdbcTemplate jdbc;
    private final int batchSize;

    public RowIssueDao(JdbcTemplate jdbc,
                       @Value("${catalogimport.screening.stage-batch-size:"
                               + CandidateStageDao.DEFAULT_BATCH_SIZE + "}") int batchSize) {
        this.jdbc = jdbc;
        this.batchSize = batchSize > 0 ? batchSize : CandidateStageDao.DEFAULT_BATCH_SIZE;
    }

    /** @return het aantal weggeschreven rijen */
    public int insertBatch(List<IssueRow> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        int written = 0;
        for (int start = 0; start < rows.size(); start += batchSize) {
            List<IssueRow> chunk = rows.subList(start, Math.min(start + batchSize, rows.size()));
            jdbc.batchUpdate(INSERT, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement statement, int index) throws SQLException {
                    bind(statement, chunk.get(index));
                }

                @Override
                public int getBatchSize() {
                    return chunk.size();
                }
            });
            written += chunk.size();
        }
        return written;
    }

    /** Enkel voor een technisch mislukte poging; een geblokkeerde batch behoudt haar problemen. */
    public int deleteByBatchId(long batchId) {
        return jdbc.update("delete from import_row_issue where batch_id = ?", batchId);
    }

    public long countByBatchId(long batchId) {
        Long count = jdbc.queryForObject("select count(*) from import_row_issue where batch_id = ?",
                Long.class, batchId);
        return count == null ? 0L : count;
    }

    /**
     * Aantal reeds bewaarde problemen met deze foutcode. Gebruikt om de voorbeeldcap per code te
     * bewaken en om te voorkomen dat dezelfde blokkade bij een hervatting een tweede issuerij krijgt.
     */
    public long countByBatchIdAndIssueCode(long batchId, String issueCode) {
        Long count = jdbc.queryForObject(
                "select count(*) from import_row_issue where batch_id = ? and issue_code = ?",
                Long.class, batchId, truncate(issueCode, MAX_ISSUE_CODE_LENGTH));
        return count == null ? 0L : count;
    }

    /**
     * Aantal reeds bewaarde problemen met deze foutcode <b>en</b> deze signatuur. Gebruikt om een
     * samenvattende melding (zoals {@code ROW_ISSUE_RECORDING_CAPPED} per foutcode) precies één keer
     * te schrijven, ook wanneer de verwerking hervat is.
     */
    public long countByBatchIdAndSignature(long batchId, String issueCode, String signature) {
        Long count = jdbc.queryForObject(
                "select count(*) from import_row_issue where batch_id = ? and issue_code = ? "
                        + "and signature = ?", Long.class, batchId,
                truncate(issueCode, MAX_ISSUE_CODE_LENGTH), truncate(signature, MAX_SIGNATURE_LENGTH));
        return count == null ? 0L : count;
    }

    /**
     * Aantallen per ernst; de basis voor {@code import_batch.validation_result} (R-THR-06). Eén
     * query in plaats van een telling per ernst, zodat het oordeel op één momentopname berust.
     */
    public Map<RowIssueSeverity, Long> countsBySeverity(long batchId) {
        Map<RowIssueSeverity, Long> counts = new EnumMap<>(RowIssueSeverity.class);
        jdbc.query("select severity, count(*) from import_row_issue where batch_id = ? group by severity",
                resultSet -> {
                    counts.merge(RowIssueSeverity.valueOf(resultSet.getString(1)), resultSet.getLong(2),
                            Long::sum);
                }, batchId);
        return counts;
    }

    /** Aantallen per {@code issue_code}, gesorteerd op code; gebruikt in de blokkeerreden. */
    public Map<String, Long> countByIssueCode(long batchId) {
        List<Map.Entry<String, Long>> rows = jdbc.query(
                "select issue_code, count(*) from import_row_issue where batch_id = ? "
                        + "group by issue_code order by issue_code",
                (resultSet, rowNumber) -> Map.entry(resultSet.getString(1), resultSet.getLong(2)),
                batchId);
        Map<String, Long> counts = new LinkedHashMap<>();
        rows.forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
        return counts;
    }

    private static void bind(PreparedStatement statement, IssueRow row) throws SQLException {
        statement.setLong(1, row.batchId());
        setNullableLong(statement, 2, row.deliveryFileId());
        setNullableLong(statement, 3, row.rowNumber());
        statement.setString(4, truncate(row.issueCode(), MAX_ISSUE_CODE_LENGTH));
        setNullable(statement, 5, truncate(row.fieldName(), MAX_FIELD_NAME_LENGTH));
        statement.setString(6, row.severity().name());
        statement.setString(7, row.issueDomain().name());
        statement.setString(8, row.controlLevel().name());
        statement.setString(9, row.impactScope().name());
        statement.setString(10, row.handlingStatus().name());
        setNullable(statement, 11, truncate(row.sourceValue(), MAX_SOURCE_VALUE_LENGTH));
        setNullable(statement, 12, truncate(row.expectedValue(), MAX_EXPECTED_VALUE_LENGTH));
        statement.setString(13, truncate(row.message(), MAX_MESSAGE_LENGTH));
        setNullable(statement, 14, truncate(row.signature(), MAX_SIGNATURE_LENGTH));
        statement.setObject(15, OffsetDateTime.ofInstant(row.createdAt(), ZoneOffset.UTC));
    }

    private static void setNullable(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
