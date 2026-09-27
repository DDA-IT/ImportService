package be.dda.catalogimport.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.DeliveryFileRepository;
import be.dda.catalogimport.dao.DeliveryRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.ImportMutationRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Bouwstap V-1 (docs/design/valuta-standaard-design.md par. 2, 3 en 7, changeset 010): bewijst dat de drie
 * herkomstkolommen en {@code import_link.default_currency} migreren, nullable zijn, hun check afdwingen en dat
 * Hibernate de entiteiten met {@code ddl-auto: validate} aanvaardt. Codes zijn per test uniek omdat de
 * database gedeeld is.
 */
@SpringBootTest
@ActiveProfiles("local")
class CurrencyDefaultSchemaTest {

    private static final String[] ORIGIN_TABLES = {"import_candidate_stage", "catalog_source_state", "import_mutation"};

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
    private DeliveryFileRepository deliveryFiles;
    @Autowired
    private ImportBatchRepository batches;
    @Autowired
    private ImportMutationRepository mutations;
    @Autowired
    private JdbcTemplate jdbc;

    // --- Schema ----------------------------------------------------------------------------------

    @Test
    void createsTheOriginColumnsAndTheDefaultCurrencyColumnAsNullableWithTheRightLength() {
        for (String table : ORIGIN_TABLES) {
            assertThat(length(table, "base_price_currency_origin")).as(table).isEqualTo(20);
            assertThat(nullable(table, "base_price_currency_origin")).as(table).isEqualTo("YES");
            assertThat(jdbc.queryForObject(columnQuery("column_default"), String.class, table,
                    "base_price_currency_origin")).as(table).isNull();
        }
        assertThat(length("import_link", "default_currency")).isEqualTo(3);
        assertThat(nullable("import_link", "default_currency")).isEqualTo("YES");
        assertThat(jdbc.queryForObject(columnQuery("column_default"), String.class, "import_link",
                "default_currency")).isNull();
    }

    @Test
    void currencyOriginEnumHasTheThreeDocumentedValues() {
        assertThat(CurrencyOrigin.values()).extracting(Enum::name)
                .containsExactlyInAnyOrder("SOURCE", "LINK_DEFAULT", "SYSTEM_DEFAULT");
    }

    // --- Check op de herkomst --------------------------------------------------------------------

    @Test
    void stageOriginCheckRefusesAnInvalidValueAndAcceptsTheDocumentedOnesAndNull() {
        Scenario s = scenario("CDSTG");
        ImportBatch batch = batches.saveAndFlush(s.newBatch(1));
        insertStage(batch.getId(), s.file().getId(), 1, sha256("cdstg"));
        String update = "update import_candidate_stage set base_price_currency_origin = ? where batch_id = ?";

        assertOriginCheck(update, batch.getId());
    }

    @Test
    void sourceStateOriginCheckRefusesAnInvalidValueAndAcceptsTheDocumentedOnesAndNull() {
        Scenario s = scenario("CDSTATE");
        ImportBatch batch = batches.saveAndFlush(s.newBatch(1));
        insertSourceState(s.link.getId(), s.delivery.getId(), batch.getId(), sha256("cdstate"));
        String update = "update catalog_source_state set base_price_currency_origin = ? where import_link_id = ?";

        assertOriginCheck(update, s.link.getId());
    }

    @Test
    void mutationOriginCheckRefusesAnInvalidValueAndAcceptsTheDocumentedOnesAndNull() {
        Scenario s = scenario("CDMUT");
        ImportBatch batch = batches.saveAndFlush(s.newBatch(1));
        ImportMutation mutation = mutations.saveAndFlush(createMutation(batch, "CDMUT-1"));
        String update = "update import_mutation set base_price_currency_origin = ? where id = ?";

        assertOriginCheck(update, mutation.getId());
    }

    @Test
    void markerMutationAcceptsAnOriginColumnValueWithoutBreakingTheMarkerCheck() {
        Scenario s = scenario("CDMARK");
        ImportBatch batch = batches.saveAndFlush(s.newBatch(1));
        ImportMutation marker = new ImportMutation(batch, MutationActionType.IMPORT_MARKER,
                MutationTargetDomain.IMPORT, MutationStatus.RECORDED, "CDMARK-marker");
        marker.setResultSummary("outcome=SCREENED;completenessProven=false");

        assertThatCode(() -> mutations.saveAndFlush(marker)).doesNotThrowAnyException();
        assertThat(mutations.findById(marker.getId()).orElseThrow().getBasePriceCurrencyOrigin()).isNull();
    }

    // --- default_currency ------------------------------------------------------------------------

    @Test
    void defaultCurrencyCheckRefusesMalformedValuesAndAcceptsIsoShapedOnesAndNull() {
        Scenario s = scenario("CDLINK");
        String update = "update import_link set default_currency = ? where id = ?";

        for (String invalid : new String[] {"eur", "EU", "EURO", "123"}) {
            assertThatThrownBy(() -> jdbc.update(update, invalid, s.link.getId())).as(invalid)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        for (String valid : new String[] {"EUR", "USD"}) {
            assertThatCode(() -> jdbc.update(update, valid, s.link.getId())).as(valid).doesNotThrowAnyException();
            assertThat(jdbc.queryForObject("select default_currency from import_link where id = ?", String.class,
                    s.link.getId())).isEqualTo(valid);
        }
        assertThatCode(() -> jdbc.update(update, null, s.link.getId())).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("select default_currency from import_link where id = ?", String.class,
                s.link.getId())).isNull();
    }

    // --- Entiteiten ------------------------------------------------------------------------------

    @Test
    void savesAnImportLinkWithADefaultCurrencyAndReadsItBack() {
        Scenario s = scenario("CDLRT");
        assertThat(links.findById(s.link.getId()).orElseThrow().getDefaultCurrency()).isNull();

        s.link.setDefaultCurrency("USD");
        links.saveAndFlush(s.link);

        assertThat(links.findById(s.link.getId()).orElseThrow().getDefaultCurrency()).isEqualTo("USD");
    }

    @Test
    void savesAnImportMutationWithACurrencyOriginAndReadsItBack() {
        for (CurrencyOrigin origin : CurrencyOrigin.values()) {
            Scenario s = scenario("CDMRT-" + origin.ordinal());
            ImportBatch batch = batches.saveAndFlush(s.newBatch(1));
            ImportMutation mutation = createMutation(batch, "CDMRT-" + origin);
            mutation.setBasePriceCurrency("EUR");
            mutation.setBasePriceCurrencyOrigin(origin);
            ImportMutation saved = mutations.saveAndFlush(mutation);

            ImportMutation found = mutations.findById(saved.getId()).orElseThrow();
            assertThat(found.getBasePriceCurrencyOrigin()).isEqualTo(origin);
            assertThat(jdbc.queryForObject("select base_price_currency_origin from import_mutation where id = ?",
                    String.class, saved.getId())).isEqualTo(origin.name());
        }
    }

    // --- Helpers ---------------------------------------------------------------------------------

    private void assertOriginCheck(String update, Long key) {
        assertThatThrownBy(() -> jdbc.update(update, "BOGUS", key))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(update, "source", key))
                .isInstanceOf(DataIntegrityViolationException.class);
        for (CurrencyOrigin origin : CurrencyOrigin.values()) {
            assertThatCode(() -> jdbc.update(update, origin.name(), key)).doesNotThrowAnyException();
        }
        assertThatCode(() -> jdbc.update(update, null, key)).doesNotThrowAnyException();
    }

    private ImportMutation createMutation(ImportBatch batch, String idempotencyKey) {
        ImportMutation mutation = new ImportMutation(batch, MutationActionType.CREATE,
                MutationTargetDomain.OFFER, MutationStatus.PLANNED, idempotencyKey);
        mutation.setIdentitySupplier("LEV");
        mutation.setIdentitySupplierGroup("GRP");
        mutation.setIdentitySupplierReference("REF-" + idempotencyKey);
        return mutation;
    }

    private void insertStage(Long batchId, Long fileId, long rowNumber, byte[] identityHash) {
        jdbc.update("insert into import_candidate_stage (batch_id, row_number, delivery_file_id, identity_supplier, "
                        + "identity_supplier_group, identity_supplier_reference, identity_discount_state, "
                        + "identity_hash, base_price, article_fingerprint, price_fingerprint, "
                        + "combined_fingerprint, mutation_key_prefix, created_at) "
                        + "values (?, ?, ?, 'LEV', 'GRP', 'REF', 'NOT_USED', ?, ?, ?, ?, ?, 'prefix', ?)",
                batchId, rowNumber, fileId, identityHash, new BigDecimal("1.500000"),
                sha256("article"), sha256("price"), sha256("combined"), OffsetDateTime.now());
    }

    private void insertSourceState(Long linkId, Long deliveryId, Long batchId, byte[] identityHash) {
        jdbc.update("insert into catalog_source_state (import_link_id, identity_hash, identity_supplier, "
                        + "identity_supplier_group, identity_supplier_reference, identity_discount_state, "
                        + "identity_profile_kind, article_fingerprint, price_fingerprint, combined_fingerprint, "
                        + "base_price, state_origin, last_change_delivery_id, last_change_batch_id, "
                        + "created_at, updated_at) "
                        + "values (?, ?, 'LEV', 'GRP', 'REF', 'NOT_USED', 'THREE_PART', ?, ?, ?, ?, "
                        + "'BASELINE_ACCEPTED', ?, ?, ?, ?)",
                linkId, identityHash, sha256("article"), sha256("price"), sha256("combined"),
                new BigDecimal("1.500000"), deliveryId, batchId, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String columnQuery(String selected) {
        return "select " + selected + " from information_schema.columns where table_schema = current_schema() "
                + "and lower(table_name) = ? and lower(column_name) = ?";
    }

    private Integer length(String table, String column) {
        Map<String, Object> row = jdbc.queryForMap(columnQuery("character_maximum_length as len"), table, column);
        return ((Number) row.get("len")).intValue();
    }

    private String nullable(String table, String column) {
        return jdbc.queryForObject(columnQuery("is_nullable"), String.class, table, column);
    }

    private Scenario scenario(String prefix) {
        SourceOrganisation organisation = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(prefix + "-ORG", prefix + "-ORG BV", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(
                new ImportDefinition(organisation, prefix + "-DEF", prefix + " catalogus", "beheerder@example.test"));
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
        revision = revisions.saveAndFlush(revision);
        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(prefix + "-SUP", prefix + "-SUP BV", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(
                new ImportLink(prefix + "-LINK", prefix + "-LINK koppeling", definition, supplier, "PSARF050"));
        CatalogImportTask task = tasks.saveAndFlush(new CatalogImportTask(link, prefix + "-taak", TaskTriggerType.MANUAL));
        Delivery delivery = deliveries.saveAndFlush(new Delivery(task, "manual:" + prefix, Instant.now()));
        return new Scenario(revision, link, delivery);
    }

    private final class Scenario {
        private final ImportDefinitionRevision revision;
        private final ImportLink link;
        private final Delivery delivery;
        private DeliveryFile file;

        private Scenario(ImportDefinitionRevision revision, ImportLink link, Delivery delivery) {
            this.revision = revision;
            this.link = link;
            this.delivery = delivery;
        }

        ImportBatch newBatch(int attemptNo) {
            return new ImportBatch(delivery, link, revision, attemptNo, "tester@example.test");
        }

        DeliveryFile file() {
            if (file == null) {
                file = deliveryFiles.saveAndFlush(new DeliveryFile(delivery, 1, "levering.csv",
                        "2026/09/18/" + delivery.getId() + "/levering.csv", "a".repeat(64), 100L));
            }
            return file;
        }
    }
}
