/**
 * CD-F — het blok "Standaarden bij aanmaak" op de koppelingskaart (tab "Koppelingen en taken"): de btw-code en de eenheid
 * die een artikel krijgt dat CatalogImport voor deze koppeling aanmaakt (beslissingslog 2026-10-23 "Aanmaakstandaard per
 * koppeling: uitwerking (CD)"), met de herkomst ("Uit legacy") en wie het wijzigde. Lezen kan met `READ` (enkel de
 * codes; de Prodis-lijsten vragen `MANAGE`); "Wijzigen" opent een kleine editor onder het blok met twee keuzelijsten
 * (`Combobox`, client-side gefilterd, vrije tekst toegestaan: de server controleert bij het bewaren). Een ontbrekende
 * lijst (409/503) wordt een gewoon tekstveld met "Prodis-lijst niet beschikbaar". Leeg laten wist de standaard.
 *
 * Bestaande artikels worden nooit aangepast; staat de vlag "standaarden overschrijven" op de importdefinitie aan, dan
 * zegt een gedempte regel dat een nieuwe overname uit legacy deze waarden overschrijft.
 */
import { useId, useState } from 'react';
import { usePermissionGate } from '../../actor/permissions.ts';
import { setLinkCreationDefaults } from '../../api/creationDefaults.ts';
import {
  PERMISSION_MANAGE,
  type ImportLinkRow,
  type LinkCreationDefaultsView,
  type ProdisUnitOfMeasure,
  type ProdisVatCode,
} from '../../api/types.ts';
import button from '../../components/Button.module.css';
import { Combobox } from '../../components/Combobox.tsx';
import { Field } from '../../components/Field.tsx';
import { ToneBadge } from '../../components/StatusBadge.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { formatDateTime } from '../../format.ts';
import { useAction } from '../../hooks/useAction.ts';
import { CREATION_DEFAULTS_TEXT as TEXT } from '../../terms/creationDefaults.ts';
import { term, termLabel } from '../../terms/index.ts';
import type { ProdisLists } from './creationDefaultsData.ts';
import styles from './CreationDefaults.module.css';

type CodeInputProps = {
  id: string;
  value: string;
  onChange: (value: string) => void;
  options: { value: string; label: string }[];
  placeholder: string;
  status: ProdisLists['status'];
};

function CodeInput({ id, value, onChange, options, placeholder, status }: CodeInputProps) {
  const noteId = useId();
  if (status === 'off' || status === 'unavailable') {
    // Geen lijst: een gewoon tekstveld; de server controleert de code bij het bewaren.
    return (
      <>
        <input
          id={id}
          className={styles.input}
          type="text"
          value={value}
          maxLength={50}
          autoComplete="off"
          aria-describedby={status === 'unavailable' ? noteId : undefined}
          onChange={(event) => onChange(event.target.value)}
        />
        {status === 'unavailable' && (
          <p className={styles.meta} id={noteId} role="note" data-testid={`${id}-unavailable`}>
            {TEXT.listUnavailable}. {TEXT.listUnavailableHint}
          </p>
        )}
      </>
    );
  }
  return (
    <Combobox
      id={id}
      value={value}
      onChange={onChange}
      onSelect={(option) => onChange(option.value)}
      options={options}
      filter
      loading={status === 'loading'}
      maxLength={50}
      placeholder={placeholder}
      emptyText={TEXT.noResults}
      loadingText={TEXT.listLoading}
    />
  );
}

function CreationDefaultsEditor({
  link,
  lists,
  onCancel,
  onSaved,
}: {
  link: ImportLinkRow;
  lists: ProdisLists;
  onCancel: () => void;
  onSaved: (view: LinkCreationDefaultsView) => void;
}) {
  const [vat, setVat] = useState(link.defaultVatCode ?? '');
  const [unit, setUnit] = useState(link.defaultUnitCode ?? '');
  const { execute, pending, error, reset } = useAction((vatCode: string | null, unitCode: string | null) =>
    setLinkCreationDefaults(link.id, { vatCode, unitCode }),
  );

  async function save() {
    const view = await execute(vat.trim() === '' ? null : vat.trim(), unit.trim() === '' ? null : unit.trim());
    if (view !== undefined) {
      onSaved(view);
    }
  }

  return (
    <div
      className={styles.editor}
      role="group"
      aria-label={TEXT.changeTitle(link.code)}
      data-testid={`creation-defaults-editor-${link.id}`}
    >
      <Field label={TEXT.vat} htmlFor={`creation-vat-${link.id}`} hint={TEXT.clearHint}>
        <CodeInput
          id={`creation-vat-${link.id}`}
          value={vat}
          onChange={setVat}
          options={lists.vat.map((row: ProdisVatCode) => ({ value: row.code, label: TEXT.vatOption(row) }))}
          placeholder={TEXT.vatPlaceholder}
          status={lists.status}
        />
      </Field>
      <Field label={TEXT.unit} htmlFor={`creation-unit-${link.id}`}>
        <CodeInput
          id={`creation-unit-${link.id}`}
          value={unit}
          onChange={setUnit}
          options={lists.units.map((row: ProdisUnitOfMeasure) => ({ value: row.code, label: TEXT.unitOption(row) }))}
          placeholder={TEXT.unitPlaceholder}
          status={lists.status}
        />
      </Field>
      {error !== null && <ErrorBanner error={error} />}
      <div className={styles.actions}>
        <button
          type="button"
          className={`${button.primary} ${button.small}`}
          disabled={pending}
          data-testid={`creation-defaults-save-${link.id}`}
          onClick={() => void save()}
        >
          {pending ? TEXT.saving : TEXT.save}
        </button>
        <button
          type="button"
          className={`${button.secondary} ${button.small}`}
          disabled={pending}
          onClick={() => {
            reset();
            onCancel();
          }}
        >
          {TEXT.cancel}
        </button>
      </div>
    </div>
  );
}

export function CreationDefaultsBlock({
  link,
  overwriteEnabled,
  lists,
  onSaved,
}: {
  link: ImportLinkRow;
  /** De vlag "standaarden overschrijven" van de importdefinitie van deze koppeling. */
  overwriteEnabled: boolean;
  lists: ProdisLists;
  /** Herlaadt het leverancierspakket zodat de koppelingsrij de bewaarde waarden draagt. */
  onSaved: () => void;
}) {
  const manageGate = usePermissionGate(PERMISSION_MANAGE);
  const [editing, setEditing] = useState(false);
  const [saved, setSaved] = useState<LinkCreationDefaultsView | null>(null);

  // Direct na het bewaren toont het blok het antwoord van de server; de herlaadde rij draagt daarna dezelfde waarden.
  const current: Pick<
    ImportLinkRow,
    'defaultVatCode' | 'defaultUnitCode' | 'creationDefaultsOrigin' | 'creationDefaultsUpdatedAt' | 'creationDefaultsUpdatedBy'
  > = saved ?? link;
  const vatCode = current.defaultVatCode ?? null;
  const unitCode = current.defaultUnitCode ?? null;
  const origin = current.creationDefaultsOrigin ?? null;
  const updatedBy = current.creationDefaultsUpdatedBy ?? null;
  const updatedAt = current.creationDefaultsUpdatedAt ?? null;
  const titleId = `creation-defaults-title-${link.id}`;

  return (
    <div className={styles.block} role="group" aria-labelledby={titleId} data-testid={`creation-defaults-${link.id}`}>
      <div className={styles.head}>
        <h4 className={styles.title} id={titleId}>
          {TEXT.title}
        </h4>
        {origin === 'LEGACY' && <ToneBadge label={TEXT.legacyBadge} tone="neutral" />}
      </div>
      <dl className={styles.dl}>
        <dt>{TEXT.vat}</dt>
        <dd data-testid={`creation-vat-value-${link.id}`}>
          {vatCode === null ? (
            <span className={styles.unset}>{TEXT.unset}</span>
          ) : (
            TEXT.vatValue(vatCode, lists.vat.find((row) => row.code === vatCode))
          )}
        </dd>
        <dt>{TEXT.unit}</dt>
        <dd data-testid={`creation-unit-value-${link.id}`}>
          {unitCode === null ? (
            <span className={styles.unset}>{TEXT.unset}</span>
          ) : (
            TEXT.unitValue(unitCode, lists.units.find((row) => row.code === unitCode))
          )}
        </dd>
      </dl>
      {updatedBy !== null && (
        <p className={styles.meta} data-testid={`creation-defaults-changed-${link.id}`}>
          {TEXT.changedBy(updatedBy, updatedAt === null ? null : formatDateTime(updatedAt))}
        </p>
      )}
      <p className={styles.meta}>{TEXT.note}</p>
      {overwriteEnabled && (
        <p className={styles.meta} data-testid={`creation-defaults-overwrite-note-${link.id}`}>
          {TEXT.overwriteNote}
        </p>
      )}
      {saved !== null && !editing && (
        <>
          <p className={styles.saved} role="status" data-testid={`creation-defaults-saved-${link.id}`}>
            {TEXT.saved}
          </p>
          {saved.warnings.length > 0 && (
            <ul className={styles.warnings} data-testid={`creation-defaults-warnings-${link.id}`}>
              {saved.warnings.map((warning, index) => {
                const field = TEXT.warningField(warning.field);
                return (
                  <li key={`${warning.code}-${warning.field ?? ''}-${index}`}>
                    {field !== null && `${field}: `}
                    {termLabel('legacyReportCode', warning.code, 'Waarschuwing')}.{' '}
                    {term('legacyReportCode', warning.code).uitleg}
                  </li>
                );
              })}
            </ul>
          )}
        </>
      )}
      {!editing && (
        <div className={styles.actions}>
          <button
            type="button"
            className={`${button.secondary} ${button.small}`}
            disabled={!manageGate.allowed}
            title={manageGate.allowed ? undefined : manageGate.reason}
            data-testid={`creation-defaults-edit-${link.id}`}
            onClick={() => {
              setSaved(null);
              setEditing(true);
            }}
          >
            {TEXT.change}
          </button>
          {!manageGate.allowed && <span className={styles.reason}>{manageGate.reason}</span>}
        </div>
      )}
      {editing && (
        <CreationDefaultsEditor
          link={{ ...link, defaultVatCode: vatCode, defaultUnitCode: unitCode }}
          lists={lists}
          onCancel={() => setEditing(false)}
          onSaved={(view) => {
            setSaved(view);
            setEditing(false);
            onSaved();
          }}
        />
      )}
    </div>
  );
}
