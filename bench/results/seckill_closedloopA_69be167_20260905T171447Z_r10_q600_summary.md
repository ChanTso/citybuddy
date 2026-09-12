<a id="a有限库存准入成单与清空"></a>

# A: finite-inventory admission, order creation and drainage

citybuddy_commit=69be167a3df030bf45795c49f444d6e7c24d0423
label=closedloopA_69be167_20260905T171447Z_r10_q600

This run began after foreground computer use stopped. Other work was limited to lightweight file reads/writes; no builds, tests, model calls or other application services ran concurrently. The earlier disturbed closedloop_ac19913 series is excluded from this conclusion and comparison.

<a id="工作负载和环境"></a>

## Workload and environment

MacBook Pro M4, Docker 8 CPUs / 14,638,391,296 bytes, Commerce limited to 4 CPUs; k6 inside the VM. The original setup_bench_env.sh, run_ladder.sh and seckill_ladder.js were used without changing scheduling, batch size, delay or application code. The merge commit had the same source tree as the previously built revision, so existing JARs were reused. Both JAR SHA-256 digests and the full measured commit are recorded in setup/environment.

One measured run, without a preliminary probe. One activity bench-activity-0 and product bench-product, stock/quota 600 each, 650 users, 10 requests/s × 60 seconds, quantity 1, expected version 1. The original script used 50 preallocated / 200 maximum VUs. New transaction/timeout/catalog topics and groups used suffix=closedloopA-69be167-20260905T171447Z.

Positive warm-up was zero. Setup only performed login and read-only JWKS/catalog readiness. Before measurement, SQL reservations/orders were zero, stock/quota 600, old Redis intent keys zero and global/activity handoff zero. There was no hidden positive warm-up backlog.

<a id="http-原始结果"></a>

## Raw HTTP results

- 601 completions: 600 ADMITTED (HTTP 201), plus 1 EXHAUSTED (HTTP 409) at the original k6 duration boundary. dropped_iterations Points sum to zero.
- Native k6 http_req_failed=1/601, corresponding to that sold-out 409. No other HTTP status or business decision occurred; the native metric must not be rewritten as zero.
- All 601 HTTP requests: p50=10.427084 ms, p99=26.225 ms, max=123.836084 ms.
- First HTTP completion: 2026-09-05T17:20:14.613212297Z; last: 2026-09-05T17:21:14.489101838Z. “After input stopped” below starts at the last HTTP completion, not runner exit.

<a id="sql-业务闭环与订单窗口"></a>

## SQL business closure and order windows

All 600 reservations were ORDERED/ADMITTED, producing 600 UNPAID orders and 600 SECKILL_ORDER_CREATE ledger entries. inventory_delta and activity_quota_delta each totaled -600; MySQL inventory and Redis remaining quota were both zero. Configured allocated_quota remained 600.

SQL found zero order/reservation binding errors, admitted reservations without orders, duplicate users, duplicate reservations, creation-ledger errors, extra ledgers or orders created beyond the payment deadline. No CANCELLED or UNFULFILLED states occurred.

First reservation persisted: 17:20:14.596577Z; first order created: 17:20:17.999783Z; last order created: 17:22:05.903500Z. First reservation to last order spans 111.3069 seconds; 600 ÷ 111.3069 = batch mean 5.3905 orders/s. This is the average over a finite batch's complete processing window, not sustained order capacity or a limit.

Under the registered convention, flooring the first reservation time to the second gives t0=17:20:14Z. Fixed 60-second order-creation windows:

| UTC window, half-open | Orders | Denominator | Window average |
| --- | ---: | ---: | ---: |
| 17:20:14–17:21:14 | 320 | 60 seconds | 5.3333 orders/s |
| 17:21:14–17:22:14 | 280 | 60 seconds | 4.6667 orders/s |

SQL reservation created_at → order created_at wait, for 600 orders using nearest-rank percentiles: p50=28.139 seconds, p95=58.462 seconds, p99=62.905 seconds, max=65.501 seconds; negative differences zero. This measures waiting after reservation persistence, not exact end-to-end latency from client submission or MQ enqueue.

<a id="停压清空与复查"></a>

## Post-input drainage and recheck

- At 17:22:01.536096Z, 24 orders were still pending creation; at 17:22:06.634222Z, all 600/600 were first observed created. Order backlog therefore drained in the sampled interval approximately 47–52 seconds after input stopped. The last SQL order timestamp is about 51.414 seconds after the last HTTP completion, a cross-container wall-clock difference.
- At 17:22:06.634222Z, 24 timeout messages still awaited sending; at 17:22:11.741427Z, all were first observed SENT and global/activity Redis handoff were zero. Business drainage including timeout-send work therefore falls approximately 52–57 seconds after input stopped.
- At 17:24:38.956769Z, explicit-topic consumerProgress showed four broker/consumer offsets 152/155/135/158, totaling 600; Consume Diff Total=0, Consume Inflight Total=0. Per-message ACK times were not measured; this later MQ read cannot be projected backward to an exact first MQ-drain time.
- A stable recheck at 17:25:37.812645Z still showed 600/600, UNPAID/SENT, handoff zero and consistent inventory/ledgers.
- The 15-minute unpaid-close messages were sent to this run's isolated timeout topic. Their future delayed delivery is not transaction backlog that should clear immediately.

<a id="恢复与采用建议"></a>

## Restoration and scope of use

Earliest payment deadline: 17:35:17.997653Z. Benchmark Commerce/Auth stopped at 17:26:43Z; removal and transactional restoration of the original demo's public signing metadata completed at 17:26:44Z. Restored values matched the ignored backup field by field. Private keys and ordinary demo business data were unchanged, and drainage was not manufactured through payment, cancellation or message deletion. Commerce log WARN/ERROR counts were both zero. The source tree was clean at the end and the full SHA unchanged.

This run supports the statement that all 600 finite-inventory admissions became orders, order/stock/quota ledgers agreed and transaction backlog eventually drained. The 5.39 orders/s and 47–52-second figures remain measurement facts with their denominators and explanatory boundaries, not high-throughput highlights. They do not replace the existing 3,000/s sold-out rejection result. Stage A did not optimize or rerun to select a better number.

The [raw evidence archive](closedloop-A-20260905-raw.tar.gz) contains same-label k6 summary/points/console/cpu, ladder steps, setup/environment, SQL queries and original output, and Redis/MQ commands and readings. Each result retains the full measured SHA. Originals were not rewritten, and local copies were retained when archiving.
