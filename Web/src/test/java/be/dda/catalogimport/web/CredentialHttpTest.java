package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.ConnectionProfileVersionRepository;
import be.dda.catalogimport.dao.ExternalCredentialEventRepository;
import be.dda.catalogimport.dao.ExternalCredentialRepository;
import be.dda.catalogimport.service.ActorIdentity;
import be.dda.catalogimport.service.ConflictException;
import be.dda.catalogimport.service.CredentialService;
import be.dda.catalogimport.service.SecretsNotConfiguredException;
import be.dda.catalogimport.service.SecretsService;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Bouwstap K-3: credential-endpoints ({@code docs/design/leveringsconfiguratie-design.md} par. 6/10,
 * {@code credentials-sleutelbeheer-design.md} par. 5-6; beslissingslog 2026-09-29 V1, V2, V7, L4a, L7, A5, A8 en
 * "K-3: een ingetrokken credential mag heractiveerd worden").
 *
 * <ul>
 *   <li><b>Regel:</b> geen API geeft een secret terug (V1). <b>Bewijs:</b> de exacte veldenset van elk antwoord, en
 *       geen antwoord of logregel (Spring-web op DEBUG, onze pakketten op TRACE, SQL-log aan) bevat de waarde, de
 *       ciphertext of het sleutel-ID.</li>
 *   <li><b>Regel:</b> host-binding op een genormaliseerde host (L4a). <b>Bewijs:</b> hoofdletters/rand-spaties/punt
 *       achteraan worden genormaliseerd, ongeldige vormen geven 400 {@code CREDENTIAL_HOST_INVALID}.</li>
 *   <li><b>Regel:</b> vervangen, intrekken (crypto-shred) en heractiveren met verplichte reden en append-only
 *       events. <b>Data:</b> {@code external_credential} + {@code external_credential_event}, rechtstreeks gelezen.</li>
 *   <li><b>Regel:</b> geen sleutelring = aanmaken/vervangen 409, lezen en intrekken werken (V7, A8). <b>Bewijs:</b>
 *       op serviceniveau met een niet-geconfigureerde {@link SecretsService}; op HTTP-niveau in
 *       {@code PermissionWriteEndpointsHttpTest}/{@code PermissionReadEndpointsHttpTest} (context zonder ring).</li>
 * </ul>
 * De sleutel staat enkel hier (V6), onder een vast sleutel-ID met vast materiaal: {@code secret_key_check} legt hem bij
 * de eerste run vast en elke volgende run met hetzelfde materiaal slaagt.
 */
@SpringBootTest(properties = {"catalogimport.screening.recovery-on-startup=false",
        "catalogimport.secrets.keys=" + CredentialHttpTest.KEY_ID + ":" + CredentialHttpTest.KEY,
        "catalogimport.secrets.active-key-id=" + CredentialHttpTest.KEY_ID})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class CredentialHttpTest {

    static final String KEY_ID = "k3-http-test";
    /** 32 bytes met waarde 41, base64. Enkel in de testsources (V6). */
    static final String KEY = "KSkpKSkpKSkpKSkpKSkpKSkpKSkpKSkpKSkpKSkpKSk=";

    private static final String API = "/api/catalog-import/credentials";
    private static final String USER = "an.janssens@example.test";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @TempDir
    static Path archiveRoot;
    @TempDir
    static Path localSourceRoot;

    @DynamicPropertySource
    static void directories(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
        registry.add("catalogimport.local-source.directory", () -> localSourceRoot.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SecretsService secrets;
    @Autowired
    private CredentialService credentialService;
    @Autowired
    private ExternalCredentialRepository credentials;
    @Autowired
    private ExternalCredentialEventRepository events;
    @Autowired
    private ConnectionProfileVersionRepository profileVersions;
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
        raise("org.springframework.web", Level.DEBUG);
        raise("org.hibernate.SQL", Level.DEBUG);
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

    // --- Aanmaken -------------------------------------------------------------------------------------------------

    @Test
    void createAnswers201WithoutAnySecretMaterialAndNormalisesTheHost() throws Exception {
        String plain = secretValue("create");
        String body = create("  SFTP.Leverancier-Een.TEST.  ", plain, "Nieuwe leverancier")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.boundHost").value("sftp.leverancier-een.test"))
                .andExpect(jsonPath("$.secretKind").value("SFTP_PASSWORD"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.secretSet").value(true))
                .andExpect(jsonPath("$.secretUpdatedBy").value(USER))
                .andExpect(jsonPath("$.secretUpdatedAt").isNotEmpty())
                .andExpect(jsonPath("$.profileVersionCount").value(0))
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> view = JsonPath.read(body, "$");
        assertThat(view.keySet()).containsExactlyInAnyOrder("credentialRef", "label", "secretKind", "boundHost",
                "status", "secretSet", "secretUpdatedAt", "secretUpdatedBy", "profileVersionCount");
        UUID ref = UUID.fromString((String) view.get("credentialRef"));
        Map<String, Object> row = row(ref);
        String ciphertext = (String) row.get("ciphertext");
        assertThat(ciphertext).startsWith("v1:" + KEY_ID + ":");
        assertThat(row.get("encryption_key_id")).isEqualTo(KEY_ID);
        assertThat(row.get("bound_host")).isEqualTo("sftp.leverancier-een.test");
        assertThat(row.get("created_by")).isEqualTo(USER);
        assertThat(row.get("created_by_subject")).isEqualTo("test-sub-" + USER);
        assertThat(row.get("secret_updated_by_subject")).isEqualTo("test-sub-" + USER);
        assertThat(secrets.decrypt(ciphertext, ref, "SFTP_PASSWORD")).isEqualTo(plain);
        assertThat(body).doesNotContain(plain).doesNotContain(ciphertext).doesNotContain(KEY_ID)
                .doesNotContain("ciphertext").doesNotContain("encryptionKeyId").doesNotContain("test-sub-");

        List<Map<String, Object>> trail = eventRows(ref);
        assertThat(trail).hasSize(1);
        assertThat(trail.get(0)).containsEntry("event_kind", "CREATED").containsEntry("source", "HUMAN")
                .containsEntry("changed_by", USER).containsEntry("reason", "Nieuwe leverancier")
                .containsEntry("new_key_id", KEY_ID);
        assertThat(trail.get(0).get("previous_key_id")).isNull();

        // Lezen: lijst, detail, events - dezelfde velden, nooit sleutelmateriaal.
        String detail = read(get(API + "/{ref}", ref)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Map<String, Object>>read(detail, "$").keySet()).isEqualTo(view.keySet());
        String list = read(get(API)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(list, "$[*].credentialRef")).contains(ref.toString());
        String eventsBody = read(get(API + "/{ref}/events", ref)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventKind").value("CREATED"))
                .andExpect(jsonPath("$[0].changedBy").value(USER))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Map<String, Object>>read(eventsBody, "$[0]").keySet())
                .containsExactlyInAnyOrder("id", "eventKind", "reason", "source", "changedBy", "changedAt");
        for (String answer : List.of(detail, list, eventsBody)) {
            assertThat(answer).doesNotContain(plain).doesNotContain(ciphertext).doesNotContain(KEY_ID);
        }
        assertLogsDoNotContain(plain, ciphertext.split(":")[2]);
    }

    /** Dubbele invoer: er is geen idempotentiesleutel in het ontwerp; twee aanvragen = twee losse credentials. */
    @Test
    void theSameCreateTwiceGivesTwoSeparateCredentials() throws Exception {
        String plain = secretValue("dubbel");
        UUID first = refOf(create("sftp.dubbel.test", plain, "Eerste").andExpect(status().isCreated()));
        UUID second = refOf(create("sftp.dubbel.test", plain, "Tweede").andExpect(status().isCreated()));

        assertThat(first).isNotEqualTo(second);
        assertThat(row(first).get("ciphertext")).isNotEqualTo(row(second).get("ciphertext"));
    }

    // --- Validatie: 400 zonder echo van de waarde -----------------------------------------------------------------

    @Test
    void invalidInputIsA400WithACodeAndNeverEchoesTheSecret() throws Exception {
        String plain = secretValue("invalid");
        long before = credentialCount();

        for (String host : new String[] {"", "   ", ".", "sftp host.test", "sftp://sftp.test", "user@sftp.test",
                "sftp.test/pad", "sftp.test..", "sKftp.test", "h".repeat(256)}) {
            invalid(createBody("Label", "SFTP_PASSWORD", host, plain, "Reden"), "CREDENTIAL_HOST_INVALID", plain);
        }
        invalid(createBody("Label", "SFTP_PASSWORD", null, plain, "Reden"), "CREDENTIAL_HOST_INVALID", plain);
        invalid(createBody("Label", "SFTP_PASSWORD", "sftp.test", plain, ""), "CREDENTIAL_REASON_REQUIRED", plain);
        invalid(createBody("Label", "SFTP_PASSWORD", "sftp.test", plain, "   "), "CREDENTIAL_REASON_REQUIRED", plain);
        invalid(createBody("Label", "SFTP_PASSWORD", "sftp.test", "", "Reden"), "CREDENTIAL_SECRET_REQUIRED", plain);
        invalid(createBody("Label", "SFTP_PASSWORD", "sftp.test", "   ", "Reden"), "CREDENTIAL_SECRET_REQUIRED", plain);
        invalid(createBody("Label", "SFTP_PASSWORD", "sftp.test", null, "Reden"), "CREDENTIAL_SECRET_REQUIRED", plain);
        String tooLong = plain + "x".repeat(CredentialService.MAX_SECRET_LENGTH);
        invalid(createBody("Label", "SFTP_PASSWORD", "sftp.test", tooLong, "Reden"), "CREDENTIAL_SECRET_TOO_LONG",
                plain);
        invalid(createBody("", "SFTP_PASSWORD", "sftp.test", plain, "Reden"), "CREDENTIAL_LABEL_REQUIRED", plain);
        invalid(createBody("Label", null, "sftp.test", plain, "Reden"), "CREDENTIAL_SECRET_KIND_INVALID", plain);
        invalid(createBody("Label", "WACHTWOORD", "sftp.test", plain, "Reden"), "CREDENTIAL_SECRET_KIND_INVALID",
                plain);
        invalid(createBody("Label", "SSH_PRIVATE_KEY", "sftp.test", plain, "Reden"),
                "CREDENTIAL_SECRET_KIND_NOT_SUPPORTED", plain);
        // Te lange reden: 400 (bestaande tekstregel, zonder code), nooit afgekapt.
        String longReason = json(post(API), "{\"label\":\"L\",\"secretKind\":\"SFTP_PASSWORD\",\"boundHost\":\"a.test\","
                + "\"secret\":\"" + plain + "\",\"reason\":\"" + "r".repeat(501) + "\"}")
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(longReason).doesNotContain(plain);
        // Onleesbare JSON met een niet-geciteerde waarde: vaste melding, geen echo, geen log.
        String broken = json(post(API), "{\"label\":\"L\",\"secret\": " + plain + " }")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(CatalogImportCredentialController.CODE_REQUEST_UNREADABLE))
                .andReturn().getResponse().getContentAsString();
        assertThat(broken).doesNotContain(plain);
        json(post(API), "").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(CatalogImportCredentialController.CODE_REQUEST_UNREADABLE));

        assertThat(credentialCount()).isEqualTo(before);
        assertLogsDoNotContain(plain);
    }

    // --- Vervangen --------------------------------------------------------------------------------------------------

    @Test
    void replacingWritesANewCiphertextAndAReplacedEventWithBothKeyIds() throws Exception {
        String first = secretValue("oud");
        String second = secretValue("nieuw");
        UUID ref = refOf(create("sftp.vervang.test", first, "Aanmaken").andExpect(status().isCreated()));
        String before = (String) row(ref).get("ciphertext");

        String body = json(put(API + "/{ref}/secret", ref), replaceBody(second, "Wachtwoord gewijzigd door leverancier"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.secretSet").value(true))
                .andExpect(jsonPath("$.boundHost").value("sftp.vervang.test"))
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> row = row(ref);
        assertThat(row.get("ciphertext")).isNotEqualTo(before);
        assertThat(secrets.decrypt((String) row.get("ciphertext"), ref, "SFTP_PASSWORD")).isEqualTo(second);
        List<Map<String, Object>> trail = eventRows(ref);
        assertThat(eventKinds(ref)).containsExactly("CREATED", "REPLACED");
        assertThat(trail.get(1)).containsEntry("previous_key_id", KEY_ID).containsEntry("new_key_id", KEY_ID)
                .containsEntry("changed_by", USER).containsEntry("source", "HUMAN");
        assertThat(body).doesNotContain(first).doesNotContain(second).doesNotContain(KEY_ID);

        json(put(API + "/{ref}/secret", ref), replaceBody(second, "")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDENTIAL_REASON_REQUIRED"));
        json(put(API + "/{ref}/secret", ref), replaceBody("", "Reden")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDENTIAL_SECRET_REQUIRED"));
        assertThat(eventRows(ref)).hasSize(2);
        assertLogsDoNotContain(first, second);
    }

    // --- Intrekken en heractiveren ------------------------------------------------------------------------------------

    @Test
    void revokingShredsTheValueAndASecondRevokeIsA409WithoutANewEvent() throws Exception {
        UUID ref = refOf(create("sftp.intrek.test", secretValue("weg"), "Aanmaken").andExpect(status().isCreated()));

        json(post(API + "/{ref}/revoke", ref), "{\"reason\":\"\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDENTIAL_REASON_REQUIRED"));
        json(post(API + "/{ref}/revoke", ref), "{\"reason\":\"Leverancier gestopt\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.secretSet").value(false));

        Map<String, Object> row = row(ref);
        assertThat(row.get("ciphertext")).isNull();
        assertThat(row.get("encryption_key_id")).isNull();
        assertThat(row).containsEntry("status", "REVOKED").containsEntry("revoked_by", USER)
                .containsEntry("revoked_reason", "Leverancier gestopt")
                .containsEntry("revoked_by_subject", "test-sub-" + USER);
        assertThat(row.get("revoked_at")).isNotNull();
        List<Map<String, Object>> trail = eventRows(ref);
        assertThat(eventKinds(ref)).containsExactly("CREATED", "REVOKED");
        assertThat(trail.get(1)).containsEntry("previous_key_id", KEY_ID);
        assertThat(trail.get(1).get("new_key_id")).isNull();

        json(post(API + "/{ref}/revoke", ref), "{\"reason\":\"Nog eens\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CREDENTIAL_ALREADY_REVOKED"));
        assertThat(eventRows(ref)).hasSize(2);
    }

    @Test
    void aRevokedCredentialCanBeReactivatedWithANewValue() throws Exception {
        String original = secretValue("origineel");
        String renewed = secretValue("heractief");
        UUID ref = refOf(create("sftp.heractief.test", original, "Aanmaken").andExpect(status().isCreated()));
        json(post(API + "/{ref}/revoke", ref), "{\"reason\":\"Vermoedelijk gelekt\"}").andExpect(status().isOk());

        json(put(API + "/{ref}/secret", ref), replaceBody(renewed, "Nieuw wachtwoord na lek"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.secretSet").value(true))
                .andExpect(jsonPath("$.boundHost").value("sftp.heractief.test"))
                .andExpect(jsonPath("$.credentialRef").value(ref.toString()));

        Map<String, Object> row = row(ref);
        assertThat(row).containsEntry("status", "ACTIVE").containsEntry("encryption_key_id", KEY_ID)
                .containsEntry("secret_kind", "SFTP_PASSWORD").containsEntry("bound_host", "sftp.heractief.test");
        for (String column : List.of("revoked_at", "revoked_by", "revoked_by_subject", "revoked_reason")) {
            assertThat(row.get(column)).as(column).isNull();
        }
        assertThat(secrets.decrypt((String) row.get("ciphertext"), ref, "SFTP_PASSWORD")).isEqualTo(renewed);
        List<Map<String, Object>> trail = eventRows(ref);
        assertThat(eventKinds(ref)).containsExactly("CREATED", "REVOKED", "REPLACED");
        assertThat(trail.get(2).get("previous_key_id")).isNull();
        assertThat(trail.get(2)).containsEntry("new_key_id", KEY_ID)
                .containsEntry("reason", "Nieuw wachtwoord na lek");
        // De historiek van de intrekking blijft zichtbaar in de events.
        read(get(API + "/{ref}/events", ref)).andExpect(status().isOk())
                .andExpect(jsonPath("$[1].eventKind").value("REVOKED"))
                .andExpect(jsonPath("$[1].reason").value("Vermoedelijk gelekt"))
                .andExpect(jsonPath("$[2].eventKind").value("REPLACED"));
        assertLogsDoNotContain(original, renewed);
    }

    // --- Gebruik (LC-2, K-3 afwijking 2) -----------------------------------------------------------------------------

    /**
     * Het gebruik is het aantal profielversies dat naar de credential verwijst: zichtbaar in detail en lijst, en ook
     * na intrekken (A8: het antwoord toont wat de intrekking raakt). Een andere credential blijft op 0.
     */
    @Test
    void theUsageCountsTheProfileVersionsThatReferToTheCredential() throws Exception {
        UUID used = refOf(create("sftp.gebruik.test", secretValue("gebruik"), "Aanmaken").andExpect(status().isCreated()));
        UUID unused = refOf(create("sftp.gebruik.test", secretValue("ongebruikt"), "Aanmaken")
                .andExpect(status().isCreated()));
        for (int i = 0; i < 2; i++) {
            String code = "CRED-USE-" + UUID.randomUUID().toString().substring(0, 18) + "-" + i;
            json(post("/api/catalog-import/connection-profiles"), "{\"code\":\"" + code + "\",\"name\":\"Gebruik\","
                    + "\"host\":\"sftp.gebruik.test\",\"username\":\"lev\",\"authMethod\":\"PASSWORD\","
                    + "\"credentialRef\":\"" + used + "\",\"hostKeyAlgorithm\":\"ssh-ed25519\","
                    + "\"hostKeyFingerprintSha256\":\"" + fingerprint() + "\",\"reason\":\"Gebruik\"}")
                    .andExpect(status().isCreated());
        }

        read(get(API + "/{ref}", used)).andExpect(status().isOk()).andExpect(jsonPath("$.profileVersionCount").value(2));
        read(get(API + "/{ref}", unused)).andExpect(status().isOk())
                .andExpect(jsonPath("$.profileVersionCount").value(0));
        String list = read(get(API)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Integer>>read(list, "$[?(@.credentialRef == '" + used + "')].profileVersionCount"))
                .containsExactly(2);
        assertThat(JsonPath.<List<Integer>>read(list, "$[?(@.credentialRef == '" + unused + "')].profileVersionCount"))
                .containsExactly(0);

        json(post(API + "/{ref}/revoke", used), "{\"reason\":\"Leverancier gestopt\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.profileVersionCount").value(2));
    }

    // --- Onbekend, rechten --------------------------------------------------------------------------------------------

    @Test
    void anUnknownOrMalformedRefIsA404Everywhere() throws Exception {
        for (String ref : new String[] {UUID.randomUUID().toString(), "geen-uuid"}) {
            read(get(API + "/{ref}", ref)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CREDENTIAL_NOT_FOUND"));
            read(get(API + "/{ref}/events", ref)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CREDENTIAL_NOT_FOUND"));
            json(put(API + "/{ref}/secret", ref), replaceBody("Iets-Geheims-1", "Reden"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CREDENTIAL_NOT_FOUND"));
            json(post(API + "/{ref}/revoke", ref), "{\"reason\":\"Reden\"}")
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CREDENTIAL_NOT_FOUND"));
        }
    }

    /** Leeskeuze (L7b, design par. 6): READ mag niets, ook niet lezen; er wordt niets geschreven. */
    @Test
    void aReadOnlyUserMayNeitherWriteNorReadCredentials() throws Exception {
        UUID ref = refOf(create("sftp.lezer.test", secretValue("lezer"), "Aanmaken").andExpect(status().isCreated()));
        int eventCount = eventRows(ref).size();
        RequestPostProcessor reader = as(USER, Permission.READ);

        for (MockHttpServletRequestBuilder request : List.of(get(API), get(API + "/{ref}", ref),
                get(API + "/{ref}/events", ref))) {
            mockMvc.perform(request.with(reader)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
        json(post(API), reader, createBody("L", "SFTP_PASSWORD", "sftp.test", "Geheim-Lezer-2", "R"))
                .andExpect(status().isForbidden());
        json(put(API + "/{ref}/secret", ref), reader, replaceBody("Geheim-Lezer-3", "R"))
                .andExpect(status().isForbidden());
        json(post(API + "/{ref}/revoke", ref), reader, "{\"reason\":\"R\"}").andExpect(status().isForbidden());

        assertThat(row(ref)).containsEntry("status", "ACTIVE");
        assertThat(eventRows(ref)).hasSize(eventCount);
    }

    // --- Zonder sleutelring, gelijktijdigheid (serviceniveau) --------------------------------------------------------

    @Test
    void withoutAKeyRingCreateAndReplaceAre409ButReadAndRevokeWork() throws Exception {
        UUID ref = refOf(create("sftp.zonderring.test", secretValue("ring"), "Aanmaken")
                .andExpect(status().isCreated()));
        CredentialService unconfigured = new CredentialService(new SecretsService("", ""), credentials, events,
                profileVersions, transactionManager, clock);
        ActorIdentity actor = new ActorIdentity(USER, "test-sub-" + USER);
        long before = credentialCount();

        assertThatThrownBy(() -> unconfigured.create("L", "SFTP_PASSWORD", "sftp.test", "Geheim-Ring-1", "R", actor))
                .isInstanceOf(SecretsNotConfiguredException.class);
        assertThatThrownBy(() -> unconfigured.replaceSecret(ref.toString(), "Geheim-Ring-2", "R", actor))
                .isInstanceOf(SecretsNotConfiguredException.class);
        assertThat(credentialCount()).isEqualTo(before);
        assertThat(eventRows(ref)).hasSize(1);

        assertThat(unconfigured.list()).extracting(CredentialService.CredentialView::credentialRef).contains(ref);
        assertThat(unconfigured.get(ref.toString()).status()).isEqualTo("ACTIVE");
        assertThat(unconfigured.events(ref.toString())).hasSize(1);
        assertThat(unconfigured.revoke(ref.toString(), "Sleutel kwijt", actor).status()).isEqualTo("REVOKED");
        assertThat(row(ref).get("ciphertext")).isNull();
    }

    /** Twee gelijktijdige intrekkingen: het rijslot laat er één slagen, de andere krijgt 409; exact één event. */
    @Test
    void twoConcurrentRevokesProduceExactlyOneRevokedEvent() throws Exception {
        UUID ref = refOf(create("sftp.gelijktijdig.test", secretValue("race"), "Aanmaken")
                .andExpect(status().isCreated()));
        ActorIdentity actor = new ActorIdentity(USER, "test-sub-" + USER);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> outcomes = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                outcomes.add(pool.submit(() -> {
                    go.await();
                    try {
                        return credentialService.revoke(ref.toString(), "Gelijktijdig", actor).status();
                    } catch (ConflictException conflict) {
                        return conflict.getCode();
                    }
                }));
            }
            go.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> outcome : outcomes) {
                results.add(outcome.get());
            }
            assertThat(results).containsExactlyInAnyOrder("REVOKED", "CREDENTIAL_ALREADY_REVOKED");
        } finally {
            pool.shutdownNow();
        }
        assertThat(eventKinds(ref)).containsExactly("CREATED", "REVOKED");
    }

    // --- Helpers ---------------------------------------------------------------------------------------------------

    private ResultActions create(String host, String secret, String reason) throws Exception {
        return json(post(API), createBody("Leverancier " + SEQUENCE.incrementAndGet(), "SFTP_PASSWORD", host, secret,
                reason));
    }

    private void invalid(String body, String code, String plain) throws Exception {
        String answer = json(post(API), body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(code)).andReturn().getResponse().getContentAsString();
        assertThat(answer).doesNotContain(plain);
    }

    private ResultActions json(MockHttpServletRequestBuilder request, String body) throws Exception {
        return json(request, as(USER, Permission.MANAGE), body);
    }

    private ResultActions json(MockHttpServletRequestBuilder request, RequestPostProcessor actor, String body)
            throws Exception {
        return mockMvc.perform(request.with(actor).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions read(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(as(USER, Permission.MANAGE)));
    }

    private static UUID refOf(ResultActions result) throws Exception {
        return UUID.fromString(JsonPath.read(result.andReturn().getResponse().getContentAsString(),
                "$.credentialRef"));
    }

    private static String createBody(String label, String kind, String host, String secret, String reason) {
        return "{\"label\":" + quoted(label) + ",\"secretKind\":" + quoted(kind) + ",\"boundHost\":" + quoted(host)
                + ",\"secret\":" + quoted(secret) + ",\"reason\":" + quoted(reason) + "}";
    }

    private static String replaceBody(String secret, String reason) {
        return "{\"secret\":" + quoted(secret) + ",\"reason\":" + quoted(reason) + "}";
    }

    /** Minimale JSON-string; de testwaarden bevatten geen aanhalingstekens of backslashes. */
    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    /** Een geldige OpenSSH-vingerafdruk ({@code SHA256:} + 32 willekeurige bytes, base64 zonder opvulling). */
    private static String fingerprint() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Een waarde die nergens toevallig voorkomt, zodat elke vondst in een antwoord of log een echt lek is. */
    private static String secretValue(String hint) {
        return "K3Leak-" + hint + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    private Map<String, Object> row(UUID ref) {
        return jdbc.queryForMap("select * from external_credential where credential_ref = ?", ref);
    }

    private List<Map<String, Object>> eventRows(UUID ref) {
        return jdbc.queryForList("select e.* from external_credential_event e join external_credential c "
                + "on c.id = e.credential_id where c.credential_ref = ? order by e.id", ref);
    }

    private long credentialCount() {
        return jdbc.queryForObject("select count(*) from external_credential", Long.class);
    }

    private void raise(String loggerName, Level level) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerName);
        raised.add(logger);
        originalLevels.add(logger.getLevel());
        logger.setLevel(level);
    }

    private void assertLogsDoNotContain(String... forbidden) {
        assertThat(this.log.list).as("captured log lines").isNotEmpty();
        for (ILoggingEvent event : this.log.list) {
            StringBuilder text = new StringBuilder(String.valueOf(event.getFormattedMessage()));
            for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                text.append('\n').append(proxy.getClassName()).append(": ").append(proxy.getMessage());
            }
            for (String value : forbidden) {
                assertThat(text.toString()).as("log line of " + event.getLoggerName()).doesNotContain(value);
            }
        }
    }

    private List<Object> eventKinds(UUID ref) {
        return eventRows(ref).stream().map(e -> e.get("event_kind")).toList();
    }
}
