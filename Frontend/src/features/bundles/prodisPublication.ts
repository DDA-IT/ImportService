/**
 * Pure hulpfuncties voor de publicatie naar de Prodis-database (P7, `docs/design/publicatie-prodis-db-direct-design.md`
 * par. 5): de poort om te starten, de vaste waarschuwing bij een legacy-meester en wat een run "bezig" maakt.
 * Geen backendkennis buiten het contract uit `api/types.ts`; de server blijft de bron van waarheid.
 */

import { denied, ALLOWED, type Gate } from '../../actor/gate.ts';
import { term } from '../../terms/index.ts';
import {
  PUBLICATION_ITEM_STATUSES,
  type LegacyMaster,
  type PublicationBundleStatus,
  type PublicationItemStatus,
  type PublicationRunView,
  type PublicationTargetMode,
} from '../../api/types.ts';

/** De vaste waarschuwing (beslissing 2026-10-22): de volgende datasync zet de wijzigingen terug. */
export const DATASYNC_WARNING =
  'De volgende datasync zet deze wijzigingen terug naar de legacy-waarden (bewust aanvaard, beslissing 2026-10-22).';

/** Wachttijd na `PUBLICATION_RUN_BUSY` (een andere aanroep verwerkt dezelfde run). Aanpasbaar voor tests. */
export const LOOP_TIMING = { busyWaitMs: 1500 };

const ACTIVE_RUN_STATUSES = ['REQUESTED', 'PREPARING', 'PUBLISHING'];

/** Een niet-herkende of ontbrekende stand telt als onbekend: dan geldt de waarschuwing ook. */
export function normalizeLegacyMaster(value: string | null | undefined): LegacyMaster {
  return value === 'NONE' || value === 'PRODIS' || value === 'PROFAB' ? value : 'UNKNOWN';
}

/** Bij PRODIS, PROFAB en onbekend (design par. 5: "onleesbaar -> onbekend + waarschuwing") komt de waarschuwing. */
export function showsDatasyncWarning(master: LegacyMaster): boolean {
  return master !== 'NONE';
}

export function isProdisRun(run: PublicationRunView): boolean {
  return run.targetMode === 'PRODUCTION';
}

/**
 * De laatste proefpublicatie (`SIMULATED`) van de bundel, met de samenvatting van de parallelrun (P2c) en dus de
 * stand van de legacy-meester. `runs` is chronologisch; de hoogste id wint.
 */
export function latestSimulatedRun(runs: readonly PublicationRunView[] | null): PublicationRunView | null {
  if (runs === null) {
    return null;
  }
  let best: PublicationRunView | null = null;
  for (const run of runs) {
    if (run.status === 'SIMULATED' && (best === null || run.id > best.id)) {
      best = run;
    }
  }
  return best;
}

/** Mag "Publiceren naar Prodis" gestart worden? Spiegel van de server; een 409 wordt altijd getoond. */
export function prodisPublishGate(
  bundleStatus: PublicationBundleStatus,
  targetMode: PublicationTargetMode,
  runs: readonly PublicationRunView[] | null,
  loopRunning: boolean,
): Gate {
  if (targetMode !== 'PRODUCTION') {
    return denied('Kan niet: deze publicatie is niet bedoeld voor de echte publicatie.', 'PUBLICATION_MODE_NOT_ENABLED');
  }
  if (bundleStatus !== 'FROZEN') {
    return denied('Kan niet: alleen een bevroren publicatie kan naar Prodis gepubliceerd worden.', 'BUNDLE_NOT_FROZEN');
  }
  if (runs === null) {
    return denied('De lijst van publicatieruns is nog niet geladen (of het laden mislukte).');
  }
  if (loopRunning) {
    return denied('Er wordt al gepubliceerd; wacht tot dat klaar of gepauzeerd is.', 'PUBLICATION_RUN_IN_PROGRESS');
  }
  const active = runs.find((run) => ACTIVE_RUN_STATUSES.includes(run.status));
  if (active !== undefined) {
    return denied(
      `Er loopt al een publicatierun (#${active.id}, status "${term('publicationRunStatus', active.status).label}") voor deze publicatie.`,
      'PUBLICATION_RUN_IN_PROGRESS',
    );
  }
  return ALLOWED;
}

/** Een run waarvoor "Verder publiceren" zin heeft: bezig met schrijven en nog niet klaar. */
export function isContinuable(run: PublicationRunView): boolean {
  return isProdisRun(run) && run.status === 'PUBLISHING' && run.done !== true;
}

/** De lus loopt door zolang de run publiceert en niet klaar is (stopt dus ook als `done` ontbreekt maar de status terminaal is). */
export function shouldContinue(run: PublicationRunView): boolean {
  return run.status === 'PUBLISHING' && run.done !== true;
}

/** Ontbrekende tellers zijn 0 (het contract laat nulwaarden weg). */
export function itemCount(run: PublicationRunView, status: PublicationItemStatus): number {
  return run.itemCounts?.[status] ?? 0;
}

export function totalItems(run: PublicationRunView): number {
  return PUBLICATION_ITEM_STATUSES.reduce((sum, status) => sum + itemCount(run, status), 0);
}

/** Artikelen waarover niets meer te verwachten valt: alles behalve wachtend, onderweg en met onbekende uitkomst. */
export function finishedItems(run: PublicationRunView): number {
  return totalItems(run) - itemCount(run, 'PENDING') - itemCount(run, 'SENT') - itemCount(run, 'RESULT_UNKNOWN');
}
