import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';

const HISTORY_CASES = Object.freeze([
  'empty',
  'one-short',
  'max-count',
  'high-pressure',
]);
const HISTORY_CASE = __ENV.HISTORY_CASE;
if (!HISTORY_CASE || !HISTORY_CASES.includes(HISTORY_CASE)) {
  throw new Error(
    `HISTORY_CASE is required; expected one of: ${HISTORY_CASES.join(' ')}`,
  );
}

function requiredPositiveInteger(name) {
  const raw = __ENV[name];
  if (!raw || !/^[1-9][0-9]*$/.test(raw)) {
    throw new Error(`${name} is required and must be a positive integer`);
  }
  return Number(raw);
}

const RATE = requiredPositiveInteger('RATE');
const DURATION_SECONDS = requiredPositiveInteger('DURATION_SECONDS');
const TARGET_SESSION_COUNT = requiredPositiveInteger('TARGET_SESSION_COUNT');
const RUN_ID = __ENV.RUN_ID;
if (!RUN_ID) {
  throw new Error('RUN_ID is required');
}
const BASE = __ENV.BASE_URL || 'http://127.0.0.1:8001';
const MESSAGE = 'hello, can you tell me about delivery times';
const pool = new SharedArray('warm-history-pool', () => JSON.parse(open(__ENV.POOL_FILE)));

const outcomes = new Counter('agent_outcomes');
const exhausted = new Counter('pool_exhausted');

export const options = {
  scenarios: {
    warm_history: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: `${DURATION_SECONDS}s`,
      preAllocatedVUs: Math.max(50, RATE * 2),
      maxVUs: Math.max(200, RATE * 20),
      gracefulStop: '45s',
      exec: 'turn',
      tags: { case: HISTORY_CASE, rate: String(RATE) },
    },
  },
  discardResponseBodies: false,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    pool_exhausted: [{ threshold: 'count == 0', abortOnFail: true }],
  },
};

export function turn() {
  const index = exec.scenario.iterationInTest;
  if (index >= TARGET_SESSION_COUNT || index >= pool.length) {
    exhausted.add(1);
    throw new Error(
      `warm fixture of ${TARGET_SESSION_COUNT} sessions (pool ${pool.length}) exhausted at index ${index}`,
    );
  }
  const entry = pool[index];
  const response = http.post(
    `${BASE}/api/chat`,
    JSON.stringify({ message: MESSAGE }),
    {
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${entry.token}`,
        'X-Session-Id': entry.sessionId,
        'Idempotency-Key': `${RUN_ID}-warm-${HISTORY_CASE}-${index}`,
      },
      tags: {
        case: HISTORY_CASE,
        rate: String(RATE),
        path: 'chat',
        expected_tool_profile: 'read',
      },
    },
  );

  let outcome;
  try {
    outcome = (response.json() || {}).outcome || `HTTP_${response.status}`;
  } catch (error) {
    outcome = `HTTP_${response.status}`;
  }
  outcomes.add(1, {
    case: HISTORY_CASE,
    outcome: String(outcome),
    path: 'chat',
    rate: String(RATE),
  });
}
