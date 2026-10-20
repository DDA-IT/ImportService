package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.LegacyConfigReceipt;
import be.dda.catalogimport.domain.LegacyConfigReceiptStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Ontvangsten van legacy-config-bundels (changeset 020-1; {@code docs/design/legacy-config-bundle-design.md} par. 2).
 * Geen enkele methode geeft een secret terug: de tabel bevat er geen.
 */
public interface LegacyConfigReceiptRepository extends JpaRepository<LegacyConfigReceipt, Long> {

    /**
     * De laatste ontvangst (hoogste id) van deze herkomst, ongeacht haar status: de enige ontvangst waartegen een push
     * op idempotentie vergeleken wordt (beslissingslog 2026-10-20; index {@code idx_legacy_config_receipt_origin_latest}).
     */
    Optional<LegacyConfigReceipt> findFirstByOriginEnvironmentAndOriginDossierAndInterfaceCodeOrderByIdDesc(
            String originEnvironment, String originDossier, String interfaceCode);

    /**
     * De open ontvangst ({@code RECEIVED}) van deze herkomst, met een schrijfslot tot het einde van de transactie:
     * twee gelijktijdige pushes van dezelfde herkomst vervangen ze zo na elkaar (patroon
     * {@code ExternalCredentialRepository#findByCredentialRefForUpdate}). Wie wacht en daarna de rij als
     * {@code SUPERSEDED} terugvindt, krijgt niets terug.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from LegacyConfigReceipt r where r.originEnvironment = :environment "
            + "and r.originDossier = :dossier and r.interfaceCode = :interfaceCode and r.openMarker = true")
    Optional<LegacyConfigReceipt> findOpenForUpdate(@Param("environment") String environment,
                                                    @Param("dossier") String dossier,
                                                    @Param("interfaceCode") String interfaceCode);

    /**
     * De lijst (GET /receipts), optioneel gefilterd op status en exacte interfacecode; vaste sortering
     * {@code received_at desc, id desc}.
     */
    @Query(value = "select r from LegacyConfigReceipt r where (:status is null or r.status = :status) "
            + "and (:interfaceCode is null or r.interfaceCode = :interfaceCode) order by r.receivedAt desc, r.id desc",
            countQuery = "select count(r) from LegacyConfigReceipt r where (:status is null or r.status = :status) "
                    + "and (:interfaceCode is null or r.interfaceCode = :interfaceCode)")
    Page<LegacyConfigReceipt> findRows(@Param("status") LegacyConfigReceiptStatus status,
                                       @Param("interfaceCode") String interfaceCode, Pageable pageable);
}
