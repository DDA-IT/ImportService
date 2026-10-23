/**
 * E2 — het zijpaneel voor een extra veld (mapping) in Bewerken van "Bestand en velden" (mock
 * `docs/mock/setup-redesign-mock.html`, beslissingslog 2026-10-23 "Nieuwe bewerkervaring", O1): een bestaand extra veld
 * wijzigen ([Toepassen] = `PUT /setup/revisions/{id}/mappings/{mid}`, de server geeft een nieuw id), een extra veld
 * toevoegen via "Veld toevoegen" (stap 1: zoekende lijst, stap 2: hetzelfde paneel; `POST .../mappings`) of
 * "Koppelen" bij een veld uit "Nog niet gekoppeld", en [Niet meer gebruiken] (`DELETE`, met bevestiging).
 *
 * De bewerkingschips zijn een weergave over het bestaande formulier van `mappingReplaceModel.ts` (`mappingPanelModel.ts`):
 * de client kent enkel de vaste ketenvakken; of een keten en de instellingen kloppen beslist de server
 * (`CONFIG_TRANSFORM_INVALID`, `VALUE_MAP_*`, `CONFIG_BOOKMARK_*`), die fout blijft in het paneel staan.
 */

import { useId, useRef, useState, type ReactNode } from 'react';
import { useActor } from '../../actor/ActorContext.tsx';
import * as setupRevisionsApi from '../../api/setupRevisions.ts';
import type { CreateMappingRequest, MappingView, RevisionDetail } from '../../api/types.ts';
import button from '../../components/Button.module.css';
import { ConfirmDialog } from '../../components/ConfirmDialog.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useAction } from '../../hooks/useAction.ts';
import { termLabel } from '../../terms/index.ts';
import { EDITOR_TEXT, MAPPING_TEXT as TEXT } from '../../terms/setupEditor.ts';
import { catalogueFields, type CatalogueGroup } from './fieldsGridModel.ts';
import { FlagOffNotice } from './FlagOffNotice.tsx';
import { TableEditor } from './TableEditor.tsx';
import {
  OPS,
  activeOps,
  newMappingTemplate,
  setOp,
  stageOfOp,
  unsupportedKinds,
  withStage,
  type OpDef,
} from './mappingPanelModel.ts';
import {
  ON_MISS_KEEP_SOURCE,
  ON_MISS_REJECT,
  buildMappingRequest,
  initialMappingForm,
  tableKind,
  type MappingForm,
  type Stage,
} from './mappingReplaceModel.ts';
import { PanelShell } from './PanelShell.tsx';
import { isSetupApiDisabledError, SETUP_WRITE_API_DISABLED_MESSAGE } from './setupWriteFlag.ts';
import styles from './FieldsEditor.module.css';

export const MAPPING_TARGET_IN_USE = 'MAPPING_TARGET_IN_USE';

/** Een bestaand extra veld wijzigen (op doelveld, niet op id: een PUT geeft een nieuw id) of een veld toevoegen. */
export type MappingPanelMode = { kind: 'edit'; code: string } | { kind: 'add'; code: string | null };

export type MappingPanelProps = {
  revision: RevisionDetail;
  mode: MappingPanelMode;
  opener: HTMLElement | null;
  onClose: () => void;
  /** Na een geslaagde toevoeging, wijziging of verwijdering: de aanroeper toont de melding, herlaadt en sluit het paneel. */
  onSaved: () => void;
};

type Session = {
  code: string;
  mapping: MappingView;
  form: MappingForm;
  baseline: string;
  /** `initialMappingForm` kan dit extra veld niet veilig teruggeven. */
  unsupported: string | null;
};

function kindLabel(kind: string): string {
  return termLabel('transformKind', kind, 'andere bewerking');
}

function fieldLabel(code: string): string {
  return termLabel('fieldKey', code, 'een ander veld');
}

function startSession(revision: RevisionDetail, mode: MappingPanelMode, code: string): Session | null {
  const existing = mode.kind === 'edit' ? revision.mappings.find((mapping) => mapping.targetFieldCode === code) : undefined;
  if (mode.kind === 'edit' && existing === undefined) {
    return null;
  }
  const mapping = existing ?? newMappingTemplate(revision.id, code);
  const { form, unsupported } = initialMappingForm(mapping);
  return { code, mapping, form, baseline: JSON.stringify(form), unsupported };
}

function Radios({
  legend,
  name,
  options,
  current,
  onPick,
}: {
  legend: string;
  name: string;
  options: { value: string; label: string }[];
  current: string;
  onPick: (value: string) => void;
}) {
  return (
    <div className={styles.pf} role="radiogroup" aria-label={legend}>
      <span className={styles.pfLabel}>{legend}</span>
      <div className={styles.radios}>
        {options.map((option) => (
          <label key={option.value}>
            <input
              type="radio"
              name={name}
              value={option.value}
              checked={current === option.value}
              onChange={() => onPick(option.value)}
            />
            {option.label}
          </label>
        ))}
      </div>
    </div>
  );
}

export function MappingPanel({ revision, mode, opener, onClose, onSaved }: MappingPanelProps) {
  const { actor } = useActor();
  const uid = useId();
  const contentRef = useRef<HTMLDivElement>(null);
  const [session, setSession] = useState<Session | null>(() =>
    mode.code === null ? null : startSession(revision, mode, mode.code),
  );
  const [query, setQuery] = useState('');
  const [problems, setProblems] = useState<string[]>([]);
  const [message, setMessage] = useState<string | null>(null);
  const [moreOpen, setMoreOpen] = useState(false);
  const [unused, setUnused] = useState(false);
  const [confirmUnused, setConfirmUnused] = useState(false);

  const save = useAction((body: CreateMappingRequest) => {
    const existing =
      mode.kind === 'edit' ? revision.mappings.find((mapping) => mapping.targetFieldCode === mode.code) : undefined;
    return existing === undefined
      ? setupRevisionsApi.addMapping(revision.id, body)
      : setupRevisionsApi.replaceMapping(revision.id, existing.id, body);
  });
  // 204 levert geen body: `true` is het teken van een geslaagde verwijdering (zie DeleteChildRowAction).
  const remove = useAction(async () => {
    if (session !== null) {
      await setupRevisionsApi.deleteMapping(revision.id, session.mapping.id);
    }
    return true as const;
  });

  const form = session?.form ?? null;
  const dirty = session !== null && (unused || JSON.stringify(session.form) !== session.baseline);
  const adding = mode.kind === 'add';
  const referenceKind = revision.structureFieldReferenceKind;

  function touched() {
    setProblems([]);
    setMessage(null);
    save.reset();
  }

  function change(next: (previous: MappingForm) => MappingForm) {
    setSession((previous) => (previous === null ? previous : { ...previous, form: next(previous.form) }));
    touched();
  }

  function patch(values: Partial<MappingForm>) {
    change((previous) => ({ ...previous, ...values }));
  }

  function pick(code: string) {
    setSession(startSession(revision, mode, code));
    setQuery('');
    touched();
    // Stap 2: de focus naar het eerste veld van het paneel.
    window.setTimeout(() => {
      contentRef.current?.querySelector<HTMLElement>('input:not([disabled]), select:not([disabled]), button:not([disabled])')?.focus();
    }, 0);
  }

  function backToList() {
    setSession(null);
    setUnused(false);
    touched();
  }

  async function apply() {
    if (session === null) {
      return;
    }
    if (unused) {
      setConfirmUnused(true);
      return;
    }
    if (session.unsupported !== null) {
      setProblems([session.unsupported]);
      return;
    }
    const built = buildMappingRequest(session.form, session.mapping, kindLabel, actor);
    if ('problems' in built) {
      setProblems(built.problems);
      return;
    }
    if (!adding && JSON.stringify(session.form) === session.baseline) {
      setMessage(EDITOR_TEXT.nothingChanged);
      return;
    }
    setProblems([]);
    setMessage(null);
    const result = await save.execute(built.body);
    if (result === undefined) {
      return;
    }
    onSaved();
  }

  async function confirmRemove() {
    const result = await remove.execute();
    if (result === undefined) {
      // Mislukt (409 CONFIG_BOOKMARK_*): er is niets verwijderd, de bevestiging blijft open met de melding.
      return;
    }
    setConfirmUnused(false);
    onSaved();
  }

  // --- stap 1 van "Veld toevoegen": de zoekende lijst ---

  function pickList(): ReactNode {
    const mapped = new Set(revision.mappings.map((mapping) => mapping.targetFieldCode));
    const needle = query.trim().toLowerCase();
    const fields = catalogueFields().filter(
      (field) => needle === '' || field.label.toLowerCase().includes(needle) || field.code.toLowerCase().includes(needle),
    );
    const groups: [CatalogueGroup, string][] = [
      ['prices', TEXT.groupLabels.prices],
      ['references', TEXT.groupLabels.references],
      ['other', TEXT.groupLabels.other],
    ];
    return (
      <div data-testid="add-field-list">
        <div className={styles.pf}>
          <label className={styles.pfLabel} htmlFor={`${uid}-search`}>
            {TEXT.searchLabel}
          </label>
          <input
            id={`${uid}-search`}
            className={styles.textInput}
            type="search"
            value={query}
            placeholder={TEXT.searchPlaceholder}
            autoComplete="off"
            onChange={(event) => setQuery(event.target.value)}
          />
        </div>
        {fields.length === 0 && <p className={styles.pfHint}>{TEXT.noResults}</p>}
        {groups.map(([group, title]) => {
          const inGroup = fields.filter((field) => field.group === group);
          if (inGroup.length === 0) {
            return null;
          }
          return (
            <section key={group} aria-label={title}>
              <h3 className={styles.addGroupTitle}>{title}</h3>
              <ul className={styles.addList}>
                {inGroup.map((field) => {
                  const done = mapped.has(field.code);
                  return (
                    <li key={field.code} className={styles.addItem} data-testid={`add-item-${field.code}`}>
                      <div className={done ? styles.addItemDone : undefined}>
                        <strong>{field.label}</strong>
                        {field.note !== null && !done && <span className={styles.pfHint}>{field.note}</span>}
                      </div>
                      {done ? (
                        <span className={styles.status}>{TEXT.alreadyMapped}</span>
                      ) : (
                        <button
                          type="button"
                          className={`${button.secondary} ${button.small}`}
                          aria-label={TEXT.chooseLabel(field.label)}
                          data-testid={`add-pick-${field.code}`}
                          onClick={() => pick(field.code)}
                        >
                          {TEXT.choose}
                        </button>
                      )}
                    </li>
                  );
                })}
              </ul>
            </section>
          );
        })}
      </div>
    );
  }

  // --- stap 2 / wijzigen: het formulier ---

  function textInput(id: string, label: string, value: string, onChange: (value: string) => void, hint?: string, testId?: string, numeric = false) {
    return (
      <div className={styles.pf}>
        <label className={styles.pfLabel} htmlFor={id}>
          {label}
        </label>
        <input
          id={id}
          className={styles.textInput}
          type="text"
          inputMode={numeric ? 'numeric' : undefined}
          value={value}
          maxLength={numeric ? undefined : 500}
          autoComplete="off"
          data-testid={testId}
          onChange={(event) => onChange(event.target.value)}
        />
        {hint !== undefined && <p className={styles.pfHint}>{hint}</p>}
      </div>
    );
  }

  function checkbox(label: string, checked: boolean, onChange: (checked: boolean) => void, testId: string) {
    return (
      <label className={styles.checkRow}>
        <input type="checkbox" checked={checked} data-testid={testId} onChange={(event) => onChange(event.target.checked)} />
        {label}
      </label>
    );
  }

  function settingLabel(op: OpDef, key: string): string {
    if (key === 'operand') {
      return op.id === 'multiply' ? TEXT.settings.operandMultiply : TEXT.settings.operandDivide;
    }
    switch (key) {
      case 'prefix':
        return TEXT.settings.prefix;
      case 'suffix':
        return TEXT.settings.suffix;
      case 'start':
        return TEXT.settings.start;
      case 'length':
        return TEXT.settings.length;
      case 'operandField':
        return TEXT.settings.operandField;
      case 'base':
        return TEXT.settings.base;
      case 'baseField':
        return TEXT.settings.baseField;
      default:
        return key;
    }
  }

  function opInputs(current: MappingForm, op: OpDef): ReactNode {
    const found = stageOfOp(current, op.id);
    if (found === null) {
      return null;
    }
    const { where, stage } = found;
    const set = (key: string, value: string) =>
      change((previous) => withStage(previous, where, { ...stage, settings: { ...stage.settings, [key]: value } } as Stage));
    const keys: Record<string, readonly string[]> = {
      cut: ['start', 'length'],
      fix: stage.kind === 'PREFIX' ? ['prefix'] : stage.kind === 'SUFFIX' ? ['suffix'] : ['prefix', 'suffix'],
      upper: [],
      translate: [],
      divide: ['operand', 'operandField'],
      multiply: ['operand', 'operandField'],
      percentage: ['base', 'baseField'],
    };
    return (
      <div className={styles.opBlock} data-testid={`op-block-${op.id}`}>
        {(keys[op.id] ?? []).map((key) =>
          textInput(
            `${uid}-${op.id}-${key}`,
            settingLabel(op, key),
            stage.settings[key] ?? '',
            (value) => set(key, value),
            undefined,
            `op-${op.id}-${key}`,
            key === 'start' || key === 'length',
          ),
        )}
        {op.id === 'translate' && (
          <>
            <div className={styles.pf}>
              <span className={styles.pfLabel}>{TEXT.translationLabel}</span>
              <TableEditor
                kind={tableKind(current) ?? 'MAP'}
                rows={current.rows}
                onChange={(rows) => patch({ rows })}
                uid={`${uid}-table`}
              />
            </div>
            {stage.kind === 'MAP' && (
              <>
                <div className={styles.pf}>
                  <label className={styles.pfLabel} htmlFor={`${uid}-onmiss`}>
                    {TEXT.onMissLabel}
                  </label>
                  <select
                    id={`${uid}-onmiss`}
                    className={styles.textInput}
                    data-testid="op-translate-onMiss"
                    value={stage.settings.onMiss === ON_MISS_KEEP_SOURCE ? ON_MISS_KEEP_SOURCE : ON_MISS_REJECT}
                    onChange={(event) => set('onMiss', event.target.value)}
                  >
                    <option value={ON_MISS_REJECT}>{TEXT.onMissReject}</option>
                    <option value={ON_MISS_KEEP_SOURCE}>{TEXT.onMissKeep}</option>
                  </select>
                </div>
                {checkbox(
                  TEXT.settings.caseSensitive,
                  stage.settings.caseSensitive === 'true',
                  (checked) => set('caseSensitive', checked ? 'true' : 'false'),
                  'op-translate-caseSensitive',
                )}
              </>
            )}
          </>
        )}
      </div>
    );
  }

  function formBody(current: MappingForm, mapping: MappingView, unsupported: string | null): ReactNode {
    const ops = activeOps(current);
    const odd = unsupportedKinds(current);
    const standardSource = current.valueKind === 'SOURCE_FIELD' || current.valueKind === 'FIXED_VALUE';
    const note = catalogueFields().find((field) => field.code === mapping.targetFieldCode)?.note ?? null;
    const sourceChoice = unused ? 'none' : current.valueKind === 'FIXED_VALUE' ? 'fixed' : 'column';
    const options = [
      { value: 'column', label: TEXT.sourceColumn },
      { value: 'fixed', label: TEXT.sourceFixed },
      ...(adding ? [] : [{ value: 'none', label: TEXT.sourceNone }]),
    ];
    return (
      <>
        {note !== null && <p className={`${styles.pfHint} ${styles.pf}`}>{note}</p>}
        {unsupported !== null && (
          <p className={styles.warning} role="note" data-testid="mapping-unsupported">
            {unsupported}
          </p>
        )}
        {standardSource ? (
          <Radios
            legend={TEXT.sourceLabel}
            name={`${uid}-source`}
            options={options}
            current={sourceChoice}
            onPick={(value) => {
              setUnused(value === 'none');
              if (value === 'column') {
                patch({ valueKind: 'SOURCE_FIELD', fixedValue: '' });
              } else if (value === 'fixed') {
                patch({ valueKind: 'FIXED_VALUE', sourceReference: '' });
              } else {
                touched();
              }
            }}
          />
        ) : (
          <p className={styles.pf}>
            <span className={styles.pfLabel}>{TEXT.sourceLabel}</span>
            {termLabel('mappingValueKind', current.valueKind, 'Andere herkomst')}
          </p>
        )}
        {unused ? (
          <p className={styles.pfHint}>{TEXT.unusedBody(fieldLabel(mapping.targetFieldCode))}</p>
        ) : (
          <>
            {current.valueKind === 'SOURCE_FIELD' &&
              textInput(
                `${uid}-column`,
                referenceKind === 'COLUMN_INDEX' ? TEXT.columnNumber : TEXT.columnName,
                current.sourceReference,
                (value) => patch({ sourceReference: value }),
                referenceKind === 'COLUMN_INDEX' ? TEXT.columnNumberHint : TEXT.columnNameHint,
                'mapping-column',
              )}
            {current.valueKind === 'FIXED_VALUE' &&
              textInput(
                `${uid}-fixed`,
                TEXT.fixedValueLabel,
                current.fixedValue,
                (value) => patch({ fixedValue: value }),
                TEXT.fixedValueHint,
                'mapping-fixed',
              )}

            <div className={styles.pf} role="group" aria-label={TEXT.operationLabel}>
              <span className={styles.pfLabel}>{TEXT.operationLabel}</span>
              <div className={styles.chips}>
                {OPS.map((op) => (
                  <button
                    key={op.id}
                    type="button"
                    className={`${styles.chip} ${styles.chipButton}`}
                    aria-pressed={ops.has(op.id)}
                    disabled={odd.length > 0}
                    data-testid={`op-${op.id}`}
                    onClick={() => change((previous) => setOp(previous, op.id, !ops.has(op.id)))}
                  >
                    {TEXT.ops[op.id]}
                  </button>
                ))}
              </div>
              <p className={styles.pfHint}>{TEXT.opsHint}</p>
              {odd.length > 0 && (
                <p className={styles.warning} role="note" data-testid="mapping-odd-ops">
                  {TEXT.unsupportedOps(odd.map(kindLabel))}
                </p>
              )}
            </div>
            {OPS.filter((op) => ops.has(op.id)).map((op) => (
              <div key={op.id}>{opInputs(current, op)}</div>
            ))}

            <Radios
              legend={TEXT.criticalLabel}
              name={`${uid}-critical`}
              options={[
                { value: 'CRITICAL', label: TEXT.critical },
                { value: 'NON_CRITICAL', label: TEXT.notCritical },
              ]}
              current={current.criticality}
              onPick={(value) => patch({ criticality: value })}
            />
            <Radios
              legend={TEXT.requiredLabel}
              name={`${uid}-required`}
              options={[
                { value: 'yes', label: TEXT.yes },
                { value: 'no', label: TEXT.no },
              ]}
              current={current.required ? 'yes' : 'no'}
              onPick={(value) => patch({ required: value === 'yes' })}
            />

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
              {textInput(`${uid}-max`, TEXT.moreLabels.maxLength, current.maxLength, (value) => patch({ maxLength: value }), undefined, 'more-maxLength', true)}
              {textInput(`${uid}-scale`, TEXT.moreLabels.decimalScale, current.decimalScale, (value) => patch({ decimalScale: value }), undefined, 'more-decimalScale', true)}
              {checkbox(TEXT.moreLabels.zeroAllowed, current.zeroAllowed, (checked) => patch({ zeroAllowed: checked }), 'more-zeroAllowed')}
              {checkbox(TEXT.moreLabels.negativeAllowed, current.negativeAllowed, (checked) => patch({ negativeAllowed: checked }), 'more-negativeAllowed')}
              {textInput(`${uid}-default`, TEXT.moreLabels.defaultValue, current.defaultValue, (value) => patch({ defaultValue: value }), undefined, 'more-defaultValue')}
              {textInput(`${uid}-position`, TEXT.moreLabels.expectedPosition, current.expectedPosition, (value) => patch({ expectedPosition: value }), undefined, 'more-expectedPosition', true)}
            </div>
          </>
        )}
      </>
    );
  }

  const label = session === null ? '' : fieldLabel(session.code);
  const title = adding
    ? session === null
      ? TEXT.addTitle
      : TEXT.addTitleFor(label)
    : EDITOR_TEXT.panelTitle(label);
  const targetInUse = save.error?.code === MAPPING_TARGET_IN_USE;
  const flagOff = save.error !== null && isSetupApiDisabledError(save.error);

  if (!adding && session === null) {
    // Het extra veld bestaat niet (meer) in deze versie (bv. na een herlading): niets te wijzigen.
    return (
      <PanelShell title={EDITOR_TEXT.panelTitle('')} opener={opener} dirty={false} pending={false} onClose={onClose} foot={(requestClose) => (
        <div className={styles.panelActions}>
          <button type="button" className={button.secondary} onClick={requestClose}>
            {EDITOR_TEXT.cancel}
          </button>
        </div>
      )}>
        <p className={styles.pfHint}>{TEXT.noResults}</p>
      </PanelShell>
    );
  }

  return (
    <>
      <PanelShell
        title={title}
        opener={opener}
        dirty={dirty}
        pending={save.pending || remove.pending}
        onClose={onClose}
        blocked={confirmUnused}
        foot={(requestClose) => (
          <>
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
            {targetInUse && (
              <p className={styles.panelMessage} role="alert" data-testid="mapping-target-in-use">
                {TEXT.targetInUse}
              </p>
            )}
            {save.error !== null &&
              !targetInUse &&
              (flagOff ? (
                <FlagOffNotice message={SETUP_WRITE_API_DISABLED_MESSAGE} />
              ) : (
                <ErrorBanner error={save.error} />
              ))}
            <div className={styles.panelActions}>
              {session !== null && (
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
              {adding && session !== null && mode.code === null && (
                <button type="button" className={button.secondary} data-testid="panel-back" onClick={backToList}>
                  {TEXT.back}
                </button>
              )}
              <button type="button" className={button.secondary} onClick={requestClose}>
                {EDITOR_TEXT.cancel}
              </button>
              {!adding && session !== null && (
                <button
                  type="button"
                  className={button.dangerOutlined}
                  data-testid="panel-unused"
                  onClick={() => {
                    remove.reset();
                    setConfirmUnused(true);
                  }}
                >
                  {TEXT.unusedButton}
                </button>
              )}
            </div>
          </>
        )}
      >
        <div ref={contentRef}>
          {session === null || form === null ? pickList() : formBody(form, session.mapping, session.unsupported)}
        </div>
      </PanelShell>
      {session !== null && (
        <ConfirmDialog
          open={confirmUnused}
          title={TEXT.unusedTitle}
          body={
            <div>
              <p>{TEXT.unusedBody(fieldLabel(session.code))}</p>
              <p className={styles.pfHint}>{TEXT.unusedBookmarkNote}</p>
            </div>
          }
          reasonRequirement="none"
          confirmLabel={TEXT.unusedConfirm}
          cancelLabel={TEXT.unusedKeep}
          variant="danger"
          pending={remove.pending}
          error={
            remove.error === null ? undefined : isSetupApiDisabledError(remove.error) ? (
              <FlagOffNotice message={SETUP_WRITE_API_DISABLED_MESSAGE} />
            ) : (
              <ErrorBanner error={remove.error} />
            )
          }
          onConfirm={() => void confirmRemove()}
          onCancel={() => setConfirmUnused(false)}
        />
      )}
    </>
  );
}
