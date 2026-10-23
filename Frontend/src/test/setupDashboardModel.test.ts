/**
 * R1 — het zuivere model van het dashboard Leveranciers (`features/setup/setupDashboardModel.ts`): groeperen,
 * gereedheid, redenen en het samenvoegen van pagina's.
 */
import { describe, expect, it } from 'vitest';
import type { DefinitionRow, ImportLinkRow, PageResult, SourceOrganisationRow, TaskRow } from '../api/types';
import { buildDashboardModel, fetchAllPages } from '../features/setup/setupDashboardModel';

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
