package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.PublicationDecision;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PublicationDecisionRepository extends JpaRepository<PublicationDecision, Long> {

    List<PublicationDecision> findByBundleIdOrderByDecidedAtAsc(Long bundleId);

    List<PublicationDecision> findByMutationIdOrderByDecidedAtAsc(Long mutationId);
}
