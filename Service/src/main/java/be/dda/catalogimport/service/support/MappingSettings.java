package be.dda.catalogimport.service.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * De instellingen van één veldmapping, gelezen uit {@code import_field_mapping.transform_config}
 * (ontwerp fase 3, R-REC-05/R-REC-07).
 *
 * <h2>Waarom geen vrije expressie</h2>
 * R-REC-07 laat uitsluitend een gesloten lijst bewerkingen toe. Deze instellingen zijn daarom een
 * platte lijst {@code sleutel=waarde}, gescheiden door {@code ;} — geen script, geen formule, geen
 * runtime-evaluatie. Alles wordt <b>één keer per batch</b> gelezen (stap B'), zodat er per bronregel
 * geen enkele parse of query meer nodig is.
 *
 * <h2>Vorm</h2>
 * <pre>
 * prefix=ART-
 * separator=|;index=2
 * values=A&gt;1|B&gt;2;caseSensitive=false
 * dateFormat=dd/MM/yyyy;zone=Europe/Brussels
 * decimalSeparator=,;groupingSeparator=.
 * </pre>
 * Een sleutel komt hoogstens één keer voor. Een onbekende sleutel is een configuratiefout en wordt
 * nooit genegeerd: negeren zou betekenen dat een beheerder denkt dat er een bewerking gebeurt terwijl
 * de bronwaarde ongewijzigd doorgaat. Er is geen escaping: een waarde die zelf {@code ;} bevat, kan
 * hier niet uitgedrukt worden en levert een configuratiefout op in plaats van een half ingelezen
 * instelling.
 */
final class MappingSettings {

    /** Scheidt twee instellingen. */
    static final char PAIR_SEPARATOR = ';';
    /** Scheidt sleutel en waarde. */
    static final char KEY_SEPARATOR = '=';
    /** Scheidt de onderdelen van een lijstwaarde ({@code sources}, {@code values}). */
    static final char LIST_SEPARATOR = '|';
    /** Scheidt bron en doel binnen één vertaalregel van {@code values}. */
    static final char ENTRY_SEPARATOR = '>';

    private final String fieldCode;
    private final Map<String, String> settings;
    private final Set<String> used = new LinkedHashSet<>();

    private MappingSettings(String fieldCode, Map<String, String> settings) {
        this.fieldCode = fieldCode;
        this.settings = settings;
    }

    /**
     * Leest {@code transform_config}.
     *
     * @param fieldCode het doelveld; enkel voor de foutmelding
     * @throws ScreeningBlockedException {@code CONFIG_TRANSFORM_INVALID} bij een onleesbare of dubbele
     *                                   instelling
     */
    static MappingSettings parse(String fieldCode, String config) {
        Map<String, String> settings = new LinkedHashMap<>();
        if (config == null || config.isBlank()) {
            return new MappingSettings(fieldCode, settings);
        }
        for (String pair : split(config, PAIR_SEPARATOR)) {
            if (pair.isBlank()) {
                continue;
            }
            int separator = pair.indexOf(KEY_SEPARATOR);
            if (separator <= 0) {
                throw invalid(fieldCode, pair, "setting '" + pair.trim() + "' is not of the form key"
                        + KEY_SEPARATOR + "value");
            }
            String key = pair.substring(0, separator).trim();
            String value = pair.substring(separator + 1).trim();
            if (settings.putIfAbsent(key, value) != null) {
                throw invalid(fieldCode, pair, "setting '" + key + "' occurs more than once");
            }
        }
        return new MappingSettings(fieldCode, settings);
    }

    boolean isEmpty() {
        return settings.isEmpty();
    }

    /** @return de waarde, of {@code null} wanneer de sleutel niet gedeclareerd is */
    String get(String key) {
        used.add(key);
        return settings.get(key);
    }

    /**
     * @throws ScreeningBlockedException wanneer de sleutel ontbreekt of leeg is; een bewerking zonder
     *                                   haar parameter wordt nooit "dan maar niets doen"
     */
    String require(String key, String reason) {
        String value = get(key);
        if (value == null || value.isEmpty()) {
            throw invalid(fieldCode, null, "setting '" + key + "' is required: " + reason);
        }
        return value;
    }

    boolean flag(String key, boolean defaultValue) {
        String value = get(key);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw invalid(fieldCode, value, "setting '" + key + "' must be true or false");
    }

    int integer(String key, String reason) {
        String value = require(key, reason);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException notANumber) {
            throw invalid(fieldCode, value, "setting '" + key + "' must be a whole number");
        }
    }

    /** Eén teken; een instelling van twee tekens is een fout en geen "neem het eerste". */
    char character(String key, String reason) {
        String value = require(key, reason);
        if (value.length() != 1) {
            throw invalid(fieldCode, value, "setting '" + key + "' must be exactly one character");
        }
        return value.charAt(0);
    }

    /** Eén teken, of {@code null} wanneer de sleutel niet gedeclareerd is. */
    Character optionalCharacter(String key) {
        String value = get(key);
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (value.length() != 1) {
            throw invalid(fieldCode, value, "setting '" + key + "' must be exactly one character");
        }
        return value.charAt(0);
    }

    List<String> list(String key, String reason) {
        List<String> parts = new ArrayList<>();
        for (String part : split(require(key, reason), LIST_SEPARATOR)) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        if (parts.isEmpty()) {
            throw invalid(fieldCode, null, "setting '" + key + "' is required: " + reason);
        }
        return List.copyOf(parts);
    }

    /** Een vertaaltabel {@code bron>doel|bron>doel}; een lege doelwaarde is toegelaten en betekent "". */
    Map<String, String> table(String key, String reason) {
        Map<String, String> table = new LinkedHashMap<>();
        for (String entry : split(require(key, reason), LIST_SEPARATOR)) {
            if (entry.isBlank()) {
                continue;
            }
            int separator = entry.indexOf(ENTRY_SEPARATOR);
            if (separator < 0) {
                throw invalid(fieldCode, entry, "translation '" + entry.trim() + "' is not of the form source"
                        + ENTRY_SEPARATOR + "target");
            }
            String from = entry.substring(0, separator).trim();
            String to = entry.substring(separator + 1).trim();
            if (from.isEmpty()) {
                throw invalid(fieldCode, entry, "translation '" + entry.trim() + "' has no source value");
            }
            if (table.putIfAbsent(from, to) != null) {
                throw invalid(fieldCode, entry, "source value '" + from + "' is translated more than once");
            }
        }
        if (table.isEmpty()) {
            throw invalid(fieldCode, null, "setting '" + key + "' is required: " + reason);
        }
        return Map.copyOf(table);
    }

    /**
     * Sluit de instellingen af: elke gedeclareerde sleutel moet ook gelezen zijn. Een sleutel die
     * niemand leest, betekent dat de beheerder een bewerking verwacht die niet gebeurt.
     *
     * @throws ScreeningBlockedException {@code CONFIG_TRANSFORM_INVALID}
     */
    void verifyFullyUsed() {
        for (String key : settings.keySet()) {
            if (!used.contains(key)) {
                throw invalid(fieldCode, key, "setting '" + key + "' is not used by this transformation or "
                        + "data type; it would silently have no effect");
            }
        }
    }

    private static List<String> split(String text, char separator) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == separator) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    private static ScreeningBlockedException invalid(String fieldCode, String sourceValue, String reason) {
        return new ScreeningBlockedException(ImportMappingConfigFactory.CODE_TRANSFORM_INVALID, fieldCode,
                sourceValue, null, "Mapping for '" + fieldCode + "' has an invalid transform configuration: "
                + reason);
    }
}
