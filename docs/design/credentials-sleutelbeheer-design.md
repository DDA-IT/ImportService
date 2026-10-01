# Ontwerp — versleutelde opslag van externe credentials (SFTP/API)

Bindend na `docs/decisions.md` 2026-09-29 ("Sleutelbeheer credentials (kandidaat c): recht voor instellen/vervangen/wissen") en beslissingsschema V1–V8. Dit document beschrijft dat besluit concreet; het is geen nieuwe architectuurkeuze. Bron: `business-analyse-leveranciersbibliotheken.md` §14.17 (r.2180-2210), B10, vraag 17; `docs/decisions.md` 2026-09-25 (A2), 2026-09-26, 2026-09-29.

**Gelijkgetrokken op 2026-09-29 (stap DC-0):** het voorlopige karakter van §5 (datamodel K-2) en de bouwvolgorde in §6 zijn opgeheven door `docs/decisions.md` 2026-09-29 "Leveringsconfiguratie en verbindingsprofiel (SFTP): ontwerp bindend (L1-L8)" en `docs/design/leveringsconfiguratie-design.md`. Het datamodel in §5 is nu definitief; de bouwvolgorde staat in `leveringsconfiguratie-design.md` §10.

## 0. Doel en afbakening

Een externe bron (bv. een SFTP-server of API) draagt credentials (wachtwoord, API-sleutel, token) waarmee CatalogImport zich authenticeert. Deze credentials moeten op de database versleuteld opgeslagen worden, zodat:

- ze nooit in zuivere tekst bewaard worden;
- ze nooit via een API, log-melding of toString teruggegeven worden;
- ze enkel ontsleuteld kunnen worden door componenten die de sleutel bezitten (server-side);
- hun verlies traceerbaar en onherstelbaar is.

**Scope K (fasen; volgorde en trap in `docs/design/leveringsconfiguratie-design.md` §10):**
- **K-0** (afgerond): besluiten + dit designdocument.
- **K-1** (afgerond): versleutelcomponent (JDK `javax.crypto`, sleutelring via properties, opstartvalidatie). Geen schema, geen API. Unit-tests.
- **K-2a** (afgerond): Liquibase 013 (`external_credential`, `external_credential_event`, `secret_key_check`), Domain/Dao, key-check-AAD in `SecretsService`, startcontrole, lekbewijs.
- **K-2b** (afgerond): herversleutelen bij opstart (R2a).
- **K-3** (afgerond): REST-endpoints (credentials aanmaken, secret vervangen/heractiveren, intrekken, metadata-GET), rechtencontrole (`MANAGE`), regressietests.
- **K-4a/b/c**: SFTP-connector, verbindingstest en ophaalrun, na de leveringsconfiguratiestappen LC-1/LC-2.

**Bewust buiten scope:**
- Leveringsconfiguratie en verbindingsprofiel — uitgewerkt in `docs/design/leveringsconfiguratie-design.md` (bindend, L1-L8), niet in dit document.
- Secrets-manager-integratie (HashiCorp Vault, AWS Secrets Manager) — hoort in externe infrapunten (§6).

De eerdere zin "SFTP-hostsleutel (`known_hosts`-beheer) — infrastructuur, geen applicatie" is **opgeheven**: de hostsleutel wordt per profielversie in de applicatie vastgepind (L5, `leveringsconfiguratie-design.md`).

## 1. Besluiten V1–V8 (samenvatting uit decisions.md 2026-09-29)

| # | Besluit | Aanvaard | Opmerking |
|---|---------|----------|----------|
| **V1** | Geen API geeft een secret terug, ook niet aan de invoerder. Antwoorden tonen `secretSet`, `secretUpdatedAt`, `secretUpdatedBy`. Ontsleutelen enkel server-side. | Aanbeveling; mens = ja | Recht `delivery.credentials.view` (BA §16.8) vervalt — die paragraaf is achterhaald door 2026-09-26. |
| **V2** | Recht voor instellen/vervangen/wissen = `MANAGE` (afwijkend van aanbeveling `catalogImport.credentials`). Gevolg: wie `APPROVE` heeft, kan ook credentials vervangen. | Aanbeveling P2, mens = P1 ✓ | Volgt actiemapping A2 (2026-09-25: credentials zijn configuratie). `ConfiguredPermissionSource` en Prodis-seedverzoek ongewijzigd. |
| **V3** | Sleutel in omgevingsvariabele met sleutelring (optioneel via bestand). Properties `catalogimport.secrets.*`. Aparte sleutel per applicatie/omgeving. Reservekopie staat nooit in `CATALOG_BACKUP_DIR` of op dezelfde media als pg_dump. | Aanbeveling; mens = ja | Niet `PRODIS_SECRETS_MASTER_KEY`. Back-up-relatie uitgewerkt in §"Sleutel en back-up" van `docs/design/backup-herstel-design.md`. |
| **V4** | Sleutel-ID per waarde vanaf dag 1. Kolomformaat `v1:<keyId>:<base64(nonce‖ciphertext+tag)>` + kolom `encryption_key_id`. Één actieve sleutel versleutelt; oude sleutels ontsleutelen. Herversleutelen als idempotente batch bij opstart (R2a), auditevent per rij SYSTEM. Oude sleutel ≥ back-upretentie (~5 weken), pas weg bij 0 rijen. | Aanbeveling; mens = ja | Rotatie zonder operationele downtime mogelijk. |
| **V5** | Sleutelverlies is onherstelbaar. Betrokken credentials → `UNDECRYPTABLE`. Connectors weigeren (`CREDENTIAL_UNDECRYPTABLE`). Gebruiker voert waarden opnieuw in. App start wel. | Aanbeveling; mens = ja | Geen stille fallback. |
| **V6** | Geen ontwikkelsleutel in de repo. Tests krijgen vaste sleutel enkel in testsources. | CatalogImport-conventie (als `CATALOG_OIDC_CLIENT_SECRET`); mens = ja | Volgt bestaand beveiligingspatroon. |
| **V7** | Hybride modus: geen config = functie uit (409 `SECRETS_NOT_CONFIGURED`); config invalid = fail-fast bij opstart. Controlewaarde `secret_key_check`. | Aanbeveling; mens = ja | Geen stille siloing of verborgen fouten. |
| **V8** | Scope nu enkel K-1: versleutelcomponent, sleutelring-properties, opstartvalidatie, unit-tests. Geen schema, geen API. | Aanbeveling; mens = ja | K-2 (tabellen) hooguit als losstaande tabel; endpoints (K-3) en SFTP-connector (K-4) pas na ConnectionProfile-ontwerp. "Geen server-side ophaling" (2026-09-26) blijft gelden tot K-4. |

## 2. Technische standaard

### 2.1 Codering: JDK javax.crypto

- **Algoritme:** AES-256 in GCM-modus (Galois/Counter Mode), geen padding.
- **Sleutellengte:** 256 bits (32 bytes).
- **Nonce:** 96 bits (12 bytes), willekeurig gegenereerd per versleuteling via `SecureRandom`, nooit hergebruikt.
- **Authenticationtag:** 128 bits (16 bytes).
- **Associated Data (AAD):** `catalogimport|external_credential|<credential_ref-UUID>|<secret_kind>|v1`
  - Voorkomt dat ciphertext naar een ander object of doel verplaatst kan worden.
  - `credential_ref` is de permanente unieke identifier (zie §3 en 4).
  - `secret_kind` (bv. SFTP_PASSWORD, API_KEY) moet in alle bewerkingen hetzelfde zijn.

### 2.2 Kolomformaat (K-2)

```
v1:<keyId>:<base64(nonce‖ciphertext+tag)>
```

- **Versie:** `v1` — bij een toekomstige algoritmewisseling → `v2`, oude versies blijven ontsleutelbaar.
- **keyId:** string `[A-Za-z0-9-]{1,32}`, uniek within de sleutelring.
- **nonce:** 12 bytes (96 bits), willekeurig.
- **ciphertext+tag:** totaal 16 bytes (ciphertext) + 16 bytes (tag).
- **base64:** zonder padding (Java default), URL-safe encoding niet vereist.

Aparte kolom `encryption_key_id` dupliceert de sleutel-ID voor optimalisatie (snelle filtering op "welke sleutels worden nog gebruikt").

### 2.3 Sleutelring: omgevingsvariabelen (K-1)

Geen default in `application*.yml`, nooit een sleutelmateriaal in de repository.

```
# Omgevingsvariabelen (beide nodig, of beide leeg)
export CATALOG_SECRETS_KEYS="key-2024-09:ABCDef123...base64-32-bytes,key-2024-10:XYZuvw456...base64-32-bytes"
export CATALOG_SECRETS_ACTIVE_KEY_ID="key-2024-10"
```

**Formaat `CATALOG_SECRETS_KEYS`:**
- Komma-gescheiden lijst van `<keyId>:<base64>` paren.
- Elke `<keyId>` moet `[A-Za-z0-9-]{1,32}` matchen.
- Elke `<base64>` is exact 32 bytes (256 bits) van AES-sleutel, gegenereerd via `openssl rand -base64 32`.
- Spaties rond komma's en dubbele punten worden getrimd.
- Voorbeeld: `key-2024-09:dBISBjT5vEQE5v5n1m3t6u2z9c0d3e6f+A==,key-2024-10:qW7xE4rY2sU8vC1jM0nP3qR5sT7uV9wX2y==`

**Beide instellingen leeg** = functie uit, app start normaal, opslaan/ontsleutelen geeft `409 SECRETS_NOT_CONFIGURED`.  
**Beide ingesteld + geldig** = app start, versleuteling actief.  
**Iets ingesteld, invalid of inconsistent** = `IllegalStateException`, app start niet.

**Toekomstige uitbreiding (K-2+):** bestandsvariant zonder codewijziging via Spring `spring.config.import=configtree:<path>`, bijvoorbeeld Docker `configtree:/run/secrets`. Momenteel (K-1) enkel via omgeving.

### 2.4 Opstartvalidatie (fail-fast, K-1)

Zijn `keys` én `active-key-id` beide leeg, dan staat de functie uit (de app start; gebruik geeft
`SecretsNotConfiguredException`). Is er wél config, dan gelden onderstaande validaties; bij minstens één fout:
`IllegalStateException` bij constructie, de app start niet.

1. **Beide of geen:** `keys` zonder `active-key-id` (of omgekeerd) is ongeldig.
2. **Formaat per entry:** `id:base64`; een lege entry of een entry zonder `:` is ongeldig.
3. **Sleutel-ID:** `[A-Za-z0-9-]{1,32}` en uniek binnen de ring.
4. **Materiaal:** geldige base64 van precies 32 bytes.
5. **Actieve sleutel-ID** staat in de ring.
6. **Geen identiek materiaal onder twee ID's:** rechtstreekse bytevergelijking (`MessageDigest.isEqual()`), geen hash.

**Opmerking:** de controlewaarde `secret_key_check(key_id, check_ciphertext)` — een bekende tekst versleuteld met elke
sleutel, om bij opstart te detecteren dat de sleutel niet bij deze database hoort — is persistent en volgt in K-2a (tabel `secret_key_check`, eigen AAD `catalogimport|secret_key_check|<keyId>|v1`; zie §5.3).

### 2.5 Lekpreventie

- Waarde **nooit** in log-meldingen.
- Waarde **nooit** in exceptie-stacktrace.
- Waarde **nooit** in `toString()`-output.
- Audit: nooit de waarde zelf, nooit een hash ervan — enkel `secretSet` (boolean), `secretUpdatedAt` (timestamp), `secretUpdatedBy` (gebruiker).
- Waarde (ook de ciphertext) **nooit** in een databasefoutmelding: een checkfout van PostgreSQL draagt standaard de hele rij ("Failing row contains (...)"). Daarom staat de pgjdbc-property `logServerErrorDetail=false` in `application.yml` als `spring.datasource.hikari.data-source-properties.logServerErrorDetail` (geldt voor alle profielen en de testrun; de URL's blijven ongewijzigd). Bewezen door `CredentialLeakDetectionTest` (decisions 2026-09-29, K-2a (a)).

### 2.6 HTTP-statusmapping (K-1)

Beide runtime-excepties extenden `ConflictException` en mappen naar HTTP 409:

- **`SecretsNotConfiguredException`:** secrets niet geconfigureerd (geen `CATALOG_SECRETS_KEYS` en `CATALOG_SECRETS_ACTIVE_KEY_ID`). Gebruiker ziet geen details; enkel 409 SECRETS_NOT_CONFIGURED.
- **`CredentialUndecryptableException`:** decryptie mislukt (onbekend keyId, ongeldig formaat, tag-mismatch, verloren sleutel). Gebruiker ziet geen details; enkel 409 CREDENTIAL_UNDECRYPTABLE.

## 3. Important technical constraint discovered — credential_ref als UUID

Alle entiteiten in CatalogImport gebruiken `GenerationType.IDENTITY` (auto-incrementing `BIGINT`), dus het rij-ID is pas na de eerste INSERT beschikbaar. 

Bij het versleutelen moet echter **al de credential_ref bekend zijn** (onderdeel van de associated data in AES-GCM). Daarom:

- **Elke credential krijgt vooraf een UUID** (`java.util.UUID.randomUUID()`), gegenereerd in de applicatie vóórdat de rij wordt opgeslagen.
- Dit UUID fungeert als **permanente, wereldwijd unieke identifier**, onafhankelijk van het database-rij-ID.
- De UUID staat ook in het associated data voor de AES-GCM-bewerking.
- Functie: ook de "credentialreferentie" uit businessanalyse r.2186/r.2198.

## 4. Important technical constraint discovered — Prodis SecretCipher precedent

Prodis (`Prodis/src/main/java/.../../SecretCipher.java`) kent al een secrets-implementatie:
- AES-256-GCM (dezelfde standaard ✓).
- Properties-gebaseerde sleutel: `PRODIS_SECRETS_MASTER_KEY` (omgevingsvariabele).
- Kolomformaat: `v1:<base64(nonce‖ciphertext+tag)>` (geen sleutel-ID, dus geen rotatie).
- Dev-sleutel: hardcoded in `application-dev.yml` (anti-patroon).
- Rotatie: niet ondersteund (opnieuw invoeren als handmatige actie).
- Referentie: `Prodis/docs/internal/integrations/payments-api-mollie.md` r.30-34.

**Gevolg voor CatalogImport:**
- Dezelfde operationele vorm (properties-gebaseerde sleutelring, fail-fast).
- **Geen codehergebruik:** Prodis en CatalogImport zijn verschillende Maven-reactoren, niet gekoppeld.
- **Wel dezelfde beveiligingsprincipes:** geen dev-sleutel in repo, ontsleuteling enkel server-side, lekpreventie.
- **Verschillen:** CatalogImport voegt sleutel-ID toe (V4, rotatie zonder downtime), commit geen dev-key.

## 4.2 Important technical constraint discovered — geen testmap in Service

De module `Service` (Maven) heeft geen `src/test`-directory. Tests van Service-componenten (`SecretsService`, etc.) staan daarom in `Web/src/test` onder hun eigen pakketpad — dit volgt de CatalogImport-conventie van andere Service-klassen. Bij toekomstige uitbreiding moet dezelfde conventie gevolgd worden.

## 5. Datamodel K-2 (definitief)

Het eerdere voorlopige model is opgeheven. Definitief volgens `docs/decisions.md` 2026-09-29 (L1-L8, A6, A7, A19) en `docs/design/leveringsconfiguratie-design.md` §3.1 (bindend voor kolomdetails). Changeset **013**, volledig additief. Wijzigingen ten opzichte van het voorlopige model:

- `ciphertext` en `encryption_key_id` zijn **nullable** met checks: het oude §5.1 (`ciphertext not null`) sprak §5.3 (wissen = `null`) tegen (T5).
- Projectconventies (001/007/012) in plaats van de eerdere SQL-schets (T6): `id bigint generated by default as identity`, `timestamp with time zone`, `*_by varchar(100)` + `*_by_subject varchar(255)`, `reason varchar(500)`.
- Nieuw: `label`, `bound_host` en de unieke sleutel `(id, secret_kind, bound_host)` (doel van de samengestelde FK vanuit een profielversie: een credential is DB-afgedwongen aan één host gebonden, L4a).
- `status` is enkel `ACTIVE`/`REVOKED`; `UNDECRYPTABLE` (V5) is **afgeleid** (ciphertext kan niet ontsleuteld worden), niet opgeslagen (A6).

### 5.1 Entiteit: `external_credential`

| Kolom | Type | Opmerking |
|---|---|---|
| `id` | bigint identity | PK |
| `credential_ref` | uuid not null, unique | vooraf in Java gegenereerd (A19), permanent, deel van de AAD |
| `label` | varchar(200) not null | herkenbare naam, geen geheim |
| `secret_kind` | varchar(40) not null | check `SFTP_PASSWORD`, `SSH_PRIVATE_KEY` (laatste gereserveerd; v1 enkel wachtwoord) |
| `bound_host` | varchar(255) not null | genormaliseerd: kleine letters, geen punt achteraan |
| `ciphertext` | text null | `v1:<keyId>:<base64(nonce‖ct+tag)>`; null na intrekken |
| `encryption_key_id` | varchar(32) null | kopie van keyId; null exact wanneer `ciphertext` null is |
| `status` | varchar(20) not null | `ACTIVE` / `REVOKED` |
| `secret_updated_at`, `secret_updated_by`, `secret_updated_by_subject` | timestamptz, varchar(100), varchar(255) | laatste instellen of vervangen |
| `created_at`, `created_by`, `created_by_subject` | idem | |
| `revoked_at`, `revoked_by`, `revoked_by_subject`, `revoked_reason` (varchar(500)) | idem | |

Checks: `(status = 'ACTIVE') = (ciphertext is not null)`; `(ciphertext is null) = (encryption_key_id is null)`; `status = 'REVOKED'` => `revoked_reason is not null`. Unieke sleutels: `credential_ref`; `(id, secret_kind, bound_host)`.

Extra check (changeset 013-4, additief): `ck_external_credential_bound_host_normalized` — `bound_host` is niet leeg (na trim), gelijk aan `lower(bound_host)`, heeft geen punt achteraan en bevat geen witruimte. De entiteitsconstructor weigert een niet-genormaliseerde host (zonder de waarde te noemen); normaliseren gebeurt in de `CredentialService` (K-3).

### 5.2 Entiteit: `external_credential_event` (append-only)

Kolommen: `id`, `credential_id` (FK naar `external_credential.id`), `event_kind` (`CREATED`/`REPLACED`/`REVOKED`/`REENCRYPTED`), `reason varchar(500) not null`, `source` (`HUMAN`/`SYSTEM`), `changed_by`, `changed_by_subject`, `changed_at`, `previous_key_id`, `new_key_id`.

- Append-only: geen UPDATE, geen DELETE.
- `CREATED`: eerste keer aangemaakt. `REPLACED`: nieuwe waarde ingevoerd. `REVOKED`: gewist (crypto-shred). `REENCRYPTED`: idempotente batch bij opstart (V4, R2a), met `previous_key_id` en `new_key_id`.
- `source`: `HUMAN` => `changed_by` niet null; `SYSTEM` => `changed_by` en `changed_by_subject` null (patroon `012-issue-case.sql` r.116-120), niet de tekst "system".
- `reason` is verplicht. Nooit een waarde of hash van het secret in een event.

### 5.3 Entiteit: `secret_key_check`

`key_id varchar(32)` PK, `check_value text not null`, `created_at`. `check_value` is een bekende tekst, versleuteld met de sleutel `key_id` onder een **eigen AAD** `catalogimport|secret_key_check|<keyId>|v1` (uitbreiding van `SecretsService`; de bestaande AAD voor credentials blijft ongewijzigd). Bij opstart wordt de actieve sleutel gecontroleerd (fail-fast, A7): hoort deze sleutel niet bij deze database, dan start de applicatie niet. Sleutel-ID's krijgen een omgevingsprefix.

### 5.4 Wissen (crypto-shred)

Wissen = `UPDATE external_credential SET ciphertext = null, encryption_key_id = null, status = 'REVOKED', revoked_* = ...` plus één event `REVOKED`; altijd toegestaan (A8).
- Rij en events blijven (audit).
- Status `REVOKED` voorkomt verdere use (connector: `CREDENTIAL_REVOKED`).
- `ciphertext = null` is de crypto-shred; de checks in §5.1 houden dat consistent.

## 6. Bouwvolgorde

| # | Fase | Doel | Trap | Gerichte tests | Status |
|----|------|------|------|---|--------|
| K-0 | Intake | Besluiten + dit document | — | — | **Afgerond** |
| K-1 | Component | `SecretsService` (JDK Cipher), sleutelring-properties, opstartvalidatie | Gemiddeld | `SecretCipherTest`, `SecretsStartupValidationTest`, `SecretsPropertiesTest` | **Afgerond (2026-09-29)** |
| K-2a | Schema & key-check | Liquibase 013, Domain/Dao, `SecretsService` key-check-AAD, startcontrole, lekbewijs | Zwaar | `ExternalCredentialSchemaTest`, `SecretKeyCheckStartupTest`, `CredentialLeakDetectionTest` | **Afgerond (2026-09-29)** |
| K-2b | Herversleutelen | Idempotente batch R2a bij opstart | Gemiddeld | `SecretsRotationTest` | **Afgerond (2026-09-29)** |
| K-3 | REST & rechten | `CredentialService` + credential-endpoints, `MANAGE`, regressietests | Zwaar | `CredentialHttpTest`, `PermissionCoverageTest`, `PermissionWriteEndpointsHttpTest`, `PermissionReadEndpointsHttpTest` | **Afgerond (2026-09-29)** |
| LC-1 / LC-2 | Leveringsconfiguratie | Changeset 014, profielen, DC's, taakkoppeling | Gemiddeld / zwaar | zie `leveringsconfiguratie-design.md` §10 | **Open** |
| K-4a / K-4b / K-4c | SFTP-connector en ophaalrun | MINA-adapter, allowlist, scan/test; changeset 015 en ophaalrun; herstel vastgelopen run | Zwaar / zwaar / gemiddeld | zie `leveringsconfiguratie-design.md` §10 | **Open** |

De bouwvolgorde en de gerichte tests per stap staan gezaghebbend in `docs/design/leveringsconfiguratie-design.md` §10 (sequentieel: K-2a, K-2b, K-3, LC-1, LC-2, K-4a, K-4b, K-4c). De vroegere tabelrijen K-2/K-3/K-4 zijn daardoor vervangen.

**Bouwstap K-1 (afgerond 2026-09-29, gerichte testcommando):**
```bash
mvn -pl Web -am test "-Dtest=SecretCipherTest,SecretsStartupValidationTest,SecretsPropertiesTest" -Dsurefire.failIfNoSpecifiedTests=false
```

**Bouwstap K-2a (afgerond 2026-09-29).** Gebouwd:
- Changeset `Web/src/main/resources/db/changelog/013-external-credential.sql` (in de master): 013-1 `external_credential`, 013-2 `external_credential_event` (append-only, index `(credential_id, id)`), 013-3 `secret_key_check`. Volledig additief, elk met rollback.
- Domain: `ExternalCredential`, `ExternalCredentialEvent`, enums `ExternalCredentialSecretKind`, `ExternalCredentialStatus`, `ExternalCredentialEventKind`, `ExternalCredentialEventSource`. `toString` toont nooit de ciphertext (enkel `secretSet`); geen getter of repositorymethode geeft een ontsleutelde waarde.
- Dao: `ExternalCredentialRepository` (`findByCredentialRef`, `findDistinctEncryptionKeyIds`), `ExternalCredentialEventRepository`, `SecretKeyCheckDao` (JDBC, `findCheckValue`, `insertIfAbsent`).
- Service: `SecretsService` uitgebreid met `keyIds()`, `keyCheckValue(keyId)`, `matchesKeyCheckValue(keyId, value)` onder de eigen AAD `catalogimport|secret_key_check|<keyId>|v1` (credential-AAD en formaat ongewijzigd); nieuwe `SecretKeyCheckVerifier` (`SmartInitializingSingleton`: na Liquibase, vóór de webserver).
- Tests (`Web/src/test`): `domain.ExternalCredentialSchemaTest`, `service.SecretKeyCheckStartupTest`, `service.CredentialLeakDetectionTest`; `ConfigurationActorSubjectSchemaTest` bijgewerkt (subjectkolommen 21 op 15 → 25 op 17).

Afwijkingen en invullingen ten opzichte van §5 (geen van alle wijzigt een bestaand contract):
- De opstartcontrole controleert **elke** sleutel in de ring, niet enkel de actieve (A7 noemt de actieve; dit is een strengere bovenverzameling). Per sleutel: rij aanwezig → moet ontsleutelen tot de bekende tekst, anders start de applicatie niet; geen rij → aanmaken (nooit overschrijven). Een sleutel-ID in `external_credential` buiten de ring geeft enkel een WARN (V5/A6). Geen config → niets.
- `secret_key_check` heeft geen JPA-entiteit maar een JDBC-DAO: bij een toegewezen primaire sleutel doet `save` een `merge`, die bij een gelijktijdige opstart een bestaande controlewaarde zou overschrijven.
- Drie extra checks op `external_credential_event` die uit §5.2 volgen: `CREATED` ⇒ `previous_key_id` null; `REENCRYPTED` ⇒ beide sleutel-ID's gevuld; `REVOKED` ⇒ `new_key_id` null.
- `created_by` is `not null` (een credential wordt altijd door een mens met `MANAGE` aangemaakt); `secret_updated_*` en `revoked_*` zijn nullable met koppelcheck subject ⇒ naam.
- De entiteit kent in K-2a enkel het aanmaken; vervangen en intrekken (§5.4) volgen met `CredentialService` in K-3, herversleutelen in K-2b.

Gerichte tests (Spring-context, PostgreSQL, geïsoleerd schema):
```powershell
.\scripts\test\run-full-tests.ps1 -Tests ExternalCredentialSchemaTest,SecretKeyCheckStartupTest,CredentialLeakDetectionTest,ConfigurationActorSubjectSchemaTest
.\scripts\test\run-full-tests.ps1 -NoReset -Tests PermissionHttpTest
mvn -pl Web -am test "-Dtest=SecretCipherTest,SecretsStartupValidationTest,SecretsPropertiesTest" -Dsurefire.failIfNoSpecifiedTests=false
```

**Bouwstap K-2b (afgerond 2026-09-29).** Gebouwd:
- Service: `SecretsRotationService` (`SmartInitializingSingleton`). Geen config = niets. Selecteert `ACTIVE`-rijen met `encryption_key_id` <> actieve sleutel én in de ring; per rij een eigen `TransactionTemplate`-transactie: ontsleutelen (zelfde AAD), versleutelen met de actieve sleutel, ciphertext + `encryption_key_id` bijwerken, één `REENCRYPTED`-event (`SYSTEM`, `changed_by` null, `previous_key_id`, `new_key_id`, vaste reden "Automatisch herversleuteld naar de actieve sleutel bij opstart"). `secret_updated_*` blijft ongewijzigd (de waarde is niet veranderd). INFO-log: enkel aantallen per oude sleutel-ID.
- Dao: `ExternalCredentialRepository.findRotationCandidates`, `replaceCiphertextIfUnchanged` (optimistische guard) en `countRowsPerEncryptionKeyId` (operatorhulp; geen endpoint).
- Test: `service.SecretsRotationTest`. Geen schemawijziging, geen endpoints.

Keuzes en afwijkingen (geen wijziging van een bestaand contract):
- **Ordening t.o.v. de verifier:** `SmartInitializingSingleton`-callbacks lopen in registratie-, niet in afhankelijkheidsvolgorde. `SecretsRotationService.afterSingletonsInstantiated()` roept daarom eerst expliciet `SecretKeyCheckVerifier.verify()` aan (idempotent) en pas daarna `rotate()`. Hoort een sleutel niet bij deze database, dan start de applicatie niet en is er niets herversleuteld. De verifier is ongewijzigd (zijn eigen callback draait daardoor mogelijk een tweede keer; dat is onschadelijk).
- **Gelijktijdigheid:** de entiteit heeft geen `@Version`; de update is `where id = ? and ciphertext = <gelezen> and encryption_key_id = <oude> and status = 'ACTIVE'`. Enkel bij 1 geraakte rij wordt in dezelfde transactie het event geschreven. Onder READ COMMITTED wacht een gelijktijdige tweede update en raakt dan 0 rijen: geen dubbel event, geen verloren update (een intussen vervangen of ingetrokken rij wordt niet overschreven).
- **Fouten per rij:** een niet-ontsleutelbare rij (corrupt, verkeerde context) geeft één WARN met `CREDENTIAL_UNDECRYPTABLE`, rij-id en sleutel-ID (nooit waarde of ciphertext); de rest wordt verwerkt. Elke andere `RuntimeException` op een rij wordt gelogd met enkel het exceptietype en breekt het opstarten niet; de rij komt bij de volgende start opnieuw aan bod. Rijen onder een sleutel-ID buiten de ring worden niet geselecteerd (enkel de bestaande WARN van de verifier); `REVOKED`-rijen worden nooit aangeraakt.

**Operatorprocedure sleutelrotatie:**
1. Genereer een nieuwe sleutel (`openssl rand -base64 32`) met een nieuw sleutel-ID met omgevingsprefix; voeg hem toe aan `CATALOG_SECRETS_KEYS` (naast de bestaande sleutel(s)).
2. Zet `CATALOG_SECRETS_ACTIVE_KEY_ID` op het nieuwe ID.
3. Herstart de applicatie: de opstartcontrole loopt eerst, daarna worden alle actieve credentials herversleuteld (INFO-log met aantallen per oude sleutel-ID; per rij een `REENCRYPTED`-event).
4. Controleer dat 0 rijen nog onder de oude sleutel staan (`ExternalCredentialRepository.countRowsPerEncryptionKeyId()`, of `select encryption_key_id, count(*) from external_credential group by 1`). Een rij die de WARN `CREDENTIAL_UNDECRYPTABLE` gaf, blijft onder de oude sleutel: los dat eerst op (waarde opnieuw invoeren).
5. Bewaar de oude sleutel minstens zo lang als de back-upretentie (~5 weken): een back-up van vóór de rotatie bevat nog waarden onder de oude sleutel.
6. Verwijder daarna de oude sleutel uit `CATALOG_SECRETS_KEYS`.

```powershell
.\scripts\test\run-full-tests.ps1 -Tests SecretsRotationTest,SecretKeyCheckStartupTest,CredentialLeakDetectionTest,ExternalCredentialSchemaTest
.\scripts\test\run-full-tests.ps1 -NoReset -Tests PermissionHttpTest
```

**Bouwstap K-3 (afgerond 2026-09-29).** Gebouwd (geen schemawijziging):
- Service: `CredentialService` (patroon `IssueCaseService`: één `TransactionTemplate` per schrijfactie, `findByCredentialRefForUpdate` als serialisatiepunt; lezen in een read-only transactie) en `service.support.HostNames` (normalisatie van `bound_host`, herbruikbaar voor de profielhost in LC-2).
- Web: `CatalogImportCredentialController` onder `/api/catalog-import/credentials`.
- Domain (additief): `ExternalCredential.recordReplacement`, `recordReactivation`, `recordRevocation`. Dao (additief): `ExternalCredentialRepository.findByCredentialRefForUpdate` (pessimistisch schrijfslot), `findAllByOrderByLabelAscIdAsc`.

| Endpoint | Recht | Gedrag |
|---|---|---|
| `POST /credentials` `{label, secretKind, boundHost, secret, reason}` | MANAGE | 201; server-side UUID; host genormaliseerd; versleuteld met de actieve sleutel; event `CREATED` (`new_key_id`) |
| `PUT /credentials/{ref}/secret` `{secret, reason}` | MANAGE | `ACTIVE`: nieuwe waarde, event `REPLACED` (`previous_key_id` + `new_key_id`). `REVOKED`: heractiveren (decisions 2026-09-29): status `ACTIVE`, `revoked_*` leeg, event `REPLACED` met `previous_key_id` null |
| `POST /credentials/{ref}/revoke` `{reason}` | MANAGE | crypto-shred: ciphertext en sleutel-ID null, `REVOKED` + `revoked_*`, event `REVOKED` (`previous_key_id` = het gewiste sleutel-ID). Al `REVOKED` = 409 zonder nieuw event |
| `GET /credentials`, `/credentials/{ref}`, `/credentials/{ref}/events` | MANAGE | zie hieronder |

Antwoord van een credential: `credentialRef`, `label`, `secretKind`, `boundHost`, `status`, `secretSet`, `secretUpdatedAt`, `secretUpdatedBy` — nooit de waarde, de ciphertext, een sleutel-ID of een subject. Event: `id`, `eventKind`, `reason`, `source`, `changedBy`, `changedAt` (zonder sleutel-ID's). Lijst gesorteerd op label, dan id; geen paginering.

Foutcodes: 400 `CREDENTIAL_LABEL_REQUIRED`, `CREDENTIAL_SECRET_KIND_INVALID`, `CREDENTIAL_SECRET_KIND_NOT_SUPPORTED` (`SSH_PRIVATE_KEY` is gereserveerd, A5), `CREDENTIAL_HOST_INVALID`, `CREDENTIAL_SECRET_REQUIRED` (leeg of enkel witruimte; de waarde wordt nooit getrimd), `CREDENTIAL_SECRET_TOO_LONG` (> 1024 tekens), `CREDENTIAL_REASON_REQUIRED`, `CREDENTIAL_REQUEST_UNREADABLE`; te lange label/reden = 400 zonder code (bestaande tekstregel); 404 `CREDENTIAL_NOT_FOUND` (ook voor een ref die geen UUID is); 409 `SECRETS_NOT_CONFIGURED`, `CREDENTIAL_ALREADY_REVOKED`. Volgorde: 403 (interceptor) → 400 → 404 → 409.

Keuzes en afwijkingen (geen wijziging van een bestaand contract):
- **Leesrechten:** alle drie de GET's vragen `MANAGE` (design `leveringsconfiguratie-design.md` §6 "Credentials lezen: MANAGE"; elk antwoord toont `boundHost`, host-gevoelig volgens L7b; precedent D8). `READ` ziet dus geen credentials; wat L7b hoogstens aan `READ` toelaat (code/naam/status/`secretSet`), komt pas indirect via profielen/DC's (LC-2) als dat nodig blijkt.
- **Gebruik (aantal profielversies, A8)** zit nog niet in het antwoord: de tabel bestaat pas met LC-1. Het veld wordt in LC-2 additief toegevoegd (bewust geen hardgecodeerde `0`).
- **Intrekken zonder sleutelring werkt** (A8 "altijd toegestaan"; V7 noemt enkel opslaan): er wordt niets versleuteld. Aanmaken en vervangen/heractiveren geven zonder ring 409 `SECRETS_NOT_CONFIGURED`; lezen werkt.
- **Dubbele aanmaak** geeft twee losse credentials: het ontwerp kent geen idempotentiesleutel of unieke label.
- **Hostvalidatie** is strenger dan enkel "niet leeg, geen spaties": enkel ASCII-letters, cijfers en `. - _ : [ ]`, hoogstens 255 tekens (geen schema, pad of `user@`; een IDN in punycodevorm). ASCII houdt de Java-normalisatie gelijk aan `lower()` in de databasecheck.
- **Lekpreventie van de body:** de controller leest de JSON-body zelf (Spring-`ObjectMapper`) in plaats van `@RequestBody`. Spring logt bij een onleesbare body de Jackson-melding (DEBUG `InvocableHandlerMethod`, WARN `DefaultHandlerExceptionResolver`), en die kan een niet-geciteerd token uit de invoer herhalen. Een leesfout is nu een vaste 400 zonder oorzaak-keten en zonder log; de request-records hebben een `toString` zonder de waarde. Bewezen in `CredentialHttpTest` met `org.springframework.web` op DEBUG.
- Tijdstempels worden op microseconden afgekapt (zoals PostgreSQL bewaart), zodat het antwoord van een schrijfactie gelijk is aan een latere GET.

```powershell
.\scripts\test\run-full-tests.ps1 -Tests CredentialHttpTest,PermissionCoverageTest,PermissionWriteEndpointsHttpTest,PermissionReadEndpointsHttpTest
.\scripts\test\run-full-tests.ps1 -NoReset -Tests SecretsRotationTest,CredentialLeakDetectionTest,ExternalCredentialSchemaTest,PermissionHttpTest
```

## 7. Open punten en externe afhankelijkheden

Zie `docs/openstaande-externe-punten.md` **§6. Sleutel, back-up, secrets-manager:**

- **Productiehosting:** onbekend (Linux/cron of Windows/TaskScheduler). Impact op sleutelring-bestand en back-uplocatie.
- **Secret-management platform:** optioneel (HashiCorp Vault, AWS Secrets Manager). Niet in K-0/K-1 (eigenaar infra), maar wel in K-3-ontwerp als uitbreidingspunt.
- **SFTP-hostsleutel:** niet langer buiten scope; vastgepind per profielversie in de applicatie (L5, `docs/design/leveringsconfiguratie-design.md`). De vroegere zin "infrastructuur-verantwoordelijkheid" is opgeheven.
- **secret_kind-model en deling van credentials tussen profielen:** opgelost (bindend): `secret_kind` `SFTP_PASSWORD`/`SSH_PRIVATE_KEY` (gereserveerd), een credential is een los object dat aan één host gebonden is en door meerdere profielversies gebruikt kan worden (§5.1).
- **Externe punten voor de SFTP-ophaling (firewall, uitgaand IP, leveranciersgegevens):** `docs/openstaande-externe-punten.md` punt 7.

**Back-up en sleutel:**

Zie paragraaf "Sleutel en back-up" in `docs/design/backup-herstel-design.md` — een pg_dump bevat ciphertext en sleutel-ID's, maar ontsleutering hangt af van beschikbaarheid van de sleutel(s) die golden op het moment van de dump.
