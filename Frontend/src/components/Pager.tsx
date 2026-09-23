/**
 * Gedeelde paginering: vorige/volgende, "x-y van n", en de paginagroottekeuzes die de backend toelaat
 * (`MAX_PAGE_SIZE` = 200, zie §6/§1.2 van het ontwerp). Kent geen scherm en geen backend-aanroep — een
 * aanroeper geeft `page`/`size`/`totalElements` mee en krijgt terug wat er moet veranderen.
 */

import styles from './Pager.module.css';

const PAGE_SIZES = [25, 50, 100, 200] as const;

export type PagerProps = {
  /** 0-gebaseerd, zoals de backend (`PageResult.page`). */
  page: number;
  size: number;
  totalElements: number;
  onPageChange: (page: number) => void;
  onSizeChange: (size: number) => void;
};

export function Pager({ page, size, totalElements, onPageChange, onSizeChange }: PagerProps) {
  const from = totalElements === 0 ? 0 : page * size + 1;
  const to = Math.min((page + 1) * size, totalElements);
  const hasPrevious = page > 0;
  const hasNext = to < totalElements;

  return (
    <div className={styles.pager}>
      <button type="button" onClick={() => onPageChange(page - 1)} disabled={!hasPrevious}>
        Vorige
      </button>
      <span className={styles.summary}>
        {totalElements === 0 ? '0 van 0' : `${from}-${to} van ${totalElements}`}
      </span>
      <button type="button" onClick={() => onPageChange(page + 1)} disabled={!hasNext}>
        Volgende
      </button>
      <label className={styles.sizeLabel} htmlFor="pager-size">
        Per pagina
        <select
          id="pager-size"
          value={size}
          onChange={(event) => onSizeChange(Number(event.target.value))}
        >
          {PAGE_SIZES.map((option) => (
            <option key={option} value={option}>
              {option}
            </option>
          ))}
        </select>
      </label>
    </div>
  );
}
