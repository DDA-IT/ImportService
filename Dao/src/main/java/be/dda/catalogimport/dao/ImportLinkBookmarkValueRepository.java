package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.ImportLinkBookmarkValue;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * De LINK-scope bookmarkwaarden van een importkoppeling (sjabloon-materialisatie-design.md §3,
 * bouwstap 5a).
 */
public interface ImportLinkBookmarkValueRepository extends JpaRepository<ImportLinkBookmarkValue, Long> {

    /** De waarde op (koppeling, bookmarknaam); afwezigheid betekent "niet ingevuld" (R-BMK-03). */
    Optional<ImportLinkBookmarkValue> findByImportLinkIdAndBookmarkName(Long importLinkId, String bookmarkName);

    /**
     * Alle ingevulde waarden van een koppeling, op naam gesorteerd (bouwstap 5f). De volgorde is de
     * leesvolgorde van {@code GET /links/{id}/bookmark-values} én de canonieke volgorde van de
     * vingerafdruk in {@code import_batch.bookmark_values_hash}; sorteren op naam is het enige
     * stabiele criterium, want {@code sort_order} hoort bij de declaratie en niet bij de waarde.
     * <p>
     * Ook rijen waarvan de naam in de huidige actieve revisie niet meer gedeclareerd is (wezen) komen
     * mee: die blijven auditmateriaal en moeten getoond worden, niet stil verdwijnen
     * (sjabloon-materialisatie-design.md §7, javadoc {@code ImportLinkBookmarkValue}).
     */
    List<ImportLinkBookmarkValue> findByImportLinkIdOrderByBookmarkNameAsc(Long importLinkId);
}
