package be.dda.catalogimport.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.dao.ExternalCredentialEventRepository;
import be.dda.catalogimport.dao.ExternalCredentialRepository;
import be.dda.catalogimport.dao.SecretKeyCheckDao;
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
 * Bouwstap K-2a ({@code docs/design/leveringsconfiguratie-design.md} par. 3.1 en 10,
 * {@code docs/design/credentials-sleutelbeheer-design.md} par. 5): bewijst dat changeset 013 migreert, dat
 * Hibernate {@link ExternalCredential} en {@link ExternalCredentialEvent} met {@code ddl-auto: validate} aanvaardt
 * (het booten van deze context ís dat bewijs) en dat elke databasecheck werkelijk weigert wat het ontwerp verbiedt.
 *
 * <h2>Business rule vs. implementatie</h2>
 * <ul>
 *   <li><b>Regel:</b> een actieve credential heeft altijd een versleutelde waarde, een ingetrokken nooit
 *       (crypto-shred, par. 5.4). <b>Implementatie:</b> {@code ck_external_credential_secret_present} en
 *       {@code ck_external_credential_key_id}.</li>
 *   <li><b>Regel:</b> intrekken draagt altijd een reden. <b>Implementatie:</b> {@code ck_external_credential_revoked}.</li>
 *   <li><b>Regel:</b> {@code UNDECRYPTABLE} is afgeleid, niet opgeslagen (A6). <b>Implementatie:</b>
 *       {@code ck_external_credential_status} laat enkel {@code ACTIVE}/{@code REVOKED} toe.</li>
 *   <li><b>Regel:</b> een credential is aan één host gebonden (L4a). <b>Implementatie:</b>
 *       {@code uk_external_credential_host_binding (id, secret_kind, bound_host)} als doel van de samengestelde
 *       FK van changeset 014.</li>
 *   <li><b>Regel:</b> het auditregister is append-only; een systeemactie draagt geen naam. <b>Implementatie:</b>
 *       {@code ck_external_credential_event_source} (patroon 012).</li>
 * </ul>
 * Elke rij krijgt een verse {@code credential_ref}: het {@code local}-profiel draait tegen een blijvende
 * PostgreSQL (zie {@code scripts/test/run-full-tests.ps1}).
 */
@SpringBootTest
@ActiveProfiles("local")
class ExternalCredentialSchemaTest {

    private static final String USER = "beheerder@example.test";
    private static final String SUBJECT = "keycloak-sub-k2a";
    private static final String KEY_ID = "schema-test";
    /** Geen echte ciphertext: de checks kijken enkel of de kolom gevuld is. */
    private static final String CIPHERTEXT = "v1:" + KEY_ID + ":AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    @Autowired
    private ExternalCredentialRepository credentials;
    @Autowired
    private ExternalCredentialEventRepository events;
    @Autowired
    private SecretKeyCheckDao keyChecks;
    @Autowired
    private JdbcTemplate jdbc;

    // --- Vorm van het schema ----------------------------------------------------------------------------

    @Test
    void createsTheThreeTablesWithTheDocumentedColumnTypes() {
        assertColumn("external_credential", "credential_ref", "uuid", null, "NO");
        assertColumn("external_credential", "label", "character varying", 200, "NO");
        assertColumn("external_credential", "secret_kind", "character varying", 40, "NO");
        assertColumn("external_credential", "bound_host", "character varying", 255, "NO");
        assertColumn("external_credential", "ciphertext", "text", null, "YES");
        assertColumn("external_credential", "encryption_key_id", "character varying", 32, "YES");
        assertColumn("external_credential", "status", "character varying", 20, "NO");
        assertColumn("external_credential", "secret_updated_at", "timestamp with time zone", null, "YES");
        assertColumn("external_credential", "secret_updated_by", "character varying", 100, "YES");
        assertColumn("external_credential", "secret_updated_by_subject", "character varying", 255, "YES");
        assertColumn("external_credential", "created_at", "timestamp with time zone", null, "NO");
        assertColumn("external_credential", "created_by", "character varying", 100, "NO");
        assertColumn("external_credential", "created_by_subject", "character varying", 255, "YES");
        assertColumn("external_credential", "revoked_at", "timestamp with time zone", null, "YES");
        assertColumn("external_credential", "revoked_by", "character varying", 100, "YES");
        assertColumn("external_credential", "revoked_by_subject", "character varying", 255, "YES");
        assertColumn("external_credential", "revoked_reason", "character varying", 500, "YES");

        assertColumn("external_credential_event", "credential_id", "bigint", null, "NO");
        assertColumn("external_credential_event", "event_kind", "character varying", 30, "NO");
        assertColumn("external_credential_event", "reason", "character varying", 500, "NO");
        assertColumn("external_credential_event", "source", "character varying", 20, "NO");
        assertColumn("external_credential_event", "changed_by", "character varying", 100, "YES");
        assertColumn("external_credential_event", "changed_by_subject", "character varying", 255, "YES");
        assertColumn("external_credential_event", "changed_at", "timestamp with time zone", null, "NO");
        assertColumn("external_credential_event", "previous_key_id", "character varying", 32, "YES");
        assertColumn("external_credential_event", "new_key_id", "character varying", 32, "YES");

        assertColumn("secret_key_check", "key_id", "character varying", 32, "NO");
        assertColumn("secret_key_check", "check_value", "text", null, "NO");
        assertColumn("secret_key_check", "created_at", "timestamp with time zone", null, "NO");
    }

    /** Geen kolom mag ooit een secret in zuivere tekst of een hash ervan kunnen bevatten (par. 2.5). */
    @Test
    void hasNoColumnForAPlaintextSecretOrAHashOfIt() {
        List<String> columns = jdbc.queryForList("select column_name from information_schema.columns "
                + "where table_schema = current_schema() and table_name in "
                + "('external_credential', 'external_credential_event', 'secret_key_check')", String.class);

        assertThat(columns).isNotEmpty()
                .noneMatch(name -> name.contains("plain") || name.contains("hash") || name.equals("secret")
                        || name.equals("password"));
    }

    @Test
    void declaresEveryDocumentedConstraint() {
        for (String name : List.of("pk_external_credential", "uk_external_credential_ref",
                "uk_external_credential_host_binding", "ck_external_credential_secret_kind",
                "ck_external_credential_status", "ck_external_credential_secret_present",
                "ck_external_credential_key_id", "ck_external_credential_revoked",
                "ck_external_credential_secret_updated_subject", "ck_external_credential_revoked_subject",
                "ck_external_credential_bound_host_normalized", "pk_external_credential_event", "fk_external_credential_event_credential",
                "ck_external_credential_event_kind", "ck_external_credential_event_source",
                "ck_external_credential_event_subject", "ck_external_credential_event_created",
                "ck_external_credential_event_reencrypted", "ck_external_credential_event_revoked",
                "pk_secret_key_check")) {
            assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints "
                    + "where constraint_schema = current_schema() and constraint_name = ?", Long.class, name))
                    .as(name).isEqualTo(1L);
        }
    }

    /**
     * L4a: het doel van de samengestelde FK vanuit {@code connection_profile_version} (changeset 014). Omdat
     * {@code id} al uniek is, kan geen insert deze sleutel doen botsen; bewezen wordt dus haar bestaan en
     * kolomvolgorde, zodat een FK {@code (credential_id, credential_secret_kind, credential_host)} ernaar kan wijzen.
     */
    @Test
    void theHostBindingKeyIsAUniqueKeyOnIdSecretKindAndBoundHostInThatOrder() {
        assertThat(jdbc.queryForObject("select constraint_type from information_schema.table_constraints "
                + "where constraint_schema = current_schema() and constraint_name = ?", String.class,
                "uk_external_credential_host_binding")).isEqualTo("UNIQUE");
        assertThat(jdbc.queryForList("select column_name from information_schema.key_column_usage "
                        + "where constraint_schema = current_schema() and constraint_name = ? "
                        + "order by ordinal_position", String.class, "uk_external_credential_host_binding"))
                .containsExactly("id", "secret_kind", "bound_host");
    }

    // --- external_credential: mapping ---------------------------------------------------------------------

    @Test
    void persistsAnActiveCredentialWithTheDocumentedMapping() {
        UUID ref = UUID.randomUUID();
        Instant now = Instant.now();

        ExternalCredential saved = credentials.saveAndFlush(new ExternalCredential(ref, "Leverancier X SFTP",
                ExternalCredentialSecretKind.SFTP_PASSWORD, "sftp.example.test", CIPHERTEXT, KEY_ID, USER,
                SUBJECT, now));

        ExternalCredential found = credentials.findByCredentialRef(ref).orElseThrow();
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getCredentialRef()).isEqualTo(ref);
        assertThat(found.getLabel()).isEqualTo("Leverancier X SFTP");
        assertThat(found.getSecretKind()).isEqualTo(ExternalCredentialSecretKind.SFTP_PASSWORD);
        assertThat(found.getBoundHost()).isEqualTo("sftp.example.test");
        assertThat(found.getStatus()).isEqualTo(ExternalCredentialStatus.ACTIVE);
        assertThat(found.isSecretSet()).isTrue();
        assertThat(found.getCiphertext()).isEqualTo(CIPHERTEXT);
        assertThat(found.getEncryptionKeyId()).isEqualTo(KEY_ID);
        assertThat(found.getCreatedBy()).isEqualTo(USER);
        assertThat(found.getCreatedBySubject()).isEqualTo(SUBJECT);
        // Het eerste instellen telt als "laatst ingesteld" (secretUpdatedAt/-By uit V1).
        assertThat(found.getSecretUpdatedBy()).isEqualTo(USER);
        assertThat(found.getSecretUpdatedBySubject()).isEqualTo(SUBJECT);
        assertThat(found.getSecretUpdatedAt()).isNotNull();
        assertThat(found.getRevokedAt()).isNull();
        assertThat(found.getRevokedReason()).isNull();
        assertThat(found.toString()).doesNotContain(CIPHERTEXT).contains("secretSet=true");
        assertThat(credentials.findDistinctEncryptionKeyIds()).contains(KEY_ID);
    }

    @Test
    void theConstructorRefusesMissingDataWithoutEchoingAValue() {
        UUID ref = UUID.randomUUID();
        Instant now = Instant.now();

        assertThatThrownBy(() -> new ExternalCredential(ref, "label", ExternalCredentialSecretKind.SFTP_PASSWORD,
                "host", null, KEY_ID, USER, null, now)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ciphertext is required");
        assertThatThrownBy(() -> new ExternalCredential(ref, "label", ExternalCredentialSecretKind.SFTP_PASSWORD,
                "host", CIPHERTEXT, " ", USER, null, now)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("encryptionKeyId is required");
        assertThatThrownBy(() -> new ExternalCredential(null, "label", ExternalCredentialSecretKind.SFTP_PASSWORD,
                "host", CIPHERTEXT, KEY_ID, USER, null, now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExternalCredential(ref, "label", null, "host", CIPHERTEXT, KEY_ID, USER,
                null, now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExternalCredential(ref, "label", ExternalCredentialSecretKind.SFTP_PASSWORD,
                "", CIPHERTEXT, KEY_ID, USER, null, now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExternalCredential(ref, "label", ExternalCredentialSecretKind.SFTP_PASSWORD,
                "host", CIPHERTEXT, KEY_ID, null, null, now)).isInstanceOf(IllegalArgumentException.class);
    }

    /** {@code uk_external_credential_ref}: dezelfde referentie tweemaal kan niet, ook niet via JPA. */
    @Test
    void rejectsADuplicateCredentialRef() {
        UUID ref = UUID.randomUUID();
        credentials.saveAndFlush(newCredential(ref));

        assertThatThrownBy(() -> credentials.saveAndFlush(newCredential(ref)))
                .isInstanceOf(DataIntegrityViolationException.class);
        Map<String, Object> duplicate = activeRow();
        duplicate.put("credential_ref", ref);
        assertThatThrownBy(() -> insertCredential(duplicate)).isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- external_credential: checks ----------------------------------------------------------------------

    @Test
    void rejectsAnActiveCredentialWithoutCiphertext() {
        Map<String, Object> row = activeRow();
        row.put("ciphertext", null);
        row.put("encryption_key_id", null);

        assertThatThrownBy(() -> insertCredential(row)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsACiphertextWithoutKeyIdAndAKeyIdWithoutCiphertext() {
        Map<String, Object> withoutKeyId = activeRow();
        withoutKeyId.put("encryption_key_id", null);
        assertThatThrownBy(() -> insertCredential(withoutKeyId)).isInstanceOf(DataIntegrityViolationException.class);

        Map<String, Object> keyIdWithoutCiphertext = revokedRow();
        keyIdWithoutCiphertext.put("encryption_key_id", KEY_ID);
        assertThatThrownBy(() -> insertCredential(keyIdWithoutCiphertext))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Crypto-shred (par. 5.4): een ingetrokken credential mag geen versleutelde waarde meer dragen. */
    @Test
    void rejectsARevokedCredentialThatStillCarriesACiphertext() {
        Map<String, Object> row = revokedRow();
        row.put("ciphertext", CIPHERTEXT);
        row.put("encryption_key_id", KEY_ID);

        assertThatThrownBy(() -> insertCredential(row)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void requiresAReasonForARevokedCredentialAndReadsAValidOneBack() {
        Map<String, Object> withoutReason = revokedRow();
        withoutReason.put("revoked_reason", null);
        assertThatThrownBy(() -> insertCredential(withoutReason)).isInstanceOf(DataIntegrityViolationException.class);

        Map<String, Object> valid = revokedRow();
        long id = insertCredential(valid);

        ExternalCredential found = credentials.findById(id).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(ExternalCredentialStatus.REVOKED);
        assertThat(found.getCiphertext()).isNull();
        assertThat(found.getEncryptionKeyId()).isNull();
        assertThat(found.isSecretSet()).isFalse();
        assertThat(found.getRevokedReason()).isEqualTo("Leverancier gestopt");
        assertThat(found.toString()).contains("secretSet=false");
    }

    /** A5: {@code SSH_PRIVATE_KEY} is gereserveerd maar toegelaten; elke andere soort niet. */
    @Test
    void allowsOnlyTheDocumentedSecretKinds() {
        for (ExternalCredentialSecretKind kind : ExternalCredentialSecretKind.values()) {
            Map<String, Object> row = activeRow();
            row.put("secret_kind", kind.name());
            assertThatCode(() -> insertCredential(row)).as(kind.name()).doesNotThrowAnyException();
        }
        Map<String, Object> apiKey = activeRow();
        apiKey.put("secret_kind", "API_KEY");
        assertThatThrownBy(() -> insertCredential(apiKey)).isInstanceOf(DataIntegrityViolationException.class);
    }

    /** A6: {@code UNDECRYPTABLE} is afgeleid en kan dus nooit opgeslagen worden. */
    @Test
    void allowsOnlyActiveAndRevokedAsStoredStatus() {
        Map<String, Object> undecryptable = activeRow();
        undecryptable.put("status", "UNDECRYPTABLE");

        assertThatThrownBy(() -> insertCredential(undecryptable)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(ExternalCredentialStatus.values()).extracting(Enum::name).containsExactly("ACTIVE", "REVOKED");
    }

    /** Changeset 013-4: bound_host moet genormaliseerd zijn (kleine letters, geen punt achteraan, geen witruimte). */
    @Test
    void refusesANonNormalizedBoundHostInTheDatabaseAndInTheConstructor() {
        for (String host : List.of("SFTP.Example.test", "sftp.example.test.", "sftp .example.test",
                " sftp.example.test", "sftp.example.test ", "sftp.example\ttest", "", "   ")) {
            Map<String, Object> row = activeRow();
            row.put("bound_host", host);
            assertThatThrownBy(() -> insertCredential(row)).as("db: '" + host + "'")
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        Map<String, Object> normalized = activeRow();
        normalized.put("bound_host", "sftp-1.example.test");
        assertThatCode(() -> insertCredential(normalized)).doesNotThrowAnyException();

        UUID ref = UUID.randomUUID();
        Instant now = Instant.now();
        for (String host : List.of("SFTP.Example.test", "sftp.example.test.", "sftp example.test", " sftp.example.test")) {
            assertThatThrownBy(() -> new ExternalCredential(ref, "label", ExternalCredentialSecretKind.SFTP_PASSWORD,
                    host, CIPHERTEXT, KEY_ID, USER, null, now)).as("entity: '" + host + "'")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("boundHost must be normalized")
                    .hasMessageNotContaining(CIPHERTEXT);
        }
        assertThatCode(() -> new ExternalCredential(ref, "label", ExternalCredentialSecretKind.SFTP_PASSWORD,
                "sftp.example.test", CIPHERTEXT, KEY_ID, USER, null, now)).doesNotThrowAnyException();
    }

    @Test
    void refusesASubjectWithoutANameAndACredentialWithoutCreator() {
        Map<String, Object> secretSubject = activeRow();
        secretSubject.put("secret_updated_by", null);
        secretSubject.put("secret_updated_by_subject", SUBJECT);
        assertThatThrownBy(() -> insertCredential(secretSubject)).isInstanceOf(DataIntegrityViolationException.class);

        Map<String, Object> revokedSubject = revokedRow();
        revokedSubject.put("revoked_by", null);
        revokedSubject.put("revoked_by_subject", SUBJECT);
        assertThatThrownBy(() -> insertCredential(revokedSubject)).isInstanceOf(DataIntegrityViolationException.class);

        Map<String, Object> noCreator = activeRow();
        noCreator.put("created_by", null);
        assertThatThrownBy(() -> insertCredential(noCreator)).isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- external_credential_event: append-only auditregister ----------------------------------------------

    @Test
    void persistsTheHistoryOfACredentialInInsertionOrder() {
        ExternalCredential credential = credentials.saveAndFlush(newCredential(UUID.randomUUID()));

        ExternalCredentialEvent created = events.saveAndFlush(new ExternalCredentialEvent(credential,
                ExternalCredentialEventKind.CREATED, "Nieuwe leverancier", ExternalCredentialEventSource.HUMAN,
                USER, SUBJECT, Instant.now(), null, KEY_ID));
        events.saveAndFlush(new ExternalCredentialEvent(credential, ExternalCredentialEventKind.REENCRYPTED,
                "Sleutelrotatie", ExternalCredentialEventSource.SYSTEM, null, null, Instant.now(), KEY_ID,
                "schema-test-2"));

        List<ExternalCredentialEvent> history = events.findByCredentialIdOrderByIdAsc(credential.getId());
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getId()).isEqualTo(created.getId());
        assertThat(history.get(0).getEventKind()).isEqualTo(ExternalCredentialEventKind.CREATED);
        assertThat(history.get(0).getChangedBy()).isEqualTo(USER);
        assertThat(history.get(0).getPreviousKeyId()).isNull();
        assertThat(history.get(0).getNewKeyId()).isEqualTo(KEY_ID);
        assertThat(history.get(1).getSource()).isEqualTo(ExternalCredentialEventSource.SYSTEM);
        assertThat(history.get(1).getChangedBy()).isNull();
        assertThat(history.get(1).getPreviousKeyId()).isEqualTo(KEY_ID);
        assertThat(history.get(1).getNewKeyId()).isEqualTo("schema-test-2");
    }

    /** {@code ck_external_credential_event_source}: HUMAN draagt altijd een naam, SYSTEM nooit (patroon 012). */
    @Test
    void enforcesTheSourceRulesForWhoActed() {
        long credentialId = insertCredential(activeRow());

        assertThatThrownBy(() -> insertEvent(credentialId, "REPLACED", "HUMAN", null, null, KEY_ID, KEY_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertEvent(credentialId, "REENCRYPTED", "SYSTEM", "system", null, KEY_ID, "k2"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertEvent(credentialId, "REENCRYPTED", "SYSTEM", null, SUBJECT, KEY_ID, "k2"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertEvent(credentialId, "REPLACED", "SCHEDULER", USER, null, KEY_ID, KEY_ID))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatCode(() -> insertEvent(credentialId, "REPLACED", "HUMAN", USER, SUBJECT, KEY_ID, KEY_ID))
                .doesNotThrowAnyException();
        assertThatCode(() -> insertEvent(credentialId, "REENCRYPTED", "SYSTEM", null, null, KEY_ID, "k2"))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsOnlyTheDocumentedEventKindsAndAlwaysRequiresAReason() {
        long credentialId = insertCredential(activeRow());

        assertThatThrownBy(() -> insertEvent(credentialId, "UNDECRYPTABLE", "HUMAN", USER, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into external_credential_event (credential_id, event_kind, "
                        + "reason, source, changed_by, changed_at) values (?, 'REPLACED', null, 'HUMAN', ?, ?)",
                credentialId, USER, OffsetDateTime.now()))
                .isInstanceOf(DataIntegrityViolationException.class);
        for (String kind : List.of("CREATED", "REPLACED", "REVOKED", "REENCRYPTED")) {
            String previous = kind.equals("CREATED") ? null : KEY_ID;
            String next = kind.equals("REVOKED") ? null : KEY_ID;
            assertThatCode(() -> insertEvent(credentialId, kind, "HUMAN", USER, null, previous, next))
                    .as(kind).doesNotThrowAnyException();
        }
    }

    /** De sleutel-ID's per soort: CREATED zonder vorige, REENCRYPTED met beide, REVOKED zonder nieuwe. */
    @Test
    void enforcesTheKeyIdsThatBelongToEachEventKind() {
        long credentialId = insertCredential(activeRow());

        assertThatThrownBy(() -> insertEvent(credentialId, "CREATED", "HUMAN", USER, null, KEY_ID, KEY_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertEvent(credentialId, "REENCRYPTED", "SYSTEM", null, null, null, KEY_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertEvent(credentialId, "REENCRYPTED", "SYSTEM", null, null, KEY_ID, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertEvent(credentialId, "REVOKED", "HUMAN", USER, null, KEY_ID, KEY_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refusesAnEventForAnUnknownCredential() {
        assertThatThrownBy(() -> insertEvent(999_999_999L, "CREATED", "HUMAN", USER, null, null, KEY_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- secret_key_check ---------------------------------------------------------------------------------

    @Test
    void secretKeyCheckHasOneRowPerKeyIdAndNeverOverwritesIt() {
        String keyId = "schema-" + UUID.randomUUID().toString().substring(0, 8);

        assertThat(keyChecks.findCheckValue(keyId)).isEmpty();
        assertThat(keyChecks.insertIfAbsent(keyId, "v1:" + keyId + ":EERSTE", Instant.now())).isTrue();
        assertThat(keyChecks.insertIfAbsent(keyId, "v1:" + keyId + ":TWEEDE", Instant.now())).isFalse();
        assertThat(keyChecks.findCheckValue(keyId)).contains("v1:" + keyId + ":EERSTE");

        assertThatThrownBy(() -> jdbc.update("insert into secret_key_check (key_id, check_value, created_at) "
                + "values (?, ?, ?)", keyId, "v1:x:y", OffsetDateTime.now()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into secret_key_check (key_id, check_value, created_at) "
                + "values (?, null, ?)", keyId + "n", OffsetDateTime.now()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- Helpers ------------------------------------------------------------------------------------------

    private ExternalCredential newCredential(UUID ref) {
        return new ExternalCredential(ref, "Testcredential", ExternalCredentialSecretKind.SFTP_PASSWORD,
                "sftp.example.test", CIPHERTEXT, KEY_ID, USER, null, Instant.now());
    }

    private static Map<String, Object> activeRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("credential_ref", UUID.randomUUID());
        row.put("label", "Testcredential");
        row.put("secret_kind", "SFTP_PASSWORD");
        row.put("bound_host", "sftp.example.test");
        row.put("ciphertext", CIPHERTEXT);
        row.put("encryption_key_id", KEY_ID);
        row.put("status", "ACTIVE");
        row.put("secret_updated_at", OffsetDateTime.now());
        row.put("secret_updated_by", USER);
        row.put("created_at", OffsetDateTime.now());
        row.put("created_by", USER);
        return row;
    }

    private static Map<String, Object> revokedRow() {
        Map<String, Object> row = activeRow();
        row.put("ciphertext", null);
        row.put("encryption_key_id", null);
        row.put("status", "REVOKED");
        row.put("revoked_at", OffsetDateTime.now());
        row.put("revoked_by", USER);
        row.put("revoked_reason", "Leverancier gestopt");
        return row;
    }

    private long insertCredential(Map<String, Object> row) {
        String columns = String.join(", ", row.keySet());
        String marks = row.keySet().stream().map(column -> "?").collect(Collectors.joining(", "));
        Long id = jdbc.queryForObject("insert into external_credential (" + columns + ") values (" + marks
                + ") returning id", Long.class, row.values().toArray());
        return id == null ? -1L : id;
    }

    private void insertEvent(long credentialId, String kind, String source, String changedBy, String subject,
                             String previousKeyId, String newKeyId) {
        jdbc.update("insert into external_credential_event (credential_id, event_kind, reason, source, "
                        + "changed_by, changed_by_subject, changed_at, previous_key_id, new_key_id) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                credentialId, kind, "reden " + kind, source, changedBy, subject, OffsetDateTime.now(),
                previousKeyId, newKeyId);
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
        assertThat((String) row.get("is_nullable")).as(qualified).isEqualToIgnoringCase(nullable);
    }
}
