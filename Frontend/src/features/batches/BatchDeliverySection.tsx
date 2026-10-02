/**
 * De levering achter een batch (A-F2): bestanden, verwachte versus werkelijke aantallen en of de
 * volledigheid bewezen is. Alleen-lezen. `completenessProven = false` wordt uitdrukkelijk getoond
 * (in de huidige fase altijd zo); verwachte waarden zijn `null` zonder manifest.
 *
 * NT-11a (V7): de sleutel van de levering en de vingerafdruk van elk bestand zijn technisch en staan onder
 * "Technische details (voor support)"; bovenaan staan enkel gewone woorden.
 */

import * as batchesApi from '../../api/batches.ts';
import { DataTable, type DataTableColumn } from '../../components/DataTable.tsx';
import type { DeliveryFileView } from '../../api/types.ts';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useQuery } from '../../hooks/useQuery.ts';
import { TechnicalDetails } from '../../terms/TechnicalDetails.tsx';
import { Count } from './format.tsx';
import styles from './BatchDetailPage.module.css';
import { formatDateTime } from '../../format.ts';

const FILE_COLUMNS: readonly DataTableColumn<DeliveryFileView>[] = [
  { key: 'sequenceNumber', header: '#', render: (f) => f.sequenceNumber, align: 'right' },
  { key: 'fileName', header: 'Bestand', render: (f) => f.fileName },
  { key: 'byteSize', header: 'Grootte in bytes', render: (f) => f.byteSize, align: 'right' },
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
              <dt>Begeleidend overzicht</dt>
              <dd>{data.manifestReference ?? '—'}</dd>
            </div>
            <div className={styles.fact}>
              <dt>Bestanden (verwacht / werkelijk)</dt>
              <dd>
                <Count value={data.expectedFileCount} /> / {data.actualFileCount}
              </dd>
            </div>
            <div className={styles.fact}>
              <dt>Regels (verwacht / werkelijk)</dt>
              <dd>
                <Count value={data.expectedRecordCount} /> / <Count value={data.actualRecordCount} />
              </dd>
            </div>
            <div className={styles.fact}>
              <dt>Grootte in bytes (verwacht / werkelijk)</dt>
              <dd>
                <Count value={data.expectedByteSize} /> / {data.actualByteSize}
              </dd>
            </div>
            <div className={styles.fact}>
              <dt>Volledigheid</dt>
              <dd>
                <span
                  title="Of zeker is dat de levering volledig aankwam. Dat kan pas met een begeleidend overzicht; zonder is het altijd 'Niet bewezen'."
                  style={{ textDecoration: 'underline dotted', cursor: 'help' }}
                >
                  {data.completenessProven ? 'Bewezen' : 'Niet bewezen'}
                </span>
              </dd>
            </div>
          </dl>
          <p className={styles.secondary}>
            Het begeleidend overzicht is een overzicht dat de aanleveraar meestuurt met wat er in de levering hoort te
            zitten. Zonder dat overzicht zijn de verwachte waarden niet vastgesteld (&quot;—&quot;).
          </p>
          <DataTable
            columns={FILE_COLUMNS}
            rows={data.files}
            rowKey={(f) => f.sequenceNumber}
            emptyMessage="Deze levering heeft geen bestanden."
          />
          <TechnicalDetails
            items={[
              { name: 'Sleutel van de levering', value: data.idempotencyKey },
              ...data.files.map((f) => ({
                name: `Vingerafdruk van bestand ${f.sequenceNumber} (${f.fileName})`,
                value: `${f.hashAlgorithm}: ${f.contentHash}`,
              })),
            ]}
          />
        </>
      )}
    </section>
  );
}
