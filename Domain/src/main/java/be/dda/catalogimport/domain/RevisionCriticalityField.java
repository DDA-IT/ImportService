package be.dda.catalogimport.domain;

import java.util.Optional;

/**
 * De revisie-eigen velden waarvan de kritiek-vlag in {@code import_revision_field_criticality} kan
 * staan (ontwerp fase 3, par. 15.1). Het zijn de velden die op de revisie zelf staan en dus geen
 * {@link ImportFieldMapping}-rij hebben (R-STR-06); de naam van de constante is de {@code field_key}.
 * <p>
 * Naast {@link RevisionOwnedField} bestaat deze enum apart, omdat de munt hier wél in thuishoort (ze
 * heeft een kritiek-vlag) maar niet in {@code RevisionOwnedField} (er bestaat geen doelveld
 * {@code CURRENCY} in de catalogus dat een dubbele bron zou kunnen zijn).
 * <p>
 * Standaard van de soort, wanneer er geen rij is: identiteit {@link Criticality#CRITICAL} en niet
 * instelbaar; {@code BASE_PRICE} en {@code CURRENCY} {@code CRITICAL} en instelbaar; {@code DESCRIPTION}
 * {@link Criticality#NON_CRITICAL} en instelbaar.
 */
public enum RevisionCriticalityField {

    SUPPLIER(true, Criticality.CRITICAL),
    SUPPLIER_GROUP(true, Criticality.CRITICAL),
    SUPPLIER_REFERENCE(true, Criticality.CRITICAL),
    DISCOUNT_CODE(true, Criticality.CRITICAL),
    BASE_PRICE(false, Criticality.CRITICAL),
    CURRENCY(false, Criticality.CRITICAL),
    DESCRIPTION(false, Criticality.NON_CRITICAL);

    private final boolean identity;
    private final Criticality defaultCriticality;

    RevisionCriticalityField(boolean identity, Criticality defaultCriticality) {
        this.identity = identity;
        this.defaultCriticality = defaultCriticality;
    }

    /** Een identiteitsveld is nooit {@link Criticality#NON_CRITICAL}. */
    public boolean isIdentity() {
        return identity;
    }

    /** De kritiek-vlag die geldt wanneer er geen overrule-rij bestaat. */
    public Criticality defaultCriticality() {
        return defaultCriticality;
    }

    /** @return het veld met deze {@code field_key}, of leeg voor een onbekende sleutel */
    public static Optional<RevisionCriticalityField> byKey(String fieldKey) {
        if (fieldKey == null) {
            return Optional.empty();
        }
        for (RevisionCriticalityField field : values()) {
            if (field.name().equals(fieldKey)) {
                return Optional.of(field);
            }
        }
        return Optional.empty();
    }
}
