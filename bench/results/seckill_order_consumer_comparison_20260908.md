<a id="单热点成单接收批量与批后等待组合对照"></a>

# Single-hotspot order creation: receive batch size and inter-batch delay

This is a historical experiment with a 128MiB buffer pool. The final implementation and all eight later measurement points are in the [final comparison](seckill_order_final_comparison_20260908.md). The stopping decision and single-threaded implementation described here apply only to the version measured at that time.

<a id="条件与问题"></a>

## Conditions and question

Baseline CityBuddy: `76c293178923bf78e747ab1ba9590e6348108ad8`, label `a200r1_76c2931_20260907T145922Z`. Modified CityBuddy: `2093b1355397ea330e9e03f806454722db1a2246`, label `aopt_2093b13_20260907T202251Z`. Both used an M4, Docker with 8CPU / 14,638,391,296 bytes, Commerce limited to 4CPU, and k6 inside the VM. Each used one activity / SKU, 60050 distinct users and matching stock/quota, with 200 new requests/s for 300 seconds and no successful-admission warm-up. Every request used a new idempotency key; login and fixture preparation were outside the window.

The baseline processed each transaction and ACK serially, receiving up to 16 messages per batch and waiting 50ms afterward. The modified version received up to 32 and waited 10ms. The 1-second receive wait, 10-second invisibility period, timeout-dispatch and cancellation cadence, transaction writes, and persistence settings were unchanged. The combined change targeted batching and waiting overhead in the serial consumer; it did not assume that receiving, SQL, ACK, or scheduling was the dominant component.

Both used the same k6 workload and periodic SQL/resource observation. The current full post-run SQL used a 60-second client timeout; the baseline's original 15-second query timed out, then the same SQL succeeded with 60 seconds. This did not change observation during input. Database history accumulated, and initial cache state and background work were not identical. This is neither a byte-identical disk/memory comparison nor a production-capacity or exact maximum-TPS claim.

<a id="结果"></a>

## Results

| Metric | 16 messages / 50ms | 32 messages / 10ms |
|---|---:|---:|
| Newly admitted requests / final orders | 60001 / 60001 | 60001 / 60001 |
| HTTP failures / dropped / interrupted iterations | 0 / 0 / 0 | 0 / 0 / 0 |
| Reservation persistence to order creation, p50 | 36989.468ms | 37.330ms |
| Same interval, p95 | 87338.829ms | 2684.519ms |
| Same interval, p99, including startup | 91124.602ms | 5343.929ms |
| Same interval, maximum wait | 95255.726ms | 7768.808ms |
| Maximum sampled uncreated orders, 5-second cadence | 12294 | 730 |
| Maximum sampled pending timeout dispatches, 5-second cadence | 16 | 23 |
| Backlog in the middle and later input window | Continued growing | Fell again and tracked input |

Output in the modified run's five complete minutes was 199.7000, 198.9333, 201.0333, 197.2333, and 202.7500 orders/s. The final 22 orders occupied an incomplete minute; 22/60 is not a tail consumption-capacity estimate. Reservation-minute p99 values were 6937.574, 1050.409, 156.085, 1414.026, and 336.338ms. Startup and intermittent waiting remained; later-minute percentiles cannot replace the full-window 5.34 seconds.

The complete raw HTTP window was 20:24:35.444784260–20:29:35.074145926 UTC: 60001 responses with 201 / ADMITTED / replay=false, no negative HTTP timing components, and HTTP p99 of 42.807667ms. HTTP admission latency is not order-creation wait. SQL records the last order's creation at 20:29:35.104352 UTC, approximately 30.206ms after the last HTTP point. This cross-clock difference does not establish ACK completion or clearance of all timeout dispatches.

<a id="正确性与观察限制"></a>

## Correctness and observation limits

Full after SQL confirmed 60001 orders and 60001 stock/quota deductions, with stock conservation of 49+60001=60050. Missing orders, duplicate users/reservations, binding, ledger, deadline, and negative-wait errors were all 0. At 20:33:16.812012 UTC, independent grouped SQL confirmed 60001 UNPAID/SENT orders and 60001 ORDERED/ADMITTED reservations; both MQ groups had Diff/Inflight 0 and Redis handoff was 0.

The observer exited on docker stats EOF during the final resource sample, close to the k6 container's exit; the cause was not independently established. The original error and 61 progress samples are retained. Successful input-script completion does not imply successful observer completion. The last regular sample had 59328 created / 4 pending creation / 4 pending dispatch; it cannot establish clearance at the first 5-second sample after input stopped. Post-run SQL and independent grouped checks establish final correctness. There is no exact end-to-end drain interval directly comparable with the baseline's (89.16,95.04] seconds, so no drain-time improvement ratio is claimed.

Resource sampling included startup and an interrupted tail: Commerce peaked at 177.27%, k6 at 15.45%, MySQL at 45.05%, Redis at 5.89%, and Broker at 102.95%. Commerce throttling increased by 7 events / 461611 microseconds. These observations neither prove the root bottleneck nor establish a separate CPU improvement.

<a id="判定与取舍"></a>

## Decision and tradeoffs

At the same 200/s load, the combined change allowed later order creation to track input, reduced full-window wait p99 from 91.12 seconds to 5.34 seconds, and reduced maximum sampled backlog from 12294 to 730. Final business SQL was correct. At this stage, the result supported one combined improvement without further factor separation, higher-rate exploration, or concurrent-consumer restructuring. The single thread and per-order transaction/ACK were retained. A shorter delay increases retry frequency on immediate failures; normal idle reception still blocks in long polling.

This was one before/after comparison, not a new capacity ceiling established through repeated runs. Five minutes of unpaid order creation does not demonstrate steady state over the complete unpaid-order cancellation lifecycle. Natural cancellation recovery is recorded separately; cleanup during recovery is outside the performance comparison.

Local validation: `make java-ci python-ci web-ci repo-ci` and `make test-catalog-integration` passed. Independent read-only code review found no blocking issues. Baseline originals are linked from the [baseline report](orders_a200r1_76c2931_20260907T145922Z_report.md) and its archive. The new point retains same-label setup, k6, progress/resources, before/after, drained_groups, and native SQL/MQ outputs.

<a id="测量后的清理资源调整"></a>

## Post-measurement cleanup resource adjustment

Formal HTTP measurement and order SQL checks both finished before 20:33 UTC. Natural cancellation of unpaid orders was subsequently slow: 55481 orders remained unpaid at 20:51:32. To shorten cleanup-only waiting, MySQL's runtime buffer pool was temporarily increased from 134217728 to 1073741824 bytes at 20:54:25 UTC. The record confirms unchanged MySQL 8.4.10, flush-at-commit=1, and sync-binlog=1; cancellation code, transactions, and messages were not changed. Subsequent cancellation duration is not compared with the old baseline and is not a same-configuration cancellation-performance result. The original buffer size was restored and recorded after final cleanup. Both formal 200/s comparison runs used the original 128MiB configuration.

Final cleanup: at 2026-09-07 21:37:36 UTC, SQL confirmed all 60001 orders CANCELLED, stock and activity quota restored to 60050, and 60001 creation and cancellation ledger entries each. Unsettled reservations, invalid cancellation bindings, unfinished admissions, and undispatched timeouts were all 0; both MQ groups' Diff/Inflight and Redis handoff were 0. The buffer pool was restored to 134217728 bytes at 21:38:25; shrink completion was confirmed at 21:38:51, with status 0 and progress 100%. Original public signing metadata was restored, then test applications and dependencies were stopped while retaining volumes.

Originals: [formal 200/s measurement](seckill-orders-aopt-20260908.tar.gz), [post-run recovery and buffer restoration](seckill-orders-aopt-recovery-20260908.tar.gz). Cleanup durations in the latter archive are excluded from the performance comparison above.
