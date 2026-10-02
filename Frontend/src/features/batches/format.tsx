/** Kleine weergavehulpjes voor het batchdetailscherm. */

import { Term } from '../../terms/Term.tsx';

/**
 * Een teller is `null` wanneer de backend hem (nog) niet vastgesteld heeft — nooit hetzelfde als `0`
 * (zelfde regel als scherm 0 en scherm 3). Getoond als "—" met een tooltip.
 */
export function Count({ value }: { value: number | null }) {
  if (value === null) {
    return (
      <span title="niet vastgesteld" style={{ textDecoration: 'underline dotted', cursor: 'help' }}>
        —
      </span>
    );
  }
  return <>{value}</>;
}

/** Het Nederlandse woord (met uitleg in de tooltip) bij een teller van een batch; `counter` is de veldnaam van de teller. */
export function CounterLabel({ counter }: { counter: string }) {
  return <Term domain="batchCounter" code={counter} />;
}
