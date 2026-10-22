package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.PublicationRunItem;
import be.dda.catalogimport.domain.PublicationRunItemStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository voor het itemregister van een PRODUCTION-run (changeset 027-1; overgangen in P5b). */
public interface PublicationRunItemRepository extends JpaRepository<PublicationRunItem, Long> {

    List<PublicationRunItem> findByRunIdOrderByIdAsc(Long runId);

    Optional<PublicationRunItem> findByIdempotencyKey(String idempotencyKey);

    long countByRunIdAndStatus(Long runId, PublicationRunItemStatus status);

    /**
     * Het item met een schrijfslot tot het einde van de transactie: serialisatiepunt voor de itemlevenscyclus (P5b), zelfde
     * patroon als {@link PublicationBundleRepository#findByIdForUpdate}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from PublicationRunItem i where i.id = :id")
    Optional<PublicationRunItem> findByIdForUpdate(@Param("id") Long id);
}
