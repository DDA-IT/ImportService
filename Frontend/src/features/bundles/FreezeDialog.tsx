/**
 * Bevriezen van een bundel — bouwstap F10, zie `docs/design/frontend-scherm3-bundel-design.md` §10.5 en
 * `docs/decisions.md` 2026-09-23 (C2/C3/V4: het goedkeuringsgetal komt van de server, de voorvlucht is
 * `GET /bundles/{id}/freeze-check`).
 *
 * Bevriezen is de zwaarste, binnen de applicatie onomkeerbare stap: alle resterende `PLANNED`-mutaties
 * worden in bulk goedgekeurd **op naam van de bevriezer**, tellers en bundelhash worden vastgezet, en de
 * bundel aanvaardt daarna niets meer. Daarom:
 *
 * 1. **Voorcontrole ("voorvlucht") vóór alles.** Bij het openen wordt de voorvlucht geladen; zolang die er niet is
 *    (of het laden mislukte), is bevriezen uit. Het `PLANNED`-aantal staat bovenaan: dat is wat de gebruiker
 *    met zijn naam ondertekent. NT-11b: op het scherm heet dit "voorcontrole", en staan codes enkel onder
 *    "Technische details".
 * 2. **Blokkades vooraf, met reden.** Elke blokkadecode van de voorvlucht zet de knop uit, met de reden
 *    als tekst. De voorvlucht is een momentopname zonder slot: de server controleert bij het bevriezen
 *    alles opnieuw, en een 409 wordt altijd getoond, ook als de voorvlucht "bevriesbaar" zei (A44).
 * 3. **Typ-bevestiging**: actor (zichtbaar/wijzigbaar), verplichte reden, en de `bundleReference`
 *    exact overtypen (via `ConfirmDialog`).
 * 4. **Geen stille success, geen retry.** Een fout blijft in de open dialoog staan, met de volledige
 *    servertekst bij conflicten (`ErrorBanner` + `codes.ts`). Er wordt nooit automatisch opnieuw
 *    verstuurd; alleen de voorvlucht (een lezing) wordt na een weigering opnieuw geladen, en kan de
 *    gebruiker ook zelf opnieuw laten controleren.
 *
 * De ouder mount dit component pas bij het openen en ontkoppelt het bij het sluiten, zodat elke opening
 * met een verse voorvlucht en een lege foutstatus begint.
 */

import * as bundlesApi from '../../api/bundles.ts';
import type { BundleDetail, FreezeBundleRequest, FreezePreflight } from '../../api/types.ts';
import { useActor } from '../../actor/ActorContext.tsx';
import { ConfirmDialog } from '../../components/ConfirmDialog.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useAction } from '../../hooks/useAction.ts';
import { useQuery } from '../../hooks/useQuery.ts';
import { TechnicalDetails } from '../../terms/TechnicalDetails.tsx';
import { Term } from '../../terms/Term.tsx';
import { term } from '../../terms/index.ts';
import { WhatIsThis } from '../../terms/WhatIsThis.tsx';
import { freezeBlockers, freezeGate } from './bundlePolicy.ts';
import styles from './ClosingDialogs.module.css';
import { formatDateTime } from '../../format.ts';

export type FreezeDialogProps = {
  bundle: BundleDetail;
  /** Sluiten zonder bevriezen (of na een fout): er is niets verstuurd dat nog loopt. */
  onClose: () => void;
  /**
   * Na een geslaagde bevriezing: de melding voor de gebruiker en het antwoord van de server (de nieuwe
   * `BundleDetail`, bewijs van wat er vastgelegd is). De ouder herlaadt de bundel (§5).
   */
  onFrozen: (message: string, result: BundleDetail) => void;
};

function mutations(count: number): string {
  return `${count} ${count === 1 ? 'mutatie' : 'mutaties'}`;
}

/** Wat de gebruiker na het antwoord te zien krijgt; nooit "gelukt" als de server iets anders toont. */
function resultMessage(result: BundleDetail): string {
  if (result.status !== 'FROZEN') {
    return (
      `De server aanvaardde het bevriezen, maar de bundel heeft nu de status "${term('bundleStatus', result.status).label}" ` +
      `in plaats van "${term('bundleStatus', 'FROZEN').label}". Controleer de bundel en het beslissingsregister.`
    );
  }
  const hash =
    result.contentHash === null
      ? 'geen vingerafdruk ontvangen'
      : `vingerafdruk ${result.contentHash.slice(0, 16)}…`;
  return (
    `Bundel ${result.bundleReference} is bevroren door ${result.frozenBy ?? '—'} op ${formatDateTime(result.frozenAt)} ` +
    `(${hash}). Tellers en vingerafdruk staan nu vast. Hoeveel mutaties met status "${term('mutationStatus', 'PLANNED').label}" ` +
    'daarbij op uw naam goedgekeurd zijn, staat in het beslissingsregister (automatische goedkeuring bij het bevriezen).'
  );
}

function ConflictExamples({ label, examples }: { label: string; examples: string[] }) {
  if (examples.length === 0) {
    return null;
  }
  return (
    <div>
      <p className={styles.sectionLabel}>{label}</p>
      <ul className={styles.examples} aria-label={label}>
        {examples.map((example, index) => (
          // Positie in de lijst van de server als sleutel: twee identieke voorbeeldregels blijven allebei staan.
          <li key={`${index}:${example}`}>{example}</li>
        ))}
      </ul>
    </div>
  );
}

function Preflight({ preflight, actor }: { preflight: FreezePreflight; actor: string }) {
  const blockers = freezeBlockers(preflight);
  const signer = actor.trim() === '' ? 'uw naam' : `uw naam (${actor.trim()})`;
  return (
    <>
      <p className={styles.keyFigure} data-testid="freeze-planned-count">
        <strong>{mutations(preflight.plannedCount)}</strong> met status{' '}
        <Term domain="mutationStatus" code="PLANNED" /> {preflight.plannedCount === 1 ? 'wordt' : 'worden'} bij het
        bevriezen goedgekeurd op {signer}.
      </p>
      <dl className={styles.figures} aria-label="Voorcontrole">
        <div className={styles.figure}>
          <dt>Actieve batches</dt>
          <dd>{preflight.batchCount}</dd>
        </div>
        <div className={styles.figure}>
          <dt>
            <Term domain="bundleCounter" code="awaitingApprovalCount" />
          </dt>
          <dd>{preflight.awaitingApprovalCount}</dd>
        </div>
        <div className={styles.figure}>
          <dt>
            <Term domain="bundleCounter" code="staleMutationCount" />
          </dt>
          <dd>{preflight.staleMutationCount}</dd>
        </div>
      </dl>
      {blockers.length > 0 && (
        <ul className={styles.blockers} aria-label="Blokkades">
          {blockers.map((reason, index) => (
            <li key={`${index}:${reason}`}>{reason}</li>
          ))}
        </ul>
      )}
      {preflight.blockerCodes.length > 0 && (
        <TechnicalDetails
          items={preflight.blockerCodes.map((code, index) => ({
            name: `Blokkade ${index + 1} (${term('freezeCheck', code).uitleg === '' ? 'onbekend' : term('freezeCheck', code).label})`,
            value: code,
          }))}
        />
      )}
      {(preflight.inBundleConflicts.length > 0 || preflight.crossBundleConflicts.length > 0) && (
        <p>
          Twee publiceerbare mutaties op dezelfde aanbieding: keur er één af, of publiceer/annuleer eerst de
          andere bundel. De server geeft hoogstens tien voorbeelden per soort conflict.
        </p>
      )}
      <ConflictExamples label="Conflicten binnen deze bundel" examples={preflight.inBundleConflicts} />
      <ConflictExamples label="Conflicten met een andere bundel" examples={preflight.crossBundleConflicts} />
    </>
  );
}

export function FreezeDialog({ bundle, onClose, onFrozen }: FreezeDialogProps) {
  const { actor } = useActor();
  const preflight = useQuery(`freeze-check:${bundle.id}`, (signal) => bundlesApi.freezeCheck(bundle.id, signal));
  const runner = useAction((body: FreezeBundleRequest) => bundlesApi.freeze(bundle.id, body));

  // Alleen een voorvlucht die nu geldt, telt: tijdens het opnieuw controleren of na een mislukte lezing
  // is er géén voorvlucht (useQuery houdt de vorige data vast; die mag de knop niet aan zetten).
  const current = !preflight.loading && preflight.error === null ? preflight.data : null;
  const gate = freezeGate(bundle.status, current);
  const blockedReason = gate.allowed
    ? null
    : current !== null && freezeBlockers(current).length > 0
      ? 'Bevriezen is geblokkeerd: zie de blokkade(s) hierboven.'
      : gate.reason;

  async function handleConfirm(input: { actor: string; reason: string | null }) {
    if (!gate.allowed || input.reason === null) {
      return;
    }
    const result = await runner.execute({ frozenBy: input.actor, reason: input.reason });
    if (result === undefined) {
      // Mislukt: de dialoog blijft open met de foutmelding (stabiele code, volledige servertekst). De
      // voorvlucht wordt opnieuw gelezen (een lezing, geen herhaling van het bevriezen), zodat de
      // blokkades de toestand ná de weigering tonen in plaats van de verouderde "bevriesbaar".
      preflight.reload();
      return;
    }
    onFrozen(resultMessage(result), result);
  }

  return (
    <ConfirmDialog
      open
      title={`Bundel ${bundle.bundleReference} bevriezen`}
      body={
        <div className={styles.body}>
          <WhatIsThis>
            <p>
              Bevriezen sluit de bundel af: de mutaties met status &quot;{term('mutationStatus', 'PLANNED').label}&quot;
              worden in één keer goedgekeurd op uw naam, de getallen en de vingerafdruk van de bundel liggen vast, en er
              kan niets meer bij of af.
            </p>
            <p>
              De voorcontrole toont vooraf wat het bevriezen zou doen en wat het nog tegenhoudt, zonder iets te wijzigen.
            </p>
          </WhatIsThis>
          {preflight.loading && <p className={styles.muted}>Voorcontrole wordt geladen…</p>}
          {!preflight.loading && preflight.error !== null && <ErrorBanner error={preflight.error} />}
          {current !== null && <Preflight preflight={current} actor={actor} />}
          <p className={styles.muted}>
            De voorcontrole is een momentopname: het bevriezen zelf controleert alles opnieuw.
          </p>
          <button
            type="button"
            className={styles.recheckButton}
            onClick={preflight.reload}
            disabled={preflight.loading || runner.pending}
          >
            Opnieuw controleren
          </button>
          <p className={styles.warning}>
            Echt publiceren naar Prodis kan nog niet; een proefpublicatie wel. Een bevroren bundel blokkeert de
            betrokken aanbiedingen voor elke andere bundel tot ze gepubliceerd of geannuleerd wordt.
          </p>
          <p>
            Bevriezen is binnen de applicatie niet ongedaan te maken; daarna kan de bundel alleen nog geannuleerd
            worden.
          </p>
        </div>
      }
      reasonRequirement="required"
      typedConfirmationText={bundle.bundleReference}
      confirmLabel="Bundel bevriezen"
      cancelLabel="Sluiten zonder bevriezen"
      variant="danger"
      pending={runner.pending}
      error={runner.error !== null ? <ErrorBanner error={runner.error} /> : undefined}
      confirmBlockedReason={blockedReason}
      onConfirm={handleConfirm}
      onCancel={onClose}
    />
  );
}
