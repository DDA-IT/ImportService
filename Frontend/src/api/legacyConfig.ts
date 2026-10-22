/**
 * Functies voor `/legacy-config`-endpoints van `be.dda.catalogimport.web.CatalogImportLegacyConfigController`
 * (slice S9 van `docs/design/legacy-config-bundle-design.md` §2). Alles vraagt `MANAGE`. De ontvangst zelf
 * (`POST /legacy-config/receipts`) is voor de technische client van de datasync en komt hier bewust niet in voor.
 */
import { request, toQueryString } from './http.ts';
import type {
  LegacyApplyChoices,
  LegacyApplyRequest,
  LegacyApplyResult,
  LegacyDiscardRequest,
  LegacyDiscardResult,
  LegacyPreviewResult,
  LegacyReceiptDetail,
  LegacyReceiptRow,
  LegacyReceiptStatus,
  PageResult,
} from './types.ts';

/** GET /legacy-config/receipts — sortering `received_at desc, id desc`, zonder bundel. */
export function listLegacyReceipts(
  params: { status?: LegacyReceiptStatus; interfaceCode?: string; page?: number; size?: number },
  signal?: AbortSignal,
): Promise<PageResult<LegacyReceiptRow>> {
  const query = toQueryString({
    status: params.status,
    interface: params.interfaceCode,
    page: params.page,
    size: params.size,
  });
  return request<PageResult<LegacyReceiptRow>>(`/legacy-config/receipts${query}`, { signal });
}

/** GET /legacy-config/receipts/{id} — de bundel zonder secret, met `secretOutcome` en `credentialRef`. */
export function getLegacyReceipt(receiptId: number, signal?: AbortSignal): Promise<LegacyReceiptDetail> {
  return request<LegacyReceiptDetail>(`/legacy-config/receipts/${receiptId}`, { signal });
}

/** GET /legacy-config/receipts/{id}/preview — het rapport en de `previewFingerprint`; schrijft niets. */
export function previewLegacyReceipt(
  receiptId: number,
  choices: LegacyApplyChoices,
  signal?: AbortSignal,
): Promise<LegacyPreviewResult> {
  const query = toQueryString({
    definitionCodeOverride: choices.definitionCodeOverride,
    charsetWhenUndeclared: choices.charsetWhenUndeclared,
    libraryCode: choices.libraryCode,
  });
  return request<LegacyPreviewResult>(`/legacy-config/receipts/${receiptId}/preview${query}`, { signal });
}

/**
 * POST /legacy-config/receipts/{id}/apply — schrijft de configuratie (nooit activeren). 409
 * `LEGACY_PREVIEW_STALE` als de `previewFingerprint` niet meer klopt, `LEGACY_APPLY_BLOCKED` bij blokkerende punten.
 */
export function applyLegacyReceipt(
  receiptId: number,
  body: LegacyApplyRequest,
  signal?: AbortSignal,
): Promise<LegacyApplyResult> {
  return request<LegacyApplyResult>(`/legacy-config/receipts/${receiptId}/apply`, {
    method: 'POST',
    body: JSON.stringify(body),
    signal,
  });
}

/**
 * POST /legacy-config/receipts/{id}/discard — verwerpt een ontvangst met status `RECEIVED` (G1a). 409
 * `LEGACY_RECEIPT_NOT_APPLICABLE` bij elke andere status; 400 `LEGACY_DISCARD_REASON_REQUIRED` zonder reden.
 */
export function discardLegacyReceipt(
  receiptId: number,
  body: LegacyDiscardRequest,
  signal?: AbortSignal,
): Promise<LegacyDiscardResult> {
  return request<LegacyDiscardResult>(`/legacy-config/receipts/${receiptId}/discard`, {
    method: 'POST',
    body: JSON.stringify(body),
    signal,
  });
}
