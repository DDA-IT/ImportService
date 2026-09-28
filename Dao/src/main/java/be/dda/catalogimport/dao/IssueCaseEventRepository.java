package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.IssueCaseEvent;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IssueCaseEventRepository extends JpaRepository<IssueCaseEvent, Long> {

    /** Het chronologisch verloop van één geval, oplopend op {@code id} (patroon publication_decision). */
    List<IssueCaseEvent> findByIssueCaseIdOrderByIdAsc(Long issueCaseId);

    Page<IssueCaseEvent> findByIssueCaseId(Long issueCaseId, Pageable pageable);
}
