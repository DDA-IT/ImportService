/**
 * Functies voor `/batches`-endpoints van `be.dda.catalogimport.web.CatalogImportBatchController`
 * die scherm (3) nodig heeft: de mutatielijst (hergebruikt door het herbruikbare
 * mutatielijst-component, zie §11 van het ontwerp) en `accept-baseline` (scherm 2). Sinds bouwstap
 * S0-B1/S0-B2 (D14, Scherm 0) ook `listBatches`/`batchSummary`, de werkvoorraadlijst en -samenvatting.
 * De overige endpoints van die controller (`/batches/{id}`, `/issues`, `/issue-groups`, `/continue`)
 * horen bij scherm (2) en volgen in een latere bouwstap.
 */
import { request, toQueryString } from './http.ts';
import type {
  AcceptBaselineRequest,
  BaselineAcceptance,
  BatchRow,
  BatchSummary,
  ImportBatchStatus,
  MutationActionType,
  MutationRow,
  MutationStatus,
  PageResult,
  ValidationResult,
} from './types.ts';

/** GET /batches — CatalogImportBatchController.batches (Scherm 0, D14, bouwstap S0-B1) */
export function listBatches(
  params: {
    status?: ImportBatchStatus;
    validationResult?: ValidationResult;
    importLinkId?: number;
    createdFrom?: string;
    createdTo?: string;
    page?: number;
    size?: number;
  },
  signal?: AbortSignal,
): Promise<PageResult<BatchRow>> {
  const query = toQueryString({
    status: params.status,
    validationResult: params.validationResult,
    importLinkId: params.importLinkId,
    createdFrom: params.createdFrom,
    createdTo: params.createdTo,
    page: params.page,
    size: params.size,
  });
  return request<PageResult<BatchRow>>(`/batches${query}`, { signal });
}

/** GET /batches/summary — CatalogImportBatchController.summary (Scherm 0, D14, bouwstap S0-B2) */
export function batchSummary(params: { importLinkId?: number }, signal?: AbortSignal): Promise<BatchSummary> {
  const query = toQueryString({ importLinkId: params.importLinkId });
  return request<BatchSummary>(`/batches/summary${query}`, { signal });
}

/**
 * GET /batches/{batchId}/mutations — CatalogImportBatchController.mutations
 *
 * Draagt sinds bouwstap C1 ook `status` en `statusReason` (exacte, hoofdlettergevoelige gelijkheid) en
 * sinds C4 `identityHash` (hoofdletterongevoelig). Deze lijst kent géén `batchId`-filter: de batch
 * staat al in het pad. Lege filters worden niet meegestuurd.
 */
export function batchMutations(
  batchId: number,
  params: {
    status?: MutationStatus;
    statusReason?: string;
    actionType?: MutationActionType;
    identityHash?: string;
    page?: number;
    size?: number;
  },
  signal?: AbortSignal,
): Promise<PageResult<MutationRow>> {
  const query = toQueryString({
    status: params.status,
    statusReason: params.statusReason,
    actionType: params.actionType,
    identityHash: params.identityHash,
    page: params.page,
    size: params.size,
  });
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
