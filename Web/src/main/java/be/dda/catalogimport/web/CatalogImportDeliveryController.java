package be.dda.catalogimport.web;

import be.dda.catalogimport.service.DeliveryIntakeService;
import be.dda.catalogimport.service.DeliveryIntakeService.IntakeResult;
import be.dda.catalogimport.service.DeliveryQueryService;
import be.dda.catalogimport.service.DeliveryView;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Manuele levering van een CSV-bestand (Fase 2, stap 2b). Dit endpoint ontvangt en registreert
 * enkel; het start nog geen screening (die volgt in stap 2c/2d), dus de batchstatus blijft
 * {@code RECEIVED}. Autorisatie volgt in Fase 5: {@code uploadedBy} is voorlopig een requestveld.
 */
@RestController
@RequestMapping("/api/catalog-import")
public class CatalogImportDeliveryController {

    /** Antwoord op een upload; {@code status} is de status van de aangemaakte (of bestaande) batch. */
    public record UploadResponse(long deliveryId, long batchId, String status) {
    }

    private final DeliveryIntakeService intake;
    private final DeliveryQueryService queries;

    public CatalogImportDeliveryController(DeliveryIntakeService intake, DeliveryQueryService queries) {
        this.intake = intake;
        this.queries = queries;
    }

    /** 201 bij een nieuwe levering; 200 bij een idempotente retry (zelfde referentie, zelfde inhoud). */
    @PostMapping(path = "/tasks/{taskId}/deliveries", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<UploadResponse> upload(@PathVariable("taskId") long taskId,
                                          @RequestParam("file") MultipartFile file,
                                          @RequestParam("deliveryReference") String deliveryReference,
                                          @RequestParam("uploadedBy") String uploadedBy,
                                          @RequestParam(value = "expectedRecordCount", required = false)
                                          Long expectedRecordCount,
                                          @RequestParam(value = "expectedByteSize", required = false)
                                          Long expectedByteSize) {
        IntakeResult result;
        try (InputStream content = file.getInputStream()) {
            result = intake.intake(taskId, deliveryReference, uploadedBy, expectedRecordCount, expectedByteSize,
                    file.getOriginalFilename(), content);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read uploaded file", failure);
        }
        DeliveryView delivery = result.delivery();
        UploadResponse body = new UploadResponse(delivery.deliveryId(), delivery.batch().batchId(),
                delivery.batch().status());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(body);
    }

    /** De levering met haar bestand(en) en de status van de laatste batch. */
    @GetMapping("/deliveries/{deliveryId}")
    DeliveryView delivery(@PathVariable("deliveryId") long deliveryId) {
        return queries.getDelivery(deliveryId);
    }
}
