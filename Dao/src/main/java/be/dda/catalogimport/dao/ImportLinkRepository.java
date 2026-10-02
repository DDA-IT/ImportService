package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.ImportLink;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface ImportLinkRepository extends JpaRepository<ImportLink, Long> {

    Optional<ImportLink> findByCode(String code);

    /**
     * De koppeling met een schrijfslot tot het einde van de transactie, zonder te wachten (S5-a): bezet slot geeft
     * meteen een lock-fout ({@code FOR UPDATE NOWAIT}, hint {@code jakarta.persistence.lock.timeout = 0}). Zie
     * {@link ImportBatchRepository#findByIdForUpdateNowait} voor de afhandeling van die fout en de slotvolgorde
     * (koppeling vóór batch).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("select l from ImportLink l where l.id = :id")
    Optional<ImportLink> findByIdForUpdateNowait(@Param("id") Long id);

    List<ImportLink> findByImportDefinitionId(Long importDefinitionId);

    List<ImportLink> findByLibraryCode(String libraryCode);

    /**
     * Het aantal koppelingen per definitie, voor de keuzelijst
     * {@code GET /templates/{id}/materialisations} (bouwstap 5d): "hoeveel leveranciers delen deze
     * definitie al?". Eén {@code group by} in plaats van een telling per rij — met een paginagrootte
     * tot 200 zou dat laatste 200 extra queries kosten.
     * <p>
     * Elk element is {@code [definitieId (Long), aantal (Long)]}; een definitie zonder koppelingen komt
     * niet in het resultaat voor (en telt bij de aanroeper als 0). Zelfde {@code Object[]}-vorm als de
     * telblokken op {@code ImportBatchRepository}.
     */
    @Query("select l.importDefinition.id, count(l) from ImportLink l "
            + "where l.importDefinition.id in :definitionIds group by l.importDefinition.id")
    List<Object[]> countByImportDefinitionIdGrouped(@Param("definitionIds") Collection<Long> definitionIds);

    /**
     * Opzoeklijst voor Scherm 0/3 (D14, bouwstap S0-B3): alle koppelingen, optioneel gefilterd op
     * {@code active} en, sinds S1-B1, op {@code importDefinitionId} (additief, sluit de boom van scherm
     * 1a tot op koppelingenniveau), oplopend op {@code code}. {@code join fetch} op
     * {@code supplierOrganisation} ({@code LAZY}) omdat {@code ImportLinkQueryService.ImportLinkRow} haar
     * naam/code toont en dat anders per rij een extra query zou kosten. De sortering staat in de
     * {@code order by}, niet in de {@link Pageable}: {@code code} bestaat op zowel {@code ImportLink} als
     * de gejoinde {@code SourceOrganisation}, wat een door Spring Data afgeleide {@code order by}
     * dubbelzinnig zou maken.
     */
    @Query(value = "select l from ImportLink l join fetch l.supplierOrganisation "
            + "where (:active is null or l.active = :active) "
            + "and (:importDefinitionId is null or l.importDefinition.id = :importDefinitionId) "
            + "order by l.code asc",
            countQuery = "select count(l) from ImportLink l where (:active is null or l.active = :active) "
                    + "and (:importDefinitionId is null or l.importDefinition.id = :importDefinitionId)")
    Page<ImportLink> findLinkRows(@Param("active") Boolean active,
                                  @Param("importDefinitionId") Long importDefinitionId, Pageable pageable);
}
