# Catalog import — impactanalyse huidige applicatie

## Architectuur

De Maven-reactor bestaat uit `Domain`, `Dao`, `Service` en `Web`. De lagen volgen één richting: REST in Web roept de transactionele service aan; de service gebruikt Spring Data JPA-repositories; het domeinmodel wordt met JPA/Liquibase in PostgreSQL opgeslagen. Het profiel `local` gebruikt H2.

## Betrokken componenten

| Component | Verantwoordelijkheid |
| --- | --- |
| `Web/.../CatalogImportController` | REST-endpoints voor bron, definitie, upload, planning en goedkeuring/publicatie. |
| `Web/.../ApiExceptionHandler` | Zet `IllegalArgumentException` en `IllegalStateException` om naar HTTP 400 met foutboodschap. |
| `Service/.../CatalogImportService` | De volledige transactionele importflow: hashing, CSV-screening, planning, bulkdrempel en publicatie. |
| `Domain/.../CatalogSource`, `ImportDefinition`, `ImportBatch` | Bron, mappingversie en traceerbare levering. |
| `Domain/.../CandidateOffer`, `ImportMutation`, `LibraryOffer` | Stagingregel, voorgenomen wijziging en gepubliceerde controlebibliotheekaanbieding. |
| `Dao/.../*Repository` | JPA-opslag en lookup op batch, bibliotheek en aanbiedingsidentiteit. |
| `Web/.../db/changelog/001-initial-schema.sql` | Het bestaande relationele schema en unieke constraints. |
| `Web/.../CatalogImportFlowTest` | Gerichte end-to-end service-test voor initiële publicatie, idempotentie, ongeldige prijs en creatiedrempel. |

## Huidige datastroom

`POST source` → `POST definition` → `POST multipart batch` → SHA-256/idempotentiecontrole → CSV-header- en regelvalidatie → `CandidateOffer`-opslag → mutatieplanning → expliciete approval → upsert van `LibraryOffer` → status `PUBLISHED`.

Bij een fout in header, verplichte kolommen, invoer of bulkdrempel wordt de batch `BLOCKED`; de bibliotheek verandert dan niet.

## Bestaande REST-contracten

| Methode en pad | Doel |
| --- | --- |
| `POST /api/catalog-import/sources` | Bron registreren. |
| `POST /api/catalog-import/definitions` | Versie van CSV-mapping en doelbibliotheek registreren. |
| `POST /api/catalog-import/definitions/{id}/batches` | CSV uploaden en screenen. |
| `POST /api/catalog-import/batches/{id}/plan` | Een gescreend batch plannen. |
| `POST /api/catalog-import/batches/{id}/approve` | Een gepland batch goedkeuren en publiceren. |

## Compatibiliteitsrisico's en conventies

- De huidige API gebruikt inline Java records als request/response; voeg geen aparte DTO-laag toe zonder reden, of migreer het contract bewust.
- De service is één transactionele use-case. Wijzigingen aan planning of publicatie kunnen bestaande idempotentie en de testverwachtingen beïnvloeden.
- Unieke constraints zijn functioneel: `definition + contentHash`, `batch + leverancier + referentie`, `candidate` per mutatie en `bibliotheek + leverancier + referentie`.
- `LibraryOffer` heeft bewust alleen de minimale catalogusvelden. Uitbreiding naar het brede legacy-model vraagt een expliciete mapping- en datamodelkeuze.
- De bestaande `MutationType.INACTIVATE` is nog niet gebruikt. Alleen een enumwaarde toevoegen betekent niet dat opschoning veilig is.
