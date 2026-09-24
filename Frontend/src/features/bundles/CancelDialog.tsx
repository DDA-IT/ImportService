/**
 * Annuleren van een bundel — bouwstap F10, zie `docs/design/frontend-scherm3-bundel-design.md` §10.6.
 *
 * Mag vanuit `ASSEMBLING` én `FROZEN`. Gevolg: elke niet-terminale inhoudelijke mutatie van de actieve
 * leden wordt `EXPIRED` en wordt nooit meer herleefd; de batches komen vrij voor `accept-baseline` of een
 * andere bundel. Binnen de applicatie niet ongedaan te maken, dus:
 *
 * 1. **Voorvlucht**: bij het openen wordt het aantal mutaties dat vervalt vastgesteld
 *    (`expiringMutations.ts`); zolang dat getal er niet is, is annuleren uit (`cancelGate`).
 * 2. **Typ-bevestiging** zoals bij bevriezen: actor + verplichte reden + `bundleReference` overtypen.
 * 3. **Geen stille success, geen retry**: een fout (bv. 409 `BUNDLE_NOT_CANCELLABLE`) blijft in de open
 *    dialoog staan met haar stabiele code; alleen de telling (een lezing) wordt daarna opnieuw geladen.
 *
 * De ouder mount dit component pas bij het openen, zodat elke opening met een verse telling begint.
 */

import type { BundleDetail, CancelBundleRequest } from '../../api/types.ts';
import * as bundlesApi from '../../api/bundles.ts';
import { ConfirmDialog } from '../../components/ConfirmDialog.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useAction } from '../../hooks/useAction.ts';
import { useQuery } from '../../hooks/useQuery.ts';
import { cancelGate } from './bundlePolicy.ts';
import { EXPIRING_STATUSES, loadExpiringCounts } from './expiringMutations.ts';
import styles from './ClosingDialogs.module.css';

export type CancelDialogProps = {
  bundle: BundleDetail;
  /** Sluiten zonder annuleren (of na een fout). */
  onClose: () => void;
  /** Na een geslaagde annulering: de melding en het antwoord van de server; de ouder herlaadt (§5). */
  onCancelled: (message: string, result: BundleDetail) => void;
};

function mutations(count: number): string {
  return `${count} ${count === 1 ? 'mutatie' : 'mutaties'}`;
}

function formatDateTime(iso: string | null): string {
  return iso === null ? '—' : new Date(iso).toLocaleString('nl-BE');
}

function resultMessage(result: BundleDetail): string {
  if (result.status !== 'CANCELLED') {
    return (
      `De server aanvaardde het annuleren, maar de bundel staat nu op ${result.status} in plaats van CANCELLED. ` +
      'Controleer de bundel en het beslissingsregister.'
    );
  }
  return (
    `Bundel ${result.bundleReference} is geannuleerd door ${result.cancelledBy ?? '—'} op ` +
    `${formatDateTime(result.cancelledAt)}. De niet-afgeronde mutaties zijn vervallen (EXPIRED) en de batches ` +
    'zijn vrijgegeven. Het werkelijke aantal staat op de CANCEL-regel in het beslissingsregister.'
  );
}

export function CancelDialog({ bundle, onClose, onCancelled }: CancelDialogProps) {
  const counts = useQuery(`cancel-expiring:${bundle.id}`, (signal) => loadExpiringCounts(bundle.id, signal));
  const runner = useAction((body: CancelBundleRequest) => bundlesApi.cancel(bundle.id, body));

  // Zelfde regel als bij de voorvlucht van het bevriezen: alleen een telling die nu geldt, telt.
  const current = !counts.loading && counts.error === null ? counts.data : null;
  const gate = cancelGate(bundle.status, current === null ? null : current.total);

  async function handleConfirm(input: { actor: string; reason: string | null }) {
    if (!gate.allowed || input.reason === null) {
      return;
    }
    const result = await runner.execute({ cancelledBy: input.actor, reason: input.reason });
    if (result === undefined) {
      // Mislukt: de dialoog blijft open met de foutmelding (inclusief de stabiele backendcode). De telling
      // wordt opnieuw gelezen (een lezing, nooit een herhaling van het annuleren).
      counts.reload();
      return;
    }
    onCancelled(resultMessage(result), result);
  }

  return (
    <ConfirmDialog
      open
      title={`Bundel ${bundle.bundleReference} annuleren`}
      body={
        <div className={styles.body}>
          {counts.loading && <p className={styles.muted}>Het aantal mutaties dat vervalt wordt geteld…</p>}
          {!counts.loading && counts.error !== null && <ErrorBanner error={counts.error} />}
          {current !== null && (
            <>
              <p className={styles.keyFigure} data-testid="cancel-expiring-count">
                <strong>{mutations(current.total)}</strong> {current.total === 1 ? 'vervalt' : 'vervallen'} (EXPIRED) en{' '}
                {current.total === 1 ? 'wordt' : 'worden'} nooit meer herleefd.
              </p>
              <dl className={styles.figures} aria-label="Mutaties die vervallen">
                {EXPIRING_STATUSES.map((status) => (
                  <div key={status} className={styles.figure}>
                    <dt>{status}</dt>
                    <dd>{current.byStatus[status]}</dd>
                  </div>
                ))}
              </dl>
            </>
          )}
          <p className={styles.muted}>
            Het getal is een momentopname; het annuleren telt zelf opnieuw en schrijft het werkelijke aantal in
            het beslissingsregister.
          </p>
          <button
            type="button"
            className={styles.recheckButton}
            onClick={counts.reload}
            disabled={counts.loading || runner.pending}
          >
            Opnieuw tellen
          </button>
          <p>
            Elke niet-afgeronde mutatie (PLANNED, AWAITING_APPROVAL, READY_FOR_PUBLICATION) van de actieve batches
            vervalt; afgekeurde, geblokkeerde en overgeslagen mutaties, identiteitsincidenten en de importmarkering
            blijven zoals ze staan. De batches komen vrij voor accept-baseline of een andere bundel.
          </p>
          <p className={styles.warning}>
            Dit maakt de beslissingen in deze bundel niet ongedaan in het register — ze blijven bewaard — maar de
            mutaties zelf zijn daarna definitief vervallen.
          </p>
        </div>
      }
      reasonRequirement="required"
      typedConfirmationText={bundle.bundleReference}
      confirmLabel="Bundel annuleren"
      cancelLabel="Sluiten zonder annuleren"
      variant="danger"
      pending={runner.pending}
      error={runner.error !== null ? <ErrorBanner error={runner.error} /> : undefined}
      confirmBlockedReason={gate.allowed ? null : gate.reason}
      onConfirm={handleConfirm}
      onCancel={onClose}
    />
  );
}
