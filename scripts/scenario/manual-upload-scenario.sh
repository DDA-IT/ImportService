#!/usr/bin/env bash
# Scenario: keten inrichten, CSV uploaden, accept-baseline, delta-levering, bundel (SIMULATION) beslissen en bevriezen.
#
# STATUS SINDS FASE 5-AUTH: dit script kan niet meer scripted draaien.
# Elke /api/**-aanroep vereist een Keycloak-login (sessiecookie) en elke schrijfaanroep een CSRF-header
# X-XSRF-TOKEN. Zonder login geeft de API 401 AUTHENTICATION_REQUIRED. Er is bewust geen omzeiling en geen
# bearer-token voor scripts (docs/design/fase5-auth-design.md, par. 9 V1 = A1; beslissing 2026-09-25).
#
# Het scenario is daarom een HANDMATIGE CHECKLIST geworden. De stappen, verzoekvormen en verwachte
# resultaten staan in docs/handleiding/standaardflows.md (flow 1 t/m 5); de bestanden levering-1.csv en
# levering-2.csv in deze map blijven de invoer. Voer de verzoeken uit in een aangemelde browsersessie
# (http://localhost:5173 na login bij Keycloak; zie README.md, "Lokaal aanmelden").
#
# RECHTEN (5-PERM): naast de login is een recht nodig. Zet uw eigen Keycloak-username in
# catalogimport.permissions.grants (application-demo.yml/application-local.yml, zie README.md, "Rechten (5-PERM)").
# Stap 1 en de uploads vragen MANAGE (setup-API ook de vlag catalogimport.setup-api.enabled), accept-baseline,
# beslissen en freeze vragen APPROVE (dat omvat MANAGE en READ), onderzoeken vraagt READ. Zonder recht: 403 PERMISSION_DENIED.
#
# Overzicht van de stappen (verwachte resultaten: zie standaardflows.md):
#   1. Keten inrichten via de setup-API (profiel demo): bronorganisatie, definitie, revisie, mapping,
#      activeren, koppeling, taak.
#   2. Upload levering-1.csv (referentie SCN-<suffix>-1): 201, 7 regels, 6 geldig, 1 afgewezen (prijs abc),
#      INITIAL_LOAD, REVIEW_REQUIRED.
#   3. Onderzoek de batch: mutaties, issues, issue-groups, filters (status, actionType, statusReason).
#   4. Identieke herupload (zelfde referentie en bestand): 200, dezelfde batch.
#   5. accept-baseline op batch 1: BASELINE_ACCEPTED, 6 mutaties SKIPPED.
#   6. Upload levering-2.csv (nieuwe referentie): delta van 1 UPDATE (S-1), 1 CREATE (S-8), 5 ongewijzigd.
#   7. Bundel (SIMULATION) aanmaken, batch toevoegen, onderzoeken.
#   8. Beslissen (groepsbeslissing status=PLANNED), freeze zonder de wachtende creatie: 409
#      BUNDLE_HAS_UNDECIDED_MUTATIONS; creatie individueel goedkeuren; freeze: 200, FROZEN.

cat >&2 <<'EOF'
manual-upload-scenario.sh draait sinds Fase 5-AUTH niet meer scripted.

Reden: /api/** vereist een Keycloak-login (sessiecookie) en schrijfaanroepen een CSRF-header X-XSRF-TOKEN;
zonder login volgt 401 AUTHENTICATION_REQUIRED. Er is bewust geen omzeiling of bearer-token voor scripts
(docs/design/fase5-auth-design.md, par. 9 V1 = A1).

Doe het scenario handmatig in een aangemelde browsersessie (http://localhost:5173):
  - stappen en verwachte resultaten: docs/handleiding/standaardflows.md (flow 1 t/m 5)
  - aanmelden en verzoeken uitvoeren: README.md, "Lokaal aanmelden" en "Hoe voert u de API-voorbeelden uit?"
  - invoerbestanden: scripts/scenario/levering-1.csv en levering-2.csv
EOF
exit 2
