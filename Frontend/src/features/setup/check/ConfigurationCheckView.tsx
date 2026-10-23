/**
 * E5a — de controle van de instellingen bovenaan stap ② van de conceptbalk (beslissingslog 2026-10-23 "E5a"): een
 * kopregel "Instellingen: in orde" of "n problemen blokkeren het activeren", per probleem een Nederlandse zin met een
 * sprong naar de rij of sectie waar het opgelost wordt, en een korte noot als er controles overgeslagen zijn. De codes
 * staan enkel onder "Technische details (voor support)". Een mislukte oproep blokkeert niets en wordt rustig gemeld.
 */

import type { ApiError } from '../../../api/http.ts';
import type { ReadinessCheck, RevisionConfigurationCheck } from '../../../api/types.ts';
import { CONFIG_CHECK_TEXT as TEXT } from '../../../terms/setupEditor.ts';
import { TechnicalDetails } from '../../../terms/TechnicalDetails.tsx';
import button from '../../../components/Button.module.css';
import editor from '../FieldsEditor.module.css';
import { describeFindingField } from './configFindings.tsx';
import { jumpTargetOf, summarise, type JumpTarget } from './configurationCheckModel.ts';
import { describeReadinessCheck } from './linkCheck.ts';

/** De toestand van de oproep zoals `useQuery` ze geeft. */
export type ConfigurationCheckState = {
  data: RevisionConfigurationCheck | null;
  error: ApiError | null;
  loading: boolean;
};

/**
 * Wat de conceptbalk met de controle doet: het aantal blokkerende problemen als de controle een antwoord heeft
 * (`null` = geen bruikbaar antwoord: nog aan het laden of mislukt; dan wordt activeren nooit geblokkeerd, zoals in E4).
 */
export function blockingProblemCount(state: ConfigurationCheckState): number | null {
  if (state.error !== null || state.loading || state.data === null || state.data.activatable) {
    return null;
  }
  return Math.max(1, summarise(state.data).problems.length);
}

const FLASH_MS = 2500;

/** Scrolt naar het eerste bestaande element van de sprong, markeert het even en zet bij een sectie de focus op de kop. */
export function jumpTo(target: JumpTarget): void {
  for (const id of target.elementIds) {
    const element = document.getElementById(id);
    if (element === null) {
      continue;
    }
    element.scrollIntoView?.({ behavior: 'smooth', block: 'center' });
    element.setAttribute('data-check-flash', 'true');
    window.setTimeout(() => element.removeAttribute('data-check-flash'), FLASH_MS);
    element.querySelector<HTMLElement>('h3')?.focus({ preventScroll: true });
    return;
  }
}

function ProblemRow({ check }: { check: ReadinessCheck }) {
  const described = describeReadinessCheck(check.code);
  const field = describeFindingField(check.revisionField, check.fieldName);
  const target = jumpTargetOf(check);
  const details = [
    { name: 'Code', value: check.code },
    ...(check.detail === null ? [] : [{ name: 'Toelichting', value: check.detail }]),
    ...(check.revisionField ? [{ name: 'Veldsleutel', value: check.revisionField }] : []),
    ...(check.fieldName ? [{ name: 'Veldnaam', value: check.fieldName }] : []),
  ];
  return (
    <li data-testid="config-check-problem">
      <strong>{described.label}</strong>. {described.uitleg}
      {field !== null && <> ({field})</>}{' '}
      {target !== null && (
        <button
          type="button"
          className={`${button.secondary} ${button.small}`}
          aria-label={TEXT.jumpLabel(described.label)}
          data-testid="config-check-jump"
          onClick={() => jumpTo(target)}
        >
          {target.kind === 'row' ? TEXT.jumpRow : TEXT.jumpSection}
        </button>
      )}
      <TechnicalDetails items={details} />
    </li>
  );
}

export function ConfigurationCheckView({ state }: { state: ConfigurationCheckState }) {
  const { data, error, loading } = state;
  if (error !== null) {
    return (
      <p className={editor.barNote} role="status" data-testid="config-check-failed">
        {TEXT.failed}
      </p>
    );
  }
  if (data === null) {
    return loading ? (
      <p className={editor.barNote} role="status" data-testid="config-check-loading">
        {TEXT.loading}
      </p>
    ) : null;
  }
  const { problems, skipped } = summarise(data);
  return (
    <div className={editor.barNote} data-testid="config-check" aria-busy={loading}>
      {data.activatable ? (
        <p role="status" data-testid="config-check-summary">
          ✓ {TEXT.ok}
        </p>
      ) : (
        <p role="status" data-testid="config-check-summary">
          ✗ {TEXT.problems(problems.length)}
        </p>
      )}
      {problems.length > 0 && (
        <ul data-testid="config-check-problems">
          {problems.map((check, index) => (
            <ProblemRow key={`${check.code}-${check.fieldName ?? ''}-${check.revisionField ?? ''}-${index}`} check={check} />
          ))}
        </ul>
      )}
      {skipped !== null && <p data-testid="config-check-skipped">{TEXT.skipped}</p>}
    </div>
  );
}
