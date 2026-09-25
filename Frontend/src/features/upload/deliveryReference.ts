/**
 * Deterministische `deliveryReference` (beslissing 2026-09-23, stap 9): afgeleid van bestandsnaam én
 * inhoud, nooit van een tijdstempel. Zo is een herhaalde upload van hetzelfde bestand een idempotente
 * retry (backend: 200, geen nieuwe screening), en krijgt een gecorrigeerd bestand vanzelf een nieuwe
 * referentie (anders 409 `DELIVERY_REFERENCE_REUSED_WITH_DIFFERENT_CONTENT`).
 *
 * Vorm: `<bestandsnaam>#<eerste 12 hex-tekens van de SHA-256 van de inhoud>`, de naam zo nodig
 * ingekort zodat het geheel de servergrens van 190 tekens haalt.
 */

export const MAX_DELIVERY_REFERENCE_LENGTH = 190;
const HASH_PREFIX_LENGTH = 12;

/** `Blob.arrayBuffer()` bestaat in elke browser; de `FileReader`-terugval dekt oudere DOM-omgevingen (jsdom). */
function readBytes(file: File): Promise<ArrayBuffer> {
  if (typeof file.arrayBuffer === 'function') {
    return file.arrayBuffer();
  }
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as ArrayBuffer);
    reader.onerror = () => reject(reader.error);
    reader.readAsArrayBuffer(file);
  });
}

export async function deriveDeliveryReference(file: File): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', await readBytes(file));
  const hex = Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('')
    .slice(0, HASH_PREFIX_LENGTH);
  const suffix = `#${hex}`;
  return `${file.name.slice(0, MAX_DELIVERY_REFERENCE_LENGTH - suffix.length)}${suffix}`;
}
