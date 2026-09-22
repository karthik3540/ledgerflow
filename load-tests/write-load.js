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
 * Creates 20 pairs of accounts (40 accounts total), each funded with 100000.
 */
export function setup() {
  const pairs = [];
  const headers = { 'Content-Type': 'application/json' };

  for (let i = 1; i <= 20; i++) {
    // Create source account
    const sourcePayload = JSON.stringify({
      ownerName: `Write-Load-Source-${i}`,
      initialBalance: 100000.00,
    });
    const sourceRes = http.post(`${BASE_URL}/accounts`, sourcePayload, { headers });
    const sourceOk = check(sourceRes, {
      'source account created (201)': (r) => r.status === 201,
    });
    if (!sourceOk) {
      throw new Error(`Failed to create source account #${i}: status ${sourceRes.status} body ${sourceRes.body}`);
    }
    const sourceId = JSON.parse(sourceRes.body).data.id;

    // Create destination account
    const destPayload = JSON.stringify({
      ownerName: `Write-Load-Dest-${i}`,
      initialBalance: 100000.00,
    });
    const destRes = http.post(`${BASE_URL}/accounts`, destPayload, { headers });
    const destOk = check(destRes, {
      'dest account created (201)': (r) => r.status === 201,
    });
    if (!destOk) {
      throw new Error(`Failed to create dest account #${i}: status ${destRes.status} body ${destRes.body}`);
    }
    const destId = JSON.parse(destRes.body).data.id;

    pairs.push({ fromId: sourceId, toId: destId });
  }

  return { pairs };
}

/**
 * Default VU execution loop.
 * Executes a POST /transfers moving 10 between its assigned account pair.
 */
export default function (data) {
  // Assign account pair based on virtual user ID
  const pairIndex = (__VU - 1) % data.pairs.length;
  const pair = data.pairs[pairIndex];

  // Generate unique idempotency key using VU, iteration, timestamp, and random suffix
  const randomSuffix = Math.floor(Math.random() * 1000000);
  const idempotencyKey = `tx-vu${__VU}-iter${__ITER}-${Date.now()}-${randomSuffix}`;

  const payload = JSON.stringify({
    fromAccountId: pair.fromId,
    toAccountId: pair.toId,
    amount: 10.00,
    idempotencyKey: idempotencyKey,
  });

  const headers = {
    'Content-Type': 'application/json',
    'X-Client-Id': `load-vu-${__VU}`,
  };

  const res = http.post(`${BASE_URL}/transfers`, payload, { headers });

  check(res, {
    'POST /transfers returns 200': (r) => r.status === 200,
  });
}
