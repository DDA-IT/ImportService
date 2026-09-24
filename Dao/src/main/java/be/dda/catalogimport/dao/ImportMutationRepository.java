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

    // --- Filter op de wijzigingsgroep: identity_hash (bouwstap C4) --------------------------------
    //
    // WAAROM NATIVE. identity_hash is op ImportMutation BEWUST niet gemapt (zie de klassejavadoc:
    // databasespecifiek binair type, enkel voor set-based SQL). JPQL kan een niet-gemapte kolom niet
    // noemen, dus draait deze variant als native query. De kolom komt enkel in de WHERE voor; de
    // entiteit die eruit komt is exact dezelfde als die van de JPQL-varianten hierboven ({@code m.*}
    // levert alle gemapte kolommen), zodat er geen tweede weergave van een mutatie ontstaat.
    //
    // WAAROM BYTES EN GEEN encode(...,'hex'). De vergelijking gebeurt op de binaire waarde zelf, met
    // de hexstring in Java ontleed (HexFormat). Dat houdt de query draagbaar (MutationDao: "nooit een
    // databasespecifieke hexfunctie") en blijft indexeerbaar: encode() per rij zou de indexen
    // idx_import_mutation_link_identity en idx_import_mutation_identity_status onbruikbaar maken.
    //
    // WAAROM APARTE METHODES en geen extra guard op de bestaande query's: de bestaande, werkende
    // JPQL-paden blijven daardoor letterlijk ongewijzigd voor elke aanroep zonder identityHash.
    //
    // De null-guards staan met een expliciete cast in de SQL: PostgreSQL kan het type van een
    // ongebonden null-parameter anders niet afleiden ("could not determine data type of parameter").
    // De sortering staat in de query zelf; de aanroeper geeft een ONGESORTEERDE Pageable mee.

    String IDENTITY_HASH_FILTER = " and m.identity_hash = :identityHash "
            + "and (cast(:status as varchar) is null or m.status = cast(:status as varchar)) "
            + "and (cast(:actionType as varchar) is null or m.action_type = cast(:actionType as varchar)) "
            + "and (cast(:statusReason as varchar) is null "
            + "     or m.status_reason = cast(:statusReason as varchar)) ";

    @Query(value = "select m.* from import_mutation m where m.batch_id in (:batchIds) "
            + IDENTITY_HASH_FILTER + "order by m.id",
            countQuery = "select count(*) from import_mutation m where m.batch_id in (:batchIds) "
                    + IDENTITY_HASH_FILTER,
            nativeQuery = true)
    Page<ImportMutation> findBundleMutationsByIdentityHash(@Param("batchIds") Collection<Long> batchIds,
                                                           @Param("status") String status,
                                                           @Param("actionType") String actionType,
                                                           @Param("statusReason") String statusReason,
                                                           @Param("identityHash") byte[] identityHash,
                                                           Pageable pageable);

    @Query(value = "select m.* from import_mutation m where m.batch_id = :batchId "
            + IDENTITY_HASH_FILTER + "order by m.id",
            countQuery = "select count(*) from import_mutation m where m.batch_id = :batchId "
                    + IDENTITY_HASH_FILTER,
            nativeQuery = true)
    Page<ImportMutation> findBatchMutationsByIdentityHash(@Param("batchId") Long batchId,
                                                          @Param("status") String status,
                                                          @Param("actionType") String actionType,
                                                          @Param("statusReason") String statusReason,
                                                          @Param("identityHash") byte[] identityHash,
                                                          Pageable pageable);
}
