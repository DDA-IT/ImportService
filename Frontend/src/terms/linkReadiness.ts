/**
 * F3/F4 — Nederlandse teksten bij de gereedheid en het wachtwoord van een koppeling (leveranciersdetail, "Gebruikt
 * door" en het dashboard). De probleemtekst komt uit het bestaande woord bij de controle-code
 * (`describeReadinessCheck`); een code staat nooit als hoofdtekst.
 */
import type { LinkCredentialState } from '../api/types.ts';

/** Eerste letter klein, voor een zin na een dubbelepunt of komma. */
export function lowerFirst(text: string): string {
  return `${text.charAt(0).toLowerCase()}${text.slice(1)}`;
}

export const LINK_READINESS_TEXT = {
  ready: 'Klaar',
  /** `problem` is het (Nederlandse) woord van het eerste probleem; de eerste letter wordt klein na de dubbelepunt. */
  notReady: (problem: string) => `Niet klaar: ${lowerFirst(problem)}`,
  notReadyUnknown: 'Niet klaar',
  /** Uitleg bij de badge: de controle komt van de server, dezelfde als op het scherm Controleren. */
  hint: 'Uitkomst van de controle van de koppeling, dezelfde als op het scherm Controleren.',
} as const;

export const CREDENTIAL_STATE_LABEL: Record<LinkCredentialState, string> = {
  STORED: 'Veilig bewaard',
  REVOKED: 'Ingetrokken',
  NONE: 'Geen',
};

export const CREDENTIAL_LABEL = 'Wachtwoord';

/** Eén waarde voor meerdere koppelingen: één bewaard wachtwoord is genoeg, anders een ingetrokken, anders geen. */
export function aggregateCredentialState(states: readonly LinkCredentialState[]): LinkCredentialState {
  if (states.includes('STORED')) {
    return 'STORED';
  }
  return states.includes('REVOKED') ? 'REVOKED' : 'NONE';
}
