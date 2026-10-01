/**
 * Inklapbare "Technische details (voor support)": de ruwe codes en ids, klein en standaard dicht. Het
 * Nederlandse woord blijft altijd de hoofdtekst (V7); dit blok is voor wie met support belt.
 */

import type { ReactNode } from 'react';
import styles from './Term.module.css';

export type TechnicalDetailItem = { name: string; value: ReactNode };
export type TechnicalDetailsProps = { items: readonly TechnicalDetailItem[] };

export function TechnicalDetails({ items }: TechnicalDetailsProps) {
  if (items.length === 0) {
    return null;
  }
  return (
    <details className={`${styles.details} ${styles.technical}`}>
      <summary className={styles.summary}>Technische details (voor support)</summary>
      <dl className={styles.technicalList}>
        {items.map((item) => (
          <div key={item.name} style={{ display: 'contents' }}>
            <dt>{item.name}</dt>
            <dd>{item.value}</dd>
          </div>
        ))}
      </dl>
    </details>
  );
}
