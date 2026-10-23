/**
 * Adressen van het importdefinitiedetail (`/templates/:definitionId`) en zijn tabs (nested routes, patroon
 * `supplierRoutes.ts`). De route blijft `/templates` (beslissingslog: routes, codes en API blijven); enkel de zichtbare
 * naam is Importdefinities. Het detail neemt elk definitie-id aan, eigen en herbruikbaar. Een versie wordt gekozen met
 * `?versie=<id>`; `bewerken=1` opent meteen het bewerkblok.
 */

export type DefinitionTab = 'overview' | 'fields' | 'versions' | 'usedBy';

const TAB_PATH: Record<DefinitionTab, string> = {
  overview: '',
  fields: '/fields',
  versions: '/versions',
  usedBy: '/used-by',
};

export type DefinitionHrefOptions = { revisionId?: number; edit?: boolean };

export function definitionHref(definitionId: number, tab: DefinitionTab = 'overview', options: DefinitionHrefOptions = {}): string {
  const params = new URLSearchParams();
  if (options.revisionId !== undefined) {
    params.set('versie', String(options.revisionId));
  }
  if (options.edit === true) {
    params.set('bewerken', '1');
  }
  const query = params.toString();
  return `/templates/${definitionId}${TAB_PATH[tab]}${query === '' ? '' : `?${query}`}`;
}
