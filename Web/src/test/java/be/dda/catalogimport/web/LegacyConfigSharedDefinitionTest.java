package be.dda.catalogimport.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.service.ActorIdentity;
import be.dda.catalogimport.service.ConflictException;
import be.dda.catalogimport.service.LegacyConfigApplyService;
import be.dda.catalogimport.service.LegacyConfigApplyService.ApplyCommand;
import be.dda.catalogimport.service.TemplateMaterialisationService;
import be.dda.catalogimport.service.TemplateMaterialisationService.MaterialisationMode;
import be.dda.catalogimport.service.TemplateMaterialisationService.MaterialisationView;
import be.dda.catalogimport.service.TemplateMaterialisationService.MaterialiseRequest;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Slice S7 van {@code docs/design/legacy-config-bundle-design.md} (par. 5 "Dedup", par. 8): gedeelde definities volgens
 * beslissingslog 2026-10-20 M1 (optie A) en 2026-10-21 "Legacy-config S7: gedeelde definities" (A2).
 *
 * <h2>Business rule → wat hier bewezen wordt</h2>
 * <ul>
 *   <li>Elke migratie maakt een {@code REUSABLE_TEMPLATE} (DRAFT, {@code <code>-SJ}) en een gedeelde
 *       {@code OWN_DEFINITION} {@code based_on} dat sjabloon, ook bij één interface; volgende interfaces van dezelfde
 *       groep krijgen enkel koppeling + taak - ongeacht de volgorde van ontvangst en APPLY.</li>
 *   <li>Nooit over leveranciers heen; {@code sharedWith} toont de (toekomstige) leden, zonder {@code DISCARDED}.</li>
 *   <li>{@code SHARED_DEFINITION_DIVERGED}: zelfde legacy-code met andere inhoud, zelfde legacy-definitie met andere
 *       interface-instellingen, of een wijziging die maar een deel van de leden bereikt. Bereikt ze alle leden, dan volgt
 *       de gedeelde definitie (opvolgende DRAFT) en blijft het sjabloon ongemoeid ({@code TEMPLATE_NOT_UPDATED}).</li>
 *   <li>Twee leden met dezelfde bibliotheek: {@code LINK_SCOPE_CONFLICT_MANUAL} al in de preview.</li>
 *   <li>Guard 006-5: het sjabloon krijgt nooit een koppeling; de materialisatiewizard herkent de gedeelde definitie
 *       ({@code REUSE_DEFINITION}) zodra de mens het sjabloon activeert.</li>
 *   <li>Herhaalde APPLY/push is veilig: geen tweede sjabloon, definitie, koppeling of taak.</li>
 * </ul>
 */
class LegacyConfigSharedDefinitionTest extends LegacyConfigS2TestBase {

    private static final ActorIdentity ACTOR = new ActorIdentity(USER, "test-sub-" + USER);

    @Autowired
    private ImportLinkRepository linkRepository;
    @Autowired
    private ImportDefinitionRepository definitionRepository;
    @Autowired
    private SourceOrganisationRepository organisationRepository;
    @Autowired
    private TemplateMaterialisationService materialisation;
    @Autowired
    private LegacyConfigApplyService applyService;

    @Test
    void threeIdenticalInterfacesShareOneTemplateAndOneDefinitionWhateverTheOrder() throws Exception {
        ObjectNode a = applicable();
        ObjectNode b = member(a, "B");
        ObjectNode c = member(a, "C");
        String supplier = supplier(a);
        long receiptA = receive(a);
        long receiptB = receive(b);

        // A en B zijn ontvangen, C nog niet: de preview van B toont A als toekomstig lid.
        String previewB = previewBody(receiptB, "libraryCode", "LIB-B");
        assertThat(blockingCodes(previewB)).isEmpty();
        assertThat((List<String>) JsonPath.read(previewB, "$.report.sharedWith")).containsExactly(interfaceCode(a));
        assertThat((String) JsonPath.read(previewB, "$.report.planned.template.action")).isEqualTo("CREATE");
        assertThat((String) JsonPath.read(previewB, "$.report.planned.template.code")).isEqualTo("VROOAM2-SJ");
        assertThat((String) JsonPath.read(previewB, "$.report.planned.definition.action")).isEqualTo("CREATE");

        // B eerst toegepast: B maakt sjabloon + gedeelde definitie.
        String appliedB = apply(receiptB, JsonPath.read(previewB, "$.previewFingerprint"), REASON, "libraryCode",
                "LIB-B").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long template = definitionId(supplier, "VROOAM2-SJ");
        long shared = definitionId(supplier, "VROOAM2");
        assertThat(((Number) JsonPath.read(appliedB, "$.report.planned.template.id")).longValue()).isEqualTo(template);
        assertThat((Object) JsonPath.read(appliedB, "$.report.planned.template.revisionId")).isNotNull();

        // C komt pas nu binnen (laatkomer) en wordt vóór A toegepast: enkel koppeling + taak.
        long receiptC = receive(c);
        String previewC = previewBody(receiptC, "libraryCode", "LIB-C");
        assertThat(blockingCodes(previewC)).isEmpty();
        assertThat(codes(previewC, "MIGRATED")).contains("SHARED_DEFINITION");
        assertThat((List<String>) JsonPath.read(previewC, "$.report.sharedWith"))
                .containsExactly(interfaceCode(a), interfaceCode(b));
        assertThat((String) JsonPath.read(previewC, "$.report.planned.definition.action")).isEqualTo("REUSE");
        assertThat((String) JsonPath.read(previewC, "$.report.planned.definition.revisionAction"))
                .isEqualTo("UNCHANGED");
        assertThat((String) JsonPath.read(previewC, "$.report.planned.template.action")).isEqualTo("REUSE");
        assertThat(((Number) JsonPath.read(previewC, "$.report.planned.template.id")).longValue()).isEqualTo(template);
        apply(receiptC, JsonPath.read(previewC, "$.previewFingerprint"), REASON, "libraryCode", "LIB-C")
                .andExpect(status().isOk());
        previewAndApply(receiptA, "libraryCode", "LIB-A").andExpect(status().isOk())
                .andExpect(jsonPath("$.report.planned.definition.id").value(shared));

        // Eén sjabloon, één gedeelde definitie, drie koppelingen + taken; het sjabloon blijft koppelingsvrij.
        assertThat(count("select count(*) from import_definition d join source_organisation o "
                + "on o.id = d.source_organisation_id where o.code = ?", supplier)).isEqualTo(2);
        Map<String, Object> templateRow = jdbc.queryForMap("select * from import_definition where id = ?", template);
        assertThat(templateRow.get("usage_type")).isEqualTo("REUSABLE_TEMPLATE");
        Map<String, Object> sharedRow = jdbc.queryForMap("select * from import_definition where id = ?", shared);
        assertThat(sharedRow.get("usage_type")).isEqualTo("OWN_DEFINITION");
        assertThat(((Number) sharedRow.get("based_on_definition_id")).longValue()).isEqualTo(template);
        List<Map<String, Object>> templateRevisions = revisionRows(template);
        assertThat(templateRevisions).singleElement().satisfies(row -> assertThat(row.get("status")).isEqualTo("DRAFT"));
        List<Map<String, Object>> sharedRevisions = revisionRows(shared);
        assertThat(sharedRevisions).hasSize(1);
        assertThat(sharedRevisions.get(0).get("status")).isEqualTo("DRAFT");
        assertThat(sharedRevisions.get(0).get("based_on_revision_id")).isEqualTo(templateRevisions.get(0).get("id"));
        assertThat(jdbc.queryForList("select library_code from import_link where import_definition_id = ? "
                + "order by library_code", String.class, shared)).containsExactly("LIB-A", "LIB-B", "LIB-C");
        assertThat(count("select count(*) from catalog_import_task t join import_link l on l.id = t.import_link_id "
                + "where l.import_definition_id = ?", shared)).isEqualTo(3);
        assertThat(count("select count(*) from import_link where import_definition_id = ?", template)).isZero();
        // 020-3 met beide id's; 020-4 enkel op de revisie van de gedeelde definitie.
        Map<String, Object> group = jdbc.queryForMap("select * from legacy_config_definition_origin "
                + "where origin_dossier = ?", dossier(a));
        assertThat(((Number) group.get("template_definition_id")).longValue()).isEqualTo(template);
        assertThat(((Number) group.get("import_definition_id")).longValue()).isEqualTo(shared);
        assertThat(group.get("legacy_definition_codes")).isEqualTo("VROOAM2");
        assertThat(jdbc.queryForList("select revision_id from legacy_config_revision_origin ro join "
                + "legacy_config_receipt r on r.id = ro.receipt_id where r.origin_dossier = ?", Long.class, dossier(a)))
                .containsExactly(((Number) sharedRevisions.get(0).get("id")).longValue());

        // Herhaalde push van een lid (andere contentHash, zelfde definitie): niets nieuws.
        List<Long> before = footprint(a);
        ((ObjectNode) a.get("interface").get("header")).put("info", "tweede export");
        long again = receive(a);
        String previewAgain = previewBody(again);
        assertThat(codes(previewAgain, "MIGRATED")).contains("DEFINITION_UNCHANGED");
        apply(again, JsonPath.read(previewAgain, "$.previewFingerprint"), REASON).andExpect(status().isOk());
        List<Long> after = footprint(a);
        assertThat(after.subList(0, 3)).isEqualTo(before.subList(0, 3));
        assertThat(count("select count(*) from import_link where import_definition_id = ?", shared)).isEqualTo(3);
        assertThat(revisionRows(shared)).hasSize(1);
        assertThat(revisionRows(template)).hasSize(1);
    }

    @Test
    void aSingleInterfaceAlsoGetsATemplateAndASharedDefinition() throws Exception {
        ObjectNode bundle = applicable();
        String body = previewAndApply(receive(bundle), "libraryCode", "LIB").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        long template = definitionId(supplier(bundle), "VROOAM2-SJ");
        long shared = definitionId(supplier(bundle), "VROOAM2");
        assertThat((List<String>) JsonPath.read(body, "$.report.sharedWith")).isEmpty();
        assertThat(jdbc.queryForObject("select usage_type from import_definition where id = ?", String.class,
                template)).isEqualTo("REUSABLE_TEMPLATE");
        assertThat(jdbc.queryForObject("select based_on_definition_id from import_definition where id = ?",
                Long.class, shared)).isEqualTo(template);
        // Het sjabloon draagt dezelfde gemigreerde inhoud (zelfde structuur en identiteit als de gedeelde definitie).
        Map<String, Object> templateRevision = revisionRows(template).get(0);
        Map<String, Object> sharedRevision = revisionRows(shared).get(0);
        for (String column : List.of("structure_delimiter", "structure_charset", "identity_profile_kind",
                "identity_supplier_field", "identity_supplier_group_field", "identity_supplier_reference_field",
                "record_base_price_field", "identity_uppercase", "structure_config_hash", "record_rules_config_hash")) {
            assertThat(sharedRevision.get(column)).as(column).isEqualTo(templateRevision.get(column));
        }
        assertThat(count("select count(*) from import_field_mapping where definition_revision_id = ?",
                templateRevision.get("id"))).isEqualTo(2);
        assertThat(count("select count(*) from import_record_filter where definition_revision_id = ?",
                templateRevision.get("id"))).isEqualTo(1);
    }

    @Test
    void definitionsAreNeverSharedAcrossSuppliersAndDiscardedReceiptsDoNotCount() throws Exception {
        ObjectNode a = applicable();
        ObjectNode otherSupplier = member(a, "X");
        detail(otherSupplier, "Supplier", "T" + ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999));
        ObjectNode discarded = member(a, "D");
        long receiptA = receive(a);
        long receiptOther = receive(otherSupplier);
        long receiptDiscarded = receive(discarded);
        jdbc.update("update legacy_config_receipt set status = 'DISCARDED', open_marker = null, discarded_at = "
                + "current_timestamp, discarded_by = 'test', discarded_reason = 'test' where id = ?", receiptDiscarded);

        assertThat((List<String>) JsonPath.read(previewBody(receiptA, "libraryCode", "LIB"), "$.report.sharedWith"))
                .isEmpty();
        previewAndApply(receiptA, "libraryCode", "LIB").andExpect(status().isOk());
        String other = previewBody(receiptOther, "libraryCode", "LIB");
        assertThat((String) JsonPath.read(other, "$.report.planned.definition.action")).isEqualTo("CREATE");
        assertThat((String) JsonPath.read(other, "$.report.planned.template.action")).isEqualTo("CREATE");
        assertThat((List<String>) JsonPath.read(other, "$.report.sharedWith")).isEmpty();
        apply(receiptOther, JsonPath.read(other, "$.previewFingerprint"), REASON, "libraryCode", "LIB")
                .andExpect(status().isOk());

        assertThat(definitionId(supplier(otherSupplier), "VROOAM2"))
                .isNotEqualTo(definitionId(supplier(a), "VROOAM2"));
        assertThat(count("select count(*) from legacy_config_definition_origin where origin_dossier = ?", dossier(a)))
                .isEqualTo(2);
    }

    @Test
    void theSameLibraryTwiceInOneGroupIsBlockedInThePreview() throws Exception {
        ObjectNode a = applicable();
        ObjectNode b = member(a, "B");
        previewAndApply(receive(a), "libraryCode", "LIB-SAME").andExpect(status().isOk());
        long receiptB = receive(b);
        List<Long> before = footprint(b);

        String preview = previewBody(receiptB, "libraryCode", "LIB-SAME");
        assertThat(blockingCodes(preview)).containsExactly("LINK_SCOPE_CONFLICT_MANUAL");
        apply(receiptB, JsonPath.read(preview, "$.previewFingerprint"), REASON, "libraryCode", "LIB-SAME")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LEGACY_APPLY_BLOCKED"));
        assertThat(footprint(b)).isEqualTo(before);

        assertThat(blockingCodes(previewBody(receiptB, "libraryCode", "LIB-OTHER"))).isEmpty();
    }

    @Test
    void aNewInterfaceThatDiffersFromItsGroupIsDiverged() throws Exception {
        ObjectNode a = applicable();
        previewAndApply(receive(a), "libraryCode", "LIB-A").andExpect(status().isOk());

        // Zelfde legacy-code onder dezelfde leverancier, andere inhoud.
        ObjectNode otherContent = member(a, "C");
        line(otherContent, 26).put("column", 27);
        String preview = previewBody(receive(otherContent), "libraryCode", "LIB-C");
        assertThat(blockingCodes(preview)).containsExactly("SHARED_DEFINITION_DIVERGED");

        // Zelfde legacy-definitie (zelfde definitionContentHash), andere interface-instelling (scheidingsteken).
        ObjectNode otherSettings = member(a, "S");
        detail(otherSettings, "Separator", "Comma");
        String settings = previewBody(receive(otherSettings), "libraryCode", "LIB-S");
        assertThat(blockingCodes(settings)).containsExactly("SHARED_DEFINITION_DIVERGED");
        assertThat((List<String>) JsonPath.read(settings, "$.report.sharedWith")).containsExactly(interfaceCode(a));
    }

    @Test
    void aChangeReachingOnlyPartOfTheGroupIsDivergedAndAChangeReachingAllFollowsWithoutTheTemplate()
            throws Exception {
        ObjectNode a = applicable();
        ObjectNode b = member(a, "B");
        long receiptA = receive(a);
        long receiptB = receive(b);
        previewAndApply(receiptA, "libraryCode", "LIB-A").andExpect(status().isOk());
        previewAndApply(receiptB, "libraryCode", "LIB-B").andExpect(status().isOk());
        String supplier = supplier(a);
        long template = definitionId(supplier, "VROOAM2-SJ");
        long shared = definitionId(supplier, "VROOAM2");
        long first = ((Number) revisionRows(shared).get(0).get("id")).longValue();
        setup.activateRevision(first, ACTOR);

        // Enkel A levert de nieuwe legacy-inhoud: de groep loopt uiteen.
        line(a, 26).put("column", 17);
        long changedA = receive(a);
        assertThat(blockingCodes(previewBody(changedA))).containsExactly("SHARED_DEFINITION_DIVERGED");

        // Nu levert ook B ze: de gedeelde definitie krijgt een opvolgende DRAFT, het sjabloon niet.
        line(b, 26).put("column", 17);
        long changedB = receive(b);
        String preview = previewBody(changedA);
        assertThat(blockingCodes(preview)).isEmpty();
        assertThat((String) JsonPath.read(preview, "$.report.planned.definition.revisionAction"))
                .isEqualTo("SUCCESSOR_DRAFT");
        assertThat(codes(preview, "NOT_MIGRATED")).contains("TEMPLATE_NOT_UPDATED");
        assertThat((List<String>) JsonPath.read(preview, "$.report.sharedWith")).containsExactly(interfaceCode(b));
        apply(changedA, JsonPath.read(preview, "$.previewFingerprint"), REASON).andExpect(status().isOk());

        assertThat(revisionRows(shared)).hasSize(2);
        assertThat(revisionRows(shared).get(1).get("record_base_price_field")).isEqualTo("17");
        assertThat(revisionRows(template)).singleElement()
                .satisfies(row -> assertThat(row.get("record_base_price_field")).isEqualTo("7"));
        assertThat(jdbc.queryForObject("select definition_content_hash from legacy_config_definition_origin "
                + "where import_definition_id = ?", String.class, shared))
                .isEqualTo(a.get("definitionContentHash").asText());

        // B volgt: de gedeelde definitie heeft die inhoud al.
        String previewB = previewBody(changedB);
        assertThat(blockingCodes(previewB)).isEmpty();
        assertThat(codes(previewB, "MIGRATED")).contains("DEFINITION_UNCHANGED");
        apply(changedB, JsonPath.read(previewB, "$.previewFingerprint"), REASON).andExpect(status().isOk());
        assertThat(revisionRows(shared)).hasSize(2);
    }

    @Test
    void theTemplateNeverGetsALinkButTheWizardCanReuseTheSharedDefinitionOnceTheTemplateIsActive()
            throws Exception {
        ObjectNode bundle = applicable();
        previewAndApply(receive(bundle), "libraryCode", "LIB-1").andExpect(status().isOk());
        String supplier = supplier(bundle);
        long template = definitionId(supplier, "VROOAM2-SJ");
        long shared = definitionId(supplier, "VROOAM2");

        // Guard 006-5: rechtstreeks via de repository, zonder enige servicecontrole.
        ImportLink smuggled = new ImportLink("SMOKKEL-" + interfaceCode(bundle), "Koppeling naar een sjabloon",
                definitionRepository.findById(template).orElseThrow(),
                organisationRepository.findByCode(supplier).orElseThrow(), "LIB-X");
        assertThatThrownBy(() -> linkRepository.saveAndFlush(smuggled))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("select count(*) from import_link where import_definition_id = ?", template)).isZero();

        // De mens activeert het sjabloon; daarna herkent de materialisatiewizard de gedeelde definitie.
        long templateRevision = ((Number) revisionRows(template).get(0).get("id")).longValue();
        setup.activateRevision(templateRevision, ACTOR);
        MaterialisationView reused = materialisation.materialise(template, new MaterialiseRequest(null,
                MaterialisationMode.REUSE_DEFINITION, shared, null, null, null, "WIZ-" + interfaceCode(bundle),
                "Koppeling via de wizard", supplier, "LIB-2", null, List.of(), USER), ACTOR);

        assertThat(reused.definitionCreated()).isFalse();
        assertThat(reused.definitionId()).isEqualTo(shared);
        assertThat(count("select count(*) from import_link where import_definition_id = ?", shared)).isEqualTo(2);
        assertThat(revisionRows(shared)).hasSize(1);
    }

    /**
     * Twee leden van een nieuwe groep tegelijk: beide previews plannen een nieuw sjabloon; de database (unieke codes)
     * laat er één winnen, de ander krijgt 409 en schrijft niets. Een nieuwe preview van de verliezer sluit aan.
     */
    @Test
    void concurrentAppliesOfTwoMembersOfANewGroupCreateOneGroup() throws Exception {
        ObjectNode a = applicable();
        ObjectNode b = member(a, "B");
        long receiptA = receive(a);
        long receiptB = receive(b);
        ApplyCommand commandA = new ApplyCommand(fingerprint(receiptA, "libraryCode", "LIB-A"), REASON, null, null,
                "LIB-A");
        ApplyCommand commandB = new ApplyCommand(fingerprint(receiptB, "libraryCode", "LIB-B"), REASON, null, null,
                "LIB-B");

        List<Object> outcomes = concurrently(List.of(() -> outcome(receiptA, commandA),
                () -> outcome(receiptB, commandB)));

        assertThat(outcomes.stream().filter("APPLIED"::equals).count()).isEqualTo(1);
        // De verliezer botst op een unieke code (nieuwe leverancier of sjabloon/definitie) en krijgt een vertaalde 409.
        assertThat(outcomes).filteredOn(outcome -> !"APPLIED".equals(outcome)).singleElement()
                .isIn("SOURCE_ORGANISATION_CODE_IN_USE", "DEFINITION_CODE_IN_USE");
        String supplier = supplier(a);
        assertThat(count("select count(*) from import_definition d join source_organisation o "
                + "on o.id = d.source_organisation_id where o.code = ?", supplier)).isEqualTo(2);
        assertThat(count("select count(*) from legacy_config_definition_origin where origin_dossier = ?", dossier(a)))
                .isEqualTo(1);
        long loser = "APPLIED".equals(outcomes.get(0)) ? receiptB : receiptA;
        String library = loser == receiptA ? "LIB-A" : "LIB-B";
        assertThat(receiptRow(loser).get("status")).isEqualTo("RECEIVED");
        previewAndApply(loser, "libraryCode", library).andExpect(status().isOk())
                .andExpect(jsonPath("$.report.planned.definition.action").value("REUSE"));
        assertThat(count("select count(*) from import_link where import_definition_id = ?",
                definitionId(supplier, "VROOAM2"))).isEqualTo(2);
    }

    @Test
    void aKnownInterfaceWhoseContentNowEqualsAnotherGroupIsDiverged() throws Exception {
        ObjectNode a = applicable();
        ObjectNode b = member(a, "B");
        ((ObjectNode) b.get("definition")).put("code", "VROOAM3");
        line(b, 26).put("column", 27);
        previewAndApply(receive(a), "libraryCode", "LIB-A").andExpect(status().isOk());
        previewAndApply(receive(b), "libraryCode", "LIB-B").andExpect(status().isOk());
        assertThat(definitionId(supplier(b), "VROOAM3-SJ")).isPositive();

        a.set("definition", b.get("definition").deepCopy());
        String preview = previewBody(receive(a));

        assertThat(blockingCodes(preview)).containsExactly("SHARED_DEFINITION_DIVERGED");
        assertThat(count("select count(*) from legacy_config_definition_origin where origin_dossier = ?", dossier(a)))
                .isEqualTo(2);
    }

    // --- Hulp -----------------------------------------------------------------------------------------------------

    private Object outcome(long receiptId, ApplyCommand command) {
        try {
            return applyService.apply(receiptId, command, ACTOR).receiptStatus();
        } catch (ConflictException conflict) {
            return conflict.getCode();
        } catch (RuntimeException other) {
            return other.getClass().getSimpleName();
        }
    }

    private static List<Object> concurrently(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Een andere interface met exact dezelfde inhoud (zelfde dossier, leverancier en definitie). */
    private static ObjectNode member(ObjectNode base, String suffix) {
        ObjectNode copy = base.deepCopy();
        interfaceCode(copy, interfaceCode(base) + suffix);
        return copy;
    }
}
