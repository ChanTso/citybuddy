# Seckill consumer cadence comparison — registered 2026-09-06

Baseline CityBuddy: `1993c281c81e1ea34708773eea3a2825657bef84`.
Baseline labels: `ordersteady_1993c28_20260906T062129Z_r20_s300` and
`ordersteady_1993c28_20260906T063632Z_r40_s300`.
The comparison revision will be committed before input and recorded in every setup,
raw point stream and result. This document does not claim an unrun after result.

## Decision from the baseline

20/s for 300 seconds completed 6,001 unique orders with no dropped iterations and
SQL wait p99 558.988ms; its sampled queue remained bounded. At 40/s for 300 seconds,
12,000 arrivals were admitted but production remained about 29 orders/s, leaving
3,229 pending orders at the last sampled input boundary. All eventually completed;
SQL wait p99 was 134,367.726ms. SQL binding, ledger and stock checks passed, and the
explicit transaction-topic MQ query found zero Diff/Inflight. Complete counts and
minute windows are retained in the raw files, including startup and draining.

The measured setup limits each order receive to 16 messages followed by 500ms fixed
delay; even zero SQL/ACK time would cap that configuration below 32 orders/s. The
application fallback had an even longer 1,000ms delay. Timeout dispatch similarly
handles at most 32 orders followed by 1,000ms delay. These are serial stage limits,
not a lack of four CPU cores. Refining the old 20–40/s bracket adds less value than
checking a small adjustment against exactly the same 40/s input.

## One cadence adjustment across both serial stages

Set order-worker and timeout-dispatch fixed delays to 50ms in application defaults
and benchmark setup. Keep receive batches 16/32, long-poll await, invisible duration,
ACK-after-transaction behavior, serial processing, scheduler assignments, transactions,
recovery rules, database/MQ topology and Commerce's four-CPU limit unchanged.
This comparison changes both batch-after delays, not just the order delay.

The healthy-loop benefit has a tradeoff: idle dispatch can query up to about 20 times
per second instead of once, and immediate repeated publish failures can be retried
more often because the existing FAILED queue has no time-based backoff. Fixed delay
still prevents re-entry. Existing failure/replay/activation integration tests remain
mandatory; a short idle CPU/SQL observation accompanies the new configuration. No new
retry platform or consumer concurrency is included in this change.

Benchmark request keys also gain an explicit per-label prefix so a repeated rate
cannot replay a retained result from a preceding fixture. Standalone JS retains its
old default prefix. The decision metric additionally records the existing replay
boolean. Both series exercise fresh requests; these measurement changes do not
modify service decision logic and are disclosed in the comparison.

## Load sequence and interpretation

- First repeat 40/s for 300s, one activity/product, stock/quota/users 12,050; use the
  same original k6 script/runner, VM8/14GB, Commerce4, SQL cadence and no positive warmup.
- If bounded and correct, test 80/s, then 160/s. Use a fresh fixture and unique prefix
  per point, each with rate*300+50 users/stock/quota. If 160/s remains bounded, one
  200/s point is useful; do not continue doubling to chase a larger peak.
- If a point clearly accumulates work, at most one intermediate point may narrow the
  practical bracket. Report the last demonstrated rate and observed overloaded rate,
  not an exact integer maximum. Persistent plateau ambiguity is reported and examined.
- Retain all 300s. Interpret startup separately and the following four minutes using
  the frozen drift/oldest-pending rules in seckill_sustained_registration_20260906.md.
  Record timeout dispatch separately: sustained order production is insufficient if
  SENT production falls behind. A larger stationary queue is not automatically failure.
- Stop input for any unexpected business/process/memory failure. After the first minute,
  stop early if current backlog already exceeds 240 seconds of the last complete
  minute's observed production, or if the nearest payment deadline has less than that
  estimated drain time plus 60 seconds remaining. This protects the unpaid workload;
  such a shortened point remains an overload observation, not a completed five-minute run.
- Retain up to 300s post-input observation. A non-drained point is incomplete; do not
  cancel, pay or delete its data/messages to force a success. Preserve it until resolved.

Final SQL must prove all admitted reservations uniquely ordered, matching inventory
and quota ledger deltas and no deadline crossover. Separately observe order clearance,
timeout dispatch clearance, Redis handoffs and actual MQ Diff/Inflight. Five-second
sampling only bounds the first observed clear time; it does not measure exact ACK time.

After this comparison, continue the local sold-out 3,000/4,000 diagnosis with fixed
warmup. No cloud action is authorized by this registration; unresolved local attribution
is explained to the owner before considering cloud work.
