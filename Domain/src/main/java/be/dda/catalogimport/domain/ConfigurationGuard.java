package be.dda.catalogimport.domain;

import java.util.Locale;

/**
 * Gedeelde invoercontroles voor de entiteiten van changeset 014 (verbindingsprofiel, Leveringsconfiguratie,
 * ophaalconfiguratie-events). Elke melding noemt enkel de naam van het gegeven, nooit de waarde: een host,
 * gebruikersnaam of vingerafdruk komt zo niet in een log of exceptie terecht.
 */
final class ConfigurationGuard {

    private ConfigurationGuard() {
    }

    static <T> T required(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    static String requiredText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    static String requiredText(String value, String name, int maxLength) {
        requiredText(value, name);
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(name + " is too long");
        }
        return value;
    }

    static String optionalText(String value, String name, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException(name + " is too long");
        }
        return value;
    }

    static int atLeast(int value, int minimum, String name) {
        if (value < minimum) {
            throw new IllegalArgumentException(name + " is out of range");
        }
        return value;
    }

    /**
     * Weigert (normaliseert niet) een host die {@code ck_connection_profile_version_host_normalized} zou weigeren;
     * normaliseren hoort in de service (LC-2, {@code HostNames}).
     */
    static String normalizedHost(String host, String name) {
        requiredText(host, name, 255);
        if (!host.equals(host.toLowerCase(Locale.ROOT)) || host.endsWith(".")
                || host.codePoints().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(name + " must be normalized (lower case, no trailing dot, no whitespace)");
        }
        return host;
    }

    /** Wie handelde: een subject zonder naam bestaat niet (koppelcheck van changeset 007). */
    static void checkSubjectHasName(String by, String subject, String name) {
        if (subject != null && (by == null || by.isBlank())) {
            throw new IllegalArgumentException(name + " subject requires a name");
        }
    }
}
