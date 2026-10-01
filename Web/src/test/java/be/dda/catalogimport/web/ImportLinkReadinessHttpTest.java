package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static be.dda.catalogimport.testsupport.TestActors.withoutPermissions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.ExternalCredentialRepository;
import be.dda.catalogimport.dao.ImportDefinitionBookmarkRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.BookmarkDataType;
import be.dda.catalogimport.domain.BookmarkValueScope;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.ExternalCredential;
import be.dda.catalogimport.domain.ExternalCredentialSecretKind;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionBookmark;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.service.ActorIdentity;
import be.dda.catalogimport.service.ConnectionProfileService;
import be.dda.catalogimport.service.ConnectionProfileService.NewConnectionProfile;
import be.dda.catalogimport.service.DeliveryConfigurationService;
import be.dda.catalogimport.service.DeliveryConfigurationService.NewDeliveryConfiguration;
import be.dda.catalogimport.service.LinkBookmarkValueService;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * NT-8: gereedheidscontrole zonder bestand, {@code GET /import-links/{id}/readiness} (beslissingslog 2026-09-30
 * "Nieuwe leverancier + taak (NT-spoor)", V1 = C).
 *
 * <ul>
 *   <li><b>Regel:</b> alle bevindingen tegelijk, niet enkel de eerste. <b>Implementatie:</b>
 *       {@code ChainConfigurationChecks} verzamelt; {@code ImportLinkReadinessService} toont alles.</li>
 *   <li><b>Regel (pariteit):</b> een probleem draagt exact de code die de upload (409) of de activatie (400/409) geeft,
 *       en de upload/activatie werpt de <b>eerste</b> bevinding. <b>Implementatie:</b> dezelfde verzamelende
 *       implementatie; bewezen per blokkerende toestand hieronder.</li>
 *   <li><b>Regel:</b> de controle schrijft niets. <b>Data:</b> de rijen van de keten zijn vóór en na identiek.</li>
 *   <li><b>Regel:</b> {@code READ} volstaat; zonder recht 403; onbekende koppeling 404 {@code LINK_NOT_FOUND}.</li>
 * </ul>
 * Elke test bouwt een eigen keten met unieke codes: de database is gedeeld.
 */
@SpringBootTest(properties = {"catalogimport.screening.recovery-on-startup=false"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ImportLinkReadinessHttpTest {

    private static final String API = "/api/catalog-import";
    private static final String USER = "an.janssens@example.test";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    /** Uniek per testrun: het {@code local}-profiel draait op een gedeelde PostgreSQL. */
    private static final String RUN = Long.toString(System.currentTimeMillis() % 1_000_000L, 36).toUpperCase();
    private static final byte[] CSV = ("LEVERANCIER;GROEP;REFERENTIE;PRIJS\n"
            + "ACME;G1;R1;1,50\n").getBytes(StandardCharsets.UTF_8);

    @TempDir
    static Path archiveRoot;
    @TempDir
    static Path localSourceRoot;

    @DynamicPropertySource
    static void directories(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
        registry.add("catalogimport.local-source.directory", () -> localSourceRoot.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SourceOrganisationRepository organisations;
    @Autowired
    private ImportDefinitionRepository definitions;
    @Autowired
    private ImportDefinitionRevisionRepository revisions;
    @Autowired
    private ImportLinkRepository links;
    @Autowired
    private CatalogImportTaskRepository tasks;
    @Autowired
    private ImportDefinitionBookmarkRepository bookmarks;
    @Autowired
    private LinkBookmarkValueService linkBookmarkValues;
    @Autowired
    private ExternalCredentialRepository credentials;
    @Autowired
    private ConnectionProfileService profiles;
    @Autowired
    private DeliveryConfigurationService configurations;

    // --- Zonder actieve revisie: het concept wordt getoetst ------------------------------------------------------------

    @Test
    void aFreshChainWithOnlyAValidDraftIsNotReadyAndSaysTheDraftCanBeActivated() throws Exception {
        Fixture f = fixture("FRESH", RevisionStatus.DRAFT, true);
        List<Map<String, Object>> before = snapshot(f);

        String body = readiness(f.link().getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(false))
                .andReturn().getResponse().getContentAsString();

        assertThat(((Number) JsonPath.read(body, "$.linkId")).longValue()).isEqualTo(f.link().getId());
        assertThat(codes(body, "PROBLEM")).containsExactly("NO_ACTIVE_REVISION");
        assertCheck(body, "NO_ACTIVE_REVISION", "PROBLEM", "DEFINITION", f.definition().getId());
        assertCheck(body, "READY_DRAFT_ACTIVATABLE", "OK", "REVISION", f.revision().getId());
        assertCheck(body, "READY_LINK_ACTIVE", "OK", "LINK", f.link().getId());
        assertCheck(body, "READY_LINK_BOOKMARKS", "OK", "LINK", f.link().getId());
        assertCheck(body, "READY_TASK_ACCEPTS_UPLOAD", "OK", "TASK", f.task().getId());
        assertCheck(body, "INFO_LIBRARY_NOT_VERIFIED", "INFO", "LINK", f.link().getId());
        assertThat(codes(body, "OK")).doesNotContain("READY_ACTIVE_REVISION");

        // Herhaalbaar en zonder schrijven: dezelfde vraag geeft hetzelfde antwoord en de keten is onaangeroerd.
        String again = readiness(f.link().getId()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(again).isEqualTo(body);
        assertThat(snapshot(f)).isEqualTo(before);

        // Pariteit: de upload weigert met dezelfde code; het concept laat zich activeren zoals voorspeld.
        upload(f.task().getId(), unique("REF")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_ACTIVE_REVISION"));
        activate(f.revision().getId()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));

        String after = readiness(f.link().getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(codes(after, "PROBLEM")).isEmpty();
        assertCheck(after, "READY_ACTIVE_REVISION", "OK", "REVISION", f.revision().getId());
    }

    /**
     * Een concept met meerdere, onafhankelijke problemen: alles in één antwoord. De activatie werpt de eerste in
     * dezelfde volgorde (eerst de configuratievalidatie, dan de DEFINITION-bookmarks).
     */
    @Test
    void aDraftWithSeveralProblemsListsThemAllAtOnceAndActivationThrowsThemInOrder() throws Exception {
        Fixture f = fixture("MANY", RevisionStatus.DRAFT, false);
        ImportDefinitionRevision draft = f.revision();
        draft.setRecordBasePriceField(null);
        draft = revisions.saveAndFlush(draft);
        declare(draft, "PRIJSBASIS", BookmarkValueScope.DEFINITION, true);
        declare(draft, "CULTUUR", BookmarkValueScope.LINK, true);
        List<Map<String, Object>> before = snapshot(f);

        String body = readiness(f.link().getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(false))
                .andReturn().getResponse().getContentAsString();

        assertThat(codes(body, "PROBLEM")).containsExactly("NO_ACTIVE_REVISION", "CONFIG_PRICE_FIELD_MISSING",
                "CONFIG_REQUIRED_BOOKMARK_MISSING", "CONFIG_REQUIRED_BOOKMARK_MISSING", "LINK_HAS_NO_TASK");
        assertCheck(body, "CONFIG_PRICE_FIELD_MISSING", "PROBLEM", "REVISION", draft.getId());
        assertCheck(body, "CONFIG_REQUIRED_BOOKMARK_MISSING", "PROBLEM", "REVISION", draft.getId());
        assertCheck(body, "CONFIG_REQUIRED_BOOKMARK_MISSING", "PROBLEM", "LINK", f.link().getId());
        assertCheck(body, "LINK_HAS_NO_TASK", "PROBLEM", "LINK", f.link().getId());
        assertThat(codes(body, "OK")).doesNotContain("READY_DRAFT_ACTIVATABLE", "READY_LINK_BOOKMARKS");
        assertThat(detail(body, "CONFIG_PRICE_FIELD_MISSING")).startsWith("CONFIG_PRICE_FIELD_MISSING: ");
        assertThat(snapshot(f)).isEqualTo(before);

        // Pariteit met de activatie: eerst de configuratievalidatie (400) ...
        activate(draft.getId()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONFIG_PRICE_FIELD_MISSING"))
                .andExpect(jsonPath("$.error").value(detail(body, "CONFIG_PRICE_FIELD_MISSING")));
        // ... en pas daarna de verplichte DEFINITION-bookmark (409), met dezelfde tekst als de checklist.
        draft.setRecordBasePriceField("PRIJS");
        draft = revisions.saveAndFlush(draft);
        String second = readiness(f.link().getId()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(codes(second, "PROBLEM")).containsExactly("NO_ACTIVE_REVISION",
                "CONFIG_REQUIRED_BOOKMARK_MISSING", "CONFIG_REQUIRED_BOOKMARK_MISSING", "LINK_HAS_NO_TASK");
        activate(draft.getId()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFIG_REQUIRED_BOOKMARK_MISSING"))
                .andExpect(jsonPath("$.error").value(detailFor(second, "CONFIG_REQUIRED_BOOKMARK_MISSING",
                        "REVISION")));
        assertThat(revisions.findById(draft.getId()).orElseThrow().getStatus()).isEqualTo(RevisionStatus.DRAFT);
    }

    /** Een fout in de tweede validatiestap (mapping/canonicalisatie) draagt haar eigen CONFIG_*-code. */
    @Test
    void aDraftFailingTheMappingStageCarriesTheSameCodeAsTheActivation() throws Exception {
        Fixture f = fixture("CANON", RevisionStatus.DRAFT, true);
        ImportDefinitionRevision draft = f.revision();
        draft.setRecordCurrencyField("VALUTA");
        draft.setRecordCanonicalisationVersion(1);
        draft = revisions.saveAndFlush(draft);

        String body = readiness(f.link().getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(false))
                .andReturn().getResponse().getContentAsString();

        assertThat(codes(body, "PROBLEM")).containsExactly("NO_ACTIVE_REVISION",
                "CONFIG_CANONICALISATION_VERSION_REQUIRED");
        assertCheck(body, "CONFIG_CANONICALISATION_VERSION_REQUIRED", "PROBLEM", "REVISION", draft.getId());
        activate(draft.getId()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONFIG_CANONICALISATION_VERSION_REQUIRED"));
    }

    /**
     * NT-14-2: een concept met drie onafhankelijke configuratiefouten toont drie PROBLEM-rijen (subject REVISION) in de
     * volgorde van de fabriek; de eerste rij is code en tekst van de 400 van de activatie.
     */
    @Test
    void aDraftWithSeveralIndependentConfigErrorsListsEachOneInOrderAndTheFirstEqualsTheActivation400()
            throws Exception {
        Fixture f = fixture("ALLCFG", RevisionStatus.DRAFT, true);
        ImportDefinitionRevision draft = f.revision();
        draft.setIdentitySupplierField(" ");
        draft.setIdentitySupplierGroupField(" ");
        draft.setRecordBasePriceField(null);
        draft = revisions.saveAndFlush(draft);
        List<Map<String, Object>> before = snapshot(f);

        String body = readiness(f.link().getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(false))
                .andReturn().getResponse().getContentAsString();

        List<String> problems = codes(body, "PROBLEM");
        assertThat(problems.subList(0, 4)).containsExactly("NO_ACTIVE_REVISION", "CONFIG_IDENTITY_FIELD_MISSING",
                "CONFIG_IDENTITY_FIELD_MISSING", "CONFIG_PRICE_FIELD_MISSING");
        List<Map<String, Object>> configRows = revisionProblemRows(body, draft.getId());
        assertThat(configRows.subList(0, 3)).extracting(row -> row.get("code")).containsExactly(
                "CONFIG_IDENTITY_FIELD_MISSING", "CONFIG_IDENTITY_FIELD_MISSING", "CONFIG_PRICE_FIELD_MISSING");
        assertThat((String) configRows.get(0).get("detail")).isNotEqualTo(configRows.get(1).get("detail"));
        assertThat(configRows.subList(0, 3)).allSatisfy(row ->
                assertThat((String) row.get("detail")).startsWith(row.get("code") + ": "));
        assertThat(snapshot(f)).isEqualTo(before);

        // De eerste rij is wat de activatie weigert: zelfde code, zelfde tekst (byte-gelijk).
        activate(draft.getId()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(configRows.get(0).get("code")))
                .andExpect(jsonPath("$.error").value(configRows.get(0).get("detail")));
        assertThat(revisions.findById(draft.getId()).orElseThrow().getStatus()).isEqualTo(RevisionStatus.DRAFT);
    }

    /** NT-14-2: een afhankelijke controle wordt niet beoordeeld en dat wordt gemeld; het telt niet voor `ready`. */
    @Test
    void aDraftWithADependentCheckReportsTheSkippedChecksAsInformation() throws Exception {
        Fixture f = fixture("SKIP", RevisionStatus.DRAFT, true);
        ImportDefinitionRevision draft = f.revision();
        draft.setStructureDelimiter("");
        draft.setStructureQuoteChar("'");
        draft.setRecordBasePriceField(null);
        draft = revisions.saveAndFlush(draft);

        String body = readiness(f.link().getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(false))
                .andReturn().getResponse().getContentAsString();

        assertThat(codes(body, "PROBLEM")).contains("CONFIG_DELIMITER_MISSING", "CONFIG_PRICE_FIELD_MISSING");
        assertThat(codes(body, "INFO")).contains("INFO_CONFIG_CHECKS_SKIPPED");
        assertCheck(body, "INFO_CONFIG_CHECKS_SKIPPED", "INFO", "REVISION", draft.getId());
        assertThat(detail(body, "INFO_CONFIG_CHECKS_SKIPPED")).startsWith("Checks depending on [")
                .contains("CONFIG_DELIMITER_MISSING").endsWith("] were not evaluated");
        assertThat(codes(body, "PROBLEM")).doesNotContain("INFO_CONFIG_CHECKS_SKIPPED");
        // NT-14-5: de codes zijn nu ook in het gestructureerde veld skippedBecause
        List<String> skipped = JsonPath.read(body, "$.checks[?(@.code == 'INFO_CONFIG_CHECKS_SKIPPED')].skippedBecause[0]");
        assertThat(skipped).contains("CONFIG_DELIMITER_MISSING");

        // Pariteit: de eerste rij is de 400 van de activatie.
        activate(draft.getId()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONFIG_DELIMITER_MISSING"))
                .andExpect(jsonPath("$.error").value(detail(body, "CONFIG_DELIMITER_MISSING")));
    }

    @Test
    void aDefinitionWithoutActiveAndWithoutDraftRevisionSaysSo() throws Exception {
        Fixture f = fixture("SUPER", RevisionStatus.SUPERSEDED, true);

        String body = readiness(f.link().getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(false))
                .andReturn().getResponse().getContentAsString();

        assertThat(codes(body, "PROBLEM")).containsExactly("NO_ACTIVE_REVISION");
        assertCheck(body, "INFO_NO_DRAFT_REVISION", "INFO", "DEFINITION", f.definition().getId());
    }

    // --- Met actieve revisie ---------------------------------------------------------------------------------------------

    @Test
    void anActiveChainWithEverythingInPlaceIsReadyAndTheUploadIsAccepted() throws Exception {
        Fixture f = fixture("READY", RevisionStatus.ACTIVE, true);
        List<Map<String, Object>> before = snapshot(f);

        String body = readiness(f.link().getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(true))
                .andReturn().getResponse().getContentAsString();

        assertThat(codes(body, "PROBLEM")).isEmpty();
        assertThat(codes(body, "OK")).containsExactly("READY_LINK_ACTIVE", "READY_ACTIVE_REVISION",
                "READY_PRICE_FIELD", "READY_LINK_BOOKMARKS", "READY_TASK_ACCEPTS_UPLOAD");
        assertThat(codes(body, "INFO")).containsExactly("INFO_LIBRARY_NOT_VERIFIED");
        assertCheck(body, "READY_ACTIVE_REVISION", "OK", "REVISION", f.revision().getId());
        assertThat(snapshot(f)).isEqualTo(before);

        upload(f.task().getId(), unique("REF")).andExpect(status().isCreated());
    }

    @Test
    void aMissingRequiredLinkBookmarkIsTheSameProblemAsTheUploadGives() throws Exception {
        Fixture f = fixture("LBMK", RevisionStatus.ACTIVE, true);
        declare(f.revision(), "CULTUUR", BookmarkValueScope.LINK, true);

        String body = readiness(f.link().getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(codes(body, "PROBLEM")).containsExactly("CONFIG_REQUIRED_BOOKMARK_MISSING");
        assertCheck(body, "CONFIG_REQUIRED_BOOKMARK_MISSING", "PROBLEM", "LINK", f.link().getId());

        upload(f.task().getId(), unique("REF")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFIG_REQUIRED_BOOKMARK_MISSING"))
                .andExpect(jsonPath("$.error").value(detail(body, "CONFIG_REQUIRED_BOOKMARK_MISSING")));

        // "" is geen invulling (R-BMK-03); een echte waarde wel.
        linkBookmarkValues.setValue(f.link().getId(), "CULTUUR", "", USER);
        readiness(f.link().getId()).andExpect(jsonPath("$.ready").value(false));
        linkBookmarkValues.setValue(f.link().getId(), "CULTUUR", "NL", USER);
        readiness(f.link().getId()).andExpect(jsonPath("$.ready").value(true));
    }

    /** Twee problemen op de actieve revisie: de checklist toont beide, de upload werpt het eerste (prijsveld). */
    @Test
    void anActiveRevisionWithoutPriceFieldAndAMissingLinkBookmarkShowsBothButTheUploadGivesThePriceCode()
            throws Exception {
        Fixture f = fixture("PRICE", RevisionStatus.ACTIVE, true);
        ImportDefinitionRevision active = f.revision();
        active.setRecordBasePriceField(null);
        active = revisions.saveAndFlush(active);
        declare(active, "CULTUUR", BookmarkValueScope.LINK, true);

        String body = readiness(f.link().getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(codes(body, "PROBLEM")).containsExactly("CONFIG_PRICE_FIELD_MISSING",
                "CONFIG_REQUIRED_BOOKMARK_MISSING");
        assertCheck(body, "CONFIG_PRICE_FIELD_MISSING", "PROBLEM", "REVISION", active.getId());

        upload(f.task().getId(), unique("REF")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFIG_PRICE_FIELD_MISSING"))
                .andExpect(jsonPath("$.error").value(detail(body, "CONFIG_PRICE_FIELD_MISSING")));
    }

    @Test
    void aLinkWithoutTaskIsNotReady() throws Exception {
        Fixture f = fixture("NOTASK", RevisionStatus.ACTIVE, false);

        String body = readiness(f.link().getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(false))
                .andReturn().getResponse().getContentAsString();

        assertThat(codes(body, "PROBLEM")).containsExactly("LINK_HAS_NO_TASK");
        assertThat(codes(body, "OK")).doesNotContain("READY_TASK_ACCEPTS_UPLOAD");
    }

    /** Per taak: een taak met Leveringsconfiguratie (A10) en een geplande taak, elk met de code van de upload. */
    @Test
    void aTaskWithADeliveryConfigurationAndAScheduledTaskAreReportedPerTaskWithTheUploadCodes() throws Exception {
        Fixture f = fixture("TASKS", RevisionStatus.ACTIVE, true);
        CatalogImportTask scheduled = new CatalogImportTask(f.link(), f.code() + "-gepland", TaskTriggerType.SCHEDULED);
        scheduled.setTriggerExpression("0 0 6 * * *");
        scheduled = tasks.saveAndFlush(scheduled);
        mockMvc.perform(put(API + "/tasks/{id}/delivery-configuration", f.task().getId())
                        .with(as(USER, Permission.MANAGE)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versionId\":" + deliveryConfigurationVersion() + ",\"reason\":\"Ophalen\"}"))
                .andExpect(status().isOk());

        String body = readiness(f.link().getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(false))
                .andReturn().getResponse().getContentAsString();

        assertThat(codes(body, "PROBLEM")).containsExactly("TASK_HAS_DELIVERY_CONFIGURATION", "TASK_NOT_MANUAL");
        assertCheck(body, "TASK_HAS_DELIVERY_CONFIGURATION", "PROBLEM", "TASK", f.task().getId());
        assertCheck(body, "TASK_NOT_MANUAL", "PROBLEM", "TASK", scheduled.getId());
        assertThat(codes(body, "OK")).doesNotContain("READY_TASK_ACCEPTS_UPLOAD");

        upload(f.task().getId(), unique("REF")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_HAS_DELIVERY_CONFIGURATION"))
                .andExpect(jsonPath("$.error").value(detail(body, "TASK_HAS_DELIVERY_CONFIGURATION")));
        upload(scheduled.getId(), unique("REF")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_NOT_MANUAL"))
                .andExpect(jsonPath("$.error").value(detail(body, "TASK_NOT_MANUAL")));
    }

    /** De upload controleert de actief-vlag van de koppeling niet; de checklist meldt ze dus enkel als informatie. */
    @Test
    void anInactiveLinkIsOnlyInformationalBecauseTheUploadDoesNotCheckIt() throws Exception {
        Fixture f = fixture("INACT", RevisionStatus.ACTIVE, true);
        ImportLink link = f.link();
        link.setActive(false);
        links.saveAndFlush(link);

        String body = readiness(link.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(true))
                .andReturn().getResponse().getContentAsString();
        assertCheck(body, "INFO_LINK_INACTIVE", "INFO", "LINK", link.getId());
        assertThat(codes(body, "OK")).doesNotContain("READY_LINK_ACTIVE");

        upload(f.task().getId(), unique("REF")).andExpect(status().isCreated());
    }

    // --- Rechten en onbekend ---------------------------------------------------------------------------------------------

    @Test
    void readIsEnoughWithoutRightsIs403AndAnUnknownLinkIs404() throws Exception {
        Fixture f = fixture("PERM", RevisionStatus.ACTIVE, true);

        mockMvc.perform(get(API + "/import-links/{id}/readiness", f.link().getId()).with(as(USER, Permission.READ)))
                .andExpect(status().isOk());
        mockMvc.perform(get(API + "/import-links/{id}/readiness", f.link().getId()).with(withoutPermissions(USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        mockMvc.perform(get(API + "/import-links/{id}/readiness", 999_999_999L).with(as(USER, Permission.READ)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"));
    }

    // --- Helpers -----------------------------------------------------------------------------------------------------------

    private ResultActions readiness(long linkId) throws Exception {
        return mockMvc.perform(get(API + "/import-links/{id}/readiness", linkId).with(as(USER, Permission.READ)));
    }

    private ResultActions upload(long taskId, String reference) throws Exception {
        return mockMvc.perform(multipart(API + "/tasks/{id}/deliveries", taskId)
                .file(new MockMultipartFile("file", "levering.csv", "text/csv", CSV))
                .param("deliveryReference", reference)
                .param("uploadedBy", USER)
                .with(as(USER)));
    }

    private ResultActions activate(long revisionId) throws Exception {
        return mockMvc.perform(post(API + "/setup/revisions/{id}/activate", revisionId).with(as(USER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"approvedBy\":\"" + USER + "\"}"));
    }

    /** De codes met deze status, in de volgorde van het antwoord. */
    private static List<String> codes(String body, String status) {
        List<Map<String, Object>> checks = JsonPath.read(body, "$.checks");
        List<String> codes = new ArrayList<>();
        for (Map<String, Object> check : checks) {
            if (status.equals(check.get("status"))) {
                codes.add((String) check.get("code"));
            }
        }
        return codes;
    }

    private static void assertCheck(String body, String code, String status, String subjectType, long subjectId) {
        List<Map<String, Object>> checks = JsonPath.read(body, "$.checks");
        assertThat(checks).as(code + " on " + subjectType + " " + subjectId).anySatisfy(check -> {
            assertThat(check.get("code")).isEqualTo(code);
            assertThat(check.get("status")).isEqualTo(status);
            @SuppressWarnings("unchecked")
            Map<String, Object> subject = (Map<String, Object>) check.get("subject");
            assertThat(subject.get("type")).isEqualTo(subjectType);
            assertThat(((Number) subject.get("id")).longValue()).isEqualTo(subjectId);
        });
    }

    /** De PROBLEM-rijen met subject REVISION {@code revisionId}, in de volgorde van het antwoord. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> revisionProblemRows(String body, long revisionId) {
        List<Map<String, Object>> checks = JsonPath.read(body, "$.checks");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> check : checks) {
            Map<String, Object> subject = (Map<String, Object>) check.get("subject");
            if ("PROBLEM".equals(check.get("status")) && "REVISION".equals(subject.get("type"))
                    && ((Number) subject.get("id")).longValue() == revisionId) {
                rows.add(check);
            }
        }
        return rows;
    }

    /** De technische toelichting van de eerste regel met deze code. */
    private static String detail(String body, String code) {
        List<Map<String, Object>> checks = JsonPath.read(body, "$.checks");
        return checks.stream().filter(check -> code.equals(check.get("code")))
                .map(check -> (String) check.get("detail")).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static String detailFor(String body, String code, String subjectType) {
        List<Map<String, Object>> checks = JsonPath.read(body, "$.checks");
        return checks.stream().filter(check -> code.equals(check.get("code"))
                        && subjectType.equals(((Map<String, Object>) check.get("subject")).get("type")))
                .map(check -> (String) check.get("detail")).findFirst().orElseThrow();
    }

    /**
     * De rijen van de keten, om "schrijft niets" te bewijzen: revisies, koppeling, taken, runs, leveringen, batches en
     * de bookmarkdeclaraties en -waarden.
     */
    private List<Map<String, Object>> snapshot(Fixture f) {
        long definitionId = f.definition().getId();
        long linkId = f.link().getId();
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll(jdbc.queryForList("select * from import_definition_revision where import_definition_id = ? "
                + "order by id", definitionId));
        rows.addAll(jdbc.queryForList("select * from import_link where id = ?", linkId));
        rows.addAll(jdbc.queryForList("select * from catalog_import_task where import_link_id = ? order by id",
                linkId));
        rows.addAll(jdbc.queryForList("select * from task_run where task_id in "
                + "(select id from catalog_import_task where import_link_id = ?) order by id", linkId));
        rows.addAll(jdbc.queryForList("select * from delivery where task_id in "
                + "(select id from catalog_import_task where import_link_id = ?) order by id", linkId));
        rows.addAll(jdbc.queryForList("select * from import_batch where import_link_id = ? order by id", linkId));
        rows.addAll(jdbc.queryForList("select * from import_link_bookmark_value where import_link_id = ? order by id",
                linkId));
        rows.addAll(jdbc.queryForList("select * from import_definition_bookmark_value where definition_revision_id in "
                + "(select id from import_definition_revision where import_definition_id = ?) order by id",
                definitionId));
        rows.addAll(jdbc.queryForList("select * from import_definition_bookmark where definition_revision_id in "
                + "(select id from import_definition_revision where import_definition_id = ?) order by id",
                definitionId));
        // Een bytea-kolom komt terug als byte[] (gelijkheid op referentie); vergelijk daarom de inhoud.
        List<Map<String, Object>> comparable = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>();
            row.forEach((column, value) -> copy.put(column,
                    value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : value));
            comparable.add(copy);
        }
        return comparable;
    }

    private ImportDefinitionBookmark declare(ImportDefinitionRevision revision, String name, BookmarkValueScope scope,
                                             boolean required) {
        ImportDefinitionBookmark bookmark = new ImportDefinitionBookmark(revision, name, name.toLowerCase(),
                BookmarkDataType.TEXT, scope, "catalogImport.manage", SEQUENCE.incrementAndGet());
        bookmark.setRequired(required);
        return bookmarks.saveAndFlush(bookmark);
    }

    /** Organisatie, definitie (OWN_DEFINITION), revisie 1 met de gegeven status, koppeling en optioneel een MANUAL-taak. */
    private Fixture fixture(String prefix, RevisionStatus status, boolean withTask) {
        String unique = unique("RDY") + "-" + prefix;
        SourceOrganisation organisation = organisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + " BV", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(new ImportDefinition(organisation, unique + "-DEF",
                unique + " catalogus", USER));
        ImportDefinitionRevision revision = new ImportDefinitionRevision(definition, 1,
                IdentityProfileKind.THREE_PART, USER);
        revision.setAccessConfigHash("1".repeat(64));
        revision.setStructureConfigHash("2".repeat(64));
        revision.setRecordRulesConfigHash("3".repeat(64));
        revision.setCompositeConfigHash("4".repeat(64));
        revision.setIdentitySupplierField("LEVERANCIER");
        revision.setIdentitySupplierGroupField("GROEP");
        revision.setIdentitySupplierReferenceField("REFERENTIE");
        revision.setStructureDelimiter(";");
        revision.setRecordBasePriceField("PRIJS");
        revision.setStatus(status);
        revision = revisions.saveAndFlush(revision);
        SourceOrganisation supplier = organisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + " leverancier", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, "PSARF050"));
        CatalogImportTask task = withTask
                ? tasks.saveAndFlush(new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL))
                : null;
        return new Fixture(unique, definition, revision, link, task);
    }

    private record Fixture(String code, ImportDefinition definition, ImportDefinitionRevision revision,
                           ImportLink link, CatalogImportTask task) {
    }

    /** Een Leveringsconfiguratie-versie om een taak aan te koppelen (zelfde opbouw als {@code TaskBindingHttpTest}). */
    private long deliveryConfigurationVersion() {
        ActorIdentity actor = new ActorIdentity(USER, "test-sub-" + USER);
        String host = "sftp.readiness" + SEQUENCE.incrementAndGet() + ".test";
        ExternalCredential credential = credentials.saveAndFlush(new ExternalCredential(UUID.randomUUID(),
                "NT-8 " + host, ExternalCredentialSecretKind.SFTP_PASSWORD, host, "v1:nt8-test:Nep", "nt8-test",
                USER, null, Instant.now()));
        long profileVersionId = profiles.create(new NewConnectionProfile(unique("PRF"), "Profiel", host, null, "lev",
                "PASSWORD", credential.getCredentialRef().toString(), "ssh-ed25519",
                ConnectionProfileHttpTest.fingerprint(), "Test"), actor).versions().get(0).id();
        return configurations.create(new NewDeliveryConfiguration(unique("DC"), "Levering", profileVersionId, "/out",
                "ALL_FILES", null, null, null, "Test"), actor).versions().get(0).id();
    }

    private static String unique(String prefix) {
        return prefix + RUN + SEQUENCE.incrementAndGet();
    }
}
