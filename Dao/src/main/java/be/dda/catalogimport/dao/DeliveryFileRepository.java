package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.DeliveryFile;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryFileRepository extends JpaRepository<DeliveryFile, Long> {

    List<DeliveryFile> findByDeliveryIdOrderBySequenceNumberAsc(Long deliveryId);

    List<DeliveryFile> findByContentHash(String contentHash);
}
