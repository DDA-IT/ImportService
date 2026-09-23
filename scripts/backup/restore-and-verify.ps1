# Wekelijkse hersteltest voor de dagelijkse pg_dump-back-up (D13: aantoonbaar herstelbaar,
# niet enkel "een bestand bestaat"). PowerShell-equivalent van restore-and-verify.sh, voor
# lokaal oefenen op Windows. Zie docs/decisions.md "D13-uitvoering" en
# docs/design/backup-herstel-design.md.
#
# Restore gebeurt ALTIJD in een scratch-database (catalog_import_restoretest), NOOIT in de
# echte database. Na de restore volgen twee sanity-checks:
#   1. Liquibase-status van de scratch-database moet "up to date" zijn (geen pending changesets).
#   2. Een eenvoudige rijentelling (> 0) op een paar kerntabellen.
#
# Gebruik:
#   restore-and-verify.ps1 [-DumpFile <pad>] [-Keep]
#   Zonder -DumpFile: het meest recente bestand in $env:CATALOG_BACKUP_DIR\daily wordt gebruikt.
#   -Keep: scratch-database NIET opruimen na afloop (handmatige inspectie).

param(
    [string]$DumpFile,
    [switch]$Keep
)

$ErrorActionPreference = "Stop"

$ScratchDb = "catalog_import_restoretest"
$BackupDir = if ($env:CATALOG_BACKUP_DIR) { $env:CATALOG_BACKUP_DIR } else { "C:\backups\catalog-import" }
$DailyDir = Join-Path $BackupDir "daily"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Resolve-Path (Join-Path $ScriptDir "..\..")
$ResourcesRoot = Join-Path $RepoRoot "Web\src\main\resources"
$ChangelogPath = "db/changelog/db.changelog-master.yaml"
$KernTabellen = @("source_organisation", "import_definition", "import_definition_revision")

function Write-Log {
    param([string]$Message)
    $stamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    Write-Output "$stamp [restore-and-verify] $Message"
}

function Fail {
    param([string]$Message)
    Write-Log "FAIL: $Message"
    exit 1
}

if (-not $DumpFile) {
    $latest = Get-ChildItem -Path $DailyDir -Filter "catalog_import_*.dump" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $latest) {
        Fail "geen dumpbestand opgegeven en geen bestand gevonden in $DailyDir"
    }
    $DumpFile = $latest.FullName
}
if (-not (Test-Path $DumpFile)) {
    Fail "dumpbestand niet gevonden: $DumpFile"
}

if (-not $env:PGUSER) {
    Fail "PGUSER is niet gezet (zie backup-postgres.ps1 voor de afleiding uit CATALOG_DB_USERNAME)"
}

foreach ($tool in @("pg_restore", "psql", "javac", "mvn")) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        Fail "$tool niet gevonden in PATH"
    }
}

Write-Log "Gebruik dumpbestand: $DumpFile"

# Scratch-database altijd fris: drop-if-exists, dan aanmaken. Nooit de echte database.
Write-Log "Scratch-database $ScratchDb opnieuw aanmaken"
# De rol in PGUSER heeft hiervoor het CREATEDB-recht nodig (de applicatierol heeft dat normaal niet):
# draai deze hersteltest met een aparte beheerrol, of geef die rol CREATEDB.
& psql -d postgres -c "DROP DATABASE IF EXISTS $ScratchDb;" | Out-Null
$dropOk = ($LASTEXITCODE -eq 0)
if ($dropOk) {
    & psql -d postgres -c "CREATE DATABASE $ScratchDb;" | Out-Null
}
if (-not $dropOk -or $LASTEXITCODE -ne 0) {
    Fail "scratch-database $ScratchDb aanmaken mislukt (heeft PGUSER het CREATEDB-recht?)"
}

try {
    Write-Log "Restore uitvoeren in $ScratchDb"
    & pg_restore --clean --if-exists --no-owner --dbname=$ScratchDb $DumpFile
    if ($LASTEXITCODE -ne 0) {
        Fail "pg_restore is mislukt"
    }

    # --- Sanity-check 1: Liquibase-statuscontrole ---
    # Er is geen liquibase-maven-plugin in dit project (enkel liquibase-core als runtime-dependency,
    # zie Web/pom.xml en application.yml). Deze check compileert daarom een kleine, losstaande
    # helperklasse tegen de classpath die het project zelf al aanlevert (dependency:build-classpath),
    # in plaats van een nieuwe Maven-plugin toe te voegen.
    Write-Log "Liquibase-classpath opbouwen via Maven"
    $lbCpFile = New-TemporaryFile
    Push-Location $RepoRoot
    try {
        & mvn -q -pl Web -am dependency:build-classpath "-Dmdep.outputFile=$($lbCpFile.FullName)"
    } finally {
        Pop-Location
    }
    $lbCp = Get-Content $lbCpFile.FullName -Raw
    $lbClassesDir = Join-Path ([System.IO.Path]::GetTempPath()) ([System.Guid]::NewGuid().ToString())
    New-Item -ItemType Directory -Path $lbClassesDir | Out-Null
    & javac -cp $lbCp -d $lbClassesDir (Join-Path $ScriptDir "LiquibaseStatusCheck.java")

    Write-Log "Liquibase-status controleren tegen $ScratchDb"
    $pgHost = if ($env:PGHOST) { $env:PGHOST } else { "localhost" }
    $pgPort = if ($env:PGPORT) { $env:PGPORT } else { "5432" }
    $jdbcUrl = "jdbc:postgresql://${pgHost}:${pgPort}/$ScratchDb"

    Push-Location $lbClassesDir
    try {
        & java -cp ".;$lbCp" LiquibaseStatusCheck $jdbcUrl $env:PGUSER $env:PGPASSWORD $ResourcesRoot $ChangelogPath
        $lbExit = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($lbExit -eq 0) {
        Write-Log "PASS: Liquibase-status is up to date"
    } else {
        Fail "Liquibase-status meldt pending changesets of een fout tegen de herstelde database"
    }

    # --- Sanity-check 2: rijentelling op kerntabellen ---
    foreach ($table in $KernTabellen) {
        $count = (& psql -d $ScratchDb -t -A -c "select count(*) from $table;").Trim()
        if ([int]$count -gt 0) {
            Write-Log "PASS: $table bevat $count rijen"
        } else {
            Fail "$table bevat 0 rijen na restore (verwacht > 0)"
        }
    }

    Write-Log "PASS: hersteltest geslaagd voor $DumpFile"
    exit 0
} finally {
    if (-not $Keep) {
        Write-Log "Opruimen: scratch-database $ScratchDb verwijderen (gebruik -Keep om te bewaren)"
        & psql -d postgres -c "DROP DATABASE IF EXISTS $ScratchDb;" 2>$null | Out-Null
    } else {
        Write-Log "Scratch-database $ScratchDb blijft bestaan (-Keep)"
    }
}
