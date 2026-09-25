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
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Het HTTP-contract van {@code GET /bundles/{id}/psimport-preview} (beslissing 2026-09-25, slice 1),
 * end-to-end via MockMvc tegen de echte controller, service, DAO en database. Vereist een draaiende
 * database (profiel local). De pure mappinglogica staat database-vrij in {@code PsimportPreviewMapperTest}.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=50",
        "catalogimport.screening.mutation-chunk-size=50",
        "catalogimport.screening.recovery-on-startup=false"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class PsimportPreviewHttpTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    private static final String CREATOR = "jan.peeters@example.test";
    private static final String DECIDER = "piet.willems@example.test";
    private static final String FREEZER = "an.janssens@example.test";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
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

    @Test
    void aNotFrozenBundleIsA409BundleNotFrozen() throws Exception {
        long bundleId = createBundle();

        mockMvc.perform(get("/api/catalog-import/bundles/{id}/psimport-preview", bundleId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUNDLE_NOT_FROZEN"));
    }

    @Test
    void anUnknownBundleIsA404BundleNotFound() throws Exception {
        mockMvc.perform(get("/api/catalog-import/bundles/{id}/psimport-preview", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BUNDLE_NOT_FOUND"));
    }

    @Test
    void anEmptyFrozenBundleGivesAnEmptyPageWithTheEnvelope() throws Exception {
        // Een bundel zonder batch kan niet bevroren worden (BUNDLE_EMPTY); een bevroren bundel waarvan alle
        // mutaties uit de selectie vallen is de bereikbare "lege" vorm: alles BLOCKED.
        Frozen frozen = frozenBundle("EMPTY", 2);
        jdbc.update("update import_mutation set status = 'BLOCKED' where batch_id = ? "
                + "and action_type in ('CREATE', 'UPDATE')", frozen.batchId());

        mockMvc.perform(get("/api/catalog-import/bundles/{id}/psimport-preview", frozen.bundleId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previewOnly").value(true))
                .andExpect(jsonPath("$.contractStatus").value("UNVERIFIED_FIELD_INVENTORY"))
                .andExpect(jsonPath("$.previewSpecVersion").isString())
                .andExpect(jsonPath("$.bundleContentHash").isString())
                .andExpect(jsonPath("$.generatedAt").exists())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void aNullCurrencyIsUnknownAndTheRowIsIncomplete() throws Exception {
        Frozen frozen = frozenBundle("CUR", 2);
        long mutationId = mutationIds(frozen.batchId()).get(0);
        jdbc.update("update import_mutation set base_price_currency = null where id = ?", mutationId);

        String body = preview(frozen.bundleId(), "");
        assertThat(JsonPath.<List<Boolean>>read(body, "$.content[?(@.mutationId==" + mutationId + ")].complete"))
                .containsExactly(false);
        assertThat(JsonPath.<List<String>>read(body, "$.content[?(@.mutationId==" + mutationId
                + ")].fields[?(@.code=='BASE_PRICE_CURRENCY')].state")).containsExactly("UNKNOWN");
        assertThat(JsonPath.<List<Boolean>>read(body, "$.content[?(@.mutationId!=" + mutationId + ")].complete"))
                .containsExactly(true);
    }

    @Test
    void aMutationWithoutAPriceHasAnUnknownBasePriceNeverZero() throws Exception {
        Frozen frozen = frozenBundle("NOPRICE", 2);
        long mutationId = mutationIds(frozen.batchId()).get(0);
        jdbc.update("update import_mutation set after_base_price = null where id = ?", mutationId);

        String body = preview(frozen.bundleId(), "");
        String path = "$.content[?(@.mutationId==" + mutationId + ")].fields[?(@.code=='BASE_PRICE')]";
        assertThat(JsonPath.<List<String>>read(body, path + ".state")).containsExactly("UNKNOWN");
        assertThat(JsonPath.<List<Object>>read(body, path + ".value")).containsExactly((Object) null);
        assertThat(JsonPath.<List<Boolean>>read(body, "$.content[?(@.mutationId==" + mutationId + ")].complete"))
                .containsExactly(false);
    }

    @Test
    void aPriceIsAStringNeverAJsonNumber() throws Exception {
        Frozen frozen = frozenBundle("STRING", 1);

        String body = preview(frozen.bundleId(), "");
        Object value = JsonPath.<List<Object>>read(body, "$.content[0].fields[?(@.code=='BASE_PRICE')].value").get(0);
        assertThat(value).isInstanceOf(String.class);
        assertThat(new BigDecimal((String) value)).isEqualByComparingTo("1.00");
    }

    @Test
    void askingTwiceGivesAnIdenticalAnswerExceptGeneratedAt() throws Exception {
        Frozen frozen = frozenBundle("TWICE", 3);

        ObjectNode first = (ObjectNode) MAPPER.readTree(preview(frozen.bundleId(), ""));
        ObjectNode second = (ObjectNode) MAPPER.readTree(preview(frozen.bundleId(), ""));
        // generatedAt is het tijdstip van opvragen en hoort daarom niet in de vergelijking.
        first.remove("generatedAt");
        second.remove("generatedAt");
        assertThat(second).isEqualTo(first);
        assertThat(first.get("content").size()).isEqualTo(3);
    }

    @Test
    void importMarkerAndBlockedMutationsAreNeverIncluded() throws Exception {
        Frozen frozen = frozenBundle("EXCL", 3);
        List<Long> ids = mutationIds(frozen.batchId());
        jdbc.update("update import_mutation set status = 'BLOCKED' where id = ?", ids.get(0));

        String body = preview(frozen.bundleId(), "");
        List<Integer> returned = JsonPath.read(body, "$.content[*].mutationId");
        assertThat(returned).containsExactly(ids.get(1).intValue(), ids.get(2).intValue());
        assertThat(JsonPath.<List<String>>read(body, "$.content[*].actionType")).containsOnly("CREATE");
        Long marker = jdbc.queryForObject("select id from import_mutation where batch_id = ? "
                + "and action_type = 'IMPORT_MARKER'", Long.class, frozen.batchId());
        assertThat(returned).doesNotContain(marker.intValue());
    }

    @Test
    void everyNotContractedFieldHasNoValue() throws Exception {
        Frozen frozen = frozenBundle("CONTRACT", 2);

        String body = preview(frozen.bundleId(), "");
        for (String code : List.of("PROCESS", "DELETE", "RECORD", "NUMBER")) {
            assertThat(JsonPath.<List<String>>read(body, "$.content[*].fields[?(@.code=='" + code + "')].state"))
                    .hasSize(2).containsOnly("NOT_CONTRACTED");
            assertThat(JsonPath.<List<Object>>read(body, "$.content[*].fields[?(@.code=='" + code + "')].value"))
                    .containsOnlyNulls();
        }
    }

    @Test
    void paginationIsSortedAndZeroBasedWithBounds() throws Exception {
        Frozen frozen = frozenBundle("PAGE", 5);
        List<Long> ids = mutationIds(frozen.batchId());

        String first = preview(frozen.bundleId(), "?size=2&page=0");
        assertThat(JsonPath.<Integer>read(first, "$.totalElements")).isEqualTo(5);
        assertThat(JsonPath.<Integer>read(first, "$.totalPages")).isEqualTo(3);
        assertThat(JsonPath.<Integer>read(first, "$.page")).isEqualTo(0);
        assertThat(JsonPath.<List<Integer>>read(first, "$.content[*].mutationId"))
                .containsExactly(ids.get(0).intValue(), ids.get(1).intValue());
        String last = preview(frozen.bundleId(), "?size=2&page=2");
        assertThat(JsonPath.<List<Integer>>read(last, "$.content[*].mutationId"))
                .containsExactly(ids.get(4).intValue());

        mockMvc.perform(get("/api/catalog-import/bundles/{id}/psimport-preview", frozen.bundleId())
                        .param("size", "5000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(200));
        mockMvc.perform(get("/api/catalog-import/bundles/{id}/psimport-preview", frozen.bundleId())
                        .param("size", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/catalog-import/bundles/{id}/psimport-preview", frozen.bundleId())
                        .param("page", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void csvFormatRequiresFrozenBundle() throws Exception {
        long bundleId = createBundle();

        mockMvc.perform(get("/api/catalog-import/bundles/{id}/psimport-preview", bundleId)
                        .param("format", "csv"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUNDLE_NOT_FROZEN"));
    }

    @Test
    void csvFormatReturnsTextCsvContentType() throws Exception {
        Frozen frozen = frozenBundle("CSV_CT", 1);

        mockMvc.perform(get("/api/catalog-import/bundles/{id}/psimport-preview", frozen.bundleId())
                        .param("format", "csv"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentType()).startsWith("text/csv"));
    }

    @Test
    void csvFormatIncludesBannerWithPreviewMetadata() throws Exception {
        Frozen frozen = frozenBundle("CSV_BANNER", 1);

        String csv = mockMvc.perform(get("/api/catalog-import/bundles/{id}/psimport-preview", frozen.bundleId())
                        .param("format", "csv"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        String[] lines = csv.split("\n");
        assertThat(lines[0]).startsWith("# PREVIEW");
        assertThat(lines[0]).contains("previewOnly=true");
        assertThat(lines[0]).contains("contractStatus=UNVERIFIED_FIELD_INVENTORY");
        assertThat(lines[0]).contains("previewSpecVersion=");
        assertThat(lines[0]).contains("bundleContentHash=");
    }

    @Test
    void csvFormatHasHeaderRowAndDataRows() throws Exception {
        Frozen frozen = frozenBundle("CSV_DATA", 2);

        String csv = mockMvc.perform(get("/api/catalog-import/bundles/{id}/psimport-preview", frozen.bundleId())
                        .param("format", "csv"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        String[] lines = csv.split("\n");
        assertThat(lines).hasSizeGreaterThanOrEqualTo(4); // banner + header + 2 data rows
        assertThat(lines[1]).startsWith("batchId,mutationId,actionType,complete");
        assertThat(lines[2]).startsWith(String.valueOf(frozen.batchId()));
        assertThat(lines[3]).startsWith(String.valueOf(frozen.batchId()));
    }

    @Test
    void csvFormatRowsHaveBatchIdMutationIdActionTypeComplete() throws Exception {
        Frozen frozen = frozenBundle("CSV_FIELDS", 1);
        List<Long> ids = mutationIds(frozen.batchId());

        String csv = mockMvc.perform(get("/api/catalog-import/bundles/{id}/psimport-preview", frozen.bundleId())
                        .param("format", "csv"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        String[] lines = csv.split("\n");
        String dataRow = lines[2];
        String[] fields = dataRow.split(",", 5); // batchId, mutationId, actionType, complete, ...

        assertThat(fields[0]).isEqualTo(String.valueOf(frozen.batchId()));
        assertThat(Long.parseLong(fields[1])).isEqualTo(ids.get(0));
        assertThat(fields[2]).isEqualTo("CREATE");
        assertThat(fields[3]).isEqualTo("true");
    }

    @Test
    void unknownFormatGives400() throws Exception {
        Frozen frozen = frozenBundle("CSV_FORMAT", 1);

        mockMvc.perform(get("/api/catalog-import/bundles/{id}/psimport-preview", frozen.bundleId())
                        .param("format", "xml"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void csvFormatWithPaginationWorks() throws Exception {
        Frozen frozen = frozenBundle("CSV_PAGE", 5);

        String csv = mockMvc.perform(get("/api/catalog-import/bundles/{id}/psimport-preview", frozen.bundleId())
                        .param("format", "csv")
                        .param("size", "2")
                        .param("page", "0"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        String[] lines = csv.split("\n");
        // banner + header + 2 data rows (size=2)
        assertThat(lines).hasSizeGreaterThanOrEqualTo(4);
    }

    // --- Helpers ---------------------------------------------------------------------------------------

    private String preview(long bundleId, String query) throws Exception {
        return mockMvc.perform(get("/api/catalog-import/bundles/" + bundleId + "/psimport-preview" + query))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private List<Long> mutationIds(long batchId) {
        return jdbc.queryForList("select id from import_mutation where batch_id = ? "
                + "and action_type in ('CREATE', 'UPDATE') order by id", Long.class, batchId);
    }

    private long createBundle() throws Exception {
        String reference = "BND-PSI-" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet();
        String body = mockMvc.perform(post("/api/catalog-import/bundles").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bundleReference\":\"" + reference + "\",\"targetMode\":\"SIMULATION\","
                                + "\"createdBy\":\"" + CREATOR + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    /** Upload met {@code rowCount} regels (prijs 1,00 en oplopend), bundel, alles goedgekeurd, bevroren. */
    private Frozen frozenBundle(String prefix, int rowCount) throws Exception {
        Fixture f = fixture(prefix);
        StringBuilder csv = new StringBuilder(HEADER);
        for (int index = 0; index < rowCount; index++) {
            csv.append("ACME;G1;R").append(index + 1).append(";1,").append(String.format("%02d", index))
                    .append(";Artikel ").append(index + 1).append('\n');
        }
        String upload = mockMvc.perform(multipart("/api/catalog-import/tasks/{id}/deliveries", f.taskId())
                        .file(new MockMultipartFile("file", "levering.csv", "text/csv",
                                csv.toString().getBytes(StandardCharsets.UTF_8)))
                        .param("deliveryReference", "REF-" + f.unique())
                        .param("uploadedBy", "tester@example.test"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("SCREENED"))
                .andReturn().getResponse().getContentAsString();
        long batchId = ((Number) JsonPath.read(upload, "$.batchId")).longValue();
        // De screening van een valutaveld valt buiten deze test: de fixture kent geen valutakolom, dus geef de
        // mutaties expliciet een valuta (anders is elke rij terecht complete=false, want er wordt nooit EUR aangenomen).
        jdbc.update("update import_mutation set base_price_currency = 'EUR' where batch_id = ? "
                + "and action_type in ('CREATE', 'UPDATE')", batchId);
        long bundleId = createBundle();
        mockMvc.perform(post("/api/catalog-import/bundles/{id}/batches", bundleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchIds\":[" + batchId + "],\"addedBy\":\"" + CREATOR + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/catalog-import/bundles/{id}/decisions", bundleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisionKind\":\"APPROVE\",\"decidedBy\":\"" + DECIDER + "\","
                                + "\"reason\":\"Nagekeken\",\"filter\":{\"batchId\":" + batchId + "}}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/catalog-import/bundles/{id}/freeze", bundleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"frozenBy\":\"" + FREEZER + "\",\"reason\":\"Preview test\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FROZEN"));
        return new Frozen(bundleId, batchId);
    }

    private Fixture fixture(String prefix) {
        String unique = "PV" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
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
        CatalogImportTask task = tasks.saveAndFlush(new CatalogImportTask(link, unique + "-taak",
                TaskTriggerType.MANUAL));
        return new Fixture(task.getId(), unique);
    }

    private record Fixture(long taskId, String unique) {
    }

    private record Frozen(long bundleId, long batchId) {
    }
}
