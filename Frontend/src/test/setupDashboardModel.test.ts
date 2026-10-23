/**
 * R1 — het zuivere model van het dashboard Leveranciers (`features/setup/setupDashboardModel.ts`): groeperen,
 * gereedheid, redenen en het samenvoegen van pagina's.
 */
import { describe, expect, it } from 'vitest';
import type { DefinitionRow, ImportLinkRow, PageResult, SourceOrganisationRow, TaskRow } from '../api/types';
import { buildDashboardModel, fetchAllPages, linkIdsToCheck, type LinkCheckResults } from '../features/setup/setupDashboardModel';

function org(id: number, over: Partial<SourceOrganisationRow> = {}): SourceOrganisationRow {
  return { id, code: `ORG-${id}`, name: `Leverancier ${id}`, type: 'SUPPLIER', active: true, ...over };
}
function def(id: number, organisationId: number, over: Partial<DefinitionRow> = {}): DefinitionRow {
  return {
    id,
    code: `DEF-${id}`,
    name: `Definitie ${id}`,
    usageType: 'OWN_DEFINITION',
    sourceOrganisationId: organisationId,
    sourceOrganisationCode: `ORG-${organisationId}`,
    activeRevisionId: 100 + id,
    ...over,
  };
}
function link(id: number, definitionId: number, over: Partial<ImportLinkRow> = {}): ImportLinkRow {
  return {
    id,
    code: `LNK-${id}`,
    name: `Koppeling ${id}`,
    supplierCode: 'SUP',
    supplierName: 'Leverancier',
    libraryCode: 'LIB',
    active: true,
    importDefinitionId: definitionId,
    credentialState: 'NONE',
    ...over,
  };
}
function task(id: number, linkId: number, definitionId: number, over: Partial<TaskRow> = {}): TaskRow {
  return {
    id,
    name: `Taak ${id}`,
    active: true,
    triggerType: 'MANUAL',
    preventConcurrentRuns: true,
    importLinkId: linkId,
    importLinkCode: `LNK-${linkId}`,
    supplierCode: 'SUP',
    libraryCode: 'LIB',
    lastRunStartedAt: null,
    lastRunFinishedAt: null,
    importDefinitionId: definitionId,
    activeRevisionId: 100 + definitionId,
    ...over,
  };
}

describe('buildDashboardModel', () => {
  it('leeg: geen leveranciers, alle tellers nul', () => {
    const model = buildDashboardModel({ organisations: [], definitions: [], links: [], tasks: [] });
    expect(model.suppliers).toEqual([]);
    expect(model.reasons).toEqual([]);
    expect(model.totals.organisations).toBe(0);
    expect(model.totals.readyTasks).toBe(0);
  });

  it('groepeert organisatie > definitie > koppeling > taak en is klaar bij een actieve taak met actieve versie', () => {
    const model = buildDashboardModel({
      organisations: [org(1), org(2)],
      definitions: [def(10, 1), def(20, 2)],
      links: [link(501, 10), link(502, 10), link(503, 20)],
      tasks: [task(7, 501, 10), task(8, 503, 20)],
    });
    const first = model.suppliers[0]!;
    expect(first.readiness).toBe('READY');
    expect(first.linkCount).toBe(2);
    expect(first.taskCount).toBe(1);
    expect(first.definitions[0]!.links.map((entry) => entry.link.id)).toEqual([501, 502]);
    expect(first.definitions[0]!.links[0]!.tasks.map((t) => t.id)).toEqual([7]);
    // Het zijspoor (koppeling 502 zonder taak) blokkeert een klare leverancier niet, maar blijft als reden staan.
    expect(first.reasons).toEqual([{ kind: 'LINK_WITHOUT_TASK', organisationId: 1, definitionId: 10, linkId: 502 }]);
    expect(model.suppliers[1]!.readiness).toBe('READY');
    expect(model.totals.readySuppliers).toBe(2);
    expect(model.totals.readyTasks).toBe(2);
  });

  it('taak zonder actieve versie: nog niet klaar, reden "geen actieve versie"', () => {
    const model = buildDashboardModel({
      organisations: [org(1)],
      definitions: [def(10, 1, { activeRevisionId: null })],
      links: [link(501, 10)],
      tasks: [task(7, 501, 10, { activeRevisionId: null })],
    });
    const supplier = model.suppliers[0]!;
    expect(supplier.readiness).toBe('NOT_READY');
    expect(supplier.reasons).toEqual([{ kind: 'NO_ACTIVE_VERSION', organisationId: 1, definitionId: 10 }]);
    expect(model.totals.readyTasks).toBe(0);
    expect(model.totals.reasonCounts.NO_ACTIVE_VERSION).toBe(1);
  });

  it('koppeling zonder taak: nog niet klaar, reden "koppeling zonder taak"', () => {
    const model = buildDashboardModel({
      organisations: [org(1)],
      definitions: [def(10, 1)],
      links: [link(501, 10)],
      tasks: [],
    });
    expect(model.suppliers[0]!.readiness).toBe('NOT_READY');
    expect(model.suppliers[0]!.reasons).toEqual([
      { kind: 'LINK_WITHOUT_TASK', organisationId: 1, definitionId: 10, linkId: 501 },
    ]);
  });

  it('eigen definitie zonder koppeling: nog niet klaar, reden "geen koppeling"', () => {
    const model = buildDashboardModel({ organisations: [org(1)], definitions: [def(10, 1)], links: [], tasks: [] });
    expect(model.suppliers[0]!.readiness).toBe('NOT_READY');
    expect(model.suppliers[0]!.reasons).toEqual([{ kind: 'NO_LINK', organisationId: 1, definitionId: 10 }]);
  });

  it('alleen uitgeschakelde taken: nog niet klaar met een eigen reden (nooit een lege redenlijst)', () => {
    const model = buildDashboardModel({
      organisations: [org(1)],
      definitions: [def(10, 1)],
      links: [link(501, 10)],
      tasks: [task(7, 501, 10, { active: false })],
    });
    expect(model.suppliers[0]!.readiness).toBe('NOT_READY');
    expect(model.suppliers[0]!.reasons).toEqual([
      { kind: 'NO_ACTIVE_TASK', organisationId: 1, definitionId: 10, linkId: 501 },
    ]);
  });

  it('geen definitie: nog niet ingericht; een herbruikbare definitie telt niet mee', () => {
    const model = buildDashboardModel({
      organisations: [org(1), org(2)],
      definitions: [def(30, 2, { usageType: 'REUSABLE_TEMPLATE' })],
      links: [],
      tasks: [],
    });
    expect(model.suppliers[0]!.readiness).toBe('NOT_SET_UP');
    expect(model.suppliers[0]!.reasons).toEqual([{ kind: 'NO_DEFINITION', organisationId: 1 }]);
    expect(model.suppliers[1]!.readiness).toBe('NOT_SET_UP');
    expect(model.suppliers[1]!.definitions).toHaveLength(1);
    expect(model.suppliers[1]!.ownDefinitions).toHaveLength(0);
    expect(model.totals.notSetUpSuppliers).toBe(2);
    expect(model.totals.reusableDefinitions).toBe(1);
  });

  it('inactieve organisatie: inactief, ook met een volledige keten, zonder redenen', () => {
    const model = buildDashboardModel({
      organisations: [org(1, { active: false })],
      definitions: [def(10, 1)],
      links: [link(501, 10)],
      tasks: [task(7, 501, 10)],
    });
    expect(model.suppliers[0]!.readiness).toBe('INACTIVE');
    expect(model.suppliers[0]!.reasons).toEqual([]);
    expect(model.totals.inactiveSuppliers).toBe(1);
  });

  it('telt leveranciers en aankoopverenigingen apart', () => {
    const model = buildDashboardModel({
      organisations: [org(1), org(2, { type: 'PURCHASING_ASSOCIATION' }), org(3, { type: 'PURCHASING_ASSOCIATION' })],
      definitions: [],
      links: [],
      tasks: [],
    });
    expect(model.totals.suppliers).toBe(1);
    expect(model.totals.associations).toBe(2);
  });
});

function pageOf<T>(all: T[], page: number, size: number): PageResult<T> {
  return {
    content: all.slice(page * size, (page + 1) * size),
    page,
    size,
    totalElements: all.length,
    totalPages: Math.ceil(all.length / size),
  };
}

describe('fetchAllPages', () => {
  it('voegt alle pagina\'s samen tot totalElements', async () => {
    const all = Array.from({ length: 5 }, (_, index) => index + 1);
    const calls: number[] = [];
    const result = await fetchAllPages(async (page, size) => {
      calls.push(page);
      return pageOf(all, page, size);
    }, undefined, 2);
    expect(result).toEqual(all);
    expect(calls).toEqual([0, 1, 2]);
  });

  it('één pagina volstaat bij een kleine lijst', async () => {
    const calls: number[] = [];
    const result = await fetchAllPages(async (page, size) => {
      calls.push(page);
      return pageOf([1, 2], page, size);
    });
    expect(result).toEqual([1, 2]);
    expect(calls).toEqual([0]);
  });

  it('een lege lijst geeft een lege uitkomst na één verzoek', async () => {
    const result = await fetchAllPages(async (page, size) => pageOf<number>([], page, size));
    expect(result).toEqual([]);
  });

  it('stopt op een lege pagina, ook als totalElements meer beloofde', async () => {
    let calls = 0;
    const result = await fetchAllPages(async (page, size) => {
      calls += 1;
      return page === 0
        ? { content: [1, 2], page, size, totalElements: 10, totalPages: 5 }
        : { content: [], page, size, totalElements: 10, totalPages: 5 };
    }, undefined, 2);
    expect(result).toEqual([1, 2]);
    expect(calls).toBe(2);
  });

  it('een fout op een latere pagina maakt het geheel mislukt (geen stil afgekapte lijst)', async () => {
    await expect(
      fetchAllPages(async (page, size) => {
        if (page === 1) {
          throw new Error('stuk');
        }
        return pageOf([1, 2, 3, 4], page, size);
      }, undefined, 2),
    ).rejects.toThrow('stuk');
  });
});

describe('buildDashboardModel met de serverchecks van de koppelingen (F4)', () => {
  const input = {
    organisations: [org(1), org(2), org(3, { active: false })],
    definitions: [def(10, 1), def(20, 2), def(30, 3), def(40, 1, { usageType: 'REUSABLE_TEMPLATE' })],
    links: [link(501, 10), link(502, 10), link(503, 20), link(504, 30)],
    tasks: [task(7, 501, 10), task(8, 502, 10), task(9, 503, 20), task(10, 504, 30)],
  };

  it('vraagt enkel koppelingen van eigen definities van actieve organisaties', () => {
    expect(linkIdsToCheck(input)).toEqual([501, 502, 503]);
  });

  it('een leverancier is enkel klaar met minstens een klare taak op een koppeling die de controle doorstaat', () => {
    const checks: LinkCheckResults = new Map([
      [501, { ready: false, problems: [{ code: 'CONFIG_PRICE_FIELD_MISSING' }] }],
      [502, { ready: true, problems: [] }],
      [503, { ready: false, problems: [{ code: 'NO_ACTIVE_REVISION' }, { code: 'LINK_HAS_NO_TASK' }] }],
    ]);
    const model = buildDashboardModel(input, checks);
    // Org 1: een koppeling faalt maar de andere slaagt: nog READY, met de afgekeurde koppeling als reden.
    expect(model.suppliers[0]!.readiness).toBe('READY');
    expect(model.suppliers[0]!.readyTasks.map((t) => t.id)).toEqual([8]);
    expect(model.suppliers[0]!.reasons).toEqual([
      { kind: 'LINK_CHECK_FAILED', organisationId: 1, definitionId: 10, linkId: 501, checkCode: 'CONFIG_PRICE_FIELD_MISSING' },
    ]);
    // Org 2: haar enige koppeling faalt: niet meer READY, eerste probleem als code.
    expect(model.suppliers[1]!.readiness).toBe('NOT_READY');
    expect(model.suppliers[1]!.readyTasks).toEqual([]);
    expect(model.suppliers[1]!.reasons).toEqual([
      { kind: 'LINK_CHECK_FAILED', organisationId: 2, definitionId: 20, linkId: 503, checkCode: 'NO_ACTIVE_REVISION' },
    ]);
    // Org 3 is inactief: geen checks, geen redenen.
    expect(model.suppliers[2]!.readiness).toBe('INACTIVE');
    expect(model.suppliers[2]!.reasons).toEqual([]);
    expect(model.totals.readySuppliers).toBe(1);
    expect(model.totals.reasonCounts.LINK_CHECK_FAILED).toBe(2);
  });

  it('alle koppelingen klaar: hetzelfde model als zonder checks', () => {
    const checks: LinkCheckResults = new Map([501, 502, 503].map((id) => [id, { ready: true, problems: [] }] as const));
    expect(buildDashboardModel(input, checks)).toEqual(buildDashboardModel(input));
  });

  it('een koppeling zonder taak of zonder actieve versie krijgt geen dubbele reden', () => {
    const model = buildDashboardModel(
      {
        organisations: [org(1)],
        definitions: [def(10, 1, { activeRevisionId: null }), def(11, 1)],
        links: [link(501, 10), link(502, 11)],
        tasks: [task(7, 501, 10), task(8, 502, 11, { active: false })],
      },
      new Map([
        [501, { ready: false, problems: [{ code: 'NO_ACTIVE_REVISION' }] }],
        [502, { ready: false, problems: [{ code: 'LINK_HAS_NO_TASK' }] }],
      ]),
    );
    expect(model.suppliers[0]!.reasons.map((reason) => reason.kind)).toEqual(['NO_ACTIVE_VERSION', 'NO_ACTIVE_TASK']);
  });

  it('zonder checks (mislukte tweede fase) blijft het model de heuristiek, ongewijzigd', () => {
    const heuristic = buildDashboardModel(input);
    expect(buildDashboardModel(input, null)).toEqual(heuristic);
    expect(heuristic.suppliers[1]!.readiness).toBe('READY');
    expect(heuristic.totals.reasonCounts.LINK_CHECK_FAILED).toBe(0);
  });
});
