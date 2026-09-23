/**
 * De enige plek die `fetch()` aanroept. Zie
 * `docs/design/frontend-scherm3-bundel-design.md` §3.1-3.2.
 */

const BASE = import.meta.env.VITE_API_BASE_URL ?? '/api/catalog-import';

/**
 * Een mislukt of afgebroken API-verzoek. `status = 0` betekent een netwerkfout of een afgebroken
 * verzoek (bv. door `AbortController`), niet een antwoord van de server.
 */
export class ApiError extends Error {
  /** HTTP-status van het antwoord, of `0` bij een netwerkfout/afgebroken verzoek. */
  readonly status: number;
  /** De stabiele backendcode uit het `code`-veld, of `null` als het antwoord er geen droeg. */
  readonly code: string | null;
  /** Het `error`-veld: Engelse ontwikkelaarstekst, of `null` als er geen (leesbare) body was. */
  readonly backendMessage: string | null;
  /** Het pad dat geweigerd heeft (relatief aan de basis-URL). */
  readonly path: string;

  constructor(status: number, code: string | null, backendMessage: string | null, path: string) {
    super(backendMessage ?? `Request to ${path} failed with status ${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.backendMessage = backendMessage;
    this.path = path;
  }
}

type ErrorBody = { error?: unknown; code?: unknown };

/**
 * Voert een API-verzoek uit tegen de CatalogImport-backend en levert het JSON-antwoord af, getypeerd
 * als `T`. Geen retry, geen timeout (zie §3.2 voor de motivering).
 */
export async function request<T>(path: string, init: RequestInit & { signal?: AbortSignal } = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json');
  if (init.body !== undefined && init.body !== null) {
    headers.set('Content-Type', 'application/json');
  }

  let response: Response;
  try {
    response = await fetch(`${BASE}${path}`, { ...init, headers });
  } catch {
    // Netwerkfout of afgebroken verzoek (bv. AbortError): nooit een kale Error.
    throw new ApiError(0, null, null, path);
  }

  if (response.status === 204) {
    return null as T;
  }

  if (!response.ok) {
    let body: ErrorBody | null = null;
    try {
      body = (await response.json()) as ErrorBody;
    } catch {
      body = null;
    }
    const code = body && typeof body.code === 'string' ? body.code : null;
    const backendMessage = body && typeof body.error === 'string' ? body.error : null;
    throw new ApiError(response.status, code, backendMessage, path);
  }

  return (await response.json()) as T;
}

/**
 * Bouwt een querystring uit een plat object; `undefined`/`null`/lege-string waarden worden
 * weggelaten. Gedeeld door `api/bundles.ts` en `api/batches.ts` zodat elk endpoint dezelfde regel
 * volgt voor optionele filter-/paginaparameters.
 */
export function toQueryString(params: Record<string, string | number | boolean | undefined | null>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') {
      continue;
    }
    search.set(key, String(value));
  }
  const query = search.toString();
  return query === '' ? '' : `?${query}`;
}
