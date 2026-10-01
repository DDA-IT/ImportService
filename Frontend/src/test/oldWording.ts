/**
 * NT-11d — bewaker van de woordkeuzes van de mens (beslissingslog 2026-10-01): in zichtbare tekst staat nooit
 * "bookmark" (is "invulpunt"), "revisie" (is "versie"), "bronorganisatie" (is "leverancier of aankoopvereniging")
 * of "importdefinitie" (is "beschrijving van het bestand"), in welke hoofdlettercombinatie ook. Tooltips
 * (attributen) en de inklapbare `<details>`-blokken tellen niet mee, net als in `noRawCodes.test.tsx`.
 */
import { expect } from 'vitest';

const OLD_WORDING = /bookmark|revisie|bronorganisatie|importdefinitie/gi;

/** De zichtbare tekst zonder `<details>`; tekstknopen met een spatie gescheiden. */
function visibleTextOf(container: HTMLElement): string {
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
