package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
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
import be.dda.catalogimport.service.BundleFreezeService;
import be.dda.catalogimport.testsupport.TestSecurityConfiguration;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * Bouwstap 5A-2 (docs/design/fase5-auth-design.md par. 3, 4 en 7): het bevriezen van een bundel is het
 * eerste endpoint dat op de <b>geverifieerde identiteit</b> aangesloten is.
 *
 * <h2>Business rule vs. implementatie</h2>
 * <ul>
 *   <li><b>Regel:</b> wie bevriest, tekent onder zijn eigen naam. <b>Implementatie:</b> de naam komt uit
 *       claim {@code preferred_username} van de login, nooit uit het request; het request mag de naam
 *       hooguit <i>bevestigen</i>.</li>
 *   <li><b>Regel:</b> een handtekening onder de verkeerde naam mag nooit ongemerkt gebeuren.
 *       <b>Implementatie:</b> een afwijkende naam in {@code frozenBy} is 400
 *       {@code ACTOR_FIELD_MISMATCH} en er wordt niets geschreven — geen status, geen beslissingsregel.</li>
 *   <li><b>Regel:</b> {@code system} tekent nooit. <b>Implementatie:</b> 403
 *       {@code SYSTEM_ACTOR_FORBIDDEN}, ook al mag {@code system} wel lezen.</li>
 *   <li><b>Regel:</b> {@code NULL} in {@code *_by_subject} betekent "geen geverifieerde identiteit".
 *       <b>Implementatie:</b> de HTTP-weg vult altijd een subject; de oude Service-overload (tests,
 *       {@code DemoDataSeeder}) laat het bewust leeg. Beide worden hier bewezen.</li>
 * </ul>
 * Het subject wordt via JDBC nagekeken: het staat bewust in <b>geen enkel</b> domeinantwoord (A6).
 * <p>
 * Elke test bouwt een eigen keten met unieke codes: de database is gedeeld.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=50",
        "catalogimport.screening.mutation-chunk-size=50",
        "catalogimport.screening.recovery-on-startup=false"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class FreezeActorHttpTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    private static final String CREATOR = "jan.peeters@example.test";
    private static final String FREEZER = "an.janssens@example.test";
    private static final String OTHER_ACTOR = "piet.willems@example.test";
    private static final String FREEZE_REASON = "Prijsronde september goedgekeurd";

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private BundleFreezeService freezeService;
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

    // --- Normaal scenario: geen frozenBy meegestuurd -------------------------------------------------

    /**
     * Het gewone geval na 5A-3: de SPA stuurt geen naam meer mee. Bewaard wordt de token-username, en
     * zowel de bundelrij als <b>beide</b> beslissingsregels dragen het token-subject.
     */
    @Test
    void withoutAFrozenByFieldTheTokenUsernameAndSubjectAreStored() throws Exception {
        long bundleId = plannedBundle("NOFIELD");

        mockMvc.perform(post("/api/catalog-import/bundles/{id}/freeze", bundleId).with(as(FREEZER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + FREEZE_REASON + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"))
                .andExpect(jsonPath("$.frozenBy").value(FREEZER))
                // A6: het subject lekt nergens in een domeinantwoord.
                .andExpect(jsonPath("$.frozenBySubject").doesNotExist())
                .andExpect(jsonPath("$.subject").doesNotExist());

        assertThat(frozenBy(bundleId)).containsExactly(FREEZER, subjectOf(FREEZER));
        // Twee regels: de handeling bevriezen zelf en de bulkgoedkeuring van de PLANNED-mutaties.
        assertThat(decisionSubjects(bundleId)).containsOnly(
                entry("FREEZE", subjectOf(FREEZER)),
                entry("AUTO_APPROVE_PLANNED", subjectOf(FREEZER)));
        assertThat(decisionActors(bundleId)).containsOnly(FREEZER);
    }

    // --- Grensgeval: dezelfde naam, andere spelling --------------------------------------------------

    /**
     * A2: vergelijken gebeurt getrimd en hoofdletterongevoelig, maar <b>bewaard wordt de tokenspelling</b>
     * — nooit de spelling uit het request. Anders zou de audit tonen wat de verzender typte in plaats van
     * wie er aangemeld was.
     */
    @Test
    void aDifferentCasingInTheRequestIsAcceptedButTheTokenSpellingIsStored() throws Exception {
        long bundleId = plannedBundle("CASE");

        mockMvc.perform(post("/api/catalog-import/bundles/{id}/freeze", bundleId).with(as(FREEZER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"frozenBy\":\"  " + FREEZER.toUpperCase(Locale.ROOT) + "  \","
                                + "\"reason\":\"" + FREEZE_REASON + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frozenBy").value(FREEZER));

        assertThat(frozenBy(bundleId)).containsExactly(FREEZER, subjectOf(FREEZER));
    }

    /** De bevestigende variant: exact dezelfde naam meesturen werkt gewoon (A4, wat de SPA blijft doen). */
    @Test
    void theExactSameNameInTheRequestIsAccepted() throws Exception {
        long bundleId = plannedBundle("SAME");

        mockMvc.perform(post("/api/catalog-import/bundles/{id}/freeze", bundleId).with(as(FREEZER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"frozenBy\":\"" + FREEZER + "\",\"reason\":\"" + FREEZE_REASON + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));

        assertThat(frozenBy(bundleId)).containsExactly(FREEZER, subjectOf(FREEZER));
    }

    // --- Ongeldige invoer: een andere naam ------------------------------------------------------------

    /**
     * De kern van {@code ACTOR_FIELD_MISMATCH}: wie in een ander tabblad intussen als iemand anders
     * aangemeld raakte, tekent niet ongemerkt op de verkeerde naam. Niets geschreven, en de foutmelding
     * lekt geen van beide namen.
     */
    @Test
    void anotherNameInTheRequestIs400AndNothingIsWritten() throws Exception {
        long bundleId = plannedBundle("MISMATCH");

        String body = mockMvc.perform(post("/api/catalog-import/bundles/{id}/freeze", bundleId).with(as(FREEZER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"frozenBy\":\"" + OTHER_ACTOR + "\",\"reason\":\"" + FREEZE_REASON + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACTOR_FIELD_MISMATCH"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("frozenBy").doesNotContain(FREEZER).doesNotContain(OTHER_ACTOR);

        assertUnchanged(bundleId);
    }

    /** Dezelfde weigering als de aangemelde gebruiker de standaardtestgebruiker is (geen expliciete login). */
    @Test
    void theMismatchIsAlsoDetectedAgainstTheDefaultLogin() throws Exception {
        long bundleId = plannedBundle("MISMATCHDEF");

        mockMvc.perform(post("/api/catalog-import/bundles/{id}/freeze", bundleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"frozenBy\":\"" + FREEZER + "\",\"reason\":\"" + FREEZE_REASON + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACTOR_FIELD_MISMATCH"));

        assertUnchanged(bundleId);

        // En met de juiste (standaard)gebruiker lukt het wél, met diens naam en subject.
        mockMvc.perform(post("/api/catalog-import/bundles/{id}/freeze", bundleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + FREEZE_REASON + "\"}"))
                .andExpect(status().isOk());
        assertThat(frozenBy(bundleId)).containsExactly(TestSecurityConfiguration.DEFAULT_USERNAME,
                TestSecurityConfiguration.DEFAULT_SUBJECT);
    }

    /** Een blanco {@code frozenBy} is "afwezig" (A3), geen mismatch. */
    @Test
    void aBlankFrozenByIsTreatedAsAbsent() throws Exception {
        long bundleId = plannedBundle("BLANK");

        mockMvc.perform(post("/api/catalog-import/bundles/{id}/freeze", bundleId).with(as(FREEZER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"frozenBy\":\"   \",\"reason\":\"" + FREEZE_REASON + "\"}"))
                .andExpect(status().isOk());

        assertThat(frozenBy(bundleId)).containsExactly(FREEZER, subjectOf(FREEZER));
    }

    // --- system mag lezen, nooit tekenen --------------------------------------------------------------

    @Test
    void aSystemLoginIs403AndNothingIsWritten() throws Exception {
        long bundleId = plannedBundle("SYSTEM");

        mockMvc.perform(post("/api/catalog-import/bundles/{id}/freeze", bundleId).with(as("SyStEm"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + FREEZE_REASON + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));

        assertUnchanged(bundleId);

        // Lezen blijft wél toegestaan voor dezelfde login: het verschil zit in het tekenen.
        mockMvc.perform(get("/api/catalog-import/bundles/{id}", bundleId).with(as("SyStEm")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSEMBLING"));
    }

    // --- Geen login: 401, nog vóór enige actorcontrole -------------------------------------------------

    @Test
    void anAnonymousFreezeIs401AndNothingIsWritten() throws Exception {
        long bundleId = plannedBundle("ANON");

        mockMvc.perform(post("/api/catalog-import/bundles/{id}/freeze", bundleId).with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + FREEZE_REASON + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        assertUnchanged(bundleId);
    }

    // --- Retry/idempotentie ----------------------------------------------------------------------------

    /**
     * Tweemaal dezelfde bevriezing levert nooit een tweede handtekening op: de tweede poging is 409 en de
     * bewaarde naam, het subject en het aantal beslissingsregels blijven exact die van de eerste.
     */
    @Test
    void aSecondFreezeByAnotherUserIs409AndLeavesTheFirstSignatureIntact() throws Exception {
        long bundleId = plannedBundle("RETRY");

        mockMvc.perform(post("/api/catalog-import/bundles/{id}/freeze", bundleId).with(as(FREEZER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + FREEZE_REASON + "\"}"))
                .andExpect(status().isOk());
        long decisionsAfterFirst = decisionCount(bundleId);

        mockMvc.perform(post("/api/catalog-import/bundles/{id}/freeze", bundleId).with(as(OTHER_ACTOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Nog eens\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUNDLE_NOT_ASSEMBLING"));

        assertThat(frozenBy(bundleId)).containsExactly(FREEZER, subjectOf(FREEZER));
        assertThat(decisionCount(bundleId)).isEqualTo(decisionsAfterFirst);
    }

    // --- Ontbrekende geverifieerde identiteit: NULL, nooit stil ingevuld --------------------------------

    /**
     * De oude Service-overload (tests, {@code DemoDataSeeder}) blijft bestaan en bevriest <b>zonder</b>
     * geverifieerde identiteit: {@code frozen_by_subject} en {@code decided_by_subject} blijven
     * {@code NULL}. Dat is de betekenis waarop G1 steunt — er wordt nooit een subject afgeleid uit een
     * naam.
     */
    @Test
    void aDirectServiceCallStoresTheNameWithoutASubject() throws Exception {
        long bundleId = plannedBundle("UNVERIFIED");

        freezeService.freeze(bundleId, FREEZER, FREEZE_REASON);

        assertThat(frozenBy(bundleId)).containsExactly(FREEZER, null);
        assertThat(decisionSubjects(bundleId)).containsOnly(
                entry("FREEZE", null),
                entry("AUTO_APPROVE_PLANNED", null));
        assertThat(decisionActors(bundleId)).containsOnly(FREEZER);
    }

    // --- Helpers ----------------------------------------------------------------------------------------

    /** {@code TestActors.as} leidt het subject hier vandaan; hardcoded zodat een wijziging opvalt. */
    private static String subjectOf(String username) {
        return "test-sub-" + username;
    }

    /** {@code frozen_by} en {@code frozen_by_subject} van de bundelrij, in die volgorde. */
    private List<String> frozenBy(long bundleId) {
        Map<String, Object> row = jdbc.queryForMap(
                "select frozen_by, frozen_by_subject from publication_bundle where id = ?", bundleId);
        return Arrays.asList((String) row.get("frozen_by"), (String) row.get("frozen_by_subject"));
    }

    /** Per beslissingssoort van déze bundel het bewaarde subject (de database is gedeeld). */
    private Map<String, String> decisionSubjects(long bundleId) {
        Map<String, String> result = new HashMap<>();
        jdbc.queryForList("select decision_kind, decided_by_subject from publication_decision where bundle_id = ?",
                        bundleId)
                .forEach(row -> result.put((String) row.get("decision_kind"), (String) row.get("decided_by_subject")));
        return result;
    }

    private List<String> decisionActors(long bundleId) {
        return jdbc.queryForList("select decided_by from publication_decision where bundle_id = ?",
                String.class, bundleId);
    }

    private long decisionCount(long bundleId) {
        Long count = jdbc.queryForObject("select count(*) from publication_decision where bundle_id = ?",
                Long.class, bundleId);
        return count == null ? 0L : count;
    }

    /** Niets geschreven: nog ASSEMBLING, geen handtekening, geen beslissingsregel. */
    private void assertUnchanged(long bundleId) {
        Map<String, Object> row = jdbc.queryForMap(
                "select status, frozen_by, frozen_by_subject, frozen_at, content_hash "
                        + "from publication_bundle where id = ?", bundleId);
        assertThat(row.get("status")).isEqualTo("ASSEMBLING");
        assertThat(row.get("frozen_by")).isNull();
        assertThat(row.get("frozen_by_subject")).isNull();
        assertThat(row.get("frozen_at")).isNull();
        assertThat(row.get("content_hash")).isNull();
        assertThat(decisionCount(bundleId)).isZero();
    }

    /**
     * Een {@code ASSEMBLING}-bundel met één batch vol <b>geplande</b> prijswijzigingen: eerste levering
     * aanvaard als nulmeting, tweede levering met dezelfde identiteiten en andere prijzen. Geplande
     * mutaties zijn nodig om ook de {@code AUTO_APPROVE_PLANNED}-regel te laten ontstaan — anders schrijft
     * het bevriezen enkel de {@code FREEZE}-regel.
     */
    private long plannedBundle(String prefix) throws Exception {
        Fixture f = fixture(prefix);
        // Exact dezelfde opzet als BundleFreezeTest.updateScenario (5 regels, 100 -> 125 cent), zodat dit
        // dezelfde geplande prijswijzigingen oplevert en deze test enkel over de actor gaat.
        long first = uploadAndScreen(f, "REF-1", rows(5, 100));
        mockMvc.perform(post("/api/catalog-import/batches/{id}/accept-baseline", first)
                        .with(as(CREATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acceptedBy\":\"" + CREATOR + "\",\"reason\":\"Nulmeting\"}"))
                .andExpect(status().isOk());
        long second = uploadAndScreen(f, "REF-2", rows(5, 125));

        String reference = "BND-FA-" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet();
        String created = mockMvc.perform(post("/api/catalog-import/bundles").with(as(CREATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bundleReference\":\"" + reference + "\",\"targetMode\":\"SIMULATION\","
                                + "\"createdBy\":\"" + CREATOR + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long bundleId = ((Number) JsonPath.read(created, "$.id")).longValue();
        mockMvc.perform(post("/api/catalog-import/bundles/{id}/batches", bundleId).with(as(CREATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchIds\":[" + second + "],\"addedBy\":\"" + CREATOR + "\"}"))
                .andExpect(status().isOk());
        return bundleId;
    }

    private long uploadAndScreen(Fixture f, String reference, String[] rows) throws Exception {
        StringBuilder csv = new StringBuilder(HEADER);
        for (String row : rows) {
            csv.append(row).append('\n');
        }
        String body = mockMvc.perform(multipart("/api/catalog-import/tasks/{id}/deliveries", f.taskId())
                        .file(new MockMultipartFile("file", "levering.csv", "text/csv",
                                csv.toString().getBytes(StandardCharsets.UTF_8)))
                        .param("deliveryReference", reference)
                        .param("uploadedBy", "tester@example.test").with(as("tester@example.test")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SCREENED"))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.batchId")).longValue();
    }

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
        String unique = "FA" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
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
