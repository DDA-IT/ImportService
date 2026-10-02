/**
 * NT-10 — scherm "Controleren" (`/setup/links/:linkId/check`): checklist (NT-8), proefinlezing (NT-9, contract
 * `docs/design/proefinlezing-design.md`), activeren met de V6-waarschuwing en "Wat nu?".
 *
 * Dekt per AGENT.md §2 principe 10:
 * - **normaal**: checklist volledig in orde; proef zonder blokkade met tellers, voorbeeldregels, problemen, grenzen
 *   en "niet gecontroleerd"; activeren na een geslaagde proef zonder extra waarschuwing.
 * - **ontbrekende data**: tellers `null` tonen "—" (nooit 0); geen bestand gekozen = geen verzoek; koppeling niet
 *   gevonden; versie uit het adres niet gevonden.
 * - **ongeldige input / geweigerd**: 400 `FILE_REQUIRED` in het Nederlands; zonder Beheren alles uitgeschakeld mét reden.
 * - **dubbele input**: twee keer klikken tijdens een lopende proef = één verzoek.
 * - **grensgeval**: V6 — activeren zonder (geslaagde) proef mag, maar met een duidelijke waarschuwing; na activeren
 *   wordt de checklist opnieuw gelezen.
 * - **V7**: geen ruwe code in de zichtbare tekst buiten "Technische details (voor support)".
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ActorProvider, type ActorIdentity } from '../actor/ActorContext';
import { PERMISSION_READ } from '../api/types';
import type { LinkReadiness, RevisionRow, TrialReadResult } from '../api/types';
import { LinkCheckPage } from '../features/setup/check/LinkCheckPage';
import { TEST_IDENTITY, testIdentityWith } from './testIdentity';
import { expectNoOldWording } from './oldWording';

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function page<T>(content: T[]) {
  return { content, page: 0, size: 200, totalElements: content.length, totalPages: 1 };
}

// --- Vaste testgegevens (veldnamen zonder underscore: de V7-controle zoekt naar ruwe codes) -------------------

const LINK = {
  id: 501,
  code: 'LNK-1',
  name: 'Koppeling Een',
  supplierCode: 'SUP1',
  supplierName: 'Leverancier Een',
  libraryCode: 'LIB1',
  active: true,
  importDefinitionId: 10,
};

const DEFINITION = {
  id: 10,
  code: 'DEF-10',
  name: 'Prijslijst',
  usageType: 'OWN_DEFINITION',
  sourceOrganisationId: 1,
  sourceOrganisationCode: 'ORG-1',
  activeRevisionId: null,
};

const DRAFT: RevisionRow = { id: 100, definitionId: 10, revisionNumber: 1, status: 'DRAFT' };

const READINESS_NOT_READY: LinkReadiness = {
  linkId: 501,
  ready: false,
  checks: [
    { code: 'READY_LINK_ACTIVE', status: 'OK', subject: { type: 'LINK', id: 501 }, detail: null },
    {
      code: 'NO_ACTIVE_REVISION',
      status: 'PROBLEM',
      subject: { type: 'DEFINITION', id: 10 },
      detail: 'Import definition 10 has no active revision',
    },
    {
      code: 'CONFIG_FIELD_REFERENCE_INVALID',
      status: 'PROBLEM',
      subject: { type: 'REVISION', id: 100 },
      detail: 'Field reference Prijs must be a 1-based column index',
    },
    {
      code: 'CONFIG_REQUIRED_BOOKMARK_MISSING',
      status: 'PROBLEM',
      subject: { type: 'LINK', id: 501 },
      detail: 'Required bookmark Levcode has no value on import link 501',
    },
    {
      code: 'LINK_HAS_NO_TASK',
      status: 'PROBLEM',
      subject: { type: 'LINK', id: 501 },
      detail: 'Import link 501 has no task to receive a delivery on',
    },
    {
      code: 'INFO_LIBRARY_NOT_VERIFIED',
      status: 'INFO',
      subject: { type: 'LINK', id: 501 },
      detail: "Library code 'LIB1' is not checked against Prodis",
    },
  ],
};

const READINESS_READY: LinkReadiness = {
  linkId: 501,
  ready: true,
  checks: [
    { code: 'READY_LINK_ACTIVE', status: 'OK', subject: { type: 'LINK', id: 501 }, detail: null },
    { code: 'READY_ACTIVE_REVISION', status: 'OK', subject: { type: 'REVISION', id: 100 }, detail: null },
    { code: 'READY_PRICE_FIELD', status: 'OK', subject: { type: 'REVISION', id: 100 }, detail: null },
    { code: 'READY_LINK_BOOKMARKS', status: 'OK', subject: { type: 'LINK', id: 501 }, detail: null },
    { code: 'READY_TASK_ACCEPTS_UPLOAD', status: 'OK', subject: { type: 'TASK', id: 900 }, detail: null },
    {
      code: 'INFO_LIBRARY_NOT_VERIFIED',
      status: 'INFO',
      subject: { type: 'LINK', id: 501 },
      detail: "Library code 'LIB1' is not checked against Prodis",
    },
  ],
};

const NULL_COUNTERS = {
  rawRecordCount: null,
  validRecordCount: null,
  rejectedRecordCount: null,
  filteredOutCount: null,
  errorBeforeFilterCount: null,
  criticalLineCount: null,
  duplicateIdentityCount: null,
  scopeRecordCount: null,
  physicalLineCount: null,
  prefixLineCount: null,
  skippedBlankLineCount: null,
  columnCount: null,
  linesWithReplacementCharacter: null,
  issueOccurrencesBySeverity: { CRITICAL: 0, ERROR: 0, WARNING: 0, INFO: 0 },
};

const TRIAL_BLOCKED: TrialReadResult = {
  revisionId: 100,
  revisionNumber: 1,
  revisionStatus: 'DRAFT',
  linkId: 501,
  file: { byteSize: 120, sha256: 'ab'.repeat(32), fileName: 'prijzen.csv' },
  currencyDefault: { value: 'EUR', origin: 'SYSTEM_DEFAULT' },
  verdict: {
    result: 'WOULD_BLOCK',
    blockedCode: 'HEADER_FIELD_MISSING:Prijs',
    blockedReason: "Declared field 'Prijs' is missing from the header on line 1",
    fieldName: 'Prijs',
    sourceValue: null,
    expectedValue: 'Prijs',
    stage: 'READING',
  },
  counters: NULL_COUNTERS,
  header: {
    referenceKind: 'HEADER_NAME',
    hasHeader: true,
    headerLineNumber: 1,
    expectedColumnCount: null,
    foundColumnCount: 3,
    foundColumns: ['Leverancier', 'Groep', 'Artikel'],
    expectedColumns: [
      { reference: 'Leverancier', role: 'IDENTITY', required: true, foundAtPosition: 1 },
      { reference: 'Prijs', role: 'PRICE', required: true, foundAtPosition: null },
    ],
    missingRequired: ['Prijs'],
    missingOptionalFilterColumns: [],
    extraColumns: [],
    shifted: [],
  },
  sampleRows: [],
  sampleRowsTruncated: false,
  issueGroups: [],
  thresholds: {
    scopeRecordCount: null,
    bulkIncidentSharePercent: '1',
    critical: {
      count: null,
      identityIncidentsEvaluated: false,
      thresholdPercent: '1',
      sharePercent: null,
      outcome: 'UNDETERMINED',
      countIsLowerBound: true,
    },
    rejected: { count: null, thresholdPercent: null, sharePercent: null, outcome: 'NOT_APPLICABLE' },
  },
  configProblems: [],
  notEvaluated: [{ check: 'CREATION_POLICY', status: 'INFO', reason: 'NO_SOURCE_STATE' }],
};

const TRIAL_OK: TrialReadResult = {
  ...TRIAL_BLOCKED,
  verdict: {
    result: 'NO_BLOCKER_FOUND',
    blockedCode: null,
    blockedReason: null,
    fieldName: null,
    sourceValue: null,
    expectedValue: null,
    stage: null,
  },
  currencyDefault: { value: 'USD', origin: 'LINK_DEFAULT' },
  counters: {
    rawRecordCount: 3,
    validRecordCount: 2,
    rejectedRecordCount: 1,
    filteredOutCount: 0,
    errorBeforeFilterCount: 0,
    criticalLineCount: 0,
    duplicateIdentityCount: null,
    scopeRecordCount: 3,
    physicalLineCount: 4,
    prefixLineCount: 0,
    skippedBlankLineCount: 0,
    columnCount: 4,
    linesWithReplacementCharacter: 2,
    issueOccurrencesBySeverity: { CRITICAL: 0, ERROR: 1, WARNING: 0, INFO: 0 },
  },
  header: {
    ...TRIAL_BLOCKED.header!,
    foundColumnCount: 4,
    foundColumns: ['Leverancier', 'Groep', 'Artikel', 'Prijs'],
    expectedColumns: [
      { reference: 'Leverancier', role: 'IDENTITY', required: true, foundAtPosition: 1 },
      { reference: 'Prijs', role: 'PRICE', required: true, foundAtPosition: 4 },
    ],
    missingRequired: [],
  },
  sampleRows: [
    {
      lineNumber: 2,
      status: 'VALID',
      rawValues: ['SUP1', 'GRP', 'A100', '12,50'],
      sourceValue: null,
      interpreted: {
        supplier: 'SUP1',
        supplierGroup: 'GRP',
        supplierReference: 'A100',
        discountCode: null,
        discountState: 'NOT_USED',
        identityHash: 'ff',
        basePriceRaw: '12,50',
        basePrice: '12.500000',
        currency: 'USD',
        currencyOrigin: 'LINK_DEFAULT',
        description: 'Schroef',
        mappedFields: {},
        priceComponents: [],
        references: [],
      },
      filter: { kind: 'IN_SCOPE', decidingSequenceNumber: null },
      issues: [],
    },
    {
      lineNumber: 3,
      status: 'REJECTED',
      rawValues: ['SUP1', 'GRP', 'A101', 'twaalf'],
      sourceValue: null,
      interpreted: null,
      filter: { kind: 'REJECTED', decidingSequenceNumber: null },
      issues: [
        { code: 'PRICE_UNREADABLE', severity: 'ERROR', fieldName: 'Prijs', sourceValue: 'twaalf', message: 'Price is not a number' },
      ],
    },
  ],
  issueGroups: [
    {
      code: 'PRICE_UNREADABLE',
      fieldName: 'Prijs',
      severity: 'ERROR',
      domain: 'PRICE',
      controlLevel: 'RECORD',
      deliveryEffect: 'NONE',
      occurrenceCount: 1,
      grouped: false,
      bulkIncident: false,
      sharePercent: null,
      examples: [{ lineNumber: 3, fieldName: 'Prijs', sourceValue: 'twaalf', message: 'Price is not a number' }],
      examplesTruncated: false,
    },
  ],
  thresholds: {
    scopeRecordCount: 3,
    bulkIncidentSharePercent: '1',
    critical: {
      count: 0,
      identityIncidentsEvaluated: false,
      thresholdPercent: '1',
      sharePercent: '0',
      outcome: 'WITHIN',
      countIsLowerBound: true,
    },
    rejected: { count: 1, thresholdPercent: null, sharePercent: '33.33', outcome: 'NOT_APPLICABLE' },
  },
  notEvaluated: [
    { check: 'CREATION_POLICY', status: 'INFO', reason: 'NO_SOURCE_STATE' },
    { check: 'PRICE_DEVIATION', status: 'INFO', reason: 'NO_PRICE_HISTORY' },
  ],
};

// --- Nep-server --------------------------------------------------------------------------------------------------

type ServerState = {
  links: unknown[];
  revisions: RevisionRow[];
  readiness: LinkReadiness;
  /** Na het activeren: wat de checklist en de versielijst daarna teruggeven. */
  afterActivation: { readiness: LinkReadiness; revisions: RevisionRow[] };
  trial: () => Promise<Response>;
};

let state: ServerState;

function freshState(overrides: Partial<ServerState> = {}): ServerState {
  return {
    links: [LINK],
    revisions: [DRAFT],
    readiness: READINESS_NOT_READY,
    afterActivation: {
      readiness: READINESS_READY,
      revisions: [{ ...DRAFT, status: 'ACTIVE' }],
    },
    trial: () => Promise.resolve(json(TRIAL_OK)),
    ...overrides,
  };
}

function installServer() {
  global.fetch = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input.toString();
    const method = (init?.method ?? 'GET').toUpperCase();
    if (url.includes('/import-links/501/readiness')) {
      return Promise.resolve(json(state.readiness));
    }
    if (url.includes('/revisions/100/trial-reads') && method === 'POST') {
      return state.trial();
    }
    if (url.includes('/setup/revisions/100/activate') && method === 'POST') {
      state.readiness = state.afterActivation.readiness;
      state.revisions = state.afterActivation.revisions;
      return Promise.resolve(
        json({ id: 100, definitionId: 10, revisionNumber: 1, status: 'ACTIVE' }),
      );
    }
    if (url.includes('/definitions/10/revisions')) {
      return Promise.resolve(json(page(state.revisions)));
    }
    if (url.includes('/definitions')) {
      return Promise.resolve(json(page([DEFINITION])));
    }
    if (url.includes('/import-links')) {
      return Promise.resolve(json(page(state.links)));
    }
    return Promise.reject(new Error(`Onverwachte URL in test: ${method} ${url}`));
  }) as unknown as typeof fetch;
}

function calls(part: string, method = 'GET'): Array<[string, RequestInit | undefined]> {
  return vi
    .mocked(global.fetch)
    .mock.calls.filter(
      (call) => String(call[0]).includes(part) && ((call[1] as RequestInit | undefined)?.method ?? 'GET') === method,
    )
    .map((call) => [String(call[0]), call[1] as RequestInit | undefined]);
}

function renderPage(path = '/setup/links/501/check?definitionId=10', identity: ActorIdentity = TEST_IDENTITY) {
  return render(
    <ActorProvider identity={identity}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/setup/links/:linkId/check" element={<LinkCheckPage />} />
        </Routes>
      </MemoryRouter>
    </ActorProvider>,
  );
}

/** De tekst die een gebruiker ziet, zonder de ingeklapte "Technische details (voor support)". */
function visibleText(root: HTMLElement): string {
  const clone = root.cloneNode(true) as HTMLElement;
  clone.querySelectorAll('details').forEach((element) => {
    if ((element.querySelector('summary')?.textContent ?? '').startsWith('Technische details')) {
      element.remove();
    }
  });
  return clone.textContent ?? '';
}

const RAW_CODE = /\b[A-Z][A-Z0-9]*_[A-Z0-9_]+\b/;

function csv(name = 'prijzen.csv'): File {
  return new File(['Leverancier;Groep;Artikel;Prijs\nSUP1;GRP;A100;12,50\n'], name, { type: 'text/csv' });
}

function chooseFile(file: File = csv()) {
  fireEvent.change(screen.getByLabelText(/^Bestand \(CSV\)/), { target: { files: [file] } });
}

async function runTrial() {
  chooseFile();
  fireEvent.click(screen.getByTestId('trial-start'));
  return screen.findByTestId('trial-result');
}

describe('NT-10 — scherm "Controleren"', () => {
  const originalFetch = global.fetch;

  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    vi.clearAllMocks();
    global.fetch = originalFetch;
  });

  // --- Blok 1: checklist -------------------------------------------------------------------------------------

  it('NT-10.1: checklist volledig in orde — "Klaar voor leveringen", Nederlandse zinnen, code enkel bij support', async () => {
    state = freshState({ readiness: READINESS_READY, revisions: [{ ...DRAFT, status: 'ACTIVE' }] });
    installServer();
    const { container } = renderPage();

    expect(await screen.findByTestId('readiness-summary')).toHaveTextContent('Klaar voor leveringen');
    const list = screen.getByTestId('readiness-checks');
    expect(list).toHaveTextContent('De koppeling staat aan');
    expect(list).toHaveTextContent('Er is een versie in gebruik');
    expect(list).toHaveTextContent('De taak neemt opgeladen bestanden aan');
    expect(within(list).getAllByText('In orde')).toHaveLength(5);
    expect(within(list).getByText('Ter info')).toBeInTheDocument();
    // De INFO-regel zegt wat de gebruiker zelf moet nakijken; een OK-regel heeft geen "Wat moet ik doen?".
    expect(list).toHaveTextContent('Kijk zelf na of de bibliotheekcode in Prodis bestaat.');
    expect(within(list).getAllByText('Wat moet ik doen?')).toHaveLength(1);
    // De drie beperkingen van NT-8, in gewoon Nederlands.
    const caveats = screen.getByTestId('readiness-caveats');
    // NT-14-4: de oude "één fout per keer"-beperking bestaat niet meer.
    expect(caveats).not.toHaveTextContent('één per keer');
    expect(caveats).not.toHaveTextContent('verder gecontroleerd');
    expect(caveats).toHaveTextContent('pas invullen nadat de versie in gebruik genomen is');
    expect(caveats).toHaveTextContent('niet tegen Prodis gecontroleerd');
    // Een actieve versie: "Wat nu?" wijst naar Levering uploaden; er valt niets te activeren.
    expect(await screen.findByTestId('what-now-upload')).toHaveAttribute('href', '/upload');
    expect(screen.getByTestId('activate-already-active')).toBeInTheDocument();
    expect(screen.queryByTestId('activate-revision')).not.toBeInTheDocument();

    // V7: de code staat enkel onder "Technische details (voor support)".
    expect(visibleText(container)).not.toMatch(RAW_CODE);
    expectNoOldWording(container, 'scherm Controleren: checklist in orde');
    expect(container.textContent).toContain('READY_LINK_ACTIVE');
  });

  it('NT-10.2: checklist met problemen — telling, uitleg en een link naar de plek waar het opgelost wordt', async () => {
    state = freshState();
    installServer();
    const { container } = renderPage();

    expect(await screen.findByTestId('readiness-summary')).toHaveTextContent('Nog niet klaar: 4 punten');
    const list = screen.getByTestId('readiness-checks');
    expect(list).toHaveTextContent('Er is nog geen versie in gebruik');
    expect(list).toHaveTextContent('Ongeldige kolomaanduiding');
    expect(list).toHaveTextContent('Verplichte invulpunten zijn niet ingevuld');
    expect(list).toHaveTextContent('Er is nog geen taak');
    expect(within(list).getAllByText('Probleem')).toHaveLength(4);

    const hrefs = within(list)
      .getAllByTestId('readiness-fix-link')
      .map((link) => link.getAttribute('href'));
    // Geen versie in gebruik → naar het activeren op deze pagina.
    expect(hrefs).toContain('#activeren');
    // Fout in het concept → het concept openen bij Inrichting.
    expect(hrefs).toContain('/setup?definitionId=10&revisionId=100');
    // Geen taak → het stappenplan bij de taakstap van deze koppeling.
    expect(hrefs).toContain('/setup/new?organisationId=1&definitionId=10&linkId=501');
    // De invulpunten van de koppeling kunnen pas na het activeren (NT-8-beperking), dat staat er letterlijk.
    expect(list).toHaveTextContent('Deze waarden kunt u pas invullen nadat de versie in gebruik genomen is.');
    expect(list).toHaveTextContent('Pas conceptversie 1 aan bij Inrichting.');
    // Zonder actieve versie nog geen invulformulier voor de koppeling, en nog geen upload.
    expect(screen.queryByTestId('link-bookmark-values')).not.toBeInTheDocument();
    expect(screen.queryByTestId('what-now-upload')).not.toBeInTheDocument();

    expect(visibleText(container)).not.toMatch(RAW_CODE);
    expectNoOldWording(container, 'scherm Controleren: checklist met problemen');
    // De Engelse servertekst staat bij support, niet als hoofdtekst.
    expect(visibleText(container)).not.toContain('has no task');
    expect(container.textContent).toContain('Import link 501 has no task to receive a delivery on');
  });

  it('NT-10.3: "Opnieuw controleren" leest de checklist opnieuw', async () => {
    state = freshState();
    installServer();
    renderPage();
    await screen.findByTestId('readiness-summary');
    expect(calls('/import-links/501/readiness')).toHaveLength(1);

    state.readiness = READINESS_READY;
    fireEvent.click(screen.getByTestId('readiness-reload'));

    await waitFor(() => expect(screen.getByTestId('readiness-summary')).toHaveTextContent('Klaar voor leveringen'));
    expect(calls('/import-links/501/readiness')).toHaveLength(2);
  });

  it('NT-10.4: een onbekende koppeling wordt gemeld, niet stil vervangen', async () => {
    state = freshState({ links: [] });
    installServer();
    renderPage();
    expect(await screen.findByTestId('check-link-not-found')).toHaveTextContent('bestaat niet (meer)');
    expect(screen.queryByTestId('trial-section')).not.toBeInTheDocument();
  });

  it('NT-10.5: een versie uit het adres die niet bij de koppeling hoort, wordt gemeld', async () => {
    state = freshState();
    installServer();
    renderPage('/setup/links/501/check?definitionId=10&revisionId=777');
    expect(await screen.findByTestId('check-revision-not-found')).toHaveTextContent(
      'De versie uit het adres hoort niet bij deze koppeling',
    );
    // Er wordt verder gecontroleerd met de conceptversie, en dat staat er ook.
    expect(screen.getByTestId('check-revision-not-found')).toHaveTextContent('versie 1');
  });

  // --- Blok 2: proefinlezing ---------------------------------------------------------------------------------

  it('NT-10.6: proef die zou blokkeren — reden in het Nederlands, tellers "—", ontbrekende kolom, niets opgeslagen', async () => {
    state = freshState({ trial: () => Promise.resolve(json(TRIAL_BLOCKED)) });
    installServer();
    const { container } = renderPage();
    await screen.findByTestId('trial-section');
    expect(screen.getByTestId('trial-nothing-stored')).toHaveTextContent('Er wordt niets opgeslagen of gepubliceerd.');

    const result = await runTrial();

    const verdict = within(result).getByTestId('trial-verdict');
    expect(verdict).toHaveTextContent('Deze levering zou tegengehouden worden omdat: Verplichte kolom ontbreekt');
    expect(verdict).toHaveTextContent('Kolom: Prijs');
    expect(verdict).toHaveTextContent('Lezen van het bestand');
    // Niet vastgesteld = "—" met tooltip, nooit 0.
    const counters = within(result).getByTestId('trial-counters');
    const raw = within(counters).getByText('Gelezen regels').closest('div')!;
    expect(raw).toHaveTextContent('—');
    expect(within(raw).getByTitle('niet vastgesteld')).toBeInTheDocument();
    expect(raw).not.toHaveTextContent('0');
    expect(within(result).getByTestId('trial-missing-columns')).toHaveTextContent('Prijs');
    expect(result).toHaveTextContent('Er is niets opgeslagen of gepubliceerd');

    // Het verzoek: multipart met het bestand en de koppeling, naar de gekozen conceptversie.
    const [[url, init]] = calls('/revisions/100/trial-reads', 'POST') as [[string, RequestInit | undefined]];
    expect(url).toContain('/revisions/100/trial-reads');
    const body = init!.body as FormData;
    expect((body.get('file') as File).name).toBe('prijzen.csv');
    expect(body.get('linkId')).toBe('501');

    expect(visibleText(container)).not.toMatch(RAW_CODE);
    expectNoOldWording(container, 'scherm Controleren: proef die zou blokkeren');
    expect(container.textContent).toContain('HEADER_FIELD_MISSING:Prijs');
  });

  it('NT-10.7: proef zonder blokkade — tellers, voorbeeldregels, problemen, grenzen en "niet gecontroleerd"', async () => {
    state = freshState();
    installServer();
    const { container } = renderPage();
    await screen.findByTestId('trial-section');

    const result = await runTrial();

    expect(within(result).getByTestId('trial-verdict')).toHaveTextContent('Deze levering zou aanvaard worden');
    const counters = within(result).getByTestId('trial-counters');
    expect(within(counters).getByText('Gelezen regels').closest('div')).toHaveTextContent('3');
    expect(within(counters).getByText('Verworpen').closest('div')).toHaveTextContent('1');
    // Dubbele artikelen niet vastgesteld: "—", nooit 0.
    const duplicates = within(counters).getByText('Dubbele artikelen').closest('div')!;
    expect(duplicates).toHaveTextContent('—');
    expect(within(duplicates).getByTitle('niet vastgesteld')).toBeInTheDocument();

    // Voorbeeldregels: prijs zoals in het bestand → zoals begrepen, valuta met herkomst in het Nederlands.
    const valid = within(result).getByTestId('trial-sample-2');
    expect(valid).toHaveTextContent('12,50 → 12.500000');
    // (De uitleg van het woord staat als verborgen tekst meteen na het woord, voor schermlezers.)
    expect(valid).toHaveTextContent('USD (Standaard van de koppeling');
    expect(valid).toHaveTextContent('SUP1 / GRP / A100');
    expect(within(valid).getByText('Geldig')).toBeInTheDocument();
    const rejected = within(result).getByTestId('trial-sample-3');
    expect(within(rejected).getByText('Verworpen')).toBeInTheDocument();
    expect(rejected).toHaveTextContent('Prijs onleesbaar (kolom Prijs): «twaalf»');

    expect(within(result).getByTestId('trial-issue-groups')).toHaveTextContent('Prijs onleesbaar');
    const thresholds = within(result).getByTestId('trial-thresholds');
    expect(thresholds).toHaveTextContent('Regels ter beoordeling: minstens 0');
    expect(thresholds).toHaveTextContent('kan hoger zijn in een echte levering');
    expect(thresholds).toHaveTextContent('Binnen de grens');
    expect(thresholds).toHaveTextContent('grens niet ingesteld');
    const notEvaluated = within(result).getByTestId('trial-not-evaluated');
    expect(notEvaluated).toHaveTextContent('Te veel nieuwe artikelen');
    expect(notEvaluated).toHaveTextContent('want dan kennen we de bestaande artikelen');
    expect(notEvaluated).toHaveTextContent('Prijsafwijking');
    expect(within(result).getByTestId('trial-charset-hint')).toHaveTextContent(
      '2 regels bevatten tekens die niet gelezen konden worden — kies mogelijk een andere Tekenset',
    );

    expect(visibleText(container)).not.toMatch(RAW_CODE);
  });

  it('NT-14-4: checklist toont alle configuratiefouten tegelijk, met veld, en een melding over overgeslagen controles', async () => {
    const revision = { type: 'REVISION' as const, id: 100 };
    state = freshState({
      readiness: {
        linkId: 501,
        ready: false,
        checks: [
          {
            code: 'CONFIG_FIELD_REFERENCE_INVALID',
            status: 'PROBLEM',
            subject: revision,
            detail: 'Field reference EAN must be a 1-based column index',
            fieldName: 'EAN',
            revisionField: 'supplierField',
          },
          {
            code: 'CONFIG_FILTER_INVALID',
            status: 'PROBLEM',
            subject: revision,
            detail: 'Filter on Merkkolom is invalid',
            fieldName: 'Merkkolom',
            revisionField: null,
          },
          {
            code: 'CONFIG_PRICE_FIELD_MISSING',
            status: 'PROBLEM',
            subject: revision,
            detail: 'No price field',
            fieldName: null,
            revisionField: 'delimiter',
          },
          {
            code: 'INFO_CONFIG_CHECKS_SKIPPED',
            status: 'INFO',
            subject: revision,
            detail: 'Checks depending on [CONFIG_FIELD_REFERENCE_KIND_INVALID] were not evaluated',
            skippedBecause: ['CONFIG_FIELD_REFERENCE_KIND_INVALID'],
          },
        ],
      },
    });
    installServer();
    const { container } = renderPage();

    expect(await screen.findByTestId('readiness-summary')).toHaveTextContent('Nog niet klaar: 3 punten');
    const list = screen.getByTestId('readiness-checks');
    expect(within(list).getAllByText('Probleem')).toHaveLength(3);
    expect(list).toHaveTextContent('Ongeldige kolomaanduiding');
    expect(list).toHaveTextContent('Ongeldig filter');
    expect(list).toHaveTextContent('De prijskolom ontbreekt');
    // Het veld waarover het gaat: veld van de beschrijving, doelveld (met Nederlands woord) of kolom zoals ze is.
    const fields = within(list).getAllByTestId('readiness-check-field').map((element) => element.textContent);
    expect(fields).toEqual([
      'Veld: Kolom leverancier — Doelveld: EAN-barcode',
      'Kolom: Merkkolom',
      'Veld: Scheidingsteken',
    ]);
    // De INFO-regel telt niet mee, zegt het in gewoon Nederlands en noemt de overgeslagen controles bij naam.
    expect(list).toHaveTextContent(
      'Sommige controles konden nog niet uitgevoerd worden omdat ze afhangen van een fout hierboven; na herstel kunnen er nog fouten bijkomen.',
    );
    const skippedSection = within(list).getByTestId('config-skipped-causes');
    expect(skippedSection).toHaveTextContent('Ongeldige manier om kolommen te herkennen');
    // NT-14-5: de lijst wordt gegenereerd uit het gestructureerde skippedBecause-veld; verifieer li-elementen
    const skippedItems = within(skippedSection).getAllByRole('listitem');
    expect(skippedItems).toHaveLength(1);
    expect(skippedItems[0]).toHaveTextContent('Ongeldige manier om kolommen te herkennen');
    expect(screen.getByTestId('readiness-caveats')).not.toHaveTextContent('één per keer');

    expect(visibleText(container)).not.toMatch(RAW_CODE);
    expectNoOldWording(container, 'scherm Controleren: alle configuratiefouten');
    // De ruwe codes staan enkel bij support.
    expect(container.textContent).toContain('CONFIG_FIELD_REFERENCE_KIND_INVALID');
  });

  it('NT-14-4: proef met drie configuratieproblemen toont ze allemaal, met veld, en de overgeslagen controles', async () => {
    const trial: TrialReadResult = {
      ...TRIAL_BLOCKED,
      configProblems: [
        {
          code: 'CONFIG_FIELD_REFERENCE_INVALID',
          message: 'Field reference EAN must be a 1-based column index',
          fieldName: 'EAN',
          revisionField: 'supplierField',
        },
        { code: 'CONFIG_FILTER_INVALID', message: 'Filter invalid', fieldName: 'Merkkolom', revisionField: null },
        { code: 'CONFIG_PRICE_FIELD_MISSING', message: 'No price', fieldName: null, revisionField: 'delimiter' },
      ],
      configChecksSkippedBecause: ['CONFIG_FIELD_REFERENCE_KIND_INVALID'],
    };
    state = freshState({ trial: () => Promise.resolve(json(trial)) });
    installServer();
    const { container } = renderPage();
    await screen.findByTestId('trial-section');

    const result = await runTrial();

    const problems = within(result).getByTestId('trial-config-problems');
    expect(within(within(problems).getByTestId('trial-config-problem-list')).getAllByRole('listitem')).toHaveLength(3);
    expect(problems).toHaveTextContent('3 problemen in de beschrijving van het bestand');
    expect(problems).toHaveTextContent('Veld: Kolom leverancier — Doelveld: EAN-barcode');
    expect(problems).toHaveTextContent('Kolom: Merkkolom');
    expect(problems).toHaveTextContent('Veld: Scheidingsteken');
    expect(problems).not.toHaveTextContent('één fout per keer');
    const skipped = within(problems).getByTestId('trial-config-skipped');
    expect(skipped).toHaveTextContent('na herstel kunnen er nog fouten bijkomen');
    expect(skipped).toHaveTextContent('Ongeldige manier om kolommen te herkennen');

    expect(visibleText(container)).not.toMatch(RAW_CODE);
    expectNoOldWording(container, 'scherm Controleren: proef met configuratieproblemen');
  });

  it('NT-14-4: zonder overgeslagen controles toont de proef geen melding daarover', async () => {
    const trial: TrialReadResult = {
      ...TRIAL_BLOCKED,
      configProblems: [{ code: 'CONFIG_FILTER_INVALID', message: 'Filter invalid', fieldName: null, revisionField: null }],
      configChecksSkippedBecause: [],
    };
    state = freshState({ trial: () => Promise.resolve(json(trial)) });
    installServer();
    renderPage();
    await screen.findByTestId('trial-section');

    const result = await runTrial();

    expect(within(result).getByTestId('trial-config-problems')).toHaveTextContent('Probleem in de beschrijving van het bestand');
    expect(within(result).queryByTestId('trial-config-skipped')).not.toBeInTheDocument();
  });

  it('NT-10.8: zonder bestand geen verzoek, maar een Nederlandse melding', async () => {
    state = freshState();
    installServer();
    renderPage();
    await screen.findByTestId('trial-section');

    fireEvent.click(screen.getByTestId('trial-start'));

    expect(await screen.findByTestId('trial-validation')).toHaveTextContent('Kies eerst een bestand om te testen.');
    expect(calls('/trial-reads', 'POST')).toHaveLength(0);
  });

  it('NT-10.9: twee keer klikken tijdens een lopende proef geeft één verzoek', async () => {
    state = freshState({ trial: () => new Promise<Response>(() => {}) });
    installServer();
    renderPage();
    await screen.findByTestId('trial-section');

    chooseFile();
    fireEvent.click(screen.getByTestId('trial-start'));
    expect(await screen.findByTestId('trial-progress')).toBeInTheDocument();
    expect(screen.getByTestId('trial-start')).toBeDisabled();
    fireEvent.click(screen.getByTestId('trial-start'));

    expect(calls('/trial-reads', 'POST')).toHaveLength(1);
  });

  it('NT-10.10: een weigering van de server (400 zonder bestand) staat in het Nederlands bij de proef', async () => {
    state = freshState({
      trial: () => Promise.resolve(json({ error: 'A file is required', code: 'FILE_REQUIRED' }, 400)),
    });
    installServer();
    renderPage();
    await screen.findByTestId('trial-section');
    chooseFile();
    fireEvent.click(screen.getByTestId('trial-start'));

    expect(await screen.findByText('Kies een bestand')).toBeInTheDocument();
    expect(screen.queryByTestId('trial-result')).not.toBeInTheDocument();
  });

  it('NT-10.11: met enkel het recht Lezen zijn proef en activeren uitgeschakeld mét reden; de checklist blijft', async () => {
    state = freshState();
    installServer();
    renderPage(undefined, testIdentityWith(PERMISSION_READ));

    expect(await screen.findByTestId('readiness-summary')).toHaveTextContent('Nog niet klaar');
    const start = await screen.findByTestId('trial-start');
    expect(start).toBeDisabled();
    expect(start).toHaveAttribute('title', "U heeft het recht 'Beheren' (catalogImport.manage) niet.");
    expect(screen.getByTestId('trial-blocked-reason')).toHaveTextContent("U heeft het recht 'Beheren'");
    const activate = screen.getByTestId('activate-revision');
    expect(activate).toBeDisabled();
    expect(activate).toHaveAttribute('title', "U heeft het recht 'Beheren' (catalogImport.manage) niet.");

    chooseFile();
    fireEvent.submit(start.closest('form')!);
    expect(calls('/trial-reads', 'POST')).toHaveLength(0);
  });

  // --- Blok 3: activeren (V6) --------------------------------------------------------------------------------

  it('NT-10.12: activeren zonder proef mag, met een duidelijke waarschuwing; daarna wordt de checklist herlezen', async () => {
    state = freshState();
    installServer();
    renderPage();
    await screen.findByTestId('readiness-summary');
    expect(calls('/import-links/501/readiness')).toHaveLength(1);

    const button = screen.getByTestId('activate-revision');
    expect(button).toHaveTextContent('Versie 1 in gebruik nemen');
    fireEvent.click(button);

    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByTestId('no-successful-trial-warning')).toHaveTextContent(
      'u heeft nog geen geslaagde proefinlezing gedaan met deze versie',
    );
    expect(within(dialog).getByTestId('no-successful-trial-warning')).toHaveTextContent('U kunt toch activeren');
    // De verplichte waarschuwing van het activeren blijft staan.
    expect(within(dialog).getByTestId('reopen-rejected-cases-warning')).toBeInTheDocument();

    fireEvent.click(within(dialog).getByRole('button', { name: 'In gebruik nemen' }));

    await waitFor(() => expect(screen.getByTestId('readiness-summary')).toHaveTextContent('Klaar voor leveringen'));
    const [[, init]] = calls('/setup/revisions/100/activate', 'POST') as [[string, RequestInit | undefined]];
    expect(JSON.parse(String(init!.body))).toEqual({ approvedBy: 'An Beslisser' });
    expect(calls('/import-links/501/readiness')).toHaveLength(2);
    expect(screen.getByTestId('check-activated')).toBeInTheDocument();
    expect(await screen.findByTestId('what-now-upload')).toHaveAttribute('href', '/upload');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('NT-10.13: na een geslaagde proef met deze versie staat er geen extra waarschuwing', async () => {
    state = freshState();
    installServer();
    renderPage();
    await screen.findByTestId('trial-section');
    await runTrial();

    fireEvent.click(screen.getByTestId('activate-revision'));

    const dialog = screen.getByRole('dialog');
    expect(within(dialog).queryByTestId('no-successful-trial-warning')).not.toBeInTheDocument();
    expect(within(dialog).getByTestId('reopen-rejected-cases-warning')).toBeInTheDocument();
    expect(screen.queryByTestId('activate-tip')).not.toBeInTheDocument();
  });

  it('NT-10.14: na een proef die zou blokkeren, zegt de waarschuwing dat uitdrukkelijk', async () => {
    state = freshState({ trial: () => Promise.resolve(json(TRIAL_BLOCKED)) });
    installServer();
    renderPage();
    await screen.findByTestId('trial-section');
    await runTrial();

    fireEvent.click(screen.getByTestId('activate-revision'));

    expect(within(screen.getByRole('dialog')).getByTestId('no-successful-trial-warning')).toHaveTextContent(
      'uw laatste proefinlezing met deze versie zou de levering tegenhouden',
    );
  });
});
