/**
 * Toont een `ApiError` volgens `describe()` (zie `codes.ts`): titel, uitleg, "wat nu" en — altijd,
 * ook bij een bekende code — de technische regel `<code> · HTTP <status> · <pad>` (§4 regel 1).
 *
 * Draagt de code volgens `codes.ts` concrete servergegevens (`detail`, bv. de tot tien
 * conflictvoorbeelden bij `BUNDLE_OFFER_CONFLICT`), dan staat die tekst volledig en letterlijk onder de
 * uitleg, met behoud van eventuele regelafbrekingen (§10.5) — nooit ingekort.
 *
 * Plaatsing is de verantwoordelijkheid van de aanroeper: bij de actie die de fout veroorzaakte, niet
 * in een globale toast (§4, onderaan).
 */

import { describe } from './codes';
import type { ApiError } from '../api/http';
import styles from './ErrorBanner.module.css';

export type ErrorBannerProps = { error: ApiError };

export function ErrorBanner({ error }: ErrorBannerProps) {
  const { title, explanation, whatNow, technical, detail } = describe(error);

  return (
    <div className={styles.banner} role="alert">
      <p className={styles.title}>{title}</p>
      <p className={styles.explanation}>{explanation}</p>
      {whatNow !== null && <p className={styles.whatNow}>{whatNow}</p>}
      {detail !== null && (
        <div className={styles.detail}>
          <p className={styles.detailLabel}>Melding van de server (letterlijk):</p>
          <p className={styles.detailText} data-testid="error-detail">
            {detail}
          </p>
        </div>
      )}
      <p className={styles.technical}>{technical}</p>
    </div>
  );
}
