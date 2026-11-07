import { useCallback } from 'react';
import { useApi } from '../api/apiContext';
import type { components } from '../api/schema';
import { useRequest } from '../api/useRequest';
import type { RequestState } from '../api/useRequest';

export type CatalogTable = components['schemas']['CatalogTable'];
export type CatalogTableDetail = components['schemas']['CatalogTableDetail'];

/** Tabelas e views do banco do tenant em HML (onde o integrador desenvolve e testa). */
export function useCatalogTables(slug: string): RequestState<CatalogTable[]> {
  const api = useApi();
  const run = useCallback(async () => {
    const { data } = await api.GET('/api/v1/tenants/{slug}/environments/{env}/catalog/tables', {
      params: { path: { slug, env: 'HML' } },
    });
    return data ?? [];
  }, [api, slug]);
  return useRequest(`catalog:${slug}`, run);
}

/** Colunas de uma tabela; sem referência, não consulta nada. */
export function useCatalogTable(
  slug: string,
  reference: string,
): RequestState<CatalogTableDetail | undefined> {
  const api = useApi();
  const run = useCallback(async () => {
    if (!reference) {
      return undefined;
    }
    const { data } = await api.GET(
      '/api/v1/tenants/{slug}/environments/{env}/catalog/tables/{table}',
      { params: { path: { slug, env: 'HML', table: reference } } },
    );
    return data;
  }, [api, slug, reference]);
  return useRequest(`catalog:${slug}:${reference}`, run);
}
