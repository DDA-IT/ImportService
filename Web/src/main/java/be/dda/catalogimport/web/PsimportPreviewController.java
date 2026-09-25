package be.dda.catalogimport.web;

import be.dda.catalogimport.service.PsimportPreviewCsvSerializer;
import be.dda.catalogimport.service.PsimportPreviewService;
import be.dda.catalogimport.service.PsimportPreviewService.PsimportPreview;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only PSIMPORT-preview van een bevroren bundel (beslissing 2026-09-25, slice 1). Vrij lezen zoals
 * {@code GET /bundles}. 404 {@code BUNDLE_NOT_FOUND}, 409 {@code BUNDLE_NOT_FROZEN}, 400 bij ongeldige
 * paginering of onbekend format. Geen schrijfactie, geen outbox.
 * <p>
 * Format-parameter: {@code format=json} (default) of {@code format=csv}. Onbekend format → 400.
 */
@RestController
@RequestMapping("/api/catalog-import/bundles")
public class PsimportPreviewController {

    private final PsimportPreviewService service;

    public PsimportPreviewController(PsimportPreviewService service) {
        this.service = service;
    }

    @GetMapping("/{bundleId}/psimport-preview")
    public Object preview(@PathVariable("bundleId") long bundleId,
                          @RequestParam(value = "page", required = false) Integer page,
                          @RequestParam(value = "size", required = false) Integer size,
                          @RequestParam(value = "format", required = false, defaultValue = "json") String format,
                          HttpServletResponse response) {
        // Valideer format parameter
        if (!("json".equalsIgnoreCase(format) || "csv".equalsIgnoreCase(format))) {
            throw new IllegalArgumentException("format must be 'json' or 'csv', got: " + format);
        }

        PsimportPreview preview = service.preview(bundleId, page, size);

        if ("csv".equalsIgnoreCase(format)) {
            response.setContentType("text/csv; charset=UTF-8");
            response.setHeader("Content-Disposition", "attachment; filename=\"psimport-preview.csv\"");
            try {
                response.getWriter().write(PsimportPreviewCsvSerializer.toCsv(preview));
            } catch (Exception e) {
                throw new RuntimeException("Failed to write CSV response", e);
            }
            return null;
        }

        // Default: JSON
        return preview;
    }
}
