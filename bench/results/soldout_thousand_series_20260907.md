<a id="售罄整千档固定负载确认"></a>

# Fixed-load sold-out confirmation at thousand-request increments

Full CityBuddy SHA: `1ab85a460cea188c3f2f6770bdb1fa0c52e57922`. Java business source matches `76c293178923bf78e747ab1ba9590e6348108ad8`. make repackaged the JAR for this run; actual digests are in each setup artifact, with no claim of identical binary digests. M4, Docker 8 CPUs / 14,638,391,296 bytes, Commerce 4 CPUs; k6 and services share the VM network. Each fixed point uses 32 independent activities, 320 actual preparation orders, another 16384 buyers and 500 fixed VUs. Warm-up is 1000/s for 30 seconds, followed by a 5-second gap and a 30-second measured window; each request uses a new idempotency key. Natural cancellation and TTL-recovery records are retained before a new point; unrelated data is not cleared.

The target was to narrow the last fully served point and first dropped-iteration point to adjacent 1000/s increments under a fixed generator configuration. Expected 409/EXHAUSTED/replay=false responses are correct business outcomes. Latency boundaries are separate; doubling p99 is not directly treated as throughput overload. A generator boundary is not Commerce production capacity.

## 14000/s

Label `b14000r1_1ab85a4_20260907T174705Z`. Measured HTTP interval: 17:49:39.032390970–17:50:09.030640178 UTC. All 415789 requests returned 409/EXHAUSTED/replay=false, with 4218 drops, zero HTTP failures, interruptions or negative durations. Completed throughput per planned 30 seconds was 13859.6/s; linear p99 45.597811 ms, maximum 113.013584 ms. Warm-up is separate: 30000 requests, p99 6.368338 ms. The seven extra scheduled opportunities at arrival-rate boundaries remain in the actual counts; completions and drops are not adjusted.

Raw drops were distributed as 17:49:39=1431, :46=331, :49=305, :51=38, :55=741, :56=340, :57=21, and 17:50:01=11, :06=300, :07=519, :08=181, totaling 4218. They were not limited to the step transition. k6 reported insufficient 500 VUs. The six measured resource samples peak at Commerce 273.62%, k6 248.47%, Redis 145.62%, MySQL 3.01% and Broker 110.76%; peaks need not be simultaneous. First/last in-window Commerce throttle counts are both 10/550651 microseconds, with no evidence of sustained four-CPU saturation. Redis container CPU includes background work; cumulative AOF rewrites increased 17 → 18, so 145.62% does not prove main-thread saturation. Insufficient VUs and distributed drops are established; these samples alone do not identify a unique underlying limit.

Before/after SQL both show 320 ORDERED/ADMITTED and UNPAID/SENT, inventory 1999680, 320 ledger entries, zero binding errors and no additional orders. Redis used_memory was 75339968 → 817961768, up 742621800 bytes, approximately 1665.86 bytes per HTTP request across 445789 total requests. expired_keys remained 3505788. AOF rewrite was zero at both endpoints, but cumulative counts show rewriting occurred in between. Full SQL and resource artifacts include actual timestamps; CPU after raw metric output is outside the input window.

14k was not a fully served passing point. The next proposed midpoint was 12k; natural recovery and further results were pending at that time, and adjacent-thousand convergence was not complete.

14k natural recovery: 320 orders were CANCELLED by 18:07:00. Full SQL at 18:07:50 confirmed inventory 2000000, quota 10 per activity, 320 entries in each ledger, zero errors or incomplete work, and both MQ groups and handoff zero. Originals: final_1788804470045257000, mq_1788804511703441000 and cooldown_1788804420004805000. [Lossless raw archive](soldout-b14000r1-20260907.tar.gz).

Route revision: binary search toward 12k was paused. The generator would first be calibrated at 14k with other settings unchanged, increasing preallocated VUs from 500 to 1000. Insufficient 500 VUs do not directly establish a service-capacity boundary. The new result is separate and is not claimed as a business-performance optimization.
