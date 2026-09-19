package be.dda.catalogimport.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.service.support.CsvRecordStreamer.CollectingSink;
import be.dda.catalogimport.service.support.CsvRecordStreamer.LineIssue;
import be.dda.catalogimport.service.support.HeaderExpectations.ExpectedField;
import be.dda.catalogimport.service.support.SourceStructureConfig.FieldReferenceKind;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fase 3b (ontwerp fase 3, R-STR-02/R-STR-03): de headerpositiecontrole van {@link CsvRecordStreamer}.
 * Unittest zonder Spring en zonder database.
 * <p>
 * De onderliggende businessregel: een bron die van vorm verandert, mag nooit stil een andere kolom
 * gaan inlezen. Een verschuiving is een waarschuwing — de kolom wordt op naam teruggevonden — maar een
 * andere kolom op de plaats van een identiteits-, prijs- of referentieveld blokkeert de levering,
 * want daar zou "gewoon doorgaan" betekenen dat de verkeerde waarde de sleutel of de prijs wordt.
 * <p>
 * <b>Beperking.</b> De positiecontrole geldt uitsluitend voor mapping- en filterkolommen: de bestaande
 * revisiekolommen dragen geen verwachte positie. Zonder verwachtingen blijft het gedrag exact dat van
 * fase 2 — dat wordt hier ook bewezen.
 */
class HeaderPositionControlTest {

    private static final String DATA_ROW = "ACME;G1;R1;1,50\n";

    // --- R-STR-02: verschoven kolom ---------------------------------------------------------------

    @Test
    void warnsWhenAnExpectedColumnMovedButKeepsReadingItByName() {
        // MERK stond op positie 5 en staat nu op 6; de kolom bestaat nog en wordt op naam gevonden.
        String content = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;EXTRA;MERK\n"
                + "ACME;G1;R1;1,50;x;BOSCH\n";

        CollectingSink sink = read(content, expectations(new ExpectedField("MERK", 5, false, true)));

        assertThat(sink.rows()).singleElement().satisfies(row -> {
            assertThat(row.positions().position("MERK")).isEqualTo(5);
            assertThat(row.value(5)).isEqualTo("BOSCH");
        });
        assertThat(warnings(sink, CsvRecordStreamer.CODE_HEADER_FIELD_SHIFTED)).singleElement()
                .satisfies(issue -> {
                    assertThat(issue.fieldName()).isEqualTo("MERK");
                    assertThat(issue.sourceValue()).isEqualTo("6");
                    assertThat(issue.lineNumber()).isEqualTo(1);
                    assertThat(issue.warning()).isTrue();
                    assertThat(issue.message()).contains("expected at position 5");
                });
    }

    @Test
    void staysSilentWhenTheExpectedColumnIsExactlyWhereItWasDeclared() {
        String content = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;MERK\n" + "ACME;G1;R1;1,50;BOSCH\n";

        CollectingSink sink = read(content, expectations(new ExpectedField("MERK", 5, false, true)));

        assertThat(sink.issues()).isEmpty();
        assertThat(sink.rows()).singleElement()
                .satisfies(row -> assertThat(row.positions().position("MERK")).isEqualTo(4));
    }

    // --- R-STR-02: betekeniswijziging op de verwachte positie -------------------------------------

    @Test
    void blocksWhenAnotherColumnSitsAtThePositionOfAnIdentityPriceOrReferenceField() {
        // EAN is verdwenen; op positie 5 staat nu een andere kolom. Doorgaan zou die kolom als
        // artikelreferentie inlezen.
        String content = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;ARTIKELCODE\n"
                + "ACME;G1;R1;1,50;ABC\n";

        assertThatThrownBy(() -> read(content, expectations(new ExpectedField("EAN", 5, true, true))))
                .isInstanceOf(ScreeningBlockedException.class)
                .satisfies(failure -> {
                    ScreeningBlockedException blocked = (ScreeningBlockedException) failure;
                    assertThat(blocked.getCode())
                            .isEqualTo(CsvRecordStreamer.CODE_HEADER_FIELD_SEMANTIC_CHANGE);
                    assertThat(blocked.getFieldName()).isEqualTo("EAN");
                    assertThat(blocked.getSourceValue()).isEqualTo("ARTIKELCODE");
                    assertThat(blocked.getExpectedValue()).isEqualTo("EAN");
                });
    }

    @Test
    void blocksAMissingMappedColumnThatIsNotSemanticallyCritical() {
        String content = "LEVERANCIER;GROEP;REFERENTIE;PRIJS\n" + DATA_ROW;

        assertThatThrownBy(() -> read(content, expectations(new ExpectedField("MERK", null, false, true))))
                .isInstanceOf(ScreeningBlockedException.class)
                .extracting(failure -> ((ScreeningBlockedException) failure).getCode())
                .isEqualTo(ImportMappingConfigFactory.CODE_MAPPING_SOURCE_UNRESOLVED);
    }

    /** Een filterkolom is niet verplicht in de header: het filter beslist zelf (R-FLT-03). */
    @Test
    void leavesAMissingFilterColumnToTheFilterItself() {
        String content = "LEVERANCIER;GROEP;REFERENTIE;PRIJS\n" + DATA_ROW;

        CollectingSink sink = read(content, expectations(new ExpectedField("CULTURE", null, false, false)));

        assertThat(sink.rows()).singleElement()
                .satisfies(row -> assertThat(row.positions().position("CULTURE")).isNull());
        assertThat(sink.issues()).isEmpty();
    }

    // --- R-STR-03: onbekende kolom achteraan -------------------------------------------------------

    @Test
    void warnsAboutAnUnknownTrailingColumnWhenNothingShifted() {
        String content = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;MERK;NIEUW_VELD\n"
                + "ACME;G1;R1;1,50;BOSCH;x\n";

        CollectingSink sink = read(content, expectations(new ExpectedField("MERK", 5, false, true)));

        assertThat(warnings(sink, CsvRecordStreamer.CODE_HEADER_FIELD_SHIFTED)).isEmpty();
        assertThat(warnings(sink, CsvRecordStreamer.CODE_HEADER_UNKNOWN_COLUMN)).singleElement()
                .satisfies(issue -> {
                    assertThat(issue.fieldName()).isEqualTo("NIEUW_VELD");
                    assertThat(issue.sourceValue()).isEqualTo("6");
                    assertThat(issue.warning()).isTrue();
                });
        // De levering gaat gewoon door: een uitgebreide bron is geen fout.
        assertThat(sink.rows()).hasSize(1);
    }

    /**
     * Geen enkele verwachting (een fase 2-revisie zonder mappings of filters) ⇒ geen positiecontrole
     * en geen melding over onbekende kolommen, ook niet wanneer de bron extra kolommen heeft.
     */
    @Test
    void reportsNothingWhenTheRevisionDeclaresNoExpectationsAtAll() {
        String content = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;ONBEKEND_1;ONBEKEND_2\n"
                + "ACME;G1;R1;1,50;x;y\n";

        CollectingSink sink = read(content, HeaderExpectations.none());

        assertThat(sink.issues()).isEmpty();
        assertThat(sink.rows()).hasSize(1);
    }

    // --- Helpers ------------------------------------------------------------------------------------

    private static List<LineIssue> warnings(CollectingSink sink, String code) {
        return sink.issues().stream().filter(issue -> issue.code().equals(code)).toList();
    }

    private static HeaderExpectations expectations(ExpectedField... fields) {
        return HeaderExpectations.of(List.of(fields));
    }

    private static CollectingSink read(String content, HeaderExpectations expectations) {
        SourceStructureConfig config = new SourceStructureConfig("CSV", StandardCharsets.UTF_8, ';', '"',
                true, 1, FieldReferenceKind.HEADER_NAME, null, IdentityProfileKind.THREE_PART,
                "LEVERANCIER", "GROEP", "REFERENTIE", null, "PRIJS", null, 1);
        CollectingSink sink = new CollectingSink();
        new CsvRecordStreamer().read(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                config, expectations, CsvRecordStreamer.DEFAULT_MAX_LINE_LENGTH, sink);
        return sink;
    }
}
