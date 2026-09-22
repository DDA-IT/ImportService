package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.PublicationDecision;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PublicationDecisionRepository extends JpaRepository<PublicationDecision, Long> {

    List<PublicationDecision> findByBundleIdOrderByDecidedAtAsc(Long bundleId);

    /**
     * Het beslissingsregister van één bundel, gepagineerd. De chronologische volgorde
     * ({@code decided_at, id}) komt uit de {@link Pageable}: twee beslissingen binnen dezelfde
     * tijdsresolutie blijven zo toch in de volgorde waarin ze geschreven zijn.
     */
    Page<PublicationDecision> findByBundleId(Long bundleId, Pageable pageable);

    List<PublicationDecision> findByMutationIdOrderByDecidedAtAsc(Long mutationId);
}
