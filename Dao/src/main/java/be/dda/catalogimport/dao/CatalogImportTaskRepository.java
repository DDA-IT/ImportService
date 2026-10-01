package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.CatalogImportTask;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CatalogImportTaskRepository extends JpaRepository<CatalogImportTask, Long> {

    Optional<CatalogImportTask> findByImportLinkIdAndName(Long importLinkId, String name);

    List<CatalogImportTask> findByActiveTrue();

    /**
     * Alle taken van één koppeling, oplopend op {@code id}, <b>zonder</b> rijslot (NT-8, gereedheidscontrole): enkel
     * lezen, nooit een serialisatiepunt — daarvoor bestaat {@link #findByImportLinkIdForUpdate}.
     */
    List<CatalogImportTask> findByImportLinkIdOrderByIdAsc(Long importLinkId);

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

    /**
     * De koppeling van een taak, zonder de taak zelf in de persistence context te laden (LC-2): de taakkoppeling
     * vergrendelt daarna alle taken van die koppeling en moet hun toestand vers <b>na</b> het slot lezen.
     */
    @Query("select t.importLink.id from CatalogImportTask t where t.id = :taskId")
    Optional<Long> findImportLinkIdByTaskId(@Param("taskId") Long taskId);

    /**
     * Alle taken van één koppeling met een rijslot, in vaste volgorde op {@code id} (geen deadlock tussen twee
     * gelijktijdige aanroepers). Serialisatiepunt voor de taakkoppeling aan een Leveringsconfiguratie (LC-2): A11
     * (hoogstens één taak met een Leveringsconfiguratie per koppeling) is een servicecontrole zonder
     * databaseconstraint en houdt zo ook onder gelijktijdige aanvragen stand.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from CatalogImportTask t where t.importLink.id = :importLinkId order by t.id")
    List<CatalogImportTask> findByImportLinkIdForUpdate(@Param("importLinkId") Long importLinkId);

    /**
     * Eén taak met hetzelfde rijslot als {@link #findByImportLinkIdForUpdate}. Serialisatiepunt tussen de taakkoppeling
     * (LC-2) en elke intake of ophaalrun op die taak (K-4b, racevenster A10): wie het slot tweede krijgt, leest de
     * toestand van de eerste pas na diens commit. Moet de <b>eerste</b> lezing van de taak in de transactie zijn: een
     * reeds geladen entiteit wordt door een slotquery niet ververst.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from CatalogImportTask t where t.id = :taskId")
    Optional<CatalogImportTask> findByIdForUpdate(@Param("taskId") Long taskId);
}
