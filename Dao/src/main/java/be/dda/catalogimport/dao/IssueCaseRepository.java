package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.IssueCase;
import jakarta.persistence.LockModeType;
import java.util.Optional;
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
}
