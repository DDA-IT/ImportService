package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.ImportLinkBookmarkValue;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * De LINK-scope bookmarkwaarden van een importkoppeling (sjabloon-materialisatie-design.md §3,
 * bouwstap 5a).
 */
public interface ImportLinkBookmarkValueRepository extends JpaRepository<ImportLinkBookmarkValue, Long> {

    /** De waarde op (koppeling, bookmarknaam); afwezigheid betekent "niet ingevuld" (R-BMK-03). */
    Optional<ImportLinkBookmarkValue> findByImportLinkIdAndBookmarkName(Long importLinkId, String bookmarkName);
}
