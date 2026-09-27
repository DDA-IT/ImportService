package be.dda.catalogimport.service;

import java.util.Locale;

/**
 * Leidt de deterministische {@code deliveryReference} af uit bestandsnaam én inhoudshash — de
 * <b>serverkant van exact dezelfde regel</b> als {@code Frontend/src/features/upload/deliveryReference.ts}
 * (beslissingslog 2026-09-23 stap 9; 2026-09-27 Q1 = optie A).
 * <p>
 * <b>Deze klasse en {@code deliveryReference.ts} zijn één regel in twee talen en moeten altijd samen
 * wijzigen.</b> Lopen ze uit elkaar, dan krijgt hetzelfde bestand via de browser een andere referentie dan
 * via de servermap, en wordt eenzelfde levering twee keer geregistreerd in plaats van als idempotente retry
 * herkend. Er is bewust geen gedeelde bron: de browser hasht in JavaScript, de server in Java. De regel
 * wordt daarom aan beide kanten getest, met dezelfde voorbeelden.
 * <p>
 * Vorm: {@code <bestandsnaam ingekort tot 177 tekens>#<eerste 12 hex-tekens van de SHA-256>}, samen
 * hoogstens {@value #MAX_LENGTH} tekens. Nooit een tijdstempel: dan zou een herhaalde levering van hetzelfde
 * bestand geen retry meer zijn.
 */
public final class DeliveryReferences {

    /** Servergrens van {@code deliveryReference}; identiek aan {@code MAX_DELIVERY_REFERENCE_LENGTH} in TS. */
    public static final int MAX_LENGTH = 190;

    /** Aantal hex-tekens van de SHA-256 in de referentie; identiek aan {@code HASH_PREFIX_LENGTH} in TS. */
    static final int HASH_PREFIX_LENGTH = 12;

    private DeliveryReferences() {
        // Enkel statische helpers.
    }

    /**
     * De referentie van een bestand met deze naam en deze inhoudshash.
     * <p>
     * De naam wordt afgekapt op {@code MAX_LENGTH - 13} tekens (zoals {@code String.slice} in TS: korter
     * blijft ongewijzigd, nooit opgevuld); de hash wordt in kleine letters gebruikt, zoals de browser hem
     * met {@code toString(16)} produceert.
     *
     * @param fileName  de bestandsnaam zoals ze bij de ontvangst bekend is, niet leeg
     * @param sha256Hex de volledige SHA-256 van de inhoud in hex, minstens {@value #HASH_PREFIX_LENGTH} tekens
     * @throws IllegalArgumentException bij een lege naam of een onbruikbare hash — nooit stil een referentie
     *                                  zonder inhoudsbewijs
     */
    public static String derive(String fileName, String sha256Hex) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("Missing file name for the delivery reference");
        }
        if (sha256Hex == null || sha256Hex.length() < HASH_PREFIX_LENGTH) {
            throw new IllegalArgumentException("Missing content hash for the delivery reference");
        }
        String suffix = "#" + sha256Hex.substring(0, HASH_PREFIX_LENGTH).toLowerCase(Locale.ROOT);
        int nameBudget = MAX_LENGTH - suffix.length();
        String name = fileName.length() > nameBudget ? fileName.substring(0, nameBudget) : fileName;
        return name + suffix;
    }
}
