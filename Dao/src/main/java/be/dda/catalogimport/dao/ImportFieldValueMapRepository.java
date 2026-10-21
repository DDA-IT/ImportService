package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.ImportFieldValueMap;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * De vertaaltabellen (MAP) van de veldmappings van een revisie (legacy-config S8a, changeset 023-1).
 * <p>
 * Net als de mappings zelf worden ze <b>exact één keer per batch</b> gelezen, in stap B' vóór het bestand geopend
 * wordt ({@link #findByRevisionId}): één query voor de hele revisie, nooit een query per mapping of per bronregel.
 */
public interface ImportFieldValueMapRepository extends JpaRepository<ImportFieldValueMap, Long> {

    /**
     * Alle vertaalrijen van alle mappings van een revisie, op mapping en daarbinnen op volgnummer. Het
     * {@code join fetch} is bewust (zoals {@link ImportFieldMappingRepository#findByRevisionIdWithTargetField}): de
     * aanroeper koppelt de rijen aan hun mapping en leest de configuratie buiten de transactie verder.
     */
    @Query("select v from ImportFieldValueMap v join fetch v.mapping m where m.definitionRevision.id = :revisionId "
            + "order by m.id, v.sequenceNumber")
    List<ImportFieldValueMap> findByRevisionId(@Param("revisionId") Long revisionId);

    /**
     * De vertaalrijen van één mapping, op volgnummer. Om ze te verwijderen (vóór de mapping zelf: geen cascade in het
     * schema) worden ze als entiteit verwijderd en niet met een bulkdelete: een kopie die in dezelfde transactie nog
     * beheerd wordt (opvolgende DRAFT van een APPLY) zou anders naar een verwijderde mapping blijven wijzen.
     */
    List<ImportFieldValueMap> findByMappingIdOrderBySequenceNumberAsc(Long mappingId);
}
