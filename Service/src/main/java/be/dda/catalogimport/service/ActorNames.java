package be.dda.catalogimport.service;

/**
 * Gedeelde tekstvalidatie voor namen en vrije tekst (redenen, referenties) in Service: verplicht,
 * niet leeg, een maximale lengte, en voor een naam nooit {@code system} (hoofdletterongevoelig) — een
 * acceptatie, beslissing of andere geauditeerde actie is altijd van een mens.
 * <p>
 * Bouwstap 4b: uit {@link SourceStateBaselineService} gehaald (daar heetten de methoden
 * {@code requireAcceptedBy}/{@code requireText}) zodat {@code PublicationBundleService} dezelfde
 * regels hergebruikt in plaats van ze te kopiëren. Package-private: enkel bedoeld voor Service-klassen
 * in dit pakket, geen publiek contract.
 */
final class ActorNames {

    private static final String SYSTEM_USER = "system";

    private ActorNames() {
        // Enkel statische helpers.
    }

    /** Nooit stil afkappen: een te lange waarde wordt geweigerd. */
    static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + field);
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
        }
        return trimmed;
    }

    /**
     * Zoals {@link #requireText}, maar weigert bovendien {@code system} (hoofdletterongevoelig): een
     * naam op een geauditeerde actie is altijd van een mens.
     */
    static String requireActorName(String value, String field, int maxLength) {
        String name = requireText(value, field, maxLength);
        if (SYSTEM_USER.equalsIgnoreCase(name)) {
            throw new IllegalArgumentException(field + " must be a person, not '" + SYSTEM_USER + "'");
        }
        return name;
    }
}
