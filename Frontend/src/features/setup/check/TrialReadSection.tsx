/**
 * NT-10 blok 2 — "Test uw bestand zonder iets op te slaan": `POST /revisions/{id}/trial-reads` (NT-9, recht Beheren,
 * niet achter de setup-vlag). De koppeling gaat mee (`linkId`), zodat haar standaardvaluta gebruikt wordt.
 *
 * - Zonder recht Beheren is de knop uitgeschakeld mét reden (nooit verborgen).
 * - Geen bestand: een Nederlandse melding, geen verzoek.
 * - Zolang de proef loopt, staat de knop uit: een dubbele klik geeft één verzoek. Geen automatische herhaling, geen
 *   `AbortSignal` (de server leest het bestand synchroon, zoals bij de upload).
 * - Het resultaat leeft enkel in de toestand van de pagina (niets bewaard, ook niet in de browser).
 */
import { useState, type FormEvent } from 'react';
import * as trialReadsApi from '../../../api/trialReads.ts';
import { PERMISSION_MANAGE, type RevisionRow, type TrialReadResult } from '../../../api/types.ts';
import { usePermissionGate } from '../../../actor/permissions.ts';
import { Field } from '../../../components/Field.tsx';
import { ErrorBanner } from '../../../errors/ErrorBanner.tsx';
import { useAction } from '../../../hooks/useAction.ts';
import { Term } from '../../../terms/Term.tsx';
import { ElapsedTimer } from '../../upload/UploadPage.tsx';
import { TrialReadResultView } from './TrialReadResultView.tsx';
import styles from './LinkCheckPage.module.css';

export type TrialReadSectionProps = {
  linkId: number;
  revision: RevisionRow | null;
  /** Het resultaat van de laatste proef met déze versie (of `null`); de pagina houdt het bij. */
  result: TrialReadResult | null;
  onResult: (revisionId: number, result: TrialReadResult) => void;
};

export function TrialReadSection({ linkId, revision, result, onResult }: TrialReadSectionProps) {
  const manageGate = usePermissionGate(PERMISSION_MANAGE);
  const [file, setFile] = useState<File | null>(null);
  const [validationError, setValidationError] = useState<string | null>(null);
  const runner = useAction((revisionId: number, chosen: File) => trialReadsApi.trialRead(revisionId, chosen, linkId));

  const blockedReason = !manageGate.allowed
    ? manageGate.reason
    : revision === null
      ? 'Er is geen versie van de beschrijving om mee te testen.'
      : null;

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (runner.pending || blockedReason !== null || revision === null) {
      return;
    }
    runner.reset();
    if (file === null) {
      setValidationError('Kies eerst een bestand om te testen.');
      return;
    }
    setValidationError(null);
    const outcome = await runner.execute(revision.id, file);
    if (outcome !== undefined) {
      onResult(revision.id, outcome);
    }
  }

  return (
    <section className={styles.section} aria-labelledby="check-trial-title" data-testid="trial-section">
      <h2 id="check-trial-title" className={styles.sectionTitle}>
        2. Test uw bestand zonder iets op te slaan
      </h2>
      <p className={styles.sectionIntro}>
        Wat is dit? Een proefinlezing: we lezen een echt bestand van de leverancier met
        {revision === null ? ' de beschrijving' : ` versie ${revision.revisionNumber}`} en tonen wat een echte levering
        zou opleveren.
        {revision !== null && (
          <>
            {' '}
            (De versie is <Term domain="revisionStatus" code={revision.status} />.)
          </>
        )}
      </p>
      <p className={styles.nothingStored} data-testid="trial-nothing-stored">
        Er wordt niets opgeslagen of gepubliceerd.
      </p>

      <form onSubmit={handleSubmit} noValidate>
        <Field
          label="Bestand (CSV)"
          htmlFor="trial-file"
          required
          hint="Gebruik bij voorkeur een echt bestand van de leverancier; het wordt volledig gelezen."
        >
          <input
            id="trial-file"
            className={styles.input}
            type="file"
            disabled={runner.pending}
            onChange={(event) => {
              setFile(event.target.files?.[0] ?? null);
              setValidationError(null);
              runner.reset();
            }}
          />
        </Field>

        {validationError !== null && (
          <p className={styles.validationError} role="alert" data-testid="trial-validation">
            {validationError}
          </p>
        )}

        <div className={styles.actions}>
          <button
            type="submit"
            className={styles.submit}
            disabled={runner.pending || blockedReason !== null}
            title={blockedReason ?? undefined}
            aria-describedby={blockedReason === null ? undefined : 'trial-blocked-reason'}
            data-testid="trial-start"
          >
            {runner.pending ? 'Bezig…' : 'Proef starten'}
          </button>
          {blockedReason !== null && (
            <span id="trial-blocked-reason" className={styles.reason} data-testid="trial-blocked-reason">
              {blockedReason}
            </span>
          )}
        </div>
      </form>

      {runner.pending && (
        <p className={styles.loading} role="status" data-testid="trial-progress">
          De proef loopt (verstreken tijd <ElapsedTimer />). Het bestand wordt volledig gelezen; bij een groot bestand
          kan dat even duren. Laat dit tabblad open.
        </p>
      )}
      {runner.error !== null && <ErrorBanner error={runner.error} />}
      {result !== null && <TrialReadResultView result={result} />}
    </section>
  );
}
