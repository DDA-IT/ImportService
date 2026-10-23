package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;

import be.dda.catalogimport.service.LegacyConfigReport.Item;
import be.dda.catalogimport.service.LegacyConfigTranslator.LineTargetKind;
import be.dda.catalogimport.service.LegacyConfigTranslator.PlannedCreationDefaults;
import be.dda.catalogimport.service.LegacyConfigTranslator.Translation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Slice CD-B3, de vertaler (puur): PSIMPDEF 51 (btw-code) en 45 (eenheid) als geplande aanmaakstandaard van de koppeling;
 * beslissingslog 2026-10-23 "Btw-code en eenheid: aanmaakstandaard per koppeling" en "Aanmaakstandaard per koppeling:
 * uitwerking (CD)". De Prodis-controle en de matrix per koppeling zitten in {@code LegacyConfigApplyService}
 * ({@code LegacyConfigCreationDefaultsTest} in Web).
 *
 * <h2>Business rule → wat hier bewezen wordt</h2>
 * <ul>
 *   <li>Een vaste waarde op 51/45 wordt een geplande aanmaakstandaard (getrimd), {@code MIGRATED} met target
 *       {@code link.defaultVatCode}/{@code link.defaultUnitCode}, plus {@code PRODIS_REFERENCE_NOT_VERIFIED}; nooit meer
 *       {@code LEGACY_FIELD_NOT_MIGRATED}, nooit een mapping of revisieveld (de revisie-inhoud verandert niet).</li>
 *   <li>Kolom, CALC, prefix/suffix of Positie/Lengte naast een vaste waarde: {@code LEGACY_IGNORED_WITH_FIXED_VALUE}.</li>
 *   <li>Niets gepland (WARNING, nooit blokkerend): bronkolom in plaats van een vaste waarde, dubbele regel, lege of
 *       blanco waarde, langer dan 50 tekens (nooit afgekapt; precies 50 mag).</li>
 *   <li>50/59/1 met een vaste waarde: {@code CREATION_DEFAULT_NOT_YET_SUPPORTED} met de waarde; zonder vaste waarde
 *       blijft het {@code LEGACY_FIELD_NOT_MIGRATED}.</li>
 *   <li>{@code targetOfLine}: 51/45 zijn {@code LINK_CREATION_DEFAULT} (additief), andere nummers ongewijzigd.</li>
 * </ul>
 */
class LegacyCreationDefaultsTranslationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // --- Vaste waarde ----------------------------------------------------------------------------------------------

    @Test
    void aFixedVatCodeAndUnitBecomeThePlannedCreationDefaultsTrimmed() {
        Translation translation = translate(line(51, 0, " 3 "), line(45, 0, "ST"));

        assertThat(translation.creationDefaults()).isEqualTo(new PlannedCreationDefaults("3", "ST"));
        assertThat(translation.blocking()).isFalse();
        assertThat(translation.revision()).isNotNull();
        assertThat(elementItems(translation, "PSIMPDEF#51")).extracting(Item::code)
                .containsExactly(LegacyConfigReport.CREATION_DEFAULT_VAT, LegacyConfigTranslator.PRODIS_REFERENCE_NOT_VERIFIED);
        assertThat(elementItems(translation, "PSIMPDEF#51").get(0).status()).isEqualTo(LegacyConfigReport.MIGRATED);
        assertThat(elementItems(translation, "PSIMPDEF#51").get(0).target()).isEqualTo("link.defaultVatCode=3");
        assertThat(elementItems(translation, "PSIMPDEF#51").get(1).status()).isEqualTo(LegacyConfigReport.WARNING);
        assertThat(elementItems(translation, "PSIMPDEF#45")).extracting(Item::code)
                .containsExactly(LegacyConfigReport.CREATION_DEFAULT_UNIT, LegacyConfigTranslator.PRODIS_REFERENCE_NOT_VERIFIED);
        assertThat(elementItems(translation, "PSIMPDEF#45").get(0).target()).isEqualTo("link.defaultUnitCode=ST");
        assertThat(codes(translation)).doesNotContain(LegacyConfigTranslator.LEGACY_FIELD_NOT_MIGRATED);
        // Geen veld per regel: geen mapping, en de revisie is dezelfde als zonder 51/45.
        assertThat(translation.revision().mappings()).isEmpty();
        assertThat(translation.revision()).isEqualTo(translate().revision());
    }

    @Test
    void withoutLinesFiftyOneAndFortyFiveNothingIsPlanned() {
        Translation translation = translate();

        assertThat(translation.creationDefaults().isEmpty()).isTrue();
        assertThat(translation.creationDefaults()).isEqualTo(PlannedCreationDefaults.NONE);
        assertThat(codes(translation)).doesNotContain(LegacyConfigReport.CREATION_DEFAULT_VAT,
                LegacyConfigReport.CREATION_DEFAULT_UNIT);
    }

    @Test
    void aColumnOrTransformationNextToAFixedValueIsIgnoredLikeLegacy() {
        ObjectNode vat = line(51, 7, "6");
        vat.put("calculation", "7");
        ObjectNode unit = line(45, 0, "DS");
        unit.put("prefix", "X").put("position", 2).put("length", 1);

        Translation translation = translate(vat, unit);

        assertThat(translation.creationDefaults()).isEqualTo(new PlannedCreationDefaults("6", "DS"));
        assertThat(elementItems(translation, "PSIMPDEF#51")).extracting(Item::code).containsExactly(
                LegacyConfigTranslator.LEGACY_IGNORED_WITH_FIXED_VALUE, LegacyConfigReport.CREATION_DEFAULT_VAT,
                LegacyConfigTranslator.PRODIS_REFERENCE_NOT_VERIFIED);
        assertThat(elementItems(translation, "PSIMPDEF#51").get(0).status()).isEqualTo(LegacyConfigReport.NOT_MIGRATED);
        assertThat(elementItems(translation, "PSIMPDEF#51").get(0).message()).contains("column 7", "CALC 7");
        assertThat(elementItems(translation, "PSIMPDEF#45").get(0).code())
                .isEqualTo(LegacyConfigTranslator.LEGACY_IGNORED_WITH_FIXED_VALUE);
        assertThat(elementItems(translation, "PSIMPDEF#45").get(0).message()).contains("prefix", "position/length");
    }

    // --- Niets gepland ---------------------------------------------------------------------------------------------

    @Test
    void aSourceColumnInsteadOfAFixedValueIsAWarningAndSetsNothing() {
        Translation translation = translate(line(51, 12, ""), line(45, 0, "ST"));

        assertThat(translation.creationDefaults()).isEqualTo(new PlannedCreationDefaults(null, "ST"));
        assertThat(elementItems(translation, "PSIMPDEF#51")).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(LegacyConfigReport.WARNING);
            assertThat(item.code()).isEqualTo(LegacyConfigReport.CREATION_DEFAULT_FROM_COLUMN_NOT_MIGRATED);
            assertThat(item.target()).isNull();
            assertThat(item.message()).contains("column 12");
        });
        assertThat(translation.blocking()).isFalse();
    }

    @Test
    void anEmptyOrBlankValueWithoutColumnIsAWarningAndSetsNothing() {
        for (String empty : List.of("", "   ")) {
            Translation translation = translate(line(51, 0, empty), line(45, 0, empty));

            assertThat(translation.creationDefaults().isEmpty()).as("'" + empty + "'").isTrue();
            assertThat(elementItems(translation, "PSIMPDEF#51")).extracting(Item::code)
                    .containsExactly(LegacyConfigReport.CREATION_DEFAULT_VALUE_EMPTY);
            assertThat(elementItems(translation, "PSIMPDEF#45")).extracting(Item::code)
                    .containsExactly(LegacyConfigReport.CREATION_DEFAULT_VALUE_EMPTY);
            assertThat(elementItems(translation, "PSIMPDEF#51").get(0).status()).isEqualTo(LegacyConfigReport.WARNING);
            assertThat(translation.blocking()).isFalse();
        }
    }

    @Test
    void aDuplicatedLineIsAWarningNotBlockingAndSetsNothing() {
        Translation translation = translate(line(51, 0, "3"), line(51, 0, "6"), line(45, 0, "ST"));

        assertThat(translation.creationDefaults()).isEqualTo(new PlannedCreationDefaults(null, "ST"));
        assertThat(elementItems(translation, "PSIMPDEF#51")).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(LegacyConfigReport.WARNING);
            assertThat(item.code()).isEqualTo(LegacyConfigReport.CREATION_DEFAULT_DUPLICATED);
            assertThat(item.message()).contains("2 times");
        });
        assertThat(codes(translation)).doesNotContain(LegacyConfigTranslator.LEGACY_FIELD_DUPLICATED);
        assertThat(translation.blocking()).isFalse();
        assertThat(translation.revision()).isNotNull();
    }

    @Test
    void aValueLongerThanFiftyCharactersIsNeverTruncatedAndExactlyFiftyIsAccepted() {
        Translation tooLong = translate(line(51, 0, "V".repeat(51)), line(45, 0, " " + "U".repeat(51) + " "));
        assertThat(tooLong.creationDefaults().isEmpty()).isTrue();
        assertThat(elementItems(tooLong, "PSIMPDEF#51")).extracting(Item::code)
                .containsExactly(LegacyConfigReport.CREATION_DEFAULT_VALUE_TOO_LONG);
        assertThat(elementItems(tooLong, "PSIMPDEF#45")).extracting(Item::code)
                .containsExactly(LegacyConfigReport.CREATION_DEFAULT_VALUE_TOO_LONG);
        assertThat(tooLong.blocking()).isFalse();

        // Grensgeval: 50 tekens (na trimmen) past in import_link.default_*_code.
        Translation fifty = translate(line(51, 0, "V".repeat(50)), line(45, 0, "  " + "U".repeat(50) + "  "));
        assertThat(fifty.creationDefaults()).isEqualTo(new PlannedCreationDefaults("V".repeat(50), "U".repeat(50)));
    }

    // --- 50/59/1 ---------------------------------------------------------------------------------------------------

    @Test
    void accountsAndArticleGroupWithAFixedValueAreNotYetSupportedWithTheValueInTheMessage() {
        Translation translation = translate(line(50, 0, "700000"), line(59, 0, "600000"), line(1, 0, "GRP1"));

        for (int number : List.of(50, 59, 1)) {
            assertThat(elementItems(translation, "PSIMPDEF#" + number)).as("field " + number).singleElement()
                    .satisfies(item -> {
                        assertThat(item.status()).isEqualTo(LegacyConfigReport.NOT_MIGRATED);
                        assertThat(item.code()).isEqualTo(LegacyConfigReport.CREATION_DEFAULT_NOT_YET_SUPPORTED);
                    });
        }
        assertThat(elementItems(translation, "PSIMPDEF#50").get(0).message()).contains("'700000'");
        assertThat(elementItems(translation, "PSIMPDEF#59").get(0).message()).contains("'600000'");
        assertThat(elementItems(translation, "PSIMPDEF#1").get(0).message()).contains("'GRP1'");
        assertThat(translation.creationDefaults().isEmpty()).isTrue();
        assertThat(translation.blocking()).isFalse();
    }

    @Test
    void accountsAndArticleGroupFromAColumnStayNotMigratedAsBefore() {
        Translation translation = translate(line(50, 14, ""), line(1, 15, ""));

        assertThat(elementItems(translation, "PSIMPDEF#50")).extracting(Item::code)
                .containsExactly(LegacyConfigTranslator.LEGACY_FIELD_NOT_MIGRATED);
        assertThat(elementItems(translation, "PSIMPDEF#1")).extracting(Item::code)
                .containsExactly(LegacyConfigTranslator.LEGACY_FIELD_NOT_MIGRATED);
    }

    // --- targetOfLine (B2 legacy-herkomst) --------------------------------------------------------------------------

    @Test
    void linesFiftyOneAndFortyFiveTargetTheCreationDefaultOfTheLink() {
        for (int number : List.of(51, 45)) {
            LegacyConfigTranslator.LineTarget target = LegacyConfigTranslator.targetOfLine(number);
            assertThat(target.kind()).as("field " + number).isEqualTo(LineTargetKind.LINK_CREATION_DEFAULT);
            assertThat(target.targetFieldCode()).isNull();
            assertThat(target.revisionField()).isNull();
        }
        assertThat(LegacyConfigTranslator.targetOfLine(50).kind()).isEqualTo(LineTargetKind.NONE);
        assertThat(LegacyConfigTranslator.targetOfLine(1).kind()).isEqualTo(LineTargetKind.NONE);
        assertThat(LegacyConfigTranslator.targetOfLine(26).kind()).isEqualTo(LineTargetKind.REVISION_FIELD);
        assertThat(LegacyConfigTranslator.targetOfLine(33).kind()).isEqualTo(LineTargetKind.MAPPING);
    }

    // --- Hulp ------------------------------------------------------------------------------------------------------

    private static List<Item> elementItems(Translation translation, String element) {
        return translation.items().stream().filter(item -> item.element().equals(element)).toList();
    }

    private static List<String> codes(Translation translation) {
        return translation.items().stream().map(Item::code).toList();
    }

    /** Een toepasbare minimale bundel (identiteit 9/10/11/23, basisprijs 26) plus de gegeven regels. */
    private static Translation translate(ObjectNode... extraLines) {
        ObjectNode bundle = MAPPER.createObjectNode();
        bundle.put("definitionStatus", "PRESENT").put("definitionContentHash", "cd-b3");
        ObjectNode iface = bundle.putObject("interface");
        iface.put("code", "CDB3");
        iface.putObject("header").put("executeAutomatic", false).put("executeEvery", "").put("executionTime", "");
        iface.putArray("omittedSecretLabels");
        iface.putArray("details");
        detail(bundle, "Supplier", "9999");
        detail(bundle, "ArtImp_BibNr", "12");
        detail(bundle, "Separator", "Semicolon");
        detail(bundle, "FILE_ENCODING", "UTF-8");
        detail(bundle, "SkipHeaderLines", "1");
        detail(bundle, "ArtImp_Key_Lev", "Y");
        detail(bundle, "ArtImp_Key_RefLev", "Y");
        detail(bundle, "ArtImp_Key_LevGrp", "Y");
        detail(bundle, "ArtImp_Key_Korting", "Y");
        ObjectNode definition = bundle.putObject("definition");
        definition.put("code", "CDB3").put("description", "").put("updateFile", "").put("replacementCode", "")
                .put("deleteCode", "");
        ArrayNode lines = definition.putArray("lines");
        lines.add(line(9, 1, ""));
        lines.add(line(10, 2, ""));
        lines.add(line(11, 3, ""));
        lines.add(line(23, 4, ""));
        lines.add(line(26, 5, ""));
        for (ObjectNode extra : extraLines) {
            lines.add(extra);
        }
        definition.putArray("maps");
        definition.putArray("excludes");
        return LegacyConfigTranslator.translate(bundle, null);
    }

    private static ObjectNode line(int number, int column, String value) {
        return MAPPER.createObjectNode().put("number", number).put("field", "F" + number).put("column", column)
                .put("position", 0).put("length", 0).put("includeExclude", "0").put("includeExcludeData", "")
                .put("prefix", "").put("suffix", "").put("value", value).put("fill", "").put("calculation", "")
                .put("calculationValue", "").put("xmlElementPath", "");
    }

    private static void detail(ObjectNode bundle, String label, String value) {
        ArrayNode details = (ArrayNode) bundle.get("interface").get("details");
        details.addObject().put("sequence", details.size() + 1).put("label", label).put("value", value);
    }
}
