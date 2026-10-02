/**
 * S1-F3 — het schrijfdeel van scherm 1b: `MaterialiseForm` (materialiseren) en
 * `LinkBookmarkValuesSection` (bookmarkwaarde wijzigen), `docs/decisions.md` 2026-09-27
 * "scherm 1a/1b", S1-F3-alinea.
 *
 * Dekt per AGENT.md §2 principe 10: normaal scenario (materialiseren slaagt, waarde wijzigen slaagt),
 * ontbrekende data (`mode` niet gekozen → geen verzoek; verplichte velden), ongeldige/geweigerde input
 * (409 slot bij een open levering), grensgeval "uitdrukkelijk leeg" ≠ "niet wijzigen", en de
 * rechtenspiegel (zonder `MANAGE` zijn beide acties uitgeschakeld mét reden).
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, cleanup, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ActorProvider } from '../actor/ActorContext';
import { TEST_IDENTITY, testIdentityWith } from './testIdentity';
import { PERMISSION_READ } from '../api/types';
import type { BookmarkView, MaterialisedDefinitionView } from '../api/types';
import { MaterialiseForm } from '../features/templates/MaterialiseForm';
import { LinkBookmarkValuesSection } from '../features/templates/LinkBookmarkValuesSection';
import { TemplateDetailPage } from '../features/templates/TemplateDetailPage';
import { AddTaskAfterMaterialise } from '../features/templates/AddTaskAfterMaterialise';

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

function bodyOf(mock: FetchMock, pathPart: string): Record<string, unknown> {
  const call = mock.mock.calls.find((args) => String(args[0]).includes(pathPart));
  expect(call, `verwacht een verzoek naar ${pathPart}`).toBeDefined();
  const init = call![1] as RequestInit;
  return JSON.parse(String(init.body)) as Record<string, unknown>;
}

function postCount(mock: FetchMock): number {
  return mock.mock.calls.filter((args) => {
    const init = args[1] as RequestInit | undefined;
    const method = (init?.method ?? 'GET').toUpperCase();
    return method !== 'GET' && method !== 'HEAD';
  }).length;
}

// --- Vaste testgegevens -----------------------------------------------------------------------------

const DEFINITION_BOOKMARK: BookmarkView = {
  id: 1000,
  revisionId: 100,
  name: 'BESTANDS_PREFIX',
  label: 'Bestandsprefix',
  description: null,
  dataType: 'TEXT',
  valueScope: 'DEFINITION',
  ownerRole: 'admin',
  required: false,
  defaultValue: null,
  allowedValues: null,
  validationPattern: null,
  sortOrder: 1,
  usages: [{ id: 1, placeKind: 'FIELD_MAPPING_FIXED_VALUE', targetHint: 'ARTICLE_CODE' }],
};

const LINK_BOOKMARK: BookmarkView = {
  id: 1001,
  revisionId: 100,
  name: 'BIB_ZOEKLEVERANCIER',
  label: 'Zoekcode bibliotheek',
  description: null,
  dataType: 'TEXT',
  valueScope: 'LINK',
  ownerRole: 'admin',
  required: false,
  defaultValue: null,
  allowedValues: null,
  validationPattern: null,
  sortOrder: 2,
  usages: [],
};

const SHAREABLE: MaterialisedDefinitionView = {
  definitionId: 20,
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

const NOT_SHAREABLE: MaterialisedDefinitionView = {
  ...SHAREABLE,
  definitionId: 21,
  definitionCode: 'DEF-21',
  definitionName: 'Detailleverancier Y',
  definitionRevisionId: 201,
  importLinkCount: 1,
  shareable: false,
  blockingBookmarkName: 'DETAILLEVERANCIER',
};

const MATERIALISATION_RESULT = {
  templateDefinitionId: 1,
  templateRevisionId: 100,
  templateRevisionNumber: 1,
  templateRevisionStatus: 'ACTIVE',
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
    {
      code: 'LINK_SEARCH_SUPPLIER_NOT_DERIVED',
      bookmarkName: null,
      message: 'library_search_supplier_code stays null; it is never derived from the supplier',
    },
  ],
};

const LINK_BOOKMARK_VALUES = {
  importLinkId: 77,
  importLinkCode: 'LNK-77',
  activeRevisionId: 300,
  lockedByOpenBatch: false,
  values: [
    {
      bookmarkName: 'BIB_ZOEKLEVERANCIER',
      label: 'Zoekcode bibliotheek',
      dataType: 'TEXT',
      valueText: 'VROOAM',
      previousValueText: null,
      declared: true,
      required: false,
      filled: true,
      filledAt: '2026-09-27T08:00:00Z',
      filledBy: 'An Beslisser',
      updatedAt: null,
      updatedBy: null,
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
  missingRequiredNames: [],
};

// --- Renderhulpen -----------------------------------------------------------------------------------

function renderMaterialiseForm(options?: {
  identity?: typeof TEST_IDENTITY;
  bookmarks?: BookmarkView[];
  materialisations?: MaterialisedDefinitionView[];
  onMaterialised?: () => void;
}) {
  const onMaterialised = options?.onMaterialised ?? vi.fn();
  render(
    <ActorProvider identity={options?.identity ?? TEST_IDENTITY}>
      <MaterialiseForm
        definitionId={1}
        templateRevisionId={100}
        templateRevisionNumber={1}
        templateRevisionStatus="ACTIVE"
        bookmarks={options?.bookmarks ?? [DEFINITION_BOOKMARK, LINK_BOOKMARK]}
        materialisations={options?.materialisations ?? [SHAREABLE, NOT_SHAREABLE]}
        onMaterialised={onMaterialised}
      />
    </ActorProvider>,
  );
  return { onMaterialised };
}

function renderLinkValues(identity = TEST_IDENTITY) {
  render(
    <ActorProvider identity={identity}>
      <LinkBookmarkValuesSection linkId={77} linkCode="LNK-77" />
    </ActorProvider>,
  );
}

function chooseMode(mode: string) {
  fireEvent.change(screen.getByLabelText(/^Nieuw of hergebruik/), { target: { value: mode } });
}

function fillLinkFields() {
  fireEvent.change(screen.getByLabelText(/^Koppelingscode/), { target: { value: 'LNK-77' } });
  fireEvent.change(screen.getByLabelText(/^Koppelingsnaam/), { target: { value: 'Koppeling 77' } });
  fireEvent.change(screen.getByLabelText(/^Leverancierscode/), { target: { value: 'ORG-9' } });
  fireEvent.change(screen.getByLabelText(/^Bibliotheekcode/), { target: { value: 'BIB1' } });
}

function submitMaterialise() {
  fireEvent.click(screen.getByRole('button', { name: 'Materialiseren' }));
}

describe('MaterialiseForm / LinkBookmarkValuesSection (S1-F3)', () => {
  const originalFetch = global.fetch;

  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    vi.clearAllMocks();
    global.fetch = originalFetch;
  });

  it('S1-F3.1: zonder gekozen modus volgt een cliëntvalidatiefout en géén verzoek', async () => {
    const fetchMock = mockFetch(() => jsonResponse(MATERIALISATION_RESULT, 201));
    renderMaterialiseForm();

    // Geen voorselectie: de sentinel is de beginwaarde.
    expect((screen.getByLabelText(/^Nieuw of hergebruik/) as HTMLSelectElement).value).toBe('');

    submitMaterialise();

    expect(
      await screen.findByText('Kies of dit een nieuwe beschrijving van het bestand wordt of een bestaande hergebruikt.'),
    ).toBeInTheDocument();
    expect(postCount(fetchMock)).toBe(0);
  });

  it('S1-F3.2: NEW_DEFINITION toont definitie-/koppelingvelden en de DEFINITION-scope bookmark', () => {
    mockFetch(() => jsonResponse(MATERIALISATION_RESULT, 201));
    renderMaterialiseForm();

    // Vóór de keuze staat er geen enkel veld van een van beide modi.
    expect(screen.queryByLabelText(/^Code van de beschrijving/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/^Koppelingscode/)).not.toBeInTheDocument();

    chooseMode('NEW_DEFINITION');

    expect(screen.getByLabelText(/^Code van de beschrijving/)).toBeInTheDocument();
    expect(screen.getByLabelText(/^Naam van de beschrijving/)).toBeInTheDocument();
    expect(screen.getByLabelText(/^Koppelingscode/)).toBeInTheDocument();
    expect(screen.getByLabelText(/^Leverancierscode/)).toBeInTheDocument();
    // Beide scopes zijn in beeld, en er is geen keuzelijst voor hergebruik.
    expect(screen.getByTestId('bookmark-field-BESTANDS_PREFIX')).toBeInTheDocument();
    expect(screen.getByTestId('bookmark-field-BIB_ZOEKLEVERANCIER')).toBeInTheDocument();
    expect(screen.queryByLabelText(/^Bestaande beschrijving/)).not.toBeInTheDocument();
  });

  it('S1-F3.3: REUSE_DEFINITION verbergt de definitievelden en DEFINITION-scope, en toont de definitie-keuze', () => {
    mockFetch(() => jsonResponse(MATERIALISATION_RESULT, 201));
    renderMaterialiseForm();

    chooseMode('REUSE_DEFINITION');

    expect(screen.queryByLabelText(/^Code van de beschrijving/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/^Naam van de beschrijving/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/^Wijzigingsreden/)).not.toBeInTheDocument();
    // DEFINITION-scope verdwijnt (spiegel van DEFINITION_SCOPE_VALUE_NOT_ALLOWED_ON_REUSE); LINK blijft.
    expect(screen.queryByTestId('bookmark-field-BESTANDS_PREFIX')).not.toBeInTheDocument();
    expect(screen.getByTestId('bookmark-field-BIB_ZOEKLEVERANCIER')).toBeInTheDocument();

    const select = screen.getByLabelText(/^Bestaande beschrijving/);
    expect(select).toBeInTheDocument();
    const shareableOption = screen.getByRole('option', { name: /DEF-20/ });
    expect(shareableOption).not.toBeDisabled();
    const blockedOption = screen.getByRole('option', { name: /DEF-21/ });
    expect(blockedOption).toBeDisabled();
    // NT-11c: de technische naam staat niet in de tekst (de bookmark is hier niet gedeclareerd), wel in de tooltip.
    expect(blockedOption).toHaveTextContent('niet deelbaar door een invulpunt van dit sjabloon');
    expect(blockedOption.textContent).not.toContain('DETAILLEVERANCIER');
    expect(blockedOption).toHaveAttribute('title', 'Technische naam van het invulpunt: DETAILLEVERANCIER');
  });

  it('S1-F3.4: materialiseren (NEW_DEFINITION) slaagt, verstuurt de juiste body en toont het resultaat', async () => {
    const fetchMock = mockFetch(() => jsonResponse(MATERIALISATION_RESULT, 201));
    const { onMaterialised } = renderMaterialiseForm();

    chooseMode('NEW_DEFINITION');
    fireEvent.change(screen.getByLabelText(/^Code van de beschrijving/), { target: { value: 'DEF-30' } });
    fireEvent.change(screen.getByLabelText(/^Naam van de beschrijving/), { target: { value: 'Leverancier Z' } });
    fillLinkFields();
    fireEvent.change(screen.getByLabelText(/^Bestandsprefix/), { target: { value: 'VRO_' } });
    submitMaterialise();

    expect(await screen.findByTestId('materialise-result')).toHaveTextContent(
      'Nieuwe beschrijving DEF-30 gematerialiseerd',
    );
    expect(screen.getByTestId('materialise-result')).toHaveTextContent('LNK-77');
    // Het antwoord toont altijd welke sjabloonversie gebruikt is.
    expect(screen.getByTestId('materialise-result')).toHaveTextContent('Gebruikte sjabloonversie: 1 (Actief)');
    // NT-11c: de melding staat in gewoon Nederlands; de code staat in de tooltip.
    expect(screen.getByTestId('materialise-warnings')).toHaveTextContent(
      'Leverancierscode in de bibliotheek is niet ingevuld',
    );
    expect(screen.getByTestId('materialise-warnings').textContent).not.toContain('LINK_SEARCH_SUPPLIER_NOT_DERIVED');

    const body = bodyOf(fetchMock, '/templates/1/materialisations');
    expect(body.mode).toBe('NEW_DEFINITION');
    expect(body.templateRevisionId).toBe(100);
    expect(body.reuseDefinitionId).toBeNull();
    expect(body.definitionCode).toBe('DEF-30');
    expect(body.linkCode).toBe('LNK-77');
    expect(body.supplierOrganisationCode).toBe('ORG-9');
    expect(body.libraryCode).toBe('BIB1');
    // Niet aangeraakt = niet meegestuurd; enkel de ingevulde bookmark gaat mee (R-BMK-03).
    expect(body.bookmarkValues).toEqual([{ name: 'BESTANDS_PREFIX', value: 'VRO_' }]);
    expect(body.materialisedBy).toBe('An Beslisser');
    expect(postCount(fetchMock)).toBe(1);
    expect(onMaterialised).toHaveBeenCalledTimes(1);

    // Dubbele input: materialiseren is bewust niet idempotent, dus gaat het formulier leeg terug naar de
    // beginstand (ook `mode`). Een tweede klik verstuurt daarom niets in plaats van een 409 uit te lokken.
    expect((screen.getByLabelText(/^Nieuw of hergebruik/) as HTMLSelectElement).value).toBe('');
    submitMaterialise();
    expect(
      await screen.findByText('Kies of dit een nieuwe beschrijving van het bestand wordt of een bestaande hergebruikt.'),
    ).toBeInTheDocument();
    expect(postCount(fetchMock)).toBe(1);
  });

  it('S1-F3.5: materialiseren (REUSE_DEFINITION) stuurt geen definitievelden en geen DEFINITION-waarde mee', async () => {
    const fetchMock = mockFetch(() => jsonResponse({ ...MATERIALISATION_RESULT, definitionCreated: false }, 201));
    renderMaterialiseForm();

    chooseMode('REUSE_DEFINITION');
    fireEvent.change(screen.getByLabelText(/^Bestaande beschrijving/), { target: { value: '20' } });
    fillLinkFields();
    fireEvent.change(screen.getByLabelText(/^Zoekcode bibliotheek/), { target: { value: 'VROOAM' } });
    submitMaterialise();

    expect(await screen.findByTestId('materialise-result')).toHaveTextContent('hergebruikt');

    const body = bodyOf(fetchMock, '/templates/1/materialisations');
    expect(body.mode).toBe('REUSE_DEFINITION');
    expect(body.reuseDefinitionId).toBe(20);
    expect(body.definitionCode).toBeNull();
    expect(body.definitionName).toBeNull();
    expect(body.changeReason).toBeNull();
    expect(body.bookmarkValues).toEqual([{ name: 'BIB_ZOEKLEVERANCIER', value: 'VROOAM' }]);
  });

  it('S1-F3.6: een bookmark die een LINK_*-plaats vult, vervangt het requestveld (één bron per waarde)', () => {
    mockFetch(() => jsonResponse(MATERIALISATION_RESULT, 201));
    const supplierBookmark: BookmarkView = {
      ...LINK_BOOKMARK,
      id: 1002,
      name: 'LEVERANCIER',
      usages: [{ id: 2, placeKind: 'LINK_SUPPLIER_ORGANISATION', targetHint: '' }],
    };
    renderMaterialiseForm({ bookmarks: [supplierBookmark] });

    chooseMode('NEW_DEFINITION');

    expect(screen.queryByLabelText(/^Leverancierscode/)).not.toBeInTheDocument();
    expect(screen.getByText(/bestemming: Leverancier van de koppeling/)).toBeInTheDocument();
    expect(screen.getByLabelText(/^Bibliotheekcode/)).toBeInTheDocument();
  });

  it('S1-F3.7: zonder MANAGE is materialiseren uitgeschakeld mét reden en gebeurt er niets bij submit', () => {
    const fetchMock = mockFetch(() => jsonResponse(MATERIALISATION_RESULT, 201));
    renderMaterialiseForm({ identity: testIdentityWith(PERMISSION_READ) });

    const button = screen.getByRole('button', { name: 'Materialiseren' });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('title', "U heeft het recht 'Beheren' (catalogImport.manage) niet.");
    expect(screen.getByTestId('permission-reason-materialise')).toHaveTextContent(
      "U heeft het recht 'Beheren' (catalogImport.manage) niet.",
    );

    fireEvent.submit(screen.getByTestId('materialise-form'));
    expect(postCount(fetchMock)).toBe(0);
  });

  it('S1-F3.8: bookmarkwaarde wijzigen slaagt en verstuurt één PUT met de juiste body', async () => {
    const fetchMock = mockFetch((url, init) => {
      if ((init?.method ?? 'GET') === 'PUT') {
        return jsonResponse({ ...LINK_BOOKMARK_VALUES.values[0], valueText: 'NIEUW' });
      }
      if (url.includes('/links/77/bookmark-values')) {
        return jsonResponse(LINK_BOOKMARK_VALUES);
      }
      throw new Error(`Onverwachte URL in test: ${url}`);
    });
    renderLinkValues();

    // NT-11c: het label staat in beeld, de technische naam in de tooltip.
    const label = await screen.findByText('Zoekcode bibliotheek');
    expect(label).toHaveAttribute('title', 'Technische naam: BIB_ZOEKLEVERANCIER');
    fireEvent.click(screen.getByTestId('edit-BIB_ZOEKLEVERANCIER'));
    fireEvent.change(screen.getByLabelText(/^Nieuwe waarde voor Zoekcode bibliotheek/), {
      target: { value: 'NIEUW' },
    });
    fireEvent.click(screen.getByTestId('save-BIB_ZOEKLEVERANCIER'));

    await waitFor(() => {
      expect(postCount(fetchMock)).toBe(1);
    });
    const putCall = fetchMock.mock.calls.find((args) => (args[1] as RequestInit | undefined)?.method === 'PUT');
    expect(String(putCall![0])).toContain('/links/77/bookmark-values/BIB_ZOEKLEVERANCIER');
    expect(JSON.parse(String((putCall![1] as RequestInit).body))).toEqual({
      value: 'NIEUW',
      updatedBy: 'An Beslisser',
    });
  });

  it('S1-F3.9: "expliciet leegmaken" verstuurt een lege waarde en schakelt het tekstveld uit', async () => {
    const fetchMock = mockFetch((_url, init) => {
      if ((init?.method ?? 'GET') === 'PUT') {
        return jsonResponse({ ...LINK_BOOKMARK_VALUES.values[0], valueText: '', filled: false });
      }
      return jsonResponse(LINK_BOOKMARK_VALUES);
    });
    renderLinkValues();

    await screen.findByText('Zoekcode bibliotheek');
    fireEvent.click(screen.getByTestId('edit-BIB_ZOEKLEVERANCIER'));
    const input = screen.getByLabelText(/^Nieuwe waarde voor Zoekcode bibliotheek/);
    fireEvent.click(screen.getByLabelText(/Expliciet leegmaken/));
    expect(input).toBeDisabled();
    fireEvent.click(screen.getByTestId('save-BIB_ZOEKLEVERANCIER'));

    await waitFor(() => {
      expect(postCount(fetchMock)).toBe(1);
    });
    const putCall = fetchMock.mock.calls.find((args) => (args[1] as RequestInit | undefined)?.method === 'PUT');
    expect(JSON.parse(String((putCall![1] as RequestInit).body)).value).toBe('');
  });

  it('S1-F3.10: 409 LINK_BOOKMARK_LOCKED_BY_OPEN_BATCH staat bij de actie zelf, met de specifieke melding', async () => {
    mockFetch((_url, init) => {
      if ((init?.method ?? 'GET') === 'PUT') {
        return jsonResponse(
          {
            error: 'Import link 77 has an open batch; finish or cancel it first',
            code: 'LINK_BOOKMARK_LOCKED_BY_OPEN_BATCH',
          },
          409,
        );
      }
      return jsonResponse(LINK_BOOKMARK_VALUES);
    });
    renderLinkValues();

    await screen.findByText('Zoekcode bibliotheek');
    fireEvent.click(screen.getByTestId('edit-BIB_ZOEKLEVERANCIER'));
    fireEvent.click(screen.getByTestId('save-BIB_ZOEKLEVERANCIER'));

    const editor = await screen.findByTestId('editor-BIB_ZOEKLEVERANCIER');
    await waitFor(() => {
      expect(within(editor).getByText('Koppeling is vergrendeld door een open levering')).toBeInTheDocument();
    });
    expect(within(editor).getByText(/wacht tot de batch afgerond is/i)).toBeInTheDocument();
    // Het formulier blijft open: de gebruiker ziet welke waarde niet bewaard is.
    expect(screen.getByLabelText(/^Nieuwe waarde voor Zoekcode bibliotheek/)).toBeInTheDocument();
  });

  it('S1-F3.11: een wees (declared = false) is niet bewerkbaar, met reden', async () => {
    mockFetch(() => jsonResponse(LINK_BOOKMARK_VALUES));
    renderLinkValues();

    // Een bookmark zonder declaratie toont een neutrale naam; de technische naam staat in de tooltip.
    const orphanName = await screen.findByText('Invulpunt zonder declaratie');
    expect(orphanName).toHaveAttribute('title', 'Technische naam: OUDE_BOOKMARK');
    const orphanButton = screen.getByTestId('edit-OUDE_BOOKMARK');
    expect(orphanButton).toBeDisabled();
    expect(orphanButton).toHaveAttribute(
      'title',
      'niet meer in gebruik: dit invulpunt staat niet (meer) gedeclareerd op de actieve versie, dus niet aan te passen',
    );
  });

  it('S1-F3.12: zonder MANAGE is "Waarde wijzigen" uitgeschakeld mét reden', async () => {
    mockFetch(() => jsonResponse(LINK_BOOKMARK_VALUES));
    renderLinkValues(testIdentityWith(PERMISSION_READ));

    await screen.findByText('Zoekcode bibliotheek');
    const button = screen.getByTestId('edit-BIB_ZOEKLEVERANCIER');
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('title', "U heeft het recht 'Beheren' (catalogImport.manage) niet.");
    expect(screen.getByTestId('edit-reason-BIB_ZOEKLEVERANCIER')).toHaveTextContent(
      "U heeft het recht 'Beheren' (catalogImport.manage) niet.",
    );
  });

  it('S1-F3.13: het formulier hangt op TemplateDetailPage onder de gekozen, niet-DRAFT revisie', async () => {
    mockFetch((url) => {
      if (url.includes('/templates/1/revisions/100/bookmarks')) {
        return jsonResponse({
          definitionId: 1,
          revisionId: 100,
          bookmarks: [DEFINITION_BOOKMARK, LINK_BOOKMARK],
          problems: [],
        });
      }
      if (url.includes('/templates/1/materialisations')) {
        return jsonResponse({
          content: [SHAREABLE, NOT_SHAREABLE],
          page: 0,
          size: 50,
          totalElements: 2,
          totalPages: 1,
        });
      }
      if (url.includes('/definitions/1/revisions')) {
        return jsonResponse({
          content: [{ id: 100, definitionId: 1, revisionNumber: 1, status: 'ACTIVE' }],
          page: 0,
          size: 50,
          totalElements: 1,
          totalPages: 1,
        });
      }
      throw new Error(`Onverwachte URL in test: ${url}`);
    });

    render(
      <ActorProvider identity={TEST_IDENTITY}>
        <MemoryRouter initialEntries={['/templates/1']}>
          <Routes>
            <Route path="/templates/:definitionId" element={<TemplateDetailPage />} />
          </Routes>
        </MemoryRouter>
      </ActorProvider>,
    );

    fireEvent.click(await screen.findByRole('button', { name: 'Versie 1' }));

    expect(await screen.findByTestId('materialise-form')).toBeInTheDocument();
    // De keuzelijst voor hergebruik komt uit de al geladen materialisatiehistoriek (één verzoek);
    // wachten tot die historiek er staat, anders is de lijst nog leeg.
    await screen.findByText('DEF-21');
    chooseMode('REUSE_DEFINITION');
    expect(screen.getByRole('option', { name: /DEF-21/ })).toBeDisabled();
  });

  // --- NT-7: "Taak toevoegen" na een geslaagde materialisatie op de pagina Sjablonen ----------------------

  function mockTemplateDetailServer() {
    return mockFetch((url, init) => {
      const method = (init?.method ?? 'GET').toUpperCase();
      if (url.includes('/templates/1/revisions/100/bookmarks')) {
        return jsonResponse({ definitionId: 1, revisionId: 100, bookmarks: [DEFINITION_BOOKMARK, LINK_BOOKMARK], problems: [] });
      }
      if (url.includes('/templates/1/materialisations')) {
        if (method === 'POST') {
          return jsonResponse(MATERIALISATION_RESULT, 201);
        }
        return jsonResponse({ content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 });
      }
      if (url.includes('/definitions/1/revisions')) {
        return jsonResponse({
          content: [{ id: 100, definitionId: 1, revisionNumber: 1, status: 'ACTIVE' }],
          page: 0,
          size: 50,
          totalElements: 1,
          totalPages: 1,
        });
      }
      if (/\/templates\?/.test(url) || url.endsWith('/templates')) {
        return jsonResponse({
          content: [{ id: 1, code: 'TPL-1', name: 'Sjabloon', sourceOrganisationId: 5, sourceOrganisationCode: 'ORG-5' }],
          page: 0,
          size: 200,
          totalElements: 1,
          totalPages: 1,
        });
      }
      if (url.includes('/import-links')) {
        return jsonResponse({ content: [], page: 0, size: 200, totalElements: 0, totalPages: 0 });
      }
      if (url.includes('/links/77/bookmark-values')) {
        return jsonResponse(LINK_BOOKMARK_VALUES);
      }
      throw new Error(`Onverwachte URL in test: ${url}`);
    });
  }

  async function materialiseOnTemplatePage(identity = TEST_IDENTITY) {
    render(
      <ActorProvider identity={identity}>
        <MemoryRouter initialEntries={['/templates/1']}>
          <Routes>
            <Route path="/templates/:definitionId" element={<TemplateDetailPage />} />
          </Routes>
        </MemoryRouter>
      </ActorProvider>,
    );
    fireEvent.click(await screen.findByRole('button', { name: 'Versie 1' }));
    await screen.findByTestId('materialise-form');
    chooseMode('NEW_DEFINITION');
    fireEvent.change(screen.getByLabelText(/^Code van de beschrijving/), { target: { value: 'DEF-30' } });
    fireEvent.change(screen.getByLabelText(/^Naam van de beschrijving/), { target: { value: 'Leverancier Z' } });
    fillLinkFields();
    submitMaterialise();
  }

  it('NT-7.5: na het materialiseren is er geen taak en biedt de pagina "Taak toevoegen" naar de taakstap', async () => {
    mockTemplateDetailServer();
    await materialiseOnTemplatePage();

    await screen.findByTestId('add-task-button');
    expect(screen.getByTestId('add-task-after-materialise')).toHaveTextContent('heeft nog geen taak');
    // De organisatie komt uit de sjabloonlijst (sjabloon 1 hoort bij organisatie 5); de definitie en koppeling
    // uit het antwoord van de materialisatie.
    await waitFor(() => {
      expect(screen.getByTestId('add-task-button')).toHaveAttribute(
        'href',
        '/setup/new?organisationId=5&definitionId=30&linkId=77',
      );
    });
    // Geen stale verwijzing vasthouden: zolang de sjabloonlijst laadt is het een uitgeschakelde knop, daarna
    // een link (ander element); de waitFor hierboven haalt het element telkens opnieuw op.
    expect(screen.getByRole('link', { name: 'Taak toevoegen' })).toBeInTheDocument();
  });

  it('NT-7.6: zonder Beheren is "Taak toevoegen" uitgeschakeld mét reden', async () => {
    mockTemplateDetailServer();
    // Zonder Beheren kan er niet gematerialiseerd worden; het blok wordt daarom rechtstreeks gerenderd.
    render(
      <ActorProvider identity={testIdentityWith(PERMISSION_READ)}>
        <MemoryRouter>
          <AddTaskAfterMaterialise templateId={1} definitionId={30} linkId={77} linkCode="LNK-77" />
        </MemoryRouter>
      </ActorProvider>,
    );

    const button = await screen.findByTestId('add-task-button');
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('title', "U heeft het recht 'Beheren' (catalogImport.manage) niet.");
  });
});
