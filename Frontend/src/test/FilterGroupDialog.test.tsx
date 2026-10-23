/**
 * G2c-4 — de dialoog "Uitsluitingsgroep toevoegen" en de gegroepeerde filterlijst (`docs/design/g2-design.md` §G2c).
 * Dekt: de POST-bodies (zelfde groep, EXCLUDE/COMPARE_AS_EMPTY/EQUALS, lege vergelijkwaarde), het voorgestelde
 * groepsnummer, de vormvalidatie, een serverfout, een gedeeltelijke fout met de al aangemaakte rijen (en die rijen
 * verwijderen), de gegroepeerde modelfunctie en dat de knop enkel op een concept en met MANAGE bruikbaar is. E6: de weergave in het
 * scherm (groep als zin, knop enkel in Bewerken op een concept) staat in `FieldsEditorFilters.test.tsx`.
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ActorProvider } from '../actor/ActorContext';
import type { FilterView, RevisionDetail } from '../api/types';
import { AddFilterGroupAction } from '../features/setup/FilterGroupDialog';
import { groupFilters, groupProblems, suggestGroupNumber } from '../features/setup/filterGroupModel';
import { errorResponse, jsonResponse, mockApi } from './legacyConfigFixtures';
import { TEST_IDENTITY, testIdentityWith } from './testIdentity';

function filter(id: number, overrides: Partial<FilterView> = {}): FilterView {
  return {
    id,
    revisionId: 100,
    sequenceNumber: id,
    sourceReference: 'KOLOM',
    operator: 'EQUALS',
    compareValue: 'X',
    outcome: 'EXCLUDE',
    matchGroup: null,
    ...overrides,
  };
}

function revisionWith(filters: FilterView[], status: RevisionDetail['status'] = 'DRAFT'): RevisionDetail {
  return {
    id: 100,
    definitionId: 10,
    revisionNumber: 1,
    status,
    changeReason: 'Overname uit legacy',
    identityProfileKind: 'THREE_PART',
    identitySupplierField: 'LEV',
    identitySupplierGroupField: 'LEVGRP',
    identitySupplierReferenceField: 'LEVREF',
    identityDiscountCodeField: null,
    structureFormat: 'CSV',
    structureCharset: 'UTF-8',
    structureDelimiter: ';',
    structureQuoteChar: '"',
    structureHasHeader: true,
    structureHeaderLineNumber: 1,
    structureFieldReferenceKind: 'COLUMN_INDEX',
    accessDeliverySetKind: 'FULL_SNAPSHOT',
    recordBasePriceField: '26',
    priceControlModel: 'DEVIATION',
    createdAt: '2026-10-21T08:00:00Z',
    mappings: [],
    filters,
    fieldCriticalities: [],
    bookmarks: [],
    bookmarkValues: [],
  } as unknown as RevisionDetail;
}

type Overrides = { identity?: typeof TEST_IDENTITY; revision?: RevisionDetail };

function renderAction(handler: Parameters<typeof mockApi>[0], overrides: Overrides = {}) {
  const api = mockApi(handler);
  const onChanged = vi.fn();
  render(
    <ActorProvider identity={overrides.identity ?? TEST_IDENTITY}>
      <AddFilterGroupAction revision={overrides.revision ?? revisionWith([])} onChanged={onChanged} />
    </ActorProvider>,
  );
  return { api, onChanged };
}

let nextId = 500;
const created = (call: { body?: unknown }): Response => {
  const body = call.body as Record<string, unknown>;
  nextId += 1;
  return jsonResponse(
    {
      id: nextId,
      revisionId: 100,
      sequenceNumber: nextId,
      sourceReference: body.sourceReference,
      operator: body.operator,
      compareValue: body.compareValue,
      outcome: body.outcome,
      matchGroup: body.matchGroup,
    },
    201,
  );
};

function open() {
  fireEvent.click(screen.getByTestId('add-filter-group'));
}

function type(testId: string, value: string) {
  fireEvent.change(screen.getByTestId(testId), { target: { value } });
}

function confirm() {
  fireEvent.click(screen.getByRole('button', { name: 'Groep toevoegen' }));
}

function posts(api: ReturnType<typeof mockApi>) {
  return api.writes().filter((call) => call.method === 'POST');
}

afterEach(() => cleanup());

describe('aanmaken', () => {
  it('stuurt per rij een POST met dezelfde groep, EXCLUDE, COMPARE_AS_EMPTY en EQUALS; lege vergelijkwaarde blijft leeg', async () => {
    const { api, onChanged } = renderAction(created);
    open();
    type('filter-group-column-0', 'STATUS');
    type('filter-group-value-0', 'EOL');
    fireEvent.click(screen.getByTestId('filter-group-case-0'));
    fireEvent.click(screen.getByTestId('filter-group-add-row'));
    type('filter-group-column-1', 'OPMERKING');
    expect(screen.getByTestId('filter-group-empty-hint-1')).toHaveTextContent('Leeg veld');
    confirm();

    await waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1));
    expect(screen.queryByTestId('filter-group-dialog')).not.toBeInTheDocument();
    const calls = posts(api);
    expect(calls).toHaveLength(2);
    expect(calls[0]!.url).toContain('/setup/revisions/100/filters');
    expect(calls[0]!.body).toEqual({
      sequenceNumber: null,
      sourceReference: 'STATUS',
      operator: 'EQUALS',
      compareValue: 'EOL',
      outcome: 'EXCLUDE',
      caseSensitive: true,
      trimBeforeCompare: true,
      nullBehaviour: 'COMPARE_AS_EMPTY',
      createdBy: 'An Beslisser',
      matchGroup: 1,
    });
    expect(calls[1]!.body).toEqual({
      sequenceNumber: null,
      sourceReference: 'OPMERKING',
      operator: 'EQUALS',
      compareValue: '',
      outcome: 'EXCLUDE',
      caseSensitive: false,
      trimBeforeCompare: true,
      nullBehaviour: 'COMPARE_AS_EMPTY',
      createdBy: 'An Beslisser',
      matchGroup: 1,
    });
  });

  it('stelt het hoogste bestaande groepsnummer + 1 voor en laat het aanpassen', async () => {
    const revision = revisionWith([filter(1, { matchGroup: 2 }), filter(2, { matchGroup: 5 }), filter(3)]);
    const { api } = renderAction(created, { revision });
    open();
    expect((screen.getByTestId('filter-group-number') as HTMLInputElement).value).toBe('6');
    type('filter-group-number', '9');
    type('filter-group-column-0', 'A');
    confirm();

    await waitFor(() => expect(posts(api)).toHaveLength(1));
    expect((posts(api)[0]!.body as Record<string, unknown>).matchGroup).toBe(9);
  });

  it('toont een operator die vastligt en maximaal vier voorwaarden', () => {
    renderAction(created);
    open();
    expect(screen.getByTestId('filter-group-operator-0')).toHaveTextContent('is gelijk aan');
    for (let i = 0; i < 5; i += 1) {
      const add = screen.getByTestId('filter-group-add-row');
      if (!(add as HTMLButtonElement).disabled) {
        fireEvent.click(add);
      }
    }
    expect(screen.getByTestId('filter-group-row-3')).toBeInTheDocument();
    expect(screen.queryByTestId('filter-group-row-4')).not.toBeInTheDocument();
    expect(screen.getByTestId('filter-group-add-row')).toBeDisabled();
  });
});

describe('vormvalidatie', () => {
  it('blokkeert een lege kolom en een groepsnummer dat geen getal > 0 is, zonder iets te versturen', () => {
    const { api } = renderAction(created);
    open();
    type('filter-group-number', '0');
    const problems = screen.getByTestId('filter-group-problems');
    expect(problems).toHaveTextContent('groepsnummer');
    expect(problems).toHaveTextContent('Voorwaarde 1: vul de kolom in.');
    expect(screen.getByRole('button', { name: 'Groep toevoegen' })).toBeDisabled();
    expect(api.writes()).toHaveLength(0);
  });

  it('de pure controle: lege vergelijkwaarde is toegelaten, een negatief nummer niet', () => {
    const row = { sourceReference: 'A', compareValue: '', caseSensitive: false, trimBeforeCompare: true };
    expect(groupProblems('1', [row])).toEqual([]);
    expect(groupProblems('-1', [row])).toHaveLength(1);
    expect(groupProblems('1.5', [row])).toHaveLength(1);
    expect(groupProblems('1', [])).toHaveLength(1);
    expect(suggestGroupNumber([])).toBe(1);
  });
});

describe('fouten', () => {
  it('een serverfout bij de eerste rij toont de melding, laat de dialoog open en maakt niets aan', async () => {
    const { api, onChanged } = renderAction(() => errorResponse(400, 'MATCH_GROUP_INVALID', 'matchGroup must be positive'));
    open();
    type('filter-group-column-0', 'A');
    confirm();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Het groepsnummer klopt niet');
    expect(alert).toHaveTextContent('matchGroup must be positive');
    expect(onChanged).not.toHaveBeenCalled();
    expect(screen.queryByTestId('filter-group-partial')).not.toBeInTheDocument();
    expect(posts(api)).toHaveLength(1);
  });

  it('een mislukte tweede rij meldt de al aangemaakte rij, blokkeert opnieuw proberen en kan die rij verwijderen', async () => {
    let postCount = 0;
    const { api, onChanged } = renderAction((call) => {
      if (call.method === 'POST') {
        postCount += 1;
        return postCount === 1 ? created(call) : errorResponse(400, 'CONFIG_FILTER_INVALID', 'rule 2 is invalid');
      }
      return new Response(null, { status: 204 });
    });
    open();
    type('filter-group-column-0', 'STATUS');
    type('filter-group-value-0', 'EOL');
    fireEvent.click(screen.getByTestId('filter-group-add-row'));
    type('filter-group-column-1', 'OPMERKING');
    confirm();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('rule 2 is invalid');
    const partial = screen.getByTestId('filter-group-partial');
    expect(partial).toHaveTextContent('Voorwaarde 2 is mislukt');
    expect(partial).toHaveTextContent('kolom STATUS is gelijk aan "EOL"');
    expect(screen.getByRole('button', { name: 'Groep toevoegen' })).toBeDisabled();
    expect(posts(api)).toHaveLength(2);

    fireEvent.click(screen.getByTestId('filter-group-delete-created'));
    await waitFor(() => expect(screen.queryByTestId('filter-group-partial')).not.toBeInTheDocument());
    const deletes = api.writes().filter((call) => call.method === 'DELETE');
    expect(deletes).toHaveLength(1);
    expect(deletes[0]!.url).toMatch(/\/setup\/revisions\/100\/filters\/\d+$/);
    expect(screen.getByRole('button', { name: 'Groep toevoegen' })).toBeEnabled();

    // De ouder herlaadt bij sluiten, want er is tussentijds iets aangemaakt en verwijderd.
    fireEvent.click(screen.getByRole('button', { name: 'Sluiten' }));
    expect(onChanged).toHaveBeenCalledTimes(1);
  });

  it('sluiten na een gedeeltelijke fout zonder verwijderen herlaadt de ouder (de rijen staan er)', async () => {
    let postCount = 0;
    const { onChanged } = renderAction((call) => {
      postCount += 1;
      return postCount === 1 ? created(call) : errorResponse(409, 'FILTER_SEQUENCE_IN_USE', 'in use');
    });
    open();
    type('filter-group-column-0', 'A');
    fireEvent.click(screen.getByTestId('filter-group-add-row'));
    type('filter-group-column-1', 'B');
    confirm();
    await screen.findByTestId('filter-group-partial');
    fireEvent.click(screen.getByRole('button', { name: 'Sluiten' }));
    expect(onChanged).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId('filter-group-dialog')).not.toBeInTheDocument();
  });
});

describe('gegroepeerde weergave', () => {
  it('groepeert rijen met dezelfde matchGroup op de plaats van de eerste rij; losse rijen blijven zoals ze zijn', () => {
    const rows = [
      filter(1),
      filter(2, { matchGroup: 3, sourceReference: 'A' }),
      filter(3),
      filter(4, { matchGroup: 3, sourceReference: 'B', compareValue: '' }),
    ];
    const entries = groupFilters(rows);
    expect(entries.map((entry) => entry.kind)).toEqual(['single', 'group', 'single']);
    expect(entries[1]).toMatchObject({ kind: 'group', group: 3 });
    expect((entries[1] as { filters: FilterView[] }).filters.map((row) => row.id)).toEqual([2, 4]);
  });
});

describe('wie en wanneer', () => {
  it('zonder MANAGE is de knop uitgeschakeld', () => {
    renderAction(created, { identity: testIdentityWith('catalogImport.read') });
    expect(screen.getByTestId('add-filter-group')).toBeDisabled();
  });

  it('op een actieve versie is de knop uitgeschakeld', () => {
    renderAction(created, { revision: revisionWith([], 'ACTIVE') });
    expect(screen.getByTestId('add-filter-group')).toBeDisabled();
  });
});
