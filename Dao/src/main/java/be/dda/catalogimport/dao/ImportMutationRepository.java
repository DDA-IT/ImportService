package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.ImportMutation;
import be.dda.catalogimport.domain.MutationActionType;
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
}
