/**
 * F8 — de filterdoorgifte van de twee mutatielijst-endpoints in de API-laag.
 *
 * Beide lijsten leveren hetzelfde `PageResult<MutationRow>` (dat is wat het herbruikbare component
 * mogelijk maakt), maar ze aanvaarden **niet** dezelfde filters: `GET /batches/{id}/mutations` kent
 * geen `batchId` (die staat al in het pad). Deze test bewaakt de query-parameternamen 1:1 met
 * `CatalogImportBundleController.mutations` / `CatalogImportBatchController.mutations`, en dat lege
 * waarden nooit als parameter meereizen.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { bundleMutations } from '../api/bundles';
import { batchMutations } from '../api/batches';

function emptyPage(): Response {
  return new Response(JSON.stringify({ content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

function calledUrl(): string {
  const call = vi.mocked(global.fetch).mock.calls[0];
  return call?.[0]?.toString() ?? '';
}

describe('mutatielijst-filters in de API-laag', () => {
  const originalFetch = global.fetch;

  beforeEach(() => {
    global.fetch = vi.fn(() => Promise.resolve(emptyPage())) as unknown as typeof fetch;
  });

  afterEach(() => {
    vi.clearAllMocks();
    global.fetch = originalFetch;
  });

  it('F8-API.1: bundleMutations geeft alle zes parameters door', async () => {
    await bundleMutations(42, {
      status: 'AWAITING_APPROVAL',
      batchId: 77,
      actionType: 'UPDATE',
      statusReason: 'BULK_PRICE_INCIDENT',
      identityHash: 'abc123',
      page: 2,
      size: 100,
    });

    const url = calledUrl();
    expect(url).toContain('/bundles/42/mutations?');
    expect(url).toContain('status=AWAITING_APPROVAL');
    expect(url).toContain('batchId=77');
    expect(url).toContain('actionType=UPDATE');
    expect(url).toContain('statusReason=BULK_PRICE_INCIDENT');
    expect(url).toContain('identityHash=abc123');
    expect(url).toContain('page=2');
    expect(url).toContain('size=100');
  });

  it('F8-API.2: bundleMutations laat weggelaten en lege filters weg', async () => {
    await bundleMutations(42, { statusReason: '', identityHash: '', page: 0, size: 50 });

    const url = calledUrl();
    expect(url).toBe('/api/catalog-import/bundles/42/mutations?page=0&size=50');
  });

  it('F8-API.3: batchMutations geeft status, statusReason, actionType en identityHash door', async () => {
    await batchMutations(17, {
      status: 'PLANNED',
      statusReason: 'BULK_PRICE_INCIDENT',
      actionType: 'CREATE',
      identityHash: 'FF00',
      page: 0,
      size: 25,
    });

    const url = calledUrl();
    expect(url).toContain('/batches/17/mutations?');
    expect(url).toContain('status=PLANNED');
    expect(url).toContain('statusReason=BULK_PRICE_INCIDENT');
    expect(url).toContain('actionType=CREATE');
    // Hoofdletterongevoelig aan de serverkant (C4); de client stuurt de waarde ongewijzigd door.
    expect(url).toContain('identityHash=FF00');
    expect(url).not.toContain('batchId=');
  });

  it('F8-API.4: batchMutations zonder filters vraagt alleen de paginering op', async () => {
    await batchMutations(17, { page: 1, size: 200 });

    expect(calledUrl()).toBe('/api/catalog-import/batches/17/mutations?page=1&size=200');
  });
});
