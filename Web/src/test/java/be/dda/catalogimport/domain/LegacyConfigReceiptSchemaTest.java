package be.dda.catalogimport.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.dao.LegacyConfigOriginRepository;
import be.dda.catalogimport.dao.LegacyConfigReceiptRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Slice S1 van {@code docs/design/legacy-config-bundle-design.md} (par. 4, changesets 020-1 en 020-2): bewijst dat
 * changeset 020 migreert, dat Hibernate {@link LegacyConfigReceipt} en {@link LegacyConfigOrigin} met
 * {@code ddl-auto: validate} aanvaardt (het booten van deze context ís dat bewijs), dat de kolombreedtes de grenzen
 * van het contract volgen (40/40/20/10, hashes 64) en dat elke databasecheck werkelijk weigert wat het ontwerp
 * verbiedt.
 *
 * <h2>Business rule vs. implementatie</h2>
 * <ul>
 *   <li><b>Regel:</b> idempotentie geldt enkel tegenover de laatste ontvangst van een herkomst; een terugkerende inhoud
 *       (A → B → A) is een nieuwe ontvangst (beslissingslog 2026-10-20). <b>Implementatie:</b> geen unieke sleutel op
 *       de inhoud, wel de niet-unieke index {@code idx_legacy_config_receipt_origin_latest}
 *       {@code (origin_environment, origin_dossier, interface_code, id desc)}.</li>
 *   <li><b>Regel:</b> hoogstens een open ontvangst per herkomst. <b>Implementatie:</b> NULL-marker
 *       {@code open_marker} + {@code uk_legacy_config_receipt_open} + {@code ck_legacy_config_receipt_open_marker}.</li>
 *   <li><b>Regel:</b> een secret-uitkomst bestaat enkel als er een secret kwam; geen kolom bevat een secret.</li>
 *   <li><b>Regel:</b> statussen, auditvelden en verwijzingen zijn consistent (checks per status).</li>
 * </ul>
 * Elke rij krijgt een vers dossier: het {@code local}-profiel draait tegen een blijvende PostgreSQL.
 */
@SpringBootTest(properties = "catalogimport.screening.recovery-on-startup=false")
@ActiveProfiles("local")
class LegacyConfigReceiptSchemaTest {

    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final String SERVICE = "svc:prodis-datasync-catalogimport";

    @Autowired
    private LegacyConfigReceiptRepository receipts;
    @Autowired
    private LegacyConfigOriginRepository origins;
    @Autowired
    private JdbcTemplate jdbc;

    // --- Vorm van het schema ----------------------------------------------------------------------------

    @Test
    void createsBothTablesWithTheDocumentedColumnTypes() {
        String r = "legacy_config_receipt";
        assertColumn(r, "id", "bigint", null, "NO");
        assertColumn(r, "origin_environment", "character varying", 40, "NO");
        assertColumn(r, "origin_dossier", "character varying", 40, "NO");
        assertColumn(r, "interface_code", "character varying", 20, "NO");
        assertColumn(r, "bundle_version", "integer", null, "NO");
        assertColumn(r, "content_hash", "character varying", 64, "NO");
        assertColumn(r, "definition_content_hash", "character varying", 64, "YES");
        assertColumn(r, "legacy_definition_code", "character varying", 10, "YES");
        assertColumn(r, "bundle_json", "text", null, "NO");
        assertColumn(r, "secret_received", "boolean", null, "NO");
        assertColumn(r, "secret_outcome", "character varying", 40, "YES");
        assertColumn(r, "credential_id", "bigint", null, "YES");
        assertColumn(r, "status", "character varying", 20, "NO");
        assertColumn(r, "open_marker", "boolean", null, "YES");
        assertColumn(r, "exported_at", "timestamp with time zone", null, "NO");
        assertColumn(r, "received_at", "timestamp with time zone", null, "NO");
        assertColumn(r, "received_by", "character varying", 100, "NO");
        assertColumn(r, "received_by_subject", "character varying", 255, "YES");
        assertColumn(r, "superseded_by_receipt_id", "bigint", null, "YES");
        assertColumn(r, "applied_at", "timestamp with time zone", null, "YES");
        assertColumn(r, "applied_by", "character varying", 100, "YES");
        assertColumn(r, "applied_by_subject", "character varying", 255, "YES");
        assertColumn(r, "apply_report_json", "text", null, "YES");
        assertColumn(r, "discarded_at", "timestamp with time zone", null, "YES");
        assertColumn(r, "discarded_by", "character varying", 100, "YES");
        assertColumn(r, "discarded_by_subject", "character varying", 255, "YES");
        assertColumn(r, "discarded_reason", "character varying", 500, "YES");

        String o = "legacy_config_origin";
        assertColumn(o, "origin_environment", "character varying", 40, "NO");
        assertColumn(o, "origin_dossier", "character varying", 40, "NO");
        assertColumn(o, "interface_code", "character varying", 20, "NO");
        assertColumn(o, "import_link_id", "bigint", null, "YES");
        assertColumn(o, "catalog_import_task_id", "bigint", null, "YES");
        assertColumn(o, "credential_id", "bigint", null, "YES");
        assertColumn(o, "last_applied_receipt_id", "bigint", null, "YES");
        assertColumn(o, "created_at", "timestamp with time zone", null, "NO");
        assertColumn(o, "updated_at", "timestamp with time zone", null, "YES");
    }

    /** Geen kolom kan een secret in zuivere tekst bevatten (design par. 2, contract par. 2.1 regel 5). */
    @Test
    void hasNoColumnForASecretValue() {
        List<String> columns = jdbc.queryForList("select column_name from information_schema.columns "
                + "where table_schema = current_schema() and table_name in "
                + "('legacy_config_receipt', 'legacy_config_origin')", String.class);

        assertThat(columns).isNotEmpty()
                .noneMatch(name -> name.equals("secret") || name.contains("password") || name.contains("plain")
                        || name.equals("secret_value") || name.contains("ciphertext"));
    }

    @Test
    void declaresEveryDocumentedConstraint() {
        for (String name : List.of("pk_legacy_config_receipt", "uk_legacy_config_receipt_open", "fk_legacy_config_receipt_credential",
                "fk_legacy_config_receipt_superseded_by", "ck_legacy_config_receipt_status",
                "ck_legacy_config_receipt_open_marker", "ck_legacy_config_receipt_bundle_version",
                "ck_legacy_config_receipt_content_hash", "ck_legacy_config_receipt_definition_hash",
                "ck_legacy_config_receipt_keys_not_blank", "ck_legacy_config_receipt_secret_outcome",
                "ck_legacy_config_receipt_credential", "ck_legacy_config_receipt_superseded",
                "ck_legacy_config_receipt_applied", "ck_legacy_config_receipt_discarded",
                "ck_legacy_config_receipt_received_subject", "ck_legacy_config_receipt_applied_subject",
                "ck_legacy_config_receipt_discarded_subject", "pk_legacy_config_origin", "uk_legacy_config_origin",
                "fk_legacy_config_origin_import_link", "fk_legacy_config_origin_task",
                "fk_legacy_config_origin_credential", "fk_legacy_config_origin_last_applied",
                "ck_legacy_config_origin_keys_not_blank")) {
            assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints "
                    + "where constraint_schema = current_schema() and constraint_name = ?", Long.class, name))
                    .as(name).isEqualTo(1L);
        }
        // Beslissingslog 2026-10-20 (A -> B -> A): geen unieke sleutel meer op de inhoud.
        assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints "
                + "where constraint_schema = current_schema() and constraint_name = ?", Long.class,
                "uk_legacy_config_receipt_content")).isZero();
        assertThat(jdbc.queryForList("select column_name from information_schema.key_column_usage "
                        + "where constraint_schema = current_schema() and constraint_name = ? "
                        + "order by ordinal_position", String.class, "uk_legacy_config_receipt_open"))
                .containsExactly("origin_environment", "origin_dossier", "interface_code", "open_marker");
    }

    // --- Mapping en statusovergang ----------------------------------------------------------------------

    @Test
    void persistsAReceiptAndSupersedesItThroughTheEntity() {
        String dossier = dossier();
        Instant now = Instant.now();
        LegacyConfigReceipt first = receipts.saveAndFlush(receipt(dossier, HASH_A, now));

        assertThat(first.getStatus()).isEqualTo(LegacyConfigReceiptStatus.RECEIVED);
        assertThat(first.getOpenMarker()).isTrue();
        assertThat(first.isSecretReceived()).isFalse();
        assertThat(first.getSecretOutcome()).isNull();
        assertThat(first.toString()).doesNotContain("bundle").contains(HASH_A);

        first.markSuperseded();
        receipts.saveAndFlush(first);
        LegacyConfigReceipt second = receipts.saveAndFlush(receipt(dossier, HASH_B, now));
        first.linkSuccessor(second.getId());
        receipts.saveAndFlush(first);

        LegacyConfigReceipt reloaded = receipts.findById(first.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(LegacyConfigReceiptStatus.SUPERSEDED);
        assertThat(reloaded.getOpenMarker()).isNull();
        assertThat(reloaded.getSupersededByReceiptId()).isEqualTo(second.getId());
        assertThat(jdbc.queryForObject("select id from legacy_config_receipt where origin_dossier = ? "
                + "and open_marker = true", Long.class, dossier)).isEqualTo(second.getId());
        assertThat(receipts.findFirstByOriginEnvironmentAndOriginDossierAndInterfaceCodeOrderByIdDesc("TEST", dossier,
                "IFACE")).get().extracting(LegacyConfigReceipt::getId).isEqualTo(second.getId());
        assertThatThrownBy(first::markSuperseded).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theEntityRefusesMissingData() {
        Instant now = Instant.now();
        assertThatThrownBy(() -> new LegacyConfigReceipt(" ", "D", "I", 1, HASH_A, null, null, "{}", now, now,
                SERVICE, null)).isInstanceOf(IllegalArgumentException.class).hasMessage("originEnvironment is required");
        assertThatThrownBy(() -> new LegacyConfigReceipt("E", "D", "I", 1, HASH_A, null, null, null, now, now,
                SERVICE, null)).isInstanceOf(IllegalArgumentException.class).hasMessage("bundleJson is required");
        assertThatThrownBy(() -> new LegacyConfigReceipt("E", "D", "I", 1, HASH_A, null, null, "{}", null, now,
                SERVICE, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void persistsAnOriginWithoutReferences() {
        String dossier = dossier();
        LegacyConfigOrigin origin = origins.saveAndFlush(new LegacyConfigOrigin("TEST", dossier, "IFACE",
                Instant.now()));

        LegacyConfigOrigin found = origins.findByOriginEnvironmentAndOriginDossierAndInterfaceCode("TEST", dossier,
                "IFACE").orElseThrow();
        assertThat(found.getId()).isEqualTo(origin.getId());
        assertThat(found.getImportLinkId()).isNull();
        assertThat(found.getCredentialId()).isNull();
        assertThatThrownBy(() -> origins.saveAndFlush(new LegacyConfigOrigin("TEST", dossier, "IFACE",
                Instant.now()))).isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- Checks en sleutels -----------------------------------------------------------------------------

    /** De index voor "laatste ontvangst per herkomst": niet uniek, op de herkomst en {@code id desc}. */
    @Test
    void theLatestReceiptIndexExistsAndIsNotUnique() {
        String definition = jdbc.queryForObject("select indexdef from pg_indexes where schemaname = current_schema() "
                + "and indexname = 'idx_legacy_config_receipt_origin_latest'", String.class);

        assertThat(definition).doesNotContainIgnoringCase("unique")
                .contains("(origin_environment, origin_dossier, interface_code, id DESC)");
        assertThat(jdbc.queryForObject("select count(*) from pg_indexes where schemaname = current_schema() "
                + "and indexname = 'uk_legacy_config_receipt_open'", Long.class)).isEqualTo(1L);
    }

    /** Dezelfde herkomst mag dezelfde inhoud meermaals ontvangen (A -> B -> A); hoogstens een daarvan is open. */
    @Test
    void sameOriginAndContentHashMayExistMoreThanOnce() {
        String dossier = dossier();
        Map<String, Object> earlier = row(dossier, HASH_A);
        earlier.put("status", "SUPERSEDED");
        earlier.put("open_marker", null);
        insert(earlier);

        insert(row(dossier, HASH_A));

        assertThat(jdbc.queryForObject("select count(*) from legacy_config_receipt where origin_dossier = ? "
                + "and content_hash = ?", Long.class, dossier, HASH_A)).isEqualTo(2L);
    }

    @Test
    void atMostOneOpenReceiptPerOrigin() {
        String dossier = dossier();
        insert(row(dossier, HASH_A));

        assertThatThrownBy(() -> insert(row(dossier, HASH_B))).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_legacy_config_receipt_open");
        Map<String, Object> closed = row(dossier, HASH_B);
        closed.put("status", "SUPERSEDED");
        closed.put("open_marker", null);
        insert(closed);
    }

    @Test
    void theOpenMarkerFollowsTheStatus() {
        Map<String, Object> openButSuperseded = row(dossier(), HASH_A);
        openButSuperseded.put("status", "SUPERSEDED");
        rejects(openButSuperseded, "ck_legacy_config_receipt_open_marker");

        Map<String, Object> receivedWithoutMarker = row(dossier(), HASH_A);
        receivedWithoutMarker.put("open_marker", null);
        rejects(receivedWithoutMarker, "ck_legacy_config_receipt_open_marker");

        Map<String, Object> falseMarker = row(dossier(), HASH_A);
        falseMarker.put("open_marker", false);
        rejects(falseMarker, "ck_legacy_config_receipt_open_marker");
    }

    @Test
    void rejectsAnUnknownStatusABadHashOrABlankKey() {
        Map<String, Object> status = closedRow("PENDING");
        rejects(status, "ck_legacy_config_receipt_status");

        Map<String, Object> upper = row(dossier(), "A".repeat(64));
        rejects(upper, "ck_legacy_config_receipt_content_hash");

        Map<String, Object> shortDefinitionHash = row(dossier(), HASH_A);
        shortDefinitionHash.put("definition_content_hash", "abc");
        rejects(shortDefinitionHash, "ck_legacy_config_receipt_definition_hash");

        Map<String, Object> blank = row(dossier(), HASH_A);
        blank.put("interface_code", "   ");
        rejects(blank, "ck_legacy_config_receipt_keys_not_blank");

        Map<String, Object> version = row(dossier(), HASH_A);
        version.put("bundle_version", 0);
        rejects(version, "ck_legacy_config_receipt_bundle_version");
    }

    @Test
    void aSecretOutcomeOrCredentialOnlyExistsWhenASecretWasReceived() {
        Map<String, Object> outcomeWithoutSecret = row(dossier(), HASH_A);
        outcomeWithoutSecret.put("secret_outcome", "STORED");
        rejects(outcomeWithoutSecret, "ck_legacy_config_receipt_secret_outcome");

        Map<String, Object> secretWithoutOutcome = row(dossier(), HASH_A);
        secretWithoutOutcome.put("secret_received", true);
        rejects(secretWithoutOutcome, "ck_legacy_config_receipt_secret_outcome");

        Map<String, Object> unknownOutcome = row(dossier(), HASH_A);
        unknownOutcome.put("secret_received", true);
        unknownOutcome.put("secret_outcome", "LOGGED");
        rejects(unknownOutcome, "ck_legacy_config_receipt_secret_outcome");

        Map<String, Object> discarded = row(dossier(), HASH_A);
        discarded.put("secret_received", true);
        discarded.put("secret_outcome", "DISCARDED_NO_HOST");
        insert(discarded);
    }

    @Test
    void aCredentialReferenceNeedsAReceivedSecretAndAnExistingCredential() {
        Map<String, Object> credentialWithoutSecret = row(dossier(), HASH_A);
        credentialWithoutSecret.put("credential_id", 1L);
        assertThatThrownBy(() -> insert(credentialWithoutSecret)).isInstanceOf(DataIntegrityViolationException.class);

        Map<String, Object> unknownCredential = row(dossier(), HASH_A);
        unknownCredential.put("secret_received", true);
        unknownCredential.put("secret_outcome", "STORED");
        unknownCredential.put("credential_id", 999_999_999L);
        rejects(unknownCredential, "fk_legacy_config_receipt_credential");
    }

    @Test
    void statusSpecificAuditFieldsAreRequired() {
        rejects(closedRow("APPLIED"), "ck_legacy_config_receipt_applied");
        Map<String, Object> applied = closedRow("APPLIED");
        applied.put("applied_at", OffsetDateTime.now());
        applied.put("applied_by", "beheerder@example.test");
        insert(applied);

        Map<String, Object> discarded = closedRow("DISCARDED");
        discarded.put("discarded_at", OffsetDateTime.now());
        discarded.put("discarded_by", "beheerder@example.test");
        rejects(discarded, "ck_legacy_config_receipt_discarded");

        Map<String, Object> subjectWithoutName = closedRow("APPLIED");
        subjectWithoutName.put("applied_at", OffsetDateTime.now());
        subjectWithoutName.put("applied_by_subject", "sub");
        assertThatThrownBy(() -> insert(subjectWithoutName)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlyASupersededReceiptHasASuccessor() {
        String dossier = dossier();
        long successor = insert(row(dossier, HASH_A));

        Map<String, Object> receivedWithSuccessor = row(dossier(), HASH_B);
        receivedWithSuccessor.put("superseded_by_receipt_id", successor);
        rejects(receivedWithSuccessor, "ck_legacy_config_receipt_superseded");

        Map<String, Object> unknownSuccessor = closedRow("SUPERSEDED");
        unknownSuccessor.put("superseded_by_receipt_id", 999_999_999L);
        rejects(unknownSuccessor, "fk_legacy_config_receipt_superseded_by");

        // Vervangen zonder (nog) gekende opvolger mag: de opvolger wordt in dezelfde transactie na de insert gezet.
        insert(closedRow("SUPERSEDED"));
    }

    // --- Helpers ------------------------------------------------------------------------------------------

    private static String dossier() {
        return "SCH-" + UUID.randomUUID().toString().substring(0, 13);
    }

    private static LegacyConfigReceipt receipt(String dossier, String hash, Instant now) {
        return new LegacyConfigReceipt("TEST", dossier, "IFACE", 1, hash, null, null, "{\"secret\":null}", now, now,
                SERVICE, "sub-schema");
    }

    private Map<String, Object> closedRow(String status) {
        Map<String, Object> row = row(dossier(), HASH_A);
        row.put("status", status);
        row.put("open_marker", null);
        return row;
    }

    private static Map<String, Object> row(String dossier, String hash) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("origin_environment", "TEST");
        row.put("origin_dossier", dossier);
        row.put("interface_code", "IFACE");
        row.put("bundle_version", 1);
        row.put("content_hash", hash);
        row.put("bundle_json", "{\"secret\":null}");
        row.put("secret_received", false);
        row.put("status", "RECEIVED");
        row.put("open_marker", true);
        row.put("exported_at", OffsetDateTime.now());
        row.put("received_at", OffsetDateTime.now());
        row.put("received_by", SERVICE);
        return row;
    }

    private long insert(Map<String, Object> row) {
        String columns = String.join(", ", row.keySet());
        String marks = row.keySet().stream().map(c -> "?").collect(Collectors.joining(", "));
        return jdbc.queryForObject("insert into legacy_config_receipt (" + columns + ") values (" + marks
                + ") returning id", Long.class, row.values().toArray());
    }

    private void rejects(Map<String, Object> row, String constraint) {
        assertThatThrownBy(() -> insert(row)).as(constraint)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    private void assertColumn(String table, String column, String dataType, Integer length, String nullable) {
        Map<String, Object> row = jdbc.queryForMap("select data_type, character_maximum_length, is_nullable "
                + "from information_schema.columns where table_schema = current_schema() and table_name = ? "
                + "and column_name = ?", table, column);
        String qualified = table + "." + column;
        assertThat((String) row.get("data_type")).as(qualified).isEqualToIgnoringCase(dataType);
        if (length != null) {
            assertThat(((Number) row.get("character_maximum_length")).intValue()).as(qualified).isEqualTo(length);
        }
        assertThat((String) row.get("is_nullable")).as(qualified).isEqualTo(nullable);
    }
}
