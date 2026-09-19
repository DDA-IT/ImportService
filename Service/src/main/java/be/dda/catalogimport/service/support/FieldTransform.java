package be.dda.catalogimport.service.support;

import be.dda.catalogimport.domain.FieldTransformKind;
import be.dda.catalogimport.service.support.ImportValueRules.DecimalCodes;
import be.dda.catalogimport.service.support.ImportValueRules.DecimalFormat;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Eén toegelaten bewerking op een gemapte bronwaarde (ontwerp fase 3, R-REC-07). De verzameling is
 * <b>gesloten</b> ({@code sealed}): vaste waarde, prefix, suffix, samenvoegen, splitsen, vertalen,
 * optellen, aftrekken, vermenigvuldigen, delen en percentage. Er is geen vrije expressietaal en geen
 * runtime-evaluatie — een importdefinitie kan dus nooit willekeurige code laten uitvoeren op de
 * bronwaarden van een catalogus.
 * <p>
 * <b>Pure klasse.</b> Geen Spring, geen database, geen tijd- of omgevingsafhankelijkheid: dezelfde
 * bronregel levert altijd dezelfde uitkomst, wat noodzakelijk is omdat het resultaat in een
 * vingerafdruk terechtkomt. De configuratie wordt exact één keer per batch geparsed
 * ({@link #of(FieldTransformKind, MappingSettings, String, DecimalFormat)} in stap B'); per bronregel
 * wordt er enkel nog gerekend.
 * <p>
 * <b>Foutgedrag.</b> Elke mislukking verwerpt uitsluitend de betrokken regel
 * ({@link ImportValueException}) en levert nooit stilzwijgend een lege waarde of een nul op:
 * <ul>
 *   <li>een bewerking die niet uitgevoerd kan worden ⇒ {@link #CODE_TRANSFORM_FAILED};</li>
 *   <li>een deling door nul ⇒ {@link #CODE_TRANSFORM_DIVIDE_BY_ZERO} — nooit 0, nooit "onbekend";</li>
 *   <li>een bronwaarde die niet in de vertaaltabel staat ⇒ {@link #CODE_MAPPING_VALUE_UNKNOWN}
 *       (R-REC-08). De oorspronkelijke waarde doorlaten (het legacygedrag) is uitdrukkelijk
 *       <b>verboden</b>: dan zou een onbekende leverancierscode als geldige doelwaarde in de catalogus
 *       belanden.</li>
 * </ul>
 * <b>Ontbrekende bronwaarde.</b> Een {@code null}-bronwaarde (de kolom staat niet in deze regel)
 * blijft {@code null}: de bewerking wordt overgeslagen, zodat de standaardwaarde van R-REC-03 nog kan
 * gelden. {@link Fixed} en {@link Concat} zijn de uitzondering — die bouwen hun waarde zelf op.
 */
public sealed interface FieldTransform
        permits FieldTransform.Unchanged, FieldTransform.Fixed, FieldTransform.Affix,
        FieldTransform.Concat, FieldTransform.Split, FieldTransform.Translate,
        FieldTransform.Arithmetic, FieldTransform.Percentage {

    /** De bewerking kon niet uitgevoerd worden op deze bronwaarde. */
    String CODE_TRANSFORM_FAILED = "TRANSFORM_FAILED";
    /** Er werd door nul gedeeld; het resultaat wordt nooit 0 of leeg. */
    String CODE_TRANSFORM_DIVIDE_BY_ZERO = "TRANSFORM_DIVIDE_BY_ZERO";
    /** De bronwaarde staat niet in de vertaaltabel; er wordt nooit op de bronwaarde teruggevallen. */
    String CODE_MAPPING_VALUE_UNKNOWN = "MAPPING_VALUE_UNKNOWN";

    /** Toegang tot de overige kolommen van dezelfde bronregel (samenvoegen, delen door een kolom). */
    interface SourceValues {

        /** Staat deze kolom in dit bronbestand? */
        boolean hasColumn(String reference);

        /** De ruwe kolomwaarde, of {@code null} wanneer de kolom in deze regel ontbreekt. */
        String value(String reference);
    }

    /**
     * Voert de bewerking uit.
     *
     * @param value     de (al opgehaalde) bronwaarde; {@code null} betekent werkelijk ontbrekend
     * @param fieldName de logische veldnaam voor de melding (meldingsstijl par. 15.12)
     * @throws ImportValueException de regel wordt verworpen
     */
    String apply(String value, String fieldName, SourceValues sources);

    /** Geen bewerking: de bronwaarde gaat ongewijzigd door. */
    record Unchanged() implements FieldTransform {

        @Override
        public String apply(String value, String fieldName, SourceValues sources) {
            return value;
        }
    }

    /** Altijd dezelfde waarde, ongeacht de bron. */
    record Fixed(String value) implements FieldTransform {

        @Override
        public String apply(String ignored, String fieldName, SourceValues sources) {
            return value;
        }
    }

    /** Vaste tekst vóór en/of ná de bronwaarde. */
    record Affix(String prefix, String suffix) implements FieldTransform {

        @Override
        public String apply(String value, String fieldName, SourceValues sources) {
            if (value == null) {
                return null;
            }
            return prefix + value + suffix;
        }
    }

    /**
     * Meerdere bronkolommen in vaste volgorde samenvoegen. Een kolom die niet in dit bestand staat is
     * een fout: de definitie beloofde die kolom. Een kolom die in deze regel geen waarde heeft, levert
     * een leeg onderdeel — dat is zichtbaar in het resultaat en wordt niet weggelaten.
     */
    record Concat(List<String> sources, String separator) implements FieldTransform {

        public Concat {
            sources = List.copyOf(sources);
        }

        @Override
        public String apply(String ignored, String fieldName, SourceValues values) {
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < sources.size(); i++) {
                String reference = sources.get(i);
                if (!values.hasColumn(reference)) {
                    throw failed(fieldName, null, "column '" + reference
                            + "' is not present in this source and cannot be concatenated");
                }
                if (i > 0) {
                    joined.append(separator);
                }
                String part = values.value(reference);
                joined.append(part == null ? "" : part);
            }
            return joined.toString();
        }
    }

    /** Splitsen op een scheidingsteken en het onderdeel op een 1-gebaseerde positie nemen. */
    record Split(String separator, int index) implements FieldTransform {

        @Override
        public String apply(String value, String fieldName, SourceValues sources) {
            if (value == null) {
                return null;
            }
            String[] parts = value.split(Pattern.quote(separator), -1);
            if (index < 1 || index > parts.length) {
                throw failed(fieldName, value, "splitting on '" + separator + "' gives " + parts.length
                        + " parts, so part " + index + " does not exist");
            }
            return parts[index - 1];
        }
    }

    /**
     * Vertaaltabel (R-REC-08). Een bronwaarde die niet in de tabel staat verwerpt de regel; ze valt
     * <b>nooit</b> terug op de oorspronkelijke waarde.
     */
    record Translate(Map<String, String> values, boolean caseSensitive) implements FieldTransform {

        public Translate {
            values = Map.copyOf(values);
        }

        @Override
        public String apply(String value, String fieldName, SourceValues sources) {
            if (value == null) {
                return null;
            }
            String translated = values.get(value);
            if (translated == null && !caseSensitive) {
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    if (entry.getKey().equalsIgnoreCase(value)) {
                        translated = entry.getValue();
                        break;
                    }
                }
            }
            if (translated == null) {
                throw new ImportValueException(CODE_MAPPING_VALUE_UNKNOWN, fieldName, value,
                        fieldName + ": '" + value + "' is not in the translation table of this mapping; "
                                + "an unknown source value is never passed through unchanged");
            }
            return translated;
        }
    }

    /**
     * Optellen, aftrekken, vermenigvuldigen of delen met een vaste waarde of met een andere kolom.
     * Altijd {@link BigDecimal}, nooit {@code float}; het resultaat krijgt de schaal die het doelveld
     * declareert ({@code decimal_scale}), met {@code HALF_UP} — de enige plaats waar afgerond wordt.
     */
    record Arithmetic(FieldTransformKind kind, BigDecimal operand, String operandSource,
                      DecimalFormat format) implements FieldTransform {

        @Override
        public String apply(String value, String fieldName, SourceValues sources) {
            if (value == null) {
                return null;
            }
            BigDecimal left = number(value, fieldName, format);
            BigDecimal right = operand != null ? operand
                    : number(column(operandSource, fieldName, sources), fieldName, format);
            BigDecimal result = switch (kind) {
                case ADD -> left.add(right);
                case SUBTRACT -> left.subtract(right);
                case MULTIPLY -> left.multiply(right).setScale(format.maxScale(), RoundingMode.HALF_UP);
                case DIVIDE -> divide(left, right, fieldName, value, format);
                default -> throw failed(fieldName, value, "unsupported arithmetic " + kind);
            };
            return result.toPlainString();
        }
    }

    /**
     * De waarde als percentage van een andere waarde: {@code waarde × 100 / basis}, met de schaal van
     * het doelveld en {@code HALF_UP} (ontwerp fase 3, par. 3.4). Een basis van nul levert nooit een
     * percentage op.
     */
    record Percentage(BigDecimal base, String baseSource, DecimalFormat format) implements FieldTransform {

        @Override
        public String apply(String value, String fieldName, SourceValues sources) {
            if (value == null) {
                return null;
            }
            BigDecimal amount = number(value, fieldName, format);
            BigDecimal divisor = base != null ? base
                    : number(column(baseSource, fieldName, sources), fieldName, format);
            return divide(amount.multiply(BigDecimal.valueOf(100)), divisor, fieldName, value, format)
                    .toPlainString();
        }
    }

    // --- Configuratie (één keer per batch, stap B') ---------------------------------------------

    /**
     * Bouwt de bewerking uit {@code transform_kind} + {@code transform_config}.
     *
     * @param fieldCode het doelveld; enkel voor de foutmelding
     * @param format    de decimale notatie van dit veld, voor de rekenkundige bewerkingen
     * @throws ScreeningBlockedException {@code CONFIG_TRANSFORM_INVALID} bij een onbekende of
     *                                   onvolledige configuratie; de levering blokkeert dan vóór er
     *                                   één byte gelezen is
     */
    static FieldTransform of(FieldTransformKind kind, MappingSettings settings, String fieldCode,
                             DecimalFormat format) {
        return switch (kind) {
            case NONE -> new Unchanged();
            case FIXED_VALUE -> new Fixed(settings.require("value",
                    "a fixed value transformation needs the value it produces"));
            case PREFIX -> new Affix(settings.require("prefix", "a prefix transformation needs its text"), "");
            case SUFFIX -> new Affix("", settings.require("suffix", "a suffix transformation needs its text"));
            case CONCAT -> new Concat(
                    settings.list("sources", "a concatenation needs the columns it joins"),
                    nullToEmpty(settings.get("separator")));
            case SPLIT -> new Split(
                    settings.require("separator", "a split needs the character it splits on"),
                    settings.integer("index", "a split needs the 1-based part it keeps"));
            case MAP -> new Translate(
                    settings.table("values", "a translation needs its table of source and target values"),
                    settings.flag("caseSensitive", false));
            case ADD, SUBTRACT, MULTIPLY, DIVIDE -> arithmetic(kind, settings, fieldCode, format);
            case PERCENTAGE -> percentage(settings, fieldCode, format);
        };
    }

    private static FieldTransform arithmetic(FieldTransformKind kind, MappingSettings settings,
                                             String fieldCode, DecimalFormat format) {
        BigDecimal operand = operand(settings, "operand", fieldCode);
        String operandSource = settings.get("operandField");
        if (operand == null && (operandSource == null || operandSource.isEmpty())) {
            throw invalid(fieldCode, "an arithmetic transformation needs either operand=<value> or "
                    + "operandField=<column>");
        }
        if (operand != null && operandSource != null && !operandSource.isEmpty()) {
            throw invalid(fieldCode, "an arithmetic transformation has both operand and operandField; "
                    + "only one source of the second value is allowed");
        }
        if (kind == FieldTransformKind.DIVIDE && operand != null
                && operand.compareTo(BigDecimal.ZERO) == 0) {
            throw invalid(fieldCode, "dividing by the fixed value 0 can never produce a result");
        }
        return new Arithmetic(kind, operand, operandSource, format);
    }

    private static FieldTransform percentage(MappingSettings settings, String fieldCode,
                                             DecimalFormat format) {
        BigDecimal base = operand(settings, "base", fieldCode);
        String baseSource = settings.get("baseField");
        if (base == null && (baseSource == null || baseSource.isEmpty())) {
            throw invalid(fieldCode, "a percentage needs either base=<value> or baseField=<column>");
        }
        if (base != null && baseSource != null && !baseSource.isEmpty()) {
            throw invalid(fieldCode, "a percentage has both base and baseField; only one base is allowed");
        }
        if (base != null && base.compareTo(BigDecimal.ZERO) == 0) {
            throw invalid(fieldCode, "a percentage of the fixed base 0 can never be computed");
        }
        return new Percentage(base, baseSource, format);
    }

    private static BigDecimal operand(MappingSettings settings, String key, String fieldCode) {
        String value = settings.get(key);
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(value.replace(',', '.'));
        } catch (NumberFormatException notANumber) {
            throw invalid(fieldCode, "setting '" + key + "' must be a decimal number but is '" + value + "'");
        }
    }

    // --- Hulpmiddelen ----------------------------------------------------------------------------

    private static BigDecimal number(String text, String fieldName, DecimalFormat format) {
        // Een rekenkundige bewerking op een onleesbaar getal is een mislukte bewerking, geen typefout
        // van het doelveld: de gebruiker moet zien dát de bewerking niet kon doorgaan.
        return ImportValueRules.decimal(text, fieldName, format,
                new DecimalCodes(CODE_TRANSFORM_FAILED, CODE_TRANSFORM_FAILED, CODE_TRANSFORM_FAILED));
    }

    private static String column(String reference, String fieldName, SourceValues sources) {
        if (!sources.hasColumn(reference)) {
            throw failed(fieldName, null, "column '" + reference + "' is not present in this source");
        }
        return sources.value(reference);
    }

    private static BigDecimal divide(BigDecimal left, BigDecimal right, String fieldName, String value,
                                     DecimalFormat format) {
        if (right.compareTo(BigDecimal.ZERO) == 0) {
            throw new ImportValueException(CODE_TRANSFORM_DIVIDE_BY_ZERO, fieldName, value,
                    fieldName + ": '" + value + "' cannot be divided by zero; the result is never 0 or empty");
        }
        return left.divide(right, format.maxScale(), RoundingMode.HALF_UP);
    }

    private static ImportValueException failed(String fieldName, String value, String reason) {
        return new ImportValueException(CODE_TRANSFORM_FAILED, fieldName, value,
                fieldName + ": '" + (value == null ? "" : value) + "' " + reason);
    }

    private static ScreeningBlockedException invalid(String fieldCode, String reason) {
        return new ScreeningBlockedException(ImportMappingConfigFactory.CODE_TRANSFORM_INVALID, fieldCode,
                null, null, "Mapping for '" + fieldCode + "' has an invalid transform configuration: "
                + reason);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
