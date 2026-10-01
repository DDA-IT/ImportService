package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.ExternalCredential;
import be.dda.catalogimport.domain.ExternalCredentialStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Credentials van externe bronnen (changeset 013-1). Geen enkele methode hier geeft een ontsleutelde waarde
 * terug: ontsleutelen gebeurt uitsluitend in {@code SecretsService} (V1).
 */
public interface ExternalCredentialRepository extends JpaRepository<ExternalCredential, Long> {

    Optional<ExternalCredential> findByCredentialRef(UUID credentialRef);

    /**
     * De credential met een schrijfslot tot het einde van de transactie (K-3): serialisatiepunt voor vervangen,
     * heractiveren en intrekken, zelfde patroon als {@code IssueCaseRepository#findByIdForUpdate}. Twee
     * gelijktijdige schrijfacties op dezelfde credential lopen zo na elkaar en zien elk de toestand van de vorige.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ExternalCredential c where c.credentialRef = :credentialRef")
    Optional<ExternalCredential> findByCredentialRefForUpdate(@Param("credentialRef") UUID credentialRef);

    /** Alle credentials voor het beheeroverzicht (K-3), vaste sortering op label en daarna id. */
    List<ExternalCredential> findAllByOrderByLabelAscIdAsc();

    /**
     * De sleutel-ID's waaronder nog minstens één waarde versleuteld is, oplopend gesorteerd. Een ID die niet in
     * de sleutelring staat, betekent: die credentials zijn niet te ontsleutelen ({@code UNDECRYPTABLE}, afgeleid,
     * V5/A6) — de opstartcontrole meldt dat als waarschuwing. Ook de basis om een oude sleutel pas te verwijderen
     * bij 0 rijen eronder (V4).
     */
    @Query("select distinct c.encryptionKeyId from ExternalCredential c "
            + "where c.encryptionKeyId is not null order by c.encryptionKeyId")
    List<String> findDistinctEncryptionKeyIds();

    /** Aantal rijen per sleutel-ID (K-2b): de operatorregel "een oude sleutel pas verwijderen bij 0 rijen eronder". */
    interface KeyIdCount {
        String getEncryptionKeyId();

        long getRowCount();
    }

    @Query("select c.encryptionKeyId as encryptionKeyId, count(c) as rowCount from ExternalCredential c "
            + "where c.encryptionKeyId is not null group by c.encryptionKeyId order by c.encryptionKeyId")
    List<KeyIdCount> countRowsPerEncryptionKeyId();

    /**
     * Kandidaten voor herversleutelen (K-2b): {@code status}-rijen (ACTIVE) onder een ander sleutel-ID dan de actieve,
     * waarvan het sleutel-ID in de ring staat. Rijen onder een onbekend sleutel-ID (V5/A6) en ingetrokken rijen
     * (geen ciphertext) komen er nooit in voor.
     */
    @Query("select c from ExternalCredential c where c.status = :status and c.encryptionKeyId is not null "
            + "and c.encryptionKeyId <> :activeKeyId and c.encryptionKeyId in :ringKeyIds order by c.id")
    List<ExternalCredential> findRotationCandidates(@Param("status") ExternalCredentialStatus status,
                                                    @Param("activeKeyId") String activeKeyId,
                                                    @Param("ringKeyIds") Collection<String> ringKeyIds);

    /**
     * Optimistische guard (K-2b): vervangt de ciphertext enkel als ze nog exact de gelezen waarde is en de rij nog
     * {@code status} heeft. Levert 1 bij succes, 0 wanneer een andere instantie de rij intussen herversleuteld,
     * vervangen of ingetrokken heeft. Onder READ COMMITTED wacht een gelijktijdige tweede update op de eerste en
     * herevalueert dan de {@code where}, zodat maar één van beide slaagt.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ExternalCredential c set c.ciphertext = :newCiphertext, c.encryptionKeyId = :newKeyId "
            + "where c.id = :id and c.ciphertext = :oldCiphertext and c.encryptionKeyId = :oldKeyId "
            + "and c.status = :status")
    int replaceCiphertextIfUnchanged(@Param("id") Long id, @Param("oldCiphertext") String oldCiphertext,
                                     @Param("oldKeyId") String oldKeyId,
                                     @Param("newCiphertext") String newCiphertext,
                                     @Param("newKeyId") String newKeyId,
                                     @Param("status") ExternalCredentialStatus status);
}
