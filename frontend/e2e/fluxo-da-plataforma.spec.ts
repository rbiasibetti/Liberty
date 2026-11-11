import { expect, test } from '@playwright/test';

const OIDC = 'http://127.0.0.1:18081';
const slug = `cliente-${String(Date.now() % 1_000_000)}`;

test('administrador da plataforma cria um tenant, vê o que falta no onboarding e o suspende', async ({
  browser,
}) => {
  await fetch(`${OIDC}/__user?sub=admin`);
  const context = await browser.newContext({ viewport: { width: 1500, height: 950 } });
  const page = await context.newPage();
  await page.goto('/');
  await page.getByRole('button', { name: 'Entrar' }).click();
  await expect(page.getByText('Administrador da plataforma')).toBeVisible();

  await page.getByRole('link', { name: 'Tenants' }).click();
  await expect(page.getByRole('heading', { name: 'Tenants da plataforma' })).toBeVisible();
  await page.getByRole('button', { name: 'Novo tenant' }).click();
  await page.getByLabel('Identificador (slug)').fill(slug);
  await page.getByLabel('Nome').fill('Cliente de teste');
  await page.getByRole('button', { name: 'Criar tenant' }).click();
  await expect(page.getByRole('cell', { name: slug, exact: true })).toBeVisible();

  // tenant novo: nada configurado ainda
  await page.getByRole('button', { name: `Onboarding de ${slug}` }).click();
  await expect(page.getByText('Ainda falta configurar')).toBeVisible();
  await expect(page.getByText('Cadastre a conexão com o banco do cliente em HML')).toBeVisible();
  await page.keyboard.press('Escape');

  // identificador reservado é recusado pelo servidor
  await page.getByRole('button', { name: 'Novo tenant' }).click();
  await page.getByLabel('Identificador (slug)').fill('admin');
  await page.getByLabel('Nome').fill('Reservado');
  await page.getByRole('button', { name: 'Criar tenant' }).click();
  await expect(page.getByRole('dialog', { name: 'Novo tenant' })).toBeVisible();
  await page.keyboard.press('Escape');

  // suspender
  await page.getByRole('button', { name: `Editar ${slug}` }).click();
  await page.getByRole('combobox', { name: 'Estado' }).click();
  await page.getByRole('option', { name: 'SUSPENDED' }).click();
  await page.getByRole('button', { name: 'Salvar' }).click();
  const row = page.getByRole('row', { name: new RegExp(slug) });
  await expect(row.getByText('SUSPENDED')).toBeVisible();
});
