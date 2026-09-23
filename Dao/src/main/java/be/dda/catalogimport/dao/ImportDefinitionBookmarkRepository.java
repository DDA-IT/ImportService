package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.ImportDefinitionBookmark;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * De bookmarkdeclaraties van een sjabloonrevisie (sjabloon-materialisatie-design.md §3, bouwstap 5a).
 */
public interface ImportDefinitionBookmarkRepository extends JpaRepository<ImportDefinitionBookmark, Long> {

    /** Alle bookmarks van een revisie, in de invulschermvolgorde. */
    List<ImportDefinitionBookmark> findByDefinitionRevisionIdOrderBySortOrderAsc(Long definitionRevisionId);
}
