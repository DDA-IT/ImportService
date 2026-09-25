/**
 * F9 — de groepsactie (`GroupDecisionDialog`), zie `docs/design/frontend-scherm3-bundel-design.md` §10.4
 * en `docs/decisions.md` 2026-09-24 (C5).
 *
 * Het acceptatiecriterium van F9 is: "de dialoog kan geen filter versturen die van de getoonde lijst
 * afwijkt (aantoonbaar in code én met een test op de filter-naar-request-omzetting)". Dat wordt hier op
 * drie niveaus bewezen:
 * 1. `toDecisionFilter` — de pure omzetting neemt alle vijf velden letterlijk over;
 * 2. `MutationList` — de toolbar-slot krijgt exact de filter waarmee de lijst haar bron bevraagt, en
 *    nooit het aantal van een vorige filter;
 * 3. `BundleMutationsTab` met de echte API-laag — de `filter` in de POST-body is gelijk aan de
 *    queryparameters van de laatste `GET /bundles/{id}/mutations`.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Outlet, Route, Routes } from 'react-router-dom';
import { ActorProvider } from '../actor/ActorContext';
import type { BundleDetail, MutationRow, PageResult } from '../api/types';
import { MutationList } from '../components/MutationList/MutationList';
import type { MutationListToolbarContext, MutationQuery, MutationSource } from '../components/MutationList/types';
import { BundleMutationsTab } from '../features/bundles/BundleMutationsTab';
import { groupDecisionGate } from '../features/bundles/bundlePolicy';
import { toDecisionFilter } from '../features/bundles/groupDecisionFilter';

const HASH = 'ab00ab00ab00ab00ab00ab00ab00ab00ab00ab00ab00ab00ab00ab00ab00ab00';

function bundle(overrides: Partial<BundleDetail> = {}): BundleDetail {
  return {
    id: 42,
    bundleReference: 'BND-2026-001',
    description: null,
    status: 'ASSEMBLING',
    targetMode: 'SIMULATION',
    targetMoment: null,
    publicationPolicy: null,
    createdBy: 'An Beslisser',
    createdAt: '2026-09-20T09:00:00Z',
    frozenBy: null,
    frozenAt: null,
    frozenReason: null,
    cancelledBy: null,
    cancelledAt: null,
    cancelledReason: null,
    batchCount: 1,
    contentMutationCount: 2,
    readyCount: 0,
    rejectedCount: 0,
    blockedCount: 0,
    expiredCount: null,
    identityIncidentCount: 0,
    bulkIncidentCount: null,
    criticalIssueCount: null,
    warningCount: null,
    staleMutationCount: 0,
    contentHash: null,
    plannedCount: 1,
    awaitingApprovalCount: 1,
    expirableCount: 1,
    ...overrides,
  };
}

function mutation(overrides: Partial<MutationRow> = {}): MutationRow {
  return {
    id: 501,
    batchId: 77,
    actionType: 'UPDATE',
    targetDomain: 'OFFER',
    status: 'AWAITING_APPROVAL',
    statusReason: 'BULK_PRICE_INCIDENT',
    identitySupplier: 'SUP1',
    identitySupplierGroup: 'GRP1',
    identitySupplierReference: 'REF-1',
    identityDiscountCode: null,
    identityDiscountState: null,
    domainMask: 'PRICE',
    beforeBasePrice: 10,
    afterBasePrice: 11,
    basePriceCurrency: 'EUR',
    referenceType: null,
    beforeReferenceValue: null,
    afterReferenceValue: null,
    sourceStateId: 5,
    sourceRowNumber: 42,
    resultSummary: null,
    idempotencyKey: 'key-501',
    createdAt: '2026-09-20T10:00:00Z',
    decidedBy: null,
    decidedAt: null,
    decidedFromStatus: null,
    decisionId: null,
    identityHash: HASH,
    ...overrides,
  };
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function page(content: MutationRow[], totalElements = content.length): PageResult<MutationRow> {
  return { content, page: 0, size: 50, totalElements, totalPages: 1 };
}

// ---------------------------------------------------------------------------------------------------
// 1. De pure omzetting en de poort
// ---------------------------------------------------------------------------------------------------

describe('toDecisionFilter (F9, filter-naar-request)', () => {
  it('F9.1: neemt alle vijf lijstfilters letterlijk over, inclusief identityHash en statusReason', () => {
    const listFilter = {
      batchId: 77,
      status: 'AWAITING_APPROVAL' as const,
      actionType: 'UPDATE' as const,
      statusReason: 'BULK_PRICE_INCIDENT',
      identityHash: HASH,
    };
    expect(toDecisionFilter(listFilter)).toEqual(listFilter);
  });

  it('F9.2: voegt niets toe wat de lijst niet filtert (geen lege of verzonnen velden)', () => {
    const result = toDecisionFilter({ identityHash: HASH });
    expect(result).toEqual({ identityHash: HASH });
    expect(Object.keys(result)).toEqual(['identityHash']);
    expect(toDecisionFilter({})).toEqual({});
  });

  it('F9.3: past de waarden niet aan (geen trim, geen hoofdletterwissel, geen status-correctie)', () => {
    // De lijst heeft al getrimd; wat hier binnenkomt gaat ongewijzigd door, ook een status die de
    // groepsactie niet raakt — die wordt door de poort geweigerd, nooit stil weggelaten.
    const listFilter = { status: 'BLOCKED' as const, identityHash: HASH.toUpperCase() };
    expect(toDecisionFilter(listFilter)).toEqual(listFilter);
  });
});

describe('groupDecisionGate (F9, spiegel van de backend)', () => {
  it('F9.4: weigert een lege filter met DECISION_FILTER_REQUIRED', () => {
    const gate = groupDecisionGate('ASSEMBLING', {}, 10);
    expect(gate.allowed).toBe(false);
    expect(gate.allowed ? '' : gate.reason).toContain('DECISION_FILTER_REQUIRED');
  });

  it('F9.5: staat enkel identityHash of enkel statusReason toe als filter (C5)', () => {
    expect(groupDecisionGate('ASSEMBLING', { identityHash: HASH }, 3).allowed).toBe(true);
    expect(groupDecisionGate('ASSEMBLING', { statusReason: 'BULK_PRICE_INCIDENT' }, 3).allowed).toBe(true);
  });

  it('F9.6: weigert een status of soort die de groepsactie nooit raakt, in plaats van hem weg te laten', () => {
    for (const status of ['BLOCKED', 'READY_FOR_PUBLICATION', 'REJECTED', 'RECORDED'] as const) {
      expect(groupDecisionGate('ASSEMBLING', { status }, 3).allowed).toBe(false);
    }
    for (const actionType of ['IMPORT_MARKER', 'IDENTITY_REFERENCE_INCIDENT'] as const) {
      expect(groupDecisionGate('ASSEMBLING', { actionType }, 3).allowed).toBe(false);
    }
    expect(groupDecisionGate('ASSEMBLING', { status: 'PLANNED', actionType: 'CREATE' }, 3).allowed).toBe(true);
    expect(groupDecisionGate('ASSEMBLING', { status: 'AWAITING_APPROVAL', actionType: 'UPDATE' }, 3).allowed).toBe(
      true,
    );
  });

  it('F9.7: weigert zolang het aantal niet vaststaat, en bij een lege lijst', () => {
    expect(groupDecisionGate('ASSEMBLING', { batchId: 1 }, null).allowed).toBe(false);
    expect(groupDecisionGate('ASSEMBLING', { batchId: 1 }, 0).allowed).toBe(false);
    expect(groupDecisionGate('ASSEMBLING', { batchId: 1 }, 1).allowed).toBe(true);
  });

  it('F9.8: weigert buiten ASSEMBLING met BUNDLE_NOT_ASSEMBLING', () => {
    const gate = groupDecisionGate('FROZEN', { batchId: 1 }, 5);
    expect(gate.allowed).toBe(false);
    expect(gate.allowed ? '' : gate.reason).toContain('BUNDLE_NOT_ASSEMBLING');
  });
});

// ---------------------------------------------------------------------------------------------------
// 2. MutationList: één bron van waarheid voor de filter
// ---------------------------------------------------------------------------------------------------

describe('MutationList toolbar-slot (F9)', () => {
  afterEach(() => {
    cleanup();
    sessionStorage.clear();
  });

  function stripPaging(query: MutationQuery) {
    const { page: _page, size: _size, ...filter } = query;
    void _page;
    void _size;
    return filter;
  }

  it('F9.9: de toolbar krijgt exact de filter waarmee de bron bevraagd wordt, en nooit een oud aantal', async () => {
    const queries: MutationQuery[] = [];
    const contexts: MutationListToolbarContext[] = [];
    let resolveSecond: ((value: PageResult<MutationRow>) => void) | null = null;

    const source: MutationSource = {
      key: 'fake:1',
      supportedFilters: ['status', 'batchId', 'actionType', 'statusReason', 'identityHash'],
      fetchPage: (query) => {
        queries.push(query);
        if (queries.length === 1) {
          return Promise.resolve(page([mutation()], 5));
        }
        // Het tweede verzoek blijft hangen: de toolbar mag dan niet het aantal "5" van de vorige
        // (ongefilterde) lijst naast de nieuwe filter tonen.
        return new Promise((resolve) => {
          resolveSecond = resolve;
        });
      },
    };

    render(
      <ActorProvider>
        <MutationList
          source={source}
          toolbar={(context) => {
            contexts.push(context);
            return <p data-testid="toolbar">{context.listedCount ?? 'onbekend'}</p>;
          }}
        />
      </ActorProvider>,
    );

    await waitFor(() => expect(screen.getByTestId('toolbar').textContent).toBe('5'));
    expect(contexts.at(-1)?.filter).toEqual(stripPaging(queries[0]!));

    fireEvent.change(screen.getByLabelText('Status'), { target: { value: 'PLANNED' } });
    await waitFor(() => expect(queries).toHaveLength(2));

    // Vanaf de eerste render met de nieuwe filter: het aantal is onbekend, nooit "5".
    const withNewFilter = contexts.filter((context) => context.filter.status === 'PLANNED');
    expect(withNewFilter.length).toBeGreaterThan(0);
    for (const context of withNewFilter) {
      expect(context.listedCount).toBeNull();
    }
    expect(screen.getByTestId('toolbar').textContent).toBe('onbekend');
    expect(contexts.at(-1)?.filter).toEqual(stripPaging(queries[1]!));

    resolveSecond!(page([mutation({ status: 'PLANNED' })], 3));
    await waitFor(() => expect(screen.getByTestId('toolbar').textContent).toBe('3'));
    expect(contexts.at(-1)?.filter).toEqual({ status: 'PLANNED' });
  });
});

// ---------------------------------------------------------------------------------------------------
// 3. BundleMutationsTab met de echte API-laag
// ---------------------------------------------------------------------------------------------------

function renderTab(detail: BundleDetail, reloadBundle: () => void = () => {}) {
  return render(
    <ActorProvider>
      <MemoryRouter initialEntries={['/bundles/42/mutations']}>
        <Routes>
          <Route path="/bundles/:bundleId" element={<Outlet context={{ bundle: detail, reloadBundle }} />}>
            <Route path="mutations" element={<BundleMutationsTab />} />
          </Route>
        </Routes>
      </MemoryRouter>
    </ActorProvider>,
  );
}

function fetchCalls() {
  return vi.mocked(global.fetch).mock.calls.map((call) => ({
    url: call[0]?.toString() ?? '',
    method: call[1]?.method ?? 'GET',
    body: call[1]?.body,
  }));
}

function posts() {
  return fetchCalls().filter((call) => call.method === 'POST');
}

/** De filter zoals de lijst hem het laatst naar de server stuurde (queryparameters zonder paginering). */
function lastListFilter(): Record<string, string | number> {
  const gets = fetchCalls().filter((call) => call.method === 'GET' && call.url.includes('/bundles/42/mutations'));
  const url = gets.at(-1)?.url ?? '';
  const params = new URLSearchParams(url.slice(url.indexOf('?') + 1));
  const filter: Record<string, string | number> = {};
  params.forEach((value, key) => {
    if (key === 'page' || key === 'size') {
      return;
    }
    filter[key] = key === 'batchId' ? Number(value) : value;
  });
  return filter;
}

describe('GroupDecisionDialog in BundleMutationsTab', () => {
  const originalFetch = global.fetch;
  let rows: MutationRow[];
  let total: number;
  let postResponse: () => Response;

  beforeEach(() => {
    rows = [mutation()];
    total = 1;
    postResponse = () => jsonResponse({ decisionId: 9, affectedCount: 1, selectionFilter: 'status=AWAITING_APPROVAL' });
    global.fetch = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      if ((init?.method ?? 'GET') === 'POST') {
        return Promise.resolve(postResponse());
      }
      if (url.includes('/mutations')) {
        return Promise.resolve(jsonResponse(page(rows, total)));
      }
      return Promise.reject(new Error(`Onverwachte URL in test: ${url}`));
    }) as unknown as typeof fetch;
  });

  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    vi.clearAllMocks();
    global.fetch = originalFetch;
  });

  async function openGroupDialog(label: 'Groep goedkeuren' | 'Groep afkeuren', count: number) {
    const button = await screen.findByRole('button', { name: `${label} (${count})` });
    await waitFor(() => expect(button).toBeEnabled());
    fireEvent.click(button);
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText(/Naam/), { target: { value: 'An Beslisser' } });
    return dialog;
  }

  it('F9.10: (a) de verstuurde filter is gelijk aan de lijstfilter, inclusief identityHash en statusReason', async () => {
    renderTab(bundle());

    // identityHash via de wijzigingsgroep (dat past de filter meteen toe), daarna de rest.
    fireEvent.click(await screen.findByRole('button', { name: `Toon de hele wijzigingsgroep ${HASH}` }));
    fireEvent.change(screen.getByLabelText('Status'), { target: { value: 'AWAITING_APPROVAL' } });
    fireEvent.change(screen.getByLabelText('Soort'), { target: { value: 'UPDATE' } });
    fireEvent.change(screen.getByLabelText('Batch'), { target: { value: '77' } });
    fireEvent.change(screen.getByLabelText('Statusreden'), { target: { value: 'BULK_PRICE_INCIDENT' } });
    fireEvent.click(screen.getByRole('button', { name: 'Filteren' }));

    await waitFor(() => expect(lastListFilter()).toHaveProperty('statusReason', 'BULK_PRICE_INCIDENT'));
    const expected = {
      identityHash: HASH,
      status: 'AWAITING_APPROVAL',
      actionType: 'UPDATE',
      batchId: 77,
      statusReason: 'BULK_PRICE_INCIDENT',
    };
    expect(lastListFilter()).toEqual(expected);

    const dialog = await openGroupDialog('Groep goedkeuren', 1);
    fireEvent.click(within(dialog).getByRole('button', { name: 'Groep goedkeuren' }));

    await waitFor(() => expect(posts()).toHaveLength(1));
    const post = posts()[0]!;
    expect(post.url).toBe('/api/catalog-import/bundles/42/decisions');
    const body = JSON.parse(String(post.body));
    expect(body.filter).toEqual(lastListFilter());
    expect(body).toEqual({ decisionKind: 'APPROVE', decidedBy: 'An Beslisser', reason: null, filter: expected });
  });

  it('F9.11: (a) niet-toegepaste invoer in de filterbalk reist niet mee — alleen wat de lijst toont', async () => {
    renderTab(bundle());
    fireEvent.change(await screen.findByLabelText('Status'), { target: { value: 'PLANNED' } });
    await waitFor(() => expect(lastListFilter()).toEqual({ status: 'PLANNED' }));

    // Getypt maar NIET op "Filteren" geklikt: de lijst filtert hier niet op, de groepsactie dus ook niet.
    fireEvent.change(screen.getByLabelText('Statusreden'), { target: { value: 'NIET_TOEGEPAST' } });
    fireEvent.change(screen.getByLabelText('Batch'), { target: { value: '999' } });

    const dialog = await openGroupDialog('Groep goedkeuren', 1);
    fireEvent.click(within(dialog).getByRole('button', { name: 'Groep goedkeuren' }));

    await waitFor(() => expect(posts()).toHaveLength(1));
    expect(JSON.parse(String(posts()[0]!.body)).filter).toEqual({ status: 'PLANNED' });
  });

  it('F9.12: (b) het aantal van de lijst staat in de dialoog vóór er iets verstuurd wordt', async () => {
    total = 1234; // de server telt meer dan er op één pagina staan
    postResponse = () => jsonResponse({ decisionId: 9, affectedCount: 1200, selectionFilter: 'status=PLANNED' });
    const reloadBundle = vi.fn();
    renderTab(bundle(), reloadBundle);
    fireEvent.change(await screen.findByLabelText('Status'), { target: { value: 'PLANNED' } });

    const dialog = await openGroupDialog('Groep goedkeuren', 1234);
    expect(within(dialog).getByText(/De lijst toont/).textContent).toContain('1234 mutaties');
    expect(within(dialog).getByText(/nooit een geblokkeerde mutatie/)).toBeInTheDocument();
    expect(within(dialog).getByRole('heading').textContent).toBe('1234 mutaties goedkeuren (groepsactie)');
    expect(posts()).toHaveLength(0);

    const listLoadsBefore = fetchCalls().filter((call) => call.method === 'GET').length;
    fireEvent.click(within(dialog).getByRole('button', { name: 'Groep goedkeuren' }));

    // Het werkelijke aantal komt van de server en wordt eerlijk naast het getoonde gezet.
    await waitFor(() => expect(screen.getByRole('status').textContent).toContain('1200 mutaties goedgekeurd'));
    expect(screen.getByRole('status').textContent).toContain('De lijst toonde 1234');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    // Lijst en bundeltellers worden expliciet ververst.
    await waitFor(() =>
      expect(fetchCalls().filter((call) => call.method === 'GET').length).toBeGreaterThan(listLoadsBefore),
    );
    expect(reloadBundle).toHaveBeenCalled();
  });

  it('F9.13: (c) annuleren verstuurt niets', async () => {
    renderTab(bundle());
    fireEvent.change(await screen.findByLabelText('Status'), { target: { value: 'AWAITING_APPROVAL' } });

    const dialog = await openGroupDialog('Groep afkeuren', 1);
    fireEvent.change(within(dialog).getByLabelText(/Reden/), { target: { value: 'toch niet' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Annuleren' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(posts()).toHaveLength(0);
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('F9.14: (d) een 409 van de backend blijft in de dialoog staan mét code, geen succesmelding', async () => {
    postResponse = () =>
      jsonResponse({ error: 'Bundle 42 is FROZEN', code: 'BUNDLE_NOT_ASSEMBLING' }, 409);
    renderTab(bundle());
    fireEvent.change(await screen.findByLabelText('Status'), { target: { value: 'AWAITING_APPROVAL' } });

    const dialog = await openGroupDialog('Groep goedkeuren', 1);
    fireEvent.click(within(dialog).getByRole('button', { name: 'Groep goedkeuren' }));

    await waitFor(() => expect(within(dialog).getByRole('alert').textContent).toContain('BUNDLE_NOT_ASSEMBLING'));
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    // Geen automatische herhaling (useAction kent geen retry).
    expect(posts()).toHaveLength(1);
  });

  it('F9.15: (d) een 400 zonder code (IllegalArgumentException) wordt ook getoond, niet stil geslikt', async () => {
    postResponse = () => jsonResponse({ error: 'filter.statusReason is too long' }, 400);
    renderTab(bundle());
    fireEvent.change(await screen.findByLabelText('Statusreden'), { target: { value: 'X' } });
    fireEvent.click(screen.getByRole('button', { name: 'Filteren' }));

    const dialog = await openGroupDialog('Groep goedkeuren', 1);
    fireEvent.click(within(dialog).getByRole('button', { name: 'Groep goedkeuren' }));

    await waitFor(() => expect(within(dialog).getByRole('alert').textContent).toContain('HTTP 400'));
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('F9.16: affectedCount = 0 wordt gemeld als "niets geschreven", niet als succes', async () => {
    postResponse = () => jsonResponse({ decisionId: null, affectedCount: 0, selectionFilter: 'status=PLANNED' });
    renderTab(bundle());
    fireEvent.change(await screen.findByLabelText('Status'), { target: { value: 'PLANNED' } });

    const dialog = await openGroupDialog('Groep goedkeuren', 1);
    fireEvent.click(within(dialog).getByRole('button', { name: 'Groep goedkeuren' }));

    await waitFor(() =>
      expect(screen.getByRole('status').textContent).toBe(
        'Er voldeed niets (meer) aan de selectie; er is bewust geen beslissingsregel geschreven.',
      ),
    );
  });

  it('F9.17: afkeuren eist een reden en stuurt REJECT met die reden', async () => {
    renderTab(bundle());
    fireEvent.change(await screen.findByLabelText('Soort'), { target: { value: 'CREATE' } });

    const dialog = await openGroupDialog('Groep afkeuren', 1);
    expect(within(dialog).getByRole('button', { name: 'Groep afkeuren' })).toBeDisabled();
    fireEvent.change(within(dialog).getByLabelText(/Reden/), { target: { value: 'hele leverancier fout' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Groep afkeuren' }));

    await waitFor(() => expect(posts()).toHaveLength(1));
    expect(JSON.parse(String(posts()[0]!.body))).toEqual({
      decisionKind: 'REJECT',
      decidedBy: 'An Beslisser',
      reason: 'hele leverancier fout',
      filter: { actionType: 'CREATE' },
    });
  });

  it('F9.18: zonder filter is de groepsactie uit, met de reden als zichtbare tekst', async () => {
    renderTab(bundle());
    const approve = await screen.findByRole('button', { name: 'Groep goedkeuren (1)' });
    expect(approve).toBeDisabled();
    expect(approve.getAttribute('title')).toContain('DECISION_FILTER_REQUIRED');
    expect(screen.getByText(/DECISION_FILTER_REQUIRED/)).toBeInTheDocument();
    expect(screen.getByText('Geen filter ingesteld.')).toBeInTheDocument();

    fireEvent.click(approve);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(posts()).toHaveLength(0);
  });

  it('F9.19: een statusfilter die de groepsactie niet raakt, zet de knop uit in plaats van hem weg te laten', async () => {
    rows = [mutation({ status: 'BLOCKED' })];
    renderTab(bundle());
    fireEvent.change(await screen.findByLabelText('Status'), { target: { value: 'BLOCKED' } });

    await waitFor(() => expect(lastListFilter()).toEqual({ status: 'BLOCKED' }));
    const approve = await screen.findByRole('button', { name: 'Groep goedkeuren (1)' });
    expect(approve).toBeDisabled();
    expect(approve.getAttribute('title')).toContain('status BLOCKED');
  });

  it('F9.20: een bevroren bundel biedt de groepsactie niet aan', async () => {
    renderTab(bundle({ status: 'FROZEN', plannedCount: null, awaitingApprovalCount: null }));
    fireEvent.change(await screen.findByLabelText('Status'), { target: { value: 'PLANNED' } });

    const approve = await screen.findByRole('button', { name: 'Groep goedkeuren (1)' });
    await waitFor(() => expect(approve.getAttribute('title')).toContain('BUNDLE_NOT_ASSEMBLING'));
    expect(approve).toBeDisabled();
  });
});
