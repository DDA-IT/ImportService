package be.dda.catalogimport.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.domain.ExternalCredentialSecretKind;
import be.dda.catalogimport.service.ActorIdentity;
import be.dda.catalogimport.service.CredentialService;
import be.dda.catalogimport.service.LegacyConfigReceiptService.PushResult;
import be.dda.catalogimport.service.LegacySecret;
import be.dda.catalogimport.service.SecretsService;
import be.dda.catalogimport.testsupport.TestTechnicalClientJwt;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Slice S4 van {@code docs/design/legacy-config-bundle-design.md} (par. 2 "Het secret door de request" en "Credential
 * bij ontvangst", par. 8 rij S4, aannames A1/A2/A7); contract {@code docs/design/legacy-config-bundle-v1.md} par. 2.1;
 * beslissingslog 2026-10-20 ("secrets leesbaar over TLS; CatalogImport versleutelt meteen").
 *
 * <h2>Business rule → wat hier bewezen wordt</h2>
 * <ul>
 *   <li><b>Nieuwe ontvangst, SFTP met geldige host, herkomst zonder credential</b> → {@code STORED}: één credential
 *       {@code SFTP_PASSWORD} gebonden aan de genormaliseerde host, label {@code Legacy <dossier>/<interface>}, reden met
 *       het ontvangst-id, {@code created_by = svc:<azp>}, één {@code CREATED}-event met bron SYSTEM en zonder
 *       {@code changed_by}; {@code credential_id} op ontvangst en herkomst.</li>
 *   <li><b>Herkomst heeft al een credential</b> (ook ingetrokken) → {@code DISCARDED_ALREADY_PRESENT}: geen tweede
 *       credential, de bestaande ongemoeid (A1).</li>
 *   <li><b>FTP of onbekend protocol</b> → {@code DISCARDED_PROTOCOL_UNSUPPORTED}; <b>geen bestemming of ongeldige
 *       host</b> → {@code DISCARDED_NO_HOST}; telkens geen credential en geen herkomstrij.</li>
 *   <li><b>{@code UNCHANGED}</b> (S4-vervolg, beslissingslog 2026-10-21): heeft de herkomst al een credential, of is
 *       het geen SFTP met geldige host → secret weggegooid, niets geschreven of aangeraakt. Anders (bv. de inhoud kwam
 *       eerst zonder secret) → toch één credential, op de herkomst en op de laatste ontvangst ({@code STORED}); een
 *       volgende identieke push vervangt niets.</li>
 *   <li><b>Gelijktijdig:</b> hoogstens één credential per herkomst, ook bij gelijktijdige {@code UNCHANGED}-pushes.</li>
 *   <li><b>Userinfo verdeeld over {@code FTP}+{@code FTP2}</b> (contract par. 2.1 regel 3b/4): onbewerkt → 400
 *       {@code SECRET_IN_DETAILS}; zoals de job redigeert → aanvaard.</li>
 *   <li><b>Lekvrij:</b> de canary staat enkel als ciphertext in {@code external_credential} (ontsleutelbaar tot exact de
 *       canary) en nergens anders: niet in {@code bundle_json}/{@code apply_report_json}, niet in herkomst- of
 *       eventrijen, niet in een HTTP-antwoord (push, detail, preview, APPLY), niet in een logregel (onze pakketten,
 *       Spring Security/Web en de Hibernate-bind-log op TRACE) en niet in een exceptie.</li>
 *   <li><b>PREVIEW/APPLY:</b> {@code planned.credential} toont {@code credentialRef} en {@code secretOutcome}; APPLY
 *       maakt geen verbindingsprofiel; {@code CONNECTION_NOT_MIGRATED} draagt het voorstel host/poort/map/login +
 *       {@code credentialRef}.</li>
 * </ul>
 * De weigering zonder sleutelring (409 {@code SECRETS_NOT_CONFIGURED}) en een ander label (400) staan in
 * {@code LegacyConfigReceiptHttpTest} (context zonder sleutelring).
 */
@TestPropertySource(properties = {
        "catalogimport.technical-clients.legacy-config.audience=" + TestTechnicalClientJwt.AUDIENCE,
        "catalogimport.technical-clients.legacy-config.client-id=" + TestTechnicalClientJwt.CLIENT_ID,
        "catalogimport.technical-clients.legacy-config.required-role=" + TestTechnicalClientJwt.ROLE,
        // Zelfde testsleutel als CredentialHttpTest (secret_key_check legt het materiaal per sleutel-ID vast).
        "catalogimport.secrets.keys=" + CredentialHttpTest.KEY_ID + ":" + CredentialHttpTest.KEY,
        "catalogimport.secrets.active-key-id=" + CredentialHttpTest.KEY_ID,
        // 4 threads in de gelijktijdigheidstest; klein genoeg voor de 50 verbindingen van de lokale PostgreSQL.
        "spring.datasource.hikari.maximum-pool-size=4"})
class LegacyConfigSecretLeakTest extends LegacyConfigS2TestBase {

    private static final String PUSH = RECEIPTS;
    private static final String SERVICE_ACTOR = "svc:" + TestTechnicalClientJwt.CLIENT_ID;
    private static final ActorIdentity CLIENT = new ActorIdentity(SERVICE_ACTOR, TestTechnicalClientJwt.SUBJECT);

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SecretsService secrets;

    private MockMvc bareMvc;
    private Logger root;
    private ListAppender<ILoggingEvent> log;
    private final List<Logger> raised = new ArrayList<>();
    private final List<Level> originalLevels = new ArrayList<>();
    private final List<String> responses = new ArrayList<>();

    @BeforeEach
    void captureEverything() {
        bareMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        raise("be.dda.catalogimport", Level.TRACE);
        raise("org.springframework.security", Level.TRACE);
        raise("org.springframework.web", Level.TRACE);
        raise("org.springframework.transaction", Level.TRACE);
        raise("org.hibernate.SQL", Level.DEBUG);
        raise("org.hibernate.orm.jdbc.bind", Level.TRACE);
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        log = new ListAppender<>();
        log.start();
        root.addAppender(log);
    }

    @AfterEach
    void stopCapturing() {
        root.detachAppender(log);
        for (int i = 0; i < raised.size(); i++) {
            raised.get(i).setLevel(originalLevels.get(i));
        }
    }

    // --- STORED -------------------------------------------------------------------------------------------------

    @Test
    void aNewSftpReceiptStoresTheSecretEncryptedAsASystemCredentialAndTheCanaryAppearsNowhereElse()
            throws Exception {
        String canary = canary();
        ObjectNode bundle = withSecret(golden(), canary);
        // Host met hoofdletters en punt achteraan: gebonden in genormaliseerde vorm.
        detail(bundle, "FTP", "sftp://SFTP.Example.TEST./exports/");

        long id = created(push(bundle));

        Map<String, Object> receipt = receiptRow(id);
        assertThat(receipt.get("secret_received")).isEqualTo(true);
        assertThat(receipt.get("secret_outcome")).isEqualTo("STORED");
        long credentialId = ((Number) receipt.get("credential_id")).longValue();
        Map<String, Object> origin = originRow(bundle);
        assertThat(((Number) origin.get("credential_id")).longValue()).isEqualTo(credentialId);
        assertThat(origin.get("import_link_id")).isNull();
        assertThat(origin.get("updated_at")).isNotNull();

        Map<String, Object> credential = jdbc.queryForMap("select * from external_credential where id = ?",
                credentialId);
        UUID ref = (UUID) credential.get("credential_ref");
        assertThat(credential.get("label")).isEqualTo("Legacy " + dossier(bundle) + "/GOLDEN_ART");
        assertThat(credential.get("secret_kind")).isEqualTo("SFTP_PASSWORD");
        assertThat(credential.get("bound_host")).isEqualTo("sftp.example.test");
        assertThat(credential.get("status")).isEqualTo("ACTIVE");
        assertThat(credential.get("created_by")).isEqualTo(SERVICE_ACTOR);
        assertThat(credential.get("created_by_subject")).isEqualTo(TestTechnicalClientJwt.SUBJECT);
        assertThat(credential.get("secret_updated_by")).isEqualTo(SERVICE_ACTOR);
        assertThat(credential.get("encryption_key_id")).isEqualTo(CredentialHttpTest.KEY_ID);
        String ciphertext = (String) credential.get("ciphertext");
        assertThat(ciphertext).startsWith("v1:" + CredentialHttpTest.KEY_ID + ":").doesNotContain(canary);
        // Enkel versleuteld bewaard, en exact de meegestuurde waarde (niets getrimd of aangepast).
        assertThat(secrets.decrypt(ciphertext, ref, ExternalCredentialSecretKind.SFTP_PASSWORD.name()))
                .isEqualTo(canary);

        List<Map<String, Object>> events = jdbc.queryForList(
                "select * from external_credential_event where credential_id = ? order by id", credentialId);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.get("event_kind")).isEqualTo("CREATED");
            assertThat(event.get("source")).isEqualTo("SYSTEM");
            assertThat(event.get("changed_by")).isNull();
            assertThat(event.get("changed_by_subject")).isNull();
            assertThat(event.get("reason")).isEqualTo("Overgenomen uit legacy INDET GOLDEN_ART (ontvangst " + id + ")");
            assertThat(event.get("previous_key_id")).isNull();
            assertThat(event.get("new_key_id")).isEqualTo(CredentialHttpTest.KEY_ID);
        });

        // Detail: secretOutcome + credentialRef, nooit de waarde.
        read(get(PUSH + "/{id}", id))
                .andExpect(jsonPath("$.secretOutcome").value("STORED"))
                .andExpect(jsonPath("$.credentialRef").value(ref.toString()))
                .andExpect(jsonPath("$.receipt.secretReceived").value(true))
                .andExpect(jsonPath("$.bundle.secret").value(org.hamcrest.Matchers.nullValue()));
        // De credential-API toont de technische client als laatste wijziger; het event heeft bron SYSTEM.
        read(get("/api/catalog-import/credentials/{ref}", ref))
                .andExpect(jsonPath("$.secretUpdatedBy").value(SERVICE_ACTOR))
                .andExpect(jsonPath("$.boundHost").value("sftp.example.test"));
        read(get("/api/catalog-import/credentials/{ref}/events", ref))
                .andExpect(jsonPath("$[0].source").value("SYSTEM"))
                .andExpect(jsonPath("$[0].changedBy").value(org.hamcrest.Matchers.nullValue()));

        assertCanaryOnlyAsCiphertext(canary, dossier(bundle), 1);
    }

    // --- DISCARDED_* ----------------------------------------------------------------------------------------------

    @Test
    void anOriginWithACredentialKeepsItAndGetsNoSecondOneEvenWhenRevoked() throws Exception {
        String canary = canary();
        ObjectNode bundle = withSecret(golden(), canary);
        long first = created(push(bundle));
        long credentialId = ((Number) receiptRow(first).get("credential_id")).longValue();
        Map<String, Object> before = jdbc.queryForMap("select * from external_credential where id = ?", credentialId);

        // Andere inhoud, ander secret: nieuwe ontvangst, maar de credential bestaat al.
        String otherCanary = canary();
        ObjectNode changed = withSecret(LegacyConfigReceiptHttpTest.withChangedContent(bundle.deepCopy(), "B"),
                otherCanary);
        long second = created(push(changed));
        assertThat(receiptRow(second).get("secret_outcome")).isEqualTo("DISCARDED_ALREADY_PRESENT");
        assertThat(receiptRow(second).get("credential_id")).isNull();
        assertThat(jdbc.queryForMap("select * from external_credential where id = ?", credentialId))
                .isEqualTo(before);

        // Ook een ingetrokken credential telt als "bestaat al": nooit vervangen (A1, vervangen = menselijke PUT).
        UUID ref = (UUID) before.get("credential_ref");
        mockMvc.perform(post("/api/catalog-import/credentials/{ref}/revoke", ref)
                        .with(be.dda.catalogimport.testsupport.TestActors.as(USER, Permission.MANAGE))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Test S4\"}"))
                .andExpect(status().isOk());
        long third = created(push(withSecret(LegacyConfigReceiptHttpTest.withChangedContent(bundle.deepCopy(), "C"),
                otherCanary)));
        assertThat(receiptRow(third).get("secret_outcome")).isEqualTo("DISCARDED_ALREADY_PRESENT");
        assertThat(jdbc.queryForObject("select status from external_credential where id = ?", String.class,
                credentialId)).isEqualTo("REVOKED");

        assertThat(credentialsOfOrigin(bundle)).isEqualTo(1);
        assertThat(((Number) originRow(bundle).get("credential_id")).longValue()).isEqualTo(credentialId);
        assertCanaryOnlyAsCiphertext(otherCanary, dossier(bundle), 1);
    }

    @Test
    void ftpOrAnUnknownProtocolIsDiscardedWithoutCredentialOrOrigin() throws Exception {
        for (String ftp : List.of("ftp://ftp.example.test/in/", "ftps://ftp.example.test/in/", "sftp.example.test",
                "\\\\server\\share\\in")) {
            String canary = canary();
            ObjectNode bundle = withSecret(golden(), canary);
            detail(bundle, "FTP", ftp);

            long id = created(push(bundle));

            assertThat(receiptRow(id).get("secret_outcome")).as(ftp).isEqualTo("DISCARDED_PROTOCOL_UNSUPPORTED");
            assertThat(receiptRow(id).get("credential_id")).isNull();
            assertThat(originCount(bundle)).isZero();
            assertCanaryOnlyAsCiphertext(canary, dossier(bundle), 0);
        }
    }

    @Test
    void noDestinationOrAnInvalidHostIsDiscardedAsNoHost() throws Exception {
        List<java.util.function.Consumer<ObjectNode>> variants = List.of(
                b -> removeDetail(b, "FTP"),
                b -> detail(b, "FTP", "sftp:///exports/"),
                b -> detail(b, "FTP", "sftp://sftp.example.test:99999/x"),
                b -> detail(b, "FTP", "sftp://sftp.[YYYY].test/x"),
                b -> detail(b, "FTP", "sftp://host name.test/x"));
        // (FTP "sftp://klant" + FTP2 "@host/x" was hier een NO_HOST-variant; sinds het S4-vervolg is userinfo in de
        // samengestelde waarde een 400 SECRET_IN_DETAILS, zie userInfoSplitOverFtpAndFtp2...)
        for (var variant : variants) {
            String canary = canary();
            ObjectNode bundle = withSecret(golden(), canary);
            variant.accept(bundle);

            long id = created(push(bundle));

            assertThat(receiptRow(id).get("secret_outcome")).isEqualTo("DISCARDED_NO_HOST");
            assertThat(originCount(bundle)).isZero();
            assertCanaryOnlyAsCiphertext(canary, dossier(bundle), 0);
        }
    }

    @Test
    void ftpPlusFtp2FormTheDestinationAsInLegacyIncludingAPortAndIpv6() throws Exception {
        ObjectNode bundle = withSecret(golden(), canary());
        detail(bundle, "FTP", "sftp://Files.");
        detail(bundle, "FTP2", "Example.TEST:2222/in/");
        long id = created(push(bundle));
        assertThat(boundHost(id)).isEqualTo("files.example.test");

        ObjectNode ipv6 = withSecret(golden(), canary());
        detail(ipv6, "FTP", "sftp://[2001:DB8::1]:22/in");
        assertThat(boundHost(created(push(ipv6)))).isEqualTo("[2001:db8::1]");
    }

    // --- UNCHANGED ------------------------------------------------------------------------------------------------

    @Test
    void anUnchangedPushThrowsTheSecretAwayAndTouchesNothing() throws Exception {
        ObjectNode bundle = withSecret(golden(), canary());
        long id = created(push(bundle));
        Map<String, Object> receiptBefore = receiptRow(id);
        Map<String, Object> originBefore = originRow(bundle);
        long credentialsBefore = credentialsOfOrigin(bundle);

        // Zelfde inhoud, ander secret: UNCHANGED, het nieuwe secret wordt nergens gebruikt.
        String otherCanary = canary();
        push(withSecret(bundle, otherCanary)).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("UNCHANGED"))
                .andExpect(jsonPath("$.receiptId").value(id));

        assertThat(receiptRow(id)).isEqualTo(receiptBefore);
        assertThat(originRow(bundle)).isEqualTo(originBefore);
        assertThat(credentialsOfOrigin(bundle)).isEqualTo(credentialsBefore);
        assertCanaryOnlyAsCiphertext(otherCanary, dossier(bundle), 1);
    }

    /**
     * S4-vervolg (beslissingslog 2026-10-21, verfijnt A1), het S3-scenario: de inhoud kwam eerst zonder secret binnen
     * (de job had toen nog geen S4). Dezelfde inhoud mét secret is {@code UNCHANGED}, maar de herkomst heeft nog geen
     * credential: het secret wordt toch versleuteld opgeslagen, op de herkomst en op die laatste ontvangst gezet. Een
     * tweede identieke push met een ander secret vervangt niets.
     */
    @Test
    void aSecretThatArrivesLaterWithTheSameContentIsStoredOnceAndNeverReplaced() throws Exception {
        ObjectNode bundle = golden();
        long id = created(push(bundle));
        assertThat(receiptRow(id).get("secret_received")).isEqualTo(false);
        String canary = canary();

        push(withSecret(bundle, canary)).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("UNCHANGED"))
                .andExpect(jsonPath("$.receiptId").value(id))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.supersededReceiptId").value(org.hamcrest.Matchers.nullValue()));

        Map<String, Object> receipt = receiptRow(id);
        assertThat(receipt.get("status")).isEqualTo("RECEIVED");
        assertThat(receipt.get("secret_received")).isEqualTo(true);
        assertThat(receipt.get("secret_outcome")).isEqualTo("STORED");
        long credentialId = ((Number) receipt.get("credential_id")).longValue();
        assertThat(((Number) originRow(bundle).get("credential_id")).longValue()).isEqualTo(credentialId);
        assertThat(count("select count(*) from legacy_config_receipt where origin_dossier = ?", dossier(bundle)))
                .isEqualTo(1);
        Map<String, Object> credential = jdbc.queryForMap("select * from external_credential where id = ?",
                credentialId);
        UUID ref = (UUID) credential.get("credential_ref");
        assertThat(credential.get("bound_host")).isEqualTo("sftp.example.test");
        assertThat(credential.get("created_by")).isEqualTo(SERVICE_ACTOR);
        assertThat(secrets.decrypt((String) credential.get("ciphertext"), ref,
                ExternalCredentialSecretKind.SFTP_PASSWORD.name())).isEqualTo(canary);
        assertThat(jdbc.queryForList("select * from external_credential_event where credential_id = ?",
                credentialId)).singleElement().satisfies(event -> {
                    assertThat(event.get("source")).isEqualTo("SYSTEM");
                    assertThat(event.get("changed_by")).isNull();
                    assertThat(event.get("reason")).isEqualTo("Overgenomen uit legacy INDET GOLDEN_ART (ontvangst "
                            + id + ", herhaalde push)");
                });
        read(get(PUSH + "/{id}", id))
                .andExpect(jsonPath("$.secretOutcome").value("STORED"))
                .andExpect(jsonPath("$.credentialRef").value(ref.toString()))
                .andExpect(jsonPath("$.receipt.secretReceived").value(true));
        assertCanaryOnlyAsCiphertext(canary, dossier(bundle), 1);

        // Tweede identieke push met een ander secret: geen tweede credential, niets vervangen of aangeraakt (A1).
        Map<String, Object> receiptBefore = receiptRow(id);
        Map<String, Object> originBefore = originRow(bundle);
        Map<String, Object> credentialBefore = jdbc.queryForMap("select * from external_credential where id = ?",
                credentialId);
        String otherCanary = canary();
        push(withSecret(bundle, otherCanary)).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("UNCHANGED"))
                .andExpect(jsonPath("$.receiptId").value(id));
        assertThat(receiptRow(id)).isEqualTo(receiptBefore);
        assertThat(originRow(bundle)).isEqualTo(originBefore);
        assertThat(jdbc.queryForMap("select * from external_credential where id = ?", credentialId))
                .isEqualTo(credentialBefore);
        assertThat(credentialsOfOrigin(bundle)).isEqualTo(1);
        assertThat(count("select count(*) from external_credential_event where credential_id = ?", credentialId))
                .isEqualTo(1);
        assertCanaryOnlyAsCiphertext(otherCanary, dossier(bundle), 1);
        assertCanaryOnlyAsCiphertext(canary, dossier(bundle), 1);
    }

    /**
     * S4-vervolg, herkomst die al bestaat (door APPLY) maar nog geen credential heeft: de credential komt op die
     * herkomst (geen tweede herkomstrij) en op de toegepaste ontvangst, die {@code APPLIED} blijft.
     */
    @Test
    void aSecretAfterApplyWithTheSameContentIsStoredOnTheExistingOriginAndTheAppliedReceipt() throws Exception {
        ObjectNode bundle = rehash(applicable());
        long id = created(push(bundle));
        previewAndApply(id, "libraryCode", "LIB-S4V").andExpect(status().isOk());
        Map<String, Object> originBefore = originRow(bundle);
        assertThat(originBefore.get("credential_id")).isNull();
        assertThat(originBefore.get("import_link_id")).isNotNull();
        String canary = canary();

        push(withSecret(bundle, canary)).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("UNCHANGED"))
                .andExpect(jsonPath("$.receiptId").value(id))
                .andExpect(jsonPath("$.status").value("APPLIED"));

        Map<String, Object> receipt = receiptRow(id);
        assertThat(receipt.get("status")).isEqualTo("APPLIED");
        assertThat(receipt.get("secret_outcome")).isEqualTo("STORED");
        Map<String, Object> origin = originRow(bundle);
        assertThat(origin.get("id")).isEqualTo(originBefore.get("id"));
        assertThat(origin.get("import_link_id")).isEqualTo(originBefore.get("import_link_id"));
        assertThat(origin.get("credential_id")).isEqualTo(receipt.get("credential_id")).isNotNull();
        assertThat(originCount(bundle)).isEqualTo(1);
        assertCanaryOnlyAsCiphertext(canary, dossier(bundle), 1);
    }

    /** S4-vervolg: bij {@code UNCHANGED} gelden dezelfde weigerregels; een weggegooid secret schrijft niets. */
    @Test
    void anUnchangedPushWithASecretButNoUsableSftpHostWritesNothing() throws Exception {
        for (String ftp : List.of("ftp://ftp.example.test/in/", "sftp://sftp.[YYYY].test/x")) {
            ObjectNode bundle = golden();
            detail(bundle, "FTP", ftp);
            long id = created(push(bundle));
            Map<String, Object> before = receiptRow(id);
            String canary = canary();

            push(withSecret(bundle, canary)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("UNCHANGED"));

            assertThat(receiptRow(id)).as(ftp).isEqualTo(before);
            assertThat(originCount(bundle)).isZero();
            assertCanaryOnlyAsCiphertext(canary, dossier(bundle), 0);
        }
    }

    // --- Gelijktijdigheid -----------------------------------------------------------------------------------------

    /** Vier pushes met verschillende inhoud en elk een secret, gelijktijdig: precies één credential. */
    @Test
    void concurrentPushesOfOneOriginCreateExactlyOneCredential() throws Exception {
        ObjectNode base = golden();
        List<Callable<PushResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            byte[] body = MAPPER.writeValueAsBytes(withSecret(
                    LegacyConfigReceiptHttpTest.withChangedContent(base.deepCopy(), "Variant " + i), canary()));
            tasks.add(() -> receipts.receive(body.clone(), CLIENT));
        }

        List<PushResult> results = concurrently(tasks);

        assertThat(results).extracting(PushResult::outcome).containsOnly("RECEIVED");
        assertThat(credentialsOfOrigin(base)).isEqualTo(1);
        assertThat(count("select count(*) from legacy_config_receipt where origin_dossier = ? "
                + "and secret_outcome = 'STORED'", dossier(base))).isEqualTo(1);
        assertThat(count("select count(*) from legacy_config_receipt where origin_dossier = ? "
                + "and secret_outcome = 'DISCARDED_ALREADY_PRESENT'", dossier(base))).isEqualTo(3);
        assertThat(originCount(base)).isEqualTo(1);
    }

    /**
     * S4-vervolg, gelijktijdig: eerst de inhoud zonder secret, dan vier identieke pushes elk met een eigen secret.
     * Allemaal {@code UNCHANGED}, precies één credential, op de herkomst en op de ene ontvangst.
     */
    @Test
    void concurrentUnchangedPushesWithASecretCreateExactlyOneCredential() throws Exception {
        ObjectNode base = golden();
        long id = created(push(base));
        List<String> canaries = new ArrayList<>();
        List<Callable<PushResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String canary = canary();
            canaries.add(canary);
            byte[] body = MAPPER.writeValueAsBytes(rehash(withSecret(base.deepCopy(), canary)));
            tasks.add(() -> receipts.receive(body.clone(), CLIENT));
        }

        List<PushResult> results = concurrently(tasks);

        assertThat(results).extracting(PushResult::outcome).containsOnly("UNCHANGED");
        assertThat(results).extracting(PushResult::receiptId).containsOnly(id);
        assertThat(credentialsOfOrigin(base)).isEqualTo(1);
        assertThat(originCount(base)).isEqualTo(1);
        Map<String, Object> receipt = receiptRow(id);
        assertThat(receipt.get("secret_outcome")).isEqualTo("STORED");
        assertThat(receipt.get("credential_id")).isEqualTo(originRow(base).get("credential_id"));
        assertThat(count("select count(*) from legacy_config_receipt where origin_dossier = ?", dossier(base)))
                .isEqualTo(1);
        // Het opgeslagen secret is exact één van de vier; de andere drie staan nergens.
        long credentialId = ((Number) receipt.get("credential_id")).longValue();
        Map<String, Object> credential = jdbc.queryForMap("select * from external_credential where id = ?",
                credentialId);
        String stored = secrets.decrypt((String) credential.get("ciphertext"), (UUID) credential.get("credential_ref"),
                ExternalCredentialSecretKind.SFTP_PASSWORD.name());
        assertThat(canaries).contains(stored);
        for (String canary : canaries) {
            assertCanaryOnlyAsCiphertext(canary, dossier(base), 1);
        }
    }

    // --- Userinfo over FTP + FTP2 (S4-vervolg) --------------------------------------------------------------------

    /**
     * Contract par. 2.1 regel 3b/4 (beslissingslog 2026-10-21): legacy plakt {@code Trim(FTP) & Trim(FTP2)}; userinfo
     * die over de twee labels verdeeld is, staat in de samengestelde waarde. Rechtstreeks (onbewerkt) naar
     * CatalogImport → 400 {@code SECRET_IN_DETAILS}, niets bewaard, de userinfo nergens terug te vinden. Zoals de job
     * het redigeert (FTP behoudt {@code sftp://}, FTP2 {@code host/x}) → aanvaard, met een credential op die host.
     */
    @Test
    void userInfoSplitOverFtpAndFtp2IsRefusedRawAndAcceptedAsTheJobRedactsIt() throws Exception {
        // Een eigen canary als wachtwoord in de userinfo, in twee helften over FTP en FTP2.
        String userInfoCanary = canary();
        String half = userInfoCanary.substring(0, userInfoCanary.length() / 2);
        String rest = userInfoCanary.substring(userInfoCanary.length() / 2);
        String canary = canary();
        ObjectNode raw = withSecret(golden(), canary());
        detail(raw, "FTP", "sftp://klant:" + half);
        detail(raw, "FTP2", rest + "@sftp.example.test/x");
        byte[] body = MAPPER.writeValueAsBytes(rehash(raw));

        String refused = push(body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LEGACY_BUNDLE_INVALID"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(refused).contains("$.interface.details[9].value (SECRET_IN_DETAILS)");
        Throwable thrown = catchThrowable(() -> receipts.receive(body.clone(), CLIENT));
        assertThat(thrown).isNotNull();
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            assertThat(String.valueOf(t.getMessage())).doesNotContain(half).doesNotContain(rest);
        }
        assertThat(count("select count(*) from legacy_config_receipt where origin_dossier = ?", dossier(raw)))
                .isZero();
        // Ook spaties rond de labels (Trim) of userinfo enkel in FTP2 helpen niet.
        for (String[] variant : List.of(new String[] {"  sftp://klant:" + half + " ", " " + rest + "@h.test/x"},
                new String[] {"", "sftp://klant:" + half + rest + "@h.test/x"})) {
            ObjectNode other = withSecret(golden(), canary());
            detail(other, "FTP", variant[0]);
            detail(other, "FTP2", variant[1]);
            push(MAPPER.writeValueAsBytes(rehash(other))).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("LEGACY_BUNDLE_INVALID"));
        }

        ObjectNode redacted = withSecret(golden(), canary);
        detail(redacted, "FTP", "sftp://");
        detail(redacted, "FTP2", "sftp.example.test/x");
        long id = created(push(redacted));
        assertThat(receiptRow(id).get("secret_outcome")).isEqualTo("STORED");
        assertThat(boundHost(id)).isEqualTo("sftp.example.test");

        for (String fragment : List.of(half, rest)) {
            String like = "%" + fragment + "%";
            assertThat(count("select count(*) from legacy_config_receipt t where t::text like ?", like)).isZero();
            for (String response : responses) {
                assertThat(response).doesNotContain(fragment);
            }
            for (ILoggingEvent event : log.list) {
                assertThat(event.getFormattedMessage()).as(event.getLoggerName()).doesNotContain(fragment);
            }
        }
        assertCanaryOnlyAsCiphertext(canary, dossier(redacted), 1);
    }

    // --- Fouten zonder lek ------------------------------------------------------------------------------------------

    @Test
    void refusedBundlesWithASecretNeverEchoItInAResponseLogOrException() throws Exception {
        String canary = canary();
        ObjectNode tampered = withSecret(golden(), canary);
        ((ObjectNode) tampered.get("interface").get("header")).put("info", "zonder nieuwe hash");
        ObjectNode otherLabel = withSecret(golden(), canary);
        ((ObjectNode) otherLabel.get("secret")).put("label", "API_AUTH_PASSWORD");
        ObjectNode tooLong = withSecret(golden(), canary + "x".repeat(CredentialService.MAX_SECRET_LENGTH));
        ObjectNode unknownField = withSecret(golden(), canary);
        ((ObjectNode) unknownField.get("secret")).put("hint", canary);

        for (ObjectNode bundle : List.of(tampered, otherLabel, tooLong, unknownField)) {
            byte[] body = MAPPER.writeValueAsBytes(bundle);
            push(body).andExpect(status().isBadRequest());
            Throwable thrown = catchThrowable(() -> receipts.receive(body.clone(), CLIENT));
            assertThat(thrown).isNotNull();
            for (Throwable t = thrown; t != null; t = t.getCause()) {
                assertThat(String.valueOf(t.getMessage())).doesNotContain(canary);
                assertThat(t.toString()).doesNotContain(canary);
            }
        }
        // Rehash na het breken zou een geldige bundel geven; de geweigerde bundels lieten niets na.
        assertThat(count("select count(*) from legacy_config_receipt where origin_dossier in (?, ?, ?, ?)",
                dossier(tampered), dossier(otherLabel), dossier(tooLong), dossier(unknownField))).isZero();
        assertCanaryOnlyAsCiphertext(canary, dossier(tampered), 0);
    }

    @Test
    void theSecretRecordsNeverShowTheValue() {
        LegacySecret secret = new LegacySecret("FTP_password", "canary-tostring");
        assertThat(secret.toString()).doesNotContain("canary").contains("[REDACTED]");
    }

    // --- PREVIEW / APPLY ------------------------------------------------------------------------------------------

    @Test
    void previewAndApplyShowTheCredentialRefButLinkNoConnectionProfile() throws Exception {
        String canary = canary();
        ObjectNode bundle = withSecret(rehash(applicable()), canary);
        detail(bundle, "FTP_directory", "/in");
        long id = created(push(rehash(bundle)));
        UUID ref = credentialRef(id);
        long profilesBefore = count("select count(*) from connection_profile");
        long configurationsBefore = count("select count(*) from delivery_configuration");

        String preview = previewBody(id, "libraryCode", "LIB-S4");
        responses.add(preview);
        assertThat((String) JsonPath.read(preview, "$.report.planned.credential.credentialRef"))
                .isEqualTo(ref.toString());
        assertThat((String) JsonPath.read(preview, "$.report.planned.credential.secretOutcome")).isEqualTo("STORED");
        List<String> connection = JsonPath.read(preview,
                "$.report.items[?(@.code == 'CONNECTION_NOT_MIGRATED')].message");
        assertThat(connection).singleElement().asString()
                .contains("protocol=SFTP", "host=sftp.example.test", "port=22 (default)", "directory=/exports/",
                        "FTP_directory=/in", "login=fixture-user", "credentialRef=" + ref);
        List<String> target = JsonPath.read(preview, "$.report.items[?(@.code == 'CONNECTION_NOT_MIGRATED')].target");
        assertThat(target).containsExactly("external_credential.credentialRef=" + ref);

        String applied = apply(id, JsonPath.read(preview, "$.previewFingerprint"), REASON, "libraryCode", "LIB-S4")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        responses.add(applied);
        assertThat((String) JsonPath.read(applied, "$.report.planned.credential.credentialRef"))
                .isEqualTo(ref.toString());
        // L5: geen profiel, geen leveringsconfiguratie; de credential wordt door niets gebruikt.
        assertThat(count("select count(*) from connection_profile")).isEqualTo(profilesBefore);
        assertThat(count("select count(*) from delivery_configuration")).isEqualTo(configurationsBefore);
        read(get("/api/catalog-import/credentials/{ref}", ref)).andExpect(jsonPath("$.profileVersionCount").value(0));
        assertThat((String) receiptRow(id).get("apply_report_json")).contains(ref.toString());

        assertCanaryOnlyAsCiphertext(canary, dossier(bundle), 1);
    }

    // --- Hulp -----------------------------------------------------------------------------------------------------

    /**
     * De canary staat in geen enkele rij van de legacy-config-tabellen of credentialevents en in geen enkel
     * credentialveld behalve (versleuteld) de ciphertext; niet in een opgevangen antwoord, logregel of exceptie.
     */
    private void assertCanaryOnlyAsCiphertext(String canary, String dossier, int expectedCredentials) {
        String like = "%" + canary + "%";
        assertThat(count("select count(*) from legacy_config_receipt t where t::text like ?", like)).isZero();
        assertThat(count("select count(*) from legacy_config_origin t where t::text like ?", like)).isZero();
        assertThat(count("select count(*) from external_credential t where t::text like ?", like)).isZero();
        assertThat(count("select count(*) from external_credential_event t where t::text like ?", like)).isZero();
        assertThat(count("select count(*) from external_credential c join legacy_config_origin o "
                + "on o.credential_id = c.id where o.origin_dossier = ?", dossier)).isEqualTo(expectedCredentials);
        for (String response : responses) {
            assertThat(response).doesNotContain(canary);
        }
        assertThat(log.list).as("captured log lines").isNotEmpty();
        for (ILoggingEvent event : log.list) {
            assertThat(event.getFormattedMessage()).as(event.getLoggerName()).doesNotContain(canary);
            for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                assertThat(String.valueOf(proxy.getMessage())).as(event.getLoggerName()).doesNotContain(canary);
            }
        }
    }

    private static String canary() {
        return "canary-s4-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static ObjectNode withSecret(ObjectNode bundle, String value) {
        bundle.putObject("secret").put("label", "FTP_password").put("value", value);
        return bundle;
    }

    private ResultActions push(ObjectNode bundle) throws Exception {
        return push(MAPPER.writeValueAsBytes(rehash(bundle)));
    }

    private ResultActions push(byte[] body) throws Exception {
        ResultActions result = bareMvc.perform(post(PUSH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTechnicalClientJwt.validToken())
                .contentType(MediaType.APPLICATION_JSON).content(body));
        responses.add(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        return result;
    }

    private ResultActions read(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        ResultActions result = mockMvc.perform(request.with(
                be.dda.catalogimport.testsupport.TestActors.as(USER, Permission.MANAGE))).andExpect(status().isOk());
        responses.add(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        return result;
    }

    private static long created(ResultActions result) throws Exception {
        MvcResult done = result.andExpect(status().isCreated()).andReturn();
        return ((Number) JsonPath.read(done.getResponse().getContentAsString(), "$.receiptId")).longValue();
    }

    private Map<String, Object> originRow(ObjectNode bundle) {
        return jdbc.queryForMap("select * from legacy_config_origin where origin_dossier = ? and interface_code = ?",
                dossier(bundle), interfaceCode(bundle));
    }

    private long originCount(ObjectNode bundle) {
        return count("select count(*) from legacy_config_origin where origin_dossier = ?", dossier(bundle));
    }

    /** Credentials die bij deze herkomst horen (via het label; ook een eventuele tweede zou zo gevonden worden). */
    private long credentialsOfOrigin(ObjectNode bundle) {
        return count("select count(*) from external_credential where label = ?",
                "Legacy " + dossier(bundle) + "/" + interfaceCode(bundle));
    }

    private String boundHost(long receiptId) {
        return jdbc.queryForObject("select c.bound_host from external_credential c join legacy_config_receipt r "
                + "on r.credential_id = c.id where r.id = ?", String.class, receiptId);
    }

    private UUID credentialRef(long receiptId) {
        return jdbc.queryForObject("select c.credential_ref from external_credential c join legacy_config_receipt r "
                + "on r.credential_id = c.id where r.id = ?", UUID.class, receiptId);
    }

    private static List<PushResult> concurrently(List<Callable<PushResult>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<PushResult>> futures = new ArrayList<>();
            for (Callable<PushResult> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<PushResult> results = new ArrayList<>();
            for (Future<PushResult> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private void raise(String name, Level level) {
        Logger logger = (Logger) LoggerFactory.getLogger(name);
        raised.add(logger);
        originalLevels.add(logger.getLevel());
        logger.setLevel(level);
    }
}
