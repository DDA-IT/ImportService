/**
 * S9-e — de weergavehulpjes van de `MutationList`-cellen: `null` is "—", nooit 0 (§9.3), en bedragen worden
 * getoond zoals ze binnenkomen (§9.4).
 */

/* oxlint-disable react/only-export-components -- celhulpjes die JSX teruggeven, geen schermonderdelen met eigen fast-refresh-grens; bewust samen met `Dash` en `ChangeParts` in één bestand (S9-e) */

import { Term } from '../../terms/Term.tsx';
import { formatDateTime } from '../../format.ts';
import styles from './MutationList.module.css';

/** "niet vastgesteld" — nooit als 0 getoond (§9.3). */
export function Dash() {
  return (
    <span className={styles.dash} title="niet vastgesteld">
      —
    </span>
  );
}

export function text(value: string | null) {
  return value === null || value === '' ? <Dash /> : <>{value}</>;
}

export function count(value: number | null) {
  return value === null ? <Dash /> : <>{value}</>;
}

export function dateTime(value: string | null) {
  return value === null ? <Dash /> : <>{formatDateTime(value)}</>;
}

/**
 * Toont een bedrag zoals het binnenkomt: nl-BE-scheidingstekens, geen afronding, geen aangenomen
 * munteenheid (§9.4). `null` is "—", nooit "0,00".
 */
export function amount(value: number | null, currency: string | null) {
  if (value === null) {
    return <Dash />;
  }
  const formatted = new Intl.NumberFormat('nl-BE', { maximumFractionDigits: 20 }).format(value);
  return <>{currency === null || currency === '' ? formatted : `${formatted} ${currency}`}</>;
}

/**
 * Wat een wijziging raakt: het masker is een lijst gescheiden door komma's, bv. `ARTICLE,PRICE,PRICE:AKP`. Elk deel
 * krijgt zijn Nederlandse woord; een code achter een dubbele punt is de code van een prijsonderdeel en staat erbij.
 */
export function ChangeParts({ mask }: { mask: string | null }) {
  if (mask === null || mask.trim() === '') {
    return <Dash />;
  }
  const parts = mask
    .split(',')
    .map((part) => part.trim())
    .filter((part) => part !== '');
  return (
    <>
      {parts.map((part, index) => {
        const separator = part.indexOf(':');
        const base = separator > 0 ? part.substring(0, separator) : part;
        const component = separator > 0 ? part.substring(separator + 1) : null;
        return (
          <span key={`${part}-${index}`}>
            {index > 0 && ', '}
            <Term domain="changePart" code={base} unknownLabel="Ander onderdeel" />
            {component !== null && ` (prijsonderdeel ${component})`}
          </span>
        );
      })}
    </>
  );
}
