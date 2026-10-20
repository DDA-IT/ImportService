/**
 * `/templates` — scherm 1b, sjabloon-/materialisatiewizard, alleen-lezen deel (S1-F2, `docs/decisions.md`
 * 2026-09-27 "scherm 1a/1b"). Toont de sjablonen (`usage_type = REUSABLE_TEMPLATE`) via het bestaande,
 * ongewijzigde vlaggedekte contract van `CatalogImportTemplateController`.
 *
 * Dit scherm blijft daarom volledig achter `catalogimport.setup-api.enabled` en is dus GEEN
 * productiewaardig beheerscherm (A1/A3 openen dit contract niet — zie de javadoc op die controller):
 * met de vlag uit geeft `GET /templates` een 404 zonder `code`, hier vertaald naar een eigen,
 * scherm-specifieke melding (`setupApiFlag.ts`) in plaats van de generieke foutmelding.
 *
 * Schrijfacties (materialiseren, bookmarkwaarde wijzigen) horen bij S1-F3 en staan hier niet.
 */
import { Link } from 'react-router-dom';
import { useState } from 'react';
import * as templatesApi from '../../api/templates.ts';
import { PERMISSION_READ, type TemplateView } from '../../api/types.ts';
import { useQuery } from '../../hooks/useQuery.ts';
import { usePermissionGate } from '../../actor/permissions.ts';
import { DataTable, type DataTableColumn } from '../../components/DataTable.tsx';
import { Pager } from '../../components/Pager.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { WhatIsThis } from '../../terms/WhatIsThis.tsx';
import { INVULPUNTEN } from '../../terms/wording.ts';
import { FlagOffNotice } from '../setup/FlagOffNotice.tsx';
import { isSetupApiDisabledError, SETUP_API_DISABLED_MESSAGE } from './setupApiFlag.ts';
import pageCss from '../../components/Page.module.css';
import styles from './TemplateListPage.module.css';

const COLUMNS: readonly DataTableColumn<TemplateView>[] = [
  {
    key: 'code',
    header: 'Sjabloon',
    render: (row) => (
      <Link to={`/templates/${row.id}`} className={styles.code}>
        {row.code}
      </Link>
    ),
  },
  { key: 'name', header: 'Naam', render: (row) => row.name },
  { key: 'source', header: 'Bron', render: (row) => `bron ${row.sourceOrganisationCode}` },
];

export function TemplateListPage() {
  const readGate = usePermissionGate(PERMISSION_READ);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);

  const key = `templates:${page}:${size}`;
  const templates = useQuery(key, (signal) => templatesApi.listTemplates({ page, size }, signal));

  if (!readGate.allowed) {
    return (
      <div className={styles.page}>
        <p role="alert">{readGate.reason}</p>
      </div>
    );
  }

  return (
    <div className={styles.page}>
      <header className={pageCss.pageHead}>
        <div className={pageCss.pageHeadText}>
          <h1 className={pageCss.title}>Sjablonen</h1>
          <ol className={pageCss.crumbs}>
            <li>
              <span>CatalogImport</span>
            </li>
            <li>
              <span>Inrichting</span>
            </li>
            <li>
              <span aria-current="page">Sjablonen</span>
            </li>
          </ol>
        </div>
      </header>
      <p className={styles.intro}>
        Alleen-lezen overzicht van herbruikbare sjablonen. Kies een sjabloon om de {INVULPUNTEN} van een
        versie te bekijken en te zien welke beschrijvingen van bestanden er al uit gemaakt zijn.
      </p>
      <WhatIsThis>
        Een sjabloon is een model waaruit u voor een leverancier een beschrijving van het bestand, een
        conceptversie en een koppeling maakt. Dat heet hier &laquo;materialiseren&raquo;. Het sjabloon zelf krijgt
        nooit een koppeling of levering.
      </WhatIsThis>

      {templates.error !== null &&
        (isSetupApiDisabledError(templates.error) ? (
          <FlagOffNotice message={SETUP_API_DISABLED_MESSAGE} className={styles.flagOff} />
        ) : (
          <ErrorBanner error={templates.error} />
        ))}
      {templates.loading && templates.data === null && <p className={styles.loading}>Bezig met laden…</p>}
      {templates.data !== null && (
        <div className={pageCss.card}>
          <div data-testid="templates">
            <DataTable
              columns={COLUMNS}
              rows={templates.data.content}
              rowKey={(row) => row.id}
              emptyMessage="Geen sjablonen gevonden."
            />
          </div>
          <Pager
            page={templates.data.page}
            size={templates.data.size}
            totalElements={templates.data.totalElements}
            onPageChange={setPage}
            onSizeChange={(next) => {
              setSize(next);
              setPage(0);
            }}
          />
        </div>
      )}
    </div>
  );
}
