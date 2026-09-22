package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /**
     * Batches die in aanmerking komen om aan een Publicatiebundel toegevoegd te worden (ontwerp fase 4
     * par. 3, R-BND): {@code SCREENED}, een vastgesteld {@code validationResult} dat geen
     * {@code BLOCKING} is, optioneel beperkt tot één koppeling, en zonder actief lidmaatschap in welke
     * bundel dan ook. Dezelfde voorwaarde als {@code PublicationBundleService.addOneBatch}, hier als
     * lijst in plaats van als weigering per batch.
     */
    @Query("select b from ImportBatch b where b.status = be.dda.catalogimport.domain.ImportBatchStatus.SCREENED "
            + "and b.validationResult is not null "
            + "and b.validationResult <> be.dda.catalogimport.domain.ValidationResult.BLOCKING "
            + "and (:importLinkId is null or b.importLink.id = :importLinkId) "
            + "and not exists (select 1 from PublicationBundleBatch pbb "
            + "  where pbb.batch = b and pbb.activeMarker is not null)")
    Page<ImportBatch> findBundleCandidates(@Param("importLinkId") Long importLinkId, Pageable pageable);
}
