/**
 * E4 — Bestandsopbouw en Drempels bewerken in "Bestand en velden" (mock `docs/mock/setup-redesign-mock.html`, versie 4:
 * [Wijzigen] → invoervelden → [Opslaan]/[Annuleren]). Beide bewaren meteen met `PATCH /setup/revisions/{id}` via
 * `buildRequest` (O1): enkel wat wijzigt gaat mee ("null = ongewijzigd"); gelijk blijft niets doen ("Er is niets gewijzigd").
 *
 * Beperkingen van de server blijven zichtbaar, nooit stil: `expectedColumnCount` en `maxRejectedSharePercent` zijn langs dit
 * pad niet terug op leeg te zetten (hint "Kan hier niet leeg gemaakt worden"); een ander getalveld dat leeggemaakt wordt,
 * geeft bij Opslaan dezelfde melding in plaats van stil niets te doen. Het bestandstype is alleen-lezen. De herkenningsversie
 * staat achter "Geavanceerd" met een waarschuwing (R-REV-X2: na de eerste aanvaarde levering geblokkeerd door de server).
 */

import { useState, type ReactNode } from 'react';
import { useActor } from '../../actor/ActorContext.tsx';
import * as setupRevisionsApi from '../../api/setupRevisions.ts';
import { ROW_ISSUE_SEVERITIES, type RevisionDetail, type RevisionView } from '../../api/types.ts';
import button from '../../components/Button.module.css';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import type { ApiError } from '../../api/http.ts';
import { useAction } from '../../hooks/useAction.ts';
import { termLabel } from '../../terms/index.ts';
import { INLINE_EDIT_TEXT as TEXT } from '../../terms/setupEditor.ts';
import { Term } from '../../terms/Term.tsx';
import { FlagOffNotice } from './FlagOffNotice.tsx';
import {
  buildRequest,
  fieldWord,
  hasChanges,
  initialForm,
  type FlagKey,
  type FormState,
  type TextKey,
} from './revisionEditRequest.ts';
import { isSetupApiDisabledError, SETUP_WRITE_API_DISABLED_MESSAGE } from './setupWriteFlag.ts';
import editor from './FieldsEditor.module.css';

type NumberRule = { name: TextKey; current: number | null };

/** Een getalveld dat een waarde heeft en leeggemaakt is: de server kan het hier niet leegmaken, dus nooit stil negeren. */
function clearedProblem(form: FormState, rules: readonly NumberRule[]): string | null {
  for (const rule of rules) {
    if (rule.current !== null && form[rule.name].trim() === '') {
      return TEXT.notClearableProblem(fieldWord(rule.name));
    }
  }
  return null;
}

function useSectionPatch(revision: RevisionDetail, rules: readonly NumberRule[], onSaved: (result: RevisionView) => void) {
  const { actor } = useActor();
  const [form, setForm] = useState<FormState>(() => initialForm(revision));
  const [problem, setProblem] = useState<string | null>(null);
  const runner = useAction((body: Parameters<typeof setupRevisionsApi.updateRevision>[1]) =>
    setupRevisionsApi.updateRevision(revision.id, body),
  );

  function touched() {
    setProblem(null);
    runner.reset();
  }

  function setText(name: TextKey, value: string) {
    setForm((previous) => ({ ...previous, [name]: value }));
    touched();
  }

  function setFlag(name: FlagKey, value: boolean) {
    setForm((previous) => ({ ...previous, [name]: value }));
    touched();
  }

  async function save() {
    const cleared = clearedProblem(form, rules);
    if (cleared !== null) {
      setProblem(cleared);
      return;
    }
    const built = buildRequest(form, revision, false, actor);
    if ('problem' in built) {
      setProblem(built.problem);
      return;
    }
    if (!hasChanges(built.body)) {
      setProblem(TEXT.nothingChanged);
      return;
    }
    setProblem(null);
    const result = await runner.execute(built.body);
    if (result !== undefined) {
      onSaved(result);
    }
  }

  return { form, setText, setFlag, problem, runner, save };
}

function SaveFeedback({ problem, error }: { problem: string | null; error: ApiError | null }) {
  return (
    <>
      {problem !== null && (
        <p className={editor.panelMessage} role="alert" data-testid="inline-problem">
          {problem}
        </p>
      )}
      {error !== null &&
        (isSetupApiDisabledError(error) ? (
          <FlagOffNotice message={SETUP_WRITE_API_DISABLED_MESSAGE} />
        ) : (
          <ErrorBanner error={error} />
        ))}
    </>
  );
}

function FormField({
  id,
  label,
  hint,
  children,
}: {
  id: string;
  label: string;
  hint?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className={editor.inlineField}>
      <label className={editor.pfLabel} htmlFor={id}>
        {label}
      </label>
      {children}
      {hint !== undefined && (
        <p className={editor.pfHint} id={`${id}-hint`}>
          {hint}
        </p>
      )}
    </div>
  );
}

function FormActions({
  pending,
  onSave,
  onCancel,
}: {
  pending: boolean;
  onSave: () => void;
  onCancel: () => void;
}) {
  return (
    <div className={editor.sectionActions}>
      <button
        type="button"
        className={`${button.primary} ${button.small}`}
        disabled={pending}
        data-testid="inline-save"
        onClick={onSave}
      >
        {pending ? TEXT.saving : TEXT.save}
      </button>
      <button
        type="button"
        className={`${button.secondary} ${button.small}`}
        disabled={pending}
        data-testid="inline-cancel"
        onClick={onCancel}
      >
        {TEXT.cancel}
      </button>
    </div>
  );
}

export type InlineFormProps = {
  revision: RevisionDetail;
  onSaved: (result: RevisionView) => void;
  onCancel: () => void;
};

export function FileEditForm({ revision, onSaved, onCancel }: InlineFormProps) {
  const rules: NumberRule[] = [
    { name: 'headerLineNumber', current: revision.structureHeaderLineNumber },
    { name: 'expectedColumnCount', current: revision.structureExpectedColumnCount },
    { name: 'canonicalisationVersion', current: revision.recordCanonicalisationVersion },
  ];
  const { form, setText, setFlag, problem, runner, save } = useSectionPatch(revision, rules, onSaved);
  const textInput = (name: TextKey, label: string, options: { hint?: ReactNode; type?: string } = {}) => {
    const id = `file-${name}`;
    return (
      <FormField id={id} label={label} hint={options.hint}>
        <input
          id={id}
          className={editor.textInput}
          type={options.type ?? 'text'}
          value={form[name]}
          aria-describedby={options.hint === undefined ? undefined : `${id}-hint`}
          onChange={(event) => setText(name, event.target.value)}
        />
      </FormField>
    );
  };
  return (
    <div data-testid="file-edit-form">
      <div className={editor.inlineForm}>
        <FormField id="file-structureFormat" label={TEXT.fileType} hint={TEXT.fileTypeFixed}>
          <span id="file-structureFormat">
            <Term domain="structureFormat" code={revision.structureFormat} unknownLabel="Ander formaat" />
          </span>
        </FormField>
        {textInput('delimiter', fieldWord('delimiter'))}
        {textInput('quoteChar', fieldWord('quoteChar'))}
        {textInput('charset', fieldWord('charset'))}
        <FormField id="file-hasHeader" label={fieldWord('hasHeader')}>
          <select
            id="file-hasHeader"
            className={editor.textInput}
            value={form.hasHeader ? 'yes' : 'no'}
            onChange={(event) => setFlag('hasHeader', event.target.value === 'yes')}
          >
            <option value="yes">{TEXT.yes}</option>
            <option value="no">{TEXT.no}</option>
          </select>
        </FormField>
        {form.hasHeader && textInput('headerLineNumber', fieldWord('headerLineNumber'), { type: 'number' })}
        <FormField id="file-fieldReferenceKind" label={fieldWord('fieldReferenceKind')}>
          <select
            id="file-fieldReferenceKind"
            className={editor.textInput}
            value={form.fieldReferenceKind}
            onChange={(event) => setText('fieldReferenceKind', event.target.value)}
          >
            {['HEADER_NAME', 'COLUMN_INDEX'].map((code) => (
              <option key={code} value={code}>
                {termLabel('fieldReferenceKind', code, 'Andere herkenning')}
              </option>
            ))}
          </select>
        </FormField>
        {textInput('expectedColumnCount', fieldWord('expectedColumnCount'), {
          type: 'number',
          hint: TEXT.notClearable,
        })}
      </div>
      <details className={editor.advanced} data-testid="file-advanced">
        <summary>{TEXT.advanced}</summary>
        <p className={editor.warning} role="note">
          {TEXT.advancedWarning}
        </p>
        {textInput('canonicalisationVersion', fieldWord('canonicalisationVersion'), { type: 'number' })}
      </details>
      <SaveFeedback problem={problem} error={runner.error} />
      <FormActions pending={runner.pending} onSave={() => void save()} onCancel={onCancel} />
    </div>
  );
}

const PERCENT_FIELDS = [
  ['creationThresholdSharePercent', 'creationThresholdSharePercent'],
  ['maxCriticalSharePercent', 'maxCriticalSharePercent'],
  ['maxRejectedSharePercent', 'maxRejectedSharePercent'],
  ['bulkIncidentSharePercent', 'bulkIncidentSharePercent'],
  ['priceDeviationPercent', 'priceDeviationPercent'],
  ['priceDerivationTolerance', 'priceDerivationTolerance'],
] as const satisfies ReadonlyArray<readonly [TextKey, string]>;

export function ThresholdsEditForm({ revision, onSaved, onCancel }: InlineFormProps) {
  const rules: NumberRule[] = [
    { name: 'creationThresholdSharePercent', current: revision.creationThresholdSharePercent },
    { name: 'maxCriticalSharePercent', current: revision.maxCriticalSharePercent },
    { name: 'maxRejectedSharePercent', current: revision.maxRejectedSharePercent },
    { name: 'bulkIncidentSharePercent', current: revision.bulkIncidentSharePercent },
    { name: 'priceDeviationPercent', current: revision.priceDeviationPercent },
    { name: 'priceDerivationTolerance', current: revision.priceDerivationTolerance },
  ];
  const { form, setText, setFlag, problem, runner, save } = useSectionPatch(revision, rules, onSaved);
  const notClearable = new Set<TextKey>(['maxRejectedSharePercent']);
  return (
    <div data-testid="thresholds-edit-form">
      <p className={editor.pfHint}>{TEXT.thresholdsIntro}</p>
      <div className={editor.inlineForm}>
        {PERCENT_FIELDS.map(([name]) => {
          const id = `thr-${name}`;
          const hint = notClearable.has(name) ? TEXT.notClearable : undefined;
          return (
            <FormField key={name} id={id} label={fieldWord(name)} hint={hint}>
              <input
                id={id}
                className={editor.textInput}
                type="number"
                min="0"
                step="any"
                value={form[name]}
                aria-describedby={hint === undefined ? undefined : `${id}-hint`}
                onChange={(event) => setText(name, event.target.value)}
              />
            </FormField>
          );
        })}
        <FormField id="thr-priceDeviationSeverity" label={fieldWord('priceDeviationSeverity')}>
          <select
            id="thr-priceDeviationSeverity"
            className={editor.textInput}
            value={form.priceDeviationSeverity}
            onChange={(event) => setText('priceDeviationSeverity', event.target.value)}
          >
            {ROW_ISSUE_SEVERITIES.map((code) => (
              <option key={code} value={code}>
                {termLabel('severity', code, 'Andere ernst')}
              </option>
            ))}
          </select>
        </FormField>
        {(['basePriceZeroAllowed', 'basePriceNegativeAllowed'] as const).map((name) => (
          <FormField key={name} id={`thr-${name}`} label={fieldWord(name)}>
            <select
              id={`thr-${name}`}
              className={editor.textInput}
              value={form[name] ? 'yes' : 'no'}
              onChange={(event) => setFlag(name, event.target.value === 'yes')}
            >
              <option value="yes">{TEXT.yes}</option>
              <option value="no">{TEXT.no}</option>
            </select>
          </FormField>
        ))}
      </div>
      <SaveFeedback problem={problem} error={runner.error} />
      <FormActions pending={runner.pending} onSave={() => void save()} onCancel={onCancel} />
    </div>
  );
}
