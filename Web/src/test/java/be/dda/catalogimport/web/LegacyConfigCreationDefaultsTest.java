package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Slice CD-B3: de aanmaakstandaard van de koppeling (btw-code PSIMPDEF 51, eenheid 45) bij PREVIEW en APPLY, met een
 * geconfigureerde Prodis-leesbron (H2, {@link LegacyConfigProdisTestBase}). Beslissingslog 2026-10-23 "Btw-code en
 * eenheid: aanmaakstandaard per koppeling", "Aanmaakstandaard per koppeling: uitwerking (CD)" en "Vlag 'standaarden
 * overschrijven' op de importdefinitie". Zonder Prodis-bron: {@link LegacyConfigCreationDefaultsNotConfiguredTest}; de
 * vertaler zelf: {@code LegacyCreationDefaultsTranslationTest} (Service).
 *
 * <h2>Business rule → wat hier bewezen wordt</h2>
 * <ul>
 *   <li>Nieuwe koppeling (nieuwe interface, laatkomer op een gedeelde definitie): de geverifieerde waarden, herkomst
 *       LEGACY, de actor van APPLY als ondertekenaar.</li>
 *   <li>Bestaande koppeling, per veld: leeg → invullen; gelijk → niets (geen schrijfactie, geen audit); anders + vlag uit
 *       → {@code CREATION_DEFAULT_DIFFERS_NOT_CHANGED} met beide waarden; anders + vlag aan →
 *       {@code CREATION_DEFAULT_OVERWRITTEN} (vorige herkomst LEGACY) of {@code CREATION_DEFAULT_MANUAL_OVERWRITTEN}
 *       (vorige herkomst MANUAL). Blijft een manuele waarde staan, dan blijft de herkomst MANUAL (aanname CD-B3).</li>
 *   <li>G2a: het verleggen naar een eigen kopie houdt de standaard van de koppeling; de vlag is die van de gedeelde
 *       definitie (die de kopie erft).</li>
 *   <li>V2: een code die niet (actief) in Prodis staat → WARNING en niet gezet; dubbel in Prodis → gezet met
 *       waarschuwing; Prodis-lijsten onbereikbaar → gezet, preview "unverified".</li>
 *   <li>De gelezen Prodis-rijen, de huidige standaard van de koppeling en de vlag zitten in de fingerprint: een wijziging
 *       in Prodis, een PUT van de mens of een gewijzigde vlag tussen PREVIEW en APPLY → 409 {@code LEGACY_PREVIEW_STALE},
 *       niets geschreven.</li>
 *   <li>Een tweede APPLY van dezelfde ontvangst is 409; een nieuwe ontvangst met dezelfde waarden schrijft niets.</li>
 * </ul>
 */
class LegacyConfigCreationDefaultsTest extends LegacyConfigProdisTestBase {

    private static final String SETUP = "/api/catalog-import/setup";
    private static final String VAT = "PSIMPDEF#51";
    private static final String UNIT = "PSIMPDEF#45";
    private static final String DETACH = "detachFromShared";
    /** De legacy-code van de golden fixture (en dus van {@link #applicable()}). */
    private static final String DEFINITION_CODE = "VROOAM2";

    // --- Nieuwe koppeling ------------------------------------------------------------------------------------------

    @Test
    void aNewInterfaceGetsTheVerifiedDefaultsWithOriginLegacyAndTheApplyActor() throws Exception {
        Fixture fixture = cdFixture("CDNEW");
        Codes codes = codes();
        withDefaults(fixture.bundle(), codes.vat(), codes.unit());
        long id = receive(fixture.bundle());

        String preview = previewBody(id);
        assertThat(blockingCodes(preview)).isEmpty();
        assertThat((String) JsonPath.read(preview, "$.report.prodisReference")).isEqualTo("VERIFIED");
        assertThat(elementCodes(preview, VAT)).containsExactly("CREATION_DEFAULT_VAT", "PRODIS_VAT_CODE");
        assertThat(elementCodes(preview, UNIT)).containsExactly("CREATION_DEFAULT_UNIT", "PRODIS_UNIT");
        assertThat(elementTargets(preview, VAT)).containsExactly("link.defaultVatCode=" + codes.vat(),
                "prodis.vat_code.code=" + codes.vat());
        assertThat(codes(preview, "WARNING")).doesNotContain("PRODIS_REFERENCE_NOT_VERIFIED");
        assertThat(preview).doesNotContain("LEGACY_FIELD_NOT_MIGRATED");

        apply(id, JsonPath.read(preview, "$.previewFingerprint"), REASON).andExpect(status().isOk());

        Map<String, Object> link = linkRow(fixture.bundle());
        assertThat(link.get("default_vat_code")).isEqualTo(codes.vat());
        assertThat(link.get("default_unit_code")).isEqualTo(codes.unit());
        assertThat(link.get("creation_defaults_origin")).isEqualTo("LEGACY");
        assertThat(link.get("creation_defaults_updated_by")).isEqualTo(USER);
        assertThat(link.get("creation_defaults_updated_by_subject")).isEqualTo("test-sub-" + USER);
        assertThat(link.get("creation_defaults_updated_at")).isNotNull();
        // De vlag op de nieuwe definitie staat uit: de migratie zet ze nooit (beslissing "Vlag ...").
        assertThat(jdbc.queryForObject("select overwrite_creation_defaults from import_definition where id = ?",
                Boolean.class, definitionId(fixture.supplier(), DEFINITION_CODE))).isFalse();
    }

    @Test
    void aLateJoinerOfASharedDefinitionGetsTheDefaultsOnItsOwnNewLink() throws Exception {
        Fixture fixture = cdFixture("CDLATE");
        catalog(fixture, "CDLATEB", LEGACY_LIBRARY, "CAT B", "ACTIVE");
        Codes codes = codes();
        ObjectNode a = withDefaults(fixture.bundle(), codes.vat(), codes.unit());
        ObjectNode b = a.deepCopy();
        interfaceCode(b, interfaceCode(a) + "B");
        previewAndApply(receive(a), "libraryCode", "CDLATE").andExpect(status().isOk());

        long receiptB = receive(b);
        String previewB = previewBody(receiptB, "libraryCode", "CDLATEB");
        assertThat(blockingCodes(previewB)).isEmpty();
        assertThat(codes(previewB, "MIGRATED")).contains("SHARED_DEFINITION", "CREATION_DEFAULT_VAT",
                "CREATION_DEFAULT_UNIT");
        apply(receiptB, JsonPath.read(previewB, "$.previewFingerprint"), REASON, "libraryCode", "CDLATEB")
                .andExpect(status().isOk());

        for (ObjectNode bundle : List.of(a, b)) {
            Map<String, Object> link = linkRow(bundle);
            assertThat(link.get("default_vat_code")).as(interfaceCode(bundle)).isEqualTo(codes.vat());
            assertThat(link.get("default_unit_code")).as(interfaceCode(bundle)).isEqualTo(codes.unit());
            assertThat(link.get("creation_defaults_origin")).as(interfaceCode(bundle)).isEqualTo("LEGACY");
        }
        assertThat(count("select count(distinct import_definition_id) from import_link where code in (?, ?)",
                "LEGACY-" + interfaceCode(a), "LEGACY-" + interfaceCode(b))).isEqualTo(1);
    }

    // --- Bestaande koppeling: de matrix per veld -------------------------------------------------------------------

    @Test
    void aKnownLinkWithoutDefaultsIsFilledFromLegacy() throws Exception {
        Fixture fixture = cdFixture("CDFILL");
        previewAndApply(receive(fixture.bundle())).andExpect(status().isOk());
        assertThat(linkRow(fixture.bundle()).get("default_vat_code")).isNull();
        assertThat(linkRow(fixture.bundle()).get("creation_defaults_origin")).isNull();

        Codes codes = codes();
        withDefaults(fixture.bundle(), codes.vat(), codes.unit());
        long second = receive(fixture.bundle());
        String preview = previewBody(second);
        assertThat(blockingCodes(preview)).isEmpty();
        // 51/45 zitten niet in de revisie-inhoud: geen nieuwe revisie.
        assertThat((String) JsonPath.read(preview, "$.report.planned.definition.revisionAction"))
                .isEqualTo("UNCHANGED");
        assertThat(elementCodes(preview, VAT)).containsExactly("CREATION_DEFAULT_VAT", "PRODIS_VAT_CODE");
        assertThat(elementMessages(preview, VAT).get(0)).contains("no default VAT code yet");
        assertThat(elementCodes(preview, UNIT)).containsExactly("CREATION_DEFAULT_UNIT", "PRODIS_UNIT");

        apply(second, JsonPath.read(preview, "$.previewFingerprint"), REASON).andExpect(status().isOk());
        Map<String, Object> link = linkRow(fixture.bundle());
        assertThat(link.get("default_vat_code")).isEqualTo(codes.vat());
        assertThat(link.get("default_unit_code")).isEqualTo(codes.unit());
        assertThat(link.get("creation_defaults_origin")).isEqualTo("LEGACY");
        assertThat(link.get("creation_defaults_updated_by")).isEqualTo(USER);
    }

    @Test
    void equalDefaultsAreNotWrittenAgainAndASecondApplyOfTheSameReceiptIsRefused() throws Exception {
        Fixture fixture = cdFixture("CDEQ");
        Codes codes = codes();
        withDefaults(fixture.bundle(), codes.vat(), codes.unit());
        long first = receive(fixture.bundle());
        String fingerprint = fingerprint(first);
        apply(first, fingerprint, REASON).andExpect(status().isOk());
        Map<String, Object> afterFirst = linkRow(fixture.bundle());

        // Dezelfde ontvangst nog eens: 409, niets geschreven.
        apply(first, fingerprint, REASON).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEGACY_RECEIPT_NOT_APPLICABLE"));
        assertThat(linkRow(fixture.bundle())).isEqualTo(afterFirst);

        // Een nieuwe ontvangst met dezelfde 51/45 (enkel een niet-gemigreerd label anders): gelijk → niets.
        detail(fixture.bundle(), "ArtImp_CdB3Remark", "x");
        long second = receive(fixture.bundle());
        String preview = previewBody(second);
        assertThat(blockingCodes(preview)).isEmpty();
        assertThat(elementCodes(preview, VAT)).containsExactly("CREATION_DEFAULT_VAT", "PRODIS_VAT_CODE");
        assertThat(elementMessages(preview, VAT).get(0)).contains("already has default VAT code " + codes.vat(),
                "unchanged");
        assertThat(preview).doesNotContain("CREATION_DEFAULT_OVERWRITTEN").doesNotContain("CREATION_DEFAULT_DIFFERS")
                .doesNotContain("CREATION_DEFAULT_MANUAL_OVERWRITTEN");
        apply(second, JsonPath.read(preview, "$.previewFingerprint"), REASON).andExpect(status().isOk());

        assertThat(linkRow(fixture.bundle())).isEqualTo(afterFirst);
        assertThat(count("select count(*) from import_link where code = ?", "LEGACY-" + interfaceCode(fixture.bundle())))
                .isEqualTo(1);
    }

    @Test
    void aDifferentValueWithTheFlagOffIsReportedWithBothValuesAndKept() throws Exception {
        Fixture fixture = cdFixture("CDOFF");
        Codes before = codes();
        withDefaults(fixture.bundle(), before.vat(), before.unit());
        previewAndApply(receive(fixture.bundle())).andExpect(status().isOk());
        Map<String, Object> afterFirst = linkRow(fixture.bundle());

        Codes changed = codes();
        withDefaults(fixture.bundle(), changed.vat(), before.unit());
        long second = receive(fixture.bundle());
        String preview = previewBody(second);
        assertThat(blockingCodes(preview)).isEmpty();
        assertThat(elementCodes(preview, VAT)).containsExactly("CREATION_DEFAULT_DIFFERS_NOT_CHANGED",
                "PRODIS_VAT_CODE");
        assertThat(elementStatuses(preview, VAT).get(0)).isEqualTo("WARNING");
        assertThat(elementMessages(preview, VAT).get(0)).contains(before.vat(), changed.vat(), "LEGACY");
        assertThat(elementCodes(preview, UNIT)).containsExactly("CREATION_DEFAULT_UNIT", "PRODIS_UNIT");
        assertThat(preview).doesNotContain("CREATION_DEFAULT_OVERWRITTEN");

        apply(second, JsonPath.read(preview, "$.previewFingerprint"), REASON).andExpect(status().isOk());
        assertThat(linkRow(fixture.bundle())).isEqualTo(afterFirst);
    }

    @Test
    void withTheFlagOnAValueFromAPreviousMigrationIsOverwritten() throws Exception {
        Fixture fixture = cdFixture("CDONL");
        Codes before = codes();
        withDefaults(fixture.bundle(), before.vat(), before.unit());
        previewAndApply(receive(fixture.bundle())).andExpect(status().isOk());
        Timestamp firstAudit = (Timestamp) linkRow(fixture.bundle()).get("creation_defaults_updated_at");
        overwriteFlag(definitionId(fixture.supplier(), DEFINITION_CODE), true);

        Codes changed = codes();
        withDefaults(fixture.bundle(), changed.vat(), before.unit());
        long second = receive(fixture.bundle());
        String preview = previewBody(second);
        assertThat(blockingCodes(preview)).isEmpty();
        assertThat(elementCodes(preview, VAT)).containsExactly("CREATION_DEFAULT_VAT", "PRODIS_VAT_CODE",
                "CREATION_DEFAULT_OVERWRITTEN");
        assertThat(elementStatuses(preview, VAT).get(2)).isEqualTo("WARNING");
        assertThat(elementTargets(preview, VAT).get(2)).isEqualTo("link.defaultVatCode=" + changed.vat());
        assertThat(elementMessages(preview, VAT).get(2)).contains(before.vat(), changed.vat())
                .doesNotContain("set manually");
        assertThat(elementCodes(preview, UNIT)).containsExactly("CREATION_DEFAULT_UNIT", "PRODIS_UNIT");
        assertThat(preview).doesNotContain("CREATION_DEFAULT_MANUAL_OVERWRITTEN");

        apply(second, JsonPath.read(preview, "$.previewFingerprint"), REASON).andExpect(status().isOk());
        Map<String, Object> link = linkRow(fixture.bundle());
        assertThat(link.get("default_vat_code")).isEqualTo(changed.vat());
        assertThat(link.get("default_unit_code")).isEqualTo(before.unit());
        assertThat(link.get("creation_defaults_origin")).isEqualTo("LEGACY");
        assertThat(((Timestamp) link.get("creation_defaults_updated_at")).toInstant())
                .isAfterOrEqualTo(firstAudit.toInstant());
    }

    @Test
    void withTheFlagOnAManualValueIsOverwrittenWithItsOwnClearWarning() throws Exception {
        Fixture fixture = cdFixture("CDONM");
        previewAndApply(receive(fixture.bundle())).andExpect(status().isOk());
        long linkId = linkId(fixture.bundle());
        Codes manual = codes();
        putDefaults(linkId, manual.vat(), manual.unit()).andExpect(status().isOk());
        assertThat(linkRow(fixture.bundle()).get("creation_defaults_origin")).isEqualTo("MANUAL");
        overwriteFlag(definitionId(fixture.supplier(), DEFINITION_CODE), true);

        Codes legacy = codes();
        withDefaults(fixture.bundle(), legacy.vat(), legacy.unit());
        long second = receive(fixture.bundle());
        String preview = previewBody(second);
        assertThat(blockingCodes(preview)).isEmpty();
        assertThat(elementCodes(preview, VAT)).containsExactly("CREATION_DEFAULT_VAT", "PRODIS_VAT_CODE",
                "CREATION_DEFAULT_MANUAL_OVERWRITTEN");
        assertThat(elementCodes(preview, UNIT)).containsExactly("CREATION_DEFAULT_UNIT", "PRODIS_UNIT",
                "CREATION_DEFAULT_MANUAL_OVERWRITTEN");
        assertThat(elementMessages(preview, VAT).get(2)).contains(manual.vat(), legacy.vat(), "set manually");
        assertThat(elementMessages(preview, UNIT).get(2)).contains(manual.unit(), legacy.unit(), "set manually");
        assertThat(preview).doesNotContain("\"CREATION_DEFAULT_OVERWRITTEN\"");

        apply(second, JsonPath.read(preview, "$.previewFingerprint"), REASON).andExpect(status().isOk());
        Map<String, Object> link = linkRow(fixture.bundle());
        assertThat(link.get("default_vat_code")).isEqualTo(legacy.vat());
        assertThat(link.get("default_unit_code")).isEqualTo(legacy.unit());
        assertThat(link.get("creation_defaults_origin")).isEqualTo("LEGACY");
    }

    /** Aanname CD-B3: één herkomst voor beide velden; een manuele waarde die blijft, houdt de herkomst MANUAL. */
    @Test
    void aPartialFillNextToAManualValueThatStaysKeepsOriginManual() throws Exception {
        Fixture fixture = cdFixture("CDPART");
        previewAndApply(receive(fixture.bundle())).andExpect(status().isOk());
        long linkId = linkId(fixture.bundle());
        Codes manual = codes();
        putDefaults(linkId, manual.vat(), null).andExpect(status().isOk());
        Timestamp manualAudit = (Timestamp) linkRow(fixture.bundle()).get("creation_defaults_updated_at");

        Codes legacy = codes();
        withDefaults(fixture.bundle(), legacy.vat(), legacy.unit());
        long second = receive(fixture.bundle());
        String preview = previewBody(second);
        assertThat(elementCodes(preview, VAT)).containsExactly("CREATION_DEFAULT_DIFFERS_NOT_CHANGED",
                "PRODIS_VAT_CODE");
        assertThat(elementMessages(preview, VAT).get(0)).contains(manual.vat(), legacy.vat(), "MANUAL");
        assertThat(elementCodes(preview, UNIT)).containsExactly("CREATION_DEFAULT_UNIT", "PRODIS_UNIT");

        apply(second, JsonPath.read(preview, "$.previewFingerprint"), REASON).andExpect(status().isOk());
        Map<String, Object> link = linkRow(fixture.bundle());
        assertThat(link.get("default_vat_code")).isEqualTo(manual.vat());
        assertThat(link.get("default_unit_code")).isEqualTo(legacy.unit());
        assertThat(link.get("creation_defaults_origin")).isEqualTo("MANUAL");
        assertThat(((Timestamp) link.get("creation_defaults_updated_at")).toInstant())
                .isAfterOrEqualTo(manualAudit.toInstant());
    }

    // --- G2a: loskoppelen van een bestaande koppeling --------------------------------------------------------------

    @Test
    void detachingAnExistingLinkKeepsItsDefaultsWhenTheFlagIsOff() throws Exception {
        Group group = group("CDG2A");
        Map<String, Object> before = linkRow(group.a());

        // A wijkt af van de groep (andere basisprijskolom) en levert een andere btw-code; de vlag staat uit.
        line(group.a(), 26).put("column", 17);
        Codes changed = codes();
        withDefaults(group.a(), changed.vat(), group.codes().unit());
        long receipt = receive(group.a());
        String preview = previewBody(receipt, DETACH, "true");
        assertThat(blockingCodes(preview)).isEmpty();
        assertThat((String) JsonPath.read(preview, "$.report.planned.link.action")).isEqualTo("REPOINT");
        assertThat(elementCodes(preview, VAT)).containsExactly("CREATION_DEFAULT_DIFFERS_NOT_CHANGED",
                "PRODIS_VAT_CODE");
        assertThat(elementMessages(preview, UNIT).get(0)).contains("unchanged");

        apply(receipt, JsonPath.read(preview, "$.previewFingerprint"), REASON, DETACH, "true")
                .andExpect(status().isOk());
        long copy = definitionId(group.supplier(), "LEGACY-" + interfaceCode(group.a()));
        Map<String, Object> after = linkRow(group.a());
        assertThat(((Number) after.get("import_definition_id")).longValue()).isEqualTo(copy);
        for (String column : List.of("default_vat_code", "default_unit_code", "creation_defaults_origin",
                "creation_defaults_updated_at", "creation_defaults_updated_by", "creation_defaults_updated_by_subject")) {
            assertThat(after.get(column)).as(column).isEqualTo(before.get(column));
        }
        // B (op de gedeelde definitie) blijft ongemoeid.
        assertThat(linkRow(group.b()).get("default_vat_code")).isEqualTo(group.codes().vat());
    }

    /** De vlag wordt gelezen op de definitie waarop de koppeling staat: bij G2a de gedeelde (die de kopie erft). */
    @Test
    void detachingWithTheFlagOnOfTheSharedDefinitionOverwritesAndTheCopyInheritsTheFlag() throws Exception {
        Group group = group("CDG2B");
        overwriteFlag(group.shared(), true);

        line(group.a(), 26).put("column", 17);
        Codes changed = codes();
        withDefaults(group.a(), changed.vat(), group.codes().unit());
        long receipt = receive(group.a());
        String preview = previewBody(receipt, DETACH, "true");
        assertThat(blockingCodes(preview)).isEmpty();
        assertThat(elementCodes(preview, VAT)).containsExactly("CREATION_DEFAULT_VAT", "PRODIS_VAT_CODE",
                "CREATION_DEFAULT_OVERWRITTEN");

        apply(receipt, JsonPath.read(preview, "$.previewFingerprint"), REASON, DETACH, "true")
                .andExpect(status().isOk());
        long copy = definitionId(group.supplier(), "LEGACY-" + interfaceCode(group.a()));
        assertThat(jdbc.queryForObject("select overwrite_creation_defaults from import_definition where id = ?",
                Boolean.class, copy)).isTrue();
        assertThat(linkRow(group.a()).get("default_vat_code")).isEqualTo(changed.vat());
        assertThat(linkRow(group.a()).get("creation_defaults_origin")).isEqualTo("LEGACY");
        assertThat(linkRow(group.b()).get("default_vat_code")).isEqualTo(group.codes().vat());
    }

    // --- Prodis: onbekend (V2), dubbel, onbereikbaar -----------------------------------------------------------------

    @Test
    void anUnknownCodeIsAWarningAndIsNeverSet() throws Exception {
        Fixture fixture = cdFixture("CDV2");
        Codes codes = codes();
        String unknownVat = "NOPE" + random(6);
        withDefaults(fixture.bundle(), unknownVat, codes.unit());
        // Een inactieve of verwijderde rij telt niet.
        PRODIS.insertVatCode(unknownVat, "Inactief", 21.0, false, false);
        long id = receive(fixture.bundle());

        String preview = previewBody(id);
        assertThat(blockingCodes(preview)).isEmpty();
        assertThat(elementCodes(preview, VAT)).containsExactly("PRODIS_VAT_CODE_NOT_FOUND");
        assertThat(elementStatuses(preview, VAT)).containsExactly("WARNING");
        assertThat(elementMessages(preview, VAT).get(0)).contains(unknownVat, "does not get it");
        assertThat(elementCodes(preview, UNIT)).containsExactly("CREATION_DEFAULT_UNIT", "PRODIS_UNIT");

        apply(id, JsonPath.read(preview, "$.previewFingerprint"), REASON).andExpect(status().isOk());
        Map<String, Object> link = linkRow(fixture.bundle());
        assertThat(link.get("default_vat_code")).isNull();
        assertThat(link.get("default_unit_code")).isEqualTo(codes.unit());
        assertThat(link.get("creation_defaults_origin")).isEqualTo("LEGACY");

        // Een bestaande waarde blijft staan als legacy een onbekende code levert (nooit gewist, geen DIFFERS).
        withDefaults(fixture.bundle(), unknownVat, "NOPE" + random(6));
        long second = receive(fixture.bundle());
        String again = previewBody(second);
        assertThat(elementCodes(again, UNIT)).containsExactly("PRODIS_UNIT_NOT_FOUND");
        assertThat(again).doesNotContain("CREATION_DEFAULT_DIFFERS_NOT_CHANGED");
        apply(second, JsonPath.read(again, "$.previewFingerprint"), REASON).andExpect(status().isOk());
        assertThat(linkRow(fixture.bundle())).isEqualTo(link);
    }

    @Test
    void bothCodesUnknownLeaveTheNewLinkWithoutDefaultsAndWithoutAudit() throws Exception {
        Fixture fixture = cdFixture("CDV2B");
        withDefaults(fixture.bundle(), "NOPE" + random(6), "NOPE" + random(6));
        previewAndApply(receive(fixture.bundle())).andExpect(status().isOk());

        Map<String, Object> link = linkRow(fixture.bundle());
        assertThat(link.get("default_vat_code")).isNull();
        assertThat(link.get("default_unit_code")).isNull();
        assertThat(link.get("creation_defaults_origin")).isNull();
        assertThat(link.get("creation_defaults_updated_at")).isNull();
    }

    @Test
    void anAmbiguousCodeIsSetWithAWarning() throws Exception {
        Fixture fixture = cdFixture("CDAMB");
        Codes codes = codes();
        PRODIS.insertVatCode(codes.vat(), "Tweede rij", 6.0, true, false);
        withDefaults(fixture.bundle(), codes.vat(), codes.unit());
        long id = receive(fixture.bundle());

        String preview = previewBody(id);
        assertThat(blockingCodes(preview)).isEmpty();
        assertThat(elementCodes(preview, VAT)).containsExactly("CREATION_DEFAULT_VAT", "PRODIS_REFERENCE_AMBIGUOUS");
        assertThat(elementStatuses(preview, VAT).get(1)).isEqualTo("WARNING");

        apply(id, JsonPath.read(preview, "$.previewFingerprint"), REASON).andExpect(status().isOk());
        assertThat(linkRow(fixture.bundle()).get("default_vat_code")).isEqualTo(codes.vat());
    }

    @Test
    void whileTheProdisListsAreUnreachableTheDefaultsAreSetUnverified() throws Exception {
        Fixture fixture = cdFixture("CDUNAV");
        Codes codes = codes();
        withDefaults(fixture.bundle(), codes.vat(), codes.unit());
        long id = receive(fixture.bundle());
        String verified = fingerprint(id);

        PRODIS.execute("alter table vat_code rename to vat_code_unavailable");
        PRODIS.execute("alter table unit_of_measure rename to unit_of_measure_unavailable");
        try {
            // Een geverifieerde preview is verouderd zolang de lijsten weg zijn.
            apply(id, verified, REASON).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("LEGACY_PREVIEW_STALE"));

            String preview = previewBody(id);
            assertThat(blockingCodes(preview)).isEmpty();
            assertThat((String) JsonPath.read(preview, "$.report.prodisReference")).isEqualTo("UNAVAILABLE");
            assertThat((String) JsonPath.read(preview, "$.previewFingerprint")).startsWith("unverified:");
            assertThat(elementCodes(preview, VAT)).containsExactly("CREATION_DEFAULT_VAT",
                    "PRODIS_REFERENCE_NOT_VERIFIED");
            assertThat(elementMessages(preview, VAT).get(1)).contains("not available right now");
            assertThat(elementCodes(preview, UNIT)).containsExactly("CREATION_DEFAULT_UNIT",
                    "PRODIS_REFERENCE_NOT_VERIFIED");

            apply(id, JsonPath.read(preview, "$.previewFingerprint"), REASON).andExpect(status().isOk())
                    .andExpect(jsonPath("$.report.prodisReference").value("UNAVAILABLE"));
        } finally {
            PRODIS.execute("alter table vat_code_unavailable rename to vat_code");
            PRODIS.execute("alter table unit_of_measure_unavailable rename to unit_of_measure");
        }
        Map<String, Object> link = linkRow(fixture.bundle());
        assertThat(link.get("default_vat_code")).isEqualTo(codes.vat());
        assertThat(link.get("default_unit_code")).isEqualTo(codes.unit());
        assertThat(link.get("creation_defaults_origin")).isEqualTo("LEGACY");
    }

    // --- Fingerprint: Prodis, PUT van de mens, vlag ------------------------------------------------------------------

    @Test
    void aChangeOfTheProdisRowOfACodeMakesThePreviewStale() throws Exception {
        Fixture fixture = cdFixture("CDFPP");
        Codes codes = codes();
        withDefaults(fixture.bundle(), codes.vat(), codes.unit());
        long id = receive(fixture.bundle());
        List<Long> before = footprint(fixture.bundle());

        String first = fingerprint(id);
        assertThat(fingerprint(id)).isEqualTo(first);
        PRODIS.execute("update vat_code set description = ? where code = ?", "Btw hernoemd", codes.vat());
        String second = fingerprint(id);
        assertThat(second).isNotEqualTo(first);
        apply(id, first, REASON).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEGACY_PREVIEW_STALE"));
        assertThat(footprint(fixture.bundle())).isEqualTo(before);

        // De eenheid inactief: het rapport verandert (niet meer gezet) en dus ook de fingerprint.
        PRODIS.execute("update unit_of_measure set record_active = false where code = ?", codes.unit());
        String third = previewBody(id);
        assertThat((String) JsonPath.read(third, "$.previewFingerprint")).isNotEqualTo(second);
        assertThat(elementCodes(third, UNIT)).containsExactly("PRODIS_UNIT_NOT_FOUND");
        apply(id, second, REASON).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEGACY_PREVIEW_STALE"));

        apply(id, JsonPath.read(third, "$.previewFingerprint"), REASON).andExpect(status().isOk());
        assertThat(linkRow(fixture.bundle()).get("default_vat_code")).isEqualTo(codes.vat());
        assertThat(linkRow(fixture.bundle()).get("default_unit_code")).isNull();
    }

    @Test
    void aHumanChangeOfTheLinkOrTheFlagBetweenPreviewAndApplyMakesThePreviewStale() throws Exception {
        Fixture fixture = cdFixture("CDFPH");
        previewAndApply(receive(fixture.bundle())).andExpect(status().isOk());
        long linkId = linkId(fixture.bundle());
        Codes codes = codes();
        withDefaults(fixture.bundle(), codes.vat(), codes.unit());
        long second = receive(fixture.bundle());

        // Een PUT met dezelfde waarden als legacy schuift toch updated_at en de audit op.
        String stale = fingerprint(second);
        putDefaults(linkId, codes.vat(), codes.unit()).andExpect(status().isOk());
        Map<String, Object> manual = linkRow(fixture.bundle());
        apply(second, stale, REASON).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEGACY_PREVIEW_STALE"));
        assertThat(linkRow(fixture.bundle())).isEqualTo(manual);
        assertThat(receiptRow(second).get("status")).isEqualTo("RECEIVED");

        // De vlag omzetten tussen PREVIEW en APPLY: ook verouderd.
        String beforeFlag = fingerprint(second);
        overwriteFlag(definitionId(fixture.supplier(), DEFINITION_CODE), true);
        apply(second, beforeFlag, REASON).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEGACY_PREVIEW_STALE"));

        // Een verse preview: gelijk aan de manuele waarden → niets geschreven, herkomst blijft MANUAL.
        String fresh = previewBody(second);
        assertThat(elementCodes(fresh, VAT)).containsExactly("CREATION_DEFAULT_VAT", "PRODIS_VAT_CODE");
        apply(second, JsonPath.read(fresh, "$.previewFingerprint"), REASON).andExpect(status().isOk());
        assertThat(linkRow(fixture.bundle())).isEqualTo(manual);
        assertThat(manual.get("creation_defaults_origin")).isEqualTo("MANUAL");
    }

    // --- Hulp ------------------------------------------------------------------------------------------------------

    private record Codes(String vat, String unit) {
    }

    /** Een groep van twee interfaces (A, B) op één gedeelde definitie, beide met dezelfde standaard {@code codes}. */
    private record Group(ObjectNode a, ObjectNode b, String supplier, long shared, Codes codes) {
    }

    /** Een toepasbare bundel met een Prodis-leverancier en precies één bibliotheek {@code library} voor ArtImp_BibNr. */
    private static Fixture cdFixture(String library) throws Exception {
        Fixture fixture = withProdisSupplier("Leverancier " + library);
        catalog(fixture, library, LEGACY_LIBRARY, "CAT " + library, "ACTIVE");
        return fixture;
    }

    private Group group(String library) throws Exception {
        Fixture fixture = cdFixture(library);
        catalog(fixture, library + "B", LEGACY_LIBRARY, "CAT " + library + "B", "ACTIVE");
        Codes codes = codes();
        ObjectNode a = withDefaults(fixture.bundle(), codes.vat(), codes.unit());
        ObjectNode b = a.deepCopy();
        interfaceCode(b, interfaceCode(a) + "B");
        previewAndApply(receive(a), "libraryCode", library).andExpect(status().isOk());
        previewAndApply(receive(b), "libraryCode", library + "B").andExpect(status().isOk());
        return new Group(a, b, fixture.supplier(), definitionId(fixture.supplier(), DEFINITION_CODE), codes);
    }

    /** Een actieve btw-code en eenheid met een eigen code (de H2-bron is gedeeld). */
    private static Codes codes() {
        Codes codes = new Codes("V" + random(6), "U" + random(6));
        PRODIS.insertVatCode(codes.vat(), "Btw " + codes.vat(), 21.0, true, false);
        PRODIS.insertUnitOfMeasure(codes.unit(), "Eenheid " + codes.unit(), "e", true, false);
        return codes;
    }

    /** Vervangt PSIMPDEF 51/45 door een regel met die vaste waarde ({@code null} = geen regel). */
    static ObjectNode withDefaults(ObjectNode bundle, String vatCode, String unitCode) {
        ArrayNode lines = (ArrayNode) bundle.get("definition").get("lines");
        for (int i = lines.size() - 1; i >= 0; i--) {
            int number = lines.get(i).get("number").asInt();
            if (number == 51 || number == 45) {
                lines.remove(i);
            }
        }
        if (vatCode != null) {
            lines.add(legacyLine(lines, 51, "Btw-code", vatCode));
        }
        if (unitCode != null) {
            lines.add(legacyLine(lines, 45, "Eenheid", unitCode));
        }
        return bundle;
    }

    private static ObjectNode legacyLine(ArrayNode lines, int number, String field, String value) {
        ObjectNode line = ((ObjectNode) lines.get(0)).deepCopy();
        line.put("number", number).put("field", field).put("column", 0).put("position", 0).put("length", 0)
                .put("includeExclude", "0").put("includeExcludeData", "").put("prefix", "").put("suffix", "")
                .put("value", value).put("fill", "").put("calculation", "").put("calculationValue", "")
                .put("xmlElementPath", "");
        return line;
    }

    private Map<String, Object> linkRow(JsonNode bundle) {
        return jdbc.queryForMap("select import_definition_id, default_vat_code, default_unit_code, "
                + "creation_defaults_origin, creation_defaults_updated_at, creation_defaults_updated_by, "
                + "creation_defaults_updated_by_subject, updated_at from import_link where code = ?",
                "LEGACY-" + interfaceCode(bundle));
    }

    private long linkId(JsonNode bundle) {
        return count("select id from import_link where code = ?", "LEGACY-" + interfaceCode(bundle));
    }

    private ResultActions putDefaults(long linkId, String vatCode, String unitCode) throws Exception {
        String body = "{" + (vatCode == null ? "" : "\"vatCode\":\"" + vatCode + "\"")
                + (vatCode != null && unitCode != null ? "," : "")
                + (unitCode == null ? "" : "\"unitCode\":\"" + unitCode + "\"") + "}";
        return mockMvc.perform(put(SETUP + "/links/{id}/creation-defaults", linkId).with(as(USER))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void overwriteFlag(long definitionId, boolean enabled) throws Exception {
        mockMvc.perform(put(SETUP + "/definitions/{id}/overwrite-creation-defaults", definitionId).with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":" + enabled + "}"))
                .andExpect(status().isOk());
    }

    static List<String> elementCodes(String body, String element) {
        return JsonPath.read(body, "$.report.items[?(@.element == '" + element + "')].code");
    }

    private static List<String> elementStatuses(String body, String element) {
        return JsonPath.read(body, "$.report.items[?(@.element == '" + element + "')].status");
    }

    private static List<String> elementTargets(String body, String element) {
        return JsonPath.read(body, "$.report.items[?(@.element == '" + element + "')].target");
    }

    private static List<String> elementMessages(String body, String element) {
        return JsonPath.read(body, "$.report.items[?(@.element == '" + element + "')].message");
    }

    private static String random(int length) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, length).toUpperCase();
    }
}
