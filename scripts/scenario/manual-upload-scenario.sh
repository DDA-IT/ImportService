#!/usr/bin/env bash
# Scenario: keten inrichten, CSV uploaden, accept-baseline, delta-levering, bundel (SIMULATION) beslissen en bevriezen.
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
for i in $(seq 1 120); do [ "$(curl -s -o /dev/null -w '%{http_code}' $A/batches/summary)" = 200 ] && break; sleep 3; done
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

step "4b. Mutatiefilters op batch $B1"
for q in "status=PLANNED" "status=BLOCKED" "actionType=CREATE" "statusReason=BASELINE_ACCEPTED_WITHOUT_PUBLICATION"; do
  echo "--- GET batches/$B1/mutations?$q"; curl -s "$A/batches/$B1/mutations?size=50&$q"; echo; done

step "5a. Identieke herupload (verwacht 200, zelfde batch)"
up levering-1.csv "SCN-$SFX-1"

step "6. accept-baseline op batch $B1 (eerste levering wordt bronstaat)"
curl -s -w '
[HTTP %{http_code}]
' -X POST "$A/batches/$B1/accept-baseline" -H "$J"   -d '{"acceptedBy":"scenario@example.test","reason":"Scenario: eerste levering als baseline"}'
curl -s "$A/batches/$B1"; echo
echo "--- mutaties na accept"; curl -s "$A/batches/$B1/mutations?size=50"; echo

step "7. Derde levering = levering-2.csv NA accept-baseline (verwacht delta)"
R=$(up levering-2.csv "SCN-$SFX-2"); echo "$R"
B2=$(echo "$R" | grep -o '"batchId":[0-9]*' | head -1 | cut -d: -f2)
curl -s "$A/batches/$B2"; echo
for q in "" "status=PLANNED" "actionType=UPDATE" "actionType=CREATE" "status=SKIPPED" "statusReason=BASELINE_ACCEPTED_WITHOUT_PUBLICATION"; do
  echo "--- GET batches/$B2/mutations?$q"; curl -s "$A/batches/$B2/mutations?size=50&$q"; echo; done

step "8. Bundel: aanmaken, batch toevoegen, onderzoeken"
BA="$A/bundles"
BR=$(curl -s -X POST $BA -H "$J" -d "{\"bundleReference\":\"SCN-BUNDLE-$SFX\",\"description\":\"Scenario $SFX\",\"targetMode\":\"SIMULATION\",\"createdBy\":\"scenario@example.test\"}")
echo "$BR"; BID=$(echo "$BR" | grep -o '"bundleId":[0-9]*\|"id":[0-9]*' | head -1 | cut -d: -f2)
echo "bundle=$BID"
curl -s -w '
[HTTP %{http_code}]
' -X POST $BA/$BID/batches -H "$J" -d "{\"batchIds\":[$B2],\"addedBy\":\"scenario@example.test\"}"
for p in "$BID" "$BID/mutations?size=50" "$BID/mutations?size=50&statusReason=BASELINE_ACCEPTED_WITHOUT_PUBLICATION"; do
  echo "--- GET bundles/$p"; curl -s "$BA/$p"; echo; done

step "9. Beslissen en bevriezen (SIMULATION-bundel)"
curl -s -w '
[HTTP %{http_code}]
' -X POST $BA/$BID/decisions -H "$J"   -d '{"decisionKind":"APPROVE","decidedBy":"scenario@example.test","reason":"Scenario","filter":{"status":"PLANNED"}}'
echo "--- freeze zonder de AWAITING_APPROVAL-creatie te beslissen (verwacht 409 BUNDLE_HAS_UNDECIDED_MUTATIONS)"
curl -s -w '
[HTTP %{http_code}]
' -X POST $BA/$BID/freeze -H "$J"   -d '{"frozenBy":"scenario@example.test","reason":"Scenario: simulatie bevriezen"}'
MID=$(curl -s "$BA/$BID/mutations?status=AWAITING_APPROVAL" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
echo "--- individueel goedkeuren mutatie $MID"
curl -s -w '
[HTTP %{http_code}]
' -X POST $BA/$BID/mutations/$MID/approve -H "$J" -d '{"decidedBy":"scenario@example.test","reason":"Scenario: bulkcreatie bewust goedgekeurd"}'
echo "--- freeze (onomkeerbaar; enkel eigen SIMULATION-bundel)"
curl -s -w '
[HTTP %{http_code}]
' -X POST $BA/$BID/freeze -H "$J"   -d '{"frozenBy":"scenario@example.test","reason":"Scenario: simulatie bevriezen"}'
echo "--- decisions"; curl -s "$BA/$BID/decisions"; echo
echo "--- bundel"; curl -s "$BA/$BID"; echo
