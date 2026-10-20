import styles from './BrandBlock.module.css';

/**
 * Merkblok bovenaan de zijbalk (Prodis-mock `.brand`). Statische placeholder: wordt later de productwisselaar
 * (docs/decisions.md 2026-10-20). In de mini-modus blijft enkel het merkteken over.
 */
export function BrandBlock() {
  return (
    <div className={styles.brand}>
      <span className={styles.mark} aria-hidden="true">
        CI
      </span>
      <span className={styles.text}>
        <span className={styles.name}>CatalogImport</span>
        <span className={styles.sub}>Inlezen van catalogi</span>
      </span>
    </div>
  );
}
