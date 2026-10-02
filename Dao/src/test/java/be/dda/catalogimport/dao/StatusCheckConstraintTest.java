package be.dda.catalogimport.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.ImportMutation;
import be.dda.catalogimport.domain.MutationActionType;
import be.dda.catalogimport.domain.MutationStatus;
import be.dda.catalogimport.domain.MutationTargetDomain;
import be.dda.catalogimport.domain.PublicationBundle;
import be.dda.catalogimport.domain.PublicationBundleBatch;
import be.dda.catalogimport.domain.PublicationTargetMode;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.TaskRun;
import be.dda.catalogimport.domain.TaskRunStatus;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Changeset 018: status-CHECKs op de vier kernentiteiten (exact de waarden van de Java-enum) en de NULL-veilige
 * {@code ck_publication_bundle_batch_removed}.
 * <p>
 * Een constraintfout breekt de transactie: na de verwachte exception volgt in dezelfde test geen databasetoegang meer.
 * <p>
 * Geldige waarden: een native update per enumwaarde op een rij die geen andere check schendt. Uitzondering is
 * {@link MutationStatus#REJECTED}: {@code ck_import_mutation_rejected_decided} vraagt dan een beslissing (decision_id),
 * wat een volledige beslissingsketen zou vragen. Die waarde wordt gedekt door de "exacte definitie"-tests, die de
 * literals van elke constraint vergelijken met de enum.
 */
@DaoTest
class StatusCheckConstraintTest {

    private static final Pattern LITERAL = Pattern.compile("'([A-Z_]+)'");

    @Autowired
    private DaoFixtures fixtures;
    @Autowired
    private ImportMutationRepository mutations;
    @Autowired
    private TaskRunRepository taskRuns;
    @Autowired
    private PublicationBundleRepository bundles;
    @Autowired
    private PublicationBundleBatchRepository bundleBatches;
    @Autowired
    private JdbcTemplate jdbc;

    // ---- ongeldige status per tabel

    @Test
    void anUnknownImportBatchStatusIsRefused() {
        ImportBatch batch = fixtures.batch();
        assertThatThrownBy(() -> jdbc.update("update import_batch set status = 'BOGUS' where id = ?", batch.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_import_batch_status");
    }

    @Test
    void anUnknownImportMutationStatusIsRefused() {
        ImportMutation mutation = mutations.saveAndFlush(mutation(fixtures.batch()));
        assertThatThrownBy(
                () -> jdbc.update("update import_mutation set status = 'BOGUS' where id = ?", mutation.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_import_mutation_status");
    }

    @Test
    void anUnknownTaskRunStatusIsRefused() {
        TaskRun run = taskRun();
        assertThatThrownBy(() -> jdbc.update("update task_run set status = 'BOGUS' where id = ?", run.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_task_run_status");
    }

    @Test
    void anUnknownRevisionStatusIsRefused() {
        Long revisionId = fixtures.chain().revision().getId();
        assertThatThrownBy(() -> jdbc.update(
                "update import_definition_revision set status = 'BOGUS' where id = ?", revisionId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_import_definition_revision_status");
    }

    @Test
    void aLowerCaseStatusIsRefusedToo() {
        ImportBatch batch = fixtures.batch();
        assertThatThrownBy(() -> jdbc.update("update import_batch set status = 'failed' where id = ?", batch.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_import_batch_status");
    }

    // ---- elke geldige enumwaarde wordt aanvaard

    @Test
    void everyImportBatchStatusIsAccepted() {
        ImportBatch batch = fixtures.batch();
        for (ImportBatchStatus status : ImportBatchStatus.values()) {
            assertThat(jdbc.update("update import_batch set status = ? where id = ?", status.name(), batch.getId()))
                    .as(status.name()).isEqualTo(1);
        }
    }

    @Test
    void everyImportMutationStatusExceptRejectedIsAccepted() {
        ImportMutation mutation = mutations.saveAndFlush(mutation(fixtures.batch()));
        for (MutationStatus status : MutationStatus.values()) {
            if (status == MutationStatus.REJECTED) {
                continue; // vraagt decision_id (ck_import_mutation_rejected_decided); gedekt door de definitietest
            }
            assertThat(jdbc.update("update import_mutation set status = ? where id = ?", status.name(),
                    mutation.getId())).as(status.name()).isEqualTo(1);
        }
    }

    @Test
    void everyTaskRunStatusIsAccepted() {
        TaskRun run = taskRun();
        for (TaskRunStatus status : TaskRunStatus.values()) {
            assertThat(jdbc.update("update task_run set status = ? where id = ?", status.name(), run.getId()))
                    .as(status.name()).isEqualTo(1);
        }
    }

    @Test
    void everyRevisionStatusIsAccepted() {
        Long revisionId = fixtures.chain().revision().getId();
        for (RevisionStatus status : RevisionStatus.values()) {
            assertThat(jdbc.update("update import_definition_revision set status = ? where id = ?", status.name(),
                    revisionId)).as(status.name()).isEqualTo(1);
        }
    }

    // ---- de constraint bevat exact de enumwaarden (dekt ook MutationStatus.REJECTED)

    @Test
    void theConstraintDefinitionsListExactlyTheEnumValues() {
        assertThat(literals("ck_import_batch_status", "import_batch"))
                .isEqualTo(names(ImportBatchStatus.values()));
        assertThat(literals("ck_import_mutation_status", "import_mutation"))
                .isEqualTo(names(MutationStatus.values()));
        assertThat(literals("ck_task_run_status", "task_run"))
                .isEqualTo(names(TaskRunStatus.values()));
        assertThat(literals("ck_import_definition_revision_status", "import_definition_revision"))
                .isEqualTo(names(RevisionStatus.values()));
    }

    // ---- ck_publication_bundle_batch_removed is NULL-veilig

    @Test
    void anActiveMembershipWithoutMarkerIsNowRefused() {
        // Vroeger (active_marker = true) evalueerde deze combinatie tot NULL en glipte ze door: alle removed-velden
        // leeg en active_marker NULL. Dat schakelde uk_publication_bundle_batch_active uit.
        PublicationBundleBatch membership = membership();
        assertThatThrownBy(() -> jdbc.update(
                "update publication_bundle_batch set active_marker = null where id = ?", membership.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_publication_bundle_batch_removed");
    }

    @Test
    void aRemovedMembershipWithMarkerStillSetIsRefused() {
        PublicationBundleBatch membership = membership();
        assertThatThrownBy(() -> jdbc.update("update publication_bundle_batch set removed_by = 'x', "
                + "removed_at = now(), removed_reason = 'r' where id = ?", membership.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_publication_bundle_batch_removed");
    }

    @Test
    void aProperlyRemovedMembershipIsAccepted() {
        PublicationBundleBatch membership = membership();
        assertThat(jdbc.update("update publication_bundle_batch set removed_by = 'x', removed_at = now(), "
                + "removed_reason = 'r', active_marker = null where id = ?", membership.getId())).isEqualTo(1);
    }

    @Test
    void anActiveMembershipIsAccepted() {
        assertThat(membership().getActiveMarker()).isTrue();
    }

    // ---- hulp

    private PublicationBundleBatch membership() {
        ImportBatch batch = fixtures.batch();
        PublicationBundle bundle = bundles.saveAndFlush(new PublicationBundle("BND-" + UUID.randomUUID(),
                PublicationTargetMode.SIMULATION, DaoFixtures.USER));
        return bundleBatches.saveAndFlush(
                new PublicationBundleBatch(bundle, batch, batch.getImportLink(), DaoFixtures.USER));
    }

    private TaskRun taskRun() {
        DaoFixtures.Chain chain = fixtures.chain();
        return taskRuns.saveAndFlush(new TaskRun(chain.delivery().getTask(), Instant.now(), DaoFixtures.USER));
    }

    private static ImportMutation mutation(ImportBatch batch) {
        String key = "IDEM-" + UUID.randomUUID();
        ImportMutation mutation = new ImportMutation(batch, MutationActionType.CREATE,
                MutationTargetDomain.OFFER, MutationStatus.PLANNED, key);
        mutation.setIdentitySupplier("LEV");
        mutation.setIdentitySupplierGroup("GRP");
        mutation.setIdentitySupplierReference("REF-" + key);
        return mutation;
    }

    private Set<String> literals(String constraint, String table) {
        String definition = jdbc.queryForObject("select pg_get_constraintdef(oid) from pg_constraint "
                + "where conname = ? and conrelid = ?::regclass", String.class, constraint, table);
        Set<String> found = new HashSet<>();
        Matcher matcher = LITERAL.matcher(definition);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    private static Set<String> names(Enum<?>[] values) {
        Set<String> names = new HashSet<>();
        Arrays.stream(values).forEach(value -> names.add(value.name()));
        return names;
    }
}
