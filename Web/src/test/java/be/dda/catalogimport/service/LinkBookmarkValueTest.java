package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.DeliveryRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportDefinitionBookmarkRepository;
import be.dda.catalogimport.dao.ImportDefinitionBookmarkUsageRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkBookmarkValueRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.BookmarkDataType;
import be.dda.catalogimport.domain.BookmarkUsagePlace;
import be.dda.catalogimport.domain.BookmarkValueScope;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.Delivery;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionBookmark;
import be.dda.catalogimport.domain.ImportDefinitionBookmarkUsage;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.ImportLinkBookmarkValue;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.service.LinkBookmarkValueService.LinkBookmarkValueRow;
import be.dda.catalogimport.service.LinkBookmarkValueService.LinkBookmarkValues;
import be.dda.catalogimport.web.CatalogImportLinkController;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Bouwstap 5f: de LINK-scope bookmarkwaarden van een koppeling — het slot bij een open batch
 * (beslissingslog 23/09 keuze 5), de volledige wijzigingsaudit, en de behandeling van een wees.
 *
 * <h2>Wat hier bewezen wordt</h2>
 * <ul>
 *   <li>Zolang de koppeling een niet-terminale batch heeft, wordt elke wijziging geweigerd met 409
 *       {@code LINK_BOOKMARK_LOCKED_BY_OPEN_BATCH}; zodra die batch terminaal is, mag het weer.</li>
 *   <li>Een wijziging schrijft {@code previous_value_text}, {@code updated_by} en {@code updated_at}
 *       altijd samen, en laat {@code filled_at}/{@code filled_by} ongemoeid.</li>
 *   <li>Een waarde waarvan de naam in de actieve revisie niet (meer) gedeclareerd staat, wordt getoond
 *       met {@code declared = false}, telt niet als ingevuld en kan niet gewijzigd worden — ze blijft
 *       staan als auditmateriaal.</li>
 *   <li>{@code ""} is een bewaarde waarde maar bevredigt een verplichte bookmark niet (R-BMK-03).</li>
 *   <li>Een ongeldige of te lange waarde blokkeert in plaats van stil bewaard te worden (D5/D6).</li>
 * </ul>
 * Elke test bouwt een eigen keten met unieke codes: de database is gedeeld.
 */
@SpringBootTest(properties = "catalogimport.setup-api.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("local")
class LinkBookmarkValueTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    /**
     * Uniek per testrun. Sinds het {@code local}-profiel op een echte PostgreSQL draait, blijft de
     * testdata tussen runs staan: een teller die bij elke JVM weer op 1 begint, botst dan bij de tweede
     * run op de uniciteitsconstraints van code-kolommen.
     */
    private static final String RUN = Long.toString(System.currentTimeMillis() % 1_000_000L, 36).toUpperCase();
    private static final String USER = "beheerder@example.test";

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @Autowired
    private LinkBookmarkValueService service;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ApplicationContext context;
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
    private DeliveryRepository deliveries;
    @Autowired
    private ImportBatchRepository batches;
    @Autowired
    private ImportDefinitionBookmarkRepository bookmarks;
    @Autowired
    private ImportDefinitionBookmarkUsageRepository usages;
    @Autowired
    private ImportLinkBookmarkValueRepository values;

    // --- Het slot (keuze 5) -----------------------------------------------------------------------

    @Test
    void refusesEveryChangeWhileTheLinkHasAnOpenBatchAndAllowsItAgainWhenThatBatchIsTerminal() {
        Fixture f = fixture("LOCK");
        declare(f, "CULTUUR", BookmarkDataType.TEXT, true);
        service.setValue(f.link().getId(), "CULTUUR", "NL", USER);
        ImportBatch batch = openBatch(f);

        assertThat(service.list(f.link().getId()).lockedByOpenBatch()).isTrue();
        assertThatThrownBy(() -> service.setValue(f.link().getId(), "CULTUUR", "FR", USER))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo("LINK_BOOKMARK_LOCKED_BY_OPEN_BATCH");
        // Niets gewijzigd: geen halve wijziging achtergelaten.
        assertThat(value(f, "CULTUUR").getValueText()).isEqualTo("NL");
        assertThat(value(f, "CULTUUR").getUpdatedAt()).isNull();

        batch.setStatus(ImportBatchStatus.SCREENED);
        batches.saveAndFlush(batch);

        assertThat(service.list(f.link().getId()).lockedByOpenBatch()).isFalse();
        assertThat(service.setValue(f.link().getId(), "CULTUUR", "FR", USER).valueText()).isEqualTo("FR");
    }

    @Test
    void theLockIsAlsoRefusedOverHttpWithItsStableCode() throws Exception {
        Fixture f = fixture("LOCKHTTP");
        declare(f, "CULTUUR", BookmarkDataType.TEXT, true);
        service.setValue(f.link().getId(), "CULTUUR", "NL", USER);
        openBatch(f);

        mockMvc.perform(put("/api/catalog-import/links/{id}/bookmark-values/{name}", f.link().getId(), "CULTUUR")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"FR\",\"updatedBy\":\"" + USER + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LINK_BOOKMARK_LOCKED_BY_OPEN_BATCH"));
    }

    /** De twee endpoints volgen dezelfde vlag als de setup-API (vraag Q1). */
    @Test
    void theLinkControllerExistsOnlyBecauseTheSetupApiFlagIsOnInThisTest() {
        assertThat(context.getBeanNamesForType(CatalogImportLinkController.class)).isNotEmpty();
    }

    // --- Wijzigingsaudit ---------------------------------------------------------------------------

    @Test
    void recordsThePreviousValueTheAuthorAndTheMomentTogetherAndKeepsTheOriginalFiller() {
        Fixture f = fixture("AUDIT");
        declare(f, "DETAILLEVERANCIER", BookmarkDataType.TEXT, true);

        LinkBookmarkValueRow first = service.setValue(f.link().getId(), "DETAILLEVERANCIER", "ABP4", "eerste@example.test");
        assertThat(first.valueText()).isEqualTo("ABP4");
        assertThat(first.previousValueText()).isNull();
        assertThat(first.updatedAt()).isNull();
        assertThat(first.updatedBy()).isNull();
        assertThat(first.filledBy()).isEqualTo("eerste@example.test");
        Instant filledAt = value(f, "DETAILLEVERANCIER").getFilledAt();
        assertThat(filledAt).isNotNull();

        LinkBookmarkValueRow changed = service.setValue(f.link().getId(), "DETAILLEVERANCIER", "ABP9", "tweede@example.test");

        assertThat(changed.valueText()).isEqualTo("ABP9");
        assertThat(changed.previousValueText()).isEqualTo("ABP4");
        assertThat(changed.updatedBy()).isEqualTo("tweede@example.test");
        assertThat(changed.updatedAt()).isNotNull();
        // De eerste invuller blijft: hij is niet de auteur van deze wijziging.
        assertThat(changed.filledBy()).isEqualTo("eerste@example.test");
        ImportLinkBookmarkValue stored = value(f, "DETAILLEVERANCIER");
        assertThat(stored.getPreviousValueText()).isEqualTo("ABP4");
        assertThat(stored.getUpdatedBy()).isEqualTo("tweede@example.test");
        assertThat(stored.getUpdatedAt()).isNotNull();
        assertThat(stored.getFilledAt()).isEqualTo(filledAt);
    }

    @Test
    void refusesAnActorNameThatIsMissingOrSystemAndWritesNothing() {
        Fixture f = fixture("ACTOR");
        declare(f, "CULTUUR", BookmarkDataType.TEXT, true);

        assertThatThrownBy(() -> service.setValue(f.link().getId(), "CULTUUR", "NL", " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.setValue(f.link().getId(), "CULTUUR", "NL", "system"))
                .isInstanceOf(IllegalArgumentException.class);
        // null is geen waarde: "" is een uitdrukkelijk lege waarde, een ontbrekend veld is een fout.
        assertThatThrownBy(() -> service.setValue(f.link().getId(), "CULTUUR", null, USER))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(values.findByImportLinkIdOrderByBookmarkNameAsc(f.link().getId())).isEmpty();
    }

    @Test
    void answers404ForAnUnknownLink() {
        assertThatThrownBy(() -> service.list(999_999_999L))
                .isInstanceOf(NotFoundException.class)
                .extracting(error -> ((NotFoundException) error).getCode())
                .isEqualTo("LINK_NOT_FOUND");
    }

    // --- Wezen -------------------------------------------------------------------------------------

    @Test
    void showsAnUndeclaredValueAsAnOrphanThatNeitherCountsAsFilledNorCanBeChanged() {
        Fixture f = fixture("ORPHAN");
        declare(f, "CULTUUR", BookmarkDataType.TEXT, true);
        service.setValue(f.link().getId(), "CULTUUR", "NL", USER);
        // Een waarde uit een vorige sjabloonversie: de declaratie bestaat niet meer, de waarde wel.
        values.saveAndFlush(new ImportLinkBookmarkValue(f.link(), "BESTANDS_PREFIX", BookmarkDataType.TEXT,
                "ABP4", "vorige@example.test"));

        LinkBookmarkValues view = service.list(f.link().getId());

        LinkBookmarkValueRow orphan = row(view, "BESTANDS_PREFIX");
        assertThat(orphan.declared()).isFalse();
        assertThat(orphan.required()).isFalse();
        assertThat(orphan.filled()).isFalse();
        assertThat(orphan.valueText()).isEqualTo("ABP4"); // blijft zichtbaar als auditmateriaal
        assertThat(row(view, "CULTUUR").declared()).isTrue();
        assertThat(row(view, "CULTUUR").filled()).isTrue();
        // Een wees maakt de koppeling niet onvolledig en wordt nergens toegepast.
        assertThat(view.missingRequiredNames()).isEmpty();

        assertThatThrownBy(() -> service.setValue(f.link().getId(), "BESTANDS_PREFIX", "ABP9", USER))
                .isInstanceOf(BadRequestException.class)
                .extracting(error -> ((BadRequestException) error).getCode())
                .isEqualTo("BOOKMARK_UNKNOWN");
        assertThat(value(f, "BESTANDS_PREFIX").getValueText()).isEqualTo("ABP4");
        assertThat(value(f, "BESTANDS_PREFIX").getUpdatedBy()).isNull();
    }

    @Test
    void showsTheDeclaredFlagAndTheMissingRequiredNamesOverHttp() throws Exception {
        Fixture f = fixture("HTTP");
        declare(f, "CULTUUR", BookmarkDataType.TEXT, true);
        declare(f, "DOELBIBLIOTHEEK", BookmarkDataType.TEXT, true);
        service.setValue(f.link().getId(), "CULTUUR", "NL", USER);
        values.saveAndFlush(new ImportLinkBookmarkValue(f.link(), "BESTANDS_PREFIX", BookmarkDataType.TEXT,
                "ABP4", "vorige@example.test"));

        mockMvc.perform(get("/api/catalog-import/links/{id}/bookmark-values", f.link().getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importLinkId").value(f.link().getId()))
                .andExpect(jsonPath("$.lockedByOpenBatch").value(false))
                // Op naam gesorteerd: BESTANDS_PREFIX, CULTUUR.
                .andExpect(jsonPath("$.values[0].bookmarkName").value("BESTANDS_PREFIX"))
                .andExpect(jsonPath("$.values[0].declared").value(false))
                .andExpect(jsonPath("$.values[0].filled").value(false))
                .andExpect(jsonPath("$.values[1].bookmarkName").value("CULTUUR"))
                .andExpect(jsonPath("$.values[1].declared").value(true))
                .andExpect(jsonPath("$.values[1].filled").value(true))
                .andExpect(jsonPath("$.missingRequiredNames[0]").value("DOELBIBLIOTHEEK"));
    }

    // --- null is niet "" en een ongeldige waarde blokkeert -----------------------------------------

    @Test
    void storesAnExplicitlyEmptyValueButNeverCountsItAsFillingARequiredBookmark() {
        Fixture f = fixture("EMPTY");
        declare(f, "CULTUUR", BookmarkDataType.TEXT, true);

        LinkBookmarkValueRow row = service.setValue(f.link().getId(), "CULTUUR", "", USER);

        assertThat(row.valueText()).isEmpty();
        assertThat(row.filled()).isFalse();
        assertThat(value(f, "CULTUUR").getValueText()).isEmpty();
        assertThat(service.list(f.link().getId()).missingRequiredNames()).containsExactly("CULTUUR");
        assertThat(service.missingRequiredValues(f.link().getId(), f.revision().getId()))
                .containsExactly("CULTUUR");
    }

    @Test
    void refusesAValueThatDoesNotMatchItsDeclarationInsteadOfStoringItSilently() {
        Fixture f = fixture("INVALID");
        ImportDefinitionBookmark aantal = declare(f, "AANTAL", BookmarkDataType.INTEGER, true);
        ImportDefinitionBookmark bib = declare(f, "DOELBIBLIOTHEEK", BookmarkDataType.TEXT, true);
        usages.saveAndFlush(new ImportDefinitionBookmarkUsage(bib, BookmarkUsagePlace.LINK_LIBRARY_CODE, ""));

        assertThatThrownBy(() -> service.setValue(f.link().getId(), "AANTAL", "veel", USER))
                .isInstanceOf(BadRequestException.class)
                .extracting(error -> ((BadRequestException) error).getCode())
                .isEqualTo("CONFIG_BOOKMARK_VALUE_INVALID");
        // import_link.library_code is varchar(20): een langere waarde past nooit in de doelkolom.
        assertThatThrownBy(() -> service.setValue(f.link().getId(), "DOELBIBLIOTHEEK", "X".repeat(21), USER))
                .isInstanceOf(BadRequestException.class)
                .extracting(error -> ((BadRequestException) error).getCode())
                .isEqualTo("CONFIG_BOOKMARK_VALUE_TOO_LONG");

        assertThat(values.findByImportLinkIdOrderByBookmarkNameAsc(f.link().getId())).isEmpty();
        assertThat(aantal.getId()).isNotNull();
    }

    // --- LINK_*-kolomsynchronisatie (5f-nalevering, beslissingslog 2026-09-23) ---------------------

    @Test
    void aLinkLibraryCodeBookmarkKeepsTheImportLinkColumnInSyncAfterAPut() {
        Fixture f = fixture("LIBCODE");
        ImportDefinitionBookmark bib = declare(f, "DOELBIBLIOTHEEK", BookmarkDataType.TEXT, true);
        usages.saveAndFlush(new ImportDefinitionBookmarkUsage(bib, BookmarkUsagePlace.LINK_LIBRARY_CODE, ""));

        LinkBookmarkValueRow row = service.setValue(f.link().getId(), "DOELBIBLIOTHEEK", "PSARF099", USER);

        assertThat(row.valueText()).isEqualTo("PSARF099");
        // Niet enkel de waarderij: de kolom op import_link moet dezelfde waarde dragen.
        ImportLink reloaded = links.findById(f.link().getId()).orElseThrow();
        assertThat(reloaded.getLibraryCode()).isEqualTo("PSARF099");

        // Een tweede wijziging houdt waarde en kolom nog steeds gelijk.
        service.setValue(f.link().getId(), "DOELBIBLIOTHEEK", "PSARF100", USER);
        assertThat(links.findById(f.link().getId()).orElseThrow().getLibraryCode()).isEqualTo("PSARF100");
        assertThat(value(f, "DOELBIBLIOTHEEK").getValueText()).isEqualTo("PSARF100");
    }

    @Test
    void anEmptyValueOnALinkLibraryCodeBookmarkIsBlockedInsteadOfClearingTheNotNullColumn() {
        Fixture f = fixture("LIBEMPTY");
        ImportDefinitionBookmark bib = declare(f, "DOELBIBLIOTHEEK", BookmarkDataType.TEXT, false);
        usages.saveAndFlush(new ImportDefinitionBookmarkUsage(bib, BookmarkUsagePlace.LINK_LIBRARY_CODE, ""));
        String originalLibraryCode = f.link().getLibraryCode();

        assertThatThrownBy(() -> service.setValue(f.link().getId(), "DOELBIBLIOTHEEK", "", USER))
                .isInstanceOf(BadRequestException.class)
                .extracting(error -> ((BadRequestException) error).getCode())
                .isEqualTo("CONFIG_REQUIRED_BOOKMARK_MISSING");
        // Geen halve wijziging: noch de bookmarkwaarde, noch de NOT NULL-kolom is aangeraakt.
        assertThat(values.findByImportLinkIdAndBookmarkName(f.link().getId(), "DOELBIBLIOTHEEK")).isEmpty();
        assertThat(links.findById(f.link().getId()).orElseThrow().getLibraryCode())
                .isEqualTo(originalLibraryCode);
    }

    @Test
    void aLinkSupplierOrganisationBookmarkResolvesAndUpdatesTheSupplierColumn() {
        Fixture f = fixture("SUPPLIER");
        String newSupplierCode = f.code() + "-NEWSUP";
        SourceOrganisation newSupplier = organisations.saveAndFlush(
                new SourceOrganisation(newSupplierCode, newSupplierCode + " leverancier",
                        SourceOrganisationType.SUPPLIER));
        ImportDefinitionBookmark leverancier = declare(f, "DETAILLEVERANCIER", BookmarkDataType.TEXT, true);
        usages.saveAndFlush(new ImportDefinitionBookmarkUsage(leverancier,
                BookmarkUsagePlace.LINK_SUPPLIER_ORGANISATION, ""));

        LinkBookmarkValueRow row = service.setValue(f.link().getId(), "DETAILLEVERANCIER", newSupplierCode, USER);

        assertThat(row.valueText()).isEqualTo(newSupplierCode);
        ImportLink reloaded = links.findById(f.link().getId()).orElseThrow();
        assertThat(reloaded.getSupplierOrganisation().getId()).isEqualTo(newSupplier.getId());
    }

    @Test
    void aLinkSupplierOrganisationBookmarkPointingAtAnUnknownCodeIs404AndWritesNothing() {
        Fixture f = fixture("SUPPLIERBAD");
        ImportDefinitionBookmark leverancier = declare(f, "DETAILLEVERANCIER", BookmarkDataType.TEXT, true);
        usages.saveAndFlush(new ImportDefinitionBookmarkUsage(leverancier,
                BookmarkUsagePlace.LINK_SUPPLIER_ORGANISATION, ""));
        Long originalSupplierId = f.link().getSupplierOrganisation().getId();

        assertThatThrownBy(() -> service.setValue(f.link().getId(), "DETAILLEVERANCIER",
                f.code() + "-DOESNOTEXIST", USER))
                .isInstanceOf(NotFoundException.class)
                .extracting(error -> ((NotFoundException) error).getCode())
                .isEqualTo("SOURCE_ORGANISATION_NOT_FOUND");
        // Geen halve wijziging: noch de bookmarkwaarde, noch de koppelingskolom is aangeraakt.
        assertThat(values.findByImportLinkIdAndBookmarkName(f.link().getId(), "DETAILLEVERANCIER")).isEmpty();
        assertThat(links.findById(f.link().getId()).orElseThrow().getSupplierOrganisation().getId())
                .isEqualTo(originalSupplierId);
    }

    @Test
    void aLinkSearchSupplierBookmarkFollowsTheValueDirectlyIncludingClearingItToEmpty() {
        Fixture f = fixture("SEARCHSUP");
        ImportDefinitionBookmark zoekleverancier = declare(f, "ZOEKLEVERANCIER", BookmarkDataType.TEXT, false);
        usages.saveAndFlush(new ImportDefinitionBookmarkUsage(zoekleverancier,
                BookmarkUsagePlace.LINK_SEARCH_SUPPLIER, ""));

        service.setValue(f.link().getId(), "ZOEKLEVERANCIER", "9001", USER);
        assertThat(links.findById(f.link().getId()).orElseThrow().getLibrarySearchSupplierCode())
                .isEqualTo("9001");

        // Deze kolom is nullable: een expliciet lege waarde mag ze wél leegmaken.
        service.setValue(f.link().getId(), "ZOEKLEVERANCIER", "", USER);
        assertThat(links.findById(f.link().getId()).orElseThrow().getLibrarySearchSupplierCode())
                .isEmpty();
    }

    @Test
    void theLockAlsoBlocksAChangeToALinkLibraryCodeBookmarkAndLeavesTheColumnUntouched() {
        Fixture f = fixture("LOCKLIB");
        ImportDefinitionBookmark bib = declare(f, "DOELBIBLIOTHEEK", BookmarkDataType.TEXT, true);
        usages.saveAndFlush(new ImportDefinitionBookmarkUsage(bib, BookmarkUsagePlace.LINK_LIBRARY_CODE, ""));
        service.setValue(f.link().getId(), "DOELBIBLIOTHEEK", "PSARF200", USER);
        openBatch(f);

        assertThatThrownBy(() -> service.setValue(f.link().getId(), "DOELBIBLIOTHEEK", "PSARF201", USER))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo("LINK_BOOKMARK_LOCKED_BY_OPEN_BATCH");
        assertThat(links.findById(f.link().getId()).orElseThrow().getLibraryCode()).isEqualTo("PSARF200");
    }

    // --- Helpers ------------------------------------------------------------------------------------

    private LinkBookmarkValueRow row(LinkBookmarkValues view, String name) {
        return view.values().stream().filter(row -> row.bookmarkName().equals(name)).findFirst().orElseThrow();
    }

    private ImportLinkBookmarkValue value(Fixture f, String name) {
        return values.findByImportLinkIdAndBookmarkName(f.link().getId(), name).orElseThrow();
    }

    private ImportDefinitionBookmark declare(Fixture f, String name, BookmarkDataType dataType, boolean required) {
        ImportDefinitionBookmark bookmark = new ImportDefinitionBookmark(f.revision(), name, name.toLowerCase(),
                dataType, BookmarkValueScope.LINK, "catalogImport.manage", SEQUENCE.incrementAndGet());
        bookmark.setRequired(required);
        return bookmarks.saveAndFlush(bookmark);
    }

    /** Een niet-terminale batch op de koppeling: precies wat het slot moet zien. */
    private ImportBatch openBatch(Fixture f) {
        Delivery delivery = deliveries.saveAndFlush(
                new Delivery(f.task(), "manual:" + f.code() + "-REF", Instant.now()));
        ImportBatch batch = batches.saveAndFlush(
                new ImportBatch(delivery, f.link(), f.revision(), 1, USER));
        assertThat(batch.getStatus().isTerminal()).isFalse();
        return batch;
    }

    private Fixture fixture(String prefix) {
        String unique = "LBV" + RUN + SEQUENCE.incrementAndGet() + "-" + prefix;
        SourceOrganisation organisation = organisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + " BV", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(
                new ImportDefinition(organisation, unique + "-DEF", unique + " catalogus", USER));
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
        revision.setStatus(RevisionStatus.ACTIVE);
        revision = revisions.saveAndFlush(revision);
        SourceOrganisation supplier = organisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + " leverancier", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, "PSARF050"));
        CatalogImportTask task = tasks.saveAndFlush(
                new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL));
        return new Fixture(unique, link, revision, task);
    }

    private record Fixture(String code, ImportLink link, ImportDefinitionRevision revision,
                           CatalogImportTask task) {
    }
}
