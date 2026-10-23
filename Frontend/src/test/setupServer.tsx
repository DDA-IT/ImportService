/**
 * Gedeelde testnaad voor het dashboard Leveranciers en het leveranciersdetail (R1): een `fetch`-mock die de lijstendpoints
 * van `/setup` beantwoordt met pagina's uit een eenvoudige toestand (met `page`/`size`/filters zoals de server), en een
 * helper die de echte routes rendert. Zo testen de schermtests ook de routing (tabs, doorverwijzing).
 */
import { vi } from 'vitest';
import { render } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { ActorProvider, type ActorIdentity } from '../actor/ActorContext';
import { routes } from '../routes';
import { TEST_IDENTITY } from './testIdentity';

export type SetupState = {
  organisations: unknown[];
  definitions: unknown[];
  links: unknown[];
  tasks: unknown[];
  revisions: Record<number, unknown[]>;
  details: Record<number, unknown>;
  receipts: unknown[];
  /** R2: gematerialiseerde importdefinities per herbruikbare definitie (`/templates/{id}/materialisations`). */
  materialisations?: Record<number, unknown[]>;
  /** R2: invulpunten per `<definitie>:<versie>` (`/templates/{id}/revisions/{rev}/bookmarks`). */
  bookmarkSets?: Record<string, unknown>;
};

export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function pageOf(all: unknown[], params: URLSearchParams): unknown {
  const size = Number(params.get('size') ?? '50');
  const page = Number(params.get('page') ?? '0');
  return {
    content: all.slice(page * size, (page + 1) * size),
    page,
    size,
    totalElements: all.length,
    totalPages: Math.max(1, Math.ceil(all.length / size)),
  };
}

export function emptySetupState(): SetupState {
  return { organisations: [], definitions: [], links: [], tasks: [], revisions: {}, details: {}, receipts: [] };
}

export type ServerCalls = { urls: string[] };

/** Installeert de mock op `global.fetch`; geeft de lijst van aangeroepen urls terug. */
export function installSetupServer(state: SetupState, extra?: (url: string, init?: RequestInit) => Response | null): ServerCalls {
  const calls: ServerCalls = { urls: [] };
  global.fetch = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input.toString();
    calls.urls.push(url);
    const custom = extra?.(url, init);
    if (custom !== null && custom !== undefined) {
      return Promise.resolve(custom);
    }
    const [path, query = ''] = url.split('?');
    const params = new URLSearchParams(query);
    const materialisations = /\/templates\/(\d+)\/materialisations$/.exec(path!);
    if (materialisations !== null) {
      return Promise.resolve(jsonResponse(pageOf(state.materialisations?.[Number(materialisations[1])] ?? [], params)));
    }
    const bookmarks = /\/templates\/(\d+)\/revisions\/(\d+)\/bookmarks$/.exec(path!);
    if (bookmarks !== null) {
      const set = state.bookmarkSets?.[`${bookmarks[1]}:${bookmarks[2]}`];
      return Promise.resolve(
        set === undefined ? jsonResponse({ error: 'nope', code: 'TEMPLATE_REVISION_NOT_FOUND' }, 404) : jsonResponse(set),
      );
    }
    const detail = /\/definitions\/(\d+)\/revisions\/(\d+)$/.exec(path!);
    if (detail !== null) {
      const body = state.details[Number(detail[2])];
      return Promise.resolve(body === undefined ? jsonResponse({ error: 'nope', code: 'REVISION_NOT_FOUND' }, 404) : jsonResponse(body));
    }
    const revisions = /\/definitions\/(\d+)\/revisions$/.exec(path!);
    if (revisions !== null) {
      return Promise.resolve(jsonResponse(pageOf(state.revisions[Number(revisions[1])] ?? [], params)));
    }
    if (path!.endsWith('/definitions')) {
      const org = params.get('sourceOrganisationId');
      const rows = state.definitions.filter(
        (row) => org === null || String((row as { sourceOrganisationId: number }).sourceOrganisationId) === org,
      );
      return Promise.resolve(jsonResponse(pageOf(rows, params)));
    }
    if (path!.endsWith('/import-links')) {
      const def = params.get('importDefinitionId');
      const rows = state.links.filter(
        (row) => def === null || String((row as { importDefinitionId: number }).importDefinitionId) === def,
      );
      return Promise.resolve(jsonResponse(pageOf(rows, params)));
    }
    if (path!.endsWith('/tasks')) {
      const link = params.get('importLinkId');
      const rows = state.tasks.filter((row) => link === null || String((row as { importLinkId: number }).importLinkId) === link);
      return Promise.resolve(jsonResponse(pageOf(rows, params)));
    }
    if (path!.endsWith('/source-organisations')) {
      return Promise.resolve(jsonResponse(pageOf(state.organisations, params)));
    }
    if (path!.endsWith('/legacy-config/receipts')) {
      return Promise.resolve(jsonResponse(pageOf(state.receipts, params)));
    }
    return Promise.reject(new Error(`Onverwachte URL in test: ${url}`));
  }) as unknown as typeof fetch;
  return calls;
}

/** Rendert de echte routes op een adres (met `ActorProvider`). */
export function renderRoutes(path: string, identity: ActorIdentity = TEST_IDENTITY) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  const view = render(
    <ActorProvider identity={identity}>
      <RouterProvider router={router} />
    </ActorProvider>,
  );
  return { router, ...view };
}

export function organisation(id: number, over: Record<string, unknown> = {}) {
  return { id, code: `ORG-${id}`, name: `Leverancier ${id}`, type: 'SUPPLIER', active: true, ...over };
}

export function definition(id: number, organisationId: number, over: Record<string, unknown> = {}) {
  return {
    id,
    code: `DEF-${id}`,
    name: `Definitie ${id}`,
    usageType: 'OWN_DEFINITION',
    sourceOrganisationId: organisationId,
    sourceOrganisationCode: `ORG-${organisationId}`,
    activeRevisionId: null,
    ...over,
  };
}

export function link(id: number, definitionId: number, over: Record<string, unknown> = {}) {
  return {
    id,
    code: `LNK-${id}`,
    name: `Koppeling ${id}`,
    supplierCode: 'SUP1',
    supplierName: 'Leverancier Een',
    libraryCode: 'LIB1',
    active: true,
    importDefinitionId: definitionId,
    ...over,
  };
}

export function task(id: number, linkId: number, definitionId: number, over: Record<string, unknown> = {}) {
  return {
    id,
    name: `Taak ${id}`,
    active: true,
    triggerType: 'MANUAL',
    preventConcurrentRuns: true,
    importLinkId: linkId,
    importLinkCode: `LNK-${linkId}`,
    supplierCode: 'SUP1',
    libraryCode: 'LIB1',
    lastRunStartedAt: null,
    lastRunFinishedAt: null,
    importDefinitionId: definitionId,
    activeRevisionId: null,
    ...over,
  };
}

/** Een volledig revisiedetail (E1) met optionele afwijkingen. */
export function revisionDetail(id: number, definitionId: number, over: Record<string, unknown> = {}) {
  return {
    id,
    definitionId,
    revisionNumber: 1,
    status: 'ACTIVE',
    basedOnRevisionId: null,
    changeReason: null,
    identityProfileKind: 'THREE_PART',
    identitySupplierField: 'LEV',
    identitySupplierGroupField: 'GRP',
    identitySupplierReferenceField: 'REF',
    identityDiscountCodeField: null,
    structureFormat: 'CSV',
    structureCharset: 'UTF-8',
    structureDelimiter: ';',
    structureQuoteChar: '"',
    structureHasHeader: true,
    structureHeaderLineNumber: 1,
    structureFieldReferenceKind: 'HEADER_NAME',
    structureExpectedColumnCount: null,
    accessDeliverySetKind: 'UNDECLARED',
    recordBasePriceField: 'PRIJS',
    recordDescriptionField: 'OMSCHRIJVING',
    recordCanonicalisationVersion: 2,
    recordCurrencyField: null,
    basePriceZeroAllowed: false,
    basePriceNegativeAllowed: false,
    priceDeviationPercent: 15,
    priceDeviationSeverity: 'WARNING',
    priceDerivationTolerance: 0.01,
    priceAvgShortWindow: 50,
    priceAvgLongWindow: 200,
    priceControlModel: 'DEVIATION',
    creationThresholdAbsolute: 100,
    creationThresholdSharePercent: 1,
    maxCriticalRecords: 0,
    maxRejectedRecords: null,
    maxCriticalSharePercent: 1,
    maxRejectedSharePercent: null,
    bulkIncidentSharePercent: 1,
    accessVersion: 1,
    accessConfigHash: 'a',
    structureVersion: 1,
    structureConfigHash: 'b',
    recordRulesVersion: 1,
    recordRulesConfigHash: 'c',
    compositeConfigHash: 'd',
    createdAt: '2026-09-30T08:00:00Z',
    createdBy: 'An Beslisser',
    createdBySubject: 'test-sub',
    updatedAt: '2026-09-30T08:00:00Z',
    approvedAt: null,
    approvedBy: null,
    approvedBySubject: null,
    mappings: [],
    filters: [],
    fieldCriticalities: [],
    bookmarks: [],
    bookmarkValues: [],
    ...over,
  };
}
