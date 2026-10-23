/**
 * E1/E2 — de schil van het zijpaneel van "Bestand en velden" (mock `docs/mock/setup-redesign-mock.html`), gedeeld door het
 * paneel voor Herkenning en Basis (`FieldPanel`) en dat voor extra velden (`MappingPanel`).
 *
 * Toegankelijkheid: `role="dialog"` met `aria-labelledby`, de focus gaat bij het openen het paneel in en blijft er (Tab loopt
 * rond), Escape sluit (met "Wijzigingen weggooien?" als er iets gewijzigd is) en de focus keert terug naar de knop die het
 * paneel opende. Een open bevestigingsdialoog buiten de schil vangt Escape zelf op (`blocked`).
 */

import { useEffect, useId, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { CloseIcon } from '../../components/icons.tsx';
import { ConfirmDialog } from '../../components/ConfirmDialog.tsx';
import { EDITOR_TEXT as TEXT } from '../../terms/setupEditor.ts';
import styles from './FieldsEditor.module.css';

const FOCUSABLE = 'input:not([disabled]), select:not([disabled]), textarea:not([disabled]), button:not([disabled]), [href]';

export type PanelShellProps = {
  title: string;
  /** De knop die het paneel opende; de focus keert er na het sluiten naartoe terug. */
  opener: HTMLElement | null;
  /** Er zijn wijzigingen die nog niet bewaard zijn: sluiten vraagt dan "Wijzigingen weggooien?". */
  dirty: boolean;
  /** Een opdracht loopt: sluiten is dan niet mogelijk. */
  pending: boolean;
  onClose: () => void;
  /** Een dialoog buiten de schil is open (bv. "Niet meer gebruiken"): Escape is dan niet voor het paneel. */
  blocked?: boolean;
  /** De voet: meldingen en knoppen; `requestClose` is de sluitactie van [Annuleren]. */
  foot: (requestClose: () => void) => ReactNode;
  children: ReactNode;
};

export function PanelShell({ title, opener, dirty, pending, onClose, blocked = false, foot, children }: PanelShellProps) {
  const titleId = useId();
  const rootRef = useRef<HTMLDivElement>(null);
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  const openerTestId = useRef(opener?.getAttribute('data-testid') ?? null);

  // Focus het paneel in bij het openen; bij het sluiten terug naar de opener.
  useEffect(() => {
    const root = rootRef.current;
    const first = root?.querySelector('[data-panel-body]')?.querySelector<HTMLElement>(FOCUSABLE);
    (first ?? root?.querySelector<HTMLElement>('h2'))?.focus();
    return () => {
      const back =
        opener !== null && opener.isConnected
          ? opener
          : openerTestId.current === null
            ? null
            : document.querySelector<HTMLElement>(`[data-testid="${openerTestId.current}"]`);
      back?.focus();
    };
    // Enkel bij het openen en sluiten.
  }, []);

  function requestClose() {
    if (pending) {
      return;
    }
    if (dirty) {
      setConfirmDiscard(true);
    } else {
      onClose();
    }
  }

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (confirmDiscard || blocked) {
      return;
    }
    if (event.key === 'Escape' && !event.defaultPrevented) {
      event.stopPropagation();
      requestClose();
      return;
    }
    if (event.key === 'Tab') {
      const items = Array.from(rootRef.current?.querySelectorAll<HTMLElement>(FOCUSABLE) ?? []).filter(
        (element) => element.getAttribute('aria-hidden') !== 'true' && element.closest('[hidden]') === null,
      );
      const firstItem = items[0];
      const lastItem = items[items.length - 1];
      if (firstItem === undefined || lastItem === undefined) {
        return;
      }
      if (event.shiftKey && (document.activeElement === firstItem || document.activeElement === rootRef.current)) {
        event.preventDefault();
        lastItem.focus();
      } else if (!event.shiftKey && document.activeElement === lastItem) {
        event.preventDefault();
        firstItem.focus();
      }
    }
  }

  return (
    <div
      ref={rootRef}
      className={styles.panel}
      role="dialog"
      aria-labelledby={titleId}
      data-testid="field-panel"
      onKeyDown={handleKeyDown}
    >
      <div className={styles.panelHead}>
        <h2 id={titleId} tabIndex={-1}>
          {title}
        </h2>
        <button type="button" className={styles.iconButton} aria-label={TEXT.panelClose} onClick={requestClose}>
          <CloseIcon size={20} />
        </button>
      </div>
      <div className={styles.panelBody} data-panel-body>
        {children}
      </div>
      <div className={styles.panelFoot}>{foot(requestClose)}</div>
      <ConfirmDialog
        open={confirmDiscard}
        title={TEXT.discardTitle}
        body={<p>{TEXT.discardBody}</p>}
        reasonRequirement="none"
        confirmLabel={TEXT.discardConfirm}
        cancelLabel={TEXT.discardKeep}
        variant="danger"
        onConfirm={() => {
          setConfirmDiscard(false);
          onClose();
        }}
        onCancel={() => setConfirmDiscard(false)}
      />
    </div>
  );
}
