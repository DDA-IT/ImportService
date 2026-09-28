/**
 * S1-F3 — de koppeling-detailweergave van de wizard: de LINK-bookmarkwaarden van één gematerialiseerde
 * koppeling lezen (`GET /links/{linkId}/bookmark-values`) en er één wijzigen
 * (`PUT /links/{linkId}/bookmark-values/{name}`). Hoort bewust hier in `features/templates/` en niet op
 * scherm 1a (`docs/decisions.md` 2026-09-27 "scherm 1a/1b", S1-F3-alinea).
 *
 * Twee regels die de UI expliciet zichtbaar houdt in plaats van weg te abstraheren:
 * - **"uitdrukkelijk leeg" ≠ "niet wijzigen" (R-BMK-03).** Elke waarde wordt individueel via `PUT`
 *   verstuurd; een waarde die de gebruiker niet opent, wordt nooit meegestuurd. Leegmaken vraagt een
 *   apart vinkje dat `""` verstuurt, zodat een leeg tekstveld nooit per ongeluk een waarde wist.
 * - **Het slot.** Heeft de koppeling een open batch, dan weigert de server elke wijziging met 409
 *   `LINK_BOOKMARK_LOCKED_BY_OPEN_BATCH`. Die melding staat bij de actie zelf — in het bewerkformulier
 *   van díe ene bookmarkwaarde — en niet ergens bovenaan het scherm. `lockedByOpenBatch` uit het
 *   leesmodel is enkel de spiegel vooraf; de 409 blijft het echte slot.
 *
 * Een rij met `declared = false` is een wees: de naam staat niet (meer) gedeclareerd op de actieve
 * revisie, de waarde wordt nooit toegepast en blijft enkel auditmateriaal (§7). Ze wordt daarom getoond,
 * maar niet bewerkbaar gemaakt — een wijziging zou opnieuw 400 `BOOKMARK_UNKNOWN` geven.
 */
import { useState } from 'react';
import * as linksApi from '../../api/links.ts';
import { PERMISSION_MANAGE, type LinkBookmarkValueRow } from '../../api/types.ts';
import { useActor } from '../../actor/ActorContext.tsx';
import { usePermissionGate } from '../../actor/permissions.ts';
import { useAction } from '../../hooks/useAction.ts';
import { useQuery } from '../../hooks/useQuery.ts';
import { Field } from '../../components/Field.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { isSetupApiDisabledError, SETUP_API_DISABLED_MESSAGE } from './setupApiFlag.ts';
import styles from './LinkBookmarkValuesSection.module.css';

const ORPHAN_REASON = 'wees: niet (meer) gedeclareerd op de actieve revisie, dus niet bewerkbaar';

/** Wat er vandaag bewaard is: niets, uitdrukkelijk leeg, of een waarde — nooit op één toestand geveegd. */
function currentValueLabel(row: LinkBookmarkValueRow): string {
  if (row.valueText === null) {
    return 'niet ingevuld';
  }
  if (row.valueText === '') {
    return 'uitdrukkelijk leeg';
  }
  return row.valueText;
}

function BookmarkValueEditor({
  linkId,
  row,
  onSaved,
}: {
  linkId: number;
  row: LinkBookmarkValueRow;
  onSaved: () => void;
}) {
  const { actor } = useActor();
  const manageGate = usePermissionGate(PERMISSION_MANAGE);
  const [open, setOpen] = useState(false);
  const [text, setText] = useState(row.valueText ?? '');
  const [explicitEmpty, setExplicitEmpty] = useState(false);
  const [saved, setSaved] = useState(false);

  const { execute, pending, error, reset } = useAction((value: string) =>
    linksApi.setBookmarkValue(linkId, row.bookmarkName, { value, updatedBy: actor }),
  );

  const inputId = `link-bookmark-${row.bookmarkName}`;
  // Recht eerst, dan de toestand (dezelfde volgorde als de server en als `withPermission`).
  const permissionReason = manageGate.allowed ? null : manageGate.reason;
  const blockedReason = permissionReason ?? (row.declared ? null : ORPHAN_REASON);

  if (!open) {
    return (
      <div className={styles.editorRow}>
        <button
          type="button"
          className={styles.secondaryButton}
          disabled={blockedReason !== null}
          title={blockedReason ?? undefined}
          data-testid={`edit-${row.bookmarkName}`}
          onClick={() => {
            setSaved(false);
            reset();
            setText(row.valueText ?? '');
            setExplicitEmpty(false);
            setOpen(true);
          }}
        >
          Waarde wijzigen
        </button>
        {permissionReason !== null && (
          <span className={styles.reason} data-testid={`edit-reason-${row.bookmarkName}`}>
            {permissionReason}
          </span>
        )}
        {permissionReason === null && !row.declared && <span className={styles.reason}>{ORPHAN_REASON}</span>}
        {saved && (
          <span className={styles.saved} role="status">
            Waarde opgeslagen.
          </span>
        )}
      </div>
    );
  }

  return (
    <div className={styles.editor} data-testid={`editor-${row.bookmarkName}`}>
      <Field
        label={`Nieuwe waarde voor ${row.bookmarkName}`}
        htmlFor={inputId}
        hint="Alleen deze ene waarde wordt verstuurd; de andere bookmarks blijven ongewijzigd."
      >
        <input
          id={inputId}
          className={styles.input}
          type="text"
          value={text}
          disabled={explicitEmpty}
          onChange={(event) => setText(event.target.value)}
          maxLength={500}
        />
      </Field>
      <div className={styles.checkboxRow}>
        <input
          id={`${inputId}-empty`}
          type="checkbox"
          checked={explicitEmpty}
          onChange={(event) => setExplicitEmpty(event.target.checked)}
        />{' '}
        <label htmlFor={`${inputId}-empty`}>
          Expliciet leegmaken (verstuurt een lege waarde; niet hetzelfde als niet wijzigen)
        </label>
      </div>

      {error !== null && <ErrorBanner error={error} />}

      <div className={styles.editorActions}>
        <button
          type="button"
          className={styles.primaryButton}
          disabled={pending || permissionReason !== null}
          title={permissionReason ?? undefined}
          data-testid={`save-${row.bookmarkName}`}
          onClick={async () => {
            if (permissionReason !== null) {
              return;
            }
            const result = await execute(explicitEmpty ? '' : text);
            if (result === undefined) {
              // De fout blijft hier staan (bv. het slot bij een open batch); het formulier blijft open
              // zodat de gebruiker ziet welke waarde niet bewaard is.
              return;
            }
            setSaved(true);
            setOpen(false);
            onSaved();
          }}
        >
          {pending ? 'Bezig…' : 'Waarde opslaan'}
        </button>
        <button
          type="button"
          className={styles.secondaryButton}
          onClick={() => {
            reset();
            setOpen(false);
          }}
        >
          Annuleren
        </button>
      </div>
    </div>
  );
}

export type LinkBookmarkValuesSectionProps = {
  linkId: number;
  /** Meegegeven vanuit de koppelingenlijst, zodat de titel al leesbaar is vóór het antwoord er is. */
  linkCode?: string;
};

export function LinkBookmarkValuesSection({ linkId, linkCode }: LinkBookmarkValuesSectionProps) {
  const key = `link-bookmark-values:${linkId}`;
  const query = useQuery(key, (signal) => linksApi.getLinkBookmarkValues(linkId, signal));

  return (
    <section className={styles.section} data-testid="link-bookmark-values">
      <h3 className={styles.title}>Bookmarkwaarden van koppeling {query.data?.importLinkCode ?? linkCode ?? linkId}</h3>

      {query.error !== null &&
        (isSetupApiDisabledError(query.error) ? (
          <p role="alert" className={styles.flagOff}>
            {SETUP_API_DISABLED_MESSAGE}
          </p>
        ) : (
          <ErrorBanner error={query.error} />
        ))}
      {query.loading && query.data === null && <p className={styles.loading}>Bezig met laden…</p>}

      {query.data !== null && (
        <>
          {query.data.lockedByOpenBatch && (
            <p className={styles.locked} role="alert" data-testid="link-locked-warning">
              Deze koppeling heeft een open levering: een wijziging wordt geweigerd zolang die batch loopt.
            </p>
          )}
          {query.data.missingRequiredNames.length > 0 && (
            <p className={styles.missing} role="alert" data-testid="link-missing-required">
              Nog niet ingevulde verplichte bookmarks: {query.data.missingRequiredNames.join(', ')}. Een
              nieuwe levering wordt geweigerd zolang deze lijst niet leeg is.
            </p>
          )}
          {query.data.values.length === 0 ? (
            <p className={styles.empty}>Deze koppeling heeft nog geen LINK-bookmarkwaarden.</p>
          ) : (
            <ul className={styles.list}>
              {query.data.values.map((row) => (
                <li key={row.bookmarkName} className={styles.listItem}>
                  <div className={styles.rowHeader}>
                    <span className={styles.code}>{row.bookmarkName}</span>
                    {row.label !== null && <span>{row.label}</span>}
                    <span>{row.required ? 'verplicht' : 'optioneel'}</span>
                    <span className={styles.value}>{currentValueLabel(row)}</span>
                    {!row.declared && <span className={styles.orphan}>{ORPHAN_REASON}</span>}
                  </div>
                  {row.previousValueText !== null && (
                    <p className={styles.previous}>Vorige waarde: {row.previousValueText}</p>
                  )}
                  {row.updatedBy !== null && (
                    <p className={styles.previous}>
                      Laatst gewijzigd door {row.updatedBy}
                      {row.updatedAt !== null ? ` op ${new Date(row.updatedAt).toLocaleString('nl-BE')}` : ''}.
                    </p>
                  )}
                  <BookmarkValueEditor linkId={linkId} row={row} onSaved={query.reload} />
                </li>
              ))}
            </ul>
          )}
        </>
      )}
    </section>
  );
}
