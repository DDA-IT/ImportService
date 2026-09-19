package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportMutationRepository;
import be.dda.catalogimport.dao.ImportRowIssueRepository;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportMutation;
import be.dda.catalogimport.domain.ImportRowIssue;
import be.dda.catalogimport.domain.MutationActionType;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leest een screeningbatch, haar mutatielijst en haar regelproblemen als records; de Web-laag ziet
 * nooit JPA-entiteiten ({@code open-in-view} staat uit). De mutatielijst bevat bewust geen binaire
 * hashkolommen: die zijn niet op de entiteit gemapt en dienen enkel voor set-based SQL.
 * <p>
 * Paginering: {@code page} is 0-gebaseerd, {@code size} standaard {@value #DEFAULT_PAGE_SIZE} en
 * begrensd tot {@value #MAX_PAGE_SIZE} (een grotere waarde wordt teruggebracht tot het maximum; het
 * antwoord vermeldt de werkelijk gebruikte grootte). Een negatieve pagina of een grootte kleiner dan 1
 * is een ongeldige aanvraag.
 */
@Service
@Transactional(readOnly = true)
public class BatchQueryService {

    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 200;

    /**
     * De volledige stand van één screeningbatch; tellers zijn {@code null} wanneer ze onbekend zijn.
     * <p>
     * {@code validationResult} is het inhoudelijke eindoordeel naast {@code status} (fase 3,
     * afwijking D) en is {@code null} zolang de screening loopt of bij een technische fout.
     * <p>
     * {@code filteredOutCount} en {@code errorBeforeFilterCount} zijn additief toegevoegd in bouwstap
     * 3b (R-FLT-04). Samen met de bestaande tellers geldt
     * {@code raw = filteredOut + errorBeforeFilter + rejected + valid}. Zonder geconfigureerde
     * recordfilters staan beide op 0; {@code null} betekent zoals altijd "onbekend".
     */
    public record BatchDetail(long batchId, long deliveryId, long importLinkId, long definitionRevisionId,
                              Long taskRunId, int attemptNo, String status, String validationResult,
                              Instant startedAt, Instant finishedAt, long stagedRowCount,
                              long mutationProgressRowNumber,
                              Long rawRecordCount, Long validRecordCount, Long rejectedRecordCount,
                              Long filteredOutCount, Long errorBeforeFilterCount,
                              Long duplicateIdentityCount, Long newCount, Long changedCount,
                              Long unchangedCount, Long contentMutationCount, String blockedCode,
                              String blockedReason, String baselineAcceptedBy, Instant baselineAcceptedAt,
                              String baselineAcceptReason, Instant createdAt, String createdBy) {

        private static BatchDetail of(ImportBatch batch) {
            return new BatchDetail(batch.getId(), batch.getDelivery().getId(), batch.getImportLink().getId(),
                    batch.getDefinitionRevision().getId(),
                    batch.getTaskRun() == null ? null : batch.getTaskRun().getId(), batch.getAttemptNo(),
                    batch.getStatus().name(),
                    batch.getValidationResult() == null ? null : batch.getValidationResult().name(),
                    batch.getStartedAt(), batch.getFinishedAt(),
                    batch.getStagedRowCount(), batch.getMutationProgressRowNumber(), batch.getRawRecordCount(),
                    batch.getValidRecordCount(), batch.getRejectedRecordCount(),
                    batch.getFilteredOutCount(), batch.getErrorBeforeFilterCount(),
                    batch.getDuplicateIdentityCount(), batch.getNewCount(), batch.getChangedCount(),
                    batch.getUnchangedCount(), batch.getContentMutationCount(), batch.getBlockedCode(),
                    batch.getBlockedReason(), batch.getBaselineAcceptedBy(), batch.getBaselineAcceptedAt(),
                    batch.getBaselineAcceptReason(), batch.getCreatedAt(), batch.getCreatedBy());
        }
    }

    /** Eén regel van de mutatielijst (of de {@code IMPORT_MARKER}, dan zonder identiteit). */
    public record MutationRow(long id, String actionType, String targetDomain, String status,
                              String statusReason, String identitySupplier, String identitySupplierGroup,
                              String identitySupplierReference, String identityDiscountCode,
                              String identityDiscountState, String domainMask, BigDecimal beforeBasePrice,
                              BigDecimal afterBasePrice, String basePriceCurrency, Long sourceStateId,
                              Long sourceRowNumber, String resultSummary, String idempotencyKey,
                              Instant createdAt) {

        private static MutationRow of(ImportMutation mutation) {
            return new MutationRow(mutation.getId(), mutation.getActionType().name(),
                    mutation.getTargetDomain().name(), mutation.getStatus().name(), mutation.getStatusReason(),
                    mutation.getIdentitySupplier(), mutation.getIdentitySupplierGroup(),
                    mutation.getIdentitySupplierReference(), mutation.getIdentityDiscountCode(),
                    mutation.getIdentityDiscountState() == null ? null : mutation.getIdentityDiscountState().name(),
                    mutation.getDomainMask(), mutation.getBeforeBasePrice(), mutation.getAfterBasePrice(),
                    mutation.getBasePriceCurrency(), mutation.getSourceStateId(), mutation.getSourceRowNumber(),
                    mutation.getResultSummary(), mutation.getIdempotencyKey(), mutation.getCreatedAt());
        }
    }

    /**
     * Eén vastgesteld probleem; {@code sourceValue} is een afgekapt fragment.
     * <p>
     * {@code rowNumber} is sinds fase 3 <b>nullable</b>: een probleem op leverings- of
     * structuurniveau ({@code controlLevel}) hoort bij geen enkele bronregel. {@code severity} kan
     * naast {@code ERROR}/{@code WARNING} ook {@code CRITICAL}, {@code BLOCKING} of {@code INFO}
     * zijn. {@code issueDomain}, {@code controlLevel}, {@code impactScope} en {@code handlingStatus}
     * zijn additief toegevoegd.
     */
    public record IssueRow(long id, Long rowNumber, String issueCode, String fieldName, String severity,
                           String issueDomain, String controlLevel, String impactScope,
                           String handlingStatus, String sourceValue, String expectedValue,
                           String message, Instant createdAt) {

        private static IssueRow of(ImportRowIssue issue) {
            return new IssueRow(issue.getId(), issue.getRowNumber(), issue.getIssueCode(), issue.getFieldName(),
                    issue.getSeverity().name(), issue.getIssueDomain().name(), issue.getControlLevel().name(),
                    issue.getImpactScope().name(), issue.getHandlingStatus().name(), issue.getSourceValue(),
                    issue.getExpectedValue(), issue.getMessage(), issue.getCreatedAt());
        }
    }

    private final ImportBatchRepository batches;
    private final ImportMutationRepository mutations;
    private final ImportRowIssueRepository issues;

    public BatchQueryService(ImportBatchRepository batches, ImportMutationRepository mutations,
                             ImportRowIssueRepository issues) {
        this.batches = batches;
        this.mutations = mutations;
        this.issues = issues;
    }

    /** @throws NotFoundException onbekende batch ({@code BATCH_NOT_FOUND}) */
    public BatchDetail getBatch(long batchId) {
        return BatchDetail.of(batches.findById(batchId).orElseThrow(() -> notFound(batchId)));
    }

    /**
     * De mutatielijst van een batch, oplopend op id (= volgorde van aanmaak), optioneel gefilterd op
     * {@code actionType}.
     *
     * @throws NotFoundException        onbekende batch
     * @throws IllegalArgumentException ongeldige paginering
     */
    public PageResult<MutationRow> getMutations(long batchId, MutationActionType actionType, Integer page,
                                                Integer size) {
        requireBatch(batchId);
        PageRequest pageRequest = pageRequest(page, size, Sort.by("id"));
        Page<ImportMutation> result = actionType == null
                ? mutations.findByBatchId(batchId, pageRequest)
                : mutations.findByBatchIdAndActionType(batchId, actionType, pageRequest);
        return PageResult.of(result, MutationRow::of);
    }

    /**
     * De regelproblemen van een batch, oplopend op bronregelnummer.
     *
     * @throws NotFoundException        onbekende batch
     * @throws IllegalArgumentException ongeldige paginering
     */
    public PageResult<IssueRow> getIssues(long batchId, Integer page, Integer size) {
        requireBatch(batchId);
        return PageResult.of(issues.findByBatchId(batchId, pageRequest(page, size, Sort.by("rowNumber", "id"))),
                IssueRow::of);
    }

    private void requireBatch(long batchId) {
        if (!batches.existsById(batchId)) {
            throw notFound(batchId);
        }
    }

    private static NotFoundException notFound(long batchId) {
        return new NotFoundException("BATCH_NOT_FOUND", "Batch " + batchId + " not found");
    }

    private static PageRequest pageRequest(Integer page, Integer size, Sort sort) {
        int number = page == null ? 0 : page;
        int requested = size == null ? DEFAULT_PAGE_SIZE : size;
        if (number < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (requested < 1) {
            throw new IllegalArgumentException("size must be at least 1");
        }
        return PageRequest.of(number, Math.min(requested, MAX_PAGE_SIZE), sort);
    }
}
