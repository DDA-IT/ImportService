package be.dda.catalogimport.domain;

/** Ernst van een {@link ImportRowIssue}. */
public enum RowIssueSeverity {

    /** De regel wordt verworpen of de batch geblokkeerd. */
    ERROR,

    /** Informatief; de regel blijft geldig (bv. verwijderde BOM). */
    WARNING
}
