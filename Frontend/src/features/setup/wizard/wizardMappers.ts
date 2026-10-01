/**
 * NT-6 — omzettingen van serverantwoorden (aanmaak- en leesendpoints) naar de toestand van het stappenplan,
 * plus de kleine tekstvoorstellen. Bewust los van de componenten (Fast Refresh: een componentbestand
 * exporteert enkel componenten).
 */
import type { RevisionStatus, SourceOrganisationType } from '../../../api/types.ts';
import { term } from '../../../terms/index.ts';
import type { WizardLink, WizardOrganisation, WizardRevision } from './wizardTypes.ts';

export function toWizardOrganisation(row: {
  id: number;
  code: string;
  name: string;
  type: SourceOrganisationType;
}): WizardOrganisation {
  return { id: row.id, code: row.code, name: row.name, type: row.type };
}

/** "CODE — Naam (Leverancier)" voor een keuzelijst; de soort in gewoon Nederlands. */
export function organisationLabel(row: { code: string; name: string; type: string }): string {
  return `${row.code} — ${row.name} (${term('organisationType', row.type).label})`;
}

export function toWizardRevision(row: {
  id: number;
  definitionId: number;
  revisionNumber: number;
  status: RevisionStatus;
}): WizardRevision {
  return { id: row.id, definitionId: row.definitionId, revisionNumber: row.revisionNumber, status: row.status };
}

export function toWizardLink(row: {
  id: number;
  code: string;
  name: string;
  supplierCode: string;
  libraryCode: string;
}): WizardLink {
  return { id: row.id, code: row.code, name: row.name, supplierCode: row.supplierCode, libraryCode: row.libraryCode };
}

/** Het voorstel voor de taaknaam; de gebruiker mag het vrij aanpassen. */
export function suggestedTaskName(linkName: string): string {
  return `${linkName} – handmatige levering`;
}
