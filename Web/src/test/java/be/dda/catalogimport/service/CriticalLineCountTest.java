package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.CandidateStageDao;
import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.DeliveryRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportFieldCatalogRepository;
import be.dda.catalogimport.dao.ImportFieldMappingRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.ImportRecordFilterRepository;
import be.dda.catalogimport.dao.ImportRevisionFieldCriticalityRepository;
import be.dda.catalogimport.dao.MutationDao;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.Criticality;
import be.dda.catalogimport.domain.FieldValueKind;
import be.dda.catalogimport.domain.FilterNullBehaviour;
import be.dda.catalogimport.domain.FilterOperator;
import be.dda.catalogimport.domain.FilterOutcome;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportFieldCatalogEntry;
import be.dda.catalogimport.domain.ImportFieldMapping;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.ImportRecordFilter;
import be.dda.catalogimport.domain.ImportRevisionFieldCriticality;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.RowIssueSeverity;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.service.DeliveryScreeningService.ScreeningOutcome;
import be.dda.catalogimport.service.support.CandidateNormaliser;
import be.dda.catalogimport.service.support.CriticalLineCounter;
import be.dda.catalogimport.service.support.CsvRecordStreamer;
import be.dda.catalogimport.service.support.FieldValueMapper;
import be.dda.catalogimport.service.support.ImportIssueCatalog;
import be.dda.catalogimport.service.support.ImportMappingConfig;
import be.dda.catalogimport.service.support.ImportValueRules;
import be.dda.catalogimport.service.support.PriceDeviationEvaluator;
import be.dda.catalogimport.service.support.RecordFilterEvaluator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Bouwstap 3h-2 (ontwerp fase 3, par. 15.1): het <b>tellen</b> van kritieke lijnen. Er hangt nog geen
 * oordeel aan: geen drempel, geen {@code validation_result}-herziening, geen mutatiestatus. De teller
 * wordt enkel opgeslagen ({@code import_batch.critical_line_count}) en getoond.
 *
 * <h2>Wat hier bewezen wordt</h2>
 * <ul>
 *   <li>Een verworpen regel met een ERROR op een kritieke kolom telt; een ERROR op een niet-kritieke
 *       kolom verwerpt de regel wel maar telt niet. Kritiek geldt voor revisie-eigen velden (bronreferentie),
 *       gemapte velden (logische veldnaam) en de overrule via {@code import_revision_field_criticality}.</li>
 *   <li>Onherleidbare fouten (kolomaantal, niet-gesloten quote, lege identiteitscomponent) tellen.</li>
 *   <li>Nooit kritiek: {@code FILTER_RECORD_REJECTED}, een WARNING (ook op een kritieke kolom) en
 *       een prijsafwijking.</li>
 *   <li>De teller is <b>ongecapt</b>: bij een voorbeeldcap van 6 en 25 kritieke regels staat er 25 op de
 *       batch en zijn er 6 voorbeeldrijen bewaard.</li>
 *   <li>{@code null} = niet vastgesteld: een technisch mislukte batch heeft {@code null}; een levering
 *       zonder verworpen regels een vastgestelde 0. Een hervatte batch telt niet dubbel.</li>
 * </ul>
 * De microbatch- en chunkgrootte staan klein, zodat elk scenario meerdere commits doorloopt. Codes zijn
 * per test uniek omdat de H2-database gedeeld is.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=4",
        "catalogimport.screening.mutation-chunk-size=4",
        "catalogimport.screening.max-sample-rows-per-code=6",
        "catalogimport.screening.recovery-on-startup=false"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class CriticalLineCountTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING;BARCODE\n";

    /** De barcode mag hoogstens 8 tekens lang zijn; negen tekens verwerpt de regel. */
    private static final String TOO_LONG_BARCODE = "123456789";

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @MockitoSpyBean
    private MutationDao mutations;
    @MockitoSpyBean
    private CandidateStageDao stage;
    @Autowired
    private DeliveryIntakeService intake;
    @Autowired
    private DeliveryScreeningService screening;
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
    private ImportRecordFilterRepository recordFilters;
    @Autowired
    private ImportRevisionFieldCriticalityRepository fieldCriticalities;
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
    @Autowired
    private MockMvc mockMvc;

    // --- De kern: kritieke kolom versus niet-kritieke kolom ------------------------------------------

    @Test
    void countsOnlyTheRejectedLinesWithAnErrorOnACriticalColumn() {
        StringBuilder csv = new StringBuilder(HEADER);
        // Drie onleesbare prijzen: de basisprijs is standaard kritiek.
        for (int i = 1; i <= 3; i++) {
            csv.append("ACME;G1;C").append(i).append(";onleesbaar;Boormachine;1234567\n");
        }
        // Vijf te lange barcodes: het gemapte veld is standaard niet-kritiek.
        for (int i = 1; i <= 5; i++) {
            csv.append("ACME;G1;N").append(i).append(";1,50;Boormachine;").append(TOO_LONG_BARCODE).append('\n');
        }
        for (int i = 1; i <= 4; i++) {
            csv.append("ACME;G1;V").append(i).append(";1,50;Boormachine;1234567\n");
        }
        Fixture fixture = fixture("CORE");

        ScreeningOutcome outcome = screen(fixture, "REF-1", csv.toString());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.criticalLineCount()).isEqualTo(3L);
        assertThat(outcome.rejectedRecordCount()).isEqualTo(8L);
        assertThat(outcome.validRecordCount()).isEqualTo(4L);
        assertThat(outcome.rawRecordCount()).isEqualTo(12L);
        ImportBatch batch = batches.findById(outcome.batchId()).orElseThrow();
        assertThat(batch.getCriticalLineCount()).isEqualTo(3L);
        // Een kritieke lijn staat nooit in de stage: het record is tijdens het stagen verworpen.
        assertThat(jdbc.queryForObject("select count(*) from import_candidate_stage where batch_id = ?",
                Long.class, outcome.batchId())).isEqualTo(4L);
        // Strikt disjunct met de vastgehouden identiteitsincidenten.
        assertThat(outcome.identityIncidentCount()).isZero();
        // De rest van de verwerking is ongewijzigd: enkel de vier geldige regels leveren mutaties.
        assertThat(outcome.contentMutationCount()).isEqualTo(4L);
    }

    @Test
    void countsARejectedLineWhenItsMappedColumnIsMarkedCritical() {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 2; i++) {
            csv.append("ACME;G1;C").append(i).append(";1,50;Boormachine;").append(TOO_LONG_BARCODE).append('\n');
        }
        csv.append("ACME;G1;V1;1,50;Boormachine;1234567\n");
        Fixture fixture = fixture("MAPCRIT", revision -> {
        }, mapping -> mapping.setCriticality(Criticality.CRITICAL), revision -> {
        });

        ScreeningOutcome outcome = screen(fixture, "REF-1", csv.toString());

        // De melding draagt de logische veldnaam van het gemapte veld; die staat in de kritiek-map.
        assertThat(issueFields(outcome.batchId(), FieldValueMapper.CODE_VALUE_TOO_LONG))
                .containsExactly("Leveranciersbarcode");
        assertThat(outcome.criticalLineCount()).isEqualTo(2L);
        assertThat(outcome.rejectedRecordCount()).isEqualTo(2L);
    }

    @Test
    void doesNotCountAnUnreadablePriceWhenTheBasePriceIsOverruledToNonCritical() {
        Fixture fixture = fixture("PRICEOVR", revision -> {
        }, mapping -> {
        }, revision -> fieldCriticalities.saveAndFlush(
                new ImportRevisionFieldCriticality(revision.getId(), "BASE_PRICE", Criticality.NON_CRITICAL)));
        String csv = HEADER
                + "ACME;G1;R1;onleesbaar;Boormachine;1234567\n"
                + "ACME;G1;R2;;Boormachine;1234567\n"
                + "ACME;G1;R3;1,50;Boormachine;1234567\n";

        ScreeningOutcome outcome = screen(fixture, "REF-1", csv);

        // Wel verworpen, maar niet kritiek: de beheerder heeft de basisprijs niet-kritiek verklaard.
        assertThat(outcome.rejectedRecordCount()).isEqualTo(2L);
        assertThat(outcome.criticalLineCount()).isZero();
        assertThat(issueCodes(outcome.batchId())).contains(ImportValueRules.CODE_PRICE_UNREADABLE,
                ImportValueRules.CODE_PRICE_MISSING);
    }

    @Test
    void countsAPriceErrorOnTheRevisionOwnFieldsAsCriticalByDefault() {
        String csv = HEADER
                + "ACME;G1;R1;onleesbaar;Boormachine;1234567\n"
                + "ACME;G1;R2;0;Boormachine;1234567\n"
                + "ACME;G1;R3;1,50;Boormachine;1234567\n";

        ScreeningOutcome outcome = screen(fixture("PRICEDEF"), "REF-1", csv);

        assertThat(outcome.rejectedRecordCount()).isEqualTo(2L);
        assertThat(outcome.criticalLineCount()).isEqualTo(2L);
    }

    // --- Onherleidbare fouten -----------------------------------------------------------------------

    @Test
    void countsUnrecoverableParseErrorsAsCritical() {
        String csv = HEADER
                + "ACME;G1;R1;1,50\n"
                + "\"ACME;G1;R2;1,50;Boormachine;1234567\n"
                + "ACME;G1;R3;1,50;Boormachine;1234567\n";

        ScreeningOutcome outcome = screen(fixture("PARSE"), "REF-1", csv);

        assertThat(issueCodes(outcome.batchId())).contains(CsvRecordStreamer.CODE_ROW_COLUMN_COUNT_MISMATCH,
                ImportValueRules.CODE_CSV_UNCLOSED_QUOTE);
        assertThat(outcome.rejectedRecordCount()).isEqualTo(2L);
        assertThat(outcome.criticalLineCount()).isEqualTo(2L);
        assertThat(outcome.validRecordCount()).isEqualTo(1L);
    }

    @Test
    void countsAnEmptyIdentityComponentAsCritical() {
        String csv = HEADER
                + "ACME;;R1;1,50;Boormachine;1234567\n"
                + " ;G1;R2;1,50;Boormachine;1234567\n"
                + "ACME;G1;R3;1,50;Boormachine;1234567\n";

        ScreeningOutcome outcome = screen(fixture("IDENT"), "REF-1", csv);

        // Naast de regelfouten staat er sinds bouwstap 3h-3 één melding over de initialisatie: de
        // koppeling had nog geen enkele aanvaarde aanbieding, dus de creaties wachten op goedkeuring.
        assertThat(issueCodes(outcome.batchId())).containsOnly(
                CandidateNormaliser.CODE_IDENTITY_COMPONENT_EMPTY,
                ImportIssueCatalog.INITIAL_LOAD_REQUIRES_APPROVAL);
        assertThat(outcome.rejectedRecordCount()).isEqualTo(2L);
        assertThat(outcome.criticalLineCount()).isEqualTo(2L);
    }

    // --- Nooit kritiek --------------------------------------------------------------------------------

    /**
     * Een door de beheerder verklaarde REJECT is geen kritieke lijn, ook al verwerpt hij de regel. Een
     * onleesbare regel vóór het filter blijft dat wél: met geconfigureerde filters telt ze in
     * {@code error_before_filter_count} maar niet in {@code rejected_record_count}, en (onherleidbaar,
     * dus fail-safe kritiek) wel als kritieke lijn.
     */
    @Test
    void neverCountsARecordRejectedByAFilterButStillCountsAnUnparseableLine() {
        Fixture fixture = fixture("FILTER", revision -> {
        }, mapping -> {
        }, revision -> {
            ImportRecordFilter reject = new ImportRecordFilter(revision, 1, "OMSCHRIJVING",
                    FilterOperator.EQUALS, "Weg", FilterOutcome.REJECT);
            reject.setNullBehaviour(FilterNullBehaviour.COMPARE_AS_EMPTY);
            recordFilters.saveAndFlush(reject);
        });
        String csv = HEADER
                + "ACME;G1;R1;1,50;Weg;1234567\n"
                + "ACME;G1;R2;1,50;Weg;1234567\n"
                + "ACME;G1;R3;1,50;Boormachine;1234567\n";

        ScreeningOutcome rejectedOnly = screen(fixture, "REF-1", csv);

        assertThat(issueCodes(rejectedOnly.batchId()))
                .containsOnly(RecordFilterEvaluator.CODE_FILTER_RECORD_REJECTED,
                        // Eerste levering van de koppeling (bouwstap 3h-3); verandert niets aan de
                        // telling van de kritieke lijnen.
                        ImportIssueCatalog.INITIAL_LOAD_REQUIRES_APPROVAL);
        assertThat(rejectedOnly.rejectedRecordCount()).isEqualTo(2L);
        assertThat(rejectedOnly.criticalLineCount()).isZero();

        ScreeningOutcome withParseError = screen(fixture, "REF-2", csv + "ACME;G1;R9;1,50\n");

        assertThat(withParseError.rejectedRecordCount()).isEqualTo(2L);
        assertThat(withParseError.errorBeforeFilterCount()).isEqualTo(1L);
        assertThat(withParseError.criticalLineCount()).isEqualTo(1L);
    }

    /**
     * Een WARNING telt nooit, ook niet op een kritieke kolom. De headerwaarschuwing over een verschoven
     * kolom draagt de kolomnaam uit de bron ({@code BARCODE}), die niet in de kritiek-map staat en dus
     * fail-safe kritiek is: dat maakt haar geen kritieke lijn omdat de ernst geen ERROR is.
     */
    @Test
    void neverCountsAWarningNotEvenOnAColumnThatCountsAsCritical() {
        Fixture fixture = fixture("WARN", revision -> {
        }, mapping -> {
            mapping.setCriticality(Criticality.CRITICAL);
            mapping.setExpectedPosition(3);
        }, revision -> {
        });
        String csv = HEADER
                + "ACME;G1;R1;1,50;Boormachine;1234567\n"
                + "ACME;G1;R2;1,50;Boormachine;1234567\n";

        ScreeningOutcome outcome = screen(fixture, "REF-1", csv);

        assertThat(issueCodes(outcome.batchId())).contains(CsvRecordStreamer.CODE_HEADER_FIELD_SHIFTED);
        // Enkel de meldingen met een regelnummer: de initialisatiemelding van bouwstap 3h-3 hoort bij
        // de levering als geheel en is (tussenstand 3h-3) van ernst BLOCKING.
        assertThat(rowSeverities(outcome.batchId())).containsOnly(RowIssueSeverity.WARNING.name());
        assertThat(outcome.rejectedRecordCount()).isZero();
        assertThat(outcome.criticalLineCount()).isZero();
    }

    // --- De regel zelf (ontdubbeling, severity, filtercode) --------------------------------------------

    @Test
    void countsTwoErrorsOnTheSameLineAsOneCriticalLine() {
        ImportMappingConfig config = new ImportMappingConfig(2, List.of(), List.of(),
                Map.of("PRIJS", Criticality.CRITICAL, "GROEP", Criticality.CRITICAL,
                        "OMSCHRIJVING", Criticality.NON_CRITICAL));
        CriticalLineCounter counter = new CriticalLineCounter();

        assertThat(counter.record(config, 5, RowIssueSeverity.ERROR, "PRICE_UNREADABLE", "PRIJS")).isTrue();
        // Dezelfde regel, tweede kritieke ERROR: dezelfde lijn.
        assertThat(counter.record(config, 5, RowIssueSeverity.ERROR, "IDENTITY_COMPONENT_EMPTY", "GROEP"))
                .isFalse();
        assertThat(counter.count()).isEqualTo(1L);
        // Een niet-kritieke ERROR op een andere regel telt niet, ook niet als eerste van die regel...
        assertThat(counter.record(config, 6, RowIssueSeverity.ERROR, "VALUE_TOO_LONG", "OMSCHRIJVING"))
                .isFalse();
        // ...en een kritieke ERROR daarna op dezelfde regel telt dan wel, maar één keer.
        assertThat(counter.record(config, 6, RowIssueSeverity.ERROR, "PRICE_UNREADABLE", "PRIJS")).isTrue();
        assertThat(counter.record(config, 6, RowIssueSeverity.ERROR, "PRICE_MISSING", "PRIJS")).isFalse();
        assertThat(counter.count()).isEqualTo(2L);
    }

    @Test
    void neverCountsAWarningAnInfoAFilterRejectionOrAPriceDeviation() {
        ImportMappingConfig config = new ImportMappingConfig(2, List.of(), List.of(),
                Map.of("PRIJS", Criticality.CRITICAL));
        CriticalLineCounter counter = new CriticalLineCounter();

        assertThat(counter.record(config, 1, RowIssueSeverity.WARNING, "HEADER_FIELD_SHIFTED", "PRIJS"))
                .isFalse();
        assertThat(counter.record(config, 2, RowIssueSeverity.INFO, "VALUE_DEFAULT_APPLIED", "PRIJS"))
                .isFalse();
        assertThat(counter.record(config, 3, RowIssueSeverity.ERROR,
                RecordFilterEvaluator.CODE_FILTER_RECORD_REJECTED, "PRIJS")).isFalse();
        // Een prijsafwijking is WARNING (of, per revisie, ERROR) en verwerpt niets; ze draait bovendien
        // buiten de staging. Ook een op ERROR verzwaarde afwijking is geen verworpen regel.
        assertThat(counter.record(config, 4, RowIssueSeverity.WARNING,
                PriceDeviationEvaluator.CODE_PRICE_DEVIATION_EXCEEDED, "PRIJS")).isFalse();
        assertThat(counter.count()).isZero();
    }

    @Test
    void treatsAnUnknownOrMissingFieldAsCriticalFailSafe() {
        ImportMappingConfig config = new ImportMappingConfig(2, List.of(), List.of(),
                Map.of("OMSCHRIJVING", Criticality.NON_CRITICAL));
        CriticalLineCounter counter = new CriticalLineCounter();

        assertThat(counter.record(config, 1, RowIssueSeverity.ERROR, "ROW_COLUMN_COUNT_MISMATCH", null))
                .isTrue();
        assertThat(counter.record(config, 2, RowIssueSeverity.ERROR, "SOMETHING", "ONBEKEND")).isTrue();
        assertThat(counter.record(null, 3, RowIssueSeverity.ERROR, "SOMETHING", "OMSCHRIJVING")).isTrue();
        assertThat(counter.count()).isEqualTo(3L);
    }

    // --- Ongecapt --------------------------------------------------------------------------------------

    @Test
    void keepsTheCounterUncappedWhileTheSampleRowsAreCapped() {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 25; i++) {
            csv.append("ACME;G1;C").append(i).append(";onleesbaar;Boormachine;1234567\n");
        }
        for (int i = 1; i <= 3; i++) {
            csv.append("ACME;G1;V").append(i).append(";1,50;Boormachine;1234567\n");
        }

        ScreeningOutcome outcome = screen(fixture("CAP"), "REF-1", csv.toString());

        assertThat(outcome.criticalLineCount()).isEqualTo(25L);
        assertThat(outcome.rejectedRecordCount()).isEqualTo(25L);
        // De voorbeeldcap (hier 6) bepaalt enkel wat er bewaard blijft, nooit de teller.
        assertThat(jdbc.queryForObject("select count(*) from import_row_issue where batch_id = ? "
                        + "and issue_code = ?", Long.class, outcome.batchId(),
                ImportValueRules.CODE_PRICE_UNREADABLE)).isEqualTo(6L);
    }

    // --- null versus 0 ---------------------------------------------------------------------------------

    @Test
    void recordsAnEstablishedZeroForADeliveryWithoutRejectedLines() {
        ScreeningOutcome outcome = screen(fixture("ZERO"), "REF-1", HEADER
                + "ACME;G1;R1;1,50;Boormachine;1234567\n"
                + "ACME;G1;R2;2,25;Hamer;2234567\n");

        assertThat(outcome.criticalLineCount()).isNotNull().isZero();
        assertThat(batches.findById(outcome.batchId()).orElseThrow().getCriticalLineCount())
                .isNotNull().isZero();
    }

    @Test
    void leavesTheCounterUnknownWhenTheScreeningFailsTechnically() {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 3; i++) {
            csv.append("ACME;G1;C").append(i).append(";onleesbaar;Boormachine;1234567\n");
        }
        for (int i = 1; i <= 12; i++) {
            csv.append("ACME;G1;V").append(i).append(";1,50;Boormachine;1234567\n");
        }
        Delivered delivered = deliver(fixture("FAILED"), "REF-1", csv.toString());
        AtomicInteger microBatch = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
            if (microBatch.incrementAndGet() == 2) {
                throw new UncheckedIOException(new IOException("simulated crash while staging"));
            }
            return invocation.callRealMethod();
        }).when(stage).insertBatch(any());

        try {
            screening.screen(delivered.batchId());
            throw new AssertionError("the simulated crash did not propagate");
        } catch (UncheckedIOException expected) {
            assertThat(expected).hasRootCauseInstanceOf(IOException.class);
        }

        // Er waren al drie kritieke lijnen vastgesteld, maar de batch is FAILED: er is niets vastgesteld,
        // en dat wordt nooit een stille 0 of een halve telling.
        ImportBatch failed = batches.findById(delivered.batchId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(ImportBatchStatus.FAILED);
        assertThat(failed.getCriticalLineCount()).isNull();
        assertThat(failed.getRejectedRecordCount()).isNull();
    }

    @Test
    void doesNotCountTwiceWhenTheBatchIsResumedAfterACrashInTheMutationPhase() {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 3; i++) {
            csv.append("ACME;G1;C").append(i).append(";onleesbaar;Boormachine;1234567\n");
        }
        for (int i = 1; i <= 12; i++) {
            csv.append("ACME;G1;V").append(i).append(";1,50;Boormachine;1234567\n");
        }
        Delivered delivered = deliver(fixture("RESUME"), "REF-1", csv.toString());
        AtomicInteger chunk = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
            if (chunk.incrementAndGet() == 2) {
                throw new UncheckedIOException(new IOException("simulated crash halfway the generation"));
            }
            return invocation.callRealMethod();
        }).when(mutations).insertContentMutations(any(), any(), any(), anyLong(), anyLong(), any());

        try {
            screening.screen(delivered.batchId());
            throw new AssertionError("the simulated crash did not propagate");
        } catch (UncheckedIOException expected) {
            assertThat(expected).hasRootCauseInstanceOf(IOException.class);
        }

        // De stagingfase is klaar en haar tellers zijn samen met de rest vastgelegd.
        ImportBatch interrupted = batches.findById(delivered.batchId()).orElseThrow();
        assertThat(interrupted.getStatus()).isEqualTo(ImportBatchStatus.MUTATING);
        assertThat(interrupted.getCriticalLineCount()).isEqualTo(3L);
        assertThat(interrupted.getRejectedRecordCount()).isEqualTo(3L);

        Mockito.reset(mutations);
        ScreeningOutcome outcome = screening.continueMutating(delivered.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.criticalLineCount()).isEqualTo(3L);
        assertThat(outcome.rejectedRecordCount()).isEqualTo(3L);
        assertThat(batches.findById(delivered.batchId()).orElseThrow().getCriticalLineCount()).isEqualTo(3L);
    }

    @Test
    void keepsTheEstablishedCountWhenTheBatchIsBlockedAfterReading() {
        // Twee dezelfde identiteiten blokkeren de levering ná het lezen; de kritieke lijn is dan al
        // vastgesteld en blijft staan.
        String csv = HEADER
                + "ACME;G1;C1;onleesbaar;Boormachine;1234567\n"
                + "ACME;G1;D1;1,50;Boormachine;1234567\n"
                + "ACME;G1;D1;1,50;Boormachine;1234567\n";

        ScreeningOutcome outcome = screen(fixture("BLOCKED"), "REF-1", csv);

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(outcome.blockedCode()).isEqualTo(DeliveryScreeningService.CODE_DUPLICATE_IDENTITY_IN_DELIVERY);
        assertThat(outcome.criticalLineCount()).isEqualTo(1L);
    }

    // --- Weergave ---------------------------------------------------------------------------------------

    @Test
    void exposesTheCounterOnTheBatchAndDeliveryEndpoints() throws Exception {
        String csv = HEADER
                + "ACME;G1;C1;onleesbaar;Boormachine;1234567\n"
                + "ACME;G1;C2;onleesbaar;Boormachine;1234567\n"
                + "ACME;G1;N1;1,50;Boormachine;" + TOO_LONG_BARCODE + "\n"
                + "ACME;G1;V1;1,50;Boormachine;1234567\n";
        Fixture fixture = fixture("HTTP");
        Delivered delivered = deliver(fixture, "REF-1", csv);

        // Vóór de screening is er niets vastgesteld: null, nooit 0.
        mockMvc.perform(get("/api/catalog-import/batches/{id}", delivered.batchId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.criticalLineCount").value((Object) null));

        screening.screen(delivered.batchId());

        mockMvc.perform(get("/api/catalog-import/batches/{id}", delivered.batchId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.criticalLineCount").value(2))
                .andExpect(jsonPath("$.rejectedRecordCount").value(3));
        mockMvc.perform(get("/api/catalog-import/deliveries/{id}", delivered.deliveryId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batch.criticalLineCount").value(2))
                .andExpect(jsonPath("$.batch.rejectedRecordCount").value(3));
    }

    // --- Helpers ----------------------------------------------------------------------------------------

    private List<String> issueCodes(long batchId) {
        return jdbc.queryForList("select distinct issue_code from import_row_issue where batch_id = ? "
                + "order by issue_code", String.class, batchId);
    }

    private List<String> issueFields(long batchId, String issueCode) {
        return jdbc.queryForList("select distinct field_name from import_row_issue where batch_id = ? "
                + "and issue_code = ?", String.class, batchId, issueCode);
    }

    /** De ernst van de meldingen die aan een bronregel hangen; leveringsmeldingen tellen niet mee. */
    private List<String> rowSeverities(long batchId) {
        return jdbc.queryForList("select distinct severity from import_row_issue where batch_id = ? "
                + "and row_number is not null", String.class, batchId);
    }

    private ScreeningOutcome screen(Fixture fixture, String reference, String csv) {
        return screening.screen(deliver(fixture, reference, csv).batchId());
    }

    private Delivered deliver(Fixture fixture, String reference, String csv) {
        DeliveryView view = intake.intake(fixture.taskId(), reference, "tester@example.test", null, null,
                "levering.csv", new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))).delivery();
        return new Delivered(deliveries.findById(view.deliveryId()).orElseThrow().getId(),
                view.batch().batchId());
    }

    private Fixture fixture(String prefix) {
        return fixture(prefix, revision -> {
        }, mapping -> {
        }, revision -> {
        });
    }

    /**
     * Een revisie op canonicalisatieversie 2 met één gemapt catalogusveld ({@code SUPPLIER_BARCODE}, hoogstens
     * acht tekens, standaard niet-kritiek).
     *
     * @param mappingTweak hook op de mapping vóór ze bewaard wordt
     * @param afterSave    hook op de bewaarde revisie (recordfilters, kritiek-overrules)
     */
    private Fixture fixture(String prefix, Consumer<ImportDefinitionRevision> beforeSave,
                            Consumer<ImportFieldMapping> mappingTweak,
                            Consumer<ImportDefinitionRevision> afterSave) {
        String unique = "CL" + SEQUENCE.incrementAndGet() + "-" + prefix;
        SourceOrganisation organisation = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + "-ORG BV", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(new ImportDefinition(organisation,
                unique + "-DEF", unique + " catalogus", "beheerder@example.test"));
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
        // Bouwstap 3h-4: deze test gaat niet over de drempel op de records ter beoordeling. Met de
        // productiedefault van 1% zou een kleine fixture met een enkele kritieke lijn of een
        // vastgehouden identiteit nu geblokkeerd worden; het percentage wordt daarom PER TEST op 100
        // gezet, zodat hier exact het gedrag van vóór bouwstap 3h-4 geldt. De productiedefault zelf
        // blijft 1 procent - ThresholdBlockingTest bewijst die.
        revision.setMaxCriticalSharePercent(new BigDecimal("100"));
        revision.setStatus(RevisionStatus.ACTIVE);
        beforeSave.accept(revision);
        ImportDefinitionRevision stored = revisions.saveAndFlush(revision);

        ImportFieldCatalogEntry target = fieldCatalog.findById("SUPPLIER_BARCODE").orElseThrow();
        ImportFieldMapping mapping = new ImportFieldMapping(stored, 1, target, FieldValueKind.SOURCE_FIELD,
                target.getDataType(), target.getDefaultOwner(), target.getIdentityClass());
        mapping.setSourceReference("BARCODE");
        mapping.setMaxLength(8);
        mappingTweak.accept(mapping);
        fieldMappings.saveAndFlush(mapping);
        afterSave.accept(stored);

        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(new ImportLink(unique + "-LINK", unique + " koppeling", definition,
                supplier, unique.substring(0, Math.min(unique.length(), 20))));
        CatalogImportTask task = tasks.saveAndFlush(
                new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL));
        return new Fixture(task.getId(), link.getId(), stored.getId());
    }

    private record Fixture(long taskId, long linkId, long revisionId) {
    }

    private record Delivered(long deliveryId, long batchId) {
    }
}
