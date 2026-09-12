<a id="售罄短阶梯粗探"></a>

# Coarse short-ladder sold-out probe

Full CityBuddy SHA: `1ab85a460cea188c3f2f6770bdb1fa0c52e57922`. Java business source matches `76c293178923bf78e747ab1ba9590e6348108ad8`. Label `bprobe_1ab85a4_20260907T172543Z`. M4, Docker 8 CPUs / 14,638,391,296 bytes, Commerce 4 CPUs; generator and services share the VM network. There are 32 activities, 320 actual preparation orders, another 16384 load buyers and a new key per request. Fixed 1000/s warm-up for 30 seconds, a 5-second gap, then planned 30-second stages at 9000/18000/36000, each with 500 VUs. Stages share state and are only used to bracket a range.

| Stage | Actual HTTP / business result | Drops | Raw p99 | Boundary |
|---|---|---|---|---|
| Warm-up 1000/s | 30001 responses of 409/EXHAUSTED | 0 | 7.641083 ms | Excluded from measured capacity |
| 9000/s | 270001 responses of 409/EXHAUSTED/replay=false | 0 | 11.577875 ms | Full 30 seconds |
| 18000/s | 99060 HTTP 409 responses; 99058 EXHAUSTED/replay=false tool-counter events | 8370 | 64.757515 ms | Threshold aborted the run after about 6 seconds |
| 36000/s | Not executed | — | — | Empty metrics are not zero-latency success |

The difference of two between HTTP and custom business counts occurs at the abort boundary; missing counts are not invented. The console records 265 interrupted iterations. The 18000-stage native drop threshold was 5400, or 1% of its nominal 540000 requests. Actual drops of 8370 triggered overall exit 99, not p99 exceeding 1 second. k6 reported insufficient 500 VUs at 17:28:56. Both stages had zero negative durations. The original `steps.txt` divides achieved/s by the full planned 30 seconds; its 3302.0 value for the aborted 18000 stage is only normalized to the planned window, not actual running throughput or system capacity. The summary's overall rate also includes output-writing time and is not used for capacity conclusions.

9000 HTTP window: 17:28:20.917181587–17:28:50.913586128 UTC. 18000 window: 17:28:55.920048797–17:29:01.897160134 UTC. Raw JSON writing continued to 17:32:36 and the runner ended at 17:32:40; post-processing CPU is outside the input window. Six samples inside the 9000 window show Commerce 185.08%, k6 162.17%, Redis 45.97% and Broker 115.38%. Only one complete sample is inside the 18000 window: Commerce 127.35%, k6 155.94%, Redis 124.55%. Near the boundary at 17:28:55, k6 was 250.51%, but sampling does not cover the whole stage; these points do not establish a unique cause. Commerce in-window throttle counts are all 8/243622 microseconds, without sustained four-CPU saturation. The clear observations are insufficient 500 VUs and drops, not an application peak.

Before/after SQL both show 320 ORDERED/ADMITTED and UNPAID/SENT, inventory 1999680, 320 ledger entries, zero binding errors and no orders created by rejected requests. Redis used_memory was 75238824 → 743496344, an increase of 668257520 bytes, approximately 1674.57 bytes/request across 399062 HTTP requests. AOF rewrite was zero at both endpoints and expired_keys remained 2306904; two snapshots cannot exclude an intervening rewrite. Natural cancellation and TTL recovery were recorded separately afterward.

Further work was limited to bounded fixed-load confirmation with independent fresh fixtures. These three stages are not presented as independent formal repeats.

Natural recovery: SQL at 17:44:15 confirmed all 320 orders CANCELLED, inventory 2000000, quota 10 per activity, 320 entries in each ledger and zero errors; both MQ groups and handoff were zero. At 17:46:00 Redis used_memory was 75385832 bytes, expired_keys 3504567 and AOF rewrite/scheduled zero. Originals: final_1788803055491476000, mq_1788803061426884000 and cooldown_1788803160000310000.

[Lossless raw archive](soldout-probe-20260907.tar.gz). The subsequent target precision became adjacent 1000/s points, beginning with a 14k midpoint; the plan would not stop early at a 20–25% gap.
