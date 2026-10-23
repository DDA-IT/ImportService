/**
 * E5b — de woorden van "Per kolom van het bestand" (beslissingslog 2026-10-23 "Editor-API-gaten" EB5/E5b en "EB5: keuzes
 * kolomvoorbeeld proefinlezing"; mock `docs/mock/setup-redesign-mock.html`): het kolomvoorbeeld van de proefinlezing, per
 * kolom met waar die naartoe gaat in de versie die nu op het scherm staat. Geen codes of ruwe ids als zichtbare tekst.
 */

export const COLUMN_PREVIEW_TEXT = {
  // Schakelaar naast "Per veld" in de Velden-balk
  switchLabel: 'Weergave van de velden',
  byField: 'Per veld',
  byColumn: 'Per kolom van het bestand',
  byColumnDisabled: 'Beschikbaar nadat u een testbestand geladen hebt',

  heading: 'Velden',
  tableLabel: 'Kolommen van het testbestand',
  head: {
    column: 'Kolom',
    name: 'Kolomnaam',
    samples: 'Voorbeeldwaarden (3)',
    goesTo: 'Gaat naar',
  },
  noName: 'Geen kolomnaam',
  noValues: 'Geen waarden in de voorbeeldregels',
  emptyValue: '(leeg)',
  unreadableLine: (line: number) => `Regel ${line} onleesbaar`,
  notUsed: 'Niet gebruikt',
  link: 'Koppelen aan veld…',
  linkLabel: (position: number) => `Koppelen aan veld: kolom ${position}`,
  filterLabel: (n: number) => `Filter ${n}`,
  groupLabel: (n: number) => `Uitsluitingsgroep ${n}`,

  // Samengevatte reeksen niet-gebruikte kolommen
  run: (from: number, to: number) => `Kolommen ${from}–${to} (niet gebruikt)`,
  runShow: 'Toon deze kolommen',
  runHide: 'Verberg deze kolommen',

  // Verwacht maar niet in het bestand
  missingHeading: 'Verwacht, maar niet in het bestand',
  missingByName: (field: string, name: string) => `${field}: kolom “${name}”`,
  missingByPosition: (field: string, position: string) => `${field}: kolom ${position}`,

  // Waarschuwingen en uitkomsten
  outdated:
    'Dit voorbeeld hoort bij een eerdere bestandsopbouw (de tekenset, het scheidingsteken, het aanhalingsteken of de kopregel is sindsdien gewijzigd). Controleer opnieuw met het testbestand voor een bijgewerkt overzicht.',
  statusLayoutInvalid:
    'De bestandsopbouw van deze versie klopt niet (bijvoorbeeld het scheidingsteken, de tekenset of de kopregel). Daardoor kan het bestand niet in kolommen getoond worden. Pas de bestandsopbouw aan en controleer opnieuw.',
  statusFileEmpty: 'Het testbestand is leeg: er zijn geen kolommen om te tonen.',
  statusHeaderBeyond: (line: number | null) =>
    line === null
      ? 'De kopregel ligt voorbij het begin van het bestand dat getoond kan worden. Controleer de kopregel in de bestandsopbouw.'
      : `De kopregel (regel ${line}) ligt voorbij het begin van het bestand dat getoond kan worden. Controleer de kopregel in de bestandsopbouw.`,
  headerUnreadable:
    'De kopregel kon niet gelezen worden (bijvoorbeeld een aanhalingsteken dat niet gesloten is). Er zijn daarom geen kolommen om te tonen.',
  noColumns: 'Er zijn geen kolommen gevonden in het testbestand.',
  columnsTruncated: (shown: number) => `Alleen de eerste ${shown} kolommen worden getoond.`,
  rowsTruncated: (shown: number) =>
    `Er zijn mogelijk meer regels in het bestand. De voorbeeldwaarden komen uit de eerste ${shown} regels.`,
  rowsShown: (shown: number) => `De voorbeeldwaarden komen uit de eerste ${shown} regels van het bestand.`,
  unreadableNote: (lines: readonly number[]) =>
    lines.length === 1
      ? `Regel ${lines[0]} kon niet gelezen worden en telt niet mee in de voorbeeldwaarden.`
      : `Regels ${lines.join(', ')} konden niet gelezen worden en tellen niet mee in de voorbeeldwaarden.`,
  notStored: 'Dit voorbeeld bestaat enkel op deze pagina: er is niets opgeslagen.',
} as const;
