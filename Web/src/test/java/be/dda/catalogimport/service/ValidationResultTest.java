package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

import be.dda.catalogimport.dao.CandidateStageDao;
import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.DeliveryRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportFieldCatalogRepository;
import be.dda.catalogimport.dao.ImportFieldMappingRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.MutationDao;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.CreationOutcome;
import be.dda.catalogimport.domain.FieldValueKind;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportFieldCatalogEntry;
import be.dda.catalogimport.domain.ImportFieldMapping;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.MutationStatus;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.RowIssueSeverity;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.domain.ValidationResult;
import be.dda.catalogimport.service.DeliveryScreeningService.ScreeningOutcome;
import be.dda.catalogimport.service.support.ImportIssueCatalog;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Bouwstap 3h-5 (ontwerp fase 3 par. 15.3, beslissingslog 20/09): {@code validation_result} — het
 * inhoudelijke <b>eindoordeel</b> over een levering, als aparte statusas naast {@code status}.
 *
 * <h2>De beslissingstabel die hier bewezen wordt</h2>
 * <pre>
 * BLOCKING            geblokkeerde levering, of ≥1 foutcode met effect BLOCK
 * REVIEW_REQUIRED     anders, als er records zijn die beoordeling vragen (kritieke lijnen +
 *                     vastgehouden identiteitsincidenten), of ≥1 foutcode met effect REVIEW, of
 *                     mutaties die op goedkeuring wachten
 * VALID_WITH_WARNINGS anders, als er ≥1 fout of waarschuwing is (INFO telt niet mee)
 * VALID               anders
 * </pre>
 * Het oordeel volgt sinds deze bouwstap uit het <b>effect per foutcode</b> ({@code DeliveryEffect})
 * en niet meer uit de ernst. Twee gedragingen wijzigen daardoor bewust ten opzichte van de
 * tussenstanden van 3f/3g/3h-3:
 * <ul>
 *   <li>een kritiek referentie-incident, een bulkincident, een eerste levering en een overschreden
 *       creatiedrempel leveren {@code REVIEW_REQUIRED} op in plaats van {@code BLOCKING} — de batch
 *       was en blijft {@code SCREENED} met haar volledige mutatielijst;</li>
 *   <li>een verworpen regel op een <b>niet-kritieke</b> kolom is nooit meer {@code VALID} maar
 *       {@code VALID_WITH_WARNINGS}; één kritieke lijn vraagt wél een review, ook ver onder de
 *       drempel ("de gebruiker heeft zelf een waarde gegeven aan kolommen", beslissingslog 20/09).
 *   </li>
 * </ul>
 *
 * <h2>Wat hier nog meer bewezen wordt</h2>
 * De {@code IMPORT_MARKER} draagt het oordeel én de aantallen waarop het steunt, met een vaste
 * sleutelvolgorde, altijd volledig, {@code -} voor wat niet vastgesteld of niet geconfigureerd is,
 * en binnen de 1000 tekens van {@code result_summary}.
 * <p>
 * Elke test bouwt een eigen keten met unieke codes: de H2-database is gedeeld.
 */
@SpringBootTest
@ActiveProfiles("local")
class ValidationResultTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String HEADER =
            "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING;EAN;BARCODE\n";
    private static final String USER = "tester@example.test";

    /** Negen tekens in een kolom van hoogstens acht: verwerpt de regel, op een niet-kritieke kolom. */
    private static final String TOO_LONG_BARCODE = "123456789";

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @MockitoSpyBean
    private CandidateStageDao stage;
    @Autowired
    private DeliveryIntakeService intake;
    @Autowired
    private DeliveryScreeningService screening;
    @Autowired
    private SourceStateBaselineService baseline;
    @Autowired
    private BatchQueryService queries;
    @Autowired
    private SourceOrganisationRepository sourceOrganisations;
    @Autowired
    private ImportDefinitionRepository definitions;
    @Autowired
    private ImportDefinitionRevisionRepository revisions;
    @Autowired
    private ImportFieldCatalogRepository fieldCatalog;
    @Autowired
    private ImportFieldMappingRepository fieldMappings;
    @Autowired
    private ImportLinkRepository links;
    @Autowired
    private CatalogImportTaskRepository tasks;
    @Autowired
    private DeliveryRepository deliveries;
    @Autowired
    private ImportBatchRepository batches;
    @Autowired
    private JdbcTemplate jdbc;

    // --- VALID en VALID_WITH_WARNINGS ---------------------------------------------------------

    /** Niets vastgesteld, niets te beoordelen: het enige geval waarin een levering {@code VALID} is. */
    @Test
    void aCleanDeliveryIsValid() {
        Fixture fixture = acceptedBaseline("VALID", 2);

        ScreeningOutcome outcome = screenSecond(fixture, rows(2));

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(issueCodes(outcome.batchId())).isEmpty();
        assertThat(outcome.validationResult()).isEqualTo(ValidationResult.VALID);
        assertThat(queries.getBatch(outcome.batchId()).validationResult()).isEqualTo("VALID");
        assertThat(outcome.criticalIssueCount()).isZero();
        assertThat(outcome.warningCount()).isZero();
        assertThat(outcome.awaitingApprovalCount()).isZero();
    }

    /**
     * Een informatieve vaststelling verandert het oordeel niet: deze nieuwe aanbieding hoort via haar
     * EAN bij hetzelfde artikel als een bestaande ({@code REFERENCE_LINK_PROPOSED}, INFO, R-ID-03).
     * De aanbieding wordt gewoon aangemaakt.
     */
    @Test
    void onlyInformationalFindingsStillLeaveTheDeliveryValid() {
        Fixture fixture = acceptedBaseline("INFO", 1, revision -> {
            // Eén nieuwe aanbieding naast één bestaande is 100%: deze test gaat over het INFO-geval,
            // niet over de creatiedrempel.
            revision.setCreationThresholdSharePercent(new BigDecimal("100"));
        });

        // Bewust zonder R1: dezelfde EAN tweemaal in één levering zou beide regels vasthouden
        // (R-REF-07). Hier draagt één nieuwe aanbieding de EAN van een bestaande.
        ScreeningOutcome outcome = screenSecond(fixture,
                row("R9", "1,75", "Boormachine", "E-1", "B9"));

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(issueCodes(outcome.batchId()))
                .containsExactly(ImportIssueCatalog.REFERENCE_LINK_PROPOSED);
        assertThat(severities(outcome.batchId())).containsOnly(RowIssueSeverity.INFO.name());
        assertThat(outcome.validationResult()).isEqualTo(ValidationResult.VALID);
        assertThat(outcome.creationOutcome()).isEqualTo(CreationOutcome.AUTOMATIC);
    }

    /** Een verwijderde BOM is een waarschuwing: de levering blijft bruikbaar, maar niet onopgemerkt. */
    @Test
    void aWarningMakesTheDeliveryValidWithWarnings() {
        Fixture fixture = acceptedBaseline("WARN", 2);

        ScreeningOutcome outcome = screenSecond(fixture, "﻿", rows(2));

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.validationResult()).isEqualTo(ValidationResult.VALID_WITH_WARNINGS);
        assertThat(outcome.warningCount()).isEqualTo(1L);
        assertThat(outcome.criticalIssueCount()).isZero();
    }

    /**
     * Beslissing van de mens (20/09): "de gebruiker heeft zelf een waarde gegeven aan kolommen
     * (kritiek of niet); kritieke lijnfouten hebben een review nodig, waarschuwingen niet."
     * <p>
     * Een te lange barcode verwerpt de regel, maar de kolom is niet-kritiek verklaard: geen review.
     * Het oordeel is wél {@code VALID_WITH_WARNINGS} en <b>nooit</b> {@code VALID} — er is een fout
     * vastgesteld en die mag niet onzichtbaar worden.
     */
    @Test
    void aRejectedLineOnANonCriticalColumnIsNeverValidButNeedsNoReview() {
        Fixture fixture = acceptedBaseline("NONCRIT", 2);

        ScreeningOutcome outcome = screenSecond(fixture,
                row("R1", "1,50", "Boormachine", "E-1", "B1")
                        + row("R2", "1,50", "Schroevendraaier", "E-2", TOO_LONG_BARCODE));

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.rejectedRecordCount()).isEqualTo(1L);
        assertThat(outcome.criticalLineCount()).isZero();
        assertThat(outcome.validationResult()).isEqualTo(ValidationResult.VALID_WITH_WARNINGS);
        assertThat(outcome.awaitingApprovalCount()).isZero();
    }

    // --- REVIEW_REQUIRED ------------------------------------------------------------------------

    /**
     * Eén kritieke lijn (een onleesbare basisprijs) vraagt een review, ook binnen de drempel en ook
     * naast een waarschuwing: het oordeel is {@code REVIEW_REQUIRED} en niet
     * {@code VALID_WITH_WARNINGS}. De review weegt zwaarder dan de waarschuwing.
     */
    @Test
    void oneCriticalLineAsksForAReviewEvenNextToAWarning() {
        Fixture fixture = acceptedBaseline("CRITLINE", 2);

        ScreeningOutcome outcome = screenSecond(fixture, "﻿",
                row("R1", "onleesbaar", "Boormachine", "E-1", "B1")
                        + row("R2", "1,50", "Schroevendraaier", "E-2", "B2"));

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.criticalLineCount()).isEqualTo(1L);
        assertThat(outcome.warningCount()).isEqualTo(1L);
        assertThat(outcome.validationResult()).isEqualTo(ValidationResult.REVIEW_REQUIRED);
    }

    /**
     * Een gewijzigde EAN is een kritiek referentie-incident. <b>Gewijzigd gedrag</b> ten opzichte van
     * de 3f-tussenstand: binnen de drempel is dat {@code SCREENED} + {@code REVIEW_REQUIRED} in
     * plaats van {@code BLOCKING}. De regel blijft vastgehouden en haar mutatie {@code BLOCKED}.
     */
    @Test
    void aCriticalReferenceIncidentWithinTheThresholdAsksForAReview() {
        Fixture fixture = acceptedBaseline("REFINC", 2);

        ScreeningOutcome outcome = screenSecond(fixture,
                row("R1", "1,50", "Boormachine", "E-99", "B1")
                        + row("R2", "1,50", "Schroevendraaier", "E-2", "B2"));

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.identityIncidentCount()).isEqualTo(1L);
        assertThat(outcome.validationResult()).isEqualTo(ValidationResult.REVIEW_REQUIRED);
        assertThat(issueCodes(outcome.batchId()))
                .contains(ImportIssueCatalog.IDENTITY_REFERENCE_INCIDENT);
        // Het incident telt ongecapt mee als kritieke vaststelling.
        assertThat(outcome.criticalIssueCount()).isEqualTo(1L);
        assertThat(mutationStatuses(outcome.batchId())).contains(MutationStatus.BLOCKED.name());
    }

    /**
     * Twaalf gelijksoortige incidenten op twaalf kandidaten: één bulkgroep boven de 1%-grens, dus
     * één {@code BULK_IDENTITY_INCIDENT} náást de individuele meldingen. Ook dat vraagt een
     * beoordeling en stopt de levering niet (was {@code BLOCKING} in de 3g-tussenstand).
     */
    @Test
    void aBulkIdentityIncidentAsksForAReview() {
        Fixture fixture = acceptedBaseline("BULKID", 12);
        StringBuilder csv = new StringBuilder();
        for (int i = 1; i <= 12; i++) {
            csv.append(row("R" + i, "1,50", "Boormachine", "E-9" + i, "B" + i));
        }

        ScreeningOutcome outcome = screenSecond(fixture, csv.toString());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(issueCodes(outcome.batchId()))
                .contains(ImportIssueCatalog.BULK_IDENTITY_INCIDENT);
        assertThat(outcome.validationResult()).isEqualTo(ValidationResult.REVIEW_REQUIRED);
        assertThat(outcome.identityIncidentCount()).isEqualTo(12L);
    }

    /**
     * De <b>eerste</b> levering van een koppeling is een initialisatie: elke creatie wacht op
     * goedkeuring en de levering vraagt een beoordeling. Ook dit was {@code BLOCKING} in de
     * 3h-3-tussenstand.
     */
    @Test
    void aFirstDeliveryAsksForAReviewAndHoldsEveryCreation() {
        Fixture fixture = fixture("INIT");

        ScreeningOutcome outcome = screen(fixture, "REF-1", HEADER + rows(2));

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.creationOutcome()).isEqualTo(CreationOutcome.INITIAL_LOAD);
        assertThat(outcome.validationResult()).isEqualTo(ValidationResult.REVIEW_REQUIRED);
        assertThat(mutationStatuses(outcome.batchId()))
                .containsOnly(MutationStatus.AWAITING_APPROVAL.name());
        // De teller telt uitsluitend CREATE/UPDATE, net als content_mutation_count.
        assertThat(outcome.awaitingApprovalCount()).isEqualTo(2L);
        assertThat(outcome.contentMutationCount()).isEqualTo(2L);
        assertThat(outcome.creationCandidateCount()).isEqualTo(2L);
        assertThat(outcome.creationScopeCount()).isZero();
    }

    /** Boven de creatiedrempel: ook dat is een beoordeling, geen blokkade. */
    @Test
    void anExceededCreationThresholdAsksForAReview() {
        Fixture fixture = acceptedBaseline("CREOVER", 1);

        ScreeningOutcome outcome = screenSecond(fixture,
                row("R1", "1,50", "Boormachine", "E-1", "B1")
                        + row("R8", "1,50", "Hamer", "E-8", "B8")
                        + row("R9", "1,50", "Zaag", "E-9", "B9"));

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.creationOutcome()).isEqualTo(CreationOutcome.THRESHOLD_EXCEEDED);
        assertThat(outcome.validationResult()).isEqualTo(ValidationResult.REVIEW_REQUIRED);
        assertThat(outcome.awaitingApprovalCount()).isEqualTo(2L);
        assertThat(outcome.creationCandidateCount()).isEqualTo(2L);
        assertThat(outcome.creationScopeCount()).isEqualTo(1L);
    }

    // --- BLOCKING en "geen oordeel" -------------------------------------------------------------

    /** Een structuurfout blokkeert de levering; het oordeel volgt de blokkade. */
    @Test
    void aBlockedDeliveryIsBlocking() {
        Fixture fixture = fixture("BLOCK");

        ScreeningOutcome outcome = screen(fixture, "REF-1", HEADER);

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(outcome.validationResult()).isEqualTo(ValidationResult.BLOCKING);
        assertThat(batch(outcome.batchId()).getValidationResult())
                .isEqualTo(ValidationResult.BLOCKING);
        assertThat(markerSummary(outcome.batchId()))
                .contains("outcome=BLOCKED")
                .contains("validationResult=BLOCKING");
    }

    /** Een dubbele aanbiedingsidentiteit blokkeert eveneens — nooit "laatste wint". */
    @Test
    void aDuplicateIdentityIsBlocking() {
        Fixture fixture = fixture("DUP");

        ScreeningOutcome outcome = screen(fixture, "REF-1",
                HEADER + row("R1", "1,50", "Boormachine", "E-1", "B1")
                        + row("R1", "1,60", "Boormachine", "E-2", "B2"));

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(outcome.blockedCode())
                .isEqualTo(ImportIssueCatalog.DUPLICATE_IDENTITY_IN_DELIVERY);
        assertThat(outcome.validationResult()).isEqualTo(ValidationResult.BLOCKING);
    }

    /** Zolang er niets vastgesteld is, blijft het oordeel leeg — nooit stilzwijgend VALID. */
    @Test
    void aBatchThatHasNotFinishedHasNoValidationResultYet() {
        Fixture fixture = fixture("OPEN");
        long batchId = deliver(fixture, "REF-1", HEADER + rows(2)).batchId();

        assertThat(batch(batchId).getStatus()).isEqualTo(ImportBatchStatus.RECEIVED);
        assertThat(batch(batchId).getValidationResult()).isNull();
        assertThat(queries.getBatch(batchId).validationResult()).isNull();
        assertThat(batch(batchId).getCriticalIssueCount()).isNull();
        assertThat(batch(batchId).getWarningCount()).isNull();
        assertThat(batch(batchId).getAwaitingApprovalCount()).isNull();
    }

    /**
     * Een technische fout levert geen oordeel op: {@code FAILED} met {@code validation_result} en
     * alle tellers op {@code null}. Er is niets vastgesteld, en dat wordt nooit een stille 0 of een
     * halve meting.
     */
    @Test
    void aTechnicalFailureLeavesNoJudgementAtAll() {
        Fixture fixture = fixture("FAILED");
        StringBuilder csv = new StringBuilder();
        for (int i = 1; i <= 12; i++) {
            csv.append(row("R" + i, "1,50", "Boormachine", "E-" + i, "B" + i));
        }
        long batchId = deliver(fixture, "REF-1", HEADER + csv).batchId();
        Mockito.doThrow(new UncheckedIOException(new IOException("simulated crash while staging")))
                .when(stage).insertBatch(any());

        try {
            screening.screen(batchId);
            throw new AssertionError("the simulated crash did not propagate");
        } catch (UncheckedIOException expected) {
            assertThat(expected).hasRootCauseInstanceOf(IOException.class);
        }

        ImportBatch failed = batch(batchId);
        assertThat(failed.getStatus()).isEqualTo(ImportBatchStatus.FAILED);
        assertThat(failed.getValidationResult()).isNull();
        assertThat(failed.getCriticalIssueCount()).isNull();
        assertThat(failed.getWarningCount()).isNull();
        assertThat(failed.getAwaitingApprovalCount()).isNull();
        assertThat(markerSummaries(batchId)).isEmpty();
    }

    // --- De marker -------------------------------------------------------------------------------

    /**
     * De marker draagt alle sleutels in hun vaste volgorde, met de aantallen waarop het oordeel
     * steunt. De oude sleutels houden hun vorm: bestaande lezers verliezen niets.
     */
    @Test
    void theMarkerCarriesEveryKeyOfTheJudgementInAFixedOrder() {
        Fixture fixture = acceptedBaseline("MARKER", 2);

        ScreeningOutcome outcome = screenSecond(fixture,
                row("R1", "onleesbaar", "Boormachine", "E-1", "B1")
                        + row("R2", "1,50", "Schroevendraaier", "E-2", "B2"));

        String marker = markerSummary(outcome.batchId());
        assertThat(marker)
                // De bestaande sleutels, ongewijzigd.
                .contains("outcome=SCREENED")
                .contains("completenessProven=false")
                .contains("completenessReason=" + DeliveryScreeningService.COMPLETENESS_REASON)
                .contains("fileSha256=")
                .contains("validationResult=REVIEW_REQUIRED")
                // De aantallen van bouwstap 3h-5, in vaste volgorde.
                .contains(";criticalRecords=1/100%")
                .contains(";criticalLines=1")
                .contains(";identityIncidents=0")
                // max_rejected_share_percent is niet geconfigureerd: '-', nooit 0.
                .contains(";rejected=1/-")
                .contains(";warnings=0")
                .contains(";criticalIssues=0")
                .contains(";bulkIncidents=0")
                .contains(";awaitingApproval=0")
                .contains(";creationScope=2")
                .contains(";creationCandidates=0")
                .contains(";creationThreshold=1%")
                .contains(";creationOutcome=AUTOMATIC");
        assertThat(keysOf(marker)).containsExactly("outcome", "completenessProven",
                "completenessReason", "fileSha256", "validationResult", "criticalRecords",
                "criticalLines", "identityIncidents", "rejected", "warnings", "criticalIssues",
                "bulkIncidents", "awaitingApproval", "creationScope", "creationCandidates",
                "creationThreshold", "creationOutcome");
        assertThat(marker.length()).isLessThan(MutationDao.MAX_RESULT_SUMMARY_LENGTH);
    }

    /**
     * Een geblokkeerde levering houdt {@code outcome=BLOCKED;blockedCode=...} vooraan en vult de
     * rest zo ver ze gemeten is. Wat ze nooit bereikt heeft — het creatiebeleid draait pas ná de
     * drempels — staat op {@code -} en nooit op 0.
     */
    @Test
    void theMarkerOfABlockedDeliveryShowsDashesForWhatWasNeverEstablished() {
        Fixture fixture = fixture("MARKBLOCK");

        ScreeningOutcome outcome = screen(fixture, "REF-1", HEADER);

        String marker = markerSummary(outcome.batchId());
        assertThat(marker).startsWith("outcome=BLOCKED;blockedCode="
                + ImportIssueCatalog.SOURCE_NO_DATA_RECORDS);
        assertThat(marker)
                .contains("validationResult=BLOCKING")
                // Er is geen enkele datalijn gelezen: de records ter beoordeling zijn niet
                // vastgesteld, want de vastgehouden identiteiten zijn nooit geteld.
                .contains(";criticalRecords=-/100%")
                .contains(";identityIncidents=-")
                .contains(";criticalLines=0")
                .contains(";rejected=0/-")
                // Pass E4 draaide ook hier, dus deze tellers zijn wél gemeten.
                .contains(";criticalIssues=0")
                .contains(";warnings=0")
                .contains(";awaitingApproval=0")
                // Het creatiebeleid is nooit uitgevoerd op het blokkeerpad (par. 15.4, 3h-5).
                .contains(";creationScope=-")
                .contains(";creationCandidates=-")
                .contains(";creationOutcome=-");
        assertThat(marker.length()).isLessThan(MutationDao.MAX_RESULT_SUMMARY_LENGTH);
    }

    /**
     * Het langst denkbare realistische scenario: een blokkeercode van de maximale lengte, een
     * zesenzestig tekens lange bestandshash, zeven- tot achtcijferige tellers en percentages met
     * twaalf decimalen. De samenvatting moet dan nog altijd in {@code varchar(1000)} passen —
     * afkappen zou stil een aantal verminken.
     */
    @Test
    void theLongestRealisticMarkerStillFitsInTheColumn() {
        String longest = "outcome=BLOCKED;blockedCode=" + "X".repeat(60)
                + ";completenessProven=false;completenessReason="
                + DeliveryScreeningService.COMPLETENESS_REASON
                + ";fileSha256=" + "a".repeat(64)
                + ";validationResult=VALID_WITH_WARNINGS"
                + ";criticalRecords=99999999999/99.999999999999%"
                + ";criticalLines=99999999999;identityIncidents=99999999999"
                + ";rejected=99999999999/99.999999999999%"
                + ";warnings=99999999999;criticalIssues=99999999999;bulkIncidents=99999999999"
                + ";awaitingApproval=99999999999;creationScope=99999999999"
                + ";creationCandidates=99999999999;creationThreshold=99.999999999999%"
                + ";creationOutcome=THRESHOLD_EXCEEDED";

        assertThat(longest.length()).isLessThan(MutationDao.MAX_RESULT_SUMMARY_LENGTH);
    }

    // --- Helpers -----------------------------------------------------------------------------------

    private ImportBatch batch(long batchId) {
        return batches.findById(batchId).orElseThrow();
    }

    private String markerSummary(long batchId) {
        return markerSummaries(batchId).get(0);
    }

    private List<String> markerSummaries(long batchId) {
        return jdbc.queryForList("select result_summary from import_mutation where batch_id = ? "
                + "and action_type = 'IMPORT_MARKER'", String.class, batchId);
    }

    /** De sleutels van de marker, in de volgorde waarin ze voorkomen. */
    private static List<String> keysOf(String marker) {
        return Arrays.stream(marker.split(";"))
                .map(part -> part.substring(0, part.indexOf('=')))
                // blockedCode hoort bij outcome en staat enkel op het blokkeerpad.
                .filter(key -> !"blockedCode".equals(key))
                .toList();
    }

    private List<String> issueCodes(long batchId) {
        return jdbc.queryForList("select distinct issue_code from import_row_issue where batch_id = ? "
                + "order by issue_code", String.class, batchId);
    }

    private List<String> severities(long batchId) {
        return jdbc.queryForList("select severity from import_row_issue where batch_id = ?",
                String.class, batchId);
    }

    private List<String> mutationStatuses(long batchId) {
        return jdbc.queryForList("select status from import_mutation where batch_id = ? "
                + "and action_type in ('CREATE', 'UPDATE')", String.class, batchId);
    }

    /** {@code count} ongewijzigde regels, met hun eigen EAN en barcode. */
    private static String rows(int count) {
        StringBuilder csv = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            csv.append(row("R" + i, "1,50", "Boormachine", "E-" + i, "B" + i));
        }
        return csv.toString();
    }

    private static String row(String reference, String price, String description, String ean,
                              String barcode) {
        return "ACME;G1;" + reference + ';' + price + ';' + description + ';' + ean + ';' + barcode
                + '\n';
    }

    /**
     * Levert een nulmeting van {@code rows} regels aan, screent en aanvaardt ze. De volgende levering
     * op dezelfde keten is dus geen initialisatie meer (bouwstap 3h-3).
     */
    private Fixture acceptedBaseline(String prefix, int rows) {
        return acceptedBaseline(prefix, rows, revision -> {
        });
    }

    private Fixture acceptedBaseline(String prefix, int rows,
                                     Consumer<ImportDefinitionRevision> tweak) {
        Fixture fixture = fixture(prefix, tweak);
        ScreeningOutcome baselineOutcome = screen(fixture, "REF-BASE", HEADER + rows(rows));
        assertThat(baselineOutcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        baseline.acceptBaseline(baselineOutcome.batchId(), USER, "nulmeting");
        return fixture;
    }

    private ScreeningOutcome screenSecond(Fixture fixture, String body) {
        return screenSecond(fixture, "", body);
    }

    private ScreeningOutcome screenSecond(Fixture fixture, String prologue, String body) {
        return screen(fixture, "REF-NEXT", prologue + HEADER + body);
    }

    /** @param csv het volledige bestand, header inbegrepen */
    private ScreeningOutcome screen(Fixture fixture, String reference, String csv) {
        return screening.screen(deliver(fixture, reference, csv).batchId());
    }

    private Delivered deliver(Fixture fixture, String reference, String csv) {
        DeliveryView view = intake.intake(fixture.taskId(), reference, USER, null, null, "levering.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))).delivery();
        return new Delivered(deliveries.findById(view.deliveryId()).orElseThrow().getId(),
                view.batch().batchId());
    }

    private Fixture fixture(String prefix) {
        return fixture(prefix, revision -> {
        });
    }

    /**
     * Eén eigen keten met een gemapte kritieke koppelreferentie (EAN) en een gemapte niet-kritieke
     * kolom (leveranciersbarcode, hoogstens acht tekens).
     * <p>
     * {@code max_critical_share_percent} staat op 100: deze test gaat over het <b>oordeel</b> en niet
     * over de drempel die de levering stopt. Met de productiedefault van 1% zou een fixture met twee
     * records en één kritieke lijn geblokkeerd worden en viel er niets meer te beoordelen.
     * {@code ThresholdBlockingTest} bewijst de default zelf.
     */
    private Fixture fixture(String prefix, Consumer<ImportDefinitionRevision> tweak) {
        String unique = "VR" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
        SourceOrganisation organisation = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + "-ORG BV", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(new ImportDefinition(
                organisation, unique + "-DEF", unique + " catalogus", "beheerder@example.test"));
        ImportDefinitionRevision revision = new ImportDefinitionRevision(definition, 1,
                IdentityProfileKind.THREE_PART, "beheerder@example.test");
        revision.setAccessConfigHash("1".repeat(64));
        revision.setStructureConfigHash("2".repeat(64));
        revision.setRecordRulesConfigHash("3".repeat(64));
        revision.setCompositeConfigHash("4".repeat(64));
        revision.setIdentitySupplierField("LEVERANCIER");
        revision.setIdentitySupplierGroupField("GROEP");
        revision.setIdentitySupplierReferenceField("REFERENTIE");
        revision.setStructureDelimiter(";");
        revision.setRecordBasePriceField("PRIJS");
        revision.setRecordDescriptionField("OMSCHRIJVING");
        revision.setRecordCanonicalisationVersion(2);
        revision.setMaxCriticalSharePercent(new BigDecimal("100"));
        revision.setStatus(RevisionStatus.ACTIVE);
        tweak.accept(revision);
        ImportDefinitionRevision stored = revisions.saveAndFlush(revision);

        ImportFieldCatalogEntry ean = fieldCatalog.findById("EAN").orElseThrow();
        ImportFieldMapping eanMapping = new ImportFieldMapping(stored, 1, ean,
                FieldValueKind.SOURCE_FIELD, ean.getDataType(), ean.getDefaultOwner(),
                ean.getIdentityClass());
        eanMapping.setSourceReference("EAN");
        eanMapping.setReferenceType(ean.getReferenceType());
        fieldMappings.saveAndFlush(eanMapping);

        ImportFieldCatalogEntry barcode = fieldCatalog.findById("SUPPLIER_BARCODE").orElseThrow();
        ImportFieldMapping barcodeMapping = new ImportFieldMapping(stored, 2, barcode,
                FieldValueKind.SOURCE_FIELD, barcode.getDataType(), barcode.getDefaultOwner(),
                barcode.getIdentityClass());
        barcodeMapping.setSourceReference("BARCODE");
        barcodeMapping.setMaxLength(8);
        fieldMappings.saveAndFlush(barcodeMapping);

        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        // Een eigen bibliotheek per test: een kritieke koppelreferentie is uniek per bibliotheek.
        String libraryCode = unique.substring(0, Math.min(unique.length(), 20));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, libraryCode));
        CatalogImportTask task = tasks.saveAndFlush(
                new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL));
        return new Fixture(task.getId(), link.getId(), stored.getId());
    }

    private record Fixture(long taskId, long linkId, long revisionId) {
    }

    private record Delivered(long deliveryId, long batchId) {
    }
}
