package be.dda.catalogimport.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.service.support.CsvRecordStreamer.CollectingSink;
import be.dda.catalogimport.service.support.CsvRecordStreamer.ReadSummary;
import be.dda.catalogimport.service.support.SourceStructureConfig.FieldReferenceKind;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Fase 2c: het leescontract van {@link CsvRecordStreamer} (design par. 8). Unittest zonder Spring
 * en zonder database: het bestandscontract moet ook los van een levering hard zijn.
 */
class CsvRecordStreamerTest {

    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS";

    // --- Configuratie wordt gevolgd, niets wordt geraden ---------------------------------------

    @Test
    void usesTheConfiguredSeparatorQuoteAndCharsetWithoutGuessing() {
        SourceStructureConfig config = config('|', '\'', true, 1, null, StandardCharsets.ISO_8859_1, null);
        String content = "LEVERANCIER|GROEP|REFERENTIE|PRIJS\n"
                + "'ACME|BV'|G1|R-é|12,50\n";

        CollectingSink sink = read(content, config);

        assertThat(sink.issues()).isEmpty();
        assertThat(sink.rows()).singleElement().satisfies(row -> {
            assertThat(row.lineNumber()).isEqualTo(2);
            assertThat(row.values()).containsExactly("ACME|BV", "G1", "R-é", "12,50");
            assertThat(row.positions().position("PRIJS")).isEqualTo(3);
        });
    }

    @Test
    void readsTheHeaderOnTheConfiguredLineAndCountsTheTitleLinesBeforeIt() {
        SourceStructureConfig config = config(';', '"', true, 3, null, StandardCharsets.UTF_8, null);
        String content = "Catalogus ACME\n"
                + "Geldig vanaf 2026-01-01\n"
                + HEADER + "\n"
                + "ACME;G1;R1;1,50\n";

        CollectingSink sink = read(content, config);
        ReadSummary summary = summary(content, config);

        assertThat(summary.prefixLineCount()).isEqualTo(2);
        assertThat(summary.rawRecordCount()).isEqualTo(1);
        assertThat(summary.columnCount()).isEqualTo(4);
        // Fysieke regelnummers: de datalijn staat op regel 4, niet op regel 1.
        assertThat(sink.rows()).singleElement().satisfies(row -> assertThat(row.lineNumber()).isEqualTo(4));
    }

    @Test
    void readsLfAndCrlfIdentically() {
        CollectingSink lf = read(HEADER + "\nACME;G1;R1;1,50\nACME;G1;R2;2,25\n", config());
        CollectingSink crlf = read(HEADER + "\r\nACME;G1;R1;1,50\r\nACME;G1;R2;2,25\r\n", config());

        assertThat(lf.issues()).isEmpty();
        assertThat(crlf.issues()).isEmpty();
        assertThat(crlf.rows()).hasSize(2);
        assertThat(crlf.rows().get(1).values()).isEqualTo(lf.rows().get(1).values());
        assertThat(crlf.rows().get(1).lineNumber()).isEqualTo(lf.rows().get(1).lineNumber()).isEqualTo(3);
    }

    @Test
    void removesAByteOrderMarkAndReportsItAsAWarningOnLineOne() {
        CollectingSink sink = read("﻿" + HEADER + "\nACME;G1;R1;1,50\n", config());

        assertThat(sink.rows()).hasSize(1);
        assertThat(sink.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo(CsvRecordStreamer.CODE_SOURCE_BOM_REMOVED);
            assertThat(issue.lineNumber()).isEqualTo(1);
            assertThat(issue.warning()).isTrue();
        });
    }

    // --- Bestandscontract: blokkeert de levering -----------------------------------------------

    @Test
    void blocksAnEmptyFile() {
        assertThatThrownBy(() -> read("", config()))
                .isInstanceOf(ScreeningBlockedException.class)
                .extracting(failure -> ((ScreeningBlockedException) failure).getCode())
                .isEqualTo(CsvRecordStreamer.CODE_SOURCE_FILE_EMPTY);
    }

    @Test
    void readsAHeaderOnlyFileWithoutRecordsSoTheServiceCanBlockIt() {
        CollectingSink sink = read(HEADER + "\n", config());

        assertThat(sink.rows()).isEmpty();
        assertThat(sink.issues()).isEmpty();
        assertThat(summary(HEADER + "\n", config()).rawRecordCount()).isZero();
    }

    @Test
    void blocksWhenADeclaredFieldIsMissingFromTheHeader() {
        assertThatThrownBy(() -> read("LEVERANCIER;GROEP;REFERENTIE;BEDRAG\nACME;G1;R1;1,50\n", config()))
                .isInstanceOf(ScreeningBlockedException.class)
                .extracting(failure -> ((ScreeningBlockedException) failure).getCode())
                .isEqualTo(CsvRecordStreamer.CODE_HEADER_FIELD_MISSING + ":PRIJS");
    }

    @Test
    void matchesHeaderNamesTrimmedAndCaseInsensitively() {
        CollectingSink sink = read(" leverancier ; Groep;REFERENTIE ;prijs\nACME;G1;R1;1,50\n", config());

        assertThat(sink.rows()).singleElement()
                .satisfies(row -> assertThat(row.positions().position("PRIJS")).isEqualTo(3));
    }

    @Test
    void blocksWhenTheHeaderHasAnotherColumnCountThanTheRevisionDeclares() {
        SourceStructureConfig config = config(';', '"', true, 1, 5, StandardCharsets.UTF_8, null);

        assertThatThrownBy(() -> read(HEADER + "\nACME;G1;R1;1,50\n", config))
                .isInstanceOf(ScreeningBlockedException.class)
                .extracting(failure -> ((ScreeningBlockedException) failure).getCode())
                .isEqualTo(CsvRecordStreamer.CODE_HEADER_COLUMN_COUNT_MISMATCH);
    }

    @Test
    void blocksWhenTheFileEndsBeforeTheConfiguredHeaderLine() {
        SourceStructureConfig config = config(';', '"', true, 3, null, StandardCharsets.UTF_8, null);

        assertThatThrownBy(() -> read("Titel\n" + HEADER + "\n", config))
                .isInstanceOf(ScreeningBlockedException.class)
                .extracting(failure -> ((ScreeningBlockedException) failure).getCode())
                .isEqualTo(CsvRecordStreamer.CODE_HEADER_LINE_MISSING);
    }

    // --- Rijfouten: verwerpen enkel die regel --------------------------------------------------

    @Test
    void rejectsRowsWithTooFewOrTooManyColumnsAndNeverPadsOrTruncatesThem() {
        String content = HEADER + "\n"
                + "ACME;G1;R1;1,50\n"
                + "ACME;G1;R2\n"
                + "ACME;G1;R3;2,50;EXTRA\n"
                + "ACME;G1;R4;3,50\n";

        CollectingSink sink = read(content, config());

        assertThat(sink.rows()).hasSize(2);
        assertThat(sink.rows()).extracting(row -> row.values().get(2)).containsExactly("R1", "R4");
        assertThat(sink.issues()).hasSize(2)
                .allSatisfy(issue -> assertThat(issue.code())
                        .isEqualTo(CsvRecordStreamer.CODE_ROW_COLUMN_COUNT_MISMATCH))
                .extracting(CsvRecordStreamer.LineIssue::lineNumber)
                .containsExactly(3L, 4L);
        assertThat(summary(content, config()).rawRecordCount()).isEqualTo(4);
    }

    @Test
    void rejectsOnlyTheRowWithAnUnclosedQuote() {
        String content = HEADER + "\n"
                + "\"ACME;G1;R1;1,50\n"
                + "ACME;G1;R2;2,25\n";

        CollectingSink sink = read(content, config());

        assertThat(sink.rows()).singleElement()
                .satisfies(row -> assertThat(row.values().get(2)).isEqualTo("R2"));
        assertThat(sink.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo(ImportValueRules.CODE_CSV_UNCLOSED_QUOTE);
            assertThat(issue.lineNumber()).isEqualTo(2);
            assertThat(issue.warning()).isFalse();
        });
    }

    @Test
    void readsADoubledQuoteCharacterAsOneLiteralCharacter() {
        CollectingSink sink = read(HEADER + "\n\"ACME \"\"BV\"\"\";G1;R1;1,50\n", config());

        assertThat(sink.issues()).isEmpty();
        assertThat(sink.rows()).singleElement()
                .satisfies(row -> assertThat(row.values().get(0)).isEqualTo("ACME \"BV\""));
    }

    @Test
    void rejectsALineThatExceedsTheConfiguredMaximumLength() {
        String longReference = "R".repeat(60);
        String content = HEADER + "\n"
                + "ACME;G1;" + longReference + ";1,50\n"
                + "ACME;G1;R2;2,25\n";

        CollectingSink sink = new CollectingSink();
        new CsvRecordStreamer().read(stream(content, config()), config(), 40, sink);

        assertThat(sink.rows()).singleElement()
                .satisfies(row -> assertThat(row.values().get(2)).isEqualTo("R2"));
        assertThat(sink.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo(CsvRecordStreamer.CODE_ROW_TOO_LONG);
            assertThat(issue.lineNumber()).isEqualTo(2);
            assertThat(issue.sourceValue()).isNotNull();
        });
    }

    @Test
    void skipsAndCountsEmptyTrailingLines() {
        String content = HEADER + "\nACME;G1;R1;1,50\n\n\n";

        ReadSummary summary = summary(content, config());

        assertThat(summary.rawRecordCount()).isEqualTo(1);
        assertThat(summary.skippedBlankLineCount()).isEqualTo(2);
        assertThat(summary.physicalLineCount()).isEqualTo(4);
    }

    // --- Helpers -------------------------------------------------------------------------------

    private static CollectingSink read(String content, SourceStructureConfig config) {
        CollectingSink sink = new CollectingSink();
        new CsvRecordStreamer().read(stream(content, config), config, CsvRecordStreamer.DEFAULT_MAX_LINE_LENGTH,
                sink);
        return sink;
    }

    private static ReadSummary summary(String content, SourceStructureConfig config) {
        return new CsvRecordStreamer().read(stream(content, config), config,
                CsvRecordStreamer.DEFAULT_MAX_LINE_LENGTH, new CollectingSink());
    }

    private static ByteArrayInputStream stream(String content, SourceStructureConfig config) {
        return new ByteArrayInputStream(content.getBytes(config.charset()));
    }

    private static SourceStructureConfig config() {
        return config(';', '"', true, 1, null, StandardCharsets.UTF_8, null);
    }

    private static SourceStructureConfig config(char delimiter, Character quote, boolean hasHeader,
                                                int headerLineNumber, Integer expectedColumnCount,
                                                Charset charset, String descriptionField) {
        return new SourceStructureConfig("CSV", charset, delimiter, quote, hasHeader, headerLineNumber,
                FieldReferenceKind.HEADER_NAME, expectedColumnCount, IdentityProfileKind.THREE_PART,
                "LEVERANCIER", "GROEP", "REFERENTIE", null, "PRIJS", descriptionField, 1);
    }
}
