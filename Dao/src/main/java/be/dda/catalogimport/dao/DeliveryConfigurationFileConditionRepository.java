package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.DeliveryConfigurationFileCondition;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Bestandsvoorwaarden van een Leveringsconfiguratie-versie (changeset 014-5). */
public interface DeliveryConfigurationFileConditionRepository
        extends JpaRepository<DeliveryConfigurationFileCondition, Long> {

    /** De voorwaarden van één versie in vaste volgorde: groep, dan volgnummer. */
    List<DeliveryConfigurationFileCondition> findByDeliveryConfigurationVersionIdOrderByGroupNumberAscSequenceNumberAsc(
            Long deliveryConfigurationVersionId);
}
