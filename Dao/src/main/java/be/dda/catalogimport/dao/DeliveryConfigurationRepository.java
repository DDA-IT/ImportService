package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.DeliveryConfiguration;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Koppen van Leveringsconfiguraties (changeset 014-3). */
public interface DeliveryConfigurationRepository extends JpaRepository<DeliveryConfiguration, Long> {

    Optional<DeliveryConfiguration> findByCode(String code);

    /** Lijst (LC-2): vaste volgorde op code, dan id. */
    List<DeliveryConfiguration> findAllByOrderByCodeAscIdAsc();
}
