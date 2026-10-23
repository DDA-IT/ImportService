/**
 * E5b — het model van "Per kolom van het bestand": naamherkenning (getrimd, hoofdletterongevoelig), herkenning op nummer
 * (vanaf 1, buiten bereik = niets), filters, "verwacht maar niet in het bestand", voorbeeldwaarden, samenvatten van lange
 * reeksen en het verouderd-teken bij een gewijzigde bestandsopbouw.
 */

import { describe, expect, it } from 'vitest';
import {
  buildColumnPreviewModel,
  isPreviewOutdated,
  resolvePosition,
  samplesOf,
  sourceForColumn,
  tableItems,
  unreadableLines,
} from '../features/setup/columnPreviewModel';
import { termLabel } from '../terms/index';
import { filter, mapping, preview, revision } from './columnPreviewFixtures';

function usesOf(model: ReturnType<typeof buildColumnPreviewModel>, position: number): string[] {
  return model.columns.find((column) => column.position === position)!.uses.map((use) => use.label);
}

describe('naamherkenning (HEADER_NAME)', () => {
  it('koppelt kolommen aan herkenning en basis, getrimd en hoofdletterongevoelig', () => {
    const model = buildColumnPreviewModel(
      preview([' lev ', 'Grp', 'ref', 'PrIjS', 'Omschrijving', 'Overig']),
      revision(),
    );
    expect(usesOf(model, 1)).toEqual(['Leverancier']);
    expect(usesOf(model, 2)).toEqual(['Groep']);
    expect(usesOf(model, 3)).toEqual(['Referentie']);
    expect(usesOf(model, 4)).toEqual([termLabel('fieldKey', 'BASE_PRICE', 'Basisprijs')]);
    expect(usesOf(model, 5)).toEqual([termLabel('fieldKey', 'DESCRIPTION', 'Omschrijving')]);
    expect(usesOf(model, 6)).toEqual([]);
    expect(model.missing).toEqual([]);
  });

  it('een verwijzing met spaties of andere hoofdletters in de versie vindt dezelfde kolom', () => {
    const model = buildColumnPreviewModel(preview(['LEV', 'GRP', 'ref', 'PRIJS']), revision({ identitySupplierReferenceField: '  REF ' }));
    expect(usesOf(model, 3)).toEqual(['Referentie']);
  });

  it('één kolom kan naar meerdere plaatsen gaan (extra veld én filter)', () => {
    const model = buildColumnPreviewModel(
      preview(['LEV', 'GRP', 'REF', 'PRIJS', 'EAN']),
      revision({ mappings: [mapping(1, 'EAN', 'ean')], filters: [filter(1, 'Ean'), filter(2, 'EAN', { sequenceNumber: 2 })] }),
    );
    expect(usesOf(model, 5)).toEqual([termLabel('fieldKey', 'EAN', 'Extra veld'), 'Filter 1', 'Filter 2']);
  });

  it('een uitsluitingsgroep heet "Uitsluitingsgroep n" en telt één keer per kolom', () => {
    const model = buildColumnPreviewModel(
      preview(['LEV', 'GRP', 'REF', 'PRIJS', 'LAND']),
      revision({ filters: [filter(1, 'LAND', { matchGroup: 3 }), filter(2, 'land', { matchGroup: 3 })] }),
    );
    expect(usesOf(model, 5)).toEqual(['Uitsluitingsgroep 3']);
  });

  it('een kolom zonder verwijzing is niet gebruikt', () => {
    const model = buildColumnPreviewModel(preview(['LEV', 'GRP', 'REF', 'PRIJS', 'RUIS']), revision());
    expect(model.columns.find((column) => column.position === 5)!.uses).toEqual([]);
  });

  it('een extra veld met een vaste waarde (zonder bron) leest geen kolom', () => {
    const model = buildColumnPreviewModel(
      preview(['LEV', 'GRP', 'REF', 'PRIJS', 'EAN']),
      revision({ mappings: [mapping(1, 'EAN', '', { fixedValue: '123' })] }),
    );
    expect(usesOf(model, 5)).toEqual([]);
    expect(model.missing).toEqual([]);
  });
});

describe('herkenning op nummer (COLUMN_INDEX)', () => {
  const indexRevision = (over: Record<string, unknown> = {}) =>
    revision({
      structureFieldReferenceKind: 'COLUMN_INDEX',
      structureHasHeader: false,
      identitySupplierField: '1',
      identitySupplierGroupField: '2',
      identitySupplierReferenceField: '3',
      recordBasePriceField: '4',
      recordDescriptionField: null,
      ...over,
    });

  it('telt vanaf 1 en geeft kolommen zonder kopregel geen naam', () => {
    const model = buildColumnPreviewModel(preview([null, null, null, null, null]), indexRevision());
    expect(usesOf(model, 1)).toEqual(['Leverancier']);
    expect(usesOf(model, 3)).toEqual(['Referentie']);
    expect(usesOf(model, 4)).toEqual([termLabel('fieldKey', 'BASE_PRICE', 'Basisprijs')]);
    expect(usesOf(model, 5)).toEqual([]);
    expect(model.columns[0]!.header).toBeNull();
  });

  it('een nummer buiten het bestand wijst niets aan en staat onder "verwacht maar niet in het bestand"', () => {
    const model = buildColumnPreviewModel(preview([null, null, null, null]), indexRevision({ identityDiscountCodeField: '9' }));
    expect(model.columns.every((column) => column.uses.every((use) => use.label !== 'Kortingscode'))).toBe(true);
    expect(model.missing).toEqual([{ label: 'Kortingscode', reference: '9', byName: false }]);
  });

  it('nummer 0 en een niet-numerieke verwijzing wijzen niets aan', () => {
    expect(resolvePosition('0', 'COLUMN_INDEX', preview([null, null]).columns)).toBeNull();
    expect(resolvePosition('abc', 'COLUMN_INDEX', preview([null, null]).columns)).toBeNull();
    expect(resolvePosition(' 2 ', 'COLUMN_INDEX', preview([null, null]).columns)).toBe(2);
  });

  it('een nummer voorbij een afgekapte kolomlijst wordt niet als ontbrekend beweerd', () => {
    const truncated = preview([null, null, null, null], [], { columnsTruncated: true });
    const model = buildColumnPreviewModel(truncated, indexRevision({ identityDiscountCodeField: '600' }));
    expect(model.missing).toEqual([]);
  });
});

describe('verwacht maar niet in het bestand', () => {
  it('toont elke bron die niet gevonden werd, met het veld en de gezochte naam', () => {
    const model = buildColumnPreviewModel(
      preview(['LEV', 'GRP', 'PRIJS']),
      revision({ mappings: [mapping(1, 'EAN', 'EANCODE')], filters: [filter(1, 'LAND')] }),
    );
    expect(model.missing).toEqual([
      { label: 'Referentie', reference: 'REF', byName: true },
      { label: 'Omschrijving', reference: 'OMSCHRIJVING', byName: true },
      { label: termLabel('fieldKey', 'EAN', 'Extra veld'), reference: 'EANCODE', byName: true },
      { label: 'Filter 1', reference: 'LAND', byName: true },
    ]);
  });

  it('zonder kolommen (onleesbare kopregel) wordt niets als ontbrekend gemeld', () => {
    const model = buildColumnPreviewModel(preview([], [['a', 'b']]), revision());
    expect(model.missing).toEqual([]);
    expect(model.columns).toEqual([]);
  });
});

describe('voorbeeldwaarden', () => {
  it('neemt de eerste drie niet-lege waarden van de kolom', () => {
    const p = preview(['A', 'B'], [['1', ''], ['  ', 'x'], ['2', 'y'], ['3', 'z'], ['4', 'w']]);
    expect(samplesOf(p, 1)).toEqual([
      { kind: 'value', text: '1' },
      { kind: 'value', text: '2' },
      { kind: 'value', text: '3' },
    ]);
    expect(samplesOf(p, 2)).toEqual([
      { kind: 'value', text: 'x' },
      { kind: 'value', text: 'y' },
      { kind: 'value', text: 'z' },
    ]);
  });

  it('markeert een onleesbare regel onderweg en slaat een te korte regel over', () => {
    const p = preview(['A', 'B'], [['1'], 'unreadable', ['2', 'y']]);
    expect(samplesOf(p, 2)).toEqual([
      { kind: 'unreadable', lineNumber: 3 },
      { kind: 'value', text: 'y' },
    ]);
    expect(unreadableLines(p)).toEqual([3]);
  });

  it('een kolom zonder niet-lege waarden heeft geen voorbeelden', () => {
    expect(samplesOf(preview(['A'], [[''], ['']]), 1)).toEqual([]);
  });
});

describe('verouderd voorbeeld', () => {
  it('is niet verouderd als de bestandsopbouw gelijk is (tekenset hoofdletterongevoelig)', () => {
    const p = preview(['A']);
    p.layout.charset = 'utf-8';
    expect(isPreviewOutdated(p, revision())).toBe(false);
  });

  it.each([
    ['tekenset', { structureCharset: 'ISO-8859-1' }],
    ['scheidingsteken', { structureDelimiter: ',' }],
    ['aanhalingsteken', { structureQuoteChar: null }],
    ['kopregel aan/uit', { structureHasHeader: false }],
    ['kopregelnummer', { structureHeaderLineNumber: 2 }],
  ])('is verouderd na een andere %s', (_name, over) => {
    expect(isPreviewOutdated(preview(['A']), revision(over))).toBe(true);
    expect(buildColumnPreviewModel(preview(['A']), revision(over)).outdated).toBe(true);
  });

  it('het kopregelnummer telt niet mee zonder kopregel', () => {
    const p = preview([null, null]);
    expect(isPreviewOutdated(p, revision({ structureHasHeader: false, structureHeaderLineNumber: 5 }))).toBe(false);
  });

  it('een ongeldig onderdeel in het voorbeeld (null) verschilt van de actuele waarde', () => {
    const p = preview(['A']);
    p.layout.delimiter = null;
    expect(isPreviewOutdated(p, revision())).toBe(true);
  });
});

describe('tableItems: samenvatten van niet-gebruikte kolommen', () => {
  function columns(count: number, used: number[]) {
    return Array.from({ length: count }, (_, index) => ({
      position: index + 1,
      header: null,
      samples: [],
      uses: used.includes(index + 1) ? [{ kind: 'mapping' as const, label: 'X' }] : [],
    }));
  }

  it('vat meer dan vijf opeenvolgende niet-gebruikte kolommen samen', () => {
    const items = tableItems(columns(30, [1, 2, 30]));
    expect(items.map((item) => item.kind)).toEqual(['column', 'column', 'run', 'column']);
    const run = items[2]!;
    expect(run.kind === 'run' && [run.from, run.to, run.columns.length]).toEqual([3, 29, 27]);
  });

  it('toont een reeks van precies vijf nog afzonderlijk, en zes samengevat', () => {
    expect(tableItems(columns(7, [1, 7])).every((item) => item.kind === 'column')).toBe(true);
    const six = tableItems(columns(8, [1, 8]));
    expect(six.map((item) => item.kind)).toEqual(['column', 'run', 'column']);
  });

  it('vat ook een reeks aan het einde samen', () => {
    const items = tableItems(columns(10, [1]));
    expect(items.map((item) => item.kind)).toEqual(['column', 'run']);
  });
});

describe('sourceForColumn', () => {
  it('geeft de naam bij herkenning op naam en het nummer bij nummer of zonder naam', () => {
    expect(sourceForColumn({ position: 3, header: ' Artnr ' }, 'HEADER_NAME')).toBe('Artnr');
    expect(sourceForColumn({ position: 3, header: 'Artnr' }, 'COLUMN_INDEX')).toBe('3');
    expect(sourceForColumn({ position: 3, header: null }, 'HEADER_NAME')).toBe('3');
  });
});
