package be.dda.catalogimport.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.DeliveryRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.TaskRunRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.Delivery;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.TaskRun;
import be.dda.catalogimport.domain.TaskRunStatus;
import be.dda.catalogimport.service.ActorIdentity;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Slice G2a van {@code docs/design/g2-design.md} (par. G2a): loskoppelen van een gedeelde definitie volgens
 * beslissingslog 2026-10-22 "G2a: loskoppelen van een gedeelde definitie" (V1 = A, V2 = A).
 *
 * <h2>Business rule → wat hier bewezen wordt</h2>
 * <ul>
 *   <li>De "stroom" van een leverancierscatalogus is de koppeling: na het loskoppelen staat de koppeling op een eigen
 *       kopie, maar koppelings- en taakcode, taak (en dus leveringsconfiguratie), credential van de herkomst,
 *       bronstaat en prijshistoriek blijven; oude batches houden hun bevroren revisie.</li>
 *   <li>V2 = A: revisie 1 = ongewijzigde kopie van de ACTIVE revisie, geactiveerd met de actor als goedkeurder;
 *       revisie 2 = DRAFT met de nieuwe legacy-inhoud. Zonder ACTIVE revisie (of voor een nieuwe interface): revisie 1
 *       = DRAFT met de nieuwe inhoud.</li>
 *   <li>Enkel zonder open batch of lopende run, met een vrije en geldige code; anders blokkeert de preview en schrijft
 *       APPLY niets. Zonder afwijking van de groep: {@code DETACH_NOT_NEEDED} (genegeerd).</li>
 *   <li>Daarna volgt de kopie enkel haar interface (S2: {@code DEFINITION_UNCHANGED}, opvolger op de kopie); de groep
 *       ziet de interface niet meer ({@code sharedWith}) en de kopie trekt nooit leden aan.</li>
 *   <li>Herkomst: groepsrij 020-3 ongewijzigd, eigen 020-3-rij voor de kopie, 020-4 geërfd voor revisie 1, auditrij in
 *       {@code legacy_config_detachment}; een gewijzigde koppeling na de preview maakt die preview verouderd.</li>
 * </ul>
 */
class LegacyConfigDetachTest extends LegacyConfigS2TestBase {

    private static final ActorIdentity ACTOR = new ActorIdentity(USER, "test-sub-" + USER);
    private static final String DETACH = "detachFromShared";

    @Autowired
    private ImportLinkRepository linkRepository;
    @Autowired
    private CatalogImportTaskRepository taskRepository;
    @Autowired
    private DeliveryRepository deliveryRepository;
    @Autowired
    private ImportBatchRepository batchRepository;
    @Autowired
    private TaskRunRepository runRepository;
    @Autowired
    private ImportDefinitionRevisionRepository revisionRepository;

    /** Een groep van twee interfaces (A, B) op één gedeelde definitie. */
    private record Group(ObjectNode a, ObjectNode b, String supplier, long shared, long sharedRevision, long linkA,
                         long linkB) {
    }

    // --- Bekende interface: verleggen ---------------------------------------------------------------------------

    @Test
    void detachingMovesTheLinkToAnOwnActivatedCopyAndKeepsEverythingThatHangsOnTheLink() throws Exception {
        Group g = groupWithActiveRevision();
        String ifaceA = interfaceCode(g.a());
        long taskA = count("select id from catalog_import_task where import_link_id = ?", g.linkA());
        History history = history(g.linkA(), g.sharedRevision());
        Map<String, Object> linkBefore = linkRow(g.linkA());
        Map<String, Object> taskBefore = jdbc.queryForMap("select * from catalog_import_task where id = ?", taskA);
        Map<String, Object> groupRowBefore = groupRow(g.shared());
        Map<String, Object> originBefore = originRow(g.a());

        // Enkel A levert nieuwe legacy-inhoud: de groep loopt uiteen; de tekst wijst op de optie.
        line(g.a(), 26).put("column", 17);
        long changed = receive(g.a());
        String diverged = previewBody(changed);
        assertThat(blockingCodes(diverged)).containsExactly("SHARED_DEFINITION_DIVERGED");
        assertThat((List<String>) JsonPath.read(diverged,
                "$.report.items[?(@.code == 'SHARED_DEFINITION_DIVERGED')].message"))
                .singleElement().asString().contains("detachFromShared=true");

        String preview = previewBody(changed, DETACH, "true");
        assertThat(blockingCodes(preview)).isEmpty();
        assertThat(codes(preview, "MIGRATED")).contains("DETACH_FROM_SHARED").doesNotContain("SHARED_DEFINITION_DIVERGED");
        assertThat(((Number) JsonPath.read(preview, "$.report.planned.detach.fromDefinitionId")).longValue())
                .isEqualTo(g.shared());
        assertThat((String) JsonPath.read(preview, "$.report.planned.detach.fromDefinitionCode")).isEqualTo("VROOAM2");
        assertThat((String) JsonPath.read(preview, "$.report.planned.detach.toDefinitionCode"))
                .isEqualTo("LEGACY-" + ifaceA);
        assertThat(((Number) JsonPath.read(preview, "$.report.planned.detach.copiedRevisionId")).longValue())
                .isEqualTo(g.sharedRevision());
        assertThat((Boolean) JsonPath.read(preview, "$.report.planned.detach.carriesActive")).isTrue();
        assertThat((String) JsonPath.read(preview, "$.report.planned.link.action")).isEqualTo("REPOINT");
        assertThat(((Number) JsonPath.read(preview, "$.report.planned.link.id")).longValue()).isEqualTo(g.linkA());
        assertThat((String) JsonPath.read(preview, "$.report.planned.definition.action")).isEqualTo("CREATE");
        assertThat((String) JsonPath.read(preview, "$.report.planned.definition.revisionAction"))
                .isEqualTo("SUCCESSOR_DRAFT");
        assertThat((String) JsonPath.read(preview, "$.report.planned.template.action")).isEqualTo("NONE");
        assertThat((List<String>) JsonPath.read(preview, "$.report.sharedWith")).isEmpty();

        String applied = apply(changed, JsonPath.read(preview, "$.previewFingerprint"), REASON, DETACH, "true")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long copy = definitionId(g.supplier(), "LEGACY-" + ifaceA);
        assertThat(((Number) JsonPath.read(applied, "$.report.planned.definition.id")).longValue()).isEqualTo(copy);

        // De koppeling staat op de kopie; al de rest van de koppeling, de taak en de herkomst blijft.
        Map<String, Object> linkAfter = linkRow(g.linkA());
        assertThat(((Number) linkAfter.get("import_definition_id")).longValue()).isEqualTo(copy);
        for (String column : List.of("code", "name", "supplier_organisation_id", "library_code",
                "library_search_supplier_code", "default_currency", "active", "created_at")) {
            assertThat(linkAfter.get(column)).as(column).isEqualTo(linkBefore.get(column));
        }
        assertThat(jdbc.queryForMap("select * from catalog_import_task where id = ?", taskA)).isEqualTo(taskBefore);
        Map<String, Object> originAfter = originRow(g.a());
        assertThat(originAfter.get("import_link_id")).isEqualTo(originBefore.get("import_link_id"));
        assertThat(originAfter.get("catalog_import_task_id")).isEqualTo(originBefore.get("catalog_import_task_id"));
        assertThat(originAfter.get("credential_id")).isEqualTo(originBefore.get("credential_id"));
        assertThat(((Number) originAfter.get("last_applied_receipt_id")).longValue()).isEqualTo(changed);
        // Oude batch, bronstaat en prijshistoriek: onaangeroerd (de batch houdt de revisie van de gedeelde definitie).
        assertThat(count("select definition_revision_id from import_batch where id = ?", history.batchId()))
                .isEqualTo(g.sharedRevision());
        assertThat(count("select count(*) from catalog_source_state where import_link_id = ?", g.linkA()))
                .isEqualTo(1);
        assertThat(count("select count(*) from catalog_price_observation where import_link_id = ? and batch_id = ?",
                g.linkA(), history.batchId())).isEqualTo(1);

        // De kopie: OWN_DEFINITION based_on de gedeelde definitie, revisie 1 ACTIVE (kopie), revisie 2 DRAFT (nieuw).
        Map<String, Object> copyRow = jdbc.queryForMap("select * from import_definition where id = ?", copy);
        Map<String, Object> sharedRow = jdbc.queryForMap("select * from import_definition where id = ?", g.shared());
        assertThat(copyRow.get("usage_type")).isEqualTo("OWN_DEFINITION");
        assertThat(((Number) copyRow.get("based_on_definition_id")).longValue()).isEqualTo(g.shared());
        assertThat(copyRow.get("source_organisation_id")).isEqualTo(sharedRow.get("source_organisation_id"));
        assertThat(copyRow.get("description")).isEqualTo(sharedRow.get("description") + " (losgekoppeld voor "
                + ifaceA + ")");
        List<Map<String, Object>> copyRevisions = revisionRows(copy);
        assertThat(copyRevisions).hasSize(2);
        Map<String, Object> first = copyRevisions.get(0);
        Map<String, Object> second = copyRevisions.get(1);
        assertThat(first.get("status")).isEqualTo("ACTIVE");
        assertThat(first.get("approved_by")).isEqualTo(USER);
        assertThat(first.get("approved_by_subject")).isEqualTo("test-sub-" + USER);
        assertThat(((Number) first.get("based_on_revision_id")).longValue()).isEqualTo(g.sharedRevision());
        assertThat(second.get("status")).isEqualTo("DRAFT");
        assertThat(((Number) second.get("based_on_revision_id")).longValue())
                .isEqualTo(((Number) first.get("id")).longValue());
        assertThat(second.get("record_base_price_field")).isEqualTo("17");
        assertThat(((Number) JsonPath.read(applied, "$.report.planned.definition.revisionId")).longValue())
                .isEqualTo(((Number) second.get("id")).longValue());
        // Revisie 1 is een ongewijzigde kopie (zelfde configuratiehashes, mappings, vertaalrijen, filters).
        Map<String, Object> sharedFirst = revisionRows(g.shared()).get(0);
        for (String column : List.of("access_config_hash", "structure_config_hash", "record_rules_config_hash",
                "record_base_price_field", "identity_uppercase", "structure_delimiter")) {
            assertThat(first.get(column)).as(column).isEqualTo(sharedFirst.get(column));
        }
        assertThat(valueMapRows(((Number) first.get("id")).longValue())).isEqualTo(valueMapRows(g.sharedRevision()))
                .isNotEmpty();
        assertThat(filterRows(((Number) first.get("id")).longValue())).isEqualTo(filterRows(g.sharedRevision()))
                .isNotEmpty();

        // De gedeelde definitie en B: ongemoeid.
        assertThat(revisionRows(g.shared())).singleElement()
                .satisfies(row -> assertThat(row.get("status")).isEqualTo("ACTIVE"));
        assertThat(count("select import_definition_id from import_link where id = ?", g.linkB())).isEqualTo(g.shared());

        // Herkomst: groepsrij ongewijzigd, eigen rij voor de kopie, 020-4 geërfd (rev 1) en nieuw (rev 2), audit.
        assertThat(groupRow(g.shared())).isEqualTo(groupRowBefore);
        Map<String, Object> detachedRow = jdbc.queryForMap("select * from legacy_config_definition_origin "
                + "where import_definition_id = ?", copy);
        assertThat(detachedRow.get("detached_interface_code")).isEqualTo(ifaceA);
        assertThat(detachedRow.get("group_marker")).isNull();
        assertThat(detachedRow.get("template_definition_id")).isNull();
        assertThat(detachedRow.get("definition_content_hash")).isEqualTo(g.a().get("definitionContentHash").asText());
        assertThat(detachedRow.get("legacy_definition_codes")).isEqualTo("VROOAM2");
        Map<String, Object> inherited = revisionOrigin(((Number) first.get("id")).longValue());
        Map<String, Object> sharedOrigin = revisionOrigin(g.sharedRevision());
        assertThat(inherited.get("receipt_id")).isEqualTo(sharedOrigin.get("receipt_id"));
        assertThat(inherited.get("migrated_content_hash")).isEqualTo(sharedOrigin.get("migrated_content_hash"));
        assertThat(inherited.get("definition_content_hash")).isEqualTo(sharedOrigin.get("definition_content_hash"));
        assertThat(((Number) revisionOrigin(((Number) second.get("id")).longValue()).get("receipt_id")).longValue())
                .isEqualTo(changed);
        Map<String, Object> audit = jdbc.queryForMap("select * from legacy_config_detachment where import_link_id = ?",
                g.linkA());
        assertThat(((Number) audit.get("from_definition_id")).longValue()).isEqualTo(g.shared());
        assertThat(((Number) audit.get("to_definition_id")).longValue()).isEqualTo(copy);
        assertThat(((Number) audit.get("receipt_id")).longValue()).isEqualTo(changed);
        assertThat(audit.get("origin_id")).isEqualTo(originAfter.get("id"));
        assertThat(audit.get("action")).isEqualTo("DETACH");
        assertThat(audit.get("reason")).isEqualTo(REASON);
        assertThat(audit.get("created_by")).isEqualTo(USER);
        assertThat(receiptRow(changed).get("status")).isEqualTo("APPLIED");

        // Een tweede APPLY van dezelfde ontvangst: 409, niets dubbel.
        apply(changed, JsonPath.read(preview, "$.previewFingerprint"), REASON, DETACH, "true")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LEGACY_RECEIPT_NOT_APPLICABLE"));
        assertThat(count("select count(*) from legacy_config_detachment where import_link_id = ?", g.linkA()))
                .isEqualTo(1);
        assertThat(count("select count(*) from import_definition where based_on_definition_id = ?", g.shared()))
                .isEqualTo(1);
    }

    @Test
    void afterDetachingTheCopyFollowsOnlyItsInterface() throws Exception {
        Group g = groupWithActiveRevision();
        String ifaceA = interfaceCode(g.a());
        line(g.a(), 26).put("column", 17);
        previewAndApply(receive(g.a()), DETACH, "true").andExpect(status().isOk());
        long copy = definitionId(g.supplier(), "LEGACY-" + ifaceA);

        // Zelfde inhoud opnieuw (andere contentHash): DEFINITION_UNCHANGED op de kopie, niets nieuws.
        ((ObjectNode) g.a().get("interface").get("header")).put("info", "tweede export");
        long again = receive(g.a());
        String unchanged = previewBody(again);
        assertThat(blockingCodes(unchanged)).isEmpty();
        assertThat(codes(unchanged, "MIGRATED")).contains("DEFINITION_UNCHANGED", "DETACHED_DEFINITION");
        assertThat((List<String>) JsonPath.read(unchanged, "$.report.sharedWith")).isEmpty();
        assertThat(((Number) JsonPath.read(unchanged, "$.report.planned.definition.id")).longValue()).isEqualTo(copy);
        assertThat((Object) JsonPath.read(unchanged, "$.report.planned.detach")).isNull();
        // De optie op een eigen kopie is overbodig: genegeerd met een waarschuwing.
        assertThat(codes(previewBody(again, DETACH, "true"), "WARNING")).contains("DETACH_NOT_NEEDED");
        apply(again, JsonPath.read(unchanged, "$.previewFingerprint"), REASON).andExpect(status().isOk());
        assertThat(revisionRows(copy)).hasSize(2);

        // Een latere legacy-wijziging: de open DRAFT van de kopie blokkeert (S2); na activatie volgt een opvolger.
        line(g.a(), 26).put("column", 18);
        long later = receive(g.a());
        assertThat(blockingCodes(previewBody(later))).containsExactly("OPEN_DRAFT_EXISTS");
        setup.activateRevision(((Number) revisionRows(copy).get(1).get("id")).longValue(), ACTOR);
        String successor = previewBody(later);
        assertThat(blockingCodes(successor)).isEmpty();
        assertThat((String) JsonPath.read(successor, "$.report.planned.definition.revisionAction"))
                .isEqualTo("SUCCESSOR_DRAFT");
        apply(later, JsonPath.read(successor, "$.previewFingerprint"), REASON).andExpect(status().isOk());
        assertThat(revisionRows(copy)).hasSize(3);
        assertThat(revisionRows(copy).get(2).get("record_base_price_field")).isEqualTo("18");
        // De 020-3-rij van de kopie schuift ter plaatse mee; de groep en de gedeelde definitie blijven.
        assertThat(jdbc.queryForObject("select definition_content_hash from legacy_config_definition_origin "
                + "where import_definition_id = ?", String.class, copy))
                .isEqualTo(g.a().get("definitionContentHash").asText());
        assertThat(revisionRows(g.shared())).hasSize(1);
    }

    @Test
    void theGroupNoLongerSeesTheDetachedInterfaceAndTheCopyAttractsNoMembers() throws Exception {
        Group g = groupWithActiveRevision();
        line(g.a(), 26).put("column", 17);
        previewAndApply(receive(g.a()), DETACH, "true").andExpect(status().isOk());

        // B levert nu dezelfde nieuwe inhoud als A: A telt niet meer mee (sharedWith), B volgt de groep alleen (S2).
        line(g.b(), 26).put("column", 17);
        long changedB = receive(g.b());
        String previewB = previewBody(changedB);
        assertThat(blockingCodes(previewB)).isEmpty();
        assertThat((List<String>) JsonPath.read(previewB, "$.report.sharedWith")).isEmpty();
        assertThat((String) JsonPath.read(previewB, "$.report.planned.definition.revisionAction"))
                .isEqualTo("SUCCESSOR_DRAFT");
        assertThat(((Number) JsonPath.read(previewB, "$.report.planned.definition.id")).longValue())
                .isEqualTo(g.shared());

        // Een nieuwe interface met de inhoud van de kopie sluit nooit bij de kopie aan: de groepsherkenning leest enkel
        // groepsrijen, dus ze wordt (op de legacy-code) bij de groep van de gedeelde definitie herkend.
        long copy = definitionId(g.supplier(), "LEGACY-" + interfaceCode(g.a()));
        ObjectNode newcomer = g.a().deepCopy();
        interfaceCode(newcomer, interfaceCode(g.a()) + "N");
        String previewN = previewBody(receive(newcomer), "libraryCode", "LIB-N");
        assertThat(((Number) JsonPath.read(previewN, "$.report.planned.definition.id")).longValue())
                .isEqualTo(g.shared()).isNotEqualTo(copy);
        assertThat(blockingCodes(previewN)).containsExactly("SHARED_DEFINITION_DIVERGED");
        assertThat((List<String>) JsonPath.read(previewN, "$.report.sharedWith")).doesNotContain(interfaceCode(g.a()));
    }

    // --- Guards ---------------------------------------------------------------------------------------------------

    @Test
    void anOpenBatchARunningRunOrAnUnusableCodeBlocksAndApplyWritesNothing() throws Exception {
        Group g = groupWithActiveRevision();
        String ifaceA = interfaceCode(g.a());
        line(g.a(), 26).put("column", 17);
        long changed = receive(g.a());
        CatalogImportTask task = taskRepository.findByImportLinkIdOrderByIdAsc(g.linkA()).get(0);

        // Open batch.
        ImportBatch open = batch(g.linkA(), g.sharedRevision(), null);
        String openBatch = previewBody(changed, DETACH, "true");
        assertThat(blockingCodes(openBatch)).containsExactly("DETACH_OPEN_BATCH");
        List<Long> before = footprint(g.a());
        long definitionsBefore = definitionsOf(g.supplier());
        apply(changed, JsonPath.read(openBatch, "$.previewFingerprint"), REASON, DETACH, "true")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LEGACY_APPLY_BLOCKED"));
        assertThat(footprint(g.a())).isEqualTo(before);
        assertThat(definitionsOf(g.supplier())).isEqualTo(definitionsBefore);
        assertThat(count("select import_definition_id from import_link where id = ?", g.linkA())).isEqualTo(g.shared());
        assertThat(count("select count(*) from legacy_config_detachment where import_link_id = ?", g.linkA())).isZero();
        open.setStatus(ImportBatchStatus.FAILED);
        batchRepository.saveAndFlush(open);

        // Lopende run op de taak van de koppeling.
        TaskRun run = new TaskRun(task, Instant.now(), USER);
        run.setStatus(TaskRunStatus.RUNNING);
        run = runRepository.saveAndFlush(run);
        assertThat(blockingCodes(previewBody(changed, DETACH, "true"))).containsExactly("DETACH_RUN_IN_PROGRESS");
        run.setStatus(TaskRunStatus.COMPLETED);
        runRepository.saveAndFlush(run);

        // Code al in gebruik bij de leverancier; een andere code via detachedDefinitionCode lost het op.
        setup.createDefinition(new be.dda.catalogimport.service.SetupService.CreateDefinitionCommand(g.supplier(),
                "LEGACY-" + ifaceA, "Handmatig", null), ACTOR);
        assertThat(blockingCodes(previewBody(changed, DETACH, "true")))
                .containsExactly("DETACHED_DEFINITION_CODE_CONFLICT");
        assertThat(blockingCodes(previewBody(changed, DETACH, "true", "detachedDefinitionCode", "X".repeat(51))))
                .containsExactly("DETACHED_DEFINITION_CODE_INVALID");
        String own = previewBody(changed, DETACH, "true", "detachedDefinitionCode", "EIGEN-" + ifaceA);
        assertThat(blockingCodes(own)).isEmpty();
        assertThat((String) JsonPath.read(own, "$.report.planned.detach.toDefinitionCode")).isEqualTo("EIGEN-" + ifaceA);
        apply(changed, JsonPath.read(own, "$.previewFingerprint"), REASON, DETACH, "true", "detachedDefinitionCode",
                "EIGEN-" + ifaceA).andExpect(status().isOk());
        assertThat(count("select import_definition_id from import_link where id = ?", g.linkA()))
                .isEqualTo(definitionId(g.supplier(), "EIGEN-" + ifaceA));
    }

    /** Een open DRAFT van de gedeelde definitie (S2-regel, geëvalueerd op de gedeelde definitie, design punt 5). */
    @Test
    void anOpenDraftOnTheSharedDefinitionBlocksTheCopy() throws Exception {
        Group g = groupWithActiveRevision();
        line(g.a(), 26).put("column", 17);
        long changed = receive(g.a());

        successors.createSuccessor(g.sharedRevision(), "Handmatige aanpassing (test)", ACTOR);
        String preview = previewBody(changed, DETACH, "true");
        assertThat(blockingCodes(preview)).containsExactly("OPEN_DRAFT_EXISTS");
        apply(changed, JsonPath.read(preview, "$.previewFingerprint"), REASON, DETACH, "true")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LEGACY_APPLY_BLOCKED"));
        assertThat(count("select import_definition_id from import_link where id = ?", g.linkA())).isEqualTo(g.shared());
    }

    /** De ACTIVE revisie wijkt handmatig af van wat de migratie schreef: nooit kopiëren en overschrijven. */
    @Test
    void manualChangesOnTheActiveRevisionBlockTheCopy() throws Exception {
        Group g = groupWithActiveRevision();
        line(g.a(), 26).put("column", 17);
        long changed = receive(g.a());

        jdbc.update("update import_definition_revision set record_base_price_field = '8' where id = ?",
                g.sharedRevision());
        assertThat(blockingCodes(previewBody(changed, DETACH, "true")))
                .containsExactly("MANUAL_CHANGES_SINCE_MIGRATION");
    }

    @Test
    void aChangeOfTheLinkAfterThePreviewMakesThePreviewStale() throws Exception {
        Group g = groupWithActiveRevision();
        line(g.a(), 26).put("column", 17);
        long changed = receive(g.a());
        String fingerprint = fingerprint(changed, DETACH, "true");

        jdbc.update("update import_link set name = ?, updated_at = ? where id = ?", "Hernoemd na de preview",
                OffsetDateTime.now(ZoneOffset.UTC), g.linkA());

        apply(changed, fingerprint, REASON, DETACH, "true").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEGACY_PREVIEW_STALE"));
        assertThat(count("select import_definition_id from import_link where id = ?", g.linkA())).isEqualTo(g.shared());
        // Ook de fingerprint zonder de optie past niet: de keuze hoort bij de preview.
        String withoutOption = fingerprint(changed);
        apply(changed, withoutOption, REASON, DETACH, "true").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEGACY_PREVIEW_STALE"));
    }

    @Test
    void theOptionIsIgnoredWithAWarningWhenNothingDiverges() throws Exception {
        ObjectNode single = applicable();
        long receipt = receive(single);
        String preview = previewBody(receipt, "libraryCode", "LIB", DETACH, "true");
        assertThat(blockingCodes(preview)).isEmpty();
        assertThat(codes(preview, "WARNING")).contains("DETACH_NOT_NEEDED");
        assertThat((Object) JsonPath.read(preview, "$.report.planned.detach")).isNull();
        assertThat((String) JsonPath.read(preview, "$.report.planned.template.action")).isEqualTo("CREATE");
        apply(receipt, JsonPath.read(preview, "$.previewFingerprint"), REASON, "libraryCode", "LIB", DETACH, "true")
                .andExpect(status().isOk());
        assertThat(definitionId(supplier(single), "VROOAM2")).isPositive();
        assertThat(count("select count(*) from legacy_config_detachment d join legacy_config_origin o "
                + "on o.id = d.origin_id where o.origin_dossier = ?", dossier(single))).isZero();
    }

    // --- Varianten ------------------------------------------------------------------------------------------------

    /** Geen ACTIVE revisie: revisie 1 van de kopie is een DRAFT met de nieuwe inhoud; niets geactiveerd. */
    @Test
    void withoutAnActiveRevisionTheCopyStartsWithADraftOfTheNewContent() throws Exception {
        Group g = group(false);
        line(g.a(), 26).put("column", 17);
        long changed = receive(g.a());
        String preview = previewBody(changed, DETACH, "true");
        assertThat(blockingCodes(preview)).isEmpty();
        assertThat((Boolean) JsonPath.read(preview, "$.report.planned.detach.carriesActive")).isFalse();
        assertThat((Object) JsonPath.read(preview, "$.report.planned.detach.copiedRevisionId")).isNull();
        assertThat((String) JsonPath.read(preview, "$.report.planned.definition.revisionAction")).isEqualTo("CREATE");
        apply(changed, JsonPath.read(preview, "$.previewFingerprint"), REASON, DETACH, "true")
                .andExpect(status().isOk());

        long copy = definitionId(g.supplier(), "LEGACY-" + interfaceCode(g.a()));
        assertThat(revisionRows(copy)).singleElement().satisfies(row -> {
            assertThat(row.get("status")).isEqualTo("DRAFT");
            assertThat(row.get("record_base_price_field")).isEqualTo("17");
        });
        assertThat(count("select import_definition_id from import_link where id = ?", g.linkA())).isEqualTo(copy);
        // De open DRAFT van de gedeelde definitie blijft bij de groep.
        assertThat(revisionRows(g.shared())).singleElement()
                .satisfies(row -> assertThat(row.get("status")).isEqualTo("DRAFT"));
    }

    /** Design G2a punt 8: een nieuwe interface die afwijkt krijgt met de optie een eigen definitie, zonder verleggen. */
    @Test
    void aNewDivergingInterfaceGetsItsOwnDefinitionWithoutRepointing() throws Exception {
        Group g = groupWithActiveRevision();
        ObjectNode other = g.a().deepCopy();
        interfaceCode(other, interfaceCode(g.a()) + "S");
        detail(other, "Separator", "Comma");
        long receipt = receive(other);
        assertThat(blockingCodes(previewBody(receipt, "libraryCode", "LIB-S")))
                .containsExactly("SHARED_DEFINITION_DIVERGED");

        String preview = previewBody(receipt, "libraryCode", "LIB-S", DETACH, "true");
        assertThat(blockingCodes(preview)).isEmpty();
        assertThat((String) JsonPath.read(preview, "$.report.planned.link.action")).isEqualTo("CREATE");
        assertThat((Boolean) JsonPath.read(preview, "$.report.planned.detach.carriesActive")).isFalse();
        assertThat((String) JsonPath.read(preview, "$.report.planned.definition.revisionAction")).isEqualTo("CREATE");
        assertThat((List<String>) JsonPath.read(preview, "$.report.sharedWith")).isEmpty();
        apply(receipt, JsonPath.read(preview, "$.previewFingerprint"), REASON, "libraryCode", "LIB-S", DETACH, "true")
                .andExpect(status().isOk());

        String iface = interfaceCode(other);
        long copy = definitionId(g.supplier(), "LEGACY-" + iface);
        long link = count("select id from import_link where code = ?", "LEGACY-" + iface);
        assertThat(count("select import_definition_id from import_link where id = ?", link)).isEqualTo(copy);
        assertThat(revisionRows(copy)).singleElement().satisfies(row -> {
            assertThat(row.get("status")).isEqualTo("DRAFT");
            assertThat(row.get("structure_delimiter")).isEqualTo(",");
        });
        assertThat(count("select import_definition_id from import_link where id = ?", g.linkA())).isEqualTo(g.shared());
        assertThat(jdbc.queryForObject("select detached_interface_code from legacy_config_definition_origin "
                + "where import_definition_id = ?", String.class, copy)).isEqualTo(iface);
        assertThat(count("select to_definition_id from legacy_config_detachment where import_link_id = ?", link))
                .isEqualTo(copy);
    }

    // --- Hulp -----------------------------------------------------------------------------------------------------

    private Group groupWithActiveRevision() throws Exception {
        return group(true);
    }

    /** A en B op één gedeelde definitie; optioneel revisie 1 geactiveerd door de mens. */
    private Group group(boolean activate) throws Exception {
        ObjectNode a = applicable();
        ObjectNode b = a.deepCopy();
        interfaceCode(b, interfaceCode(a) + "B");
        previewAndApply(receive(a), "libraryCode", "LIB-A").andExpect(status().isOk());
        previewAndApply(receive(b), "libraryCode", "LIB-B").andExpect(status().isOk());
        String supplier = supplier(a);
        long shared = definitionId(supplier, "VROOAM2");
        long revision = ((Number) revisionRows(shared).get(0).get("id")).longValue();
        if (activate) {
            setup.activateRevision(revision, ACTOR);
        }
        return new Group(a, b, supplier, shared, revision,
                count("select id from import_link where code = ?", "LEGACY-" + interfaceCode(a)),
                count("select id from import_link where code = ?", "LEGACY-" + interfaceCode(b)));
    }

    private record History(long batchId) {
    }

    /** Een afgeronde batch onder de gedeelde revisie, een bronstaatrij en een prijsobservatie op de koppeling. */
    private History history(long linkId, long revisionId) {
        ImportBatch batch = batch(linkId, revisionId, ImportBatchStatus.FAILED);
        long deliveryId = batch.getDelivery().getId();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        byte[] identity = sha256("identity-" + linkId);
        long stateId = jdbc.queryForObject("insert into catalog_source_state (import_link_id, identity_hash, "
                        + "identity_supplier, identity_supplier_group, identity_supplier_reference, "
                        + "identity_discount_state, identity_profile_kind, article_fingerprint, price_fingerprint, "
                        + "combined_fingerprint, base_price, state_origin, last_change_delivery_id, "
                        + "last_change_batch_id, created_at, updated_at) "
                        + "values (?, ?, 'LEV', 'GRP', 'REF', 'NOT_USED', 'THREE_PART', ?, ?, ?, ?, "
                        + "'BASELINE_ACCEPTED', ?, ?, ?, ?) returning id", Long.class,
                linkId, identity, sha256("article"), sha256("price"), sha256("combined"),
                new BigDecimal("1.500000"), deliveryId, batch.getId(), now, now);
        jdbc.update("insert into catalog_price_observation (import_link_id, identity_hash, component_code, "
                        + "observation_date, amount, source_state_id, batch_id, accepted_by, created_at) "
                        + "values (?, ?, 'BASE_PRICE', ?, ?, ?, ?, ?, ?)", linkId, identity, LocalDate.now(),
                new BigDecimal("1.500000"), stateId, batch.getId(), USER, now);
        return new History(batch.getId());
    }

    /** Een batch op de (eerste) taak van de koppeling; {@code status} {@code null} = open ({@code RECEIVED}). */
    private ImportBatch batch(long linkId, long revisionId, ImportBatchStatus status) {
        CatalogImportTask task = taskRepository.findByImportLinkIdOrderByIdAsc(linkId).get(0);
        Delivery delivery = deliveryRepository.saveAndFlush(new Delivery(task, "manual:g2a-"
                + UUID.randomUUID(), Instant.now()));
        ImportBatch batch = new ImportBatch(delivery, linkRepository.findById(linkId).orElseThrow(),
                revisionRepository.findById(revisionId).orElseThrow(), 1, USER);
        if (status != null) {
            batch.setStatus(status);
        }
        return batchRepository.saveAndFlush(batch);
    }

    private Map<String, Object> linkRow(long linkId) {
        return new HashMap<>(jdbc.queryForMap("select * from import_link where id = ?", linkId));
    }

    private Map<String, Object> groupRow(long sharedDefinitionId) {
        return jdbc.queryForMap("select * from legacy_config_definition_origin where import_definition_id = ?",
                sharedDefinitionId);
    }

    private Map<String, Object> originRow(ObjectNode bundle) {
        return jdbc.queryForMap("select * from legacy_config_origin where origin_dossier = ? and interface_code = ?",
                dossier(bundle), interfaceCode(bundle));
    }

    private Map<String, Object> revisionOrigin(long revisionId) {
        return jdbc.queryForMap("select * from legacy_config_revision_origin where revision_id = ?", revisionId);
    }

    private long definitionsOf(String supplier) {
        return count("select count(*) from import_definition d join source_organisation o "
                + "on o.id = d.source_organisation_id where o.code = ?", supplier);
    }

    private List<String> valueMapRows(long revisionId) {
        return jdbc.queryForList("select m.target_field_code || ':' || v.sequence_number || ':' || v.source_value "
                + "|| '>' || v.target_value from import_field_value_map v join import_field_mapping m "
                + "on m.id = v.mapping_id where m.definition_revision_id = ? "
                + "order by m.target_field_code, v.sequence_number", String.class, revisionId);
    }

    private List<String> filterRows(long revisionId) {
        return jdbc.queryForList("select sequence_number || ':' || outcome || ':' || source_reference || ':' "
                + "|| compare_value || ':' || coalesce(cast(match_group as varchar), '-') from import_record_filter "
                + "where definition_revision_id = ? order by sequence_number", String.class, revisionId);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required but not available", impossible);
        }
    }
}
