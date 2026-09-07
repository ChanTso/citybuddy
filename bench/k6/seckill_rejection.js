import http from 'k6/http';
import execution from 'k6/execution';
import { Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';

const rate = Number(__ENV.RATES);
const seconds = Number(__ENV.STEP_SECONDS);
const base = __ENV.BASE_URL || 'http://citybuddy-bench-commerce:8080';
const prefix = __ENV.REQUEST_KEY_PREFIX;
const activities = __ENV.ACTIVITY_PREFIX;
const tokens = new SharedArray('rejection-tokens', () => JSON.parse(open(__ENV.TOKENS_FILE)));
const pool = 16384;
const decisions = new Counter('seckill_decisions');
http.setResponseCallback(http.expectedStatuses(409));
if (!Number.isInteger(rate) || rate < 1 || ![30, 120].includes(seconds)
    || tokens.length !== pool + 320 || !prefix || !activities) {
  throw new Error('Invalid rejection fixture; expected a separate 320-user preparation range');
}
const phase = (target, duration, start, tag) => ({
  executor: 'constant-arrival-rate', rate: target, timeUnit: '1s', duration: `${duration}s`,
  startTime: `${start}s`, preAllocatedVUs: 500, maxVUs: 500,
  gracefulStop: tag === 'warmup' ? '5s' : '10s',
  exec: 'reserve', tags: { rate: String(target), phase: tag },
});
export const options = {
  scenarios: {
    warmup: phase(1000, 30, 0, 'warmup'),
    [`rate_${rate}`]: phase(rate, seconds, 35, 'formal'),
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {},
};
export function reserve() {
  const index = execution.scenario.iterationInTest;
  const token = tokens[index % pool];
  const res = http.post(`${base}/api/seckill/activities/${activities}${index % 32}/reservations`,
    JSON.stringify({ quantity: 1, expectedActivityVersion: 1 }), {
      timeout: '10s',
      headers: {
        'Content-Type': 'application/json', Authorization: `Bearer ${token}`,
        'Idempotency-Key': `${prefix}-${execution.scenario.name}-${index}`,
      },
    });
  let decision = `HTTP_${res.status}`;
  let replay = 'unknown';
  try {
    const body = res.json();
    decision = body.decisionCode || decision;
    replay = typeof body.replay === 'boolean' ? String(body.replay) : 'unknown';
  } catch (_) { /* The raw HTTP failure and the missing decision both remain visible. */ }
  decisions.add(1, { decision: String(decision), replay });
}
