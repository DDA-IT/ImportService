/**
 * De statusmatrix (§9.1) en de beslisbaarheid per mutatie (§9.2) uit
 * `docs/design/frontend-scherm3-bundel-design.md`. Twee **pure** functies, geen React, geen `api/`.
 *
 * Dit is de belangrijkste businesslogica die in de frontend leeft. **Cruciaal:** deze functies zijn
 * een spiegel van de backend, nooit de bron van waarheid — een 409 van de server wordt altijd getoond,
 * ook wanneer een poort hier "toegestaan" zei (A44).
 */

import type {
  DecisionFilter,
  FreezePreflight,
  MutationActionType,
  MutationStatus,
  PublicationBundleStatus,
} from '../../api/types.ts';
import { isEmptyDecisionFilter } from './groupDecisionFilter.ts';

export type Gate = { allowed: true } | { allowed: false; reason: string };

const ALLOWED: Gate = { allowed: true };

function denied(reason: string): Gate {
  return { allowed: false, reason };
}

const FASE5_STATUSES: readonly PublicationBundleStatus[] = [
  'PUBLISHING',
  'PARTIALLY_PUBLISHED',
  'PUBLISHED',
  'PUBLICATION_FAILED',
];

/** §13/§9.1: de vier Fase 5-statussen kunnen vandaag niet voorkomen, maar de UI moet ze verdragen. */
export function isReadOnlyBundleStatus(status: PublicationBundleStatus): boolean {
  return (FASE5_STATUSES as readonly string[]).includes(status);
}

function readOnlyReason(status: PublicationBundleStatus): string {
  return `Deze bundel staat in een status die deze versie van het scherm niet bedient (${status}).`;
}

/**
 * De zeven acties uit de statusmatrix van §9.1. `READ` staat er expliciet bij zodat "alles lezen"
 * (altijd toegestaan, elke status) ook tabelgedreven getest kan worden (T2).
 */
export const BUNDLE_ACTIONS = [
  'ADD_BATCHES',
  'REMOVE_BATCH',
  'DECIDE_MUTATION',
  'REVISE_DECISION',
  'GROUP_DECISION',
  'FREEZE',
  'CANCEL',
  'READ',
] as const;
export type BundleAction = (typeof BUNDLE_ACTIONS)[number];

/**
 * §9.1 — welke bundelactie mag bij welke bundelstatus. Een verboden actie krijgt altijd een reden
 * (getoond bij een uitgeschakelde knop, nooit door een verdwenen knop verborgen).
 */
export function bundleActionGate(status: PublicationBundleStatus, action: BundleAction): Gate {
  if (action === 'READ') {
    return ALLOWED;
  }

  if (isReadOnlyBundleStatus(status)) {
    return denied(readOnlyReason(status));
  }

  if (action === 'CANCEL') {
    // Annuleren mag vanuit ASSEMBLING én FROZEN (§10.6) — de enige actie die ook buiten ASSEMBLING mag.
    if (status === 'ASSEMBLING' || status === 'FROZEN') {
      return ALLOWED;
    }
    return denied('Kan niet: deze bundel is al geannuleerd (BUNDLE_NOT_CANCELLABLE).');
  }

  // Alle overige acties (batches toevoegen/verwijderen, mutatie (her)beslissen, groepsactie, bevriezen)
  // mogen uitsluitend zolang de bundel ASSEMBLING is.
  if (status === 'ASSEMBLING') {
    return ALLOWED;
  }
  if (status === 'FROZEN') {
    return denied('Kan niet: de bundel is bevroren (BUNDLE_NOT_ASSEMBLING).');
  }
  // CANCELLED
  return denied('Kan niet: de bundel is geannuleerd (BUNDLE_NOT_ASSEMBLING).');
}

/** Reikwijdte van een mutatiebeslissing: een herziening (de mutatie draagt al een beslissing) mag
 * volgens §9.2 alleen individueel, nooit via de groepsactie. */
export type MutationDecisionScope = 'individual' | 'group';

/** §9.2 — statussen die al een beslissing dragen; een nieuwe beslissing daarop is een herziening. */
export function isRevisionStatus(status: MutationStatus): boolean {
  return status === 'READY_FOR_PUBLICATION' || status === 'REJECTED';
}

/**
 * §9.2 — mag een mutatie in `status`/`actionType` goedgekeurd of afgekeurd worden, binnen een bundel
 * met `bundleStatus`? `scope` onderscheidt een individuele beslissing van een groepsactie: een
 * herziening (§10.3) is alleen individueel mogelijk.
 *
 * Geeft ook terug of het, bij toestemming, om een herziening gaat (`isRevision`), omdat een herziening
 * altijd een reden vereist — ook bij goedkeuren (§10.3).
 */
export function mutationDecisionGate(
  bundleStatus: PublicationBundleStatus,
  actionType: MutationActionType,
  status: MutationStatus,
  scope: MutationDecisionScope = 'individual',
): Gate & { isRevision?: boolean } {
  const bundleGate = bundleActionGate(bundleStatus, 'DECIDE_MUTATION');
  if (!bundleGate.allowed) {
    return bundleGate;
  }

  if (status === 'BLOCKED') {
    return denied(
      'Geblokkeerd door een kritiek identiteitsincident; deze fase kent hier geen beslispad ' +
        '(MUTATION_BLOCKED_BY_IDENTITY_INCIDENT).',
    );
  }

  if (actionType === 'IDENTITY_REFERENCE_INCIDENT') {
    return denied(
      'Een identiteitsbeslissing schrijft in de referentiestaat; dat komt in een latere fase ' +
        '(IDENTITY_DECISION_NOT_IN_SCOPE).',
    );
  }

  if (actionType === 'IMPORT_MARKER') {
    return denied('Dit is geen inhoudelijke mutatie; hier is niets te beslissen.');
  }

  // actionType is nu CREATE of UPDATE.
  if (status === 'PLANNED' || status === 'AWAITING_APPROVAL') {
    return { allowed: true, isRevision: false };
  }

  if (isRevisionStatus(status)) {
    if (scope === 'individual') {
      return { allowed: true, isRevision: true };
    }
    return denied('Een herziening van een eerdere beslissing kan alleen individueel, niet via een groepsactie.');
  }

  // Overige statussen: EXPIRED, SKIPPED, RECORDED, IN_PROGRESS, PUBLISHED, TECHNICALLY_FAILED.
  return denied('Deze mutatie staat in een status waarin goedkeuren of afkeuren niet mogelijk is (MUTATION_NOT_DECIDABLE).');
}

/** De twee statussen die een groepsactie raakt — spiegel van `BundleDecisionService.toSelection`. */
const GROUP_DECIDABLE_STATUSES: readonly MutationStatus[] = ['PLANNED', 'AWAITING_APPROVAL'];
/** De twee soorten die een groepsactie raakt — spiegel van `BundleDecisionService.toSelection`. */
const GROUP_DECIDABLE_ACTION_TYPES: readonly MutationActionType[] = ['CREATE', 'UPDATE'];

/**
 * §10.4 — mag de groepsactie aangeboden worden met déze (zichtbare) lijstfilter, binnen een bundel met
 * `bundleStatus`? `listedCount` is het aantal dat de lijst voor precies deze filter toont, of `null`
 * zolang dat niet vaststaat.
 *
 * Spiegel van de backend, nooit de bron van waarheid: een 400/409 van de server wordt altijd getoond.
 * De poort weigert wat de server toch zou weigeren (lege filter: `DECISION_FILTER_REQUIRED`; een
 * `status` buiten `PLANNED`/`AWAITING_APPROVAL` of een `actionType` buiten `CREATE`/`UPDATE`: 400
 * zonder code), zodat de gebruiker de reden vóór de klik ziet. De filter zelf wordt nooit aangepast:
 * wat de lijst toont is wat er verstuurd wordt, of er wordt niets verstuurd.
 */
export function groupDecisionGate(
  bundleStatus: PublicationBundleStatus,
  filter: DecisionFilter,
  listedCount: number | null,
): Gate {
  const bundleGate = bundleActionGate(bundleStatus, 'GROUP_DECISION');
  if (!bundleGate.allowed) {
    return bundleGate;
  }

  if (isEmptyDecisionFilter(filter)) {
    return denied(
      'Zet eerst minstens één filter in de lijst; een groepsactie over de hele bundel bestaat niet ' +
        '(DECISION_FILTER_REQUIRED).',
    );
  }

  if (filter.status !== undefined && !GROUP_DECIDABLE_STATUSES.includes(filter.status)) {
    return denied(
      `De lijst is gefilterd op status ${filter.status}; een groepsactie raakt alleen PLANNED of ` +
        'AWAITING_APPROVAL. Pas de statusfilter aan.',
    );
  }

  if (filter.actionType !== undefined && !GROUP_DECIDABLE_ACTION_TYPES.includes(filter.actionType)) {
    return denied(
      `De lijst is gefilterd op soort ${filter.actionType}; een groepsactie raakt alleen CREATE of ` +
        'UPDATE. Pas de soortfilter aan.',
    );
  }

  if (listedCount === null) {
    return denied('Het aantal mutaties voor deze filter is nog niet gekend (de lijst laadt nog, of het laden mislukte).');
  }

  if (listedCount === 0) {
    return denied('De lijst toont met deze filter geen mutaties; er is niets te beslissen.');
  }

  return ALLOWED;
}

function mutationsCount(count: number): string {
  return `${count} ${count === 1 ? 'mutatie' : 'mutaties'}`;
}

/**
 * §10.5 — de leesbare reden per blokkadecode uit de voorvlucht (`GET /bundles/{id}/freeze-check`,
 * bouwstap C3). Elke reden draagt de code letterlijk, zodat de gebruiker de 409 herkent die de server
 * zou geven. Een onbekende code (latere backenduitbreiding) wordt nooit weggelaten: ze blokkeert en
 * wordt letterlijk getoond.
 */
export function freezeBlockerReason(code: string, preflight: FreezePreflight): string {
  switch (code) {
    case 'BUNDLE_NOT_ASSEMBLING':
      return 'De bundel is niet meer in opbouw; alleen een bundel met status ASSEMBLING kan bevroren worden (BUNDLE_NOT_ASSEMBLING).';
    case 'BUNDLE_EMPTY':
      return 'De bundel heeft geen actieve batch; een lege bundel kan niet bevroren worden (BUNDLE_EMPTY).';
    case 'BUNDLE_HAS_UNDECIDED_MUTATIONS':
      return (
        `Er ${preflight.awaitingApprovalCount === 1 ? 'wacht' : 'wachten'} nog ` +
        `${mutationsCount(preflight.awaitingApprovalCount)} op een expliciete beslissing ` +
        '(AWAITING_APPROVAL). Bevriezen mag die vraag niet stilzwijgend beantwoorden: beoordeel ze eerst ' +
        '(BUNDLE_HAS_UNDECIDED_MUTATIONS).'
      );
    case 'SOURCE_STATE_CHANGED_SINCE_SCREENING':
      return (
        `De bronstaat is verschoven sinds de screening (${mutationsCount(preflight.staleMutationCount)}); ` +
        'screen de levering opnieuw (SOURCE_STATE_CHANGED_SINCE_SCREENING).'
      );
    case 'BUNDLE_OFFER_CONFLICT':
      return (
        'Dezelfde aanbieding staat publiceerbaar in meer dan één batch van deze bundel; keur er één af ' +
        '(BUNDLE_OFFER_CONFLICT).'
      );
    case 'OFFER_ALREADY_IN_ANOTHER_BUNDLE':
      return (
        'Een aanbieding staat ook publiceerbaar in een andere, niet-geannuleerde bundel; keur één kant af, ' +
        'of publiceer/annuleer eerst de andere bundel (OFFER_ALREADY_IN_ANOTHER_BUNDLE).'
      );
    default:
      return `De server meldt een blokkade die dit scherm niet kent (${code}).`;
  }
}

/** Alle blokkades uit de voorvlucht, in de volgorde van de server (R-FRZ), elk met een leesbare reden. */
export function freezeBlockers(preflight: FreezePreflight): string[] {
  const reasons = preflight.blockerCodes.map((code) => freezeBlockerReason(code, preflight));
  if (reasons.length === 0 && !preflight.freezable) {
    // Tegenstrijdig antwoord (niet bevriesbaar, maar zonder code): nooit als "toegestaan" lezen.
    reasons.push('De voorvlucht meldt dat bevriezen niet kan, zonder een reden te geven.');
  }
  return reasons;
}

/**
 * §10.5 — mag de bevriesknop in de dialoog aan? `preflight` is het antwoord van de voorvlucht, of
 * `null` zolang dat niet (succesvol) geladen is.
 *
 * Spiegel van de backend, nooit de bron van waarheid (A44): de voorvlucht is een momentopname zonder
 * slot, `POST /freeze` controleert alles opnieuw en elke 409 wordt getoond. Zonder voorvlucht is
 * bevriezen uit: het aantal `PLANNED` dat op naam van de bevriezer goedgekeurd wordt, is het
 * belangrijkste getal van de dialoog en moet in beeld staan vóór er getekend wordt.
 */
export function freezeGate(bundleStatus: PublicationBundleStatus, preflight: FreezePreflight | null): Gate {
  const bundleGate = bundleActionGate(bundleStatus, 'FREEZE');
  if (!bundleGate.allowed) {
    return bundleGate;
  }
  if (preflight === null) {
    return denied('De voorvlucht is nog niet geladen (of het laden mislukte); zonder voorvlucht kan niet bevroren worden.');
  }
  const blockers = freezeBlockers(preflight);
  if (blockers.length > 0) {
    return denied(`Kan niet bevriezen: ${blockers.join(' ')}`);
  }
  return ALLOWED;
}

/**
 * §10.6 — mag de annuleerknop in de dialoog aan? `expirableCount` is het aantal mutaties dat bij het
 * annuleren vervalt, of `null` zolang dat niet vastgesteld is. Zonder dat getal wordt er niet
 * getekend: de dialoog moet het vóór de bevestiging tonen.
 */
export function cancelGate(bundleStatus: PublicationBundleStatus, expirableCount: number | null): Gate {
  const bundleGate = bundleActionGate(bundleStatus, 'CANCEL');
  if (!bundleGate.allowed) {
    return bundleGate;
  }
  if (expirableCount === null) {
    return denied(
      'Het aantal mutaties dat vervalt is nog niet vastgesteld (het tellen loopt nog, of mislukte); zonder dat ' +
        'getal kan niet geannuleerd worden.',
    );
  }
  return ALLOWED;
}
