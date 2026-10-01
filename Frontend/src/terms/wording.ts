/**
 * NT-11d — de gekozen woorden voor het begrip dat de code "bookmark" noemt, op één plaats zodat een latere
 * woordwijziging een enkele aanpassing is. Alleen voor koppen en labels; lopende zinnen mogen het woord
 * gewoon uitschrijven (de guard in `src/test/noRawCodes.test.tsx` bewaakt dat het oude woord nergens terugkomt).
 * De technische namen (API, JSON, codes, testids) blijven ongewijzigd.
 */
export const INVULPUNT = 'invulpunt';
export const INVULPUNTEN = 'invulpunten';

function capitalise(word: string): string {
  return `${word.charAt(0).toUpperCase()}${word.slice(1)}`;
}

export const INVULPUNT_CAP = capitalise(INVULPUNT);
export const INVULPUNTEN_CAP = capitalise(INVULPUNTEN);
