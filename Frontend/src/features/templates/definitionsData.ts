/**
 * Het laden van de gegevens voor het overzicht Importdefinities en het importdefinitiedetail (R2). Alleen bestaande
 * lijstendpoints; de vlagloze (organisaties, definities, koppelingen, versies) zijn de basis. Wat achter de
 * inrichtings-API-vlag staat (`/templates/{id}/materialisations` en de invulpunten) wordt apart en alleen voor herbruikbare
 * importdefinities geladen, en een fout daarvan breekt het scherm niet.
 */

import * as setupApi from '../../api/setup.ts';
import * as importLinksApi from '../../api/importLinks.ts';
import * as templatesApi from '../../api/templates.ts';
import { ApiError } from '../../api/http.ts';
import type {
  BookmarkSetView,
  DefinitionRow,
  ImportLinkRow,
  MaterialisedDefinitionView,
  RevisionRow,
  SourceOrganisationRow,
} from '../../api/types.ts';
import { fetchAllPages } from '../setup/setupDashboardModel.ts';
import { buildDefinitionOverview, type DefinitionOverview } from './definitionsModel.ts';

/** Het overzicht: alle organisaties, definities en koppelingen, elk tot en met de laatste pagina. */
export async function loadDefinitionOverview(signal?: AbortSignal): Promise<DefinitionOverview> {
  const [organisations, definitions, links] = await Promise.all([
    fetchAllPages((page, size, s) => setupApi.listSourceOrganisations({ page, size }, s), signal),
    fetchAllPages((page, size, s) => setupApi.listDefinitions({ page, size }, s), signal),
    fetchAllPages((page, size, s) => importLinksApi.listImportLinks({ page, size }, s), signal),
  ]);
  return buildDefinitionOverview({ organisations, definitions, links });
}

/** Een aanvullende lading die mag mislukken (vlag uit, geen recht): de fout wordt bewaard in plaats van gegooid. */
export type Optional<T> = { ok: true; data: T } | { ok: false; error: ApiError };

export async function optionally<T>(load: () => Promise<T>, signal?: AbortSignal): Promise<Optional<T>> {
  try {
    return { ok: true, data: await load() };
  } catch (cause) {
    if (signal?.aborted === true) {
      throw cause;
    }
    return { ok: false, error: cause instanceof ApiError ? cause : new ApiError(0, null, null, 'optional') };
  }
}

/** Het gebruik van een rij herbruikbare importdefinities: per definitie-id de gematerialiseerde importdefinities. */
export type MaterialisationsById = Record<number, MaterialisedDefinitionView[]>;

export async function loadMaterialisations(
  definitionIds: readonly number[],
  signal?: AbortSignal,
): Promise<MaterialisationsById> {
  const lists = await Promise.all(
    definitionIds.map((id) =>
      fetchAllPages((page, size, s) => templatesApi.listMaterialisations(id, { page, size }, s), signal),
    ),
  );
  const result: MaterialisationsById = {};
  definitionIds.forEach((id, position) => {
    result[id] = lists[position] ?? [];
  });
  return result;
}

/** Eén importdefinitie met wat er vlagloos bij hoort. `null` als ze niet bestaat. */
export type DefinitionBundle = {
  definition: DefinitionRow;
  organisation: SourceOrganisationRow | null;
  /** Alle definities (voor de afgeleide herkomst en het opzoeken van de organisatie van een gebruiker). */
  allDefinitions: DefinitionRow[];
  revisions: RevisionRow[];
  /** De koppelingen van deze definitie zelf. */
  links: ImportLinkRow[];
};

export async function loadDefinitionBundle(definitionId: number, signal?: AbortSignal): Promise<DefinitionBundle | null> {
  const [organisations, definitions] = await Promise.all([
    fetchAllPages((page, size, s) => setupApi.listSourceOrganisations({ page, size }, s), signal),
    fetchAllPages((page, size, s) => setupApi.listDefinitions({ page, size }, s), signal),
  ]);
  const definition = definitions.find((entry) => entry.id === definitionId);
  if (definition === undefined) {
    return null;
  }
  const [revisions, links] = await Promise.all([
    fetchAllPages((page, size, s) => setupApi.listDefinitionRevisions(definitionId, { page, size }, s), signal),
    fetchAllPages(
      (page, size, s) => importLinksApi.listImportLinks({ importDefinitionId: definitionId, page, size }, s),
      signal,
    ),
  ]);
  return {
    definition,
    organisation: organisations.find((entry) => entry.id === definition.sourceOrganisationId) ?? null,
    allDefinitions: definitions,
    revisions,
    links,
  };
}

/** De vlaggedekte aanvulling van een herbruikbare importdefinitie: wie ze gebruikt en haar invulpunten. */
export type ReusableExtras = {
  materialisations: Optional<MaterialisedDefinitionView[]>;
  /** De invulpunten van de standaardversie (actief, anders de nieuwste); `null` als er geen versie is. */
  bookmarks: Optional<BookmarkSetView> | null;
};

export async function loadReusableExtras(
  definitionId: number,
  bookmarkRevisionId: number | null,
  signal?: AbortSignal,
): Promise<ReusableExtras> {
  const [materialisations, bookmarks] = await Promise.all([
    optionally(
      () => fetchAllPages((page, size, s) => templatesApi.listMaterialisations(definitionId, { page, size }, s), signal),
      signal,
    ),
    bookmarkRevisionId === null
      ? Promise.resolve(null)
      : optionally(() => templatesApi.getBookmarkSet(definitionId, bookmarkRevisionId, signal), signal),
  ]);
  return { materialisations, bookmarks };
}
