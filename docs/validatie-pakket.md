# Validatiepakket — legacy-config-migratie, Prodis als rechtenbron, publicatie naar de Prodis-DB

> Opgesteld 2026-10-22 (denker-licht, lezend; aangevuld door de hoofdsessie). Doel: in één keer overlopen wat sinds 2026-10-20 gebouwd is,
> welke keuzes als **"aanbeveling gevolgd — door de mens te herzien"** genomen zijn (`docs/decisions.md`), wat nog open staat, en hoe te
> valideren. Wordt bijgewerkt zodra P5c, P6 en P7 klaar zijn.

## 1. Eerst bekijken (financieel of moeilijk terug te draaien)

1. **Rechtstreeks schrijven in de Prodis-DB, ook bij `legacy_master = PRODIS`** (2026-10-22 "Publicatiefase: P-V1..P-V5"): bewust aanvaard;
   de datasync draait die wijzigingen terug. Noodrem-vlag `catalogimport.publication.prodis.enabled` komt in P5c.
2. **Cent-afwijking door `double`** (2026-10-22 "P2a"): we volgen Prodis bit-identiek, dus 0,7 × 45 % = 0,31 i.p.v. 0,32 (decimaal). Dat is
   een afrondingsverschil van de rekenwijze, geen opslagprecisie (`PRECISION_LOSS` gaat over opslag) — maar wel een financiële afwijking van
   legacy om te beoordelen.
3. **`DERIVED_PRICE_DRIFT`** (2026-10-22 "P2b" punt 10, financieel): een item zonder eigen basis-/percentagewijziging schrijft geen afgeleide
   prijzen; scheve VKP/bruto in Prodis blijven scheef tot een echte wijziging. Wijkt af van Prg_1179 (dat altijd Prg_1185 aanroept).
4. **Nul en leeg behouden** (Q2, P-V5, P2b.3): expliciete 0 en leeg behouden de Prodis-waarde; basisprijs-UPDATE zonder `before_base_price`
   → KEEP + `EXPECTED_NOT_AVAILABLE`.
5. **UNCHANGED vs. APPLIED** (2026-10-22 "P5b"): een RESULT_UNKNOWN-item dat na herstel UNCHANGED eindigt, telt als PUBLISHED maar niet als
   APPLIED → teller APPLIED kan te laag uitvallen. Rapportagekeuze.
6. **Ontbrekende munt bij publicatie** (P2b.2): geweigerd (`CURRENCY_NOT_SUPPORTED_BY_PRODIS`), nooit EUR aangenomen. Sinds 2026-09-26
   ("Ontbrekende valuta = euro") legt de screening al EUR vast, dus dit raakt enkel oudere data; te bevestigen dat dat zo bedoeld is.
7. **Loskoppelen** (2026-10-22 "G2a"): koppeling verlegd + activatie van een kopie (uitzondering op "APPLY activeert nooit"); Liquibase 026
   wijzigt een constraint.
8. **Commit-foutklassen** (2026-10-22 "P5a" punt 1): enkel klasse 23/40 = niet toegepast; al de rest = RESULT_UNKNOWN (herziet design §2.2/§2.4).

## 2. Overige gevolgde aanbevelingen

| Thema | Blok in `decisions.md` | Keuze | Markering |
|---|---|---|---|
| Prijsberekening | P2a | Prijscode 1 behoudt bestaande %; bruto bij politiek n = letterlijke 1185-regel; `PERCENTAGE_MISSING`; purchase-tak niet geport | financieel |
| Prijsberekening | P2b.1, .4, .6, .9, .13 | Kortingscode NOT_USED → `""`; CREATE altijd `NOT_SENT`; KEEP zonder Prodis-basis → `PRODIS_ROW_SHAPE_UNSUPPORTED`; `PRICE_ROUNDING_DIFFERENCE` enkel bij echt toegepaste %; negatieve basisprijs = gewone waarde | financieel |
| Afgeleide prijzen | Q1 | VKP1..5 + `brut_price`/`brut_price_percentage`, geen `purchase_price` | financieel |
| Bronstaat | Q3 | Bij APPLIED/UNCHANGED bronwaarden vastleggen (`state_origin=PUBLISHED`) | contract/schema |
| Identiteit | P2b.5, .7, .12 | Geen leverancier → `ARTICLE_NOT_FOUND`; inactieve rij → vormfout; één actieve NL-omschrijving vereist; nieuwe codes | |
| Identiteit | S6 | Hoofdletters op leverancier/groep/referentie, niet op kortingscode (zoals legacy) | |
| Filters/mapping | S8 V1, V2, S8f | `KEEP_SOURCE` per mapping (CALC 7); `match_group` = EN-regel; lege `compareValue` enkel binnen een groep | data-integriteit |
| Schrijven | P5a.2–.10 | `select 1` vóór commit; transactiehandle met `setRollbackOnly`; Dao-excepties als RuntimeException | |
| Schrijven | P5b.1–.9 | Mutatie IN_PROGRESS tussenstadium; `request_json` met integriteitscontrole; `DESCRIPTION_TOO_LONG`; UNCHANGED teruggedraaid; foutcodes; fencing-token | |
| Leesbron | P1 | Ontbrekende rij → PRODIS; ontbrekende tabel/kolom/rechten → UNAVAILABLE | |
| Parallelrun | P2c.1–.10 | Na SIMULATED, buiten transactie; JSON-artefacten; tellers `null` bij overslaan | |
| Snapshot | P4a, P4b | Actief = `active_marker is not null`; `before_percentage` enkel in `snapshot_hash` (spec v2) | contract (hash) |
| Legacy-config | S1, S2, S4-vervolg, S5, S7 | Idempotentie tegen laatste ontvangst; `libraryCode`; secret alsnog bij UNCHANGED; `unverified:<hash>`; altijd sjabloon + gedeelde definitie | contract/security |
| Loskoppelen | G2a nieuwe interface | Nieuwe afwijkende interface activeert niets | |
| Rechten | 5B-7 V1–V4 | Cache 30 s; default PRODIS; System Admin = alles; verloren token → 401 | autorisatie |

## 3. Open punten bij de mens
- Behandelgevallen na loskoppelen volgen de kopie (`IssueCaseService.activeRevision`, `LinkBookmarkValueService`, `SourceStateDao`) — bevestigen.
- UNCHANGED vs. APPLIED-telling (P5b).
- `DERIVED_PRICE_DRIFT` aanvaarden of afgeleide prijzen altijd herberekenen.
- Cent-afwijking door `double` (P2a).
- Ontbrekende munt bij publicatie (P2b.2 t.o.v. 2026-09-26).
- P5c-restpunten (worden in P5c gebouwd): marker-/finished-checks, herstel IN_PROGRESS, herhalingsgrens, noodrem-vlag, `source-id`.
- Oudere punten: visuele controle frontend-ombouw; Prodis-wijziging (seed, `prodis.catalog-import.url`) reviewen; prestatietest ~1M regels;
  asynchrone screening.
- **Achterhaalde tekst in `openstaande-externe-punten.md`:** punt 1(a) noemt client `catalog-import` (lokaal is het `web_app`); punten 3 en 5
  (252 IMPORT/1179, Pervasive-adapter) zijn achterhaald door "Publicatiedoel herzien" (2026-10-20).

## 4. Validatie-checklist (in volgorde)

**(a) Volledige testronde** — `prodis-postgresql-1` draait; IntelliJ-autobuild uit.
- Backend: `.\scripts\test\run-full-tests.ps1 -Module Web` (log `Web\target\full-test.log`).
- Frontend: `npx vitest run` en `npx tsc --noEmit -p tsconfig.app.json` in `Frontend/`.
- Datasync (`C:\javaProject\ProdisDatasyncEngine`, branch `dev`): `./mvnw.cmd -pl pervasive-dao,sync-core -am test -Dtest="LegacyConfig*Tests,CanonicalJsonTests,CatalogImportPushClientTests" -Dsurefire.failIfNoSpecifiedTests=false`.
- Opt-in: `mvn -pl Web -am test "-Dtest=ProdisSchemaParityTest" -Dprodis.schemaParity=true -Dsurefire.failIfNoSpecifiedTests=false`.

**(b) Droge run datasync-job tegen een echte Zen** — eerst DDF-entries (`X$File`/`X$Field`) per tabel nakijken; `sync.jobs.LegacyConfigExportJob.enabled=true`,
`catalogimport.push.environment/dossier`; `POST /api/jobs/LegacyConfigExportJob/run?dryRun=true`. Verwacht: per interface enkel code + hashes in de log.

**(c) Lokale end-to-end legacy-config** — Keycloak `:9080` met client `prodis-datasync-catalogimport` in `prodis-realm.json` (Prodis-repo);
backend `local,demo`, frontend `:5173`. Push één interface (`?interface=<code>`) → Inrichting → Overname uit legacy → preview → overnemen →
eventueel loskoppelen. Verwacht: concept met `based_on`, geen secret in logs of bundel.

**(d) Prodis als rechtenbron** — Prodis-backend `:8080`, `CATALOG_PERMISSION_SOURCE=PRODIS`; Prodis-gebruiker met `catalogImport.*`.
Verwacht: `/me` toont de codes; intrekken werkt binnen 30 s; Prodis weg → 503; verlopen sessie → 401.

**(e) Parallelrun op een echte bundel** — rol `catalogimport_ro` (GRANTs in `openstaande-externe-punten.md` §8),
`CATALOG_PRODIS_DB_URL/USERNAME/PASSWORD`; bevroren bundel → simulatierun → `GET /publication-runs/{id}/parallel-run-artifact`.

**(f) Publiceren naar `prodis_pubtest`** — al aangemaakt (`scripts/test/setup-prodis-pubtest.sql`);
`CATALOG_PRODIS_DB_WRITE_URL=jdbc:postgresql://localhost:5432/prodis_pubtest`, gebruiker/wachtwoord `catalogimport_pub_test`; noodrem-vlag
aan (na P5c). Daarna eventueel naar een lokale kopie van `Prodis` (naam door de mens te bepalen). De echte `Prodis` nooit.

**(g) Visuele controle** — licht, donker, mini, mobiel; schermen Overname uit legacy, revisiebewerking (vaste waarden, keten, filtergroepen),
publicatietab.

## 5. Externe acties

| Actie | Wie | Bron |
|---|---|---|
| Client `prodis-datasync-catalogimport` (client-credentials, rol `legacy-config-push`) in `prodis-realm.json` | Prodis | `application-local.yml`; design legacy-config §3 |
| Productierealm: client, audience-mapper `account`, refresh tokens, zelfde issuer-host | DDA-infra | `openstaande-externe-punten.md` §1(b), §2 |
| Prodis-rol met `catalogImport.*` aan gebruikers | Prodis | idem §2 |
| Rol `catalogimport_ro` (P1-GRANTs) | DBA | idem §8 |
| Rol `catalogimport_pub` (`docs/ops/prodis-catalogimport-pub-role.sql`) | DBA | publicatie-direct §2.3 |
| Kolomnamen Zen-DDF verifiëren | Prodis/ERP-beheer | legacy-config-bundle-design §7 |
| Secrets-sleutelbeheer, SFTP-netwerk/allowlist | DDA-infra | `openstaande-externe-punten.md` §6–§7 |
| Prodis-wijziging (seed, `prodis.catalog-import.url`) reviewen en committen | Prodis-team | decisions 2026-10-20 |
