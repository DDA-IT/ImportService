/**
 * E5b — "Per kolom van het bestand" (mock `docs/mock/setup-redesign-mock.html`, Velden → Per kolom van het bestand; beslissingslog
 * 2026-10-23 "Editor-API-gaten" EB5/E5b). De schakelaar staat naast het grid in de Velden-balk (uitgeschakeld zolang er geen
 * testbestand gelezen is); de tabel toont per kolom van het testbestand: Kolom | Kolomnaam | Voorbeeldwaarden | Gaat naar.
 * De koppeling kolom → veld/filter wordt hier gerekend tegen de versie op het scherm (`columnPreviewModel.ts`). De gegevens
 * leven enkel in de toestand van de pagina: nooit in het adres of in browseropslag.
 */

import { useState } from 'react';
import type { ColumnPreview, RevisionDetail } from '../../api/types.ts';
import button from '../../components/Button.module.css';
import { COLUMN_PREVIEW_TEXT as TEXT } from '../../terms/columnPreview.ts';
import {
  buildColumnPreviewModel,
  sourceForColumn,
  tableItems,
  unreadableLines,
  type PreviewColumnView,
} from './columnPreviewModel.ts';
import cp from './ColumnPreview.module.css';
import editor from './FieldsEditor.module.css';
import styles from './SetupScreens.module.css';

export type FieldsView = 'field' | 'column';

/** De schakelaar Per veld | Per kolom van het bestand; de tweede knop is uitgeschakeld zonder voorbeeld. */
export function FieldsViewToggle({
  value,
  columnAvailable,
  onChange,
}: {
  value: FieldsView;
  columnAvailable: boolean;
  onChange: (view: FieldsView) => void;
}) {
  return (
    <div className={cp.toolbar} data-testid="fields-view-toolbar">
      <div className={editor.segment} role="group" aria-label={TEXT.switchLabel}>
        <button type="button" aria-pressed={value === 'field'} data-testid="fields-view-field" onClick={() => onChange('field')}>
          {TEXT.byField}
        </button>
        <button
          type="button"
          aria-pressed={value === 'column'}
          disabled={!columnAvailable}
          title={columnAvailable ? undefined : TEXT.byColumnDisabled}
          data-testid="fields-view-column"
          onClick={() => onChange('column')}
        >
          {TEXT.byColumn}
        </button>
      </div>
    </div>
  );
}

/** Wat bij "Koppelen aan veld…" doorgegeven wordt: de kolom en de bron zoals de versie ze zou herkennen. */
export type ColumnLinkRequest = { position: number; header: string | null; source: string };

export type ColumnPreviewViewProps = {
  preview: ColumnPreview;
  /** De versie zoals ze NU op het scherm staat (niet die van de proefinlezing). */
  revision: RevisionDetail;
  /** Bewerken staat aan: enkel dan "Koppelen aan veld…". */
  editing?: boolean;
  /** Opent de bestaande "Veld toevoegen"-stroom voor deze kolom. */
  onLinkColumn?: (request: ColumnLinkRequest, opener: HTMLElement) => void;
};

function Samples({ column }: { column: PreviewColumnView }) {
  if (column.samples.length === 0) {
    return <span className={styles.rowMeta}>{TEXT.noValues}</span>;
  }
  return (
    <ul className={cp.samples}>
      {column.samples.map((sample, index) =>
        sample.kind === 'value' ? (
          <li key={index} className={cp.sample} title={sample.text}>
            {sample.text}
          </li>
        ) : (
          <li key={index} className={cp.unreadable} data-testid={`column-unreadable-${column.position}`}>
            {TEXT.unreadableLine(sample.lineNumber)}
          </li>
        ),
      )}
    </ul>
  );
}

function StatusMessage({ preview }: { preview: ColumnPreview }) {
  let message: string | null = null;
  if (preview.status === 'LAYOUT_INVALID') {
    message = TEXT.statusLayoutInvalid;
  } else if (preview.status === 'FILE_EMPTY') {
    message = TEXT.statusFileEmpty;
  } else if (preview.status === 'HEADER_BEYOND_PREVIEW') {
    message = TEXT.statusHeaderBeyond(preview.layout.headerLineNumber ?? preview.headerLineNumber);
  } else if (preview.columns.length === 0) {
    message = preview.layout.hasHeader ? TEXT.headerUnreadable : TEXT.noColumns;
  }
  if (message === null) {
    return null;
  }
  return (
    <p className={editor.warning} role="status" data-testid="column-preview-status">
      {message}
    </p>
  );
}

export function ColumnPreviewView({ preview, revision, editing = false, onLinkColumn }: ColumnPreviewViewProps) {
  const [expanded, setExpanded] = useState<ReadonlySet<number>>(new Set());
  const model = buildColumnPreviewModel(preview, revision);
  const showTable = preview.status === 'AVAILABLE' && model.columns.length > 0;
  const unreadable = unreadableLines(preview);
  const referenceKind = revision.structureFieldReferenceKind;

  function toggleRun(from: number) {
    setExpanded((current) => {
      const next = new Set(current);
      if (next.has(from)) {
        next.delete(from);
      } else {
        next.add(from);
      }
      return next;
    });
  }

  function columnRow(column: PreviewColumnView) {
    return (
      <tr key={column.position} data-testid={`column-row-${column.position}`}>
        <td data-label={TEXT.head.column}>
          <span className={styles.code}>{column.position}</span>
        </td>
        <td data-label={TEXT.head.name}>
          {column.header !== null && column.header.trim() !== '' ? (
            column.header
          ) : (
            <span className={styles.rowMeta}>{TEXT.noName}</span>
          )}
        </td>
        <td data-label={TEXT.head.samples}>
          <Samples column={column} />
        </td>
        <td data-label={TEXT.head.goesTo} data-testid={`column-uses-${column.position}`}>
          {column.uses.length > 0 ? (
            <span className={editor.chips}>
              {column.uses.map((use) => (
                <span key={`${use.kind}:${use.label}`} className={editor.chip} data-use={use.kind}>
                  {use.label}
                </span>
              ))}
            </span>
          ) : (
            <span className={cp.runCell}>
              <span className={styles.rowMeta}>{TEXT.notUsed}</span>
              {editing && onLinkColumn !== undefined && (
                <button
                  type="button"
                  className={`${button.secondary} ${button.small}`}
                  aria-label={TEXT.linkLabel(column.position)}
                  data-testid={`column-link-${column.position}`}
                  onClick={(event) =>
                    onLinkColumn(
                      { position: column.position, header: column.header, source: sourceForColumn(column, referenceKind) },
                      event.currentTarget,
                    )
                  }
                >
                  {TEXT.link}
                </button>
              )}
            </span>
          )}
        </td>
      </tr>
    );
  }

  return (
    <section className={styles.vdSection} aria-labelledby="vd-column-preview" data-testid="column-preview">
      <h3 id="vd-column-preview">{TEXT.heading}</h3>
      <p className={styles.rowMeta}>{TEXT.notStored}</p>
      {model.outdated && (
        <div className={styles.banner} role="status" data-testid="column-preview-outdated">
          <p>{TEXT.outdated}</p>
        </div>
      )}
      <StatusMessage preview={preview} />
      {showTable && (
        <>
          <div className={cp.notes}>
            {preview.columnsTruncated && (
              <p className={styles.rowMeta} data-testid="column-preview-columns-truncated">
                {TEXT.columnsTruncated(model.columns.length)}
              </p>
            )}
            <p className={styles.rowMeta} data-testid="column-preview-rows-note">
              {preview.rowsTruncated ? TEXT.rowsTruncated(preview.rows.length) : TEXT.rowsShown(preview.rows.length)}
            </p>
            {unreadable.length > 0 && (
              <p className={styles.rowMeta} data-testid="column-preview-unreadable">
                {TEXT.unreadableNote(unreadable)}
              </p>
            )}
          </div>
          <div className={styles.tableWrap}>
            <table className={`${styles.table} ${styles.stack}`} aria-label={TEXT.tableLabel}>
              <thead>
                <tr>
                  <th scope="col">{TEXT.head.column}</th>
                  <th scope="col">{TEXT.head.name}</th>
                  <th scope="col">{TEXT.head.samples}</th>
                  <th scope="col">{TEXT.head.goesTo}</th>
                </tr>
              </thead>
              <tbody>
                {tableItems(model.columns).map((item) => {
                  if (item.kind === 'column') {
                    return columnRow(item.column);
                  }
                  const open = expanded.has(item.from);
                  return [
                    <tr key={`run-${item.from}`} className={cp.runRow} data-testid={`column-run-${item.from}`}>
                      <td colSpan={4}>
                        <span className={cp.runCell}>
                          <span>{TEXT.run(item.from, item.to)}</span>
                          <button
                            type="button"
                            className={`${button.secondary} ${button.small}`}
                            aria-expanded={open}
                            data-testid={`column-run-toggle-${item.from}`}
                            onClick={() => toggleRun(item.from)}
                          >
                            {open ? TEXT.runHide : TEXT.runShow}
                          </button>
                        </span>
                      </td>
                    </tr>,
                    ...(open ? item.columns.map((column) => columnRow(column)) : []),
                  ];
                })}
              </tbody>
            </table>
          </div>
        </>
      )}
      {model.missing.length > 0 && (
        <div data-testid="column-preview-missing">
          <p>
            <strong>{TEXT.missingHeading}</strong>
          </p>
          <ul className={cp.missing}>
            {model.missing.map((item) => (
              <li key={`${item.label}:${item.reference}`}>
                {item.byName ? TEXT.missingByName(item.label, item.reference) : TEXT.missingByPosition(item.label, item.reference)}
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}
