package be.dda.catalogimport.service.support;

import be.dda.catalogimport.domain.DeliveryFileConditionKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Welke bestandsnamen een Leveringsconfiguratie-versie selecteert (bouwstap K-4a;
 * {@code docs/design/leveringsconfiguratie-design.md} par. 3.2 en 6 "Keuzes LC-2"). Pure klasse: geen Spring, geen
 * database, geen netwerk.
 * <ul>
 *   <li>{@code ALL_FILES} ({@link #allFiles()}): elke naam.</li>
 *   <li>{@code CONDITIONS} ({@link #conditions}): voorwaarden binnen een groep zijn <b>EN</b>, groepen onderling
 *       <b>OF</b>. Letterlijke vergelijking (geen wildcards, geen regex). {@code caseSensitive = false} vergelijkt beide
 *       kanten in kleine letters ({@link Locale#ROOT}).</li>
 *   <li>{@code EXTENSION_IS x}: de naam eindigt op {@code "." + x} (dus {@code tar.gz} mag; een naam die exact
 *       {@code ".csv"} is, telt ook).</li>
 * </ul>
 * Deze klasse beslist enkel over de naam. Of een ingang een gewoon bestand is (geen map of symlink) beslist de
 * aanroeper; minimale ouderdom en maximale grootte zijn beslissingen van de ophaalrun (K-4b), niet van de selectie.
 */
public final class RemoteFileSelection {

    /** Eén voorwaarde; {@code group} is het groepsnummer (EN binnen de groep, OF tussen groepen). */
    public record Condition(int group, DeliveryFileConditionKind kind, String value, boolean caseSensitive) {

        public Condition {
            if (kind == null) {
                throw new IllegalArgumentException("kind is required");
            }
            if (value == null || value.isEmpty()) {
                throw new IllegalArgumentException("value is required");
            }
        }
    }

    /** {@code null} = alle bestanden. */
    private final List<List<Condition>> groups;

    private RemoteFileSelection(List<List<Condition>> groups) {
        this.groups = groups;
    }

    /** Selectiemodus {@code ALL_FILES}. */
    public static RemoteFileSelection allFiles() {
        return new RemoteFileSelection(null);
    }

    /**
     * Selectiemodus {@code CONDITIONS}; de volgorde van de groepen volgt de eerste vermelding van hun nummer.
     *
     * @throws IllegalArgumentException geen enkele voorwaarde (een lege selectie zou stil niets of alles kiezen)
     */
    public static RemoteFileSelection conditions(List<Condition> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            throw new IllegalArgumentException("CONDITIONS needs at least one condition");
        }
        Map<Integer, List<Condition>> byGroup = new LinkedHashMap<>();
        for (Condition condition : conditions) {
            if (condition == null) {
                throw new IllegalArgumentException("a condition is missing");
            }
            byGroup.computeIfAbsent(condition.group(), g -> new ArrayList<>()).add(condition);
        }
        List<List<Condition>> groups = byGroup.values().stream().map(List::copyOf).toList();
        return new RemoteFileSelection(groups);
    }

    public boolean isAllFiles() {
        return groups == null;
    }

    /** {@code true} als de naam geselecteerd wordt. */
    public boolean matches(String fileName) {
        if (fileName == null) {
            return false;
        }
        if (groups == null) {
            return true;
        }
        for (List<Condition> group : groups) {
            if (group.stream().allMatch(condition -> matches(condition, fileName))) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(Condition condition, String fileName) {
        String name = condition.caseSensitive() ? fileName : fileName.toLowerCase(Locale.ROOT);
        String value = condition.caseSensitive() ? condition.value() : condition.value().toLowerCase(Locale.ROOT);
        return switch (condition.kind()) {
            case NAME_EQUALS -> name.equals(value);
            case NAME_STARTS_WITH -> name.startsWith(value);
            case NAME_ENDS_WITH -> name.endsWith(value);
            case NAME_CONTAINS -> name.contains(value);
            case EXTENSION_IS -> name.endsWith("." + value);
        };
    }

    @Override
    public String toString() {
        return groups == null ? "RemoteFileSelection[ALL_FILES]"
                : "RemoteFileSelection[CONDITIONS, groups=" + groups.size() + "]";
    }
}
