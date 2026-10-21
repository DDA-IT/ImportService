package be.dda.catalogimport.web;

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
        // KEY) staan in import_field_value_map, transform_config draagt enkel nog de instellingen.
        List<Map<String, Object>> mappings = jdbc.queryForList("select target_field_code, value_kind, "
                + "source_reference, transform_kind, transform_config from import_field_mapping "
                + "where definition_revision_id = ? order by target_field_code", revisionId);
        assertThat(mappings).hasSize(2);
        assertThat(mappings.get(0)).containsEntry("target_field_code", "VKP1_PCT")
                .containsEntry("source_reference", "8").containsEntry("transform_kind", "MAP")
                .containsEntry("transform_config", "caseSensitive=true;decimalSeparator=,");
        assertThat(valueMap(revisionId, "VKP1_PCT")).containsExactly("1:DOOS>BOX", "2:STK>PCE");
        assertThat(jdbc.queryForList("select v.created_by from import_field_value_map v join import_field_mapping m "
                + "on m.id = v.mapping_id where m.definition_revision_id = ?", String.class, revisionId))
                .containsOnly(USER);
        // S7: het sjabloon draagt dezelfde rijen (de gedeelde definitie is er een kopie van).
        assertThat(valueMap(((Number) templateRevisions.get(0).get("id")).longValue(), "VKP1_PCT"))
                .containsExactly("1:DOOS>BOX", "2:STK>PCE");
        assertThat(mappings.get(1)).containsEntry("target_field_code", "VKP5_PCT")
                .containsEntry("source_reference", "9").containsEntry("transform_kind", "NONE")
                .containsEntry("transform_config", "decimalSeparator=,");

        // Include/Exclude 1 op lijn 37 → INCLUDE EQUALS op kolom 9.
        List<Map<String, Object>> filters = jdbc.queryForList("select * from import_record_filter "
                + "where definition_revision_id = ?", revisionId);
        assertThat(filters).hasSize(1);
        assertThat(filters.get(0)).containsEntry("source_reference", "9").containsEntry("operator", "EQUALS")
                .containsEntry("compare_value", "ACME").containsEntry("outcome", "INCLUDE")
                .containsEntry("case_sensitive", true);

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
        assertThat(count("select count(*) from import_record_filter where definition_revision_id = ?", successorId))
                .isEqualTo(1);
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
        assertThat(codes(preview, "WARNING")).contains("MAP_MISS_NO_FALLBACK", "MAP_KEY_AMBIGUOUS");
        assertThat(codes(preview, "MIGRATED")).contains("MAP_TABLE");
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
                .isEqualTo("caseSensitive=true;decimalSeparator=,");

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
        assertThat(mappingOf(revisionId, "VKP5_PCT")).isEqualTo("UPPER|decimalSeparator=,");
        assertThat(mappingOf(revisionId, "VKP1_PCT")).isEqualTo("SUBSTRING|start=3;length=500;decimalSeparator=,");

        ((ObjectNode) bundle.get("interface").get("header")).put("info", "tweede export s8b");
        long second = receive(bundle);
        String again = previewBody(second, "libraryCode", "LIB-S8B");
        assertThat(codes(again, "MIGRATED")).contains("DEFINITION_UNCHANGED");
        apply(second, JsonPath.read(again, "$.previewFingerprint"), REASON, "libraryCode", "LIB-S8B")
                .andExpect(status().isOk());
        assertThat(revisionRows(definitionId)).hasSize(1);
    }

    /** S8b: een substring naast een andere bewerking blijft {@code TRANSFORM_CHAIN_UNSUPPORTED} tot S8c; nooit stil half. */
    @Test
    void aSubstringCombinedWithAnotherOperationStaysUnsupportedUntilTheChain() throws Exception {
        ObjectNode bundle = applicable();
        line(bundle, 37).put("prefix", "X-").put("position", 2).put("length", 4);
        line(bundle, 33).put("calculation", "15").put("position", 1).put("length", 2);
        String preview = previewBody(receive(bundle), "libraryCode", "LIB-S8B2");

        assertThat(blockingCodes(preview)).isEmpty();
        assertThat(codes(preview, "WARNING")).contains("TRANSFORM_CHAIN_UNSUPPORTED");
        assertThat(codes(preview, "MIGRATED")).doesNotContain("MAPPING");
    }

    /** S8b: Positie/Lengte op de basisprijs (26) blijft blokkeren. */
    @Test
    void aSubstringOnTheBasePriceStillBlocks() throws Exception {
        ObjectNode bundle = applicable();
        line(bundle, 26).put("position", 2).put("length", 3);
        assertThat(blockingCodes(previewBody(receive(bundle), "libraryCode", "LIB-S8B3")))
                .containsExactly("BASE_PRICE_TRANSFORM_UNSUPPORTED");
    }

    // --- Hulp -----------------------------------------------------------------------------------------------------

    /** S8b: soort en configuratie van één mapping als {@code SOORT|config}. */
    private String mappingOf(long revisionId, String targetFieldCode) {
        return jdbc.queryForObject("select transform_kind || '|' || coalesce(transform_config, '') from "
                + "import_field_mapping where definition_revision_id = ? and target_field_code = ?", String.class,
                revisionId, targetFieldCode);
    }

    /** S8a: de vertaalrijen van één mapping als {@code volgnummer:bron>doel}, op volgnummer. */
    private List<String> valueMap(long revisionId, String targetFieldCode) {
        return jdbc.queryForList("select v.sequence_number || ':' || v.source_value || '>' || v.target_value "
                + "from import_field_value_map v join import_field_mapping m on m.id = v.mapping_id "
                + "where m.definition_revision_id = ? and m.target_field_code = ? order by v.sequence_number",
                String.class, revisionId, targetFieldCode);
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
