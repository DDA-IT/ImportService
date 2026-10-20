package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.LegacyConfigOrigin;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Herkomst van legacy-interfaces (changeset 020-2). APPLY (S2) en de credentialovername bij ontvangst (S4) vullen ze
 * aan.
 */
public interface LegacyConfigOriginRepository extends JpaRepository<LegacyConfigOrigin, Long> {

    Optional<LegacyConfigOrigin> findByOriginEnvironmentAndOriginDossierAndInterfaceCode(String originEnvironment,
                                                                                         String originDossier,
                                                                                         String interfaceCode);

    /**
     * De herkomst met een schrijfslot tot het einde van de transactie (S4): twee pushes die elk een credential zouden
     * aanmaken, lezen {@code credential_id} zo na elkaar. Bestaat de rij nog niet, dan is er niets te vergrendelen;
     * een gelijktijdige insert botst dan op {@code uk_legacy_config_origin} en wordt herhaald.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from LegacyConfigOrigin o where o.originEnvironment = :environment "
            + "and o.originDossier = :dossier and o.interfaceCode = :interfaceCode")
    Optional<LegacyConfigOrigin> findForUpdate(@Param("environment") String environment,
                                               @Param("dossier") String dossier,
                                               @Param("interfaceCode") String interfaceCode);
}
