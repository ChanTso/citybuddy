# Redis-first sold-out rejection capacity, normal-awake session

This session measured the HTTP rejection entry on a source-clean CityBuddy checkout at full
commit `c5af89d5e07fa5a20f0a32b865557fbbbb08aabd`. Under the registered stopping rule, the last
clean tested point was 3,000 offered requests/s and the first bad point was 4,000 offered
requests/s. This is a local sold-out rejection result, not order-completion capacity or a
production SLO.

## Environment and pre-registration boundary

- One local MacBook Pro ran Docker with 8 CPUs and 14,638,391,296 bytes of memory. Commerce had
  a 4-CPU limit. No build or CI overlapped the formal windows.
- All four fixtures used the same Auth JAR
  `819e6279a9c3562bfc77357339ed603e666a031acdd2a6c459fe1814186bf1cb` and Commerce JAR
  `afb6e193e05344a5bcb52b7829e032f6e8859a4060b3aa526b8fbdcb2f3bd73c`. k6 used
  `grafana/k6@sha256:5221b620a4f874faff6e32ba597aa667c058391fe4898b1c6f6377f062c6cdec`.
- Before the session, the lid was open, `pmset` recorded `FullWake` at 03:46:40 UTC, no benchmark
  or k6 container was running, and three Docker idle samples were registered. MySQL was
  0.70/0.70/0.73%, commerce Redis 1.81/1.67/0.21%, Broker proxy 5.96/6.73/5.87%, and NameServer
  0.19/0.22/0.23%. The registration is retained in the
  [pre-run record](https://github.com/ChanTso/citybuddy/pull/147#issuecomment-5549158048).
  Contemporaneous before/after power captures also reported `AppleClamshellState=No`; they are
  operator-run artifacts under `bench/.run/capacity-e-awake-20260905T035120Z/`, not part of the
  published result bundle.
- Each rate used a fresh fixture: 32 activities, quota 100 and stock 2,000,000, with a fresh user
  pool and topic suffix. Exactly 3,200 legal admissions were driven before timing and allowed to
  reach `ORDERED` / `UNPAID` / timeout dispatch `SENT`; all activities then had zero remaining
  quota and zero pending handoff. Formal users and idempotency keys were disjoint from preparation.
- Every formal point requested one 30-second constant-arrival-rate window. The planned sequence
  was 1,500, 2,000, 3,000, 4,000, then higher points only if the prior point remained clean. Stop
  at the first dropped/interrupted iteration, unexpected business result, or p99 at least twice
  the immediately preceding clean point. Expected `409 / EXHAUSTED` is a valid rejection, not an
  unexpected HTTP failure.

The earlier 1,500/s observation occurred during a separately documented DarkWake session and is
not pooled, compared or used as an anchor here. Its power evidence remains in
[`capacity_20260904_power_interruption.txt`](capacity_20260904_power_interruption.txt).

## Preparation failure and one same-parameter retry

The first 1,500/s setup attempt stopped during Commerce startup. RocketMQ returned `CODE 17` for
the fresh transaction topic, and the proxy readiness topic also had no route. Formal load,
preparation HTTP and `bench.env` creation had not started, so this attempt is not a capacity point
and contributes no clean/bad classification. The compound setup command also did not fail fast;
the explicit stop record, rather than later shell progress, is authoritative:
[`seckill_capacityEawake_20260905T035120Z_c5af89d_r1500_preparation_failure_stop.txt`](seckill_capacityEawake_20260905T035120Z_c5af89d_r1500_preparation_failure_stop.txt).

At 03:54:19 UTC the Broker and proxy route checks passed. One retry kept the same 1,500/s
candidate, 30-second window, activity/quota/stock shape and stop rule, while using a fresh unique
fixture namespace. It completed setup and preparation and became the session's first formal
point. The failed setup is retained; it was not converted into a performance result or silently
deleted.

## Formal results

`Complete` is the k6 summary's completed HTTP/iteration count. Zero-drop metrics are absent from
the summary JSON and are reported as zero by the retained step analysis; 4,000/s carries the
explicit `dropped_iterations` counter. Percentiles below are copied from each summary rather than
reconstructed from the large point streams.

| Offered/s | Fixture users | Runner envelope UTC | Complete | Dropped / interrupted | HTTP status / decision | p50 ms | p99 ms | SQL at post-window observation |
|---:|---:|---|---:|---:|---|---:|---:|---|
| 1,500 | 48,600 | 04:07:54–04:08:30 | 44,999 | 0 / 0 | 44,999 × `409 / EXHAUSTED` | 0.369167 | 329.8936131599999 | 3,200 `ORDERED/ADMITTED`; 3,200 `UNPAID/SENT`; ledger 3,200/-3,200/-3,200; stock 1,996,800 |
| 2,000 | 63,600 | 04:23:12–04:23:58 | 60,001 | 0 / 0 | 60,001 × `409 / EXHAUSTED` | 0.404791 | 507.332876 | 3,200 `ORDERED/ADMITTED`; 3,200 `UNPAID/SENT`; ledger 3,200/-3,200/-3,200; stock 1,996,800 |
| 3,000 | 93,600 | 04:38:39–04:39:45 | 90,000 | 0 / 0 | 90,000 × `409 / EXHAUSTED` | 0.383875 | 29.594460739999956 | 3,200 `ORDERED/ADMITTED`; 3,200 `UNPAID/SENT`; ledger 3,200/-3,200/-3,200; stock 1,996,800 |
| 4,000 | 123,600 | 04:54:42–04:56:08 | 117,681 | 2,321 / 0 | 117,681 × `409 / EXHAUSTED` | 0.379500 | 1188.8854586 | 3,200 `ORDERED/ADMITTED`; 3,200 `UNPAID/SENT`; ledger 3,200/-3,200/-3,200; stock 1,996,800 |

The SQL rows are preparation state: Redis-first formal rejections add no durable reservation,
order or ledger row. Each post-window observation occurred before that fixture's first unpaid
deadline, so timeout cancellation did not change the counts. The three ledger numbers are row
count, inventory delta and activity-quota delta respectively. They are nearby authoritative
business observations from the retained SQL invocation, not a claim about completed orders.

### Per-point raw bundles

| Offered/s | Environment and fixture | HTTP and derived row | Summary / points | SQL | CPU samples |
|---:|---|---|---|---|---|
| 1,500 | [setup](seckill_capacityEawake_20260905T035120Z_c5af89d_r1500retry1_setup.txt), [preflight](seckill_capacityEawake_20260905T035120Z_c5af89d_r1500retry1_preflight_head.txt), [preparation](seckill_capacityEawake_20260905T035120Z_c5af89d_r1500retry1_prep_http.txt) | [console](k6_capacityEawake_20260905T035120Z_c5af89d_r1500retry1_console.txt.gz), [steps](ladder_capacityEawake_20260905T035120Z_c5af89d_r1500retry1_steps.txt) | [summary](k6_capacityEawake_20260905T035120Z_c5af89d_r1500retry1_summary.json), [points](k6_capacityEawake_20260905T035120Z_c5af89d_r1500retry1_points.json.gz) | [before](seckill_capacityEawake_20260905T035120Z_c5af89d_r1500retry1_before_sql.txt), [after](seckill_capacityEawake_20260905T035120Z_c5af89d_r1500retry1_after_sql.txt) | [raw samples](k6_capacityEawake_20260905T035120Z_c5af89d_r1500retry1_cpu.txt) |
| 2,000 | [setup](seckill_capacityEawake_20260905T035120Z_c5af89d_r2000_setup.txt), [preflight](seckill_capacityEawake_20260905T035120Z_c5af89d_r2000_preflight_head.txt), [preparation](seckill_capacityEawake_20260905T035120Z_c5af89d_r2000_prep_http.txt) | [console](k6_capacityEawake_20260905T035120Z_c5af89d_r2000_console.txt.gz), [steps](ladder_capacityEawake_20260905T035120Z_c5af89d_r2000_steps.txt) | [summary](k6_capacityEawake_20260905T035120Z_c5af89d_r2000_summary.json), [points](k6_capacityEawake_20260905T035120Z_c5af89d_r2000_points.json.gz) | [before](seckill_capacityEawake_20260905T035120Z_c5af89d_r2000_before_sql.txt), [after](seckill_capacityEawake_20260905T035120Z_c5af89d_r2000_after_sql.txt) | [raw samples](k6_capacityEawake_20260905T035120Z_c5af89d_r2000_cpu.txt) |
| 3,000 | [setup](seckill_capacityEawake_20260905T035120Z_c5af89d_r3000_setup.txt), [preflight](seckill_capacityEawake_20260905T035120Z_c5af89d_r3000_preflight_head.txt), [preparation](seckill_capacityEawake_20260905T035120Z_c5af89d_r3000_prep_http.txt) | [console](k6_capacityEawake_20260905T035120Z_c5af89d_r3000_console.txt.gz), [steps](ladder_capacityEawake_20260905T035120Z_c5af89d_r3000_steps.txt) | [summary](k6_capacityEawake_20260905T035120Z_c5af89d_r3000_summary.json), [points](k6_capacityEawake_20260905T035120Z_c5af89d_r3000_points.json.gz) | [before](seckill_capacityEawake_20260905T035120Z_c5af89d_r3000_before_sql.txt), [after](seckill_capacityEawake_20260905T035120Z_c5af89d_r3000_after_sql.txt) | [raw samples](k6_capacityEawake_20260905T035120Z_c5af89d_r3000_cpu.txt) |
| 4,000 | [setup](seckill_capacityEawake_20260905T035120Z_c5af89d_r4000_setup.txt), [preflight](seckill_capacityEawake_20260905T035120Z_c5af89d_r4000_preflight_head.txt), [preparation](seckill_capacityEawake_20260905T035120Z_c5af89d_r4000_prep_http.txt) | [console](k6_capacityEawake_20260905T035120Z_c5af89d_r4000_console.txt.gz), [steps](ladder_capacityEawake_20260905T035120Z_c5af89d_r4000_steps.txt) | [summary](k6_capacityEawake_20260905T035120Z_c5af89d_r4000_summary.json), [points](k6_capacityEawake_20260905T035120Z_c5af89d_r4000_points.json.gz) | [before](seckill_capacityEawake_20260905T035120Z_c5af89d_r4000_before_sql.txt), [after](seckill_capacityEawake_20260905T035120Z_c5af89d_r4000_after_sql.txt) | [raw samples](k6_capacityEawake_20260905T035120Z_c5af89d_r4000_cpu.txt) |

The gzip-compressed point JSON files are the retained lossless originals. They are large and were not reread or
used to recompute this report; the retained summary and step-analysis values are the reported
metrics.

## Stop result and resource observations

The immediate 3,000/s clean point set the next p99 stop line at
`2 × 29.594460739999956 = 59.18892147999991 ms`. At 4,000/s, p99 was
1188.8854586 ms and 2,321 iterations were dropped. The first bad point therefore triggered both
registered conditions. All 117,681 requests that did complete still returned the expected
`409 / EXHAUSTED`, and the post-window SQL retained only the 3,200 preparation effects. No 8,000/s
point was run.

At 4,000/s the sampled Commerce peak was 378.32%, close to its four-CPU limit, while the sampled
k6 peak was 198.64%. These are individual `docker stats` observations, not time-weighted CPU,
method profiles or CPU per request. They show that both SUT and generator consumed substantial
CPU, but do not isolate which side first limited the window. The latency rise is observed at the
SUT boundary and k6 also dropped scheduled iterations; without a controlled generator/SUT
separation, the limiting side remains incompletely identified. No single request or code method is
assigned either peak.

Within this environment and workload, the supported result is: **last clean tested sold-out
rejection point 3,000 offered requests/s; first bad tested point 4,000 offered requests/s**. It is
not a claim that 3,000/s is a sustained production capacity, that 4,000/s is solely Commerce- or
generator-limited, or that rejected traffic measures asynchronous order completion.
