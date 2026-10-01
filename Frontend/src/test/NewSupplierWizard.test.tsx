/**
 * NT-6 — stappenplan "Nieuwe leverancier en taak" (`/setup/new`, weg "zelf beschrijven").
 * Beslissingen: `docs/decisions.md` 2026-09-30 (NT-spoor V1-V7, A1, A3).
 *
 * Dekt: normaal scenario (van niets tot taak: vijf POST's met hun bodies), hervatten (bestaande definitie
 * met en zonder versie, koppeling zonder taak), dubbele input (409 op de organisatiecode met "Doorgaan met
 * de bestaande"; dubbele klik = één POST), ongeldige input (400 `DELIMITER_REQUIRED` bij het veld in het
 * Nederlands; ongeldige valuta wordt nooit stil in hoofdletters gezet), gedeeltelijke verwerking (beschrijving
 * bewaard, versie niet: een nieuwe poging stuurt enkel de versie), retry na een netwerkfout (eerst nalezen,
 * geen tweede POST), en rechten (alleen-lezen: knop uit mét reden).
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ActorProvider, type ActorIdentity } from '../actor/ActorContext';
import { TEST_IDENTITY, testIdentityWith } from './testIdentity';
import { NewSupplierWizardPage } from '../features/setup/wizard/NewSupplierWizardPage';
import { SetupOverviewPage } from '../features/setup/SetupOverviewPage';
import { CODE_MESSAGES } from '../errors/codes';
import { expectNoOldWording } from './oldWording';

// --- Nepserver ------------------------------------------------------------------------------------------

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function page(rows: unknown[]) {
  return { content: rows, page: 0, size: 200, totalElements: rows.length, totalPages: 1 };
}

type ServerState = {
  organisations: unknown[];
  definitions: Record<number, unknown[]>;
  revisions: Record<number, unknown[]>;
  links: Record<number, unknown[]>;
  tasks: Record<number, unknown[]>;
};

function emptyState(): ServerState {
  return { organisations: [], definitions: {}, revisions: {}, links: {}, tasks: {} };
}

/** Het revisiedetail (E1) dat de slotstap leest voor de werkelijk bewaarde drempels. */
function revisionDetail(definitionId: number, revisionId: number) {
  return {
    id: revisionId,
    definitionId,
    revisionNumber: 1,
    status: 'DRAFT',
    creationThresholdSharePercent: 1,
    maxCriticalSharePercent: 1,
    maxRejectedSharePercent: null,
    bulkIncidentSharePercent: 1,
    mappings: [],
    filters: [],
    fieldCriticalities: [],
    bookmarks: [],
    bookmarkValues: [],
  };
}

function answerGet(state: ServerState, url: string): Response | undefined {
  const path = url.replace(/^.*\/api\/catalog-import/, '');
  const query = new URL(`http://test${path}`).searchParams;
  let match = /^\/definitions\/(\d+)\/revisions\/(\d+)/.exec(path);
  if (match !== null) {
    return json(revisionDetail(Number(match[1]), Number(match[2])));
  }
  match = /^\/definitions\/(\d+)\/revisions/.exec(path);
  if (match !== null) {
    return json(page(state.revisions[Number(match[1])] ?? []));
  }
  if (path.startsWith('/definitions')) {
    return json(page(state.definitions[Number(query.get('sourceOrganisationId'))] ?? []));
  }
  if (path.startsWith('/source-organisations')) {
    return json(page(state.organisations));
  }
  if (path.startsWith('/import-links')) {
    return json(page(state.links[Number(query.get('importDefinitionId'))] ?? []));
  }
  if (path.startsWith('/tasks')) {
    return json(page(state.tasks[Number(query.get('importLinkId'))] ?? []));
  }
  return undefined;
}

type PostHandler = (path: string, body: Record<string, unknown>) => Response | Promise<Response> | undefined;

/** De standaardantwoorden van de vijf aanmaakpaden. */
const CREATED: PostHandler = (path, body) => {
  if (path === '/setup/source-organisations') {
    return json({ id: 1, code: body.code, name: body.name, type: body.type, active: true }, 201);
  }
  if (path === '/setup/definitions') {
    return json(
      {
        id: 10,
        code: body.code,
        name: body.name,
        usageType: 'OWN_DEFINITION',
        sourceOrganisationId: 1,
        sourceOrganisationCode: body.sourceOrganisationCode,
      },
      201,
    );
  }
  const revision = /^\/setup\/definitions\/(\d+)\/revisions$/.exec(path);
  if (revision !== null) {
    return json(
      {
        id: 100,
        definitionId: Number(revision[1]),
        revisionNumber: 1,
        status: 'DRAFT',
        identityProfileKind: body.identityProfileKind,
        delimiter: body.delimiter,
        hasHeader: body.hasHeader,
        fieldReferenceKind: body.fieldReferenceKind,
        canonicalisationVersion: body.canonicalisationVersion,
        supplierField: body.supplierField,
        supplierGroupField: body.supplierGroupField,
        supplierReferenceField: body.supplierReferenceField,
        discountCodeField: body.discountCodeField,
        basePriceField: body.basePriceField,
        descriptionField: body.descriptionField,
        currencyField: body.currencyField,
        creationThresholdSharePercent: 1,
        maxCriticalSharePercent: 1,
        maxRejectedSharePercent: null,
        bulkIncidentSharePercent: 1,
      },
      201,
    );
  }
  if (path === '/setup/links') {
    return json(
      {
        id: 501,
        code: body.code,
        name: body.name,
        definitionId: body.definitionId,
        supplierCode: body.supplierCode,
        libraryCode: body.libraryCode,
        active: true,
        defaultCurrency: body.defaultCurrency,
      },
      201,
    );
  }
  if (path === '/setup/tasks') {
    return json(
      {
        id: 900,
        linkId: body.linkId,
        name: body.name,
        triggerType: 'MANUAL',
        active: true,
        preventConcurrentRuns: body.preventConcurrentRuns,
      },
      201,
    );
  }
  return undefined;
};

function installServer(state: ServerState, post: PostHandler = CREATED) {
  const mock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = (init?.method ?? 'GET').toUpperCase();
    if (method === 'GET') {
      const answer = answerGet(state, url);
      if (answer === undefined) {
        throw new Error(`Onverwachte URL in test: ${url}`);
      }
      return answer;
    }
    const path = url.replace(/^.*\/api\/catalog-import/, '');
    const body = (init?.body ? JSON.parse(String(init.body)) : {}) as Record<string, unknown>;
    const answer = await post(path, body);
    if (answer === undefined) {
      throw new Error(`Onverwachte POST in test: ${path}`);
    }
    return answer;
  });
  global.fetch = mock as unknown as typeof fetch;
  return mock;
}

type Mock = ReturnType<typeof installServer>;

function posts(mock: Mock): Array<{ path: string; body: Record<string, unknown> }> {
  return mock.mock.calls
    .filter((call) => ((call[1] as RequestInit | undefined)?.method ?? 'GET').toUpperCase() === 'POST')
    .map((call) => ({
      path: String(call[0]).replace(/^.*\/api\/catalog-import/, ''),
      body: JSON.parse(String((call[1] as RequestInit).body)) as Record<string, unknown>,
    }));
}

// --- Renderen en invullen ----------------------------------------------------------------------------------

function renderWizard(path = '/setup/new', identity: ActorIdentity = TEST_IDENTITY) {
  return render(
    <ActorProvider identity={identity}>
      <MemoryRouter initialEntries={[path]}>
        <NewSupplierWizardPage />
      </MemoryRouter>
    </ActorProvider>,
  );
}

function typeInto(label: RegExp, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
}

function radio(name: string, value: string): HTMLInputElement {
  const input = document.querySelector<HTMLInputElement>(`input[name="${name}"][value="${value}"]`);
  expect(input, `keuzerondje ${name}=${value}`).not.toBeNull();
  return input!;
}

function click(name: string | RegExp) {
  fireEvent.click(screen.getByRole('button', { name }));
}

async function fillOrganisationStep() {
  await screen.findByRole('heading', { name: /Stap 1/ });
  typeInto(/^Code/, 'LEV-A');
  typeInto(/^Naam/, 'Leverancier A');
  fireEvent.click(radio('wizard-org-type', 'SUPPLIER'));
}

/** Vult stap 3 in; `definition` = ook de velden van de beschrijving zelf (nog niet bewaard). */
function fillDescriptionStep(definition: boolean) {
  if (definition) {
    typeInto(/^Code/, 'LEV-A-CSV');
    typeInto(/^Naam/, 'Prijslijst Leverancier A');
  }
  fireEvent.change(screen.getByLabelText(/^Scheidingsteken/), { target: { value: ';' } });
  fireEvent.click(radio('wizard-identityProfileKind', 'THREE_PART'));
  typeInto(/^Kolom leverancier/, 'LEV');
  typeInto(/^Kolom groep/, 'GRP');
  typeInto(/^Kolom referentie/, 'ARTNR');
  typeInto(/^Kolom basisprijs/, 'PRIJS');
}

function fillLinkStep() {
  typeInto(/^Code/, 'LNK-A');
  typeInto(/^Naam/, 'Koppeling A');
  typeInto(/^Doelbibliotheek/, 'LIB1');
}

const EXISTING_ORG = { id: 1, code: 'LEV-A', name: 'Leverancier A', type: 'SUPPLIER', active: true };
const EXISTING_DEFINITION = {
  id: 10,
  code: 'LEV-A-CSV',
  name: 'Prijslijst Leverancier A',
  usageType: 'OWN_DEFINITION',
  sourceOrganisationId: 1,
  sourceOrganisationCode: 'LEV-A',
  activeRevisionId: null,
};
const EXISTING_DRAFT = { id: 100, definitionId: 10, revisionNumber: 1, status: 'DRAFT' };
const EXISTING_LINK = {
  id: 501,
  code: 'LNK-A',
  name: 'Koppeling A',
  supplierCode: 'LEV-A',
  supplierName: 'Leverancier A',
  libraryCode: 'LIB1',
  active: true,
  importDefinitionId: 10,
};

describe('NewSupplierWizardPage (NT-6)', () => {
  const originalFetch = global.fetch;

  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    vi.clearAllMocks();
    global.fetch = originalFetch;
  });

  it('normaal: van niets tot een taak — vijf POSTs met de juiste bodies en een duidelijke volgende stap', async () => {
    const mock = installServer(emptyState());
    renderWizard();

    await fillOrganisationStep();
    click('Opslaan en verder');

    await screen.findByRole('heading', { name: /Stap 2/ });
    // NT-7: het sjabloonpad is een tweede weg in dit scherm zelf (geen verwijzing naar Sjablonen meer).
    expect(screen.getByRole('button', { name: 'Vanuit een sjabloon van deze leverancier of aankoopvereniging' })).toBeEnabled();
    expectNoOldWording(document.body, 'stappenplan stap 2');
    click('Zelf beschrijven');

    await screen.findByRole('heading', { name: /Stap 3/ });
    expectNoOldWording(document.body, 'stappenplan stap 3');
    // Drempels staan er met hun standaardwaarde, ter info.
    expect(screen.getByTestId('wizard-default-thresholds')).toHaveTextContent('Drempel nieuwe artikelen (%)');
    // Herkenningsversie staat onder "Geavanceerd" op 2 (A3).
    expect((screen.getByLabelText(/^Herkenningsversie/) as HTMLSelectElement).value).toBe('2');
    fillDescriptionStep(true);
    click('Opslaan en verder');

    await screen.findByRole('heading', { name: /Stap 4/ });
    expectNoOldWording(document.body, 'stappenplan stap 4');
    // De leverancier van stap 1 is standaard de leverancier van de koppeling.
    expect((screen.getByLabelText(/^Leverancier van de koppeling/) as HTMLSelectElement).value).toBe('LEV-A');
    fillLinkStep();
    click('Opslaan en verder');

    await screen.findByRole('heading', { name: /Stap 5/ });
    expectNoOldWording(document.body, 'stappenplan stap 5');
    expect((screen.getByLabelText(/^Naam van de taak/) as HTMLInputElement).value).toBe(
      'Koppeling A – handmatige levering',
    );
    expect((document.getElementById('wizard-task-prevent-concurrent') as HTMLInputElement).checked).toBe(true);
    click('Taak aanmaken');

    const summary = await screen.findByTestId('wizard-summary');
    expect(summary).toHaveTextContent('Koppeling A – handmatige levering');
    expect(within(summary).getByTestId('wizard-next-step')).toHaveTextContent(
      'Controleer en activeer de conceptversie',
    );
    // NT-10: de slotstap opent het scherm "Controleren" van de koppeling, met de conceptversie gekozen.
    expect(within(summary).getByTestId('wizard-next-step')).toHaveAttribute(
      'href',
      '/setup/links/501/check?definitionId=10&revisionId=100',
    );
    // De drempels in de slotstap komen uit de server.
    expect(await within(summary).findByTestId('wizard-summary-thresholds')).toHaveTextContent('niet ingesteld');
    expectNoOldWording(document.body, 'stappenplan samenvatting');

    const sent = posts(mock);
    expect(sent.map((call) => call.path)).toEqual([
      '/setup/source-organisations',
      '/setup/definitions',
      '/setup/definitions/10/revisions',
      '/setup/links',
      '/setup/tasks',
    ]);
    expect(sent[0]!.body).toEqual({ code: 'LEV-A', name: 'Leverancier A', type: 'SUPPLIER' });
    expect(sent[1]!.body).toEqual({
      sourceOrganisationCode: 'LEV-A',
      code: 'LEV-A-CSV',
      name: 'Prijslijst Leverancier A',
      usageType: 'OWN_DEFINITION',
    });
    expect(sent[2]!.body).toEqual({
      delimiter: ';',
      quoteChar: '"',
      charset: 'UTF-8',
      hasHeader: true,
      headerLineNumber: 1,
      fieldReferenceKind: 'HEADER_NAME',
      expectedColumnCount: null,
      identityProfileKind: 'THREE_PART',
      supplierField: 'LEV',
      supplierGroupField: 'GRP',
      supplierReferenceField: 'ARTNR',
      discountCodeField: null,
      basePriceField: 'PRIJS',
      descriptionField: null,
      currencyField: null,
      canonicalisationVersion: 2,
      changeReason: null,
      createdBy: 'An Beslisser',
    });
    // Geen enkele drempel gaat mee: de server houdt zijn standaardwaarden.
    expect(Object.keys(sent[2]!.body).some((key) => key.endsWith('Percent'))).toBe(false);
    expect(sent[3]!.body).toEqual({
      definitionId: 10,
      code: 'LNK-A',
      name: 'Koppeling A',
      supplierCode: 'LEV-A',
      libraryCode: 'LIB1',
      librarySearchSupplierCode: null,
      defaultCurrency: null,
    });
    expect(sent[4]!.body).toEqual({
      linkId: 501,
      name: 'Koppeling A – handmatige levering',
      preventConcurrentRuns: true,
    });
  });

  it('hervatten: bestaande definitie mét conceptversie opent bij de koppeling; ongeldige valuta wordt nooit aangepast', async () => {
    const state = emptyState();
    state.organisations = [EXISTING_ORG];
    state.definitions[1] = [EXISTING_DEFINITION];
    state.revisions[10] = [EXISTING_DRAFT];
    const mock = installServer(state);
    renderWizard('/setup/new?organisationId=1&definitionId=10');

    await screen.findByRole('heading', { name: /Stap 4/ });
    expect(screen.getByTestId('wizard-done-so-far')).toHaveTextContent('LEV-A-CSV');
    expect(screen.getByTestId('wizard-done-so-far')).toHaveTextContent('versie 1');
    expect(posts(mock)).toHaveLength(0);

    fillLinkStep();
    typeInto(/^Standaardvaluta/, 'eur');
    click('Opslaan en verder');
    expect(await screen.findByText(/precies drie hoofdletters/)).toBeInTheDocument();
    expect(posts(mock)).toHaveLength(0);

    typeInto(/^Standaardvaluta/, 'EUR');
    click('Opslaan en verder');
    await screen.findByRole('heading', { name: /Stap 5/ });
    click('Taak aanmaken');
    await screen.findByTestId('wizard-summary');

    const sent = posts(mock);
    expect(sent.map((call) => call.path)).toEqual(['/setup/links', '/setup/tasks']);
    expect(sent[0]!.body.defaultCurrency).toBe('EUR');
    expect(sent[0]!.body.definitionId).toBe(10);
  });

  it('hervatten: bestaande definitie zonder versie stuurt enkel de versie', async () => {
    const state = emptyState();
    state.organisations = [EXISTING_ORG];
    state.definitions[1] = [EXISTING_DEFINITION];
    const mock = installServer(state);
    renderWizard('/setup/new?organisationId=1&definitionId=10');

    await screen.findByRole('heading', { name: /Stap 3/ });
    expect(screen.getByTestId('wizard-definition-known')).toHaveTextContent('LEV-A-CSV');
    fillDescriptionStep(false);
    click('Opslaan en verder');
    await screen.findByRole('heading', { name: /Stap 4/ });

    expect(posts(mock).map((call) => call.path)).toEqual(['/setup/definitions/10/revisions']);
  });

  it('hervatten: een id dat niet (meer) klopt, wordt gemeld en niet stil vervangen', async () => {
    const state = emptyState();
    state.organisations = [EXISTING_ORG];
    installServer(state);
    renderWizard('/setup/new?organisationId=1&definitionId=77');

    expect(await screen.findByTestId('wizard-resume-problem')).toHaveTextContent(
      'hoort niet bij deze organisatie of bestaat niet (meer)',
    );
    expect(screen.queryByRole('heading', { name: /Stap/ })).not.toBeInTheDocument();
  });

  it('409 op de organisatiecode: Nederlandse melding bij het veld en "Doorgaan met de bestaande" (uitdrukkelijk)', async () => {
    const state = emptyState();
    const mock = installServer(state, (path, body) => {
      if (path === '/setup/source-organisations') {
        // Iemand anders maakte dezelfde code intussen aan.
        state.organisations = [EXISTING_ORG];
        return json({ error: "Source organisation code 'LEV-A' already exists", code: 'SOURCE_ORGANISATION_CODE_IN_USE' }, 409);
      }
      return CREATED(path, body);
    });
    renderWizard();

    await fillOrganisationStep();
    click('Opslaan en verder');

    const codeField = screen.getByLabelText(/^Code/).closest('div')!;
    expect(await within(codeField).findByRole('alert')).toHaveTextContent('Deze code is al in gebruik');
    const existing = await screen.findByTestId('wizard-existing');
    expect(existing).toHaveTextContent('LEV-A');
    // Niets stil overgenomen: we staan nog bij stap 1.
    expect(screen.getByRole('heading', { name: /Stap 1/ })).toBeInTheDocument();
    // De ruwe code staat niet in de gewone tekst, enkel in de technische details.
    expect(within(codeField).getByRole('alert').textContent).not.toContain('SOURCE_ORGANISATION_CODE_IN_USE');

    fireEvent.click(within(existing).getByRole('button', { name: 'Doorgaan met de bestaande' }));
    await screen.findByRole('heading', { name: /Stap 2/ });
    expect(screen.getByTestId('wizard-notice')).toHaveTextContent('bestaande organisatie LEV-A');
    expect(posts(mock)).toHaveLength(1);
  });

  it('400 DELIMITER_REQUIRED staat bij het scheidingsteken in het Nederlands; een nieuwe poging stuurt enkel de versie', async () => {
    const state = emptyState();
    state.organisations = [EXISTING_ORG];
    let revisionAttempts = 0;
    const mock = installServer(state, (path, body) => {
      if (path === '/setup/definitions/10/revisions') {
        revisionAttempts += 1;
        if (revisionAttempts === 1) {
          return json({ error: 'delimiter must not be blank', code: 'DELIMITER_REQUIRED' }, 400);
        }
      }
      return CREATED(path, body);
    });
    renderWizard('/setup/new?organisationId=1');

    await screen.findByRole('heading', { name: /Stap 2/ });
    click('Zelf beschrijven');
    await screen.findByRole('heading', { name: /Stap 3/ });
    fillDescriptionStep(true);
    click('Opslaan en verder');

    const delimiterField = screen.getByLabelText(/^Scheidingsteken/).closest('div')!;
    const alert = await within(delimiterField).findByRole('alert');
    expect(alert).toHaveTextContent('Kies een scheidingsteken');
    expect(alert.textContent).not.toContain('DELIMITER_REQUIRED');
    // De beschrijving is wel bewaard (gedeeltelijke verwerking) en blijft staan.
    expect(screen.getByTestId('wizard-definition-known')).toHaveTextContent('LEV-A-CSV');

    fireEvent.change(screen.getByLabelText(/^Scheidingsteken/), { target: { value: ',' } });
    click('Opslaan en verder');
    await screen.findByRole('heading', { name: /Stap 4/ });

    expect(posts(mock).map((call) => call.path)).toEqual([
      '/setup/definitions',
      '/setup/definitions/10/revisions',
      '/setup/definitions/10/revisions',
    ]);
    expect(posts(mock)[2]!.body.delimiter).toBe(',');
  });

  it('dubbele klik: één POST zolang het verzoek loopt', async () => {
    let resolvePost: (response: Response) => void = () => {};
    const mock = installServer(emptyState(), (path, body) => {
      if (path === '/setup/source-organisations') {
        return new Promise<Response>((resolve) => {
          resolvePost = resolve;
        });
      }
      return CREATED(path, body);
    });
    renderWizard();
    await fillOrganisationStep();

    click('Opslaan en verder');
    const busy = await screen.findByRole('button', { name: 'Bezig…' });
    expect(busy).toBeDisabled();
    fireEvent.click(busy);
    fireEvent.click(busy);
    expect(posts(mock)).toHaveLength(1);

    await act(async () => {
      resolvePost(json({ id: 1, code: 'LEV-A', name: 'Leverancier A', type: 'SUPPLIER', active: true }, 201));
    });
    await screen.findByRole('heading', { name: /Stap 2/ });
    expect(posts(mock)).toHaveLength(1);
  });

  it('netwerkfout: vóór een nieuwe poging wordt eerst nagelezen; een gevonden taak wordt niet dubbel aangemaakt', async () => {
    const state = emptyState();
    state.organisations = [EXISTING_ORG];
    state.definitions[1] = [EXISTING_DEFINITION];
    state.revisions[10] = [EXISTING_DRAFT];
    state.links[10] = [EXISTING_LINK];
    const mock = installServer(state, (path, body) => {
      if (path === '/setup/tasks') {
        // De server maakte de taak wel aan, maar het antwoord kwam nooit aan.
        state.tasks[501] = [
          {
            id: 900,
            name: body.name,
            active: true,
            triggerType: 'MANUAL',
            preventConcurrentRuns: true,
            importLinkId: 501,
            importLinkCode: 'LNK-A',
            supplierCode: 'LEV-A',
            libraryCode: 'LIB1',
            lastRunStartedAt: null,
            lastRunFinishedAt: null,
            importDefinitionId: 10,
            activeRevisionId: null,
          },
        ];
        throw new TypeError('network');
      }
      return CREATED(path, body);
    });
    renderWizard('/setup/new?organisationId=1&definitionId=10&linkId=501');

    await screen.findByRole('heading', { name: /Stap 5/ });
    click('Taak aanmaken');
    expect(await screen.findByText('Geen verbinding met de server')).toBeInTheDocument();
    expect(screen.getByTestId('wizard-no-answer')).toBeInTheDocument();

    click('Taak aanmaken');
    const existing = await screen.findByTestId('wizard-existing');
    expect(existing).toHaveTextContent('Koppeling A – handmatige levering');
    // Geen tweede POST: eerst nagelezen, en wat gevonden werd, wordt niet stil overgenomen.
    expect(posts(mock)).toHaveLength(1);
    expect(screen.getByRole('heading', { name: /Stap 5/ })).toBeInTheDocument();

    fireEvent.click(within(existing).getByRole('button', { name: 'Doorgaan met de bestaande' }));
    expect(await screen.findByTestId('wizard-summary')).toBeInTheDocument();
    expect(posts(mock)).toHaveLength(1);
  });

  it('ongeldige invoer: verplichte velden en herkenning zonder standaard worden gemeld, niets verstuurd', async () => {
    const state = emptyState();
    state.organisations = [EXISTING_ORG];
    const mock = installServer(state);
    renderWizard('/setup/new?organisationId=1');
    await screen.findByRole('heading', { name: /Stap 2/ });
    click('Zelf beschrijven');
    await screen.findByRole('heading', { name: /Stap 3/ });

    click('Opslaan en verder');
    expect(await screen.findByText('Kies het teken tussen de kolommen.')).toBeInTheDocument();
    expect(screen.getByText(/deze keuze heeft bewust geen standaard/)).toBeInTheDocument();

    // Zonder kopregel kunnen kolommen enkel op positie herkend worden, en een positie is een getal.
    fillDescriptionStep(true);
    fireEvent.click(screen.getByRole('checkbox', { name: /kopregel/ }));
    click('Opslaan en verder');
    expect(await screen.findByText(/enkel op positie herkend/)).toBeInTheDocument();
    fireEvent.click(radio('wizard-fieldReferenceKind', 'COLUMN_INDEX'));
    click('Opslaan en verder');
    expect((await screen.findAllByText(/Vul het volgnummer van de kolom in/)).length).toBeGreaterThan(0);

    expect(posts(mock)).toHaveLength(0);
  });

  it('aankoopvereniging: geen standaardleverancier voor de koppeling', async () => {
    const state = emptyState();
    state.organisations = [{ id: 2, code: 'VROOAM', name: 'VROOAM', type: 'PURCHASING_ASSOCIATION', active: true }];
    state.definitions[2] = [{ ...EXISTING_DEFINITION, sourceOrganisationId: 2, sourceOrganisationCode: 'VROOAM' }];
    state.revisions[10] = [EXISTING_DRAFT];
    const mock = installServer(state);
    renderWizard('/setup/new?organisationId=2&definitionId=10');

    await screen.findByRole('heading', { name: /Stap 4/ });
    expect((screen.getByLabelText(/^Leverancier van de koppeling/) as HTMLSelectElement).value).toBe('');
    fillLinkStep();
    click('Opslaan en verder');
    expect(await screen.findByText('Kies de leverancier van deze koppeling.')).toBeInTheDocument();
    expect(posts(mock)).toHaveLength(0);
  });

  it('alleen-lezen: in Inrichting is "Nieuwe leverancier en taak" uitgeschakeld mét reden', async () => {
    installServer(emptyState());
    render(
      <ActorProvider identity={testIdentityWith('catalogImport.read')}>
        <MemoryRouter initialEntries={['/setup']}>
          <SetupOverviewPage />
        </MemoryRouter>
      </ActorProvider>,
    );
    const button = await screen.findByRole('button', { name: 'Nieuwe leverancier en taak' });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('title', "U heeft het recht 'Beheren' (catalogImport.manage) niet.");
    expect(screen.getByTestId('new-supplier-blocked')).toHaveTextContent("U heeft het recht 'Beheren'");
  });

  it('alleen-lezen: het stappenplan zelf meldt het ontbrekende recht en verstuurt niets', async () => {
    const mock = installServer(emptyState());
    renderWizard('/setup/new', testIdentityWith('catalogImport.read'));
    expect(await screen.findByTestId('wizard-no-manage')).toHaveTextContent("U heeft het recht 'Beheren'");
    await fillOrganisationStep();
    const button = screen.getByRole('button', { name: 'Opslaan en verder' });
    expect(button).toBeDisabled();
    fireEvent.click(button);
    await waitFor(() => expect(posts(mock)).toHaveLength(0));
  });
});

describe('errors/codes.ts — NT-6-teksten', () => {
  const NT6_CODES = [
    'SOURCE_ORGANISATION_CODE_IN_USE',
    'TASK_NAME_IN_USE',
    'CODE_REQUIRED',
    'CODE_TOO_LONG',
    'NAME_REQUIRED',
    'NAME_TOO_LONG',
    'TYPE_REQUIRED',
    'SOURCE_ORGANISATION_CODE_REQUIRED',
    'DELIMITER_REQUIRED',
    'DISCOUNT_CODE_FIELD_REQUIRED',
    'DISCOUNT_CODE_FIELD_INVALID',
    'BASE_PRICE_FIELD_REQUIRED',
    'LIBRARY_CODE_REQUIRED',
    'LINK_CURRENCY_INVALID',
  ];

  it('heeft een Nederlandse tekst zonder ruwe code', () => {
    for (const code of NT6_CODES) {
      const entry = CODE_MESSAGES[code];
      expect(entry, `verwacht een tekst voor ${code}`).toBeDefined();
      for (const text of [entry!.title, entry!.explanation, entry!.whatNow ?? '']) {
        expect(/[A-Z]+_[A-Z_]+/.test(text), `${code}: ruwe code in "${text}"`).toBe(false);
      }
    }
  });
});
