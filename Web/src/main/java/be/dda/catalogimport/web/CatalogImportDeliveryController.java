package be.dda.catalogimport.web;

import be.dda.catalogimport.service.ConflictException;
import be.dda.catalogimport.service.DeliveryIntakeService;
import be.dda.catalogimport.service.DeliveryIntakeService.IntakeResult;
import be.dda.catalogimport.service.DeliveryQueryService;
import be.dda.catalogimport.service.DeliveryScreeningService;
import be.dda.catalogimport.service.DeliveryView;
import be.dda.catalogimport.service.DeliveryView.BatchView;
import be.dda.catalogimport.service.NotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Manuele levering van een CSV-bestand (Fase 2). De upload archiveert, registreert én screent
 * synchroon (design par. 10; asynchroon volgt in fase 5), zodat de aanroeper in hetzelfde antwoord
 * de eindstatus van de screening en haar tellers ziet.
 * <p>
 * <b>Statuscodes.</b> 201 bij een nieuwe levering, 200 bij een idempotente retry (zelfde referentie,
 * zelfde inhoud) — die retry screent bewust <b>niet</b> opnieuw en geeft de bestaande uitkomst terug.
 * De uitkomst van de screening zelf staat in {@code status}: {@code SCREENED} (eventueel met
 * verworpen regels), {@code BLOCKED} met een {@code blockedCode}, of {@code FAILED} bij een
 * technische fout. Een technisch mislukte screening blijft een 201: de levering ís aangemaakt en
 * gearchiveerd, en een 500 zou de aanroeper uitnodigen opnieuw te uploaden terwijl zijn referentie
 * al bezet is. De fout wordt hier gelogd en staat als {@code SCREENING_FAILED} op de batch.
 * <p>
 * Autorisatie volgt in Fase 5: {@code uploadedBy} is voorlopig een requestveld.
 */
@RestController
@RequestMapping("/api/catalog-import")
public class CatalogImportDeliveryController {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogImportDeliveryController.class);

    /**
     * Antwoord op een upload: de levering, haar batch en de uitkomst van de screening. Tellers zijn
     * {@code null} wanneer de screening er niet aan toegekomen is — nooit stil {@code 0}.
     */
    public record UploadResponse(long deliveryId, long batchId, String status, String blockedCode,
                                 Long rawRecordCount, Long validRecordCount, Long rejectedRecordCount,
                                 Long duplicateIdentityCount, Long newCount, Long changedCount,
                                 Long unchangedCount, Long contentMutationCount) {

        private static UploadResponse of(DeliveryView delivery) {
            BatchView batch = delivery.batch();
            return new UploadResponse(delivery.deliveryId(), batch.batchId(), batch.status(),
                    batch.blockedCode(), batch.rawRecordCount(), batch.validRecordCount(),
                    batch.rejectedRecordCount(), batch.duplicateIdentityCount(), batch.newCount(),
                    batch.changedCount(), batch.unchangedCount(), batch.contentMutationCount());
        }
    }

    private final DeliveryIntakeService intake;
    private final DeliveryScreeningService screening;
    private final DeliveryQueryService queries;

    public CatalogImportDeliveryController(DeliveryIntakeService intake, DeliveryScreeningService screening,
                                           DeliveryQueryService queries) {
        this.intake = intake;
        this.screening = screening;
        this.queries = queries;
    }

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
        if (result.created()) {
            screen(delivery.batch().batchId());
            // Opnieuw lezen: de screening heeft status en tellers ondertussen bijgewerkt.
            delivery = queries.getDelivery(delivery.deliveryId());
        }
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(UploadResponse.of(delivery));
    }

    /**
     * Een technische fout laat de batch op {@code FAILED} (of, halverwege de mutatiegeneratie, op het
     * hervatbare {@code MUTATING}) achter; die toestand is het antwoord, niet een verloren upload.
     * Een {@code NotFound}/{@code Conflict} uit de screening blijft wél doorgaan naar de
     * foutafhandeling: dat wijst op een inconsistentie die de aanroeper moet zien.
     */
    private void screen(long batchId) {
        try {
            screening.screen(batchId);
        } catch (NotFoundException | ConflictException expected) {
            throw expected;
        } catch (RuntimeException technical) {
            LOG.error("Screening of batch {} failed technically", batchId, technical);
        }
    }

    /** De levering met haar bestand(en) en de status van de laatste batch. */
    @GetMapping("/deliveries/{deliveryId}")
    DeliveryView delivery(@PathVariable("deliveryId") long deliveryId) {
        return queries.getDelivery(deliveryId);
    }
}
