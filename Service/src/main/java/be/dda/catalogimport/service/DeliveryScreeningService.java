package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.CandidatePriceDao;
import be.dda.catalogimport.dao.CandidatePriceDao.PriceRow;
import be.dda.catalogimport.dao.CandidateReferenceDao;
import be.dda.catalogimport.dao.CandidateReferenceDao.ReferenceRow;
import be.dda.catalogimport.dao.CandidateStageDao;
import be.dda.catalogimport.dao.CandidateStageDao.DuplicateRow;
import be.dda.catalogimport.dao.CandidateStageDao.StageRow;
import be.dda.catalogimport.dao.DeliveryFileRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.IssueGroupDao;
import be.dda.catalogimport.dao.MutationDao;
import be.dda.catalogimport.dao.MutationDao.MutationContext;
import be.dda.catalogimport.dao.PriceDeviationDao;
import be.dda.catalogimport.dao.PriceDeviationDao.DeviationRow;
import be.dda.catalogimport.dao.PriceDeviationDao.MissingReferenceCounts;
import be.dda.catalogimport.dao.ReferenceControlDao;
import be.dda.catalogimport.dao.ReferenceControlDao.DuplicateReferenceRow;
import be.dda.catalogimport.dao.ReferenceControlDao.IncidentMutation;
import be.dda.catalogimport.dao.ReferenceControlDao.MatchUpdate;
import be.dda.catalogimport.dao.ReferenceControlDao.ReferenceCandidate;
import be.dda.catalogimport.dao.RowIssueDao;
import be.dda.catalogimport.dao.RowIssueDao.IssueRow;
import be.dda.catalogimport.dao.SourceStateDao;
import be.dda.catalogimport.dao.TaskRunRepository;
import be.dda.catalogimport.domain.CandidateClassification;
import be.dda.catalogimport.domain.CreationOutcome;
import be.dda.catalogimport.domain.Delivery;
import be.dda.catalogimport.domain.DeliveryFile;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.IssueIncidentKind;
import be.dda.catalogimport.domain.RowIssueSeverity;
import be.dda.catalogimport.domain.TaskRun;
import be.dda.catalogimport.domain.TaskRunStatus;
import be.dda.catalogimport.domain.ValidationResult;
import be.dda.catalogimport.service.support.CandidateNormaliser;
import be.dda.catalogimport.service.support.CandidateNormaliser.NormalisedCandidate;
import be.dda.catalogimport.service.support.CreationPolicyEvaluator;
import be.dda.catalogimport.service.support.CreationPolicyEvaluator.Decision;
import be.dda.catalogimport.service.support.CriticalLineCounter;
import be.dda.catalogimport.service.support.CsvRecordStreamer;
import be.dda.catalogimport.service.support.CsvRecordStreamer.LineIssue;
import be.dda.catalogimport.service.support.CsvRecordStreamer.ParsedRow;
import be.dda.catalogimport.service.support.CsvRecordStreamer.ReadSummary;
import be.dda.catalogimport.service.support.ImportIssueCatalog;
import be.dda.catalogimport.service.support.ImportMappingConfig;
import be.dda.catalogimport.service.support.ImportMappingConfigFactory;
import be.dda.catalogimport.service.support.IssueSignature;
import be.dda.catalogimport.service.support.IssueTally;
import be.dda.catalogimport.service.support.PriceDeviationEvaluator;
import be.dda.catalogimport.service.support.PriceDeviationEvaluator.Reference;
import be.dda.catalogimport.service.support.PriceDeviationEvaluator.ReferenceKind;
import be.dda.catalogimport.service.support.PriceRules;
import be.dda.catalogimport.service.support.RecordFilterEvaluator;
import be.dda.catalogimport.service.support.ReferenceControlEvaluator;
import be.dda.catalogimport.service.support.ReferenceControlEvaluator.RecordOutcome;
import be.dda.catalogimport.service.support.ReferenceControlEvaluator.ReferenceOutcome;
import be.dda.catalogimport.service.support.ScreeningBlockedException;
import be.dda.catalogimport.service.support.SourceStructureConfig;
import be.dda.catalogimport.service.support.SourceStructureConfigFactory;
import be.dda.catalogimport.service.support.ThresholdEvaluator;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Screent één {@link ImportBatch} van begin tot eind: het gearchiveerde bronbestand lezen en stagen
 * (design par. 9 stap C), dubbele identiteiten en hashcollisies vaststellen (stap D), de delta tegen
 * de bronstaat bepalen en de mutatielijst genereren (stap E) en de screening afronden met tellers,
 * {@code IMPORT_MARKER} en eindstatus (stap F).
 * <p>
 * <b>Businessregels die hier hard zijn.</b>
 * <ul>
 *   <li>Een inhoudelijk ongeldige regel verwerpt <b>enkel die regel</b> (aanname A4) en levert nooit
 *       een prijs 0 op; de batch eindigt dan gewoon op {@code SCREENED} met
 *       {@code rejected_record_count > 0}.</li>
 *   <li>Een contract- of structuurfout (leeg bestand, header-only, ontbrekend headerveld,
 *       kolomaantal, aantalsmismatch, dubbele identiteit, hashcollisie) blokkeert de <b>volledige</b>
 *       levering: {@code BLOCKED}, nul inhoudelijke mutaties, wel één marker met
 *       {@code outcome=BLOCKED}. Elke blokkade laat sinds fase 3 ook één issuerij achter op niveau
 *       {@code STRUCTURE} of {@code DELIVERY} — de blokkeerreden op de batch blijft daarnaast
 *       ongewijzigd bestaan.</li>
 *   <li><b>Veel identieke regelfouten blokkeren niet.</b> Per foutcode worden hoogstens
 *       {@code catalogimport.screening.max-sample-rows-per-code} voorbeeldrijen bewaard (de laagste
 *       regelnummers, want er wordt in leesvolgorde gestreamd); de volledige aantallen per code
 *       blijven bewaard in {@code import_issue_group} en in één
 *       {@code ROW_ISSUE_RECORDING_CAPPED}-melding per foutcode. Dat vervangt de
 *       fase 2-blokkade {@code TOO_MANY_ROW_ISSUES}, die een technische logginglimiet was en geen
 *       businessoordeel (ontwerp fase 3, afwijking C).</li>
 *   <li><b>Gelijksoortige vaststellingen worden samengevat</b> (fase 3g, pass E4, R-THR-04). Vanaf
 *       tien gelijke foutsignaturen ontstaat er één {@code import_issue_group} met het
 *       <b>werkelijke</b> aantal — niet het aantal bewaarde voorbeeldrijen. Overschrijdt dat aantal
 *       het percentage {@code bulk_incident_share_percent} van de gecontroleerde scope (default 1%,
 *       beslissingslog 20/09: elke drempel is altijd een percentage, nooit een vast aantal), dan is
 *       het een <b>bulkincident</b>: één {@code BULK_PRICE_INCIDENT} (R-PRI-14) of één
 *       {@code BULK_IDENTITY_INCIDENT} (R-REF-07) <i>naast</i> de individuele meldingen, die
 *       onverkort blijven bestaan. Een gewone foutgroep krijgt geen extra foutcode, enkel
 *       {@code is_bulk_incident} op de groep.</li>
 *   <li><b>Tussenstand van bouwstap 3g.</b> {@code validation_result} wordt hier nog steeds uit de
 *       ernst van de issuerijen afgeleid (3a-logica). Een {@code BULK_PRICE_INCIDENT} (BLOCKING) of
 *       {@code BULK_IDENTITY_INCIDENT} (CRITICAL) zet het eindoordeel daardoor op
 *       {@code BLOCKING}, terwijl ontwerp par. 3.6/15.3 daarvoor {@code REVIEW_REQUIRED}
 *       voorschrijft. Dat is een bewuste tussenstand: de herziening van {@code validation_result}
 *       (en van de mutatiestatussen) hoort bij bouwstap 3h en wordt hier niet geraden. De
 *       batchstatus zelf verandert niet: een bulkincident blokkeert de verwerking niet.</li>
 *   <li><b>Recordfilters bepalen de importscope</b> (fase 3, R-FLT-01..R-FLT-04). Ze draaien
 *       onmiddellijk na het parsen en vóór identiteit, prijs en referenties; een record dat buiten de
 *       scope valt krijgt geen enkele verdere controle en telt in {@code filtered_out_count} — dat is
 *       geen fout. Een record dat al vóór het filter onleesbaar was, kan niet meer aan de scope
 *       toegewezen worden en telt in {@code error_before_filter_count}, niet in
 *       {@code rejected_record_count}. Het bronbestand wordt altijd <b>volledig</b> gelezen: header,
 *       structuur, parsefouten en het ruwe recordaantal gelden over 100% van de levering. Zonder
 *       geconfigureerde filters staan beide tellers op 0 en blijft het gedrag exact dat van fase 2.</li>
 *   <li>Een dubbele aanbiedingsidentiteit binnen één levering is nooit "laatste wint": élke
 *       betrokken regel krijgt een probleem en de levering blokkeert.</li>
 *   <li><b>Een gewijzigde prijs wordt met drie referenties vergeleken</b> (fase 3, R-PRI-10..R-PRI-12):
 *       de laatste aanvaarde waarde en de gemiddelden van de laatste 50 en 200 goedgekeurde
 *       dagwaarden uit {@code catalog_price_observation}. Een overschrijding van de ingestelde grens
 *       (default 15%) levert een melding op en <b>wijzigt nooit een bedrag, een percentage of een
 *       mutatie</b>; de mutatie blijft {@code PLANNED}. Deze controle is een eigen, afzonderlijk
 *       hervatbare pass met een eigen voortgangskolom, tussen de identiteitscontrole en de
 *       mutatiegeneratie.</li>
 *   <li><b>Een kritieke koppelreferentie wordt nooit stil gewijzigd</b> (fase 3f, R-REF-02..R-REF-09).
 *       Een EAN, PIM-ID, CAB-ID of {@code E_MARK+ARTICLE_REFERENCE} die verschilt van de actieve
 *       waarde, die leeg geleverd wordt terwijl er een actieve waarde is, die bij een andere
 *       aanbieding actief staat, of die naar meer dan één bestaande aanbieding wijst, wordt
 *       <b>nooit</b> een gewone {@code UPDATE}. Het record wordt vastgehouden (classificatie
 *       {@code IDENTITY_INCIDENT}), zijn inhoudelijke mutatie krijgt status {@code BLOCKED} en er komt
 *       een aparte {@code IDENTITY_REFERENCE_INCIDENT}-mutatie die op goedkeuring wacht. Dezelfde
 *       referentiewaarde bij twee aanbiedingen binnen één levering houdt <b>beide</b> regels vast —
 *       nooit "laatste wint".</li>
 *   <li><b>Een levering stopt pas wanneer ze als geheel onbetrouwbaar is</b> (bouwstap 3h-4, pass
 *       E4b, ontwerp par. 15.2/15.3). Twee drempels per revisie, allebei een <b>percentage</b> van de
 *       records in scope ({@code raw_record_count − filtered_out_count}) en nooit een vast aantal
 *       (beslissingslog 20/09): {@code max_critical_share_percent} (default 1%) op de records die
 *       beoordeling vragen — kritieke lijnen plus vastgehouden identiteitsincidenten — en
 *       {@code max_rejected_share_percent} (standaard niet geconfigureerd, dus nooit overschreden) op
 *       de verworpen regels. <b>Binnen</b> de drempel verandert er niets: de levering gaat door en
 *       vraagt een review. <b>Boven</b> de drempel eindigt ze op {@code BLOCKED} met
 *       {@code CRITICAL_RECORD_THRESHOLD_EXCEEDED} respectievelijk
 *       {@code REJECTED_RECORD_THRESHOLD_EXCEEDED}, zonder één inhoudelijke mutatie, met precies één
 *       marker. De reeds geschreven {@code IDENTITY_REFERENCE_INCIDENT}-mutaties (E2) blijven staan
 *       als bewijs. Exact op de grens is niet overschreden; de absolute kolommen
 *       {@code max_critical_records}/{@code max_rejected_records} worden niet meer gelezen.</li>
 *   <li><b>Nieuwe aanbiedingen worden niet zomaar aangemaakt</b> (bouwstap 3h-3, pass E4b, ontwerp
 *       par. 15.2, R-THR-01). Vóór de mutatiegeneratie wordt één vraag beantwoord en vastgelegd op
 *       {@code import_batch.creation_outcome}: mag deze levering zelf creëren? Een koppeling zonder
 *       enkele actieve aanbieding is een <b>initialisatie</b> ({@code INITIAL_LOAD}); ligt het aantal
 *       creaties boven {@code creation_threshold_share_percent} van de bestaande omvang, dan is het
 *       een <b>bulkcreatie</b> ({@code THRESHOLD_EXCEEDED}); anders {@code AUTOMATIC} en verandert er
 *       niets. In de eerste twee gevallen krijgt élke {@code CREATE} status
 *       {@code AWAITING_APPROVAL} met reden {@code INITIAL_LOAD_REQUIRES_APPROVAL} respectievelijk
 *       {@code BULK_CREATION_INCIDENT}; een {@code UPDATE} blijft {@code PLANNED}. Precedentie:
 *       {@code BLOCKED} (identiteitsincident) wint altijd, dan {@code AWAITING_APPROVAL}, dan
 *       {@code PLANNED}. De drempel is altijd een percentage en nooit een vast aantal
 *       (beslissingslog 20/09); exact op de grens is niet overschreden.</li>
 *   <li><b>Tussenstand van bouwstap 3h-3.</b> {@code INITIAL_LOAD_REQUIRES_APPROVAL} en
 *       {@code BULK_CREATION_INCIDENT} zijn voorlopig van ernst {@code BLOCKING}, waardoor de
 *       3a-logica {@code validation_result = BLOCKING} oplevert terwijl ontwerp par. 15.3 daarvoor
 *       {@code REVIEW_REQUIRED} voorschrijft. Dat is een <b>bewuste</b> tussenstand: het eindoordeel
 *       wordt in bouwstap 3h-5 uit {@code DeliveryEffect} afgeleid in plaats van uit de ernst. De
 *       batchstatus zelf verandert niet — een wachtende creatie blokkeert de verwerking niet en de
 *       levering eindigt gewoon op {@code SCREENED} met haar volledige mutatielijst. Gevolg dat u
 *       nu al ziet: de <b>eerste</b> levering van een koppeling levert N {@code CREATE}-mutaties in
 *       {@code AWAITING_APPROVAL} op in plaats van in {@code PLANNED}. {@code accept-baseline}
 *       aanvaardt die mutaties wel (par. 15.4): die actie ís de goedkeuring.</li>
 *   <li><b>Kritieke lijnen worden geteld, nog niet beoordeeld</b> (bouwstap 3h-2, ontwerp par. 15.1).
 *       Een verworpen bronregel met een ERROR op een kritieke kolom (of een niet aan een kolom
 *       toewijsbare ERROR) telt in {@code import_batch.critical_line_count}, ontdubbeld per regel en
 *       niet gecapt door de voorbeeldcap; zie {@link CriticalLineCounter}. De teller wordt samen met
 *       {@code rejected_record_count} vastgelegd en beïnvloedt nog geen drempel, oordeel of
 *       mutatiestatus.</li>
 *   <li>De screening schrijft <b>nooit</b> in {@code catalog_source_state}. Een ongewijzigde regel
 *       raakt de bronstaat niet aan en levert geen mutatie op. Het bijwerken van de bronstaat is een
 *       aparte, geauditeerde actie (beslissingslog 18/09, accept-baseline).</li>
 *   <li>Een technische fout tijdens het stagen levert {@code FAILED} op: geen marker, geen mutaties,
 *       staging en problemen van die poging opgeruimd.</li>
 * </ul>
 * <b>Transactiegrenzen.</b> Deze orchestrator is bewust <b>niet</b> {@code @Transactional}: één
 * transactie over een miljoen regels zou het transactielog en het geheugen laten vollopen en na een
 * crash alles verliezen. In plaats daarvan: één korte transactie voor de overgang naar
 * {@code SCREENING}, één transactie per microbatch tijdens het stagen
 * ({@code catalogimport.screening.stage-batch-size}), één transactie per chunk tijdens de
 * mutatiegeneratie ({@code catalogimport.screening.mutation-chunk-size}, die ook
 * {@code mutation_progress_row_number} bijwerkt) en één afrondende transactie die tellers, marker,
 * eindstatus en {@code TaskRun} samen vastlegt. Er wordt nooit in dezelfde transactie via JPA
 * teruggelezen wat via JdbcTemplate geschreven is.
 * <p>
 * <b>Hervatten.</b> Breekt de mutatiegeneratie halverwege af, dan blijft de batch op
 * {@code MUTATING} staan — met haar staging, haar reeds geschreven mutaties en haar hervatpunt.
 * {@link #continueMutating(long)} pakt die batch opnieuw op. Dubbele mutaties zijn onmogelijk: elke
 * insert slaat bestaande idempotentiesleutels over en {@code uk_import_mutation_idempotency} is de
 * harde garantie. Dat is bewust géén {@code FAILED}: het werk dat al gedaan is (mogelijk honderden
 * chunks) mag niet weggegooid worden, en design par. 9 merkt {@code MUTATING} expliciet als
 * hervatbaar aan. De HTTP-ingang ({@code POST /batches/{id}/continue}) en de opstartrecovery
 * ({@link ScreeningRecoveryService}) zijn bouwstap 2e.
 */
@Service
public class DeliveryScreeningService {

    /** Het verwachte byte-aantal uit het manifest klopt niet met het ontvangen bestand. */
    public static final String CODE_BYTE_SIZE_MISMATCH = ImportIssueCatalog.BYTE_SIZE_MISMATCH;
    /** Het verwachte recordaantal uit het manifest klopt niet met het gelezen bestand. */
    public static final String CODE_RECORD_COUNT_MISMATCH = ImportIssueCatalog.RECORD_COUNT_MISMATCH;
    /** Het bestand bevat een header maar geen enkele datalijn (aanname A3). */
    public static final String CODE_SOURCE_NO_DATA_RECORDS = ImportIssueCatalog.SOURCE_NO_DATA_RECORDS;
    /** Dezelfde aanbiedingsidentiteit komt meermaals voor in één levering; nooit "laatste wint". */
    public static final String CODE_DUPLICATE_IDENTITY_IN_DELIVERY =
            ImportIssueCatalog.DUPLICATE_IDENTITY_IN_DELIVERY;
    /** Zelfde identiteitshash, andere sleutelcomponenten: de identiteit is niet betrouwbaar. */
    public static final String CODE_IDENTITY_HASH_COLLISION = ImportIssueCatalog.IDENTITY_HASH_COLLISION;
    /** Technische fout tijdens de screening; batch en run eindigen op FAILED. */
    public static final String CODE_SCREENING_FAILED = ImportIssueCatalog.SCREENING_FAILED;
    /**
     * Er zijn meer voorvallen van een foutcode dan er voorbeeldrijen bewaard worden. Informatief:
     * dit blokkeert de levering <b>niet</b> (ontwerp fase 3, afwijking C).
     */
    public static final String CODE_ROW_ISSUE_RECORDING_CAPPED =
            ImportIssueCatalog.ROW_ISSUE_RECORDING_CAPPED;
    /** Een kritieke koppelreferentie is gewijzigd, verwijderd, hergebruikt of dubbelzinnig geworden. */
    public static final String CODE_IDENTITY_REFERENCE_INCIDENT =
            ImportIssueCatalog.IDENTITY_REFERENCE_INCIDENT;
    /** Dezelfde referentiewaarde staat in deze levering bij twee verschillende aanbiedingen. */
    public static final String CODE_DUPLICATE_REFERENCE_IN_DELIVERY =
            ImportIssueCatalog.DUPLICATE_REFERENCE_IN_DELIVERY;
    /** Matchingstap 2: deze nieuwe aanbieding hoort bij hetzelfde artikel als een bestaande. */
    public static final String CODE_REFERENCE_LINK_PROPOSED = ImportIssueCatalog.REFERENCE_LINK_PROPOSED;
    /** Deze levering is onder deze revisie al gescreend; een tweede screening is een conflict. */
    public static final String CODE_ALREADY_SCREENED = "DELIVERY_ALREADY_SCREENED_WITH_THIS_REVISION";
    /** Alleen een batch in {@code MUTATING} kan hervat worden. */
    public static final String CODE_BATCH_NOT_RESUMABLE = "BATCH_NOT_RESUMABLE";

    /** Standaardaantal bewaarde voorbeeldrijen per foutcode (ontwerp fase 3, R-ISS-03). */
    public static final int DEFAULT_MAX_SAMPLE_ROWS_PER_CODE = 200;

    /**
     * Het soort referentie-incident in de signatuur van een dubbele referentiewaarde binnen één
     * levering (D1). Geen {@code ReferenceMatchResult}: D1 stelt geen match vast, ze stelt vast dat
     * er niets vast te stellen valt.
     */
    private static final String DUPLICATE_INCIDENT_KIND = "DUPLICATE";

    /** Fase 2 kent geen volledigheidscontract; {@code completeness_proven} is altijd false (A6). */
    public static final String COMPLETENESS_REASON = "PHASE2_NO_COMPLETENESS_CONTRACT";

    /** {@code import_batch.blocked_code} is varchar(60), {@code blocked_reason} varchar(500). */
    private static final int MAX_BLOCKED_CODE_LENGTH = 60;
    private static final int MAX_BLOCKED_REASON_LENGTH = 500;

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryScreeningService.class);

    /**
     * Wat de screening van deze batch heeft opgeleverd; tellers zijn {@code null} als ze onbekend
     * zijn — nooit stil {@code 0}. Een geblokkeerde batch die de delta nooit bereikt heeft, heeft
     * dus geen {@code newCount}, maar wél {@code contentMutationCount = 0}: dát is wél zeker.
     */
    public record ScreeningOutcome(long batchId, ImportBatchStatus status, ValidationResult validationResult,
                                   Long rawRecordCount, Long validRecordCount, Long rejectedRecordCount,
                                   Long filteredOutCount, Long errorBeforeFilterCount,
                                   long stagedRowCount, Long duplicateIdentityCount, Long newCount,
                                   Long changedCount, Long unchangedCount, Long identityIncidentCount,
                                   Long criticalLineCount, CreationOutcome creationOutcome,
                                   Long creationScopeCount,
                                   Long contentMutationCount, String blockedCode, String blockedReason) {
    }

    /**
     * Het prijsbeleid van de revisie, één keer per batch gelezen (R-PRI-10). Het model zelf staat er
     * niet in: een revisie met een ander model dan {@code DEVIATION} wordt al bij het laden van de
     * configuratie geweigerd ({@code CONFIG_PRICE_CONTROL_MODEL_UNSUPPORTED}), dus alles wat hier
     * aankomt hoort bij model 1.
     *
     * @param deviationPercent de gezamenlijke grens per revisie; default 15
     * @param severity         de ernst van een overschrijding; default {@code WARNING}, per revisie
     *                         {@code ERROR}
     */
    private record PriceControl(BigDecimal deviationPercent, RowIssueSeverity severity,
                                int shortWindow, int longWindow) {
    }

    /** Alles wat buiten een transactie nodig is; bewust geen JPA-entiteiten (open-in-view staat uit). */
    private record Context(long batchId, long deliveryId, long deliveryFileId, long importLinkId,
                           String libraryCode, long definitionRevisionId, Long taskRunId,
                           String archiveReference,
                           long fileByteSize, String fileSha256, Long expectedRecordCount,
                           Long expectedByteSize, SourceStructureConfig config,
                           ImportMappingConfig mappingConfig, PriceControl priceControl,
                           BigDecimal bulkIncidentSharePercent,
                           BigDecimal creationThresholdSharePercent,
                           BigDecimal maxCriticalSharePercent,
                           BigDecimal maxRejectedSharePercent,
                           ScreeningBlockedException configFailure) {

        private MutationContext mutationContext() {
            return new MutationContext(batchId, deliveryId, importLinkId, definitionRevisionId, taskRunId,
                    deliveryFileId);
        }

        /** Zonder geconfigureerde recordfilters blijft het gedrag exact dat van fase 2. */
        private boolean hasRecordFilters() {
            return mappingConfig != null && mappingConfig.hasFilters();
        }
    }

    /**
     * Een vastgestelde reden om de volledige levering te blokkeren, met wat eromheen bekend is.
     * {@code rowNumber} is gevuld wanneer de blokkade aan één regel op te hangen is (hashcollisie);
     * bij een leverings- of structuurfout blijft die bewust {@code null} in plaats van 0.
     */
    private record Blockage(String code, String reason, String fieldName, String sourceValue,
                            String expectedValue, Long rowNumber, Long duplicateRowCount) {

        private static Blockage of(ScreeningBlockedException blocked) {
            return new Blockage(blocked.getCode(), blocked.getMessage(), blocked.getFieldName(),
                    blocked.getSourceValue(), blocked.getExpectedValue(), null, null);
        }

        private static Blockage onRow(String code, long rowNumber, String reason) {
            return new Blockage(code, reason, null, null, null, rowNumber, null);
        }

        private static Blockage duplicates(String code, long duplicateRowCount, String reason) {
            return new Blockage(code, reason, null, null, null, null, duplicateRowCount);
        }

        /**
         * Een vaststelling over de levering als geheel, zonder regelnummer: een overschreden drempel
         * (bouwstap 3h-4). De issuerij is dan al geschreven door de pass die de drempel vaststelde,
         * met haar aantallen erin; {@code block(...)} schrijft er geen tweede.
         */
        private static Blockage delivery(String code, String reason) {
            return new Blockage(code, reason, null, null, null, null, null);
        }
    }

    /** Lopende stand van één screening; enkel binnen één {@link #screen(long)}-aanroep gebruikt. */
    private static final class Progress {
        private final List<StageRow> pendingRows = new ArrayList<>();
        /** De prijscomponenten van dezelfde microbatch; ze worden in dezelfde transactie vastgelegd. */
        private final List<PriceRow> pendingPrices = new ArrayList<>();
        /** De kritieke koppelreferenties van dezelfde microbatch, in dezelfde transactie. */
        private final List<ReferenceRow> pendingReferences = new ArrayList<>();
        private final List<IssueRow> pendingIssues = new ArrayList<>();
        /**
         * Volledige aantallen per foutsignatuur (foutcode + logisch veld), de basis voor de
         * issuegroepen van pass E4. Deze telling loopt door boven de voorbeeldcap: zij is de enige
         * plek waar het werkelijke aantal van een verworpen regel nog bestaat.
         */
        private final IssueTally tally = new IssueTally();
        /** Aantal reeds bewaarde voorbeeldrijen per foutcode. */
        private final Map<String, Integer> recordedSamples = new HashMap<>();
        private long validCount;
        private long rejectedCount;
        /**
         * Het aantal kritieke lijnen (3h-2, ontwerp par. 15.1): ongecapt, ontdubbeld per regelnummer en
         * onafhankelijk van de voorbeeldcap. Wordt samen met {@link #rejectedCount} vastgelegd.
         */
        private final CriticalLineCounter criticalLines = new CriticalLineCounter();
        private long filteredOutCount;
        private long errorBeforeFilterCount;
        private long stagedCount;
        private Long rawRecordCount;
    }

    private final CsvRecordStreamer streamer = new CsvRecordStreamer();
    private final CandidateNormaliser normaliser = new CandidateNormaliser();

    private final DeliveryArchiveStore archive;
    private final SourceStructureConfigFactory configFactory;
    private final ImportMappingConfigFactory mappingConfigFactory;
    private final ImportBatchRepository batches;
    private final DeliveryFileRepository deliveryFiles;
    private final TaskRunRepository runs;
    private final CandidateStageDao stage;
    private final CandidatePriceDao candidatePrices;
    private final CandidateReferenceDao candidateReferences;
    private final PriceDeviationDao deviations;
    private final ReferenceControlDao referenceControl;
    /** Enkel om de bestaande omvang van de koppeling te tellen (pass E4b); schrijft hier nooit. */
    private final SourceStateDao sourceState;
    private final RowIssueDao rowIssues;
    private final IssueGroupDao issueGroups;
    private final IssueAggregationService aggregation;
    private final MutationDao mutations;
    private final TransactionTemplate transaction;
    private final int stageBatchSize;
    private final int maxSampleRowsPerCode;
    private final int maxLineLength;

    public DeliveryScreeningService(DeliveryArchiveStore archive, SourceStructureConfigFactory configFactory,
                                    ImportMappingConfigFactory mappingConfigFactory,
                                    ImportBatchRepository batches, DeliveryFileRepository deliveryFiles,
                                    TaskRunRepository runs, CandidateStageDao stage,
                                    CandidatePriceDao candidatePrices,
                                    CandidateReferenceDao candidateReferences,
                                    PriceDeviationDao deviations, ReferenceControlDao referenceControl,
                                    SourceStateDao sourceState,
                                    RowIssueDao rowIssues, IssueGroupDao issueGroups,
                                    IssueAggregationService aggregation,
                                    MutationDao mutations, PlatformTransactionManager transactionManager,
                                    @Value("${catalogimport.screening.stage-batch-size:2000}") int stageBatchSize,
                                    @Value("${catalogimport.screening.max-sample-rows-per-code:"
                                            + DEFAULT_MAX_SAMPLE_ROWS_PER_CODE + "}")
                                    int maxSampleRowsPerCode,
                                    @Value("${catalogimport.screening.max-line-length:100000}") int maxLineLength) {
        this.archive = archive;
        this.configFactory = configFactory;
        this.mappingConfigFactory = mappingConfigFactory;
        this.batches = batches;
        this.deliveryFiles = deliveryFiles;
        this.runs = runs;
        this.stage = stage;
        this.candidatePrices = candidatePrices;
        this.candidateReferences = candidateReferences;
        this.deviations = deviations;
        this.referenceControl = referenceControl;
        this.sourceState = sourceState;
        this.rowIssues = rowIssues;
        this.issueGroups = issueGroups;
        this.aggregation = aggregation;
        this.mutations = mutations;
        this.transaction = new TransactionTemplate(transactionManager);
        this.stageBatchSize = stageBatchSize > 0 ? stageBatchSize : CandidateStageDao.DEFAULT_BATCH_SIZE;
        this.maxSampleRowsPerCode = maxSampleRowsPerCode > 0
                ? maxSampleRowsPerCode : DEFAULT_MAX_SAMPLE_ROWS_PER_CODE;
        this.maxLineLength = maxLineLength > 0 ? maxLineLength : CsvRecordStreamer.DEFAULT_MAX_LINE_LENGTH;
    }

    /**
     * Screent de batch volledig. Herhaalde aanroep is veilig: enkel een batch in {@code RECEIVED}
     * wordt gescreend en een levering die onder deze revisie al een marker heeft, wordt geweigerd.
     *
     * @throws NotFoundException onbekende batch ({@code BATCH_NOT_FOUND})
     * @throws ConflictException {@code DELIVERY_ALREADY_SCREENED_WITH_THIS_REVISION},
     *                           {@code BATCH_NOT_SCREENABLE}, {@code DELIVERY_FILE_COUNT_UNSUPPORTED}
     * @throws RuntimeException  bij een technische fout tijdens het stagen; de batch staat dan al op
     *                           {@code FAILED} met opgeruimde staging. Breekt de mutatiegeneratie af,
     *                           dan blijft de batch op {@code MUTATING} staan en is
     *                           {@link #continueMutating(long)} de weg terug.
     */
    public ScreeningOutcome screen(long batchId) {
        Context context = transaction.execute(status -> start(batchId));
        Progress progress = new Progress();
        try {
            if (context.configFailure() != null) {
                throw context.configFailure();
            }
            verifyExpectedByteSize(context);
            ReadSummary summary = readAndStage(context, progress);
            progress.rawRecordCount = summary.rawRecordCount();
            flush(context, progress);
            verifyRecordCount(context, progress);
            transaction.executeWithoutResult(status -> toMutating(context, progress));
        } catch (ScreeningBlockedException blocked) {
            flushBeforeBlocking(context, progress);
            return transaction.execute(status -> block(context, Blockage.of(blocked), progress));
        } catch (RuntimeException | Error technical) {
            fail(context, technical);
            throw technical;
        }
        return mutate(context);
    }

    /**
     * Hervat de mutatiefase van een batch die op {@code MUTATING} is blijven staan (onderbroken
     * verwerking of herstart van de applicatie). Reeds geschreven mutaties worden niet herhaald.
     *
     * @throws NotFoundException onbekende batch ({@code BATCH_NOT_FOUND})
     * @throws ConflictException {@code BATCH_NOT_RESUMABLE} als de batch niet in {@code MUTATING} staat
     */
    public ScreeningOutcome continueMutating(long batchId) {
        return mutate(transaction.execute(status -> resume(batchId)));
    }

    // --- Stap 1: overgang naar SCREENING -----------------------------------------------------

    private Context start(long batchId) {
        ImportBatch batch = batches.findById(batchId)
                .orElseThrow(() -> new NotFoundException("BATCH_NOT_FOUND", "Batch " + batchId + " not found"));
        Delivery delivery = batch.getDelivery();
        long revisionId = batch.getDefinitionRevision().getId();
        // De marker is het bewijs dat deze levering onder deze revisie al afgerond is; de unieke
        // idempotentiesleutel blijft daarnaast de harde garantie tegen dubbele mutaties.
        if (mutations.markerExists(delivery.getId(), revisionId)) {
            throw new ConflictException(CODE_ALREADY_SCREENED, "Delivery " + delivery.getId()
                    + " has already been screened under definition revision " + revisionId);
        }
        if (batch.getStatus() != ImportBatchStatus.RECEIVED) {
            throw new ConflictException("BATCH_NOT_SCREENABLE",
                    "Batch " + batchId + " is in status " + batch.getStatus() + " and cannot be screened again");
        }
        DeliveryFile file = singleFile(delivery);

        // De configuratie wordt hier gelezen omdat de revisie een lazy JPA-entiteit is. Een fout
        // blokkeert de batch en mag deze transactie dus niet terugdraaien: ze reist mee als resultaat.
        // Dit is stap B' uit ontwerp fase 3 par. 3.1: structuur, veldmapping, recordfilters en
        // drempels worden één keer per batch geladen en gevalideerd, vóór er één byte gelezen is.
        SourceStructureConfig config = null;
        ImportMappingConfig mappingConfig = null;
        ScreeningBlockedException configFailure = null;
        try {
            config = configFactory.from(batch.getDefinitionRevision());
            mappingConfig = mappingConfigFactory.from(batch.getDefinitionRevision(), config);
        } catch (ScreeningBlockedException failure) {
            configFailure = failure;
        }

        // Het prijsbeleid komt van dezelfde (lazy) revisie en wordt hier, binnen de transactie, exact
        // één keer per batch gelezen - nooit per regel en nooit per chunk (R-PRI-10).
        PriceControl priceControl = priceControl(batch);

        batch.setStatus(ImportBatchStatus.SCREENING);
        batch.setStartedAt(Instant.now());
        batches.saveAndFlush(batch);

        return context(batch, delivery, file, config, mappingConfig, priceControl, configFailure);
    }

    private static PriceControl priceControl(ImportBatch batch) {
        return new PriceControl(batch.getDefinitionRevision().getPriceDeviationPercent(),
                batch.getDefinitionRevision().getPriceDeviationSeverity(),
                batch.getDefinitionRevision().getPriceAvgShortWindow(),
                batch.getDefinitionRevision().getPriceAvgLongWindow());
    }

    private Context resume(long batchId) {
        ImportBatch batch = batches.findById(batchId)
                .orElseThrow(() -> new NotFoundException("BATCH_NOT_FOUND", "Batch " + batchId + " not found"));
        if (batch.getStatus() != ImportBatchStatus.MUTATING) {
            throw new ConflictException(CODE_BATCH_NOT_RESUMABLE, "Batch " + batchId + " is in status "
                    + batch.getStatus() + "; only a batch in MUTATING can be resumed");
        }
        Delivery delivery = batch.getDelivery();
        // De bronconfiguratie is hier niet meer nodig: het bestand is al gelezen en gestaged. Het
        // prijsbeleid wél: de prijscontrolepass draait ná het stagen en kan dus hervat worden.
        return context(batch, delivery, singleFile(delivery), null, null, priceControl(batch), null);
    }

    private DeliveryFile singleFile(Delivery delivery) {
        List<DeliveryFile> files = deliveryFiles.findByDeliveryIdOrderBySequenceNumberAsc(delivery.getId());
        if (files.size() != 1) {
            throw new ConflictException("DELIVERY_FILE_COUNT_UNSUPPORTED",
                    "Delivery " + delivery.getId() + " has " + files.size()
                            + " files; phase 2 screens exactly one file per delivery");
        }
        return files.get(0);
    }

    private static Context context(ImportBatch batch, Delivery delivery, DeliveryFile file,
                                   SourceStructureConfig config, ImportMappingConfig mappingConfig,
                                   PriceControl priceControl,
                                   ScreeningBlockedException configFailure) {
        // Het bulkpercentage komt van dezelfde (lazy) revisie en wordt hier, binnen de openende
        // transactie, exact één keer per batch gelezen - nooit per groep en nooit per chunk. Ook een
        // hervatte batch leest het opnieuw, zodat pass E4 na een onderbreking hetzelfde oordeelt.
        BigDecimal bulkSharePercent = batch.getDefinitionRevision().getBulkIncidentSharePercent();
        // Idem voor de creatiedrempel (pass E4b, par. 15.2): één keer per batch van de revisie gelezen.
        // De absolute kolom creation_threshold_absolute wordt bewust niet meer gebruikt - elke drempel
        // is altijd een percentage (beslissingslog 20/09).
        BigDecimal creationSharePercent =
                batch.getDefinitionRevision().getCreationThresholdSharePercent();
        // Idem voor de twee leveringsdrempels van pass E4b (bouwstap 3h-4, par. 15.2): één keer per
        // batch van de revisie gelezen, ook bij een hervatting, zodat E4b na een onderbreking exact
        // hetzelfde oordeelt. De absolute kolommen max_critical_records/max_rejected_records worden
        // bewust niet meer gelezen - elke drempel is altijd een percentage (beslissingslog 20/09).
        BigDecimal maxCriticalSharePercent = batch.getDefinitionRevision().getMaxCriticalSharePercent();
        BigDecimal maxRejectedSharePercent = batch.getDefinitionRevision().getMaxRejectedSharePercent();
        // De bibliotheekcode is scope, geen sleutelonderdeel (beslissingslog 18/09), maar wél de scope
        // waarbinnen een kritieke koppelreferentie uniek moet zijn (par. 14.23.3). Ze wordt hier, binnen
        // de openende transactie, exact één keer per batch van de (lazy) koppeling gelezen.
        return new Context(batch.getId(), delivery.getId(), file.getId(), batch.getImportLink().getId(),
                batch.getImportLink().getLibraryCode(), batch.getDefinitionRevision().getId(),
                batch.getTaskRun() == null ? null : batch.getTaskRun().getId(), file.getArchiveReference(),
                file.getByteSize(), file.getContentHash(), delivery.getExpectedRecordCount(),
                delivery.getExpectedByteSize(), config, mappingConfig, priceControl, bulkSharePercent,
                creationSharePercent, maxCriticalSharePercent, maxRejectedSharePercent, configFailure);
    }

    // --- Stap 2: volledigheidscontroles ------------------------------------------------------

    /** Design par. 6: het byte-aantal wordt vóór het parsen gecontroleerd. */
    private void verifyExpectedByteSize(Context context) {
        Long expected = context.expectedByteSize();
        if (expected != null && expected != context.fileByteSize()) {
            throw new ScreeningBlockedException(CODE_BYTE_SIZE_MISMATCH, "byteSize",
                    String.valueOf(context.fileByteSize()), String.valueOf(expected),
                    "Manifest declares " + expected + " bytes but the delivered file has "
                            + context.fileByteSize());
        }
    }

    private void verifyRecordCount(Context context, Progress progress) {
        long raw = progress.rawRecordCount == null ? 0L : progress.rawRecordCount;
        if (raw == 0) {
            throw new ScreeningBlockedException(CODE_SOURCE_NO_DATA_RECORDS,
                    "The delivery file contains no data records");
        }
        Long expected = context.expectedRecordCount();
        if (expected != null && expected != raw) {
            throw new ScreeningBlockedException(CODE_RECORD_COUNT_MISMATCH, "recordCount",
                    String.valueOf(raw), String.valueOf(expected),
                    "Manifest declares " + expected + " records but the file contains " + raw);
        }
    }

    // --- Stap 3: lezen en stagen -------------------------------------------------------------

    private ReadSummary readAndStage(Context context, Progress progress) {
        ImportMappingConfig mappingConfig = context.mappingConfig();
        RecordFilterEvaluator filters = new RecordFilterEvaluator(mappingConfig.filters());
        try (InputStream archived = archive.open(context.archiveReference());
             CountingInputStream counting = new CountingInputStream(archived)) {
            ReadSummary summary = streamer.read(counting, context.config(),
                    mappingConfig.headerExpectations(), maxLineLength,
                    new StagingSink(context, progress, filters));
            if (counting.count() != context.fileByteSize()) {
                // Het archief is onveranderlijk: een ander byte-aantal betekent een beschadigd of
                // afgekapt object. Dat is technisch, geen leveringsprobleem.
                throw new IllegalStateException("Archived object " + context.archiveReference() + " returned "
                        + counting.count() + " bytes but " + context.fileByteSize()
                        + " were registered at intake");
            }
            return summary;
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read archived object " + context.archiveReference(), failure);
        }
    }

    /**
     * Vertaalt leesresultaten naar stagingrijen en problemen, en commit per microbatch.
     * <p>
     * <b>Volgorde (R-FLT-02).</b> Het recordfilter draait onmiddellijk na het parsen en vóór
     * identiteit, prijs en referenties. Een record dat buiten de importscope valt, krijgt dus géén
     * enkele verdere controle: het kan nooit een identiteits-, prijs- of referentieprobleem
     * veroorzaken en telt in {@code filtered_out_count} in plaats van in
     * {@code rejected_record_count}.
     */
    private final class StagingSink implements CsvRecordStreamer.Sink {

        private final Context context;
        private final Progress progress;
        private final RecordFilterEvaluator filters;

        private StagingSink(Context context, Progress progress, RecordFilterEvaluator filters) {
            this.context = context;
            this.progress = progress;
            this.filters = filters;
        }

        @Override
        public void record(ParsedRow row) {
            RecordFilterEvaluator.Decision decision = filters.evaluate(row);
            switch (decision.kind()) {
                case FILTERED_OUT -> {
                    // Geen probleemrij: buiten de scope vallen is geen fout. Het aantal blijft wel
                    // zichtbaar, zodat de reconciliatie van de tellers klopt.
                    progress.filteredOutCount++;
                    return;
                }
                case REJECTED -> {
                    addIssue(context, progress, row.lineNumber(),
                            RecordFilterEvaluator.CODE_FILTER_RECORD_REJECTED, decision.fieldName(),
                            decision.sourceValue(), decision.message(), false);
                    return;
                }
                case IN_SCOPE -> {
                    // Verder met de gewone recordcontroles.
                }
            }
            CandidateNormaliser.Result result = normaliser.normalise(row, context.config(),
                    context.mappingConfig());
            if (result instanceof NormalisedCandidate candidate) {
                progress.pendingRows.add(stageRow(context, candidate));
                progress.pendingPrices.addAll(priceRows(context, candidate));
                progress.pendingReferences.addAll(referenceRows(context, candidate));
                progress.validCount++;
                // Informatieve vaststellingen (een toegepaste standaardwaarde) horen bij een geldige
                // regel: ze verwerpen niets, maar ze mogen ook niet onzichtbaar blijven.
                for (CandidateNormaliser.RowIssue notice : candidate.notices()) {
                    addIssue(context, progress, notice.rowNumber(), notice.code(), notice.fieldName(),
                            notice.sourceValue(), notice.message(), false);
                }
                if (progress.pendingRows.size() >= stageBatchSize) {
                    flush(context, progress);
                }
            } else if (result instanceof CandidateNormaliser.RowIssue rejected) {
                addIssue(context, progress, rejected.rowNumber(), rejected.code(), rejected.fieldName(),
                        rejected.sourceValue(), rejected.message(), false);
            }
        }

        /**
         * Een probleem dat bij het lezen zelf ontstaat (kolomaantal, niet-gesloten aanhalingsteken, te
         * lange regel) of een waarschuwing over de header. Zo'n regel is niet parseerbaar en kan dus
         * <b>niet</b> aan de importscope toegewezen worden: met geconfigureerde filters telt ze in
         * {@code error_before_filter_count} en niet in {@code rejected_record_count}.
         */
        @Override
        public void issue(LineIssue issue) {
            addIssue(context, progress, issue.lineNumber(), issue.code(), issue.fieldName(),
                    issue.sourceValue(), issue.message(), true);
        }
    }

    private static StageRow stageRow(Context context, NormalisedCandidate candidate) {
        return new StageRow(context.batchId(), candidate.rowNumber(), context.deliveryFileId(),
                candidate.supplier(), candidate.supplierGroup(), candidate.supplierReference(),
                candidate.discountCode(), candidate.discountState(), candidate.identityHash(),
                candidate.basePrice(), candidate.basePriceCurrency(), candidate.description(),
                candidate.articleFingerprint(), candidate.priceFingerprint(),
                candidate.referenceFingerprint(), candidate.combinedFingerprint(),
                candidate.mutationKeyPrefix(context.deliveryId(), context.definitionRevisionId()),
                Instant.now());
    }

    /**
     * De prijscomponenten van één kandidaat (R-PRI-04). Leeg wanneer de revisie geen enkele
     * prijscomponent mapt: er wordt dan geen enkele {@code import_candidate_price}-rij geschreven en
     * het gedrag blijft exact dat van fase 2/3c.
     */
    private static List<PriceRow> priceRows(Context context, NormalisedCandidate candidate) {
        if (candidate.priceComponents().isEmpty()) {
            return List.of();
        }
        List<PriceRow> rows = new ArrayList<>(candidate.priceComponents().size());
        for (PriceRules.PriceComponent component : candidate.priceComponents()) {
            rows.add(new PriceRow(context.batchId(), candidate.rowNumber(), component.componentCode(),
                    component.sourceAmount(), component.percentage(), component.currency(),
                    component.status().name()));
        }
        return rows;
    }

    /**
     * De kritieke koppelreferenties van één kandidaat (R-REF-01/R-REF-03). Leeg wanneer de revisie er
     * geen mapt: er wordt dan geen enkele {@code import_candidate_reference}-rij geschreven, de
     * referentiecontrole heeft niets te doen en het gedrag blijft exact dat van bouwstap 3d.
     * <p>
     * Per <b>gemapte</b> referentie komt er een rij, ook wanneer de bron ze leeg levert: "gemapt maar
     * leeg" is een uitspraak van de leverancier, "niet gemapt" is er geen.
     */
    private static List<ReferenceRow> referenceRows(Context context, NormalisedCandidate candidate) {
        if (candidate.references().isEmpty()) {
            return List.of();
        }
        List<ReferenceRow> rows = new ArrayList<>(candidate.references().size());
        for (CandidateNormaliser.ReferenceValue reference : candidate.references()) {
            rows.add(new ReferenceRow(context.batchId(), candidate.rowNumber(),
                    reference.referenceType(), reference.valueRaw(), reference.valueNormalised()));
        }
        return rows;
    }

    /**
     * Legt één vastgesteld probleem vast. De ernst komt uit {@link ImportIssueCatalog} en nooit uit
     * de aanroeper: zo kan geen enkel pad een eigen oordeel wegschrijven (R-ISS-02/R-ISS-06).
     * <p>
     * <b>Alle voorvallen tellen, niet alle voorvallen worden bewaard.</b> De teller per code loopt
     * altijd door — {@code rejected_record_count} blijft dus exact — maar per code worden hoogstens
     * {@code maxSampleRowsPerCode} voorbeeldrijen bewaard. Omdat er in leesvolgorde gestreamd wordt,
     * zijn dat deterministisch de laagste regelnummers (R-ISS-03).
     *
     * @param beforeFilter of dit probleem ontstond vóór het recordfilter kon draaien. Alleen wanneer
     *                     er werkelijk filters geconfigureerd zijn, krijgt zo'n regel een eigen teller
     *                     ({@code error_before_filter_count}); zonder filters is er geen scope om
     *                     buiten te vallen en blijft het fase 2-gedrag gelden (R-FLT-04).
     */
    private void addIssue(Context context, Progress progress, long rowNumber, String code, String field,
                          String sourceValue, String message, boolean beforeFilter) {
        RowIssueSeverity severity = ImportIssueCatalog.classify(code).severity();
        // Élk voorval telt, ook het voorval waarvan geen voorbeeldrij bewaard wordt: het aantal in de
        // issuegroep is het werkelijke aantal en nooit het aantal bewaarde voorbeelden (R-ISS-03).
        progress.tally.add(code, IssueSignature.generic(field), null, rowNumber, Instant.now());
        if (severity == RowIssueSeverity.ERROR) {
            if (beforeFilter && context.hasRecordFilters()) {
                progress.errorBeforeFilterCount++;
            } else {
                progress.rejectedCount++;
            }
        }
        // Kritieke lijn (3h-2, par. 15.1): vóór de voorbeeldcap, zodat de teller ongecapt blijft. Het
        // tellen zelf verandert niets aan de verwerking; de beoordeling komt in latere bouwstappen.
        progress.criticalLines.record(context.mappingConfig(), rowNumber, severity, code, field);
        int recorded = progress.recordedSamples.getOrDefault(code, 0);
        if (recorded >= maxSampleRowsPerCode) {
            // Geen voorbeeldrij meer, maar de telling hierboven loopt door: het werkelijke aantal
            // belandt in de issuegroep en de cap-melding komt uit pass E4.
            return;
        }
        progress.recordedSamples.put(code, recorded + 1);
        progress.pendingIssues.add(ImportIssueCatalog.issue(context.batchId(), context.deliveryFileId(),
                rowNumber, code, field, sourceValue, null, message, Instant.now()));
        if (progress.pendingIssues.size() >= stageBatchSize) {
            flush(context, progress);
        }
    }

    /**
     * Legt één microbatch vast: stagingrijen, regelproblemen en de voortgangsteller in dezelfde
     * transactie. Een crash tussen twee microbatches laat dus nooit rijen zonder hun teller achter.
     */
    private void flush(Context context, Progress progress) {
        if (progress.pendingRows.isEmpty() && progress.pendingIssues.isEmpty()) {
            return;
        }
        List<StageRow> rows = List.copyOf(progress.pendingRows);
        List<PriceRow> prices = List.copyOf(progress.pendingPrices);
        List<ReferenceRow> references = List.copyOf(progress.pendingReferences);
        List<IssueRow> issues = List.copyOf(progress.pendingIssues);
        transaction.executeWithoutResult(status -> {
            stage.insertBatch(rows);
            // Ná de stagingrijen: import_candidate_price en import_candidate_reference hebben een
            // foreign key naar de kandidaat, en een prijscomponent of referentie zonder haar regel mag
            // niet kunnen bestaan.
            candidatePrices.insertBatch(prices);
            candidateReferences.insertBatch(references);
            rowIssues.insertBatch(issues);
            ImportBatch batch = batches.findById(context.batchId()).orElseThrow();
            batch.setStagedRowCount(batch.getStagedRowCount() + rows.size());
            batches.saveAndFlush(batch);
        });
        progress.stagedCount += rows.size();
        progress.pendingRows.clear();
        progress.pendingPrices.clear();
        progress.pendingReferences.clear();
        progress.pendingIssues.clear();
    }

    /**
     * Een geblokkeerde batch behoudt haar staging en haar problemen als bewijsmateriaal; de nog niet
     * weggeschreven microbatch wordt dus eerst alsnog vastgelegd. Faalt dat, dan is het alsnog een
     * technische fout.
     */
    private void flushBeforeBlocking(Context context, Progress progress) {
        try {
            flush(context, progress);
        } catch (RuntimeException technical) {
            fail(context, technical);
            throw technical;
        }
    }

    /** Einde van de stagingfase: tellers vast, batch naar MUTATING. De TaskRun blijft open. */
    private void toMutating(Context context, Progress progress) {
        ImportBatch batch = batches.findById(context.batchId()).orElseThrow();
        applyCounts(batch, progress);
        persistStagingTallies(context, progress);
        batch.getDelivery().setActualRecordCount(progress.rawRecordCount);
        batch.setStatus(ImportBatchStatus.MUTATING);
        batches.saveAndFlush(batch);
    }

    // --- Stap 4: identiteitscontrole, delta en mutatiegeneratie -------------------------------

    /**
     * De passes ná het stagen, in de volgorde van ontwerp par. 3.1/15.4: D (duplicaat/collisie) → D1
     * (dubbele kritieke referentie binnen de levering) → E1 (classificatie) → E2 (referentiecontrole)
     * → E3 (prijscontrole) → E4 (groepering en bulkincidenten) → E4b (creatiebeleid én
     * leveringsdrempels) → E5 (mutatiegeneratie) → F (afronden).
     * <p>
     * <b>Waarom classificatie en mutatie-insert gesplitst zijn</b> (ontwerp par. 3.1, "important
     * technical constraint"): de referentiecontrole bepaalt of een regel wordt vastgehouden en dus of
     * haar mutatie {@code PLANNED} of {@code BLOCKED} wordt. Die vaststelling moet volledig zijn
     * vóórdat er één mutatie geschreven wordt; in fase 2 zaten beide nog in dezelfde chunktransactie.
     * Elke pass heeft daarom een eigen voortgangskolom, is afzonderlijk hervatbaar en idempotent.
     * <p>
     * Voor een revisie <b>zonder</b> referentiemappings is het externe gedrag exact dat van fase 2/3e:
     * D1 en E2 stellen in één telling vast dat er niets te doen is en slaan zichzelf over.
     */
    private ScreeningOutcome mutate(Context context) {
        Blockage blockage = detectIdentityProblems(context);
        if (blockage != null) {
            return transaction.execute(status -> block(context, blockage, null));
        }
        detectDuplicateReferences(context);
        classifyCandidates(context);
        controlReferences(context);
        controlPrices(context);
        aggregate(context);
        CreationOutcome creationOutcome = evaluateCreationPolicy(context);
        Blockage exceeded = evaluateThresholds(context);
        if (exceeded != null) {
            // Boven een leveringsdrempel: geen enkele inhoudelijke mutatie meer. E5 draait dus niet
            // en de levering eindigt op BLOCKED, met haar staging en haar meldingen als bewijs.
            return transaction.execute(status -> block(context, exceeded, null));
        }
        generateMutations(context, creationOutcome);
        return transaction.execute(status -> complete(context));
    }

    // --- Stap E4: groeperen en bulkincidenten (ontwerp fase 3 par. 3.1, R-THR-04) ---------------

    /**
     * Vat de vastgestelde problemen samen per foutsignatuur en merkt bulkincidenten aan. Deze pass
     * draait <b>na</b> alle detectiepassen — anders zou ze op halve aantallen oordelen — en
     * <b>vóór</b> de mutatiegeneratie, zoals ontwerp par. 3.1 voorschrijft. Ze wijzigt geen enkele
     * mutatie en geen enkele classificatie: het gedrag van E5 blijft exact zoals het was.
     * <p>
     * De scope per incidentsoort wordt hier bepaald, want alleen deze service kent de koppeling en
     * het prijsbeleid. Elke bron wordt hoogstens één keer bevraagd, en alleen wanneer er werkelijk
     * een groep van die soort bestaat: een levering zonder prijs- of referentiegroepen kost dus geen
     * enkele extra query.
     */
    private IssueAggregationService.Aggregation aggregate(Context context) {
        return aggregation.aggregate(context.batchId(), context.deliveryFileId(),
                kind -> scopeFor(context, kind), context.bulkIncidentSharePercent(),
                maxSampleRowsPerCode);
    }

    /**
     * De hoeveelheid die voor deze incidentsoort werkelijk gecontroleerd is — de noemer van de
     * 1%-regel (R-THR-04). {@code null} betekent "onbekend"; er wordt dan geen aandeel berekend en
     * nooit een noemer geraden.
     */
    private Long scopeFor(Context context, IssueIncidentKind kind) {
        return switch (kind) {
            // De records die binnen de importscope vielen: een uitgefilterd record is nooit
            // gecontroleerd en hoort dus niet in de noemer (R-FLT-02/R-FLT-04).
            case GENERIC -> transaction.execute(status -> {
                ImportBatch batch = batches.findById(context.batchId()).orElseThrow();
                if (batch.getRawRecordCount() == null) {
                    return null;
                }
                long filteredOut = batch.getFilteredOutCount() == null ? 0L : batch.getFilteredOutCount();
                return Math.max(0L, batch.getRawRecordCount() - filteredOut);
            });
            // De werkelijk uitgevoerde prijsvergelijkingen (R-PRI-14).
            case PRICE -> context.priceControl() == null ? null
                    : deviations.countComparisons(context.batchId(), context.importLinkId(),
                            context.priceControl().shortWindow(), context.priceControl().longWindow());
            // De kandidaten die een gemapte kritieke referentie dragen (R-REF-07).
            case IDENTITY -> candidateReferences.countCandidatesWithReferences(context.batchId());
            // De creatiedrempel is geen issuegroep: ze wordt in pass E4b op de batch zelf berekend
            // (par. 15.2) en er bestaat dus nooit een groep van deze soort om een noemer voor te
            // leveren. Null is hier "niet van toepassing", geen geraden noemer.
            case CREATION -> null;
        };
    }

    // --- Stap E4b: het creatiebeleid (ontwerp fase 3 par. 15.2, R-THR-01) ----------------------

    /**
     * Beoordeelt of de nieuwe aanbiedingen van deze levering zonder menselijke tussenkomst aangemaakt
     * mogen worden, en legt dat oordeel vast op de batch — <b>vóór</b> de mutatiegeneratie (E5).
     * <p>
     * <b>Waarom vóór E5 en waarom persistent.</b> De mutatiestatus van elke creatie hangt van dit ene
     * oordeel af. Zou E5 het per chunk opnieuw berekenen, dan zou een levering die halverwege
     * onderbroken wordt terwijl een andere batch intussen een baseline aanvaardt, de eerste helft van
     * haar creaties op {@code AWAITING_APPROVAL} en de tweede helft op {@code PLANNED} zetten. Het
     * oordeel staat daarom in {@code import_batch.creation_outcome} en wordt nooit herberekend zodra
     * het er is: een hervatte batch leest het gewoon terug.
     * <p>
     * <b>Set-based en idempotent.</b> Twee tellingen, geen chunking, geen voortgangskolom. De
     * melding wordt enkel geschreven als ze er nog niet is, en tellers worden overschreven, nooit
     * opgeteld. Tweemaal draaien levert exact dezelfde toestand op.
     * <p>
     * <b>De teller is bewust niet {@code new_count}</b>: een regel die door de referentiecontrole (E2)
     * vastgehouden is, draagt geen {@code NEW} meer, maar wordt na goedkeuring van dat incident alsnog
     * een creatie. Zo'n regel telt mee zodra ze geen bronstaatrij heeft — conservatief, want een
     * bulkcreatie die ongezien doorgaat is erger dan een goedkeuring te veel.
     *
     * @return het geldende oordeel; nooit {@code null}
     */
    private CreationOutcome evaluateCreationPolicy(Context context) {
        CreationOutcome recorded = transaction.execute(status ->
                batches.findById(context.batchId()).orElseThrow().getCreationOutcome());
        if (recorded != null) {
            // Al beoordeeld (deze batch wordt hervat): niets herrekenen, anders zouden de statussen van
            // de tweede helft van de levering van een intussen gewijzigde bronstaat afhangen.
            return recorded;
        }
        long scope = sourceState.countActiveByImportLinkId(context.importLinkId());
        long candidates = mutations.countCreationCandidates(context.batchId(), context.importLinkId());
        Decision decision = CreationPolicyEvaluator.evaluate(scope, candidates,
                context.creationThresholdSharePercent());
        // Oordeel, noemer en melding in dezelfde transactie: er bestaat nooit een vastgelegd oordeel
        // zonder zijn melding, en nooit een melding zonder het oordeel dat haar verklaart.
        transaction.executeWithoutResult(status -> {
            ImportBatch batch = batches.findById(context.batchId()).orElseThrow();
            batch.setCreationOutcome(decision.outcome());
            batch.setCreationScopeCount(decision.scope());
            batches.saveAndFlush(batch);
            recordCreationIssue(context, decision);
        });
        LOG.info("Batch {} creation policy: {} ({} candidates against {} active offers, threshold {}%)",
                context.batchId(), decision.outcome(), decision.candidates(), decision.scope(),
                decision.thresholdPercent().toPlainString());
        return decision.outcome();
    }

    /**
     * Eén melding per batch per foutcode over het creatiebeleid, met de aantallen erin (par. 15.12).
     * Bestaat ze al — een eerdere doorloop van deze pass — dan komt er geen tweede bij.
     * <p>
     * Een initialisatie levert <b>uitsluitend</b> {@code INITIAL_LOAD_REQUIRES_APPROVAL} op en nooit
     * óók {@code BULK_CREATION_INCIDENT}: zonder bestaande omvang bestaat er geen percentage om te
     * overschrijden.
     */
    private void recordCreationIssue(Context context, Decision decision) {
        String code = decision.issueCode();
        if (code == null || rowIssues.countByBatchIdAndIssueCode(context.batchId(), code) > 0) {
            return;
        }
        rowIssues.insertBatch(List.of(ImportIssueCatalog.issue(context.batchId(),
                context.deliveryFileId(), null, code, null, String.valueOf(decision.candidates()),
                decision.thresholdPercent().toPlainString(), CreationPolicyEvaluator.message(decision),
                Instant.now())));
    }

    // --- Stap E4b (tweede deel): de leveringsdrempels (ontwerp fase 3 par. 15.2/15.3) -----------

    /**
     * Beoordeelt de twee drempels die de <b>volledige levering</b> kunnen stoppen, ná het
     * creatiebeleid en <b>vóór</b> de mutatiegeneratie (E5):
     * <ul>
     *   <li><b>records ter beoordeling</b> = {@code critical_line_count + identity_incident_count}
     *       tegen {@code max_critical_share_percent} van de records in scope. Blijft het aantal
     *       binnen de drempel, dan verandert er hier niets: de levering gaat door en vraagt een
     *       review. Erboven is de levering als geheel onbetrouwbaar ⇒ {@code BLOCKED} met
     *       {@code CRITICAL_RECORD_THRESHOLD_EXCEEDED};</li>
     *   <li><b>verworpen regels</b> tegen {@code max_rejected_share_percent}, dat standaard niet
     *       geconfigureerd is en dan nooit overschreden wordt ⇒ {@code BLOCKED} met
     *       {@code REJECTED_RECORD_THRESHOLD_EXCEEDED}.</li>
     * </ul>
     * Zijn beide overschreden, dan worden <b>beide</b> meldingen geschreven — allebei zijn ze waar en
     * allebei moeten ze zichtbaar zijn — maar {@code blocked_code} draagt de kritieke drempel: die
     * gaat over de betrouwbaarheid van de records die wél door de validatie kwamen, en weegt zwaarder
     * dan het volume van wat sowieso al verworpen was (precedentie par. 15.3).
     * <p>
     * <b>Set-based, idempotent en hervatbaar.</b> Geen chunking en geen voortgangskolom: alles wordt
     * uit de database herberekend en een melding wordt enkel geschreven wanneer die code er nog niet
     * is. De meldingen worden bewust in een <b>eigen</b> transactie vastgelegd, vóór de afrondende
     * blokkeertransactie: valt de verwerking daartussen weg, dan blijft de batch op {@code MUTATING}
     * staan en levert {@link #continueMutating(long)} exact dezelfde blokkade op, zonder één melding
     * te verdubbelen.
     * <p>
     * <b>Wat hier bewust niet gebeurt.</b> Geen enkele mutatiestatus en geen enkel
     * {@code validation_result} wordt hier herzien: het eindoordeel volgt in bouwstap 3h-5 uit
     * {@code DeliveryEffect} in plaats van uit de ernst. Een levering <i>binnen</i> de kritieke
     * drempel gedraagt zich dus exact zoals vóór deze bouwstap.
     *
     * @return de reden om te blokkeren, of {@code null} wanneer de levering binnen beide drempels
     *         blijft (of er geen oordeel mogelijk is)
     */
    private Blockage evaluateThresholds(Context context) {
        ThresholdCounts counts = transaction.execute(status -> measureThresholdCounts(context));
        ThresholdEvaluator.Judgement critical = ThresholdEvaluator.evaluateCritical(
                ThresholdEvaluator.criticalRecordCount(counts.criticalLineCount(),
                        counts.identityIncidentCount()),
                counts.scope(), context.maxCriticalSharePercent());
        ThresholdEvaluator.Judgement rejected = ThresholdEvaluator.evaluateRejected(
                counts.rejectedRecordCount(), counts.scope(), context.maxRejectedSharePercent());
        if (!critical.blocks() && !rejected.blocks()) {
            return null;
        }
        transaction.executeWithoutResult(status -> {
            if (critical.blocks()) {
                recordThresholdIssue(context, critical, ThresholdEvaluator.criticalMessage(critical,
                        counts.criticalLineCount(), counts.identityIncidentCount()));
            }
            if (rejected.blocks()) {
                recordThresholdIssue(context, rejected, ThresholdEvaluator.rejectedMessage(rejected));
            }
        });
        ThresholdEvaluator.Judgement leading = critical.blocks() ? critical : rejected;
        String reason = critical.blocks()
                ? ThresholdEvaluator.criticalMessage(critical, counts.criticalLineCount(),
                        counts.identityIncidentCount())
                : ThresholdEvaluator.rejectedMessage(rejected);
        LOG.info("Batch {} blocked by a delivery threshold: {} ({} of {} records in scope, threshold {}%)",
                context.batchId(), leading.issueCode(), leading.count(), leading.scope(),
                leading.thresholdPercent() == null ? "-" : leading.thresholdPercent().toPlainString());
        return Blockage.delivery(leading.issueCode(), reason);
    }

    /**
     * De vier getallen waarop de drempels oordelen, alle vier uit de database en nooit uit het
     * geheugen van deze doorloop: een hervatte batch moet identiek oordelen.
     */
    private record ThresholdCounts(Long criticalLineCount, Long identityIncidentCount,
                                   Long rejectedRecordCount, Long scope) {
    }

    /**
     * Meet de tellers en legt {@code identity_incident_count} alvast vast.
     * <p>
     * <b>Waarom die teller hier al geschreven wordt</b> en niet pas bij het afronden (stap F): een
     * levering die op deze drempel strandt, bereikt F nooit. Zonder deze regel zou een geblokkeerde
     * levering melden dat er N records beoordeling vroegen terwijl haar eigen teller leeg bleef. De
     * waarde is een meting uit de staging — dezelfde telling die F uitvoert — en wordt overschreven,
     * nooit opgeteld: tweemaal draaien levert exact hetzelfde op.
     * <p>
     * De classificatie is op dit punt volledig: D1 en E2 hebben elke vastgehouden regel al op
     * {@code IDENTITY_INCIDENT} gezet.
     */
    private ThresholdCounts measureThresholdCounts(Context context) {
        long identityIncidents = stage.countByClassification(context.batchId())
                .getOrDefault(CandidateClassification.IDENTITY_INCIDENT.name(), 0L);
        ImportBatch batch = batches.findById(context.batchId()).orElseThrow();
        batch.setIdentityIncidentCount(identityIncidents);
        batches.saveAndFlush(batch);
        // De records die werkelijk binnen de importscope vielen; een uitgefilterd record is nooit
        // gecontroleerd en hoort dus niet in de noemer (R-FLT-02/R-FLT-04). Is het bestand niet
        // volledig gelezen, dan blijft de scope onbekend (null) en wordt er niets geoordeeld.
        Long scope = null;
        if (batch.getRawRecordCount() != null) {
            long filteredOut = batch.getFilteredOutCount() == null ? 0L : batch.getFilteredOutCount();
            scope = Math.max(0L, batch.getRawRecordCount() - filteredOut);
        }
        return new ThresholdCounts(batch.getCriticalLineCount(), identityIncidents,
                batch.getRejectedRecordCount(), scope);
    }

    /**
     * Eén melding per batch per drempelcode, met de aantallen, het percentage en de scope erin
     * (par. 15.12). Bestaat ze al — een eerdere doorloop van deze pass — dan komt er geen tweede bij.
     */
    private void recordThresholdIssue(Context context, ThresholdEvaluator.Judgement judgement,
                                      String message) {
        if (rowIssues.countByBatchIdAndIssueCode(context.batchId(), judgement.issueCode()) > 0) {
            return;
        }
        rowIssues.insertBatch(List.of(ImportIssueCatalog.issue(context.batchId(),
                context.deliveryFileId(), null, judgement.issueCode(), null,
                String.valueOf(judgement.count()),
                judgement.thresholdPercent() == null ? null
                        : judgement.thresholdPercent().toPlainString(),
                message, Instant.now())));
    }

    /**
     * Legt de aantallen per foutsignatuur van de stagingfase vast, in dezelfde transactie als de
     * tellers van de batch. Dit is het enige moment waarop het werkelijke aantal van een verworpen
     * bronregel nog bestaat: zo'n regel wordt niet gestaged, en per foutcode zijn er hoogstens
     * {@code maxSampleRowsPerCode} voorbeeldrijen bewaard.
     */
    private void persistStagingTallies(Context context, Progress progress) {
        if (!progress.tally.isEmpty()) {
            issueGroups.accumulate(context.batchId(), progress.tally.drain());
        }
    }

    // --- Stap D1: dezelfde kritieke referentie bij twee aanbiedingen in één levering -----------

    /**
     * Dezelfde genormaliseerde referentiewaarde van hetzelfde type bij twee of meer <b>verschillende</b>
     * aanbiedingsidentiteiten binnen één levering (R-REF-07). Dat is nooit op te lossen met "laatste
     * wint": élke betrokken regel wordt vastgehouden en krijgt haar eigen kritieke melding.
     * <p>
     * In tegenstelling tot een dubbele <i>aanbiedingsidentiteit</i> blokkeert dit de volledige levering
     * niet: de rest van de catalogus is bruikbaar en enkel de betrokken aanbiedingen zijn onbetrouwbaar.
     * De levering eindigt wel op {@code validation_result = BLOCKING}, want een kritieke vaststelling
     * weegt door ongeacht volume.
     * <p>
     * <b>Eén keer.</b> Bestaan er al meldingen met deze code voor deze batch, dan is deze stap al
     * uitgevoerd — classificatie en meldingen zijn in dezelfde transactie vastgelegd — en wordt er bij
     * een hervatting niets verdubbeld.
     */
    private void detectDuplicateReferences(Context context) {
        if (!candidateReferences.hasReferences(context.batchId())
                || rowIssues.countByBatchIdAndIssueCode(context.batchId(),
                        CODE_DUPLICATE_REFERENCE_IN_DELIVERY) > 0) {
            return;
        }
        long duplicates = referenceControl.countDuplicateReferenceRows(context.batchId());
        if (duplicates == 0) {
            return;
        }
        Map<String, String> fieldNames = referenceControl.fieldNameByReferenceType();
        transaction.executeWithoutResult(status -> {
            referenceControl.classifyDuplicateReferences(context.batchId(),
                    MutationDao.IDENTITY_INCIDENT_CLASSIFICATION);
            int budget = (int) Math.min(maxSampleRowsPerCode, duplicates);
            List<DuplicateReferenceRow> rows =
                    referenceControl.findDuplicateReferenceRows(context.batchId(), budget);
            Instant now = Instant.now();
            List<IssueRow> issues = new ArrayList<>(rows.size());
            for (DuplicateReferenceRow row : rows) {
                String fieldName = fieldNames.getOrDefault(row.referenceType(), row.referenceType());
                issues.add(ImportIssueCatalog.issue(context.batchId(), context.deliveryFileId(),
                        row.rowNumber(), CODE_DUPLICATE_REFERENCE_IN_DELIVERY, fieldName,
                        row.valueNormalised(), row.referenceType(),
                        fieldName + ": '" + row.valueNormalised() + "' identifies more than one offer "
                                + "in this delivery; every line involved is held because a repeated "
                                + "critical reference is never resolved by keeping the last line",
                        IssueSignature.identity(row.referenceType(), DUPLICATE_INCIDENT_KIND), null,
                        now));
            }
            rowIssues.insertBatch(issues);
            // Het werkelijke aantal per referentietype, set-based geteld - niet het aantal bewaarde
            // voorbeeldrijen. Eén melding over de voorbeeldcap volgt in pass E4, uniform voor alle
            // passen (R-ISS-03).
            IssueTally tally = new IssueTally();
            for (ReferenceControlDao.TypeCount count
                    : referenceControl.countDuplicateReferenceRowsByType(context.batchId())) {
                tally.add(CODE_DUPLICATE_REFERENCE_IN_DELIVERY,
                        IssueSignature.identity(count.referenceType(), DUPLICATE_INCIDENT_KIND), null,
                        count.rowCount(), count.firstRowNumber(), now);
            }
            issueGroups.accumulate(context.batchId(), tally.drain());
        });
    }

    // --- Stap E1: classificatie tegen de bronstaat --------------------------------------------

    /**
     * Zet de classificatie van elke gestagede regel ten opzichte van de bronstaat ({@code NEW},
     * {@code CHANGED} of {@code UNCHANGED}), per chunk, met een eigen hervatpunt
     * ({@code import_batch.classify_progress_row_number}).
     * <p>
     * <b>Idempotent.</b> De update raakt uitsluitend regels zonder classificatie, dus een regel die
     * al vastgehouden is (D1) of al geclassificeerd is bij een eerdere doorloop, blijft staan.
     */
    private void classifyCandidates(Context context) {
        long from = transaction.execute(status ->
                batches.findById(context.batchId()).orElseThrow().getClassifyProgressRowNumber());
        Long boundary;
        while ((boundary = mutations.nextChunkBoundary(context.batchId(), from)) != null) {
            long chunkFrom = from;
            long chunkTo = boundary;
            transaction.executeWithoutResult(status -> {
                mutations.classifyChunk(context.batchId(), context.importLinkId(), chunkFrom, chunkTo);
                ImportBatch batch = batches.findById(context.batchId()).orElseThrow();
                batch.setClassifyProgressRowNumber(chunkTo);
                batches.saveAndFlush(batch);
            });
            from = chunkTo;
        }
    }

    // --- Stap E2: controle van de kritieke koppelreferenties -----------------------------------

    /**
     * Beoordeelt de kritieke koppelreferenties van elke gewijzigde of nieuwe regel (R-ID-03/R-ID-04,
     * R-REF-02..R-REF-06) en houdt elke regel met een incident vast (R-REF-09). Een afzonderlijke
     * pass met een eigen hervatpunt ({@code import_batch.reference_progress_row_number}), die ná de
     * classificatie en vóór de mutatiegeneratie draait.
     * <p>
     * <b>Wat deze pass doet en niet doet.</b> Ze wijzigt geen enkele referentie en geen enkele
     * koppeling — dat kan alleen een mens, via de goedkeuringsroute. Ze schrijft:
     * <ul>
     *   <li>de uitkomst per referentie in {@code import_candidate_reference.match_result} (audit);</li>
     *   <li>classificatie {@code IDENTITY_INCIDENT} op elke regel met een incident, zodat haar
     *       inhoudelijke mutatie {@code BLOCKED} wordt en {@code accept-baseline} haar nooit
     *       aanvaardt;</li>
     *   <li>één {@code IDENTITY_REFERENCE_INCIDENT}-mutatie per incident, status
     *       {@code AWAITING_APPROVAL}, met referentietype, oude en nieuwe waarde;</li>
     *   <li>één kritieke melding per incident en één informatieve melding per voorgestelde
     *       artikelkoppeling (R-ID-03), met dezelfde voorbeeldcap per foutcode als elke andere code.</li>
     * </ul>
     * <b>Niets te doen zonder referentiemappings.</b> Draagt deze batch geen enkele referentie, dan
     * stopt de pass meteen en kost ze één telling — het gedrag blijft exact dat van bouwstap 3e.
     */
    private void controlReferences(Context context) {
        if (!candidateReferences.hasReferences(context.batchId())) {
            return;
        }
        // Eén query per batch voor de logische veldnamen; nooit een join per regel (par. 15.12).
        Map<String, String> fieldNames = referenceControl.fieldNameByReferenceType();
        ReferencePassProgress pass = new ReferencePassProgress(
                rowIssues.countByBatchIdAndIssueCode(context.batchId(), CODE_IDENTITY_REFERENCE_INCIDENT),
                rowIssues.countByBatchIdAndIssueCode(context.batchId(), CODE_REFERENCE_LINK_PROPOSED));
        long from = transaction.execute(status ->
                batches.findById(context.batchId()).orElseThrow().getReferenceProgressRowNumber());
        Long boundary;
        while ((boundary = referenceControl.nextChunkBoundary(context.batchId(), from)) != null) {
            long chunkFrom = from;
            long chunkTo = boundary;
            // Meldingen, incidenten, classificatie en hervatpunt in dezelfde transactie: na een crash
            // wordt geen enkele chunk een tweede keer beoordeeld en ontstaat er geen dubbel incident.
            transaction.executeWithoutResult(status -> {
                evaluateReferenceChunk(context, fieldNames, pass, chunkFrom, chunkTo);
                ImportBatch batch = batches.findById(context.batchId()).orElseThrow();
                batch.setReferenceProgressRowNumber(chunkTo);
                batches.saveAndFlush(batch);
            });
            from = chunkTo;
        }
    }

    private void evaluateReferenceChunk(Context context, Map<String, String> fieldNames,
                                        ReferencePassProgress pass, long fromExclusive, long toInclusive) {
        List<ReferenceCandidate> candidates = referenceControl.findCandidates(context.batchId(),
                context.importLinkId(), context.libraryCode(), fromExclusive, toInclusive);
        if (candidates.isEmpty()) {
            return;
        }
        List<MatchUpdate> updates = new ArrayList<>();
        List<Long> heldRows = new ArrayList<>();
        List<IncidentMutation> incidents = new ArrayList<>();
        List<IssueRow> issues = new ArrayList<>();
        Instant now = Instant.now();
        // De DAO levert op regelnummer geordend; één regel is dus één aaneengesloten blok.
        List<ReferenceCandidate> group = new ArrayList<>();
        for (ReferenceCandidate candidate : candidates) {
            if (!group.isEmpty() && group.get(0).rowNumber() != candidate.rowNumber()) {
                evaluateRecord(context, fieldNames, pass, group, updates, heldRows, incidents, issues, now);
                group = new ArrayList<>();
            }
            group.add(candidate);
        }
        evaluateRecord(context, fieldNames, pass, group, updates, heldRows, incidents, issues, now);

        referenceControl.markMatchResults(context.batchId(), updates);
        referenceControl.classifyIdentityIncidents(context.batchId(), heldRows,
                MutationDao.IDENTITY_INCIDENT_CLASSIFICATION);
        referenceControl.insertIncidentMutations(context.mutationContext(), incidents, now);
        rowIssues.insertBatch(issues);
        // De aantallen van deze chunk, in dezelfde transactie als haar hervatpunt: een hervatte pass
        // telt daardoor nooit een incident dubbel.
        issueGroups.accumulate(context.batchId(), pass.tally.drain());
    }

    private void evaluateRecord(Context context, Map<String, String> fieldNames,
                                ReferencePassProgress pass, List<ReferenceCandidate> group,
                                List<MatchUpdate> updates, List<Long> heldRows,
                                List<IncidentMutation> incidents, List<IssueRow> issues, Instant now) {
        if (group.isEmpty()) {
            return;
        }
        long rowNumber = group.get(0).rowNumber();
        RecordOutcome outcome = ReferenceControlEvaluator.evaluate(rowNumber, group);
        for (ReferenceOutcome reference : outcome.references()) {
            updates.add(new MatchUpdate(rowNumber, reference.referenceType(),
                    reference.result().name(), reference.matchedSourceStateId()));
        }
        if (outcome.hasIncident()) {
            heldRows.add(rowNumber);
            for (ReferenceOutcome incident : outcome.incidents()) {
                String fieldName = fieldNames.getOrDefault(incident.referenceType(),
                        incident.referenceType());
                String message = ReferenceControlEvaluator.message(incident, fieldName);
                incidents.add(new IncidentMutation(rowNumber, incident.referenceType(),
                        incident.result().name(), incident.beforeValue(), incident.afterValue(),
                        message));
                // Élk incident telt in zijn groep, ook boven de voorbeeldcap: de groepering op
                // type + soort incident is precies wat een bulktransformatie zichtbaar maakt
                // (R-REF-07).
                IssueSignature.Signature signature = IssueSignature.identity(incident.referenceType(),
                        incident.result().name());
                pass.tally.add(CODE_IDENTITY_REFERENCE_INCIDENT, signature, null, rowNumber, now);
                if (pass.recordedIncidents >= maxSampleRowsPerCode) {
                    continue;
                }
                pass.recordedIncidents++;
                issues.add(ImportIssueCatalog.issue(context.batchId(), context.deliveryFileId(),
                        rowNumber, CODE_IDENTITY_REFERENCE_INCIDENT, fieldName,
                        incident.afterValue(), incident.result().name(), message, signature, null,
                        now));
            }
            return;
        }
        if (!outcome.linkProposed()) {
            return;
        }
        // R-ID-03: geen incident maar een vaststelling - deze nieuwe aanbieding hoort bij hetzelfde
        // artikel als een bestaande. De aanbieding wordt gewoon aangemaakt en de bestaande
        // aanbiedingsidentiteit blijft onaangeroerd.
        ReferenceOutcome matching = outcome.references().stream()
                .filter(reference -> outcome.proposedSourceStateId()
                        .equals(reference.matchedSourceStateId()))
                .findFirst().orElse(null);
        String fieldName = matching == null ? null
                : fieldNames.getOrDefault(matching.referenceType(), matching.referenceType());
        String value = matching == null ? null : matching.afterValue();
        pass.tally.add(CODE_REFERENCE_LINK_PROPOSED, IssueSignature.generic(fieldName), null,
                rowNumber, now);
        if (pass.recordedProposed >= maxSampleRowsPerCode) {
            return;
        }
        pass.recordedProposed++;
        issues.add(ImportIssueCatalog.issue(context.batchId(), context.deliveryFileId(), rowNumber,
                CODE_REFERENCE_LINK_PROPOSED, fieldName, value,
                String.valueOf(outcome.proposedSourceStateId()),
                (fieldName == null ? "criticalReference" : fieldName) + ": '" + value
                        + "' already identifies offer " + outcome.proposedSourceStateId()
                        + " in this library; this is another supplier offer for the same article. The "
                        + "new offer is created under the normal creation policy and the existing offer "
                        + "identity is never replaced", now));
    }

    /** Lopende stand van één referentiecontrolepass; enkel binnen {@link #controlReferences(Context)}. */
    private static final class ReferencePassProgress {
        /** Reeds bewaarde voorbeeldrijen per code, inclusief die van een eerdere doorloop. */
        private long recordedIncidents;
        private long recordedProposed;
        /**
         * De werkelijke aantallen per signatuur van de lopende chunk. Ze worden per chunk
         * weggeschreven, samen met het hervatpunt: het totaal over de hele batch staat in
         * {@code import_issue_group} en niet in dit object, zodat een hervatte pass niet met een
         * deelaantal eindigt.
         */
        private final IssueTally tally = new IssueTally();

        private ReferencePassProgress(long recordedIncidents, long recordedProposed) {
            this.recordedIncidents = recordedIncidents;
            this.recordedProposed = recordedProposed;
        }
    }

    // --- Stap E3: prijsafwijkingscontrole (ontwerp fase 3 par. 3.1, R-PRI-10..R-PRI-12) --------

    /**
     * Vergelijkt elke gewijzigde prijs met haar drie referenties en meldt een overschrijding. Een
     * afzonderlijke pass met een eigen hervatpunt ({@code import_batch.price_progress_row_number}),
     * die na de duplicaat-/collisiecontrole en vóór de mutatiegeneratie draait.
     * <p>
     * <b>Deze pass wijzigt niets aan de data</b> (R-PRI-12): geen prijs, geen percentage, geen
     * classificatie, geen mutatie-inhoud en geen mutatiestatus. Ze schrijft uitsluitend meldingen.
     * Een overschrijding verwerpt het record dus ook niet — ook niet wanneer de revisie de ernst op
     * {@code ERROR} zet: {@code rejected_record_count} blijft ongemoeid, want die teller telt
     * verworpen records en niet zware waarschuwingen.
     * <p>
     * <b>Niets te doen zonder bronstaat.</b> Bestaat er voor deze koppeling nog geen enkele aanvaarde
     * aanbieding, dan is er geen vorige waarde en geen historiek: de pass stopt meteen en kost geen
     * enkele extra query. Een eerste levering gedraagt zich dus exact als vóór bouwstap 3e.
     */
    private void controlPrices(Context context) {
        PriceControl control = context.priceControl();
        if (control == null || deviations.countSourceStateRows(context.importLinkId()) == 0) {
            return;
        }
        // Eén query per batch voor de logische veldnamen; nooit een join per regel (par. 15.12).
        Map<String, String> fieldNames = deviations.fieldNameByComponent();
        PricePassProgress pass = new PricePassProgress(rowIssues.countByBatchIdAndIssueCode(
                context.batchId(), PriceDeviationEvaluator.CODE_PRICE_DEVIATION_EXCEEDED));
        long from = transaction.execute(status ->
                batches.findById(context.batchId()).orElseThrow().getPriceProgressRowNumber());
        Long boundary;
        while ((boundary = deviations.nextChunkBoundary(context.batchId(), from)) != null) {
            long chunkFrom = from;
            long chunkTo = boundary;
            // Meldingen en hervatpunt in dezelfde transactie: na een crash wordt geen enkele chunk
            // een tweede keer beoordeeld en ontstaan er dus geen dubbele prijsissues.
            transaction.executeWithoutResult(status -> {
                evaluateChunk(context, control, fieldNames, pass, chunkFrom, chunkTo);
                ImportBatch batch = batches.findById(context.batchId()).orElseThrow();
                batch.setPriceProgressRowNumber(chunkTo);
                batches.saveAndFlush(batch);
            });
            from = chunkTo;
        }
        transaction.executeWithoutResult(status -> recordMissingReferenceSummary(context, control));
    }

    private void evaluateChunk(Context context, PriceControl control, Map<String, String> fieldNames,
                               PricePassProgress pass, long fromExclusive, long toInclusive) {
        List<DeviationRow> candidates = deviations.findCandidates(context.batchId(),
                context.importLinkId(), control.shortWindow(), control.longWindow(), fromExclusive,
                toInclusive);
        if (candidates.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        List<IssueRow> issues = new ArrayList<>();
        for (DeviationRow candidate : candidates) {
            PriceDeviationEvaluator.Result result = PriceDeviationEvaluator.evaluate(
                    candidate.componentCode(),
                    fieldNames.getOrDefault(candidate.componentCode(), candidate.componentCode()),
                    candidate.newAmount(),
                    Reference.previous(candidate.previousAmount()),
                    new Reference(ReferenceKind.AVG50, candidate.averageShort(), control.shortWindow()),
                    new Reference(ReferenceKind.AVG200, candidate.averageLong(), control.longWindow()),
                    control.deviationPercent());
            if (!result.exceeded()) {
                continue;
            }
            // De groepering van R-PRI-14: zelfde component én zelfde richting. Élke overschrijding
            // telt mee, ook boven de voorbeeldcap - anders zou een bulkincident afhangen van hoeveel
            // voorbeelden er toevallig bewaard zijn.
            IssueSignature.Signature signature = IssueSignature.price(result.componentCode(),
                    result.direction());
            pass.tally.add(PriceDeviationEvaluator.CODE_PRICE_DEVIATION_EXCEEDED, signature,
                    control.severity(), candidate.rowNumber(), now);
            // Dezelfde voorbeeldcap als elke andere foutcode (R-ISS-03): boven de cap worden er geen
            // voorbeelden meer bewaard, maar het aantal blijft geteld.
            if (pass.recorded >= maxSampleRowsPerCode) {
                continue;
            }
            pass.recorded++;
            issues.add(ImportIssueCatalog.issue(context.batchId(), context.deliveryFileId(),
                    candidate.rowNumber(), PriceDeviationEvaluator.CODE_PRICE_DEVIATION_EXCEEDED,
                    result.fieldName(), result.newAmount().toPlainString(), result.expectedValue(),
                    result.message(), signature, control.severity(), now));
        }
        rowIssues.insertBatch(issues);
        // De aantallen van deze chunk, in dezelfde transactie als haar hervatpunt.
        issueGroups.accumulate(context.batchId(), pass.tally.drain());
    }

    /**
     * R-PRI-11: één samenvattende INFO-melding per levering over de referenties die ontbraken, nooit
     * één per record — bij een eerste historiekopbouw zou dat miljoenen identieke rijen opleveren.
     * De aantallen worden over de <b>volledige</b> batch uit de database geteld, zodat ze ook na een
     * hervatte pass kloppen; bestaat de melding al, dan wordt er geen tweede geschreven.
     */
    private void recordMissingReferenceSummary(Context context, PriceControl control) {
        if (rowIssues.countByBatchIdAndIssueCode(context.batchId(),
                PriceDeviationEvaluator.CODE_PRICE_REFERENCE_NOT_AVAILABLE) > 0) {
            return;
        }
        MissingReferenceCounts counts = deviations.countMissingReferences(context.batchId(),
                context.importLinkId(), control.shortWindow(), control.longWindow());
        if (counts.total() == 0) {
            return;
        }
        rowIssues.insertBatch(List.of(ImportIssueCatalog.issue(context.batchId(),
                context.deliveryFileId(), null,
                PriceDeviationEvaluator.CODE_PRICE_REFERENCE_NOT_AVAILABLE, null, null, null,
                "priceReferences: '" + counts.total() + "' of " + (counts.evaluated() * 3)
                        + " price comparisons had no usable reference ("
                        + ReferenceKind.PREVIOUS.notAvailableStatus() + "=" + counts.previousMissing()
                        + ", " + ReferenceKind.AVG50.notAvailableStatus() + "="
                        + counts.shortAverageMissing()
                        + ", " + ReferenceKind.AVG200.notAvailableStatus() + "="
                        + counts.longAverageMissing()
                        + "); no deviation was computed for those and no price or percentage was changed",
                Instant.now())));
    }

    /** Lopende stand van één prijscontrolepass; enkel binnen {@link #controlPrices(Context)}. */
    private static final class PricePassProgress {
        /** Aantal reeds bewaarde voorbeeldrijen met deze foutcode, inclusief een eerdere doorloop. */
        private long recorded;
        /**
         * De werkelijke aantallen per signatuur van de lopende chunk; ze worden per chunk
         * weggeschreven, samen met het hervatpunt.
         */
        private final IssueTally tally = new IssueTally();

        private PricePassProgress(long alreadyRecorded) {
            this.recorded = alreadyRecorded;
        }
    }

    /**
     * Design par. 9 stap D: read-only en herhaalbaar. Volgorde is bewust collisie eerst — twee regels
     * met dezelfde hash maar andere sleutelcomponenten zijn géén dubbele levering maar een kapotte
     * identiteit, en mogen niet als "duplicaat" gerapporteerd worden.
     *
     * @return de reden om te blokkeren, of {@code null} als de identiteiten bruikbaar zijn
     */
    private Blockage detectIdentityProblems(Context context) {
        OptionalLong inDelivery = stage.findIdentityHashCollisionRow(context.batchId());
        if (inDelivery.isPresent()) {
            return Blockage.onRow(CODE_IDENTITY_HASH_COLLISION, inDelivery.getAsLong(),
                    "Line " + inDelivery.getAsLong()
                            + " shares its identity hash with another line in this delivery that has different "
                            + "identity components");
        }
        OptionalLong againstState = mutations.findSourceStateCollisionRow(context.batchId(),
                context.importLinkId());
        if (againstState.isPresent()) {
            return Blockage.onRow(CODE_IDENTITY_HASH_COLLISION, againstState.getAsLong(),
                    "Line " + againstState.getAsLong()
                            + " shares its identity hash with a known offer that has different identity "
                            + "components");
        }
        long duplicates = stage.countDuplicateRows(context.batchId());
        if (duplicates > 0) {
            return Blockage.duplicates(CODE_DUPLICATE_IDENTITY_IN_DELIVERY, duplicates, duplicates
                    + " lines repeat an offer identity that already occurs in this delivery; the delivery is "
                    + "blocked because a repeated identity is never resolved by keeping the last line");
        }
        return null;
    }

    /**
     * Stap E5 (ontwerp fase 3 par. 3.1): per chunk één transactie die de mutaties én het hervatpunt
     * samen vastlegt. Valt de verwerking tussen twee chunks weg, dan staat het hervatpunt altijd op
     * een chunkgrens waarvan de mutaties gecommit zijn.
     * <p>
     * <b>De classificatie zit hier niet meer in</b> (fase 2 deed beide in dezelfde chunktransactie):
     * ze is een eigen pass geworden (E1), omdat de referentiecontrole en het creatiebeleid (E4b)
     * bepalen of een mutatie {@code PLANNED}, {@code AWAITING_APPROVAL} of {@code BLOCKED} wordt. Dat
     * oordeel moet volledig zijn vóór de eerste mutatie geschreven wordt.
     *
     * @param creationOutcome het vastgelegde oordeel van pass E4b; het bepaalt of élke creatie van
     *                        deze batch op goedkeuring wacht. Het wordt hier meegegeven en niet per
     *                        chunk opnieuw bepaald, zodat een hervatte generatie dezelfde statussen
     *                        oplevert
     */
    private void generateMutations(Context context, CreationOutcome creationOutcome) {
        MutationContext mutationContext = context.mutationContext();
        // De reden op elke wachtende creatie: de foutcode van het oordeel, of null bij AUTOMATIC -
        // dan blijft het gedrag exact dat van vóór bouwstap 3h-3.
        String creationStatusReason = switch (creationOutcome) {
            case AUTOMATIC -> null;
            case INITIAL_LOAD -> ImportIssueCatalog.INITIAL_LOAD_REQUIRES_APPROVAL;
            case THRESHOLD_EXCEEDED -> ImportIssueCatalog.BULK_CREATION_INCIDENT;
        };
        // Exact één keer per batch, niet per chunk: welke prijscomponenten deze levering draagt, bepaalt
        // het domeinmasker van élke mutatie (R-PRI-09). Uit de staging en niet uit de configuratie,
        // zodat een hervatte batch hetzelfde masker oplevert als een batch in één keer.
        List<String> componentCodes = candidatePrices.componentCodes(context.batchId());
        long from = transaction.execute(status ->
                batches.findById(context.batchId()).orElseThrow().getMutationProgressRowNumber());
        Long boundary;
        while ((boundary = mutations.nextChunkBoundary(context.batchId(), from)) != null) {
            long chunkFrom = from;
            long chunkTo = boundary;
            transaction.executeWithoutResult(status -> {
                mutations.insertContentMutations(mutationContext, componentCodes, creationStatusReason,
                        chunkFrom, chunkTo, Instant.now());
                ImportBatch batch = batches.findById(context.batchId()).orElseThrow();
                batch.setMutationProgressRowNumber(chunkTo);
                batches.saveAndFlush(batch);
            });
            from = chunkTo;
        }
    }

    // --- Stap 5: afronden (design par. 9 stap F) ----------------------------------------------

    /**
     * Eén transactie: tellers, precies één {@code IMPORT_MARKER}, de overgang naar {@code SCREENED}
     * en het afsluiten van de {@code TaskRun}. Zo bestaat er nooit een afgeronde screening zonder
     * marker of een marker zonder eindstatus.
     */
    private ScreeningOutcome complete(Context context) {
        ImportBatch batch = batches.findById(context.batchId()).orElseThrow();
        Map<String, Long> classified = stage.countByClassification(context.batchId());
        batch.setNewCount(classified.getOrDefault(CandidateClassification.NEW.name(), 0L));
        batch.setChangedCount(classified.getOrDefault(CandidateClassification.CHANGED.name(), 0L));
        batch.setUnchangedCount(classified.getOrDefault(CandidateClassification.UNCHANGED.name(), 0L));
        batch.setDuplicateIdentityCount(
                classified.getOrDefault(CandidateClassification.DUPLICATE_IN_DELIVERY.name(), 0L));
        // R-REF-09: een vastgehouden regel is wél geldig gelezen, maar wordt niet doorgelaten. Ze
        // telt daarom in een eigen bak, zodat de reconciliatie blijft kloppen:
        // valid = new + changed + unchanged + duplicate_identity + identity_incident.
        batch.setIdentityIncidentCount(
                classified.getOrDefault(CandidateClassification.IDENTITY_INCIDENT.name(), 0L));
        batch.setContentMutationCount(mutations.countContentMutations(context.batchId()));
        // Pass E4 is hiervoor al gedraaid; hier wordt enkel geteld wat ze vastgesteld heeft.
        batch.setBulkIncidentCount(issueGroups.countBulkIncidents(context.batchId()));
        ValidationResult validationResult = determineValidationResult(context.batchId(), false);
        batch.setValidationResult(validationResult);
        batch.setStatus(ImportBatchStatus.SCREENED);
        batch.setFinishedAt(Instant.now());
        batches.saveAndFlush(batch);
        writeMarker(context, "outcome=SCREENED", validationResult);
        finishTaskRun(context, TaskRunStatus.COMPLETED);
        return outcome(batch);
    }

    /**
     * Het inhoudelijke eindoordeel naast de status (R-THR-06), voor zover in deze bouwstap te
     * berekenen: {@code BLOCKING} bij een geblokkeerde levering of minstens één kritiek/blokkerend
     * probleem, anders {@code VALID_WITH_WARNINGS} bij minstens één waarschuwing, anders
     * {@code VALID}. {@code REVIEW_REQUIRED} (bulkincidenten en wachtende creaties) komt in bouwstap
     * 3h-5, wanneer het oordeel uit {@code DeliveryEffect} per foutcode volgt in plaats van uit de
     * ernst; tot dan wordt die waarde nooit gezet in plaats van geraden. Gevolg van bouwstap 3h-3: een
     * initialisatie of een overschreden creatiedrempel levert hier voorlopig {@code BLOCKING} op,
     * terwijl de batch gewoon {@code SCREENED} is en enkel haar creaties op goedkeuring wachten.
     * <p>
     * Leest met JdbcTemplate wat in deze transactie met JdbcTemplate geschreven is — nooit via JPA.
     */
    private ValidationResult determineValidationResult(long batchId, boolean blocked) {
        Map<RowIssueSeverity, Long> counts = rowIssues.countsBySeverity(batchId);
        boolean blockingIssue = counts.entrySet().stream()
                .anyMatch(entry -> entry.getKey().isBlockingForBatch() && entry.getValue() > 0);
        if (blocked || blockingIssue) {
            return ValidationResult.BLOCKING;
        }
        if (counts.getOrDefault(RowIssueSeverity.WARNING, 0L) > 0) {
            return ValidationResult.VALID_WITH_WARNINGS;
        }
        return ValidationResult.VALID;
    }

    /**
     * Blokkeert de volledige levering: geen enkele inhoudelijke mutatie, wél één marker met
     * {@code outcome=BLOCKED}, in dezelfde transactie als de eindtransitie. Staging en problemen
     * blijven bewaard als bewijsmateriaal; enkel een technische fout ruimt ze op.
     * <p>
     * <b>Ook bereikbaar ná de detectiepassen</b> (bouwstap 3h-4): een overschreden leveringsdrempel
     * blokkeert vanuit pass E4b, dus wanneer E4 al gedraaid heeft. Dat is veilig: E4 is idempotent
     * (koppelen gebeurt enkel voor nog niet gekoppelde rijen, de voorbeelden worden herteld en een
     * bulk- of cap-melding wordt enkel geschreven als ze nog niet bestaat), dus de tweede doorloop
     * hieronder laat exact dezelfde groepen en meldingen achter als de eerste. Wat er ná E2 al staat,
     * blijft staan: de {@code IDENTITY_REFERENCE_INCIDENT}-mutaties in {@code AWAITING_APPROVAL} zijn
     * het bewijs dat die aanbiedingen vastgehouden zijn (par. 15.3). {@code content_mutation_count}
     * wordt gemeten en is dan 0 — E5 heeft niet gedraaid — en {@code creation_outcome} blijft staan
     * zoals E4b het vastlegde: een vastgelegd oordeel over creaties die nooit geschreven zijn, wordt
     * niet achteraf uitgewist, want dan zou een hervatte verwerking opnieuw moeten raden.
     *
     * @param progress de stand van de stagingfase, of {@code null} wanneer die al vastligt op de batch
     */
    private ScreeningOutcome block(Context context, Blockage blockage, Progress progress) {
        ImportBatch batch = batches.findById(context.batchId()).orElseThrow();
        if (progress != null) {
            applyCounts(batch, progress);
            persistStagingTallies(context, progress);
            if (progress.rawRecordCount != null) {
                batch.getDelivery().setActualRecordCount(progress.rawRecordCount);
            }
        }
        if (blockage.duplicateRowCount() != null) {
            recordDuplicateIssues(context, blockage.duplicateRowCount());
            batch.setDuplicateIdentityCount(blockage.duplicateRowCount());
        }
        recordBlockageIssue(context, blockage);
        // Ook een geblokkeerde levering krijgt haar samenvatting (pass E4). Zonder die stap zou een
        // levering die op een structuurfout strandt, het werkelijke aantal van haar regelfouten
        // verliezen: daarvan zijn immers hoogstens N voorbeeldrijen bewaard. Prijs- en
        // referentiegroepen bestaan hier niet - die passen hebben nooit gedraaid.
        batch.setBulkIncidentCount(aggregate(context).bulkIncidentCount());
        // Nul is hier geen aanname maar een vaststelling: een geblokkeerde levering genereert niets.
        batch.setContentMutationCount(mutations.countContentMutations(context.batchId()));
        ValidationResult validationResult = determineValidationResult(context.batchId(), true);
        batch.setValidationResult(validationResult);
        batch.setBlockedCode(truncate(blockage.code(), MAX_BLOCKED_CODE_LENGTH));
        batch.setBlockedReason(truncate(blockage.code() + ": " + blockage.reason(), MAX_BLOCKED_REASON_LENGTH));
        batch.setStatus(ImportBatchStatus.BLOCKED);
        batch.setFinishedAt(Instant.now());
        batches.saveAndFlush(batch);
        writeMarker(context, "outcome=BLOCKED;blockedCode=" + blockage.code(), validationResult);
        finishTaskRun(context, TaskRunStatus.COMPLETED);
        LOG.info("Batch {} blocked: {} ({})", context.batchId(), blockage.code(), blockage.reason());
        return outcome(batch);
    }

    /**
     * Elke blokkade laat ook een issuerij achter (ontwerp fase 3, par. 3.3), op controleniveau
     * {@code STRUCTURE} of {@code DELIVERY} met impactscope {@code DELIVERY}. Zonder die rij zou de
     * zwaarste vaststelling over een levering alléén in {@code blocked_code} staan en dus buiten de
     * probleemlijst vallen die de gebruiker leest. {@code blocked_code}/{@code blocked_reason}
     * blijven onveranderd bestaan.
     * <p>
     * <b>Nooit twee keer.</b> Bestaat er al een rij met deze foutcode voor deze batch, dan wordt er
     * geen samenvattende rij meer bijgeschreven: een dubbele identiteit heeft haar regels dan al
     * gemeld, en een hervatte verwerking mag de blokkade niet verdubbelen.
     */
    private void recordBlockageIssue(Context context, Blockage blockage) {
        if (rowIssues.countByBatchIdAndIssueCode(context.batchId(), blockage.code()) > 0) {
            return;
        }
        rowIssues.insertBatch(List.of(ImportIssueCatalog.issue(context.batchId(), context.deliveryFileId(),
                blockage.rowNumber(), blockage.code(), blockage.fieldName(), blockage.sourceValue(),
                blockage.expectedValue(), blockage.reason(), Instant.now())));
    }

    /**
     * Elke regel die bij een dubbele identiteit betrokken is, krijgt haar eigen probleem met het
     * regelnummer van de eerste voorkomst — ook de eerste regel zelf, want zonder de rest is ook zij
     * niet te vertrouwen. De voorbeeldcap per foutcode geldt onverkort: boven de cap worden er geen
     * voorbeelden meer bewaard, maar het volledige aantal staat in de blokkeerreden én in
     * {@code duplicate_identity_count} — het gaat dus nooit verloren.
     */
    private void recordDuplicateIssues(Context context, long duplicateRowCount) {
        stage.classifyDuplicates(context.batchId());
        long alreadyRecorded = rowIssues.countByBatchIdAndIssueCode(context.batchId(),
                CODE_DUPLICATE_IDENTITY_IN_DELIVERY);
        int budget = (int) Math.max(0, Math.min(maxSampleRowsPerCode - alreadyRecorded, duplicateRowCount));
        List<DuplicateRow> duplicates = stage.findDuplicateRows(context.batchId(), budget);
        Instant now = Instant.now();
        // Het werkelijke aantal betrokken regels, set-based vastgesteld - niet het aantal bewaarde
        // voorbeeldrijen. Zonder deze telling zou een lezer van de issuegroepen bij duizend dubbele
        // identiteiten het getal 200 zien staan.
        IssueTally tally = new IssueTally();
        tally.add(CODE_DUPLICATE_IDENTITY_IN_DELIVERY, IssueSignature.generic(null), null,
                duplicateRowCount,
                duplicates.isEmpty() ? null : duplicates.get(0).rowNumber(), now);
        issueGroups.accumulate(context.batchId(), tally.drain());
        List<IssueRow> issues = duplicates.stream()
                .map(duplicate -> ImportIssueCatalog.issue(context.batchId(), context.deliveryFileId(),
                        duplicate.rowNumber(), CODE_DUPLICATE_IDENTITY_IN_DELIVERY, null, null, null,
                        "Offer identity occurs more than once in this delivery; first occurrence on line "
                                + duplicate.firstRowNumber(), now))
                .toList();
        rowIssues.insertBatch(issues);
    }

    /**
     * Design par. 4: exact één marker per afgeronde screening. De samenvatting legt vast dat de
     * volledigheid in fase 2 niet bewezen is en welk bestand gescreend werd, zodat een latere
     * reconciliatie niet van de (wijzigbare) leveringsrijen hoeft af te hangen.
     */
    private void writeMarker(Context context, String outcome, ValidationResult validationResult) {
        mutations.insertMarker(context.mutationContext(),
                outcome + ";completenessProven=false;completenessReason=" + COMPLETENESS_REASON
                        + ";fileSha256=" + context.fileSha256()
                        + ";validationResult=" + validationResult.name(), Instant.now());
    }

    /**
     * Technische fout: batch en run op FAILED, staging en problemen van deze poging weg, geen marker.
     * Faalt ook dat nog, dan wordt die tweede fout gelogd en niet over de oorspronkelijke heen gegooid.
     */
    private void fail(Context context, Throwable cause) {
        try {
            transaction.executeWithoutResult(status -> {
                // Eerst de prijscomponenten en de referenties: ze hangen met een foreign key aan de
                // staging. De databasecascade zou ze ook opruimen; ze hier expliciet verwijderen houdt
                // de bedoeling zichtbaar in plaats van ze aan een schema-eigenschap over te laten.
                candidatePrices.deleteByBatchId(context.batchId());
                candidateReferences.deleteByBatchId(context.batchId());
                stage.deleteByBatchId(context.batchId());
                rowIssues.deleteByBatchId(context.batchId());
                // Ná de issuerijen: de foreign key van import_row_issue wijst naar de groep, en een
                // samenvatting van verdwenen problemen zou een verzonnen aantal zijn.
                issueGroups.deleteByBatchId(context.batchId());
                ImportBatch batch = batches.findById(context.batchId()).orElseThrow();
                batch.setStagedRowCount(0);
                batch.setBlockedCode(CODE_SCREENING_FAILED);
                batch.setBlockedReason(truncate(CODE_SCREENING_FAILED + ": " + cause, MAX_BLOCKED_REASON_LENGTH));
                batch.setStatus(ImportBatchStatus.FAILED);
                batch.setFinishedAt(Instant.now());
                batches.saveAndFlush(batch);
                finishTaskRun(context, TaskRunStatus.FAILED);
            });
        } catch (RuntimeException secondary) {
            LOG.error("Cannot mark batch {} as FAILED after {}", context.batchId(), cause, secondary);
        }
    }

    /**
     * Alleen een volledig gelezen bestand levert eindtellers op; anders blijven ze onbekend (null).
     * <p>
     * De vijf tellers reconciliëren (R-FLT-04):
     * {@code raw = filtered_out + error_before_filter + rejected + valid}. Zonder geconfigureerde
     * recordfilters staan {@code filtered_out} en {@code error_before_filter} op 0 — dat is geen
     * aanname maar een vaststelling: zonder filters is er niets om buiten te vallen.
     */
    private static void applyCounts(ImportBatch batch, Progress progress) {
        batch.setStagedRowCount(progress.stagedCount);
        if (progress.rawRecordCount != null) {
            batch.setRawRecordCount(progress.rawRecordCount);
            batch.setValidRecordCount(progress.validCount);
            batch.setRejectedRecordCount(progress.rejectedCount);
            batch.setCriticalLineCount(progress.criticalLines.count());
            batch.setFilteredOutCount(progress.filteredOutCount);
            batch.setErrorBeforeFilterCount(progress.errorBeforeFilterCount);
        }
    }

    private void finishTaskRun(Context context, TaskRunStatus status) {
        if (context.taskRunId() == null) {
            return;
        }
        TaskRun run = runs.findById(context.taskRunId()).orElse(null);
        if (run == null) {
            return;
        }
        run.setStatus(status);
        run.setFinishedAt(Instant.now());
        runs.saveAndFlush(run);
    }

    private static ScreeningOutcome outcome(ImportBatch batch) {
        return new ScreeningOutcome(batch.getId(), batch.getStatus(), batch.getValidationResult(),
                batch.getRawRecordCount(), batch.getValidRecordCount(), batch.getRejectedRecordCount(),
                batch.getFilteredOutCount(), batch.getErrorBeforeFilterCount(),
                batch.getStagedRowCount(), batch.getDuplicateIdentityCount(), batch.getNewCount(),
                batch.getChangedCount(), batch.getUnchangedCount(), batch.getIdentityIncidentCount(),
                batch.getCriticalLineCount(), batch.getCreationOutcome(), batch.getCreationScopeCount(),
                batch.getContentMutationCount(), batch.getBlockedCode(), batch.getBlockedReason());
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /** Telt de bytes die werkelijk uit het archief gelezen worden, zonder ze te bufferen. */
    private static final class CountingInputStream extends FilterInputStream {

        private long count;

        private CountingInputStream(InputStream delegate) {
            super(delegate);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                count++;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                count += read;
            }
            return read;
        }

        @Override
        public long skip(long requested) throws IOException {
            long skipped = super.skip(requested);
            count += skipped;
            return skipped;
        }

        private long count() {
            return count;
        }
    }
}
