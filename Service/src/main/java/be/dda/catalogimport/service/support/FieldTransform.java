package be.dda.catalogimport.service.support;

import be.dda.catalogimport.domain.FieldTransformKind;
import be.dda.catalogimport.service.support.ImportValueRules.DecimalCodes;
import be.dda.catalogimport.service.support.ImportValueRules.DecimalFormat;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
        FieldTransform.Arithmetic, FieldTransform.Percentage, FieldTransform.Upper, FieldTransform.Substring,
        FieldTransform.LegacyAffix, FieldTransform.Chain {

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

    /** Hoofdletters met {@link Locale#ROOT} (taalonafhankelijk; legacy CALC 15). {@code null} blijft {@code null}. */
    record Upper() implements FieldTransform {

        @Override
        public String apply(String value, String fieldName, SourceValues sources) {
            return value == null ? null : value.toUpperCase(Locale.ROOT);
        }
    }

    /**
     * Deeltekst vanaf een 1-gebaseerde positie, met optionele lengte (legacy {@code MID}, Prg_1170 r.730). Een start
     * voorbij het einde geeft {@code ""} (aanname S8, beslissing 2026-10-21); een lengte voorbij het einde geeft de
     * rest. De buitenste trim volgt in de canonicalisatie van {@code FieldValueMapper}.
     *
     * @param length {@code null} = de rest
     */
    record Substring(int start, Integer length) implements FieldTransform {

        @Override
        public String apply(String value, String fieldName, SourceValues sources) {
            if (value == null) {
                return null;
            }
            int from = start - 1;
            if (from >= value.length()) {
                return "";
            }
            int to = length == null ? value.length() : (int) Math.min((long) from + length, value.length());
            return value.substring(from, to);
        }
    }

    /**
     * Prefix en/of suffix met de legacy-semantiek (S8c, {@code Prg_1170} r.730): enkel als de getrimde waarde niet leeg
     * is {@code Trim(prefix) & waarde & Trim(suffix)}, anders {@code ""}. Anders dan {@link Affix} (PREFIX/SUFFIX) bouwt
     * dit nooit een waarde rond een lege cel. {@code null} blijft {@code null}.
     *
     * @param prefix al getrimd ({@link MappingSettings} trimt elke waarde); {@code ""} = geen
     * @param suffix idem
     */
    record LegacyAffix(String prefix, String suffix) implements FieldTransform {

        @Override
        public String apply(String value, String fieldName, SourceValues sources) {
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            return trimmed.isEmpty() ? "" : prefix + trimmed + suffix;
        }
    }

    /**
     * De transformatieketen van een {@code CHAIN}-mapping (S8c, {@code import_field_transform_step}), in de vaste
     * legacy-volgorde ({@code Prg_1170} r.730-732, r.1039-1115): een eventuele {@link Substring} op de <b>ruwe</b>
     * waarde (legacy {@code MID} op het record), dan trimmen; is het resultaat leeg, dan is de uitkomst {@code ""} en
     * worden alle volgende stappen overgeslagen. Legacy rekent een lege waarde met {@code Val('')=0 ± x}; hier blijft
     * ze leeg (verklaard verschil, nooit een verzonnen getal). {@code null} (kolom ontbreekt) blijft {@code null}.
     *
     * @param steps de gevalideerde stappen in volgorde (zie {@link #of(FieldTransformKind, MappingSettings, List,
     *              List, String, DecimalFormat)})
     */
    record Chain(List<FieldTransform> steps) implements FieldTransform {

        public Chain {
            steps = List.copyOf(steps);
        }

        @Override
        public String apply(String value, String fieldName, SourceValues sources) {
            if (value == null) {
                return null;
            }
            String current = value;
            int next = 0;
            if (!steps.isEmpty() && steps.get(0) instanceof Substring substring) {
                current = substring.apply(current, fieldName, sources);
                next = 1;
            }
            current = current.trim();
            if (current.isEmpty()) {
                return "";
            }
            for (int i = next; i < steps.size(); i++) {
                current = steps.get(i).apply(current, fieldName, sources);
            }
            return current;
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
        return of(kind, settings, List.of(), fieldCode, format);
    }

    /**
     * Eén regel van de vertaaltabel van een mapping (legacy-config S8a, {@code import_field_value_map}).
     *
     * @param sequence de volgorde binnen de mapping; enkel voor de melding
     */
    record MapEntry(int sequence, String source, String target) {
    }

    /**
     * Zoals {@link #of(FieldTransformKind, MappingSettings, String, DecimalFormat)}, met de vertaalrijen van deze
     * mapping uit {@code import_field_value_map} (legacy-config S8a, {@code docs/design/legacy-config-s8-design.md}
     * par. 2a). Een {@code MAP} gebruikt óf {@code values=} óf de rijen: beide, geen van beide, of (bij
     * {@code caseSensitive=false}) twee rijen die enkel in hoofdletters verschillen, is {@code CONFIG_TRANSFORM_INVALID}.
     * Rijen bij een andere soort zouden stil zonder effect blijven en zijn daarom ook een configuratiefout.
     *
     * @param valueMap de vertaalrijen van deze mapping, in volgorde; leeg als er geen zijn
     */
    static FieldTransform of(FieldTransformKind kind, MappingSettings settings, List<MapEntry> valueMap,
                             String fieldCode, DecimalFormat format) {
        return of(kind, settings, valueMap, List.of(), fieldCode, format);
    }

    /**
     * Eén stap van de transformatieketen van een mapping (legacy-config S8c, {@code import_field_transform_step}).
     *
     * @param number het stapnummer (1-gebaseerd)
     * @param config de instellingen van deze stap, zoals {@code transform_config}; {@code null} = geen
     */
    record Step(int number, FieldTransformKind kind, String config) {
    }

    /**
     * Zoals {@link #of(FieldTransformKind, MappingSettings, List, String, DecimalFormat)}, met de ketenstappen van deze
     * mapping (S8c, {@code docs/design/legacy-config-s8-design.md} par. 2c). Een {@code CHAIN} heeft 1-3 stappen in de
     * gesloten volgorde {@code [SUBSTRING] → [AFFIX|PREFIX|SUFFIX] → [UPPER|MAP|ADD|SUBTRACT|MULTIPLY|DIVIDE|
     * PERCENTAGE|SPLIT]}; elke stap is een gewone soort met haar eigen instellingen (elke onbekende instelling is een
     * fout). De vertaalrijen gaan naar de MAP-stap. Stappen bij een andere soort dan {@code CHAIN} zouden stil zonder
     * effect blijven en zijn een configuratiefout; zo ook vertaalrijen bij een keten zonder MAP-stap.
     *
     * @param steps de stappen van deze mapping, op stapnummer; leeg als er geen zijn
     */
    static FieldTransform of(FieldTransformKind kind, MappingSettings settings, List<MapEntry> valueMap,
                             List<Step> steps, String fieldCode, DecimalFormat format) {
        if (kind != FieldTransformKind.CHAIN && !steps.isEmpty()) {
            throw invalid(fieldCode, "it has " + steps.size() + " transformation step(s) but transformation " + kind
                    + " is not a CHAIN; they would silently have no effect");
        }
        if (kind == FieldTransformKind.CHAIN) {
            return chain(steps, valueMap, fieldCode, format);
        }
        if (kind != FieldTransformKind.MAP && !valueMap.isEmpty()) {
            throw invalid(fieldCode, "it has " + valueMap.size() + " value map row(s) but transformation " + kind
                    + " does not use a translation table; they would silently have no effect");
        }
        if (kind == FieldTransformKind.MAP && !valueMap.isEmpty()) {
            return tableTranslation(settings, valueMap, fieldCode);
        }
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
            case UPPER -> new Upper();
            case SUBSTRING -> substring(settings, fieldCode);
            case AFFIX -> affix(settings, fieldCode);
            case CHAIN -> chain(steps, valueMap, fieldCode, format);
        };
    }

    /** S8c: {@code prefix} en/of {@code suffix}; minstens één van beide is niet leeg. */
    private static FieldTransform affix(MappingSettings settings, String fieldCode) {
        String prefix = nullToEmpty(settings.get("prefix"));
        String suffix = nullToEmpty(settings.get("suffix"));
        if (prefix.isEmpty() && suffix.isEmpty()) {
            throw invalid(fieldCode, "an affix transformation needs prefix=<text>, suffix=<text> or both");
        }
        return new LegacyAffix(prefix, suffix);
    }

    /**
     * S8c: de keten. Volgorde van de controles: aantal, nummering, soort per stap (nooit {@code NONE},
     * {@code FIXED_VALUE}, {@code CONCAT} of {@code CHAIN}), volgorde, en per stap haar eigen configuratie. Een
     * waardebewerking komt hoogstens één keer voor, dus ook hoogstens één tabelstap (MAP; S8d voegt MAP_CONTAINS toe).
     */
    private static FieldTransform chain(List<Step> steps, List<MapEntry> valueMap, String fieldCode,
                                        DecimalFormat format) {
        if (steps.isEmpty() || steps.size() > MAX_CHAIN_STEPS) {
            throw invalid(fieldCode, "a chain needs 1 to " + MAX_CHAIN_STEPS + " steps but has " + steps.size());
        }
        List<FieldTransform> built = new ArrayList<>(steps.size());
        Step previous = null;
        boolean usesTable = false;
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            if (step.number() != i + 1) {
                throw invalid(fieldCode, String.valueOf(step.number()), "the steps of a chain are numbered 1, 2, 3 "
                        + "without gaps, but step " + (i + 1) + " has number " + step.number());
            }
            if (step.kind() == null) {
                throw invalid(fieldCode, "step " + step.number() + " of the chain has no transform kind");
            }
            int stage = chainStage(step.kind());
            if (stage == 0) {
                throw invalid(fieldCode, step.kind().name(), "step " + step.number() + " (" + step.kind()
                        + ") can never be a step of a chain; " + chainOrder());
            }
            if (previous != null && stage <= chainStage(previous.kind())) {
                throw invalid(fieldCode, step.kind().name(), "step " + step.number() + " (" + step.kind()
                        + ") cannot follow step " + previous.number() + " (" + previous.kind() + "); " + chainOrder());
            }
            boolean table = step.kind() == FieldTransformKind.MAP;
            try {
                MappingSettings stepSettings = MappingSettings.parse(fieldCode, step.config());
                built.add(of(step.kind(), stepSettings, table ? valueMap : List.of(), List.of(), fieldCode, format));
                stepSettings.verifyFullyUsed();
            } catch (ScreeningBlockedException invalidStep) {
                throw inStep(step, fieldCode, invalidStep);
            }
            usesTable |= table;
            previous = step;
        }
        if (!valueMap.isEmpty() && !usesTable) {
            throw invalid(fieldCode, "it has " + valueMap.size() + " value map row(s) but no step of its chain uses "
                    + "a translation table; they would silently have no effect");
        }
        return new Chain(built);
    }

    /** Hoogstens substring, prefix/suffix en één waardebewerking ({@code ck_import_field_transform_step_number}). */
    int MAX_CHAIN_STEPS = 3;

    /** De gesloten volgorde, voor de melding. */
    private static String chainOrder() {
        return "a chain holds at most SUBSTRING, then one of AFFIX, PREFIX or SUFFIX, then one of UPPER, "
                + "MAP, ADD, SUBTRACT, MULTIPLY, DIVIDE, PERCENTAGE or SPLIT, in that order";
    }

    /**
     * De plaats van een soort in de keten: 1 = substring, 2 = prefix/suffix, 3 = waardebewerking, 0 = nooit een stap.
     * Bewust een volledige {@code switch} zonder {@code default}: een nieuwe soort (S8d: MAP_CONTAINS → 3) compileert pas
     * als haar plaats in de keten beslist is.
     */
    private static int chainStage(FieldTransformKind kind) {
        return switch (kind) {
            case SUBSTRING -> 1;
            case AFFIX, PREFIX, SUFFIX -> 2;
            case UPPER, MAP, ADD, SUBTRACT, MULTIPLY, DIVIDE, PERCENTAGE, SPLIT -> 3;
            case NONE, FIXED_VALUE, CONCAT, CHAIN -> 0;
        };
    }

    /** Dezelfde bevinding, met de stap erbij in de melding; code, veld en bronwaarde blijven. */
    private static ScreeningBlockedException inStep(Step step, String fieldCode, ScreeningBlockedException invalid) {
        String lead = "Mapping for '" + fieldCode + "' has an invalid transform configuration: ";
        String message = invalid.getMessage();
        String reason = message != null && message.startsWith(lead) ? message.substring(lead.length()) : message;
        return new ScreeningBlockedException(invalid.getCode(), invalid.getFieldName(), invalid.getSourceValue(),
                invalid.getExpectedValue(), lead + "step " + step.number() + " (" + step.kind() + "): " + reason);
    }

    /** S8b: {@code start} (default 1, >= 1) en {@code length} (optioneel, >= 1; leeg = de rest). */
    private static FieldTransform substring(MappingSettings settings, String fieldCode) {
        int start = positive(settings, "start", fieldCode, 1);
        Integer length = settings.get("length") == null || settings.get("length").isEmpty() ? null
                : Integer.valueOf(positive(settings, "length", fieldCode, 1));
        return new Substring(start, length);
    }

    private static int positive(MappingSettings settings, String key, String fieldCode, int defaultValue) {
        String value = settings.get(key);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException notANumber) {
            throw invalid(fieldCode, value, "setting '" + key + "' must be a whole number");
        }
        if (parsed < 1) {
            throw invalid(fieldCode, value, "setting '" + key + "' must be 1 or higher");
        }
        return parsed;
    }

    /**
     * S8a: de vertaling uit de rijen van {@code import_field_value_map}. Dezelfde regels als {@code values=}
     * ({@link MappingSettings#table}): een bronwaarde is niet blanco en komt hoogstens één keer voor; een lege
     * doelwaarde is toegelaten. Daarbovenop: zonder hoofdlettergevoeligheid mogen twee bronwaarden niet enkel in
     * hoofdletters verschillen, anders hangt de vertaling af van welke rij toevallig eerst gevonden wordt.
     */
    private static FieldTransform tableTranslation(MappingSettings settings, List<MapEntry> valueMap,
                                                   String fieldCode) {
        if (settings.get("values") != null) {
            throw invalid(fieldCode, "it has both values= and " + valueMap.size() + " value map row(s); a "
                    + "translation has exactly one table");
        }
        boolean caseSensitive = settings.flag("caseSensitive", false);
        Map<String, String> table = new LinkedHashMap<>();
        Map<String, String> folded = new HashMap<>();
        for (MapEntry entry : valueMap) {
            String source = entry.source();
            if (source == null || source.isBlank()) {
                throw invalid(fieldCode, source, "value map row " + entry.sequence() + " has no source value");
            }
            if (entry.target() == null) {
                throw invalid(fieldCode, source, "value map row " + entry.sequence() + " has no target value; use "
                        + "an empty text to translate to empty");
            }
            if (table.putIfAbsent(source, entry.target()) != null) {
                throw invalid(fieldCode, source, "source value '" + source + "' is translated more than once");
            }
            if (!caseSensitive) {
                String other = folded.putIfAbsent(foldCase(source), source);
                if (other != null) {
                    throw invalid(fieldCode, source, "source values '" + other + "' and '" + source + "' differ "
                            + "only in case while caseSensitive=false; which one translates would be undefined");
                }
            }
        }
        return new Translate(table, caseSensitive);
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
        return invalid(fieldCode, null, reason);
    }

    private static ScreeningBlockedException invalid(String fieldCode, String sourceValue, String reason) {
        return new ScreeningBlockedException(ImportMappingConfigFactory.CODE_TRANSFORM_INVALID, fieldCode,
                sourceValue, null, "Mapping for '" + fieldCode + "' has an invalid transform configuration: "
                + reason);
    }

    /**
     * Teken per teken dezelfde gelijkheid als {@link String#equalsIgnoreCase} (die {@link Translate} gebruikt): twee
     * teksten met dezelfde gevouwen vorm worden zonder hoofdlettergevoeligheid als gelijk vertaald.
     */
    private static String foldCase(String text) {
        StringBuilder folded = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            folded.append(Character.toLowerCase(Character.toUpperCase(text.charAt(i))));
        }
        return folded.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
