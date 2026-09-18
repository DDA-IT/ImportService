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
