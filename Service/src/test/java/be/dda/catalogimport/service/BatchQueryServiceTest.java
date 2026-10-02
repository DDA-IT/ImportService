package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.IssueGroupDao;
import be.dda.catalogimport.dao.MutationDao;
import be.dda.catalogimport.domain.ImportBatch;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BatchQueryServiceTest {

    @Mock
    private ImportBatchRepository batches;

    @Mock
    private MutationDao mutationDao;

    @Mock
    private IssueGroupDao issueGroupDao;

    @Mock
    private BatchProcessingClaims processingClaims;

    @InjectMocks
    private BatchQueryService service;

    @Test
    void processingActiveTrueWhenClaimIsAlive() {
        ImportBatch batch = mock(ImportBatch.class);
        when(batch.getId()).thenReturn(1L);
        when(batch.getDelivery()).thenReturn(mock());
        when(batch.getDelivery().getId()).thenReturn(10L);
        when(batch.getImportLink()).thenReturn(mock());
        when(batch.getImportLink().getId()).thenReturn(100L);
        when(batch.getImportLink().getCode()).thenReturn("LINK-001");
        when(batch.getImportLink().getSupplierOrganisation()).thenReturn(mock());
        when(batch.getImportLink().getSupplierOrganisation().getCode()).thenReturn("SUPPLIER");
        when(batch.getImportLink().getLibraryCode()).thenReturn("LIB-001");
        when(batch.getDefinitionRevision()).thenReturn(mock());
        when(batch.getDefinitionRevision().getId()).thenReturn(1000L);
        when(batch.getTaskRun()).thenReturn(null);
        when(batch.getAttemptNo()).thenReturn(1);
        when(batch.getStatus()).thenReturn(mock());
        when(batch.getStatus().name()).thenReturn("SCREENED");
        when(batch.getValidationResult()).thenReturn(null);
        when(batch.getStartedAt()).thenReturn(null);
        when(batch.getFinishedAt()).thenReturn(null);
        when(batch.getStagedRowCount()).thenReturn(5L);
        when(batch.getMutationProgressRowNumber()).thenReturn(0L);
        when(batch.getRawRecordCount()).thenReturn(5L);
        when(batch.getValidRecordCount()).thenReturn(5L);
        when(batch.getRejectedRecordCount()).thenReturn(0L);
        when(batch.getFilteredOutCount()).thenReturn(null);
        when(batch.getErrorBeforeFilterCount()).thenReturn(null);
        when(batch.getDuplicateIdentityCount()).thenReturn(null);
        when(batch.getNewCount()).thenReturn(5L);
        when(batch.getChangedCount()).thenReturn(null);
        when(batch.getUnchangedCount()).thenReturn(null);
        when(batch.getIdentityIncidentCount()).thenReturn(null);
        when(batch.getContentMutationCount()).thenReturn(null);
        when(batch.getBulkIncidentCount()).thenReturn(null);
        when(batch.getCriticalLineCount()).thenReturn(null);
        when(batch.getCriticalIssueCount()).thenReturn(null);
        when(batch.getWarningCount()).thenReturn(null);
        when(batch.getAwaitingApprovalCount()).thenReturn(null);
        when(batch.getCreationOutcome()).thenReturn(null);
        when(batch.getCreationScopeCount()).thenReturn(null);
        when(batch.getCreationCandidateCount()).thenReturn(null);
        when(batch.getBlockedCode()).thenReturn(null);
        when(batch.getBlockedReason()).thenReturn(null);
        when(batch.getBaselineAcceptedBy()).thenReturn(null);
        when(batch.getBaselineAcceptedAt()).thenReturn(null);
        when(batch.getBaselineAcceptReason()).thenReturn(null);
        when(batch.getCreatedAt()).thenReturn(null);
        when(batch.getCreatedBy()).thenReturn("user");

        when(batches.findDetailById(1L)).thenReturn(Optional.of(batch));
        when(processingClaims.isAlive(batch)).thenReturn(true);

        BatchQueryService.BatchDetail detail = service.getBatch(1L);

        assertThat(detail.processingActive()).isTrue();
    }

    @Test
    void processingActiveFalseWhenClaimIsNotAlive() {
        ImportBatch batch = mock(ImportBatch.class);
        when(batch.getId()).thenReturn(2L);
        when(batch.getDelivery()).thenReturn(mock());
        when(batch.getDelivery().getId()).thenReturn(20L);
        when(batch.getImportLink()).thenReturn(mock());
        when(batch.getImportLink().getId()).thenReturn(200L);
        when(batch.getImportLink().getCode()).thenReturn("LINK-002");
        when(batch.getImportLink().getSupplierOrganisation()).thenReturn(mock());
        when(batch.getImportLink().getSupplierOrganisation().getCode()).thenReturn("SUPPLIER2");
        when(batch.getImportLink().getLibraryCode()).thenReturn("LIB-002");
        when(batch.getDefinitionRevision()).thenReturn(mock());
        when(batch.getDefinitionRevision().getId()).thenReturn(2000L);
        when(batch.getTaskRun()).thenReturn(null);
        when(batch.getAttemptNo()).thenReturn(1);
        when(batch.getStatus()).thenReturn(mock());
        when(batch.getStatus().name()).thenReturn("SCREENED");
        when(batch.getValidationResult()).thenReturn(null);
        when(batch.getStartedAt()).thenReturn(null);
        when(batch.getFinishedAt()).thenReturn(null);
        when(batch.getStagedRowCount()).thenReturn(10L);
        when(batch.getMutationProgressRowNumber()).thenReturn(0L);
        when(batch.getRawRecordCount()).thenReturn(10L);
        when(batch.getValidRecordCount()).thenReturn(10L);
        when(batch.getRejectedRecordCount()).thenReturn(0L);
        when(batch.getFilteredOutCount()).thenReturn(null);
        when(batch.getErrorBeforeFilterCount()).thenReturn(null);
        when(batch.getDuplicateIdentityCount()).thenReturn(null);
        when(batch.getNewCount()).thenReturn(10L);
        when(batch.getChangedCount()).thenReturn(null);
        when(batch.getUnchangedCount()).thenReturn(null);
        when(batch.getIdentityIncidentCount()).thenReturn(null);
        when(batch.getContentMutationCount()).thenReturn(null);
        when(batch.getBulkIncidentCount()).thenReturn(null);
        when(batch.getCriticalLineCount()).thenReturn(null);
        when(batch.getCriticalIssueCount()).thenReturn(null);
        when(batch.getWarningCount()).thenReturn(null);
        when(batch.getAwaitingApprovalCount()).thenReturn(null);
        when(batch.getCreationOutcome()).thenReturn(null);
        when(batch.getCreationScopeCount()).thenReturn(null);
        when(batch.getCreationCandidateCount()).thenReturn(null);
        when(batch.getBlockedCode()).thenReturn(null);
        when(batch.getBlockedReason()).thenReturn(null);
        when(batch.getBaselineAcceptedBy()).thenReturn(null);
        when(batch.getBaselineAcceptedAt()).thenReturn(null);
        when(batch.getBaselineAcceptReason()).thenReturn(null);
        when(batch.getCreatedAt()).thenReturn(null);
        when(batch.getCreatedBy()).thenReturn("user");

        when(batches.findDetailById(2L)).thenReturn(Optional.of(batch));
        when(processingClaims.isAlive(batch)).thenReturn(false);

        BatchQueryService.BatchDetail detail = service.getBatch(2L);

        assertThat(detail.processingActive()).isFalse();
    }
}
