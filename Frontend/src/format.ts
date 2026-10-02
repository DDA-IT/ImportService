/** Eén tijdstip-weergave voor alle schermen: Belgisch-Nederlandse notatie, een streepje bij een ontbrekend tijdstip. */
export function formatDateTime(iso: string | null): string {
  return iso === null ? '—' : new Date(iso).toLocaleString('nl-BE');
}
