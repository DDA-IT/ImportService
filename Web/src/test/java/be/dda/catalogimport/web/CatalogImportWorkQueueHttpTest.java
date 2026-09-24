package be.dda.catalogimport.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.DeliveryRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.Delivery;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.domain.ValidationResult;
import com.jayway.jsonpath.JsonPath;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-contract van de drie nieuwe Scherm 0-endpoints (D14, eerste verticale slice, beslissingslog
 * 23/09 "Frontend: D14 opgepakt"): {@code GET /batches}, {@code GET /batches/summary} en
 * {@code GET /import-links}. Bouwt haar fixtures rechtstreeks via de repositories (geen echte
 * CSV-upload/screening nodig, deze endpoints zijn zuiver leesmodel over al bestaande kolommen).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class CatalogImportWorkQueueHttpTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @Autowired
    private MockMvc mockMvc;
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

    // --- GET /batches -----------------------------------------------------------------------------

    @Test
    void anUnknownImportLinkGivesAnEmptyBatchList() throws Exception {
        mockMvc.perform(get("/api/catalog-import/batches").param("importLinkId", "999999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void batchesCanBeFilteredByStatusValidationResultImportLinkAndCreatedWindowAloneAndCombined()
            throws Exception {
        Fixture f = fixture("WQ-FLT");
        ImportBatch screenedValid = batch(f, ImportBatchStatus.SCREENED, ValidationResult.VALID, 1);
        sleepPastClockResolution();
        ImportBatch screenedBlocking = batch(f, ImportBatchStatus.SCREENED, ValidationResult.BLOCKING, 2);
        sleepPastClockResolution();
        ImportBatch failedNoResult = batch(f, ImportBatchStatus.FAILED, null, 3);

        // Los op status.
        mockMvc.perform(get("/api/catalog-import/batches").param("importLinkId", String.valueOf(f.linkId()))
                        .param("status", "SCREENED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        // Los op validationResult.
        mockMvc.perform(get("/api/catalog-import/batches").param("importLinkId", String.valueOf(f.linkId()))
                        .param("validationResult", "BLOCKING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].batchId").value(screenedBlocking.getId()));

        // Gecombineerd: status EN validationResult.
        mockMvc.perform(get("/api/catalog-import/batches").param("importLinkId", String.valueOf(f.linkId()))
                        .param("status", "SCREENED").param("validationResult", "VALID"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].batchId").value(screenedValid.getId()));

        // Op importLinkId alleen: alle drie.
        mockMvc.perform(get("/api/catalog-import/batches").param("importLinkId", String.valueOf(f.linkId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));

        // createdFrom/createdTo: halfopen [from, to) op created_at.
        Instant from = screenedBlocking.getCreatedAt();
        Instant to = failedNoResult.getCreatedAt();
        mockMvc.perform(get("/api/catalog-import/batches").param("importLinkId", String.valueOf(f.linkId()))
                        .param("createdFrom", from.toString()).param("createdTo", to.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].batchId").value(screenedBlocking.getId()));

        // De rij toont koppelinggegevens zonder aparte opzoekactie.
        mockMvc.perform(get("/api/catalog-import/batches").param("importLinkId", String.valueOf(f.linkId()))
                        .param("status", "SCREENED").param("validationResult", "VALID"))
                .andExpect(jsonPath("$.content[0].importLinkCode").value(f.linkCode()))
                .andExpect(jsonPath("$.content[0].supplierCode").value(f.supplierCode()))
                .andExpect(jsonPath("$.content[0].libraryCode").value("PSARF050"));
    }

    @Test
    void batchesAreDeterministicallySortedNewestFirstAndStableAcrossRepeatedCalls() throws Exception {
        Fixture f = fixture("WQ-SORT");
        ImportBatch first = batch(f, ImportBatchStatus.SCREENED, ValidationResult.VALID, 1);
        ImportBatch second = batch(f, ImportBatchStatus.SCREENED, ValidationResult.VALID, 2);
        ImportBatch third = batch(f, ImportBatchStatus.SCREENED, ValidationResult.VALID, 3);
        List<Long> expected = List.of(third.getId(), second.getId(), first.getId());

        for (int attempt = 0; attempt < 3; attempt++) {
            mockMvc.perform(get("/api/catalog-import/batches")
                            .param("importLinkId", String.valueOf(f.linkId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].batchId").value(expected.get(0)))
                    .andExpect(jsonPath("$.content[1].batchId").value(expected.get(1)))
                    .andExpect(jsonPath("$.content[2].batchId").value(expected.get(2)));
        }
    }

    // --- GET /batches/summary -----------------------------------------------------------------------

    @Test
    void summaryKeepsNullValidationResultAsItsOwnRowSeparateFromValid() throws Exception {
        Fixture f = fixture("WQ-SUM");
        batch(f, ImportBatchStatus.SCREENED, ValidationResult.VALID, 1);
        batch(f, ImportBatchStatus.SCREENED, ValidationResult.VALID, 2);
        batch(f, ImportBatchStatus.FAILED, null, 3);
        batch(f, ImportBatchStatus.RECEIVED, null, 4);

        mockMvc.perform(get("/api/catalog-import/batches/summary")
                        .param("importLinkId", String.valueOf(f.linkId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.byStatus[?(@.status=='SCREENED')].count").value(2))
                .andExpect(jsonPath("$.byStatus[?(@.status=='FAILED')].count").value(1))
                .andExpect(jsonPath("$.byStatus[?(@.status=='RECEIVED')].count").value(1))
                .andExpect(jsonPath("$.byValidationResult[?(@.validationResult=='VALID')].count").value(2))
                // De null-groep is de eigen, zichtbare "niet vastgesteld"-rij: het veld staat er als
                // JSON-null in (geen NON_NULL-uitsluiting geconfigureerd), maar de rij zelf blijft staan
                // met haar telling — nooit samengevoegd met VALID, nooit weggelaten.
                .andExpect(jsonPath("$.byValidationResult[?(@.validationResult==null)].count").value(2));
    }

    @Test
    void summaryWithoutAnyBatchesForTheLinkIsAllZero() throws Exception {
        mockMvc.perform(get("/api/catalog-import/batches/summary").param("importLinkId", "999999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.byStatus").isArray())
                .andExpect(jsonPath("$.byStatus.length()").value(0))
                .andExpect(jsonPath("$.byValidationResult.length()").value(0));
    }

    // --- /batches/summary vs. /batches/{batchId} --------------------------------------------------

    @Test
    void batchesSummaryPathDoesNotCollideWithTheBatchByIdEndpoint() throws Exception {
        Fixture f = fixture("WQ-PATH");
        ImportBatch b = batch(f, ImportBatchStatus.SCREENED, ValidationResult.VALID, 1);

        // Het letterlijke /summary-pad wint: geen BATCH_NOT_FOUND, gewoon de samenvatting.
        mockMvc.perform(get("/api/catalog-import/batches/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").isNumber());

        // /batches/{batchId} blijft intact werken voor een echt numeriek id.
        mockMvc.perform(get("/api/catalog-import/batches/{batchId}", b.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(b.getId()));

        // Een onbekend numeriek id blijft de bestaande 404 geven, niet beïnvloed door /summary.
        mockMvc.perform(get("/api/catalog-import/batches/{batchId}", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BATCH_NOT_FOUND"));
    }

    @Test
    void batchDetailShowsTheLinkCodeSupplierAndLibraryAndKeepsItsExistingFields() throws Exception {
        Fixture f = fixture("WQ-DET");
        ImportBatch b = batch(f, ImportBatchStatus.SCREENED, ValidationResult.VALID, 1);

        mockMvc.perform(get("/api/catalog-import/batches/{batchId}", b.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(b.getId()))
                .andExpect(jsonPath("$.importLinkId").value(f.linkId()))
                .andExpect(jsonPath("$.importLinkCode").value(f.linkCode()))
                .andExpect(jsonPath("$.supplierCode").value(f.supplierCode()))
                .andExpect(jsonPath("$.libraryCode").value("PSARF050"))
                .andExpect(jsonPath("$.status").value("SCREENED"))
                .andExpect(jsonPath("$.validationResult").value("VALID"))
                .andExpect(jsonPath("$.attemptNo").value(1))
                .andExpect(jsonPath("$.deliveryId").value(b.getDelivery().getId()));
    }

    // --- GET /import-links --------------------------------------------------------------------------

    @Test
    void importLinksIsReachableWithoutTheSetupApiFlagAndListsCodeNameSupplierAndLibrary() throws Exception {
        Fixture f = fixture("WQ-LNK");

        // De H2/Postgres-database is gedeeld met de rest van de module (zie BundleHttpTest): met een
        // groeiend aantal koppelingen en een vaste sortering op code staat de zonet aangemaakte
        // koppeling niet gegarandeerd op pagina 0, dus alle pagina's doorlopen tot ze gevonden is.
        List<Object> found = findImportLinkAcrossPages(null, f.linkId());
        assertThat(found).isNotEmpty();
        Object row = found.get(0);
        assertThat(JsonPath.<String>read(row, "$.code")).isEqualTo(f.linkCode());
        assertThat(JsonPath.<String>read(row, "$.supplierCode")).isEqualTo(f.supplierCode());
        assertThat(JsonPath.<String>read(row, "$.libraryCode")).isEqualTo("PSARF050");
        assertThat(JsonPath.<Boolean>read(row, "$.active")).isTrue();
    }

    @Test
    void importLinksCanBeFilteredByActive() throws Exception {
        Fixture f = fixture("WQ-LNKACT");
        ImportLink link = links.findById(f.linkId()).orElseThrow();
        link.setActive(false);
        links.saveAndFlush(link);

        assertThat(findImportLinkAcrossPages(false, f.linkId())).isNotEmpty();
        assertThat(findImportLinkAcrossPages(true, f.linkId())).isEmpty();
    }

    @Test
    void importLinksArePaginatedWithTheSharedDefaultAndMaximum() throws Exception {
        mockMvc.perform(get("/api/catalog-import/import-links"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(50));
        mockMvc.perform(get("/api/catalog-import/import-links").param("size", "5000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(200));
        mockMvc.perform(get("/api/catalog-import/import-links").param("size", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/catalog-import/import-links").param("page", "-1"))
                .andExpect(status().isBadRequest());
    }

    // --- Helpers ---------------------------------------------------------------------------------------

    /**
     * Doorloopt alle pagina's van {@code GET /import-links} (grootte 200) op zoek naar {@code linkId},
     * en geeft de gevonden rij(en) terug als losse JSON-documenten (leeg = niet gevonden). Nodig omdat
     * de database gedeeld is met de rest van de module en de sortering op {@code code} de zonet
     * aangemaakte koppeling niet op een voorspelbare pagina zet.
     */
    private List<Object> findImportLinkAcrossPages(Boolean active, long linkId) throws Exception {
        List<Object> found = new ArrayList<>();
        int page = 0;
        int totalPages = 1;
        while (page < totalPages) {
            var request = get("/api/catalog-import/import-links").param("size", "200")
                    .param("page", String.valueOf(page));
            if (active != null) {
                request = request.param("active", String.valueOf(active));
            }
            String body = mockMvc.perform(request).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            totalPages = ((Number) JsonPath.read(body, "$.totalPages")).intValue();
            List<Object> matches = JsonPath.read(body, "$.content[?(@.id==" + linkId + ")]");
            found.addAll(matches);
            page++;
        }
        return found;
    }

    /** Slaat een {@link ImportBatch} rechtstreeks op via de repository, elk met een eigen tijdstip. */
    private ImportBatch batch(Fixture f, ImportBatchStatus status, ValidationResult validationResult,
                              int attemptNo) {
        Delivery delivery = deliveries.saveAndFlush(
                new Delivery(f.task(), f.unique() + "-DLV-" + attemptNo, Instant.now()));
        ImportBatch batch = new ImportBatch(delivery, f.link(), f.revision(), attemptNo, "tester@example.test");
        batch.setStatus(status);
        batch.setValidationResult(validationResult);
        return batches.saveAndFlush(batch);
    }

    /**
     * {@code created_at} komt uit {@code @PrePersist} ({@code Instant.now()}); Windows' klok heeft
     * doorgaans maar ~15 ms resolutie, dus zonder deze pauze kunnen twee snel na elkaar bewaarde
     * batches een identiek tijdstip krijgen, wat het halfopen {@code createdFrom}/{@code createdTo}
     * venster onbetrouwbaar zou maken.
     */
    private void sleepPastClockResolution() throws InterruptedException {
        Thread.sleep(25);
    }

    private Fixture fixture(String prefix) {
        // De database is een persistente lokale Postgres (geen wegwerp-testcontainer): een teller die
        // per JVM-run bij 0 herbegint zou bij een herhaalde testrun op dezelfde database botsen met
        // codes van een vorige run. Vandaar System.nanoTime() erbij, naast de teller voor leesbare
        // volgorde binnen één run.
        String unique = "WQH" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
        SourceOrganisation organisation = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + "-ORG BV", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(
                new ImportDefinition(organisation, unique + "-DEF", unique + " catalogus", "beheerder@example.test"));
        ImportDefinitionRevision revision = new ImportDefinitionRevision(definition, 1, IdentityProfileKind.THREE_PART,
                "beheerder@example.test");
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
        revision.setMaxCriticalSharePercent(new java.math.BigDecimal("100"));
        revision.setStatus(RevisionStatus.ACTIVE);
        ImportDefinitionRevision savedRevision = revisions.saveAndFlush(revision);
        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, "PSARF050"));
        CatalogImportTask task = tasks.saveAndFlush(new CatalogImportTask(link, unique + "-taak",
                TaskTriggerType.MANUAL));
        return new Fixture(task, link, savedRevision, supplier.getCode(), unique);
    }

    private record Fixture(CatalogImportTask task, ImportLink link, ImportDefinitionRevision revision,
                           String supplierCode, String unique) {

        long linkId() {
            return link.getId();
        }

        String linkCode() {
            return link.getCode();
        }
    }
}
