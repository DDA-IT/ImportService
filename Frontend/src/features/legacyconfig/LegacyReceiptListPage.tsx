/**
 * `/setup/legacy-config` — de ontvangsten van legacy-configuratie (S9, `docs/design/legacy-config-bundle-design.md` §2):
 * `GET /legacy-config/receipts` met filter op status en interface en paginering. Alleen-lezen; een rij opent het detail
 * waar de controle vooraf en het overnemen staan. Het wachtwoord staat er enkel als label van wat ermee gebeurde
 * (`secretOutcome`), nooit als waarde.
 *
 * Alle `/legacy-config`-endpoints vragen `MANAGE` (de bundel toont host en login). Zonder dat recht staat hier enkel de
 * reden, en wordt er niets opgehaald; het menu-item is dan ook verborgen.
 */

import { useState } from 'react';
import { Link } from 'react-router-dom';
import { usePermissionGate } from '../../actor/permissions.ts';
import * as legacyConfigApi from '../../api/legacyConfig.ts';
import { LEGACY_RECEIPT_STATUSES, PERMISSION_MANAGE } from '../../api/types.ts';
import type { LegacyReceiptRow, LegacyReceiptStatus } from '../../api/types.ts';
import { DataTable, type DataTableColumn } from '../../components/DataTable.tsx';
import { Field } from '../../components/Field.tsx';
import { Pager } from '../../components/Pager.tsx';
import { StatusBadge } from '../../components/StatusBadge.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { formatDateTime } from '../../format.ts';
import { useQuery } from '../../hooks/useQuery.ts';
import { Term } from '../../terms/Term.tsx';
import { WhatIsThis } from '../../terms/WhatIsThis.tsx';
import { term } from '../../terms/index.ts';
import pageCss from '../../components/Page.module.css';
import styles from './LegacyConfig.module.css';

const NO_STATUS_FILTER = '';
type StatusFilter = LegacyReceiptStatus | typeof NO_STATUS_FILTER;

const COLUMNS: readonly DataTableColumn<LegacyReceiptRow>[] = [
  {
    key: 'interfaceCode',
    header: 'Interface',
    render: (row) => <Link to={`/setup/legacy-config/${row.id}`}>{row.interfaceCode}</Link>,
  },
  { key: 'origin', header: 'Herkomst', render: (row) => `${row.originEnvironment} / ${row.originDossier}` },
  { key: 'status', header: 'Status', render: (row) => <StatusBadge status={row.status} domain="legacyReceiptStatus" /> },
  { key: 'receivedAt', header: 'Ontvangen op', render: (row) => formatDateTime(row.receivedAt) },
  {
    key: 'secretOutcome',
    header: 'Wachtwoord',
    render: (row) => <Term domain="legacySecretOutcome" code={row.secretOutcome} unknownLabel="Andere uitkomst" />,
  },
];

function Crumbs() {
  return (
    <ol className={pageCss.crumbs}>
      <li>
        <span>CatalogImport</span>
      </li>
      <li>
        <span>Inrichting</span>
      </li>
      <li>
        <span aria-current="page">Overname uit legacy</span>
      </li>
    </ol>
  );
}

function ReceiptList() {
  const [status, setStatus] = useState<StatusFilter>(NO_STATUS_FILTER);
  const [interfaceCode, setInterfaceCode] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);

  const trimmedInterface = interfaceCode.trim();
  const listKey = `legacy-receipts:${status}:${trimmedInterface}:${page}:${size}`;
  const list = useQuery(listKey, (signal) =>
    legacyConfigApi.listLegacyReceipts(
      {
        status: status === NO_STATUS_FILTER ? undefined : status,
        interfaceCode: trimmedInterface === '' ? undefined : trimmedInterface,
        page,
        size,
      },
      signal,
    ),
  );

  return (
    <div className={pageCss.card}>
      <div className={`${pageCss.toolbar} ${styles.toolbar}`} role="search">
        <Field label="Status" htmlFor="legacy-status-filter">
          <select
            id="legacy-status-filter"
            value={status}
            onChange={(event) => {
              setStatus(event.target.value as StatusFilter);
              setPage(0);
            }}
          >
            <option value={NO_STATUS_FILTER}>Alle</option>
            {LEGACY_RECEIPT_STATUSES.map((option) => (
              <option key={option} value={option}>
                {term('legacyReceiptStatus', option).label}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Interface" htmlFor="legacy-interface-filter" hint="Exacte code van de interface.">
          <input
            id="legacy-interface-filter"
            type="text"
            value={interfaceCode}
            onChange={(event) => {
              setInterfaceCode(event.target.value);
              setPage(0);
            }}
          />
        </Field>
      </div>

      {list.error !== null && <ErrorBanner error={list.error} />}
      {list.loading && list.data === null && <p className={styles.loading}>Bezig met laden…</p>}

      {list.data !== null && (
        <>
          <DataTable
            columns={COLUMNS}
            rows={list.data.content}
            rowKey={(row) => row.id}
            emptyMessage="Geen ontvangsten gevonden."
          />
          <Pager
            page={list.data.page}
            size={list.data.size}
            totalElements={list.data.totalElements}
            onPageChange={setPage}
            onSizeChange={(next) => {
              setSize(next);
              setPage(0);
            }}
          />
        </>
      )}
    </div>
  );
}

export function LegacyReceiptListPage() {
  const manage = usePermissionGate(PERMISSION_MANAGE);
  return (
    <div className={styles.page}>
      <header className={pageCss.pageHead}>
        <div className={pageCss.pageHeadText}>
          <h1 className={pageCss.title}>Overname uit legacy</h1>
          <Crumbs />
        </div>
      </header>
      <WhatIsThis>
        De datasync stuurt de instellingen van elke legacy-interface naar CatalogImport. Elke keer dat ze veranderd zijn,
        verschijnt hier een nieuwe ontvangst. Open een ontvangst om te zien wat er zou gebeuren en om ze zelf over te
        nemen. Er wordt niets automatisch overgenomen of geactiveerd, en een wachtwoord wordt nergens getoond.
      </WhatIsThis>
      {manage.allowed ? (
        <ReceiptList />
      ) : (
        <div className={`${pageCss.card} ${styles.noPermission}`} data-testid="legacy-no-permission">
          <p>{manage.reason}</p>
          <p>Voor de overname van legacy-configuratie is dit recht nodig, omdat de ontvangen gegevens servers en logins tonen.</p>
        </div>
      )}
    </div>
  );
}
