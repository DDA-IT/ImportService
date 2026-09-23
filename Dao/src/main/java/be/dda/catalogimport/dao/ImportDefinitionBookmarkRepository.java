package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.ImportDefinitionBookmark;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * De bookmarkdeclaraties van een sjabloonrevisie (sjabloon-materialisatie-design.md §3, bouwstap 5a).
 */
public interface ImportDefinitionBookmarkRepository extends JpaRepository<ImportDefinitionBookmark, Long> {

    /** Alle bookmarks van een revisie, in de invulschermvolgorde. */
    List<ImportDefinitionBookmark> findByDefinitionRevisionIdOrderBySortOrderAsc(Long definitionRevisionId);

    /** Eén bookmark op haar (stabiele) naam binnen een revisie; toegevoegd in bouwstap 5b. */
    Optional<ImportDefinitionBookmark> findByDefinitionRevisionIdAndName(Long definitionRevisionId, String name);
}
