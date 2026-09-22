# Catalog import — technisch ontwerp van de proefversie

> Status: historisch ontwerp van een eerdere proefversie. Het is niet de actuele beschrijving van
> de broncode; zie
> [`../analysis/current-project-vs-businessanalyse-2.md`](../analysis/current-project-vs-businessanalyse-2.md)
> voor de vergelijking met de vernieuwde businessanalyse.

## Verantwoordelijkheden

- `CatalogImportController` vormt de HTTP-grens en valideert de verplichte requestvelden.
- `CatalogImportService` beheert de statusovergangen en voert per operatie één database-transactie uit.
- `CandidateOffer` is de gecontroleerde staginglaag; `ImportMutation` is het reviewbare plan; `LibraryOffer` is het publicatiedoel.
- Liquibase beheert het schema; Hibernate valideert het slechts bij starten (`ddl-auto: validate`).

## Datamodel

```text
CatalogSource 1 ── * ImportDefinition 1 ── * ImportBatch
                                           ├── * CandidateOffer
                                           └── * ImportMutation ── 1 CandidateOffer

LibraryOffer is geïdentificeerd door (libraryCode, supplierCode, supplierReference).
```

`ImportBatch` bindt de ongewijzigde invoer aan één mappingversie. Daardoor blijft een gepubliceerd resultaat herleidbaar tot de ontvangen gegevens, ook wanneer later een nieuwe definitieversie wordt aangemaakt.

## Verwerkingsflow en foutafhandeling

1. Registreer bron en importdefinitie.
2. Upload CSV; bereken SHA-256 en geef een bestaand batch terug bij dezelfde definitie en inhoud.
3. Bewaar nieuw batch met originele inhoud en screen header en records.
4. Blokkeer bij een structurele fout of ten minste één ongeldige/dubbele record.
5. Bepaal tegen `LibraryOffer` CREATE/UPDATE/no-op en blokkeer een verdachte bulkcreatie.
6. Laat alleen een benoemde approver een `PLANNED` batch publiceren.
7. Schrijf alle geplande mutaties naar `LibraryOffer` en markeer het batch `PUBLISHED`.

Programmeerfouten in de service vallen niet onder de huidige API-exceptionhandler. Voor business- en statusfouten levert die handler HTTP 400 met `error`.

## Eerste verticale versie

De huidige implementatie is de minimale end-to-end versie: één manueel CSV-bestand, één definitie, één doelbibliotheek, vier kernkolommen en expliciete publicatie. Dit levert controleerbare catalogusaanbiedingen op zonder afhankelijkheid van externe leveranciers- of Prodis-contracten.

## Bewuste ontwerpgrenzen voor een volgende iteratie

- maak `ImportIssue` persistent voor foutcode, bronnr., veld en leesbare oorzaak;
- leg approval-audit vast (`approver`, `approvedAt`) voordat autorisatie wordt aangesloten;
- voeg concrete leveranciersmapping toe als versieerbare configuratie, inclusief validatie met voorbeeldregels;
- ontwerp eigenaarschap (`bron + bibliotheek + aanbod`) vóór INACTIVATE of destructieve synchronisatie;
- integreer pas met Prodis/WebBase na een overeengekomen extern contract en reconciliatieflow.
