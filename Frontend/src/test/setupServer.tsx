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
  /** F2: legacy-herkomst per revisie-id (`.../revisions/{rev}/legacy-origin`); zonder invoer: niet uit legacy. */
  legacyOrigins?: Record<number, unknown>;
  /**
   * F4: de uitkomst van `GET /import-links/readiness` per koppeling-id. Zonder invoer is de koppeling klaar. Een
   * koppeling die niet in `links` staat geeft 404 `LINK_NOT_FOUND`, zoals de server.
   */
  readiness?: Record<number, { ready: boolean; problems: unknown[] }>;
  /**
   * CD-F: de Prodis-keuzelijsten (`GET /prodis-reference/vat-codes` en `/units-of-measure`). Zonder invoer antwoordt de
   * server 409 `PRODIS_DB_NOT_CONFIGURED`, zodat bestaande tests de lijst als "niet beschikbaar" zien.
   */
  prodisVatCodes?: unknown[];
  prodisUnits?: unknown[];
  /** CD-F: de waarschuwingen die `PUT /setup/links/{id}/creation-defaults` teruggeeft (standaard geen). */
  creationDefaultsWarnings?: unknown[];
  /**
   * EF1: het antwoord van `GET /field-catalog`. Zonder invoer: `defaultFieldCatalog()` (de velden van het woordenboek);
   * `null`: de server geeft 500, zodat de editor op de terugval uit het woordenboek valt.
   */
  fieldCatalog?: unknown | null;
  /**
   * E5a: het antwoord van `GET /definitions/{d}/revisions/{r}/configuration-check` per revisie-id. Zonder invoer: activeerbaar
   * (geen problemen), zodat bestaande tests de conceptbalk ongewijzigd zien.
   */
  configurationChecks?: Record<number, unknown>;
};

/** De herkomst van een versie die niet uit legacy komt (zoals de server ze levert). */
export function notFromLegacy(revisionId: number) {
  return {
    revisionId,
    fromLegacy: false,
    originKind: null,
    originRevisionId: null,
    changedSinceMigration: null,
    receiptId: null,
    interfaceCode: null,
    legacyDefinitionCode: null,
    exportedAt: null,
    appliedAt: null,
    linesAvailable: false,
    lines: [],
  };
}

/** Een legacy-herkomst (F2) met optionele afwijkingen. */
export function legacyOrigin(revisionId: number, over: Record<string, unknown> = {}) {
  return {
    ...notFromLegacy(revisionId),
    fromLegacy: true,
    originKind: 'MIGRATED',
    originRevisionId: revisionId,
    changedSinceMigration: false,
    receiptId: 77,
    interfaceCode: 'INT-A',
    legacyDefinitionCode: 'PRIJSLIJST',
    exportedAt: '2026-09-01T06:00:00Z',
    appliedAt: '2026-09-01T07:00:00Z',
    linesAvailable: true,
    ...over,
  };
}

/** Een legacy-regel (F2) met optionele afwijkingen. */
export function legacyLine(number: number, over: Record<string, unknown> = {}) {
  return {
    number,
    legacyFieldName: `VELD${number}`,
    targetKind: 'NONE',
    targetFieldCode: null,
    revisionField: null,
    column: 0,
    position: 0,
    length: 0,
    prefix: '',
    suffix: '',
    fixedValue: '',
    calculation: '',
    calculationValue: '',
    fill: '',
    xmlElementPath: '',
    includeExclude: '',
    includeExcludeData: '',
    ...over,
  };
}

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

/** Een veld van `GET /field-catalog` (EB1) met de standaardregel van de server: referentie vergrendeld kritiek, prijs kritiek, rest niet. */
export function catalogEntry(code: string, over: Record<string, unknown> = {}) {
  const reference = typeof over.referenceType === 'string';
  const price = typeof over.priceComponentCode === 'string';
  return {
    code,
    name: code,
    dataType: 'TEXT',
    defaultOwner: 'CATALOG_SOURCE',
    identityClass: 'NONE',
    priceComponentCode: null,
    referenceType: null,
    ownerChangeable: true,
    active: true,
    sortOrder: 100,
    defaultCriticality: reference || price ? 'CRITICAL' : 'NON_CRITICAL',
    criticalityChangeable: !reference,
    ...over,
  };
}

/** De zeven revisie-eigen velden van `GET /field-catalog` (zoals `RevisionCriticalityField`). */
export function defaultRevisionFields() {
  return [
    { fieldKey: 'SUPPLIER', identity: true, defaultCriticality: 'CRITICAL', criticalityChangeable: false },
    { fieldKey: 'SUPPLIER_GROUP', identity: true, defaultCriticality: 'CRITICAL', criticalityChangeable: false },
    { fieldKey: 'SUPPLIER_REFERENCE', identity: true, defaultCriticality: 'CRITICAL', criticalityChangeable: false },
    { fieldKey: 'DISCOUNT_CODE', identity: true, defaultCriticality: 'CRITICAL', criticalityChangeable: false },
    { fieldKey: 'BASE_PRICE', identity: false, defaultCriticality: 'CRITICAL', criticalityChangeable: true },
    { fieldKey: 'CURRENCY', identity: false, defaultCriticality: 'CRITICAL', criticalityChangeable: true },
    { fieldKey: 'DESCRIPTION', identity: false, defaultCriticality: 'NON_CRITICAL', criticalityChangeable: true },
  ];
}

/**
 * Het standaardantwoord van `GET /field-catalog` in tests: dezelfde velden als het woordenboek `fieldKey` (zodat de lijsten
 * van de bestaande tests gelijk blijven), ingedeeld zoals de server: prijscomponenten, referentievelden en Prodis-velden.
 */
export function defaultFieldCatalog() {
  const prices = ['AKP_PCT', 'VKP1_PCT', 'VKP2_PCT', 'VKP3_PCT', 'VKP4_PCT', 'VKP5_PCT', 'VKP_GROSS_PCT'];
  const references = ['EAN', 'PIM_ID', 'CAB_ID', 'E_MARK_ARTICLE_REFERENCE', 'E_SUPPLIER', 'SUPPLIER_BARCODE'];
  return {
    fields: [
      catalogEntry('SUPPLIER', { identityClass: 'IDENTITY', sortOrder: 1 }),
      catalogEntry('SUPPLIER_GROUP', { identityClass: 'IDENTITY', sortOrder: 2 }),
      catalogEntry('SUPPLIER_REFERENCE', { identityClass: 'IDENTITY', sortOrder: 3 }),
      catalogEntry('DISCOUNT_CODE', { identityClass: 'IDENTITY', sortOrder: 4 }),
      catalogEntry('BASE_PRICE', { priceComponentCode: 'BASE', defaultOwner: 'PRICE_CONTROL', sortOrder: 5 }),
      catalogEntry('DESCRIPTION', { sortOrder: 6 }),
      catalogEntry('CURRENCY', { sortOrder: 7 }),
      ...prices.map((code, index) =>
        catalogEntry(code, { priceComponentCode: code.replace('_PCT', ''), defaultOwner: 'PRICE_CONTROL', sortOrder: 10 + index }),
      ),
      ...references.map((code, index) =>
        catalogEntry(code, { referenceType: code, defaultOwner: 'CRITICAL_REFERENCE', ownerChangeable: false, sortOrder: 30 + index }),
      ),
      catalogEntry('BRAND', { defaultOwner: 'PRODIS_USER', sortOrder: 60 }),
      catalogEntry('UNIT', { defaultOwner: 'PRODIS_USER', sortOrder: 61 }),
    ],
    revisionFields: defaultRevisionFields(),
  };
}

/** E5a: het antwoord van de configuratiecontrole zonder problemen (zoals de server voor een geldig concept). */
export function activatableCheck(revisionId: number) {
  return {
    revisionId,
    revisionStatus: 'DRAFT',
    activatable: true,
    checks: [{ code: 'READY_DRAFT_ACTIVATABLE', status: 'OK', subject: { type: 'REVISION', id: revisionId }, detail: null }],
  };
}

export function emptySetupState(): SetupState {
  return { organisations: [], definitions: [], links: [], tasks: [], revisions: {}, details: {}, receipts: [] };
}

/** `requests` bevat enkel de schrijfverzoeken (methode, url, body als object) zodat een test de verstuurde body kan nakijken. */
export type ServerCalls = { urls: string[]; requests: { method: string; url: string; body: unknown }[] };

/** Installeert de mock op `global.fetch`; geeft de lijst van aangeroepen urls terug. */
export function installSetupServer(state: SetupState, extra?: (url: string, init?: RequestInit) => Response | null): ServerCalls {
  const calls: ServerCalls = { urls: [], requests: [] };
  global.fetch = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input.toString();
    calls.urls.push(url);
    const custom = extra?.(url, init);
    if (custom !== null && custom !== undefined) {
      return Promise.resolve(custom);
    }
    const [path, query = ''] = url.split('?');
    const params = new URLSearchParams(query);
    if (init?.method === 'PUT') {
      const body = typeof init.body === 'string' ? (JSON.parse(init.body) as Record<string, unknown>) : {};
      calls.requests.push({ method: 'PUT', url, body });
      const linkDefaults = /\/setup\/links\/(\d+)\/creation-defaults$/.exec(path!);
      if (linkDefaults !== null) {
        // Zoals de server: de herkomst wordt handmatig en de audit schuift mee.
        const row = state.links.find((candidate) => (candidate as { id: number }).id === Number(linkDefaults[1])) as
          | Record<string, unknown>
          | undefined;
        if (row === undefined) {
          return Promise.resolve(jsonResponse({ error: 'nope', code: 'LINK_NOT_FOUND' }, 404));
        }
        Object.assign(row, {
          defaultVatCode: body.vatCode ?? null,
          defaultUnitCode: body.unitCode ?? null,
          creationDefaultsOrigin: 'MANUAL',
          creationDefaultsUpdatedAt: '2026-10-23T12:00:00Z',
          creationDefaultsUpdatedBy: 'An Beslisser',
        });
        return Promise.resolve(
          jsonResponse({
            linkId: Number(linkDefaults[1]),
            defaultVatCode: row.defaultVatCode,
            defaultUnitCode: row.defaultUnitCode,
            creationDefaultsOrigin: row.creationDefaultsOrigin,
            creationDefaultsUpdatedAt: row.creationDefaultsUpdatedAt,
            creationDefaultsUpdatedBy: row.creationDefaultsUpdatedBy,
            warnings: state.creationDefaultsWarnings ?? [],
          }),
        );
      }
      const overwrite = /\/setup\/definitions\/(\d+)\/overwrite-creation-defaults$/.exec(path!);
      if (overwrite !== null) {
        const row = state.definitions.find((candidate) => (candidate as { id: number }).id === Number(overwrite[1])) as
          | Record<string, unknown>
          | undefined;
        if (row === undefined) {
          return Promise.resolve(jsonResponse({ error: 'nope', code: 'DEFINITION_NOT_FOUND' }, 404));
        }
        row.overwriteCreationDefaults = body.enabled === true;
        return Promise.resolve(
          jsonResponse({
            definitionId: Number(overwrite[1]),
            overwriteCreationDefaults: row.overwriteCreationDefaults,
            overwriteCreationDefaultsChangedAt: '2026-10-23T12:00:00Z',
            overwriteCreationDefaultsChangedBy: 'An Beslisser',
          }),
        );
      }
    }
    // EF2 (EB2): de kritiek-overrule van een kernrij zetten of terugzetten; het detail volgt de wijziging, zoals de server.
    // (Een PUT is hierboven al in `requests` genoteerd.)
    const criticalityPath = /\/setup\/revisions\/(\d+)\/field-criticality\/([A-Z_]+)$/.exec(path!);
    if (criticalityPath !== null && (init?.method === 'PUT' || init?.method === 'DELETE')) {
      const revisionId = Number(criticalityPath[1]);
      const fieldKey = criticalityPath[2]!;
      const detailRow = state.details[revisionId] as
        | { fieldCriticalities: { revisionId: number; fieldKey: string; criticality: string }[] }
        | undefined;
      if (init.method === 'DELETE') {
        calls.requests.push({ method: 'DELETE', url, body: null });
        if (!(detailRow?.fieldCriticalities.some((row) => row.fieldKey === fieldKey) ?? false)) {
          return Promise.resolve(jsonResponse({ error: 'nope', code: 'FIELD_CRITICALITY_NOT_FOUND' }, 404));
        }
        detailRow!.fieldCriticalities = detailRow!.fieldCriticalities.filter((row) => row.fieldKey !== fieldKey);
        return Promise.resolve(new Response(null, { status: 204 }));
      }
      const putBody = typeof init.body === 'string' ? (JSON.parse(init.body) as { criticality: string }) : { criticality: '' };
      if (detailRow !== undefined) {
        detailRow.fieldCriticalities = [
          ...detailRow.fieldCriticalities.filter((row) => row.fieldKey !== fieldKey),
          { revisionId, fieldKey, criticality: putBody.criticality },
        ];
      }
      return Promise.resolve(jsonResponse({ revisionId, fieldKey, criticality: putBody.criticality }));
    }
    if (path!.endsWith('/field-catalog')) {
      return Promise.resolve(
        state.fieldCatalog === null
          ? jsonResponse({ error: 'kapot', code: 'INTERNAL_ERROR' }, 500)
          : jsonResponse(state.fieldCatalog ?? defaultFieldCatalog()),
      );
    }
    if (path!.endsWith('/prodis-reference/vat-codes')) {
      return Promise.resolve(
        state.prodisVatCodes === undefined
          ? jsonResponse({ error: 'nope', code: 'PRODIS_DB_NOT_CONFIGURED' }, 409)
          : jsonResponse(state.prodisVatCodes),
      );
    }
    if (path!.endsWith('/prodis-reference/units-of-measure')) {
      return Promise.resolve(
        state.prodisUnits === undefined
          ? jsonResponse({ error: 'nope', code: 'PRODIS_DB_NOT_CONFIGURED' }, 409)
          : jsonResponse(state.prodisUnits),
      );
    }
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
    const originMatch = /\/definitions\/(\d+)\/revisions\/(\d+)\/legacy-origin$/.exec(path!);
    if (originMatch !== null) {
      const revisionId = Number(originMatch[2]);
      return Promise.resolve(jsonResponse(state.legacyOrigins?.[revisionId] ?? notFromLegacy(revisionId)));
    }
    const configCheck = /\/definitions\/(\d+)\/revisions\/(\d+)\/configuration-check$/.exec(path!);
    if (configCheck !== null) {
      const revisionId = Number(configCheck[2]);
      return Promise.resolve(jsonResponse(state.configurationChecks?.[revisionId] ?? activatableCheck(revisionId)));
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
    if (path!.endsWith('/import-links/readiness')) {
      const ids = (params.get('linkIds') ?? '').split(',').filter((id) => id !== '').map(Number);
      if (ids.length === 0 || ids.length > 100) {
        return Promise.resolve(jsonResponse({ error: 'nope', code: 'READINESS_LINK_IDS_INVALID' }, 400));
      }
      const known = new Set(state.links.map((row) => (row as { id: number }).id));
      if (ids.some((id) => !known.has(id))) {
        return Promise.resolve(jsonResponse({ error: 'nope', code: 'LINK_NOT_FOUND' }, 404));
      }
      return Promise.resolve(
        jsonResponse(ids.map((linkId) => ({ linkId, ...(state.readiness?.[linkId] ?? { ready: true, problems: [] }) }))),
      );
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
    // Uitgebreide velden (B1): standaard een eigen importdefinitie van een actieve organisatie zonder versienummers.
    activeRevisionNumber: null,
    draftRevisionId: null,
    draftRevisionNumber: null,
    sourceOrganisationActive: true,
    basedOnDefinitionId: null,
    legacyOriginKind: null,
    legacyDefinitionCodes: [],
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
    credentialState: 'NONE',
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
