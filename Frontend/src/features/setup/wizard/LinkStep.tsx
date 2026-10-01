/**
 * NT-6 stap 4 — de koppeling (`POST /setup/links`): verbindt de beschrijving met één leverancier en één
 * Prodis-bibliotheek.
 *
 * - Leverancier: standaard de organisatie uit stap 1 als dat een leverancier is. Bij een aankoopvereniging is
 *   er bewust **geen** standaard: de gebruiker kiest of maakt de leverancier waarvoor de vereniging levert.
 * - Doelbibliotheek: wordt niet tegen Prodis gecontroleerd (aanname A5).
 * - Leverancierscode in de bibliotheek: nooit automatisch ingevuld.
 * - Standaardvaluta: leeg = euro; nooit in hoofdletters gezet of op een andere manier aangepast.
 */
import { useState, type FormEvent } from 'react';
import { usePermissionGate } from '../../../actor/permissions.ts';
import * as setupCreateApi from '../../../api/setupCreate.ts';
import { PERMISSION_MANAGE, type ImportLinkRow } from '../../../api/types.ts';
import { Field } from '../../../components/Field.tsx';
import { ErrorBanner } from '../../../errors/ErrorBanner.tsx';
import { useQuery } from '../../../hooks/useQuery.ts';
import { term } from '../../../terms/index.ts';
import { findLinkByCode, loadAllOrganisations } from './lookup.ts';
import { OrganisationCreateSection } from './OrganisationStep.tsx';
import { ExistingChoice, StepError } from './StepFeedback.tsx';
import { fieldMessage, fieldOfError, optional, toApiError, useStepSubmit } from './stepSubmit.ts';
import { organisationLabel, toWizardLink } from './wizardMappers.ts';
import type { WizardDefinition, WizardLink, WizardOrganisation } from './wizardTypes.ts';
import styles from './Wizard.module.css';

const MAX_CODE = 50;
const MAX_NAME = 200;
const MAX_LIBRARY_CODE = 20;
const MAX_SEARCH_SUPPLIER_CODE = 50;
/** Dezelfde vormregel als de server (`LINK_CURRENCY_SHAPE`): exact drie hoofdletters. */
const CURRENCY_SHAPE = /^[A-Z]{3}$/;

type FormState = {
  code: string;
  name: string;
  supplierCode: string;
  libraryCode: string;
  librarySearchSupplierCode: string;
  defaultCurrency: string;
};
type FormKey = keyof FormState;
type Errors = Partial<Record<FormKey, string>>;

export type LinkStepProps = {
  organisation: WizardOrganisation;
  definition: WizardDefinition;
  onLink: (link: WizardLink, notice: string | null) => void;
};

export function LinkStep({ organisation, definition, onLink }: LinkStepProps) {
  const manageGate = usePermissionGate(PERMISSION_MANAGE);
  const submit = useStepSubmit();
  const organisations = useQuery('wizard-link-organisations', (signal) => loadAllOrganisations(signal));
  const [extraSuppliers, setExtraSuppliers] = useState<WizardOrganisation[]>([]);
  const [creatingSupplier, setCreatingSupplier] = useState(false);
  const [form, setForm] = useState<FormState>({
    code: '',
    name: '',
    supplierCode: organisation.type === 'SUPPLIER' ? organisation.code : '',
    libraryCode: '',
    librarySearchSupplierCode: '',
    defaultCurrency: '',
  });
  const [errors, setErrors] = useState<Errors>({});
  const [candidate, setCandidate] = useState<ImportLinkRow | null>(null);

  const serverField = fieldOfError(
    submit.error,
    {
      CODE: 'code',
      NAME: 'name',
      LIBRARY_CODE: 'libraryCode',
      SOURCE_ORGANISATION_CODE: 'supplierCode',
      LIBRARY_SEARCH_SUPPLIER_CODE: 'librarySearchSupplierCode',
    },
    { LINK_CODE_IN_USE: 'code', LINK_CURRENCY_INVALID: 'defaultCurrency' },
  ) as FormKey | null;

  function fieldError(name: FormKey): string | null {
    if (errors[name] !== undefined) {
      return errors[name] ?? null;
    }
    return serverField === name && submit.error !== null ? fieldMessage(submit.error) : null;
  }

  function set(name: FormKey, value: string) {
    setForm((previous) => {
      const next: FormState = { ...previous };
      next[name] = value;
      return next;
    });
    setErrors((previous) => {
      const next: Errors = { ...previous };
      delete next[name];
      return next;
    });
    setCandidate(null);
    submit.clearError();
  }

  // De organisatie uit stap 1 staat altijd in de lijst, ook als de lijst (nog) niet geladen is.
  const supplierOptions = [organisation, ...(organisations.data ?? []), ...extraSuppliers].filter(
    (row, index, all) => all.findIndex((other) => other.code === row.code) === index,
  );

  function validate(): Errors {
    const problems: Errors = {};
    const code = form.code.trim();
    const name = form.name.trim();
    const libraryCode = form.libraryCode.trim();
    const searchCode = form.librarySearchSupplierCode.trim();
    const currency = form.defaultCurrency.trim();
    if (code === '') problems.code = 'Vul een code in.';
    else if (code.length > MAX_CODE) problems.code = `Een code heeft hoogstens ${MAX_CODE} tekens.`;
    if (name === '') problems.name = 'Vul een naam in.';
    else if (name.length > MAX_NAME) problems.name = `Een naam heeft hoogstens ${MAX_NAME} tekens.`;
    if (form.supplierCode === '') problems.supplierCode = 'Kies de leverancier van deze koppeling.';
    if (libraryCode === '') problems.libraryCode = 'Vul de doelbibliotheek in.';
    else if (libraryCode.length > MAX_LIBRARY_CODE) {
      problems.libraryCode = `De code van een bibliotheek heeft hoogstens ${MAX_LIBRARY_CODE} tekens.`;
    }
    if (searchCode.length > MAX_SEARCH_SUPPLIER_CODE) {
      problems.librarySearchSupplierCode = `Hoogstens ${MAX_SEARCH_SUPPLIER_CODE} tekens.`;
    }
    // Nooit zelf in hoofdletters zetten: een afwijkende vorm is een fout, geen stille correctie.
    if (currency !== '' && !CURRENCY_SHAPE.test(currency)) {
      problems.defaultCurrency = 'Een valuta is precies drie hoofdletters, bijvoorbeeld EUR. Leeg betekent euro.';
    }
    return problems;
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!manageGate.allowed || submit.pending) {
      return;
    }
    const problems = validate();
    setErrors(problems);
    if (Object.keys(problems).length > 0) {
      return;
    }
    setCandidate(null);
    const code = form.code.trim();
    const recheck = submit.recheckFirst;
    await submit.run(async () => {
      if (recheck) {
        const existing = await findLinkByCode(definition.id, code);
        if (existing !== null) {
          setCandidate(existing);
          return;
        }
      }
      try {
        const created = await setupCreateApi.createLink({
          definitionId: definition.id,
          code,
          name: form.name.trim(),
          supplierCode: form.supplierCode,
          libraryCode: form.libraryCode.trim(),
          librarySearchSupplierCode: optional(form.librarySearchSupplierCode),
          // Leeg = euro (de server bewaart dan niets); een ingevulde waarde gaat ongewijzigd mee.
          defaultCurrency: optional(form.defaultCurrency),
        });
        onLink(toWizardLink(created), null);
      } catch (cause) {
        const error = toApiError(cause);
        if (error.status === 409 && error.code === 'LINK_CODE_IN_USE') {
          // Enkel een koppeling met deze code onder déze beschrijving is een geldige voortzetting.
          setCandidate(await findLinkByCode(definition.id, code).catch(() => null));
        }
        throw error;
      }
    });
  }

  const setupTerm = (name: string) => term('setupField', name);

  return (
    <form className={styles.form} onSubmit={handleSubmit} aria-labelledby="wizard-step-4-title" noValidate>
      <h2 id="wizard-step-4-title" className={styles.stepTitle}>
        Stap 4 — {setupTerm('link').label}
      </h2>
      <p className={styles.note}>{setupTerm('link').uitleg}</p>

      <Field
        label={setupTerm('code').label}
        htmlFor="wizard-link-code"
        required
        help={setupTerm('code').uitleg}
        error={fieldError('code')}
      >
        <input
          id="wizard-link-code"
          className={styles.input}
          type="text"
          value={form.code}
          maxLength={MAX_CODE}
          onChange={(event) => set('code', event.target.value)}
        />
      </Field>
      <Field label="Naam" htmlFor="wizard-link-name" required error={fieldError('name')}>
        <input
          id="wizard-link-name"
          className={styles.input}
          type="text"
          value={form.name}
          maxLength={MAX_NAME}
          onChange={(event) => set('name', event.target.value)}
        />
      </Field>

      <Field
        label={setupTerm('supplierCode').label}
        htmlFor="wizard-link-supplier"
        required
        help={setupTerm('supplierCode').uitleg}
        hint={
          organisation.type === 'PURCHASING_ASSOCIATION'
            ? 'Een aankoopvereniging levert namens leveranciers: kies of maak de leverancier van deze koppeling.'
            : undefined
        }
        error={fieldError('supplierCode')}
      >
        <select
          id="wizard-link-supplier"
          className={styles.select}
          value={form.supplierCode}
          onChange={(event) => set('supplierCode', event.target.value)}
        >
          <option value="">— kies —</option>
          {supplierOptions.map((row) => (
            <option key={row.code} value={row.code}>
              {organisationLabel(row)}
            </option>
          ))}
        </select>
      </Field>
      {organisations.error !== null && <ErrorBanner error={organisations.error} />}
      {!creatingSupplier ? (
        <p className={styles.note}>
          <button type="button" className={styles.secondary} onClick={() => setCreatingSupplier(true)}>
            Nieuwe leverancier aanmaken
          </button>
        </p>
      ) : (
        <fieldset className={styles.fieldset}>
          <legend className={styles.legend}>Nieuwe leverancier</legend>
          <OrganisationCreateSection
            idPrefix="wizard-supplier"
            fixedType="SUPPLIER"
            submitLabel="Leverancier aanmaken"
            onDone={(created) => {
              setExtraSuppliers((previous) => [...previous, created]);
              set('supplierCode', created.code);
              setCreatingSupplier(false);
            }}
          />
        </fieldset>
      )}

      <Field
        label={setupTerm('libraryCode').label}
        htmlFor="wizard-link-library"
        required
        help={setupTerm('libraryCode').uitleg}
        hint="Wordt niet bij Prodis nagekeken: controleer de code zelf."
        error={fieldError('libraryCode')}
      >
        <input
          id="wizard-link-library"
          className={styles.input}
          type="text"
          value={form.libraryCode}
          maxLength={MAX_LIBRARY_CODE}
          onChange={(event) => set('libraryCode', event.target.value)}
        />
      </Field>
      <Field
        label={setupTerm('librarySearchSupplierCode').label}
        htmlFor="wizard-link-search-supplier"
        help={setupTerm('librarySearchSupplierCode').uitleg}
        hint="Optioneel."
        error={fieldError('librarySearchSupplierCode')}
      >
        <input
          id="wizard-link-search-supplier"
          className={styles.input}
          type="text"
          value={form.librarySearchSupplierCode}
          maxLength={MAX_SEARCH_SUPPLIER_CODE}
          onChange={(event) => set('librarySearchSupplierCode', event.target.value)}
        />
      </Field>
      <Field
        label={setupTerm('defaultCurrency').label}
        htmlFor="wizard-link-currency"
        help={setupTerm('defaultCurrency').uitleg}
        hint="Optioneel; drie hoofdletters, bijvoorbeeld EUR."
        error={fieldError('defaultCurrency')}
      >
        <input
          id="wizard-link-currency"
          className={styles.input}
          type="text"
          value={form.defaultCurrency}
          maxLength={3}
          onChange={(event) => set('defaultCurrency', event.target.value)}
        />
      </Field>

      {candidate !== null && (
        <ExistingChoice
          disabled={submit.pending}
          onContinue={() => {
            setCandidate(null);
            onLink(toWizardLink(candidate), `U gaat verder met de bestaande koppeling ${candidate.code}.`);
          }}
        >
          Deze beschrijving heeft al een koppeling met code <strong>{candidate.code}</strong>: {candidate.name}{' '}
          (leverancier {candidate.supplierCode}, bibliotheek {candidate.libraryCode}). Kiest u deze, dan wordt er
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
          {submit.pending ? 'Bezig…' : 'Opslaan en verder'}
        </button>
      </div>
    </form>
  );
}
