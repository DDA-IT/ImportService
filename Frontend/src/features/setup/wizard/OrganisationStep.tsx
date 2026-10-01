/**
 * NT-6 stap 1 — de leverancier of aankoopvereniging die het bestand aanlevert: een bestaande kiezen of een
 * nieuwe aanmaken (`POST /setup/source-organisations`). `OrganisationCreateSection` wordt in stap 4
 * hergebruikt om bij een aankoopvereniging de leverancier van de koppeling aan te maken.
 */
import { useState } from 'react';
import * as setupCreateApi from '../../../api/setupCreate.ts';
import {
  PERMISSION_MANAGE,
  SOURCE_ORGANISATION_TYPES,
  type SourceOrganisationRow,
  type SourceOrganisationType,
} from '../../../api/types.ts';
import { usePermissionGate } from '../../../actor/permissions.ts';
import { Field } from '../../../components/Field.tsx';
import { ErrorBanner } from '../../../errors/ErrorBanner.tsx';
import { useQuery } from '../../../hooks/useQuery.ts';
import { Term } from '../../../terms/Term.tsx';
import { term } from '../../../terms/index.ts';
import { findOrganisationByCode, loadAllOrganisations } from './lookup.ts';
import { ExistingChoice, StepError } from './StepFeedback.tsx';
import { fieldMessage, fieldOfError, toApiError, useStepSubmit } from './stepSubmit.ts';
import { organisationLabel, toWizardOrganisation } from './wizardMappers.ts';
import type { WizardOrganisation } from './wizardTypes.ts';
import styles from './Wizard.module.css';

const MAX_CODE = 50;
const MAX_NAME = 200;

type CreateForm = { code: string; name: string; type: SourceOrganisationType | '' };

export function OrganisationCreateSection({
  idPrefix,
  fixedType,
  submitLabel,
  onDone,
}: {
  idPrefix: string;
  /** Gezet in stap 4: de leverancier van de koppeling is altijd een leverancier. */
  fixedType?: SourceOrganisationType;
  submitLabel: string;
  /** `adopted` = de gebruiker koos uitdrukkelijk een bestaande organisatie met dezelfde code. */
  onDone: (organisation: WizardOrganisation, adopted: boolean) => void;
}) {
  const manageGate = usePermissionGate(PERMISSION_MANAGE);
  const submit = useStepSubmit();
  const [form, setForm] = useState<CreateForm>({ code: '', name: '', type: fixedType ?? '' });
  const [clientErrors, setClientErrors] = useState<Partial<Record<keyof CreateForm, string>>>({});
  const [candidate, setCandidate] = useState<SourceOrganisationRow | null>(null);

  const serverField = fieldOfError(
    submit.error,
    { CODE: 'code', NAME: 'name', TYPE: 'type' },
    { SOURCE_ORGANISATION_CODE_IN_USE: 'code' },
  );

  function fieldError(name: keyof CreateForm): string | null {
    if (clientErrors[name] !== undefined) {
      return clientErrors[name] ?? null;
    }
    return serverField === name && submit.error !== null ? fieldMessage(submit.error) : null;
  }

  function update<K extends keyof CreateForm>(name: K, value: CreateForm[K]) {
    setForm((previous) => {
      const next: CreateForm = { ...previous };
      next[name] = value;
      return next;
    });
    setClientErrors((previous) => {
      const next = { ...previous };
      delete next[name];
      return next;
    });
    setCandidate(null);
    submit.clearError();
  }

  function validate(): boolean {
    const problems: Partial<Record<keyof CreateForm, string>> = {};
    const code = form.code.trim();
    const name = form.name.trim();
    if (code === '') {
      problems.code = 'Vul een code in.';
    } else if (code.length > MAX_CODE) {
      problems.code = `Een code heeft hoogstens ${MAX_CODE} tekens.`;
    }
    if (name === '') {
      problems.name = 'Vul een naam in.';
    } else if (name.length > MAX_NAME) {
      problems.name = `Een naam heeft hoogstens ${MAX_NAME} tekens.`;
    }
    if (form.type === '') {
      problems.type = 'Kies of het om een leverancier of een aankoopvereniging gaat.';
    }
    setClientErrors(problems);
    return Object.keys(problems).length === 0;
  }

  async function handleCreate() {
    if (!manageGate.allowed || submit.pending || !validate()) {
      return;
    }
    const code = form.code.trim();
    const type = form.type as SourceOrganisationType;
    setCandidate(null);
    const recheck = submit.recheckFirst;
    await submit.run(async () => {
      if (recheck) {
        // Het vorige verzoek kreeg geen antwoord: eerst nagaan of het toch aangekomen is.
        const existing = await findOrganisationByCode(code);
        if (existing !== null) {
          setCandidate(existing);
          return;
        }
      }
      try {
        const created = await setupCreateApi.createSourceOrganisation({ code, name: form.name.trim(), type });
        onDone(toWizardOrganisation(created), false);
      } catch (cause) {
        const error = toApiError(cause);
        if (error.status === 409 && error.code === 'SOURCE_ORGANISATION_CODE_IN_USE') {
          setCandidate(await findOrganisationByCode(code).catch(() => null));
        }
        throw error;
      }
    });
  }

  return (
    <div data-testid={`${idPrefix}-create`}>
      <Field
        label={term('setupField', 'code').label}
        htmlFor={`${idPrefix}-code`}
        required
        help={term('setupField', 'code').uitleg}
        error={fieldError('code')}
      >
        <input
          id={`${idPrefix}-code`}
          className={styles.input}
          type="text"
          value={form.code}
          maxLength={MAX_CODE}
          onChange={(event) => update('code', event.target.value)}
        />
      </Field>
      <Field label="Naam" htmlFor={`${idPrefix}-name`} required error={fieldError('name')}>
        <input
          id={`${idPrefix}-name`}
          className={styles.input}
          type="text"
          value={form.name}
          maxLength={MAX_NAME}
          onChange={(event) => update('name', event.target.value)}
        />
      </Field>
      {fixedType === undefined && (
        <fieldset className={styles.fieldset}>
          <legend className={styles.legend}>Soort organisatie *</legend>
          {SOURCE_ORGANISATION_TYPES.map((type) => (
            <label key={type} className={styles.radioRow}>
              <input
                type="radio"
                name={`${idPrefix}-type`}
                value={type}
                checked={form.type === type}
                onChange={() => update('type', type)}
              />{' '}
              <Term domain="organisationType" code={type} /> — {term('organisationType', type).uitleg}
            </label>
          ))}
          {fieldError('type') !== null && (
            <p className={styles.fieldNote} role="alert">
              {fieldError('type')}
            </p>
          )}
        </fieldset>
      )}

      {candidate !== null && (
        <ExistingChoice disabled={submit.pending} onContinue={() => onDone(toWizardOrganisation(candidate), true)}>
          Er bestaat al een organisatie met code <strong>{candidate.code}</strong>: {candidate.name} (
          <Term domain="organisationType" code={candidate.type} />
          ). Kiest u deze, dan wordt er niets nieuws aangemaakt.
        </ExistingChoice>
      )}
      <StepError error={submit.error} fieldName={serverField} />

      <div className={styles.actions}>
        <button
          type="button"
          className={styles.submit}
          disabled={submit.pending || !manageGate.allowed}
          title={manageGate.allowed ? undefined : manageGate.reason}
          onClick={() => void handleCreate()}
        >
          {submit.pending ? 'Bezig…' : submitLabel}
        </button>
      </div>
    </div>
  );
}

type Mode = 'EXISTING' | 'NEW';

export function OrganisationStep({
  onChosen,
}: {
  onChosen: (organisation: WizardOrganisation, notice: string | null) => void;
}) {
  const organisations = useQuery('wizard-organisations', (signal) => loadAllOrganisations(signal));
  const [mode, setMode] = useState<Mode>('NEW');
  const [selectedId, setSelectedId] = useState('');
  const [selectError, setSelectError] = useState<string | null>(null);

  const rows = organisations.data ?? [];

  function continueWithExisting() {
    const row = rows.find((candidate) => String(candidate.id) === selectedId);
    if (row === undefined) {
      setSelectError('Kies een bestaande leverancier of aankoopvereniging.');
      return;
    }
    onChosen(toWizardOrganisation(row), null);
  }

  return (
    <section className={styles.form} aria-labelledby="wizard-step-1-title">
      <h2 id="wizard-step-1-title" className={styles.stepTitle}>
        Stap 1 — {term('setupField', 'sourceOrganisation').label}
      </h2>
      <p className={styles.note}>{term('setupField', 'sourceOrganisation').uitleg}</p>

      <fieldset className={styles.fieldset}>
        <legend className={styles.legend}>Wat wilt u doen?</legend>
        <label className={styles.radioRow}>
          <input type="radio" name="wizard-org-mode" checked={mode === 'NEW'} onChange={() => setMode('NEW')} />{' '}
          Een nieuwe aanmaken
        </label>
        <label className={styles.radioRow}>
          <input
            type="radio"
            name="wizard-org-mode"
            checked={mode === 'EXISTING'}
            onChange={() => setMode('EXISTING')}
          />{' '}
          Een bestaande kiezen
        </label>
      </fieldset>

      {mode === 'EXISTING' && (
        <>
          {organisations.error !== null && <ErrorBanner error={organisations.error} />}
          {organisations.loading && organisations.data === null && (
            <p className={styles.loading}>Bezig met laden…</p>
          )}
          {organisations.data !== null && rows.length === 0 && (
            <p className={styles.note}>Er bestaat nog geen enkele leverancier of aankoopvereniging.</p>
          )}
          {rows.length > 0 && (
            <>
              <Field
                label={term('setupField', 'sourceOrganisation').label}
                htmlFor="wizard-org-existing"
                required
                error={selectError}
              >
                <select
                  id="wizard-org-existing"
                  className={styles.select}
                  value={selectedId}
                  onChange={(event) => {
                    setSelectedId(event.target.value);
                    setSelectError(null);
                  }}
                >
                  <option value="">— kies —</option>
                  {rows.map((row) => (
                    <option key={row.id} value={row.id}>
                      {organisationLabel(row)}
                    </option>
                  ))}
                </select>
              </Field>
              <div className={styles.actions}>
                <button type="button" className={styles.submit} onClick={continueWithExisting}>
                  Verder
                </button>
              </div>
            </>
          )}
        </>
      )}

      {mode === 'NEW' && (
        <OrganisationCreateSection
          idPrefix="wizard-org"
          submitLabel="Opslaan en verder"
          onDone={(organisation, adopted) =>
            onChosen(
              organisation,
              adopted ? `U gaat verder met de bestaande organisatie ${organisation.code}.` : null,
            )
          }
        />
      )}
    </section>
  );
}
