# Catalog import — acceptatiecriteria

## Functioneel

- Een gebruiker kan een bron en een unieke importdefinitieversie aanmaken.
- Een geldige CSV met de geconfigureerde vier kolommen kan worden geüpload, gepland, goedgekeurd en gepubliceerd.
- Een eerste geldige aanbieding wordt als CREATE gepubliceerd in de geconfigureerde controlebibliotheek.
- Een bestaande aanbieding met gewijzigde groep of prijs wordt als UPDATE gepubliceerd; een identieke actieve aanbieding veroorzaakt geen mutatie.
- Een lege header, ontbrekende vereiste kolom, lege kernwaarde, dubbele aanbiedingsidentiteit of onleesbare prijs blokkeert het batch en wijzigt niets in de bibliotheek.
- Een prijs wordt nooit impliciet nul wegens een parsefout.
- Een identieke bestandsinhoud voor dezelfde definitie geeft hetzelfde batch terug en veroorzaakt geen dubbele aanbieding.
- Bij bestaande bibliotheekomvang blokkeert een batch met meer dan 100 CREATE-mutaties of meer CREATE-mutaties dan de bestaande omvang.
- Publicatie zonder `PLANNED` status of met approver `system` wordt geweigerd.

## Traceerbaarheid en controles

- Support kan per batch definitie, hash, originele inhoud, bestandsnaam, ontvangsttijd, aantallen en eindstatus terugvinden.
- Per gepubliceerde mutatie is de kandidaat en vorige prijs (waar van toepassing) terug te vinden.
- De unieke databaseconstraints voorkomen dubbelresultaten, ook buiten de applicatielogica.

## Bestaande geautomatiseerde verificatie

`Web/src/test/java/be/dda/catalogimport/web/CatalogImportFlowTest.java` dekt momenteel:

- initiële publicatie plus hash-idempotentie;
- blokkade voor een onleesbare prijs;
- blokkade voor een bulkcreatie tegen bestaande scope.

Uit te voeren gerichte testcommando: `mvn -pl Web -am test`.
