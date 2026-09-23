package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.RevisionStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImportDefinitionRevisionRepository extends JpaRepository<ImportDefinitionRevision, Long> {

    Optional<ImportDefinitionRevision> findByImportDefinitionIdAndRevisionNumber(Long importDefinitionId,
                                                                                int revisionNumber);

    /** Hoogstens één ACTIVE revisie per definitie; dit wordt ook op databaseniveau afgedwongen. */
    Optional<ImportDefinitionRevision> findByImportDefinitionIdAndStatus(Long importDefinitionId,
                                                                        RevisionStatus status);

    List<ImportDefinitionRevision> findByImportDefinitionIdOrderByRevisionNumberDesc(Long importDefinitionId);

    /**
     * De revisies van meerdere definities in één keer, met hun herkomstrevisie meegeladen — voor de
     * keuzelijst {@code GET /templates/{id}/materialisations} (bouwstap 5d). Zonder de
     * {@code left join fetch} zou elke rij van die lijst een extra query kosten om de sjabloonversie te
     * tonen waarop ze bevroren is. {@code left}, want een revisie die niet uit een sjabloon komt
     * ({@code based_on_revision_id is null}) moet zichtbaar blijven in plaats van uit de lijst te
     * verdwijnen.
     * <p>
     * Oplopend op revisienummer: de eerste revisie met een herkomstrevisie is de
     * materialisatierevisie, en die bepaalt uit welke sjabloonversie de definitie stamt (ontwerp §6
     * punt 3).
     */
    @Query("select r from ImportDefinitionRevision r left join fetch r.basedOnRevision "
            + "where r.importDefinition.id in :definitionIds order by r.revisionNumber asc")
    List<ImportDefinitionRevision> findWithOriginByImportDefinitionIdIn(
            @Param("definitionIds") Collection<Long> definitionIds);
}
