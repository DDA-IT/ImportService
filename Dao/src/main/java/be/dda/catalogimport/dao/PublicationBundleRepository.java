package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.PublicationBundle;
import be.dda.catalogimport.domain.PublicationBundleStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PublicationBundleRepository extends JpaRepository<PublicationBundle, Long> {

    Optional<PublicationBundle> findByBundleReference(String bundleReference);

    Optional<PublicationBundle> findByIdempotencyKey(String idempotencyKey);

    Page<PublicationBundle> findByStatus(PublicationBundleStatus status, Pageable pageable);

    /**
     * De bundel met een schrijfslot tot het einde van de transactie. Serialisatiepunt voor
     * bundelacties (batches toevoegen, beslissen, bevriezen, annuleren) — zelfde patroon als
     * {@link ImportBatchRepository#findByIdForUpdate}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from PublicationBundle b where b.id = :id")
    Optional<PublicationBundle> findByIdForUpdate(@Param("id") Long id);
}
