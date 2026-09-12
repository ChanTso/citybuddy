<a id="交易工作点与最终成单对照"></a>

# Transaction work points and final order-creation comparison

CityBuddy baseline and ordinary-payment/sold-out revision: `76c293178923bf78e747ab1ba9590e6348108ad8`. Initial combined consumer adjustment: `2093b1355397ea330e9e03f806454722db1a2246`. Final order baseline: `fe60d7c3c95eb09399a95562745e96ff5cc386c7`; final optimized revision: `9bb08c6087d9a73befb909b021debb30a9fb9aa1`. Later result-document commits are not measured business revisions. Environment: local M4, Docker 8 CPUs / 14 GB, Commerce 4 CPUs. Each scenario has its own fixture and window; login and data preparation are excluded.

| Business question | Verified result | Boundary and report |
|---|---|---|
| Sold-out entry | 6000/s, 180002 correct rejections in 30 seconds, p99 4.92 ms, zero drops | [Fixed series](soldout_fixed_series_20260907.md): one work point, not the limit or long-run steady state |
| Earlier order work point | Two 160/s × 300-second runs each completed 48001 orders; 200/s accumulated backlog | [160 repeat](orders_a160r3_76c2931_20260907T141442Z_report.md), [200 baseline](orders_a200r1_76c2931_20260907T145922Z_report.md) |
| Final order comparison | Same 200/s × 300 seconds, all 60001 orders completed with zero drops; wait p99 9.26 → 1.70 seconds | [Final eight-point report](seckill_order_final_comparison_20260908.md): common 1 GiB buffer pool; the repeat completed all 59963 orders but had 37 unissued startup iterations, so these are not two zero-drop passes |
| Overload and dispatch recovery | Final 400/s × 300 seconds accumulated backlog; all 120001 orders eventually completed; order/dispatch backlog cleared about 3 minutes after input stopped | [Final report](seckill_order_final_comparison_20260908.md): one negative receiving-time sample; not certified HTTP capacity or stable 400/s |
| Ordinary order/payment | Two 20 flows/s × 120-second runs completed 4801 flows, with full-flow p99 about 84 ms and correct SQL state | [Ordinary transaction baseline](order_payment_baseline_20260907.md): simulated payment, not maximum throughput for this path |

Higher sold-out points and generator calibration remain in the [coarse probe](soldout_probe_20260907.md), [fixed 14k point](soldout_thousand_series_20260907.md), [VU/output calibration](soldout_generator_calibration_20260907.md) and raw archives. They describe measurement limits, not the Commerce capacity ceiling. Each report identifies its measured SHA.

Business correctness comes from authoritative SQL; counts and durations come from original k6 output and database timestamps. Rejection QPS is not reported as order TPS. Timing anomalies and failed observations remain recorded, and machine specifications or durability requirements were not relaxed to obtain the numbers.

The earlier batch/delay comparison with a 128 MiB buffer pool, 91.12 → 5.34 seconds, remains a [historical record](seckill_order_consumer_comparison_20260908.md) and is not spliced into the final 1 GiB series. The final single-hotspot maximum capacity remains undetermined; sold-out peak exploration has stopped.
