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
}
