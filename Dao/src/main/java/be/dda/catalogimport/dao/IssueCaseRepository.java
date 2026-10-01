package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.IssueCase;
import be.dda.catalogimport.domain.IssueCaseStatus;
import be.dda.catalogimport.domain.RowIssueSeverity;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IssueCaseRepository extends JpaRepository<IssueCase, Long> {

    /** De identiteit van het behandelgeval (D1, {@code uk_issue_case_identity}). */
    Optional<IssueCase> findByImportLinkIdAndIssueCodeAndSignature(Long importLinkId, String issueCode,
                                                                    String signature);

    /**
     * Het geval met een schrijfslot tot het einde van de transactie (S2-B2): serialisatiepunt voor een
     * menselijke statuswijziging, zelfde patroon als {@code PublicationBundleRepository#findByIdForUpdate}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from IssueCase c where c.id = :id")
    Optional<IssueCase> findByIdForUpdate(@Param("id") Long id);

    /**
     * De behandelgevallenlijst (S2-B3, docs/design/issue-case-design.md §6): optioneel gefilterd op
     * {@code importLinkId}, {@code status}, {@code severity}, {@code issueCode} en het halfopen
     * {@code [lastSeenFrom, lastSeenTo)}-interval op {@code lastSeenAt}. Vaste sortering
     * {@code last_seen_at desc, id desc} (design §2: "de lijst moet dat als kenmerk tonen/filteren" —
     * recentste onderdrukking/heropening eerst).
     */
    @Query(value = "select c from IssueCase c "
            + "where (:importLinkId is null or c.importLink.id = :importLinkId) "
            + "and (:status is null or c.status = :status) "
            + "and (:severity is null or c.severity = :severity) "
            + "and (:issueCode is null or c.issueCode = :issueCode) "
            + "and (cast(:lastSeenFrom as timestamp) is null or c.lastSeenAt >= :lastSeenFrom) "
            + "and (cast(:lastSeenTo as timestamp) is null or c.lastSeenAt < :lastSeenTo) "
            + "order by c.lastSeenAt desc, c.id desc",
            countQuery = "select count(c) from IssueCase c "
                    + "where (:importLinkId is null or c.importLink.id = :importLinkId) "
                    + "and (:status is null or c.status = :status) "
                    + "and (:severity is null or c.severity = :severity) "
                    + "and (:issueCode is null or c.issueCode = :issueCode) "
                    + "and (cast(:lastSeenFrom as timestamp) is null or c.lastSeenAt >= :lastSeenFrom) "
                    + "and (cast(:lastSeenTo as timestamp) is null or c.lastSeenAt < :lastSeenTo)")
    Page<IssueCase> findCaseRows(@Param("importLinkId") Long importLinkId,
                                 @Param("status") IssueCaseStatus status,
                                 @Param("severity") RowIssueSeverity severity,
                                 @Param("issueCode") String issueCode,
                                 @Param("lastSeenFrom") Instant lastSeenFrom,
                                 @Param("lastSeenTo") Instant lastSeenTo, Pageable pageable);

    /**
     * Aantal gevallen per {@link IssueCaseStatus}, optioneel beperkt tot één koppeling (patroon
     * {@code ImportBatchRepository#countByStatusGrouped}). Alle vier statuswaarden zijn hier altijd
     * berekenbaar (geen "niet vastgesteld"-groep zoals bij {@code validationResult}).
     */
    @Query("select c.status, count(c) from IssueCase c "
            + "where (:importLinkId is null or c.importLink.id = :importLinkId) group by c.status")
    List<Object[]> countByStatusGrouped(@Param("importLinkId") Long importLinkId);
}
