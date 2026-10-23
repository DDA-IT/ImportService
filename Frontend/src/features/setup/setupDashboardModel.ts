/**
 * Het zuivere model achter het dashboard Leveranciers en het leveranciersdetail (beslissingslog 2026-10-23
 * "Redesign inrichtingsoverzicht /setup"): groepeert de vier bulklijsten (organisaties, definities, koppelingen, taken)
 * client-side tot organisatie > definities > koppelingen > taken en leidt per leverancier de gereedheid af.
 * Geen backend, geen React, geen tekst: enkel gegevens, zodat het model los getest kan worden.
 *
 * Gereedheid van een leverancier:
 * - `INACTIVE`   — de organisatie is niet actief;
 * - `NOT_SET_UP` — geen eigen importdefinitie (herbruikbare definities tellen niet mee: die krijgen nooit een koppeling);
 * - `READY`      — er is een koppeling met een actieve taak waarvan de importdefinitie een actieve versie heeft en (als
 *                  de serverchecks van F4 bekend zijn) die de controle van de server doorstaat;
 * - `NOT_READY`  — de rest.
 *
 * De redenen ({@link NotReadyReason}) staan los van de gereedheid: een actieve leverancier draagt al wat nog ontbreekt
 * (ook een klare leverancier met een zijspoor); een inactieve leverancier draagt er geen.
 */

import type {
  DefinitionRow,
  RevisionRow,
  ImportLinkRow,
  PageResult,
  SourceOrganisationRow,
  TaskRow,
} from '../../api/types.ts';

export type Readiness = 'READY' | 'NOT_READY' | 'NOT_SET_UP' | 'INACTIVE';

export type NotReadyReasonKind =
  /** Een eigen importdefinitie zonder actieve versie. */
  | 'NO_ACTIVE_VERSION'
  /** Een koppeling zonder enige taak. */
  | 'LINK_WITHOUT_TASK'
  /** Een koppeling waarvan alle taken uitgeschakeld zijn (de importdefinitie heeft wel een actieve versie). */
  | 'NO_ACTIVE_TASK'
  /** Een eigen importdefinitie zonder koppeling. */
  | 'NO_LINK'
  /** Een actieve leverancier zonder eigen importdefinitie. */
  | 'NO_DEFINITION'
  /**
   * Een koppeling die er klaar uitziet (actieve versie en een actieve taak) maar de controle van de server (F4,
   * `GET /import-links/readiness`) niet doorstaat. Enkel gezet als de tweede fase van het dashboard slaagde.
   */
  | 'LINK_CHECK_FAILED';

export type NotReadyReason = {
  kind: NotReadyReasonKind;
  organisationId: number;
  definitionId?: number;
  linkId?: number;
  /** Bij `LINK_CHECK_FAILED`: de code van het eerste probleem van de server (voor het Nederlandse woord in de UI). */
  checkCode?: string;
};

/** De uitkomst van de serverchecks per koppeling-id (tweede fase van het dashboard). */
export type LinkCheckResults = ReadonlyMap<number, { ready: boolean; problems: readonly { code: string }[] }>;

export type LinkModel = { link: ImportLinkRow; tasks: TaskRow[] };
export type DefinitionModel = { definition: DefinitionRow; links: LinkModel[] };

export type SupplierModel = {
  organisation: SourceOrganisationRow;
  definitions: DefinitionModel[];
  /** Enkel de eigen definities (geen herbruikbare). */
  ownDefinitions: DefinitionModel[];
  linkCount: number;
  taskCount: number;
  /** Actieve taken waarvan de importdefinitie een actieve versie heeft. */
  readyTasks: TaskRow[];
  readiness: Readiness;
  reasons: NotReadyReason[];
};

export type DashboardTotals = {
  organisations: number;
  suppliers: number;
  associations: number;
  /** Aantal leveranciers per gereedheid. */
  readySuppliers: number;
  notReadySuppliers: number;
  notSetUpSuppliers: number;
  inactiveSuppliers: number;
  /** Aantal taken dat klaar is voor levering. */
  readyTasks: number;
  /** Aantal redenen onder "Nog niet klaar", per soort. */
  reasonCounts: Record<NotReadyReasonKind, number>;
  definitions: number;
  reusableDefinitions: number;
};

export type DashboardModel = {
  suppliers: SupplierModel[];
  totals: DashboardTotals;
  /** Alle redenen van alle leveranciers, in de volgorde van de leveranciers. */
  reasons: NotReadyReason[];
};

export type DashboardInput = {
  organisations: readonly SourceOrganisationRow[];
  definitions: readonly DefinitionRow[];
  links: readonly ImportLinkRow[];
  tasks: readonly TaskRow[];
};

export function isOwnDefinition(definition: DefinitionRow): boolean {
  return definition.usageType !== 'REUSABLE_TEMPLATE';
}

function groupBy<T>(items: readonly T[], key: (item: T) => number): Map<number, T[]> {
  const map = new Map<number, T[]>();
  for (const item of items) {
    const k = key(item);
    const bucket = map.get(k);
    if (bucket === undefined) {
      map.set(k, [item]);
    } else {
      bucket.push(item);
    }
  }
  return map;
}

function buildSupplier(
  organisation: SourceOrganisationRow,
  definitionsOf: readonly DefinitionRow[],
  linksByDefinition: Map<number, ImportLinkRow[]>,
  tasksByLink: Map<number, TaskRow[]>,
  linkChecks: LinkCheckResults | null,
): SupplierModel {
  const definitions: DefinitionModel[] = definitionsOf.map((definition) => ({
    definition,
    links: (linksByDefinition.get(definition.id) ?? []).map((link) => ({ link, tasks: tasksByLink.get(link.id) ?? [] })),
  }));
  const ownDefinitions = definitions.filter((entry) => isOwnDefinition(entry.definition));

  const readyTasks: TaskRow[] = [];
  const reasons: NotReadyReason[] = [];
  let linkCount = 0;
  let taskCount = 0;

  for (const entry of definitions) {
    for (const linkModel of entry.links) {
      linkCount += 1;
      taskCount += linkModel.tasks.length;
    }
  }

  for (const entry of ownDefinitions) {
    const hasActiveVersion = entry.definition.activeRevisionId !== null;
    if (entry.links.length === 0) {
      reasons.push({ kind: 'NO_LINK', organisationId: organisation.id, definitionId: entry.definition.id });
    }
    if (!hasActiveVersion) {
      reasons.push({ kind: 'NO_ACTIVE_VERSION', organisationId: organisation.id, definitionId: entry.definition.id });
    }
    for (const linkModel of entry.links) {
      if (linkModel.tasks.length === 0) {
        reasons.push({
          kind: 'LINK_WITHOUT_TASK',
          organisationId: organisation.id,
          definitionId: entry.definition.id,
          linkId: linkModel.link.id,
        });
      }
      if (hasActiveVersion) {
        const activeTasks = linkModel.tasks.filter((task) => task.active);
        const check = organisation.active ? linkChecks?.get(linkModel.link.id) : undefined;
        if (check !== undefined && !check.ready && activeTasks.length > 0) {
          // De koppeling lijkt klaar maar de server keurt ze af: haar taken tellen niet als klaar, en de reden staat erbij.
          reasons.push({
            kind: 'LINK_CHECK_FAILED',
            organisationId: organisation.id,
            definitionId: entry.definition.id,
            linkId: linkModel.link.id,
            ...(check.problems[0] !== undefined ? { checkCode: check.problems[0].code } : {}),
          });
        } else {
          readyTasks.push(...activeTasks);
        }
        if (linkModel.tasks.length > 0 && activeTasks.length === 0) {
          reasons.push({
            kind: 'NO_ACTIVE_TASK',
            organisationId: organisation.id,
            definitionId: entry.definition.id,
            linkId: linkModel.link.id,
          });
        }
      }
    }
  }

  let readiness: Readiness;
  let shownReasons: NotReadyReason[] = reasons;
  if (!organisation.active) {
    readiness = 'INACTIVE';
    shownReasons = [];
  } else if (ownDefinitions.length === 0) {
    readiness = 'NOT_SET_UP';
    shownReasons = [{ kind: 'NO_DEFINITION', organisationId: organisation.id }];
  } else if (readyTasks.length > 0) {
    // Een klare leverancier kan nog een zijspoor hebben (bv. een tweede koppeling zonder taak): dat blijft als reden
    // staan, zoals in de mock, maar het maakt de leverancier niet "nog niet klaar".
    readiness = 'READY';
  } else {
    readiness = 'NOT_READY';
  }

  return {
    organisation,
    definitions,
    ownDefinitions,
    linkCount,
    taskCount,
    readyTasks,
    readiness,
    reasons: shownReasons,
  };
}

/**
 * De koppelingen die de tweede fase van het dashboard bij de server laat controleren (F4): enkel koppelingen van eigen
 * importdefinities van actieve organisaties (een herbruikbare definitie krijgt nooit een koppeling; een inactieve
 * organisatie is toch al "Inactief").
 */
export function linkIdsToCheck(input: DashboardInput): number[] {
  const activeOrganisations = new Set(input.organisations.filter((o) => o.active).map((o) => o.id));
  const ownDefinitions = new Set(
    input.definitions
      .filter((d) => isOwnDefinition(d) && activeOrganisations.has(d.sourceOrganisationId))
      .map((d) => d.id),
  );
  return input.links.filter((link) => ownDefinitions.has(link.importDefinitionId)).map((link) => link.id);
}

/**
 * Bouwt het dashboardmodel. Zonder `linkChecks` (`null`: de tweede fase is niet gelopen of mislukt) is het model het
 * heuristische model van de vier bulklijsten, ongewijzigd.
 */
export function buildDashboardModel(input: DashboardInput, linkChecks: LinkCheckResults | null = null): DashboardModel {
  const definitionsByOrganisation = groupBy(input.definitions, (definition) => definition.sourceOrganisationId);
  const linksByDefinition = groupBy(input.links, (link) => link.importDefinitionId);
  const tasksByLink = groupBy(input.tasks, (task) => task.importLinkId);

  const suppliers = input.organisations.map((organisation) =>
    buildSupplier(organisation, definitionsByOrganisation.get(organisation.id) ?? [], linksByDefinition, tasksByLink, linkChecks),
  );

  const reasonCounts: Record<NotReadyReasonKind, number> = {
    NO_ACTIVE_VERSION: 0,
    LINK_WITHOUT_TASK: 0,
    NO_ACTIVE_TASK: 0,
    NO_LINK: 0,
    NO_DEFINITION: 0,
    LINK_CHECK_FAILED: 0,
  };
  const reasons: NotReadyReason[] = [];
  for (const supplier of suppliers) {
    for (const reason of supplier.reasons) {
      reasons.push(reason);
      reasonCounts[reason.kind] += 1;
    }
  }

  const count = (readiness: Readiness) => suppliers.filter((supplier) => supplier.readiness === readiness).length;
  return {
    suppliers,
    reasons,
    totals: {
      organisations: suppliers.length,
      suppliers: input.organisations.filter((organisation) => organisation.type === 'SUPPLIER').length,
      associations: input.organisations.filter((organisation) => organisation.type !== 'SUPPLIER').length,
      readySuppliers: count('READY'),
      notReadySuppliers: count('NOT_READY'),
      notSetUpSuppliers: count('NOT_SET_UP'),
      inactiveSuppliers: count('INACTIVE'),
      readyTasks: suppliers.reduce((sum, supplier) => sum + supplier.readyTasks.length, 0),
      reasonCounts,
      definitions: input.definitions.length,
      reusableDefinitions: input.definitions.filter((definition) => !isOwnDefinition(definition)).length,
    },
  };
}

/** Het verzoek om één pagina. */
export type PageFetcher<T> = (page: number, size: number, signal?: AbortSignal) => Promise<PageResult<T>>;

/** Het servermaximum per pagina van de lijstendpoints (`MAX_PAGE_SIZE`). */
export const BULK_PAGE_SIZE = 200;

/**
 * Haalt alle pagina's van een lijstendpoint op tot `totalElements` bereikt is en voegt ze samen. Stopt ook op een lege
 * pagina of na `totalPages`, zodat een server die het aantal verandert tijdens het bladeren nooit een eindeloze lus
 * geeft. Een fout van één pagina is een fout van het geheel: nooit een stil afgekapte lijst.
 */
export async function fetchAllPages<T>(
  fetchPage: PageFetcher<T>,
  signal?: AbortSignal,
  size: number = BULK_PAGE_SIZE,
): Promise<T[]> {
  const all: T[] = [];
  let page = 0;
  for (;;) {
    const result = await fetchPage(page, size, signal);
    all.push(...result.content);
    page += 1;
    if (result.content.length === 0 || all.length >= result.totalElements || page >= result.totalPages) {
      return all;
    }
  }
}

/** De versies per importdefinitie (id) zoals `GET /definitions/{id}/revisions` ze levert. */
export type RevisionsByDefinition = Readonly<Record<number, readonly RevisionRow[]>>;

/** De ene volgende stap van een leverancier (leveranciersdetail, tab Overzicht). */
export type NextStep =
  | { kind: 'inactive'; step: number }
  | { kind: 'createDefinition'; step: number }
  | { kind: 'activateDraft'; step: number; definitionId: number; revisionId: number; revisionNumber: number }
  | { kind: 'noActiveVersion'; step: number; definitionId: number }
  | { kind: 'createLink'; step: number; definitionId: number }
  | { kind: 'createTask'; step: number; definitionId: number; linkId: number }
  | { kind: 'reactivateTask'; step: number }
  | { kind: 'ready'; step: number };

/** Posities in de stappenbalk: Leverancier 0, Importdefinitie 1, Koppeling 2, Taak 3, Inlezing 4, Publicatie 5. */
export const STEP_SUPPLIER = 0;
export const STEP_DEFINITION = 1;
export const STEP_LINK = 2;
export const STEP_TASK = 3;
export const STEP_INTAKE = 4;

/**
 * Wat nu het eerstvolgende is voor deze leverancier, altijd precies één ding. Volgorde: inactief, geen
 * importdefinitie, een openstaand concept (activeren), een definitie zonder actieve versie, zonder koppeling, een
 * koppeling zonder taak, alle taken uit, anders klaar om in te lezen. Een openstaand concept gaat voor, ook bij een
 * leverancier die al klaar is: dat is werk dat nog wacht.
 */
export function nextStep(supplier: SupplierModel, revisions: RevisionsByDefinition): NextStep {
  if (supplier.readiness === 'INACTIVE') {
    return { kind: 'inactive', step: STEP_SUPPLIER };
  }
  if (supplier.ownDefinitions.length === 0) {
    return { kind: 'createDefinition', step: STEP_DEFINITION };
  }
  for (const entry of supplier.ownDefinitions) {
    const drafts = (revisions[entry.definition.id] ?? [])
      .filter((revision) => revision.status === 'DRAFT')
      .sort((a, b) => b.revisionNumber - a.revisionNumber);
    if (drafts.length > 0) {
      const draft = drafts[0]!;
      return {
        kind: 'activateDraft',
        step: STEP_DEFINITION,
        definitionId: entry.definition.id,
        revisionId: draft.id,
        revisionNumber: draft.revisionNumber,
      };
    }
  }
  for (const entry of supplier.ownDefinitions) {
    if (entry.definition.activeRevisionId === null) {
      return { kind: 'noActiveVersion', step: STEP_DEFINITION, definitionId: entry.definition.id };
    }
  }
  for (const entry of supplier.ownDefinitions) {
    if (entry.links.length === 0) {
      return { kind: 'createLink', step: STEP_LINK, definitionId: entry.definition.id };
    }
  }
  for (const entry of supplier.ownDefinitions) {
    for (const linkModel of entry.links) {
      if (linkModel.tasks.length === 0) {
        return { kind: 'createTask', step: STEP_TASK, definitionId: entry.definition.id, linkId: linkModel.link.id };
      }
    }
  }
  if (supplier.readyTasks.length === 0) {
    return { kind: 'reactivateTask', step: STEP_TASK };
  }
  return { kind: 'ready', step: STEP_INTAKE };
}

/** De actieve versie (ACTIVE) van een definitie uit de versielijst, of `null`. */
export function activeRevisionOf(revisions: readonly RevisionRow[]): RevisionRow | null {
  return revisions.find((revision) => revision.status === 'ACTIVE') ?? null;
}

/**
 * De versie die "Bestand en velden" toont: de gevraagde (als ze bij de definitie hoort), anders de actieve, anders het
 * nieuwste concept, anders de hoogste versie; `null` zonder versies.
 */
export function defaultRevisionOf(revisions: readonly RevisionRow[], requestedId: number | null): RevisionRow | null {
  const requested = requestedId === null ? undefined : revisions.find((revision) => revision.id === requestedId);
  if (requested !== undefined) {
    return requested;
  }
  const active = activeRevisionOf(revisions);
  if (active !== null) {
    return active;
  }
  const newestFirst = [...revisions].sort((a, b) => b.revisionNumber - a.revisionNumber);
  return newestFirst.find((revision) => revision.status === 'DRAFT') ?? newestFirst[0] ?? null;
}
