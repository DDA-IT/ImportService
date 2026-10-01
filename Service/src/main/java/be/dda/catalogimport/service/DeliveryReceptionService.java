package be.dda.catalogimport.service;

import be.dda.catalogimport.service.DeliveryIntakeService.IntakeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * De orkestratie intake → screening (bouwstap K-4b; {@code docs/design/leveringsconfiguratie-design.md} par. 11,
 * eerste ontdekking): tot K-4b zat die in de Web-controller ({@code CatalogImportDeliveryController.respond/screen}).
 * Nu gebruiken de upload, de servermap én de ophaalrun dezelfde Service-methode, met exact hetzelfde gedrag als
 * voorheen.
 * <p>
 * <b>Businessgedrag (ongewijzigd).</b> Een <b>nieuwe</b> levering wordt synchroon gescreend; een idempotente retry
 * ({@code created = false}) screent bewust niet opnieuw en geeft de bestaande uitkomst terug. Een technisch mislukte
 * screening laat de batch op {@code FAILED} (of het hervatbare {@code MUTATING}) staan: die toestand is het antwoord,
 * niet een verloren levering - de fout wordt gelogd en niet doorgegeven. Een {@link NotFoundException} of
 * {@link ConflictException} uit de screening wijst op een inconsistentie die de aanroeper moet zien en gaat wél door.
 * <p>
 * <b>Waarom enkel de screening hier en niet ook de intake.</b> De aanroepers openen en sluiten zelf hun bronstream
 * (multipart, servermapbestand, SFTP-download) rond de intake; de screening leest daarna uit het archief. Zo blijft een
 * bronbestand niet open staan tijdens de screening - precies zoals vóór K-4b.
 */
@Service
public class DeliveryReceptionService {

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryReceptionService.class);

    private final DeliveryScreeningService screening;
    private final DeliveryQueryService queries;

    public DeliveryReceptionService(DeliveryScreeningService screening, DeliveryQueryService queries) {
        this.screening = screening;
        this.queries = queries;
    }

    /**
     * Screent een nieuw ontvangen levering synchroon en geeft de bijgewerkte toestand terug; een retry komt ongewijzigd
     * terug.
     *
     * @throws NotFoundException uit de screening (inconsistentie)
     * @throws ConflictException uit de screening (inconsistentie)
     */
    public IntakeResult screenIfCreated(IntakeResult result) {
        if (result == null || !result.created()) {
            return result;
        }
        DeliveryView delivery = result.delivery();
        screen(delivery.batch().batchId());
        // Opnieuw lezen: de screening heeft status en tellers ondertussen bijgewerkt.
        return new IntakeResult(true, queries.getDelivery(delivery.deliveryId()));
    }

    /**
     * Een technische fout laat de batch op {@code FAILED} (of, halverwege de mutatiegeneratie, op het
     * hervatbare {@code MUTATING}) achter; die toestand is het antwoord, niet een verloren upload.
     */
    private void screen(long batchId) {
        try {
            screening.screen(batchId);
        } catch (NotFoundException notFound) {
            throw notFound;
        } catch (ConflictException conflict) {
            throw conflict;
        } catch (RuntimeException technical) {
            LOG.error("Screening of batch {} failed technically", batchId, technical);
        }
    }
}
