# Volledige (of gerichte) testronde van een module (default Web, incl. -am) tegen een AFGESCHERMD PostgreSQL-schema.
#
# Waarom een eigen schema: de tests draaien tegen PostgreSQL (profiel local) en een deel ervan (o.a.
# ImportControlSchemaTest, ScreeningSchemaTest) gebruikt vaste codes zonder op te ruimen, dus slaagt alleen op een
# lege database. Een eigen schema geeft elke ronde een schone lei zonder de echte data in `public` aan te raken.
# Zie ook README.md ("Gerichte tests") en docs/decisions.md.
#
# Waarom de contextcache klein is: elke Spring-testcontext houdt zijn eigen verbindingspool open en Spring bewaart er
# standaard tot 32. Op een server met weinig verbindingen (max_connections = 50 lokaal) geeft dat
# "remaining connection slots are reserved for roles with the SUPERUSER attribute". Daarom: kleine pools
# en een contextcache van 2 (trager, maar betrouwbaar).
#
# Gebruik (vanuit de projectroot):
#   .\scripts\test\run-full-tests.ps1                                   # alle tests, schoon schema ci_fulltest
#   .\scripts\test\run-full-tests.ps1 -Tests BundleHttpTest,SecurityHttpTest
#   .\scripts\test\run-full-tests.ps1 -Module Service                # Service (+ Domain/Dao) i.p.v. Web
#   .\scripts\test\run-full-tests.ps1 -NoReset                          # schema NIET leegmaken
#   .\scripts\test\run-full-tests.ps1 -DropAfter                        # schema na afloop verwijderen
#
# Verbindingsgegevens (zelfde variabelen als application.yml; het wachtwoord wordt nooit gelogd):
#   CATALOG_DB_URL       default jdbc:postgresql://localhost:5432/catalog_import
#   CATALOG_DB_USERNAME  default catalog_import
#   CATALOG_DB_PASSWORD  default catalog_import
#
# Het script draait NOOIT tegen schema public en verandert geen code of configuratie.

[CmdletBinding()]
param(
    [string]$Schema = "ci_fulltest",
    [ValidateSet("Domain", "Dao", "Service", "Web")][string]$Module = "Web",
    [string[]]$Tests = @(),
    [int]$CacheSize = 2,
    [int]$PoolSize = 3,
    [switch]$NoReset,
    [switch]$DropAfter
)

$ErrorActionPreference = "Stop"

function Write-Log {
    param([string]$Message)
    Write-Output ("{0} [run-full-tests] {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $Message)
}

function Fail {
    param([string]$Message)
    Write-Log "FOUT: $Message"
    exit 1
}

if ($Schema -notmatch '^[a-z][a-z0-9_]*$' -or $Schema -eq "public" -or $Schema -like "pg_*") {
    Fail "schemanaam '$Schema' is niet toegestaan (alleen [a-z][a-z0-9_]*, nooit public)."
}

$root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $root

$baseUrl = if ($env:CATALOG_DB_URL) { $env:CATALOG_DB_URL } else { "jdbc:postgresql://localhost:5432/catalog_import" }
$user = if ($env:CATALOG_DB_USERNAME) { $env:CATALOG_DB_USERNAME } else { "catalog_import" }
$password = if ($env:CATALOG_DB_PASSWORD) { $env:CATALOG_DB_PASSWORD } else { "catalog_import" }
if ($baseUrl -match '[?&]currentSchema=') {
    Fail "CATALOG_DB_URL bevat al een currentSchema; gebruik de basis-URL zonder schema (het script voegt het toe)."
}
$testUrl = "$baseUrl" + $(if ($baseUrl.Contains("?")) { "&" } else { "?" }) + "currentSchema=$Schema"

$jar = Get-ChildItem (Join-Path $env:USERPROFILE ".m2\repository\org\postgresql\postgresql") -Recurse -Filter "postgresql-*.jar" `
    -ErrorAction SilentlyContinue | Where-Object { $_.Name -notmatch 'sources|javadoc' } |
    Sort-Object Name | Select-Object -Last 1 -ExpandProperty FullName
if (-not $jar) {
    Fail "PostgreSQL-driver niet gevonden in ~/.m2; draai eerst 'mvn -q -pl Web -am install -DskipTests'."
}

$helper = Join-Path $PSScriptRoot "ResetTestSchema.java"
function Invoke-SchemaAction {
    param([string]$Action)
    & java -cp $jar $helper $baseUrl $user $password $Schema $Action
    if ($LASTEXITCODE -ne 0) { Fail "schema-actie '$Action' mislukt (heeft de gebruiker CREATE op de database?)." }
}

if ($Module -eq "Domain") {
    Write-Log "module Domain: geen database nodig, schema-reset overgeslagen"
} elseif ($NoReset) {
    Write-Log "schema '$Schema' blijft bestaan (-NoReset)"
    Invoke-SchemaAction "create"
} else {
    Write-Log "schema '$Schema' leegmaken"
    Invoke-SchemaAction "reset"
}

$argLine = "-Dspring.datasource.url=$testUrl " +
    "-Dspring.datasource.hikari.maximum-pool-size=$PoolSize " +
    "-Dspring.datasource.hikari.minimum-idle=0 " +
    "-Dspring.test.context.cache.maxSize=$CacheSize"

$mvnArgs = @("-pl", $Module, "-am", "-fae", "test", "-DargLine=$argLine")
if ($Tests.Count -gt 0) {
    $mvnArgs += "-Dtest=$($Tests -join ',')"
    $mvnArgs += "-Dsurefire.failIfNoSpecifiedTests=false"
    Write-Log ("gerichte run: " + ($Tests -join ", "))
} else {
    Write-Log "volledige run van module $Module (kan lang duren: elke Spring-context start opnieuw op)"
}

$logFile = Join-Path $root "$Module\target\full-test.log"
New-Item -ItemType Directory -Force (Split-Path $logFile) | Out-Null
$started = Get-Date
# Windows PowerShell 5.1 maakt van elke stderr-regel van een extern commando een ErrorRecord; onder "Stop" breekt
# een gewone waarschuwing van Maven/Mockito dan het script af. Daarom hier tijdelijk "Continue".
$previousPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
& mvn @mvnArgs 2>&1 | ForEach-Object { "$_" } | Out-File -FilePath $logFile -Encoding utf8
$mavenExit = $LASTEXITCODE
$ErrorActionPreference = $previousPreference
$minutes = [math]::Round(((Get-Date) - $started).TotalMinutes, 1)

# Samenvatting uit de surefire-rapporten van alle modules in de reactor. Falende tests laten Maven nu falen
# (geen failure.ignore meer); '-fae' laat onafhankelijke modules wel doorlopen. Modules die in de Reactor Summary
# SKIPPED staan (bv. omdat een upstream-module faalde) hebben dus NIET getest en worden expliciet gemeld.
$totalRun = 0; $totalFail = 0; $totalErr = 0; $bad = @(); $reportCount = 0
foreach ($mod in @("Domain", "Dao", "Service", "Web")) {
    $dir = Join-Path $root "$mod\target\surefire-reports"
    $reports = @(Get-ChildItem $dir -Filter "*.txt" -ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTime -ge $started })
    $run = 0; $fail = 0; $err = 0
    foreach ($report in $reports) {
        $line = (Get-Content $report.FullName -TotalCount 4)[3]
        if ($line -match 'Tests run: (\d+), Failures: (\d+), Errors: (\d+)') {
            $run += [int]$Matches[1]; $fail += [int]$Matches[2]; $err += [int]$Matches[3]
            if ([int]$Matches[2] + [int]$Matches[3] -gt 0) {
                $bad += ("{0}/{1}: {2} fout(en)/falend van {3}" -f $mod, ($report.BaseName -replace '^.*\.', ''), ([int]$Matches[2] + [int]$Matches[3]), $Matches[1])
            }
        }
    }
    $reportCount += $reports.Count
    $totalRun += $run; $totalFail += $fail; $totalErr += $err
    $skipped = Select-String -Path $logFile -Pattern ("^\[INFO\] .*\b" + $mod + "\b.* SKIPPED") -ErrorAction SilentlyContinue
    if ($skipped) {
        Write-Log ("{0}: SKIPPED in de Reactor Summary (NIET getest)" -f $mod)
    } else {
        Write-Log ("{0}: klassen {1}; tests {2}; falend {3}; fouten {4}" -f $mod, $reports.Count, $run, $fail, $err)
    }
}

Write-Log "klaar in $minutes min (mvn exit $mavenExit); log: $logFile"
Write-Log "totaal: klassen $reportCount; tests: $totalRun; falend: $totalFail; fouten: $totalErr"
if ($bad.Count -gt 0) {
    Write-Log "klassen met problemen:"
    $bad | ForEach-Object { Write-Output "  - $_" }
    $slots = Select-String -Path $logFile -Pattern "remaining connection slots" -SimpleMatch -ErrorAction SilentlyContinue
    if ($slots) {
        Write-Log "LET OP: $($slots.Count)x 'remaining connection slots': de verbindingen raakten op. Verklein -CacheSize/-PoolSize of sluit andere clients."
    }
}

if ($DropAfter) {
    Write-Log "schema '$Schema' verwijderen (-DropAfter)"
    Invoke-SchemaAction "drop"
}

if ($totalFail + $totalErr -gt 0 -or $mavenExit -ne 0) { exit 1 }
exit 0
