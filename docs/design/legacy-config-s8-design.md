# Legacy-config S8 — maptabel, transformatieketen, Include/Exclude, PSIMPEXCL

> **Status: VOORSTEL (2026-10-21), denker-zwaar; S8a gebouwd (2026-10-21, zie "Uitwerking S8a" in §2a).** Verfijnt
> `legacy-config-bundle-design.md` §8 rij S8 en `legacy-config-migratie-design.md` LM-2. S8b–S8e kunnen starten; **S8f wacht
> op V2**, **S8g wacht op V1** (§5).
> Bronnen: `LegacyConfigTranslator`, `FieldTransform`, `FieldValueMapper`, `MappingSettings`, `ImportMappingConfigFactory`,
> `RecordFilterEvaluator`, `RevisionCopier`, `LegacyRevisionContent`, Liquibase 004; Legacy-vault `Prg_1170` r.370-480,
> r.700-1170, r.2582-2690, DS_0254, DS_0257; ruwe bron `C:\XPA48\Projects\PRODIS\Source\Prg_1170.xml` r.18681-18701.

**Correcties op eerdere aannames:** PSIMPEXCL wordt toegepast in `Prg_1170` (taken "Check Excludes"/"Excludes",
r.2582-2687, enkel als `NOT >SkipExcludes`, r.395), niet in `Prg_1171`.

## 1. Scope

| Onderdeel | In S8 | Waarom |
|---|---|---|
| Maptabel `import_field_value_map` (CALC 7) | S8a | `MAP_TOO_LARGE` en niet-uitdrukbare `;\|>` verdwijnen; MAP_CONTAINS heeft de tabel nodig |
| UPPER (CALC 15), SUBSTRING (Positie/Lengte) | S8b | kleine gesloten soorten, geen schema |
| Keten (substring + prefix/suffix + CALC) | S8c | legacy past elke regel als vaste pijplijn toe |
| MAP_CONTAINS (CALC 14) | S8d | gebruikt de tabel |
| Include/Exclude-codes 2–6 en EN tussen include-regels | S8e | zonder schemawijziging uit te drukken |
| PSIMPEXCL + `Skip_Excludes` | S8f, na V2 | vraagt een EN-combinatie over kolommen |
| Terugval bij map-miss | S8g, na V1 | botst met R-REC-08 |
| Transformaties op identiteit 9–11, CALC 8/11/12/13 | nee | Q4/Q5/Q9 en systeemgrens; blokkerende codes blijven |
| CALC 9/10 en Include/Exclude met Positie/Lengte | nee (aanname) | klein vervolg |

## 2. Per onderdeel

### 2a. Maptabel (S8a, Liquibase `023-field-value-map.sql`)
- `import_field_value_map`: id; `mapping_id` FK → `import_field_mapping`; `sequence_number` int > 0; `source_value`
  varchar(50) not null, niet blanco; `target_value` varchar(50) not null (`''` toegelaten); `created_at/by/by_subject`
  (check zoals 007-4); uniek `(mapping_id, source_value)` en `(mapping_id, sequence_number)`. 50 = legacy KEY/VALUE (DS_0254).
- CALC 7 exact zoals legacy (r.1009-1021, r.1053-1054): opzoeking op (IMPORT, KEY) over álle NUMMERs; bij dubbele sleutel wint
  de laagste NUMMER (`MAP_KEY_AMBIGUOUS` blijft gemeld). `caseSensitive=true` (aanname; parallelrun bevestigt).
- Compatibiliteit: `values=` in `transform_config` blijft werken; MAP met tabelrijen zonder `values=` gebruikt de tabel; beide,
  geen van beide, of (bij `caseSensitive=false`) sleutels die enkel in hoofdletters verschillen → `CONFIG_TRANSFORM_INVALID`.
  Rijen één keer per batch gelezen; additieve overload van de fabriek.
- Rapport: `MAP_TOO_LARGE`, `TRANSFORM_VALUE_NOT_EXPRESSIBLE` (maps) verdwijnen; `MAP_EMPTY`, `MAP_KEY_AMBIGUOUS`,
  `LEGACY_MAP_UNUSED`, `MAP_MISS_NO_FALLBACK` (tot V1) blijven.

**Uitwerking S8a (gebouwd 2026-10-21; bouwer-zwaar).**
- *Schema:* changeset `023-1` zoals hierboven, plus `check (sequence_number > 0)`, `check (length(btrim(source_value)) > 0)`
  en koppelcheck `created_by_subject is null or created_by is not null`; geen cascade (de applicatie verwijdert de rijen vóór
  hun mapping, als entiteit, zodat een in dezelfde transactie beheerde kopie nooit naar een verwijderde mapping wijst).
- *Screening:* `ImportFieldValueMapRepository.findByRevisionId` (één query per revisie, `join fetch` van de mapping) in
  `ImportMappingConfigFactory.from(revision, structure)` en `collectInto(revision, facts, problems)`; additieve overloads met
  `valueMapRows` voor `from`/`collect`/`collectInto`; `FieldTransform.of(kind, settings, valueMap, ...)`. Bevindingen (M3k,
  ook in collect-modus): beide tabellen, geen van beide (ongewijzigde boodschap), rijen bij een andere soort dan MAP (zouden
  stil zonder effect blijven), dubbele of blanco bronwaarde, en bij `caseSensitive=false` twee bronwaarden die teken per teken
  enkel in hoofdletters verschillen (zoals `equalsIgnoreCase`). De opzoeking zelf is die van `values=` (`Translate`).
- *Inhoudshash:* `LegacyRevisionContent.ofRevision(revision, mappings, filters, valueMaps)` en `ofPlanned` voegen per mapping
  `V, volgnummer, bron, doel` toe, enkel als er rijen zijn (letterlijke pre-S8a-hash met een `values=`-mapping in
  `LegacyRevisionContentParityTest`). Een vóór S8a gemigreerde CALC 7-mapping plant nu een andere inhoud (rijen i.p.v.
  `values=`), dus een opvolgende DRAFT bij een nieuwe APPLY, nooit `MANUAL_CHANGES_SINCE_MIGRATION`.
- *Kopie:* `RevisionCopier.copyMappingChildren(saved, source)` (`saved` = de opgeslagen kopieën uit `saveAll`, gekoppeld op
  doelveldcode; tekening = die van de gekopieerde mapping) in `RevisionSuccessorService`, `LegacyConfigApplyService`
  (gedeelde definitie, S7) en `TemplateMaterialisationService`.
- *Setup-API (additief):* `CreateMappingCommand.valueMap[{source, target}]` en `MappingView.valueMap` (ook in het
  revisiedetail; leeg als er geen rijen zijn). Getrimd zoals `values=`; 400 `VALUE_MAP_SOURCE_REQUIRED`/`_TOO_LONG`/
  `_DUPLICATE`, `VALUE_MAP_TARGET_REQUIRED`/`_TOO_LONG`, `VALUE_MAP_INVALID` (lege regel); de rest via de
  configuratievalidatie (400 `CONFIG_TRANSFORM_INVALID`); niets bewaard bij een fout.
- *Migratie:* CALC 7 → MAP met `caseSensitive=true` (+ notatie) en rijen in volgorde (NUMMER, KEY); sleutel en waarde getrimd;
  langer dan 50 tekens → WARNING `MAP_ENTRY_TOO_LONG` (nieuw; mapping vervalt, nooit afgekapt); item `MAP_TABLE` met doel
  `import_field_value_map`.
- *Niet gewijzigd:* `values=` met `caseSensitive=false` en sleutels die enkel in hoofdletters verschillen blijft aanvaard
  (bestaande configuraties); de hoofdletterregel geldt enkel voor de tabel.

### 2b. UPPER en SUBSTRING (S8b, enum additief, geen Liquibase — `transform_kind` heeft geen check, 004-2 r.205)
- UPPER: `toUpperCase(Locale.ROOT)`, null blijft null.
- SUBSTRING: `start` (1-gebaseerd, default 1), `length` (optioneel = rest); volgt `MID(v, pos 0→1, len 0→500)` + `Trim`
  (r.730); start voorbij het einde → `""` (aanname). Positie/Lengte op 9–11/26 blijft blokkeren.
- Legacy doet geen impliciete `Upper()` op doelvelden 12/33-37/40/43/219/221: UPPER enkel voor expliciete CALC 15.
- `LEGACY_SUBSTRING_NOT_MIGRATED` en `LEGACY_CALC_15_NOT_MIGRATED` verdwijnen voor mapping-regels.

### 2c. Transformatieketen (S8c, `024-field-transform-step.sql`)
- Legacy-volgorde per regel (r.730-732, r.1039-1115): MID op ruwe waarde → Trim → enkel indien niet leeg
  `Trim(prefix) & v & Trim(suffix)`, anders `''` → hoogstens één CALC. Vaste `Waarde` negeert prefix/suffix/CALC → `FIXED_VALUE`
  met `LEGACY_IGNORED_WITH_FIXED_VALUE` (zoals S6).
- Model: `import_field_transform_step` (`mapping_id` FK, `step_number` 1..3, `transform_kind` ≠ `CHAIN`, `transform_config`
  varchar(1000), audit; uniek `(mapping_id, step_number)`); mapping met nieuwe soort `CHAIN` draagt enkel nog de notatie in
  `transform_config`. Nieuwe soort `AFFIX` (prefix en/of suffix, legacy-semantiek); `PREFIX`/`SUFFIX` ongewijzigd.
- Gesloten grammatica: `[SUBSTRING]? → [AFFIX|PREFIX|SUFFIX]? → [UPPER|MAP|MAP_CONTAINS|ADD|SUBTRACT|MULTIPLY|DIVIDE|PERCENTAGE|SPLIT]?`;
  1–3 stappen; hoogstens één tabelstap; `FIXED_VALUE`, `CONCAT`, `NONE`, `CHAIN` nooit als stap. Overtreding → `CONFIG_TRANSFORM_INVALID`.
  Geen expressietaal (Q9).
- Semantiek: invoer getrimd; lege invoer na trim → `""`, stappen overgeslagen. Legacy rekent `Val('')=0 ± x`; wij laten leeg
  (verklaard verschil, nooit een verzonnen getal).
- Migratie (aanname): elke regel met minstens één bewerking wordt `CHAIN`; `TRANSFORM_CHAIN_UNSUPPORTED` verdwijnt. S2-gemigreerde
  PREFIX/MAP-mappings krijgen bij een nieuwe APPLY een opvolgende DRAFT (nooit `MANUAL_CHANGES_SINCE_MIGRATION`).

### 2d. MAP_CONTAINS (S8d)
- Legacy `MapInstr` (r.1128-1167): enkel rijen van de eigen NUMMER, KEY oplopend; treffer als
  `InStr(Upper(v), Trim(Upper(KEY))) > 0`; eerste treffer met niet-lege VALUE; miss → `''`.
- Bij ons: soort `MAP_CONTAINS` op de maptabel, volgorde `sequence_number` (migratie: KEY-volgorde), hoofdletterongevoelig
  (`Locale.ROOT`); rijen met lege VALUE niet overgenomen (rapportitem); miss → `MAPPING_VALUE_UNKNOWN` + WARNING
  `MAP_CONTAINS_MISS_NOT_EMPTY` (zie V1). `LEGACY_CALC_14_NOT_MIGRATED` verdwijnt.

### 2e. Include/Exclude (S8e, geen schema)

| Code | Legacy slaat over als | Ons filter |
|---|---|---|
| 1 | `Trim(v) <> Trim(d)` | INCLUDE EQUALS (ongewijzigd) |
| 2 | `Trim(v) = Trim(d)` | EXCLUDE EQUALS, `COMPARE_AS_EMPTY` |
| 3 | `InStr(v,d)=1` | EXCLUDE BEGINS_WITH, `COMPARE_AS_EMPTY` + WARNING `LEGACY_FILTER_PADDING_UNVERIFIED` |
| 4 | `InStr(Flip(v),Flip(d))=1` | EXCLUDE ENDS_WITH, idem |
| 5 | `InStr(Trim(v),Trim(d))=0` | INCLUDE CONTAINS |
| 6 | `InStr(..)>0 AND v<>''` | EXCLUDE CONTAINS, `COMPARE_AS_EMPTY` |

- EN tussen include-regels (elke regel zet `Verwerken=FALSE`, r.940-941): bij 2+ include-regels wordt elke include via De Morgan
  EXCLUDE NOT_EQUALS (code 1) of EXCLUDE NOT_CONTAINS (code 5), null-gedrag EXCLUDE. Eén include blijft INCLUDE.
  Hoofdlettergevoelig zoals Magic.
- Verdwijnen: `INCLUDE_FILTER_COMBINATION_UNSUPPORTED`, `INCLUDE_EXCLUDE_CODE_NOT_MIGRATED` (codes 2–6). Nieuw: WARNING
  `INCLUDE_EXCLUDE_SUBSTRING_NOT_MIGRATED`; filter op regel met vaste `Waarde` gemeld. Blijft: `INCLUDE_FILTER_WITHOUT_COLUMN`.

### 2f. PSIMPEXCL (S8f, `025-record-filter-match-group.sql`, na V2)
- Legacy (r.2640-2686): vijf exacte opzoekingen op de verwerkte ARIMP-waarden (leverancier/groep/referentie in hoofdletters,
  kortingscode niet; `''` = letterlijk leeg). Rij `(l,g,k,r)` sluit uit als: `l≠''`, g=k=r=`''` → leverancier=l;
  `l=''`, k=r=`''` → groep=g; `l=''`, r=`''` → groep+korting; `l=''`, `r≠''` → groep+korting+referentie; l en r ≠ `''` → alle vier.
  Dode rijen: `l≠''`, `r=''` en (g of k ≠ `''`); rijen met kleine letters in l/g/r.
- Model (V2-A): `import_record_filter.match_group` int null; checks `match_group > 0` en
  `match_group is null or (outcome='EXCLUDE' and null_behaviour='COMPARE_AS_EMPTY')`. Rijen met dezelfde groep = EN-regel;
  geëvalueerd op laagste `sequence_number`; rijen zonder groep ongewijzigd (R-FLT-02).
- Vertaling: per legacy-rij één groep van 1–4 EQUALS-rijen op de identiteitskolommen (9/10/11) en de kolom van regel 23;
  lev/grp/ref `caseSensitive=false`, korting `true`; `trim=true`. Vaste delen (S6) bij migratie geëvalueerd. Alles waar →
  BLOCKING `LEGACY_EXCLUDE_EXCLUDES_ALL`; dode rij → `LEGACY_EXCLUDE_NEVER_MATCHES`; korting niet uitdrukbaar → WARNING
  `LEGACY_EXCLUDE_NOT_EXPRESSIBLE`; `Skip_Excludes=Y` → niet migreren, `LEGACY_EXCLUDES_SKIPPED`. `LEGACY_EXCLUDES_NOT_MIGRATED` verdwijnt.

## 3. Hashes, kopie, API, screening
- `RevisionConfigHashes` omvat geen mappings/filters: ongewijzigd.
- `LegacyRevisionContent`: per mapping achteraan `T,n,kind,config` (stappen) en `V,seq,bron,doel` (maprijen), per filter
  `group=n`, enkel als ze er zijn → byte-identiek voor bestaande revisies; `LegacyRevisionContentParityTest` uitbreiden.
- `CandidateNormaliser`-vingerafdrukken ongewijzigd voor revisies zonder nieuwe soorten.
- `RevisionCopier`: nieuwe `copyMappingChildren(saved, source)` na `saveAll`, aan te roepen door `LegacyConfigApplyService`
  (r.1394), `RevisionSuccessorService` (r.184), `TemplateMaterialisationService` (r.983); `match_group` in `copyFilters`.
- Setup-API additief: `valueMap[{source,target}]`, `steps[{kind,config}]`, filters `matchGroup`.
- Screening: configuratiefouten `CONFIG_TRANSFORM_INVALID`/`CONFIG_FILTER_INVALID` in stap B' (ook collect-modus);
  `ConfigFactoryParityTest` uitbreiden; inleesfouten `TRANSFORM_FAILED`/`MAPPING_VALUE_UNKNOWN`.

## 4. Bouwvolgorde (sequentieel, één commit per sub-slice)

Basis per sub-slice: `mvn -pl Service -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=FieldTransformTest,ConfigFactoryParityTest,LegacyRevisionContentParityTest,RevisionConfigHashesParityTest`
en `mvn -pl Web -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=LegacyConfigApplyTest,LegacyConfigPreviewTest`.
Steeds: tweemaal dezelfde APPLY → `DEFINITION_UNCHANGED`.

| Sub-slice | Lagen | Trap | Extra tests | Klaar als |
|---|---|---|---|---|
| S8a maptabel (**gebouwd 2026-10-21**) | Liquibase 023, alle lagen | bouwer-zwaar | `ImportFieldValueMapTest`; `run-full-tests.ps1 -Tests ScreeningSchemaTest,SetupApiFlowTest,DeliveryScreeningFlowTest` | 2000 sleutels migreren; `values=` werkt nog; kopie (opvolger, sjabloon, migratie) draagt rijen mee; pariteit byte-identiek |
| S8b UPPER/SUBSTRING | Domain, Service | bouwer-gemiddeld | — | CALC 15 en Positie/Lengte op mapping-regels gemigreerd |
| S8c keten + AFFIX | Liquibase 024, alle lagen | bouwer-zwaar | `SetupApiFlowTest`, `DeliveryScreeningFlowTest` | prefix+suffix+CALC 7 = legacy; lege invoer blijft leeg; grammaticafouten blokkeren in B' |
| S8d MAP_CONTAINS | Service | bouwer-gemiddeld | — | eerste treffer per KEY-volgorde, hoofdletterongevoelig |
| S8e Include/Exclude | Service | bouwer-gemiddeld | `RecordFilterEvaluatorTest` | codes 1–6; twee includes = EN |
| S8f PSIMPEXCL (na V2) | Liquibase 025, alle lagen | bouwer-zwaar | `RecordFilterEvaluatorTest`, `SetupApiFlowTest` | vijf vormen + dode rijen; `Skip_Excludes` |
| S8g onMiss (na V1) | Service, catalogus | bouwer-gemiddeld | — | volgens V1 |

## 5. Vragen voor de mens
- **V1 — Map-miss (blokkeert S8g).** Legacy CALC 7 laat de bronwaarde door (r.1054); CALC 14 geeft `''` (op een percentage stil 0,
  r.778); R-REC-08 verbiedt terugvallen. (A) altijd verwerpen; (B) opt-in `onMiss=KEEP_SOURCE` per MAP-mapping, migratie zet het
  enkel voor CALC 7, melding `MAPPING_VALUE_PASSED_THROUGH`, typecontrole verwerpt niet-numeriek (nooit stil 0), CALC 14 blijft
  verwerpen; (C) legacy volledig volgen. **Advies B** (gemelde aanpassing R-REC-08).
- **V2 — PSIMPEXCL-model (blokkeert S8f).** (A) `match_group` op `import_record_filter` (bronkolommen vóór identiteit, binnen
  R-FLT-02); (B) nieuwe uitsluitingstabel op de genormaliseerde identiteit (stadium `TARGET_FIELD`, botst met R-FLT-02).
  **Advies A.**

**Aannames:** elke gemigreerde regel met een transformatie wordt `CHAIN`; SUBSTRING voorbij het einde → `""`; CALC 7
hoofdlettergevoelig; varchar(50) blijft; codes 3/4 met padding-waarschuwing.

## 6. Ontdekkingen (voorstel om terug te schrijven, na akkoord)
- *Technisch:* `FieldValueMapper` (r.125-126) geeft de ongetrimde ruwe waarde aan de transformatie en trimt erna; `Affix` op `""`
  geeft `prefix+suffix`. Legacy trimt eerst en laat leeg leeg (r.730) → S2-gemigreerde PREFIX/SUFFIX/MAP wijken af bij opvulling
  en lege cellen. (→ `fase3-rules-design.md`)
- *Business:* Include/Exclude kent codes 1–6 op de MID van de kolom (lengte 0→100); elke regel sluit zelfstandig uit, dus meerdere
  includes = EN. (→ business-rules)
- *Business:* PSIMPEXCL werkt in vijf vaste vormen op de verwerkte identiteit (`Prg_1170` r.2640-2686); dode rijen;
  `Skip_Excludes=Y` schakelt uit. (→ business-rules)
- *Business:* CALC 14 zoekt enkel in de eigen NUMMER, hoofdletterongevoelig "bevat", eerste treffer in KEY-volgorde, miss → `''`. (→ business-rules)
- *Technisch:* `RevisionConfigHashes` omvat geen mappings en filters; twee revisies die enkel daarin verschillen dragen dezelfde
  samengestelde configuratiehash (bestond al vóór S8). (→ `fase3-rules-design.md`)
- *Technisch:* `RevisionCopier.copyMappings` levert niet-opgeslagen rijen; kindtabellen vragen een tweede kopieerstap bij alle drie aanroepers.
