package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import be.dda.catalogimport.dao.CandidatePriceDao;
import be.dda.catalogimport.dao.CandidateReferenceDao;
import be.dda.catalogimport.dao.CandidateStageDao;
import be.dda.catalogimport.dao.DeliveryFileRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.IssueGroupDao;
import be.dda.catalogimport.dao.MutationDao;
import be.dda.catalogimport.dao.PriceDeviationDao;
import be.dda.catalogimport.dao.ReferenceControlDao;
import be.dda.catalogimport.dao.RowIssueDao;
import be.dda.catalogimport.dao.SourceStateDao;
import be.dda.catalogimport.dao.TaskRunRepository;
import be.dda.catalogimport.domain.Delivery;
import be.dda.catalogimport.domain.DeliveryFile;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.service.DeliveryScreeningService.ScreeningOutcome;
import be.dda.catalogimport.service.support.ImportIssueCatalog;
import be.dda.catalogimport.service.support.ImportMappingConfig;
import be.dda.catalogimport.service.support.ImportMappingConfigFactory;
import be.dda.catalogimport.service.support.ScreeningBlockedException;
import be.dda.catalogimport.service.support.SourceStructureConfig;
import be.dda.catalogimport.service.support.SourceStructureConfigFactory;
import be.dda.catalogimport.service.testsupport.NoOpTransactionManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Verwerkingsclaim en fencing in de screening (stap S4-b, beslissingslog 2026-10-01 "Stap 4 en 5 uitgewerkt").
 * <p>
 * Zonder Spring-context of database: repositories en DAO's zijn Mockito-mocks, de transactiemanager doet niets en
 * {@link BatchProcessingClaims} is echt (vaste klok, instantie {@code host-1}, boot {@code boot-a}). De
 * fencing-update ({@link ImportBatchRepository#touchProcessingClaim}) is gestubd: 1 = claim nog van ons, 0 = claim
 * verloren. Elke {@code saveAndFlush} van de batch wordt als momentopname (status + token) bijgehouden, zodat te zien
 * is in welke wijziging de claim genomen en vrijgegeven wordt.
 */
class DeliveryScreeningServiceClaimTest {

    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");
    private static final long BATCH_ID = 42L;
    private static final long DELIVERY_ID = 7L;
    private static final long FILE_ID = 9L;
    private static final long LINK_ID = 3L;
    private static final long REVISION_ID = 5L;

    private record Snapshot(ImportBatchStatus status, UUID token, String claimedBy) {
    }

    private DeliveryArchiveStore archive;
    private SourceStructureConfigFactory configFactory;
    private ImportMappingConfigFactory mappingConfigFactory;
    private ImportBatchRepository batches;
    private DeliveryFileRepository deliveryFiles;
    private CandidateStageDao stage;
    private CandidatePriceDao candidatePrices;
    private CandidateReferenceDao candidateReferences;
    private PriceDeviationDao deviations;
    private RowIssueDao rowIssues;
    private IssueGroupDao issueGroups;
    private IssueAggregationService aggregation;
    private MutationDao mutations;
    private DeliveryScreeningService service;
    private ImportBatch batch;
    private final List<Snapshot> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        archive = mock(DeliveryArchiveStore.class);
        configFactory = mock(SourceStructureConfigFactory.class);
        mappingConfigFactory = mock(ImportMappingConfigFactory.class);
        batches = mock(ImportBatchRepository.class);
        deliveryFiles = mock(DeliveryFileRepository.class);
        TaskRunRepository runs = mock(TaskRunRepository.class);
        stage = mock(CandidateStageDao.class);
        candidatePrices = mock(CandidatePriceDao.class);
        candidateReferences = mock(CandidateReferenceDao.class);
        deviations = mock(PriceDeviationDao.class);
        ReferenceControlDao referenceControl = mock(ReferenceControlDao.class);
        SourceStateDao sourceState = mock(SourceStateDao.class);
        rowIssues = mock(RowIssueDao.class);
        issueGroups = mock(IssueGroupDao.class);
        aggregation = mock(IssueAggregationService.class);
        IssueCaseSyncService issueCaseSync = mock(IssueCaseSyncService.class);
        mutations = mock(MutationDao.class);

        Delivery delivery = mock(Delivery.class);
        when(delivery.getId()).thenReturn(DELIVERY_ID);
        ImportLink link = mock(ImportLink.class);
        when(link.getId()).thenReturn(LINK_ID);
        when(link.getLibraryCode()).thenReturn("PSARF050");
        ImportDefinitionRevision revision = mock(ImportDefinitionRevision.class);
        when(revision.getId()).thenReturn(REVISION_ID);
        DeliveryFile file = mock(DeliveryFile.class);
        when(file.getId()).thenReturn(FILE_ID);
        when(file.getArchiveReference()).thenReturn("archive/7/levering.csv");
        when(deliveryFiles.findByDeliveryIdOrderBySequenceNumberAsc(DELIVERY_ID)).thenReturn(List.of(file));

        batch = new ImportBatch(delivery, link, revision, 1, "tester");
        ReflectionTestUtils.setField(batch, "id", BATCH_ID);
        when(batches.findById(BATCH_ID)).thenReturn(Optional.of(batch));
        when(batches.findByIdForUpdate(BATCH_ID)).thenReturn(Optional.of(batch));
        when(batches.saveAndFlush(any(ImportBatch.class))).thenAnswer(call -> {
            ImportBatch b = call.getArgument(0);
            saved.add(new Snapshot(b.getStatus(), b.getProcessingClaimToken(), b.getProcessingClaimedBy()));
            return b;
        });
        when(batches.touchProcessingClaim(eq(BATCH_ID), any(), any())).thenAnswer(call -> {
            UUID token = call.getArgument(1);
            return token != null && token.equals(batch.getProcessingClaimToken()) ? 1 : 0;
        });
        when(aggregation.aggregate(anyLong(), any(), any(), any(), anyInt()))
                .thenReturn(new IssueAggregationService.Aggregation(0, 0));
        // Mockito geeft voor een Long-returntype 0 terug, niet null: zonder deze stub zou de chunklus nooit eindigen.
        when(mutations.nextChunkBoundary(anyLong(), anyLong())).thenReturn(null);

        BatchProcessingClaims claims = new BatchProcessingClaims(batches, Clock.fixed(NOW, ZoneOffset.UTC),
                "PT60M", "host-1", "boot-a");
        service = new DeliveryScreeningService(archive, configFactory, mappingConfigFactory, batches, deliveryFiles,
                runs, stage, candidatePrices, candidateReferences, deviations, referenceControl, sourceState,
                rowIssues, issueGroups, aggregation, issueCaseSync, mutations, new NoOpTransactionManager(), claims,
                2000, 200, 100000);
    }

    // --- Hulp --------------------------------------------------------------------------------------------------

    /** Een configuratiefout: de screening blokkeert meteen ná de start (het kortste pad met een eindstatus). */
    private void configurationFails() {
        when(configFactory.from(any(), any()))
                .thenThrow(new ScreeningBlockedException(ImportIssueCatalog.SOURCE_NO_DATA_RECORDS, "test blockage"));
    }

    /** Een bruikbare configuratie maar een onleesbaar archief: een technische fout tijdens het stagen. */
    private void archiveIsUnreadable() {
        when(configFactory.from(any(), any())).thenReturn(mock(SourceStructureConfig.class));
        when(mappingConfigFactory.from(any(), any())).thenReturn(mock(ImportMappingConfig.class));
        when(archive.open(anyString())).thenThrow(new UncheckedIOException(new IOException("disk gone")));
    }

    private void mutating(UUID token, String claimedBy, Instant heartbeat) {
        batch.setStatus(ImportBatchStatus.MUTATING);
        if (token != null) {
            batch.claimProcessing(token, claimedBy, heartbeat);
        }
    }

    /** Laat de hervatte verwerking in pass D eindigen op BLOCKED (hashcollisie), zonder de volledige delta. */
    private void identityCollisionOnResume() {
        when(stage.findIdentityHashCollisionRow(BATCH_ID)).thenReturn(OptionalLong.of(5L));
    }

    private static void assertBeingProcessed(Throwable thrown) {
        assertThat(thrown).isInstanceOfSatisfying(ConflictException.class, conflict ->
                assertThat(conflict.getCode()).isEqualTo(BatchProcessingClaims.CODE_BATCH_BEING_PROCESSED));
    }

    // --- Claim nemen bij de start --------------------------------------------------------------------------------

    @Test
    void theStartTakesTheClaimInTheSameChangeAsTheTransitionToScreening() {
        configurationFails();

        service.screen(BATCH_ID);

        Snapshot start = saved.get(0);
        assertThat(start.status()).isEqualTo(ImportBatchStatus.SCREENING);
        assertThat(start.token()).isNotNull();
        assertThat(start.claimedBy()).isEqualTo("host-1/boot-a");
        verify(batches).findByIdForUpdate(BATCH_ID);
        // De blokkerende transactie is gefenced op dezelfde token.
        verify(batches).touchProcessingClaim(BATCH_ID, start.token(), NOW);
    }

    @Test
    void blockReleasesTheClaimInTheSameChangeAsTheTerminalStatus() {
        configurationFails();

        ScreeningOutcome outcome = service.screen(BATCH_ID);

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.BLOCKED);
        Snapshot last = saved.get(saved.size() - 1);
        assertThat(last.status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(last.token()).isNull();
        assertThat(batch.getProcessingHeartbeatAt()).isNull();
        assertThat(batch.getProcessingClaimedBy()).isNull();
    }

    @Test
    void aBatchThatIsNotReceivedIsRefusedWithoutAClaim() {
        batch.setStatus(ImportBatchStatus.SCREENING);

        assertThatThrownBy(() -> service.screen(BATCH_ID))
                .isInstanceOfSatisfying(ConflictException.class,
                        conflict -> assertThat(conflict.getCode()).isEqualTo("BATCH_NOT_SCREENABLE"));
        assertThat(batch.getProcessingClaimToken()).isNull();
        assertThat(saved).isEmpty();
    }

    // --- fail(): gefenced en vrijgevend --------------------------------------------------------------------------

    @Test
    void failReleasesTheClaimInTheSameChangeAsFailed() {
        archiveIsUnreadable();

        assertThatThrownBy(() -> service.screen(BATCH_ID)).isInstanceOf(UncheckedIOException.class);

        Snapshot last = saved.get(saved.size() - 1);
        assertThat(last.status()).isEqualTo(ImportBatchStatus.FAILED);
        assertThat(last.token()).isNull();
        verify(stage).deleteByBatchId(BATCH_ID);
        verify(rowIssues).deleteByBatchId(BATCH_ID);
    }

    @Test
    void failWithALostClaimCleansNothingUp() {
        archiveIsUnreadable();
        // Elke fencing-update na de start vindt geen rij meer: een andere worker nam de batch over.
        when(batches.touchProcessingClaim(eq(BATCH_ID), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.screen(BATCH_ID)).isInstanceOf(UncheckedIOException.class);

        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.SCREENING);
        assertThat(batch.getProcessingClaimToken()).isNotNull();
        verify(stage, never()).deleteByBatchId(anyLong());
        verify(rowIssues, never()).deleteByBatchId(anyLong());
        verify(candidatePrices, never()).deleteByBatchId(anyLong());
        verify(issueGroups, never()).deleteByBatchId(anyLong());
        assertThat(saved).extracting(Snapshot::status).doesNotContain(ImportBatchStatus.FAILED);
    }

    @Test
    void aClaimLostBeforeBlockingStopsWithout409CleanupOrMarker() {
        configurationFails();
        when(batches.touchProcessingClaim(eq(BATCH_ID), any(), any())).thenReturn(0);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> service.screen(BATCH_ID));

        assertBeingProcessed(thrown);
        assertThat(thrown.getCause()).isInstanceOf(ClaimLostException.class);
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.SCREENING);
        verify(mutations, never()).insertMarker(any(), anyString(), any());
        verify(stage, never()).deleteByBatchId(anyLong());
        verify(rowIssues, never()).deleteByBatchId(anyLong());
    }

    // --- Hervatten -----------------------------------------------------------------------------------------------

    @Test
    void resumingABatchThatIsNotMutatingGives409WithoutTakingTheLock() {
        batch.setStatus(ImportBatchStatus.SCREENED);

        assertThatThrownBy(() -> service.continueMutating(BATCH_ID))
                .isInstanceOfSatisfying(ConflictException.class, conflict -> assertThat(conflict.getCode())
                        .isEqualTo(DeliveryScreeningService.CODE_BATCH_NOT_RESUMABLE));
        verify(batches, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void anUnknownBatchGives404() {
        when(batches.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.continueMutating(99L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aLiveClaimOfAnotherInstanceRefusesTheResumeWith409AndChangesNothing() {
        UUID theirs = UUID.randomUUID();
        mutating(theirs, "host-2/boot-x", NOW.minus(Duration.ofMinutes(10)));

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> service.continueMutating(BATCH_ID));

        assertBeingProcessed(thrown);
        assertThat(batch.getProcessingClaimToken()).isEqualTo(theirs);
        assertThat(saved).isEmpty();
        verify(batches, never()).touchProcessingClaim(anyLong(), any(), any());
    }

    @Test
    void aStatusChangedUnderTheLockIsRecheckedBeforeClaiming() {
        mutating(null, null, null);
        // Zonder slot nog MUTATING; onder het slot is een gelijktijdige hervatting al klaar.
        ImportBatch meanwhileScreened = new ImportBatch(batch.getDelivery(), batch.getImportLink(),
                batch.getDefinitionRevision(), 1, "tester");
        ReflectionTestUtils.setField(meanwhileScreened, "id", BATCH_ID);
        meanwhileScreened.setStatus(ImportBatchStatus.SCREENED);
        when(batches.findByIdForUpdate(BATCH_ID)).thenReturn(Optional.of(meanwhileScreened));

        assertThatThrownBy(() -> service.continueMutating(BATCH_ID))
                .isInstanceOfSatisfying(ConflictException.class, conflict -> assertThat(conflict.getCode())
                        .isEqualTo(DeliveryScreeningService.CODE_BATCH_NOT_RESUMABLE));
        assertThat(meanwhileScreened.getProcessingClaimToken()).isNull();
    }

    @Test
    void aClaimWithAnExpiredLeaseIsTakenOverOnResume() {
        UUID theirs = UUID.randomUUID();
        mutating(theirs, "host-2/boot-x", NOW.minus(Duration.ofMinutes(61)));
        identityCollisionOnResume();

        ScreeningOutcome outcome = service.continueMutating(BATCH_ID);

        Snapshot claimed = saved.get(0);
        assertThat(claimed.status()).isEqualTo(ImportBatchStatus.MUTATING);
        assertThat(claimed.token()).isNotNull().isNotEqualTo(theirs);
        assertThat(claimed.claimedBy()).isEqualTo("host-1/boot-a");
        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(batch.getProcessingClaimToken()).isNull();
    }

    @Test
    void aClaimOfAPreviousBootOfThisInstanceIsTakenOverOnResume() {
        UUID previous = UUID.randomUUID();
        mutating(previous, "host-1/boot-old", NOW.minusSeconds(30));
        identityCollisionOnResume();

        service.continueMutating(BATCH_ID);

        Snapshot claimed = saved.get(0);
        assertThat(claimed.token()).isNotNull().isNotEqualTo(previous);
        assertThat(claimed.claimedBy()).isEqualTo("host-1/boot-a");
    }

    @Test
    void completeReleasesTheClaimInTheSameChangeAsScreened() {
        mutating(null, null, null);

        ScreeningOutcome outcome = service.continueMutating(BATCH_ID);

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        Snapshot last = saved.get(saved.size() - 1);
        assertThat(last.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(last.token()).isNull();
        // Elke schrijvende transactie (claim, E4, drempels, creatiebeleid, afronden) was gefenced op de eigen token.
        ArgumentCaptor<UUID> tokens = ArgumentCaptor.forClass(UUID.class);
        verify(batches, org.mockito.Mockito.atLeast(3)).touchProcessingClaim(eq(BATCH_ID), tokens.capture(), eq(NOW));
        assertThat(tokens.getAllValues()).containsOnly(saved.get(0).token());
        verify(mutations).insertMarker(any(), anyString(), any());
    }

    @Test
    void aClaimLostMidwayStopsWith409WithoutWritingOrCleaningUp() {
        mutating(null, null, null);
        // Pass E1 heeft één chunk; de fencing-update ervoor vindt de eigen token niet meer.
        when(mutations.nextChunkBoundary(eq(BATCH_ID), anyLong())).thenReturn(10L, (Long) null);
        when(batches.touchProcessingClaim(eq(BATCH_ID), any(), any())).thenReturn(0);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> service.continueMutating(BATCH_ID));

        assertBeingProcessed(thrown);
        verify(mutations, never()).classifyChunk(anyLong(), anyLong(), anyLong(), anyLong());
        verify(mutations, never()).insertMarker(any(), anyString(), any());
        verify(stage, never()).deleteByBatchId(anyLong());
        verify(rowIssues, never()).deleteByBatchId(anyLong());
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.MUTATING);
        // De claim is niet vrijgegeven door deze worker: ze is van een ander (hier: de token die hij nam).
        assertThat(batch.getProcessingClaimToken()).isNotNull();
    }

    @Test
    void aTechnicalFailureWhileMutatingReleasesTheClaimSoTheBatchCanBeResumedAtOnce() {
        mutating(null, null, null);
        when(mutations.nextChunkBoundary(eq(BATCH_ID), anyLong())).thenReturn(10L, (Long) null);
        org.mockito.Mockito.doThrow(new UncheckedIOException(new IOException("connection reset")))
                .when(mutations).classifyChunk(anyLong(), anyLong(), anyLong(), anyLong());

        assertThatThrownBy(() -> service.continueMutating(BATCH_ID)).isInstanceOf(UncheckedIOException.class);

        // Geen eindstatus (blijft hervatbaar), geen opruiming, maar de claim is vrij.
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.MUTATING);
        assertThat(batch.getProcessingClaimToken()).isNull();
        verify(stage, never()).deleteByBatchId(anyLong());

        // Meteen opnieuw hervatten lukt, zonder op de lease te wachten.
        org.mockito.Mockito.reset(mutations);
        when(mutations.nextChunkBoundary(eq(BATCH_ID), anyLong())).thenReturn(null);
        assertThat(service.continueMutating(BATCH_ID).status()).isEqualTo(ImportBatchStatus.SCREENED);
    }
}
