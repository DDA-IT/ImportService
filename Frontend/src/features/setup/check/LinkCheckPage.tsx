/**
 * `/setup/links/:linkId/check` — scherm "Controleren" van één koppeling (NT-10; blok 2 van het verhaal "nieuwe
 * leverancier en taak", `docs/decisions.md` 2026-09-30: V1 = checklist + proefinlezing + eerste echte levering,
 * V6 = activeren zonder proef mag, met een duidelijke waarschuwing, V7 = Nederlands vooraan, code klein).
 *
 * Vier blokken, van boven naar onder:
 * 1. **Checklist** (`GET /import-links/{id}/readiness`, recht Lezen) — {@link ReadinessChecklist}.
 * 2. **Proefinlezing** (`POST /revisions/{id}/trial-reads`, recht Beheren) — {@link TrialReadSection}; het
 *    resultaat bestaat enkel in de toestand van deze pagina.
 * 3. **Activeren** — de bestaande {@link ActivateRevisionAction} met haar verplichte waarschuwing, in gewoon
 *    Nederlands, plus de V6-waarschuwing wanneer er met deze versie op deze pagina nog geen geslaagde proef was
 *    (de server weet niet of er een proef was: het is bewust UI-toestand, contract §5). Na het activeren worden
 *    de checklist en de versies opnieuw gelezen.
 * 4. **Wat nu?** — de eerste echte levering blijft de laatste controle (V1-D): een eerste levering wacht altijd op
 *    een goedkeuring, er wordt niets gepubliceerd zonder mens.
 *
 * Adres: `?definitionId=` versnelt het opzoeken (enkel onder die beschrijving gezocht), `?revisionId=` kiest de
 * versie; zonder keuze de hoogst genummerde conceptversie, anders de actieve versie.
 */
import { useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import * as importLinksApi from '../../../api/importLinks.ts';
import { PERMISSION_READ, type RevisionRow, type TrialReadResult } from '../../../api/types.ts';
import { usePermissionGate } from '../../../actor/permissions.ts';
import { ErrorBanner } from '../../../errors/ErrorBanner.tsx';
import { useQuery } from '../../../hooks/useQuery.ts';
import { Term } from '../../../terms/Term.tsx';
import { term } from '../../../terms/index.ts';
import { LinkBookmarkValuesSection } from '../../templates/LinkBookmarkValuesSection.tsx';
import { ActivateRevisionAction } from '../ActivateRevisionAction.tsx';
import { chooseRevision, loadCheckContext } from './linkCheck.ts';
import { ANCHOR_ACTIVATE, ANCHOR_LINK_VALUES, ReadinessChecklist } from './ReadinessChecklist.tsx';
import { TrialReadSection } from './TrialReadSection.tsx';
import styles from './LinkCheckPage.module.css';

function parseId(value: string | null | undefined): number | null {
  if (value === null || value === undefined || !/^\d+$/.test(value)) {
    return null;
  }
  const id = Number(value);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
}

function revisionLabel(revision: RevisionRow): string {
  return `Versie ${revision.revisionNumber} (${term('revisionStatus', revision.status).label})`;
}

type TrialState = { revisionId: number; result: TrialReadResult };

/** V6 — de waarschuwing in de bevestiging wanneer de laatste proef met deze versie niet geslaagd is. */
function NoSuccessfulTrialWarning({ trial }: { trial: TrialState | null }) {
  const blocked = trial !== null && trial.result.verdict.result === 'WOULD_BLOCK';
  return (
    <p className={styles.warning} role="alert" data-testid="no-successful-trial-warning">
      <strong>Let op:</strong>{' '}
      {blocked
        ? 'uw laatste proefinlezing met deze versie zou de levering tegenhouden.'
        : 'u heeft nog geen geslaagde proefinlezing gedaan met deze versie.'}{' '}
      Zonder geslaagde proef weet u niet of het bestand van de leverancier juist gelezen wordt. U kunt toch activeren;
      de eerste echte levering is dan de enige controle.
    </p>
  );
}

function ActivateSection({
  revision,
  trial,
  onActivated,
}: {
  revision: RevisionRow | null;
  trial: TrialState | null;
  onActivated: () => void;
}) {
  const successful = trial !== null && trial.result.verdict.result === 'NO_BLOCKER_FOUND';
  return (
    <section
      id={ANCHOR_ACTIVATE}
      className={styles.section}
      aria-labelledby="check-activate-title"
      data-testid="activate-section"
    >
      <h2 id="check-activate-title" className={styles.sectionTitle}>
        3. Deze versie in gebruik nemen
      </h2>
      <p className={styles.sectionIntro}>
        Wat is dit? Activeren betekent: vanaf nu worden nieuwe leveringen met deze versie van de beschrijving
        gelezen. Zolang er geen versie in gebruik is, kan de koppeling geen leveringen ontvangen.
      </p>
      {revision === null && <p className={styles.muted}>Er is geen conceptversie om in gebruik te nemen.</p>}
      {revision !== null && revision.status === 'ACTIVE' && (
        <p className={styles.muted} data-testid="activate-already-active">
          Versie {revision.revisionNumber} is al in gebruik (<Term domain="revisionStatus" code="ACTIVE" />). Er is
          niets te activeren.
        </p>
      )}
      {revision !== null && revision.status !== 'ACTIVE' && revision.status !== 'DRAFT' && (
        <p className={styles.muted}>
          Versie {revision.revisionNumber} is <Term domain="revisionStatus" code={revision.status} /> en kan niet
          (meer) in gebruik genomen worden.
        </p>
      )}
      {revision !== null && revision.status === 'DRAFT' && (
        <>
          {!successful && (
            <p className={styles.muted} data-testid="activate-tip">
              Tip: doe eerst een geslaagde proefinlezing hierboven.
            </p>
          )}
          <ActivateRevisionAction
            revision={revision}
            onActivated={onActivated}
            wording={{
              buttonLabel: `Versie ${revision.revisionNumber} in gebruik nemen`,
              title: `Versie ${revision.revisionNumber} in gebruik nemen`,
              confirmLabel: 'In gebruik nemen',
              explanation: (
                <>
                  <p>
                    Versie {revision.revisionNumber} wordt in gebruik genomen: nieuwe leveringen worden er vanaf nu
                    mee gelezen. Een versie die nu in gebruik is, wordt vervangen en blijft leesbaar. Vóór het
                    activeren wordt de beschrijving nog eens volledig nagekeken.
                  </p>
                  <p className={styles.muted}>
                    Leveringen die al gelezen zijn, blijven bij hun eigen versie en worden niet opnieuw beoordeeld.
                  </p>
                </>
              ),
            }}
            extraWarning={successful ? undefined : <NoSuccessfulTrialWarning trial={trial} />}
          />
        </>
      )}
    </section>
  );
}

function WhatNowSection({ hasActiveRevision }: { hasActiveRevision: boolean }) {
  return (
    <section className={styles.section} aria-labelledby="check-next-title" data-testid="what-now-section">
      <h2 id="check-next-title" className={styles.sectionTitle}>
        4. Wat nu?
      </h2>
      <p className={styles.sectionIntro}>
        De eerste echte levering is de laatste controle. Bij een{' '}
        <Term domain="creationPolicy" code="INITIAL_LOAD" /> wachten alle nieuwe artikelen op een goedkeuring, en er
        wordt niets gepubliceerd zonder dat een mens het goedkeurt.
      </p>
      {hasActiveRevision ? (
        <div className={styles.actions}>
          <Link className={styles.submit} to="/upload" data-testid="what-now-upload">
            Naar Levering uploaden
          </Link>
        </div>
      ) : (
        <p className={styles.muted}>Een levering opladen kan zodra er een versie in gebruik is.</p>
      )}
    </section>
  );
}

export function LinkCheckPage() {
  const readGate = usePermissionGate(PERMISSION_READ);
  const params = useParams();
  const [search, setSearch] = useSearchParams();
  const linkId = parseId(params.linkId);
  const definitionHint = parseId(search.get('definitionId'));
  const requestedRevisionId = parseId(search.get('revisionId'));

  const context = useQuery(`link-check-context:${linkId ?? 0}:${definitionHint ?? ''}`, (signal) =>
    linkId === null ? Promise.resolve(null) : loadCheckContext(linkId, definitionHint, signal),
  );
  const readiness = useQuery(`link-readiness:${linkId ?? 0}`, (signal) =>
    linkId === null ? Promise.resolve(null) : importLinksApi.getImportLinkReadiness(linkId, signal),
  );
  const [trial, setTrial] = useState<TrialState | null>(null);
  const [activated, setActivated] = useState(false);

  if (!readGate.allowed) {
    return (
      <div className={styles.page}>
        <p role="alert">{readGate.reason}</p>
      </div>
    );
  }
  if (linkId === null) {
    return (
      <div className={styles.page}>
        <p className={styles.validationError} role="alert">
          Het adres noemt geen geldige koppeling. <Link to="/setup">Terug naar Inrichting</Link>
        </p>
      </div>
    );
  }

  const loaded = context.data;
  const link = loaded?.link ?? null;
  const choice = chooseRevision(loaded?.revisions ?? [], requestedRevisionId);
  const chosen = choice.chosen;
  const hasActiveRevision = (loaded?.revisions ?? []).some((row) => row.status === 'ACTIVE');
  const trialForChosen = trial !== null && chosen !== null && trial.revisionId === chosen.id ? trial : null;
  const linkValuesProblem =
    readiness.data?.checks.some(
      (check) =>
        check.status === 'PROBLEM' && check.code === 'CONFIG_REQUIRED_BOOKMARK_MISSING' && check.subject.type === 'LINK',
    ) ?? false;

  function handleActivated() {
    setActivated(true);
    readiness.reload();
    context.reload();
  }

  return (
    <div className={styles.page}>
      <p>
        <Link to="/setup">← Inrichting</Link>
      </p>
      <h1 className={styles.title}>Controleren</h1>
      <p className={styles.intro}>
        Hier ziet u of deze koppeling klaar is om leveringen te ontvangen, test u een bestand zonder iets op te slaan,
        en neemt u de versie van de beschrijving in gebruik.
      </p>

      {context.error !== null && <ErrorBanner error={context.error} />}
      {context.loading && loaded === null && <p className={styles.loading}>Bezig met laden…</p>}
      {loaded !== null && link === null && (
        <p className={styles.validationError} role="alert" data-testid="check-link-not-found">
          Deze koppeling bestaat niet (meer) of hoort niet bij de beschrijving uit het adres.{' '}
          <Link to="/setup">Terug naar Inrichting</Link>
        </p>
      )}

      {link !== null && loaded !== null && (
        <>
          <dl className={styles.context} data-testid="check-context">
            <div>
              <dt>{term('setupField', 'link').label}</dt>
              <dd>
                {link.code} — {link.name} ({term('setupField', 'supplierCode').label.toLowerCase()} {link.supplierCode},{' '}
                {term('setupField', 'libraryCode').label.toLowerCase()} {link.libraryCode})
              </dd>
            </div>
            <div>
              <dt>{term('setupField', 'definition').label}</dt>
              <dd>
                {loaded.definition === null
                  ? 'niet teruggevonden'
                  : `${loaded.definition.code} — ${loaded.definition.name}`}
              </dd>
            </div>
            <div>
              <dt>{term('setupField', 'revision').label}</dt>
              <dd>
                {chosen === null ? (
                  'er is nog geen versie'
                ) : choice.choices.length > 1 ? (
                  <select
                    className={styles.select}
                    aria-label="Welke versie wilt u controleren?"
                    value={chosen.id}
                    data-testid="check-revision-select"
                    onChange={(event) => {
                      const next = new URLSearchParams(search);
                      next.set('revisionId', event.target.value);
                      setSearch(next);
                    }}
                  >
                    {choice.choices.map((row) => (
                      <option key={row.id} value={row.id}>
                        {revisionLabel(row)}
                      </option>
                    ))}
                  </select>
                ) : (
                  revisionLabel(chosen)
                )}
              </dd>
            </div>
          </dl>
          {choice.requestedNotFound && (
            <p className={styles.warning} role="alert" data-testid="check-revision-not-found">
              De versie uit het adres hoort niet bij deze koppeling of bestaat niet (meer).
              {chosen !== null && <> U controleert nu {revisionLabel(chosen).toLowerCase()}.</>}
            </p>
          )}
          {activated && (
            <p className={styles.nothingStored} role="status" data-testid="check-activated">
              De versie is in gebruik genomen. De checklist hieronder is opnieuw gecontroleerd.
            </p>
          )}

          <ReadinessChecklist
            readiness={readiness}
            context={{
              linkId: link.id,
              definitionId: link.importDefinitionId,
              organisationId: loaded.definition?.sourceOrganisationId ?? null,
              revisions: loaded.revisions,
              hasActiveRevision,
            }}
          />

          {linkValuesProblem && hasActiveRevision && (
            <section id={ANCHOR_LINK_VALUES} className={styles.section} aria-label="Waarden van de koppeling">
              <h2 className={styles.sectionTitle}>Waarden van de koppeling</h2>
              <p className={styles.sectionIntro}>
                Wat is dit? Waarden die de beschrijving per koppeling vraagt (invulpunten). Vul de ontbrekende in en
                klik daarna op &laquo;Opnieuw controleren&raquo;.
              </p>
              <LinkBookmarkValuesSection linkId={link.id} linkCode={link.code} />
            </section>
          )}

          <TrialReadSection
            linkId={link.id}
            revision={chosen}
            result={trialForChosen?.result ?? null}
            onResult={(revisionId, result) => setTrial({ revisionId, result })}
          />

          <ActivateSection revision={chosen} trial={trialForChosen} onActivated={handleActivated} />

          <WhatNowSection hasActiveRevision={hasActiveRevision} />
        </>
      )}
    </div>
  );
}
