/**
 * De bescherming tegen een onomkeerbare klik (§14.1 T4). Verzamelt de reden (verplicht of optioneel,
 * afhankelijk van de actie), toont de geverifieerde naam alleen-lezen ("U tekent als …", 5-AUTH), en
 * ondersteunt de typ-bevestiging waarbij de gebruiker een gegeven tekst exact moet overtypen (§10.5,
 * §10.6) — die wrijving bestaat alleen voor de acties die niet meer ongedaan te maken zijn.
 *
 * Bevestigen is geblokkeerd (de knop is uit) zolang: een verplichte reden
 * ontbreekt, de typ-bevestiging niet exact overeenkomt, of de aanroeper een externe blokkade meegeeft
 * (`confirmBlockedReason`, bv. een voorvlucht die nog laadt of een blokkade meldt). In dat laatste
 * geval staat de reden als tekst bij de knop — nooit een uitgeschakelde knop zonder uitleg (§9.1).
 */

import { useEffect, useId, useRef, useState, type FormEvent, type ReactNode, type SyntheticEvent } from 'react';
import { formatActor, useActor } from '../actor/ActorContext';
import { Field } from './Field';
import button from './Button.module.css';
import styles from './ConfirmDialog.module.css';

export type ConfirmDialogReasonRequirement = 'required' | 'optional' | 'none';

export type ConfirmDialogProps = {
  open: boolean;
  title: string;
  body?: ReactNode;
  reasonRequirement: ConfirmDialogReasonRequirement;
  /**
   * Zet de dialoog in typ-bevestigingsmodus: de gebruiker moet exact deze tekst overtypen (bv. de
   * `bundleReference`) voordat bevestigen mogelijk is. Weggelaten = geen typ-bevestiging.
   */
  typedConfirmationText?: string;
  confirmLabel?: string;
  cancelLabel?: string;
  variant?: 'primary' | 'danger';
  pending?: boolean;
  /** Een eerdere serverfout op deze actie (bv. een `ErrorBanner`), getoond binnen de dialoog. */
  error?: ReactNode;
  /**
   * Een blokkade van buiten de dialoog (bv. de voorvlucht van het bevriezen). Niet-`null` = bevestigen
   * is onmogelijk, met deze reden zichtbaar bij de knop. Weggelaten of `null` = geen externe blokkade.
   */
  confirmBlockedReason?: string | null;
  onConfirm: (input: { actor: string; reason: string | null }) => void;
  onCancel: () => void;
};

export function ConfirmDialog({
  open,
  title,
  body,
  reasonRequirement,
  typedConfirmationText,
  confirmLabel = 'Bevestigen',
  cancelLabel = 'Annuleren',
  variant = 'primary',
  pending = false,
  error,
  confirmBlockedReason = null,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  const actorContext = useActor();
  const { actor } = actorContext;
  const [reason, setReason] = useState('');
  const [typedValue, setTypedValue] = useState('');
  const titleId = useId();
  const reasonId = useId();
  const typedId = useId();
  const dialogRef = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    if (open) {
      setReason('');
      setTypedValue('');
    }
  }, [open]);

  // Native modale dialoog: showModal() geeft de focus trap, de top-layer en Escape. Bij sluiten gaat de
  // focus terug naar het element dat focus had op het moment van openen.
  useEffect(() => {
    const dialog = dialogRef.current;
    if (!open || dialog === null) {
      return;
    }
    const previouslyFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    if (!dialog.open) {
      dialog.showModal();
    }
    return () => {
      if (dialog.open) {
        dialog.close();
      }
      if (previouslyFocused !== null && previouslyFocused.isConnected) {
        previouslyFocused.focus();
      }
    };
  }, [open]);

  // Escape = annuleren, tenzij er een actie loopt. De browser sluit de dialoog zelf nooit: de aanroeper
  // beslist via `open`.
  function handleCancelEvent(event: SyntheticEvent<HTMLDialogElement>) {
    event.preventDefault();
    if (!pending) {
      onCancel();
    }
  }

  if (!open) {
    return null;
  }

  const reasonError = reasonRequirement === 'required' && reason.trim() === '' ? 'Vul een reden in.' : null;
  const typedConfirmationError =
    typedConfirmationText !== undefined && typedValue !== typedConfirmationText
      ? `Typ exact "${typedConfirmationText}" om te bevestigen.`
      : null;

  const canConfirm =
    reasonError === null && typedConfirmationError === null && confirmBlockedReason === null;

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canConfirm || pending) {
      return;
    }
    onConfirm({ actor, reason: reason.trim() === '' ? null : reason.trim() });
  }

  return (
    <dialog ref={dialogRef} className={styles.dialog} aria-labelledby={titleId} onCancel={handleCancelEvent}>
      <form className={styles.form} onSubmit={handleSubmit}>
        <div className={styles.scroll}>
        <h2 id={titleId} className={styles.title}>
          {title}
        </h2>
        {body !== undefined && <div className={styles.body}>{body}</div>}

        <p data-testid="confirm-dialog-actor">
          U tekent als <strong>{formatActor(actorContext)}</strong>
        </p>

        {reasonRequirement !== 'none' && (
          <Field
            label={reasonRequirement === 'required' ? 'Reden' : 'Reden (optioneel)'}
            htmlFor={reasonId}
            required={reasonRequirement === 'required'}
            error={reasonError}
          >
            <textarea
              id={reasonId}
              className={styles.textarea}
              value={reason}
              onChange={(event) => setReason(event.target.value)}
            />
          </Field>
        )}

        {typedConfirmationText !== undefined && (
          <Field
            label={`Typ "${typedConfirmationText}" om te bevestigen`}
            htmlFor={typedId}
            required
            error={typedConfirmationError}
          >
            <input
              id={typedId}
              className={styles.input}
              type="text"
              value={typedValue}
              onChange={(event) => setTypedValue(event.target.value)}
              autoComplete="off"
            />
          </Field>
        )}

        {error !== undefined && <div className={styles.error}>{error}</div>}

        {confirmBlockedReason !== null && (
          <p className={styles.blockedReason} data-testid="confirm-blocked-reason">
            {confirmBlockedReason}
          </p>
        )}
        </div>

        <div className={styles.actions}>
          <button type="button" className={button.secondary} onClick={onCancel} disabled={pending}>
            {cancelLabel}
          </button>
          <button
            type="submit"
            className={variant === 'danger' ? button.danger : button.primary}
            disabled={pending || !canConfirm}
          >
            {pending ? 'Bezig…' : confirmLabel}
          </button>
        </div>
      </form>
    </dialog>
  );
}
