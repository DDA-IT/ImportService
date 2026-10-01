/**
 * NT-6 — wat bestaat er al? Het stappenplan leest bestaande inrichting enkel via de vlagloze leeslijsten
 * (`GET /source-organisations`, `/definitions`, `/definitions/{id}/revisions`, `/import-links`, `/tasks`).
 * Er is geen "zoek op id/code"-endpoint; de lijsten worden daarom volledig doorlopen (pagina's van 200, het
 * servermaximum) en in de browser gefilterd.
 *
 * Gebruikt voor drie dingen, nooit om iets stil over te nemen:
 * - **hervatten** ("Verder inrichten" vanuit Inrichting, met id's in de adresbalk);
 * - na een 409 `*_IN_USE`: het bestaande object met dezelfde code onder dezelfde ouder opzoeken, zodat de
 *   gebruiker uitdrukkelijk "Doorgaan met de bestaande" kan kiezen;
 * - na een verzoek zonder antwoord (netwerkfout, 5xx): vóór een nieuwe poging nagaan of het vorige verzoek
 *   toch aangekomen is (een tweede revisie-POST zou anders een tweede conceptversie maken).
 */
import * as importLinksApi from '../../../api/importLinks.ts';
import * as setupApi from '../../../api/setup.ts';
import * as tasksApi from '../../../api/tasks.ts';
import * as templatesApi from '../../../api/templates.ts';
import type {
  DefinitionRow,
  ImportLinkRow,
  PageResult,
  RevisionRow,
  SourceOrganisationRow,
  TaskRow,
  TemplateView,
} from '../../../api/types.ts';

/** Het servermaximum per pagina (`SetupQueryService.MAX_PAGE_SIZE` e.a.). */
export const LOOKUP_PAGE_SIZE = 200;

/** Harde bovengrens op het aantal pagina's, zodat een onverwacht antwoord nooit een eindeloze lus wordt. */
const MAX_PAGES = 50;

/** Doorloopt alle pagina's van een lijstendpoint. */
export async function loadAll<T>(loadPage: (page: number) => Promise<PageResult<T>>): Promise<T[]> {
  const all: T[] = [];
  for (let page = 0; page < MAX_PAGES; page += 1) {
    const result = await loadPage(page);
    all.push(...result.content);
    if (result.content.length === 0 || all.length >= result.totalElements) {
      break;
    }
  }
  return all;
}

export function loadAllOrganisations(signal?: AbortSignal): Promise<SourceOrganisationRow[]> {
  return loadAll((page) => setupApi.listSourceOrganisations({ page, size: LOOKUP_PAGE_SIZE }, signal));
}

export function loadDefinitionsOf(sourceOrganisationId: number, signal?: AbortSignal): Promise<DefinitionRow[]> {
  return loadAll((page) =>
    setupApi.listDefinitions({ sourceOrganisationId, page, size: LOOKUP_PAGE_SIZE }, signal),
  );
}

export function loadRevisionsOf(definitionId: number, signal?: AbortSignal): Promise<RevisionRow[]> {
  return loadAll((page) => setupApi.listDefinitionRevisions(definitionId, { page, size: LOOKUP_PAGE_SIZE }, signal));
}

export function loadLinksOf(importDefinitionId: number, signal?: AbortSignal): Promise<ImportLinkRow[]> {
  return loadAll((page) =>
    importLinksApi.listImportLinks({ importDefinitionId, page, size: LOOKUP_PAGE_SIZE }, signal),
  );
}

export function loadTasksOf(importLinkId: number, signal?: AbortSignal): Promise<TaskRow[]> {
  return loadAll((page) => tasksApi.listTasks({ importLinkId, page, size: LOOKUP_PAGE_SIZE }, signal));
}

/** Alle sjablonen (`GET /templates`); de pagina's worden volledig doorlopen. */
export function loadAllTemplates(signal?: AbortSignal): Promise<TemplateView[]> {
  return loadAll((page) => templatesApi.listTemplates({ page, size: LOOKUP_PAGE_SIZE }, signal));
}

/**
 * NT-7 (V4 = a) — enkel de sjablonen van de eigen bronorganisatie: een sjabloon bedient enkel de organisatie
 * die het zelf aanlevert (de gematerialiseerde definitie blijft van die organisatie). Gefilterd in de browser.
 */
export async function loadTemplatesOf(sourceOrganisationId: number, signal?: AbortSignal): Promise<TemplateView[]> {
  return (await loadAllTemplates(signal)).filter((row) => row.sourceOrganisationId === sourceOrganisationId);
}

/** De code zoals de server ze bewaart: `SetupService.requireText` trimt, verder niets. */
function sameText(left: string, right: string): boolean {
  return left.trim() === right.trim();
}

export async function findOrganisationByCode(code: string): Promise<SourceOrganisationRow | null> {
  return (await loadAllOrganisations()).find((row) => sameText(row.code, code)) ?? null;
}

export async function findDefinitionByCode(
  sourceOrganisationId: number,
  code: string,
): Promise<DefinitionRow | null> {
  return (await loadDefinitionsOf(sourceOrganisationId)).find((row) => sameText(row.code, code)) ?? null;
}

/** De hoogst genummerde revisie van een definitie (de meest recente versie), of `null` zonder revisies. */
export function latestRevision(rows: readonly RevisionRow[]): RevisionRow | null {
  let latest: RevisionRow | null = null;
  for (const row of rows) {
    if (latest === null || row.revisionNumber > latest.revisionNumber) {
      latest = row;
    }
  }
  return latest;
}

export async function findLatestRevision(definitionId: number): Promise<RevisionRow | null> {
  return latestRevision(await loadRevisionsOf(definitionId));
}

/** Een koppeling met deze code **onder deze definitie** — een gelijke code elders telt niet. */
export async function findLinkByCode(importDefinitionId: number, code: string): Promise<ImportLinkRow | null> {
  return (await loadLinksOf(importDefinitionId)).find((row) => sameText(row.code, code)) ?? null;
}

export async function findTaskByName(importLinkId: number, name: string): Promise<TaskRow | null> {
  return (await loadTasksOf(importLinkId)).find((row) => sameText(row.name, name)) ?? null;
}
