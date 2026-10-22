# Publicatiefase naar de Prodis-PostgreSQL — geactualiseerd ontwerp

> **Status: VOORSTEL (2026-10-22), denker-zwaar.** Vervangt §2, §3 en §6 (V1–V4) van `prodis-db-publicatie-en-datasync-design.md`;
> bouwt verder op `fase5-pub-design.md` (snapshot, `publication_run`). Bindend blijft: bundel als eenheid, atomair per scope, "alleen
> eigen velden" (2026-09-22), BOM later, approve = publiceren (2026-09-25 A2), geen cross-DB-FK, geen scheduler.
> **Open bij de mens: P-V1–P-V5 (§7).**

## 1. Wat veranderd is

| Nieuw gegeven | Bron | Gevolg |
|---|---|---|
| Alleen-lezen Prodis-leesbron met eigen rol | `ProdisReferenceDao.java` r.10-30, S5 | V4 = uitbreiding van die leesbron, geen referentie-API |
| Prodis aanvaardt het `web_app`-gebruikerstoken via BFF-relay met refresh | `ProdisAccountClient.java` r.123-151; 5B-7 | Schrijven met het **token van de approver**; geen service-account nodig |
| Prodis is rechtenbron, `catalogImport.approve` geseed | `prodis/.../20261020120000_seed_catalog_import_rights.xml` | Prodis-endpoint controleert `catalogImport.approve` (A2), geen `catalogImport.publish` |
| Prodis heeft de legacy-prijsformule (`LegacyPriceCalculator`, Prg_1185; Prg_261-tegenhanger) | `LegacyPriceCalculator.java` r.44-73; `SupplierGroupPricePropagationService.java` r.241-296 | VKP-bedragen berekend **in Prodis**; wij sturen basisprijs + percentages |
| Prodis-voorstel publicatie-API | `prodis/docs/.../catalog-import-publication-api-design.md` §3-§7 | Overgenomen met wijzigingen: batch, `approve`, dry-run, `legacy_master`-guard |
| Enums hebben de nodige waarden | `PublicationBundleStatus`, `MutationStatus` | Additief hergebruik |
| Herstel vastgelopen runs (60 min + afbreken) | decisions 2026-09-27 | Inperken: een run met verstuurde items wordt nooit automatisch FAILED |

## 2. Publicatieroute: idempotente Prodis-API (voorstel in de Prodis-repo, de mens commit)

**Endpoint (nieuw, Prodis):** `POST /api/catalog-import/publications`, body `{dryRun, items[≤500]}`, recht `catalogImport.approve`; elk
item in een eigen transactie samen met zijn registratierij (atomair per record; v1 geen sets/deletes); **eigen stateless
SecurityFilterChain** (enkel bearer, zonder sessie/CSRF, patroon DDAProdis-keten `SecurityConfiguration.java` r.140-150; zie D1).
HTTP: 200 met uitkomst per item; 409 `LEGACY_MASTER_NOT_NONE` voor de hele batch bij `dryRun=false` en `legacy_master ≠ NONE` (niets
toegepast, definitief); 5xx/timeout → alle items van de batch `RESULT_UNKNOWN`.

**Payload per item** (businesscodes, nooit Prodis-id's):
```
externalMutationId, requestHash, payloadSpecVersion=1, action CREATE|UPDATE,
origin{bundleId, bundleContentHash, snapshotHash, runId, mutationId, frozenBy, frozenAt},
identity{supplierNumber, supplierGroup, discountCode, supplierReference, catalogScopeKey},
fields{basePrice{state,value,currency}, salesPercentages{"1".."5":{state,value}}, description{state,language:"NL",value}},
expected{basePrice}
```
`state` = `VALUE|KEEP`; `NOT_MAPPED` weggelaten; bedragen/percentages als string.
**Antwoord per item:** `outcome` `APPLIED|UNCHANGED|REJECTED|CONFLICT` (dry-run: `WOULD_CREATE|WOULD_UPDATE|UNCHANGED|REJECTED|CONFLICT`),
`reasonCode` (+ veld), `prior{…}`, `result{basePrice, salesPrices, salesPercentages}`.

**Idempotentie:** sleutel `<catalogimport.publication.source-id>:mut:<mutationId>:pa:<n>`, uniek in Prodis
(`catalog_import_publication.external_mutation_id`); zelfde sleutel + hash → opgeslagen resultaat; andere hash → REJECTED
`ID_REUSED_WITH_DIFFERENT_CONTENT`. `n` stijgt enkel na een definitief niet-toegepast resultaat (REJECTED/CONFLICT); bij `RESULT_UNKNOWN`
nooit een nieuwe sleutel. Niet steunen op `ux_catalog_article__supplier_key`.
**RESULT_UNKNOWN:** dezelfde bytes opnieuw sturen (veilig; Prodis legt registratie + mutatie in één transactie vast). Geen status-endpoint in v1.

**Hervalidatie in Prodis** (alles-of-niets per item): leverancier bestaat en is uniek; `supplier_catalog` ACTIVE voor leverancier +
`scope_key`; `supplier_group.legacy_code` (`lev|grp|korting`) bestaat (nooit aangemaakt); CREATE op bestaand artikel → CONFLICT,
UPDATE op onbestaand → REJECTED `ARTICLE_NOT_FOUND`; munt EUR; precisie zonder verlies (D2); percentages enkel bij `price_code='0'` (A4);
toepassen via de Prodis-servicelaag + `LegacyPriceCalculator`; `sales_price_last_updated` enkel bij echte wijziging.

**Kant CatalogImport** (`PRODUCTION` = "Prodis-PostgreSQL via API", javadoc-wijziging):
- Nieuwe tabel `publication_run_item` (`run_id`, `mutation_id`, `publication_attempt`, `idempotency_key`, `request_hash`, `request_json`,
  `status`, `outcome`, `reason_code`, `reason_detail`, `prior_json`, `result_json`, `sent_at`, `answered_at`, `send_count`; uniek op
  `idempotency_key`, `(run_id, mutation_id)`, `(mutation_id, publication_attempt)`).
- Run: `REQUESTED → PREPARING → PUBLISHING → APPLIED | RECOVERY_REQUIRED | FAILED` (FAILED enkel zonder verstuurde items; `isTerminal()`
  breidt uit — gemelde gedragswijziging). Item: `PENDING → SENT → APPLIED | UNCHANGED | REJECTED | CONFLICT | RESULT_UNKNOWN`, plus
  `NOT_SENT`. Mutatie: `READY_FOR_PUBLICATION → IN_PROGRESS → PUBLISHED`; bij REJECTED/CONFLICT terug naar `READY_FOR_PUBLICATION`.
  Bundel: `FROZEN → PUBLISHING → PUBLISHED | PARTIALLY_PUBLISHED`; daarna niet meer annuleerbaar.
- Transacties: (1) slot op bundel + controles + items materialiseren; (2) per chunk items SENT (commit) → HTTP buiten transactie →
  resultaten (commit); crash ertussen → RESULT_UNKNOWN, opnieuw bij "continue"; (3) afronden als elk item terminaal is.
- Zonder scheduler: `POST /bundles/{id}/publication-runs {targetMode: PRODUCTION}` (run + eerste chunk); `POST /publication-runs/{id}/continue`
  (APPROVE; volgende ≤ chunk-size, eerst RESULT_UNKNOWN opnieuw); de frontend roept continue in een lus (elke chunk een vers token).
- Startcontroles: 409 `PUBLICATION_MODE_NOT_ENABLED` (vlag `catalogimport.publication.prodis.enabled`, default false); bundel
  `target_mode = PRODUCTION`; 409 `BUNDLE_NOT_SNAPSHOTTED`; 409 `BUNDLE_HAS_INCOMPLETE_ROWS`; niet-EUR → `CURRENCY_NOT_SUPPORTED_BY_PRODIS`
  (D3); hoogstens één actieve PRODUCTION-run (partiële unieke index).
- Herstel: timeout en afbreken enkel voor PREPARING zonder verstuurde items; afbreken in PUBLISHING → 409 `PUBLICATION_RUN_HAS_SENT_ITEMS`.

## 3. Samenloop met legacy en datasync

In modus PRODIS: een UPDATE wordt teruggedraaid (fingerprint, `CatalogArticleJob.java` r.1495-1525); een CREATE zonder legacy-id's ontsnapt
aan de stale-delete (r.2766) maar `filterSupplierKeyConflicts` (r.919-955) laat dan de legacyrij met dezelfde sleutel vallen; een CREATE
mét `legacy_bib_id` wordt gewist.

| Modus | Echt schrijven | Dry-run / parallelrun |
|---|---|---|
| PRODIS | geweigerd door Prodis (409 `LEGACY_MASTER_NOT_NONE`); run FAILED, niets verstuurd | toegestaan |
| PROFAB | geweigerd | toegestaan |
| NONE | toegestaan | toegestaan |

Guard in Prodis (`LegacyMasterResolver`); CatalogImport toont de modus vooraf via de leesbron en heeft de eigen vlag als noodrem.

## 4. Referentiedata en creatiestandaarden
- Leesbron (S5) uitbreiden met GRANTs voor `catalogimport_ro`: `vat_code(code)`, `general_ledger(account_number)`,
  `article_group(prodis_code)`, `unit_of_measure(code)`, `language(code)`, `system_settings(legacy_master)`; voor de parallelrun
  `catalog_article`, `catalog_article_price`, `catalog_article_price_detail`, `catalog_article_description`.
- Businesscodes bewaren, geen id's; geen lokale momentopname in v1 (A2); Prodis hervalideert.
- Validatie volgens het S6-patroon (setup-API 400/503; bundelpreview en dry-run per item).
- Velden enkel bij CREATE (bij P-V3 = a): btw, artikelgroep, verkoop-/aankooprekening, factuur-/verkoop-/aankoopeenheid, `price_code`,
  `brut_price_politics` — creatiestandaard op `import_link` (buiten de revisiehash), nooit bij UPDATE.

## 5. Mapping

| Ons veld | Prodis | Status v1 |
|---|---|---|
| leverancier | `supplier.number` | ja |
| groep + kortingscode | `supplier_group.legacy_code` `lev\|grp\|korting` | ja, moet bestaan |
| referentie | `supplier_reference` = `supplier_reference_stripped` (A3) | ja |
| `library_code` | `supplier_catalog.scope_key`, ACTIVE | ja |
| `legacy_bib_id`/`legacy_internal_id` | null | — (D9) |
| BASE_PRICE | `catalog_article_price.base_price` | ja, precisiecontrole |
| munt | geen kolom | enkel EUR |
| VKP1..5 % | `catalog_article_price_detail.percentage` (`"1".."5"`), `.price` in Prodis | ja |
| DESCRIPTION | `catalog_article_description` NL, `default_language=true` | ja |
| AKP % | `purchase_percentage` | later (D6) |
| VKP_GROSS % | `brut_price_percentage` | later |
| EAN, PIM_ID, SUPPLIER_BARCODE, E_SUPPLIER | `supplier_barcode`, `catalog_article_external_reference` | ontbreekt in snapshot/mutatie (D8) |
| CAB_ID, E_MARK_ARTICLE_REFERENCE | geen kolom | ontbreekt |
| supplementen | geen mapping | ontbreekt aan beide kanten (D7) |
| BRAND, UNIT, voorraad, rekeningen | PRODIS_USER | nooit (2026-09-22) |

Leeg = behouden: `NOT_MAPPED` weggelaten, `EMPTY` → `KEEP` + WARNING; nooit wissen in v1 (P-V5). Afronding: Prodis rekent VKP-bedragen
volgens Prg_1185 (HALF_UP, catalogusdecimalen); resultaat in `result_json`; afwijking met een bronbedrag → vlag `PRICE_ROUNDING_DIFFERENCE`,
zichtbaar in de dry-run, nooit stil gecorrigeerd.

## 6. Bouwvolgorde (strikt sequentieel)

| # | Waar | Inhoud | Trap | Acceptatie |
|---|---|---|---|---|
| P0 | mens | P-V1..P-V5 + decisions | — | beslissingen gelogd |
| P1 | CI | Leesbron uitbreiden (+ GRANT-specificatie), `legacy_master` lezen | gemiddeld | onbereikbaar → 503; geen id's |
| P2 | CI | Parallelrun: `ProdisPublicationProjection` (snapshot → items) + vergelijking met `catalog_article`, als artefact/tellers op de SIMULATION-run | zwaar | MATCH / DIVERGED / NOT_FOUND / WOULD_CREATE; deterministisch; niets geschreven |
| P3 | **Prodis-repo (voorstel, mens commit)** | Endpoint + stateless chain + `catalog_import_publication` + guard + hervalidatie + `LegacyPriceCalculator` + dry-run | zwaar | 2× zelfde item → één wijziging; PRODIS → 409; rollback incl. registratie |
| P4 | CI | Liquibase 027: `publication_run_item`, run-status PUBLISHING, partiële unieke index | gemiddeld | constraints onder gelijktijdigheid |
| P5 | CI | Prodis-client met token relay (gedeelde relay uit `ProdisAccountClient`) + PRODUCTION-pad (start, continue, opnieuw, afronden) | zwaar | geen dubbele toepassing; crash na SENT → één toepassing; REJECTED → PARTIALLY_PUBLISHED; 409 → FAILED |
| P6 | CI | Endpoints continue, itemlijst, vlag, foutcodes, rechten | gemiddeld | APPROVE/READ volgens A2 |
| P7 | Frontend | Publicatietab: PRODUCTION met bevestiging, continue-lus, itemresultaten, dry-run-verschillen | gemiddeld | afwijking en afronding zichtbaar |
| P8 | CI + Prodis | CREATE met creatiestandaarden (na P-V3) | zwaar | CREATE nooit zonder gevalideerde standaard |
| P9 | later | AKP/AVP (D6), referenties (snapshot v2), supplementen/BOM | — | — |

**Operationele poort:** echt schrijven pas na beoordeling van het parallelrun-rapport (P2 + dry-run P3) en met `legacy_master = NONE`.

## 7. Vragen voor de mens
- **P-V1 Samenloop:** echt schrijven enkel bij `NONE` (Prodis-guard); in PRODIS/PROFAB enkel dry-run/parallelrun. Aanbeveling: ja.
- **P-V2 Route en authenticatie:** nieuw Prodis-endpoint (door ons als voorstel, door de mens gecommit), gebruikerstoken van de approver
  via relay, recht `catalogImport.approve`; technische client pas bij automatische publicatie. Aanbeveling: ja.
- **P-V3 CREATE:** (a) creatiestandaarden per koppeling, enkel bij aanmaak; (b) minimale CREATE; (c) geen CREATE in v1. Aanbeveling: (a),
  maar eerst UPDATE-only bouwen (CREATE-items `NOT_SENT`, bundel PARTIALLY_PUBLISHED).
- **P-V4 Afwijking in Prodis** (Prodis-waarde ≠ ons `before`): CONFLICT voor basisprijs en percentages, rapport voor de omschrijving;
  CREATE op bestaand artikel → CONFLICT. Aanbeveling: zo.
- **P-V5 Leeg:** leeg = behouden, nooit wissen in v1, WARNING `EMPTY_KEEPS_EXISTING`. Aanbeveling: ja.

**Aannames:** A1 één publicatie tegelijk per installatie; A2 geen lokale momentopname referentiedata; A3
`supplier_reference_stripped` = referentie; A4 percentages enkel bij `price_code '0'` (te verifiëren in `Prg_1179.xml`); A5 eerste scope
basisprijs + VKP1..5 % + omschrijving NL; A6 chunk 200, batch ≤ 500; A7 `catalog_source_state` ongewijzigd, laatst gepubliceerde waarde in
`publication_run_item.result_json`.

**Tegenstrijdigheden bij te werken:** `fase5-pub-design.md` §3/§5 en javadoc `PublicationTargetMode.PRODUCTION` (r.15);
`PublicationRunStatus.isTerminal()` (r.50-52); het Prodis-design (één item per aanroep, `.publish`); de BOM-beslissing 2026-09-22 noemt het
supplementmodel "gebouwd" maar de code heeft er geen (D7).

## 8. Ontdekkingen
- **D1** (technisch): Prodis' hoofdketen `/api/**` dwingt cookie-CSRF af (`SecurityConfiguration.java` r.155-171); een bearer-only
  server-naar-server POST wordt geweigerd; het endpoint aan `ignoringRequestMatchers` toevoegen is onveilig → eigen stateless keten.
- **D2** (technisch): Prodis-prijzen zijn `Double` (`CatalogArticlePrice.java` r.34-47); `purchase_price`, `brut_price` en percentages zijn
  `double`; `base_price` is `decimal(21,6)` maar via Double geschreven. Prodis moet precisieverlies weigeren (`PRECISION_LOSS`).
- **D3** (business): `catalog_article_price` heeft geen munt; enkel EUR publiceerbaar, nooit omrekenen.
- **D4** (technisch): in modus PRODIS wordt een UPDATE teruggedraaid en laat een CREATE de legacyrij met dezelfde sleutel vallen; ook na
  terugschakelen van NONE naar PRODIS.
- **D5** (business): VKP-bedragen zijn afgeleid `Round(Bverk × VKP%/100 × coef)` (Prg_1185); verschil met een bronbedrag expliciet melden.
- **D6** (technisch): de datasync spiegelt `purchase_percentage`/`purchase_price` uit AKP2; Prg_1185/261 werken op AKP1; welk AKP ons veld
  40 is, is niet geverifieerd.
- **D7** (technisch): CatalogImport heeft geen supplementmodel; de datasync mapt supplementen niet.
- **D8** (technisch): EAN, PIM_ID, SUPPLIER_BARCODE, E_SUPPLIER zitten niet in snapshot/mutatie; publiceren vraagt snapshot spec v2.
- **D9** (technisch): door CatalogImport aangemaakte artikels hebben geen `legacy_bib_id`/`legacy_internal_id`; "Artikel uit catalogus" werkt
  in NONE nog niet zonder die id's (Prodis-werk, niet blokkerend).
