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
                batch == null ? null
                        : new BatchView(batch.getId(), batch.getStatus().name(), batch.getAttemptNo()));
    }

    /** Eén gearchiveerd bronbestand van de levering. */
    public record FileView(int sequenceNumber, String fileName, String contentHash, String hashAlgorithm,
                           long byteSize) {
    }

    /** Status van de (laatste) screeningbatch van de levering; {@code null} in de view als er geen is. */
    public record BatchView(long batchId, String status, int attemptNo) {
    }
}
