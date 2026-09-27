package be.dda.catalogimport.domain;

/**
 * Langs welke ontvangstweg een {@link Delivery} bij CatalogImport binnenkwam (beslissingslog 2026-09-27,
 * "tweede ontvangstweg — levering inlezen uit een beheerde servermap", Q2).
 * <p>
 * Dit is <b>herkomst, geen status</b>: de waarde wordt bij de ontvangst één keer vastgelegd en daarna nooit
 * meer gewijzigd. Ze zegt niets over de geldigheid of de verwerking van de levering — twee leveringen met
 * dezelfde inhoud langs een andere weg blijven inhoudelijk identiek — maar maakt permanent auditeerbaar
 * waar de bytes vandaan kwamen.
 */
public enum DeliverySourceKind {

    /** Door een gebruiker via de browser geüpload ({@code POST /tasks/{id}/deliveries}). */
    UPLOAD,

    /**
     * Ingelezen uit de beheerde servermap ({@code catalogimport.local-source.directory}), waar een mens het
     * bestand buiten CatalogImport om geplaatst heeft. Het bronbestand blijft daarbij ongemoeid.
     */
    LOCAL_DIRECTORY
}
