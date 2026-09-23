package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.ImportDefinitionBookmarkValue;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * De DEFINITION-scope bookmarksnapshot van een afgeleide revisie (sjabloon-materialisatie-design.md
 * §3, bouwstap 5a).
 */
public interface ImportDefinitionBookmarkValueRepository
        extends JpaRepository<ImportDefinitionBookmarkValue, Long> {

    /** De waarde op (revisie, bookmarknaam); afwezigheid betekent "niet ingevuld" (R-BMK-03). */
    Optional<ImportDefinitionBookmarkValue> findByDefinitionRevisionIdAndBookmarkName(Long definitionRevisionId,
                                                                                       String bookmarkName);
}
