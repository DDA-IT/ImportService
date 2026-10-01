/**
 * `/setup/new` — stappenplan "Nieuwe leverancier en taak", weg "zelf beschrijven" (NT-6).
 *
 * Beslissingen: `docs/decisions.md` 2026-09-30 "Gebruiker richt zelf een nieuwe leverancier + taak in
 * (MANAGE)" (+ aanvulling: begrijpelijk Nederlands, uitleg bij elk begrip) en "Nieuwe leverancier + taak
 * (NT-spoor): V1-V7 beslist" (V2 = a: bestaande paden zonder vlag, V3 = a: taak als aparte laatste stap,
 * V4 = a / A1: dit stappenplan maakt enkel een eigen definitie, A3: herkenningsversie standaard 2).
 *
 * - Zes stappen: leverancier → startpunt → beschrijving van het bestand (beschrijving + versie 1 als concept)
 *   → koppeling → taak → klaar. Elke stap bewaart op de server vóór de volgende begint; de huidige stap volgt
 *   uit wat er al bestaat.
 * - **Hervatten**: `?organisationId=…[&definitionId=…[&linkId=…]]` (zo opent "Verder inrichten" in Inrichting
 *   dit scherm). De ids worden nagelezen via de vlagloze leeslijsten; wat niet (meer) klopt, wordt gemeld,
 *   nooit stil vervangen.
 * - **NT-7 — het sjabloonpad**: stap 2 kan vertrekken van een sjabloon van dezelfde organisatie (`TemplateStep`,
 *   V4 = a). Het materialiseren maakt beschrijving + conceptversie + koppeling, nooit een taak (V3 = a); daarna
 *   springt het scherm via dezelfde hervatting (`?organisationId&definitionId&linkId`) naar de taakstap.
 *   Hetzelfde adres met `linkId` is ook de ingang van "Taak toevoegen" (Inrichting, pagina Sjablonen).
 * - De slotstap verwijst naar het scherm "Controleren" van de koppeling (NT-10, `/setup/links/:linkId/check`).
 */
import { useEffect, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { ApiError } from '../../../api/http.ts';
import { PERMISSION_MANAGE, PERMISSION_READ, type MaterialisationView } from '../../../api/types.ts';
import { LinkBookmarkValuesSection } from '../../templates/LinkBookmarkValuesSection.tsx';
import { usePermissionGate } from '../../../actor/permissions.ts';
import { ErrorBanner } from '../../../errors/ErrorBanner.tsx';
import { Term } from '../../../terms/Term.tsx';
import { term } from '../../../terms/index.ts';
import { DescriptionStep } from './DescriptionStep.tsx';
import { LinkStep } from './LinkStep.tsx';
import {
  latestRevision,
  loadAllOrganisations,
  loadDefinitionsOf,
  loadLinksOf,
  loadRevisionsOf,
  loadTasksOf,
} from './lookup.ts';
import { OrganisationStep } from './OrganisationStep.tsx';
import { SummaryStep } from './SummaryStep.tsx';
import { TaskStep } from './TaskStep.tsx';
import { TemplateStep } from './TemplateStep.tsx';
import { toWizardLink, toWizardOrganisation, toWizardRevision } from './wizardMappers.ts';
import {
  currentStep,
  EMPTY_WIZARD,
  STEP_TITLES,
  wizardHref,
  type WizardData,
  type WizardStep,
} from './wizardTypes.ts';
import styles from './Wizard.module.css';

const ALL_STEPS: WizardStep[] = [1, 2, 3, 4, 5, 6];

function parseId(value: string | null): number | null {
  if (value === null || !/^\d+$/.test(value)) {
    return null;
  }
  const id = Number(value);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
}

/** Een id uit de adresbalk dat niet (meer) klopt: gemeld in gewoon Nederlands, niet stil genegeerd. */
class ResumeProblem extends Error {}

type ResumeState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'problem'; message: string }
  | { status: 'error'; error: ApiError };

/** Leest de bestaande inrichting achter de ids uit de adresbalk en leidt er de toestand van af. */
async function resumeFrom(organisationId: number, definitionId: number | null, linkId: number | null) {
  const organisation = (await loadAllOrganisations()).find((row) => row.id === organisationId);
  if (organisation === undefined) {
    throw new ResumeProblem('De leverancier of aankoopvereniging uit de link bestaat niet (meer).');
  }
  const next: WizardData = { ...EMPTY_WIZARD, organisation: toWizardOrganisation(organisation) };
  if (definitionId === null) {
    return next;
  }

  const definition = (await loadDefinitionsOf(organisation.id)).find((row) => row.id === definitionId);
  if (definition === undefined) {
    throw new ResumeProblem('De beschrijving van het bestand uit de link hoort niet bij deze organisatie of bestaat niet (meer).');
  }
  if (definition.usageType === 'REUSABLE_TEMPLATE') {
    throw new ResumeProblem('Dit is een sjabloon; een sjabloon richt u in via de pagina Sjablonen.');
  }
  next.startChosen = true;
  next.definition = { id: definition.id, code: definition.code, name: definition.name };
  const latest = latestRevision(await loadRevisionsOf(definition.id));
  next.revision = latest === null ? null : toWizardRevision(latest);
  if (linkId === null) {
    return next;
  }

  const link = (await loadLinksOf(definition.id)).find((row) => row.id === linkId);
  if (link === undefined) {
    throw new ResumeProblem('De koppeling uit de link hoort niet bij deze beschrijving of bestaat niet (meer).');
  }
  next.link = toWizardLink(link);
  const [firstTask] = await loadTasksOf(link.id);
  next.task = firstTask === undefined ? null : { id: firstTask.id, name: firstTask.name };
  return next;
}

function Stepper({ step }: { step: WizardStep }) {
  return (
    <ol className={styles.stepper} aria-label="Stappen">
      {ALL_STEPS.map((item) => (
        <li
          key={item}
          className={`${styles.stepItem} ${item === step ? styles.stepCurrent : ''} ${item < step ? styles.stepDone : ''}`}
          aria-current={item === step ? 'step' : undefined}
        >
          {item < step ? '✓ ' : `${item}. `}
          {STEP_TITLES[item]}
        </li>
      ))}
    </ol>
  );
}

/** Wat al bewaard is, bovenaan: zo ziet de gebruiker bij het hervatten waar hij staat. */
function DoneSoFar({ data }: { data: WizardData }) {
  const { organisation, definition, revision, link } = data;
  if (organisation === null) {
    return null;
  }
  return (
    <dl className={styles.doneList} data-testid="wizard-done-so-far">
      <div>
        <dt>{term('setupField', 'sourceOrganisation').label}</dt>
        <dd>
          {organisation.code} — {organisation.name} (<Term domain="organisationType" code={organisation.type} />)
        </dd>
      </div>
      {definition !== null && (
        <div>
          <dt>{term('setupField', 'definition').label}</dt>
          <dd>
            {definition.code} — {definition.name}
            {revision !== null && (
              <>
                {' '}
                · versie {revision.revisionNumber} (<Term domain="revisionStatus" code={revision.status} />)
              </>
            )}
          </dd>
        </div>
      )}
      {link !== null && (
        <div>
          <dt>{term('setupField', 'link').label}</dt>
          <dd>
            {link.code} — {link.name}
          </dd>
        </div>
      )}
    </dl>
  );
}

/** De Nederlandse uitleg bij een waarschuwing van de materialisatie; een onbekende code toont de servertekst. */
function warningText(warning: { code: string; message: string }): string {
  if (warning.code === 'LINK_SEARCH_SUPPLIER_NOT_DERIVED') {
    return (
      'De leverancier waarmee in de bibliotheek gezocht wordt, is leeg gelaten. Ze wordt nooit automatisch uit ' +
      'de leverancier afgeleid.'
    );
  }
  return warning.message;
}

/**
 * NT-7 — wat de materialisatie aangemaakt heeft, zichtbaar bij de taakstap en de samenvatting: zo blijven de
 * waarschuwingen en de nog niet ingevulde verplichte invulpunten van de koppeling in beeld ("Een nieuwe
 * levering wordt geweigerd zolang deze lijst niet leeg is").
 */
function MaterialisedSummary({ result }: { result: MaterialisationView }) {
  return (
    <section className={styles.form} aria-label="Uit het sjabloon aangemaakt" data-testid="wizard-materialised">
      <h2 className={styles.stepTitle}>Uit het sjabloon aangemaakt</h2>
      <p className={styles.note}>
        {result.definitionCreated ? 'Een nieuwe beschrijving van het bestand' : 'Een bestaande beschrijving'}{' '}
        <strong>{result.definitionCode}</strong> en de koppeling <strong>{result.importLinkCode}</strong> zijn
        klaar, op basis van versie {result.templateRevisionNumber} van het sjabloon. De versie van de beschrijving
        is <Term domain="revisionStatus" code={result.definitionRevisionStatus} />. Er is nog geen taak: die maakt
        u hieronder.
      </p>
      {result.warnings.length > 0 && (
        <ul className={styles.note} data-testid="wizard-materialised-warnings">
          {result.warnings.map((warning, index) => (
            <li key={`${warning.code}-${warning.bookmarkName ?? ''}-${index}`}>{warningText(warning)}</li>
          ))}
        </ul>
      )}
      <LinkBookmarkValuesSection linkId={result.importLinkId} linkCode={result.importLinkCode} />
    </section>
  );
}

function StartStep({ onOwnDescription, onTemplate }: { onOwnDescription: () => void; onTemplate: () => void }) {
  return (
    <section className={styles.form} aria-labelledby="wizard-step-2-title">
      <h2 id="wizard-step-2-title" className={styles.stepTitle}>
        Stap 2 — Startpunt
      </h2>
      <p className={styles.note}>Hoe beschrijft u het bestand van deze organisatie?</p>
      <div className={styles.cards}>
        <div className={styles.card}>
          <h3 className={styles.stepTitle}>Zelf beschrijven</h3>
          <p className={styles.note}>
            U vult zelf in hoe het bestand eruitziet: scheidingsteken, kolommen en hoe een artikel herkend wordt.
            Geschikt voor een leverancier die rechtstreeks zijn eigen bestand levert.
          </p>
          <button type="button" className={styles.submit} onClick={onOwnDescription}>
            Zelf beschrijven
          </button>
        </div>
        <div className={styles.card}>
          <h3 className={styles.stepTitle}>Vanuit een sjabloon van deze leverancier of aankoopvereniging</h3>
          <p className={styles.note}>
            Vertrek van een bestaande, herbruikbare beschrijving van deze organisatie, bijvoorbeeld van een
            aankoopvereniging. De beschrijving, een conceptversie en de koppeling worden in één keer aangemaakt.
          </p>
          <button type="button" className={styles.submit} onClick={onTemplate}>
            Vanuit een sjabloon van deze leverancier of aankoopvereniging
          </button>
        </div>
      </div>
    </section>
  );
}

export function NewSupplierWizardPage() {
  const readGate = usePermissionGate(PERMISSION_READ);
  const manageGate = usePermissionGate(PERMISSION_MANAGE);
  const [params] = useSearchParams();
  const organisationId = parseId(params.get('organisationId'));
  const definitionId = parseId(params.get('definitionId'));
  const linkId = parseId(params.get('linkId'));

  const [data, setData] = useState<WizardData>(EMPTY_WIZARD);
  const [notice, setNotice] = useState<string | null>(null);
  const [templateStart, setTemplateStart] = useState(false);
  const [materialised, setMaterialised] = useState<MaterialisationView | null>(null);
  const navigate = useNavigate();
  const [resume, setResume] = useState<ResumeState>({ status: organisationId === null ? 'idle' : 'loading' });

  useEffect(() => {
    let current = true;
    setNotice(null);
    if (organisationId === null) {
      setData(EMPTY_WIZARD);
      setResume({ status: 'idle' });
      return;
    }
    setResume({ status: 'loading' });
    resumeFrom(organisationId, definitionId, linkId)
      .then((next) => {
        if (current) {
          setData(next);
          setResume({ status: 'idle' });
        }
      })
      .catch((cause: unknown) => {
        if (!current) {
          return;
        }
        if (cause instanceof ResumeProblem) {
          setResume({ status: 'problem', message: cause.message });
        } else {
          setResume({ status: 'error', error: cause instanceof ApiError ? cause : new ApiError(0, null, null, '') });
        }
      });
    return () => {
      current = false;
    };
  }, [organisationId, definitionId, linkId]);

  function patch(partial: Partial<WizardData>, message: string | null) {
    setData((previous) => ({ ...previous, ...partial }));
    setNotice(message);
  }

  function handleMaterialised(result: MaterialisationView) {
    if (data.organisation === null) {
      return;
    }
    // De definitie, conceptversie en koppeling bestaan nu op de server: het stappenplan hervat er rechtstreeks
    // op (zelfde weg als "Verder inrichten"), zodat de toestand altijd uit de server volgt.
    setMaterialised(result);
    setTemplateStart(false);
    navigate(
      wizardHref({
        organisationId: data.organisation.id,
        definitionId: result.definitionId,
        linkId: result.importLinkId,
      }),
    );
  }

  if (!readGate.allowed) {
    return (
      <div className={styles.page}>
        <p role="alert">{readGate.reason}</p>
      </div>
    );
  }

  const step = currentStep(data);

  return (
    <div className={styles.page}>
      <p>
        <Link to="/setup">← Inrichting</Link>
      </p>
      <h1 className={styles.title}>Nieuwe leverancier en taak</h1>
      <p className={styles.intro}>
        In zes stappen van niets tot een taak waarop u leveringen kunt opladen. Elke stap wordt meteen bewaard:
        u kunt later verdergaan via &laquo;Verder inrichten&raquo; bij Inrichting.
      </p>

      {!manageGate.allowed && (
        <p className={styles.validationError} role="alert" data-testid="wizard-no-manage">
          {manageGate.reason} Zonder dit recht kunt u niets aanmaken.
        </p>
      )}

      {resume.status === 'loading' && <p className={styles.loading}>Bezig met laden…</p>}
      {resume.status === 'problem' && (
        <p className={styles.validationError} role="alert" data-testid="wizard-resume-problem">
          {resume.message} <Link to="/setup">Terug naar Inrichting</Link>
        </p>
      )}
      {resume.status === 'error' && <ErrorBanner error={resume.error} />}

      {resume.status === 'idle' && (
        <>
          <Stepper step={step} />
          <DoneSoFar data={data} />
          {notice !== null && (
            <p className={styles.notice} role="status" data-testid="wizard-notice">
              {notice}
            </p>
          )}

          {step === 1 && <OrganisationStep onChosen={(organisation, message) => patch({ organisation }, message)} />}
          {materialised !== null && data.link !== null && data.link.id === materialised.importLinkId && step >= 5 && (
            <MaterialisedSummary result={materialised} />
          )}

          {step === 2 && !templateStart && (
            <StartStep
              onOwnDescription={() => patch({ startChosen: true }, null)}
              onTemplate={() => setTemplateStart(true)}
            />
          )}
          {step === 2 && templateStart && data.organisation !== null && (
            <TemplateStep
              organisation={data.organisation}
              onBack={() => setTemplateStart(false)}
              onOwnDescription={() => {
                setTemplateStart(false);
                patch({ startChosen: true }, null);
              }}
              onMaterialised={handleMaterialised}
            />
          )}
          {step === 3 && data.organisation !== null && (
            <DescriptionStep
              organisation={data.organisation}
              definition={data.definition}
              onDefinition={(definition, message) => patch({ definition }, message)}
              onRevision={(revision, message) => patch({ revision }, message)}
            />
          )}
          {step === 4 && data.organisation !== null && data.definition !== null && (
            <LinkStep
              organisation={data.organisation}
              definition={data.definition}
              onLink={(link, message) => patch({ link }, message)}
            />
          )}
          {step === 5 && data.link !== null && (
            <TaskStep link={data.link} onTask={(task, message) => patch({ task }, message)} />
          )}
          {step === 6 && <SummaryStep data={data} />}
        </>
      )}
    </div>
  );
}
