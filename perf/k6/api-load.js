// API performance test for the demo app (k6, https://k6.io).
//
//   k6 run perf/k6/api-load.js                       # load profile against http://localhost:8000
//   k6 run -e PROFILE=smoke perf/k6/api-load.js      # 1 user, 30 s: checks the script and the thresholds
//   k6 run -e BASE_URL=https://qa.example.com -e AUTH_USERNAME=... -e AUTH_PASSWORD=... perf/k6/api-load.js
//
// Thresholds are the pass/fail gate: k6 exits non-zero when one is crossed, which fails the CI job.
// Credentials come from the environment; the defaults are the demo app's throwaway local values.
import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8000';
const PROFILE = __ENV.PROFILE || 'load';

const profiles = {
  // Sanity check of the script: one user for 30 seconds.
  smoke: { executor: 'constant-vus', vus: 1, duration: '30s' },
  // Normal expected traffic: ramp to 20 users, hold, ramp down.
  load: {
    executor: 'ramping-vus',
    startVUs: 0,
    stages: [
      { duration: '30s', target: 20 },
      { duration: '1m', target: 20 },
      { duration: '15s', target: 0 },
    ],
  },
  // Find the breaking point: ramp well past normal traffic.
  stress: {
    executor: 'ramping-vus',
    startVUs: 0,
    stages: [
      { duration: '1m', target: 50 },
      { duration: '1m', target: 100 },
      { duration: '30s', target: 0 },
    ],
  },
};

if (!profiles[PROFILE]) {
  throw new Error(`Unknown PROFILE '${PROFILE}': use ${Object.keys(profiles).join(', ')}`);
}

const createUserTime = new Trend('create_user_duration', true);

export const options = {
  scenarios: { api: profiles[PROFILE] },
  thresholds: {
    http_req_failed: ['rate<0.01'],                  // under 1% errors
    http_req_duration: ['p(95)<500', 'p(99)<1000'],  // overall latency
    'http_req_duration{endpoint:list_users}': ['p(95)<300'],
    'http_req_duration{endpoint:get_user}': ['p(95)<300'],
    create_user_duration: ['p(95)<500'],
    checks: ['rate>0.99'],
  },
  // Keep reports free of the token: k6 does not log headers, and summaries hold only metrics.
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// Runs once: one token shared by all virtual users (sign-in itself is not under test here).
export function setup() {
  const res = http.post(`${BASE_URL}/api/auth/login`, JSON.stringify({
    username: __ENV.AUTH_USERNAME || 'qa.admin',
    password: __ENV.AUTH_PASSWORD || 'local-demo-only',
  }), { headers: { 'Content-Type': 'application/json' }, tags: { endpoint: 'login' } });
  if (res.status !== 200) {
    throw new Error(`Sign-in failed with HTTP ${res.status}; check BASE_URL and credentials`);
  }
  return { token: res.json('token') };
}

export default function (data) {
  const params = (endpoint) => ({
    headers: { Authorization: `Bearer ${data.token}`, 'Content-Type': 'application/json' },
    tags: { endpoint },
  });

  group('browse users', () => {
    const list = http.get(`${BASE_URL}/api/users?page=1&size=20`, params('list_users'));
    check(list, { 'list users: 200': (r) => r.status === 200 });
  });

  group('create, read and delete a user', () => {
    const unique = `${__VU}-${__ITER}-${Date.now()}`;
    const created = http.post(`${BASE_URL}/api/users`, JSON.stringify({
      name: `Perf User ${unique}`, email: `perf.${unique}@example.com`, job: 'load test',
    }), params('create_user'));
    createUserTime.add(created.timings.duration);
    if (!check(created, { 'create user: 201': (r) => r.status === 201 })) {
      return;
    }
    const id = created.json('id');
    check(http.get(`${BASE_URL}/api/users/${id}`, params('get_user')), { 'get user: 200': (r) => r.status === 200 });
    // Clean up so repeated runs do not grow the data set.
    check(http.del(`${BASE_URL}/api/users/${id}`, null, params('delete_user')), { 'delete user: 204': (r) => r.status === 204 });
  });

  // Think time: a real user pauses between actions (k6 sleep, not a test wait).
  sleep(1);
}

export function handleSummary(data) {
  return {
    'target/perf/k6-summary.json': JSON.stringify(data, null, 2),
    stdout: textSummary(data),
  };
}

// Short plain-text summary (k6's own text summary needs a remote import, which we avoid).
function textSummary(data) {
  const m = data.metrics;
  const ms = (v) => (v === undefined ? 'n/a' : `${v.toFixed(1)} ms`);
  const lines = [
    `k6 ${PROFILE} profile against ${BASE_URL}`,
    `requests: ${m.http_reqs.values.count}, failed: ${(m.http_req_failed.values.rate * 100).toFixed(2)}%`,
    `latency p95: ${ms(m.http_req_duration.values['p(95)'])}, p99: ${ms(m.http_req_duration.values['p(99)'])}`,
    `checks passed: ${(m.checks.values.rate * 100).toFixed(2)}%`,
    'thresholds:',
  ];
  for (const [name, metric] of Object.entries(m)) {
    for (const [expr, result] of Object.entries(metric.thresholds || {})) {
      lines.push(`  ${result.ok ? 'PASS' : 'FAIL'}  ${name} ${expr}`);
    }
  }
  return lines.join('\n') + '\n';
}
