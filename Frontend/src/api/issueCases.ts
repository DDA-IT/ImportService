/**
 * Functies voor `/issue-cases`-endpoints van `be.dda.catalogimport.web.CatalogImportIssueCaseController`
 * (S2-B3, `docs/design/issue-case-design.md` §6): de vijf leesendpoints (recht READ) en, sinds S2-F2,
 * de schrijfactie `POST /issue-cases/{id}/status` (recht MANAGE, S2-B2).
 */
import { request, toQueryString } from './http.ts';
import type {
  IssueCaseDecision,
  IssueCaseEventRow,
  IssueCaseObservationRow,
  IssueCaseRow,
  IssueCaseStatus,
  IssueCaseStatusChangeRequest,
  IssueCaseSummary,
  PageResult,
  RowIssueSeverity,
} from './types.ts';

/**
 * GET /issue-cases — CatalogImportIssueCaseController.cases. Vaste sortering
 * `last_seen_at desc, id desc`; `lastSeenFrom`/`lastSeenTo` is een halfopen interval `[from, to)`.
 */
export function listIssueCases(
  params: {
    importLinkId?: number;
    status?: IssueCaseStatus;
    severity?: RowIssueSeverity;
    issueCode?: string;
    lastSeenFrom?: string;
    lastSeenTo?: string;
    page?: number;
    size?: number;
  },
  signal?: AbortSignal,
): Promise<PageResult<IssueCaseRow>> {
  const query = toQueryString({
    importLinkId: params.importLinkId,
    status: params.status,
    severity: params.severity,
    issueCode: params.issueCode,
    lastSeenFrom: params.lastSeenFrom,
    lastSeenTo: params.lastSeenTo,
    page: params.page,
    size: params.size,
  });
  return request<PageResult<IssueCaseRow>>(`/issue-cases${query}`, { signal });
}

/** GET /issue-cases/summary — CatalogImportIssueCaseController.summary */
export function issueCaseSummary(params: { importLinkId?: number }, signal?: AbortSignal): Promise<IssueCaseSummary> {
  const query = toQueryString({ importLinkId: params.importLinkId });
  return request<IssueCaseSummary>(`/issue-cases/summary${query}`, { signal });
}

/** GET /issue-cases/{caseId} — CatalogImportIssueCaseController.getCase */
export function getIssueCase(caseId: number, signal?: AbortSignal): Promise<IssueCaseRow> {
  return request<IssueCaseRow>(`/issue-cases/${caseId}`, { signal });
}

/** GET /issue-cases/{caseId}/observations — CatalogImportIssueCaseController.observations */
export function getIssueCaseObservations(caseId: number, signal?: AbortSignal): Promise<IssueCaseObservationRow[]> {
  return request<IssueCaseObservationRow[]>(`/issue-cases/${caseId}/observations`, { signal });
}

/** GET /issue-cases/{caseId}/events — CatalogImportIssueCaseController.events */
export function getIssueCaseEvents(caseId: number, signal?: AbortSignal): Promise<IssueCaseEventRow[]> {
  return request<IssueCaseEventRow[]>(`/issue-cases/${caseId}/events`, { signal });
}

/**
 * POST /issue-cases/{caseId}/status — CatalogImportIssueCaseController.changeStatus (S2-B2, recht
 * MANAGE). `expectedStatus` is de status waarvan de aanvrager uitgaat (de al geladen status van het
 * geval); wijkt de werkelijke status af, dan 409 ISSUE_CASE_STATUS_CHANGED zonder iets te wijzigen.
 */
export function changeIssueCaseStatus(
  caseId: number,
  body: IssueCaseStatusChangeRequest,
  signal?: AbortSignal,
): Promise<IssueCaseDecision> {
  return request<IssueCaseDecision>(`/issue-cases/${caseId}/status`, {
    method: 'POST',
    body: JSON.stringify(body),
    signal,
  });
}
