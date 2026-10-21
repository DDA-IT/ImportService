package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;

import be.dda.catalogimport.domain.FilterNullBehaviour;
import be.dda.catalogimport.domain.FilterOperator;
import be.dda.catalogimport.domain.FilterOutcome;
import be.dda.catalogimport.domain.MissingColumnBehaviour;
import be.dda.catalogimport.service.LegacyConfigReport.Item;
import be.dda.catalogimport.service.LegacyConfigTranslator.PlannedFilter;
import be.dda.catalogimport.service.LegacyConfigTranslator.Translation;
import be.dda.catalogimport.service.support.CsvRecordStreamer.ParsedRow;
import be.dda.catalogimport.service.support.ImportMappingConfig.RecordFilter;
import be.dda.catalogimport.service.support.RecordFilterEvaluator;
import be.dda.catalogimport.service.support.SourceFieldPositions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Legacy-config S8f (design {@code legacy-config-s8-design.md} par. 2f; beslissing 2026-10-21 V2 = A): de vertaling van
 * PSIMPEXCL naar EN-groepen ({@code match_group}), samen met {@link RecordFilterEvaluator}, sluit <b>exact</b> dezelfde
 * records uit als legacy.
 * <p>
 * Het orakel {@link #legacyExcludes} is een letterlijke herimplementatie van de taak "Excludes" van {@code Prg_1170}
 * (r.2582-2687): vijf exacte opzoekingen in de uitsluitingen op de verwerkte {@code ARIMP}-waarden (leverancier, groep
 * en referentie in hoofdletters, de kortingscode niet, {@code ''} letterlijk leeg); het record wordt overgeslagen als
 * een opzoeking treft. De golden test loopt over alle combinaties van de vijf vormen, de dode rijen en hoofd-/kleine
 * letters, op een recordruimte met een niet-lege leverancier en referentie (een record zonder die identiteitsdelen
 * verwerpt CatalogImport toch, vóór elke publicatie).
 */
class LegacyExcludeMigrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** De bronkolommen: 1 leverancier, 2 groep, 3 referentie, 4 kortingscode, 5 basisprijs. */
    private static final SourceFieldPositions POSITIONS =
            new SourceFieldPositions(Map.of("1", 0, "2", 1, "3", 2, "4", 3, "5", 4));

    private static final String[] SUPPLIERS = {"ACME", "acme", " ACME ", "OTHER"};
    private static final String[] GROUPS = {"", "G1", "g1", "G2"};
    private static final String[] REFERENCES = {"R1", "r1", "R2"};
    private static final String[] DISCOUNTS = {"", "K1", "k1", "K2"};

    // --- Golden: vijf vormen, dode rijen, hoofdletters -------------------------------------------------------

    @Test
    void everyExcludeRowExcludesExactlyTheRecordsLegacyExcludesWithFourPartIdentity() {
        assertGolden(Discount.COLUMN, true, null, null);
    }

    /** Ook bij THREE_PART vergelijkt legacy de kortingscode van regel 23 (ze is dan geen identiteitsdeel). */
    @Test
    void theDiscountCodeColumnIsComparedAlsoWithThreePartIdentity() {
        assertGolden(Discount.COLUMN, false, null, null);
    }

    /** Zonder regel 23 is de kortingscode in legacy altijd leeg: een vergelijking met '' valt weg, een andere is dood. */
    @Test
    void withoutFieldTwentyThreeTheDiscountCodeIsAlwaysEmpty() {
        assertGolden(Discount.ABSENT, false, null, null);
    }

    /** S6: een vaste leverancier/groep (in hoofdletters zoals legacy) wordt bij de migratie geëvalueerd. */
    @Test
    void aFixedSupplierAndGroupAreEvaluatedAtMigration() {
        assertGolden(Discount.COLUMN, true, "Acme", "G1");
    }

    /** Elke vorm apart, met de verwachte groep zoals ze in de database komt. */
    @Test
    void theFiveFormsBecomeTheseGroups() {
        assertThat(groups(translate(Discount.COLUMN, true, null, null, false, row("ACME", "", "", ""))))
                .containsExactly("1:1:EQUALS:ACME:ci");
        assertThat(groups(translate(Discount.COLUMN, true, null, null, false, row("", "G1", "", ""))))
                .containsExactly("1:2:EQUALS:G1:ci");
        assertThat(groups(translate(Discount.COLUMN, true, null, null, false, row("", "", "", ""))))
                .containsExactly("1:2:EQUALS::ci");
        assertThat(groups(translate(Discount.COLUMN, true, null, null, false, row("", "G1", "K1", ""))))
                .containsExactly("1:2:EQUALS:G1:ci", "1:4:EQUALS:K1:cs");
        assertThat(groups(translate(Discount.COLUMN, true, null, null, false, row("", "G1", "", "R1"))))
                .containsExactly("1:2:EQUALS:G1:ci", "1:4:EQUALS::cs", "1:3:EQUALS:R1:ci");
        assertThat(groups(translate(Discount.COLUMN, true, null, null, false, row("ACME", "", "k1", "R1"))))
                .containsExactly("1:1:EQUALS:ACME:ci", "1:2:EQUALS::ci", "1:4:EQUALS:k1:cs", "1:3:EQUALS:R1:ci");
        // Twee rijen: twee groepen, de volgnummers lopen door achter elkaar.
        Translation two = translate(Discount.COLUMN, true, null, null, false, row("ACME", "", "", ""),
                row("", "G1", "K1", ""));
        assertThat(groups(two)).containsExactly("1:1:EQUALS:ACME:ci", "2:2:EQUALS:G1:ci", "2:4:EQUALS:K1:cs");
        assertThat(two.revision().filters()).extracting(PlannedFilter::sequence).containsExactly(1, 2, 3);
        assertThat(two.revision().filters()).allSatisfy(filter -> {
            assertThat(filter.outcome()).isEqualTo(FilterOutcome.EXCLUDE);
            assertThat(filter.nullBehaviour()).isEqualTo(FilterNullBehaviour.COMPARE_AS_EMPTY);
        });
        assertThat(codes(two, LegacyConfigReport.MIGRATED)).filteredOn("RECORD_FILTER_GROUP"::equals).hasSize(2);
        assertThat(codes(two, null)).doesNotContain(LegacyConfigTranslator.LEGACY_EXCLUDES_NOT_MIGRATED);
    }

    @Test
    void deadRowsAreReportedAndNeverMigrated() {
        for (ObjectNode dead : List.of(row("ACME", "G1", "", ""), row("ACME", "", "K1", ""), row("acme", "", "", ""),
                row("", "g1", "", ""), row("", "G1", "", "r1"))) {
            Translation translation = translate(Discount.COLUMN, true, null, null, false, dead);
            assertThat(translation.revision().filters()).as(dead.toString()).isEmpty();
            assertThat(codes(translation, LegacyConfigReport.NOT_MIGRATED)).as(dead.toString())
                    .contains(LegacyConfigTranslator.LEGACY_EXCLUDE_NEVER_MATCHES);
        }
        // Een kleine letter in de kortingscode is geen dode rij: legacy zet de korting niet in hoofdletters.
        assertThat(groups(translate(Discount.COLUMN, true, null, null, false, row("", "G1", "k1", ""))))
                .containsExactly("1:2:EQUALS:G1:ci", "1:4:EQUALS:k1:cs");
    }

    // --- Vaste delen, niet uitdrukbaar, alles uitgesloten, Skip_Excludes -------------------------------------------

    @Test
    void aRowThatTheFixedPartsAlwaysMatchExcludesEverythingAndBlocks() {
        Translation translation = translate(Discount.COLUMN, true, "Acme", null, false, row("ACME", "", "", ""));

        assertThat(translation.blocking()).isTrue();
        assertThat(translation.revision()).isNull();
        assertThat(codes(translation, LegacyConfigReport.BLOCKING))
                .contains(LegacyConfigTranslator.LEGACY_EXCLUDE_EXCLUDES_ALL);
        // Een vaste groep (in hoofdletters G1) en de vorm "enkel groep": altijd waar.
        Translation group = translate(Discount.ABSENT, false, null, "g1", false, row("", "G1", "", ""));
        assertThat(codes(group, LegacyConfigReport.BLOCKING)).contains(LegacyConfigTranslator.LEGACY_EXCLUDE_EXCLUDES_ALL);
    }

    @Test
    void aFixedPartThatAlwaysMatchesIsLeftOutAndOneThatNeverMatchesKillsTheRow() {
        Translation leftOut = translate(Discount.COLUMN, true, null, "G1", false, row("", "G1", "K1", ""));
        assertThat(groups(leftOut)).containsExactly("1:4:EQUALS:K1:cs");
        assertThat(leftOut.items()).filteredOn(item -> "RECORD_FILTER_GROUP".equals(item.code()))
                .extracting(Item::message).singleElement().asString().contains("fixed supplier group G1");

        Translation never = translate(Discount.COLUMN, true, null, "G2", false, row("", "G1", "K1", ""));
        assertThat(never.revision().filters()).isEmpty();
        assertThat(codes(never, LegacyConfigReport.NOT_MIGRATED))
                .contains(LegacyConfigTranslator.LEGACY_EXCLUDE_NEVER_MATCHES);
    }

    /** Een kortingscode die niet uit te drukken is: de rij vervalt met een waarschuwing, nooit zonder die vergelijking. */
    @Test
    void aDiscountCodeWithoutColumnOrWithATransformationIsNotExpressible() {
        for (Discount discount : List.of(Discount.NO_COLUMN, Discount.TRANSFORMED)) {
            Translation translation = translate(discount, false, null, null, false, row("", "G1", "K1", ""),
                    row("ACME", "", "", ""));
            assertThat(codes(translation, LegacyConfigReport.WARNING)).as(discount.name())
                    .contains(LegacyConfigTranslator.LEGACY_EXCLUDE_NOT_EXPRESSIBLE);
            // De rij zonder kortingscode (enkel leverancier) wordt wel gemigreerd.
            assertThat(groups(translation)).as(discount.name()).containsExactly("1:1:EQUALS:ACME:ci");
        }
    }

    @Test
    void skipExcludesYesMigratesNoExcludeAndSaysSo() {
        Translation skipped = translate(Discount.COLUMN, true, null, null, true, row("ACME", "", "", ""));

        assertThat(skipped.revision().filters()).isEmpty();
        assertThat(codes(skipped, LegacyConfigReport.NOT_MIGRATED)).contains(LegacyConfigTranslator.LEGACY_EXCLUDES_SKIPPED);
        assertThat(codes(skipped, null)).doesNotContain("INTERFACE_SETTING_NOT_MIGRATED");

        ObjectNode bundle = bundle(Discount.COLUMN, true, null, null, row("ACME", "", "", ""));
        detail(bundle, LegacyConfigTranslator.LABEL_SKIP_EXCLUDES, "N");
        Translation applied = LegacyConfigTranslator.translate(bundle, null);
        assertThat(groups(applied)).containsExactly("1:1:EQUALS:ACME:ci");
        assertThat(codes(applied, null)).doesNotContain(LegacyConfigTranslator.LEGACY_EXCLUDES_SKIPPED,
                "INTERFACE_SETTING_NOT_MIGRATED");
    }

    /** Dezelfde bundel twee keer vertalen geeft exact hetzelfde plan (idempotentie van APPLY steunt hierop). */
    @Test
    void translatingTwiceGivesTheSamePlan() {
        ObjectNode bundle = bundle(Discount.COLUMN, true, null, null, row("ACME", "", "", "R1"), row("", "G1", "K1", ""),
                row("ACME", "", "", "R1"));
        Translation first = LegacyConfigTranslator.translate(bundle, null);
        Translation second = LegacyConfigTranslator.translate(bundle, null);

        assertThat(second.revision().filters()).isEqualTo(first.revision().filters());
        assertThat(second.items()).isEqualTo(first.items());
        // Dubbele input: twee identieke rijen worden twee identieke groepen, die samen niets anders uitsluiten.
        assertThat(groups(first)).hasSize(10);
    }

    // --- Golden-hulp -------------------------------------------------------------------------------------------

    private enum Discount { COLUMN, ABSENT, NO_COLUMN, TRANSFORMED }

    private static void assertGolden(Discount discount, boolean fourPart, String fixedSupplier, String fixedGroup) {
        String[] values = {"", "ACME", "acme"};
        String[] groupValues = {"", "G1", "g1"};
        String[] discountValues = {"", "K1", "k1"};
        String[] referenceValues = {"", "R1", "r1"};
        int checked = 0;
        for (String l : values) {
            for (String g : groupValues) {
                for (String k : discountValues) {
                    for (String r : referenceValues) {
                        ObjectNode exclude = row(l, g, k, r);
                        Translation translation = translate(discount, fourPart, fixedSupplier, fixedGroup, false,
                                exclude);
                        boolean excludesAll = codes(translation, LegacyConfigReport.BLOCKING)
                                .contains(LegacyConfigTranslator.LEGACY_EXCLUDE_EXCLUDES_ALL);
                        RecordFilterEvaluator evaluator = excludesAll ? null
                                : new RecordFilterEvaluator(recordFilters(translation));
                        for (String supplier : SUPPLIERS) {
                            for (String group : GROUPS) {
                                for (String reference : REFERENCES) {
                                    for (String code : DISCOUNTS) {
                                        boolean legacy = legacyExcludes(supplier, group, reference, code, discount,
                                                fixedSupplier, fixedGroup, l, g, k, r);
                                        String what = "row (" + l + "," + g + "," + k + "," + r + ") record (" + supplier
                                                + "," + group + "," + reference + "," + code + ")";
                                        if (excludesAll) {
                                            assertThat(legacy).as(what).isTrue();
                                        } else {
                                            ParsedRow record = new ParsedRow(2,
                                                    List.of(supplier, group, reference, code, "9,50"), POSITIONS);
                                            assertThat(!evaluator.evaluate(record).inScope()).as(what)
                                                    .isEqualTo(legacy);
                                        }
                                        checked++;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        assertThat(checked).isEqualTo(81 * SUPPLIERS.length * GROUPS.length * REFERENCES.length * DISCOUNTS.length);
    }

    /**
     * {@code Prg_1170} "Excludes": de verwerkte waarden (Trim; Upper op 9-11; een vaste waarde vervangt de kolom; zonder
     * regel 23 blijft de korting leeg) en de vijf opzoekingen ({@code Uniek_Key}, exact).
     */
    private static boolean legacyExcludes(String supplier, String group, String reference, String code,
                                          Discount discount, String fixedSupplier, String fixedGroup,
                                          String l, String g, String k, String r) {
        String arSupplier = (fixedSupplier != null ? fixedSupplier : supplier.trim()).toUpperCase(Locale.ROOT);
        String arGroup = (fixedGroup != null ? fixedGroup : group.trim()).toUpperCase(Locale.ROOT);
        String arReference = reference.trim().toUpperCase(Locale.ROOT);
        String arDiscount = discount == Discount.ABSENT ? "" : code.trim();
        boolean supplierOnly = l.equals(arSupplier) && g.isEmpty() && k.isEmpty() && r.isEmpty();
        boolean groupOnly = l.isEmpty() && g.equals(arGroup) && k.isEmpty() && r.isEmpty();
        boolean groupDiscount = l.isEmpty() && g.equals(arGroup) && k.equals(arDiscount) && r.isEmpty();
        boolean groupDiscountReference = l.isEmpty() && g.equals(arGroup) && k.equals(arDiscount)
                && r.equals(arReference);
        boolean all = l.equals(arSupplier) && g.equals(arGroup) && k.equals(arDiscount) && r.equals(arReference);
        return supplierOnly || groupOnly || groupDiscount || groupDiscountReference || all;
    }

    private static List<RecordFilter> recordFilters(Translation translation) {
        assertThat(translation.revision()).isNotNull();
        List<RecordFilter> filters = new ArrayList<>();
        for (PlannedFilter filter : translation.revision().filters()) {
            filters.add(new RecordFilter(filter.sequence(), filter.sourceReference(), filter.operator(),
                    filter.compareValue(), filter.outcome(), filter.caseSensitive(), true, filter.nullBehaviour(),
                    MissingColumnBehaviour.BLOCK, filter.matchGroup()));
        }
        return filters;
    }

    /** De gegroepeerde rijen als {@code groep:kolom:operator:waarde:ci|cs}. */
    private static List<String> groups(Translation translation) {
        assertThat(translation.revision()).isNotNull();
        return translation.revision().filters().stream()
                .peek(filter -> assertThat(filter.operator()).isEqualTo(FilterOperator.EQUALS))
                .map(filter -> filter.matchGroup() + ":" + filter.sourceReference() + ":" + filter.operator() + ":"
                        + filter.compareValue() + ":" + (filter.caseSensitive() ? "cs" : "ci"))
                .toList();
    }

    /** @param status null = alle statussen */
    private static List<String> codes(Translation translation, String status) {
        return translation.items().stream().filter(item -> status == null || status.equals(item.status()))
                .map(Item::code).toList();
    }

    // --- Bundel -------------------------------------------------------------------------------------------------

    private static Translation translate(Discount discount, boolean fourPart, String fixedSupplier, String fixedGroup,
                                         boolean skipExcludes, ObjectNode... excludes) {
        ObjectNode bundle = bundle(discount, fourPart, fixedSupplier, fixedGroup, excludes);
        if (skipExcludes) {
            detail(bundle, LegacyConfigTranslator.LABEL_SKIP_EXCLUDES, "Y");
        }
        return LegacyConfigTranslator.translate(bundle, null);
    }

    private static ObjectNode row(String supplier, String group, String discount, String reference) {
        return MAPPER.createObjectNode().put("supplier", supplier).put("supplierGroup", group)
                .put("discountCode", discount).put("supplierReference", reference);
    }

    private static ObjectNode bundle(Discount discount, boolean fourPart, String fixedSupplier, String fixedGroup,
                                     ObjectNode... excludes) {
        ObjectNode bundle = MAPPER.createObjectNode();
        bundle.put("definitionStatus", "PRESENT").put("definitionContentHash", "s8f");
        ObjectNode iface = bundle.putObject("interface");
        iface.put("code", "S8F");
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
        detail(bundle, "ArtImp_Key_Korting", fourPart ? "Y" : "N");
        ObjectNode definition = bundle.putObject("definition");
        definition.put("code", "S8F").put("description", "").put("updateFile", "").put("replacementCode", "")
                .put("deleteCode", "");
        ArrayNode lines = definition.putArray("lines");
        lines.add(line(9, fixedSupplier == null ? 1 : 0, fixedSupplier == null ? "" : fixedSupplier));
        lines.add(line(10, fixedGroup == null ? 2 : 0, fixedGroup == null ? "" : fixedGroup));
        lines.add(line(11, 3, ""));
        switch (discount) {
            case COLUMN -> lines.add(line(23, 4, ""));
            case NO_COLUMN -> lines.add(line(23, 0, ""));
            case TRANSFORMED -> lines.add(line(23, 4, "").put("prefix", "K-"));
            case ABSENT -> {
            }
        }
        lines.add(line(26, 5, ""));
        definition.putArray("maps");
        ArrayNode rows = definition.putArray("excludes");
        for (ObjectNode exclude : excludes) {
            rows.add(exclude);
        }
        return bundle;
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
