import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';

// One ladder per path. Mixing the three in one run would make each step's percentile a blend of
// three different amounts of work, so the path is fixed for a run and named by PATH_NAME.
const PATH_NAME = __ENV.PATH_NAME || 'chat';
const RATES = (__ENV.RATES || '10,20,40,80,160').split(',').map(Number);
const STEP_SECONDS = Number(__ENV.STEP_SECONDS || 20);
const GAP_SECONDS = Number(__ENV.GAP_SECONDS || 5);
const RUN_ID = __ENV.RUN_ID || 'run';
// Each path takes a disjoint region of the pool, so running all three over one fixture still
// gives every iteration its own user, order and session.
const POOL_BASE = Number(__ENV.POOL_BASE || 0);
const BASE = __ENV.BASE_URL || 'http://127.0.0.1:8001';

// Each entry is a token, the paid order that token's user owns, and a session already opened for
// that user. All three must come from the same entry: a session used with another user's token is
// rejected as a conversation ownership error rather than measured.
const pool = new SharedArray('pool', () => JSON.parse(open(__ENV.POOL_FILE)));

const outcomes = new Counter('agent_outcomes');

// The fixture model picks its behaviour from the message text.
//   chat       no keyword, so the fixture answers directly: one model call and nothing else.
//   retrieval  alias resolution, mapping validation, BM25 and dense retrieval, rerank, then the
//              closing model call.
//   prepare    a refund preparation tool call, which exchanges an on-behalf-of token and writes a
//              PendingAction through commerce.
function message(entry) {
  if (PATH_NAME === 'retrieval') {
    return 'retrieval-sufficient what does the refund policy cover';
  }
  if (PATH_NAME === 'prepare') {
    // The order id travels in the message so the fixture prepares against an order this user
    // actually owns; a foreign order is rejected as ACTION_PREPARATION_TARGET_NOT_FOUND.
    return `action-prepare refund my order ${entry.orderId}`;
  }
  return 'hello, can you tell me about delivery times';
}

// Each step gets a disjoint slice of the pool, so no user, order or session is ever used twice.
//
// maxVUs is sized for the collapsed steps rather than the healthy ones. An open-model executor
// still needs a free VU to start an iteration, so once latency reaches tens of seconds a modest
// VU pool becomes the binding constraint and the step's achieved rate describes the generator
// rather than the server. Twenty seconds of headroom per step keeps that out of the way.
const scenarios = {};
let offset = POOL_BASE;
let start = 0;
for (const rate of RATES) {
  scenarios[`rate_${rate}`] = {
    executor: 'constant-arrival-rate',
    rate: rate,
    timeUnit: '1s',
    duration: `${STEP_SECONDS}s`,
    preAllocatedVUs: Math.max(50, rate * 2),
    maxVUs: Math.max(200, rate * 20),
    startTime: `${start}s`,
    exec: 'turn',
    env: { POOL_OFFSET: String(offset) },
    tags: { rate: String(rate) },
  };
  offset += rate * STEP_SECONDS + 20;
  start += STEP_SECONDS + GAP_SECONDS;
}

export const options = {
  scenarios,
  discardResponseBodies: false,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {},
};

export function turn() {
  const rate = exec.scenario.name.replace('rate_', '');
  const index = Number(__ENV.POOL_OFFSET) + exec.scenario.iterationInTest;
  if (index >= pool.length) {
    // Wrapping would silently reuse an entry and change which path is measured, so the run fails
    // loudly instead: the pool is too small for this ladder.
    throw new Error(`pool of ${pool.length} entries exhausted at index ${index}`);
  }
  const entry = pool[index];

  const res = http.post(
    `${BASE}/api/chat`,
    JSON.stringify({ message: message(entry) }),
    {
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${entry.token}`,
        'X-Session-Id': entry.sessionId,
        // Unique per request: a repeated key on the same session replays the stored turn instead
        // of doing the work, which would measure the replay path rather than the path named.
        'Idempotency-Key': `${RUN_ID}-${PATH_NAME}-${exec.scenario.name}-${exec.scenario.iterationInTest}`,
      },
      tags: { rate: rate, path: PATH_NAME },
    },
  );

  let outcome;
  try { outcome = (res.json() || {}).outcome || `HTTP_${res.status}`; }
  catch (e) { outcome = `HTTP_${res.status}`; }
  outcomes.add(1, { outcome: String(outcome), rate: rate, path: PATH_NAME });
}
