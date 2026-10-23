/**
 * NT-11d + N2 — bewaker van de woordkeuzes van de mens (beslissingslog 2026-10-01 en 2026-10-23): in zichtbare tekst
 * staat nooit "bookmark" (is "invulpunt"), "revisie" (is "versie"), "bronorganisatie" (is "leverancier of
 * aankoopvereniging"), en sinds N2 ook nooit meer "batch" (is "inlezing"), "bundel"/"publicatiebundel" (is
 * "publicatie"), "mutatie" (is "wijziging"), "behandelgeval" (is "afwijking"), "sjabloon" (is "importdefinitie" of
 * "herbruikbare importdefinitie"), "werkvoorraad" (is "inlezingen") en "beschrijving van het bestand" (is
 * "importdefinitie"), in welke hoofdlettercombinatie ook. "Gebundelde meldingen" (gegroepeerd) is een ander woord en
 * valt er niet onder. Tooltips (attributen) en de inklapbare `<details>`-blokken (technische details) tellen niet mee,
 * net als in `noRawCodes.test.tsx`.
 */
import { expect } from 'vitest';

const OLD_WORDING =
  /bookmark|revisie|bronorganisatie|\bbatch(?:es)?\b|(?<![a-z])bundel|publicatiebundel|\bmutatie|behandelgeval|sjablo|werkvoorraad|beschrijving van het bestand/gi;

/** De zichtbare tekst zonder `<details>`; tekstknopen met een spatie gescheiden. */
export function visibleTextOf(container: HTMLElement): string {
  const clone = container.cloneNode(true) as HTMLElement;
  clone.querySelectorAll('details').forEach((element) => element.remove());
  const parts: string[] = [];
  const walker = document.createTreeWalker(clone, NodeFilter.SHOW_TEXT);
  for (let node = walker.nextNode(); node !== null; node = walker.nextNode()) {
    parts.push(node.textContent ?? '');
  }
  return parts.join(' ');
}

export function oldWordingIn(container: HTMLElement): string[] {
  return visibleTextOf(container).match(OLD_WORDING) ?? [];
}

export function expectNoOldWording(container: HTMLElement, what: string): void {
  expect(oldWordingIn(container), `${what}: oud woord als zichtbare tekst (NT-11d)`).toEqual([]);
}
