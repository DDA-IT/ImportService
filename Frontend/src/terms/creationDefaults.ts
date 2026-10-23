/**
 * CD-F — schermteksten van de aanmaakstandaard (btw-code en eenheid) van een koppeling en van de vlag "standaarden
 * overschrijven" op de importdefinitie (beslissingslog 2026-10-23 "Aanmaakstandaard per koppeling: uitwerking (CD)" en
 * "Vlag 'standaarden overschrijven' op de importdefinitie"). Codes staan hier nooit als hoofdtekst; de foutcodes
 * hebben hun tekst in `errors/codes.ts`, de waarschuwingscodes in het woordenboek (`legacyReportCode`).
 */

import type { ProdisUnitOfMeasure, ProdisVatCode } from '../api/types.ts';

/** Percentage met decimale komma, zonder nodeloze nullen: 21 -> "21", 6.5 -> "6,5". */
function formatPercentage(value: number): string {
  return String(value).replace('.', ',');
}

/** "BTW21% (21 %)", of enkel de omschrijving of het percentage als de andere ontbreekt; `null` als beide ontbreken. */
function vatDescription(row: Pick<ProdisVatCode, 'description' | 'percentage'>): string | null {
  const description = row.description?.trim() ?? '';
  const percentage = row.percentage === null ? null : `${formatPercentage(row.percentage)} %`;
  if (description !== '' && percentage !== null) {
    return `${description} (${percentage})`;
  }
  return description !== '' ? description : percentage;
}

export const CREATION_DEFAULTS_TEXT = {
  title: 'Standaarden bij aanmaak',
  vat: 'Btw-code',
  unit: 'Eenheid',
  unset: 'Niet ingesteld',
  legacyBadge: 'Uit legacy',
  changedBy: (name: string, when: string | null) => (when === null ? `gewijzigd door ${name}` : `gewijzigd door ${name} op ${when}`),
  note: 'Wordt gebruikt zodra CatalogImport artikels aanmaakt. Bestaande artikels worden nooit aangepast.',
  overwriteNote:
    'Bij een nieuwe overname uit legacy worden deze waarden overschreven (instelling van de importdefinitie).',
  change: 'Wijzigen',
  changeTitle: (linkCode: string) => `Standaarden bij aanmaak van ${linkCode} wijzigen`,
  save: 'Opslaan',
  saving: 'Bezig…',
  cancel: 'Annuleren',
  saved: 'Standaarden bij aanmaak opgeslagen.',
  warningsTitle: 'Opgeslagen, met een opmerking:',
  listUnavailable: 'Prodis-lijst niet beschikbaar',
  listUnavailableHint: 'Typ de code zelf in; ze wordt bij het bewaren gecontroleerd (voor zover Prodis bereikbaar is).',
  listLoading: 'Bezig met laden…',
  noResults: 'Geen overeenkomst in de lijst; de getypte code wordt bij het bewaren gecontroleerd.',
  clearHint: 'Laat een veld leeg om de standaard te wissen.',
  vatPlaceholder: 'Typ een code of omschrijving',
  unitPlaceholder: 'Typ een code of naam',
  /** De waarschuwingen komen uit `warnings[].field`: `vatCode` of `unitCode`. */
  warningField: (field: string | null): string | null =>
    field === 'vatCode' ? 'Btw-code' : field === 'unitCode' ? 'Eenheid' : null,
  /** Weergave van een bewaarde btw-code: "3 — BTW21% (21 %)", of enkel de code zolang de lijst ontbreekt. */
  vatValue: (code: string, row: ProdisVatCode | undefined): string => {
    const description = row === undefined ? null : vatDescription(row);
    return description === null ? code : `${code} — ${description}`;
  },
  unitValue: (code: string, row: ProdisUnitOfMeasure | undefined): string => {
    const name = row?.singular?.trim() ?? '';
    return name === '' ? code : `${code} — ${name}`;
  },
  /** Keuzelijstoptie: "3 · BTW21% (21 %)", met de melding als de code in Prodis meerdere keren voorkomt. */
  vatOption: (row: ProdisVatCode): string => {
    const description = vatDescription(row);
    const base = description === null ? row.code : `${row.code} · ${description}`;
    return row.occurrences > 1 ? `${base} — komt ${row.occurrences} keer voor in Prodis` : base;
  },
  unitOption: (row: ProdisUnitOfMeasure): string => {
    const name = row.singular?.trim() ?? '';
    const base = name === '' ? row.code : `${row.code} · ${name}`;
    return row.occurrences > 1 ? `${base} — komt ${row.occurrences} keer voor in Prodis` : base;
  },
} as const;

export const OVERWRITE_FLAG_TEXT = {
  label: 'Standaarden van koppelingen overschrijven bij legacy-overname',
  on: 'Aan',
  off: 'Uit',
  change: 'Wijzigen',
  changedBy: (name: string, when: string | null) => (when === null ? `gewijzigd door ${name}` : `gewijzigd door ${name} op ${when}`),
  warning:
    'Bij een overname uit legacy worden btw-code en eenheid van alle koppelingen overschreven, ook manueel ingestelde waarden.',
  warningShared: (n: number) =>
    `Bij een overname uit legacy worden btw-code en eenheid van alle koppelingen overschreven, ook manueel ingestelde waarden. Dit geldt voor alle ${n} koppelingen van deze importdefinitie.`,
  confirmOnTitle: 'Standaarden overschrijven aanzetten?',
  confirmOffTitle: 'Standaarden overschrijven uitzetten?',
  confirmOnBody: 'Bij een volgende overname uit legacy worden de waarden van de koppelingen opnieuw overschreven.',
  confirmOffBody: 'Een volgende overname uit legacy laat de bestaande waarden van de koppelingen staan.',
  confirmOn: 'Aanzetten',
  confirmOff: 'Uitzetten',
} as const;
