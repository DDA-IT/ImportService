package be.dda.catalogimport.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.dao.PublicationBundleRepository;
import be.dda.catalogimport.dao.PublicationRunRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Bouwstap 5P-6 (docs/design/fase5-pub-design.md par. 3 en 6, changeset 009-1): bewijst dat
 * {@code publication_run} migreert, dat Hibernate de entiteit met {@code ddl-auto: validate} aanvaardt en
 * dat de databaseconstraints afdwingen wat ze beloven. Bundelreferenties en sleutels zijn per test uniek
 * omdat de database gedeeld is.
 */
@SpringBootTest
@ActiveProfiles("local")
class PublicationRunSchemaTest {

    private static final String USER = "tester@example.test";

    @Autowired
    private PublicationBundleRepository bundles;
    @Autowired
    private PublicationRunRepository runs;
    @Autowired
    private JdbcTemplate jdbc;

    // --- Schema ----------------------------------------------------------------------------------

    @Test
    void createsThePublicationRunTableWithTheDocumentedColumnsAndTypes() {
        assertThat(columnNames()).containsExactlyInAnyOrder(
                "ID", "BUNDLE_ID", "TARGET_MODE", "ATTEMPT", "STATUS", "REQUESTED_BY", "REQUESTED_BY_SUBJECT",
                "REQUESTED_AT", "STARTED_AT", "FINISHED_AT", "BUNDLE_CONTENT_HASH", "SNAPSHOT_HASH",
                "PAYLOAD_HASH", "ARTIFACT_REFERENCE", "ARTIFACT_SHA256", "ARTIFACT_BYTE_SIZE", "ROW_COUNT",
                "INCOMPLETE_ROW_COUNT", "FAILURE_CODE", "FAILURE_MESSAGE", "IDEMPOTENCY_KEY", "ACTIVE_MARKER");

        assertThat(length("target_mode")).isEqualTo(30);
        assertThat(length("status")).isEqualTo(30);
        assertThat(length("requested_by")).isEqualTo(100);
        assertThat(length("requested_by_subject")).isEqualTo(255);
        assertThat(length("artifact_reference")).isEqualTo(500);
        assertThat(length("artifact_sha256")).isEqualTo(64);
        assertThat(length("failure_code")).isEqualTo(60);
        assertThat(length("failure_message")).isEqualTo(1000);
        assertThat(length("idempotency_key")).isEqualTo(200);
        assertThat(nullable("bundle_content_hash")).isEqualTo("NO");
        assertThat(nullable("snapshot_hash")).isEqualTo("YES");
        assertThat(nullable("payload_hash")).isEqualTo("YES");
        assertThat(nullable("requested_at")).isEqualTo("NO");
        assertThat(nullable("requested_by_subject")).isEqualTo("YES");
        // target_mode heeft bewust geen default; attempt wel (1).
        assertThat(jdbc.queryForObject(columnQuery("column_default"), String.class, "target_mode")).isNull();
        assertThat(jdbc.queryForObject(columnQuery("column_default"), String.class, "attempt")).isEqualTo("1");
    }

    // --- Checks ----------------------------------------------------------------------------------

    @Test
    void refusesAnInvalidTargetModeAndAcceptsTheThreeDocumentedOnes() {
        PublicationBundle bundle = bundle("MODE");

        assertThatThrownBy(() -> rawInsert(bundle, "BOGUS", "REQUESTED", true, null, key("MODE-X")))
                .isInstanceOf(DataIntegrityViolationException.class);
        for (PublicationTargetMode mode : PublicationTargetMode.values()) {
            // Terminale runs (marker null) zodat de actieve-marker-constraint niet botst.
            assertThatCode(() -> rawInsert(bundle, mode.name(), "FAILED", null, "CODE", key("MODE-" + mode)))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void refusesAnInvalidStatusAndAcceptsEveryEnumValue() {
        PublicationBundle bundle = bundle("STATUS");

        assertThatThrownBy(() -> rawInsert(bundle, "SIMULATION", "BOGUS", true, null, key("ST-X")))
                .isInstanceOf(DataIntegrityViolationException.class);
        for (PublicationRunStatus status : PublicationRunStatus.values()) {
            Boolean marker = status.isTerminal() ? null : Boolean.TRUE;
            PublicationBundle own = bundle("STATUS-" + status);
            assertThatCode(() -> rawInsert(own, "SIMULATION", status.name(), marker, "CODE",
                    key("ST-" + status))).doesNotThrowAnyException();
        }
    }

    @Test
    void refusesADuplicateIdempotencyKey() {
        PublicationBundle bundle = bundle("IDEM");
        String key = key("IDEM");
        rawInsert(bundle, "SIMULATION", "SIMULATED", null, null, key);

        assertThatThrownBy(() -> rawInsert(bundle, "SIMULATION", "SIMULATED", null, null, key))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsOneActiveRunPerBundleButNotASecondAndAllowsManyTerminalRuns() {
        PublicationBundle bundle = bundle("ACTIVE");
        runs.saveAndFlush(newRun(bundle, 1, key("ACTIVE-1")));

        // Tweede actieve run voor dezelfde bundel: uk_publication_run_active.
        assertThatThrownBy(() -> runs.saveAndFlush(newRun(bundle, 2, key("ACTIVE-2"))))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Een andere bundel mag wel een actieve run hebben.
        PublicationBundle other = bundle("ACTIVE-OTHER");
        assertThatCode(() -> runs.saveAndFlush(newRun(other, 1, key("ACTIVE-3")))).doesNotThrowAnyException();

        // Meerdere terminale runs (marker NULL) voor dezelfde bundel botsen niet.
        PublicationBundle terminal = bundle("ACTIVE-TERM");
        assertThatCode(() -> {
            rawInsert(terminal, "SIMULATION", "SIMULATED", null, null, key("TERM-1"));
            rawInsert(terminal, "SIMULATION", "SIMULATED", null, null, key("TERM-2"));
            rawInsert(terminal, "SIMULATION", "FAILED", null, "CODE", key("TERM-3"));
        }).doesNotThrowAnyException();
        assertThat(runs.findByBundleIdOrderByIdAsc(terminal.getId())).hasSize(3);
    }

    @Test
    void enforcesTheMarkerStatusRule() {
        PublicationBundle bundle = bundle("MARKER");

        // Niet-terminaal zonder marker.
        assertThatThrownBy(() -> rawInsert(bundle, "SIMULATION", "REQUESTED", null, null, key("MK-1")))
                .isInstanceOf(DataIntegrityViolationException.class);
        // Terminaal met marker.
        assertThatThrownBy(() -> rawInsert(bundle, "SIMULATION", "SIMULATED", true, null, key("MK-2")))
                .isInstanceOf(DataIntegrityViolationException.class);
        // FALSE is nooit toegelaten.
        assertThatThrownBy(() -> rawInsert(bundle, "SIMULATION", "SIMULATED", false, null, key("MK-3")))
                .isInstanceOf(DataIntegrityViolationException.class);
        // Correcte combinaties.
        assertThatCode(() -> rawInsert(bundle, "SIMULATION", "PREPARING", true, null, key("MK-4")))
                .doesNotThrowAnyException();
    }

    @Test
    void requiresFinishedAtOnTerminalRunsAndFailureCodeOnFailedRuns() {
        PublicationBundle bundle = bundle("TERMINAL");

        assertThatThrownBy(() -> jdbc.update("insert into publication_run (bundle_id, target_mode, status, "
                        + "requested_by, requested_at, bundle_content_hash, idempotency_key) "
                        + "values (?, ?, ?, ?, ?, ?, ?)", bundle.getId(), "SIMULATION", "SIMULATED", USER,
                Timestamp.from(Instant.now()), new byte[32], key("TERM-NF")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> rawInsert(bundle, "SIMULATION", "FAILED", null, null, key("TERM-NC")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void requestedBySubjectIsNullableAndForeignKeyRefusesAnUnknownBundle() {
        PublicationBundle bundle = bundle("FK");
        PublicationRun run = runs.saveAndFlush(newRun(bundle, 1, key("FK-1")));
        assertThat(runs.findById(run.getId()).orElseThrow().getRequestedBySubject()).isNull();

        assertThatThrownBy(() -> jdbc.update("insert into publication_run (bundle_id, target_mode, status, "
                        + "requested_by, requested_at, bundle_content_hash, idempotency_key, active_marker) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?)", -1L, "SIMULATION", "REQUESTED", USER,
                Timestamp.from(Instant.now()), new byte[32], key("FK-2"), true))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(constraintExists("fk_publication_run_bundle")).isTrue();
    }

    // --- Entiteit en repository ------------------------------------------------------------------

    @Test
    void savesARunAndReadsItBackIncludingHashes() {
        PublicationBundle bundle = bundle("RT");
        byte[] contentHash = hash(1);
        byte[] snapshotHash = hash(50);
        String key = key("RT");
        Instant requestedAt = Instant.now();
        PublicationRun saved = runs.saveAndFlush(new PublicationRun(bundle, PublicationTargetMode.SIMULATION, 1,
                USER, "sub-rt", requestedAt, contentHash, snapshotHash, key));
        assertThat(saved.getId()).isNotNull();

        PublicationRun found = runs.findById(saved.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(PublicationRunStatus.REQUESTED);
        assertThat(found.getActiveMarker()).isTrue();
        assertThat(found.getTargetMode()).isEqualTo(PublicationTargetMode.SIMULATION);
        assertThat(found.getAttempt()).isEqualTo(1);
        assertThat(found.getRequestedBy()).isEqualTo(USER);
        assertThat(found.getRequestedBySubject()).isEqualTo("sub-rt");
        assertThat(found.getRequestedAt()).isNotNull();
        assertThat(found.getBundleContentHash()).isEqualTo(contentHash);
        assertThat(found.getSnapshotHash()).isEqualTo(snapshotHash);
        assertThat(found.getPayloadHash()).isNull();
        assertThat(found.getArtifactSha256()).isNull();
        assertThat(found.getRowCount()).isNull();
        assertThat(found.getIdempotencyKey()).isEqualTo(key);
        assertThat(runs.findByIdempotencyKey(key)).isPresent();
        assertThat(runs.findByBundleIdOrderByIdAsc(bundle.getId())).extracting(PublicationRun::getId)
                .containsExactly(saved.getId());
    }

    @Test
    void enumHelpersMatchTheMarkerRule() {
        for (PublicationRunStatus status : PublicationRunStatus.values()) {
            assertThat(status.isTerminal()).isEqualTo(
                    status == PublicationRunStatus.SIMULATED || status == PublicationRunStatus.FAILED);
        }
        assertThat(PublicationRunStatus.values()).hasSize(11);
    }

    // --- Helpers ---------------------------------------------------------------------------------

    private PublicationRun newRun(PublicationBundle bundle, int attempt, String key) {
        return new PublicationRun(bundle, PublicationTargetMode.SIMULATION, attempt, USER, null, Instant.now(),
                hash(7), null, key);
    }

    private void rawInsert(PublicationBundle bundle, String mode, String status, Boolean marker,
                           String failureCode, String key) {
        jdbc.update("insert into publication_run (bundle_id, target_mode, status, requested_by, requested_at, "
                        + "finished_at, bundle_content_hash, failure_code, idempotency_key, active_marker) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                bundle.getId(), mode, status, USER, Timestamp.from(Instant.now()),
                marker == null ? Timestamp.from(Instant.now()) : null, new byte[32], failureCode, key, marker);
    }

    private PublicationBundle bundle(String prefix) {
        return bundles.saveAndFlush(new PublicationBundle("BND-RUN-" + prefix + "-" + Long.toString(System.nanoTime(), 36),
                PublicationTargetMode.SIMULATION, USER));
    }

    private static String key(String prefix) {
        return "run-test:" + prefix + ":" + Long.toString(System.nanoTime(), 36);
    }

    private static byte[] hash(int seed) {
        byte[] hash = new byte[32];
        for (int i = 0; i < hash.length; i++) {
            hash[i] = (byte) (seed + i);
        }
        return hash;
    }

    private List<String> columnNames() {
        return jdbc.queryForList("select upper(column_name) from information_schema.columns "
                + "where table_schema = current_schema() and upper(table_name) = 'PUBLICATION_RUN'", String.class);
    }

    private String columnQuery(String selected) {
        return "select " + selected + " from information_schema.columns where table_schema = current_schema() "
                + "and upper(table_name) = 'PUBLICATION_RUN' and lower(column_name) = ?";
    }

    private Integer length(String column) {
        Map<String, Object> row = jdbc.queryForMap(columnQuery("character_maximum_length as len"), column);
        return ((Number) row.get("len")).intValue();
    }

    private String nullable(String column) {
        return jdbc.queryForObject(columnQuery("is_nullable"), String.class, column);
    }

    private boolean constraintExists(String name) {
        Integer count = jdbc.queryForObject("select count(*) from information_schema.table_constraints "
                + "where constraint_schema = current_schema() and upper(constraint_name) = upper(?)", Integer.class, name);
        return count != null && count > 0;
    }
}
