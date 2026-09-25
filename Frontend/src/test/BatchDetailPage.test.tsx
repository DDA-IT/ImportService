/**
 * A-F1 en A-F2 — `BatchDetailPage` (`/batches/:batchId`), zie `docs/decisions.md` 2026-09-23.
 *
 * A-F1: kop, koppelingslabels (A-B1), tellers ("—" bij null, nooit 0), blokkade, nulmeting, mutatielijst
 * zonder acties, onbekende batch (BATCH_NOT_FOUND), ongeldig id.
 * A-F2: foutgroepen (werkelijk aantal), problemen (voorbeeldwaarschuwing, leeg), levering, foutcode.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ActorProvider } from '../actor/ActorContext';
import { BatchDetailPage } from '../features/batches/BatchDetailPage';

const BATCH = {
  batchId: 101,
  deliveryId: 7,
  importLinkId: 1,
  importLinkCode: 'LNK-1',
  supplierCode: 'SUP1',
  libraryCode: 'LIB1',
  definitionRevisionId: 3,
  taskRunId: null,
  attemptNo: 2,
  status: 'SCREENED',
  validationResult: 'VALID',
  startedAt: '2026-09-20T10:00:05Z',
  finishedAt: null,
  stagedRowCount: 100,
  mutationProgressRowNumber: 0,
  rawRecordCount: 100,
  validRecordCount: 95,
  rejectedRecordCount: 0,
  filteredOutCount: null,
  errorBeforeFilterCount: null,
  duplicateIdentityCount: null,
  newCount: null,
  changedCount: null,
  unchangedCount: null,
  identityIncidentCount: null,
  contentMutationCount: null,
  bulkIncidentCount: null,
  criticalLineCount: null,
  criticalIssueCount: null,
  warningCount: null,
  awaitingApprovalCount: null,
  creationOutcome: null,
  creationScopeCount: null,
  creationCandidateCount: null,
  blockedCode: null,
  blockedReason: null,
  baselineAcceptedBy: null,
  baselineAcceptedAt: null,
  baselineAcceptReason: null,
  createdAt: '2026-09-20T10:00:00Z',
  createdBy: 'tester',
};

const DELIVERY = {
  deliveryId: 7,
  taskId: 1,
  taskRunId: null,
  idempotencyKey: 'manual:REF-1',
  receivedAt: '2026-09-20T09:59:00Z',
  expectedFileCount: null,
  actualFileCount: 1,
  expectedRecordCount: null,
  actualRecordCount: 100,
  expectedByteSize: null,
  actualByteSize: 2048,
  completenessProven: false,
  manifestReference: null,
  files: [{ sequenceNumber: 1, fileName: 'prijzen.csv', contentHash: 'abc123', hashAlgorithm: 'SHA-256', byteSize: 2048 }],
  batch: null,
};

function page<T>(content: T[]) {
  return { content, page: 0, size: 50, totalElements: content.length, totalPages: content.length === 0 ? 0 : 1 };
}

const GROUP = {
  id: 55,
  issueCode: 'PRICE_INVALID',
  signature: 'sig',
  severity: 'ERROR',
  issueDomain: 'PRICE',
  controlLevel: 'RECORD',
  impactScope: 'ROW',
  incidentKind: 'FIELD_ERROR',
  occurrenceCount: 1234,
  recordedSampleCount: 10,
  scopeRecordCount: null,
  sharePercent: null,
  bulkIncident: true,
  priceComponentCode: null,
  deviationDirection: null,
  dominantFactor: null,
  referenceType: null,
  patternDescription: null,
  firstRowNumber: 3,
  firstDetectedAt: '2026-09-20T10:00:10Z',
  lastDetectedAt: '2026-09-20T10:00:20Z',
  handlingStatus: 'OPEN',
};

const ISSUE = {
  id: 900,
  rowNumber: null,
  issueCode: 'STRUCTURE_BAD',
  fieldName: null,
  severity: 'BLOCKING',
  issueDomain: 'DELIVERY',
  controlLevel: 'DELIVERY',
  impactScope: 'DELIVERY',
  handlingStatus: 'OPEN',
  sourceValue: null,
  expectedValue: null,
  message: 'Kop ontbreekt',
  issueGroupId: null,
  createdAt: '2026-09-20T10:00:10Z',
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

type Overrides = Partial<{ batch: Response; groups: unknown; issues: unknown; delivery: Response; mutations: Response }>;

function stubFetch(overrides: Overrides = {}) {
  global.fetch = vi.fn((input: RequestInfo | URL) => {
    const url = typeof input === 'string' ? input : input.toString();
    if (url.includes('/issue-groups')) return Promise.resolve(json(overrides.groups ?? page([GROUP])));
    if (url.includes('/issues')) return Promise.resolve(json(overrides.issues ?? page([ISSUE])));
    if (url.includes('/mutations')) return Promise.resolve(overrides.mutations ?? json(page([])));
    if (url.includes('/deliveries/')) return Promise.resolve(overrides.delivery ?? json(DELIVERY));
    if (/\/batches\/[^/?]+$/.test(url)) return Promise.resolve(overrides.batch ?? json(BATCH));
    return Promise.reject(new Error(`Onverwachte URL in test: ${url}`));
  }) as unknown as typeof fetch;
}

function renderAt(path: string) {
  return render(
    <ActorProvider>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/batches/:batchId" element={<BatchDetailPage />} />
        </Routes>
      </MemoryRouter>
    </ActorProvider>,
  );
}

describe('BatchDetailPage', () => {
  const originalFetch = global.fetch;

  beforeEach(() => stubFetch());

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    global.fetch = originalFetch;
  });

  it('A-F1.1: toont kop, koppelingslabels en tellers, met "—" (nooit 0) voor niet vastgestelde tellers', async () => {
    renderAt('/batches/101');

    expect(await screen.findByRole('heading', { name: 'Batch 101' })).toBeInTheDocument();
    const facts = screen.getByTestId('batch-facts');
    expect(within(facts).getByText('LNK-1')).toBeInTheDocument();
    expect(within(facts).getByText(/SUP1 · LIB1/)).toBeInTheDocument();

    const counters = screen.getByTestId('batch-counters');
    const raw = within(counters).getByText('Ruwe records').closest('div') as HTMLElement;
    expect(within(raw).getByText('100')).toBeInTheDocument();
    const nieuw = within(counters).getByText('Nieuw').closest('div') as HTMLElement;
    expect(within(nieuw).getByText('—')).toBeInTheDocument();
    expect(within(nieuw).queryByText('0')).toBeNull();
  });

  it('A-F1.2: toont een blokkade en de nulmeting alleen als ze bestaan', async () => {
    stubFetch({
      batch: json({
        ...BATCH,
        status: 'BLOCKED',
        validationResult: null,
        blockedCode: 'THRESHOLD_EXCEEDED',
        blockedReason: 'Te veel wijzigingen',
        baselineAcceptedBy: 'Jan',
        baselineAcceptedAt: '2026-09-21T10:00:00Z',
        baselineAcceptReason: 'akkoord',
      }),
    });
    renderAt('/batches/101');

    const blocked = await screen.findByTestId('batch-blocked');
    expect(blocked).toHaveTextContent('THRESHOLD_EXCEEDED');
    expect(blocked).toHaveTextContent('Te veel wijzigingen');
    expect(screen.getByTestId('batch-baseline')).toHaveTextContent('Jan');
    expect(screen.getByText('Eindoordeel niet vastgesteld')).toBeInTheDocument();
  });

  it('A-F1.3: zonder blokkade en nulmeting verschijnen die blokken niet', async () => {
    renderAt('/batches/101');
    await screen.findByRole('heading', { name: 'Batch 101' });
    expect(screen.queryByTestId('batch-blocked')).toBeNull();
    expect(screen.queryByTestId('batch-baseline')).toBeNull();
  });

  it('A-F1.4: de mutatielijst is een leeslijst en vraagt /batches/{id}/mutations op', async () => {
    renderAt('/batches/101');
    expect(await screen.findByText('Deze batch heeft (met deze filter) geen mutaties.')).toBeInTheDocument();
    const calls = vi.mocked(global.fetch).mock.calls.map((c) => c[0]?.toString() ?? '');
    expect(calls.some((u) => u.includes('/batches/101/mutations'))).toBe(true);
    expect(screen.queryByRole('button', { name: 'Goedkeuren' })).toBeNull();
  });

  it('A-F1.5: onbekende batch toont BATCH_NOT_FOUND leesbaar, zonder de andere secties', async () => {
    stubFetch({ batch: json({ code: 'BATCH_NOT_FOUND', error: 'Batch 5 not found' }, 404) });
    renderAt('/batches/5');

    expect(await screen.findByText('Batch niet gevonden')).toBeInTheDocument();
    expect(screen.getByText(/BATCH_NOT_FOUND · HTTP 404/)).toBeInTheDocument();
    expect(screen.queryByTestId('batch-delivery')).toBeNull();
    expect(screen.queryByTestId('batch-issues')).toBeNull();
  });

  it('A-F1.6: een ongeldig batchnummer doet geen verzoek', async () => {
    renderAt('/batches/abc');
    expect(await screen.findByText(/Ongeldig batchnummer/)).toBeInTheDocument();
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it('A-F2.1: foutgroepen tonen het werkelijke aantal en filteren de problemen op groep', async () => {
    renderAt('/batches/101');

    const groups = await screen.findByTestId('batch-issue-groups');
    await within(groups).findByText('PRICE_INVALID');
    expect(within(groups).getByText('1234')).toBeInTheDocument();
    expect(within(groups).getByText('Ja')).toBeInTheDocument();
    // Scope onbekend: "—", geen geraden noemer.
    expect(within(groups).getAllByText('—').length).toBeGreaterThan(0);

    fireEvent.click(within(groups).getByRole('button', { name: 'Toon voorbeelden' }));
    await waitFor(() => {
      const calls = vi.mocked(global.fetch).mock.calls.map((c) => c[0]?.toString() ?? '');
      expect(calls.some((u) => u.includes('/issues?') && u.includes('issueGroupId=55'))).toBe(true);
    });
  });

  it('A-F2.2: problemen tonen de voorbeeldwaarschuwing en een leveringsprobleem zonder rij', async () => {
    renderAt('/batches/101');
    const issues = await screen.findByTestId('batch-issues');
    expect(within(issues).getByRole('note')).toHaveTextContent('voorbeeldrijen');
    expect(await within(issues).findByText('Kop ontbreekt')).toBeInTheDocument();
    expect(within(issues).getByText('levering')).toBeInTheDocument();
  });

  it('A-F2.3: lege foutgroepen en problemen tonen een lege melding', async () => {
    stubFetch({ groups: page([]), issues: page([]) });
    renderAt('/batches/101');
    expect(await screen.findByText('Deze batch heeft geen foutgroepen.')).toBeInTheDocument();
    expect(await screen.findByText('Geen problemen vastgesteld.')).toBeInTheDocument();
  });

  it('A-F2.4: de levering toont bestanden, "—" voor onbekende verwachte waarden en niet-bewezen volledigheid', async () => {
    renderAt('/batches/101');
    const delivery = await screen.findByTestId('batch-delivery');
    expect(await within(delivery).findByText('prijzen.csv')).toBeInTheDocument();
    expect(within(delivery).getByText('Niet bewezen')).toBeInTheDocument();
    expect(within(delivery).getByText('manual:REF-1')).toBeInTheDocument();
    expect(within(delivery).getAllByText('—').length).toBeGreaterThan(0);
  });

  it('A-F2.5: een fout bij de levering (DELIVERY_NOT_FOUND) blijft beperkt tot die sectie', async () => {
    stubFetch({ delivery: json({ code: 'DELIVERY_NOT_FOUND', error: 'nope' }, 404) });
    renderAt('/batches/101');
    const delivery = await screen.findByTestId('batch-delivery');
    expect(await within(delivery).findByText('Levering niet gevonden')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Batch 101' })).toBeInTheDocument();
  });

  it('A-F2.6: een 500 op de foutgroepen toont de technische regel zonder de pagina te breken', async () => {
    global.fetch = vi.fn((input: RequestInfo | URL) => {
      const url = input.toString();
      if (url.includes('/issue-groups')) return Promise.resolve(new Response('boom', { status: 500 }));
      if (url.includes('/issues')) return Promise.resolve(json(page([])));
      if (url.includes('/mutations')) return Promise.resolve(json(page([])));
      if (url.includes('/deliveries/')) return Promise.resolve(json(DELIVERY));
      return Promise.resolve(json(BATCH));
    }) as unknown as typeof fetch;
    renderAt('/batches/101');
    const groups = await screen.findByTestId('batch-issue-groups');
    expect(await within(groups).findByText('Onverwachte serverfout')).toBeInTheDocument();
  });
});
