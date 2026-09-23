package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.dao.BatchBookmarkHashDao;
import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.DeliveryRepository;
import be.dda.catalogimport.dao.ImportDefinitionBookmarkRepository;
import be.dda.catalogimport.dao.ImportDefinitionBookmarkValueRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkBookmarkValueRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.dao.TaskRunRepository;
import be.dda.catalogimport.domain.BookmarkDataType;
import be.dda.catalogimport.domain.BookmarkValueScope;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.DefinitionUsageType;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionBookmark;
import be.dda.catalogimport.domain.ImportDefinitionBookmarkValue;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.ImportLinkBookmarkValue;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Bouwstap 5f: de twee blokkeerpunten van beslissingslog 23/09 keuze 6
 * (sjabloon-materialisatie-design.md §7), plus de vingerafdruk van de bookmarkwaarden op de batch.
 *
 * <h2>Wat hier bewezen wordt</h2>
 * <ul>
 *   <li><b>Bij activeren.</b> Een revisie met een verplichte DEFINITION-bookmark zonder waarde wordt
 *       geweigerd met 409 {@code CONFIG_REQUIRED_BOOKMARK_MISSING}; een uitdrukkelijk lege waarde
 *       ({@code ""}) bevredigt die verplichting niet (R-BMK-03); met een echte waarde slaagt het.</li>
 *   <li><b>Bij de start van een levering.</b> Een koppeling die een verplichte LINK-bookmark van de
 *       actieve revisie niet ingevuld heeft, krijgt 409 {@code CONFIG_REQUIRED_BOOKMARK_MISSING} en er
 *       is <b>aantoonbaar niets gearchiveerd</b> en niets geregistreerd — de batch komt dus ook niet op
 *       {@code BLOCKED} te staan (vraag Q4).</li>
 *   <li><b>{@code import_batch.bookmark_values_hash}</b> draagt na een geslaagde levering de SHA-256
 *       over de LINK-waarden van dat moment, en blijft {@code null} voor een koppeling zonder enige
 *       bookmarkwaarde.</li>
 * </ul>
 * Elke test bouwt een eigen keten met unieke codes: de database is gedeeld.
 */
@SpringBootTest
@ActiveProfiles("local")
class TemplateBlockingPointTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    /**
     * Uniek per testrun. Sinds het {@code local}-profiel op een echte PostgreSQL draait, blijft de
     * testdata tussen runs staan: een teller die bij elke JVM weer op 1 begint, botst dan bij de tweede
     * run op de uniciteitsconstraints van code-kolommen.
     */
    private static final String RUN = Long.toString(System.currentTimeMillis() % 1_000_000L, 36).toUpperCase();
    private static final String USER = "beheerder@example.test";
    private static final byte[] CSV = ("LEVERANCIER;GROEP;REFERENTIE;PRIJS\n"
            + "ACME;G1;R1;1,50\n").getBytes(StandardCharsets.UTF_8);

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @Autowired
    private SetupService setup;
    @Autowired
    private DeliveryIntakeService intake;
    @Autowired
    private LinkBookmarkValueService bookmarkValues;
    @Autowired
    private BatchBookmarkHashDao bookmarkHashes;
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
    private TaskRunRepository runs;
    @Autowired
    private DeliveryRepository deliveries;
    @Autowired
    private ImportDefinitionBookmarkRepository bookmarks;
    @Autowired
    private ImportDefinitionBookmarkValueRepository definitionValues;
    @Autowired
    private ImportLinkBookmarkValueRepository linkValues;

    // --- Blokkeerpunt 1: activeren -----------------------------------------------------------------

    @Test
    void refusesToActivateARevisionWithAnUnfilledRequiredDefinitionBookmark() {
        Fixture f = fixture("ACT", RevisionStatus.DRAFT);
        declare(f, "PRIJSBASIS", BookmarkValueScope.DEFINITION, true);

        assertThatThrownBy(() -> setup.activateRevision(f.revision().getId(), USER))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo("CONFIG_REQUIRED_BOOKMARK_MISSING");
        assertThat(revisions.findById(f.revision().getId()).orElseThrow().getStatus())
                .isEqualTo(RevisionStatus.DRAFT);

        // "" is "uitdrukkelijk leeg", niet "ingevuld" (R-BMK-03): de revisie blijft geblokkeerd.
        ImportDefinitionBookmarkValue empty = definitionValues.saveAndFlush(new ImportDefinitionBookmarkValue(
                f.revision(), "PRIJSBASIS", BookmarkDataType.TEXT, "", USER));
        assertThatThrownBy(() -> setup.activateRevision(f.revision().getId(), USER))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo("CONFIG_REQUIRED_BOOKMARK_MISSING");

        empty.setValueText("BRUTO");
        definitionValues.saveAndFlush(empty);
        assertThat(setup.activateRevision(f.revision().getId(), USER).status()).isEqualTo("ACTIVE");
    }

    @Test
    void anOptionalDefinitionBookmarkAndALinkBookmarkNeverBlockTheActivation() {
        Fixture f = fixture("ACTOK", RevisionStatus.DRAFT);
        declare(f, "OPMERKING", BookmarkValueScope.DEFINITION, false);
        // LINK-scope kan hier niet beoordeeld worden: er is nog geen of meer dan één koppeling (§7).
        declare(f, "CULTUUR", BookmarkValueScope.LINK, true);

        assertThat(setup.activateRevision(f.revision().getId(), USER).status()).isEqualTo("ACTIVE");
    }

    /**
     * Een sjabloon moet {@code ACTIVE} kunnen worden om materialiseerbaar te zijn (fase A3/A4), terwijl
     * zijn DEFINITION-bookmarks per constructie pas bij materialisatie ingevuld worden. De controle
     * hierboven slaat daarom niet op een {@link DefinitionUsageType#REUSABLE_TEMPLATE}.
     */
    @Test
    void aTemplateRevisionActivatesEvenWithAnUnfilledRequiredDefinitionBookmark() {
        Fixture f = fixture("TPL", RevisionStatus.DRAFT, DefinitionUsageType.REUSABLE_TEMPLATE);
        declare(f, "DETAILLEVERANCIER", BookmarkValueScope.DEFINITION, true);

        assertThat(setup.activateRevision(f.revision().getId(), USER).status()).isEqualTo("ACTIVE");
    }

    // --- Blokkeerpunt 2: de start van een levering --------------------------------------------------

    @Test
    void refusesAnUploadWithAnUnfilledRequiredLinkBookmarkAndArchivesNothing() throws Exception {
        Fixture f = fixture("DEL", RevisionStatus.ACTIVE);
        declare(f, "CULTUUR", BookmarkValueScope.LINK, true);
        long archivedBefore = archivedFileCount();

        assertThatThrownBy(() -> upload(f, "REF-1"))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo("CONFIG_REQUIRED_BOOKMARK_MISSING");

        // Niets gearchiveerd, niets geregistreerd: de weigering komt vóór stap A (archiveren).
        assertThat(archivedFileCount()).isEqualTo(archivedBefore);
        assertThat(deliveries.findByTaskIdOrderByReceivedAtDesc(f.task().getId())).isEmpty();
        assertThat(runs.findByTaskIdOrderByStartedAtDesc(f.task().getId())).isEmpty();

        // Een uitdrukkelijk lege waarde is geen invulling: de levering blijft geweigerd.
        bookmarkValues.setValue(f.link().getId(), "CULTUUR", "", USER);
        assertThatThrownBy(() -> upload(f, "REF-1"))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo("CONFIG_REQUIRED_BOOKMARK_MISSING");
        assertThat(archivedFileCount()).isEqualTo(archivedBefore);

        // Met een echte waarde gaat de levering door en draagt de batch de vingerafdruk ervan.
        bookmarkValues.setValue(f.link().getId(), "CULTUUR", "NL", USER);
        var result = upload(f, "REF-1");

        assertThat(result.created()).isTrue();
        assertThat(archivedFileCount()).isEqualTo(archivedBefore + 1);
        long batchId = result.delivery().batch().batchId();
        assertThat(bookmarkHashes.findBookmarkValuesHash(batchId))
                .isEqualTo(sha256("CULTUURNL"));
    }

    @Test
    void leavesTheBookmarkValuesHashNullForALinkWithoutAnyBookmarkValue() throws Exception {
        Fixture f = fixture("NOBMK", RevisionStatus.ACTIVE);

        var result = upload(f, "REF-1");

        // null betekent "geen bookmarkwaarden van toepassing", nooit de hash van een lege reeks.
        assertThat(bookmarkHashes.findBookmarkValuesHash(result.delivery().batch().batchId())).isNull();
    }

    @Test
    void anOrphanValueDoesNotBlockTheUploadButIsPartOfTheFingerprint() throws Exception {
        Fixture f = fixture("ORPH", RevisionStatus.ACTIVE);
        declare(f, "CULTUUR", BookmarkValueScope.LINK, true);
        bookmarkValues.setValue(f.link().getId(), "CULTUUR", "NL", USER);
        // Een waarde zonder declaratie in de actieve revisie: wordt niet toegepast en telt niet als
        // ingevuld, maar hoort wél bij de toestand van de koppeling op dit moment.
        linkValues.saveAndFlush(new ImportLinkBookmarkValue(f.link(), "BESTANDS_PREFIX",
                BookmarkDataType.TEXT, "ABP4", USER));

        var result = upload(f, "REF-1");

        assertThat(bookmarkHashes.findBookmarkValuesHash(result.delivery().batch().batchId()))
                .isEqualTo(sha256("BESTANDS_PREFIXABP4CULTUURNL"));
    }

    // --- Helpers -------------------------------------------------------------------------------------

    private DeliveryIntakeService.IntakeResult upload(Fixture f, String reference) {
        return intake.intake(f.task().getId(), reference, USER, null, null, "levering.csv",
                new ByteArrayInputStream(CSV));
    }

    private long archivedFileCount() throws Exception {
        try (var walk = Files.walk(archiveRoot)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    private static byte[] sha256(String canonical) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private ImportDefinitionBookmark declare(Fixture f, String name, BookmarkValueScope scope, boolean required) {
        ImportDefinitionBookmark bookmark = new ImportDefinitionBookmark(f.revision(), name, name.toLowerCase(),
                BookmarkDataType.TEXT, scope, "catalogImport.manage", SEQUENCE.incrementAndGet());
        bookmark.setRequired(required);
        return bookmarks.saveAndFlush(bookmark);
    }

    private Fixture fixture(String prefix, RevisionStatus status) {
        return fixture(prefix, status, DefinitionUsageType.OWN_DEFINITION);
    }

    private Fixture fixture(String prefix, RevisionStatus status, DefinitionUsageType usageType) {
        String unique = "BLK" + RUN + SEQUENCE.incrementAndGet() + "-" + prefix;
        SourceOrganisation organisation = organisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + " BV", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = new ImportDefinition(organisation, unique + "-DEF", unique + " catalogus",
                USER);
        definition.setUsageType(usageType);
        definition = definitions.saveAndFlush(definition);
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
        if (usageType == DefinitionUsageType.REUSABLE_TEMPLATE) {
            // Changeset 006-5 verbiedt een koppeling op een sjabloon; er is er hier ook geen nodig.
            return new Fixture(unique, definition, revision, null, null);
        }
        SourceOrganisation supplier = organisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + " leverancier", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, "PSARF050"));
        CatalogImportTask task = tasks.saveAndFlush(
                new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL));
        return new Fixture(unique, definition, revision, link, task);
    }

    private record Fixture(String code, ImportDefinition definition, ImportDefinitionRevision revision,
                           ImportLink link, CatalogImportTask task) {
    }
}
