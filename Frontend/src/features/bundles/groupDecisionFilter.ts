/**
 * De omzetting van de zichtbare lijstfilter naar de filter van de groepsactie (bouwstap F9), zie
 * `docs/design/frontend-scherm3-bundel-design.md` §10.4 punt 1 en `docs/decisions.md` 2026-09-24
 * (C5: `identityHash` en `statusReason` horen in de groepsfilter).
 *
 * Eén pure functie, geen React, geen `api/`. De invoer is het filterobject dat `MutationList` zelf ook
 * voor zijn query gebruikt (`MutationListToolbarContext.filter`); er is geen tweede filterformulier.
 *
 * Waarom geen `{ ...filter }`: de backend negeert onbekende JSON-velden. Zou de lijst ooit een zesde
 * filter krijgen die `DecisionFilter` niet kent, dan zou een kale spread dat veld stil laten wegvallen
 * — en dan beslist de groepsactie over **meer** mutaties dan de lijst toont. De destructurering
 * hieronder laat de typecheck falen zodra `MutationFilter` een veld krijgt dat hier niet expliciet
 * overgenomen wordt.
 */

import type { DecisionFilter } from '../../api/types.ts';
import type { MutationFilter } from '../../components/MutationList/types.ts';

export function toDecisionFilter(filter: MutationFilter): DecisionFilter {
  const { batchId, status, statusReason, actionType, identityHash, ...rest } = filter;
  // Compile-time bewaking (zie hierboven): een niet-overgenomen veld maakt `rest` niet-leeg en dan is
  // deze toewijzing een typefout.
  const notTransferred: Record<string, never> = rest;
  void notTransferred;

  const result: DecisionFilter = {};
  if (batchId !== undefined) {
    result.batchId = batchId;
  }
  if (status !== undefined) {
    result.status = status;
  }
  if (statusReason !== undefined) {
    result.statusReason = statusReason;
  }
  if (actionType !== undefined) {
    result.actionType = actionType;
  }
  if (identityHash !== undefined) {
    result.identityHash = identityHash;
  }
  return result;
}

/** `true` zodra de filter geen enkel veld draagt — de backend weigert dat met `DECISION_FILTER_REQUIRED`. */
export function isEmptyDecisionFilter(filter: DecisionFilter): boolean {
  return (
    filter.batchId === undefined &&
    filter.status === undefined &&
    filter.actionType === undefined &&
    (filter.statusReason === undefined || filter.statusReason.trim() === '') &&
    (filter.identityHash === undefined || filter.identityHash.trim() === '')
  );
}
