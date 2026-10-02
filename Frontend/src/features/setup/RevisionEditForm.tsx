/**
 * S1-F4 — het bewerkformulier voor de scalaire velden van een DRAFT-revisie (endpoint E3,
 * `PATCH /setup/revisions/{revisionId}`; `docs/design/revision-successor-design.md` §5, §6).
 *
 * <h2>"null = ongewijzigd" is hier de hele opzet</h2>
 * Het formulier wordt gevuld uit het geladen revisiedetail (E1) en verstuurt **alleen de velden die
 * werkelijk afwijken** van die geladen waarde. Elk ander veld gaat als `null` mee, wat op de server
 * "ongewijzigd" betekent. Zo kan dit scherm nooit een veld meenemen dat de gebruiker niet aanraakte —
 * belangrijk, want drempels en prijsbeleid zijn financieel bepalend.
 *
 * Drie gevolgen die zichtbaar gemaakt zijn in plaats van weggeabstraheerd:
 * - **Leegmaken kan alleen waar de server het aanvaardt.** Voor de optionele tekstvelden
 *   (`quoteChar`, `discountCodeField`, `descriptionField`, `currencyField`) verstuurt een leeggemaakt
 *   veld een uitdrukkelijk lege tekst en wist de waarde. Voor een verplicht veld is leeg een
 *   cliëntvalidatiefout: het wordt nooit als `''` verstuurd (dat zou een 400 worden).
 * - **`expectedColumnCount` en `maxRejectedSharePercent` zijn langs dit pad niet op "niet ingesteld"
 *   terug te zetten** (bewuste beperking van "null = ongewijzigd", zie `UpdateRevisionCommand`). Een
 *   leeggemaakt veld betekent daar dus "ongewijzigd", met die tekst als hint bij het veld.
 * - **Nooit stil op 0.** Een onleesbaar getal is een cliëntvalidatiefout, geen 0 en geen lege waarde.
 *
 * <h2>R-REV-X3 — de identiteitswijziging moet bevestigd worden</h2>
 * Wijzigt `identityProfileKind` of een van de vier identiteitsvelden, dan verschijnt een expliciete
 * bevestiging (`acknowledgeIdentityChange`) die **bewust geen standaardwaarde** heeft — zelfde patroon
 * als de `mode`-sentinel van `MaterialiseForm` (`MATERIALISATION_MODE_REQUIRED`). Zonder vinkje wordt er
 * niets verstuurd; de server weigert zo'n verzoek toch met 409 `IDENTITY_CHANGE_NOT_ACKNOWLEDGED`.
 *
 * <h2>R-REV-X2 — de canonicalisatieversie is onvoorwaardelijk geblokkeerd</h2>
 * Bij `REVISION_CANONICALISATION_CHANGE_BLOCKED` wordt **geen** bevestigingsoptie aangeboden: er bestaat
 * geen veld dat die blokkade opheft, en er één tonen zou de gebruiker een uitweg suggereren die de
 * server niet aanvaardt. Zolang die fout staat, is ook de identiteitsbevestiging verborgen — het verzoek
 * zou opnieuw op R-REV-X2 stuklopen (die controle gaat vóór R-REV-X3). Elke aanpassing in het formulier
 * wist de foutmelding, zodat er nooit een doodlopende toestand ontstaat.
 *
 * Er is **geen** actie voor bookmarkdeclaraties: harde ontwerpgrens §2 (geen enkel endpoint om ze toe te
 * voegen, te wijzigen of te verwijderen), zodat een opvolgrevisie nooit een bestaande
 * LINK-bookmarkwaarde tot wees maakt.
 */

import { useState, type FormEvent, type ReactNode } from 'react';
import * as setupRevisionsApi from '../../api/setupRevisions.ts';
import {
  IDENTITY_PROFILE_KINDS,
  PERMISSION_MANAGE,
  ROW_ISSUE_SEVERITIES,
  type IdentityProfileKind,
  type RevisionDetail,
  type RevisionView,
  type RowIssueSeverity,
  type UpdateRevisionRequest,
} from '../../api/types.ts';
import { useActor } from '../../actor/ActorContext.tsx';
import { usePermissionGate, withPermission } from '../../actor/permissions.ts';
import { Field } from '../../components/Field.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useAction } from '../../hooks/useAction.ts';
import { gateTitle } from '../../actor/gate.ts';
import { term, termLabel } from '../../terms/index.ts';
import { WhatIsThis } from '../../terms/WhatIsThis.tsx';
import { FlagOffNotice } from './FlagOffNotice.tsx';
import { editGate } from './revisionPolicy.ts';
import {
  buildRequest,
  diffNumber,
  fieldWord,
  hasChanges,
  identityChanged,
  initialForm,
  type FlagKey,
  type FormState,
  type TextKey,
} from './revisionEditRequest.ts';
import { isSetupApiDisabledError, SETUP_WRITE_API_DISABLED_MESSAGE } from './setupWriteFlag.ts';
import button from '../../components/Button.module.css';
import styles from './RevisionEditForm.module.css';

const CANONICALISATION_CODE = 'REVISION_CANONICALISATION_CHANGE_BLOCKED';

/** De twee manieren om kolommen te herkennen (`FieldReferenceKind`); de waarde is de code die de server verwacht. */
const FIELD_REFERENCE_KINDS: readonly string[] = ['HEADER_NAME', 'COLUMN_INDEX'];

/** De hint bij een veld dat langs dit pad niet meer op "niet ingesteld" gezet kan worden. */
const NOT_CLEARABLE_HINT =
  'Leeg laten betekent hier "ongewijzigd": dit veld kan langs dit pad niet terug op "niet ingesteld" ' +
  'gezet worden.';

const CLEARABLE_HINT = 'Leegmaken wist de waarde (een uitdrukkelijk lege waarde wordt verstuurd).';

export type RevisionEditFormProps = {
  revision: RevisionDetail;
  /** Na een geslaagde wijziging: de ouder herlaadt het revisiedetail (expliciete invalidatie). */
  onUpdated: (result: RevisionView) => void;
};

export function RevisionEditForm({ revision, onUpdated }: RevisionEditFormProps) {
  const { actor } = useActor();
  const manageGate = usePermissionGate(PERMISSION_MANAGE);
  const gate = withPermission(manageGate, editGate(revision.status));

  const [form, setForm] = useState<FormState>(() => initialForm(revision));
  const [acknowledgeIdentityChange, setAcknowledgeIdentityChange] = useState(false);
  const [validationError, setValidationError] = useState<string | null>(null);
  const [saved, setSaved] = useState<string | null>(null);
  const runner = useAction((body: UpdateRevisionRequest) =>
    setupRevisionsApi.updateRevision(revision.id, body),
  );

  /** Elke aanpassing wist de vorige uitkomst én de vorige serverfout: nooit een stale blokkade. */
  function touched() {
    setValidationError(null);
    setSaved(null);
    runner.reset();
  }

  function setText(name: TextKey, value: string) {
    setForm((previous) => {
      const next: FormState = { ...previous };
      next[name] = value;
      return next;
    });
    touched();
  }

  function setFlag(name: FlagKey, value: boolean) {
    setForm((previous) => {
      const next: FormState = { ...previous };
      next[name] = value;
      return next;
    });
    touched();
  }

  // R-REV-X3: dezelfde vergelijking als de server (zie `identityChanged`).
  const identityChangedNow = identityChanged(form, revision);

  // R-REV-X2: elke wijziging (ook een verlaging) heeft hetzelfde gevolg en is niet te bevestigen.
  const canonicalisationDiff = diffNumber(form.canonicalisationVersion, revision.recordCanonicalisationVersion);
  const canonicalisationChanged = !canonicalisationDiff.invalid && canonicalisationDiff.value !== null;
  const canonicalisationBlockedByServer = runner.error?.code === CANONICALISATION_CODE;

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!gate.allowed) {
      return;
    }
    setSaved(null);
    const built = buildRequest(form, revision, acknowledgeIdentityChange, actor);
    if ('problem' in built) {
      setValidationError(built.problem);
      return;
    }
    if (!hasChanges(built.body)) {
      setValidationError('Er is niets gewijzigd; er is niets verstuurd.');
      return;
    }
    setValidationError(null);
    const result = await runner.execute(built.body);
    if (result === undefined) {
      // De fout (bv. 409 REVISION_CANONICALISATION_CHANGE_BLOCKED) blijft staan bij het formulier, met
      // de ingevulde waarden erin: de gebruiker ziet welke wijziging niet bewaard is.
      return;
    }
    setSaved(`Versie ${result.revisionNumber} is bijgewerkt (status: ${term('revisionStatus', result.status).label}).`);
    setAcknowledgeIdentityChange(false);
    onUpdated(result);
  }

  function textRow(
    name: TextKey,
    options: { required?: boolean; hint?: string; maxLength: number },
  ): ReactNode {
    const id = `revision-${name}`;
    const word = term('revisionField', name);
    return (
      <Field label={word.label} htmlFor={id} required={options.required} hint={options.hint} help={word.uitleg}>
        <input
          id={id}
          className={styles.input}
          type="text"
          value={form[name]}
          maxLength={options.maxLength}
          onChange={(event) => setText(name, event.target.value)}
        />
      </Field>
    );
  }

  function numberRow(name: TextKey, hint: string): ReactNode {
    const id = `revision-${name}`;
    const word = term('revisionField', name);
    return (
      <Field label={word.label} htmlFor={id} hint={hint} help={word.uitleg}>
        <input
          id={id}
          className={styles.input}
          type="text"
          inputMode="decimal"
          value={form[name]}
          onChange={(event) => setText(name, event.target.value)}
        />
      </Field>
    );
  }

  function checkRow(name: FlagKey, label?: string): ReactNode {
    const id = `revision-${name}`;
    const word = term('revisionField', name);
    return (
      <div className={styles.checkboxRow}>
        <input
          id={id}
          type="checkbox"
          checked={form[name]}
          onChange={(event) => setFlag(name, event.target.checked)}
        />{' '}
        <label htmlFor={id}>{label ?? word.label}</label>
        <WhatIsThis>{word.uitleg}</WhatIsThis>
      </div>
    );
  }

  return (
    <form className={styles.form} onSubmit={handleSubmit} data-testid="revision-edit-form">
      <h3 className={styles.title}>Concept aanpassen</h3>
      <p className={styles.intro}>
        Alleen de velden die u werkelijk wijzigt, worden verstuurd; al de rest blijft ongewijzigd. Een concept
        mag tussentijds onvolledig zijn: de volledige controle van de instellingen gebeurt pas bij het
        activeren.
      </p>

      <fieldset className={styles.fieldset}>
        <legend className={styles.legend}>Herkenning van een artikel</legend>
        <p className={styles.identityNote}>
          Elk veld in dit blok bepaalt hoe een artikel in een volgende levering herkend wordt. Een wijziging
          hier laat élk bestaand artikel als nieuw artikel terugkomen en vraagt daarom een uitdrukkelijke
          bevestiging onderaan.
        </p>
        <Field
          label={fieldWord('identityProfileKind')}
          htmlFor="revision-identityProfileKind"
          required
          hint="Bij de herkenning zonder kortingscode blijft de kolom voor de kortingscode leeg; met kortingscode moet die kolom ingesteld zijn."
          help={term('revisionField', 'identityProfileKind').uitleg}
        >
          <select
            id="revision-identityProfileKind"
            className={styles.select}
            value={form.identityProfileKind}
            onChange={(event) => setText('identityProfileKind', event.target.value)}
          >
            {IDENTITY_PROFILE_KINDS.map((kind) => (
              <option key={kind} value={kind}>
                {term('identityProfile', kind).label}
              </option>
            ))}
            {/* Een onbekende (toekomstige) waarde uit de backend wordt getoond, niet weggelaten. */}
            {!IDENTITY_PROFILE_KINDS.includes(form.identityProfileKind as IdentityProfileKind) && (
              <option value={form.identityProfileKind}>
                {termLabel('identityProfile', form.identityProfileKind, 'Andere herkenning')}
              </option>
            )}
          </select>
        </Field>
        {textRow('supplierField', { required: true, maxLength: 200 })}
        {textRow('supplierGroupField', { required: true, maxLength: 200 })}
        {textRow('supplierReferenceField', { required: true, maxLength: 200 })}
        {textRow('discountCodeField', { hint: CLEARABLE_HINT, maxLength: 200 })}
      </fieldset>

      <fieldset className={styles.fieldset}>
        <legend className={styles.legend}>Herkenningsversie</legend>
        {numberRow(
          'canonicalisationVersion',
          'Wijzigen wordt onvoorwaardelijk geweigerd zodra er voor deze beschrijving van het bestand al een ' +
            'aanvaarde stand van de artikelen bestaat (bijvoorbeeld door een nulmeting) — ook een verlaging. ' +
            'Er is geen bevestiging die dat opheft.',
        )}
        {canonicalisationChanged && !canonicalisationBlockedByServer && (
          <p className={styles.warning} role="note" data-testid="canonicalisation-change-warning">
            U wijzigt de herkenningsversie van {revision.recordCanonicalisationVersion} naar{' '}
            {form.canonicalisationVersion.trim()}. Bestaat er voor deze beschrijving van het bestand al een
            aanvaarde stand van de artikelen, dan weigert de server dit onvoorwaardelijk en wordt er niets
            opgeslagen — ook niet de andere velden in dit formulier.
          </p>
        )}
        {canonicalisationBlockedByServer && (
          <p className={styles.blocked} role="alert" data-testid="canonicalisation-blocked">
            De server heeft deze wijziging geweigerd omdat er al een aanvaarde stand van de artikelen bestaat.
            Deze weigering is niet te omzeilen: er is geen bevestiging en geen optie die ze opheft. Zet de
            herkenningsversie terug op {revision.recordCanonicalisationVersion} om de overige wijzigingen wel
            te kunnen bewaren.
          </p>
        )}
      </fieldset>

      <fieldset className={styles.fieldset}>
        <legend className={styles.legend}>Opbouw van het bestand</legend>
        {textRow('delimiter', { required: true, maxLength: 1 })}
        {textRow('quoteChar', { hint: CLEARABLE_HINT, maxLength: 1 })}
        {textRow('charset', { required: true, maxLength: 40 })}
        {checkRow('hasHeader', 'Het bestand heeft een kopregel')}
        {numberRow('headerLineNumber', 'Geheel getal.')}
        <Field
          label={fieldWord('fieldReferenceKind')}
          htmlFor="revision-fieldReferenceKind"
          required
          help={term('revisionField', 'fieldReferenceKind').uitleg}
        >
          <select
            id="revision-fieldReferenceKind"
            className={styles.select}
            value={form.fieldReferenceKind}
            onChange={(event) => setText('fieldReferenceKind', event.target.value)}
          >
            {FIELD_REFERENCE_KINDS.map((kind) => (
              <option key={kind} value={kind}>
                {term('fieldReferenceKind', kind).label}
              </option>
            ))}
            {/* Een onbekende (toekomstige) waarde uit de backend wordt getoond, niet weggelaten. */}
            {!FIELD_REFERENCE_KINDS.includes(form.fieldReferenceKind) && (
              <option value={form.fieldReferenceKind}>
                {termLabel('fieldReferenceKind', form.fieldReferenceKind, 'Andere herkenning van kolommen')}
              </option>
            )}
          </select>
        </Field>
        {numberRow('expectedColumnCount', NOT_CLEARABLE_HINT)}
      </fieldset>

      <fieldset className={styles.fieldset}>
        <legend className={styles.legend}>Kolommen en prijsbeleid</legend>
        {textRow('basePriceField', { required: true, maxLength: 200 })}
        {textRow('descriptionField', { hint: CLEARABLE_HINT, maxLength: 200 })}
        {textRow('currencyField', { hint: CLEARABLE_HINT, maxLength: 200 })}
        {checkRow('basePriceZeroAllowed')}
        {checkRow('basePriceNegativeAllowed')}
        {numberRow('priceDeviationPercent', 'Percentage, niet negatief.')}
        <Field
          label={fieldWord('priceDeviationSeverity')}
          htmlFor="revision-priceDeviationSeverity"
          help={term('revisionField', 'priceDeviationSeverity').uitleg}
        >
          <select
            id="revision-priceDeviationSeverity"
            className={styles.select}
            value={form.priceDeviationSeverity}
            onChange={(event) => setText('priceDeviationSeverity', event.target.value)}
          >
            {ROW_ISSUE_SEVERITIES.map((severity) => (
              <option key={severity} value={severity}>
                {term('severity', severity).label}
              </option>
            ))}
            {!ROW_ISSUE_SEVERITIES.includes(form.priceDeviationSeverity as RowIssueSeverity) && (
              <option value={form.priceDeviationSeverity}>
                {termLabel('severity', form.priceDeviationSeverity, 'Andere ernst')}
              </option>
            )}
          </select>
        </Field>
        {numberRow('priceDerivationTolerance', 'Bedrag; wordt letterlijk doorgegeven, de toepassing rekent er nooit zelf mee.')}
      </fieldset>

      <fieldset className={styles.fieldset}>
        <legend className={styles.legend}>Drempels</legend>
        <p className={styles.identityNote}>
          Een wijziging aan de drempels of het prijsbeleid verandert alleen het gedrag bij een levering (wat
          tegengehouden of gemeld wordt); ze raakt de herkomstgegevens van de versie niet.
        </p>
        {numberRow('creationThresholdSharePercent', 'Percentage, niet negatief.')}
        {numberRow('maxCriticalSharePercent', 'Percentage, niet negatief.')}
        {numberRow('maxRejectedSharePercent', NOT_CLEARABLE_HINT)}
        {numberRow('bulkIncidentSharePercent', 'Percentage, niet negatief.')}
      </fieldset>

      <fieldset className={styles.fieldset}>
        <legend className={styles.legend}>Wijzigingsreden</legend>
        {textRow('changeReason', { hint: CLEARABLE_HINT, maxLength: 500 })}
      </fieldset>

      {identityChangedNow && !canonicalisationBlockedByServer && (
        <div className={styles.acknowledge} data-testid="identity-change-acknowledge">
          <p className={styles.warning} role="note">
            Deze wijziging raakt hoe een artikel herkend wordt. De herkenning verandert daardoor, waardoor
            élk bestaand artikel van deze beschrijving van het bestand als nieuw artikel terugkomt (een
            massale aanmaak). Deze keuze heeft bewust geen standaardwaarde.
          </p>
          <div className={styles.checkboxRow}>
            <input
              id="revision-acknowledgeIdentityChange"
              type="checkbox"
              checked={acknowledgeIdentityChange}
              onChange={(event) => {
                setAcknowledgeIdentityChange(event.target.checked);
                setValidationError(null);
              }}
            />{' '}
            <label htmlFor="revision-acknowledgeIdentityChange">
              Ja, ik weet dat elk bestaand artikel hierna als nieuw artikel terugkomt
            </label>
          </div>
        </div>
      )}

      <p className={styles.actorRow}>
        Uitgevoerd door: <strong>{actor}</strong>. Let op: er wordt niet apart bewaard wie dit concept heeft
        aangepast (enkel wie het aanmaakte en wie het activeert).
      </p>

      {validationError !== null && (
        <p className={styles.validationError} role="alert" data-testid="revision-edit-validation">
          {validationError}
        </p>
      )}
      {runner.error !== null &&
        (isSetupApiDisabledError(runner.error) ? (
          <FlagOffNotice message={SETUP_WRITE_API_DISABLED_MESSAGE} className={styles.flagOff} />
        ) : (
          <ErrorBanner error={runner.error} />
        ))}
      {saved !== null && (
        <p className={styles.saved} role="status" data-testid="revision-edit-saved">
          {saved}
        </p>
      )}

      {!gate.allowed && (
        <p className={styles.validationError} id="revision-edit-blocked" data-testid="revision-edit-blocked">
          {gate.reason}
        </p>
      )}
      <button
        type="submit"
        className={button.primary}
        disabled={runner.pending || !gate.allowed}
        title={gateTitle(gate)}
        aria-describedby={gate.allowed ? undefined : 'revision-edit-blocked'}
      >
        {runner.pending ? 'Bezig…' : 'Wijzigingen opslaan'}
      </button>
    </form>
  );
}
