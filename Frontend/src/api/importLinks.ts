/**
 * Functie voor `/import-links` — `be.dda.catalogimport.web.CatalogImportImportLinkController`
 * (Scherm 0/3, D14, bouwstap S0-B3): alleen-lezen opzoeklijst van koppelingen, altijd bereikbaar
 * (niet achter `catalogimport.setup-api.enabled`, zie de javadoc op de controller).
 *
 * `importDefinitionId` is additief (S1-B1): sluit de boom van scherm 1a tot op koppelingenniveau.
 */
import { request, toQueryString } from './http.ts';
import type { ImportLinkRow, LinkReadiness, PageResult } from './types.ts';

/** GET /import-links — CatalogImportImportLinkController.importLinks */
export function listImportLinks(
  params: { active?: boolean; importDefinitionId?: number; page?: number; size?: number },
  signal?: AbortSignal,
): Promise<PageResult<ImportLinkRow>> {
  const query = toQueryString({
    active: params.active,
    importDefinitionId: params.importDefinitionId,
    page: params.page,
    size: params.size,
  });
  return request<PageResult<ImportLinkRow>>(`/import-links${query}`, { signal });
}

/**
 * GET /import-links/{linkId}/readiness — CatalogImportImportLinkController.readiness (READ; NT-8).
 *
 * Gereedheidscontrole zonder bestand: alle bevindingen voor de koppeling en haar keten tegelijk, elk met een stabiele
 * code. Schrijft niets en staat niet achter `catalogimport.setup-api.enabled`.
 *
 * @throws {import('./http.ts').ApiError} 404 `LINK_NOT_FOUND`; 403 `PERMISSION_DENIED` zonder leesrecht
 */
export function getImportLinkReadiness(linkId: number, signal?: AbortSignal): Promise<LinkReadiness> {
  return request<LinkReadiness>(`/import-links/${linkId}/readiness`, { signal });
}
