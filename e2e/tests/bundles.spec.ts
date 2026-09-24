import { test, expect, type APIRequestContext } from '@playwright/test';
import { ACTOR, bundleMutations, createBundleScenario, newApi } from '../helpers/api';

type Scenario = Awaited<ReturnType<typeof createBundleScenario>>;

/**
 * Bundelschermen. Ketting: keten -> levering-1 -> accept-baseline -> delta-levering -> bundel (SIMULATION).
 * Eén scenario per describe-blok; alle codes zijn uniek per run.
 */
test.describe.serial('Bundels', () => {
  let api: APIRequestContext;
  let s: Scenario;

  test.beforeAll(async () => {
    test.setTimeout(180_000);
    api = await newApi();
    s = await createBundleScenario(api);
  });

  test.afterAll(async () => {
    await api.dispose();
  });

  test.beforeEach(async ({ page }) => {
    // De actornaam leeft in sessionStorage; vooraf invullen vervangt de ActorBar-stap.
    await page.addInitScript((name) => sessionStorage.setItem('catalogimport.actor', name), ACTOR);
  });

  test('bundellijst en detail: ASSEMBLING, 1 batch, tabblad Mutaties toont mutaties', async ({ page }) => {
    await page.goto('/bundles');
    const link = page.getByRole('link', { name: s.bundleRef });
    await expect(link).toBeVisible();
    const row = page.getByRole('row').filter({ has: link });
    await expect(row.getByText('ASSEMBLING', { exact: true })).toBeVisible();

    await link.click();
    await expect(page.getByRole('heading', { name: s.bundleRef })).toBeVisible();
    await expect(page.getByText('ASSEMBLING', { exact: true }).first()).toBeVisible();
    const batchesCounter = page.getByRole('term').filter({ hasText: /^Batches$/ }).locator('xpath=following-sibling::dd[1]');
    await expect(batchesCounter).toHaveText('1');

    await page.getByRole('link', { name: 'Mutaties' }).click();
    await expect(page).toHaveURL(new RegExp(`/bundles/${s.bundleId}/mutations$`));
    const mutations = await bundleMutations(api, s.bundleId);
    expect(mutations.length).toBeGreaterThan(0);
    await expect(page.getByRole('button', { name: /^Goedkeuren mutatie \d+/ }).first()).toBeVisible();
    await expect(page.getByText(/1-\d+ van \d+/)).toBeVisible();
  });

  test('mutatielijst filters (F8): statusreden, soort, wijzigingsgroep, null als "—"', async ({ page }) => {
    await page.goto(`/bundles/${s.bundleId}/mutations`);
    const mutations = await bundleMutations(api, s.bundleId);
    await expect(page.getByText(new RegExp(`1-${mutations.length} van ${mutations.length}`))).toBeVisible();

    // Onbekende statusreden: lege lijst, geen foutbanner.
    await page.getByLabel('Statusreden').fill('BESTAAT_NIET_E2E');
    await page.getByRole('button', { name: 'Filteren' }).click();
    await expect(page.getByText('Deze bundel bevat (met deze filter) geen mutaties.')).toBeVisible();
    await expect(page.getByRole('alert')).toHaveCount(0);

    // Filters wissen: alles terug.
    await page.getByRole('button', { name: 'Filters wissen' }).click();
    await expect(page.getByText(new RegExp(`van ${mutations.length}$`))).toBeVisible();

    // Soort = UPDATE: alleen UPDATE-mutaties.
    const updates = mutations.filter((m) => m.actionType === 'UPDATE');
    expect(updates.length).toBeGreaterThan(0);
    await page.getByLabel('Soort').selectOption('UPDATE');
    await expect(page.getByText(new RegExp(`van ${updates.length}$`))).toBeVisible();

    // Wijzigingsgroep: klikken zet het (server-side) filter.
    const hash = updates[0].identityHash!;
    expect(hash).toBeTruthy();
    await page.getByRole('button', { name: `Toon de hele wijzigingsgroep ${hash}` }).click();
    await expect(page.getByLabel('Wijzigingsgroep (identityHash)')).toHaveValue(hash);
    await expect(page.getByText(/Gefilterd op wijzigingsgroep/)).toBeVisible();
    await page.getByLabel('Soort').selectOption({ label: 'Alle' }); // de hele groep, ook andere soorten
    const inGroup = mutations.filter((m) => m.identityHash === hash);
    await expect(page.getByText(new RegExp(`van ${inGroup.length}$`))).toBeVisible();

    // IMPORT_MARKER heeft geen hash: "—" en geen groepsknop.
    const marker = mutations.find((m) => m.actionType === 'IMPORT_MARKER');
    expect(marker, 'de delta-batch hoort een IMPORT_MARKER-mutatie te hebben').toBeTruthy();
    await page.getByRole('button', { name: 'Filters wissen' }).click();
    await page.getByLabel('Soort').selectOption('IMPORT_MARKER');
    await expect(page.getByText(/1-1 van 1/)).toBeVisible();
    await expect(page.getByRole('button', { name: /Toon de hele wijzigingsgroep/ })).toHaveCount(0);
    await expect(page.getByTitle('niet vastgesteld').first()).toHaveText('—');
  });

  test('individuele goedkeuring: READY_FOR_PUBLICATION, tweede keer idempotent', async ({ page }) => {
    const candidates = await bundleMutations(api, s.bundleId);
    const target = candidates.find(
      (m) => (m.actionType === 'UPDATE' || m.actionType === 'CREATE') && (m.status === 'PLANNED' || m.status === 'AWAITING_APPROVAL'),
    );
    expect(target, 'een beslisbare mutatie').toBeTruthy();

    await page.goto(`/bundles/${s.bundleId}/mutations`);
    await page.getByRole('button', { name: `Goedkeuren mutatie ${target!.id}`, exact: true }).click();
    let dialog = page.getByRole('dialog');
    await expect(dialog.getByLabel(/Naam/)).toHaveValue(ACTOR);
    await dialog.getByLabel(/Reden/).fill('E2E goedkeuring');
    await dialog.getByRole('button', { name: 'Goedkeuren' }).click();
    await expect(page.getByRole('status')).toContainText('Goedkeuring vastgelegd in het beslissingsregister.');

    const after = (await bundleMutations(api, s.bundleId)).find((m) => m.id === target!.id)!;
    expect(after.status).toBe('READY_FOR_PUBLICATION');
    expect(after.decisionId).not.toBeNull();

    // Tweede keer dezelfde goedkeuring door dezelfde persoon: idempotent (herziening vraagt een reden).
    await page.getByRole('button', { name: new RegExp(`^Goedkeuren mutatie ${target!.id}`) }).click();
    dialog = page.getByRole('dialog');
    await dialog.getByLabel(/Reden/).fill('E2E opnieuw');
    await dialog.getByRole('button', { name: 'Goedkeuren' }).click();
    await expect(page.getByRole('status')).toContainText('geen tweede regel geschreven');
    const again = (await bundleMutations(api, s.bundleId)).find((m) => m.id === target!.id)!;
    expect(again.decisionId).toBe(after.decisionId);
  });
});
