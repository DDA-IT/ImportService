package be.dda.catalogimport.domain;

/**
 * Levenscyclus van één {@link PublicationRunItem} (docs/design/publicatie-prodis-db-direct-design.md par. 2.4, changeset
 * 027-1). {@code PENDING → SENT → APPLIED | UNCHANGED | REJECTED | CONFLICT | RESULT_UNKNOWN}, plus {@code NOT_SENT}.
 * Een fout vóór de Prodis-commit zet een {@code SENT}-item terug op {@code PENDING} (zeker niet toegepast); een fout tijdens
 * de commit of een crash daarna geeft {@code RESULT_UNKNOWN}. P4a declareert enkel; het gedrag volgt in P5.
 */
public enum PublicationRunItemStatus {

    /** Aangemaakt of na een zeker niet-toegepaste fout terug klaar om (opnieuw) verstuurd te worden. */
    PENDING,

    /** Verzonden, antwoord nog niet verwerkt. */
    SENT,

    /** Definitief: de wijziging is in Prodis toegepast. */
    APPLIED,

    /** Definitief: Prodis stond al op de doelwaarde, niets geschreven. */
    UNCHANGED,

    /** Definitief: geweigerd (met reason_code); niets geschreven. */
    REJECTED,

    /** Definitief: de Prodis-waarde week af van de verwachting; niets geschreven. */
    CONFLICT,

    /** Uitkomst onbekend (fout tijdens commit of crash): opnieuw sturen met dezelfde sleutel en bytes. */
    RESULT_UNKNOWN,

    /** In deze run niet verstuurd (bv. CREATE in v1). */
    NOT_SENT;

    /** @return {@code true} voor de vier definitieve antwoorden (status = outcome, antwoordtijdstip gevuld). */
    public boolean isAnswered() {
        return this == APPLIED || this == UNCHANGED || this == REJECTED || this == CONFLICT;
    }
}
