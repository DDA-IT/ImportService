package be.dda.catalogimport.service;

import be.dda.catalogimport.domain.Delivery;
import be.dda.catalogimport.domain.DeliveryFile;
import be.dda.catalogimport.domain.ImportBatch;
import java.time.Instant;
import java.util.List;

/**
 * Leesmodel van een levering met haar bestand(en) en de meest recente batch. Wordt door de Service
 * teruggegeven zodat de Web-laag nooit JPA-entiteiten ziet ({@code open-in-view} staat uit).
 * <p>
 * Tellers zijn nullable: {@code null} betekent onbekend en is nooit stil {@code 0}.
 * {@code completenessProven} is in Fase 2 altijd {@code false} (design par. 6, aanname A6).
 * Het interne archiefpad wordt bewust niet blootgesteld.
 */
public record DeliveryView(
        long deliveryId,
        long taskId,
        Long taskRunId,
        String idempotencyKey,
        Instant receivedAt,
        Integer expectedFileCount,
        int actualFileCount,
        Long expectedRecordCount,
        Long actualRecordCount,
        Long expectedByteSize,
        long actualByteSize,
        boolean completenessProven,
        String manifestReference,
        List<FileView> files,
        BatchView batch) {

    /** Bouwt de view uit entiteiten; enkel binnen een transactie aanroepen (lazy associaties). */
    static DeliveryView of(Delivery delivery, List<DeliveryFile> files, ImportBatch batch) {
        return new DeliveryView(
                delivery.getId(),
                delivery.getTask().getId(),
                delivery.getTaskRun() == null ? null : delivery.getTaskRun().getId(),
                delivery.getIdempotencyKey(),
                delivery.getReceivedAt(),
                delivery.getExpectedFileCount(),
                delivery.getActualFileCount(),
                delivery.getExpectedRecordCount(),
                delivery.getActualRecordCount(),
                delivery.getExpectedByteSize(),
                delivery.getActualByteSize(),
                delivery.isCompletenessProven(),
                delivery.getManifestReference(),
                files.stream()
                        .map(file -> new FileView(file.getSequenceNumber(), file.getFileName(),
                                file.getContentHash(), file.getHashAlgorithm(), file.getByteSize()))
                        .toList(),
                batch == null ? null : BatchView.of(batch));
    }

    /** Eén gearchiveerd bronbestand van de levering. */
    public record FileView(int sequenceNumber, String fileName, String contentHash, String hashAlgorithm,
                           long byteSize) {
    }

    /**
     * Status en uitkomst van de (laatste) screeningbatch; {@code null} in de view als er geen is.
     * <p>
     * De tellers zijn additief toegevoegd in bouwstap 2d: {@code null} betekent onbekend (de
     * screening is er niet aan toegekomen) en nooit stil {@code 0}. {@code contentMutationCount}
     * telt de aanbiedingsmutaties zonder de {@code IMPORT_MARKER}.
     * <p>
     * {@code validationResult} is additief toegevoegd in bouwstap 3a: het inhoudelijke eindoordeel
     * naast {@code status}, {@code null} zolang er niets vastgesteld is.
     * <p>
     * {@code filteredOutCount} (records buiten de importscope) en {@code errorBeforeFilterCount}
     * (records die al vóór het filter onleesbaar waren) zijn additief toegevoegd in bouwstap 3b:
     * {@code raw = filteredOut + errorBeforeFilter + rejected + valid} (R-FLT-04). Zonder
     * geconfigureerde recordfilters staan ze op 0.
     * <p>
     * {@code criticalLineCount} (verworpen regels met een fout op een kritieke kolom) is additief
     * toegevoegd in bouwstap 3h-2; {@code null} betekent onbekend.
     * <p>
     * {@code creationOutcome} (het oordeel van het creatiebeleid) en {@code creationScopeCount} (het
     * aantal actieve aanbiedingen waartegen geoordeeld is) zijn additief toegevoegd in bouwstap 3h-3;
     * {@code null} betekent "nog niet beoordeeld", nooit stil {@code AUTOMATIC} of 0.
     * <p>
     * {@code criticalIssueCount} en {@code warningCount} (ongecapte aantallen vastgestelde
     * voorvallen, uit de issuegroepen en nooit uit de bewaarde voorbeeldrijen),
     * {@code awaitingApprovalCount} (de {@code CREATE}/{@code UPDATE}-mutaties die op goedkeuring
     * wachten) en {@code creationCandidateCount} (de teller van het creatiebeleid, naast zijn noemer)
     * zijn additief toegevoegd in bouwstap 3h-5; {@code null} betekent onbekend, nooit stil 0.
     */
    public record BatchView(long batchId, String status, String validationResult, int attemptNo,
                            Long rawRecordCount, Long validRecordCount, Long rejectedRecordCount,
                            Long filteredOutCount, Long errorBeforeFilterCount,
                            Long duplicateIdentityCount, Long newCount, Long changedCount,
                            Long unchangedCount, Long contentMutationCount, Long criticalLineCount,
                            Long criticalIssueCount, Long warningCount, Long awaitingApprovalCount,
                            String creationOutcome, Long creationScopeCount,
                            Long creationCandidateCount,
                            String blockedCode,
                            String blockedReason) {

        static BatchView of(ImportBatch batch) {
            return new BatchView(batch.getId(), batch.getStatus().name(),
                    batch.getValidationResult() == null ? null : batch.getValidationResult().name(),
                    batch.getAttemptNo(),
                    batch.getRawRecordCount(), batch.getValidRecordCount(), batch.getRejectedRecordCount(),
                    batch.getFilteredOutCount(), batch.getErrorBeforeFilterCount(),
                    batch.getDuplicateIdentityCount(), batch.getNewCount(), batch.getChangedCount(),
                    batch.getUnchangedCount(), batch.getContentMutationCount(),
                    batch.getCriticalLineCount(), batch.getCriticalIssueCount(),
                    batch.getWarningCount(), batch.getAwaitingApprovalCount(),
                    batch.getCreationOutcome() == null ? null : batch.getCreationOutcome().name(),
                    batch.getCreationScopeCount(), batch.getCreationCandidateCount(),
                    batch.getBlockedCode(),
                    batch.getBlockedReason());
        }
    }
}
