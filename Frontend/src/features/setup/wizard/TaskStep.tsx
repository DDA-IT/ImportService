/**
 * NT-6 stap 5 — de taak (`POST /setup/tasks`, V3 = a): de ingang waarop leveringen opgeladen worden. Altijd
 * handmatig. "Gelijktijdige uitvoeringen voorkomen" staat standaard aan, onder "Geavanceerd".
 */
import { useState, type FormEvent } from 'react';
import { usePermissionGate } from '../../../actor/permissions.ts';
import * as setupCreateApi from '../../../api/setupCreate.ts';
import { PERMISSION_MANAGE, type TaskRow } from '../../../api/types.ts';
import { Field } from '../../../components/Field.tsx';
import { term } from '../../../terms/index.ts';
import { findTaskByName } from './lookup.ts';
import { ExistingChoice, StepError } from './StepFeedback.tsx';
import { fieldMessage, fieldOfError, toApiError, useStepSubmit } from './stepSubmit.ts';
import { suggestedTaskName } from './wizardMappers.ts';
import type { WizardLink, WizardTask } from './wizardTypes.ts';
import styles from './Wizard.module.css';

const MAX_NAME = 200;

export type TaskStepProps = {
  link: WizardLink;
  onTask: (task: WizardTask, notice: string | null) => void;
};

export function TaskStep({ link, onTask }: TaskStepProps) {
  const manageGate = usePermissionGate(PERMISSION_MANAGE);
  const submit = useStepSubmit();
  const [name, setName] = useState(() => suggestedTaskName(link.name));
  const [preventConcurrentRuns, setPreventConcurrentRuns] = useState(true);
  const [nameError, setNameError] = useState<string | null>(null);
  const [candidate, setCandidate] = useState<TaskRow | null>(null);

  const serverField = fieldOfError(submit.error, { NAME: 'name' }, { TASK_NAME_IN_USE: 'name' });
  const shownNameError =
    nameError ?? (serverField === 'name' && submit.error !== null ? fieldMessage(submit.error) : null);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!manageGate.allowed || submit.pending) {
      return;
    }
    const trimmedName = name.trim();
    if (trimmedName === '') {
      setNameError('Vul een naam in.');
      return;
    }
    if (trimmedName.length > MAX_NAME) {
      setNameError(`Een naam heeft hoogstens ${MAX_NAME} tekens.`);
      return;
    }
    setNameError(null);
    setCandidate(null);
    const recheck = submit.recheckFirst;
    await submit.run(async () => {
      if (recheck) {
        const existing = await findTaskByName(link.id, trimmedName);
        if (existing !== null) {
          setCandidate(existing);
          return;
        }
      }
      try {
        const created = await setupCreateApi.createTask({ linkId: link.id, name: trimmedName, preventConcurrentRuns });
        onTask({ id: created.id, name: created.name }, null);
      } catch (cause) {
        const error = toApiError(cause);
        if (error.status === 409 && error.code === 'TASK_NAME_IN_USE') {
          setCandidate(await findTaskByName(link.id, trimmedName).catch(() => null));
        }
        throw error;
      }
    });
  }

  const taskTerm = term('setupField', 'task');
  const concurrencyTerm = term('setupField', 'preventConcurrentRuns');

  return (
    <form className={styles.form} onSubmit={handleSubmit} aria-labelledby="wizard-step-5-title" noValidate>
      <h2 id="wizard-step-5-title" className={styles.stepTitle}>
        Stap 5 — {taskTerm.label}
      </h2>
      <p className={styles.note}>
        {taskTerm.uitleg} De taak is <strong>{term('taskTrigger', 'MANUAL').label.toLowerCase()}</strong>:{' '}
        {term('taskTrigger', 'MANUAL').uitleg}
      </p>

      <Field
        label="Naam van de taak"
        htmlFor="wizard-task-name"
        required
        help={taskTerm.uitleg}
        error={shownNameError}
      >
        <input
          id="wizard-task-name"
          className={styles.input}
          type="text"
          value={name}
          maxLength={MAX_NAME}
          onChange={(event) => {
            setName(event.target.value);
            setNameError(null);
            setCandidate(null);
            submit.clearError();
          }}
        />
      </Field>

      <details className={styles.advanced}>
        <summary>Geavanceerd</summary>
        <label className={styles.checkboxRow}>
          <input
            id="wizard-task-prevent-concurrent"
            type="checkbox"
            checked={preventConcurrentRuns}
            onChange={(event) => setPreventConcurrentRuns(event.target.checked)}
          />{' '}
          {concurrencyTerm.label}
        </label>
        <p className={styles.note}>{concurrencyTerm.uitleg} Standaard aan.</p>
      </details>

      {candidate !== null && (
        <ExistingChoice
          disabled={submit.pending}
          onContinue={() => {
            setCandidate(null);
            onTask({ id: candidate.id, name: candidate.name }, `U gaat verder met de bestaande taak ${candidate.name}.`);
          }}
        >
          Deze koppeling heeft al een taak met de naam <strong>{candidate.name}</strong>. Kiest u deze, dan wordt er
          niets nieuws aangemaakt.
        </ExistingChoice>
      )}
      <StepError error={submit.error} fieldName={serverField} />

      <div className={styles.actions}>
        <button
          type="submit"
          className={styles.submit}
          disabled={submit.pending || !manageGate.allowed}
          title={manageGate.allowed ? undefined : manageGate.reason}
        >
          {submit.pending ? 'Bezig…' : 'Taak aanmaken'}
        </button>
      </div>
    </form>
  );
}
