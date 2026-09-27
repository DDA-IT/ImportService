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