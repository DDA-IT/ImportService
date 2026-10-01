package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.RevisionStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /**
     * Opzoeklijst voor scherm 1a (S1-B1, buiten {@code catalogimport.setup-api.enabled}): alle revisies
     * van één definitie, oplopend op {@code revisionNumber}.
     */
    @Query(value = "select r from ImportDefinitionRevision r where r.importDefinition.id = :definitionId "
            + "order by r.revisionNumber asc",
            countQuery = "select count(r) from ImportDefinitionRevision r "
                    + "where r.importDefinition.id = :definitionId")
    Page<ImportDefinitionRevision> findByDefinitionId(@Param("definitionId") long definitionId, Pageable pageable);

    /**
     * Batch-query: ACTIVE revisie-id's per definitie-id, voor batch-loading zonder N+1.
     * Alleen definitie-id's met een ACTIVE revisie zijn in het resultaat; afwezige of revisieloos
     * definities zitten niet in het resultaat.
     * <p>
     * Deze query volgt het patroon van {@link #findLatestRunsByTaskIds(Collection)} in TaskRunRepository
     * en {@link #findActiveRevisionsForDefinitions(Collection)} in TaskDefinitionRepository.
     */
    @Query("select new map(r.importDefinition.id as definitionId, r.id as revisionId) "
            + "from ImportDefinitionRevision r "
            + "where r.importDefinition.id in :definitionIds and r.status = 'ACTIVE'")
    List<java.util.Map<String, Long>> findActiveRevisionsByDefinitionIds(
            @Param("definitionIds") Collection<Long> definitionIds);
}
