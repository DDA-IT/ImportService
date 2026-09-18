package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.RowIssueSeverity;
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
 * Bulkopslag van regelproblemen in {@code import_row_issue} (design par. 5).
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
    /** {@code message} is varchar(500). */
    public static final int MAX_MESSAGE_LENGTH = 500;

    private static final String INSERT = "insert into import_row_issue ("
            + "batch_id, delivery_file_id, row_number, issue_code, field_name, severity, source_value, "
            + "message, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /** Eén te bewaren regelprobleem. */
    public record IssueRow(
            long batchId,
            long deliveryFileId,
            long rowNumber,
            String issueCode,
            String fieldName,
            RowIssueSeverity severity,
            String sourceValue,
            String message,
            Instant createdAt) {
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
        statement.setLong(2, row.deliveryFileId());
        statement.setLong(3, row.rowNumber());
        statement.setString(4, truncate(row.issueCode(), MAX_ISSUE_CODE_LENGTH));
        setNullable(statement, 5, truncate(row.fieldName(), MAX_FIELD_NAME_LENGTH));
        statement.setString(6, row.severity().name());
        setNullable(statement, 7, truncate(row.sourceValue(), MAX_SOURCE_VALUE_LENGTH));
        statement.setString(8, truncate(row.message(), MAX_MESSAGE_LENGTH));
        statement.setObject(9, OffsetDateTime.ofInstant(row.createdAt(), ZoneOffset.UTC));
    }

    private static void setNullable(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
