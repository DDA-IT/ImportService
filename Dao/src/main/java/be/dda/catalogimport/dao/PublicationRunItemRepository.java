package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.PublicationRunItem;
import be.dda.catalogimport.domain.PublicationRunItemStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Basisrepository voor het itemregister van een PRODUCTION-run (changeset 027-1). Queries voor P5 volgen daar. */
public interface PublicationRunItemRepository extends JpaRepository<PublicationRunItem, Long> {

    List<PublicationRunItem> findByRunIdOrderByIdAsc(Long runId);

    Optional<PublicationRunItem> findByIdempotencyKey(String idempotencyKey);

    long countByRunIdAndStatus(Long runId, PublicationRunItemStatus status);
}
