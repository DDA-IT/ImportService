package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.ImportDefinitionBookmarkUsage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * De toegelaten plaatsen (witte lijst) van één bookmarkdeclaratie (sjabloon-materialisatie-design.md
 * §3, bouwstap 5a).
 */
public interface ImportDefinitionBookmarkUsageRepository
        extends JpaRepository<ImportDefinitionBookmarkUsage, Long> {

    /** Alle usage-rijen van een bookmark. */
    List<ImportDefinitionBookmarkUsage> findByBookmarkId(Long bookmarkId);
}
