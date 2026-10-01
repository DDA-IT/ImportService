import { useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/http.ts';

export type UseQueryResult<T> = {
  data: T | null;
  error: ApiError | null;
  loading: boolean;
  reload: () => void;
};

/**
 * Leeshook: laadt bij binnenkomst en bij het wisselen van `key`, en opnieuw wanneer `reload()`
 * aangeroepen wordt. Zie `docs/design/frontend-scherm3-bundel-design.md` §5 voor de motivering om
 * hier geen server-state-bibliotheek voor te gebruiken.
 *
 * Breekt het lopende verzoek af (`AbortController`) bij het wisselen van `key` of bij unmount, en
 * negeert het antwoord van een verouderd verzoek — anders ziet een snel klikkende gebruiker de
 * gegevens van een vorige bundel.
 *
 * Bij een sleutelwissel is `data` meteen `null` (ook in de render vóór het effect draait) tot het
 * antwoord voor de nieuwe sleutel binnen is: een consument mag nooit op de vorige entiteit handelen.
 * Een `reload()` met dezelfde sleutel behoudt `data` tijdens het herladen, zodat bv. een succesmelding
 * niet verdwijnt.
 */
export function useQuery<T>(key: string, load: (signal: AbortSignal) => Promise<T>): UseQueryResult<T> {
  // Het resultaat draagt de sleutel waarvoor het geladen werd; `data` wordt daaruit afgeleid.
  const [result, setResult] = useState<{ key: string; value: T } | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [loading, setLoading] = useState(true);
  // Telt op bij elke (her)laadpoging, ook bij een reload() met dezelfde key, zodat een antwoord dat
  // niet meer bij de laatste poging hoort altijd genegeerd wordt.
  const [attempt, setAttempt] = useState(0);
  const loadRef = useRef(load);
  useEffect(() => {
    loadRef.current = load;
  }, [load]);

  useEffect(() => {
    const controller = new AbortController();
    let current = true;
    setLoading(true);
    setError(null);
    // Een resultaat van een andere sleutel is verouderd (ook bij A -> B -> A): weggooien.
    setResult((previous) => (previous !== null && previous.key === key ? previous : null));

    loadRef
      .current(controller.signal)
      .then((value) => {
        if (!current) {
          return;
        }
        setResult({ key, value });
        setLoading(false);
      })
      .catch((cause: unknown) => {
        if (!current) {
          return;
        }
        const apiError = cause instanceof ApiError ? cause : new ApiError(0, null, null, key);
        setError(apiError);
        setLoading(false);
      });

    return () => {
      current = false;
      controller.abort();
    };
    // `load` is intentionally not a dependency: it is read via `loadRef` so that callers can pass an
    // inline closure without retriggering the effect on every render; only `key`/`reload()` should.
  }, [key, attempt]);

  const data = result !== null && result.key === key ? result.value : null;

  return {
    data,
    error,
    loading,
    reload: () => setAttempt((n) => n + 1),
  };
}
