/**
 * E3 — het zijpaneel om een filter toe te voegen of te wijzigen ("zinnenbouwer", mock `docs/mock/setup-redesign-mock.html`,
 * beslissingslog 2026-10-23 "Nieuwe bewerkervaring"): uitkomst, kolom (label volgens de manier waarop de kolommen herkend
 * worden), vergelijking, waarde en onder "Meer" hoofdlettergevoelig, gedrag bij een lege cel en bij een ontbrekende kolom.
 * Boven het formulier staat de zin zoals hij na het bewaren gelezen wordt.
 *
 * [Toepassen] = `POST /setup/revisions/{id}/filters`. Wijzigen heeft geen eigen endpoint (gap G2): het nieuwe filter wordt
 * eerst toegevoegd en pas daarna wordt het oude verwijderd (add-before-delete: een fout laat nooit minder filters achter dan
 * er waren). Lukt het verwijderen niet, dan bestaan beide en zegt het paneel dat uitdrukkelijk; het paneel biedt dan geen
 * [Toepassen] meer aan, zodat niemand per ongeluk een derde filter maakt.
 */

import { useId, useState } from 'react';
import { useActor } from '../../actor/ActorContext.tsx';
import * as setupRevisionsApi from '../../api/setupRevisions.ts';
import type { FilterView, RevisionDetail } from '../../api/types.ts';
import button from '../../components/Button.module.css';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useAction } from '../../hooks/useAction.ts';
import { termLabel } from '../../terms/index.ts';
import { EDITOR_TEXT, FILTER_TEXT as TEXT } from '../../terms/setupEditor.ts';
import {
  FILTER_OPERATORS,
  FILTER_OUTCOMES,
  MISSING_COLUMN_BEHAVIOURS,
  NULL_BEHAVIOURS,
  buildFilterRequest,
  emptyFilterForm,
  filterProblems,
  formFromFilter,
  hasNonDefaultMore,
  previewSentence,
  sameForm,
  sentenceText,
  type FilterForm,
} from './filterSentenceModel.ts';
import { FlagOffNotice } from './FlagOffNotice.tsx';
import { PanelShell } from './PanelShell.tsx';
import { isSetupApiDisabledError, SETUP_WRITE_API_DISABLED_MESSAGE } from './setupWriteFlag.ts';
import styles from './FieldsEditor.module.css';

/** Een nieuw filter toevoegen, of een bestaand los filter wijzigen (nieuw toevoegen + oude verwijderen). */
export type FilterPanelMode = { kind: 'add' } | { kind: 'edit'; filter: FilterView };

export type FilterPanelProps = {
  revision: RevisionDetail;
  mode: FilterPanelMode;
  opener: HTMLElement | null;
  onClose: () => void;
  /** Geslaagd: de aanroeper toont de melding, herlaadt en sluit het paneel. */
  onSaved: () => void;
  /** Het nieuwe filter staat er, het oude kon niet weg: de aanroeper herlaadt, het paneel blijft met de melding staan. */
  onChanged: () => void;
};

type SaveResult = { bothExist: boolean };

export function FilterPanel({ revision, mode, opener, onClose, onSaved, onChanged }: FilterPanelProps) {
  const { actor } = useActor();
  const uid = useId();
  const original = mode.kind === 'edit' ? mode.filter : null;
  const [baseline] = useState<FilterForm>(() => (original === null ? emptyFilterForm() : formFromFilter(original)));
  const [form, setForm] = useState<FilterForm>(baseline);
  const [moreOpen, setMoreOpen] = useState(() => hasNonDefaultMore(baseline));
  const [problems, setProblems] = useState<string[]>([]);
  const [message, setMessage] = useState<string | null>(null);
  const [bothExist, setBothExist] = useState(false);
  const referenceKind = revision.structureFieldReferenceKind;
  const byIndex = referenceKind === 'COLUMN_INDEX';

  const save = useAction(async (body: ReturnType<typeof buildFilterRequest>): Promise<SaveResult> => {
    await setupRevisionsApi.addFilter(revision.id, body);
    if (original === null) {
      return { bothExist: false };
    }
    try {
      await setupRevisionsApi.deleteFilter(revision.id, original.id);
    } catch {
      return { bothExist: true };
    }
    return { bothExist: false };
  });

  const dirty = !sameForm(form, baseline) && !bothExist;

  function patch(change: Partial<FilterForm>) {
    setProblems([]);
    setMessage(null);
    setForm((current) => ({ ...current, ...change }));
  }

  async function apply() {
    const found = filterProblems(form);
    if (found.length > 0) {
      setProblems(found);
      return;
    }
    if (original !== null && sameForm(form, baseline)) {
      setMessage(EDITOR_TEXT.nothingChanged);
      return;
    }
    const result = await save.execute(buildFilterRequest(form, actor, original));
    if (result === undefined) {
      return;
    }
    if (result.bothExist) {
      setBothExist(true);
      onChanged();
      return;
    }
    onSaved();
  }

  const flagOff = save.error !== null && isSetupApiDisabledError(save.error);
  const title = original === null ? TEXT.panelAddTitle : TEXT.panelEditTitle(original.sequenceNumber);
  const preview = sentenceText(previewSentence(form, referenceKind));

  return (
    <PanelShell
      title={title}
      opener={opener}
      dirty={dirty}
      pending={save.pending}
      onClose={onClose}
      foot={(requestClose) => (
        <>
          {bothExist && (
            <p className={styles.panelMessage} role="alert" data-testid="filter-both-exist">
              {TEXT.bothExist}
            </p>
          )}
          {message !== null && (
            <p className={styles.panelMessage} role="alert" data-testid="panel-problem">
              {message}
            </p>
          )}
          {problems.length > 0 && (
            <div className={styles.panelMessage} role="alert" data-testid="panel-problems">
              <p>{TEXT.problems}</p>
              <ul>
                {problems.map((problem) => (
                  <li key={problem}>{problem}</li>
                ))}
              </ul>
            </div>
          )}
          {save.error !== null &&
            (flagOff ? <FlagOffNotice message={SETUP_WRITE_API_DISABLED_MESSAGE} /> : <ErrorBanner error={save.error} />)}
          <div className={styles.panelActions}>
            {!bothExist && (
              <button
                type="button"
                className={button.primary}
                disabled={save.pending}
                data-testid="panel-apply"
                onClick={() => void apply()}
              >
                {save.pending ? EDITOR_TEXT.applying : EDITOR_TEXT.apply}
              </button>
            )}
            <button type="button" className={button.secondary} onClick={requestClose}>
              {bothExist ? TEXT.close : EDITOR_TEXT.cancel}
            </button>
          </div>
        </>
      )}
    >
      <p className={styles.pf} role="status" data-testid="filter-preview" aria-label={TEXT.previewLabel}>
        <strong>{preview}</strong>
      </p>

      <div className={styles.pf}>
        <label className={styles.pfLabel} htmlFor={`${uid}-outcome`}>
          {TEXT.outcomeLabel}
        </label>
        <select
          id={`${uid}-outcome`}
          className={styles.textInput}
          data-testid="filter-outcome"
          value={form.outcome}
          disabled={bothExist}
          onChange={(event) => patch({ outcome: event.target.value })}
        >
          {FILTER_OUTCOMES.map((code) => (
            <option key={code} value={code}>
              {termLabel('filterOutcome', code, code)}
            </option>
          ))}
        </select>
      </div>

      <div className={styles.pf}>
        <label className={styles.pfLabel} htmlFor={`${uid}-column`}>
          {byIndex ? TEXT.columnNumberLabel : TEXT.columnNameLabel}
        </label>
        <input
          id={`${uid}-column`}
          className={styles.textInput}
          type="text"
          inputMode={byIndex ? 'numeric' : undefined}
          autoComplete="off"
          data-testid="filter-column"
          value={form.column}
          disabled={bothExist}
          onChange={(event) => patch({ column: event.target.value })}
        />
        <p className={styles.pfHint}>{byIndex ? TEXT.columnNumberHint : TEXT.columnNameHint}</p>
      </div>

      <div className={styles.pf}>
        <label className={styles.pfLabel} htmlFor={`${uid}-operator`}>
          {TEXT.operatorLabel}
        </label>
        <select
          id={`${uid}-operator`}
          className={styles.textInput}
          data-testid="filter-operator"
          value={form.operator}
          disabled={bothExist}
          onChange={(event) => patch({ operator: event.target.value })}
        >
          {FILTER_OPERATORS.map((code) => (
            <option key={code} value={code}>
              {termLabel('filterOperator', code, code)}
            </option>
          ))}
        </select>
      </div>

      <div className={styles.pf}>
        <label className={styles.pfLabel} htmlFor={`${uid}-value`}>
          {TEXT.valueLabel}
        </label>
        <input
          id={`${uid}-value`}
          className={styles.textInput}
          type="text"
          autoComplete="off"
          data-testid="filter-value"
          value={form.value}
          disabled={bothExist}
          onChange={(event) => patch({ value: event.target.value })}
        />
        <p className={styles.pfHint}>{TEXT.valueHint}</p>
      </div>

      <button
        type="button"
        className={`${button.secondary} ${button.small}`}
        aria-expanded={moreOpen}
        aria-controls={`${uid}-more`}
        data-testid="panel-more"
        onClick={() => setMoreOpen((open) => !open)}
      >
        {TEXT.more}
      </button>
      <div id={`${uid}-more`} className={styles.moreBox} hidden={!moreOpen}>
        <label className={styles.checkRow}>
          <input
            type="checkbox"
            checked={form.caseSensitive}
            disabled={bothExist}
            data-testid="filter-case"
            onChange={(event) => patch({ caseSensitive: event.target.checked })}
          />
          {TEXT.caseSensitiveLabel}
        </label>
        <div className={styles.pf}>
          <label className={styles.pfLabel} htmlFor={`${uid}-null`}>
            {TEXT.nullBehaviourLabel}
          </label>
          <select
            id={`${uid}-null`}
            className={styles.textInput}
            data-testid="filter-null"
            value={form.nullBehaviour}
            disabled={bothExist}
            onChange={(event) => patch({ nullBehaviour: event.target.value })}
          >
            {NULL_BEHAVIOURS.map((code) => (
              <option key={code} value={code}>
                {TEXT.nullBehaviourOptions[code]}
              </option>
            ))}
          </select>
        </div>
        <div className={styles.pf}>
          <label className={styles.pfLabel} htmlFor={`${uid}-missing`}>
            {TEXT.missingColumnLabel}
          </label>
          <select
            id={`${uid}-missing`}
            className={styles.textInput}
            data-testid="filter-missing"
            value={form.missingColumnBehaviour}
            disabled={bothExist}
            onChange={(event) => patch({ missingColumnBehaviour: event.target.value })}
          >
            {MISSING_COLUMN_BEHAVIOURS.map((code) => (
              <option key={code} value={code}>
                {TEXT.missingColumnOptions[code]}
              </option>
            ))}
          </select>
        </div>
      </div>
    </PanelShell>
  );
}
