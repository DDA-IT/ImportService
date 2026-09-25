/**
 * De levering achter een batch (A-F2): bestanden, verwachte versus werkelijke aantallen en of de
 * volledigheid bewezen is. Alleen-lezen. `completenessProven = false` wordt uitdrukkelijk getoond
 * (in de huidige fase altijd zo); verwachte waarden zijn `null` zonder manifest.
 */

import * as batchesApi from '../../api/batches.ts';
import { DataTable, type DataTableColumn } from '../../components/DataTable.tsx';
import type { DeliveryFileView } from '../../api/types.ts';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useQuery } from '../../hooks/useQuery.ts';
import { Count, formatDateTime } from './format.tsx';
import styles from './BatchDetailPage.module.css';

const FILE_COLUMNS: readonly DataTableColumn<DeliveryFileView>[] = [
  { key: 'sequenceNumber', header: '#', render: (f) => f.sequenceNumber, align: 'right' },
  { key: 'fileName', header: 'Bestand', render: (f) => f.fileName },
  { key: 'byteSize', header: 'Bytes', render: (f) => f.byteSize, align: 'right' },
  { key: 'hash', header: 'Hash', render: (f) => `${f.hashAlgorithm}: ${f.contentHash}` },
];

export function BatchDeliverySection({ deliveryId }: { deliveryId: number }) {
  const { data, error, loading } = useQuery(`delivery:${deliveryId}`, (signal) =>
    batchesApi.getDelivery(deliveryId, signal),
  );

  return (
    <section data-testid="batch-delivery">
      <h2 className={styles.sectionTitle}>Levering {deliveryId}</h2>
      {error !== null && <ErrorBanner error={error} />}
      {loading && data === null && error === null && <p className={styles.loading}>Bezig met laden…</p>}
      {data !== null && error === null && (
        <>
          <dl className={styles.facts}>
            <div className={styles.fact}>
              <dt>Ontvangen</dt>
              <dd>{formatDateTime(data.receivedAt)}</dd>
            </div>
            <div className={styles.fact}>
              <dt>Sleutel</dt>
              <dd>{data.idempotencyKey}</dd>
            </div>
            <div className={styles.fact}>
              <dt>Manifest</dt>
              <dd>{data.manifestReference ?? '—'}</dd>
            </div>
            <div className={styles.fact}>
              <dt>Bestanden (verwacht / werkelijk)</dt>
              <dd>
                <Count value={data.expectedFileCount} /> / {data.actualFileCount}
              </dd>
            </div>
            <div className={styles.fact}>
              <dt>Records (verwacht / werkelijk)</dt>
              <dd>
                <Count value={data.expectedRecordCount} /> / <Count value={data.actualRecordCount} />
              </dd>
            </div>
            <div className={styles.fact}>
              <dt>Bytes (verwacht / werkelijk)</dt>
              <dd>
                <Count value={data.expectedByteSize} /> / {data.actualByteSize}
              </dd>
            </div>
            <div className={styles.fact}>
              <dt>Volledigheid</dt>
              <dd>{data.completenessProven ? 'Bewezen' : 'Niet bewezen'}</dd>
            </div>
          </dl>
          <DataTable
            columns={FILE_COLUMNS}
            rows={data.files}
            rowKey={(f) => f.sequenceNumber}
            emptyMessage="Deze levering heeft geen bestanden."
          />
        </>
      )}
    </section>
  );
}
