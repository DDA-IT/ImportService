package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static be.dda.catalogimport.testsupport.TestActors.withoutPermissions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.ImportDefinitionBookmarkRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkBookmarkValueRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.BookmarkDataType;
import be.dda.catalogimport.domain.BookmarkValueScope;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionBookmark;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.ImportLinkBookmarkValue;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import com.jayway.jsonpath.JsonPath;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Bulk-gereedheid {@code GET /import-links/readiness?linkIds=} (beslissingslog 2026-10-23 "API-uitbreiding voor de
 * inrichtingsschermen", punt 4).
 *
 * <ul>
 *   <li><b>Regel (pariteit):</b> per koppeling hetzelfde oordeel en dezelfde PROBLEM-regels (code, onderwerp, tekst)
 *       als {@code GET /import-links/{id}/readiness}. <b>Implementatie:</b> één gedeelde beoordeling in
 *       {@code ImportLinkReadinessService}; bewezen per toestand hieronder.</li>
 *   <li><b>Regel:</b> enkel de problemen; volgorde = de aanvraag, dubbels één keer.</li>
 *   <li><b>Regel:</b> 1 tot en met 100 ids, anders 400 {@code READINESS_LINK_IDS_INVALID}; een onbekende koppeling
 *       404 {@code LINK_NOT_FOUND}; {@code READ} volstaat, zonder recht 403.</li>
 *   <li><b>Regel (memo):</b> wat per concept gedeeld wordt, lekt niet tussen koppelingen: de LINK-bookmarks blijven per
 *       koppeling.</li>
 * </ul>
 * Elke test bouwt een eigen keten met unieke codes: de database is gedeeld.
 */
@SpringBootTest(properties = {"catalogimport.screening.recovery-on-startup=false"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ImportLinkReadinessBulkHttpTest {

    private static final String API = "/api/catalog-import";
    private static final String USER = "an.janssens@example.test";
    private static final long UNKNOWN = 999_999_999L;
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    /** Uniek per testrun: het {@code local}-profiel draait op een gedeelde PostgreSQL. */
    private static final String RUN = Long.toString(System.currentTimeMillis() % 1_000_000L, 36).toUpperCase();

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
    private ImportLinkBookmarkValueRepository linkBookmarkValues;

    // --- Pariteit met het enkelvoudige endpoint ------------------------------------------------------------------------

    @Test
    void everyStateHasTheSameVerdictAndProblemsAsTheSingleEndpoint() throws Exception {
        Fixture ready = fixture("READY", RevisionStatus.ACTIVE, true);
        Fixture noTask = fixture("NOTASK", RevisionStatus.ACTIVE, false);
        Fixture activatableDraft = fixture("DRAFTOK", RevisionStatus.DRAFT, true);
        Fixture brokenDraft = fixture("DRAFTBAD", RevisionStatus.DRAFT, true);
        ImportDefinitionRevision broken = brokenDraft.revision();
        broken.setIdentitySupplierField(" ");
        broken.setRecordBasePriceField(null);
        revisions.saveAndFlush(broken);
        Fixture missingBookmark = fixture("LBMK", RevisionStatus.ACTIVE, true);
        declare(missingBookmark.revision(), "CULTUUR", BookmarkValueScope.LINK, true);
        List<Fixture> all = List.of(ready, noTask, activatableDraft, brokenDraft, missingBookmark);

        String bulk = bulk(all.stream().map(f -> f.link().getId()).toList()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(linkIds(bulk)).containsExactlyElementsOf(all.stream().map(f -> f.link().getId()).toList());
        for (int i = 0; i < all.size(); i++) {
            assertParity(bulk, i, all.get(i).link().getId());
        }
        // Wat de toestanden zijn, zodat de pariteit niet triviaal (alles leeg) slaagt.
        assertThat(JsonPath.<Boolean>read(bulk, "$[0].ready")).isTrue();
        assertThat(problemCodes(bulk, 0)).isEmpty();
        assertThat(problemCodes(bulk, 1)).containsExactly("LINK_HAS_NO_TASK");
        assertThat(problemCodes(bulk, 2)).containsExactly("NO_ACTIVE_REVISION");
        assertThat(problemCodes(bulk, 3)).startsWith("NO_ACTIVE_REVISION", "CONFIG_IDENTITY_FIELD_MISSING")
                .contains("CONFIG_PRICE_FIELD_MISSING");
        assertThat(problemCodes(bulk, 4)).containsExactly("CONFIG_REQUIRED_BOOKMARK_MISSING");
        // Enkel PROBLEM-regels; OK en INFO staan er niet in.
        assertThat(JsonPath.<List<String>>read(bulk, "$[*].problems[*].status")).containsOnly("PROBLEM");
        assertThat(JsonPath.<List<Object>>read(bulk, "$[*].checks")).isEmpty();
    }

    /**
     * Twee koppelingen op één definitie met een concept dat een verplichte LINK-bookmark declareert: het concept wordt
     * gedeeld getoetst, maar de bookmarkwaarde blijft per koppeling (de ene heeft ze, de andere niet).
     */
    @Test
    void linksSharingADefinitionShareTheDraftCheckButKeepTheirOwnBookmarks() throws Exception {
        Fixture first = fixture("SHARED", RevisionStatus.DRAFT, true);
        ImportDefinitionRevision draft = first.revision();
        draft.setRecordBasePriceField(null);
        revisions.saveAndFlush(draft);
        declare(draft, "CULTUUR", BookmarkValueScope.LINK, true);
        ImportLink second = secondLink(first);
        // Rechtstreeks bewaard: de service vult enkel waarden in tegen een actieve revisie, hier is er nog geen.
        linkBookmarkValues.saveAndFlush(new ImportLinkBookmarkValue(first.link(), "CULTUUR", BookmarkDataType.TEXT,
                "NL", USER));

        String bulk = bulk(List.of(first.link().getId(), second.getId())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertParity(bulk, 0, first.link().getId());
        assertParity(bulk, 1, second.getId());
        assertThat(problemCodes(bulk, 0)).containsExactly("NO_ACTIVE_REVISION", "CONFIG_PRICE_FIELD_MISSING");
        assertThat(problemCodes(bulk, 1)).containsExactly("NO_ACTIVE_REVISION", "CONFIG_PRICE_FIELD_MISSING",
                "CONFIG_REQUIRED_BOOKMARK_MISSING", "LINK_HAS_NO_TASK");
    }

    // --- Volgorde en dubbels -------------------------------------------------------------------------------------------

    @Test
    void theRequestOrderIsKeptAndDuplicatesAreAnsweredOnce() throws Exception {
        long a = fixture("ORDA", RevisionStatus.ACTIVE, true).link().getId();
        long b = fixture("ORDB", RevisionStatus.ACTIVE, false).link().getId();
        long c = fixture("ORDC", RevisionStatus.DRAFT, true).link().getId();

        String commaSeparated = mockMvc.perform(get(API + "/import-links/readiness")
                        .param("linkIds", c + "," + a + "," + c + "," + b + "," + a)
                        .with(as(USER, Permission.READ)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(linkIds(commaSeparated)).containsExactly(c, a, b);

        // Herhaalde parameter geeft hetzelfde antwoord.
        String repeated = mockMvc.perform(get(API + "/import-links/readiness")
                        .param("linkIds", Long.toString(c), Long.toString(a), Long.toString(c), Long.toString(b))
                        .with(as(USER, Permission.READ)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(repeated).isEqualTo(commaSeparated);
    }

    // --- Ongeldige aanvraag, onbekend, grenzen -------------------------------------------------------------------------

    @Test
    void noIdsOrMoreThanOneHundredIsABadRequestWithAStableCode() throws Exception {
        long known = fixture("BAD", RevisionStatus.ACTIVE, true).link().getId();

        mockMvc.perform(get(API + "/import-links/readiness").with(as(USER, Permission.READ)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("READINESS_LINK_IDS_INVALID"));
        mockMvc.perform(get(API + "/import-links/readiness").param("linkIds", "").with(as(USER, Permission.READ)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("READINESS_LINK_IDS_INVALID"));
        String hundredAndOne = LongStream.rangeClosed(1, 101).mapToObj(i -> Long.toString(known))
                .collect(Collectors.joining(","));
        mockMvc.perform(get(API + "/import-links/readiness").param("linkIds", hundredAndOne)
                        .with(as(USER, Permission.READ)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("READINESS_LINK_IDS_INVALID"));
        // Geen getal: 400 (Spring-conversie, zoals elke andere numerieke parameter van de API).
        mockMvc.perform(get(API + "/import-links/readiness").param("linkIds", known + ",abc")
                        .with(as(USER, Permission.READ)))
                .andExpect(status().isBadRequest());
    }

    /** Grens: precies 100 opgegeven ids (hier 100 keer dezelfde) is toegelaten en geeft één regel. */
    @Test
    void exactlyOneHundredIdsIsAccepted() throws Exception {
        long known = fixture("HUNDRED", RevisionStatus.ACTIVE, true).link().getId();
        List<Long> hundred = Collections.nCopies(100, known);

        String body = bulk(hundred).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(linkIds(body)).containsExactly(known);
    }

    @Test
    void anUnknownLinkAmongKnownOnesIs404LinkNotFound() throws Exception {
        long known = fixture("UNK", RevisionStatus.ACTIVE, true).link().getId();

        bulk(List.of(known, UNKNOWN)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"))
                .andExpect(jsonPath("$.error").value("Import link " + UNKNOWN + " does not exist"));
    }

    // --- Rechten -------------------------------------------------------------------------------------------------------

    @Test
    void readIsEnoughAndWithoutRightsIs403() throws Exception {
        long known = fixture("PERM", RevisionStatus.ACTIVE, true).link().getId();

        mockMvc.perform(get(API + "/import-links/readiness").param("linkIds", Long.toString(known))
                        .with(as(USER, Permission.READ)))
                .andExpect(status().isOk());
        mockMvc.perform(get(API + "/import-links/readiness").param("linkIds", Long.toString(known))
                        .with(withoutPermissions(USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        // Recht eerst: ook een ongeldige aanvraag geeft zonder recht 403, niet 400.
        mockMvc.perform(get(API + "/import-links/readiness").with(withoutPermissions(USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    // --- Helpers -------------------------------------------------------------------------------------------------------

    private ResultActions bulk(List<Long> ids) throws Exception {
        String joined = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        return mockMvc.perform(get(API + "/import-links/readiness").param("linkIds", joined)
                .with(as(USER, Permission.READ)));
    }

    /** Oordeel en PROBLEM-regels van bulkregel {@code index} zijn gelijk aan die van het enkelvoudige endpoint. */
    private void assertParity(String bulk, int index, long linkId) throws Exception {
        String single = mockMvc.perform(get(API + "/import-links/{id}/readiness", linkId)
                        .with(as(USER, Permission.READ)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> checks = JsonPath.read(single, "$.checks");
        List<Map<String, Object>> singleProblems = new ArrayList<>();
        for (Map<String, Object> check : checks) {
            if ("PROBLEM".equals(check.get("status"))) {
                singleProblems.add(check);
            }
        }
        assertThat(((Number) JsonPath.read(bulk, "$[" + index + "].linkId")).longValue()).isEqualTo(linkId);
        assertThat(JsonPath.<Boolean>read(bulk, "$[" + index + "].ready")).as("ready of link " + linkId)
                .isEqualTo(JsonPath.<Boolean>read(single, "$.ready"));
        List<Map<String, Object>> bulkProblems = JsonPath.read(bulk, "$[" + index + "].problems");
        assertThat(bulkProblems).as("problems of link " + linkId).isEqualTo(singleProblems);
    }

    private static List<Long> linkIds(String body) {
        List<Number> ids = JsonPath.read(body, "$[*].linkId");
        return ids.stream().map(Number::longValue).toList();
    }

    private static List<String> problemCodes(String body, int index) {
        return JsonPath.read(body, "$[" + index + "].problems[*].code");
    }

    private void declare(ImportDefinitionRevision revision, String name, BookmarkValueScope scope, boolean required) {
        ImportDefinitionBookmark bookmark = new ImportDefinitionBookmark(revision, name, name.toLowerCase(),
                BookmarkDataType.TEXT, scope, "catalogImport.manage", SEQUENCE.incrementAndGet());
        bookmark.setRequired(required);
        bookmarks.saveAndFlush(bookmark);
    }

    /** Een tweede koppeling (zonder taak) op dezelfde definitie, met een eigen leverancier. */
    private ImportLink secondLink(Fixture f) {
        String unique = unique("BLK2");
        SourceOrganisation supplier = organisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + " leverancier", SourceOrganisationType.SUPPLIER));
        return links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", f.definition(), supplier, "PSARF050"));
    }

    /** Organisatie, definitie, revisie 1 met de gegeven status, koppeling en optioneel een MANUAL-taak. */
    private Fixture fixture(String prefix, RevisionStatus status, boolean withTask) {
        String unique = unique("BLK") + "-" + prefix;
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
        return new Fixture(definition, revision, link, task);
    }

    private record Fixture(ImportDefinition definition, ImportDefinitionRevision revision, ImportLink link,
                           CatalogImportTask task) {
    }

    private static String unique(String prefix) {
        return prefix + RUN + SEQUENCE.incrementAndGet();
    }
}
