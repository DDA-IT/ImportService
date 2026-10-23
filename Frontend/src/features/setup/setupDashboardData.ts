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
  type DashboardModel,
  type RevisionsByDefinition,
  type SupplierModel,
} from './setupDashboardModel.ts';

/** Het dashboard: alle organisaties, definities, koppelingen en taken, elk tot en met de laatste pagina. */
export async function loadDashboard(signal?: AbortSignal): Promise<DashboardModel> {
  const [organisations, definitions, links, tasks] = await Promise.all([
    fetchAllPages((page, size, s) => setupApi.listSourceOrganisations({ page, size }, s), signal),
    fetchAllPages((page, size, s) => setupApi.listDefinitions({ page, size }, s), signal),
    fetchAllPages((page, size, s) => importLinksApi.listImportLinks({ page, size }, s), signal),
    fetchAllPages((page, size, s) => tasksApi.listTasks({ page, size }, s), signal),
  ]);
  return buildDashboardModel({ organisations, definitions, links, tasks });
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
