package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.ImportMutation;
import be.dda.catalogimport.domain.MutationActionType;
import be.dda.catalogimport.domain.MutationStatus;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    @Query(value = "select m from ImportMutation m where m.batch.id in :batchIds "
            + "and (:status is null or m.status = :status) "
            + "and (:actionType is null or m.actionType = :actionType) "
            + "and (cast(:statusReason as string) is null or m.statusReason = :statusReason) "
            + "order by m.id",
            countQuery = "select count(m) from ImportMutation m where m.batch.id in :batchIds "
                    + "and (:status is null or m.status = :status) "
                    + "and (:actionType is null or m.actionType = :actionType) "
                    + "and (cast(:statusReason as string) is null or m.statusReason = :statusReason)")
    Page<ImportMutation> findBundleMutations(@Param("batchIds") Collection<Long> batchIds,
                                             @Param("status") MutationStatus status,
                                             @Param("actionType") MutationActionType actionType,
                                             @Param("statusReason") String statusReason, Pageable pageable);

    /** Mutatielijst van een batch; null = geen filter, reden = exacte gelijkheid. */
    @Query(value = "select m from ImportMutation m where m.batch.id = :batchId "
            + "and (:status is null or m.status = :status) "
            + "and (:actionType is null or m.actionType = :actionType) "
            + "and (cast(:statusReason as string) is null or m.statusReason = :statusReason) "
            + "order by m.id",
            countQuery = "select count(m) from ImportMutation m where m.batch.id = :batchId "
                    + "and (:status is null or m.status = :status) "
                    + "and (:actionType is null or m.actionType = :actionType) "
                    + "and (cast(:statusReason as string) is null or m.statusReason = :statusReason)")
    Page<ImportMutation> findBatchMutations(@Param("batchId") Long batchId,
                                            @Param("status") MutationStatus status,
                                            @Param("actionType") MutationActionType actionType,
                                            @Param("statusReason") String statusReason, Pageable pageable);
}
