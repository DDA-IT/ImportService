/**
 * Tweede ontvangstweg — levering inlezen uit een beheerde servermap, zie `docs/decisions.md`
 * 2026-09-27 "Ontwerp bindend: tweede ontvangstweg". Beide endpoints vereisen `MANAGE`
 * (`be.dda.catalogimport.web.CatalogImportDeliveryController`).
 */
import { request, requestWithStatus } from './http.ts';
import type { LocalSourceListing, UploadResponse } from './types.ts';

/** GET /local-source/files — kan 404 `LOCAL_SOURCE_NOT_CONFIGURED` of 409 `LOCAL_SOURCE_DIRECTORY_UNAVAILABLE` geven. */
export function listLocalSourceFiles(signal?: AbortSignal): Promise<LocalSourceListing> {
  return request<LocalSourceListing>('/local-source/files', { signal });
}

export type ReadLocalSourceDeliveryParams = {
  taskId: number;
  fileName: string;
  /** Leeg/`undefined` = de server leidt de referentie zelf af (hash van het bronbestand). */
  deliveryReference?: string;
  uploadedBy: string;
  expectedRecordCount?: number;
  expectedByteSize?: number;
};

/**
 * POST /tasks/{taskId}/deliveries/local-source — zelfde `UploadOutcome`-vorm als
 * `deliveries.ts#uploadDelivery` (`created = true` bij HTTP 201, `false` bij idempotente 200).
 */
export type LocalSourceDeliveryOutcome = { created: boolean; delivery: UploadResponse };

export async function readLocalSourceDelivery(
  params: ReadLocalSourceDeliveryParams,
): Promise<LocalSourceDeliveryOutcome> {
  const { status, body } = await requestWithStatus<UploadResponse>(
    `/tasks/${params.taskId}/deliveries/local-source`,
    {
      method: 'POST',
      body: JSON.stringify({
        fileName: params.fileName,
        deliveryReference: params.deliveryReference ?? null,
        uploadedBy: params.uploadedBy,
        expectedRecordCount: params.expectedRecordCount ?? null,
        expectedByteSize: params.expectedByteSize ?? null,
      }),
    },
  );
  return { created: status === 201, delivery: body };
}
