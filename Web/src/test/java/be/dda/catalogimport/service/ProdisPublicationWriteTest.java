package be.dda.catalogimport.service;

import static be.dda.catalogimport.testsupport.ProdisPublicationTestDatabase.DESCRIPTION_ID;
import static be.dda.catalogimport.testsupport.ProdisPublicationTestDatabase.DETAIL_ID_VKP1;
import static be.dda.catalogimport.testsupport.ProdisPublicationTestDatabase.PRICE_ID;
import static be.dda.catalogimport.testsupport.ProdisPublicationTestDatabase.ownerValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.DeliveryRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.ImportMutationRepository;
import be.dda.catalogimport.dao.ProdisPublicationJdbc;
import be.dda.catalogimport.dao.ProdisPublicationSourceDao.SourcePrice;
import be.dda.catalogimport.dao.ProdisPublicationSourceDao.SourceRow;
import be.dda.catalogimport.dao.ProdisPublicationWriter;
import be.dda.catalogimport.dao.ProdisPublicationWriter.Decision;
import be.dda.catalogimport.dao.ProdisPublicationWriter.DetailColumn;
import be.dda.catalogimport.dao.ProdisPublicationWriter.WriteSet;
import be.dda.catalogimport.dao.ProdisReferenceDao;
import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticleIdentity;
import be.dda.catalogimport.dao.ProdisReferenceJdbc;
import be.dda.catalogimport.dao.ProdisUpdateCountException;
import be.dda.catalogimport.dao.PublicationBundleRepository;
import be.dda.catalogimport.dao.PublicationRunItemRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.Delivery;
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
import be.dda.catalogimport.domain.PublicationRunItemStatus;
import be.dda.catalogimport.domain.PublicationTargetMode;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.service.ProdisPublicationProjection.Item;
import be.dda.catalogimport.service.ProdisPublicationService.ItemResult;
import be.dda.catalogimport.service.ProdisReferenceService.LegacyMasterMode;
import be.dda.catalogimport.testsupport.ProdisPublicationTestDatabase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.PGConnection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * P5b ({@code docs/design/publicatie-prodis-db-direct-design.md} par. 2.4-2.6, 4, 6 rij P5b): {@link ProdisPublicationWriter}
 * en {@link ProdisPublicationService} end-to-end tegen de lokale CI-database ({@code catalog_import}, profiel {@code local})
 * en de Prodis-<b>test</b>database {@code prodis_pubtest} als de testrol met de productierechten (guard in
 * {@link ProdisPublicationTestDatabase}: elke verbinding controleert {@code current_database()}; de echte {@code Prodis}
 * wordt nooit beschreven).
 *
 * <h2>Schrijflog (enkel in de testdatabase)</h2>
 * Een {@code AFTER UPDATE}-trigger op de drie schrijfbare tabellen logt per bijgewerkte rij de transactie-id, de tabel en de
 * kolommen waarvan de waarde echt veranderde. Zo is "twee keer hetzelfde item = één wijziging" en "enkel de gewijzigde
 * kolommen" exact aantoonbaar. De trigger verdwijnt bij elke {@link ProdisPublicationTestDatabase#prepare()} (de tabellen
 * worden opnieuw gemaakt) en in {@link #close()}.
 */
@SpringBootTest
@ActiveProfiles("local")
class ProdisPublicationWriteTest {

    private static final String USER = "publisher@example.test";
    private static final String SUBJECT = "kc-subject-p5b";
    private static final ActorIdentity ACTOR = new ActorIdentity(USER, SUBJECT);
    private static final String GOLDEN = "Service/src/test/resources/legacy-price-golden.csv";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static ProdisPublicationTestDatabase database;
    private static ProdisPublicationJdbc jdbc;

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
    private PublicationRunItemRepository items;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate ci;

    private ProdisPublicationService service;

    @BeforeAll
    static void prepareProdis() throws SQLException {
        database = ProdisPublicationTestDatabase.prepare();
        jdbc = ProdisPublicationTestDatabase.publicationJdbc(Duration.ofSeconds(30), 4);
        try (Connection owner = ProdisPublicationTestDatabase.owner(); Statement statement = owner.createStatement()) {
            statement.execute("drop table if exists pubtest_write_log");
            statement.execute("create table pubtest_write_log (id bigserial primary key, tx bigint not null, "
                    + "tbl text not null, row_id bigint not null, changed text not null, modified_by text)");
            statement.execute("create or replace function pubtest_log_update() returns trigger language plpgsql "
                    + "security definer set search_path = public as $$ begin "
                    + "insert into pubtest_write_log (tx, tbl, row_id, changed, modified_by) select txid_current(), "
                    + "tg_table_name, new.id, coalesce((select string_agg(n.key, ',' order by n.key) from "
                    + "jsonb_each(to_jsonb(new)) n where (to_jsonb(old) -> n.key) is distinct from n.value), ''), "
                    + "new.last_modified_by; return new; end $$");
            for (String table : List.of("catalog_article_price", "catalog_article_price_detail",
                    "catalog_article_description")) {
                statement.execute("create trigger pubtest_log after update on " + table
                        + " for each row execute function pubtest_log_update()");
            }
        }
    }

    @AfterAll
    static void close() throws Exception {
        try (Connection owner = ProdisPublicationTestDatabase.owner(); Statement statement = owner.createStatement()) {
            for (String table : List.of("catalog_article_price", "catalog_article_price_detail",
                    "catalog_article_description")) {
                statement.execute("drop trigger if exists pubtest_log on " + table);
            }
            statement.execute("drop function if exists pubtest_log_update()");
            statement.execute("drop table if exists pubtest_write_log");
        }
        if (jdbc != null) {
            jdbc.close();
        }
    }

    @BeforeEach
    void seed() {
        database.seedArticle();
        owner("truncate pubtest_write_log");
        service = service(jdbc, Clock.systemUTC());
    }

    // --- normaal scenario ----------------------------------------------------------------------------------------

    @Test
    void aChangedBasePriceIsAppliedWithTheRecalculatedPricesAuditAndOnlyTheChangedColumns() throws Exception {
        TestItem item = fixture("NORMAL").item(r -> {
        });

        ItemResult result = service.processItem(item.id, ACTOR, LegacyMasterMode.NONE);

        assertThat(result.status()).isEqualTo(PublicationRunItemStatus.APPLIED);
        assertThat(result.prodisContacted()).isTrue();
        assertThat(result.prodisWritten()).isTrue();
        assertThat(result.superseded()).isFalse();
        assertThat(result.sendCount()).isEqualTo(1);
        assertThat(result.mutationStatus()).isEqualTo(MutationStatus.PUBLISHED);

        // Prodis: basisprijs exact, VKP1..5 = 120 x 110..150 %, bruto-politiek 1 → AVP1 = VKP1, AVP% blijft 0.
        assertThat(ownerValue("select base_price from catalog_article_price where id = " + PRICE_ID))
                .isEqualTo(new BigDecimal("120.000000"));
        assertThat(prices()).containsExactly(132.0, 144.0, 156.0, 168.0, 180.0);
        assertThat(percentages()).containsExactly(110.0, 120.0, 130.0, 140.0, 150.0);
        assertThat(ownerValue("select brut_price from catalog_article_price where id = " + PRICE_ID)).isEqualTo(132.0);
        assertThat(ownerValue("select brut_price_percentage from catalog_article_price where id = " + PRICE_ID))
                .isEqualTo(0.0);
        assertThat(ownerValue("select description from catalog_article_description where id = " + DESCRIPTION_ID))
                .isEqualTo("Nieuwe omschrijving");
        // Nooit aangeraakt: purchase_price (Q1 = b), created_*, tenant, record_active, deleted_*.
        assertThat(ownerValue("select purchase_price from catalog_article_price where id = " + PRICE_ID)).isEqualTo(55.5);
        assertThat(ownerValue("select created_by || '|' || coalesce(tenant_id, '-') || '|' || record_active || '|' "
                + "|| coalesce(deleted_by, '-') from catalog_article_price where id = " + PRICE_ID))
                .isEqualTo("system|-|true|-");

        // Audit: subject, UTC, één tijdstip voor alle rijen = sales_price_last_updated.
        assertThat(ownerValue("select count(distinct last_modified_by) || ':' || min(last_modified_by) from ("
                + "select last_modified_by from catalog_article_price union all select last_modified_by from "
                + "catalog_article_price_detail union all select last_modified_by from catalog_article_description) a"))
                .isEqualTo("1:" + SUBJECT);
        LocalDateTime written = ((Timestamp) ownerValue("select last_modified_date from catalog_article_price where id = "
                + PRICE_ID)).toLocalDateTime();
        assertThat(ownerValue("select count(distinct last_modified_date) from (select last_modified_date from "
                + "catalog_article_price union all select last_modified_date from catalog_article_price_detail union all "
                + "select last_modified_date from catalog_article_description) a")).isEqualTo(1L);
        assertThat(((Timestamp) ownerValue("select sales_price_last_updated from catalog_article_price where id = "
                + PRICE_ID)).toLocalDateTime()).isEqualTo(written);
        assertThat(Duration.between(written, LocalDateTime.now(ZoneOffset.UTC)).abs()).isLessThan(Duration.ofMinutes(1));

        // Schrijflog: één transactie, exact de gewijzigde kolommen.
        assertThat(writingTransactions()).isEqualTo(1);
        assertThat(changedColumns()).containsExactlyInAnyOrder(
                "catalog_article_description|description,last_modified_by,last_modified_date",
                "catalog_article_price|base_price,brut_price,last_modified_by,last_modified_date,sales_price_last_updated",
                "catalog_article_price_detail|last_modified_by,last_modified_date,price",
                "catalog_article_price_detail|last_modified_by,last_modified_date,price",
                "catalog_article_price_detail|last_modified_by,last_modified_date,price",
                "catalog_article_price_detail|last_modified_by,last_modified_date,price",
                "catalog_article_price_detail|last_modified_by,last_modified_date,price");

        // CatalogImport: item met verzender, modus, prior/result/warnings.
        Map<String, Object> row = itemRow(item.id);
        assertThat(row.get("status")).isEqualTo("APPLIED");
        assertThat(row.get("outcome")).isEqualTo("APPLIED");
        assertThat(row.get("reason_code")).isNull();
        assertThat(row.get("sent_by")).isEqualTo(USER);
        assertThat(row.get("sent_by_subject")).isEqualTo(SUBJECT);
        assertThat(row.get("legacy_master")).isEqualTo("NONE");
        assertThat(row.get("send_count")).isEqualTo(1);
        assertThat(row.get("sent_at")).isNotNull();
        assertThat(row.get("answered_at")).isNotNull();
        assertThat(row.get("last_error_code")).isNull();
        JsonNode prior = JSON.readTree((String) row.get("prior_json"));
        assertThat(prior.path("readUnderLock").asBoolean()).isTrue();
        assertThat(prior.path("prior").path("basePrice").asText()).isEqualTo("100");
        assertThat(prior.path("prior").path("salesPriceLastUpdated").asText()).isEqualTo("2026-02-03T04:05:06");
        JsonNode resultJson = JSON.readTree((String) row.get("result_json"));
        assertThat(resultJson.path("written").asBoolean()).isTrue();
        assertThat(resultJson.path("salesPriceLastUpdated").asBoolean()).isTrue();
        assertThat(LocalDateTime.parse(resultJson.path("writtenAtUtc").asText())).isEqualTo(written);
        assertThat(resultJson.path("sentFromStatus").asText()).isEqualTo("PENDING");
        assertThat(resultJson.path("changes")).hasSize(8);
        assertThat(resultJson.path("target").path("basePrice").asText()).isEqualTo("120");
        assertThat(JSON.readTree((String) row.get("warnings_json")).findValuesAsText("code"))
                .containsExactly("DESCRIPTION_OVERWRITTEN");
    }

    @Test
    void onlyTheReallyChangedColumnsAreWrittenAndSalesPriceLastUpdatedMovesWithAVkpPrice() {
        owner("update catalog_article_price set brut_price = 110 where id = " + PRICE_ID);
        owner("truncate pubtest_write_log");
        TestItem item = fixture("ONLY").item(r -> {
            r.after = null;
            r.description = null;
            r.descriptionState = "EMPTY";
            r.prices.add(new SourcePrice("VKP3", new BigDecimal("35"), new BigDecimal("35"), "EUR", "OK",
                    new BigDecimal("130")));
        });

        ItemResult result = service.processItem(item.id, ACTOR, LegacyMasterMode.NONE);

        assertThat(result.status()).isEqualTo(PublicationRunItemStatus.APPLIED);
        assertThat(percentages()).containsExactly(110.0, 120.0, 35.0, 140.0, 150.0);
        assertThat(prices()).containsExactly(110.0, 120.0, 35.0, 140.0, 150.0);
        assertThat(changedColumns()).containsExactlyInAnyOrder(
                "catalog_article_price|last_modified_by,last_modified_date,sales_price_last_updated",
                "catalog_article_price_detail|last_modified_by,last_modified_date,percentage,price");
        assertThat(ownerValue("select count(*) from catalog_article_price_detail where last_modified_by is null"))
                .isEqualTo(4L);
        assertThat(ownerValue("select last_modified_by is null from catalog_article_description")).isEqualTo(true);
        assertThat(ownerValue("select base_price from catalog_article_price")).isEqualTo(new BigDecimal("100.000000"));
    }

    @Test
    void anItemThatChangesOnlyTheDescriptionLeavesPricesAndSalesPriceLastUpdatedAndShowsTheDrift() throws Exception {
        TestItem item = fixture("DESC").item(r -> r.after = new BigDecimal("100.000000"));

        ItemResult result = service.processItem(item.id, ACTOR, LegacyMasterMode.PRODIS);

        assertThat(result.status()).isEqualTo(PublicationRunItemStatus.APPLIED);
        assertThat(changedColumns()).containsExactlyInAnyOrder(
                "catalog_article_description|description,last_modified_by,last_modified_date");
        assertThat(ownerValue("select sales_price_last_updated from catalog_article_price"))
                .isEqualTo(Timestamp.valueOf("2026-02-03 04:05:06"));
        assertThat(ownerValue("select brut_price from catalog_article_price")).isEqualTo(120.0);
        Map<String, Object> row = itemRow(item.id);
        assertThat(row.get("legacy_master")).isEqualTo("PRODIS");
        assertThat(JSON.readTree((String) row.get("warnings_json")).findValuesAsText("code"))
                .contains("DERIVED_PRICE_DRIFT", "DESCRIPTION_OVERWRITTEN");
        assertThat(JSON.readTree((String) row.get("result_json")).path("salesPriceLastUpdated").asBoolean()).isFalse();
    }

    @Test
    void priceCode1WritesBaseAndBrutButNeverTheSalesPricesNorSalesPriceLastUpdated() {
        owner("update catalog_article_price set price_code = '1', brut_price_politics = '0', "
                + "brut_price_percentage = 125, brut_price = 99 where id = " + PRICE_ID);
        owner("truncate pubtest_write_log");
        TestItem item = fixture("PC1").item(r -> {
            r.description = null;
            r.descriptionState = "NOT_MAPPED";
        });

        ItemResult result = service.processItem(item.id, ACTOR, LegacyMasterMode.NONE);

        assertThat(result.status()).isEqualTo(PublicationRunItemStatus.APPLIED);
        assertThat(changedColumns()).containsExactlyInAnyOrder(
                "catalog_article_price|base_price,brut_price,last_modified_by,last_modified_date");
        assertThat(ownerValue("select brut_price from catalog_article_price")).isEqualTo(150.0);
        assertThat(prices()).containsExactly(110.0, 120.0, 130.0, 140.0, 150.0);
        assertThat(ownerValue("select sales_price_last_updated from catalog_article_price"))
                .isEqualTo(Timestamp.valueOf("2026-02-03 04:05:06"));
    }

    @Test
    void withoutSubjectTheUsernameIsTheAuditName() {
        TestItem item = fixture("NOSUB").item(r -> {
        });

        service.processItem(item.id, ActorIdentity.unverified(USER), LegacyMasterMode.NONE);

        assertThat(ownerValue("select last_modified_by from catalog_article_price")).isEqualTo(USER);
        assertThat(itemRow(item.id).get("sent_by_subject")).isNull();
    }

    @Test
    void theTwoArgumentVariantRecordsAnUnknownModeWhenTheReadSourceIsNotConfigured() {
        TestItem item = fixture("MODE").item(r -> {
        });

        assertThat(service.processItem(item.id, ACTOR).status()).isEqualTo(PublicationRunItemStatus.APPLIED);
        assertThat(itemRow(item.id).get("legacy_master")).isEqualTo("UNKNOWN");
    }

    // --- idempotentie: twee keer hetzelfde item = één wijziging ----------------------------------------------------

    @Test
    void processingTheSameItemTwiceChangesProdisOnce() {
        TestItem item = fixture("TWICE").item(r -> {
        });

        ItemResult first = service.processItem(item.id, ACTOR, LegacyMasterMode.NONE);
        Object modified = ownerValue("select last_modified_date from catalog_article_price");
        ItemResult second = service.processItem(item.id, ACTOR, LegacyMasterMode.NONE);

        assertThat(first.status()).isEqualTo(PublicationRunItemStatus.APPLIED);
        assertThat(second.status()).isEqualTo(PublicationRunItemStatus.APPLIED);
        assertThat(second.prodisContacted()).isFalse();
        assertThat(second.sendCount()).isEqualTo(1);
        assertThat(writingTransactions()).isEqualTo(1);
        assertThat(ownerValue("select last_modified_date from catalog_article_price")).isEqualTo(modified);
    }

    @Test
    void twoConcurrentAttemptsOnTheSameItemWriteProdisAtMostOnce() throws Exception {
        TestItem item = fixture("CONC").item(r -> {
        });
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ItemResult>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return service.processItem(item.id, ACTOR, LegacyMasterMode.NONE);
                }));
            }
            go.countDown();
            for (Future<ItemResult> future : futures) {
                assertThat(future.get(60, TimeUnit.SECONDS).status()).isIn(PublicationRunItemStatus.APPLIED,
                        PublicationRunItemStatus.UNCHANGED, PublicationRunItemStatus.SENT);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(writingTransactions()).isEqualTo(1);
        assertThat(itemRow(item.id).get("status")).isIn("APPLIED", "UNCHANGED");
        assertThat(mutationStatus(item.mutationId)).isEqualTo("PUBLISHED");
        assertThat(prices()).containsExactly(132.0, 144.0, 156.0, 168.0, 180.0);
    }

    @Test
    void anotherItemWithTheSameContentFindsTheTargetAndIsUnchanged() {
        Fixture fixture = fixture("SAME");
        TestItem first = fixture.item(r -> {
        });
        service.processItem(first.id, ACTOR, LegacyMasterMode.NONE);
        TestItem second = fixture.item(r -> {
        });

        ItemResult result = service.processItem(second.id, ACTOR, LegacyMasterMode.NONE);

        assertThat(result.status()).isEqualTo(PublicationRunItemStatus.UNCHANGED);
        assertThat(result.prodisWritten()).isFalse();
        assertThat(writingTransactions()).isEqualTo(1);
        assertThat(mutationStatus(second.mutationId)).isEqualTo("PUBLISHED");
    }

    // --- commit-fout, crash, retry ---------------------------------------------------------------------------------

    @Test
    void aLostCommitAcknowledgementIsResultUnknownAndTheRetryIsUnchanged() throws Exception {
        TestItem item = fixture("LOSTACK").item(r -> {
        });
        try (ProdisPublicationJdbc hooked = hookedJdbc(CommitHook.COMMIT_THEN_FAIL)) {
            ItemResult unknown = service(hooked, Clock.systemUTC()).processItem(item.id, ACTOR, LegacyMasterMode.NONE);

            assertThat(unknown.status()).isEqualTo(PublicationRunItemStatus.RESULT_UNKNOWN);
            assertThat(unknown.lastErrorCode()).isEqualTo("PRODIS_COMMIT_UNKNOWN:08006");
        }
        // De commit ging echt door: Prodis staat op de target.
        assertThat(writingTransactions()).isEqualTo(1);
        assertThat(prices()).containsExactly(132.0, 144.0, 156.0, 168.0, 180.0);
        assertThat(mutationStatus(item.mutationId)).isEqualTo("IN_PROGRESS");

        ItemResult retry = service.processItem(item.id, ACTOR, LegacyMasterMode.NONE);

        assertThat(retry.status()).isEqualTo(PublicationRunItemStatus.UNCHANGED);
        assertThat(retry.sendCount()).isEqualTo(2);
        assertThat(retry.prodisWritten()).isFalse();
        assertThat(writingTransactions()).isEqualTo(1);
        assertThat(mutationStatus(item.mutationId)).isEqualTo("PUBLISHED");
        JsonNode resultJson = JSON.readTree((String) itemRow(item.id).get("result_json"));
        assertThat(resultJson.path("sentFromStatus").asText()).isEqualTo("RESULT_UNKNOWN");
        assertThat(resultJson.path("changes")).isEmpty();
        assertThat(itemRow(item.id).get("last_error_code")).isEqualTo("PRODIS_COMMIT_UNKNOWN:08006");
    }

    @Test
    void aCommitThatNeverReachedTheServerIsResultUnknownAndTheRetryAppliesExactlyOnce() throws Exception {
        TestItem item = fixture("NOCOMMIT").item(r -> {
        });
        try (ProdisPublicationJdbc hooked = hookedJdbc(CommitHook.CLOSE_THEN_COMMIT)) {
            ItemResult unknown = service(hooked, Clock.systemUTC()).processItem(item.id, ACTOR, LegacyMasterMode.NONE);

            assertThat(unknown.status()).isEqualTo(PublicationRunItemStatus.RESULT_UNKNOWN);
            assertThat(unknown.lastErrorCode()).startsWith("PRODIS_COMMIT_UNKNOWN:08");
        }
        assertThat(writingTransactions()).isZero();
        assertThat(prices()).containsExactly(110.0, 120.0, 130.0, 140.0, 150.0);

        ItemResult retry = service.processItem(item.id, ACTOR, LegacyMasterMode.NONE);

        assertThat(retry.status()).isEqualTo(PublicationRunItemStatus.APPLIED);
        assertThat(writingTransactions()).isEqualTo(1);
        assertThat(prices()).containsExactly(132.0, 144.0, 156.0, 168.0, 180.0);
    }

    @Test
    void aFailureAfterTheProdisCommitIsResultUnknownAndTheRetryIsUnchanged() {
        TestItem item = fixture("CRASH").item(r -> {
        });
        // De tweede klokaanroep is die van CI-transactie 2: ze faalt NA de Prodis-commit.
        ProdisPublicationService failing = service(jdbc, new FailingClock(2));

        assertThatThrownBy(() -> failing.processItem(item.id, ACTOR, LegacyMasterMode.NONE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("simulated crash");

        Map<String, Object> row = itemRow(item.id);
        assertThat(row.get("status")).isEqualTo("RESULT_UNKNOWN");
        assertThat(row.get("last_error_code")).isEqualTo("CI_ANSWER_NOT_RECORDED");
        assertThat(writingTransactions()).isEqualTo(1);

        assertThat(service.processItem(item.id, ACTOR, LegacyMasterMode.NONE).status())
                .isEqualTo(PublicationRunItemStatus.UNCHANGED);
        assertThat(writingTransactions()).isEqualTo(1);
        assertThat(mutationStatus(item.mutationId)).isEqualTo("PUBLISHED");
    }

    @Test
    void anItemLeftSentByACrashIsSentAgainAndIsUnchanged() {
        TestItem item = fixture("SENTLEFT").item(r -> {
        });
        assertThatThrownBy(() -> service(jdbc, new FailingClock(2)).processItem(item.id, ACTOR, LegacyMasterMode.NONE))
                .isInstanceOf(IllegalStateException.class);
        // Zoals een proces dat na de Prodis-commit hard stopte: ook RESULT_UNKNOWN werd nooit vastgelegd.
        ci.update("update publication_run_item set status = 'SENT', last_error_code = null where id = ?", item.id);

        ItemResult retry = service.processItem(item.id, ACTOR, LegacyMasterMode.NONE);

        assertThat(retry.status()).isEqualTo(PublicationRunItemStatus.UNCHANGED);
        assertThat(retry.sendCount()).isEqualTo(2);
        assertThat(writingTransactions()).isEqualTo(1);
    }

    // --- CONFLICT, REJECTED, NOT_SENT ------------------------------------------------------------------------------

    @Test
    void aConflictWritesNothingAndReturnsTheMutationToReadyForPublication() throws Exception {
        owner("update catalog_article_price set base_price = 105 where id = " + PRICE_ID);
        owner("truncate pubtest_write_log");
        TestItem item = fixture("CONFLICT").item(r -> {
        });

        ItemResult result = service.processItem(item.id, ACTOR, LegacyMasterMode.NONE);

        assertThat(result.status()).isEqualTo(PublicationRunItemStatus.CONFLICT);
        assertThat(result.reasonCode()).isEqualTo("VALUE_CHANGED_IN_PRODIS");
        assertThat(result.prodisWritten()).isFalse();
        assertThat(result.mutationStatus()).isEqualTo(MutationStatus.READY_FOR_PUBLICATION);
        assertThat(writingTransactions()).isZero();
        assertThat(ownerValue("select base_price from catalog_article_price")).isEqualTo(new BigDecimal("105.000000"));
        assertThat(ownerValue("select last_modified_by is null from catalog_article_price")).isEqualTo(true);
        Map<String, Object> row = itemRow(item.id);
        assertThat((String) row.get("reason_detail")).contains("105").contains("expected 100");
        assertThat(JSON.readTree((String) row.get("prior_json")).path("prior").path("basePrice").asText())
                .isEqualTo("105");
        assertThat(JSON.readTree((String) row.get("result_json")).path("changes")).isEmpty();
    }

    @Test
    void aChangeCommittedWhileWeWaitForTheLockIsSeenUnderTheLockAndIsAConflict() throws Exception {
        TestItem item = fixture("UNDERLOCK").item(r -> {
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection owner = ProdisPublicationTestDatabase.owner()) {
            owner.setAutoCommit(false);
            try (Statement statement = owner.createStatement()) {
                // Een gelijktijdige Prodis-gebruiker: de parallelrun (en elke lees-lookup) zag nog 100.
                statement.executeUpdate("update catalog_article_price set base_price = 107 where id = " + PRICE_ID);
            }
            Future<ItemResult> processing = pool.submit(() -> service.processItem(item.id, ACTOR, LegacyMasterMode.NONE));
            awaitWriterWaitingForALock();
            owner.commit();

            ItemResult result = processing.get(30, TimeUnit.SECONDS);

            assertThat(result.status()).isEqualTo(PublicationRunItemStatus.CONFLICT);
        } finally {
            pool.shutdownNow();
        }
        assertThat(JSON.readTree((String) itemRow(item.id).get("prior_json")).path("prior").path("basePrice").asText())
                .isEqualTo("107");
        assertThat(ownerValue("select base_price from catalog_article_price")).isEqualTo(new BigDecimal("107.000000"));
        assertThat(ownerValue("select count(*) from pubtest_write_log where modified_by = '" + SUBJECT + "'"))
                .isEqualTo(0L);
    }

    @Test
    void aLockTimeoutIsNotAppliedAndTheItemIsPendingAgain() throws Exception {
        TestItem item = fixture("LOCK").item(r -> {
        });
        try (Connection owner = ProdisPublicationTestDatabase.owner()) {
            owner.setAutoCommit(false);
            try (Statement statement = owner.createStatement()) {
                statement.execute("select id from catalog_article_price_detail where code = '3' for update");
            }
            ItemResult result = service.processItem(item.id, ACTOR, LegacyMasterMode.NONE);

            assertThat(result.status()).isEqualTo(PublicationRunItemStatus.PENDING);
            assertThat(result.lastErrorCode()).isEqualTo("PRODIS_NOT_APPLIED:55P03");
            assertThat(result.sendCount()).isEqualTo(1);
            assertThat(result.mutationStatus()).isEqualTo(MutationStatus.IN_PROGRESS);
            owner.rollback();
        }
        assertThat(writingTransactions()).isZero();
        assertThat(prices()).containsExactly(110.0, 120.0, 130.0, 140.0, 150.0);
        Map<String, Object> row = itemRow(item.id);
        assertThat(row.get("outcome")).isNull();
        assertThat(row.get("answered_at")).isNull();

        ItemResult retry = service.processItem(item.id, ACTOR, LegacyMasterMode.NONE);

        assertThat(retry.status()).isEqualTo(PublicationRunItemStatus.APPLIED);
        assertThat(retry.sendCount()).isEqualTo(2);
        assertThat(writingTransactions()).isEqualTo(1);
    }

    @Test
    void anUnknownArticleIsRejectedWithoutWritingOrLocking() {
        TestItem item = fixture("NOTFOUND").item(r -> r.reference = "NOPE");

        ItemResult result = service.processItem(item.id, ACTOR, LegacyMasterMode.NONE);

        assertThat(result.status()).isEqualTo(PublicationRunItemStatus.REJECTED);
        assertThat(result.reasonCode()).isEqualTo("ARTICLE_NOT_FOUND");
        assertThat(result.prodisContacted()).isTrue();
        assertThat(result.mutationStatus()).isEqualTo(MutationStatus.READY_FOR_PUBLICATION);
        assertThat(itemRow(item.id).get("prior_json")).isNull();
        assertThat(writingTransactions()).isZero();
    }

    @Test
    void aDescriptionLongerThan4000CharactersIsRejectedWithoutContactingProdis() {
        TestItem item = fixture("LONG").item(r -> r.description = "o".repeat(4001));

        ItemResult result = service.processItem(item.id, ACTOR, LegacyMasterMode.NONE);

        assertThat(result.status()).isEqualTo(PublicationRunItemStatus.REJECTED);
        assertThat(result.reasonCode()).isEqualTo("DESCRIPTION_TOO_LONG");
        assertThat(result.prodisContacted()).isFalse();
        assertThat(result.mutationStatus()).isEqualTo(MutationStatus.READY_FOR_PUBLICATION);
        assertThat(writingTransactions()).isZero();
        assertThat(ownerValue("select description from catalog_article_description")).isEqualTo("Omschrijving");
    }

    @Test
    void aCreateIsNotSentAndProdisIsNotContacted() {
        TestItem item = fixture("CREATE").item(r -> r.actionType = "CREATE");

        ItemResult result = service.processItem(item.id, ACTOR, LegacyMasterMode.NONE);

        assertThat(result.status()).isEqualTo(PublicationRunItemStatus.NOT_SENT);
        assertThat(result.reasonCode()).isEqualTo("CREATE_NOT_SUPPORTED_IN_V1");
        assertThat(result.sendCount()).isZero();
        assertThat(result.mutationStatus()).isEqualTo(MutationStatus.READY_FOR_PUBLICATION);
        assertThat(itemRow(item.id).get("sent_at")).isNull();
        assertThat(writingTransactions()).isZero();
    }

    // --- ongeldige invoer: niets gewijzigd ----------------------------------------------------------------------------

    @Test
    void aTamperedRequestIsRefusedAndNothingChanges() {
        TestItem item = fixture("TAMPER").item(r -> {
        });
        ci.update("update publication_run_item set request_json = replace(request_json, '\"120.000000\"', "
                + "'\"999.000000\"') where id = ?", item.id);

        assertThatThrownBy(() -> service.processItem(item.id, ACTOR, LegacyMasterMode.NONE))
                .isInstanceOfSatisfying(ConflictException.class, failure -> assertThat(failure.getCode())
                        .isEqualTo(ProdisPublicationService.CODE_ITEM_REQUEST_INVALID));
        // Ook met een bijgewerkte request_hash: de canonieke hash van de projectie verraadt de wijziging.
        String tampered = ci.queryForObject("select request_json from publication_run_item where id = ?", String.class,
                item.id);
        ci.update("update publication_run_item set request_hash = ? where id = ?",
                ProdisPublicationRequestJson.sha256Hex(tampered), item.id);
        assertThatThrownBy(() -> service.processItem(item.id, ACTOR, LegacyMasterMode.NONE))
                .isInstanceOfSatisfying(ConflictException.class, failure -> assertThat(failure.getMessage())
                        .contains("does not match its requestHash"));

        Map<String, Object> row = itemRow(item.id);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("send_count")).isEqualTo(0);
        assertThat(mutationStatus(item.mutationId)).isEqualTo("READY_FOR_PUBLICATION");
        assertThat(writingTransactions()).isZero();
    }

    @Test
    void aMutationThatIsNoLongerPublishableIsRefused() {
        TestItem item = fixture("EXPIRED").item(r -> {
        });
        ci.update("update import_mutation set status = 'EXPIRED' where id = ?", item.mutationId);

        assertThatThrownBy(() -> service.processItem(item.id, ACTOR, LegacyMasterMode.NONE))
                .isInstanceOfSatisfying(ConflictException.class, failure -> assertThat(failure.getCode())
                        .isEqualTo(ProdisPublicationService.CODE_MUTATION_NOT_PUBLISHABLE));
        assertThat(itemRow(item.id).get("status")).isEqualTo("PENDING");
        assertThat(writingTransactions()).isZero();
    }

    @Test
    void withoutAWriteConnectionNothingIsTouched() {
        TestItem item = fixture("NOCONF").item(r -> {
        });

        assertThatThrownBy(() -> service(ProdisPublicationJdbc.disabled(), Clock.systemUTC())
                .processItem(item.id, ACTOR, LegacyMasterMode.NONE))
                .isInstanceOf(ProdisPublicationWriteNotConfiguredException.class);
        assertThat(itemRow(item.id).get("status")).isEqualTo("PENDING");
        assertThat(mutationStatus(item.mutationId)).isEqualTo("READY_FOR_PUBLICATION");
    }

    @Test
    void anUnknownItemOrASystemActorIsRefused() {
        assertThatThrownBy(() -> service.processItem(-1L, ACTOR, LegacyMasterMode.NONE))
                .isInstanceOfSatisfying(NotFoundException.class, failure -> assertThat(failure.getCode())
                        .isEqualTo(ProdisPublicationService.CODE_ITEM_NOT_FOUND));
        TestItem item = fixture("SYSTEM").item(r -> {
        });
        assertThatThrownBy(() -> service.processItem(item.id, ActorIdentity.unverified("system"), LegacyMasterMode.NONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(itemRow(item.id).get("status")).isEqualTo("PENDING");
    }

    // --- writer rechtstreeks --------------------------------------------------------------------------------------

    @Test
    void theWriterLocksPriceDetailsAndDescriptionButNeverCatalogArticleAndStaysInTheTestDatabase() throws Exception {
        ProdisPublicationWriter writer = writer(jdbc);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> written = pool.submit(() -> writer.write(identity(), SUBJECT, locked -> {
                inside.countDown();
                await(release);
                return new Decision<>("plan", null);
            }).written());
            assertThat(inside.await(30, TimeUnit.SECONDS)).isTrue();
            List<String> locks = new ArrayList<>();
            List<String> databases = new ArrayList<>();
            try (Connection owner = ProdisPublicationTestDatabase.owner(); Statement statement = owner.createStatement()) {
                try (ResultSet rows = statement.executeQuery("select distinct c.relname from pg_locks l join pg_class c "
                        + "on c.oid = l.relation where c.relkind = 'r' and l.mode = 'RowShareLock' and l.pid in (select pid from "
                        + "pg_stat_activity where usename = '" + ProdisPublicationTestDatabase.ROLE + "') order by 1")) {
                    while (rows.next()) {
                        locks.add(rows.getString(1));
                    }
                }
                try (ResultSet rows = statement.executeQuery("select distinct datname from pg_stat_activity where "
                        + "usename = '" + ProdisPublicationTestDatabase.ROLE + "'")) {
                    while (rows.next()) {
                        databases.add(rows.getString(1));
                    }
                }
            }
            release.countDown();
            assertThat(written.get(30, TimeUnit.SECONDS)).isFalse();
            assertThat(locks).containsExactly("catalog_article_description", "catalog_article_price",
                    "catalog_article_price_detail");
            assertThat(databases).containsExactly(ProdisPublicationTestDatabase.DATABASE);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThatThrownBy(() -> ProdisPublicationTestDatabase.requireTestDatabaseUrl(
                "jdbc:postgresql://localhost:5432/Prodis")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anUpdateWithoutItsRowRollsBackEverythingOfTheTransaction() {
        ProdisPublicationWriter writer = writer(jdbc);

        assertThatThrownBy(() -> writer.write(identity(), SUBJECT, locked -> new Decision<>("plan", new WriteSet()
                .basePrice(new BigDecimal("1")).detail("9", DetailColumn.PRICE, 1d))))
                .isInstanceOf(ProdisUpdateCountException.class);
        assertThat(writingTransactions()).isZero();
        assertThat(ownerValue("select base_price from catalog_article_price")).isEqualTo(new BigDecimal("100.000000"));
    }

    @Test
    void aPlannerFailureInsideTheTransactionRollsBackAndIsRethrown() {
        ProdisPublicationWriter writer = writer(jdbc);
        IllegalArgumentException planner = new IllegalArgumentException("planner says no");

        assertThatThrownBy(() -> writer.write(identity(), SUBJECT, locked -> {
            throw planner;
        })).isSameAs(planner);
        assertThatThrownBy(() -> writer.write(identity(), " ", locked -> new Decision<>("plan", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(writingTransactions()).isZero();
    }

    // --- golden: afgeleide prijzen bit-identiek aan Prodis' LegacyPriceCalculator ---------------------------------

    static Stream<Arguments> goldenCatalogRows() throws IOException {
        return readGolden().stream().filter(ProdisPublicationWriteTest::writableGoldenRow)
                .map(row -> Arguments.of(row.get("case_id"), row));
    }

    @Test
    void enoughGoldenRowsAreWrittenEndToEnd() throws IOException {
        assertThat(goldenCatalogRows().count()).isGreaterThanOrEqualTo(20);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("goldenCatalogRows")
    void theDerivedPricesWrittenToProdisAreBitIdenticalToTheGolden(String caseId, Map<String, String> row)
            throws SQLException {
        BigDecimal base = new BigDecimal(row.get("base_price"));
        BigDecimal prior = base.add(BigDecimal.ONE).setScale(6);
        try (Connection owner = ProdisPublicationTestDatabase.owner()) {
            try (PreparedStatement statement = owner.prepareStatement("update catalog_article_price set price_code = ?, "
                    + "brut_price_politics = ?, base_price = ?, brut_price = ?, brut_price_percentage = ? where id = ?")) {
                statement.setString(1, text(row.get("price_code")));
                statement.setString(2, text(row.get("brut_code")));
                statement.setBigDecimal(3, prior);
                statement.setObject(4, number(row.get("brut_price")));
                statement.setObject(5, number(row.get("brut_pct")));
                statement.setLong(6, PRICE_ID);
                statement.executeUpdate();
            }
            Map<Integer, Double> percentages = cells(row, "pct");
            Map<Integer, Double> prices = cells(row, "price");
            try (PreparedStatement statement = owner.prepareStatement("update catalog_article_price_detail set "
                    + "percentage = ?, price = ? where id = ?")) {
                for (int code = 1; code <= 5; code++) {
                    statement.setObject(1, percentages.get(code));
                    statement.setObject(2, prices.get(code));
                    statement.setLong(3, DETAIL_ID_VKP1 + code - 1);
                    statement.executeUpdate();
                }
            }
        }
        owner("truncate pubtest_write_log");
        TestItem item = fixture("GOLD-" + caseId).item(r -> {
            r.before = prior;
            r.after = base;
            r.description = null;
            r.descriptionState = "NOT_MAPPED";
        });

        ItemResult result = service.processItem(item.id, ACTOR, LegacyMasterMode.NONE);

        assertThat(result.status()).as(caseId).isEqualTo(PublicationRunItemStatus.APPLIED);
        assertThat(writingTransactions()).as(caseId).isEqualTo(1);
        assertThat((BigDecimal) ownerValue("select base_price from catalog_article_price")).as(caseId)
                .isEqualByComparingTo(base);
        Map<Integer, Double> expectedPrices = cells(row, "exp_price");
        Map<Integer, Double> expectedPercentages = "1".equals(text(row.get("price_code")).trim())
                ? cells(row, "pct") : cells(row, "exp_pct");
        List<Double> actualPrices = prices();
        List<Double> actualPercentages = percentages();
        for (int code = 1; code <= 5; code++) {
            assertBitIdentical(caseId + " VKP" + code + " price", actualPrices.get(code - 1), expectedPrices.get(code));
            assertBitIdentical(caseId + " VKP" + code + " percentage", actualPercentages.get(code - 1),
                    expectedPercentages.get(code));
        }
        assertBitIdentical(caseId + " brut_price", (Double) ownerValue("select brut_price from catalog_article_price"),
                number(row.get("exp_brut_price")));
        assertBitIdentical(caseId + " brut_price_percentage",
                (Double) ownerValue("select brut_price_percentage from catalog_article_price"),
                number(row.get("exp_brut_pct")));
    }

    /** Catalogusrij, prijscode 0/1/2, alle vijf detailcellen aanwezig (UPDATE-only-vorm), basisprijs ≠ 0 (Q2). */
    private static boolean writableGoldenRow(Map<String, String> row) {
        if (!"Y".equals(row.get("catalog")) || new BigDecimal(row.get("base_price")).signum() == 0) {
            return false;
        }
        for (int code = 1; code <= 5; code++) {
            if (row.get("pct" + code).isEmpty() || row.get("price" + code).isEmpty()) {
                return false;
            }
        }
        String priceCode = text(row.get("price_code"));
        return priceCode != null && Set.of("0", "1", "2").contains(priceCode.trim());
    }

    // --- hulp -------------------------------------------------------------------------------------------------------

    private ProdisPublicationService service(ProdisPublicationJdbc prodis, Clock clock) {
        ProdisReferenceService notConfigured = new ProdisReferenceService(new ProdisReferenceDao(
                new StaticListableBeanFactory().getBeanProvider(ProdisReferenceJdbc.class)));
        return new ProdisPublicationService(items, mutations, writer(prodis), notConfigured, transactionManager, clock);
    }

    private static ProdisPublicationWriter writer(ProdisPublicationJdbc prodis) {
        return new ProdisPublicationWriter(new StaticListableBeanFactory(Map.of("prodisPublicationJdbc", prodis))
                .getBeanProvider(ProdisPublicationJdbc.class));
    }

    private static CatalogArticleIdentity identity() {
        return new CatalogArticleIdentity("50001", "50001|G|", "SCOPE", "AB1");
    }

    private Map<String, Object> itemRow(long id) {
        return ci.queryForMap("select * from publication_run_item where id = ?", id);
    }

    private String mutationStatus(long id) {
        return ci.queryForObject("select status from import_mutation where id = ?", String.class, id);
    }

    private static long writingTransactions() {
        return (Long) ownerValue("select count(distinct tx) from pubtest_write_log");
    }

    /** {@code tabel|kolommen} per bijgewerkte rij (volgorde zonder betekenis). */
    private static List<String> changedColumns() {
        List<String> lines = new ArrayList<>();
        try (Connection owner = ProdisPublicationTestDatabase.owner(); Statement statement = owner.createStatement();
             ResultSet rows = statement.executeQuery("select tbl || '|' || changed from pubtest_write_log order by id")) {
            while (rows.next()) {
                lines.add(rows.getString(1));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
        return lines;
    }

    private static List<Double> prices() {
        return detailColumn("price");
    }

    private static List<Double> percentages() {
        return detailColumn("percentage");
    }

    private static List<Double> detailColumn(String column) {
        List<Double> values = new ArrayList<>();
        try (Connection owner = ProdisPublicationTestDatabase.owner(); Statement statement = owner.createStatement();
             ResultSet rows = statement.executeQuery("select " + column + " from catalog_article_price_detail order by code")) {
            while (rows.next()) {
                double value = rows.getDouble(1);
                values.add(rows.wasNull() ? null : value);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
        return values;
    }

    private static void owner(String sql) {
        try (Connection owner = ProdisPublicationTestDatabase.owner(); Statement statement = owner.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Wacht tot een sessie van de testrol op een rijslot wacht. */
    private static void awaitWriterWaitingForALock() throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            Long waiting = (Long) ownerValue("select count(*) from pg_locks where not granted and pid in (select pid from "
                    + "pg_stat_activity where usename = '" + ProdisPublicationTestDatabase.ROLE + "')");
            if (waiting > 0) {
                return;
            }
            Thread.sleep(30);
        }
        throw new IllegalStateException("the writer never waited for the lock");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timeout");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static void assertBitIdentical(String what, Double actual, Double expected) {
        if (expected == null) {
            assertThat(actual).as(what).isNull();
            return;
        }
        assertThat(actual).as(what).isNotNull();
        assertThat(Double.doubleToRawLongBits(actual)).as(what + ": " + actual + " <> " + expected)
                .isEqualTo(Double.doubleToRawLongBits(expected));
    }

    // --- golden-CSV (zelfde interpretatie als LegacyCatalogPriceFormulaGoldenTest) ----------------------------

    private static List<Map<String, String>> readGolden() throws IOException {
        Path directory = Path.of("").toAbsolutePath();
        Path file = null;
        while (directory != null && file == null) {
            Path candidate = directory.resolve(GOLDEN);
            if (Files.isRegularFile(candidate)) {
                file = candidate;
            }
            directory = directory.getParent();
        }
        assertThat(file).as(GOLDEN).isNotNull();
        List<Map<String, String>> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String[] header = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#")) {
                    continue;
                }
                String[] cells = line.split(";", -1);
                if (header == null) {
                    header = cells;
                    continue;
                }
                Map<String, String> row = new HashMap<>();
                for (int i = 0; i < header.length; i++) {
                    row.put(header[i], cells[i]);
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /** Leeg = afwezig, "null" = aanwezig met null. */
    private static Map<Integer, Double> cells(Map<String, String> row, String prefix) {
        Map<Integer, Double> map = new TreeMap<>();
        for (int code = 1; code <= 5; code++) {
            String cell = row.get(prefix + code);
            if (!cell.isEmpty()) {
                map.put(code, "null".equals(cell) ? null : Double.valueOf(cell));
            }
        }
        return map;
    }

    private static Double number(String cell) {
        return cell.isEmpty() ? null : Double.valueOf(cell);
    }

    /** Tekstcel ongewijzigd (spaties tellen); {@code <null>} = null. */
    private static String text(String cell) {
        return "<null>".equals(cell) ? null : cell;
    }

    // --- commit-hook en klok ----------------------------------------------------------------------------------------

    private enum CommitHook {
        /** De echte commit gaat door, daarna gaat het antwoord verloren (08006): de commit is gebeurd, de uitkomst onbekend. */
        COMMIT_THEN_FAIL,
        /** De fysieke verbinding sluit vóór de echte commit (08003): niets vastgelegd, de uitkomst onbekend. */
        CLOSE_THEN_COMMIT
    }

    /** Een pool als productie (autoCommit=false, READ COMMITTED, testrol) met een commit-hook; enkel testcode. */
    private static ProdisPublicationJdbc hookedJdbc(CommitHook hook) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("p5b-commit-hook");
        config.setJdbcUrl(ProdisPublicationTestDatabase.URL);
        config.setUsername(ProdisPublicationTestDatabase.ROLE);
        config.setPassword(ProdisPublicationTestDatabase.ROLE_PASSWORD);
        config.setAutoCommit(false);
        config.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        HikariDataSource pool = new HikariDataSource(config);
        DataSource hooked = (DataSource) Proxy.newProxyInstance(ProdisPublicationWriteTest.class.getClassLoader(),
                new Class<?>[] {DataSource.class, AutoCloseable.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                        pool.close();
                        return null;
                    }
                    Object result = invoke(method, pool, args);
                    if (!method.getName().equals("getConnection")) {
                        return result;
                    }
                    Connection connection = (Connection) result;
                    ProdisPublicationTestDatabase.requireTestDatabase(connection);
                    return Proxy.newProxyInstance(ProdisPublicationWriteTest.class.getClassLoader(),
                            new Class<?>[] {Connection.class}, (connectionProxy, connectionMethod, connectionArgs) -> {
                                if (!connectionMethod.getName().equals("commit")) {
                                    return invoke(connectionMethod, connection, connectionArgs);
                                }
                                if (hook == CommitHook.COMMIT_THEN_FAIL) {
                                    connection.commit();
                                    throw new SQLException("simulated lost commit acknowledgement", "08006");
                                }
                                ((Connection) connection.unwrap(PGConnection.class)).close();
                                return invoke(connectionMethod, connection, connectionArgs);
                            });
                });
        return ProdisPublicationJdbc.of(hooked, 30);
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException invocation) {
            throw invocation.getCause();
        }
    }

    /** Een klok die bij de n-de aanroep faalt (een crash/CI-fout op dat moment). */
    private static final class FailingClock extends Clock {
        private final AtomicInteger calls = new AtomicInteger();
        private final int failOn;

        FailingClock(int failOn) {
            this.failOn = failOn;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            if (calls.incrementAndGet() == failOn) {
                throw new IllegalStateException("simulated crash after the Prodis commit");
            }
            return Instant.now();
        }
    }

    // --- CI-fixture -------------------------------------------------------------------------------------------------

    private record TestItem(long id, long mutationId) {
    }

    /** Een snapshotrij zoals de lezer ze levert; standaard UPDATE 100 → 120 EUR met omschrijving, spec 2. */
    private static final class RowBuilder {
        String actionType = "UPDATE";
        String reference = "AB1";
        BigDecimal before = new BigDecimal("100.000000");
        BigDecimal after = new BigDecimal("120.000000");
        String description = "Nieuwe omschrijving";
        String descriptionState = "VALUE";
        List<SourcePrice> prices = new ArrayList<>();
    }

    private Fixture fixture(String prefix) {
        String unique = "P5B-" + prefix + "-" + Long.toString(System.nanoTime(), 36);
        SourceOrganisation organisation = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + "-ORG BV", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(
                new ImportDefinition(organisation, unique + "-DEF", unique + " catalogus", USER));
        ImportDefinitionRevision revision = new ImportDefinitionRevision(definition, 1, IdentityProfileKind.THREE_PART,
                USER);
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
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + "-LINK koppeling", definition, supplier, "PSARF050"));
        CatalogImportTask task = tasks.saveAndFlush(new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL));
        Delivery delivery = deliveries.saveAndFlush(new Delivery(task, "manual:" + unique, Instant.now()));
        ImportBatch batch = batches.saveAndFlush(new ImportBatch(delivery, link, revision, 1, USER));
        PublicationBundle bundle = bundles.saveAndFlush(new PublicationBundle("BND-" + unique,
                PublicationTargetMode.PRODUCTION, USER));
        // Een terminale run: het item hangt eraan, maar de run houdt geen PRODUCTION-slot vast (orchestratie is P5c).
        String runKey = "p5b-test:" + unique;
        ci.update("insert into publication_run (bundle_id, target_mode, status, requested_by, requested_at, finished_at, "
                        + "bundle_content_hash, failure_code, idempotency_key, active_marker) values (?, 'PRODUCTION', "
                        + "'FAILED', ?, ?, ?, ?, 'TEST_P5B', ?, null)", bundle.getId(), USER,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), new byte[32], runKey);
        long runId = ci.queryForObject("select id from publication_run where idempotency_key = ?", Long.class, runKey);
        return new Fixture(batch, bundle.getId(), runId, unique);
    }

    private final class Fixture {
        private final ImportBatch batch;
        private final long bundleId;
        private final long runId;
        private final String unique;
        private int next;

        Fixture(ImportBatch batch, long bundleId, long runId, String unique) {
            this.batch = batch;
            this.bundleId = bundleId;
            this.runId = runId;
            this.unique = unique;
        }

        /** Een nieuwe mutatie (READY_FOR_PUBLICATION) met één PENDING-item; het verzoek is de projectie van de rij. */
        TestItem item(Consumer<RowBuilder> change) {
            RowBuilder r = new RowBuilder();
            change.accept(r);
            next++;
            ImportMutation mutation = new ImportMutation(batch, MutationActionType.valueOf(r.actionType),
                    MutationTargetDomain.OFFER, MutationStatus.READY_FOR_PUBLICATION, unique + ":" + next + ":OFFER");
            mutation.setIdentitySupplier("50001");
            mutation.setIdentitySupplierGroup("G");
            mutation.setIdentitySupplierReference(r.reference);
            long mutationId = mutations.saveAndFlush(mutation).getId();
            SourceRow row = new SourceRow(bundleId, 0L, mutationId, r.actionType, "50001", "G", r.reference, null,
                    "EMPTY", "SCOPE", r.before, r.after, "EUR", r.description, r.descriptionState, "2", r.prices);
            Item request = ProdisPublicationProjection.project(row);
            String json = ProdisPublicationRequestJson.write(request);
            String key = "p5b-test:" + unique + ":mut:" + mutationId + ":pa:1";
            ci.update("insert into publication_run_item (run_id, mutation_id, publication_attempt, idempotency_key, "
                    + "request_hash, request_json) values (?, ?, 1, ?, ?, ?)", runId, mutationId, key,
                    ProdisPublicationRequestJson.sha256Hex(json), json);
            long id = ci.queryForObject("select id from publication_run_item where idempotency_key = ?", Long.class, key);
            return new TestItem(id, mutationId);
        }
    }
}
