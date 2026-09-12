<a id="有限库存准入到成单基线"></a>

# Finite-inventory admission-to-order baseline

> Status correction, 2026-09-06: the user confirmed that every benchmark run after handoff execution began was affected by foreground computer use, including this batch. Its throughput, latency, queueing and drain times are excluded from formal performance conclusions and pre-fix baselines. Values and raw output below remain intact, without selective removal. SQL is retained only as a record of that run's state; a formal closed-loop measurement must be rerun and checked in an exclusive environment.

citybuddy_commit=ac1991316134e10269164b0e3587e9b83244388f
label=closedloop_ac19913_20260905T160911Z_r1000_q3000

MacBook Pro M4; Docker 8 CPUs / 14,638,391,296 bytes; Commerce limited to 4 CPUs.
Original setup_bench_env.sh and run_ladder.sh: one activity, stock/quota 3000 each, 3500 users, target 1000/s × 3 seconds, without positive admission warm-up. Only this load window ran. Before measured requests, the SQL fixture was empty; global/activity Redis handoff and all intent-key counts were zero.

<a id="实际结果"></a>

## Actual results

- Nominal iterations 3000; 2196 actually completed and returned ADMITTED; k6 dropped_iterations 804; zero HTTP failures among issued requests.
- HTTP p50 1595.318501 ms, p99 1903.0828552 ms. Admission normalized to the target 3 seconds was 732/s; k6's iterations rate, using its own execution duration including the request tail, was 486.062/s.
- First HTTP completion Point: 2026-09-05T16:19:23.521026301Z; last: 2026-09-05T16:19:27.370785719Z, from original http_reqs Points.
- All 2196 final reservations were ORDERED + ADMITTED, with 2196 UNPAID orders and 2196 SECKILL_ORDER_CREATE ledger entries. inventory_delta and activity_quota_delta each totaled -2196; MySQL product.stock_quantity and Redis remainingQuota were both 804, while activity.allocated_quota retained its configured 3000.
- SQL found zero order/reservation association errors, admitted reservations without orders, duplicate users, duplicate reservations, order-ledger errors, extra ledgers or creation timestamps beyond the payment deadline. There were no CANCELLED or UNFULFILLED states; no payment, refund or cancellation was performed in this run.
- Last order created_at=2026-09-05T16:26:17.979882Z, approximately 410.609 seconds after the last HTTP completion. This is a cross-container SQL/k6 clock difference, not per-message monotonic-clock latency.
- The 16:26:17.653920Z snapshot still had 4 pending orders; all orders were first observed created at 16:26:22.776076Z. All timeout_dispatch records were first observed SENT and global/activity Redis handoff zero at 16:26:33.034958Z, approximately 425.664 seconds after the last HTTP completion.
- A stable recheck at 16:28:16.867593Z still showed 2196/2196, all SENT and handoff zero. Per-association SQL checks completed at 16:29:33.425941Z.
- Explicit-topic mqadmin consumerProgress showed four broker/consumer offsets 532/570/543/551, totaling 2196; Consume Diff Total=0, Consume Inflight Total=0.

<a id="口径与结束状态"></a>

## Measurement boundaries and final state

This run cannot reaffirm clean capacity of 1000/s or prove sustained order creation at 1000/s. The cold-start window is included in every statistic; unfavorable seconds were not removed. SQL sampling occurred approximately every 5 seconds, without per-message ACK timestamps.

consumerProgress without a topic reported a nonexistent retry topic despite tool exit code zero. That actual error and the subsequent successful explicit-topic output both remain in raw logs; exit code alone was not taken as success.

The 15-minute payment-timeout messages were sent to this run's dedicated timeout topic. Future delayed delivery is not part of the transaction-backlog drainage target, so the topic was not required to empty. Earliest unpaid_deadline=2026-09-05T16:34:24.470610Z; benchmark Commerce/Auth stopped and were removed at 16:31:06Z, so automatic unpaid cancellation did not contaminate order-creation statistics.

The original demo's public-key signing metadata was restored transactionally and matched the ignored private backup field by field. Private keys and demo business data were unchanged. Base dependencies remained running; the broker retained the quiet-healthcheck settings recorded in the environment file. Commerce WARN/ERROR line counts were both zero. Source/HEAD did not change during measurement; after resource handoff, the main task could continue merging.

<a id="原始文件"></a>

## Original files

Same-label k6 summary.json / points.json / console.txt / cpu.txt, ladder steps.txt, and seckill environment.txt / setup.txt / setup_console.txt / runner_console.txt / sql.txt / redis_mq.txt. Every new result records the full measured SHA; Points include it in each measurement's tags, while setup uses CITYBUDDY_COMMIT.

The [complete raw output](closedloop-interrupted-20260905-raw.tar.gz) retains this disturbed measurement. It is excluded from valid capacity and fix comparisons.
