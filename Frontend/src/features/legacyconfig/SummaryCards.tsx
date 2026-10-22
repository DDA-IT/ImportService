/**
 * Het kaartenrooster van het detail van een ontvangst: per kaart een titel, een grote teller, één toplijn en een knop
 * die het detailpaneel onder het rooster opent. Kleur is nooit de enige drager: titel, teller en toplijn staan altijd
 * in tekst. Een lege kaart heeft een uitgeschakelde knop en toont de reden in de toplijn.
 */

import { LEGACY_CARD_TEXT } from '../../terms/legacyCards.ts';
import styles from './LegacyConfig.module.css';

export type CardTone = 'danger' | 'warning' | 'success' | 'neutral';

export type CardModel = {
  id: string;
  title: string;
  /** De grote waarde; een getal of een korte tekst. */
  count: number | string;
  topLine: string;
  /** Optionele regel onder de toplijn, bv. de deeltellers. */
  subLine?: string;
  tone: CardTone;
  /** Niets te tonen: de knop staat uit en de toplijn legt waarom. */
  empty: boolean;
};

/** De id's waarmee een kaart en haar paneel naar elkaar verwijzen. */
export function cardIds(baseId: string, cardId: string) {
  return { title: `${baseId}-title-${cardId}`, button: `${baseId}-button-${cardId}`, panel: `${baseId}-panel-${cardId}` };
}

const TONE_CLASS: Record<CardTone, string | undefined> = {
  danger: styles.cardDanger,
  warning: styles.cardWarning,
  success: styles.cardSuccess,
  neutral: styles.cardNeutral,
};

export type SummaryCardProps = {
  baseId: string;
  card: CardModel;
  expanded: boolean;
  onToggle: (cardId: string) => void;
};

export function SummaryCard({ baseId, card, expanded, onToggle }: SummaryCardProps) {
  const ids = cardIds(baseId, card.id);
  const open = expanded && !card.empty;
  return (
    <section
      className={`${styles.summaryCard} ${TONE_CLASS[card.tone] ?? ''} ${open ? styles.summaryCardOpen : ''}`}
      role="group"
      aria-labelledby={ids.title}
      data-testid={`legacy-card-${card.id}`}
    >
      <h2 id={ids.title} className={styles.summaryTitle}>
        {card.title}
      </h2>
      <p className={styles.summaryCount} data-testid={`legacy-card-count-${card.id}`}>
        {card.count}
      </p>
      <p className={styles.summaryTop}>{card.topLine}</p>
      {card.subLine !== undefined && <p className={styles.summarySub}>{card.subLine}</p>}
      <button
        type="button"
        id={ids.button}
        className={styles.summaryButton}
        aria-expanded={open}
        aria-controls={ids.panel}
        aria-labelledby={`${ids.button} ${ids.title}`}
        disabled={card.empty}
        onClick={() => onToggle(card.id)}
      >
        {open ? LEGACY_CARD_TEXT.hide : LEGACY_CARD_TEXT.show}
      </button>
    </section>
  );
}

export type SummaryCardsProps = {
  baseId: string;
  cards: readonly CardModel[];
  openId: string | null;
  onToggle: (cardId: string) => void;
};

export function SummaryCards({ baseId, cards, openId, onToggle }: SummaryCardsProps) {
  return (
    <div className={styles.cardGrid} role="group" aria-label={LEGACY_CARD_TEXT.gridLabel} data-testid="legacy-cards">
      {cards.map((card) => (
        <SummaryCard key={card.id} baseId={baseId} card={card} expanded={openId === card.id} onToggle={onToggle} />
      ))}
    </div>
  );
}
