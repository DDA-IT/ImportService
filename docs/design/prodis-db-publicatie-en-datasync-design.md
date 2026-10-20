# Publicatie naar Prodis-PostgreSQL en legacy-config via de datasync — ontwerpvoorstel

> **Status: VOORSTEL (2026-10-20), nog niet aanvaard.** Uitgewerkt door denker-zwaar na de mens-beslissingen
> "2026-10-20 — Publicatiedoel herzien: Prodis-PostgreSQL, niet Zen" en "2026-10-20 — Legacy-config-migratie Q2: secrets
> via sync" in `docs/decisions.md`. Herziet `docs/design/legacy-config-migratie-design.md` (Q1, Q2, Q6, Q8, Q10, slices).
> De §6-vragen V1–V6 (sectie 6) liggen bij de mens.

## Kernvondst

Prodis heeft een globale schakelaar `system_settings.legacy_master` (`PRODIS` | `PROFAB` | `NONE`),
`C:\javaProject\prodis\docs\internal\architecture\legacy-master-analysis.md` §1, §12, §13.
- **PRODIS** (default): legacy is meester. Prodis schrijft eerst naar legacy via ProDisWebbase (routers, bv.
  `RoutingLegacyArticleGateway`); de datasync spiegelt Zen → Postgres, alleen in die richting.
- **NONE:** Prodis is eigenaar. De datasync weigert elke run (`LegacyMasterSyncGuard.java` r.55).

> **Important technical constraint discovered**
>
> In modus PRODIS herschrijft `CatalogArticleJob` (ProdisDatasyncEngine) `catalog_article` uit PSARF001–999 en wist per
> bibliotheek alle rijen die in die run niet geschreven zijn (`select id from catalog_article where legacy_bib_id = ? and
> last_modified_date < ?`, `CatalogArticleJob.java` r.2766, r.1120-1173). Een rechtstreekse schrijfactie van CatalogImport in
> de Prodis-DB wordt in die modus de volgende run overschreven of gewist. (Geverifieerd door de hoofdsessie.)

## 1. Wat de herroeping raakt

**Vervalt of wordt herzien**
- **5-PUB-b/c richting 252 IMPORT:** vervalt. `TRIAL_LIBRARY`/`PRODUCTION` blijven 409 tot de nieuwe route er is.
- **PSIMPORT-projectie** (preview + artefact van de SIMULATION-run): niet langer het doelformaat. Endpoints blijven als
  alleen-lezen auditspoor (geen contractbreuk); een nieuwe Prodis-projectie komt additief, met eigen `previewSpecVersion`.
  `WRITTEN_TO_PSIMPORT`/`READY_FOR_DELIVERY` worden nooit gezet; nieuwe statuswaarden komen erbij, niets wordt hernoemd.
- **`openstaande-externe-punten.md`:** punt 3 is geen blokkade meer maar blijft de functionele spec van het legacygedrag;
  punt 5 (Pervasive-adapter, controlebibliotheek) vervalt voor CatalogImport; "aanbod niet aanvaard" (r.85-88) is
  achterhaald — het aanbod van Prodis om de publicatie-API te bouwen is weer relevant; punt 1 ("geen service-account")
  klopt niet meer bij M2M-publicatie (V2).
- **`catalog-import-prodis-integration.md`:** de banner "achterhaald" herzien; outbox, `externalMutationId` en
  hervalidatie (r.75-82) zijn weer de juiste basis.
- **BOM-blok 2026-09-22:** de reden ("PSIMPORT kan geen BOM dragen") vervalt; de beslissing (apart, later) blijft.

**Blijft geldig:** eigen DB zonder cross-DB-FK's; Prodis-scaffolding (CatalogImportJob e.a.) niet gebruiken;
aanbiedingsidentiteit; Publicatiebundel als eenheid, atomair per scope; alleen eigen velden (zie V3); permissiemodel;
bundelsnapshot, `publication_run`, concurrency-marker, artefact op bestandssysteem; credential-ontwerp V1–V8;
CatalogImport leest Zen nooit rechtstreeks.

**Identiteit naar Prodis:** sleutel `ux_catalog_article__supplier_key` op `(supplier_catalog_id, supplier_id,
supplier_reference, supplier_reference_stripped, supplier_group_id)` (changelog `20260902100000`);
`SupplierGroup.legacyCode = lev|grp|kortingcode` (`MAPPED_TABLES_OVERVIEW.md` r.234). Onze identiteit lev+grp+ref(+korting)
past; de kortingscode zit in Prodis in de leveranciersgroep. Bibliotheekscope ↔ `SupplierCatalog` (`legacyNumber` = `scopeKey`).

**Waar het 1179-gedrag terechtkomt**
- *Leeg = behouden:* semantiek van het contract. CatalogImport stuurt enkel eigen velden met expliciete toestand; Prodis past toe als patch.
- *Groeppercentages:* Prodis-stamdata (`SupplierGroup`); terugval hoort in Prodis. CatalogImport leest de groep zodat de
  prijscontrole het effectieve percentage ziet. Gat: `PSGRP_Percentage_1..5` niet gemapt (`MAPPED_TABLES_OVERVIEW.md` r.239).
- *Artikel/MS/bib aanmaken (CreaArt/MS_Create):* in Prodis (`POST /catalog-articles/{id}/create-article`), niet in CatalogImport v1.
- *BIB_Delete/MS_Delete:* nergens; een bundel is een deelverzameling.

**Samenloop:** in modus PRODIS wordt een rechtstreekse schrijfactie teruggedraaid. Een uitsluiting per bibliotheek in de
datasync maakt legacy blind (twee meesters) — afgeraden. Zie V1.

## 2. Publicatieroute — aanbeveling: Prodis-API

**Rechtstreeks in de Prodis-DB: afgeraden** — dupliceert Prodis-domeinregels (`supplier_reference_stripped`, prijsstructuur,
audit, `tenant_id`, legacy-master-controle), breekt stil bij elke Prodis-migratie, geen atomaire commit over twee DB's.

**Aanbevolen: idempotent publicatie-endpoint in Prodis, outbox bij CatalogImport.**
- CatalogImport: bundel, snapshot, `publication_run` + items, retry, reconciliatie.
- Prodis: hervalideert referenties, past toe per record/set in eigen transactie, antwoordt per item `APPLIED`/`REJECTED` + reden.
- Idempotentie per item (bv. `run:<id>:item:<mutationId>`), unieke constraint in Prodis — niet op
  `ux_catalog_article__supplier_key` steunen (`supplier_group_id` nullable).
- `RESULT_UNKNOWN` → status per sleutel opvragen.
- Audit: subject van de goedkeurder, bundel- en snapshot-hash in de payload, door Prodis bewaard.
- Routing binnen Prodis (NONE lokaal, PRODIS via webbase/legacy) verandert ons contract niet.

## 3. Referentiedata (btw, grootboek, groepen, eenheden, leveranciers, bibliotheken, munt)

- In modus PRODIS zijn de Prodis-codes gelijk aan de legacycodes (`VatCodeJob`, `GeneralLedgerJob`, `ArticleGroupJob`,
  `UnitOfMeasureJob`, `SupplierGroupJob`, `SupplierCatalogJob`).
- Aanbevolen: alleen-lezen referentie-API van Prodis + lokale momentopname (code, label, `fetched_at`, hash) voor
  reproduceerbare screening. Altijd businesscodes bewaren, nooit Prodis-id's. Prodis hervalideert bij publicatie.
  Terugval: alleen-lezen DB-rol met views.
- **Q8 wijzigt:** vaste creatiewaarden uit legacy-definities (Nr 1-3, btw, rekeningen, eenheid) worden
  **creatiestandaarden** op koppeling/revisie, gevalideerd tegen de referentiedata (onbekend = issue), enkel meegestuurd bij
  CREATE, nooit bij UPDATE. Vraagt V3.

## 4. Datasync-job voor de legacy-config-migratie

ProdisDatasyncEngine is de juiste plek **voor het uitlezen** (enige erkende Zen-toegang; precedent voor eenmalige jobs:
`frequency: once`, `WorkshopMasterDataJob`).
- (a) Rechtstreeks in de CatalogImport-DB: **afgeraden** (omzeilt domeinregels; vraagt sleuteldeling, strijdig met V3 credentials).
- (b) Naar de Prodis-DB: **afgeraden voor configuratie** (eigenaarschap keert om, secrets in Prodis-DB); wel juist voor echte
  Prodis-stamdata, bv. groeppercentages in `SupplierGroupJob`.
- (c) **Aanbevolen:** de job leest INHEAD, INDET, PSIMDHFD, PSIMPDEF, PSIMPDEFMAP, PSIMPEXCL (nog niet gemapt) en bouwt
  "legacy-config-bundle v1" per interface; CatalogImport doet PREVIEW → APPLY met eigen domeinlogica; idempotent op
  herkomstsleutel + inhoudshash.

**Secrets (Q2) via envelopversleuteling**
- CatalogImport publiceert een publieke transportsleutel (RSA-OAEP-256 of HPKE, eigen keyId).
- De job versleutelt `FTP_password` meteen na lezen, in geheugen; de bundel bevat enkel ciphertext.
- CatalogImport ontsleutelt met de private sleutel (enkel bij CatalogImport) en versleutelt meteen opnieuw met AES-GCM en de
  AAD van `credential_ref`. De datasync bezit nooit een geheime sleutel; een gelogde of bewaarde bundel lekt niets.
- INDET is key-value (`Interface, Label, Value`, DS 271): niet-geheime rijen met `Label <> 'FTP_password'`; het wachtwoord
  langs een apart pad (labelnaam te verifiëren).

**Transport:** bestand in beheerde map of upload, PREVIEW/APPLY door een MANAGE-gebruiker (geen M2M nodig). Push later.

## 5. Herziening Q1–Q12 en slices

**Wijzigt:** Q1 producent = datasync-job (R3 vervalt, R1 terugval); Q2 = envelopversleuteling; Q6 = API-semantiek
(`VALUE`/`EMPTY_KEEPS_EXISTING`), basisprijs 0 blijft geblokkeerd als businessregel; Q8 zie §3; Q10 + transportsleutel en
momentopname referentiedata.
**Blijft:** Q3, Q4 (UPPER weegt nu ook voor matchen op Prodis-data), Q5, Q7, Q9, Q11, Q12.

| Slice | Inhoud |
|---|---|
| LM-0 | Q-beslissingen + V1–V6 |
| DS-1 (nieuw, datasync-repo) | Bundelexporter, enkel in modus PRODIS, `X$File`-controle per tabel |
| LM-1 | Ongewijzigd; bron = datasync-bundel, golden fixture zonder secrets |
| LM-2 / LM-3 | Ongewijzigd |
| LM-R (nieuw, vóór creatiestandaarden) | Referentie-API + momentopname |
| LM-4 | Credentials; vereist transportsleutel (nieuwe K-5) |
| LM-5 | Ongewijzigd (frontend) |
| LM-6 | Parallelrun vergelijkt met Prodis `catalog_article` (gespiegeld uit PSARF) i.p.v. `PSIMP<bib>` |
| LM-7 | Onderdeel van het nieuwe publicatiecontract (5-PUB-b′), niet van 252 |

## 6. Vragen voor de mens (aanbeveling denker-zwaar)

- **V1 Samenloop:** (A) publiceren enkel bij `legacy_master = NONE`, in PRODIS enkel SIMULATION + parallelrun; (B) Prodis-API
  routeert zelf (PRODIS via webbase/legacy, NONE lokaal); (C) uitsluiting per bibliotheek in de datasync.
  **Aanbeveling:** A nu, B later binnen Prodis zonder contractwijziging, C afwijzen.
- **V2 Route en authenticatie:** Prodis-publicatie-API (aanbevolen) vs. rechtstreeks in de DB. Client-credentials-client
  beperkt tot dit endpoint, subject van de goedkeurder in de payload; nooit `hasDdaProdisApiBypass()`. Herziet externe punt 1.
- **V3 Creatiestandaarden:** botst met 2026-09-22 ("Prodis-gebruiker-velden nooit"). Aanbeveling: nieuwe eigendomsklasse
  "alleen bij aanmaak", nooit bij UPDATE.
- **V4 Referentiedata:** API + lokale momentopname (aanbevolen) of alleen-lezen DB-rol.
- **V5 Datasync-job:** optie (c) met envelopversleuteling (aanbevolen); transport bestand/upload (aanbevolen) of M2M-push.
- **V6 Secret bij herhaalde sync:** enkel aanmaken als er nog geen is; vervangen enkel met expliciete APPLY-vlag door een
  MANAGE-gebruiker, audit `REPLACED`; nooit stil vergelijken.

**Aannames:** enumwaarden `TRIAL_LIBRARY`/`PRODUCTION` blijven; PSIMPORT-endpoints alleen-lezen; job enkel manueel (`once`);
groeppercentages pas gebruikt als de datasync ze mapt; geen backfill.

## 7. Tegenstrijdigheden, gaten, ontdekkingen

**Tegenstrijdigheden:** creatiesleutels vs. 2026-09-22; "aanbod niet aanvaard" (externe punten r.85-88) vs. herroeping;
"geen service-account" (punt 1) vs. M2M-publicatie; "uiteindelijk" Prodis-DB — eindtoestand of ook overgang (= V1).

**Gaten:** verwerking van groeppercentages in Prodis niet beslist; "Artikel uit catalogus in Geen" vereist volgens Prodis nog
`legacyBibId`/`legacyInternalId` (legacy-master-analysis §13), die CatalogImport-rijen niet hebben; herstelontwerp
vastgelopen run ontbreekt nog.

**Voorgestelde ontdekkingsblokken (nog niet teruggeschreven):**

> **Important technical constraint discovered**
>
> `LegacyMasterSyncGuard` laat de datasync elke run weigeren bij `legacy_master = NONE`. De legacy-config-export moet dus
> gebeuren vóór een klant naar NONE overschakelt.

> **Important technical constraint discovered**
>
> De datasync heeft geen authenticatie op `POST /api/jobs/{name}/run` (`JobTriggerController`), `application-production.yml`
> zet `be.dda.prodis: DEBUG`, en Spring Batch-metadata komt in de Prodis-DB. Een secret mag nooit in jobparameters, execution
> context, logs of foutbestanden staan; enkel ciphertext verlaat het geheugen. (Voorstel: `credentials-sleutelbeheer-design.md`.)

> **Important technical constraint discovered**
>
> De unieke Prodis-sleutel op `catalog_article` bevat de nullable `supplier_group_id`; zonder groep geen bescherming tegen
> dubbels. Idempotentie van de publicatie vraagt een eigen sleutel per item. (Voorstel: `fase5-pub-design.md`.)

> **Important business rule discovered**
>
> In Prodis zit de kortingscode in de leveranciersgroep (`SupplierGroup.legacyCode = lev|grp|korting`). Een identiteit met
> kortingscode moet bij publicatie naar die groep vertaald worden; bestaat die groep in Prodis niet, dan wordt het item
> afgewezen, nooit aangemaakt. (Voorstel: business-rules.)
