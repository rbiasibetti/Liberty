// Parâmetros e limiares derivados de docs/requisitos-nao-funcionais.md (ADR 0018).
export const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080';

// Tenants do teste: "slug:ENV_DO_TOKEN,slug2:ENV_DO_TOKEN2". Os tokens vêm do ambiente, nunca do repositório.
export const TENANTS = (__ENV.TENANTS || '')
  .split(',')
  .filter(Boolean)
  .map((entry) => {
    const [slug, tokenVar] = entry.split(':');
    return { slug, token: __ENV[tokenVar] };
  });

export const SLO = {
  availability: 0.995, // taxa máxima de falha 0,5%
  insertP95Ms: 300,
  insertP99Ms: 800,
  selectP95Ms: 150,
  perTenantRps: 50,
  aggregateRps: 500,
  noisyNeighborVariation: 0.2,
};

export function pick(list, iteration) {
  return list[iteration % list.length];
}
