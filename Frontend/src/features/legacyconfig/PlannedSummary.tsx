/**
 * "Wat wordt aangemaakt of hergebruikt": de duidelijke samenvatting van `report.planned` (sjabloon, gedeelde
 * beschrijving, koppeling, taak, leverancier, wachtwoordverwijzing), met `sharedWith` en de Prodis-controle. Wordt
 * zowel in de controle vooraf als in de bevestigingsdialoog getoond, zodat de mens ondertekent wat hij zag.
 *
 * Een wachtwoord staat hier nooit: enkel of er een verwijzing is en wat er bij ontvangst mee gebeurde.
 */

import type { ReactNode } from 'react';
import type { LegacyConfigReport } from '../../api/types.ts';
import { Term } from '../../terms/Term.tsx';
import styles from './LegacyConfig.module.css';

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className={styles.plannedRow}>
      <dt>{label}</dt>
      <dd>{children}</dd>
    </div>
  );
}

function Action({ code }: { code: string }) {
  return <Term domain="legacyAction" code={code} unknownLabel="Andere actie" />;
}

export function PlannedSummary({ report }: { report: LegacyConfigReport }) {
  const { planned } = report;
  return (
    <dl className={styles.planned} data-testid="legacy-planned">
      {planned.sourceOrganisation !== null && (
        <Row label="Leverancier">
          <Action code={planned.sourceOrganisation.action} />
          <span>
            {planned.sourceOrganisation.code}
            {planned.sourceOrganisation.name !== null && ` — ${planned.sourceOrganisation.name}`}
          </span>
        </Row>
      )}
      {planned.template !== undefined && planned.template !== null && (
        <Row label="Sjabloon">
          <Action code={planned.template.action} />
          {planned.template.action !== 'NONE' && <span>{planned.template.code}</span>}
        </Row>
      )}
      {planned.definition !== null && (
        <Row label="Gedeelde beschrijving van het bestand">
          <Action code={planned.definition.action} />
          <span>{planned.definition.code}</span>
          <span>
            Versie: <Term domain="legacyAction" code={planned.definition.revisionAction} unknownLabel="Andere actie" />
          </span>
        </Row>
      )}
      {planned.detach !== undefined && planned.detach !== null && (
        <Row label="Loskoppelen">
          <span data-testid="legacy-detach">
            De interface verlaat de gedeelde beschrijving <strong>{planned.detach.fromDefinitionCode}</strong> en krijgt een
            eigen beschrijving <strong>{planned.detach.toDefinitionCode}</strong>. De andere interfaces blijven de gedeelde
            beschrijving volgen.
          </span>
          <span>
            {planned.detach.carriesActive
              ? 'De huidige actieve versie van de gedeelde beschrijving wordt meteen als eigen versie actief; de nieuwe inhoud van legacy wordt een conceptversie.'
              : 'De gedeelde beschrijving heeft geen actieve versie; de eigen beschrijving start met een conceptversie met de nieuwe inhoud.'}
          </span>
        </Row>
      )}
      {planned.link !== null && (
        <Row label="Koppeling">
          <Action code={planned.link.action} />
          <span>{planned.link.code}</span>
          <span>Bibliotheek: {planned.link.libraryCode ?? 'nog niet bepaald'}</span>
        </Row>
      )}
      {planned.task !== null && (
        <Row label="Taak">
          <Action code={planned.task.action} />
          <span>{planned.task.name}</span>
        </Row>
      )}
      {planned.credential !== null && (
        <Row label="Wachtwoord">
          <span>{planned.credential.credentialRef !== null ? 'Verwijzing aanwezig' : 'Geen verwijzing'}</span>
          <span>
            <Term domain="legacySecretOutcome" code={planned.credential.secretOutcome} unknownLabel="Andere uitkomst" />
          </span>
        </Row>
      )}
      <Row label="Gedeeld met">
        {report.sharedWith.length === 0 ? 'Geen andere interfaces' : report.sharedWith.join(', ')}
      </Row>
      <Row label="Controle tegen Prodis">
        <Term domain="legacyProdisReference" code={report.prodisReference} unknownLabel="Andere uitkomst" />
      </Row>
    </dl>
  );
}
