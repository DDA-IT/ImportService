package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.ImportLink;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportLinkRepository extends JpaRepository<ImportLink, Long> {

    Optional<ImportLink> findByCode(String code);

    List<ImportLink> findByImportDefinitionId(Long importDefinitionId);

    List<ImportLink> findByLibraryCode(String libraryCode);
}
