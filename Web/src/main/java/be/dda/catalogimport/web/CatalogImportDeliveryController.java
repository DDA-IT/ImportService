package be.dda.catalogimport.web;

import be.dda.catalogimport.domain.DeliverySourceKind;
import be.dda.catalogimport.service.ActorIdentity;
import be.dda.catalogimport.service.ConflictException;
import be.dda.catalogimport.service.DeliveryIntakeService;
import be.dda.catalogimport.service.DeliveryIntakeService.IntakeResult;
import be.dda.catalogimport.service.DeliveryQueryService;
import be.dda.catalogimport.service.DeliveryReceptionService;
import be.dda.catalogimport.service.DeliveryReferences;
import be.dda.catalogimport.service.DeliveryView;
import be.dda.catalogimport.service.DeliveryView.BatchView;
import be.dda.catalogimport.service.LocalSourceDirectory;
import be.dda.catalogimport.service.LocalSourceDirectory.LocalSourceFile;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
 * Sinds Fase 5-AUTH (5A-5) tekent de geverifieerde gebruiker: {@code uploadedBy} is optioneel en enkel nog
 * een controle (afwijkende naam = 400 {@code ACTOR_FIELD_MISMATCH}, {@code system} = 403
 * {@code SYSTEM_ACTOR_FORBIDDEN}); bewaard wordt de token-naam en het subject.
 * <p>
 * Sinds bouwstap K-4b zit de orkestratie intake → screening in {@link DeliveryReceptionService#screenIfCreated}
 * (gedeeld met de ophaalrun); endpoints, statuscodes en antwoorden zijn ongewijzigd.
 */
@RestController
@RequestMapping("/api/catalog-import")
public class CatalogImportDeliveryController {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogImportDeliveryController.class);

    /** Hoeveel bestanden het lijst-endpoint hoogstens toont; daarboven staat {@code truncated} op {@code true}. */
    static final int LOCAL_SOURCE_LIST_CAP = 500;

    /**
     * Antwoord op een upload: de levering, haar batch en de uitkomst van de screening. Tellers zijn
     * {@code null} wanneer de screening er niet aan toegekomen is — nooit stil {@code 0}.
     * <p>
     * {@code deliveryReference} is additief toegevoegd bij de tweede ontvangstweg (beslissingslog
     * 2026-09-27): bij het inlezen uit de servermap leidt de <b>server</b> de referentie af, dus moet de
     * aanroeper ze in het antwoord kunnen zien. Beide endpoints vullen ze in, met dezelfde betekenis:
     * de referentie waaronder deze levering bekend is (zonder het interne {@code manual:}-voorvoegsel).
     */
    public record UploadResponse(long deliveryId, long batchId, String deliveryReference, String status,
                                 String blockedCode,
                                 Long rawRecordCount, Long validRecordCount, Long rejectedRecordCount,
                                 Long duplicateIdentityCount, Long newCount, Long changedCount,
                                 Long unchangedCount, Long contentMutationCount) {

        private static UploadResponse of(DeliveryView delivery, String deliveryReference) {
            BatchView batch = delivery.batch();
            return new UploadResponse(delivery.deliveryId(), batch.batchId(), deliveryReference, batch.status(),
                    batch.blockedCode(), batch.rawRecordCount(), batch.validRecordCount(),
                    batch.rejectedRecordCount(), batch.duplicateIdentityCount(), batch.newCount(),
                    batch.changedCount(), batch.unchangedCount(), batch.contentMutationCount());
        }
    }

    /**
     * Body van {@code POST /tasks/{taskId}/deliveries/local-source}. {@code fileName} is een <b>kale
     * bestandsnaam</b> uit de beheerde servermap, nooit een pad. De overige velden zijn optioneel en hebben
     * exact dezelfde betekenis als de gelijknamige parameters van de upload; een blanco
     * {@code deliveryReference} betekent "server, leid ze zelf af uit naam en inhoud" (Q1).
     */
    public record LocalSourceDeliveryRequest(String fileName, String deliveryReference, String uploadedBy,
                                             Long expectedRecordCount, Long expectedByteSize) {
    }

    /**
     * Antwoord op {@code GET /local-source/files}: de bestanden die ingelezen kunnen worden, meest recent
     * eerst. {@code truncated} is {@code true} wanneer de map meer bestanden bevat dan er getoond worden —
     * nooit stil afkappen. Er staat bewust <b>geen pad</b> in dit antwoord, enkel bestandsnamen.
     */
    public record LocalSourceListing(List<LocalSourceFile> files, boolean truncated) {
    }

    private final DeliveryIntakeService intake;
    private final DeliveryReceptionService reception;
    private final DeliveryQueryService queries;
    private final LocalSourceDirectory localSource;

    private final CurrentActor currentActor;

    public CatalogImportDeliveryController(DeliveryIntakeService intake, DeliveryReceptionService reception,
                                           DeliveryQueryService queries, LocalSourceDirectory localSource,
                                           CurrentActor currentActor) {
        this.intake = intake;
        this.reception = reception;
        this.queries = queries;
        this.localSource = localSource;
        this.currentActor = currentActor;
    }

    @RequiresPermission(Permission.MANAGE)
    @PostMapping(path = "/tasks/{taskId}/deliveries", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<UploadResponse> upload(@PathVariable("taskId") long taskId,
                                          @RequestParam("file") MultipartFile file,
                                          @RequestParam("deliveryReference") String deliveryReference,
                                          @RequestParam(value = "uploadedBy", required = false) String uploadedBy,
                                          @RequestParam(value = "expectedRecordCount", required = false)
                                          Long expectedRecordCount,
                                          @RequestParam(value = "expectedByteSize", required = false)
                                          Long expectedByteSize) {
        // Vóór de service: 400 ACTOR_FIELD_MISMATCH / 403 SYSTEM_ACTOR_FORBIDDEN gaan vóór 404/409; er is
        // dan nog niets gearchiveerd of geregistreerd.
        ActorIdentity uploader = currentActor.signer(uploadedBy, "uploadedBy");
        IntakeResult result;
        try (InputStream content = file.getInputStream()) {
            result = intake.intake(taskId, deliveryReference, uploader, expectedRecordCount, expectedByteSize,
                    file.getOriginalFilename(), content);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read uploaded file", failure);
        }
        return respond(result, deliveryReference);
    }

    /**
     * De bestanden in de beheerde servermap die ingelezen kunnen worden (tweede ontvangstweg, beslissingslog
     * 2026-09-27, D8). Vraagt bewust {@code MANAGE} en niet {@code READ}: dit onthult de inhoud van een
     * serverdirectory, en wie mag kijken moet ook mogen inlezen.
     * <p>
     * Gesorteerd op wijzigingstijd aflopend, bij gelijke tijd op naam oplopend. Hoogstens
     * {@value #LOCAL_SOURCE_LIST_CAP} bestanden, met {@code truncated = true} als er meer zijn — nooit stil
     * afkappen. Verborgen bestanden, submappen en symlinks staan er niet in: wat hier niet staat, is ook
     * niet in te lezen. Geen absoluut pad in het antwoord.
     * <p>
     * Foutcodes: 404 {@code LOCAL_SOURCE_NOT_CONFIGURED} zolang de property niet gezet is (de ontvangstweg
     * bestaat dan niet), 409 {@code LOCAL_SOURCE_DIRECTORY_UNAVAILABLE} als de map na het opstarten
     * onbereikbaar geworden is.
     */
    @RequiresPermission(Permission.MANAGE)
    @GetMapping("/local-source/files")
    LocalSourceListing localSourceFiles() {
        // Eén bestand meer opvragen dan we tonen: zo weten we of er meer zijn zonder de hele map te tellen.
        List<LocalSourceFile> found = localSource.list(LOCAL_SOURCE_LIST_CAP + 1);
        boolean truncated = found.size() > LOCAL_SOURCE_LIST_CAP;
        return new LocalSourceListing(truncated ? found.subList(0, LOCAL_SOURCE_LIST_CAP) : found, truncated);
    }

    /**
     * Leest een levering in uit de beheerde servermap: de tweede ontvangstweg naast de browser-upload
     * (beslissingslog 2026-09-27, D5/D6). Dezelfde levering, dezelfde archivering, dezelfde synchrone
     * screening en dezelfde foutcodes als de upload — alleen de herkomst van de bytes verschilt, en die
     * wordt vastgelegd als {@code sourceKind = LOCAL_DIRECTORY}.
     * <p>
     * <b>Volgorde van de controles</b> (dezelfde gedachte als bij de upload: wie niet mag tekenen, mag ook
     * geen bestandsnamen van de server aftasten):
     * <ol>
     *   <li>het recht {@code MANAGE} (interceptor), dan de handtekening — 400
     *       {@code ACTOR_FIELD_MISMATCH} / 403 {@code SYSTEM_ACTOR_FORBIDDEN} gaan vóór 404/409;</li>
     *   <li>de bestandsnaam: kaal, geen pad — anders 400 {@code LOCAL_SOURCE_FILE_NAME_INVALID};</li>
     *   <li>grootte en wijzigingstijd vastleggen, en — bij een blanco {@code deliveryReference} — het
     *       bestand hashen en dezelfde referentie afleiden als de browser (Q1, optie A);</li>
     *   <li>de stream openen en vlak vóór de intake grootte en wijzigingstijd opnieuw controleren: gewijzigd
     *       tussenin is 409 {@code LOCAL_SOURCE_FILE_CHANGED} en er wordt <b>niets</b> geregistreerd;</li>
     *   <li>de gewone intake, inclusief al haar bestaande 404/409's ({@code TASK_NOT_FOUND},
     *       {@code TASK_NOT_MANUAL}, {@code NO_ACTIVE_REVISION}, {@code TASK_RUN_IN_PROGRESS},
     *       {@code DELIVERY_REFERENCE_REUSED_WITH_DIFFERENT_CONTENT}).</li>
     * </ol>
     * 201 bij een nieuwe levering, 200 bij een idempotente retry — hetzelfde bestand een tweede keer
     * inlezen is dus veilig, en hetzelfde bestand dat eerder via de browser kwam is dezelfde levering.
     * Het bronbestand blijft ongemoeid: niets wordt verplaatst of verwijderd (D7).
     */
    @RequiresPermission(Permission.MANAGE)
    @PostMapping(path = "/tasks/{taskId}/deliveries/local-source", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<UploadResponse> readFromLocalSource(@PathVariable("taskId") long taskId,
                                                       @RequestBody LocalSourceDeliveryRequest request) {
        ActorIdentity uploader = currentActor.signer(request.uploadedBy(), "uploadedBy");
        String fileName = request.fileName();
        // Legt naam, grootte en wijzigingstijd vast; valideert ook de naam en de containment.
        LocalSourceFile before = localSource.stat(fileName);
        String reference = request.deliveryReference() == null || request.deliveryReference().isBlank()
                ? DeliveryReferences.derive(before.fileName(), localSource.sha256Hex(fileName))
                : request.deliveryReference();

        IntakeResult result;
        try (InputStream content = localSource.open(fileName)) {
            LocalSourceFile now = localSource.stat(fileName);
            if (now.byteSize() != before.byteSize() || !now.lastModifiedAt().equals(before.lastModifiedAt())) {
                // Niets geregistreerd: een levering half uit oude en half uit nieuwe bytes mag niet bestaan.
                throw new ConflictException(LocalSourceDirectory.FILE_CHANGED, "File '" + before.fileName()
                        + "' changed while it was being read; nothing was registered. Try again");
            }
            result = intake.intake(taskId, reference, uploader, request.expectedRecordCount(),
                    request.expectedByteSize(), before.fileName(), DeliverySourceKind.LOCAL_DIRECTORY, content);
        } catch (IOException failure) {
            // Enkel het sluiten/lezen van de bronstream; het pad staat in de log, niet in het antwoord.
            LOG.error("Cannot read local source file '{}'", before.fileName(), failure);
            throw new UncheckedIOException("Cannot read the file from the configured server directory", failure);
        }
        return respond(result, reference);
    }

    /**
     * Screent een nieuwe levering synchroon (via {@link DeliveryReceptionService#screenIfCreated}, zelfde
     * foutsemantiek als vóór K-4b) en bouwt het antwoord: 201 bij een nieuwe levering, 200 bij een retry.
     */
    private ResponseEntity<UploadResponse> respond(IntakeResult result, String deliveryReference) {
        IntakeResult screened = reception.screenIfCreated(result);
        DeliveryView delivery = screened.delivery();
        return ResponseEntity.status(screened.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(UploadResponse.of(delivery, deliveryReference));
    }

    /** De levering met haar bestand(en) en de status van de laatste batch. */
    @RequiresPermission(Permission.READ)
    @GetMapping("/deliveries/{deliveryId}")
    DeliveryView delivery(@PathVariable("deliveryId") long deliveryId) {
        return queries.getDelivery(deliveryId);
    }
}
