package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.RevisionStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportDefinitionRevisionRepository extends JpaRepository<ImportDefinitionRevision, Long> {

    Optional<ImportDefinitionRevision> findByImportDefinitionIdAndRevisionNumber(Long importDefinitionId,
                                                                                int revisionNumber);

    /** Hoogstens één ACTIVE revisie per definitie; dit wordt ook op databaseniveau afgedwongen. */
    Optional<ImportDefinitionRevision> findByImportDefinitionIdAndStatus(Long importDefinitionId,
                                                                        RevisionStatus status);

    List<ImportDefinitionRevision> findByImportDefinitionIdOrderByRevisionNumberDesc(Long importDefinitionId);
}
