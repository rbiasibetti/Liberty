import { expect, test } from '@playwright/test';
import type { Browser, Page } from '@playwright/test';

const OIDC = 'http://127.0.0.1:18081';

/** Entra como o usuário indicado (o OIDC falso aprova sozinho, mas exige PKCE de verdade). */
async function loginAs(browser: Browser, sub: string): Promise<Page> {
  await fetch(`${OIDC}/__user?sub=${sub}`);
  const context = await browser.newContext({ viewport: { width: 1500, height: 950 } });
  const page = await context.newPage();
  await page.goto('/');
  await page.getByRole('button', { name: 'Entrar' }).click();
  await expect(page.getByText('Integrações de acme')).toBeVisible();
  return page;
}

async function pick(page: Page, label: string, option: string, index = 0) {
  await page.getByRole('combobox', { name: label }).nth(index).click();
  await page.getByRole('option', { name: option, exact: true }).first().click();
}

const suffix = String(Date.now() % 1_000_000);
const KEY = `pedidos-${suffix}`;

test.describe.configure({ mode: 'serial' });

test('integrador cria uma escrita master-detail, testa no sandbox e envia para revisão; aprovador publica e faz rollback', async ({
  browser,
}) => {
  // ---- integrador
  const integrator = await loginAs(browser, 'integrador');
  await integrator.getByRole('button', { name: 'Nova integração' }).click();
  await integrator.getByLabel('Chave').fill(KEY);
  await integrator.getByLabel('Nome').fill('Pedidos');
  await integrator.getByRole('combobox', { name: 'Método' }).click();
  await integrator.getByRole('option', { name: 'POST' }).click();
  await integrator.getByLabel('Rota').fill(`/${KEY}`);
  await integrator.getByRole('button', { name: 'Criar' }).click();
  await expect(integrator.getByRole('heading', { name: 'Pedidos' })).toBeVisible();

  // schema a partir de um exemplo
  await integrator.getByRole('tab', { name: 'JSON Schema' }).click();
  await integrator.getByRole('button', { name: 'Gerar a partir de um exemplo' }).click();
  await integrator
    .getByRole('textbox', { name: 'JSON de exemplo' })
    .fill('{"cliente":"Acme","total":10.5,"itens":[{"sku":"A-1","quantidade":2}]}');
  await integrator.getByRole('button', { name: 'Gerar', exact: true }).click();

  // mapeamento em 2 níveis
  await integrator.getByRole('tab', { name: 'Mapeamento' }).click();
  await pick(integrator, 'Tabela', 'dbo.tb_pedido');
  await pick(integrator, 'Chave primária gerada', 'id'); // só há opções depois que o catálogo carrega
  await integrator.getByRole('button', { name: 'Mapear pelo nome' }).first().click();
  await expect(integrator.getByRole('combobox', { name: 'Coluna', exact: true })).toHaveCount(2);
  await integrator.getByRole('button', { name: 'Adicionar nível filho (lista)' }).click();
  await pick(integrator, 'Tabela', 'dbo.tb_pedido_item', 1);
  await integrator.getByRole('combobox', { name: 'Lista no corpo (arrayPath)' }).fill('/itens');
  await pick(integrator, 'Coluna da chave estrangeira', 'pedido_id');
  await integrator.getByRole('button', { name: 'Mapear pelo nome' }).nth(1).click();
  await expect(integrator.getByRole('combobox', { name: 'Coluna', exact: true })).toHaveCount(4);

  // salva e testa no sandbox (nada é gravado)
  await integrator.getByRole('button', { name: 'Salvar rascunho' }).click();
  await expect(integrator.getByText('v1 · DRAFT')).toBeVisible();
  await integrator
    .getByRole('textbox', { name: 'Corpo (JSON)' })
    .fill('{"cliente":"Acme","total":10.5,"itens":[{"sku":"A-1","quantidade":2}]}');
  await integrator.getByRole('button', { name: 'Executar' }).click();
  await expect(integrator.getByText('sucesso')).toBeVisible();
  await expect(integrator.getByText(/desfeito/)).toBeVisible();

  // envia para revisão
  await integrator.getByRole('button', { name: 'Enviar para revisão' }).click();
  await expect(integrator).toHaveURL(new RegExp(`/integracoes/${KEY}/versoes/1`));

  // quem só integra não vê a decisão de publicar
  await expect(integrator.getByRole('heading', { name: 'Testes' })).toBeVisible();
  await expect(integrator.getByRole('button', { name: /Aprovar e publicar/ })).toHaveCount(0);

  // ---- aprovador: v1 em HML e depois em PRD
  const approver = await loginAs(browser, 'aprovador');
  await approver.goto(`/integracoes/${KEY}/versoes/1`);
  await approver.getByRole('button', { name: 'Aprovar e publicar em HML' }).click();
  await expect(approver.getByRole('button', { name: 'Promover para PRD' })).toBeEnabled();
  await approver.getByRole('button', { name: 'Promover para PRD' }).click();
  await expect(approver.getByRole('button', { name: 'Promover para PRD' })).toBeDisabled();
  await approver.screenshot({ path: 'test-results/publicada.png' });

  // ---- v2 do integrador, publicada em HML pelo aprovador, e rollback para a v1 em menos de 1 min
  await integrator.goto(`/integracoes/${KEY}`);
  await integrator.getByRole('tab', { name: 'Rota e filtros' }).click();
  await integrator
    .getByRole('switch', { name: 'Exige o cabeçalho Idempotency-Key' })
    .check({ force: true });
  await integrator.getByRole('button', { name: 'Salvar como nova versão' }).click();
  await expect(integrator.getByText(/v2 ·/)).toBeVisible();
  await integrator.getByRole('button', { name: 'Enviar para revisão' }).click();
  await expect(integrator).toHaveURL(new RegExp(`/integracoes/${KEY}/versoes/2`));

  await approver.goto(`/integracoes/${KEY}/versoes/2`);
  await approver.getByRole('button', { name: 'Aprovar e publicar em HML' }).click();
  await expect(approver.getByRole('button', { name: 'Publicar em HML' })).toBeDisabled();
  const started = Date.now();
  await approver.getByRole('button', { name: 'Rollback em HML' }).click();
  await expect(approver.getByRole('button', { name: 'Publicar em HML' })).toBeEnabled();
  expect(Date.now() - started, 'rollback em menos de 1 minuto').toBeLessThan(60_000);
  await approver.goto(`/integracoes/${KEY}/versoes/1`);
  await expect(approver.getByRole('button', { name: 'Publicar em HML' })).toBeDisabled();
  await approver.screenshot({ path: 'test-results/rollback.png' });
});
