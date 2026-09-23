package be.dda.catalogimport.web;

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
import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Het volledige, afgeronde HTTP-contract van de Publicatiebundel (Fase 4, bouwstap 4f, ontwerp par. 6
 * stap 4f), end-to-end via MockMvc tegen de echte controllers, services, DAO's en H2 — geen mocks. Dit
 * bewijst wat de losse service- en scenario-tests (o.a. {@code BundleFreezeTest}, {@code BundleCancelTest},
 * {@code BundleGroupDecisionHttpTest}, dat laatste tegen een gemokte service) niet samen tonen: het
 * statuscode-/paginering-/foutcodecontract van <b>alle</b> endpoints van deze controller in combinatie,
 * en het volledige gelukkige pad create → addBatches → approve → freeze → cancel (op een nieuwe bundel)
 * via echte HTTP-aanroepen.
 * <p>
 * Elke test bouwt een eigen keten met unieke codes: de H2-database is gedeeld.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=50",
        "catalogimport.screening.mutation-chunk-size=50",
        "catalogimport.screening.recovery-on-startup=false"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class BundleHttpTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    private static final String CREATOR = "jan.peeters@example.test";
    private static final String DECIDER = "piet.willems@example.test";
    private static final String FREEZER = "an.janssens@example.test";
    private static final String CANCELLER = "lieve.maes@example.test";

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

    // --- Het volledige gelukkige pad, end-to-end via HTTP --------------------------------------------

    /**
     * create → candidates → addBatches → batches-lijst → mutaties-lijst → groepsactie → freeze →
     * cancel op de bevroren bundel → dezelfde batch opnieuw toevoegen aan een NIEUWE bundel → die
     * nieuwe bundel ook bevriezen. Elke stap tegen de echte HTTP-laag.
     */
    @Test
    void theFullHappyPathWorksEndToEndCreateAddBatchesApproveFreezeThenCancel() throws Exception {
        Fixture f = fixture("HAPPY");
        long batchId = uploadAndScreen(f, "REF-1", rows(3, 100));

        // Kandidaten: de zonet gescreende batch staat erin.
        mockMvc.perform(get("/api/catalog-import/bundles/candidates").param("importLinkId",
                        String.valueOf(f.linkId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.batchId==" + batchId + ")]").exists());

        // Aanmaken: 200, en idempotent bij een herhaalde aanroep met dezelfde scope.
        String bundleReference = "BND-HTTP-" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet();
        String createBody = createBundleBody(bundleReference, "SIMULATION", CREATOR);
        String created = mockMvc.perform(post("/api/catalog-import/bundles")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bundleReference").value(bundleReference))
                .andExpect(jsonPath("$.status").value("ASSEMBLING"))
                .andExpect(jsonPath("$.targetMode").value("SIMULATION"))
                .andReturn().getResponse().getContentAsString();
        long bundleId = ((Number) JsonPath.read(created, "$.id")).longValue();
        mockMvc.perform(post("/api/catalog-import/bundles")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(bundleId));

        // Lijst, gefilterd op status: de H2-database is gedeeld met de rest van de module (mogelijk
        // meer dan 50 ASSEMBLING-bundels van andere testklassen), dus enkel het contract zelf wordt
        // getoetst — niet de aanwezigheid van dit ene id op een onvoorspelbare pagina. Die aanwezigheid
        // wordt hieronder wél bewezen via het ondubbelzinnige GET /bundles/{id}.
        mockMvc.perform(get("/api/catalog-import/bundles").param("status", "ASSEMBLING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());

        // Batches toevoegen.
        mockMvc.perform(post("/api/catalog-import/bundles/{id}/batches", bundleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchIds\":[" + batchId + "],\"addedBy\":\"" + CREATOR + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].batchId").value(batchId))
                .andExpect(jsonPath("$[0].active").value(true));

        // Batches-lijst van de bundel.
        mockMvc.perform(get("/api/catalog-import/bundles/{id}/batches", bundleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].batchId").value(batchId));

        // Volledige stand: live tellers zolang ASSEMBLING.
        mockMvc.perform(get("/api/catalog-import/bundles/{id}", bundleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSEMBLING"))
                .andExpect(jsonPath("$.batchCount").value(1))
                .andExpect(jsonPath("$.contentMutationCount").value(3))
                .andExpect(jsonPath("$.contentHash").doesNotExist());

        // Mutatielijst van de bundel: drie CREATE's plus de IMPORT_MARKER; eerste levering van de
        // koppeling, dus AWAITING_APPROVAL.
        mockMvc.perform(get("/api/catalog-import/bundles/{id}/mutations", bundleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[0].status").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$.content[0].batchId").value(batchId));

        // Groepsactie: alles goedkeuren.
        String groupBody = "{\"decisionKind\":\"APPROVE\",\"decidedBy\":\"" + DECIDER + "\","
                + "\"reason\":\"Eerste levering nagekeken\",\"filter\":{\"batchId\":" + batchId + "}}";
        String groupResponse = mockMvc.perform(post("/api/catalog-import/bundles/{id}/decisions", bundleId)
                        .contentType(MediaType.APPLICATION_JSON).content(groupBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedCount").value(3))
                .andReturn().getResponse().getContentAsString();
        long groupDecisionId = ((Number) JsonPath.read(groupResponse, "$.decisionId")).longValue();

        // Beslissingsregister van de bundel: de groepsactie staat erin.
        mockMvc.perform(get("/api/catalog-import/bundles/{id}/decisions", bundleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id==" + groupDecisionId + ")].decisionKind").value("APPROVE"))
                .andExpect(jsonPath("$.content[?(@.id==" + groupDecisionId + ")].decisionScope").value("GROUP"));

        // Individueel goedkeuren van een al goedgekeurde mutatie: idempotent, 200.
        String firstMutationBody = mockMvc.perform(get("/api/catalog-import/bundles/{id}/mutations", bundleId)
                        .param("status", "READY_FOR_PUBLICATION").param("size", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long firstMutationId = ((Number) JsonPath.read(firstMutationBody, "$.content[0].id")).longValue();
        mockMvc.perform(post("/api/catalog-import/bundles/{id}/mutations/{mid}/approve", bundleId, firstMutationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decidedBy\":\"" + DECIDER + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotent").value(true));

        // Bevriezen.
        String freezeBody = "{\"frozenBy\":\"" + FREEZER + "\",\"reason\":\"Eerste ronde goedgekeurd\"}";
        mockMvc.perform(post("/api/catalog-import/bundles/{id}/freeze", bundleId)
                        .contentType(MediaType.APPLICATION_JSON).content(freezeBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"))
                .andExpect(jsonPath("$.readyCount").value(3))
                .andExpect(jsonPath("$.contentHash").isString());

        // Batches toevoegen op een bevroren bundel: 409.
        mockMvc.perform(post("/api/catalog-import/bundles/{id}/batches", bundleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchIds\":[" + batchId + "],\"addedBy\":\"" + CREATOR + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUNDLE_NOT_ASSEMBLING"));

        // Annuleren van de bevroren bundel.
        String cancelBody = "{\"cancelledBy\":\"" + CANCELLER + "\",\"reason\":\"Leverancier trok de levering in\"}";
        mockMvc.perform(post("/api/catalog-import/bundles/{id}/cancel", bundleId)
                        .contentType(MediaType.APPLICATION_JSON).content(cancelBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledBy").value(CANCELLER));

        // Een tweede annulering: 409.
        mockMvc.perform(post("/api/catalog-import/bundles/{id}/cancel", bundleId)
                        .contentType(MediaType.APPLICATION_JSON).content(cancelBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUNDLE_NOT_CANCELLABLE"));

        // De batch is vrij: opnieuw toevoegen aan een NIEUWE bundel en die gewoon bevriezen.
        String secondReference = "BND-HTTP2-" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet();
        String secondCreated = mockMvc.perform(post("/api/catalog-import/bundles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBundleBody(secondReference, "SIMULATION", CREATOR)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long secondBundleId = ((Number) JsonPath.read(secondCreated, "$.id")).longValue();
        mockMvc.perform(post("/api/catalog-import/bundles/{id}/batches", secondBundleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchIds\":[" + batchId + "],\"addedBy\":\"" + CREATOR + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/catalog-import/bundles/{id}/freeze", secondBundleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"frozenBy\":\"" + FREEZER + "\",\"reason\":\"Tweede ronde, niets meer open\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"))
                .andExpect(jsonPath("$.expiredCount").value(3));
    }

    // --- Onbekende bundel: 404 op elk endpoint dat een bundel-id neemt -------------------------------

    @Test
    void anUnknownBundleIsA404WithTheStableCodeOnEveryBundleIdEndpoint() throws Exception {
        long unknown = 999_999_999L;

        mockMvc.perform(get("/api/catalog-import/bundles/{id}", unknown))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BUNDLE_NOT_FOUND"));
        mockMvc.perform(get("/api/catalog-import/bundles/{id}/batches", unknown))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BUNDLE_NOT_FOUND"));
        mockMvc.perform(get("/api/catalog-import/bundles/{id}/mutations", unknown))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BUNDLE_NOT_FOUND"));
        mockMvc.perform(get("/api/catalog-import/bundles/{id}/decisions", unknown))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BUNDLE_NOT_FOUND"));
        mockMvc.perform(post("/api/catalog-import/bundles/{id}/batches", unknown)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchIds\":[1],\"addedBy\":\"" + CREATOR + "\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BUNDLE_NOT_FOUND"));
        mockMvc.perform(post("/api/catalog-import/bundles/{id}/batches/{bid}/remove", unknown, 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"removedBy\":\"" + CREATOR + "\",\"reason\":\"Toch niet\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BUNDLE_NOT_FOUND"));
        mockMvc.perform(post("/api/catalog-import/bundles/{id}/mutations/{mid}/approve", unknown, 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decidedBy\":\"" + DECIDER + "\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BUNDLE_NOT_FOUND"));
        mockMvc.perform(post("/api/catalog-import/bundles/{id}/mutations/{mid}/reject", unknown, 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decidedBy\":\"" + DECIDER + "\",\"reason\":\"Nee\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BUNDLE_NOT_FOUND"));
        mockMvc.perform(post("/api/catalog-import/bundles/{id}/decisions", unknown)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisionKind\":\"APPROVE\",\"decidedBy\":\"" + DECIDER + "\","
                                + "\"filter\":{\"batchId\":1}}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BUNDLE_NOT_FOUND"));
        mockMvc.perform(post("/api/catalog-import/bundles/{id}/freeze", unknown)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"frozenBy\":\"" + FREEZER + "\",\"reason\":\"Nagekeken\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BUNDLE_NOT_FOUND"));
        mockMvc.perform(post("/api/catalog-import/bundles/{id}/cancel", unknown)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cancelledBy\":\"" + CANCELLER + "\",\"reason\":\"Nagekeken\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BUNDLE_NOT_FOUND"));
    }

    // --- Paginering: 0-gebaseerd, standaardgrootte, bovengrens ---------------------------------------

    @Test
    void paginationIsZeroBasedWithADefaultAndAMaximumSizeOnEveryPagedBundleEndpoint() throws Exception {
        Fixture f = fixture("PAGE");
        long batchId = uploadAndScreen(f, "REF-1", rows(5, 100));
        long bundleId = createBundle("BND-PAGE-" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet(), CREATOR);
        mockMvc.perform(post("/api/catalog-import/bundles/{id}/batches", bundleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchIds\":[" + batchId + "],\"addedBy\":\"" + CREATOR + "\"}"))
                .andExpect(status().isOk());

        // Standaardgrootte 50 en bovengrens 200 op de bundellijst.
        mockMvc.perform(get("/api/catalog-import/bundles"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(50));
        mockMvc.perform(get("/api/catalog-import/bundles").param("size", "5000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(200));
        mockMvc.perform(get("/api/catalog-import/bundles").param("size", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/catalog-import/bundles").param("page", "-1"))
                .andExpect(status().isBadRequest());

        // Dezelfde bovengrens op de mutatielijst van de bundel: vijf CREATE's plus de IMPORT_MARKER
        // (zes regels, zoals GET /batches/{id}/mutations), pagina's van twee.
        mockMvc.perform(get("/api/catalog-import/bundles/{id}/mutations", bundleId)
                        .param("size", "2").param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.content.length()").value(2));
        mockMvc.perform(get("/api/catalog-import/bundles/{id}/mutations", bundleId)
                        .param("size", "5000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(200));
        mockMvc.perform(get("/api/catalog-import/bundles/{id}/mutations", bundleId).param("size", "0"))
                .andExpect(status().isBadRequest());

        // Batches- en kandidatenlijst hebben dezelfde begrenzing.
        mockMvc.perform(get("/api/catalog-import/bundles/{id}/batches", bundleId).param("size", "5000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(200));
        mockMvc.perform(get("/api/catalog-import/bundles/candidates").param("size", "5000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(200));

        // Beslissingsregister: standaardgrootte en bovengrens.
        mockMvc.perform(get("/api/catalog-import/bundles/{id}/decisions", bundleId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(50));
        mockMvc.perform(get("/api/catalog-import/bundles/{id}/decisions", bundleId).param("size", "5000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(200));
    }

    // --- Aanmaken: ongeldige aanvragen zijn 400, zonder iets te schrijven ----------------------------

    @Test
    void creatingABundleWithoutARequiredFieldIsA400() throws Exception {
        mockMvc.perform(post("/api/catalog-import/bundles").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bundleReference\":\"BND-BAD\",\"targetMode\":\"SIMULATION\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/catalog-import/bundles").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bundleReference\":\"BND-BAD\",\"createdBy\":\"" + CREATOR + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/catalog-import/bundles").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetMode\":\"SIMULATION\",\"createdBy\":\"" + CREATOR + "\"}"))
                .andExpect(status().isBadRequest());
    }

    /** Een tweede aanmaak met dezelfde referentie maar een andere scope is een conflict, geen creatie. */
    @Test
    void reusingABundleReferenceWithADifferentScopeIsA409() throws Exception {
        String reference = "BND-SCOPE-" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet();
        mockMvc.perform(post("/api/catalog-import/bundles").contentType(MediaType.APPLICATION_JSON)
                        .content(createBundleBody(reference, "SIMULATION", CREATOR)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/catalog-import/bundles").contentType(MediaType.APPLICATION_JSON)
                        .content(createBundleBody(reference, "TRIAL_LIBRARY", CREATOR)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUNDLE_REFERENCE_REUSED_WITH_DIFFERENT_SCOPE"));
    }

    // --- Bevriezen en annuleren: ontbrekende gegevens zijn 400 ---------------------------------------

    @Test
    void freezeAndCancelWithoutARequiredFieldAreBothA400() throws Exception {
        long bundleId = createBundle("BND-REQ-" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet(), CREATOR);

        mockMvc.perform(post("/api/catalog-import/bundles/{id}/freeze", bundleId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"frozenBy\":\"" + FREEZER + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/catalog-import/bundles/{id}/freeze", bundleId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Nagekeken\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/catalog-import/bundles/{id}/cancel", bundleId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cancelledBy\":\"" + CANCELLER + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/catalog-import/bundles/{id}/cancel", bundleId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Nagekeken\"}"))
                .andExpect(status().isBadRequest());

        // Niets van dit alles heeft de bundel gewijzigd: nog steeds gewoon annuleerbaar.
        mockMvc.perform(post("/api/catalog-import/bundles/{id}/cancel", bundleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cancelledBy\":\"" + CANCELLER + "\",\"reason\":\"Nagekeken\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    // --- Helpers ---------------------------------------------------------------------------------------

    private String createBundleBody(String reference, String targetMode, String createdBy) {
        return "{\"bundleReference\":\"" + reference + "\",\"targetMode\":\"" + targetMode + "\","
                + "\"createdBy\":\"" + createdBy + "\"}";
    }

    private long createBundle(String reference, String createdBy) throws Exception {
        String body = mockMvc.perform(post("/api/catalog-import/bundles").contentType(MediaType.APPLICATION_JSON)
                        .content(createBundleBody(reference, "SIMULATION", createdBy)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    /** Uploadt en levert de batch-id op; de HTTP-intake screent synchroon. */
    private long uploadAndScreen(Fixture f, String reference, String[] rows) throws Exception {
        StringBuilder csv = new StringBuilder(HEADER);
        for (String row : rows) {
            csv.append(row).append('\n');
        }
        ResultActions upload = mockMvc.perform(multipart("/api/catalog-import/tasks/{id}/deliveries", f.taskId())
                .file(new MockMultipartFile("file", "levering.csv", "text/csv",
                        csv.toString().getBytes(StandardCharsets.UTF_8)))
                .param("deliveryReference", reference)
                .param("uploadedBy", "tester@example.test"));
        String body = upload.andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SCREENED")).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.batchId")).longValue();
    }

    /** {@code count} regels met oplopende referenties en een prijs afgeleid van {@code priceCents}. */
    private String[] rows(int count, int priceCents) {
        String[] rows = new String[count];
        for (int index = 0; index < count; index++) {
            int cents = priceCents + index * 25;
            rows[index] = "ACME;G1;R" + (index + 1) + ";" + (cents / 100) + "," + String.format("%02d", cents % 100)
                    + ";Artikel " + (index + 1);
        }
        return rows;
    }

    private Fixture fixture(String prefix) {
        // De database is een persistente lokale Postgres (geen wegwerp-testcontainer): een teller die
        // per JVM-run bij 0 herbegint zou bij een herhaalde testrun op dezelfde database botsen met
        // codes van een vorige run. Vandaar System.nanoTime() erbij, naast de teller voor leesbare
        // volgorde binnen één run.
        String unique = "BH" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
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
        return new Fixture(task.getId(), link.getId(), unique);
    }

    private record Fixture(long taskId, long linkId, String unique) {
    }
}
