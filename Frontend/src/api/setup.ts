/**
 * Functies voor de alleen-lezen inrichtingsendpoints (S1-B1) —
 * `be.dda.catalogimport.web.CatalogImportSetupQueryController`: bronorganisatie → definitie →
 * revisie. Bewust NIET achter `catalogimport.setup-api.enabled`, zie de javadoc op de controller.
 * Voedt de client-side boom van scherm 1a (S1-F1, `docs/decisions.md` 2026-09-27 "scherm 1a/1b").
 */
import { request, toQueryString } from './http.ts';
import type {
  DefinitionRow,
  DefinitionUsageType,
  FieldCatalogResponse,
  PageResult,
  RevisionConfigurationCheck,
  RevisionDetail,
  RevisionLegacyOrigin,
  RevisionRow,
  SourceOrganisationRow,
} from './types.ts';

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

/**
 * GET /definitions/{definitionId}/revisions/{revisionId} — CatalogImportSetupQueryController.revision
 * (endpoint E1 van `docs/design/revision-successor-design.md` §6, bouwstap S1-X-3).
 *
 * Het volledige revisiedetail: alle scalaire velden plus mappings, recordfilters, kritiek-overrules,
 * bookmarkdeclaraties en `DEFINITION`-scope bookmarkwaarden. Recht `READ` en — net als de rest van dit
 * bestand — **buiten** `catalogimport.setup-api.enabled`: lezen mag altijd, ook op een omgeving waar de
 * schrijfpaden (E2/E3/E4/E5, zie `api/setupRevisions.ts`) dicht staan. Geen statusbeperking: werkt op
 * een DRAFT, ACTIVE én SUPERSEDED revisie.
 *
 * @throws {import('./http.ts').ApiError} 404 `DEFINITION_NOT_FOUND`, `REVISION_NOT_FOUND` (ook wanneer
 *   de revisie bij een andere definitie hoort)
 */
export function getRevisionDetail(
  definitionId: number,
  revisionId: number,
  signal?: AbortSignal,
): Promise<RevisionDetail> {
  return request<RevisionDetail>(`/definitions/${definitionId}/revisions/${revisionId}`, { signal });
}

/**
 * GET /definitions/{definitionId}/revisions/{revisionId}/legacy-origin — RevisionLegacyOriginService (B2). Alleen lezen:
 * de legacy-regels van de ontvangst waaruit de versie komt, met het vaste doel per regel.
 *
 * @throws {import('./http.ts').ApiError} 404 `DEFINITION_NOT_FOUND`, `REVISION_NOT_FOUND`
 */
export function getRevisionLegacyOrigin(
  definitionId: number,
  revisionId: number,
  signal?: AbortSignal,
): Promise<RevisionLegacyOrigin> {
  return request<RevisionLegacyOrigin>(`/definitions/${definitionId}/revisions/${revisionId}/legacy-origin`, { signal });
}

/**
 * GET /field-catalog — CatalogImportSetupQueryController (EB1; recht `READ`, geen vlag). De veldcatalogus met per veld de
 * standaard kritiek van een nieuwe koppeling en of die instelbaar is, plus de zeven revisie-eigen velden van Herkenning en
 * Basis. Eén gedeelde standaardregel met de server (par. 15.1), zodat de client ze niet zelf hoeft af te leiden.
 */
export function getFieldCatalog(signal?: AbortSignal): Promise<FieldCatalogResponse> {
  return request<FieldCatalogResponse>('/field-catalog', { signal });
}

/**
 * GET /definitions/{definitionId}/revisions/{revisionId}/configuration-check — de activatiecontrole van een versie als
 * checklist, zonder bestand en zonder te activeren (EB4, E5a). Recht Lezen, schrijft niets.
 *
 * @throws {import('./http.ts').ApiError} 404 `DEFINITION_NOT_FOUND`, `REVISION_NOT_FOUND`
 */
export function getConfigurationCheck(
  definitionId: number,
  revisionId: number,
  signal?: AbortSignal,
): Promise<RevisionConfigurationCheck> {
  return request<RevisionConfigurationCheck>(
    `/definitions/${definitionId}/revisions/${revisionId}/configuration-check`,
    { signal },
  );
}
