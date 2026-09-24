/**
 * De bescherming tegen een onomkeerbare klik (§14.1 T4). Verzamelt de reden (verplicht of optioneel,
 * afhankelijk van de actie), toont de actornaam opnieuw en laat die ter plekke wijzigen (§7), en
 * ondersteunt de typ-bevestiging waarbij de gebruiker een gegeven tekst exact moet overtypen (§10.5,
 * §10.6) — die wrijving bestaat alleen voor de acties die niet meer ongedaan te maken zijn.
 *
 * Bevestigen is geblokkeerd (de knop is uit) zolang: de actornaam ongeldig is, een verplichte reden
 * ontbreekt, de typ-bevestiging niet exact overeenkomt, of de aanroeper een externe blokkade meegeeft
 * (`confirmBlockedReason`, bv. een voorvlucht die nog laadt of een blokkade meldt). In dat laatste
 * geval staat de reden als tekst bij de knop — nooit een uitgeschakelde knop zonder uitleg (§9.1).
 */

import { useEffect, useId, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { useActor, validateActorName } from '../actor/ActorContext';
import { Field } from './Field';
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
  const { actor, setActor } = useActor();
  const [reason, setReason] = useState('');
  const [typedValue, setTypedValue] = useState('');
  const titleId = useId();
  const actorInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (open) {
      setReason('');
      setTypedValue('');
      actorInputRef.current?.focus();
    }
  }, [open]);

  if (!open) {
    return null;
  }

  const actorError = validateActorName(actor);
  const reasonError = reasonRequirement === 'required' && reason.trim() === '' ? 'Vul een reden in.' : null;
  const typedConfirmationError =
    typedConfirmationText !== undefined && typedValue !== typedConfirmationText
      ? `Typ exact "${typedConfirmationText}" om te bevestigen.`
      : null;

  const canConfirm =
    actorError === null && reasonError === null && typedConfirmationError === null && confirmBlockedReason === null;

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canConfirm || pending) {
      return;
    }
    onConfirm({ actor, reason: reason.trim() === '' ? null : reason.trim() });
  }

  return (
    <div className={styles.overlay} role="presentation">
      <form
        className={styles.dialog}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        onSubmit={handleSubmit}
      >
        <h2 id={titleId} className={styles.title}>
          {title}
        </h2>
        {body !== undefined && <div className={styles.body}>{body}</div>}

        <Field label="Naam" htmlFor="confirm-dialog-actor" required error={actorError}>
          <input
            id="confirm-dialog-actor"
            ref={actorInputRef}
            className={styles.input}
            type="text"
            value={actor}
            onChange={(event) => setActor(event.target.value)}
            maxLength={100}
          />
        </Field>

        {reasonRequirement !== 'none' && (
          <Field
            label={reasonRequirement === 'required' ? 'Reden' : 'Reden (optioneel)'}
            htmlFor="confirm-dialog-reason"
            required={reasonRequirement === 'required'}
            error={reasonError}
          >
            <textarea
              id="confirm-dialog-reason"
              className={styles.textarea}
              value={reason}
              onChange={(event) => setReason(event.target.value)}
            />
          </Field>
        )}

        {typedConfirmationText !== undefined && (
          <Field
            label={`Typ "${typedConfirmationText}" om te bevestigen`}
            htmlFor="confirm-dialog-typed"
            required
            error={typedConfirmationError}
          >
            <input
              id="confirm-dialog-typed"
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

        <div className={styles.actions}>
          <button type="button" className={styles.cancelButton} onClick={onCancel} disabled={pending}>
            {cancelLabel}
          </button>
          <button
            type="submit"
            className={variant === 'danger' ? styles.dangerButton : styles.primaryButton}
            disabled={pending || !canConfirm}
          >
            {pending ? 'Bezig…' : confirmLabel}
          </button>
        </div>
      </form>
    </div>
  );
}
