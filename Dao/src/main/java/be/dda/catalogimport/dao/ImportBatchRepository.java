package be.dda.catalogimport.dao;

import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.ValidationResult;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
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
     * De batch met een schrijfslot tot het einde van de transactie (wacht op een bezet slot). Gebruikt door de
     * screening en het opstartherstel. Accept-baseline gebruikt sinds S5-c {@link #findByIdForUpdateNowait}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from ImportBatch b where b.id = :id")
    Optional<ImportBatch> findByIdForUpdate(@Param("id") Long id);

    /**
     * Zoals {@link #findByIdForUpdate}, maar zonder te wachten (S5-a): is het rijslot bezet, dan volgt meteen een
     * lock-fout in plaats van een blokkade. De hint {@code jakarta.persistence.lock.timeout = 0} maakt er voor
     * PostgreSQL {@code FOR UPDATE NOWAIT} van (SQLState 55P03, in Spring vertaald naar een
     * {@code PessimisticLockingFailureException}/{@code CannotAcquireLockException}). PostgreSQL breekt de lopende
     * transactie daarna af: de aanroeper moet de fout buiten de transactie vertalen
     * ({@code LockFailures} in de servicelaag). Slotvolgorde: bundel, run, koppeling(en), batch(es) op oplopend id.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("select b from ImportBatch b where b.id = :id")
    Optional<ImportBatch> findByIdForUpdateNowait(@Param("id") Long id);

    /**
     * Enkel de id van de importkoppeling van een batch, zonder de batch zelf als entiteit te laden (S5-c). Nodig wie
     * eerst de koppeling en pas daarna de batch wil vergrendelen (slotvolgorde koppeling vóór batch): een eerder
     * geladen, niet-vergrendelde batch zou in de persistentiecontext blijven en de latere
     * {@link #findByIdForUpdateNowait} zou dan die oude toestand teruggeven in plaats van de rij onder het slot.
     * Leeg als de batch niet bestaat. De koppeling van een batch wijzigt nooit.
     */
    @Query("select b.importLink.id from ImportBatch b where b.id = :id")
    Optional<Long> findImportLinkIdById(@Param("id") Long id);

    /**
     * Fencing van de verwerkingsclaim (stap 4, changeset 017): ververst het levensteken enkel als de batch nog
     * exact deze claim-token draagt. Levert 1 als de claim nog van de aanroeper is, 0 als ze intussen gewist of
     * door een andere claim vervangen werd (claim verloren). De update neemt het rijslot tot het einde van de
     * transactie, zodat de claim tijdens de rest van die transactie niet kan wisselen; een gelijktijdige
     * overname of opstartherstel wacht en ziet daarna de verse heartbeat.
     * <p>
     * Hoort als <b>eerste</b> statement in de transactie: een batch die vóór deze update al in de
     * persistence context geladen werd, draagt nog de oude heartbeat (er wordt niet automatisch gewist, zodat een
     * geladen entiteit nooit stil losgekoppeld raakt).
     */
    @Modifying(flushAutomatically = true)
    @Query("update ImportBatch b set b.processingHeartbeatAt = :now "
            + "where b.id = :id and b.processingClaimToken = :token")
    int touchProcessingClaim(@Param("id") Long id, @Param("token") UUID token, @Param("now") Instant now);

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
