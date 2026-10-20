# Legacy-importdefinities en interface-instellingen overnemen — ontwerpvoorstel

> **Status: VOORSTEL (2026-10-20), nog niet aanvaard.** Uitgewerkt door denker-zwaar na de scopebeslissing
> "2026-10-20 — Scope: legacy-importdefinities en interface-instellingen overnemen" in `docs/decisions.md`.
> De §6-beslissingen Q1–Q12 (sectie 6) liggen bij de mens; pas na hun beantwoording (vastgelegd in
> `docs/decisions.md`) start LM-1. Legacy-bron: Legacy-vault (`Prg_1170`, `Prg_1176`, `Prg_1179`, `Prg_1223`,
> `Prg_1232`, DS 252–257, DS 270–273).
>
> **Deels herzien (2026-10-20):** na de herroeping van het publicatiedoel (Prodis-PostgreSQL, niet Zen) en Q2 (secrets via
> sync) zijn Q1, Q2, Q6, Q8, Q10, de slices en alle verwijzingen naar 252 IMPORT/1179 als publicatiedoel herzien in
> `docs/design/prodis-db-publicatie-en-datasync-design.md`. Bij tegenspraak geldt dat document.

## Kort

Advies: een **eenmalige maar herhaalbare import per leverancier van een geversioneerde "legacy-configbundel"**,
die landt als **DRAFT-revisie met bronverwijzing**, met een verplichte PREVIEW (droge run) en een rapport per
legacy-element. **Geen terugkerende synchronisatie.** Dit volgt BA1 §16.10 (`business-analyse-leveranciersbibliotheken.md`
r.4278-4284: "geïmporteerd als conceptrevisies met bronverwijzing … parallelrun … precies één actieve verwerker").

Drie harde blokkades in de huidige code en contracten; zonder beslissing is een groot deel van de legacy-definities
niet getrouw over te nemen:
- **Identiteit:** een vaste of berekende leverancier/groep kan niet, en hoofdletters worden niet gelijkgetrokken.
- **Verwijderen:** legacy verwijdert via CALCULATION 8 én via `BIB_Delete`.
- **Prodis-eigen vaste waarden bij creatie** (bv. btw, grootboekrekeningen).

## 1. Mapping-inventaris

Legende: **B** = bestaand concept · **U** = uitbreiding nodig · **N** = bewust niet overnemen · **§6** = beslissing van de mens.

| Legacy-element | Doel in CatalogImport | Status / reden |
|---|---|---|
| `PSIMDHFD.Code`, `Omschrijving` | `import_definition.code` / `description` (OWN_DEFINITION) | B |
| `PSIMDHFD.Code Vervanging`, `Code Schrappen`, `Update bestand` | — | N/gat. Code Vervanging is een **stuurcode** (Nr 100, `Prg_1176` r.77; parameter naar 1179/1172), géén opvolger van een definitie — spreekt BA1 r.2410 tegen. |
| PSIMPDEF Nr 9/10/11 met Kolom | `identity_supplier/_group/_reference_field` (COLUMN_INDEX) | B |
| Nr 9/10 met `Waarde`, interface-`Supplier`, calc 7/13 op 9/10 | — | **U+§6.** Identiteitskolommen zijn alleen bronkolommen (`SourceStructureConfigFactory` r.204-212, `RevisionOwnedField`) |
| Nr 23 Kortingscode | `identity_discount_code_field` (FOUR_PART) | B |
| INDET `ArtImp_Key_Lev/RefLev/LevGrp/Korting/Barcode` | `identity_profile_kind` | B voor lev+grp+ref(+korting). Zonder groep of met barcode: **§6** (botst met decisions 2026-09-18) |
| Nr 26 Basisprijs, Nr 4 Omschrijving NL | `record_base_price_field`, `record_description_field` | B |
| Nr 33-37, 40, 43 (% VKP1-5/AKP/bruto) | `VKPn_PCT`, `AKP_PCT`, `VKP_GROSS_PCT` | B |
| Nr 12 lev.barcode, 219 PIM-ID, 221 E_SUPPLIER | `SUPPLIER_BARCODE`, `PIM_ID`, `E_SUPPLIER` | B |
| Nr 56 Barcode | `EAN`? | U/gat (betekenis te bevestigen) |
| Nr 28-32, 39, 42 (absolute prijzen) | — | N (prijsmodel basisprijs + %, decisions 2026-09-22) |
| Nr 201-212 staffels, 44 supplementbedrag | — | N nu (staffellaag later, decisions 2026-09-22) |
| Nr 222-899 supplementvelden (`REF_SUPP_*`, `*_SUPP_DFT_n`, `PIM_SUPP_x`) | supplementmodel | U (nog niet ontworpen) |
| Nr 220 PIM-alternatieven | — | N (relaties/BOM later) |
| Nr 1, 2, 3, 5-8, 13-22, 24, 25, 27, 38, 41, 45-71, 100, 213-217 (artikelgroep, genres, btw, rekeningen, eenheid, prijscodes, info …) | geen catalogusveld; deels eigendom Prodis-gebruiker | **§6** (Q8); anders N |
| Nr 200/218 depot/stock | — | N (stockplugin buiten scope) |
| `Kolom` ≥ 100 | `source_reference` COLUMN_INDEX | B (legacy kapt op 100, wij niet: verklaard verschil) |
| `Positie`/`Lengte` | — | U: transformatie `SUBSTRING` |
| `Prefix`/`Suffix` | `PREFIX`/`SUFFIX` | B voor één van beide; samen of met CALCULATION: **U** (één `transform_kind` per mapping) |
| `Waarde` (vaste waarde) | `FIXED_VALUE` | B (identiteitsvelden: zie hierboven) |
| `Fill` | — | N: niet gebruikt in de 1170-logica |
| `XML_ELEMENT_PATH` | — | N: alleen CSV (`ck_import_definition_revision_format`) |
| `Include/Exclude` code 1 + `I/E Data` | `import_record_filter` (EQUALS/INCLUDE) | B; andere codes: **gat** (ExpCalc 119-124, `Prg_1170` r.977) |
| `PSIMPEXCL` | filter op TARGET_FIELD | U: combinatie van meerdere velden niet uit te drukken |
| CALC 1/2/3/4 | `ADD/SUBTRACT/MULTIPLY/DIVIDE` | B (legacy `Val('8.2')` / `Str('8.3')`: verklaard verschil) |
| CALC 7 + `PSIMPDEFMAP` | `MAP` | B, maar legacy valt bij een miss terug op de bronwaarde, wij niet (BA1 r.688); `transform_config varchar(1000)` te klein: **U** |
| CALC 8 (verwijderen) | — | **§6** (geen DELETE-mutatie) |
| CALC 9 concat, 10 split, A percentage | `CONCAT`, `SPLIT`, `PERCENTAGE` | B |
| CALC 11/12 (`EvalStr`) | — | N automatisch; eenvoudige patronen vertalen, rest manueel. VROOAM2-geval: U |
| CALC 13 (SEARCH in PSARFBIB) | — | N: vereist leestoegang Pervasive (grens, punt 5) |
| CALC 14 MapInstr | — | U: `MAP_CONTAINS` met expliciete volgorde |
| CALC 15 UPPER + impliciete `Upper()` op Nr 1-3, 8-11, 13, 14 … | — | U: `UPPER`, ook op identiteit (**§6**) |
| `PSGRPIMP` | — | N: Prodis-stamdata, toegepast door 1179 |
| INHEAD planning | `catalog_import_task` MANUAL | N als schedule; enkel in rapport |
| INHEAD Communicator, ExecuteProgram, Running?, MaxRunTime, INLOG | — | N |
| INDET `ArtImp_BibNr`, `Supplier` | `import_link.library_code`, `supplier_organisation` | B |
| `Separator`, `FILE_ENCODING`, `DecimalComma` | `structure_delimiter`, `structure_charset`, `decimalSeparator` | B (XML = N) |
| `SkipHeaderLines` | `has_header` + `header_line_number` | B voor 0/1; >1 zonder header: U |
| `FTP`+`FTP2`, `FTP_login`, `FTP_directory`, `FTP_filename` | connection/delivery-configuratieversie | B enkel voor SFTP; wildcard/`[YYYY]` → STARTS/ENDS_WITH of issue |
| `FTP_password` | `external_credential` (versleuteld) | B, zie §3 |
| `FTP/LOCAL/API` | servermap of upload / — / — | L = B; API en FTP: **§6** (catalog-import.md r.58) |
| `FTP_delete`, `Multi_file`, `Unzip`, `No_Archive`, `FILE_CUTOFF`, `RunWebserver`, `Email_*` | — | N |
| `FileCount_Min/Max`, `RecordCount_Tol%` | — | U (drempels op de revisie, in %) |
| `Skip_Duplicate_Contr`, `Skip_Excludes` | — | N: duplicaatcontrole verplicht |
| `ArtImp_Para_Koers` | — | **§6** (financiële conversie) |
| `CreaGrp`, `CreaArt`, `CrGrp_Per*`, `MS_Create`, `MS_Delete`, `BIB_Delete`, `BIB_Per_Supplier`, `CleanUp_Supplement`, `Feed_Barcode`, `PrcUpd*`, `ArtImp_Stock`, `InterfaceDeleteCode`, `ApplyCalculationNet` | — | N/§6: 1179/1172-parameters (publicatiekant), in rapport |
| INTRANS | — | gat (BA1 r.619) |

## 2. Migratievorm

- **Hybride:** eenmalige, veilig herhaalbare import tot de omschakeling per interface; daarna is legacy read-only.
  Een terugkerende sync zou twee meesters voor dezelfde configuratie maken.
- **Contract:** geversioneerde bundel "legacy-config-bundle v1" (PSIMDHFD, PSIMPDEF, PSIMPDEFMAP, PSIMPEXCL, INHEAD,
  INDET per interface). Upload naar een MANAGE-endpoint: eerst `PREVIEW` (rapport, niets geschreven), dan `APPLY`.
  Niet via `DeliveryArchiveStore`, niet gearchiveerd.
- **Producent van de bundel (Q1):** R1 Magic-export (Prg 1176 exporteert PSIMPDEF al als `Importdef_<code>.csv`,
  `Prg_1176` r.161, r.501-689); R2 los DDA-script via DDF-SQL (niet elke tabel heeft een DDF); R3 read-only endpoint
  in ProDisWebbase (nieuw M2M-contract). **Verworpen:** CatalogImport leest Zen rechtstreeks (grens 2026-09-18).
- **Idempotentie en conflicten** (nieuwe additieve tabel, Q10): herkomstsleutel `(omgeving/dossier, soort, legacy-code)`
  + inhoudshash. Zelfde hash → no-op met vorig rapport. Gewijzigde legacy → nieuwe DRAFT-opvolger, alleen als er geen
  open DRAFT is en de laatste revisie uit de migratie kwam; een ACTIVE-revisie wordt nooit aangeraakt. Botsing met
  handmatige configuratie (`uk_import_definition_code`, `uk_import_link_scope`) → rapportregel, nooit stil samenvoegen.
  Onbekende of dubbele legacyconfiguratie → issue (BA1 r.4280).

## 3. Credentials

- Niemand ziet een secret na invoer; geen secret gaat terug naar buiten (V1, 2026-09-29). Recht: MANAGE.
- Body handmatig inlezen zonder Jackson-logging (K-3-patroon); versleutelen via `CredentialService` met de actieve
  sleutel; event `CREATED` met reden "Overgenomen uit legacy INDET <interface>". Nooit de waarde of een hash ervan
  als idempotentiesleutel.
- Herhaald inlezen maakt nooit een tweede credential en vervangt nooit een secret (geen ontsleutelen om te vergelijken);
  vervangen blijft een menselijke `PUT`.
- **Transport (Q2):** P1 mens tikt opnieuw in (aanbevolen voor de start, bundel zonder secrets); P2 secrets in de
  bundel (enkel in geheugen, niet archiveren); P3 rechtstreeks uit ProDisWebbase.
- Alleen SFTP (`secret_kind` = `SFTP_PASSWORD`); FTP/API-secrets niet opslaan, wel melden.
- **Hostsleutel:** legacy kent die niet en `host_key_fingerprint_sha256` is NOT NULL (L5). De migratie maakt enkel de
  credential; de mens doet scan → vergelijken via ander kanaal → `POST /connection-profiles`.
- Een FTP-URL met ingebedde login/wachtwoord wordt nooit als host of map bewaard.

## 4. Semantiek en contracten (raakt 5-PUB-b/c)

- **Leeg = behouden** past bij "alleen eigen velden" (2026-09-22), maar "waarde → leeg/0" kan 252/1179 niet uitvoeren.
  Voorstel (Q6), zonder hashwijziging: WARNING-issue, nieuwe preview-state `EMPTY_KEEPS_EXISTING` (additief,
  `previewSpecVersion` +1), basisprijs 0 blokkeren richting 252.
- **Lege %:** legacy vult groeppercentages in (PSGRPIMP / `P_Default_*`); onze prijscontrole ziet het effectieve
  percentage niet. Risico benoemd.
- **Verwijderen (Q5):** CALC 8 vraagt een DELETE-mutatie (raakt `MutationActionType`, `ck_import_mutation_marker`,
  bundel, snapshot, preview; `ARIMP_DELETE` is vandaag NOT_CONTRACTED). Advies: nu niet migreren; apart ontwerp.
- **Harde regel voor 5-PUB-b:** 1179 altijd met `P_BIB_DELETE_NOT_UPDATED = false` en `P_MS_DELETE = false`.

## 5. Verticale slices (sequentieel)

| Slice | Inhoud | Lagen | Trap |
|---|---|---|---|
| LM-0 | §6-beslissingen Q1-Q12 | — | mens |
| LM-1 | Eén definitie uit de 1176-CSV + kleine header (code, leverancier, bib, scheidingsteken, header, encoding, DecimalComma). Geen secrets, geen INDET. PREVIEW/APPLY maakt OWN_DEFINITION + DRAFT + (optioneel) koppeling + MANUAL-taak. Enkel B-rijen; rest in rapport met redencodes. Activeren geblokkeerd zolang identiteit onvolledig. Herkomsttabel. Golden test VROOAM2 (BA1 §6.3). | Liquibase, Domain, Dao, Service, Web | bouwer-zwaar |
| LM-2 | `UPPER`, `SUBSTRING`, `MAP_CONTAINS`, transformatieketen, maptabel PSIMPDEFMAP, excludes PSIMPEXCL | Domain, Liquibase, Service | zwaar |
| LM-3 | Identiteitsuitbreidingen volgens Q4 | alle lagen | zwaar |
| LM-4 | INHEAD/INDET → koppeling/taak/DC + credentials volgens Q2/Q3 | Service, Web | zwaar |
| LM-5 | Frontend: upload, rapport, apply | Frontend | gemiddeld |
| LM-6 | Parallelrun: onze PSIMPORT-projectie vs. legacy `PSIMP<bib>` (enkel SIMULATION) | Service | zwaar, extern |
| LM-7 | Semantiek leeg/0/verwijderen in 5-PUB-b | Service, preview | na punt 3 |

## 6. Beslissingen voor de mens (aanbeveling denker-zwaar)

- **Q1 Vorm en bron:** bundel-upload, eenmalig en herhaalbaar; producent R1 of R2.
- **Q2 Secrets:** P1 voor de start; P2 enkel met de regels uit §3.
- **Q3 FTP/FTPS/API** (heropent catalog-import.md r.58): geen plain FTP; die interfaces voorlopig via upload of servermap.
- **Q4 Identiteit:** vaste leverancier/groep per revisie toelaten (additief) en `UPPER` expliciet per identiteitsveld; sleutel zonder groep blijft niet ondersteund.
- **Q5 Verwijderen:** CALC 8, `BIB_Delete`, `MS_Delete` niet migreren; apart ontwerp.
- **Q6 Leeg = behouden:** WARNING-issue + preview-state, geen vingerafdrukwijziging.
- **Q7 Wisselkoers ≠ 0/1:** migratie van die interface blokkeren tot er een financieel ontwerp is.
- **Q8 Prodis-eigen vaste creatiewaarden:** ruw bewaren, niet publiceren; melden dat CREATE functioneel achterblijft op legacy.
- **Q9 Calc 11/12:** geen expressietaal; per geval een gesloten transformatie.
- **Q10 DB:** additieve tabellen voor herkomst/rapport, maptabel en transformatieketen.
- **Q11 Definitie per interface:** OWN_DEFINITION per interface in v1; sjabloon later.
- **Q12 Planning:** MANUAL; legacy-planning enkel in het rapport.

**Aannames (geen §6):** recht MANAGE, niet achter de setup-vlag; PREVIEW verplicht vóór APPLY; `Fill`, XML, meerdere
bestanden, e-mail en INLOG niet overgenomen; testfixtures zonder klantdata of secrets.

## 7. Tegenstrijdigheden, gaten en ontdekkingen

**Tegenstrijdigheden:**
- BA1 r.2410 (Code Vervanging = opvolger) vs. `Prg_1176` r.77 (stuurcode).
- Decisions 2026-09-18 (identiteit) en 2026-09-22 (publicatiebreedte) botsen met de legacy-flexibiliteit.

**Gaten:** Include/Exclude-codes ≠ 1, INTRANS, `Update bestand`/`Code Schrappen`, onderscheid FTP/SFTP in INDET,
getalformaat in de 1176-export, bereik PSIMPDEFMAP-sleutel bij calc 7, hoofdletters in PSARF, aantal interfaces per klant.

**Voorgestelde ontdekkingsblokken (nog niet teruggeschreven, wacht op akkoord):**

> **Important technical constraint discovered**
>
> CatalogImport publiceert per bundel (een deelverzameling). Een 1179-aanroep met `P_BIB_DELETE_NOT_UPDATED=true` of
> `P_MS_DELETE=true` zou alles buiten de bundel wissen. 5-PUB-b/c moet beide hard op `false` zetten.
> (Voorstel: `fase5-pub-design.md`.)

> **Important technical constraint discovered**
>
> Leverancier, groep, referentie en basisprijs kunnen alleen een bronkolom zijn: geen vaste waarde, geen transformatie
> (`SourceStructureConfigFactory` r.204-234, `RevisionOwnedField`). Daarnaast: `transform_config varchar(1000)` en één
> transformatie per mapping. (Voorstel: `fase3-rules-design.md`.)

> **Important business rule discovered**
>
> Legacy zet leverancier, groep en referentie (Nr 9-11) en nog een tiental velden met `Upper()` in hoofdletters
> (`Prg_1170` r.733-815). Onze canonicalisatie is hoofdlettergevoelig (`CandidateNormaliser` r.601). Een overgenomen
> definitie zonder expliciete `UPPER` geeft andere identiteiten dan PSARF. (Voorstel: business-rules.)

> **Important technical constraint discovered**
>
> Legacy rekent zo (`Prg_1170` r.771-789, r.1039-1115): `Val()` zet leeg/ongeldig stil op 0; berekeningen ronden af op
> 3 decimalen met een operand van 2 decimalen; in calc 11 wordt een lege kolom `'0'`; de koers vermenigvuldigt alleen
> bronwaarden (Nr 26, 28-32, 39, 42, 44), niet een vaste `Waarde`. Bij de parallelrun zijn verschillen te verwachten;
> tonen als verklaard verschil, nooit wegwerken.
