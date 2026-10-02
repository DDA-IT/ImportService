/**
 * Afhandelacties op het behandelgeval-detail (S2-F2, `docs/design/issue-case-design.md` §4/§6).
 *
 * Drie acties, elk via `ConfirmDialog` (patroon `BatchActions.tsx`/`CancelDialog.tsx`): "Corrigeren"
 * en "Afwijzen" vanuit `AWAITING_REVIEW`, "Heropenen" vanuit `CORRECTED`/`REJECTED`/`AUTO_RESOLVED` —
 * alle drie dezelfde knop/overgang naar `AWAITING_REVIEW`. Een reden is bij elke actie verplicht; de
 * actor komt van `useActor()` (`ConfirmDialog` vult `changedBy` zelf via `input.actor`). `expectedStatus`
 * is de al geladen status van het geval (optimistic concurrency, spiegel van `IssueCaseService`).
 *
 * Recht `MANAGE`: een ontbrekend recht zet de knoppen uit met reden, nooit verborgen (§4-eis van de UI).
 *
 * Vaste uitlegtekst (D3, ontwerp): dit register is administratief. Het deblokkeert geen data — een
 * geblokkeerde/tegengehouden mutatie blijft geblokkeerd. Om data alsnog te verwerken is een nieuwe,
 * correcte levering nodig, of een aparte goedkeuring via accept-baseline/de bundelbeslissing.
 *
 * Na een geslaagde actie herlaadt de ouder het geval (`onChanged`, patroon `BatchActions`); een fout
 * (incl. 409 `ISSUE_CASE_STATUS_CHANGED`/`ISSUE_CASE_TRANSITION_NOT_ALLOWED`) blijft in de open dialoog
 * staan, met de stabiele code — geen automatische retry.
 */

import { useState } from 'react';
import * as issueCasesApi from '../../api/issueCases.ts';
import { PERMISSION_MANAGE, type IssueCaseDecision, type IssueCaseRow } from '../../api/types.ts';
import { usePermissionGate, withPermission } from '../../actor/permissions.ts';
import { ConfirmDialog } from '../../components/ConfirmDialog.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useAction } from '../../hooks/useAction.ts';
import { gateTitle } from '../../actor/gate.ts';
import { term } from '../../terms/index.ts';
import {
  issueCaseActionGate,
  issueCaseActionLabel,
  issueCaseActionsFor,
  issueCaseTargetStatus,
  type IssueCaseAction,
} from './issueCasePolicy.ts';
import button from '../../components/Button.module.css';
import styles from './IssueCaseActions.module.css';

/**
 * De vaste, verplichte uitlegtekst bij elke afhandelactie (D3): dit is administratief, geen
 * dataherstel. Zichtbaar in elke dialoog, ongeacht de gekozen actie.
 */
function AdministrativeNotice() {
  return (
    <p className={styles.notice} data-testid="issue-case-administrative-notice">
      Dit is een administratieve beoordeling van het behandelgeval, geen dataherstel: een tegengehouden
      mutatie blijft tegengehouden. Om die gegevens alsnog te verwerken is een nieuwe, correcte levering
      nodig, of een aparte goedkeuring via &laquo;Aanvaarden als nulmeting&raquo; of de beslissing over de
      bundel.
    </p>
  );
}

type ActionDialogProps = {
  issueCase: IssueCaseRow;
  action: IssueCaseAction;
  onClose: () => void;
  onDone: (message: string) => void;
};

function ActionDialog({ issueCase, action, onClose, onDone }: ActionDialogProps) {
  const target = issueCaseTargetStatus(action);
  const runner = useAction((body: { reason: string; changedBy: string }) =>
    issueCasesApi.changeIssueCaseStatus(issueCase.id, {
      newStatus: target,
      expectedStatus: issueCase.status,
      reason: body.reason,
      changedBy: body.changedBy,
    }),
  );

  async function handleConfirm(input: { actor: string; reason: string | null }) {
    if (input.reason === null) {
      return;
    }
    const result: IssueCaseDecision | undefined = await runner.execute({
      reason: input.reason,
      changedBy: input.actor,
    });
    if (result === undefined) {
      return;
    }
    onDone(
      `Behandelgeval #${issueCase.id} staat nu op "${term('issueCaseStatus', result.status).label}" ` +
        `(door ${result.statusChangedBy ?? '—'}).`,
    );
  }

  return (
    <ConfirmDialog
      open
      title={`Behandelgeval #${issueCase.id} — ${issueCaseActionLabel(action)}`}
      body={
        <div className={styles.body}>
          <AdministrativeNotice />
          <p>
            Huidige status: <strong>{term('issueCaseStatus', issueCase.status).label}</strong>. Nieuwe status
            na bevestigen: <strong>{term('issueCaseStatus', target).label}</strong>.
          </p>
        </div>
      }
      reasonRequirement="required"
      confirmLabel={issueCaseActionLabel(action)}
      variant={action === 'REJECT' ? 'danger' : 'primary'}
      pending={runner.pending}
      error={runner.error !== null ? <ErrorBanner error={runner.error} /> : undefined}
      onConfirm={handleConfirm}
      onCancel={onClose}
    />
  );
}

export type IssueCaseActionsProps = {
  issueCase: IssueCaseRow;
  /** Na een geslaagde actie: de ouder herlaadt het geval (en de events). */
  onChanged: () => void;
};

export function IssueCaseActions({ issueCase, onChanged }: IssueCaseActionsProps) {
  const manageGate = usePermissionGate(PERMISSION_MANAGE);
  const [openAction, setOpenAction] = useState<IssueCaseAction | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const actions = issueCaseActionsFor(issueCase.status);
  if (actions.length === 0) {
    return null;
  }

  function done(message: string) {
    setOpenAction(null);
    setNotice(message);
    onChanged();
  }

  return (
    <section className={styles.actions} aria-label="Afhandelacties">
      {notice !== null && <p role="status">{notice}</p>}
      <div className={styles.buttons}>
        {actions.map((action) => {
          const stateGate = issueCaseActionGate(issueCase.status, action);
          const gate = withPermission(manageGate, stateGate);
          return (
            <button
              key={action}
              type="button"
              className={button.secondary}
              disabled={!gate.allowed}
              title={gateTitle(gate)}
              onClick={() => setOpenAction(action)}
            >
              {issueCaseActionLabel(action)}
            </button>
          );
        })}
      </div>
      {!manageGate.allowed && <p data-testid="permission-reason-manage">{manageGate.reason}</p>}

      {openAction !== null && (
        <ActionDialog
          issueCase={issueCase}
          action={openAction}
          onClose={() => setOpenAction(null)}
          onDone={done}
        />
      )}
    </section>
  );
}
