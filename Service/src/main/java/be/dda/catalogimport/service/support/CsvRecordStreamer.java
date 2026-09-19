package be.dda.catalogimport.service.support;

import be.dda.catalogimport.service.support.SourceStructureConfig.FieldReferenceKind;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
     * Leest het volledige bestand en meldt elke datalijn of regelfout aan de sink.
     *
     * @param maxLineLength maximale lengte van een datalijn in tekens
     * @throws ScreeningBlockedException bij een contract-/structuurfout die de levering blokkeert
     * @throws UncheckedIOException      bij een lees- of decodeerfout (technische fout)
     */
    public ReadSummary read(InputStream source, SourceStructureConfig config, int maxLineLength, Sink sink) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(source, config.charset()),
                READ_BUFFER_CHARS);
        State state = new State(config, maxLineLength, sink);
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
        private final int maxLineLength;
        private final Sink sink;

        private long physicalLineCount;
        private long prefixLineCount;
        private long skippedBlankLineCount;
        private long rawRecordCount;
        private int columnCount = -1;
        private boolean headerDone;
        private SourceFieldPositions positions;

        private State(SourceStructureConfig config, int maxLineLength, Sink sink) {
            this.config = config;
            this.maxLineLength = maxLineLength;
            this.sink = sink;
            if (!config.hasHeader()) {
                this.headerDone = true;
                this.positions = columnIndexPositions(config);
                if (config.expectedColumnCount() != null) {
                    this.columnCount = config.expectedColumnCount();
                    verifyPositionsFitColumnCount();
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
                positions = columnIndexPositions(config);
                verifyPositionsFitColumnCount();
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
            positions = new SourceFieldPositions(resolved);
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

        private void verifyPositionsFitColumnCount() {
            if (columnCount < 0) {
                return;
            }
            for (Map.Entry<String, Integer> entry : positions.byReference().entrySet()) {
                if (entry.getValue() >= columnCount) {
                    throw new ScreeningBlockedException(CODE_COLUMN_INDEX_OUT_OF_RANGE,
                            "Declared column index " + entry.getKey() + " is beyond the " + columnCount
                                    + " columns in the source");
                }
            }
        }

        private static SourceFieldPositions columnIndexPositions(SourceStructureConfig config) {
            Map<String, Integer> resolved = new LinkedHashMap<>();
            for (String field : config.declaredFields()) {
                try {
                    resolved.put(field, Integer.parseInt(field.trim()) - 1);
                } catch (NumberFormatException notAnIndex) {
                    throw new ScreeningBlockedException(SourceStructureConfigFactory.CODE_FIELD_REFERENCE_INVALID,
                            "Field reference " + field + " must be a 1-based column index");
                }
            }
            return new SourceFieldPositions(resolved);
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
