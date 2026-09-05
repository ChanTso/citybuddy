# Positive-admission entry capacity, normal-awake session

This session measured the positive `ADMITTED` HTTP entry on fresh, high-quota fixtures. The
new same-session 800/s A/B pair had mixed latency movement: B had a lower p50 and a higher p99.
It is one pair, not a claim of overall speedup or repeatability. For the B ladder, the last clean
tested point was 1,000 offered requests/s and the first bad tested point was 1,200 offered
requests/s, where p99 crossed the registered two-times line. This is local admission-entry
evidence, not asynchronous order-completion capacity or a production SLO.

## Source and environment boundary

- A was the source-clean reference commit
  `962693a9f25eb4e0b17d12e6d11eb7e9e8d57d6e`, with Identity/Auth JAR SHA-256
  `8866aaa6c9b49ff0839f54435ed25034c0cd55d4c012b2f7cbd0d59f9fe18083` and Commerce JAR
  SHA-256 `f314e6d4d789edf9f6c441f7d0f108a0bbee15fc527d51f7a645dc161c5e3e3d`.
- B was the source-clean current-main commit
  `c5af89d5e07fa5a20f0a32b865557fbbbb08aabd`, with Identity/Auth JAR SHA-256
  `819e6279a9c3562bfc77357339ed603e666a031acdd2a6c459fe1814186bf1cb` and Commerce JAR
  SHA-256 `afb6e193e05344a5bcb52b7829e032f6e8859a4060b3aa526b8fbdcb2f3bd73c`.
- One local MacBook Pro ran Docker with 8 CPUs and 14,638,391,296 bytes of memory; Commerce had
  a 4-CPU limit. k6 used
  `grafana/k6@sha256:5221b620a4f874faff6e32ba597aa667c058391fe4898b1c6f6377f062c6cdec`.
- Before the session, the lid was open and the retained power history recorded the last
  `DarkWake to FullWake` at 03:46:40 UTC. The three registered second-refresh host CPU samples
  were 80.30%, 78.98% and 77.60% idle. The A-retry and B per-point before/after captures kept
  `AppleClamshellState=No`, and the boot sleep/wake count remained 77 from A retry through the
  final B point. The registration is retained locally in
  `bench/.run/capacity-p-awake-20260905/session-start-idle.txt`; per-point captures are under
  `bench/.run/capacity-p-awake-20260905T045921Z/` and the detached A operator directory. They
  are operator artifacts, not part of the published result bundle. The contemporaneous public
  session record is the [P result comment](https://github.com/ChanTso/citybuddy/pull/147#issuecomment-5549673498).
- Every valid point used a fresh fixture/topic namespace with 32 activities, per-activity quota
  1,000,000 and product stock 2,000,000. The user pool was 48,100 at 800/s, 60,100 at 1,000/s
  and 72,100 at 1,200/s. The preflight user, 30-second warm users and 30-second formal users
  were disjoint. Warmup ran immediately before formal at the same offered rate and is reported
  separately but excluded from the formal result.
- The registered stop conditions were the first dropped/interrupted iteration, unexpected
  business result, or p99 at least twice the immediately preceding clean B point. Each formal
  request exercised the positive reservation entry; the retained step rows report every
  completed formal decision as `ADMITTED` and the k6 summaries report zero HTTP failures.

The old P-A1 result from the earlier two-path session is not pooled with this run. The comparison
below is the fresh A800 retry and fresh B800 fixture from this normal-awake session only.

## Invalid A800 preparation and one fresh retry

Attempt 0 completed its 800/s warmup from 05:04:16 to 05:04:48 UTC, but the compound operator
command incorrectly asserted that an excluded warmup must have zero drops. Its console records
23,875 completed `ADMITTED` decisions, 126 dropped and zero interrupted iterations, zero HTTP
failures, p50 12.484416 ms and p99 992.4250171599999 ms. The bad assertion stopped the command
before formal load began. This is an invalid preparation, not a bad 800/s formal point.

One fresh same-parameter A800 retry removed only that invalid warm-drop assertion. It used a new
fixture namespace, kept the same source/JARs, 800/s target, 30-second warm and formal windows,
and the registered stop rule. Its warmup remained excluded; the immediately following formal
window is the A result below.

## Excluded warmups

The warmup rows are retained to show exactly what preceded each formal point. Their drops and
latencies do not classify a formal capacity point.

| Variant / offered rate | Recorded warm envelope UTC | Complete | Dropped / interrupted | HTTP failures | Decision | p50 ms | p99 ms | Treatment |
|---|---|---:|---:|---:|---|---:|---:|---|
| A800 attempt 0 | 05:04:16-05:04:48 | 23,875 | 126 / 0 | 0 | 23,875 `ADMITTED` | 12.484416 | 992.4250171599999 | Excluded; bad operator assertion, no formal |
| A800 retry 1 | 05:08:57-05:09:29 | 23,745 | 257 / 0 | 0 | 23,745 `ADMITTED` | 11.727292 | 1193.3856149599999 | Excluded |
| B800 | 05:14:02-05:14:34 | 23,790 | 210 / 0 | 0 | 23,790 `ADMITTED` | 3.1682295 | 1130.86807363 | Excluded |
| B1000 | 05:18:40-05:19:14 | 29,460 | 541 / 0 | 0 | 29,460 `ADMITTED` | 4.530604 | 1386.44736469 | Excluded |
| B1200 | 05:23:39-05:24:14 | 35,136 | 865 / 0 | 0 | 35,136 `ADMITTED` | 5.855375 | 1572.0602861 | Excluded |

## Formal results

`Complete` is the k6 summary's HTTP/iteration count. Zero-drop counters are absent from the
formal summary JSON and are reported as zero by the retained step analysis. Percentiles are
copied from each summary and were not reconstructed from the large point streams.

| Variant / offered rate | Fixture users | Runner envelope UTC | Nominal | Complete | Dropped / interrupted | HTTP failures | Decision | p50 ms | p99 ms | Classification |
|---|---:|---|---:|---:|---:|---:|---|---:|---:|---|
| A800 retry 1 | 48,100 | 05:09:30-05:10:05 | 24,000 | 24,001 | 0 / 0 | 0 | 24,001 `ADMITTED` | 6.273125 | 34.78775 | Fresh A side of one pair |
| B800 | 48,100 | 05:14:34-05:15:10 | 24,000 | 24,001 | 0 / 0 | 0 | 24,001 `ADMITTED` | 2.347125 | 62.73225 | Fresh B side of one pair; clean |
| B1000 | 60,100 | 05:19:14-05:19:50 | 30,000 | 30,001 | 0 / 0 | 0 | 30,001 `ADMITTED` | 3.259625 | 93.999959 | Last clean tested B point |
| B1200 | 72,100 | 05:24:14-05:24:50 | 36,000 | 36,001 | 0 / 0 | 0 | 36,001 `ADMITTED` | 3.977958 | 204.046042 | First bad tested B point: p99 stop |

At 800/s, B's p50 moved from 6.273125 to 2.347125 ms while p99 moved from 34.78775 to
62.73225 ms. Those directions are mixed. With one fresh A/B pair and no repeat run, this does
not establish an overall performance improvement, a causal mechanism effect, or repeatability.

## Fixed post-window SQL observations

Each `after` query used the same fixed post-window observation step; the recorded SQL timestamps
landed 23-29 seconds after the corresponding formal runner completed. Counts include the one
preflight request, excluded warmup and formal requests for that fresh fixture. They must not be
read as formal-only output.

| Variant / offered rate | SQL observed UTC | Reservation state rows (`ADMITTED` / `ORDERED`) | Unpaid orders (`PENDING` / `SENT`) | Ledger rows / inventory delta / quota delta | Product stock |
|---|---|---|---|---|---:|
| A800 retry 1 | 05:10:32 | 47,250 / 497 | 32 / 465 | 497 / -497 / -497 | 1,999,492 |
| B800 | 05:15:35 | 47,326 / 466 | 32 / 434 | 466 / -466 / -466 | 1,999,518 |
| B1000 | 05:20:13 | 58,997 / 465 | 32 / 433 | 465 / -465 / -465 | 1,999,503 |
| B1200 | 05:25:19 | 70,640 / 498 | 32 / 466 | 498 / -498 / -498 | 1,999,470 |

The snapshots deliberately retain a large `ADMITTED` backlog and an in-flight 32-order
`PENDING` batch. The HTTP result therefore measures acceptance into the admission path. It does
not show that all accepted reservations had become durable orders, been paid, or completed by
the observation time. The authoritative SQL is nearby business state, not a
completion-throughput conversion.

## Raw evidence

### A files from the detached reference worktree

The following A files were copied byte-for-byte from the source-clean detached A worktree. Point
streams are published with lossless gzip compression.

Attempt 0:

- [warmup console](k6_capacityPawake_20260905T045921Z_a800_962693a_warmup_console.txt.gz)
- [warmup points](k6_capacityPawake_20260905T045921Z_a800_962693a_warmup_points.json.gz)
- [preflight](seckill_capacityPawake_20260905T045921Z_a800_962693a_preflight.txt)
- [before SQL](seckill_capacityPawake_20260905T045921Z_a800_962693a_before_sql.txt)

Retry 1:

- [setup](seckill_capacityPawake_20260905T045921Z_a800retry1_962693a_setup.txt)
- [preflight](seckill_capacityPawake_20260905T045921Z_a800retry1_962693a_preflight.txt)
- [before SQL](seckill_capacityPawake_20260905T045921Z_a800retry1_962693a_before_sql.txt)
- [after SQL](seckill_capacityPawake_20260905T045921Z_a800retry1_962693a_after_sql.txt)
- [warmup console](k6_capacityPawake_20260905T045921Z_a800retry1_962693a_warmup_console.txt.gz)
- [warmup summary](k6_capacityPawake_20260905T045921Z_a800retry1_962693a_warmup_summary.json)
- [warmup points](k6_capacityPawake_20260905T045921Z_a800retry1_962693a_warmup_points.json.gz)
- [formal console](k6_capacityPawake_20260905T045921Z_a800retry1_962693a_console.txt.gz)
- [formal summary](k6_capacityPawake_20260905T045921Z_a800retry1_962693a_summary.json)
- [formal points](k6_capacityPawake_20260905T045921Z_a800retry1_962693a_points.json.gz)
- [CPU samples](k6_capacityPawake_20260905T045921Z_a800retry1_962693a_cpu.txt)
- [step row](ladder_capacityPawake_20260905T045921Z_a800retry1_962693a_steps.txt)

### B per-point bundles

| Offered/s | Environment and probe | Warmup (excluded) | Formal HTTP and step row | Formal summary / points | SQL | CPU samples |
|---:|---|---|---|---|---|---|
| 800 | [setup](seckill_capacityPawake_20260905T045921Z_b800_c5af89d_setup.txt), [preflight](seckill_capacityPawake_20260905T045921Z_b800_c5af89d_preflight.txt) | [console](k6_capacityPawake_20260905T045921Z_b800_c5af89d_warmup_console.txt.gz), [summary](k6_capacityPawake_20260905T045921Z_b800_c5af89d_warmup_summary.json), [points](k6_capacityPawake_20260905T045921Z_b800_c5af89d_warmup_points.json.gz) | [console](k6_capacityPawake_20260905T045921Z_b800_c5af89d_console.txt.gz), [steps](ladder_capacityPawake_20260905T045921Z_b800_c5af89d_steps.txt) | [summary](k6_capacityPawake_20260905T045921Z_b800_c5af89d_summary.json), [points](k6_capacityPawake_20260905T045921Z_b800_c5af89d_points.json.gz) | [before](seckill_capacityPawake_20260905T045921Z_b800_c5af89d_before_sql.txt), [after](seckill_capacityPawake_20260905T045921Z_b800_c5af89d_after_sql.txt) | [raw samples](k6_capacityPawake_20260905T045921Z_b800_c5af89d_cpu.txt) |
| 1,000 | [setup](seckill_capacityPawake_20260905T045921Z_b1000_c5af89d_setup.txt), [preflight](seckill_capacityPawake_20260905T045921Z_b1000_c5af89d_preflight.txt) | [console](k6_capacityPawake_20260905T045921Z_b1000_c5af89d_warmup_console.txt.gz), [summary](k6_capacityPawake_20260905T045921Z_b1000_c5af89d_warmup_summary.json), [points](k6_capacityPawake_20260905T045921Z_b1000_c5af89d_warmup_points.json.gz) | [console](k6_capacityPawake_20260905T045921Z_b1000_c5af89d_console.txt.gz), [steps](ladder_capacityPawake_20260905T045921Z_b1000_c5af89d_steps.txt) | [summary](k6_capacityPawake_20260905T045921Z_b1000_c5af89d_summary.json), [points](k6_capacityPawake_20260905T045921Z_b1000_c5af89d_points.json.gz) | [before](seckill_capacityPawake_20260905T045921Z_b1000_c5af89d_before_sql.txt), [after](seckill_capacityPawake_20260905T045921Z_b1000_c5af89d_after_sql.txt) | [raw samples](k6_capacityPawake_20260905T045921Z_b1000_c5af89d_cpu.txt) |
| 1,200 | [setup](seckill_capacityPawake_20260905T045921Z_b1200_c5af89d_setup.txt), [preflight](seckill_capacityPawake_20260905T045921Z_b1200_c5af89d_preflight.txt) | [console](k6_capacityPawake_20260905T045921Z_b1200_c5af89d_warmup_console.txt.gz), [summary](k6_capacityPawake_20260905T045921Z_b1200_c5af89d_warmup_summary.json), [points](k6_capacityPawake_20260905T045921Z_b1200_c5af89d_warmup_points.json.gz) | [console](k6_capacityPawake_20260905T045921Z_b1200_c5af89d_console.txt.gz), [steps](ladder_capacityPawake_20260905T045921Z_b1200_c5af89d_steps.txt) | [summary](k6_capacityPawake_20260905T045921Z_b1200_c5af89d_summary.json), [points](k6_capacityPawake_20260905T045921Z_b1200_c5af89d_points.json.gz) | [before](seckill_capacityPawake_20260905T045921Z_b1200_c5af89d_before_sql.txt), [after](seckill_capacityPawake_20260905T045921Z_b1200_c5af89d_after_sql.txt) | [raw samples](k6_capacityPawake_20260905T045921Z_b1200_c5af89d_cpu.txt) |

The gzip-compressed point JSON files are the retained lossless originals. They are large and were not read or
used to recompute this report; summary JSON, step rows and SQL output are the reported sources.

## Stop result

B1000 was clean and set the next p99 stop line at
`2 x 93.999959 = 187.999918 ms`. B1200 completed all 36,001 iterations with zero drop,
interruption and HTTP failure, but its p99 was 204.046042 ms, above that line. It was therefore
the first bad point under the registered rule, and no 2,400/s point was run.

Within this exact local topology, fresh-fixture shape and 30-second windows, the supported B
result is: **last clean tested positive-admission entry point 1,000 offered requests/s; first bad
tested point 1,200 offered requests/s because of the adjacent-point p99 rule**. The observed
transition is conditional on this run configuration. It is not a sustained production limit,
an order-completion rate, or evidence that B is uniformly faster than A.
