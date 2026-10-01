# Alle configuratiefouten tegelijk tonen — ontwerp NT-14a

Status: bindend voor NT-14 (denker-zwaar, 2026-10-01; door de hoofdsessie overgenomen, geen §6-vragen).
Aanleiding: de mens wees op 2026-10-01 "één configuratiefout per keer" af: checklist (gereedheid) en proefinlezing tonen **alle** fouten in de
beschrijving van het bestand tegelijk. Harde eis: screening, upload, ophaalrun, activatie, setup-writes en materialisatie gedragen zich **exact** gelijk
(zelfde eerste code, status, tekst, volgorde).

## 1. Gekozen aanpak: collector met takken in de bestaande fabrieken (optie a')

- `SourceStructureConfigFactory` en `ImportMappingConfigFactory` blijven de enige implementatie van de regels. Bestaande werpplaatsen blijven
  ongewijzigd, maar worden gegroepeerd in **takken** via een `ConfigProblemCollector` met stand **FIRST** of **ALL**.
- FIRST: `report(e)` gooit hetzelfde exception-object opnieuw → `from(...)` gedraagt zich bij constructie zoals vandaag.
- ALL: `report(e)` voegt een `ConfigFinding(code, fieldName, sourceValue, expectedValue, message, revisionField)` toe; afhankelijke takken worden
  overgeslagen en hun grondoorzaak komt in `skippedBecause`.
- Nieuwe types in `service.support`: `ConfigProblemCollector`, `ConfigFinding`, `ConfigCheckReport(findings, skippedBecause)`, `StructureFacts`.
- Nieuwe methodes `collect(...)` op beide fabrieken; `from(...)` behoudt zijn handtekening (FIRST). `MappingSettings` en `FieldTransform` ongewijzigd.
- Verworpen: (b) aparte validatorlaag (tweede implementatie, drift, schendt "extend, kopieer niet"); (c) herhaald draaien met neutralisering
  (valse fouten, parallelle kennis).

## 2. Afhankelijkheden

Regel: **stop de tak, ga door met de rest**. Een controle die steunt op een zelf foute waarde wordt niet beoordeeld en dat wordt gemeld; nooit stil
weggelaten, nooit een valse fout. Voorbeelden (volledige tabel in het denker-dossier van 2026-10-01, §1):
- S6 (referentiesoort) ongeldig → geen kolomindexcontroles (S15, M3n-index, M4-index); S8 (kolomaantal) ongeldig → enkel "geen getal"/"< 1".
- S3 (scheidingsteken) ongeldig → geen gelijkheidstest met het aanhalingsteken.
- Mappingrij: onbekend doelveld (M3b) stopt de rij; onleesbare `transform_config` (M3i) → geen controle op ongebruikte sleutels (M3m).
- M5 onderdrukt bij S9 (identiteitskolommen), M6 onderdrukt bij S14 (herkenningsversie); M6 hoogstens één bevinding.
- S9: elke identiteitskolom apart (tot 3 bevindingen).

**Invariant:** `collect(...).findings().get(0)` = de FIRST-worp (code, fieldName, sourceValue, expectedValue, message); "geen worp" = lege lijst.

> **Important business rule discovered** — "Alle fouten tegelijk" betekent alle **onafhankelijk** te beoordelen fouten; wat niet beoordeeld kon worden,
> wordt expliciet gemeld ("na herstel kunnen er nog fouten bijkomen").

## 3. Presentatie

- `fieldName` blijft zoals in de exception (structuurfouten: null — wijzigen zou de opgeslagen issuerij van de screening veranderen). Nieuw, enkel in
  het collect-resultaat: `revisionField` (sleutel uit het woordenlijstdomein `revisionField`), o.a. om S11/S12 (zelfde code
  `CONFIG_PRICE_FIELD_MISSING`, andere betekenis) en de drie S9-varianten te onderscheiden.
- **Gereedheid:** `ChainConfigurationChecks.configurationReport(revision, linkCurrency)` en `activationProblemsAll(draft)`; per bevinding één PROBLEM
  (subject REVISION, zelfde detailformaat als vandaag), additief `fieldName`/`revisionField` op `ReadinessCheck`; bij overgeslagen controles één INFO
  `INFO_CONFIG_CHECKS_SKIPPED` (telt niet voor `ready`). Activatie en setup blijven `activationProblems`/`configurationProblem` (FIRST).
  De eerste configregel is byte-gelijk aan vandaag.
- **Proefinlezing:** enkel bij een configfout een tweede aanroep in ALL-stand binnen dezelfde leestransactie; `configProblems` = volledige lijst
  (additief `revisionField`), additief `configChecksSkippedBecause`. `verdict` blijft uit de screeningpoging komen (eerste fout). Bewaking:
  wijkt `configProblems[0]` af van het verdict → verdict vooraan + WARN zonder inhoud (test eist dat dit nooit gebeurt).
- Leesfase-CONFIG_* (header) blijft één item.

## 4. Buiten scope

- `TemplateMaterialisationService` blijft ongewijzigd (gebruikt dezelfde fabrieken in FIRST-stand; er is géén kopie van de regels — correctie op NT-8).
- Geen additieve foutlijst in 400-antwoorden van activatie/setup (A2).
- Checklist voert bij een **actieve** versie de fabrieken niet uit (A3).

## 5. Teststrategie

1. Golden table `ConfigFactoryParityTest` (zonder Spring), één rij per werpplaats (~85) met letterlijk verwachte code/fieldName/sourceValue/expectedValue/
   message; geschreven en groen op de **huidige** code vóór de refactor; tijdens NT-14-1 niet te wijzigen; meta-test: elke `CODE_*`-constante gedekt.
2. Volgorde-corpus (combinaties): FIRST gooit de eerste; ALL bevat beide of meldt de afhankelijke als overgeslagen.
3. Invariant over tabel + corpus.
4. `ConfigFactoryCollectAllTest` voor ALL-specifiek gedrag.
5. Integratie: `ImportLinkReadinessHttpTest` (≥3 fouten, eerste = 400 van activate), `TrialReadHttpTest` (`configProblems[0]` = `verdict.blockedCode` =
   echte upload).
6. Regressie: `MappingConfigValidationTest`, `FieldCriticalityTest`, `CandidateNormaliserTest`, `FieldValueMapperTest`, `ImportIssueCatalogTest`,
   `DeliveryStagingTest`, `ControlHierarchyTest`, `PriceDeviationTest`, `DeliveryUploadTest`, `SetupCreateConflictAndCodeHttpTest`, `RevisionUpdateTest`,
   `SetupApiFlowTest`, `TemplateMaterialisationValidationTest`, `TemplateBlockingPointTest`, `LinkBookmarkValueTest`.

## 6. Bouwvolgorde (sequentieel)

NT-14-0 golden table (gemiddeld) → NT-14-1 collector + takken (zwaar; risico: volgorde van takken, niets herordenen) → NT-14-2 gereedheid (gemiddeld) →
NT-14-3 proefinlezing (gemiddeld; `proefinlezing-design.md` §2/A-7 bijwerken) → NT-14-4 frontend (gemiddeld) → NT-12.

## 7. Aannames

A1 alle onafhankelijke fouten + melding van overgeslagen controles; A2 alle schrijfpaden en screening blijven FIRST; A3 geen fabriekscontrole op een actieve
versie in de checklist; A4 dubbele betekenis `CONFIG_PRICE_FIELD_MISSING` blijft, onderscheid via `revisionField`; A5 M5/M6 onderdrukt bij grondoorzaak;
A6 materialisatie ongewijzigd.

## 8. Ontdekte technische beperkingen

- Setup weigert nieuwe mapping/filter/kritiekheid bij **elke** bestaande configfout (volledige FIRST-validatie, `SetupService` ~1075/1124/1171);
  `updateRevision` valideert niet. NT-14 verandert dit niet.
- Structuurfouten hebben geen `fieldName` en dat moet zo blijven (opgeslagen issuerij van de screening).
- De record-opbouw van `SourceStructureConfig` gooit `IllegalArgumentException` (geen CONFIG-code); in ALL-stand nooit opbouwen zolang er een bevinding is.
