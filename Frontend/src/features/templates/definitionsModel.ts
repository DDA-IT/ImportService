/**
 * Het zuivere model achter het overzicht Importdefinities en het importdefinitiedetail (R2): groepeert de bulklijsten
 * (organisaties, definities, koppelingen) tot één lijst van ALLE importdefinities (eigen `OWN_DEFINITION` en herbruikbaar
 * `REUSABLE_TEMPLATE`) en leidt per definitie de soort, de status en de herkomst af. Geen backend, geen React, geen tekst.
 *
 * - Status: `ACTIVE` als er een actieve versie is, anders `CONCEPT` (nog niet bruikbaar). "Inactief" uit de mock is niet af
 *   te leiden: een importdefinitie heeft geen eigen actief-vlag en de lijst geeft geen versiestatussen.
 * - Herkomst "Uit legacy (afgeleid)": de overname uit legacy maakt per interfacegroep een herbruikbare importdefinitie
 *   `<legacy-code>-SJ` en een gedeelde eigen importdefinitie `<legacy-code>` (en bij loskoppelen `LEGACY-<interface>`);
 *   zie `LegacyConfigApplyService`. Er is geen veld dat dit rechtstreeks zegt, dus het wordt uit de code afgeleid en zo gemarkeerd.
 */

import type { DefinitionRow, ImportLinkRow, SourceOrganisationRow } from '../../api/types.ts';
import { isOwnDefinition } from '../setup/setupDashboardModel.ts';

export type DefinitionStatus = 'ACTIVE' | 'CONCEPT';
export type DefinitionOrigin = 'LEGACY_DERIVED' | 'OWN';

/** Achtervoegsel van de herbruikbare importdefinitie die de overname uit legacy maakt. */
export const LEGACY_TEMPLATE_SUFFIX = '-SJ';
/** Voorvoegsel van een eigen importdefinitie die uit een gedeelde legacy-definitie is losgekoppeld. */
export const LEGACY_DETACHED_PREFIX = 'LEGACY-';

export type DefinitionEntry = {
  definition: DefinitionRow;
  organisation: SourceOrganisationRow | null;
  reusable: boolean;
  status: DefinitionStatus;
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
    reusable: number;
    own: number;
    activeReusable: number;
    activeOwn: number;
  };
};

export function definitionStatusOf(definition: DefinitionRow): DefinitionStatus {
  return definition.activeRevisionId !== null ? 'ACTIVE' : 'CONCEPT';
}

/** Is deze importdefinitie (afgeleid uit de code) uit de overname van legacy gekomen? */
export function isLegacyDerived(definition: DefinitionRow, all: readonly DefinitionRow[]): boolean {
  if (!isOwnDefinition(definition)) {
    return definition.code.endsWith(LEGACY_TEMPLATE_SUFFIX);
  }
  if (definition.code.startsWith(LEGACY_DETACHED_PREFIX)) {
    return true;
  }
  const templateCode = `${definition.code}${LEGACY_TEMPLATE_SUFFIX}`;
  return all.some(
    (other) =>
      !isOwnDefinition(other) && other.code === templateCode && other.sourceOrganisationId === definition.sourceOrganisationId,
  );
}

export function definitionOriginOf(definition: DefinitionRow, all: readonly DefinitionRow[]): DefinitionOrigin {
  return isLegacyDerived(definition, all) ? 'LEGACY_DERIVED' : 'OWN';
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
    origin: definitionOriginOf(definition, input.definitions),
    links: linksByDefinition.get(definition.id) ?? [],
  }));
  const count = (predicate: (entry: DefinitionEntry) => boolean) => entries.filter(predicate).length;
  return {
    entries,
    totals: {
      all: entries.length,
      active: count((entry) => entry.status === 'ACTIVE'),
      concept: count((entry) => entry.status === 'CONCEPT'),
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
  /** '' = alle, 'ACTIVE' of 'CONCEPT'. */
  status: string;
  /** '' = alle, 'LEGACY_DERIVED' of 'OWN'. */
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
    if (filters.status !== '' && entry.status !== filters.status) {
      return false;
    }
    if (filters.origin !== '' && entry.origin !== filters.origin) {
      return false;
    }
    return true;
  });
}
