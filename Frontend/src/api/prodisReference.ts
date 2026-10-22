/**
 * G2c-2 — de keuzelijsten van de Prodis-referentie (`CatalogImportProdisReferenceController`, recht `MANAGE`,
 * niet achter de setup-vlag). Enkel lezen; de server valideert een gekozen of getypte waarde bij het bewaren
 * opnieuw (`SUPPLIER_FIXED_VALUE_*`, `SUPPLIER_GROUP_FIXED_VALUE_*`).
 *
 * Twee bekende "niet beschikbaar"-antwoorden: 409 `PRODIS_DB_NOT_CONFIGURED` (functie uit) en 503
 * `PRODIS_DB_UNAVAILABLE` (database onbereikbaar). De aanroeper valt dan terug op vrije tekst
 * ({@link isProdisUnavailable}).
 */
import { request, toQueryString, type ApiError } from './http.ts';

/** be.dda.catalogimport.dao.ProdisReferenceDao.SupplierRow. */
export type ProdisSupplier = { number: string; name: string | null };

/** be.dda.catalogimport.dao.ProdisReferenceDao.SupplierGroupRow; de vaste groep is de `groupName`. */
export type ProdisSupplierGroup = { legacyCode: string; groupName: string; discountCode: string | null };

/** GET /prodis-reference/suppliers?q= — hoogstens een beperkt aantal leveranciers. */
export function searchSuppliers(q: string, signal?: AbortSignal): Promise<ProdisSupplier[]> {
  return request<ProdisSupplier[]>(`/prodis-reference/suppliers${toQueryString({ q: q.trim() })}`, { signal });
}

/** GET /prodis-reference/suppliers/{number}/groups — 404 `PRODIS_SUPPLIER_NOT_FOUND` voor een onbekend nummer. */
export function supplierGroups(supplierNumber: string, signal?: AbortSignal): Promise<ProdisSupplierGroup[]> {
  return request<ProdisSupplierGroup[]>(
    `/prodis-reference/suppliers/${encodeURIComponent(supplierNumber)}/groups`,
    { signal },
  );
}

/** Prodis is niet ingesteld (409) of niet bereikbaar (503): de keuzelijst ontbreekt, vrije tekst blijft kunnen. */
export function isProdisUnavailable(error: ApiError | null): boolean {
  return error !== null && (error.code === 'PRODIS_DB_NOT_CONFIGURED' || error.code === 'PRODIS_DB_UNAVAILABLE');
}
