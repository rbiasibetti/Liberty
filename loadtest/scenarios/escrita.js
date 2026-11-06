// Escrita master-detail com 100 itens a 50 req/s por tenant. Exige E12 e uma integração `pedido-k6` publicada.
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, TENANTS, SLO, pick } from '../lib/config.js';
import { pedido } from '../lib/payloads.js';

export const options = {
  scenarios: {
    escrita: {
      executor: 'ramping-arrival-rate',
      startRate: 5,
      timeUnit: '1s',
      preAllocatedVUs: 100,
      maxVUs: 400,
      stages: [
        { target: SLO.perTenantRps * Math.max(TENANTS.length, 1), duration: '2m' },
        { target: SLO.perTenantRps * Math.max(TENANTS.length, 1), duration: '10m' },
        { target: 0, duration: '1m' },
      ],
    },
  },
  thresholds: {
    http_req_failed: [`rate<${1 - SLO.availability}`],
    'http_req_duration{status:201}': [`p(95)<${SLO.insertP95Ms}`, `p(99)<${SLO.insertP99Ms}`],
  },
};

export default function () {
  const tenant = pick(TENANTS, __ITER);
  const res = http.post(`${BASE_URL}/${tenant.slug}/api/pedido-k6`, pedido(__VU, __ITER), {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${tenant.token}`,
      'Idempotency-Key': `k6-${__VU}-${__ITER}-${Date.now()}`,
    },
  });
  check(res, { 'criado (201)': (r) => r.status === 201 });
}
