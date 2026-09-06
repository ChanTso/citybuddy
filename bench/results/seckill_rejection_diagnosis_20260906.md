# Fixed-warmup sold-out diagnosis — 2026-09-06

Measured CityBuddy: `16cb21154d1ae94c65fff56b8a96ea7f4514f924` for both points.
MacBook Pro M4, 10 host cores / 24 GB; Docker 8 CPU / 14,638,391,296 bytes;
Commerce limited to 4 CPU. k6 and all services run in the same Docker VM.

Both new formal windows completed all offered work without dropped or interrupted
iterations. At 4,000/s, 120,000 requests returned fresh `409 / EXHAUSTED`, with no
business writes and no Commerce quota throttling. This point did not reach an
identified limiting side. It does not retrospectively establish why the historical
4,000/s run dropped work, or establish an exact maximum capacity.

## Workload and readiness

The [registration](seckill_local_rejection_registration_20260906.md) fixed the procedure
before these points: each has a new 32-activity fixture, quota 10 per activity, one
shared product with stock 2,000,000, and 320 legal preparation admissions through the
real reservation API. Before load, all 320 were ORDERED / UNPAID / SENT, inventory and
quota ledger deltas were both -320, and stock was 1,999,680. Redis remaining quota and
handoffs were zero for every activity; explicit MQ transaction-topic queries found
all four queues empty of pending/inflight work.

Each k6 process used 20 seconds of resource observation, 1,000/s for 30 seconds of
sold-out warmup, a fixed five-second gap, then the complete 30-second formal scenario.
The original VU formula, JSON output and application/JARs were unchanged between
points. The preparation, warmup and formal users occupy disjoint ranges. Unique
request-label prefixes prevent replay of retained terminal intents. The formal
scenario, not the combined k6 summary, supplies the counts and distributions below.

Before lead-in, minimum token life was 660.908 / 711.910 seconds and earliest unpaid
order deadline margin was 831 / 858 seconds, exceeding the registered 300-second
minimum. The later readbacks still had live credentials and unpaid preparation orders.

## Complete formal windows

| Formal offered rate | Completed HTTP / iterations | Decision / replay | Dropped / interrupted | HTTP p50 / p99, unmodified raw values |
|---|---:|---|---:|---:|
| 3,000/s × 30s | 90,001 / 90,001 | EXHAUSTED / false for all | 0 / 0 | 0.419583 / 8.064084ms |
| 4,000/s × 30s | 120,000 / 120,000 | EXHAUSTED / false for all | 0 / 0 | 0.350708 / 6.451068ms, with timing anomaly below |

The 3,000/s boundary iteration is included in the 90,001 total. Warmup completed
30,000 and 30,002 iterations respectively; the two extra actual warmup iterations
are retained, covered by the token guard, and do not enter the formal count. Their
exact scheduler/timer cause was not established. Last-warmup-to-first-formal iteration
separation was about 5.008009 / 5.008775 seconds, so warmup work did not overlap formal
work. No beginning or tail of either formal scenario was removed.

k6's default `http_req_failed` marks expected HTTP 409 responses as failed; the
business outcome was EXHAUSTED throughout. Those raw counters remain unchanged.
Formal half-window p99 was 8.591585 / 7.381677ms at 3,000/s and 7.216918 / 5.488656ms
at 4,000/s. Half-window variation is diagnostic, not an automatic capacity failure.

At 4,000/s, one formal HTTP duration at `08:23:55.301389416Z` was **-0.706889ms**;
its receiving metric was -0.931014ms. Both remain in the point stream and in the
unmodified distribution. The local `timed` log inspected around this window did not
provide a corresponding adjustment record, so this anomaly is not attributed to
NTP or a particular clock mechanism. Completion and business correctness remain
observable, but this is not clean submillisecond latency acceptance. No negative
sample was discarded or clamped, and the smaller p99 is not claimed as an improvement.

## Resources aligned with formal requests

| Observation | 3,000/s | 4,000/s |
|---|---:|---:|
| Commerce cgroup CPU average, fraction of one core | 0.767 | 0.910 |
| Interior cgroup observation span | 28.149s | 29.113s |
| Added throttled periods / throttled microseconds | 0 / 0 | 0 / 0 |
| k6 sampled CPU median / maximum, 100% = one core | 86.21% / 125.35% | 90.455% / 158.29% |
| Maximum sampled active VUs | 55 | 14 |
| Host sampled idle median / minimum | 50.235% / 31.42% | 44.81% / 31.60% |

Cgroup integration uses only sample endpoints inside the actual formal HTTP window;
it does not extrapolate those shorter spans over all 30 seconds. Docker refreshes
include repeated readings. The generator's later JSON flushing and exit period is
excluded from formal CPU interpretation. In particular, the 4,000/s formal HTTP
window was approximately 08:23:54–08:24:24, while its process finished around 08:25:16.

Broker background CPU and other dependencies are retained in the raw samples. Redis
AOF rewrites increased 12→14 and 14→15 over the full before/after INFO intervals;
expired keys increased by 4,998 / 6,773. Those endpoint observations do not locate
rewrite work within the formal 30 seconds. Neither interval added evictions, delayed
fsyncs or error replies. Host compression/pageout activity existed, with no observed
swap-in/out increment. Commerce had no added OOM/memory events; VM memory PSI avg10
remained zero, with a few milliseconds of cumulative pressure at the 4,000/s point.
These observations are not a claim that background work or memory activity vanished.

Together, full scheduling, small observed active-VU counts, CPU headroom and zero
Commerce throttling provide no evidence of either side reaching a hard limit at
this 4,000/s load. They do not predict behavior at an unmeasured higher rate.

## Authoritative after state

Before/after SQL differed only in observation time and remaining deadline: all 320
preparation reservations/orders, their per-activity counts, timeout-dispatch state,
ledger movements, product stock and activity allocations remained unchanged. MQ
broker/consumer offsets also remained unchanged, with Diff=0 and Inflight=0 on every
transaction queue; all Redis quotas and handoffs remained zero. Rejection traffic
created no business reservation, order or inventory movement in MySQL.

## Decision and historical boundary

No host-generator / VM6 point, logger-format experiment or cloud measurement follows
this series. The registered reason to isolate further was a reproducible unresolved
limiting-side question. The warmed 4,000/s point has no incomplete scheduling or
quota limit to separate; moving it would create another topology without resolving
the historical run's cause. Docker stayed at 8 CPU / 14GB, Commerce at 4 CPU.

The historical 3,000/s / 90,000 headline remains attached to its original revision
`c5af89d5e07fa5a20f0a32b865557fbbbb08aabd` and
[original workload](seckill_rejection_capacity_20260905.md). That historical 4,000/s
run had 2,321 dropped iterations and co-located resource contention, without isolated
attribution. This new series changes warmup, preparation size and source revision;
it does not turn that old observation into a proven service ceiling or a same-load
service optimization. The clean optimization comparison to present is the separate
[40/s order-cadence result](seckill_sustained_orders_20260906.md).

Labels:
- `reject_vm8_r3000_16cb211_20260906T080857Z`
- `reject_vm8_r4000_16cb211_20260906T081817Z`

## Raw output

- [3,000/s complete bundle](seckill-rejection-vm3k-20260906.tar.gz): 30 original files, 866,372,705 uncompressed bytes.
- [4,000/s complete bundle](seckill-rejection-vm4k-20260906.tar.gz): 30 original files, 1,082,840,290 uncompressed bytes.

The bundles retain full k6 JSON points, mixed summaries, separately calculated formal steps, console, setup, commands, CPU/memory observations and pre/post SQL/MQ/Redis. Full measured commit `16cb21154d1ae94c65fff56b8a96ea7f4514f924` remains in the outputs. Standalone raw copies remain in ignored storage; no anomalous sample was rewritten.
