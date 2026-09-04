# Redis-first: complete rejection comparison, incomplete admission comparison

At 800 offered requests/s, both sold-out comparisons reduced HTTP p50/p99 beyond the
pre-registered two-repeat variation rule. All four windows returned only EXHAUSTED/409,
with zero dropped or interrupted iterations. This is not an order-throughput or capacity result.
The plentiful-admission comparison is incomplete; its added latency/resource cost is unquantified.

## Sources and workload

- A: `962693a9f25eb4e0b17d12e6d11eb7e9e8d57d6e`, the prior runtime
  `e59af3660c9d4a0074d5e0da8663bffb932d2503` plus the common fixture-only patch.
- B: `cc47522b2fe782c07fc7c0d76256a475eb4fcb35`, Redis-first plus that same patch.
- Common fixture patch: `cc47522b2fe782c07fc7c0d76256a475eb4fcb35`;
  setup blob: `857425034f4d9615dd9e8474cb214eb5e9eaad29`. A's runtime source is unchanged.
  Workload, setup, runner and topology files were identical across the two committed,
  source-clean trees. Artifacts were separately prebuilt; each setup file records actual JAR hashes.
- One local machine: Docker 8 CPUs / 14,638,391,296 bytes, Commerce limited to 4 CPUs.
  No builds or CI overlapped measurement. Login/token setup is excluded; measured HTTP requests
  use the real Commerce JWT boundary and the local dependency topology.
- Every formal window: 32 activities, 25,000 users, stock 2,000,000, 800 offered/s for 30 seconds.
  Each recreated its JVM, fixture and Broker/topics. These are entry-path measurements.
- E (sold out): legal initial quota 1/activity, exhausted through the API before measurement.
  Exactly 256 serial preparation requests admit 32 and reject 224, followed by one rejected replay.
  P (plentiful): quota 1,000/activity; all 256 preparation requests admit, followed by one replay.
  Formal requests use disjoint users and keys. P starts with 992 quota/activity remaining.
- Preparation SQL actually observed all admitted preparation work settled within 120 seconds.
  B additionally had no pending activity handoffs. Each completed window passed two groups
  of three host-idle samples, at least 80% each. Fixed 256-request preparation does **not**
  establish converged JIT/cache steady state. All seconds, including early slow requests, remain.
- Formal load ended before the first preparation order's 15-minute unpaid timeout.
  The existing runner and its empty k6 thresholds were unchanged. Expected EXHAUSTED/409
  remains `http_req_failed=1` in raw k6 output; analysis separates it from unexpected errors.

## All formal windows, in execution order

Rates use k6's own elapsed-time denominator, not HTTP count divided by nominal 30 seconds.
The nominal schedule is 24,000; observed counts are not rewritten to that target.
Each window has equal HTTP, completed-iteration and decision counts, no other status/decision,
zero drops and zero interrupted iterations. Percentiles use every raw HTTP duration sample.

| Window | HTTP / completed | Status / decision | Achieved/s | p50 ms | p99 ms |
|---|---:|---|---:|---:|---:|
| E-A1 | 24,001 | 409 / EXHAUSTED | 799.808 | 9.858291 | 241.034750 |
| E-B1 | 24,001 | 409 / EXHAUSTED | 799.833 | 0.294958 | 3.042209 |
| E-B2 | 24,001 | 409 / EXHAUSTED | 800.000 | 0.293000 | 2.659292 |
| E-A2 | 24,000 | 409 / EXHAUSTED | 799.838 | 9.602834 | 205.675757 |
| P-A1 | 24,002 | 201 / ADMITTED | 799.854 | 6.706958 | 104.732346 |

Before new measurements, the PR registered this lower-is-better rule, separately for each
metric: both A1-B1 and A2-B2 must be positive, and their minimum must exceed
`max(abs(A1-A2), abs(B1-B2))`. This is a descriptive two-repeat rule, not statistical significance.

| E metric | A1-B1 | A2-B2 | Within-arm variation | Repeated improvement |
|---|---:|---:|---:|---|
| p50, ms | 9.563333 | 9.309834 | 0.255458 | Yes |
| p99, ms | 237.992541 | 203.016465 | 35.358993 | Yes |
| Dropped iterations | 0 | 0 | 0 | No: unchanged at zero |

Thus E p99 was 205.7–241.0 ms on A and 2.66–3.04 ms on B across the two repeats.
This establishes a benefit at this load, not a maximum rate, production SLO, or success-order rate.

## Sampled CPU, including unfavorable observations

Every cell is median / maximum CPU percent, with **7 samples per component per window**.
100% represents one CPU. All samples, including startup and near-zero tail samples, remain;
these are not time-weighted CPU-seconds/request. The existing sampler covers only the four
listed containers, not auth, Broker, all system work, or Java methods.

| Window | Commerce | MySQL | Redis | k6 |
|---|---:|---:|---:|---:|
| E-A1 | 128.70 / 383.47 | 221.49 / 236.18 | 6.47 / 9.61 | 51.59 / 86.34 |
| E-B1 | 17.61 / 145.20 | 0.30 / 2.06 | 4.52 / 7.91 | 34.90 / 50.23 |
| E-B2 | 17.27 / 161.26 | 1.42 / 1.71 | 4.69 / 10.15 | 34.58 / 62.07 |
| E-A2 | 135.06 / 281.59 | 231.25 / 238.63 | 6.35 / 9.44 | 56.23 / 65.17 |
| P-A1 | 93.80 / 359.17 | 77.74 / 103.70 | 6.17 / 9.81 | 44.31 / 68.24 |

All four E medians and the Commerce/MySQL maxima satisfy the registered rule. The k6
maximum does not. Neither does the Redis maximum: B2's 10.15% exceeds A2's 9.44%.
No unobserved bottleneck or total-system resource saving is inferred from these samples.

## SQL at the fixed observation point

Each original SQL output records the measured SHA, runner's successful exit, and observation
exactly 120 seconds later. This is not exactly 120 seconds after the last HTTP response:
the unchanged runner performs output processing before exiting. Counts include preparation.
The three SELECTs execute sequentially in one `mysql --table -e` invocation without an
explicit transaction; they are nearby observations, not a cross-table atomic snapshot.

```sql
SELECT state,decision_code,COUNT(*) n FROM seckill_reservation WHERE activity_id LIKE 'bench-activity-%' GROUP BY state,decision_code;
SELECT status,timeout_dispatch_state,COUNT(*) n,MIN(created_at) first_created,MAX(created_at) last_created FROM seckill_order WHERE activity_id LIKE 'bench-activity-%' GROUP BY status,timeout_dispatch_state;
SELECT COUNT(*) ledger_rows,COALESCE(SUM(inventory_delta),0) inventory_delta,COALESCE(SUM(activity_quota_delta),0) quota_delta FROM inventory_ledger WHERE activity_id LIKE 'bench-activity-%';
```

| Window | Runner exit → SQL UTC | Reservation snapshot | Ledger rows / inventory delta / quota delta |
|---|---|---|---|
| E-A1 | 11:00:31 → 11:02:31 | 24,225 REJECTED; 32 ORDERED | 32 / -32 / -32 |
| E-B1 | 11:14:07 → 11:16:07 | 32 ORDERED; no durable REJECTED | 32 / -32 / -32 |
| E-B2 | 11:25:12 → 11:27:12 | 32 ORDERED; no durable REJECTED | 32 / -32 / -32 |
| E-A2 | 11:34:42 → 11:36:42 | 24,224 REJECTED; 32 ORDERED | 32 / -32 / -32 |
| P-A1 | 11:46:00 → 11:48:00 | 23,165 ADMITTED; 1,093 ORDERED | 1,093 / -1,093 / -1,093 |

E's 32 orders are preparation only; A's durable rejection totals also include 224 preparation
rejections. P's 1,093 orders include 256 preparation orders; 1,061 timeout dispatches were SENT
and 32 PENDING. This is a backlog snapshot, not a completed-order benchmark or zero-orphan proof.
No unhandled recovery-worker exception was observed in the retained per-window logs.

## Incomplete work and deviations

The planned order was E-A1/B1/B2/A2 then P-A1/B1/B2/A2, with a hard session deadline
of 12:15:47 UTC, starting 10:35:47. Only the five windows above ran. There is no P after sample,
so no admission-cost comparison, pooled E/P result or claim of zero admission overhead.

P-B1 completed its 256 admissions, replay and timely drain check, but no formal requests.
Its six retained pre-load idle groups were 79.69/78.49/79.92, 79.73/81.47/80.94,
81.40/81.40/79.26, 79.90/80.30/74.66, 80.18/79.96/80.18 and 76.45/76.29/77.21 percent.
Each missed the unchanged 80% rule. After the fifth group, remaining budget no longer reliably
covered the two later fresh repeats; measurement was deliberately ended. The already-running
sixth group was preserved but could not trigger a run. This was a budget stop, not a
pre-registered five-group limit, failed HTTP test, or evidence that the owner ran other software.
Host idle includes the benchmark services and system background work. Benchmark auth/Commerce
were stopped and no k6 was present at 12:04:40 UTC; no automatic follow-up is scheduled.

Before E-A1, one **unmeasured preparation** was invalidated: requests ended at 10:42:51,
but the first drain query was delayed until 10:46:56, missing the 120-second observation rule.
Later statement timestamps were not used to reconstruct on-time visibility. The exception—one
complete fresh preparation, unchanged parameters/deadline—was recorded before any formal sample.
Its originals remain preserved; E-A1's public label therefore contains `prep2`. No measured
window was replaced. The earlier [all-503 failed comparison](scarcepair_20260904_incomplete.md)
also remains separate and is not a before/after benefit result.

## Engineering tradeoff and claim boundary

`SeckillTransactionCoordinator.submit` returns a rejection before message submission;
`SeckillReservationService.preAdmit` validates the intent then invokes Redis, without the
business repository/transaction path. The projection must carry every admission decision input.
An admitted Lua decision retains a pending handoff until the half-message/MySQL path completes,
with recovery for interrupted handoff; rejection avoids that business MySQL/MQ work.
The previous missing fixture timestamps were a fixture defect, not a demonstrated design cost.

The supported portfolio statement is: choose Redis/Lua pre-admission so sold-out rejection
does not enter business MySQL/MQ; this fixed-load comparison reduced rejection p99, at the
architectural cost of complete decision projections and pending-handoff recovery. The added
admission-path performance cost has **not** been measured here.

## Raw bundles

All five bundles retain summary, lossless points, CPU, console, steps and setup; SQL observations
are additionally published. Points and consoles use lossless gzip so whitespace formatting
hooks cannot edit tool output. No post-warm-up seconds were selected or original output edited.

| Window | Summary | Points | CPU | SQL |
|---|---|---|---|---|
| E-A1 | [summary](k6_twopath_20260904T103547Z_E-A1-prep2_962693a_r800_summary.json) | [points](k6_twopath_20260904T103547Z_E-A1-prep2_962693a_r800_points.json.gz) | [CPU](k6_twopath_20260904T103547Z_E-A1-prep2_962693a_r800_cpu.txt) | [SQL](twopath_20260904T103547Z_E-A1-prep2_post120-sql.txt) |
| E-B1 | [summary](k6_twopath_20260904T103547Z_E-B1_cc47522_r800_summary.json) | [points](k6_twopath_20260904T103547Z_E-B1_cc47522_r800_points.json.gz) | [CPU](k6_twopath_20260904T103547Z_E-B1_cc47522_r800_cpu.txt) | [SQL](twopath_20260904T103547Z_E-B1_post120-sql.txt) |
| E-B2 | [summary](k6_twopath_20260904T103547Z_E-B2_cc47522_r800_summary.json) | [points](k6_twopath_20260904T103547Z_E-B2_cc47522_r800_points.json.gz) | [CPU](k6_twopath_20260904T103547Z_E-B2_cc47522_r800_cpu.txt) | [SQL](twopath_20260904T103547Z_E-B2_post120-sql.txt) |
| E-A2 | [summary](k6_twopath_20260904T103547Z_E-A2_962693a_r800_summary.json) | [points](k6_twopath_20260904T103547Z_E-A2_962693a_r800_points.json.gz) | [CPU](k6_twopath_20260904T103547Z_E-A2_962693a_r800_cpu.txt) | [SQL](twopath_20260904T103547Z_E-A2_post120-sql.txt) |
| P-A1 | [summary](k6_twopath_20260904T103547Z_P-A1_962693a_r800_summary.json) | [points](k6_twopath_20260904T103547Z_P-A1_962693a_r800_points.json.gz) | [CPU](k6_twopath_20260904T103547Z_P-A1_962693a_r800_cpu.txt) | [SQL](twopath_20260904T103547Z_P-A1_post120-sql.txt) |
