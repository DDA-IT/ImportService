# Catalog Import

Zelfstandige proefversie voor gecontroleerde import van leverancierscatalogi naar een controlebibliotheek. De applicatie bewaart de originele CSV met SHA-256-idempotentiesleutel, valideert een configureerbare aanbiedingsidentiteit (`leverancier + referentie`), maakt een mutatieplan en publiceert uitsluitend na expliciete goedkeuring.

## Architectuur en scope

De modulegrenzen volgen de Prodis-referentie: `Domain` bevat het model, `Dao` JPA-repositories, `Service` de transactionele businessflow en `Web` Spring Boot, REST en Liquibase. De proefversie ondersteunt een manuele CSV-levering met één importdefinitie naar één controlebibliotheek. CSV, fixed-width, XLSX, XML/JSON-connectors, supplementsets, issueschermen, Keycloak-integratie en de echte Prodis/WebBase-publicatie vereisen concrete bronconfiguratie of externe contracten.

## Vereisten en configuratie

Java 21 en Maven 3.9+ zijn nodig. PostgreSQL is de standaarddatabase; stel optioneel `CATALOG_DB_URL`, `CATALOG_DB_USERNAME` en `CATALOG_DB_PASSWORD` in.

```powershell
$env:SPRING_PROFILES_ACTIVE='local'
mvn -pl Web -am spring-boot:run
```

Het `local` profiel gebruikt H2. Voor PostgreSQL maakt u vooraf een database en gebruiker `catalog_import` aan; Liquibase voert de migraties automatisch uit.

## Gerichte tests

```powershell
mvn -pl Web -am test
```

De tests bewijzen initiële publicatie, hash-gebaseerde idempotentie, onleesbare prijs als blokkade en creatiedrempel na initialisatie.

## API-snelstart

1. `POST /api/catalog-import/sources` met `{"code":"02006","name":"Leverancier 02006"}`.
2. `POST /api/catalog-import/definitions` met kolommen `supplier`, `reference`, `group`, `price`.
3. Upload `multipart/form-data` via `POST /api/catalog-import/definitions/{id}/batches` met veld `file`.
4. Bij status `PLANNED`: `POST /api/catalog-import/batches/{id}/approve` met een benoemde `approver`.

Een CSV gebruikt komma of puntkomma als scheidingsteken. Prijzen zijn decimalen, nooit floats; een onleesbare of lege prijs is een blokkade en wordt nooit nul.

## Traceerbaarheid en grenzen

Elke batch bevat de ongewijzigde levering, ontvangstmoment, configuratieversie, status, aantallen en hash. De unieke constraints op batch-hash, kandidaataanbieding, mutatie en controlebibliotheek voorkomen dubbele verwerking. De toepassing logt geen bestandsinhoud of secrets. Vier-ogen-Keycloak-controle vereist het gedeelde realm/permissiecontract en is daarom niet in deze zelfstandige proefversie geactiveerd.
