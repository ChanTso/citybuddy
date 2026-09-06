# Sustained order-completion registration — 2026-09-06

Measured CityBuddy: `1993c281c81e1ea34708773eea3a2825657bef84`.
Product source and both JARs match the previously measured scheduling fix
`cd213a846769b40cfbc95e791847f338fd7917c3`; the intervening changes are documentation
and archive commits. Each setup records the actual full commit and JAR hashes.

This series will answer: what admitted input can the existing consumer sustain for
five minutes without accumulating work, and when does order production fall behind?
It does not reclassify the sold-out 3,000/s path as completed-order throughput.

## Workload, scope and sequence

- One activity and one product, quantity/version 1, 300 seconds at one constant rate.
  Existing 32-activity setup still shares a single product; it is not 32 independent
  inventory partitions. This series deliberately continues the single-product story.
- Original `setup_bench_env.sh`, `run_ladder.sh`, `k6/seckill_ladder.js`; no new app,
  consumer, batch, poll-delay, Broker, thread-pool or CPU-limit changes during a point.
- MacBook Pro M4; Docker 8 CPU / 14,638,391,296 bytes; Commerce 4 CPU; k6 in the same VM.
  ShopMate and unrelated project services are stopped; no concurrent build or model batch.
- Start at 20/s. If clean, try 40/s; if overloaded, try 10/s. A 20-clean/40-overloaded
  bracket permits 30/s, then 25/s if useful. Stop refining at a 5/s bracket; no exact
  integer maximum claim. An unexpected failure or uncertain plateau is diagnosed
  rather than automatically raising/lowering load.
- Each point has a fresh fixture and topic/group. Users, stock and quota are all
  `rate * 300 + 50`, leaving room for k6's duration-boundary iteration. Every user is
  used once; every non-ADMITTED result is reported, not absorbed as successful work.
- There is no separate positive warmup. Retain all five minutes and all counts;
  examine the first 60 seconds separately and use the following four 60-second
  windows for steady-load interpretation. A problem in the first minute is not erased.

## Readiness and state ownership

Before each setup: previous applications are stopped only after admitted work has
completed or the run is declared incomplete; global/per-activity handoffs must be
zero, with no non-expiring intent. Existing finite-TTL intent is allowed only when
the new rate's `k6-rate_<rate>-<iteration>` namespace has not occurred in any still-live
prior fixture. Different rates use different keys. Repeated/uncertain rate namespaces
wait for the actual maximum remaining TTL and confirm expiry. No intent is deleted or
its TTL shortened to make a point ready. All retained-key counts/TTLs are recorded.

The default database's public signing metadata is backed up before the series and
restored at the end, after benchmark applications stop. Private credentials and
signing keys remain in existing ignored files. Before input, SQL must show zero
reservations/orders, correct stock/quota, and every token must have at least 660
seconds remaining (300 seconds input + up to 300 seconds observation + 60 seconds).

## Measurements and decision boundaries

Raw k6 output retains offered rate, achieved iterations, HTTP/decision counts,
dropped/interrupted iterations and complete latency distributions. SQL sampled every
five seconds records admitted reservations, orders, pending states, timeout-dispatch
backlog, stock, quota and ledger. SQL computes one-minute production counts and
reservation-to-order waits, with an additional cohort breakdown by reservation minute.
Standard Docker/cgroup/host CPU observations accompany the point. There is no SIGQUIT
or CPU profiler in this sustained series.

Capacity interpretation separates three conditions: successful arrival scheduling,
correct admission, and bounded asynchronous work. Zero HTTP errors alone is not a
pass. p99 doubling is not a capacity stop rule. At each examined minute boundary,
report cumulative admissions/orders and queue size, plus the observed minute rate;
report whether the final minutes continue accumulating work or settle into a bounded
batch-sized queue. The detailed numerical queue-drift rule is recorded below before
the first point; near-boundary uncertainty is reported rather than silently passed.

Stop new input for unexpected business errors, a process/observer failure, memory
failure, or a payment deadline too close to preserve this unpaid-order workload.
A growing queue alone may finish the registered five-minute window if it remains
bounded in time by the stop limits; the post-input limit is 300 seconds. Never cancel,
pay or delete messages to manufacture completion. A run that does not drain is retained
as incomplete and blocks destructive fixture reset until its state is understood.

Post-input SQL checks order/reservation binding, uniqueness, order/ledger quantities,
stock/quota conservation and absence of unpaid-deadline crossover. Read actual MQ
Diff/Inflight and Redis pending handoffs. Report the last-HTTP-to-first-clear-SQL
observation interval, not a precise ACK time. Separate order clearance, timeout-message
dispatch and later explicit MQ observations. Finite batch averages are not maximum
stable throughput.

Any later source/configuration optimization receives its own committed revision,
pre-registered same-topology comparison, and actual correctness checks. This series
does not automatically trigger cloud work; cloud need is discussed first.

## Frozen queue interpretation before first input

A = SQL ADMITTED reservations; O = orders; Q = A-O is interpreted only when no
cancellation/unfulfilled/business-error state exists. Examine the four post-startup
minute endpoints using the nearest five-second sample and its actual timestamp.
A clean bounded result has endpoint Q at most 32 (two batches of 16), no two-minute
net growth exceeding 32, and no rising oldest-pending age over the final three
endpoints above five seconds. The same oldest pending reservation remaining for
more than two minutes is investigated. These tolerances account for batch phase;
32 is not a service hard limit. A stationary larger queue is inconclusive under
this rule, not automatically an overloaded or failed service.

Clear overload requires at least three consecutive minute-to-minute increases of
Q with net growth above 32, while achieved HTTP input remains on target and SQL
production continues falling behind admissions. Rising oldest-pending age supports
that diagnosis. Report the actual counts and ages irrespective of classification.
A growing queue may complete all 300 input seconds; stop sooner only for the
previously listed correctness/process/memory/deadline conditions. Final correctness
and drain observations remain separate from steady-load classification.
