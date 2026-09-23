# Dagelijkse pg_dump-back-up van de CatalogImport-database (D13: RPO <= 24u, RTO <= 4u).
# PowerShell-equivalent van backup-postgres.sh, bedoeld voor lokaal/demo-gebruik op Windows
# (bv. via Task Scheduler). Zie docs/decisions.md "D13" en "D13-uitvoering" en
# docs/design/backup-herstel-design.md.
#
# Draait NIET als onderdeel van de Spring Boot-applicatie: dit is een losstaand script.
#
# Verbindingsgegevens: dit script gebruikt de NATIEVE libpq-omgevingsvariabelen van
# pg_dump/pg_restore, niet de Spring-specifieke variabelen uit README.md/application.yml.
# Vertaal ze zelf op de operatorhost:
#   $env:PGHOST     <-> afgeleid uit CATALOG_DB_URL (jdbc:postgresql://<host>:<port>/<database>)
#   $env:PGPORT     <-> afgeleid uit CATALOG_DB_URL
#   $env:PGDATABASE <-> afgeleid uit CATALOG_DB_URL
#   $env:PGUSER     <-> CATALOG_DB_USERNAME
#   $env:PGPASSWORD <-> CATALOG_DB_PASSWORD (of gebruik een .pgpass-bestand i.p.v. deze variabele)
#
# Extra variabele, specifiek voor dit script:
#   $env:CATALOG_BACKUP_DIR  doelmap voor de back-ups (default hieronder)
#
# Het wachtwoord wordt NOOIT als CLI-argument doorgegeven en NOOIT gelogd; enkel bestandsnaam,
# bestandsgrootte en duur komen in de logregels.

$ErrorActionPreference = "Stop"

function Write-Log {
    param([string]$Message)
    $stamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    Write-Output "$stamp [backup-postgres] $Message"
}

function Fail {
    param([string]$Message)
    Write-Log "FOUT: $Message"
    exit 1
}

if (-not $env:PGDATABASE) {
    Fail "PGDATABASE is niet gezet (zie commentaarblok bovenaan voor de afleiding uit CATALOG_DB_URL)"
}
if (-not $env:PGUSER) {
    Fail "PGUSER is niet gezet (zie commentaarblok bovenaan, komt overeen met CATALOG_DB_USERNAME)"
}

if (-not (Get-Command pg_dump -ErrorAction SilentlyContinue)) {
    Fail "pg_dump niet gevonden in PATH"
}
if (-not (Get-Command pg_restore -ErrorAction SilentlyContinue)) {
    Fail "pg_restore niet gevonden in PATH"
}

$backupDir = if ($env:CATALOG_BACKUP_DIR) { $env:CATALOG_BACKUP_DIR } else { "C:\backups\catalog-import" }
$dailyDir = Join-Path $backupDir "daily"
$weeklyDir = Join-Path $backupDir "weekly"
$dailyRetention = 7
$weeklyRetention = 5

New-Item -ItemType Directory -Force -Path $dailyDir | Out-Null
New-Item -ItemType Directory -Force -Path $weeklyDir | Out-Null

$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$filename = "catalog_import_$($env:PGDATABASE)_$timestamp.dump"
$filepath = Join-Path $dailyDir $filename

Write-Log "Start back-up van database '$($env:PGDATABASE)' naar $filepath"
$startTime = Get-Date

# -Fc: custom format, vereist voor pg_restore --list/--clean; comprimeert standaard.
& pg_dump --dbname=$env:PGDATABASE -Fc -f $filepath
if ($LASTEXITCODE -ne 0) {
    Remove-Item -Force -ErrorAction SilentlyContinue $filepath
    Fail "pg_dump is mislukt, geen halfklaar bestand achtergelaten"
}

# Goedkope corruptiecontrole: kan het net gemaakte bestand zijn eigen inhoudsopgave tonen?
& pg_restore --list $filepath | Out-Null
if ($LASTEXITCODE -ne 0) {
    Remove-Item -Force -ErrorAction SilentlyContinue $filepath
    Fail "pg_restore --list kon $filename niet lezen (mogelijk corrupt dumpbestand), bestand verwijderd"
}

$duration = [int]((Get-Date) - $startTime).TotalSeconds
$filesize = (Get-Item $filepath).Length

Write-Log "Back-up geslaagd: bestand=$filename grootte=${filesize}B duur=${duration}s"

# Retentie dagelijks: enkel de laatste $dailyRetention bestanden bewaren.
$dailyFiles = Get-ChildItem -Path $dailyDir -Filter "catalog_import_*.dump" | Sort-Object LastWriteTime -Descending
if ($dailyFiles.Count -gt $dailyRetention) {
    $dailyFiles | Select-Object -Skip $dailyRetention | ForEach-Object {
        Write-Log "Retentie: verwijder oude dagelijkse back-up $($_.Name)"
        Remove-Item -Force $_.FullName
    }
}

# Wekelijkse laag: op zondag een kopie bewaren, met eigen kortere retentie.
if ((Get-Date).DayOfWeek -eq [System.DayOfWeek]::Sunday) {
    $weeklyPath = Join-Path $weeklyDir $filename
    Copy-Item -Force $filepath $weeklyPath
    Write-Log "Zondag: kopie bewaard als wekelijkse back-up $(Split-Path -Leaf $weeklyPath)"

    $weeklyFiles = Get-ChildItem -Path $weeklyDir -Filter "catalog_import_*.dump" | Sort-Object LastWriteTime -Descending
    if ($weeklyFiles.Count -gt $weeklyRetention) {
        $weeklyFiles | Select-Object -Skip $weeklyRetention | ForEach-Object {
            Write-Log "Retentie: verwijder oude wekelijkse back-up $($_.Name)"
            Remove-Item -Force $_.FullName
        }
    }
}

Write-Log "Klaar."
