/**
 * E1 — het zijpaneel voor één rij van Herkenning of Basis (of de schakelaar "Hoofdletters negeren") in Bewerken
 * (mock `docs/mock/setup-redesign-mock.html`, beslissingslog 2026-10-23 "Nieuwe bewerkervaring", O1/O5). [Toepassen] bewaart
 * meteen met `PATCH /setup/revisions/{id}` en stuurt enkel wat wijzigt ("null = ongewijzigd", `buildRequest`); de
 * identiteitsbescherming (R-REV-X3) blijft: een wijziging die de herkenning raakt vraagt een uitdrukkelijk, standaard niet
 * aangevinkt vinkje en [Toepassen] blijft uit tot het aangevinkt is. R-REV-X2 (`REVISION_CANONICALISATION_CHANGE_BLOCKED`)
 * is onvoorwaardelijk: dan wordt geen bevestiging aangeboden.
 *
 * De schil (dialoog, focus, Escape, "Wijzigingen weggooien?") staat in `PanelShell`.
 */

import { useRef, useState, type ReactNode } from 'react';
import { useActor } from '../../actor/ActorContext.tsx';
import * as setupRevisionsApi from '../../api/setupRevisions.ts';
import type { RevisionDetail, RevisionView } from '../../api/types.ts';
import button from '../../components/Button.module.css';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useAction } from '../../hooks/useAction.ts';
import { term } from '../../terms/index.ts';
import { EDITOR_TEXT as TEXT } from '../../terms/setupEditor.ts';
import { coreRowCriticality, coreRowFieldKey, type CatalogueCriticality, type CoreRowKey, type FieldCatalogue } from './fieldsGridModel.ts';
import { FlagOffNotice } from './FlagOffNotice.tsx';
import { PanelShell } from './PanelShell.tsx';
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

type CriticalityStep = { kind: 'none' } | { kind: 'set'; value: CatalogueCriticality } | { kind: 'restore' };

const CANONICALISATION_CODE = 'REVISION_CANONICALISATION_CHANGE_BLOCKED';

export type PanelTarget = { kind: 'row'; key: CoreRowKey } | { kind: 'uppercase' };

export type FieldPanelProps = {
  revision: RevisionDetail;
  target: PanelTarget;
  /** EF2: de veldcatalogus (standaard kritiek en of die te wijzigen is); zonder catalogus blijft de kritiek ongewijzigd. */
  catalogue?: FieldCatalogue | null;
  /** De bronorganisatie van de definitie (= Prodis-leveranciersnummer) voor de groepen van een vaste groep. */
  sourceOrganisationCode?: string;
  /** De knop die het paneel opende; de focus keert er na het sluiten naartoe terug. */
  opener: HTMLElement | null;
  onClose: () => void;
  /** Na het bewaren: de aanroeper toont de melding, herlaadt het detail en sluit het paneel. `null` = enkel de kritiek is gewijzigd. */
  onSaved: (result: RevisionView | null) => void;
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

export function FieldPanel({ revision, target, catalogue = null, sourceOrganisationCode, opener, onClose, onSaved }: FieldPanelProps) {
  const { actor } = useActor();
  const baseline = useRef<FormState>(initialForm(revision));
  const [form, setForm] = useState<FormState>(baseline.current);
  const [acknowledge, setAcknowledge] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  // Kortingscode: "Niet gebruikt" of een kolom; de keuze is formulierstaat, de server kent enkel het veld.
  const [discountUsed, setDiscountUsed] = useState(baseline.current.discountCodeField.trim() !== '');
  // EF2: de kritiek van Basisprijs, Valuta en Omschrijving (eigen endpoints, `PUT`/`DELETE field-criticality`).
  const rowFieldKey = target.kind === 'row' ? coreRowFieldKey(target.key) : null;
  const catalogueEntry = rowFieldKey === null ? undefined : catalogue?.revisionFields[rowFieldKey];
  const hasOverride = rowFieldKey !== null && revision.fieldCriticalities.some((row) => row.fieldKey === rowFieldKey);
  const criticalityEditable = catalogueEntry !== undefined && catalogueEntry.criticalityChangeable;
  const initialCriticality = useRef<CatalogueCriticality | null>(
    target.kind === 'row' ? coreRowCriticality(target.key, revision, catalogue) : null,
  );
  const [criticality, setCriticality] = useState<CatalogueCriticality | null>(initialCriticality.current);
  const [restore, setRestore] = useState(false);
  // Een gelukte PATCH van een eerdere poging waarvan de kritiek mislukte: niet opnieuw sturen zolang het formulier gelijk bleef.
  const scalarDone = useRef<{ form: FormState; result: RevisionView } | null>(null);
  const runner = useAction(async (job: { body: Parameters<typeof setupRevisionsApi.updateRevision>[1] | null; criticality: CriticalityStep }) => {
    let result: RevisionView | null = scalarDone.current?.result ?? null;
    if (job.body !== null) {
      result = await setupRevisionsApi.updateRevision(revision.id, job.body);
      scalarDone.current = { form, result };
    }
    if (rowFieldKey !== null) {
      if (job.criticality.kind === 'set') {
        await setupRevisionsApi.setFieldCriticality(revision.id, rowFieldKey, {
          criticality: job.criticality.value,
          createdBy: actor,
        });
      } else if (job.criticality.kind === 'restore') {
        await setupRevisionsApi.deleteFieldCriticality(revision.id, rowFieldKey);
      }
    }
    return { result };
  });

  const label = target.kind === 'row' ? ROW_LABEL[target.key] : '';

  const criticalityDirty = restore || (criticality !== initialCriticality.current);
  const dirty = !sameForm(form, baseline.current) || criticalityDirty;

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

  function criticalityStep(): CriticalityStep {
    if (!criticalityEditable) {
      return { kind: 'none' };
    }
    if (restore) {
      return hasOverride ? { kind: 'restore' } : { kind: 'none' };
    }
    return criticality !== null && criticality !== initialCriticality.current
      ? { kind: 'set', value: criticality }
      : { kind: 'none' };
  }

  async function apply() {
    const skipScalar = scalarDone.current !== null && sameForm(form, scalarDone.current.form);
    const built = buildRequest(form, revision, acknowledge, actor);
    if ('problem' in built) {
      setProblem(built.problem);
      return;
    }
    const step = criticalityStep();
    const scalarChanged = !skipScalar && hasChanges(built.body);
    if (!scalarChanged && step.kind === 'none' && !skipScalar) {
      setProblem(TEXT.nothingChanged);
      return;
    }
    setProblem(null);
    const done = await runner.execute({ body: scalarChanged ? built.body : null, criticality: step });
    if (done === undefined) {
      return;
    }
    onSaved(done.result);
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

  /** EF2: Kritiek / Niet kritiek met "Standaard herstellen"; een herkenningsrij toont enkel "Kritiek (altijd)". */
  function criticalityBlock(): ReactNode {
    if (target.kind !== 'row') {
      return null;
    }
    const CRIT = TEXT.criticality;
    const identityRow = ['supplier', 'group', 'reference', 'discount'].includes(target.key);
    if (identityRow) {
      return (
        <div className={styles.pf} data-testid="panel-criticality-locked">
          <span className={styles.pfLabel}>{CRIT.label}</span>
          <p>{CRIT.locked}</p>
          <p className={styles.pfHint}>{CRIT.lockedHint}</p>
        </div>
      );
    }
    if (!criticalityEditable) {
      return null;
    }
    const standard = catalogueEntry.defaultCriticality;
    const standardLabel = standard === 'CRITICAL' ? CRIT.critical : CRIT.notCritical;
    return (
      <div data-testid="panel-criticality">
        {radios(
          'panel-criticality',
          CRIT.label,
          [
            { value: 'CRITICAL', label: CRIT.critical },
            { value: 'NON_CRITICAL', label: CRIT.notCritical },
          ],
          criticality ?? standard,
          (value) => {
            setCriticality(value === 'NON_CRITICAL' ? 'NON_CRITICAL' : 'CRITICAL');
            setRestore(false);
            touched();
          },
        )}
        {target.key === 'basePrice' && (criticality ?? standard) === 'NON_CRITICAL' && (
          <p className={styles.warning} role="note" data-testid="panel-criticality-warning">
            {CRIT.basePriceWarning}
          </p>
        )}
        {hasOverride && (
          <div className={styles.pf}>
            <p className={styles.pfHint}>{restore ? CRIT.restoreNote(standardLabel) : CRIT.customNote}</p>
            <button
              type="button"
              className={`${button.secondary} ${button.small}`}
              disabled={restore}
              data-testid="panel-criticality-restore"
              onClick={() => {
                setCriticality(standard);
                setRestore(true);
                touched();
              }}
            >
              {CRIT.restore}
            </button>
          </div>
        )}
      </div>
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
    <PanelShell
      title={title}
      opener={opener}
      dirty={dirty}
      pending={runner.pending}
      onClose={onClose}
      foot={(requestClose) => (
        <>
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
        </>
      )}
    >
      {body()}
      {criticalityBlock()}
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
    </PanelShell>
  );
}
