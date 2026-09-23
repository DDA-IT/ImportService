#!/usr/bin/env bash
# Dagelijkse pg_dump-back-up van de CatalogImport-database (D13: RPO <= 24u, RTO <= 4u).
# Zie docs/decisions.md "D13" en "D13-uitvoering" en docs/design/backup-herstel-design.md.
#
# Draait NIET als onderdeel van de Spring Boot-applicatie: dit is een losstaand cron-script
# voor productie/Linux, bedoeld om buiten kantooruren te draaien (voorbeeld: elke nacht 02:00).
#
# Verbindingsgegevens: dit script gebruikt de NATIEVE libpq-omgevingsvariabelen van
# pg_dump/pg_restore, niet de Spring-specifieke variabelen uit README.md/application.yml.
# Vertaal ze zelf op de operatorhost:
#   PGHOST     <-> afgeleid uit CATALOG_DB_URL (jdbc:postgresql://<host>:<port>/<database>)
#   PGPORT     <-> afgeleid uit CATALOG_DB_URL
#   PGDATABASE <-> afgeleid uit CATALOG_DB_URL
#   PGUSER     <-> CATALOG_DB_USERNAME
#   PGPASSWORD <-> CATALOG_DB_PASSWORD (of gebruik ~/.pgpass i.p.v. deze variabele in het env-bestand)
#
# Extra variabele, specifiek voor dit script:
#   CATALOG_BACKUP_DIR  doelmap voor de back-ups (default hieronder)
#
# Het wachtwoord wordt NOOIT als CLI-argument doorgegeven en NOOIT gelogd; enkel bestandsnaam,
# bestandsgrootte en duur komen in de logregels.

set -euo pipefail

BACKUP_DIR="${CATALOG_BACKUP_DIR:-/var/backups/catalog-import}"
DAILY_DIR="$BACKUP_DIR/daily"
WEEKLY_DIR="$BACKUP_DIR/weekly"
DAILY_RETENTION=7
WEEKLY_RETENTION=5

log() {
    printf '%s [backup-postgres] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$1"
}

fail() {
    log "FOUT: $1"
    exit 1
}

# Vroege, duidelijke controle in plaats van een cryptische libpq-foutmelding verderop.
: "${PGDATABASE:?PGDATABASE is niet gezet (zie commentaarblok bovenaan voor de afleiding uit CATALOG_DB_URL)}"
: "${PGUSER:?PGUSER is niet gezet (zie commentaarblok bovenaan, komt overeen met CATALOG_DB_USERNAME)}"

command -v pg_dump >/dev/null 2>&1 || fail "pg_dump niet gevonden in PATH"
command -v pg_restore >/dev/null 2>&1 || fail "pg_restore niet gevonden in PATH"

mkdir -p "$DAILY_DIR" "$WEEKLY_DIR"

timestamp="$(date '+%Y%m%d_%H%M%S')"
filename="catalog_import_${PGDATABASE}_${timestamp}.dump"
filepath="$DAILY_DIR/$filename"

log "Start back-up van database '$PGDATABASE' naar $filepath"
start_epoch=$(date +%s)

# -Fc: custom format, vereist voor pg_restore --list/--clean; comprimeert standaard.
if ! pg_dump --dbname="$PGDATABASE" -Fc -f "$filepath"; then
    rm -f "$filepath"
    fail "pg_dump is mislukt, geen halfklaar bestand achtergelaten"
fi

# Goedkope corruptiecontrole: kan het net gemaakte bestand zijn eigen inhoudsopgave tonen?
if ! pg_restore --list "$filepath" >/dev/null 2>&1; then
    rm -f "$filepath"
    fail "pg_restore --list kon $filename niet lezen (mogelijk corrupt dumpbestand), bestand verwijderd"
fi

end_epoch=$(date +%s)
duration=$((end_epoch - start_epoch))
filesize=$(wc -c < "$filepath" | tr -d ' ')

log "Back-up geslaagd: bestand=$filename grootte=${filesize}B duur=${duration}s"

# Retentie dagelijks: enkel de laatste $DAILY_RETENTION bestanden bewaren.
mapfile -t daily_files < <(ls -1t "$DAILY_DIR"/catalog_import_*.dump 2>/dev/null)
if [ "${#daily_files[@]}" -gt "$DAILY_RETENTION" ]; then
    for old in "${daily_files[@]:$DAILY_RETENTION}"; do
        log "Retentie: verwijder oude dagelijkse back-up $(basename "$old")"
        rm -f "$old"
    done
fi

# Wekelijkse laag: op zondag (dag 7 van ISO-week) een kopie bewaren, met eigen kortere retentie.
if [ "$(date '+%u')" = "7" ]; then
    weekly_path="$WEEKLY_DIR/$filename"
    cp "$filepath" "$weekly_path"
    log "Zondag: kopie bewaard als wekelijkse back-up $(basename "$weekly_path")"

    mapfile -t weekly_files < <(ls -1t "$WEEKLY_DIR"/catalog_import_*.dump 2>/dev/null)
    if [ "${#weekly_files[@]}" -gt "$WEEKLY_RETENTION" ]; then
        for old in "${weekly_files[@]:$WEEKLY_RETENTION}"; do
            log "Retentie: verwijder oude wekelijkse back-up $(basename "$old")"
            rm -f "$old"
        done
    fi
fi

log "Klaar."
