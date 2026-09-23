/**
 * Functies voor `/batches`-endpoints van `be.dda.catalogimport.web.CatalogImportBatchController`
 * die scherm (3) nodig heeft: de mutatielijst (hergebruikt door het herbruikbare
 * mutatielijst-component, zie §11 van het ontwerp) en `accept-baseline` (scherm 2). De overige
 * endpoints van die controller (`/batches/{id}`, `/issues`, `/issue-groups`, `/continue`) horen bij
 * scherm (2) en volgen in een latere bouwstap.
 */
import { request, toQueryString } from './http.ts';
import type { AcceptBaselineRequest, BaselineAcceptance, MutationActionType, MutationRow, PageResult } from './types.ts';

/** GET /batches/{batchId}/mutations — CatalogImportBatchController.mutations */
export function batchMutations(
  batchId: number,
  params: { actionType?: MutationActionType; page?: number; size?: number },
  signal?: AbortSignal,
): Promise<PageResult<MutationRow>> {
  const query = toQueryString({ actionType: params.actionType, page: params.page, size: params.size });
  return request<PageResult<MutationRow>>(`/batches/${batchId}/mutations${query}`, { signal });
}

/** POST /batches/{batchId}/accept-baseline — CatalogImportBatchController.acceptBaseline */
export function acceptBaseline(
  batchId: number,
  body: AcceptBaselineRequest,
  signal?: AbortSignal,
): Promise<BaselineAcceptance> {
  return request<BaselineAcceptance>(`/batches/${batchId}/accept-baseline`, {
    method: 'POST',
    body: JSON.stringify(body),
    signal,
  });
}
