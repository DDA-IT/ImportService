package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.LegacyConfigOrigin;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Herkomst van legacy-interfaces (changeset 020-2). In S1 nog door geen service beschreven; APPLY (S2) en de
 * credentialovername (S4) vullen ze aan.
 */
public interface LegacyConfigOriginRepository extends JpaRepository<LegacyConfigOrigin, Long> {

    Optional<LegacyConfigOrigin> findByOriginEnvironmentAndOriginDossierAndInterfaceCode(String originEnvironment,
                                                                                         String originDossier,
                                                                                         String interfaceCode);
}
