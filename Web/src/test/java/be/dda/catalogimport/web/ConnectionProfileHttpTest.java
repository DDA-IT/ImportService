package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.ExternalCredentialRepository;
import be.dda.catalogimport.domain.ConnectionAuthMethod;
import be.dda.catalogimport.domain.ExternalCredential;
import be.dda.catalogimport.domain.ExternalCredentialSecretKind;
import be.dda.catalogimport.service.ConnectionProfileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

/**
 * Bouwstap LC-2: verbindingsprofielen ({@code docs/design/leveringsconfiguratie-design.md} par. 3.2, 6, 10;
 * beslissingslog 2026-09-29 L3-L5, L7, A5).
 *
 * <ul>
 *   <li><b>Regel:</b> een profielversie gebruikt enkel een actieve credential van dezelfde genormaliseerde host en de
 *       passende soort (L4a). <b>Bewijs:</b> andere host / ingetrokken / onbekend / verkeerde soort worden geweigerd en
 *       er ontstaat geen rij.</li>
 *   <li><b>Regel:</b> v1 enkel {@code PASSWORD} (A5); vastgepinde hostsleutel in OpenSSH-vorm (L5). <b>Bewijs:</b> 400
 *       met eigen code per ongeldige invoer.</li>
 *   <li><b>Regel:</b> {@code config_hash} is deterministisch over de functionele velden. <b>Bewijs:</b> gelijk bij
 *       gelijke functionele velden (andere code/naam/reden), anders bij een andere poort, en gelijk aan de
 *       gedocumenteerde canonieke vorm.</li>
 *   <li><b>Regel (L7b):</b> lezen en schrijven vragen MANAGE; een READ-gebruiker krijgt 403 en er wordt niets
 *       geschreven. Geen antwoord bevat een ciphertext of subject.</li>
 * </ul>
 * Credentials worden rechtstreeks via de repository aangemaakt (vaste nep-ciphertext): deze test gaat over de
 * profielen, niet over de versleuteling, en zo is ook een {@code SSH_PRIVATE_KEY}-credential te maken.
 */
@SpringBootTest(properties = {"catalogimport.screening.recovery-on-startup=false"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ConnectionProfileHttpTest {

    private static final String API = "/api/catalog-import/connection-profiles";
    private static final String USER = "an.janssens@example.test";
    private static final String CIPHERTEXT = "v1:lc2-test:NepCiphertextDieNooitInEenAntwoordMagStaan";
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
    private ObjectMapper objectMapper;
    @Autowired
    private ExternalCredentialRepository credentials;

    // --- Aanmaken ----------------------------------------------------------------------------------------------------

    @Test
    void createAnswers201WithVersion1AndANormalisedHost() throws Exception {
        ExternalCredential credential = credential("sftp.lev-een.test", ExternalCredentialSecretKind.SFTP_PASSWORD);
        String code = unique("NORM");
        String fingerprint = fingerprint();
        Map<String, Object> body = body(code, "  SFTP.Lev-Een.TEST.  ", null, credential.getCredentialRef());
        body.put("hostKeyFingerprintSha256", fingerprint);

        String answer = create(body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.protocol").value("SFTP"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.createdBy").value(USER))
                .andExpect(jsonPath("$.versions.length()").value(1))
                .andExpect(jsonPath("$.versions[0].versionNumber").value(1))
                .andExpect(jsonPath("$.versions[0].host").value("sftp.lev-een.test"))
                .andExpect(jsonPath("$.versions[0].port").value(22))
                .andExpect(jsonPath("$.versions[0].username").value("leverancier"))
                .andExpect(jsonPath("$.versions[0].authMethod").value("PASSWORD"))
                .andExpect(jsonPath("$.versions[0].credentialRef").value(credential.getCredentialRef().toString()))
                .andExpect(jsonPath("$.versions[0].credentialStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.versions[0].hostKeyAlgorithm").value("ssh-ed25519"))
                .andExpect(jsonPath("$.versions[0].hostKeyFingerprintSha256").value(fingerprint))
                .andExpect(jsonPath("$.versions[0].changeReason").value("Nieuwe leverancier"))
                .andExpect(jsonPath("$.versions[0].createdBy").value(USER))
                .andReturn().getResponse().getContentAsString();

        String expectedHash = ConnectionProfileService.configHash("sftp.lev-een.test", 22, "leverancier",
                ConnectionAuthMethod.PASSWORD, credential.getCredentialRef(), "ssh-ed25519", fingerprint);
        assertThat(JsonPath.<String>read(answer, "$.versions[0].configHash")).isEqualTo(expectedHash).hasSize(64);
        assertThat(answer).doesNotContain(CIPHERTEXT).doesNotContain("ciphertext").doesNotContain("encryptionKeyId")
                .doesNotContain("test-sub-");

        long id = ((Number) JsonPath.read(answer, "$.id")).longValue();
        Map<String, Object> row = jdbc.queryForMap("select * from connection_profile_version where connection_profile_id = ?",
                id);
        assertThat(row).containsEntry("host", "sftp.lev-een.test").containsEntry("credential_host", "sftp.lev-een.test")
                .containsEntry("credential_secret_kind", "SFTP_PASSWORD").containsEntry("version_number", 1)
                .containsEntry("created_by", USER).containsEntry("created_by_subject", "test-sub-" + USER)
                .containsEntry("config_hash", expectedHash);
        assertThat(jdbc.queryForObject("select created_by_subject from connection_profile where id = ?", String.class,
                id)).isEqualTo("test-sub-" + USER);

        // Detail en lijst: dezelfde versie; de lijst toont enkel kopgegevens (geen host, login of vingerafdruk).
        String detail = read(get(API + "/{id}", id)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat(JsonPath.<Object>read(detail, "$")).isEqualTo(JsonPath.read(answer, "$"));
        String list = read(get(API)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> mine = JsonPath.read(list, "$[?(@.code == '" + code + "')]");
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0)).containsEntry("latestVersionNumber", 1).containsKey("latestVersionId");
        assertThat(mine.get(0).keySet()).containsExactlyInAnyOrder("id", "code", "name", "protocol", "active",
                "latestVersionId", "latestVersionNumber", "createdAt", "createdBy");
        assertThat(list).doesNotContain(CIPHERTEXT).doesNotContain(fingerprint);
    }

    @Test
    void theConfigHashIsDeterministicOverTheFunctionalFieldsOnly() throws Exception {
        ExternalCredential credential = credential("sftp.hash.test", ExternalCredentialSecretKind.SFTP_PASSWORD);
        String fingerprint = fingerprint();
        Map<String, Object> first = body(unique("H1"), "sftp.hash.test", 2222, credential.getCredentialRef());
        first.put("hostKeyFingerprintSha256", fingerprint);
        Map<String, Object> second = body(unique("H2"), "SFTP.HASH.TEST", 2222, credential.getCredentialRef());
        second.put("hostKeyFingerprintSha256", fingerprint);
        second.put("name", "Een heel andere naam");
        second.put("reason", "Een andere reden");
        Map<String, Object> otherPort = body(unique("H3"), "sftp.hash.test", 2223, credential.getCredentialRef());
        otherPort.put("hostKeyFingerprintSha256", fingerprint);

        String a = hashOf(create(first).andExpect(status().isCreated()));
        String b = hashOf(create(second).andExpect(status().isCreated()));
        String c = hashOf(create(otherPort).andExpect(status().isCreated()));

        assertThat(a).isEqualTo(b).isNotEqualTo(c);
        assertThat(a).isEqualTo(ConnectionProfileService.configHash("sftp.hash.test", 2222, "leverancier",
                ConnectionAuthMethod.PASSWORD, credential.getCredentialRef(), "ssh-ed25519", fingerprint));
    }

    // --- Credential: host, status, soort, bestaan ---------------------------------------------------------------------

    @Test
    void aCredentialOfAnotherHostRevokedUnknownOrOfTheWrongKindIsRefusedAndNothingIsWritten() throws Exception {
        ExternalCredential otherHost = credential("sftp.ander.test", ExternalCredentialSecretKind.SFTP_PASSWORD);
        ExternalCredential revoked = credential("sftp.weiger.test", ExternalCredentialSecretKind.SFTP_PASSWORD);
        revoked.recordRevocation(USER, null, Instant.now(), "Test");
        credentials.saveAndFlush(revoked);
        ExternalCredential keyKind = credential("sftp.weiger.test", ExternalCredentialSecretKind.SSH_PRIVATE_KEY);
        long before = profileCount();

        refused(body(unique("OH"), "sftp.weiger.test", null, otherHost.getCredentialRef()), 409,
                ConnectionProfileService.CODE_CREDENTIAL_HOST_MISMATCH);
        refused(body(unique("RV"), "sftp.weiger.test", null, revoked.getCredentialRef()), 409,
                ConnectionProfileService.CODE_CREDENTIAL_NOT_ACTIVE);
        refused(body(unique("KK"), "sftp.weiger.test", null, keyKind.getCredentialRef()), 409,
                ConnectionProfileService.CODE_CREDENTIAL_KIND_MISMATCH);
        refused(body(unique("UN"), "sftp.weiger.test", null, UUID.randomUUID()), 404, "CREDENTIAL_NOT_FOUND");
        Map<String, Object> malformed = body(unique("MF"), "sftp.weiger.test", null, UUID.randomUUID());
        malformed.put("credentialRef", "geen-uuid");
        refused(malformed, 404, "CREDENTIAL_NOT_FOUND");
        Map<String, Object> missing = body(unique("MS"), "sftp.weiger.test", null, UUID.randomUUID());
        missing.remove("credentialRef");
        refused(missing, 400, ConnectionProfileService.CODE_CREDENTIAL_REQUIRED);

        assertThat(profileCount()).isEqualTo(before);
    }

    // --- Invoervalidatie -----------------------------------------------------------------------------------------------

    @Test
    void privateKeyIsNotSupportedYetAndAnUnknownAuthMethodIsInvalid() throws Exception {
        ExternalCredential credential = credential("sftp.auth.test", ExternalCredentialSecretKind.SFTP_PASSWORD);
        long before = profileCount();

        Map<String, Object> privateKey = body(unique("PK"), "sftp.auth.test", null, credential.getCredentialRef());
        privateKey.put("authMethod", "PRIVATE_KEY");
        refused(privateKey, 400, ConnectionProfileService.CODE_AUTH_METHOD_NOT_SUPPORTED);
        for (String method : new String[] {"WACHTWOORD", "", null}) {
            Map<String, Object> invalid = body(unique("AM"), "sftp.auth.test", null, credential.getCredentialRef());
            invalid.put("authMethod", method);
            refused(invalid, 400, ConnectionProfileService.CODE_AUTH_METHOD_INVALID);
        }
        assertThat(profileCount()).isEqualTo(before);
    }

    @Test
    void invalidFingerprintAlgorithmPortHostAndTextsAre400WithACode() throws Exception {
        ExternalCredential credential = credential("sftp.invoer.test", ExternalCredentialSecretKind.SFTP_PASSWORD);
        UUID ref = credential.getCredentialRef();
        long before = profileCount();

        String zeros = "A".repeat(43);
        for (String fingerprint : new String[] {null, "", "abc", "SHA256:" + zeros.substring(1), "SHA256:" + zeros + "A",
                "sha256:" + zeros, "SHA256:" + zeros + "=", "MD5:" + zeros, "SHA256:" + zeros.substring(1) + "B",
                "SHA256:" + zeros.substring(1) + "!"}) {
            Map<String, Object> invalid = body(unique("FP"), "sftp.invoer.test", null, ref);
            invalid.put("hostKeyFingerprintSha256", fingerprint);
            refused(invalid, 400, ConnectionProfileService.CODE_HOST_KEY_FINGERPRINT_INVALID);
        }
        for (String algorithm : new String[] {null, "", "ssh-rsa", "ssh-dss", "SSH-ED25519", "ed25519"}) {
            Map<String, Object> invalid = body(unique("AL"), "sftp.invoer.test", null, ref);
            invalid.put("hostKeyAlgorithm", algorithm);
            refused(invalid, 400, ConnectionProfileService.CODE_HOST_KEY_ALGORITHM_INVALID);
        }
        for (int port : new int[] {0, -1, 65536}) {
            refused(body(unique("PO"), "sftp.invoer.test", port, ref), 400, ConnectionProfileService.CODE_PORT_INVALID);
        }
        for (String host : new String[] {"", "sftp host.test", "sftp://sftp.invoer.test", "user@sftp.invoer.test"}) {
            refused(body(unique("HO"), host, null, ref), 400, ConnectionProfileService.CODE_HOST_INVALID);
        }
        Map<String, Object> noUser = body(unique("US"), "sftp.invoer.test", null, ref);
        noUser.put("username", "  ");
        refused(noUser, 400, ConnectionProfileService.CODE_USERNAME_INVALID);
        Map<String, Object> controlUser = body(unique("UC"), "sftp.invoer.test", null, ref);
        controlUser.put("username", "lev\nerancier");
        refused(controlUser, 400, ConnectionProfileService.CODE_USERNAME_INVALID);
        Map<String, Object> noCode = body(unique("CO"), "sftp.invoer.test", null, ref);
        noCode.put("code", "");
        refused(noCode, 400, ConnectionProfileService.CODE_CODE_INVALID);
        Map<String, Object> longCode = body(unique("CL"), "sftp.invoer.test", null, ref);
        longCode.put("code", "C".repeat(51));
        refused(longCode, 400, ConnectionProfileService.CODE_CODE_INVALID);
        Map<String, Object> noName = body(unique("NA"), "sftp.invoer.test", null, ref);
        noName.put("name", null);
        refused(noName, 400, ConnectionProfileService.CODE_NAME_INVALID);
        Map<String, Object> noReason = body(unique("RE"), "sftp.invoer.test", null, ref);
        noReason.put("reason", " ");
        refused(noReason, 400, ConnectionProfileService.CODE_REASON_REQUIRED);
        json(post(API), as(USER, Permission.MANAGE), "").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ConnectionProfileService.CODE_CODE_INVALID));

        // Grens: 1 en 65535 zijn geldige poorten.
        create(body(unique("P1"), "sftp.invoer.test", 1, ref)).andExpect(status().isCreated());
        create(body(unique("P2"), "sftp.invoer.test", 65535, ref)).andExpect(status().isCreated());
        assertThat(profileCount()).isEqualTo(before + 2);
    }

    // --- Dubbel, onbekend, rechten ------------------------------------------------------------------------------------

    @Test
    void aDuplicateCodeIsA409AndLeavesExactlyOneProfile() throws Exception {
        ExternalCredential credential = credential("sftp.dubbel.test", ExternalCredentialSecretKind.SFTP_PASSWORD);
        String code = unique("DUP");

        create(body(code, "sftp.dubbel.test", null, credential.getCredentialRef())).andExpect(status().isCreated());
        refused(body(code, "sftp.dubbel.test", null, credential.getCredentialRef()), 409,
                ConnectionProfileService.CODE_CODE_EXISTS);

        assertThat(jdbc.queryForObject("select count(*) from connection_profile where code = ?", Long.class, code))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from connection_profile_version v join connection_profile p "
                + "on p.id = v.connection_profile_id where p.code = ?", Long.class, code)).isEqualTo(1L);
    }

    @Test
    void anUnknownProfileIsA404() throws Exception {
        read(get(API + "/{id}", 999_999_999L)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ConnectionProfileService.CODE_NOT_FOUND));
    }

    /** L7b: READ mag hier niets, ook niet lezen; zonder recht wordt niets geschreven (ook niet met een geldige body). */
    @Test
    void aReadOnlyUserMayNeitherReadNorCreateProfiles() throws Exception {
        ExternalCredential credential = credential("sftp.lezer.test", ExternalCredentialSecretKind.SFTP_PASSWORD);
        String created = create(body(unique("RD"), "sftp.lezer.test", null, credential.getCredentialRef()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(created, "$.id")).longValue();
        RequestPostProcessor reader = as(USER, Permission.READ);
        long before = profileCount();

        for (MockHttpServletRequestBuilder request : List.of(get(API), get(API + "/{id}", id),
                get(API + "/{id}", 999_999_999L))) {
            mockMvc.perform(request.with(reader)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
        json(post(API), reader, objectMapper.writeValueAsString(
                body(unique("RD2"), "sftp.lezer.test", null, credential.getCredentialRef())))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        json(post(API), reader, "{}").andExpect(status().isForbidden());

        assertThat(profileCount()).isEqualTo(before);
    }

    // --- Helpers -------------------------------------------------------------------------------------------------------

    private ExternalCredential credential(String host, ExternalCredentialSecretKind kind) {
        return credentials.saveAndFlush(new ExternalCredential(UUID.randomUUID(), "LC-2 " + SEQUENCE.incrementAndGet(),
                kind, host, CIPHERTEXT, "lc2-test", USER, null, Instant.now()));
    }

    private static Map<String, Object> body(String code, String host, Integer port, UUID credentialRef) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", "Leverancier " + code);
        body.put("host", host);
        if (port != null) {
            body.put("port", port);
        }
        body.put("username", "leverancier");
        body.put("authMethod", "PASSWORD");
        body.put("credentialRef", credentialRef.toString());
        body.put("hostKeyAlgorithm", "ssh-ed25519");
        body.put("hostKeyFingerprintSha256", fingerprint());
        body.put("reason", "Nieuwe leverancier");
        return body;
    }

    private ResultActions create(Map<String, Object> body) throws Exception {
        return json(post(API), as(USER, Permission.MANAGE), objectMapper.writeValueAsString(body));
    }

    private void refused(Map<String, Object> body, int httpStatus, String code) throws Exception {
        String answer = create(body).andExpect(status().is(httpStatus)).andExpect(jsonPath("$.code").value(code))
                .andReturn().getResponse().getContentAsString();
        assertThat(answer).doesNotContain(CIPHERTEXT);
    }

    private ResultActions json(MockHttpServletRequestBuilder request, RequestPostProcessor actor, String body)
            throws Exception {
        return mockMvc.perform(request.with(actor).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions read(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(as(USER, Permission.MANAGE)));
    }

    private static String hashOf(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.versions[0].configHash");
    }

    private long profileCount() {
        return jdbc.queryForObject("select count(*) from connection_profile", Long.class);
    }

    static String fingerprint() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String unique(String prefix) {
        return "CP" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
    }
}
