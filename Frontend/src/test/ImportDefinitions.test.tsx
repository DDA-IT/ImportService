/**
 * R2 — het overzicht Importdefinities (`/templates`) en het importdefinitiedetail (`/templates/:definitionId`):
 * alle definities (eigen en herbruikbaar) met kaarten en filters, het gebruik van herbruikbare importdefinities enkel
 * voor de getoonde pagina, het detail met vier tabs (Bestand en velden zonder klik, Versies, Gebruikt door voor herbruikbaar
 * en eigen), de kopacties (stappenplan met `templateId`, opvolger) en het conceptgedrag.
 *
 * Beslissingslog 2026-10-23: "Importdefinitie als schermwoord", "Naamgeving en redesign inrichting: goedgekeurd".
 */

import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { PERMISSION_READ } from '../api/types';
import { wizardHref } from '../features/setup/wizard/wizardTypes';
import {
  buildDefinitionOverview,
  definitionOriginOf,
  definitionStatusOf,
  filterDefinitions,
  NO_FILTERS,
  openDraftNumberOf,
} from '../features/templates/definitionsModel';
import { formatDateTime } from '../format';
import { expectNoOldWording } from './oldWording';
import { testIdentityWith } from './testIdentity';
import {
  definition,
  emptySetupState,
  installSetupServer,
  jsonResponse,
  legacyLine,
  legacyOrigin,
  link,
  organisation,
  renderRoutes,
  revisionDetail,
  type SetupState,
} from './setupServer';

const originalFetch = global.fetch;

afterEach(() => {
  cleanup();
  sessionStorage.clear();
  global.fetch = originalFetch;
});

const REUSABLE = 'REUSABLE_TEMPLATE';

function materialised(over: Record<string, unknown>) {
  return {
    definitionId: 11,
    definitionCode: 'VRM',
    definitionName: 'Vrooam artikelbestand',
    definitionRevisionId: 301,
    definitionRevisionNumber: 1,
    definitionRevisionStatus: 'ACTIVE',
    templateRevisionId: 201,
    templateRevisionNumber: 3,
    templateRevisionStatus: 'ACTIVE',
    importLinkCount: 2,
    shareable: true,
    blockingBookmarkName: null,
    ...over,
  };
}

function bookmark(over: Record<string, unknown>) {
  return {
    id: 1,
    revisionId: 201,
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
    ...over,
  };
}

/**
 * Org 1 (Vrooam) en org 2 (Kramp); vijf importdefinities (herkomst en status uit de uitgebreide `DefinitionRow`, B1):
 *  10 herbruikbaar VRM-SJ (actief v3 met openstaand concept v4, MIGRATED_TEMPLATE) met gebruiker 11; 11 eigen VRM (actief,
 *  MIGRATED_SHARED, gebaseerd op 10); 12 herbruikbaar INTC-SJ (enkel concept v1, MIGRATED_TEMPLATE); 13 eigen KRAMP-EIG (actief,
 *  eigen, organisatie inactief); 14 herbruikbaar STD-CSV (actief, niet uit legacy) met niet-deelbare gebruiker 13.
 */
function state(): SetupState {
  const s = emptySetupState();
  s.organisations = [organisation(1, { code: 'VROOAM', name: 'Vrooam BV' }), organisation(2, { code: 'KRAMP', name: 'Kramp' })];
  s.definitions = [
    definition(10, 1, { code: 'VRM-SJ', name: 'Vrooam artikelbestand (gedeeld)', usageType: REUSABLE, activeRevisionId: 201, activeRevisionNumber: 3, draftRevisionId: 202, draftRevisionNumber: 4, legacyOriginKind: 'MIGRATED_TEMPLATE', legacyDefinitionCodes: ['VRM'] }),
    definition(11, 1, { code: 'VRM', name: 'Vrooam artikelbestand', activeRevisionId: 301, activeRevisionNumber: 1, basedOnDefinitionId: 10, legacyOriginKind: 'MIGRATED_SHARED' }),
    definition(12, 2, { code: 'INTC-SJ', name: 'Inter Cars artikelbestand', usageType: REUSABLE, activeRevisionId: null, draftRevisionId: 401, draftRevisionNumber: 1, legacyOriginKind: 'MIGRATED_TEMPLATE' }),
    definition(13, 2, { code: 'KRAMP-EIG', name: 'Kramp prijslijst', activeRevisionId: 601, activeRevisionNumber: 1, sourceOrganisationActive: false }),
    definition(14, 1, { code: 'STD-CSV', name: 'Standaard CSV prijslijst', usageType: REUSABLE, activeRevisionId: 701, activeRevisionNumber: 1 }),
  ];
  s.links = [
    link(501, 11, { code: 'VROOAM', name: 'Vrooam koppeling', libraryCode: '181' }),
    link(502, 11, { code: 'VROOAM2', name: 'Tweede koppeling', libraryCode: '181' }),
    link(503, 13, { code: 'KRAMP', name: 'Kramp koppeling', libraryCode: '4' }),
  ];
  s.revisions[10] = [
    {
      id: 202,
      definitionId: 10,
      revisionNumber: 4,
      status: 'DRAFT',
      createdAt: '2026-10-23T09:15:00Z',
      createdBy: 'an.peeters',
      approvedAt: null,
      approvedBy: null,
      changeReason: null,
      basedOnRevisionId: 201,
      fromLegacy: false,
    },
    {
      id: 201,
      definitionId: 10,
      revisionNumber: 3,
      status: 'ACTIVE',
      createdAt: '2026-10-20T08:00:00Z',
      createdBy: 'jan.janssens',
      approvedAt: '2026-10-21T10:31:00Z',
      approvedBy: 'an.peeters',
      changeReason: 'Prijskolom gewijzigd na nieuwe leveranciersindeling',
      basedOnRevisionId: 200,
      fromLegacy: false,
    },
    {
      id: 200,
      definitionId: 10,
      revisionNumber: 2,
      status: 'SUPERSEDED',
      createdAt: '2026-09-01T07:00:00Z',
      createdBy: 'overname',
      approvedAt: '2026-09-01T07:05:00Z',
      approvedBy: 'overname',
      changeReason: null,
      basedOnRevisionId: null,
      fromLegacy: true,
    },
  ];
  s.revisions[11] = [{ id: 301, definitionId: 11, revisionNumber: 1, status: 'ACTIVE' }];
  s.revisions[12] = [{ id: 401, definitionId: 12, revisionNumber: 1, status: 'DRAFT' }];
  s.revisions[13] = [{ id: 601, definitionId: 13, revisionNumber: 1, status: 'ACTIVE' }];
  s.revisions[14] = [{ id: 701, definitionId: 14, revisionNumber: 1, status: 'ACTIVE' }];
  s.details[201] = revisionDetail(201, 10, { revisionNumber: 3, status: 'ACTIVE' });
  s.details[202] = revisionDetail(202, 10, { revisionNumber: 4, status: 'DRAFT', recordBasePriceField: 'NIEUWE_PRIJS' });
  s.details[200] = revisionDetail(200, 10, { revisionNumber: 2, status: 'SUPERSEDED' });
  s.details[301] = revisionDetail(301, 11, { revisionNumber: 1, status: 'ACTIVE' });
  s.details[401] = revisionDetail(401, 12, { revisionNumber: 1, status: 'DRAFT' });
  s.details[601] = revisionDetail(601, 13, { revisionNumber: 1, status: 'ACTIVE' });
  s.details[701] = revisionDetail(701, 14, { revisionNumber: 1, status: 'ACTIVE' });
  s.materialisations = {
    10: [materialised({})],
    12: [],
    14: [
      materialised({
        definitionId: 13,
        definitionName: 'Kramp prijslijst',
        importLinkCount: 1,
        shareable: false,
        blockingBookmarkName: 'BIBLIOTHEEK',
        templateRevisionNumber: 1,
      }),
    ],
  };
  s.bookmarkSets = {
    '10:201': {
      definitionId: 10,
      revisionId: 201,
      bookmarks: [
        bookmark({}),
        bookmark({ id: 2, name: 'BIBLIOTHEEK', label: 'Bibliotheek', valueScope: 'LINK', required: false }),
      ],
      problems: [{ code: 'CONFIG_BOOKMARK_WITHOUT_PLACE', bookmarkName: 'BIBLIOTHEEK', message: 'Bookmark has no usage' }],
    },
    '10:202': { definitionId: 10, revisionId: 202, bookmarks: [bookmark({ revisionId: 202 })], problems: [] },
    '14:701': {
      definitionId: 14,
      revisionId: 701,
      bookmarks: [bookmark({ id: 9, revisionId: 701, name: 'BIBLIOTHEEK', label: 'Bibliotheek', valueScope: 'LINK' })],
      problems: [],
    },
  };
  return s;
}

function rowIds(): string[] {
  const body = screen.getByTestId('definitions');
  return within(body)
    .queryAllByTestId(/^definition-row-/)
    .map((row) => row.getAttribute('data-testid')!.replace('definition-row-', ''));
}

async function openOverview(s: SetupState = state(), extra?: Parameters<typeof installSetupServer>[1], identity?: Parameters<typeof renderRoutes>[1]) {
  const calls = installSetupServer(s, extra);
  const view = renderRoutes('/templates', identity);
  await screen.findByTestId('definition-cards');
  return { calls, ...view };
}

async function openDetail(path: string, s: SetupState = state(), extra?: Parameters<typeof installSetupServer>[1], identity?: Parameters<typeof renderRoutes>[1]) {
  const calls = installSetupServer(s, extra);
  const view = renderRoutes(path, identity);
  await screen.findByRole('navigation', { name: 'Onderdelen van de importdefinitie' });
  return { calls, ...view };
}

describe('model: soort, status en herkomst', () => {
  it('de herkomst volgt uit legacyOriginKind: een code op -SJ zonder legacyOriginKind is "Eigen", elke MIGRATED_* is "Uit legacy"', () => {
    const s = state();
    const all = s.definitions as unknown as Parameters<typeof definitionOriginOf>[0][];
    const by = (id: number) => all.find((row) => row.id === id)!;
    expect(definitionOriginOf(by(10))).toBe('LEGACY');
    expect(definitionOriginOf(by(11))).toBe('LEGACY');
    expect(definitionOriginOf(by(12))).toBe('LEGACY');
    expect(definitionOriginOf(by(13))).toBe('OWN');
    expect(definitionOriginOf(by(14))).toBe('OWN');
    expect(definitionOriginOf({ ...by(14), code: 'ABC-SJ', legacyOriginKind: null })).toBe('OWN');
    expect(definitionOriginOf({ ...by(14), code: 'LEGACY-ABC', legacyOriginKind: null })).toBe('OWN');
    for (const kind of ['MIGRATED_TEMPLATE', 'MIGRATED_SHARED', 'MIGRATED_DETACHED'] as const) {
      expect(definitionOriginOf({ ...by(14), code: 'ZONDER-SUFFIX', legacyOriginKind: kind })).toBe('LEGACY');
    }
    // Een oudere server zonder het veld: niet uit legacy.
    const withoutField = { ...by(10) };
    delete withoutField.legacyOriginKind;
    expect(definitionOriginOf(withoutField)).toBe('OWN');
  });

  it('de status: actief (met of zonder openstaand concept), enkel concept, of geen bruikbare versie', () => {
    const all = state().definitions as unknown as Parameters<typeof definitionStatusOf>[0][];
    const by = (id: number) => all.find((row) => row.id === id)!;
    expect(definitionStatusOf(by(10))).toBe('ACTIVE');
    expect(openDraftNumberOf(by(10))).toBe(4);
    expect(definitionStatusOf(by(11))).toBe('ACTIVE');
    expect(openDraftNumberOf(by(11))).toBeNull();
    expect(definitionStatusOf(by(12))).toBe('CONCEPT');
    expect(openDraftNumberOf(by(12))).toBeNull();
    expect(definitionStatusOf({ ...by(12), draftRevisionId: null, draftRevisionNumber: null })).toBe('NO_VERSION');
  });

  it('de filters Soort, Status en Herkomst en de zoekopdracht combineren', () => {
    const s = state();
    const overview = buildDefinitionOverview({
      organisations: s.organisations as never,
      definitions: s.definitions as never,
      links: s.links as never,
    });
    const ids = (filters: Partial<typeof NO_FILTERS>) =>
      filterDefinitions(overview.entries, { ...NO_FILTERS, ...filters }).map((entry) => entry.definition.id);
    expect(ids({})).toEqual([10, 11, 12, 13, 14]);
    expect(ids({ kind: 'OWN' })).toEqual([11, 13]);
    expect(ids({ kind: 'REUSABLE' })).toEqual([10, 12, 14]);
    expect(ids({ status: 'CONCEPT' })).toEqual([12]);
    expect(ids({ status: 'NO_VERSION' })).toEqual([]);
    expect(ids({ status: 'INACTIVE' })).toEqual([13]);
    expect(ids({ origin: 'LEGACY' })).toEqual([10, 11, 12]);
    expect(ids({ kind: 'REUSABLE', origin: 'OWN' })).toEqual([14]);
    expect(ids({ query: ' kramp ' })).toEqual([13]);
    expect(overview.totals).toMatchObject({ all: 5, active: 4, concept: 1, noVersion: 0, reusable: 3, own: 2, activeReusable: 2, activeOwn: 2 });
  });

  it('wizardHref neemt templateId (additief) en laat organisationId weg als die er niet is', () => {
    expect(wizardHref({ organisationId: 1, templateId: 10 })).toBe('/setup/new?organisationId=1&templateId=10');
    expect(wizardHref({ templateId: 10 })).toBe('/setup/new?templateId=10');
    expect(wizardHref({ organisationId: 5, definitionId: 30, linkId: 77 })).toBe('/setup/new?organisationId=5&definitionId=30&linkId=77');
  });
});

describe('overzicht Importdefinities', () => {
  it('toont titel, kruimelpad, uitleg en de vier kaarten met hun tellers', async () => {
    await openOverview();
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Importdefinities');
    expect(screen.getByText('Inrichting')).toBeInTheDocument();
    expect(screen.getByText(/in legacy de importsoort/)).toBeInTheDocument();
    expect(screen.getByTestId('definition-card-count-all')).toHaveTextContent('5');
    expect(screen.getByTestId('definition-card-count-active')).toHaveTextContent('4');
    expect(screen.getByTestId('definition-card-count-concept')).toHaveTextContent('1');
    expect(screen.getByTestId('definition-card-count-reusable')).toHaveTextContent('3');
    expect(screen.getByTestId('definition-card-all')).toHaveTextContent('4 actief, 1 concept');
    expect(screen.getByTestId('definition-card-active')).toHaveTextContent('2 herbruikbaar, 2 eigen');
    expect(screen.getByTestId('definition-card-concept')).toHaveTextContent('nog niet bruikbaar');
  });

  it('het paneel "Concept: nog te activeren" staat open met de conceptdefinities en Openen; de kaart sluit en opent', async () => {
    await openOverview();
    const panel = await screen.findByTestId('definition-card-panel');
    const row = within(panel).getByTestId('concept-row-12');
    expect(row).toHaveTextContent('Inter Cars artikelbestand');
    expect(row).toHaveTextContent('Versie 1 (Concept)');
    expect(within(row).getByRole('link', { name: 'Openen: Inter Cars artikelbestand' })).toHaveAttribute('href', '/templates/12');
    // De andere kaarten hebben geen knop; alleen de conceptkaart toont/verbergt.
    expect(screen.getAllByRole('button', { name: /Verbergen|Toon concepten/ })).toHaveLength(1);
    fireEvent.click(screen.getByRole('button', { name: /Verbergen/ }));
    expect(screen.queryByTestId('definition-card-panel')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Toon concepten/ }));
    expect(screen.getByTestId('definition-card-panel')).toBeInTheDocument();
  });

  it('de lijst toont ALLE importdefinities (eigen en herbruikbaar) met soort, leverancier, status, gebruik en herkomst', async () => {
    await openOverview();
    await waitFor(() => expect(screen.getByTestId('definition-row-10')).toHaveTextContent('1 importdefinitie'));
    expect(rowIds()).toEqual(['10', '11', '12', '13', '14']);
    const reusable = screen.getByTestId('definition-row-10');
    expect(reusable).toHaveTextContent('VRM-SJ');
    expect(reusable).toHaveTextContent('Herbruikbaar');
    expect(reusable).toHaveTextContent('Vrooam BV');
    expect(reusable).toHaveTextContent('Actief');
    expect(reusable).toHaveTextContent('Uit legacy');
    expect(reusable).not.toHaveTextContent('afgeleid');
    expect(reusable).toHaveTextContent('Versie 3');
    expect(reusable).toHaveTextContent('concept v4 open');
    expect(reusable).not.toHaveTextContent('Inactief');
    // Koppelingen van een herbruikbare: som van de gematerialiseerde importdefinities.
    expect(within(reusable).getAllByRole('cell').map((cell) => cell.textContent)).toContain('2');
    const own = screen.getByTestId('definition-row-13');
    expect(own).toHaveTextContent('Eigen');
    expect(own).toHaveTextContent('Kramp');
    expect(own).toHaveTextContent('1');
    // De organisatie van deze importdefinitie is inactief: aparte badge naast de status.
    expect(own).toHaveTextContent('Actief');
    expect(own).toHaveTextContent('Inactief');
    expect(within(own).getAllByText('Eigen').length).toBeGreaterThanOrEqual(2);
    expect(screen.getByTestId('definition-row-12')).toHaveTextContent('Concept');
    expect(screen.getByTestId('definition-row-12')).not.toHaveTextContent('Inactief');
    expect(screen.getByTestId('definition-row-12')).toHaveTextContent('Nog niet gebruikt');
    expect(screen.getByTestId('open-definition-13')).toHaveAttribute('href', '/templates/13');
    expectNoOldWording(document.body, 'overzicht Importdefinities');
  });

  it('laadt het gebruik enkel voor de herbruikbare importdefinities op de getoonde pagina', async () => {
    const { calls } = await openOverview();
    await waitFor(() => expect(screen.getByTestId('definition-row-10')).toHaveTextContent('1 importdefinitie'));
    const asked = calls.urls.filter((url) => url.includes('/materialisations')).map((url) => /templates\/(\d+)\//.exec(url)![1]);
    expect([...new Set(asked)].sort()).toEqual(['10', '12', '14']);
    // Een filter op Eigen toont geen herbruikbare rijen meer: er komt geen verzoek voor eigen importdefinities bij.
    fireEvent.change(screen.getByTestId('definition-filter-kind'), { target: { value: 'OWN' } });
    await waitFor(() => expect(rowIds()).toEqual(['11', '13']));
    const askedAfter = calls.urls.filter((url) => url.includes('/materialisations')).map((url) => /templates\/(\d+)\//.exec(url)![1]);
    expect(askedAfter.every((id) => ['10', '12', '14'].includes(id!))).toBe(true);
  });

  it('filter Soort: Eigen en Herbruikbaar', async () => {
    await openOverview();
    fireEvent.change(screen.getByTestId('definition-filter-kind'), { target: { value: 'OWN' } });
    expect(rowIds()).toEqual(['11', '13']);
    fireEvent.change(screen.getByTestId('definition-filter-kind'), { target: { value: 'REUSABLE' } });
    expect(rowIds()).toEqual(['10', '12', '14']);
    fireEvent.change(screen.getByTestId('definition-filter-kind'), { target: { value: '' } });
    expect(rowIds()).toHaveLength(5);
  });

  it('filter Status, Herkomst en zoeken; een lege uitkomst toont een melding', async () => {
    await openOverview();
    fireEvent.change(screen.getByTestId('definition-filter-status'), { target: { value: 'CONCEPT' } });
    expect(rowIds()).toEqual(['12']);
    fireEvent.change(screen.getByTestId('definition-filter-status'), { target: { value: 'INACTIVE' } });
    expect(rowIds()).toEqual(['13']);
    fireEvent.change(screen.getByTestId('definition-filter-status'), { target: { value: 'NO_VERSION' } });
    expect(screen.getByText(/Geen importdefinities die bij de zoekopdracht en filters passen/)).toBeInTheDocument();
    fireEvent.change(screen.getByTestId('definition-filter-status'), { target: { value: '' } });
    fireEvent.change(screen.getByTestId('definition-filter-origin'), { target: { value: 'LEGACY' } });
    expect(rowIds()).toEqual(['10', '11', '12']);
    fireEvent.change(screen.getByTestId('definition-filter-origin'), { target: { value: 'OWN' } });
    expect(rowIds()).toEqual(['13', '14']);
    fireEvent.change(screen.getByTestId('definition-filter-origin'), { target: { value: '' } });
    fireEvent.change(screen.getByTestId('definition-search'), { target: { value: 'csv' } });
    expect(rowIds()).toEqual(['14']);
    fireEvent.change(screen.getByTestId('definition-search'), { target: { value: 'bestaat niet' } });
    expect(screen.getByText(/Geen importdefinities die bij de zoekopdracht en filters passen/)).toBeInTheDocument();
  });

  it('de lijst bladert: pagina van 25 met een pager', async () => {
    const s = state();
    for (let id = 100; id < 130; id += 1) {
      s.definitions.push(definition(id, 2, { code: `EIG-${id}`, name: `Eigen importdefinitie ${id}`, activeRevisionId: 1 }));
    }
    await openOverview(s);
    expect(rowIds()).toHaveLength(25);
    expect(screen.getByText('1-25 van 35')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Volgende' }));
    expect(rowIds()).toHaveLength(10);
  });

  it('zonder de inrichtings-API-vlag (404 zonder code) toont de lijst "—" en een melding; het scherm blijft werken', async () => {
    await openOverview(state(), (url) => (url.includes('/materialisations') ? new Response(null, { status: 404 }) : null));
    await screen.findByText(/niet beschikbaar op deze omgeving/);
    expect(screen.getByTestId('definition-row-10')).toHaveTextContent('—');
    // De eigen importdefinities tonen hun gebruik uit de bulklijsten.
    expect(screen.getByTestId('definition-row-11')).toHaveTextContent('Vrooam BV');
    expect(rowIds()).toHaveLength(5);
  });

  it('zonder leesrecht staat er een melding en komt er geen verzoek', async () => {
    installSetupServer(state());
    renderRoutes('/templates', testIdentityWith('catalogImport.approve'));
    expect(await screen.findByRole('alert')).toHaveTextContent('Lezen');
  });
});

describe('importdefinitiedetail: kop en tabs', () => {
  it('toont naam, badges Herbruikbaar, Actief en Herkomst, het kruimelpad en vier tabs met aria-current op de actieve', async () => {
    await openDetail('/templates/10');
    const title = screen.getByRole('heading', { level: 1 });
    expect(title).toHaveTextContent('Vrooam artikelbestand (gedeeld)');
    expect(title).toHaveTextContent('Herbruikbaar');
    expect(title).toHaveTextContent('Actief');
    expect(title).toHaveTextContent('Herkomst: Uit legacy');
    expect(title).toHaveTextContent('concept v4 open');
    expect(title).not.toHaveTextContent('Inactief');
    const nav = screen.getByRole('navigation', { name: 'Onderdelen van de importdefinitie' });
    const links = within(nav).getAllByRole('link');
    expect(links.map((l) => l.textContent)).toEqual(['Overzicht', 'Bestand en velden', 'Versies', 'Gebruikt door']);
    expect(links[0]).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: 'Importdefinities' })).toHaveAttribute('href', '/templates');
    expect(screen.queryByTestId('concept-banner')).not.toBeInTheDocument();
    expectNoOldWording(document.body, 'importdefinitiedetail');
  });

  it('een tabblad wisselt via de route en zet aria-current mee', async () => {
    const { router } = await openDetail('/templates/10');
    const nav = screen.getByRole('navigation', { name: 'Onderdelen van de importdefinitie' });
    fireEvent.click(within(nav).getByRole('link', { name: 'Gebruikt door' }));
    await screen.findByTestId('definition-used-by');
    expect(router.state.location.pathname).toBe('/templates/10/used-by');
    expect(within(nav).getByRole('link', { name: 'Gebruikt door' })).toHaveAttribute('aria-current', 'page');
    expect(within(nav).getByRole('link', { name: 'Overzicht' })).not.toHaveAttribute('aria-current');
  });

  it('een onbekende importdefinitie toont een melding met een weg terug', async () => {
    installSetupServer(state());
    renderRoutes('/templates/999');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('bestaat niet');
    expect(within(alert).getByRole('link', { name: 'Importdefinities' })).toHaveAttribute('href', '/templates');
  });

  it('herbruikbaar en actief: "Leverancier toevoegen op basis van deze importdefinitie" opent het stappenplan met templateId', async () => {
    await openDetail('/templates/10');
    const add = screen.getByTestId('add-supplier-button');
    expect(add).toHaveTextContent('Leverancier toevoegen op basis van deze importdefinitie');
    expect(add).toHaveAttribute('href', '/setup/new?organisationId=1&templateId=10');
    // De bestaande opvolgeractie, op de actieve versie.
    expect(screen.getAllByTestId('create-successor-201').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByTestId('create-successor-201')[0]).toBeEnabled();
  });

  it('een eigen importdefinitie heeft geen knop "Leverancier toevoegen"', async () => {
    await openDetail('/templates/13');
    expect(screen.queryByTestId('add-supplier-button')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Eigen');
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Herkomst: Eigen');
  });

  it('een concept van een herbruikbare importdefinitie: knop uitgeschakeld met reden, banner "Volgende stap"', async () => {
    await openDetail('/templates/12');
    const add = screen.getByTestId('add-supplier-button');
    expect(add).toBeDisabled();
    expect(add).toHaveAttribute('title', 'Een concept van een importdefinitie is nog niet bruikbaar. Activeer het eerst.');
    const banner = screen.getByTestId('concept-banner');
    expect(banner).toHaveTextContent('Volgende stap: versie 1 controleren en activeren');
    expect(within(banner).getByRole('link', { name: 'Versie 1 bekijken' })).toHaveAttribute('href', '/templates/12/fields?versie=401');
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Concept');
  });

  it('zonder het recht Beheren is "Leverancier toevoegen" uitgeschakeld met de reden', async () => {
    await openDetail('/templates/10', state(), undefined, testIdentityWith(PERMISSION_READ));
    const add = screen.getByTestId('add-supplier-button');
    expect(add).toBeDisabled();
    expect(add.getAttribute('title')).toContain('Beheren');
  });
});

describe('importdefinitiedetail: Overzicht', () => {
  it('herbruikbaar: stappenbalk, kleine kaarten (versie, versies, invulpunten, gebruikt door, koppelingen), gegevens en bestandsopbouw', async () => {
    await openDetail('/templates/10');
    await waitFor(() => expect(screen.getByTestId('definition-card-usedby')).toHaveTextContent('1'));
    expect(screen.getByTestId('definition-card-active')).toHaveTextContent('Versie 3');
    expect(screen.getByTestId('definition-card-versions')).toHaveTextContent('3');
    expect(screen.getByTestId('definition-card-placeholders')).toHaveTextContent('2');
    expect(screen.getByTestId('definition-card-usedby')).toHaveTextContent('Importdefinities die het gebruiken');
    expect(screen.getByTestId('definition-card-links')).toHaveTextContent('2');
    expect(screen.getByRole('navigation', { name: 'Waar past dit in de keten?' })).toBeInTheDocument();
    const data = screen.getByTestId('definition-data');
    expect(data).toHaveTextContent('VRM-SJ');
    expect(data).toHaveTextContent('Herbruikbare importdefinitie');
    expect(data).toHaveTextContent('Bron');
    expect(data).toHaveTextContent('Vrooam BV');
    expect(data).toHaveTextContent('Uit legacy');
    await screen.findByTestId('read-file');
    expect(screen.getByRole('link', { name: 'Velden bekijken' })).toHaveAttribute('href', '/templates/10/fields?versie=201');
  });

  it('eigen: geen kaart Invulpunten; "Leveranciers die het gebruiken" en het aantal koppelingen', async () => {
    await openDetail('/templates/11');
    expect(screen.queryByTestId('definition-card-placeholders')).not.toBeInTheDocument();
    expect(screen.getByTestId('definition-card-usedby')).toHaveTextContent('Leveranciers die het gebruiken');
    expect(screen.getByTestId('definition-card-usedby')).toHaveTextContent('1');
    expect(screen.getByTestId('definition-card-links')).toHaveTextContent('2');
    expect(screen.getByTestId('definition-data')).toHaveTextContent('Leverancier');
  });

  it('een concept zonder actieve versie toont "Geen" bij Actieve versie en de bestandsopbouw van het concept', async () => {
    await openDetail('/templates/12');
    expect(screen.getByTestId('definition-card-active')).toHaveTextContent('Geen');
    await screen.findByTestId('read-file');
  });

  it('zonder de inrichtings-API-vlag tonen Invulpunten, Gebruikt door en Koppelingen "—"', async () => {
    await openDetail('/templates/10', state(), (url) =>
      url.includes('/templates/') ? new Response(null, { status: 404 }) : null,
    );
    await waitFor(() => expect(screen.getByTestId('definition-card-usedby')).toHaveTextContent('—'));
    expect(screen.getByTestId('definition-card-placeholders')).toHaveTextContent('—');
    expect(screen.getByTestId('definition-card-links')).toHaveTextContent('—');
  });
});

describe('importdefinitiedetail: Bestand en velden', () => {
  it('toont de actieve versie meteen leesbaar, zonder klik, met de invulpunten van die versie', async () => {
    await openDetail('/templates/10/fields');
    expect(await screen.findByTestId('read-file')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: /versie 3 \(actief\)/ })).toBeInTheDocument();
    const placeholders = await screen.findByTestId('definition-placeholders');
    expect(within(placeholders).getByText(/Een invulpunt is een waarde die je pas invult bij het gebruik van de importdefinitie/)).toBeInTheDocument();
    const rows = within(await screen.findByTestId('placeholder-rows')).getAllByRole('row');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('Bestandsprefix');
    expect(rows[0]).toHaveTextContent('Bij de importdefinitie');
    expect(rows[0]).toHaveTextContent('Ja');
    expect(rows[1]).toHaveTextContent('Bibliotheek');
    expect(rows[1]).toHaveTextContent('Per koppeling');
    expect(rows[1]).toHaveTextContent('Nee');
    // De technische naam staat enkel in de tooltip.
    expect(within(rows[0]!).getByText('Bestandsprefix').closest('td')).toHaveAttribute('title', 'Technische naam: BESTANDS_PREFIX');
    // De aandachtspunten zijn gewoon Nederlands; de code enkel in de tooltip.
    const problems = screen.getByTestId('bookmark-problems');
    expect(problems.textContent).not.toContain('CONFIG_BOOKMARK_WITHOUT_PLACE');
    expect(problems.querySelector('li')?.getAttribute('title')).toContain('CONFIG_BOOKMARK_WITHOUT_PLACE');
    expect(screen.queryByTestId('draft-banner')).not.toBeInTheDocument();
    expectNoOldWording(document.body, 'Bestand en velden');
  });

  it('een andere versie kiezen toont die versie (concept met melding) en haar invulpunten', async () => {
    await openDetail('/templates/10/fields');
    await screen.findByTestId('read-file');
    fireEvent.change(screen.getByTestId('version-select'), { target: { value: '202' } });
    expect(await screen.findByRole('heading', { name: /versie 4 \(concept\)/ })).toBeInTheDocument();
    expect(screen.getByTestId('draft-banner')).toBeInTheDocument();
    await waitFor(() => expect(within(screen.getByTestId('placeholder-rows')).getAllByRole('row')).toHaveLength(1));
  });

  it('?versie= kiest die versie (Bekijken); ?bewerken=1 zet Bewerken aan', async () => {
    await openDetail('/templates/10/fields?versie=202');
    expect(await screen.findByRole('heading', { name: /versie 4 \(concept\)/ })).toBeInTheDocument();
    expect(screen.getByTestId('mode-edit')).toHaveAttribute('aria-pressed', 'false');
    cleanup();
    await openDetail('/templates/10/fields?versie=202&bewerken=1');
    await screen.findByRole('heading', { name: /versie 4 \(concept\)/ });
    expect(screen.getByTestId('mode-edit')).toHaveAttribute('aria-pressed', 'true');
  });

  it('een eigen importdefinitie toont geen invulpunten en geen uitleg over herbruikbaar', async () => {
    await openDetail('/templates/13/fields');
    expect(await screen.findByTestId('read-file')).toBeInTheDocument();
    expect(screen.queryByTestId('definition-placeholders')).not.toBeInTheDocument();
  });

  it('zonder de inrichtings-API-vlag staat het leesbeeld er wel; de invulpunten melden dat de functie niet openstaat', async () => {
    await openDetail('/templates/10/fields', state(), (url) =>
      url.includes('/bookmarks') ? new Response(null, { status: 404 }) : null,
    );
    expect(await screen.findByTestId('read-file')).toBeInTheDocument();
    expect(await screen.findByText(/niet beschikbaar op deze omgeving/)).toBeInTheDocument();
  });
});

describe('importdefinitiedetail: Versies', () => {
  it('toont de historiek met Bekijken, Opvolger maken en Bewerken en activeren bij het concept', async () => {
    await openDetail('/templates/10/versions');
    const root = screen.getByTestId('definition-versions');
    expect(within(root).getByTestId('version-row-202')).toHaveTextContent('Versie 4');
    expect(within(root).getByTestId('version-row-201')).toHaveTextContent('Versie 3');
    expect(within(root).getByTestId('version-row-200')).toHaveTextContent('Versie 2');
    expect(within(root).getByTestId('open-revision-202')).toHaveAttribute('href', '/templates/10/fields?versie=202');
    expect(within(root).getByTestId('edit-revision-202')).toHaveAttribute('href', '/templates/10/fields?versie=202&bewerken=1');
    expect(within(root).queryByTestId('edit-revision-201')).not.toBeInTheDocument();
    expect(within(root).getByTestId('create-successor-201')).toBeEnabled();
    // Wanneer en door wie, reden, overname uit legacy en basisversie.
    const active = within(root).getByTestId('version-row-201');
    expect(active).toHaveTextContent(formatDateTime('2026-10-20T08:00:00Z'));
    expect(active).toHaveTextContent('jan.janssens');
    expect(active).toHaveTextContent('goedgekeurd op ' + formatDateTime('2026-10-21T10:31:00Z') + ' door an.peeters');
    expect(active).toHaveTextContent('gebaseerd op versie 2');
    expect(within(active).getByTestId('version-reason-201')).toHaveTextContent('Prijskolom gewijzigd na nieuwe leveranciersindeling');
    expect(active).not.toHaveTextContent('Overname uit legacy');
    const draft = within(root).getByTestId('version-row-202');
    expect(draft).toHaveTextContent('an.peeters');
    expect(draft).toHaveTextContent('gebaseerd op versie 3');
    expect(draft).not.toHaveTextContent('goedgekeurd');
    const legacy = within(root).getByTestId('version-row-200');
    expect(legacy).toHaveTextContent('Overname uit legacy');
    expect(legacy).not.toHaveTextContent('gebaseerd op');
    // Een concept kan zelf geen opvolger krijgen (bestaande poort).
    expect(within(root).getByTestId('create-successor-202')).toBeDisabled();
  });

  it('Bekijken opent Bestand en velden met die versie', async () => {
    const { router } = await openDetail('/templates/10/versions');
    fireEvent.click(screen.getByTestId('open-revision-200'));
    await screen.findByTestId('definition-fields');
    expect(router.state.location.pathname).toBe('/templates/10/fields');
    expect(router.state.location.search).toBe('?versie=200');
    expect(await screen.findByRole('heading', { name: /versie 2 \(vervangen\)/ })).toBeInTheDocument();
  });
});

describe('importdefinitiedetail: Gebruikt door', () => {
  it('herbruikbaar: een kaart per gematerialiseerde importdefinitie met koppelingen en "Naar leverancier"', async () => {
    await openDetail('/templates/10/used-by');
    const card = await screen.findByTestId('used-by-11');
    expect(card).toHaveTextContent('Vrooam artikelbestand');
    expect(card).toHaveTextContent('Gebaseerd op versie 3 van de herbruikbare importdefinitie');
    expect(card).toHaveTextContent('2 koppelingen');
    expect(card).toHaveTextContent('Deelbaar');
    await within(card).findByText('Vrooam koppeling', { exact: false });
    expect(within(card).getByText('Tweede koppeling', { exact: false })).toBeInTheDocument();
    // F4: de servercontrole vervangt de losse "Actief"-badge.
    await waitFor(() => expect(within(card).getAllByText('Klaar')).toHaveLength(2));
    expect(within(card).queryByText('Actief')).not.toBeInTheDocument();
    expect(within(card).getByTestId('to-supplier-11')).toHaveAttribute('href', '/setup/suppliers/1');
    expect(within(card).queryByTestId('not-shareable-11')).not.toBeInTheDocument();
  });

  it('Gebruikt door: een koppeling die de controle niet doorstaat toont "Niet klaar" met het eerste probleem in woorden', async () => {
    const s = state();
    s.readiness = {
      502: {
        ready: false,
        problems: [
          { code: 'NO_ACTIVE_REVISION', status: 'PROBLEM', subject: { type: 'DEFINITION', id: 11 }, detail: 'No active revision' },
          { code: 'LINK_HAS_NO_TASK', status: 'PROBLEM', subject: { type: 'LINK', id: 502 }, detail: null },
        ],
      },
    };
    s.links = [
      link(501, 11, { code: 'VROOAM', name: 'Vrooam koppeling', libraryCode: '181' }),
      link(502, 11, { code: 'VROOAM2', name: 'Tweede koppeling', libraryCode: '181', active: false }),
      link(503, 13, { code: 'KRAMP', name: 'Kramp koppeling', libraryCode: '4' }),
    ];
    await openDetail('/templates/10/used-by', s);
    const card = await screen.findByTestId('used-by-11');
    expect(await within(card).findByTestId('link-readiness-501')).toHaveTextContent(/^Klaar$/);
    expect(within(card).getByTestId('link-readiness-502')).toHaveTextContent('Niet klaar: er is nog geen versie in gebruik');
    // Een niet-actieve koppeling houdt naast de controle haar eigen badge.
    expect(within(card).getAllByText('Niet actief')).toHaveLength(1);
    expect(card).not.toHaveTextContent('NO_ACTIVE_REVISION');
  });

  it('Gebruikt door: mislukt de servercontrole, dan staat er enkel of de koppeling actief is', async () => {
    await openDetail('/templates/10/used-by', state(), (url) =>
      url.includes('/import-links/readiness') ? jsonResponse({ error: 'stuk', code: 'INTERNAL' }, 500) : null,
    );
    const card = await screen.findByTestId('used-by-11');
    await within(card).findByText('Vrooam koppeling', { exact: false });
    await waitFor(() => expect(within(card).getAllByText('Actief')).toHaveLength(2));
    expect(within(card).queryByTestId('link-readiness-501')).not.toBeInTheDocument();
  });

  it('eigen: ook de koppelingen van een eigen importdefinitie tonen de servercontrole', async () => {
    await openDetail('/templates/11/used-by');
    const card = await screen.findByTestId('used-by-own');
    expect(await within(card).findByTestId('link-readiness-501')).toHaveTextContent('Klaar');
  });

  it('een niet-deelbare gebruiker toont een waarschuwing met het invulpunt bij naam; de technische naam staat in de tooltip', async () => {
    await openDetail('/templates/14/used-by');
    const card = await screen.findByTestId('used-by-13');
    const warning = await within(card).findByTestId('not-shareable-13');
    await waitFor(() => expect(warning).toHaveTextContent('Bibliotheek'));
    expect(warning).toHaveTextContent('Delen is niet mogelijk');
    expect(warning).toHaveAttribute('title', expect.stringContaining('BIBLIOTHEEK'));
    expect(warning.textContent).not.toContain('BIBLIOTHEEK');
    expect(card).toHaveTextContent('Niet deelbaar');
    // De leverancier komt uit de gebruikende importdefinitie (org 2), niet uit de herbruikbare (org 1).
    expect(within(card).getByTestId('to-supplier-13')).toHaveAttribute('href', '/setup/suppliers/2');
  });

  it('de waarden van een koppeling openen met de knop bij de koppeling', async () => {
    const { calls } = await openDetail('/templates/10/used-by', state(), (url) =>
      url.includes('/links/501/bookmark-values')
        ? jsonResponse({
            importLinkId: 501,
            importLinkCode: 'VROOAM',
            activeRevisionId: null,
            lockedByOpenBatch: false,
            values: [],
            missingRequiredNames: [],
          })
        : null,
    );
    fireEvent.click(await screen.findByTestId('select-link-501'));
    await waitFor(() => expect(calls.urls.some((url) => url.includes('/links/501/bookmark-values'))).toBe(true));
    expect(screen.getByTestId('select-link-501')).toHaveAttribute('aria-pressed', 'true');
  });

  it('een herbruikbare importdefinitie zonder gebruik toont "Nog door geen enkele leverancier gebruikt"', async () => {
    await openDetail('/templates/12/used-by');
    expect(await screen.findByText('Nog door geen enkele leverancier gebruikt.')).toBeInTheDocument();
  });

  it('zonder de inrichtings-API-vlag staat er een melding in gewoon Nederlands', async () => {
    await openDetail('/templates/10/used-by', state(), (url) =>
      url.includes('/materialisations') ? new Response(null, { status: 404 }) : null,
    );
    expect(await screen.findByText(/niet beschikbaar op deze omgeving/)).toBeInTheDocument();
  });

  it('eigen: de ene leverancier met haar koppelingen, "Naar leverancier" en de herbruikbare importdefinitie waarop ze gebaseerd is', async () => {
    await openDetail('/templates/11/used-by');
    const card = await screen.findByTestId('used-by-own');
    expect(card).toHaveTextContent('Vrooam BV (eigen importdefinitie)');
    expect(within(card).getAllByTestId('definition-links')).toHaveLength(1);
    expect(card).toHaveTextContent('Vrooam koppeling');
    expect(card).toHaveTextContent('Tweede koppeling');
    expect(within(card).getByTestId('to-supplier-own')).toHaveAttribute('href', '/setup/suppliers/1');
    const basedOn = await within(card).findByTestId('based-on-11');
    expect(basedOn).toHaveTextContent('Gebaseerd op herbruikbare importdefinitie');
    expect(within(basedOn).getByRole('link', { name: 'Vrooam artikelbestand (gedeeld)' })).toHaveAttribute('href', '/templates/10');
  });

  it('eigen zonder bekende herbruikbare herkomst toont geen "Gebaseerd op"', async () => {
    await openDetail('/templates/13/used-by');
    await screen.findByTestId('used-by-own');
    // Org 2 heeft INTC-SJ, maar die heeft geen gematerialiseerde importdefinities.
    await waitFor(() => expect(screen.queryByTestId('based-on-13')).not.toBeInTheDocument());
  });
});

describe('geen bruikbare versie en legacy-herkomst', () => {
  function stateWithNoVersion(): SetupState {
    const s = state();
    s.definitions.push(definition(15, 1, { code: 'ABC-SJ', name: 'Zonder versie', activeRevisionId: null }));
    s.revisions[15] = [];
    return s;
  }

  it('geen actieve versie en geen concept: "Geen bruikbare versie", "Eigen" ondanks -SJ, en een eigen filter', async () => {
    await openOverview(stateWithNoVersion());
    const row = screen.getByTestId('definition-row-15');
    expect(row).toHaveTextContent('Geen bruikbare versie');
    expect(row).toHaveTextContent('Eigen');
    expect(row).not.toHaveTextContent('Uit legacy');
    expect(screen.getByTestId('definition-card-all')).toHaveTextContent('4 actief, 1 concept, 1 zonder bruikbare versie');
    fireEvent.change(screen.getByTestId('definition-filter-status'), { target: { value: 'NO_VERSION' } });
    expect(rowIds()).toEqual(['15']);
  });

  it('het detail van een definitie zonder bruikbare versie toont de status, de banner en een uitgeschakelde knop', async () => {
    const s = stateWithNoVersion();
    s.definitions[5] = definition(15, 1, { code: 'ABC-SJ', name: 'Zonder versie', usageType: REUSABLE, activeRevisionId: null });
    await openDetail('/templates/15', s);
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Geen bruikbare versie');
    expect(screen.getByTestId('concept-banner')).toHaveTextContent('een versie maken en activeren');
    expect(screen.getByTestId('add-supplier-button')).toBeDisabled();
  });

  it('de soort legacy-overname staat enkel onder Technische details, met de toelichting per soort', async () => {
    const s = state();
    s.definitions[1] = definition(11, 1, {
      code: 'VRM',
      name: 'Vrooam artikelbestand',
      activeRevisionId: 301,
      activeRevisionNumber: 1,
      legacyOriginKind: 'MIGRATED_DETACHED',
      legacyDefinitionCodes: ['PSIMPDEF-A', 'PSIMPDEF-B'],
    });
    await openDetail('/templates/11', s);
    const details = screen.getByText('Technische details (voor support)').closest('details')!;
    expect(details).toHaveTextContent('MIGRATED_DETACHED');
    expect(details).toHaveTextContent('losgekoppeld van de gedeelde importdefinitie');
    expect(details).toHaveTextContent('PSIMPDEF-A, PSIMPDEF-B');
    const visible = document.body.cloneNode(true) as HTMLElement;
    visible.querySelectorAll('details').forEach((element) => element.remove());
    expect(visible.textContent).not.toContain('MIGRATED_');
    expect(visible.textContent).not.toContain('PSIMPDEF-A');
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Herkomst: Uit legacy');
  });
});

describe('importdefinitiedetail: Technische details', () => {
  it('toont de ruwe ids en de gebruikssoort enkel onder Technische details', async () => {
    await openDetail('/templates/10');
    const details = screen.getByText('Technische details (voor support)').closest('details')!;
    expect(details).toHaveTextContent('REUSABLE_TEMPLATE');
    expect(details).toHaveTextContent('202, 201, 200');
    expect(details).toHaveTextContent('Definitie-id');
    const visible = document.body.cloneNode(true) as HTMLElement;
    visible.querySelectorAll('details').forEach((element) => element.remove());
    expect(visible.textContent).not.toContain('REUSABLE_TEMPLATE');
  });
});

describe('leveranciersdetail: Gebaseerd op herbruikbare importdefinitie', () => {
  it('de tab Versies toont bij een eigen importdefinitie de link naar de herbruikbare waarop ze gebaseerd is', async () => {
    installSetupServer(state());
    renderRoutes('/setup/suppliers/1/versions');
    const basedOn = await screen.findByTestId('based-on-11');
    expect(within(basedOn).getByRole('link', { name: 'Vrooam artikelbestand (gedeeld)' })).toHaveAttribute('href', '/templates/10');
  });

  it('de link komt uit basedOnDefinitionId: er is geen enkel verzoek naar de materialisaties nodig, ook niet zonder inrichtings-API-vlag', async () => {
    const calls = installSetupServer(state(), (url) => (url.includes('/materialisations') ? new Response(null, { status: 404 }) : null));
    renderRoutes('/setup/suppliers/1/versions');
    const basedOn = await screen.findByTestId('based-on-11');
    expect(within(basedOn).getByRole('link', { name: 'Vrooam artikelbestand (gedeeld)' })).toHaveAttribute('href', '/templates/10');
    expect(calls.urls.some((url) => url.includes('/materialisations'))).toBe(false);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('een eigen importdefinitie met basedOnDefinitionId null toont geen "Gebaseerd op"', async () => {
    const s = state();
    s.definitions = s.definitions.map((row) => ((row as { id: number }).id === 11 ? { ...(row as object), basedOnDefinitionId: null } : row));
    const calls = installSetupServer(s);
    renderRoutes('/setup/suppliers/1/versions');
    await screen.findByTestId('supplier-versions');
    expect(screen.queryByTestId('based-on-11')).not.toBeInTheDocument();
    expect(calls.urls.some((url) => url.includes('/materialisations'))).toBe(false);
  });

  it('terugval als de server het veld niet levert: opzoeken via de materialisaties; zonder vlag verdwijnt de link stilletjes', async () => {
    const withoutField = state();
    withoutField.definitions = withoutField.definitions.map((row) => {
      const copy = { ...(row as Record<string, unknown>) };
      delete copy.basedOnDefinitionId;
      return copy as never;
    });
    installSetupServer(withoutField);
    const first = renderRoutes('/setup/suppliers/1/versions');
    const basedOn = await screen.findByTestId('based-on-11');
    expect(within(basedOn).getByRole('link', { name: 'Vrooam artikelbestand (gedeeld)' })).toHaveAttribute('href', '/templates/10');
    first.unmount();
    cleanup();
    installSetupServer(withoutField, (url) => (url.includes('/materialisations') ? new Response(null, { status: 404 }) : null));
    renderRoutes('/setup/suppliers/1/versions');
    await screen.findByTestId('supplier-versions');
    await waitFor(() => expect(screen.queryByTestId('based-on-11')).not.toBeInTheDocument());
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});

describe('importdefinitiedetail: kolom "Uit legacy"', () => {
  it('toont de kolom als de versie uit legacy komt en een sinds de overname gewijzigde versie met banner', async () => {
    const s = state();
    s.details[201] = revisionDetail(201, 10, {
      revisionNumber: 3,
      status: 'ACTIVE',
      mappings: [{ id: 9001, targetFieldCode: 'EAN', sourceReference: 'BARCODE', valueKind: 'SOURCE_FIELD', steps: [], valueMap: [] }],
    });
    s.legacyOrigins = {
      201: legacyOrigin(201, {
        changedSinceMigration: true,
        originKind: 'TEMPLATE_SOURCE',
        lines: [legacyLine(100, { targetKind: 'MAPPING', targetFieldCode: 'EAN', column: 12 })],
      }),
    };
    await openDetail('/templates/10/fields', s);
    expect(await screen.findByTestId('legacy-column-head')).toHaveTextContent('Uit legacy (bij overname)');
    expect(screen.getByTestId('read-field-9001')).toHaveTextContent('regel 100 · kolom 12');
    expect(screen.getByTestId('legacy-changed-banner')).toBeInTheDocument();
    expect(screen.getByTestId('legacy-origin-kind')).toHaveTextContent('uit de herbruikbare importdefinitie');
  });

  it('zonder herkomst uit legacy staat er geen kolom; een fout van het endpoint blokkeert niets', async () => {
    await openDetail('/templates/10/fields');
    expect(await screen.findByTestId('read-file')).toBeInTheDocument();
    expect(screen.queryByTestId('legacy-column-head')).not.toBeInTheDocument();
    cleanup();

    await openDetail('/templates/10/fields', state(), (url) =>
      url.endsWith('/legacy-origin') ? jsonResponse({ error: 'kapot', code: 'INTERNAL' }, 500) : null,
    );
    expect(await screen.findByTestId('legacy-origin-failed')).toBeInTheDocument();
    expect(screen.getByTestId('read-fields')).toBeInTheDocument();
  });
});

describe('importdefinitiedetail: vlag "standaarden overschrijven" (CD-F)', () => {
  const LABEL = 'Standaarden van koppelingen overschrijven bij legacy-overname';
  const WARNING =
    'Bij een overname uit legacy worden btw-code en eenheid van alle koppelingen overschreven, ook manueel ingestelde waarden.';

  function withFlag(definitionId: number, enabled: boolean): SetupState {
    const s = state();
    const row = s.definitions.find((candidate) => (candidate as { id: number }).id === definitionId) as Record<string, unknown>;
    row.overwriteCreationDefaults = enabled;
    return s;
  }

  it('toont de rij in de gegevens met "Uit" als de vlag uit staat of ontbreekt, zonder waarschuwing', async () => {
    await openDetail('/templates/11');
    expect(screen.getByTestId('definition-data')).toHaveTextContent(LABEL);
    expect(screen.getByTestId('definition-overwrite-value')).toHaveTextContent(/^Uit$/);
    expect(screen.queryByTestId('definition-overwrite-warning')).not.toBeInTheDocument();
  });

  it('"Aan" toont de waarschuwing; bij een gedeelde importdefinitie staat het aantal koppelingen erbij', async () => {
    await openDetail('/templates/11', withFlag(11, true));
    expect(screen.getByTestId('definition-overwrite-value')).toHaveTextContent(/^Aan$/);
    expect(screen.getByTestId('definition-overwrite-warning')).toHaveTextContent(WARNING);
    expect(screen.getByTestId('definition-overwrite-warning')).toHaveTextContent('geldt voor alle 2 koppelingen');
  });

  it('met één koppeling staat er geen aantal in de waarschuwing', async () => {
    await openDetail('/templates/13', withFlag(13, true));
    expect(screen.getByTestId('definition-overwrite-warning')).toHaveTextContent(WARNING);
    expect(screen.getByTestId('definition-overwrite-warning')).not.toHaveTextContent('alle 1 koppelingen');
    expect(screen.getByTestId('definition-overwrite-warning')).not.toHaveTextContent('geldt voor alle');
  });

  it('aanzetten vraagt eerst een bevestiging en stuurt dan { enabled: true }', async () => {
    const { calls } = await openDetail('/templates/11');
    fireEvent.click(screen.getByTestId('definition-overwrite-edit'));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByRole('heading', { name: 'Standaarden overschrijven aanzetten?' })).toBeInTheDocument();
    expect(dialog).toHaveTextContent('geldt voor alle 2 koppelingen');
    // Nog niets verstuurd zolang er niet bevestigd is.
    expect(calls.requests).toHaveLength(0);
    fireEvent.click(within(dialog).getByRole('button', { name: 'Aanzetten' }));
    await waitFor(() => expect(screen.getByTestId('definition-overwrite-value')).toHaveTextContent(/^Aan$/));
    expect(calls.requests).toHaveLength(1);
    expect(calls.requests[0]!.url).toContain('/setup/definitions/11/overwrite-creation-defaults');
    expect(calls.requests[0]!.body).toEqual({ enabled: true });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByTestId('definition-overwrite-warning')).toBeInTheDocument();
  });

  it('uitzetten stuurt { enabled: false } na bevestigen', async () => {
    const { calls } = await openDetail('/templates/11', withFlag(11, true));
    fireEvent.click(screen.getByTestId('definition-overwrite-edit'));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByRole('heading', { name: 'Standaarden overschrijven uitzetten?' })).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Uitzetten' }));
    await waitFor(() => expect(screen.getByTestId('definition-overwrite-value')).toHaveTextContent(/^Uit$/));
    expect(calls.requests[0]!.body).toEqual({ enabled: false });
    expect(screen.queryByTestId('definition-overwrite-warning')).not.toBeInTheDocument();
  });

  it('Annuleren in de bevestiging verstuurt niets en laat de vlag staan', async () => {
    const { calls } = await openDetail('/templates/11');
    fireEvent.click(screen.getByTestId('definition-overwrite-edit'));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Annuleren' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(calls.requests).toHaveLength(0);
    expect(screen.getByTestId('definition-overwrite-value')).toHaveTextContent(/^Uit$/);
  });

  it('zonder recht Beheren is Wijzigen uitgeschakeld mét reden; de waarde blijft leesbaar', async () => {
    await openDetail('/templates/11', withFlag(11, true), undefined, testIdentityWith(PERMISSION_READ));
    const edit = screen.getByTestId('definition-overwrite-edit');
    expect(edit).toBeDisabled();
    expect(edit).toHaveAttribute('title', "U heeft het recht 'Beheren' (catalogImport.manage) niet.");
    expect(screen.getByTestId('definition-overwrite-value')).toHaveTextContent(/^Aan$/);
  });

  it('een serverfout staat in de bevestiging; de vlag blijft ongewijzigd', async () => {
    const { calls } = await openDetail('/templates/11', state(), (url, init) =>
      init?.method === 'PUT' && url.includes('/overwrite-creation-defaults')
        ? jsonResponse({ error: 'nope', code: 'DEFINITION_NOT_FOUND' }, 404)
        : null,
    );
    fireEvent.click(screen.getByTestId('definition-overwrite-edit'));
    const dialog = screen.getByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Aanzetten' }));
    expect(await within(dialog).findByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByTestId('definition-overwrite-value')).toHaveTextContent(/^Uit$/);
    expect(calls.requests).toHaveLength(0);
  });

  it('een herbruikbare importdefinitie toont dezelfde rij', async () => {
    await openDetail('/templates/10');
    expect(screen.getByTestId('definition-data')).toHaveTextContent(LABEL);
    expect(screen.getByTestId('definition-overwrite-value')).toHaveTextContent(/^Uit$/);
  });
});
