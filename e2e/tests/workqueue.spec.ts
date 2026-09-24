import { test, expect, type APIRequestContext } from '@playwright/test';
import { batchSummary, createChain, getBatch, levering1Csv, newApi, uploadCsv, type Chain } from '../helpers/api';

/**
 * Scherm 0 (`/`): telblokken, filters en paginering. Eigen keten met unieke codes; de gedeelde database
 * bevat ook batches van andere runs, dus alle asserties over "mijn batch" gaan via mijn koppelingscode.
 */
test.describe.serial('Werkvoorraad (Scherm 0)', () => {
  let api: APIRequestContext;
  let chain: Chain;
  let batchId: number;

  test.beforeAll(async () => {
    api = await newApi();
    chain = await createChain(api);
    // Foute regel (prijs "abc") in levering-1: raw 7, valid 6, rejected 1.
    batchId = (await uploadCsv(api, chain.taskId, levering1Csv(), `E2E-${chain.sfx}-1`)).batchId;
  });

  test.afterAll(async () => {
    await api.dispose();
  });

  test('laadt: telblokken, tabel met batches en Niet-vastgesteld-tegel consistent met de API', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByRole('heading', { name: 'Werkvoorraad' })).toBeVisible();

    const tiles = page.getByTestId('summary-tiles');
    await expect(tiles.getByText('Totaal', { exact: true })).toBeVisible();
    await expect(tiles.getByText('SCREENED', { exact: true })).toBeVisible();

    const summary = await batchSummary(api);
    const hasNull = summary.byValidationResult.some((entry) => entry.validationResult === null && entry.count > 0);
    const notEstablishedTile = tiles.getByText('Niet vastgesteld', { exact: true });
    if (hasNull) {
      await expect(notEstablishedTile).toBeVisible();
    } else {
      await expect(notEstablishedTile).toHaveCount(0);
    }

    // De tabel toont batches, en mijn batch staat er (nieuwste eerst).
    await expect(page.getByRole('row').filter({ hasText: chain.linkCode })).toHaveCount(1);

    // Een niet-vastgestelde teller staat als "—", nooit als 0.
    for (const dash of await page.getByTitle('niet vastgesteld').all()) {
      await expect(dash).toHaveText('—');
    }
  });

  test('filtert op koppeling en status; paginering aanwezig', async ({ page }) => {
    await page.goto('/');
    const myRow = page.getByRole('row').filter({ hasText: chain.linkCode });
    await expect(myRow).toHaveCount(1);

    // Statusfilter SCREENED: mijn batch (nieuwste) blijft. BASELINE_ACCEPTED: hij verdwijnt.
    await page.getByLabel('Status').selectOption('SCREENED');
    await expect(myRow).toHaveCount(1);
    await page.getByLabel('Status').selectOption('BASELINE_ACCEPTED');
    await expect(myRow).toHaveCount(0);
    await page.getByLabel('Status').selectOption({ label: 'Alle' });
    await expect(myRow).toHaveCount(1);

    // Koppelingsfilter (negatief): een andere koppeling kiezen laat mijn batch verdwijnen en toont alleen die koppeling.
    // Mijn eigen koppeling staat NIET in de keuzelijst (beperkt tot de eerste 200 van >1700 koppelingen, zie README).
    const linkSelect = page.getByLabel('Koppeling');
    await expect(linkSelect.locator('option')).not.toHaveCount(1);
    const other = await linkSelect.locator('option').nth(1).textContent();
    const otherCode = other!.split(' — ')[0];
    await linkSelect.selectOption({ index: 1 });
    await expect(myRow).toHaveCount(0);
    for (const row of (await page.getByRole('row').all()).slice(1)) {
      await expect(row).toContainText(otherCode);
    }
    await linkSelect.selectOption({ label: 'Alle' });
    await expect(myRow).toHaveCount(1);

    // Paginering is aanwezig.
    await expect(page.getByRole('button', { name: 'Vorige' })).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Volgende' })).toBeVisible();
    await expect(page.getByText(/\d+-\d+ van \d+/)).toBeVisible();
    await expect(page.getByLabel('Per pagina')).toBeVisible();
  });

  test('upload met foute regel: SCREENED met raw 7 / valid 6 / rejected 1 zichtbaar in de werkvoorraad', async ({ page }) => {
    // De UI toont de tellers niet (batchdetail bestaat nog niet): gerichte API-check.
    const batch = await getBatch(api, batchId);
    expect(batch.status).toBe('SCREENED');
    expect(batch.rawRecordCount).toBe(7);
    expect(batch.validRecordCount).toBe(6);
    expect(batch.rejectedRecordCount).toBe(1);

    await page.goto('/');
    await page.getByLabel('Status').selectOption('SCREENED');
    const myRow = page.getByRole('row').filter({ hasText: chain.linkCode });
    await expect(myRow).toHaveCount(1);
    await expect(myRow.getByText('SCREENED', { exact: true })).toBeVisible();
    // Er wachten creaties op goedkeuring (INITIAL_LOAD) en het eindoordeel is vastgesteld.
    await expect(myRow.getByText('Niet vastgesteld')).toHaveCount(batch.validationResult === null ? 1 : 0);
  });
});
