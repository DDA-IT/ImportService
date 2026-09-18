package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.DeliveryFileRepository;
import be.dda.catalogimport.dao.DeliveryRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.domain.Delivery;
import be.dda.catalogimport.domain.ImportBatch;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Leest een levering met haar bestand(en) en laatste batchstatus als {@link DeliveryView}. */
@Service
@Transactional(readOnly = true)
public class DeliveryQueryService {

    private final DeliveryRepository deliveries;
    private final DeliveryFileRepository deliveryFiles;
    private final ImportBatchRepository batches;

    public DeliveryQueryService(DeliveryRepository deliveries, DeliveryFileRepository deliveryFiles,
                                ImportBatchRepository batches) {
        this.deliveries = deliveries;
        this.deliveryFiles = deliveryFiles;
        this.batches = batches;
    }

    public DeliveryView getDelivery(long deliveryId) {
        Delivery delivery = deliveries.findById(deliveryId)
                .orElseThrow(() -> new NotFoundException("DELIVERY_NOT_FOUND",
                        "Delivery " + deliveryId + " not found"));
        return view(delivery);
    }

    private DeliveryView view(Delivery delivery) {
        List<ImportBatch> deliveryBatches = batches.findByDeliveryIdOrderByAttemptNoAsc(delivery.getId());
        ImportBatch latest = deliveryBatches.isEmpty() ? null : deliveryBatches.get(deliveryBatches.size() - 1);
        return DeliveryView.of(delivery,
                deliveryFiles.findByDeliveryIdOrderBySequenceNumberAsc(delivery.getId()), latest);
    }
}
