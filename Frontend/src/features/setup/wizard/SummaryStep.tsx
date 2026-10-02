/**
 * NT-6 stap 6 — overzicht en de duidelijke volgende stap. De taak is pas bruikbaar nadat de conceptversie
 * gecontroleerd en geactiveerd is. Sinds NT-10 opent "Controleer en activeer de conceptversie" het scherm
 * "Controleren" van deze koppeling (checklist, proefinlezing, activeren); een niet-conceptversie verwijst nog naar
 * het revisiedetail bij Inrichting.
 *
 * De drempels hier komen uit de server (het revisiedetail), niet uit het stappenplan: zo ziet de gebruiker
 * de werkelijk bewaarde waarden.
 */
import { Link } from 'react-router-dom';
import * as setupApi from '../../../api/setup.ts';
import type { RevisionDetail } from '../../../api/types.ts';
import { ErrorBanner } from '../../../errors/ErrorBanner.tsx';
import { useQuery } from '../../../hooks/useQuery.ts';
import { Term } from '../../../terms/Term.tsx';
import { term } from '../../../terms/index.ts';
import { linkCheckHref } from '../check/linkCheck.ts';
import { revisionDetailHref, type WizardData } from './wizardTypes.ts';
import button from '../../../components/Button.module.css';
import styles from './Wizard.module.css';

const THRESHOLD_KEYS = [
  'creationThresholdSharePercent',
  'maxCriticalSharePercent',
  'maxRejectedSharePercent',
  'bulkIncidentSharePercent',
] as const;

/** Een percentage zoals de server het levert; `null` is "niet ingesteld", nooit 0. */
function percentText(value: number | null): string {
  return value === null ? 'niet ingesteld' : `${value} %`;
}

function ThresholdList({ values }: { values: Pick<RevisionDetail, (typeof THRESHOLD_KEYS)[number]> }) {
  return (
    <dl className={styles.defaults} data-testid="wizard-summary-thresholds">
      {THRESHOLD_KEYS.map((key) => (
        <div key={key}>
          <dt>{term('revisionField', key).label}</dt>
          <dd>{percentText(values[key])}</dd>
        </div>
      ))}
    </dl>
  );
}

export function SummaryStep({ data }: { data: WizardData }) {
  const { organisation, definition, revision, link, task } = data;
  const detail = useQuery(`wizard-summary:${definition?.id ?? 0}:${revision?.id ?? 0}`, (signal) =>
    definition !== null && revision !== null
      ? setupApi.getRevisionDetail(definition.id, revision.id, signal)
      : Promise.resolve(null),
  );

  if (organisation === null || definition === null || revision === null || link === null || task === null) {
    return null;
  }
  const isDraft = revision.status === 'DRAFT';

  return (
    <section className={styles.form} aria-labelledby="wizard-step-6-title" data-testid="wizard-summary">
      <h2 id="wizard-step-6-title" className={styles.stepTitle}>
        Klaar: alles is bewaard
      </h2>

      <dl className={styles.defaults}>
        <div>
          <dt>{term('setupField', 'sourceOrganisation').label}</dt>
          <dd>
            {organisation.code} — {organisation.name} (<Term domain="organisationType" code={organisation.type} />)
          </dd>
        </div>
        <div>
          <dt>{term('setupField', 'definition').label}</dt>
          <dd>
            {definition.code} — {definition.name}
          </dd>
        </div>
        <div>
          <dt>{term('setupField', 'revision').label}</dt>
          <dd>
            Versie {revision.revisionNumber} — <Term domain="revisionStatus" code={revision.status} />
          </dd>
        </div>
        <div>
          <dt>{term('setupField', 'link').label}</dt>
          <dd>
            {link.code} — {link.name} ({term('setupField', 'supplierCode').label.toLowerCase()}{' '}
            {link.supplierCode}, {term('setupField', 'libraryCode').label.toLowerCase()} {link.libraryCode})
          </dd>
        </div>
        <div>
          <dt>{term('setupField', 'task').label}</dt>
          <dd>{task.name}</dd>
        </div>
      </dl>

      <h3 className={styles.stepTitle}>Drempels van deze versie</h3>
      {detail.error !== null && <ErrorBanner error={detail.error} />}
      {detail.loading && detail.data === null && <p className={styles.loading}>Bezig met laden…</p>}
      {detail.data !== null && <ThresholdList values={detail.data} />}

      <h3 className={styles.stepTitle}>Volgende stap</h3>
      {isDraft ? (
        <>
          <p className={styles.note}>
            De versie is nog een <Term domain="revisionStatus" code="DRAFT" />. Zolang ze niet geactiveerd is, staat
            de taak bij Levering uploaden als &laquo;nog niet klaar&raquo; en kan er niets opgeladen worden.
          </p>
          <div className={styles.actions}>
            <Link
              className={button.primary}
              to={linkCheckHref({ linkId: link.id, definitionId: definition.id, revisionId: revision.id })}
              data-testid="wizard-next-step"
            >
              Controleer en activeer de conceptversie
            </Link>
          </div>
        </>
      ) : (
        <>
          <p className={styles.note}>
            Versie {revision.revisionNumber} is <Term domain="revisionStatus" code={revision.status} />.
          </p>
          <div className={styles.actions}>
            <Link className={button.primary} to={revisionDetailHref(definition.id, revision.id)} data-testid="wizard-next-step">
              Bekijk de versie bij Inrichting
            </Link>
            {revision.status === 'ACTIVE' && <Link to="/upload">Naar Levering uploaden</Link>}
          </div>
        </>
      )}
    </section>
  );
}
