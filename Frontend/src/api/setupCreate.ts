/**
 * De vijf aanmaakpaden van `be.dda.catalogimport.web.CatalogImportSetupController` die het stappenplan
 * "Nieuwe leverancier en taak" gebruikt (NT-6, `docs/decisions.md` 2026-09-30 "Nieuwe leverancier + taak
 * (NT-spoor)": V2 = a, V3 = a, A1).
 *
 * Sinds NT-3 staan deze paden **niet** meer achter `catalogimport.setup-api.enabled`: ze vragen enkel het
 * recht `MANAGE`. Paden en bodies zijn ongewijzigd.
 *
 * **Geen enkele functie is idempotent.** Een tweede `POST /setup/definitions/{id}/revisions` maakt een
 * tweede conceptversie (er is geen databasesleutel die dat tegenhoudt), en de andere vier geven bij een
 * herhaling een 409 `*_IN_USE`. Daarom herhaalt de UI nooit automatisch en leest ze na een verzoek zonder
 * antwoord eerst opnieuw wat er al bestaat voor ze een nieuwe poging toelaat (zie `wizard/lookup.ts`).
 */
import { request } from './http.ts';
import type {
  CreateDefinitionRequest,
  CreateLinkRequest,
  CreateRevisionRequest,
  CreateSourceOrganisationRequest,
  CreateTaskRequest,
  DefinitionView,
  LinkView,
  RevisionView,
  SourceOrganisationView,
  TaskView,
} from './types.ts';

function post<T>(path: string, body: unknown, signal?: AbortSignal): Promise<T> {
  return request<T>(path, { method: 'POST', body: JSON.stringify(body), signal });
}

/**
 * POST /setup/source-organisations — CatalogImportSetupController.createSourceOrganisation (201).
 *
 * @throws {import('./http.ts').ApiError} 400 `CODE_*`, `NAME_*`, `TYPE_REQUIRED`; 409
 *   `SOURCE_ORGANISATION_CODE_IN_USE` (ook bij twee gelijktijdige verzoeken, NT-3)
 */
export function createSourceOrganisation(
  body: CreateSourceOrganisationRequest,
  signal?: AbortSignal,
): Promise<SourceOrganisationView> {
  return post<SourceOrganisationView>('/setup/source-organisations', body, signal);
}

/**
 * POST /setup/definitions — CatalogImportSetupController.createDefinition (201).
 *
 * @throws {import('./http.ts').ApiError} 400 `CODE_*`, `NAME_*`, `SOURCE_ORGANISATION_CODE_*`; 404
 *   `SOURCE_ORGANISATION_NOT_FOUND`; 409 `DEFINITION_CODE_IN_USE` (uniek per bronorganisatie)
 */
export function createDefinition(body: CreateDefinitionRequest, signal?: AbortSignal): Promise<DefinitionView> {
  return post<DefinitionView>('/setup/definitions', body, signal);
}

/**
 * POST /setup/definitions/{definitionId}/revisions — CatalogImportSetupController.createRevision (201).
 * Maakt altijd een **conceptversie** (DRAFT); pas "Revisie activeren" maakt ze bruikbaar voor een levering.
 *
 * @throws {import('./http.ts').ApiError} 400 `<VELD>_REQUIRED|_TOO_LONG|_INVALID` (bv. `DELIMITER_REQUIRED`,
 *   `DISCOUNT_CODE_FIELD_INVALID`); 404 `DEFINITION_NOT_FOUND`
 */
export function createRevision(
  definitionId: number,
  body: CreateRevisionRequest,
  signal?: AbortSignal,
): Promise<RevisionView> {
  return post<RevisionView>(`/setup/definitions/${definitionId}/revisions`, body, signal);
}

/**
 * POST /setup/links — CatalogImportSetupController.createLink (201).
 *
 * @throws {import('./http.ts').ApiError} 400 `CODE_*`, `NAME_*`, `LIBRARY_CODE_*`,
 *   `SOURCE_ORGANISATION_CODE_*`, `LIBRARY_SEARCH_SUPPLIER_CODE_TOO_LONG`, `LINK_CURRENCY_INVALID`; 404
 *   `DEFINITION_NOT_FOUND`, `SOURCE_ORGANISATION_NOT_FOUND`; 409 `LINK_CODE_IN_USE` (code is globaal uniek),
 *   `LINK_SCOPE_IN_USE`
 */
export function createLink(body: CreateLinkRequest, signal?: AbortSignal): Promise<LinkView> {
  return post<LinkView>('/setup/links', body, signal);
}

/**
 * POST /setup/tasks — CatalogImportSetupController.createTask (201). De taak is altijd handmatig (`MANUAL`).
 *
 * @throws {import('./http.ts').ApiError} 400 `NAME_*`, `LINK_ID_REQUIRED`; 404 `LINK_NOT_FOUND`; 409
 *   `TASK_NAME_IN_USE` (uniek per koppeling)
 */
export function createTask(body: CreateTaskRequest, signal?: AbortSignal): Promise<TaskView> {
  return post<TaskView>('/setup/tasks', body, signal);
}
