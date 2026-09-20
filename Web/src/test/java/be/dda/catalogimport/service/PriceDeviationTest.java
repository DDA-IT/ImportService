package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.DeliveryRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportFieldCatalogRepository;
import be.dda.catalogimport.dao.ImportFieldMappingRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.PriceDeviationDao;
import be.dda.catalogimport.dao.PriceObservationDao;
import be.dda.catalogimport.dao.PriceObservationDao.ObservationContext;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.FieldValueKind;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportFieldCatalogEntry;
import be.dda.catalogimport.domain.ImportFieldMapping;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.PriceControlModel;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.RowIssueSeverity;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.domain.ValidationResult;
import be.dda.catalogimport.service.DeliveryScreeningService.ScreeningOutcome;
import be.dda.catalogimport.service.support.ImportMappingConfigFactory;
import be.dda.catalogimport.service.support.PriceDeviationEvaluator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Fase 3e (ontwerp fase 3, R-PRI-10..R-PRI-13): de prijshistoriek en de afwijkingscontrole, tegen de
 * echte services, DAO's, {@code accept-baseline} en H2.
 *
 * <h2>Wat hier bewezen wordt</h2>
 * <ul>
 *   <li>Een aanvaarde levering legt per identiteit + component één <b>goedgekeurde dagwaarde</b> vast;
 *       een tweede aanvaarding op dezelfde dag laat de eerste waarde staan (A16).</li>
 *   <li>De drie referenties werken los van elkaar: met een historiek van vier dagen geven het korte
 *       en het lange venster <b>verschillende</b> gemiddelden en dus verschillende afwijkingen.</li>
 *   <li>Zonder historiek is alleen de vorige waarde bruikbaar en volgt er precies één
 *       <b>samenvattende</b> INFO-melding — nooit één per record.</li>
 *   <li>Een overschrijding <b>wijzigt niets</b>: bedrag, percentage en mutatie zijn byte voor byte
 *       gelijk aan die van dezelfde levering zonder anomalie (R-PRI-12).</li>
 *   <li>Een revisie mag de melding op {@code ERROR} zetten; het record wordt daar niet door verworpen.</li>
 *   <li>{@code BOXPLOT} wordt nog steeds geweigerd, vóór er één byte gelezen is.</li>
 *   <li>De prijscontrolepass is afzonderlijk hervatbaar: na een crash levert het hervatte deel geen
 *       tweede melding op voor een al beoordeelde regel.</li>
 * </ul>
 * De microbatch-, mutatie- én prijscontrolegrootte staan op 2, zodat elk scenario meerdere
 * chunk-commits doorloopt. De vensters staan per revisie op 2 en 4 in plaats van 50 en 200: dat is
 * exact hetzelfde mechanisme met een historiek die in een test te bouwen is. De klok is stuurbaar,
 * want de historiek telt in kalenderdagen (UTC).
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=2",
        "catalogimport.screening.mutation-chunk-size=2",
        "catalogimport.screening.price-control-chunk-size=2",
        "catalogimport.screening.recovery-on-startup=false"})
@ActiveProfiles("local")
class PriceDeviationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    private static final String COMPONENT_HEADER =
            "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING;AKP\n";

    private static final TestClock CLOCK = new TestClock();

    /**
     * De klok van de applicatie wordt overschreven zodat de kalenderdag van een observatie stuurbaar
     * is. In productie is dat {@code Clock.systemUTC()} (zie {@code TimeConfiguration}).
     */
    @TestConfiguration
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @Autowired
    private DeliveryIntakeService intake;
    @Autowired
    private DeliveryScreeningService screening;
    @Autowired
    private SourceStateBaselineService baseline;
    @Autowired
    private PriceObservationDao priceObservations;
    @MockitoSpyBean
    private PriceDeviationDao deviations;
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

    // --- De historiek zelf (R-PRI-13) ----------------------------------------------------------

    /**
     * Elke aanvaarde NEW- of CHANGED-regel legt één dagwaarde vast; een UNCHANGED-regel niet. Zo
     * lopen de gemiddelden over de laatste N <b>vastgelegde goedgekeurde</b> waarden.
     */
    @Test
    void recordsOneApprovedDayValuePerAcceptedNewOrChangedRowAndNoneForUnchangedRows() {
        Fixture fixture = fixture("HIST", false, revision -> {
        });
        CLOCK.moveTo("2026-09-01");
        screenAndAccept(fixture, "REF-1", csv(HEADER, "ACME;G1;R1;100,00;Boormachine",
                "ACME;G1;R2;200,00;Schroevendraaier"));

        assertThat(observations(fixture, "R1")).containsExactly("2026-09-01=100.000000");
        assertThat(observations(fixture, "R2")).containsExactly("2026-09-01=200.000000");

        // Volgende dag: R1 wijzigt, R2 niet.
        CLOCK.moveTo("2026-09-02");
        screenAndAccept(fixture, "REF-2", csv(HEADER, "ACME;G1;R1;110,00;Boormachine",
                "ACME;G1;R2;200,00;Schroevendraaier"));

        assertThat(observations(fixture, "R1"))
                .containsExactly("2026-09-01=100.000000", "2026-09-02=110.000000");
        // Ongewijzigd: geen tweede dagwaarde. De historiek bevat wijzigingen, geen kalenderdagen.
        assertThat(observations(fixture, "R2")).containsExactly("2026-09-01=200.000000");
    }

    /** A16: twee aanvaardingen op dezelfde kalenderdag laten de eerste waarde staan. */
    @Test
    void keepsTheFirstValueOfTheDayWhenTwoDeliveriesAreAcceptedOnTheSameDay() {
        Fixture fixture = fixture("SAMEDAY", false, revision -> {
        });
        CLOCK.moveTo("2026-09-01");
        screenAndAccept(fixture, "REF-1", csv(HEADER, "ACME;G1;R1;100,00;Boormachine"));
        screenAndAccept(fixture, "REF-2", csv(HEADER, "ACME;G1;R1;150,00;Boormachine"));

        // Eén rij voor die dag, en het is de eerst vastgelegde waarde - nooit achteraf herschreven.
        assertThat(observations(fixture, "R1")).containsExactly("2026-09-01=100.000000");
        // De bronstaat volgt de levering wél: die draagt de laatste aanvaarde waarde.
        assertThat(basePriceInSourceState(fixture, "R1")).isEqualByComparingTo("150.00");
    }

    /** Een herhaalde (of hervatte) acceptatie schrijft geen tweede observatie. */
    @Test
    void repeatingTheAcceptanceChunkWritesNoSecondObservation() {
        Fixture fixture = fixture("OBSIDEM", false, revision -> {
        });
        CLOCK.moveTo("2026-09-01");
        Delivered delivered = screenAndAccept(fixture, "REF-1",
                csv(HEADER, "ACME;G1;R1;100,00;Boormachine", "ACME;G1;R2;200,00;Schroevendraaier"));
        long before = priceObservations.countByImportLinkId(fixture.linkId());

        // Exact wat een hervatte acceptatie doet: dezelfde chunk nog een keer.
        ObservationContext context = new ObservationContext(fixture.linkId(), delivered.batchId(),
                LocalDate.of(2026, 9, 1), "tester@example.test", Instant.now());
        int written = priceObservations.insertBasePriceObservations(context, 0L, 999L);

        assertThat(written).isZero();
        assertThat(priceObservations.countByImportLinkId(fixture.linkId())).isEqualTo(before);
    }

    // --- De drie referenties (R-PRI-10/R-PRI-11) -----------------------------------------------

    /**
     * Vier goedgekeurde dagwaarden (100, 200, 300, 400): het korte venster (2) levert 350, het lange
     * (4) levert 250. Een nieuwe prijs van 420 wijkt 5% af van de vorige waarde (binnen de grens),
     * 20% van het korte en 68% van het lange gemiddelde. Eén melding, met alle drie de afwijkingen.
     */
    @Test
    void comparesAChangedPriceWithThreeSeparateReferences() {
        Fixture fixture = fixture("WINDOW", false, revision -> {
            revision.setPriceAvgShortWindow(2);
            revision.setPriceAvgLongWindow(4);
        });
        buildHistory(fixture, "100,00", "200,00", "300,00", "400,00");

        CLOCK.moveTo("2026-09-05");
        Delivered fifth = deliver(fixture, "REF-5", csv(HEADER, "ACME;G1;R1;420,00;Boormachine"));
        ScreeningOutcome outcome = screening.screen(fifth.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.rejectedRecordCount()).isZero();
        Map<String, Object> issue = singleDeviationIssue(fifth.batchId());
        assertThat(issue.get("severity")).isEqualTo(RowIssueSeverity.WARNING.name());
        assertThat(issue.get("row_number")).isEqualTo(2L);
        assertThat(issue.get("field_name")).isEqualTo("Basisprijs");
        assertThat(issue.get("source_value")).isEqualTo("420.000000");
        // Oude waarden en grens blijven machineleesbaar bewaard (R-PRI-12).
        assertThat(issue.get("expected_value")).isEqualTo(
                "PREVIOUS=400.000000;AVG50=350.000000;AVG200=250.000000;limit=15.000000000000");
        assertThat((String) issue.get("message"))
                .startsWith("Basisprijs: '420.000000' deviates ")
                // Het korte venster is de eerste referentie die de grens overschrijdt.
                .contains("20.000000000000% from the average of the last 2 approved values 350.000000")
                .contains("(limit 15.000000000000%)")
                // ... en binnen de grens t.o.v. de vorige waarde; dat blijft zichtbaar.
                .contains("the previous accepted value 400.000000 -> 5.000000000000%")
                .contains("the average of the last 4 approved values 250.000000 -> 68.000000000000%");
    }

    /**
     * Een koppeling met een bronstaat maar zonder historiek (bv. een nulmeting van vóór deze
     * bouwstap): alleen de vorige waarde is bruikbaar. De ontbrekende gemiddelden leveren géén
     * melding per record op, maar precies één samenvattende INFO-melding op leveringsniveau.
     */
    @Test
    void usesOnlyThePreviousValueWithoutHistoryAndSummarisesTheMissingReferencesOnce() {
        Fixture fixture = fixture("NOHIST", false, revision -> {
        });
        CLOCK.moveTo("2026-09-01");
        screenAndAccept(fixture, "REF-1", csv(HEADER, "ACME;G1;R1;100,00;Boormachine",
                "ACME;G1;R2;200,00;Schroevendraaier", "ACME;G1;R3;300,00;Hamer"));
        // Wis de historiek: zo ziet deze koppeling eruit als een nulmeting van vóór bouwstap 3e.
        jdbc.update("delete from catalog_price_observation where import_link_id = ?", fixture.linkId());

        CLOCK.moveTo("2026-09-02");
        Delivered second = deliver(fixture, "REF-2", csv(HEADER, "ACME;G1;R1;100,00;Boormachine",
                "ACME;G1;R2;400,00;Schroevendraaier", "ACME;G1;R3;300,00;Hamer"));
        ScreeningOutcome outcome = screening.screen(second.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        // Alleen de gewijzigde regel wordt beoordeeld: 200 -> 400 is 100% afwijking.
        Map<String, Object> deviation = singleDeviationIssue(second.batchId());
        assertThat((String) deviation.get("message"))
                .contains("100.000000000000% from the previous accepted value 200.000000")
                .contains("AVG50_NOT_AVAILABLE")
                .contains("AVG200_NOT_AVAILABLE");
        assertThat(deviation.get("expected_value"))
                .isEqualTo("PREVIOUS=200.000000;AVG50=;AVG200=;limit=15.000000000000");

        // Eén samenvattende melding voor de hele levering, zonder regelnummer.
        List<Map<String, Object>> summaries = issues(second.batchId(),
                PriceDeviationEvaluator.CODE_PRICE_REFERENCE_NOT_AVAILABLE);
        assertThat(summaries).singleElement().satisfies(summary -> {
            assertThat(summary.get("severity")).isEqualTo(RowIssueSeverity.INFO.name());
            assertThat(summary.get("row_number")).isNull();
            assertThat((String) summary.get("message"))
                    .contains("PREVIOUS_NOT_AVAILABLE=0")
                    .contains("AVG50_NOT_AVAILABLE=1")
                    .contains("AVG200_NOT_AVAILABLE=1")
                    .contains("no price or percentage was changed");
        });
    }

    /** Een eerste levering heeft geen bronstaat: geen enkele melding en geen extra werk. */
    @Test
    void writesNoPriceIssueAtAllForTheFirstDeliveryOfALink() {
        Fixture fixture = fixture("FIRST", false, revision -> {
        });
        CLOCK.moveTo("2026-09-01");
        Delivered delivered = deliver(fixture, "REF-1", csv(HEADER, "ACME;G1;R1;100,00;Boormachine"));

        ScreeningOutcome outcome = screening.screen(delivered.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.validationResult()).isEqualTo(ValidationResult.VALID);
        assertThat(jdbc.queryForObject("select count(*) from import_row_issue where batch_id = ?",
                Long.class, delivered.batchId())).isZero();
        // De pass heeft zelfs geen chunk gelezen: zonder bronstaat valt er niets te vergelijken.
        Mockito.verify(deviations, Mockito.never())
                .findCandidates(anyLong(), anyLong(), anyInt(), anyInt(), anyLong(), anyLong());
    }

    // --- De anomalie wijzigt niets (R-PRI-12) --------------------------------------------------

    /**
     * Dezelfde levering met en zonder anomalie: alleen de melding verschilt. Bedragen, percentages,
     * het domeinmasker en de mutatiestatus zijn identiek — een afwijking is een vaststelling, geen
     * correctie.
     */
    @Test
    void anExceededDeviationChangesNoAmountNoPercentageAndNoMutation() {
        Fixture flagged = fixture("NOCHG", true, revision -> {
        });
        // Dezelfde revisie, maar met een grens die niets meldt: de vergelijkingsbasis.
        Fixture tolerant = fixture("NOCHGREF", true,
                revision -> revision.setPriceDeviationPercent(new BigDecimal("500")));

        CLOCK.moveTo("2026-09-01");
        screenAndAccept(flagged, "REF-1", csv(COMPONENT_HEADER, "ACME;G1;R1;100,00;Boormachine;80,00"));
        screenAndAccept(tolerant, "REF-1", csv(COMPONENT_HEADER, "ACME;G1;R1;100,00;Boormachine;80,00"));

        CLOCK.moveTo("2026-09-02");
        Delivered flaggedSecond = deliver(flagged, "REF-2",
                csv(COMPONENT_HEADER, "ACME;G1;R1;100,00;Boormachine;95,00"));
        Delivered tolerantSecond = deliver(tolerant, "REF-2",
                csv(COMPONENT_HEADER, "ACME;G1;R1;100,00;Boormachine;95,00"));
        ScreeningOutcome flaggedOutcome = screening.screen(flaggedSecond.batchId());
        screening.screen(tolerantSecond.batchId());

        // De aankoopprijs stijgt met 18,75%: boven de grens van 15%.
        Map<String, Object> issue = singleDeviationIssue(flaggedSecond.batchId());
        assertThat((String) issue.get("message"))
                .contains("Aankoopprijs in procent van de basisprijs: '95.000000'")
                .contains("18.750000000000% from the previous accepted value 80.000000")
                .contains("(limit 15.000000000000%)");
        assertThat(issues(tolerantSecond.batchId(),
                PriceDeviationEvaluator.CODE_PRICE_DEVIATION_EXCEEDED)).isEmpty();

        // En verder is er niets verschillend: dezelfde bedragen, percentages, munt en status ...
        assertThat(priceRows(flaggedSecond.batchId())).isEqualTo(priceRows(tolerantSecond.batchId()))
                .containsExactly("AKP|95.000000|95.000000000000|OK", "BASE_PRICE|100.000000||OK");
        // ... en exact dezelfde mutatie, nog steeds PLANNED (statuswijzigingen komen in 3h).
        assertThat(mutations(flaggedSecond.batchId())).isEqualTo(mutations(tolerantSecond.batchId()))
                .containsExactly("UPDATE|PRICE:AKP|PLANNED|100.000000|100.000000");
        assertThat(flaggedOutcome.contentMutationCount()).isEqualTo(1L);
        assertThat(flaggedOutcome.rejectedRecordCount()).isZero();
        assertThat(flaggedOutcome.validRecordCount()).isEqualTo(1L);
    }

    /**
     * Per revisie mag de melding zwaarder wegen ({@code price_deviation_severity=ERROR}). Dat is een
     * <b>ernst</b>, geen verwerping: het record blijft geldig, de mutatie wordt gewoon gepland.
     */
    @Test
    void weighsTheDeviationAsAnErrorWhenTheRevisionSaysSoWithoutRejectingTheRecord() {
        Fixture fixture = fixture("SEVERR", false,
                revision -> revision.setPriceDeviationSeverity(RowIssueSeverity.ERROR));
        CLOCK.moveTo("2026-09-01");
        screenAndAccept(fixture, "REF-1", csv(HEADER, "ACME;G1;R1;100,00;Boormachine"));

        CLOCK.moveTo("2026-09-02");
        Delivered second = deliver(fixture, "REF-2", csv(HEADER, "ACME;G1;R1;150,00;Boormachine"));
        ScreeningOutcome outcome = screening.screen(second.batchId());

        assertThat(singleDeviationIssue(second.batchId()).get("severity"))
                .isEqualTo(RowIssueSeverity.ERROR.name());
        // Niet verworpen: de regel is geldig, gestaged en gemuteerd.
        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.validRecordCount()).isEqualTo(1L);
        assertThat(outcome.rejectedRecordCount()).isZero();
        assertThat(outcome.changedCount()).isEqualTo(1L);
        assertThat(outcome.contentMutationCount()).isEqualTo(1L);
        // Vastgelegd zoals R-THR-06 vandaag berekend wordt (bouwstap 3a): ERROR verzwaart het
        // eindoordeel niet. Dat is het openstaande punt dat vóór bouwstap 3h beslist moet worden.
        assertThat(outcome.validationResult()).isEqualTo(ValidationResult.VALID);
    }

    // --- Model en hervatbaarheid ---------------------------------------------------------------

    /** {@code BOXPLOT} staat in het schema maar wordt niet uitgevoerd; de levering blokkeert. */
    @Test
    void stillRefusesARevisionThatAsksForTheBoxplotModel() {
        Fixture fixture = fixture("BOX", false,
                revision -> revision.setPriceControlModel(PriceControlModel.BOXPLOT));
        Delivered delivered = deliver(fixture, "REF-1", csv(HEADER, "ACME;G1;R1;100,00;Boormachine"));

        ScreeningOutcome outcome = screening.screen(delivered.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(outcome.blockedCode())
                .isEqualTo(ImportMappingConfigFactory.CODE_PRICE_CONTROL_MODEL_UNSUPPORTED);
        assertThat(outcome.validationResult()).isEqualTo(ValidationResult.BLOCKING);
        // Geblokkeerd vóór er één byte gelezen is: niets gestaged.
        assertThat(outcome.stagedRowCount()).isZero();
    }

    /**
     * De prijscontrolepass heeft een eigen hervatpunt. Valt ze halverwege weg, dan blijft de batch op
     * {@code MUTATING} staan en beoordeelt {@code continue} alleen het resterende deel — geen tweede
     * melding voor een regel die al beoordeeld was.
     */
    @Test
    void resumesTheInterruptedPriceControlPassWithoutWritingDuplicateIssues() {
        Fixture fixture = fixture("RESUME", false, revision -> {
        });
        CLOCK.moveTo("2026-09-01");
        screenAndAccept(fixture, "REF-1", csv(HEADER, "ACME;G1;R1;100,00;Boormachine",
                "ACME;G1;R2;100,00;Schroevendraaier", "ACME;G1;R3;100,00;Hamer",
                "ACME;G1;R4;100,00;Zaag"));

        CLOCK.moveTo("2026-09-02");
        Delivered second = deliver(fixture, "REF-2", csv(HEADER, "ACME;G1;R1;200,00;Boormachine",
                "ACME;G1;R2;200,00;Schroevendraaier", "ACME;G1;R3;200,00;Hamer",
                "ACME;G1;R4;200,00;Zaag"));
        AtomicInteger chunk = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
            if (chunk.incrementAndGet() == 2) {
                throw new UncheckedIOException(new IOException("simulated crash after the first chunk"));
            }
            return invocation.callRealMethod();
        }).when(deviations).findCandidates(anyLong(), anyLong(), anyInt(), anyInt(), anyLong(), anyLong());

        try {
            screening.screen(second.batchId());
            throw new AssertionError("the simulated crash did not propagate");
        } catch (UncheckedIOException expected) {
            assertThat(expected).hasRootCauseInstanceOf(IOException.class);
        }

        // Chunk 1 (twee regels) is beoordeeld en gecommit, inclusief haar hervatpunt.
        assertThat(deviationRowNumbers(second.batchId())).containsExactly(2L, 3L);
        assertThat(batches.findById(second.batchId()).orElseThrow().getPriceProgressRowNumber())
                .isEqualTo(3L);
        assertThat(batches.findById(second.batchId()).orElseThrow().getStatus())
                .isEqualTo(ImportBatchStatus.MUTATING);

        Mockito.reset(deviations);
        ScreeningOutcome outcome = screening.continueMutating(second.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        // Elke regel exact één melding: het hervatte deel herhaalt niets.
        assertThat(deviationRowNumbers(second.batchId())).containsExactly(2L, 3L, 4L, 5L);
        assertThat(outcome.contentMutationCount()).isEqualTo(4L);
    }

    /**
     * R-ISS-03 geldt ook hier: boven de voorbeeldcap per foutcode worden er geen extra voorbeeldrijen
     * bewaard, maar het aantal gaat niet verloren — het staat in de cap-melding. Zonder die grens zou
     * een leverancier die zijn volledige catalogus 20% duurder maakt, een miljoen identieke
     * prijsmeldingen opleveren.
     */
    @Test
    void stopsKeepingExampleRowsAboveTheCapButStillReportsTheCount() {
        Fixture fixture = fixture("CAP", false, revision -> {
        });
        CLOCK.moveTo("2026-09-01");
        screenAndAccept(fixture, "REF-1", csv(HEADER, "ACME;G1;R1;100,00;Boormachine"));

        CLOCK.moveTo("2026-09-02");
        Delivered second = deliver(fixture, "REF-2", csv(HEADER, "ACME;G1;R1;200,00;Boormachine"));
        // Alsof een eerdere doorloop de cap al volgeschreven had: de pass telt wat er al bewaard is.
        int cap = DeliveryScreeningService.DEFAULT_MAX_SAMPLE_ROWS_PER_CODE;
        for (int sample = 0; sample < cap; sample++) {
            jdbc.update("insert into import_row_issue (batch_id, row_number, issue_code, severity, "
                            + "issue_domain, control_level, impact_scope, handling_status, message, "
                            + "created_at) values (?, ?, ?, 'WARNING', 'PRICE', 'RECORD', 'RECORD', "
                            + "'DETECTED', 'earlier example', ?)",
                    second.batchId(), 1000L + sample,
                    PriceDeviationEvaluator.CODE_PRICE_DEVIATION_EXCEEDED, OffsetDateTime.now());
        }

        screening.screen(second.batchId());

        // Geen 201e voorbeeldrij ...
        assertThat(issues(second.batchId(), PriceDeviationEvaluator.CODE_PRICE_DEVIATION_EXCEEDED))
                .hasSize(cap);
        // ... maar het werkelijke aantal blijft gemeld. Aangepast in bouwstap 3g: de cap-melding komt
        // niet meer van de prijspass zelf (die telde enkel haar eigen doorloop en kon na een
        // hervatting een tweede deelmelding geven) maar van de aggregatiepass E4, met het volledige
        // aantal uit de issuegroep. Eén melding per batch en foutcode.
        assertThat(issues(second.batchId(), "ROW_ISSUE_RECORDING_CAPPED")).singleElement()
                .satisfies(notice -> assertThat((String) notice.get("message"))
                        .contains("rowIssueSamples: '" + cap + "'")
                        .contains(PriceDeviationEvaluator.CODE_PRICE_DEVIATION_EXCEEDED + "=1"));
    }

    // --- Regressie ------------------------------------------------------------------------------

    /**
     * Een revisie zonder gemapte prijscomponenten (canonicalisatieversie 1) blijft werken zoals
     * voordien en krijgt toch een basisprijshistoriek: de afwijkingscontrole op de basisprijs is
     * juist voor die revisies de belangrijkste.
     */
    @Test
    void keepsARevisionWithoutPriceComponentsWorkingAndStillRecordsItsBasePriceHistory() {
        Fixture fixture = fixture("V1", false, revision -> {
        });
        CLOCK.moveTo("2026-09-01");
        Delivered first = screenAndAccept(fixture, "REF-1",
                csv(HEADER, "ACME;G1;R1;1,50;Boormachine", "ACME;G1;R2;2,25;Schroevendraaier"));

        // Geen enkele component- of prijsrij, exact zoals in bouwstap 3d.
        assertThat(jdbc.queryForObject("select count(*) from import_candidate_price where batch_id = ?",
                Long.class, first.batchId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from catalog_price_observation "
                + "where import_link_id = ? and component_code <> 'BASE_PRICE'", Long.class,
                fixture.linkId())).isZero();
        assertThat(priceObservations.countByImportLinkId(fixture.linkId())).isEqualTo(2L);

        CLOCK.moveTo("2026-09-02");
        Delivered second = deliver(fixture, "REF-2",
                csv(HEADER, "ACME;G1;R1;1,55;Boormachine", "ACME;G1;R2;2,25;Schroevendraaier"));
        ScreeningOutcome outcome = screening.screen(second.batchId());

        // 1,50 -> 1,55 is 3,33%: binnen de grens, dus geen melding.
        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.changedCount()).isEqualTo(1L);
        assertThat(issues(second.batchId(), PriceDeviationEvaluator.CODE_PRICE_DEVIATION_EXCEEDED))
                .isEmpty();
        assertThat(outcome.validationResult()).isEqualTo(ValidationResult.VALID);
    }

    // --- Helpers ---------------------------------------------------------------------------------

    /** Legt dag na dag een goedgekeurde waarde vast, telkens met een gewijzigde prijs. */
    private void buildHistory(Fixture fixture, String... pricesPerDay) {
        for (int day = 0; day < pricesPerDay.length; day++) {
            CLOCK.moveTo("2026-09-0" + (day + 1));
            screenAndAccept(fixture, "REF-H" + day,
                    csv(HEADER, "ACME;G1;R1;" + pricesPerDay[day] + ";Boormachine"));
        }
    }

    private Delivered screenAndAccept(Fixture fixture, String reference, byte[] content) {
        Delivered delivered = deliver(fixture, reference, content);
        screening.screen(delivered.batchId());
        baseline.acceptBaseline(delivered.batchId(), "tester@example.test", "nulmeting " + reference);
        return delivered;
    }

    private static byte[] csv(String header, String... rows) {
        return (header + String.join("\n", rows) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** De dagwaarden van één aanbieding, oplopend op datum: {@code <datum>=<bedrag>}. */
    private List<String> observations(Fixture fixture, String reference) {
        return jdbc.queryForList("select observation.observation_date || '=' || observation.amount "
                        + "from catalog_price_observation observation "
                        + "join catalog_source_state state on state.id = observation.source_state_id "
                        + "where observation.import_link_id = ? and observation.component_code = 'BASE_PRICE' "
                        + "and state.identity_supplier_reference = ? order by observation.observation_date",
                String.class, fixture.linkId(), reference);
    }

    private BigDecimal basePriceInSourceState(Fixture fixture, String reference) {
        return jdbc.queryForObject("select base_price from catalog_source_state "
                        + "where import_link_id = ? and identity_supplier_reference = ?",
                BigDecimal.class, fixture.linkId(), reference);
    }

    private List<Map<String, Object>> issues(long batchId, String issueCode) {
        return jdbc.queryForList("select row_number, severity, field_name, source_value, "
                        + "expected_value, message from import_row_issue "
                        + "where batch_id = ? and issue_code = ? order by row_number",
                batchId, issueCode);
    }

    private Map<String, Object> singleDeviationIssue(long batchId) {
        List<Map<String, Object>> found = issues(batchId,
                PriceDeviationEvaluator.CODE_PRICE_DEVIATION_EXCEEDED);
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    private List<Long> deviationRowNumbers(long batchId) {
        return jdbc.queryForList("select row_number from import_row_issue where batch_id = ? "
                        + "and issue_code = ? order by row_number", Long.class, batchId,
                PriceDeviationEvaluator.CODE_PRICE_DEVIATION_EXCEEDED);
    }

    /** De financiële inhoud van de prijscomponenten, zonder id's: {@code code|bedrag|pct|status}. */
    private List<String> priceRows(long batchId) {
        return jdbc.queryForList("select component_code || '|' || source_amount || '|' "
                        + "|| coalesce(cast(percentage as varchar(40)), '') || '|' || status "
                        + "from import_candidate_price where batch_id = ? order by component_code",
                String.class, batchId);
    }

    /** De inhoud van de mutatie, zonder id's: {@code actie|masker|status|voor|na}. */
    private List<String> mutations(long batchId) {
        return jdbc.queryForList("select action_type || '|' || coalesce(domain_mask, '') || '|' "
                        + "|| status || '|' || coalesce(cast(before_base_price as varchar(40)), '') "
                        + "|| '|' || coalesce(cast(after_base_price as varchar(40)), '') "
                        + "from import_mutation where batch_id = ? and action_type <> 'IMPORT_MARKER' "
                        + "order by source_row_number", String.class, batchId);
    }

    private Delivered deliver(Fixture fixture, String reference, byte[] content) {
        DeliveryView view = intake.intake(fixture.taskId(), reference, "tester@example.test", null, null,
                "levering.csv", new ByteArrayInputStream(content)).delivery();
        return new Delivered(deliveries.findById(view.deliveryId()).orElseThrow().getId(),
                view.batch().batchId());
    }

    private Fixture fixture(String prefix, boolean withPriceComponent,
                            Consumer<ImportDefinitionRevision> customiser) {
        String unique = "PD" + SEQUENCE.incrementAndGet() + "-" + prefix;
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
        revision.setStatus(RevisionStatus.ACTIVE);
        if (withPriceComponent) {
            revision.setRecordCanonicalisationVersion(2);
        }
        customiser.accept(revision);
        ImportDefinitionRevision stored = revisions.saveAndFlush(revision);
        if (withPriceComponent) {
            ImportFieldCatalogEntry target = fieldCatalog.findById("AKP_PCT").orElseThrow();
            ImportFieldMapping mapping = new ImportFieldMapping(stored, 1, target,
                    FieldValueKind.SOURCE_FIELD, target.getDataType(), target.getDefaultOwner(),
                    target.getIdentityClass());
            mapping.setSourceReference("AKP");
            mapping.setPriceComponentCode(target.getPriceComponentCode());
            fieldMappings.saveAndFlush(mapping);
        }

        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, "PSARF050"));
        CatalogImportTask task = tasks.saveAndFlush(
                new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL));
        return new Fixture(task.getId(), link.getId(), stored.getId());
    }

    private record Fixture(long taskId, long linkId, long revisionId) {
    }

    private record Delivered(long deliveryId, long batchId) {
    }

    /** Een stuurbare klok op UTC; de kalenderdag van een observatie hangt ervan af (R-PRI-13). */
    private static final class TestClock extends Clock {

        private volatile Instant now = Instant.parse("2026-09-01T09:00:00Z");

        private void moveTo(String isoDate) {
            now = Instant.parse(isoDate + "T09:00:00Z");
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
