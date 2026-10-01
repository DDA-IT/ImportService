/**
 * `/bundles/:bundleId/publication` — de SIMULATION-publicatierun van 5-PUB-a, zie
 * `docs/design/fase5-pub-design.md` en `docs/decisions.md` 2026-09-27 "Ontwerp bindend: Frontend
 * publicatierun (SIMULATION) op scherm (3)".
 *
 * NT-11b (V7): statussen via het woordenboek, geen ruwe codes in de zichtbare tekst; de foutcode, de servermelding en de
 * contractstatus staan onder "Technische details (voor support)".
 *
 * `POST /bundles/{id}/publication-runs` is **synchroon** (het antwoord is al de terminale status
 * `SIMULATED`/`FAILED`): geen polling. De runlijst is de audittrail, alleen-lezen, chronologisch,
 * zonder paginering. Buiten `FROZEN`: uitleg in plaats van de actie, geen crash, geen lege pagina.
 *
 * Herstel van een vastgelopen `PREPARING`-run (`docs/decisions.md` 2026-09-27, optie A): een "Afbreken"-
 * knop per `PREPARING`-run, gated op `APPROVE` (zelfde patroon als "Simulatierun starten"). Geen
 * tijdsvoorwaarde: de knop staat aan zodra de status `PREPARING` is.
 */

import { useState } from 'react';
import * as runsApi from '../../api/publicationRuns.ts';
import { apiUrl, ApiError } from '../../api/http.ts';
import { PERMISSION_APPROVE, type PublicationRunView } from '../../api/types.ts';
import { usePermissionGate, withPermission } from '../../actor/permissions.ts';
import { useQuery } from '../../hooks/useQuery.ts';
import { StatusBadge } from '../../components/StatusBadge.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { TechnicalDetails } from '../../terms/TechnicalDetails.tsx';
import { term } from '../../terms/index.ts';
import { WhatIsThis } from '../../terms/WhatIsThis.tsx';
import { abortRunGate, gateTitle, publicationRunGate } from './bundlePolicy.ts';
import { useBundleDetailContext } from './BundleDetailPage.tsx';
import styles from './BundlePublicationTab.module.css';

/** "—" met een tooltip voor een niet-vastgestelde teller (`null`), nooit `0`. */
function Count({ value }: { value: number | null }) {
  if (value === null) {
    return (
      <span title="niet vastgesteld" style={{ textDecoration: 'underline dotted', cursor: 'help' }}>
        —
      </span>
    );
  }
  return <>{value}</>;
}

function formatDateTime(iso: string | null): string {
  return iso === null ? '—' : new Date(iso).toLocaleString('nl-BE');
}

export function BundlePublicationTab() {
  const { bundle } = useBundleDetailContext();
  const [starting, setStarting] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [startError, setStartError] = useState<ApiError | null>(null);
  const [copiedRunId, setCopiedRunId] = useState<number | null>(null);
  const [abortingRunId, setAbortingRunId] = useState<number | null>(null);
  const [abortError, setAbortError] = useState<ApiError | null>(null);

  const key = `bundle-publication-runs:${bundle.id}`;
  const {
    data: runs,
    error: listError,
    loading,
    reload,
  } = useQuery(key, (signal) => runsApi.listRuns(bundle.id, signal));

  const approveGate = usePermissionGate(PERMISSION_APPROVE);
  const startGate = withPermission(approveGate, publicationRunGate(bundle.status, runs));

  async function handleStart() {
    setStarting(true);
    setStartError(null);
    setNotice(null);
    try {
      const run = await runsApi.requestRun(bundle.id, { targetMode: 'SIMULATION' });
      setNotice(
        run.status === 'SIMULATED'
          ? `Publicatierun #${run.id} is geslaagd (status "${term('publicationRunStatus', run.status).label}"); er is niets naar Prodis geschreven.`
          : `Publicatierun #${run.id} is mislukt (status "${term('publicationRunStatus', run.status).label}").`,
      );
      reload();
    } catch (cause) {
      if (cause instanceof ApiError) {
        setStartError(cause);
      } else {
        throw cause;
      }
    } finally {
      setStarting(false);
    }
  }

  async function handleAbort(run: PublicationRunView) {
    setAbortingRunId(run.id);
    setAbortError(null);
    try {
      await runsApi.abortRun(run.id);
      reload();
    } catch (cause) {
      if (cause instanceof ApiError) {
        setAbortError(cause);
      } else {
        throw cause;
      }
    } finally {
      setAbortingRunId(null);
    }
  }

  async function handleCopyHash(run: PublicationRunView) {
    if (run.artifactSha256 === null) {
      return;
    }
    try {
      await navigator.clipboard.writeText(run.artifactSha256);
      setCopiedRunId(run.id);
      setTimeout(() => setCopiedRunId(null), 2000);
    } catch {
      // Klembord kan geweigerd zijn; de hash blijft nog altijd zichtbaar op het scherm.
    }
  }

  const whatIsThis = (
    <WhatIsThis>
      <p>
        Een bevroren bundel kan hier gepubliceerd worden. Op dit moment kan dat alleen als proefpublicatie (simulatie): het
        bestand dat naar Prodis zou gaan, wordt opgebouwd en bewaard, maar er wordt niets echt aangepast. Zo ziet u vooraf
        wat er zou gebeuren.
      </p>
      <p>
        Elke poging is een publicatierun. U kunt een run met status &quot;{term('publicationRunStatus', 'PREPARING').label}
        &quot; die vastzit afbreken.
      </p>
    </WhatIsThis>
  );

  if (bundle.status !== 'FROZEN') {
    return (
      <div className={styles.tab}>
        {whatIsThis}
        <p className={styles.notice}>
          Een publicatierun kan alleen gestart worden voor een bevroren bundel (status &quot;
          {term('bundleStatus', 'FROZEN').label}&quot;). Deze bundel heeft status &quot;
          {term('bundleStatus', bundle.status).label}&quot;.
        </p>
      </div>
    );
  }

  return (
    <div className={styles.tab}>
      {whatIsThis}
      <div className={styles.banner} role="note">
        <p>
          Dit is een proefpublicatie: er wordt niets naar Prodis geschreven. De velden van het bestand zijn nog niet door
          Prodis bevestigd, dus het resultaat is een voorbeeld en nog geen definitief importbestand.
        </p>
        <TechnicalDetails
          items={[
            { name: 'Afspraken met Prodis over de velden', value: 'UNVERIFIED_FIELD_INVENTORY' },
            { name: 'Schrijft naar Prodis', value: 'false' },
          ]}
        />
      </div>

      <section className={styles.section}>
        <h2 className={styles.sectionTitle}>Nieuwe proefpublicatie</h2>
        <div className={styles.actionRow}>
          <button
            type="button"
            className={styles.actionButton}
            disabled={!startGate.allowed || starting}
            title={gateTitle(startGate)}
            onClick={handleStart}
          >
            {starting ? 'Bezig…' : 'Proefpublicatie starten'}
          </button>
          {!startGate.allowed && <p className={styles.actionReason}>{startGate.reason}</p>}
        </div>

        {notice !== null && (
          <p className={styles.resultNotice} role="status">
            {notice}
          </p>
        )}
        {startError !== null && <ErrorBanner error={startError} />}
      </section>

      <section className={styles.section}>
        <h2 className={styles.sectionTitle}>Publicatieruns</h2>
        {listError !== null && <ErrorBanner error={listError} />}
        {abortError !== null && <ErrorBanner error={abortError} />}
        {loading && runs === null && <p className={styles.loading}>Bezig met laden…</p>}
        {runs !== null && runs.length === 0 && (
          <p className={styles.notice}>Deze bundel heeft nog geen publicatierun.</p>
        )}
        {runs !== null && runs.length > 0 && (
          <ul className={styles.runList}>
            {runs.map((run) => {
              const abortGate = withPermission(approveGate, abortRunGate(run));
              return (
              <li key={run.id} className={styles.run}>
                <div className={styles.runHeader}>
                  <span>
                    #{run.id} — poging {run.attempt}
                  </span>
                  <StatusBadge status={run.status} domain="publicationRunStatus" />
                  <span>
                    {formatDateTime(run.requestedAt)} door {run.requestedBy}
                  </span>
                </div>

                {run.status === 'PREPARING' && (
                  <div className={styles.actionRow}>
                    <button
                      type="button"
                      className={styles.actionButton}
                      disabled={!abortGate.allowed || abortingRunId === run.id}
                      title={gateTitle(abortGate)}
                      onClick={() => handleAbort(run)}
                    >
                      {abortingRunId === run.id ? 'Bezig…' : 'Afbreken'}
                    </button>
                  </div>
                )}

                <dl className={styles.runCounts}>
                  <div>
                    <dt>Rijen</dt>
                    <dd>
                      <Count value={run.rowCount} />
                    </dd>
                  </div>
                  <div>
                    <dt>Onvolledige rijen</dt>
                    <dd>
                      <Count value={run.incompleteRowCount} />
                    </dd>
                  </div>
                </dl>

                {run.incompleteRowCount !== null && run.incompleteRowCount > 0 && (
                  <p className={styles.incompleteWarning}>
                    {run.incompleteRowCount} regel(en) zijn onvolledig (er ontbreken gegevens van de mutatie of een
                    onderdeel van de prijs); zie het voorbeeld van het importbestand voor detail.
                  </p>
                )}

                {run.status === 'SIMULATED' && (
                  <div className={styles.artifact}>
                    <p>
                      Vingerafdruk van het bestand (SHA-256): <code>{run.artifactSha256}</code>{' '}
                      <button type="button" className={styles.copyButton} onClick={() => handleCopyHash(run)}>
                        {copiedRunId === run.id ? 'Gekopieerd' : 'Kopieer de vingerafdruk'}
                      </button>
                    </p>
                    <p>Grootte: {run.artifactByteSize} byte(s)</p>
                    <p>
                      <a href={apiUrl(`/publication-runs/${run.id}/artifact`)}>
                        Download het bestand van deze run (CSV)
                      </a>
                    </p>
                    <p>
                      <a href={apiUrl(`/bundles/${bundle.id}/psimport-preview?format=csv`)}>
                        Bekijk het volledige voorbeeld van het importbestand voor Prodis (CSV)
                      </a>
                    </p>
                  </div>
                )}

                {run.status === 'FAILED' && (
                  <div className={styles.failure}>
                    <p>
                      Deze publicatierun is technisch mislukt of afgebroken. Er is niets naar Prodis geschreven; u kunt
                      opnieuw een proefpublicatie starten.
                    </p>
                    <TechnicalDetails
                      items={[
                        { name: 'Foutcode', value: run.failureCode ?? '—' },
                        { name: 'Melding van de server', value: run.failureMessage ?? '—' },
                      ]}
                    />
                  </div>
                )}
              </li>
              );
            })}
          </ul>
        )}
      </section>
    </div>
  );
}
