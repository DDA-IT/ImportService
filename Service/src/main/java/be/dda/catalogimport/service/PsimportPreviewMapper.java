package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.PsimportPreviewDao.SourceRow;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure mappinglogica van een {@code import_mutation}-rij naar de PSIMPORT-preview (beslissing
 * 2026-09-25, slice 1). Geen database, geen Spring: rechtstreeks unit-testbaar.
 * <p>
 * Sleutel van een veld is de interne code ({@code import_field_catalog.code} of, voor de
 * identiteitsvelden, de {@code import_field_mapping.field_key}); het h.26-label is enkel weergave.
 * <b>Nooit</b> een stille 0, lege tekst of aangenomen waarde: ontbrekende data geeft een expliciete
 * state. {@code complete} is {@code false} zodra minstens één veld {@link State#UNKNOWN} is.
 */
public final class PsimportPreviewMapper {

    public enum State { VALUE, NOT_MAPPED, NOT_AVAILABLE_IN_MUTATION, NOT_CONTRACTED, UNKNOWN }

    public record Field(String code, String label, String value, State state) {
    }

    public record Row(long batchId, long mutationId, String actionType, boolean complete, List<Field> fields) {
    }

    private PsimportPreviewMapper() {
    }

    public static Row map(SourceRow source) {
        List<Field> fields = new ArrayList<>();
        fields.add(text("SUPPLIER", "Leverancier Nummer", source.identitySupplier()));
        fields.add(text("SUPPLIER_GROUP", "Leverancier Groep", source.identitySupplierGroup()));
        fields.add(text("SUPPLIER_REFERENCE", "Leverancier Referentie", source.identitySupplierReference()));
        fields.add(discount(source.identityDiscountCode(), source.identityDiscountState()));
        fields.add(basePrice(source.afterBasePrice()));
        fields.add(text("BASE_PRICE_CURRENCY", "Valuta", source.basePriceCurrency()));
        fields.addAll(references(source.referenceType(), source.afterReferenceValue()));
        fields.add(new Field("DESCRIPTION", "Omschrijving NED", null, State.NOT_AVAILABLE_IN_MUTATION));
        for (int n = 1; n <= 5; n++) {
            fields.add(new Field("VKP" + n + "_PCT", "Prijs " + n + " %", null, State.NOT_AVAILABLE_IN_MUTATION));
        }
        fields.add(new Field("PROCESS", "Verwerken", null, State.NOT_CONTRACTED));
        fields.add(new Field("DELETE", "DELETE", null, State.NOT_CONTRACTED));
        fields.add(new Field("RECORD", "Record", null, State.NOT_CONTRACTED));
        fields.add(new Field("NUMBER", "Nummer", null, State.NOT_CONTRACTED));
        boolean complete = fields.stream().noneMatch(field -> field.state() == State.UNKNOWN);
        return new Row(source.batchId(), source.mutationId(), source.actionType(), complete, List.copyOf(fields));
    }

    /** Een verplichte tekstwaarde: aanwezig is VALUE, {@code null} is UNKNOWN (nooit "" of een default). */
    static Field text(String code, String label, String value) {
        return value == null ? new Field(code, label, null, State.UNKNOWN) : new Field(code, label, value, State.VALUE);
    }

    /**
     * Basisprijs als string via {@code toPlainString()}, nooit een JSON-getal en nooit 0 bij een ontbrekende
     * prijs (dan UNKNOWN).
     */
    static Field basePrice(BigDecimal price) {
        return price == null ? new Field("BASE_PRICE", "Basisprijs", null, State.UNKNOWN)
                : new Field("BASE_PRICE", "Basisprijs", price.toPlainString(), State.VALUE);
    }

    /**
     * Kortingscode: NOT_USED (kortingscode niet gemapt) is NIET gelijk aan een lege waarde. NOT_USED geeft
     * state NOT_MAPPED zonder waarde; EMPTY (gemapt maar leeg) geeft VALUE met lege tekst; VALUE geeft VALUE.
     * Een ontbrekende of onbekende state, of VALUE zonder waarde, is UNKNOWN.
     */
    static Field discount(String code, String state) {
        String label = "Korting Code";
        if ("NOT_USED".equals(state)) {
            return new Field("DISCOUNT_CODE", label, null, State.NOT_MAPPED);
        }
        if ("EMPTY".equals(state)) {
            return new Field("DISCOUNT_CODE", label, "", State.VALUE);
        }
        if ("VALUE".equals(state) && code != null) {
            return new Field("DISCOUNT_CODE", label, code, State.VALUE);
        }
        return new Field("DISCOUNT_CODE", label, null, State.UNKNOWN);
    }

    /**
     * Referentie naar Barcode (EAN, 21) of EXTERNAL_PIM_ID (PIM_ID, 105) volgens {@code reference_type}.
     * Zonder referentietype draagt de mutatie geen referentie (NOT_AVAILABLE_IN_MUTATION); een ander of
     * onbekend type geeft een UNKNOWN-veld, nooit een gegokte mapping.
     */
    static List<Field> references(String type, String value) {
        Field ean = new Field("EAN", "Barcode", null, State.NOT_AVAILABLE_IN_MUTATION);
        Field pim = new Field("PIM_ID", "EXTERNAL_PIM_ID", null, State.NOT_AVAILABLE_IN_MUTATION);
        if (type == null) {
            return List.of(ean, pim);
        }
        switch (type) {
            case "EAN":
                return List.of(text("EAN", "Barcode", value), pim);
            case "PIM_ID":
                return List.of(ean, text("PIM_ID", "EXTERNAL_PIM_ID", value));
            default:
                return List.of(ean, pim, new Field("REFERENCE", "Referentie (type " + type + ")", null, State.UNKNOWN));
        }
    }
}
