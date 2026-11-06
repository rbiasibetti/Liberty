// Um tenant a 10 vezes o plano não pode degradar os demais (SLO de isolamento). Exige E18 e 2 ou mais tenants.
// O primeiro tenant de TENANTS é o ruidoso; os demais são o grupo de controle.
import http from 'k6/http';
import { BASE_URL, TENANTS, SLO } from '../lib/config.js';

const quiet = TENANTS.slice(1);

export const options = {
  scenarios: {
    ruidoso: {
      executor: 'constant-arrival-rate',
      exec: 'noisy',
      rate: SLO.perTenantRps * 10,
      timeUnit: '1s',
      duration: '5m',
      preAllocatedVUs: 200,
      maxVUs: 800,
    },
    controle: {
      executor: 'constant-arrival-rate',
      exec: 'control',
      rate: SLO.perTenantRps * Math.max(quiet.length, 1),
      timeUnit: '1s',
      duration: '5m',
      preAllocatedVUs: 100,
      maxVUs: 300,
    },
  },
  thresholds: {
    // O controle precisa ficar dentro do SLO normal e sem erros; o ruidoso leva 429 e isso é esperado.
    'http_req_duration{grupo:controle,status:200}': [`p(95)<${SLO.selectP95Ms * (1 + SLO.noisyNeighborVariation)}`],
    'http_req_failed{grupo:controle}': [`rate<${1 - SLO.availability}`],
  },
};

function get(tenant, grupo) {
  return http.get(`${BASE_URL}/${tenant.slug}/api/pedidos-k6?limit=100`, {
    headers: { Authorization: `Bearer ${tenant.token}` },
    tags: { grupo },
    responseCallback: http.expectedStatuses(200, grupo === 'ruidoso' ? 429 : 200),
  });
}

export function noisy() {
  get(TENANTS[0], 'ruidoso');
}

export function control() {
  get(quiet[__ITER % quiet.length], 'controle');
}
