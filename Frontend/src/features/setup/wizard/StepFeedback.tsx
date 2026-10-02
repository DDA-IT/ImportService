/**
 * NT-6 — hoe een stap van het stappenplan een fout of een bestaand object toont.
 *
 * - Een fout die bij één veld hoort, staat **bij dat veld** in het Nederlands (de titel uit `codes.ts`);
 *   onder de stap blijft enkel de technische regel, inklapbaar voor support (V7).
 * - Elke andere fout komt als geheel onder de stap (`ErrorBanner`).
 * - Een verzoek zonder antwoord krijgt een extra uitleg: bij de volgende poging wordt eerst nagelezen wat al
 *   bestaat, want het vorige verzoek kan toch aangekomen zijn.
 * - Een bestaand object wordt **nooit** stil overgenomen: de gebruiker kiest uitdrukkelijk "Doorgaan met de
 *   bestaande".
 */
import type { ReactNode } from 'react';
import type { ApiError } from '../../../api/http.ts';
import { describe } from '../../../errors/codes.ts';
import { ErrorBanner } from '../../../errors/ErrorBanner.tsx';
import { TechnicalDetails } from '../../../terms/TechnicalDetails.tsx';
import { isNoAnswer } from './stepSubmit.ts';
import button from '../../../components/Button.module.css';
import styles from './Wizard.module.css';

export function StepError({ error, fieldName }: { error: ApiError | null; fieldName: string | null }) {
  if (error === null) {
    return null;
  }
  return (
    <div className={styles.stepError} data-testid="wizard-step-error">
      {fieldName === null ? (
        <ErrorBanner error={error} />
      ) : (
        <>
          <p className={styles.fieldNote} role="note">
            Niet opgeslagen: controleer het gemarkeerde veld.
          </p>
          <TechnicalDetails items={[{ name: 'Melding', value: describe(error).technical }]} />
        </>
      )}
      {isNoAnswer(error) && (
        <p className={styles.note} role="status" data-testid="wizard-no-answer">
          Er kwam geen bruikbaar antwoord van de server; uw gegevens kunnen dus toch bewaard zijn. Bij een
          nieuwe poging kijken we eerst na wat er al bestaat, zodat er niets dubbel aangemaakt wordt.
        </p>
      )}
    </div>
  );
}

export function ExistingChoice({
  children,
  onContinue,
  disabled,
}: {
  children: ReactNode;
  onContinue: () => void;
  disabled?: boolean;
}) {
  return (
    <div className={styles.existing} role="status" data-testid="wizard-existing">
      <div>{children}</div>
      <button type="button" className={button.secondary} disabled={disabled} onClick={onContinue}>
        Doorgaan met de bestaande
      </button>
    </div>
  );
}
