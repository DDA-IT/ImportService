/**
 * S9-e — de pure filter- en querykant van `MutationList`: de filterstaat, het samenstellen van het
 * `MutationFilter` uit de toegepaste filter en de querysleutel. Geen React.
 */

import type { MutationFilter, MutationFilterName, MutationQuery } from './types.ts';

export type FilterState = {
  status: string;
  batchId: string;
  actionType: string;
  statusReason: string;
  identityHash: string;
};

export const EMPTY_FILTERS: FilterState = { status: '', batchId: '', actionType: '', statusReason: '', identityHash: '' };

export function initialFilters(initial: Partial<MutationQuery> | undefined): FilterState {
  return {
    status: initial?.status ?? '',
    batchId: initial?.batchId === undefined ? '' : String(initial.batchId),
    actionType: initial?.actionType ?? '',
    statusReason: initial?.statusReason ?? '',
    identityHash: initial?.identityHash ?? '',
  };
}

/**
 * De toegepaste filter: enkel de filters die de bron ondersteunt (§11.3 punt 5), lege waarden weggelaten zodat
 * een blanco filter nooit als parameter meereist. Vroeger een closure in het component; `applied` en de
 * ondersteunde filters (`source.supportedFilters`) zijn nu parameters.
 */
export function buildFilter(
  applied: FilterState,
  supportedFilters: ReadonlyArray<MutationFilterName>,
): MutationFilter {
  const supports = (name: keyof FilterState) => supportedFilters.includes(name);
  const next: MutationFilter = {};
  if (supports('status') && applied.status !== '') {
    next.status = applied.status as NonNullable<MutationQuery['status']>;
  }
  if (supports('batchId') && applied.batchId.trim() !== '') {
    const parsed = Number(applied.batchId);
    // Onbruikbare invoer wordt niet stil op 0 gezet (AGENT.md §2 principe 3): dan gaat er gewoon geen
    // batchId-filter mee en meldt de balk dat het getal ongeldig is.
    if (Number.isInteger(parsed) && parsed > 0) {
      next.batchId = parsed;
    }
  }
  if (supports('actionType') && applied.actionType !== '') {
    next.actionType = applied.actionType as NonNullable<MutationQuery['actionType']>;
  }
  if (supports('statusReason') && applied.statusReason.trim() !== '') {
    next.statusReason = applied.statusReason.trim();
  }
  if (supports('identityHash') && applied.identityHash.trim() !== '') {
    next.identityHash = applied.identityHash.trim();
  }
  return next;
}

/** De sleutel waaronder `useQuery` het resultaat van deze bron + query bewaart. */
export function mutationQueryKey(sourceKey: string, query: MutationQuery): string {
  return [
    'mutations',
    sourceKey,
    query.status ?? '',
    query.batchId ?? '',
    query.actionType ?? '',
    query.statusReason ?? '',
    query.identityHash ?? '',
    query.page,
    query.size,
  ].join('|');
}
