package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.ImportRowIssue;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportRowIssueRepository extends JpaRepository<ImportRowIssue, Long> {

    Page<ImportRowIssue> findByBatchId(Long batchId, Pageable pageable);

    long countByBatchId(Long batchId);
}
