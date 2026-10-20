# Catalog import — businessregels en uitzonderingen

> Status: historische proefversie. Voor de actuele implementatiestatus en de verschillen met de
> vernieuwde businessanalyse, zie
> [`../analysis/current-project-vs-businessanalyse-2.md`](../analysis/current-project-vs-businessanalyse-2.md).

## Identiteit en traceerbaarheid

- Een catalogusbron heeft een unieke `code`.
- Een importdefinitie is uniek per `bron + versie` en wijst precies één controlebibliotheek aan.
- De aanbiedingsidentiteit is `leverancier + leveranciersreferentie`; binnen een controlebibliotheek wordt die identiteit gebruikt om CREATE van UPDATE te onderscheiden.
- Een batch bewaart de originele bytes, bestandsnaam, ontvangsttijd, definitie, SHA-256-hash, status, recordaantal en issueaantal.
- Een kandidaat bewaart bovendien het bronnr. van de CSV-regel. Een geplande mutatie verwijst naar zijn kandidaat en bevat bij UPDATE de vorige prijs.
- Dezelfde inhoud onder dezelfde importdefinitie mag geen tweede batch vormen. De databaseconstraint bevestigt deze regel ook bij gelijktijdige verwerking.

## Validatie bij upload

- De CSV moet een niet-lege header hebben.
- Komma en puntkomma worden ondersteund; het teken dat het vaakst in de header voorkomt, is het scheidingsteken.
- Alle door de definitie aangewezen kolommen `supplier`, `reference`, `group` en `price` moeten aanwezig zijn.
- Leverancier, referentie en groep mogen niet leeg zijn.
- Prijs moet een decimaal zijn, mag hoogstens zes decimalen hebben en wordt op zes decimalen bewaard met `HALF_UP`.
- Een open aanhalingsteken in een CSV-regel is ongeldig.
- Dezelfde aanbiedingsidentiteit mag slechts eenmaal binnen één batch voorkomen.
- Elke ongeldige of dubbele regel verhoogt `issueCount`. Zodra `issueCount > 0`, wordt het volledige batch geblokkeerd; geldige kandidaatregels uit dat batch worden niet gepubliceerd.

## Planning en publicatie

- Alleen `SCREENED` batches mogen gepland worden. Na een geslaagde automatische planning bij upload, of na de plan-call, is de status `PLANNED`.
- Een bestaande, actieve aanbieding met dezelfde groep en numeriek gelijke prijs geeft geen mutatie.
- Een ontbrekende aanbieding geeft CREATE; een bestaande maar afwijkende aanbieding geeft UPDATE.
- Bij een bestaande bibliotheekomvang groter dan nul blokkeert de planning als er meer dan 100 CREATE-mutaties zijn of als CREATE-mutaties meer dan 100% van die omvang vormen.
- Alleen een `PLANNED` batch mag worden goedgekeurd en gepubliceerd.
- `system` mag niet als goedkeurder worden gebruikt; een niet-lege, benoemde approver is vereist.
- Publicatie zet elke geplande aanbieding actief en schrijft groep, prijs en `updatedAt`. Daarna wordt het batch `PUBLISHED`.

## Statussen

`RECEIVED` is de initiële status. De huidige flow gaat vervolgens naar `SCREENED`, `PLANNED`, `APPROVED` en `PUBLISHED`, of naar `BLOCKED`. `FAILED` bestaat in het domeinmodel maar wordt in de huidige implementatie niet gezet.

## Belangrijke afbakening vanuit de businessanalyse

> Important business rule discovered
>
> Een leverancierscatalogus is broninformatie en niet automatisch de waarheid: wijzigen mag pas na controle en expliciete goedkeuring.

> Important business rule discovered
>
> Een bibliotheekzoekleverancier is zoekmetadata; de leverancier op de detailregel is leidend voor matching en prijsregels. Een verschil is niet automatisch fout.

> Important business rule discovered
>
> Cataloguspublicatie maakt of wijzigt bibliotheekaanbiedingen, maar mag geen massale creatie van operationele artikelen veroorzaken.

> Important business rule discovered
>
> Een werkvoorraad-/dashboardteller mag "niet vastgesteld" nooit als 0 tonen. Het datamodel onderscheidt beide consequent — `ImportBatch.validationResult`, `criticalLineCount`, `bulkIncidentCount`, `awaitingApprovalCount`, `creationOutcome` zijn allemaal nullable met de expliciete javadoc "`null` betekent niet vastgesteld, nooit stil 0" — maar dat moet ook in elke aggregatie/weergave zo blijven. Een tegel "0 kritieke lijnen" over batches waarvan `criticalLineCount` null is (technisch mislukte of lopende screening) is een onware geruststelling. Elke aggregatie over nullable tellers toont "niet vastgesteld" als een eigen, zichtbare categorie naast het getal.

> Important business rule discovered
>
> Legacy-gedrag van het wegschrijven naar een bibliotheek (`Prg 1179`, idem `1172` voor ARTICLES; bron: Legacy-vault, 2026-10-20): een leeg veld (of 0) betekent **bestaande waarde behouden**, niet wissen. Uitzonderingen: genre 1–8 en de leverancierssleutels worden altijd overschreven (leeg maakt leeg), een lege wijzigingsdatum wordt de verwerkingsdatum, lege prijspercentages nemen de groeppercentages over. Verwijderen gebeurt langs twee wegen: (1) per regel via `ARIMP_DELETE='Y'`, dat alleen ontstaat via berekeningscode 8 in de importdefinitie (kolomwaarde gelijk aan de ingestelde waarde); (2) met de interfaceparameter `BIB_Delete` (`P_BIB_DELETE_NOT_UPDATED`) wist `Prg 1179` élke regel van `PSARF<bib>` die in deze run niet bijgewerkt werd, zonder afbakening per leverancier (subtask `Delete Bib/MS Ref not Found`, `Prg_1179` r.2427-2436). Het leveranciersartikel (`PSARFLER`) wordt enkel gewist bij `MS_Delete` en als het niet de voorkeurleverancier is. (Gecorrigeerd 2026-10-20 na het ontwerp `docs/design/legacy-config-migratie-design.md`.) Nog door Prodis te bevestigen voor de WebBase-variant (zie `docs/openstaande-externe-punten.md` punt 3).

> Important business rule discovered
>
> Legacy bewaart interfacewachtwoorden (`INDET`, o.a. `FTP_password`) in plain text en toont ze op het scherm. Bij het overnemen van legacy-interface-instellingen mogen geheimen nooit 1-op-1 gekopieerd of getoond worden; ze gaan uitsluitend versleuteld onze credentialopslag in.

> Important business rule discovered
>
> Legacy zet leverancier, groep en referentie (veld 9-11) en nog een tiental andere velden (o.a. 1-3, 8, 13, 14) met `Upper()` in hoofdletters vóór ze in de staging komen (`Prg_1170` r.733-815). De identiteitscanonicalisatie van CatalogImport is hoofdlettergevoelig (`CandidateNormaliser` r.601). Een overgenomen legacy-definitie zonder expliciete hoofdletteromzetting geeft dus andere identiteiten dan in legacy/PSARF, met dubbels of gemiste matches als gevolg. Bron: `docs/design/legacy-config-migratie-design.md` §7 (Q4).

## Open uitzonderingen die nog een businesskeuze vragen

- De proefversie bewaart enkel een issueaantal; zij bewaart reden en bronregel niet voor ongeldige regels. Support kan de originele CSV raadplegen, maar geen foutdetail opvragen.
- De approver en het exacte goedkeuringstijdstip worden niet persistent geaudit.
- Bij database- of publicatiefouten bestaat nog geen uitgewerkte compensatie-, retry- of herverwerkingsregel.
- Inactivatie van records die niet meer in een levering staan, is niet geïmplementeerd. Hiervoor is eerst bron-eigenaarschap per bibliotheekregel nodig; een bibliotheekzoekleverancier is onvoldoende.
