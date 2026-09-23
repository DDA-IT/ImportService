/**
 * Toont een `ApiError` volgens `describe()` (zie `codes.ts`): titel, uitleg, "wat nu" en — altijd,
 * ook bij een bekende code — de technische regel `<code> · HTTP <status> · <pad>` (§4 regel 1).
 *
 * Plaatsing is de verantwoordelijkheid van de aanroeper: bij de actie die de fout veroorzaakte, niet
 * in een globale toast (§4, onderaan).
 */

import { describe } from './codes';
import type { ApiError } from '../api/http';
import styles from './ErrorBanner.module.css';

export type ErrorBannerProps = { error: ApiError };

export function ErrorBanner({ error }: ErrorBannerProps) {
  const { title, explanation, whatNow, technical } = describe(error);

  return (
    <div className={styles.banner} role="alert">
      <p className={styles.title}>{title}</p>
      <p className={styles.explanation}>{explanation}</p>
      {whatNow !== null && <p className={styles.whatNow}>{whatNow}</p>}
      <p className={styles.technical}>{technical}</p>
    </div>
  );
}
