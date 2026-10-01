package be.dda.catalogimport.service.support;

import be.dda.catalogimport.service.support.SourceStructureConfig.FieldReferenceKind;

/**
 * Wat de mappingcontrole van de bronstructuur moet weten (NT-14a par. 2), ook wanneer die structuur
 * zelf fouten bevat en er dus geen {@link SourceStructureConfig} opgebouwd wordt.
 * <p>
 * Elk gegeven zegt ook of het betrouwbaar is; een controle die erop steunt, wordt bij een
 * onbetrouwbaar gegeven niet beoordeeld en als overgeslagen gemeld:
 * <ul>
 *   <li>{@code referenceKind == null}: de referentiesoort (S6) is ongeldig → geen
 *       kolomindexcontroles op mappings en filters.</li>
 *   <li>{@code columnCountValid == false}: het kolomaantal (S8) is ongeldig → enkel "geen getal" en
 *       "kleiner dan 1", geen bovengrens.</li>
 *   <li>{@code identityFieldsValid == false}: een identiteitskolom (S9) ontbreekt → geen controle op
 *       een sterke identiteitsregel (M5).</li>
 *   <li>{@code canonicalisationVersionSupported == false}: de herkenningsversie (S14) wordt niet
 *       ondersteund → geen controle op de vereiste versie (M6).</li>
 * </ul>
 *
 * @param referenceKind       de referentiesoort, of {@code null} wanneer die ongeldig is
 * @param expectedColumnCount het gedeclareerde kolomaantal zoals het op de revisie staat, of
 *                            {@code null} wanneer niet gedeclareerd
 */
public record StructureFacts(FieldReferenceKind referenceKind, Integer expectedColumnCount,
                             boolean columnCountValid, boolean identityFieldsValid,
                             boolean canonicalisationVersionSupported) {

    /** De feiten van een volledig gevalideerde bronstructuur: alles betrouwbaar. */
    public static StructureFacts of(SourceStructureConfig structure) {
        return new StructureFacts(structure.fieldReferenceKind(), structure.expectedColumnCount(), true, true,
                true);
    }

    /**
     * De bovengrens voor een kolomindex: het kolomaantal wanneer dat geldig is, anders {@code null}
     * (geen bovengrens toetsen).
     */
    public Integer upperBound() {
        return columnCountValid ? expectedColumnCount : null;
    }
}
