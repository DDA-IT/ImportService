package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.ConnectionProfileVersion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Onveranderlijke versies van verbindingsprofielen (changeset 014-2): enkel toevoegen en lezen. */
public interface ConnectionProfileVersionRepository extends JpaRepository<ConnectionProfileVersion, Long> {

    /** De versies van één profiel, nieuwste eerst. */
    List<ConnectionProfileVersion> findByConnectionProfileIdOrderByVersionNumberDesc(Long connectionProfileId);

    Optional<ConnectionProfileVersion> findByConnectionProfileIdAndVersionNumber(Long connectionProfileId,
                                                                                int versionNumber);

    /** Het hoogste versienummer van een profiel, of leeg als er nog geen versie is (basis voor het volgende nummer). */
    @Query("select max(v.versionNumber) from ConnectionProfileVersion v where v.connectionProfile.id = :profileId")
    Optional<Integer> findMaxVersionNumber(@Param("profileId") Long profileId);

    /** Aantal profielversies dat een credential gebruikt (voor het gebruik in het credentialantwoord, LC-2). */
    long countByCredentialId(Long credentialId);

    /**
     * Het gebruik van alle credentials in één query (credentiallijst, LC-2): per {@code credential_id} het aantal
     * profielversies. Elke rij is {@code [Long credentialId, Long count]}; een credential zonder gebruik komt niet voor.
     */
    @Query("select v.credentialId, count(v) from ConnectionProfileVersion v group by v.credentialId")
    List<Object[]> countPerCredentialId();

    /** De hoogste versie van elk profiel in één query (profiellijst, LC-2). */
    @Query("select v from ConnectionProfileVersion v where v.versionNumber = (select max(v2.versionNumber) "
            + "from ConnectionProfileVersion v2 where v2.connectionProfile = v.connectionProfile)")
    List<ConnectionProfileVersion> findLatestVersions();
}
