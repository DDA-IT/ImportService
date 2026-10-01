/**
 * `/upload` — uploadscherm (scherm 2, bouwstap B-F1), zie `docs/decisions.md` 2026-09-23 "Frontend:
 * batchdetail, scherm (2) ..." stap 9.
 *
 * - Taak kiezen uit `GET /tasks`; een niet-`MANUAL`-taak is uitgeschakeld mét reden (de intake weigert ze
 *   met `TASK_NOT_MANUAL`). Dit scherm maakt géén taak aan (dat hoort bij het NT-spoor: Inrichting → Nieuwe leverancier en taak).
 * - NT-6: een taak zonder actieve versie (`activeRevisionId === null`) blijft zichtbaar maar uitgeschakeld,
 *   met "(nog niet klaar: versie niet geactiveerd)" en een korte verwijzing naar Inrichting; sinds NT-10 ook per
 *   koppeling een link "Controleren" naar `/setup/links/:linkId/check`.
 * - Geen voortgangsbalk: `fetch` kan de uploadvoortgang niet meten, en de server screent synchroon in
 *   hetzelfde verzoek. Daarom twee benoemde fasen (uploaden, screenen) en één tijdteller; het scherm kan
 *   niet zien wanneer fase 1 eindigt en zegt dat ook.
 * - Deterministische `deliveryReference` (`deliveryReference.ts`), aanpasbaar. Herhalen met dezelfde
 *   referentie is de herstelroute: identiek bestand geeft 200, geen nieuwe screening.
 * - Geen `AbortSignal`, geen timeout, geen automatische retry. De verzendknop staat uit zolang de upload loopt.
 * - Accept-baseline, bundel-opname en `continue` horen bij B-F2/B-F3 en staan hier niet.
 * - Tweede ontvangstweg (`docs/decisions.md` 2026-09-27): naast "bestand van mijn computer" kan een
 *   bestand uit een beheerde servermap gekozen worden (`GET /local-source/files`). Daar leidt de
 *   server zelf de `deliveryReference` af (de browser kan het serverbestand niet hashen); dit scherm
 *   toont enkel wat de server teruggeeft. Geen vrije bestandsnaaminvoer: enkel kiezen uit de lijst.
 */

import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import * as deliveriesApi from '../../api/deliveries.ts';
import * as localSourceApi from '../../api/localSource.ts';
import * as tasksApi from '../../api/tasks.ts';
import { PERMISSION_MANAGE, type TaskRow, type UploadResponse } from '../../api/types.ts';
import { useActor } from '../../actor/ActorContext.tsx';
import { usePermissionGate } from '../../actor/permissions.ts';
import { Field } from '../../components/Field.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useAction } from '../../hooks/useAction.ts';
import { useQuery } from '../../hooks/useQuery.ts';
import { StatusBadge } from '../../components/StatusBadge.tsx';
import { IssueCodeTerm } from '../../terms/IssueCodeTerm.tsx';
import { Count, CounterLabel } from '../batches/format.tsx';
import { linkCheckHref } from '../setup/check/linkCheck.ts';
import { deriveDeliveryReference, MAX_DELIVERY_REFERENCE_LENGTH } from './deliveryReference.ts';
import styles from './UploadPage.module.css';

const MAX_FILE_NAME_LENGTH = 500;
const NO_TASK = '';
const NO_FILE = '';

type SourceKind = 'UPLOAD' | 'LOCAL_SOURCE';

/** `Outcome` van beide bronnen heeft dezelfde vorm; enkel de aanroep zelf verschilt. */
type SubmitOutcome = { created: boolean; delivery: UploadResponse };

function formatByteSize(byteSize: number): string {
  if (byteSize < 1024) {
    return `${byteSize} B`;
  }
  const units = ['kB', 'MB', 'GB', 'TB'];
  let value = byteSize / 1024;
  let unitIndex = 0;
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024;
    unitIndex += 1;
  }
  return `${value.toFixed(1)} ${units[unitIndex]}`;
}

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

/**
 * NT-6: een taak waarvan de beschrijving nog geen actieve versie heeft (`activeRevisionId === null`, NT-4), is
 * nog niet klaar — de intake zou ze weigeren met `NO_ACTIVE_REVISION`. Ze blijft zichtbaar (nooit verborgen),
 * maar is niet te kiezen. Enkel `null` telt: een ontbrekend veld (oudere server) wordt niet als "niet klaar"
 * gelezen.
 */
function isNotReady(task: TaskRow): boolean {
  return task.activeRevisionId === null;
}

function taskLabel(task: TaskRow): string {
  const notes = [
    task.triggerType === 'MANUAL' ? null : 'niet manueel',
    task.active ? null : 'inactief',
    isNotReady(task) ? 'nog niet klaar: versie niet geactiveerd' : null,
  ].filter((note) => note !== null);
  return `${task.importLinkCode} — ${task.name}${notes.length > 0 ? ` (${notes.join(', ')})` : ''}`;
}

export function UploadPage() {
  const { actor } = useActor();
  const manageGate = usePermissionGate(PERMISSION_MANAGE);
  const tasks = useQuery('tasks:all', (signal) => tasksApi.listTasks({ size: 200 }, signal));

  const [sourceKind, setSourceKind] = useState<SourceKind>('UPLOAD');
  const [taskId, setTaskId] = useState<string>(NO_TASK);
  const [file, setFile] = useState<File | null>(null);
  const [selectedFileName, setSelectedFileName] = useState<string>(NO_FILE);
  const [reference, setReference] = useState('');
  const [expectedRecordCount, setExpectedRecordCount] = useState('');
  const [expectedByteSize, setExpectedByteSize] = useState('');
  const [validationError, setValidationError] = useState<string | null>(null);
  const [deriving, setDeriving] = useState(false);
  const pickCounter = useRef(0);

  // Enkel bevraagd zolang de servermap-bron gekozen is; bij "mijn computer" geen netwerkverzoek.
  const localSourceFiles = useQuery(`local-source:${sourceKind}`, (signal) =>
    sourceKind === 'LOCAL_SOURCE' ? localSourceApi.listLocalSourceFiles(signal) : Promise.resolve(null),
  );
  const localSourceNotConfigured =
    localSourceFiles.error !== null &&
    localSourceFiles.error.status === 404 &&
    localSourceFiles.error.code === 'LOCAL_SOURCE_NOT_CONFIGURED';

  type SubmitInput =
    | { source: 'UPLOAD'; upload: deliveriesApi.UploadDeliveryParams }
    | { source: 'LOCAL_SOURCE'; local: localSourceApi.ReadLocalSourceDeliveryParams };
  const { execute, pending, error, reset } = useAction(async (input: SubmitInput): Promise<SubmitOutcome> => {
    if (input.source === 'UPLOAD') {
      return deliveriesApi.uploadDelivery(input.upload);
    }
    return localSourceApi.readLocalSourceDelivery(input.local);
  });
  const [outcome, setOutcome] = useState<SubmitOutcome | null>(null);

  const manualTasks = tasks.data?.content.filter((task) => task.triggerType === 'MANUAL') ?? [];
  const noManualTask = tasks.data !== null && manualTasks.length === 0;
  const hasNotReadyTask = manualTasks.some(isNotReady);
  // NT-10: per koppeling (niet per taak) één link naar het scherm "Controleren".
  const notReadyLinks = manualTasks
    .filter(isNotReady)
    .filter((task, index, all) => all.findIndex((other) => other.importLinkId === task.importLinkId) === index);

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

  function handleSourceChange(next: SourceKind) {
    if (next === sourceKind) {
      return;
    }
    setSourceKind(next);
    setFile(null);
    setSelectedFileName(NO_FILE);
    setReference('');
    setOutcome(null);
    setValidationError(null);
    reset();
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending || !manageGate.allowed) {
      return;
    }
    setOutcome(null);
    reset();

    if (taskId === NO_TASK) {
      setValidationError('Kies een taak.');
      return;
    }
    if (sourceKind === 'UPLOAD' && file === null) {
      setValidationError('Kies een bestand.');
      return;
    }
    if (sourceKind === 'UPLOAD' && file !== null && file.name.length > MAX_FILE_NAME_LENGTH) {
      setValidationError(`De bestandsnaam mag niet langer zijn dan ${MAX_FILE_NAME_LENGTH} tekens.`);
      return;
    }
    if (sourceKind === 'LOCAL_SOURCE' && selectedFileName === NO_FILE) {
      setValidationError('Kies een bestand uit de servermap.');
      return;
    }
    const trimmedReference = reference.trim();
    // Bij de servermap-bron is de referentie optioneel: de server leidt ze zelf af.
    if (sourceKind === 'UPLOAD' && trimmedReference === '') {
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

    const result =
      sourceKind === 'UPLOAD'
        ? await execute({
            source: 'UPLOAD',
            upload: {
              taskId: Number(taskId),
              file: file!,
              deliveryReference: trimmedReference,
              uploadedBy: actor.trim(),
              expectedRecordCount: recordCount,
              expectedByteSize: byteSize,
            },
          })
        : await execute({
            source: 'LOCAL_SOURCE',
            local: {
              taskId: Number(taskId),
              fileName: selectedFileName,
              deliveryReference: trimmedReference === '' ? undefined : trimmedReference,
              uploadedBy: actor.trim(),
              expectedRecordCount: recordCount,
              expectedByteSize: byteSize,
            },
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
            Er is nog geen taak. Maak er een via <Link to="/setup">Inrichting</Link> → Nieuwe leverancier en taak (recht Beheren nodig).
          </p>
        )}
        {hasNotReadyTask && (
          <p className={styles.note} role="note" data-testid="not-ready-tasks">
            Een taak met &laquo;nog niet klaar&raquo; kan nog geen levering aannemen: de versie van haar beschrijving
            is nog niet geactiveerd. Controleer en activeer die versie bij <Link to="/setup">Inrichting</Link>, of
            rechtstreeks:{' '}
            {notReadyLinks.map((task, index) => (
              <span key={task.importLinkId}>
                {index > 0 && ', '}
                <Link
                  to={linkCheckHref({
                    linkId: task.importLinkId,
                    definitionId: typeof task.importDefinitionId === 'number' ? task.importDefinitionId : null,
                  })}
                  data-testid={`check-link-${task.importLinkId}`}
                >
                  Controleren ({task.importLinkCode})
                </Link>
              </span>
            ))}
            .
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
              <option key={task.id} value={task.id} disabled={task.triggerType !== 'MANUAL' || isNotReady(task)}>
                {taskLabel(task)}
              </option>
            ))}
          </select>
        </Field>

        <fieldset className={styles.sourceChoice}>
          <legend>Bron</legend>
          <label>
            <input
              type="radio"
              name="upload-source"
              checked={sourceKind === 'UPLOAD'}
              disabled={pending}
              onChange={() => handleSourceChange('UPLOAD')}
            />{' '}
            Bestand van mijn computer
          </label>
          <label>
            <input
              type="radio"
              name="upload-source"
              checked={sourceKind === 'LOCAL_SOURCE'}
              disabled={pending || localSourceNotConfigured}
              onChange={() => handleSourceChange('LOCAL_SOURCE')}
            />{' '}
            Bestand op de server
          </label>
        </fieldset>

        {sourceKind === 'UPLOAD' && (
          <Field label="Bestand (CSV)" htmlFor="upload-file" required>
            <input
              id="upload-file"
              className={styles.input}
              type="file"
              disabled={pending}
              onChange={(event) => void handleFileChange(event.target.files?.[0] ?? null)}
            />
          </Field>
        )}

        {sourceKind === 'LOCAL_SOURCE' && (
          <Field label="Kies een bestand uit de servermap" htmlFor="upload-local-file" required>
            {localSourceFiles.error !== null && <ErrorBanner error={localSourceFiles.error} />}
            {localSourceFiles.loading && localSourceFiles.data === null && localSourceFiles.error === null && (
              <p className={styles.loading}>Bestanden laden…</p>
            )}
            {localSourceFiles.data !== null && localSourceFiles.data.files.length === 0 && (
              <p className={styles.note} role="status" data-testid="no-local-source-files">
                Geen bestanden gevonden in de servermap.
              </p>
            )}
            {localSourceFiles.data !== null && localSourceFiles.data.files.length > 0 && (
              <select
                id="upload-local-file"
                className={styles.select}
                value={selectedFileName}
                disabled={pending}
                onChange={(event) => {
                  setSelectedFileName(event.target.value);
                  setOutcome(null);
                  reset();
                  setValidationError(null);
                }}
              >
                <option value={NO_FILE}>— kies een bestand —</option>
                {localSourceFiles.data.files.map((entry) => (
                  <option key={entry.fileName} value={entry.fileName}>
                    {entry.fileName} — {formatByteSize(entry.byteSize)} —{' '}
                    {new Date(entry.lastModifiedAt).toLocaleString()}
                  </option>
                ))}
              </select>
            )}
            {localSourceFiles.data?.truncated === true && (
              <p className={styles.note} role="status">
                De lijst toont niet alle bestanden in de servermap (afgekapt bij 500).
              </p>
            )}
          </Field>
        )}

        <Field
          label="Referentie"
          htmlFor="upload-reference"
          required={sourceKind === 'UPLOAD'}
          hint={
            sourceKind === 'UPLOAD'
              ? `Afgeleid van bestandsnaam en inhoud (geen tijdstempel), max. ${MAX_DELIVERY_REFERENCE_LENGTH} tekens. Hetzelfde bestand met dezelfde referentie opnieuw uploaden is veilig; een ander bestand heeft een nieuwe referentie nodig.`
              : `Optioneel: leeg gelaten leidt de server de referentie zelf af (uit de vingerafdruk van het bestand). Max. ${MAX_DELIVERY_REFERENCE_LENGTH} tekens.`
          }
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

        <Field label="Verwacht aantal datalijnen" htmlFor="upload-expected-records" hint="Optioneel. Wijkt het af, dan wordt de levering tegengehouden.">
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

        <Field label="Verwachte bestandsgrootte (bytes)" htmlFor="upload-expected-bytes" hint="Optioneel. Wijkt ze af, dan wordt de levering tegengehouden.">
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
          Geüpload door: <strong>{actor}</strong>
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
              <li>Controleren — de server leest en beoordeelt elke regel.</li>
            </ol>
            <p>
              Beide fasen lopen in één verzoek: er is geen voortgangsbalk en dit scherm ziet niet wanneer de eerste
              fase klaar is. Dit kan lang duren (minuten bij grote bestanden). Laat dit tabblad open.
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
            : is ze al verwerkt, dan krijgt u de bestaande levering terug (zonder nieuwe controle); zo niet, dan
            wordt ze nu verwerkt. Wijzig de referentie niet.
          </p>
        )}

        {!manageGate.allowed && (
          <p className={styles.validationError} id="upload-permission-reason" data-testid="permission-reason-manage">
            {manageGate.reason}
          </p>
        )}
        <button
          type="submit"
          className={styles.submit}
          disabled={pending || deriving || !manageGate.allowed}
          title={manageGate.allowed ? undefined : manageGate.reason}
          aria-describedby={manageGate.allowed ? undefined : 'upload-permission-reason'}
        >
          {submitLabel}
        </button>
      </form>

      {delivery !== null && outcome !== null && (
        <section className={styles.result} data-testid="upload-result" aria-label="Resultaat van de upload">
          <h2>{outcome.created ? 'Levering aangemaakt en gecontroleerd' : 'Bestaande levering teruggevonden'}</h2>
          {!outcome.created && (
            <p role="status">
              Deze referentie was al verwerkt met een identiek bestand. Er is niet opnieuw gecontroleerd en er is
              geen nieuwe batch ontstaan.
            </p>
          )}
          <p>
            Levering #{delivery.deliveryId} · Batch{' '}
            <Link to={`/batches/${delivery.batchId}`}>#{delivery.batchId}</Link> · status{' '}
            <strong>
              <StatusBadge status={delivery.status} domain="batchStatus" />
            </strong>
            {delivery.blockedCode !== null && (
              <>
                {' '}
                · reden van tegenhouden{' '}
                <strong>
                  <IssueCodeTerm code={delivery.blockedCode} />
                </strong>
              </>
            )}
          </p>
          <p>
            Referentie: <strong data-testid="upload-result-reference">{delivery.deliveryReference}</strong>
          </p>
          <dl className={styles.counters}>
            <Counter counter="rawRecordCount" value={delivery.rawRecordCount} />
            <Counter counter="validRecordCount" value={delivery.validRecordCount} />
            <Counter counter="rejectedRecordCount" value={delivery.rejectedRecordCount} />
            <Counter counter="duplicateIdentityCount" value={delivery.duplicateIdentityCount} />
            <Counter counter="newCount" value={delivery.newCount} />
            <Counter counter="changedCount" value={delivery.changedCount} />
            <Counter counter="unchangedCount" value={delivery.unchangedCount} />
            <Counter counter="contentMutationCount" value={delivery.contentMutationCount} />
          </dl>
        </section>
      )}
    </div>
  );
}

function Counter({ counter, value }: { counter: string; value: number | null }) {
  return (
    <div className={styles.counter}>
      <dt>
        <CounterLabel counter={counter} />
      </dt>
      <dd>
        <Count value={value} />
      </dd>
    </div>
  );
}
