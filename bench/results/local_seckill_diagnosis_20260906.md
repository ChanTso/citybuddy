# Local seckill diagnosis and consumer scheduling

This report retains the earlier scheduling diagnosis. Subsequent work measured
[five-minute completed orders](seckill_sustained_orders_20260906.md) and
[fixed-warmup 3,000/4,000 sold-out windows](seckill_rejection_diagnosis_20260906.md)
on `16cb21154d1ae94c65fff56b8a96ea7f4514f924`. Its earlier stopping decision is historical;
those later results do not replace or combine this report's before/after pair.

Baseline CityBuddy commit: `69be167a3df030bf45795c49f444d6e7c24d0423`.

Fixed CityBuddy commit: `cd213a846769b40cfbc95e791847f338fd7917c3`.

At the same 10 requests/s workload, isolating the two blocking message consumers
reduced reservation-to-order p99 from **64.013536 s to 541.240 ms**. Both runs
completed 600/600 orders with matching inventory and ledger state. This establishes
a scheduling fix at this workload; it does not measure maximum order throughput.

All runs below used one MacBook Pro M4, Docker Desktop with 8 CPUs and
14,638,391,296 bytes of memory, Commerce limited to 4 CPUs, and k6 inside the same
Docker VM. They are local workload observations, not production capacity claims.
The Broker image, data topology and business transaction rules stayed unchanged.

## Why inspect order completion

The [finite-stock run](seckill_closedloopA_69be167_20260905T171447Z_r10_q600_summary.md)
admitted and completed all 600 orders, but order creation continued for about
47–52 seconds after the last HTTP response. Fast admission did not imply fast
asynchronous completion. A new run reproduced this behavior with thread dumps and
five-second SQL samples to identify where the scheduler waited.

The diagnostic workload was one activity, one product, stock/quota 600, 650 users,
10 requests/s for 60 seconds, no positive-path warmup, a fresh topic/group, and the
unchanged `setup_bench_env.sh`, `run_ladder.sh` and `k6/seckill_ladder.js`. Both JAR
hashes and the full source commit are in the setup output.

| Baseline diagnostic | Observed result |
|---|---|
| Label | `diag_order_69be167_20260906T050232Z_r10_q600` |
| HTTP | 600 ADMITTED + 1 expected EXHAUSTED; zero dropped/interrupted iterations |
| HTTP latency | p50 10.679292 ms; p99 21.509959 ms |
| Durable orders | 600/600, all UNPAID; 600 creation ledger entries |
| Reservation persistence → order creation | p50 19.122223 s; p99 64.013536 s |
| Complete finite batch | 600 orders / 113.026735 s = 5.3085 orders/s; not maximum throughput |
| Orders drained after last HTTP | Observed between 48.85 and 53.86 s |
| Orders plus timeout-message dispatch drained | Observed between 53.86 and 58.85 s |

Authoritative SQL found no missing or duplicate orders, incorrect bindings,
incorrect ledger movements, cancelled/unfulfilled orders, or orders created after
their payment deadline. Inventory and quota deltas were both −600. Later MQ and
Redis checks found zero transaction-consumer backlog/inflight and zero pending
handoffs; future unpaid-timeout messages were not treated as immediate backlog.

Eight sparse `SIGQUIT` thread dumps were captured. Seven showed the only default
`scheduling-1` thread waiting in `SimpleConsumerImpl.receive` through
`RocketMqSeckillTimeouts.consumeOnce`. Six of those seven were taken while orders
were still waiting. The remaining dump showed the scheduler waiting for its next
scheduled task. These are discrete stack samples, not a CPU/time percentage.

The default scheduler had one thread. Both blocking consumers, recovery and timeout
dispatch used it, so an empty timeout poll delayed other scheduled work. The
fix gives order consumption and timeout consumption separate one-thread
schedulers, leaving Boot's default scheduler and application executor in place.
Batch size, fixed delays, receive waits, per-message transactions and ACK behavior
are unchanged. Independent scheduling still does not run multiple batches of one
consumer concurrently.

The comparison uses the same five-second SQL/cgroup collection, 31 samples and
eight thread dumps at sample indices 2, 4, 7, 10, 13, 16, 20 and 24. Sampling started
about 6.4 seconds before the baseline's first HTTP completion. Thread dumps and SQL
collection have overhead, so this is an instrumented comparison rather than an
unprofiled latency benchmark. Both runs retain their actual start offsets below.

[Baseline raw output](local-diagnosis-baseline-20260906-raw.tar.gz) includes k6 points,
setup, all SQL queries/results, cgroup observations and the full JVM dumps.

## Same-workload result after scheduling isolation

After label: `diag_order_cd213a8_20260906T052825Z_r10_q600`.

| Measure | Shared scheduler baseline | Isolated consumers |
|---|---:|---:|
| Admitted / ordered | 600 / 600 | 600 / 600 |
| Reservation-to-order p50 | 19,122.223 ms | 278.587 ms |
| Reservation-to-order p99 | 64,013.536 ms | 541.240 ms |
| Largest pending-order count in five-second samples | 299 | 5 |
| Post-input order drain | 48.85–53.86 s | Confirmed within 4.91 s |
| Post-input order + timeout-dispatch drain | 53.86–58.85 s | Confirmed within 4.91 s |

The fixed run completed exactly 600 HTTP 201 ADMITTED responses, with no dropped or
interrupted iterations. The baseline had one extra expected sold-out response at
the k6 duration boundary; both admitted exactly the same 600 units. Fixed-run HTTP
p50 was 9.098959 ms and p99 20.486944 ms. The principal comparison is SQL order
waiting, not a claimed HTTP latency optimization.

Final SQL again found 600 UNPAID orders and 600 creation ledger entries, stock 0,
allocated quota 600, inventory/quota deltas −600, and zero binding, uniqueness,
missing-order, ledger or payment-deadline errors. All timeout dispatch states were
SENT. A later explicit MQ check found Diff/Inflight 0; Redis handoffs were 0.
No payment, cancellation or queue deletion was used to make the run drain.

The last HTTP completed at 05:32:59.358485418 UTC. The first complete SQL sample was
05:33:04.264995 UTC, 4.90651 seconds later. The preceding incomplete SQL sample was
already before the last HTTP, so the supported wording is **confirmed drained
within five seconds**, not a precise five-second drain duration. The last database
order timestamp was 05:32:59.453827 UTC; its 95 ms difference from the last HTTP uses
cross-container wall clocks and is only supplementary information.

The fixed finite batch spanned 59.8461 seconds, averaging 10.0257 orders/s under an
approximately 10/s input. This does not establish a sustainable peak. All eight
thread dumps retained `scheduling-1`, now without either consumer's receive stack;
`seckill-order-1` and `seckill-timeout-1` independently performed their own work.
The decreased queue and changed execution stacks support the scheduling mechanism.

There was no business warmup in either arm. Setup completion to first HTTP was
approximately 140.72 s before and 185.62 s after; sampler-to-first-HTTP offsets were
6.404 s and 5.435 s. These are not exactly aligned startup phases. The full windows,
all 31 SQL/cgroup samples and all eight dumps were retained. The unchanged
non-profiled earlier A run also exhibited roughly one-minute order waiting, but its
numbers are not mixed into this instrumented comparison.

[Fixed-run raw output](local-diagnosis-after-20260906-raw.tar.gz) includes the full
source/JAR identifiers, workload, k6 points, SQL, thread dumps, MQ/Redis checks and
cleanup. Both benchmark applications were stopped and the original public signing
metadata restored after each run. Source, Docker CPU/memory and Commerce's 4-CPU
limit remained unchanged within each arm.

Validation: the targeted scheduling/MQ unit selection ran seven tests; the four
local CI targets passed; the real MySQL/Redis/RocketMQ catalog integration suite ran
121 tests without failures or skips, including 14 seckill transaction tests. The
new context test blocks both receives and checks that default recovery/dispatch
still run. The new integration case overlaps committed cancellation awaiting Redis
publication with actual MQ order creation, then replays both and checks SQL stock
and ledger truth. No batch-size or transaction test was removed or weakened.

## The sold-out control did not identify the old limiting side

A separate new control, `B1_vm8_r3000_20260906T041823Z`, used 32 activities with
quota 10 each, 320 legal preparation orders, then 3,000 expected rejections/s for
30 seconds. It completed 90,001 expected EXHAUSTED responses with zero dropped
iterations; SQL and the inventory ledger were unchanged during rejection.
Full-window p50 was 0.383333 ms and p99 was 29.256958 ms.

The two 15-second halves had p99 43.102717 and 3.548636 ms. That failed the
pre-registered steady-window qualification; it did not mean the service failed or
had reached capacity. No host-k6 point ran, Docker was never reduced to 6 CPUs, and
no cloud benchmark ran. Preparation attempts that expired or were interrupted are
retained separately from this completed control.

The [historical 3,000/s result](seckill_rejection_capacity_20260905.md) remains
90,000 expected rejections with zero drops. Neither this new control nor its fast
second half isolates the historical 4,000/s limiter. Generator and service were
still sharing the VM. The complete HTTP window, rather than k6's later JSON-output
shutdown, is the relevant resource window.

[Local isolation attempt raw output](local-isolation-attempts-20260906-raw.tar.gz)
contains the completed control, its resource samples and the retained preparation
failures. These runs do not replace the historical result or interpolate a precise
breakpoint between 3,000 and 4,000/s.

## Broker background CPU

With the benchmark applications stopped, a roughly 15-second `top -H` sample found
`TransactionalOpBatchService` consuming approximately one CPU core. The hot Linux
TID was 161 (`nid=0xa1`); before/after `jstack` snapshots both located it in
`TransactionalMessageServiceImpl.batchSendOpMessage:714`, through
`TransactionalOpBatchService.onWaitEnd` and `ServiceThread.waitForRunning`.

This matches the empty-transaction-context busy-loop mechanism reported in
[Apache RocketMQ issue #10966](https://github.com/apache/rocketmq/issues/10966).
An old empty context can leave the calculated next wakeup in the past, repeatedly
returning zero wait. The installed 5.5.0 and released 5.5.1 contain the same relevant
code; [proposed fix #10967](https://github.com/apache/rocketmq/pull/10967) was still
open when checked on 2026-09-06. No runtime heap fields were inspected, so the stack
and CPU evidence is distinguished from direct inspection of each context value.

The Broker was kept unchanged for the application comparison. Upgrading blindly,
changing transaction-check frequency, or moving the same binary to a larger machine
would not establish that this loop was fixed. Its CPU cost is a known background
condition, not proof that it caused the historical 4,000/s dropped iterations.
The thread CPU and stack originals are in the baseline archive above.
