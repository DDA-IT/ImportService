package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.ImportRowIssue;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImportRowIssueRepository extends JpaRepository<ImportRowIssue, Long> {

    /**
     * Zoekt problemen op batch-ID, gesorteerd op rijnummer (NULLs achteraan —
     * batch-niveau problemen zijn minder specifiek dan rij-specifieke problemen) en
     * daarna op ID. Gebruikt CASE WHEN voor database-draagbare NULL-afhandeling: H2
     * sorteert standaard NULLs vooraan, PostgreSQL achteraan; deze query maakt de
     * volgorde expliciet en consistent op beide.
     */
    @Query("SELECT i FROM ImportRowIssue i WHERE i.batch.id = :batchId "
            + "ORDER BY CASE WHEN i.rowNumber IS NULL THEN 1 ELSE 0 END, i.rowNumber, i.id")
    Page<ImportRowIssue> findByBatchId(@Param("batchId") Long batchId, Pageable pageable);

    /**
     * De bewaarde voorbeeldrijen van één foutgroep (bouwstap 3g), gesorteerd op
     * rijnummer (NULLs achteraan) en daarna op ID, wat zorgt voor consistent
     * sorteren op alle databases.
     */
    @Query("SELECT i FROM ImportRowIssue i WHERE i.batch.id = :batchId AND i.issueGroupId = :issueGroupId "
            + "ORDER BY CASE WHEN i.rowNumber IS NULL THEN 1 ELSE 0 END, i.rowNumber, i.id")
    Page<ImportRowIssue> findByBatchIdAndIssueGroupId(@Param("batchId") Long batchId,
                                                      @Param("issueGroupId") Long issueGroupId,
                                                      Pageable pageable);

    long countByBatchId(Long batchId);
}
