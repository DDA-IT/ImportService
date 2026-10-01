package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.AcquisitionConfigEventRepository;
import be.dda.catalogimport.dao.ConnectionProfileVersionRepository;
import be.dda.catalogimport.dao.DeliveryConfigurationFileConditionRepository;
import be.dda.catalogimport.dao.DeliveryConfigurationVersionRepository;
import be.dda.catalogimport.dao.ExternalCredentialRepository;
import be.dda.catalogimport.domain.ExternalCredential;
import be.dda.catalogimport.domain.ExternalCredentialSecretKind;
import be.dda.catalogimport.service.ActorIdentity;
import be.dda.catalogimport.service.ConflictException;
import be.dda.catalogimport.service.ConnectionProfileService;
import be.dda.catalogimport.service.ConnectionProfileService.NewConnectionProfile;
import be.dda.catalogimport.service.ConnectionTestService;
import be.dda.catalogimport.service.CredentialService;
import be.dda.catalogimport.service.DeliveryConfigurationService;
import be.dda.catalogimport.service.DeliveryConfigurationService.ConditionGroupInput;
import be.dda.catalogimport.service.DeliveryConfigurationService.ConditionInput;
import be.dda.catalogimport.service.DeliveryConfigurationService.NewDeliveryConfiguration;
import be.dda.catalogimport.service.FetchHostPolicy;
import be.dda.catalogimport.service.FetchOutcomeCodes;
import be.dda.catalogimport.service.SecretsService;
import be.dda.catalogimport.service.SftpConnector;
import be.dda.catalogimport.testsupport.SftpTestServer;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Bouwstap K-4a: hostsleutelscan en verbindingstests over HTTP tegen een embedded MINA SFTP-server
 * ({@code docs/design/leveringsconfiguratie-design.md} par. 5; beslissingslog 2026-09-29 L4, L5, L7, V1, V5, V7, A16).
 *
 * <ul>
 *   <li><b>Regel:</b> antwoord altijd 200 {@code {outcome, failureCode, presentedHostKeyFingerprint, matchedFiles,
 *       truncated}} bij een uitkomst aan de kant van de server. <b>Bewijs:</b> OK, mismatch, verkeerd wachtwoord,
 *       ingetrokken en onleesbare credential, host buiten de allowlist, ontbrekende map.</li>
 *   <li><b>Regel:</b> elke scan/test schrijft een {@code acquisition_config_event} ({@code HOST_KEY_SCANNED} /
 *       {@code CONNECTION_TESTED}, HUMAN, actor uit het token, {@code outcome_code}).</li>
 *   <li><b>Regel V1:</b> het wachtwoord komt in geen antwoord, event of logregel voor (onze pakketten op TRACE, de
 *       MINA-client op DEBUG, Spring-web op DEBUG) - ook niet als digest.</li>
 *   <li><b>Regel V7:</b> zonder sleutelring is een test 409 {@code SECRETS_NOT_CONFIGURED} (serviceniveau).</li>
 * </ul>
 * De sleutel staat enkel hier (V6), onder een vast sleutel-ID met vast materiaal ({@code secret_key_check} legt hem bij
 * de eerste run vast). Fixtures lopen via de bestaande services (credential, profiel, DC), zoals een mens ze aanmaakt.
 */
@SpringBootTest(properties = {"catalogimport.screening.recovery-on-startup=false",
        "catalogimport.secrets.keys=" + ConnectionTestHttpTest.KEY_ID + ":" + ConnectionTestHttpTest.KEY,
        "catalogimport.secrets.active-key-id=" + ConnectionTestHttpTest.KEY_ID,
        "catalogimport.fetch.allowed-hosts=127.0.0.1",
        "catalogimport.fetch.allow-loopback=true",
        "catalogimport.fetch.connect-timeout=PT3S",
        "catalogimport.fetch.auth-timeout=PT5S",
        "catalogimport.fetch.idle-timeout=PT10S"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ConnectionTestHttpTest {

    static final String KEY_ID = "k4a-http-test";
    /** 32 bytes met waarde 42, base64. Enkel in de testsources (V6). */
    static final String KEY = "KioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKio=";

    private static final String API = "/api/catalog-import";
    private static final String USER = "an.janssens@example.test";
    private static final ActorIdentity ACTOR = new ActorIdentity(USER, "test-sub-" + USER);
    /** Waarden die nergens toevallig voorkomen, zodat elke vondst een echt lek is. */
    private static final String PASSWORD = "K4a-Http-Wachtw00rd-9c1e!";
    private static final String WRONG_PASSWORD = "K4a-Fout-Wachtw00rd-3b7d!";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @TempDir
    static Path archiveRoot;
    @TempDir
    static Path localSourceRoot;
    @TempDir
    static Path sftpRoot;

    private static KeyPair hostKey;
    private static SftpTestServer server;

    @DynamicPropertySource
    static void directories(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
        registry.add("catalogimport.local-source.directory", () -> localSourceRoot.toString());
    }

    @BeforeAll
    static void startServer() throws Exception {
        Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(10, ChronoUnit.HOURS);
        Path in = Files.createDirectories(sftpRoot.resolve("in"));
        file(in, "oud.csv", base);
        file(in, "PRIJS_2026.csv", base.plus(2, ChronoUnit.HOURS));
        file(in, "lees-mij.txt", base.plus(3, ChronoUnit.HOURS));
        file(in, "voorraad.CSV", base.plus(4, ChronoUnit.HOURS));
        Files.createDirectories(in.resolve("submap.csv"));
        Path many = Files.createDirectories(sftpRoot.resolve("veel"));
        for (int i = 0; i <= 100; i++) {
            file(many, String.format("p%03d.csv", i), base.plus(i, ChronoUnit.MINUTES));
        }
        hostKey = SftpTestServer.primaryHostKey();
        server = SftpTestServer.start(sftpRoot, PASSWORD, hostKey);
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (server != null) {
            server.close();
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CredentialService credentialService;
    @Autowired
    private ConnectionProfileService profileService;
    @Autowired
    private DeliveryConfigurationService deliveryConfigurationService;
    @Autowired
    private ExternalCredentialRepository credentials;
    @Autowired
    private FetchHostPolicy hostPolicy;
    @Autowired
    private SftpConnector connector;
    @Autowired
    private ConnectionProfileVersionRepository profileVersions;
    @Autowired
    private DeliveryConfigurationVersionRepository deliveryConfigurationVersions;
    @Autowired
    private DeliveryConfigurationFileConditionRepository conditions;
    @Autowired
    private AcquisitionConfigEventRepository events;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private Clock clock;

    private Logger root;
    private ListAppender<ILoggingEvent> log;
    private final List<Logger> raised = new ArrayList<>();
    private final List<Level> originalLevels = new ArrayList<>();

    @BeforeEach
    void captureEveryLogLine() {
        raise("be.dda.catalogimport", Level.TRACE);
        raise("org.apache.sshd.client", Level.DEBUG);
        raise("org.springframework.web", Level.DEBUG);
        this.root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        this.log = new ListAppender<>();
        this.log.start();
        this.root.addAppender(this.log);
    }

    @AfterEach
    void stopCapturing() {
        this.root.detachAppender(this.log);
        for (int i = 0; i < raised.size(); i++) {
            raised.get(i).setLevel(originalLevels.get(i));
        }
    }

    // --- Scan --------------------------------------------------------------------------------------------------------

    @Test
    void theScanReturnsTheFingerprintOfTheServerAndIsAudited() throws Exception {
        long lastEvent = lastEventId();
        int attempts = server.passwordAttempts();

        String body = scan("{\"host\":\"127.0.0.1\",\"port\":" + server.port() + "}", as(USER, Permission.MANAGE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("OK"))
                .andExpect(jsonPath("$.hostKeyAlgorithm").value(SftpTestServer.algorithmOf(hostKey)))
                .andExpect(jsonPath("$.hostKeyFingerprintSha256").value(fingerprint()))
                .andReturn().getResponse().getContentAsString();

        assertThat(readOrNull(body, "$.failureCode")).isNull();
        assertThat(server.passwordAttempts()).as("a scan never logs in").isEqualTo(attempts);
        List<Map<String, Object>> rows = eventsAfter(lastEvent);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("event_kind", "HOST_KEY_SCANNED").containsEntry("outcome_code", "OK")
                .containsEntry("source", "HUMAN").containsEntry("changed_by", USER)
                .containsEntry("changed_by_subject", "test-sub-" + USER);
        assertThat(rows.get(0).get("profile_version_id")).isNull();
        assertThat((String) rows.get(0).get("detail")).contains(fingerprint()).contains("127.0.0.1:" + server.port());
    }

    @Test
    void aScanOutsideTheAllowlistIs409AndAuditedAndAnInvalidHostIs400WithoutEvent() throws Exception {
        long lastEvent = lastEventId();

        scan("{\"host\":\"sftp.elders.test\",\"port\":22}", as(USER, Permission.MANAGE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(FetchOutcomeCodes.FETCH_HOST_NOT_ALLOWED));
        List<Map<String, Object>> rows = eventsAfter(lastEvent);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("event_kind", "HOST_KEY_SCANNED")
                .containsEntry("outcome_code", FetchOutcomeCodes.FETCH_HOST_NOT_ALLOWED);

        long afterRefusal = lastEventId();
        scan("{\"host\":\"sftp://x\"}", as(USER, Permission.MANAGE)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ConnectionProfileService.CODE_HOST_INVALID));
        scan("{\"host\":\"127.0.0.1\",\"port\":70000}", as(USER, Permission.MANAGE)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ConnectionProfileService.CODE_PORT_INVALID));
        assertThat(eventsAfter(afterRefusal)).isEmpty();
    }

    // --- Profieltest ---------------------------------------------------------------------------------------------------

    @Test
    void aProfileTestWithThePinnedKeyAndThePasswordIsOk() throws Exception {
        long versionId = profileVersion(PASSWORD, fingerprint());

        String body = testProfile(versionId).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("OK"))
                .andExpect(jsonPath("$.presentedHostKeyFingerprint").value(fingerprint()))
                .andExpect(jsonPath("$.truncated").value(false))
                .andReturn().getResponse().getContentAsString();

        assertThat(readOrNull(body, "$.failureCode")).isNull();
        assertThat(readOrNull(body, "$.matchedFiles")).isNull();
        List<Map<String, Object>> rows = eventsOfProfileVersion(versionId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("event_kind", "CONNECTION_TESTED").containsEntry("outcome_code", "OK")
                .containsEntry("source", "HUMAN").containsEntry("changed_by", USER);
        assertThat(rows.get(0).get("dc_version_id")).isNull();
        assertThat(rows.get(0).get("task_id")).isNull();
    }

    @Test
    void anotherPinnedKeyIsAMismatchWithoutAnyLoginAttempt() throws Exception {
        long versionId = profileVersion(PASSWORD, randomFingerprint());
        int attempts = server.passwordAttempts();

        testProfile(versionId).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value(FetchOutcomeCodes.SFTP_HOST_KEY_MISMATCH))
                .andExpect(jsonPath("$.presentedHostKeyFingerprint").value(fingerprint()));

        assertThat(server.passwordAttempts()).isEqualTo(attempts);
        Map<String, Object> event = eventsOfProfileVersion(versionId).get(0);
        assertThat(event).containsEntry("outcome_code", FetchOutcomeCodes.SFTP_HOST_KEY_MISMATCH);
        assertThat((String) event.get("detail")).contains(fingerprint());
    }

    @Test
    void aWrongPasswordIsAnAuthenticationFailure() throws Exception {
        long versionId = profileVersion(WRONG_PASSWORD, fingerprint());

        testProfile(versionId).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value(FetchOutcomeCodes.SFTP_AUTHENTICATION_FAILED))
                .andExpect(jsonPath("$.presentedHostKeyFingerprint").value(fingerprint()));
        assertThat(eventsOfProfileVersion(versionId).get(0))
                .containsEntry("outcome_code", FetchOutcomeCodes.SFTP_AUTHENTICATION_FAILED);
    }

    @Test
    void aRevokedCredentialIsReportedWithoutContactingTheServer() throws Exception {
        CredentialService.CredentialView credential = credential("127.0.0.1", PASSWORD);
        long versionId = profileVersion(credential.credentialRef(), "127.0.0.1", fingerprint());
        credentialService.revoke(credential.credentialRef().toString(), "Leverancier gestopt", ACTOR);
        int attempts = server.passwordAttempts();

        String body = testProfile(versionId).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value(FetchOutcomeCodes.CREDENTIAL_REVOKED))
                .andReturn().getResponse().getContentAsString();

        assertThat(readOrNull(body, "$.presentedHostKeyFingerprint")).as("the server was not contacted").isNull();
        assertThat(server.passwordAttempts()).isEqualTo(attempts);
        assertThat(eventsOfProfileVersion(versionId).get(0)).containsEntry("outcome_code",
                FetchOutcomeCodes.CREDENTIAL_REVOKED);
    }

    @Test
    void anUndecryptableCredentialIsReportedAfterTheHostKeyAndNeverSkipped() throws Exception {
        byte[] payload = new byte[40];
        new SecureRandom().nextBytes(payload);
        ExternalCredential unreadable = credentials.saveAndFlush(new ExternalCredential(UUID.randomUUID(),
                "K4a onleesbaar " + SEQUENCE.incrementAndGet(), ExternalCredentialSecretKind.SFTP_PASSWORD, "127.0.0.1",
                "v1:k4a-onbekend:" + Base64.getEncoder().encodeToString(payload), "k4a-onbekend", USER, null,
                Instant.now()));
        long versionId = profileVersion(unreadable.getCredentialRef(), "127.0.0.1", fingerprint());
        int attempts = server.passwordAttempts();

        testProfile(versionId).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value(FetchOutcomeCodes.CREDENTIAL_UNDECRYPTABLE))
                .andExpect(jsonPath("$.presentedHostKeyFingerprint").value(fingerprint()));
        assertThat(server.passwordAttempts()).isEqualTo(attempts);
    }

    @Test
    void aProfileWhoseHostIsOutsideTheAllowlistFailsAsAnOutcome() throws Exception {
        CredentialService.CredentialView credential = credential("sftp.elders.test", PASSWORD);
        long versionId = profileVersion(credential.credentialRef(), "sftp.elders.test", fingerprint());

        testProfile(versionId).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value(FetchOutcomeCodes.FETCH_HOST_NOT_ALLOWED));
        assertThat(eventsOfProfileVersion(versionId).get(0)).containsEntry("outcome_code",
                FetchOutcomeCodes.FETCH_HOST_NOT_ALLOWED);
    }

    // --- DC-test -------------------------------------------------------------------------------------------------------

    @Test
    void aDeliveryConfigurationTestListsTheMatchingFilesNewestFirstWithoutDownloading() throws Exception {
        long profileVersionId = profileVersion(PASSWORD, fingerprint());
        long versionId = deliveryConfigurationVersion(profileVersionId, "/in", "CONDITIONS",
                List.of(new ConditionGroupInput(List.of(new ConditionInput("EXTENSION_IS", "csv", false)))));
        long archivedBefore = countFiles(archiveRoot);

        String body = testDeliveryConfiguration(versionId).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("OK"))
                .andExpect(jsonPath("$.presentedHostKeyFingerprint").value(fingerprint()))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.matchedFiles.length()").value(3))
                .andExpect(jsonPath("$.matchedFiles[0].name").value("voorraad.CSV"))
                .andExpect(jsonPath("$.matchedFiles[1].name").value("PRIJS_2026.csv"))
                .andExpect(jsonPath("$.matchedFiles[2].name").value("oud.csv"))
                .andExpect(jsonPath("$.matchedFiles[1].size").value(contentOf("PRIJS_2026.csv").length))
                .andReturn().getResponse().getContentAsString();

        assertThat(readOrNull(body, "$.matchedFiles[0].modifiedAt")).isNotNull();
        assertThat(countFiles(archiveRoot)).as("no download, nothing archived").isEqualTo(archivedBefore);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from acquisition_config_event where dc_version_id = ? order by id", versionId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("event_kind", "CONNECTION_TESTED").containsEntry("outcome_code", "OK");
        assertThat(((Number) rows.get(0).get("profile_version_id")).longValue()).isEqualTo(profileVersionId);
        assertThat((String) rows.get(0).get("detail")).contains("5 entries listed, 3 matching");
    }

    @Test
    void moreThan100MatchesAreCutToTheNewest100AndMarkedTruncated() throws Exception {
        long versionId = deliveryConfigurationVersion(profileVersion(PASSWORD, fingerprint()), "/veel", "ALL_FILES",
                null);

        testDeliveryConfiguration(versionId).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("OK"))
                .andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.matchedFiles.length()").value(ConnectionTestService.MAX_MATCHED_FILES))
                .andExpect(jsonPath("$.matchedFiles[0].name").value("p100.csv"))
                .andExpect(jsonPath("$.matchedFiles[99].name").value("p001.csv"));
    }

    @Test
    void aMissingRemoteDirectoryIsAnOutcome() throws Exception {
        long versionId = deliveryConfigurationVersion(profileVersion(PASSWORD, fingerprint()), "/bestaat-niet",
                "ALL_FILES", null);

        String body = testDeliveryConfiguration(versionId).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value(FetchOutcomeCodes.REMOTE_DIRECTORY_NOT_FOUND))
                .andReturn().getResponse().getContentAsString();
        assertThat(readOrNull(body, "$.matchedFiles")).isNull();
    }

    // --- Voorwaarden vóór de verbinding ------------------------------------------------------------------------------

    @Test
    void unknownVersionsAre404() throws Exception {
        testProfile(999_999_999L).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONNECTION_PROFILE_VERSION_NOT_FOUND"));
        testDeliveryConfiguration(999_999_999L).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DELIVERY_CONFIGURATION_VERSION_NOT_FOUND"));
    }

    /** V7: zonder sleutelring kan geen wachtwoord ontsleuteld worden; 409, niets gecontacteerd, geen event. */
    @Test
    void withoutAKeyRingATestIs409SecretsNotConfigured() throws Exception {
        long versionId = profileVersion(PASSWORD, fingerprint());
        ConnectionTestService withoutRing = new ConnectionTestService(hostPolicy, connector, new SecretsService("", ""),
                profileVersions, deliveryConfigurationVersions, conditions, credentials, events, transactionManager,
                clock);
        int attempts = server.passwordAttempts();

        Throwable thrown = catchThrowable(() -> withoutRing.testProfileVersion(versionId, ACTOR));

        assertThat(thrown).isInstanceOf(ConflictException.class);
        assertThat(((ConflictException) thrown).getCode()).isEqualTo("SECRETS_NOT_CONFIGURED");
        assertThat(eventsOfProfileVersion(versionId)).isEmpty();
        assertThat(server.passwordAttempts()).isEqualTo(attempts);
    }

    /** L7: READ mag niet scannen of testen; er wordt niets gecontacteerd en niets vastgelegd. */
    @Test
    void aReadOnlyUserGets403AndNothingIsContacted() throws Exception {
        long versionId = profileVersion(PASSWORD, fingerprint());
        long lastEvent = lastEventId();
        int attempts = server.passwordAttempts();
        RequestPostProcessor reader = as(USER, Permission.READ);

        scan("{\"host\":\"127.0.0.1\",\"port\":" + server.port() + "}", reader).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        mockMvc.perform(post(API + "/connection-profile-versions/{id}/test", versionId).with(reader))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        mockMvc.perform(post(API + "/delivery-configuration-versions/{id}/test", 1L).with(reader))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));

        assertThat(eventsAfter(lastEvent)).isEmpty();
        assertThat(server.passwordAttempts()).isEqualTo(attempts);
    }

    // --- Lekbewijs (V1) ---------------------------------------------------------------------------------------------

    @Test
    void noAnswerEventOrLogLineContainsThePasswordOrADigestOfIt() throws Exception {
        long okVersion = profileVersion(PASSWORD, fingerprint());
        long wrongVersion = profileVersion(WRONG_PASSWORD, fingerprint());
        long dcVersion = deliveryConfigurationVersion(okVersion, "/in", "ALL_FILES", null);
        long lastEvent = lastEventId();

        List<String> answers = new ArrayList<>();
        answers.add(scan("{\"host\":\"127.0.0.1\",\"port\":" + server.port() + "}", as(USER, Permission.MANAGE))
                .andReturn().getResponse().getContentAsString());
        answers.add(testProfile(okVersion).andReturn().getResponse().getContentAsString());
        answers.add(testProfile(wrongVersion).andReturn().getResponse().getContentAsString());
        answers.add(testDeliveryConfiguration(dcVersion).andReturn().getResponse().getContentAsString());

        List<String> forbidden = List.of(PASSWORD, WRONG_PASSWORD, digest(PASSWORD), digest(WRONG_PASSWORD));
        for (String answer : answers) {
            for (String value : forbidden) {
                assertThat(answer).doesNotContain(value);
            }
            assertThat(answer).doesNotContain("SSH-2.0").doesNotContain("ciphertext");
        }
        List<Map<String, Object>> rows = eventsAfter(lastEvent);
        assertThat(rows).hasSize(4);
        for (Map<String, Object> row : rows) {
            for (Object column : row.values()) {
                for (String value : forbidden) {
                    assertThat(String.valueOf(column)).doesNotContain(value);
                }
                assertThat(String.valueOf(column)).doesNotContain("SSH-2.0");
            }
        }
        assertThat(this.log.list).as("captured log lines").isNotEmpty();
        assertLogsDoNotContain(forbidden);
    }

    // --- Helpers -------------------------------------------------------------------------------------------------------

    private ResultActions scan(String body, RequestPostProcessor actor) throws Exception {
        return mockMvc.perform(post(API + "/connection-profiles/host-key-scan").with(actor)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions testProfile(long versionId) throws Exception {
        return mockMvc.perform(post(API + "/connection-profile-versions/{id}/test", versionId)
                .with(as(USER, Permission.MANAGE)));
    }

    private ResultActions testDeliveryConfiguration(long versionId) throws Exception {
        return mockMvc.perform(post(API + "/delivery-configuration-versions/{id}/test", versionId)
                .with(as(USER, Permission.MANAGE)));
    }

    private CredentialService.CredentialView credential(String host, String password) {
        return credentialService.create("K4a " + SEQUENCE.incrementAndGet(), "SFTP_PASSWORD", host, password,
                "Verbindingstest", ACTOR);
    }

    /** Credential op 127.0.0.1 met dit wachtwoord + profielversie met deze vastgepinde vingerafdruk. */
    private long profileVersion(String password, String pinnedFingerprint) {
        return profileVersion(credential("127.0.0.1", password).credentialRef(), "127.0.0.1", pinnedFingerprint);
    }

    private long profileVersion(UUID credentialRef, String host, String pinnedFingerprint) {
        return profileService.create(new NewConnectionProfile(unique("CP"), "Verbindingstest", host, server.port(),
                SftpTestServer.USERNAME, "PASSWORD", credentialRef.toString(), SftpTestServer.algorithmOf(hostKey),
                pinnedFingerprint, "Verbindingstest"), ACTOR).versions().get(0).id();
    }

    private long deliveryConfigurationVersion(long profileVersionId, String directory, String mode,
                                              List<ConditionGroupInput> groups) {
        return deliveryConfigurationService.create(new NewDeliveryConfiguration(unique("DC"), "Verbindingstest",
                profileVersionId, directory, mode, groups, 0, null, "Verbindingstest"), ACTOR).versions().get(0).id();
    }

    private static String fingerprint() {
        return SftpTestServer.expectedFingerprint(hostKey.getPublic());
    }

    private static String randomFingerprint() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Zoals de library een wachtwoord zou "vingerafdrukken": SHA-256 over de UTF-8-bytes, base64 zonder opvulling. */
    private static String digest(String value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().withoutPadding().encodeToString(hash);
    }

    private static void file(Path directory, String name, Instant modified) throws Exception {
        Path file = directory.resolve(name);
        Files.write(file, contentOf(name));
        Files.setLastModifiedTime(file, FileTime.from(modified));
    }

    private static byte[] contentOf(String name) {
        return ("inhoud van " + name).getBytes(StandardCharsets.UTF_8);
    }

    private static long countFiles(Path directory) throws Exception {
        try (var stream = Files.walk(directory)) {
            return stream.filter(Files::isRegularFile).count();
        }
    }

    private static Object readOrNull(String body, String path) {
        try {
            return JsonPath.read(body, path);
        } catch (PathNotFoundException absent) {
            return null;
        }
    }

    private long lastEventId() {
        Long id = jdbc.queryForObject("select max(id) from acquisition_config_event", Long.class);
        return id == null ? 0L : id;
    }

    private List<Map<String, Object>> eventsAfter(long id) {
        return jdbc.queryForList("select * from acquisition_config_event where id > ? order by id", id);
    }

    private List<Map<String, Object>> eventsOfProfileVersion(long versionId) {
        return jdbc.queryForList("select * from acquisition_config_event where profile_version_id = ? order by id",
                versionId);
    }

    private static String unique(String prefix) {
        return "K4" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
    }

    private void raise(String loggerName, Level level) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerName);
        raised.add(logger);
        originalLevels.add(logger.getLevel());
        logger.setLevel(level);
    }

    private void assertLogsDoNotContain(List<String> forbidden) {
        for (ILoggingEvent event : this.log.list) {
            StringBuilder text = new StringBuilder(event.getFormattedMessage() == null ? ""
                    : event.getFormattedMessage());
            for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                text.append('\n').append(proxy.getClassName()).append(": ").append(proxy.getMessage());
                for (StackTraceElementProxy frame : proxy.getStackTraceElementProxyArray()) {
                    text.append('\n').append(frame.getSTEAsString());
                }
            }
            for (String value : forbidden) {
                assertThat(text.toString()).as("log line of " + event.getLoggerName()).doesNotContain(value);
            }
        }
    }
}
