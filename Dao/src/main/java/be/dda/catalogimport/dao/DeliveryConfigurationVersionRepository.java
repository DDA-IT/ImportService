package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.DeliveryConfigurationVersion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Onveranderlijke versies van Leveringsconfiguraties (changeset 014-4): enkel toevoegen en lezen. */
public interface DeliveryConfigurationVersionRepository extends JpaRepository<DeliveryConfigurationVersion, Long> {

    /** De versies van één configuratie, nieuwste eerst. */
    List<DeliveryConfigurationVersion> findByDeliveryConfigurationIdOrderByVersionNumberDesc(
            Long deliveryConfigurationId);

    Optional<DeliveryConfigurationVersion> findByDeliveryConfigurationIdAndVersionNumber(
            Long deliveryConfigurationId, int versionNumber);

    /** Het hoogste versienummer van een configuratie, of leeg als er nog geen versie is. */
    @Query("select max(v.versionNumber) from DeliveryConfigurationVersion v "
            + "where v.deliveryConfiguration.id = :configurationId")
    Optional<Integer> findMaxVersionNumber(@Param("configurationId") Long configurationId);

    /** De hoogste versie van elke configuratie in één query (lijst, LC-2). */
    @Query("select v from DeliveryConfigurationVersion v where v.versionNumber = (select max(v2.versionNumber) "
            + "from DeliveryConfigurationVersion v2 where v2.deliveryConfiguration = v.deliveryConfiguration)")
    List<DeliveryConfigurationVersion> findLatestVersions();
}
