package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static be.dda.catalogimport.testsupport.TestActors.withoutPermissions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.service.ActorIdentity;
import be.dda.catalogimport.service.RevisionLegacyOriginService;
import be.dda.catalogimport.service.SetupService;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * {@code GET /definitions/{id}/revisions/{rid}/legacy-origin} (beslissingslog 2026-10-23 "API-uitbreiding voor de
 * inrichtingsschermen", punt 1; B1: READ).
 *
 * <h2>Business rule → wat hier bewezen wordt</h2>
 * <ul>
 *   <li>De revisie die APPLY schreef is {@code MIGRATED} met {@code changedSinceMigration=false}; na een handmatige
 *       wijziging van een mapping op die DRAFT {@code true} (zelfde inhoudshash als APPLY).</li>
 *   <li>Een handmatige opvolger (ook twee stappen ver) is {@code INHERITED} met de gemigreerde revisie als
 *       {@code originRevisionId}; de sjabloonrevisie is {@code TEMPLATE_SOURCE} via de gedeelde definitie.</li>
 *   <li>Elke PSIMPDEF-regel staat erin met haar vaste doel: 26 → {@code recordBasePriceField}, 37 → mapping
 *       {@code VKP5_PCT} met CALC en waarde; geen bestemming, login of PSIMPDEFMAP-inhoud.</li>
 *   <li>Handmatige definitie → 200 met {@code fromLegacy=false}; onbekende definitie/revisie of revisie van een
 *       andere definitie → 404; onleesbare {@code bundle_json} → {@code linesAvailable=false} en een logregel zonder
 *       inhoud.</li>
 *   <li>READ volstaat, zonder recht 403.</li>
 * </ul>
 */
class RevisionLegacyOriginHttpTest extends LegacyConfigS2TestBase {

    private static final String DEFINITIONS = "/api/catalog-import/definitions";
    private static final ActorIdentity ACTOR = new ActorIdentity(USER, "test-sub-" + USER);

    /** Past een afgeleide golden toe (line 37 = VKP5_PCT met CALC 3 × 2) en geeft bundel en ontvangst-id terug. */
    private record Applied(ObjectNode bundle, long receiptId, long definitionId, long revisionId) {
    }

    private Applied applied() throws Exception {
        ObjectNode bundle = applicable();
        line(bundle, 37).put("calculation", "3").put("calculationValue", "2");
        long receiptId = receive(bundle);
        previewAndApply(receiptId, "libraryCode", "LIB-LO").andExpect(status().isOk());
        long definitionId = definitionId(supplier(bundle), "VROOAM2");
        long revisionId = ((Number) revisionRows(definitionId).get(0).get("id")).longValue();
        return new Applied(bundle, receiptId, definitionId, revisionId);
    }

    private ResultActions legacyOrigin(long definitionId, long revisionId) throws Exception {
        return mockMvc.perform(get(DEFINITIONS + "/{d}/revisions/{r}/legacy-origin", definitionId, revisionId)
                .with(as(USER, Permission.READ)));
    }

    private String legacyOriginBody(long definitionId, long revisionId) throws Exception {
        return legacyOrigin(definitionId, revisionId).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
    }

    @Test
    void theAppliedRevisionIsMigratedWithItsLegacyLines() throws Exception {
        Applied applied = applied();

        legacyOrigin(applied.definitionId(), applied.revisionId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionId").value(applied.revisionId()))
                .andExpect(jsonPath("$.fromLegacy").value(true))
                .andExpect(jsonPath("$.originKind").value("MIGRATED"))
                .andExpect(jsonPath("$.originRevisionId").value(applied.revisionId()))
                .andExpect(jsonPath("$.changedSinceMigration").value(false))
                .andExpect(jsonPath("$.receiptId").value(applied.receiptId()))
                .andExpect(jsonPath("$.interfaceCode").value(interfaceCode(applied.bundle())))
                .andExpect(jsonPath("$.legacyDefinitionCode").value("VROOAM2"))
                .andExpect(jsonPath("$.exportedAt").isNotEmpty())
                .andExpect(jsonPath("$.appliedAt").isNotEmpty())
                .andExpect(jsonPath("$.linesAvailable").value(true))
                .andExpect(jsonPath("$.lines.length()").value(8))
                // Identiteits-/recordregel: het revisieveld, geen catalogcode.
                .andExpect(jsonPath("$.lines[?(@.number == 26)].targetKind").value("REVISION_FIELD"))
                .andExpect(jsonPath("$.lines[?(@.number == 26)].revisionField").value("recordBasePriceField"))
                .andExpect(jsonPath("$.lines[?(@.number == 26)].column").value(7))
                .andExpect(jsonPath("$.lines[?(@.number == 26)].legacyFieldName").value("Basisprijs"))
                .andExpect(jsonPath("$.lines[?(@.number == 9)].revisionField").value("identitySupplierField"))
                // Mappingregel met CALC en waarde, filtercode en -data zoals ontvangen.
                .andExpect(jsonPath("$.lines[?(@.number == 37)].targetKind").value("MAPPING"))
                .andExpect(jsonPath("$.lines[?(@.number == 37)].targetFieldCode").value("VKP5_PCT"))
                .andExpect(jsonPath("$.lines[?(@.number == 37)].calculation").value("3"))
                .andExpect(jsonPath("$.lines[?(@.number == 37)].calculationValue").value("2"))
                .andExpect(jsonPath("$.lines[?(@.number == 37)].includeExclude").value("1"))
                .andExpect(jsonPath("$.lines[?(@.number == 37)].includeExcludeData").value("ACME"))
                .andExpect(jsonPath("$.lines[?(@.number == 33)].targetFieldCode").value("VKP1_PCT"))
                // Vaste waarde van een identiteitsregel staat als fixedValue (bundelveld "value").
                .andExpect(jsonPath("$.lines[?(@.number == 10)].fixedValue").value(""));
    }

    @Test
    void aManualMappingChangeOnTheDraftIsChangedSinceMigration() throws Exception {
        Applied applied = applied();
        long mappingId = count("select id from import_field_mapping where definition_revision_id = ? "
                + "and target_field_code = ?", applied.revisionId(), "VKP5_PCT");

        setup.replaceMapping(applied.revisionId(), mappingId, new SetupService.CreateMappingCommand("VKP5_PCT", "10",
                null, null, null, null, null, null, null, null, null, null, null, null, null, USER, null, null), ACTOR);

        legacyOrigin(applied.definitionId(), applied.revisionId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.originKind").value("MIGRATED"))
                .andExpect(jsonPath("$.changedSinceMigration").value(true))
                // De regels blijven die van de ontvangst: legacy zei kolom 9 met CALC 3.
                .andExpect(jsonPath("$.lines[?(@.number == 37)].column").value(9));
    }

    @Test
    void aManualSuccessorInheritsTheOriginOverTwoSteps() throws Exception {
        Applied applied = applied();
        setup.activateRevision(applied.revisionId(), ACTOR);
        long successor = successors.createSuccessor(applied.revisionId(), "handmatige opvolger", ACTOR).id();

        legacyOrigin(applied.definitionId(), successor).andExpect(status().isOk())
                .andExpect(jsonPath("$.fromLegacy").value(true))
                .andExpect(jsonPath("$.originKind").value("INHERITED"))
                .andExpect(jsonPath("$.originRevisionId").value(applied.revisionId()))
                .andExpect(jsonPath("$.changedSinceMigration").value(false))
                .andExpect(jsonPath("$.receiptId").value(applied.receiptId()))
                .andExpect(jsonPath("$.lines.length()").value(8));

        setup.activateRevision(successor, ACTOR);
        long third = successors.createSuccessor(successor, "nog een opvolger", ACTOR).id();
        setup.updateRevision(third, new SetupService.UpdateRevisionCommand(null, null, null, null, null, null, null,
                null, null, null, null, null, null, "6", null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null));

        legacyOrigin(applied.definitionId(), third).andExpect(status().isOk())
                .andExpect(jsonPath("$.originKind").value("INHERITED"))
                .andExpect(jsonPath("$.originRevisionId").value(applied.revisionId()))
                .andExpect(jsonPath("$.changedSinceMigration").value(true));
    }

    @Test
    void theTemplateRevisionIsTheTemplateSource() throws Exception {
        Applied applied = applied();
        long templateId = definitionId(supplier(applied.bundle()), "VROOAM2-SJ");
        long templateRevision = ((Number) revisionRows(templateId).get(0).get("id")).longValue();

        legacyOrigin(templateId, templateRevision).andExpect(status().isOk())
                .andExpect(jsonPath("$.fromLegacy").value(true))
                .andExpect(jsonPath("$.originKind").value("TEMPLATE_SOURCE"))
                .andExpect(jsonPath("$.originRevisionId").value(applied.revisionId()))
                .andExpect(jsonPath("$.changedSinceMigration").value(false))
                .andExpect(jsonPath("$.receiptId").value(applied.receiptId()))
                .andExpect(jsonPath("$.lines[?(@.number == 26)].revisionField").value("recordBasePriceField"));
    }

    @Test
    void aManualDefinitionIsNotFromLegacy() throws Exception {
        String supplier = "LO" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        mockMvc.perform(post("/api/catalog-import/setup/source-organisations").with(as(USER, Permission.MANAGE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + supplier + "\",\"name\":\"Handmatig\",\"type\":\"SUPPLIER\"}"))
                .andExpect(status().isCreated());
        long definitionId = id(mockMvc.perform(post("/api/catalog-import/setup/definitions")
                        .with(as(USER, Permission.MANAGE)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceOrganisationCode\":\"" + supplier + "\",\"code\":\"MANUAL\","
                                + "\"name\":\"Handmatige definitie\",\"usageType\":\"OWN_DEFINITION\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long revisionId = id(mockMvc.perform(post("/api/catalog-import/setup/definitions/{id}/revisions",
                        definitionId).with(as(USER, Permission.MANAGE)).contentType(MediaType.APPLICATION_JSON)
                        .content(IdentityFixedValueHttpTest.fixed(null, null, false)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        String body = legacyOriginBody(definitionId, revisionId);

        assertThat((Boolean) JsonPath.read(body, "$.fromLegacy")).isFalse();
        assertThat((Boolean) JsonPath.read(body, "$.linesAvailable")).isFalse();
        assertThat((List<?>) JsonPath.read(body, "$.lines")).isEmpty();
        Map<String, Object> view = JsonPath.read(body, "$");
        assertThat(view.get("originKind")).isNull();
        assertThat(view.get("originRevisionId")).isNull();
        assertThat(view.get("changedSinceMigration")).isNull();
        assertThat(view.get("receiptId")).isNull();
    }

    @Test
    void unknownOrMismatchedIdsAre404WithTheirOwnCode() throws Exception {
        Applied applied = applied();
        long templateId = definitionId(supplier(applied.bundle()), "VROOAM2-SJ");
        long unknown = 999_999_999L;

        legacyOrigin(unknown, applied.revisionId()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEFINITION_NOT_FOUND"));
        legacyOrigin(applied.definitionId(), unknown).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_FOUND"));
        // Een bestaande revisie van een andere definitie.
        legacyOrigin(templateId, applied.revisionId()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_FOUND"));
    }

    @Test
    void readIsEnoughAndWithoutARightItIs403() throws Exception {
        Applied applied = applied();
        String path = DEFINITIONS + "/" + applied.definitionId() + "/revisions/" + applied.revisionId()
                + "/legacy-origin";

        mockMvc.perform(get(path).with(withoutPermissions(USER))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        mockMvc.perform(get(path).with(as(USER, Permission.READ))).andExpect(status().isOk());
    }

    /** Patroon {@code LegacyConfigSecretLeakTest}: unieke kanaries in bestemming en login, nergens in het antwoord. */
    @Test
    void theResponseNeverContainsDestinationLoginOrMapContent() throws Exception {
        String marker = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String host = "leakhost" + marker + ".example.test";
        String directory = "/leakdir" + marker + "/";
        String login = "leaklogin" + marker;
        String mapValue = "LEAKMAP" + marker.toUpperCase();
        ObjectNode bundle = applicable();
        detail(bundle, "FTP", "sftp://" + host + directory);
        detail(bundle, "FTP_login", login);
        ((ObjectNode) bundle.get("definition").get("maps").get(0)).put("value", mapValue);
        long receiptId = receive(bundle);
        previewAndApply(receiptId, "libraryCode", "LIB-LO").andExpect(status().isOk());
        long definitionId = definitionId(supplier(bundle), "VROOAM2");
        long revisionId = ((Number) revisionRows(definitionId).get(0).get("id")).longValue();
        // Voorwaarde: de kanaries staan wél in de bewaarde bundel.
        assertThat((String) receiptRow(receiptId).get("bundle_json")).contains(host, directory, login, mapValue);

        String body = legacyOriginBody(definitionId, revisionId);

        assertThat(body).contains("\"linesAvailable\":true").doesNotContain(marker).doesNotContain(host)
                .doesNotContain(login).doesNotContain(mapValue).doesNotContain(mapValue.toLowerCase())
                .doesNotContain("sftp://").doesNotContain("\"interface\"").doesNotContain("\"secret\"")
                .doesNotContain("\"maps\"").doesNotContain("\"excludes\"").doesNotContain("REF-1");
    }

    @Test
    void anUnreadableBundleGivesNoLinesAndALogLineWithoutContent() throws Exception {
        Applied applied = applied();
        String original = (String) receiptRow(applied.receiptId()).get("bundle_json");
        String garbage = "{\"definition\":{\"lines\":[ GARBAGE" + UUID.randomUUID();
        Logger logger = (Logger) LoggerFactory.getLogger(RevisionLegacyOriginService.class);
        ListAppender<ILoggingEvent> log = new ListAppender<>();
        log.start();
        logger.addAppender(log);
        jdbc.update("update legacy_config_receipt set bundle_json = ? where id = ?", garbage, applied.receiptId());
        try {
            legacyOrigin(applied.definitionId(), applied.revisionId()).andExpect(status().isOk())
                    .andExpect(jsonPath("$.fromLegacy").value(true))
                    .andExpect(jsonPath("$.originKind").value("MIGRATED"))
                    .andExpect(jsonPath("$.receiptId").value(applied.receiptId()))
                    .andExpect(jsonPath("$.linesAvailable").value(false))
                    .andExpect(jsonPath("$.lines").isEmpty());
        } finally {
            logger.detachAppender(log);
            jdbc.update("update legacy_config_receipt set bundle_json = ? where id = ?", original,
                    applied.receiptId());
        }
        assertThat(log.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("receipt " + applied.receiptId(), "is not readable"));
        assertThat(log.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain("GARBAGE");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    private static long id(String body) {
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }
}
