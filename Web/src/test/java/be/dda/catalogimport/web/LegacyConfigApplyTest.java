package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.domain.DefinitionUsageType;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.service.ActorIdentity;
import be.dda.catalogimport.service.ConflictException;
import be.dda.catalogimport.service.LegacyConfigApplyService;
import be.dda.catalogimport.service.LegacyConfigApplyService.ApplyCommand;
import be.dda.catalogimport.service.SetupService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * APPLY van slice S2 ({@code docs/design/legacy-config-bundle-design.md} par. 2, 4, 5 en 8; beslissingslog 2026-10-20
 * M1-M3, "Legacy-config S1: idempotentie" en "Legacy-config S2").
 *
 * <h2>Business rule → wat hier bewezen wordt</h2>
 * <ul>
 *   <li>APPLY maakt in één transactie organisatie, OWN_DEFINITION (DRAFT), koppeling, MANUAL-taak en herkomstrijen,
 *       met de verwachte structuur; activeert nooit.</li>
 *   <li>Enkel op RECEIVED: tweede APPLY (ook gelijktijdig) en een vervangen ontvangst zijn 409; een verouderde of
 *       ontbrekende fingerprint is 409; zonder reden 400; een geblokkeerd rapport wordt nooit toegepast.</li>
 *   <li>Zelfde inhoud → {@code DEFINITION_UNCHANGED}; gewijzigd met open DRAFT → {@code OPEN_DRAFT_EXISTS};
 *       gewijzigd na activatie → opvolgende DRAFT, ACTIVE ongemoeid, drempels uit de kopie, {@code IDENTITY_CHANGE};
 *       handmatig gewijzigd sinds de migratie → {@code MANUAL_CHANGES_SINCE_MIGRATION}.</li>
 *   <li>Conflicten: handmatige definitie (op te lossen met {@code definitionCodeOverride}), koppelingscode,
 *       organisatietype. Sinds S7 (A2) is de definitie de gedeelde {@code OWN_DEFINITION} van een sjabloon
 *       ({@code <code>-SJ}); het delen zelf: {@link LegacyConfigSharedDefinitionTest}.</li>
 * </ul>
 */
class LegacyConfigApplyTest extends LegacyConfigS2TestBase {

    private static final ActorIdentity ACTOR = new ActorIdentity(USER, "test-sub-" + USER);

    @Autowired
    private LegacyConfigApplyService applyService;

    /**
     * S4: zonder secret en zonder credential op de herkomst blijft {@code planned.credential} {@code null}; het
     * verbindingsvoorstel staat in het rapport, er wordt geen profiel aangemaakt (L5). Met credential:
     * {@code LegacyConfigSecretLeakTest}.
     */
    @Test
    void withoutASecretThereIsNoPlannedCredentialButAConnectionProposal() throws Exception {
        ObjectNode bundle = applicable();
        long id = receive(bundle);
        long profilesBefore = count("select count(*) from connection_profile");

        String body = previewAndApply(id, "libraryCode", "LIB-S4").andExpect(status().isOk())
                .andExpect(jsonPath("$.report.planned.credential").value(org.hamcrest.Matchers.nullValue()))
                .andReturn().getResponse().getContentAsString();

        List<String> connection = JsonPath.read(body, "$.report.items[?(@.code == 'CONNECTION_NOT_MIGRATED')].message");
        assertThat(connection).singleElement().asString().contains("protocol=SFTP", "host=sftp.example.test",
                "port=22 (default)", "directory=/exports/", "login=fixture-user", "credentialRef=(none)");
        assertThat(count("select count(*) from connection_profile")).isEqualTo(profilesBefore);
    }

    @Test
    void theDerivedGoldenBecomesADraftDefinitionWithLinkAndManualTask() throws Exception {
        ObjectNode bundle = applicable();
        long id = receive(bundle);
        String supplier = supplier(bundle);
        String iface = interfaceCode(bundle);

        String body = previewAndApply(id, "libraryCode", "LIB-S2").andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptStatus").value("APPLIED"))
                .andExpect(jsonPath("$.report.outcome").value("APPLICABLE"))
                // De gemigreerde DRAFT doorstaat dezelfde configuratievalidatie als het activeren.
                .andExpect(jsonPath("$.configurationFindings").isEmpty())
                .andReturn().getResponse().getContentAsString();

        // Bronorganisatie: find-or-create, SUPPLIER, plaatshoudernaam (Prodis-leesbron uit; met Prodis: S5-test).
        Map<String, Object> organisation = jdbc.queryForMap("select * from source_organisation where code = ?",
                supplier);
        assertThat(organisation.get("name")).isEqualTo("Leverancier " + supplier);
        assertThat(organisation.get("organisation_type")).isEqualTo("SUPPLIER");

        // Definitie: OWN_DEFINITION met de legacy-code.
        long definitionId = definitionId(supplier, "VROOAM2");
        Map<String, Object> definition = jdbc.queryForMap("select * from import_definition where id = ?",
                definitionId);
        assertThat(definition.get("usage_type")).isEqualTo("OWN_DEFINITION");
        assertThat(definition.get("description")).isEqualTo("Golden fixture catalogus CSV");
        // S7 (A2): altijd ook een sjabloon (DRAFT), waarop de gedeelde definitie gebaseerd is.
        long templateId = definitionId(supplier, "VROOAM2-SJ");
        assertThat(jdbc.queryForObject("select usage_type from import_definition where id = ?", String.class,
                templateId)).isEqualTo("REUSABLE_TEMPLATE");
        assertThat(((Number) definition.get("based_on_definition_id")).longValue()).isEqualTo(templateId);
        assertThat(((Number) JsonPath.read(body, "$.report.planned.template.id")).longValue()).isEqualTo(templateId);
        assertThat(((Number) JsonPath.read(body, "$.report.planned.definition.id")).longValue())
                .isEqualTo(definitionId);

        // Revisie 1: DRAFT, nooit geactiveerd, met de vertaalde structuur.
        List<Map<String, Object>> revisions = revisionRows(definitionId);
        assertThat(revisions).hasSize(1);
        Map<String, Object> revision = revisions.get(0);
        assertThat(revision.get("status")).isEqualTo("DRAFT");
        assertThat(revision.get("approved_at")).isNull();
        assertThat(revision.get("structure_delimiter")).isEqualTo(";");
        assertThat(revision.get("structure_charset")).isEqualTo("UTF-8");
        assertThat(revision.get("structure_has_header")).isEqualTo(true);
        assertThat(revision.get("structure_header_line_number")).isEqualTo(1);
        assertThat(revision.get("structure_field_reference_kind")).isEqualTo("COLUMN_INDEX");
        assertThat(revision.get("identity_profile_kind")).isEqualTo("FOUR_PART_WITH_DISCOUNT_CODE");
        assertThat(revision.get("identity_supplier_field")).isEqualTo("3");
        assertThat(revision.get("identity_supplier_group_field")).isEqualTo("4");
        assertThat(revision.get("identity_supplier_reference_field")).isEqualTo("1");
        assertThat(revision.get("identity_discount_code_field")).isEqualTo("5");
        assertThat(revision.get("record_base_price_field")).isEqualTo("7");
        assertThat(revision.get("record_description_field")).isEqualTo("2");
        assertThat(revision.get("record_canonicalisation_version")).isEqualTo(2);
        assertThat(revision.get("change_reason")).isEqualTo(REASON);
        assertThat(revision.get("created_by")).isEqualTo(USER);
        long revisionId = ((Number) revision.get("id")).longValue();
        List<Map<String, Object>> templateRevisions = revisionRows(templateId);
        assertThat(templateRevisions).hasSize(1);
        assertThat(templateRevisions.get(0).get("status")).isEqualTo("DRAFT");
        assertThat(revision.get("based_on_revision_id")).isEqualTo(templateRevisions.get(0).get("id"));

        // Mappings: 33 met MAP en DecimalComma; 37 als bronkolom. S8a: de PSIMPDEFMAP-regels (gesorteerd op NUMMER,
        // KEY) staan in import_field_value_map. S8c: de regel wordt een CHAIN met één MAP-stap; de mapping draagt
        // enkel nog de notatie, de stap de instelling.
        List<Map<String, Object>> mappings = jdbc.queryForList("select target_field_code, value_kind, "
                + "source_reference, transform_kind, transform_config from import_field_mapping "
                + "where definition_revision_id = ? order by target_field_code", revisionId);
        assertThat(mappings).hasSize(2);
        assertThat(mappings.get(0)).containsEntry("target_field_code", "VKP1_PCT")
                .containsEntry("source_reference", "8").containsEntry("transform_kind", "CHAIN")
                .containsEntry("transform_config", "decimalSeparator=,");
        assertThat(steps(revisionId, "VKP1_PCT")).containsExactly("1:MAP:caseSensitive=true;onMiss=KEEP_SOURCE");
        assertThat(jdbc.queryForList("select s.created_by from import_field_transform_step s join import_field_mapping "
                + "m on m.id = s.mapping_id where m.definition_revision_id = ?", String.class, revisionId))
                .containsOnly(USER);
        assertThat(valueMap(revisionId, "VKP1_PCT")).containsExactly("1:DOOS>BOX", "2:STK>PCE");
        assertThat(jdbc.queryForList("select v.created_by from import_field_value_map v join import_field_mapping m "
                + "on m.id = v.mapping_id where m.definition_revision_id = ?", String.class, revisionId))
                .containsOnly(USER);
        // S7: het sjabloon draagt dezelfde rijen (de gedeelde definitie is er een kopie van).
        assertThat(valueMap(((Number) templateRevisions.get(0).get("id")).longValue(), "VKP1_PCT"))
                .containsExactly("1:DOOS>BOX", "2:STK>PCE");
        assertThat(steps(((Number) templateRevisions.get(0).get("id")).longValue(), "VKP1_PCT"))
                .containsExactly("1:MAP:caseSensitive=true;onMiss=KEEP_SOURCE");
        assertThat(mappings.get(1)).containsEntry("target_field_code", "VKP5_PCT")
                .containsEntry("source_reference", "9").containsEntry("transform_kind", "NONE")
                .containsEntry("transform_config", "decimalSeparator=,");

        // Include/Exclude 1 op lijn 37 → INCLUDE EQUALS op kolom 9.
        List<Map<String, Object>> filters = jdbc.queryForList("select * from import_record_filter "
                + "where definition_revision_id = ? order by sequence_number", revisionId);
        assertThat(filters).hasSize(5);
        assertThat(filters.get(0)).containsEntry("source_reference", "9").containsEntry("operator", "EQUALS")
                .containsEntry("compare_value", "ACME").containsEntry("outcome", "INCLUDE")
                .containsEntry("case_sensitive", true).containsEntry("match_group", null);
        // S8f: de PSIMPEXCL-rij ('9999', '', '', 'REF-1') is de vorm "alle vier": een EN-groep op de kolommen van
        // 9, 10, 23 en 11 (leverancier, groep en referentie hoofdletterongevoelig, de kortingscode niet).
        assertThat(filters(revisionId)).containsExactly("1:INCLUDE:EQUALS:9:ACME:EXCLUDE:true:true:-",
                "2:EXCLUDE:EQUALS:3:9999:COMPARE_AS_EMPTY:false:true:1",
                "3:EXCLUDE:EQUALS:4::COMPARE_AS_EMPTY:false:true:1",
                "4:EXCLUDE:EQUALS:5::COMPARE_AS_EMPTY:true:true:1",
                "5:EXCLUDE:EQUALS:1:REF-1:COMPARE_AS_EMPTY:false:true:1");
        assertThat(jdbc.queryForList("select created_by from import_record_filter where definition_revision_id = ?",
                String.class, revisionId)).containsOnly(USER);

        // Koppeling en MANUAL-taak.
        Map<String, Object> link = jdbc.queryForMap("select * from import_link where code = ?", "LEGACY-" + iface);
        assertThat(link.get("library_code")).isEqualTo("LIB-S2");
        assertThat(((Number) link.get("import_definition_id")).longValue()).isEqualTo(definitionId);
        assertThat(link.get("supplier_organisation_id")).isEqualTo(organisation.get("id"));
        long linkId = ((Number) link.get("id")).longValue();
        Map<String, Object> task = jdbc.queryForMap("select * from catalog_import_task where import_link_id = ?",
                linkId);
        assertThat(task.get("name")).isEqualTo("Legacy " + iface);
        assertThat(task.get("trigger_type")).isEqualTo("MANUAL");

        // Ontvangst APPLIED met reden en rapport; herkomstrijen 020-2/020-3/020-4.
        Map<String, Object> receipt = receiptRow(id);
        assertThat(receipt.get("status")).isEqualTo("APPLIED");
        assertThat(receipt.get("open_marker")).isNull();
        assertThat(receipt.get("applied_by")).isEqualTo(USER);
        assertThat(receipt.get("applied_by_subject")).isEqualTo("test-sub-" + USER);
        assertThat(receipt.get("applied_reason")).isEqualTo(REASON);
        assertThat((String) receipt.get("apply_report_json")).contains("VROOAM2", REASON, "previewFingerprint");
        Map<String, Object> origin = jdbc.queryForMap("select * from legacy_config_origin where origin_dossier = ?",
                dossier(bundle));
        assertThat(((Number) origin.get("import_link_id")).longValue()).isEqualTo(linkId);
        assertThat(origin.get("catalog_import_task_id")).isEqualTo(task.get("id"));
        assertThat(((Number) origin.get("last_applied_receipt_id")).longValue()).isEqualTo(id);
        Map<String, Object> definitionOrigin = jdbc.queryForMap("select * from legacy_config_definition_origin "
                + "where origin_dossier = ?", dossier(bundle));
        assertThat(definitionOrigin.get("supplier_code")).isEqualTo(supplier);
        assertThat(definitionOrigin.get("legacy_definition_codes")).isEqualTo("VROOAM2");
        assertThat(definitionOrigin.get("definition_content_hash"))
                .isEqualTo(bundle.get("definitionContentHash").asText());
        assertThat(((Number) definitionOrigin.get("import_definition_id")).longValue()).isEqualTo(definitionId);
        assertThat(((Number) definitionOrigin.get("template_definition_id")).longValue()).isEqualTo(templateId);
        Map<String, Object> revisionOrigin = jdbc.queryForMap("select * from legacy_config_revision_origin "
                + "where revision_id = ?", revisionId);
        assertThat(((Number) revisionOrigin.get("receipt_id")).longValue()).isEqualTo(id);
        assertThat((String) revisionOrigin.get("migrated_content_hash")).matches("[0-9a-f]{64}");
    }

    @Test
    void aSecondApplyIs409AndWritesNothing() throws Exception {
        ObjectNode bundle = applicable();
        long id = receive(bundle);
        String fingerprint = fingerprint(id, "libraryCode", "LIB");
        apply(id, fingerprint, REASON, "libraryCode", "LIB").andExpect(status().isOk());
        List<Long> after = footprint(bundle);

        apply(id, fingerprint, REASON, "libraryCode", "LIB").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEGACY_RECEIPT_NOT_APPLICABLE"));

        assertThat(footprint(bundle)).isEqualTo(after);
    }

    @Test
    void concurrentAppliesProduceExactlyOneChain() throws Exception {
        ObjectNode bundle = applicable();
        long id = receive(bundle);
        String fingerprint = fingerprint(id, "libraryCode", "LIB");
        ApplyCommand command = new ApplyCommand(fingerprint, REASON, null, null, "LIB");

        List<Object> outcomes = concurrently(2, () -> {
            try {
                return applyService.apply(id, command, ACTOR).receiptStatus();
            } catch (ConflictException conflict) {
                return conflict.getCode();
            }
        });

        assertThat(outcomes).containsExactlyInAnyOrder("APPLIED", "LEGACY_RECEIPT_NOT_APPLICABLE");
        assertThat(count("select count(*) from import_link where code = ?", "LEGACY-" + interfaceCode(bundle)))
                .isEqualTo(1);
        assertThat(count("select count(*) from legacy_config_revision_origin where receipt_id = ?", id)).isEqualTo(1);
    }

    @Test
    void aSupersededReceiptIsNotApplicable() throws Exception {
        ObjectNode bundle = applicable();
        long first = receive(bundle);
        String fingerprint = fingerprint(first, "libraryCode", "LIB");
        ((ObjectNode) bundle.get("interface").get("header")).put("info", "nieuwere export");
        receive(bundle);

        apply(first, fingerprint, REASON, "libraryCode", "LIB").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEGACY_RECEIPT_NOT_APPLICABLE"));
    }

    @Test
    void aStaleOrMissingFingerprintIs409AndWritesNothing() throws Exception {
        ObjectNode bundle = applicable();
        long id = receive(bundle);
        List<Long> before = footprint(bundle);

        apply(id, "0".repeat(64), REASON, "libraryCode", "LIB").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEGACY_PREVIEW_STALE"));
        apply(id, null, REASON, "libraryCode", "LIB").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEGACY_PREVIEW_STALE"));
        // Andere keuze dan in de preview.
        String previewedWithA = fingerprint(id, "libraryCode", "LIB-A");
        apply(id, previewedWithA, REASON, "libraryCode", "LIB-B").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEGACY_PREVIEW_STALE"));
        // De database veranderde na de preview: de leverancier bestaat nu (wordt hergebruikt i.p.v. aangemaakt).
        String previewed = fingerprint(id, "libraryCode", "LIB");
        setup.createSourceOrganisation(new SetupService.CreateSourceOrganisationCommand(supplier(bundle),
                "Handmatig aangemaakt", SourceOrganisationType.SUPPLIER));
        apply(id, previewed, REASON, "libraryCode", "LIB").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEGACY_PREVIEW_STALE"));

        List<Long> after = footprint(bundle);
        assertThat(after.get(0)).isEqualTo(before.get(0) + 1); // enkel de handmatige organisatie
        assertThat(after.subList(1, after.size())).isEqualTo(before.subList(1, before.size()));
        assertThat(receiptRow(id).get("status")).isEqualTo("RECEIVED");

        // Een nieuwe preview hergebruikt die organisatie en is wel toepasbaar.
        String body = previewAndApply(id, "libraryCode", "LIB").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.report.planned.sourceOrganisation.action")).isEqualTo("REUSE");
    }

    @Test
    void aReasonIsRequired() throws Exception {
        ObjectNode bundle = applicable();
        long id = receive(bundle);
        String fingerprint = fingerprint(id, "libraryCode", "LIB");

        apply(id, fingerprint, "  ", "libraryCode", "LIB").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LEGACY_APPLY_REASON_REQUIRED"));
        mockMvc.perform(post(RECEIPTS + "/{id}/apply", id).with(be.dda.catalogimport.testsupport.TestActors.as(USER,
                        Permission.MANAGE)).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LEGACY_APPLY_REASON_REQUIRED"));
        apply(id, fingerprint, "x".repeat(501), "libraryCode", "LIB").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LEGACY_APPLY_REASON_TOO_LONG"));

        assertThat(receiptRow(id).get("status")).isEqualTo("RECEIVED");
    }

    @Test
    void aBlockedReportIsNeverApplied() throws Exception {
        long id = receive(golden());
        String fingerprint = fingerprint(id);

        apply(id, fingerprint, REASON).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEGACY_APPLY_BLOCKED"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString(
                        "IDENTITY_TRANSFORM_UNSUPPORTED")));
        assertThat(receiptRow(id).get("status")).isEqualTo("RECEIVED");
    }

    @Test
    void aGroupLessInterfaceIsAppliedWithAThreePartIdentity() throws Exception {
        ObjectNode bundle = applicable();
        detail(bundle, "ArtImp_Art/Bib", "B");
        detail(bundle, "ArtImp_Key_LevGrp", "N");
        detail(bundle, "ArtImp_Key_Korting", "N");
        long id = receive(bundle);

        String body = previewAndApply(id, "libraryCode", "LIB-N").andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptStatus").value("APPLIED"))
                .andReturn().getResponse().getContentAsString();

        assertThat(codes(body, "WARNING")).contains("IDENTITY_GROUP_INHERITANCE_NOT_MIGRATED");
        Map<String, Object> revision = revisionRows(definitionId(supplier(bundle), "VROOAM2")).get(0);
        assertThat(revision.get("identity_profile_kind")).isEqualTo("THREE_PART");
        assertThat(revision.get("identity_supplier_field")).isEqualTo("3");
        assertThat(revision.get("identity_supplier_group_field")).isEqualTo("4");
        assertThat(revision.get("identity_supplier_reference_field")).isEqualTo("1");
        assertThat(revision.get("identity_discount_code_field")).isNull();
    }

    @Test
    void theSameMigratedContentAgainIsDefinitionUnchanged() throws Exception {
        ObjectNode bundle = applicable();
        long first = receive(bundle);
        previewAndApply(first, "libraryCode", "LIB").andExpect(status().isOk());
        long definitionId = definitionId(supplier(bundle), "VROOAM2");

        // Andere contentHash (INHEAD-info), zelfde definitie en zelfde vertaling.
        ((ObjectNode) bundle.get("interface").get("header")).put("info", "tweede export");
        long second = receive(bundle);
        String preview = previewBody(second, "libraryCode", "LIB-OTHER");

        assertThat(blockingCodes(preview)).isEmpty();
        assertThat(codes(preview, "MIGRATED")).contains("DEFINITION_UNCHANGED");
        assertThat(codes(preview, "WARNING")).contains("LINK_DIFFERS_NOT_CHANGED");
        assertThat((String) JsonPath.read(preview, "$.report.planned.definition.revisionAction"))
                .isEqualTo("UNCHANGED");
        assertThat((String) JsonPath.read(preview, "$.report.planned.link.action")).isEqualTo("REUSE");
        assertThat((String) JsonPath.read(preview, "$.report.planned.task.action")).isEqualTo("REUSE");

        apply(second, JsonPath.read(preview, "$.previewFingerprint"), REASON, "libraryCode", "LIB-OTHER")
                .andExpect(status().isOk()).andExpect(jsonPath("$.receiptStatus").value("APPLIED"));

        assertThat(revisionRows(definitionId)).hasSize(1);
        assertThat(count("select count(*) from import_link where code = ? and library_code = 'LIB'",
                "LEGACY-" + interfaceCode(bundle))).isEqualTo(1);
        assertThat(count("select last_applied_receipt_id from legacy_config_origin where origin_dossier = ?",
                dossier(bundle))).isEqualTo(second);
        assertThat(count("select count(*) from legacy_config_revision_origin where receipt_id = ?", second))
                .isZero();
    }

    @Test
    void aChangedDefinitionWithAnOpenDraftIsBlocked() throws Exception {
        ObjectNode bundle = applicable();
        previewAndApply(receive(bundle), "libraryCode", "LIB").andExpect(status().isOk());

        line(bundle, 26).put("column", 17);
        String preview = previewBody(receive(bundle));

        assertThat(blockingCodes(preview)).containsExactly("OPEN_DRAFT_EXISTS");
    }

    @Test
    void aChangedDefinitionBecomesASuccessorDraftAndTheActiveRevisionIsUntouched() throws Exception {
        ObjectNode bundle = applicable();
        previewAndApply(receive(bundle), "libraryCode", "LIB").andExpect(status().isOk());
        long definitionId = definitionId(supplier(bundle), "VROOAM2");
        long first = ((Number) revisionRows(definitionId).get(0).get("id")).longValue();
        // Een drempel is van de mens: telt niet als handmatige wijziging en gaat mee in de kopie.
        setup.updateRevision(first, new SetupService.UpdateRevisionCommand(null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, new BigDecimal("20"),
                null, null, null, null, null, null, null, null, null, null));
        setup.activateRevision(first, ACTOR);

        line(bundle, 9).put("column", 13);   // identiteit wijzigt
        line(bundle, 26).put("column", 17);  // basisprijs
        ((ObjectNode) bundle.get("definition").get("maps").get(0)).put("value", "PCS");
        long changed = receive(bundle);
        String preview = previewBody(changed);

        assertThat(blockingCodes(preview)).isEmpty();
        assertThat(codes(preview, "WARNING")).contains("IDENTITY_CHANGE");
        assertThat((String) JsonPath.read(preview, "$.report.planned.definition.revisionAction"))
                .isEqualTo("SUCCESSOR_DRAFT");
        assertThat(((Number) JsonPath.read(preview, "$.report.planned.definition.basedOnRevision")).longValue())
                .isEqualTo(first);

        apply(changed, JsonPath.read(preview, "$.previewFingerprint"), REASON).andExpect(status().isOk());

        List<Map<String, Object>> revisions = revisionRows(definitionId);
        assertThat(revisions).hasSize(2);
        Map<String, Object> active = revisions.get(0);
        assertThat(active.get("status")).isEqualTo("ACTIVE");
        assertThat(active.get("identity_supplier_field")).isEqualTo("3");
        assertThat(active.get("record_base_price_field")).isEqualTo("7");
        assertThat(valueMap(first, "VKP1_PCT")).containsExactly("1:DOOS>BOX", "2:STK>PCE");
        assertThat(steps(first, "VKP1_PCT")).containsExactly("1:MAP:caseSensitive=true;onMiss=KEEP_SOURCE");
        Map<String, Object> successor = revisions.get(1);
        long successorId = ((Number) successor.get("id")).longValue();
        assertThat(successor.get("status")).isEqualTo("DRAFT");
        assertThat(((Number) successor.get("based_on_revision_id")).longValue()).isEqualTo(first);
        assertThat(successor.get("identity_supplier_field")).isEqualTo("13");
        assertThat(successor.get("record_base_price_field")).isEqualTo("17");
        assertThat(successor.get("change_reason")).isEqualTo(REASON);
        assertThat((BigDecimal) successor.get("price_deviation_percent")).isEqualByComparingTo("20");
        // S8a: de opvolger kreeg eerst de kopie van de rijen, daarna overschreef APPLY de mapping met de nieuwe tabel.
        assertThat(valueMap(successorId, "VKP1_PCT")).containsExactly("1:DOOS>BOX", "2:STK>PCS");
        // S8c: idem voor de ketenstappen (kopie, verwijderd als entiteit, opnieuw geschreven): precies één MAP-stap.
        assertThat(steps(successorId, "VKP1_PCT")).containsExactly("1:MAP:caseSensitive=true;onMiss=KEEP_SOURCE");
        // S8f: de opvolger kreeg eerst de kopie van de filters (met hun groep), daarna schreef APPLY ze opnieuw: de
        // groep volgt de nieuwe leverancierskolom 13, de actieve revisie houdt kolom 3.
        assertThat(filters(first)).contains("2:EXCLUDE:EQUALS:3:9999:COMPARE_AS_EMPTY:false:true:1");
        assertThat(filters(successorId)).containsExactly("1:INCLUDE:EQUALS:9:ACME:EXCLUDE:true:true:-",
                "2:EXCLUDE:EQUALS:13:9999:COMPARE_AS_EMPTY:false:true:1",
                "3:EXCLUDE:EQUALS:4::COMPARE_AS_EMPTY:false:true:1",
                "4:EXCLUDE:EQUALS:5::COMPARE_AS_EMPTY:true:true:1",
                "5:EXCLUDE:EQUALS:1:REF-1:COMPARE_AS_EMPTY:false:true:1");
        assertThat(count("select count(*) from legacy_config_revision_origin ro join import_definition_revision r "
                + "on r.id = ro.revision_id where r.import_definition_id = ?", definitionId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select definition_content_hash from legacy_config_definition_origin "
                + "where import_definition_id = ?", String.class, definitionId))
                .isEqualTo(bundle.get("definitionContentHash").asText());
    }

    @Test
    void manualChangesSinceTheMigrationBlockButAnUnchangedManualCopyDoesNot() throws Exception {
        // (a) De mens wijzigde de gemigreerde DRAFT vóór activatie.
        ObjectNode edited = applicable();
        previewAndApply(receive(edited), "libraryCode", "LIB").andExpect(status().isOk());
        long editedRevision = ((Number) revisionRows(definitionId(supplier(edited), "VROOAM2")).get(0).get("id"))
                .longValue();
        setup.updateRevision(editedRevision, new SetupService.UpdateRevisionCommand(null, null, null, null, null,
                null, null, null, null, null, null, null, null, "6", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null));
        setup.activateRevision(editedRevision, ACTOR);
        line(edited, 26).put("column", 17);
        assertThat(blockingCodes(previewBody(receive(edited)))).containsExactly("MANUAL_CHANGES_SINCE_MIGRATION");

        // (b) Een handmatige opvolger zonder inhoudelijke wijziging blokkeert niet.
        ObjectNode copied = applicable();
        previewAndApply(receive(copied), "libraryCode", "LIB").andExpect(status().isOk());
        long definitionId = definitionId(supplier(copied), "VROOAM2");
        long migrated = ((Number) revisionRows(definitionId).get(0).get("id")).longValue();
        setup.activateRevision(migrated, ACTOR);
        long copy = successors.createSuccessor(migrated, "handmatige kopie", ACTOR).id();
        // S8f: de handmatige opvolger (RevisionCopier) draagt de EN-groep letterlijk mee.
        assertThat(filters(copy)).isEqualTo(filters(migrated)).anyMatch(row -> row.endsWith(":1"));
        setup.activateRevision(copy, ACTOR);
        line(copied, 26).put("column", 17);
        String preview = previewBody(receive(copied));
        assertThat(blockingCodes(preview)).isEmpty();
        assertThat(((Number) JsonPath.read(preview, "$.report.planned.definition.basedOnRevision")).longValue())
                .isEqualTo(copy);
    }

    @Test
    void aManualDefinitionIsNeverAMigrationTargetUnlessAnotherCodeIsChosen() throws Exception {
        ObjectNode bundle = applicable();
        String supplier = supplier(bundle);
        setup.createSourceOrganisation(new SetupService.CreateSourceOrganisationCommand(supplier, "Handmatig",
                SourceOrganisationType.SUPPLIER));
        long manual = setup.createDefinition(new SetupService.CreateDefinitionCommand(supplier, "VROOAM2",
                "Handmatige definitie", DefinitionUsageType.OWN_DEFINITION), ACTOR).id();
        long id = receive(bundle);

        assertThat(blockingCodes(previewBody(id, "libraryCode", "LIB")))
                .containsExactly("DEFINITION_CODE_CONFLICT_MANUAL");

        String body = previewAndApply(id, "libraryCode", "LIB", "definitionCodeOverride", "VROOAM2-L")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(codes(body, "WARNING")).contains("DEFINITION_CODE_OVERRIDDEN");
        assertThat(definitionId(supplier, "VROOAM2-L")).isNotEqualTo(manual);
        assertThat(revisionRows(manual)).isEmpty();
        assertThat(count("select count(*) from legacy_config_definition_origin where import_definition_id = ?",
                manual)).isZero();
    }

    /**
     * S7 vervangt de S2-blokkade {@code SHARED_DEFINITION_NOT_SUPPORTED}: zelfde inhoud → de gedeelde definitie (met
     * dezelfde bibliotheek: scopeconflict, met een andere: toepasbaar); zelfde code met andere inhoud →
     * {@code SHARED_DEFINITION_DIVERGED}. Uitgebreid in {@link LegacyConfigSharedDefinitionTest}.
     */
    @Test
    void aSecondInterfaceWithTheSameDefinitionJoinsTheSharedDefinition() throws Exception {
        ObjectNode first = applicable();
        previewAndApply(receive(first), "libraryCode", "LIB").andExpect(status().isOk());

        ObjectNode sameContent = first.deepCopy();
        interfaceCode(sameContent, interfaceCode(first) + "B");
        long sameContentId = receive(sameContent);
        assertThat(blockingCodes(previewBody(sameContentId, "libraryCode", "LIB")))
                .containsExactly("LINK_SCOPE_CONFLICT_MANUAL");
        String joined = previewBody(sameContentId, "libraryCode", "LIB-B");
        assertThat(blockingCodes(joined)).isEmpty();
        assertThat((String) JsonPath.read(joined, "$.report.planned.definition.action")).isEqualTo("REUSE");

        ObjectNode sameCodeOtherContent = first.deepCopy();
        interfaceCode(sameCodeOtherContent, interfaceCode(first) + "C");
        line(sameCodeOtherContent, 26).put("column", 27);
        assertThat(blockingCodes(previewBody(receive(sameCodeOtherContent), "libraryCode", "LIB-C")))
                .containsExactly("SHARED_DEFINITION_DIVERGED");
        assertThat(previewBody(sameContentId, "libraryCode", "LIB-B")).doesNotContain("SHARED_DEFINITION_NOT_SUPPORTED");
    }

    @Test
    void anExistingLinkCodeOrAnotherOrganisationTypeBlocks() throws Exception {
        ObjectNode bundle = applicable();
        String other = "M" + supplier(bundle);
        setup.createSourceOrganisation(new SetupService.CreateSourceOrganisationCommand(other, "Andere",
                SourceOrganisationType.SUPPLIER));
        long definition = setup.createDefinition(new SetupService.CreateDefinitionCommand(other, "MANUAL",
                "Handmatig", DefinitionUsageType.OWN_DEFINITION), ACTOR).id();
        setup.createLink(new SetupService.CreateLinkCommand(definition, "LEGACY-" + interfaceCode(bundle),
                "Handmatige koppeling", other, "L1", null, null));
        assertThat(blockingCodes(previewBody(receive(bundle), "libraryCode", "LIB")))
                .containsExactly("LINK_CODE_CONFLICT_MANUAL");

        ObjectNode association = applicable();
        setup.createSourceOrganisation(new SetupService.CreateSourceOrganisationCommand(supplier(association),
                "Inkoopvereniging", SourceOrganisationType.PURCHASING_ASSOCIATION));
        assertThat(blockingCodes(previewBody(receive(association), "libraryCode", "LIB")))
                .containsExactly("SOURCE_ORGANISATION_TYPE_CONFLICT");
    }

    @Test
    void aTabSeparatorAndAChosenCharsetAreWritten() throws Exception {
        ObjectNode bundle = applicable();
        detail(bundle, "Separator", "Tab");
        detail(bundle, "FILE_ENCODING", "");
        removeDetail(bundle, "SkipHeaderLines");
        long id = receive(bundle);

        previewAndApply(id, "libraryCode", "LIB", "charsetWhenUndeclared", "windows-1252")
                .andExpect(status().isOk());

        Map<String, Object> revision = revisionRows(definitionId(supplier(bundle), "VROOAM2")).get(0);
        assertThat(revision.get("structure_delimiter")).isEqualTo("\t");
        assertThat(revision.get("structure_charset")).isEqualTo("windows-1252");
        assertThat(revision.get("structure_has_header")).isEqualTo(false);
    }

    @Test
    void anUndeclaredCharsetIsAppliedAsUtf8() throws Exception {
        ObjectNode bundle = applicable();
        detail(bundle, "FILE_ENCODING", "");
        long id = receive(bundle);

        previewAndApply(id, "libraryCode", "LIB").andExpect(status().isOk());

        Map<String, Object> revision = revisionRows(definitionId(supplier(bundle), "VROOAM2")).get(0);
        assertThat(revision.get("structure_charset")).isEqualTo("UTF-8");
    }

    @Test
    void anUnknownReceiptIsNotFoundInTheService() {
        assertThatThrownBy(() -> applyService.apply(Long.MAX_VALUE, new ApplyCommand("x", REASON, null, null,
                null), ACTOR)).hasMessageContaining("not found");
    }

    /**
     * S8a: een PSIMPDEFMAP van 2000 sleutels (ook met {@code ;}, {@code |} en {@code >}, en sleutels die over twee NUMMERs
     * dubbel voorkomen) wordt één MAP-mapping met 2000 vertaalrijen; geen {@code MAP_TOO_LARGE} of
     * {@code TRANSFORM_VALUE_NOT_EXPRESSIBLE} meer. Laagste NUMMER wint, {@code MAP_KEY_AMBIGUOUS} blijft gemeld. Een
     * tweede APPLY van dezelfde inhoud is {@code DEFINITION_UNCHANGED}.
     */
    @Test
    void aMapOfTwoThousandKeysIsMigratedIntoTheValueMapAndASecondApplyIsUnchanged() throws Exception {
        ObjectNode bundle = applicable();
        com.fasterxml.jackson.databind.node.ArrayNode maps = (com.fasterxml.jackson.databind.node.ArrayNode)
                bundle.get("definition").get("maps");
        maps.removeAll();
        for (int i = 1; i <= 2000; i++) {
            String key = i == 1 ? "A;B" : i == 2 ? "C|D" : i == 3 ? "E>F" : "K" + i;
            maps.addObject().put("number", 7).put("key", key).put("value", "V" + i + (i == 1 ? "|;" : ""))
                    .put("reserveAlpha", "");
        }
        // Dezelfde sleutel onder een hogere NUMMER met een andere waarde: legacy neemt de laagste NUMMER.
        maps.addObject().put("number", 8).put("key", "K10").put("value", "ANDERS").put("reserveAlpha", "");
        long id = receive(bundle);

        String preview = previewBody(id, "libraryCode", "LIB-2000");
        assertThat(blockingCodes(preview)).isEmpty();
        assertThat(preview).doesNotContain("MAP_TOO_LARGE").doesNotContain("TRANSFORM_VALUE_NOT_EXPRESSIBLE");
        // S8g (V1 = B): CALC 7 laat de bronwaarde door (onMiss=KEEP_SOURCE); de WARNING MAP_MISS_NO_FALLBACK vervalt.
        assertThat(codes(preview, "WARNING")).contains("MAP_KEY_AMBIGUOUS").doesNotContain("MAP_MISS_NO_FALLBACK");
        assertThat(codes(preview, "MIGRATED")).contains("MAP_TABLE", "MAP_MISS_KEEPS_SOURCE");
        apply(id, JsonPath.read(preview, "$.previewFingerprint"), REASON, "libraryCode", "LIB-2000")
                .andExpect(status().isOk()).andExpect(jsonPath("$.configurationFindings").isEmpty());

        long definitionId = definitionId(supplier(bundle), "VROOAM2");
        long revisionId = ((Number) revisionRows(definitionId).get(0).get("id")).longValue();
        List<String> rows = valueMap(revisionId, "VKP1_PCT");
        assertThat(rows).hasSize(2000);
        assertThat(rows.subList(0, 3)).containsExactly("1:A;B>V1|;", "2:C|D>V2", "3:E>F>V3");
        // Volgorde (NUMMER, KEY) tekstueel: K10 komt na A;B, C|D en E>F; de laagste NUMMER wint.
        assertThat(rows).contains("4:K10>V10").noneMatch(row -> row.endsWith(">ANDERS"));
        assertThat(jdbc.queryForObject("select transform_config from import_field_mapping where "
                + "definition_revision_id = ? and target_field_code = 'VKP1_PCT'", String.class, revisionId))
                .isEqualTo("decimalSeparator=,");
        assertThat(steps(revisionId, "VKP1_PCT")).containsExactly("1:MAP:caseSensitive=true;onMiss=KEEP_SOURCE");

        // Tweemaal dezelfde inhoud: geen nieuwe revisie.
        ((ObjectNode) bundle.get("interface").get("header")).put("info", "tweede export 2000");
        long second = receive(bundle);
        String again = previewBody(second, "libraryCode", "LIB-2000");
        assertThat(codes(again, "MIGRATED")).contains("DEFINITION_UNCHANGED");
        apply(second, JsonPath.read(again, "$.previewFingerprint"), REASON, "libraryCode", "LIB-2000")
                .andExpect(status().isOk());
        assertThat(revisionRows(definitionId)).hasSize(1);
        assertThat(valueMap(revisionId, "VKP1_PCT")).hasSize(2000);
    }

    /** S8a: een PSIMPDEFMAP-sleutel langer dan legacy toelaat (50) wordt nooit afgekapt: de mapping vervalt, gemeld. */
    @Test
    void aMapKeyLongerThanFiftyCharactersIsNeverTruncated() throws Exception {
        ObjectNode bundle = applicable();
        ((ObjectNode) bundle.get("definition").get("maps").get(0)).put("key", "X".repeat(51));
        String preview = previewBody(receive(bundle), "libraryCode", "LIB-LONG");

        assertThat(codes(preview, "WARNING")).contains("MAP_ENTRY_TOO_LONG");
        assertThat(codes(preview, "NOT_MIGRATED")).contains("LEGACY_MAP_UNUSED");
    }

    /**
     * S8b: CALC 15 wordt UPPER en Positie/Lengte wordt SUBSTRING (MID: positie 0 wordt 1, lengte 0 wordt 500); geen
     * {@code LEGACY_CALC_15_NOT_MIGRATED}/{@code LEGACY_SUBSTRING_NOT_MIGRATED} meer. Een tweede APPLY van dezelfde inhoud
     * is {@code DEFINITION_UNCHANGED}.
     */
    @Test
    void calcFifteenAndPositionLengthAreMigratedAndASecondApplyIsUnchanged() throws Exception {
        ObjectNode bundle = applicable();
        line(bundle, 37).put("calculation", "15");
        line(bundle, 33).put("calculation", "").put("position", 3).put("length", 0);
        long id = receive(bundle);

        String preview = previewBody(id, "libraryCode", "LIB-S8B");
        assertThat(blockingCodes(preview)).isEmpty();
        assertThat(preview).doesNotContain("LEGACY_CALC_15_NOT_MIGRATED").doesNotContain("LEGACY_SUBSTRING_NOT_MIGRATED")
                .doesNotContain("TRANSFORM_CHAIN_UNSUPPORTED");
        apply(id, JsonPath.read(preview, "$.previewFingerprint"), REASON, "libraryCode", "LIB-S8B")
                .andExpect(status().isOk());

        long definitionId = definitionId(supplier(bundle), "VROOAM2");
        long revisionId = ((Number) revisionRows(definitionId).get(0).get("id")).longValue();
        // S8c: elke regel met een bewerking wordt een CHAIN; de mapping draagt de notatie, de stap de bewerking.
        assertThat(mappingOf(revisionId, "VKP5_PCT")).isEqualTo("CHAIN|decimalSeparator=,");
        assertThat(steps(revisionId, "VKP5_PCT")).containsExactly("1:UPPER:");
        assertThat(mappingOf(revisionId, "VKP1_PCT")).isEqualTo("CHAIN|decimalSeparator=,");
        assertThat(steps(revisionId, "VKP1_PCT")).containsExactly("1:SUBSTRING:start=3;length=500");

        ((ObjectNode) bundle.get("interface").get("header")).put("info", "tweede export s8b");
        long second = receive(bundle);
        String again = previewBody(second, "libraryCode", "LIB-S8B");
        assertThat(codes(again, "MIGRATED")).contains("DEFINITION_UNCHANGED");
        apply(second, JsonPath.read(again, "$.previewFingerprint"), REASON, "libraryCode", "LIB-S8B")
                .andExpect(status().isOk());
        assertThat(revisionRows(definitionId)).hasSize(1);
    }

    /**
     * S8c: elke combinatie wordt een CHAIN in de legacy-volgorde (Positie/Lengte → SUBSTRING, prefix/suffix → AFFIX,
     * CALC → één waardebewerking); {@code TRANSFORM_CHAIN_UNSUPPORTED} verdwijnt. Prefix en suffix worden getrimd zoals
     * legacy {@code Trim()}. De vertaalrijen van CALC 7 hangen aan de mapping; het sjabloon en de gedeelde definitie
     * dragen dezelfde stappen. Een tweede APPLY van dezelfde inhoud is {@code DEFINITION_UNCHANGED}.
     */
    @Test
    void combinedOperationsBecomeAChainAndASecondApplyIsUnchanged() throws Exception {
        ObjectNode bundle = applicable();
        line(bundle, 37).put("prefix", " X- ").put("position", 2).put("length", 4).put("calculation", "15");
        line(bundle, 33).put("prefix", "P-").put("suffix", "-S");  // CALC 7 (golden) met prefix en suffix
        long id = receive(bundle);

        String preview = previewBody(id, "libraryCode", "LIB-S8C");
        assertThat(blockingCodes(preview)).isEmpty();
        assertThat(preview).doesNotContain("TRANSFORM_CHAIN_UNSUPPORTED").doesNotContain("LEGACY_SUBSTRING_NOT_MIGRATED");
        assertThat((List<String>) JsonPath.read(preview, "$.report.items[?(@.code == 'MAPPING')].target"))
                .contains("VKP5_PCT <- column 9 CHAIN SUBSTRING > AFFIX > UPPER",
                        "VKP1_PCT <- column 8 CHAIN AFFIX > MAP");
        apply(id, JsonPath.read(preview, "$.previewFingerprint"), REASON, "libraryCode", "LIB-S8C")
                .andExpect(status().isOk()).andExpect(jsonPath("$.configurationFindings").isEmpty());

        long definitionId = definitionId(supplier(bundle), "VROOAM2");
        long revisionId = ((Number) revisionRows(definitionId).get(0).get("id")).longValue();
        assertThat(mappingOf(revisionId, "VKP5_PCT")).isEqualTo("CHAIN|decimalSeparator=,");
        assertThat(steps(revisionId, "VKP5_PCT")).containsExactly("1:SUBSTRING:start=2;length=4", "2:AFFIX:prefix=X-",
                "3:UPPER:");
        assertThat(mappingOf(revisionId, "VKP1_PCT")).isEqualTo("CHAIN|decimalSeparator=,");
        assertThat(steps(revisionId, "VKP1_PCT")).containsExactly("1:AFFIX:prefix=P-;suffix=-S",
                "2:MAP:caseSensitive=true;onMiss=KEEP_SOURCE");
        assertThat(valueMap(revisionId, "VKP1_PCT")).containsExactly("1:DOOS>BOX", "2:STK>PCE");
        long templateRevisionId = ((Number) revisionRows(definitionId(supplier(bundle), "VROOAM2-SJ")).get(0)
                .get("id")).longValue();
        assertThat(steps(templateRevisionId, "VKP5_PCT")).isEqualTo(steps(revisionId, "VKP5_PCT"));
        assertThat(steps(templateRevisionId, "VKP1_PCT")).isEqualTo(steps(revisionId, "VKP1_PCT"));

        ((ObjectNode) bundle.get("interface").get("header")).put("info", "tweede export s8c");
        long second = receive(bundle);
        String again = previewBody(second, "libraryCode", "LIB-S8C");
        assertThat(codes(again, "MIGRATED")).contains("DEFINITION_UNCHANGED");
        apply(second, JsonPath.read(again, "$.previewFingerprint"), REASON, "libraryCode", "LIB-S8C")
                .andExpect(status().isOk());
        assertThat(revisionRows(definitionId)).hasSize(1);
        assertThat(steps(revisionId, "VKP5_PCT")).hasSize(3);
    }

    /**
     * S8c: een vaste {@code Waarde} negeert Positie/Lengte, prefix/suffix en CALC zoals legacy (en S6): FIXED_VALUE met
     * {@code LEGACY_IGNORED_WITH_FIXED_VALUE}, nooit meer {@code LEGACY_SUBSTRING_NOT_MIGRATED}.
     */
    @Test
    void aFixedValueIgnoresTheOtherOperationsLikeLegacy() throws Exception {
        ObjectNode bundle = applicable();
        line(bundle, 37).put("value", "12,5").put("position", 2).put("length", 3).put("prefix", "X");
        long id = receive(bundle);

        String preview = previewBody(id, "libraryCode", "LIB-S8CF");
        assertThat(blockingCodes(preview)).isEmpty();
        assertThat(preview).doesNotContain("LEGACY_SUBSTRING_NOT_MIGRATED").doesNotContain("TRANSFORM_CHAIN_UNSUPPORTED");
        assertThat((List<String>) JsonPath.read(preview,
                "$.report.items[?(@.code == 'LEGACY_IGNORED_WITH_FIXED_VALUE')].element")).containsExactly("PSIMPDEF#37");
        apply(id, JsonPath.read(preview, "$.previewFingerprint"), REASON, "libraryCode", "LIB-S8CF")
                .andExpect(status().isOk());

        long revisionId = ((Number) revisionRows(definitionId(supplier(bundle), "VROOAM2")).get(0).get("id"))
                .longValue();
        assertThat(jdbc.queryForObject("select value_kind || '|' || fixed_value || '|' || transform_kind from "
                + "import_field_mapping where definition_revision_id = ? and target_field_code = 'VKP5_PCT'",
                String.class, revisionId)).isEqualTo("FIXED_VALUE|12,5|NONE");
        assertThat(steps(revisionId, "VKP5_PCT")).isEmpty();
    }

    /** S8b: Positie/Lengte op de basisprijs (26) blijft blokkeren. */
    @Test
    void aSubstringOnTheBasePriceStillBlocks() throws Exception {
        ObjectNode bundle = applicable();
        line(bundle, 26).put("position", 2).put("length", 3);
        assertThat(blockingCodes(previewBody(receive(bundle), "libraryCode", "LIB-S8B3")))
                .containsExactly("BASE_PRICE_TRANSFORM_UNSUPPORTED");
    }

    /**
     * S8d: CALC 14 wordt {@code MAP_CONTAINS} met enkel de PSIMPDEFMAP-rijen van de eigen NUMMER, in KEY-volgorde; rijen
     * met lege VALUE worden niet overgenomen (gemeld) en de waarschuwing over de miss staat in het rapport. Een CALC 7
     * in dezelfde definitie gebruikt de vereniging van alle NUMMERs (S8a), dus ook de rijen van de CALC 14-regel.
     * {@code LEGACY_CALC_14_NOT_MIGRATED} verdwijnt. Een tweede APPLY van dezelfde inhoud is {@code DEFINITION_UNCHANGED}.
     */
    @Test
    void calcFourteenIsMigratedWithTheOwnRowsAndASecondApplyIsUnchanged() throws Exception {
        ObjectNode bundle = applicable();
        line(bundle, 37).put("calculation", "14");
        com.fasterxml.jackson.databind.node.ArrayNode maps = (com.fasterxml.jackson.databind.node.ArrayNode)
                bundle.get("definition").get("maps");
        maps.addObject().put("number", 37).put("key", "B-ROOD").put("value", "R").put("reserveAlpha", "");
        maps.addObject().put("number", 37).put("key", "A-GROEN").put("value", "G").put("reserveAlpha", "");
        maps.addObject().put("number", 37).put("key", "C-LEEG").put("value", "").put("reserveAlpha", "");
        long id = receive(bundle);

        String preview = previewBody(id, "libraryCode", "LIB-S8D");
        assertThat(blockingCodes(preview)).isEmpty();
        assertThat(preview).doesNotContain("LEGACY_CALC_14_NOT_MIGRATED");
        assertThat(codes(preview, "WARNING")).contains("MAP_CONTAINS_MISS_NOT_EMPTY");
        assertThat(codes(preview, "NOT_MIGRATED")).contains("MAP_CONTAINS_ROW_NOT_MIGRATED");
        // S8g: de CALC 7 in dezelfde definitie laat door (informatief), CALC 14 blijft verwerpen (WARNING hierboven).
        assertThat(codes(preview, "MIGRATED")).contains("MAP_MISS_KEEPS_SOURCE");
        assertThat((List<String>) JsonPath.read(preview, "$.report.items[?(@.code == 'MAPPING')].target"))
                .contains("VKP5_PCT <- column 9 CHAIN MAP_CONTAINS");
        apply(id, JsonPath.read(preview, "$.previewFingerprint"), REASON, "libraryCode", "LIB-S8D")
                .andExpect(status().isOk()).andExpect(jsonPath("$.configurationFindings").isEmpty());

        long definitionId = definitionId(supplier(bundle), "VROOAM2");
        long revisionId = ((Number) revisionRows(definitionId).get(0).get("id")).longValue();
        assertThat(mappingOf(revisionId, "VKP5_PCT")).isEqualTo("CHAIN|decimalSeparator=,");
        // CALC 14 krijgt nooit onMiss: een miss blijft MAPPING_VALUE_UNKNOWN.
        assertThat(steps(revisionId, "VKP5_PCT")).containsExactly("1:MAP_CONTAINS:");
        assertThat(steps(revisionId, "VKP1_PCT")).containsExactly("1:MAP:caseSensitive=true;onMiss=KEEP_SOURCE");
        assertThat(valueMap(revisionId, "VKP5_PCT")).containsExactly("1:A-GROEN>G", "2:B-ROOD>R");
        // CALC 7: de vereniging van alle NUMMERs, ook de rijen van de CALC 14-regel (legacy r.1009-1054).
        assertThat(valueMap(revisionId, "VKP1_PCT")).containsExactly("1:DOOS>BOX", "2:STK>PCE", "3:A-GROEN>G",
                "4:B-ROOD>R", "5:C-LEEG>");

        ((ObjectNode) bundle.get("interface").get("header")).put("info", "tweede export s8d");
        long second = receive(bundle);
        String again = previewBody(second, "libraryCode", "LIB-S8D");
        assertThat(codes(again, "MIGRATED")).contains("DEFINITION_UNCHANGED");
        apply(second, JsonPath.read(again, "$.previewFingerprint"), REASON, "libraryCode", "LIB-S8D")
                .andExpect(status().isOk());
        assertThat(revisionRows(definitionId)).hasSize(1);
        assertThat(valueMap(revisionId, "VKP5_PCT")).hasSize(2);
    }

    /** S8d: CALC 14 zonder bruikbare rijen voor de eigen NUMMER laat de mapping vervallen ({@code MAP_EMPTY}). */
    @Test
    void calcFourteenWithoutUsableOwnRowsDropsTheMapping() throws Exception {
        ObjectNode bundle = applicable();
        line(bundle, 37).put("calculation", "14");
        // De rijen van NUMMER 7 horen niet bij veld 37; één rij van 37 heeft een lege waarde.
        ((com.fasterxml.jackson.databind.node.ArrayNode) bundle.get("definition").get("maps")).addObject()
                .put("number", 37).put("key", "LEEG").put("value", "").put("reserveAlpha", "");
        String preview = previewBody(receive(bundle), "libraryCode", "LIB-S8D2");

        assertThat(blockingCodes(preview)).isEmpty();
        assertThat(codes(preview, "WARNING")).contains("MAP_EMPTY");
        assertThat(codes(preview, "NOT_MIGRATED")).contains("MAP_CONTAINS_ROW_NOT_MIGRATED");
        assertThat(preview).doesNotContain("MAP_CONTAINS_MISS_NOT_EMPTY");
    }

    /**
     * S8d: CALC {@code '0'} betekent "geen berekening" (legacy {@code Prg_1170} r.732: {@code <>'' AND <>'0'}): de mapping
     * blijft (zonder CALC-stap, eventueel met prefix), er is geen {@code LEGACY_CALC_0_NOT_MIGRATED}, en op een
     * identiteitsregel is CALC {@code '0'} geen transformatie die blokkeert.
     */
    @Test
    void calcZeroMeansNoCalculation() throws Exception {
        ObjectNode bundle = applicable();
        line(bundle, 37).put("calculation", "0").put("prefix", "P-");
        line(bundle, 4).put("calculation", "0");
        line(bundle, 11).put("calculation", "0");
        long id = receive(bundle);

        String preview = previewBody(id, "libraryCode", "LIB-S8D0");
        assertThat(blockingCodes(preview)).isEmpty();
        assertThat(preview).doesNotContain("LEGACY_CALC_0_NOT_MIGRATED").doesNotContain("IDENTITY_TRANSFORM_UNSUPPORTED")
                .doesNotContain("DESCRIPTION_TRANSFORM_NOT_MIGRATED");
        apply(id, JsonPath.read(preview, "$.previewFingerprint"), REASON, "libraryCode", "LIB-S8D0")
                .andExpect(status().isOk());

        long revisionId = ((Number) revisionRows(definitionId(supplier(bundle), "VROOAM2")).get(0).get("id"))
                .longValue();
        assertThat(mappingOf(revisionId, "VKP5_PCT")).isEqualTo("CHAIN|decimalSeparator=,");
        assertThat(steps(revisionId, "VKP5_PCT")).containsExactly("1:AFFIX:prefix=P-");
    }

    /**
     * S8e: de codes 2-6 worden recordfilters (legacy {@code Prg_1170} r.18681-18701); één include (code 1) blijft de
     * S2-include. Codes 3 en 4 melden de padding; {@code INCLUDE_EXCLUDE_CODE_NOT_MIGRATED} verdwijnt voor 2-6. Een
     * tweede APPLY van dezelfde inhoud is {@code DEFINITION_UNCHANGED}.
     */
    @Test
    void theIncludeExcludeCodesTwoToSixAreMigratedAndASecondApplyIsUnchanged() throws Exception {
        ObjectNode bundle = applicable();
        filterLine(line(bundle, 9), "2", "ACME");      // kolom 3
        filterLine(line(bundle, 10), "3", "GR");       // kolom 4
        filterLine(line(bundle, 11), "4", "-X");       // kolom 1
        filterLine(line(bundle, 23), "6", "KC");       // kolom 5
        filterLine(line(bundle, 26), "1", "9,50");     // kolom 7
        line(bundle, 37).put("includeExclude", "0");   // de golden heeft hier al een include (code 1, ACME)
        long id = receive(bundle);

        String preview = previewBody(id, "libraryCode", "LIB-S8E");
        assertThat(blockingCodes(preview)).isEmpty();
        assertThat(preview).doesNotContain("INCLUDE_EXCLUDE_CODE_NOT_MIGRATED")
                .doesNotContain("INCLUDE_FILTER_COMBINATION_UNSUPPORTED");
        assertThat(codes(preview, "WARNING").stream().filter("LEGACY_FILTER_PADDING_UNVERIFIED"::equals).count())
                .isEqualTo(2);
        assertThat((List<String>) JsonPath.read(preview, "$.report.items[?(@.code == 'RECORD_FILTER')].target"))
                .containsExactly("import_record_filter EXCLUDE EQUALS column 3",
                        "import_record_filter EXCLUDE BEGINS_WITH column 4",
                        "import_record_filter EXCLUDE ENDS_WITH column 1",
                        "import_record_filter EXCLUDE CONTAINS column 5",
                        "import_record_filter INCLUDE EQUALS column 7");
        apply(id, JsonPath.read(preview, "$.previewFingerprint"), REASON, "libraryCode", "LIB-S8E")
                .andExpect(status().isOk()).andExpect(jsonPath("$.configurationFindings").isEmpty());

        long definitionId = definitionId(supplier(bundle), "VROOAM2");
        long revisionId = ((Number) revisionRows(definitionId).get(0).get("id")).longValue();
        assertThat(filters(revisionId)).containsExactly(
                "1:EXCLUDE:EQUALS:3:ACME:COMPARE_AS_EMPTY:true:true:-",
                "2:EXCLUDE:BEGINS_WITH:4:GR:COMPARE_AS_EMPTY:true:true:-",
                "3:EXCLUDE:ENDS_WITH:1:-X:COMPARE_AS_EMPTY:true:true:-",
                "4:EXCLUDE:CONTAINS:5:KC:COMPARE_AS_EMPTY:true:true:-",
                "5:INCLUDE:EQUALS:7:9,50:EXCLUDE:true:true:-",
                "6:EXCLUDE:EQUALS:3:9999:COMPARE_AS_EMPTY:false:true:1",
                "7:EXCLUDE:EQUALS:4::COMPARE_AS_EMPTY:false:true:1",
                "8:EXCLUDE:EQUALS:5::COMPARE_AS_EMPTY:true:true:1",
                "9:EXCLUDE:EQUALS:1:REF-1:COMPARE_AS_EMPTY:false:true:1");

        ((ObjectNode) bundle.get("interface").get("header")).put("info", "tweede export s8e");
        long second = receive(bundle);
        String again = previewBody(second, "libraryCode", "LIB-S8E");
        assertThat(codes(again, "MIGRATED")).contains("DEFINITION_UNCHANGED");
        apply(second, JsonPath.read(again, "$.previewFingerprint"), REASON, "libraryCode", "LIB-S8E")
                .andExpect(status().isOk());
        assertThat(revisionRows(definitionId)).hasSize(1);
        assertThat(filters(revisionId)).hasSize(9);
    }

    /**
     * S8e: twee of meer include-regels (codes 1 en 5) zijn een EN: elke include wordt via De Morgan een EXCLUDE
     * {@code NOT_EQUALS}/{@code NOT_CONTAINS} met null-gedrag {@code EXCLUDE}; {@code INCLUDE_FILTER_COMBINATION_UNSUPPORTED}
     * blokkeert niet meer. Een tweede APPLY is {@code DEFINITION_UNCHANGED}.
     */
    @Test
    void twoIncludeLinesBecomeAnAndOfNegatedExcludes() throws Exception {
        ObjectNode bundle = applicable();
        filterLine(line(bundle, 9), "1", "ACME");
        filterLine(line(bundle, 26), "5", "9");
        filterLine(line(bundle, 23), "2", "KC");
        // De golden heeft op veld 37 al een include (code 1, ACME, kolom 9): dat is hier de derde include.
        long id = receive(bundle);

        String preview = previewBody(id, "libraryCode", "LIB-S8E2");
        assertThat(blockingCodes(preview)).isEmpty();
        assertThat(preview).doesNotContain("INCLUDE_FILTER_COMBINATION_UNSUPPORTED");
        apply(id, JsonPath.read(preview, "$.previewFingerprint"), REASON, "libraryCode", "LIB-S8E2")
                .andExpect(status().isOk()).andExpect(jsonPath("$.configurationFindings").isEmpty());

        long definitionId = definitionId(supplier(bundle), "VROOAM2");
        long revisionId = ((Number) revisionRows(definitionId).get(0).get("id")).longValue();
        assertThat(filters(revisionId)).containsExactly(
                "1:EXCLUDE:NOT_EQUALS:3:ACME:EXCLUDE:true:true:-",
                "2:EXCLUDE:EQUALS:5:KC:COMPARE_AS_EMPTY:true:true:-",
                "3:EXCLUDE:NOT_CONTAINS:7:9:EXCLUDE:true:true:-",
                "4:EXCLUDE:NOT_EQUALS:9:ACME:EXCLUDE:true:true:-",
                "5:EXCLUDE:EQUALS:3:9999:COMPARE_AS_EMPTY:false:true:1",
                "6:EXCLUDE:EQUALS:4::COMPARE_AS_EMPTY:false:true:1",
                "7:EXCLUDE:EQUALS:5::COMPARE_AS_EMPTY:true:true:1",
                "8:EXCLUDE:EQUALS:1:REF-1:COMPARE_AS_EMPTY:false:true:1");

        ((ObjectNode) bundle.get("interface").get("header")).put("info", "tweede export s8e2");
        long second = receive(bundle);
        String again = previewBody(second, "libraryCode", "LIB-S8E2");
        assertThat(codes(again, "MIGRATED")).contains("DEFINITION_UNCHANGED");
        apply(second, JsonPath.read(again, "$.previewFingerprint"), REASON, "libraryCode", "LIB-S8E2")
                .andExpect(status().isOk());
        assertThat(revisionRows(definitionId)).hasSize(1);
    }

    /**
     * S8e: Positie/Lengte op een filterregel is niet te migreren (WARNING, geen filter), een filterregel met vaste
     * {@code Waarde} wordt gemeld (legacy vergelijkt dan {@code ''}), een regel zonder kolom blijft
     * {@code INCLUDE_FILTER_WITHOUT_COLUMN} en een onbekende code (7) blijft niet gemigreerd. Het ene include dat wel
     * overblijft blijft een gewone include.
     */
    @Test
    void unsupportedFilterLinesAreReportedAndNeverCreatedSilently() throws Exception {
        ObjectNode bundle = applicable();
        filterLine(line(bundle, 33), "1", "ACME").put("position", 2).put("length", 3);  // substring
        filterLine(line(bundle, 26), "1", "9,50");                                      // blijft
        filterLine(line(bundle, 37), "2", "X").put("value", "12,5");                    // vaste waarde
        ObjectNode withoutColumn = line(bundle, 37).deepCopy();                         // zonder kolom
        withoutColumn.put("number", 40).put("column", 0).put("value", "");
        filterLine(withoutColumn, "5", "Y");
        ((com.fasterxml.jackson.databind.node.ArrayNode) bundle.get("definition").get("lines")).add(withoutColumn);
        filterLine(line(bundle, 23), "7", "KC");                                        // onbekende code
        long id = receive(bundle);

        String preview = previewBody(id, "libraryCode", "LIB-S8E3");
        assertThat(blockingCodes(preview)).isEmpty();
        assertThat(codes(preview, "WARNING")).contains("INCLUDE_EXCLUDE_SUBSTRING_NOT_MIGRATED",
                "INCLUDE_EXCLUDE_WITH_FIXED_VALUE", "INCLUDE_FILTER_WITHOUT_COLUMN", "INCLUDE_EXCLUDE_CODE_NOT_MIGRATED");
        apply(id, JsonPath.read(preview, "$.previewFingerprint"), REASON, "libraryCode", "LIB-S8E3")
                .andExpect(status().isOk());

        long revisionId = ((Number) revisionRows(definitionId(supplier(bundle), "VROOAM2")).get(0).get("id"))
                .longValue();
        assertThat(filters(revisionId)).containsExactly(
                "1:INCLUDE:EQUALS:7:9,50:EXCLUDE:true:true:-",
                "2:EXCLUDE:EQUALS:9:X:COMPARE_AS_EMPTY:true:true:-",
                "3:EXCLUDE:EQUALS:3:9999:COMPARE_AS_EMPTY:false:true:1",
                "4:EXCLUDE:EQUALS:4::COMPARE_AS_EMPTY:false:true:1",
                "5:EXCLUDE:EQUALS:5::COMPARE_AS_EMPTY:true:true:1",
                "6:EXCLUDE:EQUALS:1:REF-1:COMPARE_AS_EMPTY:false:true:1");
    }

    /**
     * S8f (design par. 2f, V2 = A): elke PSIMPEXCL-rij wordt een EN-groep op de bronkolommen, in de vijf legacy-vormen;
     * dode rijen worden gemeld en niet gemigreerd; {@code LEGACY_EXCLUDES_NOT_MIGRATED} verdwijnt. De gemigreerde DRAFT
     * doorstaat de configuratievalidatie en een tweede APPLY is {@code DEFINITION_UNCHANGED}.
     */
    @Test
    void theExcludesBecomeMatchGroupsInTheFiveLegacyFormsAndASecondApplyIsUnchanged() throws Exception {
        ObjectNode bundle = applicable();
        line(bundle, 37).put("includeExclude", "0");
        excludes(bundle,
                "9999", "", "", "",          // 1 enkel leverancier
                "", "GRP-A", "", "",         // 2 enkel groep
                "", "GRP-A", "kc", "",       // 3 groep + korting (kleine letters in de korting zijn geldig)
                "", "", "KC", "REF-2",       // 4 groep ('') + korting + referentie
                "9999", "GRP-A", "", "",     // dood: leverancier zonder referentie, met groep
                "", "grp-a", "", "",         // dood: kleine letters in de groep
                "9999", "", "", "REF-1");    // 5 alle vier
        long id = receive(bundle);

        String preview = previewBody(id, "libraryCode", "LIB-S8F");
        assertThat(blockingCodes(preview)).isEmpty();
        assertThat(preview).doesNotContain("LEGACY_EXCLUDES_NOT_MIGRATED");
        assertThat((List<String>) JsonPath.read(preview,
                "$.report.items[?(@.code == 'LEGACY_EXCLUDE_NEVER_MATCHES')].element"))
                .containsExactly("PSIMPEXCL#5", "PSIMPEXCL#6");
        assertThat((List<String>) JsonPath.read(preview, "$.report.items[?(@.code == 'RECORD_FILTER_GROUP')].target"))
                .containsExactly("import_record_filter group 1: column 3 EQUALS '9999'",
                        "import_record_filter group 2: column 4 EQUALS 'GRP-A'",
                        "import_record_filter group 3: column 4 EQUALS 'GRP-A' AND column 5 EQUALS 'kc'",
                        "import_record_filter group 4: column 4 EQUALS '' AND column 5 EQUALS 'KC' AND column 1 "
                                + "EQUALS 'REF-2'",
                        "import_record_filter group 5: column 3 EQUALS '9999' AND column 4 EQUALS '' AND column 5 "
                                + "EQUALS '' AND column 1 EQUALS 'REF-1'");
        apply(id, JsonPath.read(preview, "$.previewFingerprint"), REASON, "libraryCode", "LIB-S8F")
                .andExpect(status().isOk()).andExpect(jsonPath("$.configurationFindings").isEmpty());

        long definitionId = definitionId(supplier(bundle), "VROOAM2");
        long revisionId = ((Number) revisionRows(definitionId).get(0).get("id")).longValue();
        assertThat(filters(revisionId)).containsExactly(
                "1:EXCLUDE:EQUALS:3:9999:COMPARE_AS_EMPTY:false:true:1",
                "2:EXCLUDE:EQUALS:4:GRP-A:COMPARE_AS_EMPTY:false:true:2",
                "3:EXCLUDE:EQUALS:4:GRP-A:COMPARE_AS_EMPTY:false:true:3",
                "4:EXCLUDE:EQUALS:5:kc:COMPARE_AS_EMPTY:true:true:3",
                "5:EXCLUDE:EQUALS:4::COMPARE_AS_EMPTY:false:true:4",
                "6:EXCLUDE:EQUALS:5:KC:COMPARE_AS_EMPTY:true:true:4",
                "7:EXCLUDE:EQUALS:1:REF-2:COMPARE_AS_EMPTY:false:true:4",
                "8:EXCLUDE:EQUALS:3:9999:COMPARE_AS_EMPTY:false:true:5",
                "9:EXCLUDE:EQUALS:4::COMPARE_AS_EMPTY:false:true:5",
                "10:EXCLUDE:EQUALS:5::COMPARE_AS_EMPTY:true:true:5",
                "11:EXCLUDE:EQUALS:1:REF-1:COMPARE_AS_EMPTY:false:true:5");
        // Het revisiedetail van de setup-API toont de groep (additief veld).
        String detail = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/catalog-import/definitions/{definitionId}/revisions/{id}", definitionId,
                                revisionId)
                        .with(as(USER, Permission.READ)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((List<Integer>) JsonPath.read(detail, "$.filters[*].matchGroup"))
                .containsExactly(1, 2, 3, 3, 4, 4, 4, 5, 5, 5, 5);

        ((ObjectNode) bundle.get("interface").get("header")).put("info", "tweede export s8f");
        long second = receive(bundle);
        String again = previewBody(second, "libraryCode", "LIB-S8F");
        assertThat(codes(again, "MIGRATED")).contains("DEFINITION_UNCHANGED");
        apply(second, JsonPath.read(again, "$.previewFingerprint"), REASON, "libraryCode", "LIB-S8F")
                .andExpect(status().isOk());
        assertThat(revisionRows(definitionId)).hasSize(1);
        assertThat(filters(revisionId)).hasSize(11);
    }

    /** S8f: {@code Skip_Excludes=Y} (INDET): legacy past PSIMPEXCL niet toe, dus geen groep; gemeld, niet stil. */
    @Test
    void skipExcludesYesMigratesNoExclude() throws Exception {
        ObjectNode bundle = applicable();
        detail(bundle, "Skip_Excludes", "Y");
        long id = receive(bundle);

        String preview = previewBody(id, "libraryCode", "LIB-S8F2");
        assertThat(blockingCodes(preview)).isEmpty();
        assertThat(codes(preview, "NOT_MIGRATED")).contains("LEGACY_EXCLUDES_SKIPPED")
                .doesNotContain("INTERFACE_SETTING_NOT_MIGRATED");
        apply(id, JsonPath.read(preview, "$.previewFingerprint"), REASON, "libraryCode", "LIB-S8F2")
                .andExpect(status().isOk());

        long revisionId = ((Number) revisionRows(definitionId(supplier(bundle), "VROOAM2")).get(0).get("id"))
                .longValue();
        assertThat(filters(revisionId)).containsExactly("1:INCLUDE:EQUALS:9:ACME:EXCLUDE:true:true:-");
    }

    /**
     * S8f: een vaste groep (S6) wordt bij de migratie geëvalueerd. Een rij die daardoor elk record uitsluit, blokkeert
     * ({@code LEGACY_EXCLUDE_EXCLUDES_ALL}); een vergelijking die altijd klopt valt weg, een die nooit klopt maakt de rij
     * dood.
     */
    @Test
    void aFixedGroupIsEvaluatedAndARowThatExcludesEverythingBlocks() throws Exception {
        ObjectNode bundle = applicable();
        line(bundle, 10).put("column", 0).put("value", "grp-a");   // legacy zet de vaste groep in hoofdletters
        excludes(bundle, "", "GRP-A", "KC", "", "", "GRP-B", "KC", "");
        String preview = previewBody(receive(bundle), "libraryCode", "LIB-S8F3");
        assertThat(blockingCodes(preview)).isEmpty();
        assertThat((List<String>) JsonPath.read(preview, "$.report.items[?(@.code == 'RECORD_FILTER_GROUP')].target"))
                .containsExactly("import_record_filter group 1: column 5 EQUALS 'KC'");
        assertThat(codes(preview, "NOT_MIGRATED")).contains("LEGACY_EXCLUDE_NEVER_MATCHES");

        excludes(bundle, "", "GRP-A", "", "");
        String blocked = previewBody(receive(bundle), "libraryCode", "LIB-S8F3");
        assertThat(blockingCodes(blocked)).containsExactly("LEGACY_EXCLUDE_EXCLUDES_ALL");
        assertThat((String) JsonPath.read(blocked, "$.report.outcome")).isEqualTo("BLOCKED");
    }

    // --- Hulp -----------------------------------------------------------------------------------------------------

    /** S8b: soort en configuratie van één mapping als {@code SOORT|config}. */
    private String mappingOf(long revisionId, String targetFieldCode) {
        return jdbc.queryForObject("select transform_kind || '|' || coalesce(transform_config, '') from "
                + "import_field_mapping where definition_revision_id = ? and target_field_code = ?", String.class,
                revisionId, targetFieldCode);
    }

    /** S8c: de ketenstappen van één mapping als {@code nummer:soort:config}, op stapnummer. */
    private List<String> steps(long revisionId, String targetFieldCode) {
        return jdbc.queryForList("select s.step_number || ':' || s.transform_kind || ':' || coalesce(s.transform_config, "
                + "'') from import_field_transform_step s join import_field_mapping m on m.id = s.mapping_id "
                + "where m.definition_revision_id = ? and m.target_field_code = ? order by s.step_number",
                String.class, revisionId, targetFieldCode);
    }

    /** S8a: de vertaalrijen van één mapping als {@code volgnummer:bron>doel}, op volgnummer. */
    private List<String> valueMap(long revisionId, String targetFieldCode) {
        return jdbc.queryForList("select v.sequence_number || ':' || v.source_value || '>' || v.target_value "
                + "from import_field_value_map v join import_field_mapping m on m.id = v.mapping_id "
                + "where m.definition_revision_id = ? and m.target_field_code = ? order by v.sequence_number",
                String.class, revisionId, targetFieldCode);
    }

    /** S8e: zet Include/Exclude-code en -gegevens op een regel van de bundel. */
    private static ObjectNode filterLine(ObjectNode line, String code, String data) {
        return line.put("includeExclude", code).put("includeExcludeData", data);
    }

    /**
     * S8e/S8f: de filters van een revisie als {@code volgnr:uitkomst:operator:kolom:waarde:null-gedrag:case:trim:groep}
     * ({@code -} = geen groep).
     */
    private List<String> filters(long revisionId) {
        return jdbc.queryForList("select sequence_number || ':' || outcome || ':' || operator || ':' || "
                + "source_reference || ':' || compare_value || ':' || null_behaviour || ':' || case_sensitive || ':' "
                + "|| trim_before_compare || ':' || coalesce(cast(match_group as varchar), '-') "
                + "from import_record_filter where definition_revision_id = ? order by sequence_number", String.class,
                revisionId);
    }

    /** S8f: vervangt de PSIMPEXCL-rijen van de bundel door deze rijen (leverancier, groep, korting, referentie). */
    private static void excludes(ObjectNode bundle, String... values) {
        com.fasterxml.jackson.databind.node.ArrayNode rows =
                ((ObjectNode) bundle.get("definition")).putArray("excludes");
        for (int i = 0; i < values.length; i += 4) {
            rows.addObject().put("supplier", values[i]).put("supplierGroup", values[i + 1])
                    .put("discountCode", values[i + 2]).put("supplierReference", values[i + 3]);
        }
    }

    private static List<Object> concurrently(int count, Callable<Object> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
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
}
