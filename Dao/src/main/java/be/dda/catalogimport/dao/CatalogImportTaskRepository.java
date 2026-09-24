package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.CatalogImportTask;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CatalogImportTaskRepository extends JpaRepository<CatalogImportTask, Long> {

    Optional<CatalogImportTask> findByImportLinkIdAndName(Long importLinkId, String name);

    List<CatalogImportTask> findByActiveTrue();

    /**
     * Takenlijst (Scherm 2): optioneel gefilterd op koppeling en {@code active}, vast oplopend op
     * koppelingscode, taaknaam en id. {@code join fetch} op koppeling en leverancier (beide {@code LAZY})
     * zodat er geen query per rij nodig is.
     */
    @Query(value = "select t from CatalogImportTask t join fetch t.importLink il "
            + "join fetch il.supplierOrganisation "
            + "where (:importLinkId is null or il.id = :importLinkId) "
            + "and (:active is null or t.active = :active) "
            + "order by il.code asc, t.name asc, t.id asc",
            countQuery = "select count(t) from CatalogImportTask t "
                    + "where (:importLinkId is null or t.importLink.id = :importLinkId) "
                    + "and (:active is null or t.active = :active)")
    Page<CatalogImportTask> findTaskRows(@Param("importLinkId") Long importLinkId,
                                         @Param("active") Boolean active, Pageable pageable);
}
