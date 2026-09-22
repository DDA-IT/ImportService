# Vergelijking huidige CatalogImport met businessanalyse 2

## Doel en leeswijzer

Dit document vergelijkt de feitelijke huidige implementatie met
[`Businessanalyse_artikelimport_en_prijsacceptatie-2.md`](../../Businessanalyse_artikelimport_en_prijsacceptatie-2.md).
Het is een statusdocument, geen uitbreiding van de functionele scope en geen bewijs dat een nog
ontbrekende integratie al werkt.

De vergelijking is gebaseerd op de actuele broncode, Liquibase-changesets, REST-controllers en
gerichte tests. De inhoud van oudere proefversiedocumenten is alleen gebruikt wanneer zij nog met de
broncode overeenstemt.

## Samenvatting

CatalogImport is vandaag een solide **screening- en nulmetingslaag voor één handmatig geüpload
CSV-bestand**. De toepassing bewaart de bron, valideert records, detecteert verschillen tegen een
eigen bronstaat en maakt een traceerbaar mutatieplan. Dat sluit goed aan bij de ontvang-,
idempotentie- en validatieprincipes uit de businessanalyse.

De analyse beschrijft echter een volledig artikelimportplatform: meerdere leveringsonderdelen,
centrale artikelen, leverancieraanbiedingen, prijsvoorwaarden met verpakking en staffels,
leveranciers-BOM's, menselijke werkvoorraad, ERP-lezen en een bevestigde PSIMPORT-publicatieroute.
Die onderdelen zijn nog niet gerealiseerd. De huidige `accept-baseline` is bewust geen publicatie:
zij vult uitsluitend de lokale bronstaat als geauditeerde nulmeting.

## Wat de huidige implementatie al levert

| Onderwerp | Huidige werking | Aansluiting op analyse |
| --- | --- | --- |
| Bronarchief en retry | De originele bytes worden gearchiveerd; inhoudshash en leveringsreferentie voorkomen dubbele ontvangst. | Sterke basis voor ontvangst, audit en idempotentie (hoofdstuk 5). |
| Versieerbare inrichting | Importdefinitie, revisie, mapping, filters en kritieke velden worden geconfigureerd en een actieve revisie is bevroren. | Past bij bronprofielen en reproduceerbare interpretatie (5.1, 5.6 en 5.10). |
| Screening | CSV wordt streaming verwerkt naar staging; structurele fouten, recordfouten, identiteit, referenties en drempels worden afzonderlijk beoordeeld. | Past bij de poort vóór delta en acceptatie (6.1 en 12). |
| Traceerbare problemen | Regelproblemen, foutgroepen, werkelijk aantal voorvallen en blokkeringsreden zijn opvraagbaar. | Goede basis voor behandeling en monitoring (13 en 17). |
| Aanbiedingsidentiteit | `leverancier + leveranciersgroep + leveranciersreferentie`, optioneel kortingscode; bibliotheek is scope. | Sluit aan bij brongebonden identificatie, maar is nog geen centrale artikelidentiteit (4 en 8). |
| Prijscontrole | Basisprijs en prijscomponenten worden gevalideerd; afwijking wordt vergeleken met vorige waarde en korte/lange historie. | Bruikbare eerste controle, maar nog geen volledige economische prijscontext (11). |
| Herstel van screening | Staging en mutatiegeneratie zijn chunkgewijs en hervatbaar; unieke sleutels bewaken de belangrijkste races. | Past bij de eisen rond herhaling en technische uitval (1 en 16). |

## Verschillen met de vernieuwde analyse

| Analyseonderwerp | Huidige status | Gevolg / benodigde vervolgstap |
| --- | --- | --- |
| Leveringsenvelop en kanalen (5.1–5.4) | Alleen één handmatig CSV-bestand per levering. Geen API/SFTP/HTTPS-connector, XML-adapter, manifest, onderdeelvolgorde of volledigheidsbewijs. | Modelleer eerst levering, onderdeel, scope, bronvolgorde en snapshot/delta-betekenis. Verwijdering op basis van afwezigheid mag tot dan niet bestaan. |
| Centrale catalogus (4, 8 en 9) | De bronstaat bevat aanbiedingen; er is geen centraal artikel, leveranciersaanbieding als zelfstandig object, bibliotheeklidmaatschap of bronvoorrang per veldgroep. | Bepaal D01 en D03 vóór een nieuw domeinmodel. Een leverancierscode of EAN mag niet automatisch een centrale productidentiteit worden. |
| Relaties en leveranciers-BOM's (8) | Geen relationeel model voor alternatieven, supplementen of leveranciers-BOM-versies. | Voeg pas na vastlegging van productvariant, verpakkingsidentiteit en bron-BOM-eigenaarschap een versieerbare relatie-/BOM-laag toe. Eigen productie-BOM's blijven buiten scope. |
| Prijsvoorwaarden (11) | Prijs is vooral bedrag/component plus afwijkingshistoriek. Contract, prijssoort, belastingbasis, verpakking, besteleenheid, staffels, geldigheid, prijsanker en bewijs zijn niet gemodelleerd. | Bouw prijscontext en volledige prijsvoorwaardenset als één acceptatie-eenheid. Een gelijkblijvende stukprijs is onvoldoende wanneer doosinhoud of staffelgrens wijzigt. |
| Geldigheid en planning (7, 10) | Geen ingeplande/actieve/verlopen prijsversies; geen hercontrole op activeringsmoment. | Modelleer halfopen geldigheidsintervallen en scheid importstatus, voorstelstatus en prijsstatus. |
| Werkvoorraad en behandeling (13) | Er zijn issues en issuegroepen, maar geen behandelgeval, eigenaar, taakstatus, bewijs, override, dashboard of UI. | Introduceer taken op een zakelijke oorzaak/groep, niet één taak per foutieve regel. |
| Rollen en rechten (3) | `uploadedBy` en `acceptedBy` zijn requestvelden; er is geen authenticatie of autorisatie. | Koppel rechten aan de vastgelegde rollen voordat prijsuitzonderingen, fusies of productiepublicatie mogelijk zijn. Vier-ogen is in de analyse configureerbaar; de huidige keuze voor één persoon is geen bewijs van bevoegdheid. |
| Acceptatie per wijzigingsgroep (10) | De screening kent `PLANNED`, `AWAITING_APPROVAL` en `BLOCKED`, maar geen duurzaam besluit per samenhangende groep of publicatiebundel. | Leg voorstel, besluit, reden, bewijs, afhankelijkheid en versie vast. De kleinste groep is pas later te bepalen zodra prijsvoorwaarden en BOM's bestaan. |
| ERP-lezen en brongezag (2.2, 4.2–4.6, 9.2) | Geen ERP-leesconnector en geen vastgelegd veldgezag of versie van de gebruikte ERP-referentie. | D11 en D03 zijn randvoorwaarden. CatalogImport mag ERP-stamdata niet als tweede administratie gaan beheren. |
| PSIMPORT-publicatie en terugmelding (15) | Geen publisher, projectie, outbox/afleverregister, `ARIMP_Record`-allocatie, resultaatcorrelatie of reconciliatie. | Ontwerp en bevestig eerst het veldcontract, patch-/behoudsemantiek, deletecodes, atomiciteit en terugmelding. `Verwerken=True` mag niet worden gebruikt als veronderstelde succesbevestiging. |
| Niet-functionele eisen (16–18) | Streaming en chunking beperken geheugen; er is geen aangetoond capaciteits-, herstel-, beveiligings- of integratietestbewijs voor de genoemde productiedoelen. | Meet pas tegen NF01–NF10 nadat pilotbron, doelomgeving en ERP-contract zijn vastgelegd. |

## Belangrijke businessregels die al zichtbaar zijn

> Important business rule discovered
>
> Een ontvangen bestand is een bronbewering, geen wijzigingsopdracht. Alleen een inhoudelijke delta
> na controle mag verder in het proces.

> Important business rule discovered
>
> Een identiteitsincident blokkeert de afhankelijke aanbieding; een ontbrekende of onleesbare prijs
> wordt nooit als nul geïnterpreteerd.

> Important technical constraint discovered
>
> `accept-baseline` bestaat uitsluitend om de lokale bronstaat betrouwbaar op te bouwen zolang geen
> publicatieroute bestaat. Zij mag niet worden hernoemd of geïnterpreteerd als bevestigde
> Prodis-publicatie.

## Documentatie die niet langer als actuele status geldt

De volgende documenten bevatten beschrijvingen van een eerdere proefversie. Met name de verwijzingen
naar `LibraryOffer`, `PLANNED → APPROVED → PUBLISHED` en lokale publicatie zijn niet de huidige
implementatie. Ze blijven waardevol als historische ontwerpcontext, maar niet als bron voor de actuele
status of de vervolgscope:

- `docs/requirements/catalog-import.md`
- `docs/requirements/catalog-import-business-rules.md`
- `docs/requirements/catalog-import-acceptance.md`
- `docs/design/catalog-import-design.md`
- `docs/analysis/catalog-import-impact.md`

De actuele technische status staat in `README.md`, de geldende reeds gemaakte keuzes in
`docs/decisions.md`, en deze vergelijking vult die aan met de vernieuwde businessanalyse.

## Aanbevolen documentatiegestuurde fasering

1. **Besluiten vastleggen.** Werk D01–D05 en D11 uit vóór een datamodelwijziging: productvariant,
   verpakking/BOM, broncontract, veldgezag, prijsbetekenis en ERP/PSIMPORT-contract.
2. **Ontvangstcontract documenteren.** Kies één pilotbron en leg formaat, scope, volledigheid,
   volgorde, foutafhandeling en voorbeeldbestanden vast.
3. **Domeincontract documenteren.** Beschrijf centrale artikelen, leveranciersaanbiedingen,
   prijscontexten, prijsversies en relatie-/BOM-versies inclusief identificaties en geldigheid.
4. **Acceptatie- en publicatiecontract documenteren.** Leg wijzigingsgroepen, besluiten,
   bevoegdheden, audit, afhankelijkheden en herstel vast zonder goedkeuring met toepassing te
   verwarren.
5. **Integratiecontract documenteren.** Werk pas daarna het PSIMPORT-veldcontract, de
   vrijgavevolgorde en de terugmeld/reconciliatieflow uit.

Deze volgorde behoudt de bestaande screening als bruikbare verticale slice en voorkomt dat een
onbevestigd Prodis- of prijsgegeven al vroeg als waarheid in het importdomein wordt opgeslagen.
