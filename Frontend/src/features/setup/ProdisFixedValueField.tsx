/**
 * G2c-2 — het invoerveld voor een vaste leverancier of vaste groep, met een keuzelijst uit de Prodis-referentie.
 *
 * Het veld is altijd gewone tekst (`<input list>` + `<datalist>`, zoals de tekensetkeuze in `PreviewChoices`): de
 * lijst is een hulp, geen beperking. Valt de lijst weg — Prodis niet ingesteld (409 `PRODIS_DB_NOT_CONFIGURED`),
 * niet bereikbaar (503 `PRODIS_DB_UNAVAILABLE`) of de leverancier onbekend (404) — dan blijft het veld vrije tekst
 * met een duidelijke melding. De server valideert de waarde bij het bewaren opnieuw (`SUPPLIER_FIXED_VALUE_*`,
 * `SUPPLIER_GROUP_FIXED_VALUE_*`) en geeft zonder Prodis-configuratie een waarschuwing in plaats van een fout.
 *
 * Leveranciers: gezocht op wat er getypt staat (vertraagd). Groepen: de groepen van `supplierNumber`.
 */

import { useEffect, useId, useState } from 'react';
import {
  isProdisUnavailable,
  searchSuppliers,
  supplierGroups,
  type ProdisSupplier,
  type ProdisSupplierGroup,
} from '../../api/prodisReference.ts';
import { ApiError } from '../../api/http.ts';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import styles from './RevisionEditForm.module.css';

const SEARCH_DELAY_MS = 250;

type Options = { value: string; label: string }[];

export type ProdisFixedValueFieldProps = {
  kind: 'supplier' | 'group';
  id: string;
  value: string;
  onChange: (value: string) => void;
  /** Alleen voor `group`: de leverancier waarvan de groepen getoond worden; leeg/afwezig = geen keuzelijst. */
  supplierNumber?: string;
};

export function ProdisFixedValueField({ kind, id, value, onChange, supplierNumber }: ProdisFixedValueFieldProps) {
  const listId = useId();
  const [options, setOptions] = useState<Options>([]);
  const [unavailable, setUnavailable] = useState(false);
  const [notFound, setNotFound] = useState(false);
  const [otherError, setOtherError] = useState<ApiError | null>(null);

  // Wat er opgezocht wordt: voor een leverancier de (vertraagde) tekst, voor een groep het leveranciersnummer.
  const trimmedValue = value.trim();
  const groupSupplier = (supplierNumber ?? '').trim();
  const [searchText, setSearchText] = useState(trimmedValue);
  useEffect(() => {
    if (kind !== 'supplier') {
      return;
    }
    const timer = setTimeout(() => setSearchText(trimmedValue), SEARCH_DELAY_MS);
    return () => clearTimeout(timer);
  }, [kind, trimmedValue]);

  const lookupKey = kind === 'supplier' ? searchText : groupSupplier;
  useEffect(() => {
    if (kind === 'group' && lookupKey === '') {
      setOptions([]);
      setNotFound(false);
      setOtherError(null);
      return;
    }
    const controller = new AbortController();
    let current = true;
    const load: Promise<Options> =
      kind === 'supplier'
        ? searchSuppliers(lookupKey, controller.signal).then((rows: ProdisSupplier[]) =>
            rows.map((row) => ({
              value: row.number,
              label: row.name ? `${row.number} — ${row.name}` : row.number,
            })),
          )
        : supplierGroups(lookupKey, controller.signal).then((rows: ProdisSupplierGroup[]) =>
            rows.map((row) => ({ value: row.groupName, label: row.groupName })),
          );
    load
      .then((rows) => {
        if (!current) {
          return;
        }
        setOptions(rows);
        setUnavailable(false);
        setNotFound(false);
        setOtherError(null);
      })
      .catch((cause: unknown) => {
        if (!current || (cause instanceof DOMException && cause.name === 'AbortError')) {
          return;
        }
        const error = cause instanceof ApiError ? cause : new ApiError(0, null, null, lookupKey);
        setOptions([]);
        setUnavailable(isProdisUnavailable(error));
        setNotFound(error.code === 'PRODIS_SUPPLIER_NOT_FOUND');
        setOtherError(isProdisUnavailable(error) || error.code === 'PRODIS_SUPPLIER_NOT_FOUND' ? null : error);
      });
    return () => {
      current = false;
      controller.abort();
    };
  }, [kind, lookupKey]);

  const noSupplierForGroup = kind === 'group' && groupSupplier === '';
  return (
    <>
      <input
        id={id}
        className={styles.input}
        type="text"
        list={unavailable ? undefined : listId}
        value={value}
        maxLength={50}
        autoComplete="off"
        onChange={(event) => onChange(event.target.value)}
      />
      {!unavailable && (
        <datalist id={listId} data-testid={`prodis-options-${kind}`}>
          {options.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </datalist>
      )}
      {unavailable && (
        <p className={styles.identityNote} role="note" data-testid={`prodis-unavailable-${kind}`}>
          De lijst van Prodis is nu niet beschikbaar (Prodis is niet ingesteld of niet bereikbaar). Typ de waarde
          zelf; bij het bewaren wordt ze gecontroleerd of er komt een waarschuwing dat ze niet gecontroleerd is.
        </p>
      )}
      {notFound && (
        <p className={styles.identityNote} role="note" data-testid={`prodis-notfound-${kind}`}>
          Deze leverancier bestaat niet in Prodis, dus er is geen lijst met groepen. Typ de groep zelf.
        </p>
      )}
      {noSupplierForGroup && (
        <p className={styles.identityNote} role="note" data-testid="prodis-group-no-supplier">
          Er is geen leverancier bekend om groepen van op te zoeken. Typ de groep zelf.
        </p>
      )}
      {otherError !== null && <ErrorBanner error={otherError} />}
    </>
  );
}
