#!/usr/bin/env bash
# Wekelijkse hersteltest voor de dagelijkse pg_dump-back-up (D13: aantoonbaar herstelbaar,
# niet enkel "een bestand bestaat"). Zie docs/decisions.md "D13-uitvoering" en
# docs/design/backup-herstel-design.md.
#
# Restore gebeurt ALTIJD in een scratch-database (catalog_import_restoretest), NOOIT in de
# echte database. Na de restore volgen twee sanity-checks:
#   1. Liquibase-status van de scratch-database moet "up to date" zijn (geen pending changesets).
#   2. Een eenvoudige rijentelling (> 0) op een paar kerntabellen.
#
# Verbindingsgegevens: zelfde native libpq-variabelen als backup-postgres.sh
# (PGHOST/PGPORT/PGUSER/PGPASSWORD); PGDATABASE is hier NIET de doeldatabase (dat is altijd de
# vaste scratch-naam hieronder), maar wel nodig om de "postgres"-beheerconnectie te vinden.
#
# Gebruik:
#   restore-and-verify.sh [pad-naar-dumpbestand] [--keep]
#   Zonder argument: het meest recente bestand in $CATALOG_BACKUP_DIR/daily wordt gebruikt.
#   --keep: scratch-database NIET opruimen na afloop (handmatige inspectie).

set -euo pipefail

SCRATCH_DB="catalog_import_restoretest"
BACKUP_DIR="${CATALOG_BACKUP_DIR:-/var/backups/catalog-import}"
DAILY_DIR="$BACKUP_DIR/daily"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
RESOURCES_ROOT="$REPO_ROOT/Web/src/main/resources"
CHANGELOG_PATH="db/changelog/db.changelog-master.yaml"
KERNTABELLEN=("source_organisation" "import_definition" "import_definition_revision")

log() {
    printf '%s [restore-and-verify] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$1"
}

fail() {
    log "FAIL: $1"
    exit 1
}

KEEP=false
DUMP_FILE=""
for arg in "$@"; do
    case "$arg" in
        --keep) KEEP=true ;;
        *) DUMP_FILE="$arg" ;;
    esac
done

if [ -z "$DUMP_FILE" ]; then
    DUMP_FILE="$(ls -1t "$DAILY_DIR"/catalog_import_*.dump 2>/dev/null | head -n1 || true)"
    [ -n "$DUMP_FILE" ] || fail "geen dumpbestand opgegeven en geen bestand gevonden in $DAILY_DIR"
fi
[ -f "$DUMP_FILE" ] || fail "dumpbestand niet gevonden: $DUMP_FILE"

: "${PGUSER:?PGUSER is niet gezet (zie backup-postgres.sh voor de afleiding uit CATALOG_DB_USERNAME)}"

command -v pg_restore >/dev/null 2>&1 || fail "pg_restore niet gevonden in PATH"
command -v psql >/dev/null 2>&1 || fail "psql niet gevonden in PATH"
command -v javac >/dev/null 2>&1 || fail "javac niet gevonden in PATH (nodig voor de Liquibase-statuscontrole)"
command -v mvn >/dev/null 2>&1 || fail "mvn niet gevonden in PATH (nodig om de Liquibase-classpath op te bouwen)"

log "Gebruik dumpbestand: $DUMP_FILE"

# Scratch-database altijd fris: drop-if-exists, dan aanmaken. Nooit de echte database.
log "Scratch-database $SCRATCH_DB opnieuw aanmaken"
# De rol in PGUSER heeft hiervoor het CREATEDB-recht nodig (de applicatierol heeft dat normaal niet):
# draai deze hersteltest met een aparte beheerrol, of geef die rol CREATEDB.
if ! psql -d postgres -c "DROP DATABASE IF EXISTS $SCRATCH_DB;" >/dev/null \
   || ! psql -d postgres -c "CREATE DATABASE $SCRATCH_DB;" >/dev/null; then
    fail "scratch-database $SCRATCH_DB aanmaken mislukt (heeft PGUSER het CREATEDB-recht?)"
fi

cleanup() {
    if [ "$KEEP" = false ]; then
        log "Opruimen: scratch-database $SCRATCH_DB verwijderen (gebruik --keep om te bewaren)"
        psql -d postgres -c "DROP DATABASE IF EXISTS $SCRATCH_DB;" >/dev/null 2>&1 || true
    else
        log "Scratch-database $SCRATCH_DB blijft bestaan (--keep)"
    fi
}
trap cleanup EXIT

log "Restore uitvoeren in $SCRATCH_DB"
if ! pg_restore --clean --if-exists --no-owner --dbname="$SCRATCH_DB" "$DUMP_FILE"; then
    fail "pg_restore is mislukt"
fi

# --- Sanity-check 1: Liquibase-statuscontrole ---
# Er is geen liquibase-maven-plugin in dit project (enkel liquibase-core als runtime-dependency,
# zie Web/pom.xml en application.yml). Deze check compileert daarom een kleine, losstaande
# helperklasse tegen de classpath die het project zelf al aanlevert (dependency:build-classpath),
# in plaats van een nieuwe Maven-plugin toe te voegen.
log "Liquibase-classpath opbouwen via Maven"
LB_CP_FILE="$(mktemp)"
( cd "$REPO_ROOT" && mvn -q -pl Web -am dependency:build-classpath -Dmdep.outputFile="$LB_CP_FILE" )
LB_CP="$(cat "$LB_CP_FILE")"
LB_CLASSES_DIR="$(mktemp -d)"
javac -cp "$LB_CP" -d "$LB_CLASSES_DIR" "$SCRIPT_DIR/LiquibaseStatusCheck.java"

log "Liquibase-status controleren tegen $SCRATCH_DB"
# Classpath-scheidingsteken: ':' op Linux/macOS, ';' wanneer dit script onder Git Bash op Windows draait.
PATHSEP=":"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) PATHSEP=";" ;; esac
if ( cd "$LB_CLASSES_DIR" && java -cp ".${PATHSEP}${LB_CP}" LiquibaseStatusCheck \
        "jdbc:postgresql://${PGHOST:-localhost}:${PGPORT:-5432}/$SCRATCH_DB" "$PGUSER" "${PGPASSWORD:-}" \
        "$RESOURCES_ROOT" "$CHANGELOG_PATH" ); then
    log "PASS: Liquibase-status is up to date"
else
    fail "Liquibase-status meldt pending changesets of een fout tegen de herstelde database"
fi

# --- Sanity-check 2: rijentelling op kerntabellen ---
for table in "${KERNTABELLEN[@]}"; do
    count="$(psql -d "$SCRATCH_DB" -t -A -c "select count(*) from $table;")"
    if [ "$count" -gt 0 ]; then
        log "PASS: $table bevat $count rijen"
    else
        fail "$table bevat 0 rijen na restore (verwacht > 0)"
    fi
done

log "PASS: hersteltest geslaagd voor $DUMP_FILE"
exit 0
