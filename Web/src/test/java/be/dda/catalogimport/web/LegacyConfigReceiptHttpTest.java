package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.service.ActorIdentity;
import be.dda.catalogimport.service.LegacyConfigReceiptService;
import be.dda.catalogimport.service.LegacyConfigReceiptService.PushResult;
import be.dda.catalogimport.service.support.LegacyBundleCanonicalHash;
import be.dda.catalogimport.testsupport.TestPermissionSource;
import be.dda.catalogimport.testsupport.TestTechnicalClientJwt;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Slice S1 van {@code docs/design/legacy-config-bundle-design.md} (par. 2, 3, 4 en 8) over HTTP; contract
 * {@code docs/design/legacy-config-bundle-v1.md}; beslissingslog 2026-10-20 M2 (JWT via Keycloak, enkel op het
 * push-pad).
 *
 * <h2>Business rule vs. implementatie</h2>
 * <ul>
 *   <li><b>Regel:</b> enkel de technische client van de datasync mag pushen. <b>Implementatie:</b> tweede filterketen
 *       ({@code @Order(1)}) met JWT; 401 zonder (geldig) token, 403 bij verkeerde rol/{@code azp}/{@code aud}; een
 *       BFF-sessie, ook met MANAGE, krijgt 401; het token werkt op geen ander pad.</li>
 *   <li><b>Regel (beslissingslog 2026-10-20, "idempotentie tegenover de laatste ontvangst"):</b> een push wordt enkel
 *       vergeleken met de laatste ontvangst van de herkomst, ongeacht haar status. <b>Implementatie:</b> zelfde hash →
 *       200 {@code UNCHANGED} met id en status van die rij, niets geschreven; ook onder gelijktijdigheid één nieuwe
 *       rij ({@code uk_legacy_config_receipt_open} als enig serialisatiepunt + herhaling).</li>
 *   <li><b>Regel:</b> andere inhoud is altijd een nieuwe ontvangst, ook A → B → A. <b>Implementatie:</b> 201; een open
 *       ontvangst wordt {@code SUPERSEDED} met verwijzing, {@code APPLIED}/{@code DISCARDED} blijven ongemoeid
 *       ({@code supersededReceiptId = null}); de laatste ontvangst is nooit {@code SUPERSEDED}.</li>
 *   <li><b>Regel:</b> de bundel moet exact het contract volgen; hash herberekend. <b>Implementatie:</b> 400 met
 *       {@code LEGACY_BUNDLE_INVALID} (pad + regelcode), {@code ..._VERSION_UNSUPPORTED}, {@code ..._HASH_MISMATCH};
 *       413 {@code LEGACY_BUNDLE_TOO_LARGE}.</li>
 *   <li><b>Regel (S4):</b> een secret zonder sleutelring weigert de hele push en lekt nergens. <b>Implementatie:</b>
 *       409 {@code SECRETS_NOT_CONFIGURED} (deze context heeft bewust geen sleutelring), niets bewaard; de canary
 *       {@value #CANARY} staat in geen antwoord, logregel of databaserij. Een ander label, een lege of te lange waarde:
 *       400. De overname zelf: {@code LegacyConfigSecretLeakTest}.</li>
 *   <li><b>Regel:</b> inzage enkel met MANAGE (host/login, L7b). <b>Implementatie:</b> 403 vóór 404.</li>
 * </ul>
 * Elke test gebruikt een eigen dossier (herkomst): de database is gedeeld.
 */
@SpringBootTest(properties = "catalogimport.screening.recovery-on-startup=false")
@TestPropertySource(properties = {
        "catalogimport.technical-clients.legacy-config.audience=" + TestTechnicalClientJwt.AUDIENCE,
        "catalogimport.technical-clients.legacy-config.client-id=" + TestTechnicalClientJwt.CLIENT_ID,
        "catalogimport.technical-clients.legacy-config.required-role=" + TestTechnicalClientJwt.ROLE,
        // Bewust zonder sleutelring (V7): een bundel met secret wordt hier geweigerd (409 SECRETS_NOT_CONFIGURED).
        "catalogimport.secrets.keys=", "catalogimport.secrets.active-key-id=",
        // Eigen context (vaste clientwaarden): kleine pool, zodat samen met andere gecachte contexten de 50
        // verbindingen van de lokale PostgreSQL niet opraken. 5 volstaat voor de gelijktijdigheidstests (4 threads).
        "spring.datasource.hikari.maximum-pool-size=5"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class LegacyConfigReceiptHttpTest {

    static final String PUSH = "/api/catalog-import/legacy-config/receipts";
    static final String CANARY = "canary-golden-not-a-secret";
    /** Wordt in de geserialiseerde body vervangen door de JSON-escape van een ongepaarde surrogate. */
    private static final String LONE_SURROGATE = "__LONE_SURROGATE__";
    private static final String USER = "beheerder@example.test";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private LegacyConfigReceiptService service;

    /** Zonder de standaardlogin van {@code TestSecurityConfiguration}: zoals de datasync echt binnenkomt. */
    private MockMvc bareMvc;
    private Logger root;
    private ListAppender<ILoggingEvent> log;
    private final List<Logger> raised = new ArrayList<>();
    private final List<Level> originalLevels = new ArrayList<>();

    @BeforeEach
    void setUp() {
        bareMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        raise("be.dda.catalogimport", Level.TRACE);
        raise("org.springframework.security", Level.TRACE);
        raise("org.springframework.web", Level.TRACE);
        raise("org.hibernate.SQL", Level.DEBUG);
        raise("org.hibernate.orm.jdbc.bind", Level.TRACE);
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        log = new ListAppender<>();
        log.start();
        root.addAppender(log);
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(log);
        for (int i = 0; i < raised.size(); i++) {
            raised.get(i).setLevel(originalLevels.get(i));
        }
    }

    // --- Authenticatie en autorisatie van de push ---------------------------------------------------------------

    @Test
    void withoutATokenThePushIs401JsonAndNothingIsStored() throws Exception {
        ObjectNode bundle = bundle();

        bareMvc.perform(post(PUSH).contentType(MediaType.APPLICATION_JSON).content(bytes(bundle)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        assertThat(rows(bundle)).isZero();
    }

    @Test
    void anUntrustedSignatureAnExpiredTokenOrAnotherIssuerIs401() throws Exception {
        ObjectNode bundle = bundle();
        Instant past = Instant.now().minusSeconds(3600);
        List<String> tokens = List.of(
                TestTechnicalClientJwt.signedWithAnotherKey(),
                TestTechnicalClientJwt.token(c -> c.issueTime(Date.from(past))
                        .expirationTime(Date.from(past.plusSeconds(60)))),
                TestTechnicalClientJwt.token(c -> c.issuer("https://other.test.invalid/realms/x")),
                "not-a-jwt");

        for (String token : tokens) {
            push(bundle, token).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        }
        assertThat(rows(bundle)).isZero();
    }

    @Test
    void aValidTokenWithoutTheRoleOrWithAnotherAzpOrAudienceIs403AndNothingIsStored() throws Exception {
        ObjectNode bundle = bundle();
        List<String> tokens = List.of(
                // Rol ontbreekt, of staat onder een andere client.
                TestTechnicalClientJwt.token(c -> c.claim("resource_access",
                        TestTechnicalClientJwt.resourceAccess(TestTechnicalClientJwt.AUDIENCE, "other-role"))),
                TestTechnicalClientJwt.token(c -> c.claim("resource_access",
                        TestTechnicalClientJwt.resourceAccess("account", TestTechnicalClientJwt.ROLE))),
                TestTechnicalClientJwt.token(c -> c.claim("resource_access", null)),
                // Andere client (azp), of geen azp.
                TestTechnicalClientJwt.token(c -> c.claim("azp", "web_app")),
                TestTechnicalClientJwt.token(c -> c.claim("azp", null)),
                // Andere audience (rol staat wel onder web_app).
                TestTechnicalClientJwt.token(c -> c.audience(List.of("account"))));

        for (String token : tokens) {
            push(bundle, token).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
        assertThat(rows(bundle)).isZero();
        assertThat(messages()).anyMatch(line -> line.contains("LEGACY_CONFIG_PUSH_DENIED") && line.contains("ROLE"))
                .anyMatch(line -> line.contains("LEGACY_CONFIG_PUSH_DENIED") && line.contains("AUTHORIZED_PARTY"))
                .anyMatch(line -> line.contains("LEGACY_CONFIG_PUSH_DENIED") && line.contains("AUDIENCE"));
    }

    /** 403 komt vóór elke 400: ook een onleesbare body met een verkeerde rol geeft 403. */
    @Test
    void theRoleIsCheckedBeforeTheBody() throws Exception {
        String wrongRole = TestTechnicalClientJwt.token(c -> c.claim("resource_access",
                TestTechnicalClientJwt.resourceAccess(TestTechnicalClientJwt.AUDIENCE, "other-role")));

        bareMvc.perform(post(PUSH).header(HttpHeaders.AUTHORIZATION, "Bearer " + wrongRole)
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isForbidden());
    }

    /** Een BFF-aanmelding met MANAGE (zoals in elke andere test) telt op het push-pad niet: 401, niets bewaard. */
    @Test
    void aSessionLoginWithManageGets401OnThePushPath() throws Exception {
        ObjectNode bundle = bundle();

        bareMvc.perform(post(PUSH).with(as(USER, Permission.MANAGE, Permission.APPROVE)).with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON).content(bytes(bundle)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        // Ook de standaardtestlogin (alle rechten) van de gewone MockMvc.
        mockMvc.perform(post(PUSH).contentType(MediaType.APPLICATION_JSON).content(bytes(bundle)))
                .andExpect(status().isUnauthorized());

        assertThat(rows(bundle)).isZero();
    }

    /**
     * Een echte sessie (beveiligingscontext in de HTTP-sessie, zoals na de Keycloak-login): op een BFF-pad werkt ze
     * (200), op het push-pad wordt ze niet eens gelezen (stateless keten) en is het 401.
     */
    @Test
    void aRealSessionWithManageWorksOnTheBffPathsButNotOnThePushPath() throws Exception {
        ObjectNode bundle = bundle();
        MockHttpSession session = sessionWithManage();

        bareMvc.perform(get(PUSH).session(session)).andExpect(status().isOk());
        bareMvc.perform(post(PUSH).session(session).with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON).content(bytes(bundle)))
                .andExpect(status().isUnauthorized());

        assertThat(rows(bundle)).isZero();
    }

    @Test
    void theTokenWorksOnNoOtherPath() throws Exception {
        String token = TestTechnicalClientJwt.validToken();
        long id = created(push(bundle(), token));

        // POST's met een geldig CSRF-token, zodat enkel de aanmelding beslist (zonder CSRF: 403 CSRF_TOKEN_INVALID).
        for (var request : List.of(get(PUSH), get(PUSH + "/{id}", id), get("/api/catalog-import/me"),
                get("/api/catalog-import/batches"),
                post(PUSH + "/{id}/apply", id).with(csrf().asHeader()).content("{}"),
                post("/api/catalog-import/bundles").with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))) {
            bareMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        }
    }

    // --- Ontvangst, idempotentie en vervangen ------------------------------------------------------------------

    @Test
    void aValidBundleIs201ThenTheSameBundleIs200UnchangedWithTheSameId() throws Exception {
        ObjectNode bundle = bundle();
        String token = TestTechnicalClientJwt.validToken();

        MvcResult first = push(bundle, token)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("RECEIVED"))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.contentHash").value(bundle.get("contentHash").textValue()))
                .andExpect(jsonPath("$.supersededReceiptId").doesNotExist())
                .andReturn();
        long id = ((Number) JsonPath.read(first.getResponse().getContentAsString(), "$.receiptId")).longValue();

        // Zelfde inhoud, ander exporttijdstip en andere appversie: buiten de hash, dus UNCHANGED.
        bundle.put("exportedAt", "2026-10-22T08:00:00+02:00");
        ((ObjectNode) bundle.get("exporter")).put("appVersion", "9.9.9");
        push(bundle, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("UNCHANGED"))
                .andExpect(jsonPath("$.receiptId").value(id));

        assertThat(rows(bundle)).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("select * from legacy_config_receipt where id = ?", id);
        assertThat(row.get("status")).isEqualTo("RECEIVED");
        assertThat(row.get("open_marker")).isEqualTo(true);
        assertThat(row.get("received_by")).isEqualTo("svc:" + TestTechnicalClientJwt.CLIENT_ID);
        assertThat(row.get("received_by_subject")).isEqualTo(TestTechnicalClientJwt.SUBJECT);
        assertThat(row.get("interface_code")).isEqualTo("GOLDEN_ART");
        assertThat(row.get("bundle_version")).isEqualTo(1);
        assertThat(row.get("legacy_definition_code")).isEqualTo("VROOAM2");
        assertThat(row.get("definition_content_hash")).isEqualTo(bundle.get("definitionContentHash").textValue());
        assertThat(row.get("secret_received")).isEqualTo(false);
        assertThat(row.get("secret_outcome")).isNull();
        assertThat(row.get("credential_id")).isNull();
        JsonNode stored = MAPPER.readTree((String) row.get("bundle_json"));
        assertThat(stored.get("secret").isNull()).isTrue();
        assertThat(LegacyBundleCanonicalHash.contentHash(stored)).isEqualTo(row.get("content_hash"));
        // De eerste exporttijd blijft (UNCHANGED schrijft niets).
        assertThat(stored.get("exportedAt").textValue()).isEqualTo("2026-10-21T10:15:00+02:00");
    }

    @Test
    void theMissingDefinitionFixtureIsAcceptedWithoutDefinitionHashOrCode() throws Exception {
        ObjectNode bundle = (ObjectNode) fixture("golden-bundle-v1-missing-definition.json");
        ((ObjectNode) bundle.get("origin")).put("dossier", uniqueDossier());

        long id = created(push(bundle, TestTechnicalClientJwt.validToken()));

        Map<String, Object> row = jdbc.queryForMap("select * from legacy_config_receipt where id = ?", id);
        assertThat(row.get("definition_content_hash")).isNull();
        assertThat(row.get("legacy_definition_code")).isNull();
        assertThat(row.get("content_hash")).isEqualTo("d8b6c6166e0416f888730d49342ae10811f68797bc0c3848efbb3ad885ada912");
    }

    @Test
    void newContentSupersedesTheOpenReceiptAndReturningContentIsANewReceipt() throws Exception {
        ObjectNode a = bundle();
        String token = TestTechnicalClientJwt.validToken();
        long first = created(push(a, token));
        Map<String, Object> firstBefore = row(first);

        ObjectNode b = withChangedContent(a.deepCopy(), "Gewijzigde info");
        MvcResult second = push(b, token)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.supersededReceiptId").value(first))
                .andReturn();
        long secondId = receiptId(second);

        Map<String, Object> old = row(first);
        assertThat(old.get("status")).isEqualTo("SUPERSEDED");
        assertThat(old.get("open_marker")).isNull();
        assertThat(((Number) old.get("superseded_by_receipt_id")).longValue()).isEqualTo(secondId);
        assertThat(openCount(a)).isEqualTo(1);

        // A -> B -> A (beslissingslog 2026-10-20): enkel de laatste ontvangst (B) telt, dus een nieuwe ontvangst A'.
        MvcResult third = push(a, token)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("RECEIVED"))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.supersededReceiptId").value(secondId))
                .andReturn();
        long thirdId = receiptId(third);
        assertThat(thirdId).isNotEqualTo(first).isNotEqualTo(secondId);
        Map<String, Object> bAfter = row(secondId);
        assertThat(bAfter.get("status")).isEqualTo("SUPERSEDED");
        assertThat(((Number) bAfter.get("superseded_by_receipt_id")).longValue()).isEqualTo(thirdId);
        // De eerste A is ongewijzigd (nog altijd SUPERSEDED door B).
        assertThat(row(first)).isEqualTo(old);
        assertThat(firstBefore.get("bundle_json")).isEqualTo(old.get("bundle_json"));
        assertThat(rows(a)).isEqualTo(3);
        assertThat(openCount(a)).isEqualTo(1);
        assertThat(row(thirdId).get("open_marker")).isEqualTo(true);

        // Nog eens A: nu is A' de laatste ontvangst, dus UNCHANGED met id A'.
        push(a, token).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("UNCHANGED"))
                .andExpect(jsonPath("$.receiptId").value(thirdId))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.supersededReceiptId").doesNotExist());
        assertThat(rows(a)).isEqualTo(3);
    }

    /** (2) De laatste ontvangst is APPLIED met dezelfde inhoud: UNCHANGED met status APPLIED, geen nieuwe rij. */
    @Test
    void theSameContentAsTheLatestAppliedReceiptIsUnchanged() throws Exception {
        ObjectNode a = bundle();
        String token = TestTechnicalClientJwt.validToken();
        long first = created(push(a, token));
        markApplied(first);

        push(a, token).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("UNCHANGED"))
                .andExpect(jsonPath("$.receiptId").value(first))
                .andExpect(jsonPath("$.status").value("APPLIED"));
        assertThat(rows(a)).isEqualTo(1);
    }

    /** (3) A APPLIED → B → A: twee nieuwe ontvangsten; A blijft APPLIED met ongewijzigde applied_*. */
    @Test
    void anAppliedReceiptIsNeverTouchedByLaterPushes() throws Exception {
        ObjectNode a = bundle();
        String token = TestTechnicalClientJwt.validToken();
        long first = created(push(a, token));
        markApplied(first);
        Map<String, Object> appliedBefore = row(first);

        MvcResult second = push(withChangedContent(a.deepCopy(), "B"), token)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.supersededReceiptId").doesNotExist())
                .andReturn();
        push(a, token).andExpect(status().isCreated())
                .andExpect(jsonPath("$.supersededReceiptId").value(receiptId(second)));

        assertThat(row(first)).isEqualTo(appliedBefore);
        assertThat(row(first).get("status")).isEqualTo("APPLIED");
        assertThat(rows(a)).isEqualTo(3);
        assertThat(openCount(a)).isEqualTo(1);
    }

    /** (4) Laatste = APPLIED B, push A: 201 zonder vervangen ontvangst. */
    @Test
    void newContentAfterAnAppliedReceiptSupersedesNothing() throws Exception {
        ObjectNode a = bundle();
        ObjectNode b = withChangedContent(a.deepCopy(), "B");
        String token = TestTechnicalClientJwt.validToken();
        long bId = created(push(b, token));
        markApplied(bId);

        push(a, token).andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("RECEIVED"))
                .andExpect(jsonPath("$.supersededReceiptId").doesNotExist());

        assertThat(row(bId).get("status")).isEqualTo("APPLIED");
        assertThat(rows(a)).isEqualTo(2);
    }

    /** (5) Laatste = DISCARDED A, push A: UNCHANGED met status DISCARDED. */
    @Test
    void theSameContentAsTheLatestDiscardedReceiptIsUnchanged() throws Exception {
        ObjectNode a = bundle();
        String token = TestTechnicalClientJwt.validToken();
        long first = created(push(a, token));
        jdbc.update("update legacy_config_receipt set status = 'DISCARDED', open_marker = null, discarded_at = now(), "
                + "discarded_by = ?, discarded_reason = 'Niet nodig' where id = ?", USER, first);

        push(a, token).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("UNCHANGED"))
                .andExpect(jsonPath("$.receiptId").value(first))
                .andExpect(jsonPath("$.status").value("DISCARDED"));
        assertThat(rows(a)).isEqualTo(1);
    }

    @Test
    void anotherInterfaceOfTheSameDossierIsItsOwnOrigin() throws Exception {
        ObjectNode a = bundle();
        ObjectNode other = a.deepCopy();
        ((ObjectNode) other.get("interface")).put("code", "GOLDEN_ART2");
        other.put("contentHash", LegacyBundleCanonicalHash.contentHash(other));
        String token = TestTechnicalClientJwt.validToken();

        created(push(a, token));
        push(other, token).andExpect(status().isCreated()).andExpect(jsonPath("$.supersededReceiptId").doesNotExist());

        assertThat(jdbc.queryForObject("select count(*) from legacy_config_receipt where origin_dossier = ? "
                + "and status = 'RECEIVED'", Long.class, dossier(a))).isEqualTo(2);
    }

    /** Gelijktijdige identieke pushes: één rij, één 201 ({@code RECEIVED}), de rest {@code UNCHANGED}. */
    @Test
    void concurrentIdenticalPushesCreateExactlyOneReceipt() throws Exception {
        ObjectNode bundle = bundle();
        byte[] body = bytes(bundle);
        List<PushResult> results = concurrently(4, () -> service.receive(body.clone(), client()));

        assertThat(results).extracting(PushResult::outcome).containsOnlyOnce("RECEIVED");
        assertThat(results).extracting(PushResult::receiptId).containsOnly(results.get(0).receiptId());
        assertThat(rows(bundle)).isEqualTo(1);
    }

    /** (6a) Laatste = APPLIED B; 4× A gelijktijdig: precies één nieuwe rij, de rest UNCHANGED met hetzelfde id. */
    @Test
    void concurrentPushesAfterAnAppliedReceiptCreateExactlyOneNewReceipt() throws Exception {
        ObjectNode a = bundle();
        long bId = service.receive(bytes(withChangedContent(a.deepCopy(), "B")), client()).receiptId();
        markApplied(bId);
        byte[] body = bytes(a);

        List<PushResult> results = concurrently(4, () -> service.receive(body.clone(), client()));

        assertOneNewReceiptForAll(results);
        assertThat(rows(a)).isEqualTo(2);
        assertThat(row(bId).get("status")).isEqualTo("APPLIED");
    }

    /** (6b) Laatste = APPLIED A; 4× A gelijktijdig: niets nieuws, allemaal UNCHANGED met status APPLIED. */
    @Test
    void concurrentPushesOfTheLatestAppliedContentAreAllUnchanged() throws Exception {
        ObjectNode a = bundle();
        long first = service.receive(bytes(a), client()).receiptId();
        markApplied(first);
        byte[] body = bytes(a);

        List<PushResult> results = concurrently(4, () -> service.receive(body.clone(), client()));

        assertThat(results).allSatisfy(r -> {
            assertThat(r.outcome()).isEqualTo("UNCHANGED");
            assertThat(r.receiptId()).isEqualTo(first);
            assertThat(r.status()).isEqualTo("APPLIED");
        });
        assertThat(rows(a)).isEqualTo(1);
    }

    /** (6c) Na A → B (B open); 4× A gelijktijdig: precies één nieuwe A', de rest UNCHANGED met id A'. */
    @Test
    void concurrentReturningContentCreatesExactlyOneNewReceipt() throws Exception {
        ObjectNode a = bundle();
        service.receive(bytes(a), client());
        long bId = service.receive(bytes(withChangedContent(a.deepCopy(), "B")), client()).receiptId();
        byte[] body = bytes(a);

        List<PushResult> results = concurrently(4, () -> service.receive(body.clone(), client()));

        long newId = assertOneNewReceiptForAll(results);
        assertThat(results).filteredOn(r -> r.outcome().equals("RECEIVED")).singleElement()
                .extracting(PushResult::supersededReceiptId).isEqualTo(bId);
        assertThat(rows(a)).isEqualTo(3);
        assertThat(openCount(a)).isEqualTo(1);
        assertThat(((Number) row(bId).get("superseded_by_receipt_id")).longValue()).isEqualTo(newId);
    }

    /** Gelijktijdige pushes met verschillende inhoud: allemaal bewaard, precies één blijft open. */
    @Test
    void concurrentDifferentPushesLeaveExactlyOneOpenReceipt() throws Exception {
        ObjectNode base = bundle();
        List<byte[]> bodies = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            bodies.add(bytes(withChangedContent(base.deepCopy(), "Variant " + i)));
        }
        List<Callable<PushResult>> tasks = bodies.stream()
                .<Callable<PushResult>>map(body -> () -> service.receive(body.clone(), client())).toList();
        List<PushResult> results = concurrently(tasks);

        assertThat(results).extracting(PushResult::outcome).containsOnly("RECEIVED");
        assertThat(rows(base)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from legacy_config_receipt where origin_dossier = ? "
                + "and open_marker = true", Long.class, dossier(base))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from legacy_config_receipt where origin_dossier = ? "
                + "and status = 'SUPERSEDED' and superseded_by_receipt_id is not null", Long.class, dossier(base)))
                .isEqualTo(2);
    }

    // --- Validatie ---------------------------------------------------------------------------------------------

    @Test
    void aWrongContentHashOrDefinitionHashIs400HashMismatch() throws Exception {
        ObjectNode bundle = bundle();
        String token = TestTechnicalClientJwt.validToken();

        ObjectNode wrongContent = bundle.deepCopy().put("contentHash", "a".repeat(64));
        push(wrongContent, token).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LEGACY_BUNDLE_HASH_MISMATCH"));

        ObjectNode wrongDefinition = bundle.deepCopy().put("definitionContentHash", "b".repeat(64));
        push(wrongDefinition, token).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LEGACY_BUNDLE_HASH_MISMATCH"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.startsWith("definitionContentHash")));

        // Inhoud gewijzigd zonder de hash bij te werken.
        ObjectNode tampered = bundle.deepCopy();
        ((ObjectNode) tampered.get("interface").get("header")).put("info", "anders");
        push(tampered, token).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LEGACY_BUNDLE_HASH_MISMATCH"));

        assertThat(rows(bundle)).isZero();
    }

    @Test
    void anotherVersionIs400VersionUnsupportedEvenWithFieldsThatV1DoesNotKnow() throws Exception {
        ObjectNode bundle = bundle();
        String token = TestTechnicalClientJwt.validToken();

        push(bundle.deepCopy().put("version", 2), token).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LEGACY_BUNDLE_VERSION_UNSUPPORTED"));
        ObjectNode v2 = bundle.deepCopy().put("version", 2);
        v2.put("newInV2", true);
        push(v2, token).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LEGACY_BUNDLE_VERSION_UNSUPPORTED"));
        push(bundle.deepCopy().put("version", 0), token).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LEGACY_BUNDLE_VERSION_UNSUPPORTED"));

        assertThat(rows(bundle)).isZero();
    }

    static Stream<Arguments> invalidBundles() {
        return Stream.of(
                invalid("unknown root field", b -> b.put("extra", 1), "$.extra (UNKNOWN_FIELD)"),
                invalid("unknown nested field", b -> ((ObjectNode) b.get("interface").get("header")).put("x", ""),
                        "$.interface.header.x (UNKNOWN_FIELD)"),
                invalid("missing field", b -> ((ObjectNode) b.get("definition")).remove("maps"),
                        "$.definition.maps (REQUIRED)"),
                invalid("schema", b -> b.put("schema", "other"), "$.schema (PATTERN)"),
                invalid("version as text", b -> b.put("version", "1"), "$.version (TYPE)"),
                invalid("fraction", b -> ((ObjectNode) b.get("interface").get("header")).put("dayOfMonth", 1.0),
                        "$.interface.header.dayOfMonth (TYPE)"),
                invalid("range", b -> ((ObjectNode) b.get("interface").get("header")).put("monthOfYear", 100),
                        "$.interface.header.monthOfYear (RANGE)"),
                invalid("negative sequence", b -> ((ObjectNode) b.get("interface").get("details").get(2))
                        .put("sequence", -1), "$.interface.details[2].sequence (RANGE)"),
                invalid("weekdays", b -> ((ArrayNode) b.get("interface").get("header").get("weekdays")).remove(0),
                        "$.interface.header.weekdays (LENGTH)"),
                invalid("environment too long", b -> ((ObjectNode) b.get("origin")).put("environment", "E".repeat(41)),
                        "$.origin.environment (LENGTH)"),
                invalid("blank dossier", b -> ((ObjectNode) b.get("origin")).put("dossier", "  "),
                        "$.origin.dossier (LENGTH)"),
                invalid("interface code too long", b -> ((ObjectNode) b.get("interface")).put("code", "I".repeat(21)),
                        "$.interface.code (LENGTH)"),
                invalid("definition code too long", b -> ((ObjectNode) b.get("definition")).put("code", "D".repeat(11)),
                        "$.definition.code (LENGTH)"),
                invalid("legacy master", b -> ((ObjectNode) b.get("origin")).put("legacyMaster", "PROFAB"),
                        "$.origin.legacyMaster (PATTERN)"),
                invalid("exportedAt without offset", b -> b.put("exportedAt", "2026-10-21T10:15:00"),
                        "$.exportedAt (PATTERN)"),
                invalid("contentHash uppercase", b -> b.put("contentHash", "A".repeat(64)), "$.contentHash (PATTERN)"),
                invalid("password label in details", b -> ((ArrayNode) b.get("interface").get("details")).addObject()
                        .put("sequence", 3).put("label", "FTP_password").put("value", "x"),
                        "$.interface.details[9].label (SECRET_IN_DETAILS)"),
                invalid("token label in details", b -> ((ArrayNode) b.get("interface").get("details")).addObject()
                        .put("sequence", 7).put("label", "Api_Token").put("value", ""),
                        "$.interface.details[9].label (SECRET_IN_DETAILS)"),
                invalid("userinfo in FTP", b -> ((ObjectNode) b.get("interface").get("details").get(1))
                        .put("value", "sftp://user:pw@sftp.example.test/x"),
                        "$.interface.details[1].value (SECRET_IN_DETAILS)"),
                invalid("duplicate detail", b -> ((ArrayNode) b.get("interface").get("details")).addObject()
                        .put("sequence", 1).put("label", "FTP").put("value", "other"),
                        "$.interface.details[9] (DUPLICATE)"),
                invalid("duplicate omitted label", b -> ((ArrayNode) b.get("interface").get("omittedSecretLabels"))
                        .add("API_AUTH_PASSWORD"), "$.interface.omittedSecretLabels[1] (DUPLICATE)"),
                invalid("present without definition", b -> b.putNull("definition"), "$.definition (STATUS_MISMATCH)"),
                invalid("missing with definition", b -> b.put("definitionStatus", "MISSING"),
                        "$.definitionContentHash (STATUS_MISMATCH)"),
                invalid("unknown definition status", b -> b.put("definitionStatus", "GONE"),
                        "$.definitionStatus (PATTERN)"),
                invalid("unpaired surrogate", b -> ((ObjectNode) b.get("definition")).put("description", LONE_SURROGATE),
                        "$.definition.description (PATTERN)"),
                invalid("secret with another label", b -> b.putObject("secret").put("label", "FTP")
                        .put("value", CANARY), "$.secret.label (PATTERN)"),
                invalid("secret with empty value", b -> b.putObject("secret").put("label", "FTP_password")
                        .put("value", ""), "$.secret.value (LENGTH)"),
                invalid("secret with blank value", b -> b.putObject("secret").put("label", "FTP_password")
                        .put("value", "   "), "$.secret.value (LENGTH)"),
                invalid("secret longer than the credential store", b -> b.putObject("secret")
                        .put("label", "FTP_password").put("value", CANARY + "x".repeat(1024)),
                        "$.secret.value (LENGTH)"),
                invalid("secret with an unknown field", b -> b.putObject("secret").put("label", "FTP_password")
                        .put("value", CANARY).put("x", 1), "$.secret.x (UNKNOWN_FIELD)"),
                invalid("secret as text", b -> b.put("secret", CANARY), "$.secret (TYPE)"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBundles")
    void anInvalidBundleIs400WithOnlyAPathAndARuleCode(String name, Consumer<ObjectNode> breaker, String expected)
            throws Exception {
        ObjectNode bundle = bundle();
        breaker.accept(bundle);

        byte[] body = new String(bytes(bundle), StandardCharsets.UTF_8).replace(LONE_SURROGATE, "x\\ud800y")
                .getBytes(StandardCharsets.UTF_8);

        MvcResult result = push(body, TestTechnicalClientJwt.validToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LEGACY_BUNDLE_INVALID"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains(expected).doesNotContain(CANARY);
        assertThat(rows(bundle)).isZero();
    }

    @Test
    void unreadableJsonAndNonUtf8AreRejectedWithoutEchoingTheInput() throws Exception {
        String token = TestTechnicalClientJwt.validToken();
        String json = new String(bytes(bundle()), StandardCharsets.UTF_8);
        List<byte[]> bodies = List.of(
                // De canary als ongequote waarde: Jackson zou ze in zijn melding citeren.
                json.replace("\"secret\":null", "\"secret\":{\"label\":\"FTP_password\",\"value\":" + CANARY + "}")
                        .getBytes(StandardCharsets.UTF_8),
                json.replaceFirst("\\{", "{\"schema\":\"x\",").getBytes(StandardCharsets.UTF_8),
                (json + " trailing").getBytes(StandardCharsets.UTF_8),
                withBom(json.getBytes(StandardCharsets.UTF_8)),
                new byte[] {'{', '"', 's', '"', ':', '"', (byte) 0xC3, (byte) 0x28, '"', '}'},
                new byte[0],
                "[]".getBytes(StandardCharsets.UTF_8));

        for (byte[] body : bodies) {
            MvcResult result = bareMvc.perform(post(PUSH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("LEGACY_BUNDLE_INVALID"))
                    .andReturn();
            assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain(CANARY);
        }
        assertLogsDoNotContain(CANARY);
    }

    @Test
    void aBodyOverTheLimitIs413() throws Exception {
        ObjectNode bundle = bundle();
        byte[] json = bytes(bundle);
        byte[] padded = new byte[2 * 1024 * 1024 + 1];
        java.util.Arrays.fill(padded, (byte) ' ');
        System.arraycopy(json, 0, padded, 0, json.length);

        push(padded, TestTechnicalClientJwt.validToken())
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("LEGACY_BUNDLE_TOO_LARGE"));

        assertThat(rows(bundle)).isZero();
    }

    /** Exact op de grens (2 MB, met witruimte opgevuld) is nog toegelaten. */
    @Test
    void aBodyOfExactlyTheLimitIsAccepted() throws Exception {
        ObjectNode bundle = bundle();
        byte[] json = bytes(bundle);
        byte[] padded = new byte[2 * 1024 * 1024];
        java.util.Arrays.fill(padded, (byte) ' ');
        System.arraycopy(json, 0, padded, 0, json.length);

        push(padded, TestTechnicalClientJwt.validToken()).andExpect(status().isCreated());
    }

    // --- Secret zonder sleutelring (S4: 409, niets bewaard, nergens zichtbaar) ---------------------------------

    @Test
    void aBundleWithASecretWithoutAKeyRingIs409AndTheCanaryAppearsNowhere() throws Exception {
        ObjectNode golden = (ObjectNode) fixture("golden-bundle-v1.json");
        String dossier = uniqueDossier();
        ((ObjectNode) golden.get("origin")).put("dossier", dossier);
        assertThat(golden.get("secret").get("value").textValue()).isEqualTo(CANARY);
        long credentialsBefore = jdbc.queryForObject("select count(*) from external_credential", Long.class);

        MvcResult result = push(golden, TestTechnicalClientJwt.validToken())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SECRETS_NOT_CONFIGURED"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain(CANARY);
        assertThat(rows(golden)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from legacy_config_origin where origin_dossier = ?",
                Long.class, dossier)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from external_credential", Long.class))
                .isEqualTo(credentialsBefore);

        // Ook wanneer de inhoud al eens (zonder secret) binnenkwam: de hele push wordt geweigerd, niet UNCHANGED.
        ObjectNode withoutSecret = golden.deepCopy();
        withoutSecret.putNull("secret");
        long id = created(push(withoutSecret, TestTechnicalClientJwt.validToken()));
        push(golden, TestTechnicalClientJwt.validToken()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SECRETS_NOT_CONFIGURED"));
        assertThat(rows(golden)).isEqualTo(1);
        assertThat(row(id).get("secret_received")).isEqualTo(false);
        assertThat(jdbc.queryForObject("select count(*) from legacy_config_receipt where bundle_json like ? "
                + "or apply_report_json like ?", Long.class, "%" + CANARY + "%", "%" + CANARY + "%")).isZero();
        assertThat(log.list).as("captured log lines").isNotEmpty();
        assertLogsDoNotContain(CANARY);
    }

    // --- Inzage ------------------------------------------------------------------------------------------------

    @Test
    void listAndDetailNeedManageAndNeverShowASecret() throws Exception {
        ObjectNode bundle = bundle();
        long id = created(push(bundle, TestTechnicalClientJwt.validToken()));

        mockMvc.perform(get(PUSH).param("interface", "GOLDEN_ART").param("status", "RECEIVED").param("size", "200")
                        .with(as(USER, Permission.MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + id + ")].originDossier").value(dossier(bundle)))
                .andExpect(jsonPath("$.content[0].bundle").doesNotExist());
        mockMvc.perform(get(PUSH).param("status", "SUPERSEDED").param("interface", "GOLDEN_ART")
                        .param("size", "200").with(as(USER, Permission.MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + id + ")]").isEmpty());

        mockMvc.perform(get(PUSH + "/{id}", id).with(as(USER, Permission.MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receipt.id").value(id))
                .andExpect(jsonPath("$.receipt.status").value("RECEIVED"))
                .andExpect(jsonPath("$.receipt.receivedBy").value("svc:" + TestTechnicalClientJwt.CLIENT_ID))
                .andExpect(jsonPath("$.secretOutcome").doesNotExist())
                .andExpect(jsonPath("$.credentialRef").doesNotExist())
                .andExpect(jsonPath("$.bundle.secret").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.bundle.interface.code").value("GOLDEN_ART"));

        mockMvc.perform(get(PUSH + "/{id}", Long.MAX_VALUE).with(as(USER, Permission.MANAGE)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LEGACY_RECEIPT_NOT_FOUND"));
    }

    @Test
    void readOnlyUsersGet403BeforeAnything() throws Exception {
        long id = created(push(bundle(), TestTechnicalClientJwt.validToken()));

        for (var request : List.of(get(PUSH), get(PUSH + "/{id}", id), get(PUSH + "/{id}", Long.MAX_VALUE),
                get(PUSH).param("status", "NOT_A_STATUS"))) {
            mockMvc.perform(request.with(as(USER, Permission.READ)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
    }

    // --- Functie uit: geen technische client geconfigureerd -------------------------------------------------

    /**
     * Zonder {@code audience}/{@code client-id} start de applicatie en geeft de push altijd 401 — ook met een token
     * dat anders geldig zou zijn; het pad valt niet terug op de BFF-keten.
     */
    @Nested
    @TestPropertySource(properties = {
            "catalogimport.technical-clients.legacy-config.audience=",
            "catalogimport.technical-clients.legacy-config.client-id=",
            "spring.datasource.hikari.maximum-pool-size=2"})
    class WithoutTechnicalClientConfiguration {

        /** Eigen velden: deze geneste klasse heeft een eigen context (andere properties). */
        @Autowired
        private WebApplicationContext disabledContext;

        @Test
        void thePushIsAlways401AndTheRestOfTheApplicationWorks() throws Exception {
            MockMvc bare = MockMvcBuilders.webAppContextSetup(disabledContext).apply(springSecurity()).build();
            ObjectNode bundle = bundle();

            bare.perform(post(PUSH).header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTechnicalClientJwt.validToken())
                            .contentType(MediaType.APPLICATION_JSON).content(bytes(bundle)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
            bare.perform(post(PUSH).with(as(USER, Permission.MANAGE)).with(csrf().asHeader())
                            .contentType(MediaType.APPLICATION_JSON).content(bytes(bundle)))
                    .andExpect(status().isUnauthorized());
            bare.perform(get(PUSH).with(as(USER, Permission.MANAGE))).andExpect(status().isOk());

            assertThat(rows(bundle)).isZero();
        }
    }

    // --- Hulp ------------------------------------------------------------------------------------------------

    private ResultActions push(JsonNode bundle, String token) throws Exception {
        return push(bytes(bundle), token);
    }

    private ResultActions push(byte[] body, String token) throws Exception {
        return bareMvc.perform(post(PUSH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("select * from legacy_config_receipt where id = ?", id);
    }

    private long openCount(JsonNode bundle) {
        return jdbc.queryForObject("select count(*) from legacy_config_receipt where origin_dossier = ? "
                + "and open_marker = true", Long.class, dossier(bundle));
    }

    /** Zet een ontvangst op APPLIED zoals S2 dat zal doen (S1 kent nog geen APPLY). */
    private void markApplied(long id) {
        jdbc.update("update legacy_config_receipt set status = 'APPLIED', open_marker = null, "
                + "applied_at = timestamp with time zone '2026-10-21 12:00:00+00', applied_by = ?, "
                + "applied_by_subject = 'sub-apply' where id = ?", USER, id);
    }

    /** Precies één RECEIVED, alle resultaten wijzen naar hetzelfde id; geeft dat id terug. */
    private static long assertOneNewReceiptForAll(List<PushResult> results) {
        assertThat(results).extracting(PushResult::outcome).containsOnlyOnce("RECEIVED");
        long id = results.get(0).receiptId();
        assertThat(results).extracting(PushResult::receiptId).containsOnly(id);
        return id;
    }

    private static long receiptId(MvcResult result) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.receiptId")).longValue();
    }

    private static long created(ResultActions result) throws Exception {
        String body = result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.receiptId")).longValue();
    }

    /** De golden fixture zonder secret, met een eigen dossier (herkomst) per test. */
    static ObjectNode bundle() throws IOException {
        ObjectNode bundle = (ObjectNode) fixture("golden-bundle-v1.json");
        bundle.putNull("secret");
        ((ObjectNode) bundle.get("origin")).put("dossier", uniqueDossier());
        return bundle;
    }

    /** Wijzigt {@code header.info} en werkt {@code contentHash} bij (de definitie blijft gelijk). */
    static ObjectNode withChangedContent(ObjectNode bundle, String info) {
        ((ObjectNode) bundle.get("interface").get("header")).put("info", info);
        bundle.put("contentHash", LegacyBundleCanonicalHash.contentHash(bundle));
        return bundle;
    }

    static JsonNode fixture(String name) throws IOException {
        try (InputStream in = LegacyConfigReceiptHttpTest.class.getResourceAsStream("/legacy-config/" + name)) {
            return MAPPER.readTree(in);
        }
    }

    static byte[] bytes(JsonNode node) throws IOException {
        return MAPPER.writeValueAsBytes(node);
    }

    private static String uniqueDossier() {
        return "S1-" + UUID.randomUUID().toString().substring(0, 13);
    }

    private static String dossier(JsonNode bundle) {
        return bundle.get("origin").get("dossier").textValue();
    }

    private long rows(JsonNode bundle) {
        return jdbc.queryForObject("select count(*) from legacy_config_receipt where origin_dossier = ?", Long.class,
                dossier(bundle));
    }

    private static ActorIdentity client() {
        return new ActorIdentity("svc:" + TestTechnicalClientJwt.CLIENT_ID, TestTechnicalClientJwt.SUBJECT);
    }

    private static Arguments invalid(String name, Consumer<ObjectNode> breaker, String expected) {
        return Arguments.of(name, breaker, expected);
    }

    private static byte[] withBom(byte[] json) {
        byte[] out = new byte[json.length + 3];
        out[0] = (byte) 0xEF;
        out[1] = (byte) 0xBB;
        out[2] = (byte) 0xBF;
        System.arraycopy(json, 0, out, 3, json.length);
        return out;
    }

    private static MockHttpSession sessionWithManage() {
        Instant now = Instant.now();
        OidcIdToken idToken = new OidcIdToken("id-token", now, now.plusSeconds(300), Map.of(
                "sub", "test-sub-session", "preferred_username", USER,
                TestPermissionSource.CLAIM, List.of(Permission.MANAGE.code())));
        var authorities = List.of(new SimpleGrantedAuthority("OIDC_USER"));
        DefaultOidcUser user = new DefaultOidcUser(authorities, idToken, "preferred_username");
        OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(user, authorities, "keycloak");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(authentication));
        return session;
    }

    private static List<PushResult> concurrently(int count, Callable<PushResult> task) throws Exception {
        List<Callable<PushResult>> tasks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            tasks.add(task);
        }
        return concurrently(tasks);
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

    private List<String> messages() {
        return log.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private void assertLogsDoNotContain(String forbidden) {
        for (ILoggingEvent event : log.list) {
            assertThat(event.getFormattedMessage()).as(event.getLoggerName()).doesNotContain(forbidden);
            for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                assertThat(String.valueOf(proxy.getMessage())).as(event.getLoggerName()).doesNotContain(forbidden);
            }
        }
    }
}
