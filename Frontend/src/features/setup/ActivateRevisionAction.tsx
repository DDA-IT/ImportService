/**
 * S1-F4 — "Revisie activeren" op een DRAFT (endpoint E5, `docs/design/revision-successor-design.md`
 * §3, §4, §6). Het endpoint bestond al; nieuw zijn de UI-actie en de twee foutpaden
 * `REVISION_ACTIVATION_CONFLICT` (gelijktijdige activatie) en `CONFIG_REQUIRED_BOOKMARK_MISSING`.
 *
 * <h2>De verplichte R-CASE-03-waarschuwing</h2>
 * Activeren van een opvolgrevisie heropent bij de eerstvolgende waarneming **alle afgewezen
 * behandelgevallen** (`issue_case`, status `REJECTED`) van **alle koppelingen** van deze importdefinitie
 * (`docs/design/issue-case-design.md` §2, R-CASE-03). Dat is in het issue-case-ontwerp al als bewust
 * gevolg aanvaard — het is geen fout en er is geen keuze om het uit te zetten — maar ontwerp §4 eist dat
 * scherm 1a het bij de activatieknop meldt vóór de gebruiker bevestigt. Daarom staat het in de
 * bevestigingsdialoog zelf, niet ergens bovenaan het scherm.
 *
 * <h2>Geen slot, wél reproduceerbaarheid</h2>
 * Er wordt bij activeren niets vergrendeld: `import_batch.definition_revision_id` pint de revisie
 * waaronder gescreend is, en die blijft na activatie leesbaar als `SUPERSEDED`. De dialoog zegt dat
 * expliciet, zodat niemand denkt dat lopende leveringen halverwege van configuratie wisselen.
 *
 * Recht `MANAGE` + de setup-API-vlag; toestandspoort `revisionPolicy.activateGate` (alleen DRAFT).
 * Geen reden gevraagd: het endpoint bewaart er geen — enkel `approved_by`/`approved_by_subject` en
 * `approved_at`. Een redenveld aanbieden waarvan de inhoud weggegooid wordt, zou misleiden.
 *
 * NT-10: het scherm "Controleren" hergebruikt deze actie met eigen Nederlandse bewoording (`wording`) en een extra
 * V6-waarschuwing (`extraWarning`, "nog geen geslaagde proefinlezing"); zonder die props is alles ongewijzigd.
 */

import { useState, type ReactNode } from 'react';
import * as setupRevisionsApi from '../../api/setupRevisions.ts';
import { PERMISSION_MANAGE, type RevisionDetail, type RevisionView } from '../../api/types.ts';
import { usePermissionGate, withPermission } from '../../actor/permissions.ts';
import { ConfirmDialog } from '../../components/ConfirmDialog.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useAction } from '../../hooks/useAction.ts';
import { gateTitle } from '../../terms/gateTitle.ts';
import { FlagOffNotice } from './FlagOffNotice.tsx';
import { activateGate } from './revisionPolicy.ts';
import { isSetupApiDisabledError, SETUP_WRITE_API_DISABLED_MESSAGE } from './setupWriteFlag.ts';
import styles from './RevisionActions.module.css';

/**
 * De verplichte R-CASE-03-melding; ook los geëxporteerd zodat de test op één tekst kan vastpinnen. NT-11c (V7): in
 * gewoon Nederlands, zonder statuscode en zonder "importdefinitie"; de betekenis is ongewijzigd — alle afgewezen
 * behandelgevallen van alle koppelingen van deze beschrijving van het bestand worden bij de eerstvolgende
 * waarneming opnieuw geopend.
 */
export const REOPEN_REJECTED_CASES_WARNING =
  'Alle afgewezen behandelgevallen van alle koppelingen van deze beschrijving van het bestand ' +
  'worden bij de eerstvolgende waarneming opnieuw geopend. Dat is een bewust aanvaard gevolg van een ' +
  'nieuwe versie — geen fout — maar het betekent dat eerder afgewezen problemen opnieuw op de werklijst ' +
  'komen zodra ze zich nog eens voordoen.';

/**
 * NT-10 — optionele eigen bewoording voor het scherm "Controleren" (gewoon Nederlands, V7). Wat hier niet
 * meegegeven wordt, blijft de bestaande tekst van scherm 1a. De verplichte R-CASE-03-waarschuwing is bewust
 * géén onderdeel hiervan: die staat altijd in de dialoog, ongewijzigd.
 */
export type ActivateRevisionWording = {
  buttonLabel?: string;
  title?: string;
  /** Vervangt de twee uitlegparagrafen rond de verplichte waarschuwing. */
  explanation?: ReactNode;
  confirmLabel?: string;
};

export type ActivateRevisionActionProps = {
  /** Enkel id, nummer en status worden gebruikt; een volledig `RevisionDetail` past ook (compatibel). */
  revision: Pick<RevisionDetail, 'id' | 'revisionNumber' | 'status'>;
  /** Na een geslaagde activatie: de ouder herlaadt het detail en de revisielijst. */
  onActivated: (result: RevisionView) => void;
  wording?: ActivateRevisionWording;
  /**
   * NT-10 (V6): een extra waarschuwing in de bevestigingsdialoog, bv. "nog geen geslaagde proefinlezing". Ze
   * houdt het activeren niet tegen.
   */
  extraWarning?: ReactNode;
};

export function ActivateRevisionAction({ revision, onActivated, wording, extraWarning }: ActivateRevisionActionProps) {
  const manageGate = usePermissionGate(PERMISSION_MANAGE);
  const gate = withPermission(manageGate, activateGate(revision.status));
  const [open, setOpen] = useState(false);
  const runner = useAction((approvedBy: string) =>
    setupRevisionsApi.activateRevision(revision.id, { approvedBy }),
  );

  async function handleConfirm(input: { actor: string; reason: string | null }) {
    const result = await runner.execute(input.actor);
    if (result === undefined) {
      // Mislukt (bv. 409 REVISION_ACTIVATION_CONFLICT of een CONFIG_*-fout): dialoog blijft open.
      return;
    }
    setOpen(false);
    onActivated(result);
  }

  const flagOff = runner.error !== null && isSetupApiDisabledError(runner.error);

  return (
    <div className={styles.actionRow}>
      <button
        type="button"
        className={styles.primaryButton}
        disabled={!gate.allowed}
        title={gateTitle(gate)}
        data-testid="activate-revision"
        onClick={() => {
          runner.reset();
          setOpen(true);
        }}
      >
        {wording?.buttonLabel ?? 'Versie activeren'}
      </button>
      {!gate.allowed && (
        <span className={styles.reason} data-testid="activate-revision-reason">
          {gate.reason}
        </span>
      )}

      {open && (
        <ConfirmDialog
          open
          title={wording?.title ?? `Versie ${revision.revisionNumber} activeren`}
          body={
            <div className={styles.dialogBody}>
              {extraWarning}
              {wording?.explanation === undefined ? (
                <p>
                  Dit concept wordt <strong>actief</strong> en de huidige actieve versie van deze beschrijving
                  van het bestand wordt <strong>vervangen</strong>. De volledige configuratie wordt eerst
                  gecontroleerd met dezelfde regels als bij het lezen van een levering.
                </p>
              ) : (
                wording?.explanation
              )}
              <p className={styles.warning} role="note" data-testid="reopen-rejected-cases-warning">
                {REOPEN_REJECTED_CASES_WARNING}
              </p>
              {wording?.explanation === undefined && (
                <p className={styles.muted}>
                  Leveringen die al gecontroleerd zijn, blijven aan hun eigen versie hangen en worden niet
                  opnieuw beoordeeld: de vorige versie blijft leesbaar als &laquo;vervangen&raquo;.
                </p>
              )}
            </div>
          }
          reasonRequirement="none"
          confirmLabel={wording?.confirmLabel ?? 'Activeren'}
          cancelLabel="Sluiten zonder activeren"
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
    </div>
  );
}
