<a id="固定预热的本机售罄系列"></a>

# Local sold-out series with fixed warm-up

<a id="最终采用范围"></a>

## Final scope of use

Sold-out limit exploration has stopped. The final selected result is the verified 6000/s, 30-second independent fixed-load work point: a single run, not the maximum or a long-run steady-state claim. The 9000 timing anomaly and subsequent generator calibrations remain intact. References below to a “next point” describe decisions at the time, not pending work. The measured revision remains the full 76c2931 SHA below.

Measured CityBuddy: `76c293178923bf78e747ab1ba9590e6348108ad8`. M4, Docker 8 CPUs / 14 GB (14,638,391,296 bytes), Commerce 4 CPUs; generator and services share the Docker VM network. Each point uses 32 independent activities, with quota 10 each, exhausted through actual purchases by 320 separate preparation buyers. Load uses another 16384 buyers and a new idempotency key for every request. Fixed 500 VUs, 1000/s warm-up for 30 seconds, a 5-second gap, then the measured window. Login and fixture preparation are excluded. After each point, unpaid cancellation, the last rejection's 15-minute TTL and background reclamation are allowed to finish; the database is not cleared and AOF is not disabled.

<a id="4000s-控制点后续阶梯未完成"></a>

## 4000/s control point (subsequent ladder incomplete)

Label: `b4000r1_76c2931_20260907T160828Z`. Measured interval: 2026-09-07 16:10:48.172443–16:11:18.169443 UTC. All 120000 requests in 30 seconds returned 409/EXHAUSTED/replay=false; failures, drops, interruptions and negative HTTP durations were all zero. p99 3.075931 ms, p50 approximately 0.3 ms. The 30001 warm-up requests are separate, with p99 approximately 13.707 ms, and excluded from measured throughput. HTTP percentiles use linear interpolation over original k6 Points without removing measured samples.

Before/after SQL both show 320 ORDERED/ADMITTED and 320 UNPAID/SENT, inventory 1999680, 320 deductions and 320 ledger entries, with no orphans or ownership-binding mismatches. Measured rejections created no additional database orders, reservations or deductions. Transaction MQ Diff/Inflight is zero. Future unpaid cancellations are separate; this after-state is not the post-cancellation inventory result.

Six resource samples fall within the measured HTTP interval: peaks of Commerce 92.42%, k6 99.48%, Redis 35.64%, MySQL 2.51% and Broker 110.75%. The first and last in-window cgroup counts both show nr_throttled=8 and throttled_usec=287031, with no increase between them. Two samples that omit the window edges cannot establish exactly zero throttling over the whole window. The data does not show Commerce reaching its four-CPU limit or prove that the generator or a dependency reached its ceiling.

Redis used_memory rose from 79112856 to 327530088 bytes, an increase of 248417232 bytes, approximately 1656.10 bytes/request across 150001 warm-up and measured requests. RSS was 106713088 → 342904832. No AOF rewrite was active at either snapshot, but cumulative rewrites rose from 8 to 11, so the window cannot be described as rewrite-free. expired_keys did not increase; the three short-lived state entries remained within TTL. This increment only estimates memory for the next load point, not Redis long-run steady-state capacity.

Raw artifacts: same-label `k6_*`, `rejection_*_before/after.txt`, `rejection_prep_*.jsonl` and `seckill_*_setup.txt`. At this stage, a series conclusion awaited further points and repeats; this single point was not the sold-out limit.

Cooldown: all 320 orders were CANCELLED by 16:27:20. At 16:28:14, full SQL confirmed restored inventory 2000000, quota 10 per activity, 320 cancellation and 320 creation ledger entries, and zero binding errors or unfinished work; both MQ groups and handoff were zero. Native Redis snapshots at 16:27:20/16:29:02 showed used_memory 91939240/89133624 bytes, AOF rewrite/scheduled zero and expired_keys 765739/771002. RSS did not return to its initial value and a small amount of physical expiry reclamation continued; byte-for-byte equality with the starting state is not claimed. Originals: `recovery_b4000r1_76c2931_20260907T160828Z_final_1788798494430603000.txt`, `recovery_mq_b4000r1_76c2931_20260907T160828Z_1788798527244624000.txt`, and two same-label rejection_cooldown files.

<a id="6000s-点"></a>

## 6000/s point

Label: `b6000r1_76c2931_20260907T163035Z`. Measured HTTP interval: 16:32:49.955178–16:33:19.952130 UTC. All 180002 actual requests returned 409/EXHAUSTED/replay=false, with zero failures, drops, interruptions or negative HTTP durations; p99 4.915332 ms. The two extra boundary requests remain in the actual count. Warm-up is separate: 30001 requests, p99 13.797833 ms. Before/after SQL still show 320 ORDERED/ADMITTED and UNPAID/SENT, 320 stock deductions and ledger entries, inventory 1999680, zero binding errors and MQ Diff/Inflight zero.

The six measured resource samples peak at Commerce 143.13%, k6 129.74%, Redis 63.38%, MySQL 3.11% and Broker 115.29%; first/last in-window throttle counts are both 12/601956 microseconds. There is no evidence of sustained saturation of Commerce's 4 CPUs or insufficient generator scheduling. Redis used_memory was 87879400 → 423686072, up 335806672 bytes, approximately 1599.06 bytes/request across 210003 total requests. RSS was 123678720 → 439824384; expired_keys increased by 374, showing a small physical-expiry tail from the prior point. No AOF rewrite was active at the endpoints, but cumulative rewrites increased 11 → 13; the window was not necessarily rewrite-free.

6000/s is still not a measured limit. The next planned point was 9000/s. At approximately 1.6 KB/request, 300000 warm-up plus measured requests were expected to add about 480 MB of state, keeping CPU, TTL and durability settings unchanged. Natural cancellation and TTL cooldown were to finish before preparing the new fixture.

The [4000-point lossless raw archive](soldout-b4000r1-20260907.tar.gz) includes measured, warm-up, preparation and natural-recovery records. Other points were archived separately after recovery.

6000-point cooldown: 320 orders were CANCELLED by 16:49:20. Full SQL at 16:49:56 confirmed inventory 2000000, quota 10 per activity, 320 entries in each ledger, zero errors or unfinished work, and MQ/handoff zero. At 16:49:20/16:50:39, Redis used_memory was 96651736/91890552 bytes, expired_keys 1388074/1396987, and AOF rewrite/scheduled zero. Background CPU is recorded separately. Small physical-expiry tails and RSS differences are retained. Originals: `recovery_b6000r1_76c2931_20260907T163035Z_final_1788799796582807000.txt` and `recovery_mq_b6000r1_76c2931_20260907T163035Z_1788799814242924000.txt`.

[6000-point lossless raw archive](soldout-b6000r1-20260907.tar.gz).

<a id="9000s-点数量正确计时有异常"></a>

## 9000/s point: correct counts, anomalous timing

Measured CityBuddy remains `76c293178923bf78e747ab1ba9590e6348108ad8`. Label: `b9000r1_76c2931_20260907T165208Z`. Measured HTTP window: 16:54:20.193317–16:54:50.189658 UTC. All 270001 requests returned 409/EXHAUSTED/replay=false; failures, drops and interruptions were zero. The 30001 warm-up requests are separate. Before/after SQL retain 320 preparation orders, inventory 1999680, 320 ledger entries, zero binding or orphan errors, and no transaction-MQ backlog.

Two measured http_req_duration samples are negative (-0.987848/-0.882348 ms), with sending values -1.476806/-1.413223 ms at 16:54:42.531515291/16:54:42.531548166. Both used HTTP/1.1, returned 409 and had zero connection duration. Raw linear p99 is 13.441083 ms, but this latency record is not a clean result; samples are neither removed nor clamped to zero. The pinned image actually contains k6 v2.2.0, Go 1.26.5, linux/arm64. That version's [HTTP tracer](https://github.com/grafana/k6/blob/v2.2.0/lib/netext/httpext/tracer.go) calculates phase durations from UnixNano differences; a [similar upstream issue](https://github.com/grafana/k6/issues/1872) does not establish the cause here. Timing anomalies are not throughput overload and do not establish browser interference.

The six measured resource samples peak at Commerce 186.38%, k6 162.91%, Redis 43.06%, MySQL 2.55% and Broker 116.55%. First/last in-window throttle counts are both 8/271264 microseconds. There is still no evidence of sustained Commerce four-CPU saturation. Redis used_memory was 88247096 → 584541048 bytes, up 496293952, approximately 1654.30 bytes/request over 300002 total requests. RSS was 132964352 → 602361856; expired_keys 1404958 → 1405872. Raw JSON is approximately 2.4 GB and writing continued until 16:57:14. Post-processing CPU is outside the measured HTTP window.

The natural-recovery snapshot at 17:10:51 showed 320 CANCELLED orders, Redis used_memory 75937976 bytes, expired_keys 2304453 and AOF rewrite zero. Full SQL at 17:15:02 confirmed inventory 2000000, quota 10 for each of 32 activities, 320 creation and 320 cancellation ledger entries, and zero binding or incomplete-work errors; both MQ groups and handoff were zero. Originals: `recovery_b9000r1_76c2931_20260907T165208Z_final_1788801302425040000.txt` and `recovery_mq_b9000r1_76c2931_20260907T165208Z_1788801584785252000.txt`.

9000/s was not a throughput-overload point. The next approach used a short continuous ladder to bracket the range, followed by independent fixed-load confirmation. An exploratory run sharing fixtures and accumulating Redis state across stages is not merged with the independent points as a formal capacity comparison. The change from 3,000 to 9,000 is not a code-optimization gain.

[9000-point lossless raw archive](soldout-b9000r1-20260907.tar.gz), including anomalous timing samples and natural recovery.
