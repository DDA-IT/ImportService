package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.domain.ImportLink;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alleen-lezen opzoeklijst van importkoppelingen (Scherm 0/3, D14, bouwstap S0-B3): dient enkel om
 * koppelingsnamen te tonen in plaats van een kaal id, en schrijft niets. Bewust een eigen, kleine
 * service naast {@code LinkBookmarkValueService}/{@code CatalogImportLinkController} — die twee horen
 * bij de materialisatiewizard (achter {@code catalogimport.setup-api.enabled}) en blijven ongemoeid.
 * <p>
 * Paginering: {@code page} 0-gebaseerd, {@code size} standaard {@value #DEFAULT_PAGE_SIZE} en begrensd
 * tot {@value #MAX_PAGE_SIZE}, zelfde contract als {@link BatchQueryService}/{@link BundleQueryService}.
 */
@Service
@Transactional(readOnly = true)
public class ImportLinkQueryService {

    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 200;

    /** Eén koppeling, met leverancierscode/-naam meegeleverd zodat de UI geen tweede opzoekactie doet. */
    public record ImportLinkRow(Long id, String code, String name, String supplierCode, String supplierName,
                                String libraryCode, boolean active) {

        private static ImportLinkRow of(ImportLink link) {
            return new ImportLinkRow(link.getId(), link.getCode(), link.getName(),
                    link.getSupplierOrganisation().getCode(), link.getSupplierOrganisation().getName(),
                    link.getLibraryCode(), link.isActive());
        }
    }

    private final ImportLinkRepository links;

    public ImportLinkQueryService(ImportLinkRepository links) {
        this.links = links;
    }

    /**
     * Alle koppelingen, oplopend op {@code code}, optioneel gefilterd op {@code active}.
     *
     * @throws IllegalArgumentException ongeldige paginering
     */
    public PageResult<ImportLinkRow> listImportLinks(Boolean active, Integer page, Integer size) {
        PageRequest pageRequest = pageRequest(page, size);
        Page<ImportLink> result = links.findLinkRows(active, pageRequest);
        return PageResult.of(result, ImportLinkRow::of);
    }

    /** Sortering staat al vast in de {@code @Query} van {@code findLinkRows}. */
    private static PageRequest pageRequest(Integer page, Integer size) {
        int number = page == null ? 0 : page;
        int requested = size == null ? DEFAULT_PAGE_SIZE : size;
        if (number < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (requested < 1) {
            throw new IllegalArgumentException("size must be at least 1");
        }
        return PageRequest.of(number, Math.min(requested, MAX_PAGE_SIZE), Sort.unsorted());
    }
}
