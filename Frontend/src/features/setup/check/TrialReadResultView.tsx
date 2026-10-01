/**
 * NT-10 blok 2 — het resultaat van één proefinlezing (`TrialReadResult`, contract `docs/design/proefinlezing-design.md`
 * §2), in gewoon Nederlands. Het resultaat bestaat enkel in de toestand van de pagina: er is niets opgeslagen.
 *
 * Vaste regels:
 * - Een teller `null` is "niet vastgesteld": "—" met tooltip, nooit 0 (`Count`).
 * - Prijzen staan zoals in het bestand én zoals begrepen; er wordt niets gecorrigeerd of afgerond (principe 8).
 * - Geen enkele code als hoofdtekst (V7): het Nederlandse woord vooraan, de code enkel onder "Technische details" of als
 *   tooltip.
 */
import type { ReactNode } from 'react';
import type {
  TrialCounters,
  TrialHeader,
  TrialIssueGroup,
  TrialReadResult,
  TrialSampleIssue,
  TrialSampleRow,
  TrialThresholds,
} from '../../../api/types.ts';
import { StatusBadge } from '../../../components/StatusBadge.tsx';
import { Term } from '../../../terms/Term.tsx';
import { TechnicalDetails } from '../../../terms/TechnicalDetails.tsx';
import { WhatIsThis } from '../../../terms/WhatIsThis.tsx';
import { term } from '../../../terms/index.ts';
import { Count } from '../../batches/format.tsx';
import { describeFindingField, SKIPPED_CODE, SkippedCauses } from './configFindings.tsx';
import { describeIssueCode } from './linkCheck.ts';
import styles from './LinkCheckPage.module.css';

const MAIN_COUNTERS = [
  'rawRecordCount',
  'validRecordCount',
  'rejectedRecordCount',
  'filteredOutCount',
  'duplicateIdentityCount',
  'criticalLineCount',
] as const;

const EXTRA_COUNTERS = [
  'scopeRecordCount',
  'errorBeforeFilterCount',
  'physicalLineCount',
  'prefixLineCount',
  'skippedBlankLineCount',
  'columnCount',
  'linesWithReplacementCharacter',
] as const;

const SEVERITIES = ['CRITICAL', 'ERROR', 'WARNING', 'INFO'] as const;

/** Tekst of "—" (niet vastgesteld); nooit een verzonnen waarde. */
function text(value: string | number | null | undefined): string {
  return value === null || value === undefined ? '—' : String(value);
}

function percent(value: string | null): string {
  return value === null ? '—' : `${value} %`;
}

function formatByteSize(byteSize: number): string {
  if (byteSize < 1024) {
    return `${byteSize} bytes`;
  }
  const units = ['kB', 'MB', 'GB'];
  let value = byteSize / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value.toFixed(1)} ${units[unit]}`;
}

function Tile({ label, uitleg, value }: { label: string; uitleg: string; value: number | null }) {
  return (
    <div className={styles.tile}>
      <dt>{label}</dt>
      <dd className={styles.tileValue}>
        <Count value={value} />
      </dd>
      <dd className={styles.tileUitleg}>{uitleg}</dd>
    </div>
  );
}

/** Het oordeel in woorden, met de Nederlandse reden bij een blokkade. */
function Verdict({ result }: { result: TrialReadResult }) {
  const { verdict } = result;
  if (verdict.result === 'NO_BLOCKER_FOUND') {
    return (
      <div className={styles.verdictOk} data-testid="trial-verdict">
        <h3 className={styles.verdictTitle}>✓ Deze levering zou aanvaard worden</h3>
        <p className={styles.verdictBody}>{term('trialVerdict', 'NO_BLOCKER_FOUND').uitleg}</p>
        <p className={styles.verdictBody}>
          Een proef kan niet alles controleren (zie &laquo;Niet gecontroleerd in een proef&raquo; hieronder). De
          eerste echte levering blijft de laatste controle.
        </p>
      </div>
    );
  }
  const reason = describeIssueCode(verdict.blockedCode);
  const isThreshold = verdict.stage === 'THRESHOLD';
  return (
    <div className={styles.verdictBlock} data-testid="trial-verdict">
      <h3 className={styles.verdictTitle}>✗ Deze levering zou tegengehouden worden omdat: {reason.label}</h3>
      <p className={styles.verdictBody}>{reason.uitleg}</p>
      {verdict.stage !== null && (
        <p className={styles.verdictBody}>
          Gevonden bij: <Term domain="trialStage" code={verdict.stage} />
        </p>
      )}
      {!isThreshold && verdict.fieldName !== null && <p className={styles.verdictBody}>Kolom: {verdict.fieldName}</p>}
      {!isThreshold && verdict.sourceValue !== null && (
        <p className={styles.verdictBody}>In het bestand: {verdict.sourceValue}</p>
      )}
      {!isThreshold && verdict.expectedValue !== null && (
        <p className={styles.verdictBody}>Verwacht: {verdict.expectedValue}</p>
      )}
      {isThreshold && <p className={styles.verdictBody}>De cijfers staan hieronder bij &laquo;Grenzen&raquo;.</p>}
      <TechnicalDetails
        items={[
          { name: 'Code', value: text(verdict.blockedCode) },
          { name: 'Stap', value: text(verdict.stage) },
          { name: 'Toelichting', value: text(verdict.blockedReason) },
        ]}
      />
    </div>
  );
}

function ConfigProblems({ result }: { result: TrialReadResult }) {
  if (result.configProblems.length === 0) {
    return null;
  }
  const skipped = result.configChecksSkippedBecause ?? [];
  return (
    <div className={styles.warning} data-testid="trial-config-problems">
      <p>
        <strong>
          {result.configProblems.length === 1
            ? 'Probleem in de beschrijving van het bestand.'
            : `${result.configProblems.length} problemen in de beschrijving van het bestand.`}
        </strong>
      </p>
      <ul className={styles.list} data-testid="trial-config-problem-list">
        {result.configProblems.map((problem, index) => {
          const described = describeIssueCode(problem.code);
          const fieldText = describeFindingField(problem.revisionField, problem.fieldName);
          return (
            <li key={`${problem.code}-${index}`}>
              {described.label}: {described.uitleg}
              {fieldText !== null && <> ({fieldText})</>}
              <TechnicalDetails
                items={[
                  { name: 'Code', value: problem.code },
                  { name: 'Toelichting', value: problem.message },
                  ...(problem.revisionField ? [{ name: 'Veldsleutel', value: problem.revisionField }] : []),
                  ...(problem.fieldName ? [{ name: 'Veldnaam', value: problem.fieldName }] : []),
                ]}
              />
            </li>
          );
        })}
      </ul>
      {skipped.length > 0 && (
        <div data-testid="trial-config-skipped">
          <p>{term('readinessCheck', SKIPPED_CODE).uitleg}</p>
          <SkippedCauses codes={skipped} />
        </div>
      )}
    </div>
  );
}

function Counters({ counters }: { counters: TrialCounters }) {
  const bySeverity = counters.issueOccurrencesBySeverity;
  return (
    <>
      <h3 className={styles.subTitle}>Wat de proef telde</h3>
      <dl className={styles.tiles} data-testid="trial-counters">
        {MAIN_COUNTERS.map((key) => {
          const resolved = term('trialCounter', key);
          return <Tile key={key} label={resolved.label} uitleg={resolved.uitleg} value={counters[key]} />;
        })}
      </dl>
      <details>
        <summary className={styles.muted}>Meer tellers</summary>
        <dl className={styles.tiles} data-testid="trial-extra-counters">
          {EXTRA_COUNTERS.map((key) => {
            const resolved = term('trialCounter', key);
            return <Tile key={key} label={resolved.label} uitleg={resolved.uitleg} value={counters[key]} />;
          })}
          {SEVERITIES.map((severity) => {
            const resolved = term('severity', severity);
            return (
              <Tile
                key={severity}
                label={`Vaststellingen: ${resolved.label.toLowerCase()}`}
                uitleg={resolved.uitleg}
                value={bySeverity === null ? null : (bySeverity[severity] ?? null)}
              />
            );
          })}
        </dl>
      </details>
    </>
  );
}

function CharsetHint({ counters }: { counters: TrialCounters }) {
  const count = counters.linesWithReplacementCharacter;
  if (count === null || count <= 0) {
    return null;
  }
  return (
    <p className={styles.warning} role="note" data-testid="trial-charset-hint">
      {count} {count === 1 ? 'regel bevat' : 'regels bevatten'} tekens die niet gelezen konden worden — kies mogelijk
      een andere <Term domain="revisionField" code="charset" /> in de beschrijving van het bestand.
    </p>
  );
}

function Columns({ header }: { header: TrialHeader | null }) {
  if (header === null) {
    return (
      <>
        <h3 className={styles.subTitle}>Kolommen</h3>
        <p className={styles.muted}>
          De kolommen konden niet bekeken worden, omdat de beschrijving van het bestand een fout bevat.
        </p>
      </>
    );
  }
  return (
    <>
      <h3 className={styles.subTitle}>Kolommen</h3>
      <ul className={styles.list} data-testid="trial-columns">
        <li>
          Gevonden in het bestand: <Count value={header.foundColumnCount} /> kolommen
          {header.foundColumns !== null && header.foundColumns.length > 0 && <> ({header.foundColumns.join(', ')})</>}.
        </li>
        {header.missingRequired.length === 0 ? (
          <li>Ontbrekende verplichte kolommen: geen, alle verplichte kolommen zijn gevonden.</li>
        ) : (
          <li className={styles.missing} data-testid="trial-missing-columns">
            Ontbrekende verplichte kolommen: {header.missingRequired.join(', ')}. Zonder deze kolommen kan het bestand
            niet gelezen worden.
          </li>
        )}
        {header.missingOptionalFilterColumns.length > 0 && (
          <li>Ontbrekende filterkolommen: {header.missingOptionalFilterColumns.join(', ')}.</li>
        )}
        {header.extraColumns.length > 0 && (
          <li>
            Extra kolommen die niet gebruikt worden:{' '}
            {header.extraColumns
              .map((column) => `${column.name ?? 'zonder naam'} (kolom ${column.position})`)
              .join(', ')}
            .
          </li>
        )}
        {header.shifted.map((column) => (
          <li key={column.reference}>
            Verschoven: {column.reference} staat in kolom {column.foundPosition} in plaats van kolom{' '}
            {column.expectedPosition}.
          </li>
        ))}
      </ul>
      {header.expectedColumns.length > 0 && (
        <div className={styles.tableWrap}>
          <table className={styles.table} data-testid="trial-expected-columns">
            <thead>
              <tr>
                <th>Verwachte kolom</th>
                <th>Waarvoor</th>
                <th>Verplicht</th>
                <th>Gevonden</th>
              </tr>
            </thead>
            <tbody>
              {header.expectedColumns.map((column, index) => (
                <tr key={`${column.reference}-${column.role}-${index}`}>
                  <td>{column.reference}</td>
                  <td>
                    <Term domain="columnRole" code={column.role} />
                  </td>
                  <td>{column.required ? 'ja' : 'nee'}</td>
                  <td>{column.foundAtPosition === null ? 'niet gevonden' : `kolom ${column.foundAtPosition}`}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

function IssueText({ issue }: { issue: TrialSampleIssue }) {
  const described = describeIssueCode(issue.code);
  return (
    <span title={`${described.uitleg} (technische code: ${issue.code})`}>
      {described.label}
      {issue.fieldName !== null && <> (kolom {issue.fieldName})</>}
      {issue.sourceValue !== null && <>: &laquo;{issue.sourceValue}&raquo;</>}
    </span>
  );
}

function SampleRowView({ row }: { row: TrialSampleRow }) {
  const interpreted = row.interpreted;
  let remark: ReactNode = null;
  if (row.issues.length > 0) {
    remark = (
      <ul className={styles.list}>
        {row.issues.map((issue, index) => (
          <li key={`${issue.code}-${index}`}>
            <IssueText issue={issue} />
          </li>
        ))}
      </ul>
    );
  } else if (row.status === 'UNREADABLE' && row.sourceValue !== null) {
    remark = <>Regel: &laquo;{row.sourceValue}&raquo;</>;
  }
  return (
    <tr data-testid={`trial-sample-${row.lineNumber}`}>
      <td>{row.lineNumber}</td>
      <td>
        <StatusBadge status={row.status} domain="trialSampleStatus" />
      </td>
      <td>
        {interpreted === null
          ? '—'
          : `${interpreted.supplier} / ${interpreted.supplierGroup} / ${interpreted.supplierReference}`}
      </td>
      <td>
        {interpreted === null ? (
          '—'
        ) : (
          <>
            {text(interpreted.basePriceRaw)} → {interpreted.basePrice}
          </>
        )}
      </td>
      <td>
        {interpreted === null ? (
          '—'
        ) : (
          <>
            {interpreted.currency} (<Term domain="currencyOrigin" code={interpreted.currencyOrigin} />)
          </>
        )}
      </td>
      <td>{interpreted === null ? '—' : text(interpreted.description)}</td>
      <td>{remark}</td>
    </tr>
  );
}

function SampleRows({ result }: { result: TrialReadResult }) {
  return (
    <>
      <h3 className={styles.subTitle}>Zo lezen we uw bestand</h3>
      <p className={styles.sectionIntro}>
        De eerste regels van uw bestand, zoals ze gelezen worden. Kijk vooral of de prijs goed begrepen is (let op het
        decimaalteken): een prijs wordt nooit aangepast of afgerond.
      </p>
      {result.sampleRows.length === 0 ? (
        <p className={styles.muted}>Er zijn geen regels om te tonen.</p>
      ) : (
        <div className={styles.tableWrap}>
          <table className={styles.table} data-testid="trial-samples">
            <thead>
              <tr>
                <th>Regel</th>
                <th>Wat gebeurt er?</th>
                <th>Leverancier / groep / referentie</th>
                <th>Prijs in het bestand → zoals begrepen</th>
                <th>Valuta</th>
                <th>Omschrijving</th>
                <th>Opmerking</th>
              </tr>
            </thead>
            <tbody>
              {result.sampleRows.map((row) => (
                <SampleRowView key={row.lineNumber} row={row} />
              ))}
            </tbody>
          </table>
        </div>
      )}
      {result.sampleRowsTruncated && (
        <p className={styles.muted}>Enkel de eerste {result.sampleRows.length} regels worden getoond.</p>
      )}
    </>
  );
}

function IssueGroups({ groups }: { groups: TrialIssueGroup[] }) {
  return (
    <>
      <h3 className={styles.subTitle}>Gevonden problemen</h3>
      {groups.length === 0 ? (
        <p className={styles.muted} data-testid="trial-no-issues">
          Er zijn geen problemen gevonden in de regels.
        </p>
      ) : (
        <div className={styles.tableWrap}>
          <table className={styles.table} data-testid="trial-issue-groups">
            <thead>
              <tr>
                <th>Wat</th>
                <th>Kolom</th>
                <th>Ernst</th>
                <th>Gevolg</th>
                <th>Aantal</th>
                <th>Voorbeelden</th>
              </tr>
            </thead>
            <tbody>
              {groups.map((group, index) => {
                const described = describeIssueCode(group.code);
                return (
                  <tr key={`${group.code}-${group.fieldName ?? ''}-${index}`}>
                    <td>
                      <span title={`technische code: ${group.code}`}>{described.label}</span>
                      <p className={styles.tileUitleg}>{described.uitleg}</p>
                      {group.bulkIncident && (
                        <p className={styles.tileUitleg}>
                          Veel gelijke gevallen ({percent(group.sharePercent)} van de regels): ze worden samen als één
                          gebeurtenis gemeld.
                        </p>
                      )}
                    </td>
                    <td>{text(group.fieldName)}</td>
                    <td>
                      <Term domain="severity" code={group.severity} />
                    </td>
                    <td>
                      <Term domain="deliveryEffect" code={group.deliveryEffect} />
                    </td>
                    <td>{group.occurrenceCount}</td>
                    <td>
                      <ul className={styles.list}>
                        {group.examples.map((example, exampleIndex) => (
                          <li key={exampleIndex}>
                            {example.lineNumber === null ? 'bestand' : `regel ${example.lineNumber}`}
                            {example.sourceValue !== null && <>: &laquo;{example.sourceValue}&raquo;</>}
                          </li>
                        ))}
                      </ul>
                      {group.examplesTruncated && <span className={styles.muted}>… en meer</span>}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

function Thresholds({ thresholds }: { thresholds: TrialThresholds | null }) {
  if (thresholds === null) {
    return null;
  }
  const { critical, rejected } = thresholds;
  return (
    <>
      <h3 className={styles.subTitle}>Grenzen</h3>
      <p className={styles.sectionIntro}>
        Een echte levering wordt als geheel tegengehouden wanneer te veel regels een probleem hebben. Het aandeel wordt
        berekend op de <Count value={thresholds.scopeRecordCount} /> regels binnen filter.
      </p>
      <ul className={styles.list} data-testid="trial-thresholds">
        <li>
          Regels ter beoordeling: {critical.countIsLowerBound ? 'minstens ' : ''}
          <Count value={critical.count} /> ({percent(critical.sharePercent)}), grens {percent(critical.thresholdPercent)}:{' '}
          <Term domain="thresholdOutcome" code={critical.outcome} />.
          {critical.countIsLowerBound && (
            <> Dit kan hoger zijn in een echte levering, want herkenningsproblemen met bekende artikelen tellen dan mee.</>
          )}
        </li>
        <li>
          Verworpen regels: <Count value={rejected.count} /> ({percent(rejected.sharePercent)}), grens{' '}
          {rejected.thresholdPercent === null ? 'niet ingesteld' : percent(rejected.thresholdPercent)}:{' '}
          <Term domain="thresholdOutcome" code={rejected.outcome} />.
        </li>
        <li>
          Gelijksoortige problemen worden samen als één gebeurtenis gemeld vanaf{' '}
          {percent(thresholds.bulkIncidentSharePercent)} van de regels.
        </li>
      </ul>
    </>
  );
}

function NotEvaluated({ result }: { result: TrialReadResult }) {
  if (result.notEvaluated.length === 0) {
    return null;
  }
  return (
    <>
      <h3 className={styles.subTitle}>Niet gecontroleerd in een proef</h3>
      <p className={styles.sectionIntro}>
        Deze controles gebeuren pas bij een echte levering. Daarom blijft de eerste echte levering de laatste controle.
      </p>
      <ul className={styles.list} data-testid="trial-not-evaluated">
        {result.notEvaluated.map((item) => {
          const check = term('trialCheck', item.check);
          const reason = term('trialReason', item.reason);
          const known = check.uitleg !== '';
          return (
            <li key={`${item.check}-${item.reason}`}>
              <strong>{known ? check.label : 'Andere controle'}</strong>: {known ? check.uitleg : ''}{' '}
              {reason.uitleg !== '' && <span className={styles.muted}>(Waarom: {reason.uitleg})</span>}
              <TechnicalDetails
                items={[
                  { name: 'Controle', value: item.check },
                  { name: 'Reden', value: item.reason },
                ]}
              />
            </li>
          );
        })}
      </ul>
    </>
  );
}

export function TrialReadResultView({ result }: { result: TrialReadResult }) {
  return (
    <div data-testid="trial-result" aria-live="polite">
      <p className={styles.nothingStored}>Er is niets opgeslagen of gepubliceerd: dit resultaat bestaat enkel op deze pagina.</p>
      <p className={styles.muted}>
        Bestand {result.file.fileName ?? '(zonder naam)'} ({formatByteSize(result.file.byteSize)}), gelezen met versie{' '}
        {result.revisionNumber} (<Term domain="revisionStatus" code={result.revisionStatus} />). Valuta als het bestand
        er geen vermeldt: {result.currencyDefault.value} (<Term domain="currencyOrigin" code={result.currencyDefault.origin} />
        ).
      </p>
      <Verdict result={result} />
      <ConfigProblems result={result} />
      <CharsetHint counters={result.counters} />
      <Counters counters={result.counters} />
      <Columns header={result.header} />
      <SampleRows result={result} />
      <IssueGroups groups={result.issueGroups} />
      <Thresholds thresholds={result.thresholds} />
      <NotEvaluated result={result} />
      <WhatIsThis>
        Een proefinlezing leest uw bestand precies zoals een echte levering, maar bewaart niets: er ontstaat geen
        levering, geen wijziging en geen publicatie. U kunt dezelfde proef zo vaak herhalen als u wilt.
      </WhatIsThis>
      <TechnicalDetails
        items={[
          { name: 'Versie', value: String(result.revisionId) },
          { name: 'Koppeling', value: text(result.linkId) },
          { name: 'Controlegetal bestand', value: result.file.sha256 },
        ]}
      />
    </div>
  );
}
