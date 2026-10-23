/**
 * E3 — Filters als zinnen in "Bestand en velden" (beslissingslog 2026-10-23 "Nieuwe bewerkervaring"): de zinnen per
 * uitkomst/vergelijking/groep met gedempte achtervoegsels, de ankerchips (tellingen en scrollen), Filter toevoegen
 * (`POST .../filters`), Verwijderen met bevestiging, Wijzigen = toevoegen en dan verwijderen (gap G2, met het foutpad "beide
 * bestaan"), Uitsluitingsgroep toevoegen (bestaande dialoog) en alleen-lezen op een actieve versie.
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import {
  definition,
  emptySetupState,
  installSetupServer,
  jsonResponse,
  link,
  organisation,
  renderRoutes,
  revisionDetail,
  task,
  type SetupState,
} from './setupServer';
import type { FilterView } from '../api/types';
import {
  filterSentence,
  groupSentence,
  previewSentence,
  emptyFilterForm,
  buildFilterRequest,
  filterProblems,
} from '../features/setup/filterSentenceModel';

const originalFetch = global.fetch;

afterEach(() => {
  cleanup();
  sessionStorage.clear();
  global.fetch = originalFetch;
  vi.restoreAllMocks();
});

function filter(id: number, over: Partial<FilterView> = {}): FilterView {
  return {
    id,
    revisionId: 100,
    sequenceNumber: id - 7000,
    sourceReference: '3',
    operator: 'NOT_EQUALS',
    compareValue: 'BENL',
    outcome: 'EXCLUDE',
    matchGroup: null,
    caseSensitive: false,
    trimBeforeCompare: true,
    nullBehaviour: 'EXCLUDE',
    missingColumnBehaviour: 'BLOCK',
    ...over,
  };
}

describe('zinnen (model)', () => {
  it('uitkomst bepaalt het begin van de zin', () => {
    expect(filterSentence(filter(7001), 'COLUMN_INDEX').text).toBe('Sla regels over waar kolom 3 is niet gelijk aan "BENL"');
    expect(filterSentence(filter(7001, { outcome: 'REJECT' }), 'COLUMN_INDEX').text).toBe(
      'Verwerp regels waar kolom 3 is niet gelijk aan "BENL"',
    );
    expect(filterSentence(filter(7001, { outcome: 'INCLUDE' }), 'COLUMN_INDEX').text).toBe(
      'Neem alleen regels mee waar kolom 3 is niet gelijk aan "BENL"',
    );
  });

  it('elke vergelijking gebruikt het woord van het woordenboek, een kolomnaam staat tussen aanhalingstekens', () => {
    const words: Record<string, string> = {
      EQUALS: 'is gelijk aan',
      NOT_EQUALS: 'is niet gelijk aan',
      BEGINS_WITH: 'begint met',
      ENDS_WITH: 'eindigt op',
      CONTAINS: 'bevat',
      NOT_CONTAINS: 'bevat niet',
    };
    for (const [operator, word] of Object.entries(words)) {
      expect(filterSentence(filter(7001, { operator, sourceReference: 'Status' }), 'HEADER_NAME').text).toBe(
        `Sla regels over waar kolom 'Status' ${word} "BENL"`,
      );
    }
  });

  it('bijzonderheden komen als gedempt achtervoegsel; standaardwaarden niet', () => {
    expect(filterSentence(filter(7001), 'COLUMN_INDEX').suffix).toBeNull();
    expect(filterSentence(filter(7001, { caseSensitive: true }), 'COLUMN_INDEX').suffix).toBe('hoofdlettergevoelig');
    expect(filterSentence(filter(7001, { nullBehaviour: 'REJECT' }), 'COLUMN_INDEX').suffix).toBe('lege cel: regel verwerpen');
    expect(filterSentence(filter(7001, { nullBehaviour: 'COMPARE_AS_EMPTY' }), 'COLUMN_INDEX').suffix).toBe(
      'lege cel: telt als lege tekst',
    );
    expect(filterSentence(filter(7001, { missingColumnBehaviour: 'EXCLUDE' }), 'COLUMN_INDEX').suffix).toBe(
      'ontbrekende kolom: alle regels overslaan',
    );
    expect(
      filterSentence(filter(7001, { caseSensitive: true, nullBehaviour: 'REJECT', missingColumnBehaviour: 'REJECT' }), 'COLUMN_INDEX').suffix,
    ).toBe('hoofdlettergevoelig, lege cel: regel verwerpen, ontbrekende kolom: alle regels verwerpen');
    expect(filterSentence(filter(7001, { trimBeforeCompare: false }), 'COLUMN_INDEX').suffix).toBe('spaties blijven staan');
  });

  it('een groep is één zin met én en "(alle voorwaarden samen)"; een lege waarde betekent "is leeg"', () => {
    const rows = [
      filter(7001, { matchGroup: 2, operator: 'EQUALS', compareValue: 'X', nullBehaviour: 'COMPARE_AS_EMPTY' }),
      filter(7002, { matchGroup: 2, operator: 'EQUALS', compareValue: '', sourceReference: '5', nullBehaviour: 'COMPARE_AS_EMPTY', caseSensitive: true }),
    ];
    const sentence = groupSentence(rows, 'COLUMN_INDEX');
    expect(sentence.text).toBe('Sla regels over waar kolom 3 is gelijk aan "X" én kolom 5 is leeg (alle voorwaarden samen)');
    expect(sentence.suffix).toBe('hoofdlettergevoelig bij sommige voorwaarden');
  });

  it('formulier: voorbeeldzin, vormcontrole en de POST-body', () => {
    const form = { ...emptyFilterForm(), column: ' 3 ', value: 'BENL', operator: 'NOT_EQUALS', caseSensitive: true };
    expect(previewSentence(form, 'COLUMN_INDEX').text).toBe('Sla regels over waar kolom 3 is niet gelijk aan "BENL"');
    expect(filterProblems(emptyFilterForm())).toEqual(['Vul de kolom in.', 'Vul de waarde in.']);
    expect(filterProblems(form)).toEqual([]);
    expect(buildFilterRequest(form, 'An Beslisser', null)).toEqual({
      sequenceNumber: null,
      sourceReference: '3',
      operator: 'NOT_EQUALS',
      compareValue: 'BENL',
      outcome: 'EXCLUDE',
      caseSensitive: true,
      nullBehaviour: 'EXCLUDE',
      missingColumnBehaviour: 'BLOCK',
      createdBy: 'An Beslisser',
      matchGroup: null,
    });
  });
});

// --- scherm ---

const SINGLE = filter(7001);
const REJECTING = filter(7002, { outcome: 'REJECT', sourceReference: '40', operator: 'CONTAINS', compareValue: 'X', caseSensitive: true });
const GROUP_A = filter(7003, { matchGroup: 2, operator: 'EQUALS', compareValue: 'A', sourceReference: '6', nullBehaviour: 'COMPARE_AS_EMPTY' });
const GROUP_B = filter(7004, { matchGroup: 2, operator: 'EQUALS', compareValue: '', sourceReference: '7', nullBehaviour: 'COMPARE_AS_EMPTY' });

function mapping(id: number, code: string, over: Record<string, unknown> = {}) {
  return {
    id,
    revisionId: 100,
    sequenceNumber: id - 5000,
    targetFieldCode: code,
    sourceReference: 'KOLOM',
    valueKind: 'SOURCE_FIELD',
    criticality: 'NON_CRITICAL',
    expectedPosition: null,
    fixedValue: null,
    defaultValue: null,
    required: false,
    maxLength: null,
    decimalScale: null,
    zeroAllowed: false,
    negativeAllowed: false,
    transformKind: 'NONE',
    transformConfig: null,
    valueMap: [],
    steps: [],
    ...over,
  };
}

function state(filters: FilterView[], detail: Record<string, unknown> = {}): SetupState {
  const s = emptySetupState();
  s.organisations = [organisation(1, { code: 'VROOAM', name: 'Vrooam BV' })];
  s.definitions = [
    definition(10, 1, { name: 'Vrooam artikelbestand', activeRevisionId: 99, draftRevisionId: 100, draftRevisionNumber: 4 }),
  ];
  s.links = [link(501, 10)];
  s.tasks = [task(7, 501, 10, { activeRevisionId: 99 })];
  s.revisions[10] = [
    { id: 100, definitionId: 10, revisionNumber: 4, status: 'DRAFT' },
    { id: 99, definitionId: 10, revisionNumber: 3, status: 'ACTIVE' },
  ];
  s.details[99] = revisionDetail(99, 10, {
    revisionNumber: 3,
    status: 'ACTIVE',
    structureFieldReferenceKind: 'COLUMN_INDEX',
    filters: filters.map((row) => ({ ...row, revisionId: 99 })),
  });
  s.details[100] = revisionDetail(100, 10, {
    revisionNumber: 4,
    status: 'DRAFT',
    structureFieldReferenceKind: 'COLUMN_INDEX',
    identityUppercase: false,
    filters,
    ...detail,
  });
  return s;
}

type Call = { method: string; url: string; body: Record<string, unknown> | null };
type Options = { post?: () => Response; del?: (url: string) => Response };

function serve(s: SetupState, options: Options = {}): Call[] {
  const calls: Call[] = [];
  installSetupServer(s, (url, init) => {
    const method = (init?.method ?? 'GET').toUpperCase();
    if (!/\/setup\/revisions\/100\/(filters|mappings)(\/\d+)?$/.test(url) || method === 'GET') {
      return null;
    }
    const body = init?.body === undefined ? null : (JSON.parse(String(init.body)) as Record<string, unknown>);
    calls.push({ method, url, body });
    if (method === 'POST') {
      return options.post?.() ?? jsonResponse({ ...(body ?? {}), id: 8001, revisionId: 100, sequenceNumber: 9 }, 201);
    }
    return options.del?.(url) ?? new Response(null, { status: 204 });
  });
  return calls;
}

async function openEditor(s: SetupState, options: Options = {}, path = '/setup/suppliers/1/fields?definitie=10&versie=100&bewerken=1') {
  const calls = serve(s, options);
  renderRoutes(path);
  await screen.findByTestId('read-filters');
  return calls;
}

function sentences(): string[] {
  return screen.getAllByTestId('filter-sentence').map((node) => node.textContent ?? '');
}

describe('sectie Filters', () => {
  it('toont elk filter als zin, de groep als één zin en de bijzonderheden gedempt', async () => {
    await openEditor(state([SINGLE, REJECTING, GROUP_A, GROUP_B]), {}, '/setup/suppliers/1/fields?definitie=10&versie=100');
    expect(sentences()).toEqual([
      'Sla regels over waar kolom 3 is niet gelijk aan "BENL"',
      'Verwerp regels waar kolom 40 bevat "X"',
      'Sla regels over waar kolom 6 is gelijk aan "A" én kolom 7 is leeg (alle voorwaarden samen)',
    ]);
    const suffixes = screen.getAllByTestId('filter-suffix').map((node) => node.textContent);
    expect(suffixes).toEqual(['(hoofdlettergevoelig)']);
    // Lezen: geen knoppen.
    expect(screen.queryByTestId('filter-add')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Verwijderen: / })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Wijzigen: filter/ })).not.toBeInTheDocument();
  });

  it('zonder filters staat er dat alle regels verwerkt worden', async () => {
    await openEditor(state([]));
    expect(screen.getByTestId('read-filters')).toHaveTextContent('Geen filters: alle regels worden verwerkt.');
  });

  it('een actieve versie is alleen-lezen: geen knoppen, ook niet met ?bewerken=1', async () => {
    await openEditor(state([SINGLE]), {}, '/setup/suppliers/1/fields?definitie=10&versie=99&bewerken=1');
    expect(sentences()).toHaveLength(1);
    expect(screen.getByTestId('frozen-note')).toBeInTheDocument();
    expect(screen.queryByTestId('filter-add')).not.toBeInTheDocument();
    expect(screen.queryByTestId('add-filter-group')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Verwijderen: / })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Wijzigen: filter/ })).not.toBeInTheDocument();
  });

  it('Bewerken toont per zin Wijzigen en Verwijderen, een groep enkel Verwijderen', async () => {
    await openEditor(state([SINGLE, GROUP_A, GROUP_B]));
    expect(screen.getByRole('button', { name: 'Wijzigen: filter 1' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Verwijderen: filter 1' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Verwijderen: uitsluitingsgroep 2' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Wijzigen: uitsluitingsgroep 2' })).not.toBeInTheDocument();
    expect(screen.getByTestId('filter-add')).toHaveTextContent('Filter toevoegen');
  });
});

describe('ankerchips', () => {
  it('tonen de tellingen en scrollen naar de sectie', async () => {
    const scrolled: string[] = [];
    Element.prototype.scrollIntoView = function (this: Element) {
      scrolled.push(this.id);
    };
    await openEditor(
      state([SINGLE, REJECTING], {
        mappings: [mapping(5001, 'BRAND', { valueMap: [{ source: 'A', target: 'B' }] }), mapping(5002, 'EAN')],
        bookmarks: [{ id: 1, revisionId: 100, name: 'LEV', label: 'Leverancier', description: null, dataType: 'TEXT', valueScope: 'LINK', ownerRole: 'X', required: true, defaultValue: null, allowedValues: null, validationPattern: null, sortOrder: 1, usages: [] }],
      }),
      {},
      '/setup/suppliers/1/fields?definitie=10&versie=100',
    );
    const nav = screen.getByRole('navigation', { name: 'Naar sectie' });
    const labels = within(nav)
      .getAllByRole('button')
      .map((button) => button.textContent);
    // Velden: 4 herkenning/basis (LEV, GRP, REF, PRIJS) + omschrijving + 2 extra = 7.
    expect(labels).toEqual(['Velden 7', 'Filters 2', 'Vertalingen 1', 'Bestandsopbouw', 'Drempels', 'Invulpunten 1']);

    fireEvent.click(within(nav).getByRole('button', { name: 'Filters 2' }));
    fireEvent.click(within(nav).getByRole('button', { name: 'Invulpunten 1' }));
    fireEvent.click(within(nav).getByRole('button', { name: 'Bestandsopbouw' }));
    fireEvent.click(within(nav).getByRole('button', { name: 'Velden 7' }));
    expect(scrolled).toEqual(['ed-filters', 'ed-invulpunten', 'ed-bestand', 'ed-velden']);
  });
});

describe('Filter toevoegen', () => {
  it('opent de zinnenbouwer; Toepassen stuurt de POST-body en toont "Bewaard in het concept"', async () => {
    const calls = await openEditor(state([]));
    fireEvent.click(screen.getByTestId('filter-add'));
    const panel = await screen.findByRole('dialog', { name: 'Filter toevoegen' });

    // Bij herkenning op nummer heet het veld Kolomnummer.
    fireEvent.change(within(panel).getByLabelText('Kolomnummer'), { target: { value: '3' } });
    fireEvent.change(within(panel).getByTestId('filter-operator'), { target: { value: 'NOT_EQUALS' } });
    fireEvent.change(within(panel).getByLabelText('Waarde'), { target: { value: 'BENL' } });
    expect(within(panel).getByTestId('filter-preview')).toHaveTextContent('Sla regels over waar kolom 3 is niet gelijk aan "BENL"');

    fireEvent.change(within(panel).getByTestId('filter-outcome'), { target: { value: 'REJECT' } });
    fireEvent.click(within(panel).getByTestId('panel-more'));
    fireEvent.click(within(panel).getByTestId('filter-case'));
    fireEvent.change(within(panel).getByTestId('filter-null'), { target: { value: 'REJECT' } });
    fireEvent.change(within(panel).getByTestId('filter-missing'), { target: { value: 'EXCLUDE' } });
    expect(within(panel).getByTestId('filter-preview')).toHaveTextContent(
      'Verwerp regels waar kolom 3 is niet gelijk aan "BENL" (hoofdlettergevoelig, lege cel: regel verwerpen, ontbrekende kolom: alle regels overslaan)',
    );
    fireEvent.click(within(panel).getByTestId('panel-apply'));

    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0]!.method).toBe('POST');
    expect(calls[0]!.url).toMatch(/\/setup\/revisions\/100\/filters$/);
    expect(calls[0]!.body).toEqual({
      sequenceNumber: null,
      sourceReference: '3',
      operator: 'NOT_EQUALS',
      compareValue: 'BENL',
      outcome: 'REJECT',
      caseSensitive: true,
      nullBehaviour: 'REJECT',
      missingColumnBehaviour: 'EXCLUDE',
      createdBy: 'An Beslisser',
      matchGroup: null,
    });
    expect(await screen.findByTestId('save-toast')).toHaveTextContent('Bewaard in het concept');
    await waitFor(() => expect(screen.queryByTestId('field-panel')).not.toBeInTheDocument());
  });

  it('bij herkenning op naam heet het veld Kolomnaam; een lege kolom of waarde stuurt niets', async () => {
    const calls = await openEditor(state([], { structureFieldReferenceKind: 'HEADER_NAME' }));
    fireEvent.click(screen.getByTestId('filter-add'));
    const panel = await screen.findByRole('dialog', { name: 'Filter toevoegen' });
    expect(within(panel).getByLabelText('Kolomnaam')).toBeInTheDocument();
    // Alle zes echte vergelijkingen, geen "is leeg".
    expect(within(within(panel).getByTestId('filter-operator')).getAllByRole('option').map((o) => o.textContent)).toEqual([
      'is gelijk aan',
      'is niet gelijk aan',
      'begint met',
      'eindigt op',
      'bevat',
      'bevat niet',
    ]);
    fireEvent.click(within(panel).getByTestId('panel-apply'));
    expect(within(panel).getByTestId('panel-problems')).toHaveTextContent('Vul de kolom in.');
    expect(within(panel).getByTestId('panel-problems')).toHaveTextContent('Vul de waarde in.');
    expect(calls).toHaveLength(0);
  });

  it('een serverfout blijft in het paneel staan', async () => {
    const calls = await openEditor(state([]), {
      post: () => jsonResponse({ error: 'bezet', code: 'FILTER_SEQUENCE_IN_USE' }, 409),
    });
    fireEvent.click(screen.getByTestId('filter-add'));
    const panel = await screen.findByRole('dialog', { name: 'Filter toevoegen' });
    fireEvent.change(within(panel).getByLabelText('Kolomnummer'), { target: { value: '3' } });
    fireEvent.change(within(panel).getByLabelText('Waarde'), { target: { value: 'X' } });
    fireEvent.click(within(panel).getByTestId('panel-apply'));
    await waitFor(() => expect(calls).toHaveLength(1));
    await waitFor(() => expect(within(panel).getByTestId('panel-apply')).not.toBeDisabled());
    expect(screen.queryByTestId('save-toast')).not.toBeInTheDocument();
    expect(screen.getByTestId('field-panel')).toBeInTheDocument();
  });
});

describe('Verwijderen', () => {
  it('vraagt een bevestiging; Behouden verwijdert niets, Verwijderen stuurt de DELETE', async () => {
    const calls = await openEditor(state([SINGLE, REJECTING]));
    fireEvent.click(screen.getByRole('button', { name: 'Verwijderen: filter 1' }));
    const confirm = await screen.findByRole('dialog', { name: 'Filter verwijderen?' });
    expect(confirm).toHaveTextContent('Sla regels over waar kolom 3 is niet gelijk aan "BENL"');
    fireEvent.click(within(confirm).getByRole('button', { name: 'Behouden' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Filter verwijderen?' })).not.toBeInTheDocument());
    expect(calls).toHaveLength(0);

    fireEvent.click(screen.getByRole('button', { name: 'Verwijderen: filter 1' }));
    const again = await screen.findByRole('dialog', { name: 'Filter verwijderen?' });
    fireEvent.click(within(again).getByRole('button', { name: 'Verwijderen' }));
    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0]).toMatchObject({ method: 'DELETE' });
    expect(calls[0]!.url).toMatch(/\/setup\/revisions\/100\/filters\/7001$/);
    expect(await screen.findByTestId('save-toast')).toHaveTextContent('Bewaard in het concept');
  });

  it('een 409 (invulpunt steunt erop) blijft in de dialoog staan', async () => {
    const calls = await openEditor(state([SINGLE]), {
      del: () => jsonResponse({ error: 'bookmark', code: 'CONFIG_BOOKMARK_PLACE_UNRESOLVED' }, 409),
    });
    fireEvent.click(screen.getByRole('button', { name: 'Verwijderen: filter 1' }));
    const confirm = await screen.findByRole('dialog', { name: 'Filter verwijderen?' });
    fireEvent.click(within(confirm).getByRole('button', { name: 'Verwijderen' }));
    await waitFor(() => expect(calls).toHaveLength(1));
    expect(await within(confirm).findByText('Een invulpunt steunt nog op deze rij')).toBeInTheDocument();
    expect(screen.queryByTestId('save-toast')).not.toBeInTheDocument();
  });

  it('een groep verwijdert al haar voorwaarden; een mislukte tweede DELETE meldt dat er een deel overblijft', async () => {
    let seen = 0;
    const calls = await openEditor(state([GROUP_A, GROUP_B]), {
      del: () => {
        seen += 1;
        return seen === 2 ? jsonResponse({ error: 'bookmark', code: 'CONFIG_BOOKMARK_PLACE_UNRESOLVED' }, 409) : new Response(null, { status: 204 });
      },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Verwijderen: uitsluitingsgroep 2' }));
    const confirm = await screen.findByRole('dialog', { name: 'Uitsluitingsgroep verwijderen?' });
    fireEvent.click(within(confirm).getByRole('button', { name: 'Verwijderen' }));
    await waitFor(() => expect(calls).toHaveLength(2));
    expect(calls.map((call) => call.url.split('/').pop())).toEqual(['7003', '7004']);
    expect(await within(confirm).findByTestId('filter-delete-partial')).toHaveTextContent('1 van de 2 voorwaarden verwijderd');
  });
});

describe('Wijzigen = toevoegen en dan verwijderen', () => {
  async function openChange(options: Options = {}) {
    const calls = await openEditor(state([SINGLE]), options);
    fireEvent.click(screen.getByRole('button', { name: 'Wijzigen: filter 1' }));
    const panel = await screen.findByRole('dialog', { name: 'Wijzigen: filter 1' });
    // Het paneel start met de bestaande waarden.
    expect(within(panel).getByLabelText('Kolomnummer')).toHaveValue('3');
    expect(within(panel).getByLabelText('Waarde')).toHaveValue('BENL');
    return { calls, panel };
  }

  it('zonder wijziging stuurt Toepassen niets', async () => {
    const { calls, panel } = await openChange();
    fireEvent.click(within(panel).getByTestId('panel-apply'));
    expect(within(panel).getByTestId('panel-problem')).toHaveTextContent('Er is niets gewijzigd.');
    expect(calls).toHaveLength(0);
  });

  it('stuurt eerst de POST en pas daarna de DELETE van het oude filter', async () => {
    const { calls, panel } = await openChange();
    fireEvent.change(within(panel).getByLabelText('Waarde'), { target: { value: 'FRNL' } });
    fireEvent.click(within(panel).getByTestId('panel-apply'));
    await waitFor(() => expect(calls).toHaveLength(2));
    expect(calls.map((call) => call.method)).toEqual(['POST', 'DELETE']);
    expect(calls[0]!.body).toMatchObject({ sourceReference: '3', compareValue: 'FRNL', trimBeforeCompare: true, createdBy: 'An Beslisser' });
    expect(calls[1]!.url).toMatch(/\/setup\/revisions\/100\/filters\/7001$/);
    expect(await screen.findByTestId('save-toast')).toHaveTextContent('Bewaard in het concept');
    await waitFor(() => expect(screen.queryByTestId('field-panel')).not.toBeInTheDocument());
  });

  it('mislukt de POST, dan wordt er niets verwijderd', async () => {
    const { calls, panel } = await openChange({ post: () => jsonResponse({ error: 'bezet', code: 'FILTER_SEQUENCE_IN_USE' }, 409) });
    fireEvent.change(within(panel).getByLabelText('Waarde'), { target: { value: 'FRNL' } });
    fireEvent.click(within(panel).getByTestId('panel-apply'));
    await waitFor(() => expect(calls).toHaveLength(1));
    await waitFor(() => expect(within(panel).getByTestId('panel-apply')).not.toBeDisabled());
    expect(calls.map((call) => call.method)).toEqual(['POST']);
    expect(screen.queryByTestId('filter-both-exist')).not.toBeInTheDocument();
    expect(screen.getByTestId('field-panel')).toBeInTheDocument();
  });

  it('mislukt de DELETE na een geslaagde POST, dan zegt het paneel dat beide filters nu bestaan', async () => {
    const { calls, panel } = await openChange({
      del: () => jsonResponse({ error: 'bookmark', code: 'CONFIG_BOOKMARK_PLACE_UNRESOLVED' }, 409),
    });
    fireEvent.change(within(panel).getByLabelText('Waarde'), { target: { value: 'FRNL' } });
    fireEvent.click(within(panel).getByTestId('panel-apply'));
    await waitFor(() => expect(calls).toHaveLength(2));
    const both = await within(panel).findByTestId('filter-both-exist');
    expect(both).toHaveTextContent('Beide filters staan nu in het concept');
    // Geen tweede Toepassen (geen derde filter), wel Sluiten.
    expect(within(panel).queryByTestId('panel-apply')).not.toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: 'Sluiten' })).toBeInTheDocument();
    expect(screen.queryByTestId('save-toast')).not.toBeInTheDocument();
  });
});

describe('Uitsluitingsgroep toevoegen en vertalingen', () => {
  it('opent de bestaande groepsdialoog', async () => {
    await openEditor(state([]));
    fireEvent.click(screen.getByTestId('add-filter-group'));
    expect(screen.getByRole('button', { name: 'Groep toevoegen' })).toBeInTheDocument();
  });

  it('"Wijzigen bij het veld" opent het paneel van het extra veld, alleen in Bewerken', async () => {
    const s = state([], { mappings: [mapping(5001, 'BRAND', { transformKind: 'MAP', valueMap: [{ source: 'A', target: 'B' }] })] });
    await openEditor(s, {}, '/setup/suppliers/1/fields?definitie=10&versie=100');
    const section = screen.getByTestId('read-translations');
    expect(section).toHaveTextContent('1 waarde · bij een onbekende waarde: verwerpen');
    expect(within(section).queryByRole('button')).not.toBeInTheDocument();

    fireEvent.click(screen.getByTestId('mode-edit'));
    fireEvent.click(within(screen.getByTestId('read-translations')).getByRole('button', { name: /^Wijzigen bij het veld/ }));
    expect(await screen.findByRole('dialog', { name: /^Wijzigen: / })).toBeInTheDocument();
  });
});
