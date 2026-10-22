/**
 * Eén functie per endpoint van `be.dda.catalogimport.web.PublicationRunController` (5-PUB-a, bouwstap
 * 5P-8). Geen functie voor het artefact zelf: `GET /publication-runs/{id}/artifact` levert `text/csv`,
 * geen JSON, en wordt door de UI als gewone downloadlink (`<a href={apiUrl(...)}>`) benaderd — zie
 * `docs/decisions.md` 2026-09-27 "Ontwerp bindend: Frontend publicatierun (SIMULATION) op scherm (3)".
 */
import { request, toQueryString } from './http.ts';
import type {
  PageResult,
  PublicationItemStatus,
  PublicationItemView,
  PublicationRunView,
  RequestRunRequest,
} from './types.ts';

/** POST /bundles/{bundleId}/publication-runs — PublicationRunController.request */
export function requestRun(
  bundleId: number,
  body: RequestRunRequest,
  signal?: AbortSignal,
): Promise<PublicationRunView> {
  return request<PublicationRunView>(`/bundles/${bundleId}/publication-runs`, {
    method: 'POST',
    body: JSON.stringify(body),
    signal,
  });
}

/** GET /bundles/{bundleId}/publication-runs — PublicationRunController.list */
export function listRuns(bundleId: number, signal?: AbortSignal): Promise<PublicationRunView[]> {
  return request<PublicationRunView[]>(`/bundles/${bundleId}/publication-runs`, { signal });
}

/** GET /publication-runs/{runId} — PublicationRunController.get */
export function getRun(runId: number, signal?: AbortSignal): Promise<PublicationRunView> {
  return request<PublicationRunView>(`/publication-runs/${runId}`, { signal });
}

/**
 * POST /publication-runs/{runId}/abort — PublicationRunController.abort (herstel van een vastgelopen
 * PREPARING-run, `docs/decisions.md` 2026-09-27, optie A). Geen body en geen actorveld: de aanvrager komt
 * uit de login, net als `continueBatch`.
 */
export function abortRun(runId: number, signal?: AbortSignal): Promise<PublicationRunView> {
  return request<PublicationRunView>(`/publication-runs/${runId}/abort`, { method: 'POST', signal });
}

/**
 * POST /publication-runs/{runId}/continue — verwerkt de volgende brok items van een run naar Prodis (P7/P6).
 * Geen body en geen actorveld. Een tweede gelijktijdige aanroep krijgt 409 `PUBLICATION_RUN_BUSY`.
 */
export function continueRun(runId: number, signal?: AbortSignal): Promise<PublicationRunView> {
  return request<PublicationRunView>(`/publication-runs/${runId}/continue`, { method: 'POST', signal });
}

/** GET /publication-runs/{runId}/items?status=&page= — de itemresultaten van een run (READ). */
export function listRunItems(
  runId: number,
  params: { status?: PublicationItemStatus | null; page?: number },
  signal?: AbortSignal,
): Promise<PageResult<PublicationItemView>> {
  const query = toQueryString({ status: params.status, page: params.page });
  return request<PageResult<PublicationItemView>>(`/publication-runs/${runId}/items${query}`, { signal });
}
