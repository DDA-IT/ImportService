/**
 * NT-6 — het versturen van één stap van het stappenplan: dubbele verzending uitsluiten, een fout bewaren,
 * en onthouden dat een verzoek geen antwoord kreeg.
 *
 * Bewust niet `useAction`: een stap kan twee verzoeken na elkaar doen (beschrijving + versie) en moet na een
 * 409 meteen kunnen nalezen wat er al bestaat, met de fout in de hand. De regels zijn dezelfde: nooit
 * automatisch herhalen, geen optimistische update.
 */
import { useCallback, useRef, useState } from 'react';
import { ApiError } from '../../../api/http.ts';
import { describe } from '../../../errors/codes.ts';

/**
 * De Nederlandse tekst die bij een veld staat: de titel uit `errors/codes.ts` (de enige plek waar een
 * servercode Nederlands wordt). Geen ruwe code; die blijft opvraagbaar onder de technische details.
 */
export function fieldMessage(error: ApiError): string {
  return describe(error).title;
}

/** Een verzoek zonder (bruikbaar) antwoord: netwerkfout, afgebroken, of een 5xx van de server of een proxy. */
export function isNoAnswer(error: ApiError): boolean {
  return error.status === 0 || error.status >= 500;
}

export function toApiError(cause: unknown): ApiError {
  return cause instanceof ApiError ? cause : new ApiError(0, null, null, '');
}

export type StepSubmit = {
  pending: boolean;
  error: ApiError | null;
  /**
   * Het vorige verzoek kreeg geen antwoord. Het kan dus toch aangekomen zijn: de stap moet vóór een nieuwe
   * poging eerst nalezen wat er al bestaat (en nooit stil overnemen wat ze vindt).
   */
  recheckFirst: boolean;
  /** Voert `work` uit; een tweede aanroep terwijl de eerste loopt, doet niets (dubbele klik = één verzoek). */
  run: (work: () => Promise<void>) => Promise<void>;
  clearError: () => void;
};

export function useStepSubmit(): StepSubmit {
  const busy = useRef(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [recheckFirst, setRecheckFirst] = useState(false);

  const run = useCallback(async (work: () => Promise<void>) => {
    if (busy.current) {
      return;
    }
    busy.current = true;
    setPending(true);
    setError(null);
    try {
      await work();
      setRecheckFirst(false);
    } catch (cause) {
      const apiError = toApiError(cause);
      setError(apiError);
      if (isNoAnswer(apiError)) {
        setRecheckFirst(true);
      }
    } finally {
      busy.current = false;
      setPending(false);
    }
  }, []);

  const clearError = useCallback(() => setError(null), []);

  return { pending, error, recheckFirst, run, clearError };
}

const FIELD_SUFFIXES = ['_REQUIRED', '_TOO_LONG', '_INVALID'] as const;

/**
 * Bij welk formulierveld hoort deze serverfout? `prefixes` koppelt het veldvoorvoegsel van een NT-3-code
 * (`DELIMITER` in `DELIMITER_REQUIRED`) aan een veld van déze stap; `exact` koppelt volledige codes
 * (bv. `LINK_CODE_IN_USE`). Alleen 400/409; `null` = geen veld, de fout komt als geheel onder de stap.
 */
export function fieldOfError(
  error: ApiError | null,
  prefixes: Readonly<Record<string, string>>,
  exact: Readonly<Record<string, string>> = {},
): string | null {
  if (error === null || error.code === null || (error.status !== 400 && error.status !== 409)) {
    return null;
  }
  const code = error.code;
  if (Object.prototype.hasOwnProperty.call(exact, code)) {
    return exact[code] ?? null;
  }
  if (error.status !== 400) {
    return null;
  }
  for (const suffix of FIELD_SUFFIXES) {
    if (code.endsWith(suffix)) {
      const prefix = code.slice(0, -suffix.length);
      if (Object.prototype.hasOwnProperty.call(prefixes, prefix)) {
        return prefixes[prefix] ?? null;
      }
    }
  }
  return null;
}

/** Een tekstveld zoals de server het leest (`SetupService.requireText`/`optionalText` trimmen). */
export function trimmed(value: string): string {
  return value.trim();
}

/** Leeg (na trimmen) wordt `null`: "niet ingevuld", precies zoals `SetupService.optionalText`. */
export function optional(value: string): string | null {
  const text = value.trim();
  return text === '' ? null : text;
}
