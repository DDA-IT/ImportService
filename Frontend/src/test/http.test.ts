/**
 * T3 — `api/http.ts` — ApiError ontleedt `code`/`error` correct, 204 geeft null,
 * afbreken geeft `status = 0`.
 * Zie `docs/design/frontend-scherm3-bundel-design.md` §14.1.
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { request, ApiError } from '../api/http';

describe('ApiError and request()', () => {
  const originalFetch = global.fetch;

  beforeEach(() => {
    // Mock global fetch before each test
    global.fetch = vi.fn();
  });

  afterEach(() => {
    vi.clearAllMocks();
    global.fetch = originalFetch;
  });

  describe('T3.1: 404/409 with { error, code }', () => {
    it('should parse a 404 response with error and code', async () => {
      vi.mocked(global.fetch).mockResolvedValueOnce(
        new Response(JSON.stringify({ error: 'Not found', code: 'BUNDLE_NOT_FOUND' }), {
          status: 404,
          headers: { 'Content-Type': 'application/json' },
        })
      );

      try {
        await request('/bundles/999', {});
        expect.fail('Should have thrown ApiError');
      } catch (error) {
        expect(error).toBeInstanceOf(ApiError);
        const err = error as ApiError;
        expect(err.status).toBe(404);
        expect(err.code).toBe('BUNDLE_NOT_FOUND');
        expect(err.backendMessage).toBe('Not found');
        expect(err.path).toBe('/bundles/999');
      }
    });

    it('should parse a 409 response with error and code', async () => {
      vi.mocked(global.fetch).mockResolvedValueOnce(
        new Response(JSON.stringify({ error: 'Bundle not assembling', code: 'BUNDLE_NOT_ASSEMBLING' }), {
          status: 409,
          headers: { 'Content-Type': 'application/json' },
        })
      );

      try {
        await request('/bundles/42/batches', {});
        expect.fail('Should have thrown ApiError');
      } catch (error) {
        expect(error).toBeInstanceOf(ApiError);
        const err = error as ApiError;
        expect(err.status).toBe(409);
        expect(err.code).toBe('BUNDLE_NOT_ASSEMBLING');
        expect(err.backendMessage).toBe('Bundle not assembling');
      }
    });
  });

  describe('T3.2: 400 without code', () => {
    it('should parse a 400 response without code', async () => {
      vi.mocked(global.fetch).mockResolvedValueOnce(
        new Response(JSON.stringify({ error: 'Missing reason: rejecting mutations always requires one' }), {
          status: 400,
          headers: { 'Content-Type': 'application/json' },
        })
      );

      try {
        await request('/bundles/42/mutations/10/reject', {
          method: 'POST',
          body: JSON.stringify({}),
        });
        expect.fail('Should have thrown ApiError');
      } catch (error) {
        expect(error).toBeInstanceOf(ApiError);
        const err = error as ApiError;
        expect(err.status).toBe(400);
        expect(err.code).toBeNull();
        expect(err.backendMessage).toBe('Missing reason: rejecting mutations always requires one');
      }
    });
  });

  describe('T3.3: Non-JSON response body (HTML error page, empty body, etc.)', () => {
    it('should handle HTML error response gracefully', async () => {
      vi.mocked(global.fetch).mockResolvedValueOnce(
        new Response('<html><body>Internal Server Error</body></html>', {
          status: 500,
          headers: { 'Content-Type': 'text/html' },
        })
      );

      try {
        await request('/bundles', {});
        expect.fail('Should have thrown ApiError');
      } catch (error) {
        expect(error).toBeInstanceOf(ApiError);
        const err = error as ApiError;
        expect(err.status).toBe(500);
        expect(err.code).toBeNull();
        expect(err.backendMessage).toBeNull();
      }
    });

    it('should handle empty response body', async () => {
      vi.mocked(global.fetch).mockResolvedValueOnce(
        new Response('', {
          status: 500,
          headers: { 'Content-Type': 'application/json' },
        })
      );

      try {
        await request('/bundles', {});
        expect.fail('Should have thrown ApiError');
      } catch (error) {
        expect(error).toBeInstanceOf(ApiError);
        const err = error as ApiError;
        expect(err.status).toBe(500);
        expect(err.code).toBeNull();
        expect(err.backendMessage).toBeNull();
      }
    });
  });

  describe('T3.4: 204 No Content', () => {
    it('should return null for 204 response', async () => {
      vi.mocked(global.fetch).mockResolvedValueOnce(
        new Response(null, {
          status: 204,
          headers: { 'Content-Type': 'application/json' },
        })
      );

      const result = await request('/bundles/42/freeze', { method: 'POST', body: '{}' });
      expect(result).toBeNull();
    });
  });

  describe('T3.5: Network error and AbortError', () => {
    it('should handle network error (TypeError)', async () => {
      vi.mocked(global.fetch).mockRejectedValueOnce(new TypeError('Failed to fetch'));

      try {
        await request('/bundles', {});
        expect.fail('Should have thrown ApiError');
      } catch (error) {
        expect(error).toBeInstanceOf(ApiError);
        const err = error as ApiError;
        expect(err.status).toBe(0);
        expect(err.code).toBeNull();
        expect(err.backendMessage).toBeNull();
      }
    });

    it('should handle AbortError from AbortController', async () => {
      vi.mocked(global.fetch).mockRejectedValueOnce(new DOMException('Aborted', 'AbortError'));

      try {
        const controller = new AbortController();
        await request('/bundles', { signal: controller.signal });
        expect.fail('Should have thrown ApiError');
      } catch (error) {
        expect(error).toBeInstanceOf(ApiError);
        const err = error as ApiError;
        expect(err.status).toBe(0);
        expect(err.code).toBeNull();
        expect(err.backendMessage).toBeNull();
      }
    });

    it('should never throw a plain Error on network failure', async () => {
      vi.mocked(global.fetch).mockRejectedValueOnce(new Error('Some network error'));

      try {
        await request('/bundles', {});
        expect.fail('Should have thrown an error');
      } catch (error) {
        // Should be ApiError, not a plain Error
        expect(error).toBeInstanceOf(ApiError);
      }
    });
  });

  describe('T3: Happy path (200 OK)', () => {
    it('should return parsed JSON for 200 response', async () => {
      const mockData = { id: 1, bundleReference: 'TEST-001', status: 'ASSEMBLING' };
      vi.mocked(global.fetch).mockResolvedValueOnce(
        new Response(JSON.stringify(mockData), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      );

      const result = await request<{ id: number; bundleReference: string; status: string }>('/bundles/1', {});
      expect(result).toEqual(mockData);
    });
  });

  describe('ApiError class structure', () => {
    it('should have correct properties', () => {
      const err = new ApiError(409, 'SOME_CODE', 'Some error message', '/test/path');
      expect(err).toBeInstanceOf(Error);
      expect(err.name).toBe('ApiError');
      expect(err.status).toBe(409);
      expect(err.code).toBe('SOME_CODE');
      expect(err.backendMessage).toBe('Some error message');
      expect(err.path).toBe('/test/path');
    });

    it('should have proper error message when backendMessage is provided', () => {
      const err = new ApiError(404, 'NOT_FOUND', 'Bundle not found', '/bundles/999');
      expect(err.message).toBe('Bundle not found');
    });

    it('should have fallback error message when backendMessage is null', () => {
      const err = new ApiError(500, null, null, '/bundles');
      expect(err.message).toContain('/bundles');
      expect(err.message).toContain('500');
    });
  });
});
