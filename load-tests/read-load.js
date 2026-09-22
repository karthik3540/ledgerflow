import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = 'http://localhost:8080/api';

export const options = {
  stages: [
    { duration: '10s', target: 50 }, // Ramp up to 50 VUs
    { duration: '30s', target: 50 }, // Hold at 50 VUs
    { duration: '5s', target: 0 },  // Ramp down to 0 VUs
  ],
  thresholds: {
    // Fail loudly if error rate exceeds 1%
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
};

/**
 * Setup function runs once before VU iterations start.
 * Creates 20 accounts and returns their generated IDs.
 */
export function setup() {
  const accountIds = [];
  const headers = { 'Content-Type': 'application/json' };

  for (let i = 1; i <= 20; i++) {
    const payload = JSON.stringify({
      ownerName: `Read-Load-User-${i}`,
      initialBalance: 10000.00,
    });

    const res = http.post(`${BASE_URL}/accounts`, payload, { headers });
    const success = check(res, {
      'account created (201)': (r) => r.status === 201,
    });

    if (!success) {
      throw new Error(`Failed to create setup account #${i}: status ${res.status} body ${res.body}`);
    }

    const body = JSON.parse(res.body);
    accountIds.push(body.data.id);
  }

  return { accountIds };
}

/**
 * Default VU execution loop.
 * Picks a random account ID from setup and performs GET /accounts/{id}.
 */
export default function (data) {
  const randomIndex = Math.floor(Math.random() * data.accountIds.length);
  const accountId = data.accountIds[randomIndex];

  const res = http.get(`${BASE_URL}/accounts/${accountId}`);

  check(res, {
    'GET /accounts/{id} returns 200': (r) => r.status === 200,
  });
}
