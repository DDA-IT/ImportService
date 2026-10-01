/**
 * `POST /revisions/{revisionId}/trial-reads` — `be.dda.catalogimport.web.CatalogImportTrialReadController`
 * (NT-9, contract `docs/design/proefinlezing-design.md`). Proefinlezing zonder opslag: recht `MANAGE`, niet achter
 * `catalogimport.setup-api.enabled`, werkt op een DRAFT-, ACTIVE- of SUPERSEDED-revisie. Geen UI in NT-9 (dat is NT-10).
 *
 * Bewust géén `AbortSignal` en geen timeout, zoals bij de upload: het bestand wordt synchroon volledig gelezen en een
 * afgebroken verzoek stopt de server niet.
 */
import { request } from './http.ts';
import type { TrialReadResult } from './types.ts';

/**
 * Leest `file` op proef tegen de revisie. Een blokkade is een resultaat (200, `verdict.result = 'WOULD_BLOCK'`).
 *
 * @param linkId optioneel; enkel voor de vaste valuta van die koppeling (zonder: EUR, systeemstandaard)
 * @throws {import('./http.ts').ApiError} 400 `FILE_REQUIRED` / `LINK_NOT_OF_REVISION_DEFINITION`; 403
 *   `PERMISSION_DENIED`; 404 `REVISION_NOT_FOUND` / `LINK_NOT_FOUND`; 413 (te groot, zonder code)
 */
export function trialRead(revisionId: number, file: File, linkId?: number): Promise<TrialReadResult> {
  const form = new FormData();
  form.append('file', file, file.name);
  if (linkId !== undefined) {
    form.append('linkId', String(linkId));
  }
  return request<TrialReadResult>(`/revisions/${revisionId}/trial-reads`, {
    method: 'POST',
    body: form,
  });
}
