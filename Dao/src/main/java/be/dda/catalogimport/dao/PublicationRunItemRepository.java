package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.PublicationRunItem;
import be.dda.catalogimport.domain.PublicationRunItemStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    // --- Orchestratie (P5c) --------------------------------------------------------------------------------------

    /** De id's van de items van een run in één status, oudste eerst (een chunk: {@code Pageable.ofSize(n)}). */
    @Query("select i.id from PublicationRunItem i where i.run.id = :runId and i.status = :status order by i.id")
    List<Long> findIdsByRunIdAndStatus(@Param("runId") Long runId, @Param("status") PublicationRunItemStatus status,
                                       Pageable chunk);

    List<PublicationRunItem> findByRunIdAndStatusOrderByIdAsc(Long runId, PublicationRunItemStatus status);

    /** {@code PENDING}-items die de grens van zeker niet-toegepaste pogingen bereikten (begrenzing, P5c). */
    @Query("select i from PublicationRunItem i where i.run.id = :runId and i.status = :status "
            + "and i.notAppliedCount >= :limit order by i.id")
    List<PublicationRunItem> findByRunIdAndStatusAndNotAppliedCountAtLeast(@Param("runId") Long runId,
                                                                        @Param("status") PublicationRunItemStatus status,
                                                                        @Param("limit") int limit);

    /** Aantal items per status van een run: rijen {@code [PublicationRunItemStatus, Long]}. */
    @Query("select i.status, count(i) from PublicationRunItem i where i.run.id = :runId group by i.status")
    List<Object[]> countByStatus(@Param("runId") Long runId);

    long countByRunId(Long runId);

    // --- Lezen (P6) ----------------------------------------------------------------------------------------------

    /** Eén pagina items van een run (sortering via de {@code Pageable}). */
    Page<PublicationRunItem> findByRunId(Long runId, Pageable page);

    /** Eén pagina items van een run in één status. */
    Page<PublicationRunItem> findByRunIdAndStatus(Long runId, PublicationRunItemStatus status, Pageable page);
}
