# G2 — Loskoppelen, single-flight voor Prodis-rechten, bewerken van de nieuwe revisievelden

> **Status: VOORSTEL (2026-10-22), denker-zwaar.** Volgt op `docs/decisions.md` "2026-10-22 — Bouwopdracht". G2b en G2c kunnen gebouwd
> worden; **G2a wacht op V1/V2** (§4). Volgorde: G2b-1 → G2c-1..4 → G2a.

## G2a — Loskoppelen van een gedeelde definitie

**Feiten:** al het werk hangt aan de koppeling, niet aan de definitie (`catalog_source_state` uniek `(import_link_id, identity_hash)`
002 r.178; `catalog_price_observation` 004 r.506; `catalog_reference_state` 004 r.594; `issue_case` 012 r.56; `publication_bundle_batch`
005 r.88); een batch bevriest zijn `definition_revision_id` (002 r.61). De taak draagt de leveringsconfiguratie (014 r.257); de credential
hangt aan 020-2. `import_link.active`/`catalog_import_task.active` worden nergens gezet of gehandhaafd. Geen API wijzigt de definitie van een
koppeling (`LegacyConfigApplyService` r.514-531, r.1172-1196). 020-3 laat per leverancier geen tweede definitie met dezelfde hash toe.

**Aanbevolen ontwerp**
1. Trigger: APPLY-optie `detachFromShared=true` (+ optioneel `detachedDefinitionCode`) op een RECEIVED-ontvangst; ook als preview-query-param.
2. Bestaande koppeling **verleggen** (V1 = A) naar een eigen kopie; koppelings- en taakcode, leveringsconfiguratie, credential, bronstaat,
   prijshistoriek en behandelgevallen blijven.
3. Eigen kopie: `OWN_DEFINITION`, code standaard `LEGACY-<interface>` (botsing blokkeert, override mogelijk), beschrijving
   "<gedeelde beschrijving> (losgekoppeld voor <interface>)", `based_on_definition_id` = de gedeelde definitie (niet het sjabloon).
4. Revisies (V2 = A): rev 1 = `RevisionCopier`-kopie van de ACTIVE revisie van de gedeelde definitie, geactiveerd via
   `SetupService.activateRevision` in dezelfde transactie; daarna S2-regels: rev 2 = opvolgende DRAFT met de nieuwe legacy-inhoud. Geen ACTIVE
   revisie → rev 1 = DRAFT met de geplande inhoud. Een open DRAFT van de gedeelde definitie blijft bij de groep.
5. `MANUAL_CHANGES_SINCE_MIGRATION` en `OPEN_DRAFT_EXISTS` geëvalueerd op de gedeelde definitie; rijslot op de koppeling in execute.
6. Herkomst: 020-2 ongewijzigd (+ `last_applied_receipt_id`); 020-3 groepsrij ongewijzigd; nieuwe 020-3-rij voor de kopie met
   `detached_interface_code`, `template_definition_id = null`; 020-4 erft voor rev 1, nieuwe rij voor rev 2.
7. `sharedWith`: losgekoppelde interfaces vallen weg bij de groep; op de kopie `[]` + item MIGRATED `DETACHED_DEFINITION`; `findGroup` zoekt enkel groepsrijen.
8. Nieuwe interface die DIVERGED is: met de optie krijgt ze een eigen definitie (zonder verleggen).
9. Opnieuw aansluiten: niet in G2 (datamodel laat `REATTACH` later toe).

**Datamodel (Liquibase 026, additief):**
- 026-1 `legacy_config_definition_origin`: `detached_interface_code` varchar(20) null, `group_marker` boolean (TRUE = groep, NULL = losgekoppeld),
  check `(group_marker is true) = (detached_interface_code is null)`; `uk_legacy_config_definition_origin` wordt
  `(env, dossier, supplier_code, definition_content_hash, group_marker)`; nieuw `uk_…_detached (env, dossier, detached_interface_code)`.
  Gemelde constraintwijziging (020-3 nergens uitgerold).
- 026-2 `legacy_config_detachment` (append-only audit): id, `origin_id`, `import_link_id`, `from_definition_id`, `to_definition_id`,
  `receipt_id` null, `action` (DETACH; later REATTACH) + check, `reason` varchar(500), `at/by/by_subject` (koppelcheck zoals 007-4).

**Rapport/foutcodes:** `planned.detach{fromDefinitionId, fromDefinitionCode, toDefinitionCode, copiedRevisionId, carriesActive}`;
`planned.link.action=REPOINT`. Items: `DETACH_FROM_SHARED` (MIGRATED, vervangt `SHARED_DEFINITION_DIVERGED`), `DETACH_NOT_NEEDED` (WARNING),
`DETACH_OPEN_BATCH`, `DETACH_RUN_IN_PROGRESS`, `DETACHED_DEFINITION_CODE_CONFLICT`, `DETACHED_DEFINITION_CODE_INVALID` (BLOCKING).
Geen nieuwe statussen.

**Frontend:** vinkje "Loskoppelen tot een eigen beschrijving" in `PreviewChoices.tsx` (enkel bij DIVERGED), optioneel codeveld;
`PlannedSummary.tsx` toont `planned.detach`; woordenlijst + `begrippen.md`.

**Tests:** nieuw `LegacyConfigDetachTest` + `LegacyConfigSharedDefinitionTest`, `LegacyConfigApplyTest`, `LegacyConfigPreviewTest`,
`LegacyConfigReceiptSchemaTest`, `PermissionCoverageTest`; frontend `npx vitest run src/test/LegacyConfig*`.
**Slices:** G2a-1 (026 + Domain/Dao, zwaar), G2a-2 (plan/execute + Web-params, zwaar), G2a-3 (frontend, gemiddeld), G2a-4 (design, licht).

## G2b — Single-flight voor de Prodis-rechtenoproep
In `ProdisPermissionSource` (r.92-121): na een miss in de sessiecache een `ConcurrentHashMap<FlightKey, CompletableFuture<Set<Permission>>>`
met sleutel `(sessionId, subject, username)`; zonder sessie geen single-flight. Leider (wint `putIfAbsent`) kijkt opnieuw in de sessiecache,
doet de oproep synchroon op zijn eigen thread (`RequestContextHolder` en tokenrefresh blijven werken), bij succes eerst `store` dan
`complete`, in `finally` `remove(key, future)`. Wachtenden: `future.get(connectTimeout + readTimeout + 1s)`; timeout of onderbreking → 503
zonder eigen oproep. Uitkomst gedeeld met de wachtenden van die oproep (`TOKEN_LOST` → 401, rest → 503, eigen exceptie-instantie per
wachtende); fouten nooit gecachet; enkel de leider logt WARN. Item ouder dan 2×maxWait mag vervangen worden. Geen clustering nodig
(sessies in geheugen). Tests in `ProdisPermissionSourceTest` met vertraagde `FakeProdisServer` (N threads → 1 request, fout gedeeld,
timeout → 503, twee sessies/subjects → twee oproepen, geen achtergebleven item). Slice G2b-1, bouwer-gemiddeld.

## G2c — Nieuwe revisievelden bewerken
**Nu:** `RevisionEditForm.tsx`/`revisionEditRequest.ts` bewerken enkel scalaire velden; S8-velden alleen-lezen; geen UI om mappings of
filters toe te voegen (backend `POST /revisions/{id}/mappings|filters` bestaat); mappings onwijzigbaar; `MappingView` (`SetupService` r.410)
en `FilterView` (r.421) missen aanmaakvelden.
- **Vaste leverancier/groep + hoofdletters:** in `RevisionEditForm`, per deel "Kolom | Vaste waarde", vinkje `identityUppercase`,
  `RevisionView.warnings` tonen; `identityChanged()` (`revisionEditRequest.ts` r.142) moet vaste waarden en hoofdletters meenemen
  (anders 409 `IDENTITY_CHANGE_NOT_ACKNOWLEDGED` zonder bevestigingsvinkje). Keuzelijsten via nieuwe `api/prodisReference.ts`; bij 409/503
  vrije tekst met melding.
- **Transformatie/`valueMap`/`steps`/`onMiss`:** vervangdialoog per mapping (enkel DRAFT) met drie vaste ketenvakken
  [Uitknippen] → [Voor-/achtervoegsel] → [één waardebewerking]; bij MAP/MAP_CONTAINS tabeleditor + plakvak (tab of `;`, twee kolommen,
  voorbeeld vooraf); bij MAP ook `caseSensitive` en `onMiss`.
- **`matchGroup`:** dialoog "Uitsluitingsgroep toevoegen" (N rijen EXCLUDE/COMPARE_AS_EMPTY met hetzelfde groepsnummer); lijst toont groepen samen.
- **Validatie:** client enkel vorm (verplicht, ≤ 50 tekens per vertaalrij, ≤ 1000 config, geen `;`, geen dubbele bron, getallen leesbaar,
  groep > 0); de server beslist (`CONFIG_TRANSFORM_INVALID`, `CONFIG_FILTER_INVALID`, `MATCH_GROUP_INVALID`, `COMPARE_VALUE_REQUIRED`,
  `SUPPLIER_FIXED_VALUE_*`).
- **Additieve backend:** `MappingView`/`FilterView` met alle aanmaakvelden (`SetupQueryService` r.238-247, `SetupService` r.1142/1283);
  nieuw `PUT /setup/revisions/{rid}/mappings/{mid}` (verwijderen + toevoegen in één transactie, zelfde volgnummer; 404, 409
  `REVISION_NOT_EDITABLE`/`CONFIG_BOOKMARK_*`, 400 config; MANAGE, achter de setup-vlag).

| Slice | Inhoud | Trap | Test |
|---|---|---|---|
| G2c-1 | views + PUT-replace | gemiddeld | `mvn -pl Web -am test -Dtest=SetupApiFlowTest,SetupMappingReplaceTest,PermissionCoverageTest,SetupApiDisabledTest -Dsurefire.failIfNoSpecifiedTests=false` |
| G2c-2 | vaste waarden, hoofdletters, Prodis-keuze, `identityChanged` | gemiddeld | `npx vitest run src/test/RevisionEdit* src/test/LegacyConfigRevisionDetail*` |
| G2c-3 | vervangdialoog met keten, tabel en plakken | gemiddeld | vitest (`MappingReplaceDialog.test.tsx`) |
| G2c-4 | filtergroepen toevoegen en tonen | licht→gemiddeld | vitest |

Later: nieuwe mapping van nul, vaste waarden in de wizard, batch-endpoint voor filtergroepen, CSV-upload.

## 4. Vragen voor de mens (blokkeren enkel G2a)
- **V1 — Hoe loskoppelen?** A (aanbevolen): bestaande koppeling verleggen naar de eigen kopie, enkel zonder open batch of lopende run
  (gedeeltelijke herroeping van "een bestaande koppeling wordt nooit gewijzigd", voor deze expliciete actie). B: nieuwe koppeling + taak,
  oude "gedeactiveerd" — verliest bronstaat, prijshistoriek, referentiestaat en behandelgevallen; "gedeactiveerd" wordt nergens gehandhaafd.
- **V2 — Mag de loskoppel-APPLY een ongewijzigde kopie van de ACTIVE revisie activeren?** A (aanbevolen): ja, via `activateRevision` met
  volledige validatie, actor als goedkeurder; de nieuwe legacy-inhoud blijft DRAFT (uitzondering op "APPLY activeert nooit"). B: nee →
  koppeling `NO_ACTIVE_REVISION` tot iemand activeert, inname ligt stil.

## 5. Ontdekkingen (voorstel om terug te schrijven)
- *Business:* de "stroom" van een leverancierscatalogus is de koppeling, niet de definitie; een definitie wisselen onder een koppeling
  behoudt de catalogushistoriek, een nieuwe koppeling begint van nul.
- *Technisch:* `import_link.active` en `catalog_import_task.active` worden nergens gezet of gehandhaafd.
- *Technisch:* `import_link` bewaart geen definitiehistoriek; na verleggen enkel via `import_batch.definition_revision_id` en
  `legacy_config_detachment`. Batchweergaven die de definitie via `link.getImportDefinition()` tonen worden misleidend (nakijken).
- *Technisch:* mappings zijn onwijzigbaar en `MappingView`/`FilterView` geven niet alle aanmaakvelden terug; `identityChanged()` in de
  frontend negeert vaste waarden en hoofdletters.
- *Technisch:* de app-wisselaar (`ProdisAccountRelay` via `AppSwitcher`) is een tweede beller van `GET /api/account` buiten de
  single-flight; in `/me` loopt hij na de rechtencontrole (`CatalogImportMeController` r.60-65), dus restkans op de race.
