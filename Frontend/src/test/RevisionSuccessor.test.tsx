/**
 * S1-F4 — het schrijfdeel van scherm 1a: opvolgrevisie maken (E2), een DRAFT bewerken (E3), een geërfde
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
import { MemoryRouter } from 'react-router-dom';
import { ActorProvider } from '../actor/ActorContext';
import { TEST_IDENTITY, testIdentityWith } from './testIdentity';
import { PERMISSION_READ } from '../api/types';
import type { RevisionDetail, RevisionRow } from '../api/types';
import { SetupOverviewPage } from '../features/setup/SetupOverviewPage';
import { RevisionDetailSection } from '../features/setup/RevisionDetailSection';
import { RevisionEditForm } from '../features/setup/RevisionEditForm';
import { CreateSuccessorAction } from '../features/setup/CreateSuccessorAction';
import { ActivateRevisionAction } from '../features/setup/ActivateRevisionAction';

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function noContentResponse(): Response {
  return new Response(null, { status: 204 });
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

function bodyOfWrite(mock: FetchMock, pathPart: string): Record<string, unknown> {
  const call = writeCalls(mock).find(([url]) => url.includes(pathPart));
  expect(call, `verwacht een schrijfverzoek naar ${pathPart}`).toBeDefined();
  return JSON.parse(String(call![1].body)) as Record<string, unknown>;
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

function renderEditForm(revision: RevisionDetail = DRAFT_DETAIL, identity = TEST_IDENTITY) {
  const onUpdated = vi.fn();
  render(
    <ActorProvider identity={identity}>
      <RevisionEditForm revision={revision} onUpdated={onUpdated} />
    </ActorProvider>,
  );
  return { onUpdated };
}

function renderDetailSection(identity = TEST_IDENTITY) {
  const onRevisionChanged = vi.fn();
  render(
    <ActorProvider identity={identity}>
      <RevisionDetailSection definitionId={10} revisionId={100} onRevisionChanged={onRevisionChanged} />
    </ActorProvider>,
  );
  return { onRevisionChanged };
}

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

function submitEditForm() {
  fireEvent.submit(screen.getByTestId('revision-edit-form'));
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

  it('S1-F4.6: na een geslaagde kloon opent scherm 1a de nieuwe DRAFT', async () => {
    // De revisielijst levert de nieuwe DRAFT pas ná de kloon; de UI opent haar rechtstreeks uit het
    // antwoord van E2 en wacht dus niet op die herlaadde lijst.
    let cloned = false;
    const fetchMock = mockFetch((url) => {
      if (url.includes('/setup/revisions/99/successor')) {
        cloned = true;
        return jsonResponse(revisionView(), 201);
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

    await screen.findByText('Versie 1');
    fireEvent.click(screen.getByTestId('create-successor-99'));
    fillReason('Prijslijst 2027');
    fireEvent.click(screen.getByRole('button', { name: 'Opvolger aanmaken' }));

    // De nieuwe DRAFT wordt rechtstreeks geopend: het revisiedetail van #100 verschijnt.
    expect(await screen.findByTestId('revision-detail')).toBeInTheDocument();
    expect(await screen.findByTestId('revision-edit-form')).toBeInTheDocument();
    // Eén schrijfverzoek, geen automatische herhaling.
    expect(writeCalls(fetchMock)).toHaveLength(1);
  });

  // --- E3: DRAFT bewerken -----------------------------------------------------------------------

  it('S1-F4.7: een gewone drempelwijziging verstuurt alleen dat ene veld; al de rest is null', async () => {
    const fetchMock = mockFetch(() => jsonResponse(revisionView({ maxCriticalSharePercent: 7.5 })));
    const { onUpdated } = renderEditForm();

    fireEvent.change(screen.getByLabelText(/^Maximum ter beoordeling/), { target: { value: '7.5' } });
    submitEditForm();

    await waitFor(() => {
      expect(onUpdated).toHaveBeenCalledTimes(1);
    });
    const body = bodyOfWrite(fetchMock, '/setup/revisions/100');
    expect(writeCalls(fetchMock)[0]![1].method).toBe('PATCH');
    expect(body.maxCriticalSharePercent).toBe(7.5);
    // "null = ongewijzigd": geen enkel identiteits-, structuur- of prijsveld gaat mee.
    expect(body.identityProfileKind).toBeNull();
    expect(body.supplierField).toBeNull();
    expect(body.canonicalisationVersion).toBeNull();
    expect(body.delimiter).toBeNull();
    expect(body.basePriceField).toBeNull();
    expect(body.acknowledgeIdentityChange).toBeNull();
    expect(body.createdBy).toBe('An Beslisser');
    expect(screen.getByTestId('revision-edit-saved')).toHaveTextContent('is bijgewerkt');
  });

  it('S1-F4.8: zonder wijziging wordt er niets verstuurd', async () => {
    const fetchMock = mockFetch(() => jsonResponse(revisionView()));
    renderEditForm();

    submitEditForm();

    expect(await screen.findByTestId('revision-edit-validation')).toHaveTextContent('Er is niets gewijzigd');
    expect(writeCalls(fetchMock)).toHaveLength(0);
  });

  it('S1-F4.9: een onleesbaar getal is een fout, nooit stil 0 of leeg', async () => {
    const fetchMock = mockFetch(() => jsonResponse(revisionView()));
    renderEditForm();

    fireEvent.change(screen.getByLabelText(/^Drempel nieuwe artikelen/), { target: { value: 'dertig' } });
    submitEditForm();

    const message = await screen.findByTestId('revision-edit-validation');
    expect(message).toHaveTextContent('"Drempel nieuwe artikelen (%)" is geen leesbaar getal');
    expect(message).toHaveTextContent('niets op 0 gezet');
    expect(writeCalls(fetchMock)).toHaveLength(0);
  });

  it('S1-F4.10: een leeggemaakt verplicht veld wordt nooit als lege waarde verstuurd', async () => {
    const fetchMock = mockFetch(() => jsonResponse(revisionView()));
    renderEditForm();

    fireEvent.change(screen.getByLabelText(/^Kolom basisprijs/), { target: { value: '   ' } });
    submitEditForm();

    expect(await screen.findByTestId('revision-edit-validation')).toHaveTextContent(
      '"Kolom basisprijs" is verplicht en mag niet leeg zijn',
    );
    expect(writeCalls(fetchMock)).toHaveLength(0);
  });

  it('S1-F4.11: een optioneel veld leegmaken verstuurt een uitdrukkelijk lege waarde', async () => {
    const fetchMock = mockFetch(() => jsonResponse(revisionView({ descriptionField: null })));
    renderEditForm();

    fireEvent.change(screen.getByLabelText(/^Kolom omschrijving/), { target: { value: '' } });
    submitEditForm();

    await waitFor(() => {
      expect(writeCalls(fetchMock)).toHaveLength(1);
    });
    const body = bodyOfWrite(fetchMock, '/setup/revisions/100');
    expect(body.descriptionField).toBe('');
    // Een veld dat al `null` was en leeg blijft, geldt als ongewijzigd — geen lege waarde.
    expect(body.currencyField).toBeNull();
  });

  it('S1-F4.12: R-REV-X3 — een identiteitswijziging zonder bevestiging verstuurt niets', async () => {
    const fetchMock = mockFetch(() => jsonResponse(revisionView()));
    renderEditForm();

    // Vóór de wijziging is er geen bevestigingsvinkje: het heeft bewust geen standaardwaarde.
    expect(screen.queryByTestId('identity-change-acknowledge')).not.toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/^Kolom referentie/), { target: { value: 'ANDERE_REF' } });

    expect(screen.getByTestId('identity-change-acknowledge')).toBeInTheDocument();
    expect(
      (screen.getByLabelText(/^Ja, ik weet dat elk bestaand artikel/) as HTMLInputElement).checked,
    ).toBe(false);

    submitEditForm();

    expect(await screen.findByTestId('revision-edit-validation')).toHaveTextContent(
      'Bevestig hieronder uitdrukkelijk',
    );
    expect(writeCalls(fetchMock)).toHaveLength(0);
  });

  it('S1-F4.13: R-REV-X3 — mét bevestiging gaat acknowledgeIdentityChange: true mee', async () => {
    const fetchMock = mockFetch(() => jsonResponse(revisionView({ supplierReferenceField: 'ANDERE_REF' })));
    renderEditForm();

    fireEvent.change(screen.getByLabelText(/^Kolom referentie/), { target: { value: 'ANDERE_REF' } });
    fireEvent.click(screen.getByLabelText(/^Ja, ik weet dat elk bestaand artikel/));
    submitEditForm();

    await waitFor(() => {
      expect(writeCalls(fetchMock)).toHaveLength(1);
    });
    const body = bodyOfWrite(fetchMock, '/setup/revisions/100');
    expect(body.supplierReferenceField).toBe('ANDERE_REF');
    expect(body.acknowledgeIdentityChange).toBe(true);
  });

  it('S1-F4.14: 409 IDENTITY_CHANGE_NOT_ACKNOWLEDGED van de server wordt getoond bij het formulier', async () => {
    mockFetch(() =>
      jsonResponse(
        { error: 'Revision 100 changes the offer identity', code: 'IDENTITY_CHANGE_NOT_ACKNOWLEDGED' },
        409,
      ),
    );
    renderEditForm();

    fireEvent.change(screen.getByLabelText(/^Herkenning van een artikel/), {
      target: { value: 'FOUR_PART_WITH_DISCOUNT_CODE' },
    });
    fireEvent.click(screen.getByLabelText(/^Ja, ik weet dat elk bestaand artikel/));
    submitEditForm();

    expect(await screen.findByText('Identiteitswijziging niet bevestigd')).toBeInTheDocument();
  });

  it('S1-F4.15: R-REV-X2 — de canonicalisatieblokkade is onvoorwaardelijk en biedt geen bevestiging', async () => {
    mockFetch(() =>
      jsonResponse(
        {
          error:
            'Revision 100 cannot change recordCanonicalisationVersion from 1 to 2: import definition 10 ' +
            'already has accepted source state',
          code: 'REVISION_CANONICALISATION_CHANGE_BLOCKED',
        },
        409,
      ),
    );
    renderEditForm();

    fireEvent.change(screen.getByLabelText(/^Herkenningsversie/), { target: { value: '2' } });
    // Vooraf al een waarschuwing, nog vóór het versturen.
    expect(screen.getByTestId('canonicalisation-change-warning')).toHaveTextContent('van 1 naar 2');

    // Ook een identiteitsveld wijzigen: de bevestiging daarvoor mag na de blokkade niet blijven staan.
    fireEvent.change(screen.getByLabelText(/^Kolom groep/), { target: { value: 'ANDERE_GRP' } });
    fireEvent.click(screen.getByLabelText(/^Ja, ik weet dat elk bestaand artikel/));
    submitEditForm();

    const blocked = await screen.findByTestId('canonicalisation-blocked');
    expect(blocked).toHaveTextContent('niet te omzeilen');
    expect(screen.getByText('Herkenningsversie kan niet gewijzigd worden')).toBeInTheDocument();
    // Geen bevestigingsoptie naast een blokkade die de server toch niet aanvaardt.
    expect(screen.queryByTestId('identity-change-acknowledge')).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/^Ja, ik weet dat elk bestaand artikel/)).not.toBeInTheDocument();

    // Zodra de gebruiker iets aanpast, verdwijnt de blokkade-melding: nooit een doodlopende toestand.
    fireEvent.change(screen.getByLabelText(/^Herkenningsversie/), { target: { value: '1' } });
    expect(screen.queryByTestId('canonicalisation-blocked')).not.toBeInTheDocument();
    expect(screen.getByTestId('identity-change-acknowledge')).toBeInTheDocument();
  });

  it('S1-F4.16: een 400 op een negatief percentage toont de letterlijke servertekst', async () => {
    mockFetch(() =>
      jsonResponse({ error: 'maxCriticalSharePercent must not be negative' }, 400),
    );
    renderEditForm();

    fireEvent.change(screen.getByLabelText(/^Maximum ter beoordeling/), { target: { value: '-5' } });
    submitEditForm();

    expect(await screen.findByText('Ongeldige invoer')).toBeInTheDocument();
    expect(screen.getByText('maxCriticalSharePercent must not be negative')).toBeInTheDocument();
  });

  it('S1-F4.17: op een niet-DRAFT revisie is het bewerkformulier uitgeschakeld mét reden', async () => {
    mockFetch(() => jsonResponse(ACTIVE_DETAIL));
    renderEditForm(ACTIVE_DETAIL);

    const button = screen.getByRole('button', { name: 'Wijzigingen opslaan' });
    expect(button).toBeDisabled();
    expect(button.getAttribute('title')).toContain('REVISION_NOT_EDITABLE');
  });

  it('S1-F4.18: zonder MANAGE is opslaan uitgeschakeld mét reden en gebeurt er niets bij submit', () => {
    const fetchMock = mockFetch(() => jsonResponse(revisionView()));
    renderEditForm(DRAFT_DETAIL, testIdentityWith(PERMISSION_READ));

    const button = screen.getByRole('button', { name: 'Wijzigingen opslaan' });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('title', "U heeft het recht 'Beheren' (catalogImport.manage) niet.");

    fireEvent.change(screen.getByLabelText(/^Maximum ter beoordeling/), { target: { value: '7.5' } });
    submitEditForm();
    expect(writeCalls(fetchMock)).toHaveLength(0);
  });

  // --- E4: mapping/filter verwijderen -----------------------------------------------------------

  it('S1-F4.19: een mapping verwijderen slaagt en herlaadt het detail', async () => {
    const fetchMock = mockFetch((_url, init) => {
      if ((init?.method ?? 'GET') === 'DELETE') {
        return noContentResponse();
      }
      return jsonResponse(DRAFT_DETAIL);
    });
    const { onRevisionChanged } = renderDetailSection();

    fireEvent.click(await screen.findByTestId('delete-mapping-5001'));
    fireEvent.click(screen.getByRole('button', { name: 'Definitief verwijderen' }));

    await waitFor(() => {
      expect(onRevisionChanged).toHaveBeenCalledTimes(1);
    });
    const [url, init] = writeCalls(fetchMock)[0]!;
    expect(url).toContain('/setup/revisions/100/mappings/5001');
    expect(init.method).toBe('DELETE');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('S1-F4.20: een filter verwijderen gebruikt het filterpad', async () => {
    const fetchMock = mockFetch((_url, init) => {
      if ((init?.method ?? 'GET') === 'DELETE') {
        return noContentResponse();
      }
      return jsonResponse(DRAFT_DETAIL);
    });
    renderDetailSection();

    fireEvent.click(await screen.findByTestId('delete-filter-6001'));
    fireEvent.click(screen.getByRole('button', { name: 'Definitief verwijderen' }));

    await waitFor(() => {
      expect(writeCalls(fetchMock)).toHaveLength(1);
    });
    expect(writeCalls(fetchMock)[0]![0]).toContain('/setup/revisions/100/filters/6001');
  });

  it('S1-F4.21: 409 van een bookmarkdeclaratie blijft in de dialoog staan; er is niets verwijderd', async () => {
    mockFetch((_url, init) => {
      if ((init?.method ?? 'GET') === 'DELETE') {
        return jsonResponse(
          {
            error:
              "bookmark 'BESTANDS_PREFIX' points at target field ARTICLE_CODE which this revision does " +
              'not map; nothing was deleted from revision 100',
            code: 'CONFIG_BOOKMARK_PLACE_UNRESOLVED',
          },
          409,
        );
      }
      return jsonResponse(DRAFT_DETAIL);
    });
    const { onRevisionChanged } = renderDetailSection();

    fireEvent.click(await screen.findByTestId('delete-mapping-5001'));
    fireEvent.click(screen.getByRole('button', { name: 'Definitief verwijderen' }));

    expect(await screen.findByText('Een invulpunt steunt nog op deze rij')).toBeInTheDocument();
    expect(screen.getByText(/nothing was deleted from revision 100/)).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(onRevisionChanged).not.toHaveBeenCalled();
  });

  it('S1-F4.22: op een niet-DRAFT revisie staat er geen verwijderknop en geen bewerkformulier', async () => {
    mockFetch(() => jsonResponse(ACTIVE_DETAIL));
    render(
      <ActorProvider identity={TEST_IDENTITY}>
        <RevisionDetailSection definitionId={10} revisionId={99} onRevisionChanged={vi.fn()} />
      </ActorProvider>,
    );

    await screen.findByTestId('revision-mappings');
    expect(screen.queryByTestId('delete-mapping-5001')).not.toBeInTheDocument();
    expect(screen.queryByTestId('revision-edit-form')).not.toBeInTheDocument();
    const notEditable = screen.getByTestId('revision-not-editable-reason');
    expect(notEditable).toHaveTextContent('alleen een concept is aan te passen');
    expect(notEditable.textContent).not.toContain('REVISION_NOT_EDITABLE');
    expect(notEditable.getAttribute('title')).toContain('(technische code: REVISION_NOT_EDITABLE)');
    expect(screen.queryByTestId('activate-revision')).not.toBeInTheDocument();
  });

  it('S1-F4.23: bookmarkdeclaraties zijn alleen-lezen — geen enkele wijzigactie (harde grens §2)', async () => {
    mockFetch(() => jsonResponse(DRAFT_DETAIL));
    renderDetailSection();

    const bookmarks = await screen.findByTestId('revision-bookmarks');
    // NT-11c: het label staat in beeld, de technische naam in de tooltip.
    expect(bookmarks).toHaveTextContent('Bestandsprefix');
    expect(bookmarks.textContent).not.toContain('BESTANDS_PREFIX');
    expect(within(bookmarks).queryAllByRole('button')).toHaveLength(0);
    expect(screen.getByTestId('bookmark-boundary-note')).toHaveTextContent('bewust alleen-lezen');
    // Ook de ingevulde DEFINITION-waarde is enkel leesmateriaal.
    const values = screen.getByTestId('revision-bookmark-values');
    expect(within(values).queryAllByRole('button')).toHaveLength(0);
  });

  // --- E5: activeren ----------------------------------------------------------------------------

  it('S1-F4.24: activeren toont de R-CASE-03-waarschuwing en verstuurt approvedBy', async () => {
    const fetchMock = mockFetch(() => jsonResponse(revisionView({ status: 'ACTIVE' })));
    const { onActivated } = renderActivateAction();

    fireEvent.click(screen.getByTestId('activate-revision'));

    const warning = screen.getByTestId('reopen-rejected-cases-warning');
    // NT-11c: zelfde betekenis in gewoon Nederlands — geen statuscode en geen "importdefinitie" meer.
    expect(warning).toHaveTextContent('afgewezen behandelgevallen van alle koppelingen van deze beschrijving van het bestand');
    expect(warning).toHaveTextContent('opnieuw geopend');
    expect(warning).toHaveTextContent('bewust aanvaard gevolg');
    expect(warning.textContent).not.toContain('REJECTED');
    expect(warning.textContent).not.toContain('importdefinitie');
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
