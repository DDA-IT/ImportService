/**
 * NT-6 stap 3 — de beschrijving van het bestand ("zelf beschrijven", A1: altijd een eigen definitie).
 *
 * Twee verzoeken na elkaar: eerst de beschrijving zelf (`POST /setup/definitions`, gebruik "eigen
 * definitie"), dan versie 1 als concept (`POST /setup/definitions/{id}/revisions`). Lukt de eerste en faalt de
 * tweede, dan blijft de beschrijving bestaan en verstuurt een nieuwe poging enkel nog de versie.
 *
 * Regels die hier bewaakt worden (dezelfde als bij het activeren in `SourceStructureConfigFactory`, zodat de
 * gebruiker ze meteen ziet in plaats van pas bij het activeren):
 * - scheidingsteken: verplicht en uitdrukkelijk gekozen (geen standaardwaarde, zoals in de databank);
 * - aanhalingsteken verschilt van het scheidingsteken;
 * - zonder kopregel kunnen kolommen enkel op positie herkend worden, en een positie is een geheel getal ≥ 1;
 * - de herkenning van een artikel heeft **bewust geen standaardwaarde** (ze is na de eerste aanvaarde levering
 *   niet meer zonder gevolgen te wijzigen), en de kortingscodekolom hoort enkel bij de vierdelige herkenning.
 *
 * Drempels worden hier niet ingevuld: ze gaan niet mee in het verzoek, dus de server zet zijn eigen
 * standaardwaarden. Het stappenplan toont die standaard ter info en de slotstap de werkelijk bewaarde waarden.
 * Herkenningsversie staat onder "Geavanceerd" op 2 (aanname A3).
 */
import { useState, type FormEvent, type ReactNode } from 'react';
import { useActor } from '../../../actor/ActorContext.tsx';
import { usePermissionGate } from '../../../actor/permissions.ts';
import * as setupCreateApi from '../../../api/setupCreate.ts';
import {
  IDENTITY_PROFILE_KINDS,
  PERMISSION_MANAGE,
  type CreateRevisionRequest,
  type DefinitionRow,
  type IdentityProfileKind,
  type RevisionRow,
} from '../../../api/types.ts';
import { Field } from '../../../components/Field.tsx';
import { Term } from '../../../terms/Term.tsx';
import { term } from '../../../terms/index.ts';
import { findDefinitionByCode, findLatestRevision, loadRevisionsOf, latestRevision } from './lookup.ts';
import { ExistingChoice, StepError } from './StepFeedback.tsx';
import { fieldMessage, fieldOfError, optional, toApiError, useStepSubmit } from './stepSubmit.ts';
import { toWizardRevision } from './wizardMappers.ts';
import type { WizardDefinition, WizardOrganisation, WizardRevision } from './wizardTypes.ts';
import button from '../../../components/Button.module.css';
import styles from './Wizard.module.css';

const MAX_CODE = 50;
const MAX_NAME = 200;
const MAX_FIELD_REFERENCE = 200;
const MAX_CHARSET = 40;
const OTHER = 'OTHER';
const NO_QUOTE = 'NONE';

/** Herkenningsversie standaard 2 (aanname A3, `docs/decisions.md` 2026-09-30). */
export const DEFAULT_CANONICALISATION_VERSION = '2';

/**
 * Tab staat hier bewust niet tussen: de server trimt het scheidingsteken (`SetupService.requireText`) en
 * leest een tab daardoor als "leeg" (400). Zie de ontdekking in het rapport van NT-6.
 */
const DELIMITER_OPTIONS: ReadonlyArray<{ value: string; label: string }> = [
  { value: ';', label: '; (puntkomma)' },
  { value: ',', label: ', (komma)' },
  { value: '|', label: '| (verticale streep)' },
  { value: OTHER, label: 'Ander teken…' },
];

const QUOTE_OPTIONS: ReadonlyArray<{ value: string; label: string }> = [
  { value: '"', label: '" (dubbel aanhalingsteken)' },
  { value: "'", label: "' (enkel aanhalingsteken)" },
  { value: NO_QUOTE, label: 'Geen aanhalingsteken' },
];

const CHARSET_OPTIONS: ReadonlyArray<{ value: string; label: string }> = [
  { value: 'UTF-8', label: 'UTF-8 (meest gebruikt)' },
  { value: 'windows-1252', label: 'Windows (westers)' },
  { value: 'ISO-8859-1', label: 'Latin-1' },
  { value: 'ISO-8859-15', label: 'Latin-9 (met euroteken)' },
  { value: OTHER, label: 'Andere…' },
];

/**
 * De standaarddrempels van een nieuwe versie, ter info (bron: `ImportDefinitionRevision`, beslissingslog
 * 20/09 "drempels zijn altijd een percentage"). Ze worden **niet** verstuurd; de slotstap toont de werkelijk
 * bewaarde waarden uit de server.
 */
const DEFAULT_THRESHOLDS: ReadonlyArray<{ key: string; value: string }> = [
  { key: 'creationThresholdSharePercent', value: '1 %' },
  { key: 'maxCriticalSharePercent', value: '1 %' },
  { key: 'maxRejectedSharePercent', value: 'niet ingesteld' },
  { key: 'bulkIncidentSharePercent', value: '1 %' },
];

type ColumnKey =
  | 'supplierField'
  | 'supplierGroupField'
  | 'supplierReferenceField'
  | 'discountCodeField'
  | 'basePriceField'
  | 'descriptionField'
  | 'currencyField';

type FormState = {
  definitionCode: string;
  definitionName: string;
  delimiterChoice: string;
  delimiterOther: string;
  quoteChoice: string;
  charsetChoice: string;
  charsetOther: string;
  hasHeader: boolean;
  headerLineNumber: string;
  fieldReferenceKind: 'HEADER_NAME' | 'COLUMN_INDEX';
  identityProfileKind: IdentityProfileKind | '';
  canonicalisationVersion: string;
} & Record<ColumnKey, string>;

type FormKey = keyof FormState;
/** De velden met vrije tekst (geen keuzelijst met vaste waarden en geen vinkje). */
type TextKey = { [K in FormKey]: string extends FormState[K] ? K : never }[FormKey];
type Errors = Partial<Record<FormKey, string>>;

const INITIAL: FormState = {
  definitionCode: '',
  definitionName: '',
  delimiterChoice: '',
  delimiterOther: '',
  quoteChoice: '"',
  charsetChoice: 'UTF-8',
  charsetOther: '',
  hasHeader: true,
  headerLineNumber: '1',
  fieldReferenceKind: 'HEADER_NAME',
  identityProfileKind: '',
  canonicalisationVersion: DEFAULT_CANONICALISATION_VERSION,
  supplierField: '',
  supplierGroupField: '',
  supplierReferenceField: '',
  discountCodeField: '',
  basePriceField: '',
  descriptionField: '',
  currencyField: '',
};

/** Serverveld (voorvoegsel van de NT-3-code) → formulierveld. */
const DEFINITION_PREFIXES = { CODE: 'definitionCode', NAME: 'definitionName' } as const;
const REVISION_PREFIXES = {
  DELIMITER: 'delimiterChoice',
  QUOTE_CHAR: 'quoteChoice',
  CHARSET: 'charsetChoice',
  FIELD_REFERENCE_KIND: 'fieldReferenceKind',
  SUPPLIER_FIELD: 'supplierField',
  SUPPLIER_GROUP_FIELD: 'supplierGroupField',
  SUPPLIER_REFERENCE_FIELD: 'supplierReferenceField',
  DISCOUNT_CODE_FIELD: 'discountCodeField',
  BASE_PRICE_FIELD: 'basePriceField',
  DESCRIPTION_FIELD: 'descriptionField',
  CURRENCY_FIELD: 'currencyField',
} as const;

type Candidate =
  | { kind: 'definition'; row: DefinitionRow }
  | { kind: 'revision'; row: RevisionRow };

export type DescriptionStepProps = {
  organisation: WizardOrganisation;
  /** Al bewaard (vorige poging of hervatten): dan verstuurt deze stap enkel nog de versie. */
  definition: WizardDefinition | null;
  onDefinition: (definition: WizardDefinition, notice: string | null) => void;
  onRevision: (revision: WizardRevision, notice: string | null) => void;
};

function isColumnIndex(value: string): boolean {
  return /^\d+$/.test(value) && Number(value) >= 1;
}

export function DescriptionStep({ organisation, definition, onDefinition, onRevision }: DescriptionStepProps) {
  const { actor } = useActor();
  const manageGate = usePermissionGate(PERMISSION_MANAGE);
  const submit = useStepSubmit();
  const [form, setForm] = useState<FormState>(INITIAL);
  const [errors, setErrors] = useState<Errors>({});
  const [candidate, setCandidate] = useState<Candidate | null>(null);

  const fourPart = form.identityProfileKind === 'FOUR_PART_WITH_DISCOUNT_CODE';
  const byPosition = form.fieldReferenceKind === 'COLUMN_INDEX';

  // Een veldfout van de server hoort bij het verzoek dat faalde: zonder bewaarde beschrijving is dat de
  // beschrijving, anders de versie.
  const serverField = fieldOfError(
    submit.error,
    definition === null ? DEFINITION_PREFIXES : REVISION_PREFIXES,
    { DEFINITION_CODE_IN_USE: 'definitionCode' },
  );
  const visibleServerField =
    serverField === 'discountCodeField' && !fourPart ? null : (serverField as FormKey | null);

  function fieldError(name: FormKey): string | null {
    if (errors[name] !== undefined) {
      return errors[name] ?? null;
    }
    return visibleServerField === name && submit.error !== null ? fieldMessage(submit.error) : null;
  }

  function set<K extends FormKey>(name: K, value: FormState[K]) {
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

  function delimiterValue(): string {
    return form.delimiterChoice === OTHER ? form.delimiterOther : form.delimiterChoice;
  }

  function charsetValue(): string {
    return (form.charsetChoice === OTHER ? form.charsetOther : form.charsetChoice).trim();
  }

  function quoteValue(): string {
    return form.quoteChoice === NO_QUOTE ? '' : form.quoteChoice;
  }

  function validate(): Errors {
    const problems: Errors = {};
    if (definition === null) {
      const code = form.definitionCode.trim();
      const name = form.definitionName.trim();
      if (code === '') problems.definitionCode = 'Vul een code in.';
      else if (code.length > MAX_CODE) problems.definitionCode = `Een code heeft hoogstens ${MAX_CODE} tekens.`;
      if (name === '') problems.definitionName = 'Vul een naam in.';
      else if (name.length > MAX_NAME) problems.definitionName = `Een naam heeft hoogstens ${MAX_NAME} tekens.`;
    }

    const delimiter = delimiterValue();
    if (form.delimiterChoice === '') {
      problems.delimiterChoice = 'Kies het teken tussen de kolommen.';
    } else if (delimiter.trim().length !== 1 || delimiter.length !== 1) {
      problems.delimiterChoice = 'Een scheidingsteken is precies één zichtbaar teken.';
    }
    const quote = quoteValue();
    if (quote !== '' && quote === delimiter) {
      problems.quoteChoice = 'Het aanhalingsteken moet verschillen van het scheidingsteken.';
    }
    const charset = charsetValue();
    if (charset === '') problems.charsetChoice = 'Kies een tekenset.';
    else if (charset.length > MAX_CHARSET) problems.charsetChoice = `Hoogstens ${MAX_CHARSET} tekens.`;

    if (form.hasHeader) {
      const line = form.headerLineNumber.trim();
      if (!/^\d+$/.test(line) || Number(line) < 1) {
        problems.headerLineNumber = 'Vul een geheel getal van 1 of meer in. Er wordt niets op 1 gezet.';
      }
    } else if (form.fieldReferenceKind === 'HEADER_NAME') {
      problems.fieldReferenceKind = 'Zonder kopregel kunnen kolommen enkel op positie herkend worden.';
    }

    if (form.identityProfileKind === '') {
      problems.identityProfileKind = 'Kies hoe een artikel herkend wordt; deze keuze heeft bewust geen standaard.';
    }

    const required: ColumnKey[] = ['supplierField', 'supplierGroupField', 'supplierReferenceField', 'basePriceField'];
    if (fourPart) required.push('discountCodeField');
    const optionalColumns: ColumnKey[] = ['descriptionField', 'currencyField'];
    for (const key of [...required, ...optionalColumns]) {
      const value = form[key].trim();
      if (value === '') {
        if (required.includes(key)) problems[key] = 'Deze kolom is verplicht.';
        continue;
      }
      if (value.length > MAX_FIELD_REFERENCE) {
        problems[key] = `Hoogstens ${MAX_FIELD_REFERENCE} tekens.`;
      } else if (byPosition && !isColumnIndex(value)) {
        problems[key] = 'Vul het volgnummer van de kolom in (1 = de eerste kolom).';
      }
    }

    if (!/^[12]$/.test(form.canonicalisationVersion)) {
      problems.canonicalisationVersion = 'Kies versie 1 of 2.';
    }
    return problems;
  }

  function revisionBody(): CreateRevisionRequest {
    return {
      delimiter: delimiterValue(),
      quoteChar: quoteValue(),
      charset: charsetValue(),
      hasHeader: form.hasHeader,
      // Zonder kopregel gaat er geen regelnummer mee: de server laat dan zijn eigen waarde staan.
      headerLineNumber: form.hasHeader ? Number(form.headerLineNumber.trim()) : null,
      fieldReferenceKind: form.fieldReferenceKind,
      expectedColumnCount: null,
      identityProfileKind: form.identityProfileKind as IdentityProfileKind,
      supplierField: form.supplierField.trim(),
      supplierGroupField: form.supplierGroupField.trim(),
      supplierReferenceField: form.supplierReferenceField.trim(),
      // Driedelig = de kortingscode is niet gemapt (`null`), nooit een lege tekst (§14.23.3).
      discountCodeField: fourPart ? form.discountCodeField.trim() : null,
      basePriceField: form.basePriceField.trim(),
      descriptionField: optional(form.descriptionField),
      currencyField: optional(form.currencyField),
      canonicalisationVersion: Number(form.canonicalisationVersion),
      changeReason: null,
      createdBy: actor,
    };
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
    let recheck = submit.recheckFirst;
    const body = revisionBody();
    await submit.run(async () => {
      let current = definition;
      if (current === null) {
        const code = form.definitionCode.trim();
        if (recheck) {
          recheck = false;
          const existing = await findDefinitionByCode(organisation.id, code);
          if (existing !== null) {
            setCandidate({ kind: 'definition', row: existing });
            return;
          }
        }
        try {
          const created = await setupCreateApi.createDefinition({
            sourceOrganisationCode: organisation.code,
            code,
            name: form.definitionName.trim(),
            usageType: 'OWN_DEFINITION',
          });
          current = { id: created.id, code: created.code, name: created.name };
          onDefinition(current, null);
        } catch (cause) {
          const error = toApiError(cause);
          if (error.status === 409 && error.code === 'DEFINITION_CODE_IN_USE') {
            const existing = await findDefinitionByCode(organisation.id, code).catch(() => null);
            setCandidate(existing === null ? null : { kind: 'definition', row: existing });
          }
          throw error;
        }
      }

      if (recheck) {
        // De vorige versie-aanvraag kreeg geen antwoord. Een tweede aanvraag zou een tweede conceptversie
        // maken (de server weigert dat sinds NT-13 met 409, maar zo vermijden we de nutteloze fout): eerst
        // kijken of de eerste toch aankwam.
        const existing = await findLatestRevision(current.id);
        if (existing !== null) {
          setCandidate({ kind: 'revision', row: existing });
          return;
        }
      }
      let revision: Awaited<ReturnType<typeof setupCreateApi.createRevision>>;
      try {
        revision = await setupCreateApi.createRevision(current.id, body);
      } catch (cause) {
        const error = toApiError(cause);
        if (error.status === 409 && error.code === 'REVISION_DRAFT_ALREADY_EXISTS') {
          // NT-13: er staat al een concept open op deze beschrijving. Bied het uitdrukkelijk aan om verder te gaan
          // met de bestaande versie (zelfde keuzeblok als bij een bestaande code); de foutmelding blijft staan.
          const existing = await findLatestRevision(current.id).catch(() => null);
          if (existing !== null) {
            setCandidate({ kind: 'revision', row: existing });
          }
        }
        throw error;
      }
      onRevision(toWizardRevision(revision), null);
    });
  }

  /** Uitdrukkelijk gekozen: verder met de bestaande beschrijving (en haar laatste versie, als die er is). */
  async function adoptDefinition(row: DefinitionRow) {
    if (row.usageType !== 'OWN_DEFINITION') {
      // Het stappenplan maakt enkel eigen definities (A1): een sjabloon is geen geldige voortzetting.
      return;
    }
    await submit.run(async () => {
      const adopted = { id: row.id, code: row.code, name: row.name };
      const latest = latestRevision(await loadRevisionsOf(row.id));
      setCandidate(null);
      if (latest === null) {
        onDefinition(
          adopted,
          `U gaat verder met de bestaande beschrijving ${row.code}. Ze heeft nog geen versie: controleer de ` +
            'velden hieronder en klik opnieuw op "Opslaan en verder".',
        );
        return;
      }
      onDefinition(adopted, null);
      onRevision(
        toWizardRevision(latest),
        `U gaat verder met de bestaande beschrijving ${row.code} en haar versie ${latest.revisionNumber}. ` +
          'De velden die u hier invulde, zijn niet verstuurd; bekijk de bestaande versie bij Inrichting.',
      );
    });
  }

  function adoptRevision(row: RevisionRow) {
    setCandidate(null);
    onRevision(toWizardRevision(row), `U gaat verder met de bestaande versie ${row.revisionNumber}.`);
  }

  function textField(
    name: TextKey,
    label: string,
    options: { required?: boolean; help?: ReactNode; hint?: string; maxLength: number },
  ): ReactNode {
    const id = `wizard-${name}`;
    return (
      <Field
        label={label}
        htmlFor={id}
        required={options.required}
        hint={options.hint}
        help={options.help}
        error={fieldError(name)}
      >
        <input
          id={id}
          className={styles.input}
          type="text"
          value={form[name]}
          maxLength={options.maxLength}
          onChange={(event) => set(name, event.target.value)}
        />
      </Field>
    );
  }

  function columnField(name: ColumnKey, required: boolean): ReactNode {
    const entry = term('revisionField', name);
    return textField(name, entry.label, {
      required,
      help: entry.uitleg,
      hint: byPosition ? 'Het volgnummer van de kolom (1 = de eerste kolom).' : 'De kolomnaam zoals in de kopregel.',
      maxLength: MAX_FIELD_REFERENCE,
    });
  }

  const revisionTerm = (name: string) => term('revisionField', name);

  return (
    <form className={styles.form} onSubmit={handleSubmit} aria-labelledby="wizard-step-3-title" noValidate>
      <h2 id="wizard-step-3-title" className={styles.stepTitle}>
        Stap 3 — {term('setupField', 'definition').label}
      </h2>
      <p className={styles.note}>
        {term('setupField', 'definition').uitleg} Er wordt een eerste {term('setupField', 'revision').label.toLowerCase()}{' '}
        aangemaakt als <Term domain="revisionStatus" code="DRAFT" />: ze wordt pas gebruikt nadat u ze controleert en
        activeert.
      </p>

      <fieldset className={styles.fieldset}>
        <legend className={styles.legend}>{term('setupField', 'definition').label}</legend>
        {definition === null ? (
          <>
            {textField('definitionCode', term('setupField', 'code').label, {
              required: true,
              help: term('setupField', 'code').uitleg,
              maxLength: MAX_CODE,
            })}
            {textField('definitionName', 'Naam', { required: true, maxLength: MAX_NAME })}
          </>
        ) : (
          <p className={styles.note} data-testid="wizard-definition-known">
            Al bewaard: <strong>{definition.code}</strong> — {definition.name}. Hieronder beschrijft u versie 1.
          </p>
        )}
      </fieldset>

      <fieldset className={styles.fieldset}>
        <legend className={styles.legend}>Het bestand</legend>
        <Field
          label={revisionTerm('delimiter').label}
          htmlFor="wizard-delimiterChoice"
          required
          help={revisionTerm('delimiter').uitleg}
          error={fieldError('delimiterChoice')}
        >
          <select
            id="wizard-delimiterChoice"
            className={styles.select}
            value={form.delimiterChoice}
            onChange={(event) => set('delimiterChoice', event.target.value)}
          >
            <option value="">— kies —</option>
            {DELIMITER_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </Field>
        {form.delimiterChoice === OTHER &&
          textField('delimiterOther', 'Welk teken?', { required: true, maxLength: 1 })}

        <Field
          label={revisionTerm('quoteChar').label}
          htmlFor="wizard-quoteChoice"
          help={revisionTerm('quoteChar').uitleg}
          error={fieldError('quoteChoice')}
        >
          <select
            id="wizard-quoteChoice"
            className={styles.select}
            value={form.quoteChoice}
            onChange={(event) => set('quoteChoice', event.target.value)}
          >
            {QUOTE_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </Field>

        <Field
          label={revisionTerm('charset').label}
          htmlFor="wizard-charsetChoice"
          required
          help={revisionTerm('charset').uitleg}
          error={fieldError('charsetChoice')}
        >
          <select
            id="wizard-charsetChoice"
            className={styles.select}
            value={form.charsetChoice}
            onChange={(event) => set('charsetChoice', event.target.value)}
          >
            {CHARSET_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </Field>
        {form.charsetChoice === OTHER &&
          textField('charsetOther', 'Welke tekenset?', { required: true, maxLength: MAX_CHARSET })}

        <label className={styles.checkboxRow}>
          <input
            type="checkbox"
            checked={form.hasHeader}
            onChange={(event) => set('hasHeader', event.target.checked)}
          />{' '}
          Het bestand heeft een {revisionTerm('hasHeader').label.toLowerCase()}
        </label>
        <p className={styles.note}>{revisionTerm('hasHeader').uitleg}</p>
        {form.hasHeader && (
          <Field
            label={revisionTerm('headerLineNumber').label}
            htmlFor="wizard-headerLineNumber"
            required
            help={revisionTerm('headerLineNumber').uitleg}
            error={fieldError('headerLineNumber')}
          >
            <input
              id="wizard-headerLineNumber"
              className={styles.input}
              type="text"
              inputMode="numeric"
              value={form.headerLineNumber}
              onChange={(event) => set('headerLineNumber', event.target.value)}
            />
          </Field>
        )}

        <fieldset className={styles.fieldset}>
          <legend className={styles.legend}>{revisionTerm('fieldReferenceKind').label} *</legend>
          <p className={styles.note}>{revisionTerm('fieldReferenceKind').uitleg}</p>
          {(['HEADER_NAME', 'COLUMN_INDEX'] as const).map((kind) => (
            <label key={kind} className={styles.radioRow}>
              <input
                type="radio"
                name="wizard-fieldReferenceKind"
                value={kind}
                checked={form.fieldReferenceKind === kind}
                onChange={() => set('fieldReferenceKind', kind)}
              />{' '}
              <Term domain="fieldReferenceKind" code={kind} /> — {term('fieldReferenceKind', kind).uitleg}
            </label>
          ))}
          {fieldError('fieldReferenceKind') !== null && (
            <p className={styles.fieldNote} role="alert">
              {fieldError('fieldReferenceKind')}
            </p>
          )}
        </fieldset>
      </fieldset>

      <fieldset className={styles.fieldset}>
        <legend className={styles.legend}>{revisionTerm('identityProfileKind').label} *</legend>
        <p className={styles.note}>{revisionTerm('identityProfileKind').uitleg}</p>
        {IDENTITY_PROFILE_KINDS.map((kind) => (
          <label key={kind} className={styles.radioRow}>
            <input
              type="radio"
              name="wizard-identityProfileKind"
              value={kind}
              checked={form.identityProfileKind === kind}
              onChange={() => set('identityProfileKind', kind)}
            />{' '}
            <Term domain="identityProfile" code={kind} />
          </label>
        ))}
        {fieldError('identityProfileKind') !== null && (
          <p className={styles.fieldNote} role="alert">
            {fieldError('identityProfileKind')}
          </p>
        )}
        {columnField('supplierField', true)}
        {columnField('supplierGroupField', true)}
        {columnField('supplierReferenceField', true)}
        {fourPart && columnField('discountCodeField', true)}
      </fieldset>

      <fieldset className={styles.fieldset}>
        <legend className={styles.legend}>Prijs en omschrijving</legend>
        {columnField('basePriceField', true)}
        {columnField('descriptionField', false)}
        {columnField('currencyField', false)}
      </fieldset>

      <fieldset className={styles.fieldset}>
        <legend className={styles.legend}>Drempels (standaardwaarden)</legend>
        <p className={styles.note}>
          Deze standaardwaarden worden niet door dit stappenplan gewijzigd. Na het aanmaken ziet u de bewaarde
          waarden; aanpassen kan in de conceptversie bij Inrichting.
        </p>
        <dl className={styles.defaults} data-testid="wizard-default-thresholds">
          {DEFAULT_THRESHOLDS.map((threshold) => (
            <div key={threshold.key}>
              <dt>{revisionTerm(threshold.key).label}</dt>
              <dd>
                {threshold.value} — {revisionTerm(threshold.key).uitleg}
              </dd>
            </div>
          ))}
        </dl>
      </fieldset>

      <details className={styles.advanced}>
        <summary>Geavanceerd</summary>
        <Field
          label={revisionTerm('canonicalisationVersion').label}
          htmlFor="wizard-canonicalisationVersion"
          help={revisionTerm('canonicalisationVersion').uitleg}
          hint="Standaard 2. Laat dit staan tenzij u weet waarom een andere versie nodig is."
          error={fieldError('canonicalisationVersion')}
        >
          <select
            id="wizard-canonicalisationVersion"
            className={styles.select}
            value={form.canonicalisationVersion}
            onChange={(event) => set('canonicalisationVersion', event.target.value)}
          >
            <option value="2">2</option>
            <option value="1">1</option>
          </select>
        </Field>
      </details>

      {candidate?.kind === 'definition' &&
        (candidate.row.usageType === 'OWN_DEFINITION' ? (
          <ExistingChoice disabled={submit.pending} onContinue={() => void adoptDefinition(candidate.row)}>
            Deze leverancier heeft al een beschrijving met code <strong>{candidate.row.code}</strong>:{' '}
            {candidate.row.name}. Kiest u deze, dan wordt er geen nieuwe beschrijving aangemaakt.
          </ExistingChoice>
        ) : (
          <p className={styles.validationError} role="alert">
            De bestaande beschrijving met code {candidate.row.code} is een sjabloon en kan hier niet gebruikt
            worden. Kies een andere code.
          </p>
        ))}
      {candidate?.kind === 'revision' && (
        <ExistingChoice disabled={submit.pending} onContinue={() => adoptRevision(candidate.row)}>
          Deze beschrijving heeft al versie <strong>{candidate.row.revisionNumber}</strong> (
          <Term domain="revisionStatus" code={candidate.row.status} />
          ). Kiest u deze, dan wordt er geen tweede versie aangemaakt.
        </ExistingChoice>
      )}
      <StepError error={submit.error} fieldName={visibleServerField} />

      <div className={styles.actions}>
        <button
          type="submit"
          className={button.primary}
          disabled={submit.pending || !manageGate.allowed}
          title={manageGate.allowed ? undefined : manageGate.reason}
        >
          {submit.pending ? 'Bezig…' : 'Opslaan en verder'}
        </button>
      </div>
    </form>
  );
}
