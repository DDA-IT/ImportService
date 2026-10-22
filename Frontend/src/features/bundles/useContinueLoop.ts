/**
 * De continue-lus van een publicatie naar Prodis (P7, `publicatie-prodis-db-design.md` par. 2): de frontend roept
 * `POST /publication-runs/{id}/continue` herhaaldelijk aan zolang de run niet klaar is (`done`).
 *
 * - Pauzeren/stoppen = gewoon niet verder aanroepen; een lopende aanroep wordt nog afgewacht (de server verwerkt
 *   die brok toch). Hervatten kan altijd later ("Verder publiceren").
 * - `PUBLICATION_RUN_BUSY` (een andere aanroep verwerkt dezelfde run) -> korte wachttijd en opnieuw.
 * - Elke andere fout stopt de lus en wordt getoond; er wordt nooit stil herhaald.
 * - Maximaal één lus tegelijk (een tweede `start` terwijl er een loopt, wordt genegeerd).
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { continueRun } from '../../api/publicationRuns.ts';
import { ApiError } from '../../api/http.ts';
import type { PublicationRunView } from '../../api/types.ts';
import { LOOP_TIMING, shouldContinue } from './prodisPublication.ts';

export type ContinueLoop = {
  /** Het run-id waarvoor de lus loopt of laatst liep. */
  runId: number | null;
  running: boolean;
  /** De gebruiker vroeg te pauzeren; de lopende aanroep wordt nog afgewacht. */
  pausing: boolean;
  /** Er wordt even gewacht omdat de server bezig was met dezelfde run. */
  waitingBusy: boolean;
  /** Het laatste run-view uit een `continue`-antwoord (of de startwaarde). */
  live: PublicationRunView | null;
  error: ApiError | null;
  start: (runId: number, initial?: PublicationRunView) => void;
  pause: () => void;
};

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export function useContinueLoop(onSettled: () => void): ContinueLoop {
  const [runId, setRunId] = useState<number | null>(null);
  const [running, setRunning] = useState(false);
  const [pausing, setPausing] = useState(false);
  const [waitingBusy, setWaitingBusy] = useState(false);
  const [live, setLive] = useState<PublicationRunView | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const runningRef = useRef(false);
  const stopRef = useRef(false);
  const mountedRef = useRef(true);
  const settledRef = useRef(onSettled);

  useEffect(() => {
    settledRef.current = onSettled;
  }, [onSettled]);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      stopRef.current = true;
    };
  }, []);

  const start = useCallback((id: number, initial?: PublicationRunView) => {
    if (runningRef.current) {
      return;
    }
    runningRef.current = true;
    stopRef.current = false;
    setRunId(id);
    setRunning(true);
    setPausing(false);
    setWaitingBusy(false);
    setError(null);
    setLive(initial ?? null);

    void (async () => {
      let current: PublicationRunView | null = initial ?? null;
      try {
        while (!stopRef.current && (current === null || shouldContinue(current))) {
          try {
            current = await continueRun(id);
            if (mountedRef.current) {
              setLive(current);
              setWaitingBusy(false);
            }
          } catch (cause) {
            if (cause instanceof ApiError && cause.code === 'PUBLICATION_RUN_BUSY') {
              if (mountedRef.current) {
                setWaitingBusy(true);
              }
              await sleep(LOOP_TIMING.busyWaitMs);
              continue;
            }
            if (mountedRef.current) {
              setError(
                cause instanceof ApiError ? cause : new ApiError(0, null, null, `/publication-runs/${id}/continue`),
              );
            }
            break;
          }
        }
      } finally {
        runningRef.current = false;
        if (mountedRef.current) {
          setRunning(false);
          setPausing(false);
          setWaitingBusy(false);
          settledRef.current();
        }
      }
    })();
  }, []);

  const pause = useCallback(() => {
    if (runningRef.current) {
      stopRef.current = true;
      setPausing(true);
    }
  }, []);

  return { runId, running, pausing, waitingBusy, live, error, start, pause };
}
