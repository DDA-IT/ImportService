package be.dda.catalogimport.web;

import static be.dda.catalogimport.web.LegacyConfigCreationDefaultsTest.elementCodes;
import static be.dda.catalogimport.web.LegacyConfigCreationDefaultsTest.withDefaults;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Slice CD-B3 zonder Prodis-leesbron ({@link LegacyConfigS2TestBase}: {@code catalogimport.prodis-db.*} leeg). Zoals S5/S6:
 * de aanmaakstandaard wordt toch gezet, met {@code PRODIS_REFERENCE_NOT_VERIFIED} per code; de fingerprint is gewoon
 * (geen "unverified": de functie staat uit, ze is niet onbereikbaar).
 */
class LegacyConfigCreationDefaultsNotConfiguredTest extends LegacyConfigS2TestBase {

    @Test
    void withoutProdisTheDefaultsAreSetWithANotVerifiedWarningPerCode() throws Exception {
        ObjectNode bundle = withDefaults(applicable(), "3", "ST");
        long id = receive(bundle);

        String preview = previewBody(id, "libraryCode", "LIB");
        assertThat(blockingCodes(preview)).isEmpty();
        assertThat((String) JsonPath.read(preview, "$.report.prodisReference")).isEqualTo("NOT_CONFIGURED");
        assertThat((String) JsonPath.read(preview, "$.previewFingerprint")).matches("[0-9a-f]{64}");
        assertThat(elementCodes(preview, "PSIMPDEF#51")).containsExactly("CREATION_DEFAULT_VAT",
                "PRODIS_REFERENCE_NOT_VERIFIED");
        assertThat(elementCodes(preview, "PSIMPDEF#45")).containsExactly("CREATION_DEFAULT_UNIT",
                "PRODIS_REFERENCE_NOT_VERIFIED");
        assertThat((List<String>) JsonPath.read(preview,
                "$.report.items[?(@.element == 'PSIMPDEF#51' && @.code == 'PRODIS_REFERENCE_NOT_VERIFIED')].message"))
                .singleElement().asString().contains("not configured");

        apply(id, JsonPath.read(preview, "$.previewFingerprint"), REASON, "libraryCode", "LIB")
                .andExpect(status().isOk());
        Map<String, Object> link = jdbc.queryForMap("select default_vat_code, default_unit_code, "
                + "creation_defaults_origin, creation_defaults_updated_by from import_link where code = ?",
                "LEGACY-" + interfaceCode(bundle));
        assertThat(link.get("default_vat_code")).isEqualTo("3");
        assertThat(link.get("default_unit_code")).isEqualTo("ST");
        assertThat(link.get("creation_defaults_origin")).isEqualTo("LEGACY");
        assertThat(link.get("creation_defaults_updated_by")).isEqualTo(USER);
    }

    /** 50/59/1 met een vaste waarde: enkel een melding (V3); 51 uit een kolom zet niets. Via de echte PREVIEW. */
    @Test
    void thePreviewShowsTheNotYetSupportedLinesAndAColumnSetsNothing() throws Exception {
        ObjectNode bundle = withDefaults(applicable(), null, "ST");
        ArrayNode lines = (ArrayNode) bundle.get("definition").get("lines");
        ObjectNode fromColumn = ((ObjectNode) lines.get(0)).deepCopy();
        fromColumn.put("number", 51).put("field", "Btw-code").put("column", 12).put("value", "").put("prefix", "")
                .put("suffix", "").put("calculation", "").put("position", 0).put("length", 0)
                .put("includeExclude", "0").put("includeExcludeData", "");
        lines.add(fromColumn);
        ObjectNode account = fromColumn.deepCopy();
        account.put("number", 50).put("field", "Rekening").put("column", 0).put("value", "700000");
        lines.add(account);
        long id = receive(bundle);

        String preview = previewBody(id, "libraryCode", "LIB");
        assertThat(blockingCodes(preview)).isEmpty();
        assertThat(elementCodes(preview, "PSIMPDEF#51")).containsExactly("CREATION_DEFAULT_FROM_COLUMN_NOT_MIGRATED");
        assertThat(elementCodes(preview, "PSIMPDEF#50")).containsExactly("CREATION_DEFAULT_NOT_YET_SUPPORTED");
        assertThat(preview).doesNotContain("LEGACY_FIELD_NOT_MIGRATED");

        apply(id, JsonPath.read(preview, "$.previewFingerprint"), REASON, "libraryCode", "LIB")
                .andExpect(status().isOk());
        Map<String, Object> link = jdbc.queryForMap("select default_vat_code, default_unit_code from import_link "
                + "where code = ?", "LEGACY-" + interfaceCode(bundle));
        assertThat(link.get("default_vat_code")).isNull();
        assertThat(link.get("default_unit_code")).isEqualTo("ST");
    }
}
