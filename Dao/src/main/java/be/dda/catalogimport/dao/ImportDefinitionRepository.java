package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.ImportDefinition;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportDefinitionRepository extends JpaRepository<ImportDefinition, Long> {

    /** Natuurlijke sleutel: bronorganisatie + code. */
    Optional<ImportDefinition> findBySourceOrganisationIdAndCode(Long sourceOrganisationId, String code);

    List<ImportDefinition> findBySourceOrganisationId(Long sourceOrganisationId);
}
