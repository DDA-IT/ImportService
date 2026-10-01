package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.PublicationRun;
import be.dda.catalogimport.domain.PublicationTargetMode;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PublicationRunRepository extends JpaRepository<PublicationRun, Long> {

    List<PublicationRun> findByBundleIdOrderByIdAsc(Long bundleId);

    Optional<PublicationRun> findByIdempotencyKey(String idempotencyKey);

    /**
     * Het aantal niet-terminale runs van deze bundel ({@code active_marker is not null}, bouwstap 5P-7).
     * Bedoeld voor de <b>nette</b> foutmelding {@code PUBLICATION_RUN_IN_PROGRESS}; de harde zekering
     * blijft {@code uk_publication_run_active} in de database, want alleen die houdt ook stand bij twee
     * gelijktijdige aanvragen.
     */
    long countByBundleIdAndActiveMarkerIsNotNull(Long bundleId);

    /**
     * De niet-terminale run van deze bundel, indien aanwezig ({@code active_marker is not null}). Hoogstens
     * één rij door {@code uk_publication_run_active}; gebruikt om te bepalen of die run vastgelopen is
     * (bouwstap "herstel van een vastgelopen PREPARING-publicatierun", {@code docs/decisions.md} 2026-09-27).
     */
    Optional<PublicationRun> findByBundleIdAndActiveMarkerIsNotNull(Long bundleId);

    /**
     * Enkel de bundel-id van een run, zonder de run zelf te laden. Wie een bestaande run wil afronden of afbreken,
     * vergrendelt daarna de bundel ({@link PublicationBundleRepository#findByIdForUpdate}) en leest de run vers
     * <b>na</b> het slot (analyse-opvolging stap 3b; zelfde patroon als {@code TaskRunRepository.findTaskIdByRunId}).
     */
    @Query("select r.bundle.id from PublicationRun r where r.id = :runId")
    Optional<Long> findBundleIdByRunId(@Param("runId") Long runId);

    /**
     * Het hoogste {@code attempt} van deze bundel in deze modus, of 0 wanneer er nog geen run bestaat.
     * Per modus en niet over de bundel heen: {@code attempt} telt de pogingen naar één doel, en de
     * idempotentiesleutel {@code run:<bundleId>:<mode>:<attempt>} draagt de modus al.
     */
    @Query("select coalesce(max(r.attempt), 0) from PublicationRun r "
            + "where r.bundle.id = :bundleId and r.targetMode = :targetMode")
    Integer findMaxAttempt(@Param("bundleId") Long bundleId,
                           @Param("targetMode") PublicationTargetMode targetMode);
}
