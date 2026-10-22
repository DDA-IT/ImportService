/**
 * G2c-4 — "Uitsluitingsgroep toevoegen…" bij de filters van een DRAFT-revisie (`docs/design/g2-design.md` §G2c).
 *
 * Een uitsluitingsgroep is een reeks voorwaarden met hetzelfde groepsnummer die samen waar moeten zijn voor een regel
 * overgeslagen wordt (legacy PSIMPEXCL). De dialoog heeft 1 tot 4 rijen (kolom, "is gelijk aan", vergelijkwaarde — leeg
 * is toegelaten en betekent "leeg veld", hoofdlettergevoelig, trimmen); alle rijen krijgen `EXCLUDE` +
 * `COMPARE_AS_EMPTY` en hetzelfde nummer (voorstel: hoogste bestaande + 1).
 *
 * Aanmaken zijn N POST-calls na elkaar (er is geen batch-endpoint; aanvaard op een concept). Faalt er één, dan staat de
 * serverfout in de dialoog, samen met de rijen die al aangemaakt zijn en de keuze die rijen te verwijderen (bestaande
 * delete): nooit een stille gedeeltelijke groep. Zolang er aangemaakte rijen zijn, is opnieuw proberen geblokkeerd.
 * Client-validatie is enkel vorm; de server beslist (`MATCH_GROUP_INVALID`, `CONFIG_FILTER_INVALID`,
 * `COMPARE_VALUE_REQUIRED`).
 */

import { useId, useRef, useState } from 'react';
import * as setupRevisionsApi from '../../api/setupRevisions.ts';
import { PERMISSION_MANAGE, type FilterView, type RevisionDetail } from '../../api/types.ts';
import { useActor } from '../../actor/ActorContext.tsx';
import { usePermissionGate, withPermission } from '../../actor/permissions.ts';
import { gateTitle } from '../../actor/gate.ts';
import { ConfirmDialog } from '../../components/ConfirmDialog.tsx';
import { Field } from '../../components/Field.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useAction } from '../../hooks/useAction.ts';
import { termLabel } from '../../terms/index.ts';
import { FlagOffNotice } from './FlagOffNotice.tsx';
import {
  MAX_GROUP_ROWS,
  buildGroupRequest,
  emptyGroupRow,
  groupProblems,
  parseGroupNumber,
  suggestGroupNumber,
  type GroupRow,
} from './filterGroupModel.ts';
import { editGate } from './revisionPolicy.ts';
import { isSetupApiDisabledError, SETUP_WRITE_API_DISABLED_MESSAGE } from './setupWriteFlag.ts';
import button from '../../components/Button.module.css';
import styles from './FilterGroupDialog.module.css';

export type AddFilterGroupActionProps = {
  revision: RevisionDetail;
  /** Na een wijziging (groep aangemaakt, of rijen aangemaakt/verwijderd bij een gedeeltelijke fout): de ouder herlaadt het detail. */
  onChanged: () => void;
};

/** De knop "Uitsluitingsgroep toevoegen…". Enkel MANAGE en enkel op een concept (`editGate`). */
export function AddFilterGroupAction({ revision, onChanged }: AddFilterGroupActionProps) {
  const manageGate = usePermissionGate(PERMISSION_MANAGE);
  const gate = withPermission(manageGate, editGate(revision.status));
  const [open, setOpen] = useState(false);
  return (
    <>
      <button
        type="button"
        className={`${button.secondary} ${button.small}`}
        disabled={!gate.allowed}
        title={gateTitle(gate)}
        data-testid="add-filter-group"
        onClick={() => setOpen(true)}
      >
        Uitsluitingsgroep toevoegen…
      </button>
      {open && (
        <FilterGroupDialog
          revisionId={revision.id}
          filters={revision.filters}
          onClose={() => setOpen(false)}
          onChanged={onChanged}
        />
      )}
    </>
  );
}

type DialogProps = {
  revisionId: number;
  filters: readonly FilterView[];
  onClose: () => void;
  onChanged: () => void;
};

function describeValue(value: string | null): string {
  return value === null || value === '' ? 'leeg veld' : `"${value}"`;
}

/** De dialoog zelf; wordt enkel gemonteerd als hij open is, zodat elke opening met een vers voorstel begint. */
export function FilterGroupDialog({ revisionId, filters, onClose, onChanged }: DialogProps) {
  const { actor } = useActor();
  const uid = useId();
  const [groupText, setGroupText] = useState(() => String(suggestGroupNumber(filters)));
  const [rows, setRows] = useState<GroupRow[]>(() => [emptyGroupRow()]);
  const [created, setCreated] = useState<FilterView[]>([]);
  const [failedRow, setFailedRow] = useState<number | null>(null);
  // Zodra er ooit een rij is aangemaakt, moet de ouder bij sluiten herladen (ook na verwijderen van een deel).
  const touched = useRef(false);

  const creator = useAction(async (group: number, toCreate: readonly GroupRow[]) => {
    const done: FilterView[] = [];
    for (const [index, row] of toCreate.entries()) {
      try {
        done.push(await setupRevisionsApi.addFilter(revisionId, buildGroupRequest(row, group, actor)));
        touched.current = true;
      } catch (cause) {
        setCreated(done);
        setFailedRow(index);
        throw cause;
      }
    }
    return done;
  });

  const remover = useAction(async () => {
    const remaining = [...created];
    try {
      while (remaining.length > 0) {
        await setupRevisionsApi.deleteFilter(revisionId, remaining[0]!.id);
        remaining.shift();
      }
    } finally {
      setCreated(remaining);
    }
    setFailedRow(null);
    return true as const;
  });

  const problems = groupProblems(groupText, rows);
  const partial = created.length > 0;

  function resetErrors() {
    creator.reset();
    remover.reset();
  }

  function patchRow(index: number, patch: Partial<GroupRow>) {
    setRows((previous) => previous.map((row, at) => (at === index ? { ...row, ...patch } : row)));
    resetErrors();
  }

  async function handleConfirm() {
    const group = parseGroupNumber(groupText);
    if (group === null || problems.length > 0 || partial) {
      return;
    }
    const done = await creator.execute(group, rows);
    if (done === undefined) {
      return;
    }
    onChanged();
    onClose();
  }

  function handleClose() {
    if (touched.current) {
      onChanged();
    }
    onClose();
  }

  const error = creator.error ?? remover.error;
  const flagOff = error !== null && isSetupApiDisabledError(error);
  const id = (name: string) => `${uid}-${name}`;

  return (
    <ConfirmDialog
      open
      title="Uitsluitingsgroep toevoegen"
      body={
        <div className={styles.body} data-testid="filter-group-dialog">
          <p>
            Een regel wordt overgeslagen als <strong>alle</strong> voorwaarden hieronder samen waar zijn. Elke voorwaarde
            vergelijkt een kolom met een waarde; lege kolommen tellen mee als leeg. De server controleert of alles klopt.
          </p>

          <Field
            label="Groepsnummer"
            htmlFor={id('group')}
            required
            hint="Voorstel: het hoogste bestaande nummer plus één. Rijen met hetzelfde nummer horen bij dezelfde groep."
          >
            <input
              id={id('group')}
              data-testid="filter-group-number"
              inputMode="numeric"
              value={groupText}
              disabled={partial}
              onChange={(event) => {
                setGroupText(event.target.value);
                resetErrors();
              }}
            />
          </Field>

          {rows.map((row, index) => (
            // Rijen hebben geen eigen sleutel en de volgorde is de volgorde van aanmaken: de index is hier de identiteit.
            <div key={index} className={styles.row} data-testid={`filter-group-row-${index}`}>
              <h3 className={styles.rowTitle}>Voorwaarde {index + 1}</h3>
              <Field label="Kolom in het bronbestand" htmlFor={id(`col-${index}`)} required>
                <input
                  id={id(`col-${index}`)}
                  data-testid={`filter-group-column-${index}`}
                  value={row.sourceReference}
                  disabled={partial}
                  onChange={(event) => patchRow(index, { sourceReference: event.target.value })}
                />
              </Field>
              <p data-testid={`filter-group-operator-${index}`}>
                Vergelijking: <strong>{termLabel('filterOperator', 'EQUALS', 'gelijk aan')}</strong>
              </p>
              <Field label="Vergelijkwaarde" htmlFor={id(`value-${index}`)}>
                <input
                  id={id(`value-${index}`)}
                  data-testid={`filter-group-value-${index}`}
                  value={row.compareValue}
                  disabled={partial}
                  onChange={(event) => patchRow(index, { compareValue: event.target.value })}
                />
              </Field>
              {row.compareValue === '' && (
                <span className={styles.emptyHint} data-testid={`filter-group-empty-hint-${index}`}>
                  Leeg veld: de voorwaarde is waar als deze kolom leeg is.
                </span>
              )}
              <label className={styles.check}>
                <input
                  type="checkbox"
                  data-testid={`filter-group-case-${index}`}
                  checked={row.caseSensitive}
                  disabled={partial}
                  onChange={(event) => patchRow(index, { caseSensitive: event.target.checked })}
                />
                Hoofdlettergevoelig
              </label>
              <label className={styles.check}>
                <input
                  type="checkbox"
                  data-testid={`filter-group-trim-${index}`}
                  checked={row.trimBeforeCompare}
                  disabled={partial}
                  onChange={(event) => patchRow(index, { trimBeforeCompare: event.target.checked })}
                />
                Spaties vooraan en achteraan negeren
              </label>
              {rows.length > 1 && (
                <div className={styles.actions}>
                  <button
                    type="button"
                    className={`${button.dangerOutlined} ${button.small}`}
                    data-testid={`filter-group-remove-${index}`}
                    disabled={partial}
                    onClick={() => {
                      setRows((previous) => previous.filter((_, at) => at !== index));
                      resetErrors();
                    }}
                  >
                    Voorwaarde verwijderen
                  </button>
                </div>
              )}
            </div>
          ))}
          <div className={styles.actions}>
            <button
              type="button"
              className={`${button.secondary} ${button.small}`}
              data-testid="filter-group-add-row"
              disabled={partial || rows.length >= MAX_GROUP_ROWS}
              onClick={() => {
                setRows((previous) => [...previous, emptyGroupRow()]);
                resetErrors();
              }}
            >
              Voorwaarde toevoegen
            </button>
          </div>

          {problems.length > 0 && (
            <ul className={styles.problems} data-testid="filter-group-problems">
              {problems.map((problem) => (
                <li key={problem}>{problem}</li>
              ))}
            </ul>
          )}

          {partial && (
            <div className={styles.partial} data-testid="filter-group-partial">
              <p>
                <strong>De groep is niet volledig aangemaakt.</strong>
                {failedRow !== null && ` Voorwaarde ${failedRow + 1} is mislukt.`} Deze{' '}
                {created.length === 1 ? 'voorwaarde is' : `${created.length} voorwaarden zijn`} al aangemaakt en staan nu
                als groep {created[0]?.matchGroup ?? ''} op de versie:
              </p>
              <ul>
                {created.map((filter) => (
                  <li key={filter.id} data-testid={`filter-group-created-${filter.id}`}>
                    kolom {filter.sourceReference} is gelijk aan {describeValue(filter.compareValue)}
                  </li>
                ))}
              </ul>
              <p className={styles.muted}>
                Verwijder deze rijen en probeer opnieuw, of sluit de dialoog om ze te houden en de groep later aan te
                vullen.
              </p>
              <div className={styles.actions}>
                <button
                  type="button"
                  className={`${button.dangerOutlined} ${button.small}`}
                  data-testid="filter-group-delete-created"
                  disabled={remover.pending}
                  onClick={() => void remover.execute()}
                >
                  Aangemaakte rijen verwijderen
                </button>
              </div>
            </div>
          )}
        </div>
      }
      reasonRequirement="none"
      confirmLabel="Groep toevoegen"
      cancelLabel="Sluiten"
      pending={creator.pending || remover.pending}
      confirmBlockedReason={
        partial
          ? 'Verwijder eerst de al aangemaakte rijen.'
          : problems.length > 0
            ? 'Los eerst de meldingen hierboven op.'
            : null
      }
      error={
        error === null ? undefined : flagOff ? (
          <FlagOffNotice message={SETUP_WRITE_API_DISABLED_MESSAGE} className={styles.flagOff} />
        ) : (
          <ErrorBanner error={error} />
        )
      }
      onConfirm={handleConfirm}
      onCancel={handleClose}
    />
  );
}
