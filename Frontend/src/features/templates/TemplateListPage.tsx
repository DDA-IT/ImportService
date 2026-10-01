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
import { PERMISSION_READ } from '../../api/types.ts';
import { useQuery } from '../../hooks/useQuery.ts';
import { usePermissionGate } from '../../actor/permissions.ts';
import { Pager } from '../../components/Pager.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { WhatIsThis } from '../../terms/WhatIsThis.tsx';
import { INVULPUNTEN } from '../../terms/wording.ts';
import { FlagOffNotice } from '../setup/FlagOffNotice.tsx';
import { isSetupApiDisabledError, SETUP_API_DISABLED_MESSAGE } from './setupApiFlag.ts';
import styles from './TemplateListPage.module.css';

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
      <h1 className={styles.title}>Sjablonen</h1>
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
        <>
          {templates.data.content.length === 0 ? (
            <p className={styles.empty}>Geen sjablonen gevonden.</p>
          ) : (
            <ul className={styles.list} data-testid="templates">
              {templates.data.content.map((template) => (
                <li key={template.id} className={styles.listItem}>
                  <Link to={`/templates/${template.id}`} className={styles.code}>
                    {template.code}
                  </Link>
                  <span>{template.name}</span>
                  <span>bron {template.sourceOrganisationCode}</span>
                </li>
              ))}
            </ul>
          )}
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
        </>
      )}
    </div>
  );
}
