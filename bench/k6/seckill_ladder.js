import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';

// Fixed-rate steps rather than a continuous ramp: each step is its own steady-state window,
// so percentiles are read from a constant arrival rate instead of across a moving one.
const RATES = (__ENV.RATES || '50,100,200,400,800').split(',').map(Number);
const STEP_SECONDS = Number(__ENV.STEP_SECONDS || 15);
const GAP_SECONDS = Number(__ENV.GAP_SECONDS || 5);
const ACTIVITIES = Number(__ENV.ACTIVITIES || 1);
const BASE = __ENV.BASE_URL || 'http://citybuddy-bench-commerce:8080';

const tokens = new SharedArray('tokens', () => JSON.parse(open(__ENV.TOKENS_FILE)));

const decisions = new Counter('seckill_decisions');

// Each step gets a disjoint slice of the token pool so no user is reused: a repeat user would
// be rejected by the one-order-per-user rule and would stop exercising the admission path.
const scenarios = {};
let offset = 0;
let start = 0;
for (const rate of RATES) {
  scenarios[`rate_${rate}`] = {
    executor: 'constant-arrival-rate',
    rate: rate,
    timeUnit: '1s',
    duration: `${STEP_SECONDS}s`,
    preAllocatedVUs: Math.max(50, Math.ceil(rate * 0.8)),
    maxVUs: Math.max(200, rate * 3),
    startTime: `${start}s`,
    exec: 'reserve',
    env: { TOKEN_OFFSET: String(offset) },
    tags: { rate: String(rate) },
  };
  offset += rate * STEP_SECONDS + 50;
  start += STEP_SECONDS + GAP_SECONDS;
}

export const options = {
  scenarios,
  discardResponseBodies: false,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {},
};

export function reserve() {
  const idx = (Number(__ENV.TOKEN_OFFSET) + exec.scenario.iterationInTest) % tokens.length;
  const token = tokens[idx];
  // ACTIVITIES=1 concentrates every request on one activity row; ACTIVITIES=N spreads them.
  const activity = ACTIVITIES === 1
    ? 'bench-activity-0'
    : `bench-activity-${exec.scenario.iterationInTest % ACTIVITIES}`;

  const res = http.post(
    `${BASE}/api/seckill/activities/${activity}/reservations`,
    JSON.stringify({ quantity: 1, expectedActivityVersion: 1 }),
    {
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
        'Idempotency-Key': `k6-${exec.scenario.name}-${exec.scenario.iterationInTest}`,
      },
      tags: { rate: exec.scenario.name.replace('rate_', '') },
    },
  );

  let code = 'NONE';
  try { code = (res.json() || {}).decisionCode || `HTTP_${res.status}`; }
  catch (e) { code = `HTTP_${res.status}`; }
  decisions.add(1, { decision: String(code), rate: exec.scenario.name.replace('rate_', '') });
}
