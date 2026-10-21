/**
 * De keuzes die de controle vooraf beïnvloeden: een andere code voor de beschrijving, de tekenset als de interface er
 * geen opgeeft, en de bibliotheek van de koppeling. De bibliotheek is een keuzelijst zodra de server kandidaten
 * (`libraryCandidates`) gaf; die lijst blijft staan nadat er gekozen is, ook al geeft de server dan geen kandidaten meer.
 *
 * Een gekozen bibliotheek rekent de controle meteen opnieuw; de twee tekstvelden pas met "Controle opnieuw uitvoeren"
 * (anders zou elke toetsaanslag een verzoek zijn). Alle keuzes horen bij dezelfde controle en gaan ook mee bij het overnemen.
 */

import { useId } from 'react';
import type { LegacyLibraryCandidate } from '../../api/types.ts';
import button from '../../components/Button.module.css';
import { Field } from '../../components/Field.tsx';
import type { LegacyChoicesForm } from './legacyPolicy.ts';
import styles from './LegacyConfig.module.css';

const CHARSET_SUGGESTIONS = ['UTF-8', 'ISO-8859-1', 'windows-1252'];

export type PreviewChoicesProps = {
  draft: LegacyChoicesForm;
  onDraftChange: (draft: LegacyChoicesForm) => void;
  /** De te kiezen bibliotheken; leeg = vrije tekst. */
  libraryCandidates: readonly LegacyLibraryCandidate[];
  /** Past de keuzes toe: de controle vooraf wordt opnieuw berekend. */
  onApply: (choices: LegacyChoicesForm) => void;
  loading: boolean;
};

function candidateLabel(candidate: LegacyLibraryCandidate): string {
  const name = candidate.catalogName ?? 'zonder naam';
  const number = candidate.legacyNumber === null ? '' : `, legacy-nummer ${candidate.legacyNumber}`;
  return `${candidate.scopeKey} — ${name}${number}`;
}

export function PreviewChoices({ draft, onDraftChange, libraryCandidates, onApply, loading }: PreviewChoicesProps) {
  const overrideId = useId();
  const charsetId = useId();
  const libraryId = useId();
  const charsetListId = useId();

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        onApply(draft);
      }}
    >
      <div className={styles.choices}>
        <Field
          label="Andere code voor de beschrijving"
          htmlFor={overrideId}
          hint="Alleen nodig als de code van legacy al in gebruik is. Hoogstens 47 tekens."
        >
          <input
            id={overrideId}
            type="text"
            value={draft.definitionCodeOverride}
            maxLength={47}
            onChange={(event) => onDraftChange({ ...draft, definitionCodeOverride: event.target.value })}
          />
        </Field>

        <Field
          label="Tekenset als de interface er geen opgeeft"
          htmlFor={charsetId}
          hint="Wordt nooit geraden: kies zelf, bijvoorbeeld UTF-8."
        >
          <input
            id={charsetId}
            type="text"
            list={charsetListId}
            value={draft.charsetWhenUndeclared}
            onChange={(event) => onDraftChange({ ...draft, charsetWhenUndeclared: event.target.value })}
          />
          <datalist id={charsetListId}>
            {CHARSET_SUGGESTIONS.map((charset) => (
              <option key={charset} value={charset}>
                {charset}
              </option>
            ))}
          </datalist>
        </Field>

        <Field
          label="Bibliotheek van de koppeling"
          htmlFor={libraryId}
          hint={
            libraryCandidates.length > 0
              ? 'Kies de bibliotheek van deze leverancier in Prodis.'
              : 'Leeg laten om de bibliotheek van legacy te gebruiken.'
          }
        >
          {libraryCandidates.length > 0 ? (
            <select
              id={libraryId}
              value={draft.libraryCode}
              disabled={loading}
              onChange={(event) => {
                const next = { ...draft, libraryCode: event.target.value };
                onDraftChange(next);
                onApply(next);
              }}
            >
              <option value="">Geen keuze</option>
              {libraryCandidates.map((candidate) => (
                <option key={candidate.scopeKey} value={candidate.scopeKey}>
                  {candidateLabel(candidate)}
                </option>
              ))}
            </select>
          ) : (
            <input
              id={libraryId}
              type="text"
              value={draft.libraryCode}
              onChange={(event) => onDraftChange({ ...draft, libraryCode: event.target.value })}
            />
          )}
        </Field>
      </div>
      <div className={styles.choicesActions}>
        <button type="submit" className={`${button.secondary} ${button.small}`} disabled={loading}>
          Controle opnieuw uitvoeren
        </button>
      </div>
    </form>
  );
}
