package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.PublicationBundleSnapshotPrice;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PublicationBundleSnapshotPriceRepository
        extends JpaRepository<PublicationBundleSnapshotPrice, PublicationBundleSnapshotPrice.Key> {

    List<PublicationBundleSnapshotPrice> findBySnapshotId(Long snapshotId);
}
