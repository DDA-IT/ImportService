# Ontwerp Fase 3 — Business rules & validatie

Bron: denker-zwaar-ontwerp van 2026-09-19, geaccepteerd door de hoofdsessie. Bindend naast
`docs/decisions.md` en `docs/design/fase2-screening-design.md`. Leidend brondocument voor de
regels: `business-analyse-leveranciersbibliotheken.md` (§-verwijzingen hieronder). Foutcodes,
kolomnamen en tabelnamen zijn normatief.

## 0. Scope en afwijkingen t.o.v. het Fase 0-uitgangspunt

- A. Recordfilters horen WEL in Fase 3 (§14.4): het filter definieert de importscope waarop
  creatiedrempel, duplicaatcontrole en later volledigheid rekenen.
- B. Prijsobservatiehistoriek (`catalog_price_observation`) hoort WEL in Fase 3 (§14.23.2): de
  afwijkingscontrole gebruikt 3 referenties (vorige, gem. 50, gem. 200 dagen). Boxplot blijft later.
- C. `TOO_MANY_ROW_ISSUES` verdwijnt als blokkeerreden (was een technische logginglimiet; strijdig
  met "één bulkincident i.p.v. 10.000 losse fouten", §14.12/§16.2). Vervangen door: cap op bewaarde
  voorbeeldrijen per foutcode (default 200, laagste regelnummers) + volledige aantallen in de
  issuegroep + blokkeren op geconfigureerde kwaliteitsdrempels.
- D. `import_batch.validation_result` als aparte statusas naast `status` (§14.11):
  `VALID | VALID_WITH_WARNINGS | REVIEW_REQUIRED | BLOCKING`.
- BEWUST NIET in Fase 3: boxplot, supplementen, uitzonderingsregels, sjablonen/bookmarks (schema
  blijft additief-klaar), bronprioriteit bij meerdere bronnen, MOGELIJK_VERWIJDERD/volledigheidsbewijs,
  statusmachine/bundel/approval (Fase 4), stamdatavalidatie tegen Prodis (Fase 5),
  ondersteunende matching (stap 4), patroonherkenning van bulk-ID-transformaties.

## 1. Regelinventaris (regel-ID → foutcode/ernst/domein → test)

Ernst: `CRITICAL/BLOCKING/ERROR/WARNING/INFO` (ERROR=FOUT, WARNING=WAARSCHUWING; superset van de
huidige twee waarden). Domein: `DELIVERY_SOURCE, STRUCTURE_DATASET, IDENTITY_REFERENCE,
MAPPING_VALIDATION, PRICE, COMPLETENESS_DELETE, PUBLICATION_TECHNICAL, AUTHORISATION_CONFIG`
(+ `SUPPLEMENT` gedeclareerd, ongebruikt). Niveau: `DELIVERY | STRUCTURE | RECORD`.
Test: G=geldig, O=ontbrekend, D=dubbel, X=ongeldig, B=grensgeval.

### Structuur
- R-STR-01 bestaande structuurcontroles krijgen ernst BLOCKING/domein STRUCTURE_DATASET/niveau
  STRUCTURE en worden ook als issuerij bewaard (G,X).
- R-STR-02 headerpositie: verschoven kolom `HEADER_FIELD_SHIFTED` (WARNING); andere headernaam op de
  verwachte positie van een identiteits-/prijsveld `HEADER_FIELD_SEMANTIC_CHANGE` (BLOCKING) (G,X,B).
- R-STR-03 extra onbekende kolom achteraan zonder verschuiving `HEADER_UNKNOWN_COLUMN` (WARNING).
- R-STR-04 definitievalidatie vóór lezen: mapping verwijst naar bestaand bronveld/positie, doelveld
  uniek per revisie, type/lengte compatibel, geen dubbele prijscomponent → `CONFIG_MAPPING_*`
  (BLOCKING, AUTHORISATION_CONFIG) (G,O,D,X).
- R-STR-05 veld niet tegelijk sterk identificerend en ondersteunend/zwak; minstens één sterke
  identiteitsregel → `CONFIG_IDENTITY_CLASS_CONFLICT`.
- R-STR-06 mapping voor een veld dat al door een revisiekolom bepaald wordt (leverancier, groep,
  referentie, kortingscode, basisprijs, omschrijving) → `CONFIG_FIELD_MAPPING_DUPLICATES_REVISION`.

### Recordfilters
- R-FLT-01 operatoren `EQUALS, NOT_EQUALS, BEGINS_WITH, ENDS_WITH, CONTAINS, NOT_CONTAINS` met
  hoofdlettergevoeligheid, trim en null-gedrag per filterrij (`RecordFilterEvaluator`, pure klasse).
- R-FLT-02 filter draait direct na parsen, vóór identiteit/prijs/referenties; uitgesloten record
  krijgt geen verdere controles.
- R-FLT-03 ontbrekende filterkolom: standaard BLOCKING voor de hele levering `FILTER_COLUMN_MISSING`
  (per filterrij configureerbaar naar EXCLUDE/REJECT); nooit stil "filter matcht niet".
- R-FLT-04 tellers `raw_record_count, filtered_out_count, error_before_filter_count,
  rejected_record_count, valid_record_count`; reconciliatie
  `raw = filtered_out + error_before_filter + rejected + valid`.

### Mapping, datatypes, waarden (ERROR, MAPPING_VALIDATION, RECORD)
- R-REC-01 artikelnummer/leveranciersnummer/groep/referentie/barcode/PIM/CAB zijn tekst (voorloopnullen,
  lengte, case blijven) `VALUE_TYPE_MISMATCH`.
- R-REC-02 verplicht veld leeg → `VALUE_MISSING`, nooit default.
- R-REC-03 default enkel bij werkelijk ontbrekende (null) waarde, niet bij expliciet lege; `VALUE_DEFAULT_APPLIED` (INFO).
- R-REC-04 decimalen via `ImportValueRules.decimal` (enige route): `PRICE_UNREADABLE`, `PRICE_SCALE_EXCEEDED`.
- R-REC-05 datum/tijd volgens verklaard formaat + tijdzone: `DATE_UNREADABLE`, `DATE_AMBIGUOUS`.
- R-REC-06 te lange waarde → rij verwerpen, nooit afkappen (`VALUE_TOO_LONG`, alle gemapte velden).
- R-REC-07 transformaties (sealed `FieldTransform`): vaste waarde, prefix/suffix, concat, split, map,
  +,-,×,÷, percentage; geen vrije expressie; `TRANSFORM_FAILED`, `TRANSFORM_DIVIDE_BY_ZERO`.
- R-REC-08 map-miss valt nooit terug op de oorspronkelijke waarde: `MAPPING_VALUE_UNKNOWN`.
- R-REC-09 canonicalisatie: enkel encodingnormalisatie, buitenste trim, onzichtbare control characters
  weg; geen case-folding, geen leading-zero-verwijdering.

### Identiteit en matching
- R-ID-01 3/4-delige identiteit per volledige importfile, null ≠ "" (bestaand).
- R-ID-02 stap 1: exacte aanbiedingsidentiteit → zelfde aanbieding (bestaand).
- R-ID-03 stap 2: geen aanbiedingsmatch → zoek binnen bibliotheek op EAN/PIM-ID/CAB-ID/
  `E_MARK+ARTICLE_REFERENCE`; precies één eenduidige match = ander aanbod voor hetzelfde artikel
  (nieuwe aanbieding volgens creatiebeleid; bestaande identiteit nooit vervangen) →
  `REFERENCE_LINK_PROPOSED` (INFO). Alleen voor kandidaten met classificatie NEW.
- R-ID-04 stap 3: meerdere/tegenstrijdige kritieke matches → `IDENTITY_REFERENCE_INCIDENT` kind
  `AMBIGUOUS` (CRITICAL); geen creatie/merge/update.
- R-ID-06 onvolledige identiteit → geen aanbieding (`IDENTITY_COMPONENT_EMPTY`).
- R-ID-07 duplicaat/collisie blijven BLOCKING (bestaand).
- R-ID-08 identiteitsklasse per veld (`STRONG/ARTICLE_REFERENCE/SUPPORTING/WEAK`) is configuratie;
  prijs/omschrijving nooit identiteitsbeslissend.

### Kritieke referenties (CRITICAL, IDENTITY_REFERENCE)
- R-REF-01 waarde genormaliseerd bewaard náást ruwe waarde; `000123`, `123`, `AB-123` blijven verschillend.
- R-REF-02 andere waarde dan actieve → `IDENTITY_REFERENCE_INCIDENT` kind `CHANGED`, nooit gewone update.
- R-REF-03 gemapt maar leeg terwijl er een actieve waarde is → kind `REMOVED`; veld niet gemapt = geen uitspraak.
- R-REF-04 hergebruik voor andere aanbieding in dezelfde bibliotheek → kind `REUSED`.
- R-REF-05 bijkomende waarde die koppeling dubbelzinnig maakt → kind `AMBIGUOUS`.
- R-REF-06 eerste vastlegging enkel na normalisatie + uniciteit in bibliotheek + niet al aan ander artikel.
- R-REF-07 kritieke referenties blokkeren ongeacht volume; bulk = één `BULK_IDENTITY_INCIDENT` (CRITICAL),
  individuele audit blijft. Ook `DUPLICATE_REFERENCE_IN_DELIVERY` (CRITICAL).
- R-REF-08 eigenaarschap `CRITICAL_REFERENCE` niet wisselbaar: `CONFIG_OWNER_NOT_CHANGEABLE`.
- R-REF-09 record met kritiek incident wordt vastgehouden: zijn inhoudelijke mutatie krijgt status
  BLOCKED met reden `IDENTITY_REFERENCE_INCIDENT`; classificatie `IDENTITY_INCIDENT`.

### Prijs (financieel; ERROR tenzij anders, PRICE, RECORD)
- R-PRI-01 exact één basisprijsprofiel per definitie (`CONFIG_PRICE_FIELD_MISSING`, bestaand).
- R-PRI-02 basisprijs 0 is issue tenzij `zero_allowed`: `PRICE_ZERO_NOT_ALLOWED`; nul/ontbrekend/leeg
  zijn drie verschillende toestanden.
- R-PRI-03 negatief enkel bij `negative_allowed`: `PRICE_NEGATIVE_NOT_ALLOWED`.
- R-PRI-04 `pct = andere_prijs × 100 / basisprijs`, DECIMAL schaal 12, HALF_UP, nooit float.
- R-PRI-05 basisprijs nul/ontbrekend/onleesbaar → géén percentage, status `NO_BASE_PRICE`,
  `PRICE_PERCENTAGE_NOT_COMPUTABLE`.
- R-PRI-06 componenten dezelfde valuta/prijsbasis: `PRICE_CURRENCY_MISMATCH`.
- R-PRI-07 reconstructie `afronden(basis × pct / 100, 2) = bronprijs op 2 decimalen` binnen
  `price_derivation_tolerance` (default 0.01): `PRICE_DERIVATION_MISMATCH`; nooit stille correctie.
- R-PRI-08 geen kunstmatige bovengrens op verhouding; enkel geconfigureerde grenzen:
  `PRICE_PERCENTAGE_OUT_OF_RANGE`.
- R-PRI-09 percentagewijziging bij gelijke basisprijs (en omgekeerd) zichtbaar: elke component eigen
  voor/na-waarde en deelvingerafdruk; `domain_mask` bevat `PRICE:<COMPONENT>`.
- R-PRI-10 afwijkingscontrole (model 1) tegen 3 referenties: vorige geldige waarde, gemiddelde laatste
  50, gemiddelde laatste 200 goedgekeurde dagwaarden; één gezamenlijke grens per revisie, default 15%;
  `PRICE_DEVIATION_EXCEEDED` (WARNING; per revisie ERROR).
- R-PRI-11 `afwijking% = (nieuw − referentie)/referentie × 100`, schaal 12; ontbrekende/nul referentie →
  geen berekening, status `PREVIOUS/AVG50/AVG200_NOT_AVAILABLE`, `PRICE_REFERENCE_NOT_AVAILABLE` (INFO).
- R-PRI-12 anomalie wijzigt nooit prijs of percentage; bewaart oude waarde, nieuwe waarde, afwijking
  per referentie, ingestelde grens en context.
- R-PRI-13 maximaal één goedgekeurde waarde per kalenderdag per identiteit+component; enkel goedgekeurde
  waarden (Fase 3: bij accept-baseline); kandidaatprijs nooit in historiek; append-only (eerste
  waarde van de dag blijft, `insert ... where not exists`).
- R-PRI-14 gelijksoortige prijsafwijkingen binnen één import → één `BULK_PRICE_INCIDENT` (BLOCKING),
  gegroepeerd op koppeling+levering+component+richting+patroon.
- R-PRI-15 afronding pas op doelgrens (2 decimalen HALF_UP); intern schaal 6 (bedragen) / 12 (percentages).

### Drempels en eindoordeel
- R-THR-01 creatiedrempel: automatisch zolang ≤ 100 nieuwe aanbiedingen EN ≤ 1% van de bestaande
  importscope (NIET 100%: het oude requirementsdocument is fout); overschrijding →
  `BULK_CREATION_INCIDENT` (BLOCKING), alle creaties wachten op goedkeuring.
- R-THR-02 importscope = aantal actieve `catalog_source_state`-rijen van deze `import_link`; scope 0 =
  initialisatie: `INITIAL_LOAD_REQUIRES_APPROVAL` (BLOCKING), alle creaties vereisen goedkeuring.
- R-THR-03 1%-vergelijking in decimale rekenkunde: `new × 100 > share_pct × scope`.
- R-THR-04 groepering vanaf 10 gelijke foutsignaturen; bulkincident vanaf 100 records of 1% van de
  scope; kritieke referentiefouten blokkeren ongeacht volume.
- R-THR-05 per revisie: `max_critical_records` (default 0 ⇒ levering BLOCKED), `max_rejected_records`
  en `max_rejected_share_percent` (default null = niet geconfigureerd);
  `CRITICAL_RECORD_THRESHOLD_EXCEEDED`, `REJECTED_RECORD_THRESHOLD_EXCEEDED`. Drempels maken een
  onbetrouwbaar record nooit geldig.
- R-THR-06 `validation_result`: BLOCKING bij ≥1 CRITICAL/BLOCKING issue; REVIEW_REQUIRED bij ≥1
  bulkincident of wachtende creatie; VALID_WITH_WARNINGS bij ≥1 WARNING; anders VALID. Los van `status`.
- R-THR-07 per-regel-verwerping blokkeert enkel die regel; contract-/structuurfout en overschreden
  drempel blokkeren de levering.
- R-THR-08 marker draagt naast `outcome` ook `validationResult`, incidentaantallen, drempelresultaten.

### Issuemodel
- R-ISS-01 elk issue: stabiele foutcode, ernst, domein, niveau, impactscope, behandelstatus,
  batch/bestand/regel, veld, bron-/verwachte waarde, regelversie, eerste/laatste detectie, aantal.
- R-ISS-02 ernst en domein gedenormaliseerd op de issuerij (historische stabiliteit).
- R-ISS-03 per foutcode max N (default 200) voorbeeldrijen (laagste regelnummers); volledig aantal in
  de groep; `ROW_ISSUE_RECORDING_CAPPED` (INFO). Nooit een miljoen identieke foutregels.
- R-ISS-04 behandelstatus gedeclareerd (`DETECTED, AUTO_RESOLVED, AWAITING_REVIEW, CORRECTED,
  ACCEPTED_FOR_BATCH, ACCEPTED_BY_RULE, REJECTED, EXPIRED_EXCEPTION, REOPENED`); Fase 3 zet enkel DETECTED.
- R-ISS-05 impactscope `RECORD | DELIVERY | DEFINITION | LIBRARY`.
- R-ISS-06 elke gebruikte code bestaat in `ImportIssueCatalog` (Service/support; Java-bron van waarheid,
  geen DB-tabel) met ernst/domein/niveau/defaultImpactScope/acceptable; test scant alle codeconstanten.
- Meldingsstijl (§15.12): `<logische veldnaam>: '<bronwaarde>' <wat er mis is>`; prijsafwijking toont
  referentie, nieuwe waarde, afwijking % en ingestelde grens. Veldnaam = logische naam uit
  `import_field_catalog`.

## 2. Datamodel — changeset `004-import-rules-core.sql` (additief; 001-003 ongewijzigd; sub-changesets met rollback)

004-1 `import_field_catalog` (seed-referentiedata): `code varchar(60) pk`, `name varchar(200) not null`,
`data_type varchar(20) not null`, `default_owner varchar(30) not null`, `identity_class varchar(30) not null`,
`price_component_code varchar(20)`, `reference_type varchar(30)`, `owner_changeable boolean not null default true`,
`target_route varchar(200)`, `sort_order int not null`, `active boolean not null default true`;
check `reference_type is null or (default_owner='CRITICAL_REFERENCE' and owner_changeable=false)`.
Seed: `BASE_PRICE, AKP_PCT, VKP1_PCT..VKP5_PCT, VKP_GROSS_PCT, EAN, PIM_ID, CAB_ID,
E_MARK_ARTICLE_REFERENCE, E_SUPPLIER, SUPPLIER_BARCODE, DESCRIPTION, BRAND, UNIT` (BRAND/UNIT eigenaar
PRODIS_USER, niet schrijfbaar).

004-2 `import_field_mapping`: `id`, `definition_revision_id fk not null`, `sequence_number int not null`,
`target_field_code varchar(60) not null fk→catalog`, `value_kind varchar(20) not null`
(`SOURCE_FIELD|FIXED_VALUE|BOOKMARK|DERIVED`), `source_reference varchar(200)` (headernaam of
1-gebaseerde kolomindex, zoals `identity_*_field`), `expected_position int`, `fixed_value varchar(500)`,
`bookmark_name varchar(60)`, `default_value varchar(500)`, `data_type varchar(20) not null`,
`required boolean not null default false`, `max_length int`, `decimal_scale int`,
`zero_allowed boolean not null default false`, `negative_allowed boolean not null default false`,
`transform_kind varchar(30) not null default 'NONE'`, `transform_config varchar(1000)`,
`field_owner varchar(30) not null`, `identity_class varchar(30) not null`, `price_component_code varchar(20)`,
`reference_type varchar(30)`, `active boolean not null default true`, `created_at`, `created_by`.
Constraints: unique `(definition_revision_id, target_field_code)`, unique `(definition_revision_id,
sequence_number)`; checks: SOURCE_FIELD ⇒ source_reference not null; FIXED_VALUE ⇒ fixed_value not null;
BOOKMARK ⇒ bookmark_name not null; `price_component_code is null or field_owner='PRICE_CONTROL'`;
`reference_type is null or field_owner='CRITICAL_REFERENCE'`. Sjabloon-/bookmark-garantie: `value_kind=
'BOOKMARK'` + `bookmark_name` bestaan al; later enkel extra tabellen (`import_definition_bookmark`,
`import_link_bookmark_value`), `import_field_mapping` blijft ongewijzigd.

**Aanvulling (2026-09-23, domeinmodelontwerp sjabloon+bookmarks, changeset 006):** de garantie klopte
voor `import_field_mapping`, maar niet voor `import_record_filter` hieronder — zie de opmerking bij
004-3. Een gematerialiseerde `FIELD_MAPPING_FIXED_VALUE`-bookmark wordt weggeschreven als `value_kind=
'FIXED_VALUE'` + `fixed_value='<waarde>'`, met de herkomst enkel traceerbaar via
`import_definition_bookmark_value`/`import_link_bookmark_value` — `value_kind='BOOKMARK'` verschijnt
zelf nooit in een actieve revisie; de bestaande blokkades die daarop rekenen
(`ImportMappingConfigFactory`, `FieldValueMapper`) blijven dus correct.

004-3 `import_record_filter`: `id`, `definition_revision_id fk`, `sequence_number int`, `filter_stage
varchar(20) not null default 'SOURCE_FIELD'`, `source_reference varchar(200) not null`, `operator
varchar(20) not null`, `compare_value varchar(500) not null`, `outcome varchar(20) not null`
(`INCLUDE|EXCLUDE|REJECT`), `case_sensitive boolean not null default false`, `trim_before_compare
boolean not null default true`, `null_behaviour varchar(20) not null default 'EXCLUDE'`,
`missing_column_behaviour varchar(20) not null default 'BLOCK'`; unique `(definition_revision_id, sequence_number)`.

**Aanvulling (2026-09-23):** `compare_value` is `not null` en heeft geen `value_kind`/`bookmark_name`-
kolom zoals `import_field_mapping`. Een indirectie via bookmark (bv. het BA1 §14.19-voorbeeld
`culture = [Bookmark: CULTUUR]`) kan hier dus niet als losse verwijzing bestaan zonder deze tabel te
wijzigen. Gevolg: voor recordfilters is materialisatie (de bookmarkwaarde wordt bij materialisatie
letterlijk in `compare_value` geschreven) niet één van twee opties maar de enige optie die zonder
wijziging aan een bestaande tabel werkt — dit bevestigt de DEFINITION-scope-keuze in het
sjabloon+bookmarks-ontwerp (`docs/decisions.md` 2026-09-23). De herkomst van een gematerialiseerde
filterwaarde is dan alleen via `import_definition_bookmark_value` traceerbaar, niet vanaf de filterrij
zelf.

**Important technical constraint discovered:** het bookmarkvoorbeeld `BESTANDS_PREFIX` (bestandsnaam-
selectie) heeft in het huidige model geen configuratieplaats om toe te passen — bestandsselectie hoort
bij een Leveringsconfiguratie-entiteit (`ConnectionProfile`/`DeliveryConfiguration`, BA1 §14.17) die
niet bestaat (geen entiteit, geen tabel, geen endpoint). Beslissing 2026-09-23: deze bookmarkplaats
(`DELIVERY_FILE_SELECTION`) wordt voorlopig weggelaten uit de witte lijst van toegelaten
configuratieplaatsen, tot Leveringsconfiguratie gebouwd is.

004-4 `import_issue_group`: `id`, `batch_id fk not null`, `issue_code varchar(60) not null`, `signature
varchar(300) not null`, `severity varchar(20) not null`, `issue_domain varchar(40) not null`,
`control_level varchar(20) not null`, `impact_scope varchar(20) not null`, `incident_kind varchar(30) not
null default 'GENERIC'` (`GENERIC|PRICE|IDENTITY|CREATION`), `occurrence_count bigint not null`,
`recorded_sample_count int not null`, `scope_record_count bigint`, `share_percent numeric(24,12)`,
`is_bulk_incident boolean not null default false`, `price_component_code varchar(20)`,
`deviation_direction varchar(10)`, `dominant_factor numeric(24,12)`, `reference_type varchar(30)`,
`pattern_description varchar(500)`, `first_row_number bigint`, `first_detected_at`, `last_detected_at`,
`handling_status varchar(30) not null default 'DETECTED'`; unique `(batch_id, issue_code, signature)`;
index `(batch_id, severity)`.

004-5 `import_candidate_price` (JDBC-only): pk `(batch_id, row_number, component_code)` fk→stage;
`source_amount numeric(24,6)`, `percentage numeric(24,12)`, `currency varchar(3)`, `status varchar(30) not
null` (`OK|NO_BASE_PRICE|UNREADABLE|MISMATCH|...`); index `(batch_id, component_code)`; check:
`component_code='BASE_PRICE' ⇒ percentage is null and source_amount is not null`; anders percentage
gevuld of status<>'OK'.

004-6 `catalog_source_state_price`: pk `(source_state_id fk, component_code)`, `amount numeric(24,6)`,
`percentage numeric(24,12)`, `currency varchar(3)`, `updated_at`; zelfde check.

004-7 `catalog_price_observation`: `id`, `import_link_id fk`, `identity_hash ${hash.type}`, `component_code
varchar(20)`, `observation_date date not null`, `amount numeric(24,6)`, `percentage numeric(24,12)`,
`currency varchar(3)`, `source_state_id fk`, `batch_id fk`, `accepted_by varchar(100)`, `created_at`;
unique `(import_link_id, identity_hash, component_code, observation_date)`; index
`(import_link_id, identity_hash, component_code, observation_date desc)`. Append-only.

004-8 `catalog_reference_state`: `id`, `library_code varchar(20) not null`, `reference_type varchar(30) not
null`, `value_normalised varchar(200) not null`, `value_raw varchar(200) not null`, `source_state_id fk not
null`, `import_link_id fk not null`, `active_marker boolean` (TRUE zolang actief, anders NULL),
`accepted_by/accepted_at/created_at/updated_at`; unique `(library_code, reference_type, value_normalised,
active_marker)`; index `(source_state_id, reference_type)`.

004-9 `import_candidate_reference` (JDBC-only): pk `(batch_id, row_number, reference_type)` fk→stage;
`value_raw varchar(200)`, `value_normalised varchar(200)`, `is_empty boolean not null`, `match_result
varchar(30)` (`NEW|SAME|CHANGED|REUSED|AMBIGUOUS|REMOVED`), `matched_source_state_id bigint`; index
`(batch_id, reference_type, value_normalised)`.

004-10 `import_definition_revision` + `price_deviation_percent numeric(24,12) not null default 15`,
`price_deviation_severity varchar(20) not null default 'WARNING'`, `price_derivation_tolerance numeric(24,6)
not null default 0.01`, `price_avg_short_window int not null default 50`, `price_avg_long_window int not
null default 200`, `price_control_model varchar(20) not null default 'DEVIATION'` (`BOXPLOT` gedeclareerd,
niet ondersteund), `creation_threshold_absolute int not null default 100`,
`creation_threshold_share_percent numeric(24,12) not null default 1`, `max_critical_records int not null
default 0`, `max_rejected_records int`, `max_rejected_share_percent numeric(24,12)`,
`record_currency_field varchar(200)`.

004-11 `import_batch` + `validation_result varchar(30)`, `filtered_out_count bigint`,
`error_before_filter_count bigint`, `identity_incident_count bigint`, `critical_issue_count bigint`,
`warning_count bigint`, `bulk_incident_count bigint`, `awaiting_approval_count bigint`,
`classify_progress_row_number bigint not null default 0`, `price_progress_row_number bigint not null
default 0`, `reference_progress_row_number bigint not null default 0`, plus (4-ogen, zie decisions.md)
`baseline_approved_by varchar(100)`. Tellers nullable (null = onbekend). `GET /batches/{id}` krijgt
additieve velden.

004-12 `import_row_issue`: `row_number` en `delivery_file_id` van NOT NULL naar NULL; + `issue_domain
varchar(40) not null default 'MAPPING_VALIDATION'`, `control_level varchar(20) not null default 'RECORD'`,
`impact_scope varchar(20) not null default 'RECORD'`, `issue_group_id fk null`, `handling_status
varchar(30) not null default 'DETECTED'`, `rule_config_version int`, `expected_value varchar(200)`,
`occurrence_seq int`; index `(batch_id, severity)`, `(issue_group_id)`. Contractwijziging melden:
`ImportRowIssue.rowNumber` wordt `Long`; `BatchQueryService.IssueRow.rowNumber` nullable →
`GET /batches/{id}/issues` kan `rowNumber:null` teruggeven; `severity` kan `CRITICAL/BLOCKING/INFO` zijn.
Gekozen: UITBREIDEN, niet vervangen of hernoemen (superset, geen datamigratie, Fase 2-contracten blijven);
javadoc van `ImportRowIssue` vermeldt dat de tabel alle drie de niveaus draagt.

004-13 `import_mutation` + `reference_type varchar(30)`, `before_reference_value varchar(200)`,
`after_reference_value varchar(200)`, `issue_group_id bigint null`; `ck_import_mutation_marker` droppen en
heruitgeven met `IDENTITY_REFERENCE_INCIDENT` in de toegelaten action_type-set. `MutationActionType` +
`IDENTITY_REFERENCE_INCIDENT`; mutatielijst-endpoint krijgt 3 extra velden (additief).

004-14 `catalog_source_state` + `reference_fingerprint ${hash.type}` (nullable).

## 3. Regelengine

### 3.1 Flow (batchstatussen ongewijzigd: RECEIVED→SCREENING→MUTATING→SCREENED|BLOCKED|FAILED)
```
A  archiveren                        ongewijzigd
B  registratie                       ongewijzigd
B' config laden + valideren          NIEUW: mappings, filters, drempels, prijsprofiel;
                                     config-fout blokkeert vóór één byte gelezen is
C  streaming + staging (microbatch)  per record in-memory, GEEN db-query per regel:
     parse -> FILTER -> mapping/transformatie -> recordvalidatie -> identiteit+hash
           -> prijscomponenten+percentages -> referenties genormaliseerd
     schrijft stage + _price + _reference + issues
D  set-based identiteitscontroles    hashcollisie -> duplicaat (bestaand)
D1 NIEUW duplicate kritieke referentie binnen de levering
E1 classificatiepass (chunk)         classify_progress_row_number
E2 referentiecontrolepass (chunk)    reference_progress_row_number
E3 prijscontrolepass (chunk)         price_progress_row_number
E4 aggregatiepass (eenmalig)         issuegroepen -> bulkincidenten -> drempels
E5 mutatiegeneratiepass (chunk)      mutation_progress_row_number (bestaand)
F  afronden                          tellers, validation_result, marker, eindstatus
```
Classificatie en mutatie-insert (in Fase 2 één chunktransactie in `generateMutations`) worden
gesplitst: drempels bepalen de mutatiestatus en moeten volledig berekend zijn vóór E5. Elke pass
heeft een eigen voortgangskolom, is afzonderlijk hervatbaar en idempotent (`update ... where
classification is null`, `insert ... where not exists`).

### 3.2 Set-based vs per-record
Per record in-memory tijdens streaming: filter, mapping, transformatie, typevalidatie, identiteit,
percentages, referentienormalisatie (configuratie één keer per batch geladen). Set-based: duplicaten,
hashcollisie, duplicate referentie, classificatie, referentiecontrole (join stage-reference ×
`catalog_reference_state` op `(library_code, reference_type, value_normalised)`), prijsafwijking (join
`import_candidate_price` × `catalog_source_state_price` + venstersubquery
`row_number() over (partition by import_link_id, identity_hash, component_code order by observation_date
desc)` voor AVG50/AVG200), groepering (`insert into import_issue_group ... group by issue_code,
signature having count(*) >= 10` + update `issue_group_id`), drempels (drie `count(*)`).

### 3.3 Hiërarchie
Niveau 1/2-fout ⇒ recordfase start niet of stopt; alle bestaande `ScreeningBlockedException`-paden
behouden gedrag en schrijven nu ook één issuerij (`control_level=STRUCTURE|DELIVERY`,
`impact_scope=DELIVERY`, BLOCKING). Config-fout (B') ⇒ niveau 2, blokkeert vóór parsen. Recordissues
krijgen `control_level=RECORD`.

### 3.4 Rekenregels (hard)
```
bedragen        BigDecimal, numeric(24,6), nooit float/double
percentages     BigDecimal, numeric(24,12)
percentage      other.multiply(100).divide(base, 12, HALF_UP)
reconstructie   base.multiply(pct).divide(100, 2, HALF_UP) == source.setScale(2, HALF_UP)
                binnen price_derivation_tolerance, anders PRICE_DERIVATION_MISMATCH
afwijking%      new.subtract(ref).multiply(100).divide(ref, 12, HALF_UP)
gemiddelden     cast(avg(amount) as numeric(24,6)) over de laatste N dagwaarden (nooit float-aggregatie)
afronding       enkel op doelgrens (2 decimalen HALF_UP)
deling door nul basisprijs 0/ontbrekend => NO_BASE_PRICE, geen percentage
1%-drempel      new_count * 100 > share_pct * scope_count
```
Elke afwijking toont oude waarde, nieuwe waarde, afwijking per referentie en ingestelde grens; niets
wordt stil gecorrigeerd of op nul gezet.

### 3.5 Canonicalisatieversie 2
De prijsvingerafdruk moet alle prijscomponenten dekken (§14.23.2 controle 5). `SUPPORTED_CANONICALISATION_VERSION`
wordt een verzameling {1, 2}; `CandidateNormaliser` behoudt het v1-pad byte-identiek. v2:
`price_fingerprint = SHA-256(canonical(2, basePrice, currency, [component_code, percentage]* gesorteerd
op component_code))`; `article_fingerprint` bevat alle niet-prijs-, niet-referentievelden met eigenaar
CATALOG_SOURCE gesorteerd op `target_field_code`; nieuw `reference_fingerprint` over de referenties;
`combined_fingerprint = SHA-256(identity ‖ article ‖ price ‖ reference)`. Een revisie met prijscomponent-
of referentiemappings MOET v2 declareren (`CONFIG_CANONICALISATION_VERSION_REQUIRED`, BLOCKING). Geen
herbaselining voor bestaande v1-revisies (die hebben geen extra mappings).

### 3.6 Eindstatus
| Situatie | status | validation_result | mutaties |
|---|---|---|---|
| alles geldig | SCREENED | VALID | CREATE/UPDATE PLANNED |
| enkel waarschuwingen | SCREENED | VALID_WITH_WARNINGS | PLANNED |
| creatiedrempel/bulk(prijs)incident | SCREENED | REVIEW_REQUIRED | betrokken → AWAITING_APPROVAL |
| ≥1 kritiek referentie-incident binnen `max_critical_records` | SCREENED | BLOCKING | incident → AWAITING_APPROVAL; inhoudelijke mutatie van dat record → BLOCKED |
| `max_critical_records` overschreden (default 0) | BLOCKED | BLOCKING | 0 inhoudelijke mutaties, 1 marker |
| structuur-/contractfout, duplicaat, collisie, drempel | BLOCKED | BLOCKING | 0 inhoudelijke mutaties, 1 marker |
| technische fout tijdens staging | FAILED | null | geen marker, geen mutaties |
Marker blijft exact één per afgeronde screening, in dezelfde transactie als de eindtransitie.

## 4. Foutcode-catalogus
Bestaande Fase 2-codes behouden en krijgen classificatie:
- BLOCKING/AUTHORISATION_CONFIG/STRUCTURE: `CONFIG_FORMAT_UNSUPPORTED, CONFIG_CHARSET_UNKNOWN,
  CONFIG_DELIMITER_MISSING, CONFIG_DELIMITER_INVALID, CONFIG_QUOTE_INVALID, CONFIG_HEADER_LINE_INVALID,
  CONFIG_FIELD_REFERENCE_KIND_INVALID, CONFIG_HEADER_REFERENCE_INCONSISTENT,
  CONFIG_IDENTITY_FIELD_MISSING, CONFIG_PRICE_FIELD_MISSING, CONFIG_COLUMN_COUNT_INVALID,
  CONFIG_FIELD_REFERENCE_INVALID, CONFIG_CANONICALISATION_VERSION_UNSUPPORTED,
  CONFIG_DISCOUNT_FIELD_MISSING, CONFIG_FIELD_NOT_RESOLVED`.
- BLOCKING/STRUCTURE_DATASET: `CONFIG_COLUMN_INDEX_OUT_OF_RANGE, SOURCE_FILE_EMPTY, HEADER_LINE_MISSING,
  HEADER_FIELD_MISSING:<veld>, HEADER_DUPLICATE_FIELD, HEADER_COLUMN_COUNT_MISMATCH, SOURCE_NO_DATA_RECORDS`.
- WARNING: `SOURCE_BOM_REMOVED`. ERROR/STRUCTURE_DATASET/RECORD: `ROW_COLUMN_COUNT_MISMATCH, ROW_TOO_LONG,
  CSV_UNCLOSED_QUOTE`. ERROR/MAPPING_VALIDATION: `VALUE_MISSING, VALUE_TOO_LONG,
  CANONICAL_CONTROL_CHARACTER`. ERROR/IDENTITY_REFERENCE: `IDENTITY_COMPONENT_EMPTY`. ERROR/PRICE:
  `PRICE_MISSING, PRICE_UNREADABLE, PRICE_SCALE_EXCEEDED, PRICE_OUT_OF_RANGE`.
- BLOCKING/IDENTITY_REFERENCE/DELIVERY: `DUPLICATE_IDENTITY_IN_DELIVERY, IDENTITY_HASH_COLLISION`.
  BLOCKING/DELIVERY_SOURCE/DELIVERY: `BYTE_SIZE_MISMATCH, RECORD_COUNT_MISMATCH`.
  BLOCKING/PUBLICATION_TECHNICAL/DELIVERY: `SCREENING_FAILED, SCREENING_INTERRUPTED`.
- `TOO_MANY_ROW_ISSUES` vervalt als blokkeercode → `ROW_ISSUE_RECORDING_CAPPED` (INFO).
- HTTP-/conflictcodes (geen issue) blijven ongewijzigd.
Nieuwe codes Fase 3: zie §1 (CONFIG_MAPPING_TARGET_UNKNOWN, CONFIG_MAPPING_SOURCE_UNRESOLVED,
CONFIG_MAPPING_DUPLICATE_TARGET, CONFIG_FIELD_MAPPING_DUPLICATES_REVISION, CONFIG_IDENTITY_CLASS_CONFLICT,
CONFIG_OWNER_NOT_CHANGEABLE, CONFIG_TRANSFORM_INVALID, CONFIG_FILTER_INVALID,
CONFIG_CANONICALISATION_VERSION_REQUIRED, CONFIG_PRICE_COMPONENT_DUPLICATE (BLOCKING/AUTHORISATION_CONFIG);
HEADER_FIELD_SHIFTED, HEADER_UNKNOWN_COLUMN (WARNING), HEADER_FIELD_SEMANTIC_CHANGE, FILTER_COLUMN_MISSING
(BLOCKING/STRUCTURE_DATASET); VALUE_TYPE_MISMATCH, DATE_UNREADABLE, DATE_AMBIGUOUS, TRANSFORM_FAILED,
TRANSFORM_DIVIDE_BY_ZERO, MAPPING_VALUE_UNKNOWN (ERROR/MAPPING_VALIDATION); VALUE_DEFAULT_APPLIED (INFO);
PRICE_* (§1); IDENTITY_REFERENCE_INCIDENT, DUPLICATE_REFERENCE_IN_DELIVERY, BULK_IDENTITY_INCIDENT
(CRITICAL); REFERENCE_LINK_PROPOSED (INFO); BULK_PRICE_INCIDENT, BULK_CREATION_INCIDENT,
INITIAL_LOAD_REQUIRES_APPROVAL, CRITICAL_RECORD_THRESHOLD_EXCEEDED, REJECTED_RECORD_THRESHOLD_EXCEEDED
(BLOCKING); ROW_ISSUE_RECORDING_CAPPED (INFO)).

## 5. Testplan (`mvn -pl Web -am test`, alle tests in `Web/src/test`, unieke codes per test)
`ImportIssueCatalogTest` (alle codeconstanten in catalogus); `RecordFilterEvaluatorTest` (unit);
`FieldValueMapperTest` (unit); `PriceRulesTest` (unit; schaal 12, nul/negatief, reconstructie exact op
tolerantiegrens, valuta); `PriceDeviationTest` (flow; exact 15,000000000000% vs 15,000000000001%,
referentie 0, ontbrekende historiek, venster 50/200, twee accepts dezelfde dag → één observatie);
`ReferenceIncidentTest` (flow; CHANGED/REMOVED/REUSED/AMBIGUOUS, duplicaat in levering, `000123` vs
`123`, EAN-match zonder aanbiedingsmatch → NEW + REFERENCE_LINK_PROPOSED); `CreationThresholdTest`
(scope 10.000 + 100 nieuw toegelaten, 101 = incident, >1% = incident, lege bronstaat =
INITIAL_LOAD_REQUIRES_APPROVAL); `IssueGroupingTest` (9/10/100, 1% van scope, >200 gelijke fouten =
200 voorbeeldrijen + correcte occurrence_count); `ValidationResultTest`; `ControlHierarchyTest`
(header-veld ontbreekt → 1 structuurissue, 0 recordissues); `MappingConfigValidationTest`;
`ScreeningCounterReconciliationTest` (`raw = filtered_out + error_before_filter + rejected + valid`,
`valid = new + changed + unchanged + duplicate + identity_incident`); uitbreidingen op
`ScreeningSchemaTest` (alle nieuwe constraints) en `DeliveryScreeningFlowTest` (regressie Fase 2 met
aangepaste verwachting voor initialisatie en vervallen TOO_MANY_ROW_ISSUES). Buiten scope (mens):
PostgreSQL en performance; risicoqueries: venstersubquery op `catalog_price_observation`,
`import_candidate_price` (tot 7 rijen per record), referentiejoin op 200-tekens-tekst,
`classifyDuplicates`, groepering over `import_row_issue`.

## 6. Bouwstappen (strikt sequentieel, één commit per stap; alle detecties 3a-3f vóór aggregaties 3g-3h)
- 3a bouwer-zwaar: fouttaxonomie + issuemodel (004-4, 004-12, 004-11-basis), enums (`RowIssueSeverity`
  uitbreiden, `IssueDomain`, `ControlLevel`, `ImpactScope`, `IssueHandlingStatus`), `ImportIssueCatalog`,
  `ImportRowIssue`/`RowIssueDao`/`BatchQueryService` aanpassen, elke bestaande blokkade schrijft een
  issuerij, `validation_result`-kolom.
- 3b bouwer-zwaar: veldcatalogus + mapping + filters (004-1/2/3/10), seed, `ImportMappingConfig(+Factory)`,
  `RecordFilterEvaluator`, filterstap in staging-sink, vijf tellers.
- 3c bouwer-zwaar: recordvalidatie per mapping (`FieldValueMapper`, `FieldTransform` sealed,
  datum/decimaal/tekst, defaults, lengtes, `CandidateNormaliser` naar gemapte velden, `article_fingerprint` v2).
- 3d bouwer-zwaar (financieel): prijscomponenten (004-5/6), `price_fingerprint` v2 + canonicalisatieversie 2,
  `PriceRules`, `CandidatePriceDao`, `SourceStateDao`/`SourceStateBaselineService` uitbreiden,
  `domain_mask` per component. DoD: percentagewijziging bij gelijke basisprijs = exact één UPDATE met
  `domain_mask=PRICE:AKP`; v1-revisies onveranderd.
- 3e bouwer-zwaar (financieel): prijshistoriek + afwijkingscontrole (004-7), `PriceObservationDao`
  (append-only bij accept-baseline), `PriceDeviationDao`, meldingsstijl §15.12.
- 3f bouwer-zwaar (§6: identiteit): kritieke referenties (004-8/9/13/14), `CandidateReferenceDao`,
  `ReferenceControlDao`, incident-mutaties, matchingstap 2/3, classificatie IDENTITY_INCIDENT,
  accept-baseline naar `catalog_reference_state`.
- 3g bouwer-zwaar: groepering + bulkincidenten (`IssueGroupDao`, signaturen, voorbeeldcap,
  `ROW_ISSUE_RECORDING_CAPPED`, afschaffing `TOO_MANY_ROW_ISSUES`).
- 3h bouwer-zwaar (meerdere lagen + statusflow): drempels + eindoordeel (`ThresholdEvaluator`,
  creatiedrempel, `validation_result`, mutatiestatussen AWAITING_APPROVAL/BLOCKED, markerinhoud, passopsplitsing
  E1-E5, hervattingstests, en het vier-ogen-veld `approvedBy` op accept-baseline, zie decisions.md).
Rapportage aan de mens na 3d (fase 3.1) en na 3h (fase 3.2).

## 7. Aannames (A14-A22)
A14 `import_row_issue` wordt uitgebreid (naam blijft). A15 canonicalisatieversie 2 naast 1. A16
prijsobservaties append-only (eerste waarde van de dag blijft). A17 `max_critical_records` default 0.
A18 `max_rejected_*` geen default (null). A19 bestaande revisiekolommen blijven autoritair, mapping dekt
overige velden. A20 ondersteunende matching en bulk-ID-patroonherkenning niet in Fase 3. A21 record met
kritiek incident wordt vastgehouden (mutatie BLOCKED). A22 valuta syntactisch (ISO-4217-vorm) en intern
consistent gevalideerd; stamdata in Fase 5.

## 8. Documentatie-impact
`docs/requirements/catalog-import-business-rules.md` is achterhaald en r.28 aantoonbaar fout (100% i.p.v.
1%); `-acceptance.md` niet bruikbaar als DoD; overige proefversiedocumenten markeren als ARCHIEF (niet
verwijderen). Voor Fase 7: acceptatiedocument herschrijven met per regel-ID uit §1 een criterium + testklasse.
`fase2-screening-design.md` §5/§11 worden door Fase 3 achterhaald (aanvulling §18 na Fase 3).

> Important business rule discovered
> De creatiedrempel is 100 nieuwe aanbiedingen EN 1% van de importscope (§16.1), niet 100%. De eerste
> levering van elke nieuwe koppeling (scope 0) is daarom altijd goedkeuringsplichtig: vanaf Fase 3 krijgen
> die CREATE-mutaties status AWAITING_APPROVAL i.p.v. PLANNED (wijziging t.o.v. Fase 2-gedrag).

> Important technical constraint discovered
> Classificatie en mutatie-insert moeten aparte passes worden (drempels bepalen de mutatiestatus).

## 9. Aanvullingen uit stap 3a (geïmplementeerd, hoofdsessie akkoord)

- Property `catalogimport.screening.max-recorded-row-issues` is hernoemd naar
  `catalogimport.screening.max-sample-rows-per-code` (default 200); de oude sleutel wordt genegeerd
  (melden in de release-/deploynotitie).
- `ImportIssueCatalog` (34 codes + dynamisch voorvoegsel `HEADER_FIELD_MISSING`) is de Java-bron van
  waarheid; `classify()` gooit een exception op een onbekende code. Niveau-invulling: `SOURCE_FILE_EMPTY`
  en `SOURCE_NO_DATA_RECORDS` ⇒ DELIVERY; alle `CONFIG_*` en headercodes ⇒ STRUCTURE.
- Elke blokkade schrijft één issuerij (geen dubbele bij hervatten). Bij duplicaten wordt geen
  `ROW_ISSUE_RECORDING_CAPPED` geschreven (totaal staat in `duplicate_identity_count` en `blocked_reason`;
  uniforme telling komt in 3g). FAILED schrijft geen issuerij (staging + issues worden bij FAILED opgeruimd).
- Changeset-nummering: 004-11 bevat enkel `validation_result`; de overige `import_batch`-kolommen
  (tellers, voortgangskolommen E1-E3, `baseline_approved_by`) komen in 3b-3h onder een eigen id
  `004-11b` (uitgevoerde changesets mogen niet wijzigen).
- `GET /batches/{id}/issues` sorteert op rowNumber; NULL-volgorde verschilt tussen H2 (vooraan) en
  PostgreSQL (achteraan); nog niet gelijkgetrokken.

### Open punt vóór 3h (moet beslist zijn voordat 3h start)
R-THR-06 noemt CRITICAL/BLOCKING en WARNING maar niet ERROR: letterlijk geïmplementeerd krijgt een
levering met 500 verworpen regels `validation_result = VALID`. Voorstel: ERROR (verworpen records) ⇒
minstens `VALID_WITH_WARNINGS`, of expliciet overlaten aan `REJECTED_RECORD_THRESHOLD_EXCEEDED`.
Te laten beslissen door een Denker (of de mens) vóór 3h.

## 10. Aanvullingen uit stap 3b (geïmplementeerd, hoofdsessie akkoord)

- Changesets: 004-1 (+ 004-1b seed van 17 velden), 004-2, 004-3, 004-10, 004-11b (`filtered_out_count`,
  `error_before_filter_count`). `import_record_filter` heeft extra nullable `created_at/created_by`.
  Overige `import_batch`-kolommen volgen onder 004-11c/d in latere stappen.
- Filtercombinatiesemantiek: evaluatie op `sequence_number`; in scope = (geen INCLUDE-rijen OF ≥1 INCLUDE
  matcht) EN geen EXCLUDE matcht; matchende REJECT verwerpt met `FILTER_RECORD_REJECTED` (ERROR).
  `null_behaviour`: `EXCLUDE` (default), `REJECT`, `COMPARE_AS_EMPTY`.
- Tellers: met filters tellen structureel onleesbare records (`ROW_COLUMN_COUNT_MISMATCH`, `ROW_TOO_LONG`,
  `CSV_UNCLOSED_QUOTE`) in `error_before_filter_count`, niet in `rejected_record_count`; zonder filters
  gaat alles naar `rejected_record_count` (Fase 2-gedrag ongewijzigd).
- Extra codes: `CONFIG_MAPPING_TYPE_INCOMPATIBLE`, `FILTER_RECORD_REJECTED`. `IdentityClass.NONE` toegevoegd.
  Voorbehouden doelveldcodes (SUPPLIER, SUPPLIER_GROUP, SUPPLIER_REFERENCE, DISCOUNT_CODE) staan niet in de seed.
- Beperking R-STR-02/03: geldt enkel voor mapping- en filterkolommen (revisiekolommen dragen geen
  `expected_position`).
- Mappings worden in 3b gevalideerd maar nog niet toegepast (3c/3d). Prijscomponent- of referentiemappings
  blokkeren tot canonicalisatieversie 2 bestaat (`CONFIG_CANONICALISATION_VERSION_REQUIRED`).
  `BOXPLOT` en `FilterStage.TARGET_FIELD` zijn in het schema toegelaten maar worden geweigerd in de verwerking.
- `UploadResponse` is niet uitgebreid met de nieuwe tellers (enkel GET-batch, GET-delivery en continue).

## 11. Aanvullingen uit stap 3c (geïmplementeerd, hoofdsessie akkoord)

- ELKE actieve mapping vereist canonicalisatieversie 2 (niet enkel prijs-/referentiemappings): de v1-artikelhash
  dekt alleen de omschrijving, dus een gemapt veld zou wijzigingen onzichtbaar laten in de delta
  (`CONFIG_CANONICALISATION_VERSION_REQUIRED`). Prijs-/referentiemappings blijven tot 3d/3f geblokkeerd, ook
  mét v2, onder dezelfde code (tijdelijk; het v2-deel voor prijs/referentie bestaat pas dan).
- `transform_config` is een platte `sleutel=waarde;...`-lijst (`MappingSettings`), één keer per batch geparsed;
  onbekende sleutel blokkeert. Datumformaat/tijdzone: `dateFormat=`, `zone=` (STRICT: 31/02 ⇒ `DATE_UNREADABLE`).
  Decimaalnotatie: `decimalSeparator=`, `groupingSeparator=` + `decimal_scale`; zonder verklaring blijft het
  Fase 2-gedrag (komma én punt). BOOLEAN: `true/false/1/0`.
- v2-vingerafdruk: `article = canonical(2, DESCRIPTION, waarde, [code, waarde]* gesorteerd op target_field_code)`;
  `price` en `reference` volgens de v2-formules met (voorlopig) lege componenten-/referentielijst;
  `combined = SHA-256(identity ‖ article ‖ price ‖ reference)`. v1 is byte-identiek gebleven (vastgepind in tests).
  Kolommen `catalog_source_state.reference_fingerprint` (004-14) en `import_candidate_stage.reference_fingerprint`
  (004-14b), nullable: NULL = "onder v1 vastgelegd".
- `VALUE_DEFAULT_APPLIED` is voor CSV vrijwel onbereikbaar (elke gemapte kolom is minstens ""); pas relevant voor
  bronnen met optionele elementen (XML/JSON).
- INFO-issues lopen door dezelfde cap per foutcode als ERROR. Control characters worden niet verwijderd (dat zou
  data stil wijzigen); gedrag blijft buitenste trim + `CANONICAL_CONTROL_CHARACTER` op de gereserveerde stuurtekens.
- Gemapte waarden worden in 3c gevalideerd en gehasht maar niet per veld opgeslagen; `domain_mask=ARTICLE`
  zonder veldnamen tot 3d/3f. De v2-artikelhash bevat DESCRIPTION altijd (U+0000 als de revisiekolom leeg is).
- Constante hernoemd: `SourceStructureConfigFactory.SUPPORTED_CANONICALISATION_VERSION` (int) →
  `SUPPORTED_CANONICALISATION_VERSIONS` (Set<Integer>).

## 12. Aanvullingen uit stap 3d (geïmplementeerd, hoofdsessie akkoord)

- Changesets: 004-5 `import_candidate_price` (fk→stage met `on delete cascade`), 004-6
  `catalog_source_state_price` (strengere check: geen component zonder percentage in de aanvaarde bronstaat),
  004-10b (`base_price_zero_allowed`, `base_price_negative_allowed`, default false: de basisprijs heeft geen
  mapping-rij, dus deze schakelaars staan op de revisie).
- Gedragswijziging t.o.v. Fase 2: basisprijs 0 of negatief wordt nu verworpen tenzij de revisie het toelaat
  (`PRICE_ZERO_NOT_ALLOWED`, `PRICE_NEGATIVE_NOT_ALLOWED`). Bestaande koppelingen die 0-prijzen leveren moeten
  `base_price_zero_allowed` zetten (release-/deploynotitie).
- `domain_mask`-notatie: `ARTICLE`, `PRICE` (basisprijs of munt), `PRICE:<price_component_code uit de
  veldcatalogus>` (bv. `PRICE:AKP`), komma-gescheiden in vaste volgorde. Per-component voor/na is herleidbaar
  via `source_state_id` (voor: `catalog_source_state_price`) en `batch_id + source_row_number` (na:
  `import_candidate_price`); geen extra mutatiekolommen.
- Revisies zonder prijscomponenten schrijven geen enkele prijsrij (byte-neutraal; hashes vastgepind).
  Batch zonder prijsrijen raakt `catalog_source_state_price` niet aan.
- `NO_BASE_PRICE` bestaat in model/schema/`PriceRules` maar bereikt de staging nu niet (issue is ERROR ⇒ record
  verworpen); 3h kan dit anders regelen. Valuta: één munt per record uit `record_currency_field`
  (`[A-Z]{3}`, nooit stil geüppercased of EUR); een v1-revisie met `record_currency_field` blokkeert
  (`CONFIG_CANONICALISATION_VERSION_REQUIRED`).
- Prijscomponent-mapping vereist DECIMAL en `decimal_scale ≤ 6` (`CONFIG_MAPPING_TYPE_INCOMPATIBLE`);
  `maxPercentage=` als `transform_config`-sleutel (>0, alleen op prijscomponent). Prijscomponent-mappings zijn
  onder v2 toegestaan; referentie-mappings blijven geblokkeerd tot 3f.
- CHANGED-bronstaatprijzen worden per chunk vervangen (delete + insert); UNCHANGED blijft onaangeroerd.
- Publieke signatuurwijziging: `MutationDao.insertContentMutations(...)` kreeg `List<String> componentCodes`.
- RISICO: `import_mutation.domain_mask` is varchar(100); met de 7 geseede componenten is het langst mogelijke
  masker 94 tekens. `MutationDao.verifyMaskFits` faalt vooraf duidelijk (nooit afkappen). Kolom verbreden
  (aparte additieve changeset) in 3e/3h of vóór meer componenten worden geseed.

## 13. Aanvullingen uit stap 3e (geïmplementeerd, hoofdsessie akkoord)

- Changesets: 004-7 `catalog_price_observation`, 004-11c (`import_batch.price_progress_row_number`), 004-13b
  (`import_mutation.domain_mask` varchar(100) → varchar(200), standaard-SQL `alter column ... set data type`).
- Observaties worden uitsluitend bij `accept-baseline` geschreven, per aanvaarde NEW/CHANGED-rij en per component
  (ook `BASE_PRICE`, ook voor revisies zonder componenten), met `observation_date` = UTC-kalenderdag via een
  injecteerbare `Clock`; de eerste waarde van de dag blijft. UNCHANGED levert niets.
- Pass E3 (prijscontrole) draait na D en vóór de mutatiegeneratie, eigen voortgangskolom en property
  `catalogimport.screening.price-control-chunk-size` (5000). Afwijking wordt op BEDRAGEN berekend (niet op
  percentages) tegen 3 referenties; exact op de grens is niet overschreden. De controle wijzigt niets
  (mutatie blijft PLANNED, `rejected_record_count` onveranderd).
- `PRICE_DEVIATION_EXCEEDED` mag per revisie ERROR worden (`configurableSeverities`); `validation_result`
  reageert nog niet op ERROR (zie open punten). `PRICE_REFERENCE_NOT_AVAILABLE`: één samenvattend INFO-issue
  per levering. BOXPLOT blokkeert vóór het lezen (`CONFIG_PRICE_CONTROL_MODEL_UNSUPPORTED`).
- Bekend: de cap-melding van de prijspass kan na crash+continue een tweede deelmelding geven (uniforme telling
  komt met 3g). `import_row_issue.message` is varchar(500): de grens staat vooraan in de tekst.

### Open punten vóór 3h (moeten beslist zijn voordat 3h start)
1. `validation_result` en ERROR-issues (zie §9): BESLIST door de mens op 2026-09-20 (zie decisions.md): de
   gebruiker geeft per kolom aan of die kritiek is; een fout op een kritieke kolom (kritieke lijn) vereist een
   review, waarschuwingen niet. Nog uit te werken door een Denker vóór 3h (opslag van de kritiek-vlag per kolom,
   relatie tot `max_critical_records`, effect op `validation_result` en mutatiestatussen).
2. OPGELOST (mens, 2026-09-19, zie decisions.md): de 50-/200-gemiddelden zijn een extra referentie voor een
   bewegend gemiddelde; gemiddelde over de laatste N vastgelegde goedgekeurde observaties volstaat (geen
   doorgetrokken kalenderdagen). Huidige implementatie is dus correct.
3. Ruisrisico: één basisprijswijziging kan N+1 afwijkingsmeldingen geven (basis + elke component); mitigatie is
   het bulkprijsincident (3g). Groeipad/retentie van `catalog_price_observation` is nog niet belegd.

## 14. Aanvullingen uit stap 3f (geïmplementeerd, hoofdsessie akkoord)

- Changesets: 004-8 `catalog_reference_state`, 004-9 `import_candidate_reference` (cascade), 004-11d
  (`identity_incident_count`, `classify_progress_row_number`, `reference_progress_row_number`), 004-13
  (`reference_type`, `before/after_reference_value`, `issue_group_id`; `action_type` varchar(20)→(40);
  `ck_import_mutation_marker` heruitgegeven met drie soorten). Referentie-mappings actief onder v2 (onder v1
  geblokkeerd).
- Passopsplitsing gerealiseerd: C → D → D1 → E1 (classificatie) → E2 (referentiecontrole) → E3 (prijs) → E5
  (mutaties) → F, elk met eigen voortgangskolom en afzonderlijk hervatbaar. 3h voegt E4 (aggregatie/drempels) in.
- Normalisatie referenties (alle types): buitenste trim + verwijdering van onzichtbare tekens (Unicode Cc/Cf);
  géén hoofdletterconversie, géén leading-zero-verwijdering; ruwe waarde blijft ernaast bewaard.
  (Verschil met artikelvelden uit 3c, waar control characters gemeld i.p.v. verwijderd worden.)
- REUSED vs AMBIGUOUS = aanwijsbaarheid: AMBIGUOUS = referenties van één regel wijzen naar ≥2 verschillende
  bestaande aanbiedingen (of de aanbieding draagt zelf ≥2 actieve waarden voor één type); REUSED = precies één
  maar een andere aanbieding. Zwaarte: AMBIGUOUS > REUSED > CHANGED > REMOVED.
- E2 beoordeelt alleen NEW/CHANGED (nooit UNCHANGED). Eerste vastlegging van een referentie op een bestaande
  aanbieding is GEEN incident (R-REF-06). D1-rijen worden ook vastgehouden maar krijgen geen incident-mutatie.
- `idempotency_key` incident-mutatie: `<delivery>:<revisie>:<identity_hash_hex>:REFERENCE:<type>`.
  `MutationDao.countContentMutations`/`skipPlannedContentMutations` tellen/raken enkel CREATE/UPDATE.
- Eindstatus tussenstand: kritieke incidenten ⇒ batch SCREENED met `validation_result=BLOCKING` (3a-logica);
  drempels/REVIEW_REQUIRED/BLOCKED komen in 3h.
- Uniciteit van een referentie geldt per BIBLIOTHEEK (niet per koppeling). Nieuwe 409-code
  `REFERENCE_ALREADY_ACTIVE_FOR_OTHER_OFFER`; property `catalogimport.screening.reference-control-chunk-size`.

### Open punten (vóór 3h / Fase 4 te beslissen)
1. Tegenstrijdigheid: §14.23.3 zegt dat ook een NIEUWE referentie op een bestaand artikel standaard de kritieke
   route volgt; R-REF-06 laat de eerste vastlegging toe. Gevolgd: R-REF-06. Wil de business het strengere, dan is
   een per-revisie schakelaar "eerste vastlegging vereist goedkeuring" nodig, anders is het inschakelen van
   referentiemappings op een bestaande koppeling onuitvoerbaar.
2. Normalisatie van referenties is niet meer wijzigbaar zonder migratie zodra `catalog_reference_state` gevuld
   is: bevestigen vóór de eerste echte baseline.
3. Geen unieke constraint op `(source_state_id, reference_type, active_marker)`: schematisch kan een aanbieding
   twee actieve waarden voor één type hebben (evaluator behandelt dat als AMBIGUOUS).

## 15. Stap 3h — drempels, kritieke kolommen en eindoordeel (denker-zwaar 2026-09-20, met beslissingen van de mens)

Dit hoofdstuk VERVANGT R-THR-01..08 en de eindstatustabel §3.6, en past 3g (E4a) en 3h aan. Bindende beslissingen
in `decisions.md` van 2026-09-20: kritiek-per-kolom (review), drempels altijd percentage, vier-ogen nooit,
eerste referentievastlegging = optie A zonder schakelaar.

### 15.1 Kritiek-vlag per kolom
- `import_field_mapping.criticality varchar(20) not null default 'NON_CRITICAL'` (enum `Criticality {CRITICAL,
  NON_CRITICAL}`; checks: waarde in set; `reference_type is null or criticality='CRITICAL'`). Backfill:
  referentie- en prijscomponentmappings ⇒ CRITICAL. Changeset `004-2b`.
- Revisie-eigen velden (geen mapping-rij): nieuwe tabel `import_revision_field_criticality`
  (`definition_revision_id fk`, `field_key varchar(60)` in `SUPPLIER,SUPPLIER_GROUP,SUPPLIER_REFERENCE,
  DISCOUNT_CODE,BASE_PRICE,CURRENCY,DESCRIPTION`, `criticality`, `created_at/by`; pk `(revision, field_key)`;
  check: identiteitsvelden nooit NON_CRITICAL). Changeset `004-15`. Ontbrekende rij = standaard van de soort:
  identiteit CRITICAL (niet instelbaar), BASE_PRICE/CURRENCY CRITICAL (instelbaar), DESCRIPTION NON_CRITICAL,
  prijscomponent CRITICAL (instelbaar), referentie CRITICAL (niet instelbaar), overige gemapte velden NON_CRITICAL.
- **Kritieke lijn** = een verworpen bronregel in scope met ≥1 issue van ernst ERROR op een kritieke kolom, of niet
  aan een kolom toewijsbaar (`ROW_COLUMN_COUNT_MISMATCH`, `ROW_TOO_LONG`, `CSV_UNCLOSED_QUOTE`,
  `IDENTITY_COMPONENT_EMPTY` ⇒ onherleidbaar = kritiek). Nooit kritiek: WARNING/INFO, `FILTER_RECORD_REJECTED`
  (beheerder verklaarde REJECT), `PRICE_DEVIATION_EXCEEDED` (verwerpt niets). Een kritieke lijn staat nooit in de
  stage en heeft dus geen mutatie; de review gebeurt op leveringsniveau. Strikt disjunct met vastgehouden
  identiteitsincidenten (3f).
- Telling in `DeliveryScreeningService.addIssue(...)`: `import_batch.critical_line_count`, ontdubbeld op
  regelnummer, NIET gecapt (nooit uit de bewaarde voorbeeldrijen). Koppeling issue→kolom via
  `ImportMappingConfig.criticalityOf(fieldName)` (map van bronreferenties én logische veldnamen, onbekend ⇒
  CRITICAL, botsing ⇒ strengste). Nieuwe code `CONFIG_FIELD_CRITICALITY_INVALID` (BLOCKING/AUTHORISATION_CONFIG/STRUCTURE).

### 15.2 Drempels (altijd percentage; vervangt R-THR-01/03/05)
Per revisie: `creation_threshold_share_percent` (default 1), `max_critical_share_percent` (nieuw, default 1),
`max_rejected_share_percent` (default null), `bulk_incident_share_percent` (nieuw, default 1); de absolute kolommen
(`creation_threshold_absolute`, `max_critical_records`, `max_rejected_records`) blijven maar worden niet meer
gebruikt (changeset `004-10c`: additieve nieuwe kolommen; geen backfill nodig). Alle vergelijkingen decimaal:
`aantal × 100 > percentage × scope`; exact op de grens is NIET overschreden; nooit float.
- Scope voor kritiek/verworpen/bulk = records in scope van de levering = `raw_record_count − filtered_out_count`;
  scope voor de creatiedrempel = actieve `catalog_source_state`-rijen van de `import_link`.
- `critical_record_count = critical_line_count + identity_incident_count`.
  `>0` en niet boven `max_critical_share_percent` ⇒ SCREENED + `REVIEW_REQUIRED`; boven ⇒ BLOCKED +
  `CRITICAL_RECORD_THRESHOLD_EXCEEDED`. `max_rejected_share_percent` (indien niet null) boven ⇒ BLOCKED +
  `REJECTED_RECORD_THRESHOLD_EXCEEDED`.
- Creatie: `creationCandidates = NEW + IDENTITY_INCIDENT-rijen zonder bronstaat`. scope 0 en kandidaten>0 ⇒
  INITIAL_LOAD (`INITIAL_LOAD_REQUIRES_APPROVAL`, alleen deze code, nooit ook `BULK_CREATION_INCIDENT`);
  kandidaten 0 ⇒ AUTOMATIC; `kandidaten × 100 > creation_threshold_share_percent × scope` ⇒ THRESHOLD_EXCEEDED
  (`BULK_CREATION_INCIDENT`); anders AUTOMATIC. Persisteren vóór E5: `import_batch.creation_outcome`
  (`AUTOMATIC|INITIAL_LOAD|THRESHOLD_EXCEEDED`) + `creation_scope_count`, zodat een hervatte E5 identiek is.
  Gevolg voor kleine koppelingen (1% van 10 = 0,1): per leverancier het percentage hoger zetten.
- Bulkincident (3g): issuegroep vanaf 10 gelijke signaturen (technisch); bulk = `occurrence_count × 100 >
  bulk_incident_share_percent × scope_record_count` (geen vast aantal meer).

### 15.3 `validation_result`, eindstatus, mutatiestatussen (vervangt R-THR-06/07 en §3.6)
`validation_result` wordt niet meer uit de ernst afgeleid maar uit `DeliveryEffect {NONE, REVIEW, BLOCK}` per
foutcode in `ImportIssueCatalog.IssueClassification` (ernst blijft ongewijzigd). BLOCK: alle `CONFIG_*`, header-/
structuurcodes, `SOURCE_FILE_EMPTY`, `SOURCE_NO_DATA_RECORDS`, `BYTE_SIZE_MISMATCH`, `RECORD_COUNT_MISMATCH`,
`DUPLICATE_IDENTITY_IN_DELIVERY`, `IDENTITY_HASH_COLLISION`, `FILTER_COLUMN_MISSING`, `SCREENING_FAILED`,
`SCREENING_INTERRUPTED`, `CRITICAL_RECORD_THRESHOLD_EXCEEDED`, `REJECTED_RECORD_THRESHOLD_EXCEEDED`,
`CONFIG_FIELD_CRITICALITY_INVALID`. REVIEW: `IDENTITY_REFERENCE_INCIDENT`, `DUPLICATE_REFERENCE_IN_DELIVERY`,
`BULK_IDENTITY_INCIDENT`, `BULK_PRICE_INCIDENT`, `BULK_CREATION_INCIDENT`, `INITIAL_LOAD_REQUIRES_APPROVAL`
(BLOCKING/DELIVERY_SOURCE/DELIVERY). Al het overige NONE. Elke code moet een expliciet `DeliveryEffect` hebben
(test). `RowIssueSeverity.isBlockingForBatch()` blijft bestaan maar bepaalt `validation_result` niet meer.

    validation_result = BLOCKING            als blocked of ≥1 issue met effect BLOCK
                      = REVIEW_REQUIRED     anders, als critical_record_count>0 of ≥1 issue met effect REVIEW
                                            of awaiting_approval_count>0
                      = VALID_WITH_WARNINGS anders, als ≥1 issue met ernst ERROR of WARNING
                      = VALID               anders

Tellers ongecapt (uit `import_issue_group.occurrence_count` + niet-gegroepeerde rijen; vereist dat 3g op elke
bewaarde voorbeeldrij die bij een groep hoort `issue_group_id` invult — de 3h-bouwer verifieert dit eerst en meldt
afwijking): `critical_line_count`, `identity_incident_count`, `critical_issue_count`, `warning_count`,
`bulk_incident_count`, `awaiting_approval_count` (enkel CREATE/UPDATE in AWAITING_APPROVAL).
Eindstatus: verworpen regels alleen op niet-kritieke kolommen binnen drempels ⇒ SCREENED/VALID_WITH_WARNINGS;
kritieke lijn of kritiek referentie-incident binnen drempel ⇒ SCREENED/REVIEW_REQUIRED (vervangt de 3f-tussenstand
BLOCKING); initialisatie ⇒ alle CREATE `AWAITING_APPROVAL` (reden `INITIAL_LOAD_REQUIRES_APPROVAL`); creatiedrempel
⇒ alle CREATE `AWAITING_APPROVAL` (reden `BULK_CREATION_INCIDENT`), UPDATE blijft PLANNED; `BULK_PRICE_INCIDENT` ⇒
elke PLANNED UPDATE met `PRICE` in `domain_mask` ⇒ AWAITING_APPROVAL (over-inclusief, bewust: de precieze set is
door de cap niet uit issues te halen); drempel overschreden of structuur-/contract-/configfout/duplicaat/collisie ⇒
BLOCKED + BLOCKING, 0 inhoudelijke mutaties, 1 marker; technische stagingfout ⇒ FAILED, `validation_result` null.
Precedentie: BLOCKED > AWAITING_APPROVAL > PLANNED; initialisatie wint van creatiedrempel; drempels in E4b vóór
E5; een geblokkeerde batch behoudt zijn incidentmutaties (AWAITING_APPROVAL) als bewijs.

### 15.4 Passvolgorde, marker, accept-baseline (geen vier-ogen)
`C → D → D1 → E1 → E2 → E3 → E4a (3g: groepering/bulk) → E4b (3h: drempels, aparte methode `evaluateThresholds`) →
E5 → E5b (bulkprijs ⇒ AWAITING_APPROVAL) → F`. E4b is set-based, geen voortgangskolom, idempotent (issue enkel
als de code er nog niet is; tellers overschrijven, nooit optellen; alles uit de db herberekend).
Marker `result_summary` (varchar(1000)) met vaste sleutelvolgorde: `outcome`, `completenessProven`,
`completenessReason`, `fileSha256`, `validationResult`, `criticalRecords=<n>/<pct>`, `criticalLines`,
`identityIncidents`, `rejected=<n>/<pct of ->`, `warnings`, `criticalIssues`, `bulkIncidents`, `awaitingApproval`,
`creationScope`, `creationCandidates`, `creationThreshold=<pct>`, `creationOutcome`; `-` = niet geconfigureerd
(nooit 0); lengte-test < 1000.
`accept-baseline` (GEEN `approvedBy`, GEEN 409 `FOUR_EYES_APPROVAL_REQUIRED`): enkel `acceptedBy` + `reason` zoals
nu; `skipPlannedContentMutations` wordt `skipOpenContentMutations` en zet `CREATE/UPDATE` in `PLANNED` én
`AWAITING_APPROVAL` op `SKIPPED`; `BLOCKED` (identiteitsincident) en `IDENTITY_REFERENCE_INCIDENT`-mutaties
blijven onaangeroerd; bronstaat en referenties voor vastgehouden rijen nooit geschreven (3f).
Impact op bestaand gedrag/tests: eerste levering geeft N `AWAITING_APPROVAL`-CREATEs + `REVIEW_REQUIRED`;
kritiek referentie-incident ⇒ `REVIEW_REQUIRED` i.p.v. BLOCKING; te wijzigen tests: `DeliveryScreeningFlowTest`,
`BatchBaselineHttpTest`, `ValidationResultTest`, `ReferenceIncidentTest`, `PriceDeviationTest`,
`PriceComponentScreeningFlowTest`, `MappedFieldScreeningFlowTest`, `ScreeningSchemaTest`,
`ScreeningCounterReconciliationTest`, `ImportIssueCatalogTest`.

### 15.5 Changesets 3h (additief aan `004-import-rules-core.sql`; uitgevoerde nooit wijzigen)
`004-2b` (criticality), `004-15` (revisie-veld-kritiek), `004-10c` (`bulk_incident_share_percent`, gebouwd door 3g) en `004-10d` (`max_critical_share_percent`, 3h-4), `004-11e` (`import_batch`: `critical_line_count`,
`critical_issue_count`, `warning_count`, `awaiting_approval_count`, `creation_scope_count`, `creation_outcome`
+ check; `bulk_incident_count` alleen als 3g die niet al toevoegde — eerst de changelog lezen), `004-8b`
(`unique (source_state_id, reference_type, active_marker)` op `catalog_reference_state`; NULL-historiek botst niet).
Foutcodes nieuw: `CRITICAL_RECORD_THRESHOLD_EXCEEDED`, `REJECTED_RECORD_THRESHOLD_EXCEEDED`,
`INITIAL_LOAD_REQUIRES_APPROVAL`, `CONFIG_FIELD_CRITICALITY_INVALID`.

### 15.6 Bouwstappen 3h (strikt sequentieel; één commit per stap; rapportage aan de mens na 3h-7)
- 3h-1 bouwer-gemiddeld: 004-2b + 004-15, `Criticality`, mapping/config, `criticalityOf`,
  `CONFIG_FIELD_CRITICALITY_INVALID`; geen gedragswijziging.
- 3h-2 bouwer-gemiddeld: `critical_line_count` in de staging (+ deel 004-11e), `FieldCriticalityTest`,
  `CriticalLineCountTest`.
- 3h-3 bouwer-zwaar: E4b creatiedrempel/initialisatie (`creation_outcome`), E5 `AWAITING_APPROVAL`, Fase 2-tests
  aanpassen, `CreationThresholdTest` (scope 10.000 + 1% grens: 100 = niet overschreden, 101 = wel; scope 0 =
  initialisatie).
- 3h-4 bouwer-zwaar: percentagedrempels critical/rejected (004-10c), blokkadepaden, `ThresholdBlockingTest`.
- 3h-5 bouwer-zwaar: `DeliveryEffect`, nieuwe `validation_result`-evaluator, ongecapte tellers, bulkprijs ⇒
  AWAITING_APPROVAL, markerinhoud; `ValidationResultTest`.
- 3h-6 bouwer-gemiddeld: `skipOpenContentMutations`, accept-baseline zonder vier-ogen; tests.
- 3h-7 bouwer-licht: 004-8b unieke constraint + test.
(3h-8 "eerste vastlegging vereist goedkeuring" vervalt: besluit optie A zonder schakelaar.)
Testplan per regel (G/O/D/X/B): kritiek-vlag (standaarden, db-checks), kritieke lijn (onherleidbaar telt, dubbele
ERROR op één regel = 1, `FILTER_RECORD_REJECTED` niet, 250 bij cap 200 ⇒ teller 250), drempels (exact op grens niet
overschreden, `null`-drempel nooit overschreden), creatie (scope 0, 0 kandidaten, exact 1%), `validation_result`
volledige tabel (kritieke lijn + waarschuwing ⇒ REVIEW_REQUIRED), marker (lengte, `-`), hervatting E4b.
Buiten scope (mens): PostgreSQL en performance; risicoquery `domain_mask like '%PRICE%'` binnen één batch.

> Important business rule discovered
> Met percentage-only drempels (1%) kan een kleine koppeling nooit automatisch creëren (1% van 10 = 0,1);
> per leverancier het percentage verhogen. Vier-ogen wordt bewust nergens afgedwongen (herroeping 2026-09-20).

## 16. Aanvullingen uit stap 3g (geïmplementeerd, hoofdsessie akkoord)

- Pass E4 (`IssueAggregationService`) draait tussen E3 en E5 en ook op het blokkeerpad: koppelt issuerijen aan hun
  groep, hertelt de voorbeelden, past de drempels toe, schrijft bulkmeldingen en precies één
  `ROW_ISSUE_RECORDING_CAPPED` per batch+foutcode (idempotent via `signature = 'CODE=<foutcode>'`; de passen
  schrijven die melding niet meer zelf).
- `import_issue_group.occurrence_count` = het WERKELIJKE totaal (elke detectiepass telt elk voorval per foutsignatuur
  op in dezelfde transactie als haar issuerijen); `recorded_sample_count` = herteld uit de gekoppelde rijen. Elke
  bewaarde voorbeeldrij die bij een groep hoort draagt `issue_group_id` (invariant voor 3h: `occurrence_count` +
  aantal niet-gegroepeerde rijen per foutcode = werkelijk totaal; getest).
- Bulkincident uitsluitend via percentage: `occurrence × 100 > bulk_incident_share_percent × scope_record_count`;
  exact op de grens niet overschreden. Groep vanaf 10 gelijke signaturen (technische drempel, vast aantal).
  Zonder bruikbare scope (onbekend of 0): geen bulk, wel groep met het werkelijke aantal.
- Scope: GENERIC = `raw_record_count − filtered_out_count`; PRICE = aantal uitgevoerde prijsvergelijkingen;
  IDENTITY = `count(distinct row_number)` in `import_candidate_reference`.
- Signaturen (kolom `import_row_issue.signature`, 004-12b): GENERIC `FIELD=<logisch veld>`; PRICE
  `COMPONENT=<code>|DIRECTION=UP|DOWN` (richting = eerste overschreden referentie); IDENTITY
  `TYPE=<referentietype>|KIND=CHANGED|REMOVED|REUSED|AMBIGUOUS|DUPLICATE`. `dominant_factor`/`pattern_description`
  blijven NULL (A20).
- `BULK_PRICE_INCIDENT` (BLOCKING/PRICE/DELIVERY) en `BULK_IDENTITY_INCIDENT` (CRITICAL/IDENTITY_REFERENCE/DELIVERY)
  komen NAAST de individuele meldingen (R-REF-07); GENERIC-groepen boven de grens krijgen enkel
  `is_bulk_incident=true`. Groepen onder de drempel worden verwijderd, behalve als voorbeeldrijen weggelaten zijn
  (`occurrence_count > recorded_sample_count`). Duplicate identiteiten krijgen nu ook groep + cap-melding.
- Changesets: 004-12b (`signature` + index), 004-11e-import-batch-bulk-incident-count (`bulk_incident_count`),
  004-10c (`bulk_incident_share_percent numeric(24,12) not null default 1`). Nieuw endpoint
  `GET /batches/{id}/issue-groups`; `issueGroupId` op `/issues` (+ filter); `bulkIncidentCount` op `/batches/{id}`.
- Tussenstand voor 3h: `BULK_PRICE_INCIDENT` (BLOCKING) en `BULK_IDENTITY_INCIDENT` (CRITICAL) laten via de 3a-logica
  `validation_result=BLOCKING` ontstaan (batch blijft SCREENED); 3h-5 vervangt dit door `DeliveryEffect` (REVIEW).
  `import_mutation.issue_group_id` wordt nog niet gevuld. Bekend latent volgordeprobleem in
  `ReferenceIncidentTest.acceptBaselineWritesTheReferenceState...` (telt `catalog_reference_state` over alle
  bibliotheken, gedeelde H2).

## 17. Aanvullingen uit stap 3h-1 t/m 3h-7 (geïmplementeerd, hoofdsessie akkoord)

- **Kritiek-vlag (3h-1):** `import_field_mapping.criticality` + `import_revision_field_criticality`; naamruimte voor
  `ImportMappingConfig.criticalityOf`: bronreferentie voor revisie-eigen velden, `targetFieldName()` voor gemapte
  velden; onbekend/`null` ⇒ CRITICAL; botsing ⇒ strengste. `ImportFieldMapping.criticality` is intern nullable en
  leidt de standaard af (referentie of prijscomponent ⇒ CRITICAL). `CONFIG_FIELD_CRITICALITY_INVALID`.
- **Kritieke lijnen (3h-2):** `import_batch.critical_line_count` (004-11e2), ontdubbeld op regelnummer, niet gecapt.
  Met recordfilters telt een structureel onleesbare regel wel als kritieke lijn maar niet in `rejected_record_count`
  (die gaat naar `error_before_filter_count`).
- **Creatiebeleid (3h-3):** `creation_outcome` (`AUTOMATIC|INITIAL_LOAD|THRESHOLD_EXCEEDED`), `creation_scope_count`
  (004-11e3). Kandidaten = NEW plus IDENTITY_INCIDENT-rijen zonder bronstaatrij. In E5 telt "creatie" ook een
  CHANGED-regel waarvan de bronstaatrij verdween. `MutationDao.insertContentMutations(..., creationStatusReason)`.
- **Drempels (3h-4):** `max_critical_share_percent` (004-10d, default 1) en `max_rejected_share_percent` (default
  null). `ThresholdEvaluator`: `NOT_APPLICABLE|UNDETERMINED|WITHIN|EXCEEDED`; onbekende tellers ⇒ geen oordeel; scope 0
  met records ter beoordeling ⇒ fail-safe EXCEEDED. Blokkade via `block(...)`: BLOCKED, 0 inhoudelijke mutaties, 1
  marker; E2-incidentmutaties blijven `AWAITING_APPROVAL`. Vaste aantallen (`max_critical_records`,
  `max_rejected_records`, `creation_threshold_absolute`) zijn `@Deprecated` en niet meer in gebruik.
- **Eindoordeel (3h-5):** `DeliveryEffect` per foutcode + `ValidationResultEvaluator` (beslissingstabel §15.3).
  Passvolgorde definitief: E4 → drempels (kan blokkeren) → creatiebeleid (enkel bij doorgaan) → E5 → E5b
  (`holdPlannedPriceUpdates`) → F. Een geblokkeerde levering heeft geen `creation_outcome`/creatiemelding.
  Tellers `critical_issue_count`, `warning_count`, `awaiting_approval_count`, `creation_candidate_count` (004-11e4),
  ongecapt en op de GEPERSISTEERDE ernst. 3g-invariant aangescherpt: een bulkmeldingsrij (`BULK_PRICE_INCIDENT`/
  `BULK_IDENTITY_INCIDENT`) hangt aan de groep van de onderliggende foutcode en telt apart mee (anders stille 0 op een
  kritieke vaststelling). `new_count`/`changed_count`/`unchanged_count` blijven `null` op een geblokkeerde batch.
  Marker met vaste sleutelvolgorde (bestaande sleutels vooraan ongewijzigd), lengte < 1000.
- **accept-baseline (3h-6):** `skipPlannedContentMutations` ⇒ `skipOpenContentMutations`; alleen `acceptedBy` +
  `reason`; extra veld `approvedBy` wordt genegeerd (Spring-default); één bevoegde persoon kan een review afronden.
  `AcceptBaselineReviewFlowTest`.
- **Referentie-uniciteit (3h-7):** `uk_catalog_reference_state_offer_active` op `(source_state_id, reference_type,
  active_marker)` (004-8b). 3h-8 vervalt (optie A zonder schakelaar).
- **Bekende beperkingen:** `domain_mask like '%PRICE%'` scan binnen één batch; "≥1 issue met effect X" steunt op de
  voorbeeldcap (per code minstens één rij); `import_mutation.issue_group_id` wordt nog niet gevuld; alleen H2 getest.
