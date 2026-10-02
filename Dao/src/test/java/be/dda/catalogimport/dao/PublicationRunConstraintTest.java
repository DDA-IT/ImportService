package be.dda.catalogimport.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.domain.PublicationBundle;
import be.dda.catalogimport.domain.PublicationRun;
import be.dda.catalogimport.domain.PublicationRunStatus;
import be.dda.catalogimport.domain.PublicationTargetMode;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** {@code uk_publication_run_active} en de marker/status-regel van {@link PublicationRun}. */
@DaoTest
class PublicationRunConstraintTest {

    private static final String USER = "tester@example.test";

    @Autowired
    private PublicationBundleRepository bundles;
    @Autowired
    private PublicationRunRepository runs;

    @Test
    void aSecondActiveRunOnTheSameBundleIsRefused() {
        PublicationBundle bundle = bundle();
        runs.saveAndFlush(newRun(bundle, 1));

        // De transactie is hierna afgebroken: geen verdere databasetoegang in deze test.
        assertThatThrownBy(() -> runs.saveAndFlush(newRun(bundle, 2)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_publication_run_active");
    }

    @Test
    void anActiveRunOnAnotherBundleIsAccepted() {
        runs.saveAndFlush(newRun(bundle(), 1));
        PublicationRun other = runs.saveAndFlush(newRun(bundle(), 1));

        assertThat(other.getActiveMarker()).isTrue();
    }

    @Test
    void finishingARunReleasesTheMarkerSoANewRunIsAcceptedAndTerminalRunsDoNotCollide() {
        PublicationBundle bundle = bundle();
        PublicationRun first = runs.saveAndFlush(newRun(bundle, 1));
        assertThat(first.getStatus()).isEqualTo(PublicationRunStatus.REQUESTED);
        assertThat(first.getActiveMarker()).isTrue();

        first.markPreparing(Instant.now());
        runs.saveAndFlush(first);
        assertThat(first.getActiveMarker()).isTrue();

        first.recordFailed(Instant.now(), "FAILURE_TEST", "testfout");
        runs.saveAndFlush(first);
        assertThat(first.getStatus()).isEqualTo(PublicationRunStatus.FAILED);
        assertThat(first.getActiveMarker()).isNull();

        PublicationRun second = runs.saveAndFlush(newRun(bundle, 2));
        second.markPreparing(Instant.now());
        second.recordSimulated(Instant.now(), "ref", "a".repeat(64), 10L, new byte[32], 1L, 0L);
        runs.saveAndFlush(second);
        assertThat(second.getActiveMarker()).isNull();

        // Twee terminale runs naast elkaar, en daarna opnieuw een actieve.
        assertThat(runs.saveAndFlush(newRun(bundle, 3)).getActiveMarker()).isTrue();
        assertThat(runs.findByBundleIdOrderByIdAsc(bundle.getId())).hasSize(3);
    }

    @Test
    void aRunThatIsNotPreparingCannotBeFinishedAndKeepsItsMarker() {
        PublicationRun run = runs.saveAndFlush(newRun(bundle(), 1));

        assertThatThrownBy(() -> run.recordSimulated(Instant.now(), "ref", "a".repeat(64), 10L, new byte[32], 1L, 0L))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> run.recordFailed(Instant.now(), "FAILURE_TEST", "x"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(run.getStatus()).isEqualTo(PublicationRunStatus.REQUESTED);
        assertThat(run.getActiveMarker()).isTrue();
    }

    private PublicationRun newRun(PublicationBundle bundle, int attempt) {
        return new PublicationRun(bundle, PublicationTargetMode.SIMULATION, attempt, USER, null, Instant.now(),
                new byte[32], null, "KEY-" + UUID.randomUUID());
    }

    private PublicationBundle bundle() {
        return bundles.saveAndFlush(new PublicationBundle("BND-" + UUID.randomUUID(),
                PublicationTargetMode.SIMULATION, USER));
    }
}
