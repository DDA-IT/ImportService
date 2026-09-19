package be.dda.catalogimport.domain;

/**
 * Het gedeclareerde type van een doelveld (ontwerp fase 3, R-REC-01 en par. 2 004-1/004-2).
 * <p>
 * Artikelnummer, leveranciersnummer, groep, referentie, barcode, PIM- en CAB-identiteit zijn
 * <b>tekst</b>, nooit een getal: voorloopnullen, lengte en schrijfwijze zijn betekenisdragend
 * (R-REC-01). Een numeriek type op zo'n veld is een configuratiefout, geen conversieopdracht.
 */
public enum FieldDataType {

    TEXT,
    DECIMAL,
    INTEGER,
    DATE,
    DATETIME,
    BOOLEAN
}
