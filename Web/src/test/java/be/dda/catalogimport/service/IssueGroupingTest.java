package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.DeliveryFileRepository;
import be.dda.catalogimport.dao.DeliveryRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportFieldCatalogRepository;
import be.dda.catalogimport.dao.ImportFieldMappingRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.IssueGroupDao;
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
import be.dda.catalogimport.domain.IssueIncidentKind;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.domain.ValidationResult;
import be.dda.catalogimport.service.DeliveryScreeningService.ScreeningOutcome;
import be.dda.catalogimport.service.support.CandidateNormaliser;
import be.dda.catalogimport.service.support.ImportIssueCatalog;
import be.dda.catalogimport.service.support.ImportValueRules;
import be.dda.catalogimport.service.support.PriceDeviationEvaluator;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Bouwstap 3g (ontwerp fase 3, pass E4, R-THR-04, R-ISS-03, R-PRI-14, R-REF-07): het groeperen van
 * gelijksoortige vaststellingen en het vaststellen van bulkincidenten, tegen de echte services,
 * DAO's, het archief en H2.
 *
 * <h2>Wat hier bewezen wordt</h2>
 * <ul>
 *   <li>Een groep ontstaat pas vanaf tien gelijke foutsignaturen; daaronder blijven het losse
 *       fouten zonder groep.</li>
 *   <li>Een bulkincident hangt uitsluitend van een <b>percentage</b> van de gecontroleerde scope af
 *       (beslissingslog 20/09): exact op de grens is niet overschreden, en bij een kleine scope
 *       werkt dat percentage grof.</li>
 *   <li><b>De kern:</b> {@code occurrence_count} is het werkelijke aantal en komt niet uit de
 *       bewaarde voorbeeldrijen. Bij 250 identieke fouten en een cap van 200 staat er 250 in de
 *       groep en 200 in {@code import_row_issue}.</li>
 *   <li>Elke bewaarde voorbeeldrij die bij een groep hoort, draagt haar {@code issue_group_id} —
 *       bouwstap 3h telt daarop verder.</li>
 *   <li>Prijsafwijkingen in tegengestelde richting zijn twee groepen, nooit één.</li>
 *   <li>Een bulk aan kritieke referentie-incidenten levert één {@code BULK_IDENTITY_INCIDENT}
 *       <b>naast</b> de individuele incidenten, die onverkort blijven bestaan (R-REF-07).</li>
 *   <li>De aggregatie is idempotent: tweemaal draaien verandert niets.</li>
 * </ul>
 * De voorbeeldcap staat hier bewust op de standaardwaarde 200, want juist dat getal moet in de
 * groep van 250 <b>niet</b> terugkomen. De microbatch- en chunkgroottes staan klein, zodat elk
 * scenario meerdere chunk-commits doorloopt.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=25",
        "catalogimport.screening.mutation-chunk-size=25",
        "catalogimport.screening.price-control-chunk-size=3",
        "catalogimport.screening.reference-control-chunk-size=3",
        "catalogimport.screening.recovery-on-startup=false"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class IssueGroupingTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    private static final String EAN_HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING;EAN\n";

    /** De voorbeeldcap van de standaardconfiguratie; hier bewust niet verlaagd. */
    private static final int CAP = DeliveryScreeningService.DEFAULT_MAX_SAMPLE_ROWS_PER_CODE;

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
    private IssueAggregationService aggregation;
    @Autowired
    private IssueGroupDao issueGroups;
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
    private DeliveryFileRepository deliveryFiles;
    @Autowired
    private ImportBatchRepository batches;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mockMvc;

    // --- De groeperingsdrempel -------------------------------------------------------------------

    /**
     * R-THR-04: groeperen vanaf tien gelijke signaturen. Negen keer dezelfde fout blijft negen losse
     * vaststellingen — een groep zou daar meer verbergen dan tonen. Het bulkpercentage staat hier
     * hoog, zodat enkel de groeperingsdrempel gemeten wordt.
     */
    @Test
    void groupsFromTenIdenticalSignaturesAndNotBefore() {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 9; i++) {
            csv.append("ACME;G1;R").append(i).append(";onleesbaar;Boormachine\n");
        }
        for (int i = 10; i <= 19; i++) {
            csv.append("ACME;;R").append(i).append(";1,50;Boormachine\n");
        }
        Fixture fixture = fixture("MIN", revision -> revision.setBulkIncidentSharePercent(
                new BigDecimal("95")));
        long batchId = screen(fixture, "REF-1", csv.toString());

        // Negen onleesbare prijzen: geen groep, en de negen voorbeeldrijen blijven los.
        assertThat(groups(batchId)).extracting(group -> group.get("issue_code"))
                .containsExactly(CandidateNormaliser.CODE_IDENTITY_COMPONENT_EMPTY);
        assertThat(ungroupedIssueCount(batchId, ImportValueRules.CODE_PRICE_UNREADABLE)).isEqualTo(9L);

        Map<String, Object> group = singleGroup(batchId);
        assertThat(group.get("incident_kind")).isEqualTo(IssueIncidentKind.GENERIC.name());
        assertThat(group.get("signature")).isEqualTo("FIELD=GROEP");
        assertThat(count(group, "occurrence_count")).isEqualTo(10L);
        assertThat(count(group, "recorded_sample_count")).isEqualTo(10L);
        assertThat(count(group, "scope_record_count")).isEqualTo(19L);
        // Aandeel op schaal 12, decimaal berekend: 10 x 100 / 19.
        assertThat(((BigDecimal) group.get("share_percent")).toPlainString())
                .isEqualTo("52.631578947368");
        assertThat(group.get("is_bulk_incident")).isEqualTo(false);
        // A20: patroonherkenning is bewust uitgesteld en wordt nooit geraden.
        assertThat(group.get("dominant_factor")).isNull();
        assertThat(group.get("pattern_description")).isNull();
        assertThat(count(group, "first_row_number")).isEqualTo(11L);

        // Bouwstap 3h leunt hierop: élke bewaarde voorbeeldrij van een groep draagt haar groep.
        assertThat(ungroupedIssueCount(batchId, CandidateNormaliser.CODE_IDENTITY_COMPONENT_EMPTY))
                .isZero();
        assertThat(groupedIssueCount(batchId, (Long) group.get("id"))).isEqualTo(10L);
    }

    // --- De bulkdrempel: altijd een percentage ---------------------------------------------------

    /**
     * Beslissing van de mens (20/09): een bulkincident is uitsluitend een <b>percentage</b> van de
     * gecontroleerde scope, nooit een vast aantal. Met 10% van 100 records is tien voorvallen exact
     * de grens — en exact op de grens is niet overschreden — terwijl elf het wel zijn.
     */
    @Test
    void marksABulkIncidentOnlyAboveTheConfiguredPercentageOfTheScope() {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 10; i++) {
            csv.append("ACME;G1;R").append(i).append(";onleesbaar;Boormachine\n");
        }
        for (int i = 11; i <= 21; i++) {
            csv.append("ACME;;R").append(i).append(";1,50;Boormachine\n");
        }
        for (int i = 22; i <= 100; i++) {
            csv.append("ACME;G1;R").append(i).append(";1,50;Boormachine\n");
        }
        Fixture fixture = fixture("PCT", revision -> revision.setBulkIncidentSharePercent(
                new BigDecimal("10")));
        long batchId = screen(fixture, "REF-1", csv.toString());

        Map<String, Object> onTheLimit = group(batchId, ImportValueRules.CODE_PRICE_UNREADABLE);
        assertThat(count(onTheLimit, "occurrence_count")).isEqualTo(10L);
        assertThat(count(onTheLimit, "scope_record_count")).isEqualTo(100L);
        // 10 x 100 = 1000 is niet groter dan 10 x 100 = 1000.
        assertThat(onTheLimit.get("is_bulk_incident")).isEqualTo(false);

        Map<String, Object> over = group(batchId, CandidateNormaliser.CODE_IDENTITY_COMPONENT_EMPTY);
        assertThat(count(over, "occurrence_count")).isEqualTo(11L);
        assertThat(over.get("is_bulk_incident")).isEqualTo(true);
        assertThat(((BigDecimal) over.get("share_percent")).toPlainString())
                .isEqualTo("11.000000000000");

        // Een gewone foutgroep boven de grens krijgt geen extra foutcode, enkel de vlag op de groep.
        assertThat(issueCodes(batchId)).doesNotContain(ImportIssueCatalog.BULK_PRICE_INCIDENT,
                ImportIssueCatalog.BULK_IDENTITY_INCIDENT);
        assertThat(batches.findById(batchId).orElseThrow().getBulkIncidentCount()).isEqualTo(1L);
    }

    /**
     * De keerzijde van "altijd een percentage", die de mens bewust aanvaardt: bij een kleine levering
     * is 1% al na tien records overschreden. De groeperingsdrempel gaat wél voor — negen gelijke
     * fouten halen 45% van de scope en leveren tóch geen groep en dus geen bulkincident op.
     */
    @Test
    void appliesThePercentageEvenWhenTheScopeIsSmallButNeverBelowTheGroupingThreshold() {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 10; i++) {
            csv.append("ACME;G1;R").append(i).append(";onleesbaar;Boormachine\n");
        }
        for (int i = 11; i <= 19; i++) {
            csv.append("ACME;;R").append(i).append(";1,50;Boormachine\n");
        }
        csv.append("ACME;G1;R20;1,50;Boormachine\n");
        Fixture fixture = fixture("SMALL", revision -> {
        });
        long batchId = screen(fixture, "REF-1", csv.toString());

        Map<String, Object> group = singleGroup(batchId);
        assertThat(group.get("issue_code")).isEqualTo(ImportValueRules.CODE_PRICE_UNREADABLE);
        assertThat(count(group, "scope_record_count")).isEqualTo(20L);
        // 10 x 100 = 1000 > 1 x 20 = 20: met de standaard van 1% is dit een bulkincident.
        assertThat(group.get("is_bulk_incident")).isEqualTo(true);
        // Negen even zware fouten blijven onder de groeperingsdrempel en worden dus niet gewogen.
        assertThat(ungroupedIssueCount(batchId, CandidateNormaliser.CODE_IDENTITY_COMPONENT_EMPTY))
                .isEqualTo(9L);
    }

    // --- De kern: het werkelijke aantal komt niet uit de voorbeeldrijen ---------------------------

    /**
     * R-ISS-03 en de kern van deze bouwstap. Boven de voorbeeldcap worden er geen rijen meer
     * bewaard, maar het aantal loopt door: wie het totaal uit {@code import_row_issue} zou tellen,
     * krijgt 200 terug in plaats van 250 — een getal dat eruitziet als een meting en dat fout is.
     */
    @Test
    void countsTheRealNumberOfOccurrencesAndNotTheKeptExampleRows() {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 250; i++) {
            csv.append("ACME;G1;R").append(i).append(";onleesbaar;Boormachine\n");
        }
        Fixture fixture = fixture("REAL", revision -> {
        });
        ScreeningOutcome outcome = screenFully(fixture, "REF-1", csv.toString());

        assertThat(outcome.rejectedRecordCount()).isEqualTo(250L);
        Map<String, Object> group = singleGroup(outcome.batchId());
        assertThat(group.get("issue_code")).isEqualTo(ImportValueRules.CODE_PRICE_UNREADABLE);
        assertThat(count(group, "occurrence_count")).isEqualTo(250L);
        assertThat(count(group, "recorded_sample_count")).isEqualTo((long) CAP);
        assertThat(issueRowCount(outcome.batchId(), ImportValueRules.CODE_PRICE_UNREADABLE))
                .isEqualTo((long) CAP);

        // Precies één cap-melding per foutcode, met het werkelijke aantal erin.
        List<Map<String, Object>> notices = jdbc.queryForList("select message, signature, row_number "
                        + "from import_row_issue where batch_id = ? and issue_code = ?",
                outcome.batchId(), ImportIssueCatalog.ROW_ISSUE_RECORDING_CAPPED);
        assertThat(notices).singleElement().satisfies(notice -> {
            assertThat((String) notice.get("message"))
                    .contains(ImportValueRules.CODE_PRICE_UNREADABLE + "=250");
            assertThat(notice.get("signature"))
                    .isEqualTo("CODE=" + ImportValueRules.CODE_PRICE_UNREADABLE);
            assertThat(notice.get("row_number")).isNull();
        });

        // De invariant waarop bouwstap 3h steunt: het werkelijke totaal per foutcode is de som van de
        // groepen plus de rijen die bij geen groep horen - nooit een telling van de bewaarde rijen.
        long realTotal = count(group, "occurrence_count")
                + ungroupedIssueCount(outcome.batchId(), ImportValueRules.CODE_PRICE_UNREADABLE);
        assertThat(realTotal).isEqualTo(250L);
        assertThat(groupedIssueCount(outcome.batchId(), (Long) group.get("id"))).isEqualTo((long) CAP);
    }

    // --- Prijsafwijkingen: component en richting -------------------------------------------------

    /**
     * R-PRI-14: gelijksoortige prijsafwijkingen worden gegroepeerd op component <b>en richting</b>.
     * Tien stijgingen en tien dalingen zijn twee verschijnselen — een prijsverhoging en een
     * vermoedelijk verkeerd geplaatste decimaal — en leveren twee groepen en twee bulkmeldingen op.
     * De individuele meldingen blijven onverkort bestaan.
     */
    @Test
    void keepsPriceDeviationsInOppositeDirectionsApartAndReportsEachAsItsOwnBulkIncident() {
        Fixture fixture = fixture("PRICE", revision -> {
        });
        StringBuilder baselineCsv = new StringBuilder(HEADER);
        for (int i = 1; i <= 20; i++) {
            baselineCsv.append("ACME;G1;R").append(i).append(";100,00;Boormachine\n");
        }
        long first = screen(fixture, "REF-1", baselineCsv.toString());
        baseline.acceptBaseline(first, "tester@example.test", "nulmeting");

        StringBuilder changed = new StringBuilder(HEADER);
        for (int i = 1; i <= 10; i++) {
            changed.append("ACME;G1;R").append(i).append(";200,00;Boormachine\n");
        }
        for (int i = 11; i <= 20; i++) {
            changed.append("ACME;G1;R").append(i).append(";50,00;Boormachine\n");
        }
        long batchId = screen(fixture, "REF-2", changed.toString());

        List<Map<String, Object>> priceGroups = groups(batchId).stream()
                .filter(group -> IssueIncidentKind.PRICE.name().equals(group.get("incident_kind")))
                .toList();
        assertThat(priceGroups).hasSize(2);
        assertThat(priceGroups).extracting(group -> group.get("signature"))
                .containsExactlyInAnyOrder("COMPONENT=BASE_PRICE|DIRECTION=DOWN",
                        "COMPONENT=BASE_PRICE|DIRECTION=UP");
        assertThat(priceGroups).allSatisfy(group -> {
            assertThat(count(group, "occurrence_count")).isEqualTo(10L);
            assertThat(group.get("price_component_code")).isEqualTo("BASE_PRICE");
            assertThat(group.get("reference_type")).isNull();
            // De scope is het aantal werkelijk uitgevoerde vergelijkingen, niet het aantal records.
            assertThat(count(group, "scope_record_count")).isEqualTo(20L);
            assertThat(group.get("is_bulk_incident")).isEqualTo(true);
        });

        // Eén bulkmelding per groep, náást de twintig individuele meldingen.
        assertThat(issueRowCount(batchId, ImportIssueCatalog.BULK_PRICE_INCIDENT)).isEqualTo(2L);
        assertThat(issueRowCount(batchId, PriceDeviationEvaluator.CODE_PRICE_DEVIATION_EXCEEDED))
                .isEqualTo(20L);
        assertThat(jdbc.queryForList("select message from import_row_issue where batch_id = ? "
                        + "and issue_code = ? order by signature", String.class, batchId,
                ImportIssueCatalog.BULK_PRICE_INCIDENT))
                .anySatisfy(message -> assertThat(message).contains("DOWN").contains("BASE_PRICE"))
                .anySatisfy(message -> assertThat(message).contains("UP"));

        // Bewuste tussenstand van 3g: BULK_PRICE_INCIDENT is BLOCKING, dus de bestaande 3a-logica
        // zet validation_result op BLOCKING. Ontwerp par. 3.6/15.3 wil daar REVIEW_REQUIRED; dat
        // wordt door bouwstap 3h rechtgezet en hier bewust niet geraden.
        assertThat(batches.findById(batchId).orElseThrow().getValidationResult())
                .isEqualTo(ValidationResult.BLOCKING);
        assertThat(batches.findById(batchId).orElseThrow().getStatus())
                .isEqualTo(ImportBatchStatus.SCREENED);
    }

    // --- Kritieke referenties: bulk naast individuele audit ---------------------------------------

    /**
     * R-REF-07: kritieke referenties blokkeren ongeacht volume. Een bulk aan gewijzigde EAN's —
     * typisch een bulktransformatie bij de leverancier — levert daarom één
     * {@code BULK_IDENTITY_INCIDENT} <b>naast</b> de tien individuele incidenten, nooit in de plaats
     * ervan: de individuele audit moet blijven.
     */
    @Test
    void reportsOneBulkIdentityIncidentWhileEveryIndividualIncidentKeepsExisting() {
        Fixture fixture = fixture("EAN", true, revision -> {
        });
        StringBuilder baselineCsv = new StringBuilder(EAN_HEADER);
        for (int i = 1; i <= 10; i++) {
            // Eigen waardereeks: een kritieke referentie is uniek per bibliotheek, maar de
            // H2-testdatabase is gedeeld en sommige oudere tests tellen over alle bibliotheken heen.
            baselineCsv.append("ACME;G1;R").append(i).append(";1,50;Boormachine;IGE-").append(i)
                    .append('\n');
        }
        long first = screen(fixture, "REF-1", baselineCsv.toString());
        baseline.acceptBaseline(first, "tester@example.test", "nulmeting met EAN");

        StringBuilder changed = new StringBuilder(EAN_HEADER);
        for (int i = 1; i <= 10; i++) {
            changed.append("ACME;G1;R").append(i).append(";1,50;Boormachine;IGX-").append(i)
                    .append('\n');
        }
        long batchId = screen(fixture, "REF-2", changed.toString());

        Map<String, Object> group = group(batchId, ImportIssueCatalog.IDENTITY_REFERENCE_INCIDENT);
        assertThat(group.get("incident_kind")).isEqualTo(IssueIncidentKind.IDENTITY.name());
        assertThat(group.get("signature")).isEqualTo("TYPE=EAN|KIND=CHANGED");
        assertThat(group.get("reference_type")).isEqualTo("EAN");
        assertThat(group.get("severity")).isEqualTo("CRITICAL");
        assertThat(count(group, "occurrence_count")).isEqualTo(10L);
        // De scope is het aantal kandidaten dat een gemapte kritieke referentie draagt.
        assertThat(count(group, "scope_record_count")).isEqualTo(10L);
        assertThat(group.get("is_bulk_incident")).isEqualTo(true);

        assertThat(issueRowCount(batchId, ImportIssueCatalog.IDENTITY_REFERENCE_INCIDENT))
                .isEqualTo(10L);
        List<Map<String, Object>> bulk = jdbc.queryForList("select severity, control_level, "
                        + "impact_scope, row_number, issue_group_id, message from import_row_issue "
                        + "where batch_id = ? and issue_code = ?", batchId,
                ImportIssueCatalog.BULK_IDENTITY_INCIDENT);
        assertThat(bulk).singleElement().satisfies(incident -> {
            assertThat(incident.get("severity")).isEqualTo("CRITICAL");
            assertThat(incident.get("control_level")).isEqualTo("DELIVERY");
            assertThat(incident.get("impact_scope")).isEqualTo("DELIVERY");
            assertThat(incident.get("row_number")).isNull();
            // De samenvatting wijst naar de groep die ze beschrijft.
            assertThat(incident.get("issue_group_id")).isEqualTo(group.get("id"));
            assertThat((String) incident.get("message")).contains("EAN").contains("CHANGED");
        });
        // De bulkmelding telt niet mee als voorbeeldrij van de groep.
        assertThat(count(group, "recorded_sample_count")).isEqualTo(10L);
    }

    // --- Idempotentie ----------------------------------------------------------------------------

    /**
     * Pass E4 mag zonder gevolgen opnieuw draaien: ze herberekent en schrijft nooit een tweede
     * melding. Dat is de garantie waarop een hervatte verwerking steunt.
     */
    @Test
    void producesExactlyTheSameResultWhenTheAggregationRunsTwice() {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 12; i++) {
            csv.append("ACME;G1;R").append(i).append(";onleesbaar;Boormachine\n");
        }
        Fixture fixture = fixture("IDEM", revision -> {
        });
        ScreeningOutcome outcome = screenFully(fixture, "REF-1", csv.toString());
        List<Map<String, Object>> before = groups(outcome.batchId());
        long issuesBefore = jdbc.queryForObject("select count(*) from import_row_issue where batch_id = ?",
                Long.class, outcome.batchId());

        aggregation.aggregate(outcome.batchId(), deliveryFileId(outcome.batchId()),
                kind -> 12L, BigDecimal.ONE, CAP);
        aggregation.aggregate(outcome.batchId(), deliveryFileId(outcome.batchId()),
                kind -> 12L, BigDecimal.ONE, CAP);

        assertThat(groups(outcome.batchId())).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from import_row_issue where batch_id = ?",
                Long.class, outcome.batchId())).isEqualTo(issuesBefore);
        assertThat(issueGroups.countByBatchId(outcome.batchId())).isEqualTo(1L);
    }

    // --- Leesroute -------------------------------------------------------------------------------

    /** Het nieuwe endpoint en de nieuwe filter, met dezelfde pagineringsregels als de rest. */
    @Test
    void exposesTheGroupsOverHttpAndLetsTheIssueListBeFilteredByGroup() throws Exception {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 11; i++) {
            csv.append("ACME;G1;R").append(i).append(";onleesbaar;Boormachine\n");
        }
        for (int i = 12; i <= 22; i++) {
            csv.append("ACME;;R").append(i).append(";1,50;Boormachine\n");
        }
        Fixture fixture = fixture("HTTP", revision -> {
        });
        long batchId = screen(fixture, "REF-1", csv.toString());
        long groupId = (Long) group(batchId, ImportValueRules.CODE_PRICE_UNREADABLE).get("id");

        mockMvc.perform(get("/api/catalog-import/batches/{id}/issue-groups", batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.content[0].occurrenceCount").value(11))
                .andExpect(jsonPath("$.content[0].bulkIncident").value(true))
                .andExpect(jsonPath("$.content[0].dominantFactor").doesNotExist());

        // Paginering: 0-gebaseerd, en een te grote paginagrootte wordt begrensd.
        mockMvc.perform(get("/api/catalog-import/batches/{id}/issue-groups", batchId)
                        .param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(1));
        mockMvc.perform(get("/api/catalog-import/batches/{id}/issue-groups", batchId)
                        .param("size", "5000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(200));
        mockMvc.perform(get("/api/catalog-import/batches/{id}/issue-groups", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BATCH_NOT_FOUND"));

        mockMvc.perform(get("/api/catalog-import/batches/{id}/issues", batchId)
                        .param("issueGroupId", String.valueOf(groupId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(11))
                .andExpect(jsonPath("$.content[0].issueGroupId").value(groupId))
                .andExpect(jsonPath("$.content[0].issueCode")
                        .value(ImportValueRules.CODE_PRICE_UNREADABLE));
        mockMvc.perform(get("/api/catalog-import/batches/{id}/issues", batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(22));
        mockMvc.perform(get("/api/catalog-import/batches/{id}", batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bulkIncidentCount").value(2));
    }

    // --- Regressie -------------------------------------------------------------------------------

    /** Een levering zonder gelijksoortige fouten gedraagt zich exact als vóór bouwstap 3g. */
    @Test
    void leavesADeliveryWithoutRepeatedFindingsExactlyAsItWas() {
        Fixture fixture = fixture("PLAIN", revision -> {
        });
        ScreeningOutcome outcome = screenFully(fixture, "REF-1",
                HEADER + "ACME;G1;R1;1,50;Boormachine\nACME;G1;R2;onleesbaar;Hamer\n");

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.validationResult()).isEqualTo(ValidationResult.VALID);
        assertThat(groups(outcome.batchId())).isEmpty();
        assertThat(batches.findById(outcome.batchId()).orElseThrow().getBulkIncidentCount()).isZero();
        assertThat(issueRowCount(outcome.batchId(), ImportIssueCatalog.ROW_ISSUE_RECORDING_CAPPED))
                .isZero();
        // De ene losse fout blijft een losse fout, zonder groep.
        assertThat(ungroupedIssueCount(outcome.batchId(), ImportValueRules.CODE_PRICE_UNREADABLE))
                .isEqualTo(1L);
        assertThat(outcome.contentMutationCount()).isEqualTo(1L);
    }

    // --- Helpers ---------------------------------------------------------------------------------

    private List<Map<String, Object>> groups(long batchId) {
        return jdbc.queryForList("select id, issue_code, signature, severity, issue_domain, "
                + "control_level, impact_scope, incident_kind, occurrence_count, recorded_sample_count, "
                + "scope_record_count, share_percent, is_bulk_incident, price_component_code, "
                + "deviation_direction, dominant_factor, reference_type, pattern_description, "
                + "first_row_number, handling_status from import_issue_group where batch_id = ? "
                + "order by issue_code, signature", batchId);
    }

    private Map<String, Object> singleGroup(long batchId) {
        List<Map<String, Object>> found = groups(batchId);
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    private Map<String, Object> group(long batchId, String issueCode) {
        List<Map<String, Object>> found = groups(batchId).stream()
                .filter(group -> issueCode.equals(group.get("issue_code"))).toList();
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    private static long count(Map<String, Object> group, String column) {
        return ((Number) group.get(column)).longValue();
    }

    private List<String> issueCodes(long batchId) {
        return jdbc.queryForList("select distinct issue_code from import_row_issue where batch_id = ? "
                + "order by issue_code", String.class, batchId);
    }

    private long issueRowCount(long batchId, String issueCode) {
        return jdbc.queryForObject("select count(*) from import_row_issue where batch_id = ? "
                + "and issue_code = ?", Long.class, batchId, issueCode);
    }

    private long ungroupedIssueCount(long batchId, String issueCode) {
        return jdbc.queryForObject("select count(*) from import_row_issue where batch_id = ? "
                + "and issue_code = ? and issue_group_id is null", Long.class, batchId, issueCode);
    }

    private long groupedIssueCount(long batchId, long issueGroupId) {
        return jdbc.queryForObject("select count(*) from import_row_issue where batch_id = ? "
                + "and issue_group_id = ?", Long.class, batchId, issueGroupId);
    }

    private Long deliveryFileId(long batchId) {
        return jdbc.queryForObject("select min(delivery_file_id) from import_row_issue "
                + "where batch_id = ? and delivery_file_id is not null", Long.class, batchId);
    }

    private long screen(Fixture fixture, String reference, String csv) {
        return screenFully(fixture, reference, csv).batchId();
    }

    private ScreeningOutcome screenFully(Fixture fixture, String reference, String csv) {
        DeliveryView view = intake.intake(fixture.taskId(), reference, "tester@example.test", null, null,
                "levering.csv", new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)))
                .delivery();
        deliveries.findById(view.deliveryId()).orElseThrow();
        deliveryFiles.findByDeliveryIdOrderBySequenceNumberAsc(view.deliveryId());
        return screening.screen(view.batch().batchId());
    }

    private Fixture fixture(String prefix, Consumer<ImportDefinitionRevision> customiser) {
        return fixture(prefix, false, customiser);
    }

    private Fixture fixture(String prefix, boolean withEan,
                            Consumer<ImportDefinitionRevision> customiser) {
        String unique = "IG" + SEQUENCE.incrementAndGet() + "-" + prefix;
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
        if (withEan) {
            revision.setRecordCanonicalisationVersion(2);
        }
        customiser.accept(revision);
        ImportDefinitionRevision stored = revisions.saveAndFlush(revision);
        if (withEan) {
            ImportFieldCatalogEntry target = fieldCatalog.findById("EAN").orElseThrow();
            ImportFieldMapping mapping = new ImportFieldMapping(stored, 1, target,
                    FieldValueKind.SOURCE_FIELD, target.getDataType(), target.getDefaultOwner(),
                    target.getIdentityClass());
            mapping.setSourceReference("EAN");
            mapping.setReferenceType(target.getReferenceType());
            fieldMappings.saveAndFlush(mapping);
        }
        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        // Eigen bibliotheek per test: een kritieke koppelreferentie is uniek per bibliotheek.
        String libraryCode = unique.substring(0, Math.min(unique.length(), 20));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, libraryCode));
        CatalogImportTask task = tasks.saveAndFlush(
                new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL));
        return new Fixture(task.getId(), link.getId(), stored.getId());
    }

    private record Fixture(long taskId, long linkId, long revisionId) {
    }
}
