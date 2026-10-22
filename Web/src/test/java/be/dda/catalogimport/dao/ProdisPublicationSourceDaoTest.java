package be.dda.catalogimport.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.dao.ProdisPublicationSourceDao.SourcePrice;
import be.dda.catalogimport.dao.ProdisPublicationSourceDao.SourceRow;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.Delivery;
import be.dda.catalogimport.domain.DiscountCodeState;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.ImportMutation;
import be.dda.catalogimport.domain.MutationActionType;
import be.dda.catalogimport.domain.MutationStatus;
import be.dda.catalogimport.domain.MutationTargetDomain;
import be.dda.catalogimport.domain.PublicationBundle;
import be.dda.catalogimport.domain.PublicationBundleSnapshot;
import be.dda.catalogimport.domain.PublicationBundleSnapshotPrice;
import be.dda.catalogimport.domain.PublicationTargetMode;
import be.dda.catalogimport.domain.SnapshotDescriptionState;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.service.ProdisPublicationProjection;
import be.dda.catalogimport.service.ProdisPublicationProjection.FieldState;
import be.dda.catalogimport.service.ProdisPublicationProjection.Item;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * P2b (design {@code publicatie-prodis-db-direct-design.md} par. 2.5/6): {@link ProdisPublicationSourceDao} leest de
 * bundelsnapshot met mutatie, bibliotheek en prijscomponenten letterlijk (geen afronding, {@code null} blijft
 * {@code null}), deterministisch op {@code mutation_id}, en enkel van de gevraagde bundel. Plus één keer de keten
 * lezer → pure projectie op echte data. Codes zijn uniek per test (gedeelde database).
 */
@SpringBootTest
@ActiveProfiles("local")
class ProdisPublicationSourceDaoTest {

    @Autowired
    private ProdisPublicationSourceDao dao;
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

    @Test
    void readsSnapshotRowsWithMutationLibraryAndPricesLiterally() {
        Fixture f = fixture("READ");
        ImportMutation update = f.mutation(MutationActionType.UPDATE, "REF-A", new BigDecimal("100.000000"),
                new BigDecimal("123.456789"), "EUR", "K1", DiscountCodeState.VALUE);
        PublicationBundleSnapshot snapshot = snapshots.saveAndFlush(new PublicationBundleSnapshot(f.bundle, update,
                f.batch.getId(), f.link.getId(), 3L, "Omschrijving A", SnapshotDescriptionState.VALUE));
        snapshotPrices.saveAndFlush(new PublicationBundleSnapshotPrice(snapshot.getId(), "VKP2",
                new BigDecimal("41.150000"), new BigDecimal("33.333333333333"), "EUR", "OK"));
        snapshotPrices.saveAndFlush(new PublicationBundleSnapshotPrice(snapshot.getId(), "AKP",
                new BigDecimal("50.000000"), null, null, "OK"));
        jdbc.update("update publication_bundle set snapshot_spec_version = '1' where id = ?", f.bundle.getId());

        List<SourceRow> rows = dao.findPage(f.bundle.getId(), 0L, 10);

        assertThat(rows).hasSize(1);
        SourceRow row = rows.get(0);
        assertThat(row.bundleId()).isEqualTo(f.bundle.getId());
        assertThat(row.snapshotId()).isEqualTo(snapshot.getId());
        assertThat(row.mutationId()).isEqualTo(update.getId());
        assertThat(row.actionType()).isEqualTo("UPDATE");
        assertThat(row.identitySupplier()).isEqualTo("0601");
        assertThat(row.identitySupplierGroup()).isEqualTo("GRP");
        assertThat(row.identitySupplierReference()).isEqualTo("REF-A");
        assertThat(row.identityDiscountCode()).isEqualTo("K1");
        assertThat(row.identityDiscountState()).isEqualTo("VALUE");
        assertThat(row.libraryCode()).isEqualTo("PSARF050");
        assertThat(row.beforeBasePrice()).isEqualByComparingTo("100");
        assertThat(row.afterBasePrice()).isEqualTo(new BigDecimal("123.456789"));
        assertThat(row.basePriceCurrency()).isEqualTo("EUR");
        assertThat(row.description()).isEqualTo("Omschrijving A");
        assertThat(row.descriptionState()).isEqualTo("VALUE");
        assertThat(row.snapshotSpecVersion()).isEqualTo("1");
        assertThat(row.prices()).extracting(SourcePrice::componentCode).containsExactly("AKP", "VKP2");
        SourcePrice vkp2 = row.prices().get(1);
        assertThat(vkp2.sourceAmount()).isEqualTo(new BigDecimal("41.150000"));
        assertThat(vkp2.percentage()).isEqualTo(new BigDecimal("33.333333333333"));
        assertThat(vkp2.beforePercentage()).isNull();
        assertThat(row.prices().get(0).percentage()).isNull();
        assertThat(row.prices().get(0).currency()).isNull();

        // De keten lezer → pure projectie: spec v1 schrijft geen percentages, de basisprijs wel.
        Item item = ProdisPublicationProjection.project(row);
        assertThat(item.identity().groupLegacyCode()).isEqualTo("0601|GRP|K1");
        assertThat(item.identity().scopeKey()).isEqualTo("PSARF050");
        assertThat(item.basePrice().state()).isEqualTo(FieldState.VALUE);
        assertThat(item.salesPercentages().get(2).state()).isEqualTo(FieldState.KEEP);
        assertThat(item.rejected()).isFalse();
    }

    /**
     * P4b: {@code before_percentage} wordt letterlijk gelezen (schaal 12, {@code null} blijft {@code null}); bij snapshot
     * spec 2 maakt de projectie er de expected van, zodat het percentage geschreven wordt. Dezelfde rij als spec 1 gelezen
     * houdt het percentage op KEEP: de lezer kijkt niet naar de versie, de projectie wel.
     */
    @Test
    void readsBeforePercentageLiterallyAndSpec2UsesItAsExpected() {
        Fixture f = fixture("BEFORE");
        ImportMutation update = f.mutation(MutationActionType.UPDATE, "REF-B", new BigDecimal("100.000000"),
                new BigDecimal("100.000000"), "EUR", null, DiscountCodeState.NOT_USED);
        PublicationBundleSnapshot snapshot = snapshots.saveAndFlush(new PublicationBundleSnapshot(f.bundle, update,
                f.batch.getId(), f.link.getId(), 1L, "Omschrijving B", SnapshotDescriptionState.VALUE));
        snapshotPrices.saveAndFlush(new PublicationBundleSnapshotPrice(snapshot.getId(), "VKP1",
                new BigDecimal("15.000000"), new BigDecimal("15.000000000000"), "EUR", "OK"));
        snapshotPrices.saveAndFlush(new PublicationBundleSnapshotPrice(snapshot.getId(), "VKP2",
                new BigDecimal("25.000000"), new BigDecimal("25.000000000000"), "EUR", "OK"));
        jdbc.update("update publication_bundle_snapshot_price set before_percentage = ? where snapshot_id = ? "
                + "and component_code = 'VKP1'", new BigDecimal("10.000000000000"), snapshot.getId());
        jdbc.update("update publication_bundle set snapshot_spec_version = '2' where id = ?", f.bundle.getId());

        SourceRow row = dao.findPage(f.bundle.getId(), 0L, 10).get(0);

        assertThat(row.snapshotSpecVersion()).isEqualTo("2");
        SourcePrice vkp1 = row.prices().get(0);
        SourcePrice vkp2 = row.prices().get(1);
        assertThat(vkp1.componentCode()).isEqualTo("VKP1");
        assertThat(vkp1.beforePercentage()).isEqualTo(new BigDecimal("10.000000000000"));
        assertThat(vkp2.beforePercentage()).isNull();

        Item item = ProdisPublicationProjection.project(row);
        assertThat(item.salesPercentages().get(1).state()).isEqualTo(FieldState.VALUE);
        assertThat(item.expected().percentages().get(1)).isEqualByComparingTo("10");
        // Geen voor-waarde → KEEP + EXPECTED_NOT_AVAILABLE, nooit 0 als verwachting.
        assertThat(item.salesPercentages().get(2).state()).isEqualTo(FieldState.KEEP);
        assertThat(item.expected().percentages()).doesNotContainKey(2);
        assertThat(item.notices()).anySatisfy(notice -> {
            assertThat(notice.code()).isEqualTo(ProdisPublicationProjection.CODE_EXPECTED_NOT_AVAILABLE);
            assertThat(notice.field()).isEqualTo("VKP2_PCT");
        });

        // Dezelfde rij als spec-1-bundel: de waarde wordt nog gelezen, maar de projectie gebruikt ze niet.
        jdbc.update("update publication_bundle set snapshot_spec_version = '1' where id = ?", f.bundle.getId());
        try {
            SourceRow asV1 = dao.findPage(f.bundle.getId(), 0L, 10).get(0);
            assertThat(asV1.prices().get(0).beforePercentage()).isEqualTo(new BigDecimal("10.000000000000"));
            Item v1Item = ProdisPublicationProjection.project(asV1);
            assertThat(v1Item.salesPercentages().get(1).state()).isEqualTo(FieldState.KEEP);
            assertThat(v1Item.expected().percentages()).isEmpty();
        } finally {
            // Gedeelde database: geen spec-1-bundel met een gevuld before_percentage achterlaten (bestaat in werkelijkheid
            // niet; PublicationRunItemSchemaTest bewaakt "geen backfill" op spec-1-bundels).
            jdbc.update("update publication_bundle set snapshot_spec_version = '2' where id = ?", f.bundle.getId());
        }
    }

    @Test
    void pagesDeterministicallyOnMutationIdAndOnlyWithinTheBundle() {
        Fixture f = fixture("PAGE");
        List<Long> mutationIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ImportMutation mutation = f.mutation(MutationActionType.UPDATE, "REF-" + i, BigDecimal.ONE, BigDecimal.TEN,
                    null, null, DiscountCodeState.NOT_USED);
            snapshots.saveAndFlush(new PublicationBundleSnapshot(f.bundle, mutation, f.batch.getId(), f.link.getId(),
                    (long) i, null, SnapshotDescriptionState.NOT_MAPPED));
            mutationIds.add(mutation.getId());
        }
        Fixture other = fixture("PAGE-OTHER");
        ImportMutation foreign = other.mutation(MutationActionType.CREATE, "REF-X", null, BigDecimal.ONE, "EUR", null,
                DiscountCodeState.NOT_USED);
        snapshots.saveAndFlush(new PublicationBundleSnapshot(other.bundle, foreign, other.batch.getId(),
                other.link.getId(), 1L, "X", SnapshotDescriptionState.VALUE));

        List<SourceRow> first = dao.findPage(f.bundle.getId(), 0L, 2);
        List<SourceRow> second = dao.findPage(f.bundle.getId(), first.get(1).mutationId(), 2);
        List<SourceRow> third = dao.findPage(f.bundle.getId(), second.get(1).mutationId(), 2);
        List<SourceRow> fourth = dao.findPage(f.bundle.getId(), third.get(0).mutationId(), 2);

        List<Long> read = new ArrayList<>();
        for (List<SourceRow> page : List.of(first, second, third)) {
            page.forEach(row -> read.add(row.mutationId()));
        }
        assertThat(read).containsExactlyElementsOf(mutationIds.stream().sorted().toList());
        assertThat(fourth).isEmpty();
        assertThat(dao.count(f.bundle.getId())).isEqualTo(5L);
        assertThat(dao.findPage(f.bundle.getId(), 0L, 10)).isEqualTo(dao.findPage(f.bundle.getId(), 0L, 10));
        // Zonder munt, zonder snapshot spec en zonder prijscomponenten: null blijft null, nooit EUR of 0.
        SourceRow row = first.get(0);
        assertThat(row.basePriceCurrency()).isNull();
        assertThat(row.snapshotSpecVersion()).isNull();
        assertThat(row.prices()).isEmpty();
        assertThat(row.identityDiscountCode()).isNull();
        assertThat(row.identityDiscountState()).isEqualTo("NOT_USED");
        assertThat(row.description()).isNull();
        assertThat(row.descriptionState()).isEqualTo("NOT_MAPPED");
    }

    @Test
    void anUnknownBundleOrABundleWithoutSnapshotReadsNothing() {
        Fixture f = fixture("EMPTY");

        assertThat(dao.findPage(f.bundle.getId(), 0L, 10)).isEmpty();
        assertThat(dao.count(f.bundle.getId())).isZero();
        assertThat(dao.findPage(-1L, 0L, 10)).isEmpty();
    }

    @Test
    void refusesAnInvalidPageSize() {
        // @Repository vertaalt de IllegalArgumentException naar Springs InvalidDataAccessApiUsageException.
        assertThatThrownBy(() -> dao.findPage(1L, 0L, 0)).isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("limit must be between 1 and");
        assertThatThrownBy(() -> dao.findPage(1L, 0L, ProdisPublicationSourceDao.MAX_PAGE_SIZE + 1))
                .isInstanceOf(InvalidDataAccessApiUsageException.class);
    }

    // --- fixture (patroon PublicationSnapshotSchemaTest) ---------------------------------------------------------------

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
        PublicationBundle bundle = bundles.saveAndFlush(new PublicationBundle("BND-" + unique,
                PublicationTargetMode.SIMULATION, "tester@example.test"));
        return new Fixture(this, unique, link, batch, bundle);
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

    private record Fixture(ProdisPublicationSourceDaoTest test, String unique, ImportLink link, ImportBatch batch,
                           PublicationBundle bundle) {

        ImportMutation mutation(MutationActionType action, String reference, BigDecimal before, BigDecimal after,
                                String currency, String discountCode, DiscountCodeState discountState) {
            ImportMutation mutation = new ImportMutation(batch, action, MutationTargetDomain.OFFER,
                    MutationStatus.READY_FOR_PUBLICATION, unique + ":" + reference + ":OFFER");
            mutation.setIdentitySupplier("0601");
            mutation.setIdentitySupplierGroup("GRP");
            mutation.setIdentitySupplierReference(reference);
            mutation.setIdentityDiscountCode(discountCode);
            mutation.setIdentityDiscountState(discountState);
            mutation.setBeforeBasePrice(before);
            mutation.setAfterBasePrice(after);
            mutation.setBasePriceCurrency(currency);
            return test.mutations.saveAndFlush(mutation);
        }
    }
}
