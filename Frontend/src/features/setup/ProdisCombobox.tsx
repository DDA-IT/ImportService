/**
 * E1 — de vaste leverancier of vaste groep als zoekende keuzelijst (`Combobox`) op de Prodis-referentie
 * (`/prodis-reference/suppliers?q=` en `/suppliers/{n}/groups`), beslissingslog 2026-10-23 "Bewerkervaring: mock goedgekeurd".
 * Gedrag en meldingen sinds G2c-2 (E6: de oude `ProdisFixedValueField` is verwijderd): de lijst is een hulp, geen beperking. Vrije tekst blijft
 * mogelijk ("Niet gevonden in Prodis — wordt bij het bewaren gecontroleerd"); is Prodis niet ingesteld (409
 * `PRODIS_DB_NOT_CONFIGURED`) of onbereikbaar (503 `PRODIS_DB_UNAVAILABLE`), dan blijft er een gewoon tekstveld met de
 * bestaande melding. De server valideert de waarde bij het bewaren opnieuw.
 */

import { useEffect, useId, useState } from 'react';
import { ApiError } from '../../api/http.ts';
import {
  isProdisUnavailable,
  searchSuppliers,
  supplierGroups,
  type ProdisSupplier,
  type ProdisSupplierGroup,
} from '../../api/prodisReference.ts';
import { Combobox, type ComboboxOption } from '../../components/Combobox.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { COMBOBOX_TEXT as TEXT } from '../../terms/setupEditor.ts';
import styles from './ProdisCombobox.module.css';

export type ProdisComboboxProps = {
  kind: 'supplier' | 'group';
  id: string;
  value: string;
  onChange: (value: string) => void;
  /** Alleen voor `group`: de leverancier waarvan de groepen getoond worden; leeg/afwezig = geen lijst. */
  supplierNumber?: string;
};

export function ProdisCombobox({ kind, id, value, onChange, supplierNumber }: ProdisComboboxProps) {
  const noteId = useId();
  const [options, setOptions] = useState<ComboboxOption[]>([]);
  const [loading, setLoading] = useState(false);
  // De opzoeking waarvoor `options` geldt; zolang ze niet gelijk is aan de huidige, weten we nog niet of een waarde bestaat.
  const [settledFor, setSettledFor] = useState<string | null>(null);
  const [unavailable, setUnavailable] = useState(false);
  const [supplierUnknown, setSupplierUnknown] = useState(false);
  const [otherError, setOtherError] = useState<ApiError | null>(null);

  const groupSupplier = (supplierNumber ?? '').trim();
  const [searchText, setSearchText] = useState(value.trim());
  const lookupKey = kind === 'supplier' ? searchText : groupSupplier;

  useEffect(() => {
    if (kind === 'group' && lookupKey === '') {
      setOptions([]);
      setSettledFor(null);
      setSupplierUnknown(false);
      setOtherError(null);
      return;
    }
    const controller = new AbortController();
    let current = true;
    setLoading(true);
    const load: Promise<ComboboxOption[]> =
      kind === 'supplier'
        ? searchSuppliers(lookupKey, controller.signal).then((rows: ProdisSupplier[]) =>
            rows.map((row) => ({ value: row.number, label: TEXT.optionLabel(row.number, row.name) })),
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
        setSettledFor(lookupKey);
        setUnavailable(false);
        setSupplierUnknown(false);
        setOtherError(null);
        setLoading(false);
      })
      .catch((cause: unknown) => {
        if (!current || (cause instanceof DOMException && cause.name === 'AbortError')) {
          return;
        }
        const error = cause instanceof ApiError ? cause : new ApiError(0, null, null, lookupKey);
        const down = isProdisUnavailable(error);
        const unknown = error.code === 'PRODIS_SUPPLIER_NOT_FOUND';
        setOptions([]);
        setSettledFor(null);
        setUnavailable(down);
        setSupplierUnknown(unknown);
        setOtherError(down || unknown ? null : error);
        setLoading(false);
      });
    return () => {
      current = false;
      controller.abort();
    };
  }, [kind, lookupKey]);

  const trimmed = value.trim();
  const chosen = options.find((option) => option.value === trimmed);
  const settled = kind === 'supplier' ? settledFor === trimmed : settledFor === groupSupplier && groupSupplier !== '';
  const showNotFound = !unavailable && trimmed !== '' && settled && !loading && chosen === undefined && !supplierUnknown;
  const noSupplierForGroup = kind === 'group' && groupSupplier === '';

  return (
    <>
      {unavailable ? (
        <input
          id={id}
          className={styles.input}
          type="text"
          value={value}
          maxLength={50}
          autoComplete="off"
          onChange={(event) => onChange(event.target.value)}
        />
      ) : (
        <Combobox
          id={id}
          value={value}
          onChange={onChange}
          onSelect={(option) => onChange(option.value)}
          options={options}
          onSearch={kind === 'supplier' ? (text) => setSearchText(text.trim()) : undefined}
          filter={kind === 'group'}
          loading={loading}
          maxLength={50}
          placeholder={kind === 'supplier' ? TEXT.supplierPlaceholder : TEXT.groupPlaceholder}
          emptyText={TEXT.noResults}
          loadingText={TEXT.loading}
          aria-describedby={noteId}
        />
      )}
      <div id={noteId}>
        {chosen !== undefined && chosen.label !== chosen.value && (
          <p className={styles.identityNote} data-testid={`prodis-chosen-${kind}`}>
            {TEXT.chosen(chosen.label)}
          </p>
        )}
        {showNotFound && (
          <p className={styles.identityNote} role="note" data-testid={`prodis-notfound-value-${kind}`}>
            {TEXT.notFound}
          </p>
        )}
        {unavailable && (
          <p className={styles.identityNote} role="note" data-testid={`prodis-unavailable-${kind}`}>
            {TEXT.unavailable}
          </p>
        )}
        {supplierUnknown && (
          <p className={styles.identityNote} role="note" data-testid={`prodis-notfound-${kind}`}>
            {TEXT.supplierUnknownForGroups}
          </p>
        )}
        {noSupplierForGroup && (
          <p className={styles.identityNote} role="note" data-testid="prodis-group-no-supplier">
            {TEXT.noSupplierForGroup}
          </p>
        )}
      </div>
      {otherError !== null && <ErrorBanner error={otherError} />}
    </>
  );
}
