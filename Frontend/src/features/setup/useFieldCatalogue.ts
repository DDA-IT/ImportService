/**
 * EF1 — laadt de veldcatalogus (`GET /field-catalog`, EB1) één keer voor de editor van "Bestand en velden". Bij een fout is de
 * lijst het woordenboek (`fallbackCatalogue`, `source = 'fallback'`): de editor blijft bruikbaar en toont een melding.
 * `null` = nog aan het laden.
 */

import * as setupApi from '../../api/setup.ts';
import { useQuery } from '../../hooks/useQuery.ts';
import { catalogueFromResponse, fallbackCatalogue, type FieldCatalogue } from './fieldsGridModel.ts';

export function useFieldCatalogue(): FieldCatalogue | null {
  const query = useQuery('setup-field-catalog', async (signal) => catalogueFromResponse(await setupApi.getFieldCatalog(signal)));
  if (query.data !== null) {
    return query.data;
  }
  return query.error !== null ? fallbackCatalogue() : null;
}
