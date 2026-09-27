/**
 * De enige plek die `fetch()` aanroept. Zie
 * `docs/design/frontend-scherm3-bundel-design.md` §3.1-3.2.
 */

const BASE = import.meta.env.VITE_API_BASE_URL ?? '/api/catalog-import';

/**
 * Bouwt de volledige URL voor `path` (relatief aan `BASE`), voor de zeldzame aanroeper die geen
 * `fetch()` via {@link request} kan gebruiken — bv. een directe downloadlink (`<a href>`) naar een
 * CSV-antwoord met sessiecookie, waar `fetch()` niet past. Gebruik dit nooit om zelf `fetch()` op te
 * roepen; dat blijft de taak van {@link request}/{@link requestWithStatus}.
 */
export function apiUrl(path: string): string {
  return `${BASE}${path}`;
}

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

let unauthenticatedHandler: (() => void) | null = null;

/** Registreert (of wist met `null`) de callback die bij een 401 op een actie de sessie als verlopen meldt. */
export function setUnauthenticatedHandler(handler: (() => void) | null): void {
  unauthenticatedHandler = handler;
}

let permissionDeniedHandler: (() => void) | null = null;

/**
 * Registreert (of wist met `null`) de callback die bij een 403 `PERMISSION_DENIED` de rechten opnieuw laadt
 * (`GET /me`): de UI-spiegel van de rechten is nooit de waarheid, ze kunnen ingetrokken zijn.
 */
export function setPermissionDeniedHandler(handler: (() => void) | null): void {
  permissionDeniedHandler = handler;
}

function isMutating(method: string | undefined): boolean {
  const upper = (method ?? 'GET').toUpperCase();
  return upper !== 'GET' && upper !== 'HEAD';
}

/** De ruwe waarde van cookie `XSRF-TOKEN` (Spring `CookieCsrfTokenRepository`), of `null`. */
function readCsrfToken(): string | null {
  for (const part of document.cookie.split(';')) {
    const trimmed = part.trim();
    if (trimmed.startsWith('XSRF-TOKEN=')) {
      return trimmed.substring('XSRF-TOKEN='.length);
    }
  }
  return null;
}

/**
 * Voert een API-verzoek uit tegen de CatalogImport-backend en levert het JSON-antwoord af, getypeerd
 * als `T`. Geen retry, geen timeout (zie §3.2 voor de motivering).
 */
export async function request<T>(path: string, init: RequestInit & { signal?: AbortSignal } = {}): Promise<T> {
  return (await requestWithStatus<T>(path, init)).body;
}

/**
 * Zoals {@link request}, maar levert ook de HTTP-statuscode af (nodig waar 200 en 201 verschillende
 * betekenis hebben, zoals bij `POST /tasks/{taskId}/deliveries`: 201 = nieuwe levering, 200 = idempotente
 * herhaling). Een `FormData`-body krijgt bewust géén `Content-Type`: de browser zet zelf
 * `multipart/form-data` mét de boundary.
 */
export async function requestWithStatus<T>(
  path: string,
  init: RequestInit & { signal?: AbortSignal } = {},
): Promise<{ status: number; body: T }> {
  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json');
  if (isMutating(init.method)) {
    const csrf = readCsrfToken();
    if (csrf !== null) {
      headers.set('X-XSRF-TOKEN', csrf);
    }
  }
  if (init.body !== undefined && init.body !== null && !(init.body instanceof FormData)) {
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
    return { status: 204, body: null as T };
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
    // `/me` handelt zijn eigen 401 af (login-redirect bij opstart); alle andere 401's = sessie verlopen.
    if (response.status === 401 && path !== '/me' && unauthenticatedHandler !== null) {
      unauthenticatedHandler();
    }
    // `/me` zelf wordt nooit met PERMISSION_DENIED geweigerd; de uitsluiting sluit een herlaadlus uit.
    if (
      response.status === 403 &&
      code === 'PERMISSION_DENIED' &&
      path !== '/me' &&
      permissionDeniedHandler !== null
    ) {
      permissionDeniedHandler();
    }
    throw new ApiError(response.status, code, backendMessage, path);
  }

  return { status: response.status, body: (await response.json()) as T };
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
