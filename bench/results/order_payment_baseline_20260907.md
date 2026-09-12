<a id="普通下单与模拟支付两次完整业务基线"></a>

# Ordinary orders and simulated payment: two complete-flow baselines

Measured CityBuddy: `76c293178923bf78e747ab1ba9590e6348108ad8`. MacBook Pro M4, Docker 8 CPUs / 14 GB (actual 14,638,391,296 bytes), Commerce limited to 4 CPUs; k6 runs on the same Docker network. Each run uses 32 new SKUs with 1000 units each and 2450 new users, at 20 complete flows/s for 120 seconds with 100 fixed VUs. Each flow submits an order, creates a payment attempt and sends a signed success callback, expecting HTTP 201/201/200. Login and fixture preparation are outside the measurement. No model calls, builds or other load ran concurrently; earlier flash-sale cancellation and MQ work had drained. Both runs use the same source and configuration while retaining accumulated database history.

| Result | First run | Second run |
|---|---:|---:|
| Complete order/payment flows | 2400 | 2401 |
| HTTP requests | 7200 | 7203 |
| Failed / dropped / interrupted | 0 / 0 / 0 | 0 / 0 / 0 |
| Complete-flow p50 / p99 | 10 / 84.02 ms | 9 / 84 ms |
| Order HTTP p99 | 22.682 ms | 22.349 ms |
| Payment-attempt HTTP p99 | 27.374 ms | 29.098 ms |
| Callback HTTP p99 | 30.941 ms | 33.970 ms |
| Payment amount (minor units) | 4,813,200 | 4,815,190 |

The second run started one extra flow at the window boundary. All 2401 are reported; no original samples are discarded. The four stages started/order_created/payment_started/paid have matching counts; their combined event count is not an order count. All HTTP durations are nonnegative. Complete-flow duration is a k6 Date.now difference, including the three HTTP calls and client-side HMAC work; percentiles come from the k6 summary. Per-stage HTTP percentiles use nearest-rank over original Points; the three p99 values are not added together.

Both authoritative SQL checks confirm every order PAID, payment attempt SUCCEEDED and callback APPLIED, with one order idempotency key, payment ledger entry and order-created Outbox entry each. Independent totals for orders, payment attempts and payment ledgers agree. Inventory differences across all 32 SKUs are zero; STANDARD_PAYMENT causes zero inventory change. Owner/key, amount, currency, version and callback-binding errors are all zero, with no orders outside the fixture. Outbox rows are PENDING: this workload proves atomic persistence, not consumption. It does not measure whole-cart checkout, external real payment or refunds.

Conclusion: the ordinary order-to-simulated-payment path repeatedly completes at the 20 flows/s baseline with correct accounting. Its maximum throughput was not sought. This validates a core business path separately from sold-out QPS and asynchronous order capacity.

Raw prefixes (each includes setup, before/after SQL, points, summary, console and cpu):

- `order_payment_normalr1_76c2931_20260907T160120Z_`
- `order_payment_normalr2_76c2931_20260907T160434Z_`

[Lossless raw-output archive](ordinary-payment-20260907.tar.gz). It contains the original k6, SQL and resource records; recalculated results do not replace the originals.
