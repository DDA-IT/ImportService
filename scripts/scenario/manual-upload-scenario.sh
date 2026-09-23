#!/usr/bin/env bash
# Scenario: nieuwe keten inrichten via de setup-API, CSV uploaden, resultaat onderzoeken.
# Env: BASE (default http://localhost:8081) ; SFX (unieke suffix, default tijdstempel)
BASE="${BASE:-http://localhost:8081}"; SFX="${SFX:-$(date +%H%M%S)}"
DIR="$(cd "$(dirname "$0")" && pwd)"
S="$BASE/api/catalog-import/setup"; A="$BASE/api/catalog-import"
J='Content-Type: application/json'
step(){ printf '\n===== %s =====\n' "$*"; }
post(){ echo ">> POST $1" >&2; curl -s -w '\n[HTTP %{http_code}]\n' -X POST "$S/$1" -H "$J" -d "$2"; }
id(){ grep -o '"id":[0-9]*' | head -1 | cut -d: -f2; }
mk(){ local out; out=$(post "$1" "$2"); echo "$out" >&2; echo "$out" | id; }

step "1. Wachten op backend"
for i in $(seq 1 60); do [ "$(curl -s -o /dev/null -w '%{http_code}' $A/batches/summary)" = 200 ] && break; sleep 3; done
curl -s $A/batches/summary; echo

step "2. Keten inrichten (suffix $SFX)"
OC="SCN$SFX"
mk source-organisations "{\"code\":\"$OC\",\"name\":\"Scenario $SFX\",\"type\":\"SUPPLIER\"}" >/dev/null
DEF=$(mk definitions "{\"sourceOrganisationCode\":\"$OC\",\"code\":\"$OC-CSV\",\"name\":\"Scenario $SFX\",\"usageType\":\"OWN_DEFINITION\"}")
REV=$(mk definitions/$DEF/revisions '{"delimiter":";","hasHeader":true,"identityProfileKind":"THREE_PART","supplierField":"leverancier","supplierGroupField":"groep","supplierReferenceField":"referentie","basePriceField":"prijs","descriptionField":"omschrijving","currencyField":"valuta","canonicalisationVersion":2,"creationThresholdSharePercent":10,"maxCriticalSharePercent":25}')
mk revisions/$REV/mappings '{"targetFieldCode":"EAN","sourceReference":"ean","sequenceNumber":1}' >/dev/null
mk revisions/$REV/activate '{"approvedBy":"beheerder@example.test"}' >/dev/null
LINK=$(mk links "{\"definitionId\":$DEF,\"code\":\"$OC-LINK\",\"name\":\"Scenario $SFX\",\"supplierCode\":\"$OC\",\"libraryCode\":\"PSARF012\"}")
TASK=$(mk tasks "{\"linkId\":$LINK,\"name\":\"Scenario manuele levering $SFX\"}")
echo "def=$DEF rev=$REV link=$LINK task=$TASK"

up(){ curl -s -w '\n[HTTP %{http_code}]\n' -F "file=@$DIR/$1" -F "deliveryReference=$2" -F "uploadedBy=scenario@example.test" $A/tasks/$TASK/deliveries; }

step "3. Upload levering-1.csv"
R=$(up levering-1.csv "SCN-$SFX-1"); echo "$R"
B1=$(echo "$R" | grep -o '"batchId":[0-9]*' | head -1 | cut -d: -f2)

step "4. Onderzoek batch $B1"
for p in "batches/$B1" "batches/$B1/mutations?size=50" "batches/$B1/issues" "batches/$B1/issue-groups" "batches?importLinkId=$LINK" "batches/summary"; do
  echo "--- GET $p"; curl -s "$A/$p"; echo; done

step "5a. Identieke herupload (verwacht 200, zelfde batch)"
up levering-1.csv "SCN-$SFX-1"
step "5b. Tweede, gewijzigde levering (levering-2.csv)"
R=$(up levering-2.csv "SCN-$SFX-2"); echo "$R"
B2=$(echo "$R" | grep -o '"batchId":[0-9]*' | head -1 | cut -d: -f2)
[ -n "$B2" ] && { echo "--- mutaties batch $B2"; curl -s "$A/batches/$B2/mutations?size=50"; echo; curl -s "$A/batches?importLinkId=$LINK"; echo; }
