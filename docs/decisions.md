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

**Uitvoering (bouwer-gemiddeld), afwijkingen van het ontwerp — geen §6-vraag, wel vastgelegd:**
1. Het ontwerp nam een bestaande Liquibase-Maven-plugin aan; die bestaat niet (alleen `liquibase-core`
   in `Web/pom.xml`, en het Liquibase-CLI werkt niet door ontbrekende picocli). De hersteltest compileert
   daarom `scripts/backup/LiquibaseStatusCheck.java` on the fly tegen het Maven-classpath van de Web-module
   (`listUnrunChangeSets` op `db.changelog-master.yaml`). `Web/pom.xml` is bewust niet gewijzigd.
2. De applicatierol `catalog_import` heeft geen `CREATEDB`; de hersteltest kan de scratch-database dus niet
   aanmaken zonder een aparte beheerrol. **Opgelost (2026-09-23, opvolging):** `scripts/backup/create-restore-role.sql`
   maakt een aparte rol `catalog_import_restore` (LOGIN + CREATEDB, wachtwoord als psql-variabele); de
   hersteltest draait met die rol via `PGUSER`, de back-up blijft als `catalog_import` draaien. Een beheerder
   voert het SQL-bestand op productie eenmalig zelf uit (§5 van het design-document).
3. Rehearsal-status: `.sh`- én `.ps1`-scripts zijn end-to-end gerepeteerd (backup, retentie 7/5, restore PASS)
   met tijdelijke `docker exec`-shims buiten de repo; er zijn geen PowerShell-bugs gevonden. Een volledige
   hersteltest duurt circa 3,5 minuten (binnen RTO 4u).

---

## 2026-09-23 — Frontend: batchdetail, scherm (2) levering & screening en de §16-uitbreidingen

**Vraag:** De mens gaf opdracht alles wat openstaat te bouwen, behalve Keycloak/Fase 5. Welke bouwvolgorde
en welke scope-/autorisatiekeuzes gelden voor (A) het batchdetailscherm, (B) scherm (2) levering &
screening en (C) de openstaande backenduitbreidingen uit `docs/design/frontend-scherm3-bundel-design.md` §16?

**Beslissing:** Het denker-zwaar-ontwerp is bindend, met de vier onderstaande antwoorden van de mens.
Strikt sequentiële bouwvolgorde, één commit per stap:

1. C1 `statusReason`(+`status`)-filter op `GET /bundles/{id}/mutations` en `GET /batches/{id}/mutations`
   (exacte, hoofdlettergevoelige gelijkheid; blanco = geen filter; onbekende reden = lege pagina).
2. C2 `plannedCount`/`awaitingApprovalCount` op `BundleDetail` (ASSEMBLING gevuld, FROZEN/CANCELLED `null`).
3. C3 `GET /bundles/{id}/freeze-check` (droogloop, momentopname zonder slot; `freeze` blijft de waarheid).
4. C4 filter `?identityHash=` op beide mutatielijsten (zie V3).
5. A-B1 koppelingslabels op `BatchDetail`.
6. B-B1 alleen-lezen, altijd bereikbaar `GET /api/catalog-import/tasks`.
7. F8 `MutationList` (herbruikbaar component, scherm-3-ontwerp §11), F9 `GroupDecisionDialog`,
   F10 `FreezeDialog`/`CancelDialog`, F11 `BundleDecisionsTab`.
8. A-F1 batchdetail `/batches/:batchId` (alleen-lezen) + doorklik vanaf Scherm 0; A-F2 problemen, foutgroepen
   en levering.
9. B-F1 uploadscherm (geen voortgangsbalk maar twee benoemde fasen + tijdteller; deterministische
   `deliveryReference`; idempotente herhaling als herstelroute; geen `AbortSignal`); B-F2 `accept-baseline` (met
   typ-bevestiging) en bundel-opname; B-F3 `continue`.

**Vier vragen door de mens beantwoord (2026-09-23):**
- **V1 (taak aanmaken via UI):** bij het materialisatiewizard-spoor leggen (achter de setup-vlag). Dit spoor
  bouwt GEEN `POST /tasks`; de uploadpagina toont bij een koppeling zonder manuele taak een uitleg.
- **V2 (`continue` aanbieden):** ja, met de expliciete vermelding dat de actie niet op naam wordt vastgelegd
  (het endpoint kent geen actorveld).
- **V3 (wijzigingsgroep/`identity_hash`):** **nu bouwen, als serverzijdig filter** `?identityHash=` — afwijkend
  van de aanbeveling (uitstellen). Gevolg: de mutatielijst gaat van een JPA-entiteitsprojectie naar een
  JDBC-projectie voor die waarde, want `identity_hash` is in `ImportMutation` bewust niet gemapt. Dit is een
  aparte, zwaardere bouwstap (C4) die een werkende laag raakt.
- **V4 (goedkeuringsgetal):** ja, `plannedCount` uit `PublicationBundleDao.countPlanned` en
  `awaitingApprovalCount` uit `countUndecided`, zodat de UI per constructie toont wat de server zal schrijven.

**Ontdekkingen (nog niet teruggeschreven naar de specificatiedocumenten):** `identity_hash` bewust niet
gemapt; het UI-getal "wordt goedgekeurd" wijkt af van `countPlanned`; geen `MaxUploadSizeExceededException`-
handler (413 zonder `code`); upload-voortgang principieel niet meetbaar; `POST /batches/{id}/continue` is de
enige schrijfactie zonder actorveld.

**Bron:** denker-zwaar (`Ontwerp batchdetail, scherm 2 en scherm-3-uitbreidingen`) /
`docs/design/frontend-scherm3-bundel-design.md` §11, §12, §15, §16; `docs/design/sjabloon-materialisatie-design.md`
§9; mens (V1-V4)

---

## 2026-09-24 — C4 uitgevoerd: `identityHash` op de mutatielijsten (gevolgen van V3)

**Vraag:** Hoe wordt het door de mens gekozen `?identityHash=`-filter (V3) uitgevoerd, gegeven dat `identity_hash`
bewust niet als JPA-veld is gemapt, en wat betekent dat voor een eerdere testverwachting?

**Beslissing (bouwer-zwaar, door de hoofdsessie aanvaard):**
- `MutationRow` krijgt een 28e, laatste component `identityHash` (hex, kleine letters; `null` bij lege kolom, dus
  altijd bij `IMPORT_MARKER`). Beide mutatielijsten krijgen `?identityHash=` (hoofdletterongevoelig, blanco = geen
  filter, onbekend/ongeldig = lege pagina 200, onbekende bundel/batch blijft 404).
- Filter via `m.identity_hash = :bytes` (byte-vergelijking, geen `encode()`): draagbaar naar H2, en de indexen
  `idx_import_mutation_link_identity`/`idx_import_mutation_identity_status` blijven bruikbaar. Twee aparte native
  query's; alle aanroepen zonder de parameter draaien letterlijk dezelfde SQL als voorheen. De waarden komen uit één
  extra `MutationDao.findIdentityHashes`-query per pagina (geen N+1); de kolom blijft ongemapt.
- `BundleDecisionService` vult de hash ook op het antwoord van een individuele beslissing, zodat een mutatie niet
  na een beslissing plots `null` toont.
- **Versoepelde testverwachting:** `BatchBaselineHttpTest.theMutationListIsPagedFilterableAndNeverExposesTheBinaryHashColumns`
  eiste dat de respons GEEN `identityHash` bevatte (de oude toestand, §16.4 punt 4). Dat is per V3 achterhaald;
  vervangen door: geen `fingerprint` in de respons, en elke `CREATE`-regel draagt een hash van 64 hex-tekens. De
  binaire kolommen zelf blijven onzichtbaar.
- Frontendtypes (`MutationRow.identityHash`) en de doorgifte van de nieuwe filters (`statusReason`, `identityHash`)
  in `Frontend/src/api/*` horen bij F8 (`MutationList`), niet bij deze backendstap.

**Bekende beperkingen:** een extra query per mutatielijstpagina; H2 niet getest (alle tests draaien op PostgreSQL);
geen lengtevalidatie op 32 bytes (`?identityHash=ab` = lege pagina, gelijk aan "onbekend").

**Technische constraint ontdekt (bouwproces):** `mvn test-compile` herbouwt testbronnen niet wanneer enkel de
classpath (een andere module) wijzigt; na een signatuurwijziging in `Service`/`Dao` kan een groene build dus
misleidend zijn totdat een testbron zelf wijzigt of `mvn clean` draait. Idem: draai `mvn -pl Web -am install
-DskipTests` vóór een handmatige `spring-boot:run`, anders draait een verouderde `Service`-jar (zie het scenario).

**Bron:** bouwer-zwaar (`C4 identityHash-filter en -veld op mutatielijsten`) / mens (V3) /
`docs/design/frontend-scherm3-bundel-design.md` §11.5, §16.4

---

## 2026-09-24 — F9-voorwaarden: groepsactie met `identityHash` en `statusReason` (nieuwe stap C5)

**Vraag:** De groepsactie (`POST /bundles/{id}/decisions`, `BundleDecisionService.DecisionFilter{batchId, status,
statusReason, actionType}`) kent geen `identityHash`-filter, terwijl de mutatielijst dat sinds C4 wel heeft
(F8-bevinding). §10.4 punt 1 eist dat de groepsactie exact de zichtbare lijstfilter gebruikt; anders raakt de actie
meer mutaties dan de gebruiker ziet. Ook was `statusReason` uit de groepsactie gesloten omdat de lijst er niet op kon
filteren (§10.4 punt 2) — sinds C1 kan dat wel.

**Beslissing (mens, 2026-09-24):**
1. **`identityHash` wordt aan het groepsfilter toegevoegd** (nieuwe backendstap **C5**, vóór F9), afwijkend van de
   aanbeveling (knop uitschakelen). De groepsactie beslist dan exact wat de gefilterde lijst toont.
2. **`statusReason` mag in de groepsactie** (het filter ondersteunt het al); F9 gebruikt dezelfde filter als de lijst.

**Gevolg:** C5 breidt `DecisionFilter` + de onderliggende selectie (`PublicationBundleDao`, `GROUP_DECISION_TAIL`-pad) met
`identityHash` uit (byte-vergelijking zoals C4). Invariant (test): `affectedCount` van een groepsactie ≤
`totalElements` van de lijst met dezelfde filter, met gelijkheid voor mutaties die de actie mag raken (CREATE/UPDATE,
zonder beslissing). F9-bouwvolgorde: C5 → F9 → F10 → F11.

**Bron:** mens / bouwer-zwaar-bevinding F8 (risico 2 en 3) / `docs/design/frontend-scherm3-bundel-design.md` §10.4, §16.1

---

## 2026-09-24 — Onbekende velden in de groepsactie weigeren (stap C6)

**Vraag:** Jackson negeert onbekende JSON-velden (Spring Boot-default `FAIL_ON_UNKNOWN_PROPERTIES = false`). Bij
`POST /bundles/{id}/decisions` valt een filterveld dat de server niet kent dus stil weg, en raakt de groepsactie meer
mutaties dan de gebruiker ziet (ontdekking F9). `@JsonIgnoreProperties(ignoreUnknown = false)` kan de globale default
niet strenger maken. Opties: A gericht op dit ene endpoint, B globaal, C niets.

**Beslissing (mens, 2026-09-24):** A. Enkel `POST /bundles/{id}/decisions` weigert onbekende velden, op topniveau én
in `filter`, via een expliciete controle in de Web-laag (whitelist op de ruwe JSON of een strenge `ObjectReader`).
Antwoord: 400 via `BadRequestException` met de nieuwe code `DECISION_FILTER_UNKNOWN_FIELD` en de veldnaam in `error`,
plus een entry in `Frontend/src/errors/codes.ts`. `DecisionFilter` in Service blijft vrij van Jackson. De globale
Jackson-configuratie blijft ongewijzigd (de contracttest `AcceptBaselineReviewFlowTest` r.260-278 blijft geldig).

**Bron:** mens / denker-zwaar (open punten scherm 3) / `docs/design/frontend-scherm3-bundel-design.md` §10.4

---

## 2026-09-24 — `expirableCount` op `BundleDetail` (stap C7)

**Vraag:** `CancelDialog` telt het aantal mutaties dat bij annuleren vervalt met zes lijstaanroepen
(`expiringMutations.ts`), een spiegel in de UI van `PublicationBundleDao.EXPIRABLE_TAIL`. Hetzelfde patroon is voor
`plannedCount` verworpen (V4/C2).

**Beslissing (mens, 2026-09-24):** nu bouwen. Additief veld `expirableCount` op `BundleDetail`, gevuld door de
backendtelling (`countExpirableMutations`) bij ASSEMBLING én FROZEN, `null` bij CANCELLED. `CancelDialog` gebruikt dat
veld; de zes lijstaanroepen en `expiringMutations.ts` vervallen.

**Bron:** mens / denker-zwaar (open punten scherm 3) / `docs/design/frontend-scherm3-bundel-design.md` §10.6

---

## 2026-09-24 — Koppelingsnaam in `BundleBatchesTab` (§16.5)

**Vraag:** `BundleBatchesTab` toont `#importLinkId` terwijl `GET /import-links` al bestaat (S0-B3).

**Beslissing (mens, 2026-09-24):** de naam van de koppeling tonen via de bestaande `importLinks`-API, zoals Scherm 0
dat doet. Het design (§10.4 punt 2, §16.1, §16.4) wordt voorlopig niet bijgewerkt.

**Bron:** mens / denker-zwaar (open punten scherm 3) / `docs/design/frontend-scherm3-bundel-design.md` §16.5

---

## 2026-09-25 — Fase 5: opknipping, volgorde en vier beslissingen voor 5-AUTH/5-PERM

**Vraag:** Hoe wordt Fase 5 (Keycloak, rechten via Prodis, publicatie naar ProDisWebbase/Pervasive) opgeknipt,
in welke volgorde, en welke §6-keuzes gelden voor identiteit, loginflow, rechtenbron en actiemapping?

**Beslissing:** Fase 5 in drie sporen: **5-AUTH** (Keycloak + geverifieerde identiteit) → **5-PERM** (rechten via
Prodis) → **5-PUB** (a SIMULATION → b TRIAL_LIBRARY → c PRODUCTION). **5-AUTH eerst.** PRODUCTION (en TRIAL) blijven
dicht tot het verwerkingscontract van 252 IMPORT/1179 (leeg = behoud of wissen, deletecodes, resultaat/OUT02) bewezen is.
De publicatievragen (doelmodus/`publication_run`, bron van de te publiceren waarden, adapterplaats) worden pas bij 5-PUB
aan de mens voorgelegd.

Door de mens beantwoord (alle conform de aanbeveling):
1. **Q1 (identiteit/datamodel):** bestaande `*_by`-kolommen krijgen `preferred_username`; additieve, nullable
   `*_by_subject`-kolommen (Keycloak `sub`) op de tabellen waar ondertekend wordt. Oude rijen blijven `NULL` =
   "vóór Fase 5, niet geverifieerd". Geen hernoeming, geen aparte actortabel.
2. **Q2 (loginflow/contract):** BFF — `Web` doet `oauth2Login` met sessiecookie en levert de SPA later statisch uit
   (Prodis-patroon). Actorvelden in requests blijven voorlopig optioneel; indien meegestuurd moeten ze gelijk zijn aan de
   tokenidentiteit, anders 400 `ACTOR_FIELD_MISMATCH`. Nooit stil negeren; verwijderen in een latere, aangekondigde stap.
3. **Q3 (rechtenbron):** token relay naar het bestaande Prodis-endpoint `GET /api/account`; geen nieuw
   machine-to-machine-contract en nooit aansluiten op `hasDdaProdisApiBypass()`. Fail-closed als Prodis onbereikbaar is.
   Voorwaarde: het CatalogImport-token mag audience `account` dragen; Prodis seedt `catalogImport.read/.manage/.approve`.
4. **A2 (actiemapping):** `read` = alle GET's; `manage` = upload, `continue`, setup/materialisatie/bookmark-PUT, bundel
   aanmaken, batches toevoegen/verwijderen; `approve` = accept-baseline, beslissingen, bevriezen, annuleren, publiceren
   (alle modi).

**Aannames (doorwerken tenzij herroepen):** zelfde Keycloak-realm als Prodis met een eigen CatalogImport-client; `system`
blijft verboden als ondertekenaar; één instantie, publicatie serieel per bibliotheek; eerste publicatiescope BASE_PRICE +
percentages + DESCRIPTION; `accept-baseline` blijft bestaan naast `state_origin = PUBLISHED`; setup-API-vlag blijft extra
bescherming tot er een scherm-1a-ontwerp is.

**Extern, door de mens op te vragen (blokkeert 5-PUB-b/c, niet 5-AUTH):** verwerkingscontract 252 IMPORT/1179 + OUT02;
adapterplaats/toegang Pervasive; Keycloak-client + `PermissionRight`-seed in Prodis; controlebibliotheek voor proef.

**Important technical constraint discovered (nog terug te schrijven naar een fase5-design):** de WebBase-uitvoerder
(`ProDisWebbase/.../ImportDefinitionRepositoryImpl.java:93-96`) zet lege/onleesbare numerieke waarden stil op 0 en
converteert bedragen via `float`; `Prodis1232Impl.createImportFile` wist bij elke run alle `PSIMP{bib}*`-bestanden;
`import_mutation` draagt buiten basisprijs/referenties geen nieuwe veldwaarden (omschrijving/percentages enkel in staging,
7 dagen retentie).

**Bron:** denker-zwaar (`Fase 5 intake en ontwerpvoorstel`, 2026-09-25) / mens (Q1-Q3, A2) /
`business-analyse-leveranciersbibliotheken.md` §14.23, §14.25-§14.26, §16.5, §16.7-§16.8; decisions 2026-09-18
(permissies, publicatiedoel), 2026-09-22 (publicatiebreedte)

---

## 2026-09-25 — Fase 5-AUTH: ontwerp bindend (V1, V2, G1)

**Vraag:** Het denker-zwaar-ontwerp voor 5-AUTH (`docs/design/fase5-auth-design.md`) liet drie punten open: (V1) hoe
lokaal/demo en scripts werken zonder bypass, (V2) hoe snel ingetrokken toegang ingaat, (G1) of ook de configuratietabellen
een `*_by_subject` krijgen.

**Beslissing:** `docs/design/fase5-auth-design.md` is bindend. Door de mens beantwoord (alle conform aanbeveling):
- **V1 = A1:** geen omzeiling, geen `local-noauth`-profiel. Lokaal draait altijd de Prodis-Keycloak (realm `prodis`, client
  `catalog-import`); tests zonder Keycloak via een testconfiguratie die niet in het artefact zit. curl-voorbeelden en het
  scenarioscript worden herschreven naar een browsersessie of voorlopig handmatig (stap 5A-7). Bearer-tokens voor scripts
  enkel via een latere, aparte beslissing.
- **V2 = B1:** toegang blijft tot de sessie-idle-timeout (30 min) tot 5-PERM; geen back-channel logout in 5-AUTH.
- **G1 = ja:** ook de configuratietabellen (definitie, revisie, mapping, filter, kritiekheid, bookmarks) krijgen een
  `*_by_subject` (changeset 007-4, stap 5A-6); 18 kolommen op 12 tabellen. `NULL` betekent overal "geen geverifieerde
  identiteit".

Kernkeuzes uit het ontwerp: BFF met `spring-boot-starter-oauth2-client`, geen resource-server; `/api/**` geeft 401 JSON
`AUTHENTICATION_REQUIRED` (geen redirect); CSRF via cookie + header, token alleen uit de header; `CurrentActor` in Web als
enige lezer van de SecurityContext, Service krijgt `ActorIdentity` via additieve overloads (geen Spring Security in Service);
foutcodes `ACTOR_FIELD_MISMATCH` (400), `SYSTEM_ACTOR_FORBIDDEN`/`ACTOR_IDENTITY_INVALID`/`CSRF_TOKEN_INVALID` (403);
`GET /me` met `permissions: null` als haakje voor 5-PERM; subject nooit in API-antwoorden. Bouwstappen 5A-1 … 5A-7 strikt
sequentieel; 5-AUTH wordt als geheel uitgerold.

**Externe voorwaarde (C7):** voor de handmatige acceptatie (5A-3) is een Keycloak-client `catalog-import` in de lokale
realm nodig, met redirect-URI's `http://localhost:8081/login/oauth2/code/keycloak` en
`http://localhost:5173/login/oauth2/code/keycloak`. De geautomatiseerde tests hebben hem niet nodig.

**Bron:** denker-zwaar (`Ontwerp 5-AUTH Keycloak identiteit`) / mens (V1, V2, G1) / `docs/design/fase5-auth-design.md`


## 2026-09-25 — Read-only PSIMPORT-preview van een bevroren bundel (slice 1)
**Vraag:** Mag er vóór Fase 5 een droge PSIMPORT-projectie komen om de uitgaande data lokaal te testen, terwijl 5-PUB dicht blijft?
**Beslissing:** Ja, door de mens beantwoord (alle vier conform aanbeveling):
- **Toegestaan als PREVIEW:** `GET /api/catalog-import/bundles/{id}/psimport-preview` (`?format=json|csv`, gepagineerd, vaste sortering
  `batch_id asc, m.id asc`). Alleen FROZEN bundels (409 `BUNDLE_NOT_FROZEN`); alleen mutaties met status `READY_FOR_PUBLICATION` en
  `actionType` CREATE/UPDATE. Geen afleverregister, geen outbox, geen schrijfactie naar ProDisWebbase, geen migratie; `ARIMP_Verwerken`,
  `ARIMP_DELETE`, `ARIMP_Record` en `ARIMP_Nummer` krijgen nooit een waarde (`NOT_CONTRACTED`). 5-PUB blijft dicht.
- **Veldnamen:** interne codes (`import_field_catalog.code`) zijn leidend; de h.26-naam is enkel label.
- **Scope slice 1:** alleen wat `import_mutation` draagt (identiteit, basisprijs, valuta, referenties). Omschrijving en percentages uit
  staging zijn een latere, aparte slice.
- **Toegang:** vrij lezen zoals `GET /bundles`, niet achter `catalogimport.setup-api.enabled`.
- **Antwoord:** elk antwoord draagt `previewOnly: true`, `contractStatus: "UNVERIFIED_FIELD_INVENTORY"`, `previewSpecVersion`,
  `bundleContentHash` (hex van `publication_bundle.content_hash`; geen nieuwe hash persisteren) en `generatedAt`. Elk veld is
  `{value, state}` met `state` VALUE | NOT_MAPPED | NOT_AVAILABLE_IN_MUTATION | NOT_CONTRACTED | UNKNOWN; nooit stil 0, "" of een
  aangenomen valuta (`base_price_currency = null` geeft `UNKNOWN` en rijvlag `complete = false`); bedragen als string
  (`toPlainString()`). Dezelfde bevroren bundel geeft twee keer een identiek antwoord, met uitzondering van `generatedAt` (opvraagtijdstip).
- **Lagen:** `PsimportPreviewController` (Web) -> `PsimportPreviewService` (`@Transactional(readOnly = true)`) -> `PsimportPreviewDao`
  (JDBC-projectie, zelfde patroon als het `identity_hash`-pad uit C4).

> **Important technical constraint discovered:** omschrijving en VKP-percentages zijn niet reconstrueerbaar uit `import_mutation`
> (die draagt enkel `before/after_base_price`, `base_price_currency`, identiteitsvelden en `before/after_reference_value`); ze staan
> in batch-gebonden staging (`import_candidate_stage.description`, `import_candidate_price.percentage`). Terugschrijven naar
> `docs/design/fase4-publication-bundle-design.md` nog niet gedaan (wacht op akkoord van de mens, AGENT.md §5).

**Bron:** denker-zwaar (`Denker: PSIMPORT-projectie ST-13`) / mens (vier keuzes) / Businessanalyse_artikelimport_en_prijsacceptatie-2.md §15.6-15.10 + h.26, docs/decisions.md 2026-09-18, 2026-09-22 en 2026-09-25

## 2026-09-26 — Ontdekkingen teruggeschreven naar de ontwerpdocumenten
**Vraag:** Mogen de tijdens het bouwen ontdekte regels en constraints (AGENT.md §5) teruggeschreven worden naar de ontwerpdocumenten?
**Beslissing:** Ja, door de mens goedgekeurd ("Alle 3", 2026-09-26). Uitgevoerd door bouwer-gemiddeld en tegen code gecontroleerd:
- `docs/design/fase2-screening-design.md` §18 (upload/accept-baseline/continue na 5-AUTH; ontdekkingen C3 en C9; synchrone upload).
- `docs/design/fase4-publication-bundle-design.md` §16 (PSIMPORT-preview, met de constraint over omschrijving/percentages) en §17 (na 5A-2 en 5A-4).
- `docs/design/fase5-auth-design.md`: verduidelijking bij A6 (geen subject in domeinantwoorden; `GET /me` geeft het wel) en nieuw §13 (actorvelden optioneel, foutvolgorde 400/403 vóór 404/409, `createDefinition` slaat de tokennaam op, feitelijke frontendstand).
- `docs/design/frontend-scherm3-bundel-design.md`: nota's bij §7 en §16, nieuw §20 (actor/`/me`/CSRF/login-redirect, proxy met `changeOrigin: false`, ontdekkingen C6 en C10, backendstand).
- `docs/requirements/catalog-import-acceptance.md`: testinstructies na 5-AUTH (gerichte tests, `scripts/test/run-full-tests.ps1` in eigen schema, Frontend-typecheck met `-p tsconfig.app.json`).
**Niet teruggeschreven (bewust):** het blok over de WebBase-uitvoerder die lege waarden stil op 0 zet (het bestemde 5-PUB-design bestaat nog niet), en de ontdekkingen uit de 2026-09-23-entry (`identity_hash` niet gemapt, UI-getal "wordt goedgekeurd" versus `countPlanned`, geen handler voor `MaxUploadSizeExceededException`, upload-voortgang niet meetbaar) omdat ze niet tegen de code geverifieerd zijn en geen bestemming noemen.
**Bron:** mens / bouwer-gemiddeld (`Schrijf ontdekkingen terug naar docs`)

## 2026-09-26 — Vier keuzes uit het beslisdossier: 5-PERM, bundelsnapshot, 5-PUB SIMULATION, credentials
**Vraag:** Welke richting voor de vier onderdelen die de rest ontgrendelen (beslisdossier van denker-zwaar, 2026-09-26)?
**Beslissing:** Door de mens beantwoord:
- **5-PERM = nu bouwen met een lokaal instelbare rechtenbron; de Prodis-adapter (token relay naar `GET /api/account`) is de laatste, losse stap.**
  Alles behalve die ene adapter is Prodis-onafhankelijk: actiemapping op alle endpoints (read = alle GET; manage = upload/continue/setup/bundel
  aanmaken en batches; approve = accept-baseline/beslissingen/bevriezen/annuleren), 403 `PERMISSION_DENIED`, `/me.permissions` vullen, knoppen
  uitgeschakeld-met-reden in de SPA. Rechten worden per verzoek opgehaald (intrekking werkt direct). Fail-closed. De setup-API-vlag blijft naast `.manage`.
  Nooit via `hasDdaProdisApiBypass()`. Bindend blijven de 2026-09-25-beslissingen Q3/A2.
- **Bundelsnapshot bij bevriezen:** omschrijving en VKP-percentages worden bij het bevriezen vastgelegd in een eigen snapshottabel (geen extra kolommen
  op `import_mutation`), zodat een bevroren bundel zelfdragend is en niet van stagingretentie afhangt. Dit is ook de databron voor 5-PUB en voor
  PSIMPORT-preview slice 2. Kandidaatstaging mag pas worden opgeruimd als de batch terminaal is en buiten elke bundel valt.
- **5-PUB-a (SIMULATION) mag gebouwd worden:** afleverregister (`publication_run`), uitgaande wachtrij en statusmachine, alleen SIMULATION, hergebruikt de
  PSIMPORT-projectie; er wordt NIETS naar ProDisWebbase of Pervasive geschreven. 5-PUB-b (TRIAL_LIBRARY) en 5-PUB-c (PRODUCTION) blijven dicht tot het
  verwerkingscontract (252 IMPORT/1179, ARIMP_DELETE-codes, OUT02) bewezen is.
- **Credentials voor externe bronnen (SFTP/API): versleutelde opslag bouwen** (eigen versleutelde kolom met sleutelbeheer). De brondocumenten verbieden leesbare
  opslag, ook tijdelijk; sleutelbeheer (waar leeft de sleutel, rotatie, wie mag ontsleutelen) is een open ontwerpvraag die eerst door een Denker uitgewerkt en
  aan de mens voorgelegd wordt vóór de Bouwer start. Tot dan: geen server-side ophaling.
**Bron:** mens (vier keuzes) / denker-zwaar (`Beslisdossier geblokkeerde onderdelen`) / docs/decisions.md 2026-09-22, 2026-09-23, 2026-09-25

## 2026-09-26 — 5-PERM: ontwerp bindend (V1-V4) en bouwvolgorde 5B-1 … 5B-7
**Vraag:** Vier open keuzes uit het 5-PERM-ontwerp (denker-zwaar): foutvolgorde, rechtenmodel, testlogin, lot van de setup-API-vlag.
**Beslissing:** `docs/design/fase5-perm-design.md` is bindend. Door de mens beantwoord:
- **V1 = recht eerst:** 403 `PERMISSION_DENIED` komt vóór 400 `ACTOR_FIELD_MISMATCH` en vóór 404/409 (wijzigt de volgorde in `fase5-auth-design.md` §13.1).
- **V2 = hiërarchisch (afwijkend van de aanbeveling "plat"):** `approve` impliceert `manage` en `read`; `manage` impliceert `read`. CatalogImport leidt het effectieve recht af uit de
  ruwe codes van de bron; `GET /me.permissions` geeft de effectieve set. (Gevolg: wie `.approve` seedt krijgt automatisch lees- en beheerrechten.)
- **V3 = standaardtestlogin krijgt alle drie de rechten:** bestaande testaanroepen blijven ongewijzigd; weigering wordt in gerichte tests met `as(user, READ)` bewezen.
- **V4 = de setup-API-vlag vervalt (afwijkend van de aanbeveling "blijft tot scherm 1a"):** `catalogimport.setup-api.enabled` en de `@ConditionalOnProperty` op de setup-, template- en linkcontrollers verdwijnen in stap 5B-3,
  nadat die endpoints hun `MANAGE`/`READ`-annotatie dragen. Dit is een bewuste verwijdering van een config-property (AGENT.md §6) door de mens; `SetupApiDisabledTest` wordt vervangen door
  tests op 403 zonder recht.
Overige punten volgen de eerdere bindende beslissingen (lokale `ConfiguredPermissionSource` per username, fail-closed zonder default, geen `hasDdaProdisApiBypass()`, Prodis-adapter als laatste stap 5B-7).
**Bron:** mens (V1-V4) / denker-zwaar (`Ontwerp 5-PERM rechtenlaag`) / docs/decisions.md 2026-09-25 en 2026-09-26

## 2026-09-26 — Herroeping van V4: de setup-API-vlag blijft als echte beveiliging
**Vraag:** De vorige entry ("5-PERM: ontwerp bindend (V1-V4)") liet de vlag `catalogimport.setup-api.enabled` vervallen (V4). Blijft dat zo?
**Beslissing:** Nee. Door de mens herroepen ("Setup vlag toch als echte beveiliging, ik heb me bedacht"). **V4 uit de vorige entry is vervangen:** de vlag en de
`@ConditionalOnProperty` op `CatalogImportSetupController`, `CatalogImportTemplateController` en `CatalogImportLinkController` blijven bestaan en gelden als tweede, onafhankelijke
beveiliging naast `MANAGE`/`READ`. Vlag uit = die endpoints bestaan niet (404, ongeacht rechten); vlag aan = recht vereist (403 `PERMISSION_DENIED` zonder). Er wordt dus géén config-property
verwijderd, `SetupApiDisabledTest` blijft, en de vlag verdwijnt niet uit `application*.yml`, README en handleiding. V1, V2 (hiërarchisch) en V3 uit de vorige entry blijven ongewijzigd.
`docs/design/fase5-perm-design.md` §5 en de rij 5B-3 zijn aangepast; 5B-1 (al gebouwd) was hierdoor niet geraakt.
**Bron:** mens / docs/design/fase5-perm-design.md

## 2026-09-26 — 5-PUB (deel a): ontwerp bindend (bundelsnapshot en SIMULATION-run) en bouwvolgorde 5P-1 … 5P-8
**Vraag:** Vier open keuzes uit het ontwerp voor de bundelsnapshot en 5-PUB-a: hash, oude bundels, artefactopslag, annuleren.
**Beslissing:** `docs/design/fase5-pub-design.md` is bindend. Door de mens beantwoord (alle vier conform aanbeveling):
- **Hash:** `publication_bundle.content_hash` blijft byte-identiek voor alle bundels; de snapshot krijgt een aparte `snapshot_hash` (+ `snapshot_spec_version`). Bestaande bundels houden `snapshot_hash = null`.
- **Oude bundels:** geen automatische backfill. Een SIMULATION-run mag op een bundel zonder snapshot draaien, met zichtbare onvolledigheid (`incompleteRowCount > 0`, preview toont `NOT_SNAPSHOTTED`); niets wordt stil ingevuld of op 0 gezet.
- **Artefact:** op het bestandssysteem via een `PublicationArtifactStore` (patroon `DeliveryArchiveStore`), SHA-256 en grootte in de database, de bytes nooit in de database.
- **Annuleren:** een geslaagde SIMULATION-run blokkeert het annuleren van de bundel niet; de runs blijven als auditspoor.
Overig volgt uit eerdere bindende beslissingen: recht APPROVE voor het starten van een run (A2, 2026-09-25); TRIAL_LIBRARY/PRODUCTION geven altijd 409 `PUBLICATION_MODE_NOT_ENABLED`; staging zonder snapshot blokkeert het bevriezen
(`SNAPSHOT_SOURCE_MISSING`); de opruiming van staging wordt nu alleen als regel + read-only guard vastgelegd. Er wordt niets naar ProDisWebbase, PSIMPORT of Pervasive geschreven.
**Bron:** mens (vier keuzes) / denker-zwaar (`Ontwerp bundelsnapshot en 5-PUB-a`) / docs/decisions.md 2026-09-25 en 2026-09-26

## 2026-09-26 — 5-PUB (deel a) uitgevoerd: bundelsnapshot en SIMULATION-run (5P-1 t/m 5P-8)
**Vraag:** Is de uitvoering van het bindende ontwerp `docs/design/fase5-pub-design.md` afgerond en geverifieerd?
**Beslissing:** Ja. Alle acht stappen zijn gebouwd; de volledige Web-ronde (`scripts/test/run-full-tests.ps1`, eigen schema) is groen: 92 klassen, 1086 tests, 0 fouten. Wat er nu bestaat: snapshottabellen (008) gevuld bij bevriezen met `SNAPSHOT_SOURCE_MISSING`-blokkade en
een aparte `snapshot_hash` (`content_hash` byte-identiek); PSIMPORT-preview slice 2 (omschrijving/VKP-percentages uit de snapshot, `NOT_SNAPSHOTTED` voor oude bundels); een read-only retentieguard (alleen `FAILED`/`BASELINE_ACCEPTED`; er is geen delete); `publication_run` (009) met statusmachine
`REQUESTED → PREPARING → SIMULATED|FAILED` en de database-afgedwongen uitzondering van hoogstens één actieve run per bundel; `PublicationRunService` + `PublicationArtifactStore` (artefact op het bestandssysteem, SHA-256 in de database); vier endpoints
(POST = APPROVE, drie GET's = READ). Er wordt NIETS naar ProDisWebbase, PSIMPORT of Pervasive geschreven; TRIAL_LIBRARY en PRODUCTION geven altijd 409 `PUBLICATION_MODE_NOT_ENABLED`. Afwijkingen en ontdekkingen staan in `fase5-pub-design.md` §7 (o.a. de constraintfout `= true` versus `is true`, de
guard die strenger is dan `isTerminal()`, en het risico van een vastgelopen `PREPARING`-run zonder recovery). Nog open: 5-PUB-b/c (geblokkeerd op het verwerkingscontract), herstel van vastgelopen runs, Frontend (foutcodes + scherm voor runs), versleutelde credentials (eerst een sleutelbeheerontwerp).
**Bron:** hoofdsessie na verificatie (volledige testronde) / docs/design/fase5-pub-design.md

## 2026-09-26 — Ontbrekende valuta = euro (herziening van aanname A22)
**Vraag:** Wat gebeurt er als een levering geen valuta bevat? Tot nu gold aanname A22 ("nooit stilzwijgend EUR veronderstellen": munt blijft `null` = onbekend, rij onvolledig).
**Beslissing:** Door de mens gewijzigd: als er geen valuta aanwezig is, **wordt uitgegaan van de euro**. Keuzes:
- **Toepassing bij het verwerken van de levering (screening/normalisatie):** zonder muntveld en zonder waarde wordt `EUR` vastgelegd, zodat mutaties, bundels, snapshot, preview en run overal dezelfde munt zien en de onvolledigheid door een ontbrekende valuta verdwijnt.
- **Zichtbaar markeren:** naast de munt komt een herkomst (uit de bron versus standaard `EUR`), zodat een aangenomen euro altijd herkenbaar en later te onderscheiden blijft. Het aannemen is dus een expliciete, gedocumenteerde standaardregel en geen stille aanpassing (AGENT.md §2 principe 8).
- **Vaste variabele per import (toevoeging mens):** het is ook aanvaardbaar dat de valuta als vaste instelling van de import wordt meegegeven. Interpretatie van de hoofdsessie ("prijs" gelezen als "valuta"; nog door de mens te bevestigen): een importdefinitierevisie kan een vaste valuta dragen die geldt wanneer de bron geen munt levert, met `EUR` als standaard als ook die ontbreekt.
- **Compatibiliteit:** de munt zit in `price_fingerprint`; de wijziging vereist daarom een aparte canonicalisatieversie (patroon van versie 2 naast 1), zodat bestaande bronstaat en bevroren bundels geen valse wijzigingen of een gewijzigde `content_hash` krijgen. Het ontwerp hiervoor volgt van een Denker.
**Bron:** mens / eerdere regel: aanname A22 in `CandidateNormaliser` (Service) en de PSIMPORT-preview-beslissing van 2026-09-25 (`base_price_currency = null` gaf `UNKNOWN`)

## 2026-09-26 — Valuta-standaard: ontwerp bindend (per koppeling, geen versiebump, geen backfill) en bouwvolgorde V-1 … V-7
**Vraag:** Drie open keuzes uit het ontwerp voor "ontbrekende valuta = euro": waar de vaste valuta wordt ingesteld, hoe bestaande vingerafdrukken compatibel blijven, en wat met bestaande gegevens.
**Beslissing:** `docs/design/valuta-standaard-design.md` is bindend. Door de mens beantwoord:
- **V1 = per koppeling (leverancier), niet per revisie (afwijkend van de aanbeveling):** de vaste valuta staat op `import_link.default_currency` en wordt bij het aanmaken van de koppeling vastgelegd; ze zit niet in de revisiehash. Dit vervangt de "revisie-instelling" uit de vorige entry.
- **V2 = geen canonicalisatieversie 3:** een aangenomen `SYSTEM_DEFAULT`-euro wordt in de vingerafdruk met de bestaande "niet gemapt"-marker (U+0000) geschreven; bestaande revisies houden byte-identieke hashes. Dit vervangt de zin "aparte canonicalisatieversie nodig" in de vorige entry, omdat een versiebump ook de identiteit van elke aanbieding verandert
  (massa-CREATE) en bestaande bronstaat niet te migreren is. Versie 3 blijft gereserveerd.
- **V3 = geen backfill:** bestaande rijen zonder valuta blijven `null`/onbekend en convergeren bij de volgende aanvaarding; bevroren bundels (`content_hash`, `snapshot_hash`) veranderen niet.
- Herkomst zichtbaar: `SOURCE`, `LINK_DEFAULT` of `SYSTEM_DEFAULT` (kolom `base_price_currency_origin`); een ongeldige bronwaarde blijft blokkeren en wordt nooit stil vervangen door EUR.
**Bron:** mens (V1-V3) / denker-zwaar (`Ontwerp standaardvaluta EUR`) / docs/decisions.md 2026-09-26 ("Ontbrekende valuta = euro")

## 2026-09-26 — Valuta-standaard teruggebracht tot het minimum (V-3, V-5 herkomstveld en V-6 vervallen)
**Vraag:** De valuta wijzigt per koppeling nooit per import; is het uitgebreide ontwerp (herkomst doorschrijven naar bronstaat en mutatie, herkomstveld in preview en run, Frontend-weergave) nodig?
**Beslissing:** Nee, door de mens teruggebracht ("dit duurt lang voor 1 veldje, dit zal nooit wijzigen per import"). Blijft: V-1 (schema `010`) en V-2 (normaliser: bronveld, dan vaste valuta van de koppeling, dan EUR; ongeldige bronwaarde blijft verwerpen; vingerafdrukken byte-identiek via de bestaande marker; delta-correcties in `domainMask` en `componentDiffers`), plus V-4 (vaste valuta bij het aanmaken van een koppeling)
en V-7 (documentatie). **Vervallen:** V-3 (herkomst doorschrijven naar `catalog_source_state` en `import_mutation`), het herkomstveld `BASE_PRICE_CURRENCY_ORIGIN` in preview/run en de `previewSpecVersion`-ophoging daarvoor (V-5), en de Frontend-weergave (V-6). De reeds aangemaakte kolommen `base_price_currency_origin` op `catalog_source_state` en
`import_mutation` blijven ongebruikt (`null`); `import_candidate_stage.base_price_currency_origin` wordt door V-2 nog wel gevuld en de `domainMask`-correctie leunt daarop. De eerdere keuze "herkomst zichtbaar markeren" geldt daardoor alleen nog op de staging; een aangenomen euro is in mutaties en preview niet meer van een geleverde te onderscheiden. Bewust aanvaard.
**Bron:** mens / docs/design/valuta-standaard-design.md (§7 herzien)

## 2026-09-27 — Vervolgprioriteit na 5-PUB-a/valuta: eerst statusdocumenten gelijktrekken, externe blokkades vastleggen
**Vraag:** Er is geen lopende onafgeronde bouwvolgorde meer. Een denker-zwaar gap-analyse (27/09) leverde vier gelijkwaardige kandidaten op om nu op te pakken: (a) Frontend voor de publicatierun + herstel van een vastgelopen `PREPARING`-run, (b) heropenen van het uitstel van scherm 1a/1b en het D14-vervolg (behandelgeval/toewijzing) — de voorwaarde "tot na Fase 5/Keycloak" is inmiddels vervuld, (c) sleutelbeheerontwerp voor versleutelde credentials, (d) enkel de achterhaalde statusdocumenten bijwerken.
**Beslissing:** Door de mens: eerst optie (d) — geen nieuwe bouw, enkel de documenten die de gap-analyse als materieel achterhaald aanmerkte gelijktrekken met de werkelijke codestatus:
- `docs/analysis/current-project-vs-businessanalyse-2.md` (T1: onderschat wat er staat — authenticatie/autorisatie, publicatiebundel-besluit en Frontend-dashboard/UI zijn wél gebouwd; T2: het acceptance-document ten onrechte als "niet langer actueel" aangemerkt).
- `docs/requirements/catalog-import-acceptance.md` r.33 (T3: vermeldt nog het niet-werkende `mvn -pl Web -am test` naast de latere §35/§53-aanvulling die het juiste, geïsoleerde testcommando geeft).
- `README.md` r.34-36 (T5: "Nog niet aanwezig" maakt geen onderscheid tussen 5-PUB-a — wél gebouwd — en 5-PUB-b/c — niet gebouwd; de publicatierun ontbreekt bovendien volledig in `docs/handleiding/`).
- `docs/design/frontend-scherm3-bundel-design.md` r.19 (T6: noemt scherm 0 nog als uitgesteld, terwijl het gebouwd is en het uitstel op 2026-09-23 al herroepen werd).
- `docs/design/valuta-standaard-design.md` r.58 (V-7: documentatiestatus "In uitvoering" afronden of expliciet openstaand laten staan).
Daarnaast worden de vijf externe blokkades uit de gap-analyse (Keycloak-client + redirect-URI's, audience/permissierechten in Prodis voor 5B-7, het verwerkingscontract 252 IMPORT/1179 + OUT02, adapterplaats/controlebibliotheek Pervasive, ERP-datasetinventaris D11) vastgelegd in een apart overzicht `docs/openstaande-externe-punten.md`, zodat ze niet verloren gaan terwijl de mens ze extern uitzet. De overige drie kandidaten (a, b, c) blijven openstaande, nog te maken keuzes — geen van drie is hiermee verworpen.
**Bron:** mens / denker-zwaar (gap-analyse "wat moet er nog gebeuren", 2026-09-27)

## 2026-09-27 — Ontwerp bindend: Frontend publicatierun (SIMULATION) op scherm (3)
**Vraag:** Kandidaat (a) uit de vorige entry ("Frontend voor de publicatierun") is door de mens nu gekozen ("Frontendpublicatie run wil ik nu"). Hoe wordt dit toegevoegd aan het bestaande bundelscherm, bovenop het al vastliggende, geteste backend-contract van 5-PUB-a (`docs/design/fase5-pub-design.md`)?
**Beslissing:** Het ontwerp van denker-gemiddeld (27/09) is bindend, geen §6-criterium geraakt (zuiver additief op een vastliggend contract):
- **Locatie:** nieuwe tab "Publicatie" naast Overzicht/Leden/Mutaties/Beslissingen op scherm (3) (`BundlePublicationTab.tsx`, route `.../publication`, `BundleDetailPage.tsx`). Altijd zichtbaar; buiten `FROZEN` een uitleg i.p.v. de actie.
- **Starten:** knop "Simulatierun starten", `withPermission(approveGate, publicationRunGate(...))` — nieuwe pure gate-functie in `bundlePolicy.ts` (niet-FROZEN → denied; al een actieve `REQUESTED`/`PREPARING`-run → denied). Geen `ConfirmDialog`: een SIMULATION-run heeft geen operationeel effect (`writesToProdis: false`), dus een gewone actieknop met inline melding volstaat.
- **Statusweergave:** `POST /bundles/{id}/publication-runs` is **synchroon** (bevestigd in `PublicationRunController`-javadoc) — geen polling nodig, het antwoord is al de terminale status (`SIMULATED`/`FAILED`). Runlijst (`GET .../publication-runs`) is de audittrail, alleen-lezen, geen paginering (platte lijst).
- **Niet-contractuele aard zichtbaar:** vaste banner ("niet-contractuele simulatie, contractStatus UNVERIFIED_FIELD_INVENTORY, writesToProdis: false"); `rowCount`/`incompleteRowCount` per run met de bestaande "null → —, nooit 0"-conventie.
- **Resultaat:** `artifactSha256` (kopieerknop) + `artifactByteSize`; downloadlink als gewone `<a>` (geen `fetch`, want CSV-antwoord + sessiecookie) naar zowel het runartefact als de bestaande PSIMPORT-preview-CSV. Vereist één kleine additieve wijziging: `apiUrl()` exporteren uit `api/http.ts` (was intern). Bij `FAILED`: `failureCode`/`failureMessage` als platte tekst.
- **Foutcodes (`errors/codes.ts`):** zes nieuwe entries (`PUBLICATION_MODE_REQUIRED`, `PUBLICATION_MODE_UNKNOWN`, `PUBLICATION_MODE_NOT_ENABLED`, `PUBLICATION_RUN_IN_PROGRESS`, `BUNDLE_CONTENT_CHANGED_SINCE_FREEZE`, `PUBLICATION_RUN_NOT_FOUND`, `PUBLICATION_RUN_ARTIFACT_NOT_AVAILABLE`) + de bestaande `BUNDLE_NOT_FROZEN`-uitleg verbreden (tekstwijziging, geen contractwijziging: geldt nu voor zowel PSIMPORT-preview als publicatierun).
- **Kleinste verticale slice (nu te bouwen):** `api/types.ts` (nieuwe DTO's), `api/publicationRuns.ts` (requestRun/listRuns/getRun, geen artefact-fetch), `api/http.ts` (`apiUrl()`), `bundlePolicy.ts` (`publicationRunGate`), `errors/codes.ts`, `BundlePublicationTab.tsx`, `routes.tsx`+`BundleDetailPage.tsx`, tests (`bundlePolicy.test.ts`-uitbreiding + nieuwe `BundlePublicationTab.test.tsx` naar het patroon van `BundleMutationsTab.test.tsx`).
- **Bewust nog niet nu:** automatisch pollen (niet nodig, POST is synchroon; pas relevant bij een hersteltraject voor de vastgelopen-`PREPARING`-edge case, dat is een aparte, nog niet genomen backendbeslissing), een volwaardige PSIMPORT-previewpagina (nu enkel downloadlink), TRIAL_LIBRARY/PRODUCTION-keuze in de UI (blijft dicht zolang 5-PUB-b/c niet gebouwd is; UI biedt hardcoded enkel SIMULATION aan).
**Bron:** mens (keuze kandidaat) / denker-gemiddeld (ontwerp "Frontend publicatierun", 2026-09-27) / docs/design/fase5-pub-design.md, docs/design/frontend-scherm3-bundel-design.md

## 2026-09-27 — Ontwerp bindend: tweede ontvangstweg — levering inlezen uit een beheerde servermap
**Vraag:** De mens wil een tweede, handmatig getriggerde manier om een levering in te lezen, naast de browser-upload: "Dit zal jaarlijkse bestanden zijn van leveranciers die dat doormailen. Een gebruiker zal dit eerst uploaden op de server en vervolgens kan die worden ingelezen." Geen scheduler, geen polling, geen credentials — een mens plaatst het bestand handmatig buiten CatalogImport om, en triggert daarna handmatig het inlezen. Hoe wordt dit gebouwd zonder het grotere, nog niet vrijgegeven `Leveringsconfiguratie`/scheduler/connectormodel uit de businessanalyse (§14.17) te bouwen?
**Beslissing:** Het ontwerp van denker-zwaar (27/09) is bindend. Harde ontwerpgrens: geen `Leveringsconfiguratie`-entiteit, geen acquisitieservice, geen scheduler — één configuratieproperty en hergebruik van de bestaande, al bron-agnostische `DeliveryIntakeService`/`DeliveryArchiveStore` (AGENT.md §0 "nooit een parallelle architectuur").

**Twee keuzes door de mens beantwoord (beide aanbevolen optie):**
- **Q1 — deliveryReference-afleiding = optie A:** de server hasht het bronbestand zelf (SHA-256, streaming) en leidt dezelfde referentie af als de browser vandaag al doet (`Frontend/src/features/upload/deliveryReference.ts`: bestandsnaam ingekort tot 177 tekens + `#` + eerste 12 hex, max. 190 tekens), onder hetzelfde `manual:`-sleutelvoorvoegsel. Gevolg: hetzelfde bestand via browser óf servermap wordt als dezelfde levering herkend (200 idempotente retry i.p.v. een dubbele levering). Kost één extra leespas vóór de intake; grootte/wijzigingstijd worden vóór de hashpas vastgelegd en vlak vóór de intake-stream herchecked — gewijzigd tussenin → 409 `LOCAL_SOURCE_FILE_CHANGED`, niets geregistreerd. Client mag een eigen referentie meegeven (override).
- **Q2 — herkomst permanent auditeerbaar = optie 1:** nieuwe kolom `delivery.source_kind varchar(20) not null default 'UPLOAD'` (Liquibase-changeset) + nieuwe waarde `LOCAL_DIRECTORY`, mee in `DeliveryView`. Bestaande rijen krijgen terecht `UPLOAD`.

**Implementatiekeuzes (denker-zwaar, geen §6-impact, gemotiveerd volgens bestaande conventies):**
- **D1** Eén property `catalogimport.local-source.directory`, optioneel zonder default (patroon `catalogimport.archive.root`); één gedeelde map voor alle leveranciers — de gebruiker kiest de taak al zoals vandaag, enkel de bronkeuze van het bestand verandert.
- **D2** Property niet gezet ⇒ beide endpoints geven 404 `LOCAL_SOURCE_NOT_CONFIGURED` (zelfde patroon als `catalogimport.setup-api.enabled`); ±30 bestaande testklassen met `@DynamicPropertySource` hoeven niet aangepast.
- **D3** Bij opstart met een gezette property: pad moet bestaan, een map zijn, leesbaar zijn, en niet overlappen met `catalogimport.archive.root` — anders `IllegalStateException` bij opstart, niet pas bij gebruik.
- **D4** Nieuwe Service-klasse `LocalSourceDirectory` (spiegel van `DeliveryArchiveStore`: eigen root, containment-check, streaming, `list`/`stat`/`open`/`sha256Hex`). Geen nieuwe abstractielaag.
- **D5** Beide nieuwe endpoints in de bestaande `CatalogImportDeliveryController` (hergebruikt `UploadResponse`/`screen(batchId)`-orkestratie), geen nieuwe controller.
- **D6** `DeliveryIntakeService` blijft ongewijzigd — aangeroepen met een `InputStream` op het bronbestand, exact zoals vandaag met de multipart-stream.
- **D7** Na het inlezen blijft het bronbestand ongemoeid (geen move/delete): de bytes zijn al onveranderlijk gearchiveerd, het OS-account van de app krijgt enkel leesrecht, en herhaald inlezen van hetzelfde bestand is via Q1-A al veilig idempotent. Opruimen is een operatortaak.
- **D8** Lijst-endpoint vereist `MANAGE` (niet `READ`) — least privilege, want het onthult serverdirectory-inhoud.
- **D9** Vlakke map, geen recursie/submappen/extensiefilter in slice 1.
- **Additieve contractwijziging:** `UploadResponse` krijgt een nieuw veld `deliveryReference` (bestaande consumenten breken niet — puur additief).

**Beveiliging (verplicht in slice 1, AGENT.md-instructie om OWASP-kwetsbaarheden te vermijden):** kale bestandsnaam van de client, nooit een pad (weiger `/`,`\`,`:`,NUL, `..`, controle­tekens, absolute/drive-paden); whitelist-resolutie door de map te lijsten en op exacte naam te matchen (geen padconcatenatie met clientinvoer); `toRealPath()`-containmentcheck tegen `catalogimport.local-source.directory` (defence in depth, symlink-bewust); symlinks altijd geweigerd (409); geen absoluut pad ooit in een API-respons of foutboodschap (enkel bestandsnaam; volledig pad alleen in de serverlog); streaming, nooit het bestand in het geheugen; beide endpoints toegevoegd aan de bestaande rechten-regressietests (`PermissionWriteEndpointsHttpTest`, `PermissionReadEndpointsHttpTest`).

**Endpoints:** `GET /api/catalog-import/local-source/files` (MANAGE; lijst met `fileName`/`byteSize`/`lastModifiedAt`, gesorteerd meest-recent-eerst, cap 500 + `truncated`, geen absoluut pad) en `POST /api/catalog-import/tasks/{taskId}/deliveries/local-source` (MANAGE; body `fileName` + optioneel `deliveryReference`/`uploadedBy`/`expectedRecordCount`/`expectedByteSize`; zelfde foutcodes/volgorde als de bestaande upload, plus `LOCAL_SOURCE_NOT_CONFIGURED`/`LOCAL_SOURCE_FILE_NOT_FOUND`/`LOCAL_SOURCE_FILE_NOT_REGULAR`/`LOCAL_SOURCE_FILE_CHANGED`/`LOCAL_SOURCE_FILE_NAME_INVALID`/`LOCAL_SOURCE_DIRECTORY_UNAVAILABLE`). Synchrone screening in hetzelfde verzoek, zoals vandaag.

**Kleinste verticale slice(s):** Slice 1 (backend, nu te bouwen — raakt Domain+Liquibase+Service+Web door Q2, dus `bouwer-zwaar`): property, `LocalSourceDirectory`, beide endpoints, `source_kind`-changeset, rechten-regressietests, nieuwe testklassen `LocalSourceDeliveryTest`/`LocalSourceDisabledTest` met een volledige traversal-/symlinkbatterij. Slice 2 (frontend, sequentieel na slice 1: bronkeuze op `UploadPage.tsx`, `api/localSource.ts`) volgt apart, geen UI nu.

**Bewust nog niet:** bestandsvoorwaarden/voorwaardebouwer, per-koppeling-mappen, submappen, automatisch opruimen/verplaatsen, "minstens N seconden ongewijzigd"-versheidsfilter, en het volledige `Leveringsconfiguratie`+scheduler+connectormodel.
**Bron:** mens (Q1, Q2) / denker-zwaar (ontwerp "tweede ontvangstweg — lokale servermap", 2026-09-27) / businessanalyse-catalogimport.md §9.1, business-analyse-leveranciersbibliotheken.md §14.17

## 2026-09-27 — Ontwerp bindend: herstel van een vastgelopen PREPARING-publicatierun
**Vraag:** `docs/design/fase5-pub-design.md` §7 markeerde expliciet "een aparte beslissing nodig" over herstel van een `publication_run` die op `PREPARING` blijft hangen (enkel bereikbaar via een servercrash tijdens de synchrone aanvraag — geen normaal foutpad, want elke gewone exception leidt al naar `FAILED`). Zolang dat niet hersteld wordt, blokkeert zo'n rij de bundel voorgoed (hoogstens één actieve run per bundel). Een denker-gemiddeld (27/09) werkte twee opties uit: (A) een expliciet "afbreken"-endpoint, (B) een automatische timeout-controle bij de eerstvolgende aanvraag (geen scheduler — dat zou de expliciete §5-ontwerpgrens "geen scheduler/retry-lus" doorbreken).
**Beslissing:** Door de mens, alle vier deelvragen volgens de aanbeveling van de denker op drie na:
- **Aanpak: beide.** Optie B (automatische timeout bij de eerstvolgende `requestRun`-aanvraag voor die bundel, geen achtergrondscheduler) als zelfherstellend vangnet, plus optie A (een expliciet `POST /publication-runs/{runId}/abort`-endpoint, recht `APPROVE` — consistent met de bestaande rechtenconventie voor bundel-levenscyclusacties) voor wie niet wil wachten. Beide hergebruiken dezelfde onderliggende Service-logica (`recordFailed` op `PublicationRun` bestaat al, geen nieuwe domeinovergang nodig) — geen dubbele code.
- **Drempel: 60 minuten** (ruimer dan de aanbevolen 30 — expliciete menskeuze), als configuratieproperty (patroon van andere `catalogimport.*`-properties, bv. `catalogimport.publication-run.stuck-after`), geen hardcoded waarde.
- **Voorwaarde bij A: geen tijdsvoorwaarde** (afwijking van de aanbeveling van de denker, die de drempel ook op A wilde toepassen). Wie `APPROVE` heeft, kan een `PREPARING`-run op elk moment handmatig afbreken, op eigen oordeel — geen 409 als de drempel nog niet verstreken is.
- **Foutcodes: akkoord.** Nieuwe `failure_code`-waarden `FAILURE_TIMED_OUT` (optie B) en `FAILURE_MANUALLY_ABORTED` (optie A).
Geen nieuwe Liquibase-migratie nodig: `started_at` bestaat al (changeset 009-1) als basis voor de timeout-controle; status/`finished_at`/`failure_code` bestaan al. Complexiteit van de bouwstap: Service + Web (+ een "Afbreken"-knop op een `PREPARING`-run in `BundlePublicationTab.tsx`), bestaande patronen, geen open architectuurvraag meer — bouwer-gemiddeld.
**Bron:** mens (vier deelvragen) / denker-gemiddeld (ontwerp "herstel vastgelopen publicatierun", 2026-09-27) / docs/design/fase5-pub-design.md §7

## 2026-09-27 — Heropening scherm 1a/1b: bereikbaarheid, mutatie-omvang en scope van 1b
**Vraag:** Scherm 1a (beheer/inrichting) en 1b (leveringsconfiguratie/sjabloonwizard) waren op 22/09 uitgesteld "tot na Fase 5/Keycloak" — die voorwaarde is nu vervuld. Een denker-zwaar Fase 0-intake (27/09) vond dat de vervalconditie bereikt is, maar dat drie andere, nog niet genomen keuzes de heropening blokkeren: (A1) de setup-API-vlag zegt expliciet "vlag uit = endpoints bestaan niet, ongeacht rechten" (26/09), wat botst met "productiewaardig beheerscherm" (22/09); (A2) de bestaande setup-API is create-only (geen wijzigen/deactiveren); (A3) 1b zoals de businessanalyse (§14.17) het oorspronkelijk definieert (ophaalwijze/connector/planning/credentials) botst met de op 27/09 vastgelegde ontwerpgrens "geen Leveringsconfiguratie-entiteit, geen scheduler" én met het nog ontbrekende sleutelbeheerontwerp.
**Beslissing:** Door de mens, alle drie volgens de aanbeveling van de denker:
- **A1 — bereikbaarheid:** nieuwe alleen-lezen inrichtingsendpoints (`GET /source-organisations`, `GET /definitions`, `GET /definitions/{id}/revisions`, patroon van het bestaande vlagloze `GET /import-links`) komen **buiten** de setup-API-vlag, met recht `READ`. Schrijfpaden blijven achter de vlag + `MANAGE`, ongewijzigd. Scherm 1a is zo altijd bruikbaar als inzagescherm, en enkel met de vlag aan ook als beheerscherm.
- **A2 — mutatie-omvang:** naast lezen en de bestaande create-acties komt er **wijzigen via een opvolgrevisie** (kopieer de actieve revisie naar een nieuwe DRAFT, wijzig, activeer — nooit een directe edit op een ACTIVE-revisie, conform de bestaande regel). Dit is zelf nog geen bouwbare slice: de denker markeerde expliciet dat de clone-semantiek (wat wordt gekopieerd, wat niet, wat met bookmarkwaarden) een eigen, apart Fase 1-achtig ontwerp vereist (spoor S1-X) vóór dit gebouwd wordt — niet inbegrepen in de eerste 1a/1b-slices.
- **A3 — scope 1b:** enkel de **sjabloon-/materialisatiewizard** (sjabloon kiezen → revisie → bookmarkinvulset → nieuwe of hergebruikte definitie → koppeling + bookmarkwaarden beheren), volledig op het bestaande, geteste backendcontract (`CatalogImportTemplateController`/`CatalogImportLinkController`). De naam "leveringsconfiguratie" wordt hiermee bewust nog niet waargemaakt; de volledige BA1 §14.17-vorm blijft dicht (vereist eerst herroeping van de 27/09-ontwerpgrens, een sleutelbeheerontwerp, en een eigen domeinontwerp — niet nu).
**Voorgestelde bouwvolgorde** (denker-zwaar, niet door de mens expliciet herzien): S1-0 (denker-gemiddeld: endpoints/routes/gates/foutcodes voor 1a/1b) → S1-B1 (bouwer: de nieuwe alleen-lezen inrichtingsendpoints) → S1-F1 (scherm 1a alleen-lezen boomweergave) → S1-F2 (scherm 1b sjabloonwizard, alleen-lezen deel) → S1-F3 (1b schrijfdeel: materialiseren + bookmarkwaarden wijzigen) → S1-X (apart ontwerp opvolgrevisie/clone) → S1-F4 (1a schrijfdeel: opvolgrevisie-wijzigen, ná S1-X).
**Bron:** mens (A1-A3) / denker-zwaar (Fase 0-intake "heropening scherm 1a/1b en D14", 2026-09-27) / docs/decisions.md 22/09 ("correctie schermenindeling"), 26/09 ("setup-API-vlag blijft")

## 2026-09-27 — Heropening D14-vervolg: behandelgeval-anker, niveau, effect op screening, toewijzing
**Vraag:** Het D14-vervolg (behandelgeval-model, eigenaar/toewijzing, issue-afhandelacties) was op 23/09 uitgesteld op vier gronden (Q2-Q4), waarvan enkel de identiteitsgrond (Q3: geen geverifieerde identiteit) door Fase 5 vervallen is. Dezelfde denker-zwaar-intake (27/09) vond een harde tegenstrijdigheid die de bouwvolgorde blokkeerde: `import_issue_group` is strikt per batch/levering (`uk_import_issue_group_signature unique (batch_id, issue_code, signature)`), terwijl de businessanalyse eist dat herhaling van hetzelfde probleem geen nieuwe taak maakt maar het bestaande geval bijwerkt. Vier deelvragen (D1-D4) moesten daarom eerst beantwoord worden.
**Beslissing:** Door de mens:
- **D1 — anker/identiteit:** een **nieuwe tabel `issue_case`**, leveringsoverstijgend, met identiteit `(import_link_id, issue_code, signature)`; een nieuwe issuegroep koppelt aan het bestaande geval (`observation_count`/`last_seen_at` bijgewerkt) in plaats van een nieuwe taak te maken. Dit herroept de "geen nieuwe tabellen"-beslissing van 23/09 (Q2) — expliciet, met deze entry als herroeping. Dit is een eigen Fase 1-achtige ontwerpcyclus (migratie, koppeling naar `import_issue_group`, heropeningsregel na `REJECTED`), geen eenvoudige slice; de denker markeerde een verplichte **denker-zwaar**-ontwerpstap (S2-0) vóór er gebouwd wordt.
- **D2 — niveaus:** **één niveau** (enkel behandelgeval, geen deeltaak/taaktype). De vier deeltaaktypes uit de businessanalyse verwijzen naar functies die niet bestaan (centraal artikel, staffels, ERP-context, deeltaaktypes); een taaktypelijst zonder die functies zou een lege schil zijn.
- **D3 — effect op screening:** **zuiver administratief, met een statuslijst zonder "geaccepteerd"-achtige waarde** (bv. `AWAITING_REVIEW`/`CORRECTED`/`REJECTED`/`AUTO_RESOLVED`, geen `ACCEPTED_*`). Concreet scenario ter verduidelijking: bij een geblokkeerde prijsafwijking die telefonisch bevestigd wordt, documenteert het behandelgeval dat (`CORRECTED`, reden), maar de geblokkeerde mutatie zelf blijft geblokkeerd — om de data echt te verwerken is een nieuwe, correcte levering nodig, óf een mens keurt de mutatie apart goed via de bestaande weg (`accept-baseline`/bundelbeslissing). Het behandelgeval is dus nooit een tweede weg om tegengehouden data door te laten; `validation_result`, drempels en mutaties blijven ongewijzigd. Geen fase 3-regel (R-THR/R-ISS) wordt heropend.
- **D4 — toewijzing: volledig uitgesteld.** Geen enkele toewijzings- of claimfunctie in deze ronde (ook geen self-claim) — enkel de rest van het behandelgeval (status, reden, audit). Een latere uitbreiding hangt af van een externe gebruikers-/groepenbron die vandaag niet bestaat (zie `docs/openstaande-externe-punten.md`).
**Gevolg voor ST-11:** van de zeven genoemde velden (oorzaak, eigenaar, prioriteit, status, afhankelijkheden, actie, gereedcriterium) worden nu status, reden/oorzaak-als-vrije-tekst en audit gebouwd; eigenaar (D4), prioriteit, afhankelijkheden, actie-per-taaktype en gereedcriterium (D2) blijven bewust weg — expliciet gedocumenteerd, niet stilzwijgend als "later" gemarkeerd zonder motivering.
**Voorgestelde bouwvolgorde** (denker-zwaar, niet door de mens expliciet herzien): S2-0 (denker-**zwaar**, verplicht: entiteit `issue_case`, identiteitssleutel, statusmachine, heropeningsregel, auditvelden — expliciet zonder eigenaar/claim gezien D4) → S2-B1 (migratie + entiteit + repository, geen gedrag) → S2-B2 (status wijzigen met reden, `MANAGE`) → S2-B3 (leesendpoints: gevallenlijst met filters, incl. de eerder uitgestelde batch-overstijgende weergave) → S2-F1 (scherm 0 uitgebreid met gevallenweergave) → S2-F2 (afhandelacties in de UI).
**Bron:** mens (D1-D4) / denker-zwaar (Fase 0-intake "heropening scherm 1a/1b en D14", 2026-09-27) / docs/decisions.md 23/09 (D14 uitstel, Q2-Q4), docs/stories/catalog-import-v2.md (ST-11), Businessanalyse_artikelimport_en_prijsacceptatie-2.md §13

## 2026-09-27 — Ontwerp bindend: scherm 1a/1b (S1-0, spoor S1-B1 t/m S1-F3)
**Vraag:** Concreet ontwerp voor de eerste vier bouwstappen van spoor 1 (scherm 1a/1b), op basis van de bindende A1-A3-keuzes.
**Beslissing:** Het ontwerp van denker-gemiddeld (27/09) is bindend:
- **S1-B1 (bouwer-gemiddeld):** nieuwe `SetupQueryService` (bewust apart van het vlaggedekte `SetupService`, patroon van `ImportLinkQueryService`), drie nieuwe DAO-methoden, nieuwe controller `CatalogImportSetupQueryController` (`GET /source-organisations`, `GET /definitions`, `GET /definitions/{id}/revisions`, alle `READ`, geen vlag). Additieve uitbreiding van `ImportLinkRow`/`findLinkRows` met `importDefinitionId` (veld + optioneel filter) om de boom in S1-F1 tot op koppelingenniveau te kunnen sluiten — puur additief, geen rename.
- **S1-F1 (bouwer-gemiddeld, na S1-B1):** scherm 1a (`/setup`, nieuwe feature-map `features/setup/`) bouwt de boom **client-side** op uit de drie S1-B1-lijstendpoints (lazy per niveau), NIET via het bestaande `GET /setup/overview` — dat endpoint blijft immers ook achter de vlag, wat het doel "altijd bruikbaar als inzagescherm" zou missen. Taken-niveau blijft in deze slice leeg met een expliciete tekst (geen vlagloos leesendpoint voor taken vandaag) — bewust niet meegenomen, niet stilzwijgend weggelaten.
- **S1-F2 (bouwer-gemiddeld, na S1-B1):** scherm 1b sjabloonwizard, alleen-lezen deel (`features/templates/`), volledig op het bestaande, ongewijzigde vlaggedekte contract van `CatalogImportTemplateController`. Eigen, scherm-specifieke afhandeling voor de 404-zonder-code wanneer de vlag uit staat (niet via de generieke `describe()`-fallback). `problems`-lijst getoond als niet-blokkerende `<ul role="alert">`, patroon van `BundleOverviewTab`'s `staleWarning`.
- **S1-F3 (bouwer-zwaar, na S1-F2):** materialiseren met expliciete `mode`-keuze zonder voorselectie (patroon `CreateBundleForm`'s `targetMode`-sentinel) en bookmarkwaarde-wijzigen met het bestaande vergrendelingsslot (`LINK_BOOKMARK_LOCKED_BY_OPEN_BATCH`, getoond bij de actie zelf). Sjabloondeclaratie zelf (`declareBookmark`/`addUsage`) blijft buiten scope (sjabloonbeheer, geen materialisatie-eindgebruikersfunctie).
- **Bevestigd door de hoofdsessie (geen nieuwe §6-vraag, logisch gevolg van A1/A3):** scherm 1b blijft volledig achter `catalogimport.setup-api.enabled` — A1 opent alleen de drie nieuwe inrichtingsendpoints, A3 heropent `CatalogImportTemplateController`/`CatalogImportLinkController` niet. Dit moet expliciet zo gecommuniceerd blijven (niemand mag scherm 1b later als "productiewaardig" beschouwen).
- **Nieuwe foutcodes:** ±25 nieuwe `errors/codes.ts`-entries voor de sjabloon-/materialisatiewizard (zie het volledige ontwerprapport voor de lijst), plus `DEFINITION_NOT_FOUND` voor S1-B1.
**Bron:** denker-gemiddeld (ontwerp "scherm 1a/1b", 2026-09-27) / docs/decisions.md 2026-09-27 ("Heropening scherm 1a/1b"), docs/design/sjabloon-materialisatie-design.md, docs/design/frontend-scherm3-bundel-design.md

---

## 2026-09-28 — S2-0: ontwerp `issue_case` bindend (met `issue_case_event`) en bouwvolgorde S2-B1 … S2-F2
**Vraag:** Concreet technisch ontwerp voor spoor S2-0 uit de entry "Heropening D14-vervolg" (2026-09-27) —
de `issue_case`-entiteit, sleutel, statusmachine, koppeling in de screeningflow en heropeningsregel, binnen de
bindende D1-D4-antwoorden van de mens.
**Beslissing:** `docs/design/issue-case-design.md` is bindend (denker-zwaar, 2026-09-28). Kern: tabel
`issue_case` (identiteit `import_link_id + issue_code + signature`, hard uniek in de database), statussen
`AWAITING_REVIEW`/`CORRECTED`/`REJECTED`/`AUTO_RESOLVED` (geen `ACCEPTED_*`, geen effect op `validation_result`
of mutatiestatussen — D3), koppeling gebeurt in `DeliveryScreeningService.aggregate(Context)` na de bestaande
issue-aggregatie, heropeningsregel R-CASE-01..04 (herhaling verhoogt waarnemingen, afwijzing onderdrukt een
identieke herlevering, een gewijzigde `import_definition_revision` heft de onderdrukking op). Bouwvolgorde
S2-B1 (schema+Domain+Dao, `bouwer-gemiddeld`) → S2-B1b (koppeling+heropening in de screeningflow,
`bouwer-zwaar`) → S2-B2 (statuswijziging, `bouwer-gemiddeld`) → S2-B3 (leesendpoints, `bouwer-gemiddeld`) →
S2-F1 (scherm, alleen-lezen, `bouwer-gemiddeld`) → S2-F2 (afhandelacties, `bouwer-gemiddeld`).

**O1 door de mens beantwoord: ja, met een aparte append-only `issue_case_event`-tabel** (naast `issue_case`
zelf) — zodat een systeemheropening nooit de afwijzingsreden van een mens overschrijft. Dit is een uitbreiding
van het "één nieuwe tabel"-gevoel uit D1, met motivatie in het ontwerp §1.

**Ontdekkingen teruggeschreven, na akkoord van de mens (enkel de businessregel, niet de technische
constraint):** de heropenings-/onderdrukkingsregel (R-CASE-01..04) is vastgelegd in
`docs/design/issue-case-design.md` §2, omdat `docs/requirements/catalog-import-business-rules.md` zelf als
"historische proefversie" gemarkeerd is en niet meer bijgewerkt wordt (banner verwijst naar
`docs/analysis/current-project-vs-businessanalyse-2.md`) — het toevoegen van een actuele R-CASE-sectie daar
zou de eigen banner van dat document tegenspreken. De technische-constraint-ontdekking
(`IssueHandlingStatus`-valkuil) is bewust niet teruggeschreven naar `fase3-rules-design.md` (mens koos dit
niet); ze blijft enkel in `issue-case-design.md` als waarschuwing voor S2-B1.

**Bron:** mens (O1, terugschrijfkeuze) / denker-zwaar (`Ontwerp S2-0 issue_case`, 2026-09-28) /
docs/decisions.md 2026-09-27 ("Heropening D14-vervolg")

---

## 2026-09-28 — S2-B1/S2-B1b uitgevoerd: schema, Domain/Dao en koppeling in de screeningflow
**Vraag:** Zijn de eerste twee bouwstappen van het bindende ontwerp `docs/design/issue-case-design.md`
afgerond en geverifieerd?
**Beslissing:** Ja. S2-B1 (`bouwer-gemiddeld`): changeset `012-issue-case.sql` (tabellen `issue_case`,
`issue_case_event`, additieve kolom `import_issue_group.issue_case_id`), Domain-klassen
(`IssueCase`/`IssueCaseEvent`/`IssueCaseStatus`/`IssueCaseEventKind`/`IssueCaseEventSource`),
`IssueCaseRepository`/`IssueCaseEventRepository`, javadoc-waarschuwing op `IssueHandlingStatus`, en
`IssueCaseSchemaTest` — 11/11 groen. S2-B1b (`bouwer-zwaar`): `IssueCaseDao` (set-based, vijf stappen
volgens ontwerp §3), `IssueCaseSyncService`, koppeling in `DeliveryScreeningService.aggregate(Context)`
(dekt zowel `mutate()` als `block()`), herstelpad in `ScreeningRecoveryService`, en `IssueCaseSyncTest` —
uiteindelijk 9/9 groen na een bugfix (zie hieronder).

**Bug gevonden en gefixt tijdens verificatie door de hoofdsessie:** het herstelpad riep `recount()` (zet
`observation_count` op 0 voor een op te ruimen geval) vóór `deleteWithoutObservationsOrHumanAction()` aan.
Voor een geval zonder menselijke actie (`status_changed_at is null`) schond die tussentijdse UPDATE meteen
`ck_issue_case_observations` — PostgreSQL controleert CHECK-constraints per statement en CHECK-constraints
kunnen (anders dan UNIQUE/PK/FK) nooit `deferrable` gemaakt worden. Fix (`bouwer-licht`): volgorde
omgedraaid (eerst verwijderen, dan hertellen) en het selectiecriterium van
`deleteWithoutObservationsOrHumanAction` aangepast van `observation_count = 0` (nog niet herberekend op dat
moment) naar een verse `not exists`-check op resterende gekoppelde `import_issue_group`-rijen.
`docs/design/issue-case-design.md` §3 is bijgewerkt met deze volgorde-eis.

**Ontdekking tijdens S2-B1b (al verwerkt in code en design, geen open vraag):** `fk_issue_case_event_case`
maakt het onmogelijk een `issue_case` te verwijderen zolang zijn `CREATED`-event bestaat. Op het
herstelpad verdwijnt een geval dat nooit geldig bestaan heeft dus samen met zijn geboorte-event; de
append-only-regel blijft onverkort gelden voor elk geval dat blijft bestaan. Vastgelegd in de javadoc van
`IssueCaseDao.deleteWithoutObservationsOrHumanAction` en in `docs/design/issue-case-design.md` §1.

Totaal 23/23 tests groen (`IssueCaseSchemaTest`, `IssueCaseSyncTest`, `ScreeningRecoveryServiceTest`).
Volgende stap: S2-B2 (statuswijziging door een mens, `bouwer-gemiddeld`).
**Bron:** hoofdsessie na verificatie (gerichte testronde) / docs/design/issue-case-design.md

---

## 2026-09-28 — S2-B2 uitgevoerd: statuswijziging door een mens
**Vraag:** Is bouwstap S2-B2 (`docs/design/issue-case-design.md` §4/§6) afgerond en geverifieerd?
**Beslissing:** Ja (`bouwer-gemiddeld`). `IssueCase.recordHumanDecision(...)` (Domain, bewaakt zelf de vijf
toegestane overgangen), `IssueCaseService.changeStatus(...)` (Service, `findByIdForUpdate`-serialisatie,
`expectedStatus`-controle, vertaalt naar 409/404), `CatalogImportIssueCaseController`
(`POST /api/catalog-import/issue-cases/{caseId}/status`, recht `MANAGE`), rechten-regressietests
uitgebreid (`PermissionCoverageTest`, `PermissionWriteEndpointsHttpTest`), nieuwe `IssueCaseStatusHttpTest`.
43/43 tests groen (`IssueCaseStatusHttpTest`, `PermissionWriteEndpointsHttpTest`, `PermissionCoverageTest`,
`IssueCaseSyncTest`, `IssueCaseSchemaTest`).

**Kleine interpretatiekeuze aanvaard (geen §6-criterium):** `reopen_count` wordt nu ook opgehoogd bij een
handmatige heropening (CORRECTED/REJECTED/AUTO_RESOLVED → AWAITING_REVIEW door een mens), niet enkel bij de
twee systeemheropeningsregels uit §2. Het ontwerp noemde `reopen_count+1` expliciet alleen voor de
systeemregels; de kolomdefinitie in §1 zegt generiek "hoe vaak heropend". Aanvaard: een menselijke
heropening is evenzeer een heropening, en dit heeft geen architectuur- of statusflow-impact.

**Twee kleine, code-consistente afwijkingen van de letterlijke opdrachttekst (geen afwijking van het
ontwerp zelf):** (1) `recordHumanDecision` neemt `changedBy`/`changedBySubject` als losse Strings i.p.v.
een `ActorIdentity`-parameter, omdat Domain geen afhankelijkheid heeft op Service — patroon van de
bestaande `PublicationRun`-constructor; (2) een ontbrekende/onbekende `expectedStatus` geeft een generieke
400 zonder foutcode (het ontwerp benoemt enkel foutcodes voor `newStatus`) — patroon van bestaande
niet-benoemde 400's elders (bv. `BundleDecisionService`).

Volgende stap: S2-B3 (leesendpoints, `bouwer-gemiddeld`).
**Bron:** hoofdsessie na verificatie (gerichte testronde) / docs/design/issue-case-design.md

---

## 2026-09-28 — S2-B3 uitgevoerd: leesendpoints behandelgeval
**Vraag:** Is bouwstap S2-B3 (`docs/design/issue-case-design.md` §6) afgerond en geverifieerd?
**Beslissing:** Ja (`bouwer-gemiddeld`). `IssueCaseQueryService` + vijf leesendpoints (`GET /issue-cases`,
`/summary`, `/{id}`, `/{id}/observations`, `/{id}/events`), alle recht `READ`. Additieve uitbreiding:
`IssueGroupDao.GroupRow`/`BatchQueryService.IssueGroupRow` krijgen achteraan `issueCaseId`, dus
`GET /batches/{id}/issue-groups` toont nu of een groep al aan een geval gekoppeld is. Rechten-regressie
uitgebreid (`PermissionCoverageTest`, `PermissionReadEndpointsHttpTest`). 60/60 tests groen
(`IssueCaseQueryHttpTest`, `PermissionCoverageTest`, `PermissionReadEndpointsHttpTest`,
`IssueGroupingTest`, `IssueCaseSyncTest`, `IssueCaseStatusHttpTest`).

**Kenmerknaam aanvaard (geen §6-criterium):** het "nieuwe waarneming ná beslissing"-kenmerk uit ontwerp §2
heet `hasUnreviewedRecurrence` (boolean). Semantiek: `statusChangedAt == null || lastSeenAt.isAfter(statusChangedAt)`
— een geval zonder beslissing staat dus altijd op `true` (nog nooit beoordeeld). Geen aparte
filterparameter (het stond niet in de expliciete filterlijst); wel zichtbaar in elke lijst-/detailrij.

Volgende stap: S2-F1 (scherm, alleen-lezen, `bouwer-gemiddeld`).
**Bron:** hoofdsessie na verificatie (gerichte testronde) / docs/design/issue-case-design.md

---

## 2026-09-28 — S2-F1 uitgevoerd: scherm behandelgeval (alleen-lezen)
**Vraag:** Is bouwstap S2-F1 (`docs/design/issue-case-design.md` §6) afgerond en geverifieerd?
**Beslissing:** Ja (`bouwer-gemiddeld`). Nieuwe feature-map `Frontend/src/features/issuecases/`:
`IssueCaseListPage` (`/issue-cases`, filters + paginering + recurrence-badge) en `IssueCaseDetailPage`
(`/issue-cases/:caseId`, classificatie/status/waarnemingen/gebeurtenissen), volledig alleen-lezen (geen
afhandelacties — dat is S2-F2). Telblok "Open behandelgevallen" op scherm 0 (`WorkQueuePage`), nieuwe route
+ navigatiemenu-item, additieve typen/foutcode/`StatusBadge`-uitbreiding. Frontend-testsuite 292/292 groen,
`tsc -p tsconfig.app.json` schoon.

**Twee kleine interpretatiekeuzes aanvaard (geen §6-criterium):** (1) rij-link in de eerste kolom i.p.v.
hele-rij-klik — geen enkel bestaand scherm gebruikt een klikbare-rij-patroon, dus de bestaande conventie
(`WorkQueuePage`/`BundleListPage`) is gevolgd; (2) het telblok toont de `AWAITING_REVIEW`-telling ("nog te
beoordelen"), niet het totaal over alle statussen — een redelijke lezing van "open", zonder architectuur-
impact en later zonder schemawijziging aan te passen.

Volgende stap: S2-F2 (afhandelacties in de UI, `bouwer-gemiddeld`) — laatste stap van het D14-spoor.
**Bron:** hoofdsessie na verificatie (Frontend-testsuite + typecheck) / docs/design/issue-case-design.md

---

## 2026-09-28 — S2-F2 uitgevoerd: afhandelacties behandelgeval (D14-spoor volledig afgerond)
**Vraag:** Is bouwstap S2-F2 (`docs/design/issue-case-design.md` §4/§6) afgerond en geverifieerd? Dit was
de laatste stap van het D14-spoor.
**Beslissing:** Ja (`bouwer-gemiddeld`). `issueCasePolicy.ts` (pure statusmatrix), `IssueCaseActions.tsx`
(Corrigeren/Afwijzen/Heropenen via `ConfirmDialog`, verplichte reden, `expectedStatus` meegestuurd, vaste
D3-uitlegtekst), uitbreiding `IssueCaseDetailPage.tsx`/`api/issueCases.ts`/`types.ts`/`errors/codes.ts`.
Frontend-testsuite 308/308 groen, `tsc -p tsconfig.app.json` schoon. Aansluitend een volledige
backend-regressieronde gedraaid (1198 tests): 3 falend, waarvan 1 een echte regressie
(`ConfigurationActorSubjectSchemaTest`, hardcoded telling van `*_by_subject`-kolommen niet bijgewerkt na
S2-B1 — bekende, gedocumenteerde valkuil) en 2 pre-existente, aan dit spoor onaangeraakte
schaal-/pagineringsflakiness in `CatalogImportSetupQueryHttpTest` (vast `size=200`, kan onder een volledige
run met 101 testklassen de eigen testrij van de eerste pagina duwen — niet veroorzaakt door D14, niet
gefixt in deze ronde). De tellingregressie is gefixt (19→21, 13→15) en geverifieerd (12/12 groen).

**Hiermee is het volledige D14-spoor (behandelgeval/`issue_case`) afgerond:** S2-B1 → S2-B1b → S2-B2 →
S2-B3 → S2-F1 → S2-F2, telkens geverifieerd met een gerichte testronde. Ontwerp: `docs/design/issue-case-design.md`.

**Bron:** hoofdsessie na verificatie (Frontend-testsuite + volledige backend-regressieronde) /
docs/design/issue-case-design.md

---

## 2026-09-28 — S1-X: ontwerp opvolgrevisie/clone bindend (O1-O3) en bouwvolgorde S1-X-1 … S1-F4
**Vraag:** Concreet technisch ontwerp voor spoor S1-X (opvolgrevisie aanmaken/wijzigen/activeren), zoals
uitgesteld op 2026-09-27 (deelvraag A2, "clone-semantiek vereist een eigen Fase 1-achtig ontwerp").
**Beslissing:** `docs/design/revision-successor-design.md` is bindend (denker-zwaar, 2026-09-28). Kern:
hergebruik van de bestaande clone-logica (`TemplateMaterialisationService`) via een gedeelde
`RevisionCopier`, geen nieuwe clone-implementatie; hergebruik van de bestaande `SetupService.activateRevision`
met twee aanvullingen (409 bij gelijktijdige activatie, laagversienummers krijgen voor het eerst betekenis);
alle vijf configuratie-kindtabellen worden letterlijk meegekopieerd (incl. alle bookmarkscopes, niet enkel
LINK); geen vergrendeling bij activeren (de gepinde batch-revisie garandeert reproduceerbaarheid al); twee
nieuwe beschermingen tegen een stille identiteitswijziging (R-REV-X1/X2/X3). Bouwvolgorde S1-X-1 (refactor
naar gedeelde `RevisionCopier`, `bouwer-zwaar`, geen gedragswijziging aan het bestaande materialisatiepad) →
S1-X-2 (opvolger aanmaken + activatie-aanvullingen, `bouwer-zwaar`) → S1-X-3 (leesendpoint revisiedetail,
`bouwer-gemiddeld`) → S1-X-4 (wijzigen/verwijderen van kindrijen + identiteitsbeschermingen, `bouwer-zwaar`)
→ S1-F4 (scherm 1a schrijfdeel, `bouwer-zwaar`).

**O1 (hoogstens één open DRAFT-opvolger per definitie) door de mens beantwoord: op serviceniveau**
(409 `REVISION_DRAFT_ALREADY_EXISTS`), geen databaseconstraint — zou het bestaande create-only-pad van
`SetupService.createRevision` kunnen breken.

**O2 (welke bronstatus mag gekloond worden) door de mens beantwoord: ACTIVE én SUPERSEDED**, niet enkel
ACTIVE zoals de letterlijke tekst van A2 zei — geeft "terugdraaien naar een eerdere configuratie" gratis,
sluit aan bij het bestaande precedent in `TemplateMaterialisationService`. DRAFT en overige statussen
blijven geweigerd.

**O3 (identiteitsbeschermingen) door de mens beantwoord: beide invoeren.** R-REV-X2 blokkeert een
canonicalisatieversie-verhoging zodra er al aanvaarde bronstaat bestaat (niet-migreerbare massa-CREATE);
R-REV-X3 vereist een expliciete bevestiging (`acknowledgeIdentityChange`) bij een wijziging van het
identiteitsprofiel of een identiteitsveld.

**Ontdekkingen teruggeschreven, na akkoord van de mens (beide):** (1) `composite_config_hash` dekt geen
mappings/recordfilters/kritiek-overrules/drempels/prijsbeleid — twee configuraties die daar enkel in
verschillen dragen dezelfde hash; vastgelegd in `docs/design/revision-successor-design.md` §9 (nergens in
productiecode gelezen, dus geen huidig foutgedrag, wel een valkuil voor later gebruik). (2) `changeReason`
wordt vandaag nergens afgedwongen ondanks de javadoc "verplicht bij een opvolgrevisie" — de nieuwe
opvolgstap (E2) is de eerste plek waar dat echt gebeurt; geen backfill, geen `not null`-constraint op
bestaande rijen.

**Bron:** mens (O1-O3, terugschrijfkeuze) / denker-zwaar (`Ontwerp S1-X opvolgrevisie/clone`, 2026-09-28) /
docs/decisions.md 2026-09-27 ("Heropening scherm 1a/1b", deelvraag A2)

---

## 2026-09-28 — S1-X-1 uitgevoerd: refactor naar gedeelde RevisionCopier
**Vraag:** Is bouwstap S1-X-1 (`docs/design/revision-successor-design.md` §0/§8) afgerond en geverifieerd
zonder gedragswijziging aan het bestaande materialisatiepad?
**Beslissing:** Ja (`bouwer-zwaar`). Nieuwe `RevisionCopier` (`Service/.../support/`) met de verhuisde
clone-logica (scalaire kopie + de vijf configuratie-kindtabellen behalve bookmarkwaarden — die volgen in
S1-X-2, zoals het ontwerp voorschrijft); `TemplateMaterialisationService` delegeert nu, ongewijzigde
publieke methode-namen/signaturen, enkel de constructor kreeg `RevisionCopier` i.p.v. rechtstreeks
`ImportRevisionFieldCriticalityRepository` (interne wijziging, geen externe aanroepers buiten Spring-DI
gevonden). 62/62 tests groen (`TemplateMaterialisationHttpTest`, `TemplateMaterialisationValidationTest`,
`TemplateGuardTest`, `TemplateBlockingPointTest`, `RevisionConfigHashesTest`, `ImportTemplateBookmarkSchemaTest`)
— onveranderd, geen enkele test aangepast.

Volgende stap: S1-X-2 (opvolger aanmaken + activatie-aanvullingen 3a/3b, `bouwer-zwaar`).
**Bron:** hoofdsessie na verificatie (gerichte testronde) / docs/design/revision-successor-design.md

---

## 2026-09-28 — S1-X-2 uitgevoerd: opvolgrevisie aanmaken + activatie-aanvullingen
**Vraag:** Is bouwstap S1-X-2 (`docs/design/revision-successor-design.md` §1-3, §6 endpoint E2) afgerond en
geverifieerd?
**Beslissing:** Ja (`bouwer-zwaar`). `RevisionCopier` uitgebreid met `copyBookmarkValues` (DEFINITION-scope,
letterlijk + `source_template_revision_id`); nieuwe `RevisionSuccessorService.createSuccessor(...)` (O2:
enkel ACTIVE/SUPERSEDED klonbaar → anders 409 `REVISION_NOT_CLONEABLE`; O1: hoogstens één open DRAFT per
definitie → anders 409 `REVISION_DRAFT_ALREADY_EXISTS`; `changeReason` verplicht → 400
`CHANGE_REASON_REQUIRED`; alle vijf kindtabellen incl. alle bookmarkscopes gekopieerd, één transactie);
endpoint `POST /setup/revisions/{revisionId}/successor` (E2, `MANAGE`, achter de vlag); `SetupService.
activateRevision` uitgebreid met 3a (409 `REVISION_ACTIVATION_CONFLICT` bij een gelijktijdige-activatie-
botsing) en 3b (laagversienummers +1 bij een gewijzigde laaghash, enkel bij een opvolger binnen dezelfde
definitie). Rechtenregressie uitgebreid. 75/75 tests groen (`RevisionSuccessorTest`, `SetupApiFlowTest`,
`LinkBookmarkValueTest`, `PermissionCoverageTest`, `PermissionWriteEndpointsHttpTest`, `SetupApiDisabledTest`,
`TemplateMaterialisationTest`, `TemplateReuseTest`, `TemplateBlockingPointTest`).

**Kleine, consistente implementatiekeuzes aanvaard (geen §6-criterium):** `SetupService.view(...)` van
`private static` naar package-private gemaakt zodat E2 dezelfde revisieweergave teruggeeft als
`createRevision`/`activate` (geen apart responsrecord); volgorde O2/O1 vóór `CHANGE_REASON_REQUIRED` (bij
een tweede opvolgpoging zonder reden krijgt de gebruiker 409, niet 400) — logisch (toestandscontrole vóór
inhoudscontrole), consistent met hoe andere endpoints in dit project dat al doen.

**Bewust nog niet:** foutcode-entries in `Frontend/src/errors/codes.ts` voor de vier nieuwe codes (hoort
volgens het ontwerp bij S1-F4); E1 (leesendpoint), E3/E4 + R-REV-X2/R-REV-X3 (S1-X-4); de R-CASE-03-melding
bij de activatieknop (S1-F4) — tot dan wordt een heropening van afgewezen behandelgevallen door het
activeren van een opvolger nog nergens aan de gebruiker gemeld.

Volgende stap: S1-X-3 (leesendpoint revisiedetail, `bouwer-gemiddeld`).
**Bron:** hoofdsessie na verificatie (gerichte testronde) / docs/design/revision-successor-design.md

---

## 2026-09-28 — S1-X-3 uitgevoerd: leesendpoint revisiedetail (E1)
**Vraag:** Is bouwstap S1-X-3 (`docs/design/revision-successor-design.md` §6, E1) afgerond en geverifieerd?
**Beslissing:** Ja (`bouwer-gemiddeld`). `GET /api/catalog-import/definitions/{definitionId}/revisions/
{revisionId}` (recht `READ`, buiten de setup-vlag) op de bestaande `CatalogImportSetupQueryController`;
nieuw `SetupQueryService.getRevisionDetail(...)` met een rijk `RevisionDetail`-record (alle scalaire velden
+ alle vijf configuratie-kindtabellen, hergebruikt bestaande view-recordtypes waar mogelijk). Geen
statusbeperking op lezen (DRAFT/ACTIVE/SUPERSEDED alle drie zichtbaar). Rechtenregressie uitgebreid.
19/19 tests groen (`CatalogImportSetupQueryHttpTest`, `PermissionCoverageTest`,
`PermissionReadEndpointsHttpTest`).

Volgende stap: S1-X-4 (wijzigen/verwijderen van kindrijen + identiteitsbeschermingen R-REV-X2/R-REV-X3,
`bouwer-zwaar`) — laatste backend-stap vóór S1-F4 (frontend).
**Bron:** hoofdsessie na verificatie (gerichte testronde) / docs/design/revision-successor-design.md

---

## 2026-09-28 — S1-X-4 uitgevoerd: wijzigpaden (E3/E4) en identiteitsbescherming
**Vraag:** Is bouwstap S1-X-4 (`docs/design/revision-successor-design.md` §5-6, E3/E4) afgerond en
geverifieerd? Dit was de laatste backend-stap van het S1-X-spoor.
**Beslissing:** Ja (`bouwer-zwaar`). `PATCH /setup/revisions/{revisionId}` (E3: scalaire velden van een
DRAFT wijzigen, `null`=ongewijzigd, hashes herberekend, R-REV-X2/R-REV-X3 afgedwongen) en twee
`DELETE`-endpoints voor mappings/filters (E4, met bookmarkdeclaratie-hercontrole na verwijderen). Nieuwe
`SourceStateDao.existsForImportDefinition`. Rechtenregressie uitgebreid (eerste PATCH/DELETE van het
project). 58/58 tests groen (`RevisionUpdateTest`, `RevisionSuccessorTest`, `SetupApiFlowTest`,
`SetupApiDisabledTest`, `PermissionCoverageTest`, `PermissionWriteEndpointsHttpTest`).

**Verruiming van R-REV-X2 bekrachtigd door de mens:** blokkeert niet enkel een verhóging maar élke
wijziging van `recordCanonicalisationVersion` zodra er bronstaat bestaat (ook verlagen) — 2→1 is even
onomkeerbaar als 1→2, dus geen uitzondering voor de richting. Onvoorwaardelijk: `acknowledgeIdentityChange`
omzeilt deze blokkade niet (in tegenstelling tot R-REV-X3, die wél een bevestiging aanvaardt).

**Ontdekking teruggeschreven, na akkoord van de mens:** `import_definition_revision` heeft geen
`updated_by`/`updated_by_subject` — wie een DRAFT-opvolger wijzigt (E3) of een kindrij verwijdert (E4)
wordt nergens vastgelegd (enkel `createdBy`/`approvedBy`). Vastgelegd in
`docs/design/revision-successor-design.md` §7 als bekend gat met een voorstel voor een latere, additieve
changeset — niet blokkerend voor S1-F4 (de bevoegdheidscontrole zelf, recht `MANAGE`, is al afgedwongen).

**Backend van het S1-X-spoor is hiermee volledig.** Volgende en laatste stap: S1-F4 (scherm 1a schrijfdeel:
opvolger maken → DRAFT bewerken → activeren, `bouwer-zwaar`).
**Bron:** mens (R-REV-X2-verruiming, terugschrijfkeuze) / hoofdsessie na verificatie (gerichte testronde) /
docs/design/revision-successor-design.md

---

## 2026-09-28 — S1-F4 uitgevoerd: scherm 1a schrijfdeel (S1-X-spoor volledig afgerond)
**Vraag:** Is bouwstap S1-F4 (`docs/design/revision-successor-design.md` §6/§8) afgerond en geverifieerd?
Dit was de laatste stap van het volledige S1-X-spoor (opvolgrevisie aanmaken/wijzigen/activeren).
**Beslissing:** Ja (`bouwer-zwaar`). "Opvolger maken" (E2, verplichte reden, enkel op ACTIVE/SUPERSEDED),
DRAFT-detail + bewerkformulier (E1/E3, `null`=ongewijzigd, beide identiteitsbeschermingen zichtbaar —
R-REV-X2 onvoorwaardelijk zonder omzeiloptie, R-REV-X3 met expliciet bevestigingsvinkje), verwijderactie op
mappings/filters (E4), activeren met de verplichte R-CASE-03-waarschuwing (E5). Twaalf nieuwe
`errors/codes.ts`-entries. Harde ontwerpgrens gerespecteerd: geen enkele actie op bookmarkdeclaraties.
Frontend-testsuite 336/336 groen, `tsc -p tsconfig.app.json` schoon.

**Ontdekking teruggeschreven, na akkoord van de mens:** een tab of spatie als scheidingsteken/
aanhalingsteken kan principieel niet via de setup-API ingesteld worden (bestaand backendgedrag,
`requireText`/`optionalText` weigeren/trimmen blanco) — een TSV-bron is er dus niet mee configureerbaar.
Vastgelegd in `docs/design/revision-successor-design.md` §7 als bekende beperking, geen fix in deze ronde.

**Hiermee is het volledige S1-X-spoor (opvolgrevisie/clone) afgerond:** S1-X-1 → S1-X-2 → S1-X-3 → S1-X-4 →
S1-F4, telkens geverifieerd met een gerichte testronde. Ontwerp: `docs/design/revision-successor-design.md`.
Nog open uit dat ontwerp (bewust, ligt bij de mens): de additieve `updated_by`/`updated_by_subject`-changeset
voor `import_definition_revision` (§9-ontdekking van S1-X-4).

**Bron:** mens (terugschrijfkeuze) / hoofdsessie na verificatie (Frontend-testsuite + typecheck) /
docs/design/revision-successor-design.md

---

## 2026-09-29 — Sleutelbeheer credentials (kandidaat c): recht voor instellen/vervangen/wissen
**Vraag:** Welk recht geldt voor het instellen, vervangen en wissen van credentials van externe bronnen
(SFTP/API-wachtwoorden)? Beslisdossier `denker-zwaar` (`Beslisdossier sleutelbeheer credentials`, 2026-09-29),
vraag V2: P1 = bestaand `MANAGE`, P2 = nieuw orthogonaal recht `catalogImport.credentials` (aanbevolen), P3 = `APPROVE`.
**Beslissing:** Door de mens: **P1 — het bestaande recht `MANAGE`** (afwijkend van de aanbeveling P2). Volgt de bindende
actiemapping A2 (2026-09-25: credentials zijn configuratie). Er komt geen nieuw recht; `ConfiguredPermissionSource`,
`/me.permissions` en het Prodis-seedverzoek (`docs/openstaande-externe-punten.md` §2) blijven ongewijzigd. Gevolg van de
hiërarchie (V2 van 2026-09-26): wie `APPROVE` heeft, kan ook credentials vervangen — bewust aanvaard.
Daarnaast is een nieuw extern punt 6 (secrets en sleutel bij DDA-infra) opgenomen in `docs/openstaande-externe-punten.md`.
**Overige punten — door de mens aanvaard conform aanbeveling ("Prima", 2026-09-29):**
- **V1 = geen enkele API geeft een secret ooit terug**, ook niet aan de invoerder; het recht `delivery.credentials.view` uit
  BA §16.8 vervalt (die paragraaf beschrijft de door 2026-09-26 vervangen toestand). Antwoorden tonen hoogstens `secretSet`,
  `secretUpdatedAt`, `secretUpdatedBy`. Ontsleutelen gebeurt enkel server-side door de ophaalcomponent en de verbindingstest.
- **V3 = sleutel in een omgevingsvariabele met sleutelring** (optioneel via bestand, `_FILE`/`configtree:`), properties onder
  `catalogimport.secrets.*`; een aparte sleutel per applicatie (niet `PRODIS_SECRETS_MASTER_KEY`) en per omgeving; de
  reservekopie van de sleutel staat nooit in `CATALOG_BACKUP_DIR` of op hetzelfde medium als de pg_dump.
- **V4 = sleutel-ID per waarde vanaf dag 1** (kolomformaat `v1:<keyId>:<base64(nonce‖ciphertext+tag)>` + kolom
  `encryption_key_id`); één actieve sleutel versleutelt, oude sleutels enkel ontsleutelen; herversleutelen als idempotente batch
  bij opstart (R2a) met een auditevent per rij als SYSTEM. Een oude sleutel blijft minstens zo lang bewaard als de
  back-upretentie (~5 weken) en wordt pas verwijderd bij 0 rijen eronder.
- **V5 = sleutelverlies is onherstelbaar:** betrokken credentials worden `UNDECRYPTABLE`, connectors weigeren expliciet
  (`CREDENTIAL_UNDECRYPTABLE`, nooit stil overslaan), een bevoegde gebruiker voert de waarden opnieuw in; de app blijft starten.
- **V6 = geen ontwikkelsleutel in de repo** (CatalogImport-conventie, zoals `CATALOG_OIDC_CLIENT_SECRET`); tests krijgen een
  vaste sleutel enkel in de testsources.
- **V7 = hybride:** geen config = functie uit (app start, opslaan geeft 409 `SECRETS_NOT_CONFIGURED`, connectors weigeren);
  config aanwezig maar ongeldig (geen base64, niet 32 bytes, dubbele ID's, actieve ID ontbreekt, zelfde materiaal onder twee
  ID's) = fail-fast bij opstart. Plus de controlewaarde `secret_key_check` (hoort de sleutel bij deze database).
- **V8 = scope nu enkel K-1:** de versleutelcomponent (JDK `javax.crypto`, AES-256-GCM, 96-bit nonce, associated data
  `catalogimport|external_credential|<credential_ref>|<secret_kind>|v1`), sleutelring-properties en opstartvalidatie, met
  unit-tests — geen schema, geen API. K-2 (tabellen `external_credential`/`_event`) hooguit als losstaande tabel; endpoints (K-3)
  en de SFTP-connector (K-4) pas na een eigen denker-zwaar-ontwerp voor Leveringsconfiguratie/ConnectionProfile.
  "Geen server-side ophaling" (2026-09-26) blijft gelden tot K-4.
Technische standaarden uit het dossier (geen nieuwe library; waarde nooit in log/exception/`toString`; audit nooit met de waarde
of een hash ervan; `credential_ref` als vooraf gegenereerde UUID omdat `GenerationType.IDENTITY` het rij-id pas na insert kent)
gelden als aangenomen.
**Nog niet teruggeschreven (wacht op akkoord mens):** de twee ontdekkingen uit het dossier — sleutel versus back-upretentie
(voorstel: `docs/design/backup-herstel-design.md`) en het Prodis-precedent `SecretCipher` (voorstel: nieuw
`docs/design/credentials-sleutelbeheer-design.md`).
**Bron:** mens / denker-zwaar (`Beslisdossier sleutelbeheer credentials`) / business-analyse-leveranciersbibliotheken.md §14.17, B10 /
docs/decisions.md 2026-09-25 (A2), 2026-09-26

(Na akkoord van de mens teruggeschreven: `docs/design/credentials-sleutelbeheer-design.md` (nieuw) en
`docs/design/backup-herstel-design.md` §7 "Sleutel en back-up" (vorige §7 → §8).)

---

## 2026-09-29 — K-1 uitgevoerd: versleutelcomponent `SecretsService`
**Vraag:** Is bouwstap K-1 (`docs/design/credentials-sleutelbeheer-design.md` §6) afgerond en geverifieerd?
**Beslissing:** Ja (`bouwer-gemiddeld`, tests gedraaid door de hoofdsessie). `SecretsService` (Service, `@Component`, `@Value`-binding
zoals `LocalSourceDirectory`): AES-256-GCM via JDK, formaat `v1:<keyId>:<base64(nonce‖ciphertext+tag)>`, associated data in de
component; `SecretsNotConfiguredException` (`SECRETS_NOT_CONFIGURED`) en `CredentialUndecryptableException` (`CREDENTIAL_UNDECRYPTABLE`),
beide `ConflictException` → 409. Config: `catalogimport.secrets.keys` (`id1:base64,id2:base64`, env `CATALOG_SECRETS_KEYS`) en
`catalogimport.secrets.active-key-id` (env `CATALOG_SECRETS_ACTIVE_KEY_ID`) in `application.yml`, zonder sleutel of default.
Geen config = functie uit; ongeldige config = fail-fast bij opstart. Geen schema, geen API.
Tests: `SecretCipherTest` 11, `SecretsStartupValidationTest` 8, `SecretsPropertiesTest` 3 — groen; controle dat bestaande
Spring-contexten zonder secrets-config blijven starten: `PermissionHttpTest` 10, `LocalSourceDisabledTest` 5 — groen.
**Afwijkingen / nog voor te leggen aan de mens:**
- Property-vorm: één string-property i.p.v. de geïndexeerde lijst/`keyring_file`/`_FILE` uit het design-doc §2.3 (bestandsvariant
  kan later via `spring.config.import=configtree:`); design-doc nog niet gelijkgetrokken.
- `secret_key_check` niet in K-1 gebouwd (persistent → K-2), terwijl het design-doc §6 het onder K-1 noemt.
- HTTP-status 409 voor `CREDENTIAL_UNDECRYPTABLE` is een aanname van de Bouwer (design legt geen status vast).
**Door de mens aanvaard ("prima", 2026-09-29):** alle drie. Design-doc gelijkgetrokken (§2.3 property-vorm, §2.4 opstartvalidatie,
§2.6 409 voor beide foutcodes, §4.2 geen testmap in Service, §6 K-1 afgerond en `secret_key_check` naar K-2).
**Bron:** hoofdsessie na verificatie (gerichte tests) / docs/design/credentials-sleutelbeheer-design.md

---

## 2026-09-29 — Leveringsconfiguratie en verbindingsprofiel (SFTP): ontwerp bindend (L1-L8)
**Vraag:** Hoe worden de gegevens om automatisch bij een leverancier op te halen (eerst SFTP) gemodelleerd, inclusief de koppeling
naar de versleutelde credential — voorwaarde voor K-2, K-3 en K-4 (beslisdossier denker-zwaar `Ontwerp Leveringsconfiguratie/ConnectionProfile`, 2026-09-29)?
**Beslissing:** Door de mens: alle acht keuzes conform aanbeveling ("prima"), plus de aannames A1-A19 uit het dossier.
- **L1 = herroeping van de 27/09-ontwerpgrens** (tweede ontvangstweg: "geen Leveringsconfiguratie-entiteit, geen acquisitieservice")
  voor dit spoor. **"Geen scheduler" blijft** voorlopig staan; de scheduler krijgt na K-4 een eigen ontwerpstap. "Geen server-side
  ophaling" (2026-09-26) vervalt pas met K-4b.
- **L2 = de Leveringsconfiguratie hangt aan de taak** (`catalog_import_task.delivery_configuration_version_id`), niet aan `import_link`
  of de revisie. Planning blijft `trigger_expression` op de taak. De revisie houdt van laag 1 enkel `access_delivery_set_kind`; een
  DC-wijziging hoogt `access_version` niet op. De ophaalrun ís een `task_run` (geen aparte `fetch_run`).
- **L3 = onveranderlijke versierijen** (kop + versie) voor verbindingsprofiel en Leveringsconfiguratie; een taak neemt een nieuwe versie
  expliciet over. Een versie in gebruik wijzigt nooit (BA r.2397).
- **L4 = uitgaande verbindingen beveiligd:** (a) een credential is DB-afgedwongen aan één host gebonden (`bound_host` + samengestelde FK
  vanuit de profielversie; host wijzigen = wachtwoord opnieuw invoeren); (b) fail-closed property `catalogimport.fetch.allowed-hosts`
  (exacte hostnamen, beheerd door infra): niet gezet = fetch/scan/test geven 404 `FETCH_NOT_CONFIGURED`; host buiten de lijst =
  `FETCH_HOST_NOT_ALLOWED`; DNS één keer resolven en op dat IP verbinden.
- **L5 = verplichte vastgepinde hostsleutel** (algoritme + SHA-256-vingerafdruk per profielversie). Een scan-endpoint toont de
  vingerafdruk, de mens vergelijkt met de waarde die de leverancier via een ander kanaal geeft en bevestigt. Daarna strikt: mismatch
  = `SFTP_HOST_KEY_MISMATCH`, nooit auto-accept; sleutelwissel bij de leverancier = nieuwe profielversie.
- **L6 = bestandskeuze:** de eerste run na het koppelen neemt enkel het recentste matchende bestand (oudere = `OLDER_THAN_WATERMARK`);
  daarna chronologisch, oudste nieuwe eerst (bij gelijke mtime op naam), **één bestand per run**, `pending_file_count` zichtbaar; nooit
  een bestand ouder dan het laatst opgehaalde; overgeslagen bestanden blijven altijd zichtbaar (`fetch_file_observation`).
- **L7 = (a) niet achter `catalogimport.setup-api.enabled`:** credential-, profiel-, DC- en koppel-endpoints vragen MANAGE, in tweede
  lijn beschermd door de allowlist van L4. **(b) Detail-GET's met host, login, map en testlistings vragen MANAGE;** READ ziet enkel
  code, naam, status, `secretSet` en de runs — bewuste afwijking van A2 ("read = alle GET's"), op grond van BA r.2210 en precedent D8.
- **L8 = Apache MINA SSHD** (`sshd-core` + `sshd-sftp`) als nieuwe runtime-afhankelijkheid in Service (enkel client); de embedded
  SFTP-server enkel in test-scope. Versie expliciet pinnen.
**Belangrijkste aannames (bindend tenzij herroepen):** A3 na ophalen enkel `LEAVE`; A4 idempotentiesleutel = remote object per taak
(`sftp:<sha256(host:port:pad)[0..32]>:<mtime>:<grootte>` in `uk_delivery_idempotency`, zonder DC-versie-ID); A5 v1 enkel wachtwoord;
A6 `UNDECRYPTABLE` afgeleid, niet opgeslagen (credential-status enkel `ACTIVE`/`REVOKED`); A7 `secret_key_check` fail-fast bij opstart
voor de actieve sleutel, sleutel-ID's met omgevingsprefix; A9 "Nu ophalen" synchroon zonder automatische retry; A10 upload en servermap
geweigerd op een taak met DC (409 `TASK_HAS_DELIVERY_CONFIGURATION`); A11 hoogstens één DC-taak per koppeling; A12 defaults min. ouderdom
300 s, cap 1 GB, time-outs 15/15/60 s, listing-cap 10 000; A13 `delivery.source_kind = SFTP` (check verbreden); A15 één bestand per
levering; A16 nog geen verplichte servertest voor activatie; A17 remote map absoluut, geen `..`, geen recursie; A18 listing nooit stil
afgekapt; A19 `credential_ref` server-side UUID. Wijzigingen aan het voorlopige K-2-model (credentials-design §5): `ciphertext` en
`encryption_key_id` nullable met checks, projectconventies (identity by default, timestamptz, `*_by varchar(100)` + `*_by_subject`),
nieuw `label` en `bound_host`, `secret_key_check` met eigen AAD `catalogimport|secret_key_check|<keyId>|v1`.
**Bouwvolgorde (sequentieel):** DC-0 (design-doc) → K-2a (013 credential/event/key-check) → K-2b (herversleutelen R2a) → K-3
(credential-endpoints) → LC-1 (014 schema) → LC-2 (profielen/DC/taakkoppeling) → K-4a (MINA-adapter, allowlist, scan/test) → K-4b
(015 + ophaalrun, intake-refactor naar Service) → K-4c (herstel vastgelopen fetch-run) → F-1 (`/connections`) → F-2 (scherm 2:
koppelen, "Nu ophalen", runlijst) → later S-0/S-1 scheduler.
**Bron:** mens / denker-zwaar (`Ontwerp Leveringsconfiguratie/ConnectionProfile`) / business-analyse-leveranciersbibliotheken.md §14,
§16.6 / docs/decisions.md 2026-09-25 (A2), 2026-09-26, 2026-09-27, 2026-09-29

(DC-0 uitgevoerd: `docs/design/leveringsconfiguratie-design.md` nieuw, credentials-design §5/§6 definitief, `docs/openstaande-externe-punten.md`
punt 7. Aannames A1, A2, A5, A8, A14 door de hoofdsessie tegen het dossier gecorrigeerd.)

---

## 2026-09-29 — K-2a uitgevoerd: credential-schema (013), entiteiten en sleutelcontrole bij opstart
**Vraag:** Is bouwstap K-2a (`docs/design/leveringsconfiguratie-design.md` §10) afgerond en geverifieerd?
**Beslissing:** Ja (`bouwer-zwaar`, tests gedraaid door de hoofdsessie). Changeset `013-external-credential.sql` (additief):
`external_credential`, `external_credential_event` (append-only), `secret_key_check`. Domain-entiteiten + enums, Dao
(`ExternalCredentialRepository`, `ExternalCredentialEventRepository`, JDBC `SecretKeyCheckDao` met insert-if-absent),
`SecretsService` uitgebreid met de key-check-AAD (K-1-gedrag ongewijzigd), `SecretKeyCheckVerifier` (`SmartInitializingSingleton`,
vóór de HTTP-poort). `ConfigurationActorSubjectSchemaTest` opgehoogd naar 25 subjectkolommen op 17 tabellen. Geen endpoints.
Tests (`run-full-tests.ps1`, eigen schema): `ExternalCredentialSchemaTest` 20, `SecretKeyCheckStartupTest` 9,
`CredentialLeakDetectionTest` 3, `ConfigurationActorSubjectSchemaTest` 12, `PermissionHttpTest` 10; K-1-regressie `SecretCipherTest` 11,
`SecretsPropertiesTest` 3, `SecretsStartupValidationTest` 8 — alle groen (76/76).
**Invullingen van de Bouwer (nog voor te leggen aan de mens):** (1) elke sleutel in de ring wordt gecontroleerd, niet enkel de
actieve (strenger dan A7); (2) extra event-checks: CREATED ⇒ geen `previous_key_id`, REENCRYPTED ⇒ beide ID's, REVOKED ⇒ geen
`new_key_id`; (3) `created_by` not null; (4) `secret_key_check` zonder JPA-entiteit (merge zou een bestaande controlewaarde kunnen
overschrijven); (5) opstartcontrole via `SmartInitializingSingleton`.
**Door de mens beslist (2026-09-29):** (a) **pgjdbc `logServerErrorDetail=false`** op de datasource in alle profielen (ook de
testrun), zodat "Failing row contains"-details (met ciphertext) nooit in exceptiemeldingen of logs belanden — bewust aanvaard dat
ook bij andere databasefouten dat detail wegvalt; (b) **`bound_host`-normalisatie ook als additieve DB-check** (kleine letters, geen
punt achteraan, niet leeg) naast de normalisatie in de K-3-service. De invullingen (1)-(5) van de Bouwer blijven zoals gebouwd.
**Uitgevoerd (2026-09-29, `bouwer-gemiddeld`, tests door de hoofdsessie):** (a) `spring.datasource.hikari.data-source-properties.logServerErrorDetail: false`
in `application.yml` (local/demo en het testscript overschrijven `hikari.*` niet); (b) additieve changeset `013-4-bound-host-normalized`
(`ck_external_credential_bound_host_normalized`) + weigering in de `ExternalCredential`-constructor. Tests: `ExternalCredentialSchemaTest` 21,
`CredentialLeakDetectionTest` 4, `SecretKeyCheckStartupTest` 9, `PermissionHttpTest` 10 — groen. Tegenproef: met de property tijdelijk op
`true` faalt `CredentialLeakDetectionTest.aCheckViolationDoesNotEchoTheFailingRowWithItsCiphertext` (de test bewijst dus echt iets);
property daarna teruggezet op `false`.

---

## 2026-09-29 — K-2b uitgevoerd: herversleutelen bij opstart (R2a)
**Vraag:** Is bouwstap K-2b (`docs/design/leveringsconfiguratie-design.md` §10) afgerond en geverifieerd?
**Beslissing:** Ja (`bouwer-gemiddeld`, tests door de hoofdsessie). `SecretsRotationService` (Service, `SmartInitializingSingleton`) roept eerst
expliciet `SecretKeyCheckVerifier.verify()` aan (callbackvolgorde is registratievolgorde, niet afhankelijkheidsvolgorde) en herversleutelt daarna
ACTIVE-credentials onder een niet-actieve sleutel uit de ring. Per rij een eigen transactie met guard (`update … where id and ciphertext and
encryption_key_id and status='ACTIVE'`), `REENCRYPTED`-event (SYSTEM, changed_by null, previous/new_key_id) enkel bij exact 1 geraakte rij.
`secret_updated_*` blijft ongewijzigd. Rijen onder een keyId buiten de ring en REVOKED-rijen worden niet aangeraakt; een niet-ontsleutelbare rij
geeft één WARN (`CREDENTIAL_UNDECRYPTABLE`, rij-id, keyId) en wordt overgeslagen. Repository: `findRotationCandidates`, `replaceCiphertextIfUnchanged`,
`countRowsPerEncryptionKeyId` (operatorhulp, geen endpoint). Operatorprocedure sleutelrotatie in credentials-design §6. Geen schema, geen endpoints.
Tests: `SecretsRotationTest` 11 (na een testfix: de referentie-`secretUpdatedAt` werd uit het in-memory object gelezen — 100-ns-precisie — i.p.v.
uit de database — microseconden; productiecode ongewijzigd), `ExternalCredentialSchemaTest` 21, `CredentialLeakDetectionTest` 4,
`SecretKeyCheckStartupTest` 9, `PermissionHttpTest` 10 — groen.
**Invulling van de Bouwer, door de mens aanvaard ("ja", 2026-09-29):** een onverwachte `RuntimeException` op één rij wordt gelogd (enkel
exceptietype) en breekt het opstarten niet af; de rij komt bij de volgende start opnieuw aan bod.
**Bron:** hoofdsessie na verificatie (gerichte tests) / docs/design/credentials-sleutelbeheer-design.md

---

## 2026-09-29 — K-3: een ingetrokken credential mag heractiveerd worden
**Vraag:** Mag een ingetrokken (`REVOKED`) credential later opnieuw een waarde krijgen (open gelaten in K-2a: "of een ingetrokken credential nog
een nieuwe waarde mag krijgen, beslist K-3")? Opties: (a) nee, `REVOKED` is eindstatus (aanbevolen); (b) ja, heractiveren.
**Beslissing:** Door de mens: **(b) heractiveren** (afwijkend van de aanbeveling). `PUT /credentials/{ref}/secret` op een `REVOKED`-credential
versleutelt de nieuwe waarde met de actieve sleutel, zet de status terug op `ACTIVE` en schrijft een `REPLACED`-event (met verplichte reden;
`previous_key_id` null omdat de oude ciphertext gewist is). Zelfde recht als vervangen (MANAGE). De historiek van de intrekking blijft in de
events; de `revoked_*`-velden op de rij worden bij heractiveren leeggemaakt (de rij toont de huidige toestand). Bestaande profielversies die
naar deze credential verwijzen, werken daarna weer. `credential_ref`, `secret_kind` en `bound_host` veranderen niet (AAD en host-binding blijven).
**Bron:** mens / docs/decisions.md 2026-09-29 (K-2a) / docs/design/credentials-sleutelbeheer-design.md

---

## 2026-09-29 — K-3 uitgevoerd: credential-endpoints
**Vraag:** Is bouwstap K-3 (`docs/design/leveringsconfiguratie-design.md` §10) afgerond en geverifieerd?
**Beslissing:** Ja (`bouwer-zwaar`, tests door de hoofdsessie). `CredentialService` (patroon `IssueCaseService`: `TransactionTemplate` per schrijfactie,
rijlock `findByCredentialRefForUpdate`), `CatalogImportCredentialController` onder `/api/catalog-import/credentials` (POST aanmaken, PUT `/{ref}/secret`
vervangen/heractiveren, POST `/{ref}/revoke`, GET lijst/detail/events), `service.support.HostNames` (hostnormalisatie; LC-2 moet die ook gebruiken).
Alle zes endpoints MANAGE (ook de GET's, omdat elk antwoord `boundHost` bevat — L7b), niet achter de setup-vlag, 403 vóór 400/404/409. Antwoorden
nooit secret, ciphertext, key-ID of subject. Foutcodes o.a. `CREDENTIAL_HOST_INVALID`, `CREDENTIAL_SECRET_KIND_NOT_SUPPORTED` (SSH_PRIVATE_KEY, A5),
`CREDENTIAL_NOT_FOUND`, `CREDENTIAL_ALREADY_REVOKED` (409, geen event), `SECRETS_NOT_CONFIGURED`. `fase5-perm-design.md` §1 één rij toegevoegd.
Tests: `CredentialHttpTest` 10, `PermissionCoverageTest` 2, `PermissionReadEndpointsHttpTest` 9, `PermissionWriteEndpointsHttpTest` 7; regressie
`ExternalCredentialSchemaTest` 21, `CredentialLeakDetectionTest` 4, `SecretsRotationTest` 11, `PermissionHttpTest` 10 — groen (74/74).
(`PermissionHttpTest` duurde 4194 s: in de log een sprong van ~67 min met Hikari "Thread starvation or clock leap detected" — vermoedelijk
slaap/pauze van de machine, niet gerelateerd aan K-3; de test zelf slaagde.)
**Afwijkingen van de Bouwer, door de mens aanvaard ("Ja ik ga akkoord", 2026-09-29):** (1) intrekken werkt ook zonder sleutelring (A8; V7 blokkeert enkel opslaan);
(2) gebruik (aantal profielversies) nog niet in het antwoord — volgt in LC-2; (3) de schrijfendpoints lezen de JSON-body zelf i.p.v. `@RequestBody`,
zodat een Jackson-parsefout de secret niet in Spring-logs kan herhalen; (4) strengere hostvalidatie (enkel ASCII letters/cijfers en `. - _ : [ ]`,
≤ 255); (5) secret ≤ 1024 tekens en niet enkel witruimte; (6) tijdstempels op microseconden afgekapt.
**Bron:** hoofdsessie na verificatie (gerichte tests) / docs/design/credentials-sleutelbeheer-design.md / docs/design/leveringsconfiguratie-design.md

---

## 2026-09-29 — LC-1 uitgevoerd: schema 014 (verbindingsprofiel, Leveringsconfiguratie), Domain en Dao
**Vraag:** Is bouwstap LC-1 (`docs/design/leveringsconfiguratie-design.md` §10) afgerond en geverifieerd?
**Beslissing:** Ja (`bouwer-gemiddeld`, tests door de hoofdsessie). Changeset `014-delivery-configuration.sql` (014-1..014-7, met rollback):
`connection_profile`, `connection_profile_version` (samengestelde FK naar `uk_external_credential_host_binding`, `credential_host = host`,
auth/secret_kind-koppeling, poort 1-65535 default 22, hostnormalisatie zoals 013-4), `delivery_configuration`, `delivery_configuration_version`
(`post_fetch_action` enkel `LEAVE`), `delivery_configuration_file_condition`, `acquisition_config_event` (patroon 012), en de nullable kolom
`catalog_import_task.delivery_configuration_version_id` (FK + index; niets hernoemd). Domain-entiteiten + enums; versies onveranderlijk via JPA
(`updatable = false`, geen setters; het project kent geen triggers en Domain heeft geen Hibernate-dependency). Dao-repositories minimaal (+ `countByCredentialId`
voor het gebruik in het credentialantwoord, LC-2). `ConfigurationActorSubjectSchemaTest` → 30 subjectkolommen op 22 tabellen.
Tests: `DeliveryConfigurationSchemaTest` 29, `ConfigurationActorSubjectSchemaTest` 12, `ExternalCredentialSchemaTest` 21, `CredentialHttpTest` 10,
`PermissionHttpTest` 10 — groen (82/82). Rollbacks van 014 niet uitgevoerd.
**Invullingen van de Bouwer, door de mens aanvaard ("top", 2026-09-29):** `config_hash` not null, `change_reason` nullable, `created_by` not null met
subject ⇒ naam, `based_on_version_id` als self-FK, extra checks (`version_number >= 1`; username, remote_directory, compare_value, hostsleutelvelden niet leeg;
group/sequence ≥ 0 — 0- of 1-gebaseerd beslist LC-2), `acquisition_config_event` zonder "minstens één verwijzing"-check, indexen op alle FK-kolommen.
**Bron:** hoofdsessie na verificatie (gerichte tests) / docs/design/leveringsconfiguratie-design.md

---

## 2026-09-29 — LC-2 uitgevoerd: verbindingsprofielen, Leveringsconfiguraties en taakkoppeling (backend)
**Vraag:** Is bouwstap LC-2 (`docs/design/leveringsconfiguratie-design.md` §10) afgerond en geverifieerd?
**Beslissing:** Ja (`bouwer-zwaar`, tests door de hoofdsessie). Nieuw: `ConnectionProfileService`, `DeliveryConfigurationService`,
`TaskDeliveryConfigurationService`, `AcquisitionConfigInput`, drie controllers (`/connection-profiles`, `/delivery-configurations`,
`/tasks/{id}/delivery-configuration` PUT/DELETE met reden in JSON-body). Alle endpoints MANAGE (ook lijst/detail, zoals K-3), niet achter de
setup-vlag. A10 gebouwd: één check in `DeliveryIntakeService.resolve` → 409 `TASK_HAS_DELIVERY_CONFIGURATION` voor upload én servermap (ook retry).
A11 en "lopende run" (PENDING/RUNNING) geserialiseerd via rijslot op alle taken van de koppeling. `CredentialView` + `/credentials` additief
`profileVersionCount`. Keuzes: vingerafdruk OpenSSH `SHA256:<43 tekens base64, canoniek>`; algoritmen `ssh-ed25519`, `ecdsa-sha2-nistp256/384/521`,
`rsa-sha2-256/512` (`ssh-rsa`/`ssh-dss` geweigerd); `config_hash` via bestaande `RevisionConfigHashes.hash` (lagen `connection_profile_version/v1`,
`delivery_configuration_version/v1`); condities 1-gebaseerd, geen wildcards; map absoluut zonder `.`/`..`/`\`; max. grootte ≤ 1024³ (400 daarboven,
nooit stil aangepast). Geen schemawijziging, geen nieuwe dependency.
Tests: `ConnectionProfileHttpTest` 8, `DeliveryConfigurationHttpTest` 9, `TaskBindingHttpTest` 8, `CredentialHttpTest` 11, `DeliveryConfigurationSchemaTest` 29,
`PermissionCoverageTest` 2, `PermissionReadEndpointsHttpTest` 10, `PermissionWriteEndpointsHttpTest` 8, `DeliveryUploadTest` 15, `LocalSourceDeliveryTest` 15,
`LocalSourceDisabledTest` 5, `PermissionHttpTest` 10 — groen (130/130).
**Nog voor te leggen aan de mens:** (1) het aanmaken van een profiel-/DC-versie schrijft geen `acquisition_config_event` (de check kent geen soort
"aangemaakt"; audit staat in de versierij zelf) — een event vraagt een additieve verbreding van de check; (2) smal racevenster A10: een upload die
zijn voorcontrole haalt vlak vóór een koppeling commit, kan nog een run starten — oplossing voorgesteld in K-4b (intake naar Service met slot);
(3) K-4a moet het gescande RSA-sleuteltype (`ssh-rsa`) afbeelden op `rsa-sha2-256/512`.
**Important technical constraint discovered (Bouwer):** een PESSIMISTIC_WRITE-query ververst een entiteit die al in de persistence context staat niet;
daarom eerst enkel het koppelings-id (scalar query) en dan de taken met slot. Voorstel: terugschrijven naar design §11 na akkoord.
**Door de mens niet expliciet beantwoord ("volgende", 2026-09-29):** de drie punten blijven zoals gebouwd — (1) geen aanmaak-event, (2) racevenster
A10 mee te nemen in K-4b, (3) constraint niet teruggeschreven.
**Bron:** hoofdsessie na verificatie (gerichte tests) / docs/design/leveringsconfiguratie-design.md

---

## 2026-09-29 — K-4a uitgevoerd: MINA SSHD, allowlist, hostsleutelscan en verbindingstests (zonder download)
**Vraag:** Is bouwstap K-4a (`docs/design/leveringsconfiguratie-design.md` §10) afgerond en geverifieerd?
**Beslissing:** Grotendeels (`bouwer-zwaar`, tests door de hoofdsessie). Apache MINA SSHD **2.15.0** (`sshd-common/-core/-sftp` in root-`dependencyManagement`,
`sshd-core`+`sshd-sftp` in Service; Boot-BOM beheert het niet); geen BouncyCastle. `FetchHostPolicy` (`catalogimport.fetch.allowed-hosts` komma-lijst via
`HostNames`; afwezig = 404 `FETCH_NOT_CONFIGURED`; ongeldige ingang/wildcard = fail-fast; één DNS-resolutie, verbinden op IP-literal via `FetchTarget`;
`catalogimport.fetch.allow-loopback` default false voor 127/8 en ::1 (enkel tests/dev, WARN bij opstart); link-local incl. 169.254.169.254, multicast,
0.0.0.0/::, 0/8, broadcast altijd geweigerd; private adressen enkel via de allowlist). `SftpConnector` (eigen `SshClient` per operatie,
`HostConfigEntryResolver.EMPTY` — geen `~/.ssh/config`, enkel wachtwoord via `PasswordIdentityProvider` pas na hostsleutelcontrole, time-outs PT15S/PT15S/PT60S,
listing-cap 10 000, geen download). `ConnectionTestService` + `CatalogImportConnectionTestController`: `POST /connection-profiles/host-key-scan`,
`POST /connection-profile-versions/{id}/test`, `POST /delivery-configuration-versions/{id}/test` (MANAGE, niet achter de setup-vlag; events
`HOST_KEY_SCANNED`/`CONNECTION_TESTED`). Scan van een host buiten de allowlist → 409 `FETCH_HOST_NOT_ALLOWED` (event wél geschreven); bij tests een
200-uitkomst (§5). Min. ouderdom en max. grootte tellen niet mee in de DC-test (K-4b).
**RSA-afbeelding (LC-2 open punt 3; door de mens bevestigd 2026-09-29):** een RSA-hostsleutel wordt gerapporteerd
als `rsa-sha2-512` (anders `rsa-sha2-256`), nooit `ssh-rsa`; bij een vastgepinde RSA-sleutel biedt de client enkel rsa-sha2-512/256 aan; vergeleken
wordt de vingerafdruk van de sleutel.
Tests: `SftpConnectorTest` 13/14 (zie hieronder), `PermissionCoverageTest` 2, `FetchAllowlistTest` 9, `ConnectionTestHttpTest` 15,
`PermissionReadEndpointsHttpTest` 10, `PermissionWriteEndpointsHttpTest` 8, `ConnectionProfileHttpTest` 8, `DeliveryConfigurationHttpTest` 9,
`PermissionHttpTest` 10 — groen, op één na (99/100).
**Open, bij de mens:** `SftpConnectorTest.ed25519WorksWithTheJdkAloneWithoutBouncyCastle` faalt: MINA SSHD 2.15.0 ondersteunt `ssh-ed25519`
niet met de JDK alleen. De overige SFTP-tests draaien op ECDSA P-256. **Door de mens beslist (2026-09-29): BouncyCastle toevoegen**
(`bcprov`, versie pinnen in root-`dependencyManagement`, runtime-dependency van Service) zodat MINA ed25519 ondersteunt; net.i2p eddsa (niet onderhouden)
en "niet ondersteunen" verworpen. **Uitgevoerd:** `org.bouncycastle:bcprov-jdk18on` **1.80** (property + root-`dependencyManagement`, scope runtime in
Service); test hernoemd naar `ed25519HostKeysAreSupported`. Hertest: `SftpConnectorTest` 14/14, `FetchAllowlistTest` 9, `ConnectionTestHttpTest` 15,
`PermissionHttpTest` 10 — groen. K-4a daarmee volledig groen. BC-versie vóór productie nog op open CVE's te controleren.
Ook niet getest: `REMOTE_PERMISSION_DENIED` (geen betrouwbare opzet op Windows).
**Important technical constraints discovered (Bouwer):** `SshClient.setUpDefaultClient()` leest standaard `~/.ssh/config` (kan host/poort/ProxyJump
buiten de allowlist omleiden) → `HostConfigEntryResolver.EMPTY` verplicht; `addPasswordIdentity` zou op DEBUG een digest van het wachtwoord loggen
(te verifiëren) → `PasswordIdentityProvider`. Voorstel: terugschrijven naar design §11 na akkoord. **Door de mens aanvaard ("Ja", 2026-09-29) en
in K-4b teruggeschreven naar design §11.**
**Bron:** hoofdsessie na verificatie (gerichte tests) / docs/design/leveringsconfiguratie-design.md

---

## 2026-09-30 — K-4b uitgevoerd: ophaalrun ("Nu ophalen"), intake-refactor en racevenster A10
**Vraag:** Is bouwstap K-4b (`docs/design/leveringsconfiguratie-design.md` §10) afgerond en geverifieerd?
**Beslissing:** Ja (`bouwer-zwaar`; twee fouten na de bouw hersteld door `bouwer-licht`: een multi-catch zonder gemeenschappelijke `getCode()` in
`FetchRunService`, en een commentaarregel in 015 die met `-- changeset` begon en door Liquibase als header gelezen werd; tests door de hoofdsessie).
Changeset `015-fetch-run.sql`: `task_run` + `trigger_source`, `delivery_configuration_version_id`, `outcome_code`, `outcome_message`, `pending_file_count`
(nullable, geen backfill); nieuwe tabel `fetch_file_observation`; `ck_delivery_source_kind` verbreed met `SFTP` (enige niet-additieve stap; rollback enkel
zolang er geen SFTP-levering is). Intake-refactor: `DeliveryIntakeService` herschreven (zelfde volgorde/meldingen; `registerInRun` voor een bestaande run),
orkestratie intake → screening naar `DeliveryReceptionService.screenIfCreated`; endpoints, statuscodes en antwoorden van upload/servermap ongewijzigd.
**Racevenster A10 opgelost:** upload-/servermapregistratie, taakkoppeling en start van een ophaalrun nemen hetzelfde rijslot op de taak.
`FetchRunService` + `CatalogImportTaskRunController`: `POST /tasks/{id}/fetch-runs` (MANAGE, 201), `GET /tasks/{id}/runs` (READ, laatste 20),
`GET /task-runs/{id}` (READ, met waarnemingen). Watermark = laatste waarneming met geregistreerde levering van de taak (over DC-versies heen),
vergeleken op (mtime in seconden, naam). Archief wordt bij falen opgeruimd (enkel een procescrash laat een wees-object — K-4c).
Docs: design §0/§3.3/§4.6/§6/§10/§11 (incl. de K-4a-constraints), README en handleiding (handmatig ophalen via API; nog geen scheduler, geen Frontend).
Tests (5 groepen): `SftpConnectorTest` 14, `SftpDownloadTest` 7, `FetchIdempotencyTest` 7, `FetchRunTest` 15, `PermissionCoverageTest` 2,
`BatchBaselineHttpTest` 14, `DeliveryUploadTest` 15, `LocalSourceDeliveryTest` 15, `LocalSourceDisabledTest` 5, `ScreeningRecoveryServiceTest` 3,
`ConnectionTestHttpTest` 15, `PermissionHttpTest` 10, `TaskBindingHttpTest` 8, `ConfigurationActorSubjectSchemaTest` 12, `DeliveryConfigurationSchemaTest` 29,
`PermissionReadEndpointsHttpTest` 11, `PermissionWriteEndpointsHttpTest` 8, `FetchAllowlistTest` 9 — groen (199/199). **"Geen server-side ophaling"
(2026-09-26) vervalt hiermee; "geen scheduler" blijft.**
**Invullingen van de Bouwer, door de mens aanvaard ("Ja akkoord", 2026-09-30):** (1) revisie-, prijsveld- en bookmarkcontroles van de upload ook als voorcontrole van
een ophaalrun (409, geen run); (2) nieuwe code `FETCH_INTERNAL_ERROR` (run FAILED, 500); (3) geen `TASK_NOT_MANUAL`-controle bij ophalen en de
`active`-vlag van DC/profiel wordt niet gecontroleerd; (4) `FETCH_NOT_CONFIGURED` vóór de taak-404; (5) bestand zonder mtime of met mtime in de
toekomst → `TOO_YOUNG`; (6) `outcome_code` blijft `FETCHED` als de screening daarna technisch faalt (run FAILED, batch `SCREENING_FAILED`);
(7) runlijst zonder waarnemingen, met extra `batchStatus` en `triggeredBy`; (8) na herkoppelen naar een andere map worden bestanden van vóór de
watermark nooit meer opgehaald (wel zichtbaar).
**Bron:** hoofdsessie na verificatie (gerichte tests) / docs/design/leveringsconfiguratie-design.md
## 2026-09-30 — Frontend-restyle naar de Prodis-look & feel
**Vraag:** De CatalogImport-frontend ziet er compleet anders uit dan Prodis; hoe trekken we die gelijk?
**Beslissing:** Referentie is `C:\Users\Willem\IdeaProjects\Prodis\Web\src\main\webapp\app` (React + MUI 7, "Minimal"-thema). CatalogImport blijft
**standalone** (niet inbedden; geen impact op login/routing). **Geen MUI** overnemen: de stijl wordt in eigen CSS nagebootst via `src/styles/tokens.css`
+ `reset.css` en de bestaande CSS-modules. Keuzes van de mens: (1) **zijbalk links zoals Prodis** (wit, ca. 300px, header erboven) i.p.v. een topbalk;
titel "CatalogImport" als tekst, **geen logo**; (2) fonts **Public Sans Variable** (tekst) en **Barlow** (h1-h3) als npm-dependency
(`@fontsource-variable/public-sans`, `@fontsource/barlow`, lokaal gebundeld); (3) **primaire knop donkergrijs `#1C252E`** (Prodis-default), cyaan
`#00B8D9` enkel als accent, links/actief menu in `#006C9C`. Tokens (uit Prodis `theme\core`): tekst `#1C252E`/`#637381`/`#919EAB`; achtergrond `#FFFFFF`,
neutral `#F4F6F8`, grijs 300 `#DFE3E8`; rand `rgba(145,158,171,.2)` (outlined knop `.32`); error `#FF5630`, success `#22C55E`, warning `#FFAB00`,
info `#00A76F`; radius 6/8/16px; tabelkop `#F4F6F8`/`#637381`/600 met dashed rijscheiding; labels soft (kleur op 16%, tekst in darker-tint);
dialog/card radius 16px. Uitvoering in sequentiële slices: 1 tokens+reset+fonts, 2 shell (`App.tsx`, `NavLink`), 3 ActorBar, 4 gedeelde componenten,
5 pagina-CSS (enkel harde waarden → tokens), 6 build/lint/gerichte vitest. Functionaliteit en API-contracten ongewijzigd.
**Bron:** denker-gemiddeld (analyse Prodis-thema) + mens (navigatie, fonts, knopkleur, standalone, geen logo)

---

## 2026-09-30 — K-4c uitgevoerd: herstel van een vastgelopen ophaalrun
**Vraag:** Is bouwstap K-4c (`docs/design/leveringsconfiguratie-design.md` §10) afgerond en geverifieerd?
**Beslissing:** Ja (`bouwer-gemiddeld`, tests door de hoofdsessie). `FetchRunRecoveryService` (precedent `PublicationRunService.abortRun` en
`ScreeningRecoveryService`): `POST /task-runs/{runId}/abort {reason}` (MANAGE, 200 met `TaskRunView`; FAILED, `FETCH_MANUALLY_ABORTED`, actor + reden in
`outcome_message`, concurrency-token vrij; 400 `FETCH_ABORT_REASON_REQUIRED`, 404 `TASK_RUN_NOT_FOUND`, 409 `FETCH_RUN_NOT_STUCK` voor een niet-RUNNING run,
een niet-ophaalrun of een run met levering; niet achter de allowlist). Opstartherstel achter `catalogimport.fetch.recovery-on-startup` (default true):
MANUAL_/SCHEDULED_FETCH-runs in RUNNING zonder levering en ouder dan `catalogimport.fetch.stuck-after` (default PT60M, fail-fast bij ongeldig/≤0) → FAILED
`FETCH_TIMED_OUT`. Race: afbreken, herstel, `registerInRun` en `FetchRunService.close` nemen allemaal het rijslot op de taak en herlezen de status
(`close` kreeg dat slot erbij). Geen schemawijziging. Tests: `FetchRunRecoveryTest` 10, `FetchRunTest` 15, `FetchIdempotencyTest` 7, `ScreeningRecoveryServiceTest` 3,
`PublicationRunHttpTest` 14, `PermissionCoverageTest` 2, `PermissionWriteEndpointsHttpTest` 8, `PermissionHttpTest` 10 — groen (69/69).
**Invullingen van de Bouwer (nog voor te leggen aan de mens):** (1) afbreken zonder tijdsvoorwaarde (zoals het publicatieprecedent): ook een verse
RUNNING ophaalrun zonder levering; de drempel geldt enkel voor het automatische herstel; (2) geen automatisch herstel bij de eerstvolgende aanvraag
(enkel bij opstart); (3) wees-archiefobject na een procescrash wordt niet opgeruimd (uuid-pad zonder run-id — bekende beperking).
**Bron:** hoofdsessie na verificatie (gerichte tests) / docs/design/leveringsconfiguratie-design.md

---

## 2026-09-30 — Gebruiker richt zelf een nieuwe leverancier + taak in (MANAGE)
**Vraag:** Een eindgebruiker kan vandaag geen nieuwe leverancier van nul inrichten: bronorganisatie, sjabloon/bladwijzers en taak zijn
enkel via de API aan te maken, en de schrijf-endpoints bestaan enkel met `catalogimport.setup-api.enabled=true` (alleen het demo-profiel).
Moet dat in de applicatie zelf kunnen?
**Beslissing:** Door de mens: **ja — een gebruiker MOET een leverancier en een taak zelf kunnen aanmaken in de UI, met het recht `MANAGE`.**
Het verhaal voor de gebruiker bestaat uit **twee grote blokken: (1) aanmaken van een nieuwe taak** (inclusief wat daarvoor nodig is:
leverancier, CSV-beschrijving, koppeling) en **(2) controleren ervan**. De concrete invulling (schermen, wat "controleren" precies inhoudt,
wat er met de setup-vlag gebeurt, bouwvolgorde) volgt uit een denker-zwaar-ontwerp en wordt aan de mens voorgelegd vóór er gebouwd wordt.
**Aanvulling door de mens (zelfde dag):** ook de controle moet **heel duidelijk** zijn voor de gebruiker. Engelse termen in de UI
(statussen zoals `SCREENED`/`AWAITING_APPROVAL`, enumwaarden, technische veldnamen tussen haakjes, foutcodes) worden **vertaald naar
begrijpelijk Nederlands**, en ook een Nederlandse term krijgt **een korte uitleg van wat het betekent en wat het doet** (hulptekst bij het
veld, de status of de knop). Geldt zeker voor de twee nieuwe blokken; de rest van de UI volgt dezelfde regel (reikwijdte en volgorde in het
ontwerp).
**Bron:** mens

---

## 2026-09-30 — Nieuwe leverancier + taak (NT-spoor): V1-V7 beslist
**Vraag:** Beslisdossier denker-zwaar `Nieuwe leverancier en taak inrichten in de UI (MANAGE) + controleren + terminologie` (2026-09-30),
vragen V1-V7.
**Beslissing (door de mens, via meerkeuze):**
- **V1 controleren = C + A + D:** (C) gereedheidscontrole/checklist zonder bestand, (A) proefinlezing zonder opslag op de (concept)versie,
  én (D) de eerste echte levering blijft als laatste controle gelden (bestaand gedrag, geen bouwwerk). Testlevering met bewaard bewijs (B)
  wordt **niet** gebouwd.
- **V2 setup-vlag = a:** de schrijfpaden voor inrichten (bronorganisatie, definitie, revisie, PATCH revisie, mappings/filters/kritiekheid,
  activeren, opvolger, koppeling, taak, sjablonen lezen + materialiseren, bookmarkwaarden koppeling) komen achter `catalogimport.setup-api.enabled`
  vandaan, **zelfde paden en bodies**, recht MANAGE (lezen READ). Achter de vlag blijven enkel sjabloonbeheer (bookmarks/usages declareren) en
  `GET /setup/overview`. Gedeeltelijke herroeping van 26/09 (V4-herroeping) en van 27/09 ("scherm 1b blijft volledig achter de vlag").
  Deelvraag (sjabloon aanmaken via dit pad met Beheren): conform aanbeveling ja, zonder extra controle.
- **V3 taak = a:** aparte laatste stap in het stappenplan met het bestaande `POST /setup/tasks`; materialiseren blijft géén taak maken.
- **V4 eigenaar gematerialiseerde definitie = a:** blijft de bronorganisatie van het sjabloon (keuze 4 van 23/09, A31 ongewijzigd); een
  rechtstreeks leverende leverancier gebruikt "zelf beschrijven".
- **V5 audit = b (nee, afwijkend van de aanbeveling):** geen `created_by`-kolommen op bronorganisatie/koppeling/taak en geen `updated_by` op
  revisie; enkel serverlog. Story NT-2 vervalt; geen schemawijziging in dit spoor.
- **V6 activeren = a:** geen verplichte proefinlezing; wel een duidelijke waarschuwing bij activeren zonder (geslaagde) proefinlezing.
  De afwijking van BA1 §14.19 (zes verplichte resultaten vóór activering, ontdekking O5) is daarmee bewust aanvaard.
- **V7 codes = a:** Nederlandse tekst vooraan; technische code klein en inklapbaar onder "Technische details (voor support)" en als tooltip op
  statuslabels. Regel "de code staat altijd in beeld" (`frontend-scherm3-bundel-design.md:277-279`) wordt "de code is altijd opvraagbaar".
  **`docs/handleiding/begrippen.md` wordt de enige redactionele bron** van woorden + uitleg; de frontend-woordenlijst volgt, een test bewaakt gelijkloop.
**Aannames uit het dossier (bindend tenzij herroepen):** A1 wizard maakt enkel OWN_DEFINITION; A2 geen extra mappings in de eerste versie
(geen leesendpoint veldcatalogus, O3); A3 herkenningsversie standaard 2; A4 proefinlezing begrensd door max. uploadgrootte + property
voorbeeldregels (default 20), één INFO-logregel zonder inhoud; A5 doelbibliotheek wordt niet tegen Prodis gecontroleerd (zo gemeld in de checklist).
**Bouwvolgorde (sequentieel):** NT-1 (UploadPage/codes.ts-teksten, licht) → NT-3 (vlag, 409 i.p.v. 500 bij gelijktijdig aanmaken, stabiele `code`
op 400, zwaar) → NT-4 (`TaskRow` + `activeRevisionId`/`importDefinitionId`, licht) → NT-5 (terminologiebasis `Frontend/src/terms/`, `<Term>`,
StatusBadge met domein, gemiddeld) → NT-6 (wizard "zelf beschrijven", zwaar) → NT-7 (sjabloonpad + taken in boom, gemiddeld) → NT-8
(gereedheidsendpoint `GET /import-links/{id}/readiness`, READ, zwaar) → NT-9a (contract proefinlezing, denker-gemiddeld) → NT-9
(`POST /revisions/{id}/trial-reads`, MANAGE, zwaar) → NT-10 (scherm "Controleren", zwaar) → NT-11a/b/c (terminologie bestaande schermen) →
NT-12 (documentatie).
**Bron:** mens / denker-zwaar (beslisdossier 2026-09-30)

---

## 2026-09-30 — NT-1 uitgevoerd: misleidende teksten over taken rechtgezet
**Vraag:** Is NT-1 afgerond en geverifieerd?
**Beslissing:** Ja (`bouwer-licht`, na één reviewronde; tests door de hoofdsessie). `UploadPage.tsx`: zonder manuele taak "Er is nog geen taak.
Maak er een via Inrichting → Nieuwe leverancier en taak (recht Beheren nodig)." (link naar `/setup`), kopcommentaar verwijst naar het NT-spoor;
`errors/codes.ts` `NO_ACTIVE_REVISION.whatNow`: "Activeer de conceptversie onder Inrichting (open de versie → "Revisie activeren")."; `SetupOverviewPage.tsx`
`TASKS_UNAVAILABLE_TEXT`: "Taken van deze koppeling staan nog niet in dit overzicht; u kiest ze bij Levering uploaden." Tests bijgewerkt.
`tsc -p tsconfig.app.json --noEmit` schoon; vitest 28 bestanden, 336/336 groen.
**Bron:** hoofdsessie na verificatie

---

## 2026-09-30 — NT-3 uitgevoerd: inrichtpaden zonder setup-vlag, 409 bij race, `code` op 400
**Vraag:** Is NT-3 afgerond en geverifieerd?
**Beslissing:** Ja (`bouwer-zwaar`, tests door de hoofdsessie). `CatalogImportSetupController`, `CatalogImportTemplateController` en
`CatalogImportLinkController` zonder `@ConditionalOnProperty`; nieuwe `CatalogImportSetupOverviewController` (`GET /setup/overview`, READ) en
`CatalogImportTemplateDeclarationController` (bookmark-/usage-declaratie, MANAGE) blijven achter de vlag. Paden, bodies en `@RequiresPermission`
ongewijzigd. `SetupService`: `DataIntegrityViolationException` op `uk_source_organisation_code`, `uk_import_definition_code`, `uk_import_link_code`
(voorrang), `uk_import_link_scope`, `uk_catalog_import_task_name` → dezelfde `*_IN_USE`-409; andere violations ongewijzigd. 400's uit SetupService
dragen `code` (`CONFIG_*`; veldfouten `<VELD>_REQUIRED|_TOO_LONG|_INVALID`, bv. `DELIMITER_REQUIRED`, `DISCOUNT_CODE_FIELD_INVALID`,
`FIELD_KEY_INVALID`, `CRITICALITY_INVALID`); `error`-tekst byte-identiek. Docs: controller-javadoc, `SetupService`, `application.yml`-commentaar,
`fase5-perm-design.md` §1/§5. Frontend ongewijzigd (vlagmeldingen reageren enkel op 404 zonder code).
Tests (`run-full-tests.ps1`, 14 klassen): 140/140 groen, 0 falend — o.a. nieuw `SetupCreateConflictAndCodeHttpTest` (echte gelijktijdigheid +
deterministisch via vastgehouden sleutel en `pg_blocking_pids`, alle vijf constraints), nieuw `SetupApiFlagOnlyPermissionHttpTest`, herschreven
`SetupApiDisabledTest`, `PermissionCoverageTest` (map ongewijzigd), `PermissionWrite/ReadEndpointsHttpTest` met vlag uit.
**Important technical constraint discovered (Bouwer):** zonder vlag geeft `POST /templates/{d}/revisions/{r}/bookmarks` **405** i.p.v. 404
(GET op hetzelfde pad bestaat nu altijd). Onbereikbaar, niets geschreven. **Ligt bij de mens** (aanbeveling: aanvaarden).
**Nog open (buiten scope NT-3):** mogelijke 500 bij gelijktijdige `createRevision` op dezelfde definitie en bij gelijktijdige mapping/filter/kritiekheid
op dezelfde DRAFT; 400 zonder code bij onleesbare body/ongeldige enum (Jackson), te lange changeReason bij opvolger, en veldfouten in
TemplateMaterialisation-/TemplateBookmark-/LinkBookmarkValueService. Verouderde vlagcommentaren in frontend-`api/*` en README/handleiding → NT-12.
**Bron:** hoofdsessie na verificatie (gerichte tests) / beslisdossier NT 2026-09-30

---

## 2026-09-30 — NT-4 uitgevoerd: takenlijst met `importDefinitionId` en `activeRevisionId`
**Vraag:** Is NT-4 afgerond en geverifieerd?
**Beslissing:** Ja (`bouwer-licht`, één reviewronde voor een onvolledige testfixture; tests door de hoofdsessie). `TaskQueryService.TaskRow` additief
`long importDefinitionId` + `Long activeRevisionId` (null zonder ACTIVE-revisie); batchquery `ImportDefinitionRevisionRepository.findActiveRevisionsByDefinitionIds`
(geen N+1). Frontend `TaskRow` in `api/types.ts` uitgebreid; geen UI-wijziging. Tests: `CatalogImportTaskHttpTest`, `PermissionReadEndpointsHttpTest`,
`PermissionCoverageTest`, `DeliveryUploadTest` — 33/33 groen; `tsc` schoon.
**Bron:** hoofdsessie na verificatie (gerichte tests)

---

## 2026-09-30 — NT-5 uitgevoerd: terminologiebasis (woordenlijst, `<Term>`, StatusBadge met domein)
**Vraag:** Is NT-5 afgerond en geverifieerd?
**Beslissing:** Ja (`bouwer-gemiddeld`, tests door de hoofdsessie). `Frontend/src/terms/` (`dictionary.ts`, `term(domein, code)` met veilige terugval,
`Term`, `WhatIsThis`, `TechnicalDetails`); `StatusBadge` met verplichte `domain` (Nederlands label, tooltip = uitleg + code; V7) op 19 plaatsen in 13
bestanden; `Field` optionele `help`. 16 domeinen (o.a. batchStatus, validationResult, mutationStatus/-Action, bundleStatus, revisionStatus, targetMode,
organisationType, taskTrigger, identityProfile, fieldReferenceKind, severity, criticality, creationPolicy, en extra issueCaseStatus en publicationRunStatus).
`docs/handleiding/begrippen.md` sectie "Woordenlijst voor de schermen" = redactionele bron (tabellen met marker `<!-- terms:<domein> -->`); sync-test
`terms.test.ts` bewaakt gelijkloop en weert ruwe codes in labels/uitleg. `SOURCE_ORGANISATION_TYPE_LABELS` vervangen door `<Term>`.
`tsc` schoon; vitest 30 bestanden, 396/396 groen.
**Invullingen van de Bouwer (ter nalezing door de mens):** woorden en uitleg voor waarden buiten het dossiervoorstel (o.a. 'Controlebibliotheek',
'Echte publicatie', severity, criticality, publicationRunStatus, `AUTOMATIC`/`THRESHOLD_EXCEEDED`); bij beslissingen kiest het domein zich op `decisionScope`;
`issueCasePolicy.ts ACTION_LABELS` ongewijzigd (acties, geen status). Overige ruwe teksten (werkvoorraadtegels, uploadresultaat, meldingen) → NT-11.
**Bron:** hoofdsessie na verificatie

---

## 2026-09-30 — NT-6 uitgevoerd: wizard "Nieuwe leverancier en taak" (zelf beschrijven)
**Vraag:** Is NT-6 afgerond en geverifieerd?
**Beslissing:** Ja (`bouwer-zwaar`, tests door de hoofdsessie). Frontend-only. Route `/setup/new` (`features/setup/wizard/*`, `api/setupCreate.ts`):
leverancier → startpunt (sjabloon = link naar Sjablonen) → beschrijving (OWN_DEFINITION + versie 1 DRAFT) → koppeling → taak (MANUAL) → samenvatting
met knop "Controleer en activeer de conceptversie" (opent revisiedetail in Inrichting). Elke stap persistent; hervatten via `?organisationId/definitionId/linkId`;
409 `*_IN_USE` met expliciet "Doorgaan met de bestaande" (enkel zelfde ouder); herlezen vóór herhaling na netwerkfout; 400-`code` bij het juiste veld.
Inrichting: knop (MANAGE, anders uitgeschakeld met reden) + "Verder inrichten". UploadPage: taak zonder actieve versie zichtbaar maar uitgeschakeld
"(nog niet klaar: versie niet geactiveerd)". Nieuwe woordenlijstdomeinen `revisionField`, `setupField` (ook in begrippen.md); ~40 nieuwe `codes.ts`-entries.
Wizard activeert nooit; drempels niet meegestuurd (serverdefaults), herkenningsversie 2 (A3). `tsc` schoon; vitest 31 bestanden, 424/424 groen.
**Invullingen van de Bouwer (ter nalezing):** scheidingsteken keuze ; , | of ander teken zonder default; aanhalingsteken " voorgeselecteerd; tekenset UTF-8
voorgeselecteerd; herkenning zonder default (expliciet kiezen); overname van bestaande definitie enkel OWN_DEFINITION; drempeldefaults in stap 3 hardgecodeerd
(informatief; stap 6 toont serverwaarden).
**Important technical constraints discovered (Bouwer):** (1) tab als scheidingsteken onmogelijk via setup-API (`requireText` trimt) — bekend sinds S1-F4;
(2) `createRevision` heeft geen guard/DB-sleutel tegen een tweede DRAFT op dezelfde definitie (enkel `successor` bewaakt "hoogstens één") — twee tabbladen
kunnen twee DRAFTs maken; **ligt bij de mens**; (3) geen get-by-id/-code voor organisatie/definitie/koppeling: de wizard bladert door lijsten.
**Bron:** hoofdsessie na verificatie

---

## 2026-09-30 — NT-7 uitgevoerd: sjabloonpad in de wizard, "Taak toevoegen", taken in de inrichtingsboom
**Vraag:** Is NT-7 afgerond en geverifieerd?
**Beslissing:** Ja (`bouwer-gemiddeld`, één reviewronde voor twee testfouten — componenten ongewijzigd; tests door de hoofdsessie). Frontend-only.
`TemplateStep` (sjablonen gefilterd op de bronorganisatie van stap 1, V4=a; enkel niet-DRAFT-versies; bij aankoopvereniging eerst leverancier kiezen/aanmaken)
→ bestaand `MaterialiseForm` (`defaultSupplierCode`) → hervatten op de taakstap (V3=a, geen taak bij materialiseren); ontbrekende verplichte
koppelingsbladwijzers zichtbaar. Sjablonenpagina: `AddTaskAfterMaterialise` ("Taak toevoegen", MANAGE). Inrichtingsboom: `LinkTasks` per koppeling (naam,
trigger via `<Term>`, "nog niet klaar: versie niet geactiveerd"), "Taak toevoegen"; één takenverzoek per koppeling. `codes.ts`: "Er is niets aangemaakt"
i.p.v. "niets gematerialiseerd"; MaterialiseForm-modi Nederlands. `tsc` schoon; vitest 32 bestanden, 433/433 groen.
**Invullingen van de Bouwer (ter nalezing):** leverancierskeuze bij aankoopvereniging altijd verplicht (ook als een bladwijzer ze invult); lokale vertaling van
waarschuwing `LINK_SEARCH_SUPPLIER_NOT_DERIVED`; MaterialiseForm blijft deels technisch (→ NT-11).
**Bron:** hoofdsessie na verificatie

---

## 2026-09-30 — NT-8 uitgevoerd: gereedheidscontrole `GET /import-links/{id}/readiness`
**Vraag:** Is NT-8 afgerond en geverifieerd?
**Beslissing:** Ja (`bouwer-zwaar`, tests door de hoofdsessie). Nieuw `ChainConfigurationChecks` = enige implementatie van de "mag dit starten?"-controles
(intake: `TASK_NOT_MANUAL`, `TASK_HAS_DELIVERY_CONFIGURATION`, `NO_ACTIVE_REVISION`, `CONFIG_PRICE_FIELD_MISSING`, `CONFIG_REQUIRED_BOOKMARK_MISSING`;
activatie: configuratie + DEFINITION-bookmarks); `DeliveryIntakeService`, ophaalrun en `SetupService.activateRevision`/`validateConfiguration` delegeren
met `throwFirst` — code, status, tekst en volgorde ongewijzigd. `ImportLinkReadinessService` (read-only) + endpoint (READ, niet achter de vlag, 404
`LINK_NOT_FOUND`). Antwoord `{linkId, ready, checks:[{code, status OK|PROBLEM|INFO, subject{type,id}, detail}]}`; nieuwe codes READY_*, `LINK_HAS_NO_TASK`,
INFO_LINK_INACTIVE, INFO_NO_DRAFT_REVISION, INFO_LIBRARY_NOT_VERIFIED (A5), terugval CONFIG_INVALID. Frontend: types + `getImportLinkReadiness` (geen UI).
Tests (12 klassen, o.a. nieuw `ImportLinkReadinessHttpTest` met pariteit upload/activatie): 126/126 groen; `tsc` schoon.
**Beperking (voorstel hoofdsessie: aanvaarden, nog te bevestigen door de mens):** binnen de configuratiefabrieken (`SourceStructureConfigFactory`,
`ImportMappingConfigFactory`, ook door de screening gebruikt) komt per concept hoogstens één `CONFIG_*`-code terug (de eerste); de overige controles verschijnen
wel allemaal tegelijk. "Alles verzamelen" binnen de fabrieken raakt de screening en is een aparte, additieve keuze.
**Invullingen van de Bouwer (ter nalezing):** `ready` = geen PROBLEM (ook één ophaaltaak maakt "niet klaar"); inactieve koppeling enkel INFO (pariteit);
geen valuta-, `TASK_RUN_IN_PROGRESS`- of `task.active`-controle; LINK-bookmarks al gemeld bij een concept terwijl invullen pas na activeren kan (UI NT-10 moet
dat uitleggen); `TemplateMaterialisationService` behoudt een eigen kopie van de fabrieksvalidatie.
**Bron:** hoofdsessie na verificatie (gerichte tests)

---

## 2026-09-30 — NT-9a: contract proefinlezing vastgelegd
**Vraag:** Hoe ziet de proefinlezing (V1-A) er precies uit?
**Beslissing:** Contract `docs/design/proefinlezing-design.md` (denker-gemiddeld; geen §6-vragen). Kern: `POST /revisions/{id}/trial-reads`
(multipart `file`, optioneel `linkId` enkel voor de vaste valuta), MANAGE, niet achter de vlag; DRAFT/ACTIVE/SUPERSEDED; altijd 200 bij voltooide proef
met `verdict` WOULD_BLOCK|NO_BLOCKER_FOUND (eerste blokkade in screeningvolgorde); tellers 1-op-1 met de screening (`null` = niet vastgesteld);
header met alle ontbrekende verplichte kolommen; eerste 20 voorbeeldrijen zoals geïnterpreteerd (prijs ruw + geparsed, nooit gecorrigeerd);
issuegroepen met dezelfde codes/classificatie; drempels (kritiek als ondergrens); `notEvaluated` (o.a. creatiedrempel) als INFO; niets bewaard,
geen inhoud in logs, pure functie. Refactor: gedeelde `RecordScreeningCore`, additieve sinkhook, zonder screeninggedrag te wijzigen.
Aannames A-1..A-8 (zie design §8), incl. U+FFFD-teller `linesWithReplacementCharacter` als tekenset-hint.
**Bron:** denker-gemiddeld (contractdossier NT-9a) / docs/design/proefinlezing-design.md

---

## 2026-09-30 — NT-9 uitgevoerd: proefinlezing `POST /revisions/{id}/trial-reads`
**Vraag:** Is NT-9 afgerond en geverifieerd?
**Beslissing:** Ja (`bouwer-zwaar`, build en tests door de hoofdsessie). Refactor zonder gedragswijziging: `CsvRecordStreamer.Sink` additieve
default-hooks `header(...)` en `physicalLine(...)`; nieuw `support.RecordScreeningCore` (beslisboom + tellerregels, `StagingSink` = adapter);
`DeliveryScreeningService.loadConfiguration`; `IssueAggregationService.isBulk/share/effectiveBulkSharePercent` publiek static; `ThresholdEvaluator.leading`.
Nieuw `TrialReadService` + `CatalogImportTrialReadController` (MANAGE, niet achter de vlag), properties `catalogimport.trial-read.*`, één INFO-logregel
zonder inhoud. Frontend: types + `api/trialReads.ts` (geen UI). Tests (30 klassen incl. nieuw `TrialReadHttpTest` met pariteit tegen echte upload,
`RecordScreeningCoreTest`, en de volledige screeningregressie): 329/329 groen; `tsc` schoon.
**Afwijkingen van het contract (voorstel hoofdsessie: aanvaarden; nog te bevestigen door de mens):** D-1 extra sinkhook `physicalLine` voor een exacte
U+FFFD-teller; D-2 `duplicateIdentityCount` = gemeten 0 waar de screening op het drempel-/`SOURCE_NO_DATA_RECORDS`-blokkeerpad null laat
(pariteitstest aanvaardt null of 0); D-3 bij een leesblokkade blijven `issueOccurrencesBySeverity` en `issueGroups` gevuld (zoals de screening
`warning_count`/groepen vastlegt), enkel de recordtellers zijn null.
**Invullingen van de Bouwer (ter nalezing):** `SOURCE_FILE_EMPTY` = stage READING; `configProblems` ook voor CONFIG_* uit de leesfase; voorbeeldcap per
issuegroep (code+veld); bij bereikte identiteitsgrens teller altijd null; eigen Engelse blokkeerteksten op drempels; percentages `stripTrailingZeros`.
**Mogelijke ontdekking (nog niet teruggeschreven):** de screening laat `duplicate_identity_count` null op het blokkeerpad van drempels en "geen datarecords".
**Bron:** hoofdsessie na verificatie (build + gerichte tests) / docs/design/proefinlezing-design.md

---

## 2026-09-30 — NT-10 uitgevoerd: scherm "Controleren" (blok 2)
**Vraag:** Is NT-10 afgerond en geverifieerd?
**Beslissing:** Ja (`bouwer-zwaar`, tests door de hoofdsessie). Frontend-only. Route `/setup/links/:linkId/check` (`features/setup/check/*`): checklist uit
readiness (per regel status, Nederlandse zin, "Wat moet ik doen?" met link; NT-8-beperkingen uitgelegd; techniek enkel in `TechnicalDetails`), proefinlezing
(MANAGE; banner "Er wordt niets opgeslagen of gepubliceerd"; oordeel, tellers met "—" voor null, kolommen, "Zo lezen we uw bestand", foutgroepen, grenzen,
"Niet gecontroleerd in een proef", tekenset-hint; enkel paginatoestand), activeren (bestaande actie; V6-waarschuwing zolang de laatste proef met deze versie
op deze pagina niet geslaagd is; checklist herladen), en "Wat nu?". Links vanuit wizard-samenvatting, inrichtingsboom en UploadPage. 13 nieuwe
woordenlijstdomeinen (o.a. `issueCode` met 88 codes) identiek in begrippen.md; `codes.ts` + `FILE_REQUIRED`, `LINK_NOT_OF_REVISION_DEFINITION`.
`tsc` schoon; vitest 33 bestanden, 487/487 groen.
**Nog technisch/ruw (→ NT-11):** verplichte R-CASE-03-tekst in de activeringsdialoog ("(status REJECTED)", "importdefinitie"); `ErrorBanner` technische regel;
`LinkBookmarkValuesSection` ("Bookmarkwaarden"). **Ter nalezing door de mens:** de ~88 `issueCode`-teksten en overige nieuwe woorden in begrippen.md.
**Hiermee zijn beide blokken van het gebruikersverhaal functioneel af** (aanmaken: NT-3/4/6/7; controleren: NT-8/9/10). Rest: NT-11a/b/c, NT-12.
**Bron:** hoofdsessie na verificatie

---

## 2026-09-30 — NT-11a uitgevoerd: terminologie werkvoorraad, batch, upload, foutmeldingen
**Vraag:** Is NT-11a afgerond en geverifieerd?
**Beslissing:** Ja (`bouwer-gemiddeld`, één reviewronde: de guard-detector plakte tekstknopen aaneen en miste losse enumwoorden — detector hersteld,
zelftest ongewijzigd; tests door de hoofdsessie). Werkvoorraad, batchdetail (incl. foutgroepen, problemen, levering), `BatchActions`, gedeelde
`MutationList`, uploadresultaat via `Term`/`StatusBadge`/`IssueCodeTerm`/`CounterLabel`; `ErrorBanner` technische regel in `TechnicalDetails` (V7);
`codes.ts` zonder ruwe codes in uitleg, Nederlandse familie-terugvaltitels. 7 nieuwe domeinen (`batchCounter`, `mutationStatusReason`, `changePart`,
`discountCodeState`, `referenceType`, `issueIncidentKind`, `issueHandlingStatus`) + 2 `issueCode`s, ook in begrippen.md. Guard `noRawCodes.test.tsx`.
**UI-contractwijziging:** het intypwoord voor "Aanvaarden als nulmeting" is `NULMETING` (was `BASELINE`); handleiding (`csv-importeren.md`, `handleiding/README.md`)
bijgewerkt. Enkel frontend (geen backendcontrole op het woord).
`tsc` schoon; vitest 34 bestanden, 517/517 groen. Playwright-specs `e2e/tests/workqueue.spec.ts`/`bundles.spec.ts` aangepast maar niet gedraaid (mens).
**Ter beslissing door de mens:** woorden Batch/Mutatie/Bundel behouden of hernoemen; "bookmark" vs "invulpunt"; statusreden-filter als keuzelijst (NT-11b).
**Bron:** hoofdsessie na verificatie

---

## 2026-09-30 — NT-11b uitgevoerd: terminologie bundelschermen
**Vraag:** Is NT-11b afgerond en geverifieerd?
**Beslissing:** Ja (`bouwer-gemiddeld`, één reviewronde: twee testqueries botsten met de nieuwe keuzelijst, en codes bij uitgeschakelde knoppen waren niet meer
opvraagbaar — nu optioneel `Gate.code` in de tooltip "(technische code: X)", zichtbare tekst zonder code; tests door de hoofdsessie). Alle bundelschermen
Nederlands via de woordenlijst; `WhatIsThis` op lijst, tabbladen en dialogen; statusreden-filter in `MutationList` = keuzelijst uit `mutationStatusReason`;
6 nieuwe domeinen (`decisionKind`, `decisionScope`, `selectionFilterField`, `freezeCheck`, `contractStatus`, `bundleCounter`) ook in begrippen.md;
`noRawCodes`-guard uitgebreid. Zichtbare labelwijzigingen (enkel frontend): "Proefpublicatie starten" (was "Simulatierun starten"), "voorcontrole",
"Vingerafdruk van de bundel", teller "Goedgekeurd" (was "Gereed"). `tsc` schoon; vitest 34 bestanden, 544/544 groen. `e2e/tests/bundles.spec.ts` aangepast, niet gedraaid.
**Correctie op NT-5:** in het beslissingsregister kiest het statusdomein zich op het soort beslissing (FREEZE/CANCEL → bundleStatus, rest → mutationStatus),
niet op `decisionScope` (`AUTO_APPROVE_PLANNED` heeft scope BUNDLE maar mutatiestatussen).
**Bron:** hoofdsessie na verificatie

---

## 2026-10-01 — NT-11c uitgevoerd: terminologie behandelgevallen, inrichting, sjablonen
**Vraag:** Is NT-11c afgerond en geverifieerd?
**Beslissing:** Ja (`bouwer-gemiddeld`, één reviewronde voor een ontbrekende testimport; tests door de hoofdsessie). Behandelgevallen (lijst, detail, acties;
`issueCasePolicy` Nederlandse redenen + `Gate.code`), inrichting (`RevisionDetailSection` herschreven, `RevisionEditForm` zonder `(technicalName)`,
`fieldReferenceKind` als keuzelijst, R-CASE-03-waarschuwing in gewone taal met dezelfde betekenis, `FlagOffNotice`), sjablonen (lijst, detail, `MaterialiseForm`,
`LinkBookmarkValuesSection`). 19 nieuwe domeinen + 13 `revisionField`-sleutels, ook in begrippen.md; `termLabel`, `terms/gateTitle.ts`; guard uitgebreid.
Filter "Soort vaststelling" = keuzelijst uit `issueCode`; kolom "Signatuur" naar technische details. `tsc` schoon; vitest 34 bestanden, 607/607 groen.
**Ter beslissing door de mens:** "bookmark" vs "invulpunt" (beide zichtbaar, niet centraal schakelbaar — inventaris in het Bouwer-rapport); "Revisie/Definitie/
Bronorganisatie" in boom en materialiseren vs woordenlijst "Versie/Beschrijving van het bestand/Leverancier of aankoopvereniging". Wizard/check niet door de guard gedekt.
**Bron:** hoofdsessie na verificatie

---

## 2026-10-01 — Woordkeuzes en openstaande punten NT-spoor (mens, meerkeuze)
**Vraag:** Open punten uit NT-3, NT-6, NT-8, NT-9, NT-11a-c.
**Beslissing (mens):**
- **"Invulpunt"** vervangt overal "bookmark" in gebruikerstekst (UI, `codes.ts`, woordenlijst, begrippen.md, handleiding). Technische codes/API blijven ongewijzigd.
- **Gelijktrekken met de woordenlijst:** "Revisie" → "Versie", "Definitie" → "Beschrijving van het bestand", "Bronorganisatie" → "Leverancier of
  aankoopvereniging" in alle gebruikerstekst (boom, materialiseren, wizard, check, foutmeldingen).
- **Batch, Mutatie, Bundel blijven** (met "Wat betekent dit?").
- **405 i.p.v. 404** voor `POST /templates/{d}/revisions/{r}/bookmarks` zonder vlag: aanvaard.
- **Hoogstens één concept per definitie, door de server afgedwongen** (niet enkel in `successor`): te bouwen.
- **Afwijkingen proefinlezing D-1, D-2, D-3:** aanvaard.
- **NIET aanvaard: "één configuratiefout per keer".** De mens wil dat checklist en proefinlezing **alle** fouten in de bestandsbeschrijving tegelijk tonen.
  Dit raakt de configuratiefabrieken die ook de screening gebruiken → eerst een denker-zwaar-ontwerp (gedrag van screening/activatie moet gelijk blijven:
  zelfde eerste code, status, tekst).
**Volgorde:** NT-11d (woordkeuzes doorvoeren) → NT-13 (max. één concept, server) → NT-14a (ontwerp alle configfouten, denker-zwaar) → NT-14 → NT-12 (documentatie).
**Bron:** mens

---

## 2026-10-01 — NT-11d uitgevoerd: woordkeuzes doorgevoerd
**Vraag:** Is NT-11d afgerond en geverifieerd?
**Beslissing:** Ja (`bouwer-gemiddeld`, tests door de hoofdsessie). Alle gebruikerstekst in Frontend en begrippen.md: "bookmark" → "invulpunt" (koppen/labels
uit `terms/wording.ts`), "Revisie" → "Versie" ("Versie n"), "Definitie/importdefinitie" → "Beschrijving van het bestand"/"beschrijving", "Bronorganisatie" →
"Leverancier of aankoopvereniging"; "Eigen definitie" → "Eigen beschrijving". Codes, API, routes, testids ongewijzigd. Guard bewaakt de oude woorden ook
(`noRawCodes`, nieuw `oldWording.ts` in wizard- en check-tests). `tsc` schoon; vitest 34 bestanden, 607/607 groen.
**Nog te doen in NT-12:** handleiding (`README.md`, `standaardflows.md`, `csv-importeren.md`) bevat nog de oude woorden.
**Bron:** hoofdsessie na verificatie

---

## 2026-10-01 — NT-13 uitgevoerd: hoogstens één concept per beschrijving (server + DB)
**Vraag:** Is NT-13 afgerond en geverifieerd?
**Beslissing:** Ja (`bouwer-gemiddeld`, één reviewronde voor een onvolledige test-INSERT; build en tests door de hoofdsessie). Changeset
`016-revision-draft-marker.sql`: kolom `import_definition_revision.draft_marker` + `uk_import_definition_revision_draft (import_definition_id, draft_marker)`
volgens het bestaande `active_marker`-patroon (dialectonafhankelijk), backfill van bestaande DRAFTs, precondition HALT bij al bestaande dubbele DRAFTs
(niets stil samengevoegd/verwijderd), rollback. Entiteit houdt de marker bij. `SetupService.createRevision` en `RevisionSuccessorService`: zelfde 409
`REVISION_DRAFT_ALREADY_EXISTS` vooraf én bij een race (ook een botsing op `uk_import_definition_revision_number` wordt zo vertaald; voorheen 500).
Wizard toont bij die 409 de bestaande versie als kandidaat. Tests: nieuw `SetupRevisionSingleDraftHttpTest` + regressie, 11 klassen, 129/129 groen;
frontend `tsc` schoon, vitest 607/607.
**Invullingen van de Bouwer:** marker-kolom i.p.v. partiële index; 409 vóór validatie in `createRevision`; raceboodschap zonder nummer; geen check marker↔status
(zoals `active_marker`). Precondition-syntax nog niet tegen een DB mét dubbele DRAFTs beproefd.
**Bron:** hoofdsessie na verificatie (build + gerichte tests)

---

## 2026-10-01 — NT-14a: ontwerp "alle configuratiefouten tegelijk"
**Vraag:** Hoe tonen checklist en proefinlezing alle fouten in de beschrijving tegelijk zonder het gedrag van screening/activatie/setup te wijzigen?
**Beslissing:** Ontwerp `docs/design/configfouten-alle-tegelijk-design.md` (denker-zwaar; geen §6-vragen). Collector met takken in de bestaande fabrieken
(FIRST = gooit hetzelfde object opnieuw, pariteit bij constructie; ALL = verzamelt), golden table vóór de refactor als pariteitsbewijs. "Alle fouten" =
alle **onafhankelijk** te beoordelen fouten; afhankelijke controles worden expliciet als overgeslagen gemeld (Important business rule discovered).
Aannames A1-A6 (design §7). Correctie op NT-8: `TemplateMaterialisationService` heeft géén eigen kopie van de regels, enkel een eigen try/catch rond
dezelfde fabrieken. Bouwvolgorde NT-14-0 → 14-1 → 14-2 → 14-3 → 14-4 → NT-12.
**Bron:** denker-zwaar (dossier NT-14a) / mens (2026-10-01)

---

## 2026-10-01 — NT-14-0 uitgevoerd: golden table `ConfigFactoryParityTest`
**Vraag:** Is de pariteitsbasis vóór de refactor vastgelegd?
**Beslissing:** Ja (`bouwer-gemiddeld`, tests door de hoofdsessie). Enkel test: `Web/src/test/java/.../service/support/ConfigFactoryParityTest.java`
(dynamische tests, ~138 werpende + 19 niet-werpende rijen, letterlijke code/fieldName/sourceValue/expectedValue/message; meta-test dat elke gedeclareerde
CONFIG-code in een rij voorkomt en omgekeerd; unieke rij-id's). Groen op de huidige code: 158/158. Onbereikbaar en bewust niet in de tabel: record-constructor
`SourceStructureConfig`, `ImportValueRules.DecimalFormat`, `MappingSettings.character` (ongebruikt), runtime-transformcodes (geen CONFIG_).
**Integriteit:** SHA-256 van het bestand bij vastleggen = `4B001553B8D30E33230E8CEF80269AEE2778E8907C55C785F80D33FD73F6FE50`; de hoofdsessie controleert na
NT-14-1 dat het ongewijzigd is.
**Bron:** hoofdsessie na verificatie

---

## 2026-10-01 — NT-14-1 uitgevoerd: collector met takken in beide configuratiefabrieken
**Vraag:** Is NT-14-1 afgerond en geverifieerd?
**Beslissing:** Ja (`bouwer-zwaar`, één reviewronde; build en tests door de hoofdsessie). Nieuw in `service.support`: `ConfigProblemCollector` (FIRST gooit
hetzelfde object opnieuw; ALL verzamelt), `ConfigFinding`, `ConfigCheckReport`, `StructureFacts` (incl. `identityFieldsValid`,
`canonicalisationVersionSupported`). `SourceStructureConfigFactory.collect/collectInto` en `ImportMappingConfigFactory.collect/collectInto`; alle `from(...)`
ongewijzigd naar buiten. Golden table ongewijzigd (SHA-256 `4B0015…FE50` gecontroleerd) en groen. Nieuw `ConfigFactoryCollectAllTest` (invariant over 52
mutatoren, ALL-specifieke gevallen, 6 succespad-pariteitstests op de opgebouwde config). 25 klassen, 603/603 groen.
**Invullingen van de Bouwer (aanvaard door de hoofdsessie, ter info aan de mens):** M3j hangt af van M3e (anders crasht ALL op een ongeldige schaal — Important
technical constraint: `ImportValueRules.DecimalFormat` gooit IAE buiten 0..12); S15 per kolom; M5/M6 in ALL enkel over mappings zonder bevinding (telling in
M6c-melding kan te laag zijn, nooit een valse fout); `revisionField` null voor rij- en koppelingsbevindingen.
**Bijvangst — regressietest `DeliveryScreeningFlowTest` (volgorde R1/R4):** oorzaak volgens de Bouwer: `MutationDao.insertContentMutations` doet `INSERT … SELECT`
zonder `ORDER BY`; de id-volgorde hangt van het PostgreSQL-plan af (data-afhankelijk). Fix: `order by stage.row_number` (id-volgorde = bronregelvolgorde;
geen kolom-, contract- of idempotentiewijziging). **Niet hard bewezen** dat NT-14-1 niet de oorzaak was (geen git in de shell om de oude fabrieken terug te zetten);
bewijs: succespad-pariteitstests + de test slaagde eerder in een grotere run met meer data. Andere `INSERT … SELECT` zonder ORDER BY (`SourceStateDao`,
`PriceObservationDao`) niet aangeraakt.
**Bron:** hoofdsessie na verificatie (build + gerichte tests)

---

## 2026-10-01 — NT-14-2 uitgevoerd: checklist toont alle configuratiefouten van een concept
**Vraag:** Is NT-14-2 afgerond en geverifieerd?
**Beslissing:** Ja (`bouwer-gemiddeld`, tests door de hoofdsessie). `ChainConfigurationChecks.configurationReport(revision, linkCurrency)` en
`activationProblemsAll(draft, linkCurrency)` (records `ReportedProblem`, `ActivationReport`; zelfde detailformaat als `configurationProblem`); FIRST-methodes
ongewijzigd. `ImportLinkReadinessService` concepttak: één PROBLEM per bevinding, additief `fieldName`/`revisionField` op `ReadinessCheck`, INFO
`INFO_CONFIG_CHECKS_SKIPPED` bij overgeslagen controles (telt niet voor `ready`); actieve-versietak ongewijzigd. Frontend: types. Tests: 11 klassen, 342/342
groen (o.a. eerste configregel = 400 van activate; golden table + collect-all groen); `tsc` schoon.
**Invulling (voorstel hoofdsessie: aanvaarden, ter bevestiging aan de mens):** de checklist geeft de standaardvaluta van de koppeling mee (zoals de screening);
activatie niet. Enkel bij een ongeldige koppelingsvaluta verschijnt `CONFIG_LINK_CURRENCY_INVALID` in de checklist en niet bij activeren.
**Bron:** hoofdsessie na verificatie

---

## 2026-10-01 — NT-14-3 uitgevoerd: proefinlezing toont alle configuratiefouten
**Vraag:** Is NT-14-3 afgerond en geverifieerd?
**Beslissing:** Ja (`bouwer-gemiddeld`, één reviewronde: testfixtures met een leeg prijsveld werden terecht al bij de upload-intake geweigerd (409) — vervangen door
screening-blokkerende fouten; productiecode ongewijzigd; tests door de hoofdsessie). `TrialReadService`: bij een configfout vóór het lezen een tweede aanroep
`configurationReport` (ALL) in dezelfde leestransactie; `configProblems` = volledige lijst (additief `revisionField`), additief `configChecksSkippedBecause`;
verdict blijft de eerste (= echte screening), guard + WARN bij afwijking. Leesfase-CONFIG_* blijft één item. `proefinlezing-design.md` §2 en A-7 bijgewerkt;
frontendtypes uitgebreid. Tests: 7 klassen, 306/306 groen (pariteit `[0]` = verdict = `blocked_code` van een echte upload); `tsc` schoon.
**Bron:** hoofdsessie na verificatie

---

## 2026-10-01 — NT-14-4 uitgevoerd: scherm toont alle configuratiefouten
**Vraag:** Is NT-14-4 afgerond en geverifieerd?
**Beslissing:** Ja (`bouwer-gemiddeld`, tests door de hoofdsessie). Checklist en proefinlezing tonen elke configbevinding met het betrokken veld ("Veld:",
"Doelveld:", "Kolom:"); de oude "één fout per keer"-zin is weg; bij overgeslagen controles de zin "Sommige controles konden nog niet uitgevoerd worden …"
met de Nederlandse labels van de grondoorzaken (codes enkel in technische details). Nieuw woord `readinessCheck.INFO_CONFIG_CHECKS_SKIPPED` ook in
begrippen.md. `tsc` schoon; vitest 34 bestanden, 610/610 groen.
**Opvolging door de hoofdsessie:** de checklist haalt de overgeslagen codes nu met een regex uit de Engelse `detail`-tekst — broos. Kleine opvolgstory NT-14-5:
gestructureerd additief veld `skippedBecause` op `ReadinessCheck` en de frontend gebruikt dat.
**Bron:** hoofdsessie na verificatie

---

## 2026-10-01 — NT-14-5, NT-12 en eindcontrole NT-spoor
**Vraag:** Zijn de laatste stappen van het NT-spoor afgerond en is het geheel geverifieerd?
**Beslissing:** Ja.
- **NT-14-5** (`bouwer-licht`, één reviewronde voor een foute testquery): `ReadinessCheck` additief `List<String> skippedBecause` (gevuld enkel bij
  `INFO_CONFIG_CHECKS_SKIPPED`; `detail` ongewijzigd); frontend gebruikt het veld, terugval op tekstparsing enkel bij een oudere server.
- **NT-12** (`bouwer-gemiddeld`): handleiding (`README.md`, `standaardflows.md`, `csv-importeren.md`), root-`README.md` en `Frontend/README.md` bijgewerkt
  (wizard, Controleren, nieuwe woorden, NULMETING, "Proefpublicatie starten", setup-vlag, nieuwe endpoints, 409's); `openstaande-externe-punten.md` klopt nog.
  Menuvolgorde in de handleiding blijft "nog te verifiëren"; codecommentaar in `bundlePolicy.ts`/`BundlePublicationTab.tsx` noemt nog "Simulatierun".
- **Volledige backendronde** (`run-full-tests.ps1`, 130 klassen, 1757 tests): 4 tests faalden — 2 door NT-13 (tests maakten nog meerdere DRAFTs per definitie:
  `ImportControlSchemaTest`, `SetupTemplateLinkActorHttpTest`, aangepast aan de nieuwe regel + extra assertie dat een tweede DRAFT geweigerd wordt) en 2 door
  paginering in het gedeelde schema (`CatalogImportSetupQueryHttpTest`, codes met prefix `000` + `size=200`). Geen productiefout. Na correctie: die klassen
  + `SetupRevisionSingleDraftHttpTest` 45/45 groen. (Geen tweede volledige ronde gedraaid.)
- **Frontend**: `tsc` schoon; vitest 34 bestanden, 610/610 groen.
**Nog open bij de mens:** bevestiging dat de checklist de koppelingsvaluta meeneemt en activatie niet (NT-14-2); nalezen van de door subagents geschreven
woorden in `begrippen.md`; Playwright-specs (`e2e/tests/*.spec.ts`) draaien; eventueel het demofilmpje bijwerken (BASELINE → NULMETING, nieuwe woorden).
**Bron:** hoofdsessie na verificatie
**Aanvulling (mens, 2026-10-01, "Ja doen"):** de checklist neemt de standaardvaluta van de koppeling mee en activatie niet — **aanvaard** (NT-14-2-invulling).
Het demofilmpje wordt bijgewerkt naar de nieuwe woorden.

---

## 2026-10-01 — Analyse-opvolging stap 1: elke module apart testen
**Vraag:** Alle 136 testbestanden staan in `Web`; Domain, Dao en Service hebben geen eigen tests. Moet dat zo blijven?
**Beslissing:** Nee. Elke module (Domain, Dao, Service, Web) wordt apart getest met een eigen gericht commando (`mvn -pl <Module> -am test`).
`run-full-tests.ps1` mag falende tests niet meer verbergen. De concrete testaanpak per module (frameworks, eerste testklassen, wat uit `Web` verhuist)
werkt een denker-subagent uit; die invulling wordt hieronder apart gelogd vóór een bouwer start.
**Bron:** mens ("elke blok apart aftesten", "ja, start met stap 1, 2 en 3") / analyse ImportService 2026-10-01

## 2026-10-01 — Analyse-opvolging stap 2: frontend-dataflow en dialoog
**Vraag:** Hoe lossen we oude data na een sleutelwissel, dubbel versturen en de modale laag op?
**Beslissing:** (a) `useQuery` zet `data` terug op `null` bij een sleutelwissel (fix in de hook, niet per scherm). (b) `useAction.execute` krijgt een
ref-guard: een tweede aanroep terwijl een actie loopt doet niets. (c) `ConfirmDialog` gebruikt `<dialog>.showModal()` (focus trap, Escape, focus terug),
een scrollbare body, en een z-index-schaal naar MUI (appBar 1100, drawer 1200, modal 1300). (d) `Pager` en `ConfirmDialog` gebruiken `useId()` in plaats
van vaste ids. Met tests per onderdeel.
**Bron:** denker-zwaar (frontendanalyse) + mens (akkoord stap 2) / analyse ImportService 2026-10-01

## 2026-10-01 — Analyse-opvolging stap 3: foutafhandeling en statusguard publicatierun
**Vraag:** Hoe stoppen we interne fouten die als 400 lekken, en de race tussen afbreken en afronden van een publicatierun?
**Beslissing:** (a) `ApiExceptionHandler`: `IllegalStateException` wordt 500 met een vaste boodschap zonder interne tekst (IAE blijft 400); de body is
null-veilig; eigen codes voor `MaxUploadSizeExceededException` en `HttpMessageNotReadableException`. Bewuste contractwijziging: ISE gaf 400, nu 500.
(b) `completeRun`/`failRun` (en `PublicationRun.recordSimulated/recordFailed`) herlezen de run onder slot en werken alleen vanuit PREPARING, naar het
precedent van `FetchRunService.close`; een afgebroken run blijft afgebroken. Met tests.
**Bron:** denker-zwaar (backendanalyse) + mens (akkoord stap 3) / analyse ImportService 2026-10-01

## 2026-10-01 — Stap 1 uitgewerkt: testaanpak per module (S1-a t/m S1-f)
**Vraag:** Hoe wordt elke module concreet apart getest?
**Beslissing:**
- Domain: JUnit 5 + AssertJ (test-scope), geen Spring. Dao: `spring-boot-starter-test` + postgresql + liquibase-core (test-scope), lokale PostgreSQL
  (geen Testcontainers, geen H2), `@DataJpaTest` + `@AutoConfigureTestDatabase(replace = NONE)` + expliciet `ddl-auto: validate`; transactioneel terugrollen,
  codes met UUID-suffix. Service: `spring-boot-starter-test` (Mockito), zonder Spring-context. Web blijft.
- Geen test-jar/testFixtures. Changelogs blijven in `Web/src/main/resources`; Dao leest `db/changelog/**` als test-resource via `<testResources>` (geen
  Maven-afhankelijkheid Dao→Web). Verhuizen naar Dao blijft een open vraag voor de mens (V1), later als aparte story.
- `run-full-tests.ps1`: `maven.test.failure.ignore` eruit, `-fae`, parameter `-Module`, exit 1 bij falen.
- Volgorde (één commit per story): S1-a script, S1-b Domain-tests, S1-c Dao-infra + tests, S1-d Service-tests, S1-e 24 pure unit-tests `git mv` Web→Service,
  S1-f `ApiExceptionHandlerContractTest`. Daarna stap 2 (frontend) en stap 3 (backend).
**Bron:** denker-zwaar / docs/decisions.md 2026-10-01 stap 1

## 2026-10-01 — Stap 1 (behalve S1-c), stap 2 en stap 3 uitgevoerd
**Vraag:** Zijn de stappen afgerond en geverifieerd?
**Beslissing:** Ja, op branch `verbeteringen-stap-1-3`, één commit per story (S1-a, S1-b, S1-d, S1-e, S1-f, stap 2, 3a, 3b). Door de hoofdsessie gericht
herhaald: Domain 52/52, Service 553/553, `ApiExceptionHandlerContractTest` 17/17, frontend `tsc -b` schoon + geraakte vitest 97/97 en `codes.test.ts` 19/19.
Aanvaarde invullingen: (1) "afgebroken run" = `FAILED` + `FAILURE_MANUALLY_ABORTED` (geen nieuwe status ABORTED); (2) `codes.ts` kent `UPLOAD_TOO_LARGE`
en `REQUEST_BODY_UNREADABLE`; (3) een niet-vastgelegd artefact wordt opgeruimd met `artifacts.deleteQuietly`; (4) `abortRun` neemt hetzelfde bundelslot.
**Nog open:** S1-c (Dao-tests) en alle `@SpringBootTest`/HTTP-tests — geen werkende lokale DB-login (`catalog_import` faalt). Te draaien door de mens:
`PublicationRunHttpTest`, `PublicationRunSimulationTest`, plus een volledige ronde `run-full-tests.ps1`. V1 (changelogs naar Dao verhuizen) ligt bij de mens.
**Bron:** hoofdsessie na verificatie

## 2026-10-01 — V1: Liquibase-changelogs blijven in Web
**Vraag:** Moeten de changelogs naar `Dao/src/main/resources` verhuizen?
**Beslissing:** Nee. Ze blijven in `Web/src/main/resources/db/changelog`; Dao leest ze als test-resource (`<testResources>`), back-upscripts ongewijzigd.
**Bron:** mens ("changelogs laten we in Web")

## 2026-10-01 — Stap 4 en 5 gestart
**Vraag:** Krijgt hervatten een exclusieve verwerkingsclaim per batch, en wordt accept-baseline herontworpen?
**Beslissing:** Ja, beide (mens: "ga verder met stap 4 + 5"). Stap 4: exclusieve verwerkingsclaim per batch voor screenen en hervatten, 409
`BATCH_BEING_PROCESSED` zolang een worker actief is, met concurrency-test. Stap 5: accept-baseline mag geen bronstaat/prijsobservaties achterlaten van een
batch die niet aanvaard wordt, en `addOneBatch` neemt het batchslot (R-BAS-02). De technische invulling (slotmechanisme, eventueel API-veld voor
"loopt nog" vs "onderbroken", volgorde van schrijven) werkt een denker-zwaar uit; die wordt apart gelogd vóór een bouwer start.
**Bron:** mens / analyse ImportService 2026-10-01

## 2026-10-01 — Stap 4 en 5 uitgewerkt: verwerkingsclaim per batch en atomaire accept-baseline
**Vraag:** Welk slotmechanisme voor screenen/hervatten, hoe ziet de UI "loopt nog", en hoe wordt accept-baseline veilig?
**Beslissing:**
- **Stap 4 — claimkolom met lease en fencing** (geen advisory lock). Changeset `017-batch-processing-claim.sql` op `import_batch`:
  `processing_claim_token uuid`, `processing_claimed_at`, `processing_heartbeat_at` (timestamptz), `processing_claimed_by varchar(100)`
  (`<instanceId>/<bootId>`), CHECK `processing_claim_token is null or open_marker = true`. Claim levend = token gezet, heartbeat binnen de lease, en niet
  van een vorige boot van dezelfde instantie. Claim nemen in `start()`/`resume()` onder `findByIdForUpdate`; levend → 409 `BATCH_BEING_PROCESSED`.
  Elke schrijvende transactie na de start is gefenced (`update … set processing_heartbeat_at … where id and token`; 0 rijen → `ClaimLostException` →
  rollback/stop, geen `fail()`-opruiming). Vrijgeven in `complete()`/`block()`/`fail()` in dezelfde transactie. Opstartherstel met compare-and-set op de
  token; een levende vreemde claim blijft ongemoeid. Config: `catalogimport.screening.claim-lease` default **PT60M** (mens), ongeldig/≤0 stopt de opstart;
  `catalogimport.instance-id` default hostnaam, **verplicht uniek per instantie**.
- **"Loopt nog":** additief veld `boolean processingActive` op `BatchDetail`; frontend: true → knop uit + uitleg, false → zoals nu, undefined (oudere
  server) → neutrale tekst, knop aan.
- **Stap 5 — accept-baseline in één transactie** (mens: alles-of-niets aanvaard, niet langer hervatbaar per chunk). Volgorde: koppeling NOWAIT → batch
  NOWAIT → requireScreened + bundellidmaatschap + stale-check → chunklus → skipOpenContentMutations → BASELINE_ACCEPTED. Geen Liquibase.
  Nieuwe code `BASELINE_ACCEPTANCE_IN_PROGRESS`. `addOneBatch`: batch-ids oplopend, `findByIdForUpdateNowait`, herlezen; slot bezet → 409
  `BATCH_BEING_PROCESSED`. Meteen 409 bij een bezet slot, niet wachten (mens akkoord). Vertaling van lock-fouten buiten `TransactionTemplate.execute`.
- **S5-d:** de stale-check geldt ook voor UNCHANGED-regels (mens: ook weigeren).
- **Globale slotvolgorde:** bundel → run → koppeling(en, oplopende id) → batch(es, oplopende id); een taakslot nooit terwijl een van deze vastgehouden wordt.
- **Stories:** S4-a schema+domein, S4-b claims+fencing in screening, S4-c opstartherstel, S4-d `processingActive`, S4-e frontend, S4-f concurrency-test,
  S5-a NOWAIT-repositories, S5-b addOneBatch, S5-c atomaire baseline, S5-d stale-check UNCHANGED, S5-e frontend/handleiding. Eén commit per story.
- Prestatietest van de atomaire aanvaarding op ~1M regels doet de mens op PostgreSQL. Geen gebruikerskolom op de claim (V2 2026-09-23 blijft).
**Bron:** denker-zwaar + mens (lease 60 min, alles-of-niets, S5-d ja, DB-omgeving door de mens) / docs/design/fase2-screening-design.md §9, §18

## 2026-10-02 — Stap 4 uitgevoerd (S4-a t/m S4-f), S1-c afgerond
**Vraag:** Zijn de verwerkingsclaim en de Dao-tests afgerond en geverifieerd?
**Beslissing:** Ja, op branch `verbeteringen-stap-1-3`. S1-c: Dao 18 tests (`@DataJpaTest` tegen PostgreSQL). Stap 3 nu ook tegen de DB bewezen
(`PublicationRunHttpTest`, `PublicationRunSimulationTest`, K-4c-tests, `BatchBaselineHttpTest`: 78/78). Stap 4: Domain 68, Dao 30, Service 596,
gerichte Web-ronde 252 + `BatchProcessingClaimConcurrencyTest` 3/3 (vijf runs) door de hoofdsessie herhaald.
Aanvaarde invullingen: (1) de claim komt ook vrij bij een technische onderbreking zonder eindstatus (hervatten blijft meteen mogelijk);
(2) `start()` neemt het schrijfslot (een gelijktijdige tweede `screen()` krijgt `BATCH_NOT_SCREENABLE`); (3) `RecoveryReport.resumableBatchIds` bevat geen
batches met een levende vreemde claim meer; (4) env-variabele voor de instance-id is `CATALOGIMPORT_INSTANCEID`; (5) instance-id max 63 tekens, zonder `/`.
**Omgeving:** lokale tests vereisen `C:\tmp\catalogimport-local-source` en `C:\tmp\catalogimport-archive`, en rol/database `catalog_import` in de
PostgreSQL-container `prodis-postgresql-1`.
**Nog open:** volledige Web-ronde door de mens; stap 5 (S5-a t/m S5-e); terugschrijven naar `fase2-screening-design.md` §9 (beperking "één instantie"
achterhaald, fencing-update moet eerste statement van de transactie zijn) na akkoord.
**Bron:** hoofdsessie na verificatie

## 2026-10-02 — Stap 5 uitgevoerd (S5-a t/m S5-e) + regressiefix S1-f
**Vraag:** Zijn de NOWAIT-sloten, addOneBatch, de atomaire accept-baseline en de strengere stale-check afgerond en geverifieerd?
**Beslissing:** Ja, op branch `verbeteringen-stap-1-3`, één commit per story. Door de hoofdsessie herhaald: Domain 68, Dao 35, Service 619;
`AcceptBaselineAtomicityTest` 7/7, regressieronde baseline/bundel 56/56, `BatchBaselineHttpTest` + atomiciteit 23/23, bundelslot 9/9.
Aanvaarde invullingen: (1) twee bestaande tests in `BatchBaselineHttpTest` die het oude halve-werk-gedrag vastlegden zijn omgedraaid (gevolg van
"alles-of-niets"); (2) tijdens een lopende aanvaarding op een koppeling krijgt ook een al aanvaarde batch `BASELINE_ACCEPTANCE_IN_PROGRESS`; (3) een
referentierace draait nu de volledige aanvaarding terug; (4) `addBatches` geeft resultaten in oplopende batch-id-volgorde; (5) de uitzondering
"bronstaat = kandidaat" in de stale-check blijft (A16, idempotente insert/update); (6) Hibernate gebruikt `FOR NO KEY UPDATE`: uploads voor een
koppeling worden tijdens een aanvaarding niet geblokkeerd, updates van de koppeling wachten.
**Regressie:** de geneste testcontroller van S1-f liet `PermissionCoverageTest` falen sinds 88a5926; opgelost door hem naar pakket
`be.dda.contracttest` te verplaatsen (`PermissionCoverageTest` ongewijzigd — een voorgestelde skip op `@Profile` is geweigerd omdat die de
rechtenbewaking zou verzwakken). Les: na elke story die testcode in `web` toevoegt, `PermissionCoverageTest` meedraaien.
**Nog open bij de mens:** volledige Web-ronde (loopt), prestatietest accept-baseline op ~1M regels, terugschrijven naar
`fase2-screening-design.md` §9/§17/§18 (één instantie achterhaald, accept-baseline niet meer hervatbaar, fencing als eerste statement,
`FOR NO KEY UPDATE`) en de business rule over UNCHANGED-regels, na akkoord.
**Bron:** hoofdsessie na verificatie

## 2026-10-02 — Ontwerpdocument bijgewerkt na stap 4 en 5
**Vraag:** Mogen de ontdekkingen van stap 4 en 5 terug in `docs/design/fase2-screening-design.md`?
**Beslissing:** Ja (mens: "werk het design-document bij"). §9: verwerkingsclaim per batch vervangt de beperking "één instantie", plus de globale
slotvolgorde (enige plek; §17 verwijst ernaar). §16/§17/§18/§10: accept-baseline atomair, NOWAIT-codes, stale-check incl. UNCHANGED (business
rule), C9 vanzelf waar; bekend restrisico "twee gelijktijdige accepts → 500" als achterhaald gemarkeerd. `FOR NO KEY UPDATE` staat erin met de
aantekening dat het enkel empirisch (tijdelijke diagnosetest S5-c) en niet in een blijvende test vastgelegd is.
**Bron:** mens / bouwer-gemiddeld, nagekeken door de hoofdsessie

## 2026-10-02 — Stap 6, 8 en 9 gestart
**Vraag:** Welke analyse-stappen worden nu opgepakt?
**Beslissing:** Stap 6 (DB-checks op de kernstatussen), stap 8 (frontend-duplicatie en tooling) en stap 9 (grote klassen opsplitsen) — mens:
"ga verder met stap 6, 8 en 9". Stap 7 (operationeel) niet. Werk op branch `verbeteringen-stap-6-8-9`, vertakt van `verbeteringen-stap-1-3` (PR open).
Stap 6: additieve CHECK-constraints op de status van `import_batch`, `import_mutation`, `task_run` en `import_definition_revision` met exact de
waarden van de Java-enums, met een precondition die stopt (HALT) als bestaande data ze schendt, zoals 016; `ck_publication_bundle_batch_removed`
corrigeren naar `is true`. Stap 8 en 9: invulling door een denker-subagent, apart gelogd vóór een bouwer start. Stap 9 zonder gedragswijziging.
**Bron:** mens / analyse ImportService 2026-10-01

## 2026-10-02 — Stap 8 en 9 uitgewerkt
**Vraag:** Hoe worden frontend-duplicatie en tooling (8) en de grote klassen (9) aangepakt?
**Beslissing:**
- **Stap 8 (sequentieel, één commit per story):** S8-a tests typechecken (`tsconfig.test.json`, vitest/jest-dom-types, node-types; stopregel
  >40 echte fouten → rapporteren); S8-b oxlint met jsx-a11y + `react/exhaustive-deps` (elke onderdrukking met reden); S8-c één `src/actor/gate.ts`
  (`Gate`, `ALLOWED`, `denied`, `gateTitle`; `PermissionGate` → `Gate`); S8-d één `src/format.ts` `formatDateTime` (`UploadPage` krijgt `nl-BE`;
  `formatByteSize` blijft dubbel omdat de tekst verschilt); S8-e terugvalteksten via de dictionary-entry, `linkCheck.baseCode` → `issueBaseCode`,
  één `OLD_WORDING`-bron (UNKNOWN-teksten blijven twee varianten); S8-f dubbele tokens weg zonder hernoeming (consumenten mee); S8-g één
  `components/Button.module.css` (varianten primary, secondary, danger, dangerOutlined, modifier small; geen `composes`; lokale modules enkel layout),
  in twee delen (g1: module + ConfirmDialog/Pager/BatchActions/ActorBar; g2: overige modules), gevolgd door een visuele controle door de mens.
- **V1 knopstijl (mens):** één opvulling per grootte, één stijl voor uitgeschakeld, donkere outlined-knoppen worden Prodis-outlined met grijze rand,
  gevaar-rood **#B71D18** (contained-achtergrond 6,6:1; outlined: tekst #B71D18, rand #FF5630) — bewust afwijkend van Prodis' #FF5630 voor WCAG AA.
- **Stap 9 (zonder gedragswijziging, tests enkel wiring):** S9-a `SetupService` → `RevisionFieldRules` + `SetupInput` (package-private, statisch);
  S9-b `DeliveryScreeningService` → `ReferenceControlPass` + `PriceControlPass` (geen beans, geen transacties/claim in de passes; constructorsignatuur
  DSS ongewijzigd; aantal `inClaim`/`transaction.` gelijk); S9-c `RevisionEditForm` → `revisionEditRequest.ts`; S9-d `DescriptionStep` →
  `descriptionForm.ts`; S9-e `MutationList` → `mutationQuery.ts` + `cells.tsx`. Blijven: SftpConnector, TemplateMaterialisationService,
  ImportMappingConfigFactory, TrialReadService, MaterialiseForm, UploadPage, TrialReadResultView, SetupOverviewPage, RevisionDetailSection.
**Bron:** denker-zwaar + mens (V1) / analyse ImportService 2026-10-01

## 2026-10-02 — S8-b: lintbeslissingen
**Vraag:** Hoe omgaan met de 30 nieuwe jsx-a11y-vondsten?
**Beslissing:** `jsx-a11y/prefer-tag-over-role` uit (`role="status"`/`role="group"` op een div is geldige ARIA; `<output>`/`<fieldset>` zijn geen
drop-in en tests zoeken op `getByRole('status')`). De backdrop in `App.tsx` krijgt een onderdrukking met reden: het is een extra muis-only
sluitlaag; het toetsenbord sluit het menu via de focusbare menuknop en de navigatielinks. Geen Escape-handler — kandidaat voor later.
exhaustive-deps: 0 vondsten. oxlint: 0 fouten, 14 bestaande waarschuwingen.
**Bron:** hoofdsessie (technische lintkeuze, geen §6) / bouwer-voorstel

## 2026-10-02 — Stap 6, 8 en 9 uitgevoerd
**Vraag:** Zijn de DB-checks, de frontend-opruiming en de opsplitsingen afgerond en geverifieerd?
**Beslissing:** Ja, op branch `verbeteringen-stap-6-8-9`, één commit per story (S8-c en S8-d samen: zelfde bestanden). Volledige ronde
`run-full-tests.ps1 -Module Web`: **162 klassen, 2012 tests, 0 gefaald**. Frontend: `tsc -b` schoon (incl. tests), oxlint 0 fouten, vitest 644/644.
Aanvaarde invullingen: Button-modifier `selected`; hash-knop niet meer monospace; in BatchActions is "Opnemen in bundel" primair en
"Aanvaarden als nulmeting" secundair (visueel na te kijken door de mens); `PriceControl` en `Context.mutationContext()` package-private (enkel
zichtbaarheid); `cells.tsx` met bestandsbrede `only-export-components`-onderdrukking met reden.
**Incident:** een eerste volledige ronde gaf 109 fouten (`NoClassDefFoundError` op Domain-klassen, Mockito "Could not modify all classes") zonder
codewijziging; de herhaling was groen. Waarschijnlijke oorzaak: IntelliJ bouwt mee in `target/classes` — niet bewezen.
**Nog open bij de mens:** visuele controle van de knoppen; push + PR van deze branch; stap 7 (operationeel).
**Bron:** hoofdsessie na verificatie

## 2026-10-02 — Stap 7 gestart (operationeel)
**Vraag:** Wordt stap 7 opgepakt?
**Beslissing:** Ja (mens: "ga verder met stap 7"), op branch `verbeteringen-stap-7` (vertakt van `verbeteringen-stap-6-8-9`, gepusht). Inhoud uit de
analyse: actuator/health toevoegen (config en security veronderstellen `/actuator/health` al), H2 en het default DB-wachtwoord uit het
productie-artefact, een retentie-/purgeontwerp voor staging en row-issues, en een asynchrone screening agenderen (enkel ontwerp/planning).
Invulling door een denker-subagent; keuzes over bewaartermijnen en blootgestelde endpoints gaan naar de mens.
**Bron:** mens / analyse ImportService 2026-10-01
