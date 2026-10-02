package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Configuratie en claimlogica van {@link BatchProcessingClaims} (stap S4-b, beslissingslog 2026-10-01): lease
 * default PT60M en fail-fast, instance-id default hostnaam en zonder {@code /}, eigenaar
 * {@code <instanceId>/<bootId>}, overname enkel van een dode claim, fencing via de repository.
 */
class BatchProcessingClaimsTest {

    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private ImportBatchRepository batches;

    @BeforeEach
    void setUp() {
        batches = mock(ImportBatchRepository.class);
    }

    private BatchProcessingClaims claims(String lease, String instanceId, String bootId) {
        return new BatchProcessingClaims(batches, CLOCK, lease, instanceId, bootId);
    }

    private static ImportBatch openBatch() {
        ImportBatch batch = new ImportBatch(null, null, null, 1, "tester");
        ReflectionTestUtils.setField(batch, "id", 42L);
        batch.setStatus(ImportBatchStatus.MUTATING);
        return batch;
    }

    // --- Configuratie --------------------------------------------------------------------------------------

    @Test
    void theDefaultLeaseIsSixtyMinutes() {
        assertThat(BatchProcessingClaims.parseLease(BatchProcessingClaims.DEFAULT_LEASE))
                .isEqualTo(Duration.ofMinutes(60));
        assertThat(claims("PT60M", "host-1", "boot-a").lease()).isEqualTo(Duration.ofMinutes(60));
    }

    @Test
    void anInvalidZeroOrNegativeLeaseStopsTheStartup() {
        for (String invalid : new String[] {"", "  ", "sixty", "60", "PT0S", "-PT1M", null}) {
            assertThatThrownBy(() -> claims(invalid, "host-1", "boot-a"))
                    .as("lease '%s'", invalid)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("catalogimport.screening.claim-lease");
        }
    }

    @Test
    void anInstanceIdWithASlashStopsTheStartup() {
        assertThatThrownBy(() -> claims("PT60M", "region/host-1", "boot-a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not contain '/'");
    }

    @Test
    void anInstanceIdWithControlCharactersOrTooLongStopsTheStartup() {
        assertThatThrownBy(() -> claims("PT60M", "host\n1", "boot-a"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("control characters");
        String tooLong = "h".repeat(BatchProcessingClaims.MAX_INSTANCE_ID_LENGTH + 1);
        assertThatThrownBy(() -> claims("PT60M", tooLong, UUID.randomUUID().toString()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("at most 63");
    }

    @Test
    void theLongestAllowedInstanceIdStillFitsTheColumnWithABootUuid() {
        String longest = "h".repeat(BatchProcessingClaims.MAX_INSTANCE_ID_LENGTH);
        BatchProcessingClaims claims = claims("PT60M", longest, UUID.randomUUID().toString());
        assertThat(claims.claimedBy()).hasSize(BatchProcessingClaims.MAX_CLAIMED_BY_LENGTH);
    }

    @Test
    void aBlankInstanceIdDefaultsToTheHostName() {
        BatchProcessingClaims claims = claims("PT60M", "  ", "boot-a");
        assertThat(claims.instanceId()).isNotBlank().doesNotContain("/");
        assertThat(claims.claimedBy()).isEqualTo(claims.instanceId() + "/boot-a");
    }

    @Test
    void theInstanceIdIsTrimmedAndTheOwnerIsInstanceSlashBoot() {
        BatchProcessingClaims claims = claims("PT60M", "  host-1 ", "boot-a");
        assertThat(claims.instanceId()).isEqualTo("host-1");
        assertThat(claims.claimedBy()).isEqualTo("host-1/boot-a");
    }

    @Test
    void theSpringConstructorGivesEveryStartANewBootId() {
        BatchProcessingClaims first = new BatchProcessingClaims(batches, CLOCK, "PT60M", "host-1");
        BatchProcessingClaims second = new BatchProcessingClaims(batches, CLOCK, "PT60M", "host-1");
        assertThat(first.bootId()).isNotEqualTo(second.bootId());
        assertThat(UUID.fromString(first.bootId())).isNotNull();
    }

    // --- Claim nemen ---------------------------------------------------------------------------------------

    @Test
    void aBatchWithoutClaimIsClaimedWithTheOwnAndTheClockTime() {
        ImportBatch batch = openBatch();
        UUID token = claims("PT60M", "host-1", "boot-a").claim(batch);

        assertThat(batch.getProcessingClaimToken()).isEqualTo(token);
        assertThat(batch.getProcessingClaimedBy()).isEqualTo("host-1/boot-a");
        assertThat(batch.getProcessingClaimedAt()).isEqualTo(NOW);
        assertThat(batch.getProcessingHeartbeatAt()).isEqualTo(NOW);
    }

    @Test
    void aLiveClaimOfAnotherInstanceIsRefusedWith409AndLeftUntouched() {
        ImportBatch batch = openBatch();
        UUID theirs = UUID.randomUUID();
        batch.claimProcessing(theirs, "host-2/boot-x", NOW.minus(Duration.ofMinutes(59)));

        assertThatThrownBy(() -> claims("PT60M", "host-1", "boot-a").claim(batch))
                .isInstanceOfSatisfying(ConflictException.class, conflict -> {
                    assertThat(conflict.getCode()).isEqualTo(BatchProcessingClaims.CODE_BATCH_BEING_PROCESSED);
                    // De eigenaar (hostnaam) lekt niet naar het HTTP-antwoord.
                    assertThat(conflict.getMessage()).doesNotContain("host-2");
                });
        assertThat(batch.getProcessingClaimToken()).isEqualTo(theirs);
    }

    @Test
    void aClaimWithAnExpiredLeaseIsTakenOver() {
        ImportBatch batch = openBatch();
        UUID theirs = UUID.randomUUID();
        batch.claimProcessing(theirs, "host-2/boot-x", NOW.minus(Duration.ofMinutes(60)).minusMillis(1));

        UUID mine = claims("PT60M", "host-1", "boot-a").claim(batch);

        assertThat(mine).isNotEqualTo(theirs);
        assertThat(batch.getProcessingClaimToken()).isEqualTo(mine);
        assertThat(batch.getProcessingClaimedBy()).isEqualTo("host-1/boot-a");
    }

    @Test
    void aClaimOfAPreviousBootOfThisInstanceIsTakenOverAtOnce() {
        ImportBatch batch = openBatch();
        batch.claimProcessing(UUID.randomUUID(), "host-1/boot-old", NOW.minusSeconds(5));

        UUID mine = claims("PT60M", "host-1", "boot-new").claim(batch);

        assertThat(batch.getProcessingClaimToken()).isEqualTo(mine);
        assertThat(batch.getProcessingClaimedBy()).isEqualTo("host-1/boot-new");
    }

    @Test
    void aLiveClaimOfThisVeryBootIsAlsoRefused() {
        ImportBatch batch = openBatch();
        batch.claimProcessing(UUID.randomUUID(), "host-1/boot-a", NOW.minusSeconds(5));

        assertThatThrownBy(() -> claims("PT60M", "host-1", "boot-a").claim(batch))
                .isInstanceOf(ConflictException.class);
    }

    // --- Fencing -------------------------------------------------------------------------------------------

    @Test
    void touchWithTheCurrentTokenPassesTheClockTime() {
        UUID token = UUID.randomUUID();
        when(batches.touchProcessingClaim(42L, token, NOW)).thenReturn(1);

        claims("PT60M", "host-1", "boot-a").touch(42L, token);

        verify(batches).touchProcessingClaim(42L, token, NOW);
    }

    @Test
    void touchThatFindsNoRowMeansTheClaimIsLost() {
        UUID token = UUID.randomUUID();
        when(batches.touchProcessingClaim(eq(42L), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> claims("PT60M", "host-1", "boot-a").touch(42L, token))
                .isInstanceOfSatisfying(ClaimLostException.class,
                        lost -> assertThat(lost.getBatchId()).isEqualTo(42L));
    }

    @Test
    void touchWithoutTokenIsALostClaimWithoutQuery() {
        assertThatThrownBy(() -> claims("PT60M", "host-1", "boot-a").touch(42L, null))
                .isInstanceOf(ClaimLostException.class);
        verify(batches, never()).touchProcessingClaim(anyLong(), any(), any());
    }
}
