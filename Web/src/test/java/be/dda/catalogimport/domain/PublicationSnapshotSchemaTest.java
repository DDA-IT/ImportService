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
import be.dda.catalogimport.dao.ImportMutationRepository;
import be.dda.catalogimport.dao.PublicationBundleRepository;
import be.dda.catalogimport.dao.PublicationBundleSnapshotPriceRepository;
import be.dda.catalogimport.dao.PublicationBundleSnapshotRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Bouwstap 5P-1 (docs/design/fase5-pub-design.md par. 1 en 6): bewijst dat changeset 008 migreert, dat
 * Hibernate de snapshotentiteiten met {@code ddl-auto: validate} aanvaardt en dat de databaseconstraints
 * afdwingen wat ze beloven. Codes zijn per test uniek omdat de database gedeeld is.
 */
@SpringBootTest
@ActiveProfiles("local")
class PublicationSnapshotSchemaTest {

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
    private ImportMutationRepository mutations;
    @Autowired
    private PublicationBundleRepository bundles;
    @Autowired
    private PublicationBundleSnapshotRepository snapshots;
    @Autowired
    private PublicationBundleSnapshotPriceRepository snapshotPrices;
    @Autowired
    private JdbcTemplate jdbc;

    // --- Schema ----------------------------------------------------------------------------------

    @Test
    void createsTheSnapshotTablesWithTheDocumentedColumnsAndTypes() {
        assertThat(columnNames("publication_bundle_snapshot")).containsExactlyInAnyOrder(
                "ID", "BUNDLE_ID", "MUTATION_ID", "BATCH_ID", "IMPORT_LINK_ID", "SOURCE_ROW_NUMBER",
                "DESCRIPTION", "DESCRIPTION_STATE", "CREATED_AT");
        assertThat(columnNames("publication_bundle_snapshot_price")).containsExactlyInAnyOrder(
                "SNAPSHOT_ID", "COMPONENT_CODE", "SOURCE_AMOUNT", "PERCENTAGE", "CURRENCY", "STATUS");

        assertThat(numeric("publication_bundle_snapshot_price", "source_amount")).containsExactly(24, 6);
        assertThat(numeric("publication_bundle_snapshot_price", "percentage")).containsExactly(24, 12);
        // Exact gelijk aan import_candidate_price.
        assertThat(numeric("import_candidate_price", "source_amount")).containsExactly(24, 6);
        assertThat(numeric("import_candidate_price", "percentage")).containsExactly(24, 12);

        assertThat(jdbc.queryForObject(
                "select character_maximum_length from information_schema.columns where table_schema = current_schema() "
                        + "and upper(table_name) = "
                        + "'PUBLICATION_BUNDLE_SNAPSHOT' and upper(column_name) = 'DESCRIPTION'", Integer.class))
                .isEqualTo(1000);
        assertThat(columnNames("publication_bundle")).contains("SNAPSHOT_HASH", "SNAPSHOT_SPEC_VERSION");
        assertThat(jdbc.queryForObject(
                "select character_maximum_length from information_schema.columns where table_schema = current_schema() "
                        + "and upper(table_name) = "
                        + "'PUBLICATION_BUNDLE' and upper(column_name) = 'SNAPSHOT_SPEC_VERSION'", Integer.class))
                .isEqualTo(10);
    }

    // --- publication_bundle: nieuwe kolommen -----------------------------------------------------

    @Test
    void leavesSnapshotHashAndSpecVersionNullOnBundlesAndOnFrozenBundles() {
        PublicationBundle bundle = bundles.saveAndFlush(
                new PublicationBundle(bundleRef("NULLS"), PublicationTargetMode.SIMULATION, "tester@example.test"));
        assertThat(bundles.findById(bundle.getId()).orElseThrow().getSnapshotHash()).isNull();
        assertThat(bundles.findById(bundle.getId()).orElseThrow().getSnapshotSpecVersion()).isNull();

        // Een reeds bevroren bundel (zonder snapshot) blijft geldig: NO_SNAPSHOT.
        bundle.recordFreeze("freezer@example.test", Instant.now(), "Beoordeeld", new byte[32]);
        bundles.saveAndFlush(bundle);
        PublicationBundle frozen = bundles.findById(bundle.getId()).orElseThrow();
        assertThat(frozen.getStatus()).isEqualTo(PublicationBundleStatus.FROZEN);
        assertThat(frozen.getSnapshotHash()).isNull();
        assertThat(frozen.getSnapshotSpecVersion()).isNull();
    }

    @Test
    void storesSnapshotHashAndSpecVersionWhenWritten() {
        PublicationBundle bundle = bundles.saveAndFlush(
                new PublicationBundle(bundleRef("HASH"), PublicationTargetMode.SIMULATION, "tester@example.test"));
        byte[] hash = new byte[32];
        for (int i = 0; i < hash.length; i++) {
            hash[i] = (byte) (i + 1);
        }
        jdbc.update("update publication_bundle set snapshot_hash = ?, snapshot_spec_version = ? where id = ?",
                hash, "1", bundle.getId());

        PublicationBundle found = bundles.findById(bundle.getId()).orElseThrow();
        assertThat(found.getSnapshotHash()).isEqualTo(hash);
        assertThat(found.getSnapshotSpecVersion()).isEqualTo("1");
    }

    // --- publication_bundle_snapshot -------------------------------------------------------------

    @Test
    void savesASnapshotWithExactPriceRowsAndReadsThemBackWithoutRounding() {
        Fixture f = fixture("RT");
        PublicationBundleSnapshot snapshot = snapshots.saveAndFlush(f.snapshot(f.mutation, "Omschrijving",
                SnapshotDescriptionState.VALUE));
        assertThat(snapshot.getId()).isNotNull();
        assertThat(snapshot.getCreatedAt()).isNotNull();

        BigDecimal amount = new BigDecimal("1234567890123456.123456");
        BigDecimal pct = new BigDecimal("12.345678901234");
        snapshotPrices.saveAndFlush(new PublicationBundleSnapshotPrice(snapshot.getId(), "AKP", amount, null, "EUR", "OK"));
        snapshotPrices.saveAndFlush(new PublicationBundleSnapshotPrice(snapshot.getId(), "VKP1", null, pct, null, "OK"));

        PublicationBundleSnapshot found = snapshots.findById(snapshot.getId()).orElseThrow();
        assertThat(found.getDescription()).isEqualTo("Omschrijving");
        assertThat(found.getDescriptionState()).isEqualTo(SnapshotDescriptionState.VALUE);
        assertThat(found.getBatchId()).isEqualTo(f.batch.getId());
        assertThat(found.getImportLinkId()).isEqualTo(f.link.getId());
        assertThat(found.getSourceRowNumber()).isEqualTo(7L);
        assertThat(snapshots.findByMutationId(f.mutation.getId())).isPresent();
        assertThat(snapshots.countByBundleId(f.bundle.getId())).isEqualTo(1);

        List<PublicationBundleSnapshotPrice> prices = snapshotPrices.findBySnapshotId(snapshot.getId());
        assertThat(prices).hasSize(2);
        PublicationBundleSnapshotPrice akp = snapshotPrices.findById(
                new PublicationBundleSnapshotPrice.Key(snapshot.getId(), "AKP")).orElseThrow();
        assertThat(akp.getSourceAmount()).isEqualByComparingTo(amount);
        assertThat(akp.getSourceAmount().scale()).isEqualTo(6);
        assertThat(akp.getPercentage()).isNull();
        PublicationBundleSnapshotPrice vkp = snapshotPrices.findById(
                new PublicationBundleSnapshotPrice.Key(snapshot.getId(), "VKP1")).orElseThrow();
        assertThat(vkp.getPercentage()).isEqualByComparingTo(pct);
        assertThat(vkp.getPercentage().scale()).isEqualTo(12);
        assertThat(vkp.getSourceAmount()).isNull();
        assertThat(vkp.getCurrency()).isNull();
    }

    @Test
    void refusesAnInvalidDescriptionState() {
        Fixture f = fixture("CKSTATE");
        PublicationBundleSnapshot snapshot = snapshots.saveAndFlush(f.snapshot(f.mutation, null,
                SnapshotDescriptionState.NOT_MAPPED));

        assertThatThrownBy(() -> jdbc.update(
                "update publication_bundle_snapshot set description_state = ? where id = ?", "BOGUS", snapshot.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        for (SnapshotDescriptionState state : SnapshotDescriptionState.values()) {
            assertThatCode(() -> jdbc.update(
                    "update publication_bundle_snapshot set description_state = ? where id = ?",
                    state.name(), snapshot.getId())).doesNotThrowAnyException();
        }
    }

    @Test
    void refusesASecondSnapshotForTheSameMutation() {
        Fixture f = fixture("UKMUT");
        snapshots.saveAndFlush(f.snapshot(f.mutation, "A", SnapshotDescriptionState.VALUE));
        PublicationBundle otherBundle = bundles.saveAndFlush(
                new PublicationBundle(bundleRef("UKMUT-2"), PublicationTargetMode.SIMULATION, "tester@example.test"));

        // uk_publication_bundle_snapshot_mutation: ook in een andere bundel.
        assertThatThrownBy(() -> snapshots.saveAndFlush(new PublicationBundleSnapshot(otherBundle, f.mutation,
                f.batch.getId(), f.link.getId(), 7L, "B", SnapshotDescriptionState.VALUE)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcesTheCompositeUniqueBundleAndMutationConstraintOnDatabaseLevel() {
        Fixture f = fixture("UKBM");
        PublicationBundleSnapshot first = snapshots.saveAndFlush(f.snapshot(f.mutation, "A", SnapshotDescriptionState.VALUE));
        assertThat(constraintExists("uk_publication_bundle_snapshot_bundle_mutation")).isTrue();

        // Dezelfde (bundle, mutation) opnieuw: rechtstreeks via SQL zodat enkel de databaseconstraint spreekt.
        assertThatThrownBy(() -> jdbc.update("insert into publication_bundle_snapshot (bundle_id, mutation_id, "
                + "batch_id, import_link_id, source_row_number, description_state, created_at) "
                + "values (?, ?, ?, ?, ?, ?, ?)", first.getBundle().getId(), f.mutation.getId(), f.batch.getId(),
                f.link.getId(), 7L, "VALUE", java.sql.Timestamp.from(Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refusesADuplicateComponentCodeForTheSameSnapshot() {
        Fixture f = fixture("PKPRICE");
        PublicationBundleSnapshot snapshot = snapshots.saveAndFlush(f.snapshot(f.mutation, "A", SnapshotDescriptionState.VALUE));
        jdbc.update("insert into publication_bundle_snapshot_price (snapshot_id, component_code, status) "
                + "values (?, ?, ?)", snapshot.getId(), "AKP", "OK");

        assertThatThrownBy(() -> jdbc.update("insert into publication_bundle_snapshot_price "
                + "(snapshot_id, component_code, status) values (?, ?, ?)", snapshot.getId(), "AKP", "OK"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcesForeignKeyIntegrity() {
        Fixture f = fixture("FK");

        assertThatThrownBy(() -> jdbc.update("insert into publication_bundle_snapshot_price "
                + "(snapshot_id, component_code, status) values (?, ?, ?)", -1L, "AKP", "OK"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into publication_bundle_snapshot (bundle_id, mutation_id, "
                + "batch_id, import_link_id, source_row_number, description_state, created_at) "
                + "values (?, ?, ?, ?, ?, ?, ?)", -1L, f.mutation.getId(), f.batch.getId(), f.link.getId(), 1L,
                "VALUE", java.sql.Timestamp.from(Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into publication_bundle_snapshot (bundle_id, mutation_id, "
                + "batch_id, import_link_id, source_row_number, description_state, created_at) "
                + "values (?, ?, ?, ?, ?, ?, ?)", f.bundle.getId(), -1L, f.batch.getId(), f.link.getId(), 1L,
                "VALUE", java.sql.Timestamp.from(Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Geen cascade: een snapshot met prijsrijen kan niet zomaar verwijderd worden.
        PublicationBundleSnapshot snapshot = snapshots.saveAndFlush(f.snapshot(f.mutation, "A", SnapshotDescriptionState.VALUE));
        jdbc.update("insert into publication_bundle_snapshot_price (snapshot_id, component_code, status) "
                + "values (?, ?, ?)", snapshot.getId(), "AKP", "OK");
        assertThatThrownBy(() -> jdbc.update("delete from publication_bundle_snapshot where id = ?", snapshot.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- Helpers ---------------------------------------------------------------------------------

    private List<String> columnNames(String table) {
        return jdbc.queryForList("select upper(column_name) from information_schema.columns "
                + "where table_schema = current_schema() and upper(table_name) = upper(?)", String.class, table);
    }

    private Integer[] numeric(String table, String column) {
        Map<String, Object> row = jdbc.queryForMap("select numeric_precision p, numeric_scale s "
                + "from information_schema.columns where table_schema = current_schema() "
                + "and upper(table_name) = upper(?) and upper(column_name) = upper(?)", table, column);
        return new Integer[] {((Number) row.get("P")).intValue(), ((Number) row.get("S")).intValue()};
    }

    private boolean constraintExists(String name) {
        Integer count = jdbc.queryForObject("select count(*) from information_schema.table_constraints "
                + "where constraint_schema = current_schema() and upper(constraint_name) = upper(?)", Integer.class, name);
        return count != null && count > 0;
    }

    private String bundleRef(String prefix) {
        return "BND-" + prefix + "-" + Long.toString(System.nanoTime(), 36);
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

    /** Bundel + batch + mutatie in een volledige keten. */
    private Fixture fixture(String prefix) {
        String unique = prefix + "-" + Long.toString(System.nanoTime(), 36);
        SourceOrganisation organisation = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + "-ORG BV", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(
                new ImportDefinition(organisation, unique + "-DEF", unique + " catalogus", "beheerder@example.test"));
        ImportDefinitionRevision revision = revisions.saveAndFlush(newRevision(definition));
        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + "-LINK koppeling", definition, supplier, "PSARF050"));
        CatalogImportTask task = tasks.saveAndFlush(new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL));
        Delivery delivery = deliveries.saveAndFlush(new Delivery(task, "manual:" + unique, Instant.now()));
        ImportBatch batch = batches.saveAndFlush(new ImportBatch(delivery, link, revision, 1, "tester@example.test"));
        PublicationBundle bundle = bundles.saveAndFlush(
                new PublicationBundle(bundleRef(prefix), PublicationTargetMode.SIMULATION, "tester@example.test"));
        String key = unique + ":1:H1:OFFER";
        ImportMutation mutation = new ImportMutation(batch, MutationActionType.CREATE,
                MutationTargetDomain.OFFER, MutationStatus.PLANNED, key);
        mutation.setIdentitySupplier("LEV");
        mutation.setIdentitySupplierGroup("GRP");
        mutation.setIdentitySupplierReference("REF-" + key);
        mutation = mutations.saveAndFlush(mutation);
        return new Fixture(link, batch, bundle, mutation);
    }

    private record Fixture(ImportLink link, ImportBatch batch, PublicationBundle bundle, ImportMutation mutation) {

        PublicationBundleSnapshot snapshot(ImportMutation m, String description, SnapshotDescriptionState state) {
            return new PublicationBundleSnapshot(bundle, m, batch.getId(), link.getId(), 7L, description, state);
        }
    }
}
