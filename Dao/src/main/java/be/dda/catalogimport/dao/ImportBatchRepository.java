package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, Long> {

    /**
     * De open batch van een levering, indien die {@code open_marker} bezet. De databaseconstraint
     * {@code uk_import_batch_open} garandeert dat dit er hoogstens één is.
     */
    Optional<ImportBatch> findByDeliveryIdAndOpenMarkerIsNotNull(Long deliveryId);

    List<ImportBatch> findByDeliveryIdOrderByAttemptNoAsc(Long deliveryId);

    /** Alle batches van een levering onder één revisie (bepaalt het volgende {@code attemptNo}). */
    List<ImportBatch> findByDeliveryIdAndDefinitionRevisionId(Long deliveryId, Long definitionRevisionId);

    /** Recovery bij opstart: batches die nog in een bepaalde (open) status staan. */
    List<ImportBatch> findByStatus(ImportBatchStatus status);

    /**
     * De batch met een schrijfslot tot het einde van de transactie. Gebruikt door de afsluitende
     * transactie van accept-baseline, zodat twee gelijktijdige acceptaties van dezelfde batch de
     * statuscontrole en de overgang naar {@code BASELINE_ACCEPTED} niet allebei kunnen winnen.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from ImportBatch b where b.id = :id")
    Optional<ImportBatch> findByIdForUpdate(@Param("id") Long id);
}
