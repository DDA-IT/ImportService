/**
 * De ontvangen bundel zonder secret: interface-instellingen, planning zoals legacy ze had en de importsoort
 * (omschrijving, aantal regels, omzettingen en uitsluitingen). Enkel gelezen velden worden getoond; `bundle.secret`
 * wordt nooit gerenderd, ook niet als een antwoord het (ten onrechte) zou bevatten. Geheime labels staan hier enkel bij
 * naam, nooit met een waarde.
 */

import type { LegacyBundle, LegacyReceiptDetail } from '../../api/types.ts';
import { DataTable, type DataTableColumn } from '../../components/DataTable.tsx';
import { Term } from '../../terms/Term.tsx';
import { TechnicalDetails } from '../../terms/TechnicalDetails.tsx';
import styles from './LegacyConfig.module.css';

type Setting = { sequence: number; label: string; value: string };

/**
 * Vangnet: de backend laat geheime labels nooit mee (`omittedSecretLabels`), maar komt er toch zo'n label met een waarde
 * binnen, dan toont dit scherm de waarde niet. Zelfde patroon als de bundelparser.
 */
const SECRET_LABEL = /pass|secret|apikey|api_key|token|_pw$/i;
const HIDDEN_VALUE = '(verborgen)';

const SETTING_COLUMNS: readonly DataTableColumn<Setting>[] = [
  { key: 'label', header: 'Instelling', render: (row) => row.label },
  { key: 'value', header: 'Waarde', render: (row) => (row.value === '' ? '—' : row.value) },
];

function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className={styles.fact}>
      <dt>{label}</dt>
      <dd>{children}</dd>
    </div>
  );
}

function yesNo(value: boolean | undefined): string {
  return value === undefined ? '—' : value ? 'ja' : 'nee';
}

function show(value: string | number | undefined | null): string {
  return value === undefined || value === null || value === '' ? '—' : String(value);
}

function Interface({ bundle }: { bundle: LegacyBundle }) {
  const iface = bundle.interface;
  const header = iface?.header;
  const settings: Setting[] = (iface?.details ?? []).map((detail) => ({
    sequence: detail.sequence,
    label: detail.label,
    value: SECRET_LABEL.test(detail.label) ? HIDDEN_VALUE : detail.value,
  }));
  return (
    <>
      <dl className={styles.facts} data-testid="legacy-interface-facts">
        <Fact label="Interface">{show(iface?.code)}</Fact>
        <Fact label="Richting">{show(header?.direction)}</Fact>
        <Fact label="Soort">{show(header?.importExportType)}</Fact>
        <Fact label="Automatisch uitvoeren in legacy">{yesNo(header?.executeAutomatic)}</Fact>
        <Fact label="Frequentie in legacy">{show(header?.executeEvery)}</Fact>
        <Fact label="Uitvoeringstijd in legacy">{show(header?.executionTime)}</Fact>
        <Fact label="Omschrijving">{show(header?.info)}</Fact>
      </dl>
      {iface?.ftpCredentialsRedacted === true && (
        <p className={styles.notice}>
          In de serveradres van de interface zat een gebruikersnaam met wachtwoord; die zijn weggelaten.
        </p>
      )}
      <h4 className={styles.subTitle}>Instellingen van de interface ({settings.length})</h4>
      <DataTable
        columns={SETTING_COLUMNS}
        rows={settings}
        rowKey={(row) => `${row.sequence}:${row.label}`}
        emptyMessage="Deze interface heeft geen instellingen."
      />
      {(iface?.omittedSecretLabels ?? []).length > 0 && (
        <>
          <h4 className={styles.subTitle}>Geheime instellingen die nooit worden doorgegeven</h4>
          <ul className={styles.plainList} data-testid="legacy-omitted-secrets">
            {(iface?.omittedSecretLabels ?? []).map((label) => (
              <li key={label}>{label}</li>
            ))}
          </ul>
        </>
      )}
    </>
  );
}

function Definition({ bundle }: { bundle: LegacyBundle }) {
  const definition = bundle.definition;
  return (
    <>
      <dl className={styles.facts} data-testid="legacy-definition-facts">
        <Fact label="Staat">
          <Term domain="legacyDefinitionStatus" code={bundle.definitionStatus} unknownLabel="Andere staat" />
        </Fact>
        {definition !== null && definition !== undefined && (
          <>
            <Fact label="Importsoort">{show(definition.code)}</Fact>
            <Fact label="Omschrijving">{show(definition.description)}</Fact>
            <Fact label="Aantal regels">{definition.lines?.length ?? 0}</Fact>
            <Fact label="Aantal omzettingen">{definition.maps?.length ?? 0}</Fact>
            <Fact label="Aantal uitsluitingen">{definition.excludes?.length ?? 0}</Fact>
          </>
        )}
      </dl>
    </>
  );
}

export function BundleSummary({ detail }: { detail: LegacyReceiptDetail }) {
  const { bundle } = detail;
  if (bundle === null) {
    return <p className={styles.muted}>Deze ontvangst heeft geen leesbare bundel.</p>;
  }
  return (
    <>
      <Interface bundle={bundle} />
      <h4 className={styles.subTitle}>Importsoort</h4>
      <Definition bundle={bundle} />
      <TechnicalDetails
        items={[
          { name: 'Inhoudscontrolegetal', value: detail.receipt.contentHash },
          ...(detail.receipt.definitionContentHash === null
            ? []
            : [{ name: 'Controlegetal van de importsoort', value: detail.receipt.definitionContentHash }]),
          { name: 'Versie van het formaat', value: String(detail.receipt.bundleVersion) },
        ]}
      />
    </>
  );
}
