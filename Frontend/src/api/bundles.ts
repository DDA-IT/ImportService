/**
 * Eén functie per endpoint van `be.dda.catalogimport.web.CatalogImportBundleController`. Geen
 * component roept `request()`/`fetch()` rechtstreeks aan; dit is de enige plek die dat doet voor
 * `/bundles`.
 */
import { request, toQueryString } from './http.ts';
import type {
  AddBatchesRequest,
  BundleBatchRow,
  BundleCandidate,
  BundleDetail,
  BundleReference,
  BundleSummary,
  CancelBundleRequest,
  CreateBundleRequest,
  DecideGroupRequest,
  DecideMutationRequest,
  DecisionRow,
  FreezeBundleRequest,
  FreezePreflight,
  GroupDecisionView,
  Membership,
  MutationActionType,
  MutationDecisionView,
  MutationRow,
  MutationStatus,
  PageResult,
  PublicationBundleStatus,
  RemoveBatchRequest,
} from './types.ts';

type Page = { page?: number; size?: number };

/** GET /bundles/candidates — CatalogImportBundleController.candidates */
export function candidates(
  params: { importLinkId?: number } & Page,
  signal?: AbortSignal,
): Promise<PageResult<BundleCandidate>> {
  const query = toQueryString({ importLinkId: params.importLinkId, page: params.page, size: params.size });
  return request<PageResult<BundleCandidate>>(`/bundles/candidates${query}`, { signal });
}

/** POST /bundles — CatalogImportBundleController.create */
export function createBundle(body: CreateBundleRequest, signal?: AbortSignal): Promise<BundleReference> {
  return request<BundleReference>('/bundles', { method: 'POST', body: JSON.stringify(body), signal });
}

/** GET /bundles — CatalogImportBundleController.list */
export function list(
  params: { status?: PublicationBundleStatus } & Page,
  signal?: AbortSignal,
): Promise<PageResult<BundleSummary>> {
  const query = toQueryString({ status: params.status, page: params.page, size: params.size });
  return request<PageResult<BundleSummary>>(`/bundles${query}`, { signal });
}

/** GET /bundles/{bundleId} — CatalogImportBundleController.get */
export function get(bundleId: number, signal?: AbortSignal): Promise<BundleDetail> {
  return request<BundleDetail>(`/bundles/${bundleId}`, { signal });
}

/** GET /bundles/{bundleId}/batches — CatalogImportBundleController.batches */
export function batches(
  bundleId: number,
  params: Page,
  signal?: AbortSignal,
): Promise<PageResult<BundleBatchRow>> {
  const query = toQueryString({ page: params.page, size: params.size });
  return request<PageResult<BundleBatchRow>>(`/bundles/${bundleId}/batches${query}`, { signal });
}

/** POST /bundles/{bundleId}/batches — CatalogImportBundleController.addBatches */
export function addBatches(
  bundleId: number,
  body: AddBatchesRequest,
  signal?: AbortSignal,
): Promise<Membership[]> {
  return request<Membership[]>(`/bundles/${bundleId}/batches`, {
    method: 'POST',
    body: JSON.stringify(body),
    signal,
  });
}

/** POST /bundles/{bundleId}/batches/{batchId}/remove — CatalogImportBundleController.removeBatch */
export function removeBatch(
  bundleId: number,
  batchId: number,
  body: RemoveBatchRequest,
  signal?: AbortSignal,
): Promise<Membership> {
  return request<Membership>(`/bundles/${bundleId}/batches/${batchId}/remove`, {
    method: 'POST',
    body: JSON.stringify(body),
    signal,
  });
}

/**
 * GET /bundles/{bundleId}/mutations — CatalogImportBundleController.mutations
 *
 * `statusReason` (bouwstap C1, exacte hoofdlettergevoelige gelijkheid) en `identityHash` (bouwstap C4,
 * hoofdletterongevoelig, de sleutel van de wijzigingsgroep) zijn additief. Lege of weggelaten filters
 * worden door `toQueryString` niet meegestuurd; een onbekende reden of hash geeft een lege pagina, geen
 * fout.
 */
export function bundleMutations(
  bundleId: number,
  params: {
    status?: MutationStatus;
    batchId?: number;
    actionType?: MutationActionType;
    statusReason?: string;
    identityHash?: string;
  } & Page,
  signal?: AbortSignal,
): Promise<PageResult<MutationRow>> {
  const query = toQueryString({
    status: params.status,
    batchId: params.batchId,
    actionType: params.actionType,
    statusReason: params.statusReason,
    identityHash: params.identityHash,
    page: params.page,
    size: params.size,
  });
  return request<PageResult<MutationRow>>(`/bundles/${bundleId}/mutations${query}`, { signal });
}

/** GET /bundles/{bundleId}/decisions — CatalogImportBundleController.decisions */
export function decisions(bundleId: number, params: Page, signal?: AbortSignal): Promise<PageResult<DecisionRow>> {
  const query = toQueryString({ page: params.page, size: params.size });
  return request<PageResult<DecisionRow>>(`/bundles/${bundleId}/decisions${query}`, { signal });
}

/** POST /bundles/{bundleId}/mutations/{mutationId}/approve — CatalogImportBundleController.approve */
export function approve(
  bundleId: number,
  mutationId: number,
  body: DecideMutationRequest,
  signal?: AbortSignal,
): Promise<MutationDecisionView> {
  return request<MutationDecisionView>(`/bundles/${bundleId}/mutations/${mutationId}/approve`, {
    method: 'POST',
    body: JSON.stringify(body),
    signal,
  });
}

/** POST /bundles/{bundleId}/mutations/{mutationId}/reject — CatalogImportBundleController.reject */
export function reject(
  bundleId: number,
  mutationId: number,
  body: DecideMutationRequest,
  signal?: AbortSignal,
): Promise<MutationDecisionView> {
  return request<MutationDecisionView>(`/bundles/${bundleId}/mutations/${mutationId}/reject`, {
    method: 'POST',
    body: JSON.stringify(body),
    signal,
  });
}

/** POST /bundles/{bundleId}/decisions — CatalogImportBundleController.decideGroup */
export function decideGroup(
  bundleId: number,
  body: DecideGroupRequest,
  signal?: AbortSignal,
): Promise<GroupDecisionView> {
  return request<GroupDecisionView>(`/bundles/${bundleId}/decisions`, {
    method: 'POST',
    body: JSON.stringify(body),
    signal,
  });
}

/** POST /bundles/{bundleId}/freeze — CatalogImportBundleController.freeze */
export function freeze(bundleId: number, body: FreezeBundleRequest, signal?: AbortSignal): Promise<BundleDetail> {
  return request<BundleDetail>(`/bundles/${bundleId}/freeze`, {
    method: 'POST',
    body: JSON.stringify(body),
    signal,
  });
}

/**
 * GET /bundles/{bundleId}/freeze-check — CatalogImportBundleController.freezeCheck (bouwstap C3).
 * Momentopname zonder slot: `freezable: true` is nooit een garantie; `freeze` blijft de waarheid.
 */
export function freezeCheck(bundleId: number, signal?: AbortSignal): Promise<FreezePreflight> {
  return request<FreezePreflight>(`/bundles/${bundleId}/freeze-check`, { signal });
}

/** POST /bundles/{bundleId}/cancel — CatalogImportBundleController.cancel */
export function cancel(bundleId: number, body: CancelBundleRequest, signal?: AbortSignal): Promise<BundleDetail> {
  return request<BundleDetail>(`/bundles/${bundleId}/cancel`, {
    method: 'POST',
    body: JSON.stringify(body),
    signal,
  });
}
