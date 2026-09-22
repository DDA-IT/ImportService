package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.ImportMutation;
import be.dda.catalogimport.domain.MutationActionType;
import be.dda.catalogimport.domain.MutationStatus;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportMutationRepository extends JpaRepository<ImportMutation, Long> {

    Optional<ImportMutation> findByIdempotencyKey(String idempotencyKey);

    Page<ImportMutation> findByBatchId(Long batchId, Pageable pageable);

    Page<ImportMutation> findByBatchIdAndActionType(Long batchId, MutationActionType actionType, Pageable pageable);

    long countByBatchIdAndActionType(Long batchId, MutationActionType actionType);

    /**
     * Heeft deze batch al minstens één beslissing op een mutatie (fase 4, R-BND: {@code removeBatch}
     * weigert een batch met besliste mutaties uit een bundel te verwijderen)? {@code decisionId} is
     * één van de vier samen leeg/samen gevulde decide-velden, dus deze controle volstaat.
     */
    boolean existsByBatchIdAndDecisionIdIsNotNull(Long batchId);

    // --- Mutatielijst van een Publicatiebundel (fase 4, bouwstap 4c) -------------------------------
    //
    // De bundel van een mutatie loopt via haar batch (ontwerp par. 2: lidmaatschap op BATCHNIVEAU,
    // geen publication_bundle_id op de mutatie). De aanroeper bepaalt eerst de ACTIEVE batches van de
    // bundel en geeft die verzameling hier mee. Vier expliciete afleidingen in plaats van één
    // dynamisch opgebouwde query: de filtercombinaties liggen vast en zijn zo leesbaar en typeveilig,
    // zonder SQL in de Service-laag.

    Page<ImportMutation> findByBatchIdIn(Collection<Long> batchIds, Pageable pageable);

    Page<ImportMutation> findByBatchIdInAndStatus(Collection<Long> batchIds, MutationStatus status,
                                                  Pageable pageable);

    Page<ImportMutation> findByBatchIdInAndActionType(Collection<Long> batchIds, MutationActionType actionType,
                                                      Pageable pageable);

    Page<ImportMutation> findByBatchIdInAndStatusAndActionType(Collection<Long> batchIds, MutationStatus status,
                                                               MutationActionType actionType, Pageable pageable);
}
