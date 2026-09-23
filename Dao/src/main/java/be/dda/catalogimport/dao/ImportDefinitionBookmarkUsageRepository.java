package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.BookmarkValueScope;
import be.dda.catalogimport.domain.ImportDefinitionBookmarkUsage;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * De toegelaten plaatsen (witte lijst) van één bookmarkdeclaratie (sjabloon-materialisatie-design.md
 * §3, bouwstap 5a).
 */
public interface ImportDefinitionBookmarkUsageRepository
        extends JpaRepository<ImportDefinitionBookmarkUsage, Long> {

    /** Alle usage-rijen van een bookmark. */
    List<ImportDefinitionBookmarkUsage> findByBookmarkId(Long bookmarkId);

    /**
     * De usage-rijen van alle bookmarks met één scope, over meerdere revisies heen — de deelbaarheid
     * van een afgeleide definitie (bouwstap 5d, ontwerp §6 punt 5 / vraag Q2).
     * <p>
     * Een {@code LINK}-scope bookmark waarvan de plaats op revisieniveau ligt (het
     * {@code DETAILLEVERANCIER}-geval) zet een per-leverancier waarde in de <i>gedeelde</i> revisie.
     * Om te weten of dat het geval is, volstaat het de declaraties van de afgeleide revisie zélf te
     * lezen: materialisatie kopieert de {@code LINK}-scope declaraties mee (R-MAT-03), dus het
     * sjabloon hoeft er niet voor gelezen te worden — precies het runtime-leespad dat R-MAT-02
     * verbiedt.
     * <p>
     * {@code join fetch} op de bookmark: de aanroeper heeft naam en scope nodig om de blokkerende
     * bookmark te kunnen noemen. Gesorteerd op {@code sort_order}, zodat "de bookmark die het
     * verhindert" bij meerdere kandidaten altijd dezelfde is.
     */
    @Query("select u from ImportDefinitionBookmarkUsage u join fetch u.bookmark b "
            + "where b.definitionRevision.id in :revisionIds and b.valueScope = :valueScope "
            + "order by b.sortOrder asc, u.id asc")
    List<ImportDefinitionBookmarkUsage> findByRevisionIdsAndScope(
            @Param("revisionIds") Collection<Long> revisionIds,
            @Param("valueScope") BookmarkValueScope valueScope);
}
