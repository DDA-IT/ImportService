package be.dda.catalogimport.service.support;

import be.dda.catalogimport.service.support.SourceStructureConfig.FieldReferenceKind;
import java.util.ArrayList;
import java.util.List;

/**
 * Kolomvoorbeeld van de proefinlezing (EB5/G3, beslissingslog 2026-10-23 "Editor-API-gaten"): de headerkolommen en de
 * eerste datalijnen van een bestand, gesplitst met de leesindeling van de revisie, zodat de bewerker een kolom aan een
 * veld of filter kan koppelen. Die koppeling doet de frontend; hier staat enkel wat er in het bestand staat.
 * <p>
 * <b>Businessgedrag.</b>
 * <ul>
 *   <li>Enkel het begin van het bestand (de eerste {@code preview-max-bytes} bytes) wordt bekeken. Een regel die op
 *       die grens afgebroken is, wordt weggelaten, nooit half getoond ({@code rowsTruncated}).</li>
 *   <li>Dezelfde leesregels als {@link CsvRecordStreamer}: de tekenset uit de revisie (nooit geraden), een BOM vooraan
 *       weg, regeleinden {@code \n}, {@code \r\n} en {@code \r}, prefixregels vóór de header overgeslagen, lege regels
 *       overgeslagen, één fysieke regel is één record, splitsen met {@link ImportValueRules#parseCsvLine}.</li>
 *   <li>Een datalijn die de streamer niet zou parsen (te lang, aanhalingsteken niet gesloten) is {@code unreadable}
 *       zonder cellen. Er wordt nooit aangevuld, verschoven of gecorrigeerd; een cel wordt enkel ingekort
 *       ({@link #CELL_MAX_LENGTH}).</li>
 *   <li>Onafhankelijk van het verdict: ook bij een configuratiefout of een headerblokkade staan de kolommen er, zolang de
 *       leesindeling zelf bruikbaar is.</li>
 * </ul>
 * Pure functie: hetzelfde voorvoegsel met dezelfde indeling en grenzen geeft hetzelfde resultaat. Niets wordt bewaard
 * of gelogd.
 *
 * @param status           of er kolommen te tonen zijn, en anders waarom niet
 * @param layout           de leesindeling waarmee gesplitst is (zoals geconfigureerd op de revisie)
 * @param headerLineNumber het fysieke regelnummer van de gebruikte headerregel, of {@code null} zonder header of
 *                         wanneer de header niet in het voorbeeld ligt
 * @param columns          de kolommen: bij een header de headercellen, zonder header de posities tot het grootste
 *                         aantal cellen in de getoonde regels
 * @param rows             de eerste datalijnen (lege regels niet meegeteld)
 * @param columnsTruncated er zijn meer kolommen (in de header of in een getoonde regel) dan getoond
 * @param rowsTruncated    het bestand bevat (mogelijk) meer datalijnen dan getoond
 */
public record ColumnPreview(Status status, Layout layout, Integer headerLineNumber, List<Column> columns,
                            List<Row> rows, boolean columnsTruncated, boolean rowsTruncated) {

    /** Een cel (of headernaam) wordt op zoveel tekens gekapt, zoals {@code rawValues} van de voorbeeldrijen. */
    public static final int CELL_MAX_LENGTH = 200;

    private static final char BOM = '﻿';

    public enum Status {
        /** Kolommen en regels zijn bepaald. */
        AVAILABLE,
        /** De leesindeling van de revisie is zelf ongeldig (formaat, tekenset, scheidingsteken, aanhalingsteken, header). */
        LAYOUT_INVALID,
        /** Het bestand is leeg (0 bytes). */
        FILE_EMPTY,
        /** De geconfigureerde headerregel valt na het einde van het bestand of buiten het bekeken begin. */
        HEADER_BEYOND_PREVIEW
    }

    /**
     * De leesindeling zoals ze in het antwoord staat; {@code null} voor een onderdeel dat ongeldig is.
     *
     * @param quoteChar {@code null} = geen quoting (of ongeldig, dan is de status {@link Status#LAYOUT_INVALID})
     */
    public record Layout(String charset, String delimiter, String quoteChar, boolean hasHeader, int headerLineNumber,
                         FieldReferenceKind referenceKind) {

        public static Layout of(SourceLayout layout) {
            return new Layout(layout.charset() == null ? null : layout.charset().name(),
                    layout.delimiter() == null ? null : String.valueOf(layout.delimiter()),
                    layout.quote() == null ? null : String.valueOf(layout.quote()), layout.hasHeader(),
                    layout.headerLineNumber(), layout.referenceKind());
        }
    }

    /** @param header de getrimde headernaam (zoals {@code header.foundColumns}), of {@code null} zonder header */
    public record Column(int position, String header) {
    }

    /** @param cells de cellen zoals gelezen (enkel gekapt), of {@code null} wanneer de regel {@code unreadable} is */
    public record Row(long lineNumber, List<String> cells, boolean unreadable) {
    }

    /**
     * De grenzen van het voorbeeld.
     *
     * @param maxRows       hoogstens zoveel datalijnen
     * @param maxColumns    hoogstens zoveel kolommen (en cellen per regel)
     * @param maxLineLength een langere datalijn is {@code unreadable} ({@code catalogimport.screening.max-line-length})
     */
    public record Limits(int maxRows, int maxColumns, int maxLineLength) {
    }

    /**
     * Bouwt het voorbeeld uit het begin van het bestand.
     *
     * @param prefix   de eerste bytes van het bestand (hoogstens {@code preview-max-bytes})
     * @param complete {@code true} wanneer {@code prefix} het volledige bestand is
     * @param layout   de leesindeling van de revisie ({@link SourceStructureConfigFactory#layoutOf})
     */
    public static ColumnPreview of(byte[] prefix, boolean complete, SourceLayout layout, Limits limits) {
        Layout shown = Layout.of(layout);
        if (!layout.usable()) {
            return empty(Status.LAYOUT_INVALID, shown);
        }
        if (prefix.length == 0 && complete) {
            return empty(Status.FILE_EMPTY, shown);
        }

        List<String> lines = lines(new String(prefix, layout.charset()), complete);
        if (!lines.isEmpty() && !lines.get(0).isEmpty() && lines.get(0).charAt(0) == BOM) {
            lines.set(0, lines.get(0).substring(1));
        }

        int firstDataIndex = 0;
        List<Column> columns = new ArrayList<>();
        boolean columnsTruncated = false;
        Integer headerLineNumber = null;
        if (layout.hasHeader()) {
            int headerIndex = layout.headerLineNumber() - 1;
            if (headerIndex >= lines.size()) {
                return new ColumnPreview(Status.HEADER_BEYOND_PREVIEW, shown, null, List.of(), List.of(), false,
                        !complete);
            }
            headerLineNumber = layout.headerLineNumber();
            firstDataIndex = headerIndex + 1;
            String[] header = split(lines.get(headerIndex), layout);
            // Een niet te parsen headerregel (aanhalingsteken niet gesloten): geen kolomnamen, nooit geraden.
            if (header != null) {
                for (int i = 0; i < header.length && i < limits.maxColumns(); i++) {
                    columns.add(new Column(i + 1, cap(header[i].trim())));
                }
                columnsTruncated = header.length > limits.maxColumns();
            }
        }

        List<Row> rows = new ArrayList<>();
        boolean moreRows = false;
        int widest = 0;
        for (int index = firstDataIndex; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isEmpty()) {
                continue; // zoals de streamer: een volledig lege regel draagt geen waarde
            }
            if (rows.size() >= limits.maxRows()) {
                moreRows = true;
                break;
            }
            long lineNumber = index + 1L;
            String[] cells = line.length() > limits.maxLineLength() ? null : split(line, layout);
            if (cells == null) {
                rows.add(new Row(lineNumber, null, true));
                continue;
            }
            widest = Math.max(widest, cells.length);
            List<String> shownCells = new ArrayList<>(Math.min(cells.length, limits.maxColumns()));
            for (int i = 0; i < cells.length && i < limits.maxColumns(); i++) {
                shownCells.add(cap(cells[i]));
            }
            columnsTruncated |= cells.length > limits.maxColumns();
            rows.add(new Row(lineNumber, List.copyOf(shownCells), false));
        }

        if (!layout.hasHeader()) {
            for (int position = 1; position <= widest && position <= limits.maxColumns(); position++) {
                columns.add(new Column(position, null));
            }
        }
        return new ColumnPreview(Status.AVAILABLE, shown, headerLineNumber, List.copyOf(columns), List.copyOf(rows),
                columnsTruncated, moreRows || !complete);
    }

    private static ColumnPreview empty(Status status, Layout layout) {
        return new ColumnPreview(status, layout, null, List.of(), List.of(), false, false);
    }

    /**
     * De volledige regels, met dezelfde regeleinden als {@link java.io.BufferedReader#readLine()}. Is het voorvoegsel
     * niet het hele bestand, dan valt alles na het laatste regeleinde weg (die regel kan afgebroken zijn).
     */
    private static List<String> lines(String text, boolean complete) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        int length = text.length();
        int index = 0;
        while (index < length) {
            char current = text.charAt(index);
            if (current == '\n' || current == '\r') {
                lines.add(text.substring(start, index));
                if (current == '\r' && index + 1 < length && text.charAt(index + 1) == '\n') {
                    index++;
                }
                start = index + 1;
            }
            index++;
        }
        if (complete && start < length) {
            lines.add(text.substring(start));
        }
        return lines;
    }

    /** De cellen van één regel, of {@code null} wanneer de regel niet te parsen is. */
    private static String[] split(String line, SourceLayout layout) {
        try {
            return ImportValueRules.parseCsvLine(line, layout.delimiter(), layout.quoteChar());
        } catch (ImportValueException unreadable) {
            return null;
        }
    }

    private static String cap(String value) {
        return value.length() <= CELL_MAX_LENGTH ? value : value.substring(0, CELL_MAX_LENGTH);
    }
}
