package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.ImportFieldCatalogEntry;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * De veldcatalogus (ontwerp fase 3, par. 2 004-1): referentiedata, geseed door Liquibase en binnen
 * één screening hoogstens één keer gelezen — nooit per bronregel.
 */
public interface ImportFieldCatalogRepository extends JpaRepository<ImportFieldCatalogEntry, String> {

    List<ImportFieldCatalogEntry> findByActiveTrueOrderBySortOrderAsc();
}
