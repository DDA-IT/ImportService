package be.dda.catalogimport.domain;

/**
 * Of een fout op een kolom kritiek is (ontwerp fase 3, par. 15.1; beslissingslog 20/09).
 * <p>
 * De gebruiker geeft per kolom zelf een waarde. Een fout op een {@link #CRITICAL kritieke} kolom maakt
 * van de bronregel een kritieke lijn en vraagt om een review; een fout op een {@link #NON_CRITICAL
 * niet-kritieke} kolom verwerpt de regel wel, maar vraagt geen review.
 * <p>
 * Bouwstap 3h-1 legt de vlag enkel vast en maakt ze bevraagbaar; er wordt nog niets mee geteld.
 */
public enum Criticality {

    CRITICAL,
    NON_CRITICAL;

    /**
     * De strengste van twee vlaggen: {@link #CRITICAL} wint altijd. Wanneer twee kolomnamen botsen,
     * mag de minder strenge nooit de strengere overschrijven (fail-safe).
     */
    public static Criticality defaultForMapping(String referenceType, String priceComponentCode) {
        return referenceType != null || priceComponentCode != null ? CRITICAL : NON_CRITICAL;
    }

    /**
     * Mag de kritiek-vlag van een mapping met dit referentietype {@link #NON_CRITICAL} zijn? Een
     * referentieveld is altijd kritiek ({@code ck_import_field_mapping_reference_critical}; par. 15.1).
     */
    public static boolean changeableForMapping(String referenceType) {
        return referenceType == null;
    }

    /**
     * De strengste van twee vlaggen: {@link #CRITICAL} wint altijd. Wanneer twee kolomnamen botsen,
     * mag de minder strenge nooit de strengere overschrijven (fail-safe).
     */
    public static Criticality strictest(Criticality first, Criticality second) {
        return first == CRITICAL || second == CRITICAL ? CRITICAL : NON_CRITICAL;
    }
}
