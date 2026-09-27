/**
 * 5A-3 — CSRF-header in `api/http.ts`: `X-XSRF-TOKEN` (ruwe cookiewaarde van `XSRF-TOKEN`) bij elke
 * niet-GET/HEAD-aanroep, nooit bij GET. Zie `docs/design/fase5-auth-design.md` §6.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { request, requestWithStatus } from '../api/http';

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function clearCookie() {
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
}

describe('api/http — CSRF-header', () => {
  const originalFetch = global.fetch;

  beforeEach(() => {
    clearCookie();
    global.fetch = vi.fn(() => Promise.resolve(json({ ok: true }))) as unknown as typeof fetch;
  });

  afterEach(() => {
    clearCookie();
    global.fetch = originalFetch;
  });

  function sentHeaders(): Headers {
    return new Headers(vi.mocked(global.fetch).mock.calls[0]![1]?.headers);
  }

  it('POST stuurt X-XSRF-TOKEN met de ruwe cookiewaarde (niet gedecodeerd)', async () => {
    document.cookie = 'XSRF-TOKEN=abc%3D-def; path=/';
    await request('/bundles/1/freeze', { method: 'POST', body: JSON.stringify({}) });
    expect(sentHeaders().get('X-XSRF-TOKEN')).toBe('abc%3D-def');
  });

  it.each(['PUT', 'DELETE', 'PATCH', 'post'])('%s stuurt de header ook', async (method) => {
    document.cookie = 'XSRF-TOKEN=tok; path=/';
    await request('/x', { method });
    expect(sentHeaders().get('X-XSRF-TOKEN')).toBe('tok');
  });

  it('multipart-POST (FormData) stuurt de header en geen Content-Type', async () => {
    document.cookie = 'XSRF-TOKEN=tok; path=/';
    await requestWithStatus('/tasks/5/deliveries', { method: 'POST', body: new FormData() });
    expect(sentHeaders().get('X-XSRF-TOKEN')).toBe('tok');
    expect(sentHeaders().has('Content-Type')).toBe(false);
  });

  it('GET (expliciet of standaard) stuurt de header niet, ook niet met cookie', async () => {
    document.cookie = 'XSRF-TOKEN=tok; path=/';
    await request('/bundles');
    expect(sentHeaders().has('X-XSRF-TOKEN')).toBe(false);

    vi.mocked(global.fetch).mockClear();
    await request('/bundles', { method: 'GET' });
    expect(sentHeaders().has('X-XSRF-TOKEN')).toBe(false);
  });

  it('POST zonder cookie stuurt geen header (geen verzonnen waarde)', async () => {
    await request('/x', { method: 'POST' });
    expect(sentHeaders().has('X-XSRF-TOKEN')).toBe(false);
  });
});
