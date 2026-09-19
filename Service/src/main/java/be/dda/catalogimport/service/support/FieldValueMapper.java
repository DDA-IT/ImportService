package be.dda.catalogimport.service.support;

import be.dda.catalogimport.domain.FieldDataType;
import be.dda.catalogimport.service.support.CsvRecordStreamer.ParsedRow;
import be.dda.catalogimport.service.support.ImportMappingConfig.FieldMapping;
import be.dda.catalogimport.service.support.ImportMappingConfig.ValueFormat;
import be.dda.catalogimport.service.support.ImportValueRules.DecimalCodes;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Past de veldmapping van één bevroren revisie toe op één geparste bronregel (ontwerp fase 3,
 * R-REC-01..R-REC-09, par. 3.1 stap C). Pure klasse: geen Spring, geen database, geen tijd- of
 * omgevingsafhankelijkheid — dezelfde bronregel levert altijd dezelfde doelwaarden op, wat nodig is
 * omdat die waarden in de artikelvingerafdruk terechtkomen.
 *
 * <h2>Volgorde per veld</h2>
 * <ol>
 *   <li><b>bronwaarde ophalen</b> volgens {@code value_kind}: een bronkolom (op headernaam of
 *       kolomindex), een vaste waarde, een sjabloonwaarde (bookmark) of een afgeleide waarde;</li>
 *   <li><b>transformatie</b> uit de gesloten lijst van {@link FieldTransform} (R-REC-07);</li>
 *   <li><b>canonicalisatie</b>: buitenste trim, verder niets — geen case-folding, geen
 *       leading-zero-verwijdering, geen Unicode-herschrijving (R-REC-09);</li>
 *   <li><b>standaardwaarde</b> uitsluitend bij een <i>werkelijk ontbrekende</i> waarde (R-REC-03);</li>
 *   <li><b>verplicht veld</b> leeg of ontbrekend ⇒ {@link #CODE_VALUE_MISSING}, nooit een default
 *       (R-REC-02);</li>
 *   <li><b>lengte</b> boven {@code max_length} ⇒ {@link #CODE_VALUE_TOO_LONG}; de regel wordt
 *       verworpen en de waarde <b>nooit</b> afgekapt (R-REC-06);</li>
 *   <li><b>type</b>: tekst blijft tekst, decimalen lopen uitsluitend via
 *       {@link ImportValueRules#decimal} en datums via het verklaarde bronformaat.</li>
 * </ol>
 *
 * <h2>Wat hier nooit gebeurt</h2>
 * Een onleesbare waarde wordt nooit 0, leeg of "vandaag"; een te lange waarde wordt nooit afgekapt;
 * een onbekende vertaalwaarde valt nooit terug op de bronwaarde; een ambigue datum wordt nooit
 * geraden. Elk van die gevallen verwerpt <b>enkel de betrokken bronregel</b>
 * ({@link ImportValueException}); de levering loopt door. Een fout in de <i>definitie</i> zelf
 * (onvervulde bookmark, onoplosbare bronkolom) is een {@link ScreeningBlockedException} en blokkeert
 * de volledige levering, want die fout geldt voor élke regel.
 *
 * <h2>Grens van bouwstap 3c</h2>
 * Prijscomponenten, percentages, afwijkingscontrole en kritieke referenties worden hier gelezen en
 * getypeerd, maar nog niet inhoudelijk beoordeeld: {@code zero_allowed}, {@code negative_allowed},
 * de percentageberekening en de referentiecontrole zijn bouwstap 3d/3f. Tot dan blokkeert
 * {@link ImportMappingConfigFactory} een revisie die zulke velden mapt.
 */
public final class FieldValueMapper {

    /** De bronwaarde past niet bij het verklaarde type van het doelveld (R-REC-01/R-REC-04). */
    public static final String CODE_VALUE_TYPE_MISMATCH = "VALUE_TYPE_MISMATCH";
    /** De datum bestaat niet of past niet in het verklaarde bronformaat (R-REC-05). */
    public static final String CODE_DATE_UNREADABLE = "DATE_UNREADABLE";
    /** De datum heeft geen verklaard formaat en is niet onmiskenbaar ISO-8601 (R-REC-05). */
    public static final String CODE_DATE_AMBIGUOUS = "DATE_AMBIGUOUS";
    /** Er is een standaardwaarde toegepast omdat de bronwaarde ontbrak; informatief (R-REC-03). */
    public static final String CODE_VALUE_DEFAULT_APPLIED = "VALUE_DEFAULT_APPLIED";
    /** Verplicht veld leeg of ontbrekend; nooit een stille default (R-REC-02). */
    public static final String CODE_VALUE_MISSING = ImportValueRules.CODE_VALUE_MISSING;
    /** Waarde langer dan toegelaten; de regel wordt verworpen, nooit afgekapt (R-REC-06). */
    public static final String CODE_VALUE_TOO_LONG = CandidateNormaliser.CODE_VALUE_TOO_LONG;

    /** Een bronwaarde die zonder verklaard formaat toch onmiskenbaar een ISO-datum is. */
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}.*");

    private static final MappedRecord EMPTY = new MappedRecord(Map.of(), List.of());

    /**
     * De doelwaarden van één bronregel.
     *
     * @param values   per {@code target_field_code} de canonieke doelwaarde; {@code null} betekent
     *                 <b>werkelijk ontbrekend</b> en is iets anders dan {@code ""} (gemapt maar leeg).
     *                 Dat onderscheid gaat tot in de vingerafdruk mee (par. 3.5)
     * @param notices  informatieve vaststellingen (vandaag enkel toegepaste standaardwaarden); ze
     *                 verwerpen de regel niet
     */
    public record MappedRecord(Map<String, String> values, List<Notice> notices) {

        public MappedRecord {
            notices = List.copyOf(notices);
        }

        /** De canonieke waarde van een doelveld, of {@code null} wanneer er geen waarde is. */
        public String value(String targetFieldCode) {
            return values.get(targetFieldCode);
        }
    }

    /** Eén informatieve vaststelling over één regel; wordt een {@code import_row_issue} met ernst INFO. */
    public record Notice(long rowNumber, String code, String fieldName, String sourceValue, String message) {
    }

    /**
     * Zet de gemapte doelvelden van één regel om.
     *
     * @throws ImportValueException      de regel wordt verworpen (recordfout)
     * @throws ScreeningBlockedException de definitie past niet op dit bestand; de levering blokkeert
     */
    public MappedRecord map(ParsedRow row, ImportMappingConfig config) {
        if (config == null || !config.hasFields()) {
            return EMPTY;
        }
        Map<String, String> values = new LinkedHashMap<>();
        List<Notice> notices = new ArrayList<>();
        RowValues sources = new RowValues(row);
        for (FieldMapping field : config.fields()) {
            values.put(field.targetFieldCode(), value(row, field, sources, notices));
        }
        return new MappedRecord(Collections.unmodifiableMap(values), notices);
    }

    // --- Eén veld --------------------------------------------------------------------------------

    private String value(ParsedRow row, FieldMapping field, RowValues sources, List<Notice> notices) {
        String name = field.targetFieldName();
        String raw = source(row, field, name);
        String value = canonical(field.transform().apply(raw, name, sources));

        if (value == null && field.defaultValue() != null) {
            // R-REC-03: enkel bij een werkelijk ontbrekende waarde. Een expliciet lege bronwaarde ("")
            // is een uitspraak van de leverancier en wordt nooit door een default vervangen.
            value = canonical(field.defaultValue());
            notices.add(new Notice(row.lineNumber(), CODE_VALUE_DEFAULT_APPLIED, name, null,
                    name + ": no value in the source; the configured default '" + value + "' was applied"));
        }
        if (field.required() && (value == null || value.isEmpty())) {
            throw new ImportValueException(CODE_VALUE_MISSING, name, raw,
                    name + ": '" + nullToEmpty(raw) + "' is empty while this field is required; a required "
                            + "field is never filled with a default");
        }
        requireLength(value, field, name);
        return typed(value, field, name);
    }

    /** Haalt de bronwaarde op; {@code null} betekent dat deze regel geen waarde voor dit veld heeft. */
    private static String source(ParsedRow row, FieldMapping field, String name) {
        return switch (field.valueKind()) {
            case FIXED_VALUE -> field.fixedValue();
            case SOURCE_FIELD -> {
                Integer position = row.positions().position(field.sourceReference());
                if (position == null) {
                    // De headercontrole hoort dit al te blokkeren; gebeurt het toch, dan is doorgaan met
                    // een lege waarde het enige wat echt fout zou zijn.
                    throw new ScreeningBlockedException(
                            ImportMappingConfigFactory.CODE_MAPPING_SOURCE_UNRESOLVED, name, null,
                            field.sourceReference(), "Mapped column '" + field.sourceReference()
                            + "' for '" + field.targetFieldCode() + "' is not present in this source");
                }
                yield row.value(position);
            }
            case BOOKMARK -> throw new ScreeningBlockedException(
                    ImportMappingConfigFactory.CODE_MAPPING_SOURCE_UNRESOLVED, name, null,
                    field.bookmarkName(), "Mapping for '" + field.targetFieldCode()
                    + "' reads the template value '" + field.bookmarkName() + "', which has no filled-in "
                    + "value: templates and bookmarks are declared in the schema but not filled in by this "
                    + "build, and an empty target field would look like a deliberate blank");
            // De transformatie bouwt de waarde zelf op (vaste waarde of samenvoeging); de factory heeft
            // al geverifieerd dat die transformatie dat werkelijk kan.
            case DERIVED -> null;
        };
    }

    /** R-REC-09: enkel de buitenste spaties verdwijnen; verder wordt er niets aan de waarde veranderd. */
    private static String canonical(String value) {
        return value == null ? null : value.trim();
    }

    /** R-REC-06: te lang is verwerpen, nooit afkappen — afkappen wijzigt stil de betekenis. */
    private static void requireLength(String value, FieldMapping field, String name) {
        Integer maxLength = field.maxLength();
        if (value != null && maxLength != null && value.length() > maxLength) {
            throw new ImportValueException(CODE_VALUE_TOO_LONG, name, value,
                    name + ": '" + value + "' is " + value.length() + " characters while at most " + maxLength
                            + " are allowed; the value is rejected and never truncated");
        }
    }

    /**
     * Zet de waarde om naar haar canonieke vorm voor het verklaarde type. Tekst blijft exact zoals ze
     * in de bron stond (R-REC-01); enkel getallen, datums en booleans krijgen een genormaliseerde
     * schrijfwijze, zodat {@code 12,50} en {@code 12.5} dezelfde vingerafdruk opleveren.
     */
    private static String typed(String value, FieldMapping field, String name) {
        // R-REC-01: op een identificerend veld is een numeriek type altijd fout - een artikelnummer
        // 007 zou dan 7 worden en een barcode zou haar voorloopnullen verliezen.
        if (field.isIdentifyingText() && isNumeric(field.dataType())) {
            throw new ImportValueException(CODE_VALUE_TYPE_MISMATCH, name, value,
                    name + ": '" + nullToEmpty(value) + "' is declared as " + field.dataType()
                            + " while this field identifies an article or an offer; leading zeroes, length "
                            + "and case must stay exactly as delivered");
        }
        if (value == null || value.isEmpty()) {
            // Ontbrekend en expliciet leeg blijven verschillende toestanden; geen van beide wordt 0.
            return value;
        }
        return switch (field.dataType()) {
            case TEXT -> value;
            case INTEGER -> integer(value, name);
            case DECIMAL -> decimal(value, field, name);
            case DATE -> date(value, field, name);
            case DATETIME -> dateTime(value, field, name);
            case BOOLEAN -> bool(value, name);
        };
    }

    private static boolean isNumeric(FieldDataType dataType) {
        return dataType == FieldDataType.INTEGER || dataType == FieldDataType.DECIMAL;
    }

    private static String integer(String value, String name) {
        try {
            return new BigInteger(value).toString();
        } catch (NumberFormatException notAnInteger) {
            throw new ImportValueException(CODE_VALUE_TYPE_MISMATCH, name, value,
                    name + ": '" + value + "' is not a whole number");
        }
    }

    /**
     * R-REC-04: decimalen lopen uitsluitend via {@link ImportValueRules#decimal}. Een prijsveld meldt
     * de bestaande {@code PRICE_*}-codes; elk ander decimaal veld meldt een typefout. Nooit
     * {@code float}, nooit een stille nul, nooit stil afkappen.
     */
    private static String decimal(String value, FieldMapping field, String name) {
        DecimalCodes codes = field.priceComponentCode() != null ? DecimalCodes.PRICE
                : new DecimalCodes(CODE_VALUE_MISSING, CODE_VALUE_TYPE_MISMATCH, CODE_VALUE_TYPE_MISMATCH);
        BigDecimal parsed = ImportValueRules.decimal(value, name, field.valueFormat().decimal(), codes);
        return parsed.toPlainString();
    }

    /**
     * R-REC-05. Met een verklaard bronformaat wordt strikt geparsed: 31/02 bestaat niet en wordt
     * {@link #CODE_DATE_UNREADABLE}, niet stilzwijgend 28/02. Zonder verklaard formaat wordt enkel
     * ISO-8601 aanvaard; {@code 01/02/2026} is dan {@link #CODE_DATE_AMBIGUOUS} — 1 februari en
     * 2 januari zijn allebei verdedigbaar en raden is geen optie.
     */
    private static String date(String value, FieldMapping field, String name) {
        ValueFormat format = field.valueFormat();
        try {
            if (format.dateFormatter() == null) {
                return LocalDate.parse(value).toString();
            }
            return LocalDate.parse(value, format.dateFormatter()).toString();
        } catch (DateTimeParseException invalid) {
            throw dateFailure(value, name, format, invalid);
        }
    }

    private static String dateTime(String value, FieldMapping field, String name) {
        ValueFormat format = field.valueFormat();
        try {
            if (format.dateFormatter() == null) {
                // Zonder verklaard formaat is enkel een ISO-tijdstip mét zone of offset eenduidig.
                return OffsetDateTime.parse(value).toInstant().toString();
            }
            LocalDateTime local = LocalDateTime.parse(value, format.dateFormatter());
            if (format.zone() == null) {
                throw new ImportValueException(CODE_DATE_AMBIGUOUS, name, value,
                        name + ": '" + value + "' has no declared time zone; a local timestamp cannot be "
                                + "turned into a point in time without one");
            }
            return local.atZone(format.zone()).toInstant().toString();
        } catch (DateTimeParseException invalid) {
            throw dateFailure(value, name, format, invalid);
        }
    }

    private static ImportValueException dateFailure(String value, String name, ValueFormat format,
                                                    DateTimeParseException invalid) {
        if (format.dateFormatter() != null) {
            return new ImportValueException(CODE_DATE_UNREADABLE, name, value,
                    name + ": '" + value + "' is not a valid date in the declared source format '"
                            + format.datePattern() + "'");
        }
        if (ISO_DATE.matcher(value).matches()) {
            return new ImportValueException(CODE_DATE_UNREADABLE, name, value,
                    name + ": '" + value + "' looks like an ISO-8601 date but is not a date that exists");
        }
        return new ImportValueException(CODE_DATE_AMBIGUOUS, name, value,
                name + ": '" + value + "' has no declared source format and is not unambiguous ISO-8601; "
                        + "the day and the month order would have to be guessed");
    }

    private static String bool(String value, String name) {
        if ("true".equalsIgnoreCase(value) || "1".equals(value)) {
            return "true";
        }
        if ("false".equalsIgnoreCase(value) || "0".equals(value)) {
            return "false";
        }
        throw new ImportValueException(CODE_VALUE_TYPE_MISMATCH, name, value,
                name + ": '" + value + "' is not a boolean; only true, false, 1 and 0 are accepted");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** De overige kolommen van dezelfde bronregel, voor samenvoegen en rekenen met een andere kolom. */
    private record RowValues(ParsedRow row) implements FieldTransform.SourceValues {

        @Override
        public boolean hasColumn(String reference) {
            return row.positions().position(reference) != null;
        }

        @Override
        public String value(String reference) {
            Integer position = row.positions().position(reference);
            return position == null ? null : row.value(position);
        }
    }
}
