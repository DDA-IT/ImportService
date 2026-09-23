/**
 * Gedeelde tabel: kolomdefinities als data, geen generieke tabelmotor (§6). Kent geen scherm en geen
 * backend — een aanroeper geeft rijen en kolommen mee, `DataTable` weet niet waar ze vandaan komen.
 */

import type { ReactNode } from 'react';
import styles from './DataTable.module.css';

export type DataTableColumn<T> = {
  key: string;
  header: string;
  render: (row: T) => ReactNode;
  align?: 'left' | 'right' | 'center';
};

export type DataTableProps<T> = {
  columns: readonly DataTableColumn<T>[];
  rows: readonly T[];
  rowKey: (row: T) => string | number;
  emptyMessage?: string;
};

function alignClass(align: DataTableColumn<unknown>['align']): string | undefined {
  if (align === 'right') {
    return styles.alignRight;
  }
  if (align === 'center') {
    return styles.alignCenter;
  }
  return undefined;
}

export function DataTable<T>({ columns, rows, rowKey, emptyMessage = 'Geen gegevens.' }: DataTableProps<T>) {
  if (rows.length === 0) {
    return <p className={styles.empty}>{emptyMessage}</p>;
  }

  return (
    <table className={styles.table}>
      <thead>
        <tr>
          {columns.map((column) => (
            <th key={column.key} className={alignClass(column.align)}>
              {column.header}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={rowKey(row)}>
            {columns.map((column) => (
              <td key={column.key} className={alignClass(column.align)}>
                {column.render(row)}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}
