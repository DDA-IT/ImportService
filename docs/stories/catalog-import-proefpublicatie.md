# Story — gecontroleerde catalogusimport in Prodis

## Doel

Als catalogusbeheerder wil ik een leveranciersbestand in Prodis laten verwerken tot een controleerbare leverancierscatalogus, zodat ik de catalogusartikelen, prijzen en fouten kan beoordelen zonder centrale operationele artikelen te creëren.

## Deploy-doel

Deze story wordt gedeployed in `C:\Users\Willem\IdeaProjects\Prodis`, volgens de bestaande lagen `Dao` → `Service` → `Web`. Zij wordt **niet** als parallel productiemodel in `CatalogImport` gedeployed.

## Bestaande data die hergebruikt wordt

- `CatalogImportProfile` inclusief mapping, businessregels en broninstellingen;
- `CatalogImportJob`, `CatalogImportRawRow`, `CatalogImportError` en `CatalogImportApprovalEvent` voor de volledige audittrail;
- `SupplierCatalog` en `CatalogArticle` plus prijs, beschrijvingen, attributen en externe referenties voor het resultaat;
- de bestaande unieke aanbiedingssleutel binnen één leverancierscatalogus;
- de bestaande Prodis-securityconventie via `PermissionService`.

## Gedrag

1. Een gebruiker start een importjob op een bestaand importprofiel.
2. De job bewaart de verwerkte bronregels en alle fouten met regelnummer en payload.
3. Geldige regels worden als voorstel gematerialiseerd naar één `SupplierCatalog`; de job is daarna `PENDING_APPROVAL` of `READY_FOR_ACTIVATION` volgens de gekozen businessregel.
4. Een bevoegde reviewer registreert een approval event met actor, tijdstip, beslissing en reden.
5. Bij activering krijgt de leverancierscatalogus status `ACTIVE`; de vorige actieve catalogus met dezelfde leverancier/catalogus/scope wordt `SUPERSEDED`.
6. De import maakt geen centraal `Article` en roept nooit `/api/catalog-articles/{id}/create-article` aan.

## Acceptatiecriteria

- De gebruiker kan een job herleiden tot profiel, bronbestand/checksum, regels, fouten en goedkeuringsbeslissingen.
- Een foutieve regel is zichtbaar met regelnummer, foutmelding en herstelstatus; een blocked/failed job activeert geen catalogus.
- Een opnieuw aangeboden bestand kan via checksum en scope idempotent worden behandeld; er ontstaan geen dubbele `CatalogArticle`-records.
- Catalogusartikelen zijn uniek volgens de bestaande sleutel inclusief `supplier_catalog_id` en `supplier_group_id`.
- Een geactiveerde catalogus is zichtbaar via de bestaande catalogusartikel-API/UI.
- Geen enkele test of productie-import maakt een centraal artikel, multi-supplierrelatie of voorraadlijn aan.
- Alleen een gebruiker met het daarvoor toegekende import/approval/activationrecht kan de overeenstemmende stap uitvoeren. `catalogArticles.modify` alleen is onvoldoende voor de scheiding van taken.

## Kleinste eerste verticale slice

Ondersteun één CSV-profiel met leverancier, referentie, leveranciersgroep, basisprijs en een standaardbeschrijving. Verwerk naar een `SupplierCatalog` in status `VALIDATED`, met `CatalogArticle`, `CatalogArticlePrice` en `CatalogArticleDescription`; maak fouten en approval events persistent. Laat activation en superseding pas toe na een gerichte review.

## Gerichte tests

- geldig bestand: job → catalogus → catalogusartikel, prijs en beschrijving;
- ontbrekende leveranciersreferentie/groep of onleesbare prijs: foutrecord en geen activatie;
- dezelfde regel tweemaal: constraint/idempotentie voorkomt duplicaat;
- tweede goedgekeurde catalogus in dezelfde scope: vorige wordt `SUPERSEDED`;
- verifieer expliciet dat geen `Article`, `ArticleMultiSupplier` of `ArticleStock` wordt gecreëerd;
- autorisatie: import, approval en activation zijn afzonderlijk afgedwongen.

## Nog te beslissen vóór productieactivering

- de concrete controlecatalogus/scope en naamgeving;
- welke van de bestaande importprofielen als eerste leverancier wordt genomen;
- exacte prijsvelden en blank-value-policy per profiel;
- retentie van oorspronkelijke bestanden en raw payloads;
- de namen en toekenning van de drie nieuwe rechten: importeren, goedkeuren en activeren.
