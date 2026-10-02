package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import be.dda.catalogimport.dao.CandidatePriceDao;
import be.dda.catalogimport.dao.CandidateReferenceDao;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.MutationDao;
import be.dda.catalogimport.dao.PriceObservationDao;
import be.dda.catalogimport.dao.PublicationBundleBatchRepository;
import be.dda.catalogimport.dao.SourceStateDao;
import be.dda.catalogimport.domain.Delivery;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.PublicationBundleBatch;
import be.dda.catalogimport.service.SourceStateBaselineService.BaselineAcceptance;
import be.dda.catalogimport.service.testsupport.NoOpTransactionManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * S5-c (beslissingslog 2026-10-01 "Stap 4 en 5 uitgewerkt"): accept-baseline is één transactie, alles of niets.
 * Koppeling NOWAIT, dan batch NOWAIT, dan voorwaarden, chunks en eindtransitie. Een bezet koppelingsslot of een
 * deadlock geeft 409 {@code BASELINE_ACCEPTANCE_IN_PROGRESS}, een bezet batchslot 409 {@code BATCH_BEING_PROCESSED};
 * elke fout geeft een rollback zonder statuswijziging. Zonder Spring of database; het echte terugdraaien tegen
 * PostgreSQL staat in {@code AcceptBaselineAtomicityTest} (Web).
 */
class SourceStateBaselineServiceAtomicityTest {

    private static final long BATCH_ID = 10L;
    private static final long LINK_ID = 50L;
    private static final String USER = "jan@example.test";
    private static final String REASON = "nulmeting";

    /** Telt begin/commit/rollback: bewijst dat er precies één transactie is. */
    private static final class CountingTransactionManager extends NoOpTransactionManager {
        int begun;
        int committed;
        int rolledBack;

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            begun++;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            committed++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rolledBack++;
        }
    }

    private SourceStateDao sourceState;
    private CandidatePriceDao candidatePrices;
    private CandidateReferenceDao candidateReferences;
    private PriceObservationDao observations;
    private MutationDao mutations;
    private ImportBatchRepository batches;
    private ImportLinkRepository links;
    private PublicationBundleBatchRepository bundleMemberships;
    private CountingTransactionManager transactions;
    private SourceStateBaselineService service;
    private ImportBatch batch;

    @BeforeEach
    void setUp() {
        sourceState = mock(SourceStateDao.class);
        candidatePrices = mock(CandidatePriceDao.class);
        candidateReferences = mock(CandidateReferenceDao.class);
        observations = mock(PriceObservationDao.class);
        mutations = mock(MutationDao.class);
        batches = mock(ImportBatchRepository.class);
        links = mock(ImportLinkRepository.class);
        bundleMemberships = mock(PublicationBundleBatchRepository.class);
        transactions = new CountingTransactionManager();
        service = new SourceStateBaselineService(sourceState, candidatePrices, candidateReferences, observations,
                mutations, batches, links, bundleMemberships, transactions,
                Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC));

        ImportLink link = new ImportLink("L", "Link", null, null, "LIB");
        ReflectionTestUtils.setField(link, "id", LINK_ID);
        Delivery delivery = mock(Delivery.class);
        when(delivery.getId()).thenReturn(7L);
        ImportDefinitionRevision revision = mock(ImportDefinitionRevision.class);
        when(revision.getIdentityProfileKind()).thenReturn(IdentityProfileKind.THREE_PART);
        batch = new ImportBatch(delivery, link, revision, 1, "tester");
        ReflectionTestUtils.setField(batch, "id", BATCH_ID);
        batch.setStatus(ImportBatchStatus.SCREENED);

        when(batches.findImportLinkIdById(BATCH_ID)).thenReturn(Optional.of(LINK_ID));
        when(links.findByIdForUpdateNowait(LINK_ID)).thenReturn(Optional.of(link));
        when(batches.findByIdForUpdateNowait(BATCH_ID)).thenReturn(Optional.of(batch));
        when(bundleMemberships.findByBatchIdAndActiveMarkerIsNotNull(BATCH_ID)).thenReturn(Optional.empty());
        when(sourceState.countRowsStaleSinceScreening(BATCH_ID, LINK_ID)).thenReturn(0L);
        // Drie chunks: (0,2], (2,4], (4,5].
        when(sourceState.nextChunkBoundary(BATCH_ID, 0L)).thenReturn(2L);
        when(sourceState.nextChunkBoundary(BATCH_ID, 2L)).thenReturn(4L);
        when(sourceState.nextChunkBoundary(BATCH_ID, 4L)).thenReturn(5L);
        when(sourceState.nextChunkBoundary(BATCH_ID, 5L)).thenReturn(null);
        when(mutations.skipOpenContentMutations(eq(BATCH_ID), anyString())).thenReturn(5);
    }

    @Test
    void theWholeAcceptanceIsExactlyOneCommittedTransaction() {
        BaselineAcceptance result = service.acceptBaseline(BATCH_ID, USER, REASON);

        assertThat(result.status()).isEqualTo("BASELINE_ACCEPTED");
        assertThat(result.skippedMutationCount()).isEqualTo(5);
        assertThat(transactions.begun).isEqualTo(1);
        assertThat(transactions.committed).isEqualTo(1);
        assertThat(transactions.rolledBack).isZero();
        verify(sourceState, times(3)).insertNewFromStage(any(), anyLong(), anyLong());
        verify(observations, times(3)).insertBasePriceObservations(any(), anyLong(), anyLong());
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.BASELINE_ACCEPTED);
        assertThat(batch.getBaselineAcceptedBy()).isEqualTo(USER);
        assertThat(batch.getBaselineAcceptReason()).isEqualTo(REASON);
    }

    @Test
    void theLinkIsLockedBeforeTheBatchAndBothBeforeAnyCheckOrWrite() {
        service.acceptBaseline(BATCH_ID, USER, REASON);

        InOrder order = inOrder(batches, links, bundleMemberships, sourceState, mutations);
        order.verify(batches).findImportLinkIdById(BATCH_ID);
        order.verify(links).findByIdForUpdateNowait(LINK_ID);
        order.verify(batches).findByIdForUpdateNowait(BATCH_ID);
        order.verify(bundleMemberships).findByBatchIdAndActiveMarkerIsNotNull(BATCH_ID);
        order.verify(sourceState).countRowsStaleSinceScreening(BATCH_ID, LINK_ID);
        order.verify(sourceState).insertNewFromStage(any(), eq(0L), eq(2L));
        order.verify(mutations).skipOpenContentMutations(eq(BATCH_ID), anyString());
        order.verify(batches).saveAndFlush(batch);
        // Geen enkele niet-vergrendelde lezing van de batch als entiteit: die zou een oude toestand meenemen.
        verify(batches, never()).findById(anyLong());
        verify(batches, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void aFailureInTheSecondChunkRollsBackWithoutFinishOrStatusChange() {
        doThrow(new UncheckedIOException(new IOException("simulated failure in chunk 2")))
                .when(sourceState).insertNewFromStage(any(), eq(2L), eq(4L));

        assertThatThrownBy(() -> service.acceptBaseline(BATCH_ID, USER, REASON))
                .hasRootCauseInstanceOf(IOException.class);

        assertThat(transactions.begun).isEqualTo(1);
        assertThat(transactions.committed).isZero();
        assertThat(transactions.rolledBack).isEqualTo(1);
        verify(sourceState, never()).insertNewFromStage(any(), eq(4L), eq(5L));
        verify(mutations, never()).skipOpenContentMutations(anyLong(), anyString());
        verify(batches, never()).saveAndFlush(any());
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(batch.getBaselineAcceptedBy()).isNull();
    }

    @Test
    void aBusyLinkGivesBaselineAcceptanceInProgressAndTouchesNothingElse() {
        when(links.findByIdForUpdateNowait(LINK_ID)).thenThrow(new CannotAcquireLockException("nowait"));

        assertThatThrownBy(() -> service.acceptBaseline(BATCH_ID, USER, REASON))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode())
                        .isEqualTo(SourceStateBaselineService.CODE_BASELINE_ACCEPTANCE_IN_PROGRESS));

        verify(batches, never()).findByIdForUpdateNowait(anyLong());
        verifyNoInteractions(sourceState, observations, mutations, bundleMemberships);
        assertThat(transactions.rolledBack).isEqualTo(1);
    }

    @Test
    void aBusyBatchGivesBatchBeingProcessed() {
        when(batches.findByIdForUpdateNowait(BATCH_ID)).thenThrow(new CannotAcquireLockException("nowait"));

        assertThatThrownBy(() -> service.acceptBaseline(BATCH_ID, USER, REASON))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode())
                        .isEqualTo(SourceStateBaselineService.CODE_BATCH_BEING_PROCESSED)
                        .isEqualTo("BATCH_BEING_PROCESSED"));

        verifyNoInteractions(sourceState, observations, mutations, bundleMemberships);
        assertThat(transactions.rolledBack).isEqualTo(1);
    }

    @Test
    void aDeadlockDuringTheChunksGivesBaselineAcceptanceInProgressNotBatchBeingProcessed() {
        doThrow(new DeadlockLoserDataAccessException("deadlock", null))
                .when(sourceState).updateChangedFromStage(any(), eq(2L), eq(4L));

        assertThatThrownBy(() -> service.acceptBaseline(BATCH_ID, USER, REASON))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode())
                        .isEqualTo(SourceStateBaselineService.CODE_BASELINE_ACCEPTANCE_IN_PROGRESS));

        verify(mutations, never()).skipOpenContentMutations(anyLong(), anyString());
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(transactions.rolledBack).isEqualTo(1);
    }

    @Test
    void anUnknownBatchIsNotFoundWithoutTakingAnyLock() {
        when(batches.findImportLinkIdById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acceptBaseline(99L, USER, REASON)).isInstanceOf(NotFoundException.class);

        verifyNoInteractions(links);
        verify(batches, never()).findByIdForUpdateNowait(anyLong());
    }

    @Test
    void theStatusIsCheckedOnTheRowReturnedUnderTheLock() {
        batch.setStatus(ImportBatchStatus.BASELINE_ACCEPTED);

        assertThatThrownBy(() -> service.acceptBaseline(BATCH_ID, USER, REASON))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode())
                        .isEqualTo(SourceStateBaselineService.CODE_BATCH_NOT_ACCEPTABLE));

        verify(sourceState, never()).insertNewFromStage(any(), anyLong(), anyLong());
        verify(mutations, never()).skipOpenContentMutations(anyLong(), anyString());
    }

    @Test
    void anActiveBundleMembershipUnderTheLockIsRefusedBeforeAnythingIsWritten() {
        when(bundleMemberships.findByBatchIdAndActiveMarkerIsNotNull(BATCH_ID))
                .thenReturn(Optional.of(mock(PublicationBundleBatch.class)));

        assertThatThrownBy(() -> service.acceptBaseline(BATCH_ID, USER, REASON))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode())
                        .isEqualTo(SourceStateBaselineService.CODE_BATCH_IN_PUBLICATION_BUNDLE));

        verify(sourceState, never()).countRowsStaleSinceScreening(anyLong(), anyLong());
        verify(sourceState, never()).insertNewFromStage(any(), anyLong(), anyLong());
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.SCREENED);
    }

    @Test
    void aStaleSourceStateIsRefusedBeforeAnythingIsWritten() {
        when(sourceState.countRowsStaleSinceScreening(BATCH_ID, LINK_ID)).thenReturn(3L);

        assertThatThrownBy(() -> service.acceptBaseline(BATCH_ID, USER, REASON))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode())
                        .isEqualTo(SourceStateBaselineService.CODE_SOURCE_STATE_CHANGED));

        verify(sourceState, never()).nextChunkBoundary(anyLong(), anyLong());
        verify(observations, never()).insertBasePriceObservations(any(), anyLong(), anyLong());
    }

    @Test
    void aReferenceRaceInALaterChunkRollsBackTheWholeAcceptance() {
        when(candidateReferences.hasReferences(BATCH_ID)).thenReturn(true);
        doThrow(new DuplicateKeyException("uk_catalog_reference_state_active"))
                .when(sourceState).insertReferencesFromStage(any(), eq(4L), eq(5L));

        assertThatThrownBy(() -> service.acceptBaseline(BATCH_ID, USER, REASON))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode())
                        .isEqualTo(SourceStateBaselineService.CODE_REFERENCE_ALREADY_ACTIVE));

        assertThat(transactions.committed).isZero();
        assertThat(transactions.rolledBack).isEqualTo(1);
        verify(mutations, never()).skipOpenContentMutations(anyLong(), anyString());
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.SCREENED);
    }

    @Test
    void invalidInputIsRefusedBeforeAnyTransaction() {
        assertThatThrownBy(() -> service.acceptBaseline(BATCH_ID, "  ", REASON))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.acceptBaseline(BATCH_ID, USER, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(transactions.begun).isZero();
    }
}
