<a id="单热点成单最终组合优化与有界过载验证"></a>

# Single-hotspot order creation: final combined optimization and bounded overload validation

<a id="结论与版本"></a>

## Conclusion and versions

At 200 new requests/s for 5 minutes, the final version created 60001 orders with 0 HTTP failures, dropped iterations, or negative timings. Reservation-to-order-creation p99 was 1697.658ms, compared with 9256.534ms for the baseline under the same environment settings. The repeat created 59963 orders; 37 k6 iterations were never issued, so the two runs cannot be described as both having zero drops. Backlog continued growing at 400/s. This report claims neither stable 400 orders/s nor an established maximum capacity.

- A: `fe60d7c3c95eb09399a95562745e96ff5cc386c7`. Business code from main `5e80decdefe5b0ead3a0dafe74011b5a2fd856ce`, plus the common observer adjustment; one consumer, batch size 32, 10ms inter-batch delay.
- C1: `1d93cc35f2e79bf4d0c3c4fd7731081d1da8a20b`. Shared activity lock, shorter inventory critical section, and four bounded consumer loops.
- C2, final measured code: `9bb08c6087d9a73befb909b021debb30a9fb9aa1`. C1 plus a timeout-dispatch queue index, batched receipt transactions, and a 10ms dispatch interval.

Archive and documentation commits do not change these measured versions. The old 128MiB series' 91.12-second → 5.34-second result belongs to the [previous experiment](seckill_order_consumer_comparison_20260908.md) and cannot be spliced into this comparison.

<a id="共同条件与业务口径"></a>

## Common conditions and business definitions

Local MacBook Pro M4; Docker Desktop with 8CPU and 14,638,391,296 bytes; Commerce limited to 4CPU, Hikari 64. MySQL 8.4.10 used a common 1GiB buffer pool, innodb_flush_log_at_trx_commit=1, and sync_binlog=1. Both k6 and services ran inside the Docker VM network. No cloud migration, Commerce CPU reduction, or relaxation of persistence was used. HTTP load used k6's constant-arrival-rate executor; raw points, resource samples, and SQL are retained.

Each point used a new activity, one SKU, new users and idempotency keys, and separate Topic and consumer group. Stock and quota exceeded nominal input by 50. The 60-second probes and 300-second formal windows are labeled separately. Real login and fixture preparation occurred outside the window; there was no successful-admission warm-up, and startup remained included. A/C build artifacts were saved separately; measured source was clean and committed. Builds, tests, and archiving did not run during input or recovery. The common observer sampled every 10 seconds, with full join and percentile SQL before and after.

Database history accumulated; each point did not restore an identical physical snapshot. Old consumers were stopped, but old delayed messages could still expire on the shared Broker. Separate Topics do not provide physical resource isolation. The repeated C2 200/s input overlapped an earlier point's delayed-message expiry window. These are local execution boundaries: a single before/after result is not strictly isolated production capacity or a precise attribution of causal contributions.

“Order-creation wait” uses SQL reservation created_at to order created_at. It includes queueing and business waiting around the conditional inventory update, but is not the final COMMIT, ACK, or timeout-dispatch completion time. The stock update remains before the order INSERT; timestamps were not moved to shorten the metric. Post-commit SQL checks final orders, inventory, quota, and ledger movements. Timeout-message SENT means reliable dispatch, not order cancellation.

<a id="八个测点全部结果"></a>

## Results for all eight points

| Point / version | Input | Admitted and finally created | k6 drops | SQL wait p99 | Maximum sampled admitted-but-uncreated / pending dispatch | Assessment |
|---|---|---:|---:|---:|---:|---|
| A200 | 200/s×300s | 60001 | 0 | 9256.534ms | 1054 / 27 | Clean comparison baseline |
| C1-200 | 200/s×300s | 60001 | 0 | 307.509ms | 37 / 197 | Intermediate version, not a final-code result |
| C1-400 probe | 400/s×60s | 24001 | 0 | 11644.703ms | 2525 / 11008 | Useful for load selection; does not establish five-minute steady state |
| C1-800 probe | 800/s×60s | 47856 | 145 | 67065.203ms | 31964 / 25075 | Large backlog and one negative receive timing; retained for diagnosis |
| C1-400 formal | 400/s×300s | 120000 | 0 | 225737.327ms | 46938 / 66423 | Continuing backlog; dispatch unfinished 300 seconds after input stopped |
| C2-200 first | 200/s×300s | 60001 | 0 | 1697.658ms | 388 / 5 | Final clean operating point |
| C2-400 | 400/s×300s | 120001 | 0 | 186047.859ms | 39356 / 45 | Overload recovery; one negative receive timing, not an HTTP-capacity certification |
| C2-200 repeat | 200/s×300s | 59963 | 37 | 1442.847ms | 390 / 5 | All admitted requests completed; did not meet the zero-drop repeat condition |

HTTP business failures and interrupted iterations were 0 at every point; the expected response was 201 / ADMITTED / replay=false. Each point's after SQL reported 0 missing-order, duplicate-user/reservation, binding, order-ledger, deadline, and negative-wait errors, with stock and quota ledger conservation. C1-800 had 194 stock remaining; every other point reconciled its actual order count in the table with its initial fixture values. Repeated C2-200 had 87 remaining, not an assumed 60001 orders.

All 37 dropped_iterations in the C2 repeat occurred at 07:45:41.311341171–07:45:41.670507880 UTC; the first HTTP point was 07:45:41.672790172. They were concentrated at generator startup. Whether generator CPU, dynamic VU preparation, or service startup latency triggered them first was not independently established. They cannot be reclassified as service errors or removed. This point had no negative timings.

C1-800 had one http_req_receiving value of -0.102764ms; C2-400 had one of -0.850472ms. Non-negative total HTTP duration does not make every timing component clean. Originals are retained. SQL timing and business terminal state can be assessed independently; these points do not establish an HTTP limit.

<a id="两组能够解释的改进"></a>

## Two explainable improvements

<a id="1-订单消费组合ac2"></a>

### 1. Combined order-consumer changes: A→C2

Read-only activity validation changed from FOR UPDATE to FOR SHARE; exclusive boundaries required by rebuild and cancellation remain. Normal product snapshots and order queries no longer acquire the product X lock early. The inventory update checks stock_quantity, publication_version, and availability. On 0 updated rows, a current read distinguishes actual stock shortage from a version change; the latter rolls back and waits for redelivery. Each order, inventory change, and ledger entry still share one transaction, followed by ACK after commit.

Four registered scheduler tasks execute four receive → order transaction → ACK loops using a pool of 4. Each receive still takes at most 32 messages, with a 10ms inter-batch delay. Orders within a batch are not merged into one transaction. Locks and unique constraints serialize the same reservation, and the hot inventory update through commit remains serialized. Four threads do not imply four times the throughput.

Across the same complete 200/s window, A p50/p95/p99 was 27.654/4882.776/9256.534ms, versus 21.216/137.280/1697.658ms for the first C2 run. Both created 60001 orders with zero drops and correct SQL. The C2 repeat had p99=1442.847ms but 37 unissued iterations, so it is not a second fully passing point. The intermediate C1 result of 307.509ms does not replace the final result: the claim is a combined improvement, not monotonically improving tail latency at every step.

<a id="2-超时派发c1-400c2-400"></a>

### 2. Timeout dispatch: C1-400→C2-400

A short probe draining successfully does not establish sustainability over a longer window. C1's long window exposed a growing dispatch queue: despite LIMIT 32, the original query scanned/sorted the backlog after status filtering. Native performance_schema snapshots taken before input and after input ended produced the following differences. C1 spanned approximately 319.65 seconds and C2 approximately 319.77 seconds; both include time before and after the 300-second generator window.

| Measured SQL | C1 | C2 |
|---|---:|---:|
| Pending-dispatch batch selections | 1474 | 17909 |
| Mean examined rows per selection | 15004.49 | 4.61 |
| Mean statement duration per selection | 47.847ms | 0.191ms |
| Dispatch-receipt UPDATE count | 41833 | 82528 |
| Mean receipt UPDATE statement duration | 3.414ms | 0.095ms |

C2 uses the virtual generated column timeout_dispatch_ready to express UNPAID with PENDING/FAILED equivalently. A composite index on ready, attempts, created_at, and order_id preserves the original ordering. MQ sends remain outside database transactions. After sending a batch, a separate transaction commits that batch's success/failure receipts, reducing per-receipt autocommits. A failed receipt transaction may resend a batch, so cancellation must remain idempotent. Multiple orders are still not created in one transaction.

The versions completed different amounts of work and executed different query counts. Values in the table are statement averages over the approximately 320-second sample intervals, not exact 300-second input windows or a decomposition of one transaction. The UPDATE-duration reduction includes moving commits from individual receipts to the batch end. It is not the same reduction in total database time per order, and COMMIT or statement duration cannot be labeled pure fsync or pure lock wait.

Maximum sampled pending dispatches fell from 66423 to 45. C2 dispatch tracked order creation, but the order backlog itself still grew to 39356 at 400/s. The remaining limit was not attributed to a particular CPU, connection pool, Redis, or disk; further tuning stopped. This change resolves a demonstrated queue problem but does not establish stable 400/s.

<a id="排空取消与观测边界"></a>

## Drain, cancellation, and observation boundaries

Relative to the last raw HTTP point, the following intervals contain the first periodic SQL observation with all orders created and all timeout messages SENT. Bounds include query start/end uncertainty. They are neither MQ ACK completion times nor natural order-cancellation times.

| Point | First all-ORDERED/SENT observation interval after input stopped |
|---|---:|
| A200 | (0,3.61] seconds |
| C1-200 | (0,3.75] seconds |
| C1-400 probe | (44.07,54.35] seconds |
| C1-800 probe | (173.71,183.94] seconds |
| C1-400 formal | Not complete within 300 seconds; undispatched messages remained afterward |
| C2-200 first | (0,3.72] seconds |
| C2-400 | (172.43,182.94] seconds |
| C2-200 repeat | (0,3.82] seconds |

Subsequent point-by-point MQ checks confirmed order-Topic Diff/Inflight=0 and Redis handoff=0. Broker LastTime was not treated as an exact ACK completion time. A's observer exited with its parent without writing a completion footer, but input-period and first fully drained SQL samples were recorded with no sampling errors. Later parent processes explicitly waited for the observer to exit. C1-400 reaching the observer's 650-second limit did not mean successful recovery: 60521 SENT / 59479 PENDING remained at 07:06:12. The old application was subsequently stopped, retaining that state.

After C2 migration and tests, only dispatch was recovered on the original C1-400 fixture, with the old cancellation consumer's initial wait set to one hour. The 15-minute order deadline was unchanged. At 07:22:53, SQL confirmed 120000 UNPAID/SENT; order MQ/Redis were then checked at 0 before stopping the old application. This record is labeled separately as C2 and is not presented as recovery within the C1 measurement. Subsequent new fixtures restored normal cancellation-consumer configuration.

Cancellation correctness was checked by the affected integration tests using real MySQL, Redis, and RocketMQ, including new cases for two-order batch-receipt rollback, real message resend, and duplicate cancellation. Each performance point did not require tens of thousands of orders to cancel naturally one by one. All isolated performance fixtures were ultimately retained as UNPAID/SENT, along with original messages and evidence, and old consumers were stopped. Five-minute order-creation results do not establish full-lifecycle steady state when every order remains unpaid.

<a id="验证与收尾"></a>

## Validation and closeout

Final code passed `make java-ci python-ci web-ci repo-ci` and `make test-catalog-integration`. The latter ran 207 tests with 0 failures, errors, or skips, including 17 seckill-transaction tests. New coverage includes inventory concurrency, redelivery after publication-version changes, four actual scheduler tasks, and batch-receipt failure / duplicate cancellation. Independent read-only code review found no blockers; the final-code PR passed 26 remote checks.

This series comprises 8 points; further rate exploration and changes stopped. The existing sold-out 6000/s short-window operating point, ordinary order/payment baseline, and transaction evidence remain in the [transaction-results index](transaction_results_20260908.md). Cleanup state, original signing metadata, and MySQL buffer restoration for this series are in the [environment record](seckill_final_environment_20260908.txt).

<a id="原始归档"></a>

## Raw archives

Each archive contains its label's setup, raw k6 points/summary/console, observer SQL/resources, native database snapshots before/after, and independent post-run checks. Full measured SHAs are recorded in the originals.

- [A200](seckill-final-a200n_20260908T061729Z.tar.gz)
- [C1-200](seckill-final-c200n_20260908T062708Z.tar.gz)
- [C1-400 probe](seckill-final-c400p_20260908T063510Z.tar.gz)
- [C1-800 probe](seckill-final-c800p_20260908T064255Z.tar.gz)
- [C1-400 formal and separate C2 dispatch recovery](seckill-final-c400r1_20260908T065204Z.tar.gz)
- [C2-200 first](seckill-final-c2_200r1_20260908T072339Z.tar.gz)
- [C2-400](seckill-final-c2_400r1_20260908T073148Z.tar.gz)
- [C2-200 repeat](seckill-final-c2_200r2_20260908T074349Z.tar.gz)
