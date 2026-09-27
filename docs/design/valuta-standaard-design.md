# Ontwerp — ontbrekende valuta = euro (standaardvaluta), herziening van aanname A22

Bindend na `docs/decisions.md` 2026-09-26 ("Ontbrekende valuta = euro" en "Valuta-standaard: ontwerp bindend"). Bouwstappen V-1 … V-7, strikt sequentieel.

## 1. Regel en plaats in de flow

In `CandidateNormaliser.normalise` (Service), op de plek waar nu `currency = null` ontstaat. Volgorde van de effectieve munt:
1. **Bronveld gemapt** (`config.currencyField() != null`) → `PriceRules.currency(raw, veld)`; herkomst `SOURCE`.
2. **Geen bronveld en de koppeling draagt een vaste valuta** (`import_link.default_currency`) → die waarde; herkomst `LINK_DEFAULT`.
3. **Anders** `EUR`; herkomst `SYSTEM_DEFAULT`.

Een **ongeldige of niet-ondersteunde bronwaarde blijft blokkeren** (`PriceRules.currency` gooit `PRICE_CURRENCY_MISMATCH`, de regel wordt verworpen); ze wordt nooit stil vervangen door de standaard. Alleen een *ontbrekend veld* krijgt de standaard. Een gemapt veld met een lege waarde blijft een fout
(bestaand gedrag). `PriceRules.componentCurrency` erft de effectieve munt; de `baseCurrency == null`-tak blijft staan maar is voor nieuwe rijen onbereikbaar.

## 2. Vaste valuta per koppeling (keuze mens: per koppeling, niet per revisie)

`import_link.default_currency varchar(3)` (nullable, check `~ '^[A-Z]{3}$'`, dezelfde ISO-4217-vormregel als `PriceRules.currency`, nooit upper-casen). Vastgelegd bij het aanmaken van de koppeling via `POST /setup/links` (optioneel veld `defaultCurrency`);
de setup-API is create-only, dus de waarde ligt daarna vast. Ze zit NIET in `record_rules_config_hash` (dat is een revisie-hash). Een wijziging van de vaste valuta van een koppeling verschijnt in de volgende levering als bedoelde prijswijziging (§4).

## 3. Herkomstmarkering (changeset `010-valuta-standaard.sql`, additief)

`base_price_currency_origin varchar(20)` (nullable, check `in ('SOURCE','LINK_DEFAULT','SYSTEM_DEFAULT')`) op `import_candidate_stage` (010-1), `catalog_source_state` (010-2; anders verliest de aanvaarde bronstaat de herkomst bij accept-baseline) en `import_mutation` (010-3).
`import_link.default_currency` is 010-4. Niet op `publication_bundle_snapshot_price`, `import_candidate_price`, `catalog_source_state_price` of `catalog_price_observation`: die erven de munt van de basisprijs (R-PRI-06). Oude rijen behouden `null` (= herkomst onbekend).

## 4. Vingerafdrukcompatibiliteit (keuze mens: geen canonicalisatieversie 3)

Bewezen: `ImportValueRules.canonical` zet het versienummer vooraan in élke canonieke tekst, ook die van `identity_hash`; een versiebump maakt dus elke bestaande aanbieding `NEW` (massa-CREATE), en `catalog_source_state` bewaart de invoer van `article_fingerprint` niet, dus migreren kan niet.
**Daarom géén versiebump.** De canonieke prijstekst schrijft de munt als: `SOURCE`/`LINK_DEFAULT` → de code; `SYSTEM_DEFAULT` → de bestaande "niet gemapt"-marker (U+0000). Gevolg: bestaande revisies (v1/v2) houden byte-identieke hashes en krijgen geen valse wijzigingen; een koppeling die een vaste valuta instelt, ziet die
munt wel in de delta (geen stille valutawijziging). Onder v1/v2 betekent de marker in het prijsdeel voortaan "EUR volgens de systeemregel". Versie 3 blijft gereserveerd voor het geval de systeemstandaard ooit iets anders dan EUR wordt.
`MutationDao.domainMask` vergelijkt de munt ook rechtstreeks (`coalesce(state.base_price_currency,'') <> coalesce(stage.base_price_currency,'')`): een state-`null` geldt als gelijk aan een stage-munt met herkomst `SYSTEM_DEFAULT`, anders krijgt elke gewijzigde rij ten onrechte `PRICE` in haar domeinmasker.

Compatibiliteitsmatrix: bestaande bronstaatrij (munt `null`) + levering zonder muntveld → hash ongewijzigd → `UNCHANGED`, en de kolom convergeert naar `EUR/SYSTEM_DEFAULT` bij de eerstvolgende aanvaarding. Bestaande koppeling die een vaste valuta krijgt (nieuwe koppelingen kunnen dat direct) → prijswijziging (terecht). `content_hash` en `snapshot_hash` van bevroren bundels worden
nooit herberekend en veranderen niet (`content_hash` bevat geen valuta).

## 4b. Bestaande gegevens (keuze mens: laten staan, geen backfill)

Bronstaat, mutaties en snapshots van vóór deze wijziging behouden `currency = null` en herkomst `null`; ze convergeren vanzelf bij de volgende aanvaarding. Een backfill zou een al ondertekende bundel van betekenis laten veranderen.

## 5. PSIMPORT-preview en run

`BASE_PRICE_CURRENCY = UNKNOWN` blijft bestaan en geldt alleen nog voor mutaties van vóór deze wijziging; nieuwe rijen geven `VALUE`. Nieuw veld `BASE_PRICE_CURRENCY_ORIGIN` (state `VALUE`, of `UNKNOWN` voor oude rijen) in mapper, CSV-serializer en Dao; géén nieuwe `State`-waarde, zodat `complete` niet van betekenis verandert.
`previewSpecVersion` wordt opgehoogd.

## 6. Afwijkingscontrole en prijsobservaties

`PRICE_CURRENCY_MISMATCH` blijft ongewijzigd (componentmunt versus basismunt). Een overgang `null → EUR` mag geen afwijking of valse mismatch geven; een tweede identieke levering na de wijziging levert 0 mutaties en 0 nieuwe observaties (expliciet testen).

## 7. Bouwstappen

| # | Doel | Trap | Gerichte tests | Status |
|---|---|---|---|---|
| V-1 | Schema: 3 origin-kolommen + `import_link.default_currency` (010-1..4), entiteiten | gemiddeld | `ScreeningSchemaTest`, `ImportControlSchemaTest` (kolomlijsten), nieuwe schematest | **Geïmplementeerd** |
| V-2 | Effectieve munt + herkomst in `CandidateNormaliser` (de koppelingswaarde bereikt de normaliser via de screeningconfig) | **zwaar** | `CandidateNormaliser`-tests, `PriceComponentScreeningFlowTest`, `FieldCriticalityTest`; v1/v2-hash byte-identiek vastpinnen | **Geïmplementeerd** |
| V-3 | Doorschrijven stage → bronstaat → mutatie + `domainMask`-correctie | **zwaar** | `DeliveryStagingTest`, `DeliveryScreeningFlowTest`, `CreationThresholdTest`, herleveringstest (0 mutaties) | **Vervallen (mens, 2026-09-26)** |
| V-4 | Setup: `defaultCurrency` bij het aanmaken van een koppeling, validatie, `*_by_subject`-neutraal | gemiddeld | `SetupApiFlowTest`, `SetupTemplateLinkActorHttpTest` | **Geïmplementeerd** |
| V-5 | Preview/run: herkomstveld, `previewSpecVersion` | gemiddeld | `PsimportPreviewMapperTest`, `PsimportPreviewHttpTest`, `PublicationRun*Test` | **Vervallen (mens, 2026-09-26)** |
| V-6 | Frontend: herkomst tonen naast de munt (oude rijen tonen "—", nooit EUR) | licht | `MutationList.test.tsx`, `BundleMutationsTab.test.tsx` | **Vervallen (mens, 2026-09-26)** |
| V-7 | Documentatie: A22 vervalt, fase2/fase3-design, business-rules, README/handleiding | licht | n.v.t. | **Afgerond** (fase2-screening-design.md §"base_price_currency", fase3-rules-design.md A22, catalog-import-business-rules.md, README.md §"Een eigen keten aanmaken", handleiding/csv-importeren.md en handleiding/standaardflows.md documenteren de standaard) |

## 8. Aanvullingen na implementatie

Onderstaande aanvullingen zijn in de code uitgevoerd maar niet in de initiële ontwerpsectie opgenomen:

- **`MutationDao.componentDiffers`-correctie (naast `domainMask`):** De mutatie-vergelijking controleert de munt ook rechtstreeks (`coalesce(state.base_price_currency,'') <> coalesce(stage.base_price_currency,'')`). Een state-`null` geldt hier als gelijk aan een stage-munt met herkomst `SYSTEM_DEFAULT` (dus `EUR`), anders krijgt elke wijziging ten onrechte `PRICE` in haar domeinmasker. Deze correctie is nodig omdat bestaande rijen `null` hebben en nieuwe rijen `EUR/SYSTEM_DEFAULT` krijgen.

- **Blokkeercode `CONFIG_LINK_CURRENCY_INVALID`:** Extra validatiecode bij een ongeldige vaste valuta. Dit is een vangnet (in praktijk onbereikbaar dankzij de `check`-constraint op de tabel).

- **Foutcode `LINK_CURRENCY_INVALID` (HTTP 400):** Wordt teruggegeven door de setup-API (`POST /api/catalog-import/setup/links`) wanneer de `defaultCurrency`-parameter niet aan de ISO-4217-regex voldoet (drie hoofdletters).

> **Important technical constraint discovered**
>
> `ImportValueRules.canonical` plaatst het canonicalisatieversienummer vooraan in élke canonieke tekst, óók die van `identity_hash`. Een revisie naar een hogere canonicalisatieversie tillen verandert daarom de aanbiedingsidentiteit zelf (elke bestaande aanbieding komt als `NEW`). Samen met het feit dat
> `catalog_source_state` de invoer van `article_fingerprint` niet bewaart, is een migratie van bestaande bronstaat naar een nieuwe versie niet berekenbaar. Daarom geen versiebump voor de standaardvaluta.

> **Important business rule discovered**
>
> `MutationDao.domainMask` vergelijkt de munt ook rechtstreeks naast de vingerafdruk. Zonder correctie krijgt elke toch al gewijzigde rij na invoering van de EUR-standaard ten onrechte `PRICE` in haar domeinmasker.