/**
 * S1-F4 — "Opvolger maken" op een revisierij van scherm 1a (endpoint E2,
 * `docs/design/revision-successor-design.md` §1, §2, §6).
 *
 * De kloon is **byte-identiek** (R-REV-X1): alle configuratievelden en alle vijf de kindtabellen gaan
 * verbatim mee, `recordCanonicalisationVersion` wordt nooit automatisch verhoogd, en er wordt niets
 * geactiveerd. De betekenis van de definitie verandert dus pas door een latere wijziging in die DRAFT.
 * Die uitleg staat in de dialoog, zodat de gebruiker weet dat deze klik zelf nog niets aan de catalogus
 * doet.
 *
 * **De reden is verplicht** (`changeReason`, 400 `CHANGE_REASON_REQUIRED`) — daarom
 * `reasonRequirement="required"` op `ConfirmDialog`, hetzelfde afdwingpatroon als bij `CancelDialog` en
 * `IssueCaseActions`: bevestigen blijft uit zolang het redenveld leeg is, en de UI verstuurt in dat
 * geval niets.
 *
 * Recht `MANAGE` + de setup-API-vlag; de toestandspoort komt uit `revisionPolicy.successorGate`
 * (alleen ACTIVE/SUPERSEDED). Recht eerst, dan de toestand (`withPermission`), net als de server.
 * Een ontbrekend recht of een verkeerde bronstatus schakelt de knop uit MÉT reden — nooit verborgen.
 *
 * Geen automatische herhaling: een tweede poging zou een tweede DRAFT willen maken en botst op 409
 * `REVISION_DRAFT_ALREADY_EXISTS` (O1). De fout blijft in de open dialoog staan.
 */

import { useState } from 'react';
import * as setupRevisionsApi from '../../api/setupRevisions.ts';
import { PERMISSION_MANAGE, type RevisionRow, type RevisionView } from '../../api/types.ts';
import { usePermissionGate, withPermission } from '../../actor/permissions.ts';
import { ConfirmDialog } from '../../components/ConfirmDialog.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useAction } from '../../hooks/useAction.ts';
import { gateTitle } from '../../actor/gate.ts';
import { term } from '../../terms/index.ts';
import { FlagOffNotice } from './FlagOffNotice.tsx';
import { successorGate } from './revisionPolicy.ts';
import { isSetupApiDisabledError, SETUP_WRITE_API_DISABLED_MESSAGE } from './setupWriteFlag.ts';
import styles from './RevisionActions.module.css';

export type CreateSuccessorActionProps = {
  revision: RevisionRow;
  /** Na een geslaagde kloon: de ouder herlaadt de revisielijst en opent de nieuwe DRAFT. */
  onCreated: (created: RevisionView) => void;
};

export function CreateSuccessorAction({ revision, onCreated }: CreateSuccessorActionProps) {
  const manageGate = usePermissionGate(PERMISSION_MANAGE);
  const gate = withPermission(manageGate, successorGate(revision.status));
  const [open, setOpen] = useState(false);
  const runner = useAction((body: { changeReason: string; createdBy: string }) =>
    setupRevisionsApi.createSuccessor(revision.id, body),
  );

  async function handleConfirm(input: { actor: string; reason: string | null }) {
    // De dialoog laat bevestigen niet toe zonder reden; deze controle is de tweede sluis, geen default.
    if (input.reason === null) {
      return;
    }
    const created = await runner.execute({ changeReason: input.reason, createdBy: input.actor });
    if (created === undefined) {
      // Mislukt (bv. 409 REVISION_DRAFT_ALREADY_EXISTS): de dialoog blijft open met de stabiele code.
      return;
    }
    setOpen(false);
    onCreated(created);
  }

  const flagOff = runner.error !== null && isSetupApiDisabledError(runner.error);

  return (
    <>
      <button
        type="button"
        className={styles.actionButton}
        disabled={!gate.allowed}
        title={gateTitle(gate)}
        data-testid={`create-successor-${revision.id}`}
        onClick={() => {
          runner.reset();
          setOpen(true);
        }}
      >
        Opvolger maken
      </button>
      {!gate.allowed && (
        <span className={styles.reason} data-testid={`create-successor-reason-${revision.id}`}>
          {gate.reason}
        </span>
      )}

      {open && (
        <ConfirmDialog
          open
          title={`Opvolger maken van versie ${revision.revisionNumber}`}
          body={
            <div className={styles.dialogBody}>
              <p>
                Er komt een nieuw <strong>concept</strong> bij deze beschrijving van het bestand, als exacte
                kopie van versie {revision.revisionNumber} (status &laquo;{term('revisionStatus', revision.status).label}
                &raquo;): alle instellingen, extra velden, filters, kritiek-overrules en gedeclareerde invulpunten gaan
                ongewijzigd mee.
              </p>
              <p className={styles.muted}>
                Deze stap verandert zelf niets aan de artikelen: de kopie is identiek en wordt niet geactiveerd.
                Ook de herkenningsversie blijft staan. Wat u daarna in het concept wijzigt, bepaalt het gevolg.
              </p>
              <p className={styles.muted}>
                Per beschrijving van het bestand mag er hoogstens één concept open staan; staat er al één, dan
                weigert de server deze aanvraag en wordt er niets aangemaakt.
              </p>
            </div>
          }
          reasonRequirement="required"
          // Bewust een ander opschrift dan de knop die deze dialoog opende: de bevestiging moet
          // ondubbelzinnig herkenbaar zijn, ook voor een schermlezer.
          confirmLabel="Opvolger aanmaken"
          cancelLabel="Sluiten zonder aanmaken"
          pending={runner.pending}
          error={
            runner.error === null ? undefined : flagOff ? (
              <FlagOffNotice message={SETUP_WRITE_API_DISABLED_MESSAGE} className={styles.flagOff} />
            ) : (
              <ErrorBanner error={runner.error} />
            )
          }
          onConfirm={handleConfirm}
          onCancel={() => setOpen(false)}
        />
      )}
    </>
  );
}
