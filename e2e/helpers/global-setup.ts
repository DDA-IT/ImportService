const SUMMARY = 'http://localhost:8081/api/catalog-import/batches/summary';

export default async function globalSetup() {
  let status: number | string;
  try {
    status = (await fetch(SUMMARY)).status;
  } catch (e) {
    status = `geen verbinding (${(e as Error).message})`;
  }
  if (status !== 200) {
    throw new Error(
      `De backend is niet bereikbaar op ${SUMMARY} (antwoord: ${status}). ` +
        'Start eerst de backend met profielen local,demo (zie e2e/README.md) en wacht tot hij klaar is (1-4 minuten).',
    );
  }
}
