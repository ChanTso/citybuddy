# Fixed-load Redis-first comparison — stopped at B1

Status: incomplete comparison, not a Redis-first performance result or a capacity claim.
No B2/A2 run was performed, and neither pre-registered reporting outcome was established.

This is the historical failed session at the two revisions below. Its original observations
are not replaced by the later [corrected fixed-load comparison](redis_first_fixed_load_20260904.md).

Measured commits:

- A1: `e59af3660c9d4a0074d5e0da8663bffb932d2503`.
- B1: `e77bf7e3f42bd6e4f2adeba133004f329a7ee21b`.

## Workload and complete observations

The planned sequence was A1/B1/B2/A2: 800 offered requests/s for 30 seconds per window,
32 activities with quota 100 each, 25,000 distinct users, product stock 2,000,000.
Each arm recreated its fixture, topics/Broker and JVM. There was no explicit warm-up.
JVM launch was outside the window; database/Redis caches were not claimed to be cold.
Docker exposed 8 CPUs and 14,638,391,296 bytes; Commerce was actually limited to 4 CPUs.
The JAR mounts and hashes matched the two prebuilt, source-clean worktrees.

| Complete window | A1 | B1 |
|---|---:|---:|
| Nominal iterations | 24,000 | 24,000 |
| HTTP samples / completed iterations | 23,645 | 24,002 |
| Dropped iterations | 356 | 0 |
| Interrupted iterations | 0 | 0 |
| HTTP 201 / ADMITTED | 3,200 | 0 |
| HTTP 409 / EXHAUSTED | 20,445 | 0 |
| HTTP 503 | 0 | 24,002 |
| Unexpected HTTP responses | 0 | 24,002 |

The raw scheduled totals are not rewritten to equal nominal: A1 completed+dropped is
24,001, and B1 completed+dropped is 24,002. Dropped iterations have no HTTP latency sample.
A1's legacy `http_req_failed` count includes the 20,445 expected EXHAUSTED responses; it is
not a count of unexpected business errors.

A1 complete-response p50/p99 were 12.9875/1368.873844 ms. The HTTP 409 group uniquely
corresponded to EXHAUSTED and had p50/p99 10.978375/427.72190656 ms. The HTTP 201 group
had p50/p99 995.6908965/1522.5853765 ms. These are complete-window groups, not selected
post-warm-up seconds.

B1's complete-response p50/p99 were 0.301583/20.59127941 ms, but **every response was 503**.
Those numbers describe fast failure, not improved admission or rejection handling. No speedup
or before/after ratio is calculated.

A1 used 640 preallocated VUs and initialized up to 884, below the allowed maximum 2,400.
Its sampled k6 CPU peak was 71.22%. Dynamic VU initialization and dropped work are retained
as generator-context facts; those observations alone neither establish nor rule out client
effects. This was not a clean capacity point or a server-only ceiling measurement.

## Stop and diagnosed fixture mismatch

B1 returned only unexpected 503 responses. The sequence stopped without resetting its fixture,
running B2/A2, changing parameters, or replacing this failed run.

The read-only Redis snapshot at 2026-09-04 08:58:46 UTC was:

```json
{"activityId":"bench-activity-0","projectionVersion":1,"startsAt":"2020-01-01T00:00:00Z","endsAt":"2035-01-01T00:00:00Z","state":"ACTIVE","remainingQuota":100}
```

The unchanged `bench/setup_bench_env.sh:156` publishes those string timestamps but omits
`startsAtEpochMicros` and `endsAtEpochMicros`. B's `ReservationAdmissionStore:126–139`
requires the numeric fields and returns `MALFORMED_ACTIVITY` when either is absent;
`:766–780` raises an indeterminate-admission exception, which
`SeckillTransactionCoordinator:28–29` maps to HTTP 503. A's prior Lua path reads time values
from arguments supplied by the MySQL-backed path instead.

The production projection type already contains both numeric fields
(`SeckillProjection:10–11,25–26`). Identical setup scripts therefore did **not** imply that
the fixture was compatible with both runtime schemas. B1 did not exercise the intended
Redis-first admission/rejection workload.

Commerce also logged a separate scheduled-recovery error, `Reservation activity truth is
missing`. Its presence is retained as an unresolved observation, not substituted for the
fixture mismatch or claimed as the unique cause of all 503s.

## Boundaries and retained files

Before load, the PR recorded that this comparison reused already executed local real-middleware
catalog tests and SQL assertions. It did not rerun the full historical correctness recipe:
the old script requires 500 durable MySQL REJECTED rows, which is inapplicable to Redis-only
rejection. This experiment produces no new correctness-SQL verdict or zero-orphan claim.

The same B commit's hosted catalog CI remains red: 107 tests, one consumed-message-count
failure, zero errors and zero skips. Its prior local 107-test pass remains a separate result.
Performance sampling does not waive that failure or authorize merging the draft PR.

Each arm retains six original outputs. The points stream and console are losslessly compressed;
the other four files retain their original bytes. The source SHA and setup/run metadata
remain in the raw bundle.

- A1 [steps](ladder_scarcepair_20260904T083000Z_a1_e59af36_r800_steps.txt),
  [summary](k6_scarcepair_20260904T083000Z_a1_e59af36_r800_summary.json),
  [console](k6_scarcepair_20260904T083000Z_a1_e59af36_r800_console.txt.gz),
  [CPU](k6_scarcepair_20260904T083000Z_a1_e59af36_r800_cpu.txt),
  [setup](seckill_scarcepair_20260904T083000Z_a1_e59af36_r800_setup.txt),
  [points](k6_scarcepair_20260904T083000Z_a1_e59af36_r800_points.json.gz).
- B1 [steps](ladder_scarcepair_20260904T083000Z_b1_e77bf7e_r800_steps.txt),
  [summary](k6_scarcepair_20260904T083000Z_b1_e77bf7e_r800_summary.json),
  [console](k6_scarcepair_20260904T083000Z_b1_e77bf7e_r800_console.txt.gz),
  [CPU](k6_scarcepair_20260904T083000Z_b1_e77bf7e_r800_cpu.txt),
  [setup](seckill_scarcepair_20260904T083000Z_b1_e77bf7e_r800_setup.txt),
  [points](k6_scarcepair_20260904T083000Z_b1_e77bf7e_r800_points.json.gz).

Run metadata windows were A1 08:46:08–08:46:44 UTC and B1 08:55:39–08:56:15 UTC;
these include runner initialization/teardown around each 30-second offered window.
At 09:00:53 UTC both benchmark services had been deliberately stopped; containers and
Redis/MySQL state were retained. No benchmark load remained active.
