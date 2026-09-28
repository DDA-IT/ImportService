package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.SourceOrganisation;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SourceOrganisationRepository extends JpaRepository<SourceOrganisation, Long> {

    Optional<SourceOrganisation> findByCode(String code);

    boolean existsByCode(String code);

    /**
     * Opzoeklijst voor scherm 1a (S1-B1, buiten {@code catalogimport.setup-api.enabled}): alle
     * bronorganisaties, optioneel gefilterd op {@code active}, oplopend op {@code code}. Zelfde vorm als
     * {@code ImportLinkRepository.findLinkRows}.
     */
    @Query(value = "select o from SourceOrganisation o where (:active is null or o.active = :active) "
            + "order by o.code asc",
            countQuery = "select count(o) from SourceOrganisation o where (:active is null or o.active = :active)")
    Page<SourceOrganisation> findOrganisationRows(@Param("active") Boolean active, Pageable pageable);
}
