# Five-minute seckill order production — 2026-09-06

Baseline measured CityBuddy: `1993c281c81e1ea34708773eea3a2825657bef84`.
MacBook Pro M4, 10 host cores / 24 GB; Docker 8 CPU / 14,638,391,296 bytes;
Commerce limited to 4 CPU; k6 runs in the same Docker VM. One activity, one product,
quantity one, new fixture and topics at each rate. ShopMate, builds and model batches
were stopped during measurement. All times below are UTC.

The [original registration](seckill_sustained_registration_20260906.md) defines a
300-second input window and independent admission/order/timeout-dispatch observations.
The subsequent [cadence comparison registration](seckill_cadence_comparison_registration_20260906.md)
records the adjustment before its input. The completed same-load comparison and higher
input rates follow below; this series does not establish an exact maximum capacity.

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

Baseline measured CityBuddy: `1993c281c81e1ea34708773eea3a2825657bef84`.
Cadence-adjusted measured CityBuddy: `16cb21154d1ae94c65fff56b8a96ea7f4514f924`.

## Same-load cadence comparison

The registered change reduced order-batch fixed delay from 500ms to 50ms and timeout-dispatch fixed delay from 1,000ms to 50ms. Receive/dispatch batches remained 16/32, with the same serial processing, long polling, ACK behavior, dedicated consumer schedulers, business transactions, Broker, topology and four-CPU Commerce limit. This is a joint change to two serial-stage delays, not an isolated measurement of either delay.

At the same 40/s for 300 seconds, 12,000 unique admissions all became orders. All HTTP requests returned 201/ADMITTED, no iterations were dropped, and the newly recorded replay tag was false for all 12,000 decisions.

| Same 40/s input | Before | After |
|---|---:|---:|
| Post-startup minute production | 29.33–29.58 orders/s | 39.99–40.01 orders/s |
| Five minute-end pending counts | 684 / 1,325 / 1,949 / 2,588 / 3,229 | 2 / 2 / 2 / 2 / 2 |
| Maximum pending count observed every five seconds | 3,229 | 3 |
| Maximum pending timeout-dispatch count observed every five seconds | 32 | 3 |
| SQL reservation-to-order wait p50 | 48,080.999ms | 36.705ms |
| SQL reservation-to-order wait p99 | 134,367.726ms | 72.234ms |
| HTTP p99 | 13.398ms | 15.117ms |
| Order and timeout-dispatch clearance after last HTTP | (109.591,114.594]s | First post-input sample, within five seconds |

The final four minute admission and order increments after adjustment matched exactly: 2,401 / 2,399 / 2,400 / 2,400. SQL reservation-minute p99 after startup stayed at 69.874 / 70.148 / 69.416 / 71.441ms. The result supports bounded asynchronous production at this workload; it does not imply an HTTP throughput improvement or establish the adjusted capacity limit.

Final SQL found no binding, duplicate, missing-order, inventory/quota ledger, unexpected movement or deadline violation. There were no negative waits, cancellations or unfulfilled orders. All 12,000 orders were UNPAID/SENT; remaining stock was 50 of the initial 12,050. The explicit MQ transaction-topic snapshot found Diff=0 and Inflight=0 in all four queues; Redis handoffs were zero with finite terminal-intent TTLs.

The last HTTP finished at 07:09:06.437704296. The preceding SQL snapshot was 0.289 seconds earlier; the first post-input snapshot at 07:09:11.150739 found all orders and timeout dispatch complete. “Within five seconds” is an observation bound, not an exact 4.713-second or 7.9-millisecond drain time. A future delayed timeout is not counted as consumed merely because its dispatch is complete.

Benchmark request keys gained a per-label prefix and the existing replay field gained a metric tag, so repeated rates use disjoint retained-intent namespaces. Both series exercised new requests; this change did not alter service decision logic. Setup-to-first-HTTP idle time was about 91.146 seconds before and 130.722 seconds after, the latter including the registered idle observation. Sampler lead-in was about 5.595 / 5.640 seconds. No positive business warmup, SIGQUIT or profiler was added; complete startup data remain in the result rather than being removed.

## Idle polling cost

Before the adjusted 40/s input, a 60.112307-second SQL observation recorded MySQL global Com_select increasing by 1,209 (about 20.1/s) and Questions by 1,271 (about 21.1/s). Slow_queries remained zero; Threads_running was two at both endpoints. These are aggregate instance counters, including other connections and observation queries, not a per-dispatch query attribution or a measured before/after SQL increase.

Twelve idle Docker samples showed Commerce at 2.04–7.96% of one CPU and MySQL at 1.18–4.28%. Commerce's integrable first-to-last cgroup samples covered 54.997835 seconds and 1.601296 CPU seconds, about 2.91% of one CPU; this is a roughly 55-second CPU window, not an invented complete 60-second integration. Throttling and OOM counters did not increase. Broker background activity remained present and unchanged.

The faster healthy loop therefore retains a measurable idle-query cost. It also retains the registered limitation that immediate publish failures can retry more frequently without a timed failure backoff. The normal-load and idle observations do not claim to measure that failure scenario.

## Further offered rates and their limits

| Adjusted input for 300s | HTTP admissions / final orders | Five-minute-end pending orders | Sampled max orders / timeout dispatch pending | Interpretation |
|---|---:|---|---:|---|
| 40/s | 12,000 / 12,000 | 2 / 2 / 2 / 2 / 2 | 3 / 3 | Bounded; same-load comparison above |
| 80/s | 24,001 / 24,001 | 2 / 3 / 3 / 5 / 4 | 8 / 5 | Counts remain bounded; wall-clock anomaly compromises SQL latency evidence |
| 160/s, original observer | 48,000 / 48,000 | 12 / 17 / 18 / 1,545 / 4,527 | 4,527 / 16 | Increasing order backlog after the third minute; later observer probe below |
| 160/s, no periodic ledger aggregate | 48,001 / 48,001 | 21 / 15 / 59 / 19 / 21 | 252 / 15 | Later production follows input; original clean-gate numerical exception remains |
| 200/s, no periodic ledger aggregate | 60,001 / 60,001 | 2,464 / 4,356 / 6,183 / 8,368 / 10,644 | 10,644 / 16 | Sustained accumulation despite eventual successful drain |

Every request in these adjusted runs returned 201/ADMITTED with replay=false, with no dropped iterations. Final authoritative SQL found each admitted reservation uniquely ordered and matched its stock/quota ledger use. MQ Diff/Inflight and Redis handoffs were zero at the later checks. These correctness statements do not turn an accumulating queue into stable capacity or make every timestamp valid.

### 80/s wall-clock anomaly

The 80/s final SQL contained one negative reservation-to-order interval of -305.595ms. Its unmodified SQL p50/p99 were 42.316/103.866ms; these values remain in the raw result but are not treated as clean latency acceptance. The negative interval is not removed to produce a better-looking distribution.

The k6 point stream also reverses wall-clock time around 07:15:55: successive HTTP metric records at lines 145022 and 145036 are timestamped 07:15:55.390915847 and 07:15:55.045985333, a 344.930514ms reversal. The decisions/iterations records show the same transition. Host sampler 26→27 spans only 4.646691 wall-clock seconds, and the corresponding SQL timestamps span 4.640594 seconds instead of roughly five. A separately retained host `timed` record confirms `settimeofday` adjustment of -0.357229233 seconds. These observations agree on a clock step; they are not a business double-write or missing-order result.

The 24,001 unique admissions, matching final orders and bounded count snapshots remain usable observations. Wall-clock-derived exact rates, SQL delays and derived ages carry the anomaly. The first post-input SQL sample found order and dispatch clearance within five seconds, but no subsecond clearance claim is made from these clocks.

### 160/s original-observer result

At 160/s, the post-startup admission increments were 9,600 / 9,599 / 9,607 / 9,596, while order increments were 9,595 / 9,598 / 8,080 / 6,614. The last two monotonic-timed intervals produced about 134.65 and 110.24 orders/s. Oldest sampled pending age rose to 36.1524 seconds at the final input boundary; timeout dispatch still followed actual order production with small pending counts.

Final SQL wait p50/p99 were 151.495ms/36.808084s, with zero negative waits. The last two reservation-minute cohort p99 values were 18.560626 and 38.021664 seconds. Orders and timeout dispatch first cleared between 29.230 and 34.194 seconds after the last HTTP. The 144.4570 orders/s complete-batch average includes draining and is not a sustainable limit.

The observer for this run added host monotonic timestamps beside wall time without changing the load or SQL. Adjacent SQL-observer samples showed no wall-minus-monotonic jump above 10ms; the large step seen at 80/s was not reproduced. Small k6 record-time reversals alone are not labelled the same clock fault.

During roughly the last two load minutes, Commerce cgroup CPU averaged only about 26.10% and 24.48% of one CPU, with no new throttling. Earlier startup had six throttle events totalling 230,016 microseconds. The startup events do not uniquely explain the later decline; neither do sampled CPU values uniquely identify SQL, MQ or scheduling as its cause.

### Diagnostic probe: changing observation cost

The probe kept the same adjusted application SHA `16cb21154d1ae94c65fff56b8a96ea7f4514f924`, 160/s × 300s load, one-activity fixture, topology and other five-second SQL observations. It removed only the periodic ledger aggregate, while retaining complete ledger/invariant SQL before and after the run. Its label is `orderprobe_16cb211_20260906T074822Z_r160_nolgr`.

All 48,001 unique admissions became orders, with no SQL correctness violation or negative wait. The last two observed monotonic minute intervals produced 9,638 and 9,600 orders, about 160.64 and 159.97/s; the original observer's corresponding increments were 8,080 and 6,614. The late sustained decline did not recur. Final reservation-minute p99 after startup was 246.371 / 484.703 / 383.906 / 415.534ms, while the complete-run p50/p99 remained 78.590/2,827.065ms. The complete p99 retains the first minute's startup tail, whose cohort p99 was 3,815.816ms; startup was not removed.

The count observations do not satisfy every original clean-gate number: one minute endpoint had Q=59, above the registered 32, and two-minute growth from 21 to 59 was +38. That increase then recovered to 19 and 21, with no late sustained growth. Business success, recovery of the queue, and full numerical clean-gate qualification are separate statements. The original 32-request tolerance is not silently changed into a new threshold, nor treated as a service hard limit.

The last HTTP completed at 07:55:36.856303338. The preceding SQL sample was earlier than that HTTP; the first subsequent sample at 07:55:41.142352 found all orders and timeout dispatch complete. This is confirmation within five seconds, not exact 4.286-second clearance. Final stock was 49 of 48,050; inventory/quota ledger use was 48,001, with no duplicate or missing order. All MQ Diff/Inflight and Redis handoffs were zero at their subsequent checks.

Probe status snapshots span 07:50:31.034348–07:56:31.455109, or 360.420761 seconds including pre/post-input observation. The periodic ledger digest remained exactly 269 executions, 83.1185 cumulative statement seconds and 5,311,728 rows examined at both endpoints. Its increment was zero. Meanwhile the progress-summary digest added 73 executions and 5,953,642 rows examined, oldest-pending lookup added 72 executions and 3,872,032 rows, and the other periodic order/reservation aggregates each added 73 executions. This confirms that the remaining observation work was still present.

Over the same snapshot interval, global InnoDB buffer-pool reads increased by 96,626, logical read requests by 54,926,358, and buffer-pool wait-free by zero. The starting wait-free count of 6,054 is historical, not a probe event count. Global row-lock time/waits increased by 52,743ms/32,141. These are instance-wide interval deltas, not per-query CPU or an exact 300-second workload profile. Earlier cumulative totals cannot be used as the original 160/s point's own deltas, so no fabricated percentage reduction in physical reads is reported.

The same-application probe supports a material effect from periodic measurement cost on the original 160/s observation. It is not a service-code optimization, nor a unique proof of buffer-pool pollution, disk I/O or any other single internal mechanism. Cache/time state cannot be reproduced perfectly with a new fixture and one repeat. Both observer versions and all original results remain retained; the original point is not silently replaced.

### 200/s with the lighter observer

The final offered point used the same application and lighter observer at 200/s for 300 seconds, with 60,050 users/stock/quota. All 60,001 HTTP requests were fresh admissions and eventually became correct orders. Nevertheless, every post-startup minute added backlog: +1,892 / +1,827 / +2,185 / +2,276. The last two monotonic-timed minute intervals produced about 163.61 and 161.99 orders/s, below the offered input. Oldest pending age at the five minute endpoints rose from 14.8672 to 66.9188 seconds; the full sampled maximum was 91.5583 seconds. This is an overloaded observation, not a larger stationary queue.

Final SQL found no binding, duplicate, missing-order, inventory/quota ledger, unexpected movement or deadline violation, with zero negative waits. The unmodified SQL wait p50/p99 were 30.823643/87.229040 seconds and the maximum was 92.019968 seconds. These values carry a clock qualification: sampler 43→44 advanced 5.114118 wall-clock seconds over 5.002656042 monotonic seconds, a +0.111461958-second step. k6 also recorded a 117.896902ms adjacent timestamp gap near 08:03:55. The raw clock behavior is retained; no submillisecond accuracy is claimed. A roughly 0.11-second step cannot explain thousands of increasing pending orders and waits of tens of seconds.

The last HTTP completed at 08:05:18.917458636. SQL at 08:06:33.042372 still had 416 pending orders and 10 pending timeout dispatches; at 08:06:38.050537 both were zero. Order and dispatch clearance were therefore observed in (74.125,79.133] seconds after the final HTTP. Later explicit MQ checks had zero Diff/Inflight and Redis handoffs were zero. The 159.1623 orders/s complete-batch average includes draining and is not a measured stable limit.

Commerce's roughly 300.555497-second cgroup window used 118.645563 CPU seconds, about 39.48% of one CPU on average. The first minute included seven throttle events totalling 532,501 microseconds; subsequent windows had no new throttling. Last-two-minute average CPU was about 31.67% and 30.74% of one CPU. The isolated startup Docker sample near 394% is not evidence that the four-CPU quota continuously limited the later run. These observations do not uniquely decompose per-batch SQL, ACK, scheduling and storage costs.

## What this local series establishes

The strongest same-load engineering result remains the 40/s cadence comparison: bounded production and SQL p99 134.368s→72.234ms with correctness preserved. The higher-rate observations add boundaries, not an exact integer capacity claim.

At 160/s with the lighter observer, 48,001 fresh requests offered over five minutes all became correct orders, later production followed input, and the first post-input five-second snapshot confirmed clearance. The Q=59 and +38 two-minute drift exceptions remain attached to that result. At 200/s under that observer, work accumulated continuously. It would be inaccurate to summarize this as “clean stable capacity is exactly 160/s and the limit is 200/s,” or to use a complete-batch average to fill the gap.

No further midpoint or peak-seeking order measurements are added. A résumé can use the clean cadence comparison and, if space permits, the explicit 160/s five-minute input/completion fact with its observation boundary. Sold-out rejection throughput is a separate workload and must not be presented as positive order-production throughput.


## Adjusted-series raw archive

[Complete adjusted raw output](seckill-sustained-after-20260906.tar.gz) contains 99 files (1,477,394,894 uncompressed bytes), including all five full k6 point streams, setup, SQL, resource, clock and MQ/Redis observations. Each measured file records `16cb21154d1ae94c65fff56b8a96ea7f4514f924`. Original standalone files are retained in ignored local storage.

Labels:

- `ordersteady_16cb211_20260906T070117Z_r40_s300`
- `ordersteady_16cb211_20260906T071124Z_r80_s300`
- `ordersteady_16cb211_20260906T072515Z_r160_s300`
- `orderprobe_16cb211_20260906T074822Z_r160_nolgr`
- `ordersteady_16cb211_20260906T075754Z_r200_nolgr`
