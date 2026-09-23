package be.dda.catalogimport.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.DeliveryRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fase 1-achtige bouwstap sjabloon + bookmarks (docs/decisions.md 2026-09-23, changeset 006): bewijst
 * dat changeset 006 migreert, dat Hibernate de vier nieuwe entiteiten met {@code ddl-auto: validate}
 * aanvaardt (het booten van deze context is dat bewijs) en dat de databaseconstraints werkelijk
 * afdwingen wat ze beloven.
 * <p>
 * De vier nieuwe tabellen worden hier grotendeels via {@link JdbcTemplate} benaderd: deze bouwstap
 * levert bewust alleen entiteiten en migratie, nog geen Dao-/servicelaag. Eén test schrijft de
 * entiteiten wél via JPA weg, zodat de mapping ook bij het werkelijk invoegen klopt en niet alleen bij
 * het valideren van het schema.
 * <p>
 * Alle codes krijgen een per-run uniek achtervoegsel ({@link #RUN}). Dat wijkt af van de bestaande
 * schematests, die vaste codes gebruiken omdat ze een verse H2 per run veronderstellen; draait het
 * {@code local}-profiel tegen een blijvende PostgreSQL, dan lopen die tests bij de tweede run stuk op
 * hun eigen achtergelaten rijen. Deze test is daarom herhaalbaar op beide.
 */
@SpringBootTest
@ActiveProfiles("local")
class ImportTemplateBookmarkSchemaTest {

    /** Per-run achtervoegsel, zodat dezelfde test twee keer draaien geen unique constraint breekt. */
    private static final String RUN = Long.toString(System.nanoTime() % 1_000_000_000L, 36).toUpperCase();

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
    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    // --- (a) declaratie: uniciteit en naamformaat -------------------------------------------------

    @Test
    void rejectsTheSameBookmarkNameTwiceWithinOneRevision() {
        long revisionId = templateRevision("A1");
        insertBookmark(revisionId, "BESTANDS_PREFIX", "TEXT", "DEFINITION", 1, null);

        // uk_import_definition_bookmark_name: één naam per revisie, anders is niet meer te bepalen
        // welke declaratie een ingevulde waarde bedoelt.
        assertThatThrownBy(() -> insertBookmark(revisionId, "BESTANDS_PREFIX", "TEXT", "LINK", 2, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsTheSameBookmarkNameInADifferentRevision() {
        long first = templateRevision("A2A");
        long second = templateRevision("A2B");
        insertBookmark(first, "CULTUUR", "TEXT", "DEFINITION", 1, null);

        // Een opvolgrevisie herdeclareert dezelfde namen; dat mag en moet.
        assertThatCode(() -> insertBookmark(second, "CULTUUR", "TEXT", "DEFINITION", 1, null))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsTwoBookmarksWithTheSameSortOrderWithinOneRevision() {
        long revisionId = templateRevision("A3");
        insertBookmark(revisionId, "EERSTE", "TEXT", "DEFINITION", 1, null);

        assertThatThrownBy(() -> insertBookmark(revisionId, "TWEEDE", "TEXT", "DEFINITION", 1, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsABookmarkNameThatIsNotAnUppercaseTechnicalName() {
        long revisionId = templateRevision("A4");

        assertThatThrownBy(() -> insertBookmark(revisionId, "bad-name", "TEXT", "DEFINITION", 1, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertBookmark(revisionId, "1CULTUUR", "TEXT", "DEFINITION", 2, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertBookmark(revisionId, "MET SPATIE", "TEXT", "DEFINITION", 3, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> insertBookmark(revisionId, "BESTANDS_PREFIX2", "TEXT", "DEFINITION", 4, null))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAnUnknownDataTypeAndAnUnknownValueScope() {
        long revisionId = templateRevision("A5");

        assertThatThrownBy(() -> insertBookmark(revisionId, "ONBEKEND_TYPE", "MONEY", "DEFINITION", 1, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        // BATCH is geen scope: enkel DEFINITION en LINK bestaan (beslissingslog 23/09 keuze 1).
        assertThatThrownBy(() -> insertBookmark(revisionId, "ONBEKENDE_SCOPE", "TEXT", "BATCH", 2, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refusesAnEnumBookmarkWithoutAllowedValuesButAcceptsItWithThem() {
        long revisionId = templateRevision("A6");

        // Een keuzelijst zonder keuzes is altijd een configuratiefout.
        assertThatThrownBy(() -> insertBookmark(revisionId, "KEUZE_LEEG", "ENUM", "DEFINITION", 1, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> insertBookmark(revisionId, "KEUZE_OK", "ENUM", "DEFINITION", 2, "NL,FR,EN"))
                .doesNotThrowAnyException();
    }

    @Test
    void defaultsRequiredToTrueSoAnUnfilledBookmarkBlocksRatherThanPassesSilently() {
        long revisionId = templateRevision("A7");
        jdbc.update("insert into import_definition_bookmark (definition_revision_id, name, label, data_type,"
                        + " value_scope, owner_role, sort_order, created_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?)",
                revisionId, "ZONDER_REQUIRED", "Zonder required", "TEXT", "DEFINITION", "catalogImport.manage",
                1, OffsetDateTime.now());

        Boolean required = jdbc.queryForObject(
                "select required from import_definition_bookmark where definition_revision_id = ? and name = ?",
                Boolean.class, revisionId, "ZONDER_REQUIRED");
        assertThat(required).isTrue();
    }

    // --- (a/dubbel) witte lijst van configuratieplaatsen -------------------------------------------

    @Test
    void refusesDeliveryFileSelectionBecauseThereIsNoDeliveryConfigurationYet() {
        long revisionId = templateRevision("B1");
        long bookmarkId = insertBookmark(revisionId, "BESTANDS_PREFIX", "TEXT", "DEFINITION", 1, null);

        // Beslissingslog 23/09 keuze 3: bewust weggelaten tot Leveringsconfiguratie bestaat.
        assertThatThrownBy(() -> insertUsage(bookmarkId, "DELIVERY_FILE_SELECTION", ""))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void acceptsTheSevenWhitelistedPlacesAndRefusesADuplicateEntry() {
        long revisionId = templateRevision("B2");
        long bookmarkId = insertBookmark(revisionId, "CULTUUR", "TEXT", "DEFINITION", 1, null);

        for (BookmarkUsagePlace place : BookmarkUsagePlace.values()) {
            insertUsage(bookmarkId, place.name(), "");
        }
        assertThat(BookmarkUsagePlace.values()).hasSize(7);
        assertThat(jdbc.queryForObject(
                "select count(*) from import_definition_bookmark_usage where bookmark_id = ?",
                Integer.class, bookmarkId)).isEqualTo(7);

        // target_hint is not null default '': dezelfde plaats zonder nadere aanduiding kan niet twee
        // keer in de witte lijst belanden (met een nullable kolom zou dat wél kunnen).
        assertThatThrownBy(() -> insertUsage(bookmarkId, "FIELD_MAPPING_FIXED_VALUE", ""))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsTheSamePlaceTwiceWhenTheTargetHintDiffers() {
        long revisionId = templateRevision("B3");
        long bookmarkId = insertBookmark(revisionId, "VASTE_WAARDE", "TEXT", "DEFINITION", 1, null);

        insertUsage(bookmarkId, "FIELD_MAPPING_FIXED_VALUE", "BRAND");
        assertThatCode(() -> insertUsage(bookmarkId, "FIELD_MAPPING_FIXED_VALUE", "DESCRIPTION"))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesAUsageRowWithoutATargetHintValue() {
        long revisionId = templateRevision("B4");
        long bookmarkId = insertBookmark(revisionId, "GEEN_HINT", "TEXT", "DEFINITION", 1, null);

        assertThatThrownBy(() -> jdbc.update(
                "insert into import_definition_bookmark_usage (bookmark_id, place_kind, target_hint, created_at)"
                        + " values (?, ?, ?, ?)",
                bookmarkId, "REVISION_PRICE_POLICY", null, OffsetDateTime.now()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- (b) waarden: '' blijft '', NULL kan niet -------------------------------------------------

    @Test
    void keepsAnEmptyDefinitionScopeValueAsEmptyStringAndNeverAsNull() {
        long revisionId = templateRevision("C1");
        insertDefinitionValue(revisionId, "LEEG_INGEVULD", "TEXT", "");

        String stored = jdbc.queryForObject(
                "select value_text from import_definition_bookmark_value"
                        + " where definition_revision_id = ? and bookmark_name = ?",
                String.class, revisionId, "LEEG_INGEVULD");
        assertThat(stored).isNotNull().isEmpty();

        // "niet ingevuld" is de afwezigheid van een rij, niet NULL in deze kolom.
        assertThatThrownBy(() -> insertDefinitionValue(revisionId, "NULL_INGEVULD", "TEXT", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void keepsAnEmptyLinkScopeValueAsEmptyStringAndNeverAsNull() {
        Scenario scenario = scenario("C2");
        insertLinkValue(scenario.linkId(), "LEEG_PER_KOPPELING", "TEXT", "");

        String stored = jdbc.queryForObject(
                "select value_text from import_link_bookmark_value"
                        + " where import_link_id = ? and bookmark_name = ?",
                String.class, scenario.linkId(), "LEEG_PER_KOPPELING");
        assertThat(stored).isNotNull().isEmpty();

        assertThatThrownBy(() -> insertLinkValue(scenario.linkId(), "NULL_PER_KOPPELING", "TEXT", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refusesTheSameDefinitionScopeBookmarkTwiceSoRerunningMaterialisationCannotDuplicate() {
        long revisionId = templateRevision("C3");
        insertDefinitionValue(revisionId, "PREFIX", "TEXT", "VROOAM_");

        // Twee keer materialiseren mag nooit twee snapshots van dezelfde bookmark opleveren.
        assertThatThrownBy(() -> insertDefinitionValue(revisionId, "PREFIX", "TEXT", "ANDERS_"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refusesTheSameLinkScopeBookmarkTwiceForOneLinkButAllowsItForAnotherLink() {
        Scenario first = scenario("C4A");
        Scenario second = scenario("C4B");
        insertLinkValue(first.linkId(), "CULTUUR", "TEXT", "NL");
        assertThatCode(() -> insertLinkValue(second.linkId(), "CULTUUR", "TEXT", "FR"))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> insertLinkValue(first.linkId(), "CULTUUR", "TEXT", "FR"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void acceptsAValueOfExactlyTheMaximumLengthThatStillFitsTheMaterialisationTarget() {
        long revisionId = templateRevision("C5");
        String maximum = "X".repeat(500);

        assertThatCode(() -> insertDefinitionValue(revisionId, "LANGE_WAARDE", "TEXT", maximum))
                .doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("select length(value_text) from import_definition_bookmark_value"
                        + " where definition_revision_id = ? and bookmark_name = ?",
                Integer.class, revisionId, "LANGE_WAARDE")).isEqualTo(500);
    }

    @Test
    void keepsALinkValueWhoseDeclarationNoLongerExistsBecauseThereIsDeliberatelyNoForeignKey() {
        Scenario scenario = scenario("C6");

        // Geen enkele import_definition_bookmark-rij met deze naam: de waarde blijft als
        // auditmateriaal staan wanneer een opvolgrevisie de declaratie laat vallen.
        assertThatCode(() -> insertLinkValue(scenario.linkId(), "WEES_BOOKMARK", "TEXT", "blijft staan"))
                .doesNotThrowAnyException();
    }

    // --- wijzigingsaudit op een LINK-waarde --------------------------------------------------------

    @Test
    void refusesAHalfRecordedChangeOnALinkScopeValue() {
        Scenario scenario = scenario("D1");
        insertLinkValue(scenario.linkId(), "AUDIT_HALF", "TEXT", "eerste");

        // updated_at zonder updated_by: wel een tijdstip, geen naam.
        assertThatThrownBy(() -> jdbc.update("update import_link_bookmark_value set updated_at = ?"
                        + " where import_link_id = ? and bookmark_name = ?",
                OffsetDateTime.now(), scenario.linkId(), "AUDIT_HALF"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refusesAPreviousValueWithoutAChangeThatExplainsIt() {
        Scenario scenario = scenario("D2");
        insertLinkValue(scenario.linkId(), "AUDIT_PREV", "TEXT", "eerste");

        assertThatThrownBy(() -> jdbc.update("update import_link_bookmark_value set previous_value_text = ?"
                        + " where import_link_id = ? and bookmark_name = ?",
                "verzonnen", scenario.linkId(), "AUDIT_PREV"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void acceptsAFullyRecordedChangeAndKeepsTheOldValueVisible() {
        Scenario scenario = scenario("D3");
        insertLinkValue(scenario.linkId(), "AUDIT_OK", "TEXT", "NL");

        jdbc.update("update import_link_bookmark_value set value_text = ?, previous_value_text = ?,"
                        + " updated_at = ?, updated_by = ? where import_link_id = ? and bookmark_name = ?",
                "FR", "NL", OffsetDateTime.now(), "beheerder@example.test", scenario.linkId(), "AUDIT_OK");

        assertThat(jdbc.queryForMap("select value_text, previous_value_text, updated_by"
                        + " from import_link_bookmark_value where import_link_id = ? and bookmark_name = ?",
                scenario.linkId(), "AUDIT_OK"))
                .containsEntry("value_text", "FR")
                .containsEntry("previous_value_text", "NL")
                .containsEntry("updated_by", "beheerder@example.test");
    }

    // --- (d) de guard van 006-5 --------------------------------------------------------------------

    @Test
    void refusesAnImportLinkOnAReusableTemplateDefinition() {
        SourceOrganisation organisation = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(code("E1-ORG"), "E1 aankoopvereniging", SourceOrganisationType.PURCHASING_ASSOCIATION));
        ImportDefinition template = new ImportDefinition(organisation, code("E1-TPL"),
                "VROOAM sjabloon", "beheerder@example.test");
        template.setUsageType(DefinitionUsageType.REUSABLE_TEMPLATE);
        definitions.saveAndFlush(template);
        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(code("E1-SUP"), "E1 leverancier", SourceOrganisationType.SUPPLIER));

        // fk_import_link_definition_usage + ck_import_link_definition_usage_type: een sjabloon kan
        // nooit leveringen, batches of een publicatie krijgen (beslissingslog 23/09 keuze 7).
        assertThatThrownBy(() -> links.saveAndFlush(
                new ImportLink(code("E1-LNK"), "E1 koppeling", template, supplier, "PSARF050")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refusesTurningADefinitionIntoATemplateWhileAnImportLinkStillPointsAtIt() {
        Scenario scenario = scenario("E2");
        ImportDefinition definition = definitions.findById(scenario.definitionId()).orElseThrow();
        definition.setUsageType(DefinitionUsageType.REUSABLE_TEMPLATE);

        assertThatThrownBy(() -> definitions.saveAndFlush(definition))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- (c) regressie: bestaande rijen zonder bookmarks blijven werken ----------------------------

    @Test
    void leavesAnExistingDefinitionLinkAndBatchWithoutAnyBookmarkRowFullyWorking() {
        Scenario scenario = scenario("F1");

        ImportLink link = links.findById(scenario.linkId()).orElseThrow();
        assertThat(link.isActive()).isTrue();
        assertThat(definitions.findById(scenario.definitionId()).orElseThrow().getUsageType())
                .isEqualTo(DefinitionUsageType.OWN_DEFINITION);
        assertThat(jdbc.queryForObject("select count(*) from import_link_bookmark_value where import_link_id = ?",
                Integer.class, scenario.linkId())).isZero();

        // 006-5: de nieuwe kolom is door de default gevuld, zonder datamigratie en zonder mapping op
        // de entiteit.
        assertThat(jdbc.queryForObject("select definition_usage_type from import_link where id = ?",
                String.class, scenario.linkId())).isEqualTo("OWN_DEFINITION");

        // 006-6: de nieuwe batchkolom is nullable en blijft NULL zolang de servicelaag niets vult.
        ImportBatch batch = batches.saveAndFlush(scenario.newBatch());
        assertThat(jdbc.queryForMap("select bookmark_values_hash from import_batch where id = ?",
                batch.getId())).containsEntry("bookmark_values_hash", null);
        assertThat(batches.findById(batch.getId())).isPresent();
    }

    // --- JPA-mapping van de vier nieuwe entiteiten -------------------------------------------------

    @Test
    void persistsAllFourNewEntitiesThroughJpaAndReadsThemBackUnchanged() {
        Scenario scenario = scenario("G1");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        Long bookmarkId = tx.execute(status -> {
            ImportDefinitionRevision revision = entityManager.find(
                    ImportDefinitionRevision.class, scenario.revisionId());
            ImportLink link = entityManager.find(ImportLink.class, scenario.linkId());

            ImportDefinitionBookmark bookmark = new ImportDefinitionBookmark(revision, "CULTUUR",
                    "Cultuurcode", BookmarkDataType.ENUM, BookmarkValueScope.LINK,
                    "catalogImport.manage", 1);
            bookmark.setAllowedValues("NL,FR,EN");
            bookmark.setCreatedBy("beheerder@example.test");
            entityManager.persist(bookmark);
            entityManager.persist(new ImportDefinitionBookmarkUsage(bookmark,
                    BookmarkUsagePlace.RECORD_FILTER_COMPARE_VALUE, "1"));
            entityManager.persist(new ImportDefinitionBookmarkValue(revision, "PREFIX",
                    BookmarkDataType.TEXT, "", "beheerder@example.test"));

            ImportLinkBookmarkValue linkValue = new ImportLinkBookmarkValue(link, "CULTUUR",
                    BookmarkDataType.ENUM, "NL", "beheerder@example.test");
            linkValue.recordChange("FR", "beheerder2@example.test", Instant.now());
            entityManager.persist(linkValue);
            entityManager.flush();
            return bookmark.getId();
        });

        assertThat(bookmarkId).isNotNull();
        tx.execute(status -> {
            ImportDefinitionBookmark found = entityManager.find(ImportDefinitionBookmark.class, bookmarkId);
            assertThat(found.getValueScope()).isEqualTo(BookmarkValueScope.LINK);
            assertThat(found.getDataType()).isEqualTo(BookmarkDataType.ENUM);
            assertThat(found.isRequired()).isTrue();
            assertThat(found.getCreatedAt()).isNotNull();
            return null;
        });

        assertThat(jdbc.queryForObject("select value_text from import_definition_bookmark_value"
                        + " where definition_revision_id = ? and bookmark_name = ?",
                String.class, scenario.revisionId(), "PREFIX")).isEmpty();
        assertThat(jdbc.queryForMap("select value_text, previous_value_text, updated_by"
                        + " from import_link_bookmark_value where import_link_id = ? and bookmark_name = ?",
                scenario.linkId(), "CULTUUR"))
                .containsEntry("value_text", "FR")
                .containsEntry("previous_value_text", "NL")
                .containsEntry("updated_by", "beheerder2@example.test");
    }

    // --- Helpers ---------------------------------------------------------------------------------

    private static String code(String base) {
        return base + "-" + RUN;
    }

    private long insertBookmark(long revisionId, String name, String dataType, String valueScope,
                                int sortOrder, String allowedValues) {
        jdbc.update("insert into import_definition_bookmark (definition_revision_id, name, label, data_type,"
                        + " value_scope, owner_role, required, allowed_values, sort_order, created_at, created_by)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                revisionId, name, name + " label", dataType, valueScope, "catalogImport.manage", true,
                allowedValues, sortOrder, OffsetDateTime.now(), "beheerder@example.test");
        return jdbc.queryForObject("select id from import_definition_bookmark"
                + " where definition_revision_id = ? and name = ?", Long.class, revisionId, name);
    }

    private void insertUsage(long bookmarkId, String placeKind, String targetHint) {
        jdbc.update("insert into import_definition_bookmark_usage (bookmark_id, place_kind, target_hint,"
                        + " created_at) values (?, ?, ?, ?)",
                bookmarkId, placeKind, targetHint, OffsetDateTime.now());
    }

    private void insertDefinitionValue(long revisionId, String bookmarkName, String dataType, String valueText) {
        jdbc.update("insert into import_definition_bookmark_value (definition_revision_id, bookmark_name,"
                        + " data_type, value_text, filled_at, filled_by) values (?, ?, ?, ?, ?, ?)",
                revisionId, bookmarkName, dataType, valueText, OffsetDateTime.now(), "beheerder@example.test");
    }

    private void insertLinkValue(long linkId, String bookmarkName, String dataType, String valueText) {
        jdbc.update("insert into import_link_bookmark_value (import_link_id, bookmark_name, data_type,"
                        + " value_text, filled_at, filled_by) values (?, ?, ?, ?, ?, ?)",
                linkId, bookmarkName, dataType, valueText, OffsetDateTime.now(), "beheerder@example.test");
    }

    /** Een sjabloondefinitie ({@code REUSABLE_TEMPLATE}) met één revisie; geeft het revisie-id terug. */
    private long templateRevision(String prefix) {
        SourceOrganisation organisation = sourceOrganisations.saveAndFlush(new SourceOrganisation(
                code(prefix + "-ORG"), prefix + " aankoopvereniging", SourceOrganisationType.PURCHASING_ASSOCIATION));
        ImportDefinition template = new ImportDefinition(organisation, code(prefix + "-TPL"),
                prefix + " sjabloon", "beheerder@example.test");
        template.setUsageType(DefinitionUsageType.REUSABLE_TEMPLATE);
        definitions.saveAndFlush(template);
        return revisions.saveAndFlush(newRevision(template)).getId();
    }

    private ImportDefinitionRevision newRevision(ImportDefinition definition) {
        ImportDefinitionRevision revision = new ImportDefinitionRevision(definition, 1,
                IdentityProfileKind.THREE_PART, "beheerder@example.test");
        revision.setAccessConfigHash("1".repeat(64));
        revision.setStructureConfigHash("2".repeat(64));
        revision.setRecordRulesConfigHash("3".repeat(64));
        revision.setCompositeConfigHash("4".repeat(64));
        revision.setIdentitySupplierField("LEVERANCIER");
        revision.setIdentitySupplierGroupField("LEV_GROEP");
        revision.setIdentitySupplierReferenceField("LEV_REFERENTIE");
        revision.setStructureDelimiter(";");
        return revision;
    }

    /** Een gewone (niet-sjabloon) keten definitie -> revisie -> koppeling -> taak -> levering. */
    private Scenario scenario(String prefix) {
        SourceOrganisation organisation = sourceOrganisations.saveAndFlush(new SourceOrganisation(
                code(prefix + "-ORG"), prefix + " bron", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(new ImportDefinition(organisation,
                code(prefix + "-DEF"), prefix + " catalogus", "beheerder@example.test"));
        ImportDefinitionRevision revision = revisions.saveAndFlush(newRevision(definition));
        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(new SourceOrganisation(
                code(prefix + "-SUP"), prefix + " leverancier", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(new ImportLink(code(prefix + "-LNK"),
                prefix + " koppeling", definition, supplier, "PSARF050"));
        CatalogImportTask task = tasks.saveAndFlush(
                new CatalogImportTask(link, prefix + "-taak", TaskTriggerType.MANUAL));
        Delivery delivery = deliveries.saveAndFlush(
                new Delivery(task, "manual:" + code(prefix), Instant.now()));
        return new Scenario(definition.getId(), revision.getId(), link.getId(), link, revision, delivery);
    }

    private record Scenario(Long definitionId, Long revisionId, Long linkId, ImportLink link,
                            ImportDefinitionRevision revision, Delivery delivery) {

        ImportBatch newBatch() {
            return new ImportBatch(delivery, link, revision, 1, "tester@example.test");
        }
    }
}
