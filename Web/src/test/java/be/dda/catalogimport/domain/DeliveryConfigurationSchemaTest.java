package be.dda.catalogimport.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import be.dda.catalogimport.dao.AcquisitionConfigEventRepository;
import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.ConnectionProfileRepository;
import be.dda.catalogimport.dao.ConnectionProfileVersionRepository;
import be.dda.catalogimport.dao.DeliveryConfigurationFileConditionRepository;
import be.dda.catalogimport.dao.DeliveryConfigurationRepository;
import be.dda.catalogimport.dao.DeliveryConfigurationVersionRepository;
import be.dda.catalogimport.dao.ExternalCredentialRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Bouwstap LC-1 ({@code docs/design/leveringsconfiguratie-design.md} par. 3.2 en 10): bewijst dat changeset 014
 * migreert, dat Hibernate de nieuwe entiteiten en de uitgebreide {@link CatalogImportTask} met
 * {@code ddl-auto: validate} aanvaardt (het booten van deze context ís dat bewijs) en dat elke databasecheck
 * werkelijk weigert wat het ontwerp verbiedt. Een weigering wordt telkens op de <b>naam van de constraint</b>
 * gecontroleerd, zodat een test niet per ongeluk op een andere fout slaagt.
 *
 * <h2>Business rule vs. implementatie</h2>
 * <ul>
 *   <li><b>Regel:</b> een profielversie met een eigen host en de credential van een andere host bestaat niet (L4a).
 *       <b>Implementatie:</b> samengestelde FK {@code (credential_id, credential_secret_kind, credential_host)} naar
 *       {@code external_credential (id, secret_kind, bound_host)} plus {@code credential_host = host}.</li>
 *   <li><b>Regel:</b> de authenticatiemethode past bij de soort credential. <b>Implementatie:</b> twee
 *       {@code auth_method}-checks.</li>
 *   <li><b>Regel:</b> na het ophalen blijft het bronbestand staan (A3). <b>Implementatie:</b>
 *       {@code post_fetch_action in ('LEAVE')}.</li>
 *   <li><b>Regel:</b> versies zijn onveranderlijk (L3). <b>Implementatie:</b> geen setters, {@code updatable = false}
 *       op elke kolom (geen databasetrigger: het project kent er geen).</li>
 *   <li><b>Regel:</b> het auditregister is append-only; een systeemactie draagt geen naam. <b>Implementatie:</b>
 *       {@code ck_acquisition_config_event_source} (patroon 012/013).</li>
 * </ul>
 * Codes en referenties zijn per run uniek: het {@code local}-profiel draait tegen een blijvende PostgreSQL (zie
 * {@code scripts/test/run-full-tests.ps1}).
 */
@SpringBootTest
@ActiveProfiles("local")
class DeliveryConfigurationSchemaTest {

    private static final String USER = "beheerder@example.test";
    private static final String SUBJECT = "keycloak-sub-lc1";
    private static final String HOST = "sftp.example.test";
    private static final String OTHER_HOST = "other.example.test";
    private static final String HASH = "a".repeat(64);
    private static final String CIPHERTEXT = "v1:lc1-test:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String FINGERPRINT = "SHA256:abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";

    @Autowired
    private ExternalCredentialRepository credentials;
    @Autowired
    private ConnectionProfileRepository profiles;
    @Autowired
    private ConnectionProfileVersionRepository profileVersions;
    @Autowired
    private DeliveryConfigurationRepository configurations;
    @Autowired
    private DeliveryConfigurationVersionRepository configurationVersions;
    @Autowired
    private DeliveryConfigurationFileConditionRepository conditions;
    @Autowired
    private AcquisitionConfigEventRepository events;
    @Autowired
    private CatalogImportTaskRepository tasks;
    @Autowired
    private SourceOrganisationRepository sourceOrganisations;
    @Autowired
    private ImportDefinitionRepository definitions;
    @Autowired
    private ImportLinkRepository links;
    @Autowired
    private JdbcTemplate jdbc;

    // --- Vorm van het schema ----------------------------------------------------------------------------

    @Test
    void createsTheSixTablesWithTheDocumentedColumnTypes() {
        assertColumn("connection_profile", "code", "character varying", 50, "NO");
        assertColumn("connection_profile", "name", "character varying", 200, "NO");
        assertColumn("connection_profile", "protocol", "character varying", 20, "NO");
        assertColumn("connection_profile", "active", "boolean", null, "NO");
        assertColumn("connection_profile", "created_at", "timestamp with time zone", null, "NO");
        assertColumn("connection_profile", "created_by", "character varying", 100, "NO");
        assertColumn("connection_profile", "created_by_subject", "character varying", 255, "YES");
        assertColumn("connection_profile", "updated_at", "timestamp with time zone", null, "NO");

        assertColumn("connection_profile_version", "connection_profile_id", "bigint", null, "NO");
        assertColumn("connection_profile_version", "version_number", "integer", null, "NO");
        assertColumn("connection_profile_version", "host", "character varying", 255, "NO");
        assertColumn("connection_profile_version", "port", "integer", null, "NO");
        assertColumn("connection_profile_version", "username", "character varying", 200, "NO");
        assertColumn("connection_profile_version", "auth_method", "character varying", 20, "NO");
        assertColumn("connection_profile_version", "credential_id", "bigint", null, "NO");
        assertColumn("connection_profile_version", "credential_secret_kind", "character varying", 40, "NO");
        assertColumn("connection_profile_version", "credential_host", "character varying", 255, "NO");
        assertColumn("connection_profile_version", "host_key_algorithm", "character varying", 40, "NO");
        assertColumn("connection_profile_version", "host_key_fingerprint_sha256", "character varying", 100, "NO");
        assertColumn("connection_profile_version", "based_on_version_id", "bigint", null, "YES");
        assertColumn("connection_profile_version", "change_reason", "character varying", 500, "YES");
        assertColumn("connection_profile_version", "config_hash", "character varying", 64, "NO");
        assertColumn("connection_profile_version", "created_at", "timestamp with time zone", null, "NO");
        assertColumn("connection_profile_version", "created_by", "character varying", 100, "NO");
        assertColumn("connection_profile_version", "created_by_subject", "character varying", 255, "YES");

        assertColumn("delivery_configuration", "code", "character varying", 50, "NO");
        assertColumn("delivery_configuration", "name", "character varying", 200, "NO");
        assertColumn("delivery_configuration", "acquisition_kind", "character varying", 20, "NO");
        assertColumn("delivery_configuration", "active", "boolean", null, "NO");
        assertColumn("delivery_configuration", "created_by_subject", "character varying", 255, "YES");
        assertColumn("delivery_configuration", "updated_at", "timestamp with time zone", null, "NO");

        assertColumn("delivery_configuration_version", "delivery_configuration_id", "bigint", null, "NO");
        assertColumn("delivery_configuration_version", "version_number", "integer", null, "NO");
        assertColumn("delivery_configuration_version", "connection_profile_version_id", "bigint", null, "NO");
        assertColumn("delivery_configuration_version", "remote_directory", "character varying", 500, "NO");
        assertColumn("delivery_configuration_version", "selection_mode", "character varying", 20, "NO");
        assertColumn("delivery_configuration_version", "min_file_age_seconds", "integer", null, "NO");
        assertColumn("delivery_configuration_version", "max_file_bytes", "bigint", null, "NO");
        assertColumn("delivery_configuration_version", "post_fetch_action", "character varying", 20, "NO");
        assertColumn("delivery_configuration_version", "based_on_version_id", "bigint", null, "YES");
        assertColumn("delivery_configuration_version", "change_reason", "character varying", 500, "YES");
        assertColumn("delivery_configuration_version", "config_hash", "character varying", 64, "NO");
        assertColumn("delivery_configuration_version", "created_by_subject", "character varying", 255, "YES");

        assertColumn("delivery_configuration_file_condition", "dc_version_id", "bigint", null, "NO");
        assertColumn("delivery_configuration_file_condition", "group_number", "integer", null, "NO");
        assertColumn("delivery_configuration_file_condition", "sequence_number", "integer", null, "NO");
        assertColumn("delivery_configuration_file_condition", "condition_kind", "character varying", 30, "NO");
        assertColumn("delivery_configuration_file_condition", "compare_value", "character varying", 200, "NO");
        assertColumn("delivery_configuration_file_condition", "case_sensitive", "boolean", null, "NO");
        assertColumn("delivery_configuration_file_condition", "bookmark_name", "character varying", 100, "YES");

        assertColumn("acquisition_config_event", "event_kind", "character varying", 30, "NO");
        assertColumn("acquisition_config_event", "task_id", "bigint", null, "YES");
        assertColumn("acquisition_config_event", "dc_version_id", "bigint", null, "YES");
        assertColumn("acquisition_config_event", "profile_version_id", "bigint", null, "YES");
        assertColumn("acquisition_config_event", "outcome_code", "character varying", 60, "YES");
        assertColumn("acquisition_config_event", "detail", "character varying", 500, "YES");
        assertColumn("acquisition_config_event", "reason", "character varying", 500, "YES");
        assertColumn("acquisition_config_event", "source", "character varying", 20, "NO");
        assertColumn("acquisition_config_event", "changed_by", "character varying", 100, "YES");
        assertColumn("acquisition_config_event", "changed_by_subject", "character varying", 255, "YES");
        assertColumn("acquisition_config_event", "changed_at", "timestamp with time zone", null, "NO");

        assertColumn("catalog_import_task", "delivery_configuration_version_id", "bigint", null, "YES");
    }

    @Test
    void declaresEveryDocumentedConstraintAndIndex() {
        for (String name : List.of(
                "pk_connection_profile", "uk_connection_profile_code", "ck_connection_profile_protocol",
                "ck_connection_profile_created_subject",
                "pk_connection_profile_version", "uk_connection_profile_version_number",
                "fk_connection_profile_version_profile", "fk_connection_profile_version_credential",
                "fk_connection_profile_version_based_on", "ck_connection_profile_version_number",
                "ck_connection_profile_version_port", "ck_connection_profile_version_auth_method",
                "ck_connection_profile_version_credential_host", "ck_connection_profile_version_auth_password",
                "ck_connection_profile_version_auth_key", "ck_connection_profile_version_host_normalized",
                "ck_connection_profile_version_username", "ck_connection_profile_version_host_key",
                "ck_connection_profile_version_created_subject",
                "pk_delivery_configuration", "uk_delivery_configuration_code",
                "ck_delivery_configuration_acquisition_kind", "ck_delivery_configuration_created_subject",
                "pk_delivery_configuration_version", "uk_delivery_configuration_version_number",
                "fk_delivery_configuration_version_configuration",
                "fk_delivery_configuration_version_profile_version", "fk_delivery_configuration_version_based_on",
                "ck_delivery_configuration_version_number", "ck_delivery_configuration_version_selection_mode",
                "ck_delivery_configuration_version_min_age", "ck_delivery_configuration_version_max_bytes",
                "ck_delivery_configuration_version_post_fetch", "ck_delivery_configuration_version_directory",
                "ck_delivery_configuration_version_created_subject",
                "pk_delivery_configuration_file_condition", "uk_delivery_configuration_file_condition_order",
                "fk_delivery_configuration_file_condition_version", "ck_delivery_configuration_file_condition_kind",
                "ck_delivery_configuration_file_condition_numbers",
                "ck_delivery_configuration_file_condition_value",
                "pk_acquisition_config_event", "fk_acquisition_config_event_task",
                "fk_acquisition_config_event_dc_version", "fk_acquisition_config_event_profile_version",
                "ck_acquisition_config_event_kind", "ck_acquisition_config_event_source",
                "ck_acquisition_config_event_subject", "fk_catalog_import_task_dc_version")) {
            assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints "
                    + "where constraint_schema = current_schema() and constraint_name = ?", Long.class, name))
                    .as(name).isEqualTo(1L);
        }
        for (String index : List.of("idx_connection_profile_version_credential",
                "idx_connection_profile_version_based_on", "idx_delivery_configuration_version_profile_version",
                "idx_delivery_configuration_version_based_on", "idx_acquisition_config_event_task",
                "idx_acquisition_config_event_dc_version", "idx_acquisition_config_event_profile_version",
                "idx_catalog_import_task_dc_version")) {
            assertThat(jdbc.queryForObject("select count(*) from pg_indexes where schemaname = current_schema() "
                    + "and indexname = ?", Long.class, index)).as(index).isEqualTo(1L);
        }
    }

    /** L4a: de FK is samengesteld en verwijst naar de unieke sleutel van 013-1, in die kolomvolgorde. */
    @Test
    void theCredentialForeignKeyIsCompositeAndPointsAtTheHostBindingKey() {
        assertThat(jdbc.queryForList("select column_name from information_schema.key_column_usage "
                        + "where constraint_schema = current_schema() and constraint_name = ? "
                        + "order by ordinal_position", String.class, "fk_connection_profile_version_credential"))
                .containsExactly("credential_id", "credential_secret_kind", "credential_host");
        assertThat(jdbc.queryForObject("select unique_constraint_name from information_schema.referential_constraints "
                + "where constraint_schema = current_schema() and constraint_name = ?", String.class,
                "fk_connection_profile_version_credential")).isEqualTo("uk_external_credential_host_binding");
    }

    // --- connection_profile ------------------------------------------------------------------------------

    @Test
    void rejectsADuplicateProfileCodeAndAnUnknownProtocol() {
        String code = unique("P");
        insertProfile(code);

        rejects("uk_connection_profile_code", () -> insertProfile(code));
        Map<String, Object> ftp = headRow(unique("P"));
        ftp.put("protocol", "FTP");
        rejects("ck_connection_profile_protocol", () -> insertInto("connection_profile", ftp));
    }

    // --- connection_profile_version: checks ---------------------------------------------------------------

    @Test
    void rejectsACredentialHostThatDiffersFromTheProfileHost() {
        long profileId = insertProfile(unique("P"));
        long credentialId = insertCredential(OTHER_HOST, "SFTP_PASSWORD");

        Map<String, Object> row = profileVersionRow(profileId, credentialId, "SFTP_PASSWORD", HOST);
        row.put("credential_host", OTHER_HOST);

        rejects("ck_connection_profile_version_credential_host", () -> insertInto("connection_profile_version", row));
    }

    /** De kernregel L4a: een credential van een andere host kan niet gekoppeld worden, ook niet met valse kolommen. */
    @Test
    void rejectsACredentialThatIsBoundToAnotherHostThroughTheCompositeForeignKey() {
        long profileId = insertProfile(unique("P"));
        long credentialOfOtherHost = insertCredential(OTHER_HOST, "SFTP_PASSWORD");

        // Host en credential_host zijn gelijk (de check slaagt), maar de credential is aan een andere host gebonden.
        Map<String, Object> row = profileVersionRow(profileId, credentialOfOtherHost, "SFTP_PASSWORD", HOST);
        rejects("fk_connection_profile_version_credential", () -> insertInto("connection_profile_version", row));

        // Ook een credential met een andere soort dan opgegeven wordt door dezelfde FK geweigerd.
        long passwordCredential = insertCredential(HOST, "SFTP_PASSWORD");
        Map<String, Object> wrongKind = profileVersionRow(profileId, passwordCredential, "SSH_PRIVATE_KEY", HOST);
        wrongKind.put("auth_method", "PRIVATE_KEY");
        rejects("fk_connection_profile_version_credential", () -> insertInto("connection_profile_version", wrongKind));

        // Een onbekende credential idem.
        Map<String, Object> unknown = profileVersionRow(profileId, 999_999_999L, "SFTP_PASSWORD", HOST);
        rejects("fk_connection_profile_version_credential", () -> insertInto("connection_profile_version", unknown));
    }

    @Test
    void rejectsAnAuthMethodThatDoesNotMatchTheCredentialKind() {
        long profileId = insertProfile(unique("P"));
        long passwordCredential = insertCredential(HOST, "SFTP_PASSWORD");
        long keyCredential = insertCredential(HOST, "SSH_PRIVATE_KEY");

        // Beide checks beschrijven dezelfde regel; Postgres meldt de eerste die faalt.
        Map<String, Object> passwordWithKey = profileVersionRow(profileId, keyCredential, "SSH_PRIVATE_KEY", HOST);
        rejectsOneOf(List.of("ck_connection_profile_version_auth_password", "ck_connection_profile_version_auth_key"),
                () -> insertInto("connection_profile_version", withAuth(passwordWithKey, "PASSWORD")));

        Map<String, Object> keyWithPassword = profileVersionRow(profileId, passwordCredential, "SFTP_PASSWORD", HOST);
        rejectsOneOf(List.of("ck_connection_profile_version_auth_password", "ck_connection_profile_version_auth_key"),
                () -> insertInto("connection_profile_version", withAuth(keyWithPassword, "PRIVATE_KEY")));

        Map<String, Object> unknownMethod = profileVersionRow(profileId, passwordCredential, "SFTP_PASSWORD", HOST);
        rejects("ck_connection_profile_version_auth_method",
                () -> insertInto("connection_profile_version", withAuth(unknownMethod, "KERBEROS")));

        // De gereserveerde combinatie PRIVATE_KEY + SSH_PRIVATE_KEY is databasematig toegelaten (A5 beperkt de service).
        Map<String, Object> keyWithKey = profileVersionRow(profileId, keyCredential, "SSH_PRIVATE_KEY", HOST);
        assertThatCode(() -> insertInto("connection_profile_version", withAuth(keyWithKey, "PRIVATE_KEY")))
                .doesNotThrowAnyException();
    }

    @Test
    void enforcesThePortRangeAndDefaultsToTwentyTwo() {
        long profileId = insertProfile(unique("P"));
        long credentialId = insertCredential(HOST, "SFTP_PASSWORD");

        int version = 1;
        for (int badPort : new int[] {0, -1, 65536}) {
            Map<String, Object> row = profileVersionRow(profileId, credentialId, "SFTP_PASSWORD", HOST);
            row.put("port", badPort);
            row.put("version_number", version++);
            rejects("ck_connection_profile_version_port", () -> insertInto("connection_profile_version", row));
        }
        for (int goodPort : new int[] {1, 65535}) {
            Map<String, Object> row = profileVersionRow(profileId, credentialId, "SFTP_PASSWORD", HOST);
            row.put("port", goodPort);
            row.put("version_number", version++);
            assertThatCode(() -> insertInto("connection_profile_version", row)).doesNotThrowAnyException();
        }
        Map<String, Object> withoutPort = profileVersionRow(profileId, credentialId, "SFTP_PASSWORD", HOST);
        withoutPort.put("version_number", version);
        long id = insertInto("connection_profile_version", withoutPort);
        assertThat(jdbc.queryForObject("select port from connection_profile_version where id = ?", Integer.class, id))
                .isEqualTo(22);
    }

    /** Dezelfde normalisatiecheck als 013-4: enkel geweigerd, nooit stil aangepast. */
    @Test
    void rejectsANonNormalizedHost() {
        long profileId = insertProfile(unique("P"));
        long credentialId = insertCredential(HOST, "SFTP_PASSWORD");

        int version = 1;
        for (String host : List.of("SFTP.Example.test", "sftp.example.test.", "sftp .example.test",
                " sftp.example.test", "sftp.example.test ", "sftp.example\ttest", "", "   ")) {
            // credential_host = host, zodat de hostnormalisatie (en niet de gelijkheidscheck) de weigering is.
            Map<String, Object> row = profileVersionRow(profileId, credentialId, "SFTP_PASSWORD", host);
            row.put("version_number", version++);
            rejects("ck_connection_profile_version_host_normalized",
                    () -> insertInto("connection_profile_version", row));
        }
    }

    @Test
    void requiresUsernameHostKeyAndConfigHash() {
        long profileId = insertProfile(unique("P"));
        long credentialId = insertCredential(HOST, "SFTP_PASSWORD");

        Map<String, Object> blankUser = profileVersionRow(profileId, credentialId, "SFTP_PASSWORD", HOST);
        blankUser.put("username", "  ");
        rejects("ck_connection_profile_version_username", () -> insertInto("connection_profile_version", blankUser));

        Map<String, Object> blankAlgorithm = profileVersionRow(profileId, credentialId, "SFTP_PASSWORD", HOST);
        blankAlgorithm.put("host_key_algorithm", "");
        rejects("ck_connection_profile_version_host_key",
                () -> insertInto("connection_profile_version", blankAlgorithm));

        Map<String, Object> blankFingerprint = profileVersionRow(profileId, credentialId, "SFTP_PASSWORD", HOST);
        blankFingerprint.put("host_key_fingerprint_sha256", " ");
        rejects("ck_connection_profile_version_host_key",
                () -> insertInto("connection_profile_version", blankFingerprint));

        Map<String, Object> noHash = profileVersionRow(profileId, credentialId, "SFTP_PASSWORD", HOST);
        noHash.put("config_hash", null);
        assertThatThrownBy(() -> insertInto("connection_profile_version", noHash))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("config_hash");

        Map<String, Object> noHostKey = profileVersionRow(profileId, credentialId, "SFTP_PASSWORD", HOST);
        noHostKey.put("host_key_fingerprint_sha256", null);
        assertThatThrownBy(() -> insertInto("connection_profile_version", noHostKey))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("host_key_fingerprint");
    }

    @Test
    void versionNumbersAreUniquePerProfileAndStartAtOne() {
        long profile = insertProfile(unique("P"));
        long otherProfile = insertProfile(unique("P"));
        long credentialId = insertCredential(HOST, "SFTP_PASSWORD");

        long first = insertInto("connection_profile_version",
                profileVersionRow(profile, credentialId, "SFTP_PASSWORD", HOST));
        rejects("uk_connection_profile_version_number", () -> insertInto("connection_profile_version",
                profileVersionRow(profile, credentialId, "SFTP_PASSWORD", HOST)));
        assertThatCode(() -> insertInto("connection_profile_version",
                profileVersionRow(otherProfile, credentialId, "SFTP_PASSWORD", HOST))).doesNotThrowAnyException();

        Map<String, Object> zero = profileVersionRow(profile, credentialId, "SFTP_PASSWORD", HOST);
        zero.put("version_number", 0);
        rejects("ck_connection_profile_version_number", () -> insertInto("connection_profile_version", zero));

        Map<String, Object> second = profileVersionRow(profile, credentialId, "SFTP_PASSWORD", HOST);
        second.put("version_number", 2);
        second.put("based_on_version_id", first);
        assertThatCode(() -> insertInto("connection_profile_version", second)).doesNotThrowAnyException();

        Map<String, Object> dangling = profileVersionRow(profile, credentialId, "SFTP_PASSWORD", HOST);
        dangling.put("version_number", 3);
        dangling.put("based_on_version_id", 999_999_999L);
        rejects("fk_connection_profile_version_based_on", () -> insertInto("connection_profile_version", dangling));
    }

    @Test
    void refusesASubjectWithoutANameAndAVersionWithoutCreator() {
        long profileId = insertProfile(unique("P"));
        long credentialId = insertCredential(HOST, "SFTP_PASSWORD");

        Map<String, Object> subjectOnly = profileVersionRow(profileId, credentialId, "SFTP_PASSWORD", HOST);
        subjectOnly.put("created_by", null);
        subjectOnly.put("created_by_subject", SUBJECT);
        assertThatThrownBy(() -> insertInto("connection_profile_version", subjectOnly))
                .isInstanceOf(DataIntegrityViolationException.class);

        Map<String, Object> headSubject = headRow(unique("P"));
        headSubject.put("created_by", null);
        headSubject.put("created_by_subject", SUBJECT);
        assertThatThrownBy(() -> insertInto("connection_profile", headSubject))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- delivery_configuration + versie ------------------------------------------------------------------

    @Test
    void rejectsADuplicateConfigurationCodeAndAnUnknownAcquisitionKind() {
        String code = unique("D");
        insertConfiguration(code);

        rejects("uk_delivery_configuration_code", () -> insertConfiguration(code));
        Map<String, Object> ftp = headRow(unique("D"));
        ftp.put("acquisition_kind", "FTP");
        ftp.remove("protocol");
        rejects("ck_delivery_configuration_acquisition_kind", () -> insertInto("delivery_configuration", ftp));
    }

    /** A3: na het ophalen enkel LEAVE; verplaatsen of verwijderen bestaat niet. */
    @Test
    void allowsOnlyLeaveAsPostFetchAction() {
        Chain chain = insertChain();

        for (String action : List.of("DELETE", "MOVE", "ARCHIVE", "leave", "")) {
            Map<String, Object> row = dcVersionRow(chain.configurationId, chain.profileVersionId, 1);
            row.put("post_fetch_action", action);
            rejects("ck_delivery_configuration_version_post_fetch",
                    () -> insertInto("delivery_configuration_version", row));
        }
        assertThatCode(() -> insertInto("delivery_configuration_version",
                dcVersionRow(chain.configurationId, chain.profileVersionId, 1))).doesNotThrowAnyException();
    }

    @Test
    void allowsOnlyTheDocumentedSelectionModes() {
        Chain chain = insertChain();

        Map<String, Object> regex = dcVersionRow(chain.configurationId, chain.profileVersionId, 1);
        regex.put("selection_mode", "REGEX");
        rejects("ck_delivery_configuration_version_selection_mode",
                () -> insertInto("delivery_configuration_version", regex));

        int version = 1;
        for (String mode : List.of("CONDITIONS", "ALL_FILES")) {
            Map<String, Object> row = dcVersionRow(chain.configurationId, chain.profileVersionId, version++);
            row.put("selection_mode", mode);
            assertThatCode(() -> insertInto("delivery_configuration_version", row)).as(mode)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void enforcesTheAgeAndSizeBoundaries() {
        Chain chain = insertChain();

        Map<String, Object> negativeAge = dcVersionRow(chain.configurationId, chain.profileVersionId, 1);
        negativeAge.put("min_file_age_seconds", -1);
        rejects("ck_delivery_configuration_version_min_age",
                () -> insertInto("delivery_configuration_version", negativeAge));

        Map<String, Object> zeroBytes = dcVersionRow(chain.configurationId, chain.profileVersionId, 1);
        zeroBytes.put("max_file_bytes", 0L);
        rejects("ck_delivery_configuration_version_max_bytes",
                () -> insertInto("delivery_configuration_version", zeroBytes));
        Map<String, Object> negativeBytes = dcVersionRow(chain.configurationId, chain.profileVersionId, 1);
        negativeBytes.put("max_file_bytes", -5L);
        rejects("ck_delivery_configuration_version_max_bytes",
                () -> insertInto("delivery_configuration_version", negativeBytes));

        // Grensgevallen: 0 seconden en 1 byte zijn geldig.
        Map<String, Object> boundary = dcVersionRow(chain.configurationId, chain.profileVersionId, 1);
        boundary.put("min_file_age_seconds", 0);
        boundary.put("max_file_bytes", 1L);
        assertThatCode(() -> insertInto("delivery_configuration_version", boundary)).doesNotThrowAnyException();

        Map<String, Object> noAge = dcVersionRow(chain.configurationId, chain.profileVersionId, 2);
        noAge.put("min_file_age_seconds", null);
        assertThatThrownBy(() -> insertInto("delivery_configuration_version", noAge))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("min_file_age_seconds");
    }

    @Test
    void versionNumbersOfAConfigurationAreUniqueAndItsReferencesMustExist() {
        Chain chain = insertChain();
        long otherConfiguration = insertConfiguration(unique("D"));

        long first = insertInto("delivery_configuration_version",
                dcVersionRow(chain.configurationId, chain.profileVersionId, 1));
        rejects("uk_delivery_configuration_version_number", () -> insertInto("delivery_configuration_version",
                dcVersionRow(chain.configurationId, chain.profileVersionId, 1)));
        assertThatCode(() -> insertInto("delivery_configuration_version",
                dcVersionRow(otherConfiguration, chain.profileVersionId, 1))).doesNotThrowAnyException();

        Map<String, Object> zero = dcVersionRow(chain.configurationId, chain.profileVersionId, 0);
        rejects("ck_delivery_configuration_version_number", () -> insertInto("delivery_configuration_version", zero));

        Map<String, Object> basedOn = dcVersionRow(chain.configurationId, chain.profileVersionId, 2);
        basedOn.put("based_on_version_id", first);
        assertThatCode(() -> insertInto("delivery_configuration_version", basedOn)).doesNotThrowAnyException();

        Map<String, Object> unknownProfileVersion = dcVersionRow(chain.configurationId, 999_999_999L, 3);
        rejects("fk_delivery_configuration_version_profile_version",
                () -> insertInto("delivery_configuration_version", unknownProfileVersion));
        Map<String, Object> unknownConfiguration = dcVersionRow(999_999_999L, chain.profileVersionId, 1);
        rejects("fk_delivery_configuration_version_configuration",
                () -> insertInto("delivery_configuration_version", unknownConfiguration));
        Map<String, Object> danglingBasedOn = dcVersionRow(chain.configurationId, chain.profileVersionId, 4);
        danglingBasedOn.put("based_on_version_id", 999_999_999L);
        rejects("fk_delivery_configuration_version_based_on",
                () -> insertInto("delivery_configuration_version", danglingBasedOn));

        Map<String, Object> blankDirectory = dcVersionRow(chain.configurationId, chain.profileVersionId, 5);
        blankDirectory.put("remote_directory", " ");
        rejects("ck_delivery_configuration_version_directory",
                () -> insertInto("delivery_configuration_version", blankDirectory));
    }

    // --- delivery_configuration_file_condition ------------------------------------------------------------

    @Test
    void allowsOnlyTheDocumentedConditionKinds() {
        long dcVersionId = insertChain().insertVersion(1);

        int sequence = 0;
        for (String kind : List.of("NAME_EQUALS", "NAME_STARTS_WITH", "NAME_ENDS_WITH", "NAME_CONTAINS",
                "EXTENSION_IS")) {
            Map<String, Object> row = conditionRow(dcVersionId, 1, sequence++, kind);
            assertThatCode(() -> insertInto("delivery_configuration_file_condition", row)).as(kind)
                    .doesNotThrowAnyException();
        }
        for (String kind : List.of("NAME_REGEX", "NAME_GLOB", "name_equals")) {
            Map<String, Object> row = conditionRow(dcVersionId, 2, sequence++, kind);
            rejects("ck_delivery_configuration_file_condition_kind",
                    () -> insertInto("delivery_configuration_file_condition", row));
        }
        assertThat(DeliveryFileConditionKind.values()).extracting(Enum::name).containsExactly("NAME_EQUALS",
                "NAME_STARTS_WITH", "NAME_ENDS_WITH", "NAME_CONTAINS", "EXTENSION_IS");
    }

    @Test
    void refusesADuplicateSequenceInTheSameGroupButAllowsItInAnotherGroupOrVersion() {
        Chain chain = insertChain();
        long version1 = chain.insertVersion(1);
        long version2 = chain.insertVersion(2);

        insertInto("delivery_configuration_file_condition", conditionRow(version1, 1, 1, "NAME_EQUALS"));

        rejects("uk_delivery_configuration_file_condition_order", () -> insertInto(
                "delivery_configuration_file_condition", conditionRow(version1, 1, 1, "NAME_CONTAINS")));
        assertThatCode(() -> insertInto("delivery_configuration_file_condition",
                conditionRow(version1, 2, 1, "NAME_EQUALS"))).doesNotThrowAnyException();
        assertThatCode(() -> insertInto("delivery_configuration_file_condition",
                conditionRow(version1, 1, 2, "NAME_EQUALS"))).doesNotThrowAnyException();
        assertThatCode(() -> insertInto("delivery_configuration_file_condition",
                conditionRow(version2, 1, 1, "NAME_EQUALS"))).doesNotThrowAnyException();
    }

    @Test
    void checksTheConditionValueNumbersAndVersionReference() {
        long dcVersionId = insertChain().insertVersion(1);

        Map<String, Object> blank = conditionRow(dcVersionId, 1, 1, "NAME_EQUALS");
        blank.put("compare_value", "  ");
        rejects("ck_delivery_configuration_file_condition_value",
                () -> insertInto("delivery_configuration_file_condition", blank));

        Map<String, Object> negative = conditionRow(dcVersionId, -1, 1, "NAME_EQUALS");
        rejects("ck_delivery_configuration_file_condition_numbers",
                () -> insertInto("delivery_configuration_file_condition", negative));

        Map<String, Object> dangling = conditionRow(999_999_999L, 1, 1, "NAME_EQUALS");
        rejects("fk_delivery_configuration_file_condition_version",
                () -> insertInto("delivery_configuration_file_condition", dangling));

        Map<String, Object> noCase = conditionRow(dcVersionId, 1, 1, "NAME_EQUALS");
        noCase.put("case_sensitive", null);
        assertThatThrownBy(() -> insertInto("delivery_configuration_file_condition", noCase))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("case_sensitive");
    }

    // --- acquisition_config_event ------------------------------------------------------------------------

    @Test
    void allowsOnlyTheDocumentedEventKindsAndSources() {
        for (String kind : List.of("TASK_BOUND", "TASK_UNBOUND", "CONNECTION_TESTED", "HOST_KEY_SCANNED",
                "RETIRED")) {
            assertThatCode(() -> insertEvent(kind, "HUMAN", USER, null)).as(kind).doesNotThrowAnyException();
        }
        rejects("ck_acquisition_config_event_kind", () -> insertEvent("FETCHED", "HUMAN", USER, null));
        rejects("ck_acquisition_config_event_source", () -> insertEvent("TASK_BOUND", "SCHEDULER", USER, null));
    }

    /** {@code ck_acquisition_config_event_source}: HUMAN draagt altijd een naam, SYSTEM nooit (patroon 012/013). */
    @Test
    void enforcesTheSourceRulesForWhoActed() {
        rejects("ck_acquisition_config_event_source", () -> insertEvent("TASK_BOUND", "HUMAN", null, null));
        rejects("ck_acquisition_config_event_source", () -> insertEvent("CONNECTION_TESTED", "SYSTEM", "system", null));
        rejects("ck_acquisition_config_event_source", () -> insertEvent("CONNECTION_TESTED", "SYSTEM", null, SUBJECT));
        rejects("ck_acquisition_config_event_source", () -> insertEvent("CONNECTION_TESTED", "SYSTEM", USER, SUBJECT));
        // Een subject zonder naam: de source-check en de koppelcheck beschrijven hier dezelfde regel.
        rejectsOneOf(List.of("ck_acquisition_config_event_source", "ck_acquisition_config_event_subject"),
                () -> insertEvent("TASK_BOUND", "HUMAN", null, SUBJECT));

        assertThatCode(() -> insertEvent("TASK_BOUND", "HUMAN", USER, SUBJECT)).doesNotThrowAnyException();
        assertThatCode(() -> insertEvent("CONNECTION_TESTED", "SYSTEM", null, null)).doesNotThrowAnyException();
    }

    @Test
    void refusesAnEventForAnUnknownTaskVersionOrProfileVersion() {
        for (String column : List.of("task_id", "dc_version_id", "profile_version_id")) {
            Map<String, Object> row = eventRow("TASK_BOUND", "HUMAN", USER, null);
            row.put(column, 999_999_999L);
            assertThatThrownBy(() -> insertInto("acquisition_config_event", row)).as(column)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("fk_acquisition_config_event_");
        }
    }

    // --- catalog_import_task ------------------------------------------------------------------------------

    @Test
    void aTaskWithoutDeliveryConfigurationKeepsWorkingAndAnUnknownVersionIsRefused() {
        Scenario scenario = scenario();
        CatalogImportTask plain = tasks.saveAndFlush(new CatalogImportTask(scenario.link, unique("T"),
                TaskTriggerType.MANUAL));

        assertThat(jdbc.queryForObject("select delivery_configuration_version_id from catalog_import_task "
                + "where id = ?", Long.class, plain.getId())).isNull();
        assertThat(tasks.findById(plain.getId()).orElseThrow().getDeliveryConfigurationVersion()).isNull();

        assertThatThrownBy(() -> jdbc.update("update catalog_import_task set delivery_configuration_version_id = ? "
                + "where id = ?", 999_999_999L, plain.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_catalog_import_task_dc_version");
    }

    // --- Geldige keten via JPA en repositories ------------------------------------------------------------

    @Test
    void persistsAValidChainFromCredentialToTaskBinding() {
        Instant now = Instant.now();
        ExternalCredential credential = credentials.saveAndFlush(new ExternalCredential(UUID.randomUUID(),
                "Leverancier X", ExternalCredentialSecretKind.SFTP_PASSWORD, HOST, CIPHERTEXT, "lc1-test", USER,
                null, now));
        String profileCode = unique("P");
        ConnectionProfile profile = profiles.saveAndFlush(new ConnectionProfile(profileCode, "Profiel X",
                ConnectionProtocol.SFTP, USER, SUBJECT, now));
        ConnectionProfileVersion profileV1 = profileVersions.saveAndFlush(new ConnectionProfileVersion(profile, 1,
                HOST, 2222, "leverancier", ConnectionAuthMethod.PASSWORD, credential, "ssh-ed25519", FINGERPRINT,
                null, "eerste versie", HASH, USER, SUBJECT, now));
        ConnectionProfileVersion profileV2 = profileVersions.saveAndFlush(new ConnectionProfileVersion(profile, 2,
                HOST, 22, "leverancier", ConnectionAuthMethod.PASSWORD, credential, "ssh-ed25519", FINGERPRINT,
                profileV1, "poort terug naar 22", "b".repeat(64), USER, null, now));

        String configurationCode = unique("D");
        DeliveryConfiguration configuration = configurations.saveAndFlush(new DeliveryConfiguration(
                configurationCode, "Levering X", AcquisitionKind.SFTP, USER, SUBJECT, now));
        DeliveryConfigurationVersion dcV1 = configurationVersions.saveAndFlush(new DeliveryConfigurationVersion(
                configuration, 1, profileV2, "/uitgaand/prijzen", DeliverySelectionMode.CONDITIONS, 300,
                1_073_741_824L, DeliveryPostFetchAction.LEAVE, null, "start", HASH, USER, SUBJECT, now));
        conditions.saveAndFlush(new DeliveryConfigurationFileCondition(dcV1, 1, 2, DeliveryFileConditionKind.EXTENSION_IS,
                "csv", false, null));
        conditions.saveAndFlush(new DeliveryConfigurationFileCondition(dcV1, 1, 1,
                DeliveryFileConditionKind.NAME_STARTS_WITH, "PRIJS_", true, null));
        conditions.saveAndFlush(new DeliveryConfigurationFileCondition(dcV1, 0, 1,
                DeliveryFileConditionKind.NAME_EQUALS, "prijzen.csv", false, "REEKS"));

        Scenario scenario = scenario();
        CatalogImportTask task = tasks.saveAndFlush(new CatalogImportTask(scenario.link, unique("T"),
                TaskTriggerType.MANUAL));
        task.setDeliveryConfigurationVersion(dcV1);
        tasks.saveAndFlush(task);
        events.saveAndFlush(new AcquisitionConfigEvent(AcquisitionConfigEventKind.TASK_BOUND, task, dcV1, profileV2,
                "OK", "taak gekoppeld", "nieuwe leverancier", AcquisitionConfigEventSource.HUMAN, USER, SUBJECT, now));
        events.saveAndFlush(new AcquisitionConfigEvent(AcquisitionConfigEventKind.CONNECTION_TESTED, task, dcV1,
                profileV2, "SFTP_AUTH_FAILED", null, null, AcquisitionConfigEventSource.SYSTEM, null, null, now));

        // Leesbaar via de repositories.
        assertThat(profiles.findByCode(profileCode).orElseThrow().getId()).isEqualTo(profile.getId());
        assertThat(configurations.findByCode(configurationCode).orElseThrow().getId()).isEqualTo(configuration.getId());
        assertThat(profileVersions.findByConnectionProfileIdOrderByVersionNumberDesc(profile.getId()))
                .extracting(ConnectionProfileVersion::getVersionNumber).containsExactly(2, 1);
        assertThat(profileVersions.findMaxVersionNumber(profile.getId())).contains(2);
        assertThat(profileVersions.findMaxVersionNumber(999_999_999L)).isEmpty();
        assertThat(profileVersions.findByConnectionProfileIdAndVersionNumber(profile.getId(), 1)).isPresent();
        assertThat(profileVersions.countByCredentialId(credential.getId())).isEqualTo(2L);
        assertThat(configurationVersions.findByDeliveryConfigurationIdOrderByVersionNumberDesc(configuration.getId()))
                .extracting(DeliveryConfigurationVersion::getId).containsExactly(dcV1.getId());
        assertThat(configurationVersions.findMaxVersionNumber(configuration.getId())).contains(1);
        assertThat(configurationVersions.findByDeliveryConfigurationIdAndVersionNumber(configuration.getId(), 1))
                .isPresent();
        assertThat(conditions.findByDeliveryConfigurationVersionIdOrderByGroupNumberAscSequenceNumberAsc(dcV1.getId()))
                .extracting(DeliveryConfigurationFileCondition::getCompareValue)
                .containsExactly("prijzen.csv", "PRIJS_", "csv");
        assertThat(conditions.findByDeliveryConfigurationVersionIdOrderByGroupNumberAscSequenceNumberAsc(dcV1.getId())
                .get(0).getBookmarkName()).isEqualTo("REEKS");
        assertThat(jdbc.queryForObject("select delivery_configuration_version_id from catalog_import_task "
                + "where id = ?", Long.class, task.getId())).isEqualTo(dcV1.getId());
        List<AcquisitionConfigEvent> history = events.findByTaskIdOrderByIdAsc(task.getId());
        assertThat(history).extracting(AcquisitionConfigEvent::getEventKind)
                .containsExactly(AcquisitionConfigEventKind.TASK_BOUND, AcquisitionConfigEventKind.CONNECTION_TESTED);
        assertThat(history.get(1).getSource()).isEqualTo(AcquisitionConfigEventSource.SYSTEM);
        assertThat(history.get(1).getChangedBy()).isNull();

        // De opgeslagen kolommen van de profielversie kloppen (o.a. de afgeleide credentialkolommen).
        ConnectionProfileVersion reloaded = profileVersions.findById(profileV1.getId()).orElseThrow();
        assertThat(reloaded.getHost()).isEqualTo(HOST);
        assertThat(reloaded.getPort()).isEqualTo(2222);
        assertThat(reloaded.getCredentialId()).isEqualTo(credential.getId());
        assertThat(reloaded.getCredentialSecretKind()).isEqualTo(ExternalCredentialSecretKind.SFTP_PASSWORD);
        assertThat(reloaded.getCredentialHost()).isEqualTo(HOST);
        assertThat(reloaded.getCreatedBySubject()).isEqualTo(SUBJECT);
        assertThat(reloaded.toString()).doesNotContain(HOST).doesNotContain("leverancier")
                .doesNotContain(FINGERPRINT);
    }

    // --- Constructors weigeren ongeldige invoer zonder waarden te echoën ----------------------------------

    @Test
    void theProfileVersionConstructorRefusesInvalidInputWithoutEchoingValues() {
        Instant now = Instant.now();
        ExternalCredential credential = credentials.saveAndFlush(new ExternalCredential(UUID.randomUUID(), "Cred",
                ExternalCredentialSecretKind.SFTP_PASSWORD, HOST, CIPHERTEXT, "lc1-test", USER, null, now));
        ExternalCredential keyCredential = credentials.saveAndFlush(new ExternalCredential(UUID.randomUUID(), "Key",
                ExternalCredentialSecretKind.SSH_PRIVATE_KEY, HOST, CIPHERTEXT, "lc1-test", USER, null, now));
        ConnectionProfile profile = profiles.saveAndFlush(new ConnectionProfile(unique("P"), "Profiel",
                ConnectionProtocol.SFTP, USER, null, now));

        // Credential van een andere host.
        assertThatThrownBy(() -> version(profile, OTHER_HOST, 22, "geheime-login", ConnectionAuthMethod.PASSWORD,
                credential, now)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("credential is bound to another host");
        // Authenticatiemethode past niet bij de soort credential.
        assertThatThrownBy(() -> version(profile, HOST, 22, "geheime-login", ConnectionAuthMethod.PRIVATE_KEY,
                credential, now)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("authMethod does not match the credential secretKind");
        assertThatThrownBy(() -> version(profile, HOST, 22, "geheime-login", ConnectionAuthMethod.PASSWORD,
                keyCredential, now)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("authMethod does not match the credential secretKind");
        // Poort buiten bereik.
        for (int port : new int[] {0, 65536}) {
            assertThatThrownBy(() -> version(profile, HOST, port, "geheime-login", ConnectionAuthMethod.PASSWORD,
                    credential, now)).isInstanceOf(IllegalArgumentException.class).hasMessage("port is out of range");
        }
        // Niet-genormaliseerde host, zonder de host te noemen.
        for (String host : List.of("SFTP.Example.test", "sftp.example.test.", "sftp example.test")) {
            assertThatThrownBy(() -> version(profile, host, 22, "geheime-login", ConnectionAuthMethod.PASSWORD,
                    credential, now)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("host must be normalized").hasMessageNotContaining(host.trim())
                    .hasMessageNotContaining("geheime-login");
        }
        // Ontbrekende gegevens en een niet-opgeslagen credential.
        assertThatThrownBy(() -> version(profile, HOST, 22, " ", ConnectionAuthMethod.PASSWORD, credential, now))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("username is required");
        assertThatThrownBy(() -> version(profile, HOST, 22, "geheime-login", null, credential, now))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("authMethod is required");
        assertThatThrownBy(() -> version(profile, HOST, 22, "geheime-login", ConnectionAuthMethod.PASSWORD, null, now))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("credential is required");
        ExternalCredential unsaved = new ExternalCredential(UUID.randomUUID(), "Nieuw",
                ExternalCredentialSecretKind.SFTP_PASSWORD, HOST, CIPHERTEXT, "lc1-test", USER, null, now);
        assertThatThrownBy(() -> version(profile, HOST, 22, "geheime-login", ConnectionAuthMethod.PASSWORD, unsaved,
                now)).isInstanceOf(IllegalArgumentException.class).hasMessage("credential id is required");
        assertThatCode(() -> version(profile, HOST, 22, "geheime-login", ConnectionAuthMethod.PASSWORD, credential,
                now)).doesNotThrowAnyException();
    }

    @Test
    void theOtherConstructorsRefuseInvalidInput() {
        Instant now = Instant.now();
        ConnectionProfile profile = new ConnectionProfile(unique("P"), "Profiel", ConnectionProtocol.SFTP, USER, null,
                now);
        DeliveryConfiguration configuration = new DeliveryConfiguration(unique("D"), "Config",
                AcquisitionKind.SFTP, USER, null, now);
        ConnectionProfileVersion profileVersion = null;

        assertThatThrownBy(() -> new ConnectionProfile(" ", "n", ConnectionProtocol.SFTP, USER, null, now))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("code is required");
        assertThatThrownBy(() -> new ConnectionProfile("c", "n", null, USER, null, now))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("protocol is required");
        assertThatThrownBy(() -> new ConnectionProfile("c", "n", ConnectionProtocol.SFTP, null, null, now))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("createdBy is required");
        assertThatThrownBy(() -> new DeliveryConfiguration("c", "n", null, USER, null, now))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("acquisitionKind is required");

        assertThatThrownBy(() -> new DeliveryConfigurationVersion(configuration, 1, profileVersion, "/map",
                DeliverySelectionMode.ALL_FILES, 0, 1L, DeliveryPostFetchAction.LEAVE, null, null, HASH, USER, null,
                now)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("connectionProfileVersion is required");
        ConnectionProfileVersion dummyProfileVersion = new ConnectionProfileVersion();
        assertThatThrownBy(() -> new DeliveryConfigurationVersion(configuration, 1, dummyProfileVersion, "/map",
                DeliverySelectionMode.ALL_FILES, -1, 1L, DeliveryPostFetchAction.LEAVE, null, null, HASH, USER, null,
                now)).isInstanceOf(IllegalArgumentException.class).hasMessage("minFileAgeSeconds is out of range");
        assertThatThrownBy(() -> new DeliveryConfigurationVersion(configuration, 1, dummyProfileVersion, "/geheim",
                DeliverySelectionMode.ALL_FILES, 0, 0L, DeliveryPostFetchAction.LEAVE, null, null, HASH, USER, null,
                now)).isInstanceOf(IllegalArgumentException.class).hasMessage("maxFileBytes is out of range")
                .hasMessageNotContaining("/geheim");
        assertThatThrownBy(() -> new DeliveryConfigurationVersion(configuration, 1, dummyProfileVersion, "/map",
                DeliverySelectionMode.ALL_FILES, 0, 1L, null, null, null, HASH, USER, null, now))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("postFetchAction is required");
        assertThatThrownBy(() -> new DeliveryConfigurationVersion(configuration, 1, dummyProfileVersion, "/map",
                DeliverySelectionMode.ALL_FILES, 0, 1L, DeliveryPostFetchAction.LEAVE, null, null, null, USER, null,
                now)).isInstanceOf(IllegalArgumentException.class).hasMessage("configHash is required");

        DeliveryConfigurationVersion dcVersion = new DeliveryConfigurationVersion();
        assertThatThrownBy(() -> new DeliveryConfigurationFileCondition(dcVersion, 0, 0, null, "x", false, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("conditionKind is required");
        assertThatThrownBy(() -> new DeliveryConfigurationFileCondition(dcVersion, 0, 0,
                DeliveryFileConditionKind.NAME_EQUALS, " ", false, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("compareValue is required");
        assertThatThrownBy(() -> new DeliveryConfigurationFileCondition(dcVersion, -1, 0,
                DeliveryFileConditionKind.NAME_EQUALS, "x", false, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("groupNumber is out of range");

        // Event: HUMAN zonder naam, SYSTEM met naam of subject, subject zonder naam.
        assertThatThrownBy(() -> new AcquisitionConfigEvent(AcquisitionConfigEventKind.TASK_BOUND, null, null, null,
                null, null, null, AcquisitionConfigEventSource.HUMAN, null, null, now))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("changedBy is required");
        assertThatThrownBy(() -> new AcquisitionConfigEvent(AcquisitionConfigEventKind.TASK_BOUND, null, null, null,
                null, null, null, AcquisitionConfigEventSource.SYSTEM, "system", null, now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a SYSTEM event carries no changedBy or changedBySubject");
        assertThatThrownBy(() -> new AcquisitionConfigEvent(AcquisitionConfigEventKind.TASK_BOUND, null, null, null,
                null, null, null, AcquisitionConfigEventSource.SYSTEM, null, SUBJECT, now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a SYSTEM event carries no changedBy or changedBySubject");
        assertThatCode(() -> new AcquisitionConfigEvent(AcquisitionConfigEventKind.RETIRED, null, null, null, null,
                null, null, AcquisitionConfigEventSource.SYSTEM, null, null, now)).doesNotThrowAnyException();
    }

    // --- Onveranderlijkheid van versies (L3) --------------------------------------------------------------

    /** Geen enkele versie-entiteit heeft een setter: een wijziging is per definitie een nieuwe versie. */
    @Test
    void versionEntitiesExposeNoSetters() {
        for (Class<?> type : List.of(ConnectionProfileVersion.class, DeliveryConfigurationVersion.class,
                DeliveryConfigurationFileCondition.class, AcquisitionConfigEvent.class)) {
            List<String> setters = Arrays.stream(type.getMethods()).map(Method::getName)
                    .filter(name -> name.startsWith("set")).collect(Collectors.toList());
            assertThat(setters).as(type.getSimpleName()).isEmpty();
        }
    }

    /**
     * Ook een geforceerde wijziging (reflectie op een losgekoppelde instantie, daarna opslaan) bereikt de database
     * niet: elke kolom van een versie is {@code updatable = false}.
     */
    @Test
    void aForcedChangeToAVersionNeverReachesTheDatabase() {
        Instant now = Instant.now();
        ExternalCredential credential = credentials.saveAndFlush(new ExternalCredential(UUID.randomUUID(), "Cred",
                ExternalCredentialSecretKind.SFTP_PASSWORD, HOST, CIPHERTEXT, "lc1-test", USER, null, now));
        ConnectionProfile profile = profiles.saveAndFlush(new ConnectionProfile(unique("P"), "Profiel",
                ConnectionProtocol.SFTP, USER, null, now));
        ConnectionProfileVersion profileVersion = profileVersions.saveAndFlush(version(profile, HOST, 22,
                "originele-login", ConnectionAuthMethod.PASSWORD, credential, now));
        DeliveryConfiguration configuration = configurations.saveAndFlush(new DeliveryConfiguration(unique("D"),
                "Config", AcquisitionKind.SFTP, USER, null, now));
        DeliveryConfigurationVersion dcVersion = configurationVersions.saveAndFlush(new DeliveryConfigurationVersion(
                configuration, 1, profileVersion, "/origineel", DeliverySelectionMode.CONDITIONS, 300, 1000L,
                DeliveryPostFetchAction.LEAVE, null, null, HASH, USER, null, now));
        DeliveryConfigurationFileCondition condition = conditions.saveAndFlush(
                new DeliveryConfigurationFileCondition(dcVersion, 1, 1, DeliveryFileConditionKind.NAME_EQUALS,
                        "origineel.csv", false, null));

        ReflectionTestUtils.setField(profileVersion, "username", "gewijzigde-login");
        ReflectionTestUtils.setField(profileVersion, "port", 2200);
        profileVersions.saveAndFlush(profileVersion);
        ReflectionTestUtils.setField(dcVersion, "remoteDirectory", "/gewijzigd");
        ReflectionTestUtils.setField(dcVersion, "maxFileBytes", 5L);
        configurationVersions.saveAndFlush(dcVersion);
        ReflectionTestUtils.setField(condition, "compareValue", "gewijzigd.csv");
        conditions.saveAndFlush(condition);

        assertThat(jdbc.queryForObject("select username from connection_profile_version where id = ?", String.class,
                profileVersion.getId())).isEqualTo("originele-login");
        assertThat(jdbc.queryForObject("select port from connection_profile_version where id = ?", Integer.class,
                profileVersion.getId())).isEqualTo(22);
        assertThat(jdbc.queryForObject("select remote_directory from delivery_configuration_version where id = ?",
                String.class, dcVersion.getId())).isEqualTo("/origineel");
        assertThat(jdbc.queryForObject("select max_file_bytes from delivery_configuration_version where id = ?",
                Long.class, dcVersion.getId())).isEqualTo(1000L);
        assertThat(jdbc.queryForObject("select compare_value from delivery_configuration_file_condition where id = ?",
                String.class, condition.getId())).isEqualTo("origineel.csv");
    }

    // --- Helpers ------------------------------------------------------------------------------------------

    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 12);
    }

    private ConnectionProfileVersion version(ConnectionProfile profile, String host, int port, String username,
                                             ConnectionAuthMethod authMethod, ExternalCredential credential,
                                             Instant now) {
        return new ConnectionProfileVersion(profile, 1, host, port, username, authMethod, credential, "ssh-ed25519",
                FINGERPRINT, null, null, HASH, USER, null, now);
    }

    private void rejects(String constraint, ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    private void rejectsOneOf(List<String> constraints, ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(thrown.getMessage()).containsAnyOf(constraints.toArray(new String[0]));
    }

    private static Map<String, Object> withAuth(Map<String, Object> row, String authMethod) {
        row.put("auth_method", authMethod);
        return row;
    }

    private long insertCredential(String boundHost, String secretKind) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("credential_ref", UUID.randomUUID());
        row.put("label", "LC-1 testcredential");
        row.put("secret_kind", secretKind);
        row.put("bound_host", boundHost);
        row.put("ciphertext", CIPHERTEXT);
        row.put("encryption_key_id", "lc1-test");
        row.put("status", "ACTIVE");
        row.put("secret_updated_at", OffsetDateTime.now());
        row.put("secret_updated_by", USER);
        row.put("created_at", OffsetDateTime.now());
        row.put("created_by", USER);
        return insertInto("external_credential", row);
    }

    private static Map<String, Object> headRow(String code) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("code", code);
        row.put("name", "Testkop " + code);
        row.put("protocol", "SFTP");
        row.put("active", true);
        row.put("created_at", OffsetDateTime.now());
        row.put("created_by", USER);
        row.put("updated_at", OffsetDateTime.now());
        return row;
    }

    private long insertProfile(String code) {
        return insertInto("connection_profile", headRow(code));
    }

    private long insertConfiguration(String code) {
        Map<String, Object> row = headRow(code);
        row.remove("protocol");
        row.put("acquisition_kind", "SFTP");
        return insertInto("delivery_configuration", row);
    }

    /** Een geldige profielversie; {@code credential_host} volgt {@code host} (de regel {@code credential_host = host}). */
    private static Map<String, Object> profileVersionRow(long profileId, long credentialId, String secretKind,
                                                         String host) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("connection_profile_id", profileId);
        row.put("version_number", 1);
        row.put("host", host);
        row.put("username", "leverancier");
        row.put("auth_method", secretKind.equals("SSH_PRIVATE_KEY") ? "PRIVATE_KEY" : "PASSWORD");
        row.put("credential_id", credentialId);
        row.put("credential_secret_kind", secretKind);
        row.put("credential_host", host);
        row.put("host_key_algorithm", "ssh-ed25519");
        row.put("host_key_fingerprint_sha256", FINGERPRINT);
        row.put("config_hash", HASH);
        row.put("created_at", OffsetDateTime.now());
        row.put("created_by", USER);
        return row;
    }

    private static Map<String, Object> dcVersionRow(long configurationId, long profileVersionId, int versionNumber) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("delivery_configuration_id", configurationId);
        row.put("version_number", versionNumber);
        row.put("connection_profile_version_id", profileVersionId);
        row.put("remote_directory", "/uitgaand");
        row.put("selection_mode", "CONDITIONS");
        row.put("min_file_age_seconds", 300);
        row.put("max_file_bytes", 1_073_741_824L);
        row.put("post_fetch_action", "LEAVE");
        row.put("config_hash", HASH);
        row.put("created_at", OffsetDateTime.now());
        row.put("created_by", USER);
        return row;
    }

    private static Map<String, Object> conditionRow(long dcVersionId, int group, int sequence, String kind) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("dc_version_id", dcVersionId);
        row.put("group_number", group);
        row.put("sequence_number", sequence);
        row.put("condition_kind", kind);
        row.put("compare_value", "prijzen");
        row.put("case_sensitive", false);
        return row;
    }

    private static Map<String, Object> eventRow(String kind, String source, String changedBy, String subject) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("event_kind", kind);
        row.put("source", source);
        row.put("changed_by", changedBy);
        row.put("changed_by_subject", subject);
        row.put("changed_at", OffsetDateTime.now());
        return row;
    }

    private void insertEvent(String kind, String source, String changedBy, String subject) {
        insertInto("acquisition_config_event", eventRow(kind, source, changedBy, subject));
    }

    private long insertInto(String table, Map<String, Object> row) {
        String columns = String.join(", ", row.keySet());
        String marks = row.keySet().stream().map(column -> "?").collect(Collectors.joining(", "));
        Long id = jdbc.queryForObject("insert into " + table + " (" + columns + ") values (" + marks
                + ") returning id", Long.class, row.values().toArray());
        return id == null ? -1L : id;
    }

    /** Credential, profiel, profielversie en Leveringsconfiguratie-kop: de basis voor DC-versies en voorwaarden. */
    private Chain insertChain() {
        long credentialId = insertCredential(HOST, "SFTP_PASSWORD");
        long profileId = insertProfile(unique("P"));
        long profileVersionId = insertInto("connection_profile_version",
                profileVersionRow(profileId, credentialId, "SFTP_PASSWORD", HOST));
        long configurationId = insertConfiguration(unique("D"));
        return new Chain(configurationId, profileVersionId);
    }

    private final class Chain {
        private final long configurationId;
        private final long profileVersionId;

        private Chain(long configurationId, long profileVersionId) {
            this.configurationId = configurationId;
            this.profileVersionId = profileVersionId;
        }

        long insertVersion(int versionNumber) {
            return insertInto("delivery_configuration_version",
                    dcVersionRow(configurationId, profileVersionId, versionNumber));
        }
    }

    private Scenario scenario() {
        String prefix = "LC" + UUID.randomUUID().toString().substring(0, 8);
        SourceOrganisation organisation = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(prefix + "-ORG", prefix + "-ORG BV", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(
                new ImportDefinition(organisation, prefix + "-DEF", prefix + " catalogus", USER));
        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(prefix + "-SUP", prefix + "-SUP BV", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(
                new ImportLink(prefix + "-LINK", prefix + "-LINK koppeling", definition, supplier, "PSARF050"));
        return new Scenario(link);
    }

    private static final class Scenario {
        private final ImportLink link;

        private Scenario(ImportLink link) {
            this.link = link;
        }
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
