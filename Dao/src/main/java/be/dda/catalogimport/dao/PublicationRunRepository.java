package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.PublicationRun;
import be.dda.catalogimport.domain.PublicationRunStatus;
import be.dda.catalogimport.domain.PublicationTargetMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    // --- PRODUCTION-run (P5c) ------------------------------------------------------------------------------------

    /**
     * De actieve run van dit doel over alle bundels heen ({@code active_marker is not null}), voor de nette foutmelding bij
     * een tweede PRODUCTION-run; de harde zekering is de partiële index {@code uk_publication_run_active_production}.
     */
    Optional<PublicationRun> findFirstByTargetModeAndActiveMarkerIsNotNull(PublicationTargetMode targetMode);

    /**
     * Neemt de chunk-lease (design par. 2.4 "Gelijktijdigheid") als de run {@code status} heeft en er geen geldige lease is
     * (geen houder, of {@code lease_until <= now}). Eén voorwaardelijke update: twee gelijktijdige aanroepen kunnen nooit
     * allebei 1 krijgen (de tweede wacht op het rijslot en herevalueert de voorwaarde).
     *
     * @return 1 = lease genomen, 0 = bezet (of de run is niet in {@code status})
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PublicationRun r set r.leaseOwner = :owner, r.leaseUntil = :until where r.id = :id "
            + "and r.status = :status and (r.leaseOwner is null or r.leaseUntil <= :now)")
    int acquireLease(@Param("id") Long id, @Param("owner") String owner, @Param("until") Instant until,
                     @Param("now") Instant now, @Param("status") PublicationRunStatus status);

    /**
     * Verlengt de lease, enkel voor de houder en zolang de run {@code status} heeft.
     *
     * @return 1 = verlengd, 0 = de lease is overgenomen of de run is niet meer in {@code status}
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PublicationRun r set r.leaseUntil = :until where r.id = :id and r.leaseOwner = :owner "
            + "and r.status = :status")
    int renewLease(@Param("id") Long id, @Param("owner") String owner, @Param("until") Instant until,
                   @Param("status") PublicationRunStatus status);

    /** Geeft de lease vrij, enkel voor de houder. @return 1 = vrijgegeven, 0 = niet (meer) de houder */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PublicationRun r set r.leaseOwner = null, r.leaseUntil = null where r.id = :id "
            + "and r.leaseOwner = :owner")
    int releaseLease(@Param("id") Long id, @Param("owner") String owner);
}
