package be.dda.catalogimport.service.support;

import be.dda.catalogimport.service.support.SourceStructureConfig.FieldReferenceKind;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Leest een CSV-bronbestand regel per regel en geeft elke datalijn als {@link ParsedRow} door
 * (design par. 8). Pure klasse: geen Spring, geen database, geen archiefkennis — dat maakt het
 * volledige leescontract in een unittest afdwingbaar.
 * <p>
 * <b>Streaming.</b> Het bestand wordt nooit in het geheugen geladen; er is altijd hoogstens één
 * regel tegelijk in het geheugen. De {@link InputStream} wordt niet gesloten: dat blijft de
 * verantwoordelijkheid van de aanroeper.
 * <p>
 * <b>Harde leesregels.</b>
 * <ul>
 *   <li>De encoding komt uit de configuratie; er wordt <b>nooit</b> geraden. Een UTF-8/Unicode-BOM
 *       wordt verwijderd én gemeld ({@link #CODE_SOURCE_BOM_REMOVED}, severity WARNING op regel 1),
 *       zodat een onzichtbaar teken nooit stil in de eerste kolomnaam terechtkomt.</li>
 *   <li>Eén fysieke lijn is één record (aanname A2). Een aanhalingsteken dat op dezelfde lijn niet
 *       gesloten wordt is een rijfout ({@code CSV_UNCLOSED_QUOTE}); een record met een echt
 *       ingebed regeleinde wordt niet ondersteund en wordt dus nooit half ingelezen.</li>
 *   <li>Regelnummers zijn fysiek en 1-gebaseerd, inclusief prefixregels en header, zodat een
 *       melding rechtstreeks naar de regel in het bronbestand verwijst.</li>
 *   <li>Een datalijn met een ander kolomaantal dan de header/het contract wordt verworpen met
 *       {@link #CODE_ROW_COLUMN_COUNT_MISMATCH}; er wordt <b>nooit</b> aangevuld of afgekapt.</li>
 *   <li>Een volledig lege regel wordt overgeslagen en geteld
 *       ({@link ReadSummary#skippedBlankLineCount()}); ze draagt geen waarde, dus dat wijzigt geen
 *       data. Een regel met enkel spaties is wél een (foute) datalijn.</li>
 * </ul>
 * <b>Blokkerend versus rijfout.</b> Alles wat het bestandscontract raakt (leeg bestand, ontbrekende
 * header, ontbrekend headerveld, verkeerd kolomaantal in de header) gooit een
 * {@link ScreeningBlockedException} en stopt het lezen; alles wat één regel raakt, komt als
 * {@link LineIssue} bij de {@link Sink} terecht en het lezen gaat door.
 * <p>
 * <b>Headerpositiecontrole (fase 3, R-STR-02/R-STR-03).</b> Krijgt de streamer
 * {@link HeaderExpectations} mee, dan controleert hij ook de kolommen van de veldmapping en de
 * recordfilters: een verschoven kolom is een waarschuwing (ze wordt op naam teruggevonden), een
 * andere kolom op de verwachte positie van een identiteits-, prijs- of referentieveld blokkeert, en
 * een onbekende kolom achteraan is een waarschuwing. Zonder verwachtingen blijft het gedrag exact dat
 * van fase 2: de bestaande revisiekolommen dragen geen verwachte positie.
 */
public final class CsvRecordStreamer {

    /** Een byte-order mark stond vooraan en is verwijderd (WARNING, regel 1). */
    public static final String CODE_SOURCE_BOM_REMOVED = "SOURCE_BOM_REMOVED";
    /** Het bronbestand bevat geen enkele regel. */
    public static final String CODE_SOURCE_FILE_EMPTY = "SOURCE_FILE_EMPTY";
    /** Het bestand eindigt vóór de geconfigureerde headerregel. */
    public static final String CODE_HEADER_LINE_MISSING = "HEADER_LINE_MISSING";
    /** Prefix van de blokkeercode; de volledige code is {@code HEADER_FIELD_MISSING:<veld>}. */
    public static final String CODE_HEADER_FIELD_MISSING = "HEADER_FIELD_MISSING";
    /** Twee headerkolommen dragen dezelfde naam; de mapping zou dubbelzinnig zijn. */
    public static final String CODE_HEADER_DUPLICATE_FIELD = "HEADER_DUPLICATE_FIELD";
    /** De header heeft een ander kolomaantal dan de revisie declareert. */
    public static final String CODE_HEADER_COLUMN_COUNT_MISMATCH = "HEADER_COLUMN_COUNT_MISMATCH";
    /** Een gedeclareerde kolomindex valt buiten het werkelijke kolomaantal. */
    public static final String CODE_COLUMN_INDEX_OUT_OF_RANGE = "CONFIG_COLUMN_INDEX_OUT_OF_RANGE";
    /** Een verwachte kolom staat op een andere positie; ze is op naam teruggevonden (WARNING). */
    public static final String CODE_HEADER_FIELD_SHIFTED = "HEADER_FIELD_SHIFTED";
    /** Op de verwachte positie van een identiteits-, prijs- of referentieveld staat een andere kolom. */
    public static final String CODE_HEADER_FIELD_SEMANTIC_CHANGE = "HEADER_FIELD_SEMANTIC_CHANGE";
    /** Achteraan staat een kolom die de definitie niet kent; niets verschoof (WARNING). */
    public static final String CODE_HEADER_UNKNOWN_COLUMN = "HEADER_UNKNOWN_COLUMN";
    /** Een datalijn heeft een ander kolomaantal dan het contract. */
    public static final String CODE_ROW_COLUMN_COUNT_MISMATCH = "ROW_COLUMN_COUNT_MISMATCH";
    /** Een datalijn is langer dan {@code catalogimport.screening.max-line-length}. */
    public static final String CODE_ROW_TOO_LONG = "ROW_TOO_LONG";

    /** Standaardgrens voor de regellengte (design par. 8). */
    public static final int DEFAULT_MAX_LINE_LENGTH = 100_000;

    private static final char BOM = '﻿';
    private static final int READ_BUFFER_CHARS = 64 * 1024;
    private static final int SOURCE_VALUE_SAMPLE = 200;

    /** Eén datalijn, gesplitst in kolommen, met de veldposities van dit bestand. */
    public record ParsedRow(long lineNumber, List<String> values, SourceFieldPositions positions) {

        public ParsedRow {
            values = List.copyOf(values);
        }

        /** De ruwe kolomwaarde op een 0-gebaseerde positie, of {@code null} buiten de rij. */
        public String value(int position) {
            return position >= 0 && position < values.size() ? values.get(position) : null;
        }
    }

    /** Een probleem op één fysieke regel; {@code warning} betekent: informatief, geen verwerping. */
    public record LineIssue(long lineNumber, String code, String fieldName, String sourceValue, String message,
                            boolean warning) {
    }

    /** Wat er gelezen is. Tellers zijn exact; er wordt nooit geschat. */
    public record ReadSummary(long physicalLineCount, long prefixLineCount, long skippedBlankLineCount,
                              long rawRecordCount, int columnCount) {
    }

    /** Ontvanger van de leesresultaten; implementaties mogen per microbatch wegschrijven. */
    public interface Sink {

        /** Een datalijn met het juiste kolomaantal. */
        void record(ParsedRow row);

        /** Een probleem op één regel (rijfout of waarschuwing). */
        void issue(LineIssue issue);
    }

    /**
     * Leest het volledige bestand en meldt elke datalijn of regelfout aan de sink, zonder
     * headerpositiecontrole. Gelijk aan {@link #read(InputStream, SourceStructureConfig,
     * HeaderExpectations, int, Sink)} met {@link HeaderExpectations#none()}.
     *
     * @param maxLineLength maximale lengte van een datalijn in tekens
     * @throws ScreeningBlockedException bij een contract-/structuurfout die de levering blokkeert
     * @throws UncheckedIOException      bij een lees- of decodeerfout (technische fout)
     */
    public ReadSummary read(InputStream source, SourceStructureConfig config, int maxLineLength, Sink sink) {
        return read(source, config, HeaderExpectations.none(), maxLineLength, sink);
    }

    /**
     * Leest het volledige bestand, meldt elke datalijn of regelfout aan de sink en controleert
     * daarbij de kolommen die de veldmapping en de recordfilters verwachten (R-STR-02/R-STR-03).
     * <p>
     * De opgeloste posities van die verwachte kolommen komen mee in
     * {@link ParsedRow#positions()}, zodat de filterevaluatie en (vanaf bouwstap 3c) de mapping ze
     * per regel kunnen opzoeken zonder de header opnieuw te lezen.
     *
     * @param expectations de verwachte kolommen; {@link HeaderExpectations#none()} schakelt de
     *                     positiecontrole volledig uit en levert exact het fase 2-gedrag op
     * @throws ScreeningBlockedException bij een contract-/structuurfout die de levering blokkeert
     * @throws UncheckedIOException      bij een lees- of decodeerfout (technische fout)
     */
    public ReadSummary read(InputStream source, SourceStructureConfig config,
                            HeaderExpectations expectations, int maxLineLength, Sink sink) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(source, config.charset()),
                READ_BUFFER_CHARS);
        State state = new State(config, expectations, maxLineLength, sink);
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                state.physicalLineCount++;
                if (state.physicalLineCount == 1) {
                    line = state.stripByteOrderMark(line);
                }
                state.consume(line);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read the delivery file", failure);
        }
        state.verifyEndOfFile();
        return new ReadSummary(state.physicalLineCount, state.prefixLineCount, state.skippedBlankLineCount,
                state.rawRecordCount, state.columnCount);
    }

    /** Leesstand van één bestand; bewust package-private en niet herbruikbaar over bestanden heen. */
    private static final class State {

        private final SourceStructureConfig config;
        private final HeaderExpectations expectations;
        private final int maxLineLength;
        private final Sink sink;

        /** De gedeclareerde revisievelden; ontbreken is altijd blokkerend. */
        private Map<String, Integer> declared = new LinkedHashMap<>();
        /** De kolommen van mappings en filters; een ontbrekende filterkolom is niet blokkerend. */
        private Map<String, Integer> expected = new LinkedHashMap<>();

        private long physicalLineCount;
        private long prefixLineCount;
        private long skippedBlankLineCount;
        private long rawRecordCount;
        private int columnCount = -1;
        private boolean headerDone;
        private SourceFieldPositions positions;

        private State(SourceStructureConfig config, HeaderExpectations expectations, int maxLineLength,
                      Sink sink) {
            this.config = config;
            this.expectations = expectations;
            this.maxLineLength = maxLineLength;
            this.sink = sink;
            if (!config.hasHeader()) {
                this.headerDone = true;
                this.declared = columnIndexPositions(config.declaredFields());
                this.expected = columnIndexPositions(expectations.references());
                buildPositions();
                if (config.expectedColumnCount() != null) {
                    this.columnCount = config.expectedColumnCount();
                    verifyPositionsFitColumnCount();
                    buildPositions();
                }
            }
        }

        private String stripByteOrderMark(String line) {
            if (line.isEmpty() || line.charAt(0) != BOM) {
                return line;
            }
            sink.issue(new LineIssue(1, CODE_SOURCE_BOM_REMOVED, null, null,
                    "A byte order mark was removed from the first line", true));
            return line.substring(1);
        }

        private void consume(String line) {
            if (!headerDone) {
                if (physicalLineCount < config.headerLineNumber()) {
                    prefixLineCount++;
                    return;
                }
                readHeader(line);
                headerDone = true;
                return;
            }
            readDataLine(line);
        }

        private void readHeader(String line) {
            String[] header;
            try {
                header = ImportValueRules.parseCsvLine(line, config.delimiter(), config.quoteChar());
            } catch (ImportValueException unreadable) {
                throw new ScreeningBlockedException(unreadable.getCode(),
                        "Header line " + physicalLineCount + " cannot be parsed: " + unreadable.getMessage());
            }
            if (config.expectedColumnCount() != null && header.length != config.expectedColumnCount()) {
                throw new ScreeningBlockedException(CODE_HEADER_COLUMN_COUNT_MISMATCH, null,
                        String.valueOf(header.length), String.valueOf(config.expectedColumnCount()),
                        "Header has " + header.length + " columns but the revision declares "
                                + config.expectedColumnCount());
            }
            columnCount = header.length;

            if (config.fieldReferenceKind() == FieldReferenceKind.COLUMN_INDEX) {
                declared = columnIndexPositions(config.declaredFields());
                expected = columnIndexPositions(expectations.references());
                buildPositions();
                verifyPositionsFitColumnCount();
                buildPositions();
                return;
            }

            Map<String, Integer> byName = new HashMap<>();
            for (int i = 0; i < header.length; i++) {
                String name = normalise(header[i]);
                Integer previous = byName.putIfAbsent(name, i);
                if (previous != null) {
                    throw new ScreeningBlockedException(CODE_HEADER_DUPLICATE_FIELD,
                            "Header column '" + header[i].trim() + "' occurs more than once (positions "
                                    + (previous + 1) + " and " + (i + 1) + ")");
                }
            }
            Map<String, Integer> resolved = new LinkedHashMap<>();
            for (String field : config.declaredFields()) {
                Integer position = byName.get(normalise(field));
                if (position == null) {
                    throw new ScreeningBlockedException(CODE_HEADER_FIELD_MISSING + ":" + field, field,
                            null, field, "Declared field '" + field + "' is missing from the header on line "
                                    + physicalLineCount);
                }
                resolved.put(field, position);
            }
            declared = resolved;
            expected = resolveExpectedColumns(byName, header);
            buildPositions();
        }

        /**
         * R-STR-02/R-STR-03: de kolommen van de veldmapping en de recordfilters worden op naam
         * teruggevonden; hun verwachte positie dient enkel als controle.
         * <ul>
         *   <li>gevonden op een andere positie ⇒ {@link #CODE_HEADER_FIELD_SHIFTED} (waarschuwing, de
         *       levering gaat door en de naam blijft leidend);</li>
         *   <li>niet gevonden terwijl er een andere kolom staat op de verwachte positie van een
         *       identiteits-, prijs- of referentieveld ⇒ {@link #CODE_HEADER_FIELD_SEMANTIC_CHANGE}
         *       (blokkerend: doorgaan zou de verkeerde kolom als sleutel of prijs inlezen);</li>
         *   <li>niet gevonden terwijl een mapping de kolom nodig heeft ⇒
         *       {@code CONFIG_MAPPING_SOURCE_UNRESOLVED} (blokkerend);</li>
         *   <li>niet gevonden terwijl enkel een filter de kolom gebruikt ⇒ niets hier; dat filter
         *       heeft zijn eigen {@code missing_column_behaviour} (R-FLT-03).</li>
         * </ul>
         */
        private Map<String, Integer> resolveExpectedColumns(Map<String, Integer> byName, String[] header) {
            Map<String, Integer> resolved = new LinkedHashMap<>();
            if (expectations.isEmpty()) {
                return resolved;
            }
            boolean shifted = false;
            for (HeaderExpectations.ExpectedField field : expectations.fields()) {
                Integer position = byName.get(normalise(field.reference()));
                if (position == null) {
                    reportMissingExpectedColumn(field, header);
                    continue;
                }
                resolved.put(field.reference(), position);
                Integer expectedPosition = field.expectedPosition();
                if (expectedPosition != null && expectedPosition != position + 1) {
                    shifted = true;
                    sink.issue(new LineIssue(physicalLineCount, CODE_HEADER_FIELD_SHIFTED, field.reference(),
                            String.valueOf(position + 1),
                            "Column '" + field.reference() + "' was expected at position " + expectedPosition
                                    + " but is at position " + (position + 1)
                                    + "; it is read by name, not by position", true));
                }
            }
            if (!shifted) {
                reportUnknownTrailingColumns(header, resolved);
            }
            return resolved;
        }

        private void reportMissingExpectedColumn(HeaderExpectations.ExpectedField field, String[] header) {
            Integer expectedPosition = field.expectedPosition();
            if (field.semanticallyCritical() && expectedPosition != null) {
                String found = expectedPosition >= 1 && expectedPosition <= header.length
                        ? header[expectedPosition - 1].trim() : null;
                throw new ScreeningBlockedException(CODE_HEADER_FIELD_SEMANTIC_CHANGE, field.reference(),
                        found, field.reference(),
                        "Position " + expectedPosition + " carries column '" + found + "' instead of the "
                                + "expected identity, price or reference column '" + field.reference()
                                + "', which is not in this header at all");
            }
            if (field.requiredInHeader()) {
                throw new ScreeningBlockedException(
                        ImportMappingConfigFactory.CODE_MAPPING_SOURCE_UNRESOLVED, field.reference(), null,
                        field.reference(), "Mapped column '" + field.reference() + "' is missing from the "
                                + "header on line " + physicalLineCount);
            }
        }

        /**
         * R-STR-03: een extra kolom achteraan die de definitie niet kent, zonder dat er iets verschoven
         * is. Informatief — de bron is uitgebreid — maar nooit stil: een nieuwe kolom kan een nieuw
         * veld zijn dat de beheerder wil mappen.
         */
        private void reportUnknownTrailingColumns(String[] header, Map<String, Integer> expectedPositions) {
            Set<String> known = new HashSet<>();
            int lastKnown = -1;
            for (String field : config.declaredFields()) {
                known.add(normalise(field));
            }
            for (Map.Entry<String, Integer> entry : declared.entrySet()) {
                lastKnown = Math.max(lastKnown, entry.getValue());
            }
            for (Map.Entry<String, Integer> entry : expectedPositions.entrySet()) {
                known.add(normalise(entry.getKey()));
                lastKnown = Math.max(lastKnown, entry.getValue());
            }
            for (int i = lastKnown + 1; i < header.length; i++) {
                if (known.contains(normalise(header[i]))) {
                    continue;
                }
                sink.issue(new LineIssue(physicalLineCount, CODE_HEADER_UNKNOWN_COLUMN, header[i].trim(),
                        String.valueOf(i + 1), "Column '" + header[i].trim() + "' at position " + (i + 1)
                        + " is not used by this import definition", true));
            }
        }

        /** De gedeclareerde velden plus de verwachte kolommen die binnen dit bestand passen. */
        private void buildPositions() {
            Map<String, Integer> merged = new LinkedHashMap<>(declared);
            for (Map.Entry<String, Integer> entry : expected.entrySet()) {
                if (columnCount < 0 || entry.getValue() < columnCount) {
                    merged.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
            positions = new SourceFieldPositions(merged);
        }

        private void readDataLine(String line) {
            if (line.isEmpty()) {
                skippedBlankLineCount++;
                return;
            }
            rawRecordCount++;
            if (line.length() > maxLineLength) {
                sink.issue(new LineIssue(physicalLineCount, CODE_ROW_TOO_LONG, null, sample(line),
                        "Line length " + line.length() + " exceeds the configured maximum " + maxLineLength,
                        false));
                return;
            }
            String[] values;
            try {
                values = ImportValueRules.parseCsvLine(line, config.delimiter(), config.quoteChar());
            } catch (ImportValueException unreadable) {
                sink.issue(new LineIssue(physicalLineCount, unreadable.getCode(), unreadable.getField(),
                        sample(line), unreadable.getMessage(), false));
                return;
            }
            if (columnCount < 0) {
                // Bestand zonder header en zonder gedeclareerd kolomaantal: de eerste datalijn zet het contract.
                columnCount = values.length;
                verifyPositionsFitColumnCount();
                buildPositions();
            }
            if (values.length != columnCount) {
                sink.issue(new LineIssue(physicalLineCount, CODE_ROW_COLUMN_COUNT_MISMATCH, null, sample(line),
                        "Line has " + values.length + " columns but " + columnCount + " were expected", false));
                return;
            }
            sink.record(new ParsedRow(physicalLineCount, List.of(values), positions));
        }

        private void verifyEndOfFile() {
            if (physicalLineCount == 0) {
                throw new ScreeningBlockedException(CODE_SOURCE_FILE_EMPTY, "The delivery file contains no lines");
            }
            if (!headerDone) {
                throw new ScreeningBlockedException(CODE_HEADER_LINE_MISSING,
                        "The file ends at line " + physicalLineCount + " before the configured header line "
                                + config.headerLineNumber());
            }
        }

        /**
         * Alleen de <b>gedeclareerde</b> revisievelden blokkeren wanneer hun kolomindex buiten het
         * bestand valt. Een verwachte kolom van een filter die buiten het bestand valt wordt uit
         * {@link #positions} weggelaten; dat filter beslist dan via zijn eigen
         * {@code missing_column_behaviour} (R-FLT-03).
         */
        private void verifyPositionsFitColumnCount() {
            if (columnCount < 0) {
                return;
            }
            for (Map.Entry<String, Integer> entry : declared.entrySet()) {
                if (entry.getValue() >= columnCount) {
                    throw new ScreeningBlockedException(CODE_COLUMN_INDEX_OUT_OF_RANGE,
                            "Declared column index " + entry.getKey() + " is beyond the " + columnCount
                                    + " columns in the source");
                }
            }
        }

        private static Map<String, Integer> columnIndexPositions(List<String> references) {
            Map<String, Integer> resolved = new LinkedHashMap<>();
            for (String field : references) {
                try {
                    resolved.put(field, Integer.parseInt(field.trim()) - 1);
                } catch (NumberFormatException notAnIndex) {
                    throw new ScreeningBlockedException(SourceStructureConfigFactory.CODE_FIELD_REFERENCE_INVALID,
                            "Field reference " + field + " must be a 1-based column index");
                }
            }
            return resolved;
        }

        private static String normalise(String headerName) {
            return headerName == null ? "" : headerName.trim().toLowerCase(Locale.ROOT);
        }

        private static String sample(String line) {
            return line.length() <= SOURCE_VALUE_SAMPLE ? line : line.substring(0, SOURCE_VALUE_SAMPLE);
        }
    }

    /** Hulpje voor tests en aanroepers die de rijen gewoon willen verzamelen. */
    public static final class CollectingSink implements Sink {

        private final List<ParsedRow> rows = new ArrayList<>();
        private final List<LineIssue> issues = new ArrayList<>();

        @Override
        public void record(ParsedRow row) {
            rows.add(row);
        }

        @Override
        public void issue(LineIssue issue) {
            issues.add(issue);
        }

        public List<ParsedRow> rows() {
            return rows;
        }

        public List<LineIssue> issues() {
            return issues;
        }
    }
}
