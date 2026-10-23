/**
 * E1 — een toegankelijke zoekende keuzelijst (ARIA 1.2 combobox met listbox, "list autocomplete"): een tekstveld waarvan de
 * lijst een hulp is, geen beperking. Getypte tekst blijft altijd de waarde (vrije tekst is toegestaan); een keuze uit
 * de lijst vervangt ze door de waarde van de gekozen optie.
 *
 * Toetsen: ↓/↑ openen en bewegen (met `aria-activedescendant`, de focus blijft in het tekstveld), Enter kiest de
 * actieve optie, Escape sluit eerst de lijst (en bereikt dan het omliggende paneel niet). Zoeken gebeurt vertraagd:
 * `onSearch` krijgt de getypte tekst pas na `debounceMs` zonder toets. Het component haalt zelf niets op; de aanroeper
 * levert de `options` (bv. van de Prodis-referentie) en kan met `filter` laten filteren op wat er getypt staat.
 */

import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import styles from './Combobox.module.css';

export type ComboboxOption = { value: string; label: string };

export type ComboboxProps = {
  id: string;
  value: string;
  /** De getypte tekst (vrije tekst is de waarde). */
  onChange: (text: string) => void;
  /** Een keuze uit de lijst; de aanroeper zet meestal `option.value` als waarde. */
  onSelect: (option: ComboboxOption) => void;
  options: readonly ComboboxOption[];
  /** Vertraagd na het typen: de aanroeper haalt nieuwe opties op. */
  onSearch?: (text: string) => void;
  debounceMs?: number;
  /** `true` = de lijst wordt hier gefilterd op de getypte tekst (voor een lijst die de aanroeper volledig kent). */
  filter?: boolean;
  loading?: boolean;
  placeholder?: string;
  maxLength?: number;
  emptyText: string;
  loadingText: string;
  disabled?: boolean;
  'aria-describedby'?: string;
};

export function Combobox({
  id,
  value,
  onChange,
  onSelect,
  options,
  onSearch,
  debounceMs = 250,
  filter = false,
  loading = false,
  placeholder,
  maxLength,
  emptyText,
  loadingText,
  disabled = false,
  'aria-describedby': describedBy,
}: ComboboxProps) {
  const listboxId = useId();
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(
    () => () => {
      if (timer.current !== null) {
        clearTimeout(timer.current);
      }
    },
    [],
  );

  const shown = useMemo(() => {
    if (!filter) {
      return options;
    }
    const needle = value.trim().toLowerCase();
    return needle === ''
      ? options
      : options.filter((option) => option.label.toLowerCase().includes(needle) || option.value.toLowerCase().includes(needle));
  }, [filter, options, value]);

  function optionId(index: number): string {
    return `${listboxId}-option-${index}`;
  }

  function handleInput(text: string) {
    onChange(text);
    setOpen(true);
    setActive(-1);
    if (onSearch !== undefined) {
      if (timer.current !== null) {
        clearTimeout(timer.current);
      }
      timer.current = setTimeout(() => onSearch(text), debounceMs);
    }
  }

  function choose(option: ComboboxOption) {
    onSelect(option);
    setOpen(false);
    setActive(-1);
  }

  function handleKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      if (!open) {
        setOpen(true);
        setActive(shown.length > 0 ? 0 : -1);
      } else if (shown.length > 0) {
        setActive((current) => (current + 1) % shown.length);
      }
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      if (!open) {
        setOpen(true);
        setActive(shown.length > 0 ? shown.length - 1 : -1);
      } else if (shown.length > 0) {
        setActive((current) => (current <= 0 ? shown.length - 1 : current - 1));
      }
    } else if (event.key === 'Enter') {
      const option = open && active >= 0 ? shown[active] : undefined;
      if (option !== undefined) {
        event.preventDefault();
        choose(option);
      }
    } else if (event.key === 'Escape' && open) {
      // De lijst sluit eerst; het omliggende paneel mag deze Escape niet meer zien.
      event.preventDefault();
      event.stopPropagation();
      setOpen(false);
      setActive(-1);
    }
  }

  const expanded = open && !disabled;
  return (
    <div className={styles.wrap}>
      <input
        id={id}
        className={styles.input}
        type="text"
        role="combobox"
        aria-expanded={expanded}
        aria-controls={listboxId}
        aria-autocomplete="list"
        aria-activedescendant={expanded && active >= 0 ? optionId(active) : undefined}
        aria-describedby={describedBy}
        autoComplete="off"
        value={value}
        placeholder={placeholder}
        maxLength={maxLength}
        disabled={disabled}
        onChange={(event) => handleInput(event.target.value)}
        onKeyDown={handleKeyDown}
        onClick={() => setOpen(true)}
        onBlur={() => {
          setOpen(false);
          setActive(-1);
        }}
      />
      <ul id={listboxId} role="listbox" className={styles.list} hidden={!expanded}>
        {shown.map((option, index) => (
          <li
            key={option.value}
            id={optionId(index)}
            role="option"
            aria-selected={index === active}
            className={`${styles.option} ${index === active ? styles.active : ''}`}
            // De focus blijft in het tekstveld: een klik mag het veld niet laten blur'en.
            onMouseDown={(event) => event.preventDefault()}
            onClick={() => choose(option)}
          >
            {option.label}
          </li>
        ))}
        {shown.length === 0 && (
          <li role="presentation" className={styles.status}>
            {loading ? loadingText : emptyText}
          </li>
        )}
      </ul>
    </div>
  );
}
