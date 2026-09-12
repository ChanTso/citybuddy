<a id="售罄发生器校准"></a>

# Sold-out load-generator calibration

Services retain Commerce 4 CPUs, Docker 8 CPUs / 14,638,391,296 bytes, the same M4 VM network, a fixed k6 image, 32 activities, 320 actual preparation orders and another 16384 buyers. Each point has a fresh fixture, a 1000/s warm-up for 30 seconds, a 5-second gap, 14000/s for 30 measured seconds and a new key per request. Generator-configuration changes are not business-code optimization gains.

<a id="5001000预分配vu"></a>

## Preallocated VUs: 500 → 1000

Earlier 500-VU CityBuddy point `1ab85a460cea188c3f2f6770bdb1fa0c52e57922`: 415789 correct rejections, 4218 drops, p99 45.597811 ms; see [soldout_thousand_series](soldout_thousand_series_20260907.md).

1000-VU point: CityBuddy `c652870c545e87d82d832cd0fd4d2ad92582a51d`, label `bv1000_c652870_20260907T181205Z`. This commit only adds recorded preallocated/max-VU parameters; Java business source is unchanged. Measured HTTP interval: 18:14:31.217947925–18:15:01.217950925 UTC. There were 415331 responses of 409/EXHAUSTED/replay=false, 4685 drops, and zero HTTP failures, interruptions or negative durations. Linear p99 was 85.869041 ms, maximum 217.4 ms. Warm-up: 30000 requests separately, p99 6.618343 ms. k6 still reported insufficient 1000 VUs; doubling VUs did not resolve the generator problem.

Six measured resource samples peak at Commerce 277.57%, k6 259.72%, Redis 122.61%, Broker 110.01% and MySQL 3.35%. First/last in-window Commerce throttle counts are both 7/382333 microseconds, without evidence of sustained four-CPU saturation. Full SQL still shows only 320 preparation orders and creation ledger entries, inventory 1999680, and zero binding errors; rejected requests created no orders. Redis used_memory was 75438528 → 817329568 bytes (up 741891040), with expired_keys unchanged at 4844115. Cumulative AOF rewrites were 19 → 22, versus 17 → 18 at the old 500-VU point. Equal durability settings do not imply identical background-rewrite timing; the full latency difference cannot be attributed to VUs.

Conclusion at this point: increasing VUs did not eliminate drops, so VUs would not be increased without bound. The next diagnostic retained 1000 VUs and the same 14k business load to isolate the effect of writing every metric point. Native summaries, business outcomes and SQL remained. This did not disable correctness checks or assume I/O was already the root cause. Natural recovery and the output diagnostic were recorded separately.

1000-VU recovery: full SQL at 18:29:35 confirmed 320 cancellations, inventory 2000000, quota 10 per activity, 320 entries in each ledger, zero errors and MQ/handoff zero. At 18:32:00 Redis used_memory was 108223768 bytes, expired_keys 6177627, AOF rewrite/scheduled zero. A physical-reclamation tail remained; byte-for-byte return to the initial state is not claimed. Originals: final_1788805775007157000, mq_1788805783600514000 and cooldown_1788805920003388000. [Lossless raw archive](soldout-vu1000-20260907.tar.gz).

<a id="原生汇总输出对照"></a>

## Native-summary output comparison

CityBuddy `3ed99bc580ec7c72a557e72e1d8339a42cc3781a`, label `bsum_3ed99bc_20260907T183328Z`. The point retains 1000 VUs, 14000/s for 30 measured seconds and the same business topology. It uses native k6 per-scenario summaries without writing the full point-by-point JSON; HTTP requests and business parsing are unchanged. Empty threshold lists added by the commit retain native per-scenario aggregates; they are not pass criteria.

Native measured counts: 418911 requests, 418911 complete iterations, 418911 EXHAUSTED/replay=false, zero HTTP failures and dropped_iterations 1111. The full run completed 448912 iterations with zero interruptions, including 30001 warm-up requests. Native console measured HTTP p99 is 64.54 ms at the tool's displayed precision; summary JSON only supplies p90/p95, so no finer precision is invented. Measured duration minimum is 0.01625 ms, maximum 155.034166 ms. Global http_req_receiving minimum is -0.625473 ms, confirming an anomalous timing component. Without point records, its count, time and stage cannot be determined. This is not a capacity result with entirely clean timing.

The native summary is 12371 bytes. Removing point output reduced drops from 4685 to 1111, but did not achieve zero drops. A single comparison with different background activity cannot prove file output was the only cause, nor is it a Java-code improvement. k6 still reported insufficient 1000 VUs. Whole-run resource peaks, including warm-up rather than an exact measured window, were Commerce 268.53%, k6 222.80%, Redis 84.37% and Broker 111.05%. SQL still confirmed only 320 preparation orders, inventory 1999680 and zero business-binding errors. Redis used_memory was 75539136 → 823160776 bytes, expired_keys remained 6181068 and AOF rewrites increased 22 → 24.

Conclusion at the time: neither doubling VUs nor reducing output established a valid 14k zero-drop control point, so 14k cannot be reported as Commerce's capacity ceiling. Binary search stopped at this generator boundary. If further work proceeded, the next step would be a new topology using host k6 through local published ports while retaining Commerce 4 CPUs, recorded separately from the VM series. Adjacent 1000/s points would only be pursued after generator headroom and timing were valid. Physical cloud isolation would require a separate explanation, not automatic deployment.

Originals: `k6_bsum_3ed99bc_20260907T183328Z_{summary.json,console.txt,cpu.txt}`, and same-label setup, before/after SQL and preparation records. Natural cancellation recovery is recorded after measurement and is not part of the input window.

Natural recovery: full SQL at 20:11:28 UTC confirmed 320 CANCELLED orders, inventory 2000000, quota 10 for each of 32 activities, 320 creation and 320 cancellation ledger entries, and zero errors or unfinished states. Both MQ groups then showed Diff/Inflight zero, and handoff was zero. This is the final verification time, not the earliest measured drain time. Full originals are in the [summary-mode raw archive](soldout-summary-20260907.tar.gz).

The final route stopped sold-out limit exploration and did not move the generator to the host. The fixed 6000/s work point is retained; this calibration explains measurement limits, not a service-capacity ceiling.
