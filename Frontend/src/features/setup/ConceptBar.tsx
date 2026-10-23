/**
 * E4 — de conceptbalk van "Bestand en velden" (mock `docs/mock/setup-redesign-mock.html`, versie 4; beslissingslog 2026-10-23
 * "Nieuwe bewerkervaring", O4/O6). Alleen op een concept, plakkend onder de paginakop:
 * "Concept · versie n · gebaseerd op versie m · k wijzigingen", [Toon wijzigingen] (lijst met het verschil), de reden van deze
 * versie (potlood → invoer → `PATCH changeReason`, enkel in Bewerken), de stappen Aanpassen → Controleren → Activeren, de
 * knoppen [Controleren met testbestand] (de bestaande proefinlezing, `TrialReadResultView`) en [Activeren]
 * (`ActivateRevisionAction`, met de verplichte R-CASE-03-waarschuwing).
 *
 * Stap ② is klaar na een proefinlezing zonder blokkade (`NO_BLOCKER_FOUND`); een blokkade wordt wel getoond, maar vinkt de
 * stap niet af. Activeren blijft altijd mogelijk, met een extra waarschuwing zolang er geen geslaagde controle is.
 */

import { useState, type FormEvent } from 'react';
import { useActor } from '../../actor/ActorContext.tsx';
import { usePermissionGate } from '../../actor/permissions.ts';
import * as setupRevisionsApi from '../../api/setupRevisions.ts';
import * as trialReadsApi from '../../api/trialReads.ts';
import {
  PERMISSION_MANAGE,
  type ImportLinkRow,
  type RevisionDetail,
  type TrialReadResult,
} from '../../api/types.ts';
import button from '../../components/Button.module.css';
import { PencilIcon } from '../../components/icons.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useAction } from '../../hooks/useAction.ts';
import { CONCEPT_TEXT as TEXT, TRIAL_BOX_TEXT } from '../../terms/setupEditor.ts';
import { ActivateRevisionAction } from './ActivateRevisionAction.tsx';
import { TrialReadResultView } from './check/TrialReadResultView.tsx';
import { DiffBox } from './DiffParts.tsx';
import { FlagOffNotice } from './FlagOffNotice.tsx';
import { buildRequest, hasChanges, initialForm } from './revisionEditRequest.ts';
import type { RevisionDiff } from './revisionDiffModel.ts';
import { isSetupApiDisabledError, SETUP_WRITE_API_DISABLED_MESSAGE } from './setupWriteFlag.ts';
import editor from './FieldsEditor.module.css';
import styles from './SetupScreens.module.css';

/** O6: de impactbanner bij een definitie die meer dan één koppeling gebruikt; anders niets. */
export function SharedDefinitionNotice({ links }: { links: readonly ImportLinkRow[] }) {
  if (links.length < 2) {
    return null;
  }
  return (
    <div className={styles.banner} role="note" data-testid="shared-definition-banner">
      <p>{TEXT.shared(links.map((link) => link.name))}</p>
    </div>
  );
}

function ReasonLine({
  revision,
  editing,
  onSaved,
}: {
  revision: RevisionDetail;
  editing: boolean;
  onSaved: () => void;
}) {
  const { actor } = useActor();
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState('');
  const runner = useAction((body: Parameters<typeof setupRevisionsApi.updateRevision>[1]) =>
    setupRevisionsApi.updateRevision(revision.id, body),
  );

  function start() {
    runner.reset();
    setDraft(revision.changeReason ?? '');
    setOpen(true);
  }

  async function save() {
    const form = { ...initialForm(revision), changeReason: draft };
    const built = buildRequest(form, revision, false, actor);
    if ('problem' in built || !hasChanges(built.body)) {
      setOpen(false);
      return;
    }
    const result = await runner.execute(built.body);
    if (result !== undefined) {
      setOpen(false);
      onSaved();
    }
  }

  return (
    <div className={editor.barReason} data-testid="concept-reason">
      <span className={styles.rowMeta}>{TEXT.reasonLabel}</span>
      {open ? (
        <>
          <input
            className={editor.textInput}
            aria-label={TEXT.reasonInput}
            value={draft}
            data-testid="concept-reason-input"
            onChange={(event) => setDraft(event.target.value)}
          />
          <button
            type="button"
            className={`${button.primary} ${button.small}`}
            disabled={runner.pending}
            data-testid="concept-reason-save"
            onClick={() => void save()}
          >
            {TEXT.reasonSave}
          </button>
          <button
            type="button"
            className={`${button.secondary} ${button.small}`}
            disabled={runner.pending}
            data-testid="concept-reason-cancel"
            onClick={() => setOpen(false)}
          >
            {TEXT.reasonCancel}
          </button>
          {runner.error !== null &&
            (isSetupApiDisabledError(runner.error) ? (
              <FlagOffNotice message={SETUP_WRITE_API_DISABLED_MESSAGE} />
            ) : (
              <ErrorBanner error={runner.error} />
            ))}
        </>
      ) : (
        <>
          {revision.changeReason !== null && revision.changeReason.trim() !== '' ? (
            <span data-testid="concept-reason-text">{revision.changeReason}</span>
          ) : (
            <span className={styles.rowMeta} data-testid="concept-reason-text">
              {TEXT.reasonNone}
            </span>
          )}
          {editing && (
            <button
              type="button"
              className={editor.iconButton}
              aria-label={TEXT.reasonChange}
              data-testid="concept-reason-edit"
              onClick={start}
            >
              <PencilIcon size={18} />
            </button>
          )}
        </>
      )}
    </div>
  );
}

function TrialBox({
  revision,
  links,
  result,
  onResult,
  onClose,
}: {
  revision: RevisionDetail;
  links: readonly ImportLinkRow[];
  result: TrialReadResult | null;
  onResult: (result: TrialReadResult) => void;
  onClose: () => void;
}) {
  const [file, setFile] = useState<File | null>(null);
  const [validation, setValidation] = useState<string | null>(null);
  // De koppeling bepaalt enkel de standaardvaluta; bij meer dan één koppeling is er geen eenduidige keuze.
  const linkId = links.length === 1 ? links[0]!.id : undefined;
  const runner = useAction((chosen: File) => trialReadsApi.trialRead(revision.id, chosen, linkId));

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (runner.pending) {
      return;
    }
    if (file === null) {
      setValidation(TRIAL_BOX_TEXT.noFile);
      return;
    }
    setValidation(null);
    const outcome = await runner.execute(file);
    if (outcome !== undefined) {
      onResult(outcome);
    }
  }

  return (
    <div className={editor.trialBox} role="region" aria-label={TRIAL_BOX_TEXT.heading} data-testid="concept-trial-box">
      <h3 className={editor.diffHeading}>{TRIAL_BOX_TEXT.heading}</h3>
      <p className={styles.rowMeta}>{TRIAL_BOX_TEXT.intro}</p>
      <form onSubmit={(event) => void submit(event)} noValidate>
        <label className={editor.pfLabel} htmlFor="concept-trial-file">
          {TRIAL_BOX_TEXT.fileLabel}
        </label>
        <input
          id="concept-trial-file"
          type="file"
          disabled={runner.pending}
          data-testid="concept-trial-file"
          onChange={(event) => {
            setFile(event.target.files?.[0] ?? null);
            setValidation(null);
            runner.reset();
          }}
        />
        {validation !== null && (
          <p className={editor.panelMessage} role="alert" data-testid="concept-trial-validation">
            {validation}
          </p>
        )}
        <div className={editor.sectionActions}>
          <button
            type="submit"
            className={`${button.primary} ${button.small}`}
            disabled={runner.pending}
            data-testid="concept-trial-start"
          >
            {runner.pending ? TRIAL_BOX_TEXT.running : TRIAL_BOX_TEXT.start}
          </button>
          <button type="button" className={`${button.secondary} ${button.small}`} onClick={onClose}>
            {TRIAL_BOX_TEXT.close}
          </button>
        </div>
      </form>
      {runner.error !== null && <ErrorBanner error={runner.error} />}
      {result !== null && <TrialReadResultView result={result} />}
    </div>
  );
}

export type ConceptBarProps = {
  revision: RevisionDetail;
  /** Het nummer van de basisversie, of `null` als die er niet is of niet geladen kon worden. */
  baseNumber: number | null;
  /** `true` als er een basisversie is, maar ze niet geladen kon worden. */
  baseFailed: boolean;
  diff: RevisionDiff;
  /** Bewerken staat aan: enkel dan het potlood bij de reden. */
  editing: boolean;
  links: readonly ImportLinkRow[];
  trialResult: TrialReadResult | null;
  onTrialResult: (result: TrialReadResult) => void;
  /** De reden is bewaard: de aanroeper herlaadt het detail. */
  onReasonSaved: () => void;
  /** Na een geslaagde activatie: de aanroeper herlaadt. */
  onActivated: () => void;
};

export function ConceptBar({
  revision,
  baseNumber,
  baseFailed,
  diff,
  editing,
  links,
  trialResult,
  onTrialResult,
  onReasonSaved,
  onActivated,
}: ConceptBarProps) {
  const manageGate = usePermissionGate(PERMISSION_MANAGE);
  const [showDiff, setShowDiff] = useState(false);
  const [trialOpen, setTrialOpen] = useState(false);
  const checked = trialResult?.verdict.result === 'NO_BLOCKER_FOUND';
  const current = checked ? 2 : 0;
  const hasBase = baseNumber !== null;

  return (
    <>
      <div className={editor.conceptBar} role="region" aria-label={TEXT.barLabel} data-testid="concept-bar">
        <div className={editor.barTop}>
          <strong data-testid="concept-title">
            {TEXT.title(revision.revisionNumber, baseNumber, hasBase ? diff.count : null)}
          </strong>
          {hasBase && (
            <button
              type="button"
              className={`${button.secondary} ${button.small}`}
              aria-expanded={showDiff}
              data-testid="diff-toggle"
              onClick={() => setShowDiff((open) => !open)}
            >
              {showDiff ? TEXT.hideChanges : TEXT.showChanges}
            </button>
          )}
        </div>
        {baseFailed && (
          <p className={editor.barNote} role="status" data-testid="base-failed">
            {TEXT.baseFailed}
          </p>
        )}
        <ReasonLine revision={revision} editing={editing} onSaved={onReasonSaved} />
        <ol className={editor.steps} aria-label={TEXT.stepsLabel}>
          {TEXT.steps.map((step, index) => (
            <li
              key={step}
              aria-current={index === current ? 'step' : undefined}
              className={index < current || (index === 1 && checked) ? editor.stepDone : undefined}
              data-testid={`concept-step-${index + 1}`}
            >
              {index === 1 && checked ? '✓ ' : ''}
              {step}
            </li>
          ))}
        </ol>
        <div className={editor.barButtons}>
          <button
            type="button"
            className={`${button.secondary} ${button.small}`}
            disabled={!manageGate.allowed}
            title={manageGate.allowed ? undefined : manageGate.reason}
            aria-expanded={trialOpen}
            data-testid="concept-trial"
            onClick={() => setTrialOpen((open) => !open)}
          >
            {TEXT.trialButton}
          </button>
          <ActivateRevisionAction
            revision={revision}
            testId="concept-activate"
            wording={{ buttonLabel: TEXT.activateButton }}
            extraWarning={
              checked ? undefined : (
                <p className={styles.rowMeta} role="alert" data-testid="concept-no-trial-warning">
                  {TEXT.noTrialWarning}
                </p>
              )
            }
            onActivated={onActivated}
          />
        </div>
        <p className={editor.barNote}>{TEXT.note}</p>
      </div>
      {showDiff && hasBase && <DiffBox diff={diff} />}
      {trialOpen && (
        <TrialBox
          revision={revision}
          links={links}
          result={trialResult}
          onResult={onTrialResult}
          onClose={() => setTrialOpen(false)}
        />
      )}
    </>
  );
}
