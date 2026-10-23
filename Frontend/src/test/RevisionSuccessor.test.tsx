/**
 * S1-F4 (E6: het oude bewerkformulier en de verwijderknoppen zijn weg) — het schrijfdeel van scherm 1a: opvolgrevisie maken (E2), een DRAFT bewerken (E3, nu in het paneel), een geërfde
 * mapping/filter verwijderen (E4) en activeren (E5) — `docs/design/revision-successor-design.md` §4, §5,
 * §6, bouwstaprij S1-F4 in §8.
 *
 * Dekt per AGENT.md §2 principe 10:
 * - **normaal scenario**: opvolger maken slaagt en opent de nieuwe DRAFT; een drempel wijzigen verstuurt
 *   precies één veld; een mapping verwijderen slaagt; activeren slaagt.
 * - **ontbrekende data**: geen reden bij "opvolger maken" (bevestigen blijft uit, geen verzoek); een
 *   verplicht tekstveld leeggemaakt; niets gewijzigd → geen verzoek.
 * - **ongeldige input**: een onleesbaar getal wordt nooit stil 0; een negatief percentage komt als 400
 *   met de letterlijke servertekst terug.
 * - **geweigerde toestand**: DRAFT-bron klonen is uitgeschakeld mét reden; 409
 *   `REVISION_DRAFT_ALREADY_EXISTS`; 409 `CONFIG_BOOKMARK_PLACE_UNRESOLVED` bij verwijderen; 409
 *   `REVISION_ACTIVATION_CONFLICT` bij activeren.
 * - **grensgeval identiteit**: R-REV-X3 zonder bevestiging (geen verzoek), mét bevestiging
 *   (`acknowledgeIdentityChange: true`), en R-REV-X2 die **onvoorwaardelijk** is — er mag dan geen
 *   bevestigingsoptie naast staan.
 * - **rechtenspiegel**: zonder `MANAGE` staan alle schrijfacties uit mét reden.
 * - **harde ontwerpgrens §2**: geen enkele actie op bookmarkdeclaraties.
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, cleanup, fireEvent, waitFor, within } from '@testing-library/react';
import { RouterProvider, createMemoryRouter } from 'react-router-dom';
import { ActorProvider } from '../actor/ActorContext';
import { TEST_IDENTITY, testIdentityWith } from './testIdentity';
import { PERMISSION_READ } from '../api/types';
import type { RevisionDetail, RevisionRow } from '../api/types';
import { routes } from '../routes';
import { AdvancedSection, BookmarksSection } from '../features/setup/EditorSections';
import { CreateSuccessorAction } from '../features/setup/CreateSuccessorAction';
import { ActivateRevisionAction } from '../features/setup/ActivateRevisionAction';

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

type FetchMock = ReturnType<typeof vi.fn>;

function mockFetch(handler: (url: string, init: RequestInit | undefined) => Response): FetchMock {
  const mock = vi.fn((input: RequestInfo | URL, init?: RequestInit) =>
    Promise.resolve(handler(typeof input === 'string' ? input : input.toString(), init)),
  );
  global.fetch = mock as unknown as typeof fetch;
  return mock;
}

/** Alle niet-lezende verzoeken; de teller waarmee "er is niets verstuurd" bewijsbaar wordt. */
function writeCalls(mock: FetchMock): Array<[string, RequestInit]> {
  return mock.mock.calls
    .filter((args) => {
      const method = ((args[1] as RequestInit | undefined)?.method ?? 'GET').toUpperCase();
      return method !== 'GET' && method !== 'HEAD';
    })
    .map((args) => [String(args[0]), args[1] as RequestInit]);
}

// --- Vaste testgegevens -----------------------------------------------------------------------------

const ACTIVE_ROW: RevisionRow = { id: 99, definitionId: 10, revisionNumber: 1, status: 'ACTIVE' };
const DRAFT_ROW: RevisionRow = { id: 100, definitionId: 10, revisionNumber: 2, status: 'DRAFT' };
const SUPERSEDED_ROW: RevisionRow = { id: 98, definitionId: 10, revisionNumber: 0, status: 'SUPERSEDED' };

const DRAFT_DETAIL: RevisionDetail = {
  id: 100,
  definitionId: 10,
  revisionNumber: 2,
  status: 'DRAFT',
  basedOnRevisionId: 99,
  changeReason: 'Nieuwe prijslijst 2027',
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
  structureFieldReferenceKind: 'HEADER_NAME',
  structureExpectedColumnCount: 12,
  accessDeliverySetKind: 'FULL_SNAPSHOT',
  recordBasePriceField: 'PRIJS',
  recordDescriptionField: 'OMSCHRIJVING',
  recordCurrencyField: null,
  recordCanonicalisationVersion: 1,
  basePriceZeroAllowed: false,
  basePriceNegativeAllowed: false,
  priceDeviationPercent: 20,
  priceDeviationSeverity: 'WARNING',
  priceDerivationTolerance: 0.01,
  priceAvgShortWindow: 3,
  priceAvgLongWindow: 12,
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
      revisionId: 100,
      sequenceNumber: 1,
      targetFieldCode: 'EAN',
      sourceReference: 'ARTNR',
      valueKind: 'SOURCE_FIELD',
      criticality: 'CRITICAL',
    },
  ],
  filters: [
    {
      id: 6001,
      revisionId: 100,
      sequenceNumber: 1,
      sourceReference: 'STATUS',
      operator: 'EQUALS',
      compareValue: 'A',
      outcome: 'INCLUDE',
    },
  ],
  fieldCriticalities: [{ revisionId: 100, fieldKey: 'BASE_PRICE', criticality: 'CRITICAL' }],
  bookmarks: [
    {
      id: 1000,
      revisionId: 100,
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
};

const ACTIVE_DETAIL: RevisionDetail = { ...DRAFT_DETAIL, id: 99, revisionNumber: 1, status: 'ACTIVE' };

/** Het antwoord van E2/E3/E5: dezelfde kleine `RevisionView` voor alle drie. */
function revisionView(overrides: Record<string, unknown> = {}) {
  return {
    id: 100,
    definitionId: 10,
    revisionNumber: 2,
    status: 'DRAFT',
    identityProfileKind: 'THREE_PART',
    delimiter: ';',
    hasHeader: true,
    fieldReferenceKind: 'NAME',
    canonicalisationVersion: 1,
    supplierField: 'LEV',
    supplierGroupField: 'LEVGRP',
    supplierReferenceField: 'LEVREF',
    discountCodeField: null,
    basePriceField: 'PRIJS',
    descriptionField: 'OMSCHRIJVING',
    currencyField: null,
    creationThresholdSharePercent: 30,
    maxCriticalSharePercent: 5,
    maxRejectedSharePercent: 10,
    bulkIncidentSharePercent: 15,
    ...overrides,
  };
}

const ORGANISATIONS_RESPONSE = {
  content: [{ id: 1, code: 'ORG-1', name: 'VROOAM', type: 'PURCHASING_ASSOCIATION', active: true }],
  page: 0,
  size: 50,
  totalElements: 1,
  totalPages: 1,
};

const DEFINITIONS_RESPONSE = {
  content: [
    {
      id: 10,
      code: 'DEF-10',
      name: 'Definitie met actieve revisie',
      usageType: 'OWN_DEFINITION',
      sourceOrganisationId: 1,
      sourceOrganisationCode: 'ORG-1',
      activeRevisionId: 99,
    },
  ],
  page: 0,
  size: 50,
  totalElements: 1,
  totalPages: 1,
};

function revisionsResponse(rows: RevisionRow[]) {
  return { content: rows, page: 0, size: 50, totalElements: rows.length, totalPages: 1 };
}

const IMPORT_LINKS_RESPONSE = { content: [], page: 0, size: 50, totalElements: 0, totalPages: 1 };

// --- Renderhulpen -----------------------------------------------------------------------------------

function renderSuccessorAction(revision: RevisionRow, identity = TEST_IDENTITY) {
  const onCreated = vi.fn();
  render(
    <ActorProvider identity={identity}>
      <CreateSuccessorAction revision={revision} onCreated={onCreated} />
    </ActorProvider>,
  );
  return { onCreated };
}

function renderActivateAction(revision: RevisionDetail = DRAFT_DETAIL, identity = TEST_IDENTITY) {
  const onActivated = vi.fn();
  render(
    <ActorProvider identity={identity}>
      <ActivateRevisionAction revision={revision} onActivated={onActivated} />
    </ActorProvider>,
  );
  return { onActivated };
}

function fillReason(text: string) {
  fireEvent.change(screen.getByLabelText(/^Reden/), { target: { value: text } });
}

describe('S1-F4 — schrijfdeel van scherm 1a (opvolgrevisie / DRAFT bewerken / activeren)', () => {
  const originalFetch = global.fetch;

  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    vi.clearAllMocks();
    global.fetch = originalFetch;
  });

  // --- E2: opvolger maken -----------------------------------------------------------------------

  it('S1-F4.1: opvolger maken slaagt en verstuurt changeReason + createdBy', async () => {
    const fetchMock = mockFetch(() => jsonResponse(revisionView(), 201));
    const { onCreated } = renderSuccessorAction(ACTIVE_ROW);

    fireEvent.click(screen.getByTestId('create-successor-99'));
    // Zonder reden blijft bevestigen uit: verplicht redenveld, patroon CancelDialog.
    expect(screen.getByRole('button', { name: 'Opvolger aanmaken' })).toBeDisabled();
    expect(writeCalls(fetchMock)).toHaveLength(0);

    fillReason('Prijslijst 2027');
    fireEvent.click(screen.getByRole('button', { name: 'Opvolger aanmaken' }));

    await waitFor(() => {
      expect(onCreated).toHaveBeenCalledTimes(1);
    });
    const [url, init] = writeCalls(fetchMock)[0]!;
    expect(url).toContain('/setup/revisions/99/successor');
    expect(init.method).toBe('POST');
    expect(JSON.parse(String(init.body))).toEqual({
      changeReason: 'Prijslijst 2027',
      createdBy: 'An Beslisser',
    });
  });

  it('S1-F4.2: op een DRAFT-bron is "Opvolger maken" uitgeschakeld mét reden en gebeurt er niets', () => {
    const fetchMock = mockFetch(() => jsonResponse(revisionView(), 201));
    renderSuccessorAction(DRAFT_ROW);

    const button = screen.getByTestId('create-successor-100');
    expect(button).toBeDisabled();
    expect(button.getAttribute('title')).toContain('REVISION_NOT_CLONEABLE');
    // NT-11c: de reden is gewoon Nederlands, de code staat enkel in de tooltip.
    expect(screen.getByTestId('create-successor-reason-100')).toHaveTextContent('dit is al een concept');
    expect(screen.getByTestId('create-successor-reason-100').textContent).not.toContain('REVISION_NOT_CLONEABLE');
    expect(button.getAttribute('title')).toContain('(technische code: REVISION_NOT_CLONEABLE)');
    expect(writeCalls(fetchMock)).toHaveLength(0);
  });

  it('S1-F4.3: een SUPERSEDED bron mag wél gekloond worden (O2)', () => {
    mockFetch(() => jsonResponse(revisionView(), 201));
    renderSuccessorAction(SUPERSEDED_ROW);

    expect(screen.getByTestId('create-successor-98')).toBeEnabled();
  });

  it('S1-F4.4: 409 REVISION_DRAFT_ALREADY_EXISTS blijft in de open dialoog staan', async () => {
    mockFetch(() =>
      jsonResponse(
        {
          error: 'Import definition 10 already has an open DRAFT revision #2',
          code: 'REVISION_DRAFT_ALREADY_EXISTS',
        },
        409,
      ),
    );
    const { onCreated } = renderSuccessorAction(ACTIVE_ROW);

    fireEvent.click(screen.getByTestId('create-successor-99'));
    fillReason('Tweede poging');
    fireEvent.click(screen.getByRole('button', { name: 'Opvolger aanmaken' }));

    expect(await screen.findByText('Er staat al een concept open')).toBeInTheDocument();
    // De letterlijke servertekst noemt het bestaande revisienummer en wordt volledig getoond.
    expect(screen.getByText(/already has an open DRAFT revision #2/)).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(onCreated).not.toHaveBeenCalled();
  });

  it('S1-F4.5: zonder MANAGE is "Opvolger maken" uitgeschakeld mét reden', () => {
    mockFetch(() => jsonResponse(revisionView(), 201));
    renderSuccessorAction(ACTIVE_ROW, testIdentityWith(PERMISSION_READ));

    const button = screen.getByTestId('create-successor-99');
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('title', "U heeft het recht 'Beheren' (catalogImport.manage) niet.");
  });

  it('S1-F4.6: na een geslaagde kloon opent het leveranciersdetail de nieuwe DRAFT (Bestand en velden, Bewerken aan)', async () => {
    // De revisielijst levert de nieuwe DRAFT pas ná de kloon; de UI opent haar rechtstreeks uit het
    // antwoord van E2 en wacht dus niet op die herlaadde lijst.
    let cloned = false;
    const fetchMock = mockFetch((url) => {
      if (url.includes('/setup/revisions/99/successor')) {
        cloned = true;
        return jsonResponse(revisionView(), 201);
      }
      if (url.includes('/definitions/10/revisions/99')) {
        return jsonResponse(ACTIVE_DETAIL);
      }
      if (url.includes('/definitions/10/revisions/100')) {
        return jsonResponse(DRAFT_DETAIL);
      }
      if (url.includes('/definitions/10/revisions')) {
        return jsonResponse(cloned ? revisionsResponse([ACTIVE_ROW, DRAFT_ROW]) : revisionsResponse([ACTIVE_ROW]));
      }
      if (url.includes('/import-links')) {
        return jsonResponse(IMPORT_LINKS_RESPONSE);
      }
      if (url.includes('/definitions')) {
        return jsonResponse(DEFINITIONS_RESPONSE);
      }
      if (url.includes('/source-organisations')) {
        return jsonResponse(ORGANISATIONS_RESPONSE);
      }
      throw new Error(`Onverwachte URL in test: ${url}`);
    });

    const router = createMemoryRouter(routes, { initialEntries: ['/setup/suppliers/1/versions'] });
    render(
      <ActorProvider identity={TEST_IDENTITY}>
        <RouterProvider router={router} />
      </ActorProvider>,
    );

    await screen.findByText('Versie 1');
    fireEvent.click(screen.getByTestId('create-successor-99'));
    fillReason('Prijslijst 2027');
    fireEvent.click(screen.getByRole('button', { name: 'Opvolger aanmaken' }));

    // De nieuwe DRAFT wordt rechtstreeks geopend (Bewerken staat aan); de conceptbalk hoort bij versie #100.
    expect(await screen.findByTestId('mode-edit')).toHaveAttribute('aria-pressed', 'true');
    expect(await screen.findByTestId('concept-bar')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/setup/suppliers/1/fields');
    expect(router.state.location.search).toContain('versie=100');
    // Eén schrijfverzoek, geen automatische herhaling.
    expect(writeCalls(fetchMock)).toHaveLength(1);
  });

  // --- Wat het oude bewerkformulier en de verwijderknoppen deden, staat nu in het nieuwe paneel (E6) --------
  // Wijzigen van velden, bevestiging van een identiteitswijziging (R-REV-X3), de canonicalisatieblokkade (R-REV-X2) en het
  // verwijderen van een extra veld of filter: zie `FieldsEditor*.test.tsx`; de pure "null = ongewijzigd"-regels staan in
  // `RevisionEditFixedIdentity.test.tsx`.

  it('S1-F4.23: bookmarkdeclaraties zijn alleen-lezen — geen enkele wijzigactie (harde grens §2)', () => {
    render(<BookmarksSection revision={DRAFT_DETAIL} />);

    const bookmarks = screen.getByTestId('revision-bookmarks');
    // NT-11c: het label staat in beeld, de technische naam in de tooltip.
    expect(bookmarks).toHaveTextContent('Bestandsprefix');
    expect(bookmarks.textContent).not.toContain('BESTANDS_PREFIX');
    expect(within(bookmarks).queryAllByRole('button')).toHaveLength(0);
    expect(screen.getByTestId('bookmark-boundary-note')).toHaveTextContent('alleen');
    // Ook de ingevulde DEFINITION-waarde is enkel leesmateriaal.
    const values = screen.getByTestId('revision-bookmark-values');
    expect(within(values).queryAllByRole('button')).toHaveLength(0);
  });

  it('E6: Geavanceerd toont kritiek per veld, herkomst en technische details alleen-lezen', () => {
    render(<AdvancedSection revision={DRAFT_DETAIL} />);

    const section = screen.getByTestId('read-advanced');
    expect(section).toHaveTextContent('Geavanceerd');
    expect(section).toHaveTextContent('Kritiek per veld aangepast');
    expect(section).toHaveTextContent('Aangemaakt door');
    expect(section).toHaveTextContent('Technische details (voor support)');
    expect(within(section).queryAllByRole('button')).toHaveLength(0);
  });

  // --- E5: activeren ----------------------------------------------------------------------------

  it('S1-F4.24: activeren toont de R-CASE-03-waarschuwing en verstuurt approvedBy', async () => {
    const fetchMock = mockFetch(() => jsonResponse(revisionView({ status: 'ACTIVE' })));
    const { onActivated } = renderActivateAction();

    fireEvent.click(screen.getByTestId('activate-revision'));

    const warning = screen.getByTestId('reopen-rejected-cases-warning');
    // NT-11c: zelfde betekenis in gewoon Nederlands — geen statuscode en geen technische klassenaam (ImportDefinition).
    expect(warning).toHaveTextContent('afgewezen afwijkingen van alle koppelingen van deze importdefinitie');
    expect(warning).toHaveTextContent('opnieuw geopend');
    expect(warning).toHaveTextContent('bewust aanvaard gevolg');
    expect(warning.textContent).not.toContain('REJECTED');
    expect(warning.textContent).not.toContain('ImportDefinition');
    // De waarschuwing staat vóór de bevestiging, in dezelfde dialoog.
    expect(within(screen.getByRole('dialog')).getByTestId('reopen-rejected-cases-warning')).toBe(warning);
    expect(writeCalls(fetchMock)).toHaveLength(0);

    fireEvent.click(screen.getByRole('button', { name: 'Activeren' }));

    await waitFor(() => {
      expect(onActivated).toHaveBeenCalledTimes(1);
    });
    const [url, init] = writeCalls(fetchMock)[0]!;
    expect(url).toContain('/setup/revisions/100/activate');
    expect(init.method).toBe('POST');
    expect(JSON.parse(String(init.body))).toEqual({ approvedBy: 'An Beslisser' });
  });

  it('S1-F4.25: 409 REVISION_ACTIVATION_CONFLICT blijft in de open dialoog staan', async () => {
    mockFetch(() =>
      jsonResponse(
        {
          error: 'Another revision of this import definition was activated at the same moment',
          code: 'REVISION_ACTIVATION_CONFLICT',
        },
        409,
      ),
    );
    const { onActivated } = renderActivateAction();

    fireEvent.click(screen.getByTestId('activate-revision'));
    fireEvent.click(screen.getByRole('button', { name: 'Activeren' }));

    expect(await screen.findByText('Gelijktijdige activatie')).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(onActivated).not.toHaveBeenCalled();
  });

  it('S1-F4.26: op een ACTIVE revisie is activeren uitgeschakeld mét reden', () => {
    mockFetch(() => jsonResponse(revisionView()));
    renderActivateAction(ACTIVE_DETAIL);

    const button = screen.getByTestId('activate-revision');
    expect(button).toBeDisabled();
    expect(screen.getByTestId('activate-revision-reason')).toHaveTextContent('al actief');
  });

  it('S1-F4.27: zonder MANAGE is activeren uitgeschakeld mét reden', () => {
    mockFetch(() => jsonResponse(revisionView()));
    renderActivateAction(DRAFT_DETAIL, testIdentityWith(PERMISSION_READ));

    const button = screen.getByTestId('activate-revision');
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('title', "U heeft het recht 'Beheren' (catalogImport.manage) niet.");
  });

  it('S1-F4.28: met de setup-API-vlag uit (404 zonder code) volgt de eigen melding, niet de generieke', async () => {
    mockFetch((_url, init) => {
      if ((init?.method ?? 'GET') === 'POST') {
        return new Response(null, { status: 404 });
      }
      return jsonResponse(revisionView());
    });
    renderActivateAction();

    fireEvent.click(screen.getByTestId('activate-revision'));
    fireEvent.click(screen.getByRole('button', { name: 'Activeren' }));

    expect(
      await screen.findByText(/De schrijfacties op de inrichting .* staan niet open op deze omgeving/),
    ).toBeInTheDocument();
    expect(screen.queryByText(/De bewerking is geweigerd/)).not.toBeInTheDocument();
  });
});
