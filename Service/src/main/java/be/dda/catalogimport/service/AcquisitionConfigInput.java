package be.dda.catalogimport.service;

/**
 * Gedeelde invoercontroles voor verbindingsprofielen, Leveringsconfiguraties en de taakkoppeling (bouwstap LC-2,
 * {@code docs/design/leveringsconfiguratie-design.md} par. 6/10). Package-private: geen publiek contract.
 * <p>
 * Elke tekst wordt aan de buitenkant getrimd (zoals {@link ActorNames#requireText}), nooit afgekapt: te lang is een
 * 400. Een <b>stuurteken</b> (bv. een regeleinde of {@code U+001F}) wordt geweigerd: zo kan een waarde nooit het
 * scheidingsteken van de canonieke {@code config_hash}-vorm bevatten ({@code RevisionConfigHashes.hash}) en blijft
 * die vorm eenduidig. De foutmeldingen noemen enkel de naam van het veld, nooit de waarde (host, login en map zijn
 * MANAGE-gegevens, L7b).
 */
final class AcquisitionConfigInput {

    private AcquisitionConfigInput() {
    }

    /**
     * Verplichte tekst: niet leeg, hoogstens {@code maxLength} tekens na trimmen, geen stuurtekens.
     *
     * @throws BadRequestException 400 met {@code code}
     */
    static String requireText(String value, String field, int maxLength, String code) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(code, "Missing " + field);
        }
        String trimmed = value.strip();
        if (trimmed.length() > maxLength) {
            throw new BadRequestException(code, field + " exceeds " + maxLength + " characters");
        }
        if (hasControlCharacter(trimmed)) {
            throw new BadRequestException(code, field + " must not contain control characters");
        }
        return trimmed;
    }

    static boolean hasControlCharacter(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }
}
