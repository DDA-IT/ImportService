# Catalog import — probleem en doel

## Status

Dit document beschrijft de huidige, werkende proefversie op basis van de broncode, de gerichte integratietest en `business-analyse-leveranciersbibliotheken.md`. Niet-bevestigde legacyfunctionaliteit is niet als vereiste van deze versie opgenomen.

## Bedrijfsprobleem

Leverancierscatalogi kunnen zeer veel artikelen bevatten en brondata is niet automatisch betrouwbaar. Zonder controle kan een foutieve levering op grote schaal bibliotheekaanbiedingen of prijzen wijzigen. De toepassing moet een levering daarom eerst bewaren, controleren en als voorgenomen mutaties tonen voordat zij de controlebibliotheek wijzigt.

## Gebruiker

Een bevoegde medewerker die een leverancierscatalogus beheert en een benoemde reviewer die de publicatie goedkeurt. De huidige proefversie heeft nog geen gekoppelde gebruikers- of autorisatieprovider; de approver wordt als tekst aan de API aangeleverd.

## Gewenst resultaat

De gebruiker kan:

- een catalogusbron registreren;
- een versie van een importdefinitie registreren, inclusief doelbibliotheek en de vier benodigde CSV-kolommen;
- een CSV-bestand uploaden;
- ongeldige leveringen blokkeren zonder de bibliotheek te wijzigen;
- een plan met CREATE- en UPDATE-mutaties laten maken;
- een gepland batch expliciet goedkeuren en publiceren;
- exact dezelfde levering veilig opnieuw aanbieden zonder dubbele verwerking.

Een gepubliceerde regel is een `LibraryOffer`: leverancier, leveranciersreferentie, groep en prijs binnen één controlebibliotheek. Dit maakt **geen** operationeel centraal artikel aan.

## Voorbeelden

### Geldige input

```csv
supplier;reference;group;price
02006;A-1;TOOLS;12,50
```

Met een definitie die de kolommen `supplier`, `reference`, `group` en `price` aanwijst, wordt een kandidaat-aanbieding gemaakt. Bij een lege controlebibliotheek resulteert dit na planning in een CREATE-mutatie. Na goedkeuring bestaat de aanbieding in die bibliotheek met prijs `12.500000`.

### Ongeldige input

```csv
supplier,reference,group,price
02006,A-1,TOOLS,12x
```

De prijs is geen decimaal. Het batch krijgt status `BLOCKED`, telt één issue en publiceert niets. Een onleesbare prijs wordt nooit als nul geïnterpreteerd.

### Herverwerking

De SHA-256-hash van de ongewijzigde bestandsinhoud is, samen met de importdefinitie, de idempotentiesleutel. Dezelfde inhoud voor dezelfde definitie geeft het bestaande batch terug, ook als de bestandsnaam verschilt.

## Buiten scope van deze proefversie

- leveranciersconnectors (FTP, API, XML/JSON, XLSX en fixed-width);
- configurabele transformaties, filters en formules uit de legacy-importdefinities;
- meerdere doelbibliotheken per batch;
- echte Prodis/WebBase-publicatie en synchronisatie met operationele artikelen;
- automatische creatie/promotie van operationele artikelen;
- gebruikersbeheer, Keycloak en afdwingbare vier-ogen-autorisatie;
- issuescherm, detailfouten per regel, herstelworkflow en notificaties;
- opschoning/inactivatie van niet langer aangeleverde records.
