package be.dda.catalogimport.service.support;

import static org.assertj.core.api.Assertions.assertThat;

import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.service.support.SourceStructureConfig.FieldReferenceKind;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * EB5/G3: {@link SourceStructureConfigFactory#layoutOf} geeft de fysieke leesindeling met exact dezelfde regels als
 * {@link SourceStructureConfigFactory#from}.
 * <ul>
 *   <li><b>Regel:</b> voor elke rij van de golden table ({@link ConfigFactoryParityTest}) waarop {@code from()} slaagt,
 *       is de indeling bruikbaar en gelijk aan de waarden van {@code from()} (tekenset, scheidingsteken,
 *       aanhalingsteken, header, headerregel, verwijzingswijze).</li>
 *   <li><b>Regel:</b> faalt {@code from()} op een leesregel (S1-S5), dan is de indeling onbruikbaar met die code als
 *       eerste; faalt {@code from()} op iets anders (identiteit, prijs, kolomindex, ...), dan blijft de indeling
 *       bruikbaar — zo toont de proefinlezing de kolommen ook bij bv. {@code CONFIG_PRICE_FIELD_MISSING}.</li>
 * </ul>
 * De golden table wordt via reflectie gelezen: ze mag niet gewijzigd worden (ook niet in zichtbaarheid), en zo volgt
 * deze test elke rij die er later bijkomt.
 */
class SourceStructureLayoutTest {

    private static final SourceStructureConfigFactory FACTORY = new SourceStructureConfigFactory();

    /** De leescodes (S1-S5): enkel die maken de indeling onbruikbaar. */
    private static final Set<String> LAYOUT_CODES = Set.of(SourceStructureConfigFactory.CODE_FORMAT_UNSUPPORTED,
            SourceStructureConfigFactory.CODE_CHARSET_UNKNOWN, SourceStructureConfigFactory.CODE_DELIMITER_MISSING,
            SourceStructureConfigFactory.CODE_DELIMITER_INVALID, SourceStructureConfigFactory.CODE_QUOTE_INVALID,
            SourceStructureConfigFactory.CODE_HEADER_LINE_INVALID);

    // --- Golden table ---------------------------------------------------------------------------------------------

    @TestFactory
    Stream<DynamicTest> layoutOfEqualsFromForEveryGoldenRowWhereFromSucceeds() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        for (GoldenRow row : goldenRows()) {
            tests.add(DynamicTest.dynamicTest(row.id(), () -> {
                ImportDefinitionRevision revision = row.revision();
                SourceStructureConfig config;
                try {
                    config = FACTORY.from(revision, row.linkCurrency());
                } catch (ScreeningBlockedException blocked) {
                    assertLayoutAfterFailure(FACTORY.layoutOf(revision), blocked, row.id());
                    return;
                }
                SourceLayout layout = FACTORY.layoutOf(revision);
                assertThat(layout.usable()).as("%s usable", row.id()).isTrue();
                assertThat(layout.invalidCodes()).as("%s invalidCodes", row.id()).isEmpty();
                assertThat(layout.charset()).as("%s charset", row.id()).isEqualTo(config.charset());
                assertThat(layout.delimiter()).as("%s delimiter", row.id()).isEqualTo(config.delimiter());
                assertThat(layout.quote()).as("%s quote", row.id()).isEqualTo(config.quote());
                assertThat(layout.quoteChar()).as("%s quoteChar", row.id()).isEqualTo(config.quoteChar());
                assertThat(layout.hasHeader()).as("%s hasHeader", row.id()).isEqualTo(config.hasHeader());
                assertThat(layout.headerLineNumber()).as("%s headerLineNumber", row.id())
                        .isEqualTo(config.headerLineNumber());
                assertThat(layout.referenceKind()).as("%s referenceKind", row.id())
                        .isEqualTo(config.fieldReferenceKind());
            }));
        }
        assertThat(tests).as("golden table rows").isNotEmpty();
        return tests.stream();
    }

    /** De tabel bevat zowel slagende rijen als rijen met een lees- en met een veldfout; anders bewijst ze niets. */
    @Test
    void theGoldenTableCoversSucceedingLayoutFailingAndFieldFailingRows() throws Exception {
        int succeeding = 0;
        int layoutFailing = 0;
        int fieldFailing = 0;
        for (GoldenRow row : goldenRows()) {
            try {
                FACTORY.from(row.revision(), row.linkCurrency());
                succeeding++;
            } catch (ScreeningBlockedException blocked) {
                if (LAYOUT_CODES.contains(blocked.getCode())) {
                    layoutFailing++;
                } else {
                    fieldFailing++;
                }
            }
        }
        assertThat(succeeding).isGreaterThan(10);
        assertThat(layoutFailing).isGreaterThanOrEqualTo(10);
        assertThat(fieldFailing).isGreaterThan(10);
    }

    private static void assertLayoutAfterFailure(SourceLayout layout, ScreeningBlockedException blocked, String id) {
        if (LAYOUT_CODES.contains(blocked.getCode())) {
            assertThat(layout.usable()).as("%s usable after %s", id, blocked.getCode()).isFalse();
            assertThat(layout.invalidCodes()).as("%s first invalid code", id).first().isEqualTo(blocked.getCode());
        } else {
            assertThat(layout.usable()).as("%s usable after field error %s", id, blocked.getCode()).isTrue();
            assertThat(layout.charset()).as("%s charset", id).isNotNull();
            assertThat(layout.delimiter()).as("%s delimiter", id).isNotNull();
        }
    }

    // --- Afzonderlijke regels ---------------------------------------------------------------------------------------

    @Test
    void anUnknownCharsetMakesTheLayoutInvalid() {
        ImportDefinitionRevision revision = base();
        revision.setStructureCharset("NOPE-9");

        SourceLayout layout = FACTORY.layoutOf(revision);

        assertThat(layout.usable()).isFalse();
        assertThat(layout.invalidCodes()).containsExactly(SourceStructureConfigFactory.CODE_CHARSET_UNKNOWN);
        assertThat(layout.charset()).isNull();
        assertThat(layout.delimiter()).isEqualTo(';');
    }

    @Test
    void aMissingCharsetIsNeverGuessed() {
        ImportDefinitionRevision revision = base();
        revision.setStructureCharset(" ");

        SourceLayout layout = FACTORY.layoutOf(revision);

        assertThat(layout.usable()).isFalse();
        assertThat(layout.charset()).isNull();
        assertThat(layout.invalidCodes()).containsExactly(SourceStructureConfigFactory.CODE_CHARSET_UNKNOWN);
    }

    @Test
    void aMissingOrTooLongDelimiterMakesTheLayoutInvalid() {
        ImportDefinitionRevision missing = base();
        missing.setStructureDelimiter("");
        ImportDefinitionRevision tooLong = base();
        tooLong.setStructureDelimiter(";;");

        SourceLayout withoutDelimiter = FACTORY.layoutOf(missing);
        SourceLayout withLongDelimiter = FACTORY.layoutOf(tooLong);

        assertThat(withoutDelimiter.usable()).isFalse();
        assertThat(withoutDelimiter.delimiter()).isNull();
        assertThat(withoutDelimiter.invalidCodes()).containsExactly(SourceStructureConfigFactory.CODE_DELIMITER_MISSING);
        assertThat(withLongDelimiter.usable()).isFalse();
        assertThat(withLongDelimiter.invalidCodes()).containsExactly(SourceStructureConfigFactory.CODE_DELIMITER_INVALID);
    }

    @Test
    void aQuoteEqualToTheDelimiterOrAnInvalidHeaderLineMakesTheLayoutInvalidInFactoryOrder() {
        ImportDefinitionRevision revision = base();
        revision.setStructureQuoteChar(";");
        revision.setStructureHeaderLineNumber(0);

        SourceLayout layout = FACTORY.layoutOf(revision);

        assertThat(layout.usable()).isFalse();
        assertThat(layout.quote()).isNull();
        assertThat(layout.invalidCodes()).containsExactly(SourceStructureConfigFactory.CODE_QUOTE_INVALID,
                SourceStructureConfigFactory.CODE_HEADER_LINE_INVALID);
    }

    @Test
    void anUnsupportedFormatMakesTheLayoutInvalid() {
        ImportDefinitionRevision revision = base();
        revision.setStructureFormat("FIXED");

        SourceLayout layout = FACTORY.layoutOf(revision);

        assertThat(layout.usable()).isFalse();
        assertThat(layout.invalidCodes()).containsExactly(SourceStructureConfigFactory.CODE_FORMAT_UNSUPPORTED);
    }

    @Test
    void anUnknownReferenceKindOnlyLeavesTheReferenceKindEmpty() {
        ImportDefinitionRevision revision = base();
        revision.setStructureFieldReferenceKind("FOO");

        SourceLayout layout = FACTORY.layoutOf(revision);

        assertThat(layout.usable()).isTrue();
        assertThat(layout.referenceKind()).isNull();
    }

    @Test
    void aMissingPriceFieldFailsTheStructureButNotTheLayout() {
        ImportDefinitionRevision revision = base();
        revision.setRecordBasePriceField(null);

        SourceLayout layout = FACTORY.layoutOf(revision);

        assertThat(layout.usable()).isTrue();
        assertThat(layout.charset()).isEqualTo(StandardCharsets.UTF_8);
        assertThat(layout.delimiter()).isEqualTo(';');
        assertThat(layout.quote()).isEqualTo('"');
        assertThat(layout.hasHeader()).isTrue();
        assertThat(layout.headerLineNumber()).isEqualTo(1);
        assertThat(layout.referenceKind()).isEqualTo(FieldReferenceKind.HEADER_NAME);
    }

    @Test
    void noQuoteMeansNoQuotingLikeTheStructure() {
        ImportDefinitionRevision revision = base();
        revision.setStructureQuoteChar(null);

        SourceLayout layout = FACTORY.layoutOf(revision);

        assertThat(layout.usable()).isTrue();
        assertThat(layout.quote()).isNull();
        assertThat(layout.quoteChar()).isEqualTo(' ');
        assertThat(layout.quoteChar()).isEqualTo(FACTORY.from(revision).quoteChar());
    }

    @Test
    void anAliasCharsetIsReportedByItsCanonicalName() {
        ImportDefinitionRevision revision = base();
        revision.setStructureCharset("latin1");

        SourceLayout layout = FACTORY.layoutOf(revision);

        assertThat(layout.charset()).isEqualTo(FACTORY.from(revision).charset());
        assertThat(layout.charset().name()).isEqualTo("ISO-8859-1");
    }

    private static ImportDefinitionRevision base() {
        ImportDefinitionRevision revision = new ImportDefinitionRevision(null, 1, IdentityProfileKind.THREE_PART,
                "beheerder@example.test");
        revision.setStructureDelimiter(";");
        revision.setIdentitySupplierField("LEVERANCIER");
        revision.setIdentitySupplierGroupField("GROEP");
        revision.setIdentitySupplierReferenceField("REFERENTIE");
        revision.setRecordBasePriceField("PRIJS");
        revision.setRecordCanonicalisationVersion(2);
        return revision;
    }

    // --- Toegang tot de golden table (reflectie, de tabel blijft ongewijzigd) ---------------------------------------

    /** Eén rij van de golden table, na haar mutator op een verse fixture. */
    private record GoldenRow(String id, ImportDefinitionRevision revision, String linkCurrency) {
    }

    @SuppressWarnings("unchecked")
    private static List<GoldenRow> goldenRows() throws Exception {
        Field tableField = ConfigFactoryParityTest.class.getDeclaredField("TABLE");
        tableField.setAccessible(true);
        List<?> table = (List<?>) tableField.get(null);

        Class<?> fixtureType = Class.forName(ConfigFactoryParityTest.class.getName() + "$Fixture");
        Constructor<?> newFixture = fixtureType.getDeclaredConstructor();
        newFixture.setAccessible(true);
        Field revisionField = fixtureType.getDeclaredField("revision");
        revisionField.setAccessible(true);
        Field linkCurrencyField = fixtureType.getDeclaredField("linkCurrency");
        linkCurrencyField.setAccessible(true);

        List<GoldenRow> rows = new ArrayList<>(table.size());
        for (Object row : table) {
            Method id = row.getClass().getDeclaredMethod("id");
            id.setAccessible(true);
            Method mutator = row.getClass().getDeclaredMethod("mutator");
            mutator.setAccessible(true);
            Object fixture = newFixture.newInstance();
            ((Consumer<Object>) mutator.invoke(row)).accept(fixture);
            rows.add(new GoldenRow((String) id.invoke(row), (ImportDefinitionRevision) revisionField.get(fixture),
                    (String) linkCurrencyField.get(fixture)));
        }
        return rows;
    }
}
