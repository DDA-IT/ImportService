/**
 * Adressen van het leveranciersdetail (`/setup/suppliers/:organisationId`) en zijn tabs (nested routes, patroon
 * `BundleDetailPage`). Een versie wordt gekozen met `?definitie=<id>&versie=<id>`; `bewerken=1` opent meteen het
 * bewerkblok (de bestaande revisiedetailsectie met bewerken, activeren en opvolger).
 */

export type SupplierTab = 'overview' | 'fields' | 'versions' | 'links';

const TAB_PATH: Record<SupplierTab, string> = {
  overview: '',
  fields: '/fields',
  versions: '/versions',
  links: '/links',
};

export type SupplierHrefOptions = { definitionId?: number; revisionId?: number; edit?: boolean };

export function supplierHref(organisationId: number, tab: SupplierTab = 'overview', options: SupplierHrefOptions = {}): string {
  const params = new URLSearchParams();
  if (options.definitionId !== undefined) {
    params.set('definitie', String(options.definitionId));
  }
  if (options.revisionId !== undefined) {
    params.set('versie', String(options.revisionId));
  }
  if (options.edit === true) {
    params.set('bewerken', '1');
  }
  const query = params.toString();
  return `/setup/suppliers/${organisationId}${TAB_PATH[tab]}${query === '' ? '' : `?${query}`}`;
}

/** Een id uit de adresbalk: enkel een positief geheel getal, anders `null`. */
export function parseId(value: string | null): number | null {
  if (value === null || !/^\d+$/.test(value)) {
    return null;
  }
  const id = Number(value);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
}
