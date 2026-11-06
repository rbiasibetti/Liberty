// Leitura paginada (100 linhas) a 50 req/s por tenant. Exige E11 e uma integração `pedidos-k6` publicada.
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, TENANTS, SLO, pick } from '../lib/config.js';

export const options = {
  scenarios: {
    leitura: {
      executor: 'constant-arrival-rate',
      rate: SLO.perTenantRps * Math.max(TENANTS.length, 1),
      timeUnit: '1s',
      duration: '10m',
      preAllocatedVUs: 100,
      maxVUs: 300,
    },
  },
  thresholds: {
    http_req_failed: [`rate<${1 - SLO.availability}`],
    'http_req_duration{status:200}': [`p(95)<${SLO.selectP95Ms}`],
  },
};

export default function () {
  const tenant = pick(TENANTS, __ITER);
  const res = http.get(`${BASE_URL}/${tenant.slug}/api/pedidos-k6?limit=100&offset=0`, {
    headers: { Authorization: `Bearer ${tenant.token}` },
  });
  check(res, { 'ok (200)': (r) => r.status === 200 });
}
