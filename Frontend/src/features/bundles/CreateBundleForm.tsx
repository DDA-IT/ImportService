/**
 * Bundel aanmaken (`POST /bundles`), zie
 * `docs/design/frontend-scherm3-bundel-design.md` §10.1.
 *
 * - `bundleReference` verplicht, `targetMode` verplicht zonder voorselectie (de backend heeft bewust
 *   geen default; deze UI mag dat niet ongedaan maken door een keuze voor te selecteren).
 * - `targetMode = PRODUCTION` krijgt een expliciete waarschuwing (beslissing van de mens, §17 Q3): een
 *   latere publicatiefase behandelt zo'n bundel als echte publicatie naar ProDisWebbase.
 * - De backend is idempotent op `bundleReference`: een herhaalde aanroep met dezelfde referentie (en
 *   scope) geeft de bestaande bundel terug in plaats van een fout. De UI meldt dat expliciet in plaats
 *   van stil te doen alsof er iets nieuws gemaakt is (zie de toelichting bij `alreadyExisted` hieronder).
 * - Een andere scope bij dezelfde referentie geeft 409 `BUNDLE_REFERENCE_REUSED_WITH_DIFFERENT_SCOPE`,
 *   die via `ErrorBanner`/`describe()` al een Nederlandse uitleg krijgt (`errors/codes.ts`).
 */

import { useState, type FormEvent } from 'react';
import * as bundlesApi from '../../api/bundles.ts';
import type { PublicationTargetMode } from '../../api/types.ts';
import { PUBLICATION_TARGET_MODES } from '../../api/types.ts';
import type { BundleReference } from '../../api/types.ts';
import { useActor, validateActorName } from '../../actor/ActorContext.tsx';
import { useAction } from '../../hooks/useAction.ts';
import { Field } from '../../components/Field.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import styles from './CreateBundleForm.module.css';

export type CreateBundleFormProps = {
  /** Aangeroepen na een geslaagde `POST /bundles` (ook bij de idempotente hervinding van een bestaande
   * bundel), zodat de ouder de bundellijst herlaadt (§5: expliciete invalidatie, geen magie). */
  onCreated: () => void;
};

const NO_TARGET_MODE = '';
type TargetModeSelection = PublicationTargetMode | typeof NO_TARGET_MODE;

/**
 * De backend geeft bij `POST /bundles` géén expliciete vlag mee die zegt of de bundel nieuw is of al
 * bestond — het antwoord is in beide gevallen dezelfde `BundleReference`. Bij benadering: als de
 * teruggegeven `createdAt` duidelijk vóór het moment van dit verzoek ligt (met een ruime marge voor
 * netwerklatentie/klokverschil), bestond de bundel al.
 *
 * Important technical constraint discovered: `PublicationBundleService.createBundle` retourneert bij
 * een idempotente hervinding exact dezelfde vorm (`BundleReference`) als bij een nieuwe aanmaak, zonder
 * een "is nieuw"-signaal. Een additief responsveld (bv. `alreadyExisted: boolean`) zou deze benadering
 * overbodig maken. Niet aangepast in deze bouwstap — zie het rapport.
 */
const ALREADY_EXISTED_MARGIN_MS = 5000;

function isAlreadyExisted(requestedAt: number, bundle: BundleReference): boolean {
  const createdAtMs = Date.parse(bundle.createdAt);
  if (Number.isNaN(createdAtMs)) {
    return false;
  }
  return requestedAt - createdAtMs > ALREADY_EXISTED_MARGIN_MS;
}

export function CreateBundleForm({ onCreated }: CreateBundleFormProps) {
  const { actor } = useActor();
  const [bundleReference, setBundleReference] = useState('');
  const [description, setDescription] = useState('');
  const [targetMode, setTargetMode] = useState<TargetModeSelection>(NO_TARGET_MODE);
  const [targetMoment, setTargetMoment] = useState('');
  const [publicationPolicy, setPublicationPolicy] = useState('');
  const [validationError, setValidationError] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<{ bundle: BundleReference; alreadyExisted: boolean } | null>(null);

  const { execute, pending, error, reset } = useAction(() =>
    bundlesApi.createBundle({
      bundleReference: bundleReference.trim(),
      description: description.trim() === '' ? null : description.trim(),
      targetMode: targetMode as PublicationTargetMode,
      targetMoment: targetMoment === '' ? null : new Date(targetMoment).toISOString(),
      publicationPolicy: publicationPolicy.trim() === '' ? null : publicationPolicy.trim(),
      createdBy: actor,
    }),
  );

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setOutcome(null);
    reset();

    const actorError = validateActorName(actor);
    if (actorError !== null) {
      setValidationError(`Aangemaakt door: ${actorError}`);
      return;
    }
    if (bundleReference.trim() === '') {
      setValidationError('Vul een bundelreferentie in.');
      return;
    }
    if (targetMode === NO_TARGET_MODE) {
      setValidationError('Kies een doelmodus.');
      return;
    }
    setValidationError(null);

    const requestedAt = Date.now();
    const bundle = await execute();
    if (bundle === undefined) {
      return;
    }

    const alreadyExisted = isAlreadyExisted(requestedAt, bundle);
    setOutcome({ bundle, alreadyExisted });
    if (!alreadyExisted) {
      setBundleReference('');
      setDescription('');
      setTargetMode(NO_TARGET_MODE);
      setTargetMoment('');
      setPublicationPolicy('');
    }
    onCreated();
  }

  return (
    <form className={styles.form} onSubmit={handleSubmit}>
      <h2 className={styles.title}>Nieuwe bundel</h2>

      <Field label="Bundelreferentie" htmlFor="create-bundle-reference" required>
        <input
          id="create-bundle-reference"
          className={styles.input}
          type="text"
          value={bundleReference}
          onChange={(event) => setBundleReference(event.target.value)}
          maxLength={200}
        />
      </Field>

      <Field label="Omschrijving" htmlFor="create-bundle-description">
        <input
          id="create-bundle-description"
          className={styles.input}
          type="text"
          value={description}
          onChange={(event) => setDescription(event.target.value)}
        />
      </Field>

      <Field
        label="Doelmodus"
        htmlFor="create-bundle-target-mode"
        required
        hint="Geen automatische keuze: er is bewust geen voorselectie."
      >
        <select
          id="create-bundle-target-mode"
          className={styles.select}
          value={targetMode}
          onChange={(event) => setTargetMode(event.target.value as TargetModeSelection)}
        >
          <option value={NO_TARGET_MODE}>— kies een doelmodus —</option>
          {PUBLICATION_TARGET_MODES.map((mode) => (
            <option key={mode} value={mode}>
              {mode}
            </option>
          ))}
        </select>
      </Field>

      {targetMode === 'PRODUCTION' && (
        <p className={styles.warning} role="alert">
          Let op: deze bundel wordt door een latere publicatiefase als echte publicatie naar
          ProDisWebbase behandeld.
        </p>
      )}

      <Field label="Doelmoment" htmlFor="create-bundle-target-moment" hint="Optioneel.">
        <input
          id="create-bundle-target-moment"
          className={styles.input}
          type="datetime-local"
          value={targetMoment}
          onChange={(event) => setTargetMoment(event.target.value)}
        />
      </Field>

      <Field label="Publicatiebeleid" htmlFor="create-bundle-policy" hint="Optioneel.">
        <input
          id="create-bundle-policy"
          className={styles.input}
          type="text"
          value={publicationPolicy}
          onChange={(event) => setPublicationPolicy(event.target.value)}
        />
      </Field>

      <p className={styles.actorRow}>
        Aangemaakt door: <strong>{actor === '' ? '(nog niet ingevuld)' : actor}</strong> — wijzig dit
        hierboven bij "Ingelogd als".
      </p>

      {validationError !== null && (
        <p className={styles.validationError} role="alert">
          {validationError}
        </p>
      )}
      {error !== null && <ErrorBanner error={error} />}
      {outcome !== null && outcome.alreadyExisted && (
        <p className={`${styles.notice} ${styles.reused}`} role="status">
          Deze bundelreferentie bestond al: <strong>{outcome.bundle.bundleReference}</strong>{' '}
          (aangemaakt door {outcome.bundle.createdBy} op{' '}
          {new Date(outcome.bundle.createdAt).toLocaleString('nl-BE')}). Er is geen nieuwe bundel
          aangemaakt.
        </p>
      )}
      {outcome !== null && !outcome.alreadyExisted && (
        <p className={styles.notice} role="status">
          Bundel <strong>{outcome.bundle.bundleReference}</strong> aangemaakt.
        </p>
      )}

      <button type="submit" className={styles.submit} disabled={pending}>
        {pending ? 'Bezig…' : 'Bundel aanmaken'}
      </button>
    </form>
  );
}
