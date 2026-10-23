/**
 * E4 — de kleine bouwstenen van het verschil met de basisversie: de chip Nieuw/Gewijzigd op een rij of zin, de strook
 * "Niet meer in deze versie: …" en de lijst achter "Toon wijzigingen" (mock `docs/mock/setup-redesign-mock.html`, v4).
 */

import { DIFF_TEXT as TEXT } from '../../terms/setupEditor.ts';
import type { ChipKind, DiffEntry, RevisionDiff } from './revisionDiffModel.ts';
import editor from './FieldsEditor.module.css';
import styles from './SetupScreens.module.css';

export function DiffChip({ kind, testId }: { kind: ChipKind | undefined; testId?: string }) {
  if (kind === undefined) {
    return null;
  }
  return (
    <span
      className={`${editor.diffChip} ${kind === 'added' ? editor.diffAdded : editor.diffChanged}`}
      data-testid={testId ?? `diff-chip-${kind}`}
    >
      {kind === 'added' ? TEXT.chipAdded : TEXT.chipChanged}
    </span>
  );
}

/** "Niet meer in deze versie: …" voor velden of filters die het concept niet meer heeft; niets als er geen zijn. */
export function RemovedStrip({ entries, testId }: { entries: readonly DiffEntry[]; testId: string }) {
  if (entries.length === 0) {
    return null;
  }
  return (
    <p className={`${styles.rowMeta} ${editor.removedStrip}`} data-testid={testId}>
      {TEXT.removedStrip(entries.map((entry) => (entry.section === 'filter' ? (entry.from ?? entry.label) : entry.label)))}
    </p>
  );
}

function kindLabel(kind: DiffEntry['kind']): string {
  return TEXT.kinds[kind];
}

/** De lijst achter "Toon wijzigingen": per verschil de naam, wat het was en wat het nu is, met de soort als badge. */
export function DiffBox({ diff }: { diff: RevisionDiff }) {
  const base = diff.baseRevisionNumber ?? 0;
  return (
    <div className={editor.diffBox} role="region" aria-label={TEXT.panelLabel(base)} data-testid="diff-box">
      <h3 className={editor.diffHeading}>{TEXT.heading(base)}</h3>
      {diff.entries.length === 0 ? (
        <p className={styles.rowMeta}>{TEXT.none(base)}</p>
      ) : (
        <ul className={styles.rowList}>
          {diff.entries.map((entry) => (
            <li key={`${entry.section}:${entry.key}:${entry.kind}`} data-testid="diff-entry">
              <div className={styles.rowMain}>
                <strong>{entry.label}</strong>
                {entry.kind === 'changed' && (
                  <span className={styles.rowMeta}>
                    {' '}
                    · {TEXT.was}: {entry.from} → {TEXT.now}: {entry.to}
                  </span>
                )}
                {entry.kind === 'added' && <span className={styles.rowMeta}> · {entry.to}</span>}
                {entry.kind === 'removed' && (
                  <span className={styles.rowMeta}>
                    {' '}
                    · {TEXT.was}: {entry.from}
                  </span>
                )}
              </div>
              <span
                className={`${editor.diffChip} ${
                  entry.kind === 'added' ? editor.diffAdded : entry.kind === 'changed' ? editor.diffChanged : editor.diffRemoved
                }`}
              >
                {kindLabel(entry.kind)}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
