/**
 * `/upload` — uploadscherm (scherm 2, bouwstap B-F1), zie `docs/decisions.md` 2026-09-23 "Frontend:
 * batchdetail, scherm (2) ..." stap 9.
 *
 * - Taak kiezen uit `GET /tasks`; een niet-`MANUAL`-taak is uitgeschakeld mét reden (de intake weigert ze
 *   met `TASK_NOT_MANUAL`). Dit scherm maakt géén taak aan (V1: dat hoort bij het materialisatiewizard-spoor).
 * - Geen voortgangsbalk: `fetch` kan de uploadvoortgang niet meten, en de server screent synchroon in
 *   hetzelfde verzoek. Daarom twee benoemde fasen (uploaden, screenen) en één tijdteller; het scherm kan
 *   niet zien wanneer fase 1 eindigt en zegt dat ook.
 * - Deterministische `deliveryReference` (`deliveryReference.ts`), aanpasbaar. Herhalen met dezelfde
 *   referentie is de herstelroute: identiek bestand geeft 200, geen nieuwe screening.
 * - Geen `AbortSignal`, geen timeout, geen automatische retry. De verzendknop staat uit zolang de upload loopt.
 * - Accept-baseline, bundel-opname en `continue` horen bij B-F2/B-F3 en staan hier niet.
 */

import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import * as deliveriesApi from '../../api/deliveries.ts';
import * as tasksApi from '../../api/tasks.ts';
import type { TaskRow } from '../../api/types.ts';
import { useActor, validateActorName } from '../../actor/ActorContext.tsx';
import { Field } from '../../components/Field.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useAction } from '../../hooks/useAction.ts';
import { useQuery } from '../../hooks/useQuery.ts';
import { Count } from '../batches/format.tsx';
import { deriveDeliveryReference, MAX_DELIVERY_REFERENCE_LENGTH } from './deliveryReference.ts';
import styles from './UploadPage.module.css';

const MAX_FILE_NAME_LENGTH = 500;
const NO_TASK = '';

/** mm:ss sinds het mounten; mount = start van de upload. */
export function ElapsedTimer() {
  const [seconds, setSeconds] = useState(0);
  useEffect(() => {
    const id = setInterval(() => setSeconds((s) => s + 1), 1000);
    return () => clearInterval(id);
  }, []);
  const mm = String(Math.floor(seconds / 60)).padStart(2, '0');
  const ss = String(seconds % 60).padStart(2, '0');
  return (
    <span className={styles.timer} data-testid="upload-timer">
      {mm}:{ss}
    </span>
  );
}

/** Leest een optionele niet-negatieve gehele waarde; `undefined` = leeg, `null` = ongeldig. */
function parseOptionalCount(raw: string): number | undefined | null {
  const trimmed = raw.trim();
  if (trimmed === '') {
    return undefined;
  }
  if (!/^\d+$/.test(trimmed)) {
    return null;
  }
  const value = Number(trimmed);
  return Number.isSafeInteger(value) ? value : null;
}

function taskLabel(task: TaskRow): string {
  const notes = [task.triggerType === 'MANUAL' ? null : 'niet manueel', task.active ? null : 'inactief'].filter(
    (note) => note !== null,
  );
  return `${task.importLinkCode} — ${task.name}${notes.length > 0 ? ` (${notes.join(', ')})` : ''}`;
}

export function UploadPage() {
  const { actor } = useActor();
  const tasks = useQuery('tasks:all', (signal) => tasksApi.listTasks({ size: 200 }, signal));

  const [taskId, setTaskId] = useState<string>(NO_TASK);
  const [file, setFile] = useState<File | null>(null);
  const [reference, setReference] = useState('');
  const [expectedRecordCount, setExpectedRecordCount] = useState('');
  const [expectedByteSize, setExpectedByteSize] = useState('');
  const [validationError, setValidationError] = useState<string | null>(null);
  const [deriving, setDeriving] = useState(false);
  const pickCounter = useRef(0);

  const { execute, pending, error, reset } = useAction(deliveriesApi.uploadDelivery);
  const [outcome, setOutcome] = useState<deliveriesApi.UploadOutcome | null>(null);

  const manualTasks = tasks.data?.content.filter((task) => task.triggerType === 'MANUAL') ?? [];
  const noManualTask = tasks.data !== null && manualTasks.length === 0;

  async function handleFileChange(next: File | null) {
    const pick = ++pickCounter.current;
    setFile(next);
    setOutcome(null);
    reset();
    setValidationError(null);
    if (next === null) {
      setReference('');
      return;
    }
    setDeriving(true);
    try {
      const derived = await deriveDeliveryReference(next);
      if (pick === pickCounter.current) {
        setReference(derived);
      }
    } catch {
      if (pick === pickCounter.current) {
        setReference('');
        setValidationError('De referentie kon niet uit het bestand afgeleid worden; vul zelf een referentie in.');
      }
    } finally {
      if (pick === pickCounter.current) {
        setDeriving(false);
      }
    }
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) {
      return;
    }
    setOutcome(null);
    reset();

    const actorError = validateActorName(actor);
    if (actorError !== null) {
      setValidationError(`Geüpload door: ${actorError}`);
      return;
    }
    if (taskId === NO_TASK) {
      setValidationError('Kies een taak.');
      return;
    }
    if (file === null) {
      setValidationError('Kies een bestand.');
      return;
    }
    if (file.name.length > MAX_FILE_NAME_LENGTH) {
      setValidationError(`De bestandsnaam mag niet langer zijn dan ${MAX_FILE_NAME_LENGTH} tekens.`);
      return;
    }
    const trimmedReference = reference.trim();
    if (trimmedReference === '') {
      setValidationError('Vul een referentie in.');
      return;
    }
    if (trimmedReference.length > MAX_DELIVERY_REFERENCE_LENGTH) {
      setValidationError(`Een referentie mag niet langer zijn dan ${MAX_DELIVERY_REFERENCE_LENGTH} tekens.`);
      return;
    }
    const recordCount = parseOptionalCount(expectedRecordCount);
    if (recordCount === null) {
      setValidationError('Verwacht aantal datalijnen moet een geheel getal van 0 of meer zijn.');
      return;
    }
    const byteSize = parseOptionalCount(expectedByteSize);
    if (byteSize === null) {
      setValidationError('Verwachte bestandsgrootte moet een geheel getal van 0 of meer zijn.');
      return;
    }
    setValidationError(null);

    const result = await execute({
      taskId: Number(taskId),
      file,
      deliveryReference: trimmedReference,
      uploadedBy: actor.trim(),
      expectedRecordCount: recordCount,
      expectedByteSize: byteSize,
    });
    if (result !== undefined) {
      setOutcome(result);
    }
  }

  // Zonder (leesbaar) antwoord weet de gebruiker niet of de server doorwerkt of klaar is: de herstelroute
  // is herhalen met dezelfde referentie (idempotent).
  const noAnswer = error !== null && (error.status === 0 || error.status >= 500);
  const submitLabel = pending ? 'Bezig…' : noAnswer ? 'Herhaal met dezelfde referentie' : 'Uploaden';
  const delivery = outcome?.delivery ?? null;

  return (
    <div className={styles.page}>
      <h1 className={styles.title}>Levering uploaden</h1>

      <form className={styles.form} onSubmit={handleSubmit}>
        {tasks.error !== null && <ErrorBanner error={tasks.error} />}
        {tasks.loading && tasks.data === null && tasks.error === null && (
          <p className={styles.loading}>Taken laden…</p>
        )}
        {noManualTask && (
          <p className={styles.note} role="status" data-testid="no-manual-task">
            Er is geen manuele taak. Een levering kan alleen op een taak met trigger MANUAL geüpload worden. Dit
            scherm maakt geen taak aan: dat gebeurt bij het inrichten van de koppeling (materialisatie van het
            sjabloon).
          </p>
        )}

        <Field label="Taak" htmlFor="upload-task" required>
          <select
            id="upload-task"
            className={styles.select}
            value={taskId}
            disabled={pending}
            onChange={(event) => setTaskId(event.target.value)}
          >
            <option value={NO_TASK}>— kies een taak —</option>
            {tasks.data?.content.map((task) => (
              <option key={task.id} value={task.id} disabled={task.triggerType !== 'MANUAL'}>
                {taskLabel(task)}
              </option>
            ))}
          </select>
        </Field>

        <Field label="Bestand (CSV)" htmlFor="upload-file" required>
          <input
            id="upload-file"
            className={styles.input}
            type="file"
            disabled={pending}
            onChange={(event) => void handleFileChange(event.target.files?.[0] ?? null)}
          />
        </Field>

        <Field
          label="Referentie"
          htmlFor="upload-reference"
          required
          hint={`Afgeleid van bestandsnaam en inhoud (geen tijdstempel), max. ${MAX_DELIVERY_REFERENCE_LENGTH} tekens. Hetzelfde bestand met dezelfde referentie opnieuw uploaden is veilig; een ander bestand heeft een nieuwe referentie nodig.`}
        >
          <input
            id="upload-reference"
            className={styles.input}
            type="text"
            value={reference}
            disabled={pending}
            onChange={(event) => setReference(event.target.value)}
          />
        </Field>

        <Field label="Verwacht aantal datalijnen" htmlFor="upload-expected-records" hint="Optioneel. Wijkt het af, dan wordt de levering geblokkeerd.">
          <input
            id="upload-expected-records"
            className={styles.input}
            type="text"
            inputMode="numeric"
            value={expectedRecordCount}
            disabled={pending}
            onChange={(event) => setExpectedRecordCount(event.target.value)}
          />
        </Field>

        <Field label="Verwachte bestandsgrootte (bytes)" htmlFor="upload-expected-bytes" hint="Optioneel. Wijkt ze af, dan wordt de levering geblokkeerd.">
          <input
            id="upload-expected-bytes"
            className={styles.input}
            type="text"
            inputMode="numeric"
            value={expectedByteSize}
            disabled={pending}
            onChange={(event) => setExpectedByteSize(event.target.value)}
          />
        </Field>

        <p className={styles.actorRow}>
          Geüpload door: <strong>{actor === '' ? '(nog niet ingevuld)' : actor}</strong> — wijzig dit hierboven bij
          "Ingelogd als".
        </p>

        {validationError !== null && (
          <p className={styles.validationError} role="alert">
            {validationError}
          </p>
        )}

        {pending && (
          <div className={styles.progress} role="status" data-testid="upload-progress">
            <p>
              Verstreken tijd: <ElapsedTimer />
            </p>
            <ol className={styles.phases}>
              <li>Uploaden — het bestand wordt naar de server gestuurd.</li>
              <li>Screenen — de server leest en beoordeelt elke regel.</li>
            </ol>
            <p>
              Beide fasen lopen in één verzoek: er is geen voortgangsbalk en dit scherm ziet niet wanneer fase 1
              klaar is. Dit kan lang duren (minuten bij grote bestanden). Laat dit tabblad open.
            </p>
          </div>
        )}

        {error !== null && <ErrorBanner error={error} />}
        {noAnswer && (
          <p className={styles.note} role="status" data-testid="upload-recovery">
            De server kan de levering toch verwerkt hebben. Herhaal met dezelfde referentie
            {reference.trim() !== '' && (
              <>
                {' '}
                (<strong>{reference.trim()}</strong>)
              </>
            )}
            : is ze al verwerkt, dan krijgt u de bestaande levering terug (HTTP 200, geen nieuwe screening); zo niet,
            dan wordt ze nu verwerkt. Wijzig de referentie niet.
          </p>
        )}

        <button type="submit" className={styles.submit} disabled={pending || deriving}>
          {submitLabel}
        </button>
      </form>

      {delivery !== null && outcome !== null && (
        <section className={styles.result} data-testid="upload-result" aria-label="Resultaat van de upload">
          <h2>{outcome.created ? 'Levering aangemaakt en gescreend' : 'Bestaande levering teruggevonden'}</h2>
          {!outcome.created && (
            <p role="status">
              Deze referentie was al verwerkt met een identiek bestand (HTTP 200). Er is niet opnieuw gescreend en
              er is geen nieuwe batch ontstaan.
            </p>
          )}
          <p>
            Levering #{delivery.deliveryId} · Batch{' '}
            <Link to={`/batches/${delivery.batchId}`}>#{delivery.batchId}</Link> · status{' '}
            <strong>{delivery.status}</strong>
            {delivery.blockedCode !== null && (
              <>
                {' '}
                · reden <strong>{delivery.blockedCode}</strong>
              </>
            )}
          </p>
          <dl className={styles.counters}>
            <Counter label="Ruwe records" value={delivery.rawRecordCount} />
            <Counter label="Geldig" value={delivery.validRecordCount} />
            <Counter label="Verworpen" value={delivery.rejectedRecordCount} />
            <Counter label="Dubbele identiteit" value={delivery.duplicateIdentityCount} />
            <Counter label="Nieuw" value={delivery.newCount} />
            <Counter label="Gewijzigd" value={delivery.changedCount} />
            <Counter label="Ongewijzigd" value={delivery.unchangedCount} />
            <Counter label="Inhoudsmutaties" value={delivery.contentMutationCount} />
          </dl>
        </section>
      )}
    </div>
  );
}

function Counter({ label, value }: { label: string; value: number | null }) {
  return (
    <div className={styles.counter}>
      <dt>{label}</dt>
      <dd>
        <Count value={value} />
      </dd>
    </div>
  );
}
