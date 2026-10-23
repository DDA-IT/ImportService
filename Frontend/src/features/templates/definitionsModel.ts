/**
 * Het zuivere model achter het overzicht Importdefinities en het importdefinitiedetail (R2): groepeert de bulklijsten
 * (organisaties, definities, koppelingen) tot één lijst van ALLE importdefinities (eigen `OWN_DEFINITION` en herbruikbaar
 * `REUSABLE_TEMPLATE`) en leidt per definitie de soort, de status en de herkomst af. Geen backend, geen React, geen tekst.
 *
 * Bron: beslissingslog 2026-10-23 "API-uitbreiding voor de inrichtingsschermen", punt 5 (backend B1).
 * - Status: `ACTIVE` als er een actieve versie is (met `draftRevisionNumber` als er daarnaast een concept openstaat),
 *   `CONCEPT` als er enkel een concept is, anders `NO_VERSION` (geen bruikbare versie). Ontbreekt `draftRevisionId` (oudere
 *   server), dan telt het als "geen concept".
 * - "Inactief" is geen status maar een aparte vlag: de organisatie van de importdefinitie is inactief
 *   (`sourceOrganisationActive === false`).
 * - Herkomst "Uit legacy": `legacyOriginKind` is niet `null`. De vroegere afleiding uit de code (`-SJ`, `LEGACY-`) is vervallen.
 */

import type { DefinitionRow, ImportLinkRow, LegacyOriginKind, SourceOrganisationRow } from '../../api/types.ts';
import { isOwnDefinition } from '../setup/setupDashboardModel.ts';

export type DefinitionStatus = 'ACTIVE' | 'CONCEPT' | 'NO_VERSION';
export type DefinitionOrigin = 'LEGACY' | 'OWN';

export type DefinitionEntry = {
  definition: DefinitionRow;
  organisation: SourceOrganisationRow | null;
  reusable: boolean;
  status: DefinitionStatus;
  /** De organisatie van de importdefinitie is inactief. */
  inactive: boolean;
  origin: DefinitionOrigin;
  /** Enkel de koppelingen van deze definitie (een herbruikbare krijgt er nooit zelf). */
  links: ImportLinkRow[];
};

export type DefinitionOverview = {
  entries: DefinitionEntry[];
  totals: {
    all: number;
    active: number;
    concept: number;
    noVersion: number;
    reusable: number;
    own: number;
    activeReusable: number;
    activeOwn: number;
  };
};

export function definitionStatusOf(definition: DefinitionRow): DefinitionStatus {
  if (definition.activeRevisionId !== null) {
    return 'ACTIVE';
  }
  return definition.draftRevisionId !== undefined && definition.draftRevisionId !== null ? 'CONCEPT' : 'NO_VERSION';
}

/** Het versienummer van het openstaande concept naast een actieve versie ("concept v4 open"), anders `null`. */
export function openDraftNumberOf(definition: DefinitionRow): number | null {
  return definitionStatusOf(definition) === 'ACTIVE' ? (definition.draftRevisionNumber ?? null) : null;
}

export function isInactiveOrganisation(definition: DefinitionRow): boolean {
  return definition.sourceOrganisationActive === false;
}

/** De soort legacy-overname, of `null` als de importdefinitie niet uit legacy komt. */
export function legacyKindOf(definition: DefinitionRow): LegacyOriginKind | null {
  return definition.legacyOriginKind ?? null;
}

export function definitionOriginOf(definition: DefinitionRow): DefinitionOrigin {
  return legacyKindOf(definition) !== null ? 'LEGACY' : 'OWN';
}

export function buildDefinitionOverview(input: {
  organisations: readonly SourceOrganisationRow[];
  definitions: readonly DefinitionRow[];
  links: readonly ImportLinkRow[];
}): DefinitionOverview {
  const organisations = new Map(input.organisations.map((organisation) => [organisation.id, organisation]));
  const linksByDefinition = new Map<number, ImportLinkRow[]>();
  for (const link of input.links) {
    const bucket = linksByDefinition.get(link.importDefinitionId);
    if (bucket === undefined) {
      linksByDefinition.set(link.importDefinitionId, [link]);
    } else {
      bucket.push(link);
    }
  }
  const entries: DefinitionEntry[] = input.definitions.map((definition) => ({
    definition,
    organisation: organisations.get(definition.sourceOrganisationId) ?? null,
    reusable: !isOwnDefinition(definition),
    status: definitionStatusOf(definition),
    inactive: isInactiveOrganisation(definition),
    origin: definitionOriginOf(definition),
    links: linksByDefinition.get(definition.id) ?? [],
  }));
  const count = (predicate: (entry: DefinitionEntry) => boolean) => entries.filter(predicate).length;
  return {
    entries,
    totals: {
      all: entries.length,
      active: count((entry) => entry.status === 'ACTIVE'),
      concept: count((entry) => entry.status === 'CONCEPT'),
      noVersion: count((entry) => entry.status === 'NO_VERSION'),
      reusable: count((entry) => entry.reusable),
      own: count((entry) => !entry.reusable),
      activeReusable: count((entry) => entry.reusable && entry.status === 'ACTIVE'),
      activeOwn: count((entry) => !entry.reusable && entry.status === 'ACTIVE'),
    },
  };
}

export type DefinitionFilters = {
  query: string;
  /** '' = alle, 'REUSABLE' of 'OWN'. */
  kind: string;
  /** '' = alle, 'ACTIVE', 'CONCEPT', 'NO_VERSION' of 'INACTIVE' (organisatie inactief; los van de versiestatus). */
  status: string;
  /** '' = alle, 'LEGACY' of 'OWN'. */
  origin: string;
};

export const NO_FILTERS: DefinitionFilters = { query: '', kind: '', status: '', origin: '' };

export function filterDefinitions(entries: readonly DefinitionEntry[], filters: DefinitionFilters): DefinitionEntry[] {
  const query = filters.query.trim().toLowerCase();
  return entries.filter((entry) => {
    const { definition } = entry;
    if (query !== '' && !definition.code.toLowerCase().includes(query) && !definition.name.toLowerCase().includes(query)) {
      return false;
    }
    if (filters.kind !== '' && (filters.kind === 'REUSABLE') !== entry.reusable) {
      return false;
    }
    if (filters.status === 'INACTIVE') {
      if (!entry.inactive) {
        return false;
      }
    } else if (filters.status !== '' && entry.status !== filters.status) {
      return false;
    }
    if (filters.origin !== '' && entry.origin !== filters.origin) {
      return false;
    }
    return true;
  });
}
