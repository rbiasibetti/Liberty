import type { ApiClient } from '../api/client';
import { describeError } from '../api/problem';
import type { TestCase } from './model';

export interface TestResult {
  name: string;
  passed: boolean;
  reason?: string;
}

/** `expected` está contido em `actual`: objetos por propriedade, listas por elemento, valores por igualdade. */
export function contains(expected: unknown, actual: unknown): boolean {
  if (Array.isArray(expected)) {
    return (
      Array.isArray(actual) &&
      expected.every((item) => actual.some((candidate) => contains(item, candidate)))
    );
  }
  if (typeof expected === 'object' && expected !== null) {
    return (
      typeof actual === 'object' &&
      actual !== null &&
      Object.entries(expected).every(([key, value]) =>
        contains(value, (actual as Record<string, unknown>)[key]),
      )
    );
  }
  return expected === actual;
}

/** Roda os casos de teste da versão no sandbox (HML, transação desfeita) e confere as expectativas. */
export async function runTests(
  api: ApiClient,
  slug: string,
  key: string,
  versionNo: number,
  tests: TestCase[],
): Promise<TestResult[]> {
  const results: TestResult[] = [];
  for (const test of tests) {
    try {
      const { data } = await api.POST(
        '/api/v1/tenants/{slug}/integrations/{key}/versions/{no}/sandbox',
        {
          params: { path: { slug, key, no: versionNo } },
          body: { params: test.request?.params ?? {}, body: test.request?.body ?? null },
        },
      );
      if (!data) {
        results.push({ name: test.name, passed: false, reason: 'Sem resposta do sandbox' });
      } else if (data.status !== test.expect.status) {
        results.push({
          name: test.name,
          passed: false,
          reason: `Esperado status ${String(test.expect.status)}, veio ${String(data.status)}`,
        });
      } else if (
        test.expect.bodyContains !== undefined &&
        !contains(test.expect.bodyContains, data.body)
      ) {
        results.push({ name: test.name, passed: false, reason: 'O corpo não contém o esperado' });
      } else {
        results.push({ name: test.name, passed: true });
      }
    } catch (error) {
      results.push({ name: test.name, passed: false, reason: describeError(error) });
    }
  }
  return results;
}
