package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.ExternalCredentialRepository;
import be.dda.catalogimport.domain.ExternalCredential;
import be.dda.catalogimport.domain.ExternalCredentialSecretKind;
import be.dda.catalogimport.service.ActorIdentity;
import be.dda.catalogimport.service.ConnectionProfileService;
import be.dda.catalogimport.service.ConnectionProfileService.NewConnectionProfile;
import be.dda.catalogimport.service.DeliveryConfigurationService;
import be.dda.catalogimport.service.support.RevisionConfigHashes;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
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
 * Bouwstap LC-2: Leveringsconfiguraties ({@code docs/design/leveringsconfiguratie-design.md} par. 3.2, 6, 10;
 * beslissingslog 2026-09-29 L3, L7, A3, A12, A17).
 *
 * <ul>
 *   <li><b>Regel:</b> condities in groepen (EN binnen, OF tussen), 1-gebaseerd genummerd. <b>Data:</b>
 *       {@code delivery_configuration_file_condition.group_number/sequence_number} rechtstreeks gelezen.</li>
 *   <li><b>Regel:</b> {@code ALL_FILES} = geen condities, {@code CONDITIONS} = minstens één; letterlijke waarden.</li>
 *   <li><b>Regel:</b> externe map absoluut, geen {@code ..} (A17); limieten met default en cap 1 GB (A12);
 *       {@code post_fetch_action} altijd {@code LEAVE} (A3).</li>
 *   <li><b>Regel:</b> {@code config_hash} deterministisch en gelijk aan de gedocumenteerde canonieke vorm.</li>
 *   <li><b>Regel (L7b):</b> alles MANAGE; READ krijgt 403 en er wordt niets geschreven.</li>
 * </ul>
 */
@SpringBootTest(properties = {"catalogimport.screening.recovery-on-startup=false"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class DeliveryConfigurationHttpTest {

    private static final String API = "/api/catalog-import/delivery-configurations";
    private static final String USER = "an.janssens@example.test";
    private static final long CAP = 1024L * 1024L * 1024L;
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
    @Autowired
    private ConnectionProfileService profiles;

    // --- Aanmaken ----------------------------------------------------------------------------------------------------

    @Test
    void createWithConditionGroupsNumbersThemFromOneAndAppliesTheDefaults() throws Exception {
        Profile profile = profile();
        String code = unique("GRP");
        Map<String, Object> body = body(code, profile.versionId(), "/out/prijzen", "CONDITIONS", List.of(
                List.of(condition("EXTENSION_IS", "csv", false), condition("NAME_STARTS_WITH", "PRIJS", true)),
                List.of(condition("NAME_EQUALS", "levering.csv", false))));

        String answer = create(body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.acquisitionKind").value("SFTP"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.versions.length()").value(1))
                .andExpect(jsonPath("$.versions[0].versionNumber").value(1))
                .andExpect(jsonPath("$.versions[0].connectionProfileVersionId").value(profile.versionId()))
                .andExpect(jsonPath("$.versions[0].connectionProfileCode").value(profile.code()))
                .andExpect(jsonPath("$.versions[0].connectionProfileVersionNumber").value(1))
                .andExpect(jsonPath("$.versions[0].remoteDirectory").value("/out/prijzen"))
                .andExpect(jsonPath("$.versions[0].selectionMode").value("CONDITIONS"))
                .andExpect(jsonPath("$.versions[0].minFileAgeSeconds").value(300))
                .andExpect(jsonPath("$.versions[0].maxFileBytes").value(CAP))
                .andExpect(jsonPath("$.versions[0].postFetchAction").value("LEAVE"))
                .andExpect(jsonPath("$.versions[0].changeReason").value("Nieuwe levering"))
                .andExpect(jsonPath("$.versions[0].createdBy").value(USER))
                .andExpect(jsonPath("$.versions[0].conditionGroups.length()").value(2))
                .andExpect(jsonPath("$.versions[0].conditionGroups[0].groupNumber").value(1))
                .andExpect(jsonPath("$.versions[0].conditionGroups[0].conditions[0].sequenceNumber").value(1))
                .andExpect(jsonPath("$.versions[0].conditionGroups[0].conditions[0].kind").value("EXTENSION_IS"))
                .andExpect(jsonPath("$.versions[0].conditionGroups[0].conditions[0].value").value("csv"))
                .andExpect(jsonPath("$.versions[0].conditionGroups[0].conditions[0].caseSensitive").value(false))
                .andExpect(jsonPath("$.versions[0].conditionGroups[0].conditions[1].sequenceNumber").value(2))
                .andExpect(jsonPath("$.versions[0].conditionGroups[0].conditions[1].caseSensitive").value(true))
                .andExpect(jsonPath("$.versions[0].conditionGroups[1].groupNumber").value(2))
                .andExpect(jsonPath("$.versions[0].conditionGroups[1].conditions[0].sequenceNumber").value(1))
                .andExpect(jsonPath("$.versions[0].conditionGroups[1].conditions[0].value").value("levering.csv"))
                .andReturn().getResponse().getContentAsString();

        long versionId = ((Number) JsonPath.read(answer, "$.versions[0].id")).longValue();
        List<Map<String, Object>> rows = jdbc.queryForList("select group_number, sequence_number, condition_kind, "
                + "compare_value, case_sensitive, bookmark_name from delivery_configuration_file_condition "
                + "where dc_version_id = ? order by group_number, sequence_number", versionId);
        assertThat(rows).extracting(r -> r.get("group_number") + "." + r.get("sequence_number"))
                .containsExactly("1.1", "1.2", "2.1");
        assertThat(rows).extracting(r -> r.get("bookmark_name")).containsOnlyNulls();
        Map<String, Object> version = jdbc.queryForMap("select * from delivery_configuration_version where id = ?",
                versionId);
        assertThat(version).containsEntry("post_fetch_action", "LEAVE").containsEntry("min_file_age_seconds", 300)
                .containsEntry("max_file_bytes", CAP).containsEntry("created_by_subject", "test-sub-" + USER);

        String expectedHash = RevisionConfigHashes.hash("delivery_configuration_version/v1", profile.code(), "1",
                "/out/prijzen", "CONDITIONS", "300", Long.toString(CAP), "LEAVE", "3",
                "1", "1", "EXTENSION_IS", "false", "csv",
                "1", "2", "NAME_STARTS_WITH", "true", "PRIJS",
                "2", "1", "NAME_EQUALS", "false", "levering.csv");
        assertThat(JsonPath.<String>read(answer, "$.versions[0].configHash")).isEqualTo(expectedHash);
        assertThat(version).containsEntry("config_hash", expectedHash);

        long id = ((Number) JsonPath.read(answer, "$.id")).longValue();
        String detail = read(get(API + "/{id}", id)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat(JsonPath.<Object>read(detail, "$")).isEqualTo(JsonPath.read(answer, "$"));
        String list = read(get(API)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> mine = JsonPath.read(list, "$[?(@.code == '" + code + "')]");
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).keySet()).containsExactlyInAnyOrder("id", "code", "name", "acquisitionKind", "active",
                "latestVersionId", "latestVersionNumber", "createdAt", "createdBy");
        assertThat(mine.get(0)).containsEntry("latestVersionNumber", 1);
    }

    @Test
    void allFilesTakesNoConditionsAndConditionsNeedsAtLeastOne() throws Exception {
        Profile profile = profile();
        long before = configurationCount();

        String answer = create(body(unique("ALL"), profile.versionId(), "/", "ALL_FILES", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versions[0].selectionMode").value("ALL_FILES"))
                .andExpect(jsonPath("$.versions[0].remoteDirectory").value("/"))
                .andExpect(jsonPath("$.versions[0].conditionGroups.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        long versionId = ((Number) JsonPath.read(answer, "$.versions[0].id")).longValue();
        assertThat(jdbc.queryForObject("select count(*) from delivery_configuration_file_condition where dc_version_id = ?",
                Long.class, versionId)).isZero();
        create(body(unique("ALL0"), profile.versionId(), "/in", "ALL_FILES", List.of()))
                .andExpect(status().isCreated());

        refused(body(unique("ALLC"), profile.versionId(), "/in", "ALL_FILES",
                List.of(List.of(condition("EXTENSION_IS", "csv", false)))), 400,
                DeliveryConfigurationService.CODE_CONDITIONS_NOT_ALLOWED);
        refused(body(unique("CN"), profile.versionId(), "/in", "CONDITIONS", null), 400,
                DeliveryConfigurationService.CODE_CONDITIONS_REQUIRED);
        refused(body(unique("CE"), profile.versionId(), "/in", "CONDITIONS", List.of()), 400,
                DeliveryConfigurationService.CODE_CONDITIONS_REQUIRED);
        refused(body(unique("CG"), profile.versionId(), "/in", "CONDITIONS",
                List.of(List.of(condition("EXTENSION_IS", "csv", false)), List.of())), 400,
                DeliveryConfigurationService.CODE_CONDITIONS_REQUIRED);
        for (String mode : new String[] {null, "", "SOME_FILES"}) {
            refused(body(unique("SM"), profile.versionId(), "/in", mode, null), 400,
                    DeliveryConfigurationService.CODE_SELECTION_MODE_INVALID);
        }
        assertThat(configurationCount()).isEqualTo(before + 2);
    }

    // --- Invoervalidatie -----------------------------------------------------------------------------------------------

    @Test
    void aRelativeOrTraversingRemoteDirectoryIsRefused() throws Exception {
        Profile profile = profile();
        long before = configurationCount();

        for (String directory : new String[] {null, "", "   ", "in/prijzen", "./in", "/in/../etc", "/..", "/in/./x",
                "/in//x", "/in/", "\\in", "/in\\x", "/in\nx", "/" + "d".repeat(500)}) {
            refused(body(unique("DIR"), profile.versionId(), directory, "ALL_FILES", null), 400,
                    DeliveryConfigurationService.CODE_REMOTE_DIRECTORY_INVALID);
        }
        assertThat(configurationCount()).isEqualTo(before);
        // Een map met punten in een naam is geen traversal.
        create(body(unique("DOT"), profile.versionId(), "/out/v1.2/..prijzen", "ALL_FILES", null))
                .andExpect(status().isCreated());
    }

    @Test
    void theLimitsHaveDefaultsAndACapAndAreNeverSilentlyAdjusted() throws Exception {
        Profile profile = profile();
        long before = configurationCount();

        for (long bytes : new long[] {CAP + 1, 0, -1}) {
            Map<String, Object> body = body(unique("MAX"), profile.versionId(), "/in", "ALL_FILES", null);
            body.put("maxFileBytes", bytes);
            refused(body, 400, DeliveryConfigurationService.CODE_MAX_FILE_BYTES_INVALID);
        }
        Map<String, Object> negativeAge = body(unique("AGE"), profile.versionId(), "/in", "ALL_FILES", null);
        negativeAge.put("minFileAgeSeconds", -1);
        refused(negativeAge, 400, DeliveryConfigurationService.CODE_MIN_FILE_AGE_INVALID);
        assertThat(configurationCount()).isEqualTo(before);

        Map<String, Object> edges = body(unique("EDGE"), profile.versionId(), "/in", "ALL_FILES", null);
        edges.put("maxFileBytes", CAP);
        edges.put("minFileAgeSeconds", 0);
        create(edges).andExpect(status().isCreated())
                .andExpect(jsonPath("$.versions[0].maxFileBytes").value(CAP))
                .andExpect(jsonPath("$.versions[0].minFileAgeSeconds").value(0));
        Map<String, Object> small = body(unique("SMALL"), profile.versionId(), "/in", "ALL_FILES", null);
        small.put("maxFileBytes", 1);
        small.put("minFileAgeSeconds", 60);
        small.put("postFetchAction", "DELETE");
        create(small).andExpect(status().isCreated())
                .andExpect(jsonPath("$.versions[0].maxFileBytes").value(1))
                .andExpect(jsonPath("$.versions[0].postFetchAction").value("LEAVE"));
    }

    @Test
    void anInvalidConditionIsRefusedWithACode() throws Exception {
        Profile profile = profile();
        long before = configurationCount();

        List<Map<String, Object>> invalid = new ArrayList<>(List.of(condition("NAME_MATCHES", "x", false),
                condition("NAME_CONTAINS", "*.csv", false), condition("NAME_CONTAINS", "prijs?", false),
                condition("EXTENSION_IS", ".csv", false), condition("EXTENSION_IS", "csv.", false),
                condition("NAME_EQUALS", "map/levering.csv", false), condition("NAME_EQUALS", "map\\x.csv", false),
                condition("NAME_EQUALS", "  ", false), condition("NAME_EQUALS", "x".repeat(201), false),
                condition("NAME_EQUALS", "a\tb", false)));
        Map<String, Object> noCase = condition("NAME_EQUALS", "levering.csv", false);
        noCase.remove("caseSensitive");
        invalid.add(noCase);
        Map<String, Object> noKind = condition("NAME_EQUALS", "levering.csv", false);
        noKind.put("kind", null);
        invalid.add(noKind);
        for (Map<String, Object> condition : invalid) {
            refused(body(unique("CON"), profile.versionId(), "/in", "CONDITIONS",
                    List.of(List.of(condition("EXTENSION_IS", "csv", false), condition))), 400,
                    DeliveryConfigurationService.CODE_CONDITION_INVALID);
        }
        assertThat(configurationCount()).isEqualTo(before);
        // Grens: een extensie met een punt binnenin mag (tar.gz).
        create(body(unique("TGZ"), profile.versionId(), "/in", "CONDITIONS",
                List.of(List.of(condition("EXTENSION_IS", "tar.gz", false))))).andExpect(status().isCreated());
    }

    @Test
    void anUnknownOrMissingProfileVersionAndMissingTextsAreRefused() throws Exception {
        Profile profile = profile();
        long before = configurationCount();

        refused(body(unique("UNK"), 999_999_999L, "/in", "ALL_FILES", null), 404,
                DeliveryConfigurationService.CODE_PROFILE_VERSION_NOT_FOUND);
        refused(body(unique("MIS"), null, "/in", "ALL_FILES", null), 400,
                DeliveryConfigurationService.CODE_PROFILE_VERSION_REQUIRED);
        Map<String, Object> noCode = body(unique("NC"), profile.versionId(), "/in", "ALL_FILES", null);
        noCode.put("code", " ");
        refused(noCode, 400, DeliveryConfigurationService.CODE_CODE_INVALID);
        Map<String, Object> noName = body(unique("NN"), profile.versionId(), "/in", "ALL_FILES", null);
        noName.remove("name");
        refused(noName, 400, DeliveryConfigurationService.CODE_NAME_INVALID);
        Map<String, Object> noReason = body(unique("NR"), profile.versionId(), "/in", "ALL_FILES", null);
        noReason.put("reason", "");
        refused(noReason, 400, DeliveryConfigurationService.CODE_REASON_REQUIRED);
        json(post(API), as(USER, Permission.MANAGE), "").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(DeliveryConfigurationService.CODE_CODE_INVALID));
        assertThat(configurationCount()).isEqualTo(before);
    }

    // --- config_hash, dubbel, onbekend, rechten -----------------------------------------------------------------------

    @Test
    void theConfigHashIsDeterministicAndChangesWithAFunctionalField() throws Exception {
        Profile profile = profile();
        List<List<Map<String, Object>>> groups = List.of(List.of(condition("NAME_STARTS_WITH", "PRIJS", true)));
        Map<String, Object> first = body(unique("H1"), profile.versionId(), "/in", "CONDITIONS", groups);
        Map<String, Object> second = body(unique("H2"), profile.versionId(), "/in", "CONDITIONS", groups);
        second.put("name", "Andere naam");
        second.put("reason", "Andere reden");
        Map<String, Object> otherCase = body(unique("H3"), profile.versionId(), "/in", "CONDITIONS",
                List.of(List.of(condition("NAME_STARTS_WITH", "PRIJS", false))));
        Map<String, Object> otherGrouping = body(unique("H4"), profile.versionId(), "/in", "CONDITIONS",
                List.of(List.of(condition("NAME_STARTS_WITH", "PRIJS", true)),
                        List.of(condition("EXTENSION_IS", "csv", false))));
        Map<String, Object> sameConditionsOneGroup = body(unique("H5"), profile.versionId(), "/in", "CONDITIONS",
                List.of(List.of(condition("NAME_STARTS_WITH", "PRIJS", true), condition("EXTENSION_IS", "csv", false))));

        String a = hashOf(create(first).andExpect(status().isCreated()));
        String b = hashOf(create(second).andExpect(status().isCreated()));
        String c = hashOf(create(otherCase).andExpect(status().isCreated()));
        String d = hashOf(create(otherGrouping).andExpect(status().isCreated()));
        String e = hashOf(create(sameConditionsOneGroup).andExpect(status().isCreated()));

        assertThat(a).isEqualTo(b);
        assertThat(List.of(a, c, d, e)).doesNotHaveDuplicates();
    }

    @Test
    void aDuplicateCodeIsA409AndAnUnknownConfigurationA404() throws Exception {
        Profile profile = profile();
        String code = unique("DUP");
        create(body(code, profile.versionId(), "/in", "ALL_FILES", null)).andExpect(status().isCreated());
        refused(body(code, profile.versionId(), "/in", "ALL_FILES", null), 409,
                DeliveryConfigurationService.CODE_CODE_EXISTS);
        assertThat(jdbc.queryForObject("select count(*) from delivery_configuration where code = ?", Long.class, code))
                .isEqualTo(1L);

        read(get(API + "/{id}", 999_999_999L)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(DeliveryConfigurationService.CODE_NOT_FOUND));
    }

    @Test
    void aReadOnlyUserMayNeitherReadNorCreateConfigurations() throws Exception {
        Profile profile = profile();
        String created = create(body(unique("RD"), profile.versionId(), "/in", "ALL_FILES", null))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(created, "$.id")).longValue();
        RequestPostProcessor reader = as(USER, Permission.READ);
        long before = configurationCount();

        for (MockHttpServletRequestBuilder request : List.of(get(API), get(API + "/{id}", id))) {
            mockMvc.perform(request.with(reader)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
        json(post(API), reader, objectMapper.writeValueAsString(
                body(unique("RD2"), profile.versionId(), "/in", "ALL_FILES", null)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        assertThat(configurationCount()).isEqualTo(before);
    }

    // --- Helpers -------------------------------------------------------------------------------------------------------

    private record Profile(String code, long versionId) {
    }

    /** Een profiel (versie 1) met een eigen credential, via de service: deze test gaat over de DC. */
    private Profile profile() {
        String host = "sftp.dc" + SEQUENCE.incrementAndGet() + ".test";
        ExternalCredential credential = credentials.saveAndFlush(new ExternalCredential(UUID.randomUUID(),
                "LC-2 DC " + host, ExternalCredentialSecretKind.SFTP_PASSWORD, host, "v1:lc2-test:Nep", "lc2-test",
                USER, null, Instant.now()));
        String code = unique("PRF");
        var detail = profiles.create(new NewConnectionProfile(code, "Profiel " + code, host, null, "leverancier",
                "PASSWORD", credential.getCredentialRef().toString(), "ssh-ed25519",
                ConnectionProfileHttpTest.fingerprint(), "Testprofiel"), new ActorIdentity(USER, "test-sub-" + USER));
        return new Profile(code, detail.versions().get(0).id());
    }

    private static Map<String, Object> condition(String kind, String value, boolean caseSensitive) {
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("kind", kind);
        condition.put("value", value);
        condition.put("caseSensitive", caseSensitive);
        return condition;
    }

    private static Map<String, Object> body(String code, Long profileVersionId, String directory, String mode,
                                            List<List<Map<String, Object>>> groups) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", "Levering " + code);
        body.put("connectionProfileVersionId", profileVersionId);
        body.put("remoteDirectory", directory);
        body.put("selectionMode", mode);
        if (groups != null) {
            body.put("conditionGroups", groups.stream().map(g -> Map.of("conditions", g)).toList());
        }
        body.put("reason", "Nieuwe levering");
        return body;
    }

    private ResultActions create(Map<String, Object> body) throws Exception {
        return json(post(API), as(USER, Permission.MANAGE), objectMapper.writeValueAsString(body));
    }

    private void refused(Map<String, Object> body, int httpStatus, String code) throws Exception {
        create(body).andExpect(status().is(httpStatus)).andExpect(jsonPath("$.code").value(code));
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

    private long configurationCount() {
        return jdbc.queryForObject("select count(*) from delivery_configuration", Long.class);
    }

    private static String unique(String prefix) {
        return "DC" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
    }
}
