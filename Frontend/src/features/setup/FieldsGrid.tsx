/**
 * E1 — het Velden-grid van "Bestand en velden" (mock `docs/mock/setup-redesign-mock.html`, beslissingslog 2026-10-23
 * "Nieuwe bewerkervaring"): dezelfde lay-out om te lezen en te bewerken. Groepen: Herkenning van het artikel
 * (Leverancier, Groep, Referentie, Kortingscode; schakelaar "Hoofdletters negeren" in de groepskop), Basis (Basisprijs,
 * Omschrijving, Valuta) en Extra velden. Kolommen: Veld | Bron | Bewerking | Kritiek | Verplicht | Status, met "Uit legacy"
 * erachter bij een versie uit legacy en, enkel in Bewerken, een kolom met het potlood per rij van Herkenning en Basis.
 * E2: in Bewerken hebben ook de Extra velden een potlood (zijpaneel voor een mapping), staat boven het grid "Veld
 * toevoegen", komt er een ingeklapte groep "Nog niet gekoppeld (n)" met de velden van de catalogus zonder rij, en toont de
 * weergave Verkoopprijs 1-5 (%) als één uitklapbare groep wanneer alle vijf dezelfde bron en bewerking hebben.
 */

import { useId, useState, type ReactNode } from 'react';
import type { MappingView, RevisionDetail, RevisionLegacyOrigin } from '../../api/types.ts';
import button from '../../components/Button.module.css';
import { PencilIcon } from '../../components/icons.tsx';
import { EDITOR_TEXT as TEXT, MAPPING_TEXT } from '../../terms/setupEditor.ts';
import { SUPPLIER_TEXT } from '../../terms/setupDashboard.ts';
import { Term } from '../../terms/Term.tsx';
import { DiffChip, RemovedStrip } from './DiffParts.tsx';
import type { RevisionDiff } from './revisionDiffModel.ts';
import { LegacyHint, UncoveredLegacyLines, legacyColumnLabel } from './LegacyOriginParts.tsx';
import { lineForRow, type LegacyRowKey } from './legacyOrigin.ts';
import {
  basisRows,
  coreRowMissing,
  identityRows,
  mappingChips,
  mappingMissing,
  type CoreRow,
  coreRowCriticality,
  unmappedFields,
  vkpGroup,
  VKP_CODES,
  type CoreRowKey,
  type FieldCatalogue,
  type GridSource,
} from './fieldsGridModel.ts';
import { FieldKeyTerm } from './revisionDetailParts.tsx';
import { termLabel } from '../../terms/index.ts';
import editor from './FieldsEditor.module.css';
import styles from './SetupScreens.module.css';

const COMMON = SUPPLIER_TEXT;

export type FieldsGridProps = {
  revision: RevisionDetail;
  legacy: RevisionLegacyOrigin | null;
  /** EF1: de veldcatalogus; `null` zolang ze laadt (dan geen "Nog niet gekoppeld" en een streepje bij onbekende kritiek). */
  catalogue?: FieldCatalogue | null;
  /** `true` = Bewerken: de rijen van Herkenning en Basis krijgen een potlood, de groepskop een schakelaar. */
  editing?: boolean;
  /** Het potlood van een rij is aangeklikt; `opener` is de knop waar de focus na het sluiten naartoe terugkeert. */
  onEditRow?: (key: CoreRowKey, opener: HTMLElement) => void;
  /** De schakelaar "Hoofdletters negeren" is aangeklikt (een identiteitswijziging: het paneel vraagt de bevestiging). */
  onEditUppercase?: (opener: HTMLElement) => void;
  /** E2: het potlood van een extra veld (op doelveld, want een wijziging geeft de rij een nieuw id). */
  onEditMapping?: (code: string, opener: HTMLElement) => void;
  /** E2: "Veld toevoegen" in de kop van het grid. */
  onAddField?: (opener: HTMLElement) => void;
  /** E2: "Koppelen" bij een veld in "Nog niet gekoppeld". */
  onMapField?: (code: string, opener: HTMLElement) => void;
  /** E4: het verschil met de basisversie: chips Nieuw/Gewijzigd en de strook "Niet meer in deze versie". */
  diff?: RevisionDiff | null;
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

export function FieldsGrid({
  revision,
  legacy,
  catalogue = null,
  editing = false,
  onEditRow,
  onEditUppercase,
  onEditMapping,
  onAddField,
  onMapField,
  diff = null,
}: FieldsGridProps) {
  const [unmappedOpen, setUnmappedOpen] = useState(false);
  const [vkpOpen, setVkpOpen] = useState(false);
  const identityHeadId = useId();
  const basisHeadId = useId();
  const extraHeadId = useId();
  const unmappedHeadId = useId();
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

  /** EF1: de geldende kritiek van een kernrij (overrule van deze versie, anders de standaard); wijzigen in het zijpaneel (EF2). */
  const coreCriticality = (row: CoreRow): ReactNode => {
    const value = coreRowCriticality(row.key, revision, catalogue);
    return value === null ? COMMON.unknown : <Term domain="criticality" code={value} unknownLabel="Andere kritiek" />;
  };

  const coreRow = (row: CoreRow) => (
    <tr key={row.key} className={coreRowMissing(row) ? editor.rowWarn : undefined} data-testid={`grid-row-${row.key}`}>
      <td data-label={head.field}>
        <strong>{row.label}</strong>
        <DiffChip kind={diff?.scalarKinds[row.key]} testId={`diff-chip-${row.key}`} />
      </td>
      <td data-label={head.source}>
        <SourceCell source={row.source} />
      </td>
      <td data-label={head.operation}>{COMMON.unknown}</td>
      <td data-label={head.critical} data-testid={`critical-${row.key}`}>
        {coreCriticality(row)}
      </td>
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

  const mappingActionCell = (mapping: MappingView): ReactNode => {
    if (!editing) {
      return null;
    }
    const label = termLabel('fieldKey', mapping.targetFieldCode, 'een ander veld');
    return (
      <td className={editor.actionCell}>
        <button
          type="button"
          className={editor.iconButton}
          aria-label={TEXT.editRowLabel(label)}
          data-testid={`edit-mapping-${mapping.targetFieldCode}`}
          onClick={(event) => onEditMapping?.(mapping.targetFieldCode, event.currentTarget)}
        >
          <PencilIcon size={18} />
        </button>
      </td>
    );
  };

  const mappingRow = (mapping: MappingView, sub = false) => {
    const missing = mappingMissing(mapping);
    return (
      <tr
        key={mapping.targetFieldCode}
        className={`${missing ? editor.rowWarn : ''} ${sub ? editor.subRow : ''}`.trim() || undefined}
        data-testid={`read-field-${mapping.id}`}
      >
        <td data-label={head.field}>
          <strong>
            <FieldKeyTerm code={mapping.targetFieldCode} />
          </strong>
          <DiffChip kind={diff?.fieldKinds[mapping.targetFieldCode]} testId={`diff-chip-${mapping.targetFieldCode}`} />
        </td>
        <td data-label={head.source}>{mappingSource(mapping)}</td>
        <td data-label={head.operation}>
          <Chips chips={mappingChips(mapping)} />
        </td>
        <td data-label={head.critical}>
          <Term domain="criticality" code={mapping.criticality} unknownLabel="Andere kritiek" />
        </td>
        <td data-label={head.required}>
          {mapping.required === undefined ? COMMON.unknown : mapping.required ? COMMON.yes : COMMON.no}
        </td>
        <td data-label={head.status}>
          <StatusBadgeCell missing={missing} />
        </td>
        {legacyCell({ mappingCode: mapping.targetFieldCode })}
        {mappingActionCell(mapping)}
      </tr>
    );
  };

  /** Verkoopprijs 1-5 (%): één weergavegroep met de gemeenschappelijke bron en bewerking; bewerken blijft per rij. */
  const vkpSummaryRow = (group: MappingView[]) => {
    const first = group[0]!;
    const same = <T,>(pick: (mapping: MappingView) => T) => group.every((mapping) => pick(mapping) === pick(first));
    const missing = group.some(mappingMissing);
    return (
      <tr key="vkp-group" className={missing ? editor.rowWarn : undefined} data-testid="vkp-group">
        <td data-label={head.field}>
          <button
            type="button"
            className={editor.rowToggle}
            aria-expanded={vkpOpen}
            aria-label={`${MAPPING_TEXT.vkpGroup}: ${vkpOpen ? MAPPING_TEXT.collapse : MAPPING_TEXT.expand}`}
            data-testid="vkp-toggle"
            onClick={() => setVkpOpen((open) => !open)}
          >
            <span aria-hidden="true">{vkpOpen ? '▾' : '▸'}</span>
          </button>
          <strong>{MAPPING_TEXT.vkpGroup}</strong>
          <DiffChip
            kind={group.some((member) => diff?.fieldKinds[member.targetFieldCode] !== undefined) ? 'changed' : undefined}
            testId="diff-chip-vkp-group"
          />
        </td>
        <td data-label={head.source}>{mappingSource(first)}</td>
        <td data-label={head.operation}>
          <Chips chips={mappingChips(first)} />
        </td>
        <td data-label={head.critical}>
          {same((mapping) => mapping.criticality) ? (
            <Term domain="criticality" code={first.criticality} unknownLabel="Andere kritiek" />
          ) : (
            MAPPING_TEXT.vkpDiffers
          )}
        </td>
        <td data-label={head.required}>
          {same((mapping) => mapping.required) ? (first.required === undefined ? COMMON.unknown : first.required ? COMMON.yes : COMMON.no) : MAPPING_TEXT.vkpDiffers}
        </td>
        <td data-label={head.status}>
          <StatusBadgeCell missing={missing} />
        </td>
        {legacy === null ? null : <td data-label={legacyLabel} />}
        {editing && <td className={editor.actionCell} />}
      </tr>
    );
  };

  const extraRows = (): ReactNode[] => {
    const group = vkpGroup(revision.mappings);
    const rows: ReactNode[] = [];
    let groupDone = false;
    for (const mapping of revision.mappings) {
      const inGroup = group !== null && (VKP_CODES as readonly string[]).includes(mapping.targetFieldCode);
      if (!inGroup) {
        rows.push(mappingRow(mapping));
      } else if (!groupDone) {
        groupDone = true;
        rows.push(vkpSummaryRow(group));
        if (vkpOpen) {
          group.forEach((member) => rows.push(mappingRow(member, true)));
        }
      }
    }
    return rows;
  };

  const unmapped = editing && catalogue !== null ? unmappedFields(revision, catalogue) : [];

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
  const uppercaseDiff = <DiffChip kind={diff?.scalarKinds.uppercase} testId="diff-chip-uppercase" />;

  return (
    <section className={styles.vdSection} aria-labelledby="vd-fields" data-testid="read-fields">
      <h3 id="vd-fields">{SUPPLIER_TEXT.sections.fields}</h3>
      {editing && (
        <div className={editor.gridToolbar}>
          <button
            type="button"
            className={`${button.primary} ${button.small}`}
            data-testid="add-field"
            onClick={(event) => onAddField?.(event.currentTarget)}
          >
            {MAPPING_TEXT.addButton}
          </button>
          {catalogue?.source === 'fallback' && (
            <span className={styles.rowMeta} role="note" data-testid="grid-catalogue-fallback">
              {MAPPING_TEXT.catalogueFallback}
            </span>
          )}
        </div>
      )}
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
            {groupRow(
              identityHeadId,
              TEXT.groups.identity,
              <>
                {uppercaseControl}
                {uppercaseDiff}
              </>,
            )}
            {identityRows(revision).map(coreRow)}
          </tbody>
          <tbody aria-labelledby={basisHeadId} data-testid="read-basis">
            {groupRow(basisHeadId, TEXT.groups.basis)}
            {basisRows(revision).map(coreRow)}
          </tbody>
          <tbody aria-labelledby={extraHeadId} data-testid="read-extra">
            {groupRow(extraHeadId, TEXT.groups.extra(revision.mappings.length))}
            {extraRows()}
            {revision.mappings.length === 0 && (
              <tr>
                <td colSpan={columns} className={styles.moreNote}>
                  {COMMON.noFields}
                </td>
              </tr>
            )}
          </tbody>
          {unmapped.length > 0 && (
            <tbody aria-labelledby={unmappedHeadId} data-testid="read-unmapped">
              <tr className={editor.groupRow}>
                <td colSpan={columns}>
                  <button
                    type="button"
                    id={unmappedHeadId}
                    className={editor.groupToggle}
                    aria-expanded={unmappedOpen}
                    data-testid="unmapped-toggle"
                    onClick={() => setUnmappedOpen((open) => !open)}
                  >
                    <span aria-hidden="true">{unmappedOpen ? '▾' : '▸'}</span> {MAPPING_TEXT.unmappedGroup(unmapped.length)}
                  </button>
                </td>
              </tr>
              {unmappedOpen &&
                unmapped.map((field) => (
                  <tr key={field.code} data-testid={`unmapped-${field.code}`}>
                    <td data-label={head.field}>
                      <strong>{field.label}</strong>
                      {field.note !== null && (
                        <>
                          <br />
                          <span className={styles.rowMeta}>{field.note}</span>
                        </>
                      )}
                    </td>
                    <td data-label={head.source}>
                      <Unset>{MAPPING_TEXT.notMapped}</Unset>
                    </td>
                    <td />
                    <td />
                    <td />
                    <td data-label={head.status}>
                      <span className={editor.status}>{MAPPING_TEXT.notMapped}</span>
                    </td>
                    {legacy === null ? null : <td data-label={legacyLabel} />}
                    <td className={editor.actionCell}>
                      <button
                        type="button"
                        className={`${button.secondary} ${button.small}`}
                        aria-label={MAPPING_TEXT.mapLabel(field.label)}
                        data-testid={`map-field-${field.code}`}
                        onClick={(event) => onMapField?.(field.code, event.currentTarget)}
                      >
                        {MAPPING_TEXT.map}
                      </button>
                    </td>
                  </tr>
                ))}
            </tbody>
          )}
        </table>
      </div>
      <RemovedStrip entries={diff?.removedFields ?? []} testId="removed-fields" />
      {legacy !== null && <UncoveredLegacyLines origin={legacy} revision={revision} />}
    </section>
  );
}
