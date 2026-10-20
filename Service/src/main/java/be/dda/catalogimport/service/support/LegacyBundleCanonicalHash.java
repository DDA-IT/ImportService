package be.dda.catalogimport.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * De canonieke JSON en de twee inhoudshashes van een legacy-config-bundel v1 — de enige Java-implementatie in
 * CatalogImport van {@code docs/design/legacy-config-bundle-v1.md} par. 6 (normatief contract, S0). De datasync
 * ({@code CanonicalJson} in ProdisDatasyncEngine) is een tweede, onafhankelijke implementatie; beide moeten voor de
 * golden fixtures exact {@code expected-hashes.json} opleveren.
 *
 * <h2>Wat hier exact gebeurt (contract par. 6.1 en 6.2)</h2>
 * <ul>
 *   <li>Objecten: leden gesorteerd op sleutel in UTF-16-code-unit-volgorde ({@link String#compareTo}); leden met
 *       {@code null} blijven staan.</li>
 *   <li>Arrays: {@code interface.details}, {@code interface.omittedSecretLabels}, {@code definition.lines},
 *       {@code definition.maps} en {@code definition.excludes} worden gesorteerd op hun sleutels uit par. 6.2, met als
 *       laatste tie-breaker de canonieke tekst van het element zelf. {@code interface.header.weekdays} nooit.</li>
 *   <li>Strings zonder normalisatie; enkel {@code "}, {@code \}, {@code \b \t \n \f \r} en de overige tekens onder
 *       U+0020 ({@code \}{@code u00xx}, kleine hex) worden ge-escaped; al de rest (ook {@code /}, U+007F en niet-ASCII)
 *       staat letterlijk.</li>
 *   <li>Getallen enkel als integer in decimale notatie; een niet-integer getal is een programmeerfout (de validatie
 *       weigert het eerder met {@code TYPE}).</li>
 *   <li>Hash: SHA-256 over de UTF-8-bytes, hex in kleine letters.</li>
 * </ul>
 * Een ongepaarde surrogate kan niet als UTF-8 gecodeerd worden en geeft hier een {@link IllegalArgumentException}
 * (de validatie weigert ze eerder). Deze klasse logt niets en kent geen secret: {@code secret} zit nooit in de
 * gehashte objecten (par. 6.3).
 */
public final class LegacyBundleCanonicalHash {

    /** Het pad (vanaf de bundelwortel, enkel objectsleutels) van elke array die gesorteerd wordt, met zijn sleutels. */
    private static final Map<String, List<SortKey>> SORTED_ARRAYS = Map.of(
            "interface.details", List.of(SortKey.number("sequence"), SortKey.text("label"), SortKey.text("value")),
            "interface.omittedSecretLabels", List.of(SortKey.self()),
            "definition.lines", List.of(SortKey.number("number"), SortKey.number("column"),
                    SortKey.number("position"), SortKey.number("length"), SortKey.text("field")),
            "definition.maps", List.of(SortKey.number("number"), SortKey.text("key"), SortKey.text("value"),
                    SortKey.text("reserveAlpha")),
            "definition.excludes", List.of(SortKey.text("supplier"), SortKey.text("supplierGroup"),
                    SortKey.text("discountCode"), SortKey.text("supplierReference")));

    private static final String PRESENT = "PRESENT";

    private LegacyBundleCanonicalHash() {
        // Enkel statische helpers.
    }

    /**
     * {@code contentHash} (par. 6.3): SHA-256 over {@code canon({interface, definitionStatus, definition})}. Alle
     * andere leden van de bundel ({@code schema}, {@code version}, {@code origin}, {@code exportedAt},
     * {@code exporter}, beide hashvelden en {@code secret}) doen niet mee. Een ontbrekende {@code definition} telt als
     * {@code null}.
     */
    public static String contentHash(JsonNode bundle) {
        ObjectNode hashed = JsonNodeFactory.instance.objectNode();
        hashed.set("interface", bundle.get("interface"));
        hashed.set("definitionStatus", bundle.get("definitionStatus"));
        JsonNode definition = bundle.get("definition");
        hashed.set("definition", definition == null ? JsonNodeFactory.instance.nullNode() : definition);
        return sha256Hex(canonical(hashed, ""));
    }

    /**
     * {@code definitionContentHash} (par. 6.4): enkel bij {@code definitionStatus = PRESENT} de SHA-256 over de
     * canonieke {@code definition} zonder {@code code} en {@code description}; anders {@code null}.
     */
    public static String definitionContentHash(JsonNode bundle) {
        JsonNode status = bundle.get("definitionStatus");
        JsonNode definition = bundle.get("definition");
        if (status == null || !status.isTextual() || !PRESENT.equals(status.textValue())
                || definition == null || !definition.isObject()) {
            return null;
        }
        ObjectNode withoutIdentity = ((ObjectNode) definition).deepCopy();
        withoutIdentity.remove("code");
        withoutIdentity.remove("description");
        return sha256Hex(canonical(withoutIdentity, "definition"));
    }

    /**
     * De canonieke tekst van {@code node}, met de bundelwortel als vertrekpunt voor de padgebonden sortering
     * (zichtbaar voor tests).
     */
    public static String canonical(JsonNode node) {
        return canonical(node, "");
    }

    static String canonical(JsonNode node, String path) {
        StringBuilder out = new StringBuilder();
        write(node, path, out);
        return out.toString();
    }

    private static void write(JsonNode node, String path, StringBuilder out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            out.append("null");
        } else if (node.isObject()) {
            writeObject(node, path, out);
        } else if (node.isArray()) {
            writeArray(node, path, out);
        } else if (node.isTextual()) {
            writeString(node.textValue(), out);
        } else if (node.isBoolean()) {
            out.append(node.booleanValue() ? "true" : "false");
        } else if (node.isIntegralNumber()) {
            out.append(node.bigIntegerValue().toString());
        } else {
            // Breuken, exponenten, binaire of POJO-knopen bestaan niet in een geldige bundel (par. 6.1.5).
            throw new IllegalArgumentException("Not canonicalisable at " + displayPath(path) + ": "
                    + node.getNodeType());
        }
    }

    private static void writeObject(JsonNode node, String path, StringBuilder out) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        names.sort(Comparator.naturalOrder());
        out.append('{');
        boolean first = true;
        for (String name : names) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeString(name, out);
            out.append(':');
            write(node.get(name), path.isEmpty() ? name : path + "." + name, out);
        }
        out.append('}');
    }

    private static void writeArray(JsonNode node, String path, StringBuilder out) {
        List<SortKey> keys = SORTED_ARRAYS.get(path);
        // Elementen van een array dragen geen eigen padsegment: het contract kent geen geneste te sorteren arrays.
        List<Element> elements = new ArrayList<>();
        for (Iterator<JsonNode> it = node.elements(); it.hasNext(); ) {
            JsonNode element = it.next();
            elements.add(new Element(element, canonical(element, path + "[]")));
        }
        if (keys != null) {
            elements.sort((a, b) -> compare(a, b, keys));
        }
        out.append('[');
        for (int i = 0; i < elements.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(elements.get(i).canonical());
        }
        out.append(']');
    }

    private static int compare(Element a, Element b, List<SortKey> keys) {
        for (SortKey key : keys) {
            int result = key.compare(a.node(), b.node());
            if (result != 0) {
                return result;
            }
        }
        // Laatste tie-breaker (par. 6.2): de canonieke tekst van het element zelf.
        return a.canonical().compareTo(b.canonical());
    }

    /** Par. 6.1.4: minimale escaping; elk ander teken letterlijk. */
    static void writeString(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    throw new IllegalArgumentException("Unpaired surrogate in a string");
                }
                out.append(c).append(value.charAt(++i));
                continue;
            }
            if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("Unpaired surrogate in a string");
            }
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\f' -> out.append("\\f");
                case '\r' -> out.append("\\r");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u00").append(Character.forDigit(c >> 4, 16)).append(Character.forDigit(c & 0xF, 16));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    static String sha256Hex(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required but not available", impossible);
        }
    }

    private static String displayPath(String path) {
        return path.isEmpty() ? "$" : "$." + path;
    }

    private record Element(JsonNode node, String canonical) {
    }

    /** Eén sorteersleutel uit par. 6.2: een numeriek lid, een tekstlid of het element zelf (een string). */
    private record SortKey(String field, boolean numeric) {

        static SortKey number(String field) {
            return new SortKey(field, true);
        }

        static SortKey text(String field) {
            return new SortKey(field, false);
        }

        static SortKey self() {
            return new SortKey(null, false);
        }

        int compare(JsonNode a, JsonNode b) {
            JsonNode left = field == null ? a : (a == null ? null : a.get(field));
            JsonNode right = field == null ? b : (b == null ? null : b.get(field));
            if (numeric && left != null && right != null && left.isIntegralNumber() && right.isIntegralNumber()) {
                return left.bigIntegerValue().compareTo(right.bigIntegerValue());
            }
            if (!numeric && left != null && right != null && left.isTextual() && right.isTextual()) {
                return left.textValue().compareTo(right.textValue());
            }
            // Een geldige bundel komt hier nooit: de validatie eist het juiste type. Defensief beslist de
            // laatste tie-breaker (de canonieke tekst van het hele element).
            return 0;
        }
    }
}
