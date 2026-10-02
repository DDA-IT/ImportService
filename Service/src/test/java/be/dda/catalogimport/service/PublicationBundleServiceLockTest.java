package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportMutationRepository;
import be.dda.catalogimport.dao.PublicationBundleBatchRepository;
import be.dda.catalogimport.dao.PublicationBundleRepository;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.PublicationBundle;
import be.dda.catalogimport.domain.PublicationBundleBatch;
import be.dda.catalogimport.domain.PublicationTargetMode;
import be.dda.catalogimport.domain.ValidationResult;
import be.dda.catalogimport.service.PublicationBundleService.Membership;
import be.dda.catalogimport.service.testsupport.NoOpTransactionManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * S5-b: {@code PublicationBundleService.addBatches} vergrendelt de batches in oplopend id met NOWAIT, na het
 * bundelslot, en controleert status en lidmaatschap onder dat slot. Een bezet slot geeft meteen 409
 * {@code BATCH_BEING_PROCESSED}. Zonder Spring of database; de echte lock-uitvoering staat in
 * {@code NowaitLockTest} (Dao) en {@code BundleBatchLockHttpTest} (Web).
 */
class PublicationBundleServiceLockTest {

    private static final long BUNDLE_ID = 7L;

    private PublicationBundleRepository bundles;
    private PublicationBundleBatchRepository bundleBatches;
    private ImportBatchRepository batches;
    private PublicationBundleService service;

    @BeforeEach
    void setUp() {
        bundles = mock(PublicationBundleRepository.class);
        bundleBatches = mock(PublicationBundleBatchRepository.class);
        batches = mock(ImportBatchRepository.class);
        service = new PublicationBundleService(bundles, bundleBatches, batches, mock(ImportMutationRepository.class),
                new NoOpTransactionManager(), Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC));
        PublicationBundle bundle = new PublicationBundle("B-1", PublicationTargetMode.SIMULATION, "tester");
        ReflectionTestUtils.setField(bundle, "id", BUNDLE_ID);
        when(bundles.findByIdForUpdate(BUNDLE_ID)).thenReturn(Optional.of(bundle));
        when(bundleBatches.findByBatchIdAndActiveMarkerIsNotNull(anyLong())).thenReturn(Optional.empty());
        when(bundleBatches.saveAndFlush(any(PublicationBundleBatch.class))).thenAnswer(call -> {
            PublicationBundleBatch membership = call.getArgument(0);
            ReflectionTestUtils.setField(membership, "id", 1000L + membership.getBatch().getId());
            return membership;
        });
    }

    private ImportBatch screenedBatch(long id) {
        ImportLink link = new ImportLink("L", "Link", null, null, "LIB");
        ReflectionTestUtils.setField(link, "id", 50L);
        ImportBatch batch = new ImportBatch(null, link, null, 1, "tester");
        ReflectionTestUtils.setField(batch, "id", id);
        batch.setStatus(ImportBatchStatus.SCREENED);
        batch.setValidationResult(ValidationResult.VALID);
        when(batches.findByIdForUpdateNowait(id)).thenReturn(Optional.of(batch));
        return batch;
    }

    @Test
    void batchesAreLockedNowaitInAscendingIdAfterTheBundleLock() {
        screenedBatch(30L);
        screenedBatch(10L);
        screenedBatch(20L);

        List<Membership> result = service.addBatches(BUNDLE_ID, List.of(30L, 10L, 20L), "jan@example.test");

        InOrder order = inOrder(bundles, batches);
        order.verify(bundles).findByIdForUpdate(BUNDLE_ID);
        order.verify(batches).findByIdForUpdateNowait(10L);
        order.verify(batches).findByIdForUpdateNowait(20L);
        order.verify(batches).findByIdForUpdateNowait(30L);
        assertThat(result).extracting(Membership::batchId).containsExactly(10L, 20L, 30L);
        verify(batches, never()).findById(anyLong());
    }

    @Test
    void statusIsCheckedOnTheRowReturnedUnderTheLock() {
        ImportBatch batch = screenedBatch(10L);
        batch.setStatus(ImportBatchStatus.BASELINE_ACCEPTED);

        assertThatThrownBy(() -> service.addBatches(BUNDLE_ID, List.of(10L), "jan@example.test"))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo(PublicationBundleService.CODE_BATCH_NOT_BUNDLEABLE));
        verify(bundleBatches, never()).saveAndFlush(any());
    }

    @Test
    void membershipIsCheckedAfterTheBatchLockIsHeld() {
        screenedBatch(10L);
        when(bundleBatches.findByBatchIdAndActiveMarkerIsNotNull(10L))
                .thenReturn(Optional.of(mock(PublicationBundleBatch.class)));

        assertThatThrownBy(() -> service.addBatches(BUNDLE_ID, List.of(10L), "jan@example.test"))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo(PublicationBundleService.CODE_BATCH_ALREADY_IN_BUNDLE));
        InOrder order = inOrder(batches, bundleBatches);
        order.verify(batches).findByIdForUpdateNowait(10L);
        order.verify(bundleBatches).findByBatchIdAndActiveMarkerIsNotNull(10L);
    }

    @Test
    void anUnknownBatchStillGivesBatchNotFound() {
        when(batches.findByIdForUpdateNowait(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addBatches(BUNDLE_ID, List.of(99L), "jan@example.test"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aBusyBatchRowBecomesConflictBatchBeingProcessed() {
        screenedBatch(10L);
        when(batches.findByIdForUpdateNowait(20L)).thenThrow(new CannotAcquireLockException("could not obtain lock"));

        assertThatThrownBy(() -> service.addBatches(BUNDLE_ID, List.of(20L, 10L), "jan@example.test"))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("BATCH_BEING_PROCESSED"));
    }
}
