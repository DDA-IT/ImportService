# Ontwerp back-up-/hersteltaak — dagelijkse pg_dump + wekelijkse hersteltest

Bron: `docs/decisions.md` — "2026-09-23 — D13: RPO/RTO-productiedoel voor de importcontrolelaag"
en "2026-09-23 — D13-uitvoering: ontwerp back-up-/hersteltaak (losstaande scripts)". Dit document
beschrijft dat besluit concreet; het is geen nieuwe architectuurkeuze.

## 0. Bewust géén Spring Boot-code

Back-up is infrastructuur, geen applicatielogica. Er komt daarom **geen scheduled `@Component`**
in de Web-module: de scripts staan los, in `scripts/backup/`, buiten de Maven-reactor
(`Domain`/`Dao`/`Service`/`Web`) en worden door de OS-scheduler aangeroepen (cron in productie,
Task Scheduler lokaal/demo). Dit houdt de applicatie zelf vrij van een verantwoordelijkheid die
niets met screening, mutaties of publicatie te maken heeft, en laat het back-upregime volledig
onafhankelijk van een applicatie-herstart of -deploy draaien.

## 1. Doel: RPO ≤ 24 uur, RTO ≤ 4 uur

Vastgelegd in D13: BA1's doel (`RPO ≤ 24u`, `RTO ≤ 4u`) wordt het productiedoel, niet BA2's
strengere `RPO ≤ 15 min`. Een dagelijkse `pg_dump` is voldoende om het RPO-doel te halen zonder de
aanzienlijk complexere WAL-archivering/point-in-time recovery die 15 minuten zou vereisen. Er
bestond vóór dit ontwerp geen enkel back-upmechanisme voor de CatalogImport-Postgres-database.

## 2. Regime

- **Dagelijks, 02:00**: `backup-postgres.sh`/`.ps1` maakt een `pg_dump -Fc` van de volledige
  database naar `daily/`, gevolgd door een goedkope corruptiecontrole (`pg_restore --list`).
- **Wekelijks, zondag**: dezelfde dagelijkse back-up wordt bovendien gekopieerd naar `weekly/`
  (geen aparte dump — één I/O-belasting per dag volstaat, de wekelijkse laag is puur een langer
  bewaarde kopie).
- **Wekelijks, zondag 03:00** (één uur na de back-up): `restore-and-verify.sh`/`.ps1` herstelt de
  meest recente dagelijkse dump in een scratch-database en bewijst dat ze bruikbaar is
  (Liquibase-status + rijentelling). Dit is het verschil tussen "er staat een bestand" en "dit
  bestand herstelt aantoonbaar een werkende database" — zonder hersteltest is een back-up een
  aanname, geen garantie.

## 3. Retentiebeleid (nieuw voorstel, geen brondocument geeft een getal)

| Laag | Bewaard | Motivatie |
| --- | --- | --- |
| `daily/` | laatste 7 | dekt het RPO-venster van 24u ruim; een week aan dagelijkse back-ups vangt ook "we ontdekken het probleem pas na een paar dagen" op |
| `weekly/` | laatste 5 | circa een maand terug kunnen zonder de schijfkost van 30+ dagelijkse dumps te dragen |

Dit is een eigen, veel kortere levenscyclus dan de 7-jarige applicatiedata-auditretentie
(businessanalyse §16.7): back-upbestanden zijn een operationeel hersteldoel, geen boekhoudkundig
of wettelijk bewijsstuk. Beide niet met elkaar verwarren.

## 4. Verbindingsgegevens

De scripts gebruiken de **native libpq-omgevingsvariabelen** (`PGHOST`, `PGPORT`, `PGDATABASE`,
`PGUSER`, `PGPASSWORD`), niet de Spring-specifieke `CATALOG_DB_URL`/`CATALOG_DB_USERNAME`/
`CATALOG_DB_PASSWORD` uit `README.md`/`application.yml` — `pg_dump`/`pg_restore`/`psql` kennen geen
JDBC-URL's. De operator leidt ze af: host/poort/database uit
`jdbc:postgresql://<host>:<port>/<database>`, gebruiker en wachtwoord blijven gelijk. Het
wachtwoord wordt nooit als CLI-argument doorgegeven (zichtbaar in `ps`/Taakbeheer) en nooit
gelogd; gebruik op de operatorhost bij voorkeur `~/.pgpass` in plaats van `PGPASSWORD` in een
gedeeld env-bestand.

`CATALOG_BACKUP_DIR` is een nieuwe variabele, enkel voor deze scripts (default
`/var/backups/catalog-import` op Linux, `C:\backups\catalog-import` op Windows).

## 5. Hersteltest — waarom een scratch-database en twee sanity-checks

Restore gebeurt **nooit** in de echte `catalog_import`-database (dat zou een gewone databaseherstel-
actie in een productietaak veranderen in een risico op zichzelf), maar in een vaste scratch-naam
`catalog_import_restoretest`, die het script bij elke run drop-if-exists en opnieuw aanmaakt.

Twee sanity-checks bewijzen dat de herstelde database bruikbaar is, niet enkel "leeg maar
consistent":

1. **Liquibase-status.** Dit project heeft geen `liquibase-maven-plugin` (enkel de
   `liquibase-core`-runtimedependency die Spring Boot bij het opstarten gebruikt via
   `spring.liquibase.change-log`, zie `Web/pom.xml`/`application.yml`). In plaats van een nieuwe
   Maven-plugin toe te voegen — dat zou `Web/pom.xml` wijzigen, buiten de scope van deze
   back-uptaak — compileert `restore-and-verify.sh`/`.ps1` een kleine, losstaande helperklasse
   (`LiquibaseStatusCheck.java`, in dezelfde map) tegen de classpath die
   `mvn -pl Web -am dependency:build-classpath` oplevert: exact dezelfde `liquibase-core`- en
   PostgreSQL-driverversie als de applicatie zelf gebruikt, zonder de Maven-module zelf aan te
   raken. De klasse gebruikt de programmatische Liquibase-API (`Liquibase#listUnrunChangeSets`)
   rechtstreeks tegen het changelog-bestand op schijf (`db/changelog/db.changelog-master.yaml`) en
   verwacht een lege lijst (geen pending changesets).
2. **Rijentelling.** Eenvoudige `count(*) > 0` op `source_organisation`, `import_definition` en
   `import_definition_revision` (changeset 001) — de kerntabellen van de importcontrolelaag.

**Rechten:** de hersteltest maakt de scratch-database aan en verwijdert ze weer, dus de rol in
`PGUSER` heeft het `CREATEDB`-recht nodig. De applicatierol (`catalog_import`) heeft dat bewust
niet en krijgt het ook niet. Daarom bestaat `scripts/backup/create-restore-role.sql`: een
databasebeheerder voert dat eenmalig uit en maakt de rol `catalog_import_restore` (LOGIN +
CREATEDB) aan; het wachtwoord gaat mee als psql-variabele, er staat geen geheim in het bestand:

```
psql -d postgres -v restore_password='<geheim>' -f scripts/backup/create-restore-role.sql
```

De back-up blijft draaien als `catalog_import` (`PGUSER=catalog_import`); enkel de cron-/Taakplanner-
omgeving van de hersteltest zet `PGUSER=catalog_import_restore` en het bijhorende `PGPASSWORD`
(of een `.pgpass`-regel). Omdat de restore met `--no-owner` gebeurt en de scratch-database eigendom
is van de herstelrol, zijn geen extra rechten nodig. Zonder CREATEDB stopt het script met een
duidelijke `FAIL`-regel.

Bij falen van een van beide checks stopt het script met exitcode 1 en een duidelijke `FAIL`-regel,
geschikt voor cron-/Taakplanner-monitoring op basis van de exitcode. Standaard ruimt het script de
scratch-database op na afloop; `--keep`/`-Keep` laat ze staan voor handmatige inspectie.

## 6. Planning

Productie (Linux/cron):

```
0 2 * * *  /opt/catalog-import/scripts/backup/backup-postgres.sh >> /var/log/catalog-import/backup.log 2>&1
0 3 * * 0  /opt/catalog-import/scripts/backup/restore-and-verify.sh >> /var/log/catalog-import/restore-test.log 2>&1
```

Lokaal/demo (Windows Task Scheduler):

```
schtasks /create /tn "CatalogImport-Backup" /tr "powershell -File C:\catalog-import\scripts\backup\backup-postgres.ps1" /sc daily /st 02:00
schtasks /create /tn "CatalogImport-RestoreTest" /tr "powershell -File C:\catalog-import\scripts\backup\restore-and-verify.ps1" /sc weekly /d SUN /st 03:00
```

Vervang de paden door de daadwerkelijke installatielocatie; zet de `PG*`-omgevingsvariabelen in de
omgeving van de geplande taak (Task Scheduler: "Add new"-omgevingsvariabele of een wrapper-script;
cron: in `/etc/environment`, een `.env`-bestand dat het script inleest, of user-crontab-omgeving).

## 7. Aannames en bewust uitgestelde verbeteringen

- Productie is Linux/cron (aanname uit D13-uitvoering); enkel de scheduling-laag verandert als dat
  niet klopt, niet de scripts zelf.
- Geen secrets-manager-integratie: wachtwoordopslag via `.pgpass`/omgevingsvariabele op de host.
- Retentiegetallen 7/5 zijn een redelijk minimum, aan te passen zonder herontwerp.
- Een sterkere sidecar-countvergelijking (bv. rijentelling van de scratch-database vergelijken met
  een op het back-upmoment vastgelegde telling van de echte database) is een expliciet uitgestelde
  verbetering — vandaag bewijst de hersteltest "een werkende database met data", niet "exact
  evenveel rijen als bij het maken van de back-up".
- Puntherstel (WAL-archivering, RPO ≤ 15 min) blijft uitgesteld tot de operationele praktijk erom
  vraagt (D13).
