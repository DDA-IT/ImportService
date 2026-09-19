package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.ImportFieldMapping;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * De veldmappings van een bevroren revisie (ontwerp fase 3, par. 2 004-2).
 * <p>
 * Ze worden exact één keer per batch gelezen, in stap B' vóór het bestand geopend wordt: een query
 * per bronregel zou bij een miljoen regels een miljoen queries betekenen.
 */
public interface ImportFieldMappingRepository extends JpaRepository<ImportFieldMapping, Long> {

    /**
     * Alle mappings van een revisie, oplopend op volgnummer, met de catalogusrij er meteen bij. Het
     * {@code join fetch} is bewust: de aanroeper leest de configuratie buiten de transactie verder en
     * mag geen lazy associatie meer aanraken.
     */
    @Query("select m from ImportFieldMapping m join fetch m.targetField "
            + "where m.definitionRevision.id = :revisionId order by m.sequenceNumber")
    List<ImportFieldMapping> findByRevisionIdWithTargetField(@Param("revisionId") Long revisionId);
}
