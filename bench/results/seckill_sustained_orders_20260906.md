# Five-minute seckill order production — 2026-09-06

Baseline measured CityBuddy: `1993c281c81e1ea34708773eea3a2825657bef84`.
MacBook Pro M4, 10 host cores / 24 GB; Docker 8 CPU / 14,638,391,296 bytes;
Commerce limited to 4 CPU; k6 runs in the same Docker VM. One activity, one product,
quantity one, new fixture and topics at each rate. ShopMate, builds and model batches
were stopped during measurement. All times below are UTC.

The [original registration](seckill_sustained_registration_20260906.md) defines a
300-second input window and independent admission/order/timeout-dispatch observations.
The subsequent [cadence comparison registration](seckill_cadence_comparison_registration_20260906.md)
records the next adjustment before its input. Results from that adjustment are pending;
this report does not call the current work complete or state a maximum capacity.

## Baseline result

| Offered for 300s | HTTP admitted / final orders | SQL order-wait p50 / p99 | Post-startup order rate | Sampled pending queue | Interpretation |
|---|---:|---:|---:|---|---|
| 20/s | 6,001 / 6,001 | 293.070 / 558.988ms | 19.94–20.07/s | max 11, no growing trend | Bounded at this workload |
| 40/s | 12,000 / 12,000 | 48.081 / 134.368s | 29.33–29.58/s | minute endpoints 684→1,325→1,949→2,588→3,229 | Input outpaces production |

Both HTTP windows had zero dropped/interrupted iterations and all decisions were
ADMITTED/201. The extra 20/s iteration is k6's duration-boundary scheduling; it also
completed normally, using the registered 50-user/stock margin. HTTP latency is separate
from the SQL reservation-to-order wait; complete HTTP admission does not imply bounded
asynchronous work. The 40/s point is not rescued by its eventual successful drain.

At 20/s, the final four observed minute queue changes were +3, -4, -1, +7; the maximum
sampled oldest pending age was 0.6031s. Reservation-minute cohort p99 stayed around
555–558ms after startup. At 40/s, the sampled oldest pending age at the five minute
endpoints grew from 26.06s to 112.26s. All startup data and all drain data remain in the
raw bundle; the first minute was not deleted to improve the result.

## Clearance and authoritative checks

At 20/s, the last HTTP completed at 06:34:52.164532554. SQL first confirmed all 6,001
orders and timeout messages dispatched at 06:34:56.836415: within five seconds after
input ended. This is a five-second observation bound, not an exact ACK duration.

At 40/s, the last HTTP-to-first-clear observation interval was (104.602,109.591]
seconds for orders, and (109.591,114.594] seconds for orders plus timeout dispatch.
The finite batch took 409.1132 seconds from its first reservation to last order;
12,000/409.1132 ≈29.33/s is a batch average, not a separately established stable limit.

Both final SQL audits found no binding mismatch, admitted reservation missing an order,
duplicate order user/reservation, incorrect or extra ledger movement, negative wait,
cancelled/unfulfilled order or order created after its unpaid deadline. Remaining
stock was 49/50 respectively; adding the 6,001/12,000 ordered units reconstructs the
6,050/12,050 initial stock. Both inventory and quota ledger uses equal ordered units.

Explicit transaction-topic consumerProgress queries found Diff=0 and Inflight=0 in
all four queues. Redis global/per-activity handoffs were zero; retained terminal
intent keys had finite TTL and were not deleted. The initial 20/s group-only MQ query
failed because this SimpleConsumer did not expose the legacy retry topic; that raw
failure is retained beside the successful query with an explicit business topic.
Later MQ snapshots do not retroactively give an exact earlier ACK-clearance time.

## Why adjust cadence before refining the old bracket

The measured order worker receives at most 16 messages and then waits 500ms after
finishing the batch. That configuration cannot exceed 32/s even if processing costs
nothing. The timeout dispatcher similarly handles at most 32 then waits 1,000ms.
The 40/s point produced approximately 29/s, close to the order stage's cadence ceiling.

During roughly 300.123s around that HTTP window, Commerce cgroup usage increased by
49.149566 CPU seconds (about 16.38% of one CPU on average), with no additional quota
throttling. k6's highest sampled CPU was 4.42%. These observations support a cadence
bottleneck rather than exhaustion of Commerce's four-CPU allowance. They do not claim
to uniquely decompose SQL, ACK, scheduling or Broker time. A same-load comparison is
required before attributing an improvement to the registered adjustment.

## Raw sources

[Lossless baseline archive](seckill-sustained-before-20260906.tar.gz) contains 38 original
files (139,306,621 uncompressed bytes), including complete k6 point streams, summaries,
console, setup and token-refresh timestamps, five-second SQL, final SQL, cgroups,
Docker/host observations and MQ/Redis output. Every measured file records the full
baseline commit. Standalone raw copies are retained in ignored local storage.

Labels:
- `ordersteady_1993c28_20260906T062129Z_r20_s300`
- `ordersteady_1993c28_20260906T063632Z_r40_s300`

20/s tokens were refreshed through normal Auth before input because protocol review
had consumed time after setup; token contents were never logged. The earliest reservation was
persisted at 06:29:52.441254. 40/s used its freshly minted setup pool; the earliest reservation was 06:38:43.128402. This is no-positive-warmup
sustained measurement, not an exact comparison of cold-start ages across the two rates.
