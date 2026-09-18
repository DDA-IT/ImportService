package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.CatalogImportTask;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogImportTaskRepository extends JpaRepository<CatalogImportTask, Long> {

    Optional<CatalogImportTask> findByImportLinkIdAndName(Long importLinkId, String name);

    List<CatalogImportTask> findByActiveTrue();
}
