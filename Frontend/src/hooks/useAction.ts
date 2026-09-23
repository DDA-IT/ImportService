import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/http.ts';

export type UseActionResult<A extends unknown[], R> = {
  execute: (...args: A) => Promise<R | undefined>;
  pending: boolean;
  error: ApiError | null;
  reset: () => void;
};

/**
 * Schrijfhook: voert `run` uit, houdt bij of het bezig is en welke fout er (eventueel) was. Roept
 * `run` nooit automatisch opnieuw aan — geen retry, zie §3.2/§5 van het ontwerp: elke herhaalde poging
 * op een schrijfactie kan een nieuwe beslissingsregel schrijven.
 *
 * Bewust géén optimistische update: `data`/succes bestaat hier niet, de aanroeper gebruikt het
 * antwoord van `execute()` zelf en/of roept daarna `reload()` van de betrokken `useQuery`-instanties
 * aan (expliciete cache-invalidatie, zie §5).
 */
export function useAction<A extends unknown[], R>(run: (...args: A) => Promise<R>): UseActionResult<A, R> {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const runRef = useRef(run);
  useEffect(() => {
    runRef.current = run;
  }, [run]);

  const execute = useCallback(async (...args: A): Promise<R | undefined> => {
    setPending(true);
    setError(null);
    try {
      const result = await runRef.current(...args);
      setPending(false);
      return result;
    } catch (cause) {
      const apiError = cause instanceof ApiError ? cause : new ApiError(0, null, null, '');
      setError(apiError);
      setPending(false);
      return undefined;
    }
  }, []);

  const reset = useCallback(() => {
    setError(null);
  }, []);

  return { execute, pending, error, reset };
}
