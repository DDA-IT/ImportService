/**
 * NT-11a (V7) — bewaker: op de schermen van de werkvoorraad, het batchdetail, het uploadresultaat en in de
 * foutmeldingen staat geen ruwe technische code als zichtbare tekst. Een code mag enkel nog in een tooltip
 * (`title`) of onder "Technische details (voor support)" (`<details>`).
 *
 * Werkwijze: elk scherm wordt gerenderd met representatieve (echte) codes in de mockdata; daarna wordt de
 * zichtbare tekst gelezen zonder de `<details>`-blokken (de tooltips zijn attributen en tellen dus niet mee) en
 * gecontroleerd op twee soorten ruwe code: een woord met een underscore (`BLOCKED_BY_X`) en een enumwaarde in
 * hoofdletters die in het woordenboek staat (`SCREENED`, `DRAFT`, ...).
 *
 * NT-11b breidde de bewaker uit naar de bundelschermen; NT-11c (onderaan) naar de behandelgevallen (lijst, detail,
 * afhandeldialogen), de inrichting (boom, versiedetail, bewerkformulier, dialogen) en de sjablonen (lijst, detail,
 * materialiseren, waarden van een koppeling).
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ActorProvider } from '../actor/ActorContext';
import { ApiError } from '../api/http';
import {
  ISSUE_CASE_STATUSES,
  PUBLICATION_BUNDLE_STATUSES,
  PUBLICATION_RUN_STATUSES,
  PUBLICATION_TARGET_MODES,
  type BundleDetail,
  type DecisionRow,
  type FreezePreflight,
  type MutationRow,
  type PublicationRunView,
  type RevisionDetail,
} from '../api/types';
import { ErrorBanner } from '../errors/ErrorBanner';
import { CODE_MESSAGES } from '../errors/codes';
import { BatchActions } from '../features/batches/BatchActions';
import { BatchDetailPage } from '../features/batches/BatchDetailPage';
import { BundleBatchesTab } from '../features/bundles/BundleBatchesTab';
import { BundleDecisionsTab } from '../features/bundles/BundleDecisionsTab';
import { BundleDetailPage } from '../features/bundles/BundleDetailPage';
import { BundleListPage } from '../features/bundles/BundleListPage';
import { BundleMutationsTab } from '../features/bundles/BundleMutationsTab';
import { BundleOverviewTab } from '../features/bundles/BundleOverviewTab';
import { BundlePublicationTab } from '../features/bundles/BundlePublicationTab';
import { IssueCaseDetailPage } from '../features/issuecases/IssueCaseDetailPage';
import { IssueCaseListPage } from '../features/issuecases/IssueCaseListPage';
import { RevisionEditForm } from '../features/setup/RevisionEditForm';
import { SetupOverviewPage } from '../features/setup/SetupOverviewPage';
import { TemplateDetailPage } from '../features/templates/TemplateDetailPage';
import { TemplateListPage } from '../features/templates/TemplateListPage';
import { UploadPage } from '../features/upload/UploadPage';
import { WorkQueuePage } from '../features/workqueue/WorkQueuePage';
import { IssueCodeTerm } from '../terms/IssueCodeTerm';
import { DICTIONARY } from '../terms/index';
import { oldWordingIn, visibleTextOf } from './oldWording';
import { TEST_IDENTITY } from './testIdentity';

const RAW_WITH_UNDERSCORE = /\b[A-Z]+_[A-Z_]+\b/g;

/** De enumwaarden uit het woordenboek die uit één hoofdletterwoord bestaan (SCREENED, DRAFT, ERROR, ...). */
const ENUM_WORDS: string[] = [
  ...new Set(
    Object.values(DICTIONARY).flatMap((entries) => Object.keys(entries).filter((code) => /^[A-Z]{4,}$/.test(code))),
  ),
];
const RAW_ENUM_WORD = new RegExp(`\\b(?:${ENUM_WORDS.join('|')})\\b`, 'g');

function rawCodesIn(container: HTMLElement): string[] {
  const text = visibleTextOf(container);
  return [...(text.match(RAW_WITH_UNDERSCORE) ?? []), ...(text.match(RAW_ENUM_WORD) ?? [])];
}

function expectNoRawCodes(container: HTMLElement, what: string) {
  expect(rawCodesIn(container), `${what}: ruwe code(s) als zichtbare tekst`).toEqual([]);
  expect(oldWordingIn(container), `${what}: oud woord als zichtbare tekst (NT-11d)`).toEqual([]);
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function page<T>(content: T[]) {
  return { content, page: 0, size: 50, totalElements: content.length, totalPages: content.length === 0 ? 0 : 1 };
}

describe('NT-11a — geen ruwe codes als zichtbare tekst', () => {
  const originalFetch = global.fetch;

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    sessionStorage.clear();
    global.fetch = originalFetch;
  });

  it('de hulpfunctie ziet een ruwe code wel (de bewaker bewaakt zichzelf)', () => {
    const { container } = render(
      <div>
        <p>Status SCREENED</p>
        <p>Reden BULK_PRICE_INCIDENT</p>
        <details>
          <summary>Technische details (voor support)</summary>
          <p>BATCH_NOT_FOUND</p>
        </details>
      </div>,
    );
    expect(rawCodesIn(container)).toEqual(['BULK_PRICE_INCIDENT', 'SCREENED']);
  });

  it('IssueCodeTerm: bekende code, code met veldnaam, onbekende CONFIG-code en onbekende code', () => {
    const { container } = render(
      <p>
        <IssueCodeTerm code="BYTE_SIZE_MISMATCH" />|<IssueCodeTerm code="HEADER_FIELD_MISSING:Prijs" />|
        <IssueCodeTerm code="CONFIG_NIEUWE_FOUT" />|<IssueCodeTerm code="EEN_TOEKOMSTIGE_CODE" />|
        <IssueCodeTerm code={null} />
      </p>,
    );
    expect(screen.getByText('Andere bestandsgrootte dan verwacht')).toHaveAttribute(
      'title',
      expect.stringContaining('BYTE_SIZE_MISMATCH'),
    );
    expect(screen.getByText('Verplichte kolom ontbreekt')).toBeInTheDocument();
    expect(screen.getByText('Fout in de beschrijving van het bestand')).toBeInTheDocument();
    const unknown = screen.getByText('Ander probleem');
    expect(unknown).toHaveAttribute('title', expect.stringContaining('EEN_TOEKOMSTIGE_CODE'));
    expectNoRawCodes(container, 'IssueCodeTerm');
  });

  it('werkvoorraad: tegels, keuzelijsten, tabel en blokkeerreden', async () => {
    global.fetch = vi.fn((input: RequestInfo | URL) => {
      const url = input.toString();
      if (url.includes('/batches/summary')) {
        return Promise.resolve(
          json({
            total: 9,
            byStatus: [
              { status: 'RECEIVED', count: 1 },
              { status: 'BLOCKED', count: 2 },
              { status: 'BASELINE_ACCEPTED', count: 1 },
              { status: 'SCREENED', count: 5 },
            ],
            byValidationResult: [
              { validationResult: 'VALID_WITH_WARNINGS', count: 4 },
              { validationResult: 'REVIEW_REQUIRED', count: 1 },
              { validationResult: 'BLOCKING', count: 2 },
              { validationResult: null, count: 2 },
            ],
          }),
        );
      }
      if (url.includes('/issue-cases/summary')) {
        return Promise.resolve(json({ total: 1, byStatus: [{ status: 'AWAITING_REVIEW', count: 1 }] }));
      }
      if (url.includes('/import-links')) {
        return Promise.resolve(json(page([{ id: 1, code: 'LNK-1', name: 'Koppeling Een' }])));
      }
      if (url.includes('/batches')) {
        return Promise.resolve(
          json(
            page([
              {
                batchId: 101,
                importLinkId: 1,
                importLinkCode: 'LNK-1',
                supplierCode: 'SUP1',
                libraryCode: 'LIB1',
                status: 'BLOCKED',
                validationResult: 'BLOCKING',
                createdAt: '2026-09-20T10:00:00Z',
                criticalIssueCount: 3,
                awaitingApprovalCount: null,
                blockedCode: 'RECORD_COUNT_MISMATCH',
              },
              {
                batchId: 102,
                importLinkId: 1,
                importLinkCode: 'LNK-1',
                supplierCode: 'SUP1',
                libraryCode: 'LIB1',
                status: 'SCREENING',
                validationResult: null,
                createdAt: '2026-09-20T11:00:00Z',
                criticalIssueCount: null,
                awaitingApprovalCount: 0,
                blockedCode: 'EEN_TOEKOMSTIGE_CODE',
              },
            ]),
          ),
        );
      }
      return Promise.reject(new Error(`Onverwachte URL in test: ${url}`));
    }) as unknown as typeof fetch;

    const { container } = render(
      <MemoryRouter>
        <WorkQueuePage />
      </MemoryRouter>,
    );
    await screen.findByText('#101');
    expectNoRawCodes(container, 'werkvoorraad');
    // Een bekende blokkeerreden toont haar Nederlandse woord; een onbekende van een latere backend een neutraal woord.
    expect(screen.getByText('Ander aantal regels dan verwacht')).toBeInTheDocument();
    expect(screen.getByText('Ander probleem')).toBeInTheDocument();
  });

  it('batchdetail: kop, blokkade, tellers, foutgroepen, problemen, levering en mutaties', async () => {
    const batch = {
      batchId: 101,
      deliveryId: 7,
      importLinkId: 1,
      importLinkCode: 'LNK-1',
      supplierCode: 'SUP1',
      libraryCode: 'LIB1',
      definitionRevisionId: 3,
      taskRunId: null,
      attemptNo: 1,
      status: 'BLOCKED',
      validationResult: 'BLOCKING',
      startedAt: '2026-09-20T10:00:05Z',
      finishedAt: null,
      rawRecordCount: 100,
      validRecordCount: 95,
      rejectedRecordCount: 5,
      filteredOutCount: 0,
      errorBeforeFilterCount: 0,
      duplicateIdentityCount: null,
      newCount: 12,
      changedCount: 3,
      unchangedCount: 80,
      identityIncidentCount: 1,
      contentMutationCount: 15,
      bulkIncidentCount: 1,
      criticalLineCount: 2,
      criticalIssueCount: 2,
      warningCount: 4,
      awaitingApprovalCount: 12,
      creationOutcome: 'THRESHOLD_EXCEEDED',
      creationScopeCount: 100,
      creationCandidateCount: 12,
      blockedCode: 'CRITICAL_RECORD_THRESHOLD_EXCEEDED',
      blockedReason: 'CRITICAL_RECORD_THRESHOLD_EXCEEDED: too many critical records',
      baselineAcceptedBy: null,
      baselineAcceptedAt: null,
      baselineAcceptReason: null,
      createdAt: '2026-09-20T10:00:00Z',
      createdBy: 'tester',
    };
    const delivery = {
      deliveryId: 7,
      taskId: 1,
      taskRunId: null,
      idempotencyKey: 'manual:REF_1',
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
    const group = {
      id: 55,
      issueCode: 'BULK_PRICE_INCIDENT',
      signature: 'sig',
      severity: 'WARNING',
      issueDomain: 'PRICE',
      controlLevel: 'RECORD',
      impactScope: 'ROW',
      incidentKind: 'PRICE',
      occurrenceCount: 40,
      recordedSampleCount: 10,
      scopeRecordCount: 100,
      sharePercent: 40,
      bulkIncident: true,
      priceComponentCode: null,
      deviationDirection: null,
      dominantFactor: null,
      referenceType: null,
      patternDescription: null,
      firstRowNumber: 3,
      firstDetectedAt: '2026-09-20T10:00:10Z',
      lastDetectedAt: '2026-09-20T10:00:20Z',
      handlingStatus: 'DETECTED',
    };
    const issues = [
      {
        id: 900,
        rowNumber: 4,
        issueCode: 'HEADER_FIELD_MISSING:Prijs',
        fieldName: 'Prijs',
        severity: 'CRITICAL',
        issueDomain: 'DELIVERY',
        controlLevel: 'DELIVERY',
        impactScope: 'DELIVERY',
        handlingStatus: 'AWAITING_REVIEW',
        sourceValue: null,
        expectedValue: null,
        message: 'Kop ontbreekt',
        issueGroupId: null,
        createdAt: '2026-09-20T10:00:10Z',
      },
      {
        id: 901,
        rowNumber: null,
        issueCode: 'EEN_TOEKOMSTIGE_CODE',
        fieldName: null,
        severity: 'BLOCKING',
        issueDomain: 'DELIVERY',
        controlLevel: 'DELIVERY',
        impactScope: 'DELIVERY',
        handlingStatus: 'REOPENED',
        sourceValue: null,
        expectedValue: null,
        message: null,
        issueGroupId: null,
        createdAt: '2026-09-20T10:00:11Z',
      },
    ];
    const base: MutationRow = {
      id: 1,
      batchId: 101,
      actionType: 'UPDATE',
      targetDomain: 'OFFER',
      status: 'AWAITING_APPROVAL',
      statusReason: 'BULK_PRICE_INCIDENT',
      identitySupplier: 'SUP1',
      identitySupplierGroup: 'GRP1',
      identitySupplierReference: 'REF-1',
      identityDiscountCode: 'KORTING',
      identityDiscountState: 'VALUE',
      domainMask: 'ARTICLE,PRICE,PRICE:AKP',
      beforeBasePrice: 12.34,
      afterBasePrice: 15.5,
      basePriceCurrency: 'EUR',
      referenceType: null,
      beforeReferenceValue: null,
      afterReferenceValue: null,
      sourceStateId: 5,
      sourceRowNumber: 42,
      resultSummary: null,
      idempotencyKey: 'key-1',
      createdAt: '2026-09-20T10:00:00Z',
      decidedBy: 'An',
      decidedAt: '2026-09-21T10:00:00Z',
      decidedFromStatus: 'PLANNED',
      decisionId: 9,
      identityHash: 'a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90',
    };
    const mutations: MutationRow[] = [
      base,
      { ...base, id: 2, actionType: 'CREATE', status: 'PLANNED', statusReason: 'INITIAL_LOAD_REQUIRES_APPROVAL', identityDiscountState: 'NOT_USED', domainMask: null },
      {
        ...base,
        id: 3,
        actionType: 'IDENTITY_REFERENCE_INCIDENT',
        status: 'BLOCKED',
        statusReason: 'REUSED',
        referenceType: 'E_MARK_ARTICLE_REFERENCE',
        beforeReferenceValue: '123',
        afterReferenceValue: '456',
      },
      { ...base, id: 4, actionType: 'IMPORT_MARKER', status: 'RECORDED', statusReason: null, identityHash: null, decidedFromStatus: null },
      { ...base, id: 5, status: 'SKIPPED', statusReason: 'BASELINE_ACCEPTED_WITHOUT_PUBLICATION' },
      { ...base, id: 6, status: 'REJECTED', statusReason: 'EEN_TOEKOMSTIGE_REDEN' },
    ];

    global.fetch = vi.fn((input: RequestInfo | URL) => {
      const url = input.toString();
      if (url.includes('/issue-groups')) return Promise.resolve(json(page([group])));
      if (url.includes('/issues')) return Promise.resolve(json(page(issues)));
      if (url.includes('/mutations')) return Promise.resolve(json(page(mutations)));
      if (url.includes('/deliveries/')) return Promise.resolve(json(delivery));
      if (/\/batches\/[^/?]+$/.test(url)) return Promise.resolve(json(batch));
      return Promise.reject(new Error(`Onverwachte URL in test: ${url}`));
    }) as unknown as typeof fetch;

    const { container } = render(
      <ActorProvider identity={TEST_IDENTITY}>
        <MemoryRouter initialEntries={['/batches/101']}>
          <Routes>
            <Route path="/batches/:batchId" element={<BatchDetailPage />} />
          </Routes>
        </MemoryRouter>
      </ActorProvider>,
    );

    await screen.findByTestId('batch-blocked');
    await screen.findByTestId('batch-issue-groups');
    await waitFor(() => expect(screen.getAllByText('Veel gelijke prijsafwijkingen').length).toBeGreaterThan(0));
    await screen.findByText('prijzen.csv');
    await screen.findByText('Kop ontbreekt');
    await screen.findByText('Overgeslagen door de nulmeting', { selector: 'span' });
    expectNoRawCodes(container, 'batchdetail');
    // Onbekende waarden van een latere backend tonen een neutraal woord.
    expect(screen.getAllByText('Andere reden').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Ander probleem').length).toBeGreaterThan(0);
  });

  it('acties op een batch: nulmeting/bundel en hervatten', () => {
    for (const status of ['SCREENED', 'MUTATING']) {
      const { container, unmount } = render(
        <ActorProvider identity={TEST_IDENTITY}>
          <MemoryRouter>
            <BatchActions batch={{ batchId: 101, status } as never} onChanged={() => {}} />
          </MemoryRouter>
        </ActorProvider>,
      );
      expectNoRawCodes(container, `acties bij status ${status}`);
      unmount();
    }
  });

  it('uploadresultaat: status, reden van tegenhouden en tellers', async () => {
    const tasks = {
      content: [
        {
          id: 5,
          name: 'Handmatige levering',
          active: true,
          triggerType: 'MANUAL',
          preventConcurrentRuns: true,
          importLinkId: 1,
          importLinkCode: 'LNK-1',
          supplierCode: 'SUP1',
          libraryCode: 'LIB1',
          activeRevisionId: 3,
          importDefinitionId: 2,
          lastRunStartedAt: null,
          lastRunFinishedAt: null,
        },
      ],
      page: 0,
      size: 200,
      totalElements: 1,
      totalPages: 1,
    };
    global.fetch = vi.fn((input: RequestInfo | URL) => {
      const url = input.toString();
      if (url.endsWith('/deliveries')) {
        return Promise.resolve(
          json(
            {
              deliveryId: 9,
              batchId: 55,
              deliveryReference: 'levering.csv#abc123abc123',
              status: 'BLOCKED',
              blockedCode: 'BYTE_SIZE_MISMATCH',
              rawRecordCount: 7,
              validRecordCount: 6,
              rejectedRecordCount: 1,
              duplicateIdentityCount: null,
              newCount: 6,
              changedCount: 0,
              unchangedCount: 0,
              contentMutationCount: 6,
            },
            201,
          ),
        );
      }
      if (url.includes('/tasks')) return Promise.resolve(json(tasks));
      return Promise.reject(new Error(`Onverwachte URL in test: ${url}`));
    }) as unknown as typeof fetch;

    const { container } = render(
      <ActorProvider identity={TEST_IDENTITY}>
        <MemoryRouter>
          <UploadPage />
        </MemoryRouter>
      </ActorProvider>,
    );
    await screen.findByRole('option', { name: /LNK-1 — Handmatige levering/ });
    fireEvent.change(screen.getByLabelText(/^Taak/), { target: { value: '5' } });
    fireEvent.change(screen.getByLabelText(/^Bestand \(CSV\)/), {
      target: { files: [new File(['a;b\n1;2\n'], 'levering.csv', { type: 'text/csv' })] },
    });
    await waitFor(() => expect((screen.getByLabelText(/^Referentie/) as HTMLInputElement).value).toContain('#'));
    fireEvent.click(screen.getByRole('button', { name: 'Uploaden' }));

    await screen.findByTestId('upload-result');
    expectNoRawCodes(container, 'uploadresultaat');
  });

  it('elke foutmelding: Nederlandse titel, uitleg en "wat nu"; de code enkel onder "Technische details"', () => {
    const codes = Object.keys(CODE_MESSAGES);
    for (const code of codes) {
      const { container, unmount } = render(
        <ErrorBanner error={new ApiError(409, code, 'Een gewone servermelding', '/ergens/1')} />,
      );
      expectNoRawCodes(container, `ErrorBanner ${code}`);
      // De technische regel staat er wel, maar in het inklapbare blok.
      const details = container.querySelector('details');
      expect(details, `${code}: Technische details ontbreekt`).not.toBeNull();
      expect(details?.textContent).toContain(`${code} · HTTP 409 · /ergens/1`);
      unmount();
    }
  });

  it('foutmeldingen zonder bekende code: onbekende code, 400 zonder code, 413, 500 en netwerkfout', () => {
    const errors = [
      new ApiError(409, 'EEN_TOEKOMSTIGE_CODE', 'Some future thing', '/x'),
      new ApiError(409, 'CONFIG_NIEUWE_FOUT', 'Config', '/x'),
      new ApiError(404, 'IETS_NOT_FOUND', 'Missing', '/x'),
      new ApiError(400, null, 'Missing reason', '/x'),
      new ApiError(413, null, null, '/x'),
      new ApiError(500, null, null, '/x'),
      new ApiError(0, null, null, '/x'),
    ];
    for (const error of errors) {
      const { container, unmount } = render(<ErrorBanner error={error} />);
      expectNoRawCodes(container, `ErrorBanner ${error.code ?? error.status}`);
      unmount();
    }
  });
});

// ---------------------------------------------------------------------------------------------------
// NT-11b — de bundelschermen: lijst, overzicht (met bevriezen en annuleren), leden, mutaties (met groepsactie en
// beslissing), beslissingsregister en publicatie. Alles gerenderd met representatieve (echte) codes, ook een
// onbekende code van een latere backend; de bewaker leest `document.body`, dus ook de dialogen.
// ---------------------------------------------------------------------------------------------------

function bundleDetail(overrides: Partial<BundleDetail> = {}): BundleDetail {
  return {
    id: 42,
    bundleReference: 'BND-2026-001',
    description: 'Najaarsprijzen',
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
    batchCount: 2,
    contentMutationCount: 12,
    readyCount: 5,
    rejectedCount: 1,
    blockedCount: 1,
    expiredCount: null,
    identityIncidentCount: 1,
    bulkIncidentCount: null,
    criticalIssueCount: null,
    warningCount: null,
    staleMutationCount: 2,
    contentHash: null,
    plannedCount: 7,
    awaitingApprovalCount: 3,
    expirableCount: 10,
    ...overrides,
  };
}

function bundleMutation(overrides: Partial<MutationRow> = {}): MutationRow {
  return {
    id: 1,
    batchId: 101,
    actionType: 'UPDATE',
    targetDomain: 'OFFER',
    status: 'AWAITING_APPROVAL',
    statusReason: 'BULK_PRICE_INCIDENT',
    identitySupplier: 'SUP1',
    identitySupplierGroup: 'GRP1',
    identitySupplierReference: 'REF-1',
    identityDiscountCode: null,
    identityDiscountState: 'NOT_USED',
    domainMask: 'ARTICLE,PRICE,PRICE:AKP',
    beforeBasePrice: 12.34,
    afterBasePrice: 15.5,
    basePriceCurrency: 'EUR',
    referenceType: null,
    beforeReferenceValue: null,
    afterReferenceValue: null,
    sourceStateId: 5,
    sourceRowNumber: 42,
    resultSummary: null,
    idempotencyKey: 'key-1',
    createdAt: '2026-09-20T10:00:00Z',
    decidedBy: null,
    decidedAt: null,
    decidedFromStatus: null,
    decisionId: null,
    identityHash: 'a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90',
    ...overrides,
  };
}

function publicationRun(overrides: Partial<PublicationRunView> = {}): PublicationRunView {
  return {
    id: 1,
    bundleId: 42,
    targetMode: 'SIMULATION',
    attempt: 1,
    status: 'SIMULATED',
    requestedBy: 'An Beslisser',
    requestedAt: '2026-09-27T10:00:00Z',
    startedAt: '2026-09-27T10:00:01Z',
    finishedAt: '2026-09-27T10:00:02Z',
    bundleContentHash: 'ab'.repeat(32),
    snapshotHash: 'cd'.repeat(32),
    payloadHash: 'ef'.repeat(32),
    artifactSha256: '12'.repeat(32),
    artifactByteSize: 100,
    rowCount: 5,
    incompleteRowCount: 2,
    failureCode: null,
    failureMessage: null,
    simulationOnly: true,
    writesToProdis: false,
    contractStatus: 'UNVERIFIED_FIELD_INVENTORY',
    previewSpecVersion: 'v1',
    snapshotSpecVersion: 'v1',
    ...overrides,
  };
}

describe('NT-11b — bundelschermen: geen ruwe codes als zichtbare tekst', () => {
  const originalFetch = global.fetch;
  let detail: BundleDetail;
  let preflight: FreezePreflight;
  let mutations: MutationRow[];
  let decisions: DecisionRow[];
  let runs: PublicationRunView[];

  function installFetch() {
    global.fetch = vi.fn((input: RequestInfo | URL) => {
      const path = input.toString().split('?')[0] ?? '';
      if (path.endsWith('/bundles/42/freeze-check')) return Promise.resolve(json(preflight));
      if (path.endsWith('/bundles/42/batches')) {
        return Promise.resolve(
          json(
            page([
              {
                id: 1,
                bundleId: 42,
                batchId: 101,
                importLinkId: 1,
                addedBy: 'An Beslisser',
                addedAt: '2026-09-20T10:00:00Z',
                removedBy: null,
                removedAt: null,
                removedReason: null,
                active: true,
                batchStatus: 'SCREENED',
                batchContentMutationCount: 5,
              },
              {
                id: 2,
                bundleId: 42,
                batchId: 102,
                importLinkId: 1,
                addedBy: 'An Beslisser',
                addedAt: '2026-09-20T10:00:00Z',
                removedBy: 'An Beslisser',
                removedAt: '2026-09-21T10:00:00Z',
                removedReason: 'Verkeerde levering',
                active: false,
                batchStatus: 'BLOCKED',
                batchContentMutationCount: null,
              },
            ]),
          ),
        );
      }
      if (path.endsWith('/bundles/candidates')) {
        return Promise.resolve(
          json(
            page([
              {
                batchId: 103,
                importLinkId: 1,
                status: 'SCREENED',
                validationResult: 'VALID_WITH_WARNINGS',
                contentMutationCount: 4,
                finishedAt: '2026-09-20T12:00:00Z',
              },
            ]),
          ),
        );
      }
      if (path.includes('/import-links')) {
        return Promise.resolve(json(page([{ id: 1, code: 'LNK-1', name: 'Koppeling Een' }])));
      }
      if (path.endsWith('/bundles/42/mutations')) return Promise.resolve(json(page(mutations)));
      if (path.endsWith('/bundles/42/decisions')) return Promise.resolve(json(page(decisions)));
      if (path.endsWith('/bundles/42/publication-runs')) return Promise.resolve(json(runs));
      if (path.endsWith('/bundles/42')) return Promise.resolve(json(detail));
      if (path.endsWith('/bundles')) {
        return Promise.resolve(
          json(
            page(
              PUBLICATION_BUNDLE_STATUSES.map((status, index) => ({
                ...bundleDetail({ id: 100 + index, bundleReference: `BND-${index}`, status }),
                targetMode: PUBLICATION_TARGET_MODES[index % PUBLICATION_TARGET_MODES.length],
              })),
            ),
          ),
        );
      }
      return Promise.reject(new Error(`Onverwachte URL in test: ${path}`));
    }) as unknown as typeof fetch;
  }

  function renderBundle(path: string) {
    return render(
      <ActorProvider identity={TEST_IDENTITY}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/bundles" element={<BundleListPage />} />
            <Route path="/bundles/:bundleId" element={<BundleDetailPage />}>
              <Route index element={<BundleOverviewTab />} />
              <Route path="batches" element={<BundleBatchesTab />} />
              <Route path="mutations" element={<BundleMutationsTab />} />
              <Route path="decisions" element={<BundleDecisionsTab />} />
              <Route path="publication" element={<BundlePublicationTab />} />
            </Route>
          </Routes>
        </MemoryRouter>
      </ActorProvider>,
    );
  }

  beforeEach(() => {
    detail = bundleDetail();
    preflight = {
      freezable: false,
      blockerCodes: [
        'BUNDLE_NOT_ASSEMBLING',
        'BUNDLE_EMPTY',
        'BUNDLE_HAS_UNDECIDED_MUTATIONS',
        'SOURCE_STATE_CHANGED_SINCE_SCREENING',
        'BUNDLE_OFFER_CONFLICT',
        'OFFER_ALREADY_IN_ANOTHER_BUNDLE',
        'EEN_NIEUWE_BLOKKADE',
      ],
      batchCount: 2,
      plannedCount: 7,
      awaitingApprovalCount: 3,
      staleMutationCount: 2,
      inBundleConflicts: ['link 7 offer SUP1/GRP1/REF-1 in 2 batches (e.g. 101 and 102)'],
      crossBundleConflicts: ['link 7 offer SUP2/GRP2/REF-2 (batch 101 here, batch 301 in bundle 9)'],
    };
    mutations = [
      bundleMutation(),
      bundleMutation({ id: 2, actionType: 'CREATE', status: 'PLANNED', statusReason: 'INITIAL_LOAD_REQUIRES_APPROVAL', domainMask: null }),
      bundleMutation({
        id: 3,
        actionType: 'IDENTITY_REFERENCE_INCIDENT',
        status: 'BLOCKED',
        statusReason: 'REUSED',
        referenceType: 'E_MARK_ARTICLE_REFERENCE',
        beforeReferenceValue: '123',
        afterReferenceValue: '456',
      }),
      bundleMutation({ id: 4, actionType: 'IMPORT_MARKER', status: 'RECORDED', statusReason: null, identityHash: null }),
      bundleMutation({
        id: 5,
        status: 'REJECTED',
        statusReason: 'EEN_TOEKOMSTIGE_REDEN',
        decidedBy: 'Eerdere Beslisser',
        decidedAt: '2026-09-21T08:00:00Z',
        decidedFromStatus: 'AWAITING_APPROVAL',
        decisionId: 7,
      }),
    ];
    const decision = (overrides: Partial<DecisionRow>): DecisionRow => ({
      id: 1,
      bundleId: 42,
      mutationId: 501,
      decisionKind: 'APPROVE',
      decisionScope: 'MUTATION',
      selectionFilter: null,
      previousStatus: 'AWAITING_APPROVAL',
      newStatus: 'READY_FOR_PUBLICATION',
      affectedCount: 1,
      decidedBy: 'An Beslisser',
      decidedAt: '2026-09-20T10:00:00Z',
      reason: 'Prijs gecontroleerd',
      ...overrides,
    });
    decisions = [
      decision({}),
      decision({ id: 2, decisionKind: 'REJECT', previousStatus: 'READY_FOR_PUBLICATION', newStatus: 'REJECTED' }),
      decision({
        id: 3,
        decisionKind: 'APPROVE',
        decisionScope: 'GROUP',
        mutationId: null,
        affectedCount: 40,
        previousStatus: 'PLANNED',
        newStatus: 'READY_FOR_PUBLICATION',
        selectionFilter:
          'batchId=3;status=AWAITING_APPROVAL;statusReason=BULK_PRICE_INCIDENT;actionType=UPDATE;' +
          'identityHash=a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90;nieuw_veld=X',
      }),
      decision({
        id: 4,
        decisionKind: 'AUTO_APPROVE_PLANNED',
        decisionScope: 'BUNDLE',
        mutationId: null,
        previousStatus: 'PLANNED',
        newStatus: 'READY_FOR_PUBLICATION',
        selectionFilter: 'status=PLANNED',
      }),
      decision({
        id: 5,
        decisionKind: 'FREEZE',
        decisionScope: 'BUNDLE',
        mutationId: null,
        previousStatus: 'ASSEMBLING',
        newStatus: 'FROZEN',
      }),
      decision({
        id: 6,
        decisionKind: 'CANCEL',
        decisionScope: 'BUNDLE',
        mutationId: null,
        previousStatus: 'FROZEN',
        newStatus: 'CANCELLED',
      }),
      decision({ id: 7, decisionKind: 'EEN_TOEKOMSTIGE_SOORT' as never, decisionScope: 'EEN_TOEKOMSTIG_BEREIK' as never }),
    ];
    runs = PUBLICATION_RUN_STATUSES.map((status, index) =>
      publicationRun({
        id: index + 1,
        attempt: index + 1,
        status,
        failureCode: status === 'FAILED' ? 'ARTIFACT_WRITE_FAILED' : null,
        failureMessage: status === 'FAILED' ? 'disk full' : null,
      }),
    );
    installFetch();
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    sessionStorage.clear();
    global.fetch = originalFetch;
  });

  it('bundellijst met elke status en doelmodus, en het aanmaakformulier met de waarschuwing voor een echte publicatie', async () => {
    renderBundle('/bundles');
    await screen.findByRole('link', { name: 'BND-0' });
    expectNoRawCodes(document.body, 'bundellijst');

    fireEvent.change(screen.getByLabelText(/^Doelmodus/), { target: { value: 'PRODUCTION' } });
    await screen.findByRole('alert');
    expect(screen.getByRole('alert').textContent).toContain('echte publicatie');
    expectNoRawCodes(document.body, 'bundellijst met waarschuwing');
  });

  it('bundeloverzicht in elke status (tellers, acties, redenen van uitgeschakelde knoppen)', async () => {
    for (const status of PUBLICATION_BUNDLE_STATUSES) {
      detail = bundleDetail({
        status,
        contentHash: status === 'ASSEMBLING' ? null : 'abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789',
        frozenBy: status === 'ASSEMBLING' ? null : 'An Beslisser',
        frozenAt: status === 'ASSEMBLING' ? null : '2026-09-24T10:00:00Z',
        frozenReason: status === 'ASSEMBLING' ? null : 'Klaar',
      });
      const { unmount } = renderBundle('/bundles/42');
      await screen.findByRole('heading', { name: 'BND-2026-001' });
      await screen.findByText('Tellers');
      expectNoRawCodes(document.body, `overzicht bij status ${status}`);
      unmount();
    }
  });

  it('bevriezen (voorcontrole met bekende en onbekende blokkades) en annuleren', async () => {
    renderBundle('/bundles/42');
    fireEvent.click(await screen.findByRole('button', { name: 'Bevriezen' }));
    await screen.findByTestId('freeze-planned-count');
    expectNoRawCodes(document.body, 'bevriezen');
    // De blokkadecodes blijven opvraagbaar, onder "Technische details".
    expect(screen.getByText('BUNDLE_OFFER_CONFLICT')).toBeInTheDocument();
    expect(screen.getByText('EEN_NIEUWE_BLOKKADE')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Sluiten zonder bevriezen' }));

    fireEvent.click(screen.getByRole('button', { name: 'Annuleren' }));
    await screen.findByTestId('cancel-expiring-count');
    expectNoRawCodes(document.body, 'annuleren');
  });

  it('leden en kandidaten', async () => {
    renderBundle('/bundles/42/batches');
    await screen.findByText('103');
    expectNoRawCodes(document.body, 'leden');
  });

  it('mutaties: lijst, filter op statusreden, groepsactie en beslissingsdialoog', async () => {
    renderBundle('/bundles/42/mutations');
    await screen.findByRole('table');
    await screen.findByText('Tegengehouden', { selector: 'span' });
    expectNoRawCodes(document.body, 'mutatielijst');

    fireEvent.change(screen.getByLabelText('Statusreden'), { target: { value: 'BULK_PRICE_INCIDENT' } });
    fireEvent.change(screen.getByLabelText('Status'), { target: { value: 'AWAITING_APPROVAL' } });
    const groupButton = await screen.findByRole('button', { name: /^Groep goedkeuren \(/ });
    await waitFor(() => expect(groupButton).toBeEnabled());
    expectNoRawCodes(document.body, 'mutatielijst met filter');

    fireEvent.click(groupButton);
    await screen.findByRole('dialog');
    expectNoRawCodes(document.body, 'groepsactie');
    fireEvent.click(screen.getByRole('button', { name: 'Annuleren' }));

    fireEvent.click(await screen.findByRole('button', { name: 'Goedkeuren mutatie 1' }));
    await screen.findByRole('dialog');
    expectNoRawCodes(document.body, 'beslissing over één mutatie');
    fireEvent.click(screen.getByRole('button', { name: 'Annuleren' }));

    // Een herziening noemt de eerdere beslisser.
    fireEvent.click(await screen.findByRole('button', { name: 'Goedkeuren mutatie 5' }));
    await screen.findByRole('dialog');
    expectNoRawCodes(document.body, 'herziening');
  });

  it('beslissingsregister: elke soort, elk bereik, statusovergangen en de filter van een groepsbeslissing', async () => {
    renderBundle('/bundles/42/decisions');
    await screen.findByText('Bevriezing');
    expectNoRawCodes(document.body, 'beslissingsregister');
    // Bekende en onbekende waarden: een Nederlands woord, de code enkel onder "Technische details".
    expect(screen.getByText('Automatische goedkeuring bij het bevriezen')).toBeInTheDocument();
    expect(screen.getAllByText('Andere beslissing').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Ander bereik').length).toBeGreaterThan(0);
  });

  it('publicatie: elke status van een run (ook een mislukte, een lopende en een proefrun)', async () => {
    detail = bundleDetail({ status: 'FROZEN', contentHash: 'abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789' });
    renderBundle('/bundles/42/publication');
    await screen.findByRole('button', { name: 'Proefpublicatie starten' });
    await screen.findByText('#1 — poging 1');
    expectNoRawCodes(document.body, 'publicatie');
  });

  it('publicatie van een bundel die nog niet bevroren is', async () => {
    renderBundle('/bundles/42/publication');
    await screen.findByText(/alleen gestart worden voor een bevroren bundel/);
    expectNoRawCodes(document.body, 'publicatie zonder bevroren bundel');
  });
});

// ---------------------------------------------------------------------------------------------------
// NT-11c — behandelgevallen (lijst, detail, afhandeldialogen), inrichting (boom, versiedetail, bewerkformulier,
// dialogen) en sjablonen (lijst, detail, materialiseren, waarden van een koppeling). Alles gerenderd met echte codes,
// ook een onbekende code van een latere backend; de bewaker leest `document.body`, dus ook de dialogen.
// ---------------------------------------------------------------------------------------------------

function issueCaseRow(overrides: Record<string, unknown> = {}) {
  return {
    id: 501,
    importLinkId: 1,
    issueCode: 'PRICE_MISSING',
    signature: 'sig_een',
    severity: 'ERROR',
    issueDomain: 'IDENTITY_REFERENCE',
    controlLevel: 'STRUCTURE',
    impactScope: 'LIBRARY',
    incidentKind: 'IDENTITY',
    priceComponentCode: 'VKP_GROSS',
    referenceType: 'E_MARK_ARTICLE_REFERENCE',
    status: 'AWAITING_REVIEW',
    observationCount: 2,
    totalOccurrenceCount: 42,
    firstSeenAt: '2026-09-01T10:00:00Z',
    lastSeenAt: '2026-09-20T10:00:00Z',
    firstSeenBatchId: 10,
    lastSeenBatchId: 15,
    lastSeenRevisionId: 2,
    reopenCount: 1,
    statusReason: 'Bekende bug',
    statusChangedAt: '2026-09-21T10:00:00Z',
    statusChangedBy: 'Jan',
    statusChangedBySubject: 'jan-sub',
    decisionRevisionId: 2,
    createdAt: '2026-09-01T10:00:00Z',
    updatedAt: '2026-09-21T10:00:00Z',
    hasUnreviewedRecurrence: true,
    ...overrides,
  };
}

const SETUP_REVISIONS = [
  { id: 97, definitionId: 10, revisionNumber: 1, status: 'WITHDRAWN' },
  { id: 98, definitionId: 10, revisionNumber: 2, status: 'SUPERSEDED' },
  { id: 99, definitionId: 10, revisionNumber: 3, status: 'ACTIVE' },
  { id: 100, definitionId: 10, revisionNumber: 4, status: 'DRAFT' },
];

/** Het volledige revisiedetail (E1) met realistische codes, ook een onbekende waarde van een latere backend. */
function setupRevisionDetail(id: number): RevisionDetail {
  const row = SETUP_REVISIONS.find((candidate) => candidate.id === id)!;
  return {
    id,
    definitionId: 10,
    revisionNumber: row.revisionNumber,
    status: row.status,
    basedOnRevisionId: 99,
    changeReason: 'Nieuwe prijslijst',
    identityProfileKind: 'FOUR_PART_WITH_DISCOUNT_CODE',
    identitySupplierField: 'Leverancier',
    identitySupplierGroupField: 'Groep',
    identitySupplierReferenceField: 'Referentie',
    identityDiscountCodeField: 'Korting',
    structureFormat: 'CSV',
    structureCharset: 'UTF-8',
    structureDelimiter: ';',
    structureQuoteChar: '"',
    structureHasHeader: true,
    structureHeaderLineNumber: 1,
    structureFieldReferenceKind: 'HEADER_NAME',
    structureExpectedColumnCount: 12,
    accessDeliverySetKind: 'DELTA',
    recordBasePriceField: 'Prijs',
    recordDescriptionField: 'Omschrijving',
    recordCurrencyField: null,
    recordCanonicalisationVersion: 2,
    basePriceZeroAllowed: false,
    basePriceNegativeAllowed: true,
    priceDeviationPercent: 20,
    priceDeviationSeverity: 'WARNING',
    priceDerivationTolerance: 0.01,
    priceAvgShortWindow: 50,
    priceAvgLongWindow: 200,
    priceControlModel: 'DEVIATION',
    creationThresholdAbsolute: 100,
    creationThresholdSharePercent: 30,
    maxCriticalRecords: 0,
    maxRejectedRecords: null,
    maxCriticalSharePercent: 5,
    maxRejectedSharePercent: 10,
    bulkIncidentSharePercent: 15,
    accessVersion: 1,
    accessConfigHash: 'aaaa',
    structureVersion: 1,
    structureConfigHash: 'bbbb',
    recordRulesVersion: 1,
    recordRulesConfigHash: 'cccc',
    compositeConfigHash: 'dddd',
    createdAt: '2026-09-28T08:00:00Z',
    createdBy: 'An Beslisser',
    createdBySubject: 'test-sub',
    updatedAt: '2026-09-28T08:00:00Z',
    approvedAt: null,
    approvedBy: null,
    approvedBySubject: null,
    mappings: [
      {
        id: 5001,
        revisionId: id,
        sequenceNumber: 1,
        targetFieldCode: 'VKP_GROSS_PCT',
        sourceReference: 'Bruto',
        valueKind: 'SOURCE_FIELD',
        criticality: 'NON_CRITICAL',
      },
      {
        id: 5002,
        revisionId: id,
        sequenceNumber: 2,
        targetFieldCode: 'EEN_NIEUW_DOELVELD',
        sourceReference: 'Nieuw',
        valueKind: 'EEN_NIEUWE_HERKOMST',
        criticality: 'EEN_NIEUWE_KRITIEK',
      },
    ],
    filters: [
      {
        id: 6001,
        revisionId: id,
        sequenceNumber: 1,
        sourceReference: 'Status',
        operator: 'NOT_EQUALS',
        compareValue: 'X',
        outcome: 'EXCLUDE',
      },
      {
        id: 6002,
        revisionId: id,
        sequenceNumber: 2,
        sourceReference: 'Soort',
        operator: 'EEN_NIEUWE_VERGELIJKING',
        compareValue: null,
        outcome: 'REJECT',
      },
    ],
    fieldCriticalities: [
      { revisionId: id, fieldKey: 'SUPPLIER_GROUP', criticality: 'CRITICAL' },
      { revisionId: id, fieldKey: 'EEN_NIEUW_VELD', criticality: 'NON_CRITICAL' },
    ],
    bookmarks: [
      {
        id: 1000,
        revisionId: id,
        name: 'BESTANDS_PREFIX',
        label: 'Bestandsprefix',
        description: null,
        dataType: 'TEXT',
        valueScope: 'DEFINITION',
        ownerRole: 'admin',
        required: true,
        defaultValue: null,
        allowedValues: null,
        validationPattern: null,
        sortOrder: 1,
        usages: [],
      },
      {
        id: 1001,
        revisionId: id,
        name: 'SOORT_LEVERING',
        label: 'Soort levering',
        description: null,
        dataType: 'ENUM',
        valueScope: 'LINK',
        ownerRole: 'admin',
        required: false,
        defaultValue: 'A',
        allowedValues: 'A,B',
        validationPattern: null,
        sortOrder: 2,
        usages: [],
      },
    ],
    bookmarkValues: [
      {
        bookmarkName: 'BESTANDS_PREFIX',
        dataType: 'TEXT',
        valueText: 'VRO_',
        sourceTemplateRevisionId: 300,
        filledAt: '2026-09-28T08:00:00Z',
        filledBy: 'An Beslisser',
        filledBySubject: 'test-sub',
      },
    ],
  } as unknown as RevisionDetail;
}

describe('NT-11c — behandelgevallen, inrichting en sjablonen: geen ruwe codes als zichtbare tekst', () => {
  const originalFetch = global.fetch;

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    sessionStorage.clear();
    global.fetch = originalFetch;
  });

  it('behandelgevallenlijst: elke status, bekende en onbekende ernst en vaststelling', async () => {
    const codes = ['PRICE_MISSING', 'HEADER_FIELD_MISSING:Prijs', 'EEN_TOEKOMSTIGE_CODE', 'CONFIG_NIEUWE_FOUT'];
    const severities = ['CRITICAL', 'WARNING', 'EEN_NIEUWE_ERNST', 'BLOCKING'];
    const rows = ISSUE_CASE_STATUSES.map((status, index) =>
      issueCaseRow({ id: 600 + index, status, issueCode: codes[index], severity: severities[index] }),
    );
    global.fetch = vi.fn((input: RequestInfo | URL) => {
      const url = input.toString();
      if (url.includes('/import-links')) {
        return Promise.resolve(json(page([{ id: 1, code: 'LNK-1', name: 'Koppeling Een' }])));
      }
      if (url.includes('/issue-cases')) return Promise.resolve(json(page(rows)));
      return Promise.reject(new Error(`Onverwachte URL in test: ${url}`));
    }) as unknown as typeof fetch;

    render(
      <MemoryRouter>
        <IssueCaseListPage />
      </MemoryRouter>,
    );
    await screen.findByRole('link', { name: '#600' });
    expectNoRawCodes(document.body, 'behandelgevallenlijst');
    // Een bekende code toont haar Nederlandse woord, een onbekende een neutraal woord; de code staat in de tooltip.
    expect(screen.getAllByText('Ander probleem').length).toBeGreaterThan(0);
    expect(screen.getByText('Andere ernst')).toBeInTheDocument();
    expect(screen.getAllByText('Verplichte kolom ontbreekt', { selector: 'span' }).length).toBeGreaterThan(0);
  });

  it('behandelgeval: elke status met classificatie en geschiedenis, en de dialoog van de afhandelactie', async () => {
    const events = [
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
        reason: 'Bekende bug',
        source: 'HUMAN',
        changedBy: 'Jan',
        changedBySubject: 'jan-sub',
        changedAt: '2026-09-21T10:00:00Z',
        observationBatchId: null,
      },
      {
        id: 3,
        eventKind: 'EEN_NIEUWE_SOORT',
        previousStatus: 'REJECTED',
        newStatus: 'AUTO_RESOLVED',
        reason: 'Vanzelf opgelost',
        source: 'EEN_NIEUWE_BRON',
        changedBy: null,
        changedBySubject: null,
        changedAt: '2026-09-22T10:00:00Z',
        observationBatchId: null,
      },
    ];
    const observations = [
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

    for (const status of ISSUE_CASE_STATUSES) {
      global.fetch = vi.fn((input: RequestInfo | URL) => {
        const url = input.toString();
        if (url.includes('/observations')) return Promise.resolve(json(observations));
        if (url.includes('/events')) return Promise.resolve(json(events));
        if (/\/issue-cases\/\d+$/.test(url)) return Promise.resolve(json(issueCaseRow({ status })));
        return Promise.reject(new Error(`Onverwachte URL in test: ${url}`));
      }) as unknown as typeof fetch;

      const { unmount } = render(
        <ActorProvider identity={TEST_IDENTITY}>
          <MemoryRouter initialEntries={['/issue-cases/501']}>
            <Routes>
              <Route path="/issue-cases/:caseId" element={<IssueCaseDetailPage />} />
            </Routes>
          </MemoryRouter>
        </ActorProvider>,
      );
      await screen.findByTestId('issue-case-events');
      await screen.findByTestId('issue-case-observations');
      expectNoRawCodes(document.body, `behandelgeval in status ${status}`);

      const actionName = status === 'AWAITING_REVIEW' ? 'Corrigeren' : 'Heropenen';
      fireEvent.click(screen.getByRole('button', { name: actionName }));
      await screen.findByRole('dialog');
      expectNoRawCodes(document.body, `dialoog "${actionName}" bij status ${status}`);
      unmount();
    }
  });

  it('inrichting: boom, versies in elke status, conceptdetail met bewerkformulier, dialogen en niet-bewerkbare versie', async () => {
    const organisations = [{ id: 1, code: 'ORG-1', name: 'VROOAM', type: 'PURCHASING_ASSOCIATION', active: true }];
    const definitions = [
      {
        id: 10,
        code: 'DEF-10',
        name: 'Beschrijving van het bestand',
        usageType: 'OWN_DEFINITION',
        sourceOrganisationId: 1,
        sourceOrganisationCode: 'ORG-1',
        activeRevisionId: 99,
      },
    ];
    const links = [
      {
        id: 1,
        code: 'LNK-1',
        name: 'Koppeling Een',
        supplierCode: 'SUP1',
        supplierName: 'Leverancier Een',
        libraryCode: 'LIB1',
        active: true,
        importDefinitionId: 10,
      },
    ];
    const tasks = [
      {
        id: 7,
        name: 'Klare taak',
        active: true,
        triggerType: 'MANUAL',
        preventConcurrentRuns: true,
        importLinkId: 1,
        importLinkCode: 'LNK-1',
        supplierCode: 'SUP1',
        libraryCode: 'LIB1',
        activeRevisionId: 99,
        importDefinitionId: 10,
        lastRunStartedAt: null,
        lastRunFinishedAt: null,
      },
      {
        id: 8,
        name: 'Taak zonder actieve versie',
        active: false,
        triggerType: 'SCHEDULED',
        preventConcurrentRuns: true,
        importLinkId: 1,
        importLinkCode: 'LNK-1',
        supplierCode: 'SUP1',
        libraryCode: 'LIB1',
        activeRevisionId: null,
        importDefinitionId: 10,
        lastRunStartedAt: null,
        lastRunFinishedAt: null,
      },
    ];
    global.fetch = vi.fn((input: RequestInfo | URL) => {
      const url = input.toString();
      const detail = /\/definitions\/10\/revisions\/(\d+)/.exec(url);
      if (detail !== null) return Promise.resolve(json(setupRevisionDetail(Number(detail[1]))));
      if (url.includes('/definitions/10/revisions')) return Promise.resolve(json(page(SETUP_REVISIONS)));
      if (url.includes('/tasks')) return Promise.resolve(json(page(tasks)));
      if (url.includes('/import-links')) return Promise.resolve(json(page(links)));
      if (url.includes('/definitions')) return Promise.resolve(json(page(definitions)));
      if (url.includes('/source-organisations')) return Promise.resolve(json(page(organisations)));
      return Promise.reject(new Error(`Onverwachte URL in test: ${url}`));
    }) as unknown as typeof fetch;

    render(
      <ActorProvider identity={TEST_IDENTITY}>
        <MemoryRouter initialEntries={['/setup']}>
          <SetupOverviewPage />
        </MemoryRouter>
      </ActorProvider>,
    );

    fireEvent.click((await screen.findAllByRole('button', { name: /Uitklappen/ }))[0]!);
    const definitionRow = await screen.findByText('DEF-10');
    fireEvent.click(within(definitionRow.closest('li')!).getByRole('button', { name: /Uitklappen/ }));
    await screen.findByText('Versie 4');
    await screen.findByTestId('task-7');
    expectNoRawCodes(document.body, 'inrichtingsboom');

    // Het concept: bewerkformulier, verwijderdialoog en de activeerdialoog met de verplichte waarschuwing.
    fireEvent.click(screen.getByTestId('open-revision-100'));
    await screen.findByTestId('revision-edit-form');
    await screen.findByTestId('revision-bookmarks');
    expectNoRawCodes(document.body, 'conceptdetail met bewerkformulier');

    fireEvent.click(screen.getByTestId('delete-mapping-5001'));
    await screen.findByRole('dialog');
    expectNoRawCodes(document.body, 'verwijderdialoog');
    fireEvent.click(screen.getByRole('button', { name: 'Sluiten zonder verwijderen' }));

    fireEvent.click(screen.getByTestId('activate-revision'));
    await screen.findByRole('dialog');
    expect(screen.getByTestId('reopen-rejected-cases-warning').textContent).toContain('afgewezen behandelgevallen');
    expectNoRawCodes(document.body, 'activeerdialoog');
    fireEvent.click(screen.getByRole('button', { name: 'Sluiten zonder activeren' }));

    // Een actieve versie: geen bewerkformulier maar de reden, en de dialoog van "Opvolger maken".
    fireEvent.click(screen.getByTestId('create-successor-99'));
    await screen.findByRole('dialog');
    expectNoRawCodes(document.body, 'dialoog opvolger maken');
    fireEvent.click(screen.getByRole('button', { name: 'Sluiten zonder aanmaken' }));

    fireEvent.click(screen.getByTestId('open-revision-99'));
    await screen.findByTestId('revision-not-editable-reason');
    expectNoRawCodes(document.body, 'detail van een actieve versie');
  });

  it('bewerkformulier: identiteitswijziging, waarschuwing bij de herkenningsversie, blokkade en validatiefout', async () => {
    global.fetch = vi.fn(() =>
      Promise.resolve(json({ error: 'Not allowed', code: 'REVISION_CANONICALISATION_CHANGE_BLOCKED' }, 409)),
    ) as unknown as typeof fetch;
    render(
      <ActorProvider identity={TEST_IDENTITY}>
        <RevisionEditForm revision={setupRevisionDetail(100)} onUpdated={() => {}} />
      </ActorProvider>,
    );
    expectNoRawCodes(document.body, 'bewerkformulier');

    fireEvent.change(screen.getByLabelText(/^Kolom referentie/), { target: { value: 'Andere referentie' } });
    await screen.findByTestId('identity-change-acknowledge');
    expectNoRawCodes(document.body, 'bewerkformulier met identiteitswijziging');

    fireEvent.change(screen.getByLabelText(/^Herkenningsversie/), { target: { value: '3' } });
    await screen.findByTestId('canonicalisation-change-warning');
    expectNoRawCodes(document.body, 'bewerkformulier met waarschuwing bij de herkenningsversie');

    fireEvent.click(screen.getByLabelText(/^Ja, ik weet dat elk bestaand artikel/));
    fireEvent.submit(screen.getByTestId('revision-edit-form'));
    await screen.findByTestId('canonicalisation-blocked');
    expectNoRawCodes(document.body, 'bewerkformulier met geweigerde herkenningsversie');

    fireEvent.change(screen.getByLabelText(/^Drempel nieuwe artikelen/), { target: { value: 'dertig' } });
    fireEvent.submit(screen.getByTestId('revision-edit-form'));
    await screen.findByTestId('revision-edit-validation');
    expectNoRawCodes(document.body, 'bewerkformulier met validatiefout');
  });

  it('sjablonenlijst en de melding dat de functie op deze omgeving niet openstaat', async () => {
    global.fetch = vi.fn(() =>
      Promise.resolve(
        json(page([{ id: 1, code: 'TPL-1', name: 'VROOAM-sjabloon', sourceOrganisationId: 5, sourceOrganisationCode: 'ORG-5' }])),
      ),
    ) as unknown as typeof fetch;
    const first = render(
      <ActorProvider identity={TEST_IDENTITY}>
        <MemoryRouter>
          <TemplateListPage />
        </MemoryRouter>
      </ActorProvider>,
    );
    await screen.findByText('TPL-1');
    expectNoRawCodes(document.body, 'sjablonenlijst');
    first.unmount();

    // 404 zonder code: de melding is gewoon Nederlands; de naam van de serverinstelling staat onder "Technische details".
    global.fetch = vi.fn(() => Promise.resolve(new Response(null, { status: 404 }))) as unknown as typeof fetch;
    render(
      <ActorProvider identity={TEST_IDENTITY}>
        <MemoryRouter>
          <TemplateListPage />
        </MemoryRouter>
      </ActorProvider>,
    );
    await screen.findByText(/niet actief op deze omgeving/);
    expectNoRawCodes(document.body, 'melding functie niet beschikbaar');
    expect(screen.getByText('catalogimport.setup-api.enabled=false')).toBeInTheDocument();
  });

  it('sjabloondetail: invulpunten en problemen, materialiseren (nieuw en hergebruik), meldingen en waarden van de koppeling', async () => {
    const bookmark = (overrides: Record<string, unknown>) => ({
      id: 1,
      revisionId: 100,
      name: 'BESTANDS_PREFIX',
      label: 'Bestandsprefix',
      description: 'Komt voor elke bestandsnaam',
      dataType: 'TEXT',
      valueScope: 'DEFINITION',
      ownerRole: 'admin',
      required: true,
      defaultValue: null,
      allowedValues: null,
      validationPattern: null,
      sortOrder: 1,
      usages: [{ id: 1, placeKind: 'FIELD_MAPPING_FIXED_VALUE', targetHint: 'EAN' }],
      ...overrides,
    });
    const bookmarks = [
      bookmark({}),
      bookmark({
        id: 2,
        name: 'LEVERANCIER',
        label: 'Leverancier van het bestand',
        description: null,
        dataType: 'SUPPLIER_REFERENCE',
        valueScope: 'LINK',
        required: false,
        usages: [{ id: 2, placeKind: 'LINK_SUPPLIER_ORGANISATION', targetHint: '' }],
      }),
      bookmark({
        id: 3,
        name: 'SOORT_LEVERING',
        label: 'Soort levering',
        description: null,
        dataType: 'ENUM',
        valueScope: 'LINK',
        required: false,
        defaultValue: 'A',
        allowedValues: 'A,B',
        usages: [{ id: 3, placeKind: 'RECORD_FILTER_COMPARE_VALUE', targetHint: '1' }],
      }),
      bookmark({
        id: 4,
        name: 'EEN_NIEUW_SOORT',
        label: 'Invulpunt van een latere soort',
        description: null,
        dataType: 'EEN_NIEUW_TYPE',
        valueScope: 'EEN_NIEUW_BEREIK',
        required: false,
        usages: [],
      }),
    ];
    const problems = [
      { code: 'CONFIG_BOOKMARK_WITHOUT_PLACE', bookmarkName: 'SOORT_LEVERING', message: 'Bookmark has no usage' },
      { code: 'CONFIG_BOOKMARK_PLACE_UNRESOLVED', bookmarkName: 'BESTANDS_PREFIX', message: 'Place unresolved' },
      { code: 'EEN_NIEUW_PROBLEEM', bookmarkName: 'ONBEKEND', message: 'Something new' },
    ];
    const base = {
      definitionCode: 'DEF-20',
      definitionName: 'Leverancier X',
      definitionRevisionId: 200,
      definitionRevisionNumber: 1,
      definitionRevisionStatus: 'ACTIVE',
      templateRevisionId: 100,
      templateRevisionNumber: 1,
      templateRevisionStatus: 'ACTIVE',
      importLinkCount: 2,
      shareable: true,
      blockingBookmarkName: null,
    };
    const materialisations = [
      { ...base, definitionId: 20 },
      { ...base, definitionId: 21, definitionCode: 'DEF-21', shareable: false, blockingBookmarkName: 'SOORT_LEVERING' },
      {
        ...base,
        definitionId: 22,
        definitionCode: 'DEF-22',
        definitionRevisionId: null,
        definitionRevisionNumber: null,
        definitionRevisionStatus: null,
        templateRevisionId: null,
        templateRevisionNumber: null,
        templateRevisionStatus: null,
        shareable: false,
      },
    ];
    const result = {
      templateDefinitionId: 1,
      templateRevisionId: 100,
      templateRevisionNumber: 1,
      templateRevisionStatus: 'SUPERSEDED',
      definitionId: 30,
      definitionCode: 'DEF-30',
      definitionCreated: true,
      definitionRevisionId: 300,
      definitionRevisionNumber: 1,
      definitionRevisionStatus: 'DRAFT',
      importLinkId: 77,
      importLinkCode: 'LNK-77',
      definitionValues: [],
      linkValues: [],
      warnings: [
        { code: 'LINK_SEARCH_SUPPLIER_NOT_DERIVED', bookmarkName: null, message: 'never derived' },
        { code: 'OPTIONAL_BOOKMARK_NOT_FILLED', bookmarkName: 'SOORT_LEVERING', message: 'not filled' },
        { code: 'EEN_NIEUWE_MELDING', bookmarkName: null, message: 'something new' },
      ],
    };
    const linkValues = {
      importLinkId: 77,
      importLinkCode: 'LNK-77',
      activeRevisionId: null,
      lockedByOpenBatch: true,
      values: [
        {
          bookmarkName: 'BESTANDS_PREFIX',
          label: 'Bestandsprefix',
          dataType: 'TEXT',
          valueText: null,
          previousValueText: 'VRO_',
          declared: true,
          required: true,
          filled: false,
          filledAt: null,
          filledBy: null,
          updatedAt: '2026-09-28T08:00:00Z',
          updatedBy: 'An Beslisser',
        },
        {
          bookmarkName: 'OUDE_BOOKMARK',
          label: null,
          dataType: null,
          valueText: 'x',
          previousValueText: null,
          declared: false,
          required: false,
          filled: true,
          filledAt: '2026-09-01T08:00:00Z',
          filledBy: 'An Beslisser',
          updatedAt: null,
          updatedBy: null,
        },
      ],
      missingRequiredNames: ['BESTANDS_PREFIX'],
    };
    global.fetch = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = input.toString();
      const method = (init?.method ?? 'GET').toUpperCase();
      if (url.includes('/templates/1/revisions/100/bookmarks')) {
        return Promise.resolve(json({ definitionId: 1, revisionId: 100, bookmarks, problems }));
      }
      if (url.includes('/templates/1/materialisations')) {
        return Promise.resolve(method === 'POST' ? json(result, 201) : json(page(materialisations)));
      }
      if (url.includes('/definitions/1/revisions')) {
        return Promise.resolve(
          json(
            page([
              { id: 100, definitionId: 1, revisionNumber: 1, status: 'ACTIVE' },
              { id: 101, definitionId: 1, revisionNumber: 2, status: 'DRAFT' },
              { id: 99, definitionId: 1, revisionNumber: 3, status: 'SUPERSEDED' },
            ]),
          ),
        );
      }
      if (url.includes('/import-links')) {
        return Promise.resolve(
          json(
            page([
              {
                id: 77,
                code: 'LNK-77',
                name: 'Koppeling 77',
                supplierCode: 'ORG-9',
                supplierName: 'Leverancier',
                libraryCode: 'BIB1',
                active: true,
                importDefinitionId: 30,
              },
            ]),
          ),
        );
      }
      if (url.includes('/links/77/bookmark-values')) return Promise.resolve(json(linkValues));
      if (/\/templates(\?|$)/.test(url)) {
        return Promise.resolve(
          json(page([{ id: 1, code: 'TPL-1', name: 'Sjabloon', sourceOrganisationId: 5, sourceOrganisationCode: 'ORG-5' }])),
        );
      }
      return Promise.reject(new Error(`Onverwachte URL in test: ${url}`));
    }) as unknown as typeof fetch;

    render(
      <ActorProvider identity={TEST_IDENTITY}>
        <MemoryRouter initialEntries={['/templates/1']}>
          <Routes>
            <Route path="/templates/:definitionId" element={<TemplateDetailPage />} />
          </Routes>
        </MemoryRouter>
      </ActorProvider>,
    );

    await screen.findByText('DEF-21');
    expectNoRawCodes(document.body, 'sjabloondetail: versies en historiek');

    fireEvent.click(screen.getByRole('button', { name: 'Versie 1' }));
    await screen.findByTestId('materialise-form');
    await screen.findByTestId('bookmark-problems');
    expectNoRawCodes(document.body, 'sjabloondetail: invulpunten en problemen');

    fireEvent.change(screen.getByLabelText(/^Nieuw of hergebruik/), { target: { value: 'REUSE_DEFINITION' } });
    expectNoRawCodes(document.body, 'materialiseren: hergebruik');

    fireEvent.change(screen.getByLabelText(/^Nieuw of hergebruik/), { target: { value: 'NEW_DEFINITION' } });
    expectNoRawCodes(document.body, 'materialiseren: nieuw');
    fireEvent.change(screen.getByLabelText(/^Code van de beschrijving/), { target: { value: 'DEF-30' } });
    fireEvent.change(screen.getByLabelText(/^Naam van de beschrijving/), { target: { value: 'Leverancier Z' } });
    fireEvent.change(screen.getByLabelText(/^Koppelingscode/), { target: { value: 'LNK-77' } });
    fireEvent.change(screen.getByLabelText(/^Koppelingsnaam/), { target: { value: 'Koppeling 77' } });
    fireEvent.change(screen.getByLabelText(/^Bibliotheekcode/), { target: { value: 'BIB1' } });
    fireEvent.change(screen.getByLabelText(/^Bestandsprefix/), { target: { value: 'VRO_' } });
    fireEvent.click(screen.getByRole('button', { name: 'Materialiseren' }));

    await screen.findByTestId('materialise-result');
    await screen.findByTestId('materialise-warnings');
    await screen.findByTestId('link-missing-required');
    await screen.findByTestId('definition-links');
    expectNoRawCodes(document.body, 'sjabloondetail na het materialiseren (meldingen, koppeling, waarden)');
    // De meldingen staan in gewoon Nederlands, een onbekende melding krijgt een neutraal woord.
    expect(screen.getByTestId('materialise-warnings').textContent).toContain(
      'Leverancierscode in de bibliotheek is niet ingevuld',
    );
    expect(screen.getByTestId('materialise-warnings').textContent).toContain('Een melding van de server');
  });
});
