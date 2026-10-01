/**
 * NT-6 — wat het stappenplan per stap bijhoudt. Elk object hieronder bestaat **op de server** zodra het hier
 * staat: een stap schrijft eerst, en pas het antwoord (of een uitdrukkelijk gekozen bestaand object) vult de
 * toestand. Zo is het stappenplan op elk moment te hervatten vanuit wat er al bestaat.
 */
import type { RevisionStatus, SourceOrganisationType } from '../../../api/types.ts';

export type WizardOrganisation = { id: number; code: string; name: string; type: SourceOrganisationType };
export type WizardDefinition = { id: number; code: string; name: string };
export type WizardRevision = { id: number; definitionId: number; revisionNumber: number; status: RevisionStatus };
export type WizardLink = { id: number; code: string; name: string; supplierCode: string; libraryCode: string };
export type WizardTask = { id: number; name: string };

export type WizardData = {
  organisation: WizardOrganisation | null;
  /** Stap 2 schrijft niets; ze onthoudt enkel dat "Zelf beschrijven" gekozen is. */
  startChosen: boolean;
  definition: WizardDefinition | null;
  revision: WizardRevision | null;
  link: WizardLink | null;
  task: WizardTask | null;
};

export const EMPTY_WIZARD: WizardData = {
  organisation: null,
  startChosen: false,
  definition: null,
  revision: null,
  link: null,
  task: null,
};

export type WizardStep = 1 | 2 | 3 | 4 | 5 | 6;

export const STEP_TITLES: Record<WizardStep, string> = {
  1: 'Leverancier',
  2: 'Startpunt',
  3: 'Beschrijving van het bestand',
  4: 'Koppeling',
  5: 'Taak',
  6: 'Klaar',
};

/** De huidige stap volgt uit wat er al bestaat — nooit uit een los bijgehouden teller. */
export function currentStep(data: WizardData): WizardStep {
  if (data.organisation === null) {
    return 1;
  }
  if (data.definition === null && !data.startChosen) {
    return 2;
  }
  if (data.definition === null || data.revision === null) {
    return 3;
  }
  if (data.link === null) {
    return 4;
  }
  if (data.task === null) {
    return 5;
  }
  return 6;
}

/**
 * Het adres van het stappenplan, hervat vanaf wat er al bestaat (NT-6 "Verder inrichten", NT-7 "Taak
 * toevoegen"): met `linkId` (en `definitionId`) opent het stappenplan bij de taakstap.
 */
export function wizardHref(ids: { organisationId: number; definitionId?: number; linkId?: number }): string {
  const params = new URLSearchParams({ organisationId: String(ids.organisationId) });
  if (ids.definitionId !== undefined) {
    params.set('definitionId', String(ids.definitionId));
  }
  if (ids.linkId !== undefined) {
    params.set('linkId', String(ids.linkId));
  }
  return `/setup/new?${params.toString()}`;
}

/** Het adres van het revisiedetail in Inrichting (daar staat "Revisie activeren"). */
export function revisionDetailHref(definitionId: number, revisionId: number): string {
  return `/setup?definitionId=${definitionId}&revisionId=${revisionId}`;
}
