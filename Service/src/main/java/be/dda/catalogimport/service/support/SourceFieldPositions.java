package be.dda.catalogimport.service.support;

import java.util.Map;

/**
 * De oplossing van gedeclareerde veldreferenties naar 0-gebaseerde kolomposities van één
 * bronbestand. Wordt één keer per bestand gebouwd door {@link CsvRecordStreamer} (uit de header, of
 * uit de kolomindexen bij een bestand zonder header) en daarna door elke rij hergebruikt.
 * <p>
 * De sleutels zijn de referenties exact zoals ze in de configuratie staan, zodat de normaliser met
 * {@code config.supplierField()} kan opzoeken zonder de headerconventies te kennen.
 */
public record SourceFieldPositions(Map<String, Integer> byReference) {

    public SourceFieldPositions {
        byReference = Map.copyOf(byReference);
    }

    /** De 0-gebaseerde kolompositie, of {@code null} wanneer het veld niet opgelost kon worden. */
    public Integer position(String reference) {
        return byReference.get(reference);
    }
}
