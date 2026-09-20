package be.dda.catalogimport.service.support;

import be.dda.catalogimport.domain.Criticality;
import be.dda.catalogimport.domain.RowIssueSeverity;

/**
 * Telt de <b>kritieke lijnen</b> van één levering (bouwstap 3h-2, ontwerp fase 3 par. 15.1).
 * <p>
 * <b>Business rule.</b> Een kritieke lijn is een verworpen bronregel met minstens één issue van ernst
 * {@link RowIssueSeverity#ERROR} op een kolom die kritiek is ({@link ImportMappingConfig#criticalityOf}),
 * of met een ERROR die niet aan een kolom toe te wijzen is (kolomaantal, te lange regel, niet-gesloten
 * aanhalingsteken: {@code fieldName == null}, dus onherleidbaar en daarom fail-safe kritiek).
 * Nooit een kritieke lijn: een WARNING of INFO (ook op een kritieke kolom), een regel die de beheerder
 * zelf via een REJECT-filter verwierp ({@link RecordFilterEvaluator#CODE_FILTER_RECORD_REJECTED}), en
 * een prijsafwijking (die verwerpt niets en draait buiten de staging).
 * <p>
 * <b>Technische implementatie.</b> Er wordt in leesvolgorde gestreamd, dus alle issues van één regel
 * komen na elkaar: het onthouden van het laatst getelde regelnummer volstaat om twee ERROR's op
 * dezelfde regel als één lijn te tellen. De teller is <b>nooit</b> gecapt: hij staat los van de
 * voorbeeldcap per foutcode en van de bewaarde voorbeeldrijen. Hij is niet thread-safe; één instantie
 * hoort bij één screening.
 */
public final class CriticalLineCounter {

    private long count;
    private long lastCriticalRowNumber = -1L;

    /**
     * Verwerkt één vastgestelde melding.
     *
     * @param mapping    de mappingconfiguratie van de revisie; {@code null} is een onbekende kolom en
     *                   dus fail-safe kritiek
     * @param fieldName  het veld zoals de melding het draagt (bronreferentie of logische veldnaam),
     *                   of {@code null} als het niet aan een kolom toewijsbaar is
     * @return {@code true} als deze melding een nieuwe kritieke lijn opleverde
     */
    public boolean record(ImportMappingConfig mapping, long rowNumber, RowIssueSeverity severity,
                          String code, String fieldName) {
        if (severity != RowIssueSeverity.ERROR
                || RecordFilterEvaluator.CODE_FILTER_RECORD_REJECTED.equals(code)) {
            return false;
        }
        Criticality criticality = mapping == null ? Criticality.CRITICAL : mapping.criticalityOf(fieldName);
        if (criticality != Criticality.CRITICAL || rowNumber == lastCriticalRowNumber) {
            return false;
        }
        count++;
        lastCriticalRowNumber = rowNumber;
        return true;
    }

    /** Het aantal kritieke lijnen tot nu toe; ongecapt. */
    public long count() {
        return count;
    }
}
