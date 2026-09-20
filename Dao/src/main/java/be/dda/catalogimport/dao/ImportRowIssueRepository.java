package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.ImportRowIssue;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportRowIssueRepository extends JpaRepository<ImportRowIssue, Long> {

    Page<ImportRowIssue> findByBatchId(Long batchId, Pageable pageable);

    /** De bewaarde voorbeeldrijen van één foutgroep (bouwstap 3g). */
    Page<ImportRowIssue> findByBatchIdAndIssueGroupId(Long batchId, Long issueGroupId, Pageable pageable);

    long countByBatchId(Long batchId);
}
