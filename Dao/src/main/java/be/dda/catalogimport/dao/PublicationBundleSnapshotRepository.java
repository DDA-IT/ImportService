package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.PublicationBundleSnapshot;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PublicationBundleSnapshotRepository extends JpaRepository<PublicationBundleSnapshot, Long> {

    List<PublicationBundleSnapshot> findByBundleId(Long bundleId);

    Optional<PublicationBundleSnapshot> findByMutationId(Long mutationId);

    long countByBundleId(Long bundleId);
}
