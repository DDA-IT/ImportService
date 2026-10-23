/**
 * CD-B2 — de aanmaakstandaard (btw-code en eenheid) van een koppeling en de vlag "standaarden overschrijven" van een
 * importdefinitie (`CatalogImportSetupController`, recht `MANAGE`; beslissingslog 2026-10-23 "Aanmaakstandaard per
 * koppeling: uitwerking (CD)"). Niet idempotent gemaakt in de client: `useAction` herhaalt nooit automatisch.
 */
import { request } from './http.ts';
import type { DefinitionOverwriteFlagView, LinkCreationDefaultsView, SetCreationDefaultsRequest } from './types.ts';

/**
 * PUT /setup/links/{linkId}/creation-defaults — zet beide waarden (`null` wist ze) en de herkomst op handmatig.
 *
 * @throws {import('./http.ts').ApiError} 400 `VAT_CODE_TOO_LONG`, `UNIT_CODE_TOO_LONG`, `PRODIS_VAT_CODE_NOT_FOUND`,
 *   `PRODIS_UNIT_NOT_FOUND`; 404 `LINK_NOT_FOUND`; 503 `PRODIS_DB_UNAVAILABLE`
 */
export function setLinkCreationDefaults(
  linkId: number,
  body: SetCreationDefaultsRequest,
  signal?: AbortSignal,
): Promise<LinkCreationDefaultsView> {
  return request<LinkCreationDefaultsView>(`/setup/links/${linkId}/creation-defaults`, {
    method: 'PUT',
    body: JSON.stringify(body),
    signal,
  });
}

/**
 * PUT /setup/definitions/{definitionId}/overwrite-creation-defaults — zet de vlag van de importdefinitie.
 *
 * @throws {import('./http.ts').ApiError} 400 `ENABLED_REQUIRED`; 404 `DEFINITION_NOT_FOUND`
 */
export function setOverwriteCreationDefaults(
  definitionId: number,
  enabled: boolean,
  signal?: AbortSignal,
): Promise<DefinitionOverwriteFlagView> {
  return request<DefinitionOverwriteFlagView>(`/setup/definitions/${definitionId}/overwrite-creation-defaults`, {
    method: 'PUT',
    body: JSON.stringify({ enabled }),
    signal,
  });
}
