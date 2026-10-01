/**
 * De statusmatrix van het behandelgeval (docs/design/issue-case-design.md §4, bouwstap S2-F2). Eén
 * **pure** functie, geen React, geen `api/` — spiegel van `IssueCase#recordHumanDecision`
 * (Domain), nooit de bron van waarheid: een 409 `ISSUE_CASE_TRANSITION_NOT_ALLOWED`/
 * `ISSUE_CASE_STATUS_CHANGED` van de server wordt altijd getoond, ook wanneer deze poort "toegestaan"
 * zei (patroon `bundlePolicy.ts`, A44).
 *
 * Door een mens zijn er drie acties (§4): "Corrigeren" en "Afwijzen" vanuit `AWAITING_REVIEW`, en
 * "Heropenen" vanuit `CORRECTED`/`REJECTED`/`AUTO_RESOLVED` — alle drie dezelfde overgang naar
 * `AWAITING_REVIEW`. Rechtstreeks tussen `CORRECTED` en `REJECTED` bestaat niet (eerst heropenen).
 */

import type { IssueCaseStatus } from '../../api/types.ts';
import { term } from '../../terms/index.ts';

/**
 * `code` is de stabiele technische code van de weigering (V7, NT-11c: altijd opvraagbaar, nooit in `reason`); de
 * knop toont hem klein in de tooltip via `gateTitle` uit `terms/gateTitle.ts`.
 */
export type Gate = { allowed: true } | { allowed: false; reason: string; code?: string };

const ALLOWED: Gate = { allowed: true };

/** De technische code van de server bij een niet-toegestane statusovergang van een behandelgeval. */
export const TRANSITION_NOT_ALLOWED_CODE = 'ISSUE_CASE_TRANSITION_NOT_ALLOWED';

function denied(reason: string, code?: string): Gate {
  return code === undefined ? { allowed: false, reason } : { allowed: false, reason, code };
}

/** De drie menselijke acties uit §4; "Heropenen" dekt alle drie de heropeningsovergangen met één knop. */
export const ISSUE_CASE_ACTIONS = ['CORRECT', 'REJECT', 'REOPEN'] as const;
export type IssueCaseAction = (typeof ISSUE_CASE_ACTIONS)[number];

const ACTION_LABELS: Record<IssueCaseAction, string> = {
  CORRECT: 'Corrigeren',
  REJECT: 'Afwijzen',
  REOPEN: 'Heropenen',
};

/** Het label zoals getoond op de knop/in de dialoogtitel voor deze actie. */
export function issueCaseActionLabel(action: IssueCaseAction): string {
  return ACTION_LABELS[action];
}

/** §4 — de doelstatus van deze actie; "Heropenen" gaat altijd terug naar `AWAITING_REVIEW`. */
export function issueCaseTargetStatus(action: IssueCaseAction): IssueCaseStatus {
  switch (action) {
    case 'CORRECT':
      return 'CORRECTED';
    case 'REJECT':
      return 'REJECTED';
    case 'REOPEN':
      return 'AWAITING_REVIEW';
  }
}

/**
 * §4 — mag deze actie aangeboden worden, uitgaande van de huidige (al geladen) status? "Corrigeren"/
 * "Afwijzen" enkel vanuit `AWAITING_REVIEW`; "Heropenen" enkel vanuit een status die geen
 * `AWAITING_REVIEW` is. De server bewaakt dit nog eens hard (`IssueCase#recordHumanDecision`,
 * `ISSUE_CASE_TRANSITION_NOT_ALLOWED`); deze poort bestaat om de knop vóór de klik al te sturen.
 */
export function issueCaseActionGate(status: IssueCaseStatus, action: IssueCaseAction): Gate {
  switch (action) {
    case 'CORRECT':
    case 'REJECT':
      if (status === 'AWAITING_REVIEW') {
        return ALLOWED;
      }
      return denied(
        `Kan niet: "${issueCaseActionLabel(action)}" kan alleen bij een geval dat op beoordeling wacht ` +
          `(dit geval staat op "${term('issueCaseStatus', status).label}"). Heropen het geval eerst.`,
        TRANSITION_NOT_ALLOWED_CODE,
      );
    case 'REOPEN':
      if (status === 'AWAITING_REVIEW') {
        return denied(
          'Kan niet: dit geval wacht al op beoordeling, heropenen is dan niet zinvol.',
          TRANSITION_NOT_ALLOWED_CODE,
        );
      }
      return ALLOWED;
  }
}

/** De acties die bij deze status aangeboden worden (ongeacht rechten), in de volgorde van §4. */
export function issueCaseActionsFor(status: IssueCaseStatus): readonly IssueCaseAction[] {
  return ISSUE_CASE_ACTIONS.filter((action) => issueCaseActionGate(status, action).allowed);
}
