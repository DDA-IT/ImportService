/**
 * Hoeveel mutaties vervallen er als deze bundel geannuleerd wordt? (§10.6: "met het aantal mutaties dat
 * vervalt".) De backend levert dat getal niet als veld of endpoint; deze module stelt het vast met het
 * patroon van §9.3 — goedkope `size=1`-aanroepen op `GET /bundles/{id}/mutations` en hun
 * `totalElements`.
 *
 * De selectie is een letterlijke spiegel van `PublicationBundleDao.EXPIRABLE_TAIL` (bouwstap 4f):
 * `action_type in ('CREATE','UPDATE') and status in ('PLANNED','AWAITING_APPROVAL','READY_FOR_PUBLICATION')`,
 * over de actieve leden van de bundel — dezelfde leden waarop de mutatielijst filtert. Daarom per
 * status **én** per soort geteld: een `IDENTITY_REFERENCE_INCIDENT` kan op `AWAITING_APPROVAL` staan maar
 * vervalt niet, en een telling op status alleen zou hem ten onrechte meetellen.
 *
 * Een momentopname, net als de voorvlucht van het bevriezen: `POST /cancel` telt zelf opnieuw en schrijft
 * het werkelijke aantal op de `CANCEL`-regel van het beslissingsregister. Mislukt één van de tellingen,
 * dan mislukt het geheel — een gedeeltelijke som wordt nooit als totaal getoond.
 */

import * as bundlesApi from '../../api/bundles.ts';
import type { MutationActionType, MutationStatus } from '../../api/types.ts';

/** De drie niet-terminale statussen die bij annuleren `EXPIRED` worden (R-FRZ-10). */
export const EXPIRING_STATUSES = ['PLANNED', 'AWAITING_APPROVAL', 'READY_FOR_PUBLICATION'] as const satisfies readonly MutationStatus[];
export type ExpiringStatus = (typeof EXPIRING_STATUSES)[number];

/** De twee inhoudelijke soorten die bij annuleren kunnen vervallen. */
export const EXPIRING_ACTION_TYPES = ['CREATE', 'UPDATE'] as const satisfies readonly MutationActionType[];

export type ExpiringCounts = {
  byStatus: Record<ExpiringStatus, number>;
  total: number;
};

export async function loadExpiringCounts(bundleId: number, signal?: AbortSignal): Promise<ExpiringCounts> {
  const pairs = EXPIRING_STATUSES.flatMap((status) =>
    EXPIRING_ACTION_TYPES.map((actionType) => ({ status, actionType })),
  );
  const totals = await Promise.all(
    pairs.map(({ status, actionType }) =>
      bundlesApi
        .bundleMutations(bundleId, { status, actionType, page: 0, size: 1 }, signal)
        .then((result) => ({ status, count: result.totalElements })),
    ),
  );
  const byStatus: Record<ExpiringStatus, number> = { PLANNED: 0, AWAITING_APPROVAL: 0, READY_FOR_PUBLICATION: 0 };
  let total = 0;
  for (const { status, count } of totals) {
    byStatus[status] += count;
    total += count;
  }
  return { byStatus, total };
}
