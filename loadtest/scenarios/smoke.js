// Fumaça: a aplicação sobe e responde dentro do orçamento. Roda desde a E01.
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL } from '../lib/config.js';

export const options = {
  vus: 5,
  duration: '30s',
  thresholds: {
    http_req_failed: ['rate<0.005'],
    http_req_duration: ['p(95)<150', 'p(99)<400'],
    checks: ['rate>0.995'],
  },
};

export default function () {
  const res = http.get(`${BASE_URL}/actuator/health`);
  check(res, {
    'status 200': (r) => r.status === 200,
    'status UP': (r) => r.json('status') === 'UP',
  });
}
