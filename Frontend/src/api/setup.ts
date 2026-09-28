/**
 * Functies voor de alleen-lezen inrichtingsendpoints (S1-B1) —
 * `be.dda.catalogimport.web.CatalogImportSetupQueryController`: bronorganisatie → definitie →
 * revisie. Bewust NIET achter `catalogimport.setup-api.enabled`, zie de javadoc op de controller.
 * Voedt de client-side boom van scherm 1a (S1-F1, `docs/decisions.md` 2026-09-27 "scherm 1a/1b").
 */
import { request, toQueryString } from './http.ts';
import type { DefinitionRow, DefinitionUsageType, PageResult, RevisionRow, SourceOrganisationRow } from './types.ts';

/** GET /source-organisations — CatalogImportSetupQueryController.sourceOrganisations */
export function listSourceOrganisations(
  params: { active?: boolean; page?: number; size?: number },
  signal?: AbortSignal,
): Promise<PageResult<SourceOrganisationRow>> {
  const query = toQueryString({ active: params.active, page: params.page, size: params.size });
  return request<PageResult<SourceOrganisationRow>>(`/source-organisations${query}`, { signal });
}

/** GET /definitions — CatalogImportSetupQueryController.definitions */
export function listDefinitions(
  params: { sourceOrganisationId?: number; usageType?: DefinitionUsageType; page?: number; size?: number },
  signal?: AbortSignal,
): Promise<PageResult<DefinitionRow>> {
  const query = toQueryString({
    sourceOrganisationId: params.sourceOrganisationId,
    usageType: params.usageType,
    page: params.page,
    size: params.size,
  });
  return request<PageResult<DefinitionRow>>(`/definitions${query}`, { signal });
}

/**
 * GET /definitions/{definitionId}/revisions — CatalogImportSetupQueryController.revisions
 *
 * @throws {import('./http.ts').ApiError} 404 `DEFINITION_NOT_FOUND`
 */
export function listDefinitionRevisions(
  definitionId: number,
  params: { page?: number; size?: number },
  signal?: AbortSignal,
): Promise<PageResult<RevisionRow>> {
  const query = toQueryString({ page: params.page, size: params.size });
  return request<PageResult<RevisionRow>>(`/definitions/${definitionId}/revisions${query}`, { signal });
}
