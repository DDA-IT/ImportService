package be.dda.catalogimport.web;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * De drie rechten van CatalogImport (Fase 5-PERM, bindend ontwerp
 * {@code docs/design/fase5-perm-design.md} par. 1; beslissingslog 2026-09-18 "permissiemodel" en
 * 2026-09-26 V2).
 *
 * <h2>Business rule vs. implementatie</h2>
 * <ul>
 *   <li><b>Regel:</b> grofmazig, drie rechten: lezen, beheren, goedkeuren. <b>Implementatie:</b> deze enum;
 *       de codes {@code catalogImport.read/.manage/.approve} zijn het contract met de rechtenbron
 *       (Prodis) en met de SPA, de enumnamen zijn intern.</li>
 *   <li><b>Regel (keuze mens, 2026-09-26, V2):</b> {@code approve} impliceert {@code manage} en
 *       {@code read}; {@code manage} impliceert {@code read}. <b>Implementatie:</b> {@link #effective}
 *       — de <b>enige</b> plek waar die afleiding gebeurt. De bron levert altijd de <i>ruwe</i> codes;
 *       niemand anders mag ze uitbreiden.</li>
 * </ul>
 *
 * <p>Gevolg van de hiërarchie dat bewust aanvaard is: wie {@code .approve} krijgt toegewezen, krijgt
 * lezen en beheren er automatisch bij.</p>
 */
public enum Permission {

    /** Alle GET's (beslissingslog 2026-09-25, A2). */
    READ("catalogImport.read"),

    /** Upload, {@code continue}, setup/materialisatie/bookmark-PUT, bundel aanmaken, batches. */
    MANAGE("catalogImport.manage"),

    /** {@code accept-baseline}, beslissingen, bevriezen, annuleren, publiceren. */
    APPROVE("catalogImport.approve");

    private final String code;

    Permission(String code) {
        this.code = code;
    }

    /** De code zoals de rechtenbron en de SPA ze kennen, bv. {@code catalogImport.approve}. */
    public String code() {
        return this.code;
    }

    /**
     * Het effectieve recht: de toegekende rechten plus alles wat ze volgens de hiërarchie impliceren.
     * <b>Eén plek</b> (ontwerp par. 1) — {@code CurrentActor} roept dit aan, elke bron levert ruwe codes.
     *
     * @param granted de ruwe, toegekende rechten; {@code null} of leeg = niemand heeft iets (fail-closed)
     * @return een onveranderlijke set; nooit {@code null}
     */
    public static Set<Permission> effective(Collection<Permission> granted) {
        EnumSet<Permission> effective = EnumSet.noneOf(Permission.class);
        if (granted != null) {
            for (Permission permission : granted) {
                if (permission != null) {
                    effective.addAll(permission.implies());
                }
            }
        }
        return Collections.unmodifiableSet(effective);
    }

    /**
     * Herkent één ruwe waarde: de volledige code ({@code catalogImport.approve}) of de korte naam
     * ({@code approve}), getrimd en hoofdletterongevoelig. Beide vormen komen in de documentenset voor:
     * de bron levert codes (ontwerp par. 2), de yml-configuratie korte namen (ontwerp par. 2, voorbeeld).
     *
     * @return leeg bij {@code null}, blanco of een onbekende waarde — de aanroeper beslist of dat een
     *         opstartfout is (configuratie) of stil genegeerd wordt (externe bron)
     */
    public static Optional<Permission> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return Optional.empty();
        }
        for (Permission permission : values()) {
            if (permission.code.equalsIgnoreCase(value) || permission.name().equalsIgnoreCase(value)) {
                return Optional.of(permission);
            }
        }
        return Optional.empty();
    }

    /** Alle toegelaten schrijfwijzen, enkel voor een foutboodschap bij een onbekende waarde in de yml. */
    static String knownValues() {
        StringBuilder known = new StringBuilder();
        for (Permission permission : values()) {
            if (known.length() > 0) {
                known.append(", ");
            }
            known.append(permission.name().toLowerCase(Locale.ROOT)).append(" (").append(permission.code).append(')');
        }
        return known.toString();
    }

    /** Dit recht plus wat het rechtstreeks meebrengt. */
    private Set<Permission> implies() {
        return switch (this) {
            case READ -> EnumSet.of(READ);
            case MANAGE -> EnumSet.of(MANAGE, READ);
            case APPROVE -> EnumSet.of(APPROVE, MANAGE, READ);
        };
    }
}
