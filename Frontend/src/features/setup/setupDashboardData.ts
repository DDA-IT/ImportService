/**
 * Het laden van de gegevens voor het dashboard Leveranciers en het leveranciersdetail (beslissingslog 2026-10-23
 * "Redesign inrichtingsoverzicht /setup": 4 bulkaanroepen met doorbladeren, client-side gegroepeerd; geen
 * `/setup/overview`). Alleen bestaande lijstendpoints, alle vlagloos en met recht Lezen.
 */

import * as setupApi from '../../api/setup.ts';
import * as importLinksApi from '../../api/importLinks.ts';
import * as tasksApi from '../../api/tasks.ts';
import {
  buildDashboardModel,
  fetchAllPages,
  linkIdsToCheck,
  type DashboardInput,
  type DashboardModel,
  type LinkCheckResults,
  type RevisionsByDefinition,
  type SupplierModel,
} from './setupDashboardModel.ts';

/** Het dashboard en of de tweede fase (serverchecks van de koppelingen) ontbreekt. */
export type LoadedDashboard = {
  model: DashboardModel;
  /** `true` als de controle van de koppelingen mislukte: het model is dan de heuristiek van de vier lijsten. */
  linkChecksFailed: boolean;
};

/**
 * Het dashboard: alle organisaties, definities, koppelingen en taken, elk tot en met de laatste pagina (fase 1). Daarna
 * een tweede fase (F4): de serverchecks (`GET /import-links/readiness`, per 100) enkel voor de koppelingen van eigen
 * importdefinities van actieve organisaties. Mislukt die fase, dan blijft het model de heuristiek van fase 1 en geeft
 * `linkChecksFailed` dat door voor een niet-blokkerende melding; een afgebroken verzoek wordt wel doorgegeven.
 */
export async function loadDashboard(signal?: AbortSignal): Promise<LoadedDashboard> {
  const [organisations, definitions, links, tasks] = await Promise.all([
    fetchAllPages((page, size, s) => setupApi.listSourceOrganisations({ page, size }, s), signal),
    fetchAllPages((page, size, s) => setupApi.listDefinitions({ page, size }, s), signal),
    fetchAllPages((page, size, s) => importLinksApi.listImportLinks({ page, size }, s), signal),
    fetchAllPages((page, size, s) => tasksApi.listTasks({ page, size }, s), signal),
  ]);
  const input: DashboardInput = { organisations, definitions, links, tasks };
  const ids = linkIdsToCheck(input);
  if (ids.length === 0) {
    return { model: buildDashboardModel(input), linkChecksFailed: false };
  }
  try {
    const summaries = await importLinksApi.listLinkReadiness(ids, signal);
    const checks: LinkCheckResults = new Map(summaries.map((summary) => [summary.linkId, summary]));
    return { model: buildDashboardModel(input, checks), linkChecksFailed: false };
  } catch (cause) {
    if (signal?.aborted === true) {
      throw cause;
    }
    return { model: buildDashboardModel(input), linkChecksFailed: true };
  }
}

/** Eén leverancier met haar keten en de versies van haar importdefinities. */
export type SupplierBundle = { supplier: SupplierModel; revisions: RevisionsByDefinition };

/**
 * Eén leverancier met haar keten. Er is geen endpoint voor één organisatie: de (kleine) organisatielijst wordt
 * doorgebladerd, de rest haalt enkel op wat bij deze organisatie hoort (filters `sourceOrganisationId`,
 * `importDefinitionId`, `importLinkId`) plus de versielijst per importdefinitie. `null` als de organisatie niet
 * bestaat.
 */
export async function loadSupplier(organisationId: number, signal?: AbortSignal): Promise<SupplierBundle | null> {
  const organisations = await fetchAllPages((page, size, s) => setupApi.listSourceOrganisations({ page, size }, s), signal);
  const organisation = organisations.find((entry) => entry.id === organisationId);
  if (organisation === undefined) {
    return null;
  }
  const definitions = await fetchAllPages(
    (page, size, s) => setupApi.listDefinitions({ sourceOrganisationId: organisationId, page, size }, s),
    signal,
  );
  const [linkLists, revisionLists] = await Promise.all([
    Promise.all(
      definitions.map((definition) =>
        fetchAllPages(
          (page, size, s) => importLinksApi.listImportLinks({ importDefinitionId: definition.id, page, size }, s),
          signal,
        ),
      ),
    ),
    Promise.all(
      definitions.map((definition) =>
        fetchAllPages((page, size, s) => setupApi.listDefinitionRevisions(definition.id, { page, size }, s), signal),
      ),
    ),
  ]);
  const links = linkLists.flat();
  const taskLists = await Promise.all(
    links.map((link) =>
      fetchAllPages((page, size, s) => tasksApi.listTasks({ importLinkId: link.id, page, size }, s), signal),
    ),
  );
  const model = buildDashboardModel({ organisations: [organisation], definitions, links, tasks: taskLists.flat() });
  const supplier = model.suppliers[0];
  if (supplier === undefined) {
    return null;
  }
  const revisions: Record<number, RevisionsByDefinition[number]> = {};
  definitions.forEach((definition, position) => {
    revisions[definition.id] = revisionLists[position] ?? [];
  });
  return { supplier, revisions };
}
