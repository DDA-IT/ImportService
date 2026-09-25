/** Kleine weergavehulpjes voor het batchdetailscherm. */

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

export function formatDateTime(iso: string | null): string {
  return iso === null ? '—' : new Date(iso).toLocaleString('nl-BE');
}
