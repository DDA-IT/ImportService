package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.PublicationBundleBatch;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PublicationBundleBatchRepository extends JpaRepository<PublicationBundleBatch, Long> {

    /**
     * Het actieve lidmaatschap van een batch, indien aanwezig. De databaseconstraint
     * {@code uk_publication_bundle_batch_active} garandeert dat dit er hoogstens één is, over alle
     * bundels heen.
     */
    Optional<PublicationBundleBatch> findByBatchIdAndActiveMarkerIsNotNull(Long batchId);

    List<PublicationBundleBatch> findByBundleId(Long bundleId);

    Page<PublicationBundleBatch> findByBundleId(Long bundleId, Pageable pageable);

    long countByBundleIdAndActiveMarkerIsNotNull(Long bundleId);

    Optional<PublicationBundleBatch> findByBundleIdAndBatchId(Long bundleId, Long batchId);
}
