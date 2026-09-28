/**
 * Functies voor de alleen-lezen kant van de sjabloon-/materialisatiewizard (S1-F2, scherm 1b) —
 * `be.dda.catalogimport.web.CatalogImportTemplateController`. Blijft, in tegenstelling tot `api/setup.ts`,
 * volledig achter `catalogimport.setup-api.enabled` (A1/A3 openen dit contract niet, zie
 * `docs/decisions.md` 2026-09-27 "scherm 1a/1b"): met de vlag uit geeft elk pad hier 404 zonder `code`.
 *
 * Sjabloondeclaratie (`declareBookmark`, `addUsage`) blijft ook na S1-F3 bewust buiten dit bestand: dat
 * is sjabloonbeheer, geen materialisatiefunctie voor de eindgebruiker (`docs/decisions.md` 2026-09-27,
 * S1-F3-alinea).
 */
import { request, toQueryString } from './http.ts';
import type {
  BookmarkSetView,
  MaterialisationView,
  MaterialisedDefinitionView,
  MaterialiseRequest,
  PageResult,
  TemplateView,
} from './types.ts';

/** GET /templates — CatalogImportTemplateController.templates */
export function listTemplates(
  params: { page?: number; size?: number },
  signal?: AbortSignal,
): Promise<PageResult<TemplateView>> {
  const query = toQueryString({ page: params.page, size: params.size });
  return request<PageResult<TemplateView>>(`/templates${query}`, { signal });
}

/**
 * GET /templates/{definitionId}/revisions/{revisionId}/bookmarks — CatalogImportTemplateController.bookmarks
 *
 * @throws {import('./http.ts').ApiError} 404 `TEMPLATE_NOT_FOUND`, `TEMPLATE_REVISION_NOT_FOUND`
 */
export function getBookmarkSet(
  definitionId: number,
  revisionId: number,
  signal?: AbortSignal,
): Promise<BookmarkSetView> {
  return request<BookmarkSetView>(`/templates/${definitionId}/revisions/${revisionId}/bookmarks`, { signal });
}

/** GET /templates/{definitionId}/materialisations — CatalogImportTemplateController.materialisations */
export function listMaterialisations(
  definitionId: number,
  params: { page?: number; size?: number },
  signal?: AbortSignal,
): Promise<PageResult<MaterialisedDefinitionView>> {
  const query = toQueryString({ page: params.page, size: params.size });
  return request<PageResult<MaterialisedDefinitionView>>(`/templates/${definitionId}/materialisations${query}`, {
    signal,
  });
}

/**
 * POST /templates/{definitionId}/materialisations — CatalogImportTemplateController.materialise (201).
 *
 * De operatie zelf (S1-F3): één transactie, alles of niets. **Bewust niet idempotent** (ontwerp A35):
 * twee keer dezelfde materialisatie botst op de bestaande unieke sleutels en geeft 409
 * (`DEFINITION_CODE_IN_USE`, `LINK_CODE_IN_USE`, `LINK_SCOPE_IN_USE`) — er ontstaat nooit een duplicaat,
 * maar de UI mag dit verzoek dus ook nooit automatisch herhalen (`useAction` doet dat niet).
 *
 * @throws {import('./http.ts').ApiError} 400 `MATERIALISATION_MODE_REQUIRED`,
 *   `REUSE_DEFINITION_REQUIRED`, `REUSE_DEFINITION_NOT_ALLOWED`,
 *   `DEFINITION_SCOPE_VALUE_NOT_ALLOWED_ON_REUSE`, `BOOKMARK_UNKNOWN`,
 *   `CONFIG_REQUIRED_BOOKMARK_MISSING`, `CONFIG_BOOKMARK_VALUE_INVALID`,
 *   `CONFIG_BOOKMARK_VALUE_TOO_LONG`, `LINK_FIELD_BOTH_BOOKMARK_AND_EXPLICIT`; 404
 *   `TEMPLATE_NOT_FOUND`, `TEMPLATE_REVISION_NOT_FOUND`, `DEFINITION_NOT_FOUND`,
 *   `SOURCE_ORGANISATION_NOT_FOUND`; 409 `DEFINITION_NOT_A_TEMPLATE`,
 *   `TEMPLATE_REVISION_NOT_MATERIALISABLE`, `NO_ACTIVE_TEMPLATE_REVISION`,
 *   `DEFINITION_NOT_FROM_TEMPLATE`, `TEMPLATE_REVISION_MISMATCH_ON_REUSE`,
 *   `DEFINITION_NOT_SHAREABLE`, `DEFINITION_CODE_IN_USE`, `LINK_CODE_IN_USE`, `LINK_SCOPE_IN_USE`,
 *   `CONFIG_BOOKMARK_*`
 */
export function materialiseTemplate(
  definitionId: number,
  body: MaterialiseRequest,
  signal?: AbortSignal,
): Promise<MaterialisationView> {
  return request<MaterialisationView>(`/templates/${definitionId}/materialisations`, {
    method: 'POST',
    body: JSON.stringify(body),
    signal,
  });
}
