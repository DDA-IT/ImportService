/**
 * Wie tekent er, zolang er geen authenticatie is (Fase 5). Zie
 * `docs/design/frontend-scherm3-bundel-design.md` §7.
 *
 * - Eén naam voor de hele applicatie, bewaard in `sessionStorage` (bewust niet `localStorage`: een
 *   naam die dagen later nog voorgevuld staat op een gedeelde machine is precies hoe iemand ongemerkt
 *   op naam van een collega tekent).
 * - `validateActorName` spiegelt de servervalidatie (niet leeg, ≤100 tekens, nooit `system`,
 *   hoofdletterongevoelig) maar vervangt ze niet: een 400 van de server wordt altijd afgehandeld,
 *   ongeacht wat de client hier al afvangt.
 */

import { createContext, useCallback, useContext, useState, type ReactNode } from 'react';

const STORAGE_KEY = 'catalogimport.actor';
const MAX_LENGTH = 100;

export type ActorContextValue = {
  /** De huidige actornaam, ongetrimd zoals de gebruiker ze intypte. Leeg = nog niet ingevuld. */
  actor: string;
  /** Vervangt de actornaam voor de rest van de browsersessie. */
  setActor: (name: string) => void;
};

/**
 * Spiegelt de servervalidatie van de actornaam. Geeft `null` terug wanneer de naam geldig is, anders
 * een Nederlandse foutmelding.
 */
export function validateActorName(name: string): string | null {
  const trimmed = name.trim();
  if (trimmed === '') {
    return 'Vul een naam in.';
  }
  if (trimmed.length > MAX_LENGTH) {
    return `Een naam mag niet langer zijn dan ${MAX_LENGTH} tekens.`;
  }
  if (trimmed.toLowerCase() === 'system') {
    return 'De naam "system" is niet toegestaan.';
  }
  return null;
}

function readInitialActor(): string {
  try {
    return sessionStorage.getItem(STORAGE_KEY) ?? '';
  } catch {
    // sessionStorage kan geweigerd zijn (bv. privémodus zonder opslag); de naam blijft dan enkel
    // in-memory geldig voor de rest van deze paginalading.
    return '';
  }
}

const ActorContext = createContext<ActorContextValue | null>(null);

export function ActorProvider({ children }: { children: ReactNode }) {
  const [actor, setActorState] = useState<string>(readInitialActor);

  const setActor = useCallback((name: string) => {
    setActorState(name);
    try {
      sessionStorage.setItem(STORAGE_KEY, name);
    } catch {
      // Zie readInitialActor: opslag is optioneel, niet vereist voor werking binnen deze paginalading.
    }
  }, []);

  return <ActorContext.Provider value={{ actor, setActor }}>{children}</ActorContext.Provider>;
}

export function useActor(): ActorContextValue {
  const value = useContext(ActorContext);
  if (value === null) {
    throw new Error('useActor must be used within an ActorProvider');
  }
  return value;
}
