/**
 * Eén functie per endpoint van `be.dda.catalogimport.web.PublicationRunController` (5-PUB-a, bouwstap
 * 5P-8). Geen functie voor het artefact zelf: `GET /publication-runs/{id}/artifact` levert `text/csv`,
 * geen JSON, en wordt door de UI als gewone downloadlink (`<a href={apiUrl(...)}>`) benaderd — zie
 * `docs/decisions.md` 2026-09-27 "Ontwerp bindend: Frontend publicatierun (SIMULATION) op scherm (3)".
 */
import { request } from './http.ts';
import type { PublicationRunView, RequestRunRequest } from './types.ts';

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
