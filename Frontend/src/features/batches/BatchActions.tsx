/**
 * Bouwstap B-F2 (`docs/decisions.md` 2026-09-23, stap 9; plaatsing 2026-09-22 "accept-baseline vs.
 * bundel-opname": als actie naast de batch op het batchdetail).
 *
 * Business rule: een `SCREENED` batch kan op twee manieren verder, en nooit op allebei:
 * `accept-baseline` (geauditeerde nulmeting van de lokale bronstaat, geen publicatie) of opname in een
 * publicatiebundel. Binnen de applicatie zijn beide onomkeerbaar voor deze batch; de server dwingt het af
 * met 409 `BATCH_IN_PUBLICATION_BUNDLE` / `BATCH_NOT_ACCEPTABLE`.
 *
 * Implementatie: de acties staan er alleen bij status `SCREENED`. Beide gaan via `ConfirmDialog`
 * (actor uit `ActorProvider`, typ-bevestiging); een fout blijft in de open dialoog staan, er is geen retry,
 * en na succes herlaadt de ouder de batch.
 */

import { useRef, useState } from 'react';
import * as batchesApi from '../../api/batches.ts';
import * as bundlesApi from '../../api/bundles.ts';
import type { BatchDetail, BaselineAcceptance } from '../../api/types.ts';
import { ConfirmDialog } from '../../components/ConfirmDialog.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useAction } from '../../hooks/useAction.ts';
import { useQuery } from '../../hooks/useQuery.ts';
import styles from './BatchActions.module.css';

/** De tekst die overgetypt moet worden voor accept-baseline (de documenten leggen er geen vast). */
export const ACCEPT_BASELINE_CONFIRMATION = 'BASELINE';

type Props = { batch: BatchDetail; onChanged: () => void };

function AcceptBaselineDialog({
  batchId,
  onClose,
  onDone,
}: {
  batchId: number;
  onClose: () => void;
  onDone: (message: string) => void;
}) {
  const runner = useAction((body: { acceptedBy: string; reason: string }) =>
    batchesApi.acceptBaseline(batchId, body),
  );

  async function handleConfirm(input: { actor: string; reason: string | null }) {
    if (input.reason === null) {
      return;
    }
    const result: BaselineAcceptance | undefined = await runner.execute({
      acceptedBy: input.actor,
      reason: input.reason,
    });
    if (result === undefined) {
      return;
    }
    onDone(
      `Batch ${batchId} is aanvaard als nulmeting door ${result.acceptedBy}; ` +
        `${result.skippedMutationCount} mutaties zijn overgeslagen (er is niets gepubliceerd).`,
    );
  }

  return (
    <ConfirmDialog
      open
      title={`Batch ${batchId} aanvaarden als nulmeting`}
      body={
        <p>
          De batch wordt de nulmeting van de lokale bronstaat. Er wordt niets gepubliceerd. Dit kan één keer
          per batch en sluit opname in een bundel voorgoed uit.
        </p>
      }
      reasonRequirement="required"
      typedConfirmationText={ACCEPT_BASELINE_CONFIRMATION}
      confirmLabel="Aanvaarden als nulmeting"
      variant="danger"
      pending={runner.pending}
      error={runner.error !== null ? <ErrorBanner error={runner.error} /> : undefined}
      onConfirm={handleConfirm}
      onCancel={onClose}
    />
  );
}

function AddToBundleDialog({
  batchId,
  onClose,
  onDone,
}: {
  batchId: number;
  onClose: () => void;
  onDone: (message: string) => void;
}) {
  const bundles = useQuery('batch-actions:assembling-bundles', (signal) =>
    bundlesApi.list({ status: 'ASSEMBLING', size: 100 }, signal),
  );
  const [bundleId, setBundleId] = useState<number | null>(null);
  const runner = useAction((id: number, actor: string) => bundlesApi.addBatches(id, { batchIds: [batchId], addedBy: actor }));

  const options = bundles.data?.content ?? [];
  const selected = options.find((bundle) => bundle.id === bundleId) ?? null;

  async function handleConfirm(input: { actor: string; reason: string | null }) {
    if (selected === null) {
      return;
    }
    const result = await runner.execute(selected.id, input.actor);
    if (result === undefined) {
      return;
    }
    onDone(`Batch ${batchId} is opgenomen in bundel ${selected.bundleReference}.`);
  }

  return (
    <ConfirmDialog
      open
      title={`Batch ${batchId} opnemen in een bundel`}
      body={
        <div>
          <p>
            Opname in een bundel sluit <code>accept-baseline</code> voor deze batch uit, tot de bundel geannuleerd
            wordt.
          </p>
          {bundles.loading && <p>Bundels worden geladen…</p>}
          {!bundles.loading && bundles.error !== null && <ErrorBanner error={bundles.error} />}
          {!bundles.loading && bundles.error === null && options.length === 0 && (
            <p>Er is geen bundel in opbouw (ASSEMBLING). Maak eerst een bundel aan.</p>
          )}
          {options.length > 0 && (
            <label>
              Bundel{' '}
              <select
                value={bundleId ?? ''}
                onChange={(event) => setBundleId(event.target.value === '' ? null : Number(event.target.value))}
              >
                <option value="">— kies een bundel —</option>
                {options.map((bundle) => (
                  <option key={bundle.id} value={bundle.id}>
                    {bundle.bundleReference}
                  </option>
                ))}
              </select>
            </label>
          )}
        </div>
      }
      reasonRequirement="none"
      typedConfirmationText={selected?.bundleReference}
      confirmLabel="Opnemen in bundel"
      pending={runner.pending}
      error={runner.error !== null ? <ErrorBanner error={runner.error} /> : undefined}
      confirmBlockedReason={selected === null ? 'Kies eerst een bundel in opbouw.' : null}
      onConfirm={handleConfirm}
      onCancel={onClose}
    />
  );
}

/**
 * B-F3 (`docs/decisions.md` 2026-09-23, V2). Business rule: een batch op `MUTATING` is hervatbaar; de actie
 * heeft GEEN actorveld en wordt dus niet op naam vastgelegd. Implementatie: geen `ConfirmDialog` (die vraagt
 * een naam die nergens heen gaat) maar een inline bevestiging met die vermelding; pending state (plus ref)
 * voorkomt een dubbele klik; een fout blijft staan en er is geen automatische retry.
 */
function ContinueSection({ batchId, onDone }: { batchId: number; onDone: (message: string) => void }) {
  const [confirming, setConfirming] = useState(false);
  const inFlight = useRef(false);
  const runner = useAction(() => batchesApi.continueBatch(batchId));

  async function handleContinue() {
    if (inFlight.current) {
      return;
    }
    inFlight.current = true;
    try {
      const result = await runner.execute();
      if (result === undefined) {
        return;
      }
      setConfirming(false);
      onDone(`Batch ${batchId} is hervat; eindstatus ${result.status}.`);
    } finally {
      inFlight.current = false;
    }
  }

  return (
    <>
      <h2 className={styles.title}>Deze batch is onderbroken</h2>
      <p>
        De batch staat op <code>MUTATING</code>: de screening stopte halverwege de mutatiegeneratie. Hervatten
        gaat verder waar het stopte.
      </p>
      {!confirming ? (
        <div className={styles.buttons}>
          <button type="button" onClick={() => setConfirming(true)}>
            Batch hervatten
          </button>
        </div>
      ) : (
        <div role="group" aria-label="Hervatten bevestigen">
          <p>
            <strong>Let op:</strong> deze actie wordt niet op naam vastgelegd; het endpoint kent geen actorveld.
            Noteer zelf wie de batch hervatte als dat nodig is.
          </p>
          {runner.error !== null && <ErrorBanner error={runner.error} />}
          <div className={styles.buttons}>
            <button type="button" onClick={() => void handleContinue()} disabled={runner.pending}>
              {runner.pending ? 'Bezig…' : 'Hervatten bevestigen'}
            </button>
            <button type="button" onClick={() => setConfirming(false)} disabled={runner.pending}>
              Annuleren
            </button>
          </div>
        </div>
      )}
    </>
  );
}

export function BatchActions({ batch, onChanged }: Props) {
  const [dialog, setDialog] = useState<'baseline' | 'bundle' | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const done = (message: string) => {
    setDialog(null);
    setNotice(message);
    onChanged();
  };

  return (
    <section className={styles.actions} aria-label="Acties">
      {notice !== null && <p role="status">{notice}</p>}
      {batch.status === 'SCREENED' && (
        <>
          <h2 className={styles.title}>Hoe verder met deze batch?</h2>
          <p>
            Kies één van twee routes; ze sluiten elkaar per batch uit. Aanvaarden als nulmeting legt de
            bronstaat vast zonder publicatie. Opname in een bundel bereidt publicatie voor. Is de batch al lid
            van een bundel, dan geeft de server 409 <code>BATCH_IN_PUBLICATION_BUNDLE</code>.
          </p>
          <div className={styles.buttons}>
            <button type="button" onClick={() => setDialog('baseline')}>
              Aanvaarden als nulmeting
            </button>
            <button type="button" onClick={() => setDialog('bundle')}>
              Opnemen in bundel
            </button>
          </div>
        </>
      )}
      {batch.status === 'MUTATING' && <ContinueSection batchId={batch.batchId} onDone={done} />}
      {dialog === 'baseline' && (
        <AcceptBaselineDialog batchId={batch.batchId} onClose={() => setDialog(null)} onDone={done} />
      )}
      {dialog === 'bundle' && (
        <AddToBundleDialog batchId={batch.batchId} onClose={() => setDialog(null)} onDone={done} />
      )}
    </section>
  );
}
