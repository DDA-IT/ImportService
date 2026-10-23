/**
 * E5b — "Per kolom van het bestand": de tabel (Kolom | Kolomnaam | Voorbeeldwaarden | Gaat naar), de chips, "Niet gebruikt"
 * met "Koppelen aan veld…", de uitkomsten van het voorbeeld (ongeldige opbouw, leeg bestand, kopregel te ver), afkapping,
 * het samenvatten van lange reeksen niet-gebruikte kolommen en de schakelaar naast "Per veld".
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { ActorProvider } from '../actor/ActorContext';
import type { ColumnPreview, RevisionDetail } from '../api/types';
import { ColumnPreviewView, FieldsViewToggle } from '../features/setup/ColumnPreview';
import { RevisionReadView } from '../features/setup/RevisionReadView';
import { termLabel } from '../terms/index';
import { filter, mapping, preview, revision } from './columnPreviewFixtures';
import { TEST_IDENTITY } from './testIdentity';

afterEach(() => cleanup());

function renderPreview(p: ColumnPreview, r: RevisionDetail = revision(), props: Record<string, unknown> = {}) {
  return render(<ColumnPreviewView preview={p} revision={r} {...props} />);
}

describe('de tabel', () => {
  it('toont per kolom het nummer, de naam, de eerste drie niet-lege waarden en waar de kolom naartoe gaat', () => {
    renderPreview(
      preview(
        ['LEV', 'GRP', 'REF', 'PRIJS', 'EAN'],
        [
          ['V1', 'G1', 'A-1', '1,50', ''],
          ['V1', 'G1', 'A-2', '2,00', '5412345'],
          ['V1', 'G1', 'A-3', '3,25', '5412346'],
          ['V1', 'G1', 'A-4', '4,00', '5412347'],
        ],
      ),
      revision({ mappings: [mapping(1, 'EAN', 'ean')] }),
    );
    expect(screen.getAllByRole('columnheader').map((cell) => cell.textContent)).toEqual([
      'Kolom',
      'Kolomnaam',
      'Voorbeeldwaarden (3)',
      'Gaat naar',
    ]);
    const row3 = screen.getByTestId('column-row-3');
    expect(within(row3).getByText('REF')).toBeInTheDocument();
    expect(within(row3).getByText('A-1')).toBeInTheDocument();
    expect(within(row3).getByText('A-3')).toBeInTheDocument();
    expect(within(row3).queryByText('A-4')).toBeNull();
    // De lege eerste waarde van EAN wordt overgeslagen: de drie niet-lege waarden zijn de regels 2 t/m 4.
    const row5 = screen.getByTestId('column-row-5');
    expect(within(row5).getByText('5412345')).toBeInTheDocument();
    expect(within(row5).getByText('5412347')).toBeInTheDocument();
  });

  it('toont chips met veldnamen uit het woordenboek en "Filter n"', () => {
    renderPreview(
      preview(['LEV', 'GRP', 'REF', 'PRIJS', 'EAN', 'LAND']),
      revision({ mappings: [mapping(1, 'EAN', 'EAN')], filters: [filter(1, 'LAND'), filter(2, 'land', { matchGroup: 2 })] }),
    );
    expect(within(screen.getByTestId('column-uses-1')).getByText('Leverancier')).toBeInTheDocument();
    expect(within(screen.getByTestId('column-uses-3')).getByText('Referentie')).toBeInTheDocument();
    expect(within(screen.getByTestId('column-uses-5')).getByText(termLabel('fieldKey', 'EAN', 'x'))).toBeInTheDocument();
    const land = screen.getByTestId('column-uses-6');
    expect(within(land).getByText('Filter 1')).toBeInTheDocument();
    expect(within(land).getByText('Uitsluitingsgroep 2')).toBeInTheDocument();
  });

  it('toont een kolom zonder kopregel met "Geen kolomnaam"', () => {
    renderPreview(preview([null, null], [['a', 'b']]), revision({ structureHasHeader: false, structureFieldReferenceKind: 'COLUMN_INDEX' }));
    expect(within(screen.getByTestId('column-row-1')).getByText('Geen kolomnaam')).toBeInTheDocument();
  });

  it('markeert een onleesbare regel in de voorbeeldwaarden en meldt ze boven de tabel', () => {
    renderPreview(preview(['LEV', 'GRP'], [['a', 'b'], 'unreadable', ['c', 'd']]));
    expect(within(screen.getByTestId('column-row-1')).getByTestId('column-unreadable-1')).toHaveTextContent('Regel 3 onleesbaar');
    expect(screen.getByTestId('column-preview-unreadable')).toHaveTextContent('Regel 3 kon niet gelezen worden');
  });

  it('toont een kolom zonder niet-lege waarden zonder voorbeelden', () => {
    renderPreview(preview(['LEV', 'LEEG'], [['a', ''], ['b', '']]));
    expect(within(screen.getByTestId('column-row-2')).getByText('Geen waarden in de voorbeeldregels')).toBeInTheDocument();
  });

  it('toont nergens een ruwe code als zichtbare tekst', () => {
    renderPreview(preview(['LEV', 'GRP', 'REF', 'PRIJS', 'X'], [['a', 'b', 'c', 'd', 'e']]));
    const text = screen.getByTestId('column-preview').textContent ?? '';
    expect(text).not.toMatch(/AVAILABLE|HEADER_NAME|COLUMN_INDEX|LAYOUT_INVALID|BASE_PRICE/);
  });
});

describe('Niet gebruikt en Koppelen aan veld', () => {
  const unusedPreview = () => preview(['LEV', 'GRP', 'REF', 'PRIJS', 'Merk'], [['a', 'b', 'c', 'd', 'Acme']]);

  it('toont "Niet gebruikt" met de knop in Bewerken en geeft kolom en bron door', () => {
    const onLinkColumn = vi.fn();
    renderPreview(unusedPreview(), revision(), { editing: true, onLinkColumn });
    const cell = screen.getByTestId('column-uses-5');
    expect(within(cell).getByText('Niet gebruikt')).toBeInTheDocument();
    const link = screen.getByRole('button', { name: 'Koppelen aan veld: kolom 5' });
    expect(link).toHaveTextContent('Koppelen aan veld…');
    fireEvent.click(link);
    expect(onLinkColumn).toHaveBeenCalledTimes(1);
    expect(onLinkColumn.mock.calls[0]![0]).toEqual({ position: 5, header: 'Merk', source: 'Merk' });
    expect(onLinkColumn.mock.calls[0]![1]).toBe(link);
  });

  it('geeft bij herkenning op nummer het kolomnummer als bron', () => {
    const onLinkColumn = vi.fn();
    renderPreview(
      preview(['LEV', 'GRP', 'REF', 'PRIJS', 'Merk']),
      revision({
        structureFieldReferenceKind: 'COLUMN_INDEX',
        identitySupplierField: '1',
        identitySupplierGroupField: '2',
        identitySupplierReferenceField: '3',
        recordBasePriceField: '4',
        recordDescriptionField: null,
      }),
      { editing: true, onLinkColumn },
    );
    fireEvent.click(screen.getByTestId('column-link-5'));
    expect(onLinkColumn.mock.calls[0]![0]).toEqual({ position: 5, header: 'Merk', source: '5' });
  });

  it('toont "Niet gebruikt" zonder knop als er niet bewerkt wordt', () => {
    renderPreview(unusedPreview(), revision(), { editing: false, onLinkColumn: vi.fn() });
    expect(within(screen.getByTestId('column-uses-5')).getByText('Niet gebruikt')).toBeInTheDocument();
    expect(screen.queryByTestId('column-link-5')).toBeNull();
  });

  it('toont geen knop bij een kolom die al ergens naartoe gaat', () => {
    renderPreview(unusedPreview(), revision(), { editing: true, onLinkColumn: vi.fn() });
    expect(screen.queryByTestId('column-link-3')).toBeNull();
  });
});

describe('verwacht maar niet in het bestand', () => {
  it('toont de bronnen die het bestand niet heeft', () => {
    renderPreview(preview(['LEV', 'GRP', 'PRIJS']), revision({ filters: [filter(1, 'LAND')] }));
    const box = screen.getByTestId('column-preview-missing');
    expect(box).toHaveTextContent('Verwacht, maar niet in het bestand');
    expect(box).toHaveTextContent('Referentie: kolom “REF”');
    expect(box).toHaveTextContent('Filter 1: kolom “LAND”');
  });

  it('toont het blok niet als alles gevonden is', () => {
    renderPreview(preview(['LEV', 'GRP', 'REF', 'PRIJS', 'OMSCHRIJVING']));
    expect(screen.queryByTestId('column-preview-missing')).toBeNull();
  });

  it('noemt bij herkenning op nummer het nummer buiten het bestand', () => {
    renderPreview(
      preview([null, null, null, null]),
      revision({
        structureFieldReferenceKind: 'COLUMN_INDEX',
        structureHasHeader: false,
        identitySupplierField: '1',
        identitySupplierGroupField: '2',
        identitySupplierReferenceField: '3',
        recordBasePriceField: '4',
        recordDescriptionField: '12',
      }),
    );
    expect(screen.getByTestId('column-preview-missing')).toHaveTextContent('Omschrijving: kolom 12');
  });
});

describe('uitkomsten van het voorbeeld', () => {
  it('toont bij een ongeldige bestandsopbouw enkel de melding, zonder tabel', () => {
    renderPreview(preview([], [], { status: 'LAYOUT_INVALID', layout: { ...preview([]).layout, delimiter: null } }));
    expect(screen.getByTestId('column-preview-status')).toHaveTextContent('De bestandsopbouw van deze versie klopt niet');
    expect(screen.queryByRole('table')).toBeNull();
    expect(screen.queryByTestId('column-preview-missing')).toBeNull();
  });

  it('toont bij een leeg bestand de melding', () => {
    renderPreview(preview([], [], { status: 'FILE_EMPTY' }));
    expect(screen.getByTestId('column-preview-status')).toHaveTextContent('Het testbestand is leeg');
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('toont bij een kopregel voorbij het begin de melding met het regelnummer', () => {
    const p = preview([], [], { status: 'HEADER_BEYOND_PREVIEW' });
    p.layout.headerLineNumber = 40000;
    renderPreview(p, revision({ structureHeaderLineNumber: 40000 }));
    expect(screen.getByTestId('column-preview-status')).toHaveTextContent('De kopregel (regel 40000) ligt voorbij het begin');
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('toont bij een onleesbare kopregel (geen kolommen, wel regels) een eigen melding', () => {
    renderPreview(preview([], [['a', 'b']], { layout: { ...preview([]).layout, hasHeader: true } }));
    expect(screen.getByTestId('column-preview-status')).toHaveTextContent('De kopregel kon niet gelezen worden');
  });

  it('meldt dat het voorbeeld verouderd is als de bestandsopbouw sindsdien veranderde', () => {
    renderPreview(preview(['LEV']), revision({ structureDelimiter: ',' }));
    expect(screen.getByTestId('column-preview-outdated')).toHaveTextContent('eerdere bestandsopbouw');
    cleanup();
    renderPreview(preview(['LEV']), revision());
    expect(screen.queryByTestId('column-preview-outdated')).toBeNull();
  });

  it('toont de verouderd-melding ook bij een voorbeeld dat niet getoond kon worden', () => {
    renderPreview(preview([], [], { status: 'LAYOUT_INVALID' }), revision());
    expect(screen.getByTestId('column-preview-outdated')).toBeInTheDocument();
  });
});

describe('afkapping', () => {
  it('meldt dat alleen de eerste kolommen getoond worden', () => {
    renderPreview(preview(['A', 'B', 'C'], [], { columnsTruncated: true }));
    expect(screen.getByTestId('column-preview-columns-truncated')).toHaveTextContent('Alleen de eerste 3 kolommen worden getoond');
  });

  it('meldt dat er mogelijk meer regels zijn', () => {
    renderPreview(preview(['A'], [['1'], ['2']], { rowsTruncated: true }));
    expect(screen.getByTestId('column-preview-rows-note')).toHaveTextContent('Er zijn mogelijk meer regels in het bestand');
    expect(screen.getByTestId('column-preview-rows-note')).toHaveTextContent('eerste 2 regels');
  });

  it('meldt zonder afkapping enkel uit hoeveel regels de waarden komen', () => {
    renderPreview(preview(['A'], [['1'], ['2']]));
    expect(screen.getByTestId('column-preview-rows-note')).toHaveTextContent('uit de eerste 2 regels van het bestand');
    expect(screen.queryByTestId('column-preview-columns-truncated')).toBeNull();
  });
});

describe('lange reeksen niet-gebruikte kolommen', () => {
  const headers = Array.from({ length: 30 }, (_, index) => `K${index + 1}`);
  const usedRevision = () =>
    revision({
      identitySupplierField: 'K1',
      identitySupplierGroupField: 'K2',
      identitySupplierReferenceField: 'K3',
      recordBasePriceField: 'K4',
      recordDescriptionField: 'K30',
    });

  it('vat meer dan vijf opeenvolgende niet-gebruikte kolommen samen tot één regel', () => {
    renderPreview(preview(headers), usedRevision());
    expect(screen.getByTestId('column-run-5')).toHaveTextContent('Kolommen 5–29 (niet gebruikt)');
    expect(screen.queryByTestId('column-row-10')).toBeNull();
    expect(screen.getByTestId('column-row-4')).toBeInTheDocument();
    expect(screen.getByTestId('column-row-30')).toBeInTheDocument();
  });

  it('klapt de samengevatte kolommen open en weer dicht', () => {
    renderPreview(preview(headers), usedRevision());
    const toggle = screen.getByTestId('column-run-toggle-5');
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(toggle);
    expect(screen.getByTestId('column-row-10')).toBeInTheDocument();
    expect(screen.getByTestId('column-run-toggle-5')).toHaveAttribute('aria-expanded', 'true');
    fireEvent.click(screen.getByTestId('column-run-toggle-5'));
    expect(screen.queryByTestId('column-row-10')).toBeNull();
  });

  it('toont een reeks van vijf of minder afzonderlijk', () => {
    const rev = revision({
      identitySupplierField: 'K1',
      identitySupplierGroupField: 'K2',
      identitySupplierReferenceField: 'K3',
      recordBasePriceField: 'K4',
      recordDescriptionField: 'K10',
    });
    renderPreview(preview(headers.slice(0, 10)), rev);
    expect(screen.queryByTestId('column-run-5')).toBeNull();
    expect(screen.getByTestId('column-row-5')).toBeInTheDocument();
    expect(screen.getByTestId('column-row-9')).toBeInTheDocument();
  });
});

describe('de schakelaar Per veld | Per kolom van het bestand', () => {
  it('heeft de tweede knop uitgeschakeld zonder voorbeeld, met uitleg', () => {
    const onChange = vi.fn();
    render(<FieldsViewToggle value="field" columnAvailable={false} onChange={onChange} />);
    const byColumn = screen.getByRole('button', { name: 'Per kolom van het bestand' });
    expect(byColumn).toBeDisabled();
    expect(byColumn).toHaveAttribute('title', 'Beschikbaar nadat u een testbestand geladen hebt');
    expect(screen.getByRole('button', { name: 'Per veld' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('schakelt om tussen de twee weergaven', () => {
    const onChange = vi.fn();
    render(<FieldsViewToggle value="field" columnAvailable onChange={onChange} />);
    fireEvent.click(screen.getByRole('button', { name: 'Per kolom van het bestand' }));
    expect(onChange).toHaveBeenCalledWith('column');
  });
});

describe('in het leesbeeld (RevisionReadView)', () => {
  function renderRead(columnPreview: ColumnPreview | null | undefined, editing = false, onLinkColumn = vi.fn()) {
    render(
      <ActorProvider identity={TEST_IDENTITY}>
        <RevisionReadView revision={revision()} columnPreview={columnPreview} editing={editing} onLinkColumn={onLinkColumn} />
      </ActorProvider>,
    );
    return onLinkColumn;
  }

  it('toont geen schakelaar zonder concept (columnPreview ontbreekt)', () => {
    renderRead(undefined);
    expect(screen.queryByTestId('fields-view-toolbar')).toBeNull();
    expect(screen.getByTestId('read-fields')).toBeInTheDocument();
  });

  it('toont de schakelaar uitgeschakeld zolang er geen voorbeeld is', () => {
    renderRead(null);
    expect(screen.getByTestId('fields-view-column')).toBeDisabled();
    expect(screen.getByTestId('read-fields')).toBeInTheDocument();
  });

  it('wisselt van het veldengrid naar de kolomtabel en terug', () => {
    renderRead(preview(['LEV', 'GRP', 'REF', 'PRIJS', 'Merk'], [['a', 'b', 'c', 'd', 'e']]));
    expect(screen.queryByTestId('column-preview')).toBeNull();
    fireEvent.click(screen.getByTestId('fields-view-column'));
    expect(screen.getByTestId('column-preview')).toBeInTheDocument();
    expect(screen.queryByTestId('read-fields')).toBeNull();
    fireEvent.click(screen.getByTestId('fields-view-field'));
    expect(screen.queryByTestId('column-preview')).toBeNull();
    expect(screen.getByTestId('read-fields')).toBeInTheDocument();
  });

  it('geeft "Koppelen aan veld…" door aan de aanroeper in Bewerken', () => {
    const onLinkColumn = renderRead(preview(['LEV', 'GRP', 'REF', 'PRIJS', 'Merk']), true);
    fireEvent.click(screen.getByTestId('fields-view-column'));
    fireEvent.click(screen.getByTestId('column-link-5'));
    expect(onLinkColumn).toHaveBeenCalledTimes(1);
  });
});
