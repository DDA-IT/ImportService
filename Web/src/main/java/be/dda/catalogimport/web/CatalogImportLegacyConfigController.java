package be.dda.catalogimport.web;

import be.dda.catalogimport.domain.LegacyConfigReceiptStatus;
import be.dda.catalogimport.service.ActorIdentity;
import be.dda.catalogimport.service.BadRequestException;
import be.dda.catalogimport.service.LegacyConfigApplyService;
import be.dda.catalogimport.service.LegacyConfigApplyService.ApplyCommand;
import be.dda.catalogimport.service.LegacyConfigApplyService.ApplyOptions;
import be.dda.catalogimport.service.LegacyConfigApplyService.ApplyResult;
import be.dda.catalogimport.service.LegacyConfigApplyService.PreviewResult;
import be.dda.catalogimport.service.LegacyBundleParser;
import be.dda.catalogimport.service.LegacyConfigReceiptService;
import be.dda.catalogimport.service.LegacyConfigReceiptService.PushResult;
import be.dda.catalogimport.service.LegacyConfigReceiptService.ReceiptDetail;
import be.dda.catalogimport.service.LegacyConfigReceiptService.ReceiptRow;
import be.dda.catalogimport.service.PageResult;
import be.dda.catalogimport.service.PayloadTooLargeException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ontvangst en inzage van legacy-config-bundels, slice S1 van {@code docs/design/legacy-config-bundle-design.md}
 * (par. 2 en 3); contract {@code docs/design/legacy-config-bundle-v1.md}; beslissingslog 2026-10-20 (M2).
 *
 * <h2>Wie</h2>
 * <ul>
 *   <li>{@code POST /receipts}: <b>enkel</b> de technische client van de datasync ({@link TechnicalClientOnly}; de
 *       JWT-keten {@code LegacyConfigPushSecurityConfiguration} schermt het pad af). Een BFF-sessie: 401.</li>
 *   <li>{@code GET /receipts} en {@code GET /receipts/{id}}: {@code MANAGE} via de BFF-sessie — de bundel toont host
 *       en login (L7b). Een bearer-token werkt op deze paden niet.</li>
 *   <li>{@code GET /receipts/{id}/preview} en {@code POST /receipts/{id}/apply} (slice S2, {@link LegacyConfigApplyService}):
 *       {@code MANAGE}; 403 vóór 400/404/409.</li>
 * </ul>
 * Niet achter {@code catalogimport.setup-api.enabled} (precedent L7a). 401/403 komen uit de filterketen of de
 * interceptor, vóór het lezen van de body en dus vóór elke 400/404/409/413.
 *
 * <h2>Statuscodes van de push</h2>
 * 201 {@code RECEIVED} of 200 {@code UNCHANGED} (dezelfde inhoud als de laatste ontvangst van de herkomst: id en status
 * van die rij; contract par. 8); 400 {@code LEGACY_BUNDLE_INVALID},
 * {@code LEGACY_BUNDLE_VERSION_UNSUPPORTED}, {@code LEGACY_BUNDLE_HASH_MISMATCH}; 409 {@code SECRETS_NOT_CONFIGURED}
 * (de bundel draagt een secret en er is geen sleutelring: niets bewaard, S4); 413 {@value #CODE_TOO_LARGE}.
 *
 * <h2>Lekpreventie (design par. 2, "Secret door de request")</h2>
 * De body wordt hier zelf gelezen als {@code byte[]} met een grens ({@code catalogimport.legacy-config.max-bundle-bytes},
 * default 2 MB), nooit via {@code @RequestBody} (Spring logt een Jackson-fout met een stuk invoer), en na verwerking
 * overschreven, ook bij een fout. Het secret gaat als {@code LegacySecret} enkel naar de credentialopslag (S4); geen
 * enkel antwoord, geen logregel en geen {@code bundle_json} bevat het.
 */
@RestController
@RequestMapping("/api/catalog-import/legacy-config")
public class CatalogImportLegacyConfigController {

    /** 413: de body is groter dan {@code catalogimport.legacy-config.max-bundle-bytes}. */
    public static final String CODE_TOO_LARGE = "LEGACY_BUNDLE_TOO_LARGE";

    /** Default van {@code catalogimport.legacy-config.max-bundle-bytes}: 2 MB. */
    static final long DEFAULT_MAX_BUNDLE_BYTES = 2L * 1024 * 1024;

    private static final int INITIAL_BUFFER = 8 * 1024;

    private final LegacyConfigReceiptService receipts;
    private final LegacyConfigApplyService applies;
    private final CurrentActor currentActor;
    private final int maxBundleBytes;

    public CatalogImportLegacyConfigController(LegacyConfigReceiptService receipts, LegacyConfigApplyService applies,
                                               CurrentActor currentActor,
                                               @Value("${catalogimport.legacy-config.max-bundle-bytes:"
                                                       + DEFAULT_MAX_BUNDLE_BYTES + "}") long maxBundleBytes) {
        if (maxBundleBytes < 1 || maxBundleBytes > Integer.MAX_VALUE - 1) {
            throw new IllegalStateException("catalogimport.legacy-config.max-bundle-bytes must be between 1 and "
                    + (Integer.MAX_VALUE - 1));
        }
        this.receipts = receipts;
        this.applies = applies;
        this.currentActor = currentActor;
        this.maxBundleBytes = (int) maxBundleBytes;
    }

    @TechnicalClientOnly(TechnicalClientOnly.LEGACY_CONFIG_PUSH)
    @PostMapping("/receipts")
    ResponseEntity<PushResult> push(HttpServletRequest request) {
        ActorIdentity client = currentActor.technicalClient(TechnicalClientOnly.LEGACY_CONFIG_PUSH);
        byte[] body = readLimited(request);
        try {
            PushResult result = receipts.receive(body, client);
            HttpStatus status = LegacyConfigReceiptService.OUTCOME_RECEIVED.equals(result.outcome())
                    ? HttpStatus.CREATED : HttpStatus.OK;
            return ResponseEntity.status(status).body(result);
        } finally {
            Arrays.fill(body, (byte) 0);
        }
    }

    /** De ontvangsten, zonder bundel; sortering {@code received_at desc, id desc}. */
    @RequiresPermission(Permission.MANAGE)
    @GetMapping("/receipts")
    PageResult<ReceiptRow> list(@RequestParam(value = "status", required = false) LegacyConfigReceiptStatus status,
                                @RequestParam(value = "interface", required = false) String interfaceCode,
                                @RequestParam(value = "page", required = false) Integer page,
                                @RequestParam(value = "size", required = false) Integer size) {
        return receipts.list(status, interfaceCode, page, size);
    }

    /** Detail met de bundel zonder secret, {@code secretOutcome} en {@code credentialRef}. */
    @RequiresPermission(Permission.MANAGE)
    @GetMapping("/receipts/{receiptId}")
    ReceiptDetail get(@PathVariable("receiptId") long receiptId) {
        return receipts.get(receiptId);
    }

    /**
     * PREVIEW (S2): het rapport van wat APPLY nu zou doen, met dezelfde keuzes als de APPLY-body (als
     * queryparameters), plus {@code previewFingerprint}. Schrijft niets.
     */
    @RequiresPermission(Permission.MANAGE)
    @GetMapping("/receipts/{receiptId}/preview")
    PreviewResult preview(@PathVariable("receiptId") long receiptId,
                          @RequestParam(value = "definitionCodeOverride", required = false) String definitionCodeOverride,
                          @RequestParam(value = "charsetWhenUndeclared", required = false) String charsetWhenUndeclared,
                          @RequestParam(value = "libraryCode", required = false) String libraryCode) {
        return applies.preview(receiptId, new ApplyOptions(definitionCodeOverride, charsetWhenUndeclared, libraryCode));
    }

    /**
     * APPLY (S2): {@code {previewFingerprint, reason, definitionCodeOverride?, charsetWhenUndeclared?, libraryCode?}}
     * → 200 + rapport. 400 {@code LEGACY_APPLY_REASON_REQUIRED}; 404 {@code LEGACY_RECEIPT_NOT_FOUND}; 409
     * {@code LEGACY_RECEIPT_NOT_APPLICABLE}, {@code LEGACY_PREVIEW_STALE}, {@code LEGACY_APPLY_BLOCKED}. Activeert
     * nooit.
     */
    @RequiresPermission(Permission.MANAGE)
    @PostMapping("/receipts/{receiptId}/apply")
    ApplyResult apply(@PathVariable("receiptId") long receiptId,
                      @RequestBody(required = false) ApplyCommand request) {
        return applies.apply(receiptId, request, currentActor.signer(null, "appliedBy"));
    }

    /**
     * Leest de body tot hoogstens {@link #maxBundleBytes}; meer = 413, zonder de rest te lezen. Een gedeclareerde
     * {@code Content-Length} boven de grens wordt meteen geweigerd. Elke tussenbuffer wordt overschreven.
     */
    private byte[] readLimited(HttpServletRequest request) {
        long declared = request.getContentLengthLong();
        if (declared > maxBundleBytes) {
            throw tooLarge();
        }
        int capacity = (int) Math.min(declared > 0 ? declared : INITIAL_BUFFER, (long) maxBundleBytes + 1);
        byte[] buffer = new byte[Math.max(capacity, 1)];
        int length = 0;
        try (InputStream in = request.getInputStream()) {
            while (true) {
                if (length == buffer.length) {
                    if (buffer.length > maxBundleBytes) {
                        Arrays.fill(buffer, (byte) 0);
                        throw tooLarge();
                    }
                    byte[] grown = Arrays.copyOf(buffer, (int) Math.min((long) buffer.length * 2,
                            (long) maxBundleBytes + 1));
                    Arrays.fill(buffer, (byte) 0);
                    buffer = grown;
                }
                int read = in.read(buffer, length, buffer.length - length);
                if (read < 0) {
                    break;
                }
                length += read;
            }
        } catch (IOException unreadable) {
            Arrays.fill(buffer, (byte) 0);
            throw new BadRequestException(LegacyBundleParser.CODE_INVALID, "The request body could not be read");
        }
        if (length > maxBundleBytes) {
            Arrays.fill(buffer, (byte) 0);
            throw tooLarge();
        }
        byte[] body = Arrays.copyOf(buffer, length);
        Arrays.fill(buffer, (byte) 0);
        return body;
    }

    private PayloadTooLargeException tooLarge() {
        return new PayloadTooLargeException(CODE_TOO_LARGE, "The legacy-config bundle exceeds the maximum size of "
                + maxBundleBytes + " bytes");
    }
}
