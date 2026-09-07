// A flow is one ordinary order, one payment attempt, and one signed success callback.
import http from 'k6/http';
import crypto from 'k6/crypto';
import execution from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter, Trend } from 'k6/metrics';

const rate = Number(__ENV.RATE || 20);
const seconds = Number(__ENV.DURATION_SECONDS || 120);
const label = __ENV.REQUEST_KEY_PREFIX;
const base = __ENV.BASE_URL || 'http://citybuddy-bench-commerce:8080';
const tokens = new SharedArray('normal-order-tokens', () => JSON.parse(open(__ENV.TOKENS_FILE)));
const products = new SharedArray('normal-order-products', () => JSON.parse(open(__ENV.PRODUCTS_FILE)));
const keyId = __ENV.CITYBUDDY_MOCKPAYMENT_CALLBACKKEYID;
const secret = __ENV.CITYBUDDY_MOCKPAYMENT_CALLBACKSECRET;
const outcomes = new Counter('order_payment_outcomes');
const flowDuration = new Trend('order_payment_duration', true);

if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$/.test(label || '')
    || !Number.isInteger(rate) || rate < 1 || !Number.isInteger(seconds) || seconds < 1
    || products.length !== 32 || tokens.length < rate * seconds + 50
    || !keyId || !secret || secret.length < 32) {
  throw new Error('Invalid normal-order fixture or callback configuration');
}

export const options = {
  scenarios: {
    order_payment: {
      executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration: `${seconds}s`,
      preAllocatedVUs: 100, maxVUs: 100, gracefulStop: '100s',
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: { dropped_iterations: ['count==0'], 'order_payment_outcomes{outcome:failed}': ['count==0'] },
};

function uuid() {
  const bytes = new Uint8Array(crypto.randomBytes(16));
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function fail(stage, reason) {
  outcomes.add(1, { outcome: 'failed', stage, reason });
  return null;
}

function post(stage, path, body, headers, status) {
  const response = http.post(`${base}${path}`, JSON.stringify(body), {
    timeout: '30s', headers: { 'Content-Type': 'application/json', ...headers },
    tags: { stage, name: stage },
  });
  if (response.status !== status) return fail(stage, `http_${response.status}`);
  let value;
  try { value = response.json(); }
  catch (_) { return fail(stage, 'invalid_json'); }
  if (!value || typeof value !== 'object' || Array.isArray(value)) return fail(stage, 'invalid_shape');
  return value;
}

export default function () {
  const startedAt = Date.now();
  let paid = false;
  try {
    const index = execution.scenario.iterationInTest;
    outcomes.add(1, { outcome: 'started' });
    if (index >= tokens.length) return fail('create_order', 'token_pool_exhausted');
    const product = products[index % products.length];
    const bearer = { Authorization: `Bearer ${tokens[index]}` };
    const order = post('create_order', '/api/orders', {
      productId: product.productId, quantity: 1, expectedProductVersion: product.productVersion,
    }, { ...bearer, 'Idempotency-Key': `${label}:o:${index}` }, 201);
    if (!order) return;
    if (order.replayed !== false || order.status !== 'UNPAID' || typeof order.orderId !== 'string'
        || order.productId !== product.productId || order.quantity !== 1
        || order.unitPriceMinor !== product.unitPriceMinor || order.totalPriceMinor !== product.unitPriceMinor
        || order.currency !== product.currency || order.productVersion !== product.productVersion) {
      return fail('create_order', 'unexpected_result');
    }
    outcomes.add(1, { outcome: 'order_created' });
    const payment = post('start_payment', `/api/orders/${order.orderId}/mock-payment`, {
      amountMinor: order.totalPriceMinor, currency: order.currency,
    }, { ...bearer, 'Idempotency-Key': `${label}:p:${index}` }, 201);
    if (!payment) return;
    if (payment.replayed !== false || payment.state !== 'PENDING' || payment.orderKind !== 'STANDARD'
        || payment.orderId !== order.orderId || payment.amountMinor !== order.totalPriceMinor
        || payment.currency !== order.currency || typeof payment.attemptId !== 'string'
        || typeof payment.callbackCorrelationId !== 'string') return fail('start_payment', 'unexpected_result');
    outcomes.add(1, { outcome: 'payment_started' });
    const key = `${label}:c:${index}`;
    const timestamp = String(Math.floor(Date.now() / 1000));
    const callback = {
      callbackEventId: uuid(), callbackCorrelationId: payment.callbackCorrelationId,
      orderId: order.orderId, amountMinor: order.totalPriceMinor, currency: order.currency,
      outcome: 'SUCCEEDED',
    };
    const canonical = [keyId, timestamp, key, callback.callbackEventId, callback.callbackCorrelationId,
      callback.orderId, String(callback.amountMinor), callback.currency, callback.outcome,
      '', '', '', ''].join('\n');
    const settled = post('settle_payment', '/internal/mock-payments/callback', callback, {
      'Idempotency-Key': key, 'X-Mock-Payment-Key-Id': keyId, 'X-Mock-Payment-Timestamp': timestamp,
      'X-Mock-Payment-Signature': crypto.hmac('sha256', secret, canonical, 'hex'),
    }, 200);
    if (!settled) return;
    if (settled.replayed !== false || settled.state !== 'SUCCEEDED' || settled.orderId !== order.orderId
        || settled.attemptId !== payment.attemptId
        || settled.callbackCorrelationId !== payment.callbackCorrelationId) return fail('settle_payment', 'unexpected_result');
    outcomes.add(1, { outcome: 'paid' });
    paid = true;
  } finally {
    flowDuration.add(Date.now() - startedAt, { outcome: paid ? 'paid' : 'failed' });
  }
}
