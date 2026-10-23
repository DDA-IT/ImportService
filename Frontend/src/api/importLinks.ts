/**
 * Functie voor `/import-links` — `be.dda.catalogimport.web.CatalogImportImportLinkController`
 * (Scherm 0/3, D14, bouwstap S0-B3): alleen-lezen opzoeklijst van koppelingen, altijd bereikbaar
 * (niet achter `catalogimport.setup-api.enabled`, zie de javadoc op de controller).
 *
 * `importDefinitionId` is additief (S1-B1): sluit de boom van scherm 1a tot op koppelingenniveau.
 */
import { request, toQueryString } from './http.ts';
import type { ImportLinkRow, LinkReadiness, LinkReadinessSummary, PageResult } from './types.ts';

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

/** Het servermaximum aan koppelingen per aanroep van `GET /import-links/readiness`. */
export const READINESS_BATCH_SIZE = 100;

/**
 * GET /import-links/readiness?linkIds=1,2 — CatalogImportImportLinkController (READ): de gereedheid van meerdere
 * koppelingen in één verzoek, met dezelfde uitkomst als de enkelvoudige controle (`ready` + enkel de problemen).
 * Het servermaximum is 100 id's per verzoek; meer id's worden hier in stukken van 100 gesplitst. Een lege lijst doet
 * geen verzoek. Een fout van één stuk is een fout van het geheel (nooit een stil onvolledig antwoord).
 *
 * @throws {import('./http.ts').ApiError} 400 `READINESS_LINK_IDS_INVALID`; 404 `LINK_NOT_FOUND`; 403 `PERMISSION_DENIED`
 */
export async function listLinkReadiness(
  ids: readonly number[],
  signal?: AbortSignal,
): Promise<LinkReadinessSummary[]> {
  const unique = [...new Set(ids)];
  const chunks: number[][] = [];
  for (let start = 0; start < unique.length; start += READINESS_BATCH_SIZE) {
    chunks.push(unique.slice(start, start + READINESS_BATCH_SIZE));
  }
  const results = await Promise.all(
    chunks.map((chunk) =>
      request<LinkReadinessSummary[]>(`/import-links/readiness${toQueryString({ linkIds: chunk.join(',') })}`, { signal }),
    ),
  );
  return results.flat();
}
