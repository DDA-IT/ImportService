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

**Expliciet:** wij hebben GEEN scheduler-client/service-account nodig — dat had Prodis fout
voorgesteld. Wij gebruiken uitsluitend de standard-flow-client die al in
`docs/design/fase5-auth-design.md` (aanname A1, §2.3, C7) staat.

## 2. Audience/rechten in Prodis

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

**Blokkeert:** 5B-7, de Prodis-permissie-adapter (token relay naar `GET /api/account`). Tot dan blijft de
lokale, YAML-gebaseerde rechtenbron (`ConfiguredPermissionSource`) de enige bron.

## 3. Verwerkingscontract 252 IMPORT / 1179

**Wat is nodig:** bevestiging van het verwerkingscontract 252 IMPORT/1179: de betekenis van een leeg veld
(behoud van de bestaande waarde of wissen), de `ARIMP_DELETE`-codes, en het resultaat/OUT02-terugmeldpad.

**Herkomst van deze nummers:** "252", "1179" en `ARIMP_*` zijn legacy PRODIS-programmanummers en
PSIMPORT-werkfilekolommen — géén CatalogImport-ticketnummers. Programma 1179 = "Wegschrijven
bibliotheek in wacht", leest uit de `252 IMPORT`/PSIMPORT-werkfile. De gezaghebbende beschrijving
staat in de Legacy-vault (Obsidian), niet in de Prodis-repo.

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
