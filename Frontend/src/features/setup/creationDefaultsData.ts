/**
 * CD-F — de Prodis-keuzelijsten (btw-codes en eenheden) voor de aanmaakstandaard van een koppeling. De lijsten worden één
 * keer per scherm geladen (enkel met recht `MANAGE`; zonder dat recht vragen ze niets) en daarna client-side gefilterd.
 * Een fout (409 `PRODIS_DB_NOT_CONFIGURED`, 503 `PRODIS_DB_UNAVAILABLE` of iets anders) is geen schermfout: de lijst
 * ontbreekt dan en de codes blijven als gewone tekst in te vullen, de server controleert ze bij het bewaren opnieuw.
 */
import { useEffect, useState } from 'react';
import { listUnitsOfMeasure, listVatCodes } from '../../api/prodisReference.ts';
import type { ProdisUnitOfMeasure, ProdisVatCode } from '../../api/types.ts';

export type ProdisLists = {
  /** `off` = geen recht (er is niets gevraagd); `unavailable` = de lijst kon niet geladen worden. */
  status: 'off' | 'loading' | 'ready' | 'unavailable';
  vat: ProdisVatCode[];
  units: ProdisUnitOfMeasure[];
};

const OFF: ProdisLists = { status: 'off', vat: [], units: [] };

export function useProdisCreationLists(enabled: boolean): ProdisLists {
  const [lists, setLists] = useState<ProdisLists>(enabled ? { status: 'loading', vat: [], units: [] } : OFF);

  useEffect(() => {
    if (!enabled) {
      setLists(OFF);
      return;
    }
    const controller = new AbortController();
    let current = true;
    setLists({ status: 'loading', vat: [], units: [] });
    Promise.all([listVatCodes(controller.signal), listUnitsOfMeasure(controller.signal)])
      .then(([vat, units]) => {
        if (current) {
          setLists({ status: 'ready', vat, units });
        }
      })
      .catch(() => {
        if (current) {
          setLists({ status: 'unavailable', vat: [], units: [] });
        }
      });
    return () => {
      current = false;
      controller.abort();
    };
  }, [enabled]);

  return lists;
}
