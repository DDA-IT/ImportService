/**
 * E1 — het zijpaneel voor één rij van Herkenning of Basis (of de schakelaar "Hoofdletters negeren") in Bewerken
 * (mock `docs/mock/setup-redesign-mock.html`, beslissingslog 2026-10-23 "Nieuwe bewerkervaring", O1/O5). [Toepassen] bewaart
 * meteen met `PATCH /setup/revisions/{id}` en stuurt enkel wat wijzigt ("null = ongewijzigd", `buildRequest`); de
 * identiteitsbescherming (R-REV-X3) blijft: een wijziging die de herkenning raakt vraagt een uitdrukkelijk, standaard niet
 * aangevinkt vinkje en [Toepassen] blijft uit tot het aangevinkt is. R-REV-X2 (`REVISION_CANONICALISATION_CHANGE_BLOCKED`)
 * is onvoorwaardelijk: dan wordt geen bevestiging aangeboden.
 *
 * Toegankelijkheid: `role="dialog"` met `aria-labelledby`, de focus gaat bij het openen het paneel in en blijft er
 * (Tab loopt rond), Escape sluit (met "Wijzigingen weggooien?" als er iets gewijzigd is) en de focus keert terug naar de
 * knop die het paneel opende.
 */

import { useEffect, useId, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { useActor } from '../../actor/ActorContext.tsx';
import * as setupRevisionsApi from '../../api/setupRevisions.ts';
import type { RevisionDetail, RevisionView } from '../../api/types.ts';
import button from '../../components/Button.module.css';
import { CloseIcon } from '../../components/icons.tsx';
import { ConfirmDialog } from '../../components/ConfirmDialog.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useAction } from '../../hooks/useAction.ts';
import { term } from '../../terms/index.ts';
import { EDITOR_TEXT as TEXT } from '../../terms/setupEditor.ts';
import type { CoreRowKey } from './fieldsGridModel.ts';
import { FlagOffNotice } from './FlagOffNotice.tsx';
import { ProdisCombobox } from './ProdisCombobox.tsx';
import {
  SOURCE_COLUMN,
  SOURCE_FIXED,
  buildRequest,
  hasChanges,
  identityChanged,
  initialForm,
  type FormState,
  type TextKey,
} from './revisionEditRequest.ts';
import { isSetupApiDisabledError, SETUP_WRITE_API_DISABLED_MESSAGE } from './setupWriteFlag.ts';
import styles from './FieldsEditor.module.css';

const CANONICALISATION_CODE = 'REVISION_CANONICALISATION_CHANGE_BLOCKED';

export type PanelTarget = { kind: 'row'; key: CoreRowKey } | { kind: 'uppercase' };

export type FieldPanelProps = {
  revision: RevisionDetail;
  target: PanelTarget;
  /** De bronorganisatie van de definitie (= Prodis-leveranciersnummer) voor de groepen van een vaste groep. */
  sourceOrganisationCode?: string;
  /** De knop die het paneel opende; de focus keert er na het sluiten naartoe terug. */
  opener: HTMLElement | null;
  onClose: () => void;
  /** Na een geslaagde PATCH: de aanroeper toont de melding, herlaadt het detail en sluit het paneel. */
  onSaved: (result: RevisionView) => void;
};

function titleOf(target: PanelTarget, label: string): string {
  return target.kind === 'uppercase' ? TEXT.panelTitle(TEXT.uppercaseChange) : TEXT.panelTitle(label);
}

const ROW_LABEL: Record<CoreRowKey, string> = {
  supplier: TEXT.identityLabels.SUPPLIER,
  group: TEXT.identityLabels.SUPPLIER_GROUP,
  reference: TEXT.identityLabels.SUPPLIER_REFERENCE,
  discount: TEXT.identityLabels.DISCOUNT_CODE,
  basePrice: term('fieldKey', 'BASE_PRICE').label,
  description: term('fieldKey', 'DESCRIPTION').label,
  currency: term('fieldKey', 'CURRENCY').label,
};

function sameForm(a: FormState, b: FormState): boolean {
  return (Object.keys(a) as (keyof FormState)[]).every((name) => a[name] === b[name]);
}

const FOCUSABLE = 'input:not([disabled]), select:not([disabled]), textarea:not([disabled]), button:not([disabled]), [href]';

export function FieldPanel({ revision, target, sourceOrganisationCode, opener, onClose, onSaved }: FieldPanelProps) {
  const { actor } = useActor();
  const titleId = useId();
  const rootRef = useRef<HTMLDivElement>(null);
  const baseline = useRef<FormState>(initialForm(revision));
  const [form, setForm] = useState<FormState>(baseline.current);
  const [acknowledge, setAcknowledge] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  // Kortingscode: "Niet gebruikt" of een kolom; de keuze is formulierstaat, de server kent enkel het veld.
  const [discountUsed, setDiscountUsed] = useState(baseline.current.discountCodeField.trim() !== '');
  const runner = useAction((body: Parameters<typeof setupRevisionsApi.updateRevision>[1]) =>
    setupRevisionsApi.updateRevision(revision.id, body),
  );

  const label = target.kind === 'row' ? ROW_LABEL[target.key] : '';
  const openerTestId = useRef(opener?.getAttribute('data-testid') ?? null);

  // Focus het paneel in bij het openen; bij het sluiten terug naar de opener.
  useEffect(() => {
    const root = rootRef.current;
    const first = root?.querySelector('[data-panel-body]')?.querySelector<HTMLElement>(FOCUSABLE);
    (first ?? root?.querySelector<HTMLElement>('h2'))?.focus();
    return () => {
      const back =
        opener !== null && opener.isConnected
          ? opener
          : openerTestId.current === null
            ? null
            : document.querySelector<HTMLElement>(`[data-testid="${openerTestId.current}"]`);
      back?.focus();
    };
    // Enkel bij het openen en sluiten.
  }, []);

  const dirty = !sameForm(form, baseline.current);

  function requestClose() {
    if (runner.pending) {
      return;
    }
    if (dirty) {
      setConfirmDiscard(true);
    } else {
      onClose();
    }
  }

  function touched() {
    setProblem(null);
    runner.reset();
  }

  function setText(name: TextKey, value: string) {
    setForm((previous) => ({ ...previous, [name]: value }));
    touched();
  }

  function setDiscount(text: string) {
    // Het herkenningsprofiel volgt de kortingscodekolom (het paar moet consistent zijn, ck_import_definition_revision_identity).
    setForm((previous) => ({
      ...previous,
      discountCodeField: text,
      identityProfileKind: text.trim() === '' ? 'THREE_PART' : 'FOUR_PART_WITH_DISCOUNT_CODE',
    }));
    touched();
  }

  const identityChangedNow = identityChanged(form, revision);
  const canonicalisationBlocked = runner.error?.code === CANONICALISATION_CODE;
  const needsAcknowledge = identityChangedNow && !canonicalisationBlocked;

  async function apply() {
    const built = buildRequest(form, revision, acknowledge, actor);
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
    if (result === undefined) {
      return;
    }
    onSaved(result);
  }

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (confirmDiscard) {
      return;
    }
    if (event.key === 'Escape' && !event.defaultPrevented) {
      event.stopPropagation();
      requestClose();
      return;
    }
    if (event.key === 'Tab') {
      const items = Array.from(rootRef.current?.querySelectorAll<HTMLElement>(FOCUSABLE) ?? []).filter(
        (element) => element.getAttribute('aria-hidden') !== 'true' && element.closest('[hidden]') === null,
      );
      const firstItem = items[0];
      const lastItem = items[items.length - 1];
      if (firstItem === undefined || lastItem === undefined) {
        return;
      }
      if (event.shiftKey && (document.activeElement === firstItem || document.activeElement === rootRef.current)) {
        event.preventDefault();
        lastItem.focus();
      } else if (!event.shiftKey && document.activeElement === lastItem) {
        event.preventDefault();
        firstItem.focus();
      }
    }
  }

  // --- de velden van het paneel ---

  function columnInput(name: TextKey, inputLabel: string, hint?: string): ReactNode {
    const id = `panel-${name}`;
    return (
      <div className={styles.pf}>
        <label className={styles.pfLabel} htmlFor={id}>
          {inputLabel}
        </label>
        <input
          id={id}
          className={styles.textInput}
          type="text"
          value={form[name]}
          maxLength={200}
          autoComplete="off"
          onChange={(event) => setText(name, event.target.value)}
        />
        {hint !== undefined && <p className={styles.pfHint}>{hint}</p>}
      </div>
    );
  }

  function radios(name: string, legend: string, options: { value: string; label: string }[], current: string, onPick: (value: string) => void) {
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

  /** Leverancier of groep: een kolom of een vaste waarde (zoekende keuzelijst op Prodis). */
  function sourcePart(part: 'supplier' | 'group'): ReactNode {
    const sourceKey: TextKey = part === 'supplier' ? 'supplierSource' : 'supplierGroupSource';
    const columnKey: TextKey = part === 'supplier' ? 'supplierField' : 'supplierGroupField';
    const fixedKey: TextKey = part === 'supplier' ? 'supplierFixedValue' : 'supplierGroupFixedValue';
    const fixedId = `panel-${fixedKey}`;
    // De groepen horen bij de vaste leverancier, anders bij de bronorganisatie van de definitie (zoals de server).
    const supplierTyped = form.supplierSource === SOURCE_FIXED ? form.supplierFixedValue.trim() : '';
    const groupSupplier =
      supplierTyped !== ''
        ? form.identityUppercase
          ? supplierTyped.toUpperCase()
          : supplierTyped
        : (sourceOrganisationCode ?? '');
    return (
      <>
        {radios(
          `panel-${part}-source`,
          TEXT.sourceLabel,
          [
            { value: SOURCE_COLUMN, label: TEXT.sourceColumn },
            { value: SOURCE_FIXED, label: TEXT.sourceFixed },
          ],
          form[sourceKey],
          (value) => setText(sourceKey, value),
        )}
        {form[sourceKey] === SOURCE_FIXED ? (
          <div className={styles.pf}>
            <label className={styles.pfLabel} htmlFor={fixedId}>
              {part === 'supplier' ? TEXT.fixedSupplierLabel : TEXT.fixedGroupLabel}
            </label>
            <ProdisCombobox
              kind={part}
              id={fixedId}
              value={form[fixedKey]}
              onChange={(next) => setText(fixedKey, next)}
              supplierNumber={part === 'group' ? groupSupplier : undefined}
            />
          </div>
        ) : (
          columnInput(columnKey, TEXT.columnLabel, TEXT.columnHint)
        )}
      </>
    );
  }

  function body(): ReactNode {
    if (target.kind === 'uppercase') {
      return (
        <div className={styles.pf}>
          <label>
            <input
              type="checkbox"
              checked={form.identityUppercase}
              onChange={(event) => {
                setForm((previous) => ({ ...previous, identityUppercase: event.target.checked }));
                touched();
              }}
            />{' '}
            {TEXT.uppercaseSwitch}
          </label>
          <p className={styles.pfHint}>{term('revisionField', 'identityUppercase').uitleg}</p>
        </div>
      );
    }
    switch (target.key) {
      case 'supplier':
        return sourcePart('supplier');
      case 'group':
        return sourcePart('group');
      case 'reference':
        return columnInput('supplierReferenceField', TEXT.columnLabel, TEXT.columnHint);
      case 'discount':
        return (
          <>
            {radios(
              'panel-discount-source',
              TEXT.sourceLabel,
              [
                { value: SOURCE_COLUMN, label: TEXT.sourceColumn },
                { value: 'NONE', label: TEXT.sourceNone },
              ],
              discountUsed ? SOURCE_COLUMN : 'NONE',
              (value) => {
                const used = value === SOURCE_COLUMN;
                setDiscountUsed(used);
                if (!used) {
                  setDiscount('');
                }
              },
            )}
            {discountUsed && (
              <div className={styles.pf}>
                <label className={styles.pfLabel} htmlFor="panel-discountCodeField">
                  {TEXT.columnLabel}
                </label>
                <input
                  id="panel-discountCodeField"
                  className={styles.textInput}
                  type="text"
                  value={form.discountCodeField}
                  maxLength={200}
                  autoComplete="off"
                  onChange={(event) => setDiscount(event.target.value)}
                />
                <p className={styles.pfHint}>{TEXT.columnHint}</p>
              </div>
            )}
          </>
        );
      case 'basePrice':
        return columnInput('basePriceField', TEXT.columnLabel, TEXT.columnHint);
      case 'description':
        return columnInput('descriptionField', TEXT.columnLabel, `${TEXT.columnHint} ${TEXT.clearHint}`);
      case 'currency':
        return columnInput('currencyField', TEXT.columnLabel, `${TEXT.columnHint} ${TEXT.clearHint}`);
    }
  }

  const title = titleOf(target, label);
  const applyBlocked = runner.pending || (needsAcknowledge && !acknowledge);

  return (
    <div
      ref={rootRef}
      className={styles.panel}
      role="dialog"
      aria-labelledby={titleId}
      data-testid="field-panel"
      onKeyDown={handleKeyDown}
    >
      <div className={styles.panelHead}>
        <h2 id={titleId} tabIndex={-1}>
          {title}
        </h2>
        <button type="button" className={styles.iconButton} aria-label={TEXT.panelClose} onClick={requestClose}>
          <CloseIcon size={20} />
        </button>
      </div>
      <div className={styles.panelBody} data-panel-body>
        {body()}
        {needsAcknowledge && (
          <div data-testid="identity-change-acknowledge">
            <p className={styles.warning} role="note">
              {TEXT.identityWarning}
            </p>
            <label>
              <input
                type="checkbox"
                checked={acknowledge}
                onChange={(event) => {
                  setAcknowledge(event.target.checked);
                  setProblem(null);
                }}
              />{' '}
              {TEXT.identityAcknowledge}
            </label>
          </div>
        )}
      </div>
      <div className={styles.panelFoot}>
        {problem !== null && (
          <p className={styles.panelMessage} role="alert" data-testid="panel-problem">
            {problem}
          </p>
        )}
        {runner.error !== null &&
          (isSetupApiDisabledError(runner.error) ? (
            <FlagOffNotice message={SETUP_WRITE_API_DISABLED_MESSAGE} />
          ) : (
            <ErrorBanner error={runner.error} />
          ))}
        <div className={styles.panelActions}>
          <button
            type="button"
            className={button.primary}
            disabled={applyBlocked}
            data-testid="panel-apply"
            onClick={() => void apply()}
          >
            {runner.pending ? TEXT.applying : TEXT.apply}
          </button>
          <button type="button" className={button.secondary} onClick={requestClose}>
            {TEXT.cancel}
          </button>
        </div>
      </div>
      <ConfirmDialog
        open={confirmDiscard}
        title={TEXT.discardTitle}
        body={<p>{TEXT.discardBody}</p>}
        reasonRequirement="none"
        confirmLabel={TEXT.discardConfirm}
        cancelLabel={TEXT.discardKeep}
        variant="danger"
        onConfirm={() => {
          setConfirmDiscard(false);
          onClose();
        }}
        onCancel={() => setConfirmDiscard(false)}
      />
    </div>
  );
}
