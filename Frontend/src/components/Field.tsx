/**
 * Gedeelde labelwrapper voor formuliervelden: label, optioneel een sterretje voor "verplicht", een
 * hint, en een foutmelding. Kent geen scherm en geen backend (§2, regel 1 van het ontwerp).
 */

import type { ReactNode } from 'react';
import { WhatIsThis } from '../terms/WhatIsThis.tsx';
import styles from './Field.module.css';

export type FieldProps = {
  label: string;
  htmlFor: string;
  required?: boolean;
  hint?: string;
  /** Optionele uitleg in gewoon Nederlands, inklapbaar onder "Wat betekent dit?" (NT-5). */
  help?: ReactNode;
  error?: string | null;
  children: ReactNode;
};

export function Field({ label, htmlFor, required = false, hint, help, error = null, children }: FieldProps) {
  return (
    <div className={styles.field}>
      <label className={styles.label} htmlFor={htmlFor}>
        {label}
        {required && (
          <span className={styles.required} aria-hidden="true">
            {' '}
            *
          </span>
        )}
      </label>
      {children}
      {error === null && hint !== undefined && <p className={styles.hint}>{hint}</p>}
      {help !== undefined && <WhatIsThis>{help}</WhatIsThis>}
      {error !== null && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}
    </div>
  );
}
