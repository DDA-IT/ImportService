package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.ImportFieldTransformStep;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * De ketenstappen (CHAIN) van de veldmappings van een revisie (legacy-config S8c, changeset 024-1).
 * <p>
 * Zoals de vertaalrijen ({@link ImportFieldValueMapRepository}) <b>exact één keer per batch</b> gelezen, in stap B'
 * vóór het bestand geopend wordt ({@link #findByRevisionId}): één query voor de hele revisie.
 */
public interface ImportFieldTransformStepRepository extends JpaRepository<ImportFieldTransformStep, Long> {

    /**
     * Alle stappen van alle mappings van een revisie, op mapping en daarbinnen op stapnummer; {@code join fetch} van de
     * mapping zoals {@link ImportFieldValueMapRepository#findByRevisionId}.
     */
    @Query("select s from ImportFieldTransformStep s join fetch s.mapping m where m.definitionRevision.id = :revisionId "
            + "order by m.id, s.stepNumber")
    List<ImportFieldTransformStep> findByRevisionId(@Param("revisionId") Long revisionId);

    /**
     * De stappen van één mapping, op stapnummer. Verwijderen gebeurt als entiteit (vóór de mapping zelf: geen cascade),
     * nooit met een bulkdelete; zie {@link ImportFieldValueMapRepository#findByMappingIdOrderBySequenceNumberAsc}.
     */
    List<ImportFieldTransformStep> findByMappingIdOrderByStepNumberAsc(Long mappingId);
}
