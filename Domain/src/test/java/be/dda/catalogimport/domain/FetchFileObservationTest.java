package be.dda.catalogimport.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class FetchFileObservationTest {

    private static final TaskRun RUN = new TaskRun(null, Instant.parse("2026-01-01T00:00:00Z"), "tester");
    private static final Delivery DELIVERY = new Delivery(null, "key", Instant.parse("2026-01-01T00:00:00Z"));
    private static final Instant MODIFIED = Instant.parse("2025-12-31T00:00:00Z");

    @Test
    void validSelectedObservationCarriesDelivery() {
        FetchFileObservation observation = new FetchFileObservation(RUN, "a.csv", 10L, MODIFIED,
                FetchFileDecision.SELECTED, DELIVERY);
        assertThat(observation.getTaskRun()).isSameAs(RUN);
        assertThat(observation.getRemoteFileName()).isEqualTo("a.csv");
        assertThat(observation.getByteSize()).isEqualTo(10L);
        assertThat(observation.getRemoteModifiedAt()).isEqualTo(MODIFIED);
        assertThat(observation.getDecision()).isEqualTo(FetchFileDecision.SELECTED);
        assertThat(observation.getDelivery()).isSameAs(DELIVERY);
    }

    @Test
    void nonSelectedObservationWithoutDeliveryIsValid() {
        for (FetchFileDecision decision : FetchFileDecision.values()) {
            if (decision == FetchFileDecision.SELECTED) {
                continue;
            }
            FetchFileObservation observation = new FetchFileObservation(RUN, "a.csv", 0L, null, decision, null);
            assertThat(observation.getDecision()).isEqualTo(decision);
            assertThat(observation.getDelivery()).isNull();
            assertThat(observation.getRemoteModifiedAt()).isNull();
        }
    }

    @Test
    void selectedObservationWithoutDeliveryIsAllowed() {
        assertThat(new FetchFileObservation(RUN, "a.csv", 1L, null, FetchFileDecision.SELECTED, null).getDelivery())
                .isNull();
    }

    @Test
    void taskRunIsRequired() {
        assertThatThrownBy(() -> new FetchFileObservation(null, "a.csv", 1L, null, FetchFileDecision.SELECTED, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fileNameMustBeBetween1And500Characters() {
        assertThatThrownBy(() -> new FetchFileObservation(RUN, null, 1L, null, FetchFileDecision.TOO_YOUNG, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FetchFileObservation(RUN, "", 1L, null, FetchFileDecision.TOO_YOUNG, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FetchFileObservation(RUN, "x".repeat(501), 1L, null,
                FetchFileDecision.TOO_YOUNG, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new FetchFileObservation(RUN, "x".repeat(500), 1L, null, FetchFileDecision.TOO_YOUNG, null)
                .getRemoteFileName()).hasSize(FetchFileObservation.MAX_FILE_NAME_LENGTH);
        assertThat(new FetchFileObservation(RUN, "x", 1L, null, FetchFileDecision.TOO_YOUNG, null)
                .getRemoteFileName()).isEqualTo("x");
    }

    @Test
    void byteSizeMayNotBeNegative() {
        assertThatThrownBy(() -> new FetchFileObservation(RUN, "a", -1L, null, FetchFileDecision.TOO_LARGE, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new FetchFileObservation(RUN, "a", 0L, null, FetchFileDecision.TOO_LARGE, null).getByteSize())
                .isZero();
    }

    @Test
    void decisionIsRequired() {
        assertThatThrownBy(() -> new FetchFileObservation(RUN, "a", 1L, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlySelectedObservationMayCarryDelivery() {
        for (FetchFileDecision decision : FetchFileDecision.values()) {
            if (decision == FetchFileDecision.SELECTED) {
                continue;
            }
            assertThatThrownBy(() -> new FetchFileObservation(RUN, "a", 1L, null, decision, DELIVERY))
                    .as("%s", decision).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
