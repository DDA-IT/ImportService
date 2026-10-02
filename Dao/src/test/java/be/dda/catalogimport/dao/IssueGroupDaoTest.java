package be.dda.catalogimport.dao;

import static org.assertj.core.api.Assertions.assertThat;

import be.dda.catalogimport.domain.ControlLevel;
import be.dda.catalogimport.domain.ImpactScope;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.IssueDomain;
import be.dda.catalogimport.domain.IssueIncidentKind;
import be.dda.catalogimport.domain.RowIssueSeverity;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Gedrag van {@link IssueGroupDao} tegen het echte schema (changeset 004). Elke test rolt terug. */
@DaoTest
class IssueGroupDaoTest {

    @Autowired
    private IssueGroupDao groups;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DaoFixtures fixtures;

    @Test
    void accumulatingTwiceOnTheSameSignatureAddsTheOccurrencesUpOnOneGroup() {
        ImportBatch batch = fixtures.batch();

        groups.accumulate(batch.getId(), List.of(counter("CODE_A", "sig-1", RowIssueSeverity.ERROR, 3, 10L)));
        groups.accumulate(batch.getId(), List.of(counter("CODE_A", "sig-1", RowIssueSeverity.ERROR, 4, 20L)));

        List<IssueGroupDao.GroupRow> rows = groups.findByBatchId(batch.getId());
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.occurrenceCount()).isEqualTo(7);
            assertThat(row.recordedSampleCount()).isZero();
        });
        assertThat(groups.countByBatchId(batch.getId())).isEqualTo(1);
    }

    @Test
    void anotherSignatureOrCodeBecomesItsOwnGroup() {
        ImportBatch batch = fixtures.batch();

        groups.accumulate(batch.getId(), List.of(
                counter("CODE_A", "sig-1", RowIssueSeverity.ERROR, 1, 1L),
                counter("CODE_A", "sig-2", RowIssueSeverity.ERROR, 1, 1L),
                counter("CODE_B", "sig-1", RowIssueSeverity.ERROR, 1, 1L)));

        assertThat(groups.countByBatchId(batch.getId())).isEqualTo(3);
    }

    @Test
    void theLowestFirstRowNumberWinsAndANullNeverOverwritesAValue() {
        ImportBatch batch = fixtures.batch();

        groups.accumulate(batch.getId(), List.of(counter("CODE_A", "sig-1", RowIssueSeverity.ERROR, 1, 10L)));
        assertThat(firstRow(batch, "CODE_A")).isEqualTo(10L);

        groups.accumulate(batch.getId(), List.of(counter("CODE_A", "sig-1", RowIssueSeverity.ERROR, 1, 5L)));
        assertThat(firstRow(batch, "CODE_A")).isEqualTo(5L);

        groups.accumulate(batch.getId(), List.of(counter("CODE_A", "sig-1", RowIssueSeverity.ERROR, 1, 8L)));
        assertThat(firstRow(batch, "CODE_A")).isEqualTo(5L);

        groups.accumulate(batch.getId(), List.of(counter("CODE_A", "sig-1", RowIssueSeverity.ERROR, 1, null)));
        assertThat(firstRow(batch, "CODE_A")).isEqualTo(5L);

        // Een groep die zonder regelnummer begon, krijgt het eerste bekende regelnummer.
        groups.accumulate(batch.getId(), List.of(counter("CODE_B", "sig-1", RowIssueSeverity.ERROR, 1, null)));
        assertThat(firstRow(batch, "CODE_B")).isNull();
        groups.accumulate(batch.getId(), List.of(counter("CODE_B", "sig-1", RowIssueSeverity.ERROR, 1, 7L)));
        assertThat(firstRow(batch, "CODE_B")).isEqualTo(7L);
    }

    @Test
    void refreshSampleCountsRecountsInsteadOfAddingSoASecondPassGivesTheSameNumber() {
        ImportBatch batch = fixtures.batch();
        groups.accumulate(batch.getId(), List.of(counter("CODE_A", "sig-1", RowIssueSeverity.ERROR, 50, 1L)));
        insertIssue(batch, "CODE_A", "sig-1", RowIssueSeverity.ERROR);
        insertIssue(batch, "CODE_A", "sig-1", RowIssueSeverity.ERROR);
        insertIssue(batch, "CODE_A", "sig-1", RowIssueSeverity.ERROR);
        assertThat(groups.linkIssueRows(batch.getId())).isEqualTo(3);

        groups.refreshSampleCounts(batch.getId());
        assertThat(groups.findByBatchId(batch.getId()).get(0).recordedSampleCount()).isEqualTo(3);
        groups.refreshSampleCounts(batch.getId());
        assertThat(groups.findByBatchId(batch.getId()).get(0).recordedSampleCount()).isEqualTo(3);
        // Het werkelijke aantal blijft los van het aantal voorbeelden.
        assertThat(groups.findByBatchId(batch.getId()).get(0).occurrenceCount()).isEqualTo(50);
    }

    @Test
    void deleteBelowThresholdKeepsAGroupWhoseOccurrencesExceedItsRecordedSamples() {
        ImportBatch batch = fixtures.batch();
        // A: 2 voorvallen, 2 voorbeelden, onder de drempel -> verdwijnt (elk voorval blijft een eigen rij).
        // B: 5 voorvallen, 2 voorbeelden (gecapt), onder de drempel -> blijft (anders is het aantal weg).
        // C: 4 voorvallen, 0 voorbeelden -> blijft.
        groups.accumulate(batch.getId(), List.of(
                counter("CODE_A", "sig", RowIssueSeverity.ERROR, 2, 1L),
                counter("CODE_B", "sig", RowIssueSeverity.ERROR, 5, 1L),
                counter("CODE_C", "sig", RowIssueSeverity.ERROR, 4, 1L)));
        insertIssue(batch, "CODE_A", "sig", RowIssueSeverity.ERROR);
        insertIssue(batch, "CODE_A", "sig", RowIssueSeverity.ERROR);
        insertIssue(batch, "CODE_B", "sig", RowIssueSeverity.ERROR);
        insertIssue(batch, "CODE_B", "sig", RowIssueSeverity.ERROR);
        groups.linkIssueRows(batch.getId());
        groups.refreshSampleCounts(batch.getId());

        int deleted = groups.deleteBelowThreshold(batch.getId(), 10);

        assertThat(deleted).isEqualTo(1);
        assertThat(groups.findByBatchId(batch.getId())).extracting(IssueGroupDao.GroupRow::issueCode)
                .containsExactly("CODE_B", "CODE_C");
        // De issuerijen van de verwijderde groep bestaan nog, maar zonder verwijzing.
        assertThat(jdbc.queryForObject("select count(*) from import_row_issue where batch_id = ? "
                + "and issue_code = 'CODE_A' and issue_group_id is null", Long.class, batch.getId())).isEqualTo(2);
    }

    @Test
    void deleteBelowThresholdKeepsGroupsThatReachTheThreshold() {
        ImportBatch batch = fixtures.batch();
        groups.accumulate(batch.getId(), List.of(counter("CODE_A", "sig", RowIssueSeverity.ERROR, 3, 1L)));
        insertIssue(batch, "CODE_A", "sig", RowIssueSeverity.ERROR);
        insertIssue(batch, "CODE_A", "sig", RowIssueSeverity.ERROR);
        insertIssue(batch, "CODE_A", "sig", RowIssueSeverity.ERROR);
        groups.linkIssueRows(batch.getId());
        groups.refreshSampleCounts(batch.getId());

        assertThat(groups.deleteBelowThreshold(batch.getId(), 3)).isZero();
        assertThat(groups.countByBatchId(batch.getId())).isEqualTo(1);
    }

    @Test
    void countOccurrencesBySeverityCountsGroupedAndUngroupedWithoutDoubleCounting() {
        ImportBatch batch = fixtures.batch();
        // Gegroepeerd: 250 voorvallen, maar slechts 3 voorbeeldrijen (cap) -> telt 250, niet 253.
        groups.accumulate(batch.getId(), List.of(
                counter("CODE_CRIT", "sig", RowIssueSeverity.CRITICAL, 250, 1L),
                counter("CODE_WARN", "sig", RowIssueSeverity.WARNING, 7, 1L)));
        insertIssue(batch, "CODE_CRIT", "sig", RowIssueSeverity.CRITICAL);
        insertIssue(batch, "CODE_CRIT", "sig", RowIssueSeverity.CRITICAL);
        insertIssue(batch, "CODE_CRIT", "sig", RowIssueSeverity.CRITICAL);
        groups.linkIssueRows(batch.getId());
        // Ongegroepeerd: een rij zonder signatuur telt 1.
        insertIssue(batch, "CODE_LOOSE", null, RowIssueSeverity.CRITICAL);
        // Een bulkmelding met een andere code hangt aan de groep van de onderliggende code en telt als 1 extra.
        insertIssue(batch, "BULK_PRICE_INCIDENT", "sig", RowIssueSeverity.CRITICAL);
        long groupId = groups.findByBatchId(batch.getId()).stream()
                .filter(row -> row.issueCode().equals("CODE_CRIT")).findFirst().orElseThrow().id();
        groups.linkIssueRowsToGroup(batch.getId(), groupId, "BULK_PRICE_INCIDENT", "sig");

        assertThat(groups.countOccurrencesBySeverity(batch.getId(), RowIssueSeverity.CRITICAL))
                .isEqualTo(250 + 1 + 1);
        assertThat(groups.countOccurrencesBySeverity(batch.getId(), RowIssueSeverity.WARNING)).isEqualTo(7);
        assertThat(groups.countOccurrencesBySeverity(batch.getId(), RowIssueSeverity.INFO)).isZero();
    }

    // --- Helpers ---------------------------------------------------------------------------------

    private Long firstRow(ImportBatch batch, String code) {
        return groups.findByBatchId(batch.getId()).stream().filter(row -> row.issueCode().equals(code))
                .findFirst().orElseThrow().firstRowNumber();
    }

    private static IssueGroupDao.Counter counter(String code, String signature, RowIssueSeverity severity,
                                                 long occurrences, Long firstRow) {
        return new IssueGroupDao.Counter(code, signature, severity, IssueDomain.MAPPING_VALIDATION,
                ControlLevel.DELIVERY, ImpactScope.RECORD, IssueIncidentKind.GENERIC, null, null, null,
                occurrences, firstRow, Instant.now());
    }

    private void insertIssue(ImportBatch batch, String code, String signature, RowIssueSeverity severity) {
        jdbc.update("insert into import_row_issue (batch_id, issue_code, signature, severity, message, created_at) "
                + "values (?, ?, ?, ?, 'testmelding', current_timestamp)",
                batch.getId(), code, signature, severity.name());
    }
}
