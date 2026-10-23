/**
 * `/batches/:batchId` — batchdetail, bouwstappen A-F1 en A-F2, zie `docs/decisions.md` 2026-09-23 "Frontend:
 * batchdetail, scherm (2) levering & screening en de §16-uitbreidingen"; opmaak en tabbladen volgens de
 * Prodis-mock (`docs/decisions.md` 2026-10-20, story UI-7).
 *
 * Tabbladen: een ARIA-tablist met lokale state (geen subroutes: de tabbladen delen één `GET /batches/{id}` en
 * de gekozen foutgroep). Overzicht blijft altijd gemount (de uitkomst en het open dialoog van de acties blijven
 * zo bestaan bij een tabwissel); Problemen, Mutaties en Levering laden pas bij het openen.
 * Alle tellers zijn `number | null`; `null` is "niet vastgesteld" en wordt als "—" getoond, nooit als 0.
 */

import { useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { Link, useParams } from 'react-router-dom';
import * as batchesApi from '../../api/batches.ts';
import type { BatchDetail } from '../../api/types.ts';
import { useQuery } from '../../hooks/useQuery.ts';
import { BackIcon, ClockIcon, LinkIcon, WarnIcon } from '../../components/icons.tsx';
import { StatusBadge } from '../../components/StatusBadge.tsx';
import { MutationList } from '../../components/MutationList/MutationList.tsx';
import type { MutationSource } from '../../components/MutationList/types.ts';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { describeIssue } from '../../terms/IssueCodeTerm.tsx';
import { TechnicalDetails } from '../../terms/TechnicalDetails.tsx';
import { Term } from '../../terms/Term.tsx';
import { WhatIsThis } from '../../terms/WhatIsThis.tsx';
import { BatchActions } from './BatchActions.tsx';
import { BatchDeliverySection } from './BatchDeliverySection.tsx';
import { BatchIssueGroupsSection } from './BatchIssueGroupsSection.tsx';
import { BatchIssuesSection } from './BatchIssuesSection.tsx';
import { CounterLabel, Count } from './format.tsx';
import pageCss from '../../components/Page.module.css';
import styles from './BatchDetailPage.module.css';
import { formatDateTime } from '../../format.ts';
import { SCREEN } from '../../terms/navigation.ts';

function Fact({ label, children }: { label: ReactNode; children: ReactNode }) {
  return (
    <div className={styles.fact}>
      <dt>{label}</dt>
      <dd>{children}</dd>
    </div>
  );
}

/** De tellers in de volgorde van het scherm; de sleutel is de veldnaam van `BatchDetail` én de woordenboekcode. */
const COUNTER_KEYS = [
  'rawRecordCount',
  'validRecordCount',
  'rejectedRecordCount',
  'filteredOutCount',
  'errorBeforeFilterCount',
  'duplicateIdentityCount',
  'newCount',
  'changedCount',
  'unchangedCount',
  'contentMutationCount',
  'awaitingApprovalCount',
  'identityIncidentCount',
  'bulkIncidentCount',
  'criticalLineCount',
  'criticalIssueCount',
  'warningCount',
] as const satisfies ReadonlyArray<keyof BatchDetail>;

type TabKey = 'overview' | 'problems' | 'mutations' | 'delivery';

const TABS: ReadonlyArray<{ key: TabKey; label: string }> = [
  { key: 'overview', label: 'Overzicht' },
  { key: 'problems', label: 'Problemen' },
  { key: 'mutations', label: 'Wijzigingen' },
  { key: 'delivery', label: 'Levering' },
];

/** Twee tekens voor de avatar: het begin van de leverancierscode (puur decoratief, `aria-hidden`). */
function supplierInitials(supplierCode: string): string {
  return supplierCode.replace(/[^A-Za-z0-9]/g, '').slice(0, 2).toUpperCase() || '?';
}

function BatchHead({ batch }: { batch: BatchDetail }) {
  return (
    <>
      <header className={`${pageCss.pageHead} ${styles.crumbHead}`}>
        <div className={pageCss.pageHeadText}>
          <ol className={pageCss.crumbs}>
            <li>
              <Link to="/">{SCREEN.inlezingen}</Link>
            </li>
            <li>
              <span aria-current="page">Inlezing {batch.batchId}</span>
            </li>
          </ol>
        </div>
      </header>
      <div className={styles.detailHead}>
        <Link to="/" className={styles.backButton} aria-label="Terug naar de inlezingen">
          <BackIcon />
        </Link>
        <span className={styles.avatar} aria-hidden="true">
          {supplierInitials(batch.supplierCode)}
        </span>
        <div className={styles.detailId}>
          <h1 className={pageCss.title}>Inlezing {batch.batchId}</h1>
          <div className={styles.meta}>
            <span>
              <LinkIcon />
              {batch.importLinkCode}
            </span>
            <span>
              {batch.supplierCode} · {batch.libraryCode}
            </span>
            <span>
              <ClockIcon />
              {formatDateTime(batch.createdAt)}
            </span>
          </div>
          <div className={styles.chips}>
            <StatusBadge status={batch.status} domain="batchStatus" />
            {batch.validationResult === null ? (
              <span>
                Eindoordeel: <Term domain="validationResult" code={null} />
              </span>
            ) : (
              <StatusBadge status={batch.validationResult} domain="validationResult" />
            )}
            <span className={styles.chip}>Poging {batch.attemptNo}</span>
          </div>
        </div>
      </div>
    </>
  );
}

function BatchOverview({ batch, onChanged }: { batch: BatchDetail; onChanged: () => void }) {
  const blocked = batch.blockedCode === null ? null : describeIssue(batch.blockedCode);
  return (
    <>
      <WhatIsThis>
        <p>
          Een inlezing is de controle van één aangeleverde levering. De status zegt hoe ver die controle staat; het
          eindoordeel zegt wat de controle vond. Een woord met een stippellijn heeft een uitleg: ga er met de muis
          over.
        </p>
      </WhatIsThis>

      {batch.blockedCode !== null && blocked !== null && (
        <div className={styles.alert} role="alert" data-testid="batch-blocked">
          <WarnIcon />
          <div>
            <strong>Tegengehouden: {blocked.label}</strong>
            <p>{blocked.uitleg}</p>
            <TechnicalDetails
              items={[
                { name: 'Code', value: batch.blockedCode },
                ...(batch.blockedReason !== null ? [{ name: 'Melding van de server', value: batch.blockedReason }] : []),
              ]}
            />
          </div>
        </div>
      )}

      <div className={styles.grid}>
        <section className={`${pageCss.card} ${styles.span8}`} aria-label="Tellers">
          <div className={pageCss.cardHead}>
            <div>
              <h2 className={pageCss.subtitle}>Tellers</h2>
              <p className={styles.secondary}>Uitkomst van de controle van deze levering.</p>
            </div>
          </div>
          <div className={pageCss.cardPad}>
            <dl className={styles.counters} data-testid="batch-counters">
              {COUNTER_KEYS.map((key) => (
                <Fact key={key} label={<CounterLabel counter={key} />}>
                  <Count value={batch[key]} />
                </Fact>
              ))}
            </dl>
          </div>
        </section>

        <section className={`${pageCss.card} ${styles.span4}`} aria-label="Leveringsfeiten">
          <div className={pageCss.cardHead}>
            <div>
              <h2 className={pageCss.subtitle}>Levering</h2>
              <p className={styles.secondary}>Wat er aangeleverd werd.</p>
            </div>
          </div>
          <div className={pageCss.cardPad}>
            <dl className={styles.facts} data-testid="batch-facts">
              <Fact label="Koppeling">
                <span title={`Leverancier ${batch.supplierCode} · bibliotheek ${batch.libraryCode}`}>
                  {batch.importLinkCode}
                </span>{' '}
                <span className={styles.secondary}>
                  ({batch.supplierCode} · {batch.libraryCode})
                </span>
              </Fact>
              <Fact label="Levering">#{batch.deliveryId}</Fact>
              <Fact label="Poging">{batch.attemptNo}</Fact>
              <Fact label="Aangemaakt">
                {formatDateTime(batch.createdAt)}
                {batch.createdBy !== null && ` door ${batch.createdBy}`}
              </Fact>
              <Fact label="Gestart">{formatDateTime(batch.startedAt)}</Fact>
              <Fact label="Afgerond">{formatDateTime(batch.finishedAt)}</Fact>
              <Fact label="Beleid voor nieuwe artikelen">
                <Term domain="creationPolicy" code={batch.creationOutcome} />
                {batch.creationCandidateCount !== null && batch.creationScopeCount !== null && (
                  <span className={styles.secondary}>
                    {' '}
                    ({batch.creationCandidateCount} nieuwe artikelen bij {batch.creationScopeCount} bestaande)
                  </span>
                )}
              </Fact>
            </dl>
            {batch.stagingPurgedAt != null && (
              <p className={styles.note} data-testid="batch-staging-purged">
                De tussentijdse gegevens van deze controle zijn op {formatDateTime(batch.stagingPurgedAt)}{' '}
                opgeruimd. De tellers, meldingen en wijzigingen blijven bewaard.
              </p>
            )}
            {batch.baselineAcceptedAt !== null && (
              <p className={styles.note} data-testid="batch-baseline">
                Nulmeting aanvaard door {batch.baselineAcceptedBy ?? 'onbekend'} op{' '}
                {formatDateTime(batch.baselineAcceptedAt)}
                {batch.baselineAcceptReason !== null && ` — reden: ${batch.baselineAcceptReason}`}
              </p>
            )}
          </div>
        </section>

        <div className={styles.span12}>
          <BatchActions batch={batch} onChanged={onChanged} />
        </div>
      </div>
    </>
  );
}

/** ARIA-tablist met lokale state: pijltjes, Home en End verplaatsen de focus én de selectie (automatische activatie). */
function BatchTabs({ selected, onSelect }: { selected: TabKey; onSelect: (key: TabKey) => void }) {
  const refs = useRef<Partial<Record<TabKey, HTMLButtonElement | null>>>({});

  function handleKeyDown(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    let next: number;
    switch (event.key) {
      case 'ArrowRight':
        next = (index + 1) % TABS.length;
        break;
      case 'ArrowLeft':
        next = (index - 1 + TABS.length) % TABS.length;
        break;
      case 'Home':
        next = 0;
        break;
      case 'End':
        next = TABS.length - 1;
        break;
      default:
        return;
    }
    const target = TABS[next]?.key;
    if (target === undefined) {
      return;
    }
    event.preventDefault();
    onSelect(target);
    refs.current[target]?.focus();
  }

  return (
    <div className={pageCss.tabs} role="tablist" aria-label="Onderdelen van de inlezing">
      {TABS.map((tab, index) => (
        <button
          key={tab.key}
          ref={(el) => {
            refs.current[tab.key] = el;
          }}
          type="button"
          role="tab"
          id={`batch-tab-${tab.key}`}
          aria-controls={`batch-panel-${tab.key}`}
          aria-selected={selected === tab.key}
          tabIndex={selected === tab.key ? 0 : -1}
          className={pageCss.tab}
          onClick={() => onSelect(tab.key)}
          onKeyDown={(event) => handleKeyDown(event, index)}
        >
          {tab.label}
        </button>
      ))}
    </div>
  );
}

function Panel({ tab, selected, children }: { tab: TabKey; selected: TabKey; children: ReactNode }) {
  return (
    <div role="tabpanel" id={`batch-panel-${tab}`} aria-labelledby={`batch-tab-${tab}`} hidden={selected !== tab}>
      {children}
    </div>
  );
}

export function BatchDetailPage() {
  const params = useParams<{ batchId: string }>();
  const batchId = Number(params.batchId);
  const validId = Number.isInteger(batchId) && batchId > 0;
  const [selectedGroupId, setSelectedGroupId] = useState<number | null>(null);
  const [tab, setTab] = useState<TabKey>('overview');

  const { data, error, loading, reload } = useQuery(`batch-detail:${params.batchId ?? ''}`, (signal) =>
    validId ? batchesApi.getBatch(batchId, signal) : Promise.reject(new Error('invalid batch id')),
  );

  const mutationSource: MutationSource = {
    key: `batch:${batchId}`,
    fetchPage: (query, signal) => batchesApi.batchMutations(batchId, query, signal),
    // `GET /batches/{id}/mutations` filtert sinds C1/C4 ook op status, statusReason en identityHash; er is
    // geen `batchId`-filter (de batch staat in het pad). Geen rowActions: beslissen gebeurt in een bundel.
    supportedFilters: ['status', 'actionType', 'statusReason', 'identityHash'],
  };

  const showCrumb = !validId || error !== null;

  return (
    <div className={styles.page}>
      {showCrumb && (
        <p className={styles.breadcrumb}>
          <Link to="/">{SCREEN.inlezingen}</Link>
        </p>
      )}

      {!validId && (
        <p role="alert" className={styles.alert}>
          Ongeldig inlezingsnummer: “{params.batchId}”.
        </p>
      )}
      {validId && error !== null && <ErrorBanner error={error} />}
      {validId && loading && data === null && error === null && <p className={styles.loading}>Bezig met laden…</p>}

      {validId && data !== null && error === null && (
        <>
          <BatchHead batch={data} />
          <BatchTabs selected={tab} onSelect={setTab} />

          <Panel tab="overview" selected={tab}>
            <BatchOverview batch={data} onChanged={reload} />
          </Panel>

          {tab === 'problems' && (
            <Panel tab="problems" selected={tab}>
              <BatchIssueGroupsSection
                batchId={batchId}
                selectedGroupId={selectedGroupId}
                onSelectGroup={setSelectedGroupId}
              />
              <BatchIssuesSection batchId={batchId} issueGroupId={selectedGroupId} />
            </Panel>
          )}

          {tab === 'mutations' && (
            <Panel tab="mutations" selected={tab}>
              <section className={pageCss.card} aria-label="Wijzigingen">
                <div className={pageCss.cardHead}>
                  <div>
                    <h2 className={pageCss.subtitle}>Wijzigingen</h2>
                    <p className={styles.secondary}>
                      Voorgestelde wijzigingen aan artikelen. Beslissen (goedkeuren of afkeuren) gebeurt in een publicatie.
                    </p>
                  </div>
                </div>
                <div className={pageCss.cardPad}>
                  <WhatIsThis>
                    <p>
                      Een wijziging is één voorgestelde wijziging aan een artikel: een nieuw artikel, een gewijzigd
                      artikel, een herkenningsprobleem of de vastlegging dat de controle klaar is. Hier kunt u ze
                      bekijken; beslissen (goedkeuren of afkeuren) gebeurt in een publicatie.
                    </p>
                  </WhatIsThis>
                  <MutationList source={mutationSource} emptyMessage="Deze inlezing heeft (met deze filter) geen wijzigingen." />
                </div>
              </section>
            </Panel>
          )}

          {tab === 'delivery' && (
            <Panel tab="delivery" selected={tab}>
              <BatchDeliverySection deliveryId={data.deliveryId} />
            </Panel>
          )}
        </>
      )}
    </div>
  );
}
