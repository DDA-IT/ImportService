/**
 * NT-10 blok 1 — de checklist "Is deze koppeling klaar om leveringen te ontvangen?" uit
 * `GET /import-links/{id}/readiness` (NT-8, recht Lezen, schrijft niets).
 *
 * Per regel: een duidelijk teken en woord (in orde / probleem / ter info), een Nederlandse zin wat de regel
 * betekent, en bij een probleem "Wat moet ik doen?" met een link naar de plek waar het opgelost wordt. De code, het
 * onderwerp en de Engelse servertekst staan enkel onder "Technische details (voor support)" (V7).
 *
 * De beperkingen staan er in gewoon Nederlands bij: invulpunten van de koppeling pas na het activeren, en de
 * doelbibliotheek wordt niet tegen Prodis gecontroleerd. Fouten in de beschrijving van het bestand staan er allemaal
 * tegelijk (NT-14-4), elk met het veld waarop ze slaan; wat nog niet beoordeeld kon worden, meldt één infolijn.
 */
import { Link } from 'react-router-dom';
import type { ApiError } from '../../../api/http.ts';
import type { LinkReadiness, ReadinessCheck, RevisionRow } from '../../../api/types.ts';
import { ErrorBanner } from '../../../errors/ErrorBanner.tsx';
import { StatusBadge } from '../../../components/StatusBadge.tsx';
import { TechnicalDetails } from '../../../terms/TechnicalDetails.tsx';
import { revisionDetailHref, wizardHref } from '../wizard/wizardTypes.ts';
import { describeFindingField, getSkippedCodes, SKIPPED_CODE, SkippedCauses } from './configFindings.tsx';
import { describeReadinessCheck } from './linkCheck.ts';
import styles from './LinkCheckPage.module.css';

/** Ankers op dezelfde pagina waar de oplossing staat. */
export const ANCHOR_ACTIVATE = 'activeren';
export const ANCHOR_LINK_VALUES = 'waarden-koppeling';

export type ChecklistContext = {
  linkId: number;
  definitionId: number;
  /** `null` als de organisatie van de beschrijving niet teruggevonden werd; dan verwijst "Taak toevoegen" naar Inrichting. */
  organisationId: number | null;
  revisions: readonly RevisionRow[];
  hasActiveRevision: boolean;
};

type Action = { text: string; to?: string; anchor?: string; label?: string };

function taskAction(context: ChecklistContext, text: string): Action {
  return context.organisationId === null
    ? { text, to: '/setup', label: 'Naar Inrichting' }
    : {
        text,
        to: wizardHref({ organisationId: context.organisationId, definitionId: context.definitionId, linkId: context.linkId }),
        label: 'Taak toevoegen',
      };
}

/** Wat de gebruiker bij een probleem (of een INFO-regel met een handeling) moet doen, en waar. */
export function whatToDo(check: ReadinessCheck, context: ChecklistContext): Action | null {
  if (check.status === 'OK') {
    return null;
  }
  switch (check.code) {
    case 'NO_ACTIVE_REVISION':
      return {
        text: 'Test het bestand eerst met een proefinlezing en neem de conceptversie daarna in gebruik.',
        anchor: ANCHOR_ACTIVATE,
        label: 'Naar "Deze versie in gebruik nemen"',
      };
    case 'CONFIG_REQUIRED_BOOKMARK_MISSING':
      if (check.subject.type === 'LINK') {
        return context.hasActiveRevision
          ? {
              text: 'Vul de ontbrekende waarden van de koppeling in.',
              anchor: ANCHOR_LINK_VALUES,
              label: 'Naar "Waarden van de koppeling"',
            }
          : {
              text:
                'Deze waarden kunt u pas invullen nadat de versie in gebruik genomen is. Neem eerst de versie in ' +
                'gebruik en vul daarna de waarden van de koppeling in.',
              anchor: ANCHOR_ACTIVATE,
              label: 'Naar "Deze versie in gebruik nemen"',
            };
      }
      return revisionAction(check, context);
    case 'LINK_HAS_NO_TASK':
      return taskAction(context, 'Voeg een taak toe: dat is de ingang waarop u leveringen oplaadt.');
    case 'TASK_NOT_MANUAL':
    case 'TASK_HAS_DELIVERY_CONFIGURATION':
      return taskAction(
        context,
        'Een bestand opladen kan alleen op een handmatige taak zonder ophaalinstelling. Voeg zo een taak toe.',
      );
    case 'INFO_NO_DRAFT_REVISION':
      return { text: 'Maak bij Inrichting een nieuwe versie van de beschrijving van het bestand.', to: '/setup', label: 'Naar Inrichting' };
    case 'INFO_LIBRARY_NOT_VERIFIED':
      return { text: 'Kijk zelf na of de bibliotheekcode in Prodis bestaat.' };
    case 'INFO_LINK_INACTIVE':
      return null;
    default:
      return check.status === 'PROBLEM' ? revisionAction(check, context) : null;
  }
}

/** Een fout in de beschrijving: een concept past u zelf aan, een versie in gebruik vraagt een nieuwe versie. */
function revisionAction(check: ReadinessCheck, context: ChecklistContext): Action {
  if (check.subject.type !== 'REVISION') {
    return { text: 'Pas de beschrijving van het bestand aan bij Inrichting.', to: '/setup', label: 'Naar Inrichting' };
  }
  const revision = context.revisions.find((row) => row.id === check.subject.id);
  const to = revisionDetailHref(context.definitionId, check.subject.id);
  if (revision?.status === 'DRAFT') {
    return { text: `Pas conceptversie ${revision.revisionNumber} aan bij Inrichting.`, to, label: 'Versie openen' };
  }
  return {
    text: 'Deze versie is al in gebruik en kan niet meer gewijzigd worden: maak bij Inrichting een nieuwe versie en pas die aan.',
    to,
    label: 'Versie openen',
  };
}

const ICONS: Record<ReadinessCheck['status'], { symbol: string; className: string }> = {
  OK: { symbol: '✓', className: styles.iconOk ?? '' },
  PROBLEM: { symbol: '✗', className: styles.iconProblem ?? '' },
  INFO: { symbol: 'i', className: styles.iconInfo ?? '' },
};

function CheckRow({ check, context }: { check: ReadinessCheck; context: ChecklistContext }) {
  const described = describeReadinessCheck(check.code);
  const action = whatToDo(check, context);
  const icon = ICONS[check.status] ?? ICONS.INFO;
  const fieldText = describeFindingField(check.revisionField, check.fieldName);
  const details = [
    { name: 'Code', value: check.code },
    { name: 'Onderwerp', value: `${check.subject.type} ${check.subject.id}` },
    ...(check.detail === null ? [] : [{ name: 'Toelichting', value: check.detail }]),
    ...(check.revisionField ? [{ name: 'Veldsleutel', value: check.revisionField }] : []),
    ...(check.fieldName ? [{ name: 'Veldnaam', value: check.fieldName }] : []),
  ];
  return (
    <li className={styles.checkRow} data-testid={`readiness-check-${check.status}`}>
      <span className={`${styles.icon} ${icon.className}`} aria-hidden="true">
        {icon.symbol}
      </span>
      <div>
        <div className={styles.checkHead}>
          <StatusBadge status={check.status} domain="readinessStatus" />
          <span>{described.label}</span>
        </div>
        <p className={styles.checkUitleg}>{described.uitleg}</p>
        {action !== null && (
          <p className={styles.whatToDo}>
            <strong>Wat moet ik doen?</strong> {action.text}{' '}
            {action.to !== undefined && (
              <Link to={action.to} data-testid="readiness-fix-link">
                {action.label}
              </Link>
            )}
            {action.anchor !== undefined && (
              <a href={`#${action.anchor}`} data-testid="readiness-fix-link">
                {action.label}
              </a>
            )}
          </p>
        )}
        {fieldText !== null && (
          <p className={styles.checkUitleg} data-testid="readiness-check-field">
            {fieldText}
          </p>
        )}
        {check.code === SKIPPED_CODE ? (
          <SkippedCauses codes={getSkippedCodes(check)} extraDetails={details} />
        ) : (
          <TechnicalDetails items={details} />
        )}
      </div>
    </li>
  );
}

export type ReadinessChecklistProps = {
  readiness: { data: LinkReadiness | null; error: ApiError | null; loading: boolean; reload: () => void };
  context: ChecklistContext;
};

export function ReadinessChecklist({ readiness, context }: ReadinessChecklistProps) {
  const data = readiness.data;
  const problems = data === null ? 0 : data.checks.filter((check) => check.status === 'PROBLEM').length;
  return (
    <section className={styles.section} aria-labelledby="check-readiness-title" data-testid="readiness-section">
      <h2 id="check-readiness-title" className={styles.sectionTitle}>
        1. Is deze koppeling klaar om leveringen te ontvangen?
      </h2>
      <p className={styles.sectionIntro}>
        Wat is dit? Een controle zonder bestand: ze kijkt of alles ingesteld is wat een levering nodig heeft. Er
        wordt niets gewijzigd.
      </p>

      {readiness.error !== null && <ErrorBanner error={readiness.error} />}
      {readiness.loading && data === null && <p className={styles.loading}>Bezig met controleren…</p>}

      {data !== null && (
        <>
          {data.ready ? (
            <p className={styles.summaryReady} role="status" data-testid="readiness-summary">
              ✓ Klaar voor leveringen
            </p>
          ) : (
            <p className={styles.summaryNotReady} role="status" data-testid="readiness-summary">
              ✗ Nog niet klaar: {problems} {problems === 1 ? 'punt' : 'punten'} op te lossen
            </p>
          )}
          <ul className={styles.checkList} data-testid="readiness-checks">
            {data.checks.map((check, index) => (
              <CheckRow key={`${check.code}-${check.subject.type}-${check.subject.id}-${index}`} check={check} context={context} />
            ))}
          </ul>
        </>
      )}

      <ul className={styles.caveats} data-testid="readiness-caveats">
        <li>
          De waarden van de koppeling (invulpunten) kunt u pas invullen nadat de versie in gebruik genomen is.
          Zolang de versie een concept is, staan ze hier daarom al als probleem.
        </li>
        <li>De doelbibliotheek wordt niet tegen Prodis gecontroleerd; kijk de code zelf na.</li>
      </ul>

      <div className={styles.actions}>
        <button
          type="button"
          className={styles.secondary}
          onClick={readiness.reload}
          disabled={readiness.loading}
          data-testid="readiness-reload"
        >
          Opnieuw controleren
        </button>
      </div>
    </section>
  );
}
