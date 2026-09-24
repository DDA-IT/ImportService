package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportMutationRepository;
import be.dda.catalogimport.dao.ImportRowIssueRepository;
import be.dda.catalogimport.dao.IssueGroupDao;
import be.dda.catalogimport.dao.IssueGroupDao.GroupRow;
import be.dda.catalogimport.dao.MutationDao;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.ImportMutation;
import be.dda.catalogimport.domain.ImportRowIssue;
import be.dda.catalogimport.domain.MutationActionType;
import be.dda.catalogimport.domain.MutationStatus;
import be.dda.catalogimport.domain.ValidationResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leest een screeningbatch, haar mutatielijst en haar regelproblemen als records; de Web-laag ziet
 * nooit JPA-entiteiten ({@code open-in-view} staat uit). De mutatielijst bevat bewust geen binaire
 * hashkolommen: die zijn niet op de entiteit gemapt en dienen enkel voor set-based SQL.
 * <p>
 * Paginering: {@code page} is 0-gebaseerd, {@code size} standaard {@value #DEFAULT_PAGE_SIZE} en
 * begrensd tot {@value #MAX_PAGE_SIZE} (een grotere waarde wordt teruggebracht tot het maximum; het
 * antwoord vermeldt de werkelijk gebruikte grootte). Een negatieve pagina of een grootte kleiner dan 1
 * is een ongeldige aanvraag.
 */
@Service
@Transactional(readOnly = true)
public class BatchQueryService {

    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 200;

    /**
     * De volledige stand van één screeningbatch; tellers zijn {@code null} wanneer ze onbekend zijn.
     * <p>
     * {@code validationResult} is het inhoudelijke eindoordeel naast {@code status} (fase 3,
     * afwijking D) en is {@code null} zolang de screening loopt of bij een technische fout.
     * <p>
     * {@code filteredOutCount} en {@code errorBeforeFilterCount} zijn additief toegevoegd in bouwstap
     * 3b (R-FLT-04). Samen met de bestaande tellers geldt
     * {@code raw = filteredOut + errorBeforeFilter + rejected + valid}. Zonder geconfigureerde
     * recordfilters staan beide op 0; {@code null} betekent zoals altijd "onbekend".
     * <p>
     * {@code bulkIncidentCount} is additief toegevoegd in bouwstap 3g (R-THR-04): het aantal
     * foutgroepen dat als bulkincident aangemerkt is. De groepen zelf staan in
     * {@code GET /batches/{id}/issue-groups}.
     * <p>
     * {@code criticalLineCount} is additief toegevoegd in bouwstap 3h-2 (ontwerp fase 3 par. 15.1): het
     * aantal verworpen bronregels met een fout op een kritieke kolom (ongecapt, ontdubbeld per regel).
     * {@code null} betekent "niet vastgesteld" en is nooit stil 0; er hangt nog geen oordeel aan.
     * <p>
     * {@code creationOutcome} en {@code creationScopeCount} zijn additief toegevoegd in bouwstap 3h-3
     * (ontwerp fase 3 par. 15.2): het oordeel van het creatiebeleid
     * ({@code AUTOMATIC|INITIAL_LOAD|THRESHOLD_EXCEEDED}) en de omvang waartegen geoordeeld is (het
     * aantal actieve aanbiedingen van de koppeling). Beide zijn {@code null} zolang pass E4b niet
     * gedraaid heeft — nooit stil {@code AUTOMATIC} of 0. Een geblokkeerde levering houdt ze leeg:
     * zonder inhoudelijke mutaties valt er geen creatie te beoordelen (bouwstap 3h-5).
     * <p>
     * {@code criticalIssueCount}, {@code warningCount}, {@code awaitingApprovalCount} en
     * {@code creationCandidateCount} zijn additief toegevoegd in bouwstap 3h-5 (ontwerp fase 3
     * par. 15.3). De eerste twee zijn <b>ongecapte</b> aantallen vastgestelde voorvallen (uit de
     * issuegroepen, niet uit de bewaarde voorbeeldrijen); de derde telt de {@code CREATE}/
     * {@code UPDATE}-mutaties die op goedkeuring wachten; de vierde is de teller waarop het
     * creatiebeleid geoordeeld heeft, naast de reeds bestaande noemer {@code creationScopeCount}.
     * Alle vier {@code null} wanneer ze niet vastgesteld zijn, nooit stil 0.
     */
    public record BatchDetail(long batchId, long deliveryId, long importLinkId, long definitionRevisionId,
                              Long taskRunId, int attemptNo, String status, String validationResult,
                              Instant startedAt, Instant finishedAt, long stagedRowCount,
                              long mutationProgressRowNumber,
                              Long rawRecordCount, Long validRecordCount, Long rejectedRecordCount,
                              Long filteredOutCount, Long errorBeforeFilterCount,
                              Long duplicateIdentityCount, Long newCount, Long changedCount,
                              Long unchangedCount, Long identityIncidentCount,
                              Long contentMutationCount, Long bulkIncidentCount, Long criticalLineCount,
                              Long criticalIssueCount, Long warningCount, Long awaitingApprovalCount,
                              String creationOutcome, Long creationScopeCount,
                              Long creationCandidateCount,
                              String blockedCode,
                              String blockedReason, String baselineAcceptedBy, Instant baselineAcceptedAt,
                              String baselineAcceptReason, Instant createdAt, String createdBy) {

        private static BatchDetail of(ImportBatch batch) {
            return new BatchDetail(batch.getId(), batch.getDelivery().getId(), batch.getImportLink().getId(),
                    batch.getDefinitionRevision().getId(),
                    batch.getTaskRun() == null ? null : batch.getTaskRun().getId(), batch.getAttemptNo(),
                    batch.getStatus().name(),
                    batch.getValidationResult() == null ? null : batch.getValidationResult().name(),
                    batch.getStartedAt(), batch.getFinishedAt(),
                    batch.getStagedRowCount(), batch.getMutationProgressRowNumber(), batch.getRawRecordCount(),
                    batch.getValidRecordCount(), batch.getRejectedRecordCount(),
                    batch.getFilteredOutCount(), batch.getErrorBeforeFilterCount(),
                    batch.getDuplicateIdentityCount(), batch.getNewCount(), batch.getChangedCount(),
                    batch.getUnchangedCount(), batch.getIdentityIncidentCount(),
                    batch.getContentMutationCount(), batch.getBulkIncidentCount(),
                    batch.getCriticalLineCount(), batch.getCriticalIssueCount(),
                    batch.getWarningCount(), batch.getAwaitingApprovalCount(),
                    batch.getCreationOutcome() == null ? null : batch.getCreationOutcome().name(),
                    batch.getCreationScopeCount(), batch.getCreationCandidateCount(),
                    batch.getBlockedCode(),
                    batch.getBlockedReason(), batch.getBaselineAcceptedBy(), batch.getBaselineAcceptedAt(),
                    batch.getBaselineAcceptReason(), batch.getCreatedAt(), batch.getCreatedBy());
        }
    }

    /**
     * Eén rij van de werkvoorraadlijst (Scherm 0, D14, bouwstap S0-B1): dezelfde batch als
     * {@link BatchDetail}, maar herleid tot wat een lijstweergave nodig heeft — zonder de
     * {@code *ProgressRowNumber}-hervatpunten, de {@code creation*}-velden en de lange
     * {@code blockedReason}, die exclusief in {@link #getBatch} blijven. Bevat ook koppelinggegevens
     * ({@code importLinkCode}, {@code supplierCode}, {@code libraryCode}) zodat de lijst geen aparte
     * opzoekactie per rij nodig heeft.
     */
    public record BatchRow(long batchId, long deliveryId, long importLinkId, String importLinkCode,
                           String supplierCode, String libraryCode, int attemptNo, String status,
                           String validationResult, Instant createdAt, Instant startedAt, Instant finishedAt,
                           Long rawRecordCount, Long validRecordCount, Long rejectedRecordCount,
                           Long contentMutationCount, Long awaitingApprovalCount, Long criticalLineCount,
                           Long criticalIssueCount, Long warningCount, Long bulkIncidentCount,
                           Long identityIncidentCount, String blockedCode, String baselineAcceptedBy,
                           Instant baselineAcceptedAt) {

        private static BatchRow of(ImportBatch batch) {
            ImportLink link = batch.getImportLink();
            return new BatchRow(batch.getId(), batch.getDelivery().getId(), link.getId(), link.getCode(),
                    link.getSupplierOrganisation().getCode(), link.getLibraryCode(), batch.getAttemptNo(),
                    batch.getStatus().name(),
                    batch.getValidationResult() == null ? null : batch.getValidationResult().name(),
                    batch.getCreatedAt(), batch.getStartedAt(), batch.getFinishedAt(),
                    batch.getRawRecordCount(), batch.getValidRecordCount(), batch.getRejectedRecordCount(),
                    batch.getContentMutationCount(), batch.getAwaitingApprovalCount(),
                    batch.getCriticalLineCount(), batch.getCriticalIssueCount(), batch.getWarningCount(),
                    batch.getBulkIncidentCount(), batch.getIdentityIncidentCount(), batch.getBlockedCode(),
                    batch.getBaselineAcceptedBy(), batch.getBaselineAcceptedAt());
        }
    }

    /** Aantal batches met een bepaalde {@link ImportBatchStatus}. */
    public record StatusCount(ImportBatchStatus status, long count) {
    }

    /**
     * Aantal batches met een bepaald {@link ValidationResult}; {@code validationResult == null} is de
     * eigen, altijd zichtbare groep "niet vastgesteld" (nooit samengevoegd met {@code VALID}, nooit
     * weggelaten wanneer er werkelijk zulke batches zijn).
     */
    public record ValidationCount(ValidationResult validationResult, long count) {
    }

    /**
     * Samenvatting voor Scherm 0 (D14, bouwstap S0-B2): totaal aantal batches en de verdeling over
     * beide statusassen, optioneel beperkt tot één koppeling. {@code total} is de som van
     * {@code byStatus} (elke batch heeft altijd een status), dus geen aparte tellingsquery nodig.
     */
    public record BatchSummary(long total, List<StatusCount> byStatus, List<ValidationCount> byValidationResult) {
    }

    /**
     * Eén regel van de mutatielijst (of de {@code IMPORT_MARKER}, dan zonder identiteit).
     * <p>
     * {@code referenceType}, {@code beforeReferenceValue} en {@code afterReferenceValue} zijn additief
     * toegevoegd in bouwstap 3f en zijn enkel gevuld op een {@code IDENTITY_REFERENCE_INCIDENT}: het
     * soort kritieke koppelreferentie en haar oude en nieuwe genormaliseerde waarde. Voor elke andere
     * mutatiesoort blijven ze {@code null}, precies zoals vóór 3f.
     * <p>
     * {@code batchId}, {@code decidedBy}, {@code decidedAt}, {@code decidedFromStatus} en
     * {@code decisionId} zijn additief toegevoegd in bouwstap 4c (ontwerp fase 4 par. 2, R-DEC).
     * Dezelfde regel wordt nu ook door de mutatielijst van een Publicatiebundel gebruikt — waar
     * mutaties van meerdere batches door elkaar staan en {@code batchId} dus nodig is — zodat er
     * <b>één</b> weergave van een mutatie bestaat en niet twee die uit elkaar kunnen groeien. De vier
     * beslisvelden zijn samen leeg of samen gevuld ({@code ck_import_mutation_decision_fields}) en
     * blijven {@code null} zolang er over deze mutatie niets beslist is. {@code decisionId} verwijst
     * naar de <b>laatste</b> beslissing; het volledige, append-only verloop staat in
     * {@code GET /bundles/{id}/decisions}.
     * <p>
     * {@code identityHash} is additief toegevoegd in bouwstap C4 (ontwerp scherm 3 par. 16.4): de
     * identiteitshash als hexadecimale tekst in kleine letters — de sleutel van de wijzigingsgroep
     * {@code (batch_id, identity_hash)}. {@code null} wanneer de kolom leeg is, wat per definitie zo
     * is voor de {@code IMPORT_MARKER} (die draagt geen identiteit, {@code ck_import_mutation_marker}).
     * De waarde wordt <b>niet</b> door {@link #of(ImportMutation)} gevuld: de kolom is op de entiteit
     * bewust niet gemapt en komt uit één extra, gerichte query per pagina
     * ({@code MutationDao.findIdentityHashes}) via {@link #withIdentityHash(String)}. Dit veld staat
     * bewust achteraan: de bestaande velden en hun volgorde blijven ongewijzigd.
     */
    public record MutationRow(long id, long batchId, String actionType, String targetDomain, String status,
                              String statusReason, String identitySupplier, String identitySupplierGroup,
                              String identitySupplierReference, String identityDiscountCode,
                              String identityDiscountState, String domainMask, BigDecimal beforeBasePrice,
                              BigDecimal afterBasePrice, String basePriceCurrency, String referenceType,
                              String beforeReferenceValue, String afterReferenceValue, Long sourceStateId,
                              Long sourceRowNumber, String resultSummary, String idempotencyKey,
                              Instant createdAt, String decidedBy, Instant decidedAt,
                              String decidedFromStatus, Long decisionId, String identityHash) {

        /**
         * Package-private sinds 4c: {@code BundleQueryService} toont dezelfde regel. {@code identityHash}
         * blijft hier leeg — zie {@link #withIdentityHash(String)}.
         */
        static MutationRow of(ImportMutation mutation) {
            return new MutationRow(mutation.getId(), mutation.getBatch().getId(), mutation.getActionType().name(),
                    mutation.getTargetDomain().name(), mutation.getStatus().name(), mutation.getStatusReason(),
                    mutation.getIdentitySupplier(), mutation.getIdentitySupplierGroup(),
                    mutation.getIdentitySupplierReference(), mutation.getIdentityDiscountCode(),
                    mutation.getIdentityDiscountState() == null ? null : mutation.getIdentityDiscountState().name(),
                    mutation.getDomainMask(), mutation.getBeforeBasePrice(), mutation.getAfterBasePrice(),
                    mutation.getBasePriceCurrency(), mutation.getReferenceType(),
                    mutation.getBeforeReferenceValue(), mutation.getAfterReferenceValue(),
                    mutation.getSourceStateId(), mutation.getSourceRowNumber(),
                    mutation.getResultSummary(), mutation.getIdempotencyKey(), mutation.getCreatedAt(),
                    mutation.getDecidedBy(), mutation.getDecidedAt(),
                    mutation.getDecidedFromStatus() == null ? null : mutation.getDecidedFromStatus().name(),
                    mutation.getDecisionId(), null);
        }

        /**
         * Dezelfde regel met haar identiteitshash erop (bouwstap C4). Alle overige velden ongewijzigd:
         * dit is een aanvulling op wat er al gelezen is, geen herberekening.
         *
         * @param hash hexadecimaal, kleine letters; {@code null} wanneer de kolom leeg is
         */
        MutationRow withIdentityHash(String hash) {
            return new MutationRow(id, batchId, actionType, targetDomain, status, statusReason,
                    identitySupplier, identitySupplierGroup, identitySupplierReference, identityDiscountCode,
                    identityDiscountState, domainMask, beforeBasePrice, afterBasePrice, basePriceCurrency,
                    referenceType, beforeReferenceValue, afterReferenceValue, sourceStateId, sourceRowNumber,
                    resultSummary, idempotencyKey, createdAt, decidedBy, decidedAt, decidedFromStatus,
                    decisionId, hash);
        }

        /**
         * Dezelfde regel met de zopas vastgelegde beslissing erop (bouwstap 4c). Alle overige velden —
         * inclusief {@code beforeBasePrice}, {@code afterBasePrice}, {@code domainMask} en
         * {@code statusReason} — worden ongewijzigd overgenomen: een beslissing verandert er niets
         * aan, dus doet deze weergave dat ook niet.
         * <p>
         * Nodig omdat de beslissing met één gerichte {@code update} geschreven wordt en de
         * JPA-entiteit daarna bewust niet opnieuw gelezen wordt (binnen dezelfde transactie nooit via
         * JPA teruglezen wat via JDBC geschreven is).
         */
        MutationRow withDecision(String newStatus, String newDecidedBy, Instant newDecidedAt,
                                 String newDecidedFromStatus, Long newDecisionId) {
            return new MutationRow(id, batchId, actionType, targetDomain, newStatus, statusReason,
                    identitySupplier, identitySupplierGroup, identitySupplierReference, identityDiscountCode,
                    identityDiscountState, domainMask, beforeBasePrice, afterBasePrice, basePriceCurrency,
                    referenceType, beforeReferenceValue, afterReferenceValue, sourceStateId, sourceRowNumber,
                    resultSummary, idempotencyKey, createdAt, newDecidedBy, newDecidedAt, newDecidedFromStatus,
                    newDecisionId, identityHash);
        }
    }

    /**
     * Eén vastgesteld probleem; {@code sourceValue} is een afgekapt fragment.
     * <p>
     * {@code rowNumber} is sinds fase 3 <b>nullable</b>: een probleem op leverings- of
     * structuurniveau ({@code controlLevel}) hoort bij geen enkele bronregel. {@code severity} kan
     * naast {@code ERROR}/{@code WARNING} ook {@code CRITICAL}, {@code BLOCKING} of {@code INFO}
     * zijn. {@code issueDomain}, {@code controlLevel}, {@code impactScope} en {@code handlingStatus}
     * zijn additief toegevoegd.
     * <p>
     * {@code issueGroupId} is additief toegevoegd in bouwstap 3g: de samenvatting waartoe dit
     * probleem behoort, of {@code null} wanneer het los staat (minder dan tien gelijksoortige
     * vaststellingen, of een probleem dat per definitie hoogstens één keer voorkomt).
     */
    public record IssueRow(long id, Long rowNumber, String issueCode, String fieldName, String severity,
                           String issueDomain, String controlLevel, String impactScope,
                           String handlingStatus, String sourceValue, String expectedValue,
                           String message, Long issueGroupId, Instant createdAt) {

        private static IssueRow of(ImportRowIssue issue) {
            return new IssueRow(issue.getId(), issue.getRowNumber(), issue.getIssueCode(), issue.getFieldName(),
                    issue.getSeverity().name(), issue.getIssueDomain().name(), issue.getControlLevel().name(),
                    issue.getImpactScope().name(), issue.getHandlingStatus().name(), issue.getSourceValue(),
                    issue.getExpectedValue(), issue.getMessage(), issue.getIssueGroupId(),
                    issue.getCreatedAt());
        }
    }

    /**
     * Eén samenvatting van gelijksoortige problemen binnen deze batch (bouwstap 3g, R-THR-04).
     * <p>
     * <b>{@code occurrenceCount} is het werkelijke aantal</b>, niet het aantal bewaarde
     * voorbeeldrijen: dat laatste staat in {@code recordedSampleCount}. Bij een miljoen identieke
     * fouten worden er hoogstens {@code max-sample-rows-per-code} voorbeelden bewaard, maar het
     * totaal blijft hier staan.
     * <p>
     * {@code sharePercent} is het aandeel in {@code scopeRecordCount}, de hoeveelheid die voor dit
     * soort incident werkelijk gecontroleerd is. Beide zijn {@code null} wanneer die scope onbekend
     * is; er wordt nooit een noemer geraden. {@code dominantFactor} en {@code patternDescription}
     * blijven in fase 3 leeg: patroonherkenning van bulktransformaties is bewust uitgesteld.
     */
    public record IssueGroupRow(long id, String issueCode, String signature, String severity,
                                String issueDomain, String controlLevel, String impactScope,
                                String incidentKind, long occurrenceCount, int recordedSampleCount,
                                Long scopeRecordCount, BigDecimal sharePercent, boolean bulkIncident,
                                String priceComponentCode, String deviationDirection,
                                BigDecimal dominantFactor, String referenceType,
                                String patternDescription, Long firstRowNumber,
                                Instant firstDetectedAt, Instant lastDetectedAt,
                                String handlingStatus) {

        private static IssueGroupRow of(GroupRow group) {
            return new IssueGroupRow(group.id(), group.issueCode(), group.signature(), group.severity(),
                    group.issueDomain(), group.controlLevel(), group.impactScope(),
                    group.incidentKind(), group.occurrenceCount(), group.recordedSampleCount(),
                    group.scopeRecordCount(), group.sharePercent(), group.bulkIncident(),
                    group.priceComponentCode(), group.deviationDirection(), group.dominantFactor(),
                    group.referenceType(), group.patternDescription(), group.firstRowNumber(),
                    group.firstDetectedAt(), group.lastDetectedAt(), group.handlingStatus());
        }
    }

    private final ImportBatchRepository batches;
    private final ImportMutationRepository mutations;
    private final ImportRowIssueRepository issues;
    private final IssueGroupDao issueGroups;
    /** Enkel voor de niet-gemapte {@code identity_hash} van een opgehaalde pagina (bouwstap C4). */
    private final MutationDao mutationHashes;

    public BatchQueryService(ImportBatchRepository batches, ImportMutationRepository mutations,
                             ImportRowIssueRepository issues, IssueGroupDao issueGroups,
                             MutationDao mutationHashes) {
        this.batches = batches;
        this.mutations = mutations;
        this.issues = issues;
        this.issueGroups = issueGroups;
        this.mutationHashes = mutationHashes;
    }

    /** @throws NotFoundException onbekende batch ({@code BATCH_NOT_FOUND}) */
    public BatchDetail getBatch(long batchId) {
        return BatchDetail.of(batches.findById(batchId).orElseThrow(() -> notFound(batchId)));
    }

    /**
     * De werkvoorraadlijst (Scherm 0, D14, bouwstap S0-B1): alle batches, optioneel gefilterd op
     * {@code status}, {@code validationResult}, {@code importLinkId} en het halfopen
     * {@code [createdFrom, createdTo)}-interval op {@code createdAt}. Vaste sortering {@code id desc}
     * (nieuwste eerst) — nooit onbepaald, want dat geeft op Postgres instabiele paginering.
     *
     * @throws IllegalArgumentException ongeldige paginering
     */
    public PageResult<BatchRow> listBatches(ImportBatchStatus status, ValidationResult validationResult,
                                            Long importLinkId, Instant createdFrom, Instant createdTo,
                                            Integer page, Integer size) {
        PageRequest pageRequest = pageRequest(page, size);
        Page<ImportBatch> result = batches.findBatchRows(status, validationResult, importLinkId, createdFrom,
                createdTo, pageRequest);
        return PageResult.of(result, BatchRow::of);
    }

    /**
     * De werkvoorraadsamenvatting (Scherm 0, D14, bouwstap S0-B2): totaal en de verdeling over status
     * en eindoordeel, optioneel beperkt tot één koppeling. Twee {@code group by}-query's, geen losse
     * telling per waarde.
     */
    public BatchSummary getSummary(Long importLinkId) {
        List<StatusCount> byStatus = batches.countByStatusGrouped(importLinkId).stream()
                .map(row -> new StatusCount((ImportBatchStatus) row[0], (Long) row[1]))
                .toList();
        long total = byStatus.stream().mapToLong(StatusCount::count).sum();
        List<ValidationCount> byValidationResult = batches.countByValidationResultGrouped(importLinkId).stream()
                .map(row -> new ValidationCount((ValidationResult) row[0], (Long) row[1]))
                .toList();
        return new BatchSummary(total, byStatus, byValidationResult);
    }

    /**
     * De mutatielijst van een batch, oplopend op id (= volgorde van aanmaak), optioneel gefilterd op
     * {@code status}, {@code statusReason} (exact; blanco = geen filter), {@code actionType} en
     * {@code identityHash}.
     *
     * @param identityHash enkel de mutaties met deze identiteitshash — de wijzigingsgroep van één
     *                     aanbieding (bouwstap C4). Hexadecimaal, hoofdletterongevoelig; {@code null}
     *                     of blanco = geen filter. Een onbekende of ongeldige waarde levert een lege
     *                     pagina op en geen fout: de gebruiker heeft dan gewoon niets gevonden
     * @throws NotFoundException        onbekende batch
     * @throws IllegalArgumentException ongeldige paginering
     */
    public PageResult<MutationRow> getMutations(long batchId, MutationStatus status, String statusReason,
                                                MutationActionType actionType, String identityHash,
                                                Integer page, Integer size) {
        requireBatch(batchId);
        String reason = statusReason == null || statusReason.isBlank() ? null : statusReason;
        if (isIdentityHashFilter(identityHash)) {
            // Ongesorteerde Pageable: de native variant draagt haar eigen "order by m.id".
            PageRequest nativeRequest = pageRequest(page, size);
            byte[] hash = parseIdentityHash(identityHash);
            if (hash == null) {
                return emptyMutationPage(nativeRequest);
            }
            return mutationRows(mutations.findBatchMutationsByIdentityHash(batchId, name(status),
                    name(actionType), reason, hash, nativeRequest), mutationHashes);
        }
        PageRequest pageRequest = pageRequest(page, size, Sort.by("id"));
        Page<ImportMutation> result = mutations.findBatchMutations(batchId, status, actionType, reason,
                pageRequest);
        return mutationRows(result, mutationHashes);
    }

    // --- Identiteitshash op de mutatielijst (bouwstap C4) -----------------------------------------
    //
    // Gedeeld met BundleQueryService, dat dezelfde MutationRow toont: één plaats waar de hash aan een
    // pagina gehangen wordt en één plaats waar een hexinvoer ontleed wordt, zodat beide lijsten
    // onmogelijk uit elkaar kunnen lopen.

    /** {@code true} zodra er werkelijk op een hash gefilterd wordt; blanco is geen filter. */
    static boolean isIdentityHashFilter(String identityHash) {
        return identityHash != null && !identityHash.isBlank();
    }

    /**
     * De hexinvoer als bytes, of {@code null} wanneer het geen geldige hex is (oneven lengte of een
     * teken buiten {@code 0-9a-fA-F}). Bewust geen {@code IllegalArgumentException}: een gebruiker die
     * een half gekopieerde hash plakt, hoort een lege lijst te zien en geen foutmelding — precies zoals
     * een onbekende maar geldige hash. {@link HexFormat} aanvaardt hoofd- én kleine letters.
     */
    static byte[] parseIdentityHash(String identityHash) {
        try {
            return HexFormat.of().parseHex(identityHash.trim());
        } catch (IllegalArgumentException notHex) {
            return null;
        }
    }

    static PageResult<MutationRow> emptyMutationPage(PageRequest pageRequest) {
        return new PageResult<>(List.of(), pageRequest.getPageNumber(), pageRequest.getPageSize(), 0L, 0);
    }

    /**
     * Zet een pagina entiteiten om in regels en hangt er in <b>één</b> extra query de identiteitshashes
     * aan (bouwstap C4). Geen query per rij, en de kolom blijft ongemapt op {@link ImportMutation}.
     */
    static PageResult<MutationRow> mutationRows(Page<ImportMutation> page, MutationDao hashes) {
        PageResult<MutationRow> rows = PageResult.of(page, MutationRow::of);
        if (rows.content().isEmpty()) {
            return rows;
        }
        Map<Long, String> byId = hashes.findIdentityHashes(rows.content().stream().map(MutationRow::id).toList());
        return new PageResult<>(rows.content().stream().map(row -> row.withIdentityHash(byId.get(row.id()))).toList(),
                rows.page(), rows.size(), rows.totalElements(), rows.totalPages());
    }

    /** Enums gaan als tekst naar een native query: daar is geen enum-mapping beschikbaar. */
    static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    /**
     * De regelproblemen van een batch, oplopend op bronregelnummer, optioneel beperkt tot één
     * foutgroep.
     *
     * @param issueGroupId enkel de voorbeeldrijen van deze groep; {@code null} voor alle problemen.
     *                     Een onbekende groep levert een lege pagina op en geen fout: de groep kan
     *                     bestaan hebben en bij een hertelling onder de groeperingsdrempel gevallen
     *                     zijn
     * @throws NotFoundException        onbekende batch
     * @throws IllegalArgumentException ongeldige paginering
     */
    public PageResult<IssueRow> getIssues(long batchId, Long issueGroupId, Integer page, Integer size) {
        requireBatch(batchId);
        PageRequest pageRequest = pageRequest(page, size, Sort.by("rowNumber", "id"));
        return PageResult.of(issueGroupId == null
                        ? issues.findByBatchId(batchId, pageRequest)
                        : issues.findByBatchIdAndIssueGroupId(batchId, issueGroupId, pageRequest),
                IssueRow::of);
    }

    /**
     * De foutgroepen van een batch, oplopend op id (= volgorde waarin ze ontstonden).
     * <p>
     * Deze lijst is bewust de plek waar de <b>werkelijke</b> aantallen staan: de probleemlijst zelf
     * bevat per foutcode hoogstens een beperkt aantal voorbeeldrijen (R-ISS-03), dus een telling
     * daarover zou systematisch te laag uitkomen.
     *
     * @throws NotFoundException        onbekende batch
     * @throws IllegalArgumentException ongeldige paginering
     */
    public PageResult<IssueGroupRow> getIssueGroups(long batchId, Integer page, Integer size) {
        requireBatch(batchId);
        PageRequest pageRequest = pageRequest(page, size, Sort.by("id"));
        long total = issueGroups.countByBatchId(batchId);
        List<IssueGroupRow> content = issueGroups
                .findPage(batchId, (int) pageRequest.getOffset(), pageRequest.getPageSize()).stream()
                .map(IssueGroupRow::of).toList();
        int totalPages = (int) ((total + pageRequest.getPageSize() - 1) / pageRequest.getPageSize());
        return new PageResult<>(content, pageRequest.getPageNumber(), pageRequest.getPageSize(), total,
                totalPages);
    }

    private void requireBatch(long batchId) {
        if (!batches.existsById(batchId)) {
            throw notFound(batchId);
        }
    }

    private static NotFoundException notFound(long batchId) {
        return new NotFoundException("BATCH_NOT_FOUND", "Batch " + batchId + " not found");
    }

    /** Voor query's die hun sortering al vast in de {@code order by} van de {@code @Query} hebben. */
    private static PageRequest pageRequest(Integer page, Integer size) {
        return pageRequest(page, size, Sort.unsorted());
    }

    private static PageRequest pageRequest(Integer page, Integer size, Sort sort) {
        int number = page == null ? 0 : page;
        int requested = size == null ? DEFAULT_PAGE_SIZE : size;
        if (number < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (requested < 1) {
            throw new IllegalArgumentException("size must be at least 1");
        }
        return PageRequest.of(number, Math.min(requested, MAX_PAGE_SIZE), sort);
    }
}
