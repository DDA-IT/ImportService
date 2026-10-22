# Openstaande externe punten (Prodis/extern)

Dit overzicht is afgeleid uit de denker-zwaar gap-analyse van 2026-09-27 (zie `docs/decisions.md`,
2026-09-27 "Vervolgprioriteit na 5-PUB-a/valuta"). Het zijn externe blokkades die de mens — niet een
Denker- of Bouwer-subagent — bij Prodis/extern moet uitzetten. Zolang een punt niet is uitgezet, blijft
het hier staan zodat het niet verloren gaat. Zodra een punt is uitgezet en de bijhorende blokkade is
opgeheven: dat hier doorhalen of verwijderen, niet stilzwijgend laten verouderen.

## 1. Keycloak-client `catalog-import` in de Prodis-realm

**Wat is nodig, opgesplitst in twee sub-acties:**

- **(a) Lokaal/dev — deblokkeert volledig:** Prodis kan in hún eigen repo-bestand
  `Web/src/main/docker/realm-config/prodis-realm.json` een client `catalog-import` toevoegen
  (confidential, standard flow, **geen** service-account/client-credentials) met de twee
  redirect-URI's `http://localhost:8081/login/oauth2/code/keycloak` en
  `http://localhost:5173/login/oauth2/code/keycloak` (zie `docs/design/fase5-auth-design.md`
  aanname A1, §2.3, C7). Dit is geen infra-verzoek maar een kleine wijziging in hún repo.
- **(b) Echte (staging/productie) realm — blokkeert vandaag niets:** Jeff/DDA-infra maakt
  dezelfde client daar aan met productie-redirect-URI's en een secret via env. Vroeg aanvragen
  voorkomt latere wachttijd, maar dit is geen huidige blokkade.

**Wie levert dit:** (a) Prodis, in hún repo; (b) Jeff/DDA-infra.

**Blokkeert:** (a) deblokkeert de handmatige eindtoets van 5A-3 (browser-login) volledig. De
geautomatiseerde tests hebben deze client niet nodig (die draaien zonder Keycloak via
`TestSecurityConfiguration`).

**Expliciet:** voor de **gebruikerslogin en de rechten** hebben wij GEEN scheduler-client/service-account nodig — dat had
Prodis fout voorgesteld; daarvoor gebruiken wij uitsluitend de standard-flow-client die al in
`docs/design/fase5-auth-design.md` (aanname A1, §2.3, C7) staat. **Nuance:** voor de legacy-config-push bestaat wél een technische
client `prodis-datasync-catalogimport` (client-credentials, enkel `POST .../legacy-config/receipts`; zie `docs/decisions.md`
2026-10-20 M2). Die staat los van de login en de rechten.

## 2. Audience/rechten in Prodis

**Stand 2026-10-22 (5B-7 gebouwd):** de seed van `catalogImport.read/.manage/.approve` is in Prodis gecommit op `dev` (`6e617bd73`);
de audience is lokaal opgelost (`web_app` draagt `aud: account` via de standaardrol en scope `roles`). **Resterend:** (1) een Prodis-rol met
`catalogImport.*` aan gebruikers toekennen (beheerdata); (2) in de productierealm een `oidc-audience-mapper` (audience `account`)
op de CatalogImport-client, plus refresh tokens aan en dezelfde publieke issuer-host als Prodis.

**Bevestigd:** de audience-eis is bevestigd — Prodis' `AudienceValidator` accepteert
`account`/`api://default`. De resterende detailvraag is of dat identiek geldt voor het
gebruikerstoken van de standard-flow-client dat onze BFF relayt (niet voor een
service-account-token, dat hebben wij niet).

**Wat is nodig:** Prodis moet `catalogImport.read`, `catalogImport.manage` en
`catalogImport.approve` (met `.approve` dat bij ons ook publiceren dekt) als `PermissionRight`
seeden zodat `GET /api/account` deze codes voor de ingelogde gebruiker teruggeeft. **Let op:**
dit zijn NIET dezelfde codes als het door Prodis voorgestelde `catalogImport.publish` — dat is
hun eigen (niet onze) recht uit hun herroepen publicatie-API-ontwerp en wordt hier niet gebruikt.

Het recht, de rol, én de koppeling naar een `prodis_user`-rij bij Prodis bestaan nog niet.
"Hoe die rij ontstaat" (Liquibase-seed vs. beheerscherm) is zelf nog een open punt bij Prodis.

**Wie levert dit:** Prodis/beheerder van de Prodis-permissiedata.

**Blokkeert:** niet meer de bouw (5B-7 is gebouwd), wel het echte gebruik van `source=PRODIS`: zonder een rol met `catalogImport.*`
krijgt een gebruiker geen rechten, en zonder audience `account` in de productierealm weigert Prodis het token. Lokaal en in demo
blijft de YAML-bron (`CONFIGURED`) werken.

## 3. Verwerkingscontract 252 IMPORT / 1179

**Wat is nodig:** bevestiging van het verwerkingscontract 252 IMPORT/1179: de betekenis van een leeg veld
(behoud van de bestaande waarde of wissen), de `ARIMP_DELETE`-codes, en het resultaat/OUT02-terugmeldpad.

**Herkomst van deze nummers:** "252", "1179" en `ARIMP_*` zijn legacy PRODIS-programmanummers en
PSIMPORT-werkfilekolommen — géén CatalogImport-ticketnummers. Programma 1179 = "Wegschrijven
bibliotheek in wacht", leest uit de `252 IMPORT`/PSIMPORT-werkfile. De gezaghebbende beschrijving
staat in de Legacy-vault (Obsidian), niet in de Prodis-repo.

**Grotendeels beantwoord vanuit de Legacy-vault (2026-10-20, uit legacy-code, nog niet door Prodis bevestigd):**

- *Naamgeving:* "252 IMPORT" = datasource 252 `IMPORT` (`PSIMPORT`) in **PRODIS_SEG**. In **PRODIS** is
  dezelfde tabel DS 251 en is DS 252 juist `PSIMPDEF` (importdefinitie). Vermeld voortaan het project.
- *Keten (PRODIS):* `1223`/`1232` (auto) of `1177` (manueel) → `1170` bestand + definitie → `PSIMP<bib>`
  → `1171` controle → `1179` naar `%BIB%PSARF<nnn>` (type B) of `1172` naar `ARTICLES`/`PSARFLEV`/… (type A).
  Bron: vault `Prg_1223` r.163–190, `wiki/concepts/prodis-artikelimport-stock-prg1232.md`.
- *Leeg veld:* **behoud bestaande waarde** (leeg of 0), `Prg_1179` r.402–512; idem `1172`. Uitzonderingen:
  genre 1–8 en leverancierssleutels worden altijd overschreven; lege wijzigingsdatum → `Date()`; lege
  prijs-% → groeppercentages.
- *`ARIMP_DELETE`:* enkel `Y`/`N`; `Y` uitsluitend via CALCULATION `8` in `PSIMPDEF` (kolomwaarde =
  `CALCULATION_VALUE`). Wist het bib-artikel; `PSARFLER` enkel bij `MS_Delete` en niet-voorkeurleverancier.
- *Terugmelding:* **"OUT02" bestaat niet in de vault.** Legacy meldt via `V_ImportResultaat`/
  `V_ControleResultaat`, foutmail (`Prg 487`), memo op `PSBIB`, controle-pdf's en logbestand.
  De Java-kant van ProDisWebbase slikt fouten in en antwoordt `200`.
- *"In wacht":* vermoedelijk geen aparte bibliotheek maar een gevulde staging die klaarstaat voor `1179`;
  geen proef- vs. productiebibliotheek gevonden (inferentie).

**Nog bij Prodis te bevestigen:** wat zij met "OUT02" bedoelen, of het voorgaande ook voor de
WebBase-variant geldt, en of SEG-`PSIMPORT` (`Prg_728`) dezelfde regels volgt.

**Aanbod (niet aanvaard):** Prodis heeft aangeboden hun (herroepen) publicatie-API test-first te
bouwen bij akkoord. Dat aanbod staat los van deze blokkade en wordt hier niet aanvaard — de
bindende beslissing van 2026-09-18 (zie `docs/decisions.md`, blok "Fase 0: publicatiedoel") blijft
van kracht. Een eventuele herziening daarvan is een apart mens-besluit, buiten dit overzicht.

**Wie levert dit:** Prodis/extern (eigenaar van ProDisWebbase/Pervasive en het 252 IMPORT-verwerkingsproces).

**Blokkeert:** 5-PUB-b/c (echt schrijven naar TRIAL_LIBRARY/PRODUCTION), en de stories ST-12, ST-13, ST-14.

## 4. ERP-datasetinventaris (D11)

**Wat is nodig:** een inventaris van de relevante ERP-datasets (zie `businessanalyse-catalogimport.md`
§7.5 + Bijlage A) — welke ERP-gegevens bestaan, wie er gezag over heeft, en in welke vorm ze leesbaar zijn.

**Gezaghebbende bron nu bekend:** de Legacy-vault (Obsidian), "Legacy is the spec", te bevragen via
de legacy-vault-query-skill. Voor het al gemigreerde deel dient de Prodis-Postgres-structuur zelf
(`Dao/.../entity/*`: `CatalogArticle`, `Supplier`, `SupplierCatalog`) als inventaris — dat dekt niet
het volledige legacy-ERP.

**Wie levert dit:** Prodis/extern (ERP-beheerder).

**Blokkeert:** ST-12. Bepaalt mede punt 5 hieronder (of er een rechtstreekse leesbehoefte richting
legacy-Zen is).

## 5. Adapterplaats/toegang Pervasive + controlebibliotheek voor de proef

**Wat is nodig:** een vastgelegde adapterplaats en toegang tot Pervasive, plus een controlebibliotheek
(`PSARFxxx`) om de eerste publicatieproef tegen te draaien zonder een productiebibliotheek te raken.
De controlebibliotheek is door Prodis niet beantwoord en blijft dus volledig open.

**Werkhypothese (geen bevestigd feit):** er bestaan al drie Zen-consumenten (`datasync`,
`ProDisWebbase` — schrijft synchroon namens Prodis, legacy-first — en `ProFinApi` met eigen DSN).
`ProDisWebbase` is vermoedelijk de bedoelde adapterplaats voor 5-PUB-b/c (consistent met onze eigen
term "252 IMPORT of de functioneel identieke WebBase-uitvoerder"), niet een eigen
Pervasive-driver in CatalogImport.

**Important technical constraint discovered:** Zen-tabellen zijn alleen via SQL bereikbaar met een
DDF-item (SQL-dictionary-entry) per tabel; niet elke tabel heeft er een.

**Dit punt wacht praktisch op een mens-beslissing** (architectuurbevestiging van de publicatieroute
en van de leesbehoefte richting legacy-Zen, zie punt 4) voordat het verder uitgezet kan worden.

**Wie levert dit:** Prodis/extern (beheerder van de Pervasive-omgeving).

**Blokkeert:** 5-PUB-b (TRIAL_LIBRARY).

## 6. Secrets en sleutelbeheer bij DDA-infra (versleutelde credentials)

**Wat is nodig:** antwoord van DDA-infra op drie vragen rond de versleutelde opslag van
SFTP/API-credentials (zie `docs/decisions.md`, 2026-09-29 "Sleutelbeheer credentials"):

- **Hoe komen secrets in staging en productie in de app terecht?** (bv. omgevingsvariabele,
  `_FILE`/Docker-secrets, systemd `EnvironmentFile`, CI-variabelen.)
- **Bestaat er bij DDA een vault of kluis** (secrets-manager, KMS, gedeelde wachtwoordkluis) voor de
  opslag van de hoofdsleutel?
- **Wie bewaart de reservekopie van de sleutel,** waar, gescheiden van de databaseback-up (pg_dump), en
  minstens zo lang als de back-upretentie na een sleutelwissel?

**Wie levert dit:** Jeff/DDA-infra.

**Blokkeert:** niet de bouwstappen K-1 (versleutelcomponent) en K-2 (opslagtabel) — lokaal en in tests
volstaat een omgevingsvariabele. Wél de uiteindelijke inzet in productie (de eerste echte connector, K-4).

## 7. SFTP-ophaling: DDA-infra, eerste leverancier(s) en pilotkeuze

**Wat is nodig:** gegevens en afspraken voor het automatisch ophalen van leveringsbestanden via SFTP
(zie `docs/decisions.md`, 2026-09-29 "Leveringsconfiguratie en verbindingsprofiel (SFTP)" en
`docs/design/leveringsconfiguratie-design.md`):

- **DDA-infra:**
  - uitgaande firewall open voor poort 22 naar de leveranciers;
  - een vast uitgaand IP-adres, zodat leveranciers dat kunnen whitelisten;
  - wie de property `catalogimport.fetch.allowed-hosts` (exacte hostnamen) beheert en hoe een nieuwe
    host daarin komt;
  - de productiehosting (Linux/cron of Windows/TaskScheduler; zie ook punt 6).
- **Eerste leverancier(s):**
  - host, poort, login en authenticatiemethode (v1 enkel wachtwoord);
  - de hostsleutelvingerafdruk (SHA-256), via een apart kanaal dan de verbinding zelf;
  - de map en de naamconventie van de bestanden (overschreven of gedateerd);
  - het publicatietijdstip van nieuwe bestanden;
  - de bewaartermijn bij de leverancier;
  - of verplaatsen of verwijderen na het ophalen ooit gewenst en toegestaan is.
- **Keuze van de pilotleverancier.** VROOAM is het gedeelde-mapgeval: zonder `BESTANDS_PREFIX` is die
  enkel bruikbaar met recordfilters.

**Wie levert dit:** (a) Jeff/DDA-infra; (b) en (c) de business/leveranciersbeheer, met de betrokken
leverancier(s) als bron.

**Blokkeert:** niet de bouwstappen K-2 t/m K-4a/b in dev en test (embedded testserver). Wél de eerste
echte ophaling bij een leverancier en de productie-inzet.

## 8. Prodis-leesrol `catalogimport_ro`: extra SELECT-rechten voor de publicatie-parallelrun (P1)

**Wat is nodig:** `catalogimport_ro` (alleen-lezen, `default_transaction_read_only=on`; zie
`docs/design/legacy-config-bundle-design.md` par. 6) krijgt bovenop de bestaande drie GRANTs kolomrechten op de
catalogustabellen, `language` en `system_settings`. Enkel `SELECT`, geen `UPDATE`/`INSERT`; de schrijfrol
`catalogimport_pub` volgt in P5a (`docs/design/publicatie-prodis-db-direct-design.md` par. 2.3). Kolomnamen bevestigd tegen de
Prodis-Liquibase en de lokale Prodis-database.

```sql
-- Bestaand (S5): supplier(id, number, name), supplier_group(id, supplier_id, group_name, discount_code, legacy_code),
--                supplier_catalog(id, supplier_id, scope_key, legacy_number, catalog_name, status)
-- Nieuw (P1):
GRANT SELECT (id, code) ON language TO catalogimport_ro;
GRANT SELECT (id, legacy_master) ON system_settings TO catalogimport_ro;
GRANT SELECT (id, supplier_id, supplier_group_id, supplier_catalog_id, supplier_reference_stripped,
              record_active, deleted_at) ON catalog_article TO catalogimport_ro;
GRANT SELECT (id, catalog_article_id, price_code, brut_price_politics, base_price, brut_price,
              brut_price_percentage, sales_price_last_updated, record_active, deleted_at)
      ON catalog_article_price TO catalogimport_ro;
GRANT SELECT (id, catalog_article_price_id, code, percentage, price, record_active, deleted_at)
      ON catalog_article_price_detail TO catalogimport_ro;
GRANT SELECT (id, catalog_article_id, language_id, description, record_active, deleted_at)
      ON catalog_article_description TO catalogimport_ro;
```

**Wie levert dit:** Jeff/DDA-infra (of de DBA van de Prodis-database).

**Blokkeert:** niet de bouw (de H2-testbron bevat deze kolommen). Wel de parallelrun (P2c) en de modusweergave
tegen een echte Prodis-database: zonder deze rechten geeft de leesbron `UNAVAILABLE` (fail-closed, nooit "niet gevonden"
of een stille standaardmodus).
