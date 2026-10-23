/**
 * De vertaaltabel (bron -> nieuwe waarde) met een plakvak, gedeeld door het extra-veldpaneel (`MappingPanel`): tab of `;`
 * per regel, eerst een voorbeeld, geen bestandsupload. De client controleert enkel de vorm (`mappingReplaceModel.ts`);
 * of de tabel klopt beslist de server. (E6: verhuisd uit de verwijderde `MappingReplaceDialog`.)
 */

import { useState } from 'react';
import button from '../../components/Button.module.css';
import { Field } from '../../components/Field.tsx';
import { parsePaste, type TableRow } from './mappingReplaceModel.ts';
import styles from './TableEditor.module.css';

/** De vertaaltabel (bron → nieuwe waarde) met een plakvak: tab of `;` per regel, eerst een voorbeeld. */
export function TableEditor({
  kind,
  rows,
  onChange,
  uid,
}: {
  kind: 'MAP' | 'MAP_CONTAINS';
  rows: TableRow[];
  onChange: (rows: TableRow[]) => void;
  uid: string;
}) {
  const [pasted, setPasted] = useState('');
  const preview = parsePaste(
    pasted,
    rows.map((row) => row.source),
  );
  const hasText = pasted.trim() !== '';

  function setRow(index: number, patch: Partial<TableRow>) {
    onChange(rows.map((row, at) => (at === index ? { ...row, ...patch } : row)));
  }

  return (
    <div className={styles.table} data-testid="value-map-editor">
      <h3 className={styles.section}>
        {kind === 'MAP' ? 'Omzettingstabel' : 'Omzettingstabel (op een deel van de waarde)'}
      </h3>
      <p className={styles.muted}>
        {kind === 'MAP'
          ? 'Elke bronwaarde krijgt de nieuwe waarde ernaast; een lege nieuwe waarde zet om naar leeg.'
          : 'De eerste rij waarvan de bronwaarde in de waarde voorkomt, bepaalt de nieuwe waarde; de volgorde telt.'}
      </p>
      {rows.length > 0 && (
        <div className={`${styles.tableRow} ${styles.tableHead}`}>
          <span>Bronwaarde</span>
          <span>Nieuwe waarde</span>
          <span />
        </div>
      )}
      {rows.map((row, index) => (
        // De volgorde is betekenisvol (MAP_CONTAINS) en rijen hebben geen eigen sleutel: de index is hier de identiteit.
        <div key={index} className={styles.tableRow} data-testid={`value-map-row-${index}`}>
          <input
            className={styles.cell}
            aria-label={`Bronwaarde rij ${index + 1}`}
            data-testid={`value-map-source-${index}`}
            value={row.source}
            onChange={(event) => setRow(index, { source: event.target.value })}
          />
          <input
            className={styles.cell}
            aria-label={`Nieuwe waarde rij ${index + 1}`}
            data-testid={`value-map-target-${index}`}
            value={row.target}
            onChange={(event) => setRow(index, { target: event.target.value })}
          />
          <button
            type="button"
            className={`${button.dangerOutlined} ${button.small}`}
            data-testid={`value-map-remove-${index}`}
            onClick={() => onChange(rows.filter((_, at) => at !== index))}
          >
            Verwijderen
          </button>
        </div>
      ))}
      <div className={styles.tableActions}>
        <button
          type="button"
          className={`${button.secondary} ${button.small}`}
          data-testid="value-map-add"
          onClick={() => onChange([...rows, { source: '', target: '' }])}
        >
          Rij toevoegen
        </button>
      </div>

      <div className={styles.paste}>
        <Field
          label="Regels plakken"
          htmlFor={`${uid}-paste`}
          hint="Eén regel per rij, twee kolommen: bronwaarde en nieuwe waarde, gescheiden door een tab (uit een spreadsheet) of een puntkomma."
        >
          <textarea
            id={`${uid}-paste`}
            data-testid="value-map-paste"
            value={pasted}
            onChange={(event) => setPasted(event.target.value)}
          />
        </Field>
        {hasText && (
          <div data-testid="value-map-preview">
            <p>
              Voorbeeld: {preview.rows.length} {preview.rows.length === 1 ? 'rij' : 'rijen'} klaar om toe te voegen
              {preview.errors.length > 0 ? `, ${preview.errors.length} met een fout` : ''}.
            </p>
            {preview.rows.length > 0 && (
              <ul className={styles.previewList}>
                {preview.rows.map((row, index) => (
                  <li key={index}>
                    {row.source} → {row.target === '' ? '(leeg)' : row.target}
                  </li>
                ))}
              </ul>
            )}
            {preview.errors.length > 0 && (
              <ul className={`${styles.previewList} ${styles.previewError}`} data-testid="value-map-preview-errors">
                {preview.errors.map((error) => (
                  <li key={error}>{error}</li>
                ))}
              </ul>
            )}
            {preview.errors.length > 0 && (
              <p className={styles.muted}>Er wordt niets toegevoegd zolang er fouten zijn; pas de tekst aan.</p>
            )}
          </div>
        )}
        <button
          type="button"
          className={`${button.secondary} ${button.small}`}
          data-testid="value-map-apply"
          disabled={!hasText || preview.errors.length > 0 || preview.rows.length === 0}
          onClick={() => {
            onChange([...rows, ...preview.rows]);
            setPasted('');
          }}
        >
          Toevoegen aan de tabel
        </button>
      </div>
    </div>
  );
}
