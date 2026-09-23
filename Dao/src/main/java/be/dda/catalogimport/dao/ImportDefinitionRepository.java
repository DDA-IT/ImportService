package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.DefinitionUsageType;
import be.dda.catalogimport.domain.ImportDefinition;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportDefinitionRepository extends JpaRepository<ImportDefinition, Long> {

    /** Natuurlijke sleutel: bronorganisatie + code. */
    Optional<ImportDefinition> findBySourceOrganisationIdAndCode(Long sourceOrganisationId, String code);

    List<ImportDefinition> findBySourceOrganisationId(Long sourceOrganisationId);

    /** De sjablonen ({@code REUSABLE_TEMPLATE}), gepagineerd; toegevoegd in bouwstap 5b. */
    Page<ImportDefinition> findByUsageType(DefinitionUsageType usageType, Pageable pageable);
}
