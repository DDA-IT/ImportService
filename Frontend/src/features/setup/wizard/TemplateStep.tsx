/**
 * NT-7 stap 2, weg "vanuit een sjabloon" (`docs/decisions.md` 2026-09-30, NT-spoor V3 = a, V4 = a).
 *
 * - Enkel de sjablonen van **dezelfde organisatie** als stap 1 worden getoond (V4 = a: een sjabloon bedient
 *   enkel de organisatie die het zelf aanlevert; de gematerialiseerde beschrijving blijft van die organisatie).
 *   Is er geen, dan legt het scherm dat uit en wijst het naar "Zelf beschrijven".
 * - Het formulier is het bestaande `MaterialiseForm` (ook gebruikt op de pagina Sjablonen). Dat maakt de
 *   beschrijving, een conceptversie en de koppeling in één keer, en **nooit een taak** (V3 = a): de taak volgt in
 *   de volgende stap van het stappenplan.
 * - De leverancier van de koppeling is bij een leverancier die van stap 1 (voorinvulling, nog aanpasbaar). Bij een
 *   aankoopvereniging kiest of maakt de gebruiker eerst de leverancier, net als in de koppelingsstap.
 * - Niets wordt stil gekozen: een sjabloonversie, de leverancier en de modus van het formulier kiest de gebruiker
 *   zelf (de actieve sjabloonversie is enkel voorgeselecteerd).
 */
import { useState } from 'react';
import * as setupApi from '../../../api/setup.ts';
import * as templatesApi from '../../../api/templates.ts';
import type { MaterialisationView, RevisionRow, TemplateView } from '../../../api/types.ts';
import { Field } from '../../../components/Field.tsx';
import { ErrorBanner } from '../../../errors/ErrorBanner.tsx';
import { useQuery } from '../../../hooks/useQuery.ts';
import { term } from '../../../terms/index.ts';
import { MaterialiseForm } from '../../templates/MaterialiseForm.tsx';
import { loadAllOrganisations, loadTemplatesOf, LOOKUP_PAGE_SIZE } from './lookup.ts';
import { OrganisationCreateSection } from './OrganisationStep.tsx';
import { organisationLabel } from './wizardMappers.ts';
import type { WizardOrganisation } from './wizardTypes.ts';
import button from '../../../components/Button.module.css';
import styles from './Wizard.module.css';

export type TemplateStepProps = {
  organisation: WizardOrganisation;
  /** Terug naar de keuze "zelf beschrijven of sjabloon". */
  onBack: () => void;
  /** De gebruiker kiest toch "Zelf beschrijven" (bv. omdat er geen sjabloon is). */
  onOwnDescription: () => void;
  onMaterialised: (result: MaterialisationView) => void;
};

/** Bij een aankoopvereniging: de leverancier van de koppeling kiezen of aanmaken (zoals in de koppelingsstap). */
function SupplierChoice({ value, onChange }: { value: string; onChange: (supplierCode: string) => void }) {
  const organisations = useQuery('wizard-template-suppliers', (signal) => loadAllOrganisations(signal));
  const [extra, setExtra] = useState<WizardOrganisation[]>([]);
  const [creating, setCreating] = useState(false);
  const suppliers = [...(organisations.data ?? []), ...extra].filter(
    (row, index, all) => row.type === 'SUPPLIER' && all.findIndex((other) => other.code === row.code) === index,
  );
  const supplierTerm = term('setupField', 'supplierCode');

  return (
    <div data-testid="wizard-template-supplier">
      <Field
        label={supplierTerm.label}
        htmlFor="wizard-template-supplier-select"
        required
        help={supplierTerm.uitleg}
        hint="Een aankoopvereniging levert namens leveranciers: kies of maak de leverancier van deze koppeling."
      >
        <select
          id="wizard-template-supplier-select"
          className={styles.select}
          value={value}
          onChange={(event) => onChange(event.target.value)}
        >
          <option value="">— kies —</option>
          {suppliers.map((row) => (
            <option key={row.code} value={row.code}>
              {organisationLabel(row)}
            </option>
          ))}
        </select>
      </Field>
      {organisations.error !== null && <ErrorBanner error={organisations.error} />}
      {!creating ? (
        <p className={styles.note}>
          <button type="button" className={button.secondary} onClick={() => setCreating(true)}>
            Nieuwe leverancier aanmaken
          </button>
        </p>
      ) : (
        <fieldset className={styles.fieldset}>
          <legend className={styles.legend}>Nieuwe leverancier</legend>
          <OrganisationCreateSection
            idPrefix="wizard-template-new-supplier"
            fixedType="SUPPLIER"
            submitLabel="Leverancier aanmaken"
            onDone={(created) => {
              setExtra((previous) => [...previous, created]);
              onChange(created.code);
              setCreating(false);
            }}
          />
        </fieldset>
      )}
    </div>
  );
}

/** Laadt de invulpunten (bladwijzers) en de bestaande beschrijvingen uit één sjabloonversie en toont het formulier. */
function RevisionMaterialise({
  template,
  revision,
  supplierCode,
  onMaterialised,
}: {
  template: TemplateView;
  revision: RevisionRow;
  supplierCode: string;
  onMaterialised: (result: MaterialisationView) => void;
}) {
  const bookmarkSet = useQuery(`wizard-template-bookmarks:${template.id}:${revision.id}`, (signal) =>
    templatesApi.getBookmarkSet(template.id, revision.id, signal),
  );
  const materialisations = useQuery(`wizard-template-materialisations:${template.id}`, (signal) =>
    templatesApi.listMaterialisations(template.id, { page: 0, size: LOOKUP_PAGE_SIZE }, signal),
  );

  if (bookmarkSet.error !== null) {
    return <ErrorBanner error={bookmarkSet.error} />;
  }
  if (materialisations.error !== null) {
    return <ErrorBanner error={materialisations.error} />;
  }
  if (bookmarkSet.data === null || materialisations.data === null) {
    return <p className={styles.loading}>Bezig met laden…</p>;
  }
  return (
    <MaterialiseForm
      key={`${revision.id}:${supplierCode}`}
      definitionId={template.id}
      templateRevisionId={revision.id}
      templateRevisionNumber={revision.revisionNumber}
      templateRevisionStatus={revision.status}
      bookmarks={bookmarkSet.data.bookmarks}
      materialisations={materialisations.data.content}
      onMaterialised={onMaterialised}
      defaultSupplierCode={supplierCode}
    />
  );
}

/** De gekozen sjabloon: versie kiezen, (bij een aankoopvereniging) de leverancier kiezen, dan het formulier. */
function TemplateMaterialise({
  template,
  organisation,
  onMaterialised,
}: {
  template: TemplateView;
  organisation: WizardOrganisation;
  onMaterialised: (result: MaterialisationView) => void;
}) {
  const revisions = useQuery(`wizard-template-revisions:${template.id}`, (signal) =>
    setupApi.listDefinitionRevisions(template.id, { page: 0, size: LOOKUP_PAGE_SIZE }, signal),
  );
  const [chosenRevisionId, setChosenRevisionId] = useState('');
  const [chosenSupplierCode, setChosenSupplierCode] = useState('');

  if (revisions.error !== null) {
    return <ErrorBanner error={revisions.error} />;
  }
  if (revisions.data === null) {
    return <p className={styles.loading}>Bezig met laden…</p>;
  }
  // Een concept van het sjabloon zelf is nog niet doorlopen en kan niet gebruikt worden.
  const usable = revisions.data.content.filter((row) => row.status !== 'DRAFT');
  if (usable.length === 0) {
    return (
      <p className={styles.note} role="status" data-testid="wizard-template-no-revision">
        Dit sjabloon heeft nog geen actieve versie en kan daarom nog niet gebruikt worden.
      </p>
    );
  }
  const active = usable.find((row) => row.status === 'ACTIVE');
  const effectiveId = chosenRevisionId !== '' ? chosenRevisionId : active !== undefined ? String(active.id) : '';
  const revision = usable.find((row) => String(row.id) === effectiveId) ?? null;

  const isAssociation = organisation.type === 'PURCHASING_ASSOCIATION';
  const supplierCode = isAssociation ? chosenSupplierCode : organisation.code;

  return (
    <div data-testid="wizard-template-materialise">
      <Field
        label="Versie van het sjabloon"
        htmlFor="wizard-template-revision"
        required
        hint="Standaard de actieve versie. Een oudere (vervangen) versie kan, maar kies die bewust."
      >
        <select
          id="wizard-template-revision"
          className={styles.select}
          value={effectiveId}
          onChange={(event) => setChosenRevisionId(event.target.value)}
        >
          {active === undefined && <option value="">— kies —</option>}
          {usable.map((row) => (
            <option key={row.id} value={String(row.id)}>
              Versie {row.revisionNumber} — {term('revisionStatus', row.status).label}
            </option>
          ))}
        </select>
      </Field>

      {isAssociation && <SupplierChoice value={chosenSupplierCode} onChange={setChosenSupplierCode} />}

      {revision !== null && (!isAssociation || supplierCode !== '') && (
        <RevisionMaterialise
          template={template}
          revision={revision}
          supplierCode={supplierCode}
          onMaterialised={onMaterialised}
        />
      )}
    </div>
  );
}

export function TemplateStep({ organisation, onBack, onOwnDescription, onMaterialised }: TemplateStepProps) {
  const templates = useQuery(`wizard-templates:${organisation.id}`, (signal) =>
    loadTemplatesOf(organisation.id, signal),
  );
  const [templateId, setTemplateId] = useState('');

  const rows = templates.data ?? [];
  const template = rows.find((row) => String(row.id) === templateId) ?? null;

  return (
    <section className={styles.form} aria-labelledby="wizard-step-2-title" data-testid="wizard-template-step">
      <h2 id="wizard-step-2-title" className={styles.stepTitle}>
        Stap 2 — Startpunt: vanuit een sjabloon
      </h2>
      <p className={styles.note}>
        Een sjabloon is een herbruikbare beschrijving van een bestand. Hieruit maakt u in één keer de beschrijving van
        het bestand (als conceptversie) en de koppeling. Een taak volgt in de volgende stap. Er worden enkel sjablonen
        van {organisation.name} getoond: een sjabloon kan alleen gebruikt worden voor de organisatie die het zelf
        aanlevert.
      </p>

      {templates.error !== null && <ErrorBanner error={templates.error} />}
      {templates.loading && templates.data === null && <p className={styles.loading}>Bezig met laden…</p>}

      {templates.data !== null && rows.length === 0 && (
        <p className={styles.note} role="status" data-testid="wizard-no-templates">
          Er is geen sjabloon voor {organisation.code} — {organisation.name}. Een sjabloon van een andere organisatie
          kan hier niet gebruikt worden. Kies &laquo;Zelf beschrijven&raquo; om het bestand zelf te beschrijven.
        </p>
      )}

      {rows.length > 0 && (
        <Field label="Sjabloon" htmlFor="wizard-template-select" required>
          <select
            id="wizard-template-select"
            className={styles.select}
            value={templateId}
            onChange={(event) => setTemplateId(event.target.value)}
          >
            <option value="">— kies —</option>
            {rows.map((row) => (
              <option key={row.id} value={String(row.id)}>
                {row.code} — {row.name}
              </option>
            ))}
          </select>
        </Field>
      )}

      {template !== null && (
        <TemplateMaterialise
          key={template.id}
          template={template}
          organisation={organisation}
          onMaterialised={onMaterialised}
        />
      )}

      <div className={styles.actions}>
        <button type="button" className={button.secondary} onClick={onBack}>
          Terug naar het startpunt
        </button>
        <button type="button" className={button.secondary} onClick={onOwnDescription}>
          Zelf beschrijven
        </button>
      </div>
    </section>
  );
}
