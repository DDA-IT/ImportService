/**
 * De vier schrijfpaden op een importdefinitierevisie van
 * `be.dda.catalogimport.web.CatalogImportSetupController` (S1-F4, het schrijfdeel van scherm 1a —
 * `docs/design/revision-successor-design.md` §6, endpoints E2 t/m E5).
 *
 * **Bewust een apart bestand van `api/setup.ts`.** Dat bestand bedient
 * `CatalogImportSetupQueryController` (de leeslijsten en sinds S1-X-3 het revisiedetail E1), dat altijd
 * bereikbaar is; deze controller staat volledig achter `catalogimport.setup-api.enabled` (A1:
 * schrijfpaden blijven achter de vlag) en geeft met de vlag uit 404 zonder `code`. Dezelfde scheiding
 * als tussen `api/importLinks.ts` (vlagloos) en `api/links.ts` (achter de vlag); ze samenvoegen zou dat
 * verschil in bereikbaarheid onzichtbaar maken.
 *
 * Alle vier vragen recht `MANAGE`. Geen enkele is idempotent gemaakt in de client: `useAction` herhaalt
 * nooit automatisch, want E2 zou bij een tweede poging een tweede DRAFT willen maken (409
 * `REVISION_DRAFT_ALREADY_EXISTS`) en E5 een tweede activatie (409 `REVISION_NOT_ACTIVATABLE`).
 *
 * **Harde ontwerpgrens (§2):** er is en komt hier géén functie om bookmarkdeclaraties toe te voegen, te
 * wijzigen of te verwijderen. Zo is per constructie bewijsbaar dat een opvolgrevisie nooit een bestaande
 * LINK-bookmarkwaarde tot wees maakt.
 */
import { request } from './http.ts';
import type {
  ActivateRevisionRequest,
  CreateMappingRequest,
  CreateSuccessorRequest,
  FilterView,
  MappingView,
  RevisionView,
  UpdateRevisionRequest,
} from './types.ts';

/**
 * POST /setup/revisions/{revisionId}/successor — CatalogImportSetupController.createSuccessor (201).
 *
 * Kloont een `ACTIVE` of `SUPERSEDED` revisie naar een nieuwe `DRAFT` binnen dezelfde definitie, met
 * alle configuratievelden en alle vijf de kindtabellen verbatim mee (§1/§2). Activeert niets: de kloon
 * is byte-identiek, de betekenis verandert pas door een latere wijziging in die DRAFT (§5, R-REV-X1).
 *
 * @throws {import('./http.ts').ApiError} 400 `CHANGE_REASON_REQUIRED`; 404 `REVISION_NOT_FOUND`; 409
 *   `REVISION_NOT_CLONEABLE` (bronstatus is geen ACTIVE/SUPERSEDED),
 *   `REVISION_DRAFT_ALREADY_EXISTS` (er staat al een DRAFT open op deze definitie — O1, hoogstens één)
 */
export function createSuccessor(
  revisionId: number,
  body: CreateSuccessorRequest,
  signal?: AbortSignal,
): Promise<RevisionView> {
  return request<RevisionView>(`/setup/revisions/${revisionId}/successor`, {
    method: 'POST',
    body: JSON.stringify(body),
    signal,
  });
}

/**
 * PATCH /setup/revisions/{revisionId} — CatalogImportSetupController.updateRevision (200).
 *
 * Wijzigt de scalaire velden van een `DRAFT`; elk `null`-veld blijft ongewijzigd. De server herberekent
 * daarna de vier configuratiehashes. Er is geen `updated_by`-kolom: `createdBy` is enkel een controle
 * tegen de aangemelde gebruiker, geen audit van wie de DRAFT wijzigde (ontwerp §7).
 *
 * @throws {import('./http.ts').ApiError} 400 bij een lege verplichte of ongeldige waarde (een
 *   configuratiefout draagt haar `CONFIG_*`-code); 404 `REVISION_NOT_FOUND`; 409
 *   `REVISION_NOT_EDITABLE` (alleen een DRAFT is bewerkbaar),
 *   `REVISION_CANONICALISATION_CHANGE_BLOCKED` (R-REV-X2 — **onvoorwaardelijk**, geen bevestiging heft
 *   dit op), `IDENTITY_CHANGE_NOT_ACKNOWLEDGED` (R-REV-X3 — wél op te heffen met
 *   `acknowledgeIdentityChange: true`). In elk foutgeval is er niets opgeslagen.
 */
export function updateRevision(
  revisionId: number,
  body: UpdateRevisionRequest,
  signal?: AbortSignal,
): Promise<RevisionView> {
  return request<RevisionView>(`/setup/revisions/${revisionId}`, {
    method: 'PATCH',
    body: JSON.stringify(body),
    signal,
  });
}

/**
 * DELETE /setup/revisions/{revisionId}/mappings/{mappingId} —
 * CatalogImportSetupController.deleteMapping (204, geen inhoud).
 *
 * Zonder dit pad kon een geërfde mapping van een opvolgrevisie alleen nog toegevoegd, nooit verwijderd
 * worden. Ná het verwijderen hercontroleert de server de bookmarkdeclaratie van deze revisie: steunde
 * een declaratie op deze mapping, dan is er niets verwijderd.
 *
 * @throws {import('./http.ts').ApiError} 404 `REVISION_NOT_FOUND`, `MAPPING_NOT_FOUND` (onbekend of van
 *   een andere revisie); 409 `REVISION_NOT_EDITABLE`, of een bestaande `CONFIG_BOOKMARK_*`-code
 *   (typisch `CONFIG_BOOKMARK_PLACE_UNRESOLVED`) — dan is er niets verwijderd
 */
export function deleteMapping(revisionId: number, mappingId: number, signal?: AbortSignal): Promise<void> {
  return request<void>(`/setup/revisions/${revisionId}/mappings/${mappingId}`, {
    method: 'DELETE',
    signal,
  });
}

/**
 * PUT /setup/revisions/{revisionId}/mappings/{mappingId} — CatalogImportSetupController.replaceMapping (200, G2c-1).
 *
 * Vervangt één extra veld van een `DRAFT` door de nieuwe configuratie: de server verwijdert de oude rij en voegt de nieuwe
 * toe in één transactie, met hetzelfde volgnummer maar een **nieuw id**. Bij een fout is er niets gewijzigd.
 *
 * @throws {import('./http.ts').ApiError} 400 bij een ongeldige configuratie (`CONFIG_TRANSFORM_INVALID`, `VALUE_MAP_*`);
 *   404 `REVISION_NOT_FOUND`, `MAPPING_NOT_FOUND`; 409 `REVISION_NOT_EDITABLE` of een `CONFIG_BOOKMARK_*`-code
 */
export function replaceMapping(
  revisionId: number,
  mappingId: number,
  body: CreateMappingRequest,
  signal?: AbortSignal,
): Promise<MappingView> {
  return request<MappingView>(`/setup/revisions/${revisionId}/mappings/${mappingId}`, {
    method: 'PUT',
    body: JSON.stringify(body),
    signal,
  });
}

/**
 * POST /setup/revisions/{revisionId}/mappings — CatalogImportSetupController.addMapping (201, E2).
 *
 * Voegt één extra veld toe aan een `DRAFT`; met `sequenceNumber: null` kiest de server het volgende volgnummer. Een doelveld
 * heeft precies één bron: staat het al in deze revisie, dan is er niets toegevoegd.
 *
 * @throws {import('./http.ts').ApiError} 400 bij een ongeldige configuratie (`CONFIG_TRANSFORM_INVALID`, `VALUE_MAP_*`);
 *   404 `REVISION_NOT_FOUND`, `FIELD_NOT_FOUND`; 409 `REVISION_NOT_EDITABLE`, `MAPPING_TARGET_IN_USE`,
 *   `MAPPING_SEQUENCE_IN_USE` of een `CONFIG_BOOKMARK_*`-code
 */
export function addMapping(revisionId: number, body: CreateMappingRequest, signal?: AbortSignal): Promise<MappingView> {
  return request<MappingView>(`/setup/revisions/${revisionId}/mappings`, {
    method: 'POST',
    body: JSON.stringify(body),
    signal,
  });
}

/**
 * DELETE /setup/revisions/{revisionId}/filters/{filterId} — CatalogImportSetupController.deleteFilter
 * (204). Zelfde regels en dezelfde hercontrole als {@link deleteMapping}.
 *
 * @throws {import('./http.ts').ApiError} 404 `REVISION_NOT_FOUND`, `FILTER_NOT_FOUND`; 409
 *   `REVISION_NOT_EDITABLE` of een `CONFIG_BOOKMARK_*`-code
 */
export function deleteFilter(revisionId: number, filterId: number, signal?: AbortSignal): Promise<void> {
  return request<void>(`/setup/revisions/${revisionId}/filters/${filterId}`, {
    method: 'DELETE',
    signal,
  });
}

/**
 * POST /setup/revisions/{revisionId}/activate — CatalogImportSetupController.activateRevision (200).
 *
 * Zet de `DRAFT` op `ACTIVE` en de vorige actieve revisie van dezelfde definitie op `SUPERSEDED`, in
 * één transactie, ná een volledige configuratievalidatie met dezelfde fabrieken als de screening.
 *
 * **R-CASE-03 (`docs/design/issue-case-design.md` §2, ontwerp §4).** Activeren heropent bij de
 * eerstvolgende waarneming alle afgewezen behandelgevallen van alle koppelingen van deze definitie. Dat
 * is een bewust aanvaard gevolg, geen fout — maar de UI moet het vóór de bevestiging melden.
 *
 * @throws {import('./http.ts').ApiError} 400 bij een `CONFIG_*`-fout in de configuratie; 404
 *   `REVISION_NOT_FOUND`; 409 `REVISION_NOT_ACTIVATABLE` (al ACTIVE of geen DRAFT),
 *   `CONFIG_REQUIRED_BOOKMARK_MISSING`, `REVISION_ACTIVATION_CONFLICT` (gelijktijdige activatie — er is
 *   dan niets geactiveerd en niets op SUPERSEDED gezet)
 */
export function activateRevision(
  revisionId: number,
  body: ActivateRevisionRequest,
  signal?: AbortSignal,
): Promise<RevisionView> {
  return request<RevisionView>(`/setup/revisions/${revisionId}/activate`, {
    method: 'POST',
    body: JSON.stringify(body),
    signal,
  });
}

/** Body van `POST /setup/revisions/{rid}/filters` (`SetupService.CreateFilterCommand`); weggelaten velden krijgen de serverstandaard. */
export type CreateFilterRequest = {
  sequenceNumber?: number | null;
  sourceReference: string;
  operator: string;
  compareValue: string | null;
  outcome: string;
  caseSensitive?: boolean;
  trimBeforeCompare?: boolean;
  nullBehaviour?: string;
  missingColumnBehaviour?: string;
  createdBy: string | null;
  matchGroup?: number | null;
};

/**
 * POST /setup/revisions/{revisionId}/filters — CatalogImportSetupController.addFilter (201, G2c-4).
 *
 * Voegt één filterrij toe aan een `DRAFT`. Met `matchGroup` is de rij deel van een uitsluitingsgroep (enkel `EXCLUDE` +
 * `COMPARE_AS_EMPTY`; bij `EQUALS` betekent een lege vergelijkwaarde "kolom is leeg").
 *
 * @throws {import('./http.ts').ApiError} 400 `MATCH_GROUP_INVALID`, `CONFIG_FILTER_INVALID`, `COMPARE_VALUE_REQUIRED`;
 *   404 `REVISION_NOT_FOUND`; 409 `REVISION_NOT_EDITABLE`, `FILTER_SEQUENCE_IN_USE` of een `CONFIG_BOOKMARK_*`-code
 */
export function addFilter(revisionId: number, body: CreateFilterRequest, signal?: AbortSignal): Promise<FilterView> {
  return request<FilterView>(`/setup/revisions/${revisionId}/filters`, {
    method: 'POST',
    body: JSON.stringify(body),
    signal,
  });
}
