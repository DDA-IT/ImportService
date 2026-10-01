/**
 * "Wat betekent dit?" — korte, inklapbare uitleg naast een veld of begrip (native `<details>`, dus
 * toetsenbord- en schermlezervriendelijk zonder eigen script).
 */

import type { ReactNode } from 'react';
import styles from './Term.module.css';

export type WhatIsThisProps = { children: ReactNode };

export function WhatIsThis({ children }: WhatIsThisProps) {
  return (
    <details className={styles.details}>
      <summary className={styles.summary}>Wat betekent dit?</summary>
      <div className={styles.detailsBody}>{children}</div>
    </details>
  );
}
