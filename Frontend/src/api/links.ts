/**
 * De twee bookmarkwaarde-endpoints van `be.dda.catalogimport.web.CatalogImportLinkController` (S1-F3,
 * scherm 1b schrijfdeel — `docs/decisions.md` 2026-09-27 "scherm 1a/1b").
 *
 * **Bewust een apart bestand van `api/importLinks.ts`.** Dat bestand bedient
 * `CatalogImportImportLinkController` (`GET /import-links`), dat altijd bereikbaar is; deze controller
 * staat volledig achter `catalogimport.setup-api.enabled` en geeft met de vlag uit 404 zonder `code` —
 * dezelfde scheiding als tussen `api/setup.ts` (vlagloos) en `api/templates.ts` (achter de vlag). Ze in
 * één bestand samenvoegen zou dat verschil in bereikbaarheid onzichtbaar maken.
 */
import { request } from './http.ts';
import type { LinkBookmarkValueRow, LinkBookmarkValues, SetBookmarkValueRequest } from './types.ts';

/**
 * GET /links/{linkId}/bookmark-values — CatalogImportLinkController.bookmarkValues (READ).
 *
 * @throws {import('./http.ts').ApiError} 404 `LINK_NOT_FOUND`
 */
export function getLinkBookmarkValues(linkId: number, signal?: AbortSignal): Promise<LinkBookmarkValues> {
  return request<LinkBookmarkValues>(`/links/${linkId}/bookmark-values`, { signal });
}

/**
 * PUT /links/{linkId}/bookmark-values/{name} — CatalogImportLinkController.setBookmarkValue (MANAGE).
 *
 * Eén waarde per aanroep: het endpoint kent geen verzamelvorm, en de wijziging legt per waarde de vorige
 * waarde, wie en wanneer vast. `value` is verplicht en `''` betekent uitdrukkelijk leeg — niet
 * "ongewijzigd laten"; niet wijzigen doet de UI door deze functie niet aan te roepen (R-BMK-03).
 *
 * @throws {import('./http.ts').ApiError} 404 `LINK_NOT_FOUND`, `SOURCE_ORGANISATION_NOT_FOUND`; 409
 *   `LINK_BOOKMARK_LOCKED_BY_OPEN_BATCH` (open batch — het slot van ontwerpkeuze 5), `NO_ACTIVE_REVISION`;
 *   400 `BOOKMARK_UNKNOWN`, `BOOKMARK_SCOPE_MISMATCH`, `CONFIG_BOOKMARK_VALUE_INVALID`,
 *   `CONFIG_BOOKMARK_VALUE_TOO_LONG`, `CONFIG_REQUIRED_BOOKMARK_MISSING`
 */
export function setBookmarkValue(
  linkId: number,
  bookmarkName: string,
  body: SetBookmarkValueRequest,
  signal?: AbortSignal,
): Promise<LinkBookmarkValueRow> {
  return request<LinkBookmarkValueRow>(
    `/links/${linkId}/bookmark-values/${encodeURIComponent(bookmarkName)}`,
    { method: 'PUT', body: JSON.stringify(body), signal },
  );
}
