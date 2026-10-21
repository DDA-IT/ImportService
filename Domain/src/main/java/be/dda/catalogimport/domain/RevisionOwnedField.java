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
 * <p>
 * Sinds legacy-config S6 kunnen leverancier en leveranciersgroep ook door een <b>vaste waarde</b> op de revisie
 * bepaald worden ({@link #fixedValueColumn()}); ook dan blijft het veld van de revisie.
 */
public enum RevisionOwnedField {

    SUPPLIER("identity_supplier_field", "identity_supplier_fixed_value"),
    SUPPLIER_GROUP("identity_supplier_group_field", "identity_supplier_group_fixed_value"),
    SUPPLIER_REFERENCE("identity_supplier_reference_field", null),
    DISCOUNT_CODE("identity_discount_code_field", null),
    BASE_PRICE("record_base_price_field", null),
    DESCRIPTION("record_description_field", null);

    private final String revisionColumn;
    private final String fixedValueColumn;

    RevisionOwnedField(String revisionColumn, String fixedValueColumn) {
        this.revisionColumn = revisionColumn;
        this.fixedValueColumn = fixedValueColumn;
    }

    /** De kolomnaam op {@code import_definition_revision} die dit veld bepaalt. */
    public String revisionColumn() {
        return revisionColumn;
    }

    /**
     * De kolom op {@code import_definition_revision} die dit veld als vaste waarde kan bepalen (legacy-config S6), of
     * {@code null} wanneer dit veld enkel uit een bronkolom komt.
     */
    public String fixedValueColumn() {
        return fixedValueColumn;
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
