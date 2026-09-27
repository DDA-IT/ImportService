/**
 * 5B-5 — rechten in de UI (`docs/design/fase5-perm-design.md` §1 en §4): schrijfknoppen zijn uitgeschakeld MÉT
 * reden wanneer het recht ontbreekt (niet verborgen) en gewoon aan met het recht; `permissions: []` toont één
 * paginabreed vlak zonder data-fetches. De UI houdt geen eigen hiërarchie bij: de testidentiteit is de
 * effectieve set zoals `/me` hem levert.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { useEffect, type ReactNode } from 'react';
import { MemoryRouter, Outlet, Route, Routes } from 'react-router-dom';
import App from '../App';
import { ActorProvider, type ActorIdentity } from '../actor/ActorContext';
import { missingPermissionReason } from '../actor/permissions';
import { TEST_IDENTITY, testIdentityWith } from './testIdentity';
import type { BatchDetail, BundleDetail, MutationRow } from '../api/types';
import { BatchActions } from '../features/batches/BatchActions';
import { BundleMutationsTab } from '../features/bundles/BundleMutationsTab';
import { BundleOverviewTab } from '../features/bundles/BundleOverviewTab';
import { CreateBundleForm } from '../features/bundles/CreateBundleForm';
import { GroupDecisionDialog } from '../features/bundles/GroupDecisionDialog';

const APPROVE_REASON = "U heeft het recht 'Goedkeuren' (catalogImport.approve) niet.";
const MANAGE_REASON = "U heeft het recht 'Beheren' (catalogImport.manage) niet.";

const READ_ONLY = testIdentityWith('catalogImport.read');
const NO_APPROVE = testIdentityWith('catalogImport.read', 'catalogImport.manage');
const NO_MANAGE = testIdentityWith('catalogImport.read', 'catalogImport.approve');

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function bundle(): BundleDetail {
  return {
    id: 42,
    bundleReference: 'BND-1',
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
    contentMutationCount: 1,
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
    awaitingApprovalCount: 0,
    expirableCount: 0,
  };
}

function mutation(): MutationRow {
  return {
    id: 501,
    batchId: 77,
    actionType: 'UPDATE',
    targetDomain: 'OFFER',
    status: 'AWAITING_APPROVAL',
    statusReason: null,
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
    identityHash: 'ab00',
  };
}

function withActor(identity: ActorIdentity, children: ReactNode) {
  return render(
    <ActorProvider identity={identity}>
      <MemoryRouter>{children}</MemoryRouter>
    </ActorProvider>,
  );
}

function renderOverview(identity: ActorIdentity) {
  render(
    <ActorProvider identity={identity}>
      <MemoryRouter initialEntries={['/bundles/42']}>
        <Routes>
          <Route path="/bundles/:bundleId" element={<Outlet context={{ bundle: bundle(), reloadBundle: vi.fn() }} />}>
            <Route index element={<BundleOverviewTab />} />
          </Route>
        </Routes>
      </MemoryRouter>
    </ActorProvider>,
  );
}

describe('rechten in de UI (5B-5)', () => {
  const originalFetch = global.fetch;

  beforeEach(() => {
    global.fetch = vi.fn((input: RequestInfo | URL) => {
      const url = input.toString();
      if (url.includes('/bundles/42/mutations')) {
        return Promise.resolve(jsonResponse({ content: [mutation()], page: 0, size: 50, totalElements: 1, totalPages: 1 }));
      }
      return Promise.reject(new Error(`Onverwachte URL in test: ${url}`));
    }) as unknown as typeof fetch;
  });

  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    global.fetch = originalFetch;
  });

  it('missingPermissionReason: vaste Nederlandse tekst met de rechtcode', () => {
    expect(missingPermissionReason('catalogImport.approve')).toBe(APPROVE_REASON);
    expect(missingPermissionReason('catalogImport.manage')).toBe(MANAGE_REASON);
    expect(missingPermissionReason('catalogImport.read')).toBe("U heeft het recht 'Lezen' (catalogImport.read) niet.");
  });

  describe('bevriezen en annuleren (APPROVE)', () => {
    it('zonder APPROVE: beide knoppen uitgeschakeld, mét reden als title en zichtbare tekst', () => {
      renderOverview(NO_APPROVE);
      for (const name of ['Bevriezen', 'Annuleren']) {
        const button = screen.getByRole('button', { name });
        expect(button).toBeDisabled();
        expect(button).toHaveAttribute('title', APPROVE_REASON);
      }
      expect(screen.getAllByText(APPROVE_REASON)).toHaveLength(2);
    });

    it('met APPROVE: knoppen aan', () => {
      renderOverview(TEST_IDENTITY);
      expect(screen.getByRole('button', { name: 'Bevriezen' })).toBeEnabled();
      expect(screen.getByRole('button', { name: 'Annuleren' })).toBeEnabled();
    });
  });

  describe('accept-baseline (APPROVE), opnemen in bundel en hervatten (MANAGE)', () => {
    const screened = { batchId: 101, status: 'SCREENED' } as unknown as BatchDetail;
    const mutating = { batchId: 101, status: 'MUTATING' } as unknown as BatchDetail;

    it('zonder APPROVE: accept-baseline uit met reden, opnemen in bundel (MANAGE) blijft aan', () => {
      withActor(NO_APPROVE, <BatchActions batch={screened} onChanged={vi.fn()} />);
      const baseline = screen.getByRole('button', { name: 'Aanvaarden als nulmeting' });
      expect(baseline).toBeDisabled();
      expect(baseline).toHaveAttribute('title', APPROVE_REASON);
      expect(screen.getByTestId('permission-reason-approve')).toHaveTextContent(APPROVE_REASON);
      expect(screen.getByRole('button', { name: 'Opnemen in bundel' })).toBeEnabled();
    });

    it('zonder MANAGE: opnemen in bundel uit met reden, accept-baseline (APPROVE) blijft aan', () => {
      withActor(NO_MANAGE, <BatchActions batch={screened} onChanged={vi.fn()} />);
      const add = screen.getByRole('button', { name: 'Opnemen in bundel' });
      expect(add).toBeDisabled();
      expect(add).toHaveAttribute('title', MANAGE_REASON);
      expect(screen.getByRole('button', { name: 'Aanvaarden als nulmeting' })).toBeEnabled();
    });

    it('zonder MANAGE: hervatten (continue) uit met reden; met MANAGE aan', () => {
      const { unmount } = withActor(READ_ONLY, <BatchActions batch={mutating} onChanged={vi.fn()} />);
      const resume = screen.getByRole('button', { name: 'Batch hervatten' });
      expect(resume).toBeDisabled();
      expect(resume).toHaveAttribute('title', MANAGE_REASON);
      expect(screen.getByTestId('permission-reason-manage')).toHaveTextContent(MANAGE_REASON);
      unmount();

      withActor(NO_APPROVE, <BatchActions batch={mutating} onChanged={vi.fn()} />);
      expect(screen.getByRole('button', { name: 'Batch hervatten' })).toBeEnabled();
      expect(screen.queryByTestId('permission-reason-manage')).not.toBeInTheDocument();
    });
  });

  describe('bundel aanmaken (MANAGE)', () => {
    it('zonder MANAGE: submit uitgeschakeld met reden', () => {
      withActor(READ_ONLY, <CreateBundleForm onCreated={vi.fn()} />);
      const submit = screen.getByRole('button', { name: 'Bundel aanmaken' });
      expect(submit).toBeDisabled();
      expect(submit).toHaveAttribute('title', MANAGE_REASON);
      expect(screen.getByTestId('permission-reason-manage')).toHaveTextContent(MANAGE_REASON);
    });

    it('met MANAGE: submit aan', () => {
      withActor(NO_APPROVE, <CreateBundleForm onCreated={vi.fn()} />);
      expect(screen.getByRole('button', { name: 'Bundel aanmaken' })).toBeEnabled();
    });
  });

  describe('groepsbeslissing en mutatie goedkeuren/afkeuren (APPROVE)', () => {
    function renderGroup(identity: ActorIdentity) {
      withActor(
        identity,
        <GroupDecisionDialog
          bundleId={42}
          bundleStatus="ASSEMBLING"
          filter={{ status: 'PLANNED' }}
          listedCount={3}
          onDecided={vi.fn()}
        />,
      );
    }

    it('zonder APPROVE: groepsknoppen uitgeschakeld met reden (recht gaat voor toestand)', () => {
      renderGroup(NO_APPROVE);
      for (const name of ['Groep goedkeuren (3)', 'Groep afkeuren (3)']) {
        const button = screen.getByRole('button', { name });
        expect(button).toBeDisabled();
        expect(button).toHaveAttribute('title', APPROVE_REASON);
      }
      expect(screen.getByText(APPROVE_REASON)).toBeInTheDocument();
    });

    it('met APPROVE: groepsknoppen aan', () => {
      renderGroup(TEST_IDENTITY);
      expect(screen.getByRole('button', { name: 'Groep goedkeuren (3)' })).toBeEnabled();
    });

    it('zonder APPROVE: rijknoppen Goedkeuren/Afkeuren uit met reden; met APPROVE aan', async () => {
      const { unmount } = render(
        <ActorProvider identity={NO_APPROVE}>
          <MemoryRouter initialEntries={['/bundles/42/mutations']}>
            <Routes>
              <Route path="/bundles/:bundleId" element={<Outlet context={{ bundle: bundle(), reloadBundle: vi.fn() }} />}>
                <Route path="mutations" element={<BundleMutationsTab />} />
              </Route>
            </Routes>
          </MemoryRouter>
        </ActorProvider>,
      );
      const approve = await screen.findByRole('button', { name: `Goedkeuren mutatie 501: ${APPROVE_REASON}` });
      expect(approve).toBeDisabled();
      expect(screen.getByRole('button', { name: `Afkeuren mutatie 501: ${APPROVE_REASON}` })).toBeDisabled();
      unmount();

      render(
        <ActorProvider identity={TEST_IDENTITY}>
          <MemoryRouter initialEntries={['/bundles/42/mutations']}>
            <Routes>
              <Route path="/bundles/:bundleId" element={<Outlet context={{ bundle: bundle(), reloadBundle: vi.fn() }} />}>
                <Route path="mutations" element={<BundleMutationsTab />} />
              </Route>
            </Routes>
          </MemoryRouter>
        </ActorProvider>,
      );
      expect(await screen.findByRole('button', { name: 'Goedkeuren mutatie 501' })).toBeEnabled();
    });
  });

  describe('permissions: [] (App-shell)', () => {
    function PageProbe() {
      useEffect(() => {
        void fetch('/api/catalog-import/tasks');
      }, []);
      return <p data-testid="page">pagina</p>;
    }

    function renderShell(identity: ActorIdentity) {
      render(
        <ActorProvider identity={identity}>
          <MemoryRouter initialEntries={['/']}>
            <Routes>
              <Route element={<App />}>
                <Route path="/" element={<PageProbe />} />
              </Route>
            </Routes>
          </MemoryRouter>
        </ActorProvider>,
      );
    }

    it('toont één vlak, houdt het menu zichtbaar en mount de pagina niet (geen fetches)', () => {
      renderShell({ ...TEST_IDENTITY, permissions: [] });
      expect(screen.getByTestId('no-permissions')).toHaveTextContent('U heeft geen rechten voor CatalogImport.');
      expect(screen.getByRole('link', { name: 'Levering uploaden' })).toBeInTheDocument();
      expect(screen.queryByTestId('page')).not.toBeInTheDocument();
      expect(vi.mocked(global.fetch)).not.toHaveBeenCalled();
    });

    it('met minstens één recht: de pagina wordt gemount en het vlak ontbreekt', () => {
      global.fetch = vi.fn(() => Promise.resolve(jsonResponse({}))) as unknown as typeof fetch;
      renderShell(READ_ONLY);
      expect(screen.getByTestId('page')).toBeInTheDocument();
      expect(screen.queryByTestId('no-permissions')).not.toBeInTheDocument();
    });
  });
});
