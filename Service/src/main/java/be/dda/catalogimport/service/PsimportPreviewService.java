package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.PsimportPreviewDao;
import be.dda.catalogimport.dao.PublicationBundleRepository;
import be.dda.catalogimport.domain.PublicationBundle;
import be.dda.catalogimport.domain.PublicationBundleStatus;
import be.dda.catalogimport.service.PsimportPreviewMapper.Row;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only PSIMPORT-preview van een bevroren bundel (beslissing 2026-09-25, slice 1). Schrijft niets,
 * persisteert geen hash en raakt 5-PUB niet.
 * <p>
 * {@code generatedAt} is het tijdstip van opvragen en verschilt dus per aanroep; twee antwoorden voor
 * dezelfde bevroren bundel zijn identiek <b>behalve</b> {@code generatedAt}, dat bij een vergelijking
 * uitgesloten moet worden.
 */
@Service
@Transactional(readOnly = true)
public class PsimportPreviewService {

    public static final String CODE_BUNDLE_NOT_FROZEN = "BUNDLE_NOT_FROZEN";
    public static final String CONTRACT_STATUS = "UNVERIFIED_FIELD_INVENTORY";
    public static final String PREVIEW_SPEC_VERSION = "1";

    /** Antwoord; {@code content/page/size/totalElements/totalPages} spiegelen {@link PageResult}. */
    public record PsimportPreview(boolean previewOnly, String contractStatus, String previewSpecVersion,
                                  long bundleId, String bundleContentHash, Instant generatedAt,
                                  List<Row> content, int page, int size, long totalElements, int totalPages) {
    }

    private final PublicationBundleRepository bundles;
    private final PsimportPreviewDao dao;

    public PsimportPreviewService(PublicationBundleRepository bundles, PsimportPreviewDao dao) {
        this.bundles = bundles;
        this.dao = dao;
    }

    /**
     * @throws NotFoundException        {@code BUNDLE_NOT_FOUND}
     * @throws ConflictException        {@code BUNDLE_NOT_FROZEN} bij elke andere status dan FROZEN
     * @throws IllegalArgumentException ongeldige paginering
     */
    public PsimportPreview preview(long bundleId, Integer page, Integer size) {
        PublicationBundle bundle = bundles.findById(bundleId).orElseThrow(() -> new NotFoundException(
                PublicationBundleService.CODE_BUNDLE_NOT_FOUND, "Bundle " + bundleId + " not found"));
        if (bundle.getStatus() != PublicationBundleStatus.FROZEN) {
            throw new ConflictException(CODE_BUNDLE_NOT_FROZEN, "Bundle " + bundleId + " is "
                    + bundle.getStatus() + "; a PSIMPORT preview is only available for a FROZEN bundle");
        }
        int number = page == null ? 0 : page;
        int requested = size == null ? BundleQueryService.DEFAULT_PAGE_SIZE : size;
        if (number < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (requested < 1) {
            throw new IllegalArgumentException("size must be at least 1");
        }
        int pageSize = Math.min(requested, BundleQueryService.MAX_PAGE_SIZE);
        long total = dao.count(bundleId);
        List<Row> rows = dao.findPage(bundleId, pageSize, (long) number * pageSize).stream()
                .map(PsimportPreviewMapper::map).toList();
        byte[] hash = bundle.getContentHash();
        return new PsimportPreview(true, CONTRACT_STATUS, PREVIEW_SPEC_VERSION, bundleId,
                hash == null ? null : HexFormat.of().formatHex(hash), Instant.now(), rows, number, pageSize,
                total, (int) ((total + pageSize - 1) / pageSize));
    }
}
