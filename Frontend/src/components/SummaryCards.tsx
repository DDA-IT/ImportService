/**
 * Het kaartenrooster met één detailpaneel eronder: per kaart een titel, een grote teller, een optionele toplijn en
 * subregel en een knop die het detailpaneel opent. Kleur is nooit de enige drager: titel, teller en regels staan altijd
 * in tekst. Een lege kaart heeft een uitgeschakelde knop en toont de reden in de toplijn.
 *
 * Gedeeld door het detail van een ontvangst van legacy-configuratie (`features/legacyconfig`) en het
 * leveranciersdashboard (`features/setup`); de aanroeper geeft de teksten en testid-voorvoegsels mee.
 */

import type { ReactNode } from 'react';
import styles from './SummaryCards.module.css';

export type CardTone = 'danger' | 'warning' | 'success' | 'neutral';

export type CardModel = {
  id: string;
  title: string;
  /** De grote waarde; een getal of een korte tekst. */
  count: number | string;
  /** Eén vetgedrukte toplijn onder de teller (optioneel). */
  topLine?: string;
  /** Optionele regel onder de toplijn, bv. de deeltellers. */
  subLine?: string;
  tone: CardTone;
  /** Niets te tonen: de knop staat uit en de toplijn legt waarom. */
  empty: boolean;
  /** Eigen knoptekst als de kaart niet "Tonen"/"Verbergen" zegt (bv. "Toon wat ontbreekt"). */
  showLabel?: string;
  /** Extra inhoud onderaan de kaart (bv. een link). */
  footer?: ReactNode;
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

export type CardTexts = {
  show: string;
  hide: string;
  gridLabel: string;
};

export type SummaryCardProps = {
  baseId: string;
  card: CardModel;
  expanded: boolean;
  onToggle: (cardId: string) => void;
  texts: CardTexts;
  /** Voorvoegsel van de testids: `<prefix>-<id>` en `<prefix>-count-<id>`. */
  testIdPrefix: string;
};

export function SummaryCard({ baseId, card, expanded, onToggle, texts, testIdPrefix }: SummaryCardProps) {
  const ids = cardIds(baseId, card.id);
  const open = expanded && !card.empty;
  return (
    <section
      className={`${styles.summaryCard} ${TONE_CLASS[card.tone] ?? ''} ${open ? styles.summaryCardOpen : ''}`}
      role="group"
      aria-labelledby={ids.title}
      data-testid={`${testIdPrefix}-${card.id}`}
    >
      <h2 id={ids.title} className={styles.summaryTitle}>
        {card.title}
      </h2>
      <p className={styles.summaryCount} data-testid={`${testIdPrefix}-count-${card.id}`}>
        {card.count}
      </p>
      {card.topLine !== undefined && <p className={styles.summaryTop}>{card.topLine}</p>}
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
        {open ? texts.hide : (card.showLabel ?? texts.show)}
      </button>
      {card.footer}
    </section>
  );
}

export type SummaryCardsProps = {
  baseId: string;
  cards: readonly CardModel[];
  openId: string | null;
  onToggle: (cardId: string) => void;
  texts: CardTexts;
  testIdPrefix: string;
  /** testid van het rooster zelf. */
  gridTestId: string;
};

export function SummaryCards({ baseId, cards, openId, onToggle, texts, testIdPrefix, gridTestId }: SummaryCardsProps) {
  return (
    <div className={styles.cardGrid} role="group" aria-label={texts.gridLabel} data-testid={gridTestId}>
      {cards.map((card) => (
        <SummaryCard
          key={card.id}
          baseId={baseId}
          card={card}
          expanded={openId === card.id}
          onToggle={onToggle}
          texts={texts}
          testIdPrefix={testIdPrefix}
        />
      ))}
    </div>
  );
}

export type DetailPanelProps = {
  baseId: string;
  cardId: string;
  title: string;
  children: ReactNode;
  testId?: string;
};

/** Het ene detailpaneel onder het rooster; verwijst met `aria-labelledby` naar de kaarttitel. */
export function DetailPanel({ baseId, cardId, title, children, testId }: DetailPanelProps) {
  const ids = cardIds(baseId, cardId);
  return (
    <section
      id={ids.panel}
      className={styles.detailPanel}
      role="region"
      aria-labelledby={`${ids.panel}-heading`}
      data-testid={testId}
    >
      <h3 id={`${ids.panel}-heading`} className={styles.detailTitle}>
        {title}
      </h3>
      {children}
    </section>
  );
}

export type MiniCardProps = { id: string; title: string; value: ReactNode; tone: CardTone; testId?: string };

/** Een kleine kaart zonder knop: titel en één waarde (bv. op het overzicht van een leverancier). */
export function MiniCard({ id, title, value, tone, testId }: MiniCardProps) {
  return (
    <section
      className={`${styles.summaryCard} ${TONE_CLASS[tone] ?? ''}`}
      role="group"
      aria-labelledby={id}
      data-testid={testId}
    >
      <h3 id={id} className={styles.summaryTitle}>
        {title}
      </h3>
      <p className={`${styles.summaryCount} ${styles.miniCount}`}>{value}</p>
    </section>
  );
}
