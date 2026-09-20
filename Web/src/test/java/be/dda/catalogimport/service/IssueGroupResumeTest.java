package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.DeliveryRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.PriceDeviationDao;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.service.DeliveryScreeningService.ScreeningOutcome;
import be.dda.catalogimport.service.support.ImportIssueCatalog;
import be.dda.catalogimport.service.support.PriceDeviationEvaluator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Regressietest voor het bekende risico uit de bouwstappen 3e en 3f: een controlepass die halverwege
 * wegviel, schreef bij het hervatten een <b>tweede</b> cap-melding met enkel het aantal van dát
 * deel. Een lezer zag dan twee meldingen met twee deelaantallen en kon nergens meer aflezen hoeveel
 * fouten er werkelijk waren.
 * <p>
 * Sinds bouwstap 3g telt elke pass haar voorvallen per chunk op in {@code import_issue_group} — in
 * dezelfde transactie als haar hervatpunt — en schrijft uitsluitend de aggregatiepass E4, ná alle
 * detectiepassen, precies één {@code ROW_ISSUE_RECORDING_CAPPED} per batch en foutcode, met het
 * <b>werkelijke</b> totaal.
 * <p>
 * De voorbeeldcap staat hier op 3 en de chunkgrootte van de prijscontrole op 2, zodat de cap en de
 * onderbreking met twaalf regels aantoonbaar zijn — twaalf, want een groep ontstaat pas vanaf tien
 * gelijke signaturen. Daarom is dit een eigen testklasse: de cap van {@code IssueGroupingTest} moet
 * juist op de standaardwaarde 200 blijven staan.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=4",
        "catalogimport.screening.mutation-chunk-size=4",
        "catalogimport.screening.price-control-chunk-size=2",
        "catalogimport.screening.max-sample-rows-per-code=3",
        "catalogimport.screening.recovery-on-startup=false"})
@ActiveProfiles("local")
class IssueGroupResumeTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @MockitoSpyBean
    private PriceDeviationDao deviations;
    @Autowired
    private DeliveryIntakeService intake;
    @Autowired
    private DeliveryScreeningService screening;
    @Autowired
    private SourceStateBaselineService baseline;
    @Autowired
    private SourceOrganisationRepository sourceOrganisations;
    @Autowired
    private ImportDefinitionRepository definitions;
    @Autowired
    private ImportDefinitionRevisionRepository revisions;
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

    @Test
    void keepsOneCapNoticeWithTheFullCountAfterAnInterruptedPriceControlPass() {
        Fixture fixture = fixture("RESUME");
        long first = screen(fixture, "REF-1", rows("100,00"));
        baseline.acceptBaseline(first, "tester@example.test", "nulmeting");

        long batchId = deliver(fixture, "REF-2", rows("200,00"));
        AtomicInteger chunk = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
            if (chunk.incrementAndGet() == 3) {
                throw new UncheckedIOException(new IOException("simulated crash halfway"));
            }
            return invocation.callRealMethod();
        }).when(deviations).findCandidates(anyLong(), anyLong(), anyInt(), anyInt(), anyLong(), anyLong());

        try {
            screening.screen(batchId);
            throw new AssertionError("the simulated crash did not propagate");
        } catch (UncheckedIOException expected) {
            assertThat(expected).hasRootCauseInstanceOf(IOException.class);
        }

        // Vier regels zijn beoordeeld en gecommit, inclusief hun hervatpunt en hun telling. De
        // aggregatiepass draaide nog niet, dus er is nog geen cap-melding: het aantal zou op dat
        // moment een deelaantal zijn.
        assertThat(batches.findById(batchId).orElseThrow().getStatus())
                .isEqualTo(ImportBatchStatus.MUTATING);
        assertThat(occurrenceCount(batchId)).isEqualTo(4L);
        assertThat(issueRowCount(batchId, ImportIssueCatalog.ROW_ISSUE_RECORDING_CAPPED)).isZero();

        Mockito.reset(deviations);
        ScreeningOutcome outcome = screening.continueMutating(batchId);

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        // Eén groep, met het werkelijke totaal over beide doorlopen - geen tweede groep en geen
        // dubbeltelling.
        List<Map<String, Object>> groups = jdbc.queryForList("select signature, occurrence_count, "
                + "recorded_sample_count, is_bulk_incident from import_issue_group where batch_id = ?",
                batchId);
        assertThat(groups).singleElement().satisfies(group -> {
            assertThat(group.get("signature")).isEqualTo("COMPONENT=BASE_PRICE|DIRECTION=UP");
            assertThat(((Number) group.get("occurrence_count")).longValue()).isEqualTo(12L);
            assertThat(((Number) group.get("recorded_sample_count")).intValue()).isEqualTo(3);
            assertThat(group.get("is_bulk_incident")).isEqualTo(true);
        });

        // Dit is de eigenlijke regressie: precies één cap-melding voor de hele batch en foutcode,
        // met het volledige aantal - niet twee deelmeldingen.
        List<String> notices = jdbc.queryForList("select message from import_row_issue "
                        + "where batch_id = ? and issue_code = ?", String.class, batchId,
                ImportIssueCatalog.ROW_ISSUE_RECORDING_CAPPED);
        assertThat(notices).singleElement().satisfies(message -> assertThat(message)
                .contains(PriceDeviationEvaluator.CODE_PRICE_DEVIATION_EXCEEDED + "=12")
                .contains("3 example rows kept"));
        assertThat(issueRowCount(batchId, PriceDeviationEvaluator.CODE_PRICE_DEVIATION_EXCEEDED))
                .isEqualTo(3L);
        assertThat(issueRowCount(batchId, ImportIssueCatalog.BULK_PRICE_INCIDENT)).isEqualTo(1L);
        // Elke bewaarde voorbeeldrij hangt aan haar groep (voorwaarde voor bouwstap 3h).
        assertThat(jdbc.queryForObject("select count(*) from import_row_issue where batch_id = ? "
                        + "and issue_code = ? and issue_group_id is null", Long.class, batchId,
                PriceDeviationEvaluator.CODE_PRICE_DEVIATION_EXCEEDED)).isZero();
    }

    // --- Helpers ---------------------------------------------------------------------------------

    private static String rows(String price) {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 12; i++) {
            csv.append("ACME;G1;R").append(i).append(';').append(price).append(";Boormachine\n");
        }
        return csv.toString();
    }

    private long occurrenceCount(long batchId) {
        Long count = jdbc.queryForObject("select coalesce(sum(occurrence_count), 0) "
                + "from import_issue_group where batch_id = ?", Long.class, batchId);
        return count == null ? 0L : count;
    }

    private long issueRowCount(long batchId, String issueCode) {
        return jdbc.queryForObject("select count(*) from import_row_issue where batch_id = ? "
                + "and issue_code = ?", Long.class, batchId, issueCode);
    }

    private long screen(Fixture fixture, String reference, String csv) {
        long batchId = deliver(fixture, reference, csv);
        screening.screen(batchId);
        return batchId;
    }

    private long deliver(Fixture fixture, String reference, String csv) {
        DeliveryView view = intake.intake(fixture.taskId(), reference, "tester@example.test", null, null,
                "levering.csv", new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)))
                .delivery();
        deliveries.findById(view.deliveryId()).orElseThrow();
        return view.batch().batchId();
    }

    private Fixture fixture(String prefix) {
        String unique = "IGR" + SEQUENCE.incrementAndGet() + "-" + prefix;
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
        ImportDefinitionRevision stored = revisions.saveAndFlush(revision);
        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier,
                        unique.substring(0, Math.min(unique.length(), 20))));
        CatalogImportTask task = tasks.saveAndFlush(
                new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL));
        return new Fixture(task.getId(), link.getId(), stored.getId());
    }

    private record Fixture(long taskId, long linkId, long revisionId) {
    }
}
