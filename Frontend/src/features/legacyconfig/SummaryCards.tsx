/**
 * Het kaartenrooster van het detail van een ontvangst: de gedeelde component (`components/SummaryCards.tsx`) met de
 * teksten en testids van de legacy-overname. Kleur is nooit de enige drager: titel, teller en toplijn staan altijd in
 * tekst. Een lege kaart heeft een uitgeschakelde knop en toont de reden in de toplijn.
 */

import { SummaryCard as BaseCard, SummaryCards as BaseCards, cardIds } from '../../components/SummaryCards.tsx';
import type { CardModel as BaseCardModel, CardTone } from '../../components/SummaryCards.tsx';
import { LEGACY_CARD_TEXT } from '../../terms/legacyCards.ts';

export { cardIds };
export type { CardTone };

/** Op het ontvangstdetail is de toplijn verplicht. */
export type CardModel = BaseCardModel & { topLine: string };

const TEXTS = { show: LEGACY_CARD_TEXT.show, hide: LEGACY_CARD_TEXT.hide, gridLabel: LEGACY_CARD_TEXT.gridLabel };
const PREFIX = 'legacy-card';

export type SummaryCardProps = {
  baseId: string;
  card: CardModel;
  expanded: boolean;
  onToggle: (cardId: string) => void;
};

export function SummaryCard({ baseId, card, expanded, onToggle }: SummaryCardProps) {
  return <BaseCard baseId={baseId} card={card} expanded={expanded} onToggle={onToggle} texts={TEXTS} testIdPrefix={PREFIX} />;
}

export type SummaryCardsProps = {
  baseId: string;
  cards: readonly CardModel[];
  openId: string | null;
  onToggle: (cardId: string) => void;
};

export function SummaryCards({ baseId, cards, openId, onToggle }: SummaryCardsProps) {
  return (
    <BaseCards
      baseId={baseId}
      cards={cards}
      openId={openId}
      onToggle={onToggle}
      texts={TEXTS}
      testIdPrefix={PREFIX}
      gridTestId="legacy-cards"
    />
  );
}
