package be.dda.catalogimport.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.ImportMutationRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.ImportMutation;
import be.dda.catalogimport.domain.MutationActionType;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Bouwstap C4 (ontwerp scherm 3 par. 16.4 en 11.5): het additieve veld {@code identityHash} op elke
 * regel van beide mutatielijsten, en het serverzijdige filter {@code ?identityHash=} erop.
 * <p>
 * <b>De fixture</b> is twee opeenvolgende leveringen van dezelfde drie aanbiedingen, zonder aanvaarde
 * nulmeting ertussen, samen in één bundel. De tweede screening ziet die aanbiedingen dan opnieuw als
 * nieuw, dus elke aanbieding draagt twee {@code CREATE}-voorstellen met <b>dezelfde</b>
 * identiteitshash uit twee <b>verschillende</b> batches — precies de wijzigingsgroep
 * {@code (identity_hash)} die over paginagrenzen heen te tonen moet zijn, en waarvoor een groepering in
 * de UI per constructie te kort zou komen (par. 11.5).
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=50",
        "catalogimport.screening.mutation-chunk-size=50",
        "catalogimport.screening.recovery-on-startup=false"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class MutationIdentityHashHttpTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    private static final String CREATOR = "jan.peeters@example.test";
    private static final String BUNDLE_URL = "/api/catalog-import/bundles/{id}/mutations";
    private static final String BATCH_URL = "/api/catalog-import/batches/{id}/mutations";
    /** 64 hex-tekens die in geen enkele fixture voorkomen: geldige hex, onbekende waarde. */
    private static final String UNKNOWN_HASH = "0".repeat(64);
    /** Bestaat niet als bundel en niet als batch. */
    private static final long UNKNOWN_ID = 999_999_999L;

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
    private ImportMutationRepository mutations;

    private record Scenario(long firstBatchId, long secondBatchId, long bundleId, String reason) {
    }

    // --- (a) Het veld -----------------------------------------------------------------------------

    /**
     * Elke inhoudelijke mutatie toont haar hash (64 hex-tekens, kleine letters); de
     * {@code IMPORT_MARKER} draagt geen identiteit ({@code ck_import_mutation_marker}) en toont dus
     * {@code null} — nooit een lege tekst die op een bestaande waarde lijkt.
     */
    @Test
    void everyContentMutationShowsItsIdentityHashAndTheMarkerShowsNone() throws Exception {
        Scenario s = scenario("FIELD");

        for (String url : List.of(BUNDLE_URL, BATCH_URL)) {
            long id = url.equals(BUNDLE_URL) ? s.bundleId() : s.firstBatchId();
            List<Map<String, Object>> rows = rows(body(get(url, id).param("size", "50")));
            assertThat(rows).isNotEmpty();
            assertThat(rows).filteredOn(row -> !"IMPORT_MARKER".equals(row.get("actionType")))
                    .isNotEmpty()
                    .allSatisfy(row -> assertThat((String) row.get("identityHash"))
                            .as("hash van mutatie %s", row.get("id")).matches("[0-9a-f]{64}"));
            assertThat(rows).filteredOn(row -> "IMPORT_MARKER".equals(row.get("actionType")))
                    .isNotEmpty()
                    .allSatisfy(row -> assertThat(row.get("identityHash")).isNull());
        }

        // Dezelfde aanbieding uit twee leveringen: dezelfde hash, twee verschillende mutaties.
        List<Map<String, Object>> bundleRows = rows(body(get(BUNDLE_URL, s.bundleId()).param("size", "50")));
        assertThat(hashOf(bundleRows, s.firstBatchId(), "R1")).isEqualTo(hashOf(bundleRows, s.secondBatchId(), "R1"));
        assertThat(hashOf(bundleRows, s.firstBatchId(), "R1"))
                .isNotEqualTo(hashOf(bundleRows, s.firstBatchId(), "R2"));
    }

    // --- (b) Het filter ---------------------------------------------------------------------------

    /** De hele wijzigingsgroep, ook wanneer ze uit twee batches van dezelfde koppeling komt. */
    @Test
    void filteringOnAnIdentityHashReturnsTheWholeChangeGroupAcrossBatches() throws Exception {
        Scenario s = scenario("GROUP");
        List<Map<String, Object>> all = rows(body(get(BUNDLE_URL, s.bundleId()).param("size", "50")));
        String hash = hashOf(all, s.firstBatchId(), "R1");

        String hit = body(get(BUNDLE_URL, s.bundleId()).param("identityHash", hash));
        assertThat(JsonPath.<Integer>read(hit, "$.totalElements")).isEqualTo(2);
        assertThat(JsonPath.<List<String>>read(hit, "$.content[*].identityHash")).containsOnly(hash);
        assertThat(JsonPath.<List<String>>read(hit, "$.content[*].identitySupplierReference"))
                .containsOnly("R1");
        assertThat(JsonPath.<List<Integer>>read(hit, "$.content[*].id")).isSorted();
        assertThat(JsonPath.<List<Number>>read(hit, "$.content[*].batchId")).extracting(Number::longValue)
                .containsExactly(s.firstBatchId(), s.secondBatchId());

        // Hoofdletterongevoelige invoer, kleine letters in het antwoord.
        assertThat(body(get(BUNDLE_URL, s.bundleId()).param("identityHash", hash.toUpperCase())))
                .isEqualTo(hit);

        // Dezelfde hash op één batch: enkel het voorstel van die batch.
        String perBatch = body(get(BATCH_URL, s.firstBatchId()).param("identityHash", hash));
        assertThat(JsonPath.<Integer>read(perBatch, "$.totalElements")).isEqualTo(1);
        assertThat(JsonPath.<List<Number>>read(perBatch, "$.content[*].batchId")).extracting(Number::longValue)
                .containsExactly(s.firstBatchId());

        // Combineerbaar met het bestaande batchId-filter van de bundellijst, dat de batchselectie vóór
        // de query versmalt.
        mockMvc.perform(get(BUNDLE_URL, s.bundleId()).param("identityHash", hash)
                        .param("batchId", String.valueOf(s.secondBatchId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].batchId").value(s.secondBatchId()));

        // Een onbekende bundel/batch blijft een 404: het filter mag dat nooit tot een lege lijst maken.
        mockMvc.perform(get(BUNDLE_URL, UNKNOWN_ID).param("identityHash", hash))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(BATCH_URL, UNKNOWN_ID).param("identityHash", hash))
                .andExpect(status().isNotFound());
    }

    /**
     * Onbekend, ongeldig en blanco. Een half gekopieerde hash is voor de gebruiker hetzelfde geval als
     * een hash die niets oplevert: een lege lijst, geen 500 en geen 400.
     */
    @Test
    void unknownOrInvalidHexIsAnEmptyPageAndBlankIsNoFilter() throws Exception {
        Scenario s = scenario("EMPTY");

        for (String url : List.of(BUNDLE_URL, BATCH_URL)) {
            long id = url.equals(BUNDLE_URL) ? s.bundleId() : s.firstBatchId();
            String none = body(get(url, id).param("size", "50"));
            long total = JsonPath.<Integer>read(none, "$.totalElements");
            assertThat(total).isPositive();

            // Blanco en afwezig zijn identiek - geen filter.
            assertThat(body(get(url, id).param("size", "50").param("identityHash", ""))).isEqualTo(none);
            assertThat(body(get(url, id).param("size", "50").param("identityHash", "   "))).isEqualTo(none);

            // Geldige hex zonder treffer, en elke vorm van ongeldige hex: lege pagina, status 200.
            for (String value : List.of(UNKNOWN_HASH, "zz", "abc", "geen-hash", "0x1234",
                    "ab cd", "1".repeat(63), "1".repeat(200))) {
                mockMvc.perform(get(url, id).param("identityHash", value))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.totalElements").value(0))
                        .andExpect(jsonPath("$.content.length()").value(0))
                        .andExpect(jsonPath("$.totalPages").value(0));
            }
        }
    }

    /**
     * Combineerbaar met de bestaande filters, en de teller klopt over paginagrenzen heen — dat laatste
     * is het hele punt van een serverzijdig filter (ontwerp par. 11.5).
     */
    @Test
    void theHashFilterCombinesWithTheOtherFiltersAndPagesCorrectly() throws Exception {
        Scenario s = scenario("COMBI");
        List<Map<String, Object>> all = rows(body(get(BUNDLE_URL, s.bundleId()).param("size", "50")));
        String hash = hashOf(all, s.firstBatchId(), "R1");

        // Enkel de mutatie van de eerste batch draagt de gekende reden.
        mockMvc.perform(get(BUNDLE_URL, s.bundleId()).param("identityHash", hash)
                        .param("statusReason", s.reason()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].batchId").value(s.firstBatchId()));
        mockMvc.perform(get(BUNDLE_URL, s.bundleId()).param("identityHash", hash)
                        .param("statusReason", "BESTAAT-NIET"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // actionType en status versmallen verder; een marker draagt per definitie geen hash.
        mockMvc.perform(get(BUNDLE_URL, s.bundleId()).param("identityHash", hash)
                        .param("actionType", "CREATE").param("status", "AWAITING_APPROVAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get(BUNDLE_URL, s.bundleId()).param("identityHash", hash)
                        .param("actionType", "IMPORT_MARKER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get(BUNDLE_URL, s.bundleId()).param("identityHash", hash)
                        .param("status", "READY_FOR_PUBLICATION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get(BATCH_URL, s.firstBatchId()).param("identityHash", hash)
                        .param("actionType", "CREATE").param("statusReason", s.reason()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        // Paginering: twee treffers, één per pagina, oplopend op id en met de volledige teller.
        String first = body(get(BUNDLE_URL, s.bundleId()).param("identityHash", hash)
                .param("size", "1").param("page", "0"));
        String second = body(get(BUNDLE_URL, s.bundleId()).param("identityHash", hash)
                .param("size", "1").param("page", "1"));
        for (String page : List.of(first, second)) {
            assertThat(JsonPath.<Integer>read(page, "$.totalElements")).isEqualTo(2);
            assertThat(JsonPath.<Integer>read(page, "$.totalPages")).isEqualTo(2);
            assertThat(JsonPath.<List<Object>>read(page, "$.content")).hasSize(1);
        }
        long firstId = JsonPath.<Number>read(first, "$.content[0].id").longValue();
        long secondId = JsonPath.<Number>read(second, "$.content[0].id").longValue();
        assertThat(firstId).isLessThan(secondId);
        // Een pagina voorbij het einde is leeg, met dezelfde teller.
        String beyond = body(get(BUNDLE_URL, s.bundleId()).param("identityHash", hash)
                .param("size", "1").param("page", "5"));
        assertThat(JsonPath.<Integer>read(beyond, "$.totalElements")).isEqualTo(2);
        assertThat(JsonPath.<List<Object>>read(beyond, "$.content")).isEmpty();
    }

    // --- Helpers ----------------------------------------------------------------------------------

    private String hashOf(List<Map<String, Object>> rows, long batchId, String reference) {
        return rows.stream()
                .filter(row -> ((Number) row.get("batchId")).longValue() == batchId
                        && reference.equals(row.get("identitySupplierReference")))
                .map(row -> (String) row.get("identityHash"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Geen mutatie " + reference + " in batch " + batchId));
    }

    private List<Map<String, Object>> rows(String body) {
        return JsonPath.read(body, "$.content");
    }

    private String body(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    /**
     * Twee uploads van dezelfde drie aanbiedingen (zonder aanvaarde nulmeting ertussen), beide batches
     * in één bundel, en de {@code R1}-mutatie van de <b>eerste</b> batch krijgt een gekende statusreden
     * zodat de filtercombinatie te controleren is.
     */
    private Scenario scenario(String prefix) throws Exception {
        String unique = "MIH" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
        CatalogImportTask task = fixture(unique);
        long firstBatch = upload(task.getId(), unique + "-LEV1", 100);
        long secondBatch = upload(task.getId(), unique + "-LEV2", 150);

        String reason = "TESTREDEN-" + unique;
        List<ImportMutation> creates = mutations.findByBatchIdAndActionType(firstBatch, MutationActionType.CREATE,
                PageRequest.of(0, 10)).getContent();
        assertThat(creates).hasSize(3);
        ImportMutation r1 = creates.stream().filter(m -> "R1".equals(m.getIdentitySupplierReference()))
                .findFirst().orElseThrow();
        r1.setStatusReason(reason);
        mutations.saveAndFlush(r1);

        String created = mockMvc.perform(post("/api/catalog-import/bundles").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bundleReference\":\"BND-" + unique + "\",\"targetMode\":\"SIMULATION\","
                                + "\"createdBy\":\"" + CREATOR + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long bundleId = ((Number) JsonPath.read(created, "$.id")).longValue();
        mockMvc.perform(post("/api/catalog-import/bundles/{id}/batches", bundleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchIds\":[" + firstBatch + "," + secondBatch + "],\"addedBy\":\""
                                + CREATOR + "\"}"))
                .andExpect(status().isOk());
        return new Scenario(firstBatch, secondBatch, bundleId, reason);
    }

    /** Eén levering met drie aanbiedingen {@code R1..R3}; {@code priceCents} maakt de prijzen uniek. */
    private long upload(long taskId, String reference, int priceCents) throws Exception {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int index = 1; index <= 3; index++) {
            int cents = priceCents + index * 25;
            csv.append("ACME;G1;R").append(index).append(';').append(cents / 100).append(',')
                    .append(String.format("%02d", cents % 100)).append(";Artikel ").append(index).append('\n');
        }
        String upload = mockMvc.perform(multipart("/api/catalog-import/tasks/{id}/deliveries", taskId)
                        .file(new MockMultipartFile("file", "levering.csv", "text/csv",
                                csv.toString().getBytes(StandardCharsets.UTF_8)))
                        .param("deliveryReference", reference)
                        .param("uploadedBy", "tester@example.test"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(upload, "$.batchId")).longValue();
    }

    private CatalogImportTask fixture(String unique) {
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
        revision.setMaxCriticalSharePercent(new BigDecimal("100"));
        revision.setStatus(RevisionStatus.ACTIVE);
        revisions.saveAndFlush(revision);
        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, "PSARF050"));
        return tasks.saveAndFlush(new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL));
    }
}
