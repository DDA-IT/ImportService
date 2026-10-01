/**
 * S2-F1/S2-F2 — `IssueCaseDetailPage` (`/issue-cases/:caseId`, `docs/design/issue-case-design.md` §6).
 *
 * S2-F1 dekt: toont geval/waarnemingen/events, 404-pad (ISSUE_CASE_NOT_FOUND), ongeldig id.
 * S2-F2 dekt: de juiste afhandelacties per status, een geslaagde "Corrigeren"- en "Heropenen"-flow, een
 * 409-conflictpad, en dat de acties uitgeschakeld zijn zonder recht MANAGE.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ActorProvider } from '../actor/ActorContext';
import { testIdentityWith } from './testIdentity';
import { PERMISSION_MANAGE, PERMISSION_READ, type Permission } from '../api/types';
import { IssueCaseDetailPage } from '../features/issuecases/IssueCaseDetailPage';

function issueCase(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: 501,
    importLinkId: 1,
    issueCode: 'PRICE_MISSING',
    signature: 'sig-1',
    severity: 'ERROR',
    issueDomain: 'PRICE',
    controlLevel: 'RECORD',
    impactScope: 'RECORD',
    incidentKind: 'PRICE',
    priceComponentCode: null,
    referenceType: null,
    status: 'REJECTED',
    observationCount: 2,
    totalOccurrenceCount: 42,
    firstSeenAt: '2026-09-01T10:00:00Z',
    lastSeenAt: '2026-09-20T10:00:00Z',
    firstSeenBatchId: 10,
    lastSeenBatchId: 15,
    lastSeenRevisionId: 2,
    reopenCount: 1,
    statusReason: 'Bekende bug, wordt niet meegenomen',
    statusChangedAt: '2026-09-21T10:00:00Z',
    statusChangedBy: 'Jan',
    statusChangedBySubject: 'jan-sub',
    decisionRevisionId: 2,
    createdAt: '2026-09-01T10:00:00Z',
    updatedAt: '2026-09-21T10:00:00Z',
    hasUnreviewedRecurrence: false,
    ...overrides,
  };
}

const CASE = issueCase();

const OBSERVATIONS = [
  {
    issueGroupId: 900,
    batchId: 15,
    deliveryId: 20,
    attemptNo: 1,
    definitionRevisionId: 2,
    occurrenceCount: 30,
    firstDetectedAt: '2026-09-15T10:00:00Z',
    lastDetectedAt: '2026-09-20T10:00:00Z',
  },
];

const EVENTS = [
  {
    id: 1,
    eventKind: 'CREATED',
    previousStatus: null,
    newStatus: 'AWAITING_REVIEW',
    reason: 'Eerste vaststelling in batch 10',
    source: 'SYSTEM',
    changedBy: null,
    changedBySubject: null,
    changedAt: '2026-09-01T10:00:00Z',
    observationBatchId: 10,
  },
  {
    id: 2,
    eventKind: 'STATUS_CHANGE',
    previousStatus: 'AWAITING_REVIEW',
    newStatus: 'REJECTED',
    reason: 'Bekende bug, wordt niet meegenomen',
    source: 'HUMAN',
    changedBy: 'Jan',
    changedBySubject: 'jan-sub',
    changedAt: '2026-09-21T10:00:00Z',
    observationBatchId: null,
  },
];

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

type Overrides = Partial<{
  /** Achtereenvolgende antwoorden op `GET /issue-cases/{id}`; het laatste blijft daarna herhaald gelden. */
  caseResponses: Response[];
  observations: unknown;
  events: unknown;
  statusResponse: () => Response;
}>;

function stubFetch(overrides: Overrides = {}) {
  const caseResponses = overrides.caseResponses ?? [json(CASE)];
  let caseCallCount = 0;
  global.fetch = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input.toString();
    const method = init?.method ?? 'GET';
    if (method === 'POST' && url.endsWith('/status')) {
      return Promise.resolve((overrides.statusResponse ?? (() => json({})))());
    }
    if (url.includes('/observations')) return Promise.resolve(json(overrides.observations ?? OBSERVATIONS));
    if (url.includes('/events')) return Promise.resolve(json(overrides.events ?? EVENTS));
    if (/\/issue-cases\/[^/?]+$/.test(url)) {
      const index = Math.min(caseCallCount, caseResponses.length - 1);
      caseCallCount += 1;
      return Promise.resolve(caseResponses[index]!);
    }
    return Promise.reject(new Error(`Onverwachte URL in test: ${url}`));
  }) as unknown as typeof fetch;
}

function renderAt(path: string, permissions: readonly Permission[] = [PERMISSION_READ, PERMISSION_MANAGE]) {
  return render(
    <ActorProvider identity={testIdentityWith(...permissions)}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/issue-cases/:caseId" element={<IssueCaseDetailPage />} />
        </Routes>
      </MemoryRouter>
    </ActorProvider>,
  );
}

function posts() {
  return vi.mocked(global.fetch).mock.calls.filter((call) => call[1]?.method === 'POST');
}

describe('IssueCaseDetailPage', () => {
  const originalFetch = global.fetch;

  beforeEach(() => stubFetch());

  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    vi.clearAllMocks();
    global.fetch = originalFetch;
  });

  it('S2-F1.5: toont de kop, classificatie, tellers en de laatste beslissing', async () => {
    renderAt('/issue-cases/501');

    expect(await screen.findByRole('heading', { name: 'Behandelgeval #501' })).toBeInTheDocument();
    const facts = screen.getByTestId('issue-case-facts');
    // NT-11c: het Nederlandse woord staat in beeld, de technische code in de tooltip en onder "Technische details".
    const issueWord = within(facts).getByText('Prijs ontbreekt');
    expect(issueWord).toHaveAttribute('title', expect.stringContaining('PRICE_MISSING'));
    expect(within(facts).getByText('Fout')).toBeInTheDocument();
    expect(within(facts).getByText('Prijzen')).toBeInTheDocument();
    expect(within(facts).getByText('Eén regel')).toBeInTheDocument();
    expect(within(facts).getByText('Alleen deze regel')).toBeInTheDocument();
    expect(within(facts).getByText('Prijsafwijkingen')).toBeInTheDocument();
    expect(facts.textContent).not.toContain('PRICE_MISSING');

    const decision = screen.getByTestId('issue-case-decision');
    expect(within(decision).getByText('Jan')).toBeInTheDocument();
    expect(within(decision).getByText('Bekende bug, wordt niet meegenomen')).toBeInTheDocument();
  });

  it('S2-F1.6: toont de gekoppelde waarnemingen', async () => {
    renderAt('/issue-cases/501');
    const observations = await screen.findByTestId('issue-case-observations');
    expect(await within(observations).findByRole('link', { name: '#15' })).toHaveAttribute('href', '/batches/15');
    expect(within(observations).getByText('30')).toBeInTheDocument();
  });

  it('S2-F1.7: toont de gebeurtenissengeschiedenis chronologisch, met CREATED eerst', async () => {
    renderAt('/issue-cases/501');
    const events = await screen.findByTestId('issue-case-events');
    const rows = within(events).getAllByRole('row');
    // rows[0] is de header
    expect(within(rows[1]).getByText('Aangemaakt')).toBeInTheDocument();
    expect(within(rows[1]).getByText('Het systeem')).toBeInTheDocument();
    expect(within(rows[2]).getByText('Status gewijzigd')).toBeInTheDocument();
    expect(within(rows[2]).getByText('Wacht op beoordeling')).toBeInTheDocument();
    expect(within(rows[2]).getByText('Afgewezen')).toBeInTheDocument();
    expect(within(rows[2]).getByText('Een mens')).toBeInTheDocument();
  });

  it('S2-F1.9: onbekend geval toont ISSUE_CASE_NOT_FOUND leesbaar', async () => {
    stubFetch({ caseResponses: [json({ code: 'ISSUE_CASE_NOT_FOUND', error: 'Issue case 999 not found' }, 404)] });
    renderAt('/issue-cases/999');

    expect(await screen.findByText('Behandelgeval niet gevonden')).toBeInTheDocument();
    expect(screen.getByText(/ISSUE_CASE_NOT_FOUND · HTTP 404/)).toBeInTheDocument();
  });

  it('S2-F1.10: een ongeldig gevalnummer doet geen verzoek', async () => {
    renderAt('/issue-cases/abc');
    expect(await screen.findByText(/Ongeldig gevalnummer/)).toBeInTheDocument();
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it('S2-F2.1: AWAITING_REVIEW toont Corrigeren/Afwijzen, geen Heropenen', async () => {
    stubFetch({ caseResponses: [json(issueCase({ status: 'AWAITING_REVIEW' }))] });
    renderAt('/issue-cases/501');
    await screen.findByRole('heading', { name: 'Behandelgeval #501' });

    expect(screen.getByRole('button', { name: 'Corrigeren' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Afwijzen' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Heropenen' })).not.toBeInTheDocument();
  });

  it.each(['CORRECTED', 'REJECTED', 'AUTO_RESOLVED'])(
    'S2-F2.2: %s toont enkel Heropenen',
    async (status) => {
      stubFetch({ caseResponses: [json(issueCase({ status }))] });
      renderAt('/issue-cases/501');
      await screen.findByRole('heading', { name: 'Behandelgeval #501' });

      expect(screen.getByRole('button', { name: 'Heropenen' })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Corrigeren' })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Afwijzen' })).not.toBeInTheDocument();
    },
  );

  it('S2-F2.3: succesvolle "Corrigeren"-flow: dialoog, reden invullen, bevestigen, nieuwe status zichtbaar', async () => {
    stubFetch({
      caseResponses: [
        json(issueCase({ status: 'AWAITING_REVIEW', statusChangedBy: null, statusReason: null, statusChangedAt: null })),
        json(
          issueCase({
            status: 'CORRECTED',
            statusReason: 'Foutieve prijs hersteld',
            statusChangedAt: '2026-09-25T09:00:00Z',
            statusChangedBy: 'An Beslisser',
            statusChangedBySubject: 'test-sub',
            decisionRevisionId: 3,
            reopenCount: 0,
          }),
        ),
      ],
      statusResponse: () =>
        json({
          id: 501,
          status: 'CORRECTED',
          statusReason: 'Foutieve prijs hersteld',
          statusChangedAt: '2026-09-25T09:00:00Z',
          statusChangedBy: 'An Beslisser',
          statusChangedBySubject: 'test-sub',
          decisionRevisionId: 3,
          reopenCount: 0,
        }),
    });
    renderAt('/issue-cases/501');
    fireEvent.click(await screen.findByRole('button', { name: 'Corrigeren' }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByTestId('issue-case-administrative-notice').textContent).toContain(
      'geen dataherstel',
    );
    fireEvent.change(within(dialog).getByLabelText(/Reden/), { target: { value: 'Foutieve prijs hersteld' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Corrigeren' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const statusPost = posts().find((call) => call[0]!.toString().endsWith('/status'))!;
    expect(JSON.parse(String(statusPost[1]?.body))).toEqual({
      newStatus: 'CORRECTED',
      expectedStatus: 'AWAITING_REVIEW',
      reason: 'Foutieve prijs hersteld',
      changedBy: 'An Beslisser',
    });
    expect(screen.getByRole('status').textContent).toContain('Gecorrigeerd');

    // De pagina herlaadt het geval na de actie: de nieuwe status is nu ook zichtbaar in het overzicht
    // (de StatusBadge naast de titel).
    await waitFor(() => {
      const badge = screen.getByRole('heading', { name: 'Behandelgeval #501' }).parentElement!;
      expect(within(badge).getByText('Gecorrigeerd')).toBeInTheDocument();
    });
  });

  it('S2-F2.4: succesvolle "Heropenen"-flow vanuit REJECTED', async () => {
    stubFetch({
      caseResponses: [
        json(issueCase({ status: 'REJECTED' })),
        json(
          issueCase({
            status: 'AWAITING_REVIEW',
            statusReason: 'Regels gewijzigd, opnieuw beoordelen',
            statusChangedAt: '2026-09-25T09:00:00Z',
            statusChangedBy: 'An Beslisser',
            statusChangedBySubject: 'test-sub',
            decisionRevisionId: null,
            reopenCount: 2,
          }),
        ),
      ],
      statusResponse: () =>
        json({
          id: 501,
          status: 'AWAITING_REVIEW',
          statusReason: 'Regels gewijzigd, opnieuw beoordelen',
          statusChangedAt: '2026-09-25T09:00:00Z',
          statusChangedBy: 'An Beslisser',
          statusChangedBySubject: 'test-sub',
          decisionRevisionId: null,
          reopenCount: 2,
        }),
    });
    renderAt('/issue-cases/501');
    fireEvent.click(await screen.findByRole('button', { name: 'Heropenen' }));

    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText(/Reden/), {
      target: { value: 'Regels gewijzigd, opnieuw beoordelen' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Heropenen' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const statusPost = posts().find((call) => call[0]!.toString().endsWith('/status'))!;
    expect(JSON.parse(String(statusPost[1]?.body))).toMatchObject({
      newStatus: 'AWAITING_REVIEW',
      expectedStatus: 'REJECTED',
    });
    expect(screen.getByRole('status').textContent).toContain('Wacht op beoordeling');
  });

  it('S2-F2.5: een 409 ISSUE_CASE_STATUS_CHANGED blijft in de open dialoog staan, geen succesmelding', async () => {
    stubFetch({
      caseResponses: [json(issueCase({ status: 'AWAITING_REVIEW' }))],
      statusResponse: () =>
        json(
          { error: 'Issue case 501 is in status CORRECTED, not the expected AWAITING_REVIEW', code: 'ISSUE_CASE_STATUS_CHANGED' },
          409,
        ),
    });
    renderAt('/issue-cases/501');
    fireEvent.click(await screen.findByRole('button', { name: 'Afwijzen' }));

    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText(/Reden/), { target: { value: 'Niet correct' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Afwijzen' }));

    expect(await within(dialog).findByText('Status is intussen gewijzigd')).toBeInTheDocument();
    expect(within(dialog).getByText(/ISSUE_CASE_STATUS_CHANGED · HTTP 409/)).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('S2-F2.6: zonder recht MANAGE zijn de acties zichtbaar maar uitgeschakeld, met reden', async () => {
    stubFetch({ caseResponses: [json(issueCase({ status: 'AWAITING_REVIEW' }))] });
    renderAt('/issue-cases/501', [PERMISSION_READ]);
    await screen.findByRole('heading', { name: 'Behandelgeval #501' });

    const correctButton = screen.getByRole('button', { name: 'Corrigeren' });
    expect(correctButton).toBeDisabled();
    expect(correctButton.getAttribute('title')).toContain('catalogImport.manage');
    fireEvent.click(correctButton);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(posts()).toHaveLength(0);
  });
});
