/**
 * `/bundles/:bundleId/publication` — de SIMULATION-publicatierun van 5-PUB-a, zie
 * `docs/design/fase5-pub-design.md` en `docs/decisions.md` 2026-09-27 "Ontwerp bindend: Frontend
 * publicatierun (SIMULATION) op scherm (3)".
 *
 * `POST /bundles/{id}/publication-runs` is **synchroon** (het antwoord is al de terminale status
 * `SIMULATED`/`FAILED`): geen polling. De runlijst is de audittrail, alleen-lezen, chronologisch,
 * zonder paginering. Buiten `FROZEN`: uitleg in plaats van de actie, geen crash, geen lege pagina.
 */

import { useState } from 'react';
import * as runsApi from '../../api/publicationRuns.ts';
import { apiUrl, ApiError } from '../../api/http.ts';
import { PERMISSION_APPROVE, type PublicationRunView } from '../../api/types.ts';
import { usePermissionGate, withPermission } from '../../actor/permissions.ts';
import { useQuery } from '../../hooks/useQuery.ts';
import { StatusBadge } from '../../components/StatusBadge.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { publicationRunGate } from './bundlePolicy.ts';
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
          ? `Publicatierun #${run.id} is geslaagd (status SIMULATED).`
          : `Publicatierun #${run.id} is mislukt (status ${run.status}).`,
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

  if (bundle.status !== 'FROZEN') {
    return (
      <div className={styles.tab}>
        <p className={styles.notice}>
          Een publicatierun kan alleen gestart worden voor een bevroren bundel (status FROZEN). Deze
          bundel heeft status {bundle.status}.
        </p>
      </div>
    );
  }

  return (
    <div className={styles.tab}>
      <p className={styles.banner} role="note">
        Dit is een niet-contractuele simulatie (contractStatus: UNVERIFIED_FIELD_INVENTORY). Er wordt
        niets naar ProDisWebbase/PSIMPORT geschreven (writesToProdis: false).
      </p>

      <section className={styles.section}>
        <h2 className={styles.sectionTitle}>Nieuwe run</h2>
        <div className={styles.actionRow}>
          <button
            type="button"
            className={styles.actionButton}
            disabled={!startGate.allowed || starting}
            title={startGate.allowed ? undefined : startGate.reason}
            onClick={handleStart}
          >
            {starting ? 'Bezig…' : 'Simulatierun starten'}
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
        {loading && runs === null && <p className={styles.loading}>Bezig met laden…</p>}
        {runs !== null && runs.length === 0 && (
          <p className={styles.notice}>Deze bundel heeft nog geen publicatierun.</p>
        )}
        {runs !== null && runs.length > 0 && (
          <ul className={styles.runList}>
            {runs.map((run) => (
              <li key={run.id} className={styles.run}>
                <div className={styles.runHeader}>
                  <span>
                    #{run.id} — poging {run.attempt}
                  </span>
                  <StatusBadge status={run.status} />
                  <span>
                    {formatDateTime(run.requestedAt)} door {run.requestedBy}
                  </span>
                </div>

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
                    {run.incompleteRowCount} regel(en) zijn onvolledig (NOT_SNAPSHOTTED of ontbrekende
                    prijscomponent); zie de PSIMPORT-preview voor detail.
                  </p>
                )}

                {run.status === 'SIMULATED' && (
                  <div className={styles.artifact}>
                    <p>
                      SHA-256: <code>{run.artifactSha256}</code>{' '}
                      <button type="button" className={styles.copyButton} onClick={() => handleCopyHash(run)}>
                        {copiedRunId === run.id ? 'Gekopieerd' : 'Kopieer hash'}
                      </button>
                    </p>
                    <p>Grootte: {run.artifactByteSize} byte(s)</p>
                    <p>
                      <a href={apiUrl(`/publication-runs/${run.id}/artifact`)}>
                        Download het runartefact (CSV)
                      </a>
                    </p>
                    <p>
                      <a href={apiUrl(`/bundles/${bundle.id}/psimport-preview?format=csv`)}>
                        Bekijk de volledige PSIMPORT-preview (CSV)
                      </a>
                    </p>
                  </div>
                )}

                {run.status === 'FAILED' && (
                  <div className={styles.failure}>
                    <p>Foutcode: {run.failureCode ?? '—'}</p>
                    <p>{run.failureMessage ?? '—'}</p>
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
