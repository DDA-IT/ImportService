# Beslissingslog

Dit logboek legt architectuur- en businessbeslissingen vast die anders enkel in een gesprek
zouden bestaan. Eerdere beslissingen zijn bindend tenzij de mens ze expliciet herroept
(zie AGENT.md, "Beslissingslog").

## 2026-09-18 — Fase 0: bestaande Prodis-tabellen (CatalogImportJob e.a.)

**Vraag:** Prodis bevat al ongebruikte entiteiten/tabellen `CatalogImportJob`,
`CatalogImportRawRow`, `CatalogImportError`, `CatalogImportApprovalEvent`,
`SupplierCatalog`, `CatalogArticle` (Liquibase aanwezig, geen service/REST erbovenop).
`CatalogImportProfile`/`CatalogImportMapping` zijn wél actief in gebruik voor de bestaande
`SupplierPromoPrice`-import. Wat gebeurt hiermee?

**Beslissing:** CatalogImport bouwt een volledig eigen domeinmodel in zijn eigen database,
los van deze Prodis-tabellen. De ongebruikte scaffolding in Prodis wordt niet hergebruikt
en niet aangeraakt; `CatalogImportProfile`/`CatalogImportMapping` blijven exclusief van
`SupplierPromoPrice`. Sluit aan bij het reeds vastgelegde uitgangspunt "eigen project,
eigen database, geen foreign keys tussen de databases".

**Bron:** mens / `denker-zwaar` Fase 0-intake, `Prodis/docs/internal/architecture/catalog-import-prodis-integration.md` r.54,
`Prodis/Service/.../SupplierPromoPriceImportServiceImpl.java`

---

## 2026-09-18 — Fase 0: aanbiedingsidentiteit

**Vraag:** Wat is de sleutel die bepaalt of twee regels dezelfde aanbieding zijn — 2-delig
bibliotheekgebonden (`leverancier + referentie`, zoals de bestaande proefversie) of 3-delig
bibliotheekonafhankelijk (zoals de businessanalyse)?

**Beslissing:** `leverancier + leveranciersgroep + leveranciersreferentie`, optioneel
uitgebreid met kortingscode wanneer die gemapt is. Bibliotheek en bronorganisatie zijn
**scope**, geen onderdeel van de sleutel. `null` (niet gemapt) en `""` (expliciet leeg)
zijn verschillende toestanden. Deze keuze geldt voor de volledige importfile, nooit per
record.

**Bron:** mens / `business-analyse-leveranciersbibliotheken.md` §14.23.3, §15.2 punt 5, §16.1,
beslissingslog 15/09 (overstijgt `docs/requirements/catalog-import-business-rules.md` r.7 en
`docs/stories/catalog-import-proefpublicatie.md` r.33, die als achterhaald gelden)

---

## 2026-09-18 — Fase 0: opslag van CatalogImport-permissies

**Vraag:** CatalogImport heeft geen eigen gebruikers-/rollentabellen. Waar leven de
CatalogImport-permissies?

**Beslissing:** CatalogImport bevraagt Prodis' account-API voor de rechten van de
ingelogde (Keycloak-)gebruiker. Eén bron van waarheid voor rechten; vereist een nieuw
of uit te breiden Prodis-contract voor het opvragen van CatalogImport-specifieke
permissiecodes voor de huidige gebruiker.

**Bron:** mens / `denker-zwaar` Fase 0-intake

**Important technical constraint discovered (te bevestigen bij implementatie):** Prodis'
huidige machine-to-machine-authenticatiepatroon (`DdaProdisApiKeyAuthenticationFilter`,
`ROLE_DDA_PRODIS_API`) is in `SecurityConfiguration` bewust beperkt tot
`/api/transfer/**`, `/api/invoice-lines/**` en `/api/generalledger/**`. Een nieuw
account-/permissie-endpoint voor CatalogImport moet een apart, beperkt contract krijgen —
niet zomaar op dit patroon aansluiten, want die houder krijgt via
`hasDdaProdisApiBypass()` alle permissiecodes.

---

## 2026-09-18 — Fase 0: permissiemodel

**Vraag:** Welk permissiemodel voor CatalogImport-acties — grofmazig, taakgescheiden, of
fijnmazig conform de businessanalyse?

**Beslissing:** Grofmazig: `catalogImport.read`, `catalogImport.manage`,
`catalogImport.approve`.

**Bron:** mens / `Prodis/docs/internal/architecture/catalog-import-prodis-integration.md` §"Security en audit"

---

## 2026-09-18 — Fase 0: publicatiedoel

**Vraag:** Publiceert CatalogImport naar de Prodis-PostgreSQL-kern
(`SupplierCatalog`/`CatalogArticle`) of naar ProDisWebbase/Pervasive (`PSARFxxx` via
`252 IMPORT`)?

**Beslissing:** ProDisWebbase/Pervasive, via `252 IMPORT` (of de WebBase-variant) als
enige uitvoerder van de bestaande Prodis-logica. ProdisWebBase/Pervasive blijft eigenaar
van `PSARFxxx`, `ARTICLES` en de bestaande leveranciersrelaties.

**Bron:** mens / `business-analyse-leveranciersbibliotheken.md` §14.23.7, §15.4
(overstijgt de Prodis-architectuurdocs die een `CatalogImportPublication`-API naar de
Prodis-PostgreSQL-kern beschreven — die positie geldt als achterhaald)

---

## 2026-09-18 — Fase 0: goedkeurings- en publicatie-eenheid

**Vraag:** Is de eenheid van goedkeuring/publicatie de individuele mutatie (zoals de
Prodis-stories) of de Publicatiebundel (zoals de businessanalyse)?

**Beslissing:** Publicatiebundel. Een afzonderlijke importkoppeling mag nooit zelfstandig
publiceren; alle kandidaten van de gekozen imports komen samen in één Publicatiebundel.
Publicatie blijft atomair per consistente record-, set-, verwijder- of
afhankelijkheidsscope binnen die bundel.

**Bron:** mens (expliciet bevestigd na challenge) / `business-analyse-leveranciersbibliotheken.md` §14.26 (harde kernregel)

---

## 2026-09-18 — Fase 1: bronkoppeling ImportDefinition/ImportLink + sjablonen/bookmarks

**Vraag:** `ImportDefinition` is in Fase 1 gekoppeld aan één `SourceOrganisation`
(natuurlijke sleutel bron+code); `ImportLink` draagt de concrete leverancier en
bibliotheekscope, zodat één bron (bv. de VROOAM-aankoopvereniging) via aparte
`ImportLink`-rijen voor meerdere leveranciers kan dienen. Klopt deze interpretatie van
"bron" uit §14.15?

**Beslissing:** Ja, bevestigd — met een expliciete nevenvoorwaarde: het sjabloon- en
bookmarkmechanisme uit §14.16 (een versieerbare blauwdruk met benoemde, getypeerde
invulvelden, bv. `BESTANDS_PREFIX`, waarmee per leverancier een eigen
`ImportDefinition` uit een gedeeld VROOAM-sjabloon wordt afgeleid) is **geen optionele
latere uitbreiding maar een vereiste mogelijkheid** die zonder schemamigratie moet
kunnen worden toegevoegd. Fase 1/2 bouwen sjablonen en bookmarks nog niet, maar elke
volgende fase die de `ImportDefinition`/`ImportDefinitionRevision`-structuur aanraakt
moet expliciet toetsen of sjabloon-afgeleide bookmarks er later bij kunnen zonder de
dan al bestaande leveranciersdefinities te breken.

**Bron:** mens / `business-analyse-leveranciersbibliotheken.md` §14.15, §14.16

---

## 2026-09-18 — Fase 2: ontwerp kleinste verticale slice

**Vraag:** Hoe wordt de manuele-CSV-slice (upload → archief → streaming parse → staging →
identiteit → delta → mutatielijst + IMPORT_MARKER) ontworpen?

**Beslissing:** Het ontwerp in `docs/design/fase2-screening-design.md` is bindend
(tabellen `import_batch`, `import_candidate_stage`, `import_row_issue`,
`catalog_source_state`, `import_mutation`; changeset 002 additief naast ongewijzigde 001;
JDBC-bulk voor staging/bronstaat; idempotency_key per Delivery+revisie+identiteit;
archivering op bestandssysteem; screening schrijft nooit in de bronstaat; bouwstappen
2a–2e sequentieel).

**Bron:** denker-zwaar / `business-analyse-leveranciersbibliotheken.md` §14.23, §14.24,
§14.26, §15.2, §15.10, §16.1, §16.5–16.7

---

## 2026-09-18 — Fase 2: baseline-acceptatie (`accept-baseline`)

**Vraag:** De bronstaat (`catalog_source_state`) mag volgens §14.24.6 pas na publicatie
bijgewerkt worden, maar Fase 2 kent geen publicatie. Hoe bewijzen we in Fase 2 dat een
identieke herlevering 0 mutaties oplevert?

**Beslissing:** Via een aparte, geauditeerde actie `POST /batches/{id}/accept-baseline`
(verplichte reden, acceptedBy niet leeg en niet `system`, enkel vanuit status SCREENED).
De screening zelf schrijft nooit in de bronstaat. Weggeschreven bronstaatrijen krijgen
`state_origin = BASELINE_ACCEPTED`; de mutaties van die batch krijgen status `SKIPPED` met
reden `BASELINE_ACCEPTED_WITHOUT_PUBLICATION`. In Fase 5 wordt dit aangevuld/vervangen door
de echte publicatieroute (`state_origin = PUBLISHED`).

**Bron:** mens / `docs/design/fase2-screening-design.md` §3, §10, §11

---

## 2026-09-19 — Fase 3: ontwerp business rules en validatie

**Vraag:** Welke regels uit de businessanalyse horen in Fase 3 en hoe worden ze gemodelleerd?

**Beslissing:** Het ontwerp in `docs/design/fase3-rules-design.md` is bindend, inclusief de vier
afwijkingen van het Fase 0-uitgangspunt: (A) recordfilters wél in Fase 3, (B) prijsobservatiehistoriek
wél in Fase 3, (C) `TOO_MANY_ROW_ISSUES` vervalt als blokkeerreden, (D) `validation_result` als aparte
statusas; `import_row_issue` wordt uitgebreid (niet vervangen); canonicalisatieversie 2 naast 1;
creatiedrempel 100 nieuwe aanbiedingen EN 1% van de importscope (niet 100%); bouwstappen 3a-3h
sequentieel, rapport aan de mens na 3d en na 3h.

**Bron:** denker-zwaar / `business-analyse-leveranciersbibliotheken.md` §14.4, §14.9, §14.11-§14.12,
§14.23, §15.12, §16.1, §16.2, §16.5

---

## 2026-09-19 — Fase 3: vier-ogen bij accept-baseline zonder authenticatie

**Vraag:** §16.1/§14.23.7/§16.8 eisen vier-ogen-goedkeuring (twee verschillende gebruikers) bij een
bulkcreatie, bulkprijsincident of initialisatie. Er is nog geen authenticatie (Keycloak = Fase 5).
Wat doet `accept-baseline` in de tussentijd?

**Beslissing:** Zodra een batch een bulkincident, wachtende creaties (`AWAITING_APPROVAL`) of een
initialisatie heeft, is `accept-baseline` alleen toegestaan met een extra verplicht veld `approvedBy`
(niet leeg, niet `system`, verschillend van `acceptedBy`, case-insensitief). Beide namen worden persistent
bewaard op `import_batch`. Ontbreekt het veld: 409 `FOUR_EYES_APPROVAL_REQUIRED`. In Fase 5 worden
beide namen vervangen door geverifieerde Keycloak-identiteiten. Alternatief (weigeren tot Fase 4/5) is
verworpen omdat het de eerste levering van elke nieuwe koppeling onbruikbaar zou maken.

**Bron:** mens / `business-analyse-leveranciersbibliotheken.md` §16.1, §14.23.7, §16.8

---

## 2026-09-19 — Fase 3e: betekenis van de 50- en 200-daagse gemiddelden

**Vraag:** De afwijkingscontrole vergelijkt met de vorige waarde en met het gemiddelde van de laatste 50 en 200
"dagwaarden". Gaat dat over de laatste N vastgelegde goedgekeurde waarden, of over N kalenderdagen met
doorgetrokken waarde (ook voor ongewijzigde dagen)?

**Beslissing:** De 50- en 200-gemiddelden zijn slechts een extra referentie naast de vorige waarde, om een
bewegend gemiddelde te hebben. Gemiddelde over de laatste N VASTGELEGDE goedgekeurde observaties volstaat;
geen doorgetrokken kalenderdagen, geen extra observaties voor ongewijzigde dagen. De vorige waarde blijft de
primaire referentie.

**Bron:** mens / `docs/design/fase3-rules-design.md` §13

---

## 2026-09-20 — Fase 3: eindoordeel bij verworpen regels (validation_result) en kritieke kolommen

**Vraag:** Welk eindoordeel (`validation_result`) krijgt een levering met regels die door gewone fouten
verworpen zijn maar die verder doorgaat? (Open punt R-THR-06: ERROR staat niet in de regel.)

**Beslissing (mens, letterlijk):** "de gebruiker heeft zelf een waarde gegeven aan kolommen (kritiek of niet);
kritieke lijnfouten hebben een review nodig, waarschuwingen niet."
Vertaling: per kolom (mapping/veld) bepaalt de gebruiker of die KRITIEK is of niet. Een fout op een kritieke
kolom (kritieke lijn) vereist een review (⇒ `REVIEW_REQUIRED`); een waarschuwing of een fout op een niet-kritieke
kolom vereist geen review (⇒ hooguit `VALID_WITH_WARNINGS`).

**Status:** de exacte uitwerking ontbreekt nog in het ontwerp en moet vóór stap 3h door een Denker worden
uitgewerkt en aan de mens voorgelegd waar het een §6-criterium raakt: (a) waar de kritiek-vlag per kolom
opgeslagen wordt (bv. een additieve kolom op `import_field_mapping`; voor de revisie-eigen velden identiteit/
basisprijs/omschrijving een aanvullende plek) en of de basisprijs/identiteit altijd kritiek zijn; (b) hoe dit
zich verhoudt tot `max_critical_records` (design R-THR-05: default 0 ⇒ levering BLOCKED) — de mens zegt "review",
niet "blokkeren"; (c) hoe `validation_result` en mutatiestatussen (AWAITING_APPROVAL) daarop reageren.

**Bron:** mens / `docs/design/fase3-rules-design.md` §9, §13

---

## 2026-09-20 — Fase 3: eerste vastlegging van een kritieke referentie op een bestaande aanbieding

**Vraag:** §14.23.3 zegt dat ook een nieuwe referentie (EAN/PIM/CAB) op een bestaand artikel standaard de
kritieke beoordelingsroute volgt; ontwerp R-REF-06 laat de eerste vastlegging toe. Wat geldt?

**Beslissing:** Optie A, ZONDER schakelaar: de eerste vastlegging van een referentie op een bestaande aanbieding
is geen incident zolang de waarde niet al bij een andere aanbieding in dezelfde bibliotheek actief is (zoals
3f al implementeert). Komt het massaal voor (bv. referentiekolom voor het eerst aangezet), dan vangt de bulkregel
(100 records of 1% van de scope, R-THR-04) het op als één `BULK_IDENTITY_INCIDENT` ⇒ review + vier-ogen.
Er komt GEEN per-revisie schakelaar `reference_first_binding_requires_approval` (stap 3h-8 vervalt); strenger
maken kan later additief.

**Bron:** mens / `business-analyse-leveranciersbibliotheken.md` §14.23.3, §16.2; design `fase3-rules-design.md` §14

---

## 2026-09-20 — Fase 3: drempels zijn altijd een percentage (geen vaste aantallen)

**Vraag:** Het ontwerp kende vaste aantallen (100 nieuwe aanbiedingen, 100 records ter beoordeling,
`max_rejected_records`, bulkincident vanaf 100). Kleine koppelingen worden daar onbruikbaar door.

**Beslissing (mens):** Alle drempels zijn instelbare parameters per revisie/leverancier en ALTIJD een percentage
van de omvang ("altijd een percentage-koppeling"), nooit een vast aantal. Concreet: (a) creatiedrempel =
`creation_threshold_share_percent` (default 1); (b) records ter beoordeling (kritieke lijnen + vastgehouden
identiteitsincidenten) = nieuw `max_critical_share_percent` (default 1) — boven dit percentage van de records in
scope stopt de hele levering (`BLOCKED`), daaronder is het `REVIEW_REQUIRED`; (c) verworpen regels =
`max_rejected_share_percent` (default niet geconfigureerd = `null`); (d) bulkincident = nieuw
`bulk_incident_share_percent` (default 1). De kolommen `creation_threshold_absolute`, `max_critical_records` en
`max_rejected_records` blijven bestaan (geen hernoeming/verwijdering) maar worden NIET meer gebruikt. Vergelijking
altijd decimaal: `aantal × 100 > percentage × scope`, exact op de grens is niet overschreden. Het minimum van 10
gelijke fouten om een issuegroep te vormen is een technische groeperingsdrempel (geen leveringsdrempel) en blijft.
Bij een kleine koppeling zet de beheerder het percentage per leverancier hoger.

**Bron:** mens / `docs/design/fase3-rules-design.md` §1.7, §15

---

## 2026-09-20 — Fase 3: vier-ogen (twee verschillende gebruikers) is NOOIT vereist

**Vraag:** Het beslissingsblok van 2026-09-19 vereiste een tweede naam (`approvedBy`) bij accept-baseline voor een
bulkincident, wachtende creaties of een initialisatie (§16.1, §14.23.7, §16.8).

**Beslissing (mens):** "2 gebruikers moet nooit": een goedkeuring door twee verschillende gebruikers wordt
nergens afgedwongen. Dit HERROEPT het beslissingsblok "Fase 3: vier-ogen bij accept-baseline zonder
authenticatie" (2026-09-19) en wijkt bewust af van businessanalyse §16.8 en §14.23.7. Gevolg: `approvedBy`,
`baseline_approved_by` en de 409 `FOUR_EYES_APPROVAL_REQUIRED` komen er niet. Een review blijft bestaan als
status (`REVIEW_REQUIRED`, mutaties `AWAITING_APPROVAL`), maar één bevoegde persoon (`acceptedBy` + verplichte
reden, niet `system`) kan die afronden. Risico dat de mens bewust neemt: één persoon kan een bulkcreatie of een
initialisatie goedkeuren zonder tweede controle. Strenger maken kan later additief.

**Bron:** mens / afwijking van `business-analyse-leveranciersbibliotheken.md` §16.1, §16.8, §14.23.7

---

## 2026-09-22 — Samenvoeging businessanalyses: centraal artikel

**Vraag:** Komt er een 'centraal artikel' dat meerdere aanbiedingen van verschillende leveranciers
aan hetzelfde product koppelt?

**Beslissing:** Tussenweg. Een artikelcluster ontstaat alleen automatisch uit een bevestigde
EAN/PIM/CAB-koppeling (R-REF-01..09), met een vaste interne ID en volledige, permanente audit van
elke samenvoeging. Geen automatisch samenvoegen op basis van omschrijving of score, geen apart
beheerscherm voor fuseren/splitsen in de eerste versie.

**Bron:** mens / analyserapport samenvoeging businessanalyses, vraag Q2

---

## 2026-09-22 — Samenvoeging businessanalyses: prijsmodel

**Vraag:** Blijft het prijsmodel 'basisprijs + percentages' (gebouwd), of komt er een volledig
prijzenstelsel bij (verpakking, staffels, toeslagen)?

**Beslissing:** Beide lagen, gefaseerd. 'Basisprijs + percentages' blijft het publicatiemodel richting
Prodis (ongewijzigd, al gebouwd). Verpakking/staffels/toeslagen komen er in een latere fase apart bij
als importlaag-gegevens, met de harde voorwaarde dat een wijziging in verpakking/staffelgrens altijd
als prijswijziging in de deltavingerafdruk telt — anders kan een prijsstijging onzichtbaar blijven
(zie het "Important business rule discovered"-blok in het analyserapport). Normalisatie per stuk wordt
niet ingevoerd vóór deze laag gebouwd is.

**Bron:** mens / analyserapport samenvoeging businessanalyses, vraag Q3

---

## 2026-09-22 — Samenvoeging businessanalyses: prijsanker

**Vraag:** Komt er een bevestigd prijsanker naast de bestaande afwijkingscontrole (vorige prijs,
gemiddelde 50/200 dagen)?

**Beslissing:** Ja. Een vierde referentie op prijsobservatieniveau die niet automatisch meeschuift met
dagelijkse goedkeuringen; enkel een bevoegd persoon kan het anker expliciet verzetten. Beschermt tegen
sluipende prijsdrift die de bestaande drie (meeschuivende) referenties niet detecteren. Additief:
geen wijziging aan bestaande kolommen/gedrag, komt in een latere Fase-3-achtige uitbreiding van de
prijscontrole.

**Bron:** mens / analyserapport samenvoeging businessanalyses, vraag Q4 (businessanalyse 2 §11.2/§11.6)

---

## 2026-09-22 — Samenvoeging businessanalyses: leveranciers-BOM's

**Vraag:** Vallen leveranciers-onderdelenlijsten (BOM's) binnen de projectscope?

**Beslissing:** Ja, maar als apart, later te bouwen onderwerp met een eigen publicatieroute (de huidige
ProDisWebbase/PSIMPORT-route kan een volledige BOM/relatiestructuur niet dragen, businessanalyse 2
§15.10). Het bestaande supplementmodel (BA1 §14.22/§16.3, gebouwd) blijft het implementatiemodel voor
supplementen; BOM is een generalisatie die er niet in vervangen wordt maar naast komt.

**Bron:** mens / analyserapport samenvoeging businessanalyses, vraag Q5

---

## 2026-09-22 — Samenvoeging businessanalyses: publicatiebreedte naar Prodis

**Vraag:** Schrijft de publicatie naar Prodis alleen de velden waar de import zeggenschap over heeft,
of ook een volledige rij (met risico op overschrijven van Prodis-eigen gegevens zoals voorraad,
locatie, boekhoudrekeningen)?

**Beslissing:** Alleen eigen velden, altijd. De bestaande veldeigenaarsmatrix (BA1 §14.23: eigenaar
Catalogusbron/Prijscontrole/Kritieke referentie/Prodis-gebruiker) blijft de harde bovengrens; een veld
met eigenaar Prodis-gebruiker (eenheid, voorraad, locatie, rekeningen) wordt nooit door de import
geschreven, ook niet als "behoud van de huidige waarde". Een bredere publicatievariant (volledige rij
met terugleespatch) wordt pas overwogen nadat het behoud-/patchgedrag van de Prodis-verwerker feitelijk
bewezen is — dat is geen principekeuze die nu al anders wordt vastgelegd.

**Bron:** mens / analyserapport samenvoeging businessanalyses, vraag Q6 (BA1 §14.23 PSARF-matrix vs
businessanalyse 2 §15.8)

---

## 2026-09-22 — Fase 4: ontwerp Publicatiebundel

**Vraag:** Hoe worden mutaties die op "wacht op goedkeuring"/"gepland" staan samengebracht,
individueel of in groep goedgekeurd/afgekeurd, en klaargezet voor publicatie (Fase 5)?

**Beslissing:** Het ontwerp in `docs/design/fase4-publication-bundle-design.md` is bindend
(changeset 005: `publication_bundle`, `publication_bundle_batch` op BATCHNIVEAU, geen
`publication_bundle_id` op de mutatie; `publication_decision` als append-only beslissingsregister;
vier additieve velden op `import_mutation`; wijzigingsgroep = `(batch_id, identity_hash)`;
bouwstappen 4a-4f sequentieel). Vier eigen keuzes van de Denker bevestigd door de mens: (1) `PLANNED`-
mutaties worden bij het bevriezen in bulk goedgekeurd op naam van de bevriezer, niet één voor één
aangeklikt; (2) een bundel met één importkoppeling is toegestaan; (3) kritieke identiteitsincidenten
(`BLOCKED`/`IDENTITY_REFERENCE_INCIDENT`) krijgen in Fase 4 geen goedkeur-/afkeurpad, blijven zichtbaar
staan en beletten bevriezen niet — dat komt in een latere fase met schrijftoegang tot
`catalog_reference_state`; (4) een bevroren bundel kan nog geannuleerd worden zolang Fase 5 niet
begonnen is met publiceren.

**Bron:** denker-zwaar / `businessanalyse-catalogimport.md` h. 17, 18, 22, 23, 25, 26;
`docs/decisions.md` 2026-09-18 (goedkeurings-/publicatie-eenheid), 2026-09-20 (vier-ogen nooit)

---

## 2026-09-22 — Frontend: stack en locatie

**Vraag:** README.md meldt een gebruikersinterface als "nog niet aanwezig". Er is geen bestaand
frontend-project en geen document dat een stack voorschrijft. Welke stack en waar in de repo?

**Beslissing:** React + Vite + TypeScript, als volledig losstaand SPA-project in een nieuwe
topniveau-map `Frontend/` (sibling van `Domain`/`Dao`/`Service`/`Web`), buiten de Maven-reactor.
Praat uitsluitend via REST/JSON met de bestaande `Web`-module (lokaal poort 8081, zie
`Web/src/main/resources/application-local.yml`). Geen Thymeleaf/server-side rendering, geen
bundeling in de Maven-build.

**Bron:** mens (expliciet gekozen na toelichting van de opties)

---

## 2026-09-22 — Frontend: correctie van de schermenindeling (3 → 5)

**Vraag:** Klopt de voorgestelde indeling van drie schermen (leveringssetup, data-analyse
vanuit een sjabloon, publicatie) volgens de volledige documentenset?

**Beslissing:** Nee, bevestigd door de mens na een denker-zwaar-toetsing tegen de normatieve
schermkaart (`business-analyse-leveranciersbibliotheken.md` §15.6, 17 schermen) en de
bestaande backend. Herziene indeling: (0) werkvoorraad/dashboard, (1a) beheer/inrichting,
(1b) leveringsconfiguratie, (2) levering & screening, (3) publicatiebundel. Van deze vijf
zijn vandaag alleen (2) en (3) zonder nieuw backendwerk bouwbaar.

**Bron:** denker-zwaar (`Toets 3-schermenindeling aan documentenset`) / `business-analyse-leveranciersbibliotheken.md`
§15.6, §14.4, §14.16, §14.18; `businessanalyse-catalogimport.md` h.10.1, h.24, h.33, h.34.3;
`docs/design/fase4-publication-bundle-design.md` §1

---

## 2026-09-22 — Frontend: D14, scope eerste werkvoorraadslice

**Vraag:** Wat moet de eerste werkvoorraad-/dashboardslice (scherm 0) minimaal tonen, of
stellen we die uit? (`businessanalyse-catalogimport.md` h.33, één van de twee laatst
openstaande projectbeslissingen)

**Beslissing:** Uitstellen. Eerst schermen (2) levering & screening en (3) publicatiebundel
bouwen — de enige twee zonder nieuw backendwerk. Werkvoorraad/dashboard volgt later als
aparte fase met eigen Denker-analyse (er bestaat vandaag geen enkel lijst-/zoekendpoint voor
leveringen/batches/taken).

**Bron:** mens / denker-zwaar-toetsing

---

## 2026-09-22 — Frontend: betekenis "sjabloon" in het levering&screening-scherm

**Vraag:** Wat betekent "data-analyse vanuit een sjabloon" — een gewone levering tegen de
bevroren actieve definitieversie, het importsjabloon+bookmarks-mechanisme (§14.16), of een
structuurpreview vanaf een testbestand (§14.4)?

**Beslissing:** Importsjabloon + bookmarks (§14.16): een versieerbare blauwdruk met benoemde,
getypeerde invulvelden waaruit per leverancier een eigen `ImportDefinition` gematerialiseerd
wordt. Dit mechanisme is al op 2026-09-18 vastgelegd als "vereiste mogelijkheid, geen
optionele latere uitbreiding", maar is nog niet gebouwd: geen `import_definition_bookmark`/
`import_link_bookmark_value`-tabellen, geen endpoint.

**Belangrijk gevolg (nog niet opgelost, zie melding aan de mens hierna):** dit maakt scherm
(2) afhankelijk van eerst nieuw domeinmodel-/servicewerk (sjabloon + bookmarks) — scherm (2)
is dus NIET meer zonder nieuw backendwerk bouwbaar, in tegenstelling tot de eerdere
denker-conclusie. Dit moet met de mens kortgesloten worden vóór er een Bouwer op scherm (2)
start.

**Bron:** mens / `business-analyse-leveranciersbibliotheken.md` §14.16; `docs/decisions.md`
2026-09-18 "Fase 1: bronkoppeling ImportDefinition/ImportLink + sjablonen/bookmarks"

---

## 2026-09-22 — Frontend: plaatsing accept-baseline vs. bundel-opname

**Vraag:** Waar hoort de keuze tussen `accept-baseline` en "toevoegen aan publicatiebundel"
in de UI thuis? De twee sluiten elkaar per batch onherroepelijk uit (409
`BATCH_IN_PUBLICATION_BUNDLE`).

**Beslissing:** Op het levering&screening-scherm (2), als actie naast de batch zelf, dicht
bij waar de status van die batch toch al zichtbaar is.

**Bron:** mens / denker-zwaar-toetsing

---

## 2026-09-22 — Frontend: koppelingoverstijgende mutatielijst (BA1 scherm 11)

**Vraag:** Wordt de koppelingoverstijgende mutatielijst een eigen scherm, of een
herbruikbaar component ingebed in de andere schermen?

**Beslissing:** Herbruikbaar component, ingebed in zowel scherm (2) (`/batches/{id}/mutations`)
als scherm (3) (`/bundles/{id}/mutations`) — dit matcht hoe de backend al bewust gebouwd is
(Fase 4c: "dezelfde vier velden ... zodat beide lijsten exact dezelfde vorm hebben"). Geen
apart, koppelingoverstijgend scherm nu.

**Bron:** mens / denker-zwaar-toetsing

---

## 2026-09-22 — Volgorde: sjabloon+bookmarks vóór scherm 2

**Vraag:** Scherm 2 hangt af van het nog niet gebouwde importsjabloon+bookmarks-mechanisme
(§14.16). Bouwen we dat mechanisme eerst (nieuwe Fase 1-achtige cyclus), bouwen we scherm 2
eerst zonder sjabloon, of starten we de eerste UI-slice met alleen scherm 3?

**Beslissing:** Eerst het sjabloon+bookmarks-mechanisme bouwen (domeinmodel + service +
endpoint), vóór er UI voor scherm 2 komt. Vereist een eigen denker-zwaar-ontwerp (Fase
1-achtig: entiteiten, migratie, relatie tot bestaande `ImportDefinition`/
`ImportDefinitionRevision`), gevolgd door bouwer-cycli — dit is geen frontend-taak.

**Bron:** mens / denker-zwaar-toetsing

---

## 2026-09-22 — Frontend: setup-API productiewaardig maken

**Vraag:** Komt er eerst een productiewaardige, geautoriseerde beheer-API voor de
inrichtingsketen (bronorganisatie/definitie/koppeling — scherm 1a), of blijft dat voorlopig
de ontwikkelhulp-API (`CatalogImportSetupController`, standaard uit, geen authenticatie,
create-only)?

**Beslissing:** Later, na Fase 5/Keycloak. Scherm (1a) blijft uitgesteld tot de
autorisatiebeslissing er is (`docs/decisions.md` 2026-09-18 "Fase 0: permissiemodel"). Nu
focus op schermen (2) en (3).

**Bron:** mens / denker-zwaar-toetsing

---

## 2026-09-23 — Fase 1-achtig: domeinmodel sjabloon + bookmarks

**Vraag:** Hoe wordt het importsjabloon+bookmarkmechanisme (§14.16) gemodelleerd — eigen
entiteit of variant op `ImportDefinition`, waar leeft de ingevulde bookmarkwaarde
(definitie/revisie vs. koppeling), en hoe verhoudt het zich tot bestaand versiebeheer?

**Beslissing:** Het ontwerp van de denker-zwaar (`Ontwerp sjabloon+bookmarks domeinmodel`,
2026-09-23) is bindend: een sjabloon is een bestaande `ImportDefinition` met
`usage_type = REUSABLE_TEMPLATE` (géén nieuwe tabel, géén parallel versiebegrip — hergebruikt
`ImportDefinitionRevision`). Vier nieuwe, volledig additieve tabellen (changeset
`006-import-template-bookmark.sql`): `import_definition_bookmark` (declaratie op
revisieniveau), `import_definition_bookmark_usage` (witte lijst toegelaten
configuratieplaatsen), `import_definition_bookmark_value` (DEFINITION-scope snapshot bij
materialisatie), `import_link_bookmark_value` (LINK-scope waarde per koppeling, gekoppeld op
`bookmark_name`, niet op FK naar de declaratierij — declaraties verdwijnen bij elke
opvolgrevisie, koppelingen niet).

Concrete keuzes bevestigd door de mens:
1. **Scopemodel:** elke bookmark krijgt een verplichte `value_scope`. DEFINITION-scope wordt
   bij materialisatie in de afgeleide revisie vastgezet; LINK-scope wordt per `ImportLink`
   ingevuld. Enige lezing waarin BA1 §14.16 en §14.17/§14.19/§14.20 elkaar niet
   tegenspreken.
2. **Gedeelde definitie toegestaan, met beperking:** één uit een sjabloon afgeleide
   `ImportDefinition` mag door meerdere `ImportLink`s (meerdere leveranciers) gedeeld
   blijven. Gevolg: een DEFINITION-scope bookmark mag nooit een waarde bevatten die per
   leverancier zou moeten verschillen — zo'n bookmark moet LINK-scope zijn. Dit moet als
   regel afgedwongen worden bij het declareren van een bookmark (niet pas bij materialisatie
   ontdekt).
3. **`DELIVERY_FILE_SELECTION`-plaats (bv. `BESTANDS_PREFIX`) wordt weggelaten** tot er een
   Leveringsconfiguratie-entiteit bestaat — geen bookmarkplaats die nu niets toepast.
4. **`source_organisation_id` blijft verplicht (NOT NULL)** op een sjabloon; een
   bronoverstijgend sjabloon is geen scope van deze bouwstap, kan later additief.
5. **Reproduceerbaarheid — hash + lock:** een nullable `import_batch.bookmark_values_hash`
   toegevoegd (additieve kolom op een bestaande tabel, expliciet gemeld) én een LINK-scope
   bookmarkwaarde wordt niet meer wijzigbaar zolang de koppeling een open (niet-terminale)
   batch heeft.
6. **Blokkeerpunt verplichte bookmarks:** zowel bij het activeren van de afgeleide revisie
   als bij de start van een batch/levering, via de bestaande `CONFIG_*`-foutfamilie. Dit is
   een nieuwe blokkeergrond op leveringsniveau.

7. **Databaseguard (changeset `006-5`) wordt meegenomen, niet alleen serviceniveau:**
   `import_definition.id` krijgt `unique (id, usage_type)`, `import_link` krijgt een nieuwe
   kolom `definition_usage_type varchar(40) not null default 'OWN_DEFINITION'` met een
   samengestelde FK naar `(import_definition_id, usage_type='OWN_DEFINITION')` en een check —
   zodat een sjabloon nooit een `ImportLink` (en dus nooit een batch of publicatie) kan
   krijgen, ook niet bij een toekomstige servicebug. Additief: bestaande rijen blijven geldig
   via de default.

**Nog niet gebouwd in deze beslissing:** service-/REST-/UI-laag (materialisatiewizard,
sjabloonversievergelijking, bulkcreatie) — dat is de volgende bouwstap.

**Bron:** denker-zwaar (`Ontwerp sjabloon+bookmarks domeinmodel`) / mens (Q1-Q6 bevestigd) /
`business-analyse-leveranciersbibliotheken.md` §14.14-§14.20; `Businessanalyse_artikelimport_en_prijsacceptatie-2.md`
§5.8-§5.10; `docs/decisions.md` 2026-09-18 (bronkoppeling + sjablonen/bookmarks)

---

## 2026-09-23 — Service-/REST-laag materialisatiewizard: ontwerp + vijf beantwoorde vragen

**Vraag:** Hoe wordt de service-/REST-laag ontworpen die vanuit een sjabloon (`ImportDefinition` met
`usage_type = REUSABLE_TEMPLATE`) + ingevulde bookmarkwaarden een leveranciersgebonden
`ImportDefinition`/`ImportDefinitionRevision` + `ImportLink` materialiseert? Dit is de bouwstap die op
2026-09-23 (domeinmodel sjabloon+bookmarks) expliciet als "nog niet gebouwd, volgende bouwstap" is
aangemerkt.

**Beslissing:** Het ontwerp in `docs/design/sjabloon-materialisatie-design.md` is bindend voor de
delen die er geen §6-vraag over stellen: geen schemawijziging (alles past in changeset 001-006);
materialisatie is een snapshot (DEFINITION-waarden worden letterlijk vastgezet, nooit een runtime-
verwijzing naar het sjabloon); LINK-scope declaraties worden meegekopieerd naar de afgeleide revisie
(zie Q5 hieronder voor de bevestigingsvraag); de scope↔plaats-regel wordt zowel bij declareren als
defensief bij materialiseren gecontroleerd (er bestaat nog geen declaratiepad en de database kan de
regel niet afdwingen — zie het "Important technical constraint discovered"-blok in het ontwerp §13);
`mode` (NEW_DEFINITION/REUSE_DEFINITION) is verplicht zonder default; foutcodes blijven in de bestaande
`CONFIG_*`/`*_NOT_FOUND`/`*_IN_USE`-families; bouwstappen 5a-5f strikt sequentieel, 5c is de kleinste
verticale slice (alleen NEW_DEFINITION, vier plaatsen).

**Vijf punten waren nog open (§6-criteria) en zijn door de mens beantwoord (2026-09-23):**
1. **Q1 (autorisatie):** materialisatie-API achter de bestaande `catalogimport.setup-api.enabled`-vlag
   (standaard uit, geen authenticatie — zelfde patroon als `CatalogImportSetupController`).
2. **Q2 (datamodel/identiteit, de zwaarste):** een LINK-scope bookmark op een revisieniveau-plaats
   (bv. `DETAILLEVERANCIER` via `FIELD_MAPPING_FIXED_VALUE`) is toegestaan, maar de resulterende
   definitie wordt daarna geweigerd bij `REUSE_DEFINITION` (409 `DEFINITION_NOT_SHAREABLE`). Houdt
   §14.15 ("elke leverancier een eigen definitie" voor dit geval) én de 23/09-keuze "gedeelde definitie
   toegestaan" allebei waar: delen mag, maar niet zodra een per-leverancier waarde in de definitie zit.
3. **Q3 (statusflow):** ook uit een `SUPERSEDED` sjabloonrevisie mag gematerialiseerd worden (niet enkel
   `ACTIVE`); alleen `DRAFT` blijft geweigerd. Wijkt af van het aanvankelijke, strengere denker-voorstel.
4. **Q4 (statusflow):** een ontbrekende verplichte LINK-bookmark bij de start van een levering weigert
   de upload met 409 `CONFIG_REQUIRED_BOOKMARK_MISSING` vóór er iets gearchiveerd wordt — identiek aan
   de bestaande `CONFIG_PRICE_FIELD_MISSING`-controle.
5. **Q5 (contract):** bevestigd — LINK-scope bookmarkdeclaraties worden meegekopieerd naar elke
   afgeleide revisie. Breidt de betekenis van `import_definition_bookmark` uit van "declaratie op een
   sjabloonrevisie" naar "declaratie op elke revisie".

Het ontwerp in `docs/design/sjabloon-materialisatie-design.md` is hiermee **volledig bindend**,
inclusief §4 fase A4 (aangepast voor Q3) en §12 (antwoorden verwerkt). Bouwstappen 5a-5f (§11) kunnen
sequentieel starten zonder verdere architectuurvragen.

**Bron:** denker-zwaar (`Ontwerp materialisatiewizard service/REST-laag`) /
`docs/design/sjabloon-materialisatie-design.md`; mens (Q1-Q5 bevestigd, Q3 wijkt af van het
denker-voorstel)

---

## 2026-09-23 — Frontend-slice 1: scherm (3) Publicatiebundel + het frontendfundament

**Vraag:** Hoe wordt de eerste echte frontend-slice gebouwd — welke fundamentele patronen (mapstructuur,
API-client, foutcodeweergave, server-state, stijl, actormodel) en hoe ziet scherm (3) Publicatiebundel
eruit, inclusief het herbruikbare mutatielijst-component dat scherm (2) later ongewijzigd overneemt?

**Beslissing:** Het ontwerp in `docs/design/frontend-scherm3-bundel-design.md` is bindend. Kern:

- **Geen server-state-bibliotheek** (geen TanStack Query/Redux/SWR): twee eigen hooks met expliciete
  cache-invalidatie. Reden: optimistische updates zijn hier ongewenst — een goedkeuring is een
  geauditeerde handeling en de UI mag nooit "goedgekeurd" tonen vóór de server dat bevestigt.
  Herzieningstrigger vastgelegd in §5.
- **Gewone CSS met CSS Modules**, geen Tailwind/componentenbibliotheek. `react-router` is de enige
  nieuwe runtime-afhankelijkheid.
- **Stabiele backendfoutcodes blijven altijd zichtbaar** in de UI (`errors/codes.ts` met
  familie-fallbacks); een onbekende code geeft nog steeds een leesbare melding mét de code.
- **De frontend rekent nooit met bedragen** — geen berekend prijsverschil tussen `beforeBasePrice` en
  `afterBasePrice` (AGENT.md §2 principe 8; zie ook het BigDecimal-constraintblok in §18).
- **`bundlePolicy.ts` is een spiegel van de backend, nooit de bron van waarheid**: elke 409 wordt
  afgehandeld, ook wanneer de UI-poort "toegestaan" zei. Verboden acties worden uitgeschakeld getoond
  mét reden, niet verborgen.
- **Bevriezen en annuleren vragen een typ-bevestiging** van de `bundleReference`; dat zijn de twee
  acties die binnen de applicatie niet meer ongedaan te maken zijn.
- Bouwstappen F1-F11 strikt sequentieel (zij delen `routes.tsx`, de app shell en `api/types.ts`), plus
  een aparte backendstap B1.

**Vier vragen door de mens beantwoord (2026-09-23), alle conform de aanbeveling:**
1. **Q1 (actornaam):** één keer per browsersessie invullen, bewaard in `sessionStorage`, permanent
   zichtbaar in de app shell én in elke bevestigingsdialoog opnieuw getoond en ter plekke wijzigbaar.
   Bewust niet `localStorage`: een naam die dagen later nog voorgevuld staat op een gedeelde machine is
   precies hoe iemand ongemerkt op naam van een collega tekent.
2. **Q2 (bereikbaarheid):** de frontend blijft tot Fase 5/Keycloak uitdrukkelijk een ontwikkelhulpmiddel
   — alleen `npm run dev` tegen een lokale backend. Geen CORS, geen statische uitlevering via de
   `Web`-module, niets uitgeleverd. Zonder authenticatie zou een bereikbare frontend betekenen dat
   iedereen die het netwerkadres kent een publicatiebundel kan bevriezen.
3. **Q3 (`targetMode`):** alle drie de waarden worden aangeboden, zonder voorselectie, met een expliciete
   waarschuwing bij `PRODUCTION` dat een latere publicatiefase die bundel als echte publicatie
   behandelt. Verbergen zou schijnveiligheid geven — de backend aanvaardt de waarde toch.
4. **Q4 (sortering):** `GET /bundles` en `GET /bundles/{id}/batches` krijgen deterministische sortering
   (bundels aflopend op `id`, lidmaatschappen oplopend) als aparte backendstap B1. Dit legt een tot nu
   toe ongedefinieerde volgorde vast.

**Vier ontdekkingen** staan in §18 van het ontwerp en verdienen terugschrijving naar de betrokken
ontwerpdocumenten: paginering zonder sortering (fase 4), het niet-uniforme foutcontract
(`IllegalArgumentException` → 400 zonder `code`, fase 4), de synchrone upload+screening binnen één
HTTP-verzoek bij een maximum van 1 GB (fase 2), en `BigDecimal` als JSON-getal waardoor schaal en
precisie in de browser verloren gaan.

**Nog niet beslist, wel voorgesteld:** drie kleine additieve backenduitbreidingen die scherm (3)
merkbaar bruikbaarder maken (§16): een `statusReason`-filter op de mutatielijst, tellers voor `PLANNED`
en `AWAITING_APPROVAL` op `BundleDetail`, en `importLinkCode`/`supplierCode` op `BundleBatchRow`/
`BundleCandidate` zodat de UI niet "koppeling #7" hoeft te tonen.

**Bron:** denker-zwaar (`Ontwerp frontend scherm 3 + fundament`) /
`docs/design/frontend-scherm3-bundel-design.md`; mens (Q1-Q4 bevestigd)

---

## 2026-09-23 — Frontend: D14 opgepakt, eerste verticale slice Scherm 0 (werkvoorraad)

**Vraag:** D14 stond uitgesteld (22/09, "geen enkel lijst-/zoekendpoint voor
leveringen/batches/taken"). Nu scherm (2)/(3) grotendeels gebouwd zijn: wat is de kleinste
eerste verticale slice voor Scherm 0, welke lijst-/zoekendpoints zijn daarvoor nodig, en
welke scope-/autorisatiekeuzes moeten daarbij expliciet vastgelegd worden?

**Beslissing:** `ImportBatch` is de enige zinvolle werkvoorraad-eenheid (draagt status,
eindoordeel, alle tellers en de blokkeerreden; leveringen/taken/runs voegen niets toe).
Eerste slice is volledig alleen-lezen en bouwt drie nieuwe endpoints, zonder nieuwe tabellen
of migraties:

- **`GET /api/catalog-import/batches`** — `PageResult<BatchRow>`, filters `status`,
  `validationResult`, `importLinkId`, `createdFrom`/`createdTo` (op `created_at`), vaste
  sortering `id desc`. Hergebruikt `ImportBatchRepository`/`BatchQueryService`/
  `CatalogImportBatchController` (patroon: `findBundleCandidates`). Vereist `join fetch`
  op `importLink`/`supplierOrganisation` (beide `LAZY`) om N+1 te vermijden.
- **`GET /api/catalog-import/batches/summary`** — telblokken per status en per
  eindoordeel (JPQL `group by`, geen losse count-queries per status). `null`-eindoordeel
  ("niet vastgesteld") is een eigen zichtbare regel, nooit samengevoegd met `VALID` en
  nooit als 0 getoond.
- **`GET /api/catalog-import/import-links`** — nieuw, alleen-lezen, altijd bereikbaar
  (buiten de `catalogimport.setup-api.enabled`-vlag om): `id/code/name/supplierCode/
  supplierName/libraryCode/active`. Nodig zodat Scherm 0 (en scherm 3) koppelingsnamen
  tonen i.p.v. "koppeling #7".

**Vier vragen door de mens beantwoord (2026-09-23), alle conform de aanbeveling:**
1. **Q1 (import-links buiten setup-vlag):** ja, nieuw alleen-lezen endpoint, altijd aan.
   Het schrijft geen configuratie (dat is wat de setup-API-vlag beschermt) en `GET
   /batches`/`GET /bundles` staan vandaag ook al zonder authenticatie open.
2. **Q2 (behandelgeval/deeltaak, D14-kern):** geen nieuwe tabellen. De werkvoorraad blijft
   een **afgeleide weergave** over bestaande batches/issuegroepen. ST-11's volledige
   behandelgeval-model (oorzaak, eigenaar, prioriteit, statusmachine) blijft uitgesteld —
   een eigenaarsveld zonder geverifieerde identiteit zou een lege schil zijn.
3. **Q3 (toewijzing/"Mijn taken"):** nee, uitgesteld tot Fase 5/Keycloak. De actor is
   vandaag een zelfingetypte `sessionStorage`-naam; dat is expliciet niet het bewijs dat
   ST-11 als vereiste identiteit stelt en zou anders permanent in de database komen te
   staan.
4. **Q4 (issue-afhandeling):** nee. Slice 1 blijft volledig alleen-lezen; `IssueHandlingStatus`
   blijft op `DETECTED` staan (fase 3-beperking, zie de enum-javadoc). Een aanvaardingsactie
   laat data door die de screening tegenhield en verdient een eigen ontwerp.

**Kleinste eerste verticale slice:** S0-B1 (`GET /batches`), S0-B2 (`GET /batches/summary`),
S0-B3 (`GET /import-links`), S0-F1 (route `/` met telblokken + filterbare tabel, geen enkele
schrijfactie). Bouwstap B1 uit de scherm-3-beslissing (deterministische sortering op
`GET /bundles`) wordt in dezelfde cyclus meegenomen — zelfde patroon, nog niet uitgevoerd.

**Bewust buiten scope:** rollen/rechten, behandelgeval/deeltaak, eigenaar/toewijzing,
issue-afhandelacties, ERP-monitoring, notificaties, cross-batch issuegroeplijst,
vrije-tekstzoek, deep-linking van filterstatus. D13 (RPO/RTO) blijft apart openstaand,
ongerelateerd aan Scherm 0.

**Autorisatiegrens is lezen-vs-schrijven, niet scherm-vs-scherm:** `GET /import-links` staat
om dezelfde reden niet achter `catalogimport.setup-api.enabled` als `GET /batches`/`GET
/bundles` vandaag al niet doen — het schrijft niets. Dat verschilt bewust van
`CatalogImportLinkController` (de bookmarkwaarde-schrijfendpoints van de
materialisatiewizard), die wél achter die vlag blijft staan, want die schrijft configuratie.

**Gevolg voor het scherm 3-ontwerp:** dit lost drie van de zeven tekortkomingen uit
`docs/design/frontend-scherm3-bundel-design.md` §16 op of brengt ze in behandeling: §16.5
(koppelingscode i.p.v. "koppeling #7"), §16.6 (paginering zonder sortering) en §16.7 (geen
lijstendpoint voor batches). §16.1-§16.4 blijven open.

**Herroept** `docs/decisions.md` 2026-09-22 "Frontend: D14, scope eerste werkvoorraadslice" —
het toenmalige uitstel was precies gemotiveerd door het ontbreken van deze endpoints.

**Bron:** denker-zwaar (`Denker-analyse lijst-/zoekendpoints Scherm 0`) /
`businessanalyse-catalogimport.md` h.24, h.29, h.33; `business-analyse-leveranciersbibliotheken.md`
§15.6; `docs/stories/catalog-import-v2.md` ST-10/ST-11; `docs/analysis/current-project-vs-businessanalyse-2.md`;
`docs/design/frontend-scherm3-bundel-design.md` §12-§16; mens (Q1-Q4 bevestigd, alle conform aanbeveling)

---

## 2026-09-23 — Fase 7-verificatie S0-B1/B2/B3: collision-gevoelige testsleutels in `BundleHttpTest`

**Vraag:** Bij het gericht draaien van `mvn -pl Web -am test` voor de Scherm 0-endpoints
faalden drie ongerelateerde tests in `BundleHttpTest` (409 i.p.v. 200, unieke-constraint-
schendingen op `SourceOrganisation`-codes). Oorzaak: `BundleHttpTest` genereert testcodes met
een JVM-lokale `AtomicInteger SEQUENCE` die bij elke Maven-run weer bij 1 begint, terwijl de
lokale Postgres-database persistent is (geen H2 meer). Na enkele runs botsen nieuwe
testcodes op eerder achtergebleven rijen. Dit is een pre-existing test-infrastructuurprobleem,
niet veroorzaakt door de sorteringsfix op `BundleQueryService` — is dit nu meteen te fixen, of
apart te loggen en later op te pakken?

**Beslissing:** Nu meteen fixen, beperkt tot `BundleHttpTest` (niet de 30+ andere testklassen
met hetzelfde `AtomicInteger SEQUENCE`-patroon, die vandaag niet faalden en dus buiten deze
scope vallen). Fix: dezelfde `System.nanoTime()`+teller-combinatie toepassen die al bewezen is
in `CatalogImportWorkQueueHttpTest` (nieuw in deze cyclus), zodat gegenereerde testcodes uniek
blijven over JVM-herstarts heen.

**Bron:** mens (expliciet gekozen: "Nu meteen fixen" i.p.v. enkel loggen of negeren)

---

## 2026-09-23 — 5f-nalevering: PUT op een LINK-bookmark werkt de koppelingskolom bij

**Vraag:** Bij materialisatie wordt een bookmark met een `LINK_*`-plaats in twee dingen tegelijk
geschreven: de kolom op `import_link` (waar de runtime naar kijkt, aanname A34) én een
`import_link_bookmark_value`-rij (het auditspoor). Bouwstap 5f implementeerde `PUT
/links/{id}/bookmark-values/{name}` zó dat alleen de waarderij wijzigt. Daardoor kunnen de twee na een
wijziging uiteenlopen. Wat moet `PUT` doen?

**Beslissing (mens):** `PUT` werkt de bijbehorende kolom op `import_link` mee bij. De bookmark blijft
dus ook ná materialisatie het invoerveld voor die koppelingskolom; er is één waarheid in plaats van een
auditspoor dat iets anders beweert dan wat de runtime gebruikt. De wijziging blijft achter het
bestaande slot staan (409 `LINK_BOOKMARK_LOCKED_BY_OPEN_BATCH` zolang de koppeling een open batch
heeft), en blijft door `BookmarkValueRules` gevalideerd — inclusief de doelkolomlengte, die nu
werkelijk bepalend is.

Verworpen alternatieven: `PUT` weigeren voor bookmarks met een `LINK_*`-plaats (dwingt een tweede
route via de setup-API af voor iets wat de wizard juist bundelt), en divergentie toestaan (het
auditspoor zou dan kunnen tegenspreken wat er werkelijk toegepast wordt).

**Gevolg — nog te bouwen:** bouwstap 5f is gecommit (`56c8061`) zonder deze propagatie. Er volgt een
aparte bouwstap die `LinkBookmarkValueService.setValue` uitbreidt naar `import_link.library_code`,
`library_search_supplier_code` en de leverancierskoppeling, met een test die bewijst dat waarde en
kolom na een `PUT` niet meer uiteen kunnen lopen.

**Bron:** mens / rapport bouwstap 5f, punt 2; `docs/design/sjabloon-materialisatie-design.md` §4 (D7,
"waarheidsbron na materialisatie"), aanname A34

---

## 2026-09-23 — Samengevoegd met "Frontend: D14 opgepakt, eerste verticale slice Scherm 0"

Dit blok observeerde vanuit een parallelle sessie dezelfde D14-beslissing die hierboven al
volledig staat (met de denker-zwaar-analyse en de vier door de mens beantwoorde vragen). Om
te vermijden dat het logboek twee verschillende verhalen over dezelfde beslissing bijhoudt,
is de inhoud samengevoegd in het blok "2026-09-23 — Frontend: D14 opgepakt, eerste verticale
slice Scherm 0 (werkvoorraad)" hierboven. Zie daar voor de volledige, bindende beslissing.

---

## 2026-09-23 — D13: RPO/RTO-productiedoel voor de importcontrolelaag

**Vraag:** `businessanalyse-catalogimport.md` §28.1/§33 (D13) benoemt expliciet een niet-opgelost
verschil tussen BA1 (`RPO ≤ 24u`, `RTO ≤ 4u`, §16.7/§26.1) en BA2's NF05 (`RPO ≤ 15 minuten`,
`RTO ≤ 4u`) voor de PostgreSQL-database van de importcontrolelaag. Het document zegt zelf dat dit
geen architectuurkeuze is (beide waarden passen op dezelfde technische architectuur) maar dat het
concrete getal vastgesteld moet worden op basis van werkelijke back-up-/hersteltests — welk doel
stellen we vast?

**Bevindingen (denker-gemiddeld):** vandaag bestaat er **geen enkel back-upmechanisme** voor de
CatalogImport-Postgres-database — geen docker-compose, geen `pg_dump`/WAL/point-in-time-configuratie,
nergens in het project. Zowel 24u als 15 minuten RPO zijn dus vandaag evenzeer onhaalbaar; het
fundament ontbreekt. Verzachtende factor: de hervatbare, idempotente kernflow (hoofdstuk 26) vangt
dataverlies binnen het RPO-venster grotendeels op als herwerk (opnieuw screenen), niet als
dubbele publicaties of boekhoudschade — dit is dus eerder een operationeel comfortdoel dan een
integriteitskritische eis.

**Beslissing:** `RPO ≤ 24 uur`, `RTO ≤ 4 uur` (BA1) wordt het productiedoel. Bouw eerst een dagelijkse
`pg_dump`/snapshot-back-up met hersteltest — aanmerkelijk eenvoudiger dan point-in-time recovery via
WAL-archiving (nodig voor 15 min), en er bestaat vandaag nog geen van beide. Een strenger RPO (BA2's
15 min) wordt pas heroverwogen zodra de operationele praktijk (werkelijke storingsfrequentie,
acceptabel herwerk) daarom vraagt. Dit is uitdrukkelijk geen code-/architectuurwijziging in dit
blok — het legt het doel vast; het bouwen en testen van het back-upregime is nog te doen werk,
buiten deze CatalogImport-applicatiecode (hosting/infrastructuur).

**Bron:** denker-gemiddeld (`Onderzoek D13 RPO/RTO-hersteldoel`) / `businessanalyse-catalogimport.md`
§16.7-context, §26.1, §28.1, §29, §33; `business-analyse-leveranciersbibliotheken.md` recovery-/
retentietabel; `Businessanalyse_artikelimport_en_prijsacceptatie-2.md` NF05; mens (optie A bevestigd,
conform aanbeveling)

---

## 2026-09-23 — D13-uitvoering: ontwerp back-up-/hersteltaak (losstaande scripts)

**Vraag:** Hoe landt het dagelijkse `pg_dump`-back-upregime met hersteltest (D13) concreet, gegeven
dat er geen enkele infra-as-code (docker-compose/CI/CD) in dit project bestaat?

**Beslissing:** Losstaande scripts buiten de Spring Boot-applicatiecode, geen scheduled `@Component` —
back-up is infrastructuur, geen applicatielogica.

- **Locatie:** nieuwe top-level map `scripts/backup/` (geen Maven-module, geen naamsbotsing met
  Domain/Dao/Service/Web): `backup-postgres.sh` (productie/Linux/cron) + `backup-postgres.ps1`
  (lokaal/demo Windows-equivalent, Task Scheduler), `restore-and-verify.sh`/`.ps1` (hersteltest).
- **Back-up:** `pg_dump -Fc` naar `$CATALOG_BACKUP_DIR/daily/catalog_import_<db>_<timestamp>.dump`,
  via de native libpq-env-vars (`PGHOST`/`PGPORT`/`PGDATABASE`/`PGUSER`/`PGPASSWORD`, wachtwoord nooit
  als CLI-argument), plus een goedkope `pg_restore --list`-syntaxcontrole na elke dump.
- **Retentie (nieuw, geen brondocument geeft een getal — expliciet een voorstel, geen aanname):**
  laatste 7 dagelijkse dumps in `daily/`, laatste 5 wekelijkse kopieën (zondag) in `weekly/`. Geen
  langere laag: back-upbestanden zijn een eigen, kortere levenscyclus dan de 7-jarige
  applicatiedata-auditretentie uit §16.7 — beide niet verwarren.
- **Hersteltest, aantoonbaar:** restore in een aparte scratch-database (nooit de echte
  `catalog_import`), gevolgd door (a) een Liquibase-statuscontrole ("up to date", geen pending
  changesets) en (b) een eenvoudige rijentelling (`>0`) op de kerntabellen uit changelog 001-003.
  Een sterkere sidecar-countvergelijking is een expliciet uitgestelde verbetering, geen scope-belofte
  nu.
- **Documentatie:** nieuw `docs/design/backup-herstel-design.md`, volgt het bestaande
  `*-design.md`-patroon; bevat het cron-voorbeeld (productie) en het Windows Task
  Scheduler-voorbeeld (lokaal/demo-rehearsal), en verwijst naar dit D13-blok als bron van het
  RPO/RTO-doel.

**Aannames (§6: geen architectuurimpact, later aan te passen):** productie is Linux/cron (onbekend
waar het exact draait — enkel de scheduling-laag verandert als dat niet klopt, niet de scripts);
wachtwoordopslag via `.pgpass`/env var op de host, geen secrets-manager-aanname; retentiegetallen
7/5 zijn een redelijk minimum, geen afgeleide brontekst.

**Geen open vraag voor de mens** — dit raakt geen databasesleutel, identiteitsdefinitie of
mutatiescope (§6), en elke keuze hierboven is zonder herontwerp aanpasbaar.

**Bron:** denker-gemiddeld (`Ontwerp dagelijkse Postgres-back-up + hersteltest`) /
`business-analyse-leveranciersbibliotheken.md` §16.7; `README.md`; `application.yml`/
`application-local.yml`/`application-demo.yml`; dit blok voert het hierboven vastgelegde D13-besluit
uit
