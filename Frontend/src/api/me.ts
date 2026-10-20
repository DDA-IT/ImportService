/**
 * `GET /me` en logout, zie `docs/design/fase5-auth-design.md` §5-§6.
 * `permissions` (5-PERM) is altijd een lijst van EFFECTIEVE rechtcodes (de backend past de hiërarchie toe);
 * `[]` = geen rechten. De SPA houdt geen eigen hiërarchie bij. `subject` wordt niet getoond (A6), enkel bewaard in de context.
 */

import { request } from './http.ts';

export type ProductAppId = 'prodis' | 'profin' | 'prodev';

/** Een andere app voor de productwisselaar; naam, logo en ondertitel kent de client (`layout/ProductSwitcher`). */
export type ProductApp = { id: ProductAppId; url: string };

export type Me = {
  username: string;
  subject: string;
  displayName: string | null;
  permissions: string[];
  /** Andere apps voor de wisselaar (huidige app niet inbegrepen); ontbreekt = geen. */
  apps?: ProductApp[];
};

export function getMe(signal?: AbortSignal): Promise<Me> {
  return request<Me>('/me', { signal });
}

/** `POST /logout` (met CSRF); antwoordt 200 met de end-session-URL van Keycloak. */
export function logout(): Promise<{ logoutUrl: string }> {
  return request<{ logoutUrl: string }>('/logout', { method: 'POST' });
}
