package be.dda.catalogimport.service.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wat er — náást de gedeclareerde revisievelden — van de header van dit bronbestand verwacht wordt
 * (ontwerp fase 3, R-STR-02/R-STR-03): de kolommen van de veldmappings en van de recordfilters, met
 * per kolom de positie waarop ze bij het vastleggen van de revisie stond.
 * <p>
 * <b>De naam blijft leidend, de positie is enkel een controle.</b> Een kolom die verschoven is wordt
 * op haar naam teruggevonden en gemeld als waarschuwing; er wordt nooit stil op positie gemapt.
 * <p>
 * <b>Beperking.</b> De bestaande revisiekolommen ({@code identity_supplier_field},
 * {@code record_base_price_field}, ...) dragen geen verwachte positie. De positiecontrole geldt
 * daarom uitsluitend voor mappingrijen met een {@code expected_position}; voor de revisievelden
 * blijft het bestaande gedrag gelden (ontbrekend veld ⇒ {@code HEADER_FIELD_MISSING}).
 */
public record HeaderExpectations(List<ExpectedField> fields) {

    /**
     * Eén verwachte bronkolom.
     *
     * @param reference             de veldreferentie zoals ze in de configuratie staat (headernaam of
     *                              1-gebaseerde kolomindex)
     * @param expectedPosition      1-gebaseerde positie waarop deze kolom verwacht wordt, of
     *                              {@code null} wanneer de revisie geen positie vastgelegd heeft
     * @param semanticallyCritical  of dit een identiteits-, prijs- of referentieveld is; een andere
     *                              headernaam op de verwachte positie van zo'n veld blokkeert de
     *                              levering ({@code HEADER_FIELD_SEMANTIC_CHANGE}) in plaats van te
     *                              waarschuwen
     * @param requiredInHeader      of de kolom aanwezig móét zijn; {@code false} voor filterkolommen,
     *                              die hun eigen {@code missing_column_behaviour} hebben
     */
    public record ExpectedField(String reference, Integer expectedPosition, boolean semanticallyCritical,
                                boolean requiredInHeader) {

        /** Combineert twee verwachtingen op dezelfde kolom tot de strengste van de twee. */
        ExpectedField merge(ExpectedField other) {
            return new ExpectedField(reference,
                    expectedPosition != null ? expectedPosition : other.expectedPosition(),
                    semanticallyCritical || other.semanticallyCritical(),
                    requiredInHeader || other.requiredInHeader());
        }
    }

    public HeaderExpectations {
        fields = List.copyOf(fields);
    }

    /** Geen enkele verwachting: de positiecontrole en de controle op onbekende kolommen blijven uit. */
    public static HeaderExpectations none() {
        return new HeaderExpectations(List.of());
    }

    /** Bouwt de verzameling met hoogstens één verwachting per kolomreferentie. */
    public static HeaderExpectations of(List<ExpectedField> fields) {
        Map<String, ExpectedField> byReference = new LinkedHashMap<>();
        for (ExpectedField field : fields) {
            byReference.merge(field.reference(), field, ExpectedField::merge);
        }
        return new HeaderExpectations(new ArrayList<>(byReference.values()));
    }

    public boolean isEmpty() {
        return fields.isEmpty();
    }

    /** Alle verwachte kolomreferenties, in de volgorde waarin ze gedeclareerd zijn. */
    public List<String> references() {
        return fields.stream().map(ExpectedField::reference).toList();
    }
}
