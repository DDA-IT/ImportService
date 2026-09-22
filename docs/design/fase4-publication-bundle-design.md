# Ontwerp Fase 4 — Publicatiebundel, goedkeuring en bevriezing

Bron: denker-zwaar-ontwerp van 2026-09-22, geaccepteerd door de hoofdsessie. Bindend naast
`docs/decisions.md`, `businessanalyse-catalogimport.md`, `docs/design/fase2-screening-design.md`
en `docs/design/fase3-rules-design.md`.

## 0. Vooraf

De letterlijke zin "acceptatie/afkeuring gebeurt per mutatie" staat niet woordelijk in het
brondocument; drie andere ankers zeggen samen hetzelfde en zijn hier leidend: bulk moet mogelijk
zijn (h. 4.1), elke mutatie draagt haar eigen herkomst (h. 18.1), de gebruiker beslist één keer per
incident (h. 23.2). Bulk is dus de bedoelde werkwijze, mits elke mutatie individueel auditeerbaar
blijft. Hoofdstuk 18 van `businessanalyse-catalogimport.md` wijst de Publicatiebundel zelf aan
"Fase 5" toe — dat is een faseringsuitspraak, geen businessregel, en wordt door dit ontwerp
ingehaald (bijgewerkt na afronding). Geen tegenstrijdigheid gevonden tussen decisions.md en het
brondocument op dit terrein.

## 1. Regelinventaris (samengevat; volledige tabellen in het denker-rapport, zie git-geschiedenis
van dit bestand als het rapport apart bewaard moet worden — hier de kern per groep)

**Bundel (R-BND):** een importkoppeling publiceert nooit zelfstandig; kandidaten van gekozen imports
komen samen in één, eventueel koppelingsoverstijgende Publicatiebundel. Een batch zit in hoogstens
één niet-geannuleerde bundel (databaseconstraint). Alleen een `SCREENED`-batch met vastgesteld
`validation_result ≠ BLOCKING` mag toetreden. Een batch met 0 inhoudelijke mutaties mag toetreden
(zijn bestaande `IMPORT_MARKER` telt als bewijs). Baseline wordt bij toevoegen én bij bevriezen
getoetst. Fase 4 schrijft NOOIT in `catalog_source_state(_price)`, `catalog_price_observation` of
`catalog_reference_state`.

**Beslissingen per mutatie (R-DEC):** acceptatie/afkeuring per mutatie, elke mutatie krijgt eigen
`decided_by`/`decided_at`/`decided_from_status`/verwijzing naar de beslissing, ook bij een
groepsactie. Eén bevoegde persoon volstaat (geen vier-ogen). `decidedBy` verplicht/niet-leeg/≤100/
nooit `system`. Afkeuren vereist een reden (verplicht, ≤500). Goedgekeurd ⇒ `READY_FOR_PUBLICATION`,
afgekeurd ⇒ `REJECTED` (bestaande `MutationStatus`-enum, ongewijzigd). Idempotent bij herhaling;
een tegengestelde herziening mag alleen individueel en alleen tijdens `ASSEMBLING`, en schrijft altijd
een nieuwe, append-only beslissingsregel. `PLANNED` vereist geen individuele beoordeling (wordt bij
bevriezen in bulk goedgekeurd op naam van de bevriezer); `AWAITING_APPROVAL` wél altijd expliciet.
`BLOCKED` en `IDENTITY_REFERENCE_INCIDENT`-mutaties krijgen geen beslispad in Fase 4 (zie §3.6).
Financiële waarden (`before/after_base_price`, `domain_mask`, vingerafdrukken) blijven vóór en na
een beslissing byte-identiek.

**Bevriezen (R-FRZ):** alleen vanuit `ASSEMBLING`, met ≥1 batch, `frozenBy` + verplichte reden.
Geweigerd zolang er nog `AWAITING_APPROVAL`-mutaties open staan. Conflictregel binnen de bundel:
twee publiceerbare mutaties op dezelfde `(import_link_id, identity_hash)` uit verschillende batches
⇒ geweigerd tot één van beide is afgekeurd. Conflictregel tussen bundels: dezelfde combinatie mag
niet elders publiceerbaar openstaan. Bevriezen zet resterende `PLANNED` in bulk op
`READY_FOR_PUBLICATION`, berekent en bewaart een volledige bundelhash + globale tellers (nullable =
niet vastgesteld), is één transactie (alles of niets, hervatbaar bij onderbreking), en sluit de
bundel daarna af (geen nieuwe leden/beslissingen, 409). Annuleren mag vanuit `ASSEMBLING` én `FROZEN`
(zolang Fase 5 niet begonnen is), met verplichte reden; niet-terminale mutaties van de leden worden
`EXPIRED`, batches komen weer vrij.

**Verhouding tot `accept-baseline` (R-BAS):** blijft ongewijzigd bestaan als Fase 2/3-actie ("is deze
screening een betrouwbare vergelijkingsbasis"), geen goedkeuring, geen publicatie. De twee routes
sluiten elkaar per batch uit: `accept-baseline` geeft 409 `BATCH_IN_PUBLICATION_BUNDLE` voor een
batch met een actief bundellidmaatschap; een `BASELINE_ACCEPTED`-batch kan nooit tot een bundel
toetreden. Na annulering is `accept-baseline` weer mogelijk.

**Bewust uitgesteld naar Fase 5 (of later):** het daadwerkelijk publiceren naar ProDisWebbase/
Pervasive (`252 IMPORT`/PSIMPORT, `READY_FOR_PUBLICATION`→`IN_PROGRESS`/`PUBLISHED`/
`TECHNICALLY_FAILED`); bijwerken van `catalog_source_state` naar `PUBLISHED`; het beslissingspad voor
kritieke identiteitsincidenten (vraagt schrijven in `catalog_reference_state` + permanente
migratie-audit); scheduler/connectors/Keycloak; BA2's expliciete wijzigingsgroep-object (verpakking/
staffels/BOM/supplementsets/verwijderscopes — bestaan nog niet in het datamodel); uitzonderingsregels,
werkvoorraad/dashboards; echte proefpublicatie naar een controle-PSARFxxx.

## 2. Datamodel — changeset `005-publication-bundle-core.sql` (additief; 001-004 ongewijzigd)

**005-1 `publication_bundle`**: `id`, `bundle_reference varchar(100) not null unique`, `description
varchar(500)`, `status varchar(30) not null` (`PublicationBundleStatus`), `target_mode varchar(30)
not null` (`SIMULATION|TRIAL_LIBRARY|PRODUCTION`, GEEN default — nooit stil PRODUCTION of stil
SIMULATION), `target_moment`, `publication_policy varchar(1000)`, `created_by/at`, `frozen_by/at/
reason`, `cancelled_by/at/reason`, `content_hash ${hash.type}` (enkel bij FROZEN), `idempotency_key
varchar(200) not null unique` (`'bundle:' || bundle_reference`), `batch_count`,
`content_mutation_count`, `ready_count`, `rejected_count`, `blocked_count`, `expired_count`,
`identity_incident_count`, `bulk_incident_count`, `critical_issue_count`, `warning_count` (alle
tellers bigint nullable). Checks: status-set, target_mode-set, FROZEN vereist frozen_by/at/
content_hash, CANCELLED vereist cancelled_by/at/reason.

**005-2 `publication_bundle_batch`** (koppeltabel op BATCHNIVEAU, niet op mutatieniveau — zie
motivering hieronder): `id`, `bundle_id fk`, `batch_id fk`, `import_link_id fk` (gedenormaliseerd),
`added_by/at`, `removed_by/at/reason`, `active_marker boolean` (TRUE zolang actief, anders NULL).
`unique (bundle_id, batch_id)`; **`unique (batch_id, active_marker)`** — de harde garantie dat een
batch hoogstens één actief lidmaatschap heeft (zelfde NULL-markertruc als `uk_import_batch_open`,
`ImportDefinitionRevision.activeMarker`, `catalog_reference_state.active_marker`,
`uk_catalog_reference_state_offer_active`). Check: removed-velden allemaal samen null of samen gevuld,
consistent met active_marker.

> **Ontwerpkeuze (bewust afwijkend van de letterlijke opdrachttekst):** koppeling op BATCHNIVEAU, niet
> op mutatieniveau. Reden: (1) het brondocument zegt letterlijk "een batch kan slechts in één open of
> bevroren bundel tegelijk zitten"; (2) een koppeltabel per mutatie zou een tweede bron van waarheid
> worden naast `import_mutation.batch_id`; (3) bij leveringen tot 1M regels zou een koppelrij per
> mutatie het toevoegen van een batch tot een miljoenen-rijen-insert maken. De garantie "een mutatie
> zit in hoogstens één actieve bundel" volgt transitief uit de batch-constraint. Er komt daarom ook
> GEEN `import_mutation.publication_bundle_id`-kolom, ondanks dat `fase2-screening-design.md` §4 die
> aankondigde — de bundel van een mutatie is af te leiden via
> `batch_id → publication_bundle_batch (active) → bundle_id`.

**005-3 `publication_decision`** (append-only beslissingsregister, retentie 7 jaar): `id`, `bundle_id
fk`, `mutation_id fk` (enkel bij scope MUTATION), `decision_kind varchar(30) not null`
(`APPROVE|REJECT|AUTO_APPROVE_PLANNED|FREEZE|CANCEL`), `decision_scope varchar(20) not null`
(`MUTATION|GROUP|BUNDLE`), `selection_filter varchar(500)` (canoniek gerenderde filter bij GROUP),
`previous_status`, `new_status`, `affected_count bigint not null default 1`, `decided_by not null`,
`decided_at not null`, `reason varchar(500) not null`. Checks op decision_kind/scope-sets en op de
samenhang mutation_id/affected_count/scope.

**005-4 `import_mutation`** + vier additieve, nullable kolommen: `decided_by`, `decided_at`,
`decided_from_status`, `decision_id fk → publication_decision`. Check: alle vier samen null of samen
gevuld. Check: `status='REJECTED' ⇒ decision_id is not null` (verplichte reden dus afgedwongen op
databaseniveau). Index `(import_link_id, identity_hash, status)` voor de conflictquery. Geen
`decision_reason` op de mutatie zelf (de reden staat één keer op de beslissingsregel, niet
gedupliceerd over mogelijk honderdduizenden rijen).

Niets van het bestaande wordt hernoemd, verwijderd of van betekenis veranderd: `MutationStatus`,
`MutationActionType`, `MutationTargetDomain`, `ImportBatchStatus`, `ValidationResult` blijven exact
zoals ze zijn; Fase 4 gebruikt uitsluitend al gedeclareerde waarden.

## 3. Service- en REST-ontwerp

**Nieuwe klassen:** Domain: `PublicationBundle`, `PublicationBundleBatch`, `PublicationDecision` +
enums `PublicationBundleStatus`, `PublicationTargetMode`, `BundleDecisionKind`,
`BundleDecisionScope`; `ImportMutation` krijgt de vier decisievelden + samengestelde setter
`recordDecision(by, at, fromStatus, decisionId)`. Dao: Spring Data-repo's + één JdbcTemplate-
`@Repository` `PublicationBundleDao` (set-based tellingen, conflictqueries, `decideByFilter`,
`approvePlanned`, `expireOpenMutations`, bundelhash-stream). Service:
`PublicationBundleService`, `BundleDecisionService`, `BundleFreezeService`, `BundleQueryService`
(niet `@Transactional`, `TransactionTemplate`, `findByIdForUpdate` als serialisatiepunt — zelfde
patroon als `SourceStateBaselineService`). Kleine hergebruik-refactor: `requireAcceptedBy`/
`requireText` uit `SourceStateBaselineService` worden een gedeelde package-private `ActorNames`-helper
in Service (geen publieke signatuur, geen gedragswijziging).

De baselinecontrole bij bevriezen gebruikt UITSLUITEND kolommen op `import_mutation` zelf (niet de
kandidaatstaging, die een kortere retentie heeft dan een openstaande bundel):
```sql
select count(*) from import_mutation m
join publication_bundle_batch pbb
  on pbb.batch_id = m.batch_id and pbb.bundle_id = ? and pbb.active_marker is not null
left join catalog_source_state s
  on s.import_link_id = m.import_link_id and s.identity_hash = m.identity_hash
where m.action_type in ('CREATE','UPDATE')
  and m.status in ('PLANNED','AWAITING_APPROVAL','READY_FOR_PUBLICATION')
  and ( (m.action_type = 'CREATE' and s.id is not null)
     or (m.action_type = 'UPDATE' and (s.id is null
                                    or s.combined_fingerprint <> m.before_combined_fingerprint)) )
```

**Endpoints** (onder `/api/catalog-import`, inline records, `PageResult`, bestaande
`ApiExceptionHandler`):
`GET /bundles/candidates`, `POST /bundles`, `GET /bundles`, `GET /bundles/{id}`,
`GET /bundles/{id}/batches`, `GET /bundles/{id}/mutations`, `GET /bundles/{id}/decisions`,
`POST /bundles/{id}/batches`, `POST /bundles/{id}/batches/{batchId}/remove`,
`POST /bundles/{id}/mutations/{mutationId}/approve`, `.../reject`,
`POST /bundles/{id}/decisions` (groepsactie), `POST /bundles/{id}/freeze`,
`POST /bundles/{id}/cancel`.

Bewust GEEN automatisch verzamelen bij aanmaken — een expliciete batchlijst is reproduceerbaar en
auditbaar; de volgorde waarin screenings toevallig eindigen mag nooit de bundelinhoud bepalen
(h. 17.3). Foutcodes: `BUNDLE_NOT_FOUND`, `MUTATION_NOT_IN_BUNDLE`,
`BUNDLE_REFERENCE_REUSED_WITH_DIFFERENT_SCOPE`, `BUNDLE_NOT_ASSEMBLING`, `BUNDLE_FROZEN`,
`BUNDLE_EMPTY`, `BUNDLE_HAS_UNDECIDED_MUTATIONS`, `BUNDLE_OFFER_CONFLICT`,
`OFFER_ALREADY_IN_ANOTHER_BUNDLE`, `SOURCE_STATE_CHANGED_SINCE_SCREENING` (hergebruik),
`BATCH_NOT_BUNDLEABLE`, `BATCH_ALREADY_IN_BUNDLE`, `BATCH_VALIDATION_BLOCKING`,
`BATCH_VALIDATION_NOT_ESTABLISHED`, `BATCH_HAS_DECIDED_MUTATIONS`, `BATCH_IN_PUBLICATION_BUNDLE` (op
accept-baseline), `MUTATION_NOT_DECIDABLE`, `MUTATION_BLOCKED_BY_IDENTITY_INCIDENT`,
`IDENTITY_DECISION_NOT_IN_SCOPE`, `MUTATION_ALREADY_DECIDED`, `DECISION_FILTER_REQUIRED` (400).

**Goedkeuringsalgoritme (individueel):** lock op de bundel (`ASSEMBLING` vereist) → mutatie ophalen
+ actief lidmaatschap controleren → marker/incident-mutatie ⇒ 409 → `BLOCKED` ⇒ 409 → al in
doelstatus met dezelfde beslisser ⇒ 200 idempotent → tegengestelde eindstatus ⇒ herziening
(individueel, nieuwe beslissingsregel, oude blijft staan) → beslissingsregel invoegen, dan mutatie
bijwerken. `status_reason` (bv. `BULK_PRICE_INCIDENT`) wordt NOOIT overschreven door de beslissing —
anders verlies je waarvoor iemand tekende.

**Groepsactie:** één `update ... where` met harde `where`-staart
`action_type in ('CREATE','UPDATE') and status in ('PLANNED','AWAITING_APPROVAL') and decision_id is
null` — raakt dus nooit `BLOCKED`, nooit een incident, nooit een marker, nooit een al besliste
mutatie. Eén `GROUP`-beslissingsregel met `affected_count`, elke geraakte mutatie krijgt in dezelfde
`update` haar eigen audit. Lege filter geweigerd (400).

**Wijzigingsgroep binnen de bundel (Fase 4):** `(batch_id, identity_hash)` — geen nieuwe tabel/kolom,
`identity_hash` bestaat al op `import_mutation`. De drie andere atomiciteitsscopes uit het
brondocument (set/verwijder/afhankelijkheid) hebben nog geen datamodel (supplementen niet gebouwd,
BOM later, verwijderingen niet in Fase 3, verpakking/staffels later) en worden dus niet vooruitgebouwd.
Uitbreidingspad zonder migratie: een latere nullable `change_group_id`. Afgeleide bundelstatussen
("Ter beoordeling", "Klaar voor goedkeuring", "Gedeeltelijk blokkerend") zijn viewwaarden uit de
tellers, geen eigen kolom (geen tweede bron van waarheid).

**Waarom `BLOCKED`/identiteitsincidenten geen beslispad krijgen (§3.6):** een identiteitsbeslissing
heeft vijf mogelijke uitkomsten (aanvaarden als migratie, normaliseren, bronregel opslaan, opsplitsen,
verwerpen), elk schrijft in `catalog_reference_state` met permanente migratie-audit + 365-daagse
zoekalias — dat is precies wat Fase 4 (R-BND-08) niet mag. Half goedkeuren zou een kritieke
referentiewijziging als gewone update laten publiceren, wat FR93/R-REF-02 verbiedt. Ze blijven dus
zichtbaar staan (tellers `blockedCount`/`identityIncidentCount`), beletten bevriezen niet, worden door
Fase 5 niet gepubliceerd.

**Verhouding tot `accept-baseline`:** bevestigd — twee verschillende dingen die beide blijven bestaan
(zie tabel in het denker-rapport; kern: `accept-baseline` = "is dit een betrouwbare vergelijkingsbasis"
op batchniveau en schrijft de bronstaat; de bundel = "mag dit naar Prodis" over meerdere
batches/koppelingen en schrijft de bronstaat NOOIT). Wederzijds uitsluitend per batch (R-BAS-02),
zie het "Important technical constraint discovered"-blok in §9.

**Herscreening tegenover een bevroren bundel:** een latere levering wijzigt nooit een mutatie in een
bevroren bundel (nieuwe Delivery → nieuwe batch → nieuwe mutaties met andere idempotency_key; de
nieuwe screening vergelijkt tegen `catalog_source_state`, dat Fase 4 niet schrijft). Wél nieuw risico
benoemd: twee opeenvolgende leveringen van DEZELFDE bron op dezelfde aanbieding, allebei publiceerbaar
vóór de eerste gepubliceerd is, zouden "laatste import wint" kunnen worden (verboden, r.1412) —
opgevangen door dezelfde conflictregel R-FRZ-03/04 die ook tussen bundels geldt.

## 4. Statusdiagram

`import_mutation.status` (enum ONGEWIJZIGD; Fase 4 voegt enkel overgangen toe binnen `ASSEMBLING`):
`PLANNED → READY_FOR_PUBLICATION` (approve, of automatisch bij freeze) of `→ REJECTED`;
`AWAITING_APPROVAL → READY_FOR_PUBLICATION` (altijd expliciet) of `→ REJECTED`;
`READY_FOR_PUBLICATION ↔ REJECTED` (herziening, individueel, alleen ASSEMBLING);
`PLANNED|AWAITING_APPROVAL|READY_FOR_PUBLICATION → EXPIRED` (bundel geannuleerd); `BLOCKED` en
`IDENTITY_REFERENCE_INCIDENT`-mutaties: geen pad in Fase 4. Bestaand ongewijzigd: `accept-baseline`
(`PLANNED|AWAITING_APPROVAL → SKIPPED`, alleen buiten elke bundel). Fase 5 (niet in scope):
`READY_FOR_PUBLICATION → IN_PROGRESS → PUBLISHED|TECHNICALLY_FAILED`.

`PublicationBundleStatus` (nieuw, 7 waarden, Fase 4 zet er 3): `ASSEMBLING → FROZEN → CANCELLED`
(vanuit beide) of `ASSEMBLING → CANCELLED`; Fase 5 declareert (niet gebruikt door Fase 4):
`PUBLISHING`, `PARTIALLY_PUBLISHED`, `PUBLISHED`, `PUBLICATION_FAILED`. Hulpmethodes:
`holdsBatches()` (false alleen bij CANCELLED — een gepubliceerde bundel geeft batches NOOIT vrij) en
`acceptsChanges()` (true alleen bij ASSEMBLING).

## 5. Testplan (samengevat; volledige G/O/D/X/B-tabel per testklasse in het denker-rapport)

Alle tests in `Web/src/test`, `mvn -pl Web -am test`. Testklassen: `PublicationBundleSchemaTest`,
`PublicationBundleLifecycleTest`, `BundleMutationDecisionTest`, `BundleGroupDecisionTest`,
`BundleFreezeTest`, `BundleConflictTest`, `BundleCancelTest`, `BundleBaselineInteractionTest`,
`BundleHttpTest`. Kernbewijzen: unieke actieve batch-constraint; financiële velden (`before/
after_base_price`, `domain_mask`, `status_reason`) vóór/na een beslissing byte-identiek; groepsactie
raakt nooit BLOCKED/incident/al-beslist; freeze is atomair en hervatbaar; conflict verdwijnt na
afkeuring van één kant; accept-baseline schrijft niets voor een batch in een bundel. Regressie:
`DeliveryScreeningFlowTest`, `BatchBaselineHttpTest`, `AcceptBaselineReviewFlowTest`,
`ValidationResultTest`, `ScreeningSchemaTest` blijven ongewijzigd slagen. Buiten scope (mens):
PostgreSQL, performance van de bundelhash/conflictquery/lange freeze-transactie op 1M mutaties.

## 6. Bouwstappen (strikt sequentieel, één commit per stap, geen parallellisatie)

- **4a** bouwer-gemiddeld: changeset 005 (005-1 t/m 005-4) + master-changelog include; enums;
  entiteiten `PublicationBundle`/`PublicationBundleBatch`/`PublicationDecision`; `ImportMutation` +4
  velden + `recordDecision`; repositories; `PublicationBundleSchemaTest`. DoD: compileert, schema
  migreert, alle constraints aantoonbaar, GEEN gedragswijziging, alle bestaande tests groen.
- **4b** bouwer-gemiddeld: `ActorNames`-helper; `PublicationBundleService` (create idempotent,
  batches toevoegen/verwijderen), `PublicationBundleDao.countByStatus/countStaleMutations`,
  `BundleQueryService`, endpoints t/m batchbeheer + candidates; de accept-baseline-wacht (R-BAS-02);
  `PublicationBundleLifecycleTest`, `BundleBaselineInteractionTest`. **Rapport aan de mens na deze stap.**
- **4c** bouwer-zwaar (statusflow+financieel): `BundleDecisionService`, individuele approve/reject,
  herzieningen, `GET .../mutations` + `.../decisions`, `MutationRow` uitgebreid;
  `BundleMutationDecisionTest`.
- **4d** bouwer-zwaar: `POST .../decisions` (groepsactie), `PublicationBundleDao.decideByFilter`;
  `BundleGroupDecisionTest`.
- **4e** bouwer-zwaar (meerdere lagen): `BundleFreezeService` (voorwaarden, conflictcontrole,
  baselinecontrole, auto-approve PLANNED, hash, tellers, afsluiting); `BundleFreezeTest`,
  `BundleConflictTest`.
- **4f** bouwer-gemiddeld: `POST .../cancel` (EXPIRED-sweep + vrijgave), `BundleCancelTest`,
  `BundleHttpTest`, javadoc-afwerking. **Rapport aan de mens na deze stap.**

## 7. Documentatie-impact (na afronding, met akkoord mens)

`businessanalyse-catalogimport.md` h. 18 (mutatie-eenheid → `[gebouwd: fase 4]`, plus het nieuwe
"laatste-import-wint-tussen-twee-leveringen"-blok uit §3.8), h. 12 (vierde statusas), h. 13.1 (stappen
5-6 → gebouwd), h. 27 (FR99 gedekt). `fase2-screening-design.md` §4: aanvullen dat er bewust GEEN
`publication_bundle_id` op `import_mutation` kwam, plus het accept-baseline-vs-bundel-blok.

## 8. Open vragen

Geen. Alles herleidbaar uit `docs/decisions.md` en `businessanalyse-catalogimport.md`. Vier eigen
keuzes van de Denker, door de mens bevestigd op 2026-09-22 (zie decisions.md): PLANNED wordt in bulk
goedgekeurd bij bevriezen; één import volstaat voor een bundel; kritieke identiteitsincidenten krijgen
geen beslispad in Fase 4; een bevroren bundel kan nog geannuleerd worden zolang Fase 5 niet begonnen is.

## 9. Aannames (A23-A30)

A23 lidmaatschap op batchniveau, geen `publication_bundle_id` op de mutatie. A24 wijzigingsgroep =
`(batch_id, identity_hash)`. A25 PLANNED auto-approved bij freeze, AWAITING_APPROVAL nooit. A26
BLOCKED/identiteitsincident geen beslispad. A27 freeze is één (niet-gechunkte) transactie — zelfde
risicoklasse als bestaande niet-gechunkte operaties, atomair weegt zwaarder dan hervatbaar. A28
bundelhash geordend op `id` (niet idempotency_key, i.v.m. collatie). A29 baselinecontrole op
`import_mutation`, niet op de staging (kortere retentie). A30 geen autorisatie; actor-velden als
requestveld, zelfde validatie als `acceptedBy`; Fase 5 vervangt door Keycloak-identiteiten.

> **Important technical constraint discovered**
>
> `accept-baseline` en de Publicatiebundel zijn twee elkaar uitsluitende routes voor dezelfde batch.
> Zonder wacht: `MutationDao.skipOpenContentMutations` raakt alleen PLANNED/AWAITING_APPROVAL, dus een
> al `READY_FOR_PUBLICATION`-mutatie blijft ongemoeid — maar `SourceStateBaselineService` schrijft wél
> `catalog_source_state`/`catalog_price_observation`. Gevolg: Fase 5 zou tegen een verschoven baseline
> publiceren, de prijshistoriek zou een nooit-gepubliceerde "goedgekeurde" waarde bevatten, en een
> volgende levering zou die wijziging als UNCHANGED zien. Maatregel: R-BAS-02 (409
> `BATCH_IN_PUBLICATION_BUNDLE`).

> **Important business rule discovered**
>
> Twee leveringen van DEZELFDE bron die dezelfde aanbieding wijzigen en beide publiceerbaar staan,
> zouden zonder maatregel "laatste import wint" worden (verboden, r.1412) — het brondocument benoemt
> dit conflict alleen tussen verschillende bronnen. Maatregel: dezelfde conflictregel (R-FRZ-03/04)
> geldt ook hier; bevriezen wordt geweigerd tot één kant is afgekeurd of de oudere bundel eerst
> gepubliceerd is.

## 10. Aanvullingen uit stap 4a (geïmplementeerd, hoofdsessie akkoord)

- `content_hash` op `PublicationBundle` is gewone JPA (niet JDBC-only): één rij per bundel, geen
  bulkschrijven, pas gevuld in 4e. Afwijking van het "hashkolommen nooit via JPA"-patroon, bewust
  aanvaard voor deze ene rij-per-bundel kolom.
- Samengestelde audit-methoden `recordFreeze`/`recordCancellation`/`recordRemoval` staan er al
  (zelfde stijl als `ImportBatch.recordBaselineAcceptance`), zonder validatie/transactielogica —
  die komt in 4e/4f.
- `ck_publication_bundle_batch_marker` (active_marker enkel NULL of TRUE) toegevoegd voor
  consistentie met `catalog_reference_state.active_marker`.
- Index `idx_import_mutation_link_identity_status` naast de bestaande `idx_import_mutation_link_identity`
  (004), nodig voor de conflictquery uit §3.
