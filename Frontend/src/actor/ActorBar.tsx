/**
 * Staat permanent in de app shell. Toont wie er "tekent" zolang er geen authenticatie is, en laat de
 * naam voor de rest van de browsersessie invullen/wijzigen. Zie
 * `docs/design/frontend-scherm3-bundel-design.md` §7.
 */

import { useState, type FormEvent } from 'react';
import { useActor, validateActorName } from './ActorContext';
import styles from './ActorBar.module.css';

export function ActorBar() {
  const { actor, setActor } = useActor();
  const [draft, setDraft] = useState(actor);
  const [error, setError] = useState<string | null>(null);

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const validationError = validateActorName(draft);
    if (validationError !== null) {
      setError(validationError);
      return;
    }
    setError(null);
    setActor(draft.trim());
  }

  return (
    <div className={styles.bar}>
      <form className={styles.form} onSubmit={handleSubmit}>
        <label className={styles.label} htmlFor="actor-bar-name">
          Ingelogd als
        </label>
        <input
          id="actor-bar-name"
          className={styles.input}
          type="text"
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          placeholder="Uw naam"
          maxLength={100}
        />
        <button type="submit" className={styles.button}>
          Opslaan
        </button>
        {actor !== '' && (
          <span className={styles.current}>
            Huidig: <strong>{actor}</strong>
          </span>
        )}
      </form>
      {error !== null && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}
      <p className={styles.warning}>
        Er is nog geen authenticatie. Deze naam wordt ongecontroleerd in het beslissingsregister
        bewaard.
      </p>
    </div>
  );
}
