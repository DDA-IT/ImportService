package be.dda.catalogimport.service.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Losse, herbruikbare validatiehulpmethoden die de vervangen proefversie al correct
 * implementeerde. Ze worden hier bewust als kleine statische regels bewaard — niet als
 * onderdeel van een God-class — zodat fase 2/3 erop verder kan bouwen.
 * <p>
 * Fase 1 gebruikt deze klasse nog niet; zij bevat geen domeinlogica en geen persistentie.
 */
public final class ImportValueRules {

    /** Maximale schaal van een ingelezen decimale waarde; strenger dan de doelgrens. */
    public static final int MAX_DECIMAL_SCALE = 6;

    private ImportValueRules() {
    }

    /**
     * Verplicht veld: leeg, blanco of {@code null} is een expliciete fout, nooit een stille default.
     *
     * @return de getrimde waarde
     */
    public static String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing " + field);
        }
        return value.trim();
    }

    /**
     * Decimale parsing zonder stille nul (businessanalyse §16.5: een ontbrekende of onleesbare
     * prijs wordt nooit nul). Accepteert zowel {@code ,} als {@code .} als decimaalteken, weigert
     * een bronschaal groter dan {@link #MAX_DECIMAL_SCALE} en rondt pas daarna met {@code HALF_UP}
     * af op die schaal. Er wordt nooit via {@code float}/{@code double} geconverteerd.
     */
    public static BigDecimal decimal(String raw, String field) {
        String text = required(raw, field);
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(text.replace(',', '.'));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid decimal for " + field + ": " + text);
        }
        if (parsed.scale() > MAX_DECIMAL_SCALE) {
            throw new IllegalArgumentException(
                    "Decimal scale exceeds " + MAX_DECIMAL_SCALE + " for " + field + ": " + text);
        }
        return parsed.setScale(MAX_DECIMAL_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Duplicaatdetectie binnen één levering: dezelfde aanbiedingsidentiteit mag maar één keer
     * voorkomen. De aanroeper bepaalt hoe de identiteit is samengesteld; deze regel bewaakt enkel
     * dat ze uniek blijft en blokkeert expliciet in plaats van de tweede rij stil te negeren.
     */
    public static <T> void rejectDuplicateIdentity(Set<T> seenIdentities, T identity) {
        if (!seenIdentities.add(identity)) {
            throw new IllegalArgumentException("Duplicate offer identity: " + identity);
        }
    }

    /**
     * Splitst één CSV-regel volgens RFC4180-achtige quoting. Een niet-gesloten aanhalingsteken is
     * een expliciete fout: de regel wordt nooit "zo goed mogelijk" ingelezen.
     */
    public static String[] parseCsvLine(String line, char delimiter) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            if (current == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append(current);
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (current == delimiter && !quoted) {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(current);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("Unclosed quoted CSV field");
        }
        fields.add(field.toString());
        return fields.toArray(String[]::new);
    }

    /** Hexadecimale SHA-256 van bronbytes, voor de inhoudshash van een gearchiveerd bestand. */
    public static String sha256Hex(byte[] input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("Cannot calculate content hash", unavailable);
        }
    }
}
