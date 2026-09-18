package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.SourceOrganisation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceOrganisationRepository extends JpaRepository<SourceOrganisation, Long> {

    Optional<SourceOrganisation> findByCode(String code);

    boolean existsByCode(String code);
}
