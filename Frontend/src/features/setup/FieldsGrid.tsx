/**
 * E1 — het Velden-grid van "Bestand en velden" (mock `docs/mock/setup-redesign-mock.html`, beslissingslog 2026-10-23
 * "Nieuwe bewerkervaring"): dezelfde lay-out om te lezen en te bewerken. Groepen: Herkenning van het artikel
 * (Leverancier, Groep, Referentie, Kortingscode; schakelaar "Hoofdletters negeren" in de groepskop), Basis (Basisprijs,
 * Omschrijving, Valuta) en Extra velden. Kolommen: Veld | Bron | Bewerking | Kritiek | Verplicht | Status, met "Uit legacy"
 * erachter bij een versie uit legacy en, enkel in Bewerken, een kolom met het potlood per rij van Herkenning en Basis.
 * Extra velden zijn in E1 alleen-lezen (bewerken via het zijpaneel volgt in E2).
 */

import { useId, type ReactNode } from 'react';
import type { MappingView, RevisionDetail, RevisionLegacyOrigin } from '../../api/types.ts';
import { PencilIcon } from '../../components/icons.tsx';
import { EDITOR_TEXT as TEXT } from '../../terms/setupEditor.ts';
import { SUPPLIER_TEXT } from '../../terms/setupDashboard.ts';
import { Term } from '../../terms/Term.tsx';
import { LegacyHint, UncoveredLegacyLines, legacyColumnLabel } from './LegacyOriginParts.tsx';
import { lineForRow, type LegacyRowKey } from './legacyOrigin.ts';
import {
  basisRows,
  coreRowMissing,
  identityRows,
  mappingChips,
  mappingMissing,
  type CoreRow,
  type CoreRowKey,
  type GridSource,
} from './fieldsGridModel.ts';
import { FieldKeyTerm } from './revisionDetailParts.tsx';
import editor from './FieldsEditor.module.css';
import styles from './SetupScreens.module.css';

const COMMON = SUPPLIER_TEXT;

export type FieldsGridProps = {
  revision: RevisionDetail;
  legacy: RevisionLegacyOrigin | null;
  /** `true` = Bewerken: de rijen van Herkenning en Basis krijgen een potlood, de groepskop een schakelaar. */
  editing?: boolean;
  /** Het potlood van een rij is aangeklikt; `opener` is de knop waar de focus na het sluiten naartoe terugkeert. */
  onEditRow?: (key: CoreRowKey, opener: HTMLElement) => void;
  /** De schakelaar "Hoofdletters negeren" is aangeklikt (een identiteitswijziging: het paneel vraagt de bevestiging). */
  onEditUppercase?: (opener: HTMLElement) => void;
};

function Unset({ children }: { children: string }) {
  return <span className={styles.unset}>{children}</span>;
}

function SourceCell({ source }: { source: GridSource }) {
  if (source.kind === 'column') {
    return (
      <>
        ← <span className={styles.code}>{source.column}</span>
      </>
    );
  }
  if (source.kind === 'fixed') {
    return <>{COMMON.fixedValue(source.value)}</>;
  }
  return <Unset>{COMMON.notUsed}</Unset>;
}

function mappingSource(mapping: MappingView): ReactNode {
  if (mapping.valueKind === 'SOURCE_FIELD') {
    return <span className={styles.code}>{mapping.sourceReference}</span>;
  }
  if (mapping.valueKind === 'FIXED_VALUE' && mapping.fixedValue != null) {
    return COMMON.fixedValue(mapping.fixedValue);
  }
  return <Term domain="mappingValueKind" code={mapping.valueKind} unknownLabel="Andere herkomst" />;
}

function Chips({ chips }: { chips: readonly string[] }) {
  if (chips.length === 0) {
    return <Unset>{TEXT.noOperation}</Unset>;
  }
  return (
    <span className={editor.chips}>
      {chips.map((chip, index) => (
        <span key={`${index}-${chip}`} className={editor.chip}>
          {chip}
        </span>
      ))}
    </span>
  );
}

function StatusBadgeCell({ missing }: { missing: boolean }) {
  return (
    <span className={`${editor.status} ${missing ? editor.statusMissing : editor.statusOk}`}>
      {missing ? TEXT.statusMissing : TEXT.statusOk}
    </span>
  );
}

export function FieldsGrid({ revision, legacy, editing = false, onEditRow, onEditUppercase }: FieldsGridProps) {
  const identityHeadId = useId();
  const basisHeadId = useId();
  const extraHeadId = useId();
  const head = TEXT.head;
  const legacyLabel = legacy === null ? '' : legacyColumnLabel(legacy);
  const columns = 6 + (legacy === null ? 0 : 1) + (editing ? 1 : 0);

  const legacyCell = (key: LegacyRowKey | null): ReactNode =>
    legacy === null ? null : (
      <td data-label={legacyLabel} data-testid="legacy-cell">
        <LegacyHint line={key === null ? null : lineForRow(legacy.lines, key)} />
      </td>
    );

  const actionCell = (row: CoreRow | null): ReactNode =>
    !editing ? null : (
      <td className={editor.actionCell}>
        {row !== null && (
          <button
            type="button"
            className={editor.iconButton}
            aria-label={TEXT.editRowLabel(row.label)}
            data-testid={`edit-row-${row.key}`}
            onClick={(event) => onEditRow?.(row.key, event.currentTarget)}
          >
            <PencilIcon size={18} />
          </button>
        )}
      </td>
    );

  const coreRow = (row: CoreRow) => (
    <tr key={row.key} className={coreRowMissing(row) ? editor.rowWarn : undefined} data-testid={`grid-row-${row.key}`}>
      <td data-label={head.field}>
        <strong>{row.label}</strong>
      </td>
      <td data-label={head.source}>
        <SourceCell source={row.source} />
      </td>
      <td data-label={head.operation}>{COMMON.unknown}</td>
      <td data-label={head.critical}>{COMMON.unknown}</td>
      <td data-label={head.required}>{row.required ? COMMON.yes : COMMON.no}</td>
      <td data-label={head.status}>
        <StatusBadgeCell missing={coreRowMissing(row)} />
      </td>
      {legacyCell(row.legacyKey)}
      {actionCell(row)}
    </tr>
  );

  const groupRow = (id: string, title: ReactNode, extra?: ReactNode) => (
    <tr className={editor.groupRow}>
      <td colSpan={columns}>
        <span id={id} className={editor.groupTitle}>
          {title}
        </span>
        {extra}
      </td>
    </tr>
  );

  const uppercase = revision.identityUppercase;
  const uppercaseText = uppercase === undefined ? '' : TEXT.uppercaseLabel(uppercase);
  const uppercaseControl =
    uppercase === undefined ? null : editing ? (
      <button
        type="button"
        role="switch"
        aria-checked={uppercase}
        className={`${editor.chip} ${editor.chipButton}`}
        data-testid="uppercase-switch"
        onClick={(event) => onEditUppercase?.(event.currentTarget)}
      >
        {uppercaseText}
      </button>
    ) : (
      <span className={editor.chip} data-testid="uppercase-chip">
        {uppercaseText}
      </span>
    );

  return (
    <section className={styles.vdSection} aria-labelledby="vd-fields" data-testid="read-fields">
      <h3 id="vd-fields">{SUPPLIER_TEXT.sections.fields}</h3>
      <div className={styles.tableWrap}>
        <table className={`${styles.table} ${styles.stack} ${editor.grid}`} aria-label={TEXT.gridLabel}>
          <thead>
            <tr>
              <th>{head.field}</th>
              <th>{head.source}</th>
              <th>{head.operation}</th>
              <th>{head.critical}</th>
              <th>{head.required}</th>
              <th>{head.status}</th>
              {legacy !== null && <th data-testid="legacy-column-head">{legacyLabel}</th>}
              {editing && (
                <th>
                  <span className={styles.srOnly}>{head.actions}</span>
                </th>
              )}
            </tr>
          </thead>
          <tbody aria-labelledby={identityHeadId} data-testid="read-identity">
            {groupRow(identityHeadId, TEXT.groups.identity, uppercaseControl)}
            {identityRows(revision).map(coreRow)}
          </tbody>
          <tbody aria-labelledby={basisHeadId} data-testid="read-basis">
            {groupRow(basisHeadId, TEXT.groups.basis)}
            {basisRows(revision).map(coreRow)}
          </tbody>
          <tbody aria-labelledby={extraHeadId} data-testid="read-extra">
            {groupRow(extraHeadId, TEXT.groups.extra(revision.mappings.length))}
            {revision.mappings.map((mapping) => {
              const missing = mappingMissing(mapping);
              return (
                <tr
                  key={mapping.id}
                  className={missing ? editor.rowWarn : undefined}
                  data-testid={`read-field-${mapping.id}`}
                >
                  <td data-label={head.field}>
                    <strong>
                      <FieldKeyTerm code={mapping.targetFieldCode} />
                    </strong>
                  </td>
                  <td data-label={head.source}>{mappingSource(mapping)}</td>
                  <td data-label={head.operation}>
                    <Chips chips={mappingChips(mapping)} />
                  </td>
                  <td data-label={head.critical}><Term domain="criticality" code={mapping.criticality} unknownLabel="Andere kritiek" /></td>
                  <td data-label={head.required}>
                    {mapping.required === undefined ? COMMON.unknown : mapping.required ? COMMON.yes : COMMON.no}
                  </td>
                  <td data-label={head.status}>
                    <StatusBadgeCell missing={missing} />
                  </td>
                  {legacyCell({ mappingCode: mapping.targetFieldCode })}
                  {actionCell(null)}
                </tr>
              );
            })}
            {revision.mappings.length === 0 && (
              <tr>
                <td colSpan={columns} className={styles.moreNote}>
                  {COMMON.noFields}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      {legacy !== null && <UncoveredLegacyLines origin={legacy} revision={revision} />}
    </section>
  );
}
