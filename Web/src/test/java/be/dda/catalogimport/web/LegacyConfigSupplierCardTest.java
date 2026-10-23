package be.dda.catalogimport.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.service.ActorIdentity;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * De leverancierskaart (CALC 7 op PSIMPDEF 9) als controle, beslissingen 2026-10-23 "Leverancierskaart (CALC 7 op regel
 * 9): één leverancier per bestand" en "Leverancierskaart als controle: uitwerking (SC)". PREVIEW en APPLY over HTTP.
 *
 * <h2>Business rule → wat hier bewezen wordt</h2>
 * <ul>
 *   <li>De identiteitsleverancier is de vaste waarde INDET {@code Supplier} (hoofdletters aan, geen bronkolom); de
 *       kaart wordt de laatste filterrij: REJECT {@code NOT_EQUALS} op de kolom van regel 9.</li>
 *   <li>1 sleutel k: hoofdlettergevoelig (k gelijk aan de leverancier: niet); 0 sleutels (ook zonder kaartrijen): de
 *       leverancier zelf, hoofdletterongevoelig, met een WARNING; de leverancier als sleutel naar een ander doel of
 *       meer dan één sleutel blokkeert; kaartrijen van andere leveranciers staan als NOT_MIGRATED.</li>
 *   <li>De kaart telt als gebruikt: geen {@code MAP_EMPTY}/{@code LEGACY_MAP_UNUSED} voor dit gebruik.</li>
 *   <li>Een enkele include wordt met een controlerij een EXCLUDE {@code NOT_*} (uitsluiten vóór verwerpen).</li>
 *   <li>CALC 7 met prefix/Positie/Lengte op 9 en CALC 7 op 10/11 blijven {@code IDENTITY_TRANSFORM_UNSUPPORTED}.</li>
 *   <li>Idempotent; een gewijzigde kaart is een opvolgende DRAFT zonder {@code IDENTITY_CHANGE}.</li>
 * </ul>
 */
class LegacyConfigSupplierCardTest extends LegacyConfigS2TestBase {

    private static final ActorIdentity ACTOR = new ActorIdentity(USER, "test-sub-" + USER);
    private static final String SOURCE_IS_SUPPLIER = "SUPPLIER_CHECK_SOURCE_IS_SUPPLIER";

    @Test
    void oneKeyBecomesTheFixedSupplierAndOneCaseSensitiveRejectRow() throws Exception {
        ObjectNode bundle = applicable();
        String supplier = supplier(bundle);
        card(bundle, "0042", supplier, "0043", "T-OTHER");

        long id = receive(bundle);
        String preview = previewBody(id, "libraryCode", "LIB-SC1");

        assertThat(blockingCodes(preview)).isEmpty();
        assertThat((String) JsonPath.read(preview, "$.report.outcome")).isEqualTo("APPLICABLE");
        assertThat((List<String>) JsonPath.read(preview, "$.report.items[?(@.element == 'PSIMPDEF#9')].code"))
                .containsExactly("IDENTITY_FIXED_VALUE", "SUPPLIER_CHECK_FILTER", "PRODIS_REFERENCE_NOT_VERIFIED");
        assertThat((List<String>) JsonPath.read(preview, "$.report.items[?(@.code == 'IDENTITY_FIXED_VALUE')].target"))
                .containsExactly("revision.identitySupplierFixedValue=" + supplier);
        assertThat((List<String>) JsonPath.read(preview, "$.report.items[?(@.code == 'IDENTITY_FIXED_VALUE')].message"))
                .anyMatch(message -> message != null && message.contains("INDET:Supplier"));
        assertThat((List<String>) JsonPath.read(preview, "$.report.items[?(@.code == 'SUPPLIER_CHECK_FILTER')].target"))
                .containsExactly("import_record_filter REJECT NOT_EQUALS column 3");
        assertThat((List<String>) JsonPath.read(preview,
                "$.report.items[?(@.code == 'SUPPLIER_MAP_ROWS_OTHER_SUPPLIERS')].status"))
                .containsExactly("NOT_MIGRATED");
        assertThat(codes(preview, "WARNING")).doesNotContain(SOURCE_IS_SUPPLIER);
        assertThat(preview).doesNotContain("MAP_EMPTY").doesNotContain("LEGACY_MAP_UNUSED")
                .doesNotContain("IDENTITY_TRANSFORM_UNSUPPORTED");

        apply(id, JsonPath.read(preview, "$.previewFingerprint"), REASON, "libraryCode", "LIB-SC1")
                .andExpect(status().isOk());

        long definitionId = definitionId(supplier, "VROOAM2");
        Map<String, Object> revision = revisionRows(definitionId).get(0);
        assertThat(revision.get("identity_supplier_field")).isNull();
        assertThat(revision.get("identity_supplier_fixed_value")).isEqualTo(supplier);
        assertThat(revision.get("identity_uppercase")).isEqualTo(true);
        long revisionId = ((Number) revision.get("id")).longValue();
        // De include van veld 37 wordt (met een controlerij) de negatie; de golden PSIMPEXCL-rij heeft leverancier 9999
        // en raakt de vaste leverancier nooit; de controlerij is de laatste rij.
        assertThat(filters(revisionId)).containsExactly("1:EXCLUDE:NOT_EQUALS:9:ACME:EXCLUDE:true:true:-",
                "2:REJECT:NOT_EQUALS:3:0042:REJECT:true:true:-");
    }

    @Test
    void aKeyEqualToTheSupplierIsCaseInsensitive() throws Exception {
        ObjectNode bundle = applicable();
        String supplier = supplier(bundle);
        card(bundle, supplier, supplier);

        long id = receive(bundle);
        String preview = previewBody(id, "libraryCode", "LIB-SC2");
        assertThat(blockingCodes(preview)).isEmpty();
        assertThat(codes(preview, "WARNING")).doesNotContain(SOURCE_IS_SUPPLIER);
        apply(id, JsonPath.read(preview, "$.previewFingerprint"), REASON, "libraryCode", "LIB-SC2")
                .andExpect(status().isOk());

        assertThat(lastFilters(supplier)).endsWith("2:REJECT:NOT_EQUALS:3:" + supplier + ":REJECT:false:true:-");
    }

    @Test
    void noKeyForTheSupplierMeansTheColumnMustBeTheSupplierItselfWithAWarning() throws Exception {
        ObjectNode bundle = applicable();
        String supplier = supplier(bundle);
        card(bundle, "0043", "T-OTHER");

        long id = receive(bundle);
        String preview = previewBody(id, "libraryCode", "LIB-SC3");
        assertThat(blockingCodes(preview)).isEmpty();
        assertThat((List<String>) JsonPath.read(preview, "$.report.items[?(@.code == '" + SOURCE_IS_SUPPLIER
                + "')].status")).containsExactly("WARNING");
        assertThat((List<String>) JsonPath.read(preview, "$.report.items[?(@.code == '" + SOURCE_IS_SUPPLIER
                + "')].message")).singleElement().asString().contains("column 3");
        apply(id, JsonPath.read(preview, "$.previewFingerprint"), REASON, "libraryCode", "LIB-SC3")
                .andExpect(status().isOk());

        assertThat(lastFilters(supplier)).endsWith("2:REJECT:NOT_EQUALS:3:" + supplier + ":REJECT:false:true:-");
    }

    @Test
    void aMapWithoutRowsIsTreatedAsNoKeyAndNeverRaisesMapEmpty() throws Exception {
        ObjectNode bundle = applicable();
        String supplier = supplier(bundle);
        line(bundle, 9).put("calculation", "7");
        line(bundle, 33).put("calculation", "");   // anders vraagt veld 33 de (lege) tabel
        ((ObjectNode) bundle.get("definition")).putArray("maps");

        long id = receive(bundle);
        String preview = previewBody(id, "libraryCode", "LIB-SC4");
        assertThat(blockingCodes(preview)).isEmpty();
        assertThat(codes(preview, "WARNING")).contains(SOURCE_IS_SUPPLIER).doesNotContain("MAP_EMPTY");
        assertThat(preview).doesNotContain("MAP_EMPTY").doesNotContain("LEGACY_MAP_UNUSED")
                .doesNotContain("SUPPLIER_MAP_ROWS_OTHER_SUPPLIERS");
        apply(id, JsonPath.read(preview, "$.previewFingerprint"), REASON, "libraryCode", "LIB-SC4")
                .andExpect(status().isOk());

        assertThat(lastFilters(supplier)).endsWith("2:REJECT:NOT_EQUALS:3:" + supplier + ":REJECT:false:true:-");
    }

    @Test
    void aMapOnlyUsedByTheCardIsNotReportedAsUnused() throws Exception {
        ObjectNode bundle = applicable();
        card(bundle, "0042", supplier(bundle));
        line(bundle, 33).put("calculation", "");   // geen mapping gebruikt de tabel meer

        String preview = previewBody(receive(bundle), "libraryCode", "LIB-SC5");

        assertThat(blockingCodes(preview)).isEmpty();
        assertThat(preview).doesNotContain("LEGACY_MAP_UNUSED").doesNotContain("MAP_EMPTY");
    }

    @Test
    void theSupplierAsAKeyForAnotherTargetOrSeveralKeysBlock() throws Exception {
        ObjectNode foreign = applicable();
        card(foreign, supplier(foreign), "T-OTHER", "0042", supplier(foreign));
        String foreignPreview = previewBody(receive(foreign), "libraryCode", "LIB-SC6");
        assertThat(blockingCodes(foreignPreview)).containsExactly("SUPPLIER_CHECK_SUPPLIER_IS_FOREIGN_KEY");
        assertThat((String) JsonPath.read(foreignPreview, "$.report.outcome")).isEqualTo("BLOCKED");

        ObjectNode several = applicable();
        card(several, "0042", supplier(several), "0043", supplier(several));
        String severalPreview = previewBody(receive(several), "libraryCode", "LIB-SC6");
        assertThat(blockingCodes(severalPreview)).containsExactly("SUPPLIER_CHECK_MULTIPLE_KEYS");
    }

    @Test
    void anEmptySupplierKeepsTheExistingBlock() throws Exception {
        ObjectNode bundle = applicable();
        card(bundle, "0042", supplier(bundle));
        detail(bundle, "Supplier", "");

        String preview = previewBody(receive(bundle), "libraryCode", "LIB-SC7");

        assertThat(blockingCodes(preview)).contains("SUPPLIER_NOT_DECLARED")
                .doesNotContain("SUPPLIER_CHECK_SUPPLIER_IS_FOREIGN_KEY", "SUPPLIER_CHECK_MULTIPLE_KEYS");
        assertThat((String) JsonPath.read(preview, "$.report.outcome")).isEqualTo("BLOCKED");
        assertThat(preview).doesNotContain("SUPPLIER_CHECK_FILTER");
    }

    @Test
    void otherCalcSevenCombinationsStillBlock() throws Exception {
        ObjectNode prefix = applicable();
        line(prefix, 9).put("calculation", "7").put("prefix", "X");
        assertThat(blockingCodes(previewBody(receive(prefix), "libraryCode", "LIB-SC8")))
                .containsExactly("IDENTITY_TRANSFORM_UNSUPPORTED");

        ObjectNode substring = applicable();
        line(substring, 9).put("calculation", "7").put("position", 2).put("length", 3);
        assertThat(blockingCodes(previewBody(receive(substring), "libraryCode", "LIB-SC8")))
                .containsExactly("IDENTITY_TRANSFORM_UNSUPPORTED");

        ObjectNode group = applicable();
        line(group, 10).put("calculation", "7");
        assertThat(blockingCodes(previewBody(receive(group), "libraryCode", "LIB-SC8")))
                .containsExactly("IDENTITY_TRANSFORM_UNSUPPORTED");

        ObjectNode reference = applicable();
        line(reference, 11).put("calculation", "7");
        assertThat(blockingCodes(previewBody(receive(reference), "libraryCode", "LIB-SC8")))
                .containsExactly("IDENTITY_TRANSFORM_UNSUPPORTED");

        ObjectNode withoutColumn = applicable();
        line(withoutColumn, 9).put("calculation", "7").put("column", 0);
        assertThat(blockingCodes(previewBody(receive(withoutColumn), "libraryCode", "LIB-SC8")))
                .containsExactly("IDENTITY_TRANSFORM_UNSUPPORTED");
    }

    @Test
    void aCheckRowAlsoTurnsASingleContainsIncludeIntoANegatedExclude() throws Exception {
        ObjectNode bundle = applicable();
        String supplier = supplier(bundle);
        card(bundle, "0042", supplier);
        line(bundle, 37).put("includeExclude", "5").put("includeExcludeData", "AC");

        long id = receive(bundle);
        previewAndApply(id, "libraryCode", "LIB-SC9").andExpect(status().isOk());

        assertThat(lastFilters(supplier)).startsWith("1:EXCLUDE:NOT_CONTAINS:9:AC:EXCLUDE:true:true:-")
                .endsWith("2:REJECT:NOT_EQUALS:3:0042:REJECT:true:true:-");

        // Zonder controlerij blijft een enkele include een gewone include.
        ObjectNode plain = applicable();
        previewAndApply(receive(plain), "libraryCode", "LIB-SC9B").andExpect(status().isOk());
        assertThat(lastFilters(supplier(plain))).startsWith("1:INCLUDE:EQUALS:9:ACME:EXCLUDE:true:true:-");
    }

    @Test
    void aSecondApplyIsDefinitionUnchangedAndAChangedMapIsASuccessorWithoutIdentityChange() throws Exception {
        ObjectNode bundle = applicable();
        String supplier = supplier(bundle);
        card(bundle, "0042", supplier);
        previewAndApply(receive(bundle), "libraryCode", "LIB-SC10").andExpect(status().isOk());
        long definitionId = definitionId(supplier, "VROOAM2");
        long first = ((Number) revisionRows(definitionId).get(0).get("id")).longValue();

        // Zelfde inhoud, andere export: niets nieuws.
        ((ObjectNode) bundle.get("interface").get("header")).put("info", "tweede export sc");
        long second = receive(bundle);
        String again = previewBody(second, "libraryCode", "LIB-SC10");
        assertThat(blockingCodes(again)).isEmpty();
        assertThat(codes(again, "MIGRATED")).contains("DEFINITION_UNCHANGED");
        apply(second, JsonPath.read(again, "$.previewFingerprint"), REASON, "libraryCode", "LIB-SC10")
                .andExpect(status().isOk());
        assertThat(revisionRows(definitionId)).hasSize(1);

        // Een gewijzigde kaart (andere sleutel) na activatie: opvolgende DRAFT; de kaart is geen identiteit.
        setup.activateRevision(first, ACTOR);
        ArrayNode maps = (ArrayNode) bundle.get("definition").get("maps");
        for (var row : maps) {
            if (row.get("key").asText().equals("0042")) {
                ((ObjectNode) row).put("key", "0099");
            }
        }
        long changed = receive(bundle);
        String preview = previewBody(changed, "libraryCode", "LIB-SC10");
        assertThat(blockingCodes(preview)).isEmpty();
        assertThat(codes(preview, "WARNING")).doesNotContain("IDENTITY_CHANGE");
        assertThat((String) JsonPath.read(preview, "$.report.planned.definition.revisionAction"))
                .isEqualTo("SUCCESSOR_DRAFT");
        apply(changed, JsonPath.read(preview, "$.previewFingerprint"), REASON, "libraryCode", "LIB-SC10")
                .andExpect(status().isOk());

        List<Map<String, Object>> revisions = revisionRows(definitionId);
        assertThat(revisions).hasSize(2);
        assertThat(revisions.get(1).get("status")).isEqualTo("DRAFT");
        assertThat(revisions.get(1).get("identity_supplier_fixed_value")).isEqualTo(supplier);
        assertThat(filters(first)).endsWith("2:REJECT:NOT_EQUALS:3:0042:REJECT:true:true:-");
        assertThat(filters(((Number) revisions.get(1).get("id")).longValue()))
                .endsWith("2:REJECT:NOT_EQUALS:3:0099:REJECT:true:true:-");
    }

    // --- Hulp -----------------------------------------------------------------------------------------------------

    /** Zet CALC 7 op regel 9 en voegt kaartrijen (NUMMER 0) toe: paren sleutel/waarde. */
    private static void card(ObjectNode bundle, String... keyValuePairs) {
        line(bundle, 9).put("calculation", "7");
        ArrayNode maps = (ArrayNode) bundle.get("definition").get("maps");
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            maps.addObject().put("number", 0).put("key", keyValuePairs[i]).put("value", keyValuePairs[i + 1])
                    .put("reserveAlpha", "");
        }
    }

    private List<String> lastFilters(String supplier) {
        long definitionId = definitionId(supplier, "VROOAM2");
        List<Map<String, Object>> revisions = revisionRows(definitionId);
        return filters(((Number) revisions.get(revisions.size() - 1).get("id")).longValue());
    }

    private List<String> filters(long revisionId) {
        return jdbc.queryForList("select sequence_number || ':' || outcome || ':' || operator || ':' || "
                + "source_reference || ':' || compare_value || ':' || null_behaviour || ':' || case_sensitive || ':' "
                + "|| trim_before_compare || ':' || coalesce(cast(match_group as varchar), '-') "
                + "from import_record_filter where definition_revision_id = ? order by sequence_number", String.class,
                revisionId);
    }
}
