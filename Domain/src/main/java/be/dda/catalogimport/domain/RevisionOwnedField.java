package be.dda.catalogimport.domain;

import java.util.Optional;

/**
 * De doelvelden die de bestaande revisiekolommen al bepalen (aanname A19, R-STR-06).
 * <p>
 * Leverancier, leveranciersgroep, leveranciersreferentie, kortingscode, basisprijs en omschrijving
 * worden autoritair door {@link ImportDefinitionRevision} gedragen. Een {@link ImportFieldMapping}
 * die zo'n veld nóg eens bepaalt terwijl de bijbehorende revisiekolom gevuld is, levert twee bronnen
 * voor dezelfde waarde op; dat is altijd een configuratiefout
 * ({@code CONFIG_FIELD_MAPPING_DUPLICATES_REVISION}), ook wanneer beide bronnen toevallig hetzelfde
 * zeggen.
 * <p>
 * De naam van de constante is de doelveldcode. {@code SUPPLIER}, {@code SUPPLIER_GROUP},
 * {@code SUPPLIER_REFERENCE} en {@code DISCOUNT_CODE} staan bewust <b>niet</b> in de veldcatalogus:
 * ze zijn voorbehouden aan de revisie. {@code BASE_PRICE} en {@code DESCRIPTION} staan er wel in —
 * die zijn pas verboden zolang de revisiekolom ze bepaalt.
 */
public enum RevisionOwnedField {

    SUPPLIER("identity_supplier_field"),
    SUPPLIER_GROUP("identity_supplier_group_field"),
    SUPPLIER_REFERENCE("identity_supplier_reference_field"),
    DISCOUNT_CODE("identity_discount_code_field"),
    BASE_PRICE("record_base_price_field"),
    DESCRIPTION("record_description_field");

    private final String revisionColumn;

    RevisionOwnedField(String revisionColumn) {
        this.revisionColumn = revisionColumn;
    }

    /** De kolomnaam op {@code import_definition_revision} die dit veld bepaalt. */
    public String revisionColumn() {
        return revisionColumn;
    }

    /** @return het door de revisie gedragen veld met deze doelveldcode, indien er één is */
    public static Optional<RevisionOwnedField> byCode(String targetFieldCode) {
        if (targetFieldCode == null) {
            return Optional.empty();
        }
        for (RevisionOwnedField field : values()) {
            if (field.name().equals(targetFieldCode)) {
                return Optional.of(field);
            }
        }
        return Optional.empty();
    }
}
