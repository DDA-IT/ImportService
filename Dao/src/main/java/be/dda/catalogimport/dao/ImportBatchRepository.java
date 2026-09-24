package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.ValidationResult;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, Long> {

    /**
     * De open batch van een levering, indien die {@code open_marker} bezet. De databaseconstraint
     * {@code uk_import_batch_open} garandeert dat dit er hoogstens één is.
     */
    Optional<ImportBatch> findByDeliveryIdAndOpenMarkerIsNotNull(Long deliveryId);

    List<ImportBatch> findByDeliveryIdOrderByAttemptNoAsc(Long deliveryId);

    /**
     * De batch met koppeling en leverancier al geladen (beide {@code LAZY}, open-in-view staat uit):
     * {@code BatchQueryService.BatchDetail} toont de koppelingslabels zonder tweede aanroep.
     */
    @Query("select b from ImportBatch b join fetch b.importLink il join fetch il.supplierOrganisation "
            + "where b.id = :id")
    Optional<ImportBatch> findDetailById(@Param("id") Long id);

    /**
     * Heeft deze koppeling een open (niet-terminale) batch? {@code open_marker} is {@code TRUE} zolang
     * de status niet terminaal is en {@code null} zodra ze dat wel is (zie {@link ImportBatch}), dus dit
     * is exact "een batch die nog loopt of nog op verwerking wacht".
     * <p>
     * Grondslag van het slot op LINK-bookmarkwaarden (beslissingslog 23/09 keuze 5,
     * sjabloon-materialisatie-design.md §7): een bookmarkwaarde wijzigen terwijl er een levering onder
     * die waarde loopt, maakt achteraf onbepaalbaar met welke invulling er gescreend is.
     */
    boolean existsByImportLinkIdAndOpenMarkerIsNotNull(Long importLinkId);

    /** Alle batches van een levering onder één revisie (bepaalt het volgende {@code attemptNo}). */
    List<ImportBatch> findByDeliveryIdAndDefinitionRevisionId(Long deliveryId, Long definitionRevisionId);

    /** Recovery bij opstart: batches die nog in een bepaalde (open) status staan. */
    List<ImportBatch> findByStatus(ImportBatchStatus status);

    /**
     * De batch met een schrijfslot tot het einde van de transactie. Gebruikt door de afsluitende
     * transactie van accept-baseline, zodat twee gelijktijdige acceptaties van dezelfde batch de
     * statuscontrole en de overgang naar {@code BASELINE_ACCEPTED} niet allebei kunnen winnen.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from ImportBatch b where b.id = :id")
    Optional<ImportBatch> findByIdForUpdate(@Param("id") Long id);

    /**
     * Batches die in aanmerking komen om aan een Publicatiebundel toegevoegd te worden (ontwerp fase 4
     * par. 3, R-BND): {@code SCREENED}, een vastgesteld {@code validationResult} dat geen
     * {@code BLOCKING} is, optioneel beperkt tot één koppeling, en zonder actief lidmaatschap in welke
     * bundel dan ook. Dezelfde voorwaarde als {@code PublicationBundleService.addOneBatch}, hier als
     * lijst in plaats van als weigering per batch.
     */
    @Query("select b from ImportBatch b where b.status = be.dda.catalogimport.domain.ImportBatchStatus.SCREENED "
            + "and b.validationResult is not null "
            + "and b.validationResult <> be.dda.catalogimport.domain.ValidationResult.BLOCKING "
            + "and (:importLinkId is null or b.importLink.id = :importLinkId) "
            + "and not exists (select 1 from PublicationBundleBatch pbb "
            + "  where pbb.batch = b and pbb.activeMarker is not null)")
    Page<ImportBatch> findBundleCandidates(@Param("importLinkId") Long importLinkId, Pageable pageable);

    /**
     * Werkvoorraadlijst voor Scherm 0 (D14): alle batches, optioneel gefilterd, vast {@code id desc}
     * (nieuwste eerst — vastgelegd in de {@code order by}, niet in de {@link Pageable}, om een dubbele
     * sortering te vermijden). {@code join fetch} op {@code importLink} en haar
     * {@code supplierOrganisation} (beide {@code LAZY}) omdat {@code BatchQueryService.BatchRow} hun
     * velden nodig heeft en dat anders per rij een extra query zou kosten.
     * {@code createdFrom}/{@code createdTo} zijn halfopen {@code [from, to)} op {@code created_at}.
     */
    @Query(value = "select b from ImportBatch b "
            + "join fetch b.importLink il "
            + "join fetch il.supplierOrganisation "
            + "where (:status is null or b.status = :status) "
            + "and (:validationResult is null or b.validationResult = :validationResult) "
            + "and (:importLinkId is null or il.id = :importLinkId) "
            + "and (cast(:createdFrom as timestamp) is null or b.createdAt >= :createdFrom) "
            + "and (cast(:createdTo as timestamp) is null or b.createdAt < :createdTo) "
            + "order by b.id desc",
            countQuery = "select count(b) from ImportBatch b "
                    + "where (:status is null or b.status = :status) "
                    + "and (:validationResult is null or b.validationResult = :validationResult) "
                    + "and (:importLinkId is null or b.importLink.id = :importLinkId) "
                    + "and (cast(:createdFrom as timestamp) is null or b.createdAt >= :createdFrom) "
                    + "and (cast(:createdTo as timestamp) is null or b.createdAt < :createdTo)")
    Page<ImportBatch> findBatchRows(@Param("status") ImportBatchStatus status,
                                    @Param("validationResult") ValidationResult validationResult,
                                    @Param("importLinkId") Long importLinkId,
                                    @Param("createdFrom") Instant createdFrom,
                                    @Param("createdTo") Instant createdTo, Pageable pageable);

    /**
     * Aantal batches per {@link ImportBatchStatus}, optioneel beperkt tot één koppeling (Scherm 0-
     * samenvatting). Eén {@code group by}-query in plaats van een telling per statuswaarde.
     */
    @Query("select b.status, count(b) from ImportBatch b "
            + "where (:importLinkId is null or b.importLink.id = :importLinkId) group by b.status")
    List<Object[]> countByStatusGrouped(@Param("importLinkId") Long importLinkId);

    /**
     * Aantal batches per {@link ValidationResult}, inclusief een eigen groep voor {@code null}
     * ("niet vastgesteld") — die mag nooit met {@code VALID} samenvallen en nooit wegvallen wanneer er
     * werkelijk niet-vastgestelde batches zijn.
     */
    @Query("select b.validationResult, count(b) from ImportBatch b "
            + "where (:importLinkId is null or b.importLink.id = :importLinkId) group by b.validationResult")
    List<Object[]> countByValidationResultGrouped(@Param("importLinkId") Long importLinkId);
}
