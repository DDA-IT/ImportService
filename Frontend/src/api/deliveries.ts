/**
 * `POST /tasks/{taskId}/deliveries` — `be.dda.catalogimport.web.CatalogImportDeliveryController.upload`
 * (multipart). De upload archiveert, registreert en screent synchroon binnen één verzoek en kan dus lang
 * duren. Bewust géén `AbortSignal` en geen timeout (beslissing 2026-09-23, stap 9): een afgebroken
 * verzoek zou de server niet stoppen en de gebruiker een verkeerd beeld geven.
 */
import { requestWithStatus } from './http.ts';
import type { UploadResponse } from './types.ts';

export type UploadDeliveryParams = {
  taskId: number;
  file: File;
  deliveryReference: string;
  uploadedBy: string;
  expectedRecordCount?: number;
  expectedByteSize?: number;
};

/** `created = true` bij HTTP 201 (nieuwe levering); `false` bij 200 (idempotente herhaling, niet opnieuw gescreend). */
export type UploadOutcome = { created: boolean; delivery: UploadResponse };

export async function uploadDelivery(params: UploadDeliveryParams): Promise<UploadOutcome> {
  const form = new FormData();
  form.append('file', params.file, params.file.name);
  form.append('deliveryReference', params.deliveryReference);
  form.append('uploadedBy', params.uploadedBy);
  if (params.expectedRecordCount !== undefined) {
    form.append('expectedRecordCount', String(params.expectedRecordCount));
  }
  if (params.expectedByteSize !== undefined) {
    form.append('expectedByteSize', String(params.expectedByteSize));
  }
  const { status, body } = await requestWithStatus<UploadResponse>(`/tasks/${params.taskId}/deliveries`, {
    method: 'POST',
    body: form,
  });
  return { created: status === 201, delivery: body };
}
