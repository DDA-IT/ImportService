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
